# 批次 24 · 4.2.1.2 批量编辑并入 + 失联车道取证

日期：2026-10-19 10:00–10:30（cron tick）
集成头：`efda503d` → `611da90c`

## 结论

本轮零派发。做完三件事：解掉阻塞两轮的车道冲突、跑完批次闸并 push、把上一轮
暴毙造成的 3 条孤儿车道取证清楚并交还。

进度 **199 → 200 已交付**（465 中，43.0%）。

---

## 一、根因：上一轮不是「停滞」，是暴毙

09:25:55 会话 `mvs_95c8ef65b9424a50a4359fd43eed28b4` 以 `42212`
（Token Plan 用量上限）终止。它的 5 条车道随之全部孤儿化。

**取证不是靠心跳，而是靠会话状态**：`session get` 直接返回
`status.type = "error"` / `errorCode: 42212`，`updatedAt=1792373155067`
= 09:25:55 —— 与三个 worktree 里最后一批文件写入时间（09:25:20–09:25:53）
**逐条吻合**。心跳当时只有 6–13 分钟新鲜度，光看 `lanes.json` 会误判成
「还在跑」。

⚠️ **上一轮汇报把 `api-proxy.ts` 冲突说成「结构性、需你裁决」——这是错的。**
它可机械判定，见下节。这个误判让一条已完成车道多卡了两轮。

## 二、`api-proxy.ts` 冲突：可判定，不该上报裁决

冲突只在 `apps/web/src/lib/api-proxy.ts` 的**同一个 `services[]` 锚点**，
两侧各自**新增一条独立元素**：

- ours（集成头已交付）：2.6.2.1 增量补拉 → `backfills`
- theirs（4.2.1.2）：批量编辑 → `listing-bulk-edit`

不是同一条 alternation 里并分支，**解法就是两条都保留**，没有取舍空间。

该文件是共享累积白名单，取错一侧会静默删掉别人已交付的路由，且
typecheck / lint / vitest / Playwright **全绿** —— 所以并入时做了三重取证：

1. **逐条正则集合比对**（以 base `8300d842` 为裁判）：车道新增的 1 条
   （`listing-bulk-edit`）保留 ✓；集成头新增的 pattern 全部保留 ✓；
   无凭空发明 ✓。
2. **争议行三方比对**：channel 那条超长 alternation 上 `base === theirs`，
   即**车道从未碰过它**，差异全部来自集成头侧新增 `webhook-dedup` ——
   按「取变的那边」保留集成头版本是正确的。
3. **全量 allowlist 回归**（该文件的强制例外）：
   `pnpm vitest run apps/web/src/app/api/trade apps/web/src/lib`
   → **49 文件 / 583 例全绿**。

> 第一版校验脚本误报「丢了 theirs 的一条 pattern」，根因是它按 base 相对比较
> 却当成绝对比较。**并入共享白名单的校验脚本必须以 base 为裁判**，
> 否则会把集成头侧的新增误判成车道的删除。

## 三、L2 批次闸（全绿）

| 层 | 结果 |
| --- | --- |
| 后端 `mvnw verify` | **BUILD SUCCESS**，4927 例 0 失败 0 错误，16:50（identity 59s / catalog 1:50 / channel 46s / warehouse 8:24 / trade 4:43 / crm 6s） |
| `pnpm typecheck` | 3/3 通过 |
| `pnpm lint` | 通过（仅既有 `react-hooks/exhaustive-deps` warning） |
| `pnpm test` | **238 文件 / 4080 例全绿** |
| Next production build | 成功（`NEXT_TELEMETRY_DISABLED=1`） |
| `sync.mjs --check` | 一致；台账 465 行、无重复 ID |

`--check` 首次报不一致，原因是并入后台账总览的**派生计数**没跟着重算
（199→200）。已逐行核对（`git diff -U0` 过滤模块行）确认**模块行零改动**，
只刷汇总表，单独 commit `611da90c`。

**push**：`efda503d..611da90c` → `upstream/integration/module-dev`（未碰 main）。

## 四、3 条孤儿车道：work 在，但没 commit

上一轮会话暴毙时三条车道**都还没提交**，成果只存在于 worktree 的未跟踪文件里：

| 车道 | 模块 | 迁移 | 未提交体量 | 分支状态 |
| --- | --- | --- | --- | --- |
| 4.1.2.1 | 映射管理 | V176 | 5 文件 / ~2,724 行 | HEAD 已是集成头祖先（**无独有 commit**） |
| 11.2.2.2 | 实收分配 | V178 | 工作区干净 | **1 个独有 commit** `cb24502f` |
| 11.2.3.1 | 质检与判定 | V179 | 6 文件 / ~5,653 行 | HEAD 已是集成头祖先（**无独有 commit**） |

- **11.2.2.2 是唯一可直接回收的**：已 commit 且工作区干净，`land` 后即可并入。
- **4.1.2.1 / 11.2.3.1 的成果一个 commit 都没有**。它们**不能**按「超时车道重派」
  处理 —— 重派会在同一 worktree 里开第二个 writer，与那几千行未提交代码正面冲突。
  正确做法是**先由新 worker 接管认领**（读现有文件、跑 L1、commit、land）。

本轮未派发：孤儿车道需要的是「接管认领」而非「新建车道」，任务书要逐条写明
现有代码状态与所有权移交，和 `plan` 产出的全新车道不是一回事，混在一次 `plan`
里会丢掉这个前提。留给下一 tick 单独处理。

## 五、遗留（不属本车道，未改）

- **`ListingIntegrationTest` 的 `AMAZON` 常量 `1111…-111` 被 V1 播种成了 ETSY**，
  `on conflict (id) do nothing` 静默失效。因该测试不断言平台 code，错误从未暴露。
  4.2.1.2 绕开了，**根因仍在**。
- **3 个 14 小时前泄漏的孤儿 PG 容器**（`w11311-mig` / `pg167v61687` / `pg167v61661`），
  Testcontainers 的 ryuk 未回收。非本轮产生，未擅自清理。
- **批量目标集合无条数上限**，「一键改全店」目前靠前端传 id 列表。

## 六、协议层面的两条修正建议（待用户裁决，未自行改）

1. **`lanes.json` 无法区分「真在跑」与「owner 已死」。** 心跳新鲜度在暴毙场景下
   完全无判别力（本例 6–13 分钟）。建议 `lane.mjs` 在写心跳时记录 owner session，
   由调度侧交叉核对 `status`，而不是只比时间戳。
2. **`api-proxy.ts` 的冲突是机械可判定的**（两侧各新增独立元素），
   却在 `merge-union.mjs` 里落到「行级兜底并集」⇒ 必然冲突。可给
   `services[]` 元素加一层结构化求并（键 = pattern），让这类并入自动完成。
