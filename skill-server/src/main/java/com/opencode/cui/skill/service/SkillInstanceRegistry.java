package com.opencode.cui.skill.service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicReference;

/**
 * SS 实例心跳注册器。
 *
 * <p>启动时写入 Redis 心跳 key {@code ss:internal:instance:{instanceId}}，
 * 并定时刷新 TTL，确保本实例在线期间 key 持续存活。
 * 用于 Task 2.6 失联 owner 探活：其他 SS 实例通过 {@link #isInstanceAlive} 判断
 * 目标实例是否仍然存活，决策是否接管其路由会话。
 *
 * <p>心跳 key TTL：30 秒；刷新间隔默认 10 秒（可通过配置项覆盖）。
 *
 * <p>调度时机：定时刷新由 {@link ApplicationReadyEvent} 触发后才注册到
 * {@link TaskScheduler}，确保所有 {@code @PostConstruct} 与同样监听
 * {@code ApplicationReadyEvent} 的 listener（包括
 * {@code GatewayMessageRouter.initSsRelaySubscription}）都已完成订阅，避免启动期
 * 内 {@code refreshHeartbeat} 在 relay channel 实际订阅就绪前发起伪自检触发
 * {@code forceReconnectListenerContainer} 的级联副作用。
 * 注意 {@code initSsRelaySubscription} 已迁移为 {@code @EventListener(ApplicationReadyEvent.class)}：
 * {@code RedisMessageListenerContainer} 是 {@code SmartLifecycle}，{@code @PostConstruct} 阶段
 * container 尚未 start，{@code addMessageListener} 不会真实把 SUBSCRIBE 发到 Redis。
 * 监听 {@code ApplicationReadyEvent} 之间默认无序，但本类首次执行延迟到
 * {@code now + interval}（10s）已经给 SUBSCRIBE settle 留出窗口，无需 {@code @Order}。
 *
 * <p>半死自愈：刷新心跳前，先用 {@link RedisMessageBroker#physicalSubscriberCount}
 * 检查本实例 {@code ss:relay:{instanceId}} 在 Redis 端的订阅数；若为 0（长连接
 * silent failed），调用 {@link RedisMessageBroker#forceReconnectListenerContainer}
 * 重建 subscription 连接。重连失败时跳过心跳写入，让 30s TTL 过期触发其他实例的
 * takeover 兜底。
 */
@Slf4j
@Component
public class SkillInstanceRegistry {

    /** 心跳 key TTL（秒）：比刷新间隔（10s）留足 3× 余量，避免误判失联。 */
    private static final int HEARTBEAT_TTL_SECONDS = 30;

    /**
     * 活实例花名册 ZSET 过期 cutoff（毫秒）；与 {@link #HEARTBEAT_TTL_SECONDS} 对齐。
     * <p>{@code listAliveInstances()} 用 {@code now - this} 作 ZRANGEBYSCORE 下界，
     * 即"最近 30s 内心跳过的实例"视为活实例。</p>
     */
    private static final long ALIVE_CUTOFF_MS = HEARTBEAT_TTL_SECONDS * 1000L;

    /**
     * 花名册 lazy GC 阈值（毫秒）；比 {@link #ALIVE_CUTOFF_MS} 留更长冗余，
     * 避免误删刚好在 cutoff 边界的实例条目（GC 比 cutoff 滞后一个心跳周期）。
     */
    private static final long ROSTER_GC_BEFORE_MS = 60_000L;

    /** SS relay channel 前缀，用于 pub/sub 自检。 */
    private static final String SS_RELAY_CHANNEL_PREFIX = "ss:relay:";

    /** 重连后等待物理订阅恢复的超时（毫秒）。 */
    private static final long RECONNECT_VERIFY_TIMEOUT_MS = 2000L;

    private final StringRedisTemplate redisTemplate;
    private final RedisMessageBroker redisMessageBroker;
    private final TaskScheduler taskScheduler;
    private final String instanceId;
    private final long refreshIntervalMs;

