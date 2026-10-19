import { afterEach, expect, it, vi } from 'vitest';
import { NextRequest } from 'next/server';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { DELETE, GET, POST, PUT } from './[...path]/route';

/**
 * 11.2.3.1 质检与判定的 api-proxy 白名单**双向**断言（**按模块一份**，
 * 与同目录 `return-receipt-allowlist.test.ts` 同一手法）。
 *
 * <p>白名单只在浏览器端生效：`pnpm test` 直连服务层天然测不出漏登记 ——
 * 漏登记的路径在浏览器里静默 404，而 typecheck / lint / vitest 全绿。
 * 判据是**真跑 proxy 断言 HTTP 状态码**（拦截 = 404，放行 = 转发），
 * 不是眼看正则、也不是自己再写一份正则去比。
 *
 * <p>两个方向都要断言：
 * <ul>
 *   <li><b>放行</b>：从本模块自己的模型源码里<b>反向抽取真实调用点</b>再逐条断言被放行。</li>
 *   <li><b>拒绝</b>：刻意不放行的形状仍然 404 —— 尤其是 {@code /resell} 与
 *       {@code /scrap}：处置属 11.2.3.2 未交付，而蓝图加粗句
 *       「<b>报废是不可逆的</b>，需审批与原因，不能由客服随手处理」正是这条规则的落点。</li>
 * </ul>
 */

