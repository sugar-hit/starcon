# `SaasUsageIntegrationTest` 的租户隔离断言用**子串匹配**整段 JSON ⇒ 时间戳里出现 `222` 就假红

- **日期**：2026-10-24
- **发现于**：batch-84 轮 L2 第一次尝试（`docs/sessions/2026-10/2026-10-24-batch-84-*.md`）
- **严重度**：中（按批触发，约每 150 轮 L2 会撞一次假红；撞上就浪费一整轮全量验证）
- **影响面**：`backend/services/identity/src/test/java/com/starcon/identity/SaasUsageIntegrationTest.java:630`
- **是否已修**：否 —— 流水线调度者「不写业务代码」是协议硬边界，本轮只取证并出隔离复跑结论

## 现象

batch-84 的 L2 第一遍（`.dev-agent/l2-run-batch-84-r1-flawk.log`）在 `identity` 模块挂掉：

```
[ERROR] Tests run: 515, Failures: 1, Errors: 0, Skipped: 0
[ERROR]   SaasUsageIntegrationTest.tenantIsolationHoldsOnThreeAssertions:630
[ERROR] Failed to execute goal ...maven-surefire-plugin:3.5.4:test (default-test) on project identity
```

surefire 报告里的断言详情：

```
Expecting actual:
  "[{"tenantId":"ffb66902-...","tenantCode":"USE_T15D16653A",...,
    "quantity":111.0,...,
    "collectedAt":"2026-10-24T11:05:59.057222+08:00",...}]"
not to contain:
  "222"
```

## 根因

断言把**整段响应 JSON** 做子串匹配，而响应里含 `collectedAt` 时间戳的**微秒小数部分**：

```java
// SaasUsageIntegrationTest.java:630
assertThat(forA.toString()).contains("111").doesNotContain("222");
```

`"2026-10-24T11:05:59.057222+08:00"` 里的 **`057222`** 恰好含子串 `222`
⇒ 本意是「租户 A 的报告里**不含**租户 B 的用量 222」，
实际被时间戳微秒位误伤 ⇒ **假红**。

命中条件：`collectedAt` 的 6 位微秒小数里出现 `222`（含跨位，如 `...222` / `2..2..`）。
单次概率约 **0.6%**（6 个位置 × 1/1000）。

## 为什么判定为**假红**而不是本批回归（三条独立证据）

1. **隔离复跑绿**：
   ```bash
   cd backend && ./mvnw -pl services/identity -am test \
     -Dtest='SaasUsageIntegrationTest' \
     -Dsurefire.failIfNoSpecifiedTests=false -DfailIfNoSpecifiedTests=false
   # ⇒ Tests run: 14, Failures: 0, Errors: 0 —— BUILD SUCCESS
   ```
2. **本批 3 个 merge commit 根本没碰 identity**：
   `git show --stat 0418935f / 115c7943 / 1c73d1e8` 的改动面只有
   `apps/web/src/lib/api-proxy.ts`、`packages/api-contracts/src/contracts/finance.ts`、
   `packages/api-contracts/src/finance.openapi.json`、台账 —— 全部在 finance 侧。
   且 `git log --since='2026-10-24 09:18' -- backend/services/identity/` 为空
   ⇒ 自上一次绿灯 L2（09:18:39）以来 **identity 一行代码都没变**。
3. **方向上不可能**：finance 依赖 identity（`ApprovalPort` 走 identity 的授权 API），
   identity **不依赖** finance ⇒ finance 的三条迁移（V358 / V359 / V361）不可能影响 identity 的测试。

## 建议修法

断言应针对**字段值**而不是整段 JSON 子串。可选：

1. 把 `forA.toString()` 换成解析后的字段断言（推荐）：
   ```java
   assertThat(forA).contains("111");
   assertThat(forA).doesNotContain("\"quantity\":222");   // 只匹配 quantity 字段
   ```
   或把响应反序列化成对象后断言 `quantity` 数值。
2. 若坚持用子串匹配，**先把时间戳字段从待匹配串里剔除**（例如只匹配报告主体数组的
   `quantity` / `tenantId` 片段），避免微秒位参与匹配。
3. 顺带排查同类写法：其它集成测试里凡是对**含时间戳的 JSON** 做 `doesNotContain("数字")`
   的断言，都有同款假红风险（`docs/backlog/` 里已有若干「假绿/假红」条目，属同一类）。

## 复现与验证

单次复现概率低（约 0.6%），用**构造**方式复现最稳：
把 `collectedAt` 的取值固定成一个含 `222` 的微秒串（或在测试里 mock 时钟），断言必挂。

## 关联

- 流水线协议「批量验证未过时的处置」：`tools/module-progress/PIPELINE.md`
  ——「已经是这种形状的成片失败 ⇒ 先隔离复跑最大那一堆，不要直接归因成回归」。
  本轮严格照此执行：**先隔离复跑（绿）→ 再查 merge commit 改动面（不涉及）→ 重跑全量 L2**。
- 同类历史条目：`docs/backlog/2026-10-22-trade-replenishment-upstream-404-flake.md`
  （另一个被误判成回归的 flaky 测试）—— 仓库里 flaky 测试会**反复**咬人，值得单开一条长期跟踪。
