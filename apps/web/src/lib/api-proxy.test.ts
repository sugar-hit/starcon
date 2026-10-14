import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { describe, expect, it } from 'vitest';

/**
 * 8.2.1.1 收货单的 api-proxy 白名单回归。
 *
 * <p>白名单**只在浏览器端生效**（`pnpm test` 直连服务层，永远测不出代理 404），
 * 所以漏登记一条 = 整个页面在浏览器里 404，而 CI 全绿。这里把
 * 「组件里真实写的调用路径」与「api-proxy 里的正则」放在一起对撞：
 *
 * <ol>
 *   <li>从 {@code api-proxy.ts} 源码里**抽出 warehouse 的白名单正则**（不改它的导出）；
 *   <li>从组件与模型源码里**反向抽取所有 `warehouse/...` 字符串字面量** ——
 *       不是手抄一份路径清单，而是从真实代码里挖，路径改了这里就会红；
 *   <li>逐条断言应放行的放行、不该放行的（尤其是 DELETE）不放行。
 * </ol>
 */

const WAREHOUSE_PREFIX = 'warehouse/';

function warehousePattern(): RegExp {
  const source = readFileSync(join(__dirname, 'api-proxy.ts'), 'utf8');
  const block = source.slice(source.indexOf(`prefix: '${WAREHOUSE_PREFIX}'`));
  // `pattern:` 与正则字面量之间可能夹着 `//` 注释行，所以不能用一条正则去跨行捞 ——
  // 改为定位首尾：找到 `/^` 起点，再从那里扫描「未转义的 `/`」，直到后跟逗号为止。
  const start = block.indexOf('/^');
  if (start < 0) throw new Error('未能从 api-proxy.ts 里定位 warehouse 白名单正则起点');
  for (let i = start + 1; i < block.length; i++) {
    const ch = block[i];
    if (ch === '\\') {
      i++; // 转义字符：跳��，不当作字面量结束
      continue;
    }
    if (ch === '/' && block[i + 1] === ',') {
      return new RegExp(block.slice(start + 1, i));
    }
  }
  throw new Error('未能从 api-proxy.ts 里定位 warehouse 白名单正则终点');
}

