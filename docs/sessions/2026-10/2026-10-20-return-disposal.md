# 2026-10-20 10.7.1.3 弃置与销毁（channel 服务，D5 期）

> 车道 `feat/10-7-1-3`，基线 `integration/module-dev`。
> 蓝图原文：`docs/product/capability-blueprint/10-logistics-and-freight.md` § 10.7.1.3。

## 决策摘要

**加粗句「销毁凭证缺失会触发品牌方追责」落成四道结构闸，缺一道那道闸就只是注释。**

蓝图这一句最容易做成「一个销毁单的空壳 + 一个备注栏写凭证」。本轮四件事同时做：

1. **销毁方式是枚举事实，不是自由文本** —— `disposal_method` 白名单
   `SHRED`（压碎）/ `INCINERATE`（焚烧）/ `PROFESSIONAL`（专业销毁），
   列上**不写 DEFAULT**：少给一列直接 not-null violation（23502），
   而不是悄悄落成某个方式。方式**同时决定基线凭证要求**
   （专业销毁要重量记录、焚烧要处置证明编号）。
2. **凭证要求是算出来的，不是填出来的** —— 四面旗子
   `require_photo` / `require_weight` / `require_certificate` / `require_executor`
   由 **DB 触发器**按「方式基线 ∪ 品牌方·平台政策」**重算并写回**：
   客户端自报一律作废（实测：直接 INSERT 一张 PROFESSIONAL 单子自称不要重量，
   被改回 `require_weight = true`）；定到 `SCHEDULED` 那一刻**冻结**
   （事后改旗子被拒 `frozen`）。
3. **凭证是真的行** —— `return_disposal_evidence` 一行一件凭证，
   每类**专属字段必填**（照片要有地址 / 重量要有读数 / 处置证明要有编号 /
   执行方签核要有执行方，列级 CHECK）；**一类凭证一张**
   （`UNIQUE(tenant_id, disposal_order_id, evidence_kind)`）——
   两张互相矛盾的 PHOTO 只会让追责时更难说清。
4. **完成态的门在 DB 上** —— 触发器 `guard_return_disposal_order_state`
   在 `status → 'DISPOSED'` 时逐项核对四面旗子 + 时刻 + 执行方，
   缺一件直接 RAISE：**绕过服务层直接写 SQL 也进不去完成态**
   （实测：只给时刻与执行方、照片缺失 ⇒ `without a PHOTO evidence record`），
   列级 CHECK 再钉一道。

其余关键决策：

- **凭证要求只能加严，不能放松** —— `return_disposal_evidence_policy`
  （GENERAL / BRAND / PLATFORM）在解析时取**并集**：品牌化客户的
  「销毁并回传证明」合约义务是**下限**，不是可选项。
  这张表**只挂租户隔离、不挂数据范围**（与 10.7.1.1 的 `return_resale_restriction`
  同纪律）：一条对全租户生效的限制若只有建它的人看得见，
  另一个运营就会在**看不见要求**的情况下把货销毁掉 —— 那正是蓝图要防的场景。
- **上游边界按「同服务 / 跨服务」一刀切**：待销毁清单来自 **10.7.1.1，
  它就在 channel（同一服务）** ⇒ 本模块**直接读** `return_disposition_order`，
  触发器钉死「必须是 `DISCARD` 且未撤销」—— 转销售 / 退运回国的处置单
  **根本进不来**（服务层 422 + DB 拒各一次）。
  **一条处置单最多进一个销毁单**（`UNIQUE(tenant_id, disposition_order_id)`）。
- **SKU / 批次 / 收货单号由服务端从处置单抄**，不接受客户端自报 ——
  自报会出现「单上写着 A 仓的货、实际销毁的是 B 仓的货」。
- **下游缺口按仓库先例显式声明、不读写不存在的服务**：12 财务（**finance 服务不存在**）
  ⇒ 销毁费用以本服务自持成本行落地（合计**不落表**、查询时 SUM 现算；
  费用行币种与销毁单费用币种**必须逐字相同**；**不做任何汇率换算**）；
  11.2.3.3 供应商索赔在 **trade（跨服务）** ⇒ 本模块**只产出索赔依据事实**
  （数量 / 重量**必须与销毁单逐字相同**、凭证齐备性由触发器重算、
  一条销毁单一条依据不重复索赔），以 `source_reference` / `claim_ref` 记逻辑引用，
  **绝不写 trade 的任何表**。
- **本模块就是 10.7.1.2 报废分录的销账方**（`consumer_module` 恒为 `10.7.1.3`）
  ⇒ 销毁即销账，**不另开库存分录**（凭空再开一张就是记两遍）。
- **零外键**：引用校验一律用**触发器**做（同批 `V219` 的同款纪律），
  跨车道 DDL 回归面 = 0，**没有打断任何既有测试的 `TRUNCATE`**。

## 关键 trade-off

