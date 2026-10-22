# 14.3.2.2 效率诊断（卷 14 广告与增长，L3 锚点 14.3.2）

- **日期**：2026-10-22
- **车道**：`feat/14-3-2-2`（worktree `integration/.worktrees/14.3.2.2`）
- **蓝图**：docs/product/capability-blueprint/14-ads-and-growth.md 第 185–190 行
- **迁移**：channel `V280__ad_efficiency_diagnostics.sql`

---

## 决策摘要

**职责句逐项落点**：「诊断投放效率问题：高花费零转化、ACOS 长期超标、预算受限、竞价过低曝光不足、
创意衰退，给出可执行建议与一键调整」。

| 蓝图词 | 落地 |
| --- | --- |
| 五种效率问题 | 五种**判定口径**（`rule_type`）写死在 DB 的 CHECK 里；每种口径的**阈值**是租户可配的库行（`ad_diagnosis_rule`），不是硬编码常量 |
| 诊断规则（输入） | `ad_diagnosis_rule` + `POST /rules` + `POST /rules/defaults`（默认阈值落成**真实库行**，可逐条改） |
| 诊断清单（输出） | `ad_diagnosis`：观察量快照（可复算）+ `evidence` 判定依据 + `recommendation` 可执行建议 |
| 建议动作（输出） | `ad_diagnosis_action`：动作类型 + 可用性 + 建议幅度 + 「本来会变成多少」+ 执行结论 |
| 一键调整 | `POST /actions/{uuid}/apply` —— 预算 / 暂停**现在就能做**；竞价**显式不可用** |

### 关键 trade-off

**1. 上游 14.3 报表是「业务上下游」，不是代码依赖。**
同批的 14.3.1.1 活动报表（V279）此刻不在本车道。诊断指标**全部从 14.1.1.1 已交付的事实表算**
（`ad_campaign` + `ad_campaign_daily_metric`），**不 import 14.3.1.1 的任何类**。
代价：与 14.3.1.1 各算各的比率口径；收益：两条车道零耦合、可独立交付，且 14.3.1.1 的报表口径
（ACOS / ROAS / TACOS 的表头定义）落地后，诊断侧无需改代码。

**2. 「一键调整」复用 14.1.1.1 的写路径，而不是自己写一套。**
实测：14.2.2.2 自动规则（`ad_rules`）**不写 `ad_campaign`** —— 它只落自己的 `ad_rule_execution` +
`ad_rule_dispatch`（待下发队列），并没有改活动。真正能改活动、且带**上限 / 审计 / 幂等**的已交付资产是
14.1.1.1 的 `AdCampaignService.bulkOperation`。
⇒ 诊断的「一键调整」**调用**它：上限（单次 5000 / 总额 20000 / 单活动 100000）、审计
（`ad_campaign_operation_log`）、幂等（已是目标状态 ⇒ SKIPPED）全部复用，**不另写一套绕过护栏的写路径**。
代价：本模块要显式处理「写路径的前置拒绝」（它抛 400 而不返回逐条结果）——已翻译成
`outcome=REJECTED` + 补写审计行，否则「为什么这条没生效」查不出来。

**3. 「竞价过低曝光不足」在没有竞价数据时怎么判。**
`ad_campaign` / `ad_campaign_daily_metric` **都没有出价列**（出价在 14.1.3.1 的 `ad_keyword.bid`，
但那是词级、与活动级诊断不同口径）。因此该诊断用**可观测的替代量**：日均曝光 / **同平台在投活动中位数**
（`percentile_disc` 离散中位数，全程 numeric，无浮点），且**与「预算受限」互斥** —— 预算是瓶颈时
只报预算受限，不重复报。结论里写「曝光不足，常见原因是竞价过低」，而**建议动作（提高竞价）标为不可用**。
代价：诊断是「症状 + 假设」而不是「确诊出价过低」；收益：不用假数据、不越界读别的模块的表。

**4. 创意衰退：显式标为数据源未就绪，不产出结论。**
创意数据在已交付上游里没有来源（14.1.1.2 是同批并行车道）。对应规则 `data_source_ready=false`，
引擎**直接跳过**并在 `ad_diagnosis_run_rule` 写明「数据源未就绪」—— 不用随机数或假曲线假装有创意数据。
连带效果：`ad_diagnosis_run_rule` 这张表的存在理由就是「**没产出结论也要留痕**」（「没跑」与「跑了没命中」是两件事）。

**5. 试运行的两侧开关与「取更严的那个」。**
规则级 `dry_run`（这条规则只出建议、不落地）与请求级 `dry_run`（这一次点是不是只算）**取更严的那个**。
默认规则**规则级关、请求级开** ⇒ 点一下默认只算出「本来会变成多少」，真实执行要显式确认。
手工新建的规则**规则级默认开**（阈值还没验证过，不该动钱）。
代价：多一个开关要解释；收益：照抄 14.2.2.2 的「必须有上限与试运行」纪律，且**请求侧默认开**保证
「随手一点不会真的调预算」。

---

## 文件清单

### 后端（channel 服务）

- `backend/services/channel/src/main/resources/db/migration/V280__ad_efficiency_diagnostics.sql`（新建）
  五张表 + 2 个触发器 + 8 个索引 + RLS/data_scope + GRANT/REVOKE。**零 ALTER 任何既有表**。
