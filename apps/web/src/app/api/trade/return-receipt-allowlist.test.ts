import { afterEach, expect, it, vi } from 'vitest';
import { NextRequest } from 'next/server';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { GET, POST, PUT } from './[...path]/route';

/**
 * 11.2.2.1 退货入库的 api-proxy 白名单**双向**断言（**按模块一份**，
 * 与同目录 `rma-instruction-allowlist.test.ts` 同一手法）。
 *
 * <p>白名单只在浏览器端生效：`pnpm test` 直连服务层天然测不出漏登记 ——
 * 漏登记的路径在浏览器里静默 404，而 typecheck / lint / vitest 全绿。
 * 判据是**真跑 proxy 断言 HTTP 状态码**（拦截 = 404，放行 = 转发），
 * 不是眼看正则、也不是自己再写一份正则去比。
 *
 * <p>两个方向都要断言：
 * <ul>
 *   <li><b>放行</b>：从本模块自己的模型源码里<b>反向抽取真实调用点</b>再逐条断言被放行。</li>
 *   <li><b>拒绝</b>：刻意不放行的形状仍然 404 —— 尤其是 <b>resell</b>：
 *       处置属 11.2.3.2 未交付，放行它会让界面显示成「这批货可以直接转可售」，
 *       而蓝图加粗句「退回商品<b>默认进待检状态</b>」正是这条规则要防的那件事。</li>
 * </ul>
 */

// 相对**本文件**定位：vitest 从仓库根或 apps/web 跑时 cwd 都不同，用 cwd 会静默读空。
const SOURCE = readFileSync(
  fileURLToPath(new URL('../../../components/aftersales/return-receipt-model.ts', import.meta.url)),
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
  const base = SOURCE.match(/const base = '([^']+)'/)?.[1] ?? 'trade/return-receipts';
  const found = new Set<string>();
  for (const match of SOURCE.matchAll(/`([^`]*)`/g)) {
    const raw = match[1];
    if (!raw || !raw.includes('${base}')) continue;
    const cleaned = raw
      .replace(/\$\{base\}/g, base)
      .replace(/\$\{id\}/g, ID)
      .replace(/\$\{[^}]*\}/g, ID)
      .replace(/\?.*$/, '');
    if (cleaned.includes('${')) continue;
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
    expect.arrayContaining(['trade/return-receipts/capabilities', `trade/return-receipts/${ID}`]),
  );
});

it('写形状（POST / PUT）被放行，且真的转发到 8085', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);

  for (const path of [
    'trade/return-receipts',
    `trade/return-receipts/${ID}/post`,
    `trade/return-receipts/${ID}/cancel`,
    `trade/return-receipts/${ID}/photos`,
  ]) {
    const response = await POST(request(path, 'POST'), {
      params: Promise.resolve({ path: path.split('/') }),
    });
    expect(response.status, `POST ${path} 未被放行`).toBe(200);
  }
  const put = await PUT(request(`trade/return-receipts/${ID}`, 'PUT'), {
    params: Promise.resolve({ path: `trade/return-receipts/${ID}`.split('/') }),
  });
  expect(put.status, 'PUT 改草稿未被放行').toBe(200);

  // 集合 GET 与 remaining-receivable 读端点同样要放行。
  for (const path of ['trade/return-receipts', 'trade/return-receipts/receivable']) {
    const response = await GET(request(path), {
      params: Promise.resolve({ path: path.split('/') }),
    });
    expect(response.status, `GET ${path} 未被放行`).toBe(200);
  }

  expect(fetcher).toHaveBeenCalledWith(
    expect.stringContaining(':8085/trade/return-receipts'),
    expect.objectContaining({ method: 'POST' }),
  );
});

it('刻意不放行的形状仍然被拒 —— 放行后端不存在的映射比 404 更糟', async () => {
  const fetcher = vi.fn();
  vi.stubGlobal('fetch', fetcher);
  for (const path of [
    // ⚠️ 11.2.3.2 未交付：放行「转可售」会让界面显示成这批货可以直接当新品卖，
    // 而退回商品**默认进待检**是蓝图加粗句里的硬规则。
    `trade/return-receipts/${ID}/resell`,
    // ⚠️ 11.2.3.1 未交付：放行「质检判定」会让界面显示成质检已完成。
    `trade/return-receipts/${ID}/inspect`,
    // ⚠️ 11.2.2.2 未交付：放行「分配」等于声称已回写订单行已退数量。
    `trade/return-receipts/${ID}/allocate`,
    // ⚠️ 11.2.2.1 的「一键收货」不存在：指引下发 ≠ 货已到手，
    // 登记实收只能走草稿 → 入库两段式，放行 receive 等于跳过清点与两个判定维度。
    `trade/return-receipts/${ID}/receive`,
    // 单字符不是 uuid：`[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}` 会把它放行。
    'trade/return-receipts/c',
    // 拼错的字面量（判别顺序里只有 capabilities / receivable 排在 uuid 段之前，
    // 但它们**只认自己那两个**）。
    'trade/return-receipts/summary',
    // 拼错的动作段。
    `trade/return-receipts/${ID}/posts`,
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
 * `ReturnReceiptController` 没有 DELETE 映射，运行角色对照片 / 留痕 / 入库分录的
 * UPDATE、DELETE 权限也已在 V172 里收回，两层都不给删。
 *
 * <p>所以这一例只断言「代理确实把 DELETE 转发了」这一既成事实，
 * 让「白名单不判动词」这个属性被记录下来，而不是伪装成一条禁令。
 */
it('白名单只判路径不判动词：DELETE 会被转发，由后端回 405', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);
  const { DELETE } = await import('./[...path]/route');
  const response = await DELETE(request(`trade/return-receipts/${ID}`, 'DELETE'), {
    params: Promise.resolve({ path: `trade/return-receipts/${ID}`.split('/') }),
  });
  expect(response.status, 'DELETE 走的是同一条路径白名单').toBe(200);
  expect(fetcher).toHaveBeenCalledWith(
    expect.stringContaining(':8085/trade/return-receipts'),
    expect.objectContaining({ method: 'DELETE' }),
  );
});

it('本模块的正则没有误伤相邻车道已交付的形状', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);
  // 本条元素与 11.2.1.2（rma-instructions）/ 11.2.1.1（rma-authorizations）相邻，
  // 前缀形状互不相交：本条一律以 `return-receipts/` 开头，那两条都不是。
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
  ]) {
    const response = await GET(request(path), {
      params: Promise.resolve({ path: path.split('/') }),
    });
    expect(response.status, `${path} 被本模块的正则误伤了`).toBe(200);
  }
});
