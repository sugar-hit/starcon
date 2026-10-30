# Batch 114 —— 竞价管理（14.2.1.1）并入集成头

- 日期：2026-10-30
- 批次号：**114**（接续该分支上已有的 batch-113）
- 集成头：`integration/module-dev`，merge commit `84599442`（`merge(14.2.1.1): 竞价管理`）
- 车道来源：`feat/14-2-1-1`（worktree `.worktrees/14.2.1.1`），本轮由调度者回收并入
- 车道自己的详细决策记录：[`2026-10-29-ad-bidding-management.md`](./2026-10-29-ad-bidding-management.md)

本批共交付 **1 个模块**。下面逐模块说明。

---

## 模块 14.2.1.1 竞价管理

**这个功能做什么**：广告运营给出一批 SKU 或关键词设定「动态竞价策略」（只增只降 / 只降 / 只增）、
目标 ACOS 或目标 ROAS、竞价区间上限与单次调整额度，系统据此算出**建议竞价**，运营可以逐条采纳；
也可以手工提交调价并逐条裁决结果。调价落到「待下发队列」，等平台广告 API 交付后再真正下发。

### 技术落地

- **迁移**：`backend/services/channel/src/main/resources/db/migration/V407__ad_bidding.sql`
  —— **七张新表、纯 CREATE TABLE、零 ALTER 任何既有表**（静态预检确认：无 `ADD COLUMN` 缺 DEFAULT、
  无 `SET NOT NULL`、无新增 CHECK 约束 ⇒ 与早先已合入车道不存在互相破坏面）：
  - `ad_bidding_policy` —— 竞价策略：`dynamic_bidding_strategy` 三档枚举（`UP_AND_DOWN` / `DOWN_ONLY` /
    `UP_ONLY`，DB 层 CHECK 钉死）、作用范围 `SHOP` / `CAMPAIGN` 两档、`min_bid` / `max_bid` 竞价范围上限、
    单次调整上限（比例列与绝对值列）、目标 ACOS 与目标 ROAS **二选一**
    （CHECK 钉死 `num_nonnulls(target_acos, target_roas) = 1`）、状态 `DRAFT` / `ACTIVE` / `PAUSED` / `ARCHIVED`
  - `ad_bidding_suggestion` —— 建议批次（口径窗口、目标值、判定参数快照、汇总计数）
  - `ad_bidding_suggestion_sku` —— SKU 级口径（花费按 listing→SKU 当前登记变体数**等分**、归因销售额只取
    **生效规则当前版本**、盈亏平衡 ACOS =（净利 + 广告费）÷ 单品收入）
  - `ad_bidding_suggestion_item` —— 关键词级落价（倍率 = 目标 ACOS ÷ 当前 ACOS，建议竞价 =
    **该关键词自己的当前竞价** × 倍价，不把 SKU 均价抄给所有词）
  - `ad_bidding_adjustment` / `ad_bidding_adjustment_item` —— 手动调整 + 逐条裁决
  - `ad_bidding_dispatch` —— 待下发队列
  - 七表全部 `tenant_id NOT NULL` + ENABLE 与 FORCE RLS isolation + RESTRICTIVE `data_scope`
    （按店铺 owner / 部门收敛）+ GRANT `channel_app` / `starcon_app` 零权限；
    策略唯一性用**表达式唯一索引** `ad_bidding_policy_scope`
    （`coalesce(target_campaign_id, 零 UUID)`）钉死 —— 一个作用范围一条策略，SHOP 级与 CAMPAIGN 级可共存
  - `current_acos` / `current_roas` 是 **GENERATED ALWAYS AS … STORED** 列（ACOS = 花费 ÷ 归因销售额、
    ROAS = 归因销售额 ÷ 花费）：分母为 0 ⇒ **NULL（不可比）而不是 0**
  - 金额 `NUMERIC(19,4)` + BigDecimal，计数与比率 `NUMERIC(19,6)`，**零 float / 零 double**；
    时间 `timestamptz`，jOOQ values 位置写 `?::timestamptz`

- **端点**：12 path / 16 端点，挂 `/channel/ad-bidding`
  （`backend/services/channel/src/main/java/com/starcon/channel/AdBiddingController.java`）：
  `GET /capabilities`（能力声明）/ `GET /options`（可选值）/ `GET /policies`（策略清单）/
  `POST /policies`（新建策略）/ `GET /policies/{id}` / `PUT /policies/{id}` /
  `POST /policies/{id}/status`（启停）/ `GET /keywords`（关键词竞价列表）/ `POST /suggestions`（生成建议）/
  `GET /suggestions`（建议清单）/ `GET /suggestions/{id}`（建议详情）/ `POST /adjustments`（提交调整）/
  `GET /adjustments` / `GET /adjustments/{id}` / `GET /dispatch`（待下发队列）/ `POST /dispatch/{id}/cancel`（撤销）。
  权限复用 `channel.operation.read` 与 `channel.operation.create`（identity 零改动）；
  租户只来自已认证成员关系，**伪造 `X-Tenant-Id` 无效**。

