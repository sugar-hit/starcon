# batch-137：15.6.1.1 会员与等级（等级由真实消费数据驱动）

> 本批次覆盖 **15.6.1.1 会员与等级**（crm 服务，卷 15 L3 锚点 15.6.1）。
>
> ⚠️ 本车道的上一任 worker 会话被网络错误掐断（`net::ERR_CONNECTION_CLOSED`），
> 产出分两半留在 worktree 里：后端已 commit 且 22 例全绿，**前端未提交**。
> 接手方**没有重写已绿的后端**，只做了：修 1 处 typecheck 错误（`SectionCard` 的
> `action` 槽位在本仓叫 `extra`）、跑全量 allowlist 回归、核对契约与 codegen 逐字节一致、
> 补齐台账证据与会话日志、提交并落地。

---

## 模块 15.6.1.1 会员与等级

**这个功能做什么**：运营在 `/crm/customer-membership` 配置「等级规则」
（门槛、等级序、权益：折扣 / 免邮 / 优先发货），系统按上游 trade 的**真实订单事实**
算出每个会员的等级并留下等级变更记录，改完规则可触发一次重算。
**等级不能手工挂** —— 手工挂等级会让权益成本失控，这是蓝图加粗句的硬约束。

**技术落地**：

- 迁移：`backend/services/crm/src/main/resources/db/migration/V440__crm_membership_level.sql`
  （498 行，**四张新表**：`crm_membership_level_rule` 等级规则 /
  `crm_membership_profile` 会员档案 / `crm_membership_level_change` 等级变更记录 /
  `crm_membership_run` 重算台账；
  全部 `tenant_id NOT NULL` + ENABLE **与** FORCE RLS（DO 块逐表开）+
  **4 个守卫触发器（零外键）** + 索引 + GRANT + 序列单独授权；
  **零 ALTER 既有表** ⇒ 跨车道 DDL 回归面 = 0）
- 端点：挂 `/crm/customer-membership`（crm 服务，jOOQ 显式 SQL，**9 条路径 / 11 个操作**）：
  `capabilities` / `members` / `members/{memberId}` / `levels`（GET 列表 + POST 新建）/
  `levels/{ruleId}`（GET 详情 + PUT 改规则）/ `benefits` / `level-changes` /
  `POST compute` / `runs`
- 页面：`/crm/customer-membership`（`apps/web/src/app/crm/customer-membership/page.tsx` +
  `customer-membership-workspace.tsx` 768 行真实工作区 + `customer-membership-model.ts`
  口径层纯函数；加载 · 错误 · 空三态齐全）
- 测试：`MembershipIntegrationTest` **18 例** + `MembershipTenancyIntegrationTest` **4 例**
  （真实 PostgreSQL 16 + Testcontainers，**连接池压到 1**，三条隔离断言实跑于本模块自己的表上）；
  前端 `customer-membership-model.test.ts` **31 例** +
  `customer-membership-allowlist.test.ts` **5 例**（白名单**双向**断言：
  反向抽取源码里的真实调用点逐条断言放行、11 个刻意不放行的形状仍 404、跨车道形状仍放行）

**关键设计决策**：

1. **「会员等级必须由真实消费数据驱动」落成四层**：
   ① 应用层 —— **服务端没有任何接受等级代码的写路径**
   （`MembershipController` 只有 `POST /compute` 与等级规则 CRUD，**没有** set-level / override 端点）；
   ② DB 层 —— 触发器 `guard_crm_membership_origin` **直接拒绝** `level_origin='MANUAL_OVERRIDE'`，
   绕开应用层直连数据库也写不进去；
   ③ 报表口径 —— 等级来源落在 **`level_origin` 列**上，将来交付人工覆盖通道后
   报表按这一列即可区分「规则算出来的等级」与「人工覆盖的等级」，**现在恒为 RULE**；
   ④ 门槛 —— 门槛是**租户数据**（`crm_membership_level_rule`），
   **代码里没有任何写死的门槛**；不配规则就没有等级，全员 `NONE`（一等状态，不是「普通会员」）。
   **人工覆盖通道本轮未交付**：交付它必须先设计「原因 + 留痕 + 有效期」三件套，
   否则它就是加粗句要禁的那条路 ⇒ 能力声明里 `manual_level_override` 一维 `available=false`，
   `source` 直写「人工覆盖等级本轮未交付」。
2. **十进制一律走文本出参** —— JSON number 装不下「显示标度」（实测 Jackson：`1.0000` → `1`、
   `300.0000` → `3E+2`），而权益生效配置要交给 **5 定价 / 14 促销**，`3E+2` 会一路传下去。
   口径：金额 = 去尾零 + 至少两位小数且**不四舍五入**；配置里的比率 = 去尾零；
   判定算出来的复购率 = 固定 6 位。
   **顺带修掉一个真 bug**：jsonb 读回走 `readValue(raw, Object.class)` ⇒ 数字先落成 double
   （金额走了一遍浮点）⇒ 改成 `readTree` + `USE_BIG_DECIMAL_FOR_FLOATS`，全程 `BigDecimal`。
   前端同理：`Number(discountRate) * 100` 在 IEEE 754 下是 `7.000000000000001` ⇒ 改成字符串移位。
