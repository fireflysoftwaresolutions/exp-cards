/* Copyright 2026 Firefly Software Foundation. Licensed under the Apache License, Version 2.0. */
package io.fireflysoftware.qualification.cards;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.*;
import com.nimbusds.jose.jwk.*;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.*;
import com.sun.net.httpserver.HttpServer;
import io.fireflysoftware.experience.cards.sdk.api.CardsApi;
import io.fireflysoftware.experience.cards.sdk.invoker.ApiClient;
import io.fireflysoftware.experience.cards.sdk.model.CardActivationRequest;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.DriverManager;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Three original executables, real generated SDKs and current native authority; issuer remains NoOp. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CardNativeAuthorityRuntimeIT {
    static final ObjectMapper JSON=new ObjectMapper();
    static final UUID CARD=UUID.randomUUID(),TENANT=UUID.randomUUID(),ACTOR=UUID.randomUUID(),PARTY=UUID.randomUUID(),ACCOUNT=UUID.randomUUID();
    static final String PROOF="unissuedPossessionProofForNativeBoundaryOnly0123456789";
    final List<Process> mains=new ArrayList<>();
    final List<Path> logs=new ArrayList<>();
    final List<Map<String,Object>> protocol=Collections.synchronizedList(new ArrayList<>());
    final AtomicInteger exchanges=new AtomicInteger(),evaluations=new AtomicInteger();
    final AtomicBoolean permit=new AtomicBoolean(true);
    final String runtimePassword=UUID.randomUUID().toString()+UUID.randomUUID();
    final String exchangeSecret=UUID.randomUUID().toString()+UUID.randomUUID();
    String baseline;
    RSAKey key;HttpServer authority;CardRuntimeTestDatabase database;int corePort,domainPort,experiencePort;
    Path root=Path.of(System.getProperty("cards.native.source-root")).toAbsolutePath();
    String issuer(){return "http://127.0.0.1:"+authority.getAddress().getPort()+"/issuer";}
    String authorityBase(){return "http://127.0.0.1:"+authority.getAddress().getPort();}
    static int port()throws Exception{try(var socket=new ServerSocket(0,1,InetAddress.getLoopbackAddress())){return socket.getLocalPort();}}
    String sign(Map<String,Object> changes,String audience,String client)throws Exception {
        var claims=new HashMap<String,Object>();claims.put("iss",issuer());claims.put("sub","controlled-card-holder");claims.put("aud",List.of(audience));claims.put("tenant","opaque-card-tenant");claims.put("azp",client);claims.put("scope","cards:activate");claims.put("principal_kind","HUMAN");claims.put("actor_id",ACTOR.toString());claims.put("party_id",PARTY.toString());claims.put("nbf",Date.from(Instant.now().minusSeconds(1)));claims.put("exp",Date.from(Instant.now().plusSeconds(120)));claims.putAll(changes);
        var builder=new JWTClaimsSet.Builder();for(var e:claims.entrySet()){if(e.getKey().equals("exp"))builder.expirationTime((Date)e.getValue());else if(e.getKey().equals("nbf"))builder.notBeforeTime((Date)e.getValue());else builder.claim(e.getKey(),e.getValue());}
        var jwt=new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(),builder.build());jwt.sign(new RSASSASigner(key));return jwt.serialize();
    }
    void respond(com.sun.net.httpserver.HttpExchange exchange,int status,Object value)throws java.io.IOException {
        var body=JSON.writeValueAsBytes(value);exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(status,body.length);exchange.getResponseBody().write(body);exchange.close();
    }
    @BeforeAll void start()throws Exception {
        assertNotNull(System.getProperty("cards.http.test.database-profile"), "Explicit owned PostgreSQL native profile required");
        key=new RSAKeyGenerator(2048).keyID("native-cards-controlled-authority").generate();
        authority=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        authority.createContext("/jwks",x->{try{x.getResponseHeaders().set("Content-Type","application/json");var bytes=new JWKSet(key.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);x.sendResponseHeaders(200,bytes.length);x.getResponseBody().write(bytes);}finally{x.close();}});
        authority.createContext("/token",x->{try {
            var form=new HashMap<String,String>();for(String pair:new String(x.getRequestBody().readNBytes(16385),StandardCharsets.UTF_8).split("&")){var parts=pair.split("=",2);if(form.put(URLDecoder.decode(parts[0],StandardCharsets.UTF_8),URLDecoder.decode(parts[1],StandardCharsets.UTF_8))!=null)throw new IllegalArgumentException();}
            var token=SignedJWT.parse(form.get("subject_token"));if(!token.verify(new RSASSAVerifier(key.toRSAPublicKey())))throw new IllegalArgumentException();var claims=token.getJWTClaimsSet();
            var target=form.get("audience");boolean toCore="core-cards".equals(target);String source=toCore?"domain-cards":"experience-cards",client=toCore?"cards-domain":"cards-experience";
            if(!"POST".equals(x.getRequestMethod())||!"urn:ietf:params:oauth:grant-type:token-exchange".equals(form.get("grant_type"))||!"cards:activate".equals(form.get("scope"))||!claims.getAudience().equals(List.of(source))||!issuer().equals(claims.getIssuer())||!"HUMAN".equals(claims.getStringClaim("principal_kind"))||!"opaque-card-tenant".equals(claims.getStringClaim("tenant"))||!ACTOR.toString().equals(claims.getStringClaim("actor_id"))||!PARTY.toString().equals(claims.getStringClaim("party_id"))||!claims.getExpirationTime().after(new Date())||!("Basic "+Base64.getEncoder().encodeToString((client+":"+exchangeSecret).getBytes(StandardCharsets.UTF_8))).equals(x.getRequestHeaders().getFirst("Authorization")))throw new IllegalArgumentException();
            String exchanged=sign(Map.of("exp",claims.getExpirationTime(),"nbf",claims.getNotBeforeTime()),target,client);exchanges.incrementAndGet();protocol.add(Map.of("stage","exchange","from",source,"to",target,"actor",ACTOR.toString(),"party",PARTY.toString()));
            respond(x,200,Map.of("access_token",exchanged,"token_type","Bearer","issued_token_type","urn:ietf:params:oauth:token-type:access_token","expires_in",30,"scope","cards:activate"));
        }catch(Exception rejected){respond(x,400,Map.of("error","invalid_request"));}});
        authority.createContext("/v1/data/cards/",x->{try {
            var input=JSON.readTree(x.getRequestBody()).path("input");var subject=input.path("subject");String action=input.path("action").asText(),resource=input.path("resource").asText();
            boolean business="cards.activate".equals(action);boolean url=("POST".equals(action)||"GET".equals(action))&&(resource.equals("/api/v1/cards/"+CARD+"/activate")||resource.startsWith("/api/v1/cards/"+CARD+"/activation"));
            boolean allowed=permit.get()&&"controlled-card-holder".equals(subject.path("subject").asText())&&issuer().equals(subject.path("issuer").asText())&&"opaque-card-tenant".equals(subject.path("tenantId").asText())&&subject.path("scopes").toString().contains("\"cards:activate\"")&&(url||business&&resource.equals("cards/card/"+CARD)&&ACTOR.toString().equals(input.path("context").path("actorId").asText())&&PARTY.toString().equals(input.path("context").path("partyId").asText())&&TENANT.toString().equals(input.path("context").path("tenantId").asText()));
            boolean evaluation=x.getRequestURI().getPath().endsWith("evaluation");if(evaluation){allowed=allowed&&ACCOUNT.toString().equals(input.path("context").path("accountId").asText())&&input.path("context").path("bindingVersion").asLong()==1&&"controlled-existing-binding".equals(input.path("context").path("adapterId").asText());evaluations.incrementAndGet();}protocol.add(Map.of("stage",evaluation?"owner-policy":"policy","action",action,"resource",resource,"allowed",allowed));
            respond(x,200,evaluation?Map.of("result",Map.of("allow",allowed,"policy_revision","controlled-cards-policy-r1"),"decision_id",UUID.randomUUID().toString()):Map.of("result",allowed));
        }catch(Exception failure){respond(x,500,Map.of("error","policy_evaluation_failed"));}});
        for(String path:List.of("/issuer/.well-known/openid-configuration","/.well-known/openid-configuration/issuer"))
            authority.createContext(path,x->respond(x,200,Map.of("issuer",issuer(),"token_endpoint",authorityBase()+"/token","authorization_endpoint",authorityBase()+"/authorize","jwks_uri",authorityBase()+"/jwks","response_types_supported",List.of("code"),"subject_types_supported",List.of("public"),"id_token_signing_alg_values_supported",List.of("RS256"))));
        authority.start();corePort=port();domainPort=port();experiencePort=port();
        database=CardRuntimeTestDatabase.start(new PostgreSQLContainer("postgres:17-alpine").withDatabaseName("synthetic_native_cards").withUsername("native_bootstrap").withPassword(UUID.randomUUID().toString()));
        var domainCache=CardRuntimeTestDatabase.start(null);var experienceCache=CardRuntimeTestDatabase.start(null);
        Flyway.configure().dataSource(database.jdbcUrl(),database.user(),database.password()).locations("filesystem:"+root.resolve("core-banking-cards/core-banking-cards-models/src/main/resources/db/migration")).load().migrate();
        try(var c=DriverManager.getConnection(database.jdbcUrl(),database.user(),database.password());var st=c.createStatement()) {
            st.execute("CREATE ROLE cards_native_runtime LOGIN PASSWORD '"+runtimePassword+"' NOSUPERUSER NOBYPASSRLS NOINHERIT NOCREATEDB NOCREATEROLE;GRANT USAGE ON SCHEMA public TO cards_native_runtime;GRANT SELECT,INSERT,UPDATE,DELETE ON ALL TABLES IN SCHEMA public TO cards_native_runtime");
            st.execute("INSERT INTO card(card_id,card_number,party_id,account_id,card_status,is_active,is_locked,expiration_date) VALUES ('"+CARD+"','fixture-reference','"+PARTY+"','"+ACCOUNT+"','DRAFT',false,true,clock_timestamp()+interval '1 year')");
            st.execute("INSERT INTO card_issuer_instrument(card_id,tenant_id,party_id,account_id,adapter_id,instrument_reference,issuance_reference,issued_inactive) VALUES ('"+CARD+"','"+TENANT+"','"+PARTY+"','"+ACCOUNT+"','controlled-existing-binding','controlled-instrument','controlled-fixture-not-issuance',true)");
        }
        baseline=snapshot();
        database.admitRuntime("cards_native_runtime",runtimePassword);
        startMain("core-banking-cards",corePort,"core-cards",database,null,0);
        startMain("domain-banking-cards",domainPort,"domain-cards",domainCache,"core-cards",corePort);
        startMain("exp-cards",experiencePort,"experience-cards",experienceCache,"domain-cards",domainPort);
    }
    void startMain(String owner,int port,String audience,CardRuntimeTestDatabase cache,String target,int targetPort)throws Exception {
        var cmd=new ArrayList<>(List.of(Path.of(System.getProperty("java.home"),"bin/java").toString(),"-Xmx384m","-jar",root.resolve(owner+"/"+owner+"-web/target/"+owner+".jar").toString(),"--server.address=127.0.0.1","--server.port="+port,"--firefly.eda.enabled=false","--firefly.stepevents.enabled=false","--spring.flyway.enabled=false","--firefly.security.resource-server.enabled=true","--firefly.security.resource-server.strict-policy=true","--firefly.security.resource-server.issuer="+issuer(),"--firefly.security.resource-server.jwk-set-uri="+authorityBase()+"/jwks","--firefly.security.resource-server.audiences="+audience,"--firefly.security.resource-server.tenant-claim-paths=tenant","--firefly.security.resource-server.scope-claim-paths=scope","--firefly.security.resource-server.tenant-required=true","--firefly.security.resource-server.permit-matchers=/v3/api-docs,/actuator/health","--firefly.cards.authorization.opa.enabled=true","--firefly.cards.authorization.opa.endpoint="+authorityBase(),"--firefly.cards.authorization.opa.decision-path=cards/allow","--firefly.cards.authorization.opa.evaluation-path=cards/evaluation","--firefly.cards.authorization.opa.allow-loopback-http=true"));
        if(target==null)cmd.addAll(List.of("--DB_HOST="+database.host(),"--DB_PORT="+database.port(),"--DB_NAME="+database.name(),"--DB_USERNAME=cards_native_runtime","--DB_SSL_MODE=disable","--firefly.cards.activation.native.enabled=true","--firefly.cards.activation.native.issuer="+issuer(),"--firefly.cards.activation.native.platform-tenant=opaque-card-tenant","--firefly.cards.activation.native.resource-tenant="+TENANT,"--firefly.cards.activation.native.audience=core-cards","--firefly.cards.activation.native.client-ids=cards-domain","--firefly.cards.activation.native.client-claim=azp","--firefly.cards.activation.native.actor-claim=actor_id","--firefly.cards.activation.native.party-claim=party_id","--firefly.cards.activation.native.kind-claim=principal_kind"));
        else {
            String delegator=owner.equals("exp-cards")?"cards-experience":"cards-domain",inbound=owner.equals("exp-cards")?"cards-channel":"cards-experience";
            var settings=Map.ofEntries(Map.entry("enabled","true"),Map.entry("endpoint","http://127.0.0.1:"+targetPort),Map.entry("issuer",issuer()),Map.entry("jwk-set-uri",authorityBase()+"/jwks"),Map.entry(owner.equals("exp-cards")?"experience-audience":"domain-audience",audience),Map.entry("target-audience",target),Map.entry("inbound-client-id",inbound),Map.entry("delegating-client-id",delegator),Map.entry("registration-id","cards-activation"),Map.entry("platform-tenant","opaque-card-tenant"),Map.entry("resource-tenant-id",TENANT.toString()),Map.entry("tenant-claim","tenant"),Map.entry("client-claim","azp"),Map.entry("kind-claim","principal_kind"),Map.entry("actor-claim","actor_id"),Map.entry("party-claim","party_id"),Map.entry("scope-claim","scope"),Map.entry("allow-loopback-http","true"));
            settings.forEach((k,v)->cmd.add("--firefly.cards.activation.delegation."+k+"="+v));
            cmd.addAll(List.of("--spring.security.oauth2.client.registration.cards-activation.provider=cards","--spring.security.oauth2.client.registration.cards-activation.client-id="+delegator,"--spring.security.oauth2.client.registration.cards-activation.client-secret=${CARD_NATIVE_EXCHANGE_SECRET}","--spring.security.oauth2.client.registration.cards-activation.client-authentication-method=client_secret_basic","--spring.security.oauth2.client.registration.cards-activation.authorization-grant-type=urn:ietf:params:oauth:grant-type:token-exchange","--spring.security.oauth2.client.registration.cards-activation.scope=cards:activate","--spring.security.oauth2.client.provider.cards.issuer-uri="+issuer(),"--spring.security.oauth2.client.provider.cards.token-uri="+authorityBase()+"/token"));
        }
        var log=Path.of("target/native-authority-"+owner+".log");logs.add(log);var builder=new ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(log.toFile());builder.environment().put("DB_PASSWORD",runtimePassword);builder.environment().put("CARD_NATIVE_EXCHANGE_SECRET",exchangeSecret);cache.configureCache(builder);var main=builder.start();mains.add(main);
        var http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build();for(int n=0;n<160;n++){assertTrue(main.isAlive(),owner+" executable exited");try{if(http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/v3/api-docs")).timeout(Duration.ofSeconds(1)).GET().build(),HttpResponse.BodyHandlers.discarding()).statusCode()==200)return;}catch(java.io.IOException unavailable){}Thread.sleep(250);}fail(owner+" startup timeout");
    }
    CardsApi api(String token){var client=new ApiClient();client.setBasePath("http://127.0.0.1:"+experiencePort);if(token!=null)client.addDefaultHeader("Authorization","Bearer "+token);return new CardsApi(client);}
    String snapshot()throws Exception {
        var rows=new TreeMap<String,List<String>>();
        try(var c=DriverManager.getConnection(database.jdbcUrl(),database.user(),database.password())) {
            for(String table:List.of("card","card_issuer_instrument","card_activation_action","card_activation_dispatch","card_activation_proof")) {
                var values=new ArrayList<String>();try(var st=c.createStatement();var r=st.executeQuery("SELECT row_to_json(value)::text FROM "+table+" value ORDER BY row_to_json(value)::text")){while(r.next())values.add(r.getString(1));}rows.put(table,values);
            }
        }
        return JSON.writeValueAsString(rows);
    }
    void unchanged()throws Exception {assertEquals(baseline,snapshot());try(var c=DriverManager.getConnection(database.jdbcUrl(),database.user(),database.password());var st=c.createStatement();var r=st.executeQuery("SELECT (SELECT count(*) FROM card_activation_action)+(SELECT count(*) FROM card_activation_dispatch)+(SELECT count(*) FROM card_activation_proof),(SELECT count(*) FROM card WHERE card_status='DRAFT' AND NOT is_active AND is_locked)")){assertTrue(r.next());assertEquals(0,r.getInt(1));assertEquals(1,r.getInt(2));}}
    @Test void currentHumanTraversesBothExchangesAndOwnerPolicyButNoOpCannotExecute()throws Exception {
        int before=exchanges.get(),ownerBefore=evaluations.get();var action=UUID.randomUUID();
        var rejected=assertThrows(WebClientResponseException.class,()->api(sign(Map.of(),"experience-cards","cards-channel")).activateCard(CARD,action,new CardActivationRequest().activationProof(PROOF)).block(Duration.ofSeconds(20)));
        assertEquals(503,rejected.getStatusCode().value());assertEquals(before+2,exchanges.get());assertTrue(evaluations.get()>ownerBefore);assertTrue(Files.readString(logs.getFirst()).contains("Card issuer NOT_CONFIGURED: NOT_EXECUTED"));assertFalse(rejected.getResponseBodyAsString().contains(PROOF));unchanged();
    }
    @Test void absentBearerAndWrongNativeAudienceCannotReachExchangeOrMutate()throws Exception {
        int before=exchanges.get();var absent=assertThrows(WebClientResponseException.class,()->api(null).activateCard(CARD,UUID.randomUUID(),new CardActivationRequest().activationProof(PROOF)).block(Duration.ofSeconds(15)));assertEquals(403,absent.getStatusCode().value());
        var wrong=assertThrows(WebClientResponseException.class,()->api(sign(Map.of(),"another-audience","cards-channel")).activateCard(CARD,UUID.randomUUID(),new CardActivationRequest().activationProof(PROOF)).block(Duration.ofSeconds(15)));assertEquals(401,wrong.getStatusCode().value());assertEquals(before,exchanges.get());unchanged();
    }
    @Test void currentPolicyDenialPreventsDelegationAndLeavesOwnerUntouched()throws Exception {
        int before=exchanges.get();permit.set(false);try{var rejected=assertThrows(WebClientResponseException.class,()->api(sign(Map.of(),"experience-cards","cards-channel")).activateCard(CARD,UUID.randomUUID(),new CardActivationRequest().activationProof(PROOF)).block(Duration.ofSeconds(15)));assertEquals(403,rejected.getStatusCode().value());assertEquals(before,exchanges.get());unchanged();}finally{permit.set(true);}
    }
    @Test void changedActorPartyTenantKindOrScopeCannotAcquireDelegation()throws Exception {
        int before=exchanges.get();
        for(var change:List.of(Map.<String,Object>of("actor_id",UUID.randomUUID().toString()),Map.<String,Object>of("party_id",UUID.randomUUID().toString()),Map.<String,Object>of("tenant","another-tenant"),Map.<String,Object>of("principal_kind","MACHINE"),Map.<String,Object>of("scope","cards:read"))) {
            var rejected=assertThrows(WebClientResponseException.class,()->api(sign(change,"experience-cards","cards-channel")).activateCard(CARD,UUID.randomUUID(),new CardActivationRequest().activationProof(PROOF)).block(Duration.ofSeconds(15)));
            assertEquals(403,rejected.getStatusCode().value());assertEquals(before,exchanges.get());unchanged();
        }
    }
    @AfterAll void stop()throws Exception {
        try{for(var p:mains){p.destroy();if(!p.waitFor(8,TimeUnit.SECONDS)){p.destroyForcibly();assertTrue(p.waitFor(5,TimeUnit.SECONDS));}assertFalse(p.isAlive());}for(var log:logs){String text=Files.readString(log);assertFalse(text.contains(PROOF));assertFalse(text.contains(runtimePassword));assertFalse(text.contains(exchangeSecret));}}
        finally{Files.writeString(Path.of("target/native-authority-processes.json"),JSON.writeValueAsString(mains.stream().map(p->Map.of("pid",p.pid(),"alive",p.isAlive())).toList()));if(database!=null&&baseline!=null)Files.writeString(Path.of("target/native-authority-physical.json"),JSON.writeValueAsString(Map.of("before",JSON.readTree(baseline),"after",JSON.readTree(snapshot()))));if(authority!=null)authority.stop(0);if(database!=null)database.stopOwnedContainer();Files.writeString(Path.of("target/native-authority-protocol.json"),JSON.writerWithDefaultPrettyPrinter().writeValueAsString(protocol));}
    }
}
