# batch-70 · 卷 16 合规与风控 ×4（4 条车道并入 + L2 全量验证）

> 本轮编队：2026-10-23 05:00 tick。4 条车道并行开发，`LANES=4`（MODE=full，`TOKEN_HORIZON=2026-10-25` 未到）。
> 调度闸：05:00 取闸，全程持有，落地后逐节点 keepalive 续期（TTL 4200s）。
> 节流闸：距上批 119 分钟 ≥ 阈值 50 分钟，放行。容量闸：温度无降频项 / load 1.19（基线 10 核）/ Testcontainers PG 容器 0 / running 0 < 配额 6 ⇒ GO。
> 静态预检：本批 4 个新迁移**零命中**互相破坏形状（无 `ADD COLUMN` 无 DEFAULT / 无 `SET NOT NULL` / 无 `ADD CONSTRAINT CHECK`），且**全部是纯新建表、零 ALTER 任何既有表**。
> 合并产物已独立校验：`channel.openapi.json` 合法 JSON、paths **822 → 882（+60，删除 0）**、schemas **1577 → 1682（+105，删除 0）**、1577 个共有 schema 语义**零变化**、float 字面量 `0.0`（8 → 8）与 decimal 字面量（21 → 21）完整保留（batch-68 的塌陷缺陷未复发）。其余 5 个服务的契约产物**逐字节不变**。

## 批次构成

| 车道     | 模块           | 服务    | 迁移 | 分支            | commit     |
| -------- | -------------- | ------- | ---- | --------------- | ---------- |
| 16.4.1.2 | 检测与预警     | channel | V317 | `feat/16-4-1-2` | `2c2dd8fb` |
| 16.5.1.1 | 黑名单         | channel | V318 | `feat/16-5-1-1` | `86510c91` |
| 16.5.2.1 | 登录与访问安全 | channel | V319 | `feat/16-5-2-1` | `46969aaa` |
| 16.6.1.1 | PII 管理       | channel | V320 | `feat/16-6-1-1` | `2b52cf23` |

**api-proxy 锚点互不重叠**（本批 4 条车道都改 `apps/web/src/lib/api-proxy.ts`）：
16.4.1.2 → `prefix: 'identity/'` 之前（`@@ +192`）／
16.5.1.1 → `// 14.5.2.1 带货归因` 之前（`@@ +1435`）／
16.5.2.1 → `// 11.4.1.1 平台纠纷列表` 之前（`@@ +7952`）／
16.6.1.1 → 数组末尾 `buyer-risk-scores` 之后（`@@ +8689`）。
四条车道各自**纯新增一条独立 `services[]` 元素**（65 / 60 / 81 / 61 行），既有 169 条元素**一字未动、也不重排** ⇒ git 能干净求并。
合并后实测：白名单条目 **169 → 173**，**零行删除**，169 条原有 prefix 全部健在（`comm` 差集为空）。
首 token 全部避开既有命名空间且既有全文零命中：`ip-detections` / `buyer-blacklist` / `access-security` / `pii-access`。

---

## 模块 16.4.1.2 检测与预警

**这个功能做什么**：商品要刊登之前，系统拿标题、描述、图片标识、品牌字段去比对公司的侵权库，
出一条**检测报告**：命中了哪一条侵权条目、命中了哪个关键词、风险多高、建议怎么整改。
命中了会**发预警**；图片相关的命中一律进**人工复核队列**（不自动判死）。
已上架的商品还能挂**定期巡检计划**，调度器按计划跑一遍，把新冒出来的风险捞出来。

**技术落地**：

- 迁移：`backend/services/channel/src/main/resources/db/migration/V317__channel_ip_detection.sql`
  —— **纯新建 9 张表、零 ALTER 任何既有表、零跨服务外键**：
  `ip_detection_report`（检测报告）/ `ip_detection_hit`（命中项，**append-only**）/
  `ip_detection_alert`（预警）/ `ip_detection_remediation`（整改建议）/
  `ip_detection_review_task`（人工复核队列）/ `ip_detection_external_query`（外部商标·专利查询登记）/
  `ip_detection_external_hit`（外部查询结果，**append-only**）/
  `ip_detection_inspection_plan`（巡检计划）/ `ip_detection_inspection_run`（巡检运行，**append-only**）。
  全部 `tenant_id uuid NOT NULL` + ENABLE **与 FORCE** ROW LEVEL SECURITY + POLICY isolation
  - `GRANT channel_app` / `REVOKE starcon_app`。
    上游 `ip_risk_entry` / `ip_risk_match_term` / `ip_risk_entry_platform` **只读**，
    命中项只存**逻辑 id + 快照**（条目后来被停用或改写，历史报告仍解释得清）。
