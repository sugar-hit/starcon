# 11.4.2.1 工单管理（阶段 D7，L3 锚点 11.4.2）

- **车道**：feat/11-4-2-1（worktree `.worktrees/11.4.2.1`）
- **蓝图**：11-aftersales-and-returns.md 第 206–211 行
- **主责服务**：trade（与 11.1.1.1 售后单列表、11.4.1.1 平台纠纷同库）
- **迁移版本**：V270（预分配）

## 决策摘要

### 1. 预留表是**扩展**而不是重建

`aftersales_service_ticket` 是 11.1.1.1 在 V156 建的**本模块预留表**
（`case_id uuid NOT NULL` + `UNIQUE(tenant_id, case_id)` + `owner_module='11.4.2.1'`）。
V270 **只做 ALTER**，绝不 `CREATE TABLE` 同名表：

- 建同名表会让 Flyway 直接炸；
- 更要命的是 V156 已经把运行角色的权限与 FORCE RLS 策略配好了，
  重建出来的表没有那些东西 —— 一个「看起来对」的重建表是租户隔离的破口。

**21 个新列全部带 DEFAULT 或可空**（早先车道的 INSERT 必须继续成立 —— 仓库里真实发生过
「某车道把列改成 NOT NULL 且无 DEFAULT，当场破坏早先合入车道的 INSERT」的事故）。

**状态词表只加 `WAITING_BUYER`**（蓝图输出句点名「待买家」），其余三态的拼写一个字不动。
约束名**不硬写**（PostgreSQL 对内联 CHECK 的自动命名是实现细节），
迁移里按 `pg_constraint` 反查后命中才删。

### 2. 「非售后类」的落法：工单**挂**在售后单**之上**

蓝图说本模块管的是「非售后类的客户服务工单」，而预留表要求 `case_id NOT NULL`。
两者的调和口径（已写进 capabilities 与页脚）：

- 工单**不替代**售后单 —— 售后单管「退/换/补/修」，工单管「这次来问的这件事有人跟没有」；
- 一张售后单**只转一次**工单（预留表自带 UNIQUE），重复转**报错并附上已有工单号**；
- 工单与售后单的**上下文双向可达**：列表与详情都带出售后单号 / 状态 / 平台时限。

### 3. SLA 判定：真身只有 DB 一处 + 达标结论在关闭那一刻冻结

- 时限是**真实 timestamptz 列**，由租户 SLA 策略（`aftersales_reply_sla`，V164）按
  「平台精确档 > '' 兜底档」解析当时生效的那一档；
- **解析不到就报错**（不编默认小时数）—— 编出来的时限会让「没配 SLA」和
  「承诺 48 小时」在绩效口径上混成同一件事；
- 判定函数 `aftersales_ticket_sla_state` 是 **IMMUTABLE**，
  列表筛选 / 排序 / 行内展示 / 达成统计四处共用，Java 与前端都**刻意不重算**；
- **达标结论在关闭那一刻由 DB 用同一个函数算出并冻结**进 `aftersales_ticket_sla_snapshot`
  —— 「当时有没有达标」是已发生的事实，绩效按它算账，不随规则改档翻来覆去；
- 统计口径：**没配处理时限的工单不进处理时限的分母**（「没这项要求」与「没达标」是两件事）；
  分母为 0 时达成率是 `null` 而不是 0%。

### 4. 升级落成**两条真通道**（蓝图加粗句「工单与售后单要能互转」）

| 方向 | 落点 | 证据 |
| --- | --- | --- |
| 咨询 → 投诉 | **真的**建出一张售后单（11.1.1.1 登记入口） | 新单号回填 `escalated_case_id`（真外键） |
| 投诉 → 纠纷 | **真的**建出一条平台纠纷（11.4.1.1） | 纠纷号回填 `escalated_dispute_id`（真外键） |

两条都把工单号 / 售后单号 / 主题 / 诉求**原文带过去**（上下文保留），
且类型推进**带方向不可逆**（咨询不能直接升纠纷、投诉不能退回咨询）。

### 5. 附属事实一律新建表且 append-only

