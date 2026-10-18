import { afterEach, expect, it, vi } from 'vitest';
import { NextRequest } from 'next/server';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { proxy } from '../../../lib/api-proxy';

/**
 * 02.6.1.1 接收记录的 api-proxy 白名单**双向**断言。
 *
 * <p>白名单只在浏览器端生效：`pnpm test` 直连服务层天然测不出漏登记 ——
 * 漏登记的路径在浏览器里静默 404，而 typecheck / lint / vitest / Playwright 全绿。
 * 所以判据是**真跑 proxy 断言 HTTP 状态码**（拦截 = 404，放行 = 转发）。
 *
 * <p>`api-proxy.ts` 是**多车道共用的累积型**文件，本车道在同一条 `channel/` 服务的
 * 正则里**新增**分支，**不替换**既有 alternation。最后一个用例把兄弟车道已登记的
 * 分支逐条钉住：并入时若取错一侧，会静默删掉别人已交付的路由，而所有常规闸门照样全绿。
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
/** endpoint_key 是 base64url 片段，不是 uuid —— 刻意含下划线，验证白名单没按 uuid 收窄。 */
const KEY = 'wh_9f8e7d6c5b4a3210fedc';

afterEach(() => vi.unstubAllGlobals());

function realCallSites(): string[] {
  const found = new Set<string>();
  for (const path of Object.keys(SPEC.paths)) {
    if (!path.startsWith('/channel/webhook')) continue;
    const resolved = path.replace(/^\//, '').replace('{id}', ID).replace('{endpointKey}', KEY);
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

  // 抽到的路径必须真的覆盖本模块的形状，否则「只放行了一条」也算通过。
  expect(sites).toEqual(
    expect.arrayContaining([
      'channel/webhook-endpoints',
      `channel/webhook-endpoints/${ID}/disable`,
      'channel/webhook-receipts',
      'channel/webhook-receipts/summary',
      `channel/webhook-receipts/${ID}/claim`,
      `channel/webhook-receipts/${ID}/complete`,
      `channel/webhooks/${KEY}`,
    ]),
  );
});

it('写动作真的转发到 8083（不是只判了正则没转发）', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);

  for (const path of [
    'channel/webhook-endpoints',
    `channel/webhook-endpoints/${ID}/disable`,
    `channel/webhook-receipts/${ID}/claim`,
    `channel/webhook-receipts/${ID}/complete`,
    `channel/webhooks/${KEY}`,
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
    expect.stringContaining(':8083/channel/webhook'),
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
    // 下游未交付：2.6.1.2 去重视图、2.6.2.1 增量补拉
    'channel/webhook-receipts/duplicates',
    'channel/webhook-receipts/events/ORDER-1',
    'channel/webhook-replay',
    'channel/webhook-backfills',
    // 字面量子路径不接 id 段，也不得带可选尾巴
    `channel/webhook-receipts/summary/${ID}`,
    `channel/webhook-endpoints/${ID}/disable/extra`,
    `channel/webhook-receipts/${ID}/extra`,
    // 回调入口固定两段：裸路径与多余段都非法
    'channel/webhooks',
    `channel/webhooks/${KEY}/extra`,
    // 集合读不接 id 段
    `channel/webhook-endpoints/${ID}`,
    // 本模块只有 claim / complete 两个动作，别人的动词不得混进来
    `channel/webhook-receipts/${ID}/replay`,
    `channel/webhook-receipts/${ID}/verify`,
    // 含非十六进制字符的伪 id 必须被拒
    `channel/webhook-receipts/not-a-uuid/claim`,
    `channel/webhook-endpoints/not-a-uuid/disable`,
  ]) {
    const response = await callProxy(path, {
      headers: { cookie: 'starcon_session=private-session' },
    });
    expect(response.status, `${path} 不该被放行`).toBe(404);
  }
  expect(fetcher).not.toHaveBeenCalled();
});

it('不误伤其他车道已登记的分支：02.4 / 02.5 / 10.4 的路由仍然放行', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);

  for (const path of [
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
    // 02.5 平台回写
    'channel/platform-operations',
    `channel/platform-operations/${ID}/unknown`,
    // 2.2.1.2 授权向导
    'channel/oauth/callback',
    'channel/oauth/sessions',
    // 10.4.2.1 海外仓连接
    'channel/warehouse-providers',
    'channel/warehouse-providers/sync-runs',
    // 10.1.x 承运档案、交接清单、渠道配置
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
