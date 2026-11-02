package io.fireflysoftware.experience.cards.interfaces.dtos;

import java.util.UUID;
import io.swagger.v3.oas.annotations.media.Schema;

/** Historical owner action status; this is not a current card spending authorization. */
public record CardActivationResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID actionId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID cardId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) State state,
        @Schema(ref = "#/components/schemas/FireflyInstant") java.time.OffsetDateTime activationTime) {
    public enum State { RESERVED, CONFIRMED, REJECTED, UNCERTAIN, RECONCILIATION_REQUIRED }
}
