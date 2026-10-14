import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { describe, expect, it } from 'vitest';

/**
 * 6.4.1.2 BOM 关联的 api-proxy 白名单回归。
 *
 * <p><b>为什么必须有这个文件</b>：白名单**只在浏览器端生效**。
 * 前端调了一个没登记的路径，后端有端点、typecheck / lint / vitest 全绿，
 * 但浏览器里是**静默 404**。上一条车道就踩过这个坑（后端有端点、前端在调、白名单零条目）。
 *
 * <p>所以这里不复用「手抄一份路径清单」的做法，而是：
 *
 * <ol>
 *   <li>从 {@code api-proxy.ts} 源码里抽出 trade 的白名单正则（不动它的导出）；
 *   <li>从 {@code bom-binding-model.ts} 里**反向抽取所有 `trade/processing/...` 字面量** ——
 *       路径改了这里就会红，不会等到浏览器 404 才发现；
 *   <li>正向逐条放行 + <b>反向逐条拒绝</b>。
 * </ol>
 *
 * <p><b>反向断言不是形式主义</b>：一个「全部拒绝」的正则也能让正向用例全绿
 * （`resolve()` 返回 null → 代理返 404 → 断言「应放行」会红，但只断言正向的套件
 * 如果写成「不抛异常就算过」就会假绿）。两侧都要写。
 */

const TRADE_PREFIX = 'trade/';

/** 从 api-proxy.ts 抽出 trade 服务那条白名单正则。 */
function tradePattern(): RegExp {
  const source = readFileSync(join(__dirname, 'api-proxy.ts'), 'utf8');
  const block = source.slice(source.indexOf(`prefix: '${TRADE_PREFIX}'`));
  const start = block.indexOf('/^');
  if (start < 0) throw new Error('未能从 api-proxy.ts 里定位 trade 白名单正则起点');
  for (let i = start + 1; i < block.length; i++) {
    const ch = block[i];
    if (ch === '\\') {
      i++; // 转义字符：跳过，不当作字面量结束
      continue;
    }
    if (ch === '/' && block[i + 1] === ',') {
      return new RegExp(block.slice(start + 1, i));
    }
  }
  throw new Error('未能从 api-proxy.ts 里定位 trade 白名单正则终点');
}

/**
 * 从模型源码里挖出**真实会发出的请求形状**。
 *
 * <p>不能只 grep `trade/processing/...` 字面量：模型把命名空间抽成了
 * `const base = 'trade/processing'`，再拼 `${base}/orders/${id}/bom-binding`。
 * 只搜完整字面量会得到 0 条 —— 而「挖到 0 条」正是这套守卫最危险的假绿：
 * 它看起来通过了，其实什么都没检查。
 *
 * <p>所以这里**两边都挖**：先取 base 常量，再取所有引用它的模板串后缀，
 * 最后按 `base + 后缀` 合成浏览器实际会发出的路径。
 */
