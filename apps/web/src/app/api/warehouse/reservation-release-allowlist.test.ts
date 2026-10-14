import { afterEach, expect, it, vi } from 'vitest';
import { NextRequest } from 'next/server';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { GET, POST } from './[...path]/route';

/**
 * 7.2.1.3 释放与过期的 api-proxy 白名单**双向**断言。
 *
 * <p>白名单只在浏览器端生效：`pnpm test` 直连服务层天然测不出漏登记 ——
 * 漏登记的路径在浏览器里静默 404，而 typecheck / lint / vitest / Playwright 全绿。
 * 所以判据是**真跑 proxy handler 断言 HTTP 状态码**（拦截 = 404，放行 = 转发），
 * 不是眼看正则、也不是自己再写一份正则去比（这两种方法在本仓库已连续骗过 6 版）。
 *
 * <p><b>累积型文件的求并纪律</b>：白名单是多车道共用的，本车道只**就地扩展** {@code reservations}
 * 那一支，绝不收窄既有分支。最后一组「不误伤其他车道」就是为此存在 —— 取错一侧会静默删掉
 * 已交付模块的路由，而表现是页面 404 且所有静态检查全绿。
 *
 * <p><b>反向断言不许省</b>：一个「全部拒绝」的正则也能让正向断言全绿，
 * 所以每组正向用例后面都跟着一组必须 404 的反向用例。
 */

// 相对**本文件**定位：vitest 从仓库根或 apps/web 跑时 cwd 都不同，用 cwd 会静默读空。
const SOURCE = readFileSync(
  fileURLToPath(new URL('../../../components/inventory/inventory-reservation-release-model.ts', import.meta.url)),
  'utf8',
);

const ID = '11111111-1111-1111-1111-111111111111';

afterEach(() => vi.unstubAllGlobals());

function request(path: string, method = 'GET') {
  return new NextRequest(`http://localhost:3000/api/warehouse/${path}`, {
    method,
    headers: {
      origin: 'http://localhost:3000',
      cookie: 'starcon_session=private-session',
      // 伪造的租户头与内部键：代理必须忽略它们，转发时不带。
      'X-Tenant-Id': 'forged',
      'X-Internal-Key': 'forged',
    },
    ...(method === 'GET' ? {} : { body: '{}' }),
  });
}

/**
 * 从模型源码里反向抽出**真实存在**的调用点。
 *
 * <p>模型里所有端点都是 `` `${base}/xxx` `` 拼出来的（base 是常量
 * 'warehouse/inventory/reservations'），所以抽取必须先把模板串**按 base 展开**再匹配 ——
 * 只认字面量会把调用点全部漏成零条，然后「一个都没断言」也算通过，
 * 那正是这类守卫最典型的失效方式。
 */
