import { afterEach, expect, it, vi } from 'vitest';
import { NextRequest } from 'next/server';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { DELETE, GET, POST, PUT } from './[...path]/route';

/**
 * 11.2.3.3 供应商索赔的 api-proxy 白名单**双向**断言（**按模块一份**，
 * 与同目录 `return-quality-allowlist.test.ts` 同一手法）。
 *
 * <p>白名单只在浏览器端生效：`pnpm test` 直连服务层天然测不出漏登记 ——
 * 漏登记的路径在浏览器里静默 404，而 typecheck / lint / vitest 全绿。
 * 判据是**真跑 proxy 断言 HTTP 状态码**（拦截 = 404，放行 = 转发），
 * 不是眼看正则、也不是自己再写一份正则去比。
 *
 * <p>两个方向都要断言：
 * <ul>
 *   <li><b>放行</b>：从本模块自己的模型源码里<b>反向抽取真实调用点</b>再逐条断言被放行。</li>
 *   <li><b>拒绝</b>：刻意不放行的形状仍然 404 —— 尤其是 {@code /settle}：
 *       蓝图加粗句「<b>索赔不是自动到账</b>，系统只跟踪流程与金额，冲减应付需财务确认」
 *       正是这条规则的落点。</li>
 * </ul>
 */

// 相对**本文件**定位：vitest 从仓库根或 apps/web 跑时 cwd 都不同，用 cwd 会静默读空。
const SOURCE = readFileSync(
  fileURLToPath(new URL('../../../components/aftersales/supplier-claim-model.ts', import.meta.url)),
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
  const base = SOURCE.match(/const base = '([^']+)'/)?.[1] ?? 'trade/supplier-claims';
  const found = new Set<string>();
  for (const match of SOURCE.matchAll(/`([^`]*)`/g)) {
    const raw = match[1];
    if (!raw || !raw.includes('${base}')) continue;
    // ⚠️ 只认 `${base}` 与 `${id}` 两种占位。带 `${query(...)}` 的调用点**不是一条路径**
    //   —— 它的 query 为空时解析成集合路径（如 `/supplier-claims`），非空时多出 `?a=b`，
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
  expect(sites).toEqual(
    expect.arrayContaining([
      'trade/supplier-claims/capabilities',
      'trade/supplier-claims',
      `trade/supplier-claims/${ID}`,
    ]),
  );
});

it('只读端点（候选 / 列表 / 详情）都被放行', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);

  // ⚠️ `candidates` 的调用点带 `${query(params)}`，抽取器会跳过它（那不是一条路径），
  //   所以在这里单独断言 —— 它是「发起索赔」的唯一入口素材，漏登记就是整块功能不可用。
  for (const path of [
    'trade/supplier-claims/capabilities',
    'trade/supplier-claims/candidates',
    'trade/supplier-claims',
    `trade/supplier-claims/${ID}`,
  ]) {
    const response = await GET(request(path), {
      params: Promise.resolve({ path: path.split('/') }),
    });
    expect(response.status, `GET ${path} 未被放行`).toBe(200);
  }
});

it('写形状（POST / DELETE）被放行，且真的转发到 8085', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);

  for (const path of [
    'trade/supplier-claims',
    `trade/supplier-claims/${ID}/items`,
    `trade/supplier-claims/${ID}/submit`,
    `trade/supplier-claims/${ID}/start-negotiation`,
    `trade/supplier-claims/${ID}/negotiations`,
    `trade/supplier-claims/${ID}/agree`,
    `trade/supplier-claims/${ID}/reject`,
    `trade/supplier-claims/${ID}/withdraw`,
    `trade/supplier-claims/${ID}/settlements`,
    `trade/supplier-claims/${ID}/settlements/${ID}/confirm`,
    `trade/supplier-claims/${ID}/settlements/${ID}/reject`,
  ]) {
    const response = await POST(request(path, 'POST'), {
      params: Promise.resolve({ path: path.split('/') }),
    });
    expect(response.status, `POST ${path} 未被放行`).toBe(200);
  }

  // 移除明细是本模块**唯一**允许的 DELETE（且后端只在草稿期放行）。
  const removed = await DELETE(
    request(`trade/supplier-claims/${ID}/items/${ID}`, 'DELETE'),
    { params: Promise.resolve({ path: `trade/supplier-claims/${ID}/items/${ID}`.split('/') }) },
  );
  expect(removed.status, 'DELETE 移除明细未��放行').toBe(200);

  expect(fetcher).toHaveBeenCalledWith(
    expect.stringContaining(':8085/trade/supplier-claims'),
    expect.objectContaining({ method: 'POST' }),
  );
});

it('刻意不放行的形状仍然被拒 —— 放行后端不存在的映射比 404 更糟', async () => {
  const fetcher = vi.fn();
  vi.stubGlobal('fetch', fetcher);
  for (const path of [
    // ⚠️ 蓝图加粗句「**索赔不是自动到账**」：已结算只能由「财务确认赔付分录」驱动。
    //   放行一个「直接置为已结算」等于让界面显示成「登记完赔付就等于钱到账了」。
    `trade/supplier-claims/${ID}/settle`,
    `trade/supplier-claims/${ID}/settled`,
    // ⚠️ 财务确认必须挂在**赔付分录**上，否则「确认的是哪一笔赔付」在库里没有答案。
    `trade/supplier-claims/${ID}/confirm`,
    // 单字符不是 uuid：`[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}` 会把它放行。
    'trade/supplier-claims/c',
    // 拼错的字面量子路径。
    'trade/supplier-claims/summary',
    'trade/supplier-claims/capabilities/extra',
    // 拼错的动作段。
    `trade/supplier-claims/${ID}/agrees`,
    `trade/supplier-claims/${ID}/settlements/${ID}/confirms`,
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
 * 断言 404 是一条永远红的测试。本模块对索赔单本身的删除禁令**由后端承担** ——
 * `SupplierClaimController` 没有对索赔单的 DELETE 映射，
 * 协商记录与留痕表的 UPDATE / DELETE 权限也已在 V188 里收回。
 */
it('白名单只判路径不判动词：DELETE 会被转发，由后端回 405', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);
  const response = await DELETE(request(`trade/supplier-claims/${ID}`, 'DELETE'), {
    params: Promise.resolve({ path: `trade/supplier-claims/${ID}`.split('/') }),
  });
  expect(response.status, 'DELETE 走的是同一条路径白名单').toBe(200);
  expect(fetcher).toHaveBeenCalledWith(
    expect.stringContaining(':8085/trade/supplier-claims'),
    expect.objectContaining({ method: 'DELETE' }),
  );
});

it('本模块的正则没有误伤相邻车道已交付的形状', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);
  // 本条元素插在 11.1.2.2（product-attribution）与 11.5.2.1（quality-analysis）之间，
  // 前缀形状互不相交：本条一律以 `supplier-claims/` 开头，那两条都不是。
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
  ]) {
    const response = await GET(request(path), {
      params: Promise.resolve({ path: path.split('/') }),
    });
    expect(response.status, `${path} 被本模块的正则误伤了`).toBe(200);
  }
});
