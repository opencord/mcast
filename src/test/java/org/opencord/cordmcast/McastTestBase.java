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

import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.onlab.packet.IpAddress;
import org.onlab.packet.VlanId;
import org.onosproject.TestApplicationId;
import org.onosproject.core.ApplicationId;
import org.onosproject.core.CoreServiceAdapter;
import org.onosproject.mastership.MastershipServiceAdapter;
import org.onosproject.mcast.api.McastRoute;
import org.onosproject.net.ConnectPoint;
import org.onosproject.net.DeviceId;
import org.onosproject.net.HostId;
import org.onosproject.net.PortNumber;
import org.onosproject.net.flow.TrafficSelector;
import org.onosproject.net.flow.TrafficTreatment;
import org.onosproject.net.flow.criteria.Criterion;
import org.onosproject.net.flow.criteria.IPCriterion;
import org.onosproject.net.flow.instructions.Instruction;
import org.onosproject.net.flow.instructions.Instructions.OutputInstruction;
import org.onosproject.net.flowobjective.FlowObjectiveServiceAdapter;
import org.onosproject.net.flowobjective.ForwardingObjective;
import org.onosproject.net.flowobjective.NextObjective;
import org.opencord.cordconfig.CordConfigListener;
import org.opencord.cordconfig.CordConfigService;
import org.opencord.cordconfig.access.AccessAgentData;
import org.opencord.cordconfig.access.AccessDeviceData;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableMap;

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

     //Host id configuration
     protected static final HostId HOST_ID_NONE = HostId.NONE;
     // Source connect point
     protected static final  Set<ConnectPoint> SOURCES_CP = new HashSet<ConnectPoint>(Arrays.asList(CONNECT_POINT_A));
     Map<HostId, Set<ConnectPoint>> sources = ImmutableMap.of(HOST_ID_NONE, SOURCES_CP);

     protected static final IpAddress MULTICAST_IP = IpAddress.valueOf("224.0.0.22");
     // Creating dummy route with IGMP type.
     McastRoute route1 = new McastRoute(null, MULTICAST_IP, McastRoute.Type.IGMP);

     // Creating empty sink used in prevRoute
     Set<ConnectPoint> sinksCp = new HashSet<ConnectPoint>(Arrays.asList());
     Map<HostId, Set<ConnectPoint>> sinks = ImmutableMap.of(HOST_ID_NONE, sinksCp);

     // Creating empty source
     Set<ConnectPoint> sourceCp = new HashSet<ConnectPoint>(Arrays.asList());
     Map<HostId, Set<ConnectPoint>> emptySource = ImmutableMap.of(HOST_ID_NONE, sourceCp);

     // Flag to check unknown olt device
     boolean knownOltFlag = false;

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

     class MockCordConfigService implements CordConfigService {

          @Override
          public void addListener(CordConfigListener listener) {

          }

          @Override
          public void removeListener(CordConfigListener listener) {

          }

          @Override
          public Set<AccessDeviceData> getAccessDevices() {

               return null;
          }

          @Override
          public Optional<AccessDeviceData> getAccessDevice(DeviceId deviceId) {
             if (deviceId == DEVICE_ID_OF_A) {
               PortNumber uplink = PortNumber.portNumber(3);
               VlanId vlan = VlanId.vlanId((short) 0);
               ObjectMapper mapper = new ObjectMapper();
               JsonNode defaultVlanNode = null;
               try {
                    defaultVlanNode =
                    (JsonNode) mapper.readTree("{\"driver\":\"pmc-olt\" , \"type \" : \"OLT\"}");
               } catch (IOException e) {
                    e.printStackTrace();
               }

               Optional<VlanId> defaultVlan;
               if (defaultVlanNode.isMissingNode()) {
                    defaultVlan = Optional.empty();
               } else {
                    defaultVlan = Optional.of(VlanId.vlanId(defaultVlanNode.shortValue()));
               }
               Optional<AccessDeviceData> accessDeviceData = null;
               AccessDeviceData accessDevice = new AccessDeviceData(deviceId, uplink, vlan, defaultVlan);
               accessDeviceData = Optional.of(accessDevice);
               return accessDeviceData;
             } else {
                 knownOltFlag = true;
                 return Optional.empty();
             }
          }

          @Override
          public Set<AccessAgentData> getAccessAgents() {
               return null;
          }

          @Override
          public Optional<AccessAgentData> getAccessAgent(DeviceId deviceId) {
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

}
