# 7.8.1.2 优先级与保底

**日期**：2026-10-07 · **车道**：`feat/7-8-1-2`（worktree `.worktrees/7.8.1.2`）· **迁移**：V76（预分配）

## 决策摘要

### 保底不是 7.8.1.1 的 member 加一列

`allocation_policy_member.priority` 回答的是「余量按什么次序分」；本模块回答的是**另一个问题**：「货不够分的时候先保住谁」。

这两个次序会打架：一家销量高但优先级低的店，在余量分摊里排最后，而它的保底又必须被满足。把保底做成 member 上的一列，两个次序只能共用一个数字，于是「既排在余量最后、又必须最先保底」这家店**无法表达**。所以保底是独立的一档（`allocation_floor`），仲裁次序复用策略解析出的次序。

### 两遍分配，而不是「先分余量再补差」

```
① 可用量 available = 缓冲 buffer + 可分配量 allocatable    ← 缓冲口径是 7.8.1.1 的，本模块只算一次
② 保底池 floorPool = min(allocatable, Σ(保底_i + 安全垫_i))
③ 余量池 residual   = allocatable − 保底池
④ 保底裁决：按仲裁次序逐个从保底池拿，差额显式记 shortfall
⑤ 余量分配：只让「未被保底足额满足」的店铺参与，份额在参与者之间重新归一化
⑥ 最终分摊_i = 保底_i + 余量_i
```

先划池子让每一步的上界都是静态的，「谁先拿到」只取决于次序，而次序是**配置**出来的、可复现的。反过来「先分余量再补差」要判断「补到多少算够」，那个判断依赖已经分出去的结果；补完可能超过可分配量，超出的部分又要从别人身上拿回来，拿回来又会让别人不够格。

### 复用 7.8.1.1 的分档解析，但不碰它的文件

`policies.effective()` 是 public 的，直接注入调用：SKU > SHOP > DEFAULT、左闭右开、区间不重叠那套约定已经被 V68 的触发器钉死了，再写一份解析器迟早在某次换档时给出不同答案。成员与销量自己读（读不构成重复实现，写才是）。

**代价（如实记录）**：余量分配仍是本模块自己算一遍——它跑在 `residual` 上、且只在参与者之间归一化，`allocate()` 无参可表达。两处算式形状相同、必须同步演进；本轮按「只碰自己新增的文件」纪律没有改 `AllocationPolicyService`，**统一成一个共享计算器是后续该做的事**。

## 关键 trade-off

| 决策 | 取舍 |
|---|---|
| `floor_order` 每个成员都有（含没配保底的） | 留 0 会让「排在最后」与「压根不在这次裁决里」长得一样。它回答的是「它本该排第几却没货保」 |
| 请求量**不**夹到可分配量 | 一夹，「要 6 件、货只有 0 件」就变成「要 0 件、给了 0 件、欠账 0」，承诺明明存在过却看不出没兑现。超发由保底池上界保证 |
| `floorSafety` 只支持件数，不支持比例 | 7.2.2.1 的 `buffer_ratio` 已经是比例口径，再造一个比例口径的安全垫，界面上就有两个都叫「比例」的百分比 |
| 落库写 7.8.1.1 的 `allocation_run` / `allocation_run_line` | 不发明第二种「分摊」概念——两个 run id 拼起来的记录迟早对不上，而 7.8.2.1 要读的正是那一张 |
| `availableQuantity` 必填 | 7.2.2.1 的池容量口径是 7.8.1.1 服务里的私有实现，复制一份只会得到一个迟早漂移的副本 |
| 不新增权限码 | 保底是分摊算法的一部分，看保底与看分摊是同一份配置的事实。拆成两个权限码只会让人以为存在「能改分摊但改不了保底」这种中间状态 |

## 过程中查出的三个真 bug（都是自己写的）

1. **`short` 是 Java 关键字** —— `BigDecimal short = req.subtract(got)` 编译不过。改名 `gap`。
2. **请求量被夹到可分配量会吞掉欠账** —— 见上表。`requestFor` 去掉夹取，改由保底池上界保证不超发。这一条同时修好了三个失败的用例（部分兑现 / 未兑现 / 零可用量）。
3. **作废后 1 秒内仍然生效** —— `retire(id, null)` 原本收口到 `effective_from + 1s`，于是「刚建好就作废」的保底在往后一秒里仍然裁决发货，而界面上显示「已作废」。改成不传时刻时用**当下**收口，`+1s` 只在「传入时刻早于生效起点」时用。

