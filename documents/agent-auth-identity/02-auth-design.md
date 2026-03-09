# 认证传输方式设计

> 版本：1.1  
> 日期：2026-03-09  
> 更新：2026-03-09（Base64URL 编码修复）

## 1. 问题定义

当前 PCAgent 认证参数（AK、timestamp、nonce、signature）通过 URL Query Parameter 传递，存在安全风险（日志泄露）。需要选择更安全的传输方式。

## 2. 方案对比

### 2.1 横向对比：四种传输方式

| 维度        | URL Query Param | HTTP Header     | 首条消息                | **WebSocket 子协议**          |
| ----------- | --------------- | --------------- | ----------------------- | ----------------------------- |
| 安全性      | ❌ URL 日志泄露  | ✅ 不入 URL 日志 | ✅ 不入日志              | **✅ 不入日志**                |
| 校验时机    | 握手阶段        | 握手阶段        | 连接后                  | **握手阶段**                  |
| 未认证窗口  | 无              | 无              | 有（握手→首条消息之间） | **无**                        |
| Spring 支持 | 原生            | 需自定义拦截器  | 在 Handler 中处理       | **原生 HandshakeInterceptor** |
| 客户端适配  | 拼 URL          | `ws` 库原生支持 | 需消息协议              | **`ws` 库原生支持**           |

### 2.2 纵向对比：安全性 vs 复杂度

| 方案                 | 安全性 | 实现复杂度 | 改造成本   |
| -------------------- | ------ | ---------- | ---------- |
| URL Query Param      | 低     | 最低       | 无（现状） |
| HTTP Header          | 中     | 低         | 小         |
| 首条消息             | 中     | 中         | 中         |
| **WebSocket 子协议** | **高** | **中**     | **中**     |

## 3. 选定方案：WebSocket 子协议

### 3.1 编码格式

```text
Sec-WebSocket-Protocol: auth.{base64url(JSON)}
```

> **重要**：必须使用 **Base64URL 编码**（RFC 4648 §5），而非标准 Base64。
> 原因：WebSocket 子协议 token 遵循 HTTP token 语法（RFC 7230），不允许包含 `+`、`/`、`=` 字符（这些是标准 Base64 的常用字符）。
> Bun 运行时（OpenCode 使用）对此做了严格校验，使用标准 Base64 会抛出 `SyntaxError: Wrong protocol`。

JSON 结构：

```json
{
  "ak": "test-ak-001",
  "ts": "1709971200000",
  "nonce": "a1b2c3d4",
  "sign": "hmac-sha256-signature"
}
```

### 3.2 服务端处理

```text
Gateway.beforeHandshake():
  1. 从 request.getHeaders().get("Sec-WebSocket-Protocol") 获取子协议列表
  2. 找到 "auth." 前缀的子协议
  3. 截取 Base64URL 部分 → Base64URL 解码 → JSON 解析 → 提取 ak/ts/nonce/sign
  4. 调用 AkSkAuthService.verify(ak, ts, nonce, sign)
  5. 校验通过 → response 回显完整子协议值 "Sec-WebSocket-Protocol: auth.{原始base64url}"
  6. 校验失败 → return false（握手拒绝）
```

> **注意**：步骤 5 中必须回显客户端发送的**完整子协议值**，而非简写为 `"auth"`。
> RFC 6455 要求服务端选择的子协议必须是客户端提供的子协议之一（精确匹配）。
> Bun 运行时严格执行此检查，不匹配会立即 `Connection reset`（close code 1006）。

### 3.3 客户端处理

```typescript
// 必须使用 'base64url' 编码，不能用 'base64'
const authPayload = Buffer.from(JSON.stringify({
  ak: auth.ak,
  ts: auth.timestamp,
  nonce: auth.nonce,
  sign: auth.signature,
})).toString('base64url');

const ws = new WebSocket(gatewayUrl, [`auth.${authPayload}`]);
```

### 3.4 选型理由

1. **安全**：认证信息不出现在 URL 中，不会被日志记录
2. **无窗口期**：握手阶段即完成校验，不存在未认证连接窗口
3. **协议标准**：`Sec-WebSocket-Protocol` 是 RFC 6455 定义的标准机制
4. **兼容性好**：Node.js `ws` 库和 Spring HandshakeInterceptor 均原生支持

### 3.5 Bun 运行时兼容性（踩坑记录）

OpenCode 运行在 Bun 运行时，Bun 的 WebSocket 实现比 Node.js `ws` 库**更严格**：

| 检查点            | Node.js `ws`               | Bun WebSocket                          |
| ----------------- | -------------------------- | -------------------------------------- |
| 子协议 token 字符 | 宽松（允许 `=`、`+`、`/`） | **严格**（遵循 RFC 7230 tchar 定义）   |
| 子协议响应匹配    | 宽松                       | **严格**（必须精确匹配客户端提供的值） |

因此：
- **编码必须用 Base64URL**（无 `+`/`/`/`=`），不能用标准 Base64
- **服务端必须回显完整子协议值**（`auth.{base64url}`），不能简写为 `auth`
