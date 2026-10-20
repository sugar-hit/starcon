import { afterEach, expect, it, vi } from 'vitest';
import { NextRequest } from 'next/server';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { proxy } from '../../../lib/api-proxy';

/**
 * 10.7.1.1 退货接收与处置决策的 api-proxy 白名单**双向**断言。
 *
 * <p>与 10.2.2.2 面单模板的那份同构：判据是**真跑 proxy 断言 HTTP 状态码**（拦截 = 404，
 * 放行 = 转发），不是眼看正则、也不是自己再写一份正则去比 —— 这两种方法在本仓库
 * 已连续骗过多版。
 *
 * <p>🔴 本文件额外钉一件事：**共享白名单是累积并集**。本车道只新增一条 `channel/`
 * 条目，兄弟车道（10.1.1.3 时效与限制、10.1.2.1 运费计算器等）的条目一字未动 ——
 * 但那必须被断言钉住，否则下一次并入取错一侧时会静默删掉已交付模块的路由
 * （表现是页面 404，而 typecheck / lint / vitest / Playwright 全部全绿）。
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
const MODULE = '/channel/return-dispositions';
/** 🔴 兄弟车道 10.1.1.3 / 10.1.2.1 的前缀：既已交付，本车道**不得**动它。 */
const SIBLINGS = ['/channel/logistics-transit-limits', '/channel/freight'];

afterEach(() => vi.unstubAllGlobals());

/** 从**本模块自己生成的契约**里抽出真实存在的 path（不手写清单）。 */
function realCallSites(): string[] {
  const found = new Set<string>();
  for (const path of Object.keys(SPEC.paths)) {
    if (!path.startsWith(MODULE)) continue;
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

  expect(sites).toEqual(
    expect.arrayContaining([
      'channel/return-dispositions/capabilities',
      'channel/return-dispositions/overview',
      'channel/return-dispositions/registrations',
      `channel/return-dispositions/registrations/${ID}`,
      'channel/return-dispositions/restrictions',
      `channel/return-dispositions/restrictions/${ID}/revoke`,
      'channel/return-dispositions/eligibility',
      'channel/return-dispositions/decisions',
      `channel/return-dispositions/decisions/${ID}`,
      'channel/return-dispositions/orders',
      `channel/return-dispositions/orders/${ID}`,
      `channel/return-dispositions/orders/${ID}/costs`,
      `channel/return-dispositions/orders/${ID}/cancel`,
      'channel/return-dispositions/events',
    ]),
  );
});

it('写动作真的转发到 8083（不是只判了正则没转发），且不转发伪造的租户头', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);

  for (const path of [
    'channel/return-dispositions/registrations',
    'channel/return-dispositions/decisions',
    'channel/return-dispositions/orders',
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
    expect.stringContaining(':8083/channel/return-dispositions'),
    expect.objectContaining({ method: 'POST' }),
  );
  const [, init] = fetcher.mock.calls[0] as [string, RequestInit];
  expect(new Headers(init.headers).get('X-Tenant-Id')).toBeNull();
});

it('未交付 / 不该存在的形状仍然 404（放行它们比 404 更糟）', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);

  for (const path of [
    // 裸路径：后端没有裸集合读
    'channel/return-dispositions',
    // 决策没有独立消费端点（只能被下发处置单消费）
    `channel/return-dispositions/decisions/${ID}/consume`,
    // 库存指令只出不消费，消费方 7.4.1.1 未交付
    `channel/return-dispositions/orders/${ID}/instructions`,
    // 退运回国 / 弃置销毁是下游 10.7.1.2 / 10.7.1.3 的主权
    `channel/return-dispositions/orders/${ID}/repatriation`,
    `channel/return-dispositions/orders/${ID}/destruction`,
    // 字面量子路径落到 uuid 分支上（b/c/f/e 都可能出现在 [0-9a-f] 里）
    'channel/return-dispositions/b',
    'channel/return-dispositions/bf',
  ]) {
    const response = await callProxy(path, {
      headers: { cookie: 'starcon_session=private-session' },
    });
    expect(response.status, `${path} 不该被放行`).toBe(404);
  }
});

it('🔴 兄弟车道的白名单条目未被本车道挤掉或污染', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);

  for (const sibling of SIBLINGS) {
    const siblingSites = Object.keys(SPEC.paths)
      .filter((path) => path.startsWith(sibling))
      .map((path) => path.replace(/^\//, '').replace('{id}', ID))
      .filter((path) => !path.includes('{'));
    expect(siblingSites.length, `兄弟模块 ${sibling} 的 path 抽不到 —— 断言本身失效了`).toBeGreaterThan(
      0,
    );
    for (const path of siblingSites) {
      const response = await callProxy(path, {
        headers: { cookie: 'starcon_session=private-session' },
      });
      expect(response.status, `兄弟模块 ${path} 被本车道改动挤掉了`).not.toBe(404);
    }
  }
});

it('本模块的路径不会被兄弟的 uuid 分支提前吃掉', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);

  // `eligibility` 的首字母 e 落在 [0-9a-f] 里 —— 若判别顺序写反，这条会被当成
  // 「id=e 的处置单」拼出去。断言它按字面量子路径放行。
  const response = await callProxy('channel/return-dispositions/eligibility', {
    headers: { cookie: 'starcon_session=private-session' },
  });
  expect(response.status).not.toBe(404);
  expect(fetcher).toHaveBeenCalledWith(
    expect.stringContaining(':8083/channel/return-dispositions/eligibility'),
    expect.anything(),
  );
});
