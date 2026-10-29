# identity L2 红点：38 例 403 的根因与修复（2026-10-28）

## 结论摘要

L2 批次闸里 identity 全套 639 例中 38 例 403，根因不是「跨测试状态污染」，而是
**授权生效时间与授权判定不在同一个钟域**：

- **写入侧**（`AdministrationController.assignments()`，建成员时落授权）把
  `role_assignment.effective_from` 写成 `OffsetDateTime.now()` —— **应用服务器的墙钟**；
- **判定侧**（`V47__member_role_assignment.sql` 的 `session_access()`）用
  `ra.effective_from <= now()` —— **数据库自己的时钟**，而 PostgreSQL 的 `now()` 是
  `transaction_timestamp()`，即**当前事务的开始时刻**。

两端分属两个钟域，于是「刚写好的授权」可能被判定成**尚未生效**，`session_access()`
返回的 `grants` 里就没有 `identity.approval_inbox.read`，于是请求落到
`require(..., "当前角色没有此操作权限")` 的 403 分支。

**修法**：写入侧改由数据库自己的 `now()` 给出（与判定侧同钟域）。列默认值本来就是
`now()`（`LocalBootstrap` 与 owner-transfer 两条路径都吃这个默认值），本次只是让
建成员这条路径回到同一个钟域。

---

## 一、取证链（每一条都是实测）

### 1. 红绿两次是同一份代码、同一套类顺序

| | 红 | 绿 |
| --- | --- | --- |
| 日志 | `.tmp/l2-batch108.log`（集成头，全量 `verify`） | `.tmp/identity-full.log`（本车道，`-pl services/identity -am test`） |
| 用例数 | 639 | 639 |
| 测试类 | 53 个，顺序**逐字相同** | 同上（`diff` 无差异） |
| identity 模块耗时 | **29 分 10 秒** | **2 分 58 秒** |

- 全量 `verify` 的 reactor 里 identity 是 **[3/11]**（`backend` → `tenancy` → `identity`），
  identity 失败后其余 8 个模块全部 `SKIPPED` ⇒ **红跑时 identity 前面也没有别的服务在跑**，
  「谁在它之前污染了共享状态」这个方向被排除。
- `verify` 与 `test` 在 Maven 生命周期里**跑的是同一个 `test` 阶段**，`verify` 多出来的
  `package`/`failsafe`/`jacoco` 全在测试**之后** ⇒ 不可能影响 identity 的测试结果。
- 编译产物逐字节相同（两处 `target/test-classes` 的 `md5` 一致）。

⇒ **红不是确定性的，且与「谁先跑」无关。**

### 2. 403 的语义被定位到唯一产生点

`ApprovalTodoController.call()`：

```java
require(a.grants().stream()
        .anyMatch(g -> g.scopeMode().equals("ALL") && g.permissions().contains(INBOX_READ)),
    "当前角色没有此操作权限");
```

⇒ 403 = **`session_access()` 解析出来的 grants 里没有 `scopeMode=ALL` 的 `INBOX_READ`**。
不是超时、不是连接失败。

### 3. 36 个 403 全部落在「API 建的成员」身上，一个都没落在 `local-admin` 身上

把 batch108 日志里每个 403 的 `Authorization` token 逐一抽出来比对（`local-admin`
每次 `login()` 都是新 token，但它在日志里是可直接定位的）：

- 36 个 403 → **36 个互不相同的成员 token**，全部是 `POST /identity/members` 建出来的；
- `local-admin`（OWNER）**一次都没 403**。

**为什么只有 API 建的成员会中招**：`local-admin` 的授权是 `LocalBootstrap` 写的，
那条 INSERT **没有带 `effective_from`**，吃的是列默认值 `now()`（数据库钟，且是**很久以前**的值）
⇒ 任何 `now()` 都 ≥ 它 ⇒ 永远可见。
而 API 建的成员走 `AdministrationController.assignments()`，那里**显式**写了
`OffsetDateTime.now()`（应用服务器墙钟）⇒ 才会被判未生效。

