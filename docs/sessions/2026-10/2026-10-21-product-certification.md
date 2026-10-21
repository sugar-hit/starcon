# 16.2.2.2 认证与资质（阶段 D7，L3 锚点 16.2.2）

> 会话日期：2026-10-21 · 车道：feat/16-2-2-2 · 落点服务：channel（02 渠道与店铺）

## 决策摘要

1. **落点服务 = channel**：卷 16「合规与风控」的后端主责取 `channel`，与同 L3 的 16.2.2.1 合规预检
   （已落在 channel，V197）**同服务同库**。这样 16.2.2.1 的「认证缺失」维度将来能直接读本模块的档案，
   不必跨服务 HTTP 绕一圈。
2. **命名空间与前缀隔离**：表一律 `product_certification_*`；内置词表 `certification_type`
   （全局参考数据、无 tenant_id，与 channel V1 的 `platform` / `marketplace` 同族）+
   租户字典 `tenant_certification_type`。
   后端命名空间 `/channel/product-certifications`，**刻意不与 16.2.2.1 的 `compliance-precheck` 撞车**；
   前端页面 `/compliance/certifications`（同域、不同页）。
3. **认证类型是受控词表，不是代码里的常量数组**。内置类型写在**迁移的 INSERT 种子数据**里，
   生效词表 = 内置 ∪ 租户字典、**租户行优先**（可改名、可停用）。
   词表由 **DB 触发器**兜底：直连数据库插一个不在词表里的类型直接拒。
4. **到期状态不落库**（与 6.1.2.3 供应商资质同一纪律）：表里没有 `expiry_state` 列，
   由读出口按**数据库时钟 `now()`** 现算；「即将过期」窗口来自**租户级策略**
   `product_certification_policy.warning_days`（默认 30 天），不是代码常量。
   剩余天数按 **floor** 算（「还剩 12 小时」显示成「还剩 0 天」而不是「还剩 1 天」）。
5. **覆盖情况统计要求侧自己维护**。只登记「有哪些认证」答不出「谁缺认证」——
   缺的那一行本来就不存在。所以本模块额外维护 `product_certification_requirement`
   （这个 SKU 需要哪些认证类型，可注明来自哪个类目要求），
   覆盖判定 = 要求侧 × 覆盖侧（当前有效且适用该 SKU 的同类型认证），
   按 **SKU / 类目 / 类型**三个口径现算。
6. **撤销与过期是两件事**：撤销（被发证机构撤销或作废）必须写理由（DB 触发器钉住时刻 + 理由），
   两者在覆盖统计里都不算「有有效认证」，但原因不同、界面上分得清。
   **认证档案不可删** —— 它是合规凭据，能做的只有撤销。
7. **扫描件真存**：文件本体写进受管目录（`LocalCertificationDocumentStorage`），
   业务表只存 key + 文件名 + MIME + 字节数 + 上传时刻；文件行**只追加**
   （续期是「再上传一份」，不是「覆盖掉原来那份」）。
8. **跨服务只经授权 HTTP API 读**，且**读不到就显式报读不到**（503 + 原因），
   **绝不**拿空列表冒充 —— 空列表会被读成「这个租户没有 SKU / 没有类目要求」。

## 关键 trade-off

- **内置词表做成全局参考数据 vs 每租户复制一份**。
  复制一份要一个租户开通钩子；钩子没跑之前词表是空的，运营录不进第一张证，
  而「认证类型下拉框是空的」会被读成「这个品类不需要认证」。
  做成全局表就没有这个空窗，代价是「租户自建类型」需要第二张表 —— 用
  `tenant_certification_type` + `product_certification_type_view`（**`security_invoker = true`**，
  否则视图以拥有者权限执行，会绕过 RLS 让别的租户看见你的字典）解决。
