# 2026-11-02: 15.3.1.3 跨店合并（阶段 D7，L3 锚点 15.3.1）

## 摘要

蓝图 15「客户与客服」→ 15.3「客户主档」→ 15.3.1.3「跨店合并」落地。

车道分支 `feat/15-3-1-3`，迁移版本号由调度者预分配为 **V437**
（`backend/services/crm/.../V437__crm_customer_merge.sql`）。

蓝图原文：「**职责**：把同一自然人在不同店铺的身份合并到一个逻辑客户，支持按邮箱/电话/姓名+地址/平台买家标识匹配，给出置信度并需人工确认，保留合并前映射可拆分。
**输入**：候选匹配（自动建议）；人工确认；拆分请求。
**输出**：合并后的逻辑客户 + 映射关系 + 操作记录。
**关系**：上游 = 15.3.1.1；下游 = 15.3.1.2、13 分析（客户价值跨店汇总）。**自动合并风险高**（同名不同人、同人不同邮箱），因此只给建议不自动执行，且必须可拆分。」

交付物：五张新表、11 个端点（`/crm/customer-merge`）、一个真实工作区页面（`/crm/customer-merge`）、
22 个后端集成测试（含租户隔离三断言）+ 27 个前端测试。

## 🔴 这是一条**续做**车道：上一任 worker 留了两份未提交文件

上一任 worker 的心跳停在「开始写 V437 迁移」，工作区里留下 2 个**未提交**的文件：

- `V437__crm_customer_merge.sql`
- `CustomerMergeModels.java`

**没有推倒重来** —— 读完、接着写。迁移骨架在开工 20 分钟内单独提交（`4a46cd29`），
之后只补了下面记的三处**真实缺陷**。

## 决策 / 结论

### 1. 🔴 蓝图加粗的两句要钉在 **DB 层**，不能只靠服务层自觉

「只给建议不自动执行」与「必须可拆分」是蓝图里**加粗**的两句。绕过服务层直接写 SQL
同样不能破坏它们 —— 于是 V437 里有 **9 个触发器**，其中三条是这两句话的落点：

| 加粗句 | DB 级钉子 |
|---|---|
| 只给建议不自动执行 | 候选**只能以 SUGGESTED 诞生**（拒绝 INSERT 一条 CONFIRMED）；**映射行必须挂在一个已人工确认的候选上**（拒绝直接 INSERT 映射）；复核结论是终态 |
| 必须可拆分 | 映射行**不删**（拒绝 DELETE）、**只能 ACTIVE → SPLIT**（拒绝 SPLIT → ACTIVE）、拆分必须带 `split_at` + `split_reason` |

这三条组合起来的效果：**一条 ACTIVE 映射**只可能由「人工确认一个候选」这一条路径产生。
集成测试逐条复验（绕过服务层直接写 SQL ⇒ 报错）。

### 2. 拆分为什么能「还原回原来的来源行」

因为**合并不修改来源行**。本模块一个字都不写 15.3.1.1 的 `crm_customer_profile`
（每张事实表只有一个写入所有者）—— 合并只在自己的映射表里把两条来源行挂到同一个逻辑客户下。
所以拆开时只需要把映射从 ACTIVE 改成 SPLIT，来源行**原样**还在。

这条边界也让「重新合并要新写一行映射，历史那行原样留着」成为可能：
`SPLIT → ACTIVE` 被 DB 拒绝，于是「合并过 → 拆开 → 又合并」在审计流水里查得到。

### 3. 四级信号分级 + 置信度可解释

蓝图点名四种依据，它们的**证据强度不同**，不能揉成一个神秘分数：

| 信号 | 权重 | 说明 |
|---|---|---|
| PLATFORM_BUYER_ID | 0.95 | 同一个 catalog 客户档案 |
| EMAIL | 0.85 | |
| PHONE | 0.75 | 家庭号 / 公司号会被多人共用 |
| NAME_ADDRESS | 0.60 | 姓名相同**且**地址相同 |

置信度 = 最强命中信号 + 0.05 × 旁证个数，上限 0.99，下限 0.60。

