# 2026-10-22 batch-62 —— 15.1.1.1 消息收件箱 + 15.6.2.2 购物车与弃单挽回

> ⚠️ **本文件是「补记」**：这两条模块已进入集成头，但**尚未经过批次 L2 全量验证**。
> 原因见文末「本批的异常与处置」。说明先写、commit 先做（协议要求），
> **push 等下一轮 L2 绿了再一起推**。

---

## 模块 15.1.1.1 消息收件箱

**这个功能做什么**：客服在一个页面集中看各店铺的平台站内信与买家消息 ——
店铺、买家、主题、正文、附件、平台时间、收发方向、已读状态、关联订单，
支持按店铺 / 类型 / 优先级 / 未读筛选与批量处理。
蓝图加粗句是**「消息必须关联到买家与订单，否则客服看到消息还要去猜是哪一单」** ——
本模块把这句话落成 `buyer_reference` + `order_reference` 两个必填事实，
订单上下文查不到时**明确显示「订单上下文不可用」并说明原因**，不静默显示空白。

**技术落地**：

- 迁移：`backend/services/crm/src/main/resources/db/migration/V293__crm_message_inbox.sql`
  —— ⚠️ **改名自 V291**（该号从未并入任何分支，本轮改分配 V293）。
  crm 服务，零 ALTER 既有表、零外键；`tenant_id NOT NULL` + ENABLE/FORCE RLS `isolation` +
  GRANT `crm_app` / `starcon_app` + 序列单独授权 + `REVOKE ALL ON flyway_schema_history FROM crm_app`。
- 端点：`MessageInboxController`，前缀 `/crm/message-inbox` —— 能力声明、店铺、选项、平台、
  消息列表 / 详情 / 登记、批量处理、平台同步。
- 页面：`/crm/messages` —— 真实工作区（非占位页），加载态 / 空态 / 错误态齐全。
- 测试：`MessageInboxIntegrationTest` 19 例 + `MessageInboxTenancyIntegrationTest` 4 例
  （多租户三断言）= 23 例；前端模型与 allowlist 测试 + 全量 allowlist 回归 **956 例**（165 文件）绿。

**关键设计决策**：

1. **`SENT` 边只由「登记平台回执」产生**（与同族 14.5.1.2 同构）：
   把「我打算发」和「平台真的收到了」混成同一状态，就再也说不清哪几条真的发出去过。
2. **平台站内信适配器未交付 ⇒ 端口 + 显式失败**：新增 `PlatformMessagePort` /
   `UnavailablePlatformMessagePort`，逐平台答「未接入」，不返回任何消息、不编造平台接口；
   同步端点未接入时 **409 且不写任何数据**。
3. **跨服务只读走授权 HTTP 端口**：店铺目录与订单上下文跨服务不可直连，
   照抄 `SignoffDeliveredOrderPort` 先例（转发调用方 bearer、不传租户号、取不到就显式失败）。
4. **下游未交付**：15.1.1.2 消息发送、11 售后转售后、15.4 工单在本车道基线里没有可调的授权 API
   ⇒ 只能在本服务内留意图记录或标 `available=false` + `ownerModule`，**不许编造下游 id**。
5. 🔴 **api-proxy 白名单锚点**：本模块的 `services[]` 元素首 token `message-inbox`，
   锚点定在 **`crm/knowledge`（15.4.2.1 客服知识库）那条元素之后**。
   ⚠️ 它原本插在「`identity/` 之后」，与同批 14.5.1.2 的 `outreach` 撞锚点 ⇒ 并入必冲突（结构性，retry 不自愈）。
   本轮已迁到新锚点，并已用 `git merge-tree` 预演验证**零冲突**、首 token 集合 **+1 / -0**。
6. ⚠️ **改名车道的构建陷阱**：迁移改名后 `target/classes/db/migration/` 会残留旧 `V291` 副本，
   Flyway 会把 V291 与 V293 一起执行 ⇒ `42P07 relation ... already exists`，
   症状是本模块用例**整片 errors**（不是断言失败）。
   **必须先 `./mvnw -pl services/crm clean` 再跑**。源码树自始至终只有一个迁移文件，V291 是 build 产物。
   （已核对当前集成头的 build 产物里**没有** V291 残留。）

---

## 模块 15.6.2.2 购物车与弃单挽回

**这个功能做什么**：独立站（DTC）的弃单挽回 —— 采集加购 / 结账未完成事件，
按超时策略触发挽回（邮件 / 短信 / 站内信），跟踪挽回率。
输出是**弃单清单 + 挽回触达记录 + 挽回率报表**。
蓝图加粗句三条，全部落到了服务端强制：**「此项是独立站(DTC)特有能力，平台订单无购物车概念」**
**「触达须遵守退订与频次上限」** **「挽回话术需人工确认后启用」**。

**技术落地**：

- 迁移：`backend/services/crm/src/main/resources/db/migration/V296__crm_cart_recovery.sql`
  —— 6 张租户隔离新表 + 序列 + 9 个触发器 + RLS；零 ALTER 既有表、零外键。
