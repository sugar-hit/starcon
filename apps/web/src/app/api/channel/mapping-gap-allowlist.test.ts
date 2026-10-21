import { afterEach, expect, it, vi } from 'vitest';
import { NextRequest } from 'next/server';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { proxy } from '../../../lib/api-proxy';

/**
 * 2.4.2.3 映射缺失队列 的 api-proxy 白名单**双向**断言。
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
 * 本车道只**新增一条** `mapping-gaps/` 元素，既有的 `listings/`（4.1.1.1）、
 * `listing-details/`（4.1.1.2）、`listing-mappings/`（4.1.2.1）等分支一字未动 ——
 * 但那必须被断言钉住，否则下一次并入取错一侧时会静默删掉已交付模块的路由。
 *
 * <p>🔴 「不按名称猜 SKU」在白名单层也有一份落点：`mapping-gaps/{uuid}/suggest`
 * 必须在**拒绝**清单里 —— 放行它就等于在浏览器侧开了一个自动匹配的入口。
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
const MODULE = '/channel/mapping-gaps';

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
    const resolved = path
      .replace(/^\//, '')
      .replace(/\{id\}/g, ID)
      .replace(/\{listingVariantId\}/g, ID)
      .replace(/\{internalSkuId\}/g, ID);
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
      'channel/mapping-gaps',
      'channel/mapping-gaps/capabilities',
      'channel/mapping-gaps/filter-options',
      'channel/mapping-gaps/summary',
      'channel/mapping-gaps/orders',
      'channel/mapping-gaps/' + ID,
      'channel/mapping-gaps/' + ID + '/map',
      'channel/mapping-gaps/' + ID + '/create-sku',
      'channel/mapping-gaps/' + ID + '/ignore',
    ]),
  );
});

it('写动作真的转发到 8083（不是只判了正则没转发）', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);

  for (const path of [
    'channel/mapping-gaps/' + ID + '/map',
    'channel/mapping-gaps/' + ID + '/create-sku',
    'channel/mapping-gaps/' + ID + '/ignore',
    'channel/mapping-gaps/orders',
  ]) {
    const response = await callProxy(path, {
      method: 'POST',
      headers: {
        origin: 'http://localhost:3000',
        cookie: 'starcon_session=private-session',
        'X-Tenant-Id': 'forged',
        'X-Internal-Key': 'forged',
      },
      body: '{}',
    });
    expect(response.status, `POST ${path} 未被放行`).not.toBe(404);
  }
  expect(fetcher).toHaveBeenCalledWith(
    expect.stringContaining(':8083/channel/mapping-gaps'),
    expect.objectContaining({ method: 'POST' }),
  );
  // 伪造的租户头不得被转发到上游 —— 租户只来自已认证成员关系（AGENTS.md）
  const [, init] = fetcher.mock.calls[0] as [string, RequestInit];
  expect(new Headers(init.headers).get('X-Tenant-Id')).toBeNull();
});

it('未交付 / 不该存在的形状仍然被拒 —— 放行不该存在的路径比 404 更糟', async () => {
  const fetcher = vi.fn();
  vi.stubGlobal('fetch', fetcher);
  const base = 'channel/mapping-gaps';
  for (const path of [
    // 🔴 「按名称猜 SKU」的入口：绝不放行
    `${base}/${ID}/suggest`,
    `${base}/suggest`,
    // 回填由映射版本落库的 DB 触发器完成，没有独立端点
    `${base}/${ID}/backfill`,
    // 「回填受影响订单」未接线（09 订单在 trade 服务）
    `${base}/${ID}/affected-orders`,
    // 批量建立映射归 4.1.2.1，本模块只逐条决策
    `${base}/batch`,
    // 字面量子路径不得带 id 段，也不得带可选尾巴
    `${base}/capabilities/${ID}`,
    `${base}/summary/${ID}`,
    `${base}/filter-options/${ID}`,
    `${base}/orders/${ID}`,
    // 动作段不得脱离 uuid 段
    `${base}/map`,
    `${base}/ignore`,
    `${base}/create-sku`,
    // 伪造的 uuid 形状必须被拒：短前缀 / 多一位都不是 uuid
    `${base}/not-a-uuid`,
    `${base}/11111111-1111-1111-1111-1111111111111`,
    // 多余路径段
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
    // 4.1.1.2 Listing 详情
    'channel/listing-details/capabilities',
    `channel/listing-details/${ID}`,
    // 4.1.2.1 映射管理（本模块的下游：映射到已有 SKU 走它的写入路径）
    'channel/listing-mappings',
    'channel/listing-mappings/unmapped',
    `channel/listing-mappings/${ID}`,
    // 4.2.2.1 内容回写（本车道插入锚点的紧邻前一条）
    'channel/listing-content-writeback/capabilities',
    // 2.6.2.1 增量补拉
    'channel/backfills',
    'channel/backfills/summary',
    // 2.4.2.1 失败队列 / 2.4.2.2 人工重放（同 L3 的兄弟模块）
    'channel/sync-failures',
    'channel/sync-failures/summary',
  ]) {
    const response = await callProxy(path, {
      headers: { cookie: 'starcon_session=private-session' },
    });
    expect(response.status, `${path} 被本车道误伤`).not.toBe(404);
  }
});

it('本车道没有把队列挂到别的服务前缀下', async () => {
  const fetcher = vi.fn();
  vi.stubGlobal('fetch', fetcher);
  // 映射缺失是 channel 的事实（平台变体在 channel 库里），挂到别的服务前缀上
  // 只会让「这条路由归谁」永远答不出来。
  for (const path of [
    'catalog/mapping-gaps',
    'trade/mapping-gaps',
    'identity/mapping-gaps',
  ]) {
    const response = await callProxy(path, {
      headers: { cookie: 'starcon_session=private-session' },
    });
    expect(response.status, `${path} 不该被放行`).toBe(404);
  }
});
