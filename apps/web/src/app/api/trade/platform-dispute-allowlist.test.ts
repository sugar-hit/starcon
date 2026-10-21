import { afterEach, expect, it, vi } from 'vitest';
import { NextRequest } from 'next/server';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { DELETE, GET, POST, PUT } from './[...path]/route';

/**
 * 11.4.1.1 平台纠纷的 api-proxy 白名单**双向**断言（**按模块一份**，
 * 与同目录 `supplier-claim-allowlist.test.ts` 同一手法）。
 *
 * <p>白名单只在浏览器端生效：`pnpm test` 直连服务层天然测不出漏登记 ——
 * 漏登记的路径在浏览器里静默 404，而 typecheck / lint / vitest 全绿。
 * 判据是**真跑 proxy 断言 HTTP 状态码**（拦截 = 404，放行 = 转发），
 * 不是眼看正则、也不是自己再写一份正则去比。
 *
 * <p>两个方向都要断言：
 * <ul>
 *   <li><b>放行</b>：从本模块自己的模型源码里<b>反向抽取真实调用点</b>再逐条断言被放行。</li>
 *   <li><b>拒绝</b>：刻意不放行的形状仍然 404 —— 尤其是
 *       {@code /auto-lose}：蓝图加粗句「<b>平台纠纷有严格举证时限，超期自动判败</b>」
 *       正是这条规则的落点，**超期判负是系统做的，没有手动入口**。</li>
 * </ul>
 */

// 相对**本文件**定位：vitest 从仓库根或 apps/web 跑时 cwd 都不同，用 cwd 会静默读空。
const SOURCE = readFileSync(
  fileURLToPath(
    new URL('../../../components/aftersales/platform-dispute-model.ts', import.meta.url),
  ),
  'utf8',
);

const ID = '11111111-1111-1111-1111-111111111111';
const ITEM = '22222222-2222-2222-2222-222222222222';

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
 * 从模型源码里反向抽取**真实调用点**。
 *
 * ⚠️ 只认 `${base}` 与 id 类的占位。带 `${query(...)}` 的调用点**不是一条路径** ——
 *   它的 query 为空时解析成集合路径（如 `/platform-disputes`），非空时多出 `?a=b`，
 *   而把 `${query(params)}` 直接替换成 id 会凭空造出后端根本不存在的形状，
 *   拿它去断言放行等于断言一条假需求。
 */
