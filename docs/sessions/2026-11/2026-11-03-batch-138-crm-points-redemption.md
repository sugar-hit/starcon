# batch-138：15.6.1.2 积分与权益（卷 15 最后一格）

> 本批次覆盖 **15.6.1.2 积分与权益**（crm 服务，卷 15 L3 锚点 15.6.1 的最后一格）。
> 上游 **15.6.1.1 会员与等级** 本轮刚交付（batch-137），其
> `crm_membership_profile` 表与 trade 的订单事实接口是本模块的直接上游。
>
> ⚠️ 本车道的 worker 会话在**提交并 land 之后**被网络错误掐断
> （`net::ERR_HTTP2_PING_FAILED`），最终汇报没发出来；
> 但产出已完整落盘（commit `db5160eb`、`land` 成功），验证结果以本篇记录为准。

---

## 模块 15.6.1.2 积分与权益

**这个功能做什么**：运营配置积分规则（消费 / 评价 / 签到获取，抵扣 / 兑换消耗）与权益内容；
系统按订单事实给会员记积分、记消耗流水、跟踪每笔积分的**有效期**，
核销权益，并对外汇总**未兑付积分负债**。
**积分是负债** —— 未兑付的积分必须在财务侧有对应科目或备忘，不能只记在业务库。

**技术落地**：

- 迁移：`backend/services/crm/src/main/resources/db/migration/V442__crm_points_redemption.sql`
  （**六张新表**：`crm_points_rule` 积分规则 / `crm_points_account` 积分账户 /
  `crm_points_lot` 积分批次（有效期落在批次上）/ `crm_points_ledger` 积分流水 /
  `crm_points_entitlement` 权益 / `crm_points_redemption` 核销记录）
- 端点：挂 `/crm/points`（crm 服务，jOOQ 显式 SQL，**20 个操作**）：
  `capabilities` / `accounts`（+`{accountId}`）/ `rules`（+`{ruleId}` + POST + PUT）/
  `entitlements`（+`{entitlementId}` + POST）/ `ledger` /
  `POST earn` / `POST spend` / `POST redeem` / `redemptions` /
  `POST redemptions/{id}/use` / `POST expire` / `GET liability`
- 页面：`/crm/customer-points`（`apps/web/src/app/crm/customer-points/page.tsx` +
  `customer-points-workspace.tsx` + `customer-points-model.ts` 口径层）
- 测试：`PointsIntegrationTest` **23 例** + `PointsTenancyIntegrationTest` **4 例**
  （真实 PostgreSQL 16 + Testcontainers，租户隔离三断言实跑）；
  前端 `customer-points-model.test.ts` + `customer-points-allowlist.test.ts` **35 例**，
  改了共享白名单 `api-proxy.ts` ⇒ **全量 allowlist 回归 302 文件 / 1694 例全绿**

**关键设计决策**：

1. **有效期落在「批次」上，不落在账户上** —— `crm_points_lot` 记每一笔积分的来源、
   金额与到期时刻。账户余额是批次的聚合视图，不是可被随意改写的存量数字
   ⇒ 「这笔积分什么时候过期」「扣的时候先扣哪一批」都有唯一答案。
2. **扣减路径在显式 SQL 里可审阅**：扣前余额 → 扣后余额逐条看得见，
   并发扣减由 DB 层（条件更新 / 守卫触发器）保证，不靠 ORM 懒加载。
3. **十进制一律走文本出参**（与 15.6.1.1、1.3.2.1 同一口径）：
   JSON number 装不下「显示标度」，而积分抵扣配置要交给 5 定价。
4. **积分负债如实标注**：下游 12 财务若未交付，本模块只**输出负债汇总**
   （`GET /crm/points/liability`），**不代劳**在财务侧建科目，**不假装**已经入账。
5. **上游事实只有一个来源**：订单事实走 trade 的授权只读 HTTP API，
   会员事实读本服务自己的 `crm_membership_profile`；**不跨库读、不建跨库 FK**。

## 验证（worker 实跑）

```
后端 L1   ./mvnw -pl services/crm -am test -Dtest='Points*'  → 27 例全绿（23 业务 + 4 租户隔离）
前端新测  pnpm vitest run <本模块两个测试文件>              → 35 例全绿
共享白名单全量回归 pnpm vitest run apps/web/src/app/api      → 302 文件 / 1694 例全绿
契约      python3 tools/codegen/catalog-contracts.py --spec-dir backend/target/openapi → 9 份 spec 全部导出并重生成
类型 / 规范  pnpm typecheck 绿；prettier 自检绿
L0 编译   ./mvnw -q compile → BUILD SUCCESS
```

## 静态预检

新迁移 `V442` 为**六张新表**，无 `ADD COLUMN` 无 DEFAULT 缺失 / 无 `SET NOT NULL` /
无 `ADD CONSTRAINT CHECK` ⇒ 跨车道 DDL 回归面 = 0。