- **要求侧登记 vs 从 catalog 推 SKU↔类目映射**。
  catalog 的 `sku_certification` 是**完整度评分的认证声明**（有没有这项要求），
  `spu_category` 虽在同一个库但**没有对外的 SKU→类目授权接口**，
  而「哪个 SKU 属于哪个类目」是 catalog 的写入所有权。
  跨服务直接查表被 AGENTS.md 禁止 ⇒ 本模块自己登记要求侧（可注明来自哪个类目），
  这是**唯一能诚实算出来的口径**，而不是猜一个类目映射。
- **扫描件上传走 base64 JSON vs multipart**。
  浏览器同源代理只转发 JSON（`request.text()` + 固定 `Content-Type: application/json`），
  multipart 过不去；而改代理函数体是**四车道共用的热文件**，并入时必冲突。
  折中：**同一路径两种 `consumes`**（JSON base64 给浏览器、multipart 给直连客户端），
  两边落到同一份受管存储与同一张元数据表；代价是体积涨 33% 且受代理请求体上限约束
  （约 140 KB 原文件，能力声明里把这个上限写给前端）。
- **下载扫描件不靠浏览器代理**：代理只原样透传 `text/csv` / `text/vnd.zpl` / `text/html`，
  二进制过不去（`JSON.parse` 会炸）。所以页面只显示扫描件**元数据**，
  `GET /documents/{id}/file` 留给直连调用方（运维脚本 / 归档任务）。
- **api-proxy 插入锚点**：文件里**最后一条 `prefix: 'channel/'` 元素之后**
  （10.7.1.3 `return-disposals` 那条），与另外三条车道的 warehouse / trade / catalog 组完全不重叠；
  **独立一条 services[] 元素**，绝不往既有 channel 长 alternation 里并分支。
- **两个跨服务同名 DTO 必须改名**：`CertificationInput` 撞 catalog 完整度评分的认证声明、
  `SkuCandidate` 撞 trade 的商品候选。契约 codegen 遇到字段级覆盖会**直接中止且不写任何文件**
  —— 不改名就是整批契约生成不出来。已改为 `ProductCertificationInput` /
  `ProductCertificationSkuCandidate`。

## 文件清单

**后端（channel）**

- `backend/services/channel/src/main/resources/db/migration/V247__product_certification.sql`
  —— 8 张新表 + 4 组守卫触发器 + RLS/GRANT + 生效词表视图；纯新建，零 ALTER 既有表。
- `backend/services/channel/src/main/java/com/starcon/channel/ProductCertificationModels.java`
  —— 传输模型。
- `.../ProductCertificationRules.java` —— 纯规则层（到期状态判定 / floor 取整 / 编码形态），可单测。
- `.../ProductCertificationSourcePort.java` —— catalog 只读端口（SKU + 类目），读不到返回状态而不是抛异常。
- `.../CertificationDocumentStorage.java` + `.../LocalCertificationDocumentStorage.java`
  —— 扫描件存储端口与本地受管目录实现（路径穿越防护）。
- `.../ProductCertificationService.java` —— jOOQ 显式 SQL（台账 / 覆盖统计 / 到期提醒 / 词表 / 策略）。
- `.../ProductCertificationController.java` —— 22 条端点，写操作权限复用 `channel.operation.*`。
- `backend/services/channel/src/main/resources/application.yml` —— multipart 上限（10 MB / 12 MB）。

**测试**

- `.../ProductCertificationIntegrationTest.java` —— 18 例（Testcontainers PostgreSQL 16）。
- `.../ProductCertificationTenancyIntegrationTest.java` —— 3 例（多租户三断言）。
- `.../ProductCertificationRulesTest.java` —— 5 例（纯函数）。
- `.../ProductCertificationCatalogStub.java` —— 测试用的真 HTTP catalog 桩（可切换故障剧本）。

**前端**

- `apps/web/src/app/compliance/certifications/page.tsx` —— 页面。
- `apps/web/src/components/product-certification/product-certification-workspace.tsx` —— 工作区。
- `apps/web/src/components/product-certification/product-certification-model.ts` + `.test.ts` —— 纯函数 + 7 例单测。
- `apps/web/src/app/api/channel/product-certification-allowlist.test.ts` —— 白名单双向断言。
- `apps/web/src/lib/api-proxy.ts` —— **纯增量**新增一条 `product-certifications` 元素。

