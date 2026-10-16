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

/**
 * 从 api-proxy.ts 抽出 trade 服务的**全部**白名单正则（同一前缀可能有多条元素）。
 *
 * <p><b>为什么必须收全部、而不是只收第一条</b>：`api-proxy.ts` 是并行车道共同的
 * **累积型**文件，同一个 prefix 出现多条元素是**合法且已经发生**的
 * （`trade/` 现有 2 条、`channel/` 有 6 条）。运行时 {@code resolve()} 是逐条
 * fallthrough：前缀不匹配就 {@code continue}，正则不匹配也继续试下一条 ——
 * 所以**后登记的那条不会因为前面那条先存在而失效**，两者是并集关系。
 *
 * <p>只抽第一条会让守卫拿其中一条正则去断言**所有** trade 路径。9.3.1.2 合单合法地
 * 新增了一条 `prefix: 'trade/'`（`fulfillment/consolidation/...`）并把它插在既有那条
 * 大元素**之前**（该车道的刻意锚点约定），于是守卫抽到的是合单那条窄正则，
 * 拿它去判 `sales-orders/detail/...` 之类 ⇒ 假红；而在别的组合下，一条窄正则做**反向**
 * 判定同样不可信。这不是功能回归，是守卫与运行时语义不同构。
 *
 * <p><b>为什么锚在行首而不是裸子串</b>：`prefix: 'trade/'` 这个字面量同时也出现在
 * 解释「插入锚点固定在 `prefix: 'trade/'` 之前」的那些注释里 —— 那是有意的文档约定，
 * 每条新车道都会写。真实元素行以缩进的 {@code prefix:} 开头（注释行以 {@code //} 开头），
 * 所以这里按行首匹配元素属性本身。这样再加多少条解释锚点的注释都伤不到守卫。
 *
 * <p><b>为什么每条元素单独定界</b>：元素边界 = 下一个元素的 prefix 行（或数组结尾）。
 * 不定界的话，从本元素 {@code prefix:} 往后找第一个 {@code /^}，找到的是**下一条元素**的
 * 正则 —— 那正是上面那批假红的确切机制。定界之后「第 N 条元素抽错正则」不再可能。
 */
function tradePatterns(): RegExp[] {
  const source = readFileSync(join(__dirname, 'api-proxy.ts'), 'utf8');
  // 所有真实元素的 prefix 行，按出现顺序。注释行以 // 开头，匹配不到这一条。
  const starts = [...source.matchAll(/^\s*prefix: '([^']+)'/gm)].map((m) => ({
    prefix: m[1] ?? '',
    at: m.index ?? 0,
  }));

  const out: RegExp[] = [];
  for (const [i, element] of starts.entries()) {
    if (element.prefix !== TRADE_PREFIX) continue;
    const end = starts[i + 1]?.at ?? source.lastIndexOf('];');
    const block = source.slice(element.at, end);
    // `pattern:` 与正则字面量之间可能夹着 `//` 注释行，所以不能用一条正则去跨行捞 ——
    // 改为定位首尾：找到 `/^` 起点，再从那里扫描「未转义的 `/`」，直到后跟逗号为止。
    const start = block.indexOf('/^');
    if (start < 0) {
      throw new Error(`未能从 api-proxy.ts 的 ${TRADE_PREFIX} 元素里定位白名单正则起点`);
    }
    let literal: string | undefined;
    for (let j = start + 1; j < block.length; j++) {
      const ch = block[j];
      if (ch === '\\') {
        j++; // 转义字符：跳过，不当作字面量结束
        continue;
      }
      if (ch === '/' && block[j + 1] === ',') {
        literal = block.slice(start + 1, j);
        break;
      }
    }
    if (literal === undefined) {
      throw new Error(`未能从 api-proxy.ts 的 ${TRADE_PREFIX} 元素里定位白名单正则终点`);
    }
    out.push(new RegExp(literal));
  }
  if (out.length === 0) {
    throw new Error(`未能从 api-proxy.ts 里定位任何真实元素 prefix: '${TRADE_PREFIX}'`);
  }
  return out;
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

const ALLOWED = tradePatterns();
const UUID = '0192f3c4-5d6e-7a8b-9c0d-1e2f3a4b5c6d';

/**
 * 走 resolve() 的真实形状：剥掉 trade/ 前缀后**逐条 fallthrough**，任一条命中即放行。
 *
 * <p>这与 api-proxy.ts 的 resolve() 同构（任一元素的 pattern 命中即放行），
 * 所以反向用例同样有效：返回 false 就意味着**全部** trade 元素都不匹配，
 * 而不是一个可能来自别的元素的窄正则碰巧没匹配上。
 */
function resolveOk(path: string): boolean {
  if (!path.startsWith(TRADE_PREFIX)) return false;
  const remainder = path.slice(TRADE_PREFIX.length);
  return ALLOWED.some((pattern) => pattern.test(remainder));
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
