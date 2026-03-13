# Message-Bridge NPM 发布指南

**Version:** 1.0
**Date:** 2026-03-13
**Status:** Active
**Owner:** message-bridge maintainers
**Related:** `../../package.json`, `../../README.md`, `./release-checklist.md`

`@opencode-cui/message-bridge` 的标准发布手册，覆盖稳定版、beta 版以及私仓切换。

## 1. 发布定位

- npm 包默认发布为可直接消费的库包。
- 对外入口固定为：
  - `main = dist/index.js`
  - `types = dist/index.d.ts`
  - `exports["."] = ./dist/index.js`
- npm 包默认只包含：
  - `dist/`
  - `README.md`
  - `LICENSE`
- `release/` 是插件分发产物，不默认进入 npm tarball。

## 2. 发布前准备

发布前先确认以下事项：

1. 已登录目标 registry。
2. `package.json` 中的版本号已经更新到目标版本。
3. 工作区没有误提交的调试改动。
4. 本地依赖已安装完成。

常用检查命令：

```bash
npm whoami
npm config get registry
```

如果是 scoped 公有包首次发布，最终发布命令需要带 `--access public`。

## 3. 稳定版发布

推荐顺序如下：

```bash
npm version <patch|minor|major>
npm run build
npm test
npm pack --dry-run
npm publish
```

说明：

- `npm run build` 会生成 `dist/`，并同时产出 `release/` 作为插件分发文件。
- `npm test` 用于拦截发布前回归。
- `npm pack --dry-run` 用于确认最终 tarball 只包含预期文件。
- 首次发布 scoped 公有包时使用：

```bash
npm publish --access public
```

## 4. Beta 包发布

beta 版使用 semver 预发布版本号，并固定发布到 `beta` dist-tag。

版本号示例：

- `1.0.1-beta.1`
- `1.1.0-beta.2`

推荐顺序如下：

```bash
npm version 1.0.1-beta.1
npm run build
npm test
npm pack --dry-run
npm publish --tag beta
```

安装 beta 的方式：

```bash
npm install @opencode-cui/message-bridge@beta
```

或安装显式版本：

```bash
npm install @opencode-cui/message-bridge@1.0.1-beta.1
```

约束：

- beta 版本不能覆盖稳定版。
- 正式发版时使用正常 semver 版本，并发布到默认 `latest`。
- 不要把 beta 包发布到 `latest`。

## 5. 私仓切换

私仓发布保持同包名 `@opencode-cui/message-bridge`，通过 registry 配置切换目标仓库，不修改包名，也不在 `package.json` 写死 `publishConfig.registry`。

### 5.1 本地切换到私仓

可以在用户级或项目级 `.npmrc` 中增加 scope 定向：

```ini
@opencode-cui:registry=https://your-private-registry.example.com/
//your-private-registry.example.com/:_authToken=${NPM_TOKEN}
```

切换后先确认：

```bash
npm config get registry
npm whoami --registry https://your-private-registry.example.com/
```

如果你的私仓只对 `@opencode-cui` scope 生效，实际发布会走 scope 定向 registry，而不是默认 registry。

### 5.2 在 CI 中发布到私仓

推荐通过环境变量注入 registry 和 token，不修改仓库文件：

```bash
export NPM_CONFIG_REGISTRY=https://your-private-registry.example.com/
export NPM_TOKEN=***REDACTED***
```

配合 CI 的 npm 认证配置完成发布，再执行：

```bash
npm run build
npm test
npm pack --dry-run
npm publish
```

如果私仓需要 scope 定向，优先在 CI 注入 `.npmrc` 内容，而不是提交到仓库。

### 5.3 从私仓切回公仓

回切时需要移除或覆盖私仓配置，避免误发：

```bash
npm config delete @opencode-cui:registry
npm config set registry https://registry.npmjs.org/
npm whoami --registry https://registry.npmjs.org/
```

如果使用的是项目级 `.npmrc`，直接删除对应 scope 定向配置即可。

## 6. 发布前验收

发布前至少执行以下命令：

```bash
npm run build
npm test
npm pack --dry-run
```

验收标准：

- `dist/index.js` 与 `dist/index.d.ts` 存在。
- `npm pack --dry-run` 中不出现 `src/`、`tests/`、`docs/`、`scripts/`。
- 默认 tarball 中不出现 `release/`。

## 7. 常见问题

### 7.1 401 / 403

- 先确认 token 是否有效。
- 再确认当前 registry 是否正确。
- 使用 `npm whoami --registry <url>` 验证当前身份。

### 7.2 版本已存在

- 同一 registry 下不能重复发布同一版本。
- 需要更新版本号后重新执行发布。

### 7.3 tag 冲突或安装到错误轨道

- beta 包必须用 `npm publish --tag beta`。
- 稳定版不要复用 beta tag。
- 安装时明确使用 `@beta` 或显式版本号。

### 7.4 误发到错误 registry

- 先用 `npm config get registry` 检查默认 registry。
- 如果用了 scope 定向，再检查 `.npmrc` 中是否存在 `@opencode-cui:registry=...`。
- 发布前执行一次 `npm whoami --registry <target-url>`。
