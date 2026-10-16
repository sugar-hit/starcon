import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { describe, expect, it } from 'vitest';

/**
 * 17.6.1.1 账簿与本位币的 api-proxy 白名单回归。
 *
 * <p>白名单**只在浏览器端生效**（`pnpm test` 直连服务层，永远测不出代理 404），
 * 所以漏登记一条 = 整个 `/settings/books` 页面在浏览器里 404，而 CI 全绿。
 * 这里把「组件里真实写的调用路径」与「api-proxy 里的正则」放在一起对撞：
 *
 * <ol>
 *   <li>从 {@code api-proxy.ts} 源码里**抽出 identity 的白名单正则**（不改它的导出）；
 *   <li>从组件源码里**反向抽取所有 `identity/...` 字符串字面量** —— 不是手抄一份路径清单，
 *       而是从真实代码里挖，路径改了这里就会红；
 *   <li>逐条断言应放行的放行、不该放行的（多一层子路径、未实现的兄弟形状）不放行。
 * </ol>
 *
 * <p>比对时把模板串里的 `${...}` 换成真实 UUID 再判定，与浏览器实际发出的形状一致 ——
 * 只截断到第一个插值会退化成 `identity/accounting-books`，等于没测到 fact-assignments。
 */

const IDENTITY_PREFIX = 'identity/';
const WORKSPACE = '../components/iam/ledger-book-workspace.tsx';
const PAGE = '../app/settings/books/page.tsx';

/**
 * 从 api-proxy.ts 抽出 identity 服务的**全部**白名单正则（同一前缀可能有多条元素）。
 *
 * <p><b>为什么必须收全部、而不是只收第一条</b>：`api-proxy.ts` 是并行车道共同的
 * **累积型**文件，同一个 prefix 出现多条元素是**合法且已经发生**的
 * （`channel/` 现有 6 条、`trade/` 现有 2 条）。运行时 {@code resolve()} 逐条
 * fallthrough：前缀不匹配就 {@code continue}，正则不匹配也继续试下一条，
 * 所以后登记的那条不会因为前面那条先存在而失效，两者构成并集。
 * 裸 {@code indexOf} 取第一条更糟：它既可能命中注释，也可能抽到下一条元素的正则 ——
 * `trade/` 上已经真实发生过后者，导致 11 个用例假红。
 *
 * <p><b>为什么锚在行首而不是裸子串</b>：`prefix: 'identity/'` 这个字面量同样会出现在
 * 解释「插入锚点固定在 … 之前」的注释里，那是有意的文档约定。真实元素行以缩进的
 * {@code prefix:} 开头（注释行以 {@code //} 开头），所以按行首匹配元素属性本身。
 *
 * <p><b>为什么每条元素单独定界</b>：元素边界 = 下一个元素的 prefix 行（或数组结尾）。
 * 不定界的话，从本元素 {@code prefix:} 往后找第一个 {@code /^}，找到的会是**下一条元素**的
 * 正则。本车道目前只有一条 identity 元素，所以这是**尚未触发**的隐患，
 * 而第二条 identity 元素落地的当天就会变成假红。
 */
function identityPatterns(): RegExp[] {
  const source = readFileSync(join(__dirname, 'api-proxy.ts'), 'utf8');
  // 所有真实元素的 prefix 行，按出现顺序。注释行以 // 开头，匹配不到这一条。
  const starts = [...source.matchAll(/^\s*prefix: '([^']+)'/gm)].map((m) => ({
    prefix: m[1] ?? '',
    at: m.index ?? 0,
  }));

  const out: RegExp[] = [];
  for (const [i, element] of starts.entries()) {
    if (element.prefix !== IDENTITY_PREFIX) continue;
    const end = starts[i + 1]?.at ?? source.lastIndexOf('];');
    const block = source.slice(element.at, end);
    // `pattern:` 与正则字面量之间可能夹着 `//` 注释行，所以不能用一条正则去跨行捞 ——
    // 改为定位首尾：找到 `/^` 起点，再从那里扫描「未转义的 `/`」，直到后跟逗号为止。
    const start = block.indexOf('/^');
    if (start < 0) {
      throw new Error(`未能从 api-proxy.ts 的 ${IDENTITY_PREFIX} 元素里定位白名单正则起点`);
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
      throw new Error(`未能从 api-proxy.ts 的 ${IDENTITY_PREFIX} 元素里定位白名单正则终点`);
    }
    out.push(new RegExp(literal));
  }
  if (out.length === 0) {
    throw new Error(`未能从 api-proxy.ts 里定位任何真实元素 prefix: '${IDENTITY_PREFIX}'`);
  }
  return out;
}

