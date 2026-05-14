package com.opencode.cui.skill.service;

import com.opencode.cui.skill.config.DeliveryProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * WS 连接注册表服务。
 *
 * <p>架构（owner-only writes，2026-05-14 重构）：每实例只写自己的
 * {@code external-ws:held-by:{selfId}} HASH，不再共享 {@code external-ws:registry:{domain}}。
 * 投递时通过 {@link SkillInstanceRegistry#listAliveInstances()} 拿活实例花名册，
 * 再 pipelined HGET 每个候选的 held-by hash。</p>
 *
 * <p>这样消除了旧实现的核心 bug：旧的"共享 hash + 任一活实例 EXPIRE 续整个 key"
 * 会让死实例字段被无限期续命，pre-7.4 Redis 没有字段级 TTL 无法补救。</p>
 */
@Slf4j
@Service
public class ExternalWsRegistry {

    private final RedisMessageBroker redisMessageBroker;
    private final SkillInstanceRegistry instanceRegistry;
    private final DeliveryProperties deliveryProperties;

    public ExternalWsRegistry(RedisMessageBroker redisMessageBroker,
                               SkillInstanceRegistry instanceRegistry,
                               DeliveryProperties deliveryProperties) {
        this.redisMessageBroker = redisMessageBroker;
        this.instanceRegistry = instanceRegistry;
        this.deliveryProperties = deliveryProperties;
    }

    /**
     * 注册（或更新）本实例持有的某个 domain 的 connectionCount。
     * 写入 {@code external-ws:held-by:{selfId}} 的 {@code {domain → count}} 字段并续 TTL。
     */
    public void register(String domain, int connectionCount) {
        if (domain == null || domain.isBlank()) {
            return;
        }
        redisMessageBroker.heldByPutAll(
                instanceRegistry.getInstanceId(),
                Map.of(domain, connectionCount),
                deliveryProperties.getRegistryTtlSeconds());
    }

    /** 注销本实例在指定 domain 的连接（删除 hash field）。 */
    public void unregister(String domain) {
        redisMessageBroker.heldByDeleteField(instanceRegistry.getInstanceId(), domain);
    }

    /**
     * 批量心跳：用本地 snapshot 一次性 putAll + EXPIRE，或当 snapshot 为空时 DEL key。
     *
     * @param snapshot {@code {domain → count}}；empty 表示本实例不再持有任何 WS，应删 key
     */
    public void heartbeatBatch(Map<String, Integer> snapshot) {
        String selfId = instanceRegistry.getInstanceId();
        if (snapshot == null || snapshot.isEmpty()) {
            redisMessageBroker.heldByDeleteKey(selfId);
            return;
        }
        redisMessageBroker.heldByPutAll(selfId, snapshot, deliveryProperties.getRegistryTtlSeconds());
    }

    /** @PreDestroy 路径：删除本实例 held-by key，加速其他实例感知。 */
    public void clearOnShutdown() {
        redisMessageBroker.heldByDeleteKey(instanceRegistry.getInstanceId());
    }

    /**
     * 查找一台持有指定 domain WS 连接的远程 SS 实例。
     *
     * <p>步骤：
     * <ol>
     *   <li>{@code instanceRegistry.listAliveInstances()} 拿活实例花名册（ZRANGEBYSCORE）</li>
     *   <li>排除 selfId</li>
     *   <li>pipeline HGET 每个候选的 {@code external-ws:held-by:{id}} 的 {domain} 字段</li>
     *   <li>返回第一个 count > 0 的远程实例 ID</li>
     * </ol>
     *
     * <p>死实例不在花名册中 → 直接被跳过，不会再被选中投递（fix 本任务核心 bug）。</p>
     *
     * @return 命中的远程实例 ID；无候选返回 null
     */
    public String findInstanceWithConnection(String domain) {
        if (domain == null || domain.isBlank()) {
            return null;
        }
        String selfId = instanceRegistry.getInstanceId();
        List<String> alive = instanceRegistry.listAliveInstances();
        if (alive == null || alive.isEmpty()) {
            return null;
        }
        List<String> candidates = new ArrayList<>(alive.size());
        for (String id : alive) {
            if (id != null && !id.equals(selfId)) {
                candidates.add(id);
            }
        }
        if (candidates.isEmpty()) {
            return null;
        }
        Map<String, Integer> counts = redisMessageBroker.heldByGetBatch(candidates, domain);
        if (counts == null || counts.isEmpty()) {
            return null;
        }
        // 按 alive 列表的顺序选第一个 count > 0 的（与旧实现"任选一推"语义一致）
        for (String id : candidates) {
            Integer count = counts.get(id);
            if (count != null && count > 0) {
                return id;
            }
        }
        return null;
    }

    /** 暴露给外部的"是否可获取活实例列表"探测（用于诊断日志）。 */
    @SuppressWarnings("unused")
    private List<String> debugSnapshotAlive() {
        List<String> alive = instanceRegistry.listAliveInstances();
        return alive == null ? Collections.emptyList() : alive;
    }
}
