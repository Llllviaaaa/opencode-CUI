# 吞吐优化实施计划（Round 2）

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 通过虚拟线程、Log4j2 全异步日志、Redis 连接池调优，消除 GW 和 SS 的线程调度争抢、日志锁争抢、Redis 连接等待三大瓶颈。

**Architecture:** GW 和 SS 两个服务做相同的三项改造：(1) 启用 Java 21 虚拟线程 (2) 从 Logback 切换到 Log4j2 + LMAX Disruptor 全异步 (3) Redis 连接池扩大到 200。

**Tech Stack:** Spring Boot 3.4.6 / Java 21 / Log4j2 + LMAX Disruptor 4.0 / Lettuce Redis

**设计文档:** `docs/superpowers/specs/2026-04-10-throughput-optimization-design.md`

---

## File Structure

### ai-gateway 改动

| 操作 | 文件 | 职责 |
|------|------|------|
| Modify | `ai-gateway/pom.xml` | 排除 Logback，引入 Log4j2 + Disruptor |
| Delete | `ai-gateway/src/main/resources/logback-spring.xml` | 旧日志配置 |
| Create | `ai-gateway/src/main/resources/log4j2-spring.xml` | 新日志配置 |
| Create | `ai-gateway/src/main/resources/log4j2.component.properties` | 启用全异步模式 |
| Modify | `ai-gateway/src/main/resources/application.yml` | 虚拟线程 + Redis 连接池 + 日志路径 |

### skill-server 改动

| 操作 | 文件 | 职责 |
|------|------|------|
| Modify | `skill-server/pom.xml` | 排除 Logback，引入 Log4j2 + Disruptor |
| Delete | `skill-server/src/main/resources/logback-spring.xml` | 旧日志配置 |
| Create | `skill-server/src/main/resources/log4j2-spring.xml` | 新日志配置 |
| Create | `skill-server/src/main/resources/log4j2.component.properties` | 启用全异步模式 |
| Modify | `skill-server/src/main/resources/application.yml` | 虚拟线程 + Redis 连接池 + 日志路径 |

---

## Task 1: GW — 启用虚拟线程 + Redis 连接池调大

**Files:**
- Modify: `ai-gateway/src/main/resources/application.yml`

- [ ] **Step 1: 在 application.yml 中启用虚拟线程**

在 `spring:` 块下添加虚拟线程配置：

```yaml
spring:
  threads:
    virtual:
      enabled: true
```

- [ ] **Step 2: 调大 Redis 连接池**

将 `spring.data.redis.lettuce.pool` 配置从：

```yaml
lettuce:
  pool:
    max-active: 50
    max-idle: 20
    min-idle: 5
```

改为：

```yaml
lettuce:
  pool:
    max-active: 200
    max-idle: 50
    min-idle: 10
```

- [ ] **Step 3: 验证编译**

Run: `cd ai-gateway && mvn compile -q`
Expected: 无输出，编译成功

- [ ] **Step 4: Commit**

```bash
git add ai-gateway/src/main/resources/application.yml
git commit -m "perf(gw): enable virtual threads and increase Redis pool to 200"
```

---

## Task 2: GW — 切换到 Log4j2 + LMAX Disruptor

**Files:**
- Modify: `ai-gateway/pom.xml`
- Delete: `ai-gateway/src/main/resources/logback-spring.xml`
- Create: `ai-gateway/src/main/resources/log4j2-spring.xml`
- Create: `ai-gateway/src/main/resources/log4j2.component.properties`
- Modify: `ai-gateway/src/main/resources/application.yml`

- [ ] **Step 1: 修改 pom.xml — 排除 Logback，引入 Log4j2 + Disruptor**

在 `spring-boot-starter-web` 依赖中排除默认的 `spring-boot-starter-logging`，然后添加 Log4j2 和 Disruptor 依赖。

在 `<dependencies>` 中找到 `spring-boot-starter-web`，修改为：

```xml
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
```

在 `<dependencies>` 末尾添加：

