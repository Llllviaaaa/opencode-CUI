# 目录结构

> `ai-gateway` 采用“按层分包 + 按职责聚合”的 Spring Boot 组织方式：入口、配置、控制器、服务、仓储、模型、WebSocket、日志与资源分离。
---

## 概览

`GatewayApplication` 同时开启了 `@EnableScheduling` 和 `@MapperScan("com.opencode.cui.gateway.repository")`，所以定时离线检查与 MyBatis Mapper 都是默认运行面，而不是旁路能力。见 `ai-gateway/src/main/java/com/opencode/cui/gateway/GatewayApplication.java:8-17`。

## Java 包结构

```text
ai-gateway/src/main/java/com/opencode/cui/gateway/
├── GatewayApplication.java                 # Spring Boot 入口；启用 scheduling 与 Mapper 扫描
├── config/
│   ├── CloudTimeoutProperties.java         # 云端连接超时配置载体
│   ├── GatewayConfig.java                  # 注册 /ws/agent、/ws/skill 和 REST MDC 拦截器
│   ├── MdcRequestInterceptor.java          # REST 请求 traceId / scenario 注入与清理
│   ├── RedisConfig.java                    # RedisTemplate、ListenerContainer 等基础配置
│   ├── RestTemplateConfig.java             # HTTP 客户端相关 Bean
│   └── SnowflakeProperties.java            # 雪花 ID 位分配与回拨策略配置
├── controller/
│   ├── AgentController.java                # 在线 Agent 查询、状态查询、按 AK/invoke 调用
│   └── CloudPushController.java            # `/api/gateway/cloud/im-push` 云端推送入口
├── logging/
│   ├── LogTimer.java                       # 外部调用计时日志包装
│   ├── MdcConstants.java                   # `traceId/sessionId/ak/userId/scenario` 常量
│   ├── MdcHelper.java                      # GatewayMessage → MDC、snapshot/restore、clearAll
│   └── SensitiveDataMasker.java            # MAC / token 脱敏
├── model/
│   ├── AgentConnection.java                # `agent_connection` 持久化实体
│   ├── AgentStatusResponse.java            # Agent 状态查询响应 record
│   ├── AgentSummaryResponse.java           # 在线 Agent 列表响应 record
│   ├── AkSkCredential.java                 # `ak_sk_credential` 凭证实体
│   ├── ApiResponse.java                    # REST 统一响应包装
│   ├── CloudRouteInfo.java                 # 云端路由查询结果
│   ├── GatewayMessage.java                 # Agent / Gateway / Source 统一协议载体
│   ├── ImPushRequest.java                  # IM 推送请求体
│   ├── InvokeResult.java                   # invoke 接口返回 DTO
│   └── RelayMessage.java                   # GW→GW Redis 中继包装 record
├── repository/
│   ├── AgentConnectionRepository.java      # Agent 连接 MyBatis Mapper 接口
│   └── AkSkCredentialRepository.java       # AK/SK 凭证 MyBatis Mapper 接口
├── service/
│   ├── AgentRegistryService.java           # Agent 注册、心跳、超时下线
│   ├── AkSkAuthService.java                # AK/SK 验签；支持 gateway/remote 两种模式
│   ├── AssistantAccountResolver.java       # assistantAccount → AK / creator 解析
│   ├── CloudAgentService.java              # 云端 Agent 调用编排
│   ├── CloudRouteService.java              # 云端路由信息查询与缓存
│   ├── ConsistentHashRing.java             # 多实例一致性哈希
│   ├── DeviceBindingService.java           # 首次注册/重连时的设备绑定校验
│   ├── EventRelayService.java              # Agent 本地会话表与双向消息中继
│   ├── IdentityApiClient.java              # 远端身份校验 API 客户端
│   ├── LegacySkillRelayStrategy.java       # 旧版上游路由兼容策略
│   ├── RedisMessageBroker.java             # Redis key、TTL、pub/sub、pending queue、source 连接表
│   ├── SkillRelayService.java              # Skill Server 路由主服务
│   ├── SkillRelayStrategy.java             # Source 路由策略接口
│   ├── SnowflakeIdGenerator.java           # 雪花 ID 生成器
│   ├── UpstreamRoutingTable.java           # `toolSessionId` / `welinkSessionId` → source 学习表
│   └── cloud/
│       ├── ApigAuthStrategy.java           # 云端 APIG 鉴权策略
│       ├── BusinessInvokeRouteStrategy.java# 企业助手调用路由策略
│       ├── CloudAuthService.java           # 云端鉴权策略选择器
│       ├── CloudAuthStrategy.java          # 鉴权策略接口
│       ├── CloudConnectionContext.java     # 云端连接上下文
│       ├── CloudConnectionHandle.java      # 可取消的云端流连接句柄
│       ├── CloudConnectionLifecycle.java   # 云端连接生命周期与超时回调
│       ├── CloudProtocolClient.java        # 云端协议客户端接口
│       ├── CloudProtocolStrategy.java      # 协议策略接口
│       ├── InvokeRouteStrategy.java        # 调用路由策略接口
│       ├── PersonalInvokeRouteStrategy.java# 个人助手调用路由策略
│       ├── SoaAuthStrategy.java            # SOA 鉴权策略
│       ├── SseProtocolStrategy.java        # SSE 云端协议实现
│       └── WebSocketProtocolStrategy.java  # WebSocket 云端协议实现
└── ws/
    ├── AgentWebSocketHandler.java          # PCAgent 握手、register/heartbeat/status_response 处理
    ├── AsyncSessionSender.java             # 本地异步发送队列
    └── SkillWebSocketHandler.java          # Skill Server 内部握手、invoke/route_confirm 处理
```

