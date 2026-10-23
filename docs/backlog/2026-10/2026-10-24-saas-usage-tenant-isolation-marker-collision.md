# 2026-10-24 用量统计「租户隔离」断言 —— 随机租户编码与标记数字撞车导致的假阳性

创建：2026-10-24。发现人：main 同步任务（并入 `main` 的闸门）。
影响范围：**identity 服务 17.7.1.1 套餐与权益 / 17.7.2.1 用量统计**（`saas_usage_statistic` 的租户可见性断言）。

本条是 `main` 合并闸门第 64 轮新增的阻塞原因之一：PR 的 Java 作业比 `main` 基线多出两条失败用例，
一条是已知的[平台开关公告生效期精度缺陷](2026-10-24-platform-flag-announcement-window-microsecond-precision.md)，
另一条就是本条。

⚠️ **本条不是租户隔离被突破，是断言自身会误判。** 看到这条用例变红时不要按「数据串租户」方向排查。

## 现象

CI 断言原文（run `40011998322`，Java 作业）：

```
java.lang.AssertionError:

Expecting actual:
  "[{"tenantId":"623e54f4-4723-4597-ac7b-e2a063c8c93d","tenantCode":"USE_TE4D52111B",
    "tenantName":"用量统计目标租户 E4D52111B",...,"quantity":222.0,...}]"
not to contain:
  "111"
	at com.starcon.identity.SaasUsageIntegrationTest.tenantIsolationHoldsOnThreeAssertions(SaasUsageIntegrationTest.java:631)
```

同一作业里基线已有的 `ApprovalRecordIntegrationTest` 失败之外，本条是**新增**失败。

## 根因：标记数字撞上了随机租户编码

### 1. 断言用「用量值」当租户隔离标记

`backend/services/identity/src/test/java/com/starcon/identity/SaasUsageIntegrationTest.java`：

```java
registerUsage(platform, tenantA, subA, "ORDER", 111);
registerUsage(platform, tenantB, subB, "ORDER", 222);
...
assertThat(forA.toString()).contains("111").doesNotContain("222");
assertThat(forB.toString()).contains("222").doesNotContain("111");
```

### 2. 但每条响应里都带着随机租户编码

`createTenant` 把随机标记拼进租户编码与租户名：

```java
"insert into tenant(id,code,name) values('" + tenantId + "','USE_T" + tag + "','用量统计目标租户 " + tag + "')"
```

`tag` 来自 `IdentityIntegrationTestBase.suffix()`：

```java
/** 随机标记，避免不同测试之间的编码撞唯一约束。 */
String suffix() {
  return UUID.randomUUID().toString().substring(0, 8).toUpperCase();
}
```

⇒ 8 位随机十六进制字符串。**它会出现在响应体里**（`tenantCode` / `tenantName` 两处）。

### 3. 撞车就在本轮发生

本轮随机标记取到 `E4D52111B` ⇒ 租户编码 `USE_TE4D52111B` 里**含有连续三个 `1`**
⇒ 被断言当作「另一个租户的用量标记」⇒ `doesNotContain("111")` 误判失败。

概率量级：8 位十六进制串里出现连续 `111` 的位置有 6 个，单位置概率 `(1/16)^3`，
合计约 **0.15% / 每个租户**。用例每次创建两个租户，CI 每次都会重跑 ⇒ **迟早会撞上一次**，
撞上就红，与被测代码无关。

## 为什么这不是租户隔离缺陷

本轮实际响应只有**一行**，且这一行就是被查询租户自己的数据：

- `tenantId` = 被查询租户的 UUID；
- `quantity` = `222.0` = 该租户自己登记的用量值（`contains("222")` 这条断言**通过**了）；
- 没有出现另一个租户的 UUID、编码或用量值。

⇒ RLS 与 API 层的租户可见性行为正确，失败的只是「用裸数字子串判断隔离」这个断言写法。
**不要按「数据串租户」方向排查，也不要因此放宽隔离相关断言。**

## 处置口径

**修在集成侧**（`integration/module-dev` 或从它切出的修复车道），**不要修在 main 同步分支上** ——
用例是集成分支交付内容的所有物，在同步分支上打补丁会让两边分叉。

- **把标记换成不可能出现在随机编码里的形式**：例如用一段带分隔符的长标记（`@@usage-111@@`）
  或直接断言租户 UUID 与 `quantity` 字段值，而不是在整段 JSON 文本里找裸数字子串。
- **不许改弱断言**：`@Disabled`、把 `doesNotContain` 换成 `isNotNull` 之类都不接受 ——
  这条用例守的是「两租户互不可见」，是 `AGENTS.md` 多租户章节要求证明的三条之一。
- **建议同时补一条反向用例**：断言另一租户的 **UUID 与编码**不出现在响应里，
  这比「不含某个数字」精确得多，也天然不受随机编码影响。

## 影响面

- 模块 17.7.1.1 / 17.7.2.1（identity 服务的套餐与权益、用量统计）。
- PR 合并：修好之前，`main` 合并闸门会持续在这条用例上阻塞（与平台开关那条**并列**，两条都要修）。

## 完成标准

- `SaasUsageIntegrationTest.tenantIsolationHoldsOnThreeAssertions` 在 CI 中转绿，且**反复跑不复发**。
- 断言改为对租户标识符做精确断言，而不是对整段 JSON 做裸数字子串匹配。

## 状态

- **Open**
