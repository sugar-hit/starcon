# 16.4.1.2 检测与预警（阶段 D7，L3 锚点 16.4.1）

- **日期**：2026-10-23
- **车道**：`feat/16-4-1-2`（worktree `.worktrees/16.4.1.2`，基线 `integration/module-dev` @ e1b808e）
- **模块**：16.4.1.2 检测与预警 —— channel 服务，迁移 `V317__channel_ip_detection.sql`

## 决策摘要

1. **九张新表、零 ALTER 既有表**：表前缀 `ip_detection_*`，与上游 `ip_risk_*`（16.4.1.1，V315）、
   下游 `compliance_intercept_*`（16.2.1.2，V313）、`brand_authz_*` / `brand_cred_*`（16.4.2）
   四个命名空间彼此隔离。上游侵权库**只读**（jOOQ 显式 SQL），命中项只存**逻辑 id + 快照** ⇒
   上游条目后来停用 / 改写，历史报告仍解释得清「当时命中了什么」。
2. **图片命中必须进人工复核队列**（蓝图加粗句，DB 层 CHECK 钉死）：`field='IMAGE' ⇒
   needs_manual_review=true`。且**图片命中永远不单独构成「可判定命中」** ⇒ 全是图片命中的报告，
   结论是「待人工复核」而不是「已判定侵权」。
3. **结论三态**：CLEAR（无命中）/ REVIEW_REQUIRED（有命中但全部待复核）/ ACTION_REQUIRED（有可判定命中）。
   「判不了」与「判了没问题」是两件事。
4. **本模块不拦截**：下游 16.2.1.2 已交付并拥有拦截权；本模块只产出 `recommended_action`（**建议**）
   + 预警 + 整改建议，零写下游策略表。
5. **外部商标 / 专利查询走「登记承接」**：本仓没有真实凭据与网络出口 ⇒ 不写打真实域名的假 HTTP
   客户端。照抄 10.4.2.2 报送表 / 16.4.2.2 凭证文件的形状：租户登记查询单 → 登记查回来的结果
   → 检测时与本地侵权库一起比对。能力声明里 `available=false` + `ownerModule` + 影响面逐条标出。
6. **巡检不装 `@Scheduled`**：与 V212 物流轨迹拉取同一纪律 —— 多副本自带定时器要做主节点选举与
   分布式锁，那是平台能力不是领域能力；外部调度（K8s CronJob）按 `interval_days` 调一次运行入口。
7. **巡检运行是 append-only 回执**：跑完才落行（没有「运行中」这一态）⇒ 该表真的只给
   SELECT, INSERT。中途崩溃则这一轮没有回执，计划的 `last_run_at` 也不前进 ⇒ 下次调度会重跑。

## 关键 trade-off

| 决策 | 取舍 |
| --- | --- |
| 命中项存**快照**（entry_code / right_type / risk_level）而不是只存外键 | 多存几列，换「条目后来被停用后历史报告仍解释得清」 |
| 巡检运行**跑完才落行** | 放弃「运行中」可见性，换该表真正 append-only（只给 SELECT,INSERT） |
| 图片比对是**标识文本比对**（文件名 / alt / URL），**不是**以图搜图 | 能力低于蓝图理想值，但**诚实**；配套「图片命中一律进复核队列」把误判挡在人工环节 |
| 品牌维度跨服务只读 catalog（`IpDetectionSourcePort`），读不到就说读不到 | 多一次 HTTP 往返，换「读不到」与「没有品牌风险」不混淆 |
| 关键词按**词边界**匹配、短语按包含匹配 | 「air」不命中「hair」；代价是首尾非字母数字的特征（`C++`）特判放行 |

## 文件清单

**后端（channel 服务，包 `com.starcon.channel` 扁平包）**

