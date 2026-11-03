# 2026-11-03: 积分与权益（15.6.1.2）

## 摘要

交付蓝图卷 15 L3 锚点 15.6.1「会员体系」下的 **15.6.1.2 积分与权益**：crm 服务新增
**V442 迁移**（六张新表）、**16 个端点**（`/crm/points/**`）、前端
**积分与权益看板**（`/crm/customer-points`）与**口径层模型**、契约（codegen 重生成）、
台账与本篇会话日志。

积分比率**全部是租户数据**（`crm_points_rule`，代码里**没有任何写死的积分比率**）；
上游 9 订单经**授权只读 HTTP API**（`GET /trade/sales-orders/list`）回源核对；
会员身份读**本服务** 15.6.1.1 的 `crm_membership_profile`（只读）。

蓝图加粗句「**未兑付积分是负债**，需在财务侧有对应科目或备忘，不能只记在业务库」
在本模块落三层：余额 DB 层硬保证不为负、流水每行带扣前 → 扣后余额且**只追加**；
单积分折算价值是租户数据且**一个租户同时只能有一条开着的估值规则**；
负债汇总**只输出**，出参 `bookedInFinance` **恒为 false**，能力声明里
`finance_liability_booking` 一维 `available=false` + 归属模块 **12** ——
**不代劳在财务侧建科目，不假装已经入账**。

## 决策 / 结论

### 1. 🔴 加粗句「未兑付积分是负债」—— 三层落地 + 一件**代劳**的事都不做

| 层 | 落点 | 说明 |
| --- | --- | --- |
| ① 量 | `crm_points_account.balance`，**CHECK >= 0** | 余额永不为负是 DB 层硬保证；扣减走 `update … where balance >= ?` 条件更新，余额不足时**更新 0 行** |
| ② 口径 | **VALUATION 规则**（`point_value_rate` + `liability_currency`）+ **部分唯一索引**保证一个租户同时只有一条开着的估值规则 | 负债 = 未兑付积分 × 单积分价值。口径必须唯一，否则「积分负债汇总」会有两个答案，财务侧没法对账 |
| ③ 输出 | `GET /crm/points/liability` + 能力声明 `finance_liability_booking`（`available=false`，`ownerModule=12`） | **只输出汇总**。出参 `bookedInFinance` 恒为 `false`，`financeNote` 直写「12 财务侧**未交付**积分负债科目」 |

**为什么不建「负债快照表」**：蓝图说「不能只记在业务库」——在业务库里再存一份
「已入账」的快照，正好是那句话要防的事。负债汇总是**读数**，不是账；
财务侧补科目后消化差额，才是对的边界。

### 2. 🔴 扣减必须可审阅 + 并发安全

流水行同时记 `balance_before` 与 `balance_after` ⇒「扣前余额 → 扣后余额」在**一行**里
看得见，不靠翻别的表推。**流水只追加**（DB 触发器拒绝 UPDATE / DELETE）。

并发扣减的 DB 层保证是**条件更新 + 行锁**：

```sql
update crm_points_account set balance = balance - ? … where id = ? and balance >= ? returning balance
```

余额不足 ⇒ **更新 0 行** ⇒ 拒绝。PostgreSQL 在行锁上重新判定谓词 ⇒ 两个并发请求
不会都扣成功（测试 `余额不足被拒_并发扣减只有一个成功` 用两个线程实跑验证：
恰好 1 个 200、1 个 409，库里只有**一条** SPEND 流水）。

🔴 **加锁顺序统一**：所有改余额的路径都**先锁账户行、再动批次** ⇒ 批次状态此刻稳定，
也不会「批次被扣光、余额却没扣」的中间态，且不会死锁。

### 3. 🔴 积分有效期按**绝对时刻**判定

批次表 `crm_points_lot.expires_at` 是 `timestamptz`；消耗时**先到期先消耗**
（`order by (expires_at is null), expires_at asc`）；到期由 `POST /expire` 结算 ——
账户余额与批次剩余量**同步减**，两者始终相等，并留一条 `EXPIRE` 流水。

测试 `有效期按绝对时刻判定_带偏移量的时刻写回等于同一绝对时刻` 用
`2026-11-01T08:00:00+08:00` 发积分、有效期 12 个月，断言**写回库里的那一列**
等于 `2027-11-01T00:00:00Z`（而不是只断言没抛异常）。绑定一律
`OffsetDateTime` + `?::timestamptz` —— 用 `Timestamp.valueOf(LocalDateTime…)`
会在东八区**静默偏 −28800 秒**。

### 4. 🔴 不跨币种算

抵扣上限 = 订单金额 × 抵扣率上限 ÷ 单积分价值。抵扣率作用在**订单币种**的金额上，
折成积分需要**同币种**的单积分价值 ⇒ 订单币种与估值币种不一致时**直接拒绝**（400），
**不偷偷按一个汇率折算**。这与 15.6.1.1「金额不跨币种相加」是同一纪律。

### 5. 积分比率是租户数据，代码里**没有写死的比率**