转派历史 / 事件流 / 满意度 / SLA 快照四类事实**都不塞进主表的当前态**：
主表是一格一值，历史是多值的；塞进去就得用数组或 json，而 json 里的事实没法建索引、没法进 WHERE。
四张表的运行角色 UPDATE / DELETE 权限**在数据库层被收回** —— 能被悄悄改掉的留痕等于没有留痕。

## 关键 trade-off

| 选择 | 放弃了什么 | 为什么 |
| --- | --- | --- |
| 工单**必须**挂售后单（不改 `case_id NOT NULL`） | 「没有售后单的纯咨询」这个形态 | 预留表已把这条钉死；另建一张平行表会让「这次工单跟的是哪笔生意」有两个答案，`UNIQUE(tenant_id,case_id)` 这条纪律也守不住 |
| SLA 解析不到就**报错** | 「先建单、后配 SLA」的宽松流程 | 编一个默认小时数会让「没配 SLA」与「承诺 48 小时」在绩效口径上混同；11.1.1.3 受理也是同一口径 |
| 达标结论**冻结成快照** | 规则改档后历史工单的口径可重算 | 绩效按「当时有没有达标」算账，结论必须只有一个答案 |
| 升级去���用**真外键** | 跨服务也能引（这里不需要） | 同服务同库是真外键的合法场景；「升级了但对方根本不存在」存不进库里 |
| 评价 / 快照在契约里是**列表**而不是「可空对象」 | 语义上更啰嗦的 API | OpenAPI 3.0 的 `nullable` 表达不了 `$ref` 上的可空（SpringDoc 会把 `nullable` 丢掉），契约里会留下「声明非空、实际可能是 null」的字段 ⇒ 前端按声明直接取属性就会在运行时炸。本仓库其它模块同样**没有**可空的嵌套对象 |

## 文件清单

**后端**

- `backend/services/trade/src/main/resources/db/migration/V270__service_ticket_management.sql`
  —— 扩展预留表 + 四张新附属表 + IMMUTABLE 判定函数 + 护栏触发器 + 索引 + RLS + 列级 GRANT
- `backend/services/trade/src/main/java/com/starcon/trade/ServiceTicketModels.java`
- `backend/services/trade/src/main/java/com/starcon/trade/ServiceTicketService.java`
- `backend/services/trade/src/main/java/com/starcon/trade/ServiceTicketController.java`
  —— 15 个 HTTP 映射（14 条路径形状）挂 `/trade/service-tickets`
- `backend/services/trade/src/test/java/com/starcon/trade/ServiceTicketIntegrationTest.java`（29 例）
- `backend/services/trade/src/test/java/com/starcon/trade/ServiceTicketTenancyIntegrationTest.java`（3 例）

**前端**

- `apps/web/src/components/aftersales/service-ticket-model.ts`
- `apps/web/src/components/aftersales/service-ticket-workspace.tsx`
- `apps/web/src/components/aftersales/service-ticket-model.test.ts`（36 例）
- `apps/web/src/app/aftersales/tickets/page.tsx`
- `apps/web/src/app/api/trade/service-ticket-allowlist.test.ts`（5 例）
- `apps/web/src/lib/api-proxy.ts` —— **新增一条独立 services[] 元素**
  （插入锚点：11.5.2.1 质量分析之后、11.1.1.2 售后单详情之前）

**共享生成物（多车道共用，按类型名 / OpenAPI 路径求并）**

- `packages/api-contracts/src/contracts/trade.ts`（+26 类型，-0）
- `packages/api-contracts/src/trade.openapi.json`（schemas +26 / paths +14）

**台账**

- `docs/product/capability-blueprint/module-progress.md` —— 只改 11.4.2.1 那一行
  的「状态」「证据」两格（1 行 diff）

## 过程中被测试逼出来的四个真实缺陷（已修）

1. **INSERT 占位符数量对不上**（`未设定参数值 21`）：21 个新列的 INSERT 模板里多写了一个 `?`。
   改成每个占位符都带显式 cast（`?::uuid` / `?::varchar` / `?::timestamptz` / `?::integer`），
   顺带让「空值列」在 PG 侧也有确定类型。
