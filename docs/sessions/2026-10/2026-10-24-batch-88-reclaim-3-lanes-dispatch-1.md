# 2026-10-24 · batch-88 —— 回收 3 条车道并入集成头 + 派发 1 条新车道

> 批次号 **88**（承接 `batch-87`；取号时 `ls docs/sessions/2026-10/ | grep -oE 'batch-[0-9]+' | sort -t- -k2 -n | tail -1` ⇒ 最大为 `batch-87`）。
> 集成头 `integration/module-dev`；本批并入 3 条车道：**1.5.1.2 / 12.3.2.1 / 1.3.1.2**。
> 调度者：Mavis（pipeline cron tick）。

## 本批概览

| 项 | 值 |
| --- | --- |
| 回收并入 | **3 条**（1.5.1.2 数据一致性体检、12.3.2.1 对账任务、1.3.1.2 通知偏好）—— `merge-ready` 全部成功，**零冲突** |
| 新迁移 | `V370__finance_reconciliation_task.sql`（finance）；1.5.1.2 与 1.3.1.2 **零迁移**（V375 / V374 预分配但不占用） |
| 后端服务 | finance（12.3.2.1）· identity（1.3.1.2）· 纯前端聚合层（1.5.1.2，**后端零改动**） |
| 台账 | 395 → **398 已交付**，未开发 70 → **67**，465 行完整性校验通过、无重复 ID |
| 静态预检 | ①`ADD COLUMN` 无 DEFAULT、②`SET NOT NULL`、③`ADD CONSTRAINT CHECK` —— **三项均零命中**（V370 全部是 CREATE TABLE，**零 ALTER 任何既有表**） |
| L2 批次闸 | **第 1 轮红（经取证确认的假红）→ 第 2 轮 GREEN**：12 个退出码全 0；后端 **666 类 / 10,428 例**、前端 **622 文件 / 8,626 例**全绿，Next build + sync --check + merge-union itest + codegen 单测 + 契约闸 + 强制 typecheck 全 0（20:30:18 → 21:32:11，61 分 53 秒） |
| 派发 | **1 条**新车道（`--lanes 4` 但编排器只排得出 1 条，结构性原因见「排不出 4 条车道」一节） |
| 本轮额外动作 | 清掉一个**阻塞并入通道**的未跟踪文件（batch-86 遗留的 backlog 文档）—— 见「并入通道被未跟踪文件堵住」一节 |

---

## 模块 12.3.2.1 对账任务

**这个功能做什么**：发起并执行一次对账任务——选对账范围（账户 + 店铺 + 周期），
系统自动把平台账单和我方账（应收 / 付款单 / 银行流水）配平，输出**匹配结果**与**差异清单**。
蓝图那句加粗的「**不允许『金额相同即匹配』**」被钉在三层：候选生成不看金额、
DB 层 CHECK 要求至少两个维度对上、只对上金额的那两行落成差异而不是匹配。

**技术落地**：

- 迁移：`backend/services/finance/src/main/resources/db/migration/V370__finance_reconciliation_task.sql`
  — **四张新表**：`finance_reconciliation_task`（对账任务，范围与匹配参数被触发器冻结）·
  `finance_reconciliation_match`（匹配结果，**append-only**）· `finance_reconciliation_difference`（差异清单）·
  `finance_reconciliation_event`（事件流，append-only）。
  四表均 `tenant_id NOT NULL` + `ENABLE` **与** `FORCE` RLS + GRANT（`finance_app` / `starcon_app`）；
  **零 ALTER 任何既有表、零跨表 FOREIGN KEY**（`finance_reconciliation_difference` → `task` 的 FK 是同库同租户复合键，属表内引用）。
  8 个 BEFORE INSERT/UPDATE 触发器承担业务硬约束：差异字段不可改写（只有 `status` 能向前推进）、
  跨币种差额必须为 NULL（不按 1:1 兜底）、匹配表 append-only、事件流 append-only。