**只有姓名相同、地址对不上（或地址取不到）⇒ 不产出候选**，只进运行台账的
`below_threshold_count`（「同名不同人」那一档）。这是蓝图那句「同名不同人」的落点。

权重用 **BigDecimal 而不是 double**：double 会算出 `0.85 + 0.05×2 == 0.9500000000000001`，
落进 `numeric(5,4)` 后舍入点上会变成「这一对到底是 0.95 还是 0.9499」的争议。

### 4. 地址从哪来：`crm_customer_profile` 没有地址列

「姓名+地址」这一档需要地址，而 15.3.1.1 的客户主档只存了标识、店铺、买家名、联系方式
—— **没有地址列**（地址的归属是 catalog 的往来单位档案）。于是新增
`CustomerMergePartyPort`，经 catalog 的**授权 HTTP API 只读**
`GET /catalog/parties/{id}/addresses` 取（只取 `oneLine` 这一种可比较形态）。

取不到时该档**不产出候选**，运行台账如实报出三个计数
（`address_resolved` / `address_unavailable` / `party_unavailable`）——
绝不把「没取到」当成「这个人没有地址」。

### 5. 权限码复用 `crm.customers.*`，不新开 `crm.merge.*`

本模块是 15.3.1.1 的下游，管理的还是同一批客户的身份关系。新开一套权限码只会让
「能看客户列表但不能看合并队列」这种业务上说不通的组合配置出现。
看明文联系方式仍然要**另外**的 `crm.customers.pii.read` **并**显式要求，没有权限还硬要 ⇒ **403**。

### 6. 🔴 过程中修掉的三个**真实缺陷**（都是集成测试才暴露的）

**(a) 迁移里的触发器在 INSERT 时也拿 OLD 比对。**
`guard_crm_customer_merge_candidate_order` 的「两侧不可改」检查在 INSERT 时也执行 ——
而 INSERT 时 `OLD` 全是 NULL，与 `NEW` 比必然「不同」⇒ **正常的插入被一起挡掉**。
修法：只在 `TG_OP = 'UPDATE'` 时检查。

**(b) 服务用 `UUID.compareTo` 决定候选的左右。**
它按**有符号 long** 比高位、再比低位；而 PostgreSQL 的 uuid 类型比的是**无符号字节**。
两者在高位 bit 为 1 时结论相反 —— 而随机 UUID 有一半的高位 bit 是 1，
也就是说「用 Java 排序」会随机地在一半的候选上与数据库的 `CHECK (left < right)` 打架，
而且**只在部分候选上发生，极难排查**。
修法：新增 `compareUuid()`，按 `uuid_send` 的字节序逐字节比（测试与生产同源）。

**(c) catalog 地址端口把上游的 JSON **对象**读成 String。**
`retrieve().body(String.class)` 在上游返回 `{"items":[...]}` 时，
Jackson 抛 `Cannot deserialize value of type java.lang.String from Object value`。
修法：改读 `JsonNode`。

### 7. 契约重生成的一个坑：用**活** spec，不用排序后的快照

`tools/codegen/catalog-contracts.py` 会把 `.openapi.json` 快照写成
`sort_keys=True` 规范化形态，而 `.ts` 产物按 **schema 的声明顺序**渲染属性。
若拿**已提交���（已排序）的快照**当输入重跑，9 个服务的 `.ts` 会**全量重排**（属性变字母序）——
一次毫无意义的全仓 diff 噪声。
修法：先用 9 个服务各自的 `OpenApiContractTest` 导出**活** spec，再跑 codegen ——
结果**只有 crm 自己的两个产物变化**（`contracts/crm.ts` + `crm.openapi.json`）。

### 8. api-proxy 的锚点：与同批三条车道都相距 3500 行以上

`apps/web/src/lib/api-proxy.ts` 是并行车道共同的累积型文件，同锚点会判冲突。
本车道的新条目锚在**紧跟 4.4.1.2 `listing-bulk-collects`（channel）那条元素之后** ——
与同批另外三条车道的锚点（identity 中段 / catalog 中段 / crm 中段 / 数组末尾）
**都相距 3500 行以上**。

首 token 选 `customer-merge`：与既有 `customer-tags` / `customer-contacts` / `customers`
三条 crm 分支的第 9 个字符互斥（`-` / `t` / `c` / `s`）⇒ 谁也不吃谁。

