import { describe, expect, it } from 'vitest';
import {
  amountText,
  availabilityLabel,
  availabilityTone,
  baselineText,
  changeDirectionLabel,
  changeRateText,
  changeText,
  changeTone,
  countText,
  currencyLabel,
  dataUpdatedNote,
  dataUpdatedText,
  defaultRange,
  dimensionLabel,
  dimensionOptions,
  dimensionScopeText,
  emptyReasonLabel,
  emptyText,
  metricUnitLabel,
  metricValueText,
  notDeliveredLines,
  notDeliveredOwnerText,
  pageState,
  peerAverageText,
  peerMedianText,
  quantityText,
  rankingPercentileText,
  rankingText,
  refreshStatusLabel,
  refreshStatusTone,
  ruleNotes,
  sectionAdCampaigns,
  sectionByCode,
  sectionCompetitors,
  sectionMetrics,
  sellerKindLabel,
  sellerKindTone,
  statusLabel,
  trendAmountsText,
  trendHasValues,
  upstreamModules,
} from './product-performance-model';
import type {
  PerformanceMetric,
  PerformanceMetricDefinitionView,
  PerformanceRanking,
  PerformanceSection,
  PerformanceTrendPoint,
  ProductPerformanceCapabilities,
  ProductPerformanceResponse,
} from './product-performance-model';

/**
 * 13.3.1.1 产品表现分析前端模型单测。
 *
 * <p>测的是**这一页最容易做丢的五件事**：
 * 「算不出来」≠「等于 0」、多币种绝不相加、环比由服务端算（前端不做业务算术）、
 * 「本轮不可用」要写明归谁管、页面状态要把「读不到」与「没有数据」分开。
 */

function metric(overrides: Partial<PerformanceMetric> = {}): PerformanceMetric {
  return {
    metricCode: 'PRODUCT_SALES_AMOUNT',
    displayName: '销售额',
    unit: 'CURRENCY',
    currency: 'USD',
    value: 100,
    baselineValue: 80,
    change: 20,
    changeRate: 25,
    availability: 'AVAILABLE',
    availabilityNote: '',
    sourceNote: '内部订单口径（9.1.1.1）',
    ...overrides,
  };
}

function ranking(overrides: Partial<PerformanceRanking> = {}): PerformanceRanking {
  return {
    rankMetricCode: 'PRODUCT_SALES_QUANTITY',
    rankMetricDisplayName: '销量',
    rank: 3,
    total: 20,
    percentile: 85,
    peerMedian: 12,
    peerAverage: 15.5,
    peerScope: '同层级（SPU）内按销量（件数）排名',
    availability: 'AVAILABLE',
    availabilityNote: '',
    ...overrides,
  };
}

function section(overrides: Partial<PerformanceSection> = {}): PerformanceSection {
  return {
    sectionCode: 'SALES',
    title: '销量与销售额',
    description: '内部订单口径（9.1.1.1 订单明细，经 13.2.2.2 投影）。',
    availability: 'AVAILABLE',
    availabilityNote: '',
    metrics: [metric()],
    competitors: [],
    adCampaigns: [],
    ...overrides,
  };
}

function response(overrides: Partial<ProductPerformanceResponse> = {}): ProductPerformanceResponse {
  return {
    from: '2026-10-01',
    to: '2026-10-30',
    baselineFrom: '2026-09-01',
    baselineTo: '2026-09-30',
    baselineType: 'PREVIOUS_PERIOD',
    dimensionType: 'SKU',
    dimensionId: 'sku-1',
    dimensionKey: 'SKU-1001',
    dimensionName: '黑色 T 恤',
    platformCodes: ['AMAZON'],
    listingIds: ['listing-1'],
    skuIds: ['sku-1'],
    skuCount: 1,
    listingCount: 1,
    sections: [section()],
    trend: [],
    ranking: ranking(),
    metricDefinitions: [],
    caliberNotes: [],
    timezones: ['UTC'],
    dataUpdatedAt: '2026-10-28T02:00:00Z',
    dataUpdatedNote: '投影读模型，与业务系统冲突时以业务系统为准。',
    status: 'PROJECTED',
    statusNote: '已投影',
    emptyReason: null,
    emptyNote: '',
    ...overrides,
  };
}

function capabilities(
  overrides: Partial<ProductPerformanceCapabilities> = {},
): ProductPerformanceCapabilities {
  return {
    upstreamConnected: true,
    analysisAvailable: true,
    dataUpdatedAtVisible: true,
    upstreamModules: ['9 订单（trade）'],
    notDelivered: ['🔴 商品维度 ACOS 本轮不可用（归 14.3.1.3 管）'],
    rules: [{ ruleCode: 'ONE_CALL', description: '一次性拉齐' }],
    ...overrides,
  };
}

