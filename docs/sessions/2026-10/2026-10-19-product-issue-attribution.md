# 11.1.2.2 商品问题归因（trade 服务，阶段 D4）

日期：2026-10-19 · 分支：`feat/11-1-2-2` · worktree：`.worktrees/11.1.2.2`

## 决策摘要

蓝图原文（`docs/product/capability-blueprint/11-aftersales-and-returns.md` L3 `11.1.2` 的第二格）：

> 职责：把质量问题归因到具体环节（生产、运输、仓储、描述），支持批次追溯与照片比对，形成对供应商或流程的改进建议。
> 输入：售后原因、批次信息、退回实物照片、质检结论。
> 输出：归因结论 + 批次质量标记。
> 加粗句：**批次归因是「同一个批次退回来 10 件」和「10 个批次各退 1 件」的区别**，前者是供应商责任，后者可能是运输或买家因素。

落地为 **V180 五张新表 + 10 个端点 + 一个真实工作区页面**，trade 服务拥有归因事实。

## 关键 trade-off

### 1. 批次标记的判据落在**件数**，不落在归因单张数

最初把阈值列写成 `min_return_count` 并按 `suspect_return_count`（归因单张数）判定，
跑测试时立刻红了 —— 那会把蓝图加粗句**判反**：

| 现实 | 张数 | 件数 | 蓝图期望 |
|---|---|---|---|
| 一张单退 10 件 | 1 | 10 | **该标**（供应商责任） |
| 十张单各退 1 件（同批次） | 10 | 10 | 该标（同上，货件数相同） |
| 十个批次各退 1 件 | 每批次 1 | 每批次 1 | **一个都不该标**（运输/买家因素） |

⇒ 阈值列改名 `min_flag_qty numeric(19,6)`，CHECK 与守卫都比较 `affected_return_qty`。
按张数判的话，「一张单退 10 件」算成 1 件不标、「十张单各退 1 件」算成 10 标 —— 结论正好相反。

### 2. 照片比对只落**内容指纹**，刻意不做图像相似度

本仓没有图像库、没有训练好的模型、没有第三方视觉服务的接入点。
同一件商品在不同光线与角度下拍出的两张照片，sha256 必然不同。
所以：`PhotoCompareResult` 只有 `IDENTITY_SAME` / `IDENTITY_DIFFER` 两值，
`compare_basis` 是恒定枚举 `CONTENT_FINGERPRINT`（把口径写进**数据**而不只写在文档里），
DB 触发器拿两张 sha256 **真比对**，「指纹不同却写 SAME」在任何路径下都存不进去。

编一个 `0.87` 的相似度会让人以为系统看懂了照片，而它其实只比了字节 ——
语义级的「是不是同一处缺陷」由人工在归因说明里写。

### 3. 照片**不加** `UNIQUE(attribution_id, content_sha256)`

照抄 V172 的习惯时差点加上（V172 的 `return_receipt_photo` 有这个唯一索引）。
但**加了就让 `IDENTITY_SAME` 永远不可达** —— 同内容第二次登记会直接撞约束，
而「同一份内容被登记了两次」（同步器重复投递、客服又拍一遍同一个缺陷）
恰恰是这个比对要识别的最主要场景。去掉唯一索引，重复提示交给统计与界面，不靠索引堵死。

### 4. 改进建议是**必填人写文字**，刻意不标成 AI 生成

职责句明写「形成对供应商或流程的改进建议」，所以 DB CHECK 拒空。
但本仓**没有模型接入点** —— 标成「AI 建议」就是在冒充智能能力。

### 5. 结论终态不可逆，但被取代的那一版**保留当时的环节与时刻**

部分唯一索引 `product_issue_one_effective_per_case`（`WHERE attribution_status <> 'SUPERSEDED'`）
保证一单只有一版生效。但最初把「未归因不许带结论」写成
`status = 'ATTRIBUTED' OR (stage IS NULL AND attributed_at IS NULL)`，
结果 `ATTRIBUTED → SUPERSEDED` 会被 CHECK 拒 —— 那等于逼着改判抹掉痕迹。
修正为只约束 **DRAFT**；SUPERSEDED 保留当时的环节与时刻，因为那正是改判要留的痕，
抹掉的话改判就变成了「从来没有过那个结论」，而纠纷里的对手方会问「你们当初凭什么是运输责任」。

### 6. 跨服务边界：批次只读，绝不写 warehouse

批次真值在 **warehouse 独立库**（7.3.1.1 / V57 `inventory_lot`）。
本库**根本没有那张表**（测试直接断言这一点），归因单零外键。
读法是 `ProductLotTracePort`（RestClient）转发**调用方自己的 Authorization bearer**，
warehouse 侧仍按已认证成员关系解析租户并强制 RLS；**一次都不传 `X-Tenant-Id`**
（测试把桩收到的全部请求头记下来断言，让这条纪律成为可跑的检查）。
批次质量标记是 **trade 侧基于归因事实的统计结论**，不是 `inventory_lot` 的副本：
后者记「这个批次有多少货」，本模块记「这个批次退回来多少、归到了哪些环节」。

