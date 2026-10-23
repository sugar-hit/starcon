import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { describe, expect, it } from 'vitest';
import { WORKBENCH_LAYOUT_PAGE_KEY } from './workbench-layout-model';

/**
 * 1.4.1.1 工作台布局的 api-proxy 白名单回归。
 *
 * <p>白名单**只在浏览器端生效**（`pnpm test` 直连服务层，永远测不出代理 404），
 * 所以「端点存在」不等于「浏览器能调到」—— 漏登记一条 = 整个布局在浏览器里存不进去，
 * 而后端测试全绿。
 *
 * <p>🔴 **本模块刻意不改 {@code api-proxy.ts}**：1.4.1.2 新增的那条独立元素
 * {@code /^user\-preferences$|^user\-preferences\/[a-z0-9][a-z0-9._-]{0,159}$/}
 * 已经覆盖了**任何 slug 形状的页面 key**，包括本模块的 {@code dashboard.layout}。
 * 不碰这个多车道共用的累积型白名单，就不需要跑全量 allowlist 回归。
 *
 * <p>本测试把「模型里真实写的调用路径」与「api-proxy 里的正则」放在一起对撞：
 *
 * <ol>
 *   <li>从 {@code api-proxy.ts} 源码里**抽出 identity 的全部白名单正则**（不改它的导出）；</li>
 *   <li>从模型源码里**反向抽取所有 {@code identity/user-preferences} 字面量** ——
 *       不是手抄一份路径清单，而是从真实代码里挖，路径改了这里就会红；</li>
 *   <li>逐条断言应放行的放行、不该放行的（多一层子路径、大写、含空格）不放行。</li>
 * </ol>
 */

const IDENTITY_PREFIX = 'identity/';
const MODEL = './workbench-layout-model.ts';
const API_PROXY = join(__dirname, '../../lib/api-proxy.ts');

/**
 * 从 api-proxy.ts 抽出 identity 服务的**全部**白名单正则（同一前缀可能有多条元素）。
 *
 * <p>为什么必须收全部：{@code api-proxy.ts} 是并行车道共同的**累积型**文件，
 * 同一个 prefix 出现多条元素是合法且已经发生的。运行时 {@code resolve()} 逐条 fallthrough：
 * 前缀不匹配就 continue，正则不匹配也继续试下一条，所以后登记的那条不会失效，两者构成并集。
 */
function identityPatterns(): RegExp[] {
  const source = readFileSync(API_PROXY, 'utf8');
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

describe('1.4.1.1 布局调用点在 api-proxy 白名单里', () => {
  const sites = callSitesIn(MODEL);

  it('确实从模型里挖到了调用点（防止正则写坏后静默通过）', () => {
    expect(sites.length).toBeGreaterThan(0);
    expect(sites.every((s) => s.startsWith(IDENTITY_PREFIX))).toBe(true);
  });

  it('本模块用的页面 key 就是 dashboard.layout', () => {
    expect(WORKBENCH_LAYOUT_PAGE_KEY).toBe('dashboard.layout');
  });

  it('每条真实调用路径都应被放行', () => {
    for (const site of sites) {
      const path = site.replace(/\$\{[^}]*\}/g, WORKBENCH_LAYOUT_PAGE_KEY);
      expect(resolveOk(path), `${path} 未登记`).toBe(true);
    }
  });
});

describe('1.4.1.1 布局路径的具体形状', () => {
  it('本模块的路径已放行（复用 1.4.1.2 登记的那条 slug 形状）', () => {
    expect(resolveOk(`identity/user-preferences/${WORKBENCH_LAYOUT_PAGE_KEY}`)).toBe(true);
  });

  it('多一层子路径仍被拒绝：放行不该存在的路径比 404 更糟', () => {
    expect(resolveOk(`identity/user-preferences/${WORKBENCH_LAYOUT_PAGE_KEY}/extra`)).toBe(false);
    expect(resolveOk(`identity/user-preferences/${WORKBENCH_LAYOUT_PAGE_KEY}/reset`)).toBe(false);
  });

  it('页面 key 词表之外的一律拒绝', () => {
    expect(resolveOk('identity/user-preferences/Dashboard.Layout')).toBe(false);
    expect(resolveOk('identity/user-preferences/dashboard layout')).toBe(false);
    expect(resolveOk('identity/user-preferences/dashboard/layout')).toBe(false);
    expect(resolveOk('identity/user-preferences/-dashboard.layout')).toBe(false);
  });

  it('不误伤其它车道已登记的 identity 形状（白名单是求并，不是二选一）', () => {
    expect(resolveOk('identity/user-preferences')).toBe(true);
    expect(resolveOk('identity/tenants')).toBe(true);
    expect(resolveOk('identity/tenants/current')).toBe(true);
    expect(resolveOk('identity/roles')).toBe(true);
  });
});
