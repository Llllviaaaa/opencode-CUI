package com.opencode.cui.skill.service;

import com.opencode.cui.skill.logging.MdcConstants;
import com.opencode.cui.skill.logging.MdcHelper;
import io.lettuce.core.RedisFuture;
import io.lettuce.core.cluster.api.async.RedisClusterAsyncCommands;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.MDC;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * RedisMessageBroker 单元测试：toolSessionId 反查缓存失效 + pub/sub silent-failure 自愈。
 *
 * <p>{@code physicalSubscriberCount_*} 测试 mock 深到 Lettuce {@code pubsubNumsub} 这一层。
 * 之所以 mock {@code connectionFactory.getConnection()} 而不是 {@code redisTemplate.execute(callback)}：
 * 后者默认通过 {@code CloseSuppressingInvocationHandler} 把 RedisConnection 包成 JDK 动态代理
 * （实现 RedisConnection interface，但**不是** {@link LettuceConnection} 类的实例），
 * 导致生产代码里的 {@code instanceof LettuceConnection} cast guard 永远 false → 早 return 0L。
 * 现在 {@code physicalSubscriberCount} 直接走
 * {@code redisTemplate.getRequiredConnectionFactory().getConnection()} 拿 raw {@link LettuceConnection}，
 * 测试 mock 路径必须与之对齐，cast 才能真实通过、覆盖到 {@code pubsubNumsub} 真实路径。
 */
@ExtendWith(MockitoExtension.class)
class RedisMessageBrokerTest {

    private static final String TOOL_SESSION_PREFIX = "ss:tool-session:";
    private static final String VERIFY_CHANNEL = "ss:relay:ss-az1-test";

    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private RedisMessageListenerContainer listenerContainer;
    @Mock
    private RedisConnectionFactory connectionFactory;
    @Mock
    private LettuceConnection lettuceConnection;
    /**
     * 与 {@link LettuceConnection#getNativeConnection()} 实际返回类型对齐
     * （{@link RedisClusterAsyncCommands} extends {@code BaseRedisAsyncCommands}）。
     */
    @Mock
    private RedisClusterAsyncCommands<byte[], byte[]> asyncCommands;
    @Mock
    @SuppressWarnings("rawtypes")
    private RedisFuture pubsubNumsubFuture;

    private RedisMessageBroker broker;
    private final AtomicReference<MessageListener> activeListenerRef = new AtomicReference<>();
    private final AtomicInteger relayHandlerInvocations = new AtomicInteger();

    @BeforeEach
    void setUp() {
        broker = new RedisMessageBroker(redisTemplate, listenerContainer);
        activeListenerRef.set(null);
        relayHandlerInvocations.set(0);
    }

    @AfterEach
    void tearDown() {
        MdcHelper.clearAll();
    }

    @Test
    @DisplayName("deleteToolSessionMapping 正常 key 调用 redisTemplate.delete(prefix+key) 一次")
    void deleteToolSessionMappingDeletesPrefixedKey() {
        broker.deleteToolSessionMapping("T1");

        verify(redisTemplate).delete(TOOL_SESSION_PREFIX + "T1");
    }

    @Test
    @DisplayName("deleteToolSessionMapping null/blank → no-op，不触达 redisTemplate")
    void deleteToolSessionMappingNullOrBlankIsNoOp() {
        broker.deleteToolSessionMapping(null);
        broker.deleteToolSessionMapping("");
        broker.deleteToolSessionMapping("   ");

        verify(redisTemplate, never()).delete(org.mockito.ArgumentMatchers.anyString());
        verifyNoInteractions(listenerContainer);
    }

    // ==================== forceReconnectListenerContainer ====================

    @Test
    @DisplayName("forceReconnectListenerContainer 优先重新订阅单个 channel，成功时不重启 container")
    void forceReconnectListenerContainer_success_shouldResubscribeChannelWithoutRestart() {
        seedActiveRelayListener();
        stubProbeLoopback();

        boolean ok = broker.forceReconnectListenerContainer(VERIFY_CHANNEL, 1000L);

        assertTrue(ok);
        InOrder order = inOrder(listenerContainer);
        order.verify(listenerContainer).removeMessageListener(any(), any(ChannelTopic.class));
        order.verify(listenerContainer).addMessageListener(any(), any(ChannelTopic.class));
        verify(listenerContainer, never()).stop();
        verify(listenerContainer, never()).start();
    }

