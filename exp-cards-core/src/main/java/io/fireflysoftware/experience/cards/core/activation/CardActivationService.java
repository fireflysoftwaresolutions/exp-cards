package io.fireflysoftware.experience.cards.core.activation;

import io.fireflysoftware.experience.cards.interfaces.dtos.CardActivationResponse;
import java.util.UUID;
import reactor.core.publisher.Mono;

public interface CardActivationService {
    Mono<CardActivationResponse> activate(UUID cardId, UUID actionId, String issuedPossessionProof);
}
