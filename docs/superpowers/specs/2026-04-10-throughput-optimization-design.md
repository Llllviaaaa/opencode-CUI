# 吞吐优化设计文档（Round 2）

## 背景

Round 1 优化解决了 DB CPU 高和 Agent 断联问题（synchronized → AsyncSessionSender）。压测验证后发现新的瓶颈：GW 和 SS 的吞吐受限于线程调度争抢、日志同步 I/O、Redis 连接池竞争。

### 压测环境

- 500 Agent 并发，10s 持续发送，10ms 间隔
- GW + SS 各一个实例，本地部署

### 压测结果（Round 1 优化后）

| 指标 | 值 |
|------|---|
| Recv/Sent | 81.5%（丢失 18.5%） |
| avg TPS | 98 msg/s |
| GW relay avg | 685ms（p50=519ms, p95=1640ms） |
| SS processing avg | 25ms（p50=19ms, p95=49ms） |
| Agent 断连 | 0（Round 1 已修复） |
| Skill WS 断连 | 9 次 |
| L3 broadcast rate limit | 1224 次 |

---

## GW 侧瓶颈分析

### G1：Tomcat 线程数不够（200 vs 500 Agent）

**根因**：200 个 OS 线程服务 500 个 Agent 的 WebSocket 消息。线程之间的调度等待占了处理时间的 80% 以上。

**日志证据**：对单个线程（exec-126）采样 139 个步骤间隔：
- <5ms（实际代码执行）：14 次（10.1%）
- 50-200ms（线程调度等待）：93 次（66.9%）
- >200ms（严重争抢）：19 次（13.7%）

当争抢消失时（11:51:41.706→41.707→41.708），三步只用了 2ms — 这才是代码真正的执行速度。

**影响**：理论吞吐 200 线程 / 0.7s = 286 msg/s，实测仅 98 msg/s。

### G2：日志同步双写

**根因**：logback 配置了 CONSOLE + FILE 两个同步 appender，无 AsyncAppender。高峰期 1500+ lines/sec，200 个线程每次 `log.info()` 都竞争日志锁。

**影响**：纯内存操作（Caffeine 查找、hashRing、JSON 序列化）之间也有 100ms+ 间隔，因为操作前后的日志写入在排队等锁。

### G3：Redis 连接池太小

**根因**：Lettuce pool `max-active=50`，200 个 Tomcat 线程竞争。

**日志证据**：单次 `getAgentUser` Redis GET 耗时 174ms（正常应 <1ms），原因是等待连接池释放连接。

---

## SS 侧瓶颈分析

### S1：读线程吞吐天花板

**根因**：GatewayWSClient 建立 8 条到 GW 的 WebSocket 连接，每条一个 `WebSocketConnectReadThread`，串行处理。8 个线程各 ~20 msg/s = 总吞吐 ~160 msg/s。

**供需对比**（逐秒）：

| 时段 | GW 入队 | SS 消费 | 差值 | 累积积压 |
|------|---------|---------|------|---------|
| 11:51:41 | 215 | 219 | -4 | 370 |
| 11:51:44 | 225 | 175 | +50 | 502 |
| 11:51:47 | 182 | 143 | +39 | 686 |
| 11:51:49 | 392 | 62 | +330 | 985 |

积压从第 1 秒就开始，每秒多灌入 30-50 条，10 秒累积到 985 条。

### S2：单条消息处理慢（44ms avg）

**各步骤耗时**：

| 步骤 | 操作 | avg 耗时 |
|------|------|---------|
| gw_entry → route_entry | JSON 解析 + session 路由 | 13.2ms |
| route_entry → tool_event | 事件翻译 | 3.7ms |
| tool_event → redis_pub | 持久化准备 | 10.0ms |
| redis_pub → broadcast | Redis PUBLISH | 4.5ms |
| broadcast → route_exit | 后续处理 | 12.1ms |
| route_exit → gw_exit | 清理 | 4.8ms |

每条消息产生 **10 条 INFO 日志**，日志 I/O 是各步骤耗时的主要组成部分。

### S3：日志同步双写

和 GW 完全相同的问题 — CONSOLE + FILE 同步 appender，8 线程 × 10 条日志/消息 = 1600 lines/sec。

### S4：队列积压导致写超时（级联故障）

