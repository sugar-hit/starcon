# 2026-10-07 · 2.2.1.2 授权向导（D1–D2）

## 决策摘要

把「平台 OAuth 跳转与回调校验未接（需平台应用凭据）」这个遗留说法清掉了。平台应用凭据本来就是
**运行时配置**，不是实现前提：端点、参数名、响应字段名做成配置表，client_id / client_secret 从环境变量读，
新增平台只插数据不改编码逻辑。集成测试用本地 stub 授权服务器（真 HTTP、真 302、真换令牌）跑通完整链路。

六步全部有真实后端支撑：选平台 / 选账号 / 授权跳转 / 回调校验 / 确认店铺范围 / 完成。

## 关键 trade-off

**1. 配置 vs 代码：按「是数据还是行为」切分**

- 端点 URL、scope 集合、参数名、响应字段名 → `platform_oauth_profile` 表（8 个平台已种）
- 协议行为（scope 分隔符、token 请求格式、账号响应套了几层壳、平台错误码翻译）→ per-vendor 适配器

按 AGENTS.md，适配器放 `com.starcon.channel.vendor.<vendor>`，只暴露 `PlatformOAuthAdapter`。
新增平台通常只插一行配置；只有协议行为超出接口表达能力时才新写 vendor 包。

**2. 回调的租户归属：一个窄的 SECURITY DEFINER 函数**

`oauth_authorization_session` 开了 FORCE RLS，没有 `app.current_tenant` 一行都读不到；而回调请求
没有可信身份。所以必须有越权入口。口径收到最窄：只认 state 精确匹配、只认 PENDING 且未过期、
只返回六个字段（租户、平台、发起人、部门、grants、loginHint），没有令牌没有 secret。
拿到租户后所有读写照旧走 RLS 与数据范围。

顺带解决了权限延续问题：光存发起人 userId 不够，数据范围策略要靠 `app.grants` 判定，
所以发起时把 grants 快照存进会话，回调用发起人的权限上下文写库——授权由谁发起就只能以谁的身份写入。

**3. 跨租户唯一性用索引而不是应用层判断**

`connection_account_owner ON channel_connection(platform_id, lower(authorized_account))`。
RLS 过滤的是"看得见的行"，唯一索引是全局的，所以这条索引就是蓝图要求的物理约束。
应用层的 `oauth_account_owner()` 只负责在**写入之前**给出可执行文案（"该平台商家账号已被其他租户绑定"），
避免用户白跳一次授权页才撞唯一约束。

**4. 令牌的存活期压到一次方法调用**

`Authorization`（换令牌 + 读账号信息合成一个返回值）而不是「换令牌结果」+「再读账号」两步。
拆两步令牌就会横跨两次调用留在字段里，中间任何异常或日志都可能带出去。
`SecretStore` 刻意不提供读取方法给业务路径——2.2.1.3 要求绝不回显明文。

**5. 平台配置缺失 ≠ 半成品**

没配 client 凭据时，后端明确返回「平台 X 尚未配置应用凭据：请设置 CHANNEL_OAUTH_X_CLIENT_ID 与
CHANNEL_OAUTH_X_CLIENT_SECRET 后重启服务」。这是可执行的运维信息，不是"功能未上线"。

## 踩到的坑（都已在代码注释里写清原因）

- **jOOQ 写 timestamptz**：brief 说用 `LocalDateTime.ofInstant(instant, UTC)`。这只在 JVM 默认时区是 UTC 时成立。
  实测 jOOQ 按 **JVM** 时区解释裸 `LocalDateTime`，在 Asia/Shanghai 机器上写入值会早 8 小时
  （授权会话写进去就是"刚创建就过期"）。实测四种绑定类型：`LocalDateTime` 差 -28800s、
  `OffsetDateTime` / `Instant` 被 PG 拒收（绑成 varchar）、**`java.sql.Timestamp` 差 0**。
  已收敛到 `Timestamps.at(instant)` 单一入口。
  ⚠️ **`ChannelService.rotateCredential`（模块 2.2.1.3，他人所有）仍是 `LocalDateTime` 写法，存在同样的 8 小时偏差**，
  本次未改动，见下方待办。
- **`sql.fetchValue(String, Class, Object...)` 会匹配到 `(String, Object...)` 重载**，把 Class 本身当成绑定值，
  报错却是 bad SQL grammar。统一走 `one(Class, sql, binds)` 取第 0 列。
- **`id in ()` 是语法错误**：显式传空 `shopIds` 必须短路返回，不能拼占位符。
- **V1 的 `GRANT ... ON ALL TABLES` 不覆盖后来新建的表**，V4 新表必须显式授权，否则运行角色拿到
  `permission denied` 而不是能读懂的报错。
