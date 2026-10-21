import { afterEach, expect, it, vi } from 'vitest';
import { NextRequest } from 'next/server';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { proxy } from '../../../lib/api-proxy';

/**
 * 10.7.1.3 弃置与销毁的 api-proxy 白名单**双向**断言。
 *
 * <p>与 10.7.1.1 / 10.7.1.2 那两份同构：判据是**真跑 proxy 断言 HTTP 状态码**（拦截 = 404，
 * 放行 = 转发），不是眼看正则、也不是自己再写一份正则去比 —— 这两种方法在本仓库
 * 已连续骗过多版。
 *
 * <p>🔴 本文件额外钉一件事：**共享白名单是累积并集**。本车道只新增一条 `channel/`
 * 条目，兄弟车道（10.1.1.3 时效与限制、10.1.2.1 运费计算器、10.1.2.2 渠道比价、
 * 10.7.1.1 退货处置、10.7.1.2 退运回国）的条目一字未动 —— 但那必须被断言钉住，
 * 否则下一次并入取错一侧时会静默删掉已交付模块的路由
 * （表现是页面 404，而 typecheck / lint / vitest / Playwright **全部全绿**）。
 *
 * <p>🔴 **负向样本一律用本模块自己的前缀**（`channel/return-disposals/...`），
 * 绝不拿兄弟车道的前缀当「未交付形状」：兄弟前缀可能是**同批同时交付**的合法前缀，
 * 拿它当负向样本 ⇒ 并入后必假红；更糟的是若那条白名单被取错一侧而静默删掉，
 * 负向断言反而会**绿** —— 「已交付的路由被误删」这个真事故被测试盖住。
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
const MODULE = '/channel/return-disposals';
/** 🔴 兄弟车道的前缀：既已交付，本车道**不得**动它。 */
const SIBLINGS = [
  '/channel/logistics-transit-limits',
  '/channel/freight',
  '/channel/rate-comparison',
  '/channel/return-dispositions',
  '/channel/return-shipbacks',
];

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
      'channel/return-disposals/capabilities',
      'channel/return-disposals/overview',
      'channel/return-disposals/candidates',
      'channel/return-disposals/policies',
      `channel/return-disposals/policies/${ID}/revoke`,
      'channel/return-disposals/orders',
      `channel/return-disposals/orders/${ID}`,
      `channel/return-disposals/orders/${ID}/schedule`,
      `channel/return-disposals/orders/${ID}/evidence`,
      `channel/return-disposals/orders/${ID}/costs`,
      `channel/return-disposals/orders/${ID}/claim-basis`,
      `channel/return-disposals/orders/${ID}/dispose`,
      `channel/return-disposals/orders/${ID}/cancel`,
      `channel/return-disposals/claim-bases/${ID}/submit`,
      `channel/return-disposals/claim-bases/${ID}/reject`,
      'channel/return-disposals/events',
    ]),
  );
});

it('写动作真的转发到 8083（不是只判了正则没转发），且不转发伪造的租户头', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);

  for (const path of [
    'channel/return-disposals/orders',
    `channel/return-disposals/orders/${ID}/evidence`,
    `channel/return-disposals/orders/${ID}/dispose`,
    `channel/return-disposals/claim-bases/${ID}/submit`,
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
    expect.stringContaining(':8083/channel/return-disposals'),
    expect.objectContaining({ method: 'POST' }),
  );
  const [, init] = fetcher.mock.calls[0] as [string, RequestInit];
  expect(new Headers(init.headers).get('X-Tenant-Id')).toBeNull();
});

it('未交付 / 不该存在的形状仍然 404（放行它们比 404 更糟）', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);

  // 🔴 下面每一条都**只用本模块自己的前缀** —— 绝不拿兄弟车道的前缀当负向样本。
  for (const path of [
    // 裸路径：后端没有裸集合读
    'channel/return-disposals',
    // 销毁单没有「再销毁一次」的子资源
    `channel/return-disposals/orders/${ID}/repatriation`,
    // 费用没有独立删除端点
    `channel/return-disposals/orders/${ID}/costs/${ID}`,
    // 凭证要求按主体定位（品牌 / 平台 / 租户默认），没有「按 id 取单条」这个端点
    `channel/return-disposals/policies/${ID}`,
    // 索赔依据挂在销毁单下，按销毁单寻址
    `channel/return-disposals/claim-bases/${ID}`,
    // 依据不可删
    `channel/return-disposals/claim-bases/${ID}/delete`,
    // 销毁单不可删
    `channel/return-disposals/orders/${ID}/delete`,
    // 一个后端根本不存在的形状
    'channel/return-disposals/undeclared-action',
    // 字面量子路径落到 uuid 分支上（b/c/f/e 都可能出现在 [0-9a-f] 里）
    'channel/return-disposals/b',
    'channel/return-disposals/bf',
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

  // `candidates` 的首字母 c 落在 [0-9a-f] 里 —— 若判别顺序写反，这条会被当成
  // 「id=c 的销毁单」拼出去。断言它按字面量子路径放行。
  const response = await callProxy('channel/return-disposals/candidates', {
    headers: { cookie: 'starcon_session=private-session' },
  });
  expect(response.status).not.toBe(404);
  expect(fetcher).toHaveBeenCalledWith(
    expect.stringContaining(':8083/channel/return-disposals/candidates'),
    expect.anything(),
  );
});

it('🔴 本模块的前缀不与兄弟模块互相吃掉（首 token 不同）', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);

  // 兄弟模块自己的前缀仍然走它自己的分支（不是被本车道的前缀截走）
  for (const sibling of ['channel/return-dispositions/orders', 'channel/return-shipbacks/consignments']) {
    const response = await callProxy(sibling, {
      headers: { cookie: 'starcon_session=private-session' },
    });
    expect(response.status, `${sibling} 不该被本车道的前缀吃掉`).not.toBe(404);
    expect(fetcher).toHaveBeenCalledWith(
      expect.stringContaining(`:8083/${sibling}`),
      expect.anything(),
    );
  }
});
