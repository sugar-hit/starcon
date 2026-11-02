import { afterEach, expect, it, vi } from 'vitest';
import { NextRequest } from 'next/server';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { GET, POST, PUT, DELETE } from './[...path]/route';

/**
 * 5.1.1.1 价目表列表的 api-proxy 白名单**双向**断言。
 *
 * <p>白名单只在浏览器端生效：`pnpm test` 直连服务层天然测不出漏登记 ——
 * 漏登记的路径在浏览器里静默 404，而 typecheck / lint / vitest / Playwright 全绿。
 * 所以判据是**真跑 route handler 断言 HTTP 状态码**（拦截 = 404，放行 = 转发），
 * 不是眼看正则、也不是自己再写一份正则去比（这两种方法在本仓库已连续骗过多版）。
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
 * <p>🔴 负向样本**全部取自本模块自己的前缀** `catalog/price-lists/...`：
 * 拿兄弟车道的前缀当「未交付形状」是 batch-32 的教训 —— 那个前缀在同批里已经交付，
 * 断言它 404 就是**假红**；更糟的是断言方向搞反时会让「已交付的路由被静默删掉」这个真事故**假绿**。
 *
 * <p>🔧 **2026-11-02（batch-134 L2 红修复）**：`price-lists/lists/{uuid}/conflicts`
 * 与 `price-lists/conflicts` 这两条形状**已由 5.1.2.1（优先级与冲突检测）交付** ——
 * 它们在白名单里由 5.1.2.1 自己那条**独立的 `services[]` 元素**放行
 * ⇒ 本文件把这两条从**负向样本**改成**正向样本**（被放行 ⇒ 状态码不是 404）。
 * `price-lists/lists/{uuid}/items` 依旧由 5.1.1.2 放行（那条注释保持不变），
 * 而 `price-lists/lists/{uuid}/items/batch` 这个**确实不存在**的形状仍留在负向样本里。
 *
 * <p><b>不误伤其他车道已登记的分支</b>：白名单是多车道共用的**累积型**文件。
 * 本车道只**新增**一条 catalog/ services 条目（锚点 = `price-anomaly-alerts` 那条之后），
 * 既有任何一条都不动 —— 但那必须被断言钉住，否则下一次并入取错一侧时会静默删掉已交付模块的路由。
 *
 * <p>🔴 本文件也是本车道**搬家后**的守门人：5.1.1.1 的白名单条目第一次并入时插在
 * **数组末尾**，与 18.4.2.3（异常诊断）撞在同一个锚点 ⇒ git 判冲突。
 * 搬家到 catalog 中段之后，这条断言要能证明：**搬家没有挤掉任何一条既有条目**。
 */

// 相对**本文件**定位：vitest 从仓库根或 apps/web 跑时 cwd 都不同，用 cwd 会静默读空。
const SOURCE = readFileSync(
  fileURLToPath(new URL('../../../components/pricing/price-list-model.ts', import.meta.url)),
  'utf8',
);

const ID = '11111111-1111-1111-1111-111111111111';
/** 本模块在白名单里的首 token（proxy 路径去掉 `catalog/` 前缀后剩下的那一段）。 */
const BASE = 'catalog/price-lists';

afterEach(() => vi.unstubAllGlobals());

