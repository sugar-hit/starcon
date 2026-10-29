import { describe, expect, it } from 'vitest';
import {
  amountText,
  attributionLabel,
  attributionTone,
  availabilityLabel,
  availabilityTone,
  cellAvailability,
  cellNote,
  cellText,
  countText,
  currencyLabel,
  currencyScopeLabel,
  dataUpdatedNote,
  dataUpdatedText,
  defaultRange,
  drilldownReferenceText,
  emptyReasonLabel,
  emptyText,
  levelLabel,
  levelOptions,
  metricFormulaText,
  metricUnitLabel,
  metricValueText,
  notDeliveredLines,
  occurredAtText,
  pageState,
  percentText,
  platformCodesLabel,
  platformLabel,
  productColumns,
  quantityText,
  rangeText,
  refreshStatusLabel,
  refreshStatusTone,
  ruleNotes,
  shareText,
  shareTotalNote,
  spuMergeEvidence,
  statusLabel,
  unattributedText,
  upstreamModules,
} from './product-sales-model';
import type {
  ProductDrilldownRow,
  ProductMetricDefinitionView,
  ProductMetricValue,
  ProductRow,
  ProductSalesResponse,
  SpuSummaryRow,
  UnattributedSummary,
  VariantDistributionResponse,
} from './product-sales-model';

/**
 * 13.2.2.2 SKU/SPU/Listing 前端模型单测。
 *
 * <p>测的是**这一页最容易做丢的五件事**：
 * 「算不出来」≠「等于 0」、多币种绝不相加、Listing 销量归属边界必须显式、
 * 数据更新时间常驻、页面状态要把「读不到」与「没有数据」分开。
 */

function metric(overrides: Partial<ProductMetricValue> = {}): ProductMetricValue {
  return {
    metricCode: 'PRODUCT_SALES_AMOUNT',
    currency: 'USD',
    value: 50,
    availability: 'AVAILABLE',
    availabilityNote: '',
    ...overrides,
  };
}

function row(overrides: Partial<ProductRow> = {}): ProductRow {
  return {
    dimensionType: 'SKU',
    dimensionId: '11111111-1111-1111-1111-111111111111',
    dimensionKey: 'SKU-RED-S',
    dimensionName: '红杯 S',
    parentDimensionType: 'SPU',
    parentDimensionKey: 'SPU-CUP',
    parentDimensionName: '马克杯套装',
    variantSignature: '红色/S',
    platformCodes: ['AMAZON', 'TIKTOK'],
    skuCount: 1,
    listingCount: 2,
    currencies: ['EUR', 'USD'],
    metrics: [],
    attributionStatus: 'NOT_APPLICABLE',
    attributionNote: '',
    ...overrides,
  };
}

function definition(
  overrides: Partial<ProductMetricDefinitionView> = {},
): ProductMetricDefinitionView {
  return {
    metricCode: 'PRODUCT_VARIANT_SHARE',
    displayName: '变体销量占比',
    unit: 'PERCENT',
    aggregation: 'DERIVED_RATIO',
    numeratorMetricCode: 'PRODUCT_SALES_QUANTITY',
    denominatorMetricCode: 'PRODUCT_SALES_QUANTITY',
    numeratorDimensionType: 'SKU',
    denominatorDimensionType: 'SPU',
    currencyScope: 'ANY',
    currencyAware: false,
    description: '变体销量占比',
    ownerModule: '13.2.2.2',
    ...overrides,
  };
}

