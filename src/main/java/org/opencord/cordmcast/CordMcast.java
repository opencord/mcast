/*
 * Copyright 2016-present Open Networking Foundation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.opencord.cordmcast;

import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.felix.scr.annotations.Activate;
import org.apache.felix.scr.annotations.Component;
import org.apache.felix.scr.annotations.Deactivate;
import org.apache.felix.scr.annotations.Modified;
import org.apache.felix.scr.annotations.Property;
import org.apache.felix.scr.annotations.Reference;
import org.apache.felix.scr.annotations.ReferenceCardinality;
import org.onlab.packet.Ethernet;
import org.onlab.packet.IpAddress;
import org.onlab.packet.VlanId;
import org.onlab.util.KryoNamespace;
import org.onosproject.cfg.ComponentConfigService;
import org.onosproject.cluster.ClusterService;
import org.onosproject.cluster.LeadershipService;
import org.onosproject.cluster.NodeId;
import org.onosproject.core.ApplicationId;
import org.onosproject.core.CoreService;
import org.onosproject.mastership.MastershipService;
import org.onosproject.mcast.api.McastEvent;
import org.onosproject.mcast.api.McastListener;
import org.onosproject.mcast.api.McastRoute;
import org.onosproject.mcast.api.MulticastRouteService;
import org.onosproject.net.ConnectPoint;
import org.onosproject.net.DeviceId;
import org.onosproject.net.HostId;
import org.onosproject.net.PortNumber;
import org.onosproject.net.config.ConfigFactory;
import org.onosproject.net.config.NetworkConfigEvent;
import org.onosproject.net.config.NetworkConfigListener;
import org.onosproject.net.config.NetworkConfigRegistry;
import org.onosproject.net.config.basics.McastConfig;
import org.onosproject.net.config.basics.SubjectFactories;
import org.onosproject.net.device.DeviceService;
import org.onosproject.net.flow.DefaultTrafficSelector;
import org.onosproject.net.flow.DefaultTrafficTreatment;
import org.onosproject.net.flow.FlowRuleService;
import org.onosproject.net.flow.TrafficSelector;
import org.onosproject.net.flowobjective.DefaultForwardingObjective;
import org.onosproject.net.flowobjective.DefaultNextObjective;
import org.onosproject.net.flowobjective.DefaultObjectiveContext;
import org.onosproject.net.flowobjective.FlowObjectiveService;
import org.onosproject.net.flowobjective.ForwardingObjective;
import org.onosproject.net.flowobjective.NextObjective;
import org.onosproject.net.flowobjective.Objective;
import org.onosproject.net.flowobjective.ObjectiveContext;
import org.onosproject.net.flowobjective.ObjectiveError;
import org.onosproject.store.serializers.KryoNamespaces;
import org.onosproject.store.service.ConsistentMap;
import org.onosproject.store.service.Serializer;
import org.onosproject.store.service.StorageService;
import org.onosproject.store.service.Versioned;
import org.opencord.cordconfig.CordConfigService;
import org.opencord.cordconfig.access.AccessDeviceData;
import org.osgi.service.component.ComponentContext;
import org.slf4j.Logger;

import java.util.Dictionary;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import static com.google.common.base.Strings.isNullOrEmpty;
import static java.util.concurrent.Executors.newSingleThreadScheduledExecutor;
import static org.onlab.util.Tools.get;
import static org.onlab.util.Tools.groupedThreads;
import static org.slf4j.LoggerFactory.getLogger;


/**
 * CORD multicast provisioning application. Operates by listening to
 * events on the multicast rib and provisioning groups to program multicast
 * flows on the dataplane.
 */
@Component(immediate = true)
public class CordMcast {
    private static final String APP_NAME = "org.opencord.cordmcast";

    private final Logger log = getLogger(getClass());

    private static final int DEFAULT_PRIORITY = 500;
    private static final short DEFAULT_MCAST_VLAN = 4000;
    private static final boolean DEFAULT_VLAN_ENABLED = true;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected MulticastRouteService mcastService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected FlowObjectiveService flowObjectiveService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected CoreService coreService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected ComponentConfigService componentConfigService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected CordConfigService cordConfigService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected NetworkConfigRegistry networkConfig;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected FlowRuleService flowRuleService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected StorageService storageService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected MastershipService mastershipService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    public DeviceService deviceService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    private ClusterService clusterService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    private LeadershipService leadershipService;

    protected McastListener listener = new InternalMulticastListener();
    private InternalNetworkConfigListener configListener =
            new InternalNetworkConfigListener();

    private ConsistentMap<NextKey, NextContent> groups;

