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


package io.fireflysoftware.experience.cards.core.issuance.workflows;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.fireflysoftware.domain.banking.cards.sdk.api.CardsApi;
import io.fireflysoftware.domain.banking.cards.sdk.model.IssueCardCommand;
import io.fireflysoftware.domain.common.notifications.sdk.api.NotificationsApi;
import io.fireflysoftware.domain.people.sdk.api.CustomersApi;
import io.fireflysoftware.domain.common.notifications.sdk.model.SendNotificationCommand;
import io.fireflysoftware.experience.cards.core.issuance.commands.ConfirmAddressCommand;
import io.fireflysoftware.experience.cards.core.issuance.commands.InitiateCardIssuanceCommand;
import io.fireflysoftware.experience.cards.core.issuance.commands.SetupPinCommand;
import io.fireflysoftware.experience.cards.core.issuance.queries.CardIssuanceJourneyStatusDTO;
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
 * Signal-driven workflow for the card issuance journey.
 * <p>
 * Execution flow:
 * <pre>
 * Layer 0:  [request-card]
 * Layer 1:  [verify-eligibility] [send-welcome]        ← parallel
 * Layer 2:  [receive-address-confirmation]              ← @WaitForSignal("address-confirmed")
 * Layer 3:  [receive-pin-setup]                         ← @WaitForSignal("pin-set")
 * Layer 4:  [issue-card]
 * Layer 5:  [activate-card]
 * Layer 6:  [send-confirmation]
 * </pre>
 */
@Slf4j
@RequiredArgsConstructor
@Component
@Workflow(
    id = CardIssuanceJourneyWorkflow.WORKFLOW_ID,
    name = "Card Issuance Journey",
    triggerMode = TriggerMode.SYNC,
    timeoutMs = 86400000,
    publishEvents = true,
    layerConcurrency = 0
)
public class CardIssuanceJourneyWorkflow {

    public static final String WORKFLOW_ID = "card-issuance-journey";
    public static final String QUERY_JOURNEY_STATUS = "journeyStatus";

    public static final String STEP_REQUEST_CARD = "request-card";
    public static final String STEP_VERIFY_ELIGIBILITY = "verify-eligibility";
    public static final String STEP_SEND_WELCOME = "send-welcome";
    public static final String STEP_RECEIVE_ADDRESS = "receive-address-confirmation";
    public static final String STEP_RECEIVE_PIN = "receive-pin-setup";
    public static final String STEP_ISSUE_CARD = "issue-card";
    public static final String STEP_ACTIVATE_CARD = "activate-card";
    public static final String STEP_SEND_CONFIRMATION = "send-confirmation";

    public static final String SIGNAL_ADDRESS_CONFIRMED = "address-confirmed";
    public static final String SIGNAL_PIN_SET = "pin-set";

    public static final String VAR_CUSTOMER_ID = "customerId";
    public static final String VAR_CARD_ID = "cardId";
    public static final String VAR_IS_VIRTUAL = "isVirtual";

    public static final String PHASE_AWAITING_ADDRESS = "AWAITING_ADDRESS_CONFIRMATION";
    public static final String PHASE_AWAITING_PIN = "AWAITING_PIN_SETUP";
    public static final String PHASE_ISSUING = "ISSUING_CARD";
    public static final String PHASE_ACTIVATING = "ACTIVATING_CARD";
    public static final String PHASE_COMPLETED = "COMPLETED";

    private static final String NOTIFICATION_CHANNEL = "AUTO";
    private static final String TEMPLATE_WELCOME = "CARD_ISSUANCE_WELCOME";
    private static final String TEMPLATE_COMPLETED = "CARD_ISSUANCE_COMPLETED";

    private final CardsApi cardsApi;
    private final NotificationsApi notificationsApi;
    private final CustomersApi customersApi;
    private final ObjectMapper objectMapper;

    @WorkflowStep(id = STEP_REQUEST_CARD)
    @SetVariable(VAR_CUSTOMER_ID)
    public Mono<UUID> requestCard(@Input InitiateCardIssuanceCommand cmd, ExecutionContext ctx) {
        ctx.putVariable(VAR_IS_VIRTUAL, cmd.getVirtual() != null && cmd.getVirtual());
        return customersApi.getCustomerInfo(cmd.getCustomerId(), UUID.randomUUID().toString())
                .map(person -> {
                    if (person == null || person.getPartyId() == null) {
                        throw new IllegalStateException("PARTY_NOT_RECORDED");
                    }
                    log.info("Card issuance requested for customer: {}", person.getPartyId());
                    return person.getPartyId();
                });
    }

