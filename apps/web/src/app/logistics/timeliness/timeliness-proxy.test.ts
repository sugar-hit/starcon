import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { describe, expect, it } from 'vitest';

/**
 * 10.6.2.1 时效分析的 api-proxy 白名单回归。
 *
 * <p>白名单**只在浏览器端生效**（`pnpm test` 直连服务层，永远测不出代理 404），
 * 所以漏登记一条 = 整个页面在浏览器里 404，而 CI 全绿。
 *
 * <p>🔴 本文件刻意**只抽 `channel/` 前缀下的全部白名单元素**并逐条 fallthrough
 * （与 api-proxy.ts 的 `resolve()` 同构）：`api-proxy.ts` 是并行车道共同的
 * **累积型**文件，同一个 prefix 出现多条元素是**合法且已经发生**的
 * （`channel/` 现在有 30+ 条）。只取第一条会既可能命中注释、也可能抽到下一条元素的正则。
 *
 * <p>本文件放在模块目录里而不是 `apps/web/src/lib/` 下：那里两个文件是**多车道共享**的，
 * 本车道往里加断言会与其它车道产生结构性冲突。
 */

const CHANNEL_PREFIX = 'channel/';

/**
 * 从 api-proxy.ts 抽出 channel 服务的**全部**白名单正则。
 *
 * <p>元素边界 = 下一个元素的 prefix 行（或数组结尾）；元素属性行以缩进的
 * {@code prefix:} 开头（注释行以 {@code //} 开头），所以按行首匹配元素本身。
 */
function channelPatterns(): RegExp[] {
  const source = readFileSync(join(__dirname, '../../../lib/api-proxy.ts'), 'utf8');
  const starts = [...source.matchAll(/^\s*prefix: '([^']+)'/gm)].map((m) => ({
    prefix: m[1] ?? '',
    at: m.index ?? 0,
  }));

  const out: RegExp[] = [];
  for (const [i, element] of starts.entries()) {
    if (element.prefix !== CHANNEL_PREFIX) continue;
    const end = starts[i + 1]?.at ?? source.lastIndexOf('];');
    const block = source.slice(element.at, end);
    const start = block.indexOf('/^');
    if (start < 0) {
      throw new Error(`未能从 api-proxy.ts 的 ${CHANNEL_PREFIX} 元素里定位白名单正则起点`);
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
      throw new Error(`未能从 api-proxy.ts 的 ${CHANNEL_PREFIX} 元素里定位白名单正则终点`);
    }
    out.push(new RegExp(literal));
  }
  if (out.length === 0) {
    throw new Error(`未能从 api-proxy.ts 里定位任何真实元素 prefix: '${CHANNEL_PREFIX}'`);
  }
  return out;
}

/** 走 resolve() 的真实形状：剥掉 channel/ 前缀后逐条 fallthrough。 */
function resolveOk(path: string): boolean {
  if (!path.startsWith(CHANNEL_PREFIX)) return false;
  const remainder = path.slice(CHANNEL_PREFIX.length);
  return channelPatterns().some((pattern) => pattern.test(remainder));
}

/**
 * 从模型源码里反向抽取所有真实请求路径。
 *
 * <p>模型里请求路径是拼出来的（{@code const BASE = 'channel/logistics-timeliness'}
 * 然后 {@code `${BASE}/capabilities`}），所以这里分两步：先取出 BASE 常量，
 * 再把每个 `${BASE}...` 模板还原成完整路径。
 *
 * <p>⚠️ <b>不能</b>把 BASE 常量本身当成一条调用路径：裸路径
 * {@code channel/logistics-timeliness} 在后端**不存在**（见下面的反向断言），
 * 把它算进「真实调用点」会让正向断言与反向断言互相打架。
 */
