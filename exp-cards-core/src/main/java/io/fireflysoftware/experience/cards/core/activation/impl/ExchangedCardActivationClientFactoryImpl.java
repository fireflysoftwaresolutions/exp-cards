/* Copyright 2026 Firefly Software Foundation. Licensed under the Apache License, Version 2.0. */
package io.fireflysoftware.experience.cards.core.activation.impl;
import io.fireflysoftware.experience.cards.core.activation.*;
import io.fireflysoftware.domain.banking.cards.sdk.invoker.ApiClient;
import io.fireflysoftware.domain.banking.cards.sdk.api.CardsApi;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.*;
import java.util.*;
import java.util.function.LongSupplier;
import org.fireflyframework.security.api.domain.SecurityPrincipal;
import org.fireflyframework.security.spi.SecurityContextPort;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.codec.Hints;
import org.springframework.http.*;
import org.springframework.http.client.reactive.*;
import org.springframework.http.codec.HttpMessageReader;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.security.oauth2.client.endpoint.*;
import org.springframework.security.oauth2.client.registration.*;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.core.endpoint.DefaultMapOAuth2AccessTokenResponseConverter;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.reactive.function.*;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
/** Standard native HUMAN token exchange; neither principal claims nor financial authority are manufactured. */
public final class ExchangedCardActivationClientFactoryImpl implements CardActivationClientFactory {
    public static final String ACTIVATE_SCOPE="cards:activate";
    private final CardActivationDelegationSettings settings;
    private final SecurityContextPort security;
    private final org.fireflyframework.security.spi.PolicyDecisionPort policies;
    private final ReactiveClientRegistrationRepository registrations;
    private final ReactiveJwtDecoder incoming,outgoing;
    private final WebClientReactiveTokenExchangeTokenResponseClient exchange;
    private final WebClient transport;
    private final Clock clock;
    private final LongSupplier nanos;
    public ExchangedCardActivationClientFactoryImpl(CardActivationDelegationSettings settings,SecurityContextPort security,org.fireflyframework.security.spi.PolicyDecisionPort policies,
            ReactiveClientRegistrationRepository registrations,ReactiveJwtDecoder incoming){
        this(settings,security,policies,registrations,incoming,targetDecoder(settings),new JdkClientHttpConnector(HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(2)).build()),Clock.systemUTC(),System::nanoTime);
    }
    ExchangedCardActivationClientFactoryImpl(CardActivationDelegationSettings settings,SecurityContextPort security,org.fireflyframework.security.spi.PolicyDecisionPort policies,ReactiveClientRegistrationRepository registrations,
            ReactiveJwtDecoder incoming,ReactiveJwtDecoder outgoing,ClientHttpConnector connector,Clock clock,LongSupplier nanos){
        this.settings=Objects.requireNonNull(settings);this.security=Objects.requireNonNull(security);this.policies=Objects.requireNonNull(policies);this.registrations=Objects.requireNonNull(registrations);this.incoming=Objects.requireNonNull(incoming);this.outgoing=Objects.requireNonNull(outgoing);this.clock=Objects.requireNonNull(clock);this.nanos=Objects.requireNonNull(nanos);
        if(!settings.enabled())throw new IllegalArgumentException("Card activation delegation must be explicitly enabled");
        transport=ApiClient.buildWebClientBuilder().clientConnector(connector).codecs(c->{c.defaultCodecs().maxInMemorySize(1048576);c.defaultCodecs().enableLoggingRequestDetails(false);}).build();
        var tokens=WebClient.builder().clientConnector(connector).codecs(c->{c.defaultCodecs().maxInMemorySize(16384);c.defaultCodecs().enableLoggingRequestDetails(false);})
            .filter((request,next)->next.exchange(request).flatMap(reply->reply.statusCode().is2xxSuccessful()?Mono.just(reply):reply.releaseBody().then(Mono.error(unavailable())))).build();
        exchange=new WebClientReactiveTokenExchangeTokenResponseClient();exchange.setWebClient(tokens);exchange.addParametersConverter(request->{var parameters=new LinkedMultiValueMap<String,String>();parameters.set("audience",settings.targetAudience());return parameters;});
        var converter=new DefaultMapOAuth2AccessTokenResponseConverter();exchange.setBodyExtractor((message,context)->BodyExtractors.toMono(new ParameterizedTypeReference<Map<String,Object>>(){}).extract(message,new BodyExtractor.Context(){
            public List<HttpMessageReader<?>> messageReaders(){return context.messageReaders();}public Optional<ServerHttpResponse> serverResponse(){return context.serverResponse();}
            public Map<String,Object> hints(){var hints=new HashMap<String,Object>(context.hints());hints.put(Hints.SUPPRESS_LOGGING_HINT,true);return Map.copyOf(hints);}
        }).map(parameters->{if(parameters.containsKey("error")||!"urn:ietf:params:oauth:token-type:access_token".equals(parameters.get("issued_token_type")))throw forbidden();return converter.convert(parameters);}));
    }
    @Override public Mono<Client> current(UUID cardId,UUID actionId){return Mono.deferContextual(context->{
        Instant observed=clock.instant();long started=nanos.getAsLong();Set<String> scopes=Set.of(ACTIVATE_SCOPE);
        if(cardId==null||actionId==null||cardId.equals(new UUID(0,0))||actionId.equals(new UUID(0,0)))return Mono.error(forbidden());
        CardActivationCallerToken bearer=context.getOrDefault(CardActivationCallerToken.class,null);
        if(bearer==null)return Mono.error(unauthorized());
        return Mono.defer(security::currentPrincipal).switchIfEmpty(Mono.error(unauthorized()))
            .flatMap(principal->incoming.decode(bearer.value())
            .switchIfEmpty(Mono.error(unauthorized())).flatMap(original->{
                original(original,principal,scopes);remaining(started,observed,original.getExpiresAt());
                return registrations.findByRegistrationId(settings.registrationId()).switchIfEmpty(Mono.error(unavailable())).flatMap(registration->{
                    registration(registration,scopes);var scoped=ClientRegistration.withClientRegistration(registration).scope(scopes).build();
                    return requireCurrent(principal,original,scopes,started,observed,cardId,actionId)
                        .then(exchange.getTokenResponse(new TokenExchangeGrantRequest(scoped,original,null)))
                        .timeout(remaining(started,observed,original.getExpiresAt())).switchIfEmpty(Mono.error(unavailable())).flatMap(response->{
                            var token=response.getAccessToken();
                            if(token==null||!OAuth2AccessToken.TokenType.BEARER.equals(token.getTokenType())||token.getTokenValue().isBlank()||!scopes.equals(token.getScopes()))return Mono.error(forbidden());
                            return outgoing.decode(token.getTokenValue()).switchIfEmpty(Mono.error(forbidden())).flatMap(target->{
                                target(target,original,scopes);
                                if(!target.getTokenValue().equals(token.getTokenValue())||token.getExpiresAt()==null||!clock.instant().isBefore(token.getExpiresAt()))throw forbidden();
                                var api=new ApiClient(transport).setBasePath(settings.endpoint().toASCIIString().replaceFirst("/$",""));
                                api.addDefaultHeader(HttpHeaders.AUTHORIZATION,"Bearer "+target.getTokenValue());
                                Client client=new CurrentClientImpl(principal,original,target,scopes,started,observed,token.getExpiresAt(),new CardsApi(api),cardId,actionId);
                                return client.revalidate().thenReturn(client);
                            });
                        });
                });
            })).timeout(Duration.ofSeconds(5)).onErrorMap(error->error instanceof ResponseStatusException?error:unavailable());
    });}
    private final class CurrentClientImpl implements Client {
        private final SecurityPrincipal principal;private final Jwt original,target;private final Set<String> scopes;private final long started;private final Instant observed,authorizedUntil;private final CardsApi api;private final UUID cardId,actionId;
        CurrentClientImpl(SecurityPrincipal principal,Jwt original,Jwt target,Set<String> scopes,long started,Instant observed,Instant authorizedUntil,CardsApi api,UUID cardId,UUID actionId){this.principal=principal;this.original=original;this.target=target;this.scopes=scopes;this.started=started;this.observed=observed;this.authorizedUntil=authorizedUntil;this.api=api;this.cardId=cardId;this.actionId=actionId;}
        @Override public CardsApi api(){return api;}
        @Override public Duration remaining(){return ExchangedCardActivationClientFactoryImpl.this.remaining(started,observed,earliest(authorizedUntil,earliest(original.getExpiresAt(),target.getExpiresAt())));}
        @Override public Mono<Void> revalidate(){return Mono.defer(()->{target(target,original,scopes);remaining();return requireCurrent(principal,original,scopes,started,observed,cardId,actionId);});}
        @Override public String toString(){return "CardActivationCurrentClient[redacted]";}
    }
    private Mono<Void> requireCurrent(SecurityPrincipal principal,Jwt original,Set<String> scopes,long started,Instant observed,UUID cardId,UUID actionId){return Mono.defer(()->{
        original(original,principal,scopes);remaining(started,observed,original.getExpiresAt());
        return security.currentPrincipal().filter(principal::equals).switchIfEmpty(Mono.error(forbidden())).flatMap(current->
            policies.authorize(current,"cards.activate","cards/card/"+cardId,Map.of("tenantId",settings.resourceTenantId().toString(),
                "platformTenantId",settings.platformTenant(),"actorId",claim(original,settings.actorClaim()),
                "partyId",claim(original,settings.partyClaim()),"cardId",cardId.toString(),"actionId",actionId.toString()))
            .filter(decision->decision.granted()&&decision.obligations().isEmpty()).switchIfEmpty(Mono.error(forbidden()))
            .flatMap(decision->security.currentPrincipal().filter(principal::equals).switchIfEmpty(Mono.error(forbidden())))
            .doOnNext(again->{original(original,again,scopes);remaining(started,observed,original.getExpiresAt());}))
            .then().timeout(remaining(started,observed,original.getExpiresAt()));
    });}
    private void original(Jwt jwt,SecurityPrincipal principal,Set<String> scopes){
        valid(jwt);if(!List.of(settings.experienceAudience()).equals(jwt.getAudience())||!settings.inboundClientId().equals(claim(jwt,settings.clientClaim()))||!principal.subject().equals(jwt.getSubject())||!settings.issuer().equals(principal.issuer())||!settings.platformTenant().equals(principal.tenantId())||!principal.claims().equals(jwt.getClaims())||!principal.scopes().containsAll(scopes)||!scopes(jwt).containsAll(scopes))throw forbidden();
    }
    private void target(Jwt target,Jwt original,Set<String> scopes){
        valid(target);if(!List.of(settings.targetAudience()).equals(target.getAudience())||!settings.delegatingClientId().equals(claim(target,settings.clientClaim()))||!original.getSubject().equals(target.getSubject())||!claim(original,settings.actorClaim()).equals(claim(target,settings.actorClaim()))||!claim(original,settings.partyClaim()).equals(claim(target,settings.partyClaim()))||target.getExpiresAt().isAfter(original.getExpiresAt())||target.getNotBefore().isBefore(original.getNotBefore())||!scopes.equals(scopes(target)))throw forbidden();
    }
    private void valid(Jwt jwt){
        var now=clock.instant();String actor=claim(jwt,settings.actorClaim()),party=claim(jwt,settings.partyClaim());if(!settings.issuer().equals(jwt.getIssuer()==null?null:jwt.getIssuer().toString())||jwt.getSubject()==null||jwt.getSubject().isBlank()||jwt.getSubject().length()>255||!settings.platformTenant().equals(claim(jwt,settings.tenantClaim()))||!"HUMAN".equals(claim(jwt,settings.kindClaim()))||!actor.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")||UUID.fromString(actor).equals(new UUID(0,0))||!party.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")||UUID.fromString(party).equals(new UUID(0,0))||!(jwt.getClaims().get("exp")instanceof Instant)||!(jwt.getClaims().get("nbf")instanceof Instant)||jwt.getExpiresAt()==null||jwt.getNotBefore()==null||!now.isBefore(jwt.getExpiresAt())||now.isBefore(jwt.getNotBefore())||!jwt.getNotBefore().isBefore(jwt.getExpiresAt()))throw forbidden();
    }
    private void registration(ClientRegistration value,Set<String> scopes){
        CardActivationDelegationSettings.secure(URI.create(value.getProviderDetails().getTokenUri()),settings.allowLoopbackHttp());if(!settings.registrationId().equals(value.getRegistrationId())||!AuthorizationGrantType.TOKEN_EXCHANGE.equals(value.getAuthorizationGrantType())||!ClientAuthenticationMethod.CLIENT_SECRET_BASIC.equals(value.getClientAuthenticationMethod())||!settings.delegatingClientId().equals(value.getClientId())||!settings.issuer().equals(value.getProviderDetails().getIssuerUri())||value.getClientSecret()==null||value.getClientSecret().isBlank()||!value.getScopes().containsAll(scopes))throw forbidden();
    }
    private Set<String> scopes(Jwt jwt){Object value=jwt.getClaims().get(settings.scopeClaim());if(!(value instanceof String text)||text.length()>4096||text.chars().anyMatch(c->c<0x20||c>0x7e)||jwt.getClaims().containsKey("scp"))throw forbidden();var result=Set.copyOf(Arrays.asList(text.split(" ",-1)));if(result.contains(""))throw forbidden();return result;}
    private Duration remaining(long started,Instant observed,Instant until){long elapsed=nanos.getAsLong()-started;if(elapsed<0||clock.instant().isBefore(observed))throw forbidden();var wall=Duration.between(clock.instant(),until);long original=Math.min(Duration.ofSeconds(5).toNanos(),Duration.between(observed,until).toNanos())-elapsed;if(original<=0||wall.isZero()||wall.isNegative())throw forbidden();return wall.compareTo(Duration.ofNanos(original))<0?wall:Duration.ofNanos(original);}
    private static Instant earliest(Instant a,Instant b){return a.isBefore(b)?a:b;}
    private static String claim(Jwt jwt,String key){Object value=jwt.getClaims().get(key);if(!(value instanceof String text)||text.isBlank()||text.codePoints().anyMatch(Character::isISOControl))throw forbidden();return text;}
    private static ReactiveJwtDecoder targetDecoder(CardActivationDelegationSettings settings){var value=NimbusReactiveJwtDecoder.withJwkSetUri(settings.jwkSetUri().toASCIIString()).jwsAlgorithm(SignatureAlgorithm.RS256).build();value.setJwtValidator(new DelegatingOAuth2TokenValidator<>(JwtValidators.createDefaultWithIssuer(settings.issuer()),new JwtTimestampValidator(Duration.ZERO),jwt->List.of(settings.targetAudience()).equals(jwt.getAudience())?OAuth2TokenValidatorResult.success():OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token","Exact Domain Cards audience required",null))));return value;}
    private static ResponseStatusException unauthorized(){return new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Original verified card activation caller required");}
    private static ResponseStatusException forbidden(){return new ResponseStatusException(HttpStatus.FORBIDDEN,"Current exact card activation delegation refused");}
    private static ResponseStatusException unavailable(){return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Card activation delegation subject exchange unavailable");}
    @Override public String toString(){return "ExchangedCardActivationClientFactoryImpl[redacted]";}
}
