import { describe, expect, it } from 'vitest';
import {
  BASE,
  emptyState,
  emptyStateKind,
  metricLabel,
  metricValueText,
  pendingKindLabel,
  riskLevelLabel,
  riskLevelTone,
  ruleSourceLabel,
  sourceLabel,
  thresholdKindLabel,
} from './account-health-model';

/**
 * 16.1.1.1 账号健康总览 前端模型的单测。
 *
 * 🔴 这组用例守的是四件最容易做丢的事：
 *
 * 1. **「量不到」不能显示成 0.00%** —— 那是把「没测到」写成「这项是 0」。
 * 2. **「判不了」是第三种状态**，且色调与「严重 / 预警 / 健康」都不同。
 * 3. **三态空态文案必须各不相同**（同一个空态换个图标不算三态）。
 * 4. **白名单前缀独立**，不与同卷兄弟（compliance-precheck / brand-authorizations）混。
 */

describe('指标值的展示口径', () => {
  it('比值渲染成百分比', () => {
    expect(metricValueText(0.028)).toBe('2.80%');
    expect(metricValueText(0.99)).toBe('99.00%');
    expect(metricValueText(0)).toBe('0.00%');
  });

  it('🔴 量不到显示成「未知」，绝不显示成 0.00%', () => {
    expect(metricValueText(null)).toBe('未知');
    expect(metricValueText(undefined)).toBe('未知');
    expect(metricValueText(null)).not.toBe('0.00%');
  });

  it('指标的人话名', () => {
    expect(metricLabel('ODR')).toBe('订单缺陷率（ODR）');
    expect(metricLabel('LATE_SHIPMENT_RATE')).toBe('迟发率');
    expect(metricLabel('CANCELLATION_RATE')).toBe('取消率');
    expect(metricLabel('VALID_TRACKING_RATE')).toBe('有效追踪率');
  });
});

describe('风险等级', () => {
  it('四种等级各有各的人话', () => {
    expect(riskLevelLabel('CRITICAL')).toContain('严重');
    expect(riskLevelLabel('WARNING')).toBe('预警');
    expect(riskLevelLabel('HEALTHY')).toBe('健康');
    expect(riskLevelLabel('UNKNOWN')).toContain('判不了');
  });

  it('🔴 「判不了」与「严重 / 预警 / 健康」的色调都不同', () => {
    expect(riskLevelTone('CRITICAL')).toBe('danger');
    expect(riskLevelTone('WARNING')).toBe('warning');
    expect(riskLevelTone('HEALTHY')).toBe('success');
    expect(riskLevelTone('UNKNOWN')).toBe('neutral');
    expect(riskLevelTone('UNKNOWN')).not.toBe(riskLevelTone('CRITICAL'));
    expect(riskLevelTone('UNKNOWN')).not.toBe(riskLevelTone('HEALTHY'));
  });
});

describe('来源与依据的人话', () => {
  it('🔴 手工录入一眼看得出，不冒充平台同步', () => {
    expect(sourceLabel('MANUAL')).toBe('人工录入');
    expect(sourceLabel('PLATFORM_SYNC')).toBe('平台同步');
    expect(sourceLabel(null)).toBe('未采集');
    expect(sourceLabel('MANUAL')).not.toBe(sourceLabel('PLATFORM_SYNC'));
  });

  it('阈值来源', () => {
    expect(ruleSourceLabel('BUILTIN_DEFAULT')).toBe('内置默认阈值');
    expect(ruleSourceLabel('TENANT_CUSTOM')).toBe('租户自定义阈值');
    expect(ruleSourceLabel('MIXED')).toBe('部分自定义 / 部分默认');
  });

  it('依据档位', () => {
    expect(thresholdKindLabel('CRITICAL')).toBe('命中严重阈值');
    expect(thresholdKindLabel('WARN')).toBe('命中警告阈值');
    expect(thresholdKindLabel('NONE')).toBe('未命中阈值');
    expect(thresholdKindLabel('UNKNOWN')).toBe('量不到');
  });

  it('需处理项的三种类型各自不同', () => {
    expect(pendingKindLabel('RISK_BREACH')).toBe('命中阈值');
    expect(pendingKindLabel('DATA_MISSING')).toBe('指标不全');
    expect(pendingKindLabel('PLATFORM_SYNC_UNAVAILABLE')).toBe('同步未接入');
  });
});

describe('三态空态', () => {
  it('三种空态的标题与说明各不相同', () => {
    const noShop = emptyState('NO_SHOP');
    const noFilter = emptyState('NO_FILTER_MATCH');
    const noMetric = emptyState('NO_METRIC');
    expect(noShop.title).not.toBe(noFilter.title);
    expect(noFilter.title).not.toBe(noMetric.title);
    expect(noShop.description).not.toBe(noFilter.description);
    expect(noFilter.description).not.toBe(noMetric.description);
  });

  it('空态说明里要点出各自的关键事实', () => {
    expect(emptyState('NO_SHOP').description).toContain('店铺');
    expect(emptyState('NO_FILTER_MATCH').description).toContain('筛选');
    expect(emptyState('NO_METRIC').description).toContain('未接入');
    expect(emptyState('NO_METRIC').description).toContain('人工录入');
  });
});

describe('空态的判定', () => {
  it('一个店铺都没有 ⇒ NO_SHOP', () => {
    expect(emptyStateKind(0, 0, false)).toBe('NO_SHOP');
  });

  it('有店铺但筛选后为空 ⇒ NO_FILTER_MATCH（不是「没有店铺」）', () => {
    expect(emptyStateKind(5, 0, true)).toBe('NO_FILTER_MATCH');
  });

  it('🔴 有店铺但一个指标都没量到 ⇒ NO_METRIC（不是「筛选无结果」）', () => {
    expect(emptyStateKind(3, 3, false)).toBe('NO_METRIC');
  });

  it('有店铺有指标 ⇒ 不是空态', () => {
    expect(emptyStateKind(3, 3, true)).toBe('NO_FILTER_MATCH');
  });
});

describe('白名单前缀', () => {
  it('🔴 用独立前缀，不与同卷兄弟混', () => {
    expect(BASE).toBe('channel/account-health');
    expect(BASE).not.toContain('compliance-precheck');
    expect(BASE).not.toContain('brand-authorizations');
  });
});