    /** ApplicationReadyEvent 触发后注册的调度任务句柄；@PreDestroy 时取消。 */
    private final AtomicReference<ScheduledFuture<?>> scheduledFutureRef = new AtomicReference<>();

    public SkillInstanceRegistry(StringRedisTemplate redisTemplate,
            RedisMessageBroker redisMessageBroker,
            TaskScheduler taskScheduler,
            @Value("${HOSTNAME:skill-server-local}") String instanceId,
            @Value("${skill.instance-registry.refresh-interval-ms:10000}") long refreshIntervalMs) {
        this.redisTemplate = redisTemplate;
        this.redisMessageBroker = redisMessageBroker;
        this.taskScheduler = taskScheduler;
        this.instanceId = instanceId;
        this.refreshIntervalMs = refreshIntervalMs;
    }

    /**
     * 启动时注册实例心跳。
     * 写入 Redis key 并记录 ENTRY 日志。
     *
     * <p>注意：仅写初始心跳（保护 pod 启动 30s 窗口内不被判死），不在此处注册定时
     * 调度。调度由 {@link #startScheduling(ApplicationReadyEvent)} 在所有 bean 就绪
     * 后触发。
     */
    @PostConstruct
    public void register() {
        writeHeartbeat();
        log.info("[ENTRY] SkillInstanceRegistry.register: instanceId={}", instanceId);
    }

    /**
     * 在 {@link ApplicationReadyEvent} 触发后注册定时心跳刷新任务。
     *
     * <p>此时所有 {@code @PostConstruct} 已完成，{@code ss:relay:*} 订阅已加入
     * Spring 端 listener mapping，但 Lettuce 实际向 Redis 发送 SUBSCRIBE 命令是异步
     * 的，存在毫秒级 settle 窗口。因此首次执行时间设为 {@code now + interval}，
     * 给 Lettuce 长连接 SUBSCRIBE 留出时间窗口，避免首轮自检看到伪 0 订阅。</p>
     *
     * <p>注意：Spring {@link TaskScheduler#scheduleWithFixedDelay(Runnable, Duration)}
     * 的默认实现等价于 {@code scheduleWithFixedDelay(task, Instant.now(), delay)}，
     * 即首次执行 {@code initialDelay = 0}，**会立即触发**。这里显式用三参数版本
     * 把首次时间推迟到 {@code now + interval}。</p>
     */
    @EventListener(ApplicationReadyEvent.class)
    public void startScheduling(ApplicationReadyEvent event) {
        Duration interval = Duration.ofMillis(refreshIntervalMs);
        Instant firstRun = Instant.now().plus(interval);
        ScheduledFuture<?> future = taskScheduler.scheduleWithFixedDelay(
                this::refreshHeartbeat, firstRun, interval);
        ScheduledFuture<?> previous = scheduledFutureRef.getAndSet(future);
        if (previous != null) {
            // ApplicationReadyEvent 理论只发一次；防御性兜底，避免重复调度泄漏。
            previous.cancel(false);
        }
        log.info("[ENTRY] SkillInstanceRegistry.startScheduling: instanceId={}, intervalMs={}, firstRunAt={}",
                instanceId, refreshIntervalMs, firstRun);
    }

    /**
     * 定时刷新心跳，防止 key 过期导致误判失联。
     *
     * <p>续心跳前先做 pub/sub 自检；若本实例 relay channel 在 Redis 端订阅数为 0，
     * 调用 broker 强制重连。重连失败则跳过本轮心跳，让 30s TTL 过期，由其他实例
     * 通过 {@link #isInstanceAlive} 判活并触发 takeover 兜底。</p>
     */
    public void refreshHeartbeat() {
        if (!verifyOwnSubscriptionAlive()) {
            log.error("Self-check failed: own relay channel has 0 subscribers, attempting reconnect: instanceId={}",
                    instanceId);
            boolean recovered = redisMessageBroker.forceReconnectListenerContainer(
                    SS_RELAY_CHANNEL_PREFIX + instanceId, RECONNECT_VERIFY_TIMEOUT_MS);
            if (!recovered) {
                log.error("Reconnect failed, skipping heartbeat to trigger takeover: instanceId={}", instanceId);
                return; // 不写心跳 → 30s TTL 过期 → 其他实例 isInstanceAlive=false → takeover 兜底
            }
            log.info("Self-healed via reconnect: instanceId={}", instanceId);
        }
        writeHeartbeat();
        log.info("[ENTRY] SkillInstanceRegistry.refreshHeartbeat: instanceId={}", instanceId);
    }

