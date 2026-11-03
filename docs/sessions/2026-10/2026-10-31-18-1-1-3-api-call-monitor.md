# 2026-10-31: 18.1.1.3 调用监控（API 调用监控 / 网关入口埋点）

## 摘要

接手 lane `18.1.1.3`（卷 18「集成开放与智能自动化」→ 18.1「开放平台」→ 18.1.1「API 管理」→ 18.1.1.3「调用监控」）。

本车道是**第 5 次重派**：前 4 任 worker 全部因基础设施中断死亡（ERR_HTTP2_PING_FAILED / Token Plan 用量上限），
成果从未进版本库（接手时工作区有 19 项未提交改动、0 个 commit）。前任留下的「已全绿」自陈属**未经验证的 worker 自陈**，
故本轮**独立重跑了 L0 + L1 全部闸门**并逐项复核，未采信自陈。

模块职责（蓝图原文）：「监控 API 调用：QPS、错误率、限流命中、慢调用、按应用与接口维度分析，异常告警。
输入：调用埋点；配额配置。输出：调用统计 + 告警。关系：上游 = API 网关；下游 = 1 工作台告警、18.1.1.1 配额调整。
**外部系统调用错误要能被快速定位**，否则对接方只会说"你们接口有问题"。」

---

## 阶段一：依赖自检（动手写代码之前先做）

> 本节是前任留下的调研结论。本轮**逐条复核过**，结论与依据均成立（复核方式见「阶段三：独立复核」）。

### 找到了什么

| 依赖 | 结论 | 依据 |
| --- | --- | --- |
| API 网关组件 | **不存在** | 全仓 `backend/services/` 无网关 / 过滤器 / 拦截器；本仓至今没有对外网关 |
| 可用的调用埋点来源 | **不存在** | 无任何服务产出「对外 API 调用」的埋点 |
| `identity_audit`（identity V3） | **不是调用日志** | 它是写操作审计表（actor_id / resource_id / action），没有 QPS / 耗时 / 状态码维度 |
| identity 的 `OperationLogInterceptor`（17.5.3.2） | **不可用** | ① 只覆盖 `/identity/**` 与 `/auth/**`；② 写 identity 库的 `operation_log`；③ 无应用（app）维度、无限流维度；④ 跨服务直接读对方表违反 AGENTS.md 模块边界纪律 |
| 18.1.1.1 `platform_app_quota` / `platform_app_quota_usage` | **可用** | 同服务同库的直接读取（限流命中维度与「配额调整」下游建议的真实来源） |
| 18.1.1.1 `platform_app_credential.api_key_lookup` | **可用** | SHA-256 十六进制，是唯一能按值定位 API Key 的列（Secret 侧刻意没有这样的列） |
| 18.1.1.2 `ApiSpecSource` / `ApiCatalogService` | **可用** | 真去各服务 `/v3/api-docs` 取活契约（TTL 60s）；platform 服务自己在册（8089） |
| 卷 01 工作台告警 | **不存在** | 全仓只有 trade 的 `WORKBENCH_TODO` 与 channel 的两个 `workbench_alert_*` 占位，三者都标 `available=false` |

### 缺什么

1. **没有上游埋点** ⇒ QPS / 错误率 / 限流命中 / 慢调用四维拿不到真数据。
2. **没有跨租户凭证定位出口** ⇒ 网关拿到一个 API Key 后无法知道它属于哪个租户（identity 只有 `GET /internal/access`，是会话路径，没有 API-Key 类内部端点）。
3. **工作台告警下游没有对接服务** ⇒ 告警只能在本模块内闭环。

### 怎么处置

1. **在 platform 服务内新增真实网关入口**（`/open/v1/**`），端点封闭、逐条可数：真实校验凭证（API Key + Secret）、
   真实判定授权范围 / IP 白名单 / 配额（按 18.1.1.1 的 `platform_app_quota`），真实落库调用埋点。
   这是任务书明确授权的做法，也是让四维指标成为真数据的**唯一**途径 —— 造一组假数组冒充监控数据是本仓明令禁止的。
2. **跨租户凭证定位只走一个出口**：`platform_api_credential_lookup(text)` SECURITY DEFINER 函数
   （与 identity V42 / V325、V377 的 `api_key_lookup` 同一套纪律）。函数**不做 LIMIT 1** ——
   V377 的唯一约束是 `UNIQUE(tenant_id, api_key_lookup)`，同一个 Key 值可以在两个租户下各登记一次，
   必须把候选行全部拿去比 Secret，比中唯一一行才放行，比中多行判为歧义并拒绝。
