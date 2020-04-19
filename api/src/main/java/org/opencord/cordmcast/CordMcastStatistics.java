/*
 * Copyright 2018-present Open Networking Foundation
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

import org.onlab.packet.IpAddress;
import org.onlab.packet.VlanId;

/**
 * POJO Class to store group data from mcast event.
 */
public class CordMcastStatistics {
    //Group Address
    private IpAddress groupAddress;
    //Set of source address in a group.
    private String sourceAddress;
    //vlan id used in mcast event.
    private VlanId vlanId;
    private VlanId innerVlanId;

    public CordMcastStatistics(IpAddress groupAddress, String sourceAddresss, VlanId vlanId, VlanId innerVlanId) {
        this.vlanId = vlanId;
        this.innerVlanId = innerVlanId;
        this.sourceAddress = sourceAddresss;
        this.groupAddress = groupAddress;
    }

    public IpAddress getGroupAddress() {
        return groupAddress;
    }

    public void setGroupAddress(IpAddress groupAddress) {
        this.groupAddress = groupAddress;
    }

    public String getSourceAddress() {
        return sourceAddress;
    }

    public void setSourceAddress(String sourceAddress) {
        this.sourceAddress = sourceAddress;
    }

    public VlanId getVlanId() {
        return vlanId;
    }

    public void setVlanId(VlanId vlanId) {
        this.vlanId = vlanId;
    }

    public VlanId getInnerVlanId() {
        return innerVlanId;
    }

    public void setInnerVlanId(VlanId innerVlanId) {
        this.innerVlanId = innerVlanId;
    }
}
