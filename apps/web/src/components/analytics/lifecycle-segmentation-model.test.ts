import { describe, expect, it } from 'vitest';
import {
  ACTIONS,
  SEGMENTS,
  abcLabel,
  abcRankText,
  actionLabel,
  actionTone,
  amountOf,
  amountRowText,
  amountText,
  availabilityLabel,
  availabilityTone,
  currencyLabel,
  dataUpdatedNote,
  dataUpdatedText,
  defaultRange,
  dimensionOptions,
  emptyReasonLabel,
  emptyText,
  grossProfitNote,
  metricFormulaText,
  notDeliveredLines,
  pageState,
  percentText,
  populatedSegments,
  quantityText,
  refreshStatusLabel,
  refreshStatusTone,
  rowOf,
  ruleThresholdText,
  segmentLabel,
  segmentOptions,
  segmentTone,
  statusLabel,
  summaryOf,
  upstreamModules,
} from './lifecycle-segmentation-model';
import type {
  LifecycleMetricReference,
  LifecycleSegmentationAmountRow,
  LifecycleSegmentationResponse,
  LifecycleSegmentationRow,
  LifecycleSegmentationRuleView,
  LifecycleSegmentSummary,
} from './lifecycle-segmentation-model';

/**
 * 13.3.1.2 生命周期与分层前端模型的单元测试。
 *
 * <p>本文件盯的是**页面最容易做丢的那几件事**：
 *
 * <ul>
 *   <li>「算不出来」≠「等于 0」：档位为 {@code null} 时显示「未定档」而不是某个默认档位，
 *       毛利为 {@code null} 时显示「—」而不是 0 元；
 *   <li>🔴 **金额按币种分行**：页面上不出现跨币种的合计；
 *   <li>🔴 **ABC 两套口径**：按销量一列、按金额一列（按币种）；
 *   <li>空币种 = 与币种无关，**不是「币种未知」**；
 *   <li>「读不到」（error）与「没有数据」（empty）是两种状态；
 *   <li>档位 → 建议动作的映射是确定的（六档 → 四类动作）。
 * </ul>
 */

function amount(overrides: Partial<LifecycleSegmentationAmountRow> = {}): LifecycleSegmentationAmountRow {
  return {
    currency: 'USD',
    salesAmount: 3000,
    grossProfitAmount: 1500,
    grossProfitAvailability: 'AVAILABLE',
    grossProfitNote: '',
    abcByAmount: 'A',
    abcRankByAmount: 1,
    abcTotalByAmount: 5,
    ...overrides,
  };
}

function row(overrides: Partial<LifecycleSegmentationRow> = {}): LifecycleSegmentationRow {
  return {
    dimensionType: 'SKU',
    dimensionId: '11111111-1111-1111-1111-111111111111',
    dimensionKey: 'SKU-HOT',
    dimensionName: '爆款',
    bucketDate: '2026-10-04',
    windowFrom: '2026-09-25',
    windowTo: '2026-10-04',
    salesQuantity: 300,
    firstSaleDate: '2026-08-30',
    historyDays: 1,
    recentQuantity: 300,
    priorQuantity: 100,
    declineRatio: 0,
    salesShare: 88.2353,
    lifecycleSegment: 'HOT',
    segmentAvailability: 'AVAILABLE',
    segmentNote: '窗口内销量份额 88.2353% ≥ 爆款门槛 10.0000%。',
    abcByQuantity: 'A',
    abcRankByQuantity: 1,
    abcTotalByQuantity: 6,
    recommendedAction: 'RESTOCK',
    actionNote: '备货：窗口内销量份额 88.2353% ≥ 爆款门槛 10.0000%。',
    onHandQuantity: 0,
    agingDays: null,
    sourceObservedAt: null,
    amounts: [amount()],
    ...overrides,
  };
}

function summary(overrides: Partial<LifecycleSegmentSummary> = {}): LifecycleSegmentSummary {
  return {
    segment: 'HOT',
    segmentLabel: '爆款',
    recommendedAction: 'RESTOCK',
    actionLabel: '备货',
    actionNote: '备货：爆款是资源分配的第一顺位（蓝图：「爆款备货」）。',
    productCount: 1,
    salesQuantity: 300,
    amounts: [amount()],
    ...overrides,
  };
}