3. **接口维度接活 spec**：端点清单的名称 / 说明取自 platform 服务自己的活 spec，不是自写接口词表；
   spec 读不到时如实标 `available=false` 并写明原因。
4. **工作台告警下游如实答 `available=false`**，写明依据与归属（卷 01），不假装已接通。

---

## 阶段二：落地

### 决策摘要

| 决策 | 结论 | 理由 |
| --- | --- | --- |
| 网关入口由谁建 | **本车道自建**（platform 服务内 `/open/v1/**`） | 蓝图写死「上游 = API 网关」而全仓没有网关；不补就没有埋点，没有埋点四维指标就只能是编出来的 |
| 网关放行范围 | **封闭清单 7 条 + 兜底 404，不做路径转发** | 放行任意路径等于开放代理（能探测内网）；放行后端不存在的映射比 404 更糟（与 18.1.1.2 刻意不放行 spec 代理同一判断） |
| 网关租户来源 | **API Key 反查**，与 `/platform/**` 的会话租户彻底分开 | 一个端点有两种租户口径是本仓最不愿意出现的形状 |
| 跨租户凭证定位 | **一个** SECURITY DEFINER 函数，**不做 LIMIT 1** | `UNIQUE(tenant_id, api_key_lookup)` 允许同一 Key 值在两个租户各登记一次；LIMIT 1 就是「随便挑一个」= 把 A 的调用算到 B 头上 |
| 定位不到租户的调用 | **不落埋点** | 没有租户可归属；硬塞一个等于伪造归属 |
| 五种失败（凭证错 / 应用吊销 / 范围不足 / IP 不在白名单 / 超配额） | **全部落埋点** | 它们恰是对接方最需要被定位的失败（蓝图原话「外部系统调用错误要能被快速定位」） |
| 限流命中 vs 错误 | **两列分开**（`outcome` + `rate_limited`），迁移里用 CHECK 把结论码与状态码**逐字钉死** | 「有多少次被配额挡下来」与「有多少个 4xx」是两个问题；两列一旦各说各话，页面上两个数字就互相打架 |
| 埋点表的可变性 | **只追加**：GRANT 只给 SELECT+INSERT，另用**触发器**在数据层拒绝 UPDATE/DELETE | 少授一个权限码会被「迁移脚本用属主身份跑」绕开，触发器不会；两个闸门都留着 |
| 告警去重 | **未闭环期间同一异常只一行**（部分唯一索引）+ 重复扫描只刷新指标 + 指标回落后自动闭环 | 否则告警列表会被同一件事刷成几十行；永远堆着早就恢复的异常，运营就分不清「现在还好吗」 |
| 阈值口径 | **平台固定口径，零预置行**，但必须随 `/options` 显式给出 | 运营看到告警要知道是按什么判的；不替用户预置任何一行配置 |
| 工作台告警 / 自动配额调整 | **如实答 `available=false`** | 卷 01 工作台告警至今未交付；改配额是写操作，不该由告警副作用触发 —— 本模块只给建议并指路 |
| 统计窗口的时钟 | **DB 时钟**（与埋点 `called_at` 同源） | 窗口切分必须与被统计的事实同一把尺子 |
| 配额窗口的时钟 | **注入的 `Clock`**（生产 = 系统时钟） | 配额窗口是**离散**的（MINUTE 截断到分钟），边界必然存在 ⇒ 固定时钟才能让测试确定（见「关键 trade-off」第 1 条） |

### 关键 trade-off

