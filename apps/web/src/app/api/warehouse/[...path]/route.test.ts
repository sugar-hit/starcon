import { afterEach, expect, it, vi } from 'vitest';
import { NextRequest } from 'next/server';
import { DELETE, GET, POST } from './route';

afterEach(() => vi.unstubAllGlobals());

function request(path: string, method = 'GET', origin = 'http://localhost:3000') {
  return new NextRequest(`http://localhost:3000/api/warehouse/${path}`, {
    method,
    headers: {
      origin,
      cookie: 'starcon_session=private-session',
      'X-Tenant-Id': 'forged',
      'X-Internal-Key': 'forged',
    },
    ...(method === 'GET' ? {} : { body: '{}' }),
  });
}

/**
 * 7.1.1.1 库存总览：warehouse allowlist 必须放行总览与参照数据的全路径。
 *
 * <p>背景见 docs/backlog/2026-10/2026-10-07-sku-code-api-proxy-allowlist.md：allowlist 是
 * 锚定正则，落不在分支里的路径一律被拦成 404，页面只进错误态，看起来像"后端没实现"。
 * 这组用例把总览用到的每条路由钉住，防止下次再加子路径时静默 404。
 */
it('warehouse 的全部路由被放行且转发到 warehouse 服务', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('[]')));
  vi.stubGlobal('fetch', fetcher);
  const id = '11111111-1111-1111-1111-111111111111';
  for (const path of [
    'warehouse/inventory/overview',
    'warehouse/inventory/buckets',
    'warehouse/inventory/bucket-actions',
    'warehouse/inventory/capabilities',
    'warehouse/inventory/policies',
    `warehouse/inventory/policies/${id}`,
    'warehouse/warehouses',
    'warehouse/legal-entities',
    'warehouse/skus',
    'warehouse/quality-statuses',
    'warehouse/locations',
    'warehouse/lots',
    // 7.5.1.1 调拨单：列表 / 详情 / 分录，以及四个动作端点。
    // 动作路径必须逐个钉住 —— 少放行一个，页面上的按钮就会变成静默 404。
    'warehouse/inventory/transfers',
    `warehouse/inventory/transfers/${id}`,
    `warehouse/inventory/transfers/${id}/movements`,
    `warehouse/inventory/transfers/${id}/confirm`,
    `warehouse/inventory/transfers/${id}/ship`,
    `warehouse/inventory/transfers/${id}/receive`,
    `warehouse/inventory/transfers/${id}/close`,
    // 8.3.4.2 面单与标签打印：能力声明 / 打印机 / 模板 / 打印对象（含打印历史）/
    //   重复打印规则 / 任务列表与详情 / 执行与取消 / 打印内容下载 / 打印日志。
    //   动作与下载路径必须逐个钉住 —— 少放行一个，页面上的按钮就会变成静默 404，
    //   而 allowlist 只在浏览器端生效、后端集成测试发现不了。
    'warehouse/inventory/printing/capabilities',
    'warehouse/inventory/printing/devices',
    'warehouse/inventory/printing/templates',
    `warehouse/inventory/printing/templates/${id}`,
    'warehouse/inventory/printing/reprint-rules',
    'warehouse/inventory/printing/targets',
    `warehouse/inventory/printing/targets/${id}`,
    `warehouse/inventory/printing/targets/${id}/history`,
    'warehouse/inventory/printing/jobs',
    `warehouse/inventory/printing/jobs/${id}`,
    `warehouse/inventory/printing/jobs/${id}/execute`,
    `warehouse/inventory/printing/jobs/${id}/cancel`,
    `warehouse/inventory/printing/jobs/${id}/items/${id}/content`,
    'warehouse/inventory/printing/logs',
  ]) {
    const response = await GET(request(path), { params: { path: path.split('/') } });
    expect(response.status).toBe(200);
    expect(fetcher).toHaveBeenLastCalledWith(
      expect.stringContaining(`:8084/${path}`),
      expect.objectContaining({
        headers: { 'Content-Type': 'application/json', Authorization: 'Bearer private-session' },
      }),
    );
  }
  // 阈值写入走 POST / DELETE，同样必须在白名单里
  expect(
    (await POST(request('warehouse/inventory/policies', 'POST'), {
      params: { path: ['warehouse', 'inventory', 'policies'] },
    })).status,
  ).toBe(200);
  expect(
    (await DELETE(request(`warehouse/inventory/policies/${id}`, 'DELETE'), {
      params: { path: ['warehouse', 'inventory', 'policies', id] },
    })).status,
  ).toBe(200);
  // 7.3.1.2 效期与先进先出的七个形状：alerts / policies / policy/{skuId} 读，
  // policy 写（停用改配置而不是删行），allocation POST 试算，warehouses 与 lots/{skuId} 参照。
  for (const path of [
    'warehouse/inventory/expiry/alerts',
    'warehouse/inventory/expiry/policies',
    'warehouse/inventory/expiry/policy',
    `warehouse/inventory/expiry/policy/${id}`,
    'warehouse/inventory/expiry/warehouses',
    `warehouse/inventory/expiry/lots/${id}`,
  ]) {
    expect((await GET(request(path), { params: { path: path.split('/') } })).status).toBe(200);
  }
  expect(
    (await POST(request('warehouse/inventory/expiry/allocation', 'POST'), {
      params: { path: ['warehouse', 'inventory', 'expiry', 'allocation'] },
    })).status,
  ).toBe(200);
  expect(
    (await POST(request('warehouse/inventory/expiry/policy', 'POST'), {
      params: { path: ['warehouse', 'inventory', 'expiry', 'policy'] },
    })).status,
  ).toBe(200);
  // 刻意**不在这里**断言 DELETE 被代理拒绝：代理只按路径形状放行、不看方法，
  // 「策略行不可删除」的两道真实防线是后端控制器没有 DELETE 形状（返回 405）
  // 与迁移里的 REVOKE DELETE，两者都由 InventoryExpiryIntegrationTest 实跑证明。
});

