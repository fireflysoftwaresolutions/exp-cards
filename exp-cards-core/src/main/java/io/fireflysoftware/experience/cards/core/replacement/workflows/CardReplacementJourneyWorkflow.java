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


package io.fireflysoftware.experience.cards.core.replacement.workflows;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.fireflysoftware.domain.banking.cards.sdk.api.CardBackofficeApi;
import io.fireflysoftware.domain.banking.cards.sdk.api.CardsApi;
import io.fireflysoftware.domain.common.notifications.sdk.api.NotificationsApi;
import io.fireflysoftware.domain.people.sdk.api.PartyContactQueriesApi;
import io.fireflysoftware.domain.common.notifications.sdk.model.SendNotificationCommand;
import io.fireflysoftware.experience.cards.core.replacement.commands.ReportCardLostCommand;
import io.fireflysoftware.experience.cards.core.replacement.commands.VerifyIdentityCommand;
import io.fireflysoftware.experience.cards.core.replacement.queries.CardReplacementJourneyStatusDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.fireflyframework.orchestration.core.argument.FromStep;
import org.fireflyframework.orchestration.core.argument.Input;
import org.fireflyframework.orchestration.core.argument.SetVariable;
import org.fireflyframework.orchestration.core.argument.Variable;
import org.fireflyframework.orchestration.core.context.ExecutionContext;
import org.fireflyframework.orchestration.core.model.StepStatus;
import org.fireflyframework.orchestration.core.model.TriggerMode;
import org.fireflyframework.orchestration.workflow.annotation.OnWorkflowComplete;
import org.fireflyframework.orchestration.workflow.annotation.OnWorkflowError;
import org.fireflyframework.orchestration.workflow.annotation.WaitForSignal;
import org.fireflyframework.orchestration.workflow.annotation.Workflow;
import org.fireflyframework.orchestration.workflow.annotation.WorkflowQuery;
import org.fireflyframework.orchestration.workflow.annotation.WorkflowStep;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.UUID;

/**
 * Signal-driven workflow for card replacement (lost/stolen).
 * <p>
 * Execution flow:
 * <pre>
 * Layer 0:  [report-card-lost]
 * Layer 1:  [block-old-card] [send-alert]              ← parallel
 * Layer 2:  [verify-identity]                           ← @WaitForSignal("identity-verified")
 * Layer 3:  [create-replacement]
 * Layer 4:  [ship-card] OR [activate-virtual]
 * Layer 5:  [send-notification]
 * </pre>
 */
@Slf4j
@RequiredArgsConstructor
@Component
@Workflow(
    id = CardReplacementJourneyWorkflow.WORKFLOW_ID,
    name = "Card Replacement Journey",
    triggerMode = TriggerMode.SYNC,
    timeoutMs = 86400000,
    publishEvents = true,
    layerConcurrency = 0
)
public class CardReplacementJourneyWorkflow {

    public static final String WORKFLOW_ID = "card-replacement-journey";
    public static final String QUERY_JOURNEY_STATUS = "journeyStatus";

    public static final String STEP_REPORT_LOST = "report-card-lost";
    public static final String STEP_BLOCK_OLD_CARD = "block-old-card";
    public static final String STEP_SEND_ALERT = "send-alert";
    public static final String STEP_VERIFY_IDENTITY = "verify-identity";
    public static final String STEP_CREATE_REPLACEMENT = "create-replacement";
    public static final String STEP_ACTIVATE_OR_SHIP = "activate-or-ship";
    public static final String STEP_SEND_NOTIFICATION = "send-notification";

    public static final String SIGNAL_IDENTITY_VERIFIED = "identity-verified";

    public static final String VAR_CUSTOMER_ID = "customerId";
    public static final String VAR_OLD_CARD_ID = "oldCardId";
    public static final String VAR_NEW_CARD_ID = "newCardId";
    public static final String VAR_IS_PHYSICAL = "isPhysical";

    public static final String PHASE_BLOCKING = "BLOCKING_OLD_CARD";
    public static final String PHASE_AWAITING_VERIFICATION = "AWAITING_IDENTITY_VERIFICATION";
    public static final String PHASE_CREATING_REPLACEMENT = "CREATING_REPLACEMENT";
    public static final String PHASE_SHIPPING = "SHIPPING_CARD";
    public static final String PHASE_COMPLETED = "COMPLETED";

