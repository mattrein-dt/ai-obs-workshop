package com.daystrom.promptcache.service;

import com.daystrom.proto.*;
import io.grpc.stub.StreamObserver;
import net.devh.boot.grpc.server.service.GrpcService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;

@GrpcService
public class PromptCacheGrpcService extends PromptCacheServiceGrpc.PromptCacheServiceImplBase {

    private static final Logger log = LoggerFactory.getLogger(PromptCacheGrpcService.class);
    private static final String KEY_PREFIX = "prefix_cache:";
    private static final long DEFAULT_TTL_SECONDS = 3600;

    private final StringRedisTemplate redisTemplate;

    public PromptCacheGrpcService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public void getCachedPrefix(GetCachedPrefixRequest request,
                                StreamObserver<GetCachedPrefixResponse> responseObserver) {
        String key = KEY_PREFIX + request.getPromptHash();
        long start = System.currentTimeMillis();

        try {
            String cached = redisTemplate.opsForValue().get(key);
            long latency = System.currentTimeMillis() - start;

            if (cached != null) {
                log.info("Cache HIT for hash={} latency={}ms", request.getPromptHash(), latency);
                // Increment hit counter
                redisTemplate.opsForValue().increment(key + ":hits");

                responseObserver.onNext(GetCachedPrefixResponse.newBuilder()
                        .setFound(true)
                        .setTokens(com.google.protobuf.ByteString.copyFromUtf8(cached))
                        .setCachedTokens(cached.length() / 4) // approximate token count
                        .build());
            } else {
                log.info("Cache MISS for hash={} latency={}ms", request.getPromptHash(), latency);
                responseObserver.onNext(GetCachedPrefixResponse.newBuilder()
                        .setFound(false)
                        .build());
            }
            responseObserver.onCompleted();
        } catch (Exception e) {
            log.error("Cache lookup failed for hash={}", request.getPromptHash(), e);
            responseObserver.onNext(GetCachedPrefixResponse.newBuilder()
                    .setFound(false)
                    .build());
            responseObserver.onCompleted();
        }
    }

    @Override
    public void putPrefix(PutPrefixRequest request,
                          StreamObserver<PutPrefixResponse> responseObserver) {
        String key = KEY_PREFIX + request.getPromptHash();
        long ttl = request.getTtlSeconds() > 0 ? request.getTtlSeconds() : DEFAULT_TTL_SECONDS;

        try {
            String value = request.getTokens().toStringUtf8();
            redisTemplate.opsForValue().set(key, value, Duration.ofSeconds(ttl));

            log.info("Cached prefix hash={} ttl={}s", request.getPromptHash(), ttl);
            responseObserver.onNext(PutPrefixResponse.newBuilder()
                    .setSuccess(true)
                    .build());
        } catch (Exception e) {
            log.error("Failed to cache prefix hash={}", request.getPromptHash(), e);
            responseObserver.onNext(PutPrefixResponse.newBuilder()
                    .setSuccess(false)
                    .build());
        }
        responseObserver.onCompleted();
    }
}
