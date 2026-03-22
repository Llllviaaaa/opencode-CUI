package com.opencode.cui.skill.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Map;

@Slf4j
@Component
public class ImTokenAuthInterceptor implements HandlerInterceptor {

    private final String inboundToken;
    private final ObjectMapper objectMapper;

    public ImTokenAuthInterceptor(
            @org.springframework.beans.factory.annotation.Value("${skill.im.inbound-token:}") String inboundToken,
            ObjectMapper objectMapper) {
        this.inboundToken = inboundToken;
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        String path = request.getRequestURI();
        String remoteAddr = request.getRemoteAddr();

        if (inboundToken == null || inboundToken.isBlank()) {
            log.warn("[AUTH_FAIL] IM token auth: reason=token_not_configured, path={}, remoteAddr={}",
                    path, remoteAddr);
            writeUnauthorized(response, "Inbound token is not configured");
            return false;
        }

        String auth = request.getHeader("Authorization");
        if (auth == null || !auth.startsWith("Bearer ")) {
            log.warn("[AUTH_FAIL] IM token auth: reason=missing_token, path={}, remoteAddr={}",
                    path, remoteAddr);
            writeUnauthorized(response, "Missing token");
            return false;
        }

        String token = auth.substring(7);
        if (!inboundToken.equals(token)) {
            log.warn("[AUTH_FAIL] IM token auth: reason=invalid_token, path={}, remoteAddr={}",
                    path, remoteAddr);
            writeUnauthorized(response, "Invalid token");
            return false;
        }

        log.info("[AUTH_OK] IM token auth: path={}, remoteAddr={}", path, remoteAddr);
        return true;
    }

    private void writeUnauthorized(HttpServletResponse response, String message) throws Exception {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write(objectMapper.writeValueAsString(
                Map.of("code", 401, "errormsg", message)));
    }
}