还有一个是**认知错误**，已按事实改正：见下节。

## 白名单是按路径登记的，不区分方法

原以为「白名单挡 DELETE」，实测发现对已登记路径发 DELETE 会被**照样转发**给 warehouse 服务。真正挡住删除的是：后端没有 DELETE 端点（405）+ V76 的 `REVOKE DELETE` + append-only 触发器。

已把 `api-proxy.ts` 的注释与用例都改成事实描述。**这条值得记下来**：写注释时最容易顺手写一句「这里挡住了 X」而没验证过，白名单类注释尤其危险。

## 未交付的上游（如实标注，未臆造）

| 上游 | 状态 | 处理 |
|---|---|---|
| 店铺绩效（17 绩效域） | 未交付，仓库服务内既无表也无授权 API | `performanceBasisAvailable=false`，页面上常驻提示「保底是人为承诺的底线，不是系统按绩效算出来的下限」。不接绩效、不算绩效分 |
| 7.2.2.1 池容量口径 | 是 7.8.1.1 服务内的私有实现 | `availableQuantity` 必填，capabilities 说明原因 |
| 「被保底挤下去」的 outcome 枚举 | 7.8.1.1 的表与 CHECK 不能改 | 复用 `OK` / `DEMAND_EXHAUSTED`，精细原因在 resolution 表逐店留痕 |
| 7.8.2.1 / 7.8.2.2 | 未交付 | 本模块已建好索引（`WHERE suppressed` / `WHERE shortfall_qty > 0`），它们可直接聚合，不需要重算分摊 |

## 文件清单

**后端**
- `backend/services/warehouse/src/main/resources/db/migration/V76__warehouse_allocation_priority_floor.sql`（新建，两张表 + 6 道 DB 约束 + RLS/授权）
- `backend/services/warehouse/src/main/java/com/starcon/warehouse/AllocationPriorityModels.java`
- `backend/services/warehouse/src/main/java/com/starcon/warehouse/AllocationPriorityService.java`（`compute` 是纯函数）
- `backend/services/warehouse/src/main/java/com/starcon/warehouse/AllocationPriorityController.java`

**测试**
- `backend/services/warehouse/src/test/java/com/starcon/warehouse/AllocationPriorityModelTest.java`（14 例纯函数）
- `backend/services/warehouse/src/test/java/com/starcon/warehouse/AllocationPriorityIntegrationTest.java`（22 例 Testcontainers，含多租户三断言）

**前端**
- `apps/web/src/components/inventory/allocation-priority-model.ts`
- `apps/web/src/components/inventory/allocation-priority-model.test.ts`（27 例）
- `apps/web/src/components/inventory/allocation-priority-workspace.tsx`
- `apps/web/src/app/inventory/allocation-priority/page.tsx`
- `apps/web/src/app/api/warehouse/allocation-priority-allowlist.test.ts`（4 例）

**生成物**
- `packages/api-contracts/src/catalog.ts` + `*.openapi.json`（新增 11 个 DTO，消失 0）

## 验证

- `./mvnw -pl services/warehouse -am verify` → BUILD SUCCESS，669 例全绿
- `pnpm typecheck` → 3/3
- `pnpm lint` → 3/3（4 条 warning 属别的文件的既有 `exhaustive-deps`）
- `npx vitest run` → 79 文件 **1385** 例全绿（基线 77 文件 **1353**，+32 无回归）

## 后续待办

1. **抽共享计算器**：把 7.8.1.1 的余量算式与本模块的余量算式合并成一个。两处形状相同、各自演进迟早算错。**需要改 `AllocationPolicyService`（7.8.1.1 的文件），因此没有在本轮做。**
2. 导航登记：`/inventory/allocation-priority` 未进导航——与 7.8.1.1 一致（两者都不在 nav 里），推测属另一条车道的工作。
3. 换版交互：目前按行挂在列表上、共用编辑器草稿；可以做成「点换版时按行预填原值」。
4. 7.8.2.2 饥饿预警可以直接用 `allocation_priority_resolution_shortfall` 与 `..._suppressed` 两条部分索引聚合，不用重算分摊。