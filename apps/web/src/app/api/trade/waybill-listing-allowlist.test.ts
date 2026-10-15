import { afterEach, expect, it, vi } from 'vitest';
import { NextRequest } from 'next/server';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { GET, POST } from './[...path]/route';

/**
 * 10.2.1.1 运单列表的 api-proxy 白名单**双向**断言。
 *
 * <p>白名单只在浏览器端生效：`pnpm test` 直连服务层天然测不出漏登记 ——
 * 漏登记的路径在浏览器里静默 404，而 typecheck / lint / vitest 全绿。所以这里逐条钉住，
 * 而且判据是**真跑 proxy 断言 HTTP 状态码**（拦截 = 404，放行 = 转发），
 * 不是眼看正则、也不是自己再写一份正则去比。
 *
 * <p><b>本车道是在 trade 既有组旁边新起一组</b>：本模块新增
 * {@code logistics/waybills}，<b>没有并进</b> waybill-applications / routing /
 * allocation / dropship / pending-shipment / shipment-records / b2b-orders /
 * dropship-margin 任何一组 —— 「去申请」说「这票去换号」，「运单列表」说
 * 「已有哪些运单、各是什么状态」，权限与日志分得开。所以除了「我的形状被放行」，
 * 还必须钉住那些组仍然只放行它们自己的形状（这个文件历史上是冲突高发点）。
 */

const SOURCE = readFileSync(
  fileURLToPath(new URL('../../../components/logistics/waybill-listing-model.ts', import.meta.url)),
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

/**
 * 从模型源码里反向抽出**真实存在**的路径。
 *
 * <p>只写死一份清单会验证「清单与白名单一致」，而真正会出事的是
 * 「源码加了新形状、白名单忘了登记」—— 所以这里从源码反向抽。
 */
function realCallSites(): string[] {
  const base = SOURCE.match(/const base = '([^']+)'/)?.[1] ?? 'trade/logistics/waybills';
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
  // 抽到的路径必须**真的**覆盖本模块的形状，否则「只放行了三个」也算通过。
  expect(sites).toEqual(
    expect.arrayContaining([
      'trade/logistics/waybills/capabilities',
      'trade/logistics/waybills/filter-options',
      'trade/logistics/waybills/list',
    ]),
  );
});

it('POST 形状同样被放行（登记运费 / 批量申请 / 取消 / 打印）', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);

  const paths = [
    `trade/logistics/waybills/${ID}/freight`,
    'trade/logistics/waybills/actions/apply',
    'trade/logistics/waybills/actions/cancel',
    'trade/logistics/waybills/actions/print',
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

  // 10.2.1.3 改址与取消、10.3.1.1 轨迹、10.1.2.1 运费试算、8.3.4.2 面单重渲染
  // 均未交付，本模块**没有**这些端点，放行它们会让界面去找一个不存在的接口。
  // 裸路径 logistics/waybills 同样 404：后端清单在 /list，没有裸映射。
  for (const path of [
    'trade/logistics/waybills',
    `trade/logistics/waybills/${ID}/redirect`,
    `trade/logistics/waybills/${ID}/rerender`,
    `trade/logistics/waybills/${ID}/cancel`,
    'trade/logistics/waybills/tracking',
    'trade/logistics/waybills/rates',
    'trade/logistics/waybills/actions/export',
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

  // 这些是既有车道已放行的形状：它们若在本车道插入时被误伤，
  // 表现是那个页面在浏览器里静默 404，而所有测试仍然全绿。
  for (const path of [
    'trade/logistics/waybill-applications/capabilities',
    'trade/logistics/waybill-applications/requests',
    'trade/logistics/waybill-applications/queue',
    'trade/fulfillment/pending-shipment/capabilities',
    'trade/fulfillment/pending-shipment/units',
    'trade/fulfillment/shipment-records/capabilities',
    'trade/fulfillment/routing/capabilities',
    'trade/fulfillment/allocation/capabilities',
    'trade/fulfillment/dropship/capabilities',
    'trade/fulfillment/dropship-orders/capabilities',
    'trade/fulfillment/dropship-tracking/capabilities',
    'trade/fulfillment/dropship-margin/capabilities',
    'trade/b2b-orders/capabilities',
  ]) {
    const response = await GET(request(path), {
      params: Promise.resolve({ path: path.split('/') }),
    });
    expect(response.status, `兄弟组 ${path} 被本车道的白名单改动挤掉了`).toBe(200);
  }
});

it('本模块没有并进兄弟组：运单申请的路径仍然只放行它自己的形状', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);

  // 「运单列表」的形状挂到「运单申请」的命名空间上 = 两个模块的权限与日志糊在一起，
  // 而它们是**两个模块**（10.2.1.1 只读，10.2.1.2 才是写入所有者）。
  for (const path of [
    'trade/logistics/waybill-applications/list',
    'trade/logistics/waybill-applications/filter-options',
    'trade/logistics/waybill-applications/actions/print',
  ]) {
    const response = await GET(request(path), {
      params: Promise.resolve({ path: path.split('/') }),
    });
    expect(response.status, `${path} 被误放行了（本模块不占运单申请的命名空间）`).toBe(404);
  }
});

it('真跑 proxy：放行的路径转发到 TRADE_URL（8085），不是只过一遍正则', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);

  await GET(request('trade/logistics/waybills/list'), {
    params: Promise.resolve({ path: ['trade', 'logistics', 'waybills', 'list'] }),
  });

  expect(fetcher).toHaveBeenCalled();
  // 转发目标是**解析出的后端地址 + trade/ 前缀**（浏览器侧的 /api/... 不出现）
  const target = String(fetcher.mock.calls[0]?.[0]);
  expect(target).toContain('8085/trade/logistics/waybills/list');
  // 代理把**用户自己的会话 cookie** 换成下游认识的 Authorization bearer ——
  // 租户由下游按已认证成员关系解析，代理**不**转发 X-Tenant-Id。
  const init = fetcher.mock.calls[0]?.[1] as RequestInit | undefined;
  const headers = JSON.stringify(init?.headers ?? '');
  expect(headers).toContain('Authorization');
  expect(headers).not.toContain('forged-tenant');
});