**完整链路**：
1. GW 入队速率（~200 msg/s）> SS 消费速率（~160 msg/s）→ 队列持续积压
2. AsyncSessionSender pending 从 252 涨到 681 → TCP 缓冲区满
3. 11:51:50.104 GW 侧 WS 写超时 → 连接断开
4. SS 8 条连接中多条断开 → 4/8 个读线程停止工作
5. SS 吞吐从 ~160 骤降到 62 msg/s → 积压加速
6. GW hashRing 清空 → 消息涌入 L3 broadcast → rate limit 丢弃 1224 条

### S5：断连后部分线程停止

写超时断连后，4/8 个读线程在 11:51:49 几乎完全停止（0-1 条/秒），等待重连。2.3 秒黑洞期内吞吐进一步下降。

---

## 解决方案

### 本轮实施（3 项，GW + SS 共 6 个改动点）

#### 1. Java 21 虚拟线程（GW + SS）

**解决**：G1（线程调度争抢）、S1（读线程吞吐天花板）

**GW 侧**：

```yaml
# application.yml
spring:
  threads:
    virtual:
      enabled: true
```

Spring Boot 3.4 + Java 21 原生支持。Tomcat 自动使用虚拟线程处理请求，500 个 Agent 的 WebSocket 消息不再受 200 OS 线程的限制。虚拟线程在遇到 I/O（Redis、日志）时自动 yield，不占用平台线程。

**SS 侧**：

同样启用虚拟线程。`GatewayWSClient` 基于 `java-websocket` 库，其读线程由库内部管理，不直接受 Spring 虚拟线程配置影响。但 SS 的 Redis 监听线程、HTTP API 线程、异步处理线程均受益。

SS 的 WS 读线程中的阻塞操作（Redis PUBLISH、DB upsert）在虚拟线程环境下会自动 yield。如果 `java-websocket` 库不支持虚拟线程作为读线程，可在读线程收到消息后立即提交到虚拟线程执行：

```java
Thread.ofVirtual().start(() -> handleGatewayMessage(message));
```

**注意事项**：
- Round 1 已将所有 `synchronized(session)` 替换为 AsyncSessionSender 异步队列，减少了虚拟线程 pin 风险
- Lettuce（Redis 客户端）在 6.x 已兼容虚拟线程
- HikariCP 内部使用 `synchronized`，可能导致虚拟线程 pin，需要关注。如果成为问题，可考虑替换为支持虚拟线程的连接池

#### 2. Log4j2 + LMAX Disruptor 全异步日志（GW + SS）

**解决**：G2（日志锁争抢）、S2（单条消息处理慢）、S3（日志同步双写）

**改动**：

1. 排除 Spring Boot 默认的 Logback：

```xml
<!-- pom.xml (GW + SS) -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-web</artifactId>
    <exclusions>
        <exclusion>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-logging</artifactId>
        </exclusion>
    </exclusions>
</dependency>
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-log4j2</artifactId>
</dependency>
<dependency>
    <groupId>com.lmax</groupId>
    <artifactId>disruptor</artifactId>
    <version>4.0.0</version>
</dependency>
```

2. 启用全异步模式：

```properties
# src/main/resources/log4j2.component.properties (GW + SS)
Log4jContextSelector=org.apache.logging.log4j.core.async.AsyncLoggerContextSelector
```

3. Log4j2 配置文件：

```xml
<!-- src/main/resources/log4j2-spring.xml (GW + SS) -->
<?xml version="1.0" encoding="UTF-8"?>
<Configuration status="WARN">
    <Properties>
        <Property name="SERVICE_NAME">ai-gateway</Property>  <!-- SS 改为 skill-server -->
        <Property name="LOG_FILE">./logs/${SERVICE_NAME}/${SERVICE_NAME}.log</Property>
        <Property name="LOG_PATTERN">%d{yyyy-MM-dd HH:mm:ss.SSS} [%thread] %-5level [${SERVICE_NAME}] [%X{traceId:-}] [%X{sessionId:-}] [%X{ak:-}] %logger{36}.%method - %msg%n</Property>
    </Properties>

    <Appenders>
        <RollingFile name="FILE"
                     fileName="${LOG_FILE}"
                     filePattern="./logs/${SERVICE_NAME}/archive/${SERVICE_NAME}.%d{yyyy-MM-dd}.%i.log.gz">
            <PatternLayout pattern="${LOG_PATTERN}" charset="UTF-8"/>
            <Policies>
                <SizeBasedTriggeringPolicy size="20MB"/>
                <TimeBasedTriggeringPolicy interval="1"/>
            </Policies>
            <DefaultRolloverStrategy max="30"/>
        </RollingFile>

        <!-- dev 环境控制台输出 -->
        <Console name="CONSOLE" target="SYSTEM_OUT">
            <PatternLayout pattern="${LOG_PATTERN}" charset="UTF-8"/>
        </Console>
    </Appenders>

    <Loggers>
        <!-- 生产/压测环境：只保留 FILE（通过 Spring Profile 或环境变量控制） -->
        <Root level="INFO">
            <AppenderRef ref="FILE"/>
        </Root>
    </Loggers>
</Configuration>
```

