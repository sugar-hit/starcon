import { afterEach, expect, it, vi } from 'vitest';
import { NextRequest } from 'next/server';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { proxy } from '../../../lib/api-proxy';

/**
 * 13.6.2.1 导出与订阅 的 api-proxy 白名单**双向**断言。
 *
 * <p>白名单只在浏览器端生效：`pnpm test` 直连服务层天然测不出漏登记 ——
 * 漏登记的路径在浏览器里静默 404，而 typecheck / lint / vitest / Playwright 全绿。
 * 所以判据是**真跑 proxy 断言 HTTP 状态码**（拦截 = 404，放行 = 转发），
 * 不是眼看正则、也不是自己再写一份正则去比（这两种方法在本仓库已连续骗过多版）。
 *
 * <p>两个方向都要断言：
 *
 * <ul>
 *   <li><b>放行</b>：从**自己生成的 OpenAPI 契约**里反向抽出本模块的真实 path，
 *       再逐条断言被放行 —— 写死一份清单只会验证「清单与白名单一致」，而真正会出事
 *       的是「后端加了新形状、白名单忘了登记」。</li>
 *   <li><b>拒绝</b>：未交付 / 不该存在的形状仍然 404。放行不该存在的路径比 404 更糟 ——
 *       它会让界面以为「导出与订阅」已经可用。</li>
 * </ul>
 *
 * <p><b>🔴 二进制透传也要断言</b>：XLSX / PDF 不是 JSON，也不是文本 ——
 * 走 `upstream.text()` 会按 UTF-8 解码，字节被替换成 U+FFFD，下载下来的文件**打不开**
 * （现场表现是「下载失败」，可服务端明明回了 200）。所以这里断言**字节逐个相同**。
 */

const SPEC = JSON.parse(
  readFileSync(
    fileURLToPath(
      new URL('../../../../../../packages/api-contracts/src/reporting.openapi.json', import.meta.url),
    ),
    'utf8',
  ),
) as { paths: Record<string, Record<string, unknown>> };

const ID = '11111111-1111-1111-1111-111111111111';
/** 本模块在契约里的 path 前缀。 */
const MODULE = '/reporting/custom-report';

afterEach(() => vi.unstubAllGlobals());

/**
 * 从**本模块自己生成的契约**里抽出真实存在的 path。
 *
 * <p>⚠️ 刻意**不削掉** `/reporting/` 前缀：`proxy()` 是拿 `params.path.join('/')`
 * 去比对 `services[].prefix` 的，prefix 就是 `reporting/`。削掉前缀会让每一条都 404。
 */
function realCallSites(): string[] {
  const found = new Set<string>();
  for (const path of Object.keys(SPEC.paths)) {
    if (!path.startsWith(MODULE)) continue;
    const resolved = path.replace(/^\//, '').replace('{jobId}', ID).replace('{deliveryId}', ID);
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

  // 抽到的路径必须**真的**覆盖本模块的形状，否则「只放行了一条」也算通过。
  expect(sites).toEqual(
    expect.arrayContaining([
      'reporting/custom-report/export/sources',
      'reporting/custom-report/export/sync',
      'reporting/custom-report/export/jobs',
      `reporting/custom-report/export/jobs/${ID}`,
      `reporting/custom-report/export/jobs/${ID}/file`,
      `reporting/custom-report/export/jobs/${ID}/cancel`,
      `reporting/custom-report/deliveries/${ID}`,
      'reporting/custom-report/deliveries/dispatch',
    ]),
  );
});

it('写动作真的转发到 8088（不是只判了正则没转发）', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);

  const response = await callProxy('reporting/custom-report/export/jobs', {
    method: 'POST',
    headers: {
      origin: 'http://localhost:3000',
      cookie: 'starcon_session=private-session',
      'X-Tenant-Id': 'forged',
    },
    body: '{}',
  });
  expect(response.status, 'POST export/jobs 未被放行').not.toBe(404);
  expect(fetcher).toHaveBeenCalledWith(
    expect.stringContaining(':8088/reporting/custom-report/export/jobs'),
    expect.objectContaining({ method: 'POST' }),
  );
  // 伪造的租户头不得被转发到上游 —— 租户只来自已认证成员关系（AGENTS.md）
  const [, init] = fetcher.mock.calls[0] as [string, RequestInit];
  expect(new Headers(init.headers).get('X-Tenant-Id')).toBeNull();
});

it('投递的写动作也被放行', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);

  const response = await callProxy('reporting/custom-report/deliveries/dispatch', {
    method: 'POST',
    headers: {
      origin: 'http://localhost:3000',
      cookie: 'starcon_session=private-session',
    },
    body: '{}',
  });
  expect(response.status, 'POST deliveries/dispatch 未被放行').not.toBe(404);
  expect(fetcher).toHaveBeenCalledWith(
    expect.stringContaining(':8088/reporting/custom-report/deliveries/dispatch'),
    expect.objectContaining({ method: 'POST' }),
  );
});

