import { afterEach, expect, it, vi } from 'vitest';
import { NextRequest } from 'next/server';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { GET, POST } from './[...path]/route';

/**
 * 11.1.1.2 售后单详情的 api-proxy 白名单**双向**断言（**按模块一份**，与同目录
 * `aftersales-allowlist.test.ts` / `fulfillment-monitor-allowlist.test.ts` 同一手法）。
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
 *   <li><b>拒绝</b>：刻意不放行的形状仍然 404。未交付的下游子资源
 *       （refunds / returns / disputes / attribution）放行它会让界面以为功能已交付。</li>
 * </ul>
 *
 * <p>⚠️ **11.1.1.1 那条测试里对 `cases/{uuid}` 断言 404 的前提是「11.1.1.2 未交付」**，
 *   前提已经不成立（那里已随本模块交付改为断言放行）。本文件是它之后的**独立**一份：
 *   两个模块的白名单形状互不相交，各自钉住自己那批路径。
 */

// 相对**本文件**定位：vitest 从仓库根或 apps/web 跑时 cwd 都不同，用 cwd 会静默读空。
const SOURCE = readFileSync(
  fileURLToPath(new URL('../../../components/aftersales/aftersales-case-detail-model.ts', import.meta.url)),
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
  const base = SOURCE.match(/const base = '([^']+)'/)?.[1] ?? 'trade/aftersales';
  const found = new Set<string>();
  for (const match of SOURCE.matchAll(/`([^`]*)`/g)) {
    const raw = match[1];
    if (!raw || !raw.includes('${base}')) continue;
    const cleaned = raw
      .replace(/\$\{base\}/g, base)
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
    const response = await GET(request(path), {
      params: Promise.resolve({ path: path.split('/') }),
    });
    expect(response.status, `${path} 未被放行（白名单漏登记 = 浏览器里静默 404）`).toBe(200);
  }
  expect(sites).toEqual(
    expect.arrayContaining([
      'trade/aftersales/case-detail/capabilities',
      `trade/aftersales/cases/${ID}`,
      `trade/aftersales/cases/${ID}/timeline`,
    ]),
  );
});

it('写形状（POST）被放行，且真的转发到 8085', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);

  for (const path of [
    `trade/aftersales/cases/${ID}/notes`,
    `trade/aftersales/cases/${ID}/messages`,
    `trade/aftersales/cases/${ID}/attachments`,
    `trade/aftersales/cases/${ID}/lines`,
  ]) {
    const response = await POST(request(path, 'POST'), {
      params: Promise.resolve({ path: path.split('/') }),
    });
    expect(response.status, `POST ${path} 未被放行`).toBe(200);
  }
  expect(fetcher).toHaveBeenCalledWith(
    expect.stringContaining(':8085/trade/aftersales/cases/'),
    expect.objectContaining({ method: 'POST' }),
  );
});

it('刻意不放行的形状仍然被拒 —— 放行后端不存在的映射比 404 更糟', async () => {
  const fetcher = vi.fn();
  vi.stubGlobal('fetch', fetcher);
  for (const path of [
    // 11.2 / 11.3 / 11.4 均未交付：放行会让界面以为「退款与退货关联」已经接通。
    `trade/aftersales/cases/${ID}/refunds`,
    `trade/aftersales/cases/${ID}/returns`,
    `trade/aftersales/cases/${ID}/disputes`,
    // 11.1.2.2 商品问题归因未交付
    `trade/aftersales/cases/${ID}/attribution`,
    // 单字符不是 uuid：`[0-9a-f-]+` 会把它放行，而症状是有人 GET
    // .../cases/c 会拿到一个「售后单 id 是 c」的请求打到后端。
    'trade/aftersales/cases/c',
    // 11.1.1.1 的批量形状不属于本模块（仍归那条正则放行，这里只验证不误伤别的）
    'trade/aftersales/case-detail',
  ]) {
    const response = await GET(request(path), {
      params: Promise.resolve({ path: path.split('/') }),
    });
    expect(response.status, `${path} 被放行了，但它在后端不存在`).toBe(404);
  }
  expect(fetcher).not.toHaveBeenCalled();
});

it('11.1.1.1 自己的形状没有被本模块的正则吃走', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);
  // 本条元素排在 11.1.1.1 那条**之前**被匹配，形状互不相交是它能并排的前提：
  // `cases`（裸）与 `cases/bulk` 必须仍然放行（它们归 11.1.1.1 的正则）。
  for (const path of ['trade/aftersales/capabilities', 'trade/aftersales/filters', 'trade/aftersales/cases']) {
    const response = await GET(request(path), {
      params: Promise.resolve({ path: path.split('/') }),
    });
    expect(response.status, `${path} 被本模块的正则误伤了`).toBe(200);
  }
});