/** 从源码里挖出所有 `identity/...` 字面量（模板串保留插值占位）。 */
function callSitesIn(file: string): string[] {
  const source = readFileSync(join(__dirname, file), 'utf8');
  const found = new Set<string>();
  for (const m of source.matchAll(/`(identity\/[^`]*)`|'(identity\/[^']*)'/g)) {
    const raw = m[1] ?? m[2];
    if (raw === undefined) continue;
    found.add(raw.trim());
  }
  return [...found].sort();
}

const ALLOWED = identityPatterns();
const UUID = '3f1b7a52-0c4d-4a1e-9c3b-8d2e5f6a7b8c';

/**
 * 走 resolve() 的真实形状：剥掉 identity/ 前缀后**逐条 fallthrough**，任一条命中即放行。
 *
 * <p>这与 api-proxy.ts 的 resolve() 同构（任一元素的 pattern 命中即放行），
 * 所以反向用例同样有效：返回 false 就意味着**全部** identity 元素都不匹配，
 * 而不是一个可能来自别的元素的窄正则碰巧没匹配上。
 */
function resolveOk(path: string): boolean {
  if (!path.startsWith(IDENTITY_PREFIX)) return false;
  const remainder = path.slice(IDENTITY_PREFIX.length);
  return ALLOWED.some((pattern) => pattern.test(remainder));
}

/** 把模板串里的插值换成真实 UUID，模拟浏览器实际发出的请求路径。 */
function concrete(site: string): string {
  return site.replace(/\$\{[^}]*\}/g, UUID);
}

describe('17.6.1.1 账簿调用点在 api-proxy 白名单里', () => {
  const sites = callSitesIn(WORKSPACE);

  it('确实从组件里挖到了调用点（防止正则写坏后静默通过）', () => {
    // 抽取器坏掉时这里会返回 0 条，下一条断言就会变成「全部通过」的假绿
    expect(sites.length).toBeGreaterThan(0);
    expect(sites.every((s) => s.startsWith(IDENTITY_PREFIX))).toBe(true);
  });

  it('挖到的调用点覆盖本模块的六个端点，少一个就是漏了一条白名单', () => {
    // 断言**具体路径**而不是模板串原文：变量名（${id} / ${bookId} …）是实现细节，
    // 换个名字不该让测试红；但「六个端点一个都没少」必须被钉住。
    const concreteSites = new Set(sites.map(concrete));
    expect(concreteSites).toContain(`identity/legal-entities`);
    expect(concreteSites).toContain(`identity/legal-entities/${UUID}/ledger-books`);
    expect(concreteSites).toContain(`identity/legal-entities/${UUID}/default-book`);
    expect(concreteSites).toContain(`identity/accounting-books/${UUID}/fact-assignments`);
    expect(concreteSites).toContain(`identity/accounting-books/${UUID}/fact-assignments/${UUID}`);
  });

  it('每条真实调用路径都应被放行', () => {
    for (const site of sites) {
      const path = concrete(site);
      expect(resolveOk(path), `${path} 未登记`).toBe(true);
    }
  });
});

describe('17.6.1.1 三段新路径的具体形状', () => {
  it('法人下的账簿分组与默认账簿放行', () => {
    expect(resolveOk(`identity/legal-entities/${UUID}/ledger-books`)).toBe(true);
    expect(resolveOk(`identity/legal-entities/${UUID}/default-book`)).toBe(true);
  });

  it('账簿下的归属登记：集合与单条都放行', () => {
    expect(resolveOk(`identity/accounting-books/${UUID}/fact-assignments`)).toBe(true);
    expect(resolveOk(`identity/accounting-books/${UUID}/fact-assignments/${UUID}`)).toBe(true);
  });

  it('查询串从不进入匹配：代理只拿 path，query 走 nextUrl.search', () => {
    expect(resolveOk(`identity/accounting-books/${UUID}/fact-assignments?factSource=ORDER`)).toBe(
      false,
    );
    expect(resolveOk(`identity/accounting-books/${UUID}/fact-assignments`)).toBe(true);
  });

  it('多一层子路径仍被拒绝：放行不该存在的路径比 404 更糟', () => {
    // 这几条如果被放行，界面会以为后端已实现「归属明细 / 归属转移 / 换个账簿读分组」，
    // 而控制器里根本没有这些端点 —— 现场表现是代理把请求送出去、后端给一个更费解的 405。
    expect(resolveOk(`identity/legal-entities/${UUID}/ledger-books/extra`)).toBe(false);
    expect(resolveOk(`identity/legal-entities/${UUID}/default-books`)).toBe(false);
    expect(resolveOk(`identity/accounting-books/${UUID}/fact-assignment`)).toBe(false);
    expect(resolveOk(`identity/accounting-books/${UUID}/fact-assignments/${UUID}/extra`)).toBe(
      false,
    );
    expect(resolveOk(`identity/accounting-books/${UUID}/fact-assignments/${UUID}/transfer`)).toBe(
      false,
    );
  });

  it('不误放行不存在的兄弟模块路径', () => {
    expect(resolveOk('identity/ledger-books')).toBe(false);
    expect(resolveOk('identity/default-book')).toBe(false);
    expect(resolveOk('identity/fact-assignments')).toBe(false);
  });

  it('不误伤 17.1.2.2 已交付的形状（白名单是求并，不是二选一）', () => {
    expect(resolveOk('identity/legal-entities')).toBe(true);
    expect(resolveOk(`identity/legal-entities/${UUID}`)).toBe(true);
    expect(resolveOk(`identity/legal-entities/${UUID}/accounting-books`)).toBe(true);
    expect(resolveOk('identity/legal-entities/references')).toBe(true);
    expect(resolveOk('identity/accounting-books')).toBe(true);
    expect(resolveOk(`identity/accounting-books/${UUID}`)).toBe(true);
  });

  it('不误伤其它车道已登记的分支', () => {
    expect(resolveOk('identity/tenants')).toBe(true);
    expect(resolveOk('identity/tenants/current')).toBe(true);
    expect(resolveOk(`identity/hierarchy-authorizations/${UUID}/revoke`)).toBe(true);
    expect(resolveOk(`identity/offboarding/${UUID}/responsibility-items/${UUID}/transfer`)).toBe(
      true,
    );
    expect(resolveOk('identity/business-parameters/overrides')).toBe(true);
    expect(resolveOk(`identity/numbering/rules/${UUID}/next`)).toBe(true);
    expect(resolveOk(`identity/departments/${UUID}/leader/proxies`)).toBe(true);
    expect(resolveOk(`identity/onboardings/${UUID}/complete`)).toBe(true);
  });

  it('反向拒绝清单仍然成立（route.test.ts 里钉住的那些）', () => {
    expect(resolveOk(`identity/tenants/${UUID}`)).toBe(false);
    expect(resolveOk(`identity/hierarchy-authorizations/${UUID}/restore`)).toBe(false);
    expect(resolveOk(`identity/departments/${UUID}/leader/unknown`)).toBe(false);
    expect(resolveOk(`identity/offboarding/${UUID}/restore`)).toBe(false);
  });
});

/**
 * 本模块两个文件的调用点对撞：工作台组件 + `/settings/books` 路由页。
 *
 * <p>刻意**不**全量扫 `apps/web/src`：扫描会连带命中其它车道页面里尚未登记的路径
 * （17.5.2.1 的 `identity/dict-*`、17.3.2.1 的 `identity/role-assignment-scopes` 等），
 * 那些是别的模块的白名单缺口，不该由本车道改，也不该让本车道的测试红。
 * 全量扫描的结果作为发现项报给调度器，由对应车道自己收口。
 */
describe('本模块文件的全部 identity 调用点都在白名单里', () => {
  const moduleSites = callSitesIn(WORKSPACE);

  it('确实挖到了调用点，且路由页确实渲染的是同一个工作台', () => {
    // 路由页本身不直接调接口（它复用工作台组件，与 /org/accounting-books 同一写法），
    // 所以这里反过来钉住「页面 → 工作台」这一跳：页面漏渲染工作台就等于零调用点。
    expect(moduleSites.length).toBeGreaterThan(0);
    expect(new Set(moduleSites).size).toBeGreaterThanOrEqual(5);
    expect(readFileSync(join(__dirname, PAGE), 'utf8')).toContain('LedgerBookWorkspace');
  });

  it('每条调用路径都被放行', () => {
    const denied = [...new Set(moduleSites)]
      .map((site) => concrete(site))
      .filter((path) => !resolveOk(path));
    expect(denied).toEqual([]);
  });
});