function callSitesIn(file: string): string[] {
  const source = readFileSync(join(__dirname, file), 'utf8');
  const base = source.match(/const base = '([^']+)'/)?.[1];
  if (!base) throw new Error(`未能从 ${file} 里挖到 base 常量`);

  const found = new Set<string>();
  for (const m of source.matchAll(/`\$\{base\}([^`]*)`/g)) {
    const suffix = m[1];
    if (suffix === undefined) continue;
    // 模板串里的插值（${encodeURIComponent(orderId)}）统一换成真实 uuid。
    const path = (base + suffix).replace(/\$\{[^}]+\}/g, UUID);
    // 真实请求路径由 `params.path.join('/')` 得到，**永远不会**带尾斜杠。
    found.add(path.endsWith('/') ? path.slice(0, -1) : path);
  }
  if (found.size === 0) throw new Error(`未能从 ${file} 里挖到任何基于 base 的调用路径`);
  return [...found];
}

const ALLOWED = tradePattern();
const UUID = '0192f3c4-5d6e-7a8b-9c0d-1e2f3a4b5c6d';

/** 走 resolve() 的真实形状：剥掉 trade/ 前缀后与正则对撞。 */
function resolveOk(path: string): boolean {
  if (!path.startsWith(TRADE_PREFIX)) return false;
  return ALLOWED.test(path.slice(TRADE_PREFIX.length));
}

describe('6.4.1.2 BOM 关联调用点在 api-proxy 白名单里', () => {
  const sites = callSitesIn('../components/purchase/bom-binding-model.ts');

  it('确实从模型里挖到了调用点（防止正则写坏后静默通过）', () => {
    expect(sites.length).toBeGreaterThan(0);
    expect(sites.every((s) => s.startsWith(`${TRADE_PREFIX}processing/`))).toBe(true);
  });

  it('每条真实调用路径都应被放行', () => {
    for (const path of sites) {
      expect(resolveOk(path), `${path} 未登记到 api-proxy 白名单`).toBe(true);
    }
  });
});

describe('6.4.1.2 的具体路径形状', () => {
  it('冻结与读绑定放行（同一形状，GET 与 POST 共用）', () => {
    expect(resolveOk(`trade/processing/orders/${UUID}/bom-binding`)).toBe(true);
  });

  it('只读展开树放行', () => {
    expect(resolveOk(`trade/processing/orders/${UUID}/bom-binding/lines`)).toBe(true);
  });

  it('反向：未知的 BOM 绑定动作仍然 404', () => {
    // 白名单是路径白名单而不是方法白名单：不存在 /refreeze、不存在 DELETE。
    // 冻结快照是不可逆事实，放行这两条等于让界面以为可以改写它。
    for (const bogus of ['refreeze', 'purge', 'delete', 'unfreeze', 'reset']) {
      expect(resolveOk(`trade/processing/orders/${UUID}/bom-binding/${bogus}`)).toBe(false);
    }
    expect(resolveOk(`trade/processing/orders/${UUID}/bom-binding`)).toBe(true);
  });

  it('反向：没有裸的 bom-bindings 列表（快照永远从属于一张加工单）', () => {
    // 后端刻意没有这个端点：没有「这张快照属于谁」的问题就不该有列表接口。
    // 放行它会让界面以为可用，点下去就是 404。
    expect(resolveOk('trade/processing/bom-bindings')).toBe(false);
    expect(resolveOk(`trade/processing/bom-bindings/${UUID}`)).toBe(false);
  });

  it('单段 orders/{id} 仍放行：它是 6.4.1.1 的加工单详情端点（GET /orders/{orderId}）', () => {
    // 这一条先写成反向断言（「orders/{uuid} 应 404」）并跑红 —— 核对 OpenAPI 后确认
    // **是我的断言写错了**：那个形状是 6.4.1.1 的真实端点（详情）。
    // 按纪律报告而不偷偷改松：改正方向是把它挪进正向集合，理由写在这里。
    expect(resolveOk(`trade/processing/orders/${UUID}`)).toBe(true);
  });

  it('不误伤 6.4.1.1 已登记的分支（累积型文件只加不改）', () => {
    // 这一条最关键：api-proxy.ts 是**累积型**文件，各车道都往同一行正则里加分支。
    // 合并时求并取错一侧，就会静悄悄丢掉别的车道的分支，而它们的前端页面照常 404。
    expect(resolveOk('trade/processing/capabilities')).toBe(true);
    expect(resolveOk('trade/processing/requirements')).toBe(true);
    expect(resolveOk('trade/processing/orders')).toBe(true);
    expect(resolveOk(`trade/processing/orders/${UUID}/status`)).toBe(true);
    expect(resolveOk(`trade/processing/orders/${UUID}/progress`)).toBe(true);
    expect(resolveOk(`trade/processing/orders/${UUID}/components/issue`)).toBe(true);
    expect(resolveOk('trade/processing/factories')).toBe(true);
    expect(resolveOk(`trade/processing/factories/${UUID}`)).toBe(true);
    // 9.3.2.2 履约路径
    expect(resolveOk('trade/fulfillment/routing/capabilities')).toBe(true);
    expect(resolveOk('trade/fulfillment/routing/decisions')).toBe(true);
    expect(resolveOk(`trade/fulfillment/routing/decisions/${UUID}/status`)).toBe(true);
    expect(resolveOk('trade/fulfillment/routing/switches')).toBe(true);
  });

  it('查询串从不进入匹配：代理只拿 path，query 走 nextUrl.search', () => {
    // `context.params.path.join('/')` 不含 `?`，所以浏览器永远不会发这种形状；
    // 如果哪天有人把整条 URL 塞进 path，这条断言会立刻红。
    expect(resolveOk(`trade/processing/orders/${UUID}/bom-binding?x=1`)).toBe(false);
    expect(resolveOk(`trade/processing/orders/${UUID}/bom-binding`)).toBe(true);
  });
});
