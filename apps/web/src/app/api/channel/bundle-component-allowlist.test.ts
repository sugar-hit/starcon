import { afterEach, expect, it, vi } from 'vitest';
import { NextRequest } from 'next/server';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { proxy } from '../../../lib/api-proxy';

/**
 * 4.1.2.3 套装组件映射 的 api-proxy 白名单**双向**断言。
 *
 * <p>白名单只在浏览器端生效：`pnpm test` 直连服务层天然测不出漏登记 ——
 * 漏登记的路径在浏览器里静默 404，而 typecheck / lint / vitest / Playwright 全绿。
 * 所以判据是**真跑 proxy 断言 HTTP 状态码**（拦截 = 404，放行 = 转发）。
 *
 * <p>两个方向都要断言：
 *
 * <ul>
 *   <li><b>放行</b>：从**自己生成的 OpenAPI 契约**里反向抽出本模块的真实 path，
 *       再逐条断言被放行 —— 写死一份清单只会验证「清单与白名单一致」。</li>
 *   <li><b>拒绝</b>：未交付 / 不该存在的形状仍然 404。</li>
 * </ul>
 *
 * <p><b>不误伤其他车道已登记的分支</b>：白名单是多车道共用的**累积型**文件。
 * 本车道只**新增一条** `bundle-components` 元素，既有的 `listings`（4.1.1.1）、
 * `listing-mappings`（4.1.2.1）、`mapping-versions`（4.1.2.2）等分支一字未动 ——
 * 但那必须被断言钉住，否则下一次并入取错一侧时会静默删掉已交付模块的路由。
 */

const SPEC = JSON.parse(
  readFileSync(
    fileURLToPath(
      new URL('../../../../../../packages/api-contracts/src/channel.openapi.json', import.meta.url),
    ),
    'utf8',
  ),
) as { paths: Record<string, Record<string, unknown>> };

const ID = '11111111-1111-1111-1111-111111111111';
/** 本模块在契约里的 path 前缀。 */
const MODULE = '/channel/bundle-components';

afterEach(() => vi.unstubAllGlobals());

/**
 * 从**本模块自己生成的契约**里抽出真实存在的 path。
 *
 * <p>⚠️ 刻意**不**削掉 `/channel/` 前缀：`proxy()` 是拿 `params.path.join('/')`
 * 去比对 `services[].prefix` 的，prefix 就是 `channel/`。
 */
function realCallSites(): string[] {
  const found = new Set<string>();
  for (const path of Object.keys(SPEC.paths)) {
    if (!path.startsWith(MODULE)) continue;
    const resolved = path.replace(/^\//, '').replace(/\{id\}/g, ID);
    if (resolved.includes('{')) continue;
    found.add(resolved);
  }
  return [...found].sort();
}

function callProxy(fullPath: string, init?: ConstructorParameters<typeof NextRequest>[1]) {
  return proxy(new NextRequest(`http://localhost:3000/api/${fullPath}`, init), {
    params: Promise.resolve({ path: fullPath.split('/') }),
  } as never);
}

it('契约里本模块的每个真实 path 都被放行', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);

  const sites = realCallSites();
  expect(sites.length, '契约里一个本模块 path 都没抽到 —— 断言本身失效了').toBeGreaterThan(0);
  for (const path of sites) {
    const response = await callProxy(path, {
      headers: { cookie: 'starcon_session=private-session' },
    });
    expect(response.status, `${path} 未被放行（白名单漏登记 = 浏览器里静默 404）`).not.toBe(404);
  }

  // 抽到的路径必须**真的**覆盖本模块的形状，否则「只放行了一条」也算通过。
  expect(sites).toEqual(
    expect.arrayContaining([
      'channel/bundle-components/capabilities',
      'channel/bundle-components/filter-options',
      'channel/bundle-components/bindings',
      'channel/bundle-components/bindings/' + ID,
      'channel/bundle-components/bindings/' + ID + '/sellable-check',
      'channel/bundle-components/components',
      'channel/bundle-components/components/' + ID,
    ]),
  );
});

it('本模块的写方法确实存在于契约里 —— 白名单不该把它们挡掉', () => {
  // 4.1.2.3 有真实的写路径（登记 / 改 / 删组件、选套装版本），
  // 与 4.1.2.2 那个「纯只读」模块不同，这里断言契约里确实有非 GET 方法。
  const methods = new Set<string>();
  for (const path of Object.keys(SPEC.paths)) {
    if (!path.startsWith(MODULE)) continue;
    for (const method of Object.keys(SPEC.paths[path] ?? {})) methods.add(method);
  }
  expect([...methods].sort()).toEqual(['delete', 'get', 'post', 'put']);
});

