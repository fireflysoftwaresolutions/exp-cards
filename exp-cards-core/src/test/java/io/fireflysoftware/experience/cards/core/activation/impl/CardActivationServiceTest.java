package io.fireflysoftware.experience.cards.core.activation.impl;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import io.fireflysoftware.domain.banking.cards.sdk.api.CardsApi;
import io.fireflysoftware.domain.banking.cards.sdk.model.CardActivationResponse;
import io.fireflysoftware.experience.cards.core.activation.CardActivationClientFactory;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class CardActivationServiceTest {
    final UUID card=UUID.randomUUID(), action=UUID.randomUUID();
    final String proof="syntheticIssuedPossessionProofForTransportOnly0123456789";
    final CardsApi api=mock(CardsApi.class);
    final CardActivationClientFactory factory=mock(CardActivationClientFactory.class);
    final CardActivationClientFactory.Client client=mock(CardActivationClientFactory.Client.class);
    final CardActivationServiceImpl service=new CardActivationServiceImpl(Optional.of(factory));
    void authorize() {
        when(factory.current(card,action)).thenReturn(Mono.just(client));
        when(client.revalidate()).thenReturn(Mono.empty());
        when(client.api()).thenReturn(api);
        when(client.remaining()).thenReturn(Duration.ofSeconds(5));
    }
    CardActivationResponse receipt(CardActivationResponse.StateEnum state) {
        return new CardActivationResponse().cardId(card).actionId(action).state(state);
    }
    @Test void exactCallerProofAndStableActionReachGeneratedDomainSdk() {
        authorize();when(api.activateCard(eq(card),eq(action),any())).thenReturn(Mono.just(receipt(CardActivationResponse.StateEnum.CONFIRMED)));
        for(int attempt=0;attempt<2;attempt++)StepVerifier.create(service.activate(card,action,proof)).assertNext(r->{assertEquals(card,r.cardId());assertEquals(action,r.actionId());assertEquals("CONFIRMED",r.state().name());}).verifyComplete();
        verify(api,times(2)).activateCard(eq(card),eq(action),argThat(r->proof.equals(r.getActivationProof())));verifyNoMoreInteractions(api);
    }
    @Test void everyOwnerStateIsPreservedWithoutPrematureActivation() {
        authorize();for(var state:CardActivationResponse.StateEnum.values()) {
            when(api.activateCard(eq(card),eq(action),any())).thenReturn(Mono.just(receipt(state)));
            StepVerifier.create(service.activate(card,action,proof)).assertNext(r->assertEquals(state.name(),r.state().name())).verifyComplete();
        }
    }
    @Test void absentAndMalformedInputsNeverDispatch() {
        for(String invalid:new String[]{null,"","123456"})StepVerifier.create(service.activate(card,action,invalid)).expectError(ResponseStatusException.class).verify();
        StepVerifier.create(service.activate(null,action,proof)).expectError(ResponseStatusException.class).verify();
        StepVerifier.create(service.activate(card,null,proof)).expectError(ResponseStatusException.class).verify();verifyNoInteractions(factory,api);
    }
    @Test void absentDelegationCannotSucceed() {
        StepVerifier.create(new CardActivationServiceImpl(Optional.empty()).activate(card,action,proof)).expectErrorMatches(e->e instanceof ResponseStatusException r&&r.getStatusCode().value()==503).verify();verifyNoInteractions(api);
    }
    @Test void withdrawnAuthorityPreventsOwnerCall() {
        authorize();when(client.revalidate()).thenReturn(Mono.error(new ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN)));
        StepVerifier.create(service.activate(card,action,proof)).expectError(ResponseStatusException.class).verify();verifyNoInteractions(api);
    }
    @Test void emptyReceiptCannotBecomeCompletion() {
        authorize();when(api.activateCard(eq(card),eq(action),any())).thenReturn(Mono.empty());StepVerifier.create(service.activate(card,action,proof)).expectError(ResponseStatusException.class).verify();
    }
    @Test void mismatchedReceiptCannotBecomeCompletion() {
        authorize();for(var invalid:new CardActivationResponse[]{receipt(CardActivationResponse.StateEnum.CONFIRMED).cardId(UUID.randomUUID()),receipt(CardActivationResponse.StateEnum.CONFIRMED).actionId(null),receipt(null)}) {
            when(api.activateCard(eq(card),eq(action),any())).thenReturn(Mono.just(invalid));StepVerifier.create(service.activate(card,action,proof)).expectError(ResponseStatusException.class).verify();
        }
    }
    @Test void uncertainTransportOutcomeIsNeverRetriedOrConfirmed() {
        authorize();when(api.activateCard(eq(card),eq(action),any())).thenReturn(Mono.error(new java.util.concurrent.TimeoutException()));
        StepVerifier.create(service.activate(card,action,proof)).expectErrorMatches(error -> error instanceof ResponseStatusException status
                && status.getStatusCode().value() == 503 && status.getReason().contains("outcome is unknown")).verify();verify(api).activateCard(eq(card),eq(action),any());verifyNoMoreInteractions(api);
    }

    @Test void unavailableDomainOutcomeRemainsUnknownWithOriginalAction() {
        authorize();when(api.activateCard(eq(card),eq(action),any())).thenReturn(Mono.error(
                org.springframework.web.reactive.function.client.WebClientResponseException.create(503,"Unavailable",org.springframework.http.HttpHeaders.EMPTY,new byte[0],null)));
        StepVerifier.create(service.activate(card,action,proof)).expectErrorMatches(e->e instanceof ResponseStatusException r&&r.getStatusCode().value()==503&&r.getReason().contains("outcome is unknown")).verify();
        verify(api).activateCard(eq(card),eq(action),any());verifyNoMoreInteractions(api);
    }
    @Test void authoritativeHistoricalTimeIsPreservedWithoutLocalTimeInference() {
        authorize();var time=java.time.Instant.parse("2026-10-31T12:34:56.123456Z");
        when(api.activateCard(eq(card),eq(action),any())).thenReturn(Mono.just(receipt(CardActivationResponse.StateEnum.CONFIRMED).activationTime(time)));
        StepVerifier.create(service.activate(card,action,proof)).assertNext(r->org.junit.jupiter.api.Assertions.assertEquals(time,r.activationTime().toInstant())).verifyComplete();
    }
    @Test void transportIoFailureRemainsUnknownWithoutRetryOrSecretDisclosure() {
        authorize();
        var failure = new org.springframework.web.reactive.function.client.WebClientRequestException(
                new java.io.IOException("sensitive-upstream-detail"), org.springframework.http.HttpMethod.POST,
                java.net.URI.create("https://owner.example/cards"), org.springframework.http.HttpHeaders.EMPTY);
        when(api.activateCard(eq(card), eq(action), any())).thenReturn(Mono.error(failure));
        StepVerifier.create(service.activate(card,action,proof)).expectErrorMatches(error -> error instanceof ResponseStatusException status
                && status.getStatusCode().value() == 503 && status.getReason().contains("outcome is unknown")
                && !status.getReason().contains("sensitive-upstream-detail")).verify();
        verify(api).activateCard(eq(card), eq(action), any());verifyNoMoreInteractions(api);
    }
}
