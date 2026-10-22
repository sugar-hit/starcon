# batch-69 · 卷 16 合规与风控 ×4（4 条车道并入 + L2 全量验证）

> 本轮编队：2026-10-23 03:00 tick。4 条车道并行开发，`LANES=4`（MODE=full，`TOKEN_HORIZON=2026-10-25` 未到）。
> 调度闸：本轮开场 03:01 取闸，03:16 释放（让 sync-main 任务推进第 39 轮纪要），03:28 重新取闸并持到 L2 + 三件套完成。
> 静态预检：本批 4 个新迁移**零命中**互相破坏形状（无 `ADD COLUMN` 无 DEFAULT / 无 `SET NOT NULL` / **无一条 `ALTER TABLE`**，触发器一律用 `CREATE TRIGGER` 挂载）。
> 合并产物已独立校验：`channel.openapi.json` 合法 JSON、paths +41 / schemas +90 / **删除 0**、1487 个共有 schema 语义零变化、float 字面量 `0.0` 完整保留（batch-68 的塌陷缺陷未复发）。

## 批次构成

| 车道     | 模块       | 服务    | 迁移 | 分支            | commit     |
| -------- | ---------- | ------- | ---- | --------------- | ---------- |
| 16.2.1.2 | 拦截执行   | channel | V313 | `feat/16-2-1-2` | `758b2d07` |
| 16.3.2.2 | EPR/包装法 | channel | V314 | `feat/16-3-2-2` | `6d5c9a93` |
| 16.4.1.1 | 侵权库     | channel | V315 | `feat/16-4-1-1` | `5902cf71` |
| 16.5.1.2 | 风险评分   | channel | V316 | `feat/16-5-1-2` | `8c03f805` |

**api-proxy 锚点互不重叠**（本批 4 条车道都改 `apps/web/src/lib/api-proxy.ts`）：
16.2.1.2 → `compliance-rules` 之后／16.3.2.2 → `platform-appeals` 之后／
16.4.1.1 → `cart-recovery` 之后／16.5.1.2 → `service-ticket-process` 之后（数组末尾）。
四条车道各自**纯新增一条独立 `services[]` 元素**，既有条目一字未动、也不重排 ⇒ git 能干净求并。
合并后实测：白名单条目 **165 → 169**，四条新条目落在 8338 / 8494 / 8585 / 8687 四处互不相邻的位置。
首 token 全部避开既有命名空间：`compliance-intercepts` / `epr-registrations` / `ip-risk-library` / `buyer-risk-scores`。

---

## 模块 16.2.1.2 拦截执行

**这个功能做什么**：商品要刊登、要发货、账户要提现之前，系统先拿合规规则库跑一遍判定。
命中阻断规则的**当场拦住**，并把「哪条规则、为什么拦、怎么才能解开」讲清楚 ——
运营可以**申请豁免**（写理由、指定有效期），但豁免**必须有人审批**，而且申请人不能自己批自己。
豁免批下来之后，后续判定仍然会在结论里写明「这次是凭哪张豁免单放行的」。

**技术落地**：

- 迁移：`backend/services/channel/src/main/resources/db/migration/V313__channel_compliance_intercept.sql`
  —— **纯新建 4 张表，零 ALTER 既有表、零外键**：`compliance_intercept_policy`（拦截策略）/
  `compliance_intercept_decision`（判定凭据）/ `compliance_intercept_exemption`（豁免单）/
  `compliance_intercept_reason`（具名原因码）+ 4 个触发器。
  命名空间 `compliance_intercept_*`，与上游 `compliance_rule_*`（16.2.1.1）、`compliance_check_*`（16.2.2.1）、
  `listing_compliance_*`（4.2.3.2）、`product_certification_*`（16.2.2.2）、`brand_authz_*` / `brand_cred_*` 彼此隔离。
  全部 `tenant_id NOT NULL` + ENABLE **与 FORCE** ROW LEVEL SECURITY + GRANT `channel_app` / REVOKE `starcon_app`。
- 端点：`/channel/compliance-intercepts/**` —— `ComplianceInterceptController` /
  `ComplianceInterceptService` / `ComplianceInterceptRules` / `ComplianceInterceptModels`；
  跨模块接入点 `ListingPublishJobService.java`（派发 / 重试路径上加服务端闸门）。
- 页面：`/compliance/intercept` —— `apps/web/src/app/compliance/intercept/page.tsx` +
  `apps/web/src/components/compliance-intercept/`（model + workspace + model.test），
  **独立于** `/compliance/rules` / `/compliance/account` / `/compliance/violations` / `/compliance/appeals`。
