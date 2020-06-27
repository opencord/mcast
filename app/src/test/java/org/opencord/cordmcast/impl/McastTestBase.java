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

import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.Set;
import java.util.HashSet;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.Iterator;

import org.onlab.packet.Ip4Address;
import org.onlab.packet.IpAddress;
import org.onlab.packet.VlanId;
import org.onosproject.TestApplicationId;
import org.onosproject.cluster.LeadershipServiceAdapter;
import org.onosproject.cluster.NodeId;
import org.onosproject.core.ApplicationId;
import org.onosproject.core.CoreServiceAdapter;
import org.onosproject.event.DefaultEventSinkRegistry;
import org.onosproject.event.Event;
import org.onosproject.event.EventDeliveryService;
import org.onosproject.event.EventSink;
import org.onosproject.mastership.MastershipServiceAdapter;
import org.onosproject.mcast.api.McastRoute;
import org.onosproject.net.AnnotationKeys;
import org.onosproject.net.Annotations;
import org.onosproject.net.ConnectPoint;
import org.onosproject.net.DefaultAnnotations;
import org.onosproject.net.DefaultDevice;
import org.onosproject.net.Device;
import org.onosproject.net.DeviceId;
import org.onosproject.net.HostId;
import org.onosproject.net.PortNumber;
import org.onosproject.net.SparseAnnotations;
import org.onosproject.net.config.Config;
import org.onosproject.net.config.NetworkConfigRegistryAdapter;
import org.onosproject.net.config.basics.McastConfig;
import org.onosproject.net.device.DeviceServiceAdapter;
import org.onosproject.net.flow.TrafficSelector;
import org.onosproject.net.flow.TrafficTreatment;
import org.onosproject.net.flow.criteria.Criterion;
import org.onosproject.net.flow.criteria.IPCriterion;
import org.onosproject.net.flow.criteria.VlanIdCriterion;
import org.onosproject.net.flow.instructions.Instruction;
import org.onosproject.net.flow.instructions.Instructions.OutputInstruction;
import org.onosproject.net.flowobjective.FlowObjectiveServiceAdapter;
import org.onosproject.net.flowobjective.ForwardingObjective;
import org.onosproject.net.flowobjective.NextObjective;

import com.google.common.collect.ImmutableMap;
import org.opencord.cordmcast.CordMcastStatisticsEvent;
import org.opencord.cordmcast.CordMcastStatisticsEventListener;
import org.opencord.sadis.BandwidthProfileInformation;
import org.opencord.sadis.BaseInformationService;
import org.opencord.sadis.SadisService;
import org.opencord.sadis.SubscriberAndDeviceInformation;

import static com.google.common.base.Preconditions.checkState;

public class McastTestBase {

     // Map to store the forwardingObjective in flowObjectiveService.forward()
     Map<DeviceId, ForwardingObjective> forwardMap = new HashMap<>();
     // Map to store the nextObjective in flowObjectiveService.next()
     Map<DeviceId, NextObjective> nextMap = new HashMap<>();
     // Device configuration
     protected static final DeviceId DEVICE_ID_OF_A = DeviceId.deviceId("of:00000a0a0a0a0a00");
     // Port number
     protected static final PortNumber PORT_A = PortNumber.portNumber(1048576);
     protected static final PortNumber PORT_B = PortNumber.portNumber(16);
     protected static final PortNumber PORT_C = PortNumber.portNumber(24);

     // Connect Point for creating source and sink
     protected static final ConnectPoint CONNECT_POINT_A = new ConnectPoint(DEVICE_ID_OF_A, PORT_A);
     protected static final ConnectPoint CONNECT_POINT_B = new ConnectPoint(DEVICE_ID_OF_A, PORT_B);
     protected static final ConnectPoint CONNECT_POINT_C = new ConnectPoint(DEVICE_ID_OF_A, PORT_C);

