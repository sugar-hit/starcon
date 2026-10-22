# 2026-10-23: 16.1.2.2 申诉管理

## 摘要

交付 L3「16.1.2 风险事件」下的 **16.1.2.2 申诉管理**（channel 服务，Flyway `V310__channel_platform_appeal.sql`）。
新建 5 张 `platform_appeal_*` 表（申诉单 / 举证材料 / 提交记录 / 结果记录 / 申诉配额），
jOOQ 显式 SQL + 14 个端点 + `/compliance/appeals` 真实工作区 + 27 例测试（真实 PG16 Testcontainers）。

蓝图加粗句「**申诉配额有限，需记录已用次数并优先申诉高影响违规**」落成三道结构闸：
配额是真的表（`used_count <= quota_limit` 由 DB 层 CHECK 钉死）、
已用次数只能由提交动作递增（事务内标记辨认提交来源，连手动 `+1` 都不行）、
优先申诉高影响违规（影响档位**派生不落库**，按档位排序 + 独立的优先申诉队列）。

## 决策 / 结论

1. **配额建模成「店铺 + 周期」而不是「店铺总量」** —— 申诉配额在真实平台上按周期发放
   （每季度 N 次），总量配额会把「这个季度还能不能再提」这个问题问不出来。
   周期用 `timestamptz` 而不是 `date`：`timestamptz::date` 会随会话 `TimeZone` 变，周期边界会漂。
   未配置周期的配额默认**当前自然季度（UTC）**，响应里回报真实边界。

2. **「没配配额」≠「无限」** —— 未配置配额的店铺**提交会被拒**（422 / 23514），
   配额清单里这类店铺**也会出现**（`configured=false`）。「看不见」比「配了但用完了」更容易被忽略。
   配额清单与店铺范围清单都按这个口径。

3. **驳回允许回到草稿（DRAFT），再提交算第 2 次、消耗第 2 次配额** ——
   没有这条边，「申诉次数统计」和「申诉配额有限」就只是两句口号：
   一个申诉单一辈子只能提交一次，提交次数恒等于 1。
   状态机因此是 `DRAFT → SUBMITTED → APPROVED/REJECTED`，`REJECTED → DRAFT`，`DRAFT → WITHDRAWN`，
   通过与撤回是终态。**提交记录 / 结果记录都是 append-only** ⇒ 上一次的结果不会被抹掉。

4. **举证材料 / 提交记录 / 结果记录三样全部 append-only** ——
   「当时交了什么」是证据，只能靠**追加新记录**冲销，不靠改历史（照抄上游
   `platform_violation_evidence` 的 immutable + no_delete 两件套）。
   举证材料**只在草稿态收集**：提交后再往里加材料就是伪造「当时交了什么」。

5. **影响档位派生不落库，且与上游逐档位对拍** —— 上游 16.1.2.1 已把影响档位定义为
   「由处罚措施集合派生」，本模块再存一列就会与上游的措施表漂移。
   本模块用 DB 函数 `platform_appeal_impact_level()` 现算，
   集成测试把它的返回值与上游 Java 规则 `PlatformViolationRules.impactLevel()` **逐档位对拍**
   （含「尚无处罚措施」这一档）。前端的人话也直接复用上游的 `impactLabel`。

6. **上游 16.1.2.1 只读不写** —— 本模块读 `platform_violation` / `platform_penalty`
   （校验违规真实存在、**属于同一店铺**、**未关闭**，并按影响档位排队列），
   但**绝不写上游的任何一行**：上游的状态机（含 `APPEALING`）与列是它自己的写权限边界。
   「提交申诉要不要把违规改成 APPEALING」是上游的状态机语义，留给上游自己决定。

7. **契约类型前缀用 `ComplianceAppeal*` 而不是 `PlatformAppeal*`** ——
   `PlatformAppeal*` 已被 trade 服务的 11.4.1.1 平台申诉占用，而契约是**扁平命名空间**
   （所有服务的类型倒进同一份 `packages/api-contracts`），撞名会让 codegen **直接报冲突**、
   消费端拿到错误的那一份。所以本模块改用合规域前缀（与同服务 16.2.x 的 `CompliancePrecheck*` 同一套约定）。
   🔴 **但 DB 表前缀仍是 `platform_appeal_*`** —— 那是四个命名空间隔离的约定，不因契约改名而变。

8. **api-proxy 白名单纯新增一条独立 `services[]` 元素**，锚点固定在 `platform-violations` 那条之后；
   既有条目一字未动、也不重排（`platform-violations` 那条元素里
   「16.1.2.2 未交付 ⇒ 不放行 `platform-violations/appeals`」的注释与拒绝清单**原样保留** ——
   本模块走自己的 `platform-appeals` 前缀，两件事互不影响）。

9. **不碰上游模块的文件** —— 16.1.2.1 的能力声明里仍把「16.1.2.2 申诉管理」标成 `available=false`，
   现在这句已经不准确了，但那是**上游模块自己的**文件，本车道**不越界改**（见「后续待办」）。

## 关键 trade-off