- 测试：`ComplianceInterceptRulesTest` 14 例 · `ComplianceInterceptIntegrationTest` 21 例 ·
  `ComplianceInterceptTenancyIntegrationTest` 3 例 = **38 例全绿**；前端 model 12 例 + allowlist 4 例 = 16 例。
  邻接回归（改了 `ListingPublishJobService`）：`ListingPublishJob*` / `ListingPublishTask*` **39 例 0 失败**。

**关键设计决策**：

1. **结论是三态 + 一个「判不了」**：通过 `PASS` / 警告 `WARN` / 阻断 `BLOCK`，另加 `UNDECIDABLE`。
   规则读不到、适用版本查不到、规则集为空时**一律不许返回 PASS** —— 那是放行，不是判定。
   `UNDECIDABLE` 时 `release_granted=false`。
2. **判定冻结「当时生效的那一版规则」**（`rule_version_id` 随判定凭据落库），
   规则变更**不追溯改变历史判定** —— 这是 16.2.1.1 立规则版本化的理由，判定侧必须兑现。
3. **阻断在服务端强制**，不是前端显示红字：判定凭据写在 SAVEPOINT **之外**，
   即便判定过程出错也不会把「已判为阻断」这件事回滚掉。
4. **豁免五道闸**（蓝图原话「豁免必须有审批与理由」，不许弱化）：终态不可回退 /
   **申请人 ≠ 审批人** / 申请人理由与审批意见**都必填** / **有效期必填**（豁免不能永久生效）/ 一张豁免单只针对一条规则。
5. **不放行必须有原因**：用延迟约束触发器兜底，原因码走**具名 CHECK** ——
   匿名 CHECK 的报错只会说 `check2`，读报错的人根本不知道哪条口径被违反。
6. **拦截默认不生效**（租户显式开启才拦）：避免把「还没开合规」当成违规把刊登链打挂，
   也避免打破已交付车道的既有测试。这是产品判断，已列入会话日志待办。
7. **越界说明（本批唯一一处）**：为落实「放行路径必须真的读服务端结论」，
   在 4.3.1.1 的 `ListingPublishJobService.java` 加了 **1 个构造参数 + 2 处闸门调用**，
   **未改其数据模型与端点契约**，且策略默认关闭 ⇒ 行为不变，邻接 39 例全绿。
8. **非词条类规则不给默认通过**：上游规则版本没有结构化的「要求载荷」，
   于是判定请求带「要求状态」，**不给即判不了**，不默认通过。
9. **未交付下游如实标**：8.3.4.1 发货（warehouse，跨服务 ⇒ 只出结论，不跨服务 SQL / 不造跨库 FK）、
   12 结算提现（**finance 服务在本仓尚未落地** ⇒ 不建财务表、不声称已拦截提现）、
   4.3.1.2 批量发布通道（归其自己接）—— 能力声明 `available=false` + `ownerModule` + 影响说明。

---

## 模块 16.3.2.2 EPR/包装法

**这个功能做什么**：德国 LUCID、包装注册、电池法、WEEE 这些 EPR 制度，
每个国家、每个品类要不要注册、注册号是多少、申报周期、一次申报要花多少钱，系统替你记着并算出**当前合不合规**。
**没注册的国家直接限制刊登** —— 这是这个模块存在的理由：与其等平台下架罚款，不如在刊登前就拦住。
页面还会给出「这个国家这个品类到底有没有被 EPR 覆盖」的查询口径，以及各制度的申报成本。

**技术落地**：

- 迁移：`backend/services/channel/src/main/resources/db/migration/V314__channel_epr_registration.sql`
  —— **纯新建 9 张表，零 ALTER 既有表、零外键**：`epr_regime` / `epr_category` / `epr_country_regime` /
  `epr_country_regime_category` / `epr_registration` / `epr_registration_country` / `epr_registration_category` /
  `epr_exemption` / `epr_fee` + 9 个触发器 + 参考数据种子 + **唯一判定源函数 `epr_gate_findings(uuid, varchar, varchar)`**。
  命名空间 `epr_*`，与上游 `tax_registration_*`（16.3.1.1）及其他卷的 `compliance_*` / `ip_risk_*` 彼此隔离。
- 端点：`/channel/epr-registrations/**` —— **17 条路径 / 21 个操作**；
  `EprRegistrationController` / `EprRegistrationService` / `EprRegistrationRules` / `EprRegistrationModels`。