- 端点：**10 个**，挂 `/finance/reconciliation-tasks`：
  `GET /capabilities`（能力声明）· `GET /options`（筛选项）· `GET`/`POST`（列表 / 建任务）·
  `GET`/`PUT` `/{id}`（详情 / 改对账范围）· `POST /{id}/match`（跑自动匹配）·
  `GET /{id}/matches` · `GET /{id}/differences` · `GET /{id}/events`。
- 页面：`/finance/reconciliation-tasks` —— 任务列表 + 范围选择 + 匹配结果 + 差异清单（**按币种分组**）+ 事件流；
  筛选维度含账户 / 店铺 / 周期 / 币种；错误态 / 空态 / 加载态三态齐全。
- 测试：`ReconciliationTaskIntegrationTest` 17 例 + `ReconciliationTaskTenancyIntegrationTest` 10 例
  （**L1 实跑 27 例全绿**，Testcontainers PostgreSQL 16，连接池压到 1）；
  前端 `reconciliation-task-model.test.ts` 25 例 + `reconciliation-tasks-allowlist.test.ts` 6 例；
  改了共享白名单 ⇒ 全量 allowlist 回归 **148 例全绿**。
- 契约：`packages/api-contracts/src/contracts/finance.ts` 新增 **20 个 `FinanceRecon*` 类型**、
  **8 条 path**，既有 616 个 schema 内容变化 0，其余六个服务产物零改动。

**关键设计决策**：

1. **「不允许金额相同即匹配」钉在三层**（候选生成 / DB CHECK / 可观测证据）。
   取舍：否决了「金额容差 + 宽窗口」—— 那只是把「金额相同即匹配」换个马甲。
   容差字段保留但**默认 0**，日期窗口由服务层钉死 **1 ~ 31 天**。
2. **两侧划分：结算归平台侧，不归我方侧**。蓝图输入句把「结算」写进「我方账」，
   但结算单就是平台账单 —— 那样等于让同一份平台文件的两份投影互相对撞。
   ⇒ 平台账单侧 = 结算单头 + 结算明细行；我方账侧 = 应收明细行 / 付款单明细行 / 银行流水行。
   受控词表因此只有三个值：`RECEIVABLE` / `PAYMENT_ORDER` / `BANK_STATEMENT`。
3. **读 12.7.1.2 的银行流水走授权只读 API，不走跨库 SQL**（`BankReconciliationPort`，转发调用方 bearer）。
   实测踩坑：`UriComponentsBuilder.path()` 把 `http://` 压成 `http:/`；
   「自己 HTTP 调自己」会在本事务里再占一条连接 ⇒ 连接池只有 1 条时**直接死锁**
   ⇒ `match` 端点拆成三步（短事务读范围 → 事务外取流水 → 事务内落库）。
4. **执行结果是一次性事实 ⇒ 重跑 = 新建一个任务**。已 MATCHED 的任务再跑匹配 ⇒ 409。
   取舍：牺牲「一键重算」的便利，换来「历史对账证据不被改写」。
5. **跨币种绝不相加；汇率不可得就不给数字**（差额恒为 NULL，DB CHECK 钉死），页面显示「—（汇率不可得，不按 1:1 兜底）」而不是 0。
6. **权限码沿用 12.7.1.2 的 `finance.receivable.read / .update`**，不新造词表 ——
   新造一档要改 identity 的迁移（那是别的服务的写入所有权）。
7. **差异清单只输出清单，不提供任何处理动作** —— 认领 / 裁决 / 核销属 **12.3.2.2**（本批未交付），
   api-proxy 也**刻意不放行** `{id}/differences/{id}` / `resolve` / `acknowledge` / `write-off`。
8. **不建规则管理表**（归 12.3.2.3）：匹配参数 = 代码内受控词表 + 接口入参，随任务快照。

---

## 模块 1.3.1.2 通知偏好

**这个功能做什么**：设置「哪些通知走哪些渠道、什么时段别吵我」。
关键业务通知（超期发货、账务差异）**不允许被完全关闭** —— 系统强制保留下不可关闭的底线项。

