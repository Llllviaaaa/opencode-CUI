package com.opencode.cui.gateway.service.cloud;

import com.opencode.cui.gateway.model.GatewayMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 云端协议客户端调度器。
 *
 * <p>通过 Spring 自动注入所有 {@link CloudProtocolStrategy} 实现，
 * 按 {@code protocol} 构建查找表，运行时按协议类型分派连接逻辑。</p>
 */
@Slf4j
@Service
public class CloudProtocolClient {

    private final Map<String, CloudProtocolStrategy> strategyMap;

    public CloudProtocolClient(List<CloudProtocolStrategy> strategies) {
        this.strategyMap = strategies.stream()
                .collect(Collectors.toMap(CloudProtocolStrategy::getProtocol, Function.identity()));
        log.info("[CLOUD_PROTOCOL] Registered protocol strategies: {}", strategyMap.keySet());
    }

    /**
     * 按协议类型连接云端服务。
     *
     * @param protocol  协议标识（sse / websocket）
     * @param context   连接上下文
     * @param lifecycle 连接生命周期管理器，用于超时计时；可为 null
     * @param onEvent   事件回调
     * @param onError   错误回调（未知协议时也通过此回调报错）
     */
    public void connect(String protocol, CloudConnectionContext context,
                        CloudConnectionLifecycle lifecycle,
                        Consumer<GatewayMessage> onEvent, Consumer<Throwable> onError) {
        CloudProtocolStrategy strategy = strategyMap.get(protocol);
        if (strategy == null) {
            log.error("[CLOUD_PROTOCOL] Unknown protocol: {}", protocol);
            onError.accept(new IllegalArgumentException("Unknown cloud protocol: " + protocol));
            return;
        }
        strategy.connect(context, lifecycle, onEvent, onError);
    }
}