// ================================================================ 「算不出来」≠「等于 0」

describe('「算不出来」与「等于 0」必须分开', () => {
  it('null 取值渲染成「—」，绝不用 0 顶替', () => {
    expect(quantityText(null)).toBe('—');
    expect(amountText(null, 'USD')).toBe('—');
    expect(countText(null)).toBe('—');
  });

  it('null 百分比渲染成「不可比」，不是 0%', () => {
    expect(metricValueText(metric({ unit: 'PERCENT', value: null }), 'PERCENT')).toBe('不可比');
  });

  it('真实取值照常显示', () => {
    expect(quantityText(12.5)).toBe('12.500');
    expect(amountText(100, 'USD')).toBe('100.00 USD');
    expect(countText(42)).toBe('42');
  });
});

describe('按单位分派的取值渲染', () => {
  it('金额带币种，件数不带', () => {
    expect(metricValueText(metric({ unit: 'CURRENCY' }), 'CURRENCY')).toBe('100.00 USD');
    expect(metricValueText(metric({ unit: 'QUANTITY', currency: '' }), 'QUANTITY')).toBe('100.000');
  });

  it('百分比走「不可比」而不是 0%', () => {
    expect(metricValueText(metric({ unit: 'PERCENT', value: null }), 'PERCENT')).toBe('不可比');
    expect(metricValueText(metric({ unit: 'PERCENT', value: 12.5 }), 'PERCENT')).toBe('12.50%');
  });

  it('单位标签有中文名', () => {
    expect(metricUnitLabel('CURRENCY')).toBe('金额');
    expect(metricUnitLabel('COUNT')).toBe('次数');
    expect(metricUnitLabel('QUANTITY')).toBe('数量');
    expect(metricUnitLabel('PERCENT')).toBe('百分比');
  });
});

// ================================================================ 多币种

describe('多币种绝不相加', () => {
  it('空币种 = 与币种无关，不是「币种未知」', () => {
    expect(currencyLabel('')).toBe('与币种无关');
    expect(currencyLabel('USD')).toBe('USD');
  });

  it('与币种无关的量显示成「（与币种无关）」', () => {
    expect(amountText(100, '')).toBe('100.00（与币种无关）');
  });

  it('趋势金额按币种分别列出，不给合计', () => {
    const text = trendAmountsText([
      { currency: 'USD', amount: 100 },
      { currency: 'EUR', amount: 80 },
    ]);
    expect(text).toBe('100.00 USD / 80.00 EUR');
    expect(text).not.toContain('180');
  });

  it('没有金额时是「—」，不是 0', () => {
    expect(trendAmountsText([])).toBe('—');
  });
});

// ================================================================ 可用性 / 未交付

describe('「本轮不可用」要写明归谁管', () => {
  it('三种「拿不到」是三件事，不混为一谈', () => {
    expect(availabilityLabel('AVAILABLE')).toBe('有数据');
    expect(availabilityLabel('NO_DATA')).toBe('没有数据');
    expect(availabilityLabel('NOT_COMPARABLE')).toBe('不可比');
    expect(availabilityLabel('NOT_DELIVERED')).toBe('本轮不可用');
  });

  it('NO_DATA 与 NOT_COMPARABLE 都是中性色（是如实说明，不是错误）', () => {
    expect(availabilityTone('NO_DATA')).toBe('neutral');
    expect(availabilityTone('NOT_COMPARABLE')).toBe('neutral');
  });

  it('NOT_DELIVERED 是提醒色（上游没交付，值得提醒）', () => {
    expect(availabilityTone('NOT_DELIVERED')).toBe('warning');
  });

  it('归属模块写清楚是归谁管', () => {
    const definition: PerformanceMetricDefinitionView = {
      metricCode: 'PRODUCT_AD_ACOS',
      displayName: '商品维度 ACOS',
      unit: 'PERCENT',
      aggregation: 'DERIVED_RATIO',
      numeratorMetricCode: 'PRODUCT_AD_SPEND',
      denominatorMetricCode: 'PRODUCT_AD_SALES_AMOUNT',
      currencyScope: 'SAME',
      currencyAware: true,
      available: false,
      ownerModule: '14.3.1.3',
      description: '',
      availabilityNote: 'channel 的广告报表没有商品维度分组。',
    };
    expect(notDeliveredOwnerText(definition)).toBe('归 14.3.1.3 管');
    expect(notDeliveredOwnerText({ ...definition, ownerModule: '' })).toBe('');
  });
});

