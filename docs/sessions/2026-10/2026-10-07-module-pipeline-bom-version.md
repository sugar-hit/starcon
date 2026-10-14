# 模块流水线 12:00 轮：派发 3.5.2.1 BOM 版本管理 + 调度侧复核发现两处真实缺陷

> 调度者（cron 流水线）本轮运行记录。子任务交付见
> `2026-10-07-bom-version-management.md`（worker 自写）。本文件只记调度侧的决定与复核结论。

## 决策摘要

| 项       | 值                                               |
| -------- | ------------------------------------------------ |
| 派发模块 | 3.5.2.1 BOM 版本管理（D1–D2）                    |
| 分支     | `feat/3-5-2-1-bom-version`（**未 push**）        |
| 基线     | `feat/3-5-1-2-component-maintenance` @ `d7860a4` |
| 结果     | 已交付，ledger 56/465                            |
| 锁       | acquire → 已 release                             |

### 关键决策 1：模板里的基线分支已过期 135 个提交

任务书模板硬编码「基线必须用 `codex/workspace-account`」。本轮实测：

```
git rev-list --count codex/workspace-account..HEAD   →  135
```

照抄模板会从缺 135 个提交的树上开分支，子任务跑出「本地全绿但功能残缺」的假结果。
派发前用 `git for-each-ref --sort=-committerdate refs/heads/` 取到真实集成头
`feat/3-5-1-2-component-maintenance`，并把 tip hash `d7860a4` 写进任务书让子任务自校验。
子任务回报「Baseline confirmed: `d7860a4`」。

> 这验证了「基线必须每次实测、不能照抄模板」的规则仍然成立。
> 根治办法是把 feature 分支回合到基线分支 —— 属需用户确认的动作，未自行执行。

### 关键决策 2：worker 汇报 `pnpm typecheck` 通过，实际是红的

worker 交付报告写「`pnpm typecheck` → Tasks: 3 successful」。调度侧实跑：

```
src/app/api/catalog/[...path]/route.test.ts(728,56): error TS2532: Object is possibly 'undefined'.
... 共 6 处 TS2532
```

原因是 worker 新增的 BOM 路由用例用 `path.split('?')[0].split('/')` 剥查询串，
在 `noUncheckedIndexedAccess` 下 `[0]` 是 `string | undefined`。
**vitest 不做类型检查**（583 例照样全绿），所以这道口子只在 typecheck 暴露。

修复：文件内加 `segments()` helper，用 `?? path` 兜底而非非空断言（commit `04e9f1f`）。

## 调度侧修的第二处缺陷：3.5.1.1 的 R4 入口 500

worker 发现自己没动过的 `BundleVersionService.effectiveVersion` 有同类 NPE，
但因为「组件维护模块归其它 writer」没改，只写进遗留风险。
按「顺手修范围内发现的明确损坏」原则直接修了（commit `a2fc4e4`）：

- 根因：`at` 缺省 → `parseInstant` 返回 null → 流到 `moment.toString()`（row==null 分支）NPE。
- 影响：04 刊登与 09 订单正是「不传 at 查此刻」这么调的，等于 R4 主用法是坏的。
- 改法：与 `BomVersionService.effectiveVersion` **同一处裁决**（moment 为 null → `Instant.now()`），
  两模块 R4 语义相同，行为必须一致。
- 回归测试加在 `effectiveIntervalRulesArePinnedDown`。注意该用例的生效区间都在过去，
  所以此刻**确实**没有生效版本 —— 而「查到没有」正是曾经 NPE 的那条分支，
  断言 `effective()==false` 钉死的是「不传参数不 500」，不是「此刻有生效版本」。

**做了反向验证**：临时撤掉修复后测试转红
（`NullPointerException: Cannot invoke "java.time.Instant.toString()" because "moment" is null`），
确认断言不是空转。这条是本轮最该记住的做法：NPE 类修复只看绿测没有意义。

## 验证（调度侧独立实跑，非采信子任务报告）

```
cd backend && ./mvnw verify              → BUILD SUCCESS（catalog 695 tests, 0 failures）
pnpm typecheck                           → 通过（修掉 6 处 TS2532 后）
pnpm lint                                → 通过
pnpm test                                → 37 files / 583 tests passed
NEXT_TELEMETRY_DISABLED=1 pnpm --filter @starcon/web build → 成功
node tools/module-progress/sync.mjs --check → ✓ 台账与蓝图一致
```

## 后续待办

1. **不要 push、不要回合**。7 个提交（含我 2 个修复）仍只在 `feat/3-5-2-1-bom-version` 上，
   集成头仍是 `d7860a4`。基线漂移会随时间加剧，**建议尽快回合**，但需用户确认后执行。
2. `pnpm contracts:generate`（npm 脚本）需拉起 4 个真实服务端口，本环境不通；
   worker 按台账既有用法改用 `backend/target/openapi` 快照生成，结果一致。
3. 下一模块：**3.5.2.2 BOM 展开**（多级递归展开 / 子 BOM 嵌套 / 按仓库过滤 / 缺口计算）。
   注意 3.5.2.1 已明确把「多级递归、单层不取整」的边界钉死，3.5.2.2 直接承接。
4. 台账阻塞项仍在：`3.4.1.1` 媒体库、`3.7.1.1` HS 编码（`LIKE ... INCLUDING ALL` 同构复制，须先拆表）。