it('🔴 二进制下载原样透传：xlsx 的字节逐个相同（不能被 UTF-8 解码吃掉）', async () => {
  // 造一段**不是合法 UTF-8** 的字节（xlsx / pdf 就是这样的二进制）。
  const bytes = new Uint8Array([0x50, 0x4b, 0x03, 0x04, 0xff, 0xfe, 0x00, 0x80, 0x9f, 0xc3]);
  const fetcher = vi.fn().mockImplementation(
    () =>
      Promise.resolve(
        new Response(bytes, {
          status: 200,
          headers: {
            'Content-Type':
              'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
            'Content-Disposition': 'attachment; filename="R-1.xlsx"',
          },
        }),
      ),
  );
  vi.stubGlobal('fetch', fetcher);

  const response = await callProxy(`reporting/custom-report/export/jobs/${ID}/file`, {
    headers: { cookie: 'starcon_session=private-session' },
  });
  expect(response.status).toBe(200);
  expect(response.headers.get('Content-Disposition')).toContain('R-1.xlsx');
  const received = new Uint8Array(await response.arrayBuffer());
  expect(Array.from(received)).toEqual(Array.from(bytes));
});

it('🔴 PDF 也原样透传', async () => {
  const bytes = new Uint8Array([0x25, 0x50, 0x44, 0x46, 0x2d, 0xff, 0x00, 0xc3]);
  const fetcher = vi.fn().mockImplementation(
    () =>
      Promise.resolve(
        new Response(bytes, {
          status: 200,
          headers: { 'Content-Type': 'application/pdf' },
        }),
      ),
  );
  vi.stubGlobal('fetch', fetcher);

  const response = await callProxy(`reporting/custom-report/export/jobs/${ID}/file`, {
    headers: { cookie: 'starcon_session=private-session' },
  });
  expect(response.status).toBe(200);
  const received = new Uint8Array(await response.arrayBuffer());
  expect(Array.from(received)).toEqual(Array.from(bytes));
});

it('未交付 / 不该存在的形状仍然被拒 —— 放行不该存在的路径比 404 更糟', async () => {
  const fetcher = vi.fn();
  vi.stubGlobal('fetch', fetcher);
  const base = 'reporting/custom-report';
  for (const path of [
    // 裸路径刻意不放行：本模块没有裸集合读，一切入口都带子路径
    base,
    // 顶层没有 id 段
    `${base}/${ID}`,
    // 多一层仍然 404
    `${base}/unknown-sub`,
    // 🔴 导出的 id 段在 **jobs 之下**（export/jobs/{uuid}），顶层 export 下没有 id 段
    `${base}/export`,
    `${base}/export/${ID}`,
    `${base}/export/jobs/${ID}/unknown`,
    // 🔴 订阅计划下面没有 deliveries 子路径
    `${base}/subscriptions/${ID}/deliveries`,
    // 🔴 本模块只读 13.6.1.1 的自定义报表，不另建第二个报表命名空间
    `${base}/report`,
  ]) {
    const response = await callProxy(path, {
      headers: { cookie: 'starcon_session=private-session' },
    });
    expect(response.status, `${path} 不该被放行`).toBe(404);
  }
});

it('不误伤其他车道已登记的分支：13.6.1.1 与其它 reporting 路由仍然放行', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);

  for (const path of [
    // 13.6.1.1 自定义报表（上游）
    'reporting/custom-report/capabilities',
    'reporting/custom-report/metrics',
    'reporting/custom-report/dimensions',
    'reporting/custom-report/reports',
    `reporting/custom-report/reports/${ID}`,
    `reporting/custom-report/reports/${ID}/run`,
    `reporting/custom-report/reports/${ID}/shares`,
    `reporting/custom-report/reports/${ID}/subscriptions`,
    `reporting/custom-report/shares/${ID}`,
    `reporting/custom-report/subscriptions/${ID}`,
    'reporting/custom-report/deliveries',
    'reporting/custom-report/deliveries/tick',
    'reporting/custom-report/shared/0123456789abcdef0123456789abcdef',
    // 13.1.1.1 经营总览 / 投影管道
    'reporting/overview',
    'reporting/overview/capabilities',
    'reporting/projection/events',
    'reporting/projection/refresh',
    // 13.4.1.1 / 13.4.2.1 / 13.4.3.1
    'reporting/profit-report/report',
    'reporting/cost-structure/structure',
    'reporting/profit-alert/alerts',
    'reporting/profit-alert/notifications',
  ]) {
    const response = await callProxy(path, {
      headers: { cookie: 'starcon_session=private-session' },
    });
    expect(response.status, `${path} 被本车道误伤`).not.toBe(404);
  }
});
