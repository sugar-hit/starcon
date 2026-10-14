# 17.6.1.1 账簿与本位币（identity，阶段 D6）

车道 `17.6.1.1`，分支 `feat/17-6-1-1`，基线 `00bb41a`（integration/module-dev）。
迁移版本号 **V81**（预分配）。

## 决策摘要

**关键前提：17.1.2.2 已经交付了 accounting_book，本条不是重做账簿。**
读 `docs/sessions/2026-10/2026-10-07-base-currency-and-ledger-book.md` 的「后续待办」写得很明确：

> 17.6.1.1 账簿与本位币（随财务排 D6）：业务账 vs 法定账的切换，一个法人多本账的默认账选择。

所以 V81 只在这张已交付的表上**加法**，补齐跨境多账簿下真正缺的四件事：

1. **业务账 / 法定账** —— `book_category`（BUSINESS/STATUTORY）。它与 17.1.2.2 已有的
   `book_type`（BOOK/CASH/BANK）是**两条正交的轴**：一本法定账同样可以是银行账簿。
   把两者塞进同一个枚举就再也表达不了「这是报税用的法定账」。
2. **一本法人多本账、各账本位币不同** —— `base_currency_mode`（INHERIT/OWN）。
   17.1.2.2 的纪律是「本位币归法人，账上只是副本」；跨境场景（同主体同时人民币 + 美元记账）
   下这条不够用。INHERIT 保留原纪律（不传即 INHERIT），OWN 让账簿自定本位币。
3. **默认账簿** —— `is_default` + 部分唯一索引。**缺默认时显式 409，绝不静默挑一本。**
4. **跨账簿不混记** —— 新表 `book_fact_assignment`，把蓝图加粗那句
   「同一笔业务事实只在归属账簿记一次」落成 **UNIQUE(tenant_id, fact_source, fact_ref)**
   的数据库约束。写入方拿唯一冲突，应用层翻译成「该业务事实已归属账簿 X，跨账簿不混记」。

## 关键 trade-off

- **不新建第二张「账簿」表**：新建会让系统里同时存在两种账簿概念，正是蓝图批评的
  「单账簿假设」换个方向复发。V81 在同一张表上加列。
- **唯一总账口径从「一法人一本」放宽为「一法人一个本位币一本总账」**：
  业务账 CNY（BOOK）与海外账 USD（BOOK）要并存，就必须让索引带上 base_currency。
  同一本位币下的行为**完全不变**，17.1.2.2 既有用例仍绿。
- **`fact_source` 刻意不建闭集枚举**：订单/采购单/供应商账单这些来源域属于下游卷 12，
  identity 只校验格式并登记归属，不替 finance 定义业务词汇表。与 V5 的
  `legal_entity_reference` 是同一套约定（下游经授权 API 登记归属事实）。
- **归属登记可解除**：`DELETE .../fact-assignments/{id}` 让「不混记」可纠错，
  否则录错一次就变成死账、事实永远无法再归属。
- **金额 `numeric(19,6)` + BigDecimal，时间 `timestamptz` + OffsetDateTime**，
  values 位置写 `?::timestamptz`，测试按值断言 `booked_at` 偏移为 0。

## 跨车道约束回归（req #8）

本迁移**只有 ADD COLUMN ... NOT NULL DEFAULT**、新增表、换索引谓词；
**没有任何裸 `ADD COLUMN NOT NULL` 无 DEFAULT，也没有收紧既有表**，
所以并行 warehouse/trade 车道即使已提交不写这些列的 INSERT 也不会违约。
唯一「换」的是 `accounting_book_main` 的索引谓词（同表、同名、无数据风险），
17.1.2.2 的 13 个既有用例作为回归一起跑。

## 文件清单

**迁移**

- `backend/services/identity/src/main/resources/db/migration/V81__ledger_book_category_default_and_fact_ownership.sql`

**后端**

- `backend/services/identity/src/main/java/com/starcon/identity/LedgerBookController.java`（新）
- `backend/services/identity/src/main/java/com/starcon/identity/AccountingBookController.java`（**只做加法**：
  输入/读出口新增 4 个可空字段，缺省即老行为；`checkBaseCurrency` 变为 INHERIT 生效）
- `backend/services/identity/src/test/java/com/starcon/identity/LedgerBookIntegrationTest.java`（新，8 例）

**台账**

- `docs/product/capability-blueprint/module-progress.md`（17.6.1.1 一行）

## 未完成部分（原因）

- **前端页面未做**、**OpenAPI 契约未重生成**：本车道 22:29 开工、00:00 硬停机，
  时间预算被后端编译 + Testcontainers 占满。选择先保证「迁移 + 端点 + 测试」落地，
  而不是让前端半成品拖累 `land`。后续补：契约重生成 +
  `/settings/books` 工作区（业务账/法定账分组、默认账簿选择、归属登记面板）。
- **完整 `./mvnw verify` 未跑完**：只做到 `services/identity` 编译通过
  （BUILD SUCCESS）。集成测试执行与全量 verify 未在停机前完成，**不能声称测试已绿**。

## 踩坑记录

- macOS 没有 `timeout` 命令，`timeout 900 ./mvnw ...` 会瞬间失败；
  配合 `grep -E "Tests run|ERROR"` 会输出为空，**极易误读成「全绿」**。
  本车道因此连续两次误判编译状态两次。判断构建结果必须看
  `BUILD SUCCESS|BUILD FAILURE` 或 `${PIPESTATUS[0]}`，不能靠 grep 空输出。
- 编译报 `推论变量 U 具有不兼容的上限 / UUID无法转换为String` 时，根因是 record
  分量类型写错（`String legalEntityId` 写成 String），泛型报错只是下游症状。
  先修分量类型，别急着改泛型写法。