    @WorkflowStep(id = STEP_VERIFY_ELIGIBILITY, dependsOn = STEP_REQUEST_CARD)
    public Mono<Boolean> verifyEligibility(@Variable(VAR_CUSTOMER_ID) UUID customerId,
                                            @Input InitiateCardIssuanceCommand cmd) {
        log.info("Verifying card eligibility for customer: {}", customerId);
        return customersApi.getCustomerInfo(customerId, UUID.randomUUID().toString())
                .flatMap(person -> {
                    if (person == null || person.getPartyId() == null || !customerId.equals(person.getPartyId())) {
                        return Mono.error(new IllegalStateException("PARTY_NOT_RECORDED"));
                    }
                    return Mono.just(true);
                });
    }

    @WorkflowStep(id = STEP_SEND_WELCOME, dependsOn = STEP_REQUEST_CARD)
    public Mono<Void> sendWelcomeNotification(@Variable(VAR_CUSTOMER_ID) UUID customerId,
                                               @Input InitiateCardIssuanceCommand cmd) {
        SendNotificationCommand notifCmd = new SendNotificationCommand()
                .partyId(customerId)
                .channel(NOTIFICATION_CHANNEL)
                .templateCode(TEMPLATE_WELCOME)
                .subject("Card Application Received")
                .recipientEmail(cmd.getEmail());

        if (cmd.getPhone() != null) {
            notifCmd.recipientPhone(cmd.getPhone());
        }

        return notificationsApi.sendNotification(notifCmd, UUID.randomUUID().toString())
                .doOnNext(r -> log.info("Sent welcome notification for customer: {}", customerId))
                .then();
    }

    @WorkflowStep(id = STEP_RECEIVE_ADDRESS, dependsOn = {STEP_VERIFY_ELIGIBILITY, STEP_SEND_WELCOME})
    @WaitForSignal(SIGNAL_ADDRESS_CONFIRMED)
    public Mono<Void> receiveAddressConfirmation(@Variable(VAR_CUSTOMER_ID) UUID customerId,
                                                  Object signalData) {
        ConfirmAddressCommand cmd = mapSignalPayload(signalData, ConfirmAddressCommand.class);
        log.info("Address confirmed for customer: {} - {}, {}",
                customerId, cmd.getCity(), cmd.getCountry());
        return Mono.empty();
    }

    @WorkflowStep(id = STEP_RECEIVE_PIN, dependsOn = STEP_RECEIVE_ADDRESS)
    @WaitForSignal(SIGNAL_PIN_SET)
    public Mono<Void> receivePinSetup(@Variable(VAR_CUSTOMER_ID) UUID customerId,
                                       Object signalData) {
        SetupPinCommand cmd = mapSignalPayload(signalData, SetupPinCommand.class);
        log.info("PIN setup completed for customer: {}", customerId);
        return Mono.empty();
    }

    @WorkflowStep(id = STEP_ISSUE_CARD, dependsOn = STEP_RECEIVE_PIN,
                  compensatable = true, compensationMethod = "compensateCancelCard")
    @SetVariable(VAR_CARD_ID)
    public Mono<UUID> issueCard(@Variable(VAR_CUSTOMER_ID) UUID customerId,
                                 @Variable(VAR_IS_VIRTUAL) Boolean isVirtual,
                                 @Input InitiateCardIssuanceCommand cmd) {
        IssueCardCommand issueCmd = new IssueCardCommand();
        issueCmd.setCustomerId(customerId);
        issueCmd.setAccountId(cmd.getAccountId());
        issueCmd.setCardProgramId(cmd.getCardProgramId());

        return cardsApi.issueCard(issueCmd, UUID.randomUUID().toString())
                .map(response -> {
                    UUID cardId = response.getCardId();
                    log.info("Card issued: cardId={} for customer: {}", cardId, customerId);
                    return cardId;
                });
    }

