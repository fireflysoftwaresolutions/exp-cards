package io.fireflysoftware.experience.cards.web.config;

import java.net.URI;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("firefly.cards.authorization.opa")
public record CardPolicyBackendSettings(boolean enabled, URI endpoint, String decisionPath,
                                        String evaluationPath, boolean allowLoopbackHttp) {
    public CardPolicyBackendSettings {
        if (enabled) {
            if (endpoint == null || endpoint.getHost() == null || endpoint.getRawUserInfo() != null
                    || endpoint.getRawQuery() != null || endpoint.getRawFragment() != null
                    || !endpoint.equals(endpoint.normalize()) || !Set.of("", "/").contains(endpoint.getRawPath())
                    || !("https".equals(endpoint.getScheme()) || allowLoopbackHttp && "http".equals(endpoint.getScheme())
                    && Set.of("127.0.0.1", "localhost", "::1", "[::1]").contains(endpoint.getHost()))) {
                throw new IllegalArgumentException("Explicit trusted card policy endpoint required");
            }
            for (String path : new String[] {decisionPath, evaluationPath}) {
                if (path == null || !path.matches("[A-Za-z0-9_-]+(?:/[A-Za-z0-9_-]+)*")) {
                    throw new IllegalArgumentException("Exact card policy document paths required");
                }
            }
        }
    }
}
