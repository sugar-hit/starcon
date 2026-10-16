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
 * 拿它去判 `processing/orders/.../bom-binding` 之类 ⇒ 假红；而在别的组合下，
 * 一条窄正则做**反向**判定同样不可信。这不是功能回归，是守卫与运行时语义不同构。
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
