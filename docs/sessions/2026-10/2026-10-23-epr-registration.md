# 16.3.2.2 EPR/包装法（阶段 D7 · L3 锚点 16.3.2）

- **日期**：2026-10-23（Asia/Shanghai）
- **车道**：`feat/16-3-2-2`（worktree `/Users/guan/Projects/starcon-worktrees/integration/.worktrees/16.3.2.2`）
- **基线**：`integration/module-dev` @ `69a76998`
- **迁移版本号**：`V314__channel_epr_registration.sql`（预分配；同批另外三条车道占用 V311 / V313 / V315 一带）

## 决策摘要

蓝图原文（`docs/product/capability-blueprint/16-compliance-and-risk.md` 第 161-166 行）：

> ### 16.3.2.2 EPR/包装法
>
> - **职责**：管理 EPR 与包装法合规（德国 LUCID、包装注册、电池法、WEEE 等）：注册号、适用国家与品类、申报周期与关联费用。
> - **输入**：注册信息；适用规则；申报数据。
> - **输出**：EPR 档案 + 合规状态 + 费用记录。
> - **关系**：上游 = 16.3.1.1；下游 = 12 财务（费用）、4 刊登（是否允许在相关国家销售）。**EPR 未注册的国家应直接限制刊登**，这是避免下架与罚款最有效的做法。

本车道的落地要点：

1. **「限制刊登」真的落地，而且判定只有一个来源。**
   DB 函数 `epr_gate_findings(tenant, country, category)` 是**唯一的判定源**；
   服务层的 `publish-gate` 端点与 DB 层的刊登触发器**调同一个函数** ⇒
   页面上的结论与真正拦人的那道闸门**不可能各说各话**。
   DB 层触发器挂在 4 刊登的**两个真实放行口**上：
   `listing_publish_job_target`（4.3.1.1 一键刊登 → `SUBMITTED` = 真发给平台）与
   `publish_task`（4.3.1.2 批量发布 → `DISPATCHED` = 真派发）——
   **只加函数、零 ALTER 既有表的任何列**（与 V198 的 `guard_listing_editor_submission_compliance` 同一手法）。
2. **判定有三种结果，不是两种。**
   `NOT_APPLICABLE`（该国不要求该制度 ⇒ 放行）/ `NOT_REGISTERED`（要求了但没注册 ⇒ 阻断）/
   **「判不了」**（没记有效期 / 没有生效版本 / 登记已停用 ⇒ **仍然阻断**，但原因单独列）。
   把 `NOT_APPLICABLE` 当成未注册，会把不该拦的全拦了 —— 这条是本模块最容易做丢的分界线。
3. **制度值域钉死在 DB 层。**
   `epr_regime` 是全局码表，值域由 **CHECK** 钉死为 `LUCID` / `PACKAGING_REGISTRATION` / `BATTERY` / `WEEE` 四种；
   登记档案上的 `regime_code` 由**触发器**校验必须落在码表里（CHECK 看不到别的表，钉不住）。
   **新增制度走 DB 迁移加值域，不许运行时塞字符串。**
4. **登记是版本化的。**
   改一条登记 = 关掉当前版本（`effective_to`）+ 插入新版本（`effective_from` / `version_no` / `supersedes_id`），
   **生效时刻不许回填**（触发器钉死 + 服务层 422 人话）⇒「历史判定按当时那版算」**物理成立**。
   已关闭的版本**连事实都不许再改**（DB 触发器直接拒）。
5. **与上游 16.3.1.1 是「粗 / 细」两层，不是同一份事实的两个副本。**
   上游 `tax_registration_record.epr_registration_no` 是**辖区级**（一个辖区一个号）；
   本模块是**制度级**（一个**制度 × 国家 × 品类**一个号）。
   ⇒ 本模块**不写** `tax_registration_record`（上游写所有权不变），
   也**不把**上游那个号复制一份当自己的权威源。能力声明里写清这层关系。
