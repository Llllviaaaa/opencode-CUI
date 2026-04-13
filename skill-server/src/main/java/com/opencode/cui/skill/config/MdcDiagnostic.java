package com.opencode.cui.skill.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

/**
 * 启动时诊断 MDC 是否正常工作。可在确认后删除。
 */
@Component
public class MdcDiagnostic implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(MdcDiagnostic.class);

    @Override
    public void run(String... args) {
        // Check async logger status
        log.info("[MDC-DIAG] Logger factory: {}", org.slf4j.LoggerFactory.getILoggerFactory().getClass().getName());

        // Check ThreadContext implementation
        log.info("[MDC-DIAG] ThreadContext map class: {}",
            org.apache.logging.log4j.ThreadContext.getContext().getClass().getName());

        // Test: put via ThreadContext, log immediately
        org.apache.logging.log4j.ThreadContext.put("traceId", "DIAG-TC-001");

        // Use Log4j2 logger directly (bypass SLF4J)
        org.apache.logging.log4j.Logger log4j = org.apache.logging.log4j.LogManager.getLogger(MdcDiagnostic.class);
        log4j.info("[MDC-DIAG] Log4j2 direct logger, traceId should be DIAG-TC-001");

        // Use SLF4J logger
        log.info("[MDC-DIAG] SLF4J logger, traceId should be DIAG-TC-001");

        // Check if ThreadContext is using DefaultThreadContextMap or CopyOnWriteSortedArrayThreadContextMap
        log.info("[MDC-DIAG] ThreadContext impl: {}",
            System.getProperty("log4j2.threadContextMap", "default"));
        log.info("[MDC-DIAG] isThreadContextMapInheritable: {}",
            System.getProperty("log4j2.isThreadContextMapInheritable", "not set"));
        log.info("[MDC-DIAG] contextSelector: {}",
            System.getProperty("Log4jContextSelector",
                System.getProperty("log4j2.contextSelector", "not set")));

        // Check internal ThreadContextMap implementation
        try {
            java.lang.reflect.Field field = org.apache.logging.log4j.ThreadContext.class.getDeclaredField("contextMap");
            if (field == null) {
                // Log4j2 3.x uses PROVIDER
                field = org.apache.logging.log4j.ThreadContext.class.getDeclaredField("PROVIDER");
            }
            field.setAccessible(true);
            Object impl = field.get(null);
            log.info("[MDC-DIAG] ThreadContext internal impl: {}", impl.getClass().getName());
        } catch (Exception e) {
            log.info("[MDC-DIAG] Could not inspect ThreadContext internals: {}", e.getMessage());
        }

        // Check if ThreadContext is empty right after put
        log.info("[MDC-DIAG] ThreadContext.containsKey('traceId')={}",
            org.apache.logging.log4j.ThreadContext.containsKey("traceId"));
        org.apache.logging.log4j.ThreadContext.put("traceId", "DIAG-FINAL");
        log.info("[MDC-DIAG] After put, containsKey={}, get={}",
            org.apache.logging.log4j.ThreadContext.containsKey("traceId"),
            org.apache.logging.log4j.ThreadContext.get("traceId"));
        log.info("[MDC-DIAG] ThreadContext.getImmutableContext={}",
            org.apache.logging.log4j.ThreadContext.getImmutableContext());

        org.apache.logging.log4j.ThreadContext.clearAll();
    }
}
