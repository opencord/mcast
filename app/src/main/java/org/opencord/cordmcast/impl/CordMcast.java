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
package org.opencord.cordmcast.impl;

import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import org.apache.commons.lang3.tuple.ImmutablePair;
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
import org.onosproject.net.Device;
import org.onosproject.net.DeviceId;
import org.onosproject.net.HostId;
import org.onosproject.net.PortNumber;
import org.onosproject.net.config.ConfigFactory;
import org.onosproject.net.config.NetworkConfigEvent;
import org.onosproject.net.config.NetworkConfigListener;
import org.onosproject.net.config.NetworkConfigRegistry;
import org.onosproject.net.config.basics.McastConfig;
import org.onosproject.net.config.basics.SubjectFactories;
import org.onosproject.net.device.DeviceEvent;
import org.onosproject.net.device.DeviceListener;
import org.onosproject.net.device.DeviceService;
import org.onosproject.net.flow.DefaultTrafficSelector;
import org.onosproject.net.flow.DefaultTrafficTreatment;
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
import org.onosproject.net.group.GroupService;
import org.onosproject.store.serializers.KryoNamespaces;
import org.onosproject.store.service.ConsistentMap;
import org.onosproject.store.service.Serializer;
import org.onosproject.store.service.StorageService;
import org.onosproject.store.service.Versioned;
import org.opencord.cordmcast.CordMcastService;
import org.opencord.cordmcast.CordMcastStatisticsService;
import org.opencord.sadis.SadisService;
import org.opencord.sadis.SubscriberAndDeviceInformation;
import org.osgi.service.component.ComponentContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Modified;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.osgi.service.component.annotations.ReferencePolicy;
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
import java.util.function.Consumer;

import static com.google.common.base.Strings.isNullOrEmpty;
import static java.util.concurrent.Executors.newSingleThreadScheduledExecutor;
import static org.onlab.util.Tools.get;
import static org.onlab.util.Tools.groupedThreads;
import static org.opencord.cordmcast.impl.OsgiPropertyConstants.*;
import static org.slf4j.LoggerFactory.getLogger;


/**
 * CORD multicast provisioning application. Operates by listening to
 * events on the multicast rib and provisioning groups to program multicast
 * flows on the dataplane.
 */
@Component(immediate = true,
        property = {
                VLAN_ENABLED + ":Boolean=" + DEFAULT_VLAN_ENABLED,
                PRIORITY + ":Integer=" + DEFAULT_PRIORITY,
        })
public class CordMcast implements CordMcastService {
    private static final String MCAST_NOT_RUNNING = "Multicast is not running.";
    private static final String SADIS_NOT_RUNNING = "Sadis is not running.";
    private static final String APP_NAME = "org.opencord.mcast";

    private final Logger log = getLogger(getClass());

    private static final int DEFAULT_PRIORITY = 500;
    private static final short DEFAULT_MCAST_VLAN = 4000;

    @Reference(cardinality = ReferenceCardinality.OPTIONAL,
            bind = "bindMcastRouteService",
            unbind = "unbindMcastRouteService",
            policy = ReferencePolicy.DYNAMIC)
    protected volatile MulticastRouteService mcastService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected FlowObjectiveService flowObjectiveService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected CoreService coreService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected ComponentConfigService componentConfigService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected NetworkConfigRegistry networkConfig;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected StorageService storageService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected MastershipService mastershipService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    public DeviceService deviceService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    private ClusterService clusterService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    private LeadershipService leadershipService;

    @Reference(cardinality = ReferenceCardinality.OPTIONAL,
            bind = "bindSadisService",
            unbind = "unbindSadisService",
            policy = ReferencePolicy.DYNAMIC)
    protected volatile SadisService sadisService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected CordMcastStatisticsService cordMcastStatisticsService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected GroupService groupService;

    protected McastListener listener = new InternalMulticastListener();

    private InternalNetworkConfigListener configListener =
            new InternalNetworkConfigListener();

    private ConsistentMap<NextKey, NextContent> groups;

    private ApplicationId appId;
    private ApplicationId coreAppId;
    private short mcastVlan = DEFAULT_MCAST_VLAN;
    private VlanId mcastInnerVlan = VlanId.NONE;

    /**
     * Whether to use VLAN for multicast traffic.
     **/
    private boolean vlanEnabled = DEFAULT_VLAN_ENABLED;