- 端点：`/channel/ip-detections/**` —— **17 条路径 / 23 个操作**：
  `IpDetectionController` / `IpDetectionService` / `IpDetectionModels` / `IpDetectionRules` / `IpDetectionSourcePort`（包 `com.starcon.channel` 扁平包）。
  覆盖检测（`detections` 列表真分页 + 真筛选 / 详情）、命中项、预警与状态流转（`alerts` + `alerts/{id}/status`）、
  整改建议与状态流转（`remediations`）、人工复核（`reviews` + `reviews/{id}`）、
  外部查询登记与结果（`external-queries` + `external-queries/{id}/hits` + `status`）、
  巡检计划与运行（`inspection-plans` / `inspection-runs`）、能力声明（`capabilities`）。
- 页面：`/compliance/ip-detection` —— `apps/web/src/app/compliance/ip-detection/page.tsx` +
  `apps/web/src/components/ip-detection/{ip-detection-model.ts, ip-detection-workspace.tsx, ip-detection-model.test.ts}`。
  筛选维度：风险等级 / 权利类型 / 命中来源（本地库 / 外部查询）/ 处置状态 / 复核状态；
  操作：发起检测、处置预警、登记整改、推进复核、挂与跑巡检。
  加载态、空态、错误态三态齐全；**外部查询与图片识别的不可用能力逐条标 `available=false` + ownerModule + 影响面**。
- 测试：`IpDetectionRulesTest` 21 例 + `IpDetectionIntegrationTest` 19 例 + `IpDetectionTenancyIntegrationTest` 3 例 = **43 例**
  （真实 PostgreSQL 16 + Testcontainers，全绿）；前端 `ip-detection-model.test.ts` + `ip-detection-allowlist.test.ts` **19 例**。

**关键设计决策**：

1. 🔴 **append-only 与巡检运行状态冲突**，改成**跑完才落行**（去掉「运行中」态）——
   `ip_detection_inspection_run` 只给 `SELECT,INSERT`，原实现要 `UPDATE` 回写状态 ⇒ 权限拒绝。
   代价：中途崩溃则这一轮没有回执 —— 但计划的 `last_run_at` 也不前进，下次调度会重跑，不会静默丢巡检。
2. 🔴 **事务内 catch 唯一约束冲突**会把事务置为 aborted、变成看不懂的 500 ⇒ 改为
   `ON CONFLICT (tenant_id, hit_id) DO NOTHING RETURNING id` + 返回 422，并补了对应用例（42 → 43 例）。
3. **图片侵权检测难度高于文本** ⇒ 本仓无图像识别引擎，图片比对是**标识文本比对**（文件名 / alt / URL），
   因此图片命中**一律**进人工复核队列且**不单独构成可判定命中**。
4. **外部商标 / 专利库无凭据、无网络出口** ⇒ 不写打真实域名的假 HTTP 客户端；照 10.4.2.2 报送表 / 16.4.2.2 凭证文件的形状
   做**登记承接**，外部结果与本地侵权库**同等参与**比对。
5. **刻意不装 `@Scheduled`**（与 V212 同纪律：多副本定时器要做主节点选举与分布式锁，属平台能力）
   ⇒ 由外部调度按 `interval_days` 调 `POST /inspection-runs?planId=…`。
6. **不写下游**：16.2.1.2 拦截（V313，已交付）拥有拦截权，本模块只产出 `recommended_action` 建议 + 预警 + 整改建议，**零写**它的策略表。

---

## 模块 16.5.1.1 黑名单

**这个功能做什么**：把有问题的买家拉黑 —— 记录是谁、在哪家店拉的、为什么拉、风险多高、
是只在一家店生效还是全店生效。下单时系统自动打标记，必要时直接拦下来。
拉黑可以**申诉**：买家申诉后由人审，审通过就解封 —— 黑名单不会把人永久钉死。

**技术落地**：