### 4. 两个触发条件都真实存在（实测）

**① 钟差**：`ClockSkewProbe`（临时探针，量完即删）实测 JVM 墙钟与 PG 容器钟：

```
SKEWPROBE SUMMARY min=-1ms max=16ms avg=0ms   (正数 = JVM 钟比 PG 钟快)
```

空闲时只有 ~1ms；但**宿主机与 Docker 虚拟机在满载/挂起恢复后的钟差正是这个量级**
（`32a16a6a` 的 commit message 已经写过这件事：「高负载下容器与宿主的时钟偏差更容易出现」）。

**② 判定事务先于写入事务开始**（本次红点的**主因**，与机器负载直接相关）：

本服务按租户用 `pg_advisory_xact_lock(hashtextextended(tenantId,0))` **串行化请求**
（全仓 12 处调用点）。锁被占住时，请求会**排队**：
- 事务在请求**到达**时就开始了 ⇒ `now()`（= `transaction_timestamp()`）取的是**到达时刻**；
- 但语句真正执行是在**排队之后**（红跑里排了 522s / 903s）。

于是只要「建成员」的请求排在「授权判定」的请求**后面**（先到达、晚执行），
判定事务的 `now()` 就**早于**授权真正落库的时刻 ⇒ 授权被判未生效 ⇒ 403。

**⇒ 这同时解释了 29 分钟的耗时、522s/903s 的排队，以及「只有 API 建的成员 403」。**

### 5. 确定性复现（不依赖负载）

把「建成员（写授权）」与随后的「授权判定」放进**同一个事务**，
判定用的 `now()` 就必然早于写入时刻 —— 这就把上面的场景②**确定化**了：

```
# 修复前（旧代码）
[ERROR] Tests run: 2, Failures: 1, Errors: 0
[ERROR] ...授权判定早于授权写入时新授权同样生效
[ERROR]   to contain: identity.approval_inbox.read      ← session_access() 返回的 grants 里没有它

# 修复后
[INFO] Tests run: 2, Failures: 0, Errors: 0, Skipped: 0
```

⇒ **旧代码必红、修复后必绿，是确定性的，不需要靠负载去撞。**

---

## 二、改了什么

### 生产代码（1 个文件，+19/-4）

`backend/services/identity/src/main/java/com/starcon/identity/AdministrationController.java`
（`assignments()`，建成员时落授权的那条 INSERT）：

```java
// 改前：写入侧取应用服务器墙钟
"insert into role_assignment(tenant_id,membership_id,role_id,effective_from,scope_mode,assigned_by)"
    + " values(?,?,?,?::timestamptz,?,?)",
    a.tenantId(), id, input.roleId(), OffsetDateTime.now(), input.scopeMode(), a.userId());

// 改后：写入侧取数据库自己的钟（与判定侧同钟域）
"insert into role_assignment(tenant_id,membership_id,role_id,effective_from,scope_mode,assigned_by)"
    + " values(?,?,?,now(),?,?)",
    a.tenantId(), id, input.roleId(), input.scopeMode(), a.userId());
```

**为什么这是生产缺陷而不是测试夹具问题**：写入侧与判定侧本来就应该在同一个钟域里比较
——判定由数据库做，「现在几点」就是数据库的时间；写入侧却拿应用服务器的墙钟去回答
「从什么时候起生效」，于是「生效时间」这个值取决于**应用服务器与数据库之间的钟差**，
在生产部署里（应用服务器与数据库不同机）同样会咬人。列默认值本来就是 `now()`，
另外两条写授权的路径（`LocalBootstrap`、owner-transfer）也都吃这个默认值，
**只有建成员这条路径是异类**。

**没有改业务语义**：「这条授权从现在起生效」仍然是原意，只是「现在」改由数据库回答。

