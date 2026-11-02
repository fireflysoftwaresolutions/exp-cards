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


package io.fireflysoftware.experience.cards.web.controllers;

import io.fireflysoftware.experience.cards.core.activation.CardActivationCallerToken;
import io.fireflysoftware.experience.cards.interfaces.dtos.CardActivationRequest;
import io.fireflysoftware.experience.cards.interfaces.dtos.CardActivationResponse;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.server.ResponseStatusException;
import org.fireflyframework.web.idempotency.annotation.DisableIdempotency;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;


import io.fireflysoftware.experience.cards.core.limits.CardLimitView;
import io.fireflysoftware.experience.cards.core.services.CardExperienceService;
import io.fireflysoftware.experience.cards.interfaces.dtos.CardSummaryResponse;
import io.fireflysoftware.experience.cards.interfaces.dtos.IssueCardRequest;
import io.fireflysoftware.experience.cards.interfaces.dtos.IssueCardResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@org.springframework.context.annotation.Import(io.fireflysoftware.experience.cards.web.openapi.CardActivationOpenApiConfiguration.class)
@RestController
@RequestMapping("/api/v1/cards")
@RequiredArgsConstructor
@Tag(name = "Cards", description = "Card experience operations")
@io.swagger.v3.oas.annotations.security.SecurityScheme(name = "cardBearer",
        type = io.swagger.v3.oas.annotations.enums.SecuritySchemeType.HTTP, scheme = "bearer", bearerFormat = "JWT")
public class CardsController {

    private final CardExperienceService cardService;
    private final io.fireflysoftware.experience.cards.core.activation.CardActivationService activationService;

    @PostMapping
    @Operation(summary = "Issue a card", description = "Checks access, then issues the card through the domain port")
    public Mono<ResponseEntity<IssueCardResponse>> issueCard(@Valid @RequestBody IssueCardRequest request) {
        return cardService.issueCard(request)
                .map(body -> ResponseEntity.status(HttpStatus.CREATED).body(body));
    }

    @GetMapping("/{cardId}")
    @Operation(
            summary = "Get card summary",
            description = "Retrieves the card summary including balances and status"
    )
    @ApiResponse(responseCode = "200", description = "Card found")
    @ApiResponse(responseCode = "404", description = "Card not found")
    public Mono<ResponseEntity<CardSummaryResponse>> getCardSummary(
            @Parameter(description = "Card identifier")
            @PathVariable UUID cardId) {
        return cardService.getCardSummary(cardId)
                .map(ResponseEntity::ok)
                .defaultIfEmpty(ResponseEntity.notFound().build());
    }

