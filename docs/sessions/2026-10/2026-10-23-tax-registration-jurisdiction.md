# 16.3.1.1 税号与辖区（阶段 D7 · L3 锚点 16.3.1）

- **日期**：2026-10-23（Asia/Shanghai）
- **车道**：`feat/16-3-1-1`（worktree `/Users/guan/Projects/starcon-worktrees/integration/.worktrees/16.3.1.1`）
- **基线**：`integration/module-dev` @ `f36ac759`
- **迁移版本号**：`V312__channel_tax_registration.sql`（预分配；同批另外三条车道占用 V309 / V310 / V311）

## 决策摘要

蓝图原文（`docs/product/capability-blueprint/16-compliance-and-risk.md` 第 140-145 行）：

> ### 16.3.1.1 税号与辖区
> - **职责**：维护各店铺/法人在各销售辖区的税务登记：税号、VAT 状态、是否注册、EPR 注册号、注册有效期与申报周期。
> - **输入**：税号录入；辖区选择；有效期。
> - **输出**：税务登记档案 + 缺失项提示。
> - **关系**：上游 = 17 法人、2 店铺；下游 = 16.3.2.1、12.6 税务。**未注册辖区的销售额是合规炸弹**：欧洲没有 VAT 号还在欧盟销售，是稽查重点。

本车道的落地要点：

1. **缺失项是真算出来的，不是文案。** 推导链每一段都是本仓真实存在的上游：
   `shop_marketplace` → `marketplace.country_code` + `marketplace.vat_required`
   → `tax_registration_jurisdiction`（受控码表）→ `tax_registration_record`（到底有没有在册登记）。
   六类缺失项（未注册该辖区 / 缺 VAT 号 / 缺 EPR 注册号 / 已过有效期 / 即将到期 / 没记有效期判不了）
   逐条带严重度、人话说明、整改建议；列表与汇总都是真分页真筛选。
2. **辖区值域钉死。** `tax_registration_jurisdiction` 是全局码表（不带 `tenant_id`，与 V1 的
   `platform` / `marketplace` 同一口径），登记的 `jurisdiction_code` 由 **DB 触发器**校验必须落在码表里 ——
   只用 CHECK 钉不住（CHECK 看不到别的表），**不留自由文本**。
3. **法人主体只存逻辑 ID。** 法人档案归 identity（17），经授权只读端口只取编码与名称；
   **不复制任何一列**、不建跨库 FK。读不到时 `available=false` + 原因，
   **不显示成空白或「未注册」**。
4. **推导边界如实写明。** `derivation_scope` 区分 `COUNTRY`（能推导）/ `SUBNATIONAL`（推不出）/ `NONE`（不跟踪）。
   **推不出不等于「都齐了」** —— 这条边界写进能力声明，也写进前端空态文案。
5. **本模块不含任何金额。** 税额与申报底稿归 12.6 财务（该服务在本仓**尚未落地**）⇒ 不建财务表、不做浮点记账。

## 关键 trade-off

| 决策点 | 取舍 | 理由 |
| --- | --- | --- |
| 辖区码表：全局码表 vs 租户级 | **全局码表**（不带 `tenant_id`） | 辖区是业务事实，与 `platform` / `marketplace` 同口径；租户级码表会让「同一个德国」在每个租户里各存一份，跨租户对不上 |
| 缺口来源：站点目录 vs 人工声明「我在德国卖」 | **站点目录推导** | 人工声明是自证，推导才是事实。站点目录（`shop_marketplace` + `marketplace.vat_required`）是本仓已有的真实上游，能把「未注册」变成可复算的结论 |
| 美国各州：也算「应当登记」 vs **不算** | **不算**（`derivation_scope='SUBNATIONAL'`） | 本仓**没有销售事实表**，推不出「该在哪几个州有 nexus」。硬推导只会给出「你在美国要注册销售税」这种错误口径。宁可不报，也不把「推不出」说成「都齐了」 |
| 缺口去重：外部要求侧 + 登记自身侧 **合并** | **按（主体 × 辖区 × 缺失项类型）去重** | 同一件事（登记已过期）从两条路都能看到，去重后运营看到的条数是真的；「未注册」与「缺号」**不**合并 —— 那是两件事、两种整改动作 |
| 有效期没记：算「没过期」 vs **判不了** | **判不了**（`REGISTRATION_VALIDITY_UNKNOWN`） | 没记有效期**不等于**没过期。把「没记」写成「没过期」或「已过期」都是撒谎 |
| 数据范围：整表租户级 vs 店铺主体收敛 | **店铺主体行收敛、法人主体行租户级** | 店铺的数据范围在本服务读得到（`shop.owner_id` / `department_id`）；法人的数据范围归 identity，**读不到**，硬套店铺收敛会让「这个法人在德国注册了没有」凭空消失 |
| 登记的删除 | **不提供**，撤下走 `status='ARCHIVED'` | 登记是事实；撤下后不参与判定（缺口回来），且触发器钉住「已撤下的登记不可再改内容」 |
| 有效期列类型 | `timestamptz` + `OffsetDateTime` + `?::timestamptz` | `Timestamp.valueOf(LocalDateTime)` 会静默偏 −28800s；测试**断言写回的值**而不是只断言没抛异常 |