    /**
     * Priority for multicast rules.
     **/
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

    //Device listener to purge groups upon device disconnection.
    private DeviceListener deviceListener = new InternalDeviceListener();

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
        if (mcastService != null) {
            mcastService.addListener(listener);
            mcastService.getRoutes().stream()
                    .map(r -> new ImmutablePair<>(r, mcastService.sinks(r)))
                    .filter(pair -> pair.getRight() != null && !pair.getRight().isEmpty())
                    .forEach(pair -> pair.getRight().forEach(sink -> addSink(pair.getLeft(),
                            sink)));
        } else {
            log.warn(MCAST_NOT_RUNNING);
        }
        McastConfig config = networkConfig.getConfig(coreAppId, CORD_MCAST_CONFIG_CLASS);
        updateConfig(config);
        deviceService.addListener(deviceListener);
        log.info("Started");
    }

    @Modified
    public void modified(ComponentContext context) {
        Dictionary<?, ?> properties = context != null ? context.getProperties() : new Properties();

        String s = get(properties, VLAN_ENABLED);
        vlanEnabled = isNullOrEmpty(s) ? DEFAULT_VLAN_ENABLED : Boolean.parseBoolean(s.trim());

        try {
            s = get(properties, PRIORITY);
            priority = isNullOrEmpty(s) ? DEFAULT_PRIORITY : Integer.parseInt(s.trim());
        } catch (NumberFormatException ne) {
            log.error("Unable to parse configuration parameter for priority", ne);
            priority = DEFAULT_PRIORITY;
        }
        feedStatsServiceWithVlanConfigValues();
    }

    @Deactivate
    public void deactivate() {
        deviceService.removeListener(deviceListener);
        componentConfigService.unregisterProperties(getClass(), false);
        if (mcastService != null) {
            mcastService.removeListener(listener);
        }
        networkConfig.removeListener(configListener);
        networkConfig.unregisterConfigFactory(cordMcastConfigFactory);
        eventExecutor.shutdown();
        eventExecutor = null;
        log.info("Stopped");
    }

    protected void bindSadisService(SadisService service) {
        sadisService = service;
        log.info("Sadis-service binds to onos.");
    }

    protected void unbindSadisService(SadisService service) {
        sadisService = null;
        log.info("Sadis-service unbinds from onos.");
    }

    protected void bindMcastRouteService(MulticastRouteService service) {
        mcastService = service;
        mcastService.addListener(listener);
        log.info("Multicast route service binds to onos.");
    }

    protected void unbindMcastRouteService(MulticastRouteService service) {
        service.removeListener(listener);
        mcastService = null;
        log.info("Multicast route service unbinds from onos.");
    }

    /**
     * Updates the stats service with current VLAN config values.
     */
    private void feedStatsServiceWithVlanConfigValues() {
        cordMcastStatisticsService.setVlanValue(assignedVlan());
        cordMcastStatisticsService.setInnerVlanValue(assignedInnerVlan());
    }

    private void clearGroups() {
        mcastLock();
        try {
            groups.keySet().forEach(groupInfo -> {
                NextContent next = groups.get(groupInfo).value();
                if (!isLocalLeader(groupInfo.getDevice())) {
                    return;
                }
                if (next != null) {
                    //On Success of removing the fwd objective we remove also the group.
                    Consumer<Objective> onSuccess = (objective) -> {
                        log.debug("Successfully removed fwd objective for {} on {}, " +
                                        "removing next objective {}", groupInfo.group,
                                groupInfo.getDevice(), next.getNextId());
                        eventExecutor.submit(() -> flowObjectiveService.next(groupInfo.getDevice(),
                                nextObject(next.getNextId(),
                                        null,
                                        NextType.Remove, groupInfo.group)));
                    };

                    ObjectiveContext context =
                            new DefaultObjectiveContext(onSuccess, (objective, error) ->
                                    log.warn("Failed to remove {} on {}: {}",
                                            groupInfo.group, next.getNextId(), error));
                    // remove the flow rule
                    flowObjectiveService.forward(groupInfo.getDevice(),
                            fwdObject(next.getNextId(),
                                    groupInfo.group).remove(context));

                }
            });
        } finally {
            mcastUnlock();
        }
    }

    private VlanId multicastVlan() {
        return VlanId.vlanId(mcastVlan);
    }

    protected VlanId assignedVlan() {
        return vlanEnabled ? multicastVlan() : VlanId.NONE;
    }

    protected VlanId assignedInnerVlan() {
        return vlanEnabled ? mcastInnerVlan : VlanId.NONE;
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
     *
     * @param prevSinks the previous sinks to be evaluated
     * @param newSinks  the new sinks to be evaluated
     * @returnt the set of the sinks to be removed
     */
    private Set<ConnectPoint> getSinksToBeRemoved(Map<HostId, Set<ConnectPoint>> prevSinks,
                                                  Map<HostId, Set<ConnectPoint>> newSinks) {
        return getSinksToBeProcessed(prevSinks, newSinks);
    }


    /**
     * Processes previous, and new sinks then finds the sinks to be added.
     *
     * @param newSinks     the new sinks to be processed
     * @param allPrevSinks all previous sinks
     * @return the set of the sinks to be added
     */
    private Set<ConnectPoint> getSinksToBeAdded(Map<HostId, Set<ConnectPoint>> newSinks,
                                                Map<HostId, Set<ConnectPoint>> allPrevSinks) {
        return getSinksToBeProcessed(newSinks, allPrevSinks);
    }

    /**
     * Gets single-homed sinks that are in set1 but not in set2.
     *
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
    }


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

        Optional<SubscriberAndDeviceInformation> oltInfo = getSubscriberAndDeviceInformation(sink.deviceId());

        if (!oltInfo.isPresent()) {
            log.warn("Unknown OLT device : {}", sink.deviceId());
            return;
        }

        log.debug("Removing sink {} from the group {}", sink, group);

        NextKey key = new NextKey(sink.deviceId(), group);
        groups.computeIfPresent(key, (k, v) -> {

            Set<PortNumber> outPorts = Sets.newHashSet(v.getOutPorts());
            outPorts.remove(sink.port());

            if (outPorts.isEmpty()) {
                log.debug("No more output ports for group {}, removing next and fwd objectives", group);

                //On Success of removing the fwd objective we remove also the group.
                Consumer<Objective> onSuccess = (objective) -> {
                    log.debug("Successfully removed fwd objective for {} on {}, " +
                                      "removing next objective {}", group, sink, v.getNextId());
                    eventExecutor.execute(() -> {
                        //No port is needed since it's a remove Operation
                        flowObjectiveService.next(sink.deviceId(), nextObject(v.getNextId(),
                                                                              null,
                                                                              NextType.Remove, group));
                    });
                };

                // this is the last sink
                ObjectiveContext context = new DefaultObjectiveContext(onSuccess,
                        (objective, error) -> log.warn("Failed to remove {} on {}: {}",
                                                       group, sink, error));
                ForwardingObjective fwdObj = fwdObject(v.getNextId(), group).remove(context);
                flowObjectiveService.forward(sink.deviceId(), fwdObj);
            } else {
                log.debug("Group {} has remaining {} ports, removing just {} " +
                                 "from it's sinks", group, outPorts, sink.port());
                flowObjectiveService.next(sink.deviceId(), nextObject(v.getNextId(), sink.port(),
                                                                      NextType.RemoveFromExisting, group));
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

        Optional<SubscriberAndDeviceInformation> oltInfo = getSubscriberAndDeviceInformation(sink.deviceId());

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
                (objective) -> log.debug("Successfully add {} on {}/{}, vlan {}, inner vlan {}",
                                         route.group(), sink.deviceId(), sink.port().toLong(),
                                         assignedVlan(), assignedInnerVlan()),
                (objective, error) -> {
                    log.warn("Failed to add {} on {}/{}, vlan {}, inner vlan {}: {}",
                             route.group(), sink.deviceId(), sink.port().toLong(), assignedVlan(),
                             assignedInnerVlan(), error);
                });

        flowObjectiveService.next(sink.deviceId(), newNextObj);

        if (theFirstSinkOfGroup) {
            // create the necessary flow rule if this is the first sink request for the group
            // on this device
            flowObjectiveService.forward(sink.deviceId(), fwdObject(newNextObj.id(),
                                                                    route.group()).add(context));
        }
    }

    /**
     * Fetches device information associated with the device serial number from SADIS.
     *
     * @param serialNumber serial number of a device
     * @return device information; an empty Optional otherwise.
     */
    private Optional<SubscriberAndDeviceInformation> getSubscriberAndDeviceInformation(String serialNumber) {
        long start = System.currentTimeMillis();
        try {
            if (sadisService == null) {
                log.warn(SADIS_NOT_RUNNING);
                return Optional.empty();
            }
            return Optional.ofNullable(sadisService.getSubscriberInfoService().get(serialNumber));
        } finally {
            if (log.isDebugEnabled()) {
                // SADIS may call remote systems to fetch device data and this calls can take a long time.
                // This measurement is just for monitoring these kinds of situations.
                log.debug("Device fetched from SADIS. Elapsed {} msec", System.currentTimeMillis() - start);
            }

        }
    }

    /**
     * Fetches device information associated with the device serial number from SADIS.
     *
     * @param deviceId device id
     * @return device information; an empty Optional otherwise.
     */
    private Optional<SubscriberAndDeviceInformation> getSubscriberAndDeviceInformation(DeviceId deviceId) {
        Device device = deviceService.getDevice(deviceId);
        if (device == null || device.serialNumber() == null) {
            return Optional.empty();
        }
        return getSubscriberAndDeviceInformation(device.serialNumber());
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
                                if (vlanEnabled && (mcastVlan != config.egressVlan().toShort() ||
                                                    !mcastInnerVlan.equals(config.egressInnerVlan()))) {
                                    clearGroups();
                                    groups.clear();
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
        if (config.egressInnerVlan() != null) {
            mcastInnerVlan = config.egressInnerVlan();
        }
        feedStatsServiceWithVlanConfigValues();
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

    private enum NextType { AddNew, AddToExisting, Remove, RemoveFromExisting }

    private NextObjective nextObject(Integer nextId, PortNumber port,
                                     NextType nextType, IpAddress mcastIp) {

        // Build the meta selector with the fwd objective info
        TrafficSelector.Builder metadata = DefaultTrafficSelector.builder()
                .matchIPDst(mcastIp.toIpPrefix());

        if (vlanEnabled) {
            metadata.matchVlanId(multicastVlan());

            if (!mcastInnerVlan.equals(VlanId.NONE)) {
                metadata.matchInnerVlanId(mcastInnerVlan);
            }
        }

        DefaultNextObjective.Builder builder = DefaultNextObjective.builder()
                .fromApp(appId)
                .withType(NextObjective.Type.BROADCAST)
                .withId(nextId)
                .withMeta(metadata.build());

        if (port == null && !nextType.equals(NextType.Remove)) {
            log.error("Port can't be null with operation {}", nextType);
            return null;
        } else if (port != null && !nextType.equals(NextType.Remove)) {
            builder.addTreatment(DefaultTrafficTreatment.builder().setOutput(port).build());
        }

        ObjectiveContext context = new ObjectiveContext() {
            @Override
            public void onSuccess(Objective objective) {
                log.debug("Success for operation {} on Next Objective {}", objective.id(), nextType);
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
                return builder.add(context);
            case AddToExisting:
                return builder.addToExisting(context);
            case Remove:
                return builder.remove(context);
            case RemoveFromExisting:
                return builder.removeFromExisting(context);
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

            if (!mcastInnerVlan.equals(VlanId.NONE)) {
                metabuilder.matchInnerVlanId(mcastInnerVlan);
            }
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

    private class InternalDeviceListener implements DeviceListener {

        @Override
        public void event(DeviceEvent event) {
            eventExecutor.execute(() -> {
                DeviceId devId = event.subject().id();
                if (!deviceService.isAvailable(devId) &&
                        isLocalLeader(event.subject().id())) {
                    if (deviceService.getPorts(devId).isEmpty()) {
                        log.info("Handling controlled device disconnection .. "
                                         + "flushing all state for dev:{}", devId);
                        groupService.purgeGroupEntries(devId);
                        groups.keySet().iterator().forEachRemaining(groupInfo -> {
                            if (groupInfo.device.equals(devId)) {
                                log.debug("Removing next key {} from distributed mcast map", groupInfo.group);
                                groups.remove(groupInfo);
                            }
                        });
                    } else {
                        log.info("Disconnected device has available ports .. "
                                         + "assuming temporary disconnection, "
                                         + "retaining state for device {}", devId);
                    }
                }
            });

        }

        @Override
        public boolean isRelevant(DeviceEvent event) {
            return event.type().equals(DeviceEvent.Type.DEVICE_AVAILABILITY_CHANGED);
        }
    }

}


