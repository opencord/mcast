/*
 * Copyright 2019-2023 Open Networking Foundation (ONF) and the ONF Contributors
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

/**
 * Constants for default values of configurable properties.
 */
public final class OsgiPropertyConstants {

    private OsgiPropertyConstants() {
    }

    public static final String VLAN_ENABLED = "vlanEnabled";
    public static final boolean DEFAULT_VLAN_ENABLED = true;

    public static final String PRIORITY = "priority";
    public static final int DEFAULT_PRIORITY = 500;

    public static final String EVENT_GENERATION_PERIOD = "eventGenerationPeriodInSeconds";
    public static final int EVENT_GENERATION_PERIOD_DEFAULT = 30;
}
