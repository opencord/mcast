/*
 * Copyright 2018-2023 Open Networking Foundation (ONF) and the ONF Contributors
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


import com.google.common.collect.Lists;
import org.onlab.packet.VlanId;
import org.onlab.util.SafeRecurringTask;
import org.onosproject.cfg.ComponentConfigService;
import org.onosproject.cluster.ClusterService;
import org.onosproject.cluster.LeadershipService;
import org.onosproject.event.AbstractListenerManager;
import org.onosproject.mcast.api.McastRoute;
import org.onosproject.mcast.api.MulticastRouteService;
import org.opencord.cordmcast.CordMcastStatistics;
import org.opencord.cordmcast.CordMcastStatisticsEvent;
import org.opencord.cordmcast.CordMcastStatisticsEventListener;
import org.opencord.cordmcast.CordMcastStatisticsService;
import org.osgi.service.component.ComponentContext;
import org.osgi.service.component.annotations.Modified;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.osgi.service.component.annotations.ReferencePolicy;
import org.slf4j.Logger;

import java.util.Dictionary;
import java.util.Set;
import java.util.List;
import java.util.ArrayList;
import java.util.Properties;
import java.util.Objects;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import static com.google.common.base.Strings.isNullOrEmpty;
import static org.onlab.util.Tools.get;
import static org.opencord.cordmcast.impl.OsgiPropertyConstants.EVENT_GENERATION_PERIOD;
import static org.opencord.cordmcast.impl.OsgiPropertyConstants.EVENT_GENERATION_PERIOD_DEFAULT;

import static org.slf4j.LoggerFactory.getLogger;

/**
 * For managing CordMcastStatisticsEvent.
 */
@Component(immediate = true, property = { EVENT_GENERATION_PERIOD + ":Integer=" + EVENT_GENERATION_PERIOD_DEFAULT, })
public class CordMcastStatisticsManager
        extends AbstractListenerManager<CordMcastStatisticsEvent, CordMcastStatisticsEventListener>
        implements CordMcastStatisticsService {
    private static final String MCAST_NOT_RUNNING = "Multicast is not running.";
    private static final String CORD_MCAST_STATISTICS_LEADERSHIP = "cord-mcast-statistics-leadership";

    @Reference(cardinality = ReferenceCardinality.OPTIONAL,
            bind = "bindMcastRouteService",
            unbind = "unbindMcastRouteService",
            policy = ReferencePolicy.DYNAMIC)
    protected volatile MulticastRouteService mcastService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected ComponentConfigService componentConfigService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected ClusterService clusterService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected LeadershipService leadershipService;


    /**
     * Multicast Statistics generation time interval.
     **/
    private int eventGenerationPeriodInSeconds = EVENT_GENERATION_PERIOD_DEFAULT;
    private final Logger log = getLogger(getClass());
    private ScheduledFuture<?> scheduledFuture = null;
    private ScheduledExecutorService executor;

    private VlanId vlanId;
    private VlanId innerVlanId;

    @Activate
    public void activate(ComponentContext context) {
        leadershipService.runForLeadership(CORD_MCAST_STATISTICS_LEADERSHIP);
        eventDispatcher.addSink(CordMcastStatisticsEvent.class, listenerRegistry);
        executor = Executors.newScheduledThreadPool(1);
        componentConfigService.registerProperties(getClass());
        modified(context);
        log.info("CordMcastStatisticsManager activated.");
    }

    @Modified
    public void modified(ComponentContext context) {
        Dictionary<?, ?> properties = context != null ? context.getProperties() : new Properties();
        try {
            String s = get(properties, EVENT_GENERATION_PERIOD);
            eventGenerationPeriodInSeconds =
                    isNullOrEmpty(s) ? EVENT_GENERATION_PERIOD_DEFAULT : Integer.parseInt(s.trim());
        } catch (NumberFormatException ne) {
            log.error("Unable to parse configuration parameter for eventGenerationPeriodInSeconds", ne);
            eventGenerationPeriodInSeconds = EVENT_GENERATION_PERIOD_DEFAULT;
        }
        if (scheduledFuture != null) {
            scheduledFuture.cancel(true);
        }
        scheduledFuture = executor.scheduleAtFixedRate(SafeRecurringTask.wrap(this::publishEvent),
                0, eventGenerationPeriodInSeconds, TimeUnit.SECONDS);
    }

    @Deactivate
    public void deactivate() {
        eventDispatcher.removeSink(CordMcastStatisticsEvent.class);
        scheduledFuture.cancel(true);
        executor.shutdown();
        leadershipService.withdraw(CORD_MCAST_STATISTICS_LEADERSHIP);
        log.info("CordMcastStatisticsManager deactivated.");
    }

    protected void bindMcastRouteService(MulticastRouteService service) {
        mcastService = service;
        log.info("Multicast route service binds to onos.");
    }

    protected void unbindMcastRouteService(MulticastRouteService service) {
        mcastService = null;
        log.info("Multicast route service unbinds from onos.");
    }

    public List<CordMcastStatistics> getMcastDetails() {
        if (mcastService == null) {
            log.warn(MCAST_NOT_RUNNING);
            return Lists.newArrayList();
        }
        List<CordMcastStatistics> mcastData = new ArrayList<CordMcastStatistics>();
        Set<McastRoute> routes = mcastService.getRoutes();
        routes.forEach(route -> {
            mcastData.add(new CordMcastStatistics(route.group(),
                    route.source().isEmpty() ? "*" : route.source().get().toString(),
                    vlanId, innerVlanId));
        });
        return mcastData;
    }

    @Override
    public void setVlanValue(VlanId vlanValue) {
        vlanId = vlanValue;
    }

    @Override
    public void setInnerVlanValue(VlanId innerVlanValue) {
        innerVlanId = innerVlanValue;
    }

    /**
     * pushing mcast stat data as event.
     */
    protected void publishEvent() {
        // Only publish events if we are the cluster leader for Igmp-stats
        if (!Objects.equals(leadershipService.getLeader(CORD_MCAST_STATISTICS_LEADERSHIP),
                clusterService.getLocalNode().id())) {
            log.debug("This is not leader of : {}", CORD_MCAST_STATISTICS_LEADERSHIP);
            return;
        }
        log.debug("pushing cord mcast event to kafka");
        List<CordMcastStatistics> routeList = getMcastDetails();
        routeList.forEach(mcastStats -> {
            log.debug("Group: " +
                    (mcastStats.getGroupAddress() != null ? mcastStats.getGroupAddress().toString() : "null") +
                    " | Source: " +
                    (mcastStats.getSourceAddress() != null ? mcastStats.getSourceAddress().toString() : "null") +
                    " | Vlan: " +
                    (mcastStats.getVlanId() != null ? mcastStats.getVlanId().toString() : "null") +
                    " | InnerVlan: " +
                    (mcastStats.getInnerVlanId() != null ? mcastStats.getInnerVlanId().toString() : "null"));
        });
        post(new CordMcastStatisticsEvent(CordMcastStatisticsEvent.Type.STATUS_UPDATE, routeList));
    }
}
