package io.fireflysoftware.experience.cards.web.config;

import org.junit.jupiter.api.Test;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.WebFilterChainProxy;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerResponse;

class CardRecoverySecurityBoundaryTest {
    WebTestClient client() {
        var routes=RouterFunctions.route().GET("/**",request->ServerResponse.ok().build()).POST("/**",request->ServerResponse.ok().build()).build();
        var security=new WebSecurityConfig().securityWebFilterChain(ServerHttpSecurity.http());
        return WebTestClient.bindToRouterFunction(routes).webFilter(new WebFilterChainProxy(security)).build();
    }
    @Test void unconfiguredTrustCannotExposeEitherActivationOperation() {
        var client=client();
        client.get().uri("/api/v1/cards/00000000-0000-0000-0000-000000000001/activation/00000000-0000-0000-0000-000000000002").exchange().expectStatus().isUnauthorized();
        client.post().uri("/api/v1/cards/00000000-0000-0000-0000-000000000001/activate").exchange().expectStatus().isUnauthorized();
    }
    @Test void declaredMetadataRemainsAvailableWithoutOpeningActivation() {
        client().get().uri("/v3/api-docs").exchange().expectStatus().isOk();
    }
}