describe('「算不出来」≠「等于 0」', () => {
  it('null 的取值渲染成「—」/「不可比」，绝不显示成 0', () => {
    expect(quantityText(null)).toBe('—');
    expect(amountText(null, 'USD')).toBe('—');
    expect(percentText(null)).toBe('不可比');
    expect(countText(null)).toBe('—');
  });

  it('有值的取值按单位定标展示', () => {
    expect(quantityText(5)).toBe('5.000');
    expect(amountText(50, 'USD')).toBe('50.00 USD');
    expect(percentText(40)).toBe('40.00%');
    expect(countText(2)).toBe('2');
  });

  it('不可比的指标带原因，空格标签是「不可比」而不是 0%', () => {
    const notComparable = metric({
      value: null,
      availability: 'NOT_COMPARABLE',
      availabilityNote: '分母为 0',
    });
    expect(metricValueText(notComparable, 'PERCENT')).toBe('不可比');
    expect(availabilityLabel('NOT_COMPARABLE')).toBe('不可比');
    expect(availabilityTone('NOT_COMPARABLE')).toBe('neutral');
    expect(availabilityTone('AVAILABLE')).toBe('success');
  });

  it('没有事实的指标是「没有数据」，不是 0', () => {
    const noData = metric({
      value: null,
      availability: 'NO_DATA',
      availabilityNote: '这个窗口没有事实',
    });
    expect(metricValueText(noData, 'CURRENCY')).toBe('—');
    expect(availabilityLabel('NO_DATA')).toBe('没有数据');
  });
});

describe('多币种绝不相加', () => {
  it('空币种 = 与币种无关，不是「币种未知」', () => {
    expect(currencyLabel('')).toBe('与币种无关');
    expect(currencyLabel('USD')).toBe('USD');
    expect(amountText(5, '')).toBe('5.00（与币种无关）');
  });

  it('按币种分行的列把每个币种各列一条，不相加', () => {
    const columns = productColumns(
      [
        definition({
          metricCode: 'PRODUCT_SALES_AMOUNT',
          displayName: '成交金额',
          unit: 'CURRENCY',
          aggregation: 'SUM',
          numeratorMetricCode: null,
          denominatorMetricCode: null,
          numeratorDimensionType: null,
          denominatorDimensionType: null,
          currencyScope: 'SAME',
          currencyAware: true,
        }),
      ],
      'SKU',
    );
    const amountColumn = columns.find((c) => c.metricCode === 'PRODUCT_SALES_AMOUNT')!;
    const multiCurrency = row({
      metrics: [metric({ currency: 'USD', value: 30 }), metric({ currency: 'EUR', value: 56 })],
    });
    const text = cellText(multiCurrency, amountColumn);
    expect(text).toContain('30.00 USD');
    expect(text).toContain('56.00 EUR');
    // 🔴 前端**不许**把 30 + 56 显示成一个数。
    expect(text).not.toContain('86');
  });

  it('口径目录写清币种口径', () => {
    expect(currencyScopeLabel('ANY')).toContain('与货币无关');
    expect(currencyScopeLabel('SAME')).toBe('同币种');
  });
});

describe('Listing 销量归属边界必须显式', () => {
  it('共享 SKU 的 Listing 显示「销量无法唯一归属」', () => {
    expect(attributionLabel('SHARED_SKU')).toBe('销量无法唯一归属');
    expect(attributionTone('SHARED_SKU')).toBe('warning');
    expect(attributionLabel('ATTRIBUTED')).toBe('销量可唯一归属');
    expect(attributionTone('ATTRIBUTED')).toBe('success');
  });

  it('无法归属的销量单列成一句话', () => {
    const summary: UnattributedSummary = {
      quantity: 2,
      orderCount: 1,
      skuCount: 1,
      amounts: [{ currency: 'USD', amount: 50 }],
      note: '订单行不带平台商品 id',
    };
    const text = unattributedText(summary);
    expect(text).toContain('无法唯一归属');
    expect(text).toContain('50.00 USD');
    expect(text).toContain('2.000');
  });

  it('没有无法归属的销量时不显示那一段', () => {
    const summary: UnattributedSummary = {
      quantity: 0,
      orderCount: 0,
      skuCount: 0,
      amounts: [],
      note: '',
    };
    expect(unattributedText(summary)).toBe('');
    expect(unattributedText(null)).toBe('');
  });
});