- **Flyway 报的是字节偏移不是行号**：`bash .tmp/pg-migrate-check.sh` 起一次性 PG16 容器按序
  `psql -f` 跑迁移拿真实行号（脚本已留在 `.tmp/`，不进版本库）。
- **代理白名单正则**：`oauth(?:\/callback|sessions...)` 的 `sessions` 分支少了前导斜杠，
  `/^(oauth)(\/callback|sessions)$/` 匹配不上 `oauth/sessions`。写完必须跑 route.test.ts。
- **`pnpm contracts:generate` 不带 `--spec-dir` 会打到 live 端口**，拿到的是旧 spec，
  本次差点把 1111 行契约删掉。正确用法：`python3 tools/codegen/catalog-contracts.py --spec-dir backend/target/openapi`。
- **基线**：任务书写的基线是 `codex/workspace-account`，但该分支少一个 hop——没有 V3 迁移、没有
  `tools/module-progress/`、台账第 123 行也不是任务书引用的那一行。实际基线是
  `feat/17-2-1-1-department-tree`（= codex/workspace-account + 流水线 + V3 + 2.2.1.1 台账）。

## 文件清单

**迁移**
- `backend/services/channel/src/main/resources/db/migration/V4__oauth_authorization_wizard.sql`

**后端（新增）**
- `.../channel/vendor/`：`PlatformOAuthAdapter`、`OAuthProtocol`、`AbstractOAuthAdapter`、
  `VendorRegistry`、`PlatformException` + `vendor/{amazon,ebay,shopee,shopify,temu,tiktok,walmart,lazada}/`
- `.../channel/oauth/`：`OAuthAuthorizationService`、`PlatformOAuthProperties`、`Timestamps`
- `.../channel/secrets/`：`SecretStore`、`FileSecretStore`
- `.../channel/ChannelController.java`：新增 6 个授权向导端点（回调端点按 Accept 分流 JSON / 302 回前端）
- `.../channel/ChannelModels.java`：新增 6 个 DTO

**前端（新增/改动）**
- `apps/web/src/components/channel/oauth-wizard.tsx`（新增，六步向导）
- `apps/web/src/components/channel/channel-workspace.tsx`（连接段接入向导，移除旧的两步式建连接表单）
- `apps/web/src/lib/api-proxy.ts`（白名单加 `oauth/*`）
- `apps/web/src/app/api/channel/[...path]/route.test.ts`（白名单测试）

**测试**
- `backend/services/channel/src/test/java/com/starcon/channel/StubOAuthServer.java`（本地授权服务器）
- `backend/services/channel/src/test/java/com/starcon/channel/OAuthAuthorizationIntegrationTest.java`（23 条）

**契约（codegen 产物，未手改）**
- `packages/api-contracts/src/catalog.ts`（+6 个类型）、`channel.openapi.json`

**台账**
- `docs/product/capability-blueprint/module-progress.md` 第 123 行

## 后续待办

1. **`ChannelService.rotateCredential` 的 8 小时时区偏差**（模块 2.2.1.3，本次未动）。
   表现：轮换凭据写进去的 `authorized_at` / `expires_at` 比预期早 8 小时，
   `expires_at` 直接导致 2.2.2.1 健康诊断与 2.2.2.2 提醒提前误判。修法与 `Timestamps.at` 一致。
2. **平台应用凭据需要在部署环境配齐**：`CHANNEL_OAUTH_<PLATFORM>_CLIENT_ID` /
   `_CLIENT_SECRET`，以及 `CHANNEL_OAUTH_PUBLIC_BASE_URL`（平台注册应用时填的 redirect_uri 主机）、
   `CHANNEL_OAUTH_UI_RETURN_URL`（回调后送回的前端地址）。未配的平台向导会明确报出缺哪个变量。
3. **`FileSecretStore` 是可替换的默认实现**：生产应换成 Vault / K8s External Secrets，
   换的时候只实现 `SecretStore` 三个方法，数据库里只存 `external_ref` 不受影响。
4. **令牌刷新未做**：现在只完成首次授权与写引用。`credential_ref` 已有 `refresh_token` 的位置，
   但平台侧自动续期（尤其 Amazon / eBay 的一小时令牌）属于 2.2.1.3 的轮换范畴，后续期做。
5. **`platform_oauth_profile` 的 PLANNED 平台**（MERCADO_LIBRE / ALIEXPRESS / ETSY / WAYFAIR）
   没有 OAuth profile，向导会返回"尚未登记授权端点"，接入时插行配置 + 写适配器即可。