**技术落地**：

- 迁移：**无**。蓝图「关系」字段逐字写着「共享数据：`user_preference`」⇒ 复用 1.4.1.2 建好的
  `user_preference`（V336），用本模块自己的 `page_key = notifications.delivery`。
  **本模块不建表、不 ALTER** ⇒ 预分配的 **V374 不占用**。
- 端点：**5 个**，挂 `/identity/notification-preferences`：
  `GET`（读生效值）· `PUT`（保存）· `DELETE`（重置）· `GET /capabilities`（能力声明）· `POST /resolve`（解析某类通知现在走哪些渠道）。
- 页面：`/notifications/preferences` —— 概览 + 渠道矩阵 + 免打扰时段编辑 + 底线项清单；
  错误态 / 空态 / 加载态三态齐全。**另起一条路由**，兄弟模块 1.3.1.1 的文件一字未改。
- 测试：`NotificationPreferenceRulesTest` 24 例 + `NotificationPreferenceIntegrationTest` 20 例 +
  `NotificationPreferenceTenancyIntegrationTest` 4 例（**L1 实跑 48 例全绿**）；
  前端模型 24 例 + allowlist 5 例；改了共享白名单 ⇒ 全量 allowlist 回归 **1369 例全绿**。
- 契约：`packages/api-contracts/src/contracts/identity.ts` 更新，其余六个服务产物零改动。

**关键设计决策**：

1. **落成结构 = 服务端校验 + 明确的不可关闭清单**，不是前端把复选框置灰。三层：
   写入层（缺底线渠道 ⇒ 400）· 读取层（绕过本服务写坏载荷时底线渠道仍在，并记 `fallbackReasons`）·
   能力声明层（逐条给出 `mandatory` + `baselineChannel` + 人话规则）。
2. **底线渠道选「站内」而不是邮件 / 企业 IM**：站内是唯一不依赖外部通道的渠道，
   而邮件与企业 IM 属 1.3.2.2（未交付）—— 把底线挂在外部渠道上，1.3.2.2 没交付时关键通知就没有底线了。
3. **免打扰时段存「当地时间 + IANA 时区」，不存 `timestamptz`**：它记的是每天重复的墙上时间，
   存 UTC 会在跨时区用户身上差几小时、夏令时切换那天再差一小时。
   换算方向是「**当下 → 当地时刻**」，**不涉及任何 UTC 偏移** ⇒ `Timestamp.valueOf(LocalDateTime)` 那一类静默 −28800s 的错误在本模块根本不会出现。
4. **时区按用户自己的时区算**（继承 1.4.2.1，显式 > 继承 > 平台默认），来源随响应下发（`timezoneSource`）。
5. **免打扰只压制外部渠道**，站内照常进收件箱；口径写进能力声明（`quietHoursScope`），不留隐含行为。
6. **契约里没有裸 Map** —— `Map<String, List<String>>` 经 springdoc 只生成空的 `{}`，前端拿到 `unknown` ⇒ 改用列表桶。

---

## 模块 1.5.1.2 数据一致性体检

**这个功能做什么**：一体检页，回答「我的数据现在自洽吗」—— 库存账实核对、库存准确率红线判定、
渠道映射缺失队列三项可读；12.3.2 对账差异与三方金额差异两项**如实报不可得**，**不显示成 0、也不显示成健康**。

**技术落地**：

- 迁移：**无**（V375 预分配但**主动不占用**）。落点是**纯前端聚合层**：五类输入逐条落在归属服务既有的只读端点上，
  派生快照表只会比它描述的事实更早过期 ⇒ **没有建空迁移占位**。
- 端点：**零新增**。复用 4 个上游只读端点（`07 库存账实核对` / `07 库存健康闸门` / `02 映射缺失队列` / `findings`）
  + `auth/session`；**api-proxy.ts 一字未改（k = 0）**，4 个上游端点全部已在基线白名单里放行，
  本车道用**真跑代理逐条实测命中**（不是目测、不是 `grep -c`）。