     // serial number of the device A
     protected static final String SERIAL_NUMBER_OF_DEVICE_A = "serialNumberOfDevA";
     // Management ip address of the device A
     protected static final Ip4Address MANAGEMENT_IP_OF_A = Ip4Address.valueOf("10.177.125.4");
     //Host id configuration
     protected static final HostId HOST_ID_NONE = HostId.NONE;
     // Source connect point
     protected static final  Set<ConnectPoint> SOURCES_CP = new HashSet<ConnectPoint>(Arrays.asList(CONNECT_POINT_A));
     Map<HostId, Set<ConnectPoint>> sources = ImmutableMap.of(HOST_ID_NONE, SOURCES_CP);

     protected static final IpAddress MULTICAST_IP = IpAddress.valueOf("224.0.0.22");
     protected static final IpAddress SOURCE_IP = IpAddress.valueOf("192.168.1.1");
     // Creating dummy route with IGMP type.
     McastRoute route1 = new McastRoute(SOURCE_IP, MULTICAST_IP, McastRoute.Type.IGMP);

     // Creating empty sink used in prevRoute
     Set<ConnectPoint> sinksCp = new HashSet<ConnectPoint>(Arrays.asList());
     Map<HostId, Set<ConnectPoint>> sinks = ImmutableMap.of(HOST_ID_NONE, sinksCp);

     // Creating empty source
     Set<ConnectPoint> sourceCp = new HashSet<ConnectPoint>(Arrays.asList());
     Map<HostId, Set<ConnectPoint>> emptySource = ImmutableMap.of(HOST_ID_NONE, sourceCp);

     // Flag to check unknown olt device
     boolean knownOltFlag = false;

     // For the tests reduce events period to 1s
     protected static final int EVENT_GENERATION_PERIOD = 1;

     class MockCoreService extends CoreServiceAdapter {
          @Override
          public ApplicationId registerApplication(String name) {
               ApplicationId testApplicationId = TestApplicationId.create("org.opencord.cordmcast");
               return testApplicationId;
          }
     }

     class MockFlowObjectiveService extends FlowObjectiveServiceAdapter {
          @Override
          public void forward(DeviceId deviceId, ForwardingObjective forwardingObjective) {
              synchronized (forwardMap) {
                forwardMap.put(deviceId, forwardingObjective);
                forwardingObjective.context().ifPresent(context -> {
                    context.onSuccess(forwardingObjective);
                });
                forwardMap.notify();
              }
          }

          @Override
          public void next(DeviceId deviceId, NextObjective nextObjective) {
             nextMap.put(deviceId, nextObjective);
          }
     }

     class TestMastershipService extends MastershipServiceAdapter {
          @Override
          public boolean isLocalMaster(DeviceId deviceId) {
               return true;
          }
     }

     class LeadershipServiceMcastAdapter extends LeadershipServiceAdapter {
        @Override
        public NodeId getLeader(String path) {
            return NodeId.nodeId("local");
        }
     }

    protected class MockSadisService implements SadisService {

        @Override
        public BaseInformationService<SubscriberAndDeviceInformation> getSubscriberInfoService() {
            return new MockSubService();
        }

        @Override
        public BaseInformationService<BandwidthProfileInformation> getBandwidthProfileService() {
            return null;
        }
    }

    /**
     * Mocks the McastConfig class to return vlan id value.
     */
    static class MockMcastConfig extends McastConfig {
        private VlanId egressVlan;
        private VlanId egressInnerVlan;

        public MockMcastConfig(VlanId egressVlan, VlanId egressInnerVlan) {
            this.egressVlan = egressVlan;
            this.egressInnerVlan = egressInnerVlan;
        }

        @Override
        public VlanId egressVlan() {
            return egressVlan;
        }

        @Override
        public VlanId egressInnerVlan() {
            return egressInnerVlan;
        }
    }

    /**
     * Mocks the network config registry.
     */
    @SuppressWarnings("unchecked")
    static final class TestNetworkConfigRegistry
            extends NetworkConfigRegistryAdapter {

        private VlanId egressVlan = VlanId.vlanId("4000");
        private VlanId egressInnerVlan = VlanId.NONE;

        public void setEgressVlan(VlanId egressVlan) {
            this.egressVlan = egressVlan;
        }

        public void setEgressInnerVlan(VlanId egressInnerVlan) {
            this.egressInnerVlan = egressInnerVlan;
        }

        @Override
        public <S, C extends Config<S>> C getConfig(S subject, Class<C> configClass) {
            McastConfig mcastConfig = new MockMcastConfig(egressVlan, egressInnerVlan);
            return (C) mcastConfig;
        }
    }

