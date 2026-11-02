package io.fireflysoftware.experience.cards.interfaces.dtos;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record CardActivationRequest(
        @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{43,128}")
        @JsonProperty(access = JsonProperty.Access.WRITE_ONLY) String activationProof) {
    @Override
    public String toString() {
        return "CardActivationRequest[activationProof=REDACTED]";
    }
}
