import { afterEach, expect, it, vi } from 'vitest';
import { NextRequest } from 'next/server';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { GET, POST } from './[...path]/route';

/**
 * 11.3.2.1 换货授权的 api-proxy 白名单**双向**断言
 * （手法与同目录 `refund-status-allowlist.test.ts` / `aftersales-allowlist.test.ts` 同一套）。
 *
 * <p>白名单只在浏览器端生效：`pnpm test` 直连服务层天然测不出漏登记 ——
 * 漏登记的路径在浏览器里静默 404，而 typecheck / lint / vitest 全绿。
 * 所以这里逐条钉住，判据是**真跑 proxy 断言 HTTP 状态码**（拦截 = 404，放行 = 转发）。
 *
 * <p>两个方向都要断言：
 *
 * <ul>
 *   <li><b>放行</b>：从本模块模型源码里<b>反向抽取真实调用点</b>再逐条断言被放行 ——
 *       写死一份清单只会验证「清单与白名单一致」，而真正会出事的是
 *       「源码加了新形状、白名单忘了登记」。</li>
 *   <li><b>拒绝</b>：刻意不放行的形状仍然 404。9.3.3.1 新出库与 11.2.2.2 旧货回寄
 *       **未交付**，放行它们会让界面以为功能已接通；换货授权行的 PUT / DELETE
 *       同样不放行 —— V162 里那些表是 append-only 的。</li>
 * </ul>
 */

// 相对**本文件**定位：vitest 从仓库根或 apps/web 跑时 cwd 都不同，用 cwd 会静默读空。
const SOURCE = readFileSync(
  fileURLToPath(
    new URL('../../../components/aftersales/exchange-authorization-model.ts', import.meta.url),
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

/** 反向抽取模型源码里真实存在的调用点（query 尾巴剥掉）。 */
function realCallSites(): string[] {
  const base = SOURCE.match(/const base = '([^']+)'/)?.[1] ?? 'trade/aftersales/exchanges';
  const found = new Set<string>();
  for (const match of SOURCE.matchAll(/`([^`]*)`/g)) {
    const raw = match[1];
    if (!raw || !raw.includes('${base}')) continue;
    const cleaned = raw
      .replace(/\$\{base\}/g, base)
      // **先**剥掉「函数调用型」插值（`${exchangeListQuery(params)}` 那种），
      // 再把 `${id}` 填成 UUID。顺序反了会少一个斜杠，看起来像漏登记其实是断言自己错了。
      .replace(/\$\{[a-zA-Z_$][\w$]*\([^)]*\)\}/g, '')
      .replace(/\$\{[^}]*$/, '')
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
      'trade/aftersales/exchanges',
      'trade/aftersales/exchanges/capabilities',
      'trade/aftersales/exchanges/filters',
      `trade/aftersales/exchanges/${ID}`,
    ]),
  );
});

it('写形状（POST）被放行：批准换货与撤销换货', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);

  for (const path of ['trade/aftersales/exchanges', `trade/aftersales/exchanges/${ID}/cancel`]) {
    const response = await POST(request(path, 'POST'), {
      params: Promise.resolve({ path: path.split('/') }),
    });
    expect(response.status, `POST ${path} 未被放行`).toBe(200);
  }
  expect(fetcher).toHaveBeenCalledWith(
    expect.stringContaining(':8085/trade/aftersales/exchanges/'),
    expect.objectContaining({ method: 'POST' }),
  );
});

it('刻意不放行的形状仍然被拒 —— 放行后端不存在的映射比 404 更糟', async () => {
  const fetcher = vi.fn();
  vi.stubGlobal('fetch', fetcher);

  for (const path of [
    // 新出库（9.3.3.1）未交付：放行会让界面以为出库单已经生成。
    `trade/aftersales/exchanges/${ID}/outbound`,
    // 旧货回寄（11.2.2.2）未交付。
    `trade/aftersales/exchanges/${ID}/return`,
    // 不存在的动作形状。
    `trade/aftersales/exchanges/${ID}/approve`,
  ]) {
    const response = await POST(request(path, 'POST'), {
      params: Promise.resolve({ path: path.split('/') }),
    });
    expect(response.status, `${path} 本该被拒`).toBe(404);
  }
  // 伪造的租户头与内部键不得被转发出去。
  expect(fetcher).not.toHaveBeenCalled();
});

it('详情 GET 被放行，而任何未登记的子动作仍被拒', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);

  const ok = await GET(request(`trade/aftersales/exchanges/${ID}`), {
    params: Promise.resolve({ path: `trade/aftersales/exchanges/${ID}`.split('/') }),
  });
  expect(ok.status, '详情 GET 必须放行').toBe(200);

  // 撤销是本模块唯一被放行的子动作：多一个动词都不该混进来。
  for (const path of [
    `trade/aftersales/exchanges/${ID}/cancel/force`,
    `trade/aftersales/exchanges/${ID}/reservations`,
  ]) {
    const response = await POST(request(path, 'POST'), {
      params: Promise.resolve({ path: path.split('/') }),
    });
    expect(response.status, `${path} 本该被拒`).toBe(404);
  }
});
