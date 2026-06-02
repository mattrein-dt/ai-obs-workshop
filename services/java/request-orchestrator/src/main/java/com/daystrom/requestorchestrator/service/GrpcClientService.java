package com.daystrom.requestorchestrator.service;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import net.devh.boot.grpc.client.inject.GrpcClient;
import org.springframework.stereotype.Service;

import com.daystrom.proto.*;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

@Service
public class GrpcClientService {

    private final Tracer tracer;

    @GrpcClient("prompt-cache")
    private PromptCacheServiceGrpc.PromptCacheServiceBlockingStub promptCacheStub;

    @GrpcClient("safety-gateway")
    private SafetyServiceGrpc.SafetyServiceBlockingStub safetyStub;

    @GrpcClient("model-router")
    private ModelRouterServiceGrpc.ModelRouterServiceBlockingStub modelRouterStub;

    public GrpcClientService(Tracer tracer) {
        this.tracer = tracer;
    }

    public boolean classifySafety(String text, String conversationId, String tenantId) {
        Span span = tracer.spanBuilder("safety.classify").startSpan();
        try {
            ClassifyRequest request = ClassifyRequest.newBuilder()
                    .setText(text)
                    .setConversationId(conversationId)
                    .setTenantId(tenantId)
                    .build();
            ClassifyResponse response = safetyStub.classify(request);
            span.setAttribute("safety.safe", response.getSafe());
            span.setAttribute("safety.confidence", response.getConfidence());
            return response.getSafe();
        } finally {
            span.end();
        }
    }

    public boolean checkPromptCache(String promptHash) {
        Span span = tracer.spanBuilder("cache.check").startSpan();
        try {
            GetCachedPrefixRequest request = GetCachedPrefixRequest.newBuilder()
                    .setPromptHash(promptHash)
                    .build();
            GetCachedPrefixResponse response = promptCacheStub.getCachedPrefix(request);
            span.setAttribute("cache.hit", response.getFound());
            span.setAttribute("cache.tokens", response.getCachedTokens());
            return response.getFound();
        } finally {
            span.end();
        }
    }

    public void storePromptCache(String promptHash, byte[] tokens) {
        Span span = tracer.spanBuilder("cache.store").startSpan();
        try {
            PutPrefixRequest request = PutPrefixRequest.newBuilder()
                    .setPromptHash(promptHash)
                    .setTokens(com.google.protobuf.ByteString.copyFrom(tokens))
                    .setTtlSeconds(3600)
                    .build();
            promptCacheStub.putPrefix(request);
        } finally {
            span.end();
        }
    }

    public Iterator<String> streamInference(String model, String prompt, String tenantId) {
        Span span = tracer.spanBuilder("inference.stream").startSpan();
        span.setAttribute("gen_ai.request.model", model);
        try {
            RouteInferenceRequest request = RouteInferenceRequest.newBuilder()
                    .setModelId(model)
                    .setPrompt(prompt)
                    .setMaxTokens(256)
                    .setTemperature(0.7f)
                    .setTenantId(tenantId)
                    .build();

            Iterator<RouteInferenceResponse> responses = modelRouterStub.routeInference(request);
            List<String> tokens = new ArrayList<>();
            while (responses.hasNext()) {
                RouteInferenceResponse resp = responses.next();
                tokens.add(resp.getText());
            }
            span.setAttribute("gen_ai.response.tokens", tokens.size());
            return tokens.iterator();
        } finally {
            span.end();
        }
    }
}
