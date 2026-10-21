import { afterEach, expect, it, vi } from 'vitest';
import { NextRequest } from 'next/server';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { GET, POST } from './[...path]/route';

/**
 * 7.7.2.2 平台托管与全托管备货的 api-proxy 白名单**双向**断言（**按模块一份**）。
 *
 * <p>白名单只在浏览器端生效：`pnpm test` 直连服务层天然测不出漏登记 ——
 * 漏登记的路径在浏览器里静默 404，而 typecheck / lint / vitest / Playwright **全部全绿**。
 * 判据是**真跑 proxy handler 断言 HTTP 状态码**（拦截 = 404，放行 = 转发），
 * 不是眼看正则、也不是自己再写一份正则去比。
 *
 * <p>两个方向都要断言：
 *
 * <ul>
 *   <li><b>放行</b>：从**本模块自己生成的契约**（{@code packages/api-contracts} 里 codegen 产出的
 *       warehouse 契约与模型源码）反向抽取真实调用点，再逐条断言被放行 ——
 *       写死一份清单只会验证「清单与白名单一致」，真正会出事的是
 *       「源码加了新形状、白名单忘了登记」。</li>
 *   <li><b>拒绝</b>：刻意不放行的形状仍然 404。尤其是
 *       {@code /orders/{uuid}/inventory}（把平台在库并进自有库存）与
 *       {@code /orders/{uuid}/settle}（12 结算未交付、跨服务写账被禁止）——
 *       放行不该存在的路径比 404 更糟：界面会以为「已经可用」。</li>
 * </ul>
 *
 * <p><b>累积型文件的求并纪律</b>：白名单是多车道共用的（另有三条车道同时在本文件追加分支），
 * 本车道只**追加**独立元素，绝不收窄既有分支。最后一组「不误伤其他车道」就是为此存在 ——
 * 取错一侧会静默删掉已交付模块的路由，而表现是页面 404 且所有静态检查全绿。
 */

