import { describe, expect, it } from 'vitest';
import {
  alignmentDifferenceNote,
  alignmentLabel,
  amountText,
  amountsText,
  countText,
  coverageTone,
  defaultRange,
  emptyReasonLabel,
  emptyText,
  heatCellClass,
  heatRow,
  heatTier,
  hourBucketOf,
  levelLabel,
  levelTone,
  pageState,
  peakRankValueText,
  percentText,
  qualifiedLabel,
  qualifiedTone,
  quantityText,
  rangeText,
  rankingMetricNote,
  refreshStatusLabel,
  refreshStatusTone,
  shopFilterText,
  shopTimezoneText,
  slotRangeText,
  statusLabel,
  timezoneStatusLabel,
  timezoneTone,
  unattributedText,
  weekdayBucketOf,
  weekdayLabel,
} from './time-pattern-model';
import type {
  TimePatternCell,
  TimePatternCoverage,
  TimePatternHourBucket,
  TimePatternPeakRank,
  TimePatternResponse,
  TimePatternShopView,
  TimePatternUnattributed,
  TimePatternWeekdayBucket,
} from './time-pattern-model';

/**
 * 13.2.1.2 分时与分周模式前端模型的单元测试。
 *
 * <p>本文件盯的是**页面最容易做丢的那几件事**：
 *
 * <ul>
 *   <li>🔴 **时区是命门**：热力图的分档用的是**服务端给的 P90 刻度**，
 *       前端不自己算「最大值」来重新定义什么叫高峰；
 *   <li>🔴 **两个汇总口径必须区分**：本地时间对齐 vs UTC 槽位对齐，
 *       页面上的文案不能把两者说成一个；
 *   <li>「算不出来」≠「等于 0」：{@code null} 一律显示成「—」/「不可比」，**不显示 0**；
 *   <li>多币种绝不相加：空币种 = 与币种无关，**不是「币种未知」**；
 *   <li>定不了时区的店铺与订单**必须被说出来**，不许静默丢掉；
 *   <li>「读不到」与「没有数据」是**两个不同的态**。
 * </ul>
 */

// ================================================================ 夹具

function cell(overrides: Partial<TimePatternCell> = {}): TimePatternCell {
  return {
    weekday: 4,
    weekdayLabel: '周四',
    hour: 19,
    hourLabel: '19:00',
    orderCount: 2,
    unitSold: 3,
    salesAmounts: [{ currency: 'USD', value: 100 }],
    orderShare: 25,
    p90Peak: true,
    ...overrides,
  };
}

function response(overrides: Partial<TimePatternResponse> = {}): TimePatternResponse {
  return {
    requestedFrom: '2026-10-01',
    requestedTo: '2026-10-07',
    from: '2026-10-01',
    to: '2026-10-07',
    alignment: 'LOCAL_TIME',
    alignmentLabel: '店铺本地时间对齐',
    alignmentNote: '🔴 当前口径 = 店铺本地时间对齐',
    shopCodes: [],
    heatmap: [],
    hourDistribution: [],
    weekdayDistribution: [],
    slots: [],
    peakRanking: [],
    rankingMetricCode: 'ORDER_COUNT',
    rankingMetricLabel: '成交订单数',
    p90OrderCount: 0.7,
    shops: [],
    unattributed: {
      orderCount: 0,
      unitSold: 0,
      salesAmounts: [],
      reason: '',
      shopCodes: [],
    },
    coverage: { projectedOrderCount: 0, projectedDays: 0, expectedDays: 0, note: '' },
    metricDefinitions: [],
    dataUpdatedAt: '',
    dataUpdatedNote: '',
    status: 'PROJECTED',
    emptyReason: '',
    emptyNote: '',
    warnings: [],
    ...overrides,
  };
}

// ================================================================ 格式化

