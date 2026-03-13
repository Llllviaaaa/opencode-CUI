# 灞傗憼 鎺ュ彛鍗忚锛歁iniapp 鈫?Skill Server

> 鐗堟湰锛?.1  
> 鏃ユ湡锛?026-03-11  
> 鐘舵€侊細寰呭疄鐜?

---

## 鍏ㄥ眬绾﹀畾

### ID 鍛藉悕瑙勮寖

| 鍚嶇О              | 璇存槑                           |
| ----------------- | ------------------------------ |
| `welinkSessionId` | Skill Server 鍐呴儴鍒嗛厤鐨勪細璇?ID |
| `toolSessionId`   | OpenCode SDK 鍒嗛厤鐨勪細璇?ID     |

### 浼氳瘽鐘舵€佹灇涓?

| 鐘舵€?    | 璇存槑                             |
| -------- | -------------------------------- |
| `ACTIVE` | 浼氳瘽娲昏穬涓紝鍙敹鍙戞秷鎭?          |
| `IDLE`   | 浼氳瘽瓒呮椂闂茬疆锛岀敱瀹氭椂浠诲姟鑷姩鏍囪 |
| `CLOSED` | 浼氳瘽宸插叧闂紝涓嶅彲鍐嶅彂閫佹秷鎭?      |

### REST API 鍝嶅簲鏍煎紡

鎵€鏈?REST 鎺ュ彛缁熶竴杩斿洖 HTTP `200 OK`锛屽搷搴斾綋缁撴瀯濡備笅锛?

```json
{
  "code": 0,
  "errormsg": "",
  "data": { ... }
}
```

| 瀛楁       | 绫诲瀷          | 璇存槑                                   |
| ---------- | ------------- | -------------------------------------- |
| `code`     | Integer       | 缁撴灉杩斿洖鐮侊紝`0` 琛ㄧず鎴愬姛锛岄潪闆惰〃绀洪敊璇?|
| `errormsg` | String        | 閿欒淇℃伅锛屾垚鍔熸椂涓虹┖瀛楃涓?            |
| `data`     | Object / null | 姝ｅ父杩斿洖鍐呭锛岄敊璇椂涓?`null`          |

---

## 涓€銆丷EST API

> 鍩虹璺緞锛歚/api/skill`  
> 璁よ瘉鏂瑰紡锛欳ookie锛堟墍鏈夋帴鍙ｄ粠 Cookie 涓В鏋?`userId`锛岀被鍨?`String`锛? 
> Content-Type锛歚application/json`

---

### 1. 鍒涘缓浼氳瘽

**POST** `/api/skill/sessions`

#### 璇锋眰

| 瀛楁        | 绫诲瀷   | 蹇呭～  | 璇存槑                                                |
| ----------- | ------ | :---: | --------------------------------------------------- |
| `ak`        | String |   鉁?  | Agent Plugin 瀵瑰簲鐨?Access Key锛岀敤浜庡畾浣?Agent 杩炴帴 |
| `title`     | String |   鉂?  | 浼氳瘽鏍囬锛屼笉濉垯鐢?AI 鑷姩鐢熸垚                      |
| `imGroupId` | String |   鉁?  | 鍏宠仈鐨?IM 缇ょ粍 ID                                   |

```json
{
  "ak": "ak_xxxxxxxx",
  "title": "甯垜鍒涘缓涓€涓猂eact椤圭洰",
  "imGroupId": "group_abc123"
}
```

#### 鍝嶅簲

```json
{
  "code": 0,
  "errormsg": "",
  "data": {
    "welinkSessionId": 42,
    "userId": "10001",
    "ak": "ak_xxxxxxxx",
    "title": "甯垜鍒涘缓涓€涓猂eact椤圭洰",
    "imGroupId": "group_abc123",
    "status": "ACTIVE",
    "toolSessionId": null,
    "createdAt": "2026-03-08T00:15:00",
    "updatedAt": "2026-03-08T00:15:00"
  }
}
```

| data 瀛楁         | 绫诲瀷   | 璇存槑                                           |
| ----------------- | ------ | ---------------------------------------------- |
| `welinkSessionId` | Long   | 浼氳瘽 ID                                        |
| `userId`          | String | 鐢ㄦ埛 ID锛堜粠 Cookie 瑙ｆ瀽锛?                     |
| `ak`              | String | Access Key                                     |
| `title`           | String | 浼氳瘽鏍囬                                       |
| `imGroupId`       | String | IM 缇ょ粍 ID                                     |
| `status`          | String | 浼氳瘽鐘舵€侊細`ACTIVE`                             |
| `toolSessionId`   | String | OpenCode Session ID锛屽垱寤烘椂涓?`null`锛屽紓姝ュ～鍏?|
| `createdAt`       | String | 鍒涘缓鏃堕棿锛孖SO-8601                             |
| `updatedAt`       | String | 鏇存柊鏃堕棿锛孖SO-8601                             |

#### 鍐呴儴鍓綔鐢?

1. 鍒涘缓 `SkillSession` 璁板綍锛坄status=ACTIVE`锛宍toolSessionId=null`锛?
2. 璁㈤槄 Redis `session:{welinkSessionId}` 骞挎挱棰戦亾
3. 鑻ラ€氳繃 `ak` 鎵惧埌鍦ㄧ嚎 Agent 鈫?鍚?Gateway 鍙戦€?`invoke.create_session`

---

### 2. 鍙戦€佹秷鎭?

**POST** `/api/skill/sessions/{welinkSessionId}/messages`

#### 璺緞鍙傛暟

| 鍙傛暟              | 绫诲瀷 | 璇存槑    |
| ----------------- | ---- | ------- |
| `welinkSessionId` | Long | 浼氳瘽 ID |

#### 璇锋眰

| 瀛楁         | 绫诲瀷   | 蹇呭～  | 璇存槑                                                           |
| ------------ | ------ | :---: | -------------------------------------------------------------- |
| `content`    | String |   鉁?  | 鐢ㄦ埛娑堟伅鏂囨湰                                                   |
| `toolCallId` | String |   鉂?  | 鍥炵瓟 AI question 鏃舵惡甯﹀搴旂殑宸ュ叿璋冪敤 ID銆備笉甯﹀垯鎸夋櫘閫氭秷鎭鐞?|

```json
{
  "content": "甯垜鍒涘缓涓€涓猂eact椤圭洰"
}
```

鍥炵瓟 AI question 鏃讹細

```json
{
  "content": "Vite",
  "toolCallId": "call_2"
}
```

#### 鍝嶅簲

```json
{
  "code": 0,
  "errormsg": "",
  "data": {
    "id": 101,
    "welinkSessionId": 42,
    "userId": "10001",
    "role": "user",
    "content": "甯垜鍒涘缓涓€涓猂eact椤圭洰",
    "messageSeq": 3,
    "createdAt": "2026-03-08T00:16:00"
  }
}
```