1. **🔴 修 flaky 的方向：固定时钟，不是 sleep / 重试 / 放宽断言。**
   前任留下的 26 例里，1 例时间相关 flaky 未修完即被掐断。本轮定位到**根因不是「时钟不准」，
   而是「窗口是离散的」**：网关判限流时窗口起点是「现在」按窗口类型**截断**出来的
   （`AppCredentialService.windowStart`：MINUTE ⇒ 截断到分钟）。原实现里
   `OffsetDateTime.now()` 散在服务里，于是「一次 200 + 一次应 429」的两次调用只要
   **跨过一次分钟边界**就落进两个窗口 ⇒ 上一个窗口的用量看不见了 ⇒ 配额判定被清零 ⇒
   本该 429 的那次反而放过去 ⇒ 用例随机红。典型概率 = 调用间隔 ÷ 60 秒
   （间隔 50ms ⇒ 约 0.08%/次；`限流告警`那例一次连打 11 次，概率更高）。
   **修法**：新增 `ApiCallMonitorClockConfiguration`（照抄 trade 的
   `ShipmentWritebackClockConfiguration` 那一套纪律：一个 `Clock` Bean，生产 = `Clock.systemUTC()`，
   **刻意不改**其余 platform 服务），`ApiGatewayService` / `ApiCallMonitorService` 注入它，
   测试用 `@Primary` 的 `Clock.fixed(2026-01-01T00:00:00Z, UTC)` 覆盖那一个 Bean。
   **顺带修掉三处「本来就不该那样」的判定**：① 配额视图与配额执行必须同一个窗口
   （原来控制器自己算 `now`，可能与网关判限流差一次截断）；② 告警的「建议配额」要读同一个窗口
   （原来会读到另一个窗口的用量，把「用得最满的窗口」挑错）；③ 按**事实列**断言而不是按行序断言
   （原来断言 `rows.get(1)`，而 `called_at` 是 DB 时钟、两行可能落在同一微秒，「取第 1 行」在那一刻就是任意的）。
2. **🔴 网关入口由本车道自建 vs 停下来说明。**
   任务书给的两条路是「把它做成真数据」或「停下来说明」。本车道选了前者，理由是：
   蓝图把「上游 = API 网关」写进了模块定义，而**网关不是本模块能顺带交付的东西**
   （它是独立的基础设施决策）；但**埋点是本模块的职责**（「输入：调用埋点」）。
   不补网关就没有埋点 ⇒ 没有埋点就只能编数据 ⇒ 那才是真正违反「禁止 mock」。
   因此网关做成**最小但真实**的形态：放行清单封闭、每条判定都是真逻辑、每次调用都落真埋点。
3. **🔴 跨租户函数不做 LIMIT 1 的代价**：跨租户定位会返回多行候选，Java 侧要逐行比 BCrypt
   （Secret 侧刻意没有可定位摘要 —— 见 V377 的纪律）。代价是 O(候选数) 次 BCrypt 比对；
   收益是「同一 Key 值在两个租户下各登记一次」时会判为歧义并拒绝，而不是静悄悄挑一个租户。
4. **🔴 网关入口不进浏览器 api-proxy 白名单**：它是给**外部系统**调的路径，租户来自 API Key 反查。
   放进浏览器白名单等于让任意浏览器带着别人的 Key 来打 ⇒ 刻意不放行，并有**负向用例**钉住。

### 涉及文件

**后端（Java，platform 服务）**

| 文件 | 作用 |
| --- | --- |
| `backend/services/platform/src/main/java/com/starcon/platform/ApiGatewayEndpoints.java` | 网关放行清单（封闭 7 条）+ 路径匹配（路径参数按段数与段形状判） |
| `backend/services/platform/src/main/java/com/starcon/platform/ApiGatewayService.java` | 网关服务：定位凭证 → 四道判定 → 执行 → 埋点落库（新增 Clock 注入） |
| `backend/services/platform/src/main/java/com/starcon/platform/ApiGatewayController.java` | 网关 HTTP 端点（`/open/v1/**`，12 条 + 兜底 404） |
| `backend/services/platform/src/main/java/com/starcon/platform/ApiCallMonitorModels.java` | 模型（概览 / 趋势 / 维度 / 慢调用 / 明细 / 告警 / 网关回显） |
| `backend/services/platform/src/main/java/com/starcon/platform/ApiCallMonitorService.java` | 监控服务：统计 + 告警判定 + 处置（新增 Clock 注入） |
| `backend/services/platform/src/main/java/com/starcon/platform/ApiCallMonitorController.java` | 监控 HTTP 端点（`/platform/call-monitor`，12 条） |
| `backend/services/platform/src/main/java/com/starcon/platform/ApiCallMonitorClockConfiguration.java` | **本轮新增**：可被钉死的时钟（修 flaky） |

**迁移**

| 文件 | 作用 |
| --- | --- |
| `backend/services/platform/src/main/resources/db/migration/V414__api_call_monitor.sql` | 2 张表（埋点 / 告警）+ 跨租户 SECURITY DEFINER 函数 + FORCE RLS + GRANT + 只追加触发器（384 行） |
| `backend/services/identity/src/main/resources/db/migration/V414__platform_call_monitor_permission.sql` | 登记 `platform.call-monitor.read` / `.update` + 补存量 OWNER（97 行） |

