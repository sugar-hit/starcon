import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { describe, expect, it } from 'vitest';

/**
 * 9.1.1.2 订单详情（控制塔）调用点在 api-proxy 白名单里。
 *
 * <p><b>为什么这个测试必须存在</b>：前端调了但白名单没登记 = **浏览器端静默 404**，
 * 而 `typecheck` / `lint` / `vitest` 全绿。它是本车道唯一能提前发现这件事的地方。
 *
 * <p><b>两个方向都要断言</b>：
 *
 * <ul>
 *   <li><b>正向</b>：从模型源码<b>反向抽取</b>真实会发出的每一条路径，逐条验放行。
 *       不能只 grep 字面量 —— 模型把命名空间抽成了 {@code const base = '...'}，
 *       只搜完整字面量会得到 0 条，而「挖到 0 条」正是这套守卫最危险的假绿。</li>
 *   <li><b>反向</b>：未知形状<b>仍然必须被拒</b>。只断言放行的话，
 *       一个「全部放行」的正则也能让正向用例全绿 —— 而那等于把整个后端
 *       变成浏览器可直连的裸服务。</li>
 * </ul>
 *
 * <p><b>并且反向断言既有分支没被改掉</b>（9.1.2.2 / 9.1.1.1 / 9.3.2.2 / 6.4.1.2）：
 * 白名单是**联合追加**，取错一侧会静默删掉已交付路由，表现为页面 404
 * 而所有 CI 都是绿的。
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
 * <p>照 6.4.1.2 的既有写法：先取 {@code const base = '...'}，
 * 再把所有基于它的模板串后缀合成浏览器实际会发出的路径。
 */
function callSitesIn(file: string): string[] {
  const source = readFileSync(join(__dirname, file), 'utf8');
  const base = source.match(/const base = '([^']+)'/)?.[1];
  if (!base) throw new Error(`未能从 ${file} 里挖到 base 常量`);

  const found = new Set<string>();
  for (const m of source.matchAll(/`\$\{base\}([^`]*)`/g)) {
    const suffix = m[1];
    if (suffix === undefined) continue;
    // 模板串里的插值（${encodeURIComponent(id)}）统一换成真实 uuid。
    const path = (base + suffix).replace(/\$\{[^}]+\}/g, UUID);
    // 真实请求路径**永远不会**带尾斜杠。
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

describe('9.1.1.2 订单详情调用点在 api-proxy 白名单里', () => {
  it('正向：模型里发出的每一条路径都被放行', () => {
    const sites = callSitesIn('../components/orders/order-overview-model.ts');
    // 守卫本身的自检：真的挖到了东西（0 条会让本文件变成假绿）。
    // 本页面只调 2 个形状（/{id} 与 /capabilities）；
    // 第三个形状 fulfillment-reference 是给 warehouse 调的服务端端点，
    // 浏览器端不碰，所以**不在**这里的抽取结果里 ——
    // 它由下面「三层细节」那组直接按字面量断言。
    expect(sites.length).toBeGreaterThanOrEqual(2);
    for (const path of sites) {
      expect(resolveOk(path), `未放行：${path}`).toBe(true);
    }
  });

  it('反向：未知形状仍然被拒（否则「全部放行」的正则也能让正向全绿）', () => {
    // 注意：这里**不能**写 sales-orders 裸路径 —— 它是 9.1.2.2 已交付的
    // 集合读，patch 之前就已经放行（实测确认），拒绝它等于把别人已交付的
    // 路由当成 bug 钉死。
    const denied = [
      `trade/sales-orders/detail/${UUID}/bogus`,
      'trade/sales-orders/detail/submit',
      'trade/sales-orders/detail/bogus',
      // 尾斜杠：真实请求路径不会带，但白名单不该替它做归一化。
      'trade/sales-orders/detail/',
      // detail 下的写形状：本模块没有任何写端点（详情页四个操作入口全 available=false）。
      'trade/sales-orders/detail/review',
      'trade/sales-orders/detail/cancel',
    ];
    for (const path of denied) {
      expect(resolveOk(path), `不该放行：${path}`).toBe(false);
    }
  });

  it('不误伤：既有车道已交付的 trade 分支逐条仍然放行', () => {
    // 9.1.2.2 手工订单
    expect(resolveOk(`trade/sales-orders/${UUID}`)).toBe(true);
    expect(resolveOk(`trade/sales-orders/${UUID}/submit`)).toBe(true);
    expect(resolveOk('trade/sales-orders/capabilities')).toBe(true);
    expect(resolveOk('trade/sales-orders')).toBe(true);
    // 9.1.1.1 订单列表
    expect(resolveOk('trade/sales-orders/list')).toBe(true);
    expect(resolveOk('trade/sales-orders/list/capabilities')).toBe(true);
    expect(resolveOk('trade/sales-orders/list/filters')).toBe(true);
    // 9.3.2.2 履约路由
    expect(resolveOk('trade/fulfillment/routing/capabilities')).toBe(true);
    expect(resolveOk(`trade/fulfillment/routing/decisions/${UUID}/status`)).toBe(true);
    // 6.4.1.2 BOM 关联（嵌套最深的既有分支）
    expect(resolveOk(`trade/processing/orders/${UUID}/bom-binding/lines`)).toBe(true);
    expect(resolveOk(`trade/processing/orders/${UUID}/components/issue`)).toBe(true);
  });

  it('不误伤：裸路径仍然按既有注释刻意不放行', () => {
    // api-proxy.ts 里那行注释明写「裸路径 fulfillment/routing 与 processing
    // 刻意不放行：后端没有对应映射，放行它会让界面以为接口可用」。
    expect(resolveOk('trade/fulfillment/routing')).toBe(false);
    expect(resolveOk('trade/processing')).toBe(false);
  });

  it('三层细节：detail 段排在 sales-orders 与 sales-orders/list 之后但仍能命中', () => {
    // 正则的 alternation 是有序的：`sales-orders(?:\/...)?` 不会吃掉
    // `sales-orders/detail/...`（它要求斜杠后是 uuid 或 capabilities）。
    // 这条断言钉住「加进去的分支位置正确」，防止下一次有人把它插错位置
    // 导致 detail 全被前面的分支半截吃掉。
    expect(resolveOk(`trade/sales-orders/detail/${UUID}`)).toBe(true);
    expect(resolveOk('trade/sales-orders/detail/capabilities')).toBe(true);
    expect(resolveOk('trade/sales-orders/detail/fulfillment-reference')).toBe(true);
  });
});