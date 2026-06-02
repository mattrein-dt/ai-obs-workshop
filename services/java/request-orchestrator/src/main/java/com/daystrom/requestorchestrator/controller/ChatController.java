package com.daystrom.requestorchestrator.controller;

import com.daystrom.requestorchestrator.service.GrpcClientService;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

@RestController
@RequestMapping("/api")
public class ChatController {

    private final GrpcClientService grpcClientService;
    private final Tracer tracer;

    public ChatController(GrpcClientService grpcClientService, Tracer tracer) {
        this.grpcClientService = grpcClientService;
        this.tracer = tracer;
    }

    @PostMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<StreamingResponseBody> chat(@RequestBody Map<String, Object> request) {
        String model = (String) request.getOrDefault("model", "large");
        String message = (String) request.getOrDefault("message", "");
        String conversationId = (String) request.getOrDefault("conversation_id", UUID.randomUUID().toString());
        String tenantId = (String) request.getOrDefault("tenant_id", "default");

        Span span = Span.current();
        span.setAttribute("chat.model", model);
        span.setAttribute("chat.conversation_id", conversationId);
        span.setAttribute("chat.tenant_id", tenantId);

        StreamingResponseBody body = outputStream -> {
            try {
                // 1. Safety check
                boolean safe = grpcClientService.classifySafety(message, conversationId, tenantId);
                if (!safe) {
                    String errorJson = "{\"error\": \"Message blocked by safety filter\"}";
                    outputStream.write(("data: " + errorJson + "\n\n").getBytes(StandardCharsets.UTF_8));
                    outputStream.flush();
                    return;
                }

                // 2. Check prompt cache
                String promptHash = sha256(message);
                boolean cacheHit = grpcClientService.checkPromptCache(promptHash);
                span.setAttribute("cache.hit", cacheHit);

                // 3. Stream inference via model-router
                Iterator<String> tokens = grpcClientService.streamInference(model, message, tenantId);
                while (tokens.hasNext()) {
                    String token = tokens.next();
                    String chunk = "{\"text\": \"" + escapeJson(token) + "\", \"cache_hit\": " + cacheHit + "}";
                    outputStream.write(("data: " + chunk + "\n\n").getBytes(StandardCharsets.UTF_8));
                    outputStream.flush();
                }

                // 4. Store in cache for next time
                if (!cacheHit) {
                    grpcClientService.storePromptCache(promptHash, message.getBytes(StandardCharsets.UTF_8));
                }

                outputStream.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
                outputStream.flush();
            } catch (Exception e) {
                span.recordException(e);
                String errorJson = "{\"error\": \"" + escapeJson(e.getMessage()) + "\"}";
                outputStream.write(("data: " + errorJson + "\n\n").getBytes(StandardCharsets.UTF_8));
                outputStream.flush();
            }
        };

        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .body(body);
    }

    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "ok");
    }

    private String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception e) {
            return input.hashCode() + "";
        }
    }

    private String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
