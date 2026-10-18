import { afterEach, expect, it, vi } from 'vitest';
import { NextRequest } from 'next/server';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { GET, POST, PUT } from './[...path]/route';

/**
 * 11.3.1.1 退款申请的 api-proxy 白名单**双向**断言（**按模块一份**，
 * 与同目录 `rma-authorization-allowlist.test.ts` 同一手法）。
 *
 * <p>白名单只在浏览器端生效：`pnpm test` 直连服务层天然测不出漏登记 ——
 * 漏登记的路径在浏览器里静默 404，而 typecheck / lint / vitest 全绿。
 * 判据是**真跑 proxy 断言 HTTP 状态码**（拦截 = 404，放行 = 转发），
 * 不是眼看正则、也不是自己再写一份正则去比。
 *
 * <p>两个方向都要断言：
 * <ul>
 *   <li><b>放行</b>：从本模块自己的模型源码里<b>反向抽取真实调用点</b>再逐条断言被放行。</li>
 *   <li><b>拒绝</b>：刻意不放行的形状仍然 404 —— 尤其是 <b>verify</b>：
 *       「先查证交易再重试」属 11.3.1.2，放行它会让界面显示成本模块能查证，
 *       而执行记录定论与否由 11.3.1.2 说了算。</li>
 * </ul>
 *
 * <p><b>最后一条断言相邻车道的形状仍然放行</b>：本条元素紧挨着 11.3.1.2 的
 * {@code refunds/} 与 11.2.1.1 的 {@code rma-authorizations/}，
 * 取错一侧会**静默删掉别人已交付的路由** —— 表现是页面 404，
 * 而 typecheck / lint / vitest 全部全绿。
 */

// 相对**本文件**定位：vitest 从仓库根或 apps/web 跑时 cwd 都不同，用 cwd 会静默读空。
const SOURCE = readFileSync(
  fileURLToPath(new URL('../../../components/aftersales/refund-application-model.ts', import.meta.url)),
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
  const base = SOURCE.match(/const base = '([^']+)'/)?.[1] ?? 'trade/refund-applications';
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
    const method = /\/submit$|\/decide$|\/dispatch$|\/execution-outcome$|\/cancel$/.test(path)
      ? 'POST'
      : 'GET';
    const handler = method === 'POST' ? POST : GET;
    const response = await handler(request(path, method), {
      params: Promise.resolve({ path: path.split('/') }),
    });
    expect(response.status, `${path} 未被放行（白名单漏登记 = 浏览器里静默 404）`).toBe(200);
  }
  expect(sites).toEqual(
    expect.arrayContaining([
      'trade/refund-applications/capabilities',
      'trade/refund-applications/refundable-balance',
      'trade/refund-applications/approval-policy',
      'trade/refund-applications/filters',
      `trade/refund-applications/${ID}`,
      `trade/refund-applications/${ID}/submit`,
      `trade/refund-applications/${ID}/decide`,
      `trade/refund-applications/${ID}/dispatch`,
      `trade/refund-applications/${ID}/execution-outcome`,
      `trade/refund-applications/${ID}/cancel`,
    ]),
  );
});

it('写形状（POST / PUT）被放行，且真的转发到 8085', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);

  for (const path of [
    'trade/refund-applications',
    `trade/refund-applications/${ID}/submit`,
    `trade/refund-applications/${ID}/decide`,
    `trade/refund-applications/${ID}/dispatch`,
    `trade/refund-applications/${ID}/execution-outcome`,
    `trade/refund-applications/${ID}/cancel`,
  ]) {
    const response = await POST(request(path, 'POST'), {
      params: Promise.resolve({ path: path.split('/') }),
    });
    expect(response.status, `POST ${path} 未被放行`).toBe(200);
  }
  const put = await PUT(request(`trade/refund-applications/${ID}`, 'PUT'), {
    params: Promise.resolve({ path: `trade/refund-applications/${ID}`.split('/') }),
  });
  expect(put.status, 'PUT 改草稿未被放行').toBe(200);

  const policy = await PUT(request('trade/refund-applications/approval-policy', 'PUT'), {
    params: Promise.resolve({ path: 'trade/refund-applications/approval-policy'.split('/') }),
  });
  expect(policy.status, 'PUT 维护审批规则未被放行').toBe(200);

  expect(fetcher).toHaveBeenCalledWith(
    expect.stringContaining(':8085/trade/refund-applications'),
    expect.objectContaining({ method: 'POST' }),
  );
});

it('刻意不放行的形状仍然被拒 —— 放行后端不存在的映射比 404 更糟', async () => {
  const fetcher = vi.fn();
  vi.stubGlobal('fetch', fetcher);
  for (const path of [
    // ⚠️ 11.3.1.2 未交付入口：「先查证交易再重试」是**那张表**的写入所有权，
    // 放行它会让界面显示成本模块能查证，而执行记录定论与否由 11.3.1.2 说了算。
    `trade/refund-applications/${ID}/verify`,
    // 12.2.2.2 未交付：真实付款执行。放行一个注定失败的付款按钮比没有更糟。
    `trade/refund-applications/${ID}/pay`,
    // 单字符不是 uuid：`[0-9a-f-]+` 会把它放行。
    'trade/refund-applications/c',
    // 拼错的字面量（字面量子路径只认自己那几个）。
    'trade/refund-applications/summary',
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
  // 本条元素与 11.3.1.2（refunds/）、11.2.1.1（rma-authorizations/）相邻，
  // 前缀形状互不相交：本条一律以 `refund-applications/` 开头，那两条都不是。
  // ⚠️ `refunds` 与 `refund-applications` 只差一个连字符 —— 这条断言就是在盯那个连字符。
  for (const path of [
    'trade/refunds/capabilities',
    'trade/refunds/discrepancies',
    `trade/refunds/${ID}`,
    `trade/refunds/${ID}/dispatch`,
    `trade/refunds/${ID}/verifications`,
    'trade/rma-authorizations/capabilities',
    `trade/rma-authorizations/${ID}`,
  ]) {
    const response = await GET(request(path), {
      params: Promise.resolve({ path: path.split('/') }),
    });
    expect(response.status, `${path} 被本模块的正则误伤了`).toBe(200);
  }
});