    private ApplicationId appId;
    private ApplicationId coreAppId;
    private short mcastVlan = DEFAULT_MCAST_VLAN;

    @Property(name = "vlanEnabled", boolValue = DEFAULT_VLAN_ENABLED,
            label = "Use vlan for multicast traffic?")
    private boolean vlanEnabled = DEFAULT_VLAN_ENABLED;

    @Property(name = "priority", intValue = DEFAULT_PRIORITY,
            label = "Priority for multicast rules")
    private int priority = DEFAULT_PRIORITY;

    private static final Class<McastConfig> CORD_MCAST_CONFIG_CLASS =
            McastConfig.class;

    private ConfigFactory<ApplicationId, McastConfig> cordMcastConfigFactory =
            new ConfigFactory<ApplicationId, McastConfig>(
                    SubjectFactories.APP_SUBJECT_FACTORY, CORD_MCAST_CONFIG_CLASS, "multicast") {
                @Override
                public McastConfig createConfig() {
                    return new McastConfig();
                }
            };

    // lock to synchronize local operations
    private final Lock mcastLock = new ReentrantLock();
    private void mcastLock() {
        mcastLock.lock();
    }
    private void mcastUnlock() {
        mcastLock.unlock();
    }
    private ExecutorService eventExecutor;

    @Activate
    public void activate(ComponentContext context) {
        componentConfigService.registerProperties(getClass());
        modified(context);

        appId = coreService.registerApplication(APP_NAME);
        coreAppId = coreService.registerApplication(CoreService.CORE_APP_NAME);

        eventExecutor = newSingleThreadScheduledExecutor(groupedThreads("cord/mcast",
                                                                        "events-mcast-%d", log));

        KryoNamespace.Builder groupsKryo = new KryoNamespace.Builder()
                .register(KryoNamespaces.API)
                .register(NextKey.class)
                .register(NextContent.class);
        groups = storageService
                .<NextKey, NextContent>consistentMapBuilder()
                .withName("cord-mcast-groups-store")
                .withSerializer(Serializer.using(groupsKryo.build("CordMcast-Groups")))
                .build();

        networkConfig.registerConfigFactory(cordMcastConfigFactory);
        networkConfig.addListener(configListener);
        mcastService.addListener(listener);

        mcastService.getRoutes().stream()
                .map(r -> new ImmutablePair<>(r, mcastService.sinks(r)))
                .filter(pair -> pair.getRight() != null && !pair.getRight().isEmpty())
                .forEach(pair -> pair.getRight().forEach(sink -> addSink(pair.getLeft(),
                                                                          sink)));

        McastConfig config = networkConfig.getConfig(coreAppId, CORD_MCAST_CONFIG_CLASS);
        updateConfig(config);

        log.info("Started");
    }

    @Deactivate
    public void deactivate() {
        componentConfigService.unregisterProperties(getClass(), false);
        mcastService.removeListener(listener);
        networkConfig.removeListener(configListener);
        networkConfig.unregisterConfigFactory(cordMcastConfigFactory);
        eventExecutor.shutdown();
        clearGroups();
        groups.destroy();
        log.info("Stopped");
    }

    public void clearGroups() {
        mcastLock();
        try {
            groups.keySet().forEach(groupInfo -> {
                if (!isLocalLeader(groupInfo.getDevice())) {
                    return;
                }
                NextContent next = groups.get(groupInfo).value();

                if (next != null) {
                    ObjectiveContext context = new DefaultObjectiveContext(
                            (objective) -> log.debug("Successfully remove {}",
                                                     groupInfo.group),
                            (objective, error) -> log.warn("Failed to remove {}: {}",
                                                           groupInfo.group, error));
                    // remove the flow rule
                    flowObjectiveService.forward(groupInfo.getDevice(), fwdObject(next.getNextId(),
                                                                                  groupInfo.group).remove(context));
                    // remove all ports from the group
                    next.getOutPorts().stream().forEach(portNumber ->
                        flowObjectiveService.next(groupInfo.getDevice(), nextObject(next.getNextId(),
                                                                                    portNumber,
                                                                                    NextType.RemoveFromExisting,
                                                                                    groupInfo.group))
                    );

                }
            });
            groups.clear();
        } finally {
            mcastUnlock();
        }
    }

    private VlanId multicastVlan() {
        return VlanId.vlanId(mcastVlan);
    }

    private VlanId assignedVlan() {
        return vlanEnabled ? multicastVlan() : VlanId.NONE;
    }