    public static class TestEventDispatcher extends DefaultEventSinkRegistry
            implements EventDeliveryService {

        @Override
        @SuppressWarnings("unchecked")
        public synchronized void post(Event event) {
            EventSink sink = getSink(event.getClass());
            checkState(sink != null, "No sink for event %s", event);
            sink.process(event);
        }

        @Override
        public void setDispatchTimeLimit(long millis) {

        }

        @Override
        public long getDispatchTimeLimit() {
            return 0;
        }
    }

    public static class MockCordMcastStatisticsEventListener implements CordMcastStatisticsEventListener {
        protected List<CordMcastStatisticsEvent> mcastEventList = new ArrayList<CordMcastStatisticsEvent>();

        @Override
        public void event(CordMcastStatisticsEvent event) {
            mcastEventList.add(event);
        }
    }

    private class MockSubService implements BaseInformationService<SubscriberAndDeviceInformation> {
        MockSubscriberAndDeviceInformation deviceA =
                new MockSubscriberAndDeviceInformation(SERIAL_NUMBER_OF_DEVICE_A, MANAGEMENT_IP_OF_A);

        @Override
        public SubscriberAndDeviceInformation get(String id) {
            return SERIAL_NUMBER_OF_DEVICE_A.equals(id) ? deviceA : null;
        }

        @Override
        public void invalidateAll() {
        }

        @Override
        public void invalidateId(String id) {
        }

        @Override
        public SubscriberAndDeviceInformation getfromCache(String id) {
            return null;
        }
    }

    private class MockSubscriberAndDeviceInformation extends SubscriberAndDeviceInformation {

        MockSubscriberAndDeviceInformation(String id, Ip4Address ipAddress) {
            this.setId(id);
            this.setIPAddress(ipAddress);
            this.setUplinkPort((int) PORT_A.toLong());
        }
    }

    class MockDeviceService extends DeviceServiceAdapter {

        @Override
        public Device getDevice(DeviceId deviceId) {
            if (DEVICE_ID_OF_A.equals(deviceId)) {
                DefaultAnnotations.Builder annotationsBuilder = DefaultAnnotations.builder()
                        .set(AnnotationKeys.MANAGEMENT_ADDRESS, MANAGEMENT_IP_OF_A.toString());
                SparseAnnotations annotations = annotationsBuilder.build();
                Annotations[] da = {annotations};

                Device deviceA = new DefaultDevice(null, DEVICE_ID_OF_A, Device.Type.OTHER, "", "",
                        "", SERIAL_NUMBER_OF_DEVICE_A, null, da);
                return deviceA;
            } else {
                knownOltFlag = true;
            }
            return null;
        }
    }

     public OutputInstruction outputPort(TrafficTreatment trafficTreatment) {
         List<Instruction> listOfInstructions = trafficTreatment.allInstructions();
         OutputInstruction output = null;
         for (Instruction intruction : listOfInstructions) {
           output = (OutputInstruction) intruction;
         }
         return output;
     }

     public IPCriterion ipAddress(TrafficSelector trafficSelector) {
       Set<Criterion> criterionSet = trafficSelector.criteria();
       Iterator<Criterion> it = criterionSet.iterator();
       IPCriterion ipCriterion = null;
       while (it.hasNext()) {
           Criterion criteria = it.next();
           if (Criterion.Type.IPV4_DST == criteria.type()) {
             ipCriterion = (IPCriterion) criteria;
           }
       }
       return (IPCriterion) ipCriterion;
     }

    public VlanIdCriterion vlanId(TrafficSelector trafficSelector, Criterion.Type type) {
        Set<Criterion> criterionSet = trafficSelector.criteria();
        Iterator<Criterion> it = criterionSet.iterator();
        VlanIdCriterion criterion = null;
        while (it.hasNext()) {
            Criterion criteria = it.next();
            if (type == criteria.type()) {
                criterion = (VlanIdCriterion) criteria;
            }
        }
        return criterion;
    }
}
