# 灞傗憿 鎺ュ彛鍗忚锛欰I-Gateway 鈫?PC Agent

> 鐗堟湰锛?.2  
> 鏃ユ湡锛?026-03-11  
> 鐘舵€侊細寰呭疄鐜?

---

## 鍏ㄥ眬绾﹀畾

### 閫氫俊鏂瑰紡

| 椤圭洰         | 璇存槑                         |
| ------------ | ---------------------------- |
| **鍗忚**     | WebSocket锛圝SON锛?           |
| **杩炴帴鏂瑰悜** | PC Agent 涓诲姩寤鸿仈鍒?Gateway  |
| **绔偣**     | `ws://gateway-host/ws/agent` |

### 璁よ瘉鏂瑰紡

AK/SK 绛惧悕鎻℃墜锛岄€氳繃 **WebSocket 瀛愬崗璁?* `Sec-WebSocket-Protocol` 澶翠紶閫掕璇佷俊鎭紙涓嶆毚闇插湪 URL 涓級锛?

```
Sec-WebSocket-Protocol: auth.{base64url(JSON)}
```

Base64URL 缂栫爜鐨?JSON 缁撴瀯锛?

```json
{
  "ak": "<access_key>",
  "ts": "<timestamp>",
  "nonce": "<random>",
  "sign": "<signature>"
}
```

