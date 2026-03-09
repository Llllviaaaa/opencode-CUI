# 实施计划

> 版本：1.0  
> 日期：2026-03-09

## 1. 改造顺序

采用自底向上的依赖顺序：

```text
Phase 1: 数据层 → Phase 2: 服务层 → Phase 3: 客户端 → Phase 4: 验证
```

## 2. 文件变更清单

### Phase 1: 数据模型与数据库迁移

| 操作   | 文件                                 | 变更说明                                                                    |
| ------ | ------------------------------------ | --------------------------------------------------------------------------- |
| MODIFY | `AgentConnection.java`               | 新增 `macAddress` 字段                                                      |
| MODIFY | `AgentConnectionRepository.java`     | 新增 `findByAkIdAndToolType()`、`updateAgentInfo()`                         |
| MODIFY | `AgentConnectionMapper.xml`          | 新增 `mac_address` 映射 + 新增 2 条 SQL                                     |
| MODIFY | `GatewayMessage.java`                | 新增 `macAddress`、`reason` 字段；新增 `registerOk()`、`registerRejected()` |
| NEW    | `V3__agent_identity_persistence.sql` | 加列 + 清理 + 唯一约束                                                      |

### Phase 2: 服务层改造

| 操作   | 文件                         | 变更说明                                       |
| ------ | ---------------------------- | ---------------------------------------------- |
| NEW    | `DeviceBindingService.java`  | 第三方设备绑定校验，fail-open                  |
| MODIFY | `AgentRegistryService.java`  | 删除踢旧逻辑，改为查找复用/新建                |
| MODIFY | `AgentWebSocketHandler.java` | 完整重写：子协议认证 + 三步注册校验 + 注册超时 |

### Phase 3: 客户端改造

| 操作   | 文件                   | 变更说明                                                                   |
| ------ | ---------------------- | -------------------------------------------------------------------------- |
| MODIFY | `GatewayConnection.ts` | 删除 `buildUrl()`；auth 走子协议；新增 `REJECTION_CODES` + `rejected` 事件 |
| MODIFY | `plugin.ts`            | 新增 `getMacAddress()`；register 消息加 `macAddress`；新增 `rejected` 监听 |

### Phase 4: 测试验证

| 操作   | 文件                      | 变更说明                                                           |
| ------ | ------------------------- | ------------------------------------------------------------------ |
| MODIFY | `GatewayMessageTest.java` | 适配 `register()` 新签名 + 新增 `registerOk/registerRejected` 测试 |

## 3. 配置变更

### 3.1 新增配置项（application.yml）

```yaml
gateway:
  device-binding:
    enabled: false
    url: ""
    timeout-ms: 3000
  agent:
    register-timeout-seconds: 10
```

### 3.2 已有配置（无需变更）

```yaml
gateway:
  agent:
    heartbeat-timeout-seconds: 90
    heartbeat-check-interval-seconds: 30
```

## 4. 验证节点

| 阶段         | 验证方式        | 通过标准                             |
| ------------ | --------------- | ------------------------------------ |
| Phase 1 完成 | `mvn compile`   | 编译通过                             |
| Phase 2 完成 | `mvn compile`   | 编译通过                             |
| Phase 3 完成 | TypeScript 编译 | 无类型错误                           |
| Phase 4 完成 | `mvn test`      | 全部测试通过                         |
| 部署前       | Flyway 迁移     | V3 脚本执行成功                      |
| 部署后       | 手动 E2E        | 正常连接 + 重复连接拒绝 + 身份持久化 |