### 7. 下游未交付的一律显式报 false

6.4.2.2 索赔、6.1.1.3 评级、11.5.2.1 质量分析**本批均未交付**。
capabilities 逐条报 `delivered=false` + 归属模块 + `href=null`
（一个点进去 404 的链接比没有链接更糟），页面也不渲染对应按钮。

## 开工前的依赖自检（10 分钟闸）

全部在集成头 `0c2d4f97` 里 grep 到，未触发 BLOCKED：

- `inventory_lot` —— warehouse V57 + `InventoryLotController`（`GET /lots/{lotId}` 等）
- 11.2.3.1 质检 —— `ReturnQuality*` + 迁移 V179
- 11.1.2.1 原因分类（同一 L3 的兄弟）—— `AftersalesReason*` + 迁移 V158
- trade 最大迁移号 = V179 ⇒ 本车道用 **V180**（预分配号，未自己找最大号）

## 过程中修掉的 6 个真 bug（都是自己写错的，不是环境问题）

1. **`REVOKE DELETE` 与整单替换流程自相矛盾** —— 照抄 V179 的「举证材料追加式」时把
   `product_issue_photo_comparison` 也收回去了，可服务层在 DRAFT 下要对照片做整单替换。
   V179 的正确分界是：`return_quality_check_result`（草稿态行级材料）保留 DML，
   只收回 `disposition_instruction`（交接凭据）与 `event`（留痕）。照此改正。
2. **CHECK 约束与改判留痕冲突**（见上 §5）。
3. **照片 UNIQUE 让 `IDENTITY_SAME` 不可达**（见上 §3）。
4. **阈值按张数判反**（见上 §1）。
5. **`fetchOne` 无行时返回 `null` 而不是空记录** —— 直接 `.get(0, …)` 抛 NPE，
   NPE 会被当成 500 返回，把「跨租户访问」伪装成「服务端故障」。三处已加 null 判断。
6. **测试按下标取照片 id** —— 照片按 `(registered_at, id)` 排序而 id 是随机 UUID，
   顺序不可预期 ⇒ 改成按对象键取。
   同类：测试桩把已拼好的 JSON 又套了一层 `json()`，多编码一次变成 JSON 字符串字面量，
   Jackson 报成「no String-argument constructor」（症状像形状不对，病因是多编码一层）。

## 文件清单

**迁移**
- `backend/services/trade/src/main/resources/db/migration/V180__product_issue_attribution.sql`
  （五张表 + 4 个守卫触发器 + FORCE RLS；`trg_guard_product_issue_attribution` 守卫①-④、
  `trg_guard_product_issue_stage` 环节相容性、`trg_guard_product_issue_photo_comparison` 比对真实性、
  `trg_guard_product_issue_lot_flag` 统计重算比对）

**后端**
- `ProductIssueAttributionModels.java`（传输模型 + 六值枚举 + 能力声明记录）
- `ProductIssueAttributionService.java`（jOOQ 显式 SQL，本模块五张表的写入所有者）
- `ProductIssueAttributionController.java`（10 个端点）
- `ProductLotTracePort.java`（跨服务读端口，转发 bearer，绝不传 `X-Tenant-Id`）

**测试**（L1 共 21 例全绿）
- `ProductIssueAttributionIntegrationTest.java`（13 例：三条硬规则 + 反例）
- `ProductIssueAttributionTenancyIntegrationTest.java`（4 例：租户三断言，连接池固定为 1）
- `ProductLotTracePortIntegrationTest.java`（4 例：跨服务边界）

**前端**
- `apps/web/src/app/aftersales/attributions/page.tsx`
- `apps/web/src/components/aftersales/product-issue-attribution-model.ts`
- `apps/web/src/components/aftersales/product-issue-attribution-model.test.ts`（11 例）
- `apps/web/src/components/aftersales/product-issue-attribution-workspace.tsx`
- `apps/web/src/app/api/trade/product-attribution-allowlist.test.ts`（5 例，双向断言）
- `apps/web/src/lib/api-proxy.ts`（**新增一条独立 `services[]` 元素**，44 增 0 删）

**契约（codegen 产物，未手改）**
- `packages/api-contracts/src/contracts/trade.ts`（+23 DTO，**0 删除**）
- `packages/api-contracts/src/trade.openapi.json`

**台账**
- `docs/product/capability-blueprint/module-progress.md`（**仅第 365 行**：状态 + 证据两格）

## 验证

- L0：`./mvnw -q compile` 通过；`pnpm typecheck` 3/3 通过
- L1：`ProductIssueAttribution*` + `ProductLotTrace*` 共 **21 例全绿**
- api-proxy 全量 allowlist 回归：`apps/web/src/app/api/trade` **41 文件 / 218 例全绿**
  （逐个单跑确认：40 个兄弟车道文件 + 我自己的，全过 ⇒ 没取错一侧）
- 三方比较以 base 为裁判：`api-proxy.ts` diff 为 **44 增 / 0 删**，
  `services[]` 元素 40 → 41，`prefix: 'trade/'` 条数 40 → 41
