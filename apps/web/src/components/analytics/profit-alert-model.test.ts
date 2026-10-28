import { describe, expect, it } from 'vitest';
import {
  ALERT_TYPE_OPTIONS,
  SEVERITY_OPTIONS,
  baselineModeLabel,
  baselineText,
  dataUpdatedNote,
  dataUpdatedText,
  defaultRange,
  deltaText,
  emptyText,
  grossProfitText,
  isActionable,
  notificationObservedText,
  notificationStatusLabel,
  notificationStatusTone,
  observedText,
  pageState,
  rangeText,
  ruleThresholdText,
  severityLabel,
  severityTone,
  thresholdModeLabel,
  thresholdText,
  undecidedNote,
  undecidedScopeText,
  unitLabel,
} from './profit-alert-model';
import type {
  ProfitAlert,
  ProfitAlertNotification,
  ProfitAlertPage,
  ProfitAlertRule,
  ProfitAlertUndecided,
} from './profit-alert-model';

/**
 * 13.4.3.1 利润预警 前端模型。
 *
 * 🔴 这些断言盯的是**页面最容易说错的四件事**：
 *
 * 1. **「判不出来」≠「没异常」**。服务端把判不了的条目单列在 `undecided` 里并写明原因，
 *    前端**必须原样带出来**，绝不能渲染成「正常」—— 判不了被当成「没异常」比误报更危险。
 * 2. **「算不出来」≠「等于 0」**。服务端给 `null` 的观测值 / 基线 / 差额必须渲染成「—」，
 *    **不是** 0.00（假 0 会被读成「完全没花钱」）。
 * 3. **阈值有单位，单位不是前端猜的**。百分点 / 百分数 / 倍数三种含义完全不同。
 * 4. **前端不做业务算术**。观测值、基线、差额一律来自服务端，这里只做格式化。
 */

function alert(overrides: Partial<ProfitAlert> = {}): ProfitAlert {
  return {
    alertType: 'SKU_NEGATIVE_GROSS_PROFIT',
    alertTypeLabel: 'SKU 毛利转负',
    severity: 'CRITICAL',
    severityLabel: '严重',
    scopeType: 'SKU',
    scopeKey: 'SKU-1',
    scopeLabel: 'SKU SKU-1',
    currency: 'USD',
    metricCode: 'GROSS_MARGIN',
    observedValue: -20,
    observedUnit: 'PERCENT',
    thresholdMode: 'FIXED',
    thresholdValue: 0,
    thresholdUnit: 'PERCENT',
    baselineValue: 8,
    baselineMode: 'PREVIOUS_WINDOW',
    delta: -28,
    revenue: 100,
    grossProfit: -20,
    fingerprint: 'SKU_NEGATIVE_GROSS_PROFIT|SKU|SKU-1|USD|2026-10-01|2026-10-01',
    reason: 'SKU SKU-1 毛利率 -20.0000% 已跌破阈值 0.0000%',
    note: 'SKU 维度的收入取订单行金额口径',
    actions: [
      { label: '去利润报表复核这个 SKU', href: '/analytics/profit/profit-report', description: 'x' },
    ],
    ...overrides,
  };
}

function undecided(overrides: Partial<ProfitAlertUndecided> = {}): ProfitAlertUndecided {
  return {
    alertType: 'SHOP_MARGIN_DROP',
    alertTypeLabel: '店铺利润率骤降',
    scopeType: 'SHOP',
    scopeKey: 'SHOP-1',
    scopeLabel: '店铺 SHOP-1',
    currency: 'USD',
    reason: '历史基线算不出来（前 2 期同口径窗口里可比的不足 2 期）⇒ 判不了骤降，不是「没骤降」。',
    ...overrides,
  };
}

function page(overrides: Partial<ProfitAlertPage> = {}): ProfitAlertPage {
  return {
    from: '2026-10-01',
    to: '2026-10-30',
    alerts: [alert()],
    undecided: [],
    summary: {
      total: 1,
      byTypeCount: 1,
      bySeverityCount: 1,
      undecidedCount: 0,
      criticalCount: 1,
      warningCount: 0,
      infoCount: 0,
      truncated: false,
    },
    page: 0,
    size: 50,
    total: 1,
    dataUpdatedAt: '2026-10-31T02:00:00Z',
    dataUpdatedNote: '最近一次利润事实投影落库时刻。',
    warnings: [],
    caliberNote: '收入：店铺 / 订单维度取订单总额口径。',
    ...overrides,
  };
}

function withUndecided(items: ProfitAlertUndecided[] = [undecided()]): ProfitAlertPage {
  const base = page();
  return {
    ...base,
    undecided: items,
    summary: { ...base.summary, undecidedCount: items.length },
  };
}

// ================================================================ 「算不出来」≠ 0