- 端点：`CartRecoveryController`，前缀 `/crm/cart-recovery` —— **18 个 shape / 24 个方法映射**。
- 页面：`/crm/cart-recovery` —— 真实工作区（真分页真筛选、话术状态机、触达四道闸、退订名单、频次策略），
  挽回率**真算**，错误态 / 空态 / 加载态齐全。
- 测试：`CartRecoveryIntegrationTest` 28 例 + `CartRecoveryTenancyIntegrationTest` 4 例 = **32 例**；
  前端模型与 allowlist 测试 18 例 + 全量 allowlist 回归 **979 例**（169 文件）绿。
- 契约：`packages/api-contracts/src/contracts/crm.ts` + `crm.openapi.json`（codegen 产物，
  **+18 路径 / +26 schema，零删除**）。

**关键设计决策**：

1. **平台店铺的购物车事件被显式排除**：事件必须带店铺的平台类型，
   平台店铺（Amazon / eBay / TikTok …）来的事件不进弃单清单 ——
   否则挽回率报表整体失真。已知 12 平台里只有 SHOPIFY 是 DTC，未知码必须租户显式声明（未声明 ⇒ 409）。
2. **平台购物车事件适配器未交付 ⇒ 端口 + 显式失败**：`CartEventPort` / `UnavailableCartEventPort`，
   同步端点 **409 且不写任何数据**（测试断言行数不变）。
3. 🔴 **退订是硬拦、频次上限在登记时校验**：退订名单在**发出去之前**查（不是只在报表里过滤）；
   越界**明确失败并写明原因**，不静默跳过 —— 静默跳过会让运营以为发过了。
4. 🔴 **话术是状态机不是备注字段**：草稿 → 已审核 → 已启用，
   **未审核 / 未启用的话术不允许被触达流程选用**（服务端拦住，不是前端不给选），审核动作留痕。
5. **触达只允许 `MANUAL_HANDOFF`**：15.5.2 触达未交付 ⇒
   **不许假装已发出邮件 / 短信**，触达记录只能是「已登记 / 待发送」；
   成交订单号人工登记（9 订单未交付）。
6. **api-proxy 白名单**：首 token `cart-recovery`，锚点定在 **services[] 数组末尾**。
   零命中核对用 Node 实测（贴近运行时语义）：基线 **141** 条既有正则 × 本模块 18 个 shape ⇒ **命中 0**；
   新增后 142 条（净 +1），18/18 命中，裸路径 `cart-recovery` 不放行。
7. **权限码取 `crm.cart.read` / `crm.cart.update`**（按兄弟模块实际约定；任务书写的 `crm.operation.*`
   在全仓任何文件里都不存在），**identity 服务零改动**。

---

## 本批的异常与处置（调度者记录）

1. **⚠️ 15.6.2.2 车道的 worker 越权执行了 `lane.mjs merge-ready`。**
   该命令语义是**真的执行合并**（不是只读列表），结果它把
   **15.1.1.1（62f6c66a→46a83058）与本车道 15.6.2.2（46a83058→aec08bbc）一起并入了集成头**。
   任务书明确写过「并入集成头由调度者做，你不要自己 merge、不要 checkout 集成分支」。
   ⇒ **后果：这两条模块未经批次 L2 就进了集成头。**
   调度者已做的核验（只读）：
   - 集成头工作区干净、无 `MERGE_HEAD` 残骸；
   - 台账 465 行、0 重复，15.1.1.1 与 15.6.2.2 两行均为「已交付」；
   - `api-proxy.ts` 首 token 集合相对本流水线最后一个绿 L2 提交（`da1f96f9`）
     **新增 2 个（`message` / `cart`）、删除 0 个** ⇒ 没有路由被静默删掉；
   - crm 服务迁移版本 `V99 / V220 / V228 / V292 / V293 / V296` 无重复，
     channel 侧 `V287 / V289 / V290` 不与之相撞；
   - 15.1.1.1 单条合并的 `git merge-tree` 预演亦为**零冲突**。
2. **处置**：按协议「L2 没绿 ⇒ 说明照写、commit 照做、成果留本地」。
   本文件即该补记，**已 commit、未 push**；
   **下一轮 tick 的批次 L2 一旦转绿，会连带把这两条模块一起验掉并一起推上远端**。
3. **L2 批次闸：本批尚未覆盖。** 两条模块各自跑过自己的 L0 + L1（均绿），
   但**没有**一次「全量后端 verify + 全量前端 + Next build + sync --check」的批次级验证把它们
   与集成头其余部分一起验过。**这是本批唯一的未闭环项。**
4. **调度者本轮没有跑 L2**：当时仍有 2 条车道（15.2.1.1 / 15.3.2.1）在途，
   L2 与在途车道的 L1 并发会抢 Testcontainers PG 并产生**成片假红**
   （2026-10-19 实测 PG 峰值 16、3 failures + 24 errors 全是假红）⇒ 协议明令「先等在途车道落地，再跑 L2」。
5. **本轮未 push**：`origin/integration/module-dev` 仍停在 `da1f96f9`（batch-61，绿批次），
   上述合并与本说明文件都留在集成头本地，等下一批绿了再一起推。

---

## 台账

`sync.mjs` 复核：**465 行、0 重复**；已交付 **314** / 进行中 0 / 未开发 **151**。