- `backend/services/channel/src/main/java/com/starcon/channel/AdEfficiencyDiagnosisModels.java`（新建）
- `backend/services/channel/src/main/java/com/starcon/channel/AdEfficiencyDiagnosisService.java`（新建）
- `backend/services/channel/src/main/java/com/starcon/channel/AdEfficiencyDiagnosisController.java`（新建）
- `backend/services/channel/src/test/java/com/starcon/channel/AdEfficiencyDiagnosisIntegrationTest.java`（新建，14 例）

### 前端

- `apps/web/src/lib/api-proxy.ts`（**仅新增一条** `ad-diagnostics/` 元素，既有 100+ 条一字未动、不重排）
- `apps/web/src/components/ads/ad-efficiency-diagnosis-model.ts`（新建）
- `apps/web/src/components/ads/ad-efficiency-diagnosis-model.test.ts`（新建，24 例）
- `apps/web/src/components/ads/ad-efficiency-diagnosis.tsx`（新建）
- `apps/web/src/app/ads/diagnostics/page.tsx`（新建）
- `apps/web/src/app/api/channel/ad-diagnostics-allowlist.test.ts`（新建，5 例）

### 契约 / 台账 / 日志

- `packages/api-contracts/src/channel.openapi.json` + `src/contracts/channel.ts`（**codegen 产物，纯增量 +12 path / +17 schema / 0 删除 / 0 修改**）
- `docs/product/capability-blueprint/module-progress.md`（**只改本模块那一行**的状态与证据两格，不重排整表）
- `docs/sessions/2026-10/2026-10-22-14-3-2-2-efficiency-diagnosis.md`（本文件）

---

## 首 token 零命中核对（api-proxy.ts）

在 `apps/web/src/lib/api-proxy.ts` 里核对首 token **`ad-diagnostics`**：

```
$ grep -c "ad-diagnostics" lib/api-proxy.ts
0
```

本文件既有 100+ 条 `services[]` 正则里 **`ad-diagnostics` 一次都没出现**
（既有的是 `ad-campaigns` / `ad-keywords` / `ad-rules` / `ad-type-diffs` / `ad-campaign-details` 一类）。
⇒ 首 token 既是独立字面量，也不会被既有分支的前缀吃掉。

**插入锚点**：10.4.2.2 的 `stock-forecasts` 元素**之前**（`prefix: 'channel/'`，
`pattern` 首 token 为 `^stock-forecasts`，全文件唯一，base 约第 5344 行）。
同批 14.1.1.2 活动详情的落点在 `compliance-precheck` 之前，与本处相隔 60+ 行 ⇒ 两个 hunk 不重叠，git 能干净求并。

---

## 验证

| 闸 | 命令 | 结果 |
| --- | --- | --- |
| L0 编译 | `backend/ && ./mvnw -q compile` | ✅ 通过 |
| L0 类型 | `pnpm typecheck` | ✅ 三包绿 |
| Lint | `pnpm lint` | ✅ 绿（仅存量告警，均在本车道之外的文件） |
| L1 模块闸 | `./mvnw -pl services/channel -am test -Dtest='AdEfficiencyDiagnosis*' …` | ✅ **14 例全绿** |
| 前端回归 | `pnpm vitest run apps/web/src/app/api/channel apps/web/src/app/api/trade` | ✅ **608 例绿 / 116 文件** |
| 前端本模块 | `pnpm vitest run <本模块两个测试文件>` | ✅ 29 例绿（24 模型 + 5 allowlist） |

L1 覆盖的关键断言：租户隔离三断言（两租户互不可见 / 伪造 `X-Tenant-Id` 头无效 /
连接池压到 1 的 A→B→A 三轮不串租户）、四种判定口径各命中一条且互不串台、
观察量快照可复算、ACOS 不可比给空值而 ROAS 真的是 0、创意衰退不产出结论、
一键调整四道闸（试运行只算不调 / 真实执行落本地并留审计 / 重复提交幂等 / 护栏越限拒绝并留痕）、
竞价动作点不动（409 + 写明 14.2.1.1）、DB 层兜底（SQLSTATE 23514）、
真分页真筛选、跨租户与不存在返回逐字相同的 404。

**契约**：`python3 tools/codegen/catalog-contracts.py --spec-dir backend/target/openapi` ⇒
channel 纯增量（+12 path / +17 schema / 0 删除 / 0 修改）。
**trade 契约的既有漂移已复原**（集成头上 trade 契约与本车道无关的存量漂移，刻意不越界改）。

---

## 后续待办

1. **14.2.1.1 竞价管理交付后**：把 `INCREASE_BID` 动作从 `UNAVAILABLE` 改成可执行，
   并接上 14.2.1.1 的写路径与它的护栏。
2. **14.1.1.2 活动详情的创意子资源交付后**：把 `CREATIVE_DECAY` 规则的 `data_source_ready` 打开，
   并补上「创意衰退」的判定口径与阈值。
3. **平台回写队列交付后**：预算 / 暂停的「待回写平台」标记可撤。
4. **14.3.1.1 活动报表交付后**：核对两侧的 ACOS / ROAS 口径是否一致；本模块的比率一律
   「先汇总再相除」，与 14.1.1.1 同口径，理论上无需改动，但**跨部门沟通前应确认一次**。
5. **14.2.2.1 预算分配交付后**：本模块只做「单条活动的建议动作」，跨活动再平衡仍归 14.2.2.1，
   两者不要互相顶替。