| data 瀛楁         | 绫诲瀷    | 璇存槑                   |
| ----------------- | ------- | ---------------------- |
| `id`              | Long    | 娑堟伅 ID                |
| `welinkSessionId` | Long    | 鎵€灞炰細璇?ID            |
| `userId`          | String  | 鍙戦€佺敤鎴?ID            |
| `role`            | String  | 瑙掕壊锛屽浐瀹?`"user"`    |
| `content`         | String  | 娑堟伅鍐呭               |
| `messageSeq`      | Integer | 璇ユ秷鎭湪浼氳瘽鍐呯殑椤哄簭鍙?|
| `createdAt`       | String  | 鍒涘缓鏃堕棿锛孖SO-8601     |

#### 鍐呴儴鍓綔鐢?

1. 浠?Cookie 瑙ｆ瀽 `userId`锛屾寔涔呭寲 user message 鍒?MySQL锛堝惈 `userId`锛?
2. 鏌ヨ session 璁板綍鑾峰彇 `toolSessionId`
3. 鏍规嵁鏄惁鎼哄甫 `toolCallId` 鍒嗘敮澶勭悊锛?
   - **鏃?`toolCallId`**锛氭瀯寤?`invoke.chat` payload锛坄{ toolSessionId, text: content }`锛夊彂閫佽嚦 Gateway
   - **鏈?`toolCallId`**锛氭瀯寤?`invoke.question_reply` payload锛坄{ toolSessionId, toolCallId, answer: content }`锛夊彂閫佽嚦 Gateway

---

### 3. 鍥炲鏉冮檺璇锋眰

**POST** `/api/skill/sessions/{welinkSessionId}/permissions/{permId}`

#### 璺緞鍙傛暟

| 鍙傛暟              | 绫诲瀷   | 璇存槑        |
| ----------------- | ------ | ----------- |
| `welinkSessionId` | Long   | 浼氳瘽 ID     |
| `permId`          | String | 鏉冮檺璇锋眰 ID |

#### 璇锋眰

| 瀛楁       | 绫诲瀷   | 蹇呭～  | 鍊煎煙                         | 璇存槑                           |
| ---------- | ------ | :---: | ---------------------------- | ------------------------------ |
| `response` | String |   鉁?  | `once` / `always` / `reject` | 鐩存帴浣跨敤 OpenCode SDK 瀹氫箟鐨勫€?|

鍚箟锛?

- `once` 鈥?浠呮湰娆″厑璁?
- `always` 鈥?姘镐箙鍏佽锛堝悓绫绘搷浣滀笉鍐嶈闂級
- `reject` 鈥?鎷掔粷

```json
{
  "response": "once"
}
```

#### 鍝嶅簲

```json
{
  "code": 0,
  "errormsg": "",
  "data": {
    "welinkSessionId": 42,
    "permissionId": "perm_1",
    "response": "once"
  }
}
```

| data 瀛楁         | 绫诲瀷   | 璇存槑        |
| ----------------- | ------ | ----------- |
| `welinkSessionId` | Long   | 浼氳瘽 ID     |
| `permissionId`    | String | 鏉冮檺璇锋眰 ID |
| `response`        | String | 鍥炲鍊?     |

#### 鍐呴儴鍓綔鐢?

1. 鏋勫缓 `invoke.permission_reply` payload锛坄{ toolSessionId, permissionId, response }`锛夊彂閫佽嚦 Gateway
2. PC Agent 鐩存帴灏?`response` 鍘熷€奸€忎紶缁?OpenCode SDK锛屾棤闇€杞崲

---

### 4. 涓浼氳瘽鎵ц

**POST** `/api/skill/sessions/{welinkSessionId}/abort`

#### 璺緞鍙傛暟

| 鍙傛暟              | 绫诲瀷 | 璇存槑    |
| ----------------- | ---- | ------- |
| `welinkSessionId` | Long | 浼氳瘽 ID |

#### 璇锋眰

鏃犺姹備綋銆?

#### 鍝嶅簲

```json
{
  "code": 0,
  "errormsg": "",
  "data": {
    "welinkSessionId": 42,
    "status": "aborted"
  }
}
```

#### 鍐呴儴鍓綔鐢?

1. 鍚?Gateway 鍙戦€?`invoke.abort_session`锛坄{ toolSessionId }`锛?
2. PC Agent 璋冪敤 `session.abort()` 涓褰撳墠鎵ц
3. Skill Session 鐘舵€?*涓嶅彉**锛堜粛涓?`ACTIVE`锛夛紝鍙互缁х画鍙戞秷鎭?

---

### 5. 鍏抽棴骞跺垹闄や細璇?

**DELETE** `/api/skill/sessions/{welinkSessionId}`

#### 璺緞鍙傛暟

| 鍙傛暟              | 绫诲瀷 | 璇存槑    |
| ----------------- | ---- | ------- |
| `welinkSessionId` | Long | 浼氳瘽 ID |

#### 璇锋眰

鏃犺姹備綋銆?

#### 鍝嶅簲

```json
{
  "code": 0,
  "errormsg": "",
  "data": {
    "welinkSessionId": 42,
    "status": "closed"
  }
}
```

#### 鍐呴儴鍓綔鐢?

