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


package io.fireflysoftware.experience.cards.core.services;

import io.fireflysoftware.experience.cards.core.limits.CardLimitView;
import io.fireflysoftware.experience.cards.core.ports.AccessDecision;
import io.fireflysoftware.experience.cards.core.ports.AccessDeniedException;
import io.fireflysoftware.experience.cards.core.ports.AccessPort;
import io.fireflysoftware.experience.cards.core.ports.IdentityPort;
import io.fireflysoftware.experience.cards.core.ports.SubjectRef;
import io.fireflysoftware.experience.cards.core.ports.CardOperations;
import io.fireflysoftware.experience.cards.interfaces.dtos.IssueCardRequest;
import io.fireflysoftware.experience.cards.interfaces.dtos.IssueCardResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CardExperienceServiceImplTest {

    @Mock private IdentityPort identityPort;
    @Mock private AccessPort accessPort;
    @Mock private CardOperations cardOperations;

    private CardExperienceServiceImpl service;

    @BeforeEach
    void setUp() {
        lenient().when(identityPort.resolve(any())).thenReturn(Mono.just(new SubjectRef(IdentityPort.SAMPLE_ACTOR, "sandbox")));
        service = new CardExperienceServiceImpl(identityPort, accessPort, cardOperations);
    }

    @Test
    void issueCard_checksAccessThenDomainPort() {
        IssueCardRequest request = IssueCardRequest.builder()
                .customerId(UUID.randomUUID())
                .accountId(UUID.randomUUID())
                .cardProgramId(UUID.randomUUID())
                .build();
        IssueCardResponse response = IssueCardResponse.builder()
                .cardId(UUID.randomUUID())
                .status("ISSUED")
                .build();
        when(accessPort.check(any())).thenReturn(Mono.just(AccessDecision.grant()));
        when(cardOperations.issue(request)).thenReturn(Mono.just(response));

        StepVerifier.create(service.issueCard(request))
                .assertNext(body -> assertThat(body.getStatus()).isEqualTo("ISSUED"))
                .verifyComplete();
    }

    @Test
    void issueCard_deniedDoesNotCallDomain() {
        IssueCardRequest request = IssueCardRequest.builder()
                .customerId(UUID.randomUUID())
                .accountId(UUID.randomUUID())
                .cardProgramId(UUID.randomUUID())
                .build();
        when(accessPort.check(any())).thenReturn(Mono.just(AccessDecision.denied("no")));

        StepVerifier.create(service.issueCard(request))
                .expectError(AccessDeniedException.class)
                .verify();
        verify(cardOperations, never()).issue(any());
    }

    @Test
    void updateLimits_checksAccessThenDomainPort() {
        UUID cardId = UUID.randomUUID();
        UUID limitId = UUID.randomUUID();
        when(accessPort.check(any())).thenReturn(Mono.just(AccessDecision.grant()));
        when(cardOperations.updateLimits(cardId, limitId, new BigDecimal("200.00"), new BigDecimal("1000.00"),
                new BigDecimal("50.00"), "DAILY_SPENDING")).thenReturn(Mono.empty());

        StepVerifier.create(service.updateLimits(cardId, limitId, new BigDecimal("200.00"),
                new BigDecimal("1000.00"), new BigDecimal("50.00"), "DAILY_SPENDING")).verifyComplete();
        verify(cardOperations).updateLimits(cardId, limitId, new BigDecimal("200.00"),
                new BigDecimal("1000.00"), new BigDecimal("50.00"), "DAILY_SPENDING");
    }

    @Test
    void updateLimits_deniedDoesNotCallDomain() {
        UUID cardId = UUID.randomUUID();
        when(accessPort.check(any())).thenReturn(Mono.just(AccessDecision.denied("no")));

        StepVerifier.create(service.updateLimits(cardId, UUID.randomUUID(), new BigDecimal("1"),
                null, null, "DAILY_SPENDING"))
                .expectError(AccessDeniedException.class)
                .verify();
        verify(cardOperations, never()).updateLimits(any(), any(), any(), any(), any(), any());
    }

    @Test
    void limits_readsThroughTheDomainPort() {
        UUID cardId = UUID.randomUUID();
        CardLimitView limit = new CardLimitView(UUID.randomUUID(), "DAILY_SPENDING", new BigDecimal("200.00"));
        when(accessPort.check(any())).thenReturn(Mono.just(AccessDecision.grant()));
        when(cardOperations.limits(cardId)).thenReturn(Mono.just(List.of(limit)));

        StepVerifier.create(service.limits(cardId))
                .assertNext(rows -> assertThat(rows).containsExactly(limit))
                .verifyComplete();
    }
}
