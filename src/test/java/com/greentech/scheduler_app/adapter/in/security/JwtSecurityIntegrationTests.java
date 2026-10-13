package com.greentech.scheduler_app.adapter.in.security;

import com.greentech.scheduler_app.TestcontainersConfiguration;
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.*;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"scheduler.security.mode=jwt","scheduler.security.issuer-uri=https://issuer.test","scheduler.security.audience=scheduler-api"})
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class JwtSecurityIntegrationTests {
    static final RSAKey KEY;
    static final HttpServer JWKS;
    static {
        try {
            KEY=new RSAKeyGenerator(2048).keyID("test-key").generate();
            JWKS=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            JWKS.createContext("/jwks",e->{
                byte[] body=new JWKSet(KEY.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
                e.getResponseHeaders().set("Content-Type","application/json"); e.sendResponseHeaders(200,body.length);
                e.getResponseBody().write(body);e.close();
            }); JWKS.start();
        } catch(Exception e) { throw new ExceptionInInitializerError(e); }
    }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry properties) {
        properties.add("scheduler.security.jwk-set-uri",()->"http://127.0.0.1:"+JWKS.getAddress().getPort()+"/jwks");
    }
    @AfterAll static void stop() { JWKS.stop(0); }
    @Autowired MockMvc mvc;
    String token(String tenant,String scope,String issuer,String audience,Instant expiry) throws Exception {
        var claims=new JWTClaimsSet.Builder().issuer(issuer).audience(audience).subject("client")
                .claim("tenant_id",tenant).claim("scope",scope).issueTime(new Date()).expirationTime(Date.from(expiry)).build();
        var jwt=new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(KEY.getKeyID()).build(),claims);
        jwt.sign(new RSASSASigner(KEY)); return jwt.serialize();
    }
    String valid(String tenant,String scope) throws Exception { return token(tenant,scope,"https://issuer.test","scheduler-api",Instant.now().plusSeconds(300)); }
    @Test void rejectsAnonymousAndCrossTenantAccess() throws Exception {
        mvc.perform(get("/v1/tenants/tenant-a/schedules")).andExpect(status().isUnauthorized());
        mvc.perform(get("/v1/tenants/tenant-a/schedules").header("Authorization","Bearer "+valid("tenant-b","schedules:read")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/v1/tenants/tenant-a/schedules").header("Authorization","Bearer "+valid("tenant-a","schedules:read")))
                .andExpect(status().isOk());
    }
    @Test void rejectsWrongIssuerAudienceExpiryAndSignature() throws Exception {
        for(String bearer:new String[]{
            token("tenant-a","schedules:read","https://wrong.test","scheduler-api",Instant.now().plusSeconds(300)),
            token("tenant-a","schedules:read","https://issuer.test","other-api",Instant.now().plusSeconds(300)),
            token("tenant-a","schedules:read","https://issuer.test","scheduler-api",Instant.now().minusSeconds(120))}) {
            mvc.perform(get("/v1/tenants/tenant-a/schedules").header("Authorization","Bearer "+bearer)).andExpect(status().isUnauthorized());
        }
        var forged=new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(KEY.getKeyID()).build(),
                new JWTClaimsSet.Builder().issuer("https://issuer.test").audience("scheduler-api").expirationTime(Date.from(Instant.now().plusSeconds(300))).build());
        forged.sign(new RSASSASigner(new RSAKeyGenerator(2048).generate()));
        mvc.perform(get("/v1/tenants/tenant-a/schedules").header("Authorization","Bearer "+forged.serialize())).andExpect(status().isUnauthorized());
    }
    @Test void enforcesReadWriteAndRedriveScopes() throws Exception {
        mvc.perform(get("/v1/tenants/tenant-a/schedules").header("Authorization","Bearer "+valid("tenant-a","schedules:write")))
                .andExpect(status().isForbidden());
        mvc.perform(post("/v1/tenants/tenant-a/schedules/id:cancel").header("If-Match","1")
                .header("Authorization","Bearer "+valid("tenant-a","schedules:read"))).andExpect(status().isForbidden());
        mvc.perform(post("/v1/tenants/tenant-a/schedules/id:redrive").header("If-Match","1")
                .contentType("application/json").content("{\"dueAt\":\""+Instant.now().plusSeconds(60)+"\"}")
                .header("Authorization","Bearer "+valid("tenant-a","schedules:write"))).andExpect(status().isForbidden());
        mvc.perform(post("/v1/tenants/tenant-a/schedules/id:redrive").header("If-Match","1")
                .contentType("application/json").content("{\"dueAt\":\""+Instant.now().plusSeconds(60)+"\"}")
                .header("Authorization","Bearer "+valid("tenant-a","schedules:redrive"))).andExpect(status().isNotFound());
    }
    @Test void exposesOnlyHealthWithoutCredentialsAndLimitsBodySize() throws Exception {
        mvc.perform(get("/actuator/health/liveness")).andExpect(status().isOk());
        mvc.perform(get("/actuator/metrics")).andExpect(status().isUnauthorized());
        mvc.perform(post("/v1/tenants/tenant-a/schedules").header("Authorization","Bearer "+valid("tenant-a","schedules:write"))
                .contentType("application/json").content("x".repeat(82000))).andExpect(status().isPayloadTooLarge());
    }
}