- 页面：`/compliance/epr` —— `apps/web/src/app/compliance/epr/page.tsx` +
  `apps/web/src/components/epr-registration/`（model + workspace + model.test），
  **独立于** `/compliance/tax`（16.3.1.1）与其他合规页。
- 测试：`EprRegistrationRulesTest` 15 例 · `EprRegistrationIntegrationTest` 28 例 ·
  `EprRegistrationTenancyIntegrationTest` 3 例 = **46 例全绿**；前端定向 19 例。

**关键设计决策**：

1. **判定只有一个源**：端点与 DB 触发器**共用同一个 `epr_gate_findings` 函数**。
   两处各写一套判定逻辑必然分叉 —— 页面说合规、DB 触发器说阻断，或反过来，是最难查的一类 bug。
2. **「未注册 ⇒ 限制刊登」落在 DB 层触发器**（挂 `listing_publish_job_target`→`SUBMITTED` 与 `publish_task`→`DISPATCHED`），
   绕过服务层直接写库也拦得住 —— 服务端强制才有意义，前端提示不算。
3. **五态而不是三态**：`NOT_APPLICABLE` / `EXEMPTED` 放行；`NOT_REGISTERED` / `EXPIRED` 阻断；
   `VALIDITY_UNKNOWN` / `NOT_EFFECTIVE_YET` / `DISABLED` 属「判不了」但**仍然阻断** ——
   判不了就放行，等于用「我不知道」换「放你过去」。
4. **与上游 16.3.1.1 是粗 / 细两层，不是同一份事实的两个副本**：
   上游 `tax_registration_record.epr_registration_no` 是**辖区级**粗粒度登记，
   本模块的粒度是**「制度 × 适用国家 × 适用品类」**。本模块**不写**上游表、**也不复制**上游那个号当自己的权威源。
5. **DB 层闸门目前只能按国家维度**：刊登入口只提供平台的 `category_path`，
   在 DB 层猜品类映射就是编造 ⇒ 品类维度留给服务层 `publish-gate`，两端口径都在前端显示。
6. **`epr_country_regime` 只列确知制度要求**：未列的国家一律 `NOT_APPLICABLE` 放行 ——
   参考数据要随业务知识补充，不能反过来把没收录的国家一律当违规。
7. **金额**一律 `BigDecimal` + `NUMERIC`，带币种，按币种分组汇总，**不把不同币种直接相加**。
8. **未交付下游如实标**：12 财务（**服务不存在** ⇒ 费用只记本服务自己的账，不建凭证、不声称已入账）、
   16.3.2.1 VAT/IOSS、16.3.2.3 销售税、16.3.1.2 申报日历 —— `available=false` + `ownerModule`。

---

## 模块 16.4.1.1 侵权库

**这个功能做什么**：运营在这里维护一份「哪些名字/关键词/标识不能随便用」的风险清单 ——
品牌商标、专利、版权、肖像权四类，每条登记风险等级和适用平台。
可以**批量导入**整份清单，导入完能逐行看到哪一行没进去、为什么没进去；
用不上的条目**停用**（写明理由），而不是删掉 —— 别的模块可能已经引用过它。

**技术落地**：

- 迁移：`backend/services/channel/src/main/resources/db/migration/V315__channel_ip_risk_library.sql`
  —— **纯新建 5 张表，零 ALTER 既有表、零外键**：`ip_risk_entry` / `ip_risk_entry_platform` /
  `ip_risk_match_term` / `ip_risk_import_batch` / `ip_risk_import_row` + 9 个触发器。
  命名空间 `ip_risk_*`，与 `brand_authz_*` / `brand_cred_*`（16.4.2.x）、`compliance_rule_*`、
  `compliance_intercept_*`、`epr_*`、`listing_compliance_*` 彼此隔离。
- 端点：`/channel/ip-risk-library/**` —— capabilities / platforms / brands / entries（GET 真分页 + 真筛选，
  POST 按条目编码**幂等**）/ entries/{id}（GET、PUT 整体替换）/ entries/{id}/status（PUT，停用必写理由）/
  imports（POST）/ imports/{id} / platform-summary；
  `IpRiskLibraryController` / `IpRiskLibraryService` / `IpRiskLibraryRules` / `IpRiskLibraryModels` /
  `IpRiskLibrarySourcePort`。