6. **本模块不含财务凭证。**
   12 财务在本仓**尚未落地** ⇒ `epr_fee` 只是**本服务自己的费用记录**（谁付的、哪个周期、多少钱、什么币种），
   **不建凭证表、不声称费用已入账**。金额 `numeric(19,4)` + **币种必带**，汇总**按币种分组**。

## 关键 trade-off

| 决策点                                                | 取舍                             | 理由                                                                                                                                                                                                                            |
| ----------------------------------------------------- | -------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 判定实现位置：Java 里算 vs **DB 函数**                | **DB 函数 `epr_gate_findings`**  | 「放行路径必须真的读服务端结论」。DB 函数让**服务层端点与 DB 触发器共用同一份判定**，杜绝两套实现漂移。Java 侧只做展示口径与编排                                                                                                |
| 闸门拦在哪：前端提示 vs **DB 触发器**                 | **DB 触发器**（两个真实放行口）  | 蓝图加粗句是硬要求；前端显示不是防线。触发器只加函数、零 ALTER 既有表，跨车道 DDL 回归面 = 0                                                                                                                                    |
| 制度粒度：沿用上游辖区级号 vs **制度级**              | **制度级独立拥有**               | 同一个国家下 LUCID / 包装注册 / 电池法 / WEEE 各有自己的注册号、申报周期与费用。粗 / 细两层，不互相复制                                                                                                                         |
| 「不适用」怎么定：只看参考表 vs **参考表 + 租户声明** | **两者都要**                     | 参考表管「该国要求哪些制度」；租户声明管「该制度在我的业务里适不适用」（例：只卖服装的卖家在德国不需要 WEEE）。只有参考表会把不该拦的全拦了；只有声明会漏拦                                                                     |
| 有效期没记：算「没过期」 vs **判不了**                | **判不了**（`VALIDITY_UNKNOWN`） | 没记有效期**不等于**没过期。但「判不了」也**不许默认放行** ⇒ 阻断 + 原因单列                                                                                                                                                    |
| 登记的修改：就地改 vs **版本化**                      | **版本化**                       | 「改了就是新的生效版本，历史判定按当时那版算」。就地改当前版本的事实在 DB 层被触发器直接拒                                                                                                                                      |
| 刊登闸门的口径：品类维度 vs **国家维度**              | **DB 层按国家维度**              | 刊登入口只有平台类目路径（`category_path`，平台侧原文），**无法可靠地**映射到 EPR 品类 —— 在 DB 层猜一个映射就是编造。服务层端点同时给出**两份口径**（`allowed` 按请求口径 + `countryScope` = DB 层强制的口径），界面必须都显示 |
| 适用品类的来源                                        | **本模块自己的 `epr_category`**  | EPR 品类（包装 / 电子 / 电池 …）是 EPR 制度的适用维度，**不是**平台商品类目，混用会得出错误口径                                                                                                                                 |
| 数据范围：RESTRICTIVE 收敛 vs **只有 isolation**      | **只有 isolation**               | EPR 档案是**租户级**合规事实，不挂在任何一家店下面；套店铺收敛会让「这个租户在德国注册了没有」凭空消失（同 V312 法人主体的理由）                                                                                                |
| 费用的跨币种汇总                                      | **按币种分组**，不给总数         | EUR + USD 不是钱。给一个总数就是错的口径                                                                                                                                                                                        |
| 金额的传输格式                                        | **十进制字符串**                 | 让金额从头到尾不经过浮点。`numeric(19,4)` 读回来就是 4 位小数，前端原样显示                                                                                                                                                     |
| 申报期：存「上次什么时候报的」 vs **周期 + 起止月份** | **周期 + 起止月份**              | 「上次报的」是**不需要**的事实（第一次报之前没有「上次」）。周期 + 起止月份能算出「当前是否在申报期内」，没记起止月份是**判不了**不是「不在期内」                                                                               |

