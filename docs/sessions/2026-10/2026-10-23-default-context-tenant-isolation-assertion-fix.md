# 1.4.2.2 默认店铺与仓库 —— L2 全量红 → 共享租户下的断言隔离修正

- **日期**：2026-10-23（Asia/Shanghai）
- **车道**：`fix/1-4-2-2-legal-entity-assertion`（worktree `/Users/guan/Projects/starcon-worktrees/integration/.worktrees/1.4.2.2-fix`）
- **基线**：`integration/module-dev` @ `7811eb54`

## 决策摘要

**只改一个断言，不改业务代码。**

L2 批量验证在 identity 服务上红了一例：

```
DefaultContextIntegrationTest.未设过时返回stored为假且候选集来自上游
Expected size: 1 but was: 26 in: [{"id":"...","code":"AS0F64F5AB","name":"测试法人 AS0F64F5AB","status":"ACTIVE"}, ...]
```

### 根因（测试隔离问题，不是业务缺陷）

- identity 的集成测试**共用同一个 Testcontainers PostgreSQL 容器与同一个库**
  （`IdentityIntegrationTestBase` 里容器是 `static`、只启动一次）。
- `login()` 登录的是固定的 `local-admin` ⇒ **所有测试类拿到的是同一个租户的 token**。
- 各测试类创建的 `legal_entity` 行会**在同一个租户里累积**：全量跑完该租户里有 25 条，
  加上本测试自己建的那条 ⇒ **26 条**。
- 本车道自己的 L1 只跑 `-Dtest='DefaultContext*'` ⇒ 库里只有它自己那 1 条 ⇒ 当时是绿的。
  **是 L2 的全量跑把这个问题暴露出来的。**

⇒ `/identity/default-context/options` 返回「本租户全部法人」是**正确业务行为**
（`DefaultContextService.legalEntityDirectory` 的口径就是 `where e.tenant_id = ?::uuid`），
**不是缺陷**。

### 改了什么

把 `DefaultContextIntegrationTest` 里对 `legalEntities` 候选集的**数量断言**改成**范围断言**：

- 去掉 `hasSize(1)`（数量在共享租户下天然会变）；
- 去掉 `get(0)`（取「第 0 条」依赖顺序）；
- 改为**按 id 找到自己创建的那一条**，再断言它的 code。

与 `IdentityIntegrationTest` 对法人列表的既有 **contains 语义**一致（`assertThat(listed.toString()).contains(code)`）。

### 同文件其他数量断言的复核（逐一确认后**保持原样**）

| 位置 | 断言 | 复核结论 |
| --- | --- | --- |
| `options.get("shops")` | `hasSize(1)` | 来自本测试 stub 的上游 HTTP 响应（`stubShops`），天然隔离 ⇒ 保留 |
| `options.get("warehouses")` | `hasSize(1)` | 来自 `stubWarehouses`，天然隔离 ⇒ 保留 |
| `saved.get("stale")` | `isEmpty()` | 本成员自己那一行偏好 ⇒ 保留 |
| `view.get("stale")` | `hasSize(1)` | `stale` 范围是 tenant + membership + page_key（`DefaultContextService.resolve` 只对**本人偏好行**里的三个维度取值）⇒ 隔离 ⇒ 保留 |
| `options.get("legalEntities")`（无权限那例） | `isEmpty()` | 权限闸（缺 `identity.legal_entity.read` ⇒ `Directory.failed`），与租户里累积多少行无关 ⇒ 保留 |
| `capabilities.get("sources")` | `hasSize(3)` | 三维度固定契约 ⇒ 保留 |

## 文件清单

- `backend/services/identity/src/test/java/com/starcon/identity/DefaultContextIntegrationTest.java`
  —— 第 62–63 行（把 code 提成变量）、第 74–80 行（数量断言 → 按 id 定位的范围断言）、
  第 328–340 行（新增私有辅助 `byId`）

**未改动**：`DefaultContextService` / `DefaultContextController` / `DefaultContextRules`、
`DefaultContextTenancyIntegrationTest` 及其余任何测试类；未建表、未占 Flyway 版本号；
未动 `packages/api-contracts`；未动台账。

## 验证

- **L1（修复前，取证）**：`./mvnw -pl services/identity -am test` ⇒
  `Tests run: 515, Failures: 1, Errors: 0` —— 红在 `DefaultContextIntegrationTest.java:73`
  （`DefaultContextIntegrationTest` 12 例 / `DefaultContextTenancyIntegrationTest` 4 例，后者绿）
- **L1（修复后，本车道闸）**：`./mvnw -pl services/identity -am test` ⇒
  `Tests run: 515, Failures: 0, Errors: 0, Skipped: 0` —— **BUILD SUCCESS**
  - `DefaultContextIntegrationTest`：Tests run: 12, Failures: 0, Errors: 0, Skipped: 0
  - `DefaultContextTenancyIntegrationTest`：Tests run: 4, Failures: 0, Errors: 0, Skipped: 0
- **编译闸**：`./mvnw -q compile` ⇒ exit 0（只有 JVM 自身的 warning，无 error）

> 复现要点：这个问题**只在全量跑 identity 服务时出现**（别的测试类先跑、往共享租户里塞了法人行）。
> 只跑 `-Dtest='DefaultContext*'` 重现不出来，也不足以证明修好了。

## 后续待办

无。本车道只改测试断言，不涉及业务行为、迁移或契约。
