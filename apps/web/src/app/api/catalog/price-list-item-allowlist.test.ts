import { afterEach, expect, it, vi } from 'vitest';
import { NextRequest } from 'next/server';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { GET, POST, PUT, DELETE } from './[...path]/route';

/**
 * 5.1.1.2 价目项批量维护的 api-proxy 白名单**双向**断言。
 *
 * <p>判据是**真跑 route handler 断言 HTTP 状态码**（拦截 = 404，放行 = 转发），
 * 不是眼看正则、也不是自己再写一份正则去比（这两种方法在本仓库已连续骗过多版）。
 *
 * <p>两个方向都要断言：
 *
 * <ul>
 *   <li><b>放行</b>：从**本模块自己生成的契约**（{@code packages/api-contracts/src/catalog.openapi.json}）
 *       里反向抽出真实存在的 path 再逐条断言被放行 —— 写死一份清单只会验证
 *       「清单与白名单一致」，而真正会出事的是「后端加了新形状、白名单忘了登记」。</li>
 *   <li><b>拒绝</b>：未交付 / 不该存在的形状仍然 404。放行不该存在的路径比 404 更糟 ——
 *       它会让界面以为「已经可用」。</li>
 * </ul>
 *
 * <p>🔴 负向样本**全部取自本模块自己的形状**（`catalog/price-lists/lists/{id}/items/...`）：
 * 拿兄弟车道的前缀当「未交付形状」是 batch-32 的教训。
 *
 * <p>🔴 最要紧的一条负向样本：{@code price-lists/lists/{id}/conflicts} ——
 * 优先级与冲突检测是 **5.1.2.1**（本表的下游守门员），本模块**不**实现跨价目表的冲突判定。
 * 放行它 ⇒ 界面会以为「冲突检测已交付」。
 *
 * <p><b>不误伤其他车道已登记的分支</b>：白名单是多车道共用的**累积型**文件。
 * 本车道只**新增**一条 catalog/ services 条目（锚点 = 数组末尾），
 * 既有任何一条都不动 —— 但那必须被断言钉住，否则下一次并入取错一侧时会静默删掉已交付模块的路由。
 */

// 相对**本文件**定位：vitest 从仓库根或 apps/web 跑时 cwd 都不同，用 cwd 会静默读空。
const SPEC = JSON.parse(
  readFileSync(
    fileURLToPath(
      new URL('../../../../../../packages/api-contracts/src/catalog.openapi.json', import.meta.url),
    ),
    'utf8',
  ),
) as { paths: Record<string, Record<string, unknown>> };

const ID = '11111111-1111-1111-1111-111111111111';
/** 本模块在契约里的 path 前缀。 */
const MODULE = '/catalog/price-lists/lists/{priceListId}/items';

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
 * 真跑代理。
 *
 * <p>🔴 path 数组必须**包含** `catalog/` 这一段：api-proxy 的白名单按
 * `path.startsWith('catalog/')` 判定，而浏览器的地址是 `/api/catalog/<path>`。
 */
function callProxy(path: string, method: 'GET' | 'POST' | 'PUT' | 'DELETE' = 'GET') {
  const segments = ['catalog', ...path.split('/')];
  const handler =
    method === 'POST' ? POST : method === 'PUT' ? PUT : method === 'DELETE' ? DELETE : GET;
  return handler(request(path, method), {
    params: Promise.resolve({ path: segments }),
  } as never);
}

/** 从**本模块自己生成的契约**里抽出真实存在的 path（不手写清单）。 */
function realCallSites(): string[] {
  const found = new Set<string>();
  for (const path of Object.keys(SPEC.paths)) {
    // 能力声明挂在价目表命名空间的**另一个**前缀下（`price-lists/item-capabilities`），
    // 所以先单独认它，再认 `price-lists/lists/{priceListId}/items` 那一族。
    if (path === '/catalog/price-lists/item-capabilities') {
      found.add('price-lists/item-capabilities');
      continue;
    }
    if (path !== MODULE && !path.startsWith(`${MODULE}/`)) continue;
    const resolved = path.replace(/^\/catalog\//, '').replace(/\{[^}]+\}/g, ID);
    if (resolved.includes('{')) continue;
    found.add(resolved);
  }
  return [...found].sort();
}

it('契约里本模块的每个真实 path 都被放行', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);

  const sites = realCallSites();
  expect(sites.length, '契约里一个本模块 path 都没抽到 —— 断言本身失效了').toBeGreaterThan(0);
  for (const path of sites) {
    const response = await callProxy(path);
    expect(response.status, `${path} 未被放行（白名单漏登记 = 浏览器里静默 404）`).not.toBe(404);
  }

  expect(sites).toEqual(
    expect.arrayContaining([
      'price-lists/item-capabilities',
      `price-lists/lists/${ID}/items`,
      `price-lists/lists/${ID}/items/${ID}`,
      `price-lists/lists/${ID}/items/conflicts`,
      `price-lists/lists/${ID}/items/resolve`,
      `price-lists/lists/${ID}/items/preview`,
      `price-lists/lists/${ID}/items/apply`,
      `price-lists/lists/${ID}/items/export`,
      `price-lists/lists/${ID}/items/import`,
    ]),
  );
});

