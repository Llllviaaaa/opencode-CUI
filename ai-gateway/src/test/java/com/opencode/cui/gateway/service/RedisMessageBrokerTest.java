package com.opencode.cui.gateway.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

import java.time.Duration;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RedisMessageBrokerTest {

    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private RedisMessageListenerContainer listenerContainer;
    @Mock
    private ValueOperations<String, String> valueOperations;
    @Mock
    private SetOperations<String, String> setOperations;

    private RedisMessageBroker broker;

    @BeforeEach
    void setUp() {
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        lenient().when(redisTemplate.opsForSet()).thenReturn(setOperations);
        broker = new RedisMessageBroker(redisTemplate, listenerContainer, new ObjectMapper());
    }

    @Test
    @DisplayName("refreshSourceOwner stores source scoped owner key")
    void refreshSourceOwnerStoresSourceScopedOwnerKey() {
        Duration ttl = Duration.ofSeconds(30);

        broker.refreshSourceOwner("skill-server", "gateway-a", ttl);

        verify(valueOperations).set("gw:source:owner:skill-server:gateway-a", "alive", ttl);
        verify(setOperations).add("gw:source:owners:skill-server", "skill-server:gateway-a");
    }

    @Test
    @DisplayName("getActiveSourceOwners only returns active owners in the same source domain")
    void getActiveSourceOwnersOnlyReturnsActiveOwnersInSameSourceDomain() {
        when(setOperations.members("gw:source:owners:new-service"))
                .thenReturn(Set.of("new-service:gateway-a", "new-service:gateway-b", "skill-server:gateway-a", "invalid"));
        when(redisTemplate.hasKey(anyString())).thenAnswer(invocation ->
                "gw:source:owner:new-service:gateway-a".equals(invocation.getArgument(0)));

        Set<String> owners = broker.getActiveSourceOwners("new-service");

        assertEquals(Set.of("new-service:gateway-a"), owners);
        verify(setOperations).remove("gw:source:owners:new-service", "new-service:gateway-b");
        verify(setOperations).remove("gw:source:owners:new-service", "skill-server:gateway-a");
        verify(setOperations).remove("gw:source:owners:new-service", "invalid");
    }

    @Test
    @DisplayName("owner key helpers split source and instance id")
    void ownerKeyHelpersSplitSourceAndInstanceId() {
        assertEquals("new-service:gateway-b", RedisMessageBroker.sourceOwnerMember("new-service", "gateway-b"));
        assertEquals("new-service", RedisMessageBroker.sourceFromOwnerKey("new-service:gateway-b"));
        assertEquals("gateway-b", RedisMessageBroker.instanceIdFromOwnerKey("new-service:gateway-b"));
        assertFalse(broker.hasActiveSourceOwner(null, "gateway-b"));
        assertFalse(broker.hasActiveSourceOwner("new-service", null));
        assertNull(RedisMessageBroker.sourceFromOwnerKey("bad-key"));
        assertNull(RedisMessageBroker.instanceIdFromOwnerKey("bad-key"));
    }
}