- 迁移：`backend/services/channel/src/main/resources/db/migration/V318__channel_buyer_blacklist.sql`
  —— **纯新建 4 张表、零 ALTER 任何既有表**：`buyer_blacklist_entry`（黑名单登记）/
  `buyer_blacklist_status_event`（状态流转，**append-only**，解除留痕）/
  `buyer_blacklist_appeal`（申诉）/ `buyer_blacklist_order_flag`（下单拦截标记）+ **7 个 DB 守卫触发器**
  - FORCE RLS + `GRANT channel_app` / `REVOKE starcon_app`。
    买家标识沿用上游口径 `buyer_key`（32 位小写 hex）。
- 端点：`/channel/buyer-blacklist/**` —— **9 条路径 / 10 个操作**：
  `BuyerBlacklistController` / `BuyerBlacklistService` / `BuyerBlacklistModels` / `BuyerBlacklistRules` / `BuyerBlacklistSourcePort`。
  `capabilities`（能力声明）/ `entries`（GET 列表真分页 + POST 手工登记）/`entries/auto`（来自风控信号的自动登记）/
  `entries/{id}`（详情）/`entries/{id}/release`（解除）/`entries/{id}/appeals`（申诉）/`appeals/{id}/decision`（申诉裁决）/
  `check`（下单时判定）/ `flags`（拦截标记查询）。
  **刻意没有 DELETE 段** —— 黑名单靠状态机收口，历史不可抹。
- 页面：`/compliance/buyer-blacklist` —— `apps/web/src/app/compliance/buyer-blacklist/page.tsx` +
  `apps/web/src/components/buyer-blacklist/{buyer-blacklist-model.ts, buyer-blacklist-workspace.tsx}`。
  筛选维度：生效范围（单店 / 全店）/ 风险等级 / 状态（生效中 / 已解除）/ 录入来源（手工 / 自动）；
  操作：手工登记、自动登记、解除、受理申诉、裁决申诉。三态齐全。
- 测试：`BuyerBlacklistIntegrationTest` 22 例 + `BuyerBlacklistTenancyIntegrationTest` 4 例 = **26 例**（含租户隔离三断言，全绿）；
  `BuyerBlacklistTradeStub` 提供 9.2 审单的跨服务只读桩；前端模块 23 例 + 共享白名单全量回归 139 文件 / 735 例全绿。

**关键设计决策**：

1. 🔴 **没有写上游 `buyer_risk_score.blacklisted` / `blacklist_reason`** —— 任务书原本假设这两列是「回写目标位」，
   但实读上游代码（`BuyerRiskScoreService:558-559`）确认它们是**评分时刻从 trade 画像抄来的快照**，
   且 V316 给整表挂了 `BEFORE UPDATE OR DELETE` 触发器 —— **任何 UPDATE 都会被 RAISE**。
   写它必须 ALTER 上游触发器（跨车道 DDL 回归面）。故本模块**自建登记表承载权威事实**，
   取舍写进迁移注释 / 能力声明 / 会话日志。同理未写 `buyer_risk_signal_writeback`（其 `feature_code` 被 CHECK 钉死为两个费率码）。
2. 🔴 **9.2 审单不可达 ⇒ 503**（不是空集合 —— 空集合会被读成「这个买家没有风险」）；查无此人 ⇒ 允许登记并标「未知」。
3. **拦截标记 append-only、无撤销入口**（有意为之）—— 误拦放行仍需 9.2.1.3 的人工处置闭环，已写入能力声明。
4. **下游状态与预期不同**：grep 台账确认 **9.2.1.3 异常拦截** 与 **11.1.1.1 售后单列表** 均已交付（都在 trade 服务），
   但本模块**不代劳**它们的能力（不写对方表、不代建售后单），能力声明逐条标 `available=false` + `ownerModule`。
5. **上游 `buyer-risk-scores` 白名单条目一字未动**（含其「黑名单未交付」的旧注释）—— 本模块另开独立条目，避免与已并入内容产生无谓 diff。

---

## 模块 16.5.2.1 登录与访问安全

**这个功能做什么**：管平台账号和系统的访问安全 —— 谁能登、登了留痕、异常登录会告警、
IP 白名单管谁能进、会话怎么管、还有**平台授权凭据有没有被人偷看**。
凭据是最值钱的资产，所以每一次访问都要留痕，而且默认最小权限。

