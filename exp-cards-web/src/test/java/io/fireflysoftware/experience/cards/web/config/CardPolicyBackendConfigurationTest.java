package io.fireflysoftware.experience.cards.web.config;

import static org.junit.jupiter.api.Assertions.*;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.fireflyframework.security.api.domain.SecurityPrincipal;
import org.fireflyframework.security.spi.PolicyDecisionPort;
import org.fireflyframework.security.spi.PolicyEvaluationPort;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class CardPolicyBackendConfigurationTest {
    final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(CardPolicyBackendConfiguration.class);
    CardPolicyBackendSettings settings(String endpoint) {
        return new CardPolicyBackendSettings(true,URI.create(endpoint),"cards/allow","cards/evaluation",true);
    }
    @Test void disabledBackendDoesNotInventPolicyPorts() {
        context.run(c -> { assertNull(c.getStartupFailure()); assertEquals(0,c.getBeansOfType(PolicyDecisionPort.class).size()); assertEquals(0,c.getBeansOfType(PolicyEvaluationPort.class).size()); });
    }
    @Test void enabledBackendRequiresExplicitTrustedEndpoint() {
        for(String endpoint:new String[]{"http://example.com","https://user:secret@example.com","https://example.com/path","https://example.com?token=secret","https://example.com/#fragment"})
            assertThrows(IllegalArgumentException.class,()->settings(endpoint));
        assertThrows(IllegalArgumentException.class,()->new CardPolicyBackendSettings(true,URI.create("http://127.0.0.1"),"allow","evaluation",false));
    }
    @Test void policyDocumentTraversalIsRejected() {
        for(String document:new String[]{"../allow","/allow","allow?x=1","allow//nested","allow%2fother"})
            assertThrows(IllegalArgumentException.class,()->new CardPolicyBackendSettings(true,URI.create("https://example.com"),document,"evaluation",false));
    }
    @Test void publishedAdaptersSendCurrentAuthorityAndPreserveEngineProvenance() throws Exception {
        var captured=new AtomicReference<String>();
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/v1/data/cards/",exchange->{
            captured.set(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));
            String value=exchange.getRequestURI().getPath().endsWith("evaluation")
                    ? "{\"result\":{\"allow\":true,\"policy_revision\":\"engine-revision-7\"},\"decision_id\":\"engine-decision-9\"}"
                    : "{\"result\":true}";
            byte[] body=value.getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);exchange.close();
        });server.start();
        try {
            var cfg=new CardPolicyBackendConfiguration();var settings=settings("http://127.0.0.1:"+server.getAddress().getPort());
            var principal=new SecurityPrincipal("actual-subject","https://issuer.example","opaque-tenant",Set.of(),Set.of("cards:activate"),Map.of(),Instant.now(),null,Set.of(),Map.of());
            assertTrue(cfg.cardPolicyDecisions(settings).authorize(principal,"cards.activate","cards/card/owned",Map.of("actorId","current-actor")).block().granted());
            var result=cfg.cardPolicyEvaluations(settings).evaluate(principal,"cards.activate","cards/card/owned",Map.of("actorId","current-actor")).block();
            assertTrue(result.decision().granted());assertEquals("engine-revision-7",result.provenance().orElseThrow().policyRevision());assertEquals("engine-decision-9",result.provenance().orElseThrow().decisionId());
            assertTrue(captured.get().contains("actual-subject"));assertTrue(captured.get().contains("current-actor"));assertTrue(captured.get().contains("cards/card/owned"));
        } finally { server.stop(0); }
    }
    @Test void unreachableBackendCannotGrantOrFabricateProvenance() {
        var cfg=new CardPolicyBackendConfiguration();var principal=new SecurityPrincipal("subject","issuer","tenant",Set.of(),Set.of(),Map.of(),Instant.now(),null,Set.of(),Map.of());
        var evaluation=cfg.cardPolicyEvaluations(settings("http://127.0.0.1:1")).evaluate(principal,"cards.activate","cards/card/owned",Map.of()).block();
        assertFalse(evaluation.decision().granted());assertTrue(evaluation.provenance().isEmpty());
    }
}
