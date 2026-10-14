# 2026-10-07: 面单与标签打印（8.3.4.2）

## 摘要

在并行车道 `feat/8-3-4-2` 落地能力蓝图 8.3.4.2「面单与标签打印」（阶段 D4，L3 锚点 8.3.4）。
新增 7 张表（`print_device` / `print_template` / `print_target` / `print_job` / `print_job_item` /
`label_print_log` / `print_reprint_rule`，迁移 `V73__warehouse_label_printing.sql`），
jOOQ 显式 SQL 服务与端点，`/inventory/printing` 工作区页面，以及 26 个真实 PostgreSQL 16 集成测试。

本模块最核心也最容易被做成假逻辑的地方是**打印次数**。这里把「次数」落成三处并用数据库约束互相钉死：

- `print_target.print_count` —— 权威计数，触发器保证只增不减，且**不得超过已有打印凭据条数**；
- `label_print_log.print_sequence` —— append-only 的逐次凭据，序号即「第几次」；
- `print_reprint_rule` —— 平台维度的 `warning_count` / `max_count` 判据。

## 决策 / 结论

### 1. 面单数据不自己建表，改用「本地快照 + 外部引用」

蓝图上游是 10 物流（面单数据）与 3.1.2.3（条码），两者都未交付；出库单 8.3.1.1 在并行车道里。
按 AGENTS.md（禁止跨服务 SQL / FK / 共享视图），本模块**不建** `outbound_order`、不读 10 服务的库。

做法：`print_target` 落一份打印要用的字段快照（运单号 / 承运商 / 收件人地址 / SKU / 库位 / 箱号 /
重量），外加一个纯文本 `external_ref` 指向调用方的对象标识。好处是渲染内容在生成时刻就确定：
重打第 2 次贴出来的与第 1 次逐字一致，计数与告警才对得上。

快照由触发器 `guard_print_target_snapshot_frozen` 冻成不可变（`external_ref`、`object_type`、
各快照字段、`platform_code` 都不许改）。改了等于换了一张面单，必须换 `external_ref` 重开一条，
否则计数与平台规则会对不上（换平台就是另一套重复打印规则，所以 `platform_code` 也不许改）。

### 2. 打印次数的判定基准是「下一次序号」，不是「当前次数」

一次任务可能打多份（`copies`），所以判定用 `next = print_count + copies`：

```
next <= warning_count                 → NORMAL   照打
warning_count < next <= max_count     → WARNING  照打但留告警标记
next > max_count                      → BLOCKED  整单拒绝，除非 override + 理由
```

超限是**整体拒绝**而不是「跳过超限的那几条继续打剩下的」—— 半张面单贴出去比不贴更糟。
强制重打必须填理由（应用层 + DB CHECK `print_job_override_reason_ck` 都收），
且放行后在凭据上标 `override_applied`。

规则按「平台 + 对象类型」取，先找平台专属、再回落 `platform_code = '*'` 通配规则，两者都没有则不限次。
通配规则是必要的：租户往往先给全局口径（「所有平台面单最多打 3 次」），再对某个平台收紧。

### 3. 加了一条「次数不得超过凭据条数」的 DB 约束

最初只写了「只增不减」，测试时发现**不够**：仍然能把 2 直接改成 7（只增），而库里只有 2 条凭据，
「这张面单到底打过几次」从此答错。补上 `guard_print_count_monotonic` 的第二条判据：

- 正好 `+1`（打印动作那一步）→ 要求改动前的次数就等于凭据条数；
- 一次跳 `N`（`N ≠ 1`）→ 直接拒，因为没有 N 条凭据支撑它。

事务提交后两者必然相等（`executeJob` 在同一事务里先 `+1` 再写凭据）。
**这条是本模块防止「打印次数是假的」的最后一道闸门。**

### 4. 打印内容是真渲染，不是写死的占位文件

- 标签类（面单 / SKU 条码 / 库位标签）→ **ZPL 指令流**：`^XA … ^XZ` 包边，条码用 `^BC`（Code 128）
  交给打印机编码，现场热敏机直接吃。
- 单据类（装箱单 / 箱唛）→ **HTML 打印视图**：内联样式 + `@page` 尺寸取自模板纸张，
  条码画成 SVG 条纹，带 `print-color-adjust:exact` 保证条码黑白可打。

条码是真码不是装饰：`LabelRenderService.Code128` 按 Code 128 Set B 的 107 项宽度表编码，
**含按规范算的校验位** `(start + Σ 值×位) mod 103`，两侧各留 10 个模块静区。
校验位算错扫码枪会直接拒读。

渲染内容在建任务时按模板与快照**现算**并落 `print_job_item.rendered_content` +
`content_checksum`（SHA-256）。下载端点返回的是**打印那一刻落库的那一份**（不是现算的），
并在返回前再验一次校验和 —— 所以「日志里的凭据」永远等于「下载到的内容」。

### 5. 同一任务只能打印一次，重打走新建任务

