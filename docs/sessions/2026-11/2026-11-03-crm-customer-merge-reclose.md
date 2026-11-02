# 2026-11-03: 15.3.1.3 跨店合并 —— 收口（阶段 D7，L3 锚点 15.3.1）

> 续 `2026-11-02-crm-customer-merge.md`。上一任 worker 已把功能写完并提交（5 个 commit），
> 但**没有跑 `land`** ⇒ 成果至今没被并入集成头。本轮只做**收口**：核对完整性 + 跑闸 + 补漏。

## 摘要

七件产物（迁移 / 服务 / 端点 / 页面 / 测试 / 台账行 / 会话日志）**全部齐全，无半成品**：
没有 mock、没有占位页、没有 `TODO` + `UnsupportedOperationException`；分页是真 LIMIT/OFFSET + 独立统计，
搜索是真 SQL 筛选（不是前端过滤）。

蓝图点名的三条语义逐条核对通过：

| 蓝图加粗句 | 落点 | 验证方式 |
|---|---|---|
| **只给建议不自动执行** | `scan` 只写 `crm_customer_merge_candidate`；逻辑客户与映射表**一行都不写** | DB 级两道钉子：① 触发器拒绝 INSERT 一条 `status='CONFIRMED'` 的候选；② 触发器拒绝直接 INSERT 映射（映射必须挂在一个**已人工确认**的候选上）⇒ 绕过服务层直接写 SQL 同样被挡 |
| **给出置信度** | 四级信号（PLATFORM_BUYER_ID 0.95 / EMAIL 0.85 / PHONE 0.75 / NAME_ADDRESS 0.60）加权 = 最强信号 + 0.05×旁证数，上限 0.99、下限 0.60；**numeric(5,4) + BigDecimal**（权重也是 BigDecimal 不是 double） | 每条候选都带**逐信号证据明细**（命中了哪个信号、强度多少、比对值是什么 —— 只存脱敏形态） |
| **保留合并前映射可拆分** | `split` 把映射 `ACTIVE → SPLIT` 并留痕（`split_at` + `split_reason`），**映射行不删**；**来源行 `crm_customer_profile` 从头到尾一个字都没改过** | DB 级三道钉子：触发器拒绝 `SPLIT → ACTIVE`（重新合并要新写一行）、拒绝 DELETE 映射行、拆分必须留痕；操作流水只追加 |

## 决策 / 结论

### 1. 🔴 白名单多放行了一个**后端不存在**的裸路径

`api-proxy.ts` 本车道那条 elements[] 的正则外层子路径组**带了 `?`** ⇒ 裸路径 `crm/customer-merge` 被放行。
但后端 `@RequestMapping("/crm/customer-merge")` 下面第一个子路径就是 `capabilities` ——
**没有裸路径端点**（逻辑客户列表的真身是 `customer-merge/logical`）。

这与该文件自己写下的原则矛盾：「放行后端不存在的映射比 404 更糟 —— 界面会以为功能已交付」。
**收紧**：外层组末尾去掉 `?`（`candidates` / `logical` 两个子组**各自**的 `?` 保留 —— 那两个本身是真端点），
并把裸路径写进「刻意不放行」清单 + 加进守卫的负向断言。

**trade-off**：改的是共享文件（多车道累积型）。判断为**安全**：首 token `customer-merge` 全仓唯一
（与 `customer-tags` / `customer-contacts` / `customers` 第 9 个字符互斥），
全仓只有本车道自己的守卫引用过这个前缀 ⇒ 收紧不会误伤任何别的车道。
改完必须重跑**全量 allowlist 回归**（已跑，见下）。

### 2. 台账行有两处**与事实不符 / 缺落点**，本轮改正

- 「11 个方法映射 / **10 条路径**」→ 路径数错了。实测后端 controller **11 个方法映射 / 11 条路径**
  （OpenAPI spec 也正好 11 条 path）。
