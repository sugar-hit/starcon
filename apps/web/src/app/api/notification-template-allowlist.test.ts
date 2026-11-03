import { describe, expect, it } from 'vitest';
import {
  entriesBySource,
  matchingEntries,
  proxyEntries,
  type ProxyEntry,
} from '../../lib/api-proxy-entries';

/**
 * 18.5.1.1 模板管理的 api-proxy 白名单回归。
 *
 * <p>白名单**只在浏览器端生效**（`pnpm test` 直连服务层，永远测不出代理 404），
 * 所以漏登记一条 = 整个页面在浏览器里 404，而 CI 全绿。这里把
 * 「后端真实实现的路径形状」与「api-proxy 里的正则」放在一起对撞：
 *
 * <ol>
 *   <li>用 <b>TypeScript 编译器</b>解析 {@code api-proxy.ts} 里 {@code services} 变量的
 *       <b>真实数组字面量</b>（不是文本计数、不是手抄一份清单）；
 *   <li>按 {@code resolve()} 的<b>同构口径</b>判定（先按 prefix 收窄服务，再测正则）；
 *   <li>逐条断言：本模块 12 条形状<b>恰好</b>被放行，且<b>只</b>被本模块这一条放行；
 *   <li>逐条断言：刻意不放行的 6 条形状<b>仍然 404</b>；
 *   <li>反向核对：<b>已经交付的兄弟路由</b>（text / webhooks / deliveries / catalog…）
 *       仍然各自被放行，<b>没有被本条遮蔽</b>。
 * </ol>
 *
 * <p>解析与判定口径抽在 {@code src/lib/api-proxy-entries.ts}（测试侧共用）——
 * 18.4.2.2 的 {@code ai-forecast-allowlist.test.ts} 用的是**同一套**口径：
 * 两条车道都在 {@code /platform/ai/…} 命名空间下，白名单是累积型共享文件，
 * 判定口径必须一致，否则两个车道会得出互相矛盾的结论。
 */

type Entry = ProxyEntry;

const entries = (): Entry[] => proxyEntries();

/** `resolve()` 的同构口径：先按 prefix 收窄服务，再测正则。 */
const matching = (path: string, all: Entry[]): Entry[] => matchingEntries(path, all);

const UUID = '6f1f0e2a-1c3d-4f5a-9b7c-8d9e0f1a2b3c';
const OTHER_UUID = '11111111-2222-4333-8444-555555555555';

/** 本模块真实实现的 12 条形状。 */
const ALLOWED = [
  'platform/ai/templates',
  'platform/ai/templates/capabilities',
  'platform/ai/templates/overview',
  'platform/ai/templates/resolve',
  'platform/ai/templates/render-preview',
  'platform/ai/templates/upstream/content-assets',
  `platform/ai/templates/${UUID}`,
  `platform/ai/templates/${UUID}/metadata`,
  `platform/ai/templates/${UUID}/versions`,
  `platform/ai/templates/${UUID}/versions/${OTHER_UUID}`,
  `platform/ai/templates/${UUID}/versions/${OTHER_UUID}/enable`,
  `platform/ai/templates/${UUID}/versions/${OTHER_UUID}/disable`,
];

/** 刻意**不放行**的形状（放行后端不存在的映射比 404 更糟）。 */
const DENIED = [
  'platform/ai/templates/unknown',
  'platform/ai/templates/not-a-uuid',
  `platform/ai/templates/${UUID}/extra`,
  `platform/ai/templates/${UUID}/versions/${OTHER_UUID}/update`,
  `platform/ai/templates/${UUID}/versions/${OTHER_UUID}/delete`,
  'platform/ai/templates/upstream/unknown',
];

/** 已经交付的兄弟路由 —— 本条**不许**遮蔽它们。 */
const DELIVERED = [
  'platform/text/capabilities',
  'platform/text/tasks',
  'platform/webhooks/subscriptions',
  'platform/deliveries/publish',
  'platform/automation-rules/rules',
  'platform/automation-rule-editor/save',
  'platform/external-integrations/adapters/availability',
  'catalog/products',
  'identity/roles',
  'channel/ad-campaigns/campaigns',
  'finance/payment-orders',
];

describe('18.5.1.1 模板管理的 api-proxy 白名单', () => {
  it('本模块 12 条形状恰好被放行，且只被本模块这一条放行', () => {
    const all = entries();
    for (const path of ALLOWED) {
      const matched = matching(path, all);
      expect(matched.length, `${path} 应当恰好被一条放行`).toBe(1);
      expect(matched[0]?.prefix, `${path} 应当走 platform 服务`).toBe('platform/');
      expect(matched[0]?.pattern.test(path.slice('platform/'.length))).toBe(true);
    }
  });

  it('刻意不放行的 6 条形状仍然 404', () => {
    const all = entries();
    for (const path of DENIED) {
      expect(matching(path, all), `${path} 不该被放行`).toEqual([]);
    }
  });

  it('已经交付的兄弟路由没有被本条遮蔽', () => {
    const all = entries();
    const mine = entriesBySource(all, '^ai\\/templates');
    expect(mine.length, '本模块应当恰好有一条独立条目').toBe(1);
    const minePattern = mine[0]!.pattern;
    for (const path of DELIVERED) {
      const matched = matching(path, all);
      expect(matched.length, `${path} 应当仍然被放行`).toBe(1);
      expect(
        minePattern.test(path.slice('platform/'.length)),
        `${path} 不该被本模块的条目吃掉`,
      ).toBe(false);
    }
  });

  it('同批其它车道的形状不被本条吃掉', () => {
    const all = entries();
    const mine = entriesBySource(all, '^ai\\/templates');
    expect(mine.length, '本模块应当恰好有一条独立条目').toBe(1);
    const pattern = mine[0]!.pattern;
    // 18.4.2.2 → ai/forecast/…；18.4.3.1 → ai/usage/…；18.3.2.1 → automation/runs/…
    for (const sibling of [
      'platform/ai/forecast/capabilities',
      'platform/ai/forecast/predictions',
      'platform/ai/usage/capabilities',
      'platform/ai/usage/quotas',
      'platform/automation/runs',
      'platform/automation/runs/123',
    ]) {
      expect(pattern.test(sibling.slice('platform/'.length)), `${sibling} 不该被本条吃掉`).toBe(
        false,
      );
    }
  });
});
