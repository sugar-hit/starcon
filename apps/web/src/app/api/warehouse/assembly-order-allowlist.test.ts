import { afterEach, expect, it, vi } from 'vitest';
import { NextRequest } from 'next/server';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { DELETE, GET, POST, PUT } from './[...path]/route';

/**
 * 8.4.1.1 组装单的 api-proxy 白名单**双向**断言。
 *
 * <p>白名单只在浏览器端生效：`pnpm test` 直连服务层天然测不出漏登记 ——
 * 漏登记的路径在浏览器里静默 404，而 typecheck / lint / vitest / Playwright 全绿。
 * 所以判据是**真跑 proxy handler 断言 HTTP 状态码**（拦截 = 404，放行 = 转发），
 * 不是眼看正则、也不是自己再写一份正则去比（这两种方法在本仓库已连续骗过 6 版）。
 *
 * <p>两个方向都要断言：
 *
 * <ul>
 *   <li><b>放行</b>：从本模块自己的模型源码里<b>反向抽取真实调用点</b>再逐条断言被放行 ——
 *       写死一份清单只会验证「清单与白名单一致」，而真正会出事的是
 *       「源码加了新形状、白名单忘了登记」。</li>
 *   <li><b>拒绝</b>：未交付 / 不该存在的形状仍然 404。<b>反向用例要能证明「多一层后缀」仍然 404</b>
 *       ——{id}/issue 放行不代表 {id}/issue/confirm 也该放行，那是不存在的动作。
 *       放行不该存在的路径比 404 更糟：它会让界面以为「已经可用」。</li>
 * </ul>
 *
 * <p><b>累积型文件的求并纪律</b>：白名单是多车道共用的（另有三条车道同时在本文件追加分支），
 * 本车道只**追加**分支，绝不收窄既有分支。最后一组「不误伤其他车道」就是为此存在 ——
 * 取错一侧会静默删掉已交付模块的路由，而表现是页面 404 且所有静态检查全绿。
 */

