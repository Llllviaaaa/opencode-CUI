package com.opencode.cui.skill.config;

import com.opencode.cui.skill.logging.MdcHelper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * REST 请求的 MDC 拦截器。
 * 在请求入口自动设置 traceId、sessionId，
 * 在请求结束后清理 MDC。
 */
@Component
public class MdcRequestInterceptor implements HandlerInterceptor {

    private static final String TRACE_ID_HEADER = "X-Trace-Id";

    /** 匹配 /api/skill/sessions/{sessionId} 或 /api/skill/sessions/{sessionId}/... */
    private static final Pattern SESSION_PATH_PATTERN = Pattern.compile("/api/skill/sessions/([^/]+)");

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        // traceId: 从 header 读取或自动生成
        String traceId = request.getHeader(TRACE_ID_HEADER);
        if (traceId != null && !traceId.isBlank()) {
            MdcHelper.putTraceId(traceId);
        } else {
            MdcHelper.ensureTraceId();
        }

        // sessionId: 从 URL 路径提取
        String path = request.getRequestURI();
        Matcher matcher = SESSION_PATH_PATTERN.matcher(path);
        if (matcher.find()) {
            MdcHelper.putSessionId(matcher.group(1));
        }

        MdcHelper.putScenario("rest-" + request.getMethod().toLowerCase());
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
            Object handler, Exception ex) {
        MdcHelper.clearAll();
    }
}
