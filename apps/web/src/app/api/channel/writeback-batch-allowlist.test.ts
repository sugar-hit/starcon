import { afterEach, expect, it, vi } from 'vitest';
import { NextRequest } from 'next/server';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { proxy } from '../../../lib/api-proxy';

/**
 * 2.5.2.1 常用回写操作的 api-proxy 白名单**双向**断言。
 *
 * <p>白名单只在浏览器端生效：`pnpm test` 直连服务层天然测不出漏登记 ——
 * 漏登记的路径在浏览器里静默 404，而 typecheck / lint / vitest / Playwright 全绿。
 * 所以判据是**真跑 proxy 断言 HTTP 状态码**（拦截 = 404，放行 = 转发），
 * 不是眼看正则、也不是自己再写一份正则去比（这两种方法在本仓库已连续骗过多版）。
 *
 * <p><b>不误伤其他车道已登记的分支</b>：白名单是多车道共用的累积型文件。本车道只新增一条
 * channel/ services 条目、不动既有那一条 —— 但那必须被断言钉住，否则下一次并入取错一侧时会
 * 静默删掉已交付模块的路由。
 */

// 相对**本文件**定位：vitest 从仓库根或 apps/web 跑时 cwd 都不同，用 cwd 会静默读空。
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

/**
 * 从**本模块自己生成的契约**里抽出真实存在的 path。
 *
 * <p>⚠️ 刻意**不削掉** `/channel/` 前缀：proxy() 是拿 `params.path.join('/')` 去比对
 * `services[].prefix` 的，prefix 就是 `channel/`。削掉前缀会让每一条都 404 ——
 * 症状是「白名单明明登记了却全被拒」，很容易被误判成正则写错。
 */
function realCallSites(prefixes: string[]): string[] {
  const found = new Set<string>();
  for (const path of Object.keys(SPEC.paths)) {
    if (!prefixes.some((p) => path.startsWith(p))) continue;
    // 只削**开头那一个斜杠**；`/channel/` 这段服务前缀必须保留。
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

  const sites = realCallSites(['/channel/writeback-targets', '/channel/writeback-batches']);
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
      'channel/writeback-targets',
      `channel/writeback-targets/${ID}/status`,
      'channel/writeback-batches',
      'channel/writeback-batches/preview',
      `channel/writeback-batches/${ID}`,
    ]),
  );
});

it('写动作真的转发到 8083（不是只判了正则没转发），且伪造租户头不被转发', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);

  for (const path of [
    'channel/writeback-targets',
    `channel/writeback-targets/${ID}/status`,
    'channel/writeback-batches',
    'channel/writeback-batches/preview',
  ]) {
    const response = await callProxy(path, {
      method: 'POST',
      headers: {
        origin: 'http://localhost:3000',
        cookie: 'starcon_session=private-session',
        // 伪造的租户头与内部键：代理必须忽略它们，转发时不带。
        'X-Tenant-Id': 'forged',
        'X-Internal-Key': 'forged',
      },
      body: '{}',
    });
    expect(response.status, `POST ${path} 未被放行`).not.toBe(404);
  }
  expect(fetcher).toHaveBeenCalledWith(
    expect.stringContaining(':8083/channel/writeback-batches'),
    expect.objectContaining({ method: 'POST' }),
  );
  // 租户只来自已认证成员关系（AGENTS.md）：伪造头不得被转发到上游。
  for (const [, init] of fetcher.mock.calls as [string, RequestInit][]) {
    expect(new Headers(init.headers).get('X-Tenant-Id')).toBeNull();
    expect(new Headers(init.headers).get('X-Internal-Key')).toBeNull();
  }
});

it('未交付 / 不该存在的形状仍然被拒 —— 放行不该存在的路径比 404 更糟', async () => {
  const fetcher = vi.fn();
  vi.stubGlobal('fetch', fetcher);
  const targets = 'channel/writeback-targets';
  const batches = 'channel/writeback-batches';
  for (const path of [
    // 逐条结果随详情返回，不单独开一条
    `${batches}/${ID}/items`,
    // 目标没有独立详情端点：候选池集合读已带全部字段
    `${targets}/${ID}`,
    // ⚠️ 整批重发是有资损风险的动作：重试按 2.5.1.1 逐条判断（2.5.1.2 未知结果核验）
    `${batches}/${ID}/retry`,
    // 本模块不经任何跨服务同步
    `${targets}/sync`,
    `${batches}/sync`,
    // 拼写错误 / 单数
    'channel/writeback-target',
    'channel/writeback-batch',
    // 非 UUID 段不应被 id 分支吃掉（完整 UUID 形状，不是 [0-9a-f-]+）
    `${batches}/not-a-uuid`,
    `${targets}/not-a-uuid/status`,
    // 单字符：c / f / e 是合法 hex，写成 `[0-9a-f-]+` 就会被凭空放行
    `${batches}/c`,
    `${batches}/f`,
    `${batches}/p`,
    // status 只属于 targets，不属于 targets/{uuid} 的兄弟形状
    `${targets}/status`,
    // 尾斜杠
    `${batches}/`,
  ]) {
    const response = await callProxy(path, {
      headers: { cookie: 'starcon_session=private-session' },
    });
    expect(response.status, `${path} 不应被放行`).toBe(404);
  }
  expect(fetcher, '被拒的路径不得触发任何上游调用').not.toHaveBeenCalled();
});

/**
 * 不误伤其他车道已登记的分支（求并，不是二选一）。
 *
 * <p>本车道只新增一条 services 条目，但白名单是累积型共享文件：并入时若取错一侧，
 * 会静默删掉已交付模块的路由 —— 表现是页面 404，而 typecheck / lint / vitest / Playwright
 * 全绿，因为白名单只在浏览器端生效。
 */
it('不误伤其余服务、以及本模块之外的 channel 分支（求并，不是二选一）', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);

  const cases: string[] = [
    // 同一服务（channel）里其他车道已交付的分支
    'channel/carriers',
    'channel/carriers/capabilities',
    `channel/carriers/${ID}`,
    'channel/handover-batches',
    `channel/handover-batches/${ID}/manifest`,
    'channel/label-templates',
    'channel/ad-type-diffs/profiles',
    'channel/rate-comparison/comparisons',
    'channel/platforms',
    // 同 L3 的兄弟模块 2.5.1.1 回写任务列表（既有 channel 分支，一字未动）
    'channel/platform-operations',
    `channel/platform-operations/${ID}`,
    // 其余服务
    'catalog/kb/overview',
    'identity/roles',
    'trade/processing/orders',
    'warehouse/inventory/overview',
    'crm/knowledge/capabilities',
  ];
  for (const path of cases) {
    const response = await callProxy(path, { headers: { cookie: 'starcon_session=x' } });
    expect(response.status, `${path} 被本车道误伤 —— 白名单必须求并`).not.toBe(404);
  }
});

/** 跨源写请求必须被 403 拒绝（CSRF 闸），与白名单无关但同一份代理代码。 */
it('跨源写请求被 403 拒绝，不转发', async () => {
  const fetcher = vi.fn();
  vi.stubGlobal('fetch', fetcher);
  const response = await callProxy('channel/writeback-batches', {
    method: 'POST',
    headers: { origin: 'http://evil.example', cookie: 'starcon_session=x' },
    body: '{}',
  });
  expect(response.status).toBe(403);
  expect(fetcher).not.toHaveBeenCalled();
});
