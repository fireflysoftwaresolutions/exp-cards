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

import io.fireflysoftware.domain.banking.cards.sdk.api.CardBackofficeApi;
import io.fireflysoftware.domain.banking.cards.sdk.api.CardLimitsApi;
import io.fireflysoftware.domain.banking.cards.sdk.api.CardsApi;
import io.fireflysoftware.domain.banking.cards.sdk.model.IssueCardCommand;
import io.fireflysoftware.domain.banking.cards.sdk.model.UpdateCardLimitsCommand;
import io.fireflysoftware.experience.cards.core.limits.CardLimitView;
import io.fireflysoftware.experience.cards.core.ports.CardOperations;
import io.fireflysoftware.experience.cards.interfaces.dtos.CardSummaryResponse;
import io.fireflysoftware.experience.cards.interfaces.dtos.IssueCardRequest;
import io.fireflysoftware.experience.cards.interfaces.dtos.IssueCardResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Calls domain-banking-cards. Replace this class to send the same port through a process engine.
 */
@Component("domainCardAdapter")
@RequiredArgsConstructor
@ConditionalOnProperty(name = "firefly.experience.cards.ports.card-operations", havingValue = "domain", matchIfMissing = true)
public class DomainCardAdapterImpl implements CardOperations {

    private final CardsApi cardsApi;
    private final CardBackofficeApi cardBackofficeApi;
    private final CardLimitsApi cardLimitsApi;

    @Override
    public Mono<IssueCardResponse> issue(IssueCardRequest request) {
        IssueCardCommand command = new IssueCardCommand();
        command.setCustomerId(request.getCustomerId());
        command.setAccountId(request.getAccountId());
        command.setCardProgramId(request.getCardProgramId());
        return cardsApi.issueCard(command, UUID.randomUUID().toString())
                .map(result -> IssueCardResponse.builder()
                        .cardId(result.getCardId())
                        .executionId(result.getExecutionId())
                        .status(result.getStatus())
                        .build());
    }

    @Override
    public Mono<CardSummaryResponse> get(UUID cardId) {
        return cardBackofficeApi.getCard(cardId, UUID.randomUUID().toString())
                .map(card -> CardSummaryResponse.builder()
                        .cardId(card.getCardId())
                        .maskedCardNumber(card.getCardNumber())
                        .status(card.getCardStatus() == null ? null : card.getCardStatus().toString())
                        .cardholderName(card.getCardHolderName())
                        .build());
    }

    @Override
    public Mono<Void> block(UUID cardId, String reason) {
        return cardsApi.blockCard(cardId, reason, "CUSTOMER", UUID.randomUUID().toString()).then();
    }

    @Override
    public Mono<Void> cancel(UUID cardId, String reason) {
        return cardsApi.cancelCard(cardId, reason == null ? "CUSTOMER_REQUEST" : reason,
                "CUSTOMER", UUID.randomUUID().toString()).then();
    }

    @Override
    public Mono<Void> updateLimits(UUID cardId, UUID limitId, BigDecimal dailyLimit, BigDecimal monthlyLimit,
                                   BigDecimal transactionLimit, String limitType) {
        UpdateCardLimitsCommand command = new UpdateCardLimitsCommand();
        command.setCardId(cardId);
        command.setLimitId(limitId);
        command.setDailyLimit(dailyLimit);
        command.setMonthlyLimit(monthlyLimit);
        command.setTransactionLimit(transactionLimit);
        command.setLimitType(limitType);
        return cardLimitsApi.updateLimits(cardId, limitId, command, UUID.randomUUID().toString()).then();
    }

    @Override
    public Mono<List<CardLimitView>> limits(UUID cardId) {
        return cardBackofficeApi.getCardLimits(cardId, UUID.randomUUID().toString())
                .map(row -> new CardLimitView(
                        row.getCardLimitId(),
                        row.getLimitType() == null ? null : row.getLimitType().getValue(),
                        row.getLimitAmount()))
                .collectList();
    }
}