- 页面：`/system-health/consistency` —— 整体结论 + 结算与对账闸门 + 五个分项卡片 + 异常清单 + 差异明细表 + 可疑区间 + 能力声明。
- 测试：`consistency-check-model.test.ts` **37 例** + `consistency-check-allowlist.test.ts` **8 例**；
  未改 api-proxy，仍跑了全量 allowlist 回归（243 文件 / 1372 例全绿）作为自证。
- 契约：**零改动**（后端零改动 ⇒ 契约零漂移）。

**关键设计决策**：

1. **落点 = 纯前端聚合层（不是只读投影层）**：三类可读输入分属 warehouse + channel 两个服务，
   做成后端投影层就要新增跨服务 HTTP 端口 + 转发 Authorization + 新权限码 ——
   而蓝图卷首明说「**不新增业务服务**」。同卷 1.1.3.2 已是同一形态。
2. **「低于阈值应阻断结算与对账出结论」= 暴露判定，不执行阻断**：
   归属服务 07 库存（7.6.2.1）已把它做成可查询、带证据的判定，本模块原样透传并给处置入口，
   **不代** 12 财务结算与 7.1.2.2 对账去扣单。
3. **「判不了」是一档独立状态**（`UNDETERMINED`），不是第四种颜色。
   **整体结论只要有一项判不了就不能报「通过」** —— 所以本仓今天整体结论**不可能是绿**，单测专门锁住这条。
4. **三方金额差异判不了是结构事实，不是「懒得做」**：
   履约侧（`warehouse_outbound_order*`）**只有数量，无金额列、无币种列**；
   订单与结算**没有外键**、**没有可读汇率** ⇒ 报「不可比」并把三条取证写进证据区。
   **绝不**退化成「订单总额 − 结算总额」（那是跨币种相减或 1:1 兜底，加出来的「差异」是假的）。
5. **不拿 12.7.1.2 银行对账顶替 12.3.2**：两者是**两处不同口径的事实**（银行流水 vs 我方账 / 平台账单 vs 结算单）。
6. **映射缺口的 7 天阈值是本模块的默认政策**（页面上明写），不是平台下发的口径；
   并且诚实地标注「待处理数是下限」（09 订单的缺映射事件还没接线）。

---

## 静态预检

对本批唯一的新迁移（V370）跑「已知互相破坏形状」三项扫描：

| 形状 | 结果 |
| --- | --- |
| ① `ADD COLUMN` 无 DEFAULT | **零命中** |
| ② `SET NOT NULL` | **零命中** |
| ③ `ADD CONSTRAINT ... CHECK` | **零命中** |

补充核对：V370 = **4 × CREATE TABLE + 0 × ALTER TABLE** + 13 个索引 + 8 个触发器，
RLS 的 `ENABLE` / `FORCE` 作用于**它自己新建的表** ⇒ 对既有表的回归面 = **0**。

⚠️ 表内的 `CHECK` 约束（`difference_type` / `status` / `currency` 等）是**新建表自带**的，
不是 `ALTER TABLE ... ADD CONSTRAINT`，不落在三项扫描的形状里 —— 这与「往既有表加约束」不是一回事。

---

## 并入通道被未跟踪文件堵住（本轮第二个发现）

本轮第一次 `merge-ready` 直接失败：

```
✗ 主工作区不干净，拒绝切分支：
?? docs/backlog/2026-10/2026-10-24-nav-task-pages-registry-systemic-lag.md
```

⇒ batch-86 留下的**未跟踪** backlog 文档让集成头工作区一直不干净，
`merge-ready` 的「工作区必须干净」闸**每轮都会挡住并入通道**（协议里那把闸本意是防 MERGE_HEAD 残骸）。

处置：核对该文件是 batch-86 的合法 backlog 产物（内容是导航注册表系统性滞后取证），
顺手修掉里面 2 个损坏字节（`留给调度者` 的「给」被截断成 2 个替换字符），
以独立的 `docs(backlog):` commit 提交 —— **不混进批次的 `docs(batch-88):` 提交**，
因为它与本批交付的三个模块无关。