## 文件清单

### 后端（新增）

- `backend/services/crm/src/main/resources/db/migration/V437__crm_customer_merge.sql`（迁移，5 表 + 9 触发器 + RLS + 2 序列）
- `backend/services/crm/src/main/java/com/starcon/crm/CustomerMergeModels.java`（传输模型）
- `backend/services/crm/src/main/java/com/starcon/crm/CustomerMergeService.java`（服务）
- `backend/services/crm/src/main/java/com/starcon/crm/CustomerMergeController.java`（11 个端点）
- `backend/services/crm/src/main/java/com/starcon/crm/CustomerMergePartyPort.java`（catalog 地址只读端口）

### 后端（测试，新增）

- `backend/services/crm/src/test/java/com/starcon/crm/CustomerMergeIntegrationTest.java`（18 例）
- `backend/services/crm/src/test/java/com/starcon/crm/CustomerMergeTenancyIntegrationTest.java`（4 例，租户隔离三断言）

### 前端（新增）

- `apps/web/src/components/crm/customer-merge-model.ts`
- `apps/web/src/components/crm/customer-merge-model.test.ts`（22 例）
- `apps/web/src/components/crm/customer-merge-workspace.tsx`
- `apps/web/src/app/crm/customer-merge/page.tsx`
- `apps/web/src/app/api/crm/customer-merge-allowlist.test.ts`（5 例）

### 前端（改动）

- `apps/web/src/lib/api-proxy.ts`（**新增一条** services[] 元素，既有条目一字未改、不重排）

### 契约（codegen 产物）

- `packages/api-contracts/src/contracts/crm.ts`（新增 20 个 `CrmCustomerMerge*` 类型）
- `packages/api-contracts/src/crm.openapi.json`

### 文档

- `docs/product/capability-blueprint/module-progress.md`（**只改 15.3.1.3 那一行的状态与证据两格**）
- `docs/sessions/2026-11/2026-11-02-crm-customer-merge.md`（本文件）

## 验证

| 闸 | 命令 | 结果 |
|---|---|---|
| L0 编译 | `cd backend && ./mvnw -o compile` | BUILD SUCCESS |
| L1 模块闸 | `./mvnw -pl services/crm -am test -Dtest='CustomerMerge*'` | Tests run: 22, Failures: 0, Errors: 0 |
| L0 类型闸 | `pnpm typecheck` | 3 successful, 3 total |
| Lint | `pnpm lint` | 3 successful, 3 total |
| 前端单测 | `pnpm vitest run apps/web/src/components/crm/customer-merge-model.test.ts` | 22 passed |
| api-proxy 白名单 | `pnpm vitest run apps/web/src/app/api/crm/customer-merge-allowlist.test.ts` | 5 passed |
| **全量 allowlist 回归** | `pnpm vitest run apps/web/src/app/api` | 296 文件 / 1663 用例，**1660 通过** |
| 格式 | `pnpm exec prettier --check <本车道文件>` | All matched files use Prettier code style! |

⚠️ 全量 allowlist 回归里剩下的 **3 个失败**（`catalog/price-list-allowlist`、
`catalog/price-list-item-allowlist`、`trade/price-list-priority-allowlist`）
经 `git stash` 对照确认**基线即红** —— 5.1.1.2 车道已交付的 `price-lists` 路由
没有同步更新它们自己的负向断言。与本车道无关，且本车道不改别的模块的文件。

## 后续待办

1. **13 分析（客户价值跨店汇总）**：可读本模块的逻辑客户表按**币种分行**聚合（跨币种仍不相加）。
2. **15.3.1.2 客户详情**：可读本模块的 **ACTIVE 映射**与逻辑客户表（跨模块只读，纪律不变）。
3. **15.3.1.1「按逻辑客户展示」**：是它的**下游增强**（读本模块的映射），本模块**不写**它的表。
4. 🔴 **基线的 3 个 price-list allowlist 红灯**需要 5.1.1.2 车道或调度者处理 ——
   它们断言「`catalog/price-lists/lists/{uuid}/items` 应 404」，而该路由已由 5.1.1.2 交付。
