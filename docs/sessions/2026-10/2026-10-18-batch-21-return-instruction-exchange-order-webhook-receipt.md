# 批次 21 — 退货指令下发 / 换货新单 / 接收记录

> 2026-10-18 22:23 回收，22:57 L2 全绿。集成头 `4b21ba70`。
> 本批由「静默窗口内临时批准」执行，跨过了常规 08:00–20:00 节奏。

## 本批交付

| 模块 | 名称 | 阶段 | 迁移 | 合并 commit |
| --- | --- | --- | --- | --- |
| `11.2.1.2` | 退货指令下发 | D4 | `V165__trade_rma_instruction.sql` | `0acca8a0` |
| `11.3.2.2` | 换货新单 | D4 | `V167__trade_exchange_order_link.sql` | `05a5751c` |
| `2.6.1.1` | 接收记录 | D5 | `V170__webhook_receipt.sql` | `9d3a3ef4` |

进度：190 → **193 已交付**（41.5%），未开发 272。

---

## 模块 11.2.1.2 退货指令下发

**这个功能做什么**：客服在退货授权单授权后，给买家下发一张「怎么寄回来」的指令单——寄到哪、什么时候之前寄到、怎么打包、贴哪张面单——并跟踪包裹回寄轨迹。本系统只负责**指引与跟踪，不控制实物**。

**技术落地**：

- 迁移 `V165__trade_rma_instruction.sql`（642 行）：五张新表，`tenant_id NOT NULL` + FORCE RLS isolation + GRANT，**零 ALTER 既有表、零外键** → 跨车道 DDL 回归面为 0
- 端点 12 个挂在 `/trade/rma-instructions`
- 页面 `/aftersales/return-instructions`：真实工作区（非占位页），五态齐全
- 测试 `RmaInstructionIntegrationTest` 25 例 + `RmaInstructionTenancyIntegrationTest` 4 例，实跑 Testcontainers PG16

**关键设计决策**：

- **地址与时限是快照不是引用**。3.6.3 的买家地址在 catalog 服务、平台地址在 channel 独立库，本服务跨库读不到；而且「买家可能已经照着旧地址寄了」，所以建单时连来源一起复制（`address_source` + `source_reference`）。
- **下发即冻结**（`guard_rma_instruction_frozen` 触发器钉死）。理由不是严谨而是现实：改地址要撤销后重建，不能就地改。
- **标签存真字节 + SHA-256，读取时服务端重算比对**，被改过立刻报 `checksumVerified=false`。**刻意不生成 PDF**——真实面单要走 10 物流的承运商接口（未交付）。
- **当前状态不落列**，由该运单号 `occurred_at` 最大的那条事件实时算出；买家可以分包裹寄回、也可以换承运商。
- **`/expire-overdue` 的判据刻意不含「有没有轨迹」**：时限到了而货在路上的单子仍可能签收成功，按「货没回来」关掉会让下游拒收一件真收到的货。

---

## 模块 11.3.2.2 换货新单

**这个功能做什么**：买家要换货，运营给换货单**真的建一张新销售单**，走正常出库流程发回给同一个买家，并跟踪闭环。

**技术落地**：

- 迁移 `V167__trade_exchange_order_link.sql`（636 行）：三张新表 + **本批唯一一处 ALTER 既有表**——按 V99 文件头自写的「R6 加值」纪律给 `sales_order.manual_kind` 补 `'EXCHANGE'`，写法幂等（DROP IF EXISTS → ADD）
- 端点 8 个 `/trade/aftersales/exchange-orders`
- 页面 `/aftersales/exchange-orders`
- 测试 `ExchangeOrderLinkIntegrationTest` 24 例 + `ExchangeOrderLinkTenancyIntegrationTest` 6 例 + 前端 37 例

**关键设计决策**：

- **新单必须是真单，这是本模块与假交付的分界线**。最容易做成假的是记一行 `new_order_no` + `status='SHIPPED'`，界面上「换货已发货」而 `sales_order` 里根本没这张单。所以每次建单都真的建单并写齐 `sales_order_line` / `order_address_snapshot` / `sales_order_party_snapshot`。
- **服务层 `sync` 不接受任何状态入参**，只读真事实推进。页面上「手工标记已发货」的端点**根本不存在**（api-proxy 刻意不放行，测试反向断言 404）。这是四层防线的第一层。
- **`CLOSED` 当前只能由「已发出 + 旧货经批准免回」达成**，这是如实不是漏做：实查 `rma_authorization` 词表没有 `RECEIVED`，旧货实收状态机未交付，本模块自造 `RETURNED` 会与将来的实收状态机打架。`capabilities` 里 `oldGoodsReturnReceivedDelivered=false` 如实报出。
- **防重复换货靠 DB 三条唯一约束**，服务层 `SELECT ... FOR UPDATE` 锁 exchange_authorization 再建单——顺序是 load-bearing 的，否则两次并发请求各建出一张真 sales_order。测试断言重复请求后 `EXCHANGE` 单仍只有一张。

---

## 模块 2.6.1.1 接收记录

