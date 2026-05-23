package com.opencode.cui.skill.service;

import com.opencode.cui.skill.config.DeliveryProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * ExternalWsRegistry 单元测试（owner-only writes 架构）：
 * <ul>
 *   <li>register/unregister 落到本实例自管 held-by hash</li>
 *   <li>heartbeatBatch: snapshot 非空走 putAll；空走 DEL</li>
 *   <li>findInstancesWithConnection: 活实例花名册过滤 + pipelined HGET + count>0 返回候选列表</li>
 *   <li>关键 fix：roster 不含的死实例自动被跳过（不会再被选中投递）</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class ExternalWsRegistryTest {

    @Mock private RedisMessageBroker redisMessageBroker;
    @Mock private SkillInstanceRegistry instanceRegistry;
    @Mock private DeliveryProperties deliveryProperties;
    @InjectMocks private ExternalWsRegistry registry;

    @Test
    @DisplayName("register: putAll 写入 held-by:{selfId} 的单 domain 字段 + TTL")
    void register_writesHeldByHash() {
        when(instanceRegistry.getInstanceId()).thenReturn("ss-pod-1");
        when(deliveryProperties.getRegistryTtlSeconds()).thenReturn(30);
        registry.register("im", 3);
        verify(redisMessageBroker).heldByPutAll("ss-pod-1", Map.of("im", 3), 30);
    }

    @Test
    @DisplayName("register: blank domain 不触达 Redis")
    void register_blankDomainIsNoOp() {
        registry.register("", 3);
        registry.register(null, 3);
        verifyNoInteractions(redisMessageBroker);
    }

    @Test
    @DisplayName("unregister: 删除 held-by:{selfId} 的 domain 字段")
    void unregister_deletesField() {
        when(instanceRegistry.getInstanceId()).thenReturn("ss-pod-1");
        registry.unregister("im");
        verify(redisMessageBroker).heldByDeleteField("ss-pod-1", "im");
    }

    @Test
    @DisplayName("heartbeatBatch: snapshot 非空 → putAll + EXPIRE 整批续命")
    void heartbeatBatch_nonEmpty_putAll() {
        when(instanceRegistry.getInstanceId()).thenReturn("ss-pod-1");
        when(deliveryProperties.getRegistryTtlSeconds()).thenReturn(30);
        Map<String, Integer> snapshot = Map.of("im", 2, "crm", 1);
        registry.heartbeatBatch(snapshot);
        verify(redisMessageBroker).heldByPutAll("ss-pod-1", snapshot, 30);
        verify(redisMessageBroker, never()).heldByDeleteKey(anyString());
    }

    @Test
    @DisplayName("heartbeatBatch: snapshot 为空 → DEL key（避免残留字段）")
    void heartbeatBatch_empty_deletesKey() {
        when(instanceRegistry.getInstanceId()).thenReturn("ss-pod-1");
        registry.heartbeatBatch(Map.of());
        verify(redisMessageBroker).heldByDeleteKey("ss-pod-1");
        verify(redisMessageBroker, never()).heldByPutAll(anyString(), anyMap(), anyInt());
    }

    @Test
    @DisplayName("heartbeatBatch: null snapshot 视为空，DEL key")
    void heartbeatBatch_null_deletesKey() {
        when(instanceRegistry.getInstanceId()).thenReturn("ss-pod-1");
        registry.heartbeatBatch(null);
        verify(redisMessageBroker).heldByDeleteKey("ss-pod-1");
    }

    @Test
    @DisplayName("clearOnShutdown: DEL held-by key（@PreDestroy graceful 路径）")
    void clearOnShutdown_deletesKey() {
        when(instanceRegistry.getInstanceId()).thenReturn("ss-pod-1");
        registry.clearOnShutdown();
        verify(redisMessageBroker).heldByDeleteKey("ss-pod-1");
    }

    // ==================== findInstancesWithConnection ====================

    @Test
    @DisplayName("findInstancesWithConnection: 活实例 + 持有该 domain → 按 roster 顺序返回所有 count>0")
    void findInstances_aliveWithDomain_returnsAllCandidates() {
        when(instanceRegistry.getInstanceId()).thenReturn("ss-pod-1");
        when(instanceRegistry.listAliveInstances())
                .thenReturn(List.of("ss-pod-1", "ss-pod-2", "ss-pod-3", "ss-pod-4"));
        Map<String, Integer> counts = new LinkedHashMap<>();
        counts.put("ss-pod-2", 0);
        counts.put("ss-pod-3", 2);
        counts.put("ss-pod-4", 1);
        when(redisMessageBroker.heldByGetBatch(List.of("ss-pod-2", "ss-pod-3", "ss-pod-4"), "im"))
                .thenReturn(counts);

        List<String> targets = registry.findInstancesWithConnection("im");

        assertEquals(List.of("ss-pod-3", "ss-pod-4"), targets);
    }

    @Test
    @DisplayName("findInstancesWithConnection: blank domain → empty")
    void findInstances_blankDomain() {
        assertTrue(registry.findInstancesWithConnection("").isEmpty());
        assertTrue(registry.findInstancesWithConnection(null).isEmpty());
        verifyNoInteractions(redisMessageBroker, instanceRegistry);
    }

    // ==================== findInstanceWithConnection ====================

    @Test
    @DisplayName("findInstanceWithConnection: 活实例 + 持有该 domain → 命中第一个 count>0")
    void findInstance_aliveWithDomain_returnsFirst() {
        when(instanceRegistry.getInstanceId()).thenReturn("ss-pod-1");
        when(instanceRegistry.listAliveInstances())
                .thenReturn(List.of("ss-pod-1", "ss-pod-2", "ss-pod-3"));
        Map<String, Integer> counts = new LinkedHashMap<>();
        counts.put("ss-pod-2", 0);  // 持有过但当前断开
        counts.put("ss-pod-3", 2);  // 命中
        when(redisMessageBroker.heldByGetBatch(List.of("ss-pod-2", "ss-pod-3"), "im"))
                .thenReturn(counts);

        String target = registry.findInstanceWithConnection("im");
        assertEquals("ss-pod-3", target);
    }

    @Test
    @DisplayName("findInstanceWithConnection: 死实例（不在 roster）自动被跳过 [关键 fix]")
    void findInstance_deadInstanceSkipped() {
        when(instanceRegistry.getInstanceId()).thenReturn("ss-pod-1");
        // roster 只有 self + 一个活实例；曾经持有连接的 dead-pod 不在花名册
        when(instanceRegistry.listAliveInstances())
                .thenReturn(List.of("ss-pod-1", "ss-pod-2"));
        // 即使 held-by:dead-pod 还残留 domain，因为 dead-pod 不在 candidates 里，
        // heldByGetBatch 根本不会查它 → 永远不会被选中投递
        when(redisMessageBroker.heldByGetBatch(List.of("ss-pod-2"), "im"))
                .thenReturn(Map.of()); // ss-pod-2 没持有 im
        assertNull(registry.findInstanceWithConnection("im"));
        // 确保没有把 dead-pod 当候选
        verify(redisMessageBroker).heldByGetBatch(List.of("ss-pod-2"), "im");
    }

    @Test
    @DisplayName("findInstanceWithConnection: roster 有但 held-by 没该 domain → 跳过")
    void findInstance_aliveButNoDomain_skipped() {
        when(instanceRegistry.getInstanceId()).thenReturn("ss-pod-1");
        when(instanceRegistry.listAliveInstances())
                .thenReturn(List.of("ss-pod-1", "ss-pod-2"));
        when(redisMessageBroker.heldByGetBatch(List.of("ss-pod-2"), "im"))
                .thenReturn(Map.of()); // pipeline HGET 返回 null → 不进结果
        assertNull(registry.findInstanceWithConnection("im"));
    }

    @Test
    @DisplayName("findInstanceWithConnection: roster 全是自己 → 返回 null")
    void findInstance_onlySelfInRoster() {
        when(instanceRegistry.getInstanceId()).thenReturn("ss-pod-1");
        when(instanceRegistry.listAliveInstances()).thenReturn(List.of("ss-pod-1"));
        assertNull(registry.findInstanceWithConnection("im"));
        verifyNoInteractions(redisMessageBroker);
    }

    @Test
    @DisplayName("findInstanceWithConnection: roster 为空 → 返回 null（L3 降级）")
    void findInstance_emptyRoster() {
        when(instanceRegistry.listAliveInstances()).thenReturn(List.of());
        assertNull(registry.findInstanceWithConnection("im"));
        verifyNoInteractions(redisMessageBroker);
    }

    @Test
    @DisplayName("findInstanceWithConnection: count<=0 候选淘汰")
    void findInstance_countZero_skipped() {
        when(instanceRegistry.getInstanceId()).thenReturn("ss-pod-1");
        when(instanceRegistry.listAliveInstances())
                .thenReturn(List.of("ss-pod-1", "ss-pod-2"));
        when(redisMessageBroker.heldByGetBatch(List.of("ss-pod-2"), "im"))
                .thenReturn(Map.of("ss-pod-2", 0));
        assertNull(registry.findInstanceWithConnection("im"));
    }

    @Test
    @DisplayName("findInstanceWithConnection: blank domain → null")
    void findInstance_blankDomain() {
        assertNull(registry.findInstanceWithConnection(""));
        assertNull(registry.findInstanceWithConnection(null));
        verifyNoInteractions(redisMessageBroker, instanceRegistry);
    }
}