三种规则共用一张表：`EARN` 获取（来源 PURCHASE 消费 / REVIEW 评价 / CHECKIN 签到 /
MANUAL 人工 + 每单位金额换多少积分 / 固定积分 + 消费门槛 + **积分有效期月数** +
每日上限）、`SPEND` 消耗（抵扣率上限 + 单笔最少抵扣）、`VALUATION` 估值
（单积分价值 + 负债币种）。**不配规则就没有积分**（earn 直接 409 并说清缺哪条规则）。

### 6. 跨服务纪律

- **上游 9 订单**在 trade ⇒ 只经 `GET /trade/sales-orders/list` 的**授权只读 HTTP API**
  （转发调用方自己的 bearer）。本服务**一条跨库 SQL / FK / 视图都没有**。
  🔴 该端点是**分页列表**、没有按订单 id 过滤的参数 ⇒ `PointsOrderFactsPort.orderById`
  **翻页扫描**并**有上限**；翻页触顶仍没找到 ⇒ **503**（「没扫到」≠「没有」，
  后者会被误读成「这客户没买过东西」）。
- **会员身份**读**本服务** 15.6.1.1 的 `crm_membership_profile`（只读）；
  订单归属按**来源店铺**核对（合并后的会员有多家店铺，按 customer_party_id 核对会误杀）。
- **评价**读**本服务** 15.2.2.1 的 `crm_product_review`（只读），评价的买家标识
  必须与会员的客户标识一致，否则 400。

### 7. 十进制一律走**文本**（出参 `String`）

与仓里 1.3.2.1 预警阈值、15.6.1.1 会员等级同一口径：积分 numeric(19,6)、金额
numeric(19,4) + `BigDecimal`，**禁浮点**；出参一律十进制文本 —— JSON number 装不下
「显示标度」，`300.0000` 会被解析成 `3E+2`，而积分抵扣配置要交给 5 定价。
前端同理：`pointsPercentText` 用**字符串移位**（`0.07 * 100` 在 IEEE 754 下是
`7.000000000000001`）。

## 过程中修掉的四个真问题（都不是「改断言迁就实现」）

1. **迁移漏列**：V442 的 `crm_points_redemption` 漏了 `redeemed_at` ⇒ Flyway 直接炸
   （`column "redeemed_at" does not exist`）。补列。
2. **`earn_source NOT NULL` 误伤 SPEND / VALUATION 规则** ⇒ 建规则一律 409，而服务层
   的 catch 把**所有**约束违同一律说成「已经有一条生效中的规则了」——把真问题盖成了
   误导人的话。修法两条：迁移里 `earn_source` 改可空（+ EARN 必须非空的 CHECK），
   **并且**服务层按 DB 的真实理由分说法（`duplicate key` 才说「已经有了一条」，
   其余把 DB 原话带出来）。
3. **`case when ? is null then …` 让 PostgreSQL 无法推断占位符类型**
   （`could not determine data type of parameter`）⇒ 权益核销 409。
   改成 `coalesce(?::varchar, order_ref)`，**参数显式 cast**。
4. **「没给积分数 = 用满额」的语义错了**：抵扣上限 500 分、余额 100 分时直接 409。
   改成取「抵扣率上限」与「当前可用积分」的**较小值**（结账时的自然语义）；
   **显式给了积分数就照给的来**，显式请求满足不了必须拒绝（悄悄少抵一部分会让人
   以为订单已经抵满）。

## 文件清单

**迁移**：`backend/services/crm/src/main/resources/db/migration/V442__crm_points_redemption.sql`

**后端**（crm 服务）：
`PointsModels.java` · `PointsService.java` · `PointsController.java` ·
`PointsErrorHandler.java` · `PointsOrderFactsPort.java`

**测试**（crm 服务）：
`PointsIntegrationTest.java`（业务不变量）· `PointsTenancyIntegrationTest.java`
（**租户隔离三断言**）

**前端**：`apps/web/src/app/crm/customer-points/page.tsx` ·
`apps/web/src/components/crm/customer-points-{workspace.tsx,model.ts,model.test.ts}` ·
`apps/web/src/app/api/crm/customer-points-allowlist.test.ts` ·
`apps/web/src/lib/api-proxy.ts`（**新增一条** `prefix: 'crm/'` 的 services 元素）

**契约**：`packages/api-contracts/src/crm.openapi.json` ·
`packages/api-contracts/src/contracts/crm.ts` · `packages/api-contracts/src/index.ts`
（**codegen 产物，未手改**）

**台账**：`docs/product/capability-blueprint/module-progress.md`（**只改 15.6.1.2 那一行**）

## 关键设计取舍

- **六张表而不是四张**：15.6.1.1 是四张表；本模块多了「积分批次」（有效期载体）、
  「权益目录」与「权益核销记录」。批次单独成表是为了让**流水保持只追加** ——
  若把「剩余可消耗量」放进流水行，流水就得能被 UPDATE，「只追加」就成了空话。
- **抵扣的「先锁账户行、再动批次」**：反过来（先动批次再扣余额）会在并发下出现
  「批次被扣光、余额却没扣」的中间态。统一加锁顺序同时解决了死锁。
