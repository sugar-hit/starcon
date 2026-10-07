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
  // 7.8.1.1 分配策略配置：capabilities / effective / calculation（试算）/ runs（落库）
  // / runs/{runId}（批次明细）/ {id}（详情）/ {id}/versions（换版）/ {id}/retire（作废）
  // 以及销量登记 allocation-sales（GET 名册 + POST 登记）。
  // 动作路径必须逐个钉住 —— 少放行一个，页面上的按钮就会变成静默 404。
  for (const path of [
    'warehouse/inventory/allocation-policies/capabilities',
    'warehouse/inventory/allocation-policies/effective',
    'warehouse/inventory/allocation-sales',
    'warehouse/inventory/allocation-policies/runs',
    `warehouse/inventory/allocation-policies/runs/${id}`,
    'warehouse/inventory/allocation-policies/calculation',
    `warehouse/inventory/allocation-policies/${id}`,
    `warehouse/inventory/allocation-policies/${id}/versions`,
    `warehouse/inventory/allocation-policies/${id}/retire`,
  ]) {
    expect((await GET(request(path), { params: { path: path.split('/') } })).status).toBe(200);
  }
  for (const path of [
    'warehouse/inventory/allocation-policies/calculation',
    'warehouse/inventory/allocation-policies/runs',
    'warehouse/inventory/allocation-sales',
    `warehouse/inventory/allocation-policies/${id}/versions`,
    `warehouse/inventory/allocation-policies/${id}/retire`,
  ]) {
    expect(
      (await POST(request(path, 'POST'), { params: { path: path.split('/') } })).status,
    ).toBe(200);
  }
  // 序列号与批次的**明细子路径**此前一个都没进白名单：只有集合读
  // （serials / lots 各一条）被放行，而 inventory-lot-model.ts / serial-model.ts
  // 实际在调 stats、{id}、{id}/movements、{id}/distribution —— 后端
  // InventorySerialController / InventoryLotController 这些端点都真实存在。
  // 白名单只在浏览器端生效，`pnpm test` 直连服务层，这类漏登记天然测不出来，
  // 只能在这里钉住；少钉一条，那些页面就会在浏览器里静默 404。
  for (const path of [
    'warehouse/inventory/serials',
    'warehouse/inventory/serials/stats',
    'warehouse/inventory/serials/import',
    'warehouse/inventory/serials/movements',
    'warehouse/inventory/serials/validate-movement',
    `warehouse/inventory/serials/${id}`,
    `warehouse/inventory/serials/${id}/movements`,
    'warehouse/inventory/lots',
    `warehouse/inventory/lots/${id}`,
    `warehouse/inventory/lots/${id}/distribution`,
    `warehouse/inventory/lots/${id}/movements`,
  ]) {
    expect((await GET(request(path), { params: { path: path.split('/') } })).status).toBe(200);
  }
  expect(
    (await POST(request('warehouse/inventory/expiry/policy', 'POST'), {
      params: { path: ['warehouse', 'inventory', 'expiry', 'policy'] },
    })).status,
  ).toBe(200);
  // 刻意**不在这里**断言 DELETE 被代理拒绝：代理只按路径形状放行、不看方法，
  // 「策略行不可删除」的两道真实防线是后端控制器没有 DELETE 形状（返回 405）
  // 与迁移里的 REVOKE DELETE，两者都由 InventoryExpiryIntegrationTest 实跑证明。
});

/**
 * 8.1.1.1 仓库列表：从 `components/warehouse/warehouse-facility-model.ts` **反向抽取**真实调用点。
 *
 * <p>四个形状（列表 GET / 新增 POST / 下拉项 GET / 能力声明 GET）逐条钉住。
 * 白名单只在浏览器端生效，`pnpm test` 直连服务层发现不了漏登记 —— 漏一条就等于
 * 该页面在浏览器里静默 404，看起来像「后端没实现」。
 */
it('8.1.1.1 仓库列表的四个调用形状都被放行', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);
  for (const path of [
    'warehouse/facilities',
    'warehouse/facilities/options',
    'warehouse/facilities/capabilities',
  ]) {
    const response = await GET(request(path), { params: { path: path.split('/') } });
    expect(response.status, `${path} 未被放行`).toBe(200);
    expect(fetcher).toHaveBeenLastCalledWith(
      expect.stringContaining(`:8084/${path}`),
      expect.objectContaining({
        headers: { 'Content-Type': 'application/json', Authorization: 'Bearer private-session' },
      }),
    );
  }
  // 新增走 POST 同一路径
  expect(
    (await POST(request('warehouse/facilities', 'POST'), {
      params: { path: ['warehouse', 'facilities'] },
    })).status,
  ).toBe(200);
});

