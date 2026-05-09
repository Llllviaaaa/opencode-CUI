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
 * {@link TaskScheduler}，确保所有 {@code @PostConstruct}（包括
 * {@code GatewayMessageRouter.initSsRelaySubscription}）都已完成订阅，避免启动期
 * 内 {@code refreshHeartbeat} 在 relay channel 实际订阅就绪前发起伪自检触发
 * {@code forceReconnectListenerContainer} 的级联副作用。
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
     * <p>此时所有 {@code @PostConstruct} 已完成，{@code ss:relay:*} 订阅已就绪，
     * 后续 {@link #refreshHeartbeat} 的自检不会因启动顺序产生伪 0 订阅。</p>
     */
    @EventListener(ApplicationReadyEvent.class)
    public void startScheduling(ApplicationReadyEvent event) {
        Duration interval = Duration.ofMillis(refreshIntervalMs);
        ScheduledFuture<?> future = taskScheduler.scheduleWithFixedDelay(this::refreshHeartbeat, interval);
        ScheduledFuture<?> previous = scheduledFutureRef.getAndSet(future);
        if (previous != null) {
            // ApplicationReadyEvent 理论只发一次；防御性兜底，避免重复调度泄漏。
            previous.cancel(false);
        }
        log.info("[ENTRY] SkillInstanceRegistry.startScheduling: instanceId={}, intervalMs={}",
                instanceId, refreshIntervalMs);
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
    }

    private String redisKey() {
        return "ss:internal:instance:" + instanceId;
    }
}