    @Test
    @DisplayName("forceReconnectListenerContainer 单通道重订阅失败时不重启共享 container")
    void forceReconnectListenerContainer_resubscribeFails_shouldNotRestartSharedContainer() {
        seedActiveRelayListener();
        org.mockito.Mockito.doThrow(new RuntimeException("resubscribe failed"))
                .when(listenerContainer).addMessageListener(any(), any(ChannelTopic.class));

        boolean ok = broker.forceReconnectListenerContainer(VERIFY_CHANNEL, 1000L);

        assertFalse(ok);
        InOrder order = inOrder(listenerContainer);
        order.verify(listenerContainer).removeMessageListener(any(), any(ChannelTopic.class));
        order.verify(listenerContainer).addMessageListener(any(), any(ChannelTopic.class));
        verify(listenerContainer, never()).stop();
        verify(listenerContainer, never()).start();
    }

    @Test
    @DisplayName("forceReconnectListenerContainer remove 异常 → 返回 false 且不重启 container")
    void forceReconnectListenerContainer_removeThrows_shouldReturnFalseWithoutRestart() {
        seedActiveRelayListener();
        org.mockito.Mockito.doThrow(new RuntimeException("resubscribe failed"))
                .when(listenerContainer).removeMessageListener(any(), any(ChannelTopic.class));

        boolean ok = broker.forceReconnectListenerContainer(VERIFY_CHANNEL, 100L);

        assertFalse(ok);
        verify(listenerContainer).removeMessageListener(any(), any(ChannelTopic.class));
        verify(listenerContainer, never()).addMessageListener(any(), any(ChannelTopic.class));
        verify(listenerContainer, never()).stop();
        verify(listenerContainer, never()).start();
    }

    @Test
    @DisplayName("forceReconnectListenerContainer 重订阅超时 → 返回 false 且不重启 container")
    void forceReconnectListenerContainer_resubscribeTimeout_shouldReturnFalseWithoutRestart() {
        seedActiveRelayListener();
        when(redisTemplate.convertAndSend(eq(VERIFY_CHANNEL), anyString())).thenReturn(0L);

        boolean ok = broker.forceReconnectListenerContainer(VERIFY_CHANNEL, 100L);

        assertFalse(ok);
        InOrder order = inOrder(listenerContainer);
        order.verify(listenerContainer).removeMessageListener(any(), any(ChannelTopic.class));
        order.verify(listenerContainer).addMessageListener(any(), any(ChannelTopic.class));
        verify(listenerContainer, never()).stop();
        verify(listenerContainer, never()).start();
    }

    @Test
    @DisplayName("forceReconnectListenerContainer 重连中重入时不重复重订阅")
    void forceReconnectListenerContainer_reentrant_shouldNotResubscribeSecondCycle() {
        seedActiveRelayListener();
        when(redisTemplate.convertAndSend(eq(VERIFY_CHANNEL), anyString())).thenReturn(0L);
        org.mockito.Mockito.doAnswer(invocation -> {
            boolean nested = broker.forceReconnectListenerContainer(VERIFY_CHANNEL, 0L);
            assertFalse(nested);
            return null;
        }).when(listenerContainer).addMessageListener(any(), any(ChannelTopic.class));

        boolean ok = broker.forceReconnectListenerContainer(VERIFY_CHANNEL, 0L);

        assertFalse(ok);
        verify(listenerContainer, times(1)).removeMessageListener(any(), any(ChannelTopic.class));
        verify(listenerContainer, times(1)).addMessageListener(any(), any(ChannelTopic.class));
        verify(listenerContainer, never()).stop();
        verify(listenerContainer, never()).start();
    }

    @Test
    @DisplayName("verifySubscriptionDelivery 通过 loopback probe 确认真实投递，probe 不进入业务 handler")
    void verifySubscriptionDelivery_probeAck_shouldReturnTrueAndSkipBusinessHandler() {
        seedActiveRelayListener();
        stubProbeLoopback();

        boolean ok = broker.verifySubscriptionDelivery(VERIFY_CHANNEL, 1000L);

        assertTrue(ok);
        assertEquals(0, relayHandlerInvocations.get());
        verify(redisTemplate).convertAndSend(eq(VERIFY_CHANNEL), anyString());
    }

    @Test
    @DisplayName("verifySubscriptionDelivery probe 超时返回 false")
    void verifySubscriptionDelivery_probeTimeout_shouldReturnFalse() {
        seedActiveRelayListener();
        when(redisTemplate.convertAndSend(eq(VERIFY_CHANNEL), anyString())).thenReturn(0L);

        boolean ok = broker.verifySubscriptionDelivery(VERIFY_CHANNEL, 10L);

        assertFalse(ok);
        verify(redisTemplate).convertAndSend(eq(VERIFY_CHANNEL), anyString());
    }

