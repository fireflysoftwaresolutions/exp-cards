/* Copyright 2026 Firefly Software Foundation. Licensed under the Apache License, Version 2.0. */
package io.fireflysoftware.experience.cards.core.activation;
/** Original request bytes only; current native principal and cryptographic verification are independently required. */
public record CardActivationCallerToken(String value) {
    public CardActivationCallerToken {
        if (value == null || value.length() > 16384
                || !value.matches("[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+")) {
            throw new IllegalArgumentException("Native bearer token required");
        }
    }
    @Override public String toString() { return "CardActivationCallerToken[redacted]"; }
}
