package io.fireflysoftware.experience.cards.web.openapi;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.media.StringSchema;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class CardActivationOpenApiConfiguration {
    @Bean
    OpenApiCustomizer cardActivationTimeSchema() {
        return document -> {
            if (document.getComponents() == null) document.setComponents(new Components());
            document.getComponents().addSchemas("FireflyInstant", new StringSchema().format("date-time"));
        };
    }
}