it('warehouse 白名单外的路径被拒绝且不发出请求', async () => {
  const fetcher = vi.fn();
  vi.stubGlobal('fetch', fetcher);
  // 预占（7.2.1）与调整单（7.4.1.1）尚未交付，不应被代理放行。
  // transfer 曾在这份拒绝清单里，那是 7.5.1.1 交付前的状态；它已并入且前端在调
  // /transfers 这组路径，不放行等于调拨页面在浏览器里 404，故已移出。
  // 7.1.1.2 明细页的行内操作入口指向这些路由，入口契约就位但目标模块还没到 ——
  // 目标模块交付后再加，否则就是一个点下去 404 的死链。
  //
  // 注意 ledger 曾在这份拒绝清单里：那是 7.1.2 尚未交付时的状态，
  // 7.1.2.1 已交付并入后它的前端页面就在调这个路径，不放行等于页面在浏览器里 404，
  // 所以已移出拒绝清单（放行覆盖由上面「已放行」用例钉住）。
  for (const path of [
    'warehouse/inventory/reservations',
    'warehouse/inventory/adjustments',
    'warehouse/internal/access',
  ]) {
    expect(
      (await GET(request(path), { params: { path: path.split('/') } })).status,
    ).toBe(404);
  }
  expect(fetcher).not.toHaveBeenCalled();
});

it('跨站写入被拒绝且不发出请求', async () => {
  const fetcher = vi.fn();
  vi.stubGlobal('fetch', fetcher);
  expect(
    (
      await POST(request('warehouse/inventory/policies', 'POST', 'https://attacker.example'), {
        params: { path: ['warehouse', 'inventory', 'policies'] },
      })
    ).status,
  ).toBe(403);
  expect(fetcher).not.toHaveBeenCalled();
});

/**
 * 8.3.4.2 打印内容下载：ZPL 指令流与 HTML 打印视图都不是 JSON，必须原样透传。
 *
 * <p>为什么要单独钉：走 JSON.parse 的话，条码指令流会被解析失败吞成一个假的 503，
 * 现场看到的是「下载失败」，而真实原因是代理把非 JSON 响应吃掉了。
 * content-disposition 也必须一起带走，否则浏览器拿不到文件名。
 */
it('打印内容（ZPL / HTML）原样透传而不是被 JSON 解析吞成 503', async () => {
  const zpl = '^XA^FO32,24^A0N,34,34^FD面单^FS^XZ\n';
  for (const [contentType, body] of [
    ['text/vnd.zpl; charset=utf-8', zpl],
    ['text/html; charset=utf-8', '<!doctype html><html><body>装箱单</body></html>'],
  ] as const) {
    const fetcher = vi.fn().mockImplementation(() =>
      Promise.resolve(
        new Response(body, {
          status: 200,
          headers: {
            'content-type': contentType,
            'content-disposition': 'attachment; filename="label.zpl"',
          },
        }),
      ),
    );
    vi.stubGlobal('fetch', fetcher);
    // params.path 的第一段是 'warehouse'（服务前缀），与上面「已放行」用例同一写法
    const path =
      'warehouse/inventory/printing/jobs/11111111-1111-1111-1111-111111111111' +
      '/items/22222222-2222-2222-2222-222222222222/content';
    const response = await GET(request(path), { params: { path: path.split('/') } });

    expect(response.status).toBe(200);
    expect(response.headers.get('content-type')).toBe(contentType);
    expect(response.headers.get('content-disposition')).toBe('attachment; filename="label.zpl"');
    // 字节必须一字不差 —— 条码少一个字符，扫码枪就读不出来了
    expect(await response.text()).toBe(body);
  }
});
