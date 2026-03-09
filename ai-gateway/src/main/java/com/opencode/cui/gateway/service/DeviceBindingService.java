package com.opencode.cui.gateway.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

/**
 * Validates AK binding to a specific device (MAC address) and tool type.
 * Calls a third-party service for validation.
 *
 * Fail-open strategy: if the third-party service is unavailable,
 * the connection is allowed and a warning is logged.
 */
@Slf4j
@Service
public class DeviceBindingService {

    @Value("${gateway.device-binding.url:}")
    private String bindingServiceUrl;

    @Value("${gateway.device-binding.timeout-ms:3000}")
    private int timeoutMs;

    @Value("${gateway.device-binding.enabled:false}")
    private boolean enabled;

    private final RestTemplate restTemplate;

    public DeviceBindingService() {
        this.restTemplate = new RestTemplate();
    }

    /**
     * Validate that the given AK is bound to the specified MAC address and tool
     * type.
     *
     * @param ak         Access Key ID
     * @param macAddress Device MAC address
     * @param toolType   Tool type (e.g. OPENCODE)
     * @return true if validation passes or service is unavailable (fail-open)
     */
    public boolean validate(String ak, String macAddress, String toolType) {
        if (!enabled) {
            log.debug("Device binding validation disabled, allowing: ak={}", ak);
            return true;
        }

        if (bindingServiceUrl == null || bindingServiceUrl.isBlank()) {
            log.warn("Device binding service URL not configured, fail-open: ak={}", ak);
            return true;
        }

        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            Map<String, String> body = Map.of(
                    "ak", ak,
                    "macAddress", macAddress,
                    "toolType", toolType);

            HttpEntity<Map<String, String>> request = new HttpEntity<>(body, headers);

            @SuppressWarnings("unchecked")
            Map<String, Object> response = restTemplate.postForObject(
                    bindingServiceUrl, request, Map.class);

            if (response != null && Boolean.TRUE.equals(response.get("valid"))) {
                log.debug("Device binding validated: ak={}, mac={}, toolType={}", ak, macAddress, toolType);
                return true;
            }

            String message = response != null ? String.valueOf(response.get("message")) : "unknown";
            log.warn("Device binding validation failed: ak={}, mac={}, toolType={}, reason={}",
                    ak, macAddress, toolType, message);
            return false;

        } catch (Exception e) {
            log.warn("Device binding service unavailable, fail-open: ak={}, error={}",
                    ak, e.getMessage());
            return true; // Fail-open: allow connection when service is down
        }
    }
}
