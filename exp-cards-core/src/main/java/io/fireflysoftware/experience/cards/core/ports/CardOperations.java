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


package io.fireflysoftware.experience.cards.core.ports;

import io.fireflysoftware.experience.cards.core.limits.CardLimitView;
import io.fireflysoftware.experience.cards.interfaces.dtos.CardSummaryResponse;
import io.fireflysoftware.experience.cards.interfaces.dtos.IssueCardRequest;
import io.fireflysoftware.experience.cards.interfaces.dtos.IssueCardResponse;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Card journey port. The domain adapter calls domain-banking-cards.
 */
public interface CardOperations {

    Mono<IssueCardResponse> issue(IssueCardRequest request);

    Mono<CardSummaryResponse> get(UUID cardId);


    Mono<Void> block(UUID cardId, String reason);

    Mono<Void> cancel(UUID cardId, String reason);

    Mono<Void> updateLimits(UUID cardId, UUID limitId, BigDecimal dailyLimit, BigDecimal monthlyLimit,
                            BigDecimal transactionLimit, String limitType);

    Mono<List<CardLimitView>> limits(UUID cardId);
}