**测试**

| 文件 | 作用 |
| --- | --- |
| `backend/services/platform/src/test/java/com/starcon/platform/ApiCallMonitorIntegrationTest.java` | 15 例功能集成测试（真实 PostgreSQL 16.15） |
| `backend/services/platform/src/test/java/com/starcon/platform/ApiCallMonitorTenancyIntegrationTest.java` | 11 例租户隔离测试（连接池压到 1） |

**前端**

| 文件 | 作用 |
| --- | --- |
| `apps/web/src/app/integrations/call-monitor/page.tsx` | 页面入口 `/integrations/call-monitor` |
| `apps/web/src/components/integrations/call-monitor-workspace.tsx` | 工作区（五个页签，938 行） |
| `apps/web/src/components/integrations/call-monitor-model.ts` | 取数 + 显示口径（325 行） |
| `apps/web/src/components/integrations/call-monitor-model.test.ts` | 10 例 |
| `apps/web/src/app/api/platform/call-monitor-allowlist.test.ts` | 6 例（白名单双向断言） |
| `apps/web/src/lib/api-proxy.ts` | **仅新增一条独立 `services[]` 元素**（既有条目一字未改） |

**契约（生成物）**：`packages/api-contracts/src/platform.openapi.json`、`packages/api-contracts/src/contracts/platform.ts`
（重新生成，未手改；其余 8 个服务与 index.ts 逐字未变）

**台账**：`docs/product/capability-blueprint/module-progress.md` 第 538 行（18.1.1.3 调用监控 —— 只改「状态」「证据」两格）

---

## 阶段三：独立复核（本轮实际做了什么）

### 接手时的 19 项未提交改动：逐项处置

| # | 文件 | 处置 | 依据 |
| --- | --- | --- | --- |
| 1 | `backend/.../V414__api_call_monitor.sql` | **继承** | 复核表结构、CHECK、RLS、GRANT、触发器、函数签名（`text` ↔ `text` 逐字一致，PL/pgSQL RETURN QUERY 严格类型比较） |
| 2 | `backend/.../V414__platform_call_monitor_permission.sql` | **继承** | 复核权限码只有 read/update 两个（没有端点的权限码不注册）、page_code 外键链、存量 OWNER 补权 |
| 3 | `backend/.../ApiGatewayEndpoints.java` | **继承** | 复核放行清单封闭 7 条、路径参数按段数判 |
| 4 | `backend/.../ApiGatewayService.java` | **继承 + 改写** | 继承五步判定与埋点落库；**改写**：注入 `Clock`、配额视图改由服务按同一时钟算窗口 |
| 5 | `backend/.../ApiGatewayController.java` | **继承 + 改写** | 继承 12 条端点 + 兜底 404；**改写**：`quota()` 不再自己算 `now`（否则视图与执行不是同一个窗口） |
| 6 | `backend/.../ApiCallMonitorModels.java` | **继承** | 复核全部类型与契约生成结果对得上 |
| 7 | `backend/.../ApiCallMonitorService.java` | **继承 + 改写** | 继承统计与告警判定；**改写**：注入 `Clock`、`quotaSuggestion` 改用同一时钟、统计窗口仍用 DB 时钟 |
| 8 | `backend/.../ApiCallMonitorController.java` | **继承** | 复核 12 条端点、权限码 read/update 分离 |
| 9 | `backend/.../ApiCallMonitorIntegrationTest.java` | **继承 + 改写** | 继承 15 例；**改写**：固定时钟、按事实列断言、断言写回的值（窗口起点与用量） |
| 10 | `backend/.../ApiCallMonitorTenancyIntegrationTest.java` | **继承** | 复核三断言齐备 + 凭证歧义拒绝实跑 |
| 11 | `apps/web/src/lib/api-proxy.ts` | **继承 + 拆成两个 commit** | 新增的 `services[]` 元素保留在功能 commit；prettier 机械格式化单独成一个 `style:` commit |
| 12 | `apps/web/src/components/integrations/call-monitor-model.ts` | **继承** | 复核类型全部从契约派生、查询串空值不拼 |
| 13 | `apps/web/src/components/integrations/call-monitor-model.test.ts` | **继承** | 复核 10 例覆盖显示口径 |
| 14 | `apps/web/src/components/integrations/call-monitor-workspace.tsx` | **继承** | 复核加载 / 空 / 错误三态齐全、阈值口径显式、不可用下游显式 |
| 15 | `apps/web/src/app/integrations/call-monitor/page.tsx` | **继承** | 复核不是占位页 |
| 16 | `apps/web/src/app/api/platform/call-monitor-allowlist.test.ts` | **继承** | 复核双向断言 + 负向样本取自本模块前缀 + 网关入口不可从浏览器打 |
| 17 | `packages/api-contracts/src/platform.openapi.json` | **弃用并重新生成** | 前任的产物**独立重新生成后逐字相同** ⇒ 结论正确；但本轮不采信自陈，改为跑 codegen 复现 |
| 18 | `packages/api-contracts/src/contracts/platform.ts` | **弃用并重新生成** | 同上 |
| 19 | `docs/sessions/2026-10/2026-10-31-18-1-1-3-api-call-monitor.md` | **继承 + 补齐** | 阶段一保留（复核后成立），阶段二与涉及文件两节由本轮补齐 |

