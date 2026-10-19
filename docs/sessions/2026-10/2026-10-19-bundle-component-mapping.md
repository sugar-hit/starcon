# 4.1.2.3 套装组件映射 —— 车道交付记录

- 模块：**4.1.2.3 套装组件映射**（L3「4.1.2 变体映射」，D5）
- 分支：`feat/4-1-2-3`（worktree `.worktrees/4.1.2.3`）
- Flyway：**V186**（预分配）

## 决策摘要

### 1. 组件表归本模块，但**不复制** catalog 的构成真值

V176 第六节已把线划出来：「组件 SKU 逐条明细不建表 —— 那是 4.1.2.3 的职责」。落地时分界线取的是
**两个问题，不是两个真相**：

| 谁 | 回答什么 | 性质 |
| --- | --- | --- |
| catalog `bundle_component`（3.5.1） | 这个**内部套装**由哪些 SKU 组成 | 产品定义，跟着内部商品走 |
| 本模块 `listing_bundle_component` | 这个**平台变体**按哪一版、每卖 1 件扣几件 | 刊登侧绑定 |

两者**允许不同**（同一套装 A 店 1:1、B 店 2:1）—— 这正是本表存在的理由。所以本表**引用**
`bundle_version_id` 而不复制构成快照：构成快照是 09 订单 `order_line_component` 的冻结职责，
复制就会有两个真相。写入时经 catalog 授权 API 核对「这个 SKU 确实属于这一版」。

### 2. 「换版」用生效区间，不用 status 字段

与 4.1.2.2 的 `listing_mapping_version` 同一套纪律：`effective_from` / `effective_to` 半开区间，
`NULL` = 当前生效。选它而不是 `status='ACTIVE'` 是因为「这一版从什么时候用到现在」本身就是
运营要回答的问题。`UNIQUE(tenant_id, mapping_id) WHERE effective_to IS NULL` 部分唯一索引挡并发，
触发器给可读原因。

### 3. 两条蓝图加粗句的落点不同（最容易做丢的一处）

- **「组件数量必须大于 0」** → **能**落成 DB 约束：`CHECK(quantity>0)` + 触发器给可读原因。
- **「缺任一组件时整个套装不可售」** → **只能落一半**。「可售」的真值在 warehouse 库里
  （在手−预占−安全缓冲），AGENTS.md 禁止跨服务 SQL，所以**表达不成 CHECK**。硬写一个无跨库视图的
  假约束只会造成「库里说可售、实际不可售」的假保证。落在服务层的 `sellable-check` 端点，
  `bundleSellable` 是**整体合取**。

### 4. 「查不到」与「确实不可售」必须可区分

上游 warehouse 不可达一律 **503**，绝不返回「全部可售」。返回「可售」会让运营把「我们查不到」
读成「能卖」，而那正是会真的超卖的路径。反过来，上游**正常返回但该 SKU 无行**是可售量按 0 计 ——
那是**结论**不是故障。测试两条都钉住了。

## 关键 trade-off

| 选择 | 代价 | 为什么值得 |
| --- | --- | --- |
| 组件表引用 catalog 而非复制 | 每次写入要多一次跨服务 HTTP | 复制就会有**两个真相**，且订单侧冻结要的那一份仍然得从 catalog 取 |
| 目标仓库作为**必填**查询参数 | 运营不能「不填就查全部」 | 可售量按仓库计算，不指定仓库就没有唯一答案；给个全量会被读成「这个 SKU 在任何仓库都没货」 |
| 不做构成快照 | 「当时这一版由哪些 SKU 组成」要现查 catalog | 快照是订单侧的职责；本模块做一份就等于抢写入所有权 |
| DTO 加 `ListingBundle*` 前缀 | 比 `BundleComponent*` 长 | catalog 已有同名 DTO，codegen 会**静默覆盖字段**（gate 会拦，但撞了就改不动别人的） |

## 文件清单

**后端（channel）**

- `src/main/resources/db/migration/V186__listing_bundle_component.sql` —— 两张表 + 7 索引 +
  5 个触发器 + RLS + GRANT
- `ListingBundleComponentModels.java` —— 传输模型
- `ListingBundleComponentService.java` —— jOOQ 显式 SQL（写 + 读 + 可售性校验）
- `ListingBundleComponentController.java` —— 9 个端点
- `BundleVersionQueryPort.java` —— catalog 3.5.1 构成归属校验
- `SellableQuantityQueryPort.java` —— warehouse 07 可售量（只读）
- `src/test/java/.../ListingBundleComponentIntegrationTest.java` —— **15 例**

**前端**

- `apps/web/src/app/listings/bundle-components/page.tsx`
- `apps/web/src/components/bundle-component/bundle-component-workspace.tsx`
- `apps/web/src/components/bundle-component/bundle-component-model.ts`
- `apps/web/src/components/bundle-component/bundle-component-model.test.ts` —— **19 例**
- `apps/web/src/app/api/channel/bundle-component-allowlist.test.ts` —— **6 例**
- `apps/web/src/lib/api-proxy.ts` —— **只新增一条** `bundle-components/` 元素

**契约（codegen 产物，只读）**：`packages/api-contracts/src/contracts/channel.ts`（+11 DTO）、
`packages/api-contracts/src/channel.openapi.json`（+7 path）

## 踩到的三个坑（都值得记下来）

1. **JDK 25 的 javac 拒绝参数列表里的尾随逗号**（`f(\n  a,\n)`）。仓库既有 Java 代码从不这么写，
   所以是纯新增代码才踩到。报错误导性很强：`非法的表达式开始` 指向的是一个完全合法的 `)`。
2. **plpgsql 的 `RAISE EXCEPTION '... % ...'` 里 `%` 是占位符**。不传参数就是
   `too few parameters specified for RAISE`，且**建表阶段就失败**，表现为「整个服务起不来」。
3. **`SELECT ... INTO` 查不到行时目标变量是 NULL**，与「查到的值就是 NULL」不可区分。判「绑定
   存不存在」必须用 `FOUND` —— 用 `IS NULL` 会把每一个**当前生效**的绑定（`effective_to` 本来
   就是 NULL）误判成「不存在」。

## 验证

- L0：`./mvnw -pl services/channel -am compile` 绿；`pnpm typecheck` 三包绿
- L1：`ListingBundleComponentIntegrationTest` **15 例绿**（含租户隔离三断言、timestamptz 往返）
  + `OpenApiContractTest` 1 例绿
- 前端：`bundle-component-model.test.ts` 19 例 + `bundle-component-allowlist.test.ts` 6 例绿；
  **allowlist 全量回归** `apps/web/src/app/api/trade` 218 例 + `apps/web/src/app/api/channel` 91 例绿

## 后续待办

1. **07 库存「按组件预占」的写路径**未交付。本模块只读可售量做校验、不发起预占 ——
   capabilities 里 `inventory_reservation` 显式 `available=false`。落地后 warehouse 侧按本模块的
   组件口径执行即可，本模块的查询面不需要改。
2. **09 订单的构成冻结快照**（`order_line_component`）未交付。本模块**刻意不存**构成快照。
3. 前端页面未接入导航菜单（菜单由调度器在批次末统一处理），路由已可用：`/listings/bundle-components`。