function realCallSites(): string[] {
  const base = SOURCE.match(/const base = '([^']+)'/)?.[1] ?? 'trade/platform-disputes';
  const found = new Set<string>();
  for (const match of SOURCE.matchAll(/`([^`]*)`/g)) {
    const raw = match[1];
    if (!raw || !raw.includes('${base}')) continue;
    // ⚠️ **先把 query 段切掉再查占位**：像
    //   `${base}/${id}/start-evidence?expectedVersion=${expectedVersion}`
    //   这样的调用点，query 里的占位**不是路径的一部分**，
    //   留着会让整条路径被误判成「还有未解析的占位」而整条被跳过 ——
    //   于是「进入举证准备」这个端点就悄悄没人断言了。
    const pathPart = raw.split('?')[0] ?? '';
    // ⚠️ 只认 `${base}` 与 id 类的占位。带 `${query(...)}` 的调用点**不是一条路径** ——
    //   它的 query 为空时解析成集合路径（如 `/platform-disputes`），非空时多出 `?a=b`，
    //   而把 `${query(params)}` 直接替换成 id 会凭空造出后端根本不存在的形状，
    //   拿它去断言放行等于断言一条假需求。
    const unresolved = pathPart.replace(
      /\$\{base\}|\$\{id\}|\$\{itemId\}|\$\{settlementId\}/g,
      '',
    );
    if (unresolved.includes('${')) continue;
    const cleaned = pathPart
      .replace(/\$\{base\}/g, base)
      .replace(/\$\{id\}/g, ID)
      .replace(/\$\{itemId\}/g, ITEM)
      .replace(/\$\{settlementId\}/g, ITEM);
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
      'trade/platform-disputes/capabilities',
      'trade/platform-disputes',
      `trade/platform-disputes/${ID}`,
      `trade/platform-disputes/${ID}/evidence-items`,
      `trade/platform-disputes/${ID}/evidence-items/${ITEM}`,
      `trade/platform-disputes/${ID}/start-evidence`,
      `trade/platform-disputes/${ID}/position`,
      `trade/platform-disputes/${ID}/submit-evidence`,
      `trade/platform-disputes/${ID}/resolve`,
      `trade/platform-disputes/${ID}/withdraw`,
      `trade/platform-disputes/${ID}/settlements`,
      `trade/platform-disputes/${ID}/settlements/${ITEM}/confirm`,
      `trade/platform-disputes/${ID}/settlements/${ITEM}/reject`,
    ]),
  );
});

it('写形状（POST / PUT / DELETE）被放行，且真的转发到 8085', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);

  for (const path of [
    'trade/platform-disputes',
    `trade/platform-disputes/${ID}/evidence-items`,
    `trade/platform-disputes/${ID}/start-evidence`,
    `trade/platform-disputes/${ID}/position`,
    `trade/platform-disputes/${ID}/submit-evidence`,
    `trade/platform-disputes/${ID}/resolve`,
    `trade/platform-disputes/${ID}/withdraw`,
    `trade/platform-disputes/${ID}/settlements`,
    `trade/platform-disputes/${ID}/settlements/${ITEM}/confirm`,
    `trade/platform-disputes/${ID}/settlements/${ITEM}/reject`,
  ]) {
    const response = await POST(request(path, 'POST'), {
      params: Promise.resolve({ path: path.split('/') }),
    });
    expect(response.status, `POST ${path} 未被放行`).toBe(200);
  }

  // 更新举证材料状态是 PUT。
  const marked = await PUT(request(`trade/platform-disputes/${ID}/evidence-items/${ITEM}`, 'PUT'), {
    params: Promise.resolve({
      path: `trade/platform-disputes/${ID}/evidence-items/${ITEM}`.split('/'),
    }),
  });
  expect(marked.status, 'PUT 更新举证材料状态未被放行').toBe(200);

  // 移除举证材料项是 DELETE。
  const removed = await DELETE(
    request(`trade/platform-disputes/${ID}/evidence-items/${ITEM}`, 'DELETE'),
    { params: Promise.resolve({ path: `trade/platform-disputes/${ID}/evidence-items/${ITEM}`.split('/') }) },
  );
  expect(removed.status, 'DELETE 移除举证材料项未被放行').toBe(200);

  expect(fetcher).toHaveBeenCalledWith(
    expect.stringContaining(':8085/trade/platform-disputes'),
    expect.objectContaining({ method: 'POST' }),
  );
});

it('刻意不放行的形状仍然被拒 —— 放行后端不存在的映射比 404 更糟', async () => {
  const fetcher = vi.fn();
  vi.stubGlobal('fetch', fetcher);
  for (const path of [
    // ⚠️ 蓝图加粗句「**平台纠纷有严格举证时限，超期自动判败**」：
    //   超期判负是**系统做的**（后端 DB 守卫①），刻意不提供手动入口 ——
    //   放行一个「手动标记超期判负」等于让界面显示成「我可以提前把这件纠纷结掉」。
    `trade/platform-disputes/${ID}/auto-lose`,
    `trade/platform-disputes/${ID}/expire`,
    // 举证**清单**在详情里，没有单独的读端点。
    `trade/platform-disputes/${ID}/evidence`,
    // 本模块只落**清单事实**（该交什么 / 齐不齐 / 缺什么），不上传材料：
    //   材料与申诉属 11.4.1.2（未开发）。
    `trade/platform-disputes/${ID}/upload`,
    `trade/platform-disputes/${ID}/appeal`,
    // channel 服务**没有纠纷专用同步端点** ⇒ 放行它等于让界面以为平台会自动拉纠纷。
    `trade/platform-disputes/${ID}/sync`,
    // 汇总在列表响应里，没有单独的汇总端点。
    'trade/platform-disputes/summary',
    // 终态不可逆：纠纷凭据不能删。
    `trade/platform-disputes/${ID}/delete`,
    // 单字符不是 uuid：`[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}` 会把它放行。
    'trade/platform-disputes/c',
    // 拼错的字面量子路径与动作段。
    'trade/platform-disputes/capabilities/extra',
    `trade/platform-disputes/${ID}/resolves`,
    `trade/platform-disputes/${ID}/settlements/${ITEM}/confirms`,
    `trade/platform-disputes/${ID}/evidence-items/${ITEM}/extra`,
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
 * 断言 404 是一条永远红的测试。本模块对纠纷单本身的删除禁令**由后端承担** ——
 * `PlatformDisputeController` 没有对纠纷单的 DELETE 映射，
 * 留痕表的 UPDATE / DELETE 权限也已在 V265 里收回。
 */
it('白名单只判路径不判动词：DELETE 会被转发，由后端回 405', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);
  const response = await DELETE(request(`trade/platform-disputes/${ID}`, 'DELETE'), {
    params: Promise.resolve({ path: `trade/platform-disputes/${ID}`.split('/') }),
  });
  expect(response.status, 'DELETE 走的是同一条路径白名单').toBe(200);
  expect(fetcher).toHaveBeenCalledWith(
    expect.stringContaining(':8085/trade/platform-disputes'),
    expect.objectContaining({ method: 'DELETE' }),
  );
});

it('本模块的正则没有误伤相邻车道已交付的形状', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);
  // 本条元素插在 services[] 的**最末尾**，前缀形状与其它车道互不相交：
  // 本条一律以 `platform-disputes/` 开头，其余 trade 分支没有这个首 token。
  // ⚠️ 同批另有若干车道也在改这个文件 —— 求并时**不要**把它们的分支删掉，
  //   所以这里把它们当前已交付的形状一并断言，作为「求并后仍然全在」的证据。
  for (const path of [
    'trade/aftersales/capabilities',
    'trade/aftersales/cases',
    `trade/aftersales/cases/${ID}`,
    'trade/refunds/capabilities',
    `trade/refunds/${ID}/dispatch`,
    'trade/rma-authorizations/capabilities',
    `trade/rma-authorizations/${ID}/decide`,
    'trade/return-quality/capabilities',
    'trade/return-quality/inspections',
    `trade/return-quality/inspections/${ID}/conclude`,
    'trade/return-receipts/capabilities',
    `trade/return-receipts/${ID}/post`,
    'trade/product-attribution/capabilities',
    'trade/product-attribution/attributions',
    `trade/product-attribution/attributions/${ID}/attribute`,
    'trade/supplier-claims/capabilities',
    'trade/supplier-claims/candidates',
    'trade/supplier-claims',
    `trade/supplier-claims/${ID}`,
    `trade/supplier-claims/${ID}/items`,
  ]) {
    const response = await GET(request(path), {
      params: Promise.resolve({ path: path.split('/') }),
    });
    expect(response.status, `${path} 被本模块的正则误伤了`).toBe(200);
  }
});