2. **`t.*` 与 join 表撞名**：`select t.*` 会与 `aftersales_case` 的同名列（id / tenant_id /
   version / created_at / updated_at）撞名，jOOQ 的 `record.get("id")` 在撞名时返回**第一个**匹配 ——
   哪天有人调一下列顺序，取到的就是另一张表的 id，而它不会报错。改成**逐列列出**。
3. **`ec` / `ed` 两个 join 漏了**：`escalated_case_no` / `escalated_dispute_no` 引用了两个
   没进 FROM 的别名 —— 编译期看不出来，运行期才炸。
4. **`ticket_status` 的 CHECK 约束名不能硬写**：PostgreSQL 对内联 CHECK 的自动命名
   （`<table>_<column>_check`）是实现细节，硬写 DROP CONSTRAINT 会让迁移当场炸。
   改成按 `pg_constraint` 反查后命中才删。

## 验证

- **L0**：`cd backend && ./mvnw -q compile` → exit 0
- **L0**：`TURBO_FORCE=true pnpm typecheck` → 3 successful / Cached: 0 cached, 3 total
- **L0**：`pnpm lint` → 3 successful
- **L1**：`./mvnw -pl services/trade -am test -Dtest='ServiceTicket*'` →
  **32 tests / 0 failures / 0 errors / 0 skipped**，BUILD SUCCESS
- **前端**：`pnpm vitest run apps/web/src/components/aftersales/service-ticket-model.test.ts` → 36 例
- **前端**：`pnpm vitest run apps/web/src/app/api/trade` → **64 文件 / 356 例**
  （本车道改了共享白名单 `api-proxy.ts`，按纪律跑**全量** allowlist 回归）
- **契约**：`python3 tools/codegen/catalog-contracts.py --spec-dir .tmp/specs` 重生成；
  按**集合**比对而非看 diff：trade schemas +26 / paths +14，**删除 0**，
  既有 schemas 与 paths **结构变化 0**；其余五服务 `+0/-0`

## 后续待办

1. **15 客户与客服（15.1.x / 15.3.x / 15.5.x / 15.6.x）未开发** ——
   「来自消息」的工单创建入口当前是**显式的来源引用**（来源标识 + 关联售后单 + 消息号由调用方给），
   本模块**不读任何不存在的消息表、不 mock 一条「买家发来的消息」**。
   15 的模块落地后，经**授权 API / event** 调本模块的建单入口即可衔接。
2. **11.4.2.2 知识库在 catalog 服务** ⇒ 工单上只落**逻辑引用** `knowledge_entry_id`，
   话术正文由前端经 catalog 的**授权 API** 取，trade 侧**不读**它的任何表。
3. **11.4.2.1 与 11.1.1.1 共用一张表、一个号段**（`aftersales_ticket_no_seq`）——
   11.1.1.1 的批量「转工单」写的正是本模块接管的表；
   两处的「一张售后单只转一次」纪律对两个入口同样成立。
   ⚠️ 11.1.1.1 的能力声明里还写着「11.4.2.1 未交付」，并入后应同步更新为已交付
   （属 11.1.1.1 车道的范围，本车道不改别人的文件）。
4. **`aftersales_service_ticket` 上没有「剩余小时数」列是刻意的** ——
   那一列写下去就开始自己过期；剩余时间在读取时刻由截止时刻算出。
5. 升级通道的 `COMPLAINT` 分支要目标售后单的五种类型 / 七类原因分类 / 金额 / 币种 / 数量，
   `DISPUTE` 分支要平台上下文（平台码 / 平台名 / 平台纠纷单号 / 纠纷类型 / 金额 / 币种 / 举证截止时刻）。
   两套词表**照抄既有模块**（11.1.1.1 的售后类型与原因分类、11.4.1.1 的纠纷类型），
   不另立一套 —— 另立一套会让「这是哪种售后单 / 哪种纠纷」在两个模块里给出两个答案。
   页面上的升级表单按通道切换字段，且**当场说清还差哪几项**（`escalateMissingFields`），
   不让点了才被后端 400 挡回来。