3. **时刻出参统一换算成 UTC**（同一绝对时刻只有一种写法）；
   等级变更的 `changed_at` 改成「这次判定发生的时刻」（原来用窗口终点，
   同一窗口的多次重算落在同一时间点上，列表排序只能靠随机 id 排）。
4. **门槛与等级序不可就地修改**（改门槛要新建一条带新生效起点的规则），
   但「只改启用状态」是运营最常见的动作 ⇒ 权益字段「**没给 = 不改**」、
   生效终点「**没给 = 保持原样**」。
5. **模块内自带的 `MembershipErrorHandler`**（`assignableTypes = MembershipController.class`，
   **不动共享的 `ApiErrorHandler`** —— 那是 crm 服务所有模块共用的文件）：
   业务拒绝的**理由进响应体**（默认 `sendError` 是空体，运营拿到 409 却不知道是
   「门槛不可就地修改」还是「币种没给」）。
6. **跨服务边界**：等级判定的事实来源只有一个 —— trade 的**真实订单事实**
   （`MembershipOrderFactsPort` → 授权只读 `GET /trade/sales-orders/list`）；
   会员等级事实读**本服务自己的** `crm_membership_profile` 表；
   **不跨库读、不建跨库 FK 或视图**。

## 接手时修掉的三处问题（上一任会话遗留）

1. **后端 12 处失败逐个定位修掉**（**没有删断言迁就实现**）：十进制文本化、
   时刻 UTC 化、`changed_at` 语义修正、PUT 的「没给 = 不改」、模块内错误 advice、
   能力声明补「人工覆盖未交付」、
   测试隔离（`@BeforeEach` 连 15.3.1.3 的合并表一起清 —— 合并用例的真实合并映射
   会漏给后面的用例，症状是「会员数从 4 变 3」，**病因在别的表**）。
2. **前端 1 处 typecheck 错误**：`customer-membership-workspace.tsx` 用了
   `SectionCard` 不存在的 `action` 槽位（本仓惯例叫 `extra`）⇒ 改为 `extra`。
3. **台账证据里的例数修正**：前端口径层测试实际 **31 例**，上一任写的 **24 例** 是旧数 ⇒ 已改。

## 验证（真实输出，接手方实跑）

```
后端 L1   cd backend && ./mvnw -pl services/crm -am test -Dtest='Membership*' \
             -Dsurefire.failIfNoSpecifiedTests=false -DfailIfNoSpecifiedTests=false
          → BUILD SUCCESS  Tests run: 22, Failures: 0, Errors: 0, Skipped: 0
            （MembershipIntegrationTest 18 ✓ / MembershipTenancyIntegrationTest 4 ✓）

前端新测  pnpm vitest run apps/web/src/components/crm/customer-membership-model.test.ts \
                        apps/web/src/app/api/crm/customer-membership-allowlist.test.ts
          → Test Files 2 passed (2) / Tests 36 passed (36)

共享白名单全量回归（改了 api-proxy.ts ⇒ 必须跑）
          pnpm vitest run apps/web/src/app/api
          → Test Files 300 passed (300) / Tests 1684 passed (1684)

类型 / 规范  pnpm typecheck → 3/3 packages successful（EXIT=0）
             pnpm lint      → 3/3 packages successful（EXIT=0），本车道文件 0 warning
             pnpm exec prettier --check <本车道 6 个前端文件> → 全过

契约核对   python3 tools/codegen/catalog-contracts.py --spec-dir backend/target/openapi \
             --out-dir .tmp/contracts-check  → EXIT=0
           与工作区已生成版本 **逐字节一致**（contracts/crm.ts 与 crm.openapi.json 均无差异）
           spec 里 9 条路径与白名单正则逐条对齐
```

## 未交付部分与明确原因

| 项 | 原因 |
| --- | --- |
| 人工覆盖等级通道 | 交付它必须先设计「原因 + 留痕 + 有效期」三件套，否则就是蓝图加粗句要禁的那条路；能力声明 `manual_level_override` 一维 `available=false`，DB 触发器亦拒绝 `level_origin='MANUAL_OVERRIDE'` |
| 平台会员体系对接 | channel 服务无平台会员接口；且平台侧等级**不回写**本服务（那会绕过「真实消费数据」这条硬约束） |
| 积分与权益 | 属 **15.6.1.2**，另一格 |
| 权益生效配置的下游消费（5 定价 / 14 促销） | 本模块只**输出**配置，不代劳改价目表 / 促销表（跨服务禁止直连对方库） |

## 静态预检

新迁移 `V440` 的「已知互相破坏形状」扫描
（`ADD COLUMN` 无 DEFAULT / `SET NOT NULL` / `ADD CONSTRAINT CHECK`）**零命中**，
且**零 ALTER 既有表**（四表全新建）⇒ 跨车道 DDL 回归面 = 0。

## 本批次的验证结论（L2）

### 第一次：L2 r165 —— **RED**（本批新增失败 1 条）

