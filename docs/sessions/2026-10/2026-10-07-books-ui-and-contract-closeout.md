# 2026-10-07 17.6.1.1 账簿与本位币 —— 前端页面 / 白名单 / 契约收尾

车道 `feat/17-6-1-1`（worktree `.worktrees/17.6.1.1`），阶段 D6，L3 锚点 17.6.1。
本轮是**续做**：后端（V81 迁移 + `LedgerBookController` 6 个端点 + `LedgerBookIntegrationTest` 8 例）
已在集成头交付，本车道**没有新增任何迁移**，只补台账点名的三个缺口。

## 决策摘要

| 缺口 | 结论 |
| --- | --- |
| 蓝图路由 `/settings/books` 不存在 | 新建页面，真实调用 6 个新端点 |
| api-proxy 白名单没有三段新路径 | 补 inner alternation，反向抽取断言 |
| OpenAPI 契约未重生成 | `--spec-dir` 重跑 codegen |

### ① 页面：照 17.1.2.2 的写法新建，不另起风格

- `apps/web/src/app/settings/books/page.tsx` —— 与 `app/org/accounting-books/page.tsx` 同构（一行导入 + 渲染工作台）。
- `apps/web/src/components/iam/ledger-book-workspace.tsx` —— 工作台本体。
- `apps/web/src/components/iam/ledger-book-model.ts` —— 展示与入参口径（对应 17.1.2.2 的 `accounting-book-model.ts`）。

关键取舍：

- **6 个端点全部真实调用**，一个都不是重复老页面的 `identity/accounting-books`：
  分组读、默认账簿读写、归属登记读/写/解除。
- **默认账簿 409 不吞成空态**。分组与默认账簿用 `Promise.allSettled` **分别结算** ——
  `Promise.all` 会让那一个 409 把已经成功的分组结果一起吞掉，页面就变成
  「这个法人一本账都没有」，那正是"把 409 吞成空态"。409 单独成为
  `defaultMissing` 状态，渲染成「去设默认」的可操作引导并带上后端原话。
- **三态齐全**：加载态（`!ready`）、错误态（`error` / `groupsError` / `assignmentsError`
  各自带重试）、空态（无法人 / 无账簿 / 某类账为空 / 无归属）。**零 mock**。
- **导航入口**：`identity-workspace.tsx` 补一张 `/settings/books` 卡片。
  17.1.2.2 的账簿页此前零入口（`/org` 导航与 `AdministrationKind` 都没有它），
  本模块若只建页面不挂入口就是同一个毛病重演。它**不进 `destinations`**——
  那张表的 href 是写死的 `` `/org/${key}` `` 模板，装不下 `/settings/books`，
  所以单开一张卡挂同一个 `visible()`，并补 `identity.accounting_book.read` 权限码分支
  （`AdministrationKind` 里没有 `'books'`，少这条会落到 `canManage` 兜底恒 false）。

### ② api-proxy 白名单：改的是**内层** alternation

`apps/web/src/lib/api-proxy.ts` 的 `identity/` 正则有两层：外层是 17.x 各分支，
内层是某个子模块自己的 `(?:…)`。三段新路径都属于**已有分支的子路径**，
所以改内层，不是新增外层分支：

```
legal-entities\/(?:[0-9a-f-]+\/(?:accounting-books|ledger-books|default-book)|references)
accounting-books(?:\/[0-9a-f-]+(?:\/fact-assignments(?:\/[0-9a-f-]+)?)?)?
```

为什么不新增外层分支：`ledger-books` 的真实路径是 `legal-entities/{uuid}/ledger-books`，
放到外层会变成顶层 `ledger-books`，与 `accounting-books/{uuid}/fact-assignments` 同样错位。

**为什么必须改**：白名单只在浏览器端生效，`pnpm test` 直连服务层永远测不出代理 404 ——
不改的话页面在浏览器里 404，而 typecheck / lint / vitest / Playwright 全绿。

验证不靠眼看：`apps/web/src/lib/ledger-book-api-proxy.test.ts` 从前端源码
**反向抽取** `identity/...` 字面量，把 `${...}` 换成真实 UUID 再与正则对撞。
反向抽取命中 **7 条**本模块调用点，**逐条断言放行**；并**额外断言抽取条数 > 0**
（抽取器坏掉返回 0 条时，「全部通过」会变成假绿）。同时钉住反向：
`/extra`、`default-books`、`/transfer` 等**尚未实现**的子路径必须仍 404，
且 17.1.2.2 与其它车道已登记的分支一条都不被误伤。

`apps/web/src/app/api/iam/[...path]/route.test.ts` 同步加了放行 + 反向拒绝断言
（含 DELETE 解除归属）。原有反向拒绝清单**一条都没删**，全部仍绿。