```xml
<!-- Log4j2 + LMAX Disruptor 全异步日志 -->
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

- [ ] **Step 2: 创建 log4j2.component.properties — 启用全异步**

创建文件 `ai-gateway/src/main/resources/log4j2.component.properties`：

```properties
# Enable all-async logging via LMAX Disruptor
Log4jContextSelector=org.apache.logging.log4j.core.async.AsyncLoggerContextSelector
```

- [ ] **Step 3: 创建 log4j2-spring.xml — 日志配置**

创建文件 `ai-gateway/src/main/resources/log4j2-spring.xml`：

```xml
<?xml version="1.0" encoding="UTF-8"?>
<Configuration status="WARN">
    <Properties>
        <Property name="SERVICE_NAME">ai-gateway</Property>
        <Property name="LOG_FILE">${sys:opencode.logging.file:-./logs/ai-gateway/ai-gateway.log}</Property>
        <Property name="ARCHIVE_DIR">${sys:opencode.logging.archive-dir:-./logs/ai-gateway/archive}</Property>
        <Property name="LOG_PATTERN">%d{yyyy-MM-dd HH:mm:ss.SSS} [%thread] %-5level [${SERVICE_NAME}] [%X{traceId:-}] [%X{sessionId:-}] [%X{ak:-}] %logger{36}.%method - %msg%n</Property>
    </Properties>

    <Appenders>
        <RollingFile name="FILE"
                     fileName="${LOG_FILE}"
                     filePattern="${ARCHIVE_DIR}/${SERVICE_NAME}.%d{yyyy-MM-dd}.%i.log.gz">
            <PatternLayout pattern="${LOG_PATTERN}" charset="UTF-8"/>
            <Policies>
                <SizeBasedTriggeringPolicy size="20MB"/>
                <TimeBasedTriggeringPolicy interval="1"/>
            </Policies>
            <DefaultRolloverStrategy max="30">
                <Delete basePath="${ARCHIVE_DIR}" maxDepth="1">
                    <IfAccumulatedFileSize exceeds="2GB"/>
                </Delete>
            </DefaultRolloverStrategy>
        </RollingFile>

        <Console name="CONSOLE" target="SYSTEM_OUT">
            <PatternLayout pattern="${LOG_PATTERN}" charset="UTF-8"/>
        </Console>
    </Appenders>

    <Loggers>
        <SpringProfile name="dev">
            <Root level="INFO">
                <AppenderRef ref="CONSOLE"/>
                <AppenderRef ref="FILE"/>
            </Root>
        </SpringProfile>
        <SpringProfile name="!dev">
            <Root level="INFO">
                <AppenderRef ref="FILE"/>
            </Root>
        </SpringProfile>
    </Loggers>
</Configuration>
```

- [ ] **Step 4: 删除旧的 logback-spring.xml**

删除文件 `ai-gateway/src/main/resources/logback-spring.xml`

- [ ] **Step 5: 清理 application.yml 中的 logback 相关注释**

在 `ai-gateway/src/main/resources/application.yml` 中，找到注释 `# pattern 和 rollingpolicy 已迁移到 logback-spring.xml`，修改为 `# 日志配置见 log4j2-spring.xml`。

同时确认 `logging.level` 配置在 Log4j2 下仍然有效（Spring Boot 会自动桥接）。

- [ ] **Step 6: 验证编译和启动**

Run: `cd ai-gateway && mvn compile -q`
Expected: 无输出，编译成功

Run: `cd ai-gateway && mvn spring-boot:run`
Expected: 启动日志格式正确，包含 `[ai-gateway]` 前缀，日志文件正常生成

- [ ] **Step 7: Commit**

```bash
git add ai-gateway/pom.xml
git add ai-gateway/src/main/resources/log4j2-spring.xml
git add ai-gateway/src/main/resources/log4j2.component.properties
git add ai-gateway/src/main/resources/application.yml
git rm ai-gateway/src/main/resources/logback-spring.xml
git commit -m "perf(gw): switch to Log4j2 + LMAX Disruptor all-async logging"
```

---

## Task 3: SS — 启用虚拟线程 + Redis 连接池调大

**Files:**
- Modify: `skill-server/src/main/resources/application.yml`

- [ ] **Step 1: 在 application.yml 中启用虚拟线程**

在 `spring:` 块下添加虚拟线程配置：

```yaml
spring:
  threads:
    virtual:
      enabled: true
```

- [ ] **Step 2: 调大 Redis 连接池**

将 `spring.data.redis.lettuce.pool` 配置从：

```yaml
lettuce:
  pool:
    max-active: 50
    max-idle: 20
    min-idle: 5
```

改为：

```yaml
lettuce:
  pool:
    max-active: 200
    max-idle: 50
    min-idle: 10
```

- [ ] **Step 3: 验证编译**

Run: `cd skill-server && mvn compile -q`
Expected: 无输出，编译成功

- [ ] **Step 4: Commit**

```bash
git add skill-server/src/main/resources/application.yml
git commit -m "perf(ss): enable virtual threads and increase Redis pool to 200"
```

---

## Task 4: SS — 切换到 Log4j2 + LMAX Disruptor

**Files:**
- Modify: `skill-server/pom.xml`
- Delete: `skill-server/src/main/resources/logback-spring.xml`
- Create: `skill-server/src/main/resources/log4j2-spring.xml`
- Create: `skill-server/src/main/resources/log4j2.component.properties`
- Modify: `skill-server/src/main/resources/application.yml`

- [ ] **Step 1: 修改 pom.xml — 排除 Logback，引入 Log4j2 + Disruptor**

在 `spring-boot-starter-web` 依赖中排除默认的 `spring-boot-starter-logging`，然后添加 Log4j2 和 Disruptor 依赖。