    @WorkflowStep(id = STEP_ACTIVATE_CARD, dependsOn = STEP_ISSUE_CARD)
    public Mono<Void> activateCard(@Variable(VAR_CARD_ID) UUID cardId) {
        return Mono.error(new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
                "Caller-authorized activation with owner-issued possession proof is required"));
    }

    @WorkflowStep(id = STEP_SEND_CONFIRMATION, dependsOn = STEP_ACTIVATE_CARD)
    public Mono<Void> sendConfirmationNotification(@Variable(VAR_CUSTOMER_ID) UUID customerId,
                                                    @Variable(VAR_CARD_ID) UUID cardId) {
        SendNotificationCommand notifCmd = new SendNotificationCommand()
                .partyId(customerId)
                .channel(NOTIFICATION_CHANNEL)
                .templateCode(TEMPLATE_COMPLETED)
                .subject("Your Card is Ready");

        return notificationsApi.sendNotification(notifCmd, UUID.randomUUID().toString())
                .doOnNext(r -> log.info("Sent confirmation for card: {}", cardId))
                .then();
    }

    public Mono<Void> compensateCancelCard(@FromStep(STEP_ISSUE_CARD) UUID cardId) {
        log.warn("Compensating: cancelling card cardId={}", cardId);
        return cardsApi.cancelCard(cardId, "Workflow compensation", "SYSTEM", UUID.randomUUID().toString())
                .then()
                .onErrorResume(ex -> {
                    log.warn("Failed to compensate card cancellation cardId={}: {}", cardId, ex.getMessage());
                    return Mono.empty();
                });
    }

    @WorkflowQuery(QUERY_JOURNEY_STATUS)
    public CardIssuanceJourneyStatusDTO getJourneyStatus(ExecutionContext ctx) {
        Map<String, StepStatus> steps = ctx.getStepStatuses();
        return CardIssuanceJourneyStatusDTO.builder()
                .journeyId(UUID.fromString(ctx.getCorrelationId()))
                .customerId(toUuid(ctx.getVariable(VAR_CUSTOMER_ID)))
                .cardId(toUuid(ctx.getVariable(VAR_CARD_ID)))
                .currentPhase(deriveCurrentPhase(steps))
                .completedSteps(steps.entrySet().stream()
                        .filter(e -> e.getValue() == StepStatus.DONE)
                        .map(Map.Entry::getKey)
                        .toList())
                .nextStep(deriveNextStep(steps))
                .cardStatus(deriveCardStatus(steps))
                .isVirtual((Boolean) ctx.getVariable(VAR_IS_VIRTUAL))
                .build();
    }

    @OnWorkflowComplete
    public void onJourneyComplete(ExecutionContext ctx) {
        log.info("Card issuance journey completed for customer: {}, cardId: {}",
                ctx.getVariable(VAR_CUSTOMER_ID), ctx.getVariable(VAR_CARD_ID));
    }

    @OnWorkflowError
    public void onJourneyError(Throwable error, ExecutionContext ctx) {
        log.error("Card issuance journey failed for customer: {}: {}",
                ctx.getVariable(VAR_CUSTOMER_ID), error.getMessage());
    }

    private String deriveCurrentPhase(Map<String, StepStatus> steps) {
        if (steps.getOrDefault(STEP_RECEIVE_ADDRESS, StepStatus.PENDING) == StepStatus.PENDING) {
            return PHASE_AWAITING_ADDRESS;
        }
        if (steps.getOrDefault(STEP_RECEIVE_PIN, StepStatus.PENDING) == StepStatus.PENDING) {
            return PHASE_AWAITING_PIN;
        }
        if (steps.getOrDefault(STEP_ISSUE_CARD, StepStatus.PENDING) == StepStatus.PENDING) {
            return PHASE_ISSUING;
        }
        if (steps.getOrDefault(STEP_ACTIVATE_CARD, StepStatus.PENDING) == StepStatus.PENDING) {
            return PHASE_ACTIVATING;
        }
        return PHASE_COMPLETED;
    }

    private String deriveCardStatus(Map<String, StepStatus> steps) {
        if (steps.getOrDefault(STEP_ACTIVATE_CARD, StepStatus.PENDING) == StepStatus.DONE) {
            return "ACTIVE";
        }
        if (steps.getOrDefault(STEP_ISSUE_CARD, StepStatus.PENDING) == StepStatus.DONE) {
            return "ISSUED";
        }
        if (steps.getOrDefault(STEP_VERIFY_ELIGIBILITY, StepStatus.PENDING) == StepStatus.DONE) {
            return "ELIGIBLE";
        }
        return "PENDING";
    }

    private String deriveNextStep(Map<String, StepStatus> steps) {
        if (steps.getOrDefault(STEP_RECEIVE_ADDRESS, StepStatus.PENDING) == StepStatus.PENDING) {
            return STEP_RECEIVE_ADDRESS;
        }
        if (steps.getOrDefault(STEP_RECEIVE_PIN, StepStatus.PENDING) == StepStatus.PENDING) {
            return STEP_RECEIVE_PIN;
        }
        if (steps.getOrDefault(STEP_ISSUE_CARD, StepStatus.PENDING) == StepStatus.PENDING) {
            return STEP_ISSUE_CARD;
        }
        return null;
    }

    private <T> T mapSignalPayload(Object signalData, Class<T> type) {
        return objectMapper.convertValue(signalData, type);
    }

    private UUID toUuid(Object value) {
        if (value instanceof UUID uuid) return uuid;
        if (value instanceof String s) return UUID.fromString(s);
        return null;
    }
}
