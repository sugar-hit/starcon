import { describe, expect, it } from 'vitest';
import {
  ALERT_LABELS,
  HEALTH_LEVEL_LABELS,
  METRIC_LABELS,
  SERVICE_LABELS,
  alertAction,
  describeAlert,
  describeHealth,
  describeMetric,
  formatByUnit,
  formatMilliseconds,
  formatQuantity,
  formatRatio,
  formatTimestamp,
  healthLevel,
  overviewCards,
  sortAlerts,
  toThresholdDraft,
  validateThresholdInput,
  type AlertLike,
  type HealthLike,
  type MetricLike,
} from './platform-ops-model';

/**
 * 17.7.2.2 平台运维视图的展示口径。
 *
 * <p>本模块最要紧的一条纪律是**「没数据」与「指标是 0」必须分开说**：后端在没有
 * 采样时返回 {@code null}，前端必须显示「—」而不是 0。把它显示成 0 等于把
 * 「没采到」说成「一切正常」—— 这是把缺口说成了事实。
 */

function health(overrides: Partial<HealthLike> = {}): HealthLike {
  return {
    tenantId: '11111111-1111-1111-1111-111111111111',
    tenantCode: 'T1',
    tenantName: '租户一',
    tenantStatus: 'ACTIVE',
    online: true,
    errorRate: 0.001,
    taskBacklog: 3,
    queueDepth: 0,
    storageUtilization: 0.4,
    availability: 0.999,
    latencyMs: 120,
    costEstimate: 12.5,
    openAlertCount: 0,
    criticalAlertCount: 0,
    lastSampleAt: '2026-10-23T08:00:00Z',
    ...overrides,
  };
}

function alert(overrides: Partial<AlertLike> = {}): AlertLike {
  return {
    id: '22222222-2222-2222-2222-222222222222',
    tenantId: '11111111-1111-1111-1111-111111111111',
    alertCode: 'ERROR_RATE_HIGH',
    metricCode: 'ERROR_RATE',
    serviceCode: 'identity',
    severity: 'WARNING',
    observedValue: 0.02,
    thresholdValue: 0.01,
    status: 'OPEN',
    raisedAt: '2026-10-23T08:00:00Z',
    acknowledgedAt: null,
    resolvedAt: null,
    ...overrides,
  };
}

describe('数量与比率的呈现', () => {
  it('没有值一律显示「—」，绝不补 0', () => {
    expect(formatQuantity(null)).toBe('—');
    expect(formatQuantity(undefined)).toBe('—');
    expect(formatQuantity('')).toBe('—');
    expect(formatRatio(null)).toBe('—');
    expect(formatMilliseconds(null)).toBe('—');
    expect(formatTimestamp(null)).toBe('—');
    expect(formatTimestamp('not-a-time')).toBe('—');
  });

  it('0 是真的 0，不能显示成「—」', () => {
    expect(formatQuantity(0)).toBe('0');
    expect(formatRatio(0)).toBe('0.00%');
  });

  it('整数不带小数点，带小数的保留两位', () => {
    expect(formatQuantity(12)).toBe('12');
    expect(formatQuantity(12.5)).toBe('12.50');
    expect(formatQuantity('1500')).toBe('1500');
  });

  it('比率按百分比呈现', () => {
    expect(formatRatio(0.0123)).toBe('1.23%');
    expect(formatRatio(0.99)).toBe('99.00%');
  });

  it('毫秒带单位', () => {
    expect(formatMilliseconds(1250)).toBe('1250 ms');
  });

  it('按量纲选口径：在线状态显示在线 / 离线', () => {
    expect(formatByUnit(1, 'STATE')).toBe('在线');
    expect(formatByUnit(0, 'STATE')).toBe('离线');
    expect(formatByUnit(0.5, 'RATIO')).toBe('50.00%');
    expect(formatByUnit(7, 'COUNT')).toBe('7');
    expect(formatByUnit(12, 'MILLISECOND')).toBe('12 ms');
  });
});

