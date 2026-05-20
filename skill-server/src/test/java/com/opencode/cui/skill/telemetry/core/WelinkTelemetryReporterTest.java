package com.opencode.cui.skill.telemetry.core;

import com.opencode.cui.skill.telemetry.client.WelinkTelemetryClient;
import com.opencode.cui.skill.telemetry.client.dto.TelemetryPayload;
import com.opencode.cui.skill.telemetry.config.WelinkTelemetryProperties;
import com.opencode.cui.skill.logging.MdcConstants;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.MDC;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class WelinkTelemetryReporterTest {

    private WelinkTelemetryProperties properties;
    private WelinkTelemetryClient client;
    private TelemetryExecutor executor;

    @BeforeEach
    void setUp() {
        // 防止上游测试 MDC 泄漏污染 traceId 断言（full-suite 跑时 surefire 复用 forked JVM 线程）
        MDC.remove(MdcConstants.TRACE_ID);
        properties = new WelinkTelemetryProperties();
        properties.setPolicyName("POLICY_TEST");
        properties.setServiceName("svc-x");
        properties.setAppName("app-x");
        properties.setAppPackageName("pkg.x");
        properties.setTenantId("tenant-1");

        client = mock(WelinkTelemetryClient.class);
        executor = mock(TelemetryExecutor.class);
        // 让 executor.submit(r) 立即执行 runnable，便于断言 client.send 被调
        doAnswer(inv -> {
            Runnable r = inv.getArgument(0);
            r.run();
            return null;
        }).when(executor).submit(any(Runnable.class));
    }

    @AfterEach
    void tearDown() {
        MDC.remove(MdcConstants.TRACE_ID);
    }

    @Test
    @DisplayName("effectiveEnabled=true: 调 client.send, payload.data 含全部 PRD §3 字段")
    void enabledReportsWithAllFields() {
        WelinkTelemetryReporter reporter = new WelinkTelemetryReporter(properties, client, executor, true);

        Map<String, Object> extend = new LinkedHashMap<>();
        extend.put("businessTag", "tag-A");
        TelemetryEvent ev = simple("skill_chat_request", "用户发起 chat 对话",
                "biz-session-99", "user-001", extend);

        reporter.report(ev);

        ArgumentCaptor<TelemetryPayload> payloadCap = ArgumentCaptor.forClass(TelemetryPayload.class);
        verify(client).send(eq("skill_chat_request"), eq("biz-session-99"), payloadCap.capture());
        TelemetryPayload payload = payloadCap.getValue();
        assertNotNull(payload);
        assertEquals("POLICY_TEST", payload.policyName());

        Map<String, Object> d = payload.data();
        assertEquals("POLICY_TEST", d.get("policyName"));
        assertEquals("svc-x", d.get("serviceName"));
        assertEquals("app-x", d.get("appName"));
        assertEquals("pkg.x", d.get("appPackageName"));
        assertEquals("tenant-1", d.get("tenantId"));
        assertEquals("biz-session-99", d.get("sessionId"));
        assertEquals("event", d.get("eventType"));
        assertNotNull(d.get("eventTime"));
        assertEquals("skill_chat_request", d.get("eventId"));
        assertEquals("用户发起 chat 对话", d.get("eventLabel"));
        assertEquals("user-001", d.get("userId"));
        assertEquals(extend, d.get("extendData"));
        // traceId 在没有 MDC 时为 ""
        assertEquals("", d.get("traceId"));
    }

    @Test
    @DisplayName("effectiveEnabled=false: 直接 return，client/executor 都不调")
    void disabledShortCircuit() {
        WelinkTelemetryReporter reporter = new WelinkTelemetryReporter(properties, client, executor, false);

        reporter.report(simple("any", "any", "s", "u", new LinkedHashMap<>()));

        verifyNoInteractions(executor);
        verifyNoInteractions(client);
    }

    @Test
    @DisplayName("event 为 null: 不抛, client 不调")
    void nullEventNoOp() {
        WelinkTelemetryReporter reporter = new WelinkTelemetryReporter(properties, client, executor, true);
        reporter.report(null);
        verify(client, never()).send(anyString(), anyString(), any());
    }

    private static TelemetryEvent simple(String eventId, String label,
                                         String sessionId, String userId,
                                         Map<String, Object> extend) {
        return new TelemetryEvent() {
            @Override public String eventId() { return eventId; }
            @Override public String eventLabel() { return label; }
            @Override public String sessionId() { return sessionId; }
            @Override public String userId() { return userId; }
            @Override public Map<String, Object> extendData() { return extend; }
        };
    }
}