4. 删除旧的 `logback-spring.xml`。

**性能对比**：

| | Logback 同步 | Log4j2 + Disruptor 全异步 |
|---|---|---|
| 内部队列 | 无（直接写 I/O） | LMAX Disruptor ring buffer（lock-free） |
| 吞吐 | ~100 万 msg/s | ~1800 万 msg/s |
| 业务线程阻塞 | 每次 log 调用等锁 | 写入 ring buffer 后立即返回 |
| GC 压力 | 每条日志创建对象 | ring buffer 预分配，对象复用 |

**CONSOLE 控制**：通过 Spring Profile 或环境变量控制。开发环境添加 CONSOLE appender，生产/压测环境只保留 FILE。

#### 3. Redis 连接池调到 200（GW + SS）

**解决**：G3（Redis 连接等待）

```yaml
# application.yml (GW + SS)
spring:
  data:
    redis:
      lettuce:
        pool:
          max-active: 200
          max-idle: 50
          min-idle: 10
```

启用虚拟线程后并发可能更高，但 Redis 服务端有连接数上限。200 是合理起点，后续根据压测调整。

---

### 下轮待观察

压测验证后，如果吞吐仍不足：

| 方向 | 做法 | 触发条件 |
|------|------|---------|
| SS I/O 层重构 | Netty WebSocket Client + per-connection SerialExecutor（虚拟线程支撑） | SS 读线程仍是瓶颈 |
| GW 断连防护 | hashRing 延迟移除 + 重连窗口 | 仍有消息涌入 L3 被丢弃 |
| GW L3 改进 | rate limit 改排队重试 | L3 丢消息仍严重 |

---

## 预期效果

| 指标 | 当前 | 预期 |
|------|------|------|
| GW relay avg | 685ms | <50ms（消除线程调度 + 日志锁 + Redis 等待） |
| SS processing avg | 44ms | <10ms（消除日志锁） |
| 整体 TPS | 98 msg/s | >500 msg/s |
| 消息丢失率 | 18.5% | <1%（SS 吞吐超过 GW 入队速率，不再积压） |
| Skill WS 断连 | 9 次 | 0（不再积压触发写超时） |

---

## 实施清单

| 步骤 | 改动 | 位置 | 风险 |
|------|------|------|------|
| 1 | 虚拟线程 `spring.threads.virtual.enabled=true` | GW application.yml | 低 |
| 2 | 虚拟线程 `spring.threads.virtual.enabled=true` | SS application.yml | 低 |
| 3 | 排除 Logback，引入 Log4j2 + Disruptor 依赖 | GW pom.xml | 中（换日志框架） |
| 4 | 排除 Logback，引入 Log4j2 + Disruptor 依赖 | SS pom.xml | 中（换日志框架） |
| 5 | Log4j2 配置 + 全异步模式 + 删除 logback-spring.xml | GW resources/ | 中 |
| 6 | Log4j2 配置 + 全异步模式 + 删除 logback-spring.xml | SS resources/ | 中 |
| 7 | Redis 连接池 max-active=200 | GW application.yml | 低 |
| 8 | Redis 连接池 max-active=200 | SS application.yml | 低 |

## 验证方式

- 压测对比：相同条件（500 pairs, 10s, 10ms interval）下 TPS、丢失率、relay 耗时
- 功能验证：日志格式和内容正确性
- 稳定性验证：长时间压测无 WS 写超时和断连
