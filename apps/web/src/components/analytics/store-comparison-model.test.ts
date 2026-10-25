import { describe, expect, it } from 'vitest';
import {
  amountText,
  attributionHints,
  availabilityLabel,
  availabilityTone,
  caliberWarnings,
  cellAvailability,
  cellNote,
  cellText,
  classificationLabel,
  classificationTone,
  comparisonWindowText,
  countText,
  dataUpdatedNote,
  dataUpdatedText,
  defaultRange,
  emptyReasonLabel,
  emptyText,
  groupCaliberNote,
  hasMultipleCurrencies,
  hasMultiplePlatforms,
  matrixColumns,
  metricFormulaText,
  metricUnitLabel,
  metricValueText,
  pageState,
  percentText,
  platformFilterOptions,
  platformLabel,
  rangeText,
  rankText,
  rankingEntries,
  ratioText,
  statusLabel,
} from './store-comparison-model';
import type {
  ComparisonResponse,
  StoreComparisonRow,
  StoreMetricDefinitionView,
  StoreMetricValue,
} from './store-comparison-model';

/**
 * 13.1.1.2 多店对比 前端模型单测。
 *
 * <p>测的是**这一页最容易做丢的五件事**：
 * 「算不出来」≠「等于 0」、跨平台口径差异必须标注、多币种不相加、
 * 数据更新时间常驻、页面状态要把「读不到」与「没有数据」分开。
 */

function metric(overrides: Partial<StoreMetricValue> = {}): StoreMetricValue {
  return {
    metricCode: 'STORE_GMV_PLATFORM',
    currency: 'USD',
    value: 100,
    baseValue: null,
    baseCurrency: '',
    rank: 1,
    availability: 'AVAILABLE',
    availabilityNote: '',
    ...overrides,
  };
}

function row(overrides: Partial<StoreComparisonRow> = {}): StoreComparisonRow {
  return {
    shopCode: 'shop-a',
    shopName: '亚马逊旗舰店',
    platformCode: 'AMAZON',
    platformName: '亚马逊',
    currencies: ['USD'],
    metrics: [metric()],
    scaleRank: 1,
    growthRank: null,
    classification: 'STABLE',
    classificationNote: '成交金额环比增长率在 ±20% 之间，判为稳定。',
    attributionHints: ['成交金额高于中位数 10.0000（中位数 90.0000，币种 USD）'],
    ...overrides,
  };
}

function response(overrides: Partial<ComparisonResponse> = {}): ComparisonResponse {
  return {
    from: '2026-10-01',
    to: '2026-10-02',
    previousFrom: '2026-09-29',
    previousTo: '2026-09-30',
    rows: [row()],
    platformGroups: [
      {
        platformCode: 'AMAZON',
        platformName: '亚马逊',
        stores: [row()],
        storeCount: 1,
        caliberNote: '亚马逊（AMAZON）平台的成交金额是**平台侧报告口径**。',
      },
    ],
    rankings: [
      {
        metricCode: 'STORE_GMV_PLATFORM',
        displayName: '平台侧成交金额',
        unit: 'CURRENCY',
        entries: [
          { rank: 1, shopCode: 'shop-a', shopName: '亚马逊旗舰店', platformCode: 'AMAZON', currency: 'USD', value: 100 },
          { rank: 1, shopCode: 'shop-b', shopName: 'TikTok 小店', platformCode: 'TIKTOK', currency: 'EUR', value: 80 },
        ],
        note: '',
      },
    ],
    laggards: [],
    growthStores: [],
    metricDefinitions: [],
    caliberNotes: ['口径差异说明'],
    dataUpdatedAt: '2026-10-03T02:00:00Z',
    dataUpdatedNote: '最近一次店铺维度投影落库时刻。',
    status: 'PROJECTED',
    statusNote: '',
    baseCurrency: null,
    baseRates: [],
    emptyReason: null,
    emptyNote: '',
    ...overrides,
  };
}

const DEFINITIONS: StoreMetricDefinitionView[] = [
  {
    metricCode: 'STORE_GMV_PLATFORM',
    displayName: '平台侧成交金额',
    unit: 'CURRENCY',
    aggregation: 'SUM',
    numeratorMetricCode: null,
    denominatorMetricCode: null,
    baseMetricCode: null,
    currencyAware: true,
    description: '平台侧报告的成交金额。',
    ownerModule: '9.1.1.1',
  },
  {
    metricCode: 'STORE_GMV_GROWTH',
    displayName: '成交金额环比增长率',
    unit: 'PERCENT',
    aggregation: 'WINDOW_GROWTH',
    numeratorMetricCode: null,
    denominatorMetricCode: null,
    baseMetricCode: 'STORE_GMV_PLATFORM',
    currencyAware: true,
    description: '环比增长率。',
    ownerModule: '13.1.1.2',
  },
  {
    metricCode: 'STORE_CALIBER_GAP',
    displayName: '口径差异额',
    unit: 'CURRENCY',
    aggregation: 'DERIVED_DIFF',
    numeratorMetricCode: 'STORE_GMV_PLATFORM',
    denominatorMetricCode: 'STORE_GMV_RESTORED',
    baseMetricCode: null,
    currencyAware: true,
    description: '平台侧 − 还原口径。',
    ownerModule: '13.1.1.2',
  },
];