// 相对**本文件**定位：vitest 从仓库根或 apps/web 跑时 cwd 都不同，用 cwd 会静默读空。
const SOURCE = readFileSync(
  fileURLToPath(new URL('../../../components/warehouse/assembly-order-model.ts', import.meta.url)),
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
 * 'warehouse/assembly-orders'），所以抽取必须先把模板串**按 base 展开**再匹配 ——
 * 只认字面量会把调用点全部漏成零条，然后「一个都没断言」也算通过，
 * 那正是这类守卫最典型的失效方式。
 */
function realCallSites(): string[] {
  const base = SOURCE.match(/const base = '([^']+)'/)?.[1] ?? 'warehouse/assembly-orders';
  const found = new Set<string>();
  for (const match of SOURCE.matchAll(/`([^`]*)`/g)) {
    const raw = match[1];
    if (!raw || !raw.includes('${base}')) continue;
    const cleaned = raw
      .replace(/\$\{base\}/g, base)
      // ${assemblyOrderQuery(...)} 是查询串，整段去掉；其余插值（通常是 id）换成 uuid。
      .replace(/\$\{assemblyOrderQuery\([^)]*\)\}/g, '')
      .replace(/\$\{[^}]*\}/g, ID)
      .replace(/\?.*$/, '');
    if (cleaned.includes('${')) continue;
    found.add(cleaned);
  }
  // 字面量（没有模板拼接时也要能抽到）。
  // **裸 base 本身要排除**：它是前缀常量而不是端点（`api(base, 'POST', …)`），
  // 后端没有「一条裸路径的 POST 映射」以外的语义；下面有用例专门断言
  // 「裸路径 + 多一层后缀」仍然 404。
  for (const match of SOURCE.matchAll(/'([^']*)'/g)) {
    const raw = match[1];
    if (!raw || !raw.startsWith(base) || raw === base) continue;
    found.add(raw);
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
      'warehouse/assembly-orders/capabilities',
      'warehouse/assembly-orders',
      `warehouse/assembly-orders/${ID}`,
      `warehouse/assembly-orders/${ID}/issue`,
      `warehouse/assembly-orders/${ID}/cancel`,
    ]),
  );
});

it('写动作的 POST 形状被放行，且真的转发到 8084', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);

  for (const path of [
    'warehouse/assembly-orders',
    `warehouse/assembly-orders/${ID}/issue`,
    `warehouse/assembly-orders/${ID}/cancel`,
  ]) {
    const response = await POST(request(path, 'POST'), { params: { path: path.split('/') } });
    expect(response.status, `POST ${path} 未被放行`).toBe(200);
  }
  expect(fetcher).toHaveBeenCalledWith(
    expect.stringContaining(':8084/warehouse/assembly-orders'),
    expect.objectContaining({ method: 'POST' }),
  );
});

/**
 * 刻意没有 PUT / DELETE 端点：组件明细是建单时按 BOM 冻结的快照，
 * 已完成的组装单是凭据（组件已出库、成品已入库）。
 *
 * <p>所以这里断言的是代理的**真实语义**而不是它本该有的语义：白名单按**路径**放行、
 * **不按方法**过滤，PUT / DELETE 会被照常转发，最终由后端回 405。
 * 写成「代理应该返回 404」会是一条永远失败的断言，而把它写成「被转发」才是
 * 「方法由后端兜」这条边界的真实读数。
 */
it('PUT / DELETE 不由代理拦：路径在白名单内就会被转发，方法由后端 405 兜住', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);

  for (const path of [`warehouse/assembly-orders/${ID}`, 'warehouse/assembly-orders']) {
    expect(
      (await PUT(request(path, 'PUT'), { params: { path: path.split('/') } })).status,
      `PUT ${path} 应转发给后端`,
    ).toBe(200);
    expect(
      (await DELETE(request(path, 'DELETE'), { params: { path: path.split('/') } })).status,
      `DELETE ${path} 应转发给后端`,
    ).toBe(200);
  }
  // 后端确实没有这两个端点：POST 才是写动作的形状。
  expect(fetcher).toHaveBeenCalledWith(
    expect.stringContaining(':8084/warehouse/assembly-orders'),
    expect.objectContaining({ method: 'PUT' }),
  );
  expect(fetcher).toHaveBeenCalledWith(
    expect.stringContaining(':8084/warehouse/assembly-orders'),
    expect.objectContaining({ method: 'DELETE' }),
  );
});

it('未交付 / 不该存在的形状仍然被拒，且「多一层后缀」也仍然 404', async () => {
  const fetcher = vi.fn();
  vi.stubGlobal('fetch', fetcher);
  for (const path of [
    // 同组兄弟模块未开发：8.4.2.1 拆解单。
    'warehouse/assembly-orders/disassemblies',
    'warehouse/assembly-orders/kits',
    // 已放行的动作后面再多一层 —— 必须仍然 404（放行一个不存在的动作比 404 更糟）。
    `warehouse/assembly-orders/${ID}/issue/confirm`,
    `warehouse/assembly-orders/${ID}/cancel/reason`,
    // capabilities 后面多一层。
    'warehouse/assembly-orders/capabilities/advanced',
    // 裸路径 + 多一层：后端没有「一条不存在的列表子路径」。
    'warehouse/assembly-orders/summary',
    `warehouse/assembly-orders/${ID}/components`,
    // 非 UUID 段不应被 [0-9a-f-]+ 分支吃掉。
    'warehouse/assembly-orders/not-a-uuid',
    // 交替写错就会互相吃掉，必须反向钉住。
    'warehouse/assembly-order',
    'warehouse/assembly-order/{id}/issue',
  ]) {
    const response = await GET(request(path), { params: { path: path.split('/') } });
    expect(response.status, `${path} 不应被放行`).toBe(404);
  }
  expect(fetcher, '被拒的路径不得触发任何上游调用').not.toHaveBeenCalled();
});

/**
 * 不误伤其他车道已登记的分支。
 *
 * <p>白名单是多车道共用的**累积型**文件（同时有 6 条车道在往 warehouse/ 那一支的
 * alternation 里并）。并入时若取错一侧，会静默删掉已交付模块的路由 —— 表现是页面 404，
 * 而 typecheck / lint / vitest / Playwright 全绿，因为白名单只在浏览器端生效。
 * 所以必须正向钉住本车道**之前**已放行的几条仓库分支。
 */
it('不误伤本车道之前已放行的仓库分支（求并，不是二选一）', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);

  for (const path of [
    'warehouse/inventory/overview',
    'warehouse/processing-orders/capabilities',
    'warehouse/processing-orders/executions',
    'warehouse/goods-receipt-discrepancies/capabilities',
    'warehouse/trade-documents/capabilities',
  ]) {
    const response = await GET(request(path), { params: { path: path.split('/') } });
    expect(response.status, `已交付模块的 ${path} 被本车道误伤 —— 白名单必须求并`).toBe(200);
  }
});

/** 跨源写请求必须被拒（CSRF 闸），与白名单无关但同一份代理代码。 */
it('跨源写请求被 403 拒绝，不转发', async () => {
  const fetcher = vi.fn();
  vi.stubGlobal('fetch', fetcher);
  const path = `warehouse/assembly-orders/${ID}/issue`;
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