function response(overrides: Partial<LifecycleSegmentationResponse> = {}): LifecycleSegmentationResponse {
  return {
    requestedFrom: '2026-09-25',
    requestedTo: '2026-10-04',
    from: '2026-09-25',
    to: '2026-10-04',
    windowMatched: true,
    windowNote: '分层窗口与请求一致。',
    dimensionType: 'SKU',
    ruleCode: 'LIFECYCLE_DEFAULT_SKU',
    ruleVersion: 1,
    ruleViews: [],
    summary: [summary()],
    rows: [row()],
    page: 0,
    size: 20,
    total: 1,
    archivedProductCount: 6,
    notSegmentedProductCount: 0,
    coverageNote: '商品档案里共 6 个SKU，窗口内有分层结果的是 1 个。',
    metricReferences: [],
    caliberNotes: [],
    rules: [],
    dataUpdatedAt: '2026-10-04T12:00:00Z',
    dataUpdatedNote: '最近一次分层落库时刻（DB 钟域）。',
    status: 'OK',
    statusNote: '分层结果已就绪。',
    emptyReason: null,
    emptyNote: '',
    ...overrides,
  };
}

function rule(overrides: Partial<LifecycleSegmentationRuleView> = {}): LifecycleSegmentationRuleView {
  return {
    ruleCode: 'LIFECYCLE_DEFAULT_SKU',
    ruleName: '默认分层规则（SKU 层）',
    dimensionType: 'SKU',
    version: 1,
    windowDays: 30,
    newMaxDaysSinceFirstSale: 30,
    firstSaleLookbackDays: 90,
    minHistoryDays: 1,
    decliningMinDeclineRatio: 40,
    hotMinSalesShareRatio: 10,
    strongMinSalesShareRatio: 3,
    steadyMinSalesShareRatio: 0.5,
    slowMaxSalesPerDay: 0.5,
    abcACumulativeRatio: 80,
    abcBCumulativeRatio: 95,
    quantityMetricCode: 'PRODUCT_SALES_QUANTITY',
    amountMetricCode: 'PRODUCT_SALES_AMOUNT',
    grossProfitMetricCode: 'PRODUCT_GROSS_PROFIT_AMOUNT',
    effectiveFrom: '2026-10-04',
    effectiveTo: null,
    enabled: true,
    createdBy: 'system-default',
    note: '标准分层规则。',
    createdAt: '2026-10-04T12:00:00Z',
    updatedAt: '2026-10-04T12:00:00Z',
    ...overrides,
  };
}

// ================================================================ 档位与动作

describe('档位与建议动作', () => {
  it('六档一个不多一个不少', () => {
    expect(SEGMENTS).toEqual(['NEW', 'HOT', 'STRONG', 'STEADY', 'SLOW', 'DECLINING']);
  });

  it('四类建议动作齐全', () => {
    expect(ACTIONS).toEqual(['RESTOCK', 'CLEARANCE', 'DELIST', 'PROMOTE']);
  });

  it('档位有中文名', () => {
    expect(segmentLabel('NEW')).toBe('新品');
    expect(segmentLabel('HOT')).toBe('爆款');
    expect(segmentLabel('STRONG')).toBe('旺款');
    expect(segmentLabel('STEADY')).toBe('平款');
    expect(segmentLabel('SLOW')).toBe('滞销款');
    expect(segmentLabel('DECLINING')).toBe('衰退款');
  });

  it('🔴 算不出档位时是「未定档」，不是某个默认档位', () => {
    expect(segmentLabel(null)).toBe('未定档');
    expect(segmentLabel('')).toBe('未定档');
    expect(segmentTone(null)).toBe('neutral');
  });

  it('档位有语义色', () => {
    expect(segmentTone('HOT')).toBe('danger');
    expect(segmentTone('STRONG')).toBe('success');
    expect(segmentTone('NEW')).toBe('info');
    expect(segmentTone('SLOW')).toBe('warning');
  });

  it('动作有中文名与语义色', () => {
    expect(actionLabel('RESTOCK')).toBe('备货');
    expect(actionLabel('CLEARANCE')).toBe('清仓');
    expect(actionLabel('DELIST')).toBe('下架');
    expect(actionLabel('PROMOTE')).toBe('推广');
    expect(actionTone('RESTOCK')).toBe('success');
    expect(actionTone('CLEARANCE')).toBe('warning');
    expect(actionTone('DELIST')).toBe('danger');
    expect(actionTone('PROMOTE')).toBe('info');
  });

  it('档位下拉项第一项是「全部档位」', () => {
    const options = segmentOptions();
    expect(options[0]?.value).toBe('');
    expect(options).toHaveLength(SEGMENTS.length + 1);
  });

  it('维度层只有 SKU / SPU 两档', () => {
    expect(dimensionOptions().map((option) => option.value)).toEqual(['SKU', 'SPU']);
  });
});

