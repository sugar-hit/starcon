import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { describe, expect, it } from 'vitest';

/**
 * 1.4.2.1 语言与时区的 api-proxy 白名单回归。
 *
 * <p>白名单**只在浏览器端生效**（`pnpm test` 直连服务层，永远测不出代理 404），
 * 所以漏登记一条 = 整个设置页在浏览器里存不进去，而 CI 全绿。
 *
 * <p>这里把「模型里真实写的调用路径」与「api-proxy 里的正则」放在一起对撞：
 *
 * <ol>
 *   <li>从 {@code api-proxy.ts} 源码里**抽出每个服务前缀的全部白名单正则**（不改它的导出）；</li>
 *   <li>从模型源码里**反向抽取所有 {@code identity/…} 与 {@code channel/…} 字面量** ——
 *       不是手抄一份路径清单，而是从真实代码里挖，路径改了这里就会红；</li>
 *   <li>逐条断言应放行的放行、不该放行的（多一层子路径、大写、含空格）不放行。</li>
 * </ol>
 *
 * <p><b>反向断言同样重要</b>：白名单是<b>累积求并</b>的，取错一侧会静默删掉别人已交付的
 * 路由（页面 404，而 typecheck / lint / vitest 全绿）。所以这里也断言其它车道已登记的
 * identity 形状仍然放行。
 */

const MODEL = './account-locale-model.ts';

interface Element {
  prefix: string;
  lit: string;
  re: RegExp;
}

/**
 * 从 api-proxy.ts 抽出**每个服务前缀的全部**白名单正则。
 *
 * <p>为什么必须收全部：`api-proxy.ts` 是并行车道共同的**累积型**文件，
 * 同一个 prefix 出现多条元素是合法且已经发生的。运行时 {@code resolve()} 逐条 fallthrough：
 * 前缀不匹配就 continue，正则不匹配也继续试下一条，所以后登记的那条不会失效，两者构成并集。
 */
function elements(): Element[] {
  const source = readFileSync(join(__dirname, 'api-proxy.ts'), 'utf8');
  const starts = [...source.matchAll(/^\s*prefix: '([^']+)'/gm)].map((m) => ({
    prefix: m[1] ?? '',
    at: m.index ?? 0,
  }));

  const out: Element[] = [];
  for (const [i, element] of starts.entries()) {
    const end = starts[i + 1]?.at ?? source.lastIndexOf('];');
    const block = source.slice(element.at, end);
    const start = block.indexOf('/^');
    if (start < 0) {
      throw new Error(`未能从 api-proxy.ts 的 ${element.prefix} 元素里定位白名单正则起点`);
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
      throw new Error(`未能从 api-proxy.ts 的 ${element.prefix} 元素里定位白名单正则终点`);
    }
    out.push({ prefix: element.prefix, lit: literal, re: new RegExp(literal) });
  }
  if (out.length === 0) {
    throw new Error('未能从 api-proxy.ts 里定位任何真实元素');
  }
  return out;
}

/** 从源码里挖出所有服务调用点字面量（模板串保留插值占位）。 */
function callSitesIn(file: string): string[] {
  const source = readFileSync(join(__dirname, file), 'utf8');
  const found = new Set<string>();
  for (const m of source.matchAll(/[`'"]((?:identity|channel)\/[^`'"]*)[`'"]/g)) {
    const raw = m[1];
    if (raw === undefined) continue;
    found.add(raw.trim());
  }
  return [...found].sort();
}

const ALL = elements();

/** 走 resolve() 的真实形状：剥掉服务前缀后**逐条 fallthrough**，任一条命中即放行。 */
function resolveOk(path: string): boolean {
  const owner = ALL.find((element) => path.startsWith(element.prefix));
  if (!owner) return false;
  const remainder = path.slice(owner.prefix.length);
  return ALL.filter((element) => element.prefix === owner.prefix).some((element) =>
    element.re.test(remainder),
  );
}

describe('1.4.2.1 语言与时区调用点在 api-proxy 白名单里', () => {
  const sites = callSitesIn(MODEL);

  it('确实从模型里挖到了调用点（防止正则写坏后静默通过）', () => {
    expect(sites.length).toBeGreaterThan(0);
  });

  it('挖到的调用点覆盖本模块用到的四个形状', () => {
    expect(sites).toContain('identity/user-locale');
    expect(sites).toContain('identity/user-locale/capabilities');
  });

  it('每条真实调用路径都应被放行', () => {
    for (const site of sites) {
      expect(resolveOk(site), `${site} 未登记`).toBe(true);
    }
  });

  it('平台站点时区清单走 channel 的既有端点（channel.registry.read 只读）', () => {
    expect(sites).toContain('channel/platforms');
    expect(resolveOk('channel/platforms')).toBe(true);
  });
});

describe('1.4.2.1 语言与时区路径的具体形状', () => {
  it('本体与能力声明都放行', () => {
    expect(resolveOk('identity/user-locale')).toBe(true);
    expect(resolveOk('identity/user-locale/capabilities')).toBe(true);
  });

  it('多一层子路径仍被拒绝：放行不该存在的路径比 404 更糟', () => {
    expect(resolveOk('identity/user-locale/history')).toBe(false);
    expect(resolveOk('identity/user-locale/capabilities/extra')).toBe(false);
  });

  it('偏好本体不归本模块管（走 1.4.1.2 的通用偏好端点）', () => {
    expect(resolveOk('identity/user-locale/preferences')).toBe(false);
  });

  it('大小写与非法字符一律拒绝', () => {
    expect(resolveOk('identity/User-Locale')).toBe(false);
    expect(resolveOk('identity/user locale')).toBe(false);
    expect(resolveOk('identity/user-locale/capabilities/../preferences')).toBe(false);
  });

  it('不误伤其它车道已登记的 identity 形状（白名单是求并，不是二选一）', () => {
    expect(resolveOk('identity/tenants')).toBe(true);
    expect(resolveOk('identity/tenants/current')).toBe(true);
    expect(resolveOk('identity/user-preferences')).toBe(true);
    expect(resolveOk('identity/user-preferences/orders.pending')).toBe(true);
    expect(resolveOk('identity/roles')).toBe(true);
    expect(resolveOk('identity/global-search/recent')).toBe(true);
  });

  it('本模块的正则不遮蔽 1.4.1.2 的 user-preferences（首 token 全不相同）', () => {
    const mine = ALL.filter((element) => element.prefix === 'identity/').filter((element) =>
      element.lit.includes('user\\-locale'),
    );
    expect(mine).toHaveLength(1);
    expect(mine[0]?.re.test('user-preferences')).toBe(false);
    expect(mine[0]?.re.test('user-preferences/orders.pending')).toBe(false);
  });
});
