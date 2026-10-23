# 2026-10-23 12.1.2.1 导航注册遗漏修复（阶段 D6，卷 12 L3 12.1.2）

分支 `fix/12-1-2-1-nav-route-registration`，基线 `integration/module-dev`（6a6c94f8 merge(12.2.1.1)）。
一个 atomic commit，只动一个布尔值。

## 摘要

上一批 12.1.2.1 应付单管理交付后，集成头的 L2 批量验证里**前端全量测试红了 2 条**，
两条都来自 `packages/ui/src/lib/nav-routes.test.ts`，根因是同一条注册不变式被打破：

> 对 `packages/ui/src/lib/task-pages.ts` 里的每一个条目：`implemented === 真实文件系统里该路由页面是否存在`

12.1.2.1 车道交付时**刻意没有**改 `task-pages.ts`（该文件不在仓库 merge-union 驱动器覆盖范围内，
同批多车道并发改它必然 git 冲突 —— `.gitattributes` 只给 `packages/api-contracts/src/**` 与
`module-progress.md` 配了驱动）。但 `/finance/payables` 这个 href **在本批之前就已预登记**在该文件里，
且标记是 `implemented: false`（一个「规划中、尚未实现」的占位条目）。车道把真实页面建出来后，
占位标记没有被翻正 ⇒ 不变式破了。

本车道把 `/finance/payables` 这一条的 `implemented` 由 `false` 翻成 `true`，
让**实现与登记对齐**（而不是让测试闭嘴）。

## 决策 / 结论

1. **只翻 `/finance/payables` 一条，不动其余 26 条同类占位。**
   `task-pages.ts` 里还有 26 个 `implemented: false` 的占位 href
   （`/listings/drafts`、`/purchase/plans`、`/crm/inbox`、`/finance/reconciliation`、
   `/finance/exchange`、`/org/stores` 等）—— 那些模块的页面**还没建**，翻了标记会让同一个测试
   以另一种方式红（`标记=true 实际=false`）。本车道已核验：改后这 26 条**逐条保持 `false`**，
   `/finance/payables` 已不在其中。

2. **不需要改 `nav-config.ts`，一个布尔值就够。**
   `nav-config.ts:135-145` 的 `SYSTEMS` 用 `IMPLEMENTED_TASK_PAGES` 按 `domain` 派生菜单 `children`
   （`page.domain === item.href.slice(1)`），`NAV_DESTINATIONS` 再由 `SYSTEMS` flatMap 出来。
   `/finance` 的 `item.href` 是 `/finance` ⇒ slice(1) 得 `'finance'` ⇒ 与本条 `domain: 'finance'` 匹配。
   ⇒ 翻成 `true` 后菜单入口**自动生成**，且 `NAV_DESTINATIONS` 恰好 +1，
   与「已实现任务页与菜单条目一一对应」那条计数不变式（`NAV_DESTINATIONS.length === 业务已实现页数`）
   天然配平，不需要手工补 nav 条目。

3. **不动页面、不动测试、不动台账、不动 `api-proxy.ts`、不动后端。**
   `apps/web/src/app/finance/payables/page.tsx` 本身是对的；`nav-routes.test.ts` 发现的**是真问题**，
   不改；台账 `module-progress.md` 的 12.1.2.1 行已由调度者处理，本车道不碰；
   `api-proxy.ts` 是共享白名单，与本车道无关；本车道**不需要**任何 Flyway 迁移
   （没有预分配迁移版本号），`backend/` 一字未动。

## 涉及文件

**修改**（唯一一个文件、一行）：

- `packages/ui/src/lib/task-pages.ts:505` —— `/finance/payables` 条目的 `implemented: false` → `true`。
  `href` / `domain` / `label` / `description` / `fields` / `workflow` 六个字段**逐字未动**，
  顺序、缩进、空行均未动。

**新增**：

- `docs/sessions/2026-10/2026-10-23-fix-12-1-2-1-nav-route-registration.md`（本文件）

## 关键 trade-off

| 选项                                        | 选哪个              | 代价                                                            |
| ------------------------------------------- | ------------------- | --------------------------------------------------------------- |
| 改 `task-pages.ts` 一处 vs 改 `nav-config.ts` 加菜单条目 | **只改前者**        | 无。菜单条目由 domain 派生，自动生成                             |
| 顺手把其余 26 条占位也翻正                   | **不翻**            | 占位保持「未实现」状态直到各自模块交付；这是有意的，不是遗漏     |
| 改测试让它闭嘴 vs 让实现与登记对齐          | **对齐实现**        | 无                                                                |
| 台账由本车道改 vs 交调度者                   | **交调度者**        | 台账与实现不在同一 commit，调度者并入时需自行处理台账那行       |

## 验证

- **L0 编译闸**：`pnpm typecheck` ⇒ `Tasks: 3 successful, 3 total`。
- **L1 模块闸（关键验收）**：`pnpm vitest run packages/ui/src/lib/nav-routes.test.ts`
  - 改前：`Test Files 1 failed (1)` / `Tests 2 failed | 8 passed (10)`，
    两条失败分别是 `"/finance/payables 标记=false 实际=true"` 与 `'/finance/payables'`。
  - 改后：`Test Files 1 passed (1)` / `Tests 10 passed (10)` ⇒ **2 failed 变全绿**。
- **L1 全包回归**：`pnpm vitest run packages/ui` ⇒ `Test Files 6 passed (6)` / `Tests 45 passed (45)`。
- `pnpm lint` ⇒ `Tasks: 3 successful, 3 total`（仅既有文件 warning，与本改动无关）。
- 未跑 `mvnw verify`（纯前端改动，后端零改动）；未跑 `pnpm --filter @starcon/web build` 与
  全量 `pnpm test`（按批次约定由调度者在批次末尾统一跑）。

## 后续待办

- **其余 26 条 `implemented: false` 占位**：各自模块页面落地时，由该模块的车道负责翻正。
  建议沿用本车道做法 —— **只翻自己那一条**，避免同批多车道并发改 `task-pages.ts` 造成 git 冲突
  （该文件不在 merge-union 驱动器覆盖范围内，走普通 git merge）。
  **根治建议（超出本车道范围）**：把 `task-pages.ts` 的 `implemented` 标记改成**由文件系统推导**
  或在 `.gitattributes` 里给它配 merge-union 驱动，从根上消掉「占位忘了翻正」这一类洞。
- **台账**：`docs/product/capability-blueprint/module-progress.md` 的 12.1.2.1 行由调度者统一处理，本车道未动。

## 关联

- [上一车道：12.1.2.1 应付单管理](./2026-10-23-payable-management.md) —— 本车道修的就是它留下的登记洞
- [台账：12.1.2.1 应付单管理](../../product/capability-blueprint/module-progress.md)
- `packages/ui/src/lib/nav-routes.test.ts` —— 守恒不变式的来源（未改动）
