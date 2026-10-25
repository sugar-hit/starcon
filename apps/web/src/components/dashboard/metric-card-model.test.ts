import { describe, expect, it } from 'vitest';
import {
  SCOPE_MODE_LABELS,
  WINDOW_OPTIONS,
  caliberTooltip,
  changeRateText,
  comparisonLine,
  currencyRows,
  emptyState,
  formatValue,
  headlineText,
  isUnavailable,
  scopeModeOf,
  statusLabel,
  statusTone,
  trendLabel,
  trendTone,
  unavailableCapabilities,
  unitLabel,
  windowLabel,
  type MetricCardCapability,
  type MetricCardEntry,
  type MetricCardsCapabilities,
  type SessionGrant,
} from './metric-card-model';

/**
 * 1.1.2.1 核心指标卡 前端模型单测。
 *
 * <p>这里钉住的是**前端最容易做错的那几件事**：
 * 「算不出来」不能显示成 0、趋势不能自己算、多币种不能相加、
 * 数据范围取最宽的一条授权（且 DEPARTMENT 没有部门时要退化成 SELF）。
 */

const CARD_KEY = 'metric.sales-amount';

function card(overrides: Partial<MetricCardEntry> = {}): MetricCardEntry {
  return {
    key: CARD_KEY,
    metricCode: 'SALES_AMOUNT',
    displayName: '销售额',
    unit: 'CURRENCY',
    currencyAware: true,
    status: 'AVAILABLE',
    values: [{ currency: 'USD', value: 1000 }],
    headline: { currency: 'USD', value: 1000 },
    previousPeriod: {
      label: '环比（上一等长周期）',
      from: '2026-10-25',
      to: '2026-10-25',
      changeRate: 100,
      changeAmount: 500,
      comparable: true,
      reason: '',
    },
    yearOverYear: {
      label: '同比（去年同期）',
      from: '2025-10-26',
      to: '2025-10-26',
      changeRate: 25,
      changeAmount: 200,
      comparable: true,
      reason: '',
    },
    trend: 'UP',
    trendBasis: '环比',
    caliber: '成交订单的含税成交金额，按订单币种分别统计，不跨币种相加。',
    ownerModule: '9.1.1.1',
    drilldown: {
      available: true,
      detailPath: 'reporting/overview/drilldown?metricCode=SALES_AMOUNT',
      reportPath: '/analytics?metric=SALES_AMOUNT',
      reportName: '13.1.1.1 经营总览',
      reason: '',
    },
    note: '',
    ...overrides,
  };
}

function capability(overrides: Partial<MetricCardCapability> = {}): MetricCardCapability {
  return {
    key: CARD_KEY,
    metricCode: 'SALES_AMOUNT',
    displayName: '销售额',
    unit: 'CURRENCY',
    currencyAware: true,
    available: true,
    ownerModule: '9.1.1.1',
    caliber: '成交订单的含税成交金额。',
    aggregation: 'SUM',
    numeratorMetricCode: null,
    denominatorMetricCode: null,
    sourceTables: 'reporting_metric_fact',
    sourceKind: 'REPORTING_PROJECTION',
    drilldownAvailable: true,
    drilldownTargetName: '13.1.1.1 经营总览',
    unavailableReason: '',
    note: '',
    ...overrides,
  };
}

function grant(scopeMode: string, permissions = ['reporting.overview.read']): SessionGrant {
  return {
    roleId: 'r1',
    roleCode: 'TEST',
    scopeMode,
    permissions,
  };
}

describe('数值展示：算不出来不是 0', () => {
  it('null 一律显示「—」，不是 0', () => {
    expect(formatValue(null, 'CURRENCY')).toBe('—');
    expect(formatValue(undefined, 'COUNT')).toBe('—');
    expect(formatValue(Number.NaN, 'QUANTITY')).toBe('—');
  });

  it('真的有 0 就显示 0（0 与「算不出来」是两件事）', () => {
    expect(formatValue(0, 'COUNT')).toBe('0');
  });

  it('主值缺失时显示「—」，未接通时显示「未接通」', () => {
    expect(headlineText(card({ headline: null }))).toBe('—');
    expect(headlineText(card({ status: 'NOT_CONNECTED', headline: null }))).toBe('未接通');
  });

  it('主值带币种', () => {
    expect(headlineText(card())).toContain('USD');
  });

  it('多币种折不出本位币合计时显示「按币种分列」，不是「—」也不是随便挑一个币种', () => {
    expect(
      headlineText(
        card({
          values: [
            { currency: 'USD', value: 1000 },
            { currency: 'EUR', value: 200 },
          ],
          headline: null,
        }),
      ),
    ).toBe('按币种分列');
  });
});

