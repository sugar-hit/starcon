import { afterEach, expect, it, vi } from 'vitest';
import { NextRequest } from 'next/server';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { DELETE, GET, POST, PUT } from './[...path]/route';

/**
 * 6.4.2.1 质检单的 api-proxy 白名单**双向**断言。
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
 *   <li><b>拒绝</b>：未交付 / 不存在的形状仍然 404。放行不该存在的路径比 404 更糟 ——
 *       它会让界面以为「已经可用」。</li>
 * </ul>
 */

// 相对**本文件**定位：vitest 从仓库根或 apps/web 跑时 cwd 都不同，用 cwd 会静默读空。
const SOURCE = readFileSync(
  fileURLToPath(new URL('../../../components/purchase/quality-inspection-model.ts', import.meta.url)),
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
 * <p>模型里的端点既有字面量（{@code 'trade/quality-inspections/capabilities'}）也有模板串
 * （{@code `${base}/capabilities`，{@code base = 'trade/quality-inspections'}），
 * 两种都要抽 —— 只认字面量会把带 base 前缀的那一半漏成零条，
 * 然后「只放行了几个」也算通过。
 */
function realCallSites(): string[] {
  const base = 'trade/quality-inspections';
  const found = new Set<string>();
  // 模型里声明的 base 常量（形如 `const base = 'trade/quality-inspections'`）。
  const baseLiterals = [...SOURCE.matchAll(/const\s+base\w*\s*=\s*'([^']+)'/g)].map((m) => m[1]);
  for (const match of SOURCE.matchAll(/'([^']*)'/g)) {
    const raw = match[1];
    if (!raw || !raw.startsWith(base)) continue;
    found.add(raw);
  }
  for (const match of SOURCE.matchAll(/`([^`]*)`/g)) {
    const raw = match[1];
    if (!raw) continue;
    const cleaned = raw
      .replace(/\$\{base\}/g, base)
      // 路径段里的 id（`${encodeURIComponent(orderId)}`）展开成一个真 uuid。
      .replace(/\$\{encodeURIComponent\([^}]*\)\}/g, ID)
      // 查询串 / 片段（`${qualityInspectionQuery(params)}`、`${query}`）展开成**空串** ——
      // 它是 ?a=1 这种尾巴，不是路径的一段。
      .replace(/\$\{[A-Za-z0-9_.]*\([^}]*\)\}/g, '')
      .replace(/\$\{(?:query|qs|search)\}/g, '')
      .replace(/\$\{[^}]*\}/g, ID)
      .replace(/\?.*$/, '')
      // 浏览器侧地址带 `/api/` 前缀；代理的路由段之后才是服务命名空间。
      .replace(/^\/api\//, '');
    if (cleaned.includes('${')) continue;
    // 只收本模块自己的前缀（`${base}/...` 与 baseLiterals 展开出来的）。
    if (!cleaned.startsWith(base) && !baseLiterals.some((b) => cleaned.startsWith(`${b}/`))) {
      continue;
    }
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
      'trade/quality-inspections/capabilities',
      'trade/quality-inspections/options',
      'trade/quality-inspections/summary',
      'trade/quality-inspections/templates',
      `trade/quality-inspections/templates/${ID}`,
      `trade/quality-inspections/templates/${ID}/activate`,
      `trade/quality-inspections/templates/${ID}/retire`,
      `trade/quality-inspections/${ID}`,
      `trade/quality-inspections/${ID}/items`,
      `trade/quality-inspections/${ID}/concessions`,
      `trade/quality-inspections/${ID}/complete`,
      `trade/quality-inspections/${ID}/cancel`,
      `trade/quality-inspections/${ID}/export`,
    ]),
  );
});

it('写形状（POST / PUT）被放行，且真的转发到 8085', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);

  for (const path of [
    'trade/quality-inspections',
    'trade/quality-inspections/templates',
    `trade/quality-inspections/templates/${ID}/activate`,
    `trade/quality-inspections/templates/${ID}/retire`,
    `trade/quality-inspections/${ID}/items`,
    `trade/quality-inspections/${ID}/concessions`,
    `trade/quality-inspections/${ID}/complete`,
    `trade/quality-inspections/${ID}/cancel`,
  ]) {
    const response = await POST(request(path, 'POST'), {
      params: Promise.resolve({ path: path.split('/') }),
    });
    expect(response.status, `POST ${path} 未被放行`).toBe(200);
  }
  await PUT(request(`trade/quality-inspections/templates/${ID}`, 'PUT'), {
    params: Promise.resolve({ path: `trade/quality-inspections/templates/${ID}`.split('/') }),
  });

  expect(fetcher).toHaveBeenCalledWith(
    expect.stringContaining(':8085/trade/quality-inspections'),
    expect.objectContaining({ method: 'POST' }),
  );
});

it('未交付 / 不存在的形状仍然被拒 —— 放行不该存在的路径比 404 更糟', async () => {
  const fetcher = vi.fn();
  vi.stubGlobal('fetch', fetcher);
  for (const path of [
    // 🔴 不存在「让 trade 直接把货分流到库存待检 / 残次 bucket」的端点 ——
    //    warehouse 没有可用的授权写端点，放行它会让界面显示「已分流」而其实没有。
    `trade/quality-inspections/${ID}/dispatch`,
    `trade/quality-inspections/${ID}/disposition`,
    // 6.4.2.2 不良品处置未交付。
    `trade/quality-inspections/${ID}/defectives`,
    // 没有「按 id 取单条检验结论」的端点。
    `trade/quality-inspections/${ID}/items/${ID}`,
    // 让步记录只追加，没有按 id 寻址的形状。
    `trade/quality-inspections/${ID}/concessions/${ID}`,
    // 没有批量端点。
    'trade/quality-inspections/bulk',
    // 非 UUID 段不应被 uuid 分支吃掉。
    'trade/quality-inspections/not-a-uuid',
    // 模板之下只接 activate / retire 两个动作。
    `trade/quality-inspections/templates/${ID}/delete`,
    `trade/quality-inspections/templates/${ID}/purge`,
  ]) {
    const response = await GET(request(path), {
      params: Promise.resolve({ path: path.split('/') }),
    });
    expect(response.status, `${path} 不应被放行`).toBe(404);
  }
  expect(fetcher, '被拒的路径不得触发任何上游调用').not.toHaveBeenCalled();
});

/**
 * 判据取 **codegen 产物** {@code packages/api-contracts/src/trade.openapi.json}：
 * 那是后端真实端点的一份快照。白名单放行的形状必须与契约里的形状一一对应 ——
 * 白名单多放一个 = 浏览器里会转给一个不存在的端点。
 */
it('白名单放行的形状与后端契约一一对应（不多放、不少放）', () => {
  const spec = JSON.parse(
    readFileSync(
      fileURLToPath(
        new URL('../../../../../../packages/api-contracts/src/trade.openapi.json', import.meta.url),
      ),
      'utf8',
    ),
  ) as { paths: Record<string, Record<string, unknown>> };
  const contractPaths = Object.keys(spec.paths)
    .filter((p) => p.startsWith('/trade/quality-inspections'))
    .map((p) => p.replace('/trade/', 'trade/'))
    .sort();
  expect(contractPaths.length, '契约里一条 quality-inspections 路径都没有，判定本身失效了').toBeGreaterThan(
    0,
  );

  // 契约里有的形状，白名单必须放行。
  const allowed = new Set([
    'trade/quality-inspections',
    'trade/quality-inspections/capabilities',
    'trade/quality-inspections/options',
    'trade/quality-inspections/summary',
    'trade/quality-inspections/templates',
    'trade/quality-inspections/templates/{id}',
    'trade/quality-inspections/templates/{id}/activate',
    'trade/quality-inspections/templates/{id}/retire',
    'trade/quality-inspections/{id}',
    'trade/quality-inspections/{id}/items',
    'trade/quality-inspections/{id}/concessions',
    'trade/quality-inspections/{id}/complete',
    'trade/quality-inspections/{id}/cancel',
    'trade/quality-inspections/{id}/export',
  ]);
  expect(new Set(contractPaths)).toEqual(allowed);
});

/**
 * 本车道往 `services[]` 里**新增了一条 trade/ 元素**（不并既有那条的超长 alternation），
 * 所以必须钉住「既有 trade 模块的入口没有被本条吃掉」——
 * 同一前缀下多条元素，谁先命中取决于顺序，这里逐条反查既有模块的入口。
 */
it('既有 trade 模块的入口没有被本车道新增的元素误伤', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);
  for (const path of [
    // 6.2.1.3 采购计划
    'trade/purchase-plans/capabilities',
    'trade/purchase-plans/options',
    // 6.4.1.1 加工单
    'trade/processing/capabilities',
    'trade/processing/factories',
    // 6.3.2.2 到货跟踪
    'trade/purchase-arrivals/capabilities',
    // 9.3.2.2 履约路径决策
    'trade/fulfillment/routing/capabilities',
    // 11.x 退货质检（本模块的同名兄弟，路径前缀不同）
    'trade/return-quality/capabilities',
    'trade/return-quality/inspections',
  ]) {
    const response = await GET(request(path), {
      params: Promise.resolve({ path: path.split('/') }),
    });
    expect(response.status, `${path} 被本车道新增的白名单条目误伤了`).toBe(200);
  }
});