```
L2 r165   START=2026-11-03 06:34   END=2026-11-03 07:00
HEAD=7656a721   BACKEND_TREE=4bcd8235597b88439c48ed8a8b5609e70f1fb360

前端六步  typecheck exit=0 / lint exit=0 / test exit=0 / nextbuild exit=0
          sync-check exit=0 / format-gate exit=0      ⇒ 前端全绿
后端分块  ./mvnw -pl services/crm -am verify → BUILD FAILURE（14:12 min）
          crm  Tests run: 591, Failures: 0, Errors: 1, Skipped: 0

唯一失败  [ERROR] com.starcon.crm.MembershipIntegrationTest.合并后的来源店铺客户合成一个会员
          「候选的左侧必须小于右侧（9742f694-… >= 5c1fd149-…）：顺序变了同一对客户就会变成两行候选」
```

**判据**：这条**不在** `.dev-agent/l2-baseline.txt` 里（基线里没有任何 `Membership*` 条目）
⇒ **本批新增失败 1 条**（存量红 0）⇒ 按覆盖规则第 4 条，**先不 push**，走修复。

**根因**（不是生产代码缺陷）：

- 违规方是 V437 的触发器 `guard_crm_customer_merge_candidate_order`：
  `left_customer_id >= right_customer_id` 即拒 ⇒ PostgreSQL 的 `uuid` 比的是 **16 字节无符号**。
- 出错方是**本批新写的测试**第 850–851 行：`profileA.compareTo(profileB) < 0`。
  `java.util.UUID.compareTo` 先按**有符号 long** 比高位 ⇒ 高位 bit 为 1 时与 PostgreSQL 结论**相反**
  ⇒ 随机 UUID 约 **50% 概率随机红**。
  （上一任 worker 的 L1 单跑恰好是绿的那一半，所以没暴露 —— **这就是 L2 存在的意义**：
  单跑一次绿不代表它稳定。）
- 生产代码 `CustomerMergeService.compareUuid` **早就是对的**（无符号逐字节比，包级可见），
  同包的 `CustomerMergeTenancyIntegrationTest` **已经在用** ⇒ **只有本测试没照抄**。

**处置**：沿用原分支 `feat/15-6-1-1` 与原 worktree 派修复车道
（`lane.mjs adopt` 收编，不新建分支、不重做已有代码），
改用生产代码同一份 helper，并补一条钉死排序口径的断言
（用例覆盖「高位 bit 为 1」的那一对 UUID —— 在这一对里两种排序**结论必然相反**，
断言必须能区分，否则这条断言是假的），再连跑 3 遍 + 变异复现，改完重跑 L2 r166。

### 修复（commit `43f2e19a`）

```diff
-    UUID leftId = profileA.compareTo(profileB) < 0 ? profileA : profileB;
-    UUID rightId = profileA.compareTo(profileB) < 0 ? profileB : profileA;
+    boolean aIsLeft = CustomerMergeService.compareUuid(profileA, profileB) <= 0;
+    UUID leftId = aIsLeft ? profileA : profileB;
+    UUID rightId = aIsLeft ? profileB : profileA;
```

- 排序口径**只有一份真相**：直接用生产代码的 `CustomerMergeService.compareUuid`
  （包级可见，同包可调），**没有**新增第二份实现；**生产代码一行未改**。
- 顺带修掉同一处 insert 的另一个不一致：候选行的 `left_*` / `right_*` 展示列
  原来恒写「AMAZON / 买家甲」，排序换边后与 `left_customer_id` 对不上 ⇒ 改为跟随排序，
  并加一条回读断言钉死「展示列与排序后的 id 同源」。
- 新增一条**钉口径**断言：既断言 `compareUuid` 与 PostgreSQL 同序，
  又断言**同一个对在 `UUID.compareTo` 下结论相反**（保证这条断言不是恒真），
  并与**真实 PostgreSQL** 的 `select ?::uuid < ?::uuid` 对账。

**验证**：修复后 **5 遍连跑全绿**（18 + 4 = 22 例 / 遍，BUILD SUCCESS）；
**变异验证** —— 把两行改回 `profileA.compareTo(profileB)` 后**复现红**，
报错与 L2 r165 抓到的**同一句**（`候选的左侧必须小于右侧`）⇒ 证明这条断言真的钉住了口径。

### 第二次：L2 r166 —— **GREEN**

```
L2 r166   START=2026-11-03 07:19   END=2026-11-03 08:35
HEAD=1405fd44   BACKEND_TREE=5593e9225b3d8fb3143cdd8250b47feb656f244e

前端六步  typecheck exit=0 / lint exit=0 / test exit=0 / nextbuild exit=0
          sync-check exit=0 / format-gate exit=0（changedFiles=103 checkedFiles=51 violations=[]）
后端分块  ./mvnw -pl services/crm -am verify → BUILD SUCCESS（16:37 min）
          crm  Tests run: 591, Failures: 0, Errors: 0, Skipped: 0
复用      catalog / channel / warehouse / trade / finance / reporting / platform / identity ⇒ r164 GREEN（树哈希比对未变）

VERDICT=GREEN    本批新增失败=0    存量红=0
```