    @Test
    @DisplayName("physicalSubscriberCount 通过 Lettuce native pubsubNumsub 返回 channel 真实订阅者数")
    void physicalSubscriberCount_shouldQueryPubSubNumSub() throws Exception {
        Map<byte[], Long> numsubResult = new LinkedHashMap<>();
        numsubResult.put(VERIFY_CHANNEL.getBytes(), 3L);
        stubPubsubNumsub(numsubResult);
        stubConnectionFactoryToReturnLettuceConnection();

        long count = broker.physicalSubscriberCount(VERIFY_CHANNEL);

        assertEquals(3L, count);
    }

    @Test
    @DisplayName("physicalSubscriberCount: 0 订阅者时 entry 仍在 map 里（value=0L），返回 0")
    void physicalSubscriberCount_zeroSubscribers_returnsZero() throws Exception {
        Map<byte[], Long> numsubResult = new LinkedHashMap<>();
        numsubResult.put(VERIFY_CHANNEL.getBytes(), 0L);
        stubPubsubNumsub(numsubResult);
        stubConnectionFactoryToReturnLettuceConnection();

        assertEquals(0L, broker.physicalSubscriberCount(VERIFY_CHANNEL));
    }

    @Test
    @DisplayName("physicalSubscriberCount: pubsubNumsub 抛 TimeoutException → 降级返回 0L")
    void physicalSubscriberCount_timeout_returnsZero() throws Exception {
        when(lettuceConnection.getNativeConnection()).thenReturn(asyncCommands);
        when(asyncCommands.pubsubNumsub(any(byte[].class))).thenReturn(pubsubNumsubFuture);
        when(pubsubNumsubFuture.get(2L, TimeUnit.SECONDS)).thenThrow(new TimeoutException("test timeout"));
        stubConnectionFactoryToReturnLettuceConnection();

        assertEquals(0L, broker.physicalSubscriberCount(VERIFY_CHANNEL));
    }

    @Test
    @DisplayName("physicalSubscriberCount: null/blank channel → 0，不触达 Redis")
    void physicalSubscriberCount_nullOrBlank_returnsZero() {
        assertEquals(0L, broker.physicalSubscriberCount(null));
        assertEquals(0L, broker.physicalSubscriberCount(""));
        assertEquals(0L, broker.physicalSubscriberCount("  "));
        // 早返回，不应该走到 ConnectionFactory 路径
        verify(redisTemplate, never()).getRequiredConnectionFactory();
        verifyNoInteractions(connectionFactory, lettuceConnection);
    }

    /**
     * 防回归 (AC5)：原 bug 是 {@code redisTemplate.execute(callback)} 默认通过
     * {@code CloseSuppressingInvocationHandler} 把 {@link org.springframework.data.redis.connection.RedisConnection RedisConnection}
     * 包成 JDK 动态代理（如 {@code jdk.proxy2.$Proxy134}），代理实现 {@code RedisConnection} interface 但不是
     * {@link LettuceConnection} 类的实例 → 生产代码里的 {@code instanceof LettuceConnection} cast 永远 false →
     * 早 return 0L。
     *
     * <p>本用例验证：通过 {@code redisTemplate.getRequiredConnectionFactory().getConnection()} 拿到的
     * 是 raw {@link LettuceConnection}（不被 proxy 包装），cast 真能通过、能走到 {@code pubsubNumsub} 真实路径
     * 并返回 server 端真值（这里 mock 为 7）。如果未来谁把 {@code physicalSubscriberCount} 改回
     * {@code redisTemplate.execute(callback)}，这个用例会因为 cast 失败而拿到 0 而非 7，立刻报错。
     */
    @Test
    @DisplayName("physicalSubscriberCount: 走 connectionFactory 路径，cast 不再被 proxy 阻断（防回归）")
    void physicalSubscriberCount_proxiedConnection_now_works() throws Exception {
        Map<byte[], Long> numsubResult = new LinkedHashMap<>();
        numsubResult.put(VERIFY_CHANNEL.getBytes(), 7L);
        stubPubsubNumsub(numsubResult);
        stubConnectionFactoryToReturnLettuceConnection();

        long count = broker.physicalSubscriberCount(VERIFY_CHANNEL);

        // 真值 7 来自 pubsubNumsub mock：cast guard 真实通过 → 走到 native API → 拿到真值
        assertEquals(7L, count);
        // 进一步证明 cast 通过：lettuceConnection.getNativeConnection() 一定被调到
        verify(lettuceConnection).getNativeConnection();
    }