describe('格式化：null 绝不用 0 顶替', () => {
  it('笔数为 null 时是「—」，不是 0', () => {
    expect(countText(null)).toBe('—');
    expect(countText(0)).toBe('0');
  });

  it('件数为 null 时是「—」，不是 0', () => {
    expect(quantityText(null)).toBe('—');
    expect(quantityText(1.5)).toBe('1.500');
  });

  it('占比为 null 时是「不可比」，不是 0%', () => {
    expect(percentText(null)).toBe('不可比');
    expect(percentText(25)).toBe('25.00%');
  });

  it('金额为 null 时是「—」，不是 0', () => {
    expect(amountText(null, 'USD')).toBe('—');
    expect(amountText(100, 'USD')).toBe('100.00 USD');
  });
});

describe('格式化：多币种绝不相加', () => {
  it('空币种 = 与币种无关，不是「币种未知」', () => {
    expect(amountText(10, '')).toBe('10.00（与币种无关）');
  });

  it('多个币种分别列出，绝不合并成一个数', () => {
    const text = amountsText([
      { currency: 'USD', value: 100 },
      { currency: 'EUR', value: 300 },
    ]);
    expect(text).toBe('100.00 USD / 300.00 EUR');
    // 🔴 绝不出现 400（两个币种加在一起）。
    expect(text).not.toContain('400');
  });

  it('没有金额时是「—」', () => {
    expect(amountsText([])).toBe('—');
  });
});

// ================================================================ 热力图刻度

describe('热力图刻度：用的是服务端给的 P90，不是前端自己算的最大值', () => {
  it('达到 P90 刻度的是 peak 档', () => {
    expect(heatTier(cell({ orderCount: 2 }), 0.7)).toBe('peak');
  });

  it('有单但没到 P90 刻度的是 active 档', () => {
    expect(heatTier(cell({ orderCount: 0.5 }), 0.7)).toBe('active');
  });

  it('没有事实的是 empty 档（真的 0）', () => {
    expect(heatTier(cell({ orderCount: 0 }), 0.7)).toBe('empty');
  });

  it('P90 刻度为 null 时不判 peak（没有刻度就没有高峰）', () => {
    expect(heatTier(cell({ orderCount: 999 }), null)).toBe('active');
  });

  it('不同档位的底色不同', () => {
    expect(heatCellClass('peak')).not.toBe(heatCellClass('active'));
    expect(heatCellClass('empty')).not.toBe(heatCellClass('active'));
  });

  it('按星期几取出一整行 24 格', () => {
    const heatmap = [
      cell({ weekday: 4, hour: 0 }),
      cell({ weekday: 4, hour: 1 }),
      cell({ weekday: 5, hour: 0 }),
    ];
    const row = heatRow(heatmap, 4);
    expect(row).toHaveLength(2);
    expect(row.every((one) => one.weekday === 4)).toBe(true);
  });
});

// ================================================================ 汇总口径

describe('汇总口径：两个口径必须说得清区别', () => {
  it('本地时间对齐与 UTC 槽位对齐是**两个不同的标签**', () => {
    expect(alignmentLabel('LOCAL_TIME')).toBe('店铺本地时间对齐');
    expect(alignmentLabel('UTC_SLOT')).toBe('UTC 槽位对齐');
    expect(alignmentLabel('LOCAL_TIME')).not.toBe(alignmentLabel('UTC_SLOT'));
  });

  it('口径差异说明里必须出现「结果不同」与两个口径的名字', () => {
    const note = alignmentDifferenceNote();
    expect(note).toContain('结果不同');
    expect(note).toContain('UTC');
    expect(note).toContain('本地时间对齐');
  });
});

// ================================================================ 时段与层级

describe('时段层级与判定', () => {
  it('层级的中文标签', () => {
    expect(levelLabel('PEAK')).toBe('高峰');
    expect(levelLabel('SHOULDER')).toBe('平峰');
    expect(levelLabel('TROUGH')).toBe('低谷');
  });

  it('高峰与低谷的语义色不同', () => {
    expect(levelTone('PEAK')).not.toBe(levelTone('TROUGH'));
  });

  it('「不达标」不是错误 —— 语义色是中性，不是危险', () => {
    expect(qualifiedTone(false)).toBe('neutral');
    expect(qualifiedTone(true)).toBe('success');
    expect(qualifiedLabel(false)).toBe('未达标');
  });

  it('时段区间是左闭右开 —— 展示文本把两个端点都写出来', () => {
    expect(slotRangeText(9, 12)).toBe('09:00–12:00');
    expect(slotRangeText(18, 24)).toBe('18:00–24:00');
  });
});

