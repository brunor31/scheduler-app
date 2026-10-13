package com.greentech.scheduler_app.infrastructure.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpMethod;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.web.SecurityFilterChain;
import java.util.*;

@Configuration(proxyBeanMethods=false)
@org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
public class SecurityConfiguration {
    @Bean
    @ConditionalOnProperty(name="scheduler.security.mode",havingValue="jwt",matchIfMissing=true)
    JwtDecoder decoder(@Value("${scheduler.security.issuer-uri:}") String issuer,
            @Value("${scheduler.security.jwk-set-uri:}") String jwks,
            @Value("${scheduler.security.audience:scheduler-api}") String audience) {
        if(issuer.isBlank() || audience.isBlank()) throw new IllegalArgumentException("JWT issuer and audience must be configured");
        NimbusJwtDecoder decoder=jwks.isBlank()?(NimbusJwtDecoder)JwtDecoders.fromIssuerLocation(issuer)
                :NimbusJwtDecoder.withJwkSetUri(jwks).build();
        OAuth2TokenValidator<Jwt> claims=jwt->jwt.getAudience().contains(audience) && jwt.getExpiresAt()!=null
                ?OAuth2TokenValidatorResult.success():OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token"));
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(JwtValidators.createDefaultWithIssuer(issuer),claims));
        return decoder;
    }
    @Bean
    SecurityFilterChain security(HttpSecurity http,Environment environment,
            @Value("${scheduler.security.mode:jwt}") String mode) throws Exception {
        boolean disabled="disabled".equals(mode);
        Set<String> profiles=Set.of(environment.getActiveProfiles());
        if(disabled && (!profiles.contains("local") && !profiles.contains("test") || profiles.contains("prod")))
            throw new IllegalArgumentException("authentication may only be disabled in local/test profiles");
        if(!disabled && !"jwt".equals(mode)) throw new IllegalArgumentException("unknown security mode");
        http.csrf(csrf->csrf.disable()).sessionManagement(s->s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .requestCache(cache->cache.disable());
        if(disabled) {
            http.authorizeHttpRequests(auth->auth.anyRequest().permitAll());
        } else {
            http.oauth2ResourceServer(oauth->oauth.jwt(Customizer.withDefaults()));
            http.authorizeHttpRequests(auth->auth
                .requestMatchers("/actuator/health","/actuator/health/liveness","/actuator/health/readiness").permitAll()
                .requestMatchers("/actuator/prometheus","/actuator/metrics/**").hasAuthority("SCOPE_scheduler:observe")
                .requestMatchers("/v1/tenants/{tenantId}/**").access((authentication,context)->{
                    var principal=authentication.get();
                    if(principal==null || !principal.isAuthenticated() || !(principal.getPrincipal() instanceof Jwt jwt)) return new AuthorizationDecision(false);
                    String tenant=context.getVariables().get("tenantId");
                    Object tenants=jwt.getClaims().get("tenants");
                    boolean belongs=Objects.equals(tenant,jwt.getClaims().get("tenant_id"))
                            || tenants instanceof Collection<?> values && values.contains(tenant);
                    String uri=context.getRequest().getRequestURI();
                    String required=HttpMethod.GET.matches(context.getRequest().getMethod())?"SCOPE_schedules:read"
                            :uri.endsWith(":redrive")?"SCOPE_schedules:redrive":"SCOPE_schedules:write";
                    boolean scope=principal.getAuthorities().stream().anyMatch(a->a.getAuthority().equals(required));
                    return new AuthorizationDecision(belongs && scope);
                }).anyRequest().denyAll());
        }
        return http.build();
    }
}