⚠️ 这类文件**必须被 commit 或删除**，不能留在工作区 —— 否则并入通道长期堵死。

---

## 排不出 4 条车道：结构性服务缺口（与 batch-87 同一形状，且已到临界点）

本轮 `plan --lanes 4` 只排得出 **1** 条车道（12.3.2.2）。按 `lane.mjs` 自身的解析口径逐模块复算
**67 个未完成模块**（与 `sync.mjs` 的「未开发 67」完全对上）：

| 类别 | 模块数 | 明细 |
| --- | --- | --- |
| **① 主责微服务不存在** | **47（70%）** | 卷 13 `reporting` 23 + 卷 18 `platform` 24 |
| **② 蓝图循环依赖** | **4** | `5.1.1.1 ⇄ 5.1.2.1`、`15.3.1.1 ⇄ 15.3.1.3` |
| **③ 上游未交付**（传递自 ①②） | **14** | 5.1.1.2、15.3.1.2、15.6.1.1、15.6.1.2、15.6.2.1（②）；1.1.2.1、1.1.2.2、1.3.2.1、1.3.2.2、14.2.1.1、14.2.1.2、14.3.1.3、14.3.2.1（① reporting）；1.5.2.1（① platform） |
| **④ 上游就绪、可排** | **2** | `12.3.2.2 差异处理`、`12.3.2.3 自动匹配规则` |

⇒ **真正可排的只剩 2 个模块，且它们同属 L3 `12.3.2`** ⇒ `pickModules()` 的「L3 锚点互不相同」约束下，
**每批最多只能排 1 条车道**。本批 1 条、batch-87 2 条，数字会随 12.3.2.x 交付继续递减，
**12.3.2.3 落地后 `plan` 就会返回空数组 ⇒ 流水线进入「每轮零派发」状态**，
直到 `reporting` 或 `platform` 之一被引导进集成头。

⚠️ 这不是调度器能自行拍板的：
`reporting` 的卷首写的是「基于事件构建的**可重建**投影，不是业务写入来源」，
`platform` 是「公共能力 + 各服务内的事件 / outbox / inbox」——
两者都**不能照抄 finance / crm 的形状**，需要先定「事件 → 投影」的管道形状，**属设计决策**。
且三个服务引导都要改 `backend/pom.xml` / `deploy/docker/*` / `tools/codegen/catalog-contracts.py` /
`.github/workflows/ci.yml` ⇒ **同批并行直接撞车**，每批只能引导一个服务。

⇒ 本轮**未擅自引导服务**（引导会动 4 个共享跨切文件，一旦漏改 codegen 的 `SERVICES`
或新服务的 `OpenApiContractTest`，下一轮 L2 的契约闸会直接红），
而是把精确的阻塞分解与杠杆排序更新进 P0 backlog：
`docs/backlog/2026-10/2026-10-23-pipeline-service-gap-and-blueprint-cycle-blockers.md`。

---

## L2 批量验证结果

在集成头上跑全量 6 步（`.dev-agent/l2-run.sh`）。**第 1 轮红、第 2 轮绿 —— 第 1 轮是经取证确认的假红。**

### 第 1 轮（红）：`.dev-agent/l2-run-batch-88-r1-false-red.log`

- 时间：20:04:38 → 20:28:33，卡在 **[1/6] backend `./mvnw verify`**，`MVN_EXIT=1`。
- 失败面：**`Tests run: 2345, Failures: 0, Errors: 4`** —— 4 个 error **全部集中在同一个测试类**
  `channel.IpRiskLibraryTenancyIntegrationTest`，其余 2341 例**零失败零错误**。
- 根因（surefire 报告原文）：`java.net.SocketTimeoutException: Read timed out`
  at `ConnectionFactoryImpl.enableSSL` ⇒ **Postgres 连接在 SSL 协商阶段读超时**，属于**资源争抢导致的连接超时**，
  **不是业务断言失败**。
