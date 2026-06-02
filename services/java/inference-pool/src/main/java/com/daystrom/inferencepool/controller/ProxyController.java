package com.daystrom.inferencepool.controller;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.opentelemetry.api.trace.Span;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;

import java.util.Collections;
import java.util.Enumeration;

@RestController
public class ProxyController {

    private static final Logger log = LoggerFactory.getLogger(ProxyController.class);

    private final RestTemplate restTemplate;
    private final String vllmBaseUrl;
    private final String poolName;
    private final String poolType;
    private final Counter requestCounter;
    private final Timer latencyTimer;

    public ProxyController(
            @Value("${inference-pool.vllm-base-url}") String vllmBaseUrl,
            @Value("${inference-pool.pool-name}") String poolName,
            @Value("${inference-pool.pool-type}") String poolType,
            MeterRegistry meterRegistry) {
        this.restTemplate = new RestTemplate();
        this.vllmBaseUrl = vllmBaseUrl;
        this.poolName = poolName;
        this.poolType = poolType;
        this.requestCounter = Counter.builder("pool_requests_total")
                .tag("pool_name", poolName)
                .register(meterRegistry);
        this.latencyTimer = Timer.builder("pool_latency_seconds")
                .tag("pool_name", poolName)
                .register(meterRegistry);
    }

    @RequestMapping("/v1/**")
    public ResponseEntity<byte[]> proxy(HttpServletRequest request, @RequestBody(required = false) byte[] body) {
        return latencyTimer.record(() -> {
            String path = request.getRequestURI();
            String queryString = request.getQueryString();
            String targetUrl = vllmBaseUrl + path + (queryString != null ? "?" + queryString : "");

            Span span = Span.current();
            span.setAttribute("pool.name", poolName);
            span.setAttribute("pool.type", poolType);
            span.setAttribute("inference.target_url", targetUrl);

            log.info("Proxying {} {} → {}", request.getMethod(), path, targetUrl);
            requestCounter.increment();

            // Build headers (skip hop-by-hop)
            HttpHeaders headers = new HttpHeaders();
            Enumeration<String> headerNames = request.getHeaderNames();
            while (headerNames.hasMoreElements()) {
                String name = headerNames.nextElement();
                if (!isHopByHopHeader(name)) {
                    headers.put(name, Collections.list(request.getHeaders(name)));
                }
            }

            HttpEntity<byte[]> entity = new HttpEntity<>(body, headers);
            HttpMethod method = HttpMethod.valueOf(request.getMethod());

            try {
                ResponseEntity<byte[]> response = restTemplate.exchange(
                        targetUrl, method, entity, byte[].class);

                // Add pool metadata headers
                HttpHeaders responseHeaders = new HttpHeaders();
                responseHeaders.putAll(response.getHeaders());
                responseHeaders.set("X-Pool-Name", poolName);
                responseHeaders.set("X-Pool-Type", poolType);

                span.setAttribute("http.status_code", response.getStatusCode().value());
                return new ResponseEntity<>(response.getBody(), responseHeaders, response.getStatusCode());
            } catch (Exception e) {
                log.error("Proxy failed: {}", e.getMessage());
                span.recordException(e);
                return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                        .body(("{\"error\": \"inference backend unavailable: " + e.getMessage() + "\"}").getBytes());
            }
        });
    }

    @GetMapping("/health")
    public ResponseEntity<String> health() {
        try {
            ResponseEntity<String> response = restTemplate.getForEntity(
                    vllmBaseUrl + "/health", String.class);
            return ResponseEntity.ok("{\"status\":\"ok\",\"backend\":\"healthy\"}");
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body("{\"status\":\"degraded\",\"backend\":\"unreachable\"}");
        }
    }

    private boolean isHopByHopHeader(String name) {
        String lower = name.toLowerCase();
        return lower.equals("host") || lower.equals("connection") ||
               lower.equals("transfer-encoding") || lower.equals("keep-alive") ||
               lower.equals("upgrade");
    }
}