function request(path: string, method = 'GET') {
  return new NextRequest(`http://localhost:3000/api/catalog/${path}`, {
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
 * <p>模型里的端点全部是 `api('catalog/price-lists/...')` 与
 * `` api(`catalog/price-lists/lists/${id}`) `` 两种形状，所以抽取要同时认
 * **字面量**与**模板串**；模板串里的 `${id}` 换成 uuid、`${params.toString()}`
 * 去掉查询串（查询串不是路径的一段）。
 *
 * <p>🔴 只认字面量会把模板串那几个调用点全部漏成零条，然后「一个都没断言」也算通过 ——
 * 那正是这类守卫最典型的失效方式。
 */
function realCallSites(): string[] {
  const found = new Set<string>();
  // 模板串：`catalog/price-lists/lists/${id}`
  for (const match of SOURCE.matchAll(/`([^`]*)`/g)) {
    const raw = match[1];
    if (!raw || !raw.startsWith(BASE)) continue;
    const cleaned = raw.replace(/\$\{[^}]*\}/g, ID).replace(/\?.*$/, '');
    if (!cleaned.startsWith(BASE)) continue;
    found.add(cleaned);
  }
  // 字面量：'catalog/price-lists/capabilities'
  for (const match of SOURCE.matchAll(/'([^']*)'/g)) {
    const raw = match[1];
    if (!raw || !raw.startsWith(BASE)) continue;
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
    expect(response.status, `${path} 未被放行（白名单漏登记 = 浏览器里静默 404）`).not.toBe(404);
  }
  // 抽到的路径必须**真的**覆盖本模块的形状，否则「只放行了一条」也算通过。
  expect(sites).toEqual(
    expect.arrayContaining([
      'catalog/price-lists/capabilities',
      'catalog/price-lists/scope-directory',
      'catalog/price-lists/coverage',
      'catalog/price-lists/lists',
      `catalog/price-lists/lists/${ID}`,
    ]),
  );
});

it('写动作真的转发到 8082（不是只判了正则没转发），且不转发伪造的租户头', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);

  const response = await POST(request('catalog/price-lists/lists', 'POST'), {
    params: Promise.resolve({ path: 'catalog/price-lists/lists'.split('/') }),
  });
  expect(response.status, 'POST catalog/price-lists/lists 未被放行').not.toBe(404);

  const put = await PUT(request(`catalog/price-lists/lists/${ID}`, 'PUT'), {
    params: Promise.resolve({ path: `catalog/price-lists/lists/${ID}`.split('/') }),
  });
  expect(put.status, `PUT catalog/price-lists/lists/${ID} 未被放行`).not.toBe(404);

  const del = await DELETE(request(`catalog/price-lists/lists/${ID}`, 'DELETE'), {
    params: Promise.resolve({ path: `catalog/price-lists/lists/${ID}`.split('/') }),
  });
  expect(del.status, `DELETE catalog/price-lists/lists/${ID} 未被放行`).not.toBe(404);

  expect(fetcher).toHaveBeenCalledWith(
    expect.stringContaining(':8082/catalog/price-lists'),
    expect.objectContaining({ method: 'POST' }),
  );
  const [, init] = fetcher.mock.calls[0] as [string, RequestInit];
  expect(new Headers(init.headers).get('X-Tenant-Id')).toBeNull();
});

it('未交付 / 不该存在的形状仍然 404（放行它们比 404 更糟）', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);

  // 🔴 **每一条负向样本都在本模块自己的前缀下** ——
  //   兄弟车道的前缀本批可能同时交付，拿它们当「未交付形状」并入后必然假红。
  for (const path of [
    // 裸路径：本模块没有裸集合读，放行它就是凭空多出一条 GET。
    'catalog/price-lists',
    // 价目项的批量维护形状同样属于 5.1.1.2 —— 🔴 **5.1.1.2 已交付**（2026-11-02）：
    //   下面那条 `lists/{id}/items` 已由 5.1.1.2 的**独立一条 services[] 元素**放行，
    //   形状与本模块的互不相交；本模块（5.1.1.1）仍然**不实现**那些端点。
    //   `items/batch` 这个形状**仍然**不存在（5.1.1.2 的批量形状叫 preview / apply）⇒ 保留 404 断言。
    `catalog/price-lists/lists/${ID}/items/batch`,
    // 不存在的子路径。
    'catalog/price-lists/items',
    // 非 UUID 段不应被 uuid 分支吃掉。
    `catalog/price-lists/lists/not-a-uuid`,
  ]) {
    const response = await GET(request(path), {
      params: Promise.resolve({ path: path.split('/') }),
    });
    expect(response.status, `${path} 不该被放行`).toBe(404);
  }
});

it('本模块的字面量子路径不会被 uuid 分支提前吃掉', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);

  // `capabilities` / `scope-directory` / `coverage` 的首字母都不落在 [0-9a-f] 里，
  // 但本模块的 alternation 里字面量子路径与 {uuid} 段是同一层的兄弟分支 ——
  // 判别顺序写反就会被 uuid 支吃掉半截。
  for (const path of [
    'catalog/price-lists/capabilities',
    'catalog/price-lists/scope-directory',
    'catalog/price-lists/coverage',
    'catalog/price-lists/lists',
  ]) {
    const response = await GET(request(path), {
      params: Promise.resolve({ path: path.split('/') }),
    });
    expect(response.status, `${path} 未被放行`).not.toBe(404);
    expect(fetcher).toHaveBeenCalledWith(
      expect.stringContaining(`:8082/${path}`),
      expect.anything(),
    );
  }
});

it('🔴 兄弟车道的白名单条目未被本车道挤掉或污染', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);

  // 其它车道**已交付**的模块各挑一条真实 path —— 少一条都会让
  // 「本车道取错一侧静默删掉别人路由」这件事失去唯一的守门人。
  const siblings = [
    // 3.1.1.1 商品主数据
    'catalog/products',
    // 3.1.2.1 SKU 列表
    'catalog/skus',
    // 5.2.1.1 成本构成（与本模块同属 05 定价）
    'catalog/sku-cost-compositions',
    // 5.3.2.1 价格规则（与本模块同属 05 定价）
    'catalog/pricing-rules/rules',
    // 5.3.2.2 定价模板
    'catalog/pricing-templates/templates',
    // 5.5.2.1 异常告警 —— 🔴 本条目的**紧邻锚点**（搬家就插在它后面）
    'catalog/price-anomaly-alerts/capabilities',
    'catalog/price-anomaly-alerts/events',
    // 5.1.2.1 优先级与冲突检测 —— 🔴 **已交付**（2026-11-02）：
    //   `lists/{uuid}/conflicts`（某张价目表的冲突明细）与 `conflicts`（全租户冲突概览）
    //   由**它自己那条独立的 services[] 元素**放行 ⇒ 这两条是**正向样本**（不是 404）。
    `catalog/price-lists/lists/${ID}/conflicts`,
    'catalog/price-lists/conflicts',
  ];
  for (const path of siblings) {
    const response = await GET(request(path), {
      params: Promise.resolve({ path: path.split('/') }),
    });
    expect(response.status, `兄弟模块 ${path} 被本车道改动挤掉了`).not.toBe(404);
  }
});