- 页面：`/compliance/ip-risk` —— `apps/web/src/app/compliance/ip-risk/page.tsx` +
  `apps/web/src/components/ip-risk-library/`（model + workspace + model.test），
  **独立于** `/compliance/brand-authorization` / `/compliance/brand-credential` 等。
- 测试：`IpRiskLibraryRulesTest` 6 例 · `IpRiskLibraryIntegrationTest` 13 例 ·
  `IpRiskLibraryTenancyIntegrationTest` 3 例 = **22 例全绿**；前端 model 10 例 + allowlist 5 例 = 15 例。

**关键设计决策**：

1. **权利类型与风险等级都是受控枚举**（`TRADEMARK` / `PATENT` / `COPYRIGHT` / `IMAGE_RIGHT`；
   高 / 中 / 低）—— DB 层 CHECK 钉死，**不用 0..100 的裸数字**当风险等级。
2. **适用平台是多对多**（独立表 + 平台目录触发器校验），能反查「这个平台上有哪些高风险条目」。
3. **匹配特征是本模块的关键交付**：16.4.1.2 检测要拿它去比对商品标题 / 描述 / 图片 / 品牌字段，
   所以条目必须带**可被匹配的字段**（品牌名可空 + 关键词短语集合 + 可选标识符/外观特征）。
   **本模块只登记这些特征，不执行任何比对。**
4. **批量导入要行级结果**：一次导入返回成功行数 / 失败行数 + **逐行错误**（哪一行、哪个字段、为什么），
   不是「整个文件成功 / 失败」二选一。
5. **导入幂等 + 部分失败不回滚成功行**：用稳定业务键 + 唯一约束（不靠「先查后插」的竞态写法），
   逐行 savepoint 让 DB 层报错**只回滚那一行**。
6. **不做物理删除**：条目只停用（要写理由），停用后历史记录保留。
7. **跨服务只经授权 HTTP API 读品牌**：`IpRiskLibrarySourcePort` 照抄 `BrandAuthorizationSourcePort` 的形状
   （`@Service` 类 + `RestClient` + **转发调用方自己的 bearer**、**绝不传租户号头**），
   **读不到就显式说读不到**（`available=false` + 人话原因），**绝不返回空集合冒充「上游一个数据都没有」**。
   既有代码里两个 URL 默认值写错端口的老坑没有照抄。
8. **16.4.1.2 检测与预警未交付** ⇒ 不实现检测逻辑、不产出任何检测结论、不建检测结果表，
   页面也没有「立即检测 / 放行」入口 —— **不编造指向不存在功能的假入口**。
9. **台账只改自己那一行**：中途曾用字符串批量替换误碰了别的车道那一行，已自查逐行回滚，
   复核 diff 收敛为**仅 16.4.1.1 一行增删**（合并产物已独立复核）。

---

## 模块 16.5.1.2 风险评分

**这个功能做什么**：给买家打一个 0–100 的风险分，并**逐条解释这个分是怎么来的** ——
历史退款率、纠纷率、欺诈特征、地址模式、下单行为、关联账号，各占多少权重、各贡献了几分、
以及「为什么这个买家 80 分」的人话理由。最后给出建议动作（放行 / 人工复核 / 扣住）。
客服拿着这个分能向平台申诉，**黑盒分数做不到**。

**技术落地**：

- 迁移：`backend/services/channel/src/main/resources/db/migration/V316__channel_buyer_risk_score.sql`
  —— **纯新建 4 张表，零 ALTER 既有表、零外键**：`buyer_risk_rule`（权重配置）/
  `buyer_risk_score`（评分凭据）/ `buyer_risk_feature`（逐特征贡献明细）/
  `buyer_risk_signal_writeback`（回写 9.2.2.2 的记录）+ 5 个触发器。
  命名空间 `buyer_risk_*`，与 trade 库的 `order_risk_*` / `order_review_buyer_profile`、
  crm 库的 `crm_*`、channel 库其余合规表**彼此隔离**。
- 端点：`/channel/buyer-risk-scores/**` —— capabilities / rule（GET + PUT）/ buyers /
  evaluate（POST）/ scores（GET）/ scores/{id}；
  `BuyerRiskScoreController` / `BuyerRiskScoreService` / `BuyerRiskScoreRules` /
  `BuyerRiskScoreModels` / `BuyerRiskScoreSourcePort`。
- 页面：`/compliance/buyer-risk` —— `apps/web/src/app/compliance/buyer-risk/page.tsx` +
  `apps/web/src/components/buyer-risk/`（model + workspace + model.test）。