describe('13.4.3.1 利润预警 · 算不出来不等于 0', () => {
  it('观测值为 null 时显示「—（算不出来）」而不是 0', () => {
    expect(observedText(alert({ observedValue: null }))).toBe('—（算不出来）');
    expect(observedText(alert({ observedValue: null }))).not.toContain('0.00');
  });

  it('基线为 null 时显示「—（基线算不出来）」', () => {
    expect(baselineText(alert({ baselineValue: null }))).toBe('—（基线算不出来）');
  });

  it('差额为 null 时显示「—」（任一侧不可比就不做差）', () => {
    expect(deltaText(alert({ delta: null }))).toBe('—');
  });

  it('毛利为 null 时显示「—（不可比）」而不是 0', () => {
    expect(grossProfitText(null, 'USD')).toBe('—（不可比）');
    expect(grossProfitText(0, 'USD')).toBe('0.00 USD');
  });

  it('金额为 null 时显示「—」', () => {
    expect(observedText(alert({ observedValue: null, observedUnit: 'PERCENT' }))).not.toBe('0.00%');
  });
});

// ================================================================ 单位

describe('13.4.3.1 利润预警 · 阈值单位', () => {
  it('百分点 / 百分数 / 倍数三种单位互不混用', () => {
    expect(unitLabel('PERCENTAGE_POINTS')).toBe(' 个百分点');
    expect(unitLabel('PERCENT')).toBe('%');
    expect(unitLabel('MULTIPLE')).toBe(' 倍');
    expect(unitLabel('UNKNOWN')).toBe('');
  });

  it('观测值按单位格式化', () => {
    expect(observedText(alert({ observedValue: -20, observedUnit: 'PERCENT' }))).toBe('-20.00%');
    expect(
      observedText(
        alert({ observedValue: 1.66666667, observedUnit: 'MULTIPLE', thresholdUnit: 'MULTIPLE' }),
      ),
    ).toBe('1.67 倍');
    expect(
      observedText(
        alert({ observedValue: 20, observedUnit: 'PERCENTAGE_POINTS', thresholdUnit: 'PERCENTAGE_POINTS' }),
      ),
    ).toBe('20.00 个百分点');
  });

  it('阈值与基线带上同一个单位', () => {
    expect(thresholdText(alert({ thresholdValue: 10, thresholdUnit: 'PERCENTAGE_POINTS' }))).toBe(
      '10.00 个百分点',
    );
    expect(baselineText(alert({ baselineValue: 30, observedUnit: 'PERCENTAGE_POINTS' }))).toBe(
      '30.00 个百分点',
    );
  });

  it('阈值口径与基线口径的中文说明', () => {
    expect(thresholdModeLabel('FIXED')).toBe('固定阈值线');
    expect(thresholdModeLabel('BASELINE')).toBe('相对历史基线');
    expect(baselineModeLabel('BASELINE_MEAN')).toBe('前 N 期同口径窗口均值');
    expect(baselineModeLabel('PREVIOUS_WINDOW')).toBe('上一窗口（仅作参考，不参与判定）');
  });
});

// ================================================================ 「判不出来」≠「没异常」

describe('13.4.3.1 利润预警 · 判不出来不等于没异常', () => {
  it('空态文案明说「没有命中 ≠ 没有异常」', () => {
    const empty = page({ alerts: [], undecided: [] });
    expect(emptyText(empty)).toContain('没有命中');
    expect(emptyText(empty)).toContain('≠');
  });

  it('只有判不出来、没有命中时，空态指向「判不出来」清单', () => {
    const response = { ...withUndecided(), alerts: [] };
    expect(emptyText(response)).toContain('判不出来');
  });

  it('「判不出来」清单有独立的说明，且写明不是「没异常」', () => {
    const response = withUndecided();
    expect(undecidedNote(response)).toContain('不是「没异常」');
    expect(undecidedNote(response)).toContain('1 条');
  });

  it('没有判不出来时不给出该说明', () => {
    expect(undecidedNote(page())).toBe('');
  });

  it('判不出来条目带自己的原因原文', () => {
    const item = undecided();
    expect(item.reason).toContain('判不了');
    expect(undecidedScopeText(item)).toBe('店铺 SHOP-1');
  });

  it('页面状态：只有判不出来时**不是空态**', () => {
    const response = { ...withUndecided(), alerts: [] };
    expect(pageState(response, '')).toBe('ready');
  });
});

// ================================================================ 页面状态

describe('13.4.3.1 利润预警 · 页面三态', () => {
  it('读不到 ⇒ 错误态；读到但没有数据 ⇒ 空态；有数据 ⇒ 就绪', () => {
    expect(pageState(null, 'boom')).toBe('error');
    expect(pageState(null, '')).toBe('loading');
    expect(pageState(page({ alerts: [], undecided: [] }), '')).toBe('empty');
    expect(pageState(page(), '')).toBe('ready');
  });

  it('数据更新时间常驻：没投影时也要有一句话', () => {
    expect(dataUpdatedText(null)).toBe('');
    expect(dataUpdatedText(page({ dataUpdatedAt: null }))).toContain('尚未投影');
    expect(dataUpdatedText(page())).toContain('数据截至');
    expect(dataUpdatedNote(page())).toContain('投影落库时刻');
  });

  it('默认窗口是最近 30 天（含今天）', () => {
    const range = defaultRange('2026-10-30');
    expect(range.to).toBe('2026-10-30');
    expect(range.from).toBe('2026-10-01');
    expect(rangeText(range.from, range.to)).toBe('2026-10-01 ~ 2026-10-30');
  });
});

