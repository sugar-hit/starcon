import { afterEach, expect, it, vi } from 'vitest';
import { NextRequest } from 'next/server';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { DELETE, GET, POST, PUT } from './[...path]/route';

/**
 * 11.1.2.2 商品问题归因的 api-proxy 白名单**双向**断言（**按模块一份**，
 * 与同目录 `return-quality-allowlist.test.ts` 同一手法）。
 *
 * <p>白名单只在浏览器端生效：`pnpm test` 直连服务层天然测不出漏登记 ——
 * 漏登记的路径在浏览器里静默 404，而 typecheck / lint / vitest 全绿。
 * 判据是**真跑 proxy 断言 HTTP 状态码**（拦截 = 404，放行 = 转发），
 * 不是眼看正则、也不是自己再写一份正则去比。
 *
 * <p>三个方向都要断言：
 * <ul>
 *   <li><b>放行</b>：从本模块自己的模型源码里<b>反向抽取真实调用点</b>再逐条断言被放行。</li>
 *   <li><b>拒绝</b>：刻意不放行的形状仍然 404 —— 尤其是 {@code /claim}、{@code /rating}
 *       与 {@code /similarity}：索赔属 6.4.2.2、评级属 6.1.1.3，均<b>未交付</b>；
 *       而图像相似度本仓根本没有接入点。</li>
 *   <li><b>不误伤</b>：相邻车道已交付的形状必须仍然放行 —— 这是<b>求并安全</b>的证据。
 *       本条元素是<b>新增一条 services[] 元素</b>而不是并进既有那条超长 alternation，
 *       就是为了让 git 求并时不可能把别人的分支吃掉。</li>
 * </ul>
 */

