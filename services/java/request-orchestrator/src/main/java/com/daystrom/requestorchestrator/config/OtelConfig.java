package com.daystrom.requestorchestrator.config;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Tracer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OtelConfig {

    @Bean
    public Tracer tracer() {
        return GlobalOpenTelemetry.getTracer("request-orchestrator");
    }
}