describe('SPU 是跨平台经营的基本单位', () => {
  it('SPU 汇总行说清合并了多少 SKU × 平台 × Listing', () => {
    const spu: SpuSummaryRow = {
      spuId: '1',
      spuKey: 'SPU-CUP',
      spuName: '马克杯套装',
      skuCount: 3,
      platformCodes: ['AMAZON', 'TIKTOK'],
      listingCount: 5,
      currencies: ['EUR', 'USD'],
      metrics: [],
      variants: [],
    };
    const text = spuMergeEvidence(spu);
    expect(text).toContain('3 个 SKU');
    expect(text).toContain('2 个平台');
    expect(text).toContain('5 个平台商品');
  });

  it('变体占比之和不足 100% 时说明原因', () => {
    const response = {
      shareTotal: 80,
      variants: [{ quantity: 1 }],
    } as unknown as VariantDistributionResponse;
    expect(shareTotalNote(response)).toContain('不足 100%');
    expect(
      shareTotalNote({
        shareTotal: 100,
        variants: [{ quantity: 1 }],
      } as unknown as VariantDistributionResponse),
    ).toContain('100');
  });

  it('变体占比 null 显示「不可比」', () => {
    expect(shareText(null)).toBe('不可比');
    expect(shareText(40)).toBe('40.00%');
  });
});

describe('口径目录', () => {
  it('跨层引用写清分子分母各取自哪一层', () => {
    const text = metricFormulaText(definition());
    expect(text).toContain('SKU 层');
    expect(text).toContain('SPU 层');
    expect(text).toContain('跨币种合计');
  });

  it('同层引用不写层', () => {
    const text = metricFormulaText(
      definition({
        metricCode: 'PRODUCT_AVG_UNIT_PRICE',
        numeratorDimensionType: null,
        denominatorDimensionType: null,
        currencyScope: 'SAME',
        currencyAware: true,
      }),
    );
    expect(text).toContain('同币种');
    expect(text).not.toContain('层）');
  });

  it('变体销量占比只在 SKU 层出现', () => {
    const definitions = [
      definition(),
      definition({
        metricCode: 'PRODUCT_SALES_QUANTITY',
        displayName: '销量（件）',
        unit: 'QUANTITY',
        aggregation: 'SUM',
        numeratorMetricCode: null,
        denominatorMetricCode: null,
        numeratorDimensionType: null,
        denominatorDimensionType: null,
        currencyScope: 'ANY',
        currencyAware: false,
      }),
    ];
    expect(productColumns(definitions, 'SKU').map((c) => c.metricCode)).toContain(
      'PRODUCT_VARIANT_SHARE',
    );
    expect(productColumns(definitions, 'SPU').map((c) => c.metricCode)).not.toContain(
      'PRODUCT_VARIANT_SHARE',
    );
  });

  it('未登记的指标不进列', () => {
    expect(productColumns([definition()], 'SKU').map((c) => c.metricCode)).toEqual([
      'PRODUCT_VARIANT_SHARE',
    ]);
  });

  it('单位与聚合方式有中文名', () => {
    expect(metricUnitLabel('CURRENCY')).toBe('金额');
    expect(metricUnitLabel('QUANTITY')).toBe('数量');
  });
});

describe('页面状态：读不到 ≠ 没有数据', () => {
  const emptyResponse = {
    rows: [],
    status: 'NOT_PROJECTED',
    emptyReason: 'NO_FACTS',
    emptyNote: '这个窗口里没有投影任何商品维度事实。',
    dataUpdatedAt: null,
    dataUpdatedNote: '最近一次投影落库时刻。',
  } as unknown as ProductSalesResponse;

  it('三种状态分开', () => {
    expect(pageState(null, '')).toBe('loading');
    expect(pageState(null, 'boom')).toBe('error');
    expect(pageState(emptyResponse, '')).toBe('empty');
    expect(pageState({ rows: [row()] } as unknown as ProductSalesResponse, '')).toBe('ready');
  });

  it('空态文案原样用服务端给的', () => {
    expect(emptyText(emptyResponse)).toBe('这个窗口里没有投影任何商品维度事实。');
    expect(emptyReasonLabel('NO_FACTS')).toContain('还没有投影');
    expect(emptyReasonLabel(null)).toBe('');
  });
});

