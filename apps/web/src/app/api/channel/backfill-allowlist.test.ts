import { afterEach, expect, it, vi } from 'vitest';
import { NextRequest } from 'next/server';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { proxy } from '../../../lib/api-proxy';

/**
 * 02.6.2.1 增量补拉的 api-proxy 白名单**双向**断言。
 *
 * <p>白名单只在浏览器端生效：`pnpm test` 直连服务层天然测不出漏登记 ——
 * 漏登记的路径在浏览器里静默 404，而 typecheck / lint / vitest / Playwright 全绿。
 * 所以判据是**真跑 proxy 断言 HTTP 状态码**（拦截 = 404，放行 = 转发）。
 *
 * <p>`api-proxy.ts` 是**多车道共用的累积型**文件，本车道在 services[] 里**新增**一条独立的
 * `channel/` 元素，**不替换**既有 alternation、也不重排既有条目。最后一个用例把兄弟车道
 * 已登记的分支逐条钉住：并入时若取错一侧，会静默删掉别人已交付的路由，而所有常规闸门照样全绿。
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
    if (path !== '/channel/backfills' && !path.startsWith('/channel/backfills/')) continue;
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

  // 抽到的路径必须真的覆盖本模块的形状，否则「只放行了一条」也算通过。
  expect(sites).toEqual(
    expect.arrayContaining([
      'channel/backfills',
      'channel/backfills/capabilities',
      'channel/backfills/summary',
      'channel/backfills/gaps',
      `channel/backfills/${ID}`,
      `channel/backfills/${ID}/records`,
      `channel/backfills/${ID}/segments`,
      `channel/backfills/${ID}/complete`,
      `channel/backfills/${ID}/cancel`,
    ]),
  );
});

it('写动作真的转发到 8083，且伪造的租户头不被转发', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);

  for (const path of [
    'channel/backfills',
    `channel/backfills/${ID}/records`,
    `channel/backfills/${ID}/complete`,
    `channel/backfills/${ID}/cancel`,
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
    expect.stringContaining(':8083/channel/backfills'),
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
    // 字面量子路径不接 id 段，也不得带可选尾巴
    `channel/backfills/capabilities/${ID}`,
    `channel/backfills/summary/extra`,
    // 本模块只有 records / segments / complete / cancel 四个动作
    `channel/backfills/${ID}/retry`,
    `channel/backfills/${ID}/records/${ID}`,
    `channel/backfills/${ID}/dispatch`,
    // 集合读不接 id 段
    `channel/backfills/${ID}/`,
    // 含非十六进制字符的伪 id 必须被拒
    `channel/backfills/not-a-uuid/records`,
    // 与 2.6.1.1 接收记录是同一条通道上的两个模块，但形状不同，不得互相放行
    'channel/backfills/{id}/claim',
  ]) {
    const response = await callProxy(path, {
      headers: { cookie: 'starcon_session=private-session' },
    });
    expect(response.status, `${path} 不该被放行`).toBe(404);
  }
  expect(fetcher).not.toHaveBeenCalled();
});

it('不误伤其他车道已登记的分支：02.4 / 02.5 / 02.6.1 / 4.1 / 10.x 的路由仍然放行', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);

  for (const path of [
    // 02.4 同步运行与失败队列
    'channel/sync-cursors',
    `channel/sync-cursors/${ID}/trigger`,
    'channel/sync-runs',
    // 02.5 平台回写
    'channel/platform-operations',
    // 02.6.1.1 接收记录（本模块的直接上游）
    'channel/webhook-endpoints',
    'channel/webhook-receipts',
    'channel/webhook-receipts/summary',
    // 4.1.1.1 Listing 列表
    'channel/listings',
    'channel/listings/batch',
    // 2.5.2.1 常用回写
    'channel/writeback-batches',
    // 10.1.1.2 / 10.2.2.2
    'channel/logistics-channels/overview',
    'channel/label-templates',
  ]) {
    const response = await callProxy(path, {
      headers: { cookie: 'starcon_session=private-session' },
    });
    expect(response.status, `${path} 被本车道误伤`).not.toBe(404);
  }
});