- 证据列的**页面**只写了路由 `/crm/customer-merge`，没写文件落点；**前端测试**一个名字都没有
  ⇒ 补上 `apps/web/src/app/crm/customer-merge/page.tsx` + `components/crm/customer-merge-{model.ts,customer-merge-workspace.tsx}`
  与 `customer-merge-model.test.ts` 22 例 + `customer-merge-allowlist.test.ts` 5 例。

**没有重排整表、没有动别人的行** —— 只改 15.3.1.3 那一行的证据格。

### 3. 契约：确认一致，本轮**不重跑 codegen**

`backend/target/openapi/crm.openapi.json`（活 spec）与 `packages/api-contracts/src/crm.openapi.json` 逐项比对：
**11 条 path 完全一致**（零增零删）、**20 个 `CrmCustomerMerge*` schema 逐字节一致**。
唯一差异是 `operationId` 的**命名风格**（活 spec `capabilities_14` vs codegen 产物 `get_crm_customer_merge_capabilities`）
—— 那是 codegen 自己的改写规则，不是漂移。

本轮**没有改后端 DTO**，所以按协议**不重跑 codegen**（避免产生无关 diff）。

## 文件清单（本轮改动）

- `apps/web/src/lib/api-proxy.ts`（**改动**：本车道自己那条 elements[] 元素的正则 + 同块注释）
- `apps/web/src/app/api/crm/customer-merge-allowlist.test.ts`（**改动**：负向断言加一条裸路径）
- `docs/product/capability-blueprint/module-progress.md`（**改动**：只改 15.3.1.3 那一行的证据格）
- `docs/sessions/2026-11/2026-11-03-crm-customer-merge-reclose.md`（本文件，新增）

**未改动**：迁移、4 个后端 java、2 个后端测试、前端页面 / model / workspace、契约生成物 —— 复核后确认无半成品，无需补齐。

## 验证

| 闸 | 命令 | 结果 |
|---|---|---|
| L0 编译 | `cd backend && ./mvnw -q compile` | **exit 0** |
| L1 模块闸 | `./mvnw -pl services/crm -am test -Dtest='CustomerMerge*' -Dsurefire.failIfNoSpecifiedTests=false -DfailIfNoSpecifiedTests=false` | **Tests run: 22, Failures: 0, Errors: 0, Skipped: 0** · BUILD SUCCESS |
| 前端本模块 | `pnpm vitest run apps/web/src/app/api/crm/customer-merge-allowlist.test.ts apps/web/src/components/crm/customer-merge-model.test.ts` | **2 文件 / 27 例全绿** |
| 类型闸 | `pnpm typecheck` | **3 successful, 3 total**（exit 0） |
| Lint | `pnpm lint` | **3 successful, 3 total**（exit 0；只剩 warehouse 两个**存量** warning，与本车道无关） |
| **全量 allowlist 回归** | `pnpm vitest run apps/web/src/app/api` | **296 文件 / 1663 例：1660 通过 / 3 红** |
| 契约一致性 | 活 spec ↔ 契约产物逐项比对（python3） | 11 path 一致 + 20 schema 逐字节一致 |

🔴 **全量回归里的 3 个红，与本车道无关**（与上一轮完全同一组）：
`catalog/price-list-allowlist` / `catalog/price-list-item-allowlist` / `trade/price-list-priority-allowlist`
—— 断言「`catalog/price-lists/lists/{uuid}/items` 应 404」而该路由已由 **5.1.2.1 车道**交付。
本车道**不改那三个文件**。**判据：除这 3 条以外没有新红 ✓**

## 后续待办

1. **5.1.2.1 车道**处理基线的 3 个 price-list allowlist 红灯（不在本车道范围）。
2. **15.3.1.2 客户详情**：可读本模块的 **ACTIVE 映射**与逻辑客户表（跨模块只读，纪律不变）。
3. **15.3.1.1「按逻辑客户展示」**：是它的**下游增强**（读本模块映射），本模块**不写**它的表。
4. **13 分析（客户价值跨店汇总）**：可读本模块的逻辑客户表按**币种分行**聚合（跨币种仍不相加）。
5. **调度者**：把本车道并入集成头（`land` 已跑；并入由调度者做）。
