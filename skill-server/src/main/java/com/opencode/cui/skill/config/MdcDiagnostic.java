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
        // 1. SLF4J MDC
        MDC.put("traceId", "DIAG-SLF4J-001");
        log.info("[MDC-DIAG] After SLF4J MDC.put traceId=DIAG-SLF4J-001");
        String readBack = MDC.get("traceId");
        log.info("[MDC-DIAG] SLF4J MDC.get traceId={}", readBack);

        // 2. Log4j2 ThreadContext directly
        org.apache.logging.log4j.ThreadContext.put("traceId", "DIAG-LOG4J-002");
        log.info("[MDC-DIAG] After Log4j2 ThreadContext.put traceId=DIAG-LOG4J-002");
        String readBack2 = org.apache.logging.log4j.ThreadContext.get("traceId");
        log.info("[MDC-DIAG] Log4j2 ThreadContext.get traceId={}", readBack2);

        // 3. Check MDC adapter class
        log.info("[MDC-DIAG] MDC adapter class: {}", MDC.getMDCAdapter().getClass().getName());

        // Cleanup
        MDC.remove("traceId");
        org.apache.logging.log4j.ThreadContext.remove("traceId");
        log.info("[MDC-DIAG] Cleanup done, traceId should be empty now");
    }
}
