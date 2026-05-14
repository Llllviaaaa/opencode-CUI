package com.opencode.cui.gateway.service.cloud.decoder;

import com.opencode.cui.gateway.model.GatewayMessage;

import java.util.List;

/**
 * SSE 数据行解码器。
 *
 * <p>不同 cloud profile 实现自己的 decoder，把非标 SSE data 行翻译成
 * 标准 {@link GatewayMessage} 流。</p>
 *
 * <p>状态机所需的 per-connection 字段放在 {@link DecoderSession}，
 * decoder 本身保持 stateless 单例。</p>
 */
public interface SseEventDecoder {

    /** decoder 名称（与 cloud profile 解码器名称匹配）。 */
    String getName();

    /** 工厂方法：创建一次连接对应的 session 实例。 */
    DecoderSession createSession();

    /**
     * 终止符识别。{@code data:} 后剥离前缀的剩余内容传入。
     * 常见值：{@code "[DONE]"} / {@code "FINISH"}。
     */
    boolean isTerminator(String dataLine);

    /**
     * 业务层心跳识别。默认 false（OpenCode 协议不发业务层心跳）。
     *
     * <p>识别后由 {@code SseProtocolStrategy} 调用 {@code lifecycle.onHeartbeat}
     * 重置 idle 计时器，并跳过 decode，不下发事件。</p>
     */
    default boolean isHeartbeat(String dataLine) {
        return false;
    }

    /**
     * 把单条 data 行翻译为零或多条标准 {@link GatewayMessage}。
     *
     * @param dataLineJson {@code data:} 后剥离前缀 + trim 后的内容
     * @param session      当前连接 session（{@link #createSession()} 返回）
     */
    List<GatewayMessage> decode(String dataLineJson, DecoderSession session);

    /**
     * 流终止时补未关闭的 {@code .done} / {@code step.done} 等收尾事件。
     *
     * <p>在 SseProtocolStrategy finally 中调用，无论流是正常 finish、HTTP 错误、
     * 超时或网络中断都会执行。</p>
     */
    List<GatewayMessage> flush(DecoderSession session);
}