| 鍙傛暟    | 绫诲瀷   | 蹇呭～  | 璇存槑                                          |
| ------- | ------ | :---: | --------------------------------------------- |
| `ak`    | String |   鉁?  | Agent 鐨?Access Key                           |
| `ts`    | String |   鉁?  | 褰撳墠鏃堕棿鎴?                                   |
| `nonce` | String |   鉁?  | 闅忔満鏁帮紝闃查噸鏀?                               |
| `sign`  | String |   鉁?  | HMAC 绛惧悕锛歚HMAC-SHA256(sk, ak + ts + nonce)` |

Gateway 鍦?`beforeHandshake()` 涓細
1. 浠?`Sec-WebSocket-Protocol` 澶存彁鍙?`auth.` 鍓嶇紑鐨勫瓙鍗忚
2. Base64URL 瑙ｇ爜 鈫?JSON 瑙ｆ瀽 鈫?鎻愬彇 ak/ts/nonce/sign
3. 璋冪敤 `AkSkAuthService.verify(ak, ts, nonce, sign)` 鏍￠獙绛惧悕锛岃繑鍥?`String` 绫诲瀷鐨?`userId`
4. 鏍￠獙閫氳繃 鈫?鍝嶅簲鍥炴樉瀹屾暣瀛愬崗璁€硷紱鏍￠獙澶辫触 鈫?鎷掔粷鎻℃墜

> **娉ㄦ剰**锛氬繀椤讳娇鐢?Base64URL 缂栫爜锛圧FC 4648 搂5锛夛紝涓嶈兘鐢ㄦ爣鍑?Base64銆?> 鏈嶅姟绔繀椤诲洖鏄惧鎴风鍙戦€佺殑瀹屾暣瀛愬崗璁€硷紙RFC 6455 绮剧‘鍖归厤瑕佹眰锛夈€?> Gateway 鍐呴儴浠ュ瓧绗︿覆褰㈠紡澶勭悊 `userId`锛屽苟鎸佷箙鍖栧埌 `agent_connection.user_id`銆乣ak_sk_credential.user_id`锛坄VARCHAR(128)`锛夈€?
### ID 鍛藉悕瑙勮寖

| 鍚嶇О              | 璇存槑                                                                       |
| ----------------- | -------------------------------------------------------------------------- |
| `welinkSessionId` | Skill 浼氳瘽 ID銆侾C Agent 涓嶈瘑鍒涔夛紝鏉ヨ嚜 invoke.create_session锛岄渶鍘熸牱鍥炰紶 |
| `toolSessionId`   | OpenCode SDK 鍒嗛厤鐨勪細璇?ID銆侾C Agent 鍜?OpenCode 鍧囦娇鐢ㄦ ID               |

---

## 涓€銆佷笅琛屾秷鎭紙Gateway 鈫?PC Agent锛?

---

### 1. invoke.create_session 鈥?鍒涘缓 OpenCode 浼氳瘽

```json
{
  "type": "invoke",
  "welinkSessionId": 42,
  "action": "create_session",
  "payload": {
    "title": "甯垜鍒涘缓涓€涓猂eact椤圭洰"
  }
}
```

| 瀛楁              | 绫诲瀷   | 蹇呭～  | 璇存槑                                                   |
| ----------------- | ------ | :---: | ------------------------------------------------------ |
| `type`            | String |   鉁?  | 鍥哄畾 `"invoke"`                                        |
| `welinkSessionId` | Long |   鉁?  | Skill 浼氳瘽 ID锛孭C Agent 闇€鍘熸牱鍥炰紶鍒?`session_created` |
| `action`          | String |   鉁?  | 鍥哄畾 `"create_session"`                                |
| `payload`         | Object |   鉁?  | 鍒涘缓鍙傛暟                                               |

| payload 瀛楁 | 绫诲瀷   | 蹇呭～  | 璇存槑     |
| ------------ | ------ | :---: | -------- |
| `title`      | String |   鉂?  | 浼氳瘽鏍囬 |

**PC Agent 鏀跺埌鍚?*: 璋冪敤 `client.session.create()` 鍒涘缓 OpenCode 浼氳瘽锛屾垚鍔熷悗鍙戦€?`session_created` 鍥炰紶 `toolSessionId`銆?

---

### 2. invoke.chat 鈥?鍙戦€佺敤鎴锋秷鎭?

```json
{
  "type": "invoke",
  "action": "chat",
  "payload": {
    "toolSessionId": "ses_abc",
    "text": "甯垜鍒涘缓涓€涓猂eact椤圭洰"
  }
}
```

| 瀛楁      | 绫诲瀷   | 蹇呭～  | 璇存槑            |
| --------- | ------ | :---: | --------------- |
| `type`    | String |   鉁?  | 鍥哄畾 `"invoke"` |
| `action`  | String |   鉁?  | 鍥哄畾 `"chat"`   |
| `payload` | Object |   鉁?  | 娑堟伅鍙傛暟        |

| payload 瀛楁    | 绫诲瀷   | 蹇呭～  | 璇存槑             |
| --------------- | ------ | :---: | ---------------- |
| `toolSessionId` | String |   鉁?  | OpenCode 浼氳瘽 ID |
| `text`          | String |   鉁?  | 鐢ㄦ埛娑堟伅鍐呭     |

**PC Agent 鏀跺埌鍚?*: 璋冪敤 `client.session.prompt({ path: { id: toolSessionId }, body: { parts: [{ type: 'text', text }] } })`銆?

---

### 3. invoke.abort_session 鈥?涓褰撳墠鎵ц

```json
{
  "type": "invoke",
  "action": "abort_session",
  "payload": {
    "toolSessionId": "ses_abc"
  }
}
```

| payload 瀛楁    | 绫诲瀷   | 蹇呭～  | 璇存槑             |
| --------------- | ------ | :---: | ---------------- |
| `toolSessionId` | String |   鉁?  | OpenCode 浼氳瘽 ID |

**PC Agent 鏀跺埌鍚?*: 璋冪敤 `client.session.abort({ path: { id: toolSessionId } })`銆?

---

### 4. invoke.close_session 鈥?鍏抽棴骞跺垹闄や細璇?

```json
{
  "type": "invoke",
  "action": "close_session",
  "payload": {
    "toolSessionId": "ses_abc"
  }
}
```

| payload 瀛楁    | 绫诲瀷   | 蹇呭～  | 璇存槑             |
| --------------- | ------ | :---: | ---------------- |
| `toolSessionId` | String |   鉁?  | OpenCode 浼氳瘽 ID |

**PC Agent 鏀跺埌鍚?*: 璋冪敤 `client.session.delete({ path: { id: toolSessionId } })`銆?

---

### 5. invoke.permission_reply 鈥?鍥炲鏉冮檺璇锋眰

```json
{
  "type": "invoke",
  "action": "permission_reply",
  "payload": {
    "toolSessionId": "ses_abc",
    "permissionId": "perm_1",
    "response": "once"
  }
}
```

| payload 瀛楁    | 绫诲瀷   | 蹇呭～  | 璇存槑                         |
| --------------- | ------ | :---: | ---------------------------- |
| `toolSessionId` | String |   鉁?  | OpenCode 浼氳瘽 ID             |
| `permissionId`  | String |   鉁?  | 鏉冮檺璇锋眰 ID                  |
| `response`      | String |   鉁?  | `once` / `always` / `reject` |

**PC Agent 鏀跺埌鍚?*: 璋冪敤 `client.postSessionIdPermissionsPermissionId({ body: { response }, path: { id: toolSessionId, permissionID: permissionId } })`銆?

---

### 6. invoke.question_reply 鈥?鍥炵瓟 AI 鎻愰棶

```json
{
  "type": "invoke",
  "action": "question_reply",
  "payload": {
    "toolSessionId": "ses_abc",
    "toolCallId": "call_2",
    "answer": "Vite"
  }
}
```

| payload 瀛楁    | 绫诲瀷   | 蹇呭～  | 璇存槑                        |
| --------------- | ------ | :---: | --------------------------- |
| `toolSessionId` | String |   鉁?  | OpenCode 浼氳瘽 ID            |
| `toolCallId`    | String |   鉁?  | 瀵瑰簲 question 鐨勫伐鍏疯皟鐢?ID |
| `answer`        | String |   鉁?  | 鐢ㄦ埛鐨勫洖绛斿唴瀹?             |

**PC Agent 鏀跺埌鍚?*: 璋冪敤 `client.session.prompt()` 灏嗙瓟妗堜綔涓烘秷鎭彂閫併€?

---

### 7. status_query 鈥?鐘舵€佹煡璇?

```json
{
  "type": "status_query"
}
```

| 瀛楁   | 绫诲瀷   | 蹇呭～  | 璇存槑                  |
| ------ | ------ | :---: | --------------------- |
| `type` | String |   鉁?  | 鍥哄畾 `"status_query"` |

**PC Agent 鏀跺埌鍚?*: 璋冪敤 `client.app.health()` 妫€娴?OpenCode 杩愯鏃讹紝杩斿洖 `status_response`銆?

---

## 浜屻€佷笂琛屾秷鎭紙PC Agent 鈫?Gateway锛?

---

### 1. register 鈥?Agent 娉ㄥ唽

寤鸿仈鎴愬姛鍚?PC Agent 鍙戦€佺殑**绗竴鏉℃秷鎭?*锛屽寘鍚澶囧拰宸ュ叿淇℃伅銆?*蹇呴』鍦?10 绉掑唴鍙戦€?*锛屽惁鍒欒繛鎺ュ皢琚叧闂紙close code 4408锛夈€?

```json
{
  "type": "register",
  "deviceName": "My-MacBook",
  "os": "macOS",
  "toolType": "opencode",
  "toolVersion": "0.5.0",
  "macAddress": "AA:BB:CC:DD:EE:FF"
}
```

| 瀛楁          | 绫诲瀷   | 蹇呭～  | 璇存槑                        |
| ------------- | ------ | :---: | --------------------------- |
| `type`        | String |   鉁?  | 鍥哄畾 `"register"`           |
| `deviceName`  | String |   鉂?  | 璁惧鍚嶇О                    |
| `os`          | String |   鉂?  | 鎿嶄綔绯荤粺                    |
| `toolType`    | String |   鉂?  | 宸ュ叿绫诲瀷锛岄粯璁?`"opencode"` |
| `toolVersion` | String |   鉂?  | 宸ュ叿鐗堟湰                    |
| `macAddress`  | String |   鉂?  | 璁惧 MAC 鍦板潃               |

**Gateway 鏀跺埌鍚?*:

1. 璁惧缁戝畾鏍￠獙锛堝宸插惎鐢級锛氫笌绗笁鏂规湇鍔℃牳楠?AK + MAC + toolType
2. 閲嶅杩炴帴妫€娴嬶細鍚?AK 宸叉湁娲昏穬 session 鈫?鎷掔粷鏂拌繛鎺ワ紙close code 4409锛?
3. 韬唤鎸佷箙鍖栵細鍚?AK + toolType 澶嶇敤宸叉湁璁板綍锛圲PDATE锛夛紝棣栨鍒?INSERT
4. 娉ㄥ唽 WebSocket session 鍒?`EventRelayService`
5. 閫氱煡 Skill Server `agent_online`
6. 鍥炲 `register_ok` 鎴?`register_rejected`

#### register_ok 鈥?娉ㄥ唽鎴愬姛鍝嶅簲

```json
{
  "type": "register_ok"
}
```

#### register_rejected 鈥?娉ㄥ唽鎷掔粷鍝嶅簲

```json
{
  "type": "register_rejected",
  "reason": "duplicate_connection"
}
```

| reason 鍊?              | 鍚箟               | 鍏抽棴鐮?|
| ----------------------- | ------------------ | ------ |
| `duplicate_connection`  | 鍚?AK 宸叉湁娲昏穬杩炴帴 | 4409   |
| `device_binding_failed` | 璁惧缁戝畾鏍￠獙澶辫触   | 4403   |
| `registration_timeout`  | 娉ㄥ唽瓒呮椂           | 4408   |

---

### 2. heartbeat 鈥?蹇冭烦

```json
{
  "type": "heartbeat"
}
```

| 瀛楁   | 绫诲瀷   | 蹇呭～  | 璇存槑               |
| ------ | ------ | :---: | ------------------ |
| `type` | String |   鉁?  | 鍥哄畾 `"heartbeat"` |

**Gateway 鏀跺埌鍚?*: 璋冪敤 `AgentRegistryService.heartbeat()` 鏇存柊 `last_seen_at`銆?

---

### 3. session_created 鈥?浼氳瘽鍒涘缓鎴愬姛

```json
{
  "type": "session_created",
  "welinkSessionId": 42,
  "toolSessionId": "ses_abc"
}
```

| 瀛楁              | 绫诲瀷   | 蹇呭～  | 璇存槑                                   |
| ----------------- | ------ | :---: | -------------------------------------- |
| `type`            | String |   鉁?  | 鍥哄畾 `"session_created"`               |
| `welinkSessionId` | Long |   鉁?  | 鍘熸牱鍥炰紶锛堟潵鑷?invoke.create_session锛?|
| `toolSessionId`   | String |   鉁?  | OpenCode 鍒嗛厤鐨勪細璇?ID                 |

**Gateway 鏀跺埌鍚?*: 閫忎紶缁?Skill Server锛堝眰鈶′笂琛岋級銆?

---

### 4. tool_event 鈥?OpenCode 浜嬩欢閫忎紶

```json
{
  "type": "tool_event",
  "toolSessionId": "ses_abc",
  "event": {
    "type": "message.part.updated",
    "properties": {
      "sessionId": "ses_abc",
      "part": { "type": "text", "text": "濂界殑锛? }
    }
  }
}
```

| 瀛楁            | 绫诲瀷   | 蹇呭～  | 璇存槑                   |
| --------------- | ------ | :---: | ---------------------- |
| `type`          | String |   鉁?  | 鍥哄畾 `"tool_event"`    |
| `toolSessionId` | String |   鉁?  | OpenCode 浼氳瘽 ID       |
| `event`         | Object |   鉁?  | 鍘熷 OpenCode 浜嬩欢瀵硅薄 |

**Gateway 鏀跺埌鍚?*: 鏍规嵁 `toolSessionId` 璺敱锛岄€忎紶缁欏搴?Skill Server銆?

---

### 5. tool_done 鈥?鎵ц瀹屾垚

```json
{
  "type": "tool_done",
  "toolSessionId": "ses_abc",
  "usage": {
    "inputTokens": 5000,
    "outputTokens": 200,
    "reasoningTokens": 800,
    "cost": 0.01
  }
}
```

| 瀛楁            | 绫诲瀷   | 蹇呭～  | 璇存槑               |
| --------------- | ------ | :---: | ------------------ |
| `type`          | String |   鉁?  | 鍥哄畾 `"tool_done"` |
| `toolSessionId` | String |   鉁?  | OpenCode 浼氳瘽 ID   |
| `usage`         | Object |   鉂?  | Token 浣跨敤缁熻     |

---

### 6. tool_error 鈥?鎵ц閿欒

```json
{
  "type": "tool_error",
  "toolSessionId": "ses_abc",
  "error": "session.prompt failed: connection refused"
}
```

| 瀛楁            | 绫诲瀷   | 蹇呭～  | 璇存槑                |
| --------------- | ------ | :---: | ------------------- |
| `type`          | String |   鉁?  | 鍥哄畾 `"tool_error"` |
| `toolSessionId` | String |   鉁?  | OpenCode 浼氳瘽 ID    |
| `error`         | String |   鉁?  | 閿欒鎻忚堪            |

---

### 7. status_response 鈥?鐘舵€佸搷搴?

```json
{
  "type": "status_response",
  "opencodeOnline": true
}
```

| 瀛楁             | 绫诲瀷    | 蹇呭～  | 璇存槑                     |
| ---------------- | ------- | :---: | ------------------------ |
| `type`           | String  |   鉁?  | 鍥哄畾 `"status_response"` |
| `opencodeOnline` | Boolean |   鉁?  | OpenCode 杩愯鏃舵槸鍚﹀湪绾? |