- `backend/services/channel/src/main/resources/db/migration/V317__channel_ip_detection.sql`（9 张表 + 触发器 + RLS + GRANT）
- `backend/services/channel/src/main/java/com/starcon/channel/IpDetectionModels.java`
- `backend/services/channel/src/main/java/com/starcon/channel/IpDetectionRules.java`（纯逻辑：值域 + 比对口径 + 结论口径）
- `backend/services/channel/src/main/java/com/starcon/channel/IpDetectionSourcePort.java`（catalog 只读：品牌解析）
- `backend/services/channel/src/main/java/com/starcon/channel/IpDetectionService.java`
- `backend/services/channel/src/main/java/com/starcon/channel/IpDetectionController.java`（17 条端点，前缀 `/channel/ip-detections`）

**后端测试**

- `backend/services/channel/src/test/java/com/starcon/channel/IpDetectionRulesTest.java`（21 例）
- `backend/services/channel/src/test/java/com/starcon/channel/IpDetectionIntegrationTest.java`（18 例）
- `backend/services/channel/src/test/java/com/starcon/channel/IpDetectionTenancyIntegrationTest.java`（3 例）

**前端**

- `apps/web/src/components/ip-detection/ip-detection-model.ts`
- `apps/web/src/components/ip-detection/ip-detection-workspace.tsx`
- `apps/web/src/components/ip-detection/ip-detection-model.test.ts`
- `apps/web/src/app/compliance/ip-detection/page.tsx`
- `apps/web/src/app/api/channel/ip-detection-allowlist.test.ts`
- `apps/web/src/lib/api-proxy.ts`（**新增一条独立 `services[]` 元素**，锚点 `prefix: 'identity/'` 之前；既有条目一字未动）

**契约与台账**

- `packages/api-contracts/src/contracts/channel.ts` + `packages/api-contracts/src/channel.openapi.json`（codegen 产物，**未手改**）
- `docs/product/capability-blueprint/module-progress.md`（只改 `| 16.4.1.2 |` 那一行的「状态」「证据」两格）

## 验证

- L0 编译闸：`./mvnw -q compile`（backend）+ `pnpm typecheck` —— 全绿
- L1 模块闸：`./mvnw -pl services/channel -am test -Dtest='IpDetection*' -Dsurefire.failIfNoSpecifiedTests=false
  -DfailIfNoSpecifiedTests=false` ⇒ **42 例全绿**（21 + 18 + 3）
- 前端：`pnpm vitest run apps/web/src/app/api/channel/ip-detection-allowlist.test.ts apps/web/src/components/ip-detection`
  ⇒ 19 例全绿
- **api-proxy 全量 allowlist 回归**（因为改了 `apps/web/src/lib/api-proxy.ts`）：
  `pnpm vitest run apps/web/src/app/api/trade apps/web/src/app/api/channel` ⇒ **732 例全绿**（139 个文件）
- 契约：`python3 tools/codegen/catalog-contracts.py --spec-dir backend/target/openapi` ⇒ 新增 29 schema + 17 path，
  零删除、零改动既有类型

## 后续待办

- **16.4.1.2 自身**（不在本车道范围，由下游模块消费）：16.2.1.2 拦截可读本模块的 `recommended_action`；
  16.4.2 授权核验可消费品牌类命中的整改建议（`VERIFY_BRAND_AUTH`）。
- **外部商标库自动外呼**：需要凭据与网络出口（平台能力），届时以 `IpDetectionSourcePort` 的形状接入，
  能力声明里的 `EXTERNAL_TM_DB` 缺口即可关闭。
- **以图搜图 / 图像指纹**：需要图像识别引擎（平台能力）。接入前**不得**移除
  `ip_detection_hit` 上「图片命中 ⇒ 必须人工复核」的 CHECK —— 那是蓝图的硬要求。
- **巡检定时器**：需要平台级调度器（K8s CronJob）按 `interval_days` 调
  `POST /channel/ip-detections/inspection-runs?planId=…`；本服务**刻意不装** `@Scheduled`。
