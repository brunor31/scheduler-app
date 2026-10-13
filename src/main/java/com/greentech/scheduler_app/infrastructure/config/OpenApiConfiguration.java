package com.greentech.scheduler_app.infrastructure.config;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.info.License;
import io.swagger.v3.oas.annotations.servers.Server;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@OpenAPIDefinition(
		info = @Info(
				title = "Durable Scheduling Platform API",
				version = "v1",
				description = "API for durable schedules with at-least-once delivery.",
				license = @License(name = "Proprietary")
		),
		servers = @Server(url = "/", description = "Current server")
)
public class OpenApiConfiguration {
}