    /**
     * 服务关闭时主动删除心跳 key，加速其他实例感知本实例下线。
     * 同时取消调度任务，防止已停止的 bean 仍被线程池调用。
     */
    @PreDestroy
    public void destroy() {
        ScheduledFuture<?> future = scheduledFutureRef.getAndSet(null);
        if (future != null) {
            future.cancel(false);
        }
        redisTemplate.delete(redisKey());
        // 从活实例花名册移除自己，加速其他实例 L2 投递感知本实例下线（graceful 路径）
        redisMessageBroker.removeFromInstanceRoster(instanceId);
        log.info("[EXIT] SkillInstanceRegistry.destroy: instanceId={}", instanceId);
    }

    /**
     * 探测目标实例是否存活。
     *
     * <p>通过检查 Redis 心跳 key 是否存在来判断目标实例在线状态。
     * 用于 Task 2.6 失联 owner 探活逻辑。
     *
     * @param targetInstanceId 目标 SS 实例 ID
     * @return {@code true} 表示目标实例心跳 key 存在（实例在线）；{@code false} 表示已失联
     */
    public boolean isInstanceAlive(String targetInstanceId) {
        return Boolean.TRUE.equals(redisTemplate.hasKey("ss:internal:instance:" + targetInstanceId));
    }

    /**
     * 列出当前所有活实例（基于 {@code instance:roster} ZSET）。
     *
     * <p>cutoff = {@code now - 30s}（与 {@link #HEARTBEAT_TTL_SECONDS} 一致）：
     * 任何最近 30s 内心跳过的实例视为活实例。</p>
     *
     * <p>用于 L2 投递候选枚举（替代旧的"共享 hash 枚举字段"），符合"禁用 SCAN/KEYS"约束。</p>
     *
     * @return 活实例 ID 列表；Redis 异常时返回空列表（调用方走 L3 降级）
     */
    public List<String> listAliveInstances() {
        long cutoff = System.currentTimeMillis() - ALIVE_CUTOFF_MS;
        return redisMessageBroker.rangeAliveInstances(cutoff);
    }

    /**
     * 返回本实例 ID。
     *
     * @return 实例 ID，来源于环境变量 {@code HOSTNAME}，本地开发默认 {@code skill-server-local}
     */
    public String getInstanceId() {
        return instanceId;
    }

    // ==================== 私有方法 ====================

    /**
     * 通过 PUBSUB NUMSUB 校验本实例 relay channel 在 Redis 端的真实订阅数 >0。
     * 这是 Redis server 端真值，能捕获 Spring 侧 {@code activeListeners} map 检测
     * 不到的长连接 silent failure。
     */
    private boolean verifyOwnSubscriptionAlive() {
        String channel = SS_RELAY_CHANNEL_PREFIX + instanceId;
        return redisMessageBroker.physicalSubscriberCount(channel) > 0L;
    }

    private void writeHeartbeat() {
        redisTemplate.opsForValue().set(redisKey(), "alive", Duration.ofSeconds(HEARTBEAT_TTL_SECONDS));
        // 花名册 ZADD + lazy GC：每次心跳顺手维护，避免独立调度
        long now = System.currentTimeMillis();
        redisMessageBroker.addToInstanceRoster(instanceId, now);
        redisMessageBroker.pruneRoster(now - ROSTER_GC_BEFORE_MS);
    }

    private String redisKey() {
        return "ss:internal:instance:" + instanceId;
    }
}