### 同族 sweep（按调度者要求：写入侧用应用墙钟、读取侧用 DB 钟的点）

**判据**（很关键，决定改哪些）：只有「**应用自己造出来的『此刻』**」才需要同源取时。
**调用方明确给出的绝对时刻**（生效窗口、查询区间）按绝对时刻比较是对的，不属于这一类。

| 时间列 | 写入侧 | 读取侧 | 结论 |
| --- | --- | --- | --- |
| `role_assignment.effective_from`（建成员） | 应用墙钟 | `session_access()` 的 `now()` | ✅ **已修**（38×403 的成因） |
| `department_leader_proxy.starts_at` | 应用墙钟 | `department_approval_chain` 的 `coalesce(as_of, now())`（**授权判定**） | ✅ **已修** |
| `approval_instance.submitted_at` / `node_entered_at` / `finished_at` | 应用墙钟 | `approval_dwell_seconds(now(), …)`、`node_entered_at <= now() - N*interval`（**有效期判定**） | ✅ **已修**（三个必须一起改：它们互减、且与 `now()` 减） |
| `approval_instance_action.decided_at` | 应用墙钟 | `approval_dwell_seconds(a.decided_at, coalesce(prev.decided_at, i.node_entered_at))`（`ApprovalRecordController` 的 avg_dwell / avg_handle / avg_action） | ✅ **已修**（**这条是被前面那条逼出来的**：一旦 `node_entered_at` 改成 DB 钟，`decided_at` 还留在应用钟就会**新造**一处跨钟域相减 ⇒ 必须一起改） |
| `document_number_rule.effective_from`（**调用方未给时的默认值**） | 应用墙钟（`normalize()` 里的 `utcNow()`） | `r.effective_from<=now()`（**规则是否生效**） | ✅ **已修**（调用方**明确给了**时刻的照旧走业务契约） |
| `role_assignment.effective_from` / `effective_until`（`RoleAssignmentController`） | **调用方给定的业务时刻** | `now()` | ❌ 不动：业务契约 |
| `department_leader_grant.starts_at` / `ends_at` | **调用方给定** | `coalesce(as_of, now())` | ❌ 不动：业务契约 |
| `tenant_hierarchy_grant.valid_from` / `valid_until` | **调用方给定**（`create_hierarchy_grant` 的入参，整条写入都在 SQL 里） | `hierarchy_grants` 的 `g.valid_from>now()` | ❌ 不动：业务契约 |
| `role_assignment.revoked_at` | 应用墙钟 | **无时间比较**（判定看 `status='ACTIVE'`，CHECK 只看是否为 NULL） | ❌ 不动 |
| `external_push_delivery.next_attempt_at` | 应用墙钟 | **同一个 `now` 变量**（应用钟） | ❌ 不动：两端同源 |
| `approval_instance.submitted_at` 的查询过滤参数 | 调用方给定 | 调用方给定 | ❌ 不动 |
| `HierarchyAuthorizationController.effective()` 里的「此刻」 | 应用墙钟 | 与**调用方给定的有效期**比较，不与 DB 的 `now()` 比较 | ❌ 不动（按判据不成立） |

**顺带的 schema 佐证**：`V160` 里 `approval_instance.submitted_at` / `node_entered_at`、
`V44` 的 `department_leader_proxy.starts_at`、`V160` 的 `decided_at`、以及
`V47` 的 `role_assignment.effective_from`，列默认值**全都是 `now()`** ——
**schema 的意图本来就是「此刻」取数据库的钟，是应用擅自用墙钟覆盖了它。**

### 生产代码改动清单（3 个文件，+51/-7）