describe('展示格式化', () => {
  it('null 的金额显示「—」，绝不用 0 顶替', () => {
    expect(amountText(null, 'USD')).toBe('—');
    expect(amountText(0, 'USD')).toBe('0.00 USD');
  });

  it('null 的百分比显示「不可比」，绝不用 0% 顶替', () => {
    expect(percentText(null)).toBe('不可比');
    expect(percentText(0)).toBe('0.00%');
    expect(percentText(12.3456)).toBe('+12.35%');
    expect(percentText(-75)).toBe('-75.00%');
  });

  it('null 的倍数显示「—」而不是 0 倍', () => {
    expect(ratioText(null)).toBe('—');
    expect(ratioText(5)).toBe('5.00×');
  });

  it('null 的笔数显示「—」而不是 0', () => {
    expect(countText(null)).toBe('—');
    expect(countText(2)).toBe('2');
  });

  it('按单位分派渲染', () => {
    expect(metricValueText(metric({ value: 100 }), 'CURRENCY')).toBe('100.00 USD');
    expect(metricValueText(metric({ value: 100 }), 'COUNT')).toBe('100');
    expect(metricValueText(metric({ value: 12.5 }), 'PERCENT')).toBe('+12.50%');
    expect(metricValueText(metric({ value: 3.2 }), 'RATIO')).toBe('3.20×');
    expect(metricValueText(metric({ value: null }), 'CURRENCY')).toBe('—');
  });
});

describe('口径', () => {
  it('窗口环比型必须说清它比的是哪个指标', () => {
    const growth = DEFINITIONS[1]!;
    expect(growth.aggregation).toBe('WINDOW_GROWTH');
    expect(metricFormulaText(growth)).toContain('STORE_GMV_PLATFORM');
  });

  it('派生比型说清分子与分母', () => {
    expect(metricFormulaText(DEFINITIONS[2]!)).toBe(
      'STORE_GMV_PLATFORM ÷ STORE_GMV_RESTORED',
    );
  });

  it('窗口环比型没有声明被比较指标时要说口径不成立', () => {
    const broken = { ...DEFINITIONS[1]!, baseMetricCode: null };
    expect(metricFormulaText(broken)).toContain('口径不成立');
  });

  it('单位有中文名', () => {
    expect(metricUnitLabel('CURRENCY')).toBe('金额');
    expect(metricUnitLabel('PERCENT')).toBe('百分比');
    expect(metricUnitLabel('RATIO')).toBe('倍数');
  });
});

describe('矩阵的列', () => {
  it('只列出该租户已登记的指标', () => {
    const columns = matrixColumns(DEFINITIONS);
    expect(columns.map((c) => c.metricCode)).toEqual([
      'STORE_GMV_PLATFORM',
      'STORE_CALIBER_GAP',
      'STORE_GMV_GROWTH',
    ]);
    // STORE_GMV_RESTORED 没登记 ⇒ 列就不该出现（租户把口径关掉，列就消失，
    // 而不是留一个永远空的表头）。
    expect(columns.map((c) => c.metricCode)).not.toContain('STORE_GMV_RESTORED');
  });

  it('列的标签取自口径定义', () => {
    const columns = matrixColumns(DEFINITIONS);
    expect(columns[0]!.label).toBe('平台侧成交金额');
  });

  it('没有事实的单元格是「—」而不是 0', () => {
    const target = row({
      metrics: [metric({ value: null, availability: 'NO_DATA', availabilityNote: '这个窗口没有事实。' })],
    });
    const columns = matrixColumns(DEFINITIONS);
    expect(cellText(target, columns[0]!)).toBe('—');
    expect(cellAvailability(target, columns[0]!)).toBe('NO_DATA');
    expect(cellNote(target, columns[0]!)).toBe('这个窗口没有事实。');
  });

  it('缺失的指标单元格也算「没有数据」而不是 0', () => {
    const target = row({ metrics: [] });
    const columns = matrixColumns(DEFINITIONS);
    expect(cellText(target, columns[0]!)).toBe('—');
    expect(cellAvailability(target, columns[0]!)).toBe('NO_DATA');
  });
});

describe('分类与可用性', () => {
  it('掉队是 warning 不是 danger（掉队是经营状态，不是错误）', () => {
    expect(classificationTone('LAGGARD')).toBe('warning');
    expect(classificationTone('GROWTH')).toBe('success');
    expect(classificationTone('UNCLASSIFIED')).toBe('neutral');
  });

  it('不可比 / 没有数据都不是错误色', () => {
    expect(availabilityTone('NO_DATA')).toBe('neutral');
    expect(availabilityTone('NOT_COMPARABLE')).toBe('neutral');
    expect(availabilityTone('AVAILABLE')).toBe('success');
  });

  it('分类有中文标签', () => {
    expect(classificationLabel('GROWTH')).toBe('增长店铺');
    expect(classificationLabel('LAGGARD')).toBe('掉队店铺');
    expect(classificationLabel('UNCLASSIFIED')).toBe('不可比');
  });

  it('没有排名就没有名次，不是第 0 名', () => {
    expect(rankText(null)).toBe('—');
    expect(rankText(1)).toBe('第 1 名');
  });
});

