# 交接说明 · 车道 18.3.1.1「规则列表」（V419）

> **本文件是给下一个接手本车道的 worker 看的**（前一任被基础设施掐断，本文件由调度者代写）。
> **不要从头勘察全仓** —— 下面的取证结论已由调度者**亲自复核**，可直接采信。

## 一、前两任发生了什么

| 轮次 | 结果 | 留下的东西 |
| --- | --- | --- |
| 第一任（20:14–20:39） | `net::ERR_CONNECTION_CLOSED`，**只做勘察，0 commit** | 依赖自检结论（见下） |
| 第二任（20:39–20:57） | `net::ERR_HTTP2_PING_FAILED`，**只读模板代码，0 commit** | 模板位置（见下） |

⇒ **工作区干净、分支上 0 个新 commit ⇒ 没有任何成果丢失，也没有半截改动需要接续。**
⇒ 但基础设施已经掐了本车道两次。**第三个你务必先写代码、少勘察、多 commit。**

## 二、依赖自检 —— 已通过（调度者复核）

- 事件注册表（各业务事件类型）：**存在**
- channel 店铺列表：**存在**
- warehouse 列表：**存在**

落点服务：**platform**（`backend/services/platform/`，端口 8089）。
蓝图原文位置：`docs/product/capability-blueprint/18-integration-and-automation.md` §18.3.1.1。

## 三、照抄模板（前一任已定位，调度者复核）

**后端**（`backend/services/platform/src/main/java/com/starcon/platform/`）：
- `ExternalIntegrationService.java` / `ExternalIntegrationModels.java` / `ExternalIntegrationController.java` ← **18.2.3.1 其他对接**（同卷最新、同服务）
- `DataMigrationService.java` / `DataMigrationModels.java` / `DataMigrationController.java` ← **18.1.3.2 数据迁移**

**迁移**（`backend/services/platform/src/main/resources/db/migration/`）：
- `V417__data_migration.sql`（586 行，4 表，ENABLE **与** FORCE RLS + GRANT platform_app）← **18.1.3.2**
- `V418__external_integration.sql`（3 表，同上）← **18.2.3.1**

**前端**：
- `apps/web/src/components/integrations/data-migration-workspace.tsx` + `data-migration-model.ts` ← **18.1.3.2**
- `apps/web/src/components/integrations/external-integration-workspace.tsx` + `external-integration-model.ts` ← **18.2.3.1**
- 白名单双向守卫测试：`apps/web/src/app/api/platform/data-migration-allowlist.test.ts` ← **18.1.3.2**

**测试**：
- `backend/services/platform/src/test/java/com/starcon/platform/DataMigrationIntegrationTest.java`（23 例）
- `.../DataMigrationTenancyIntegrationTest.java`（8 例）← **租户隔离三断言的照抄模板**

## 四、api-proxy 白名单写法

`apps/web/src/lib/api-proxy.ts` 的 `services[]` **数组最开头**（第一个元素之前）新增一条独立 `platform/` 元素：
- id 段用**完整 UUID 形状**
- 末尾**刻意不带 `?`**（裸路径不放行）
- **既有条目一字未改、也不重排**
- 改了这个文件 ⇒ **必须**跑全量 allowlist 回归：`pnpm vitest run apps/web/src/app/api`

## 五、飞快的起步路径（前一任都栽在「勘察太久」）

1. **先写迁移**（V419，4~5 张表：规则档案 / 条件 / 动作 / 执行统计 …），照抄 V417 的 RLS + GRANT 段
2. **再写 Models + Service + Controller**（jOOQ 显式 SQL），照抄 `ExternalIntegrationService`
3. **再写测试**（`AutomationRuleIntegrationTest` + `AutomationRuleTenancyIntegrationTest`）
4. **再生成契约 + 前端工作区**
5. **每个阶段单独 commit**（分阶段 commit 是保命纪律 —— 基础设施已经掐了两次）

⚠️ **不要在写代码前把 17.4 / 18.3 相关的代码全部读一遍。** 需要看什么就 grep 什么。

## 六、本车道专属红线（蓝图标红，必须落成可执行判定 + 测试断言）

1. **执行统计可读**：最后一次执行的时间 / 结果 / 失败原因，**不是**一个布尔位
2. **「坏规则静默失败」有反面判据**：连续失败达阈值 ⇒ **可被识别**（失败计数 + 连续失败标记），
   而不是悄悄把状态改回「正常」
3. **试运行开关是独立状态**：开启试运行的规则**不产生真实副作用**，端点与页面都要能看出来
4. **启用范围（租户/店铺/仓库）是真实约束**：能被过滤条件实际用上，不是展示用字段
5. **优先级与排序真实生效**：相同优先级要有**确定**的次序，不能靠 DB 返回顺序