    private static final String NOTIFICATION_CHANNEL = "AUTO";
    private static final String TEMPLATE_ALERT = "CARD_LOST_ALERT";
    private static final String TEMPLATE_REPLACEMENT = "CARD_REPLACEMENT_READY";

    private final CardsApi cardsApi;
    private final CardBackofficeApi cardBackofficeApi;
    private final NotificationsApi notificationsApi;
    private final PartyContactQueriesApi partyContactQueriesApi;
    private final ObjectMapper objectMapper;

    @WorkflowStep(id = STEP_REPORT_LOST)
    @SetVariable(VAR_OLD_CARD_ID)
    public Mono<UUID> reportCardLost(@Input ReportCardLostCommand cmd, ExecutionContext ctx) {
        ctx.putVariable(VAR_CUSTOMER_ID, cmd.getCustomerId());
        ctx.putVariable(VAR_IS_PHYSICAL, cmd.getRequestPhysicalReplacement() != null && cmd.getRequestPhysicalReplacement());
        return cardBackofficeApi.getCard(cmd.getCardId(), UUID.randomUUID().toString())
                .map(card -> {
                    if (card == null || card.getCardId() == null) {
                        throw new IllegalStateException("CARD_NOT_RECORDED");
                    }
                    log.info("Card reported lost/stolen: cardId={} for customer: {}", card.getCardId(), cmd.getCustomerId());
                    return card.getCardId();
                });
    }

    @WorkflowStep(id = STEP_BLOCK_OLD_CARD, dependsOn = STEP_REPORT_LOST,
                  compensatable = true, compensationMethod = "compensateUnblockCard")
    public Mono<Void> blockOldCard(@Variable(VAR_OLD_CARD_ID) UUID oldCardId,
                                    @Input ReportCardLostCommand cmd) {
        return cardsApi.blockCard(oldCardId, cmd.getReason(), "SYSTEM", UUID.randomUUID().toString())
                .doOnNext(r -> log.info("Blocked old card: cardId={}", oldCardId))
                .then();
    }

    @WorkflowStep(id = STEP_SEND_ALERT, dependsOn = STEP_REPORT_LOST)
    public Mono<Void> sendSecurityAlert(@Variable(VAR_CUSTOMER_ID) UUID customerId,
                                         @Input ReportCardLostCommand cmd) {
        SendNotificationCommand notifCmd = new SendNotificationCommand()
                .partyId(customerId)
                .channel(NOTIFICATION_CHANNEL)
                .templateCode(TEMPLATE_ALERT)
                .subject("Security Alert: Card Reported Lost")
                .recipientEmail(cmd.getEmail());

        if (cmd.getPhone() != null) {
            notifCmd.recipientPhone(cmd.getPhone());
        }

        return notificationsApi.sendNotification(notifCmd, UUID.randomUUID().toString())
                .doOnNext(r -> log.info("Sent security alert for customer: {}", customerId))
                .then();
    }

    @WorkflowStep(id = STEP_VERIFY_IDENTITY, dependsOn = {STEP_BLOCK_OLD_CARD, STEP_SEND_ALERT})
    @WaitForSignal(SIGNAL_IDENTITY_VERIFIED)
    public Mono<Boolean> verifyIdentity(@Variable(VAR_CUSTOMER_ID) UUID customerId,
                                         Object signalData) {
        VerifyIdentityCommand cmd = mapSignalPayload(signalData, VerifyIdentityCommand.class);
        if (cmd.getDocumentNumber() == null || cmd.getDocumentNumber().isBlank()) {
            return Mono.error(new IllegalStateException("IDENTITY_DOCUMENT_REQUIRED"));
        }
        String expected = cmd.getDocumentNumber().trim();
        return partyContactQueriesApi.getPartyIdentityDocuments(customerId, UUID.randomUUID().toString())
                .flatMap(page -> {
                    boolean recorded = page.getContent() != null && page.getContent().stream()
                            .anyMatch(row -> sameDocumentNumber(row, expected));
                    if (!recorded) {
                        return Mono.error(new IllegalStateException("IDENTITY_DOCUMENT_NOT_ON_FILE"));
                    }
                    log.info("Identity document on file for customer: {}", customerId);
                    return Mono.just(true);
                });
    }