// 相对**本文件**定位：vitest 从仓库根或 apps/web 跑时 cwd 都不同，用 cwd 会静默读空。
const SOURCE = readFileSync(
  fileURLToPath(
    new URL('../../../components/inventory/consignment-stock-model.ts', import.meta.url),
  ),
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
 * 从模型源码里反向抽出**真实存在**的路径。
 *
 * <p>模型里所有端点都是 `` `${base}/xxx` `` 拼出来的（base 是常量
 * 'warehouse/inventory/consignment-stock'），所以抽取必须先把模板串**按 base 展开**再匹配 ——
 * 只认字面量会把调用点全部漏成零条，然后「一个都没断言」也算通过，
 * 那正是这类守卫最典型的失效方式。
 *
 * <p>占位符只认两种：**id 形状**（{@code ${id}} / {@code ${orderId}} / {@code ${shopId}} …）
 * 与 {@code ${query(...)}}（查询串 —— 代理匹配路径时不含 query，所以要把 query 摘掉再断言）。
 * 其它占位一律跳过：把未解析的占位直接替换成 id 会凭空造出后端根本不存在的形状，
 * 拿它去断言放行等于断言一条假需求。
 */
function realCallSites(): string[] {
  const base =
    SOURCE.match(/const base = '([^']+)'/)?.[1] ?? 'warehouse/inventory/consignment-stock';
  const found = new Set<string>();
  for (const match of SOURCE.matchAll(/`([^`]*)`/g)) {
    const raw = match[1];
    if (!raw || !raw.includes('${base}')) continue;
    const unresolved = raw.replace(/\$\{base\}|\$\{[A-Za-z]*[Ii]d\}|\$\{query\([^)]*\)\}/g, '');
    if (unresolved.includes('${')) continue;
    const cleaned = raw
      .replace(/\$\{base\}/g, base)
      .replace(/\$\{[A-Za-z]*[Ii]d\}/g, ID)
      .replace(/\$\{query\([^)]*\)\}/g, '')
      .replace(/\?.*$/, '');
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
      'warehouse/inventory/consignment-stock/capabilities',
      'warehouse/inventory/consignment-stock/orders',
      `warehouse/inventory/consignment-stock/orders/${ID}`,
      `warehouse/inventory/consignment-stock/orders/${ID}/ledger`,
      `warehouse/inventory/consignment-stock/orders/${ID}/reconciliation`,
      `warehouse/inventory/consignment-stock/orders/${ID}/settlement-basis`,
      `warehouse/inventory/consignment-stock/orders/${ID}/receipts`,
      `warehouse/inventory/consignment-stock/orders/${ID}/settlement-receipts`,
      `warehouse/inventory/consignment-stock/orders/${ID}/audit`,
    ]),
  );
});

it('写形状（POST）被放行，且真的转发到 8084 且不转发伪造租户头', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);

  for (const path of [
    'warehouse/inventory/consignment-stock/orders',
    `warehouse/inventory/consignment-stock/orders/${ID}/lines`,
    `warehouse/inventory/consignment-stock/orders/${ID}/ship`,
    `warehouse/inventory/consignment-stock/orders/${ID}/confirm-receipt`,
    `warehouse/inventory/consignment-stock/orders/${ID}/close`,
    `warehouse/inventory/consignment-stock/orders/${ID}/cancel`,
    `warehouse/inventory/consignment-stock/orders/${ID}/receipts`,
    `warehouse/inventory/consignment-stock/orders/${ID}/settlement-receipts`,
  ]) {
    const response = await POST(request(path, 'POST'), {
      params: Promise.resolve({ path: path.split('/') }),
    });
    expect(response.status, `POST ${path} 未被放行`).toBe(200);
  }

  expect(fetcher).toHaveBeenCalledWith(
    expect.stringContaining(':8084/warehouse/inventory/consignment-stock'),
    expect.objectContaining({ method: 'POST' }),
  );

  // 🔴 伪造的租户头**不许**被转发到上游：租户由 identity 按成员关系解析，
  //    头进不来也改不动 RLS 的 current_setting。
  const [, init] = fetcher.mock.calls[0] as [string, RequestInit];
  const forwarded = new Headers(init.headers as HeadersInit);
  expect(forwarded.get('X-Tenant-Id')).toBeNull();
  expect(forwarded.get('X-Internal-Key')).toBeNull();
  expect(forwarded.get('Authorization')).toBe('Bearer private-session');
});

it('刻意不放行的形状仍然被拒 —— 放行后端不存在的映射比 404 更糟', async () => {
  const fetcher = vi.fn();
  vi.stubGlobal('fetch', fetcher);
  for (const path of [
    // 🔴 把平台在库并进自有库存：后端根本没有这个端点，而且蓝图加粗句
    //   「全托管下"备货"不等于"自有库存"，平台仓在库数不得并入自有库存余额」正是这条规则的落点。
    `warehouse/inventory/consignment-stock/orders/${ID}/inventory`,
    // ⚠️ 12.3.1 结算未交付，且是**独立数据库**：跨服务写账被模块边界纪律明令禁止。
    //   本模块只出「结算口径标记 + 未结量」给 12 读。
    `warehouse/inventory/consignment-stock/orders/${ID}/settle`,
    // ⚠️ 13.4.1.1 多模式利润未交付。
    `warehouse/inventory/consignment-stock/orders/${ID}/profit`,
    // ⚠️ 10.4.2.2 备货预报在 channel 服务：warehouse 不提供预报单写入端点。
    `warehouse/inventory/consignment-stock/orders/${ID}/forecast`,
    // 平台回执 / 结算回执是 append-only 凭据：后端没有删除端点。
    `warehouse/inventory/consignment-stock/orders/${ID}/delete`,
    // 拼错的字面量子路径。
    'warehouse/inventory/consignment-stock/summary',
    'warehouse/inventory/consignment-stock/orders/extra',
    // 单字符不是 uuid：`[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}` 会把它放行。
    'warehouse/inventory/consignment-stock/c',
    // 拼错的动作段。
    `warehouse/inventory/consignment-stock/orders/${ID}/ships`,
    `warehouse/inventory/consignment-stock/orders/${ID}/confirm_receipt`,
  ]) {
    const response = await GET(request(path), {
      params: Promise.resolve({ path: path.split('/') }),
    });
    expect(response.status, `${path} 被放行了，但它在后端不存在`).toBe(404);
  }
  expect(fetcher).not.toHaveBeenCalled();
});

it('本模块的正则没有误伤相邻车道已交付的形状', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);
  // 本条元素插在 7.8.2.2（stockout-alerts）之后，是**独立**的 services[] 元素：
  // 它的前缀是 `inventory/consignment-stock/`，与下面这些互不相交。
  // ⚠️ 同批另有若干车道也在改这个文件 —— 求并时**不要**把它们的分支删掉，
  //   所以这里把它们当前已交付的形状一并断言，作为「求并后仍然全在」的证据。
  for (const path of [
    // 7.7.2.1 VMI 供应商管理库存（兄弟模块）
    'warehouse/inventory/vmi/capabilities',
    'warehouse/inventory/vmi/agreements',
    `warehouse/inventory/vmi/agreements/${ID}`,
    `warehouse/inventory/vmi/agreements/${ID}/water-levels`,
    `warehouse/inventory/vmi/settlements/${ID}/confirm`,
    // 7.7.1.2 寄售与代销（**前缀只差一个词**，最容易被误伤的那个）
    'warehouse/inventory/consignments/capabilities',
    'warehouse/inventory/consignments',
    `warehouse/inventory/consignments/${ID}`,
    `warehouse/inventory/consignments/${ID}/stock-view`,
    `warehouse/inventory/consignments/${ID}/settlements`,
    // 7.8.2.2 缺货与饥饿预警（插入锚点所在的那条）
    'warehouse/inventory/stockout-alerts/capabilities',
    `warehouse/inventory/stockout-alerts/starvations/${ID}/acknowledge`,
    // 7.7.1.1 货主与所有权
    'warehouse/inventory/ownership/capabilities',
    'warehouse/inventory/ownership/report',
    // 7.8.1.1 分配策略
    'warehouse/inventory/allocation-policies/capabilities',
    // 7.1.1.3 可用量
    'warehouse/inventory/sellable/skus',
    // 8.x 仓储作业（顶层字面量分支）
    'warehouse/goods-receipts/capabilities',
    'warehouse/pick-tasks/capabilities',
  ]) {
    const response = await GET(request(path), {
      params: Promise.resolve({ path: path.split('/') }),
    });
    expect(response.status, `${path} 被本模块的正则误伤了`).toBe(200);
  }
});
