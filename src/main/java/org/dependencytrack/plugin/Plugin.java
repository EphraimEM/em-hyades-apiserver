/*
 * This file is part of Dependency-Track.
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
 *
 * SPDX-License-Identifier: Apache-2.0
 * Copyright (c) OWASP Foundation. All Rights Reserved.
 */
package org.dependencytrack.plugin;

/**
 * @since 5.6.0
 */
public interface Plugin {

    /**
     * @return The name of the plugin. Can contain lowercase letters, numbers, and periods.
     */
    String name();

    /**
     * @return Whether this plugin is required. Required plugins must have at least one active {@link Provider}.
     */
    boolean required();

    /**
     * @return Class of the {@link ProviderFactory}
     */
    Class<? extends ProviderFactory<? extends Provider>> providerFactoryClass();

    /**
     * @return Class of the {@link Provider}
     */
    Class<? extends Provider> providerClass();

}