    @PostMapping(value = "/{cardId}/activate", consumes = "application/json", produces = "application/json")
    @Operation(summary = "Request card activation", description = "Submits issued possession proof to the card owner; pending and uncertain receipts do not confirm activation")
    @SecurityRequirement(name = "cardBearer")
    @DisableIdempotency
    public Mono<ResponseEntity<CardActivationResponse>> activateCard(
            @PathVariable UUID cardId,
            @RequestHeader("X-Idempotency-Key") UUID actionId,
            @Valid @RequestBody CardActivationRequest request, ServerHttpRequest transport) {
        var authorization = transport.getHeaders().get(org.springframework.http.HttpHeaders.AUTHORIZATION);
        if (authorization == null || authorization.size() != 1 || !authorization.getFirst().startsWith("Bearer ")) {
            return Mono.error(new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Original card activation bearer required"));
        }
        final CardActivationCallerToken caller;
        try {
            caller = new CardActivationCallerToken(authorization.getFirst().substring(7));
        } catch (IllegalArgumentException invalid) {
            return Mono.error(new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Original card activation bearer required"));
        }
        return activationService.activate(cardId, actionId, request.activationProof())
                .map(receipt -> ResponseEntity.status(switch (receipt.state()) {
                    case CONFIRMED -> 200;
                    case RESERVED, UNCERTAIN -> 202;
                    case REJECTED -> 422;
                    case RECONCILIATION_REQUIRED -> 409;
                }).body(receipt)).contextWrite(context -> context.put(CardActivationCallerToken.class, caller));
    }

    @GetMapping(value = "/{cardId}/activation/{actionId}", produces = "application/json")
    @Operation(summary = "Read the original card activation receipt", description = "Requires current ownership. This read creates no action, sends no proof or issuer command, and leaves uncertain receipts uncertain.")
    @SecurityRequirement(name = "cardBearer")
    @DisableIdempotency
    public Mono<ResponseEntity<CardActivationResponse>> recoverCardActivation(
            @PathVariable UUID cardId, @PathVariable UUID actionId, ServerHttpRequest transport) {
        var authorization = transport.getHeaders().get(org.springframework.http.HttpHeaders.AUTHORIZATION);
        if (authorization == null || authorization.size() != 1 || !authorization.getFirst().startsWith("Bearer ")) {
            return Mono.error(new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Original card activation bearer required"));
        }
        final CardActivationCallerToken caller;
        try {
            caller = new CardActivationCallerToken(authorization.getFirst().substring(7));
        } catch (IllegalArgumentException invalid) {
            return Mono.error(new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Original card activation bearer required"));
        }
        return activationService.recover(cardId, actionId)
                .map(receipt -> ResponseEntity.ok().cacheControl(org.springframework.http.CacheControl.noStore()).body(receipt))
                .contextWrite(context -> context.put(CardActivationCallerToken.class, caller));
    }

    @PostMapping("/{cardId}/block")
    @Operation(
            summary = "Block a card",
            description = "Blocks a card temporarily"
    )
    @ApiResponse(responseCode = "204", description = "Card blocked successfully")
    @ApiResponse(responseCode = "404", description = "Card not found")
    public Mono<ResponseEntity<Void>> blockCard(
            @Parameter(description = "Card identifier")
            @PathVariable UUID cardId,
            @Parameter(description = "Reason for blocking")
            @RequestParam(required = false) String reason) {
        return cardService.blockCard(cardId, reason)
                .then(Mono.just(ResponseEntity.noContent().<Void>build()));
    }

    @DeleteMapping("/{cardId}")
    @Operation(
            summary = "Cancel a card",
            description = "Cancels a card permanently"
    )
    @ApiResponse(responseCode = "204", description = "Card cancelled successfully")
    @ApiResponse(responseCode = "404", description = "Card not found")
    public Mono<ResponseEntity<Void>> cancelCard(
            @Parameter(description = "Card identifier")
            @PathVariable UUID cardId,
            @Parameter(description = "Reason for cancellation")
            @RequestParam(required = false) String reason) {
        return cardService.cancelCard(cardId, reason)
                .then(Mono.just(ResponseEntity.noContent().<Void>build()));
    }

    @PutMapping("/{cardId}/limits/{limitId}")
    @Operation(summary = "Set card limits", description = "Checks access, then stores the spending limits through the domain port")
    public Mono<ResponseEntity<Void>> updateLimits(@PathVariable UUID cardId, @PathVariable UUID limitId,
                                                    @RequestBody LimitBody body) {
        return cardService.updateLimits(cardId, limitId, body.dailyLimit(), body.monthlyLimit(),
                        body.transactionLimit(), body.limitType())
                .then(Mono.just(ResponseEntity.noContent().<Void>build()));
    }

    @GetMapping("/{cardId}/limits")
    @Operation(summary = "Read card limits", description = "Checks access, then reads the spending limits through the domain port")
    public Mono<ResponseEntity<List<CardLimitView>>> limits(@PathVariable UUID cardId) {
        return cardService.limits(cardId).map(ResponseEntity::ok);
    }

    public record LimitBody(BigDecimal dailyLimit, BigDecimal monthlyLimit, BigDecimal transactionLimit, String limitType) {
    }
}
