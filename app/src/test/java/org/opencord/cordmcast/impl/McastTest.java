/*
 * Copyright 2016-2023 Open Networking Foundation (ONF) and the ONF Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.opencord.cordmcast.impl;

import static org.easymock.EasyMock.expect;
import static org.easymock.EasyMock.replay;

import org.easymock.EasyMock;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.onlab.junit.TestUtils;
import org.onlab.packet.IpAddress;
import org.onlab.packet.VlanId;
import org.onosproject.cfg.ComponentConfigAdapter;
import org.onosproject.cfg.ComponentConfigService;
import org.onosproject.cluster.ClusterServiceAdapter;
import org.onosproject.mcast.api.McastEvent;
import org.onosproject.mcast.api.McastRoute;
import org.onosproject.mcast.api.McastRouteUpdate;
import org.onosproject.mcast.api.MulticastRouteService;
import org.onosproject.net.ConnectPoint;
import org.onosproject.net.DeviceId;
import org.onosproject.net.HostId;
import org.onosproject.net.flow.TrafficSelector;
import org.onosproject.net.flow.TrafficTreatment;
import org.onosproject.net.flow.criteria.Criterion;
import org.onosproject.net.flow.criteria.IPCriterion;
import org.onosproject.net.flow.criteria.VlanIdCriterion;
import org.onosproject.net.flow.instructions.Instructions.OutputInstruction;
import org.onosproject.net.flowobjective.Objective;
import org.onosproject.store.service.StorageServiceAdapter;
import org.onosproject.store.service.TestConsistentMap;
import org.opencord.cordmcast.CordMcastStatistics;
import org.opencord.cordmcast.CordMcastStatisticsEvent;
import org.osgi.service.component.ComponentContext;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Sets;

import java.util.Dictionary;
import java.util.HashSet;
import java.util.Hashtable;
import java.util.Set;
import java.util.Map;
import java.util.Arrays;
import java.util.Collection;

import static org.junit.Assert.*;
import static org.onlab.junit.TestTools.assertAfter;

public class McastTest extends McastTestBase {

  private CordMcast cordMcast;
  private CordMcastStatisticsManager cordMcastStatisticsManager;

  private MockCordMcastStatisticsEventListener mockListener = new MockCordMcastStatisticsEventListener();

  private static final int WAIT_TIMEOUT = 1000;
  private static final int WAIT = 250;
  McastRouteUpdate previousSubject, currentSubject;

  @Before
  public void setUp() {
        //setup operation is handled by init() method in each test
   }

    @After
    public void tearDown() {
      cordMcast.deactivate();
      forwardMap.clear();
      nextMap.clear();
    }

    private void init(boolean vlanEnabled, VlanId egressVlan, VlanId egressInnerVlan) {
        cordMcast = new CordMcast();
        cordMcastStatisticsManager = new CordMcastStatisticsManager();
        cordMcastStatisticsManager.clusterService = new ClusterServiceAdapter();
        cordMcastStatisticsManager.leadershipService = new LeadershipServiceMcastAdapter();
        cordMcast.coreService = new MockCoreService();

        TestNetworkConfigRegistry testNetworkConfigRegistry = new TestNetworkConfigRegistry();
        testNetworkConfigRegistry.setEgressVlan(egressVlan);
        testNetworkConfigRegistry.setEgressInnerVlan(egressInnerVlan);
        cordMcast.networkConfig = testNetworkConfigRegistry;

        cordMcast.flowObjectiveService = new MockFlowObjectiveService();
        cordMcast.mastershipService = new TestMastershipService();
        cordMcast.deviceService = new MockDeviceService();
        cordMcast.componentConfigService = new ComponentConfigAdapter();
        cordMcastStatisticsManager.componentConfigService = new ComponentConfigAdapter();
        cordMcastStatisticsManager.addListener(mockListener);
        cordMcast.sadisService = new MockSadisService();
        cordMcast.cordMcastStatisticsService = cordMcastStatisticsManager;

        cordMcast.storageService = EasyMock.createMock(StorageServiceAdapter.class);
        expect(cordMcast.storageService.consistentMapBuilder()).andReturn(new TestConsistentMap.Builder<>());
        replay(cordMcast.storageService);

        Dictionary<String, Object> cfgDict = new Hashtable<String, Object>();
        cfgDict.put("vlanEnabled", vlanEnabled);
        cfgDict.put("eventGenerationPeriodInSeconds", EVENT_GENERATION_PERIOD);

        cordMcast.componentConfigService = EasyMock.createNiceMock(ComponentConfigService.class);
        replay(cordMcast.componentConfigService);

        Set<McastRoute> route1Set = new HashSet<McastRoute>();
        route1Set.add(route1);

        cordMcast.mcastService = EasyMock.createNiceMock(MulticastRouteService.class);
        expect(cordMcast.mcastService.getRoutes()).andReturn(Sets.newHashSet());
        replay(cordMcast.mcastService);

        cordMcastStatisticsManager.mcastService = EasyMock.createNiceMock(MulticastRouteService.class);
        expect(cordMcastStatisticsManager.mcastService.getRoutes()).andReturn(route1Set).times(2);
        replay(cordMcastStatisticsManager.mcastService);

        TestUtils.setField(cordMcastStatisticsManager, "eventDispatcher", new TestEventDispatcher());

        ComponentContext componentContext = EasyMock.createMock(ComponentContext.class);
        expect(componentContext.getProperties()).andReturn(cfgDict).times(2);
        replay(componentContext);
        cordMcast.cordMcastStatisticsService = cordMcastStatisticsManager;
        cordMcastStatisticsManager.activate(componentContext);

        cordMcast.activate(componentContext);
    }

    @Test
    public void testAddingSinkEvent() throws InterruptedException {
      init(false, null, null);

      Set<ConnectPoint> sinks2Cp = new HashSet<ConnectPoint>(Arrays.asList(CONNECT_POINT_B));
      Map<HostId, Set<ConnectPoint>> sinks2 = ImmutableMap.of(HOST_ID_NONE, sinks2Cp);

      //Adding the details to create different routes
      previousSubject = McastRouteUpdate.mcastRouteUpdate(route1, sources, sinks);
      currentSubject = McastRouteUpdate.mcastRouteUpdate(route1, sources, sinks2);
      // Creating new mcast event for adding sink
      McastEvent event = new McastEvent(McastEvent.Type.SINKS_ADDED, previousSubject, currentSubject);
      cordMcast.listener.event(event);
      synchronized (forwardMap) {
        forwardMap.wait(WAIT_TIMEOUT);
      }

      // ForwardMap will contain the operation "Add" in the flowObjective. None -> CP_B
      assertNotNull(forwardMap.get(DEVICE_ID_OF_A));
      assertEquals(forwardMap.get(DEVICE_ID_OF_A).op(), Objective.Operation.ADD);

      // Output port number will be PORT_B i.e. 16
      Collection<TrafficTreatment> traffictreatMentCollection =
           nextMap.get(DEVICE_ID_OF_A).next();
      assertEquals(1, traffictreatMentCollection.size());
      OutputInstruction output = null;
      for (TrafficTreatment trafficTreatment : traffictreatMentCollection) {
         output = outputPort(trafficTreatment);
      }
      assertNotNull(output);
      assertEquals(PORT_B, output.port());
      // Checking the group ip address
      TrafficSelector trafficSelector = forwardMap.get(DEVICE_ID_OF_A).selector();
      IPCriterion ipCriterion = ipAddress(trafficSelector);
      assertNotNull(ipCriterion);
      assertEquals(MULTICAST_IP, ipCriterion.ip().address());
      //checking the vlan criterion
      TrafficSelector meta = forwardMap.get(DEVICE_ID_OF_A).meta();
      VlanIdCriterion vlanId = vlanId(meta, Criterion.Type.VLAN_VID);
      assertNull(vlanId); //since vlanEnabled flag is false
      VlanIdCriterion innerVlanIdCriterion = vlanId(meta, Criterion.Type.INNER_VLAN_VID);
      assertNull(innerVlanIdCriterion);
    }

    @Test
    public void testAddingSinkEventVlanEnabled() throws InterruptedException {
        // vlanEnabled is set to true and just egressVlan is set
        init(true, VlanId.vlanId("4000"), null);

        Set<ConnectPoint> sinks2Cp = new HashSet<ConnectPoint>(Arrays.asList(CONNECT_POINT_B));
        Map<HostId, Set<ConnectPoint>> sinks2 = ImmutableMap.of(HOST_ID_NONE, sinks2Cp);

        //Adding the details to create different routes
        previousSubject = McastRouteUpdate.mcastRouteUpdate(route1, sources, sinks);
        currentSubject = McastRouteUpdate.mcastRouteUpdate(route1, sources, sinks2);
        // Creating new mcast event for adding sink
        McastEvent event = new McastEvent(McastEvent.Type.SINKS_ADDED, previousSubject, currentSubject);
        cordMcast.listener.event(event);
        synchronized (forwardMap) {
            forwardMap.wait(WAIT_TIMEOUT);
        }
        // ForwardMap will contain the operation "Add" in the flowObjective. None -> CP_B
        assertNotNull(forwardMap.get(DEVICE_ID_OF_A));
        assertEquals(forwardMap.get(DEVICE_ID_OF_A).op(), Objective.Operation.ADD);

        // Output port number will be PORT_B i.e. 16
        Collection<TrafficTreatment> traffictreatMentCollection =
                nextMap.get(DEVICE_ID_OF_A).next();
        assertEquals(1, traffictreatMentCollection.size());
        OutputInstruction output = null;
        for (TrafficTreatment trafficTreatment : traffictreatMentCollection) {
            output = outputPort(trafficTreatment);
        }
        assertNotNull(output);
        assertEquals(PORT_B, output.port());
        // Checking the group ip address
        TrafficSelector trafficSelector = forwardMap.get(DEVICE_ID_OF_A).selector();
        IPCriterion ipCriterion = ipAddress(trafficSelector);
        assertNotNull(ipCriterion);
        assertEquals(MULTICAST_IP, ipCriterion.ip().address());
        //checking the vlan criteria
        TrafficSelector meta = forwardMap.get(DEVICE_ID_OF_A).meta();
        VlanIdCriterion vlanIdCriterion = vlanId(meta, Criterion.Type.VLAN_VID);
        assertNotNull(vlanIdCriterion); //since vlanEnabled flag is true
        assertEquals(cordMcast.assignedVlan(), vlanIdCriterion.vlanId());
        VlanIdCriterion innerVlanIdCriterion = vlanId(meta, Criterion.Type.INNER_VLAN_VID);
        assertNull(innerVlanIdCriterion);
    }

    @Test
    public void testAddingSinkEventInnerVlanEnabled() throws InterruptedException {
        // vlanEnabled is set to true and egressVlan & egressInnerVlan are set
        init(true, VlanId.vlanId("4000"), VlanId.vlanId("1000"));

        Set<ConnectPoint> sinks2Cp = new HashSet<ConnectPoint>(Arrays.asList(CONNECT_POINT_B));
        Map<HostId, Set<ConnectPoint>> sinks2 = ImmutableMap.of(HOST_ID_NONE, sinks2Cp);

        //Adding the details to create different routes
        previousSubject = McastRouteUpdate.mcastRouteUpdate(route1, sources, sinks);
        currentSubject = McastRouteUpdate.mcastRouteUpdate(route1, sources, sinks2);
        // Creating new mcast event for adding sink
        McastEvent event = new McastEvent(McastEvent.Type.SINKS_ADDED, previousSubject, currentSubject);
        cordMcast.listener.event(event);
        synchronized (forwardMap) {
            forwardMap.wait(WAIT_TIMEOUT);
        }

        // ForwardMap will contain the operation "Add" in the flowObjective. None -> CP_B
        assertNotNull(forwardMap.get(DEVICE_ID_OF_A));
        assertEquals(forwardMap.get(DEVICE_ID_OF_A).op(), Objective.Operation.ADD);

        // Output port number will be PORT_B i.e. 16
        Collection<TrafficTreatment> traffictreatMentCollection =
                nextMap.get(DEVICE_ID_OF_A).next();
        assertEquals(1, traffictreatMentCollection.size());
        OutputInstruction output = null;
        for (TrafficTreatment trafficTreatment : traffictreatMentCollection) {
            output = outputPort(trafficTreatment);
        }
        assertNotNull(output);
        assertEquals(PORT_B, output.port());
        // Checking the group ip address
        TrafficSelector trafficSelector = forwardMap.get(DEVICE_ID_OF_A).selector();
        IPCriterion ipCriterion = ipAddress(trafficSelector);
        assertNotNull(ipCriterion);
        assertEquals(MULTICAST_IP, ipCriterion.ip().address());
        //checking the vlan criteria
        TrafficSelector meta = forwardMap.get(DEVICE_ID_OF_A).meta();
        VlanIdCriterion vlanIdCriterion = vlanId(meta, Criterion.Type.VLAN_VID);
        assertNotNull(vlanIdCriterion); //since vlanEnabled flag is true
        assertEquals(cordMcast.assignedVlan(), vlanIdCriterion.vlanId());
        VlanIdCriterion innerVlanIdCriterion = vlanId(meta, Criterion.Type.INNER_VLAN_VID);
        assertNotNull(innerVlanIdCriterion);
        assertEquals(cordMcast.assignedInnerVlan(), innerVlanIdCriterion.vlanId());
    }

    @Test
    public void testAddToExistingSinkEvent() throws InterruptedException {
      init(false, null, null);
       // Adding first sink (none --> CP_B)
       testAddingSinkEvent();

       Set<ConnectPoint> sinksCp = new HashSet<ConnectPoint>(Arrays.asList(CONNECT_POINT_B));
       Map<HostId, Set<ConnectPoint>> sinks = ImmutableMap.of(HOST_ID_NONE, sinksCp);
       previousSubject = McastRouteUpdate.mcastRouteUpdate(route1, sources, sinks);
       sinksCp = new HashSet<ConnectPoint>(Arrays.asList(CONNECT_POINT_B, CONNECT_POINT_C));
       sinks = ImmutableMap.of(HOST_ID_NONE, sinksCp);
       currentSubject = McastRouteUpdate.mcastRouteUpdate(route1, sources, sinks);
       // Again listening the mcast event with different output port ( none --> CP_B, CP_C)
       McastEvent event = new McastEvent(McastEvent.Type.SINKS_ADDED, previousSubject, currentSubject);
       cordMcast.listener.event(event);

       // NextMap will contain the operation "ADD_TO_EXISTING" in the DefaultNextObjective.
       assertAfter(WAIT_TIMEOUT, WAIT_TIMEOUT * 2, () ->
       assertEquals(nextMap.get(DEVICE_ID_OF_A).op(), Objective.Operation.ADD_TO_EXISTING));
       // Output port number will be changed to 24 i.e. PORT_C
       Collection<TrafficTreatment> traffictreatMentCollection = nextMap.get(DEVICE_ID_OF_A).next();
       assertEquals(1, traffictreatMentCollection.size());
       OutputInstruction output = null;
       for (TrafficTreatment trafficTreatment : traffictreatMentCollection) {
          output = outputPort(trafficTreatment);
       }
       assertNotNull(output);
       assertEquals(PORT_C, output.port());
    }

    @Test
    public void testRemoveSinkEvent() throws InterruptedException {
       init(false, null, null);
       testAddToExistingSinkEvent();
       // Handling the mcast event for removing sink.
       Set<ConnectPoint> sinksCp = new HashSet<ConnectPoint>(Arrays.asList(CONNECT_POINT_B, CONNECT_POINT_C));
       Map<HostId, Set<ConnectPoint>> sinks = ImmutableMap.of(HOST_ID_NONE, sinksCp);
       previousSubject = McastRouteUpdate.mcastRouteUpdate(route1, sources, sinks);
       sinksCp = new HashSet<ConnectPoint>(Arrays.asList(CONNECT_POINT_C));
       sinks = ImmutableMap.of(HOST_ID_NONE, sinksCp);
       currentSubject = McastRouteUpdate.mcastRouteUpdate(route1, sources, sinks);
       McastEvent event = new McastEvent(McastEvent.Type.SINKS_REMOVED, previousSubject, currentSubject);
       cordMcast.listener.event(event);
       // Operation will be REMOVE_FROM_EXISTING and nextMap will be updated. ( None --> CP_C)
       assertAfter(WAIT_TIMEOUT, WAIT_TIMEOUT * 2, () ->
       assertEquals(nextMap.get(DEVICE_ID_OF_A).op(), Objective.Operation.REMOVE_FROM_EXISTING));

       // Output port number will be PORT_B i.e. 16
       // Port_B is removed from the group.
       Collection<TrafficTreatment> traffictreatMentCollection =
            nextMap.get(DEVICE_ID_OF_A).next();
       assertEquals(1, traffictreatMentCollection.size());
       OutputInstruction output = null;
       for (TrafficTreatment trafficTreatment : traffictreatMentCollection) {
          output = outputPort(trafficTreatment);
       }
       assertNotNull(output);
       assertEquals(PORT_B, output.port());

    }

    @Test
    public void testRemoveLastSinkEvent() throws InterruptedException {
        init(false, null, null);
       testRemoveSinkEvent();
       // Handling the mcast event for removing sink.
       Set<ConnectPoint> sinksCp = new HashSet<ConnectPoint>(Arrays.asList(CONNECT_POINT_C));
       Map<HostId, Set<ConnectPoint>> sinks = ImmutableMap.of(HOST_ID_NONE, sinksCp);
       previousSubject = McastRouteUpdate.mcastRouteUpdate(route1, sources, sinks);
       sinksCp = new HashSet<ConnectPoint>(Arrays.asList());
       sinks = ImmutableMap.of(HOST_ID_NONE, sinksCp);
       currentSubject = McastRouteUpdate.mcastRouteUpdate(route1, sources, sinks);
       McastEvent event = new McastEvent(McastEvent.Type.SINKS_REMOVED, previousSubject, currentSubject);
       cordMcast.listener.event(event);

       // Operation will be REMOVE and nextMap will be updated.  None --> { }
       assertAfter(WAIT_TIMEOUT, WAIT_TIMEOUT * 2, () ->
       assertEquals(nextMap.get(DEVICE_ID_OF_A).op(), Objective.Operation.REMOVE));
  }

  @Test
  public void testUnkownOltDevice() throws InterruptedException {
       init(false, null, null);
       // Configuration of mcast event for unknown olt device
       final DeviceId deviceIdOfB = DeviceId.deviceId("of:1");

       ConnectPoint connectPointA = new ConnectPoint(deviceIdOfB, PORT_A);
       ConnectPoint connectPointB = new ConnectPoint(deviceIdOfB, PORT_B);
       Set<ConnectPoint> sourcesCp = new HashSet<ConnectPoint>(Arrays.asList(connectPointA));
       Set<ConnectPoint> sinksCp = new HashSet<ConnectPoint>(Arrays.asList());
       Set<ConnectPoint> sinks2Cp = new HashSet<ConnectPoint>(Arrays.asList(connectPointB));
       Map<HostId, Set<ConnectPoint>> sources = ImmutableMap.of(HOST_ID_NONE, sourcesCp);

       Map<HostId, Set<ConnectPoint>> sinks = ImmutableMap.of(HOST_ID_NONE, sinksCp);
       Map<HostId, Set<ConnectPoint>> sinks2 = ImmutableMap.of(HOST_ID_NONE, sinks2Cp);
       //Adding the details to create different routes
       McastRouteUpdate previousSubject = McastRouteUpdate.mcastRouteUpdate(route1, sources, sinks);
       McastRouteUpdate currentSubject = McastRouteUpdate.mcastRouteUpdate(route1, sources, sinks2);
       // Creating new mcast event for adding sink
       McastEvent event = new McastEvent(McastEvent.Type.SINKS_ADDED, previousSubject, currentSubject);
       cordMcast.listener.event(event);
       // OltInfo flag is set to true when olt device is unkown
       assertAfter(WAIT, WAIT * 2, () -> assertTrue(knownOltFlag));
       assertEquals(0, forwardMap.size());
       assertEquals(0, nextMap.size());

  }

  @Test
  public void testRouteAddedEvent() throws InterruptedException {
      init(false, null, null);
      //Adding the details to create different routes
      previousSubject = McastRouteUpdate.mcastRouteUpdate(route1, emptySource, sinks);
      currentSubject = McastRouteUpdate.mcastRouteUpdate(route1, emptySource, sinks);
      // Creating new mcast event for route adding
      McastEvent event = new McastEvent(McastEvent.Type.ROUTE_ADDED, previousSubject, currentSubject);
      cordMcast.listener.event(event);
      // There will be no forwarding objective
      assertAfter(WAIT, WAIT * 2, () -> assertEquals(0, forwardMap.size()));
      assertEquals(0, nextMap.size());

   }


  @Test
  public void testRouteRemovedEvent() throws InterruptedException {
      init(false, null, null);
      testRouteAddedEvent();

      //Adding the details to create different routes
      previousSubject = McastRouteUpdate.mcastRouteUpdate(route1, emptySource, sinks);
      currentSubject = McastRouteUpdate.mcastRouteUpdate(route1, emptySource, sinks);
      // Creating new mcast event for route removing
      McastEvent event = new McastEvent(McastEvent.Type.ROUTE_REMOVED, previousSubject, currentSubject);
      cordMcast.listener.event(event);
      // There will be no forwarding objective
      assertAfter(WAIT, WAIT * 2, () -> assertEquals(0, forwardMap.size()));
      assertEquals(0, nextMap.size());

   }

  @Test
  public void testSourceAddedEvent() throws InterruptedException {
      init(false, null, null);
      // Adding route before adding source.
      testRouteAddedEvent();

      //Adding the details to create different routes
      previousSubject = McastRouteUpdate.mcastRouteUpdate(route1, emptySource, sinks);
      currentSubject = McastRouteUpdate.mcastRouteUpdate(route1, sources, sinks);
      // Creating new mcast event for source adding
      McastEvent event = new McastEvent(McastEvent.Type.SOURCES_ADDED, previousSubject, currentSubject);
      cordMcast.listener.event(event);
      // There will be no forwarding objective
      assertAfter(WAIT, WAIT * 2, () -> assertEquals(0, forwardMap.size()));
      assertEquals(0, nextMap.size());

   }

  @Test
  public void testSourcesRemovedEvent() throws InterruptedException {
      init(false, null, null);
      testSourceAddedEvent();

      //Adding the details to create different routes
      previousSubject = McastRouteUpdate.mcastRouteUpdate(route1, sources, sinks);
      currentSubject = McastRouteUpdate.mcastRouteUpdate(route1, emptySource, sinks);
      // Creating new mcast event for removing source
      // Warning message of unknown event will be displayed.
      McastEvent event = new McastEvent(McastEvent.Type.SOURCES_REMOVED, previousSubject, currentSubject);
      cordMcast.listener.event(event);
      assertAfter(WAIT, WAIT * 2, () -> assertEquals(0, forwardMap.size()));
      assertEquals(0, nextMap.size());
   }

    @Test
    public void mcastTestEventGeneration() throws InterruptedException {
      init(false, VlanId.vlanId("4000"), VlanId.NONE);
      //fetching route details used to push CordMcastStatisticsEvent.
      IpAddress testGroup = route1.group();
      String testSource = route1.source().isEmpty() ? "*" : route1.source().get().toString();
      VlanId testVlan = cordMcast.assignedVlan();

      // Thread is scheduled without any delay
      assertAfter(WAIT, WAIT * 2, () ->
              assertEquals(1, mockListener.mcastEventList.size()));

      for (CordMcastStatisticsEvent event: mockListener.mcastEventList) {
           assertEquals(event.type(), CordMcastStatisticsEvent.Type.STATUS_UPDATE);
      }

      CordMcastStatistics cordMcastStatistics = mockListener.mcastEventList.get(0).subject().get(0);
      assertEquals(VlanId.NONE, cordMcastStatistics.getVlanId());
      assertEquals(testVlan, cordMcastStatistics.getVlanId());
      assertEquals(testSource, cordMcastStatistics.getSourceAddress());
      assertEquals(testGroup, cordMcastStatistics.getGroupAddress());

      // Test for vlanEnabled
      Dictionary<String, Object> cfgDict = new Hashtable<>();
      cfgDict.put("vlanEnabled", true);

      ComponentContext componentContext = EasyMock.createMock(ComponentContext.class);
      expect(componentContext.getProperties()).andReturn(cfgDict);
      replay(componentContext);
      cordMcast.modified(componentContext);
      testVlan = cordMcast.assignedVlan();

      assertAfter(EVENT_GENERATION_PERIOD, EVENT_GENERATION_PERIOD * 1000, () ->
              assertEquals(2, mockListener.mcastEventList.size()));

      for (CordMcastStatisticsEvent event: mockListener.mcastEventList) {
          assertEquals(event.type(), CordMcastStatisticsEvent.Type.STATUS_UPDATE);
      }

      cordMcastStatistics = mockListener.mcastEventList.get(1).subject().get(0);
      assertNotEquals(VlanId.NONE, cordMcastStatistics.getVlanId());
      assertEquals(testVlan, cordMcastStatistics.getVlanId());
      assertEquals(testSource, cordMcastStatistics.getSourceAddress());
      assertEquals(testGroup, cordMcastStatistics.getGroupAddress());
    }
}
