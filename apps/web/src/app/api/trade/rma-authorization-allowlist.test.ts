import { afterEach, expect, it, vi } from 'vitest';
import { NextRequest } from 'next/server';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { GET, POST, PUT } from './[...path]/route';

/**
 * 11.2.1.1 退货授权单的 api-proxy 白名单**双向**断言（**按模块一份**，
 * 与同目录 `aftersales-detail-allowlist.test.ts` 同一手法）。
 *
 * <p>白名单只在浏览器端生效：`pnpm test` 直连服务层天然测不出漏登记 ——
 * 漏登记的路径在浏览器里静默 404，而 typecheck / lint / vitest 全绿。
 * 判据是**真跑 proxy 断言 HTTP 状态码**（拦截 = 404，放行 = 转发），
 * 不是眼看正则、也不是自己再写一份正则去比。
 *
 * <p>两个方向都要断言：
 * <ul>
 *   <li><b>放行</b>：从本模块自己的模型源码里<b>反向抽取真实调用点</b>再逐条断言被放行。</li>
 *   <li><b>拒绝</b>：刻意不放行的形状仍然 404 —— 尤其是 <b>receive</b>：
 *       登记退货实收属 11.2.2.1 未交付，放行它会让界面显示成货已到手，
 *       而蓝图加粗句正是「授权不等于已收到」。</li>
 * </ul>
 */

// 相对**本文件**定位：vitest 从仓库根或 apps/web 跑时 cwd 都不同，用 cwd 会静默读空。
const SOURCE = readFileSync(
  fileURLToPath(new URL('../../../components/aftersales/rma-authorization-model.ts', import.meta.url)),
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
  const base = SOURCE.match(/const base = '([^']+)'/)?.[1] ?? 'trade/rma-authorizations';
  const found = new Set<string>();
  for (const match of SOURCE.matchAll(/`([^`]*)`/g)) {
    const raw = match[1];
    if (!raw || !raw.includes('${base}')) continue;
    const cleaned = raw
      .replace(/\$\{base\}/g, base)
      .replace(/\$\{id\}/g, ID)
      .replace(/\$\{caseId\}/g, ID)
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
    const method = path.includes('expire-overdue') ? 'POST' : 'GET';
    const handler = method === 'POST' ? POST : GET;
    const response = await handler(request(path, method), {
      params: Promise.resolve({ path: path.split('/') }),
    });
    expect(response.status, `${path} 未被放行（白名单漏登记 = 浏览器里静默 404）`).toBe(200);
  }
  expect(sites).toEqual(
    expect.arrayContaining([
      'trade/rma-authorizations/capabilities',
      'trade/rma-authorizations/returnable-quantities',
      `trade/rma-authorizations/${ID}`,
      `trade/rma-authorizations/${ID}/decide`,
      `trade/rma-authorizations/${ID}/return-logistics`,
      `trade/rma-authorizations/${ID}/cancel`,
    ]),
  );
});

it('写形状（POST / PUT）被放行，且真的转发到 8085', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);

  for (const path of ['trade/rma-authorizations', `trade/rma-authorizations/${ID}/decide`,
    `trade/rma-authorizations/${ID}/return-logistics`, `trade/rma-authorizations/${ID}/cancel`,
    'trade/rma-authorizations/expire-overdue']) {
    const response = await POST(request(path, 'POST'), {
      params: Promise.resolve({ path: path.split('/') }),
    });
    expect(response.status, `POST ${path} 未被放行`).toBe(200);
  }
  const put = await PUT(request(`trade/rma-authorizations/${ID}`, 'PUT'), {
    params: Promise.resolve({ path: `trade/rma-authorizations/${ID}`.split('/') }),
  });
  expect(put.status, `PUT 改草稿未被放行`).toBe(200);

  expect(fetcher).toHaveBeenCalledWith(
    expect.stringContaining(':8085/trade/rma-authorizations'),
    expect.objectContaining({ method: 'POST' }),
  );
});

it('刻意不放行的形状仍然被拒 —— 放行后端不存在的映射比 404 更糟', async () => {
  const fetcher = vi.fn();
  vi.stubGlobal('fetch', fetcher);
  for (const path of [
    // ⚠️ 11.2.2.1 未交付：放行「标记已收到」会让界面显示成货已到手并开始做入库分录，
    // 而退回商品**默认进待检**，直接当已收货处理会把残次品当新品卖出去。
    `trade/rma-authorizations/${ID}/receive`,
    // 11.2.1.2 未交付：退货指引与标签不是本模块的动作。
    `trade/rma-authorizations/${ID}/instruction`,
    // 单字符不是 uuid：`[0-9a-f-]+` 会把它放行。
    'trade/rma-authorizations/c',
    // 拼错的字面量（判别顺序里 capabilities / returnable-quantities / expire-overdue
    // 排在 uuid 段之前，但它们**只认自己那三个**）
    'trade/rma-authorizations/summary',
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
  // 本条元素与 11.1.1.2 / 11.3.1.2 相邻，前缀形状互不相交：
  // 本条一律以 `rma-authorizations/` 开头，那两条都不是。
  for (const path of [
    'trade/aftersales/capabilities',
    'trade/aftersales/cases',
    `trade/aftersales/cases/${ID}`,
    'trade/refunds/capabilities',
  ]) {
    const response = await GET(request(path), {
      params: Promise.resolve({ path: path.split('/') }),
    });
    expect(response.status, `${path} 被本模块的正则误伤了`).toBe(200);
  }
});
