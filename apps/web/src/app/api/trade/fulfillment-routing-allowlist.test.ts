import { afterEach, expect, it, vi } from 'vitest';
import { NextRequest } from 'next/server';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { GET, POST } from './[...path]/route';

/**
 * 9.3.2.2 FBA 与自发货选择的 api-proxy 白名单**双向**断言。
 *
 * <p>白名单只在浏览器端生效：`pnpm test` 直连服务层天然测不出漏登记 ——
 * 漏登记的路径在浏览器里静默 404，而 typecheck / lint / vitest 全绿。所以这里逐条钉住，
 * 而且判据是**真跑 proxy 断言 HTTP 状态码**（拦截 = 404，放行 = 转发），
 * 不是眼看正则、也不是自己再写一份正则去比（这两种方法在本仓库已连续骗过 6 版）。
 *
 * <p>两个方向都要断言：
 *
 * <ul>
 *   <li><b>放行</b>：从本模块自己的模型源码里<b>反向抽取真实调用点</b>再逐条断言被放行 ——
 *       写死一份清单只会验证「清单与白名单一致」，而真正会出事的是
 *       「源码加了新形状、白名单忘了登记」。</li>
 *   <li><b>拒绝</b>：未交付的形状仍然 404。放行不该存在的路径比 404 更糟 ——
 *       它会让界面以为「已经可用」。</li>
 * </ul>
 *
 * <p>本车道还**新增**了 {@code app/api/trade/[...path]/route.ts} 这个浏览器出口
 * （此前 trade 服务在白名单里是空模式，没有任何 Next 路由接它）。
 * 所以这里除了路径白名单，还要钉住「trade 真的会转发到 8085」。
 */

