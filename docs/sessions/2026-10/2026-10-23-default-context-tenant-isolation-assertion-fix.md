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

---

# 第二轮：多租户断言按 membership 收窄

- **日期**：2026-10-23（Asia/Shanghai）
- **车道**：`fix/1-4-2-2-legal-entity-assertion`（同一 worktree、同一分支的追加 commit）
- **基线**：`integration/module-dev` @ `7811eb54`（本轮起点 = 上一轮 commit `2d909810`）

## 决策摘要

**同一批代码里另一个尚未爆的同类问题：只改断言，不改业务代码。**

第一轮修的是 `DefaultContextIntegrationTest` 里对法人候选集的**数量断言**（`hasSize(1)`）。
本轮处理**同一个批次的另一个文件** `DefaultContextTenancyIntegrationTest`：它有多条断言
**直接数「某个租户下 `page_key = 'default-context'` 的所有 `user_preference` 行」**。

### 根因（与第一轮同源）

- identity 的集成测试**共用同一个 Testcontainers PostgreSQL 库**
  （`IdentityIntegrationTestBase` 里容器是 `static`、只启动一次）。
- `login()` 登录的是固定的 `local-admin` ⇒ 父租户是**所有测试类共享**的租户。
- `user_preference` 的唯一键是 `UNIQUE(tenant_id, membership_id, page_key)`（V336 迁移），
  也就是说**同一个成员**的同一页永远是**一行**（upsert），
  但**同一租户里的不同成员**各有各的行。
- ⇒ 「这个租户里 `page_key=default-context` 的行有几条」**取决于这个租户里现在有哪些成员写过这一页**：
  别的测试类会往里写，本类自己的另一个用例（`otherMember` 造的同事）也会往里写。
  **写死行数 = 把测试类执行顺序写进断言里**。

### 改了什么

照抄**同批已交付的 1.4.2.1（语言与时区）**的做法
（`AccountLocaleTenancyIntegrationTest`）：**按 membership 收窄查询**，而不是数整个租户的行。

新增私有辅助（与 1.4.2.1 同名同义）：

```java
/** 当前会话的 membership_id（租户内的「这个人」）。 */
private UUID membershipOf(String token) throws Exception {
  UUID tenant = tenantOf(token);
  for (JsonNode membership : request(token, "GET", "/auth/memberships", null, 200)) {
    if (tenant.toString().equals(membership.get("tenantId").asText())) {
      return UUID.fromString(membership.get("id").asText());
    }
  }
  throw new IllegalStateException("会话里找不到当前租户的成员关系");
}

/** 在某个租户的事务里按 membership 查行（用来验证「读不到别人的行」）。 */
private List<Record> rowsIn(UUID tenant, UUID membership) {
  return rowsIn(
      tenant,
      "select id from user_preference where page_key = ? and membership_id = ?::uuid",
      DefaultContextRules.PAGE_KEY,
      membership);
}

/** 某个会话自己那一页的行。 */
private List<Record> rowsOf(String token) throws Exception {
  return rowsIn(tenantOf(token), membershipOf(token));
}
```

（另加 `payloadsOf(token)`：按 membership 取自己那一行的 `preference::text`，
用于「内容没被别人改掉」那条断言。）

### 逐条对照：收窄前数的是什么 → 收窄后断言的是什么

