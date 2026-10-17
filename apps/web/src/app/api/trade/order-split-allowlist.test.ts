import { afterEach, expect, it, vi } from 'vitest';
import { NextRequest } from 'next/server';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { GET, POST } from './[...path]/route';

/**
 * 9.3.1.1 拆单的 api-proxy 白名单**双向**断言。
 *
 * <p>白名单只在浏览器端生效：`pnpm test` 直连服务层天然测不出漏登记 ——
 * 漏登记的路径在浏览器里静默 404，而 typecheck / lint / vitest 全绿。所以这里逐条钉住，
 * 而且判据是**真跑 proxy 断言 HTTP 状态码**（拦截 = 404，放行 = 转发），
 * 不是眼看正则、也不是自己再写一份正则去比。
 *
 * <p><b>本车道是在两层结构里新加一组</b>：外层 `trade/` 段已有，
 * 内层已有 9.3.1.2 合单的 `fulfillment/consolidation` 组、9.3.2.1 的
 * `fulfillment/allocation` 组、9.3.2.2 的 `fulfillment/routing` 组。
 * 本模块新增的是 `fulfillment/split` 组，<b>没有并进任何既有组</b> ——
 * 所以除了「我的形状被放行」，还必须钉住
 * 「兄弟组仍然只放行它们自己的形状」与「裸路径仍然被拒」。
 */

// 相对**本文件**定位：vitest 从仓库根或 apps/web 跑时 cwd 都不同，用 cwd 会静默读空。
const SOURCE = readFileSync(
  fileURLToPath(new URL('../../../components/orders/order-split-model.ts', import.meta.url)),
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
      'X-Tenant-Id': 'forged',
      'X-Internal-Key': 'forged',
    },
    ...(method === 'GET' ? {} : { body: '{}' }),
  });
}

/**
 * 从模型源码里反向抽出**真实存在**的路径。
 *
 * <p>只写死一份清单会验证「清单与白名单一致」，而真正会出事的是
 * 「源码加了新形状、白名单忘了登记」—— 所以这里从源码反向抽。
 */
function realCallSites(): string[] {
  const base = SOURCE.match(/const base = '([^']+)'/)?.[1] ?? 'trade/fulfillment/split';
  const found = new Set<string>();
  for (const match of SOURCE.matchAll(/`([^`]*)`/g)) {
    const raw = match[1];
    if (!raw || !raw.includes('${base}')) continue;
    const cleaned = raw
      .replace(/\$\{base\}/g, base)
      .replace(/\$\{query\([^)]*\)\}/g, '')
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
  // 抽到的路径必须**真的**覆盖本模块的形状，否则「只放行了三个」也算通过。
  expect(sites).toEqual(
    expect.arrayContaining([
      'trade/fulfillment/split/capabilities',
      'trade/fulfillment/split/rule',
      'trade/fulfillment/split/warehouses',
      'trade/fulfillment/split/stock-snapshots',
      'trade/fulfillment/split/splits',
      `trade/fulfillment/split/splits/${ID}`,
      `trade/fulfillment/split/splits/${ID}/status`,
      'trade/fulfillment/split/reservation-commands',
      'trade/fulfillment/split/writeback-tasks',
      `trade/fulfillment/split/splits/${ID}/units/${ID}/reservation`,
      `trade/fulfillment/split/splits/${ID}/units/${ID}/writeback`,
    ]),
  );
});

it('预览与落拆单的 POST 形状被放行，且真的转发到 8085', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);

  for (const path of [
    'trade/fulfillment/split/preview',
    'trade/fulfillment/split/splits',
    'trade/fulfillment/split/rule',
    'trade/fulfillment/split/warehouses',
    'trade/fulfillment/split/stock-snapshots',
    `trade/fulfillment/split/splits/${ID}/status`,
    `trade/fulfillment/split/splits/${ID}/units/${ID}/reservation`,
    `trade/fulfillment/split/splits/${ID}/units/${ID}/writeback`,
  ]) {
    const segments = path.split('/');
    const response = await POST(request(path, 'POST'), {
      params: Promise.resolve({ path: segments }),
    });
    expect(response.status, `POST ${path} 未被放行`).toBe(200);
  }
  expect(fetcher).toHaveBeenCalledWith(
    expect.stringContaining(':8085/trade/fulfillment/split/preview'),
    expect.objectContaining({ method: 'POST' }),
  );
});

it('未交付的形状仍然被拒 —— 放行不该存在的路径比 404 更糟', async () => {
  const fetcher = vi.fn();
  vi.stubGlobal('fetch', fetcher);

  for (const path of [
    // 裸路径：后端没有对应映射，放行它会让界面以为「一条不存在的拆单列表接口」可用。
    'trade/fulfillment/split',
    // 本模块刻意没有 DELETE：拆单方案、候选依据与事件流都是履约凭据。
    'DELETE_ONLY_PLACEHOLDER',
    // 没实现的形状。
    'trade/fulfillment/split/auto-split',
    'trade/fulfillment/split/splits/bulk',
  ].filter((p) => p !== 'DELETE_ONLY_PLACEHOLDER')) {
    const response = await GET(request(path), {
      params: Promise.resolve({ path: path.split('/') }),
    });
    expect(response.status, `${path} 不该被放行`).toBe(404);
  }
  expect(fetcher).not.toHaveBeenCalled();
});

it('「本模块没有 DELETE」是后端契约事实，不是 proxy 白名单能拦的东西', () => {
  // ⚠️ proxy 的 pattern **只按路径匹配，不看 HTTP 方法** —— 同一个 path 用 DELETE 进来
  // 一样会被转发。所以「本模块不提供 DELETE」这条保证落在**后端没有 @DeleteMapping**
  // 上（那样请求会得到 405），而不是落在白名单正则上。
  // 这里断言的真正不变量是：前端模型与页面**从不**发起 DELETE ——
  // 拆单方案、候选依据与事件流都是履约凭据，删掉等于抹掉「当时为什么这么拆」的证据。
  expect(SOURCE).not.toMatch(/'DELETE'/);
  expect(SOURCE).not.toMatch(/api<[^>]*>\([^)]*,\s*'DELETE'/);
  expect(SOURCE).not.toMatch(/method:\s*'DELETE'/);
});

it('兄弟模块的组没有被本车道挤掉或污染', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);

  // 9.3.1.2 合单 / 9.3.2.1 分仓 / 9.3.2.2 履约路径仍各自放行自己的形状。
  for (const path of [
    'trade/fulfillment/consolidation/capabilities',
    'trade/fulfillment/consolidation/consolidations',
    'trade/fulfillment/allocation/capabilities',
    'trade/fulfillment/allocation/allocations',
    'trade/fulfillment/routing/capabilities',
    'trade/fulfillment/routing/decisions',
  ]) {
    const response = await GET(request(path), {
      params: Promise.resolve({ path: path.split('/') }),
    });
    expect(response.status, `兄弟模块的 ${path} 被本车道挤掉了`).toBe(200);
  }
});