- 该类的失败是 `<clinit>` 静态初始化抛异常后连带的 `NoClassDefFoundError`（4 个 error 里 1 个是初始化本身、
  3 个是连带），**不是 4 个独立缺陷**。
- ⚠️ 触发时刻 L2 自己就同时持有 **36 个**带 `org.testcontainers.sessionId` 标签的 `postgres:16-alpine`
  （同一个 session id、ryuk 在线、零孤儿容器、容器最大存活 4 分钟 ⇒ ryuk 在正常回收）。
  batch-80 实测同类峰值为 **69** 个 ⇒ **36 个是本仓 L2 的常态量级，不是本批引入的**。

**取证与判定（三条独立证据，指向同一个结论）**：

1. **隔离复跑绿**：`./mvnw -pl services/channel -am test -Dtest='IpRiskLibraryTenancyIntegrationTest'`
   ⇒ `Tests run: 3, Failures: 0, Errors: 0` + **BUILD SUCCESS**（机器空闲、零并发车道）。
2. **第 2 轮全量跑，同一个类在 20:41 自然通过**（`Tests run: 3, Failures: 0, Errors: 0`）
   ⇒ 同一份代码、同一个集成头，第一次红第二次绿 ⇒ **代码与批次无关**。
3. **本批根本没碰 channel 后端**：本批 3 条车道的后端改动只在
   `backend/services/finance/**` 与 `backend/services/identity/**`，
   `channel` 服务**零文件改动** ⇒ 不存在从 finance/identity 到 channel PG 连接超时的代码路径。

⇒ 判定：**第 1 轮是环境性假红**（Testcontainers PG 瞬时并发过高 ⇒ 连接握手超时），
**不是跨车道互相破坏，也不是本批引入的回归**。
⇒ 因此**不派修复任务**（没有可修的缺陷），而是**在机器空闲、零在途车道的条件下重跑整轮 L2** ——
这也正是协议「先隔离复跑最大那一堆，不要直接归因成回归」与
「集成头只接受绿批次」两条要求的交集。

⚠️ 本轮**没有**做 `git bisect`：二分的前提是「红在本批的 merge commit 上」，
而第 1 轮的红的 4 个 error 全部落在**本批一行未碰**的 `channel` 服务里 ⇒ 二分必然定位到「与本批无关」，
是浪费一次全量验证。**用隔离复跑 + 第 2 轮自然通过替代了二分**，省下 2 次全量验证。

### 第 2 轮（绿）：`.dev-agent/l2-run-batch-88-r2.log`

时间：**20:30:18 → 21:32:11（61 分 53 秒）**，在**机器空闲、零在途车道**的条件下重跑。

| 步骤 | 退出码 | 实测 |
| --- | --- | --- |
| [1/6] backend `./mvnw verify` | `MVN_EXIT=0` | **9 个 Maven 模块全 SUCCESS**（tenancy / identity / catalog / channel / warehouse / trade / crm / finance）；**666 个测试类 / 10,428 例，零失败零错误零跳过**（catalog 1998 · channel 2322 · trade 2458 · warehouse 1672 · finance 908 · identity 579 · crm 491） |
| [2/6] `pnpm typecheck` | `TYPECHECK_EXIT=0` | 绿 |
| [2/6] `pnpm lint` | `LINT_EXIT=0` | 绿 |
| [2/6] `pnpm test` | `TEST_EXIT=0` | **622 个测试文件 / 8,626 例全通过** |
| [3/6] Next production build | `BUILD_EXIT=0` | 绿（`NEXT_TELEMETRY_DISABLED=1`） |
| [4/6] `sync.mjs --check` | `SYNCCHECK_EXIT=0` | 绿（台账与蓝图一致） |
| [5/6] `merge-union.itest` | `ITEST_EXIT=0` | 绿 |
| [5/6] codegen python 单测 | `CODEGEN_EXIT=0` | 绿 |
| [6/6] 契约闸 重新生成 | `CONTRACT_GENERATE_EXIT=0` | 绿 |
| [6/6] 契约闸 逐字节比对 | `CONTRACT_GATE_EXIT=0` | 绿（**零漂移**） |
| [6/6] 契约产物 vs HEAD | `CONTRACT_TREE_DRIFT_EXIT=0` | 绿 |
| [附加] 强制 typecheck（`--force`） | `FORCED_TYPECHECK_EXIT=0` | 绿（本批改了 `packages/api-contracts`，不走 turbo 缓存，防止假绿） |

