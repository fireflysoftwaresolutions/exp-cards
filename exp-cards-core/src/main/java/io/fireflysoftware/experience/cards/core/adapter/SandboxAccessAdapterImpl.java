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
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * Allows every sample journey so OpenPlatform can run without an external identity provider.
 * Replace it by setting {@code firefly.experience.access.adapter=domain}, which selects
 * {@link DomainAccessAdapterImpl} and calls domain-core-security-center.
 */
@Component("sandboxAccessAdapter")
@ConditionalOnProperty(name = "firefly.experience.access.adapter", havingValue = "sandbox")
public class SandboxAccessAdapterImpl implements AccessPort {

    @Override
    public Mono<AccessDecision> check(AccessRequest request) {
        return Mono.just(AccessDecision.grant());
    }
}