- 契约差异检查：trade.ts 导出类型 942 → 965，**丢失 0 个**

## 后续待办

1. **6.4.2.2 供应商索赔**（未交付）—— 消费本模块的归因结论与批次标记作为举证材料。
   本模块已把口径定义清楚并可通过 HTTP 取用，接入时不必改本模块。
2. **6.1.1.3 供应商评级**（未交付）—— 批次质量标记是其输入之一。
3. **11.5.2.1 质量分析**（未交付）—— 缺陷类型分布 / 批次质量趋势 / 质量成本估算。
   本模块的 `stage_breakdown` 与批次计数是它的取数面。
4. **11.5.2.1 的缺陷照片聚类**若真要「语义级」相似度，需要先有图像库 / 模型 / 视觉服务接入点。
   在那之前，照片比对的口径**必须**保持为内容指纹，不要为了「看起来更聪明」而编相似度。
5. 批次质量标记目前支持 `SUSPECT` / `CONFIRMED` 两档，`CONFIRMED` 需人工给理由；
   若将来要让「同一供应商多批次同时命中」自动升级为 `CONFIRMED`，
   那是 11.5.2.1 的阈值口径问题，不该塞进本模块。

---

## 接手记录（15:03–15:30，同日第二位 worker）

第一位 worker 在 13:00–13:27 之间因 **Token Plan 用量上限（错误码 42212）被运行时强杀**，
留下约 5800 行**未提交**的工作（4 个已跟踪文件被改 + 12 个未跟踪新文件 + 本文件）。
其会话已终止，最后一次写入 13:26:45。接手时 `HEAD` 仍是基线 `0c2d4f97`，**一个提交都没有**。

### 接手时的判断依据

`git status --porcelain` 显示的清单与派工单一致，**但派工单里「已有 1 个 commit」是错的** ——
那个 commit 就是基线本身。先摸清状态再动手，而不是照着清单直接往下做。

### 独立复核结论（不采信前一位的自述，全部重跑）

| 项 | 结论 |
| --- | --- |
| 半成品扫描 | 全部新文件 grep `TODO/FIXME/UnsupportedOperationException/DomainPlaceholder` → **零命中**，无假分页 / 假搜索 / 假校验 |
| 上游依赖 | 全部 grep 到实：7.3.1.1 `InventoryLotController`（`/warehouse/inventory/lots`，V57）、11.2.3.1 V179、11.1.2.1 `AftersalesReason*`（V158）、V161/V165/V172/V178。**未出现上游未交付 → 无需 BLOCKED** |
| L0 后端编译 | `./mvnw -q compile` EXIT=0 |
| L1 后端 | `Tests run: 21, Failures: 0, Errors: 0`，BUILD SUCCESS，迁移实跑 45 个到 v180 |
| 前端 allowlist 回归 | `apps/web/src/app/api/trade` **41 文件 / 218 例全绿** |
| 前端 model 单测 | 11 例全绿 |
| typecheck / lint | 各 3/3 successful（lint 余下 warning 均属其它文件的既有问题） |

### 复核中真正抓到并修掉的一处：契约的「假删除」

`git diff packages/api-contracts/src/contracts/trade.ts` 显示 **53 行 `-export type`**，
其中包括兄弟车道 11.2.2.2 的全部 `ReturnAllocation*` 和 11.2.3.1 的 `QualityAction` ——
按 diff 看，像是**重生成把别人的契约删了**（正是 api-proxy 那条纪律警告的同一类事故）。

按行看会误判。**改成按类型名做集合比对**，并以**真基线 `0c2d4f97`**（不是自己的 HEAD，
我第一版脚本就是错拿了 `HEAD`，等于拿自己跟自己比，得出「0 增 0 删」的假结论）为准：

- schemas **942 → 965，删除集为空**，新增恰 23 个且全部本模块
- paths **0 删除 / 10 新增**（10 条全是 `/trade/product-attribution/*`）
- `trade.ts` export type **942 → 965，删除集为空**

结论：53 行删除**全部是字母序重排的位移**，契约是纯超集，无兄弟车道损伤。

### 台账里改正的一个数字

原台账证据格写「**契约差异 0 删除（2989 DTO 全在，23 个新增）**」——
「2989 DTO」这个数字**核对不上**，实测口径是 942 → 965 的 schema 数。已改成上面这组可复现的数字，
并补上了原先缺失的 **L1 测试数与多租户三断言**证据（原格只写了「typecheck / lint / vitest 全绿」）。

### 增量提交（本车道 4 个 commit，未 push）

| commit | 内容 |
| --- | --- |
| `0e298022` | 后端服务层 + 迁移 V180 + 批次追溯端口 |
| `6e0c221a` | 后端测试 21 例 |
| `8e779781` | 契约 + 前端模型/工作区 + api-proxy 白名单 |
| （本条提交） | 台账证据格修正 + 接手记录 |

> 上一位之所以全军覆没，就是因为攒到最后一次性提交。
> 本次每凑齐一个可独立成立的小块就立刻 commit —— 宁可留下已提交的半成品，
> 也不要留一个干净但丢失的工作区。
