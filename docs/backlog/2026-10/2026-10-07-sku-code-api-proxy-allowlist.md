# 3.1.2.3 SKU 编码与条码：api-proxy allowlist 漏 sku-code，前端必然 404

- 创建: 2026-10-07
- Owner: TBD
- 优先级: **P0**
- 关联: 模块 `3.1.2.3`（台账标 已交付，但实际不可用）、`docs/sessions/2026-10/2026-10-07-unit-and-conversion.md` 第 110 行

## 背景

`3.1.2.3 编码与条码` 在台账里标 `已交付`，但浏览器打开 `/products/sku/code` 时，
`apps/web/src/lib/api-proxy.ts` 的 catalog allowlist 正则里**没有 `sku-code`** 这一段。

`api-proxy.ts:63-74` 的逻辑是：剥掉服务前缀后拿路径去 `service.pattern.test(...)`，
匹配不上就 `return NextResponse.json({ message: '接口不存在' }, { status: 404 })`。
catalog 的 pattern 是锚定正则（`^...$`），`sku-code/*` 落在 `products` / `records` /
`skus` / `unit-conversion` 四个分支之外，因此**每条 sku-code 路径都被拦成 404**。

`3.1.2.4` 派发时调度方要求子任务不越界修已交付模块，子任务用真实 route handler 实测确认后
只报告未擅动，故记入本 backlog。

### 实测证据（`feat/3-1-2-4-unit-conversion` @ `4c465cf`）

同批探测真实 `api-proxy` route handler：

| 路径 | 结果 |
| --- | --- |
| `catalog/sku-code/overview` | **404** |
| `catalog/sku-code/rules` | **404** |
| `catalog/sku-code/skus/{id}` | **404** |
| `catalog/sku-code/batch` | **404** |
| `catalog/unit-conversion/*`（6 条） | 200 |
| `catalog/skus/export` | 200 |

后端侧是好的：`SkuCodeController.java:23` 声明 `@RequestMapping("/catalog/sku-code")`，
`overview` / `rules` / `skus/{id}` / `skus/{id}/identifiers` / `batch` / `batches/{id}` 都在。
28 个后端集成测试全绿 —— **问题纯在前端代理白名单一层**，不在业务逻辑。

## 待办内容

- [ ] 在 `apps/web/src/lib/api-proxy.ts` catalog 的 `pattern` 里补 `sku-code` 分支，
      按后端实际路由放行：`rules`（GET/POST/PUT/DELETE + 可选 id）、`overview`、
      `skus/{id}`、`skus/{id}/identifiers`、`skus/{id}/code`、`batch`、`batches/{id}`、
      `identifiers/import`。
- [ ] 同步补一条 api-proxy 放行测试，断言 `catalog/sku-code/*` 不再 404 —— 现有测试只覆盖了
      `skus/export`，所以 3.1.2.3 交付时这条漏网。
- [ ] 复查其余已交付模块的 catalog / channel / identity 路由是否有同类漏放行
      （同一根因：加端点时改了后端忘了改 allowlist）。

## 影响面

- 模块 `3.1.2.3`：编码与条码工作区整页不可用（数据加载阶段就 404，页面只进错误态）。
- 模块 `3.1.2.4`：不受影响，`unit-conversion` 已放行（200）。
- 台账可信度：`3.1.2.3` 的 `已交付` 与实际不符，等于「已交付」标准里第 2 条 API 项未真正闭环。

## 完成标准

- [ ] `/products/sku/code` 在浏览器里能正常加载概览与规则（dev server 实测，非仅单测）
- [ ] api-proxy 放行测试覆盖 `catalog/sku-code/*` 全路径
- [ ] 全量验证绿：`cd backend && ./mvnw verify` / `pnpm typecheck && pnpm lint && pnpm test` /
      `NEXT_TELEMETRY_DISABLED=1 pnpm --filter @starcon/web build`
- [ ] 顺手复查其余模块的 allowlist 覆盖，无同类漏放行
