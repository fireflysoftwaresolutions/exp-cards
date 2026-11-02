/* Copyright 2026 Firefly Software Foundation. Licensed under the Apache License, Version 2.0. */
package io.fireflysoftware.experience.cards.core.activation.impl;
import static org.junit.jupiter.api.Assertions.*;
import io.fireflysoftware.experience.cards.core.activation.*;
import io.fireflysoftware.domain.banking.cards.sdk.model.CardActivationRequest;
import java.net.*;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import java.util.function.UnaryOperator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.*;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.*;
import com.sun.net.httpserver.HttpServer;
import org.fireflyframework.security.api.domain.SecurityPrincipal;
import org.fireflyframework.security.rs.*;
import org.fireflyframework.security.webflux.authentication.PrincipalSupport;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.reactive.JdkClientHttpConnector;
import org.springframework.security.oauth2.client.registration.*;
import org.springframework.security.oauth2.core.*;
import reactor.core.publisher.Mono;

/** Real JWKS, signed native HUMAN projection, standard RFC8693 HTTP and actual generated SDK; not owner Main. */
class ExchangedCardActivationClientFactoryTest {
    static final UUID TENANT=new UUID(0,1),CARD=new UUID(0,2),ACTOR=new UUID(0,3),ACTION=new UUID(0,4),PARTY=new UUID(0,7);
    static final String ACTIVATE=ExchangedCardActivationClientFactoryImpl.ACTIVATE_SCOPE,EXTRA="cards:unrelated";
    static final ObjectMapper JSON=new ObjectMapper();
    final class Fixture implements AutoCloseable {
        final RSAKey key=new RSAKeyGenerator(2048).keyID("card-activation-controlled").generate();
        final HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        final AtomicInteger exchanges=new AtomicInteger(),owners=new AtomicInteger();
        final AtomicLong ticks=new AtomicLong();final AtomicBoolean policy=new AtomicBoolean(true);
        final AtomicReference<SecurityPrincipal> principal=new AtomicReference<>();
        final AtomicReference<Map<String,String>> form=new AtomicReference<>();
        final AtomicReference<String> targetToken=new AtomicReference<>();
        volatile UnaryOperator<Map<String,Object>> mutate=claims->claims;
        volatile Runnable onExchange=()->{};
        final Instant now=Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        String original;
        Fixture()throws Exception{
            server.createContext("/jwks",exchange->{try(exchange){var bytes=new JWKSet(key.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);}});
            server.createContext("/token",exchange->{try(exchange){
                exchanges.incrementAndGet();assertEquals("POST",exchange.getRequestMethod());assertEquals("Basic "+Base64.getEncoder().encodeToString("cards-experience:synthetic-test-only".getBytes(StandardCharsets.UTF_8)),exchange.getRequestHeaders().getFirst("Authorization"));
                var fields=new LinkedHashMap<String,String>();var bytes=exchange.getRequestBody().readNBytes(16385);assertTrue(bytes.length<=16384);for(var part:new String(bytes,StandardCharsets.UTF_8).split("&")){var pair=part.split("=",2);assertNull(fields.put(URLDecoder.decode(pair[0],StandardCharsets.UTF_8),URLDecoder.decode(pair.length==2?pair[1]:"",StandardCharsets.UTF_8)));}form.set(Map.copyOf(fields));
                assertEquals("urn:ietf:params:oauth:grant-type:token-exchange",fields.get("grant_type"));assertEquals(original,fields.get("subject_token"));assertEquals("domain-cards",fields.get("audience"));
                try{var claims=claims("domain-cards","cards-experience",fields.get("scope"),"HUMAN");claims.put("exp",Date.from(now.plusSeconds(90)));var token=sign(mutate.apply(claims));targetToken.set(token);onExchange.run();var response=JSON.writeValueAsBytes(Map.of("access_token",token,"token_type","Bearer","issued_token_type","urn:ietf:params:oauth:token-type:access_token","expires_in",30,"scope",fields.get("scope")));exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,response.length);exchange.getResponseBody().write(response);}catch(Exception error){throw new IllegalStateException("Controlled token fixture failed",error);}
            }});
            server.createContext("/api/v1/",exchange->{try(exchange){owners.incrementAndGet();assertEquals("Bearer "+targetToken.get(),exchange.getRequestHeaders().getFirst("Authorization"));assertNotEquals("Bearer "+original,exchange.getRequestHeaders().getFirst("Authorization"));assertEquals("/api/v1/cards/"+CARD+"/activate",exchange.getRequestURI().getPath());assertEquals(ACTION.toString(),exchange.getRequestHeaders().getFirst("X-Idempotency-Key"));var request=JSON.readTree(exchange.getRequestBody());assertEquals("syntheticIssuedPossessionProofForTransportOnly0123456789",request.get("activationProof").asText());var response=JSON.writeValueAsBytes(Map.of("actionId",ACTION.toString(),"cardId",CARD.toString(),"state","CONFIRMED"));exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,response.length);exchange.getResponseBody().write(response);}});
            server.start();original=sign(claims("experience-cards","cards-client",ACTIVATE+" "+EXTRA,"HUMAN"));project(original);
        }
        URI base(){return URI.create("http://127.0.0.1:"+server.getAddress().getPort());}
        String issuer(){return base()+"/issuer";}
        Map<String,Object> claims(String audience,String client,String scopes,String kind){var values=new HashMap<String,Object>();values.put("iss",issuer());values.put("sub","original-card-holder");values.put("aud",List.of(audience));values.put("tenant","opaque-bank-east");values.put("azp",client);values.put("scope",scopes);values.put("principal_kind",kind);values.put("actor_id",ACTOR.toString());values.put("party_id",PARTY.toString());values.put("nbf",Date.from(now.minusSeconds(1)));values.put("exp",Date.from(now.plusSeconds(120)));return values;}
        String sign(Map<String,Object> values)throws Exception{var builder=new JWTClaimsSet.Builder();for(var entry:values.entrySet()){if(entry.getKey().equals("exp"))builder.expirationTime((Date)entry.getValue());else if(entry.getKey().equals("nbf"))builder.notBeforeTime((Date)entry.getValue());else builder.claim(entry.getKey(),entry.getValue());}var jwt=new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(),builder.build());jwt.sign(new RSASSASigner(key));return jwt.serialize();}
        ResourceServerProperties trust(String audience){var p=new ResourceServerProperties();p.setIssuer(issuer());p.setAudiences(List.of(audience));p.setJwkSetUri(base()+"/jwks");p.setTenantClaimPaths(List.of("tenant"));p.setScopeClaimPaths(List.of("scope"));p.setTenantRequired(true);return p;}
        org.springframework.security.oauth2.jwt.ReactiveJwtDecoder decoder(String audience){return new ResourceServerAutoConfiguration().fireflyReactiveJwtDecoder(new org.fireflyframework.security.core.key.InMemoryKeyManagementAdapter(),trust(audience));}
        void project(String token)throws Exception{var p=trust("experience-cards");var configuration=new ResourceServerAutoConfiguration();var jwt=decoder("experience-cards").decode(token).block(Duration.ofSeconds(5));var principals=configuration.fireflyPrincipalFactory(configuration.fireflyAuthorityMappingPort(p));principal.set(PrincipalSupport.extract(configuration.fireflyJwtAuthenticationConverter(principals,p).convert(jwt).block(Duration.ofSeconds(5))));}
        CardActivationDelegationSettings settings(){return new CardActivationDelegationSettings(true,base(),issuer(),URI.create(base()+"/jwks"),"experience-cards","domain-cards","cards-client","cards-experience","cards-activation","opaque-bank-east",TENANT,"tenant","azp","principal_kind","actor_id","party_id","scope",true);}
        ExchangedCardActivationClientFactoryImpl factory(){var registration=ClientRegistration.withRegistrationId("cards-activation").clientId("cards-experience").clientSecret("synthetic-test-only").clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC).authorizationGrantType(AuthorizationGrantType.TOKEN_EXCHANGE).tokenUri(base()+"/token").issuerUri(issuer()).scope(ACTIVATE,EXTRA).build();return new ExchangedCardActivationClientFactoryImpl(settings(),()->Mono.justOrEmpty(principal.get()),(actor,action,resource,context)->{assertEquals("cards.activate",action);assertEquals("cards/card/"+CARD,resource);assertEquals(ACTOR.toString(),context.get("actorId"));assertEquals(PARTY.toString(),context.get("partyId"));assertEquals(ACTION.toString(),context.get("actionId"));assertEquals(TENANT.toString(),context.get("tenantId"));return Mono.just(policy.get()?org.fireflyframework.security.api.domain.Decision.permit():org.fireflyframework.security.api.domain.Decision.deny("withdrawn"));},new InMemoryReactiveClientRegistrationRepository(registration),decoder("experience-cards"),decoder("domain-cards"),new JdkClientHttpConnector(HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build()),Clock.systemUTC(),ticks::get);}
        CardActivationClientFactory.Client current(){return factory().current(CARD,ACTION).contextWrite(context->context.put(CardActivationCallerToken.class,new CardActivationCallerToken(original))).block(Duration.ofSeconds(8));}
        @Override public void close(){server.stop(0);}
    }
    @Test void actualSignedHumanExchangeAndGeneratedDomainCallPreserveOriginalIdentity()throws Exception{try(var f=new Fixture()){var original=f.principal.get();var client=f.current();assertNotNull(client);assertEquals(ACTIVATE,f.form.get().get("scope"));assertEquals(1,f.exchanges.get());assertSame(original,f.principal.get());var receipt=client.api().activateCard(CARD,ACTION,new CardActivationRequest().activationProof("syntheticIssuedPossessionProofForTransportOnly0123456789")).block(Duration.ofSeconds(5));assertEquals(ACTION,receipt.getActionId());assertEquals(1,f.owners.get());client.revalidate().block();}}
    @Test void machineCannotBecomeHumanThroughExchange()throws Exception{try(var f=new Fixture()){f.original=f.sign(f.claims("experience-cards","cards-client",ACTIVATE,"MACHINE"));f.project(f.original);assertThrows(RuntimeException.class,f::current);assertEquals(0,f.exchanges.get());assertEquals(0,f.owners.get());}}
    @Test void targetCannotChangeCanonicalActorIdentity()throws Exception{try(var f=new Fixture()){f.mutate=claims->{claims.put("actor_id",new UUID(0,99).toString());return claims;};assertThrows(RuntimeException.class,f::current);assertEquals(1,f.exchanges.get());assertEquals(0,f.owners.get());}}
    @Test void targetCannotExpandOriginalRequestedScopes()throws Exception{try(var f=new Fixture()){f.mutate=claims->{claims.put("scope",ACTIVATE+" "+EXTRA);return claims;};assertThrows(RuntimeException.class,f::current);assertEquals(0,f.owners.get());}}
    @Test void changedCallerDuringExchangeCannotAcquireDomainClient()throws Exception{try(var f=new Fixture()){f.onExchange=()->f.principal.set(f.principal.get().toBuilder().subject("changed-subject").build());assertThrows(RuntimeException.class,f::current);assertEquals(0,f.owners.get());}}
    @Test void targetCannotChangePartyIdentity()throws Exception{try(var f=new Fixture()){f.mutate=claims->{claims.put("party_id",new UUID(0,99).toString());return claims;};assertThrows(RuntimeException.class,f::current);assertEquals(0,f.owners.get());}}
    @Test void absentBearerCannotExchange()throws Exception{try(var f=new Fixture()){assertThrows(RuntimeException.class,()->f.factory().current(CARD,ACTION).block());assertEquals(0,f.exchanges.get());}}
    @Test void changedBearerCannotUseOriginalNativePrincipal()throws Exception{try(var f=new Fixture()){var changed=f.claims("experience-cards","cards-client",ACTIVATE,"HUMAN");changed.put("sub","another-subject");f.original=f.sign(changed);assertThrows(RuntimeException.class,f::current);assertEquals(0,f.exchanges.get());}}
    @Test void targetCannotChangeAudienceClientOrTenant()throws Exception{for(var field:List.of("aud","azp","tenant")){try(var f=new Fixture()){f.mutate=claims->{claims.put(field,field.equals("aud")?List.of("another-owner"):"another-value");return claims;};assertThrows(RuntimeException.class,f::current);assertEquals(0,f.owners.get());}}}
    @Test void targetCannotRenewOriginalExpiryOrMonotonicWindow()throws Exception{try(var f=new Fixture()){f.mutate=claims->{claims.put("exp",Date.from(f.now.plusSeconds(600)));return claims;};assertThrows(RuntimeException.class,f::current);assertEquals(0,f.owners.get());}try(var f=new Fixture()){f.onExchange=()->f.ticks.set(5_000_000_000L);assertThrows(RuntimeException.class,f::current);assertEquals(0,f.owners.get());}}
    @Test void currentDomainPolicyDenialCannotAcquireAnyDomainClient()throws Exception{try(var f=new Fixture()){f.policy.set(false);assertThrows(RuntimeException.class,f::current);assertEquals(0,f.exchanges.get());assertEquals(0,f.owners.get());}}
    @Test void currentDomainPolicyIsRecheckedAfterExchange()throws Exception{try(var f=new Fixture()){f.onExchange=()->f.policy.set(false);assertThrows(RuntimeException.class,f::current);assertEquals(1,f.exchanges.get());assertEquals(0,f.owners.get());}}

}
