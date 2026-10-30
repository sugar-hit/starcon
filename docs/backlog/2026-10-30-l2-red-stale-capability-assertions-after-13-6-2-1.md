# L2 红点：13.6.2.1 交付后，13.6.1.1 的「未交付」能力断言过期 —— 6 处断言要一起改，只改报错的 2 处会再红

> 发现时间：2026-10-30 11:00 流水线 round 131（只读观测对端 08:00 tick 的 L2 终判）
> 严重度：**中** —— **生产代码正确**，红的是**旧模块的过期测试预期**；但它**卡住整条流水线的 push**
> （L2 没绿 ⇒ 15 个 commit 推不上远端）
> 归属：`backend/services/reporting/src/test/java/com/starcon/reporting/CustomReportIntegrationTest.java`
> （模块 **13.6.1.1 自定义报表**，commit `e74b96b6`）
> 触发方：模块 **13.6.2.1 导出与订阅**（commit `4f153960`，把能力声明的两个开关翻成真）

---

## 现象

对端 08:00 tick 的整批 L2（09:04:57 → 11:05:22，总耗时 **02:00 h**）判 **红**：
reactor 里 **只有 `reporting` 模块 FAILURE**（其余 backend / tenancy / identity / catalog / channel /
warehouse / trade / crm / finance 全 SUCCESS），reporting **585 例中 2 failures、0 errors**，
全仓唯一失败测试类是 `com.starcon.reporting.CustomReportIntegrationTest`：

```
[ERROR] CustomReportIntegrationTest.能力声明端点可用
        → JSON path "$.exportAvailable" expected:<false> but was:<true>
[ERROR] CustomReportIntegrationTest.能力声明显式标出未交付的导出与投递
        → [导出属 13.6.2.1，未交付] Expecting value to be false but was true
```

## 根因

13.6.1.1 交付时，**导出与投递确实还没交付**，所以能力声明端点如实返回 `available=false`，
并在旧测试里把这个「显式标出未交付」的状态**写成了断言**：

```java
// ReportingCustomReportService.java:145（13.6.2.1 之后）
new CustomReportCapabilities(
    true, true, true,
    true,                       // exportAvailable        ← 交付前是 false
    "13.6.2.1 导出与订阅", "",  // exportOwnerModule / exportUnavailableReason（reason 已清空）
    true,                       // deliveryAvailable      ← 交付前是 false
    "13.6.2.1 导出与订阅", "",  // deliveryOwnerModule / deliveryUnavailableReason
    ...);
```

13.6.2.1 交付后这两个开关变真 ⇒ **旧断言过期**。
两个测试类现在**互相矛盾**：新模块自己的 `ExportSubscriptionIntegrationTest` 断言 `isTrue()` / `isEmpty()`，
旧模块的 `CustomReportIntegrationTest` 断言 `isFalse()` / `isNotBlank()`。

⇒ **生产行为是正确且有意为之**（对端 commit `4f153960` 正文写明
「capabilities 的 exportAvailable / deliveryAvailable 改 true」），**要改的是旧测试**。

## ⚠️ 关键坑：surefire 只报 2 failures，实际要改 **6 处**

AssertJ 在**第一处失败就抛异常中止** ⇒ 同一个测试方法里后面的断言**根本没执行**，
所以报告里只看到 2 个 failures。**只改报错的 2 处，重跑会接着红。**

| #  | 位置                              | 现值                                        | 应改为                          |
| - | --------------------------------- | ------------------------------------------- | ------------------------------- |
| 1  | `CustomReportIntegrationTest.java:285` | `exportAvailable()).isFalse()`         | `isTrue()`                      |
| 2  | `CustomReportIntegrationTest.java:287` | `exportUnavailableReason()).isNotBlank()` | `isEmpty()`（对齐新测试 L681）  |
| 3  | `CustomReportIntegrationTest.java:288` | `deliveryAvailable()).isFalse()`        | `isTrue()`                      |
| 4  | `CustomReportIntegrationTest.java:290` | `deliveryUnavailableReason()).isNotBlank()` | `isEmpty()`（对齐新测试 L683） |
| 5  | `CustomReportIntegrationTest.java:865` | `jsonPath("$.exportAvailable").value(false)` | `value(true)`                |
| 6  | `CustomReportIntegrationTest.java:866` | `jsonPath("$.deliveryAvailable").value(false)` | `value(true)`              |

**不用改**（实测已通过）：

- L286 / L289：`exportOwnerModule()` / `deliveryOwnerModule()` `contains("13.6.2.1")` —— 归属模块没变。
- L815：`result.note()).contains("未交付")` —— 那是 `run()` 的 note，与能力声明无关。

**建议同步改文案**（不改也能过，但语义会自相矛盾）：

- L283 方法名 `能力声明显式标出未交付的导出与投递`
- L285 / L288 的 `.as("…属 13.6.2.1，未交付")`

## 修法与影响面

| 项       | 值                                                                 |
| -------- | ------------------------------------------------------------------ |
| 改动面   | **1 个测试文件**，**0 生产代码**、**0 迁移**、**0 端点**、**0 页面** |
| 车道建议 | `fix-13-6-1-1-capability-assertion`（分支 `fix/13-6-1-1-capability-assertion`），与 `lanes.json` 里已有的 8 条 `fix-*` 车道同构 |
| 顺序     | **先派修复车道并入，再重跑 L2** —— 不要在集成头上直接改（round 107 的先例） |
| 验证     | L1：`./mvnw -pl services/reporting -am test -Dtest='CustomReportIntegrationTest*' -Dsurefire.failIfNoSpecifiedTests=false -DfailIfNoSpecifiedTests=false` |
| 二分     | **不需要 `git bisect`** —— 红点已由 surefire 报告直接定位到单个测试类与单行断言 |

## 教训（值得沉淀成规约）

**「显式标出未交付」的能力断言是跨车道交付顺序的天然雷区。**

13.6.1.1 把「导出与投递未交付」写进了**断言**（而不只是文档）。一旦 13.6.2.1 补齐，
**后交付的模块必须回头更新先交付模块里这类断言**，否则整批 L2 卡在 push 之前。

⇒ 建议规约（属代码规约，改规约本身要派车道）：

1. 凡是把「某能力未交付」写成**断言**的地方，断言文案必须带**可检索的标记**
   （如 `未交付` / `属 13.6.2.1`），方便下一条补齐该能力的车道用 grep 一次找全。
2. 补齐能力的车道，**必须**在同一批次内 grep 上游模块里 `未交付` 标记并同步更新断言。

## 相关

- 本轮完整取证与汇报：`docs/sessions/2026-10/2026-10-30-1100-pipeline-round131-gate-held-peer-l2-red-capability-assertion.md`
- 批次说明 `batch-115`（覆盖 `13.6.1.2` 看板搭建 + `13.6.2.1` 导出与订阅）**尚未写**，编号留给持闸的 08:00 tick。
