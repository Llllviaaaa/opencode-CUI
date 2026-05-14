package com.opencode.cui.gateway.service.cloud.decoder;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * SSE decoder 工厂。
 *
 * <p>Spring 自动收集所有 {@link SseEventDecoder} 实现，按 {@code getName()}
 * 建立查找表，运行时按 cloud profile 名称解析。</p>
 */
@Slf4j
@Service
public class SseEventDecoderFactory {

    public static final String DEFAULT_DECODER = "default";

    private final Map<String, SseEventDecoder> decoderMap;

    public SseEventDecoderFactory(List<SseEventDecoder> decoders) {
        this.decoderMap = decoders.stream()
                .collect(Collectors.toMap(SseEventDecoder::getName, Function.identity()));
        log.info("[SSE_DECODER] registered: {}", decoderMap.keySet());
    }

    /**
     * 按 cloud profile 名解析 decoder。未注册时 fallback 到 default decoder。
     */
    public SseEventDecoder resolveDecoder(String cloudProfile) {
        String key = (cloudProfile == null || cloudProfile.isBlank()) ? DEFAULT_DECODER : cloudProfile;
        SseEventDecoder d = decoderMap.get(key);
        if (d == null) {
            log.warn("[SSE_DECODER] unknown cloudProfile={}, fallback to default", cloudProfile);
            d = decoderMap.get(DEFAULT_DECODER);
        }
        return d;
    }
}
