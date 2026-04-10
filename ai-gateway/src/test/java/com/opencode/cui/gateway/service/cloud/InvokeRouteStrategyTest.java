package com.opencode.cui.gateway.service.cloud;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.opencode.cui.gateway.model.GatewayMessage;
import com.opencode.cui.gateway.service.CloudAgentService;
import com.opencode.cui.gateway.service.SkillRelayService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * InvokeRouteStrategy 调度测试（TDD）。
 *
 * <ul>
 *   <li>scope=personal 路由到 PersonalInvokeRouteStrategy</li>
 *   <li>scope=business 路由到 BusinessInvokeRouteStrategy</li>
 *   <li>scope=null 默认路由到 personal</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class InvokeRouteStrategyTest {

    @Mock
    private CloudAgentService cloudAgentService;

    private PersonalInvokeRouteStrategy personalStrategy;
    private BusinessInvokeRouteStrategy businessStrategy;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        personalStrategy = new PersonalInvokeRouteStrategy();
        businessStrategy = new BusinessInvokeRouteStrategy(cloudAgentService);
    }

    @Nested
    @DisplayName("PersonalInvokeRouteStrategy")
    class PersonalStrategyTests {

        @Test
        @DisplayName("getScope 返回 personal")
        void shouldReturnPersonalScope() {
            assertEquals("personal", personalStrategy.getScope());
        }

        @Test
        @DisplayName("route 方法为透传，不抛异常")
        void shouldPassThroughWithoutError() {
            GatewayMessage msg = GatewayMessage.builder()
                    .type(GatewayMessage.Type.INVOKE)
                    .ak("ak-001")
                    .build();

            // PersonalInvokeRouteStrategy.route 是 no-op（原有逻辑由 SkillRelayService 处理）
            assertDoesNotThrow(() -> personalStrategy.route(msg));
        }
    }

    @Nested
    @DisplayName("BusinessInvokeRouteStrategy")
    class BusinessStrategyTests {

        @Test
        @DisplayName("getScope 返回 business")
        void shouldReturnBusinessScope() {
            assertEquals("business", businessStrategy.getScope());
        }

        @Test
        @DisplayName("route 调用 cloudAgentService.handleInvoke")
        void shouldDelegateToCloudAgentService() {
            GatewayMessage msg = GatewayMessage.builder()
                    .type(GatewayMessage.Type.INVOKE)
                    .ak("ak-biz-001")
                    .assistantScope("business")
                    .build();

            businessStrategy.route(msg);

            verify(cloudAgentService).handleInvoke(msg);
        }
    }

    @Nested
    @DisplayName("策略 Map 调度")
    class StrategyMapDispatchTests {

        @Test
        @DisplayName("通过策略 Map 按 scope 正确调度")
        void shouldDispatchByScope() {
            Map<String, InvokeRouteStrategy> strategyMap = Map.of(
                    "personal", personalStrategy,
                    "business", businessStrategy
            );

            GatewayMessage bizMsg = GatewayMessage.builder()
                    .type(GatewayMessage.Type.INVOKE)
                    .ak("ak-biz-001")
                    .assistantScope("business")
                    .build();

            String scope = bizMsg.getAssistantScope() != null ? bizMsg.getAssistantScope() : "personal";
            InvokeRouteStrategy strategy = strategyMap.getOrDefault(scope, personalStrategy);
            strategy.route(bizMsg);

            verify(cloudAgentService).handleInvoke(bizMsg);
        }

        @Test
        @DisplayName("scope 为 null 时默认走 personal 策略")
        void shouldDefaultToPersonalWhenScopeNull() {
            Map<String, InvokeRouteStrategy> strategyMap = Map.of(
                    "personal", personalStrategy,
                    "business", businessStrategy
            );

            GatewayMessage msg = GatewayMessage.builder()
                    .type(GatewayMessage.Type.INVOKE)
                    .ak("ak-001")
                    .assistantScope(null)
                    .build();

            String scope = msg.getAssistantScope() != null ? msg.getAssistantScope() : "personal";
            InvokeRouteStrategy strategy = strategyMap.getOrDefault(scope, personalStrategy);
            strategy.route(msg);

            // personal strategy is no-op, cloudAgentService should NOT be called
            verify(cloudAgentService, never()).handleInvoke(any());
        }

        @Test
        @DisplayName("未知 scope 默认走 personal 策略")
        void shouldDefaultToPersonalForUnknownScope() {
            Map<String, InvokeRouteStrategy> strategyMap = Map.of(
                    "personal", personalStrategy,
                    "business", businessStrategy
            );

            GatewayMessage msg = GatewayMessage.builder()
                    .type(GatewayMessage.Type.INVOKE)
                    .ak("ak-001")
                    .assistantScope("unknown_scope")
                    .build();

            String scope = msg.getAssistantScope() != null ? msg.getAssistantScope() : "personal";
            InvokeRouteStrategy strategy = strategyMap.getOrDefault(scope, personalStrategy);
            strategy.route(msg);

            // personal strategy is no-op, cloudAgentService should NOT be called
            verify(cloudAgentService, never()).handleInvoke(any());
        }
    }
}
