import { afterEach, expect, it, vi } from 'vitest';
import { NextRequest } from 'next/server';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { GET, POST } from './[...path]/route';

/**
 * 10.2.1.3 改址与取消的 api-proxy 白名单**双向**断言。
 *
 * <p>白名单只在浏览器端生效：`pnpm test` 直连服务层天然测不出漏登记 ——
 * 漏登记的路径在浏览器里静默 404，而 typecheck / lint / vitest 全绿。所以这里逐条钉住，
 * 而且判据是**真跑 proxy 断言 HTTP 状态码**（拦截 = 404，放行 = 转发）。
 *
 * <p><b>本车道是在 trade 既有组旁边新起一组</b>：本模块新增
 * {@code logistics/waybill-address-change}，<b>没有并进</b> waybills（10.2.1.1）/
 * waybill-applications（10.2.1.2）任何一组。所以除了「我的形状被放行」，
 * 还必须钉住兄弟组仍然只放行它们自己的形状。
 */

const SOURCE = readFileSync(
  fileURLToPath(new URL('../../../components/logistics/waybill-redirect-model.ts', import.meta.url)),
  'utf8',
);

const ID = '11111111-1111-1111-1111-111111111111';

afterEach(() => vi.unstubAllGlobals());

function request(path: string, method = 'GET') {
  return new NextRequest(`http://localhost:3000/api/trade/${path}`, {
    method,
    headers: {
      origin: 'http://localhost:3000',
      cookie: 'starcon_session=private-session',
      'X-Tenant-Id': 'forged',
      'X-Internal-Key': 'forged',
    },
    ...(method === 'GET' ? {} : { body: '{}' }),
  });
}

/** 从模型源码里反向抽出**真实存在**的路径（写死一份清单只验证「清单与白名单一致」）。 */
function realCallSites(): string[] {
  const base = SOURCE.match(/const base = '([^']+)'/)?.[1] ?? 'trade/logistics/waybill-address-change';
  const found = new Set<string>();
  for (const match of SOURCE.matchAll(/`([^`]*)`/g)) {
    const raw = match[1];
    if (!raw || !raw.includes('${base}')) continue;
    const cleaned = raw
      .replace(/\$\{base\}/g, base)
      .replace(/\$\{query\([^)]*\)\}/g, '')
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
    const response = await GET(request(path), {
      params: Promise.resolve({ path: path.split('/') }),
    });
    expect(response.status, `${path} 未被放行（白名单漏登记 = 浏览器里静默 404）`).toBe(200);
  }
  expect(sites).toEqual(
    expect.arrayContaining([
      'trade/logistics/waybill-address-change/capabilities',
      'trade/logistics/waybill-address-change/rules',
      'trade/logistics/waybill-address-change/redirects',
      'trade/logistics/waybill-address-change/notifications',
    ]),
  );
});

it('POST 形状同样被放行（登记规则 / 发起改址 / 发起取消 / 批量预判）', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);

  const paths = [
    'trade/logistics/waybill-address-change/rules',
    `trade/logistics/waybill-address-change/redirects/${ID}/submit`,
    `trade/logistics/waybill-address-change/cancels/${ID}/submit`,
    'trade/logistics/waybill-address-change/precheck',
  ];
  for (const path of paths) {
    const response = await POST(request(path, 'POST'), {
      params: Promise.resolve({ path: path.split('/') }),
    });
    expect(response.status, `POST ${path} 未被放行`).toBe(200);
  }
});

it('未交付的形状仍然 404（放行一个后端不存在的映射 = 让界面以为它可用）', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);

  // 裸路径没有映射（后端清单在 /redirects）；「发送通知 / 标记已送达」**刻意不存在**
  // —— 本模块只建任务，发送与送达状态归 15；10.3 轨迹、10.1 费率也不在本模块。
  for (const path of [
    'trade/logistics/waybill-address-change',
    `trade/logistics/waybill-address-change/notifications/${ID}/send`,
    'trade/logistics/waybill-address-change/tracking',
    'trade/logistics/waybill-address-change/rates',
    'trade/logistics/waybill-address-change/redirects/export',
  ]) {
    const response = await GET(request(path), {
      params: Promise.resolve({ path: path.split('/') }),
    });
    expect(response.status, `${path} 被误放行了（本模块没有这个端点）`).toBe(404);
  }
});

it('兄弟组没有被挤掉或污染', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);

  for (const path of [
    'trade/logistics/waybills/capabilities',
    'trade/logistics/waybills/list',
    'trade/logistics/waybill-applications/capabilities',
    'trade/logistics/waybill-applications/requests',
    'trade/fulfillment/pending-shipment/capabilities',
    'trade/fulfillment/shipment-records/capabilities',
    'trade/sales-orders/change/capabilities',
  ]) {
    const response = await GET(request(path), {
      params: Promise.resolve({ path: path.split('/') }),
    });
    expect(response.status, `兄弟组 ${path} 被本车道的白名单改动挤掉了`).toBe(200);
  }
});

it('本模块没有占用兄弟组的命名空间', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);

  // 改址的形状挂到「运单申请 / 运单列表」上 = 三个模块的权限与日志糊在一起，
  // 而它们是**三个模块**（10.2.1.1 只读、10.2.1.2 才拥有运单号、10.2.1.3 只对已出号的票改/撤）。
  for (const path of [
    `trade/logistics/waybill-applications/${ID}/redirect`,
    `trade/logistics/waybills/${ID}/redirect`,
    'trade/logistics/waybill-applications/cancels',
  ]) {
    const response = await GET(request(path), {
      params: Promise.resolve({ path: path.split('/') }),
    });
    expect(response.status, `${path} 被误放行了（本模块不占兄弟的命名空间）`).toBe(404);
  }
});

it('真跑 proxy：放行的路径转发到 TRADE_URL（8085），且不转发 X-Tenant-Id', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);

  await GET(request('trade/logistics/waybill-address-change/redirects'), {
    params: Promise.resolve({
      path: ['trade', 'logistics', 'waybill-address-change', 'redirects'],
    }),
  });

  expect(fetcher).toHaveBeenCalled();
  const target = String(fetcher.mock.calls[0]?.[0]);
  expect(target).toContain('8085/trade/logistics/waybill-address-change/redirects');
  // 代理把**用户自己的会话 cookie**换成下游认识的 Authorization bearer ——
  // 租户由下游按已认证成员关系解析，代理**不**转发 X-Tenant-Id。
  const init = fetcher.mock.calls[0]?.[1] as RequestInit | undefined;
  const headers = JSON.stringify(init?.headers ?? '');
  expect(headers).toContain('Authorization');
  expect(headers).not.toContain('forged-tenant');
});
