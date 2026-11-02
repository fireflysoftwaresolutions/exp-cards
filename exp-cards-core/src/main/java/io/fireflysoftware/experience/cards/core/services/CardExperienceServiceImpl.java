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
import io.fireflysoftware.experience.cards.core.ports.AccessAction;
import io.fireflysoftware.experience.cards.core.ports.AccessGuard;
import io.fireflysoftware.experience.cards.core.ports.IdentityPort;
import io.fireflysoftware.experience.cards.core.ports.AccessPort;
import io.fireflysoftware.experience.cards.core.ports.AccessRequest;
import io.fireflysoftware.experience.cards.core.ports.CardOperations;
import io.fireflysoftware.experience.cards.interfaces.dtos.CardSummaryResponse;
import io.fireflysoftware.experience.cards.interfaces.dtos.IssueCardRequest;
import io.fireflysoftware.experience.cards.interfaces.dtos.IssueCardResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Card journey. Order: access check, then {@link CardOperations}.
 */
@Service
@RequiredArgsConstructor
public class CardExperienceServiceImpl implements CardExperienceService {

    private final IdentityPort identityPort;
    private final AccessPort accessPort;
    private final CardOperations cardOperations;

    @Override
    public Mono<IssueCardResponse> issueCard(IssueCardRequest request) {
        return AccessGuard.require(identityPort, accessPort, IdentityPort.SAMPLE_USERNAME,
                AccessRequest.of(request.getCustomerId(), AccessAction.CREATE, "CARD", request.getAccountId()),
                () -> cardOperations.issue(request));
    }

    @Override
    public Mono<CardSummaryResponse> getCardSummary(UUID cardId) {
        return AccessGuard.require(identityPort, accessPort, IdentityPort.SAMPLE_USERNAME,
                AccessRequest.of(cardId, AccessAction.READ, "CARD", cardId),
                () -> cardOperations.get(cardId));
    }

    @Override
    public Mono<Void> blockCard(UUID cardId, String reason) {
        return AccessGuard.require(identityPort, accessPort, IdentityPort.SAMPLE_USERNAME,
                AccessRequest.of(cardId, AccessAction.UPDATE, "CARD", cardId),
                () -> cardOperations.block(cardId, reason));
    }

    @Override
    public Mono<Void> cancelCard(UUID cardId, String reason) {
        return AccessGuard.require(identityPort, accessPort, IdentityPort.SAMPLE_USERNAME,
                AccessRequest.of(cardId, AccessAction.CLOSE, "CARD", cardId),
                () -> cardOperations.cancel(cardId, reason));
    }

    @Override
    public Mono<Void> updateLimits(UUID cardId, UUID limitId, BigDecimal dailyLimit, BigDecimal monthlyLimit,
                                   BigDecimal transactionLimit, String limitType) {
        return AccessGuard.require(identityPort, accessPort, IdentityPort.SAMPLE_USERNAME,
                AccessRequest.of(cardId, AccessAction.UPDATE, "CARD", cardId),
                () -> cardOperations.updateLimits(cardId, limitId, dailyLimit, monthlyLimit, transactionLimit, limitType));
    }

    @Override
    public Mono<List<CardLimitView>> limits(UUID cardId) {
        return AccessGuard.require(identityPort, accessPort, IdentityPort.SAMPLE_USERNAME,
                AccessRequest.of(cardId, AccessAction.READ, "CARD", cardId),
                () -> cardOperations.limits(cardId));
    }
}
