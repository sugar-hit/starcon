import { describe, expect, it } from 'vitest';
import {
  BASE,
  OWN_METRIC_CODES,
  UPSTREAM_METRIC_CODES,
  breachLevelLabel,
  directionLabel,
  emptyState,
  emptyStateKind,
  forecastResultLabel,
  forecastResultTone,
  metricLabel,
  metricSourceLabel,
  metricValueText,
  ruleSourceLabel,
  severityLabel,
  severityTone,
  sourceLabel,
  thresholdKindLabel,
  unitLabel,
} from './performance-metrics-model';

/**
 * 16.1.1.2 绩效指标监控 前端模型的单测。
 *
 * 🔴 这组用例守的是五件最容易做丢的事：
 *
 * 1. **「量不到」不能显示成 0.00%** —— 那是把「没测到」写成「这项是 0」。
 * 2. **量纲不混**：比值 / 小时 / 星级是三种不同的量，绝不互相渲染。
 * 3. **双来源如实表达**：四个码来自 16.1.1.1、两个码来自本模块自有。
 * 4. **「量不到」是第三种状态**，色调与「严重 / 预警」都不同。
 * 5. **三态空态文案必须各不相同**（同一个空态换个图标不算三态）。
 */

describe('数值的展示口径（量纲不混）', () => {
  it('比值渲染成百分比', () => {
    expect(metricValueText(0.028, 'RATIO')).toBe('2.80%');
    expect(metricValueText(0.99, 'RATIO')).toBe('99.00%');
    expect(metricValueText(0, 'RATIO')).toBe('0.00%');
  });

  it('🔴 量不到显示成「未知」，绝不显示成 0.00%', () => {
    expect(metricValueText(null, 'RATIO')).toBe('未知');
    expect(metricValueText(undefined, 'RATIO')).toBe('未知');
    expect(metricValueText(null, 'RATIO')).not.toBe('0.00%');
    expect(metricValueText(null, 'HOURS')).toBe('未知');
    expect(metricValueText(null, 'SCORE')).toBe('未知');
  });

  it('🔴 小时与星级绝不按百分比渲染', () => {
    expect(metricValueText(24, 'HOURS')).toBe('24.00 小时');
    expect(metricValueText(24, 'HOURS')).not.toContain('%');
    expect(metricValueText(4.2, 'SCORE')).toBe('4.20 分');
    expect(metricValueText(4.2, 'SCORE')).not.toContain('%');
  });

  it('量纲的人话', () => {
    expect(unitLabel('RATIO')).toContain('比值');
    expect(unitLabel('HOURS')).toContain('小时');
    expect(unitLabel('SCORE')).toContain('星级');
  });

  it('指标的人话名', () => {
    expect(metricLabel('ODR')).toBe('订单缺陷率（ODR）');
    expect(metricLabel('LATE_SHIPMENT_RATE')).toBe('迟发率');
    expect(metricLabel('CANCELLATION_RATE')).toBe('取消率');
    expect(metricLabel('VALID_TRACKING_RATE')).toBe('有效追踪率');
    expect(metricLabel('RESPONSE_TIME')).toBe('响应时间');
    expect(metricLabel('ACCOUNT_RATING')).toBe('账户评级');
  });
});

describe('🔴 双来源如实表达', () => {
  it('四个码来自 16.1.1.1，两个码来自本模块自有', () => {
    expect(UPSTREAM_METRIC_CODES).toHaveLength(4);
    expect(OWN_METRIC_CODES).toHaveLength(2);
    expect(metricSourceLabel('ODR')).toContain('16.1.1.1');
    expect(metricSourceLabel('VALID_TRACKING_RATE')).toContain('16.1.1.1');
    expect(metricSourceLabel('RESPONSE_TIME')).toContain('本模块');
    expect(metricSourceLabel('ACCOUNT_RATING')).toContain('本模块');
  });

  it('🔴 两个来源集合互补且不重叠（同一个指标不会有第二个真相来源）', () => {
    for (const code of UPSTREAM_METRIC_CODES) {
      expect(OWN_METRIC_CODES as readonly string[]).not.toContain(code);
    }
    expect(UPSTREAM_METRIC_CODES).toHaveLength(4);
    expect(OWN_METRIC_CODES).toHaveLength(2);
  });
});

describe('预警等级', () => {
  it('三种等级各有各的人话', () => {
    expect(severityLabel('CRITICAL')).toContain('严重');
    expect(severityLabel('WARNING')).toContain('预警');
    expect(severityLabel('UNKNOWN')).toContain('量不到');
  });

  it('🔴 「量不到」的色调与「严重 / 预警」都不同', () => {
    expect(severityTone('CRITICAL')).toBe('danger');
    expect(severityTone('WARNING')).toBe('warning');
    expect(severityTone('UNKNOWN')).toBe('neutral');
    expect(severityTone('UNKNOWN')).not.toBe(severityTone('CRITICAL'));
    expect(severityTone('UNKNOWN')).not.toBe(severityTone('WARNING'));
  });
});