1. 鍚?Gateway 鍙戦€?`invoke.close_session`锛坄{ toolSessionId }`锛?
2. PC Agent 璋冪敤 `session.delete()` 鍒犻櫎 OpenCode session
3. 鏇存柊 `SkillSession.status = CLOSED`
4. 鍙栨秷璁㈤槄 Redis `session:{welinkSessionId}` 骞挎挱棰戦亾

---

### 6. 鏌ヨ鍘嗗彶娑堟伅

**GET** `/api/skill/sessions/{welinkSessionId}/messages`

#### 璺緞鍙傛暟

| 鍙傛暟              | 绫诲瀷 | 璇存槑    |
| ----------------- | ---- | ------- |
| `welinkSessionId` | Long | 浼氳瘽 ID |

#### 鏌ヨ鍙傛暟

| 鍙傛暟   | 绫诲瀷 | 榛樿鍊?| 璇存槑     |
| ------ | ---- | :----: | -------- |
| `page` | int  |   0    | 椤电爜     |
| `size` | int  |   50   | 姣忛〉鏉℃暟 |

#### 鍝嶅簲

```json
{
  "code": 0,
  "errormsg": "",
  "data": {
    "content": [
      {
        "id": 101,
        "welinkSessionId": 42,
        "userId": "10001",
        "role": "user",
        "content": "甯垜鍒涘缓涓€涓猂eact椤圭洰",
        "messageSeq": 1,
        "parts": [],
        "createdAt": "2026-03-08T00:15:00"
      },
      {
        "id": 102,
        "welinkSessionId": 42,
        "userId": null,
        "role": "assistant",
        "content": "濂界殑锛屾垜鏉ュ府浣犲垱寤?..",
        "messageSeq": 2,
        "parts": [
          {
            "partId": "p_1",
            "partSeq": 1,
            "type": "text",
            "content": "濂界殑锛屾垜鏉ュ府浣犲垱寤?.."
          },
          {
            "partId": "p_2",
            "partSeq": 2,
            "type": "tool",
            "toolName": "bash",
            "toolStatus": "completed",
            "toolInput": { "command": "npx create-vite" },
            "toolOutput": "Done."
          }
        ],
        "createdAt": "2026-03-08T00:15:05"
      }
    ],
    "page": 0,
    "size": 50,
    "total": 2
  }
}
```

---

### 7. 鏌ヨ浼氳瘽鍒楄〃

**GET** `/api/skill/sessions`

#### 鏌ヨ鍙傛暟

| 鍙傛暟        | 绫诲瀷   | 蹇呭～  | 榛樿鍊?| 璇存槑                                        |
| ----------- | ------ | :---: | :----: | ------------------------------------------- |
| `imGroupId` | String |   鉂?  |   鈥?   | 鎸?IM 缇ょ粍 ID 杩囨护                          |
| `ak`        | String |   鉂?  |   鈥?   | 鎸?Access Key 杩囨护                          |
| `page`      | int    |   鉂?  |   0    | 椤电爜                                        |
| `size`      | int    |   鉂?  |   20   | 姣忛〉鏉℃暟                                    |
| `status`    | String |   鉂?  |   鈥?   | 鍙€夎繃婊わ細`ACTIVE` / `CLOSED`锛屼笉浼犺繑鍥炲叏閮?|

#### 鍝嶅簲

```json
{
  "code": 0,
  "errormsg": "",
  "data": {
    "content": [
      {
        "welinkSessionId": 42,
        "userId": "10001",
        "ak": "ak_xxxxxxxx",
        "title": "甯垜鍒涘缓涓€涓猂eact椤圭洰",
        "imGroupId": "group_abc123",
        "status": "ACTIVE",
        "toolSessionId": "ses_abc",
        "createdAt": "2026-03-08T00:15:00",
        "updatedAt": "2026-03-08T00:16:00"
      }
    ],
    "page": 0,
    "size": 20,
    "total": 1
  }
}
```

---

### 8. 鏌ヨ鍗曚釜浼氳瘽

**GET** `/api/skill/sessions/{welinkSessionId}`

#### 璺緞鍙傛暟

| 鍙傛暟              | 绫诲瀷 | 璇存槑    |
| ----------------- | ---- | ------- |
| `welinkSessionId` | Long | 浼氳瘽 ID |

#### 鍝嶅簲

```json
{
  "code": 0,
  "errormsg": "",
  "data": {
    "welinkSessionId": 42,
    "userId": "10001",
    "ak": "ak_xxxxxxxx",
    "title": "甯垜鍒涘缓涓€涓猂eact椤圭洰",
    "imGroupId": "group_abc123",
    "status": "ACTIVE",
    "toolSessionId": "ses_abc",
    "createdAt": "2026-03-08T00:15:00",
    "updatedAt": "2026-03-08T00:16:00"
  }
}
```

---

### 9. 鍙戦€佸唴瀹瑰埌 IM 缇よ亰

**POST** `/api/skill/sessions/{welinkSessionId}/send-to-im`

灏?AI 鍥炲鐨勬枃鏈唴瀹硅浆鍙戝埌鍏宠仈鐨?IM 缇ょ粍銆?

#### 璺緞鍙傛暟

| 鍙傛暟              | 绫诲瀷 | 璇存槑    |
| ----------------- | ---- | ------- |
| `welinkSessionId` | Long | 浼氳瘽 ID |

#### 璇锋眰

```json
{
  "content": "杩欐槸 AI 鐢熸垚鐨勪唬鐮佺墖娈?..",
  "chatId": "group_abc123"
}
```

| 瀛楁      | 绫诲瀷   | 蹇呭～  | 璇存槑                                             |
| --------- | ------ | :---: | ------------------------------------------------ |
| `content` | String |   鉁?  | 瑕佸彂閫佺殑鏂囨湰鍐呭                                 |
| `chatId`  | String |   鉂?  | 鐩爣 IM 缇ょ粍 ID锛屼笉浼犲垯浠庝細璇濈殑 `imGroupId` 鑾峰彇 |

#### 鍝嶅簲

```json
{
  "code": 0,
  "errormsg": "",
  "data": {
    "success": true
  }
}
```

---

### 10. 鏌ヨ鍦ㄧ嚎 Agent 鍒楄〃

**GET** `/api/skill/agents`

鏌ヨ褰撳墠鐢ㄦ埛鍚嶄笅鍦ㄧ嚎鐨?Agent 鍒楄〃銆?
Skill Server 浼氫粠 Cookie 瑙ｆ瀽 `String` 绫诲瀷鐨?`userId`锛屽苟浠ｇ悊鍒?Gateway 鐨?`/api/gateway/agents?userId=<string>`銆?

#### 璇锋眰

鏃犺姹備綋銆備粠 Cookie 瑙ｆ瀽 `userId`锛坄String`锛夛紝骞堕€忎紶涓?Gateway 鐨?`userId` 鏌ヨ鍙傛暟銆?

#### 鍝嶅簲

```json
{
  "code": 0,
  "errormsg": "",
  "data": [
    {
      "ak": "ak_xxxxxxxx",
      "akId": "ak_xxxxxxxx",
      "toolType": "OPENCODE",
      "toolVersion": "1.0.0",
      "deviceName": "MyPC",
      "os": "WINDOWS"
    }
  ]
}
```

| data[] 瀛楁   | 绫诲瀷   | 璇存槑                  |
| ------------- | ------ | --------------------- |
| `ak`          | String | Agent 鐨?Access Key   |
| `akId`        | String | 鍚?`ak`锛屽墠绔吋瀹瑰瓧娈?|
| `toolType`    | String | 宸ュ叿绫诲瀷              |
| `toolVersion` | String | 宸ュ叿鐗堟湰鍙?           |
| `deviceName`  | String | 璁惧鍚嶇О              |
| `os`          | String | 鎿嶄綔绯荤粺              |

---

## 浜屻€乄ebSocket 瀹炴椂娴佸崗璁?

### 杩炴帴淇℃伅

| 椤圭洰         | 鍊?                                                                 |
| ------------ | ------------------------------------------------------------------- |
| **绔偣**     | `ws://host/ws/skill/stream`                                         |
| **璁よ瘉**     | Cookie 鈫?瑙ｆ瀽 `userId`锛圫tring锛?                                   |
| **鎺ㄩ€佺瓥鐣?* | 鑷姩鎺ㄩ€佽鐢ㄦ埛鎵€鏈?ACTIVE 浼氳瘽鐨勪簨浠讹紝鍓嶇鎸?`welinkSessionId` 鍒嗘祦 |
| **浼犺緭鏍煎紡** | JSON                                                                |
| **鏂瑰悜**     | 鏈嶅姟绔?鈫?瀹㈡埛绔紙鍗曞悜鎺ㄩ€侊級                                         |

---

### 鍏叡瀛楁

#### 浼犺緭灞傦紙鎵€鏈夋秷鎭兘鏈夛級

