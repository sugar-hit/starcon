import { describe, expect, it } from 'vitest';
import { existsSync } from 'node:fs';
import { join } from 'node:path';
import {
  DEFAULT_WORKBENCH_TAB,
  WORKBENCH_CARDS,
  WORKBENCH_TABS,
  findWorkbenchCard,
} from './workbench-card-registry';
import {
  WORKBENCH_LAYOUT_PAGE_KEY,
  buildWorkbenchLayoutPayload,
  defaultWorkbenchLayout,
  describeWorkbenchLayout,
  moveWorkbenchCard,
  normalizeWorkbenchOrder,
  reorderWorkbenchCard,
  resolveWorkbenchLayout,
  setWorkbenchCardHidden,
  visibleWorkbenchCards,
  visibleWorkbenchCardsInTab,
} from './workbench-layout-model';

/**
 * 1.4.1.1 工作台布局 —— 前端模型的单元测试。
 *
 * <p>本模型守的是四件**静默**的事：
 *
 * <ol>
 *   <li><b>缺键套默认值</b>（而不是报错）—— 第一次用、老版本布局都必须是完整可用的；</li>
 *   <li><b>存回来的顺序必须落在「现在认识的卡片」上</b> —— 下架的卡片不能继续占位；</li>
 *   <li><b>系统新加的卡片默认可见</b> —— 只有用户明确藏过的那几张才继续藏着；</li>
 *   <li><b>落库的 order 永远是一份完整排列</b> —— 「谁在第几位」只有一份真相。</li>
 * </ol>
 *
 * <p>另外两条结构性守卫：注册表里的 key 不许重复；注册表里的每一张卡片都必须指向
 * **真实存在**的页面（否则点进去撞到兜底页，那是假承诺）。
 */

const APP_DIR = join(process.cwd(), 'apps/web/src/app');

