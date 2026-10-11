# 契约平铺覆盖：新模块的 DTO 名撞掉已交付模块的类型（2026-10-10 取证）

**优先级：P1。这是仓库级地雷，不是单个模块的问题。**

## ✅ 建议方向 1 与 2 均已落地（2026-10-11）

### 方向 1：codegen 加碰撞检测（响亮失败）

`tools/codegen/catalog-contracts.py` 现在在**写出任何文件之前**先扫跨服务重名，
分级判定，不再静默：

- **结构完全相同** → 只打印 `note`（没有东西被吃掉，属于潜在风险）
- **结构不同** → 打印是哪两个服务、**谁会凭空多出字段 / 谁独有字段会消失**，**非零退出且一个文件都不写**
- 确实要接受某条有损重名 → 写进 `tools/codegen/contract-collision-allowlist.json`（进 code review 的 diff）
- allowlist 里已经不再重名的条目 → 每次跑都提示 `WARN` 可删，防止清单腐烂

同时把当时唯一那条有损重名也拆了：trade 的 `ProcessingModels.DownstreamGap` →
`ProcessingDownstreamGap`（warehouse 的同名类型由此恢复成它本来的 5 字段形状）。

### 方向 2：按服务分文件（结构性根治）

```
packages/api-contracts/src/
  contracts/<service>.ts   ← 各服务只出自己的类型，同名不再互相覆盖
  index.ts                 ← 汇总入口，显式绑定每一条重名
```

同名现在有两道网，且第二道**不依赖任何纪律**：

- 结构不同 → 方向 1 的闸门拦下；
- 结构完全相同 → 两份都活在自己的文件里，桶文件里 `export *` 撞名就是
  **TS2308 编译错误**（实测过，见会话文档）。忘了写消解行，编译会红而不是静默覆盖。

allowlist 的语义随之升级：放行时必须写 `winner`，指明桶文件绑定**哪一份** ——
否则放行后桶文件直接编译不过。

消费面 233 个文件的 import 从 `packages/api-contracts/src/catalog` 改为 `.../src/index`（242 处，纯路径替换）。

### 护栏

`tools/codegen/test_catalog_contracts.py`（14 条，`pnpm test:codegen`），已挂进 CI `test` job。
两条主线都**先标定再采信**：

- 方向 1 的断言对照旧脚本跑过：同一组 fixture 在旧脚本上是 exit 0 + 照写不误 + 静默顶掉；
- 方向 2 的断言对照真实生成物跑过：注入同名后 `tsc` 报 `TS2308` + `TS2353`，抽掉后退出码 0。

### 仍未做

建议方向 3（新模块一律带服务前缀）照旧有效，但**单独用不够**——它已经被证伪两次。
闸门是纪律的替代品，不是补充。

---

## 已发生两次（2026-10-10 / 2026-10-11），命名纪律已被证明守不住

| #   | 撞名                                                                                                                                       | 撞掉了谁                                                             |
| --- | ------------------------------------------------------------------------------------------------------------------------------------------ | -------------------------------------------------------------------- |
| 1   | `KnowledgeEntry` / `KnowledgeEntryDetail` / `KnowledgeVersion` / `KnowledgeReference` / `KnowledgeScope` / `KnowledgeCapabilities`（6 个） | `11.4.2.2` 售后知识库，30+ TS 错误                                   |
| 2   | `DownstreamGap`（1 个）                                                                                                                    | warehouse / purchase 的下游缺口类型，3 处 typecheck 报「属性不存在」 |

**第 2 次尤其说明问题**：那一条在派工前已被明确警告（「先扫一遍已存在的类型名」），
worker 也确实先扫了（确认 `SalesOrder*` 全空闲），**但漏掉了 `DownstreamGap`**——
因为它不是 `SalesOrder*` 家族的，扫描的思路是「按我要用的前缀扫」而不是「逐个比对我要定的每个名字」。

⇒ **纪律性做法（扫描 + 谨慎命名）已被证伪两次，不能作为防线。必须靠工具拦。**

## 现象

开发 `15.4.2.1`（CRM 知识库）时，它的传输模型与两天前刚交付的 `11.4.2.2`（售后知识库与话术）
**逐字符同名**：`KnowledgeEntry` / `KnowledgeEntryDetail` / `KnowledgeVersion` /
`KnowledgeReference` / `KnowledgeScope` / `KnowledgeCapabilities`。

跑 `python3 tools/codegen/catalog-contracts.py --spec-dir backend/target/openapi` 之后，
**11.4.2.2 的类型被整个摧毁**，`knowledge-workspace.tsx` 报 30+ 个 TS 错误。

## 根因

codegen 把**所有服务**的 spec 合并进**同一个扁平的 `packages/api-contracts/src/catalog.ts`**，
输出是平铺的 `export interface X` 列表，**同名 key 后者直接覆盖前者，没有命名空间、没有冲突检测**。

也就是说：**任何两个服务只要定义了同名 DTO，后跑 codegen 的那个就会静默吃掉前一个。**
症状是「我新模块的 typecheck 过了，但别人的页面挂了」，而且要等下一次 codegen 才暴露。

## 本次的处置

15.4.2.1 的全部 record 改用 `Crm` 前缀（`CrmKnowledgeEntry` 等），两边类型共存已验证。

## 建议的根治方向（按代价从低到高）

1. **codegen 加冲突检测**：合并后扫描输出里的重复类型名，发现同名直接**非零退出**并打印
   「哪两个服务的哪个 spec 都定义了这个名字」。这是最小改动、收益最大的一条 ——
   静默覆盖变成响亮失败。
2. **codegen 输出按服务分文件**：`catalog.ts` → `crm.ts` / `trade.ts` / `warehouse.ts`，
   由一个 `index.ts` 再导出。彻底消除平铺冲突，但会牵动所有 import。
3. **短期内靠纪律**：新模块的 DTO 一律带服务前缀。这条已经写在本次任务书里，
   但**依赖人不忘**，所以不能只靠它。

## 附带发现：同一个坑还有一处

记录组件上的 `@Schema(nullable = true)` 打在 record component 上时，
生成的 spec 是**裸 `$ref` 且不带 `nullable`**——契约声称非空，服务实际返回 `null`。
`6.4.1.1` 的 `inboundRequest` 有**完全相同**的漂移，属于既有问题。
15.4.2.1 的处理方式是：修自己那份的注解，并让前端对 `null` 做防御。

---

# `/crm/knowledge` 与 `/aftersales/knowledge` 未注册进导航

两个页面的路由都能打开、`next build` 里也是真实静态路由，但**都没进 `TASK_PAGES` / 导航**。

**为什么不顺手改**：注册会动到共享文件，并触发 `nav-routes.test.ts` 的文件系统校验，
把一个导航改动夹带进业务车道会让并入冲突面变大。**该给独立车道**。

现状：`11.4.2.2` 与 `15.4.2.1` 两个已交付模块的页面在产品里没有入口。

---

# catalog 既有 flaky 测试（非本次引入）

`ZombieCandidateIntegrationTest.最后维护时刻走timestamptz往返`

它的 `backdate()` 与断言**各调一次 `now()`**，两者可能跨过 1 毫秒边界，
于是「期望值」与「写入值」差 1ms 而失败（实测 `.660Z` vs `.661Z`）。

- 只在**全量并行**跑时出现（时间压力），**单跑 5/5 通过**
- catalog 模块本次零改动，**不是任何模块造成的**
- 修法：在测试里取一次 `now()` 复用，或把断言改成容差比较

**未修** —— 不该由业务车道去改别的模块的测试文件。记在这里等独立处理。
