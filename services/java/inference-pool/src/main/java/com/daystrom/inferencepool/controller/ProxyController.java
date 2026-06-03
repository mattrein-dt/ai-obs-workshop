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
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Collections;
import java.util.Enumeration;

@RestController
public class ProxyController {

    private static final Logger log = LoggerFactory.getLogger(ProxyController.class);

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

    @RequestMapping("/v1/**")
    public ResponseEntity<StreamingResponseBody> proxy(HttpServletRequest request, @RequestBody(required = false) byte[] body) {
        String path = request.getRequestURI();
        String queryString = request.getQueryString();
        String targetUrl = vllmBaseUrl + path + (queryString != null ? "?" + queryString : "");

        Span span = Span.current();
        span.setAttribute("pool.name", poolName);
        span.setAttribute("pool.type", poolType);
        span.setAttribute("inference.target_url", targetUrl);

        log.info("Proxying {} {} → {}", request.getMethod(), path, targetUrl);
        requestCounter.increment();

        try {
            // Build the upstream request
            HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(targetUrl))
                    .timeout(Duration.ofSeconds(120));

            // Copy non-hop-by-hop headers
            Enumeration<String> headerNames = request.getHeaderNames();
            while (headerNames.hasMoreElements()) {
                String name = headerNames.nextElement();
                if (!isHopByHopHeader(name)) {
                    reqBuilder.header(name, request.getHeader(name));
                }
            }

            // Set method and body
            if (body != null && body.length > 0) {
                reqBuilder.method(request.getMethod(), HttpRequest.BodyPublishers.ofByteArray(body));
            } else {
                reqBuilder.method(request.getMethod(), HttpRequest.BodyPublishers.noBody());
            }

            // Send request, get streaming response
            HttpResponse<InputStream> response = httpClient.send(
                    reqBuilder.build(), HttpResponse.BodyHandlers.ofInputStream());

            // Determine content type from upstream
            String contentType = response.headers().firstValue("content-type")
                    .orElse("application/json");

            span.setAttribute("http.status_code", response.statusCode());

            StreamingResponseBody streamBody = outputStream -> {
                Timer.Sample sample = Timer.start();
                try (InputStream is = response.body()) {
                    byte[] buffer = new byte[1024];
                    int bytesRead;
                    while ((bytesRead = is.read(buffer)) != -1) {
                        outputStream.write(buffer, 0, bytesRead);
                        outputStream.flush();
                    }
                } finally {
                    sample.stop(latencyTimer);
                }
            };

            HttpHeaders responseHeaders = new HttpHeaders();
            responseHeaders.set("Content-Type", contentType);
            responseHeaders.set("X-Pool-Name", poolName);
            responseHeaders.set("X-Pool-Type", poolType);
            responseHeaders.set("Cache-Control", "no-cache");

            return new ResponseEntity<>(streamBody, responseHeaders,
                    HttpStatus.valueOf(response.statusCode()));
        } catch (Exception e) {
            log.error("Proxy failed: {}", e.getMessage());
            span.recordException(e);
            StreamingResponseBody errorBody = out -> {
                out.write(("{\"error\": \"inference backend unavailable: " + e.getMessage() + "\"}").getBytes());
                out.flush();
            };
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(errorBody);
        }
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