describe('金额 / 数量的展示：十进制精确，不走浮点', () => {
  it('金额固定两位小数并带千分位', () => {
    expect(formatValue(1234567.891, 'CURRENCY')).toBe('1,234,567.89');
    expect(formatValue(0, 'CURRENCY')).toBe('0.00');
  });

  it('数量最多六位小数、去掉多余的尾随零', () => {
    expect(formatValue(300, 'QUANTITY')).toBe('300');
    expect(formatValue(1.5, 'QUANTITY')).toBe('1.5');
    expect(formatValue(0.000001, 'QUANTITY')).toBe('0.000001');
  });

  it('计数是整数，带千分位', () => {
    expect(formatValue(1234, 'COUNT')).toBe('1,234');
    expect(formatValue(12.7, 'COUNT')).toBe('13');
  });

  it('HALF_UP 在十进制字面串上做：1.005 进位、1.004 不进位', () => {
    // ⚠️ 这正是浮点会翻车的地方：1.005 的二进制浮点其实略小于 1.005，
    //   直接按 double 四舍五入会得到 1.00；这里按**十进制字面串**处理，得到 1.01。
    expect(formatValue(1.005, 'CURRENCY')).toBe('1.01');
    expect(formatValue(1.004, 'CURRENCY')).toBe('1.00');
  });

  it('负数按远离零进位', () => {
    expect(formatValue(-1234.565, 'CURRENCY')).toBe('-1,234.57');
  });
});

describe('环比 / 同比：不可判定显示「—」，不是 0%', () => {
  it('null 变化率显示「—」', () => {
    expect(changeRateText(null)).toBe('—');
    expect(
      changeRateText({
        label: '环比',
        from: 'a',
        to: 'b',
        changeRate: null,
        changeAmount: null,
        comparable: false,
        reason: '对比期没有事实数据',
      }),
    ).toBe('—');
  });

  it('变化率带正负号', () => {
    expect(
      changeRateText({
        label: '环比',
        from: 'a',
        to: 'b',
        changeRate: 12.345,
        changeAmount: 1,
        comparable: true,
        reason: '',
      }),
    ).toBe('+12.35%');
  });

  it('基期没数据显示「无同期基期」，不是 0% 也不是「不可判定」这种空话', () => {
    expect(
      comparisonLine({
        label: '环比',
        from: 'a',
        to: 'b',
        changeRate: null,
        changeAmount: null,
        comparable: false,
        reason: '对比期没有事实数据，无法计算变化率（不可判定，不是 0%）。',
      }),
    ).toBe('无同期基期');
  });

  it('其它不可判定原因各有各的短标签（不是一律「不可判定」）', () => {
    const label = (reason: string) =>
      comparisonLine({
        label: '环比',
        from: 'a',
        to: 'b',
        changeRate: null,
        changeAmount: null,
        comparable: false,
        reason,
      });
    expect(label('对比期取值为 0，变化率不可比（不显示成 0%）。')).toBe('基期为 0');
    expect(label('缺少汇率快照，折算不到同一本位币 —— 不可判定（没有汇率就不可判定，不是按 1:1 算）。')).toBe(
      '缺汇率快照',
    );
    expect(label('多币种指标没有单一的变化率：指定本位币后按汇率快照折算比较。')).toBe('多币种不可比');
    expect(label('对比期或本期缺少这个币种的数据，无法计算变化率。')).toBe('基期缺该币种');
    expect(label('说不清的原因')).toBe('不可判定');
  });

  it('可比时写变化率与对比期', () => {
    const line = comparisonLine(card().previousPeriod);
    expect(line).toContain('+100.00%');
    expect(line).toContain('2026-10-25');
  });
});

describe('趋势箭头：由后端的变化率决定', () => {
  it('四种取值各自的中文标签', () => {
    expect(trendLabel('UP')).toBe('上升');
    expect(trendLabel('DOWN')).toBe('下降');
    expect(trendLabel('FLAT')).toBe('持平');
    expect(trendLabel('UNKNOWN')).toBe('无');
  });

  it('UNKNOWN 不是中性绿也不是危险色', () => {
    expect(trendTone('UNKNOWN')).toBe('neutral');
  });
});

describe('卡片状态', () => {
  it('未接通与上游读不到都算「不可用」，但不是 0', () => {
    expect(isUnavailable(card({ status: 'NOT_CONNECTED' }))).toBe(true);
    expect(isUnavailable(card({ status: 'UPSTREAM_UNAVAILABLE' }))).toBe(true);
    expect(isUnavailable(card({ status: 'NO_DATA' }))).toBe(false);
    expect(isUnavailable(card())).toBe(false);
  });

  it('状态标签分得开「没有数据」与「未接通」', () => {
    expect(statusLabel('NO_DATA')).toContain('没有投影数据');
    expect(statusLabel('NOT_CONNECTED')).toBe('未接通');
    expect(statusLabel('UPSTREAM_UNAVAILABLE')).toBe('上游读不到');
    expect(statusTone('NO_DATA')).toBe('warning');
    expect(statusTone('AVAILABLE')).toBe('success');
  });
});