| 瀛楁              | 绫诲瀷    | 蹇呭～  | 璇存槑                                         |
| ----------------- | ------- | :---: | -------------------------------------------- |
| `type`            | String  |   鉁?  | 娑堟伅绫诲瀷鏍囪瘑                                 |
| `seq`             | Integer |   鉁?  | 浼犺緭搴忓彿锛屽崟璋冮€掑锛岀敤浜庢帓搴忋€佸幓閲嶃€佷涪鍖呮娴?|
| `welinkSessionId` | Long  |   鉁?  | 浼氳瘽 ID锛屽墠绔嵁姝ゅ垎娴佸埌瀵瑰簲浼氳瘽              |
| `emittedAt`       | String  |   鉁?  | 浜嬩欢鍙戝嚭鏃堕棿锛孖SO-8601                       |
| `raw`             | Object  |   鉂?  | 鍘熷 OpenCode 浜嬩欢锛屼粎璋冭瘯鐢?                |

#### 娑堟伅灞傦紙褰掑睘鍒版煇鏉¤亰澶╂皵娉＄殑浜嬩欢棰濆鎼哄甫锛?

| 瀛楁         | 绫诲瀷    | 蹇呭～  | 璇存槑                                                   |
| ------------ | ------- | :---: | ------------------------------------------------------ |
| `messageId`  | String  |   鉁?  | Skill Server 鍒嗛厤鐨勭ǔ瀹氭秷鎭?ID锛屽悓涓€姘旀场鐢熷懡鍛ㄦ湡鍐呬笉鍙?|
| `messageSeq` | Integer |   鉁?  | 浼氳瘽鍐呮秷鎭『搴忥紝涓庡巻鍙叉秷鎭?API 杩斿洖鐨勯『搴忓榻?         |
| `role`       | String  |   鉁?  | 娑堟伅瑙掕壊锛歚user` / `assistant` / `system` / `tool`     |

#### Part 灞傦紙褰掑睘鍒版秷鎭腑鏌愪釜閮ㄤ欢鐨勪簨浠堕澶栨惡甯︼級

| 瀛楁      | 绫诲瀷    | 蹇呭～  | 璇存槑                                     |
| --------- | ------- | :---: | ---------------------------------------- |
| `partId`  | String  |   鉁?  | Part 鍞竴 ID锛岀敤浜庡閲忔洿鏂板畾浣?          |
| `partSeq` | Integer |   鉂?  | Part 鍦ㄦ秷鎭唴鐨勯『搴忥紝鐢ㄤ簬鎭㈠/鍥炴斁鏃舵帓搴?|

---

### 娑堟伅绫诲瀷鎬昏

| 鍒嗙被 | type               |    瀛楁灞傜骇    | 璇存槑                     |
| ---- | ------------------ | :------------: | ------------------------ |
| 鍐呭 | `text.delta`       | 浼犺緭+娑堟伅+Part | AI 鏂囨湰娴佸紡杩藉姞          |
| 鍐呭 | `text.done`        | 浼犺緭+娑堟伅+Part | AI 鏂囨湰瀹屾垚              |
| 鍐呭 | `thinking.delta`   | 浼犺緭+娑堟伅+Part | 鎬濈淮閾炬祦寮忚拷鍔?          |
| 鍐呭 | `thinking.done`    | 浼犺緭+娑堟伅+Part | 鎬濈淮閾惧畬鎴?              |
| 鍐呭 | `tool.update`      | 浼犺緭+娑堟伅+Part | 宸ュ叿璋冪敤鐘舵€佹洿鏂?        |
| 鍐呭 | `question`         | 浼犺緭+娑堟伅+Part | AI 鎻愰棶浜や簰              |
| 鍐呭 | `file`             | 浼犺緭+娑堟伅+Part | 鏂囦欢/鍥剧墖闄勪欢            |
| 鐘舵€?| `step.start`       |   浼犺緭+娑堟伅    | 鎺ㄧ悊姝ラ寮€濮?            |
| 鐘舵€?| `step.done`        |   浼犺緭+娑堟伅    | 鎺ㄧ悊姝ラ缁撴潫             |
| 鐘舵€?| `session.status`   |     浠呬紶杈?    | 浼氳瘽鐘舵€佸彉鍖?            |
| 鐘舵€?| `session.title`    |     浠呬紶杈?    | 浼氳瘽鏍囬鍙樺寲             |
| 鐘舵€?| `session.error`    |     浠呬紶杈?    | 浼氳瘽绾ч敊璇?              |
| 浜や簰 | `permission.ask`   |   浼犺緭+娑堟伅    | 鏉冮檺璇锋眰                 |
| 浜や簰 | `permission.reply` |   浼犺緭+娑堟伅    | 鏉冮檺鍝嶅簲缁撴灉             |
| 绯荤粺 | `agent.online`     |     浠呬紶杈?    | Agent 涓婄嚎               |
| 绯荤粺 | `agent.offline`    |     浠呬紶杈?    | Agent 涓嬬嚎               |
| 绯荤粺 | `error`            |     浠呬紶杈?    | 闈炰細璇濈骇閿欒             |
| 鎭㈠ | `snapshot`         |     浠呬紶杈?    | 鏂嚎鎭㈠锛氬凡瀹屾垚娑堟伅蹇収 |
| 鎭㈠ | `streaming`        |     浠呬紶杈?    | 鏂嚎鎭㈠锛氳繘琛屼腑鐨勬祦娑堟伅 |

---

### 娑堟伅绫诲瀷璇︾粏瀹氫箟

#### `text.delta` 鈥?AI 鏂囨湰娴佸紡杩藉姞

`content` 涓哄閲忔枃鏈紝鍓嶇鎷兼帴鍒拌 `partId` 宸叉湁鍐呭涔嬪悗銆?

```json
{
  "type": "text.delta",
  "seq": 4,
  "welinkSessionId": 42,
  "emittedAt": "2026-03-08T00:16:01.123Z",
  "messageId": "m_2",
  "messageSeq": 2,
  "role": "assistant",
  "partId": "p_1",
  "partSeq": 1,
  "content": "濂界殑锛?
}
```

| 鐗规湁瀛楁  | 绫诲瀷   | 璇存槑         |
| --------- | ------ | ------------ |
| `content` | String | 澧為噺鏂囨湰鐗囨 |

---

#### `text.done` 鈥?AI 鏂囨湰瀹屾垚

`content` 涓鸿 Part 鐨勬渶缁堝畬鏁存枃鏈€?

```json
{
  "type": "text.done",
  "seq": 8,
  "welinkSessionId": 42,
  "emittedAt": "2026-03-08T00:16:05.000Z",
  "messageId": "m_2",
  "messageSeq": 2,
  "role": "assistant",
  "partId": "p_1",
  "partSeq": 1,
  "content": "濂界殑锛屾垜鏉ュ府浣犲垱寤轰竴涓猂eact椤圭洰銆?
}
```

| 鐗规湁瀛楁  | 绫诲瀷   | 璇存槑                   |
| --------- | ------ | ---------------------- |
| `content` | String | 璇?Part 鐨勬渶缁堝畬鏁存枃鏈?|

---

#### `thinking.delta` 鈥?鎬濈淮閾炬祦寮忚拷鍔?

鍓嶇娓叉煋涓哄彲鎶樺彔鐨?鎬濊€冭繃绋?鍖哄煙銆俙content` 涓哄閲忔枃鏈€?

