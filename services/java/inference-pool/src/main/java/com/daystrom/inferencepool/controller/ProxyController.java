package com.daystrom.inferencepool.controller;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.opentelemetry.api.trace.Span;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Enumeration;
import java.util.Set;

@RestController
public class ProxyController {

    private static final Logger log = LoggerFactory.getLogger(ProxyController.class);

    private static final Set<String> HOP_BY_HOP = Set.of(
            "host", "connection", "transfer-encoding", "keep-alive",
            "upgrade", "proxy-authorization", "proxy-authenticate", "te", "trailers",
            "content-length");

    private final RestTemplate restTemplate;
    private final HttpClient httpClient;
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
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
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

    /**
     * Streaming reverse proxy — writes directly to the servlet response,
     * bypassing Spring's response processing to avoid header conflicts.
     */
    @RequestMapping("/v1/**")
    public void proxy(HttpServletRequest request, HttpServletResponse response,
                      @RequestBody(required = false) byte[] body) throws IOException {

        String path = request.getRequestURI();
        String queryString = request.getQueryString();
        String targetUrl = vllmBaseUrl + path + (queryString != null ? "?" + queryString : "");

        Span span = Span.current();
        span.setAttribute("pool.name", poolName);
        span.setAttribute("pool.type", poolType);
        span.setAttribute("inference.target_url", targetUrl);

        log.info("Proxying {} {} → {}", request.getMethod(), path, targetUrl);
        requestCounter.increment();
        Timer.Sample timerSample = Timer.start();

        try {
            // Build upstream request
            HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(targetUrl))
                    .timeout(Duration.ofSeconds(120));

            // Copy non-hop-by-hop headers from the incoming request
            Enumeration<String> headerNames = request.getHeaderNames();
            while (headerNames.hasMoreElements()) {
                String name = headerNames.nextElement();
                if (!HOP_BY_HOP.contains(name.toLowerCase())) {
                    reqBuilder.header(name, request.getHeader(name));
                }
            }

            // Set method + body
            if (body != null && body.length > 0) {
                reqBuilder.method(request.getMethod(), HttpRequest.BodyPublishers.ofByteArray(body));
            } else {
                reqBuilder.method(request.getMethod(), HttpRequest.BodyPublishers.noBody());
            }

            // Execute — get a streaming InputStream response
            HttpResponse<InputStream> upstream = httpClient.send(
                    reqBuilder.build(), HttpResponse.BodyHandlers.ofInputStream());

            // Set response status
            response.setStatus(upstream.statusCode());

            // Copy safe response headers from upstream
            upstream.headers().map().forEach((name, values) -> {
                if (!HOP_BY_HOP.contains(name.toLowerCase()) && !name.startsWith(":")) {
                    for (String v : values) {
                        response.addHeader(name, v);
                    }
                }
            });

            // Add pool metadata
            response.setHeader("X-Pool-Name", poolName);
            response.setHeader("X-Pool-Type", poolType);

            span.setAttribute("http.status_code", upstream.statusCode());

            // Stream the body directly — flush each chunk immediately
            OutputStream out = response.getOutputStream();
            try (InputStream in = upstream.body()) {
                byte[] buffer = new byte[1024];
                int bytesRead;
                while ((bytesRead = in.read(buffer)) != -1) {
                    out.write(buffer, 0, bytesRead);
                    out.flush();
                }
            }

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            span.recordException(e);
            response.setStatus(502);
            response.getWriter().write("{\"error\": \"proxy interrupted\"}");
        } catch (Exception e) {
            log.error("Proxy failed: {}", e.getMessage());
            span.recordException(e);
            if (!response.isCommitted()) {
                response.setStatus(502);
                response.setContentType("application/json");
                response.getWriter().write("{\"error\": \"inference backend unavailable: " + e.getMessage() + "\"}");
            }
        } finally {
            timerSample.stop(latencyTimer);
        }
    }

    @GetMapping("/health")
    public ResponseEntity<String> health() {
        try {
            restTemplate.getForEntity(vllmBaseUrl + "/health", String.class);
            return ResponseEntity.ok("{\"status\":\"ok\",\"backend\":\"healthy\"}");
        } catch (Exception e) {
            return ResponseEntity.status(503)
                    .body("{\"status\":\"degraded\",\"backend\":\"unreachable\"}");
        }
    }
}
