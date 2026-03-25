package com.opencode.cui.gateway.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for the internal agent registry (gw:internal:agent:{ak}) methods
 * in RedisMessageBroker.
 *
 * These keys are used for intra-GW routing: locating which GW instance holds
 * a given Agent's WebSocket connection, without involving the Skill Server.
 */
@ExtendWith(MockitoExtension.class)
class InternalAgentRegistryTest {

    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private RedisMessageListenerContainer listenerContainer;
    @Mock
    private ValueOperations<String, String> valueOperations;

    private RedisMessageBroker broker;

    @BeforeEach
    void setUp() {
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        broker = new RedisMessageBroker(redisTemplate, listenerContainer, new ObjectMapper());
    }

    @Nested
    @DisplayName("bindInternalAgent")
    class BindInternalAgentTests {

        @Test
        @DisplayName("writes key gw:internal:agent:{ak} with instanceId and TTL")
        void writesKeyWithInstanceIdAndTtl() {
            broker.bindInternalAgent("agent-001", "gw-az1-1", Duration.ofSeconds(120));

            verify(valueOperations).set("gw:internal:agent:agent-001", "gw-az1-1", Duration.ofSeconds(120));
        }

        @Test
        @DisplayName("does nothing for null ak")
        void ignoresNullAk() {
            broker.bindInternalAgent(null, "gw-az1-1", Duration.ofSeconds(120));

            verify(valueOperations, never()).set(
                    org.mockito.ArgumentMatchers.anyString(),
                    org.mockito.ArgumentMatchers.anyString(),
                    org.mockito.ArgumentMatchers.any(Duration.class));
        }

        @Test
        @DisplayName("does nothing for blank ak")
        void ignoresBlankAk() {
            broker.bindInternalAgent("  ", "gw-az1-1", Duration.ofSeconds(120));

            verify(valueOperations, never()).set(
                    org.mockito.ArgumentMatchers.anyString(),
                    org.mockito.ArgumentMatchers.anyString(),
                    org.mockito.ArgumentMatchers.any(Duration.class));
        }

        @Test
        @DisplayName("does nothing for null instanceId")
        void ignoresNullInstanceId() {
            broker.bindInternalAgent("agent-001", null, Duration.ofSeconds(120));

            verify(valueOperations, never()).set(
                    org.mockito.ArgumentMatchers.anyString(),
                    org.mockito.ArgumentMatchers.anyString(),
                    org.mockito.ArgumentMatchers.any(Duration.class));
        }

        @Test
        @DisplayName("does nothing for blank instanceId")
        void ignoresBlankInstanceId() {
            broker.bindInternalAgent("agent-001", "", Duration.ofSeconds(120));

            verify(valueOperations, never()).set(
                    org.mockito.ArgumentMatchers.anyString(),
                    org.mockito.ArgumentMatchers.anyString(),
                    org.mockito.ArgumentMatchers.any(Duration.class));
        }
    }

    @Nested
    @DisplayName("getInternalAgentInstance")
    class GetInternalAgentInstanceTests {

        @Test
        @DisplayName("returns stored instanceId")
        void returnsStoredInstanceId() {
            when(valueOperations.get("gw:internal:agent:agent-001")).thenReturn("gw-az1-1");

            assertEquals("gw-az1-1", broker.getInternalAgentInstance("agent-001"));
        }

        @Test
        @DisplayName("returns null when key does not exist")
        void returnsNullWhenKeyAbsent() {
            when(valueOperations.get("gw:internal:agent:agent-404")).thenReturn(null);

            assertNull(broker.getInternalAgentInstance("agent-404"));
        }

        @Test
        @DisplayName("returns null for null ak without calling Redis")
        void returnsNullForNullAk() {
            assertNull(broker.getInternalAgentInstance(null));

            verify(valueOperations, never()).get(org.mockito.ArgumentMatchers.anyString());
        }

        @Test
        @DisplayName("returns null for blank ak without calling Redis")
        void returnsNullForBlankAk() {
            assertNull(broker.getInternalAgentInstance(""));

            verify(valueOperations, never()).get(org.mockito.ArgumentMatchers.anyString());
        }
    }

    @Nested
    @DisplayName("removeInternalAgent")
    class RemoveInternalAgentTests {

        @Test
        @DisplayName("deletes key gw:internal:agent:{ak}")
        void deletesKey() {
            broker.removeInternalAgent("agent-001");

            verify(redisTemplate).delete("gw:internal:agent:agent-001");
        }

        @Test
        @DisplayName("does nothing for null ak")
        void ignoresNullAk() {
            broker.removeInternalAgent(null);

            verify(redisTemplate, never()).delete(org.mockito.ArgumentMatchers.anyString());
        }

        @Test
        @DisplayName("does nothing for blank ak")
        void ignoresBlankAk() {
            broker.removeInternalAgent("");

            verify(redisTemplate, never()).delete(org.mockito.ArgumentMatchers.anyString());
        }
    }

    @Nested
    @DisplayName("refreshInternalAgentTtl")
    class RefreshInternalAgentTtlTests {

        @Test
        @DisplayName("refreshes TTL for key gw:internal:agent:{ak}")
        void refreshesTtl() {
            broker.refreshInternalAgentTtl("agent-001", Duration.ofSeconds(120));

            verify(redisTemplate).expire("gw:internal:agent:agent-001", Duration.ofSeconds(120));
        }

        @Test
        @DisplayName("does nothing for null ak")
        void ignoresNullAk() {
            broker.refreshInternalAgentTtl(null, Duration.ofSeconds(120));

            verify(redisTemplate, never()).expire(
                    org.mockito.ArgumentMatchers.anyString(),
                    org.mockito.ArgumentMatchers.any(Duration.class));
        }

        @Test
        @DisplayName("does nothing for blank ak")
        void ignoresBlankAk() {
            broker.refreshInternalAgentTtl("", Duration.ofSeconds(120));

            verify(redisTemplate, never()).expire(
                    org.mockito.ArgumentMatchers.anyString(),
                    org.mockito.ArgumentMatchers.any(Duration.class));
        }
    }
}