**技术落地**：

- 迁移：`backend/services/channel/src/main/resources/db/migration/V319__channel_access_security.sql`
  —— **纯新建 9 张 `access_security_*` 表、零 ALTER 任何既有表、零跨服务外键**。
  覆盖 MFA 绑定与挑战、登录审计、异常告警、IP 白名单、会话策略、凭据访问审计六条线；
  全部 `tenant_id NOT NULL` + ENABLE **与 FORCE** RLS。
  **凭据访问审计 append-only**：`GRANT` 只给 `SELECT,INSERT`，**刻意不给 UPDATE / DELETE**。
- 端点：`/channel/access-security/**` —— **22 条路径 / 28 个操作**：
  `AccessSecurityController` / `AccessSecurityService` / `AccessSecurityModels` / `AccessSecurityRules` / `AccessSecuritySourcePort`。
  `capabilities`（能力声明）/ `policy`（会话与安全策略 GET+PUT）/ `login-audit`（登录审计）+ `login-audit/scan`（扫描异常）/
  `alerts` + `alerts/{id}/{acknowledge|ignore|resolve}`（告警处置状态机）/
  `ip-allowlist`（白名单 CRUD）+ `ip-allowlist/check` + `ip-allowlist/evaluate`（真网段判定）/
  `mfa/registrations` + `mfa/registrations/{id}/{activate|disable}` + `mfa/challenges` + `mfa/challenges/{id}/attempts`/
  `credential-access` + `credential-access/{id}` + `credential-access/credentials`（凭据访问审计）/
  `session`（本服务侧会话策略）。
- 页面：`/compliance/access-security` —— `apps/web/src/app/compliance/access-security/page.tsx` +
  `apps/web/src/components/access-security/{access-security-model.ts, access-security-workspace.tsx, access-security-model.test.ts}`。
  筛选维度：告警严重度 / 处置状态 / IP 白名单命中情况 / 凭据；
  操作：登记与启停 MFA、增删白名单、扫描异常、处置告警、查凭据访问流水。三态齐全。
- 测试：`AccessSecurityIntegrationTest` 23 例 + `AccessSecurityTenancyIntegrationTest` 3 例 + `AccessSecurityRulesTest` 7 例 = **33 例**（全绿）；
  `AccessSecurityIdentityStub` 提供 identity 的跨服务只读桩；前端 17 例 + 共享白名单全量回归 139 文件 / 732 例全绿。

**关键设计决策**：

1. 🔴 **登录事件的真源头是 identity 的 `operation_log`**（`OperationLog.login` 写，event_type=LOGIN/LOGOUT），
   **不是 `identity_audit`**（后者记业务变更）—— 与任务书里的括号不符，按实查落地并写进台账与会话日志。
2. 🔴 **identity 的登录事件不含来源 IP**（`remoteHint` 恒为 null）⇒「按来源 IP 判异常」本仓量不到，
   能力声明 `available=false` + `ownerModule=17`；异常判定改用**实算**的「同账号窗口内失败 / 成功次数」。
3. 🔴 **identity 的 `auth_session` 仅四列且 `expires_at` 无读端点** ⇒ 会话到期 / 空闲 / 并发三维度读不到，如实标 false；
   本模块只管**本服务侧会话策略**，**不造第二套登录与会话系统**。
4. 🔴 **IP 白名单走 PostgreSQL `inet` 的 `<<=` 真网段包含运算**（不是字符串相等）；非法 CIDR 明确 400；
   **空白名单 ≠ 放行所有** —— ENFORCE 下切不进空白名单、也删不掉最后一条，守卫触发器 fail-closed。
5. 🔴 **上游不可达一律 503**；上游正常但无记录返回 `known=false` + 空集合（**无记录 ≠ 无风险**）。
6. **MFA 不做真实下发**（本仓无 MFA 服务商凭据），只登记下发 / 验证记录。
7. **被拒的访问不能被事务回滚带走**：404 / 403 在事务提交后才转，审计流水先落库。

---

## 模块 16.6.1.1 PII 管理

**这个功能做什么**：买家姓名、地址、电话、邮箱这些**个人信息**，默认都是**打码**的。
需要看完整信息时，要么你有权限，要么你去申请、被审批、批下来之后看 —— 而且**每一次看都要留痕**。
想批量导出更不行：得先申请、有人批、限着条数导出，批的人和申请的人不能是同一个。

