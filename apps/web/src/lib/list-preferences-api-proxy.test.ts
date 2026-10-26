import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { describe, expect, it } from 'vitest';

/**
 * 1.4.1.2 列表与表格偏好的 api-proxy 白名单回归。
 *
 * <p>白名单**只在浏览器端生效**（`pnpm test` 直连服务层，永远测不出代理 404），
 * 所以漏登记一条 = 整个列表页的偏好在浏览器里存不进去，而 CI 全绿。
 *
 * <p>这里把「模型里真实写的调用路径」与「api-proxy 里的正则」放在一起对撞：
 *
 * <ol>
 *   <li>从 {@code api-proxy.ts} 源码里**抽出 identity 的全部白名单正则**（不改它的导出）；</li>
 *   <li>从模型源码里**反向抽取所有 {@code identity/user-preferences} 字面量** ——
 *       不是手抄一份路径清单，而是从真实代码里挖，路径改了这里就会红；</li>
 *   <li>逐条断言应放行的放行、不该放行的（多一层子路径、大写、含空格）不放行。</li>
 * </ol>
 */

const IDENTITY_PREFIX = 'identity/';
const MODEL = './list-preferences-model.ts';

/**
 * 从 api-proxy.ts 抽出 identity 服务的**全部**白名单正则（同一前缀可能有多条元素）。
 *
 * <p>为什么必须收全部：`api-proxy.ts` 是并行车道共同的**累积型**文件，
 * 同一个 prefix 出现多条元素是合法且已经发生的。运行时 {@code resolve()} 逐条 fallthrough：
 * 前缀不匹配就 continue，正则不匹配也继续试下一条，所以后登记的那条不会失效，两者构成并集。
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
    const start = block.indexOf('/^');
    if (start < 0) {
      throw new Error(`未能从 api-proxy.ts 的 ${IDENTITY_PREFIX} 元素里定位白名单正则起点`);
    }
    let literal: string | undefined;
    for (let j = start + 1; j < block.length; j++) {
      const ch = block[j];
      if (ch === '\\') {
        j++;
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

/** 走 resolve() 的真实形状：剥掉 identity/ 前缀后**逐条 fallthrough**，任一条命中即放行。 */
function resolveOk(path: string): boolean {
  if (!path.startsWith(IDENTITY_PREFIX)) return false;
  const remainder = path.slice(IDENTITY_PREFIX.length);
  return ALLOWED.some((pattern) => pattern.test(remainder));
}

/** 把模板串里的插值换成真实页面 key，模拟浏览器实际发出的请求路径。 */
const PAGE_KEY = 'orders.pending-shipment';

describe('1.4.1.2 偏好调用点在 api-proxy 白名单里', () => {
  const sites = callSitesIn(MODEL);

  it('确实从模型里挖到了调用点（防止正则写坏后静默通过）', () => {
    expect(sites.length).toBeGreaterThan(0);
    expect(sites.every((s) => s.startsWith(IDENTITY_PREFIX))).toBe(true);
  });

  it('挖到的调用点覆盖本模块的四个形状', () => {
    const concrete = new Set(sites.map((s) => s.replace(/\$\{[^}]*\}/g, PAGE_KEY)));
    expect(concrete).toContain('identity/user-preferences');
    expect(concrete).toContain(`identity/user-preferences/${PAGE_KEY}`);
  });

  it('每条真实调用路径都应被放行', () => {
    for (const site of sites) {
      const path = site.replace(/\$\{[^}]*\}/g, PAGE_KEY);
      expect(resolveOk(path), `${path} 未登记`).toBe(true);
    }
  });
});

describe('1.4.1.2 偏好路径的具体形状', () => {
  it('集合与单页都放行', () => {
    expect(resolveOk('identity/user-preferences')).toBe(true);
    expect(resolveOk(`identity/user-preferences/${PAGE_KEY}`)).toBe(true);
    expect(resolveOk('identity/user-preferences/catalog.products')).toBe(true);
    expect(resolveOk('identity/user-preferences/inventory.risk')).toBe(true);
  });

  it('多一层子路径仍被拒绝：放行不该存在的路径比 404 更糟', () => {
    expect(resolveOk(`identity/user-preferences/${PAGE_KEY}/extra`)).toBe(false);
    expect(resolveOk(`identity/user-preferences/${PAGE_KEY}/reset`)).toBe(false);
  });

  it('页面 key 词表之外的一律拒绝', () => {
    expect(resolveOk('identity/user-preferences/Orders.Pending')).toBe(false);
    expect(resolveOk('identity/user-preferences/orders pending')).toBe(false);
    expect(resolveOk('identity/user-preferences/orders/pending')).toBe(false);
    expect(resolveOk('identity/user-preferences/-orders')).toBe(false);
  });

  it('查询串从不进入匹配：代理只拿 path，query 走 nextUrl.search', () => {
    expect(resolveOk(`identity/user-preferences?pageKey=${PAGE_KEY}`)).toBe(false);
    expect(resolveOk(`identity/user-preferences/${PAGE_KEY}`)).toBe(true);
  });

  it('不误伤其它车道已登记的 identity 形状（白名单是求并，不是二选一）', () => {
    expect(resolveOk('identity/tenants')).toBe(true);
    expect(resolveOk('identity/tenants/current')).toBe(true);
    expect(resolveOk('identity/global-search/recent')).toBe(true);
    expect(resolveOk('identity/roles')).toBe(true);
  });
});