    @Modified
    public void modified(ComponentContext context) {
        Dictionary<?, ?> properties = context != null ? context.getProperties() : new Properties();

        try {
            String s = get(properties, "vlanEnabled");
            vlanEnabled = isNullOrEmpty(s) ? DEFAULT_VLAN_ENABLED : Boolean.parseBoolean(s.trim());

            s = get(properties, "priority");
            priority = isNullOrEmpty(s) ? DEFAULT_PRIORITY : Integer.parseInt(s.trim());

        } catch (Exception e) {
            log.error("Unable to parse configuration parameter.", e);
            vlanEnabled = false;
            priority = DEFAULT_PRIORITY;
        }
    }

    private class InternalMulticastListener implements McastListener {
        @Override
        public void event(McastEvent event) {
            eventExecutor.execute(() -> {
                switch (event.type()) {
                    case ROUTE_ADDED:
                    case ROUTE_REMOVED:
                    case SOURCES_ADDED:
                        break;
                    case SINKS_ADDED:
                        addSinks(event);
                        break;
                    case SINKS_REMOVED:
                        removeSinks(event);
                        break;
                    default:
                        log.warn("Unknown mcast event {}", event.type());
                }
            });
        }
    }

    /**
     * Processes previous, and new sinks then finds the sinks to be removed.
     * @param prevSinks the previous sinks to be evaluated
     * @param newSinks the new sinks to be evaluated
     * @returnt the set of the sinks to be removed
     */
    private Set<ConnectPoint> getSinksToBeRemoved(Map<HostId, Set<ConnectPoint>> prevSinks,
                                                  Map<HostId, Set<ConnectPoint>> newSinks) {
        return getSinksToBeProcessed(prevSinks, newSinks);
    }


    /**
     * Processes previous, and new sinks then finds the sinks to be added.
     * @param newSinks the new sinks to be processed
     * @param allPrevSinks all previous sinks
     * @return the set of the sinks to be added
     */
    private Set<ConnectPoint> getSinksToBeAdded(Map<HostId, Set<ConnectPoint>> newSinks,
                                                Map<HostId, Set<ConnectPoint>> allPrevSinks) {
        return getSinksToBeProcessed(newSinks, allPrevSinks);
    }

    /**
     * Gets single-homed sinks that are in set1 but not in set2.
     * @param sinkSet1 the first sink map
     * @param sinkSet2 the second sink map
     * @return a set containing all the single-homed sinks found in set1 but not in set2
     */
    private Set<ConnectPoint> getSinksToBeProcessed(Map<HostId, Set<ConnectPoint>> sinkSet1,
                                                    Map<HostId, Set<ConnectPoint>> sinkSet2) {
        final Set<ConnectPoint> sinksToBeProcessed = Sets.newHashSet();
        sinkSet1.forEach(((hostId, connectPoints) -> {
            if (HostId.NONE.equals(hostId)) {
                //assume all connect points associated with HostId.NONE are single homed sinks
                sinksToBeProcessed.addAll(connectPoints);
                return;
            }
        }));
        Set<ConnectPoint> singleHomedSinksOfSet2 = sinkSet2.get(HostId.NONE) == null ?
                Sets.newHashSet() :
                sinkSet2.get(HostId.NONE);
        return Sets.difference(sinksToBeProcessed, singleHomedSinksOfSet2);
    };


    private void removeSinks(McastEvent event) {
        mcastLock();
        try {
            Set<ConnectPoint> sinksToBeRemoved = getSinksToBeRemoved(event.prevSubject().sinks(),
                                                                     event.subject().sinks());
            sinksToBeRemoved.forEach(sink -> removeSink(event.subject().route().group(), sink));
        } finally {
            mcastUnlock();
        }
    }

    private void removeSink(IpAddress group, ConnectPoint sink) {
        if (!isLocalLeader(sink.deviceId())) {
            log.debug("Not the leader of {}. Skip sink_removed event for the sink {} and group {}",
                      sink.deviceId(), sink, group);
            return;
        }

        Optional<AccessDeviceData> oltInfo = cordConfigService.getAccessDevice(sink.deviceId());

        if (!oltInfo.isPresent()) {
            log.warn("Unknown OLT device : {}", sink.deviceId());
            return;
        }

        log.debug("Removing sink {} from the group {}", sink, group);

        NextKey key = new NextKey(sink.deviceId(), group);
        groups.computeIfPresent(key, (k, v) -> {
            flowObjectiveService.next(sink.deviceId(), nextObject(v.getNextId(), sink.port(),
                                                                  NextType.RemoveFromExisting, group));

            Set<PortNumber> outPorts = Sets.newHashSet(v.getOutPorts());
            outPorts.remove(sink.port());

            if (outPorts.isEmpty()) {
                // this is the last sink
                ObjectiveContext context = new DefaultObjectiveContext(
                        (objective) -> log.debug("Successfully remove {} on {}",
                                                 group, sink),
                        (objective, error) -> log.warn("Failed to remove {} on {}: {}",
                                                       group, sink, error));
                ForwardingObjective fwdObj = fwdObject(v.getNextId(), group).remove(context);
                flowObjectiveService.forward(sink.deviceId(), fwdObj);
            }
            // remove the whole entity if no out port exists in the port list
            return outPorts.isEmpty() ? null : new NextContent(v.getNextId(),
                                                               ImmutableSet.copyOf(outPorts));
        });
    }