- **页面**：`/ads/bidding`（`apps/web/src/app/ads/bidding/page.tsx` + `apps/web/src/components/ads/`）：
  策略配置、关键词竞价列表、建议生成与采纳、手动调整与逐条裁决、待下发队列与撤销；
  **加载 / 空 / 错误三态齐全**。

- **测试**：
  - 后端 `AdBiddingIntegrationTest` **21 例** + `AdBiddingTenancyIntegrationTest` **3 例** = **24 例全绿**
    （实跑 Testcontainers PostgreSQL 16，Flyway 实跑到 v407）；多租户三断言全过：
    两租户互不可见、伪造 tenant header 无效、连接池归还后不串租户
  - 前端 `ad-bidding-model.test.ts` **21 例**

- **契约**：`packages/api-contracts` 由 codegen **纯增量**生成（+12 path / +27 schema / 0 删除 /
  0 既有条目内容变更）；`apps/web/src/lib/api-proxy.ts` 新增**独立** `prefix: 'channel/'` 元素
  （不并入既有 channel 正则，锚在第一个真正的 `prefix: 'identity/'` 之前），发车前用脚本把文件里
  全部 370 条元素逐条实测 ⇒ 本模块 12 条路径**全部零命中**，反向核对 12 条别的车道形状命中集合一字未变。

### 关键设计决策

1. **🔴 改竞价只走已交付的 14.1.3.1 写路径，不另开写路径。**
   每次调价都走 `AdKeywordService.bulkAction(AdBulkActionRequest)`，直接继承上游已钉死的三条护栏
   （`MAX_BID_DELTA_RATIO = 0.50` / `MAX_BID_ABSOLUTE = 1000.00` / `MAX_TOTAL_BID_INCREASE = 5000.00`）、
   `batch_key` 幂等与操作审计。**否决方案**：本模块自己写一条 `UPDATE ad_keyword`。
   理由 —— 两条写路径并存 ⇒ 「竞价是谁改的、为什么被限、改前改后是多少」有两份答案；
   竞价是花钱的动作，写路径只能有一条。

2. **🔴 两道闸各自独立，越限逐条 REJECTED 并写明是哪道闸、哪个上限，绝不静默截断。**
   闸① = 策略 `min_bid` / `max_bid`（结果区间，码 `RANGE_MIN` / `RANGE_MAX`）；
   闸② = 单次调整比例 / 金额额度 + 上游三条护栏
   （`ADJUST_RATIO` / `ADJUST_AMOUNT` / `UPSTREAM_RATIO` / `UPSTREAM_ABSOLUTE` / `UPSTREAM_TOTAL`）。
   **踩到的真坑**：上游 `bulkAction` 的比例上限**只在 PERCENT 模式生效**，本模块用 ABSOLUTE 模式时那条
   ±50% 护栏在服务层形同虚设 ⇒ 预检里补上同一条上限（策略自定更严值优先）。
   **否决方案**：静默截断到上限 —— 会让「我设了上限」变成一句没人验证过的空话。

3. **🔴 动态竞价是三档枚举，不是自由文本。**
   DB 层 CHECK 钉死 `UP_AND_DOWN` / `DOWN_ONLY` / `UP_ONLY`。理由：自由文本会让
   「平台支持哪几档」这个问题永远无法回答。

4. **🔴 ACOS / ROAS 用 GENERATED 列，分母为 0 ⇒ NULL（不可比）而不是 0。**
   「没卖货」与「占比完美」是两件相反的事，填 0 会把烧钱广告显示成最优广告；
   ROAS 的分母是花费，花费为 0 时是真的零收益（可比的 0），与 ACOS 不可比要分开。
   列由 DB 自己算 ⇒ 页面展示的 ACOS 与倍率必然自洽，服务层算不出和库对不上的数。

5. **🔴 平台广告 API 未交付 ⇒ 调整只落待下发队列；队列唯一能改的动作是「撤销」。**
   队列形状照抄已交付的 `ad_rule_dispatch`。**刻意不提供「标记已下发」** ——
   没有真实下发通道时提供一个「标记已下发」按钮，等于允许把没发出去的东西说成发出去了。
   **撤销 ≠ 回滚**：撤销不回滚本地竞价（回滚是一次新的调价，要走同一套护栏），界面显式说明。

6. **🔴 利润侧读不到 ⇒ 503，绝不返回空集合冒充「上游没有数据」。**
   复用既有 `AdProductProfitPort`（经 reporting 授权只读 API `GET /reporting/sku-profit/report`，
   转发调用方自己的 bearer、**不传租户号**）；`available=false` 时抛 503。
   理由：蓝图强调「没有利润数据就不知道赚不赚钱」，空集合会被读成「亏不了钱」。

### 已知缺口（如实标注，非本车道范围）

- identity **未 seed 任何 `reporting.*` 权限码** ⇒ `GET /reporting/sku-profit/report` 在真实部署中
  对普通账号返回 403 ⇒ 本模块建议生成按设计返回 503。同一缺口也影响已交付的 14.2.1.2 与 14.3.1.3。
  **这是跨模块缺口，需要在 identity 侧补 seed，本车道无权改 identity 的权限数据。**
