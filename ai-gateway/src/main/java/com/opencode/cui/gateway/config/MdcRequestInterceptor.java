package com.opencode.cui.gateway.config;

import com.opencode.cui.gateway.logging.MdcHelper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * REST 请求的 MDC 拦截器。
 * 在请求入口自动设置 traceId（从 X-Trace-Id header 或自动生成），
 * 在请求结束后清理 MDC。
 */
@Component
public class MdcRequestInterceptor implements HandlerInterceptor {

    private static final String TRACE_ID_HEADER = "X-Trace-Id";

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String traceId = request.getHeader(TRACE_ID_HEADER);
        if (traceId != null && !traceId.isBlank()) {
            MdcHelper.putTraceId(traceId);
        } else {
            MdcHelper.ensureTraceId();
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