- **配额用「周期」而不是「总量」**：多一张周期维度，也多一个「当前周期是哪一段」的判断；
  换来的是「这个季度还能不能再提」有确定答案，而不是一个越用越含糊的余额。
- **驳回回到草稿**：状态机多一条**往回走**的边（看起来不「干净」），
  换来的是提交次数与配额消耗变成**真实可数**的事实而不是恒等于 1 的摆设。
- **契约改名而不是写进碰撞白名单**：`tools/codegen/contract-collision-allowlist.json` 是**多车道共用**文件，
  写进去会让并入时多一个争用点；改名是纯本地改动，且顺带把模块归到合规域的既有约定下。
- **优先队列做成独立端点而不是只靠默认排序**：默认排序解决「我的申诉单按什么看」，
  独立队列解决「**该提哪一条**」—— 后者要跨违规看影响档位、还要看配额够不够，
  塞进申诉单清单就答不了这个问题。

## 涉及文件

**后端（channel 服务）**

- `backend/services/channel/src/main/resources/db/migration/V310__channel_platform_appeal.sql` —— 新建
- `backend/services/channel/src/main/java/com/starcon/channel/ComplianceAppealModels.java` —— 新建
- `backend/services/channel/src/main/java/com/starcon/channel/ComplianceAppealRules.java` —— 新建
- `backend/services/channel/src/main/java/com/starcon/channel/ComplianceAppealService.java` —— 新建
- `backend/services/channel/src/main/java/com/starcon/channel/ComplianceAppealController.java` —— 新建
- `backend/services/channel/src/test/java/com/starcon/channel/PlatformAppealIntegrationTest.java` —— 新建（18 例）
- `backend/services/channel/src/test/java/com/starcon/channel/PlatformAppealRulesTest.java` —— 新建（6 例）
- `backend/services/channel/src/test/java/com/starcon/channel/PlatformAppealTenancyIntegrationTest.java` —— 新建（3 例）

> 🔴 测试类名保留 `PlatformAppeal*` 前缀（生产类型已改名）：车道任务书指定的 L1 闸命令是
> `-Dtest='PlatformAppeal*'`，改测试类名会让那条命令抽到 **0 个用例**并假绿。

**前端**

- `apps/web/src/app/compliance/appeals/page.tsx` —— 新建
- `apps/web/src/components/compliance-appeals/compliance-appeals-model.ts` —— 新建
- `apps/web/src/components/compliance-appeals/compliance-appeals-workspace.tsx` —— 新建
- `apps/web/src/components/compliance-appeals/compliance-appeals-model.test.ts` —— 新建
- `apps/web/src/app/api/channel/platform-appeals-allowlist.test.ts` —— 新建
- `apps/web/src/lib/api-proxy.ts` —— **纯新增一条 `services[]` 元素**（既有条目一字未动）

**契约（codegen 产物，未手改）**

- `packages/api-contracts/src/contracts/channel.ts` —— 纯新增 25 个 `ComplianceAppeal*` DTO
- `packages/api-contracts/src/channel.openapi.json` —— 纯新增 13 path / 25 schema / 0 删除

**台账**

- `docs/product/capability-blueprint/module-progress.md` —— 只改 `| 16.1.2.2 |` 一行的「状态」「证据」两格

## 验证

- L0 编译闸：`./mvnw -q compile`（backend）+ `pnpm typecheck` —— 绿
- L1 模块闸：`./mvnw -pl services/channel -am test -Dtest='PlatformAppeal*' -Dsurefire.failIfNoSpecifiedTests=false -DfailIfNoSpecifiedTests=false`
  —— **27 例全绿**（18 集成 + 6 规则 + 3 租户隔离）
- 前端：`pnpm vitest run apps/web/src/components/compliance-appeals/compliance-appeals-model.test.ts apps/web/src/app/api/channel/platform-appeals-allowlist.test.ts` —— 16 例绿
- 全量 allowlist 回归（改了 api-proxy.ts 必须跑）：`pnpm vitest run apps/web/src/app/api` —— **185 文件 / 1068 例绿**
- 契约：`python3 tools/codegen/catalog-contracts.py --spec-dir backend/target/openapi` —— 无碰撞，
  channel 纯新增，其余五个服务的契约**逐字节不变**

## 后续待办

1. **16.1.2.1 的能力声明仍把「16.1.2.2 申诉管理」标成 `available=false`** —— 那句现在不准确了，
   但它是**上游模块自己的**文件，本车道按「只碰自己的文件」的纪律没有改。
   并入后建议由那一侧改成「已交付」并指向本模块的 `platform-appeals` 前缀。
2. **台账的总览 / 分阶段 / 分系统三处计数**在并入时要由调度者复跑
   `node tools/module-progress/sync.mjs` 重算（本车道按任务书要求**只改自己那一行**，
   刻意没动统计行，避免与同批其它车道在统计行上撞车）。
3. **平台申诉适配器未接入**：本模块的「提交」只落提交记录，**不会**真的把申诉送到平台。
   适配器接上后由那一侧来消费 `platform_appeal_submission`。
4. **「提交要不要把上游违规改成 APPEALING」** 待上游 16.1.2.1 自己决定 ——
   本模块刻意不写上游的任何一行。
