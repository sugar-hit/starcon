import { afterEach, expect, it, vi } from 'vitest';
import { NextRequest } from 'next/server';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { proxy } from '../../../lib/api-proxy';

/**
 * 02.6.1.2 去重视图的 api-proxy 白名单**双向**断言。
 *
 * <p>白名单只在浏览器端生效：`pnpm test` 直连服务层天然测不出漏登记 ——
 * 漏登记的路径在浏览器里静默 404，而 typecheck / lint / vitest / Playwright 全绿。
 * 所以判据是**真跑 proxy 断言 HTTP 状态码**（拦截 = 404，放行 = 转发）。
 *
 * <p>`api-proxy.ts` 是**多车道共用的累积型**文件，本车道在同一条 `channel/` 服务的
 * 正则里**新增**一条 `webhook-dedup` 分支，**不替换**既有 alternation。最后一个用例把
 * 兄弟车道已登记的分支逐条钉住：并入时若取错一侧，会静默删掉别人已交付的路由，
 * 而所有常规闸门照样全绿。
 *
 * <p>路径形状说明：本模块的组标识用**首次接收记录的 uuid**，不用
 * `external_event_id` 本身。后者是平台给的任意文本（最长 200 字符，可能含 `/`、
 * `:` 等不适合塞进路径段的字符），拿它做路径段既要在白名单里放宽字符集，
 * 又会在代理层引入编码歧义；uuid 既是稳定标识，也天然落在既有的 `[0-9a-f-]+` 段里。
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

afterEach(() => vi.unstubAllGlobals());

function realCallSites(): string[] {
  const found = new Set<string>();
  for (const path of Object.keys(SPEC.paths)) {
    if (!path.startsWith('/channel/webhook-dedup')) continue;
    const resolved = path.replace(/^\//, '').replace('{firstReceiptId}', ID);
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
      'channel/webhook-dedup',
      'channel/webhook-dedup/summary',
      `channel/webhook-dedup/${ID}`,
      `channel/webhook-dedup/${ID}/claim`,
    ]),
  );
});

it('写动作真的转发到 8083（不是只判了正则没转发）', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);

  const response = await callProxy(`channel/webhook-dedup/${ID}/claim`, {
    method: 'POST',
    headers: {
      origin: 'http://localhost:3000',
      cookie: 'starcon_session=private-session',
      'X-Tenant-Id': 'forged',
      'X-Internal-Key': 'forged',
    },
    body: '{}',
  });
  expect(response.status, 'POST claim 未被放行').not.toBe(404);
  expect(fetcher).toHaveBeenCalledWith(
    expect.stringContaining(':8083/channel/webhook-dedup'),
    expect.objectContaining({ method: 'POST' }),
  );
  // 伪造的租户头不得被转发到上游 —— 租户只来自已认证成员关系（AGENTS.md）
  const [, init] = fetcher.mock.calls[0] as [string, RequestInit];
  expect(new Headers(init.headers).get('X-Tenant-Id')).toBeNull();
});

it('未交付 / 不该存在的形状仍然被拒 —— 放行不该存在的路径比 404 更糟', async () => {
  const fetcher = vi.fn();
  vi.stubGlobal('fetch', fetcher);
  for (const path of [
    // 本模块只有 claim 一个动作，别人的动词不得混进来
    `channel/webhook-dedup/${ID}/complete`,
    `channel/webhook-dedup/${ID}/replay`,
    `channel/webhook-dedup/${ID}/verify`,
    // 集合读不接 id 段，字面量子路径也不接 id 段
    `channel/webhook-dedup/${ID}/summary`,
    `channel/webhook-dedup/summary/${ID}`,
    `channel/webhook-dedup/${ID}/claim/extra`,
    // 本模块只有只读 + claim，没有任何写动词以外的别名
    `channel/webhook-dedup/${ID}/events`,
    `channel/webhook-dedup/${ID}/hits`,
    // 含非十六进制字符的伪 id 必须被拒
    `channel/webhook-dedup/not-a-uuid/claim`,
    `channel/webhook-dedup/not-a-uuid`,
  ]) {
    const response = await callProxy(path, {
      headers: { cookie: 'starcon_session=private-session' },
    });
    expect(response.status, `${path} 不该被放行`).toBe(404);
  }
  expect(fetcher).not.toHaveBeenCalled();
});

it('不误伤兄弟车道已登记的分支：2.6.1.1 与 02.1–02.5 / 10.x 的路由仍然放行', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);

  for (const path of [
    // 02.6.1.1 接收记录与入口登记（本模块的正则与它共用同一条 alternation）
    'channel/webhook-endpoints',
    `channel/webhook-endpoints/${ID}/disable`,
    'channel/webhook-receipts',
    'channel/webhook-receipts/summary',
    `channel/webhook-receipts/${ID}/claim`,
    `channel/webhook-receipts/${ID}/complete`,
    `channel/webhooks/wh_9f8e7d6c5b4a3210fedc`,
    // 02.1–02.3 平台目录、授权连接、店铺
    'channel/platforms',
    'channel/platforms/AMAZON/enums',
    'channel/connections',
    'channel/connections/reminders',
    `channel/connections/${ID}/credential`,
    'channel/shops',
    `channel/shops/${ID}/transfer`,
    // 02.4 同步运行与失败队列
    'channel/sync-cursors',
    `channel/sync-cursors/${ID}/trigger`,
    'channel/sync-runs',
    'channel/sync-failures',
    'channel/sync-failures/summary',
    // 02.5 平台回写与 2.5.2.1 常用回写操作
    'channel/platform-operations',
    `channel/platform-operations/${ID}/unknown`,
    // 2.2.1.2 授权向导 / 10.4.2.1 海外仓连接 / 10.1.x 承运档案
    'channel/oauth/callback',
    'channel/oauth/sessions',
    'channel/warehouse-providers',
    'channel/warehouse-providers/sync-runs',
    'channel/carriers',
    'channel/carriers/overview',
    'channel/handover-batches',
    'channel/handover-batches/capabilities',
    'channel/logistics-channels/overview',
  ]) {
    const response = await callProxy(path, {
      headers: { cookie: 'starcon_session=private-session' },
    });
    expect(response.status, `${path} 被本车道误伤`).not.toBe(404);
  }
});
