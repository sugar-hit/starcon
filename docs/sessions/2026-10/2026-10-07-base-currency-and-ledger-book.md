# 17.1.2.2 本位币与账簿（identity）

车道 `17.1.2.2`，分支 `feat/17-1-2-2`，基线 `a9b9987`（integration/module-dev）。

## 决策摘要

交付法人名下的会计账簿：本位币、记账规则（金额/数量精度与舍入、期间设置）。
账簿挂在**法人**上（`legal_entity_id` 复合外键），不挂租户 —— 一个租户可以有多个法人，
「店铺收入记到哪本账」取决于店铺绑的是哪个法人。

三块职责各有真实读出口：

- **会计账簿** —— `book_type` 总账/现金账簿/银行账簿；一个法人一本总账（部分唯一索引），
  银行账可按账户开多本。
- **本位币** —— `base_currency` 是法人本位币在账上的**落地副本**，写入时必须等于
  `legal_entity.base_currency`，否则 409。读出口同时给出 `baseCurrency` 与
  `legalEntityCurrency` 与 `currencyAligned`：法人改了币而账没跟上时显式暴露为待同步，
  而不是继续显示一个看起来没问题的币种代码。
- **记账规则** —— 金额与数量**各带一套** scale + rounding（金额按本位币最小单位、
  数量按计量单位，用一把尺子量两种量纲是 12 财务试算不平的常见原因）；期间设置 = 期间粒度
  （月度/季度/周度）+ 会计年度起始月 + 首期起点。读出口按设置**推导**出当前会计年度区间
  （`currentFiscalYear` / `fiscalYearStart` / `fiscalYearEnd`），不用等 12.5 生成期间行
  才发现起始月配错。

## 关键 trade-off

- **本位币存副本而不是每次 join 法人**：账簿要能独立表达「这本账按哪个币记」。代价是可能出现
  落后状态，用 `currencyAligned` 显式暴露而不是靠 join 时才发现。另加
  `base_currency_changed_at` —— 中途换币会让历史凭证的换算基准前后不一致，必须能回答
  「从哪一刻起这本账按哪个币记」；该时间戳**只在本位币真的变了才前进**，改名字不动它。
- **首期对齐用触发器而不是 CHECK**：约束的模数取决于 `period_type`（月度看 12、季度看 3、
  周度不看），写成 CHECK 要在每行重复这段分支。应用层先拦一次是为了给出能照着改的中文原因，
  迁移里的触发器是同一规则的数据库侧兜底。
- **期间边界按 UTC 日历定义**：timestamptz 显式 `at time zone 'UTC'` 取月，避免会话时区
  （Asia/Shanghai）把月初那天挪走，导致同一份数据在不同时区跑出两种对齐结论。前端
  `firstPeriodStart()` 也按 UTC 截日期，保证同一账簿在任何时区打开看到同一个期界。
  代价是没有「按法人本地时区切账年」，那需要账簿级时区列，超出本模块职责。
- **独立成 `AccountingBookController` 而不是并进 `AdministrationController`**：17.1.2.1 与
  本模块是两个 L4 单元，同一文件各自生长会在并入时互相打架；也顺带没有改动 17.1.2.1 已交付的
  法人端点行为。
- **`ON DELETE RESTRICT` 而非 CASCADE**：法人还有账簿时删不掉，返回 409。账簿是下游 12 财务
  的记账基准，静默级联删除会毁掉已建账配置。既有 V5 删除保护触发器照常生效。
- **本表没有金额/数量列**：本模块交付的是**配置**，金额与数量本身属于 12 财务。因此表里
  只有精度刻度（int）与舍入枚举，没有浮点也没有 NUMERIC 金额列 —— 这是按架构边界刻意留空，
  不是漏做。

## 文件清单

**迁移**

- `backend/services/identity/src/main/resources/db/migration/V43__accounting_book.sql`

**后端**

- `backend/services/identity/src/main/java/com/starcon/identity/AccountingBookController.java`（新）
- `backend/services/identity/src/test/java/com/starcon/identity/AccountingBookIntegrationTest.java`（新，13 例）

**契约（codegen 产物，未手改）**

- `packages/api-contracts/src/identity.openapi.json`（+3 路径 / +2 schema / 0 删除 / 0 operationId 位移）
- `packages/api-contracts/src/catalog.ts`（+`AccountingBookView` / +`AccountingBookInput`）

**前端**

- `apps/web/src/components/iam/accounting-book-workspace.tsx`（新）
- `apps/web/src/components/iam/accounting-book-model.ts` + `.test.ts`（新，7 例）
- `apps/web/src/app/org/accounting-books/page.tsx`（新）
- `apps/web/src/lib/api-proxy.ts`（identity 白名单放行账簿两个形状）
- `packages/ui/src/lib/task-pages.ts`（页面目录 + 账户菜单顺序）

**台账**

- `docs/product/capability-blueprint/module-progress.md`（17.1.2.2 一行 + 四处派生汇总）

## 未采纳的做法

- `catalog.openapi.json` 被 `pnpm contracts:generate` 一并重排了 8.7k 行（18/421 个 operationId
  计数器位移），但 schema 集合与 paths 与基线**完全一致** —— 那是 catalog 车道 live 生成与
  MockMvc 生成的编号差异，与本模块无关。已 `git checkout` 还原该文件，避免把无关churn 塞进
  本车道提交、也避免在多车道共享文件上制造冲突面。`catalog.ts` 由 schema 派生，不受影响。

## 后续待办

- 17.6.1.1 账簿与本位币（随财务排 D6）：业务账 vs 法定账的切换，一个法人多本账的默认账选择。
- 12.5 会计科目与期间：消费本表的记账规则与期间设置生成并封账；`base_currency_changed_at`
  是其分段结算的输入。
- 本表没有账簿级时区列，若将来要按法人本地时区切会计年度，需要在 V44 补列并改 UTC 取月口径。