**这个功能做什么**：各平台往我们回调地址推 webhook 消息，我们要**验签**、**先落库再处理**、重复推送只处理一次，并给运营一个「接收记录」台账。

**技术落地**：

- 迁移 `V170__webhook_receipt.sql`（188 行）：`webhook_endpoint` + `webhook_receipt`，强制 RLS + 部分唯一索引（只处理一次）
- 端点：`POST /webhooks/{endpointKey}`（先落库返回 200）+ `GET /webhook-receipts(+/summary)` + 认领/收尾端点
- 页面 `/channels`「平台消息」段（登记入口 · 状态分布 · 接收记录）
- 测试 `WebhookReceiptIntegrationTest` 27 例，真实密钥对构造合法/非法签名

**关键设计决策**：

- **先持久化再处理**：平台回调超时会重投，所以必须先落库返回 200，业务处理异步。
- **HMAC-SHA256 验签用常数时间比对**；租户签名密钥用 AES-GCM 密文落库，明文不可回读。
- **部分唯一索引**实现「重复推送只处理一次」，落 DB 不变量而不是应用层先查一次——并发下两个请求都能查到「还没有」。

---

## 批次内的缺陷与修复

### `CHANNEL_WEBHOOK_SIGNING_KEY` 打断 channel 全服务上下文

**这是本批 L2 抓到的第一个真实跨车道破坏，值得单列。**

2.6.1.1 的 `WebhookService` 构造器上有 `@Value("${CHANNEL_WEBHOOK_SIGNING_KEY}")`（`WebhookService.java:78`）**且无默认值**，而该属性在任何配置文件里都没有定义——channel 当时连 `src/test/resources/` 目录都不存在。只有 `WebhookReceiptIntegrationTest` 和 `OpenApiContractTest` 两个测试类各自喂了它。

于是其余每个 channel 集成测试加载 `ChannelApplication` 时该 bean 创建失败 → `PlaceholderResolutionException` → 整个上下文起不来：

```
Tests run: 322, Failures: 0, Errors: 256
```

**为什么这条车道的 L1 是绿的**：L1 已按协议收窄成 `-Dtest='WebhookReceipt*'`，而那个类第 132 行**自己注册了这个属性**——它给自己补上了缺的依赖，另外 295 个 channel 测试根本没跑。

这正是 `PIPELINE.md` 里写明的那笔交易的另一面：「收窄 L1 后，同服务内的跨模块回归从 L1 挪到 L2」。**组批安全网第一次上线就抓到了东西。**

**修复**（`67993029`）：新增 `backend/services/channel/src/test/resources/application.properties`，只在测试作用域提供该属性。`WebhookService.java` 主代码一行未改——生产侧仍「缺了就启动失败」。

**为什么不能给 `@Value` 加默认值**：这个 key 是 `WebhookSignature.seal/open` 用来 AES-GCM 封装各租户 webhook 签名密钥的**主密钥**。给一个可预测的默认值等于给生产发一把公开密钥，是安全回归。

**一个计划外的坑**：最初按直觉建 `src/test/resources/application.yml` 会打挂全部测试。channel 主资源里本来就有 `application.yml`（datasource / jooq / jackson / springdoc），surefire classpath 上 `target/test-classes` 排在 `target/classes` 前，Spring Boot 取 `classpath:/application.yml` **只返回第一个匹配、不合并**——测试目录那份把主配置整个顶掉，表现为 `Failed to determine a suitable driver class`。改用 `.properties`（不同扩展名，两份一起加载不遮蔽）后干净通过。

---

## L2 结果

| 项 | 结果 |
| --- | --- |
| 后端 `mvnw verify` | **BUILD SUCCESS**，8 模块全过，约 17 分钟 |
| ├ channel | 322 例，0 失败 0 错误 |
| ├ trade | 1210 例，0 失败 0 错误 |
| └ warehouse | SUCCESS（8:43，本批最慢模块） |
| 前端 `typecheck` / `lint` | EXIT 0 / EXIT 0 |
| 前端 `vitest` | **227 文件 / 3970 例全过** |
| Next production build | EXIT 0 |
| `sync --check` | ✓ 台账与蓝图一致 |
| 静态预检 | 三种危险形状均未命中 |

## 遗留与待办

- `merge-ready` 的台账求并只合并各车道的**模块行**，总览与分阶段/分系统汇总块仍停在并入前的值。已用 `1345a80d` 提交 `sync.mjs` 重新生成的真实汇总。**这是求并逻辑的既有缺口**，后续每批都会复现，值得单独修。
- 3 条失联车道（`2.5.2.1` / `11.3.1.1` / `4.1.1.1`）的 worker 在 18:00 那一批因 Token Plan 配额（`errorCode 42212`）被连带杀死，成果未提交但**仍完整留在各自 worktree**，快照已存 `.dev-agent/rescue-2026-10-18-2224/`。
- 同类风险仍在：backend 任何服务新增带无默认值 `@Value` 的 bean 都会重演全服务上下文起不来。目前各服务都还没有 `src/test/resources`，这个「测试作用域补必需属性」的位置要等下次踩坑才自然长出来。
