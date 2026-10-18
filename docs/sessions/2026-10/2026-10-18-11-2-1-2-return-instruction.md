# 11.2.1.2 退货指令下发（阶段 D4，L3 锚点 11.2.1）

> 车道 `feat/11-2-1-2`，worktree `.worktrees/11.2.1.2`，基线 `integration/module-dev` 55bf6888。

## 交付内容

蓝图原文（`docs/product/capability-blueprint/11-aftersales-and-returns.md:122`）：

- 职责：向买家发出退货指引（地址、要求、时限），生成退货标签，跟踪买家回寄物流。
- 输入：退货授权；退货地址（3.6.3 或平台 RMA 地址）；渠道选择。
- 输出：退货指令 + 标签文件 + 回寄物流轨迹跟踪。
- 关系：上游 = 11.2.1.1；下游 = 10 物流（退货运单）、15 客服（通知买家）。
  **多数平台退货由买家寄回**，本系统只负责指引与跟踪，不控制实物。

## 关键决策

1. **地址与时限是快照，不是引用。** 建单时从 `rma_authorization` 连**来源一起**复制。
   理由不是「严谨」而是**买家已经照着旧地址寄了** —— 之后改授权单上的地址，
   不能把这份已发出的指引悄悄换掉。冻结由 DB 触发器 `guard_rma_instruction_frozen` 钉死，
   要改只能撤销后重建。
2. **标签是真字节 + SHA-256，不是占位串。** 内容由服务层按库里真值渲染
   （不接受前端拼的内容 —— 一张前端拼的标签，地址少一段城市照样能生成，而货会寄丢），
   读取时**重算比对**，被改过就报 `checksumVerified=false`。
3. **刻意不生成 PDF。** 真实面单要走 10 物流的承运商接口（未交付），
   自造一个「PDF」只会得到一份长得像面单、扫码扫不通的废纸。
4. **EXPIRED 只判「时限过了」，不判「货没回来」。** 时限到了而货在路上的单子
   在承运商口径里仍可能签收成功；按「货没回来」关掉会让下游拒收一件真收到的货。
   实收判定归 11.2.2.1 按收货事件去重。
5. **承运商与运单号挂在事件上而不是指令头。** 买家可以分包裹寄回、也可以换承运商重寄。
   当前状态按运单号分组、由最后一条事件实时算出，**不单独存列**。
6. **筛选值先过枚举再进 SQL。** 表里没有匹配行时表级 CHECK 根本不会被求值，
   拼错的状态值会静默返回零行 —— 运营会读成「没有这样的指令」。

## 落地位置

| 四件 | 位置 |
| --- | --- |
| 迁移 | `backend/services/trade/src/main/resources/db/migration/V165__trade_rma_instruction.sql`（五张新表，零 ALTER 既有表、零外键） |
| 端点 | `RmaInstructionController` / `RmaInstructionService` / `RmaInstructionModels`，`/trade/rma-instructions` 下 12 个 |
| 页面 | `/aftersales/return-instructions` + `components/aftersales/rma-instruction-{model,workspace}.ts(x)` |
| 测试 | `RmaInstructionIntegrationTest`（25 例）、`RmaInstructionTenancyIntegrationTest`（4 例，多租户三断言）、`rma-instruction-model.test.ts`、`rma-instruction-allowlist.test.ts` |

## 验证

- L0：`./mvnw -q -pl services/trade -am compile` 绿；`pnpm typecheck` 3/3 绿；`pnpm lint` 绿。
- L1：`./mvnw -pl services/trade -am test -Dtest='RmaInstruction*'` → **Tests run: 29, Failures: 0, Errors: 0**。
- 前端：`pnpm vitest run apps/web/src/components/aftersales/rma-instruction-model.test.ts apps/web/src/app/api/trade/rma-instruction-allowlist.test.ts` → 19 例绿。
- 全量 allowlist 回归：`pnpm vitest run apps/web/src/app/api/trade` → **36 文件 / 194 例全绿**
  （本模块用**独立 services[] 元素**，没有往既有 trade alternation 里并分支）。

## L1 期间真正抓到的缺陷（留档，避免复现）

1. `list()` 的 `timelinessCase` 有**三个**占位符（`remaining_seconds` 的 CASE 里还有一个），
   起初只绑了两个 → 「未设定参数值 6 的内容」。
2. `where` 子句被 count / summary / rows 三处查询共用，而 `fromBase` **没有**那两张 lateral join；
   在筛选里写 `lb.carrier_code` 会让总览一开就 500，而列表能开 —— 症状极具迷惑性。
3. `labelContent` 只查标签表，跨租户得到的是「当前没有未作废的标签」而不是
   「不属于当前租户」；而按 id 直达的端点必须给**同一条**消息。
4. 迁移里「作废的标签不能再带交付痕迹」这条 CHECK 会把
   「贴纸已到买家手上、承运商又取消运单」这个真实场景**卡死**（没法重出标签）—— 已删除该 CHECK，
   交付痕迹保留在 `delivered_at/by` 上。
5. 租户测试里我一度把 `asA` 嵌进 `asB` 的 lambda：连接池压到 1 时内层把
   `app.current_tenant` 重设成 A，这条「跨租户下发」的断言就变成了**永远为真的假绿**。
   已在代码注释里写成显式禁忌。

## 后续待办

- 10 物流退货运单与面单接口未交付：`rma_label.label_source` 的
  `MODULE_10_CARRIER_LABEL_API` 枚举已留口子，接入时按同一形状写入即可，表结构与查询都不用改。
- 承运商回调未接入、10 物流轮询未交付：轨迹当前只有人工登记一个来源，
  两个系统接入通道的枚举已留。
- 15 客服未交付：`delivery_channel` 记的是「打算怎么发」，真正送达渠道不在本服务。
- 11.2.2.1 / 11.2.2.2 / 11.2.3.x 未交付：本模块**刻意不建影子表**，
  `goodsReceived` 恒为 false 并带归属模块号。
