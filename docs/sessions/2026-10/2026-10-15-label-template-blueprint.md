# 10.2.2.2 面单模板（阶段 D5，L3 锚点 10.2.2）

- 车道：`feat/10-2-2-2`，基线 `integration/module-dev`（fbbb105d）
- 主责服务：**channel**（蓝图 L2 表 10.2 运单管理「主责服务 = trade + channel」；模板是**渠道侧的合规规格**，
  渠道要求来自同服务的 10.1.1.2（V136），同库同服务一跳可读）
- 迁移：`backend/services/channel/src/main/resources/db/migration/V141__logistics_label_template.sql`

## 决策摘要

### 依赖自检（开工 10 分钟内完成，全部通过）

| 关系   | 模块       | 落点                    | 结论                       |
| ------ | ---------- | ----------------------- | -------------------------- |
| 上游   | 10.1.1.2   | channel `V136`          | ✅ 已交付，同库同服务只读  |
| 兄弟   | 10.2.2.1   | warehouse `V125`        | ✅ 已交付（只作写法参照）  |
| 下游   | 8.3.4.2    | warehouse `V73`         | ✅ 已交付，经授权 HTTP 对接 |
| 上游   | 10.2.1.2   | trade `V128`            | ✅ 已交付（只记 template_ref） |

### 蓝图加粗句怎么落

> **附加声明（如电池、液体）漏打会导致清关问题，模板配置要与渠道规则强绑定**

拆成三件**同时**做的事，缺一件就退化成「把声明当文本存起来」：

1. **声明必须锚定到真实打印位** —— V141 trigger c **正反双向**：
   写声明时目标 `element_key` 必须已存在；删/改仍被引用的 `element_key` 时拒；
   并拒「既没声明代码也没印面文字」的空声明。
2. **渠道要求由服务端一个方法判** —— `POST /binding`，同库只读 10.1.1.2 的
   `logistics_channel_restriction`。**调用方拿不到限制字段就无从自己判断**，
   前端也没有任何一处自己比对。
3. **启用前真渲染一遍** —— trigger a 额外钉死「齐备度非 READY 不许进 ACTIVE」，
   启用动作走一次 `ACTIVATION` 渲染并落进 render 表（证据链）。

另有两个 DB 层硬闸：**打印位越出纸张即拒**（trigger d；负坐标只能来自 `origin_offset` 校准）、
**锂电池 2.7 < Wh ≤ 100 必须 UN38.3**（trigger e，IATA/UN 口径）。

## 关键 trade-off

### 1. 落 channel 而不是 warehouse / trade

模板是「这条渠道的纸面合规要求」，渠道要求来自 10.1.1.2（channel，V136）。
落 channel 让「渠道要求 → 模板声明」变成**同库同服务**的一跳，一条跨服务 SQL / 跨库 FK /
共享视图都没有；落 trade 则要让 trade 反向读 channel 的库，违反 AGENTS.md 模块边界。

### 2. readiness 拆成**两个字段**

- `readiness`（落库、本地齐备度：NO_LAYOUT / NO_BARCODE / DECLARATION_GAP / READY）——
  DB trigger 守护，绕过服务层写 SQL 也改不成 READY。
- `effectiveReadiness`（读取时合成，多看 10.1.1.2 的渠道可用性 ⇒ NO_CHANNEL）——
  **刻意不在 DB 层判**：渠道被停用时本表一行都不会被写、触发器也就不会跑。
  只给一个字段的话，页面会一直显示「齐备」直到有人真去印一张才发现渠道早停了。

### 3. 模板表**刻意没有**输出格式列

蓝图「打印规格」点名的五项是尺寸 / 条码类型 / 附加声明 / 电池标识 / 语言版本，**输出格式不在其中**。
渲染器只产出 ZPL（热敏面单，现场主流），所以加一个预选 PDF/HTML 而只出 ZPL 的下拉
**等于给现场一个会骗人的选择** —— 宁可没有这个字段。
渲染产物的实际格式记在 `logistics_label_render.label_format` 上（记录**实际产出**，不是许愿）。
HTML / PDF 文档类渲染是 8.3.4.2 的既有能力，本模块不重复建设。

### 4. 概览里**没有** byFormat，改为 byDeclarationKind

输出格式列不存在之后，`byFormat` 只能是一个永远为 0 的分布 —— 那比没有更糟。
换成**强制附加声明分布**：这是加粗句在概览层面的落点，
「一张强制声明都没登记」比任何汇总数字都更值得运营立刻看见。
刻意只统计 `mandatory` 声明：非强制声明混进来会把「齐备」的水位虚高。

### 5. 二维码**只校验不生成**