describe('预测结论', () => {
  it('四种结论各有各的人话', () => {
    expect(forecastResultLabel('WILL_BREACH')).toContain('会跌破');
    expect(forecastResultLabel('NO_BREACH')).toContain('不会跌破');
    expect(forecastResultLabel('ALREADY_BREACHED')).toContain('已经');
    expect(forecastResultLabel('CANNOT_PREDICT')).toContain('无法预测');
  });

  it('🔴 「无法预测」的色调不是危险色（它不是「要出事」，是「判不了」）', () => {
    expect(forecastResultTone('CANNOT_PREDICT')).toBe('neutral');
    expect(forecastResultTone('WILL_BREACH')).toBe('danger');
    expect(forecastResultTone('NO_BREACH')).toBe('success');
    expect(forecastResultTone('ALREADY_BREACHED')).toBe('warning');
  });

  it('越线档位的人话', () => {
    expect(breachLevelLabel('WARN')).toContain('警告档');
    expect(breachLevelLabel('CRITICAL')).toContain('严重档');
    expect(breachLevelLabel(null)).toBe('');
  });
});

describe('来源与阈值的人话', () => {
  it('🔴 手工录入一眼看得出，不冒充平台同步', () => {
    expect(sourceLabel('MANUAL')).toBe('人工录入');
    expect(sourceLabel('PLATFORM_SYNC')).toBe('平台同步');
    expect(sourceLabel(null)).toBe('未采集');
    expect(sourceLabel('MANUAL')).not.toBe(sourceLabel('PLATFORM_SYNC'));
  });

  it('阈值来源要说清用的是哪一条阈值', () => {
    expect(ruleSourceLabel('BUILTIN_DEFAULT')).toContain('内置默认');
    expect(ruleSourceLabel('TENANT_CUSTOM')).toContain('本模块');
    expect(ruleSourceLabel('UPSTREAM_TENANT_CUSTOM')).toContain('16.1.1.1');
  });

  it('方向与依据档位', () => {
    expect(directionLabel('ABOVE_IS_BAD')).toContain('高于');
    expect(directionLabel('BELOW_IS_BAD')).toContain('低于');
    expect(thresholdKindLabel('CRITICAL')).toBe('命中严重阈值');
    expect(thresholdKindLabel('WARN')).toBe('命中警告阈值');
    expect(thresholdKindLabel('UNKNOWN')).toBe('量不到');
  });
});

describe('三态空态', () => {
  it('三种空态的标题与说明各不相同', () => {
    const noShop = emptyState('NO_SHOP');
    const noFilter = emptyState('NO_FILTER_MATCH');
    const noSeries = emptyState('NO_SERIES');
    expect(noShop.title).not.toBe(noFilter.title);
    expect(noFilter.title).not.toBe(noSeries.title);
    expect(noShop.description).not.toBe(noFilter.description);
    expect(noFilter.description).not.toBe(noSeries.description);
  });

  it('空态说明里要点出各自的关键事实', () => {
    expect(emptyState('NO_SHOP').description).toContain('店铺');
    expect(emptyState('NO_FILTER_MATCH').description).toContain('筛选');
    expect(emptyState('NO_SERIES').description).toContain('未接入');
    expect(emptyState('NO_SERIES').description).toContain('人工录入');
  });
});

describe('空态的判定', () => {
  it('一个店铺都没有 ⇒ NO_SHOP', () => {
    expect(emptyStateKind(0, 0, false, false)).toBe('NO_SHOP');
  });

  it('🔴 带着筛选条件却一家店都搜不到 ⇒ NO_FILTER_MATCH（不是「没有店铺」）', () => {
    expect(emptyStateKind(0, 0, false, true)).toBe('NO_FILTER_MATCH');
  });

  it('有店铺但筛选后没有序列，且租户里本来就没有任何指标 ⇒ NO_SERIES', () => {
    expect(emptyStateKind(3, 0, false, false)).toBe('NO_SERIES');
  });

  it('有店铺有指标，但筛选把它们都排除了 ⇒ NO_FILTER_MATCH（不是「还没有指标」）', () => {
    expect(emptyStateKind(3, 0, true, true)).toBe('NO_FILTER_MATCH');
  });

  it('有店铺有序列 ⇒ 不是空态', () => {
    expect(emptyStateKind(3, 3, true, false)).toBe('NO_FILTER_MATCH');
  });
});

describe('白名单前缀', () => {
  it('🔴 用独立前缀，不与 16.1.1.1 的账号健康混', () => {
    expect(BASE).toBe('channel/performance-metrics');
    expect(BASE).not.toContain('account-health');
    expect(BASE).not.toContain('compliance-precheck');
  });
});
