import { afterEach, expect, it, vi } from 'vitest';
import { NextRequest } from 'next/server';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { proxy } from '../../../lib/api-proxy';

/**
 * 10.3.1.2 异常件的 api-proxy 白名单**双向**断言。
 *
 * <p>与 10.3.1.1 轨迹查询（{@code tracking-allowlist.test.ts}）、10.7.1.1 退货接收
 * （{@code return-disposition-allowlist.test.ts}）同构：判据是**真跑 proxy 断言 HTTP
 * 状态码**（拦截 = 404，放行 = 转发），不是眼看正则、也不是自己再写一份正则去比 ——
 * 这两种方法在本仓库已连续骗过多版。
 *
 * <p>🔴 本模块与 10.3.1.1 **共用同一个前缀** {@code /channel/logistics-tracking}
 * （同一个 L3 里的两个并列子空间），所以本文件额外钉三件事：
 *
 * <ol>
 *   <li><b>共享白名单是累积并集</b>：本车道只**新增一条** `channel/` 条目，
 *       兄弟车道（10.3.1.1 轨迹查询 / 10.1.1.2 渠道配置 / 10.1.1.3 时效与限制 /
 *       10.1.2.1 运费计算器）的条目一字未动 —— 但那必须被断言钉住，否则下一次
 *       并入取错一侧时会静默删掉已交付模块的路由（表现是页面 404，而
 *       typecheck / lint / vitest / Playwright 全部全绿）。</li>
 *   <li><b>字面量子路径不会被 uuid 分支提前吃掉</b>：{@code capabilities} / {@code claims}
 *       的首字母都落在 {@code [0-9a-f]} 里，判别顺序写反就会被当成「id 是 c 的异常件」
 *       拼出去。</li>
 *   <li><b>只放行真实实现的形状</b>：放行后端不存在的映射比 404 更糟 ——
 *       界面会以为功能已交付。</li>
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
/**
 * 本模块在契约里的两个子空间。
 *
 * <p>🔴 不能用 {@code '/channel/logistics-tracking'} 当前缀 —— 那是 10.3.1.1 的前缀，
 * 会把兄弟的 path 一并抽进来，断言就分不清是谁的路由了。
 */
const MODULES = [
  '/channel/logistics-tracking/exceptions',
  '/channel/logistics-tracking/dispositions',
];
/** 🔴 兄弟车道的前缀：既已交付，本车道**不得**动它们。 */
const SIBLING_PREFIXES = [
  '/channel/logistics-channels',
  '/channel/logistics-transit-limits',
  '/channel/freight',
];

afterEach(() => vi.unstubAllGlobals());

/** 从**本模块自己生成的契约**里抽出真实存在的 path（不手写清单）。 */
function realCallSites(): string[] {
  const found = new Set<string>();
  for (const path of Object.keys(SPEC.paths)) {
    if (!MODULES.some((m) => path.startsWith(m))) continue;
    const resolved = path.replace(/^\//, '').replace('{id}', ID);
    if (resolved.includes('{')) continue;
    found.add(resolved);
  }
  return [...found].sort();
}

/** 10.3.1.1 轨迹查询自己的 path（**排除**本模块的两个子空间）。 */
function trackingSiblingSites(): string[] {
  return Object.keys(SPEC.paths)
    .filter(
      (path) =>
        path.startsWith('/channel/logistics-tracking') &&
        !MODULES.some((m) => path.startsWith(m)),
    )
    .map((path) => path.replace(/^\//, '').replace('{id}', ID))
    .filter((path) => !path.includes('{'));
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
      'channel/logistics-tracking/exceptions',
      'channel/logistics-tracking/exceptions/capabilities',
      'channel/logistics-tracking/exceptions/overview',
      'channel/logistics-tracking/exceptions/rules',
      'channel/logistics-tracking/exceptions/scan',
      `channel/logistics-tracking/exceptions/${ID}`,
      `channel/logistics-tracking/exceptions/${ID}/dispositions`,
      `channel/logistics-tracking/exceptions/${ID}/claims`,
      'channel/logistics-tracking/dispositions',
      `channel/logistics-tracking/dispositions/${ID}`,
      'channel/logistics-tracking/dispositions/claims',
      `channel/logistics-tracking/dispositions/claims/${ID}`,
    ]),
  );
});