    @WorkflowStep(id = STEP_CREATE_REPLACEMENT, dependsOn = STEP_VERIFY_IDENTITY,
                  compensatable = true, compensationMethod = "compensateCancelNewCard")
    @SetVariable(VAR_NEW_CARD_ID)
    public Mono<UUID> createReplacementCard(@Variable(VAR_CUSTOMER_ID) UUID customerId,
                                             @Variable(VAR_OLD_CARD_ID) UUID oldCardId,
                                             @Input ReportCardLostCommand cmd) {
        String reason = cmd.getReason() == null || cmd.getReason().isBlank() ? "LOST" : cmd.getReason();
        return cardsApi.replaceCard(oldCardId, reason, true, true, UUID.randomUUID().toString())
                .flatMap(response -> {
                    if (response.getNewCardId() == null) {
                        return Mono.error(new IllegalStateException("CARD_REPLACEMENT_NOT_RECORDED"));
                    }
                    log.info("Replacement card {} status {} replaces {} for customer {}",
                            response.getNewCardId(), response.getStatus(), oldCardId, customerId);
                    return Mono.just(response.getNewCardId());
                });
    }

    @WorkflowStep(id = STEP_ACTIVATE_OR_SHIP, dependsOn = STEP_CREATE_REPLACEMENT)
    public Mono<Void> activateOrShipCard(@Variable(VAR_NEW_CARD_ID) UUID newCardId,
                                          @Variable(VAR_IS_PHYSICAL) Boolean isPhysical) {
        if (Boolean.TRUE.equals(isPhysical)) {
            return cardBackofficeApi.getPhysicalCard(newCardId, UUID.randomUUID().toString())
                    .flatMap(card -> {
                        if (card == null || card.getPhysicalCardId() == null) {
                            return Mono.error(new IllegalStateException("PHYSICAL_CARD_NOT_RECORDED"));
                        }
                        log.info("Physical card {} manufacturing status {}",
                                card.getPhysicalCardId(), card.getManufacturingStatus());
                        return Mono.<Void>empty();
                    });
        } else {
            return Mono.error(new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
                    "Caller-authorized activation with owner-issued possession proof is required"));
        }
    }

    @WorkflowStep(id = STEP_SEND_NOTIFICATION, dependsOn = STEP_ACTIVATE_OR_SHIP)
    public Mono<Void> sendCompletionNotification(@Variable(VAR_CUSTOMER_ID) UUID customerId,
                                                  @Variable(VAR_NEW_CARD_ID) UUID newCardId,
                                                  @Variable(VAR_IS_PHYSICAL) Boolean isPhysical) {
        String subject = Boolean.TRUE.equals(isPhysical)
                ? "Your Replacement Card is on the Way"
                : "Your Virtual Replacement Card is Ready";

        SendNotificationCommand notifCmd = new SendNotificationCommand()
                .partyId(customerId)
                .channel(NOTIFICATION_CHANNEL)
                .templateCode(TEMPLATE_REPLACEMENT)
                .subject(subject);

        return notificationsApi.sendNotification(notifCmd, UUID.randomUUID().toString())
                .doOnNext(r -> log.info("Sent replacement notification for card: {}", newCardId))
                .then();
    }

    public Mono<Void> compensateUnblockCard(@Variable(VAR_OLD_CARD_ID) UUID oldCardId) {
        log.warn("Compensating: unblocking card cardId={}", oldCardId);
        return cardsApi.unblockCard(oldCardId, "SYSTEM", UUID.randomUUID().toString())
                .then()
                .onErrorResume(ex -> {
                    log.warn("Failed to compensate card unblock cardId={}: {}", oldCardId, ex.getMessage());
                    return Mono.empty();
                });
    }

    public Mono<Void> compensateCancelNewCard(@FromStep(STEP_CREATE_REPLACEMENT) UUID newCardId) {
        log.warn("Compensating: cancelling replacement card cardId={}", newCardId);
        return cardsApi.cancelCard(newCardId, "Workflow compensation", "SYSTEM", UUID.randomUUID().toString())
                .then()
                .onErrorResume(ex -> {
                    log.warn("Failed to compensate card cancellation cardId={}: {}", newCardId, ex.getMessage());
                    return Mono.empty();
                });
    }

    @WorkflowQuery(QUERY_JOURNEY_STATUS)
    public CardReplacementJourneyStatusDTO getJourneyStatus(ExecutionContext ctx) {
        Map<String, StepStatus> steps = ctx.getStepStatuses();
        return CardReplacementJourneyStatusDTO.builder()
                .journeyId(UUID.fromString(ctx.getCorrelationId()))
                .customerId(toUuid(ctx.getVariable(VAR_CUSTOMER_ID)))
                .oldCardId(toUuid(ctx.getVariable(VAR_OLD_CARD_ID)))
                .newCardId(toUuid(ctx.getVariable(VAR_NEW_CARD_ID)))
                .currentPhase(deriveCurrentPhase(steps))
                .completedSteps(steps.entrySet().stream()
                        .filter(e -> e.getValue() == StepStatus.DONE)
                        .map(Map.Entry::getKey)
                        .toList())
                .nextStep(deriveNextStep(steps))
                .oldCardStatus(deriveOldCardStatus(steps))
                .newCardStatus(deriveNewCardStatus(steps))
                .isPhysicalReplacement((Boolean) ctx.getVariable(VAR_IS_PHYSICAL))
                .build();
    }

    @OnWorkflowComplete
    public void onJourneyComplete(ExecutionContext ctx) {
        log.info("Card replacement journey completed: oldCard={}, newCard={}",
                ctx.getVariable(VAR_OLD_CARD_ID), ctx.getVariable(VAR_NEW_CARD_ID));
    }

    @OnWorkflowError
    public void onJourneyError(Throwable error, ExecutionContext ctx) {
        log.error("Card replacement journey failed for customer: {}: {}",
                ctx.getVariable(VAR_CUSTOMER_ID), error.getMessage());
    }

    private String deriveCurrentPhase(Map<String, StepStatus> steps) {
        if (steps.getOrDefault(STEP_BLOCK_OLD_CARD, StepStatus.PENDING) == StepStatus.PENDING) {
            return PHASE_BLOCKING;
        }
        if (steps.getOrDefault(STEP_VERIFY_IDENTITY, StepStatus.PENDING) == StepStatus.PENDING) {
            return PHASE_AWAITING_VERIFICATION;
        }
        if (steps.getOrDefault(STEP_CREATE_REPLACEMENT, StepStatus.PENDING) == StepStatus.PENDING) {
            return PHASE_CREATING_REPLACEMENT;
        }
        if (steps.getOrDefault(STEP_ACTIVATE_OR_SHIP, StepStatus.PENDING) == StepStatus.PENDING) {
            return PHASE_SHIPPING;
        }
        return PHASE_COMPLETED;
    }

    private String deriveOldCardStatus(Map<String, StepStatus> steps) {
        if (steps.getOrDefault(STEP_BLOCK_OLD_CARD, StepStatus.PENDING) == StepStatus.DONE) {
            return "BLOCKED";
        }
        return "ACTIVE";
    }

    private String deriveNewCardStatus(Map<String, StepStatus> steps) {
        if (steps.getOrDefault(STEP_ACTIVATE_OR_SHIP, StepStatus.PENDING) == StepStatus.DONE) {
            return "ACTIVE_OR_SHIPPED";
        }
        if (steps.getOrDefault(STEP_CREATE_REPLACEMENT, StepStatus.PENDING) == StepStatus.DONE) {
            return "ISSUED";
        }
        return "NOT_CREATED";
    }

    private String deriveNextStep(Map<String, StepStatus> steps) {
        if (steps.getOrDefault(STEP_VERIFY_IDENTITY, StepStatus.PENDING) == StepStatus.PENDING) {
            return STEP_VERIFY_IDENTITY;
        }
        if (steps.getOrDefault(STEP_CREATE_REPLACEMENT, StepStatus.PENDING) == StepStatus.PENDING) {
            return STEP_CREATE_REPLACEMENT;
        }
        return null;
    }

    private <T> T mapSignalPayload(Object signalData, Class<T> type) {
        return objectMapper.convertValue(signalData, type);
    }

    private static boolean sameDocumentNumber(Object row, String expected) {
        if (!(row instanceof Map<?, ?> map)) {
            return false;
        }
        Object number = map.get("documentNumber");
        return number != null && expected.equals(String.valueOf(number).trim());
    }

    private UUID toUuid(Object value) {
        if (value instanceof UUID uuid) return uuid;
        if (value instanceof String s) return UUID.fromString(s);
        return null;
    }
}
