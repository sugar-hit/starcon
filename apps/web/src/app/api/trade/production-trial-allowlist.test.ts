import { afterEach, expect, it, vi } from 'vitest';
import { NextRequest } from 'next/server';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { DELETE, GET, POST, PUT } from './[...path]/route';
import { proxy } from '../../../lib/api-proxy';

/**
 * 6.6.2.1 试产单的 api-proxy 白名单**双向**断言。
 *
 * <p>白名单只在浏览器端生效：`pnpm test` 直连服务层天然测不出漏登记 ——
 * 漏登记的路径在浏览器里静默 404，而 typecheck / lint / vitest / Playwright 全绿。
 * 所以判据是**真跑 proxy 断言 HTTP 状态码**（拦截 = 404，放行 = 转发），
 * 不是眼看正则、也不是自己再写一份正则去比（这两种方法在本仓库已连续骗过 6 版）。
 *
 * <p>两个方向都要断言：
 *
 * <ul>
 *   <li><b>放行</b>：从本模块自己的模型源码里<b>反向抽取真实调用点</b>再逐条断言被放行 ——
 *       写死一份清单只会验证「清单与白名单一致」，而真正会出事的是
 *       「源码加了新形状、白名单忘了登记」。</li>
 *   <li><b>拒绝</b>：未交付 / 不该存在的形状仍然 404。放行不该存在的路径比 404 更糟 ——
 *       它会让界面以为「已经可用」。</li>
 * </ul>
 *
 * <p><b>累积型文件的求并纪律</b>：白名单是多车道共用的，本车道只**追加**分支，
 * 绝不收窄既有分支。取错一侧会静默删掉已交付模块的路由，
 * 而表现是页面 404 且所有静态检查全绿。
 */

