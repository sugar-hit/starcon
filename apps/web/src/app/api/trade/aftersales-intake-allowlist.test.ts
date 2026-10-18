import { afterEach, expect, it, vi } from 'vitest';
import { NextRequest } from 'next/server';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { GET, POST, PUT } from './[...path]/route';

/**
 * 11.1.1.3 售后受理的 api-proxy 白名单**双向**断言（**按模块一份**，与同目录
 * `aftersales-allowlist.test.ts` / `aftersales-detail-allowlist.test.ts` 同一手法）。
 *
 * <p>白名单只在浏览器端生效：`pnpm test` 直连服务层天然测不出漏登记 ——
 * 漏登记的路径在浏览器里静默 404，而 typecheck / lint / vitest 全绿。所以这里逐条钉住，
 * 而且判据是**真跑 proxy 断言 HTTP 状态码**（拦截 = 404，放行 = 转发），
 * 不是眼看正则、也不是自己再写一份正则去比。
 *
 * <p>两个方向都要断言：
 *
 * <ul>
 *   <li><b>放行</b>：从本模块自己的模型源码里<b>反向抽取真实调用点</b>再逐条断言被放行 ——
 *       写死一份清单只会验证「清单与白名单一致」，而真正会出事的是
 *       「源码加了新形状、白名单忘了登记」。</li>
 *   <li><b>拒绝</b>：刻意不放行的形状仍然 404。</li>
 * </ul>
 *
 * <p>⚠️ 本模块与 11.1.1.1 / 11.1.1.2 / 11.1.2.1 共用 `prefix: 'trade/'`，
 *   形状互斥靠的是「都带 `intake/` 这一段」—— 所以最后一条断言专门验**不误伤**那三条，
 *   哪天有人把本条的 alternation 写成 `aftersales\/(capabilities|…)` 就会误伤。
 */

// 相对**本文件**定位：vitest 从仓库根或 apps/web 跑时 cwd 都不同，用 cwd 会静默读空。
const SOURCE = readFileSync(
  fileURLToPath(
    new URL('../../../components/aftersales/aftersales-intake-model.ts', import.meta.url),
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

function realCallSites(): string[] {
  const base = SOURCE.match(/const base = '([^']+)'/)?.[1] ?? 'trade/aftersales/intake';
  const found = new Set<string>();
  for (const match of SOURCE.matchAll(/`([^`]*)`/g)) {
    const raw = match[1];
    if (!raw || !raw.includes('${base}')) continue;
    const cleaned = raw
      .replace(/\$\{base\}/g, base)
      .replace(/\$\{caseId\}/g, ID)
      .replace(/\$\{id\}/g, ID)
      .replace(/\$\{[^}]*\}/g, '')
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
    expect.arrayContaining([
      'trade/aftersales/intake/capabilities',
      'trade/aftersales/intake/verdicts',
      'trade/aftersales/intake/reply-sla',
      'trade/aftersales/intake/owner-roster',
    ]),
  );
});

it('写形状（POST / PUT）被放行，且真的转发到 8085', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);

  const posts = [
    'trade/aftersales/intake/cases',
    `trade/aftersales/intake/cases/${ID}/rejudge`,
    'trade/aftersales/intake/reply-sla',
    `trade/aftersales/intake/reply-sla/${ID}/retire`,
    'trade/aftersales/intake/owner-roster',
  ];
  for (const path of posts) {
    const response = await POST(request(path, 'POST'), {
      params: Promise.resolve({ path: path.split('/') }),
    });
    expect(response.status, `POST ${path} 未被放行`).toBe(200);
  }

  const put = await PUT(request(`trade/aftersales/intake/owner-roster/${ID}`, 'PUT'), {
    params: Promise.resolve({ path: `trade/aftersales/intake/owner-roster/${ID}`.split('/') }),
  });
  expect(put.status, `PUT owner-roster/${ID} 未被放行`).toBe(200);

  expect(fetcher).toHaveBeenCalledWith(
    expect.stringContaining(':8085/trade/aftersales/intake/'),
    expect.objectContaining({ method: 'POST' }),
  );
});

it('刻意不放行的形状仍然被拒 —— 放行后端不存在的映射比 404 更糟', async () => {
  const fetcher = vi.fn();
  vi.stubGlobal('fetch', fetcher);
  for (const path of [
    // 判定凭据 append-only：没有改写/删除端点，放行它等于告诉界面「可以擦掉判定」。
    `trade/aftersales/intake/verdicts/${ID}`,
    // 11.2 / 11.3 / 11.4 均未交付：放行会让界面以为退货/退款/纠纷已经接通。
    'trade/aftersales/intake/refunds',
    'trade/aftersales/intake/returns',
    // 11.1.2.2 商品问题归因未交付
    'trade/aftersales/intake/attribution',
    // 单字符不是 uuid：`[0-9a-f-]+` 会把它放行，症状是有人 GET
    // .../intake/verdicts/c 会拿到一个「受理判定 id 是 c」的请求打到后端。
    'trade/aftersales/intake/verdicts/c',
  ]) {
    const response = await GET(request(path), {
      params: Promise.resolve({ path: path.split('/') }),
    });
    expect(response.status, `${path} 被放行了，但它在后端不存在`).toBe(404);
  }
  expect(fetcher).not.toHaveBeenCalled();
});

it('同目录另外三条白名单的形状没有被本模块的正则吃走', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);
  // 形状互斥是四条白名单并排的前提：本条的每个分支都带 `intake/`，
  // 那三条都不带 —— 哪天有人把本条写成 `aftersales\/(capabilities|…)` 就会误伤它们。
  for (const path of [
    // 11.1.1.1
    'trade/aftersales/capabilities',
    'trade/aftersales/filters',
    'trade/aftersales/cases',
    // 11.1.1.2
    'trade/aftersales/case-detail/capabilities',
    `trade/aftersales/cases/${ID}`,
    // 11.1.2.1
    'trade/aftersales/reasons/capabilities',
    'trade/aftersales/reasons/tree',
  ]) {
    const response = await GET(request(path), {
      params: Promise.resolve({ path: path.split('/') }),
    });
    expect(response.status, `${path} 被本模块的正则误伤了`).toBe(200);
  }
});