- **到期结算是显式动作**（`POST /expire`），不是后台定时器：定时器需要额外的调度
  载体与运维口径，而「谁来结算、什么时候结算」在 ERP 里本来就该是一次可审阅的动作。
- **「没给积分数」= 能抵多少抵多少**：结账抵扣的自然语义。显式请求仍然严格。

## 后续待办

- **12 财务侧**补积分负债科目 / 备忘，消费 `GET /crm/points/liability` 的汇总
  （本模块**没有**代劳，也没有假装已入账）。
- **5 定价侧**消费抵扣口径（抵扣率上限 / 单积分价值 / 可用余额）实现积分抵扣落单；
  本模块**只输出**口径，不改价目表。
- **积分冲正**（退货 / 退款后回退已发积分）**本轮未交付**：它需要一条**反向**流水
  与批次回补口径（当前批次 remaining 只减不增），交付前必须先把回补语义设计清楚。
- **积分抵扣的自动化**目前是「显式调 `POST /spend`」；接进 5 定价的落单流程时，
  需要补一条幂等键（当前靠 `crm_points_ledger` 的部分唯一索引挡住「同一订单重复发积分」，
  但**同一订单多次抵扣**尚未被挡住）。

## L1 验证（真实结果）

全部命令在 `/Users/guan/Projects/starcon-worktrees/integration/.worktrees/15.6.1.2` 下实跑，
下面每一行都是**命令的真实输出**，不是「已验证」。

### L0 编译闸

```bash
cd backend && ./mvnw -q compile
```

→ **exit 0**，无输出（`-q` 下无 ERROR/WARNING）。

### L0 格式自检（只查本车道改动过的前端文件）

```bash
pnpm exec prettier --check $(git status --porcelain | awk '{print $2}' | grep -E '^(apps/web|packages)/' | tr '\n' ' ')
```

→ `Checking formatting... All matched files use Prettier code style!`

### 类型检查（前端）

```bash
pnpm typecheck
```

→ `Tasks: 3 successful, 3 total`（`@starcon/web` 的 `tsc --noEmit` 无错误）。

### L1 模块闸（只跑本车道新增的测试类）

```bash
cd backend && ./mvnw -pl services/crm -am test -Dtest='Points*' \
  -Dsurefire.failIfNoSpecifiedTests=false -DfailIfNoSpecifiedTests=false
```

→ surefire 报告：

```
Tests run: 23, Failures: 0, Errors: 0, Skipped: 0 -- in com.starcon.crm.PointsIntegrationTest
Tests run:  4, Failures: 0, Errors: 0, Skipped: 0 -- in com.starcon.crm.PointsTenancyIntegrationTest
```

**合计 27 例，0 失败 0 错误。**（`PointsTenancyIntegrationTest` 的 4 例里，
租户隔离三断言分别是：① 两租户互不可见 ② 伪造 `X-Tenant-Id` 头无效**且行落在会话租户名下**
③ 连接池压到 1 后归还不串租户。）

### 前端测试（只跑本车道改动过的测试文件）

```bash
pnpm vitest run apps/web/src/components/crm/customer-points-model.test.ts \
                 apps/web/src/app/api/crm/customer-points-allowlist.test.ts
```

→ `Test Files 2 passed (2)` · `Tests 35 passed (35)`

### 🔴 共享白名单全量回归（因为本车道改了 `apps/web/src/lib/api-proxy.ts`）

```bash
pnpm vitest run apps/web/src/app/api
```

→ `Test Files 302 passed (302)` · `Tests 1694 passed (1694)` —— **其它车道已登记的路由
一条都没被本车道误伤**（取错一侧会静默删掉别人的路由，表现为页面 404，
而 typecheck / lint / vitest 全绿）。

### 契约

```bash
# 9 个服务的 OpenAPI 快照由各自服务的 OpenApiContractTest 导出
cd backend && ./mvnw test -Dtest='OpenApiContractTest' \
  -Dsurefire.failIfNoSpecifiedTests=false -DfailIfNoSpecifiedTests=false
→ BUILD SUCCESS（9 个模块全 SUCCESS）

python3 tools/codegen/catalog-contracts.py --spec-dir backend/target/openapi
→ Generated 8041 DTOs in 9 per-service files, 9 OpenAPI snapshots and one barrel
```

`git status` 显示契约产物**只变了 crm 那两份**（`contracts/crm.ts` + `crm.openapi.json`），
其余 8 个服务的产物逐字节未变 ⇒ 本车道没有波及别的服务的契约。

### 容器

本车道 verify 期间**同时存活 2 个 PostgreSQL 16 容器**（`PointsIntegrationTest` 与
`PointsTenancyIntegrationTest` 各持有一个**静态**容器，surefire 单 JVM 顺序执行两个测试类
⇒ 第二个类启动时第一个还在）。verify 结束后 `docker ps` 里**已无** `postgres:16-alpine`
容器 —— 两个都被 **ryuk 回收**（容器直接被删除，不是停在 exited；
`docker ps -a` 里只剩一个两天前的 `probe-pg`，不是本车道留下的）。