- 测试：`BuyerRiskScoreIntegrationTest` 17 例 · `BuyerRiskScoreTenancyIntegrationTest` 3 例 = **20 例全绿**；
  前端定向 12 例。`BuyerRiskTradeStub` 是**真实 HTTP 服务器**，不是 mock。

**关键设计决策**：

1. **主体键 = `buyer_key`**（收件人身份三件套的 md5，分隔符 `U+001F`，
   与 trade `order_review_buyer_profile.buyer_key` **逐字节一致**）。
   **不用** `customer_party_id`（B2B 主体口径，平台 B2C 买家绝大多数没有 `party_id`），
   **更不用** crm 的 `customer_ref`（**crm 本仓没有买家档案**，它只是会话键与筛选键）。
2. **纠纷率 = 量不到**（真实缺口，不是绕得过去的）：trade `platform_dispute`
   **既没有 `order_id` 也没有任何买家标识**，只有一个自由文本 `platform_order_no`。
   靠字符串模糊匹配去凑纠纷率，会算出看似有数、实则不可信的分数 ⇒
   该维度 `available=false` + `ownerModule=11.4.1.1`，**不进分母**，结论里显式说明「纠纷率未计入」。**绝不用 0 冒充「纠纷率 0」。**
3. **关联账号维度量不到**（ownerModule `16.5.2.2`），且**刻意不给它权重列** ——
   一个永远量不到的维度留在权重表里，只会稀释其余维度的权重。
4. **「贡献分之和 = 总分」由数据层保证**：`DEFERRABLE INITIALLY DEFERRED` 约束触发器钉住，
   对不上直接报 23514；不出分的评估必须贡献为零。
5. **「缺特征」与「特征为 0」是两件事**：读不到的特征不进已知特征数、**不算通过**。
   把「没数据」写成「这买家很干净」是最危险的一种撒谎。
6. **缺信号口径由租户显式选**（`SCORE_KNOWN_ONLY` / `BLOCK_EVALUATION`），不给默认值；
   选「判不了就阻断」时**不出分**，出「信号不足，无法评分」的结论。
7. **权重改了不追溯改变历史评分**：评分凭据与特征明细**append-only**，权重快照冻在行里。
8. **回写 9.2.2.2 是尽力而为**：只回写 `REFUND_RATE`（`signalSource=TENANT_MAINTAINED`，
   `sourceReference` 指回本模块的评分凭据 id）。
   **不占用** `MODULE_15_CUSTOMER_PROFILE` / `MODULE_16_RISK_LINKAGE` 那两个预留槽（那是别的模块的），
   **不改 trade 的枚举**（综合分在 trade 侧没有对应 `signal_code`，那是跨服务改已交付服务 DDL 面）。
   回写失败（权限不足 / trade 不可达）**不阻塞本模块交付**，降级为「未回写」并写明原因。
9. **订单集合的口径与它的代价**：trade **没有「按 buyer_key 查订单」的读面**，
   所以按规则回看窗口（默认 180 天）扫 sales-orders 列表、并从**冻结的收件地址快照**算 `buyer_key`。
   为此**任一订单地址解析失败 ⇒ 三个订单派生维度本轮标为量不到** ——
   订单集合不全会**低估**退款率，宁可标「量不到」也不能给出一个偏低的好看分数。

---

## L2 批量验证（集成头上跑一次，整组 4 条车道）

> 本批新迁移静态预检**零命中**互相破坏形状；合并产物已独立校验（见文首）。
> 本批**改变了契约**（`packages/api-contracts/src/channel.openapi.json` + `contracts/channel.ts`），
> 而契约不是 workspace 包、不在 turbo 的 input hash 里 ⇒ typecheck 可能命中旧缓存假绿 ⇒ 本轮**额外跑了 `FORCE_TYPECHECK=1`**。

**L2 结果：`L2 GREEN`（`L2_EXIT=0`，2026-10-23 03:35:12 → 04:23:50，耗时 48 分 38 秒）**