    private void addSinks(McastEvent event) {
        mcastLock();
        try {
            Set<ConnectPoint> sinksToBeAdded = getSinksToBeAdded(event.subject().sinks(),
                                                                 event.prevSubject().sinks());
            sinksToBeAdded.forEach(sink -> addSink(event.subject().route(), sink));
        } finally {
            mcastUnlock();
        }
    }

    private void addSink(McastRoute route, ConnectPoint sink) {
        if (!isLocalLeader(sink.deviceId())) {
            log.debug("Not the leader of {}. Skip sink_added event for the sink {} and group {}",
                      sink.deviceId(), sink, route.group());
            return;
        }

        Optional<AccessDeviceData> oltInfo = cordConfigService.getAccessDevice(sink.deviceId());

        if (!oltInfo.isPresent()) {
            log.warn("Unknown OLT device : {}", sink.deviceId());
            return;
        }

        log.debug("Adding sink {} to the group {}", sink, route.group());

        NextKey key = new NextKey(sink.deviceId(), route.group());
        NextObjective newNextObj;

        boolean theFirstSinkOfGroup = false;
        if (!groups.containsKey(key)) {
            // First time someone request this mcast group via this device
            Integer nextId = flowObjectiveService.allocateNextId();
            newNextObj = nextObject(nextId, sink.port(), NextType.AddNew, route.group());
            // Store the new port
            groups.put(key, new NextContent(nextId, ImmutableSet.of(sink.port())));
            theFirstSinkOfGroup = true;
        } else {
            // This device already serves some subscribers of this mcast group
            Versioned<NextContent> nextObj = groups.get(key);
            if (nextObj.value().getOutPorts().contains(sink.port())) {
                log.info("Group {} already serves the sink connected to {}", route.group(), sink);
                return;
            }
            newNextObj = nextObject(nextObj.value().getNextId(), sink.port(),
                                    NextType.AddToExisting, route.group());
            // add new port to the group
            Set<PortNumber> outPorts = Sets.newHashSet(nextObj.value().getOutPorts());
            outPorts.add(sink.port());
            groups.put(key, new NextContent(newNextObj.id(), ImmutableSet.copyOf(outPorts)));
        }

        ObjectiveContext context = new DefaultObjectiveContext(
                (objective) -> log.debug("Successfully add {} on {}/{}, vlan {}",
                                         route.group(), sink.deviceId(), sink.port().toLong(),
                                         assignedVlan()),
                (objective, error) -> {
                    log.warn("Failed to add {} on {}/{}, vlan {}: {}",
                             route.group(), sink.deviceId(), sink.port().toLong(), assignedVlan(),
                             error);
                });

        flowObjectiveService.next(sink.deviceId(), newNextObj);

        if (theFirstSinkOfGroup) {
            // create the necessary flow rule if this is the first sink request for the group
            // on this device
            flowObjectiveService.forward(sink.deviceId(), fwdObject(newNextObj.id(),
                                                                    route.group()).add(context));
        }
    }

    private class InternalNetworkConfigListener implements NetworkConfigListener {
        @Override
        public void event(NetworkConfigEvent event) {
            eventExecutor.execute(() -> {
                switch (event.type()) {

                    case CONFIG_ADDED:
                    case CONFIG_UPDATED:
                        if (event.configClass().equals(CORD_MCAST_CONFIG_CLASS)) {
                            McastConfig config = networkConfig.getConfig(coreAppId, CORD_MCAST_CONFIG_CLASS);
                            if (config != null) {
                                //TODO: Simply remove flows/groups, hosts will response period query
                                // and re-sent IGMP report, so the flows can be rebuild.
                                // However, better to remove and re-add mcast flow rules here
                                if (mcastVlan != config.egressVlan().toShort() && vlanEnabled) {
                                    clearGroups();
                                }
                                updateConfig(config);
                            }
                        }
                        break;
                    case CONFIG_REGISTERED:
                    case CONFIG_UNREGISTERED:
                    case CONFIG_REMOVED:
                        break;
                    default:
                        break;
                }
            });
        }
    }