// ================================================================ 语义色与文案

describe('13.4.3.1 利润预警 · 语义色', () => {
  it('严重度跟语义挂钩', () => {
    expect(severityTone('CRITICAL')).toBe('danger');
    expect(severityTone('WARNING')).toBe('warning');
    expect(severityTone('INFO')).toBe('info');
    expect(severityTone('WHATEVER')).toBe('neutral');
  });

  it('服务端给了中文名就用服务端的', () => {
    expect(severityLabel('CRITICAL', '严重')).toBe('严重');
    expect(severityLabel('CRITICAL', '')).toBe('严重');
  });

  it('通知状态：终态不可处理', () => {
    expect(notificationStatusLabel('PENDING')).toBe('待处理');
    expect(notificationStatusTone('PENDING')).toBe('warning');
    expect(notificationStatusTone('ACKNOWLEDGED')).toBe('success');
    expect(notificationStatusTone('DISMISSED')).toBe('neutral');
    expect(isActionable('PENDING')).toBe(true);
    expect(isActionable('ACKNOWLEDGED')).toBe(false);
    expect(isActionable('DISMISSED')).toBe(false);
  });
});

// ================================================================ 阈值规则

describe('13.4.3.1 利润预警 · 阈值规则', () => {
  function rule(overrides: Partial<ProfitAlertRule> = {}): ProfitAlertRule {
    return {
      alertType: 'SHOP_MARGIN_DROP',
      alertTypeLabel: '店铺利润率骤降',
      thresholdMode: 'BASELINE',
      thresholdValue: 10,
      thresholdUnit: 'PERCENTAGE_POINTS',
      minHistoryPeriods: 2,
      severity: 'WARNING',
      severityLabel: '警告',
      enabled: true,
      description: '店铺毛利率相对历史基线下降的百分点。',
      updatedAt: '2026-10-31T02:00:00Z',
      ...overrides,
    };
  }

  it('规则阈值带单位', () => {
    expect(ruleThresholdText(rule())).toBe('10.00 个百分点');
    expect(ruleThresholdText(rule({ thresholdValue: 1, thresholdUnit: 'MULTIPLE' }))).toBe('1.00 倍');
    expect(ruleThresholdText(rule({ thresholdValue: 80, thresholdUnit: 'PERCENT' }))).toBe('80.00%');
  });
});

// ================================================================ 通知

describe('13.4.3.1 利润预警 · 通知', () => {
  function notification(overrides: Partial<ProfitAlertNotification> = {}): ProfitAlertNotification {
    return {
      id: '11111111-1111-1111-1111-111111111111',
      alertType: 'SKU_NEGATIVE_GROSS_PROFIT',
      alertTypeLabel: 'SKU 毛利转负',
      severity: 'CRITICAL',
      severityLabel: '严重',
      scopeType: 'SKU',
      scopeKey: 'SKU-1',
      scopeLabel: 'SKU SKU-1',
      currency: 'USD',
      channel: 'CONSOLE',
      status: 'PENDING',
      statusLabel: '待处理',
      title: 'SKU 毛利转负 · SKU SKU-1',
      detail: '毛利率 -20% 已跌破阈值 0%',
      observedValue: -20,
      observedUnit: 'PERCENT',
      thresholdValue: 0,
      windowStart: '2026-10-01',
      windowEnd: '2026-10-30',
      createdAt: '2026-10-31T02:00:00Z',
      acknowledgedAt: null,
      acknowledgedBy: null,
      alertFingerprint: 'fp',
      ...overrides,
    };
  }

  it('通知的观测值带单位，算不出来时是「—」', () => {
    expect(notificationObservedText(notification())).toBe('-20.00%');
    expect(notificationObservedText(notification({ observedValue: null }))).toBe('—');
  });

  it('通知通道是站内（**没有真发邮件 / webhook**）', () => {
    expect(notification().channel).toBe('CONSOLE');
  });
});

// ================================================================ 选项表

describe('13.4.3.1 利润预警 · 选项表', () => {
  it('五类预警与蓝图职责句一一对应', () => {
    expect(ALERT_TYPE_OPTIONS.map((o) => o.value)).toEqual([
      'SKU_NEGATIVE_GROSS_PROFIT',
      'SHOP_MARGIN_DROP',
      'FEE_RATIO_ANOMALY',
      'AD_LOSS_EXCEEDED',
      'LOSS_ORDER_RATIO_SPIKE',
    ]);
  });

  it('严重度选项与后端词表一致', () => {
    expect(SEVERITY_OPTIONS.map((o) => o.value)).toEqual(['CRITICAL', 'WARNING', 'INFO']);
  });
});
