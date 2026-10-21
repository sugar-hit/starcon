import { afterEach, expect, it, vi } from 'vitest';
import { NextRequest } from 'next/server';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { DELETE, GET, POST, PUT } from './[...path]/route';

/**
 * 6.4.2.2 不良品处理的 api-proxy 白名单**双向**断言。
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
  fileURLToPath(
    new URL('../../../components/purchase/defective-goods-model.ts', import.meta.url),
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
 * <p>模型里的端点既有字面量也有模板串（{@code `${base}/capabilities`}，
 * {@code base = 'trade/defective-dispositions'}），两种都要抽 ——
 * 只认字面量会把带 base 前缀的那一半漏成零条，然后「只放行了几个」也算通过。
 */
function realCallSites(): string[] {
  const base = 'trade/defective-dispositions';
  const found = new Set<string>();
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
      // 路径段里的 id 展开成一个真 uuid。
      .replace(/\$\{encodeURIComponent\([^}]*\)\}/g, ID)
      // 查询串（`${defectiveQuery(params)}` / `${query}`）展开成**空串** ——
      // 它是 ?a=1 这种尾巴，不是路径的一段。
      .replace(/\$\{[A-Za-z0-9_.]*\([^}]*\)\}/g, '')
      .replace(/\$\{(?:query|qs|search)\}/g, '')
      .replace(/\$\{[^}]*\}/g, ID)
      .replace(/\?.*$/, '')
      // 浏览器侧地址带 `/api/` 前缀；代理的路由段之后才是服务命名空间。
      .replace(/^\/api\//, '');
    if (cleaned.includes('${')) continue;
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
      'trade/defective-dispositions/capabilities',
      'trade/defective-dispositions/options',
      'trade/defective-dispositions/summary',
      `trade/defective-dispositions/${ID}`,
      `trade/defective-dispositions/${ID}/decide`,
      `trade/defective-dispositions/${ID}/dispatch-inventory`,
      `trade/defective-dispositions/${ID}/complete`,
      `trade/defective-dispositions/${ID}/cancel`,
      `trade/defective-dispositions/${ID}/claims/${ID}/submit`,
      `trade/defective-dispositions/${ID}/claims/${ID}/agree`,
      `trade/defective-dispositions/${ID}/claims/${ID}/reject`,
      `trade/defective-dispositions/${ID}/claims/${ID}/withdraw`,
      `trade/defective-dispositions/${ID}/export`,
    ]),
  );
});

it('写形状（POST / PUT）被放行，且真的转发到 8085', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);

  for (const path of [
    'trade/defective-dispositions',
    `trade/defective-dispositions/${ID}/decide`,
    `trade/defective-dispositions/${ID}/dispatch-inventory`,
    `trade/defective-dispositions/${ID}/complete`,
    `trade/defective-dispositions/${ID}/cancel`,
    `trade/defective-dispositions/${ID}/claims/${ID}/submit`,
    `trade/defective-dispositions/${ID}/claims/${ID}/agree`,
    `trade/defective-dispositions/${ID}/claims/${ID}/reject`,
    `trade/defective-dispositions/${ID}/claims/${ID}/withdraw`,
  ]) {
    const response = await POST(request(path, 'POST'), {
      params: Promise.resolve({ path: path.split('/') }),
    });
    expect(response.status, `POST ${path} 未被放行`).toBe(200);
  }
  await PUT(request(`trade/defective-dispositions/${ID}`, 'PUT'), {
    params: Promise.resolve({ path: `trade/defective-dispositions/${ID}`.split('/') }),
  });

  expect(fetcher).toHaveBeenCalledWith(
    expect.stringContaining(':8085/trade/defective-dispositions'),
    expect.objectContaining({ method: 'POST' }),
  );
});

it('未交付 / 不存在的形状仍然被拒 —— 放行不该存在的路径比 404 更糟', async () => {
  const fetcher = vi.fn();
  vi.stubGlobal('fetch', fetcher);
  for (const path of [
    // 🔴 不存在「让 trade 直接改库存状态」的端点 ——
    //    warehouse 没有可用的授权写端点，本模块只落**指令**。
    //    放行它会让界面显示「已改库存」而其实没有。
    `trade/defective-dispositions/${ID}/inventory`,
    `trade/defective-dispositions/${ID}/instructions`,
    // 🔴 不存在「财务确认入账」的端点 ——
    //    12 财务（finance 服务）在本仓不存在，已生效冲减金额恒为 0。
    `trade/defective-dispositions/${ID}/claims/${ID}/confirm`,
    `trade/defective-dispositions/${ID}/claims/${ID}/settle`,
    // 明细 / 索赔随详情返回，没有独立的子资源端点。
    `trade/defective-dispositions/${ID}/lines`,
    `trade/defective-dispositions/${ID}/claims`,
    // 没有批量端点。
    'trade/defective-dispositions/bulk',
    // 非 UUID 段不应被 uuid 分支吃掉。
    'trade/defective-dispositions/not-a-uuid',
    // 索赔动作之下只有那四个。
    `trade/defective-dispositions/${ID}/claims/${ID}/delete`,
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
    .filter((p) => p.startsWith('/trade/defective-dispositions'))
    .map((p) => p.replace('/trade/', 'trade/'))
    .sort();
  expect(
    contractPaths.length,
    '契约里一条 defective-dispositions 路径都没有，判定本身失效了',
  ).toBeGreaterThan(0);

  // 契约里有的形状，白名单必须放行。
  const allowed = new Set([
    'trade/defective-dispositions',
    'trade/defective-dispositions/capabilities',
    'trade/defective-dispositions/options',
    'trade/defective-dispositions/summary',
    'trade/defective-dispositions/{id}',
    'trade/defective-dispositions/{id}/decide',
    'trade/defective-dispositions/{id}/dispatch-inventory',
    'trade/defective-dispositions/{id}/complete',
    'trade/defective-dispositions/{id}/cancel',
    'trade/defective-dispositions/{id}/claims/{claimId}/submit',
    'trade/defective-dispositions/{id}/claims/{claimId}/agree',
    'trade/defective-dispositions/{id}/claims/{claimId}/reject',
    'trade/defective-dispositions/{id}/claims/{claimId}/withdraw',
    'trade/defective-dispositions/{id}/export',
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
    // 6.4.2.1 质检单（本模块的上游）
    'trade/quality-inspections/capabilities',
    'trade/quality-inspections/options',
    'trade/quality-inspections/templates',
    // 6.2.1.3 采购计划
    'trade/purchase-plans/capabilities',
    // 6.4.1.1 加工单
    'trade/processing/capabilities',
    // 6.3.2.2 到货跟踪
    'trade/purchase-arrivals/capabilities',
    // 9.3.2.2 履约路径决策
    'trade/fulfillment/routing/capabilities',
    // 11.x 退货质检（同名兄弟，路径前缀不同）
    'trade/return-quality/capabilities',
    'trade/return-quality/inspections',
  ]) {
    const response = await GET(request(path), {
      params: Promise.resolve({ path: path.split('/') }),
    });
    expect(response.status, `${path} 被本车道新增的白名单条目误伤了`).toBe(200);
  }
});