// ================================================================ 店铺时区

describe('店铺时区：定不了的必须被说出来', () => {
  const resolved: TimePatternShopView = {
    shopCode: 'shop-a',
    shopName: '北美店',
    shopTimezone: 'America/Los_Angeles',
    timezoneStatus: 'RESOLVED',
    timezoneReason: '按站点 US 的默认时区统计',
  };
  const unresolved: TimePatternShopView = {
    shopCode: 'shop-d',
    shopName: '多站点店',
    shopTimezone: '',
    timezoneStatus: 'UNRESOLVED',
    timezoneReason: '启用了多个时区不同的站点',
  };

  it('已定的显示 IANA 标识', () => {
    expect(shopTimezoneText(resolved)).toBe('America/Los_Angeles');
  });

  it('定不了的不留空 —— 显示「定不了时区」', () => {
    // 🔴 留空会被读成「这家店没有时区信息」，而事实是「它定不了」。
    expect(shopTimezoneText(unresolved)).toBe('定不了时区');
    expect(shopTimezoneText(unresolved)).not.toBe('');
  });

  it('定不了时区是要处理的待办 —— 语义色是 warning', () => {
    expect(timezoneTone('UNRESOLVED')).toBe('warning');
    expect(timezoneTone('RESOLVED')).toBe('success');
    expect(timezoneStatusLabel('UNRESOLVED')).toBe('定不了时区');
  });

  it('店铺筛选的展示：空 = 全部店铺', () => {
    expect(shopFilterText([], [resolved])).toBe('全部店铺');
    expect(shopFilterText(['shop-a'], [resolved])).toBe('北美店（shop-a）');
  });
});

// ================================================================ 未定时的归属

describe('未能按店铺本地时分桶：必须单列', () => {
  const unattributed: TimePatternUnattributed = {
    orderCount: 2,
    unitSold: 5,
    salesAmounts: [{ currency: 'USD', value: 900 }],
    reason: '这些订单的店铺定不了时区 ⇒ 不进任何时段与格子',
    shopCodes: ['shop-c', 'shop-d'],
  };

  it('说明里要点名是哪几个店铺', () => {
    const text = unattributedText(unattributed);
    expect(text).toContain('shop-c');
    expect(text).toContain('shop-d');
    expect(text).toContain('不计入热力图的分母');
  });

  it('没有这类订单时返回空串（不渲染一块空区块）', () => {
    expect(unattributedText({ ...unattributed, shopCodes: [] })).toBe('');
    expect(unattributedText(null)).toBe('');
  });
});

// ================================================================ 覆盖度

describe('覆盖度：投影不满必须被看见', () => {
  const full: TimePatternCoverage = {
    projectedOrderCount: 10,
    projectedDays: 7,
    expectedDays: 7,
    note: '投影已覆盖整个窗口',
  };
  const partial: TimePatternCoverage = {
    projectedOrderCount: 3,
    projectedDays: 3,
    expectedDays: 7,
    note: '投影不满',
  };

  it('投影不满是 warning', () => {
    expect(coverageTone(full)).toBe('success');
    expect(coverageTone(partial)).toBe('warning');
  });
});

// ================================================================ 高峰排序

describe('高峰时段排序：排序口径要写出来', () => {
  const orderRank: TimePatternPeakRank = {
    rank: 1,
    slotCode: 'EVENING',
    displayName: '晚间',
    level: 'PEAK',
    levelLabel: '高峰',
    metricCode: 'ORDER_COUNT',
    metricLabel: '成交订单数',
    metricValue: 2,
    currency: '',
    orderShare: 25,
    qualified: true,
    reason: '达标',
  };
  const amountRank: TimePatternPeakRank = {
    ...orderRank,
    metricCode: 'SALES_AMOUNT',
    metricLabel: '成交金额',
    metricValue: 100,
    currency: 'USD',
  };

  it('订单数类指标的币种是空串 —— 与币种无关', () => {
    expect(peakRankValueText(orderRank)).toBe('2');
  });

  it('金额类指标带币种', () => {
    expect(peakRankValueText(amountRank)).toBe('100.00 USD');
  });

  it('排序依据是订单数时，说清楚它与币种无关', () => {
    const note = rankingMetricNote(response());
    expect(note).toContain('成交订单数');
    expect(note).toContain('与币种无关');
  });

  it('排序依据是金额时，说清楚按币种各自排名', () => {
    const note = rankingMetricNote(
      response({ rankingMetricCode: 'SALES_AMOUNT', rankingMetricLabel: '成交金额' }),
    );
    expect(note).toContain('按币种各自排名');
  });
});