### ③ 契约：codegen 产物不手改

```bash
cd backend && ./mvnw verify                                     # 先产 spec
python3 tools/codegen/catalog-contracts.py --spec-dir backend/target/openapi
```

`--spec-dir` 必须带，否则脚本去启动 Spring Boot 抓 `/v3/api-docs`（本地没起服务会 Connection refused）。
生成 1047 DTO，新增 `LedgerBookGroups` / `LedgerBookItem` / `DefaultBookView` /
`DefaultBookInput` / `FactAssignmentView` / `FactAssignmentInput`。

**codegen 的坑（已按要求处理）**：Java record 的枚举字段在 TS 侧被**内联成字面量联合，
不生成具名 type alias**。所以前端枚举类型一律**从契约行类型派生**：

```ts
export type BookCategory = NonNullable<LedgerBookItem['bookCategory']>;
```

`packages/api-contracts` 与前端本地都**没有**手抄字符串联合——那是漂移源：
后端加值时本地类型不变、编译照过、运行时才炸。

## 关键 trade-off

- **`allSettled` vs `all`**：见上。代价是两条错误要分别归位（多一个 state），
  换来的是"一个 409 不会伪装成空数据"。
- **金额按字符串递给后端**：后端是 `BigDecimal` + `NUMERIC(19,6)`，前端**不做任何浮点运算**；
  提交时 `amount` 以字符串原样送出，由 Jackson 精确解析。展示侧只格式化、坏值退化成占位符。
  契约侧 `FactAssignmentView.amount` 被 codegen 生成为 `number`（Jackson BigDecimal → `type: number`），
  这是 JSON 传输的固有形态，不改变"库内禁浮点"这条纪律。
- **本车道不碰别的模块**：全量扫描 `apps/web/src` 时发现**其它车道**已有的白名单缺口
  （17.5.2.1 的 `identity/dict-*`、17.3.2.1 的 `identity/role-assignment-scopes`、
  `members/{id}/effective-permissions`、`members/{id}/role-assignments`、
  `departments/{id}/parent` 等）。**本车道不代改**，留给对应车道收口；
  因此回归测试刻意**只扫本模块两个文件**并写明理由，而不是全量扫然后让别人的缺口红在我这里。

## 文件清单

新增：
- `apps/web/src/app/settings/books/page.tsx`
- `apps/web/src/components/iam/ledger-book-workspace.tsx`
- `apps/web/src/components/iam/ledger-book-model.ts`
- `apps/web/src/components/iam/ledger-book-model.test.ts`
- `apps/web/src/lib/ledger-book-api-proxy.test.ts`

修改：
- `apps/web/src/lib/api-proxy.ts`（identity 白名单两处内层 alternation）
- `apps/web/src/app/api/iam/[...path]/route.test.ts`（新增放行 + 反向拒绝用例）
- `apps/web/src/components/iam/identity-workspace.tsx`（导航入口 + `visible()` 权限码）
- `packages/api-contracts/src/*`（codegen 重生成）
- `docs/product/capability-blueprint/module-progress.md`（17.6.1.1 状态 + 证据）

## 验证（真实结果）

| 命令 | 结果 |
| --- | --- |
| `backend && ./mvnw verify` | BUILD SUCCESS，700 例 0 失败；identity 191 例含 `LedgerBookIntegrationTest` 8 例 |
| `pnpm typecheck` | 3/3 通过 |
| `pnpm lint` | 通过（仅 catalog/warehouse 既有 warning，非本车道文件） |
| `pnpm test` | 83 文件 **1475 例全绿**（新增 `ledger-book-api-proxy` 12 例 + `ledger-book-model` 7 例） |
| `NEXT_TELEMETRY_DISABLED=1 pnpm --filter @starcon/web build` | 通过，路由表出现 `/settings/books`（6.58 kB） |
| `node tools/module-progress/sync.mjs --check` | ✓ 台账与蓝图一致 |
| 台账完整性 | 465 行、无重复 ID |

**本车道跑 verify 期间同时存活的 PG 容器数：1**（`starcon-mig-v2`，是常驻的开发库，
不是 Testcontainers 起的）。verify 结束后仍为 1 —— Testcontainers 的容器已被 Ryuk 回收，
没有残留。

## 后续待办

- 并入集成头由调度器执行（`lane.mjs land`），本车道未自己 merge、未 checkout 集成分支。
- 上条提到的**其它车道**白名单缺口（`identity/dict-*` / `identity/role-assignment-scopes` /
  `members/{id}/…` / `departments/{id}/parent`）需由各自车道补，本车道未越界。