function hasPage(href: string): boolean {
  return existsSync(join(APP_DIR, href.replace(/^\//, ''), 'page.tsx'));
}

describe('1.4.1.1 卡片注册表（系统事实）', () => {
  it('page key 用的是 dashboard.layout', () => {
    expect(WORKBENCH_LAYOUT_PAGE_KEY).toBe('dashboard.layout');
  });

  it('卡片 key 不许重复', () => {
    const keys = WORKBENCH_CARDS.map((card) => card.key);
    expect(new Set(keys).size).toBe(keys.length);
  });

  it('每张卡片都指向真实存在的页面（不指向未开发的模块）', () => {
    const missing = WORKBENCH_CARDS.filter((card) => !hasPage(card.href)).map((card) => card.href);
    expect(missing).toEqual([]);
  });

  it('每张卡片都属于一个已注册分区', () => {
    const tabIds = WORKBENCH_TABS.map((tab) => tab.id);
    for (const card of WORKBENCH_CARDS) {
      expect(tabIds, `${card.key} 属于未注册分区 ${card.tab}`).toContain(card.tab);
    }
  });

  it('每个分区至少有一张卡片（否则落地就是空的）', () => {
    for (const tab of WORKBENCH_TABS) {
      const count = WORKBENCH_CARDS.filter((card) => card.tab === tab.id).length;
      expect(count, `分区 ${tab.id} 一张卡都没有`).toBeGreaterThan(0);
    }
  });

  it('按 key 能查到卡片，查不到返回 undefined 而不是抛错', () => {
    expect(findWorkbenchCard('todo.anomaly-orders')?.label).toBe('异常订单队列');
    expect(findWorkbenchCard('card.ghost')).toBeUndefined();
  });
});

describe('1.4.1.1 布局解析：缺键套默认值', () => {
  it('载荷完全缺失时给出系统默认布局', () => {
    expect(resolveWorkbenchLayout(null)).toEqual(defaultWorkbenchLayout());
    expect(resolveWorkbenchLayout(undefined)).toEqual(defaultWorkbenchLayout());
    expect(resolveWorkbenchLayout({})).toEqual(defaultWorkbenchLayout());
  });

  it('载荷不是对象时按默认布局处理', () => {
    expect(resolveWorkbenchLayout('nonsense')).toEqual(defaultWorkbenchLayout());
    expect(resolveWorkbenchLayout([1, 2, 3])).toEqual(defaultWorkbenchLayout());
  });

  it('缺 order 时顺序用注册表默认顺序', () => {
    const resolved = resolveWorkbenchLayout({ hidden: ['risk.inventory'] });
    expect(resolved.order).toEqual(WORKBENCH_CARDS.map((card) => card.key));
    expect(resolved.hidden).toEqual(['risk.inventory']);
  });

  it('缺 defaultTab 时落在默认分区', () => {
    expect(resolveWorkbenchLayout({}).defaultTab).toBe(DEFAULT_WORKBENCH_TAB);
  });

  it('不认识的分区回落到默认分区', () => {
    expect(resolveWorkbenchLayout({ defaultTab: 'not-a-tab' }).defaultTab).toBe(
      DEFAULT_WORKBENCH_TAB,
    );
    expect(resolveWorkbenchLayout({ defaultTab: 'todo' }).defaultTab).toBe('todo');
  });
});

describe('1.4.1.1 布局解析：只认现在认识的卡片', () => {
  it('顺序里不认识的卡片被丢掉（不占位）', () => {
    const resolved = resolveWorkbenchLayout({
      order: ['card.ghost', 'risk.inventory', 'card.ghost'],
    });
    expect(resolved.order).not.toContain('card.ghost');
    expect(resolved.order).toHaveLength(WORKBENCH_CARDS.length);
  });

  it('重复的卡片只保留第一次出现的位置', () => {
    const resolved = resolveWorkbenchLayout({
      order: ['risk.inventory', 'todo.anomaly-orders', 'risk.inventory'],
    });
    expect(resolved.order[0]).toBe('risk.inventory');
    expect(resolved.order[1]).toBe('todo.anomaly-orders');
    expect(resolved.order.filter((key) => key === 'risk.inventory')).toHaveLength(1);
  });

  it('hidden 里的陌生卡片被丢掉', () => {
    const resolved = resolveWorkbenchLayout({ hidden: ['card.ghost', 'risk.inventory'] });
    expect(resolved.hidden).toEqual(['risk.inventory']);
  });

  it('新加的卡片默认可见（老用户的 hidden 里没有它）', () => {
    const resolved = resolveWorkbenchLayout({ hidden: ['risk.inventory'] });
    const visible = visibleWorkbenchCards(resolved).map((card) => card.key);
    expect(visible).not.toContain('risk.inventory');
    expect(visible).toContain('todo.anomaly-orders');
  });

  it('没提到的卡片按注册表顺序接在后面（存过的顺序原样保留）', () => {
    const resolved = resolveWorkbenchLayout({
      order: ['todo.anomaly-orders', 'risk.inventory', 'metric.fulfillment-health'],
    });
    expect(resolved.order).toHaveLength(WORKBENCH_CARDS.length);
    expect(resolved.order.slice(0, 3)).toEqual([
      'todo.anomaly-orders',
      'risk.inventory',
      'metric.fulfillment-health',
    ]);
    expect(resolved.order.slice(3)).toEqual(['nav.global-search', 'nav.shortcuts']);
  });
});

describe('1.4.1.1 顺序归一化', () => {
  it('永远与注册表等长（完整排列）', () => {
    expect(normalizeWorkbenchOrder([])).toHaveLength(WORKBENCH_CARDS.length);
    expect(normalizeWorkbenchOrder(['a', 'b'])).toHaveLength(WORKBENCH_CARDS.length);
    expect(
      normalizeWorkbenchOrder(WORKBENCH_CARDS.map((card) => card.key)),
    ).toHaveLength(WORKBENCH_CARDS.length);
  });

  it('幂等：归一化两次结果相同', () => {
    const once = normalizeWorkbenchOrder(['risk.inventory', 'card.ghost']);
    expect(normalizeWorkbenchOrder(once)).toEqual(once);
  });

  it('结果里没有重复项', () => {
    const normalized = normalizeWorkbenchOrder(['risk.inventory', 'risk.inventory']);
    expect(new Set(normalized).size).toBe(normalized.length);
  });
});

describe('1.4.1.1 排序动作', () => {
  const order = ['a', 'b', 'c'];

  it('上移一位', () => {
    expect(moveWorkbenchCard(order, 'c', -1)).toEqual(['a', 'c', 'b']);
  });

  it('下移一位', () => {
    expect(moveWorkbenchCard(order, 'a', 1)).toEqual(['b', 'a', 'c']);
  });

  it('已经在最前/最后时不动', () => {
    expect(moveWorkbenchCard(order, 'a', -1)).toEqual(order);
    expect(moveWorkbenchCard(order, 'c', 1)).toEqual(order);
  });

  it('不认识的 key 原样返回', () => {
    expect(moveWorkbenchCard(order, 'ghost', -1)).toEqual(order);
  });

  it('拖拽排序：被拖的卡落到目标卡原来的位置', () => {
    expect(reorderWorkbenchCard(order, 'a', 'c')).toEqual(['b', 'c', 'a']);
    expect(reorderWorkbenchCard(order, 'c', 'a')).toEqual(['c', 'a', 'b']);
  });

  it('拖到自己身上不动', () => {
    expect(reorderWorkbenchCard(order, 'b', 'b')).toEqual(order);
  });
});

describe('1.4.1.1 显隐', () => {
  it('藏起来：进 hidden', () => {
    expect(setWorkbenchCardHidden([], 'risk.inventory', false)).toEqual(['risk.inventory']);
  });

  it('显示出来：从 hidden 移除', () => {
    expect(setWorkbenchCardHidden(['risk.inventory'], 'risk.inventory', true)).toEqual([]);
  });

  it('重复藏同一张不会产生两份', () => {
    const hidden = setWorkbenchCardHidden(['risk.inventory'], 'risk.inventory', false);
    expect(hidden).toEqual(['risk.inventory']);
  });
});

describe('1.4.1.1 落库载荷', () => {
  it('order 永远是完整排列，hidden 只留认识的卡片', () => {
    const payload = buildWorkbenchLayoutPayload({
      order: ['card.ghost'],
      hidden: ['card.ghost', 'risk.inventory'],
      defaultTab: 'todo',
    });
    expect(payload.order).toHaveLength(WORKBENCH_CARDS.length);
    expect(payload.hidden).toEqual(['risk.inventory']);
    expect(payload.defaultTab).toBe('todo');
  });

  it('defaultTab 不认识时落默认分区', () => {
    const payload = buildWorkbenchLayoutPayload({
      order: [],
      hidden: [],
      defaultTab: 'not-a-tab' as never,
    });
    expect(payload.defaultTab).toBe(DEFAULT_WORKBENCH_TAB);
  });

  it('载荷形状是 order / hidden / defaultTab 三个键', () => {
    const payload = buildWorkbenchLayoutPayload(defaultWorkbenchLayout());
    expect(Object.keys(payload).sort()).toEqual(['defaultTab', 'hidden', 'order']);
  });
});

describe('1.4.1.1 渲染口径', () => {
  it('显示出来的卡片按布局顺序', () => {
    const state = resolveWorkbenchLayout({
      order: ['risk.inventory', 'todo.anomaly-orders'],
    });
    expect(visibleWorkbenchCards(state).map((card) => card.key)).toEqual([
      'risk.inventory',
      'todo.anomaly-orders',
      'metric.fulfillment-health',
      'nav.global-search',
      'nav.shortcuts',
    ]);
  });

  it('按分区过滤', () => {
    const state = defaultWorkbenchLayout();
    expect(visibleWorkbenchCardsInTab(state, 'todo').map((card) => card.key)).toEqual([
      'todo.anomaly-orders',
    ]);
    expect(visibleWorkbenchCardsInTab(state, 'risk').map((card) => card.key)).toEqual([
      'risk.inventory',
    ]);
  });

  it('藏光一个分区时该分区为空（界面走空态）', () => {
    const state = resolveWorkbenchLayout({ hidden: ['todo.anomaly-orders'] });
    expect(visibleWorkbenchCardsInTab(state, 'todo')).toEqual([]);
  });

  it('摘要能说清默认分区、显示了几张、藏了哪几张', () => {
    const state = resolveWorkbenchLayout({
      defaultTab: 'risk',
      hidden: ['nav.shortcuts'],
    });
    const summary = describeWorkbenchLayout(state).join('\n');
    expect(summary).toContain('风险速览');
    expect(summary).toContain('显示的卡片');
    expect(summary).toContain('快捷入口配置');
  });
});