it('未交付 / 不该存在的形状仍然被拒 —— 放行不该存在的路径比 404 更糟', async () => {
  const fetcher = vi.fn();
  vi.stubGlobal('fetch', fetcher);
  const base = 'channel/bundle-components';
  for (const path of [
    // 组件明细随绑定详情一起返回，不单独开一条
    `${base}/bindings/${ID}/components`,
    // 「按组件预占库存」是 07 库存自己的写路径，本服务不发起预占
    `${base}/bindings/${ID}/reservations`,
    `${base}/bindings/${ID}/reserve`,
    // 换版不靠 revert：生成新绑定并退役旧的
    `${base}/bindings/${ID}/revert`,
    // 可售性按**绑定**判定，整套可售性是所有组件的合取，不挂在单个组件上
    `${base}/components/${ID}/sellable-check`,
    // 字面量子路径不得带 id 段，也不得带可选尾巴
    `${base}/capabilities/${ID}`,
    `${base}/filter-options/${ID}`,
    `${base}/bindings/${ID}/sellable-check/extra`,
    // 伪造的 uuid 形状必须被拒：短前缀 / 多一位都不是 uuid
    `${base}/bindings/not-a-uuid`,
    `${base}/bindings/11111111-1111-1111-1111-1111111111111`,
    // 多余路径段
    `${base}/bindings/${ID}/a/b`,
    `${base}/a/b`,
  ]) {
    const response = await callProxy(path, {
      headers: { cookie: 'starcon_session=private-session' },
    });
    expect(response.status, `${path} 不该被放行`).toBe(404);
  }
  expect(fetcher).not.toHaveBeenCalled();
});

it('不误伤其他车道已登记的分支：既有 channel 路由仍然放行', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);

  for (const path of [
    // 4.1.1.1 Listing 列表
    'channel/listings',
    'channel/listings/capabilities',
    `channel/listings/${ID}`,
    // 4.1.2.1 映射管理（**同 L3 的上游**，本车道插入锚点的后一条既有分支）
    'channel/listing-mappings',
    'channel/listing-mappings/capabilities',
    'channel/listing-mappings/filter-options',
    `channel/listing-mappings/${ID}`,
    'channel/listing-mappings/unmapped',
    // 4.1.2.2 映射版本（本车道插入锚点的前一条）
    'channel/mapping-versions',
    `channel/mapping-versions/${ID}`,
    `channel/mapping-versions/${ID}/product-scope`,
    // 4.1.1.2 Listing 详情
    'channel/listing-details/capabilities',
    `channel/listing-details/${ID}`,
    // 4.2.1.2 批量编辑
    'channel/listing-bulk-edit/capabilities',
    // 其他已交付车道的 channel 路由
    'channel/ad-type-diffs/capabilities',
    'channel/rate-comparison/compare',
    'channel/label-templates',
  ]) {
    const response = await callProxy(path, {
      headers: { cookie: 'starcon_session=private-session' },
    });
    expect(response.status, `${path} 被本车道误伤`).not.toBe(404);
  }
});

it('两个车道用不同前缀，互不干扰：上游那条「组件明细」形状仍然 404', async () => {
  const fetcher = vi.fn();
  vi.stubGlobal('fetch', fetcher);
  // 4.1.2.2 在自己的白名单测试里断言 `mapping-versions/{id}/components` 是 404，
  // 本车道走 `bundle-components/bindings/{id}` —— **不能**顺手把上游那条也放行。
  const response = await callProxy(`channel/mapping-versions/${ID}/components`, {
    headers: { cookie: 'starcon_session=private-session' },
  });
  expect(response.status).toBe(404);
});

it('本车道没有把组件映射挂到别的服务前缀下', async () => {
  const fetcher = vi.fn();
  vi.stubGlobal('fetch', fetcher);
  // 组件映射的写入所有权在 channel（刊登侧扣减口径），
  // catalog 那边是**产品定义**（bundle_component），挂过去只会让「这条路由归谁」永远答不出来。
  // ⚠️ `catalog/components` 本身是 3.5.1 已交付的真实路由（商品中心的构成清单），
  //    所以这里只断言「本车道没把**本模块的形状**挂过去」。
  for (const path of [
    'catalog/bundle-components',
    'catalog/bundle-components/components',
    'warehouse/bundle-components',
  ]) {
    const response = await callProxy(path, {
      headers: { cookie: 'starcon_session=private-session' },
    });
    expect(response.status, `${path} 不该被放行`).toBe(404);
  }
});
