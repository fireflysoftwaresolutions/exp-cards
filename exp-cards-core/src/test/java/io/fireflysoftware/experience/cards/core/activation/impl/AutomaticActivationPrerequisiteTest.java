package io.fireflysoftware.experience.cards.core.activation.impl;

import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.fireflysoftware.domain.banking.cards.sdk.api.CardsApi;
import io.fireflysoftware.domain.banking.cards.sdk.api.CardBackofficeApi;
import io.fireflysoftware.experience.cards.core.issuance.workflows.CardIssuanceJourneyWorkflow;
import io.fireflysoftware.experience.cards.core.replacement.workflows.CardReplacementJourneyWorkflow;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import reactor.test.StepVerifier;

class AutomaticActivationPrerequisiteTest {
    @Test void issuanceCannotInventPossessionProofOrDispatchActivation() {
        var api=mock(CardsApi.class);
        var workflow=new CardIssuanceJourneyWorkflow(api,null,null,new ObjectMapper());
        StepVerifier.create(workflow.activateCard(UUID.randomUUID()))
                .expectErrorMatches(e->e instanceof ResponseStatusException r&&r.getStatusCode().value()==503).verify();
        verifyNoInteractions(api);
    }
    @Test void virtualReplacementCannotInventPossessionProofOrDispatchActivation() {
        var api=mock(CardsApi.class);var backoffice=mock(CardBackofficeApi.class);
        var workflow=new CardReplacementJourneyWorkflow(api,backoffice,null,null,new ObjectMapper());
        StepVerifier.create(workflow.activateOrShipCard(UUID.randomUUID(),false))
                .expectErrorMatches(e->e instanceof ResponseStatusException r&&r.getStatusCode().value()==503).verify();
        verifyNoInteractions(api,backoffice);
    }
}
