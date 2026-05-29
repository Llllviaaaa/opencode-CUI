package com.opencode.cui.gateway.service.cloud;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CloudRemoteRequestLogHelperTest {

    @Test
    @DisplayName("maskedHeaders masks sensitive header values and keeps diagnostic headers")
    void maskedHeaders_masksSensitiveValuesOnly() {
        Map<String, List<String>> masked = CloudRemoteRequestLogHelper.maskedHeaders(Map.of(
                "Authorization", List.of("integration-token-123456"),
                "X-Trace-Id", List.of("trace-1"),
                "X-App-Id", List.of("app-1"),
                "X-Signature", List.of("abcdef1234567890")));

        assertEquals(List.of("inte****3456"), masked.get("Authorization"));
        assertEquals(List.of("trace-1"), masked.get("X-Trace-Id"));
        assertEquals(List.of("app-1"), masked.get("X-App-Id"));
        assertEquals(List.of("abcd****7890"), masked.get("X-Signature"));
    }
}