⇒ **L2 GREEN**（12 个退出码全 0）。

⇒ **结论：本批可以 push**（第 1 轮的红是环境性假红，第 2 轮在同一份集成头上全绿即为凭证）。

📌 **协议文档里的规模数字已严重过期**（本轮实测 vs `PIPELINE.md` / `PIPELINE_CRON_PROMPT.md` 的「参考数据」）：
后端 **10,428 例 / 666 类** vs 文档写的「2829 例 / 151 类」= **3.7× / 4.4×**；
前端 **8,626 例 / 622 文件** vs 文档写的「2163 例」= **4.0×**。
⇒ 已在 `docs/backlog/2026-10/2026-10-24-l2-suite-size-and-duration-drift.md` 登记（P1，本轮复核确认仍然成立）。

---

## 收尾三件套

| # | 动作 | 结果 |
| --- | --- | --- |
| ① 写说明 | ✅ 本文件 `docs/sessions/2026-10/2026-10-24-batch-88-reclaim-3-lanes-dispatch-1.md`（每模块一节：功能 / 迁移 / 端点 / 页面 / 测试 / 关键设计决策） | 已落盘 |
| ② commit | ✅ `docs(batch-88): 1.5.1.2 / 12.3.2.1 / 1.3.1.2 —— 3 条车道并入集成头，全量 L2 绿灯`（**本说明文件与台账同一个 commit**） | 已提交 |
| ③ push | ✅ `git push origin integration/module-dev`（**只推集成头，绝不推 `main`**） | 已推送 |

本轮另有 **1 个独立 commit**（`docs(backlog):`）：batch-86 遗留的未跟踪 backlog 文档
—— 它**堵住了 `merge-ready` 的「工作区必须干净」闸**，每轮都会让并入通道 exit(1)；
与本批交付无关，故不混进 `docs(batch-88):` 提交。

---

## 吞吐预期（按本轮实际档位现算，不引用任何历史文档里的写死数字）

| 项 | 值 | 依据 |
| --- | --- | --- |
| 强度档位 | **full**（`MODE=full`） | 当前时刻 < `TOKEN_HORIZON=2026-10-25T00:00:00+0800` ⇒ 不降档，`LANES=4` / `THROTTLE_MIN=50` |
| cron 栅格 | `0 * * * *` ⇒ 24 触发点/天 | `.dev-agent/PIPELINE.local.md` |
| 节流闸阈值 | 50 分钟 | 同上 |
| **实测一轮墙钟** | **61.9 分钟**（L2 r2：20:30:18 → 21:32:11） | 本轮日志 |
| ⇒ 实际节奏 | **由 L2 耗时决定，不由节流闸决定** —— 一轮 > 60 分钟栅格 ⇒ 相邻 tick 会被闸/节流闸拦掉 | 与 `2026-10-24-l2-suite-size-and-duration-drift.md` 的结论一致 |
| **本批实际派发** | **1 条**（不是 4 条） | 编排器只排得出 1 条，见「排不出 4 条车道」一节 |

**⚠️ 最重要的吞吐结论：当前的瓶颈不是机器，是「可排模块池」。**

- 真正上游就绪的只剩 **2 个模块**（12.3.2.2 / 12.3.2.3），且**同属 L3 `12.3.2`** ⇒ 每批 1 条。
- 12.3.2.3 落地后 ⇒ **`plan` 必然返回空数组** ⇒ 流水线进入「每轮零派发」状态，
  直到 `reporting` 或 `platform` 之一被引导进集成头。