/** 从源码里挖出所有 `warehouse/...` 字面量（模板串按静态前缀截断）。 */
function callSitesIn(file: string): string[] {
  const source = readFileSync(join(__dirname, file), 'utf8');
  const found = new Set<string>();
  for (const m of source.matchAll(/`(warehouse\/[^`]*)`|'(warehouse\/[^']*)'/g)) {
    const raw = m[1] ?? m[2];
    if (raw === undefined) continue;
    // 模板串：`warehouse/goods-receipts/${id}` 之类，取到第一个插值为止的静态前缀。
    const cut = raw.indexOf('${');
    const prefix = cut >= 0 ? raw.slice(0, cut) : raw;
    // 模板串截断后可能留下尾斜杠（`warehouse/goods-receipts/${q}` → `warehouse/goods-receipts/`）。
    // 真实请求路径由 `params.path.join('/')` 得到，**永远不会**带尾斜杠，
    // 所以这里归一化掉，否则测的是一个浏览器永远不会发出的形状。
    found.add(prefix.endsWith('/') ? prefix.slice(0, -1) : prefix);
  }
  return [...found];
}

const ALLOWED = warehousePattern();
const UUID = '3f1b7a52-0c4d-4a1e-9c3b-8d2e5f6a7b8c';

/** 走 resolve() 的真实形状：剥掉 warehouse/ 前缀后与正则对撞。 */
function resolveOk(path: string): boolean {
  if (!path.startsWith(WAREHOUSE_PREFIX)) return false;
  return ALLOWED.test(path.slice(WAREHOUSE_PREFIX.length));
}

describe('8.2.1.1 收货单调用点在 api-proxy 白名单里', () => {
  const sites = [
    ...callSitesIn('../components/warehouse/goods-receipt-model.ts'),
    ...callSitesIn('../components/warehouse/goods-receipt.tsx'),
  ];

  it('确实从组件里挖到了调用点（防止正则写坏后静默通过）', () => {
    expect(sites.length).toBeGreaterThan(0);
    expect(sites.every((s) => s.startsWith(WAREHOUSE_PREFIX))).toBe(true);
  });

  it('每条真实调用路径都应被放行', () => {
    for (const site of sites) {
      // 把 uuid 占位替换成真实形状后再判定，与浏览器实际发出的请求一致
      const concrete = site.replace(/\[0-9a-f-\]\+/g, UUID);
      expect(ALLOWED.test(concrete.replace(WAREHOUSE_PREFIX, '')), `${concrete} 未登记`).toBe(true);
    }
  });
});

describe('收货单的具体路径形状', () => {
  it('列表与 capabilities 放行', () => {
    expect(resolveOk('warehouse/goods-receipts')).toBe(true);
    expect(resolveOk('warehouse/goods-receipts/capabilities')).toBe(true);
  });

  it('详情与分录放行', () => {
    expect(resolveOk(`warehouse/goods-receipts/${UUID}`)).toBe(true);
    expect(resolveOk(`warehouse/goods-receipts/${UUID}/movements`)).toBe(true);
  });

  it('动作端点放行', () => {
    expect(resolveOk(`warehouse/goods-receipts/${UUID}/receive`)).toBe(true);
    expect(resolveOk(`warehouse/goods-receipts/${UUID}/close`)).toBe(true);
  });

  it('查询串从不进入匹配：代理只拿 path，query 走 nextUrl.search', () => {
    // `context.params.path.join('/')` 不含 `?`，所以 `?page=0` 这种形状浏览器永远不会发出来；
    // 如果哪天有人把整条 URL 塞进 path，这条断言会立刻红。
    expect(resolveOk('warehouse/goods-receipts?page=0&size=20')).toBe(false);
    expect(resolveOk('warehouse/goods-receipts')).toBe(true);
  });

  it('没有放行删除形状：收货单是账实凭证，删了等于这次收货从未发生', () => {
    // 白名单是路径白名单而不是方法白名单，这里钉住「不存在 DELETE 这一支」：
    // 未知的动作后缀一律不放行，所以即使有人发明 /{id}/purge 也会 404。
    expect(resolveOk(`warehouse/goods-receipts/${UUID}/purge`)).toBe(false);
    expect(resolveOk(`warehouse/goods-receipts/${UUID}/delete`)).toBe(false);
  });

  it('不误伤其他车道已登记的分支（累积型文件只加不改）', () => {
    expect(resolveOk('warehouse/inventory/transfers')).toBe(true);
    expect(resolveOk('warehouse/inventory/stocktake-variances/capabilities')).toBe(true);
    expect(resolveOk('warehouse/inventory/buckets')).toBe(true);
  });

  it('8.2.1.2 收货差异已交付：原本挂在拒绝清单上的路径转为放行', () => {
    // 这一条曾以「不存在的兄弟模块路径」的形式留在拒绝清单里 —— 那正是 8.2.1.2
    // 尚未交付时的状态。交付后必须把它改成正向断言，否则拒绝清单就变成一条死链断言：
    // 模块已经实现、页面却在浏览器里被代理 404，而 typecheck / lint / vitest 全绿。
    expect(resolveOk('warehouse/goods-receipt-discrepancies')).toBe(true);
    expect(resolveOk('warehouse/goods-receipt-discrepancies/capabilities')).toBe(true);
    expect(resolveOk(`warehouse/goods-receipt-discrepancies/${UUID}`)).toBe(true);
    for (const action of ['evidence', 'decide', 'claim', 'close']) {
      expect(resolveOk(`warehouse/goods-receipt-discrepancies/${UUID}/${action}`)).toBe(true);
    }
    expect(resolveOk(`warehouse/goods-receipt-discrepancies/${UUID}/claim/submit`)).toBe(true);
    expect(resolveOk(`warehouse/goods-receipt-discrepancies/${UUID}/claim/withdraw`)).toBe(true);
  });

  it('差异处理路径同样没有放行删除与未实现的动作形状', () => {
    expect(resolveOk(`warehouse/goods-receipt-discrepancies/${UUID}/purge`)).toBe(false);
    expect(resolveOk(`warehouse/goods-receipt-discrepancies/${UUID}/revise`)).toBe(false);
    expect(resolveOk(`warehouse/goods-receipt-discrepancies/${UUID}/claim/settle`)).toBe(false);
  });

  it('差异处理路径与收货单路径互不吃掉', () => {
    // 两组前缀首段不同但极其接近（goods-receipts vs goods-receipt-discrepancies），
    // 正则里少写一个 `|` 或多写一个 `?` 就会让其中一组静默失放行。
    expect(resolveOk('warehouse/goods-receipts')).toBe(true);
    expect(resolveOk(`warehouse/goods-receipts/${UUID}/receive`)).toBe(true);
    expect(resolveOk('warehouse/goods-receipt-discrepancies')).toBe(true);
  });
});

/**
 * 8.3.1.2 出库单详情的 api-proxy 白名单回归。
 *
 * <p>8.3.1.1 的白名单分支是 {@code outbound-orders(?:\/(?:capabilities|pick-requests|print|cancel)|\/[0-9a-f-]+\/transition)?}，
 * 本车道往里**追加**了详情与 detail-capabilities 两支。`api-proxy.ts` 是并行车道共同的
 * 累积型文件，所以这里既验证新增的两支放行，也验证**8.3.1.1 已登记的分支没被改掉** ——
 * 取错一侧会静默删掉已交付的路由，而 typecheck / lint / vitest 仍全绿（白名单只在浏览器端生效）。
 */
describe('8.3.1.2 出库单详情调用点在 api-proxy 白名单里', () => {
  const sites = callSitesIn('../components/inventory/outbound-order-detail-model.ts');

  it('确实从组件里挖到了调用点（防止正则写坏后静默通过）', () => {
    expect(sites.length).toBeGreaterThan(0);
    expect(sites.every((s) => s.startsWith(WAREHOUSE_PREFIX))).toBe(true);
  });

  it('每条真实调用路径都应被放行', () => {
    for (const site of sites) {
      expect(resolveOk(site), `${site} 未登记`).toBe(true);
    }
  });

  it('详情与详情能力声明放行', () => {
    expect(resolveOk(`warehouse/inventory/outbound-orders/${UUID}`)).toBe(true);
    expect(resolveOk('warehouse/inventory/outbound-orders/detail-capabilities')).toBe(true);
  });

  it('不误伤 8.3.1.1 已登记的分支（累积型文件只追加不改写）', () => {
    // 这四条是 8.3.1.1 交付的路由。追加详情时若把分支替换掉，
    // 表现是列表页 404 —— 而 typecheck / lint / vitest 全绿，因为白名单只在浏览器端生效。
    expect(resolveOk('warehouse/inventory/outbound-orders')).toBe(true);
    expect(resolveOk('warehouse/inventory/outbound-orders/capabilities')).toBe(true);
    expect(resolveOk('warehouse/inventory/outbound-orders/pick-requests')).toBe(true);
    expect(resolveOk('warehouse/inventory/outbound-orders/print')).toBe(true);
    expect(resolveOk('warehouse/inventory/outbound-orders/cancel')).toBe(true);
    expect(resolveOk(`warehouse/inventory/outbound-orders/${UUID}/transition`)).toBe(true);
  });

  it('没有放行删除形状：出库单是履约凭证，只能取消留痕', () => {
    expect(resolveOk(`warehouse/inventory/outbound-orders/${UUID}/purge`)).toBe(false);
    expect(resolveOk(`warehouse/inventory/outbound-orders/${UUID}/delete`)).toBe(false);
    // 未知动作后缀一律不放行，即使有人发明 /{id}/fulfil 也会 404。
    expect(resolveOk(`warehouse/inventory/outbound-orders/${UUID}/fulfil`)).toBe(false);
  });
});
