# 10.1.1.2 渠道配置（channel 服务）

- **日期**：2026-10-15
- **阶段 / 车道**：D5 / `feat/10-1-1-2`
- **归属**：`backend/services/channel`，Flyway `V136`
- **L3 锚点**：10.1.1 承运商管理（上游 10.1.1.1 已交付于 channel `V134`）

## 决策摘要

渠道配置挂在 10.1.1.1 承运商档案之下（`carrier_profile_id` 复合 FK，带 tenant_id）。承运商身份
（名称 / 编码 / 接口类型 / 账号）**只读**，本模块写请求一个承运商字段都不接受 —— 复制一份就会造出
「同一个承运商在两处各改一次」。

蓝图加粗句「**渠道限制（禁限品类、电池液体、磁力）要硬拦截，否则会被承运商拒收产生额外费用**」
按三层落地，这是本模块唯一不可让步的一条：

1. **写入路径 DB 层双向拒绝** — 适用品类与禁限品类不得命中同一个品类。正向（加适用撞禁限）与
   反向（加禁限撞已登记适用）各一个触发器。**双向是必须的**：只做单向，「先加适用、后加禁限」
   这条完全正常的运营路径会造出一条自己和自己打架的配置，表现正是下单被拒收。
2. **服务端判定端点** `POST /eligibility` 给 PASS / REVIEW / BLOCK 三档与逐条理由。规则收在
   服务层一处，**不散落到每个调用方** —— 散落就意味着总有一个调用方漏掉「磁力」。
3. **承运商不可用时也不放行** — 上游 10.1.1.1 判 `PARTY_BLOCKED` 直接 BLOCK。

「硬拦截」与「前端判断」的分界线：判定在服务端，且绕过服务层直接写 SQL 也被 DB 触发器拒。

## 关键 trade-off

- **禁限品类登记了几条不进 readiness**。理由：没登记限制的渠道照样能发货，让它进 readiness 会让
  「先建渠道、后补限制」这个正常流程永远拿不到 READY，而 READY 在这里是**可用性**不是**合规完备度**。
- **附加费做成行，不做三个恒为 0 的列**。燃油是百分比、偏远/旺季是固定额、**旺季带生效区间**；
  列里放不下区间（任何时候都会有「配了区间但 rate=0」的半配置态，而判定端点必须能唯一回答
  「这个时刻要不要加旺季费」）。
- **`charge_divisor` 语义随计费方式变化**：按件必须为 1（DB CHECK），否则出现「按件却每件按半公斤
  算」这种界面上看不出来、试算结果系统性偏低的配置。
- **`POST` 子表整体替换**。少发一条就是静默丢失（尤其限制），所以前端必须提交全集。
- **api-proxy 走「新加一条 services[] 元素」而不是往既有 channel 分支的 alternation 里并**。
  每个服务在 `services[]` 里是一段超长单行正则，两条车道并同一分支必然结构性冲突，retry 不会自愈。

## 本轮修掉的三个真实缺陷

接手的上一次会话被中断留下一批未提交改动，其中三处是**真的会坏**的：

1. **白名单正则漏了 id 段的前导 `/`** — `(?:capabilities|overview|eligibility)|[0-9a-f-]+` 里
   `\/` 只在第一分支，`logistics-channels/{uuid}` **根本匹配不上**。改成
   `(?:\/(?:capabilities|overview|eligibility|<UUID>))?`（`/` 提到公共位置）。
2. **`[0-9a-f-]+` 放行单字符 `c` / `e` / `f`** — 它们是合法 hex，会被当成渠道 id 放行。改用本
   文件其余 20 处同款完整 UUID 形状 `[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}`。
3. **`CarrierIntegrationTest` 14 个用例全红** — 本模块对 `carrier_logistics_profile` 建了子表，
   而它的 `truncateAll` 显式只列三张承运商表，`TRUNCATE` 父表触发 **23503**（cannot truncate a
   table referenced in a foreign key constraint）。**跨车道修复**：改 `truncate … cascade` 覆盖
   **任何**引用表 —— 逐个列下游表名会让下一个引用本表的模块再红一次，且症状出现在**别的模块**的
   测试里，根因难查。