在 `<dependencies>` 中找到 `spring-boot-starter-web`，修改为：

```xml
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
```

在 `<dependencies>` 末尾添加：

```xml
<!-- Log4j2 + LMAX Disruptor 全异步日志 -->
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

- [ ] **Step 2: 创建 log4j2.component.properties — 启用全异步**

创建文件 `skill-server/src/main/resources/log4j2.component.properties`：

```properties
# Enable all-async logging via LMAX Disruptor
Log4jContextSelector=org.apache.logging.log4j.core.async.AsyncLoggerContextSelector
```

- [ ] **Step 3: 创建 log4j2-spring.xml — 日志配置**

创建文件 `skill-server/src/main/resources/log4j2-spring.xml`：

```xml
<?xml version="1.0" encoding="UTF-8"?>
<Configuration status="WARN">
    <Properties>
        <Property name="SERVICE_NAME">skill-server</Property>
        <Property name="LOG_FILE">${sys:opencode.logging.file:-./logs/skill-server/skill-server.log}</Property>
        <Property name="ARCHIVE_DIR">${sys:opencode.logging.archive-dir:-./logs/skill-server/archive}</Property>
        <Property name="LOG_PATTERN">%d{yyyy-MM-dd HH:mm:ss.SSS} [%thread] %-5level [${SERVICE_NAME}] [%X{traceId:-}] [%X{sessionId:-}] [%X{ak:-}] %logger{36}.%method - %msg%n</Property>
    </Properties>

    <Appenders>
        <RollingFile name="FILE"
                     fileName="${LOG_FILE}"
                     filePattern="${ARCHIVE_DIR}/${SERVICE_NAME}.%d{yyyy-MM-dd}.%i.log.gz">
            <PatternLayout pattern="${LOG_PATTERN}" charset="UTF-8"/>
            <Policies>
                <SizeBasedTriggeringPolicy size="20MB"/>
                <TimeBasedTriggeringPolicy interval="1"/>
            </Policies>
            <DefaultRolloverStrategy max="30">
                <Delete basePath="${ARCHIVE_DIR}" maxDepth="1">
                    <IfAccumulatedFileSize exceeds="2GB"/>
                </Delete>
            </DefaultRolloverStrategy>
        </RollingFile>

        <Console name="CONSOLE" target="SYSTEM_OUT">
            <PatternLayout pattern="${LOG_PATTERN}" charset="UTF-8"/>
        </Console>
    </Appenders>

    <Loggers>
        <SpringProfile name="dev">
            <Root level="INFO">
                <AppenderRef ref="CONSOLE"/>
                <AppenderRef ref="FILE"/>
            </Root>
        </SpringProfile>
        <SpringProfile name="!dev">
            <Root level="INFO">
                <AppenderRef ref="FILE"/>
            </Root>
        </SpringProfile>
    </Loggers>
</Configuration>
```

- [ ] **Step 4: 删除旧的 logback-spring.xml**

删除文件 `skill-server/src/main/resources/logback-spring.xml`

- [ ] **Step 5: 清理 application.yml 中的 logback 相关注释**

在 `skill-server/src/main/resources/application.yml` 中，找到注释 `# pattern 和 rollingpolicy 已迁移到 logback-spring.xml`，修改为 `# 日志配置见 log4j2-spring.xml`。

- [ ] **Step 6: 验证编译和启动**

Run: `cd skill-server && mvn compile -q`
Expected: 无输出，编译成功

Run: `cd skill-server && mvn spring-boot:run`
Expected: 启动日志格式正确，包含 `[skill-server]` 前缀，日志文件正常生成

- [ ] **Step 7: Commit**

```bash
git add skill-server/pom.xml
git add skill-server/src/main/resources/log4j2-spring.xml
git add skill-server/src/main/resources/log4j2.component.properties
git add skill-server/src/main/resources/application.yml
git rm skill-server/src/main/resources/logback-spring.xml
git commit -m "perf(ss): switch to Log4j2 + LMAX Disruptor all-async logging"
```

---

## 验证清单

- [ ] **压测验证：吞吐**
  - 相同条件（500 pairs, 10s, 10ms interval）对比 TPS
  - 预期：从 98 msg/s 提升到 >500 msg/s

- [ ] **压测验证：丢失率**
  - 对比 Recv/Sent 比率
  - 预期：从 81.5% 提升到 >99%

- [ ] **压测验证：GW relay 耗时**
  - 对比 handleRelayToSkillServer durationMs
  - 预期：从 avg 685ms 降到 <50ms

- [ ] **压测验证：WS 断连**
  - 对比 Skill WS 断连次数
  - 预期：从 9 次降到 0 次

- [ ] **功能验证：日志**
  - 日志格式正确，包含 traceId/sessionId/ak
  - 日志文件正常滚动
  - dev 环境有控制台输出，非 dev 环境无控制台输出