// 相对**本文件**定位：vitest 从仓库根或 apps/web 跑时 cwd 都不同，用 cwd 会静默读空。
const SOURCE = readFileSync(
  fileURLToPath(new URL('../../../components/aftersales/return-quality-model.ts', import.meta.url)),
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
  const base = SOURCE.match(/const base = '([^']+)'/)?.[1] ?? 'trade/return-quality';
  const found = new Set<string>();
  for (const match of SOURCE.matchAll(/`([^`]*)`/g)) {
    const raw = match[1];
    if (!raw || !raw.includes('${base}')) continue;
    // ⚠️ 只认 `${base}` 与 `${id}` 两种占位。带 `${query(...)}` 的调用点**不是一条路径**
    //   —— 它的 query 为空时解析成集合路径（如 `/categories`），非空时多出 `?a=b`，
    //   而把 `${query(params)}` 直接替换成 id 会凭空造出 `categories1111...` 这种
    //   后端根本不存在的形状，拿它去断言放行等于断言一条假需求。
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
  expect(sites).toEqual(
    expect.arrayContaining([
      'trade/return-quality/capabilities',
      'trade/return-quality/inspections',
      `trade/return-quality/inspections/${ID}`,
      `trade/return-quality/inspections/${ID}/conclude`,
    ]),
  );
});

it('写形状（POST / PUT）被放行，且真的转发到 8085', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);

  for (const path of [
    'trade/return-quality/categories',
    'trade/return-quality/templates',
    'trade/return-quality/inspections',
    `trade/return-quality/categories/${ID}/skus`,
    `trade/return-quality/templates/${ID}/activate`,
    `trade/return-quality/templates/${ID}/retire`,
    `trade/return-quality/inspections/${ID}/conclude`,
    `trade/return-quality/inspections/${ID}/cancel`,
  ]) {
    const response = await POST(request(path, 'POST'), {
      params: Promise.resolve({ path: path.split('/') }),
    });
    expect(response.status, `POST ${path} 未被放行`).toBe(200);
  }

  for (const path of [
    `trade/return-quality/categories/${ID}`,
    `trade/return-quality/templates/${ID}`,
    `trade/return-quality/inspections/${ID}`,
  ]) {
    const response = await PUT(request(path, 'PUT'), {
      params: Promise.resolve({ path: path.split('/') }),
    });
    expect(response.status, `PUT ${path} 未被放行`).toBe(200);
  }

  // 待检队列与判定预览是质检员第一屏的读端点，同样要放行。
  for (const path of [
    'trade/return-quality/categories',
    'trade/return-quality/templates',
    'trade/return-quality/queue',
    'trade/return-quality/verdict-preview',
    `trade/return-quality/templates/${ID}`,
  ]) {
    const response = await GET(request(path), {
      params: Promise.resolve({ path: path.split('/') }),
    });
    expect(response.status, `GET ${path} 未被放行`).toBe(200);
  }

  expect(fetcher).toHaveBeenCalledWith(
    expect.stringContaining(':8085/trade/return-quality'),
    expect.objectContaining({ method: 'POST' }),
  );
});

it('刻意不放行的形状仍然被拒 —— 放行后端不存在的映射比 404 更糟', async () => {
  const fetcher = vi.fn();
  vi.stubGlobal('fetch', fetcher);
  for (const path of [
    // ⚠️ 11.2.3.2 未交付：放行「执行处置」会让界面显示成判完就能上架，
    // 而蓝图加粗句「报废是**不可逆的**，需审批与原因」正是这条规则的落点。
    `trade/return-quality/inspections/${ID}/resell`,
    `trade/return-quality/inspections/${ID}/dispose`,
    `trade/return-quality/inspections/${ID}/scrap`,
    // ⚠️ 12 财务未交付，且是**独立数据库**：跨服务写账被模块边界纪律明令禁止。
    `trade/return-quality/inspections/${ID}/salvage-posting`,
    // ⚠️ 11.2.3.3 未交付：本模块只提供举证材料，不产出索赔结论。
    `trade/return-quality/inspections/${ID}/claim`,
    // ⚠️ 结论终态不可逆：再开一个「直接改结论」的形状，
    // 会让「这批货到底判成什么」出现两个答案。
    `trade/return-quality/inspections/${ID}/verdict`,
    // 单字符不是 uuid：`[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}` 会把它放行。
    'trade/return-quality/c',
    // 拼错的字面量子路径。
    'trade/return-quality/summary',
    'trade/return-quality/queue/extra',
    // 拼错的动作段。
    `trade/return-quality/templates/${ID}/activates`,
    `trade/return-quality/inspections/${ID}/concludes`,
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
 * `ReturnQualityController` 没有 DELETE 映射，运行角色对质量状态指令的
 * UPDATE / DELETE 权限也已在 V179 里收回，两层都不给删。
 */
it('白名单只判路径不判动词：DELETE 会被转发，由后端回 405', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);
  const response = await DELETE(request(`trade/return-quality/inspections/${ID}`, 'DELETE'), {
    params: Promise.resolve({ path: `trade/return-quality/inspections/${ID}`.split('/') }),
  });
  expect(response.status, 'DELETE 走的是同一条路径白名单').toBe(200);
  expect(fetcher).toHaveBeenCalledWith(
    expect.stringContaining(':8085/trade/return-quality'),
    expect.objectContaining({ method: 'DELETE' }),
  );
});

it('本模块的正则没有误伤相邻车道已交付的形状', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);
  // 本条元素插在 11.2.2.1（return-receipts）与 11.1.1.2（aftersales/cases）之间，
  // 前缀形状互不相交：本条一律以 `return-quality/` 开头，那两条都不是。
  // ⚠️ 同批另有若干 11.x 车道也在改这个文件 —— 求并时**不要**把它们的分支删掉，
  //   所以这里把它们当前已交付的形状一并断言，作为「求并后仍然全在」的证据。
  for (const path of [
    'trade/aftersales/capabilities',
    'trade/aftersales/cases',
    `trade/aftersales/cases/${ID}`,
    'trade/refunds/capabilities',
    `trade/refunds/${ID}/dispatch`,
    'trade/rma-authorizations/capabilities',
    `trade/rma-authorizations/${ID}/decide`,
    'trade/rma-instructions/capabilities',
    `trade/rma-instructions/${ID}/issue`,
    `trade/rma-instructions/${ID}/label`,
    'trade/return-receipts/capabilities',
    'trade/return-receipts/receivable',
    `trade/return-receipts/${ID}/post`,
    `trade/return-receipts/${ID}/photos`,
  ]) {
    const response = await GET(request(path), {
      params: Promise.resolve({ path: path.split('/') }),
    });
    expect(response.status, `${path} 被本模块的正则误伤了`).toBe(200);
  }
});