```json
{
  "type": "thinking.delta",
  "seq": 3,
  "welinkSessionId": 42,
  "emittedAt": "2026-03-08T00:16:00.500Z",
  "messageId": "m_2",
  "messageSeq": 2,
  "role": "assistant",
  "partId": "p_0",
  "partSeq": 0,
  "content": "鐢ㄦ埛闇€瑕佸垱寤篟eact椤圭洰锛屾垜搴旇..."
}
```

| 鐗规湁瀛楁  | 绫诲瀷   | 璇存槑           |
| --------- | ------ | -------------- |
| `content` | String | 澧為噺鎬濈淮閾炬枃鏈?|

---

#### `thinking.done` 鈥?鎬濈淮閾惧畬鎴?

```json
{
  "type": "thinking.done",
  "seq": 5,
  "welinkSessionId": 42,
  "emittedAt": "2026-03-08T00:16:01.500Z",
  "messageId": "m_2",
  "messageSeq": 2,
  "role": "assistant",
  "partId": "p_0",
  "partSeq": 0,
  "content": "鐢ㄦ埛闇€瑕佸垱寤篟eact椤圭洰锛屾垜搴旇浣跨敤Vite鏉ュ垵濮嬪寲銆?
}
```

| 鐗规湁瀛楁  | 绫诲瀷   | 璇存槑                         |
| --------- | ------ | ---------------------------- |
| `content` | String | 璇?Part 鐨勬渶缁堝畬鏁存€濈淮閾炬枃鏈?|

---

#### `tool.update` 鈥?宸ュ叿璋冪敤鐘舵€佹洿鏂?

ToolPart 鐘舵€佹満锛歚pending 鈫?running 鈫?completed / error`銆?

```json
{
  "type": "tool.update",
  "seq": 6,
  "welinkSessionId": 42,
  "emittedAt": "2026-03-08T00:16:02.000Z",
  "messageId": "m_2",
  "messageSeq": 2,
  "role": "assistant",
  "partId": "p_3",
  "partSeq": 3,
  "toolName": "bash",
  "toolCallId": "call_1",
  "status": "completed",
  "input": { "command": "npx create-vite my-app" },
  "output": "Scaffolding project in ./my-app...\nDone.",
  "title": "Execute bash command"
}
```

| 鐗规湁瀛楁     | 绫诲瀷   | 蹇呭～  | 璇存槑                                                |
| ------------ | ------ | :---: | --------------------------------------------------- |
| `toolName`   | String |   鉁?  | 宸ュ叿鍚嶇О锛坄bash` / `edit` / `read` 绛夛級             |
| `toolCallId` | String |   鉂?  | 宸ュ叿璋冪敤 ID                                         |
| `status`     | String |   鉁?  | 鐘舵€侊細`pending` / `running` / `completed` / `error` |
| `input`      | Object |   鉂?  | 宸ュ叿杈撳叆鍙傛暟                                        |
| `output`     | String |   鉂?  | 宸ュ叿杈撳嚭缁撴灉锛坄completed` 鏃讹級                      |
| `error`      | String |   鉂?  | 閿欒淇℃伅锛坄error` 鏃讹級                              |
| `title`      | String |   鉂?  | 宸ュ叿鎵ц鎽樿鏍囬                                    |

---

#### `question` 鈥?AI 鎻愰棶浜や簰

AI 閫氳繃鍐呯疆 `question` 宸ュ叿鍚戠敤鎴锋彁闂紝闃诲绛夊緟鐢ㄦ埛閫夋嫨鎴栬緭鍏ャ€?

```json
{
  "type": "question",
  "seq": 5,
  "welinkSessionId": 42,
  "emittedAt": "2026-03-08T00:16:02.500Z",
  "messageId": "m_2",
  "messageSeq": 2,
  "role": "assistant",
  "partId": "p_2",
  "partSeq": 2,
  "toolName": "question",
  "toolCallId": "call_2",
  "status": "running",
  "header": "椤圭洰閰嶇疆",
  "question": "閫夋嫨妯℃澘妗嗘灦",
  "options": ["Vite", "CRA", "Next.js"]
}
```

| 鐗规湁瀛楁     | 绫诲瀷     | 蹇呭～  | 璇存槑                               |
| ------------ | -------- | :---: | ---------------------------------- |
| `toolName`   | String   |   鉁?  | 鍥哄畾 `"question"`                  |
| `toolCallId` | String   |   鉂?  | 宸ュ叿璋冪敤 ID                        |
| `status`     | String   |   鉁?  | 鍥哄畾 `"running"`锛堢瓑寰呯敤鎴峰洖绛旓級   |
| `header`     | String   |   鉂?  | 闂鍒嗙粍鏍囬                       |
| `question`   | String   |   鉁?  | 闂姝ｆ枃                           |
| `options`    | String[] |   鉂?  | 棰勮閫夐」鍒楄〃锛岀敤鎴峰彲閫夋嫨鎴栬嚜鐢辫緭鍏?|

鐢ㄦ埛鍥炵瓟鏂瑰紡锛氳皟鐢?REST API `POST /api/skill/sessions/{welinkSessionId}/messages` 鍙戦€佸洖绛旀枃鏈€?

---

#### `file` 鈥?鏂囦欢/鍥剧墖闄勪欢

```json
{
  "type": "file",
  "seq": 10,
  "welinkSessionId": 42,
  "emittedAt": "2026-03-08T00:16:06.000Z",
  "messageId": "m_2",
  "messageSeq": 2,
  "role": "assistant",
  "partId": "p_5",
  "partSeq": 5,
  "fileName": "screenshot.png",
  "fileUrl": "https://...",
  "fileMime": "image/png"
}
```

| 鐗规湁瀛楁   | 绫诲瀷   | 蹇呭～  | 璇存槑         |
| ---------- | ------ | :---: | ------------ |
| `fileName` | String |   鉂?  | 鏂囦欢鍚?      |
| `fileUrl`  | String |   鉁?  | 鏂囦欢璁块棶 URL |
| `fileMime` | String |   鉂?  | MIME 绫诲瀷    |

---

#### `step.start` 鈥?鎺ㄧ悊姝ラ寮€濮?

AI 寮€濮嬩竴杞帹鐞嗘楠ゃ€?

```json
{
  "type": "step.start",
  "seq": 2,
  "welinkSessionId": 42,
  "emittedAt": "2026-03-08T00:16:00.000Z",
  "messageId": "m_2",
  "messageSeq": 2,
  "role": "assistant"
}
```

鏃犵壒鏈夊瓧娈点€?

---

#### `step.done` 鈥?鎺ㄧ悊姝ラ缁撴潫

涓€杞帹鐞嗘楠ゅ畬鎴愶紝鍖呭惈 token 缁熻銆?

```json
{
  "type": "step.done",
  "seq": 9,
  "welinkSessionId": 42,
  "emittedAt": "2026-03-08T00:16:05.500Z",
  "messageId": "m_2",
  "messageSeq": 2,
  "role": "assistant",
  "tokens": {
    "input": 5000,
    "output": 200,
    "reasoning": 800,
    "cache": { "read": 100, "write": 50 }
  },
  "cost": 0.01,
  "reason": "stop"
}
```

