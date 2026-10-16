import { afterEach, expect, it, vi } from 'vitest';
import { NextRequest } from 'next/server';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { proxy } from '../../../lib/api-proxy';

/**
 * 10.1.1.2 渠道配置的 api-proxy 白名单**双向**断言。
 *
 * <p>白名单只在浏览器端生效：`pnpm test` 直连服务层天然测不出漏登记 ——
 * 漏登记的路径在浏览器里静默 404，而 typecheck / lint / vitest / Playwright 全绿。
 * 所以判据是**真跑 proxy 断言 HTTP 状态码**（拦截 = 404，放行 = 转发），
 * 不是眼看正则、也不是自己再写一份正则去比（这两种方法在本仓库已连续骗过多版）。
 *
 * <p>两个方向都要断言：
 *
 * <ul>
 *   <li><b>放行</b>：从**自己生成的 OpenAPI 契约**（{@code packages/api-contracts/
 *       src/channel.openapi.json}）里反向抽出本模块的真实 path，再逐条断言被放行 ——
 *       写死一份清单只会验证「清单与白名单一致」，而真正会出事的是
 *       「后端加了新形状、白名单忘了登记」。</li>
 *   <li><b>拒绝</b>：未交付 / 不该存在的形状仍然 404。放行不该存在的路径比 404 更糟 ——
 *       它会让界面以为「已经可用」。</li>
 * </ul>
 *
 * <p><b>不误伤其他车道已登记的分支</b>：白名单是多车道共用的**累积型**文件。
 * 本车道只**新增**一条 channel/ services 条目，不动既有那一条 —— 但那必须被断言钉住，
 * 否则下一次并入取错一侧时会静默删掉已交付模块的路由。
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
/** 本模块在契约里的 path 前缀（OpenAPI 写的是完整的 /channel/logistics-channels...）。 */
const MODULE = '/channel/logistics-channels';

afterEach(() => vi.unstubAllGlobals());

/**
 * 从**本模块自己生成的契约**里抽出真实存在的 path。
 *
 * <p>契约里的 path 是 `/channel/logistics-channels/...`，<b>原样</b>传给 proxy。
 *
 * <p>⚠️ 刻意**不削掉** `/channel/` 前缀：本仓库所有 `app/api/<svc>/[...path]/route.ts`
 * 的测试（route.test.ts、catalog、trade …）一律把**服务段一起**放进
 * `params.path`（`['channel','platforms',…]`），因为 `proxy()` 是拿
 * `params.path.join('/')` 去比对 `services[].prefix` 的，prefix 就是 `channel/`。
 * 这里若削掉前缀，传进去的是 `logistics-channels`，`startsWith('channel/')` 为假，
 * **每一条**都会 404 —— 症状是「白名单明明登记了却全被拒」，很容易被误判成正则写错。
 *
 * <p>这份清单**不手写**：手写就退化成「验证白名单与我自己抄的清单一致」，
 * 而真正会出事的是「后端加了新端点、前端白名单忘了登记」。
 */
function realCallSites(): string[] {
  const found = new Set<string>();
  for (const path of Object.keys(SPEC.paths)) {
    if (!path.startsWith(MODULE)) continue;
    // 只削**开头那一个斜杠**（OpenAPI 的 path 以 `/` 开头，而 `params.path` 的各段
    // 拼起来没有开头斜杠）；`/channel/` 这段服务前缀必须保留。
    // 契约里的 `{id}` 换成真 UUID；带花括号的其它参数形状（如 {channelId}）跳过。
    const resolved = path.replace(/^\//, '').replace('{id}', ID);
    if (resolved.includes('{')) continue;
    found.add(resolved);
  }
  return [...found].sort();
}

/**
 * 走一次真实 proxy。
 *
 * <p>{@code fullPath} 是**含服务段**的完整路径（`channel/logistics-channels/...`），
 * URL 与 `params.path` 都由它派生 —— 两者必须同源，否则 URL 上写着 `/api/channel/x`
 * 而 params 传的是 `['x']`，白名单判别就会看到一条生产里不存在的路径。
 */
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
      'channel/logistics-channels',
      'channel/logistics-channels/capabilities',
      'channel/logistics-channels/overview',
      'channel/logistics-channels/eligibility',
      `channel/logistics-channels/${ID}`,
    ]),
  );
});

it('写动作真的转发到 8083（不是只判了正则没转发）', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);

  for (const path of ['channel/logistics-channels', 'channel/logistics-channels/eligibility']) {
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
    expect.stringContaining(':8083/channel/logistics-channels'),
    expect.objectContaining({ method: 'POST' }),
  );
  // 伪造的租户头不得被转发到上游 —— 租户只来自已认证成员关系（AGENTS.md）
  const [, init] = fetcher.mock.calls[0] as [string, RequestInit];
  expect(new Headers(init.headers).get('X-Tenant-Id')).toBeNull();
});

it('未交付 / 不该存在的形状仍然被拒 —— 放行不该存在的路径比 404 更糟', async () => {
  const fetcher = vi.fn();
  vi.stubGlobal('fetch', fetcher);
  const base = 'channel/logistics-channels';
  for (const path of [
    // 同 L3 但未开发的 10.1.1.3 时效与限制
    `${base}/${ID}/transit-times`,
    `${base}/${ID}/limits`,
    // 同 L2 未开发的 10.1.2.1 运费试算 / 10.1.2.2 渠道比价
    `${base}/${ID}/rates`,
    `${base}/${ID}/quotes`,
    // 本模块**刻意没有**跨服务同步：承运商档案同库（10.1.1.1 已落在 channel），
    // 没有需要投影的上游。放行它会让界面以为「有个同步按钮该点」。
    `${base}/sync`,
    // 所属承运商是**建渠道时**的入参，不是一个可单独 GET 的资源
    `${base}/${ID}/carriers`,
    // 字面量子路径刻意不接 id 段，也不得带可选尾巴
    `${base}/capabilities/${ID}`,
    `${base}/overview/extra`,
    `${base}/eligibility/extra`,
    `${base}/${ID}/extra`,
    // 非 UUID 段不应被 id 分支吃掉（完整 UUID 形状，不是 [0-9a-f-]+）
    `${base}/not-a-uuid`,
    `${base}/11111111111111111111`,
    // 单字符：c / e / f 都是合法 hex，写成 `[0-9a-f-]+` 就会被凭空放行
    `${base}/c`,
    `${base}/e`,
    `${base}/f`,
    `${base}/p`,
    // 单数 / 拼写错误
    'channel/logistics-channel',
    `${base}/capabilitie`,
    // 尾斜杠
    `${base}/`,
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
 * 全绿，因为白名单只在浏览器端生效。这里正向钉住每一支的代表形状。
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
    'channel/platforms',
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
  const response = await callProxy('channel/logistics-channels/eligibility', {
    method: 'POST',
    headers: { origin: 'http://evil.example', cookie: 'starcon_session=x' },
    body: '{}',
  });
  expect(response.status).toBe(403);
  expect(fetcher).not.toHaveBeenCalled();
});
