import { afterEach, expect, it, vi } from 'vitest';
import { NextRequest } from 'next/server';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { proxy } from '../../../lib/api-proxy';

/**
 * 10.6.1.1 成本分析的 api-proxy 白名单**双向**断言。
 *
 * <p>与 10.2.2.2 的那份同构：判据是**真跑 proxy 断言 HTTP 状态码**（拦截 = 404，
 * 放行 = 转发），不是眼看正则、也不是自己再写一份正则去比 —— 这两种方法在本仓库
 * 已连续骗过多版。
 *
 * <p>🔴 本文件额外钉两件事：
 *
 * <ol>
 *   <li><b>共享白名单是累积并集</b>：本车道只新增一条 `channel/` 条目，
 *       兄弟车道（10.2.2.2 面单模板、2.5.2.1 常用回写操作）的条目一字未动 ——
 *       但那必须被断言钉住，否则下一次并入取错一侧时会静默删掉已交付模块的路由
 *       （症状是页面 404，而 typecheck / lint / vitest / Playwright 全部全绿）。
 *   <li><b>本车道的路径不会被兄弟的 uuid 分支提前吃掉</b>：
 *       `allocations` 与 `parameters` 都是字面量子路径，若判别顺序写反，
 *       它们会被当成「id=allocations 的资源」拼出去。
 * </ol>
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
const MODULE = '/channel/logistics-cost-analysis';
/** 🔴 兄弟车道路径：既已交付，本车道**不得**动它们。 */
const SIBLINGS = ['/channel/label-templates', '/channel/writeback-batches'];

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
      'channel/logistics-cost-analysis/capabilities',
      'channel/logistics-cost-analysis/overview',
      'channel/logistics-cost-analysis/by-channel',
      'channel/logistics-cost-analysis/over-cost-orders',
      'channel/logistics-cost-analysis/allocation-rules',
      'channel/logistics-cost-analysis/allocations',
      `channel/logistics-cost-analysis/allocations/${ID}`,
      `channel/logistics-cost-analysis/allocations/${ID}/lock`,
      'channel/logistics-cost-analysis/parameters',
      `channel/logistics-cost-analysis/parameters/${ID}`,
    ]),
  );
});

it('写动作真的转发到 8083（不是只判了正则没转发），且不转发伪造的租户头', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);

  for (const path of [
    'channel/logistics-cost-analysis/allocation-rules',
    'channel/logistics-cost-analysis/allocations',
    'channel/logistics-cost-analysis/parameters',
    `channel/logistics-cost-analysis/allocations/${ID}/lock`,
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
    expect.stringContaining(':8083/channel/logistics-cost-analysis'),
    expect.objectContaining({ method: 'POST' }),
  );
  const [, init] = fetcher.mock.calls[0] as [string, RequestInit];
  expect(new Headers(init.headers).get('X-Tenant-Id')).toBeNull();
});

it('未交付 / 不该存在的形状仍然 404（放行它们比 404 更糟）', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);

  for (const path of [
    // 逐行明细随详情一起返回，不单独开一条
    `channel/logistics-cost-analysis/allocations/${ID}/lines`,
    // 取代是重算分摊的自然结果，不另开动作端点
    `channel/logistics-cost-analysis/allocations/${ID}/supersede`,
    // 报表就是 overview，不开别名
    'channel/logistics-cost-analysis/report',
    // 成本分量的台账由服务层维护，没有对外的写入口
    'channel/logistics-cost-analysis/components',
    // 裸路径：后端没有这个映射，不该放行
    'channel/logistics-cost-analysis',
    // 字面量子路径落到 uuid 分支上（a/p 都可能出现在 [0-9a-f] 里）
    'channel/logistics-cost-analysis/a',
    'channel/logistics-cost-analysis/p',
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

it('本模块的字面量子路径不会被自己的 uuid 分支提前吃掉', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);

  // `allocations` / `parameters` 的首字母都落在 [0-9a-f] 里 ——
  // 若判别顺序写反，它们会被当成「id=allocations 的资源」拼出去。
  for (const path of [
    'channel/logistics-cost-analysis/allocations',
    'channel/logistics-cost-analysis/parameters',
  ]) {
    const response = await callProxy(path, {
      headers: { cookie: 'starcon_session=private-session' },
    });
    expect(response.status, `${path} 未被放行`).not.toBe(404);
    expect(fetcher).toHaveBeenCalledWith(
      expect.stringContaining(`:8083/channel/logistics-cost-analysis${path.replace('channel/logistics-cost-analysis', '')}`),
      expect.anything(),
    );
  }
});