| 鐗规湁瀛楁           | 绫诲瀷    | 蹇呭～  | 璇存槑                             |
| ------------------ | ------- | :---: | -------------------------------- |
| `tokens`           | Object  |   鉂?  | Token 浣跨敤缁熻                   |
| `tokens.input`     | Integer |   鉂?  | 杈撳叆 token 鏁?                   |
| `tokens.output`    | Integer |   鉂?  | 杈撳嚭 token 鏁?                   |
| `tokens.reasoning` | Integer |   鉂?  | 鎺ㄧ悊 token 鏁?                   |
| `tokens.cache`     | Object  |   鉂?  | 缂撳瓨鍛戒腑缁熻                     |
| `cost`             | Number  |   鉂?  | 鏈楠よ垂鐢?                      |
| `reason`           | String  |   鉂?  | 缁撴潫鍘熷洜锛坄stop` / `length` 绛夛級 |

---

#### `session.status` 鈥?浼氳瘽鐘舵€佸彉鍖?

```json
{
  "type": "session.status",
  "seq": 1,
  "welinkSessionId": 42,
  "emittedAt": "2026-03-08T00:16:00.000Z",
  "sessionStatus": "busy"
}
```

| 鐗规湁瀛楁        | 绫诲瀷   | 蹇呭～  | 璇存槑                                                    |
| --------------- | ------ | :---: | ------------------------------------------------------- |
| `sessionStatus` | String |   鉁?  | `busy` 鈥?AI 姝ｅ湪鎺ㄧ悊 / `idle` 鈥?绌洪棽 / `retry` 鈥?閲嶈瘯涓?|

---

#### `session.title` 鈥?浼氳瘽鏍囬鍙樺寲

AI 鑷姩涓轰細璇濈敓鎴愭垨鏇存柊鏍囬鏃惰Е鍙戙€?

```json
{
  "type": "session.title",
  "seq": 11,
  "welinkSessionId": 42,
  "emittedAt": "2026-03-08T00:16:10.000Z",
  "title": "React椤圭洰鍒涘缓涓庨厤缃?
}
```

| 鐗规湁瀛楁 | 绫诲瀷   | 蹇呭～  | 璇存槑   |
| -------- | ------ | :---: | ------ |
| `title`  | String |   鉁?  | 鏂版爣棰?|

---

#### `session.error` 鈥?浼氳瘽绾ч敊璇?

```json
{
  "type": "session.error",
  "seq": 12,
  "welinkSessionId": 42,
  "emittedAt": "2026-03-08T00:16:12.000Z",
  "error": "OpenCode runtime connection lost"
}
```

| 鐗规湁瀛楁 | 绫诲瀷   | 蹇呭～  | 璇存槑     |
| -------- | ------ | :---: | -------- |
| `error`  | String |   鉁?  | 閿欒鎻忚堪 |

---

#### `permission.ask` 鈥?鏉冮檺璇锋眰

OpenCode 闇€瑕佹墽琛屽彈闄愭搷浣滐紙濡?shell 鍛戒护銆佹枃浠跺啓鍏ワ級鍓嶅彂鍑哄鎵硅姹傘€?

```json
{
  "type": "permission.ask",
  "seq": 7,
  "welinkSessionId": 42,
  "emittedAt": "2026-03-08T00:16:03.000Z",
  "messageId": "m_2",
  "messageSeq": 2,
  "role": "assistant",
  "permissionId": "perm_1",
  "permType": "bash",
  "title": "Execute shell command",
  "metadata": {
    "command": "npx create-vite my-app"
  }
}
```

| 鐗规湁瀛楁       | 绫诲瀷   | 蹇呭～  | 璇存槑                                 |
| -------------- | ------ | :---: | ------------------------------------ |
| `permissionId` | String |   鉁?  | 鏉冮檺璇锋眰鍞竴 ID                      |
| `permType`     | String |   鉂?  | 鏉冮檺绫诲瀷锛坄bash` / `file_write` 绛夛級 |
| `title`        | String |   鉂?  | 鎿嶄綔鎽樿                             |
| `metadata`     | Object |   鉂?  | 鎿嶄綔璇︽儏锛堝懡浠ゅ唴瀹广€佹枃浠惰矾寰勭瓑锛?    |

鐢ㄦ埛鍥炲鏂瑰紡锛氳皟鐢?REST API `POST /api/skill/sessions/{welinkSessionId}/permissions/{permId}`銆?

---

#### `permission.reply` 鈥?鏉冮檺鍝嶅簲缁撴灉

鏉冮檺璇锋眰琚鐞嗗悗鐨勭粨鏋滈€氱煡銆?

```json
{
  "type": "permission.reply",
  "seq": 8,
  "welinkSessionId": 42,
  "emittedAt": "2026-03-08T00:16:04.000Z",
  "messageId": "m_2",
  "messageSeq": 2,
  "role": "assistant",
  "permissionId": "perm_1",
  "response": "once"
}
```

| 鐗规湁瀛楁       | 绫诲瀷   | 蹇呭～  | 璇存槑                                 |
| -------------- | ------ | :---: | ------------------------------------ |
| `permissionId` | String |   鉁?  | 鏉冮檺璇锋眰 ID                          |
| `response`     | String |   鉁?  | 鍥炲鍊硷細`once` / `always` / `reject` |

---

#### `agent.online` 鈥?Agent 涓婄嚎

鍏宠仈鐨?PC Agent 寤虹珛杩炴帴銆?

```json
{
  "type": "agent.online",
  "seq": 0,
  "welinkSessionId": 42,
  "emittedAt": "2026-03-08T00:14:00.000Z"
}
```

鏃犵壒鏈夊瓧娈点€?

---

#### `agent.offline` 鈥?Agent 涓嬬嚎

鍏宠仈鐨?PC Agent 鏂紑杩炴帴銆?

```json
{
  "type": "agent.offline",
  "seq": 13,
  "welinkSessionId": 42,
  "emittedAt": "2026-03-08T00:20:00.000Z"
}
```

鏃犵壒鏈夊瓧娈点€?

---

#### `error` 鈥?闈炰細璇濈骇閿欒

Gateway 杩炴帴寮傚父绛夌郴缁熺骇閿欒銆?

```json
{
  "type": "error",
  "seq": 14,
  "welinkSessionId": 42,
  "emittedAt": "2026-03-08T00:21:00.000Z",
  "error": "Gateway connection timeout"
}
```

| 鐗规湁瀛楁 | 绫诲瀷   | 蹇呭～  | 璇存槑     |
| -------- | ------ | :---: | -------- |
| `error`  | String |   鉁?  | 閿欒鎻忚堪 |

---

#### `snapshot` 鈥?鏂嚎鎭㈠锛氬凡瀹屾垚娑堟伅蹇収

瀹㈡埛绔噸杩炲悗锛屾湇鍔＄鎺ㄩ€佸綋鍓嶄細璇濈殑宸插畬鎴愭秷鎭垪琛ㄣ€?

