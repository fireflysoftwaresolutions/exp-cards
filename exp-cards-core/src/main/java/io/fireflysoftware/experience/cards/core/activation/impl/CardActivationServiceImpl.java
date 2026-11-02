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


package io.fireflysoftware.experience.cards.core.activation.impl;

import io.fireflysoftware.experience.cards.core.activation.CardActivationClientFactory;
import java.util.Optional;
import java.util.UUID;
import io.fireflysoftware.domain.banking.cards.sdk.model.CardActivationRequest;

import io.fireflysoftware.experience.cards.interfaces.dtos.CardActivationResponse;
import io.fireflysoftware.experience.cards.core.activation.CardActivationService;

import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.server.ResponseStatusException;

@Service
public class CardActivationServiceImpl implements CardActivationService {

    private final Optional<CardActivationClientFactory> clients;

    public CardActivationServiceImpl(Optional<CardActivationClientFactory> clients) {
        this.clients = clients;
    }

    @Override
    public Mono<CardActivationResponse> activate(UUID cardId, UUID actionId, String proof) {
        return Mono.defer(() -> {
            if (cardId == null || actionId == null
                    || new UUID(0, 0).equals(cardId) || new UUID(0, 0).equals(actionId)
                    || proof == null
                    || !proof.matches("[A-Za-z0-9_-]{43,128}")) {
                return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Card, action and issued possession proof are required"));
            }
            if (clients.isEmpty()) {
                return Mono.error(new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                        "Card activation delegation is not configured"));
            }
            return clients.get().current(cardId, actionId)
                    .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                            "Current card activation delegation unavailable")))
                    .flatMap(client -> client.revalidate().then(Mono.defer(() -> client.api()
                            .activateCard(cardId, actionId,
                                    new CardActivationRequest().activationProof(proof))
                            .timeout(client.remaining()))))
                    .switchIfEmpty(Mono.error(invalidReceipt()))
                    .map(receipt -> checked(cardId, actionId, receipt))
                    .onErrorMap(java.util.concurrent.TimeoutException.class, failure -> unknownOutcome())
                    .onErrorMap(org.springframework.web.reactive.function.client.WebClientRequestException.class,
                            failure -> unknownOutcome())
                    .onErrorResume(WebClientResponseException.class, failure -> {
                        int status = failure.getStatusCode().value();
                        if (status == 409 || status == 422) {
                            try {
                                var result = checked(cardId, actionId, failure.getResponseBodyAs(io.fireflysoftware.domain.banking.cards.sdk.model.CardActivationResponse.class));
                                var expected = status == 422 ? CardActivationResponse.State.REJECTED
                                        : CardActivationResponse.State.RECONCILIATION_REQUIRED;
                                return result.state() == expected ? Mono.just(result) : Mono.error(invalidReceipt());
                            } catch (RuntimeException invalid) {
                                return Mono.error(new ResponseStatusException(failure.getStatusCode(),
                                        "Card activation owner refused the request"));
                            }
                        }
                        String reason = status >= 500 || status == 408 || status == 429
                                ? "Card activation outcome is unknown; recover the original action"
                                : "Card activation owner refused the request";
                        return Mono.error(new ResponseStatusException(failure.getStatusCode(), reason));
                    });
        });
    }

    private static CardActivationResponse checked(UUID cardId, UUID actionId, io.fireflysoftware.domain.banking.cards.sdk.model.CardActivationResponse receipt) {
        if (receipt == null || !cardId.equals(receipt.getCardId())
                || !actionId.equals(receipt.getActionId()) || receipt.getState() == null) {
            throw invalidReceipt();
        }
        return new CardActivationResponse(receipt.getActionId(), receipt.getCardId(),
                CardActivationResponse.State.valueOf(receipt.getState().name()), receipt.getActivationTime() == null ? null : receipt.getActivationTime().atOffset(java.time.ZoneOffset.UTC));
    }

    private static ResponseStatusException unknownOutcome() {
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Card activation outcome is unknown; recover the original action");
    }

    private static ResponseStatusException invalidReceipt() {
        return new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Matching card activation owner receipt required");
    }
}