| 闸              | 命令                                                                                                                 | 结果                                                                                                                                                                                                                                       |
| --------------- | -------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| [1/6] 后端全量  | `backend/./mvnw verify`                                                                                              | **MVN_EXIT=0** —— 8 个模块全 SUCCESS（backend / tenancy / identity / catalog / channel / warehouse / trade / crm），**8693 例 0 失败 0 错误 0 跳过**（catalog 1981 · channel 1928 · trade 2383 · warehouse 1640 · crm 491 · identity 270） |
| [2/6] 前端      | `pnpm typecheck && pnpm lint && pnpm test`                                                                           | **TYPECHECK_EXIT=0 / LINT_EXIT=0 / TEST_EXIT=0** —— vitest **502 文件 / 7207 例全绿**                                                                                                                                                      |
| [3/6] Next 构建 | `NEXT_TELEMETRY_DISABLED=1 pnpm --filter @starcon/web build`                                                         | **BUILD_EXIT=0**                                                                                                                                                                                                                           |
| [4/6] 台账一致  | `node tools/module-progress/sync.mjs --check`                                                                        | **SYNCCHECK_EXIT=0**（「✓ 台账与蓝图一致」）                                                                                                                                                                                               |
| [5/6] 回归套件  | `merge-union.itest.mjs` + `python3 -m unittest discover -s tools/codegen`                                            | **ITEST_EXIT=0 / CODEGEN_EXIT=0**                                                                                                                                                                                                          |
| [6/6] 契约闸    | `catalog-contracts.py --spec-dir backend/target/openapi --out-dir .tmp/l2-contract-gate --no-prettier` + 逐字节 diff | **CONTRACT_GENERATE_EXIT=0 / CONTRACT_GATE_EXIT=0 / CONTRACT_TREE_DRIFT_EXIT=0**（工作区契约产物与生成器输出**逐字节一致**，与 HEAD 零漂移）                                                                                               |
| 附加            | `pnpm turbo run typecheck --force`（绕开 turbo 缓存）                                                                | **FORCED_TYPECHECK_EXIT=0**                                                                                                                                                                                                                |

**合计 15900 例测试全绿**（后端 8693 + 前端 7207），本批 4 个模块的 126 例新测试（38 + 46 + 22 + 20）全部包含在内。

**跨车道互破坏专项复核**（本批两条车道都碰刊登路径，故在 L2 之前先跑了一轮定向闸）：
`-Dtest='ListingPublishJob*,ListingPublishTask*,ComplianceIntercept*,EprRegistration*,IpRiskLibrary*,BuyerRiskScore*'`
⇒ **165 例 0 失败 0 错误，BUILD SUCCESS**，且 Testcontainers 一次性把 **V313→V316 四个迁移顺序应用成功**（82 个迁移，1.75 秒）。
⇒ 16.3.2.2 的 EPR 刊登闸门 DB 触发器**没有**破坏已交付的刊登路径测试；
16.2.1.2 对 `ListingPublishJobService` 的改动**没有**破坏 4.3.1.1 的既有测试。

**契约合并产物独立校验**（在 L2 之前做，因为 batch-68 就是在这一步炸的）：
`channel.openapi.json` 合法 JSON；paths **+41 / -0**、schemas **+90 / -0**；
1487 个共有 schema **语义零变化**；float 字面量 `"minimum": 0.0` **19 处全保留**（基线 16 + 本批新增 3）
⇒ batch-68 的「`0.0` 塌成 `0`」缺陷**未复发**（求并已在 2026-10-23 改到 Python 侧，与 codegen 共用同一个 `json.dumps`）。
另做了 merge-union 干跑：base/ours/theirs 三方求并 → exit 0，9 条路径 + 23 个 schema 全部保留、零删除。
台账侧：合并后 465 行**一行不多一行不少**，本批 4 行状态均为「已交付」。

---

## 遗留与下一步

1. **12 财务 / 13 报表 / 18 平台三个服务在本仓不存在** ⇒ 未开发模块里 **102 个（占 133 的 77%）**被服务缺口堵死，
   当前真正可排的只剩 **31 个**。按每批 4 条算，约 8 批后流水线会排不出车道而空转 —— **需要人工决定**：
   先落地这三个服务，还是调整这 4 卷的「后端主责服务」归属。
2. **16.5.1.2 的两个上游缺口**：11.4.1.1 平台纠纷需要 join key（有了之后 `DISPUTE_HISTORY` 权重位现成可用，不用改表），
   16.5.1.1 黑名单与 16.5.2.2 关联风险会各自带来新的维度与权重。
3. **16.5.1.2 的读放大**：每个订单一次详情调用，受租户可配的回看窗口约束；
   正解是 trade 侧提供「按 buyer_key 查订单」的端点，而不是在本服务加缓存。
4. **16.3.2.2 的品类维度**：DB 层闸门目前只能按国家维度，待 03 商品类目落地后可细化。
5. **16.2.1.2 的拦截默认关闭**：租户需显式开启才拦，这是产品待确认项。