```json
{
  "type": "snapshot",
  "seq": 1,
  "welinkSessionId": 42,
  "emittedAt": "2026-03-08T00:22:00.000Z",
  "messages": [
    {
      "id": "m_1",
      "seq": 1,
      "role": "user",
      "content": "甯垜鍒涘缓React椤圭洰",
      "contentType": "plain",
      "createdAt": "2026-03-08T00:15:00"
    },
    {
      "id": "m_2",
      "seq": 2,
      "role": "assistant",
      "content": "濂界殑锛屾垜鏉ュ府浣犲垱寤?..",
      "contentType": "markdown",
      "parts": [
        {
          "partId": "p_1",
          "partSeq": 1,
          "type": "text",
          "content": "濂界殑锛屾垜鏉ュ府浣犲垱寤?.."
        }
      ]
    }
  ]
}
```

| 鐗规湁瀛楁                 | 绫诲瀷    | 蹇呭～  | 璇存槑                          |
| ------------------------ | ------- | :---: | ----------------------------- |
| `messages`               | Array   |   鉁?  | 宸插畬鎴愭秷鎭垪琛?               |
| `messages[].id`          | String  |   鉁?  | 娑堟伅 ID                       |
| `messages[].seq`         | Integer |   鉁?  | 娑堟伅椤哄簭                      |
| `messages[].role`        | String  |   鉁?  | 瑙掕壊                          |
| `messages[].content`     | String  |   鉁?  | 娑堟伅鍐呭                      |
| `messages[].contentType` | String  |   鉁?  | `plain` / `markdown` / `code` |
| `messages[].createdAt`   | String  |   鉂?  | 鍒涘缓鏃堕棿                      |
| `messages[].parts`       | Array   |   鉂?  | Part 鍒楄〃                     |

---

#### `streaming` 鈥?鏂嚎鎭㈠锛氳繘琛屼腑鐨勬祦娑堟伅

瀹㈡埛绔噸杩炲悗锛岃嫢鏈夋鍦ㄨ繘琛屼腑鐨?AI 鍝嶅簲锛屾帹閫佸綋鍓嶇疮绉姸鎬併€?

```json
{
  "type": "streaming",
  "seq": 2,
  "welinkSessionId": 42,
  "emittedAt": "2026-03-08T00:22:00.100Z",
  "sessionStatus": "busy",
  "messageId": "m_3",
  "messageSeq": 3,
  "role": "assistant",
  "parts": [
    {
      "partId": "p_1",
      "partSeq": 1,
      "type": "text",
      "content": "濂界殑锛屾垜姝ｅ湪鍒嗘瀽浣犵殑浠ｇ爜..."
    },
    {
      "partId": "p_2",
      "partSeq": 2,
      "type": "tool",
      "toolName": "bash",
      "toolCallId": "call_1",
      "status": "running"
    }
  ]
}
```

| 鐗规湁瀛楁             | 绫诲瀷     | 蹇呭～  | 璇存槑                                                              |
| -------------------- | -------- | :---: | ----------------------------------------------------------------- |
| `sessionStatus`      | String   |   鉁?  | `busy` / `idle`                                                   |
| `messageId`          | String   |   鉂?  | 褰撳墠娑堟伅 ID                                                       |
| `messageSeq`         | Integer  |   鉂?  | 褰撳墠娑堟伅椤哄簭                                                      |
| `role`               | String   |   鉂?  | 瑙掕壊                                                              |
| `parts`              | Array    |   鉁?  | 褰撳墠宸茬疮绉殑 Part 鍒楄〃                                            |
| `parts[].partId`     | String   |   鉁?  | Part ID                                                           |
| `parts[].partSeq`    | Integer  |   鉂?  | Part 椤哄簭                                                         |
| `parts[].type`       | String   |   鉁?  | `text` / `thinking` / `tool` / `question` / `permission` / `file` |
| `parts[].content`    | String   |   鉂?  | 鏂囨湰鍐呭                                                          |
| `parts[].toolName`   | String   |   鉂?  | 宸ュ叿鍚?                                                           |
| `parts[].toolCallId` | String   |   鉂?  | 宸ュ叿璋冪敤 ID                                                       |
| `parts[].status`     | String   |   鉂?  | 宸ュ叿鐘舵€?                                                         |
| `parts[].header`     | String   |   鉂?  | question 鏍囬                                                     |
| `parts[].question`   | String   |   鉂?  | question 闂                                                     |
| `parts[].options`    | String[] |   鉂?  | question 閫夐」                                                     |
| `parts[].fileName`   | String   |   鉂?  | 鏂囦欢鍚?                                                           |
| `parts[].fileUrl`    | String   |   鉂?  | 鏂囦欢 URL                                                          |
| `parts[].fileMime`   | String   |   鉂?  | MIME 绫诲瀷                                                         |
---

## 闄勫綍 A锛?026-03-11 瀹炵幇鍚屾琛ュ厖

鏈檮褰曠敤浜庤鐩栨湰鏂囨。涓笌褰撳墠瀹炵幇涓嶄竴鑷寸殑鏃у彛寰勶紱鑻ヤ笌姝ｆ枃鍐茬獊锛屼互鏈檮褰曚负鍑嗐€?
### A.1 REST 閫氱敤绾﹀畾

- 鎵€鏈夋彁渚涚粰 Miniapp 鐨?Layer1 REST 鎺ュ彛缁熶竴杩斿洖 HTTP `200 OK`銆?- 涓氬姟鎴愬姛涓庡け璐ョ粺涓€閫氳繃鍝嶅簲浣撲腑鐨?`code` 鍜?`errormsg` 琛ㄨ揪銆?- 鎵€鏈?Layer1 鎺ュ彛閮藉繀椤讳粠 Cookie 瑙ｆ瀽 `userId` 骞舵墽琛岃闂帶鍒躲€?- 瀵瑰甫 `welinkSessionId` 鐨勬帴鍙ｏ紝璁块棶鎺у埗閾捐矾涓猴細
  1. 浠?Cookie 瑙ｆ瀽 `userId`
  2. 鏍规嵁 `welinkSessionId` 鍔犺浇鏈湴 `SkillSession`
  3. 鍏堟牎楠?`SkillSession.userId == Cookie.userId`
  4. 鍐嶄娇鐢?`SkillSession.ak` 璋冪敤 Gateway 鏍￠獙 `ak` 涓?`userId` 鐨勫綊灞炲叧绯?  5. 浠讳竴姝ュけ璐ラ兘蹇呴』鎷掔粷璁块棶

### A.2 `POST /api/skill/sessions`

- `imGroupId` 涓哄彲閫夊瓧娈碉紝涓嶅啀瑕佹眰蹇呭～銆?- `imGroupId` 鏈紶鏃讹紝琛ㄧず璇ヤ細璇濇殏鏈粦瀹氶粯璁?IM 缇ゃ€?- 鍚庣画璋冪敤 `POST /api/skill/sessions/{welinkSessionId}/send-to-im` 鏃讹紝鑻ヤ細璇濇湰韬病鏈?`imGroupId`锛屽垯蹇呴』鏄惧紡浼犲叆 `chatId`銆?
### A.3 `POST /api/skill/sessions/{welinkSessionId}/messages`