    private void updateConfig(McastConfig config) {
        if (config == null) {
            return;
        }
        log.debug("multicast config received: {}", config);

        if (config.egressVlan() != null) {
            mcastVlan = config.egressVlan().toShort();
        }
    }

    private class NextKey {
        private DeviceId device;
        private IpAddress group;

        public NextKey(DeviceId deviceId, IpAddress groupAddress) {
            device = deviceId;
            group = groupAddress;
        }

        public DeviceId getDevice() {
            return device;
        }

        public int hashCode() {
            return Objects.hash(this.device, this.group);
        }

        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            } else if (!(obj instanceof NextKey)) {
                return false;
            } else {
                NextKey that = (NextKey) obj;
                return this.getClass() == that.getClass() &&
                        Objects.equals(this.device, that.device) &&
                        Objects.equals(this.group, that.group);
            }
        }
    }

    private class NextContent {
        private Integer nextId;
        private Set<PortNumber> outPorts;

        public NextContent(Integer nextId, Set<PortNumber> outPorts) {
            this.nextId = nextId;
            this.outPorts = outPorts;
        }

        public Integer getNextId() {
            return nextId;
        }

        public Set<PortNumber> getOutPorts() {
            return ImmutableSet.copyOf(outPorts);
        }

        public int hashCode() {
            return Objects.hash(this.nextId, this.outPorts);
        }

        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            } else if (!(obj instanceof NextContent)) {
                return false;
            } else {
                NextContent that = (NextContent) obj;
                return this.getClass() == that.getClass() &&
                        Objects.equals(this.nextId, that.nextId) &&
                        Objects.equals(this.outPorts, that.outPorts);
            }
        }
    }

    private enum NextType { AddNew, AddToExisting, Remove, RemoveFromExisting };

    private NextObjective nextObject(Integer nextId, PortNumber port,
                                     NextType nextType, IpAddress mcastIp) {

        // Build the meta selector with the fwd objective info
        TrafficSelector.Builder metadata = DefaultTrafficSelector.builder()
                .matchIPDst(mcastIp.toIpPrefix());

        if (vlanEnabled) {
            metadata.matchVlanId(multicastVlan());
        }

        DefaultNextObjective.Builder build = DefaultNextObjective.builder()
                .fromApp(appId)
                .addTreatment(DefaultTrafficTreatment.builder().setOutput(port).build())
                .withType(NextObjective.Type.BROADCAST)
                .withId(nextId)
                .withMeta(metadata.build());

        ObjectiveContext content = new ObjectiveContext() {
            @Override
            public void onSuccess(Objective objective) {
                log.debug("Next Objective {} installed", objective.id());
            }

            @Override
            public void onError(Objective objective, ObjectiveError error) {
                log.debug("Next Objective {} failed, because {}",
                          objective.id(),
                          error);
            }
        };

        switch (nextType) {
            case AddNew:
                return build.add(content);
            case AddToExisting:
                return build.addToExisting(content);
            case Remove:
                return build.remove(content);
            case RemoveFromExisting:
                return build.removeFromExisting(content);
            default:
                return null;
        }
    }

    private ForwardingObjective.Builder fwdObject(int nextId, IpAddress mcastIp) {
        TrafficSelector.Builder mcast = DefaultTrafficSelector.builder()
                .matchEthType(Ethernet.TYPE_IPV4)
                .matchIPDst(mcastIp.toIpPrefix());

        //build the meta selector
        TrafficSelector.Builder metabuilder = DefaultTrafficSelector.builder();
        if (vlanEnabled) {
            metabuilder.matchVlanId(multicastVlan());
        }

        ForwardingObjective.Builder fwdBuilder = DefaultForwardingObjective.builder()
                .fromApp(appId)
                .nextStep(nextId)
                .makePermanent()
                .withFlag(ForwardingObjective.Flag.SPECIFIC)
                .withPriority(priority)
                .withSelector(mcast.build())
                .withMeta(metabuilder.build());

        return fwdBuilder;
    }

    // Custom-built function, when the device is not available we need a fallback mechanism
    private boolean isLocalLeader(DeviceId deviceId) {
        if (!mastershipService.isLocalMaster(deviceId)) {
            // When the device is available we just check the mastership
            if (deviceService.isAvailable(deviceId)) {
                return false;
            }
            // Fallback with Leadership service - device id is used as topic
            NodeId leader = leadershipService.runForLeadership(
                    deviceId.toString()).leaderNodeId();
            // Verify if this node is the leader
            return clusterService.getLocalNode().id().equals(leader);
        }
        return true;
    }

}


