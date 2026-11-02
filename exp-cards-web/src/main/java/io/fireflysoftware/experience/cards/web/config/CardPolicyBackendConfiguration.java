package io.fireflysoftware.experience.cards.web.config;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Optional;
import org.fireflyframework.security.adapter.opa.OpaPolicyDecisionAdapter;
import org.fireflyframework.security.adapter.opa.OpaPolicyEvaluationAdapter;
import org.fireflyframework.security.api.domain.Decision;
import org.fireflyframework.security.api.domain.PolicyEvaluation;
import org.fireflyframework.security.spi.PolicyDecisionPort;
import org.fireflyframework.security.spi.PolicyEvaluationPort;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.JdkClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "firefly.cards.authorization.opa.enabled", havingValue = "true")
@EnableConfigurationProperties(CardPolicyBackendSettings.class)
public class CardPolicyBackendConfiguration {
    private static final Duration DEADLINE = Duration.ofSeconds(2);

    @Bean
    @ConditionalOnMissingBean(PolicyDecisionPort.class)
    PolicyDecisionPort cardPolicyDecisions(CardPolicyBackendSettings settings) {
        var adapter = new OpaPolicyDecisionAdapter(transport(settings), settings.decisionPath());
        return (principal, action, resource, context) -> adapter.authorize(principal, action, resource, context)
                .timeout(DEADLINE).onErrorReturn(Decision.indeterminate("Card policy decision unavailable"));
    }

    @Bean
    @ConditionalOnMissingBean(PolicyEvaluationPort.class)
    PolicyEvaluationPort cardPolicyEvaluations(CardPolicyBackendSettings settings) {
        var adapter = new OpaPolicyEvaluationAdapter(transport(settings), settings.evaluationPath());
        return (principal, action, resource, context) -> adapter.evaluate(principal, action, resource, context)
                .timeout(DEADLINE).onErrorReturn(new PolicyEvaluation(
                        Decision.indeterminate("Card policy evaluation unavailable"), Optional.empty()));
    }

    private static WebClient transport(CardPolicyBackendSettings settings) {
        var http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(DEADLINE).build();
        return WebClient.builder().baseUrl(settings.endpoint().toASCIIString())
                .clientConnector(new JdkClientHttpConnector(http))
                .codecs(codecs -> {
                    codecs.defaultCodecs().maxInMemorySize(16 * 1024);
                    codecs.defaultCodecs().enableLoggingRequestDetails(false);
                }).build();
    }
}