`guard_print_job_terminal` + `guard_print_job_transition` 两道 DB 闸门：终态不可再改，
状态只能 `DRAFT → QUEUED → PRINTING → COMPLETED|FAILED`（`DRAFT|QUEUED → CANCELLED`）。
重打新建任务，这样「第几次」永远是真的第几次。
`print_job_rollup` 另外钉住 `copies` / `target_count` / `label_count` / `template_id` 建立后不可改，
`label_count` 由 CHECK `= target_count * copies` 保证是算出来的而不是前端填的。

### 6. 前端 api-proxy 需要两处最小改动

- `apps/web/src/lib/api-proxy.ts` 的 warehouse allowlist 正则里**只追加**了 `printing(...)` 一段
  （没有整段重写那条正则，那是并行车道冲突高发区）。
- 响应处理：把打印内容（`text/vnd.zpl` / `text/html`）纳入已有的「原样透传」分支。
  理由：这两个都不是 JSON，走 `JSON.parse` 会被吞成一个**假的 503**，现场只看到「下载失败」。
  用白名单式列举（`text/csv` / `text/vnd.zpl` / `text/html`）而不是「非 JSON 就透传」——
  后者会让上游一个返回 HTML 的错误页被当成文件下载下来，更难排查。

## 涉及文件

**迁移**

- `backend/services/warehouse/src/main/resources/db/migration/V73__warehouse_label_printing.sql`

**后端**

- `backend/services/warehouse/src/main/java/com/starcon/warehouse/LabelPrintModels.java`（传输模型 / 枚举 / 能力声明）
- `backend/services/warehouse/src/main/java/com/starcon/warehouse/LabelRenderService.java`（ZPL / HTML 渲染 + Code128）
- `backend/services/warehouse/src/main/java/com/starcon/warehouse/LabelPrintingService.java`（jOOQ 显式 SQL）
- `backend/services/warehouse/src/main/java/com/starcon/warehouse/LabelPrintingController.java`（端点）
- `backend/services/warehouse/src/test/java/com/starcon/warehouse/LabelPrintingIntegrationTest.java`（26 个集成测试）

**前端**

- `apps/web/src/app/inventory/printing/page.tsx`
- `apps/web/src/components/inventory/label-printing-workspace.tsx`
- `apps/web/src/components/inventory/label-printing-model.ts`
- `apps/web/src/components/inventory/label-printing-model.test.ts`（14 个单测）
- `apps/web/src/lib/api-proxy.ts`（allowlist + 透传，两处最小改动）
- `apps/web/src/app/api/warehouse/[...path]/route.test.ts`（放行路径 + 透传用例）

## 关键 trade-off / 讨论

### 打印次数用「UPDATE … RETURNING」而不是「读出来 +1 再写回」

`update print_target set print_count = print_count + 1 … returning print_count`。
并发下两个任务同时重打同一条面单，各自的 `RETURNING` 会拿到不同序号，不会互相覆盖。
读改写会有 lost update。

### `asA` / `asB` 不能嵌套（写测试时踩到的真坑）

`set_config('app.current_tenant', …, true)` 是**事务级**的，而 `AccessService.run` 用
`TransactionTemplate` 默认传播 `REQUIRED` 会复用外层事务。在 `asB` 的 lambda 里再调 `asA`，
会把当前事务的租户悄悄改成 A，于是「B 拿不到 A 的数据」这种断言会**假绿**。
所以凡是需要在 B 的上下文里用到的 id，都必须先在 A 的上下文里取出来再传进去。
已在 `asA` 的 javadoc 上写明，避免下次再踩。

### DELETE 打印凭据是被哪一层拦下的

迁移里既有 `REVOKE DELETE ... FROM warehouse_app`（权限层），也有 `guard_inventory_append_only`
触发器（第二层）。应用角色 DELETE 时**先**被权限层拒，所以断言不能硬凑「append-only」文案 ——
测试改成断言「删不掉、条数不变」这个事实本身，UPDATE 则仍然是被触发器拦的（角色有 UPDATE 权限）。

## 后续待办

- **8.3.4.1 发货交接 / 10 物流交付后**：把面单数据的来源从「调用方传入」换成「10 物流推送」。
  要改的是「谁来填快照」，不是打印这条链路本身。
- **出库单（8.3.1.1，V72）交付后**：可考虑用 `outbound_order` 的真实单号做 `external_ref`，
  但**不要**建跨服务 FK。
- **ZPL 只覆盖了面单 / 条码 / 库位标签三类**；如果现场需要 TSPL / CPCL 指令流，
  扩展点是 `LabelRenderService.defaultFormatFor` 与 `PrintContentFormat`。
- **打印设备（`print_device`）目前只做登记与校验**，还没有做「设备在线状态 / 打印队列下发」——
  蓝图把「打印机配置」列为输入，没要求设备侧的连接管理。

## 关联

- 蓝图：`docs/product/capability-blueprint/08-warehouse-operations.md` 8.3.4.2
- 台账：`docs/product/capability-blueprint/module-progress.md` 8.3.4.2 行
- 多租户：`docs/decisions/0002-multi-tenancy-strategy.md`、`docs/architecture/multi-tenancy.md`
- 后端技术栈：`docs/decisions/0004-java-spring-boot-microservices.md`
- 商品本地开发：`docs/operations/catalog-local-development.md`