## Resources 结构

```text
ai-gateway/src/main/resources/
├── application.yml                         # MySQL、Redis、auth、relay、cloud 全量配置
├── log4j2-spring.xml                       # `[ai-gateway]` 日志格式与 rolling 策略
├── log4j2.component.properties             # Log4j2 组件配置
├── mapper/
│   ├── AgentConnectionMapper.xml           # `agent_connection` 查询/更新 SQL
│   └── AkSkCredentialMapper.xml            # `ak_sk_credential` 查询/更新 SQL
└── db/migration/
    ├── V1__gateway.sql                     # 初始 gateway 表结构
    ├── V2__ak_sk_credential.sql            # AK/SK 凭证表
    ├── V3__agent_identity_persistence.sql  # Agent 身份持久化相关变更
    ├── V4__align_userid_type.sql           # userId 类型对齐
    └── V5__snowflake_primary_keys.sql      # Snowflake 主键迁移
```

## 命名规范

| 类别 | 规则 | 真实示例 |
|------|------|----------|
| Controller | `{Domain}Controller` | `AgentController`, `CloudPushController` |
| Service | `{Domain}Service` / `{Domain}Strategy` | `AgentRegistryService`, `SkillRelayService`, `WebSocketProtocolStrategy` |
| Repository | `{Entity}Repository` | `AgentConnectionRepository`, `AkSkCredentialRepository` |
| 模型实体 | 直接使用业务名，不追加 `Entity` | `AgentConnection`, `AkSkCredential` |
| REST/协议 DTO | 用具体语义命名；能用 `record` 就不用 `Map` | `AgentSummaryResponse`, `InvokeResult`, `RelayMessage` |
| 配置类 | `{Domain}Config` / `{Domain}Properties` / `{Domain}Interceptor` | `GatewayConfig`, `SnowflakeProperties`, `MdcRequestInterceptor` |
| WebSocket Handler | `{Peer}WebSocketHandler` | `AgentWebSocketHandler`, `SkillWebSocketHandler` |
| 日志工具 | `{Domain}Helper` / `{Domain}Masker` / `{Domain}Timer` | `MdcHelper`, `SensitiveDataMasker`, `LogTimer` |

## 新增功能时的落点约定

1. 新增 REST 接口：优先放进现有 `controller/AgentController.java` 或 `controller/CloudPushController.java`；只有出现新的独立 HTTP 领域时再拆新 Controller。
2. 新增 Agent/Source 路由逻辑：落在 `service/SkillRelayService.java`、`service/EventRelayService.java` 或 `service/RedisMessageBroker.java`，不要把 Redis 细节塞进 Controller / WS Handler。
3. 新增 MySQL 表：同步新增 `model/{Entity}.java`、`repository/{Entity}Repository.java`、`resources/mapper/{Entity}Mapper.xml`、`resources/db/migration/V{n}__*.sql`。
4. 新增 Redis key 或 channel：统一收口到 `RedisMessageBroker` 的前缀常量，不要在调用点手写字符串。
5. 新增协议字段：优先扩展 `GatewayMessage` 或 `RelayMessage`，并补对应测试（如 `GatewayMessageTest`、`RelayMessageTest`）。