// ================================================================ 格式化

describe('展示格式化', () => {
  it('🔴 null 绝不显示成 0', () => {
    expect(quantityText(null)).toBe('—');
    expect(amountText(null, 'USD')).toBe('—');
    expect(percentText(null)).toBe('不可比');
  });

  it('件数保留 3 位小数', () => {
    expect(quantityText(300)).toBe('300.000');
    expect(quantityText(0.5)).toBe('0.500');
  });

  it('金额带币种', () => {
    expect(amountText(3000, 'USD')).toBe('3,000.00 USD');
  });

  it('🔴 空币种 = 与币种无关，不是「币种未知」', () => {
    expect(currencyLabel('')).toBe('与币种无关');
    expect(currencyLabel('USD')).toBe('USD');
    expect(amountText(12, '')).toContain('与币种无关');
  });

  it('百分比已经是百分数，不再乘 100', () => {
    expect(percentText(88.24)).toBe('88.24%');
    expect(percentText(0)).toBe('0.00%');
  });
});

// ================================================================ ABC

describe('ABC 分档', () => {
  it('有档位时给出档位与名次', () => {
    expect(abcRankText('A', 1, 6)).toBe('A 档 · 第 1 / 6 名');
  });

  it('🔴 没有销量事实的商品不进榜（不是 C 档）', () => {
    expect(abcRankText(null, null, null)).toBe('不进榜');
    expect(abcLabel(null)).toBe('不进榜');
  });

  it('档位标签', () => {
    expect(abcLabel('B')).toBe('B 档');
  });
});

// ================================================================ 可得性

describe('可得性语义', () => {
  it('四种可得性各有文案与颜色', () => {
    expect(availabilityLabel('AVAILABLE')).toBe('可用');
    expect(availabilityLabel('NO_DATA')).toBe('没有数据');
    expect(availabilityLabel('NOT_COMPARABLE')).toBe('不可比');
    expect(availabilityLabel('NOT_DELIVERED')).toBe('本轮不可用');
    expect(availabilityTone('NOT_COMPARABLE')).toBe('warning');
    expect(availabilityTone('NOT_DELIVERED')).toBe('danger');
  });

  it('刷新结果状态', () => {
    expect(refreshStatusLabel('SUCCEEDED')).toBe('成功');
    expect(refreshStatusLabel('FAILED')).toBe('失败');
    expect(refreshStatusTone('FAILED')).toBe('danger');
  });
});

// ================================================================ 页面状态

describe('页面状态机', () => {
  it('🔴 读不到与没有数据是两种状态', () => {
    expect(pageState(null, '')).toBe('loading');
    expect(pageState(null, 'boom')).toBe('error');
    expect(pageState(response({ status: 'EMPTY' }), '')).toBe('empty');
    expect(pageState(response(), '')).toBe('ready');
  });

  it('空态原因有文案', () => {
    expect(emptyReasonLabel('NO_SEGMENTATION_IN_WINDOW')).toBe('这个窗口内还没有分层结果');
    expect(emptyReasonLabel(null)).toBe('');
  });

  it('空态文案来自服务端', () => {
    expect(emptyText(response({ emptyNote: '先刷新。' }))).toBe('先刷新。');
    expect(emptyText(null)).toBe('');
  });

  it('🔴 空表时不给假的数据更新时间', () => {
    expect(dataUpdatedText(response({ dataUpdatedAt: null }))).toBe('尚未投影任何数据');
    expect(dataUpdatedText(response())).toContain('数据截至');
    expect(dataUpdatedText(null)).toBe('尚未读取数据');
    expect(dataUpdatedNote(null)).toContain('投影');
  });

  it('状态文案', () => {
    expect(statusLabel('OK')).toBe('就绪');
    expect(statusLabel('EMPTY')).toBe('空');
  });
});