| 位置 | 改前 | 改后 | 语义 |
| --- | --- | --- | --- |
| 断言一 ~167 | 父租户 `hasSize(1)`（整租户计数） | `rowsOf(parent)` `hasSize(1)` | 「**我**（parent 这个成员）自己那一行在」⇒ **更强**：原来还隐含「租户里没有别人的行」 |
| 断言一 ~181 | 父租户 `hasSize(2)`（整租户计数） | `rowsOf(parent)` `hasSize(1)` **且** `rowsOf(colleague)` `hasSize(1)` | 原意图就是「parent 与 colleague **各自**有一行、互不覆盖」⇒ **更强**：不再被第三方成员的行使劲 |
| 断言一 ~188 | 子租户 `hasSize(1)`（整租户计数） | `rowsOf(child)` `hasSize(1)` | 「子租户写的行落在子租户、且是子租户成员自己的那一行」⇒ 更精确 |
| 断言一 ~154 | 子租户下按 page_key 查 `isEmpty()` | `rowsIn(tenantOf(child), parentMembership)` `isEmpty()` | 仍是「**子租户读不到父租户那一行**」⇒ **更强**：点名父租户**那一行**，不是泛泛的「零行」 |
| 断言二 ~249 | 父租户 `hasSize(1)`（整租户计数） | `rowsOf(parent)` `hasSize(1)` | 「伪造租户头的写仍然落在**父租户自己那一行**」⇒ 更精确（**上一轮报告点名的那颗雷**） |
| 断言二 ~250 | 子租户 `isEmpty()` | `rowsOf(child)` `isEmpty()` | 「伪造租户头没把行写进子租户」⇒ 收窄到子租户成员自己 |
| 断言三 ~283 | 父租户整租户行数 `== parentRowsBefore` | 保留（**基线比较**，不写死数字）+ 新增 `rowsIn(tenantOf(parent), parentMembership)` `hasSize(1)` | 整租户那条是**租户级**的「子租户的写没在父租户多出一行」，按 membership 收窄看不到别人的 membership ⇒ 保留基线比较（本身与执行顺序无关），并**补上**成员级断言 |
| 断言三 ~309 | 子租户整租户 `hasSize(1)` | `rowsIn(tenantOf(child), childMembership)` `hasSize(1)` | 「子租户的写落在子租户**自己的成员**那一行」⇒ 更精确 |
| 断言三 ~324 | 子租户整租户行数 `== childRowsBefore` | 保留（基线比较） | 同上，租户级语义 |
| 断言三 ~332 | 父租户整租户里 `anyMatch(pref 含有 parentShop)` | `payloadsOf(parent)` `anyMatch(...)` | 「**我那一行**的内容没被子租户改掉」⇒ 更精确（原来可能匹配到别人的行） |

**没有任何一条断言被改弱**：该非空仍非空、该为零仍为零、该互不覆盖仍断言。
唯一「保留原样」的整租户计数是**基线比较**（`== 用例开始时取的值`，不写死数字），
它在方法内取基线 ⇒ **本来就与执行顺序无关**。

## 文件清单

- `backend/services/identity/src/test/java/com/starcon/identity/DefaultContextTenancyIntegrationTest.java`
  —— 类 javadoc（新增「行数断言一律按 membership 收窄」一节）、新增 `membershipOf` / `rowsIn(tenant, membership)` /
  `rowsOf` / `payloadsOf` 四个辅助、三个测试方法里的断言按上表逐条收窄

**未改动**：`DefaultContextService` / `DefaultContextController` / `DefaultContextRules`（业务代码一律不动）；
`DefaultContextIntegrationTest`（上一轮已改完）、`IdentityIntegrationTest`、`AuditIntegrationTest`、
`PageFavorite*`、`AccountLocale*`、`ApprovalTodo*`（全部不动）；
未建表、未占 Flyway 版本号；未动 `packages/api-contracts`；**未动台账**
（1.4.2.2 那行在集成头里已被调度者改过，在本分支改同一行会造成并入冲突 —— 理由即本节）。

## 验证

- **L1（本车道闸，全量 identity）**：`./mvnw -pl services/identity -am test` ⇒
  `Tests run: 515, Failures: 0, Errors: 0, Skipped: 0` —— **BUILD SUCCESS**
  - `DefaultContextIntegrationTest`：Tests run: 12, Failures: 0, Errors: 0, Skipped: 0
  - `DefaultContextTenancyIntegrationTest`：Tests run: 4, Failures: 0, Errors: 0, Skipped: 0
- **编译闸**：`./mvnw -q compile` ⇒ exit 0（只有 JVM 自身的 warning，无 error）
- **顺序无关性专项取证**（临时实验，**未进 commit**）：
  给该测试类临时加 `@TestMethodOrder(MethodOrderer.MethodName.class)`，
  把「造同事并给他存一份」的那个用例排到最前面 ⇒ 父租户里**已经有别人的 `default-context` 行**：
  - **改前**（`git show HEAD:` 的旧文件 + 同样的顺序）：**红** ——
    `DefaultContextTenancyIntegrationTest.伪造租户头无效` ⇒
    `Expected size: 1 but was: 2`（正是上一轮报告点名的那条断言），
    `Tests run: 4, Failures: 1, Errors: 0` ⇒ **这颗雷是真的**；
  - **改后**（新文件 + 同样的顺序）：**绿** —— `Tests run: 4, Failures: 0, Errors: 0` ⇒ BUILD SUCCESS。
  - 实验结束后已把文件**逐字节还原**（`diff` 与实验前的副本一致，`git diff --stat` 只剩本轮改动）。