describe('平台与口径差异（蓝图的加粗要求）', () => {
  it('没有平台归属的店铺归到「未标注平台」，不猜一个平台', () => {
    expect(platformLabel('', '')).toBe('未标注平台');
    expect(platformLabel('AMAZON', '亚马逊')).toBe('亚马逊（AMAZON）');
  });

  it('多个平台时必须提示「分组之间的大小差异不能直接当成经营能力差异」', () => {
    const multi = response({
      platformGroups: [
        { platformCode: 'AMAZON', platformName: '亚马逊', stores: [row()], storeCount: 1, caliberNote: 'a' },
        { platformCode: 'TIKTOK', platformName: 'TikTok', stores: [row()], storeCount: 1, caliberNote: 'b' },
      ],
    });
    expect(hasMultiplePlatforms(multi)).toBe(true);
    expect(caliberWarnings(multi).join('\n')).toContain('不能直接当成经营能力差异');
  });

  it('多个币种时必须提示排名在币种内做', () => {
    const multi = response({
      rows: [row({ currencies: ['USD', 'EUR'] })],
    });
    expect(hasMultipleCurrencies(multi)).toBe(true);
    expect(caliberWarnings(multi).join('\n')).toContain('排名也在币种内做');
  });

  it('平台侧与还原口径的差异提示始终给出', () => {
    expect(caliberWarnings(response()).join('\n')).toContain('口径差异额');
  });

  it('没有数据时没有口径提醒', () => {
    expect(caliberWarnings(null)).toEqual([]);
  });

  it('平台分组的口径说明原样显示，不改写', () => {
    const group = response().platformGroups[0]!;
    expect(groupCaliberNote(group)).toBe(group.caliberNote);
  });

  it('平台下拉的第一项是「全部平台」', () => {
    const options = platformFilterOptions([
      { platformCode: 'AMAZON', platformName: '亚马逊', storeCount: 2 },
    ]);
    expect(options[0]).toEqual({ value: '', label: '全部平台' });
    expect(options[1]!.value).toBe('AMAZON');
  });
});

describe('排名榜', () => {
  it('排名按币种分组，不同币种的位次不混在一起', () => {
    const ranking = response().rankings[0]!;
    const buckets = rankingEntries(ranking);
    expect(buckets).toHaveLength(2);
    expect(buckets.map((b) => b.currency).sort()).toEqual(['EUR', 'USD']);
    expect(buckets.every((b) => b.entries.every((e) => e.currency === b.currency))).toBe(true);
  });
});

describe('归因提示', () => {
  it('服务端给的提示原样返回', () => {
    expect(attributionHints(row())).toEqual([
      '成交金额高于中位数 10.0000（中位数 90.0000，币种 USD）',
    ]);
  });
});

describe('数据更新时间', () => {
  it('没有投影时如实说「尚未投影」', () => {
    expect(dataUpdatedText(response({ dataUpdatedAt: null }))).toContain('尚未投影');
  });

  it('有投影时给出时刻与口径说明', () => {
    const target = response();
    expect(dataUpdatedText(target)).toContain('2026-10-03T02:00:00Z');
    expect(dataUpdatedNote(target)).toContain('投影落库时刻');
  });
});

describe('页面状态', () => {
  it('「读不到」是错误态，「没有数据」是空态 —— 两件事', () => {
    expect(pageState(null, '上游不可达')).toBe('error');
    expect(pageState(null, '')).toBe('loading');
    expect(pageState(response({ rows: [] }), '')).toBe('empty');
    expect(pageState(response(), '')).toBe('ready');
  });

  it('空态文案用服务端给的原因，不自己编', () => {
    const target = response({
      rows: [],
      emptyReason: 'NOT_PROJECTED',
      emptyNote: '这个租户已有店铺档案，但这个窗口还没有投影任何店铺维度事实。',
    });
    expect(emptyText(target)).toContain('还没有投影');
    expect(emptyReasonLabel('NOT_PROJECTED')).toBe('还没有投影事实');
    expect(emptyReasonLabel('NO_STORES')).toBe('还没有店铺档案');
  });
});

describe('时间范围', () => {
  it('默认窗口是最近 30 天（含今天）', () => {
    expect(defaultRange('2026-10-10')).toEqual({ from: '2026-09-11', to: '2026-10-10' });
  });

  it('对比窗口文本带上环比基准窗口', () => {
    expect(comparisonWindowText(response())).toContain('环比基准 2026-09-29 ~ 2026-09-30');
  });

  it('范围文本', () => {
    expect(rangeText('2026-10-01', '2026-10-02')).toBe('2026-10-01 ~ 2026-10-02');
  });

  it('状态有中文标签', () => {
    expect(statusLabel('PROJECTED')).toBe('已投影');
    expect(statusLabel('NOT_PROJECTED')).toBe('未投影');
  });
});
