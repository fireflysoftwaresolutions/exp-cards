/* Copyright 2026 Firefly Software Foundation. Licensed under the Apache License, Version 2.0. */
package io.fireflysoftware.experience.cards.core.activation;
import io.fireflysoftware.domain.banking.cards.sdk.api.CardsApi;
import java.time.Duration;
import reactor.core.publisher.Mono;
/** A fresh original-caller-bound generated owner client; no implicit machine grant or authority fallback. */
public interface CardActivationClientFactory {
    Mono<Client> current(java.util.UUID cardId,java.util.UUID actionId);
    interface Client {CardsApi api();Duration remaining();Mono<Void> revalidate();}
}