it('写动作真的转发到 8083（不是只判了正则没转发），且不转发伪造的租户头', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);

  for (const path of [
    'channel/logistics-tracking/exceptions',
    'channel/logistics-tracking/exceptions/rules',
    'channel/logistics-tracking/exceptions/scan',
    `channel/logistics-tracking/exceptions/${ID}/dispositions`,
    `channel/logistics-tracking/exceptions/${ID}/claims`,
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
    expect.stringContaining(':8083/channel/logistics-tracking/exceptions'),
    expect.objectContaining({ method: 'POST' }),
  );
  const [, init] = fetcher.mock.calls[0] as [string, RequestInit];
  expect(new Headers(init.headers).get('X-Tenant-Id')).toBeNull();
});

it('未交付 / 不该存在的形状仍然 404（放行它们比 404 更糟）', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);

  for (const path of [
    // 规则是**租户级**集合，不挂在某一件上
    `channel/logistics-tracking/exceptions/${ID}/rules`,
    // 扫描是集合级动作，不对单件做
    `channel/logistics-tracking/exceptions/${ID}/scan`,
    // 轨迹事件归 10.3.1.1，不在这里
    `channel/logistics-tracking/exceptions/${ID}/events`,
    // 下游待通知任务在异常件详情里，没有第二个形状
    `channel/logistics-tracking/exceptions/${ID}/downstream-tasks`,
    // 单据挂在**异常件**上，不挂在处置记录上
    `channel/logistics-tracking/dispositions/${ID}/claims`,
    // 裸 uuid 不是任何端点
    `channel/logistics-tracking/exceptions/${ID}/`,
    // 字面量子路径落到 uuid 分支上（c/e/f 都在 [0-9a-f] 里）
    'channel/logistics-tracking/exceptions/c',
    'channel/logistics-tracking/exceptions/e',
    'channel/logistics-tracking/dispositions/c',
    // 短 id 不是完整 UUID 形状（[0-9a-f-]+ 那种写法会把它放行）
    'channel/logistics-tracking/exceptions/1111',
    'channel/logistics-tracking/dispositions/claims/1111',
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

  // 10.3.1.1 轨迹查询：与本模块**共用前缀**，最容易被本车道的新条目吃掉
  const trackingSites = trackingSiblingSites();
  expect(trackingSites.length, '兄弟模块 10.3.1.1 的 path 抽不到 —— 断言本身失效了').toBeGreaterThan(
    0,
  );
  for (const path of trackingSites) {
    const response = await callProxy(path, {
      headers: { cookie: 'starcon_session=private-session' },
    });
    expect(response.status, `兄弟模块 ${path} 被本车道改动挤掉了`).not.toBe(404);
  }

  // 其余兄弟车道的前缀
  for (const prefix of SIBLING_PREFIXES) {
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
});

it('本模块的字面量子路径不会被 uuid 分支提前吃掉', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);

  // `capabilities` 的首字母 c 落在 [0-9a-f] 里 —— 若判别顺序写反，这条会被当成
  // 「id 是 c 的异常件」拼出去。断言它按字面量子路径放行。
  const response = await callProxy('channel/logistics-tracking/exceptions/capabilities', {
    headers: { cookie: 'starcon_session=private-session' },
  });
  expect(response.status).not.toBe(404);
  expect(fetcher).toHaveBeenCalledWith(
    expect.stringContaining(':8083/channel/logistics-tracking/exceptions/capabilities'),
    expect.anything(),
  );

  // `claims` 与 `capabilities` 同首字母 c，处置记录下的那条同理
  const claims = await callProxy('channel/logistics-tracking/dispositions/claims', {
    headers: { cookie: 'starcon_session=private-session' },
  });
  expect(claims.status).not.toBe(404);
  expect(fetcher).toHaveBeenCalledWith(
    expect.stringContaining(':8083/channel/logistics-tracking/dispositions/claims'),
    expect.anything(),
  );
});