## 文件清单

### 后端

| 文件 | 说明 |
| --- | --- |
| `backend/services/channel/src/main/resources/db/migration/V312__channel_tax_registration.sql` | 两张表 + 三个触发器 + RLS + GRANT + 89 行辖区码表种子 |
| `backend/services/channel/src/main/java/com/starcon/channel/TaxRegistrationModels.java` | 传输模型（17 个 record） |
| `backend/services/channel/src/main/java/com/starcon/channel/TaxRegistrationRules.java` | 判定规则（**纯函数**，可单元测试） |
| `backend/services/channel/src/main/java/com/starcon/channel/TaxRegistrationService.java` | 服务层（jOOQ 显式 SQL） |
| `backend/services/channel/src/main/java/com/starcon/channel/TaxRegistrationController.java` | 10 个端点 |
| `backend/services/channel/src/main/java/com/starcon/channel/LegalEntitySourcePort.java` | 17 法人档案的跨服务只读端口（读不到 ⇒ `available=false`） |

### 测试

| 文件 | 例数 | 覆盖 |
| --- | --- | --- |
| `backend/services/channel/src/test/java/com/starcon/channel/TaxRegistrationRulesTest.java` | 16 | 判定规则纯函数 |
| `backend/services/channel/src/test/java/com/starcon/channel/TaxRegistrationIntegrationTest.java` | 16 | Testcontainers 真实 PostgreSQL 16 |
| `backend/services/channel/src/test/java/com/starcon/channel/TaxRegistrationTenancyIntegrationTest.java` | 3 | **租户隔离三断言** |

### 前端

| 文件 | 说明 |
| --- | --- |
| `apps/web/src/components/tax-registration/tax-registration-model.ts` | 前端模型 + API 调用 + 展示口径 |
| `apps/web/src/components/tax-registration/tax-registration-model.test.ts` | 展示口径单测（10 例） |
| `apps/web/src/components/tax-registration/tax-registration-workspace.tsx` | 工作区（缺失项 / 汇总 / 覆盖 / 档案 + 登记弹窗） |
| `apps/web/src/app/compliance/tax/page.tsx` | 页面入口 `/compliance/tax` |
| `apps/web/src/app/api/channel/tax-registration-allowlist.test.ts` | api-proxy 白名单双向断言 |
| `apps/web/src/lib/api-proxy.ts` | **新增一条独立 `services[]` 元素**（锚点：`account-health` 那条之后），既有条目一字未动、不重排 |

### 契约与台账

| 文件 | 说明 |
| --- | --- |
| `packages/api-contracts/src/channel.openapi.json` | codegen 产物（channel 纯新增 8 条路径） |
| `packages/api-contracts/src/contracts/channel.ts` | codegen 产物（纯新增 17 个类型） |
| `docs/product/capability-blueprint/module-progress.md` | **只改本模块那一行**的「状态」「证据」两格 |

## 关键事实与验证

- **上游自检**（开工前 10 分钟）：channel 的 `shop` / `shop_marketplace` / `marketplace` / `platform`
  与 identity 的 `legal_entity` 在基线里都已存在 ⇒ **不阻塞**。
- **契约生成的坑**：codegen 的 `.ts` 是从**活 spec**（声明序）生成的，而提交的 `.openapi.json` 快照是
  **key 排序**的。把其它 5 个服务的**已提交快照**喂给 codegen 会让它们的 `.ts` 全量重排。
  本车道只重生成 channel（用新导出的活 spec），其余 5 个服务的产物**逐字节不变**。
- **api-proxy 锚点零命中核对**：本车道正则对本模块 8 个 shape 命中 0 处（既有 157 条正则逐条实测）；
  既有 channel 模块入口与 crm / catalog / identity 入口均未被误伤。

## 后续待办

- **16.3.1.2 申报日历**：本模块只**存**申报周期（`filing_cycle`），日历、截止日、提醒、责任人归它。
- **16.3.2.1 VAT/IOSS**：本模块**不编造** IOSS 订单清单与代收代缴统计。
- **16.3.2.2 EPR/包装法**：本模块只**存** EPR 注册号；包装法费用与「EPR 未注册就限制刊登」的联动归它与 4 刊登。
- **12.6 税务**：finance 服务在本仓**尚未落地** ⇒ 本模块**不建任何财务表**、不做税额计算。
- **美国各州销售税**：本仓**没有销售事实表**，推不出州一级 nexus ⇒ 各州只登记、不自动派生要求。
  等 09 订单落地后可以补一条「有该州销售额 ⇒ 需要该州登记」的推导。
- **法人档案读取权限**：只读端口转发调用方自己的 bearer，identity 侧需要 `identity.legal_entity.read`；
  无权的角色会看到「读不到」+ 原因（**这是正确的行为**，不是缺陷）。
