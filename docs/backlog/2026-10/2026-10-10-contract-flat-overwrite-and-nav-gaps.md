# 契约平铺覆盖：新模块的 DTO 名撞掉已交付模块的类型（2026-10-10 取证）

**优先级：P1。这是仓库级地雷，不是单个模块的问题。**

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