    /**
     * 防回归 (AC5)：保留 cast guard 的 Jedis 兜底语义。
     *
     * <p>当 {@code factory.getConnection()} 返回的是 plain {@link RedisConnection}（非 LettuceConnection），
     * cast guard {@code if (!(conn instanceof LettuceConnection)) return 0L;} 必须降级返回 0L，
     * 而不是 NPE / ClassCastException。
     */
    @Test
    @DisplayName("physicalSubscriberCount: 非 LettuceConnection（如 Jedis）→ cast guard 降级返回 0L")
    void physicalSubscriberCount_nonLettuceConnection_returnsZero() {
        RedisConnection plainConn = org.mockito.Mockito.mock(RedisConnection.class);
        when(redisTemplate.getRequiredConnectionFactory()).thenReturn(connectionFactory);
        when(connectionFactory.getConnection()).thenReturn(plainConn);

        assertEquals(0L, broker.physicalSubscriberCount(VERIFY_CHANNEL));
        // cast guard 早 return；不应触达 LettuceConnection 路径
        verifyNoInteractions(lettuceConnection);
    }

    @Test
    @DisplayName("subscribe clears MDC after handler finishes")
    void subscribe_clearsMdcAfterHandler() {
        broker.subscribeToChannel(VERIFY_CHANNEL, ignored -> MdcHelper.putTraceId("trace-from-handler"));
        ArgumentCaptor<MessageListener> listenerCaptor = ArgumentCaptor.forClass(MessageListener.class);
        verify(listenerContainer).addMessageListener(listenerCaptor.capture(), any(ChannelTopic.class));

        Message message = org.mockito.Mockito.mock(Message.class);
        when(message.getBody()).thenReturn("{\"ok\":true}".getBytes(StandardCharsets.UTF_8));

        listenerCaptor.getValue().onMessage(message, null);

        assertEquals(null, MDC.get(MdcConstants.TRACE_ID));
    }

    // ==================== 测试辅助方法 ====================

    private void seedActiveRelayListener() {
        broker.subscribeToChannel(VERIFY_CHANNEL, ignored -> relayHandlerInvocations.incrementAndGet());
        ArgumentCaptor<MessageListener> listenerCaptor = ArgumentCaptor.forClass(MessageListener.class);
        verify(listenerContainer).addMessageListener(listenerCaptor.capture(), any(ChannelTopic.class));
        activeListenerRef.set(listenerCaptor.getValue());
        org.mockito.Mockito.clearInvocations(listenerContainer);
    }

    private void stubProbeLoopback() {
        when(redisTemplate.convertAndSend(eq(VERIFY_CHANNEL), anyString())).thenAnswer(invocation -> {
            String body = invocation.getArgument(1, String.class);
            Message message = org.mockito.Mockito.mock(Message.class);
            when(message.getBody()).thenReturn(body.getBytes(StandardCharsets.UTF_8));
            activeListenerRef.get().onMessage(message, null);
            return 1L;
        });
    }

    /**
     * 让 {@code redisTemplate.getRequiredConnectionFactory().getConnection()} 返回 mock 的
     * {@link LettuceConnection}。这与生产实现现在的真实路径对齐：
     * {@code physicalSubscriberCount} 直接 {@code RedisConnectionUtils.getConnection(factory)} →
     * 内部调 {@code factory.getConnection()}，拿到 raw connection 后做 cast。
     *
     * <p>关键：不再 mock {@code redisTemplate.execute(callback)}，那条路径默认会经
     * {@code CloseSuppressingInvocationHandler} 包 JDK 代理，破坏 {@code instanceof LettuceConnection}。
     */
    private void stubConnectionFactoryToReturnLettuceConnection() {
        when(redisTemplate.getRequiredConnectionFactory()).thenReturn(connectionFactory);
        when(connectionFactory.getConnection()).thenReturn(lettuceConnection);
    }

    /**
     * Mock {@code LettuceConnection.getNativeConnection()} → {@link RedisClusterAsyncCommands}（与
     * 真实返回类型对齐），然后让 {@code pubsubNumsub(...)} 返回提供的 map。
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private void stubPubsubNumsub(Map<byte[], Long> result) throws Exception {
        when(lettuceConnection.getNativeConnection()).thenReturn(asyncCommands);
        when(asyncCommands.pubsubNumsub(any(byte[].class))).thenReturn(pubsubNumsubFuture);
        when(pubsubNumsubFuture.get(2L, TimeUnit.SECONDS)).thenReturn(result);
    }
}