| 文件 | 改动 |
| --- | --- |
| `AdministrationController.java` | ① `assignments()`：`effective_from` 改写 `now()`（去掉 `OffsetDateTime.now()` 绑定）。② 部门负责人代管：`startsAt = AuditLog.dbNow(sql)`。 |
| `ApprovalInboxController.java` | 审批实例的 `submitted_at` / `node_entered_at` / `finished_at` 改用 `AuditLog.dbNow(sql)`；审批动作的 `decided_at`（含转交那条）同样改用 DB 钟。 |
| `NumberRuleController.java` | `normalize()`：调用方未给生效起点时，默认值改用 `AuditLog.dbNow(sql)`（原为应用墙钟 `utcNow()`）。 |
| `AuditLog.java` | 新增静态 `dbNow(DSLContext)`：取数据库自己的时钟（`select now()`）并归一到 UTC。 |

**为什么这次动的是生产代码（评审必问）**：`32a16a6a` 改的是**测试夹具造错数据**
（夹具把 `node_entered_at` 写成宿主钟，而服务端在 DB 侧算）—— 那是夹具的问题。
这次不同：**生产写入侧与生产判定侧本来就该同源** —— 判定由数据库做（「现在几点」就是
数据库的时间），写入侧却拿应用服务器的墙钟去回答「从什么时候起生效 / 什么时候进入节点」。
这不是「为迁就测试改业务语义」，而是把「此刻」还原成数据库的「此刻」；业务语义
（「从现在起生效」「这一刻进入节点」「这一刻做出决定」）一个字没变。
反过来说：测试夹具（`32a16a6a` 已改成 DB 钟）与生产（原先是应用钟）本来就是**错开**的，
本次让两者重新一致。

### 测试（新增 1 个文件，4 条用例）

`backend/services/identity/src/test/java/com/starcon/identity/RoleAssignmentEffectiveFromClockDomainIntegrationTest.java`

| 用例 | 作用 |
| --- | --- |
| `新建成员的授权立即生效且生效时间取自数据库时钟` | **钟域不变量守卫**：落库的 `effective_from` 必须落在「建成员请求前后的两次**数据库时钟**读数」之间（**故意不留容差** —— 端点同钟域，容差只会把「写入侧取错钟」盖住）；并断言该成员**一个等待窗口都不给**就能用。 |
| `授权判定早于授权写入时新授权同样生效` | **确定性复现器**：把写入与判定放进同一事务，断言 `session_access()` 仍认这条授权。旧代码必红、修复后必绿。 |
| `审批实例的提交时刻与进入节点时刻同样取自数据库时钟` | 钉住 `submitted_at` / `node_entered_at` 的钟域（它们与 DB 侧的停留时长/超时判定相减），并顺带钉住 `decided_at`（审批动作的决定时刻，也与 `node_entered_at` 相减）。 |
| `部门负责人代管的生效起点同样取自数据库时钟` | 钉住 `department_leader_proxy.starts_at` 的钟域（它进 `department_approval_chain` 的授权判定）。 |
| `编号规则的默认生效起点同样取自数据库时钟` | 钉住 `document_number_rule.effective_from` 在**调用方未给**时的默认值取自 DB 钟（判据 `r.effective_from<=now()` 决定规则是否生效）。 |

---

## 三、验证

### 断言牙齿（改坏锚点必须红，改回必须绿）

| 动作 | 结果 |
| --- | --- |
| 旧代码（`OffsetDateTime.now()`）跑新用例 | `Tests run: 2, Failures: 1` —— 复现器红 |
| 修复后（`now()`）跑新用例 | `Tests run: 2, Failures: 0` —— 绿 |
| 故意注入钟差（`now() + interval '5 minutes'`，模拟 batch108 的条件） | `Tests run: 2, Failures: 2` —— **两条都红**（不变量守卫也有牙齿） |
| 改回（`now()`） | `Tests run: 2, Failures: 0` —— 绿 |
| **最终版**：往 5 个写入点全部注入 +5min 故意钟差 | `Tests run: 5, Failures: 5` —— **五条全红** |
| **最终版**：改回 | `Tests run: 5, Failures: 0` —— 全绿 |

### 全套（最终代码）