describe('健康档位', () => {
  it('没有采样是「无数据」，不是「正常」', () => {
    const row = health({ online: null, lastSampleAt: null });
    expect(healthLevel(row)).toBe('UNKNOWN');
    expect(HEALTH_LEVEL_LABELS[healthLevel(row)]).toBe('无数据');
  });

  it('严重告警压过一切', () => {
    const row = health({ openAlertCount: 1, criticalAlertCount: 1 });
    expect(healthLevel(row)).toBe('CRITICAL');
  });

  it('有未闭环告警是警告', () => {
    expect(healthLevel(health({ openAlertCount: 1 }))).toBe('WARNING');
  });

  it('离线是严重', () => {
    expect(healthLevel(health({ online: false }))).toBe('CRITICAL');
  });

  it('错误率越线升级', () => {
    expect(healthLevel(health({ errorRate: 0.02 }))).toBe('WARNING');
    expect(healthLevel(health({ errorRate: 0.06 }))).toBe('CRITICAL');
  });

  it('可用性掉下来是警告', () => {
    expect(healthLevel(health({ availability: 0.98 }))).toBe('WARNING');
  });

  it('一切正常', () => {
    expect(healthLevel(health())).toBe('OK');
  });
});

describe('可读摘要', () => {
  it('没有采样的项显示「—」，不显示 0', () => {
    const text = describeHealth(
      health({ errorRate: null, taskBacklog: null, storageUtilization: null }),
    );
    expect(text).toContain('错误率 —');
    expect(text).toContain('积压 —');
    expect(text).toContain('存储 —');
    expect(text).not.toContain('积压 0');
  });

  it('在线状态未知与离线要分开说', () => {
    expect(describeHealth(health({ online: null }))).toContain('在线状态未知');
    expect(describeHealth(health({ online: false }))).toContain('离线');
  });

  it('告警数出现在摘要里', () => {
    expect(describeHealth(health({ openAlertCount: 2 }))).toContain('2 条未闭环告警');
    expect(describeHealth(health({ openAlertCount: 2, criticalAlertCount: 1 }))).toContain(
      '1 条严重告警',
    );
  });

  it('指标摘要带上指标名、服务与数值', () => {
    const metric: MetricLike = {
      id: 'i',
      tenantId: 't',
      serviceCode: 'identity',
      metricCode: 'ERROR_RATE',
      sampleValue: 0.02,
      unit: 'RATIO',
      severity: 'WARNING',
      windowStart: '2026-10-23T00:00:00Z',
      windowEnd: '2026-10-23T01:00:00Z',
      source: 'PUSH',
      createdAt: '2026-10-23T01:00:00Z',
    };
    const text = describeMetric(metric);
    expect(text).toContain(METRIC_LABELS.ERROR_RATE);
    expect(text).toContain(SERVICE_LABELS.identity);
    expect(text).toContain('2.00%');
  });

  it('告警摘要带上观测值与阈值', () => {
    const text = describeAlert(alert());
    expect(text).toContain(ALERT_LABELS.ERROR_RATE_HIGH);
    expect(text).toContain('观测 0.02');
    expect(text).toContain('阈值 0.01');
  });
});

describe('告警动作', () => {
  it('待处理可以确认，已确认可以闭环，已闭环没有动作', () => {
    expect(alertAction(alert({ status: 'OPEN' }))).toBe('acknowledge');
    expect(alertAction(alert({ status: 'ACKNOWLEDGED' }))).toBe('resolve');
    expect(alertAction(alert({ status: 'RESOLVED' }))).toBeNull();
  });

  it('排序：未闭环优先，再按严重度，再按时间倒序', () => {
    const rows = [
      alert({ id: 'a', status: 'RESOLVED', raisedAt: '2026-10-23T10:00:00Z' }),
      alert({ id: 'b', status: 'OPEN', severity: 'WARNING', raisedAt: '2026-10-23T09:00:00Z' }),
      alert({ id: 'c', status: 'OPEN', severity: 'CRITICAL', raisedAt: '2026-10-23T08:00:00Z' }),
      alert({ id: 'd', status: 'ACKNOWLEDGED', raisedAt: '2026-10-23T11:00:00Z' }),
    ];
    expect(sortAlerts(rows).map((row) => row.id)).toEqual(['c', 'b', 'd', 'a']);
  });
});

