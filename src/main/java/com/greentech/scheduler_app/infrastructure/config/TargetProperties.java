package com.greentech.scheduler_app.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.Set;

@ConfigurationProperties("scheduler.targets")
public record TargetProperties(boolean fakeEnabled, Map<String, Endpoint> endpoints) {
    public TargetProperties { endpoints=endpoints==null?Map.of():Map.copyOf(endpoints); }
    public record Endpoint(URI url, Set<String> tenants, String bearerTokenEnv, Duration timeout,
            Integer maxConcurrency, Integer tenantConcurrency, boolean allowPrivateNetwork) {
        public Endpoint {
            tenants=tenants==null?Set.of():Set.copyOf(tenants);
            timeout=timeout==null?Duration.ofSeconds(5):timeout;
            maxConcurrency=maxConcurrency==null?8:maxConcurrency;
            tenantConcurrency=tenantConcurrency==null?2:tenantConcurrency;
        }
    }
}