## 文件清单

### 后端

| 文件                                                                                          | 说明                                                                                             |
| --------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------ |
| `backend/services/channel/src/main/resources/db/migration/V314__channel_epr_registration.sql` | 9 张表 + 7 个触发器 + `epr_gate_findings` 判定函数 + 刊登闸门触发器 + RLS + GRANT + 参考数据种子 |
| `backend/services/channel/src/main/java/com/starcon/channel/EprRegistrationModels.java`       | 传输模型（29 个 record）                                                                         |
| `backend/services/channel/src/main/java/com/starcon/channel/EprRegistrationRules.java`        | 判定规则（**纯函数**，可单元测试）：申报期窗口 / 币种与国家码形状 / 判定原因                     |
| `backend/services/channel/src/main/java/com/starcon/channel/EprRegistrationService.java`      | 服务层（jOOQ 显式 SQL）                                                                          |
| `backend/services/channel/src/main/java/com/starcon/channel/EprRegistrationController.java`   | 17 条路径 / 21 个操作                                                                            |

### 测试

| 文件                                                                                                    | 例数 | 覆盖                              |
| ------------------------------------------------------------------------------------------------------- | ---- | --------------------------------- |
| `backend/services/channel/src/test/java/com/starcon/channel/EprRegistrationRulesTest.java`              | 15   | 判定规则纯函数                    |
| `backend/services/channel/src/test/java/com/starcon/channel/EprRegistrationIntegrationTest.java`        | 28   | Testcontainers 真实 PostgreSQL 16 |
| `backend/services/channel/src/test/java/com/starcon/channel/EprRegistrationTenancyIntegrationTest.java` | 3    | **租户隔离三断言**                |

### 前端

| 文件                                                                      | 说明                                                                                                                         |
| ------------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------- |
| `apps/web/src/components/epr-registration/epr-registration-model.ts`      | 前端模型 + API 调用 + 展示口径                                                                                               |
| `apps/web/src/components/epr-registration/epr-registration-model.test.ts` | 展示口径单测（13 例）                                                                                                        |
| `apps/web/src/components/epr-registration/epr-registration-workspace.tsx` | 工作区（刊登闸门 / 合规状态 / 覆盖反查 / 档案 / 申报期 / 费用 / 不适用声明）                                                 |
| `apps/web/src/app/compliance/epr/page.tsx`                                | 页面入口 `/compliance/epr`                                                                                                   |
| `apps/web/src/app/api/channel/epr-registration-allowlist.test.ts`         | api-proxy 白名单双向断言（6 例）                                                                                             |
| `apps/web/src/lib/api-proxy.ts`                                           | **新增一条独立 `services[]` 元素**（首 token `epr-registrations`，锚点在 `platform-appeals` 之后），既有条目一字未动、不重排 |

### 契约与台账

| 文件                                                   | 说明                                       |
| ------------------------------------------------------ | ------------------------------------------ |
| `packages/api-contracts/src/channel.openapi.json`      | codegen 产物（channel 纯新增 17 条路径）   |
| `packages/api-contracts/src/contracts/channel.ts`      | codegen 产物（纯新增 35 个类型）           |
| `docs/product/capability-blueprint/module-progress.md` | **只改本模块那一行**的「状态」「证据」两格 |

## 关键事实与验证

- **上游自检**（开工前 10 分钟）：`tax_registration_record` / `tax_registration_jurisdiction`（V312）、
  `shop` / `shop_marketplace` / `marketplace` / `platform`（V1）、`channel_scope`（V2）、
  `listing_publish_job_target`（V196）、`publish_task`（V183）在基线里都已存在 ⇒ **不阻塞**。
- **跨车道 DDL 回归面 = 0**：纯新建 9 张表、**零 ALTER 任何既有表**、**零外键**
  （跨表引用一律走触发器，与同卷 V300 / V308 / V312 同一纪律）。