it('写动作真的转发到 8082（不是只判了正则没转发），且不转发伪造的租户头', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);

  const response = await callProxy(`price-lists/lists/${ID}/items`, 'POST');
  expect(response.status, `POST items 未被放行`).not.toBe(404);
  expect(fetcher).toHaveBeenCalledWith(
    expect.stringContaining(':8082/catalog/price-lists/lists/'),
    expect.objectContaining({ method: 'POST' }),
  );
  const [, init] = fetcher.mock.calls[0] as [string, RequestInit];
  expect(new Headers(init.headers).get('X-Tenant-Id')).toBeNull();
});

it('修改与删除同样被放行', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);

  const put = await callProxy(`price-lists/lists/${ID}/items/${ID}`, 'PUT');
  expect(put.status, 'PUT items/{id} 未被放行').not.toBe(404);

  const del = await callProxy(`price-lists/lists/${ID}/items/${ID}`, 'DELETE');
  expect(del.status, 'DELETE items/{id} 未被放行').not.toBe(404);
});

it('🔴 未交付 / 不该存在的形状仍然 404（放行它们比 404 更糟）', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);

  for (const path of [
    // 🔴 优先级与冲突检测是 **5.1.2.1**（本表的下游守门员），本模块**不**实现跨表冲突判定
    //    ⇒ 后端没有这个端点 ⇒ 放行它只会让界面 404。
    `price-lists/lists/${ID}/conflicts`,
    // 本模块的批量形状叫 preview / apply；`batch` 这个形状不存在。
    `price-lists/lists/${ID}/items/batch`,
    // 不存在的子路径。
    `price-lists/lists/${ID}/items/${ID}/extra`,
    // 价目表**本身**的增删改是 5.1.1.1 的形状 —— 它已被放行，但**写**形状不该由本条放行。
    `price-lists/lists/${ID}/items/resolve/extra`,
    // 非 UUID 段不应被 uuid 分支吃掉。
    `price-lists/lists/not-a-uuid/items`,
    `price-lists/lists/${ID}/items/not-a-uuid`,
    // 裸路径：后端没有「GET /catalog/price-lists/item-capabilities/items」这种形状。
    'price-lists/item-capabilities/extra',
  ]) {
    const response = await callProxy(path);
    expect(response.status, `${path} 不该被放行`).toBe(404);
  }
});

it('🔴 兄弟车道已交付的 catalog 路由未被本车道挤掉或污染', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);

  const siblings = [
    // 5.1.1.1 价目表（**本模块的表头**，必须仍在白名单里）
    'price-lists/capabilities',
    'price-lists/scope-directory',
    'price-lists/coverage',
    'price-lists/lists',
    `price-lists/lists/${ID}`,
    // 3.1.1.1 商品主数据
    'products',
    // 3.1.2.1 SKU 列表
    'skus/capabilities',
    // 3.6.2.1 供应商报价（同样有「同优先级多条 ⇒ 报错让用户选」的规则）
    'supplier-quotes',
    // 5.3.2.1 定价模板
    'pricing-templates/capabilities',
    // 5.5.2.1 异常告警
    'price-anomaly-alerts/capabilities',
    // 5.4.1.1 平台价格同步
    'price-sync/capabilities',
  ];
  for (const path of siblings) {
    const response = await callProxy(path);
    expect(response.status, `兄弟模块 ${path} 被本车道改动挤掉了`).not.toBe(404);
  }
});

it('本模块的字面量子路径不会被 uuid 分支提前吃掉', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);

  // `conflicts` / `resolve` / `preview` / `apply` / `export` / `import` 的首字母
  // 大多落在 [0-9a-f] 里 —— 这条断言是为了保证**判别顺序**没写反
  // （字面量子路径必须排在 {uuid} 段之前）。
  for (const path of [
    `price-lists/lists/${ID}/items/conflicts`,
    `price-lists/lists/${ID}/items/resolve`,
    `price-lists/lists/${ID}/items/preview`,
    `price-lists/lists/${ID}/items/apply`,
    `price-lists/lists/${ID}/items/export`,
    `price-lists/lists/${ID}/items/import`,
  ]) {
    const response = await callProxy(path);
    expect(response.status, `${path} 未被放行`).not.toBe(404);
  }
});
