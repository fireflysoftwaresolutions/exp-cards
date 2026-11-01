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
import io.fireflysoftware.experience.cards.core.ports.IdentityPort;
import io.fireflysoftware.experience.cards.core.ports.SubjectRef;
import io.fireflysoftware.security.center.sdk.api.AccessControllerApi;
import io.fireflysoftware.security.center.sdk.api.IdentityControllerApi;
import io.fireflysoftware.security.center.sdk.invoker.ApiClient;
import io.fireflysoftware.security.center.sdk.model.IdentityRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Binds identity and access to domain-core-security-center through its SDK.
 * The security center chooses its own identity adapter.
 */
@Configuration
@ConditionalOnProperty(name = "firefly.experience.access.adapter", havingValue = "domain", matchIfMissing = true)
public class DomainAccessConfiguration {

    @Bean
    public IdentityControllerApi identityControllerApi(
            @Value("${api-configuration.domain-platform.security-center.base-path:http://localhost:8085}")
            String basePath) {
        return new IdentityControllerApi(client(basePath));
    }

    @Bean
    public AccessControllerApi accessControllerApi(
            @Value("${api-configuration.domain-platform.security-center.base-path:http://localhost:8085}")
            String basePath) {
        return new AccessControllerApi(client(basePath));
    }

    @Bean
    public IdentityPort identityPort(IdentityControllerApi identityControllerApi) {
        return new SdkIdentityClientImpl(identityControllerApi);
    }

    @Bean
    public AccessPort accessPort(AccessControllerApi accessControllerApi) {
        return new SdkAccessClientImpl(accessControllerApi);
    }

    private static ApiClient client(String basePath) {
        ApiClient client = new ApiClient();
        client.setBasePath(basePath.endsWith("/") ? basePath.substring(0, basePath.length() - 1) : basePath);
        return client;
    }

    public static final class SdkIdentityClientImpl implements IdentityPort {

        private final IdentityControllerApi api;

        public SdkIdentityClientImpl(IdentityControllerApi api) {
            this.api = api;
        }

        @Override
        public Mono<SubjectRef> resolve(String username) {
            return api.resolveSubject(new IdentityRequest().username(username))
                    .map(subject -> new SubjectRef(
                            UUID.fromString(subject.getActorId()),
                            subject.getProvider() == null ? null : subject.getProvider().getValue()));
        }
    }

    public static final class SdkAccessClientImpl implements AccessPort {

        private final AccessControllerApi api;

        public SdkAccessClientImpl(AccessControllerApi api) {
            this.api = api;
        }

        @Override
        public Mono<AccessDecision> check(AccessRequest request) {
            io.fireflysoftware.security.center.sdk.model.AccessRequest body =
                    new io.fireflysoftware.security.center.sdk.model.AccessRequest()
                            .actorId(request.actorId() == null ? null : request.actorId().toString())
                            .action(io.fireflysoftware.security.center.sdk.model.AccessRequest.ActionEnum
                                    .fromValue(request.action().name()))
                            .resourceType(resource(request.resourceType()))
                            .resourceId(request.resourceId() == null ? null : request.resourceId().toString());
            return api.decideAccess(body, UUID.randomUUID().toString())
                    .map(decision -> Boolean.TRUE.equals(decision.getAllowed())
                            ? AccessDecision.permit(decision.getReasonCode() == null ? "SANDBOX_GRANT" : decision.getReasonCode())
                            : AccessDecision.denied(decision.getReasonCode() == null ? "ACTOR_NOT_ENTITLED" : decision.getReasonCode()));
        }

        private static io.fireflysoftware.security.center.sdk.model.AccessRequest.ResourceTypeEnum resource(String value) {
            String normalized = value == null ? "" : value.trim().toUpperCase();
            if ("CUSTOMER".equals(normalized)) {
                normalized = "PARTY";
            }
            return io.fireflysoftware.security.center.sdk.model.AccessRequest.ResourceTypeEnum.fromValue(normalized);
        }
    }
}
