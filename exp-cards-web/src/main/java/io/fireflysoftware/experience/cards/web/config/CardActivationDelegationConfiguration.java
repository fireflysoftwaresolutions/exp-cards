/* Copyright 2026 Firefly Software Foundation. Licensed under the Apache License, Version 2.0. */
package io.fireflysoftware.experience.cards.web.config;

import io.fireflysoftware.experience.cards.core.activation.CardActivationClientFactory;
import io.fireflysoftware.experience.cards.core.activation.CardActivationDelegationSettings;
import io.fireflysoftware.experience.cards.core.activation.impl.ExchangedCardActivationClientFactoryImpl;
import org.fireflyframework.security.spi.PolicyDecisionPort;
import org.fireflyframework.security.spi.SecurityContextPort;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.client.registration.ReactiveClientRegistrationRepository;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = {"firefly.cards.activation.delegation.enabled",
        "firefly.security.resource-server.strict-policy"}, havingValue = "true")
@EnableConfigurationProperties(CardActivationDelegationSettings.class)
public class CardActivationDelegationConfiguration {
    @Bean
    CardActivationClientFactory cardActivationClients(CardActivationDelegationSettings settings,
            SecurityContextPort security, PolicyDecisionPort policies,
            ReactiveClientRegistrationRepository registrations, ReactiveJwtDecoder decoder) {
        return new ExchangedCardActivationClientFactoryImpl(settings, security, policies, registrations, decoder);
    }
}