QR / DataMatrix 的点阵由现场打印机的 `^BQ` / `^BX` 生成，本模块只校验字符集与
字节长度上限。能力声明里 `two_dimensional_encoding` 显式 `available=false` 并点名 8.3.4.2，
**不假装本模块会生成点阵**。一维码（Code 128 A/B、Code 39）则**真编码 + 真算模 103 校验位**，
因为「这个运单号能不能用 CODE39 印」必须在印之前就有答案。

### 6. 打印下发**不做**（8.3.4.2 的主权）

打印机、打印任务、重复打印、打印日志全是 8.3.4.2（warehouse，V73）的。
自建会出现两份「印的是什么」。能力声明里 `print_dispatch` 显式 `available=false` 并点名 8.3.4.2。

## 测试抓到的真 bug（这一条最值得记）

**Code 128 少编码一位 STOP。**
`encodeSetB` / `encodeSetA` 原来分配的数组是 `length + 2`（start + 字符 + 校验位），
**没有给 STOP 留位置**。后果：条码少一位终止符 —— 条纹都在、长度看着也对、界面上完全正常，
但**扫码枪直接读不出**。集成测试断言「模块序列必须以『校验位图案 + STOP』收尾」时暴露。
已修：抽出 `withChecksumAndStop()` 显式补齐，并补了断言钉住。

另有两个自己踩的坑（都是 V141 注释里已经写下的告警）：
- `RAISE EXCEPTION '...' , USING ERRCODE=...` —— 无参数时那个逗号是语法错；
- `RAISE` 消息里的 `%s` —— PostgreSQL 没有 C 的 `%s` 转换，中文实参会原样漏出字母 `s`。

## 文件清单

**迁移**
- `backend/services/channel/src/main/resources/db/migration/V141__logistics_label_template.sql`
  （五张表 + 5 个触发器 + RLS + GRANT，**零 ALTER 任何既有表**）

**后端（channel）**
- `LabelTemplateModels.java` —— 传输模型（20 个 record）
- `LabelRenderEngine.java` —— 🔴 真渲染：mm→dot 换算、Code 128 A/B、Code 39、ZPL 组装、sha256
- `LabelTemplateService.java` —— jOOQ 显式 SQL + 能力声明 + 读写 + 预览 + 绑定判定
- `LabelTemplateController.java` —— `/channel/label-templates` 9 个端点
- `LabelTemplateIntegrationTest.java` —— 28 例

**前端**
- `apps/web/src/components/logistics/label-template-model.ts` —— 类型 + 端点 + 字典 + 本地校验
- `apps/web/src/components/logistics/label-template-workspace.tsx` —— 四区块工作区
- `apps/web/src/app/logistics/label-templates/page.tsx` —— 页面入口
- `apps/web/src/components/logistics/label-template-model.test.ts` —— 17 例
- `apps/web/src/app/api/channel/label-template-allowlist.test.ts` —— 5 例
- `apps/web/src/lib/api-proxy.ts` —— **外科式增量**新增 `label-templates` 一组

**共用（多车道文件）**
- `packages/api-contracts/src/contracts/channel.ts` + `channel.openapi.json` —— codegen 重生成（新增 20 个 Label* DTO）
- `docs/product/capability-blueprint/module-progress.md` —— 只改本模块那一行的状态与证据

## 顺手修的一处（不是本模块的主权，但 L1 红了）

`LogisticsChannelIntegrationTest.truncateAll()` 加了 `cascade`。
原因：V141 给 `logistics_channel_config` 建了子表外键，TRUNCATE 被引用的表时
PostgreSQL 要求把引用表一并处理，否则 23503，症状是**兄弟模块每个用例在 setUp 就炸**。
`CarrierIntegrationTest` 早就为 V136 做过同一处理并在注释里写明「每多一个引用本表的下游模块
就得回来改这里一次…… cascade 覆盖**任何**引用表」—— 沿用既有结论，不重排它的清单。

## 后续待办

1. **运单 ↔ 模板的自动选版**（本模块能力声明里 `waybill_binding` available=false，ownerModule=10.2.1.2）：
   现在能算出「哪些模板能打这一票」，但运单申请侧仍由人挑模板。
2. **8.3.4.2 侧的打印对接**：本模块已能给出真 ZPL 与合规判定，
   下游需要按 `template_ref` 把它接到真实打印任务队列上。
3. **二维码点阵**：若将来要求「预览里就能扫」，需要引入一个 QR 编码器
   （当前刻意委托给打印机，能力声明已如实标 unavailable）。
4. **承运商侧的真实面单版式**：V141 的标准布局目前是集成测试里手工排的一套 100×150mm；
   等 10.1 真正接通某个承运商后，应该按该承运商的实际版式补模板而不是改引擎。
