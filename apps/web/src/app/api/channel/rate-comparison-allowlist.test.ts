import { afterEach, expect, it, vi } from 'vitest';
import { NextRequest } from 'next/server';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { proxy } from '../../../lib/api-proxy';

/**
 * 10.1.2.2 渠道比价 的 api-proxy 白名单**双向**断言。
 *
 * <p>白名单只在浏览器端生效：`pnpm test` 直连服务层天然测不出漏登记 ——
 * 漏登记的路径在浏览器里静默 404，而 typecheck / lint / vitest / Playwright 全绿。
 * 所以判据是**真跑 proxy 断言 HTTP 状态码**（拦截 = 404，放行 = 转发），
 * 不是眼看正则、也不是自己再写一份正则去比（这两种方法在本仓库已连续骗过多版）。
 *
 * <p>两个方向都要断言：
 *
 * <ul>
 *   <li><b>放行</b>：从**自己生成的 OpenAPI 契约**里反向抽出本模块的真实 path，
 *       再逐条断言被放行 —— 写死一份清单只会验证「清单与白名单一致」，而真正会出事
 *       的是「后端加了新形状、白名单忘了登记」。</li>
 *   <li><b>拒绝</b>：未交付 / 不该存在的形状仍然 404。放行不该存在的路径比 404 更糟 ——
 *       它会让界面以为「已经可用」。</li>
 * </ul>
 *
 * <p><b>不误伤其他车道已登记的分支</b>：白名单是多车道共用的**累积型**文件。
 * 本车道只**新增一条** `rate-comparison/` 元素，既有的 `freight/`（10.1.2.1）、
 * `logistics-transit-limits/`（10.1.1.3）、`logistics-channels/`（10.1.1.2）
 * 一字未动 —— 但那必须被断言钉住，否则下一次并入取错一侧时会静默删掉已交付模块的路由。
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
const MODULE = '/channel/rate-comparison';

afterEach(() => vi.unstubAllGlobals());

/**
 * 从**本模块自己生成的契约**里抽出真实存在的 path。
 *
 * <p>⚠️ 刻意**不削掉** `/channel/` 前缀：`proxy()` 是拿 `params.path.join('/')`
 * 去比对 `services[].prefix` 的，prefix 就是 `channel/`。削掉前缀会让每一条都 404，
 * 症状是「白名单明明登记了却全被拒」，很容易被误判成正则写错。
 *
 * <p>这份清单**不手写**：手写就退化成「验证白名单与我自己抄的清单一致」，
 * 而真正会出事的是「后端加了新端点、前端白名单忘了登记」。
 */
function realCallSites(): string[] {
  const found = new Set<string>();
  for (const path of Object.keys(SPEC.paths)) {
    if (!path.startsWith(MODULE)) continue;
    // 只削**开头那一个斜杠**；`channel/` 这段服务前缀必须保留。
    const resolved = path.replace(/^\//, '').replace('{id}', ID);
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
      'channel/rate-comparison/capabilities',
      // 🔴 两个比价入口：漏登记这两个，比价在浏览器里静默 404
      'channel/rate-comparison/compare',
      'channel/rate-comparison/comparisons',
      'channel/rate-comparison/comparisons/' + ID,
      'channel/rate-comparison/ratings',
      'channel/rate-comparison/shop-priorities',
    ]),
  );
});

it('写动作真的转发到 8083（不是只判了正则没转发）', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);

  for (const path of [
    'channel/rate-comparison/compare',
    'channel/rate-comparison/comparisons',
    'channel/rate-comparison/ratings',
    'channel/rate-comparison/shop-priorities',
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
    expect.stringContaining(':8083/channel/rate-comparison'),
    expect.objectContaining({ method: 'POST' }),
  );
  // 伪造的租户头不得被转发到上游 —— 租户只来自已认证成员关系（AGENTS.md）
  const [, init] = fetcher.mock.calls[0] as [string, RequestInit];
  expect(new Headers(init.headers).get('X-Tenant-Id')).toBeNull();
});

it('未交付 / 不该存在的形状仍然被拒 —— 放行不该存在的路径比 404 更糟', async () => {
  const fetcher = vi.fn();
  vi.stubGlobal('fetch', fetcher);
  const base = 'channel/rate-comparison';
  for (const path of [
    // 裸集合路径刻意不放行：本模块没有裸集合读，一切入口都带子路径
    base,
    // 评分与店铺预设不是按 id 单独取的（列表带筛选即可）
    `${base}/ratings/${ID}`,
    `${base}/shop-priorities/${ID}`,
    // 结算用实际成本，口径不在本模块（比价成本来自 10.1.2.1 的试算）
    `${base}/settlement`,
    `${base}/comparisons/${ID}/settlement`,
    // 字面量子路径刻意不接 id 段，也不得带可选尾巴
    `${base}/compare/${ID}`,
    `${base}/capabilities/${ID}`,
    `${base}/ratings/extra`,
    // 伪造的 uuid 形状必须被拒：全 0 与短前缀都不是 uuid
    `${base}/comparisons/not-a-uuid`,
    `${base}/comparisons/11111111-1111-1111-1111-1111111111111`,
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
    // 10.1.2.1 运费计算器（同一服务，本模块的直接上游）
    'channel/freight/capabilities',
    'channel/freight/estimate',
    'channel/freight/quotes',
    `channel/freight/quotes/${ID}`,
    'channel/freight/rate-cards',
    'channel/freight/country-rules',
    // 10.1.1.2 渠道配置 / 10.1.1.3 时效与限制
    'channel/logistics-channels',
    'channel/logistics-channels/eligibility',
    `channel/logistics-channels/${ID}`,
    'channel/logistics-transit-limits/capabilities',
    'channel/logistics-transit-limits/precheck',
    `channel/logistics-transit-limits/transit-rules/${ID}`,
  ]) {
    const response = await callProxy(path, {
      headers: { cookie: 'starcon_session=private-session' },
    });
    expect(response.status, `${path} 被本车道误伤`).not.toBe(404);
  }
});

it('本车道没有把 freight/compare 放行（比价挂在 rate-comparison/ 下，运费计算器自身没有这个映射）', async () => {
  const fetcher = vi.fn();
  vi.stubGlobal('fetch', fetcher);
  const response = await callProxy('channel/freight/compare', {
    headers: { cookie: 'starcon_session=private-session' },
  });
  expect(response.status).toBe(404);
});
