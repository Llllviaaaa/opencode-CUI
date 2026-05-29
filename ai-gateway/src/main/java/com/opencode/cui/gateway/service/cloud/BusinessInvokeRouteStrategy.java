package com.opencode.cui.gateway.service.cloud;

import com.opencode.cui.gateway.logging.MdcHelper;
import com.opencode.cui.gateway.model.GatewayMessage;
import com.opencode.cui.gateway.service.CloudAgentService;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;

/**
 * 业务助手路由策略。
 *
 * <p>将 invoke 消息委托给 {@link CloudAgentService} 处理，
 * 通过云端服务完成 AI 对话。</p>
 *
 * <p>{@code onRelay} 回调由 {@link com.opencode.cui.gateway.service.SkillRelayService} 传入，
 * 透传给 CloudAgentService，使得云端响应可回流到 SkillRelayService，
 * 同时避免直接依赖 SkillRelayService 造成循环依赖。</p>
 */
@Slf4j
@Component
public class BusinessInvokeRouteStrategy implements InvokeRouteStrategy {

    private static final String ACTION_ABORT_SESSION = "abort_session";

    private final CloudAgentService cloudAgentService;
    private final Executor routeExecutor;
    private final ExecutorService ownedExecutor;

    @Autowired
    public BusinessInvokeRouteStrategy(CloudAgentService cloudAgentService) {
        this(cloudAgentService, Executors.newVirtualThreadPerTaskExecutor(), true);
    }

    BusinessInvokeRouteStrategy(CloudAgentService cloudAgentService, Executor routeExecutor) {
        this(cloudAgentService, routeExecutor, false);
    }

    private BusinessInvokeRouteStrategy(CloudAgentService cloudAgentService,
                                        Executor routeExecutor,
                                        boolean ownsExecutor) {
        this.cloudAgentService = cloudAgentService;
        this.routeExecutor = routeExecutor;
        this.ownedExecutor = ownsExecutor && routeExecutor instanceof ExecutorService executorService
                ? executorService : null;
    }

    @Override
    public String getScope() {
        return "business";
    }

    @Override
    public void route(GatewayMessage message, Consumer<GatewayMessage> onRelay) {
        if (isAbortSession(message)) {
            log.info("[INVOKE_ROUTE] Business abort, delegating inline to CloudAgentService: ak={}",
                    message.getAk());
            cloudAgentService.handleInvoke(message, onRelay);
            return;
        }

        Map<String, String> capturedMdc = MdcHelper.snapshot();
        try {
            routeExecutor.execute(() -> handleInvokeWithMdc(message, onRelay, capturedMdc));
            log.info("[INVOKE_ROUTE] Business scope, scheduled CloudAgentService: ak={}, action={}",
                    message.getAk(), message.getAction());
        } catch (RejectedExecutionException e) {
            log.warn("[INVOKE_ROUTE] Business route executor rejected task, running inline: ak={}, action={}, error={}",
                    message.getAk(), message.getAction(), e.getMessage());
            handleInvokeWithMdc(message, onRelay, capturedMdc);
        }
    }

    @PreDestroy
    public void shutdown() {
        if (ownedExecutor != null) {
            ownedExecutor.shutdown();
        }
    }

    private void handleInvokeWithMdc(GatewayMessage message,
                                     Consumer<GatewayMessage> onRelay,
                                     Map<String, String> capturedMdc) {
        Map<String, String> previousMdc = MdcHelper.snapshot();
        try {
            MdcHelper.restore(capturedMdc);
            cloudAgentService.handleInvoke(message, onRelay);
        } catch (RuntimeException | Error e) {
            log.error("[INVOKE_ROUTE] Business cloud invoke failed: ak={}, action={}, traceId={}, error={}",
                    message.getAk(), message.getAction(), message.getTraceId(), e.getMessage(), e);
            throw e;
        } finally {
            MdcHelper.restore(previousMdc);
        }
    }

    private static boolean isAbortSession(GatewayMessage message) {
        if (message == null || message.getAction() == null) {
            return false;
        }
        return ACTION_ABORT_SESSION.equals(message.getAction().trim().toLowerCase(Locale.ROOT));
    }
}