- 鎴愬姛鍝嶅簲涓嶅啀鍖呭惈 `userId`銆?- 瀵瑰鍝嶅簲浣跨敤鍗忚 DTO锛屼笉鐩存帴鏆撮湶鍐呴儴 `SkillMessage` 妯″瀷銆?- 褰撳墠鎴愬姛鍝嶅簲缁撴瀯濡備笅锛?
```json
{
  "code": 0,
  "errormsg": "",
  "data": {
    "id": 101,
    "welinkSessionId": 42,
    "role": "user",
    "content": "甯垜鍒涘缓涓€涓猂eact椤圭洰",
    "messageSeq": 3,
    "createdAt": "2026-03-08T00:16:00"
  }
}
```

- `toolCallId` 浠嶇敤浜庤〃杈锯€滃洖绛?question鈥濓細
  - 鏈惡甯?`toolCallId` 鏃讹紝Skill Server 鍙戦€?Layer2 `invoke.chat`
  - 鎼哄甫 `toolCallId` 鏃讹紝Skill Server 鍙戦€?Layer2 `invoke.question_reply`
- 鑻?`toolSessionId` 灏氭湭灏辩华锛岃姹傚け璐ユ椂浠嶈繑鍥?HTTP `200`锛屽苟鍦ㄥ搷搴斾綋涓€氳繃闈?`0` 鐨?`code` 涓?`errormsg` 琛ㄨ揪閿欒銆?
### A.4 `POST /api/skill/sessions/{welinkSessionId}/permissions/{permId}`

- 閿欒鍦烘櫙鍚屾牱缁熶竴浣跨敤 HTTP `200 + code/errormsg`銆?- 鍗忚闇€瑕嗙洊鑷冲皯浠ヤ笅澶辫触鎯呭喌锛?  - `response` 缂哄け鎴栭潪娉?  - `welinkSessionId` 涓嶅瓨鍦?  - 浼氳瘽宸插叧闂?  - 浼氳瘽鏈叧鑱斿彲鐢?Agent
  - 璁块棶鎺у埗鏍￠獙澶辫触

### A.5 `GET /api/skill/sessions/{welinkSessionId}/messages`

- 鍘嗗彶娑堟伅鍝嶅簲涓嶅啀鍖呭惈 `userId`銆?- 瀵瑰瀛楁缁熶竴浣跨敤 `welinkSessionId`锛屼笉鏆撮湶鍐呴儴 `sessionId`銆?- `role`銆乣contentType` 浣跨敤鍗忚鍖栫殑灏忓啓鍊笺€?- 鍘嗗彶娑堟伅涓殑 tool part 瀛楁缁熶竴涓?`status`銆乣input`銆乣output`锛屼笉鍐嶄娇鐢?`toolStatus`銆乣toolInput`銆乣toolOutput`銆?- `POST /messages`銆乣GET /messages` 涓?WebSocket `snapshot.messages[]` 澶嶇敤鍚屼竴濂楀崗璁?DTO銆?
### A.6 `POST /api/skill/sessions/{welinkSessionId}/send-to-im`

- 閿欒鍦烘櫙缁熶竴浣跨敤 HTTP `200 + code/errormsg`銆?- 鍗忚闇€瑕嗙洊鑷冲皯浠ヤ笅澶辫触鎯呭喌锛?  - `content` 涓虹┖
  - `welinkSessionId` 涓嶅瓨鍦?  - 浼氳瘽娌℃湁 `imGroupId` 涓旇姹傛湭浼?`chatId`
  - 璁块棶鎺у埗鏍￠獙澶辫触
  - 涓嬫父 IM 鍙戦€佸け璐?
### A.7 `GET /api/skill/agents`

- `toolType` 浣跨敤灏忓啓鍗忚鍊硷紝渚嬪 `opencode`銆?- 褰撳墠鐪熷疄杩斿洖瀛楁闄?`ak`銆乣akId`銆乣toolType`銆乣toolVersion`銆乣deviceName`銆乣os` 澶栵紝杩樺寘鎷細
  - `status`
  - `connectedAt`

### A.8 WebSocket `ws://host/ws/skill/stream`

- 璇ヨ繛鎺ラ櫎鏈嶅姟绔帹閫佸锛屽鎴风杩樺厑璁稿彂閫侊細

```json
{
  "action": "resume"
}
```

- `resume` 鐨勮涔夋槸璇锋眰鏈嶅姟绔噸鏀惧綋鍓嶆椿璺冧細璇濈殑鎭㈠鎬佹暟鎹€?- 閲嶆斁椤哄簭鍥哄畾涓猴細
  1. `snapshot`
  2. `streaming`

### A.9 WebSocket 鍏叡瀛楁涓庣姸鎬?
- `snapshot` 涔熷繀椤绘惡甯?`seq`銆?- 瀵瑰 `session.status.sessionStatus` 鍊煎煙缁熶竴涓猴細
  - `busy`
  - `idle`
  - `retry`
- `streaming.sessionStatus` 浠呬娇鐢細
  - `busy`
  - `idle`

### A.10 `snapshot`

- `snapshot.messages[]` 澶嶇敤鍘嗗彶娑堟伅鍗忚 DTO銆?- 绀轰緥涓殑娑堟伅浣撳簲鐞嗚В涓猴細
  - 椤跺眰娑堟伅瀛楁浣跨敤 `welinkSessionId`
  - `role`銆乣contentType` 浣跨敤灏忓啓鍗忚鍊?  - `parts[]` 浣跨敤缁熶竴鐨勫崗璁?part 缁撴瀯

### A.11 `streaming`

- `streaming.parts[]` 琛ㄧず鑱氬悎鍚庣殑鎭㈠鎬?part 蹇収锛屼笉鏄師濮嬩簨浠跺垪琛ㄣ€?- `parts[].type` 缁熶竴浣跨敤浠ヤ笅鍗忚鍊硷細
  - `text`
  - `thinking`
  - `tool`
  - `question`
  - `permission`
  - `file`
- `streaming.parts[]` 鍙毚闇叉仮澶嶆€佹墍闇€瀛楁锛屼笉鐩存帴閫忎紶 `text.delta`銆乣tool.update` 绛変簨浠剁骇绫诲瀷銆?
### A.12 `question` 涓?`question_reply` 鐨勫眰绾у尯鍒?
- Layer1 鎺ㄧ粰 Miniapp 鐨勬秷鎭被鍨嬫槸 `question`銆?- Miniapp 鍥炵瓟 `question` 鏃讹紝璋冪敤 `POST /api/skill/sessions/{welinkSessionId}/messages`锛屽苟閫氳繃 `toolCallId` 鎸囧悜琚洖绛旂殑闂銆?- `question_reply` 鏄?Skill Server 鍙戠粰 Gateway 鐨?Layer2 `invoke` 鍔ㄤ綔锛屼笉鏄?Layer1 瀵瑰 REST API 鍚嶇О銆?
### A.13 ID 绫诲瀷鍩虹嚎

- `welinkSessionId` 涓?`Long`
  - Layer1 REST 鍝嶅簲涓殑 `welinkSessionId` 浣跨敤鏁板瓧绫诲瀷
  - Layer1 WebSocket 娑堟伅涓殑 `welinkSessionId` 涔熶娇鐢ㄦ暟瀛楃被鍨?- `toolSessionId` 涓?`String`
- `userId` 涓?`String`