**额外处理（调度者指派的增量格式闸修复）**：
`apps/web/src/components/integrations/api-catalog-workspace.tsx`（18.1.1.2 已交付物，该车道 worktree 已被 gc，
无活车道拥有）—— **只做 prettier 格式化，零逻辑变化**。已用规范化比对逐字证明：
去掉注释、空白、JSX 括号位置、className 内部的 Tailwind 类序后与格式化前**逐字相同**（17650 字符）；
className 的类集合 322 vs 322 逐个相等（只有顺序变了）；唯一非类序差异是 prettier 给 JSX 三元分支补的圆括号
（`cond ? <A/> : <B/>` → `cond ? (<A/>) : (<B/>)`），语义等价。

### 网关入口的「真实性」逐项说明

| 环节 | 实现 | 怎么证明它不是假实现 |
| --- | --- | --- |
| **凭证校验** | API Key → SHA-256 → `platform_api_credential_lookup()` → 候选行 → 逐行 BCrypt 比 Secret | 用例「凭证错也落行」断言 401 + `SECRET_MISMATCH` 且**行仍然落在密钥所属租户名下**；「定位不到租户的调用不落行」断言 Key 不存在时**不落行**（不是落一个假租户） |
| **授权范围** | 读 `platform_app_scope`，按 `资源:操作` 判定；「没配 = 不限制」 | 用例「授权范围不足被拒且落行」只授 WRITE、不授 READ ⇒ 403 + `SCOPE_DENIED` 且落行 |
| **IP 白名单** | 读 `platform_app_ip_allowlist`（CIDR），复用 18.1.1.1 的 `Cidrs.contains`；「没配 = 不限制」 | 用例「ip 不在白名单被拒且落行」配 `10.0.0.0/8`、来源 IP 不在内 ⇒ 403 + `IP_NOT_ALLOWED` 且落行 |
| **配额限流** | 逐窗口读 `platform_app_quota`（配置）与 `platform_app_quota_usage`（用量），超限 ⇒ 429 | 用例「超出配额回 429」：配 MINUTE 上限 1 ⇒ 第 1 次 200、第 2 次 429；并**断言写回的值**：用量行 `window_started_at` 恰是那一分钟、`used_count` 恰为 1（被限流挡下的那次**不消耗服务**） |
| **埋点落库** | 每次调用落一行 `platform_api_call_log`（只追加，触发器钉死改与删） | 15 例里多次断言**行数逐条对得上**；「埋点只追加」用例实跑 UPDATE 与 DELETE 都被数据层挡下；「调用时刻取自数据库时钟」断言写回的值与 DB 时钟一致（挡 `Timestamp.valueOf(LocalDateTime)` 那种 −28800s 的写法） |

### 后续待办

1. **卷 01 工作台告警**：告警目前只落在本模块的 `platform_api_call_alert` 并对外暴露列表，
   推送面待卷 01 交付后由工作台消费（能力声明里已如实标 `available=false`）。
2. **独立网关**：本车道自建的是最小真实形态。若将来接入独立网关（如 Kong / APISIX），
   它需按同一口径落 `platform_api_call_log`（能力声明的「上游缺口」一格里已写明）。
3. **调用埋点的其它来源**：目前只有本模块自建的网关入口产出埋点。
4. **配额建议的落地**：限流告警已给出 18.1.1.1 的配额调整端点与建议上限，
   但**自动执行**刻意不做（改配额是写操作，不该由告警副作用触发）。
