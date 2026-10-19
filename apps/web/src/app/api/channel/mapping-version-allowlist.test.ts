import { afterEach, expect, it, vi } from 'vitest';
import { NextRequest } from 'next/server';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { proxy } from '../../../lib/api-proxy';

/**
 * 4.1.2.2 映射版本 的 api-proxy 白名单**双向**断言。
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
 *   <li><b>拒绝</b>：未交付 / 不该存在的形状仍然 404。放行不该存在的路径比 404 更糟 ——
 *       它会让界面以为「已经可用」。</li>
 * </ul>
 *
 * <p><b>不误伤其他车道已登记的分支</b>：白名单是多车道共用的**累积型**文件。
 * 本车道只**新增一条** `mapping-versions` 元素，既有的 `listings`（4.1.1.1）、
 * `listing-mappings`（4.1.2.1）、`listing-details`（4.1.1.2）、
 * `listing-bulk-edit`（4.2.1.2）等分支一字未动 —— 但那必须被断言钉住，
 * 否则下一次并入取错一侧时会静默删掉已交付模块的路由。
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
const MODULE = '/channel/mapping-versions';

afterEach(() => vi.unstubAllGlobals());

/**
 * 从**本模块自己生成的契约**里抽出真实存在的 path。
 *
 * <p>⚠️ 刻意**不削掉** `/channel/` 前缀：`proxy()` 是拿 `params.path.join('/')`
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

/** 本模块契约里出现过的全部 HTTP 方法（应当只有 get）。 */
function realMethods(): Set<string> {
  const methods = new Set<string>();
  for (const path of Object.keys(SPEC.paths)) {
    if (!path.startsWith(MODULE)) continue;
    for (const method of Object.keys(SPEC.paths[path] ?? {})) methods.add(method);
  }
  return methods;
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
      'channel/mapping-versions',
      'channel/mapping-versions/capabilities',
      'channel/mapping-versions/filter-options',
      'channel/mapping-versions/' + ID,
      'channel/mapping-versions/' + ID + '/product-scope',
      'channel/mapping-versions/' + ID + '/affected-orders',
    ]),
  );
});

it('本模块**没有**任何写方法被放行 —— 改映射生成新版本是 4.1.2.1 的路径', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);

  // 本模块在契约里就只有这 6 条 GET：白名单若放行了 POST/PUT/DELETE 形状，
  // 说明有人加了一条能改写历史版本的路由 —— 那比 404 更糟。
  const methods = [...realMethods()].sort();
  expect(methods, '4.1.2.2 是只读模块，契约里不该出现非 GET 方法').toEqual(['get']);

  // 版本不可回滚：revert / rollback / delete 一律 404
  for (const path of [
    `channel/mapping-versions/${ID}/revert`,
    `channel/mapping-versions/${ID}/rollback`,
    `channel/mapping-versions/${ID}/close`,
    `channel/mapping-versions/${ID}/delete`,
  ]) {
    const response = await callProxy(path, { method: 'DELETE', headers: { cookie: 'x' } });
    expect(response.status, `${path} 不该被放行`).toBe(404);
  }
  expect(fetcher).not.toHaveBeenCalled();
});

it('未交付 / 不该存在的形状仍然被拒 —— 放行不该存在的路径比 404 更糟', async () => {
  const fetcher = vi.fn();
  vi.stubGlobal('fetch', fetcher);
  const base = 'channel/mapping-versions';
  for (const path of [
    // 版本历史随详情一起返回，不单独开一条
    `${base}/${ID}/versions`,
    // 「订单 → 版本」反查归 09 订单（trade 冻结 order_line_component），本服务查不到订单
    `${base}/${ID}/orders`,
    `${base}/${ID}/affected-orders/${ID}`,
    // 套装组件明细归 4.1.2.3
    `${base}/${ID}/components`,
    // 字面量子路径不得带 id 段，也不得带可选尾巴
    `${base}/capabilities/${ID}`,
    `${base}/filter-options/${ID}`,
    `${base}/product-scope/${ID}`,
    // 伪造的 uuid 形状必须被拒：短前缀 / 多一位都不是 uuid
    `${base}/not-a-uuid`,
    `${base}/11111111-1111-1111-1111-1111111111111`,
    // 多余路径段
    `${base}/a/b`,
    `${base}/a/b/c`,
  ]) {
    const response = await callProxy(path, {
      headers: { cookie: 'starcon_session=private-session' },
    });
    expect(response.status, `${path} 不该被放行`).toBe(404);
  }
});

it('不误伤其他车道已登记的分支：既有 channel 路由仍然放行', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);

  for (const path of [
    // 4.1.1.1 Listing 列表
    'channel/listings',
    'channel/listings/capabilities',
    `channel/listings/${ID}`,
    // 4.1.2.1 映射管理（**紧邻的同 L3 上游**，本车道插入锚点的后一条既有分支）
    'channel/listing-mappings',
    'channel/listing-mappings/capabilities',
    'channel/listing-mappings/filter-options',
    `channel/listing-mappings/${ID}`,
    'channel/listing-mappings/unmapped',
    // 4.1.1.2 Listing 详情（本车道插入锚点的前一条）
    'channel/listing-details/capabilities',
    `channel/listing-details/${ID}`,
    `channel/listing-details/${ID}/diff`,
    // 4.2.1.2 批量编辑
    'channel/listing-bulk-edit/capabilities',
    'channel/listing-bulk-edit/templates',
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

it('4.1.2.1 登记成「不放行」的受影响订单路径仍然 404（两个车道用不同前缀，互不干扰）', async () => {
  const fetcher = vi.fn();
  vi.stubGlobal('fetch', fetcher);
  // 4.1.2.1 在自己的白名单测试里断言 `listing-mappings/{id}/affected-orders` 是 404。
  // 本车道走 `mapping-versions/{id}/affected-orders`，**不能**顺手把上游那条也放行了。
  const response = await callProxy(`channel/listing-mappings/${ID}/affected-orders`, {
    headers: { cookie: 'starcon_session=private-session' },
  });
  expect(response.status).toBe(404);
});

it('本车道没有把版本挂到别的服务前缀下', async () => {
  const fetcher = vi.fn();
  vi.stubGlobal('fetch', fetcher);
  // 映射版本是 channel 的事实（版本行与商品范围快照都在 channel 库里），
  // 挂到别的服务前缀上只会让「这条路由归谁」永远答不出来。
  for (const path of [
    'catalog/mapping-versions',
    'trade/mapping-versions',
    'identity/mapping-versions',
  ]) {
    const response = await callProxy(path, {
      headers: { cookie: 'starcon_session=private-session' },
    });
    expect(response.status, `${path} 不该被放行`).toBe(404);
  }
});
