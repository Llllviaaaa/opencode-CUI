package com.opencode.cui.gateway.service.cloud.decoder;

/**
 * SSE decoder 会话状态标记接口。
 *
 * <p>每个 decoder 实现可定义自己的 session 子类承载流式解码状态
 * （per-connection，连接关闭后被 GC）。</p>
 */
public interface DecoderSession {
}