// 相对**本文件**定位：vitest 从仓库根或 apps/web 跑时 cwd 都不同，用 cwd 会静默读空。
const SOURCE = readFileSync(
  fileURLToPath(
    new URL('../../../components/purchase/production-trial-model.ts', import.meta.url),
  ),
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
 * 'trade/trial-productions'），所以抽取必须先把模板串**按 base 展开**再匹配 ——
 * 只认字面量会把调用点全部漏成零条，然后「一个都没断言」也算通过，
 * 那正是这类守卫最典型的失效方式。
 */
function realCallSites(): string[] {
  const base = SOURCE.match(/const base = '([^']+)'/)?.[1] ?? 'trade/trial-productions';
  const found = new Set<string>();
  for (const match of SOURCE.matchAll(/`([^`]*)`/g)) {
    const raw = match[1];
    if (!raw || !raw.includes('${base}')) continue;
    const cleaned = raw
      .replace(/\$\{base\}/g, base)
      // 查询串整段去掉：它们是 query，不是路径段。
      .replace(/\$\{[^}]*Query\([^)]*\)\}/g, '')
      // 其余插值（通常是 id）换成 uuid。
      .replace(/\$\{[^}]*\}/g, ID)
      .replace(/\?.*$/, '')
      // **导出走的是 fetch 的完整 URL**（`/api/trade/${base}/...`），比 api() 多一层
      // 前缀。这里把 `/api/<service>/` 剥掉 —— 代理看到的是 service 前缀**之后**的路径。
      .replace(/^\/api\/[a-z]+\//, '');
    if (cleaned.includes('${')) continue;
    found.add(cleaned);
  }
  // 字面量（没有模板拼接时也要能抽到）。
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
    const response = await GET(request(path), {
      params: Promise.resolve({ path: path.split('/') }),
    });
    expect(response.status, `${path} 未被放行（白名单漏登记 = 浏览器里静默 404）`).toBe(200);
  }
  // 抽到的路径必须**真的**覆盖本模块的形状，否则「只放行了一条」也算通过。
  expect(sites).toEqual(
    expect.arrayContaining([
      'trade/trial-productions',
      'trade/trial-productions/capabilities',
      'trade/trial-productions/options',
      'trade/trial-productions/summary',
      'trade/trial-productions/bom-version-candidates',
      `trade/trial-productions/${ID}`,
      `trade/trial-productions/${ID}/transitions`,
      `trade/trial-productions/${ID}/component-requirements`,
      `trade/trial-productions/${ID}/component-requirements/${ID}/submit`,
      `trade/trial-productions/${ID}/component-requirements/${ID}/release`,
      `trade/trial-productions/${ID}/inspection-items`,
      `trade/trial-productions/${ID}/inspection-items/${ID}`,
      `trade/trial-productions/${ID}/result`,
      `trade/trial-productions/${ID}/cost`,
      `trade/trial-productions/${ID}/cost/lines`,
      `trade/trial-productions/${ID}/cost/lines/${ID}`,
      `trade/trial-productions/${ID}/cost-comparison`,
      `trade/trial-productions/${ID}/mass-production-recommendation`,
    ]),
  );
});

it('写动作的 POST 形状被放行，且真的转发到 8085', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);

  for (const path of [
    'trade/trial-productions',
    `trade/trial-productions/${ID}/transitions`,
    `trade/trial-productions/${ID}/component-requirements/${ID}/submit`,
    `trade/trial-productions/${ID}/component-requirements/${ID}/release`,
    `trade/trial-productions/${ID}/inspection-items`,
    `trade/trial-productions/${ID}/result`,
    `trade/trial-productions/${ID}/cost/lines`,
    `trade/trial-productions/${ID}/mass-production-recommendation`,
  ]) {
    const response = await POST(request(path, 'POST'), {
      params: Promise.resolve({ path: path.split('/') }),
    });
    expect(response.status, `POST ${path} 未被放行`).toBe(200);
  }
  expect(fetcher).toHaveBeenCalledWith(
    expect.stringContaining(':8085/trade/trial-productions'),
    expect.objectContaining({ method: 'POST' }),
  );
});

it('更新与删除子资源的形状被放行', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);

  const put = await PUT(request(`trade/trial-productions/${ID}`, 'PUT'), {
    params: Promise.resolve({ path: `trade/trial-productions/${ID}`.split('/') }),
  });
  expect(put.status, `PUT ${ID} 未被放行`).toBe(200);

  const putItem = await PUT(
    request(`trade/trial-productions/${ID}/inspection-items/${ID}`, 'PUT'),
    {
      params: Promise.resolve({
        path: `trade/trial-productions/${ID}/inspection-items/${ID}`.split('/'),
      }),
    },
  );
  expect(putItem.status, `PUT inspection-items/${ID} 未被放行`).toBe(200);

  const del = await DELETE(request(`trade/trial-productions/${ID}/cost/lines/${ID}`, 'DELETE'), {
    params: Promise.resolve({
      path: `trade/trial-productions/${ID}/cost/lines/${ID}`.split('/'),
    }),
  });
  expect(del.status, `DELETE cost/lines/${ID} 未被放行`).toBe(200);
});

it('未交付 / 不该存在的形状仍然被拒 —— 放行不该存在的路径比 404 更糟', async () => {
  const fetcher = vi.fn();
  vi.stubGlobal('fetch', fetcher);
  for (const path of [
    // 本模块不提供删除试产单：它挂着组件需求、质检项、结果、成本与全部留痕。
    `trade/trial-productions/${ID}/delete`,
    // 🔴 蓝图加粗句「试产消耗真实组件库存，需走 8 加工单而非直接改库存」：
    //    本模块**只产出组件领用需求**，不建库存扣减表 ⇒ 这两个形状刻意不放行。
    `trade/trial-productions/${ID}/inventory-deductions`,
    `trade/trial-productions/${ID}/stock`,
    // 「扣减」动作同样不做：领用由 warehouse 侧 8.4.1 组装单承接。
    `trade/trial-productions/${ID}/component-requirements/${ID}/deduct`,
    // 组装单是 warehouse 独立库里 8.4.1 的事实，本模块只存逻辑引用 + 快照。
    `trade/trial-productions/${ID}/assembly-orders`,
    // 6.6.2.2 转量产与建档**未开发** ⇒ 本模块只产**建议事实**。
    `trade/trial-productions/${ID}/mass-production-orders`,
    // SKU 建档归 3.1.2.1 与 6.6.2.2，本模块**不建 SKU**。
    `trade/trial-productions/${ID}/skus`,
    // 非 UUID 段不应被 UUID 分支吃掉。
    `trade/trial-productions/${ID}/component-requirements/not-a-uuid`,
    `trade/trial-productions/${ID}/inspection-items/not-a-uuid`,
    `trade/trial-productions/${ID}/cost/lines/not-a-uuid`,
    // 交替写错就会互相吃掉，必须反向钉住。
    'trade/trial-production/capabilities',
    'trade/trial-productions/capabilitie',
    'trade/trial-productions/summar',
    'trade/trial-productions/bom-version-candidate',
  ]) {
    const response = await GET(request(path), {
      params: Promise.resolve({ path: path.split('/') }),
    });
    expect(response.status, `${path} 不应被放行`).toBe(404);
  }
  expect(fetcher, '被拒的路径不得触发任何上游调用').not.toHaveBeenCalled();
});

/**
 * 不误伤其他车道已登记的分支。
 *
 * <p>白名单是多车道共用的**累积型**文件。并入时若取错一侧，会静默删掉已交付模块
 * 的路由 —— 表现是页面 404，而 typecheck / lint / vitest / Playwright 全绿，
 * 因为白名单只在浏览器端生效。所以必须正向钉住已放行的全部形状。
 */
it('不误伤其他车道已登记的 trade 分支（求并，不是二选一）', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);

  for (const path of [
    // 6.6.1.2 打样与样品管理（本模块的直接上游）
    'trade/development-samples/capabilities',
    'trade/development-samples',
    // 6.6.1.1 开发立项
    'trade/development-projects/capabilities',
    'trade/development-projects',
    // 6.2.1.2 补货建议
    'trade/replenishment-suggestions/capabilities',
    // 6.2.1.3 采购计划（同为卷 06）
    'trade/purchase-plans/capabilities',
    // 6.5.1.1 采购对账（同为卷 06）
    'trade/purchase-reconciliations/capabilities',
    // 6.2.1.1 缺货与断货风险
    'trade/stockout-risk/capabilities',
    // 6.1.2.1 档口与货源池
    'trade/sources/stalls',
  ]) {
    const response = await GET(request(path), {
      params: Promise.resolve({ path: path.split('/') }),
    });
    expect(response.status, `其他车道的 ${path} 被本车道误伤 —— 白名单必须求并`).toBe(200);
  }
});

/**
 * 上游参照下拉的路径也不能被本车道的分支吃掉。
 *
 * <p>BOM 版本在 catalog（`catalog/bom-versions`）—— 那是**别的车道**登记的分支。
 * 本车道新增的 `trial-productions` 分支若写得太宽，就会把别的车道路径
 * **静默改道**到 trade 服务（表现是页面 404、数据为空）。
 */
it('上游参照下拉的路径仍走它们各自的服务', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);

  // 🔴 BOM 版本在 catalog（`catalog/bom-versions`）—— 那是**别的车道**登记的分支。
  await proxy(
    new NextRequest('http://localhost:3000/api/catalog/bom-versions', {
      headers: { origin: 'http://localhost:3000', cookie: 'starcon_session=x' },
    }),
    { params: Promise.resolve({ path: 'catalog/bom-versions'.split('/') }) } as never,
  );
  expect(fetcher).toHaveBeenCalledWith(
    expect.stringContaining(':8082/catalog/bom-versions'),
    expect.anything(),
  );
});

/** 跨源写请求必须被拒（CSRF 闸），与白名单无关但同一份代理代码。 */
it('跨源写请求被 403 拒绝，不转发', async () => {
  const fetcher = vi.fn();
  vi.stubGlobal('fetch', fetcher);
  const path = `trade/trial-productions/${ID}/transitions`;
  const response = await POST(
    new NextRequest(`http://localhost:3000/api/trade/${path}`, {
      method: 'POST',
      headers: { origin: 'http://evil.example', cookie: 'starcon_session=x' },
      body: '{}',
    }),
    { params: Promise.resolve({ path: path.split('/') }) },
  );
  expect(response.status).toBe(403);
  expect(fetcher).not.toHaveBeenCalled();
});
