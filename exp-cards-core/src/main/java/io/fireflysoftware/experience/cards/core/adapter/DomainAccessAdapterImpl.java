/*
 * Copyright 2025 Firefly Software Foundation
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


package io.fireflysoftware.experience.cards.core.adapter;

import io.fireflysoftware.experience.cards.core.ports.AccessDecision;
import io.fireflysoftware.experience.cards.core.ports.AccessPort;
import io.fireflysoftware.experience.cards.core.ports.AccessRequest;
import io.fireflysoftware.experience.cards.core.ports.SecurityCenterClient;
import reactor.core.publisher.Mono;

/**
 * Default security-center adapter. Active when {@code firefly.experience.access.adapter=domain}.
 * A future BPM engine replaces this class, not the bureau adapter inside the data domain.
 */
public class DomainAccessAdapterImpl implements AccessPort {

    private final SecurityCenterClient securityCenterClient;

    public DomainAccessAdapterImpl(SecurityCenterClient securityCenterClient) {
        this.securityCenterClient = securityCenterClient;
    }

    @Override
    public Mono<AccessDecision> check(AccessRequest request) {
        return securityCenterClient.check(request);
    }
}