// ================================================================ 分层结果

describe('分层结果', () => {
  it('按商品键取行', () => {
    expect(rowOf(response(), 'SKU-HOT')?.dimensionKey).toBe('SKU-HOT');
    expect(rowOf(response(), 'SKU-NOPE')).toBeNull();
    expect(rowOf(null, 'SKU-HOT')).toBeNull();
  });

  it('按档位取汇总', () => {
    expect(summaryOf(response(), 'HOT')?.productCount).toBe(1);
    expect(summaryOf(response(), 'SLOW')).toBeNull();
  });

  it('🔴 只列有商品的档位', () => {
    const only = populatedSegments(
      response({
        summary: [
          summary({ segment: 'HOT', productCount: 2 }),
          summary({ segment: 'SLOW', productCount: 0 }),
        ],
      }),
    );
    expect(only).toHaveLength(1);
    expect(only[0]?.segment).toBe('HOT');
    expect(populatedSegments(null)).toEqual([]);
  });

  it('按币种取金额行', () => {
    const target = row({
      amounts: [amount({ currency: 'USD' }), amount({ currency: 'EUR', abcByAmount: 'A' })],
    });
    expect(amountOf(target, 'EUR')?.currency).toBe('EUR');
    expect(amountOf(target, 'JPY')).toBeNull();
    expect(amountOf(null, 'USD')).toBeNull();
  });

  it('🔴 毛利算不出来时是「—」，不是 0 元', () => {
    const broken = amount({ grossProfitAmount: null, grossProfitAvailability: 'NO_DATA' });
    expect(amountRowText(broken)).toContain('毛利 —');
    expect(amountRowText(broken)).not.toContain('毛利 0.00 USD');
    expect(grossProfitNote(broken)).toBe('');
  });

  it('金额行文案带币种与毛利', () => {
    expect(amountRowText(amount())).toBe('3,000.00 USD（毛利 1,500.00 USD）');
  });
});

// ================================================================ 规则

describe('分层规则', () => {
  it('阈值文案只展示不重算', () => {
    const text = ruleThresholdText(rule());
    expect(text).toContain('新品 30 天');
    expect(text).toContain('ABC 80.00% / 95.00%');
    expect(text).toContain('滞销日均 0.500 件');
  });

  it('派生口径的算式写清分子分母', () => {
    const reference: LifecycleMetricReference = {
      metricCode: 'PRODUCT_GROSS_PROFIT_AMOUNT',
      displayName: '毛利额',
      unit: 'CURRENCY',
      aggregation: 'DERIVED_DIFF',
      numeratorMetricCode: 'PRODUCT_SALES_AMOUNT',
      denominatorMetricCode: 'PRODUCT_COGS_AMOUNT',
      caliberOwner: '13.3.1.1 产品表现分析',
      description: '毛利额 = 销售额 − 出库成本。',
      available: true,
      ownerModule: '',
      availabilityNote: '',
    };
    expect(metricFormulaText(reference)).toBe(
      'PRODUCT_SALES_AMOUNT − PRODUCT_COGS_AMOUNT（推导值）',
    );
  });

  it('SUM 型口径写「窗口内相加」', () => {
    const reference: LifecycleMetricReference = {
      metricCode: 'PRODUCT_SALES_QUANTITY',
      displayName: '销量（件）',
      unit: 'QUANTITY',
      aggregation: 'SUM',
      numeratorMetricCode: null,
      denominatorMetricCode: null,
      caliberOwner: '13.2.2.2 商品维度',
      description: '',
      available: true,
      ownerModule: '',
      availabilityNote: '',
    };
    expect(metricFormulaText(reference)).toBe('窗口内相加');
  });
});

// ================================================================ 能力声明与时间范围

describe('能力声明与时间范围', () => {
  it('拿不到的部分与上游清单都来自服务端', () => {
    expect(notDeliveredLines(null)).toEqual([]);
    expect(upstreamModules(null)).toEqual([]);
  });

  it('默认时间范围是最近 30 天（含今天）', () => {
    const range = defaultRange('2026-10-04');
    expect(range.to).toBe('2026-10-04');
    expect(range.from).toBe('2026-09-05');
  });
});