```
./mvnw -pl services/identity -am test   # 第 1 次
[INFO] Tests run: 644, Failures: 0, Errors: 0, Skipped: 0     BUILD SUCCESS   Total time: 02:56 min

./mvnw -pl services/identity -am test   # 第 2 次
[INFO] Tests run: 644, Failures: 0, Errors: 0, Skipped: 0     BUILD SUCCESS   Total time: 02:53 min

./mvnw -pl services/identity -am verify
[INFO] Tests run: 644, Failures: 0, Errors: 0, Skipped: 0     BUILD SUCCESS   Total time: 02:57 min
```

（644 = 原 639 + 新增 5 条回归用例。耗时稳定在 3 分钟量级，
对比 batch108 的 **29 分 10 秒** —— 那个 26 分钟是请求在 advisory lock 上排队。）

### 租户隔离三条断言

13 个 `*TenancyIntegrationTest` 在两次全套跑里逐类全绿：

```
ApprovalInboxTenancy 5/5 · ShortcutTemplateTenancy 5/5 · ApprovalRecordTenancy 4/4 ·
AccountLocaleTenancy 4/4 · ExternalChannelPushTenancy 6/6 · NotificationInboxTenancy 4/4 ·
AlertRuleTenancy 6/6 · WorkbenchLayoutTenancy 4/4 · ApprovalTodoTenancy 4/4 ·
UserPreferenceTenancy 4/4 · DefaultContextTenancy 4/4 · PageFavoriteTenancy 4/4 ·
NotificationPreferenceTenancy 4/4
```

即「两租户互不可见 / 伪造 tenant header 无效 / 连接池归还后不串租户」三条仍然成立 ——
**本次没有放松任何权限判据**：改动只把「此刻」换一个钟域，判据本身（`effective_from<=now()`）
一个字未动。

### L0 编译闸

- `backend`: `./mvnw -q compile` ⇒ EXIT=0
- 前端：`pnpm typecheck` ⇒ `3 successful, 3 total`（本次改动纯后端，前端契约零变更）

---

## 四、顺带记一笔：新回归用例自己踩了「共享状态」

新用例第一次跑全套时红过一次（`409`），**原因恰好就是本任务书一开始怀疑的那种形状**：
容器与 LOCAL 租户是所有测试类共享的，而**一个单据类型在同租户下只能有一条生效流程**
（`ApprovalFlowController` 的 409 + `approval_flow_active_document` 部分唯一索引）。
别的类留下的生效流程 ⇒ 本类建流程 409 ⇒ 提交实例连带失败。

⇒ 修法：新用例**注册一个每次唯一的单据类型**（`approval_document_type` 是全局目录表，
无 tenant_id、无 RLS，且没有任何测试枚举它），流程与实例都用它 ⇒
**既不依赖别的类的数据，也不会和谁撞**。

⇒ **教训**：在这个仓库里写 identity 的集成测试，**凡是用到「单据类型 / 实体 / 角色编码」
这类共享目录键，必须每次唯一**；用 `suffix()`（随机 8 位十六进制）拼出来，
否则顺序一变就会互撞。这也解释了「同一个测试类单独跑绿、放进全套就红」的另一类形状 ——
**与本次 403 红点无关，但同属「跨类共享状态」这一族，值得记住。**

---

## 五、后续待办 / 建议

1. **L2 全量验证前先单独确认一遍 identity**（`./mvnw -pl services/identity -am verify`）。
   这次的红点虽然已修，但它暴露出的**根因（授权判定的 `now()` 取事务开始时刻）**
   在别的服务里可能还有同形问题，值得顺手扫一遍 `session_access` 这类判据。
2. `RoleAssignmentController` 那条「调用方给定生效窗口」的路径，仍然是「调用方的钟」
   对「数据库的钟」。业务时间由调用方给定是可以接受的语义，但如果将来要支持
   「立即生效/立即失效」这类语义，建议也走数据库的钟。