// 相对**本文件**定位：vitest 从仓库根或 apps/web 跑时 cwd 都不同，用 cwd 会静默读空。
const SOURCE = readFileSync(
  fileURLToPath(
    new URL('../../../components/orders/fulfillment-routing-model.ts', import.meta.url),
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

/**
 * 从模型源码里反向抽出**真实存在**的路径。
 *
 * <p>模型里所有端点都是 `\`${base}/xxx\`` 拼出来的（base 是常量 'trade/fulfillment/routing'），
 * 所以抽取必须先把模板串**按 base 展开**再匹配 —— 只认字面量会把七条调用点全部漏成一条，
 * 然后「放行了三个」也算通过，那正是这类守卫最典型的失效方式。
 */
function realCallSites(): string[] {
  const base = SOURCE.match(/const base = '([^']+)'/)?.[1] ?? 'trade/fulfillment/routing';
  const found = new Set<string>();
  // 模板串：把 ${base} 换成真实前缀，${...id} 换成 uuid，${query(...)} 整段去掉。
  for (const match of SOURCE.matchAll(/`([^`]*)`/g)) {
    const raw = match[1];
    if (!raw || !raw.includes('${base}')) continue;
    const cleaned = raw
      .replace(/\$\{base\}/g, base)
      // ${query(...)} 是查询串，整段去掉；其余插值（通常是 id）换成 uuid。
      .replace(/\$\{query\([^)]*\)\}/g, '')
      .replace(/\$\{[^}]*\}/g, ID)
      .replace(/\?.*$/, '');
    if (cleaned.includes('${')) continue;
    found.add(cleaned);
  }
  // 字面量（没有模板拼接时也要能抽到）。
  // **裸 base 本身要排除**：它是前缀常量而不是端点，后端也没有裸路径这个映射
  // （下面有一条用例专门断言它被拒）。把它算成调用点会让这条守卫自己先红。
  for (const match of SOURCE.matchAll(/'([^']*)'/g)) {
    const raw = match[1];
    if (!raw || !raw.startsWith(base) || raw === base) continue;
    found.add(raw);
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
      'trade/fulfillment/routing/capabilities',
      'trade/fulfillment/routing/mcf-authorizations',
      'trade/fulfillment/routing/cost-profiles',
      'trade/fulfillment/routing/stock-snapshots',
      'trade/fulfillment/routing/decisions',
      `trade/fulfillment/routing/decisions/${ID}`,
      `trade/fulfillment/routing/decisions/${ID}/status`,
      'trade/fulfillment/routing/switches',
    ]),
  );
});

/**
 * POST 形状与 GET 同路径也要放行。
 *
 * <p>白名单**按路径**登记、不区分方法，所以同一个路径的 POST 只要 GET 放行了就必然放行；
 * 这里显式钉住，是为了让「评估与落决策这两个写动作」在将来被改坏时立刻变红。
 */
it('评估与落决策的 POST 形状被放行，且真的转发到 8085', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);

  for (const path of [
    'trade/fulfillment/routing/evaluations',
    'trade/fulfillment/routing/decisions',
    'trade/fulfillment/routing/mcf-authorizations',
    'trade/fulfillment/routing/cost-profiles',
    'trade/fulfillment/routing/stock-snapshots',
    `trade/fulfillment/routing/decisions/${ID}/status`,
  ]) {
    const segments = path.split('/');
    const response = await POST(request(path, 'POST'), {
      params: Promise.resolve({ path: segments }),
    });
    expect(response.status, `POST ${path} 未被放行`).toBe(200);
  }
  expect(fetcher).toHaveBeenCalledWith(
    expect.stringContaining(':8085/trade/fulfillment/routing/decisions'),
    expect.objectContaining({ method: 'POST' }),
  );
});

it('未交付的形状仍然被拒 —— 放行不该存在的路径比 404 更糟', async () => {
  const fetcher = vi.fn();
  vi.stubGlobal('fetch', fetcher);
  for (const path of [
    // 9.3.2.1 分仓规则、9.3.3.1 履约请求、9.1 订单都未交付。
    'trade/fulfillment/routing/allocation-policies',
    'trade/fulfillment/routing/fulfillment-requests',
    'trade/fulfillment/routing/orders',
    // 拼错的兄弟路径：交替写错就会互相吃掉，必须反向钉住。
    'trade/fulfillment/routing/mcf-authorisation',
    'trade/fulfillment/routing/switch',
    // 单段裸 id 之下多接一段未交付的子资源。
    `trade/fulfillment/routing/decisions/${ID}/events`,
    `trade/fulfillment/routing/decisions/${ID}/switches`,
    // 非 UUID 段不应被 [0-9a-f-]+ 分支吃掉。
    'trade/fulfillment/routing/decisions/not-a-uuid',
    // 三张输入表没有单条详情（列表 + POST 维护就是全部形状）。
    `trade/fulfillment/routing/mcf-authorizations/${ID}`,
    `trade/fulfillment/routing/cost-profiles/${ID}`,
    `trade/fulfillment/routing/stock-snapshots/${ID}`,
  ]) {
    const response = await GET(request(path), {
      params: Promise.resolve({ path: path.split('/') }),
    });
    expect(response.status, `${path} 不应被放行`).toBe(404);
  }
  expect(fetcher, '被拒的路径不得触发任何上游调用').not.toHaveBeenCalled();
});

/**
 * 根路径 `fulfillment/routing` 本身**必须被拒**：后端没有这个端点
 * （没有 Collection 映射在裸路径上），放行它会让界面以为「一条不存在的列表接口」可用。
 */
it('裸路径 fulfillment/routing 被拒：既不转发，也不冒充成上游错误', async () => {
  // 这个用例专门盯一个容易混淆的状态：代理对「被白名单拒绝」返回 404，
  // 对「放行了但上游连不上」返回 503。裸路径后端没有对应映射，
  // 所以它必须停在 404 这一侧 —— 若是 503，说明白名单把它放行了。
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);
  const path = 'trade/fulfillment/routing';
  const response = await GET(request(path), { params: Promise.resolve({ path: path.split('/') }) });
  expect(response.status, '裸路径不应被白名单放行').toBe(404);
  expect(fetcher, '被拒的路径不得触发任何上游调用').not.toHaveBeenCalled();
});

/**
 * trade 服务此前没有任何 Next 路由接它，本车道新增了出口 ——
 * 钉住「白名单放行了但路由没建」这种只在浏览器里表现为 503 的半成品状态。
 */
it('trade 出口真的转发到 8085（不是白名单放行后自己 404/503）', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);
  const path = 'trade/fulfillment/routing/decisions';
  const response = await GET(request(path), { params: Promise.resolve({ path: path.split('/') }) });
  expect(response.status).toBe(200);
  expect(fetcher).toHaveBeenCalledWith(
    expect.stringContaining(':8085/trade/fulfillment/routing/decisions'),
    expect.anything(),
  );
});
