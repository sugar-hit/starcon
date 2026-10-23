import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { describe, expect, it } from 'vitest';

/**
 * 1.4.2.2 默认店铺与仓库的 api-proxy 白名单回归。
 *
 * <p>白名单**只在浏览器端生效**（`pnpm test` 直连服务层，永远测不出代理 404），
 * 所以「端点存在」不等于「浏览器能调到」—— 漏登记一条 = 整个页面在浏览器里调不到，
 * 而后端测试全绿。
 *
 * <p>🔴 <b>本车道改了多车道共用的 {@code api-proxy.ts}</b>，所以这个测试是
 * **双向**的：既断言本模块该放行的放行，也断言**别的车道已交付的形状没被弄丢**
 * （取错一侧会静默删掉别人已交付的路由，页面 404，而 typecheck / lint / vitest 全绿）。
 *
 * <p>本测试把「模型里真实写的调用路径」与「api-proxy 里的正则」放在一起对撞：
 *
 * <ol>
 *   <li>从 {@code api-proxy.ts} 源码里**抽出 identity 的全部白名单正则**（不改它的导出）；</li>
 *   <li>从模型源码里**反向抽取所有 {@code identity/...} 字面量** ——
 *       不是手抄一份路径清单，而是从真实代码里挖，路径改了这里就会红；</li>
 *   <li>逐条断言应放行的放行、不该放行的（多一层子路径、按 id 取单条）不放行；</li>
 *   <li>断言**其它车道**在 identity 前缀下已登记的形状仍然放行。</li>
 * </ol>
 */

const IDENTITY_PREFIX = 'identity/';
const MODEL = './default-context-model.ts';
const API_PROXY = join(__dirname, '../../../lib/api-proxy.ts');

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

describe('1.4.2.2 默认上下文的调用点在 api-proxy 白名单里', () => {
  const sites = callSitesIn(MODEL);

  it('确实从模型里挖到了调用点（防止正则写坏后静默通过）', () => {
    expect(sites.length).toBeGreaterThan(0);
    expect(sites.every((s) => s.startsWith(IDENTITY_PREFIX))).toBe(true);
  });

  it('每条真实调用路径都应被放行', () => {
    for (const site of sites) {
      const path = site.replace(/\$\{[^}]*\}/g, '');
      expect(resolveOk(path), `${path} 未登记`).toBe(true);
    }
  });
});

describe('1.4.2.2 默认上下文路径的具体形状', () => {
  it('本模块真实存在的三个形状都放行', () => {
    expect(resolveOk('identity/default-context')).toBe(true);
    expect(resolveOk('identity/default-context/options')).toBe(true);
    expect(resolveOk('identity/default-context/capabilities')).toBe(true);
  });

  it('后端不存在的形状不放行：放行比 404 更糟', () => {
    // 默认上下文是**单数**的一份，没有按 id 取单条的端点
    expect(resolveOk('identity/default-context/11111111-1111-1111-1111-111111111111')).toBe(false);
    // 候选集由 options 一次给全，不另开按维度分的路径
    expect(resolveOk('identity/default-context/shops')).toBe(false);
    expect(resolveOk('identity/default-context/legal-entities')).toBe(false);
    expect(resolveOk('identity/default-context/warehouses')).toBe(false);
    // 任意其它子路径
    expect(resolveOk('identity/default-context/extra')).toBe(false);
  });

  it('不误伤其它车道已登记的 identity 形状（白名单是求并，不是二选一）', () => {
    expect(resolveOk('identity/user-preferences')).toBe(true);
    expect(resolveOk('identity/user-preferences/dashboard.layout')).toBe(true);
    expect(resolveOk('identity/tenants')).toBe(true);
    expect(resolveOk('identity/tenants/current')).toBe(true);
    expect(resolveOk('identity/roles')).toBe(true);
    expect(resolveOk('identity/shortcut-templates')).toBe(true);
  });
});