// ================================================================ 分布桶

describe('分布桶', () => {
  const hour: TimePatternHourBucket = {
    hour: 19,
    hourLabel: '19:00',
    orderCount: 2,
    unitSold: 3,
    salesAmounts: [],
    orderShare: 25,
    p90Peak: true,
  };
  const weekday: TimePatternWeekdayBucket = {
    weekday: 4,
    weekdayLabel: '周四',
    orderCount: 2,
    unitSold: 3,
    salesAmounts: [],
    orderShare: 25,
  };

  it('按小时定位桶', () => {
    expect(hourBucketOf([hour], 19)).toBe(hour);
    expect(hourBucketOf([hour], 3)).toBeNull();
  });

  it('按星期定位桶', () => {
    expect(weekdayBucketOf([weekday], 4)).toBe(weekday);
    expect(weekdayBucketOf([weekday], 5)).toBeNull();
  });

  it('星期几的展示名以服务端给的为准', () => {
    const data = response({
      weekdayDistribution: [weekday],
    });
    expect(weekdayLabel(4, data)).toBe('周四');
    expect(weekdayLabel(9, data)).toBe('');
  });
});

// ================================================================ 页面状态

describe('页面状态：「读不到」与「没有数据」是两件事', () => {
  it('出错 ⇒ error', () => {
    expect(pageState(response(), 'boom')).toBe('error');
  });

  it('还没拿到数据 ⇒ loading', () => {
    expect(pageState(null, '')).toBe('loading');
  });

  it('还没投影 ⇒ empty', () => {
    expect(pageState(response({ status: 'NOT_PROJECTED' }), '')).toBe('empty');
  });

  it('这个窗口没有事实 ⇒ empty', () => {
    expect(pageState(response({ status: 'NO_FACTS_IN_WINDOW' }), '')).toBe('empty');
  });

  it('有投影 ⇒ ready', () => {
    expect(pageState(response(), '')).toBe('ready');
  });

  it('空态文案用服务端给的原话', () => {
    expect(emptyText(response({ emptyNote: '先刷新投影' }))).toBe('先刷新投影');
    expect(emptyReasonLabel('NOT_PROJECTED')).toContain('还没有投影');
    expect(emptyReasonLabel('NO_FACTS_IN_WINDOW')).toContain('没有分时事实');
  });

  it('状态标签', () => {
    expect(statusLabel('PROJECTED')).toBe('已投影');
    expect(statusLabel('NOT_PROJECTED')).toBe('未投影');
  });
});

// ================================================================ 刷新结果

describe('刷新结果', () => {
  it('截断是 warning（超出的部分没有算进来，必须看得见）', () => {
    expect(refreshStatusTone('TRUNCATED')).toBe('warning');
    expect(refreshStatusLabel('TRUNCATED')).toBe('已截断');
    expect(refreshStatusTone('FAILED')).toBe('danger');
    expect(refreshStatusTone('SUCCEEDED')).toBe('success');
  });
});

// ================================================================ 时间范围

describe('时间范围', () => {
  it('默认窗口是最近 30 天（含今天）', () => {
    const range = defaultRange('2026-10-27');
    expect(range.to).toBe('2026-10-27');
    expect(range.from).toBe('2026-09-28');
  });

  it('范围文本', () => {
    expect(rangeText('2026-10-01', '2026-10-07')).toBe('2026-10-01 ~ 2026-10-07');
  });
});