另修：工作区组件 `lockedCarrier?: string` 在 `exactOptionalPropertyTypes` 下报 TS2375，
补 `| undefined`。

## 文件清单

**新增**
- `backend/services/channel/src/main/resources/db/migration/V136__logistics_channel_config.sql`
- `backend/services/channel/src/main/java/com/starcon/channel/LogisticsChannelModels.java`
- `backend/services/channel/src/main/java/com/starcon/channel/LogisticsChannelService.java`
- `backend/services/channel/src/main/java/com/starcon/channel/LogisticsChannelController.java`
- `backend/services/channel/src/test/java/com/starcon/channel/LogisticsChannelIntegrationTest.java`
- `apps/web/src/app/logistics/channels/page.tsx`
- `apps/web/src/components/logistics/logistics-channel-model.ts`
- `apps/web/src/components/logistics/logistics-channel-model.test.ts`
- `apps/web/src/components/logistics/logistics-channel-workspace.tsx`
- `apps/web/src/app/api/channel/logistics-channel-allowlist.test.ts`

**修改**
- `apps/web/src/lib/api-proxy.ts`（**只追加**一条 services 元素，锚在 `prefix: 'trade/'` 之前）
- `packages/api-contracts/src/channel.openapi.json` / `src/contracts/channel.ts`（**codegen 重新生成**）
- `backend/services/channel/src/test/java/com/starcon/channel/CarrierIntegrationTest.java`（cascade，见上）
- `docs/product/capability-blueprint/module-progress.md`（仅 10.1.1.2 那一行）

## 契约必须重新生成

`packages/api-contracts` 是 codegen 产物，禁止手改：
`python3 tools/codegen/catalog-contracts.py --spec-dir backend/target/openapi`
（spec 由 `OpenApiContractTest` 导出，跑 `./mvnw -pl services/channel -am test` 时顺带产出）。

codegen 会**顺带重写其它服务的契约**（本轮改了 trade，类型集合完全一致、纯重排 + 一行头注释）。
判定方法：抽出 HEAD 与新生成两边的 `export type` 名字集合做 diff，集合相同即无语义变化 → 回退。
本车道提交只含 channel 契约。

## 验证

```
cd backend && ./mvnw -q compile                                    → EXIT=0
cd backend && ./mvnw -pl services/channel -am test                → Tests run: 124, Failures: 0, Errors: 0 · BUILD SUCCESS
pnpm typecheck                                                    → 3 successful / 3 total
pnpm vitest run <白名单 + 模型 + 既有 route>                        → 58 passed (58)
```

channel 后端 124 例中本模块 21 例：租户隔离三断言（两租户互不可见 / 伪造 `X-Tenant-Id` 无效 /
连接池压到 1 后 A→B→A 三轮不串租户）、旺季区间 `timestamptz` 往返**逐字相等**（绑 `OffsetDateTime`
+ values 位写 `?::timestamptz`）、绕过服务层直接写 SQL 造冲突配置仍被拒、DB 触发器重算 readiness
对不上即拒、渠道代码建后不可改。

## 后续待办

- **10.1.1.3 时效与限制**：判定端点只覆盖「限制」，不覆盖时效（预计到达 / 截单时间）。
  capabilities 里 `transit_time` 段 `available=false` + ownerModule 10.1.1.3。
- **10.1.2.1 运费计算器**：`base_rate` / `charge_divisor` / `min_charge` / 附加费四要素已齐备落库，
  但**没有**据此算运费的能力；本模块刻意不做试算（那是 10.1.2.1 的口径，且跨币种换算归它）。
- **10.1.2.2 渠道比价** / **10.2.1.2 运单申请（trade）**：后者是本模块下游，经授权 HTTP 端点取数。
- **`ALLOWED` 档已建模但未使用**：capabilities 的 `restrictionModes` 只列 BLOCK / MANUAL_REVIEW
  （`ALLOWED` 被 V136 的 CHECK 拒在 `PROHIBITED_CATEGORY` 上）。若将来出现「显式放行」的真实需求，
  需另设行来表达，不要复用这一档。
- Testcontainers 容器由 ryuk 回收；并发跑车道时 `docker ps` 会看到其它车道的 PG 容器，属正常。