- **触发器挂在别人的表上会不会打挂别人的测试？**
  已核对：`listing_publish_job_target` / `publish_task` 的既有测试**只用 US 站点**
  （`ListingPublishJobIntegrationTest` / `ListingPublishJobTenancyIntegrationTest` /
  `ListingPublishTaskIntegrationTest` / `ListingPublishTaskTenancyIntegrationTest` / `PublishIntegrationTest`），
  而美国不在 EPR 制度参考表里 ⇒ 判定结果是 `NOT_APPLICABLE` ⇒ **不拦**，既有测试不受影响。
  新增用例反过来钉住这件事：DE 进 `SUBMITTED` 被拒、US 放行、**DRAFT 草稿不拦**。
- **PG 函数签名的一个坑**：`char` 在函数签名里是 `character(1)`（不是 `character(2)`），
  所以 `GRANT EXECUTE ON FUNCTION epr_gate_findings(uuid, char, varchar)` 会直接报「函数不存在」。
  已改成 `varchar` 参数 + `GRANT ... (uuid, varchar, varchar)`。
- **契约生成的坑**（与 16.3.1.1 同一）：codegen 的 `.ts` 从**活 spec**（声明序）生成，
  提交的 `.openapi.json` 快照是 **key 排序**的。喂其它服务的**已提交快照**会让它们的 `.ts` 全量重排。
  本车道用本 worktree 内**新导出的六份活 spec**跑 codegen ⇒ 其余 5 个服务的产物**逐字节不变**。
- **api-proxy 首 token**：`epr-registrations` 在本文件既有全文出现 **0** 次（实测）；
  既有 channel 模块入口与 crm / catalog / identity 入口均未被误伤
  （`pnpm vitest run apps/web/src/app/api` **189 文件 1090 例全绿**）。
- **后端路径前缀取 `epr-registrations`**（与任务书指定的代理首 token 一致），
  这样代理正则与后端路由**字面同前缀**，不会出现「代理放行了但后端 404」。

## 后续待办

- **12 财务**：finance 服务在本仓**尚未落地** ⇒ 本模块**不建财务凭证表**、不声称费用已入账；
  `epr_fee` 只是本服务自己的费用记录。要做凭证 / 对账 / 入账，等 12 财务落地后经**授权 API** 写。
- **16.3.2.1 VAT/IOSS**：**未交付** ⇒ 本模块**不编造** IOSS 订单清单与代收代缴统计。
- **16.3.2.3 销售税与 marketplace facilitator**：**未交付** ⇒ 本模块不碰销售税。
- **16.3.1.2 申报日历**：**未交付** ⇒ 本模块只算「当前是否在申报期内」，
  不做申报日历、不做截止日提醒、不做「未开始 / 已提交 / 已确认」的状态跟踪。
- **刊登入口的品类映射**：DB 层只能按国家维度拦（平台类目路径映射不到 EPR 品类）。
  等 03 商品类目落地后，可以补一条「源商品类目 ⇒ EPR 品类」的映射，
  把 DB 层的闸门从国家维度细化到品类维度。
- **参考数据的覆盖面**：`epr_country_regime` 目前只列**确知**的制度要求
  （德国 LUCID / WEEE / 电池法，法国 / 意大利 / 西班牙 / 荷兰 / 奥地利 / 波兰 / 英国包装注册）。
  没列出来的 (国家, 制度) 一律是 `NOT_APPLICABLE` ⇒ **放行** —— 这是刻意选的保守方向：
  **宁可放过，不可把不该拦的全拦了**。新增国家走 DB 迁移加码表。
- **不适用声明的准确性**：`epr_exemption` 是**租户声明**（不是事实）。
  声明错了就等于放错了刊登 —— 声明带**理由必填** + 生效窗口 + 可撤销（关窗口不删行），
  审计时能追到「谁在什么时候声明了什么、为什么」。