**契约与台账**

- `packages/api-contracts/src/channel.openapi.json` + `src/contracts/channel.ts`
  —— 由 `python3 tools/codegen/catalog-contracts.py --spec-dir backend/target/openapi` 重新生成。
  ⚠️ 同一次生成也带出了 catalog / trade 的历史漂移（集成头上的 5.4.2.1、6.2.1.3 等模块
  当时没有重新生成契约）；按车道纪律 `packages/api-contracts` 是多车道共用的，
  并入时由 git merge 驱动器按「类型名 / OpenAPI 路径 / 模块 ID」求并。
- `docs/product/capability-blueprint/module-progress.md` —— 只改 16.2.2.2 这一行的「状态」「证据」两格。

## 验证

| 闸 | 命令 | 结果 |
| --- | --- | --- |
| L0 编译 | `cd backend && ./mvnw -q compile` | 通过（exit 0） |
| L0 类型 | `pnpm typecheck` | 3/3 通过 |
| L1 模块 | `./mvnw -pl services/channel -am test -Dtest='ProductCertification*' -Dsurefire.failIfNoSpecifiedTests=false -DfailIfNoSpecifiedTests=false` | **26 例全绿**（18 集成 + 3 多租户 + 5 规则）· BUILD SUCCESS |
| 前端模块 | `pnpm vitest run apps/web/src/components/product-certification apps/web/src/app/api/channel/product-certification-allowlist.test.ts` | 14 例全绿 |
| 前端全量 allowlist（改了 api-proxy.ts ⇒ 必须跑） | `pnpm vitest run apps/web/src/app/api` | **126 个文件 730 个断言全绿** |
| Lint | `pnpm --filter @starcon/web lint` | 通过（警告均在既有其它文件，本模块无告警） |

Testcontainers 的 PostgreSQL 容器在每次 verify 结束后被 ryuk 回收（`docker ps` 里已无残留，
现存 12 个容器都是与本车道无关的既有服务）。

## 踩过的坑（留给下一个车道）

- **PL/pgSQL 变量名与列名同名会报 `column reference is ambiguous`**：
  触发器里 `DECLARE code text := NEW.certification_type_code;` 之后写 `t.code = code`
  会在真跑迁移时才炸。变量名一律加前缀（`v_code` / `v_tenant`）。
- **jOOQ 走 Spring 的 `SQLExceptionTranslator`**：DB 触发器 `RAISE` 抛出来的是
  `org.springframework.dao.DataIntegrityViolationException`，**不是** `org.jooq.exception.DataAccessException`，
  两种都要 catch，否则「唯一键冲突 → 409」这类友好错误会退化成 500。
- **契约 codegen 遇到同名 DTO 直接中止**：它**不写任何文件**，所以报错时先改 Java 里的 DTO 名。

## 后续待办

- **16.2.2.1 合规预检的「认证缺失」维度**：它的判定依据归 16.2.1.1 规则库（未交付），
  但**认证档案的来源本模块已交付**，该维度可以接了 —— 属 16.2.2.1 那条车道的改动，本车道不越界改它的文件。
- **4 刊登（4.3.1.1 一键刊登）消费认证状态**：目前是「下游」关系，尚未接线；
  接的时候读本模块的 `/channel/product-certifications`（同服务同库，可直读也可经 API）。
- **6.1.2.3 供应商资质（trade 服务）的「相关商品阻断判定」**：那边已交付，本模块的产品侧认证
  与它的供应商侧资质是**两件事**；若将来要合并判定，跨服务必须走授权 API，不跨库读表。
- **`product_certification_requirement` 的批量维护**：目前只能单条登记；
  SKU 量大时需要一个「按类目批量生成要求」的入口（类目要求本身在 catalog，仍要走只读接口核对）。