describe('数据延迟要显示', () => {
  it('空表时是「尚未投影」，不是长得像时间的假值', () => {
    const response = { dataUpdatedAt: null } as unknown as ProductSalesResponse;
    expect(dataUpdatedText(response)).toContain('尚未投影');
    expect(dataUpdatedText(response)).not.toContain('-infinity');
  });

  it('有投影时刻时原样显示', () => {
    const response = {
      dataUpdatedAt: '2026-10-26T12:00:00Z',
      dataUpdatedNote: '最近一次投影落库时刻。',
    } as unknown as ProductSalesResponse;
    expect(dataUpdatedText(response)).toContain('2026-10-26T12:00:00Z');
    expect(dataUpdatedNote(response)).toBe('最近一次投影落库时刻。');
  });
});

describe('能力声明', () => {
  it('上游模块与「确实拿不到」都原样列出', () => {
    const capabilities = {
      upstreamConnected: true,
      reportAvailable: true,
      dataUpdatedAtVisible: true,
      upstreamModules: ['3.1.2.1 SKU 列表'],
      notDelivered: ['Listing 层的销量归属有边界'],
      rules: [{ ruleCode: 'SPU_MERGE', description: 'SPU 汇总' }],
    };
    expect(upstreamModules(capabilities)).toEqual(['3.1.2.1 SKU 列表']);
    expect(notDeliveredLines(capabilities)).toEqual(['Listing 层的销量归属有边界']);
    expect(ruleNotes(capabilities)).toHaveLength(1);
    expect(notDeliveredLines(null)).toEqual([]);
  });
});

describe('其它展示格式化', () => {
  it('维度层与下拉项', () => {
    expect(levelLabel('SPU')).toContain('SPU');
    expect(levelOptions().map((o) => o.value)).toEqual(['SKU', 'SPU', 'LISTING']);
  });

  it('平台标签不猜平台', () => {
    expect(platformLabel('')).toBe('未标注平台');
    expect(platformCodesLabel([])).toBe('未标注平台');
    expect(platformCodesLabel(['AMAZON', 'TIKTOK'])).toBe('AMAZON、TIKTOK');
  });

  it('单元格没有指标时是「—」并说明原因', () => {
    const columns = productColumns(
      [
        definition({
          metricCode: 'PRODUCT_SALES_AMOUNT',
          displayName: '成交金额',
          unit: 'CURRENCY',
          aggregation: 'SUM',
          numeratorMetricCode: null,
          denominatorMetricCode: null,
          numeratorDimensionType: null,
          denominatorDimensionType: null,
          currencyScope: 'SAME',
          currencyAware: true,
        }),
      ],
      'SKU',
    );
    const column = columns[0]!;
    expect(cellText(row(), column)).toBe('—');
    expect(cellAvailability(row(), column)).toBe('NO_DATA');
    expect(cellNote(row(), column)).toContain('没有指标定义');
  });

  it('构成明细的订单标识与下单时刻', () => {
    const detail: ProductDrilldownRow = {
      sourceKind: 'ORDER',
      referenceId: 'SO-1#1',
      referenceLabel: 'SO-1',
      orderNo: 'SO-1',
      occurredAt: '2026-10-01T10:00:00Z',
      skuCode: 'SKU-RED-S',
      skuName: '红杯 S',
      quantity: 2,
      value: 50,
      currency: 'USD',
      bucketDate: '2026-10-01',
      timezone: 'UTC',
    };
    expect(drilldownReferenceText(detail)).toBe('SO-1');
    expect(occurredAtText(detail)).toBe('2026-10-01T10:00:00Z');
    expect(occurredAtText({ ...detail, occurredAt: null })).toBe('—');
  });

  it('刷新结果的状态与语义色', () => {
    expect(refreshStatusLabel('SUCCEEDED')).toBe('成功');
    expect(refreshStatusLabel('TRUNCATED')).toBe('已截断');
    expect(refreshStatusTone('FAILED')).toBe('danger');
    expect(refreshStatusTone('TRUNCATED')).toBe('warning');
  });

  it('时间范围与状态文案', () => {
    expect(rangeText('2026-10-01', '2026-10-30')).toBe('2026-10-01 ~ 2026-10-30');
    expect(defaultRange('2026-10-30')).toEqual({ from: '2026-10-01', to: '2026-10-30' });
    expect(statusLabel('PROJECTED')).toBe('已投影');
    expect(statusLabel('NOT_PROJECTED')).toBe('未投影');
  });
});