// ================================================================ 环比（服务端算好的）

describe('环比是服务端算好的，前端只做展示格式化', () => {
  it('变化量按单位渲染', () => {
    expect(changeText(metric({ change: 20 }), 'CURRENCY')).toBe('20.00 USD');
    expect(changeText(metric({ change: 5, currency: '' }), 'QUANTITY')).toBe('5.000');
    expect(changeText(metric({ change: -3 }), 'PERCENT')).toBe('-3.00%');
  });

  it('变化量为 null 是「—」，不是 0', () => {
    expect(changeText(metric({ change: null }), 'CURRENCY')).toBe('—');
  });

  it('变化率为 null 是「不可比」，不是 0%', () => {
    expect(changeRateText(metric({ changeRate: null }))).toBe('不可比');
  });

  it('基线为 0 时变化率算不出来 —— 「上期是 0」与「本期没变」是两件事', () => {
    const zeroBaseline = metric({ baselineValue: 0, change: 10, changeRate: null });
    expect(changeRateText(zeroBaseline)).toBe('不可比');
    expect(changeText(zeroBaseline, 'CURRENCY')).toBe('10.00 USD');
  });

  it('方向有语义色，算不出来时是中性色', () => {
    expect(changeTone(metric({ change: 5 }))).toBe('success');
    expect(changeTone(metric({ change: -5 }))).toBe('danger');
    expect(changeTone(metric({ change: 0 }))).toBe('neutral');
    expect(changeTone(metric({ change: null }))).toBe('neutral');
  });

  it('方向标签分得清「上升 / 下降 / 持平 / 不可比」', () => {
    expect(changeDirectionLabel(metric({ change: 5 }))).toBe('上升');
    expect(changeDirectionLabel(metric({ change: -5 }))).toBe('下降');
    expect(changeDirectionLabel(metric({ change: 0 }))).toBe('持平');
    expect(changeDirectionLabel(metric({ change: null }))).toBe('不可比');
  });
});

// ================================================================ 排名分位

describe('同层级排名分位', () => {
  it('给出名次与样本数', () => {
    expect(rankingText(ranking())).toBe('第 3 / 20');
    expect(rankingPercentileText(ranking())).toBe('85.00%');
  });

  it('没有样本时不可比', () => {
    expect(rankingText(ranking({ total: 0 }))).toBe('不可比');
    expect(rankingPercentileText(ranking({ percentile: null }))).toBe('不可比');
    expect(rankingText(null)).toBe('不可比');
  });

  it('中位数与平均数算不出来是「—」，不是 0', () => {
    expect(peerMedianText(ranking({ peerMedian: null }))).toBe('—');
    expect(peerAverageText(ranking({ peerAverage: null }))).toBe('—');
  });
});

// ================================================================ 区块

describe('区块读取', () => {
  it('按区块码取区块', () => {
    expect(sectionByCode(response(), 'SALES')?.title).toBe('销量与销售额');
  });

  it('找不到区块返回 null，不造一个空区块', () => {
    // 造一个空区块会让界面以为「这一段有数据但全是空的」。
    expect(sectionByCode(response(), 'NOT_A_SECTION')).toBeNull();
    expect(sectionByCode(null, 'SALES')).toBeNull();
  });

  it('竞品行与广告活动行只在对应区块里有值', () => {
    const withCompetitors = section({
      sectionCode: 'COMPETITION',
      competitors: [
        {
          sellerId: 's1',
          sellerName: '某跟卖者',
          sellerKind: 'EXTERNAL',
          hijackerPrice: 9.9,
          referencePrice: 12,
          priceGap: 2.1,
          priceGapRatio: 17.5,
          currency: 'USD',
          observedAt: '2026-10-20T00:00:00Z',
          note: '',
        },
      ],
    });
    expect(sectionCompetitors(withCompetitors)).toHaveLength(1);
    expect(sectionAdCampaigns(withCompetitors)).toHaveLength(0);
    expect(sectionMetrics(withCompetitors)).toHaveLength(1);
  });

  it('跟卖者性质分得清「己方店铺」与「外部跟卖者」', () => {
    expect(sellerKindLabel('EXTERNAL')).toBe('外部跟卖者');
    expect(sellerKindLabel('OWN_SHOP')).toBe('己方其他店铺');
    expect(sellerKindTone('EXTERNAL')).toBe('warning');
    expect(sellerKindTone('OWN_SHOP')).toBe('neutral');
  });
});

// ================================================================ 趋势