describe('概览卡片', () => {
  it('空值显示「—」，不补 0', () => {
    const cards = overviewCards({
      tenantCount: 3,
      onlineTenantCount: 1,
      offlineTenantCount: 1,
      unknownTenantCount: 1,
      avgErrorRate: null,
      totalTaskBacklog: null,
      totalCostEstimate: null,
      openAlertCount: 0,
      criticalAlertCount: 0,
      tenantWithSamples: 1,
    });
    expect(cards.find((card) => card.label === '平均错误率')?.value).toBe('—');
    expect(cards.find((card) => card.label === '任务积压')?.value).toBe('—');
    expect(cards.find((card) => card.label === '成本估算')?.value).toBe('—');
  });

  it('成本卡片的提示写明「不是金额」', () => {
    const cards = overviewCards({
      tenantCount: 1,
      onlineTenantCount: 1,
      offlineTenantCount: 0,
      unknownTenantCount: 0,
      avgErrorRate: 0.01,
      totalTaskBacklog: 5,
      totalCostEstimate: 10.5,
      openAlertCount: 1,
      criticalAlertCount: 0,
      tenantWithSamples: 1,
    });
    const cost = cards.find((card) => card.label === '成本估算');
    expect(cost?.value).toBe('10.50');
    expect(cost?.hint).toContain('不是金额');
  });
});

describe('阈值预校验', () => {
  it('GT 方向要求告警阈值小于严重阈值', () => {
    expect(
      validateThresholdInput({
        warnThreshold: '0.01',
        criticalThreshold: '0.05',
        comparison: 'GT',
        enabled: true,
      }),
    ).toBe('');
    expect(
      validateThresholdInput({
        warnThreshold: '0.05',
        criticalThreshold: '0.01',
        comparison: 'GT',
        enabled: true,
      }),
    ).toContain('必须小于严重阈值');
  });

  it('LT 方向要求严重阈值小于告警阈值', () => {
    expect(
      validateThresholdInput({
        warnThreshold: '0.99',
        criticalThreshold: '0.95',
        comparison: 'LT',
        enabled: true,
      }),
    ).toBe('');
    expect(
      validateThresholdInput({
        warnThreshold: '0.95',
        criticalThreshold: '0.99',
        comparison: 'LT',
        enabled: true,
      }),
    ).toContain('严重阈值必须小于告警阈值');
  });

  it('空值与非数值都要拦下来', () => {
    expect(
      validateThresholdInput({
        warnThreshold: '',
        criticalThreshold: '1',
        comparison: 'GT',
        enabled: true,
      }),
    ).toContain('不能为空');
    expect(
      validateThresholdInput({
        warnThreshold: 'abc',
        criticalThreshold: '1',
        comparison: 'GT',
        enabled: true,
      }),
    ).toContain('数值');
    expect(
      validateThresholdInput({
        warnThreshold: '-1',
        criticalThreshold: '1',
        comparison: 'GT',
        enabled: true,
      }),
    ).toContain('负数');
  });

  it('比较方向只能是 GT 或 LT', () => {
    expect(
      validateThresholdInput({
        warnThreshold: '1',
        criticalThreshold: '2',
        comparison: 'EQ',
        enabled: true,
      }),
    ).toContain('GT 或 LT');
  });

  it('草稿初值来自后端一行', () => {
    expect(
      toThresholdDraft({
        warnThreshold: 0.01,
        criticalThreshold: 0.05,
        comparison: 'GT',
        enabled: true,
      }),
    ).toEqual({
      warnThreshold: '0.01',
      criticalThreshold: '0.05',
      comparison: 'GT',
      enabled: true,
    });
  });
});
