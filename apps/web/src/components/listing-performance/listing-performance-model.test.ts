import { describe, expect, it } from 'vitest';
import {
  changeDirection,
  changeText,
  comparisonEntryOf,
  emptyDetailMessage,
  emptySnapshotsMessage,
  formatAmount,
  formatCount,
  formatMetric,
  formatRate,
  formatScore,
  metricLabel,
  metricUnit,
  pageSummary,
  periodLabel,
  sourceBanner,
  trendSeries,
  unavailableDimensions,
  METRIC_OPTIONS,
  type ListingPerformanceCapabilities,
  type ListingPerformanceComparisonEntry,
  type ListingPerformanceDetail,
  type ListingPerformanceTrendPoint,
} from './listing-performance-model';

/**
 * 4.5.1.1 Listing 表现 前端模型单测。
 *
 * 🔴 这里钉住的都是**页面上最容易做丢**的几件事：
 *   1. null 比率**绝不**渲染成 0%（那是结论，不是缺口）
 *   2. 上期为 0 时变化率**绝不**渲染成 0% 或 +∞
 *   3. 两种空态文案**不同**（还没同步过 vs 同步过但没数据）
 *   4. 趋势没有数据时是**空数组**，不是平线
 *   5. 未交付的下钻显式列出来，不假装具备
 */

const entry = (
  metric: string,
  current: number | null,
  previous: number | null,
  change: number | null,
  changeRate: number | null,
  unit: ListingPerformanceComparisonEntry['unit'] = 'COUNT',
): ListingPerformanceComparisonEntry => ({
  metric,
  current,
  previous,
  change,
  changeRate,
  unit,
});

describe('指标格式化', () => {
  it('🔴 null 比率显示成「—」，绝不显示成 0%', () => {
    expect(formatRate(null)).toBe('—');
    expect(formatRate(undefined)).toBe('—');
    expect(formatRate(Number.NaN)).toBe('—');
  });

  it('比率按百分数显示（0.05 ⇒ 5.00%）', () => {
    expect(formatRate(0.05)).toBe('5.00%');
    expect(formatRate(0.1)).toBe('10.00%');
    expect(formatRate(0)).toBe('0.00%');
    expect(formatRate(-0.0234)).toBe('-2.34%');
  });

  it('数量按量级自适应：整数带千分位，小数保留两位', () => {
    expect(formatCount(3000)).toBe('3,000');
    expect(formatCount(0)).toBe('0');
    expect(formatCount(null)).toBe('—');
    expect(formatCount(12.3456)).toBe('12.35');
  });

  it('金额两位小数 + 币种', () => {
    expect(formatAmount(3000, 'USD')).toBe('3,000.00 USD');
    expect(formatAmount(3000)).toBe('3,000.00');
    expect(formatAmount(null, 'USD')).toBe('—');
  });

  it('评分两位小数；null 是「—」', () => {
    expect(formatScore(4.6)).toBe('4.60');
    expect(formatScore(null)).toBe('—');
  });

  it('按单位分派到对应格式化', () => {
    expect(formatMetric(0.05, 'RATE')).toBe('5.00%');
    expect(formatMetric(1000, 'AMOUNT', 'USD')).toBe('1,000.00 USD');
    expect(formatMetric(4.6, 'SCORE')).toBe('4.60');
    expect(formatMetric(3000, 'COUNT')).toBe('3,000');
    expect(formatMetric(null, 'RATE')).toBe('—');
  });
});

describe('变化方向与文案', () => {
  it('上期为 0 ⇒ 变化率是 null ⇒ 方向是 unknown、文案带「上期为 0」', () => {
    const e = entry('impressions', 1000, 0, 1000, null);
    expect(changeDirection(e)).toBe('unknown');
    expect(changeText(e)).toBe('—（上期为 0）');
  });

  it('🔴 上期为 0 时绝不显示 0% 或 +∞', () => {
    const e = entry('impressions', 1000, 0, 1000, null);
    expect(changeText(e)).not.toContain('0%');
    expect(changeText(e)).not.toContain('∞');
  });

  it('上升 / 下降 / 持平三种方向', () => {
    expect(changeDirection(entry('clicks', 150, 120, 30, 0.25))).toBe('up');
    expect(changeDirection(entry('clicks', 120, 150, -30, -0.2))).toBe('down');
    expect(changeDirection(entry('clicks', 150, 150, 0, 0))).toBe('flat');
  });

  it('变化文案带方向箭头与百分比', () => {
    expect(changeText(entry('clicks', 150, 120, 30, 0.25))).toBe('↑ 25.00%');
    expect(changeText(entry('clicks', 120, 150, -30, -0.2))).toBe('↓ 20.00%');
    expect(changeText(entry('clicks', 150, 150, 0, 0))).toBe('持平（0.00%）');
  });

  it('没有对比项时是「—」', () => {
    expect(changeDirection(undefined)).toBe('unknown');
    expect(changeText(undefined)).toBe('—');
  });

  it('能从对比表里按指标键取项', () => {
    const comparison = { entries: [entry('clicks', 150, 120, 30, 0.25)] };
    expect(comparisonEntryOf(comparison, 'clicks')?.current).toBe(150);
    expect(comparisonEntryOf(comparison, 'ctr')).toBeUndefined();
    expect(comparisonEntryOf(undefined, 'clicks')).toBeUndefined();
  });
});