describe('趋势', () => {
  const point: PerformanceTrendPoint = {
    date: '2026-10-01',
    salesQuantity: 3,
    returnedQuantity: null,
    impressions: 1000,
    clicks: 50,
    sessions: 60,
    salesAmounts: [{ currency: 'USD', amount: 30 }],
    cogsAmounts: [],
    grossProfitAmounts: [],
  };

  it('有值才说「有趋势」，全是空的就如实说算不出来', () => {
    expect(trendHasValues([point])).toBe(true);
    expect(
      trendHasValues([
        {
          ...point,
          salesQuantity: null,
          impressions: null,
          clicks: null,
          sessions: null,
          salesAmounts: [],
        },
      ]),
    ).toBe(false);
  });
});

// ================================================================ 页面状态

describe('页面状态把「读不到」与「没有数据」分开', () => {
  it('错误 / 加载 / 空 / 就绪是四件事', () => {
    expect(pageState(null, '上游读不到')).toBe('error');
    expect(pageState(null, '')).toBe('loading');
    expect(pageState(response({ sections: [] }), '')).toBe('empty');
    expect(pageState(response(), '')).toBe('ready');
  });

  it('空态给出原因', () => {
    expect(emptyReasonLabel('NO_PROJECTION')).toBe('尚未投影');
    expect(emptyReasonLabel('NO_FACT')).toBe('窗口内没有事实');
    expect(emptyReasonLabel(null)).toBe('');
  });

  it('空态文案回落到默认说法', () => {
    expect(emptyText(response({ emptyNote: '' }))).toBe('这个窗口里没有可分析的事实。');
    expect(emptyText(response({ emptyNote: '没刷过投影' }))).toBe('没刷过投影');
  });

  it('投影状态有中文名', () => {
    expect(statusLabel('PROJECTED')).toBe('已投影');
    expect(statusLabel('NOT_PROJECTED')).toBe('尚未投影');
    expect(statusLabel('PARTIAL')).toBe('部分投影');
  });
});

// ================================================================ 数据更新时间常驻

describe('数据更新时间常驻', () => {
  it('有投影时显示截至时刻', () => {
    expect(dataUpdatedText(response())).toBe('数据截至 2026-10-28T02:00:00Z');
    expect(dataUpdatedNote(response())).toContain('以业务系统为准');
  });

  it('没投影过时是「尚未投影任何数据」，不是一个长得像时间的假值', () => {
    expect(dataUpdatedText(response({ dataUpdatedAt: null }))).toBe('尚未投影任何数据');
    expect(dataUpdatedText(null)).toBe('尚未投影任何数据');
  });
});

// ================================================================ 能力声明

describe('能力声明', () => {
  it('未交付项与上游清单原样透出', () => {
    const caps = capabilities();
    expect(notDeliveredLines(caps)).toHaveLength(1);
    expect(notDeliveredLines(caps)[0]).toContain('14.3.1.3');
    expect(upstreamModules(caps)).toHaveLength(1);
    expect(ruleNotes(caps)).toHaveLength(1);
  });

  it('没有能力声明时给空数组，不给假值', () => {
    expect(notDeliveredLines(null)).toEqual([]);
    expect(upstreamModules(null)).toEqual([]);
    expect(ruleNotes(null)).toEqual([]);
  });

  it('刷新结果状态有中文名与语义色', () => {
    expect(refreshStatusLabel('SUCCEEDED')).toBe('成功');
    expect(refreshStatusLabel('FAILED')).toBe('失败');
    expect(refreshStatusLabel('TRUNCATED')).toBe('已截断');
    expect(refreshStatusTone('FAILED')).toBe('danger');
    expect(refreshStatusTone('TRUNCATED')).toBe('warning');
  });
});

// ================================================================ 维度与时间范围

describe('维度与时间范围', () => {
  it('维度层有中文名与下拉项', () => {
    expect(dimensionLabel('SKU')).toBe('SKU');
    expect(dimensionLabel('SPU')).toBe('SPU');
    expect(dimensionLabel('LISTING')).toBe('Listing');
    expect(dimensionOptions().map((option) => option.value)).toEqual(['SKU', 'SPU', 'LISTING']);
  });

  it('默认范围是最近 30 天（含今天）', () => {
    const range = defaultRange('2026-10-30');
    expect(range.to).toBe('2026-10-30');
    expect(range.from).toBe('2026-10-01');
  });

  it('基线文本写明比的是哪一段', () => {
    expect(baselineText(response())).toBe('基线（上一个等长周期）2026-09-01 ~ 2026-09-30');
  });

  it('维度覆盖范围写明覆盖了多少 SKU / Listing', () => {
    expect(dimensionScopeText(response())).toBe('覆盖 1 个 SKU / 1 个 Listing');
  });
});