function realCallSites(): string[] {
  const base = SOURCE.match(/const RESERVATION_RELEASE_BASE = '([^']+)'/)?.[1] ?? '';
  const found = new Set<string>();
  for (const match of SOURCE.matchAll(/`([^`]*)`/g)) {
    const raw = match[1];
    if (!raw || !raw.includes('${RESERVATION_RELEASE_BASE}')) continue;
    const cleaned = raw
      .replace(/\$\{RESERVATION_RELEASE_BASE\}/g, base)
      // ${queryString(parts)} 是查询串，整段去掉；**必须排在下面的通用插值替换之前** ——
      // 否则它会被当成路径参数换成 uuid，拼出「release-candidates<uuid>」这种不存在的形状。
      .replace(/\$\{queryString\([^)]*\)\}/g, '')
      .replace(/\$\{[^}]*\}/g, ID)
      .replace(/\?.*$/, '');
    if (cleaned.includes('${')) continue;
    found.add(cleaned);
  }
  return [...found].sort();
}

it('模型源码里真实存在的每个调用点都被放行', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);

  const sites = realCallSites();
  expect(sites.length, '模型源码里一个调用点都没抽到 —— 断言本身失效了').toBeGreaterThan(0);
  for (const path of sites) {
    const response = await GET(request(path), { params: { path: path.split('/') } });
    expect(response.status, `${path} 未被放行（白名单漏登记 = 浏览器里静默 404）`).toBe(200);
  }
  // 抽到的路径必须**真的**覆盖本模块的形状，否则「只放行了一条」也算通过。
  expect(sites).toEqual(
    expect.arrayContaining([
      'warehouse/inventory/reservations/release-candidates',
      'warehouse/inventory/reservations/expiry-runs',
      `warehouse/inventory/reservations/expiry-runs/${ID}`,
      'warehouse/inventory/reservations/release-records',
      'warehouse/inventory/reservations/release-options',
      'warehouse/inventory/reservations/release-capabilities',
      `warehouse/inventory/reservations/${ID}/release`,
      `warehouse/inventory/reservations/${ID}/expiry`,
    ]),
  );
});

it('写形状被放行，且真的转发到 8084', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);

  for (const path of [
    'warehouse/inventory/reservations/expire-overdue',
    `warehouse/inventory/reservations/${ID}/release`,
    `warehouse/inventory/reservations/${ID}/expiry`,
  ]) {
    const response = await POST(request(path, 'POST'), { params: { path: path.split('/') } });
    expect(response.status, `POST ${path} 未被放行`).toBe(200);
  }
  expect(fetcher).toHaveBeenCalledWith(
    expect.stringContaining(':8084/warehouse/inventory/reservations'),
    expect.objectContaining({ method: 'POST' }),
  );
});

/**
 * **字面量子路径必须排在 [0-9a-f-]+ 之前** —— 这条用例把它的后果钉死。
 *
 * <p>`release-candidates` 的前若干个字符里就有合法十六进制串（release 里含 e/l… 之外，
 * `e` 是十六进制字符），排错就会被 uuid 通配支判成「id=release-candidates 的明细」而
 * **凭空放行一个后端不存在的形状** —— 白名单宁可少放不可放错。
 */
it('集合级字面量子路径不会被 uuid 通配支吞掉', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);
  // 这些路径若被当成 `reservations/{id}/...` 里的 id，就等于凭空开出了后端不存在的端点。
  for (const path of [
    'warehouse/inventory/reservations/release-candidates',
    'warehouse/inventory/reservations/release-records',
    'warehouse/inventory/reservations/release-options',
    'warehouse/inventory/reservations/release-capabilities',
    'warehouse/inventory/reservations/expire-overdue',
    'warehouse/inventory/reservations/expiry-runs',
  ]) {
    const response = await GET(request(path), { params: { path: path.split('/') } });
    expect(response.status, `${path} 未被放行`).toBe(200);
  }
  expect(fetcher, '放行的路径必须真的转发到后端').toHaveBeenCalledWith(
    expect.stringContaining(':8084/warehouse/inventory/reservations/release-candidates'),
    expect.anything(),
  );
});

it('未交付 / 不该存在的形状仍然被拒，且「多一层后缀」也仍然 404', async () => {
  const fetcher = vi.fn();
  vi.stubGlobal('fetch', fetcher);
  for (const path of [
    // 已放行的动作后面再多一层 —— 必须仍然 404（放行一个不存在的动作比 404 更糟）。
    `warehouse/inventory/reservations/${ID}/release/reason`,
    `warehouse/inventory/reservations/${ID}/expiry/clear`,
    'warehouse/inventory/reservations/expire-overdue/force',
    'warehouse/inventory/reservations/expiry-runs/advanced',
    // 非 UUID 段不应被 [0-9a-f-]+ 分支吃掉（release / expiry 已经是合法十六进制字符组合）。
    'warehouse/inventory/reservations/not-a-uuid',
    `warehouse/inventory/reservations/${ID}/rel-ease`,
    // 7.2.1.2 刻意不放行的形状：/entries 不是本链路的时间线（它叫 /timeline）。
    `warehouse/inventory/reservations/${ID}/entries`,
    // 拼错的顶层形状不放行。
    'warehouse/inventory/reservation-release/capabilities',
  ]) {
    const response = await GET(request(path), { params: { path: path.split('/') } });
    expect(response.status, `${path} 不应被放行`).toBe(404);
  }
  expect(fetcher, '被拒的路径不得触发任何上游调用').not.toHaveBeenCalled();
});

/**
 * 不误伤其他车道（含 7.2.1.1 / 7.2.1.2 已经登记的形状）。
 *
 * <p>白名单是多车道共用的**累积型**文件。并入时若取错一侧，会静默删掉已交付模块的路由 ——
 * 表现是页面 404，而 typecheck / lint / vitest / Playwright 全绿，因为白名单只在浏览器端生效。
 */
it('不误伤本车道之前已放行的分支（求并，不是二选一）', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);

  for (const path of [
    // 预占链路另外两个模块
    'warehouse/inventory/reservations',
    'warehouse/inventory/reservations/options',
    'warehouse/inventory/reservations/capabilities',
    `warehouse/inventory/reservations/${ID}/allocations`,
    `warehouse/inventory/reservations/${ID}/allocations/${ID}/commit`,
    `warehouse/inventory/reservations/${ID}/allocations/${ID}/consume`,
    `warehouse/inventory/reservations/${ID}/timeline`,
    `warehouse/inventory/reservations/${ID}/allocation-options`,
    `warehouse/inventory/reservations/${ID}/capabilities`,
    // 其他车道
    'warehouse/inventory/overview',
    'warehouse/location-utilization/capabilities',
    'warehouse/disassembly-orders/capabilities',
    'warehouse/inventory/expiry/alerts',
  ]) {
    const response = await GET(request(path), { params: { path: path.split('/') } });
    expect(response.status, `已交付模块的 ${path} 被本车道误伤 —— 白名单必须求并`).toBe(200);
  }
});

/** 跨源写请求必须被拒（CSRF 闸），与白名单无关但同一份代理代码。 */
it('跨源写请求被 403 拒绝，不转发', async () => {
  const fetcher = vi.fn();
  vi.stubGlobal('fetch', fetcher);
  const path = `warehouse/inventory/reservations/${ID}/release`;
  const response = await POST(
    new NextRequest(`http://localhost:3000/api/warehouse/${path}`, {
      method: 'POST',
      headers: { origin: 'http://evil.example', cookie: 'starcon_session=x' },
      body: '{}',
    }),
    { params: { path: path.split('/') } },
  );
  expect(response.status).toBe(403);
  expect(fetcher).not.toHaveBeenCalled();
});