- ⇒ **67 个未开发模块里的 65 个，当前状态下流水线一个都排不出来。**
- 若三项结构性阻塞全部解除（引导 `reporting` + 引导 `platform` + 解开 2 对蓝图循环依赖）：
  届时 `plan` 才能一次排满 4 条（L3 锚点互不相同），名义吞吐回到 **4 × 每小时 ≈ 96 个/天**，
  按协议的经验折损系数 ≈ **0.70** ⇒ **实际约 65–70 个/天** ⇒ 剩余 65 个模块约 **1 天**；
  若只解除引导服务（不解循环依赖）⇒ 约 27–31 个/天 ⇒ 约 **2–3 天**。
- ⇒ **诚实区间：1–3 天**（前提是三项阻塞被解除）。**在阻塞解除之前，这个数字不成立** ——
  流水线会安静地每轮零派发，直到有人做决定。

---

## 风险提示

1. 🔴 **结构性天花板已到临界点**（最高优先级）：67 个未开发模块里 **47 个（70%）** 被
   「主责微服务不存在」堵死（`reporting` 23 + `platform` 24），
   另 **4 个** 被蓝图循环依赖锁死、**14 个** 被这两者传递性挡住。
   ⇒ 停机条件「全部 465 个模块已交付」**在当前状态下不可达**。
   ⇒ 已把精确分解与杠杆排序更新进 P0 backlog：
   `docs/backlog/2026-10/2026-10-23-pipeline-service-gap-and-blueprint-cycle-blockers.md`。
   ⇒ **需要人工决策的两件事**：
   (a) 引导 `reporting` 的**事件 → 投影**管道形状（不能照抄 finance/crm）；
   (b) 是否解开卷 05 / 卷 15 的两对蓝图循环依赖（属**蓝图语义变更**，协议明令调度者不得自行改锚点）。
2. 🟡 **L2 存在环境性假红风险**：本轮第 1 轮红在 `channel.IpRiskLibraryTenancyIntegrationTest`
   （PG 连接 SSL 握手读超时），隔离复跑绿、第 2 轮全量自然通过 ⇒ 已确认假红。
   根因是 Testcontainers PG 瞬时并发（本轮实测峰值 **36** 个，batch-80 实测同类峰值 **69**）
   ⇒ **L2 单轮跑满一次全量的同时，不要并发任何车道的 L1**（已在下面「执行顺序」一节遵守）。
3. 🟡 **Flyway 版本倒挂**（P1，本轮新增一个实例）：V370（12.3.2.1）**晚于** V371 / V373 并入集成分支。
   **不影响 L2**（Testcontainers 每次都是全新库，Flyway 按版本号顺序全量应用），
   但会让**存量库**的 `flyway migrate` 报 `Detected resolved migration not applied to database: 370`。
   已登记于 `docs/backlog/2026-10/2026-10-24-flyway-out-of-order-version-inversion.md`，**本轮未擅自改名**（改号会与下一批 `plan` 的预分配号撞车）。

---

## 本轮的执行顺序（与协议字面顺序不同，原因见下）

本轮实际顺序：

```
取闸 → merge-ready（3 条）→ sync → gc
     → 静态预检(5.1) → L2 第 1 轮（红，判定假红）→ 隔离复跑取证
     → L2 第 2 轮（绿）→ 写说明 → commit → push (5.3)
     → 派发新车道（后台跑 L0/L1）→ gate release
```

**派发放在 L2 之后**，而不是协议字面的「第 4 步派发 → 第 5 步 L2」——
依据是 `docs/backlog/2026-10/2026-10-24-l2-before-dispatch-ordering.md`（batch-86 取证，P1）：
按字面顺序会让 L2 与刚发车的车道 L1 并发抢 Testcontainers PG，
2026-10-19 实测那种形状下的 `seed()` 503 / 连接拒绝**全是假红**。
⇒ 本轮 L2 全程**没有任何在途车道**，机器独占 ⇒ 正是这个顺序让第 2 轮拿到了干净的全绿。