describe('多币种：不相加', () => {
  it('按币种分行，空值行被剔除', () => {
    const rows = currencyRows(
      card({ values: [{ currency: 'USD', value: 1000 }, null, { currency: 'EUR', value: 200 }] }),
    );
    expect(rows).toHaveLength(2);
    expect(rows.map((row) => row.currency)).toEqual(['USD', 'EUR']);
  });
});

describe('能力声明：未接通的逐条列出来', () => {
  const caps: MetricCardsCapabilities = {
    upstreamConnected: true,
    drilldownAvailable: true,
    dataUpdatedAtVisible: true,
    metrics: [
      capability(),
      capability({
        key: 'metric.in-transit-value',
        metricCode: 'IN_TRANSIT_VALUE',
        displayName: '在途金额',
        available: false,
        numeratorMetricCode: null,
        denominatorMetricCode: null,
        unavailableReason: '口径尚未接通',
      }),
    ],
    upstreamModules: ['13.1.1.1 经营总览'],
    notDelivered: ['**在途金额**：口径表里没有这个指标码'],
    notes: ['口径单点定义在 13'],
  };

  it('未接通的指标被单独挑出来（不悄悄消失）', () => {
    const missing = unavailableCapabilities(caps);
    expect(missing).toHaveLength(1);
    expect(missing.map((metric) => metric.metricCode)).toEqual(['IN_TRANSIT_VALUE']);
    expect(missing.map((metric) => metric.unavailableReason)).toEqual([
      expect.stringContaining('口径尚未接通'),
    ]);
  });

  it('能力声明为 null 时返回空数组而不是崩', () => {
    expect(unavailableCapabilities(null)).toEqual([]);
  });
});

describe('数据范围：取最宽的一条授权', () => {
  it('ALL > DEPARTMENT > SELF', () => {
    expect(scopeModeOf([grant('SELF'), grant('DEPARTMENT'), grant('ALL')], 'reporting.overview.read', true)).toBe(
      'ALL',
    );
    expect(scopeModeOf([grant('SELF'), grant('DEPARTMENT')], 'reporting.overview.read', true)).toBe(
      'DEPARTMENT',
    );
    expect(scopeModeOf([grant('SELF')], 'reporting.overview.read', true)).toBe('SELF');
  });

  it('DEPARTMENT 但会话没有部门 ⇒ 退化成 SELF（往更严的一侧退化）', () => {
    expect(scopeModeOf([grant('DEPARTMENT')], 'reporting.overview.read', false)).toBe('SELF');
  });

  it('没有授权时按 SELF 算', () => {
    expect(scopeModeOf(null, 'reporting.overview.read', true)).toBe('SELF');
    expect(scopeModeOf([], 'reporting.overview.read', true)).toBe('SELF');
  });

  it('三个 scopeMode 都有中文标签', () => {
    expect(SCOPE_MODE_LABELS.ALL).toBe('租户内全部');
    expect(SCOPE_MODE_LABELS.DEPARTMENT).toBe('本部门');
    expect(SCOPE_MODE_LABELS.SELF).toBe('仅本人');
  });
});

describe('口径 tooltip 与空态', () => {
  it('tooltip 里带口径、归属与「为什么不可判定」', () => {
    const text = caliberTooltip(
      card({
        previousPeriod: {
          label: '环比',
          from: 'a',
          to: 'b',
          changeRate: null,
          changeAmount: null,
          comparable: false,
          reason: '对比期没有事实数据',
        },
        note: '这个窗口没有事实数据',
      }),
    );
    expect(text).toContain('含税成交金额');
    expect(text).toContain('9.1.1.1');
    expect(text).toContain('对比期没有事实数据');
  });

  it('空态：加载失败 / 没有卡片 / 没有投影数据，三种文案不同', () => {
    const failed = emptyState(false, [], false);
    const noCards = emptyState(true, [], false);
    const noFacts = emptyState(true, [card()], false);
    expect(failed.title).toContain('加载失败');
    expect(noCards.title).toContain('还没有核心指标卡');
    expect(noFacts.title).toContain('还没有投影数据');
    expect(new Set([failed.title, noCards.title, noFacts.title]).size).toBe(3);
  });
});

describe('时间口径与单位', () => {
  it('四个时间口径都有标签', () => {
    expect(WINDOW_OPTIONS.map((option) => option.value)).toEqual([
      'TODAY',
      'YESTERDAY',
      'THIS_MONTH',
      'CUSTOM',
    ]);
    expect(windowLabel('TODAY')).toBe('今日');
    expect(windowLabel('CUSTOM')).toBe('自定义区间');
  });

  it('金额没有单位后缀，计数与数量有', () => {
    expect(unitLabel('CURRENCY')).toBe('');
    expect(unitLabel('COUNT')).toBe('笔');
    expect(unitLabel('QUANTITY')).toBe('件');
  });
});