## 后续待办

- 12.5 会计科目与凭证：消费 `book_category` / `chart_of_accounts_source` 与
  `book_fact_assignment` 的归属事实生成凭证，**同一事实只能在归属账簿生成一次**。
- 前端 `/settings/books` 工作区 + 契约重生成。

---

# 追加：收尾验收（同日 23:14–23:35，验证 + 修绿）

上一棒在 commit 后、`lane.mjs land` 前被网络中断，车道仍 running。
本节是验收实测结论 + 为让 `./mvnw verify` 变绿所做的修复。**车道未 land**。

## 首跑真实结果：不是 flake，是 5 个确定性失败

首跑 `./mvnw verify` = BUILD FAILURE，`identity` 191 例中 5 例挂，且
`catalog` / `channel` / `warehouse` 三个模块根本没跑到（reactor SKIPPED）：

| 失败用例                                                                                             | 根因                     |
| ---------------------------------------------------------------------------------------------------- | ------------------------ |
| `AccountingBookIntegrationTest.bookCurrencyMustEqualTheLegalEntityCurrency`（**17.1.2.2 既有测试**） | 被本车道新用例连累       |
| `LedgerBookIntegrationTest.missingDefaultBookIsRejectedInsteadOfGuessed`                             | 同上                     |
| `LedgerBookIntegrationTest.onlyOneDefaultBookPerLegalEntityAndItMustBeActive`                        | 换默认账簿必 409         |
| `LedgerBookIntegrationTest.theSameBusinessFactCannotBeRecordedInTwoBooks`                            | 「跨账簿不混记」是死文案 |
| `LedgerBookIntegrationTest.factAssignmentKeepsItsTimestampAndCurrency`                               | 断言偏移量而非瞬时值     |

三个**产品 bug**（不是测试写错，值得单独立项记住）：

1. **换默认账簿必 409**：`setDefaultBook` 用一条
   `update ... set is_default=(id=?)` 同时把新账簿置真、旧账簿置假。
   PostgreSQL 按物理行序逐行校验部分唯一索引 `accounting_book_default`，
   先碰到新账簿时"旧的还没清掉"→ 唯一约束冲突。**改成先清后设两条语句**
   （同一事务，原子性不变）。第一次设默认没事，**换**默认必炸——只有两条都跑过才暴露。
2. **「跨账簿不混记」是死文案**：原 catch 只接
   `org.springframework.dao.DataIntegrityViolationException`，而实际抛出的是
   jOOQ `DataAccessException` 子类 `DuplicateKeyException`，接不到；就算接到了，
   `mixedBook` 在冲突**之后**才去查"事实已在哪本账"——此时 PostgreSQL 已把整个事务
   置 aborted，那一查自己就抛，调用方还是只拿到 `ApiErrorHandler` 的通用 409。
   **改为插入前先查既有归属**，命中即 409 + 指明原账簿；catch 只留并发兜底且不再查库。
3. **既有测试被连累**：`AccountingBookIntegrationTest` 断言全租户
   `GET /accounting-books` 只有 1 本账。这在"一法人一本"时恰好成立，
   17.6.1.1 让一个租户可以有多本账后，这个断言**按构造就不成立**。
   新旧用例共用同一个租户，于是新用例建的账把旧用例顶挂。
   已按其本意收窄为 `?legalEntityId=` 过滤（**改的是测试精度，不是行为**）。

其余两处：时区断言改为断言 ±1 分钟窗口内的**瞬时值**（服务按本机 +08:00 序列化，
断言偏移量等于 0 换台机器就假红，而真正要防的 8 小时偏移窗口已经抓住了）；
混记文案改从 `ResponseStatusException.getReason()` 取——identity 的业务错误按本模块
惯例抛 `ResponseStatusException`，MockMvc 不渲染成 JSON body，读 body 只会拿到空串。

## 收尾实测

- `./mvnw verify`：**BUILD SUCCESS**，backend/tenancy/identity/catalog/channel/warehouse
  全 SUCCESS；identity 191 · catalog 1205 · channel 66 · warehouse 700，
  **合计 2162 例，0 失败 0 错误**。
- 测试数**没有下降**：identity 修前修后都是 191 例；`git diff` 确认只改了用例体，
  没有增删 `@Test`。原本被 SKIPPED 的三个模块补跑后全绿。
- `pnpm typecheck` / `pnpm lint` 各 3/3 通过；`npx vitest run` 81 文件 1454 例全通过
  （vitest 不做类型检查，typecheck 单独跑过）。
- `node tools/module-progress/sync.mjs --check`：修复前 **红**，修复后 **绿**。

## 台账改回「进行中」

原提交把 17.6.1.1 标成「已交付」，但同一行的证据列自己写着"未做前端页面"，
且 `sync.mjs --check` 是红的（改了状态没重生成汇总数，D6 阶段仍显示已交付 0）。
按仓库约定「已交付 = 迁移 + API + 页面 + 测试齐全」，本模块**缺页面**：

- 蓝图把 17.6 的页面路由定在 **`/settings/books`**，该路由**不存在**。
- 17.1.2.2 的页面在 `/org/accounting-books`，只调 `identity/accounting-books`，
  **本模块 4 个新端点一个都没被前端调用**——业务/法定分组、默认账簿、归属登记
  目前**零 UI 入口**。所以不是"复用已有页面就够"，是确实漏了。
- OpenAPI 契约也未重生成。

已把状态改回 **进行中**（104 已交付 · 1 进行中 · 360 未开发），
证据列写清缺哪两块，并重生成汇总使 `sync --check` 恢复绿。

**车道没有 land**：后端已验证绿，但模块未完成且页面工作量不小，
按约定「不要 land 一个声称已交付却不成立的模块」，留给调度器决定。