- **销毁单的重量以 WEIGHT 凭证的称重读数为唯一来源**，而不是建单时先填一个：
  填了就会出现「单上 12.5kg、凭证 13kg」两个互相矛盾的数字。
  代价是完成前销毁单上的重量是 0（货还没销毁，本来就没有销毁重量）；
  触发器在 `DISPOSED` 时要求 `NEW.weight_kg` **逐字等于**那张 WEIGHT 凭证。
  一开始把凭证反过来去比对销毁单的重量（重量必须先有、凭证才能填），
  那会变成「先有重量还是先有凭证」的死循环 —— 改成凭证单向喂给单子。
- **凭证允许在销毁单完成前改写（UPSERT），完成后不可改、不可删**：
  「凭证不可篡改」要保护的是**那一次销毁的事实**（已销毁的凭证集合冻结），
  不是让运营连打错一个处置证明编号都改不了。
  代价是销毁单完成前凭证是可以被覆盖的 —— 这段时间货还没销毁，
  不存在「追责时的凭据」被改坏的问题。
- **索赔依据的人工回填下游单号**（`claim_ref`）而不是本模块直接调 trade：
  11.2.3.3 已交付但**跨服务**（AGENTS.md：跨服务只走授权 API / event），
  而本仓**没有** trade 的索赔创建契约可直接依赖，硬接会造出一条假的对接链路。
  选「只出事实 + 人工回填下游单号」，并把这条边界在 `capabilities` 里
  显式写成 `available=false` + `ownerModule=11.2.3.3` + impact。
- **api-proxy 新前缀选 `return-disposals`**：既有 `return-dispositions`（10.7.1.1）
  与 `return-shipbacks`（10.7.1.2）正则都是**锚定**的
  （`/^return\-dispositions(...)$/`），首 token 不同就不会互相吃掉；
  `return-quality` / `return-receipts` 在 trade 服务、命名空间本就不同。
  已用脚本逐条核对本文件**全部 73 条**既有正则：`return-disposals/**` **零命中**。
- **凭证要求的凭据缺失在服务端先给 422 并列出缺哪几样**，而不是笼统报错：
  运营要能一眼看出「还差一张照片」，否则凭证闸会变成一个只会拒绝的门槛。

## 文件清单

**后端（channel 服务）**

- 迁移 `backend/services/channel/src/main/resources/db/migration/V221__return_disposal.sql`
  —— 六张表 + 序列 + 触发器 + RLS + GRANT（零 ALTER 既有表）
- `backend/services/channel/src/main/java/com/starcon/channel/ReturnDisposalModels.java`
  —— 请求 / 响应形状（枚举 + record）
- `backend/services/channel/src/main/java/com/starcon/channel/ReturnDisposalService.java`
  —— jOOQ 显式 SQL（18 个端点的业务）
- `backend/services/channel/src/main/java/com/starcon/channel/ReturnDisposalController.java`
  —— 端点（权限码复用 `channel.operation.*`，identity 零改动）
- `backend/services/channel/src/test/java/com/starcon/channel/ReturnDisposalIntegrationTest.java`
  —— 22 例
- `backend/services/channel/src/test/java/com/starcon/channel/ReturnDisposalTenancyIntegrationTest.java`
  —— 3 例（租户隔离三断言）

**前端**

- `apps/web/src/lib/api-proxy.ts` —— **新增一条独立 `services[]` 元素**
  （锚点：`services[]` 最末尾，16.2.2.1 `compliance-precheck` 之后、`];` 之前），
  既有条目一字未动
- `apps/web/src/app/logistics/return-disposal/page.tsx`
- `apps/web/src/app/logistics/return-disposal/return-disposal-model.ts`
- `apps/web/src/app/logistics/return-disposal/return-disposal-model.test.ts` —— 19 例
- `apps/web/src/app/logistics/return-disposal/return-disposal-workspace.tsx`
- `apps/web/src/app/api/channel/return-disposal-allowlist.test.ts` —— 6 例

**契约 / 台账 / 会话**

- `packages/api-contracts/src/channel.openapi.json`、`packages/api-contracts/src/contracts/channel.ts`
  —— 由 `tools/codegen/catalog-contracts.py` 重新生成（**未手改**）：
  channel 纯增量 **+16 path / +27 schema / 0 改动 / 0 删除**
- `docs/product/capability-blueprint/module-progress.md` —— 只改 10.7.1.3 那一行
- 本文件

## 后续待办

- **侧栏 / 搜索导航未登记 `/logistics/return-disposal`**：`packages/ui` 的共享任务页表
  未改（避免重排他人条目），与 10.7.1.1 / 10.7.1.2 现状一致。
- **下游索赔单创建与应付冲减**（11.2.3.3，trade）留待 trade 侧授权 API / event 衔接；
  **销毁费用与 12 财务**的最终对账留待 finance 落地后衔接。
- **平台侧销毁证明回传**（9.4.2.1）未交付 —— 本模块只留存凭证以**满足**平台要求，
  不假装已回传。
- 销毁单**不支持改单**（已销毁完全不可变；未销毁可取消重来）——
  销毁是既成事实，事后改单等于「销毁没发生」。