describe('指标元数据', () => {
  it('每个指标键都有中文名与单位', () => {
    for (const option of METRIC_OPTIONS) {
      expect(metricLabel(option.value)).toBe(option.label);
      expect(metricUnit(option.value)).toBe(option.unit);
    }
  });

  it('未知指标键原样返回（不猜）', () => {
    expect(metricLabel('whatever')).toBe('whatever');
    expect(metricUnit('whatever')).toBe('COUNT');
  });
});

describe('区间与分页', () => {
  it('区间文案', () => {
    expect(periodLabel('2026-10-01', '2026-10-03')).toBe('2026-10-01 ~ 2026-10-03');
    expect(periodLabel('', '')).toBe('未指定区间');
  });

  it('真分页摘要用的是服务端给的 total', () => {
    expect(pageSummary({ page: 0, size: 20, total: 0 })).toBe('第 0-0 条 / 共 0 条');
    expect(pageSummary({ page: 0, size: 20, total: 45 })).toBe('第 1-20 条 / 共 45 条');
    expect(pageSummary({ page: 2, size: 20, total: 45 })).toBe('第 41-45 条 / 共 45 条');
  });
});

describe('空态', () => {
  it('🔴 两种空态文案不同：还没同步过 vs 同步过但没数据', () => {
    const never = emptySnapshotsMessage(0);
    const filtered = emptySnapshotsMessage(3);
    expect(never).toContain('还没有平台表现数据的同步结果');
    expect(never).not.toBe(filtered);
    expect(filtered).toContain('3');
    expect(filtered).toContain('没有平台表现数据');
  });

  it('空态文案点明「平台口径」与「不从内部订单聚合」', () => {
    expect(emptySnapshotsMessage(0)).toContain('平台口径');
    expect(emptySnapshotsMessage(0)).toContain('不从内部订单聚合');
  });

  it('明细空态优先用服务端写明的 emptyReason', () => {
    const detail = {
      emptyReason: '这个商品还没有平台表现数据的同步结果',
    } as ListingPerformanceDetail;
    expect(emptyDetailMessage(detail)).toBe('这个商品还没有平台表现数据的同步结果');
    expect(emptyDetailMessage(null)).toBe('');
  });
});

describe('趋势序列', () => {
  const points: ListingPerformanceTrendPoint[] = [
    {
      bucket: '2026-10-01',
      impressions: 1000,
      clicks: 50,
      ctr: 0.05,
      sessions: 200,
      unitsOrdered: 20,
      salesAmount: 1000,
      conversionRate: 0.1,
      returnsCount: 2,
      returnAmount: 100,
      returnRate: 0.1,
      rating: 4.5,
      ratingCount: 10,
    },
    {
      bucket: '2026-10-02',
      impressions: 0,
      clicks: 0,
      ctr: null,
      sessions: 0,
      unitsOrdered: 0,
      salesAmount: 0,
      conversionRate: null,
      returnsCount: 0,
      returnAmount: 0,
      returnRate: null,
      rating: null,
      ratingCount: null,
    },
  ];

  it('🔴 没有数据时是空数组（不画平线）', () => {
    expect(trendSeries([], 'impressions')).toEqual([]);
  });

  it('按指标键取序列值', () => {
    const series = trendSeries(points, 'impressions');
    expect(series).toHaveLength(2);
    expect(series[0]).toEqual({ bucket: '2026-10-01', value: 1000 });
    expect(series[1]?.value).toBe(0);
  });

  it('🔴 分母为 0 的比率保留为 null（断点），不变成 0', () => {
    const series = trendSeries(points, 'ctr');
    expect(series[0]?.value).toBe(0.05);
    expect(series[1]?.value).toBeNull();
  });
});

describe('能力声明与来源标注', () => {
  const caps = {
    dimensions: [
      { dimension: 'metric_aggregation', description: '', available: true, source: '', ownerModule: '', impact: '' },
      { dimension: 'product_performance_drilldown', description: '', available: false, source: '', ownerModule: '13 产品表现', impact: '' },
      { dimension: 'ad_attribution', description: '', available: false, source: '', ownerModule: '14 广告', impact: '' },
    ],
    sources: [],
    ingestChannels: [],
    granularities: [],
    metricDefinitions: '',
    dataSourceNote: '平台口径（平台回报数据，非内部订单）',
    relationNote: '',
  } as ListingPerformanceCapabilities;

  it('只挑出未交付的维度（13 / 14）', () => {
    const missing = unavailableDimensions(caps);
    expect(missing.map((d) => d.dimension)).toEqual([
      'product_performance_drilldown',
      'ad_attribution',
    ]);
    expect(missing.every((d) => d.ownerModule !== '')).toBe(true);
  });

  it('能力表还没加载时是空数组（不炸）', () => {
    expect(unavailableDimensions(null)).toEqual([]);
  });

  it('🔴 来源标注必须点明「平台口径」与「非内部订单」', () => {
    const banner = sourceBanner(caps);
    expect(banner).toContain('平台口径');
    expect(banner).toContain('非内部订单');
    expect(sourceBanner(null)).toContain('平台口径');
  });
});