it('8.1.1.1 刻意不放行仓库详情与改删形状', async () => {
  const fetcher = vi.fn();
  vi.stubGlobal('fetch', fetcher);
  const id = '11111111-1111-1111-1111-111111111111';
  // 详情属 8.1.1.2、改仓库属性属 8.1.1.2、行为参数属 8.1.1.3，三者都未交付。
  // 放行这些形状会让界面以为「按 id 取单仓」「能改能删仓库」已经可用 ——
  // 放行不该存在的路径比 404 更糟。
  for (const path of [`warehouse/facilities/${id}`, 'warehouse/facilities/wrong-segment']) {
    expect(
      (await GET(request(path), { params: { path: path.split('/') } })).status,
      `${path} 不应被放行`,
    ).toBe(404);
  }
  expect(fetcher).not.toHaveBeenCalled();
});

/**
 * 7.8.1.1 分配策略 + 序列号历史/操作头：这几条路径前端确实在调，白名单却一度漏登记。
 *
 * <p>allocation-policies 的 uuid 分支曾自带前导 `\/`，而外层组已经消费了那个斜杠，
 * 于是 `/{id}` 与 `/{id}/retire` 永远匹配不上；外层组还缺 `?`，强制要求子路径，
 * 裸列表与新增（前端 POST 裸路径）一并被拒 —— 新增分配策略在浏览器里静默 404。
 * serial-movements / serial-operations 后端早已实现（InventorySerialController 的
 * @GetMapping），前端也在调，白名单却从来没登记过。
 *
 * <p>白名单只在浏览器端生效，`pnpm test` 直连服务层发现不了漏登记，所以逐条钉住。
 */
it('7.8.1.1 分配策略与序列号历史的调用形状都被放行', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);
  const id = '11111111-1111-1111-1111-111111111111';
  for (const path of [
    'warehouse/inventory/allocation-policies',
    'warehouse/inventory/allocation-policies/capabilities',
    `warehouse/inventory/allocation-policies/${id}`,
    `warehouse/inventory/allocation-policies/${id}/retire`,
    'warehouse/inventory/allocation-sales',
    'warehouse/inventory/serial-movements',
    'warehouse/inventory/serial-operations',
  ]) {
    const response = await GET(request(path), { params: { path: path.split('/') } });
    expect(response.status, `${path} 未被放行`).toBe(200);
  }
  // 新增策略走 POST 裸路径，这条最容易因为「强制要求子路径」被拒
  expect(
    (await POST(request('warehouse/inventory/allocation-policies', 'POST'), {
      params: { path: ['warehouse', 'inventory', 'allocation-policies'] },
    })).status,
  ).toBe(200);
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

/**
 * 8.2.1.1 收货单 + 8.2.1.2 收货差异：两组路径**逐条钉住放行**。
 *
 * <p>白名单是一条共享正则，多条车道各自往同一个组追加分支。这组用例的价值在于
 * 它同时钉住了**上一条车道已交付的 goods-receipts 全路径** ——
 * 取错一侧（整段替换而不是追加）会静默删掉已交付的路由，表现为页面 404，
 * 而 typecheck / lint / vitest / Playwright 全绿，因为白名单只在浏览器端生效。
 *
 * <p>因此新增 8.2.1.2 的分支时，**不得覆盖或收窄**下面 goods-receipts 的任何一条。
 */
it('8.2.1.1 收货单与 8.2.1.2 收货差异的全部调用形状都被放行', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('[]')));
  vi.stubGlobal('fetch', fetcher);
  const id = '11111111-1111-1111-1111-111111111111';

  // 8.2.1.1 收货单：读形状 + 详情 + 分录 + 三个动作。
  for (const path of [
    'warehouse/goods-receipts',
    'warehouse/goods-receipts/capabilities',
    `warehouse/goods-receipts/${id}`,
    `warehouse/goods-receipts/${id}/movements`,
    `warehouse/goods-receipts/${id}/receive`,
    `warehouse/goods-receipts/${id}/close`,
  ]) {
    expect((await GET(request(path), { params: { path: path.split('/') } })).status).toBe(200);
  }
  expect(
    (await POST(request('warehouse/goods-receipts', 'POST'), {
      params: { path: ['warehouse', 'goods-receipts'] },
    })).status,
  ).toBe(200);

  // 8.2.1.2 收货差异：集合读 + 接手 + 详情 + 留证 / 决定 / 索赔 / 提交 / 撤回 / 结案。
  for (const path of [
    'warehouse/goods-receipt-discrepancies',
    'warehouse/goods-receipt-discrepancies/capabilities',
    `warehouse/goods-receipt-discrepancies/${id}`,
  ]) {
    expect((await GET(request(path), { params: { path: path.split('/') } })).status).toBe(200);
  }
  for (const path of [
    'warehouse/goods-receipt-discrepancies',
    `warehouse/goods-receipt-discrepancies/${id}/evidence`,
    `warehouse/goods-receipt-discrepancies/${id}/decide`,
    `warehouse/goods-receipt-discrepancies/${id}/claim`,
    `warehouse/goods-receipt-discrepancies/${id}/claim/submit`,
    `warehouse/goods-receipt-discrepancies/${id}/claim/withdraw`,
    `warehouse/goods-receipt-discrepancies/${id}/close`,
  ]) {
    expect(
      (await POST(request(path, 'POST'), { params: { path: path.split('/') } })).status,
    ).toBe(200);
  }
});