**技术落地**：

- 迁移：`backend/services/channel/src/main/resources/db/migration/V320__channel_pii_access.sql`
  —— **纯新建 6 张 `pii_access_*` 表、零 ALTER 任何既有表、零跨服务外键**：
  `pii_access_policy`（脱敏与导出策略）/ `pii_access_grant`（按角色的查看授权）/
  `pii_access_view_audit`（访问留痕，**append-only**）/ `pii_access_export_request`（导出申请与审批）/
  `pii_access_export_item`（导出清单，**append-only**）/ `pii_access_export_batch`（导出凭据，**append-only**）。
  全部 `tenant_id NOT NULL` + ENABLE **与 FORCE** RLS + `GRANT channel_app` / `REVOKE starcon_app`。
  🔴 **本服务的表里不存买家 PII 明文副本** —— 只存授权配置、访问审计、导出审批与请求。
- 端点：`/channel/pii-access/**` —— **12 条路径 / 15 个操作**：
  `PiiAccessController` / `PiiAccessService` / `PiiAccessModels` / `PiiAccessRules` / `PiiAccessSourcePort`。
  `capabilities`（能力声明）/ `policy`（GET+PUT 策略）/ `grants`（GET 列表 + PUT 登记）/`grants/{grantId}/revoke`（撤销，理由必填）/
  `subjects`（主体列表）/ `subjects/{kind}/{ref}`（主体详情，**默认脱敏**）/
  `audit`（访问留痕）/ `exports`（GET 列表 + POST 申请）/`exports/{requestId}`（申请详情）/
  `exports/{requestId}/{approve|reject|execute}`（审批 · 驳回 · 执行）。
- 页面：`/compliance/pii-access` —— `apps/web/src/app/compliance/pii-access/page.tsx` +
  `apps/web/src/components/pii-access/{pii-access-model.ts, pii-access-workspace.tsx, pii-access-model.test.ts}`。
  界面上能看出**脱敏态与完整态的区别**，以及**谁授权、为什么能看到完整值**；三态齐全。
- 测试：`PiiAccessRulesTest` 18 例 + `PiiAccessIntegrationTest` 25 例 + `PiiAccessTenancyIntegrationTest` 4 例 = **47 例**（全绿）；
  `PiiAccessUpstreamStub` 提供 crm / trade 两个跨服务只读桩；前端 15 例 + 共享白名单全量回归 140 文件 / 742 例全绿。

**关键设计决策**：

1. 🔴 **默认脱敏是硬默认，不是可选开关** —— 掩码不是把字段置空，也不是可逆编码：
   姓名保留姓氏、电话保留末位、邮箱本地部分掩码、地址保留到区级。**掩码逻辑由测试直接断言返回值里不含完整明文**。
2. 🔴 **批量导出 PII 是数据泄露事件的典型形态** ⇒ 导出**必须走审批**，未审批的导出请求一律拒绝；
   **审批人 ≠ 申请人**（DB 层 CHECK 钉死，自己批自己形同虚设）；**导出要限量** + **要留痕**。
   **物理上没有「一键全量导出」形状** —— 清单条数 = 申请条数由**延迟约束触发器在提交那一刻**核对，
   触发器挂在**申请行**上而非清单行（挂错位置则「一条都没插」没人拦）。
3. 🔴 **被拒的请求由控制器在事务提交后才转 403** —— 服务层在事务里已把越权尝试写进审计表，
   在事务里抛异常会**把越权留痕一起回滚掉**；替代方案 `REQUIRES_NEW` 在池压到 1 的多租户测试里会死锁。
4. 🔴 **SELF 范围对 ORDER 主体「判不了 ⇒ 拒绝」** —— trade 的订单与地址快照没有经手人字段（crm 的接触记录有 `operator_id`）；
   拒绝理由写进审计，调用方可区分「不是本人经手」与「判不了」。
5. **上游不可达一律 503**，绝不返回空集合或空字符串冒充（空值会被读成「这个人没有 PII」）；
   上游正常但查无此人返回 `null` + `known=false`（**无记录 ≠ 无 PII**）。
6. `actor_name` 暂空（identity `/internal/access` 不返回显示名，**本模块不改 identity**，跨车道回归面 = 0）⇒ 界面显示成员 id 短码。