// 相对**本文件**定位：vitest 从仓库根或 apps/web 跑时 cwd 都不同，用 cwd 会静默读空。
const SOURCE = readFileSync(
  fileURLToPath(new URL('../../../components/aftersales/product-issue-attribution-model.ts', import.meta.url)),
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

function realCallSites(): string[] {
  const base = SOURCE.match(/const base = '([^']+)'/)?.[1] ?? 'trade/product-attribution';
  const found = new Set<string>();
  for (const match of SOURCE.matchAll(/`([^`]*)`/g)) {
    const raw = match[1];
    if (!raw || !raw.includes('${base}')) continue;
    // ⚠️ 只认 `${base}` 与 `${id}` 两种占位。带 `${query(...)}` 的调用点**不是一条路径**
    //   —— 它的 query 为空时解析成集合路径，非空时多出 `?a=b`，
    //   而把 `${query(params)}` 直接替换成 id 会凭空造出后端根本不存在的形状，
    //   拿它去断言放行等于断言一条假需求。
    const unresolved = raw.replace(/\$\{base\}|\$\{id\}/g, '');
    if (unresolved.includes('${')) continue;
    const cleaned = raw
      .replace(/\$\{base\}/g, base)
      .replace(/\$\{id\}/g, ID)
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
  // ⚠️ 这里**只**列不带 query 的调用点：`${query(params)}` 那两个
  //   （`/attributions`、`/lot-flags`、`/lot-flags/by-lot`）被 realCallSites 有意跳过 ——
  //   它的 query 为空时解析成集合路径、非空时多出 `?a=b`，
  //   把 `${query(params)}` 直接替换成 id 会凭空造出后端不存在的形状。
  //   它们改由下面那条「写形状被放行」用显式路径逐条断言。
  expect(sites).toEqual(
    expect.arrayContaining([
      'trade/product-attribution/capabilities',
      'trade/product-attribution/attributions',
      `trade/product-attribution/attributions/${ID}`,
      `trade/product-attribution/attributions/${ID}/photos`,
      `trade/product-attribution/attributions/${ID}/photos/compare`,
      `trade/product-attribution/attributions/${ID}/attribute`,
      `trade/product-attribution/attributions/${ID}/supersede`,
    ]),
  );
});

it('写形状（POST / PUT）被放行，且真的转发到 8085', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);

  for (const path of [
    'trade/product-attribution/attributions',
    `trade/product-attribution/attributions/${ID}/photos/compare`,
    `trade/product-attribution/attributions/${ID}/attribute`,
    `trade/product-attribution/attributions/${ID}/supersede`,
    `trade/product-attribution/attributions/${ID}/lot-flag/refresh`,
  ]) {
    const response = await POST(request(path, 'POST'), {
      params: Promise.resolve({ path: path.split('/') }),
    });
    expect(response.status, `POST ${path} 未被放行`).toBe(200);
  }

  const photos = await PUT(request(`trade/product-attribution/attributions/${ID}/photos`, 'PUT'), {
    params: Promise.resolve({ path: `trade/product-attribution/attributions/${ID}/photos`.split('/') }),
  });
  expect(photos.status, 'PUT photos 未被放行').toBe(200);

  // 批次质量标记的读端点同样要放行。
  for (const path of [
    'trade/product-attribution/lot-flags',
    'trade/product-attribution/lot-flags/by-lot',
    'trade/product-attribution/capabilities',
    `trade/product-attribution/attributions/${ID}`,
  ]) {
    const response = await GET(request(path), {
      params: Promise.resolve({ path: path.split('/') }),
    });
    expect(response.status, `GET ${path} 未被放行`).toBe(200);
  }

  expect(fetcher).toHaveBeenCalledWith(
    expect.stringContaining(':8085/trade/product-attribution'),
    expect.objectContaining({ method: 'POST' }),
  );
});

it('刻意不放行的形状仍然被拒 —— 放行后端不存在的映射比 404 更糟', async () => {
  const fetcher = vi.fn();
  vi.stubGlobal('fetch', fetcher);
  for (const path of [
    // ⚠️ 6.4.2.2 供应商索赔**未交付**：本模块只提供举证材料，
    // 放行它等于渲染一个注定被拒的「发起索赔」按钮。
    `trade/product-attribution/attributions/${ID}/claim`,
    // ⚠️ 6.1.1.3 供应商评级**未交付**：批次标记是评级的输入，评级本身不在这里。
    `trade/product-attribution/attributions/${ID}/rating`,
    // ⚠️ 图像相似度：本仓没有图像库、没有模型、没有第三方视觉服务的接入点。
    // 放行它等于渲染一个冒充图像识别能力的控件。
    `trade/product-attribution/attributions/${ID}/similarity`,
    // ⚠️ 归因结论**终态不可逆**：改判只走 /supersede 留痕另起一版。
    `trade/product-attribution/attributions/${ID}/verdict`,
    // 单字符不是 uuid：`[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}` 会把它放行。
    'trade/product-attribution/attributions/c',
    // 拼错的字面量子路径。
    'trade/product-attribution/summary',
    // 拼错的动作段。
    `trade/product-attribution/attributions/${ID}/attributes`,
    `trade/product-attribution/attributions/${ID}/photos/compares`,
    // 批次标记的刷新端点挂在归因单下，裸 `lot-flags/{uuid}` 这个形状不存在。
    `trade/product-attribution/lot-flags/${ID}`,
  ]) {
    const response = await GET(request(path), {
      params: Promise.resolve({ path: path.split('/') }),
    });
    expect(response.status, `${path} 被放行了，但它在后端不存在`).toBe(404);
  }
  expect(fetcher).not.toHaveBeenCalled();
});

/**
 * 白名单是**按路径**判的，不按 HTTP 动词 —— 所以 DELETE 会被放行到后端，由后端回 405。
 *
 * <p>⚠️ 这里刻意**不断言** DELETE 404：代理的 `services[].pattern` 里没有动词，
 * 断言 404 是一条永远红的测试。本模块的删除禁令**由后端承担** ——
 * `ProductIssueAttributionController` 没有 DELETE 映射，
 * 留痕表的 UPDATE / DELETE 权限也已在 V180 里收回，两层都不给删。
 */
it('白名单只判路径不判动词：DELETE 会被转发，由后端回 405', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);
  const path = `trade/product-attribution/attributions/${ID}`;
  const response = await DELETE(request(path, 'DELETE'), {
    params: Promise.resolve({ path: path.split('/') }),
  });
  expect(response.status, 'DELETE 走的是同一条路径白名单').toBe(200);
  expect(fetcher).toHaveBeenCalledWith(
    expect.stringContaining(':8085/trade/product-attribution'),
    expect.objectContaining({ method: 'DELETE' }),
  );
});

it('本模块的正则没有误伤相邻车道已交付的形状', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);
  // 本条元素插在 11.2.3.1（return-quality）之后、11.1.1.2（aftersales/cases）之前，
  // 前缀形状互不相交：本条一律以 `product-attribution/` 开头，那两条都不是。
  // ⚠️ 同批另有若干车道也在改这个文件 —— 求并时**不要**把它们的分支删掉，
  //   所以这里把它们当前已交付的形状一并断言，作为「求并后仍然全在」的证据。
  for (const path of [
    'trade/return-quality/capabilities',
    'trade/return-quality/inspections',
    `trade/return-quality/inspections/${ID}/conclude`,
    'trade/return-receipts/capabilities',
    `trade/return-receipts/${ID}/post`,
    'trade/receipt-allocations/capabilities',
    `trade/receipt-allocations/${ID}/reconcile`,
    'trade/rma-instructions/capabilities',
    `trade/rma-instructions/${ID}/issue`,
    'trade/aftersales/capabilities',
    'trade/aftersales/cases',
    `trade/aftersales/cases/${ID}`,
    'trade/aftersales/reasons/capabilities',
  ]) {
    const response = await GET(request(path), {
      params: Promise.resolve({ path: path.split('/') }),
    });
    expect(response.status, `${path} 被本模块的正则误伤了`).toBe(200);
  }
});
