package io.fireflysoftware.experience.cards.sdk;

import static org.junit.jupiter.api.Assertions.*;
import io.fireflysoftware.experience.cards.sdk.invoker.ApiClient;
import io.fireflysoftware.experience.cards.sdk.model.CardActivationResponse;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class CardActivationReceiptContractTest {
    @Test void actualGeneratedReceiptPreservesAuthoritativeInstant() throws Exception {
        assertEquals(Instant.class, CardActivationResponse.class.getMethod("getActivationTime").getReturnType());
        var mapper = new ApiClient().getObjectMapper();
        var receipt = mapper.readValue("{\"activationTime\":\"2026-10-31T14:34:56.123456+02:00\"}", CardActivationResponse.class);
        assertEquals(Instant.parse("2026-10-31T12:34:56.123456Z"), receipt.getActivationTime());
        assertEquals(receipt.getActivationTime(), mapper.readValue(mapper.writeValueAsBytes(receipt), CardActivationResponse.class).getActivationTime());
    }
    @Test void offsetlessTimeCannotBeInferredFromTheConsumerTimezone() {
        assertThrows(Exception.class, () -> new ApiClient().getObjectMapper().readValue(
                "{\"activationTime\":\"2026-10-31T12:34:56\"}", CardActivationResponse.class));
    }

    @Test void generatedExperienceSdkForwardsExplicitProofActionAndNamedBearer() throws Exception {
        var card=java.util.UUID.randomUUID();var action=java.util.UUID.randomUUID();
        String proof="syntheticIssuedPossessionProofForTransportOnly0123456789";
        var calls=new java.util.concurrent.atomic.AtomicInteger();
        var server=com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/api/v1/cards/"+card+"/activate", exchange -> {
            try(exchange) {
                calls.incrementAndGet();assertEquals("POST",exchange.getRequestMethod());
                assertEquals(action.toString(),exchange.getRequestHeaders().getFirst("X-Idempotency-Key"));
                assertEquals("Bearer synthetic-caller-token",exchange.getRequestHeaders().getFirst("Authorization"));
                var mapper=new ApiClient().getObjectMapper();
                assertEquals(proof,mapper.readTree(exchange.getRequestBody()).path("activationProof").asText());
                var bytes=("{\"cardId\":\""+card+"\",\"actionId\":\""+action+"\",\"state\":\"UNCERTAIN\"}").getBytes(java.nio.charset.StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(202,bytes.length);exchange.getResponseBody().write(bytes);
            }
        });
        server.start();
        try {
            var client=new ApiClient().setBasePath("http://127.0.0.1:"+server.getAddress().getPort());
            ((io.fireflysoftware.experience.cards.sdk.invoker.auth.HttpBearerAuth)client.getAuthentication("cardBearer")).setBearerToken("synthetic-caller-token");
            var api=new io.fireflysoftware.experience.cards.sdk.api.CardsApi(client);
            var receipt=api.activateCard(card,action,new io.fireflysoftware.experience.cards.sdk.model.CardActivationRequest().activationProof(proof)).block(java.time.Duration.ofSeconds(5));
            assertNotNull(receipt);assertEquals(action,receipt.getActionId());assertEquals(card,receipt.getCardId());
            assertEquals(CardActivationResponse.StateEnum.UNCERTAIN,receipt.getState());assertEquals(1,calls.get());
        } finally {server.stop(0);}
    }
}
