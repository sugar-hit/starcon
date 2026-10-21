import { afterEach, expect, it, vi } from 'vitest';
import { NextRequest } from 'next/server';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { proxy } from '../../../lib/api-proxy';

/**
 * 10.3.2.2 揽收与签收确认的 api-proxy 白名单**双向**断言。
 *
 * <p>与 10.3.1.1 / 10.1.1.2 / 10.1.1.3 的那几份同构：判据是**真跑 proxy 断言 HTTP
 * 状态码**（拦截 = 404，放行 = 转发），不是眼看正则、也不是自己再写一份正则去比 ——
 * 这两种方法在本仓库已连续骗过多版。
 *
 * <p>🔴 本文件额外钉两件事：
 *
 * <ol>
 *   <li><b>共享白名单是累积并集</b>：本车道只新增一条 `channel/` 条目，
 *       兄弟车道（10.1.1.2 渠道配置、10.1.1.3 时效与限制、10.3.1.1 轨迹查询、
 *       10.3.2.1 交运批次、10.7.1.1 退货接收）的条目一字未动 ——
 *       但那必须被断言钉住，否则下一次并入取错一侧时会静默删掉已交付模块的路由，
 *       症状是页面 404，而 typecheck / lint / vitest / Playwright **全部全绿**。</li>
 *   <li><b>只放行真实实现的路径</b>：本模块**没有**裸集合读
 *       （后端没有 `/channel/logistics-pickup-signoff` 这一条），多放一段会让一个
 *       还不存在的路径看起来像已交付。</li>
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
const MODULE = '/channel/logistics-pickup-signoff';
/** 🔴 兄弟车道的前缀：既已交付，本车道**不得**动它们。 */
const SIBLINGS = [
  '/channel/logistics-channels',
  '/channel/logistics-transit-limits',
  '/channel/logistics-tracking',
  '/channel/handover-batches',
  '/channel/return-dispositions',
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
      'channel/logistics-pickup-signoff/capabilities',
      'channel/logistics-pickup-signoff/overview',
      'channel/logistics-pickup-signoff/cases',
      'channel/logistics-pickup-signoff/cases/sync',
      `channel/logistics-pickup-signoff/cases/${ID}`,
      `channel/logistics-pickup-signoff/cases/${ID}/attest`,
      'channel/logistics-pickup-signoff/reconcile',
      'channel/logistics-pickup-signoff/reminders',
      `channel/logistics-pickup-signoff/reminders/${ID}/acknowledge`,
      `channel/logistics-pickup-signoff/reminders/${ID}/resolve`,
      'channel/logistics-pickup-signoff/notifications',
      `channel/logistics-pickup-signoff/notifications/${ID}/consume`,
      'channel/logistics-pickup-signoff/events',
      'channel/logistics-pickup-signoff/sla-rules',
    ]),
  );
});

it('写动作真的转发到 8083（不是只判了正则没转发），且不转发伪造的租户头', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);

  for (const path of [
    'channel/logistics-pickup-signoff/cases/sync',
    'channel/logistics-pickup-signoff/reconcile',
    `channel/logistics-pickup-signoff/cases/${ID}/attest`,
    'channel/logistics-pickup-signoff/sla-rules',
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
    expect.stringContaining(':8083/channel/logistics-pickup-signoff/reconcile'),
    expect.objectContaining({ method: 'POST' }),
  );
  const [, init] = fetcher.mock.calls[0] as [string, RequestInit];
  expect(new Headers(init.headers).get('X-Tenant-Id')).toBeNull();
});

it('未交付 / 不该存在的形状仍然 404（放行它们比 404 更糟）', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);

  for (const path of [
    // 裸路径：后端这一组端点**没有**裸集合读，契约里也没有这一条
    'channel/logistics-pickup-signoff',
    // 确认时间线就在详情里，没有第二个形状
    `channel/logistics-pickup-signoff/cases/${ID}/events`,
    // 提醒按确认单聚合在详情里
    `channel/logistics-pickup-signoff/cases/${ID}/reminders`,
    // 规则按承运商 + 渠道定位，不按 id
    `channel/logistics-pickup-signoff/sla-rules/${ID}`,
    // 下游消费由下游自己重试，本模块只登记结果
    `channel/logistics-pickup-signoff/notifications/${ID}/retry`,
    // 兄弟模块的形状（10.3.1.1 / 10.3.2.1）不属于本模块
    'channel/logistics-pickup-signoff/pull-tasks',
    'channel/logistics-pickup-signoff/subjects',
    // 字面量子路径落到 uuid 分支上（c / e 都在 [0-9a-f] 里）
    'channel/logistics-pickup-signoff/c',
    'channel/logistics-pickup-signoff/e',
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

  // 兄弟模块的真实 path 一条都不能因为本车道新增而失效
  for (const prefix of SIBLINGS) {
    const siblingSites = Object.keys(SPEC.paths)
      .filter((path) => path.startsWith(prefix))
      .map((path) => path.replace(/^\//, '').replace('{id}', ID))
      .filter((path) => !path.includes('{'));
    expect(siblingSites.length, `兄弟模块 ${prefix} 的 path 抽不到 —— 断言本身失效了`).toBeGreaterThan(
      0,
    );
    for (const path of siblingSites) {
      const response = await callProxy(path, {
        headers: { cookie: 'starcon_session=private-session' },
      });
      expect(response.status, `兄弟模块 ${path} 被本车道改动挤掉了`).not.toBe(404);
    }
  }

  // 兄弟的写端点仍然能转发 —— 与本模块的写端点是两回事
  for (const path of [
    'channel/logistics-channels/eligibility',
    'channel/logistics-transit-limits/precheck',
    'channel/logistics-tracking/subjects/sync',
    'channel/handover-batches/candidates/sync',
  ]) {
    const response = await callProxy(path, {
      method: 'POST',
      headers: {
        origin: 'http://localhost:3000',
        cookie: 'starcon_session=private-session',
      },
      body: '{}',
    });
    expect(response.status, `兄弟模块 POST ${path} 被本车道改动挤掉了`).not.toBe(404);
  }
});

it('本模块的字面量子路径不会被 uuid 分支提前吃掉', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);

  // `cases` 的首字母 c、`events` 的 e 都在 [0-9a-f] 里 —— 断言它们按字面量放行，
  // 而不是被当成「id 是 cases 的确认单」。
  for (const path of [
    'channel/logistics-pickup-signoff/cases',
    'channel/logistics-pickup-signoff/events',
  ]) {
    const response = await callProxy(path, {
      headers: { cookie: 'starcon_session=private-session' },
    });
    expect(response.status).not.toBe(404);
  }
  expect(fetcher).toHaveBeenCalledWith(
    expect.stringContaining(':8083/channel/logistics-pickup-signoff/cases'),
    expect.anything(),
  );
});