function callSitesIn(file: string): string[] {
  const source = readFileSync(join(__dirname, file), 'utf8');
  const baseMatch = source.match(/const BASE = '(channel\/[^']+)'/);
  const base = baseMatch?.[1];
  if (!base) throw new Error(`未能从 ${file} 里定位 BASE 常量`);

  const found = new Set<string>();
  for (const m of source.matchAll(/\$\{BASE\}([^`']*)/g)) {
    const suffix = m[1] ?? '';
    if (!suffix.startsWith('/')) {
      throw new Error(`${file} 里出现了没有子路径的 BASE 用法：${suffix}`);
    }
    // 查询串不进白名单匹配：代理只拿 path（`context.params.path.join('/')`），
    // query 走 `nextUrl.search`。所以这里把 `?…` 截掉，比对的是浏览器实际发出的 path。
    const query = suffix.indexOf('?');
    found.add(`${base}${query >= 0 ? suffix.slice(0, query) : suffix}`);
  }
  return [...found];
}

const UUID = '0192f3c4-5d6e-7a8b-9c0d-1e2f3a4b5c6d';

describe('10.6.2.1 时效分析的调用点在 api-proxy 白名单里', () => {
  const sites = callSitesIn('timeliness-model.ts');

  it('确实从模型里挖到了调用点（防止正则写坏后静默通过）', () => {
    expect(sites.length).toBeGreaterThan(0);
    expect(sites.every((s) => s.startsWith(CHANNEL_PREFIX))).toBe(true);
  });

  it('每条真实调用路径都应被放行', () => {
    for (const site of sites) {
      expect(resolveOk(site), `${site} 未登记`).toBe(true);
    }
  });
});

describe('10.6.2.1 时效分析的具体路径形状', () => {
  it('能力声明与概览放行', () => {
    expect(resolveOk('channel/logistics-timeliness/capabilities')).toBe(true);
    expect(resolveOk('channel/logistics-timeliness/overview')).toBe(true);
  });

  it('🔴 交叉报表与三个分析报表放行', () => {
    expect(resolveOk('channel/logistics-timeliness/reports/matrix')).toBe(true);
    expect(resolveOk('channel/logistics-timeliness/reports/monthly')).toBe(true);
    expect(resolveOk('channel/logistics-timeliness/reports/anomalies')).toBe(true);
    expect(resolveOk('channel/logistics-timeliness/reports/scores')).toBe(true);
    expect(resolveOk('channel/logistics-timeliness/reports/score-snapshots')).toBe(true);
  });

  it('逐票明细放行', () => {
    expect(resolveOk('channel/logistics-timeliness/subjects')).toBe(true);
  });

  it('查询串从不进入匹配：代理只拿 path，query 走 nextUrl.search', () => {
    expect(resolveOk('channel/logistics-timeliness/reports/matrix?from=2026-09-01')).toBe(false);
    expect(resolveOk('channel/logistics-timeliness/reports/matrix')).toBe(true);
  });

  it('🔴 裸路径不放行：后端没有裸集合读，放行它只会让人以为已交付', () => {
    expect(resolveOk('channel/logistics-timeliness')).toBe(false);
  });

  it('🔴 reports 下未列出的子路径不放行', () => {
    expect(resolveOk('channel/logistics-timeliness/reports')).toBe(false);
    expect(resolveOk('channel/logistics-timeliness/reports/anything')).toBe(false);
    expect(resolveOk('channel/logistics-timeliness/reports/snapshots')).toBe(false);
    expect(resolveOk('channel/logistics-timeliness/reports/costs')).toBe(false);
  });

  it('🔴 明细没有按 uuid 定位的形状 —— 明细是「窗口 + 筛选 + 分页」', () => {
    expect(resolveOk(`channel/logistics-timeliness/subjects/${UUID}`)).toBe(false);
    expect(resolveOk(`channel/logistics-timeliness/reports/scores/${UUID}`)).toBe(false);
  });

  it('不误伤同服务其它车道已登记的路由（累积型文件只加不改）', () => {
    // 10.3.1.1 轨迹查询
    expect(resolveOk('channel/logistics-tracking/capabilities')).toBe(true);
    expect(resolveOk('channel/logistics-tracking/subjects')).toBe(true);
    // 10.7.1.1 退货接收
    expect(resolveOk('channel/return-dispositions/capabilities')).toBe(true);
    // 10.1.1.2 渠道配置 / 10.1.1.3 时效与限制 / 10.1.2.1 运费计算器
    expect(resolveOk('channel/logistics-channels')).toBe(true);
    expect(resolveOk('channel/logistics-transit-limits/capabilities')).toBe(true);
    expect(resolveOk('channel/freight/capabilities')).toBe(true);
  });
});