---

## L2 批量验证（集成头上跑一次，整组 4 条车道）

**结果：🟢 全绿。** `L2 START 05:33:51` → `L2 GREEN 06:22:04`（整组 4 条车道一次跑完，**全量、全绿**）。

| 步骤                                 | 命令                                                                              | 结果                                                                      |
| ------------------------------------ | --------------------------------------------------------------------------------- | ------------------------------------------------------------------------- |
| 1/5 后端全量                         | `cd backend && ./mvnw verify`                                                     | ✅ **8864 例，0 failures / 0 errors / 0 skipped**（05:33:51 → 06:20:11）  |
| 2/5 类型检查                         | `pnpm typecheck`                                                                  | ✅ 3 包全绿                                                               |
| 3/5 Lint                             | `pnpm lint`                                                                       | ✅ 3 包全绿（本批文件零告警）                                             |
| 4/5 前端全量                         | `pnpm test`                                                                       | ✅ **510 文件 / 7281 例全过**                                             |
| 5/5 Next production build + 台账校验 | `NEXT_TELEMETRY_DISABLED=1 pnpm --filter @starcon/web build` + `sync.mjs --check` | ✅ 306/306 静态页生成完毕，路由表完整输出；**✓ 台账与蓝图一致**（exit 0） |

后端分模块明细（全部 `Failures: 0, Errors: 0, Skipped: 0`）：
identity **270** / catalog **1981** / channel **2099** / warehouse **1640** / trade **2383** / crm **491**。

**本批 4 个模块的 11 个测试类在 L2 全量里全部实跑通过**（不是只在各自车道里绿）：
`IpDetectionRulesTest` / `IpDetectionIntegrationTest` / `IpDetectionTenancyIntegrationTest` /
`BuyerBlacklistIntegrationTest` / `BuyerBlacklistTenancyIntegrationTest` /
`AccessSecurityRulesTest` / `AccessSecurityIntegrationTest` / `AccessSecurityTenancyIntegrationTest` /
`PiiAccessRulesTest` / `PiiAccessIntegrationTest` / `PiiAccessTenancyIntegrationTest`。

**这一轮 L2 真正抓到了什么**：这 4 条车道**全部落在同一个 `channel` 服务**，
且 4 条车道都新增了 `services[]` 白名单条目、都动了同一份 `api-proxy.ts`、都重跑了同一份契约 codegen。
组批协议存在的意义正在于此 —— 单跑全绿的东西并到一起仍可能互相破坏，
而**合并后的集成头状态此前从来没有被任何人验证过**。本轮 8864 + 7281 例全部实跑，等于第一次给这个状态上了保险。

**L2 期间的温度与资源**：全程 `pmset -g therm` 无降频项；后端阶段 Testcontainers PG 容器峰值受
「每服务串行」约束（每个测试类一个静态容器、用完即由 ryuk 回收），未出现并发抢资源导致的成片假红；
L2 结束时 `postgres:16-alpine` 与 `ryuk` 计数均为 **0**。

**无二分**：L2 一次通过 ⇒ 未触发 `git bisect`（协议允许最多 2 次全量验证二分，本轮 0 次）。

## 遗留与下一步

- **能力声明里逐条标出的下游缺口**（非本批职责）：16.2.1.2 拦截已交付、16.4.2 授权核验由 16.4.2.1 / 16.4.2.2 承担、
  9.2.1.3 拦截与 11.1.1.1 售后已交付但不由本批代劳、16.6.2.1 数据出境与留存未交付 ⇒ PII 模块不写数据出境登记与被遗忘权删除。
- **平台侧真实能力未接通**（如实标注 `available=false`）：外部商标 / 专利库外呼、图像指纹识别、MFA 真实下发、
  identity 登录事件的来源 IP 与会话到期读端点。
- **卷 12 / 13 / 18 / 卷 1 仍被服务缺口堵死**：`finance` / `reporting` / `platform` / 前端聚合 四个主责服务不在集成头，
  本轮 `plan` 输出的 `serviceBlocked` 已点名这四卷（共 68 个模块待解锁）。
- **下一轮建议**：优先补 `finance` / `reporting` 两个服务的落地，才能解锁卷 12（财务）与卷 13（报表）共 26 个未开发模块。
