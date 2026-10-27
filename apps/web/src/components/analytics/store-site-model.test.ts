import { describe, expect, it } from 'vitest';
import {
  METRIC_AMOUNT_CONTRIBUTION,
  METRIC_AMOUNT_SHARE,
  METRIC_SALES_AMOUNT,
  METRIC_SALES_QUANTITY,
  METRIC_NEW_STORE_CONTRIBUTION,
  amountText,
  availabilityLabel,
  baseRangeText,
  cellAvailability,
  cellNote,
  cellText,
  channelShareText,
  countText,
  coverageText,
  currencyLabel,
  defaultRange,
  dimensionLabel,
  dimensionOptions,
  drilldownKindLabel,
  emptyReasonLabel,
  metricFormulaText,
  metricUnitLabel,
  metricValueText,
  newVsExistingText,
  pageState,
  percentText,
  platformLabel,
  quantityText,
  refreshStatusLabel,
  shareVsContributionNote,
  statusLabel,
  storeAgeLabel,
  storeAgeTone,
  storeSiteColumns,
  unattributedText,
  availabilityTone,
} from './store-site-model';
import type {
  StoreSiteChannelShare,
  StoreSiteCoverage,
  StoreSiteMetricDefinitionView,
  StoreSiteMetricValue,
  StoreSiteNewVsExisting,
  StoreSiteResponse,
  StoreSiteRow,
  StoreSiteUnattributed,
} from './store-site-model';

/**
 * 13.2.2.1 店铺与站点前端模型的单元测试。
 *
 * <p>本文件盯的是**页面最容易做丢的那几件事**：
 *
 * <ul>
 *   <li>「算不出来」≠「等于 0」：{@code null} 一律显示成「—」/「不可比」，**不显示 0**；
 *   <li>多币种绝不相加：空币种 = 与币种无关，**不是「币种未知」**；
 *   <li>站点归属边界：「无法唯一归属到站点」必须被说出来；
 *   <li>占比与贡献度是**两个不同的数**（占比看当前窗口，贡献度看增量）；
 *   <li>新店增长贡献度**不出现在报表列里**（它是「新店这一组」的指标）。
 * </ul>
 */

function metric(
  metricCode: string,
  currency: string,
  value: number | null,
  availability = 'AVAILABLE',
  note = '',
): StoreSiteMetricValue {
  return {
    metricCode,
    currency,
    value,
    baseValue: null,
    delta: null,
    availability,
    availabilityNote: note,
  };
}

function row(metrics: StoreSiteMetricValue[]): StoreSiteRow {
  return {
    dimensionKey: 'shop-a',
    dimensionLabel: '亚马逊旗舰店',
    platformCode: 'AMAZON',
    platformName: '亚马逊',
    siteCode: 'US',
    siteName: '美国站',
    shopCode: 'shop-a',
    shopName: '亚马逊旗舰店',
    storeAge: 'EXISTING',
    newShopCount: 0,
    existingShopCount: 1,
    metrics,
    note: '',
  };
}

describe('13.2.2.1 展示格式化', () => {
  it('null 一律显示成「—」/「不可比」，绝不显示 0', () => {
    expect(amountText(null, 'USD')).toBe('—');
    expect(quantityText(null)).toBe('—');
    expect(countText(null)).toBe('—');
    expect(percentText(null)).toBe('不可比');
  });

  it('有值时按单位格式化', () => {
    expect(amountText(300, 'USD')).toBe('300.00 USD');
    expect(quantityText(5)).toBe('5.000');
    expect(countText(2)).toBe('2');
    expect(percentText(75)).toBe('75.00%');
  });

  it('空币种 = 与币种无关，不是「币种未知」', () => {
    expect(currencyLabel('')).toBe('与币种无关');
    expect(amountText(5, '')).toContain('与币种无关');
    expect(currencyLabel('USD')).toBe('USD');
  });

  it('指标取值按单位分派', () => {
    expect(metricValueText(metric(METRIC_SALES_AMOUNT, 'USD', 10), 'CURRENCY')).toBe('10.00 USD');
    expect(metricValueText(metric(METRIC_SALES_QUANTITY, '', 3), 'QUANTITY')).toBe('3.000');
    expect(metricValueText(metric(METRIC_AMOUNT_SHARE, 'USD', null, 'NO_DATA'), 'PERCENT')).toBe(
      '不可比',
    );
  });
});

describe('13.2.2.1 维度与语义色', () => {
  it('三个维度都有中文标签与下拉项', () => {
    expect(dimensionLabel('SHOP')).toBe('店铺');
    expect(dimensionLabel('SITE')).toBe('站点');
    expect(dimensionLabel('PLATFORM')).toBe('平台（渠道）');
    expect(dimensionOptions().map((o) => o.value)).toEqual(['SHOP', 'SITE', 'PLATFORM']);
  });

  it('空平台码显示「未标注平台」，绝不猜一个平台', () => {
    expect(platformLabel('')).toBe('未标注平台');
    expect(platformLabel('AMAZON')).toBe('AMAZON');
  });

  it('新店用 info 而不是 success', () => {
    expect(storeAgeTone('NEW')).toBe('info');
    expect(storeAgeTone('EXISTING')).toBe('neutral');
    expect(storeAgeLabel('NEW')).toBe('新店');
    expect(storeAgeLabel('EXISTING')).toBe('老店');
  });

  it('「算不出来」与「没有数据」都是 neutral（不是错误）', () => {
    expect(availabilityTone('AVAILABLE')).toBe('success');
    expect(availabilityTone('NO_DATA')).toBe('neutral');
    expect(availabilityTone('NOT_COMPARABLE')).toBe('neutral');
    expect(availabilityLabel('NOT_COMPARABLE')).toBe('不可比');
  });

  it('刷新结果的状态与语义色', () => {
    expect(refreshStatusLabel('TRUNCATED')).toBe('已截断');
    expect(refreshStatusLabel('FAILED')).toBe('失败');
  });
});

describe('13.2.2.1 报表的列', () => {
  const definitions: StoreSiteMetricDefinitionView[] = [
    {
      metricCode: METRIC_SALES_QUANTITY,
      displayName: '销量（件）',
      unit: 'QUANTITY',
      aggregation: 'SUM',
      baseMetricCode: null,
      currencyAware: false,
      description: '',
      ownerModule: '13.2.2.1',
    },
    {
      metricCode: METRIC_AMOUNT_CONTRIBUTION,
      displayName: '成交金额增长贡献度',
      unit: 'PERCENT',
      aggregation: 'GROUP_CONTRIBUTION',
      baseMetricCode: METRIC_SALES_AMOUNT,
      currencyAware: true,
      description: '',
      ownerModule: '13.2.2.1',
    },
  ];

  it('只列出该租户已登记的列', () => {
    const columns = storeSiteColumns(definitions);
    expect(columns.map((c) => c.metricCode)).toEqual([
      METRIC_SALES_QUANTITY,
      METRIC_AMOUNT_CONTRIBUTION,
    ]);
  });

  it('🔴 新店增长贡献度不出现在报表列里', () => {
    const columns = storeSiteColumns([
      ...definitions,
      {
        metricCode: METRIC_NEW_STORE_CONTRIBUTION,
        displayName: '新店增长贡献度',
        unit: 'PERCENT',
        aggregation: 'GROUP_CONTRIBUTION',
        baseMetricCode: METRIC_SALES_AMOUNT,
        currencyAware: true,
        description: '',
        ownerModule: '13.2.2.1',
      },
    ]);
    expect(columns.map((c) => c.metricCode)).not.toContain(METRIC_NEW_STORE_CONTRIBUTION);
  });

  it('口径说明里说清被比较的指标', () => {
    expect(metricFormulaText(definitions[1]!)).toContain(METRIC_SALES_AMOUNT);
    expect(metricUnitLabel('PERCENT')).toBe('百分比');
  });
});

describe('13.2.2.1 单元格', () => {
  const columns = storeSiteColumns([
    {
      metricCode: METRIC_SALES_AMOUNT,
      displayName: '成交金额',
      unit: 'CURRENCY',
      aggregation: 'SUM',
      baseMetricCode: null,
      currencyAware: true,
      description: '',
      ownerModule: '13.2.2.1',
    },
  ]);

  it('按币种分行的列把多个币种拼起来（前端不做任何加减）', () => {
    const r = row([
      metric(METRIC_SALES_AMOUNT, 'USD', 300),
      metric(METRIC_SALES_AMOUNT, 'EUR', 200),
    ]);
    expect(cellText(r, columns[0]!)).toBe('300.00 USD / 200.00 EUR');
  });

  it('不可比时给出原因，没有原因时显示「—」', () => {
    const r = row([metric(METRIC_SALES_AMOUNT, 'USD', null, 'NOT_COMPARABLE', '基期为 0')]);
    // 🔴 单元格里是「—」（这一格**算不出来**，绝不用 0 顶替）；
    //    「不可比」+ 原因在**下面那一行**（availability / availabilityNote）——
    //    与同卷 13.2.2.2（product-sales-model）的写法逐字一致，不在本车道另起一套。
    expect(cellText(r, columns[0]!)).toBe('—');
    expect(cellAvailability(r, columns[0]!)).toBe('NOT_COMPARABLE');
    expect(availabilityLabel(cellAvailability(r, columns[0]!))).toBe('不可比');
    expect(cellNote(r, columns[0]!)).toBe('基期为 0');
  });

  it('🔴 占比与贡献度是两组不同的指标，不许混为一谈', () => {
    expect(shareVsContributionNote()).toContain('占比看当前窗口');
    expect(shareVsContributionNote()).toContain('贡献度看增量');
  });
});

describe('13.2.2.1 渠道占比 / 新老店 / 无法归属', () => {
  it('渠道占比把多个币种拼起来', () => {
    const share: StoreSiteChannelShare = {
      platformCode: 'AMAZON',
      platformName: '亚马逊',
      metrics: [metric(METRIC_AMOUNT_SHARE, 'USD', 100)],
      note: '',
    };
    expect(channelShareText(share, METRIC_AMOUNT_SHARE, 'PERCENT')).toBe('100.00%');
  });

  it('新店 / 老店汇总把贡献度说出来', () => {
    const summary: StoreSiteNewVsExisting = {
      newShopCount: 1,
      existingShopCount: 2,
      newStoreMetrics: [],
      existingStoreMetrics: [],
      newStoreContribution: [metric(METRIC_NEW_STORE_CONTRIBUTION, 'EUR', 100)],
      note: '',
    };
    const text = newVsExistingText(summary);
    expect(text).toContain('新店 1 个');
    expect(text).toContain('老店 2 个');
    expect(text).toContain('100.00%');
  });

  it('🔴 「无法唯一归属到站点」必须被说出来（店铺编码列出来）', () => {
    const unattributed: StoreSiteUnattributed = {
      shopCount: 2,
      shopCodes: ['shop-b', 'shop-c'],
      metrics: [metric(METRIC_SALES_AMOUNT, 'EUR', 200)],
      reason: '',
      note: '',
    };
    const text = unattributedText(unattributed);
    expect(text).toContain('无法唯一归属到站点');
    expect(text).toContain('shop-b');
    expect(text).toContain('200.00 EUR');
    expect(unattributedText({ ...unattributed, shopCount: 0 })).toBe('');
  });
});

describe('13.2.2.1 覆盖度 / 页面状态', () => {
  it('覆盖度不足时说出「没有算进来」', () => {
    const coverage: StoreSiteCoverage = {
      orderCount: 10,
      orderCountWithDetail: 4,
      detailCoveragePercent: 40,
      truncated: true,
      note: '',
    };
    expect(coverageText(coverage)).toContain('没有算进来');
    expect(coverageText(coverage)).toContain('4 / 10');
  });

  it('读不到与没有数据是两件事', () => {
    expect(pageState(null, 'boom')).toBe('error');
    expect(pageState(null, '')).toBe('loading');
    const empty = { rows: [] } as unknown as StoreSiteResponse;
    expect(pageState(empty, '')).toBe('empty');
    expect(pageState({ rows: [row([])] } as unknown as StoreSiteResponse, '')).toBe('ready');
  });

  it('空态的原因标签与状态标签', () => {
    expect(emptyReasonLabel('NO_FACTS_IN_WINDOW')).toBe('这个窗口没有可拆解的成交事实');
    expect(statusLabel('OK')).toBe('正常');
  });

  it('默认窗口是最近 30 天（含今天）', () => {
    expect(defaultRange('2026-10-27')).toEqual({ from: '2026-09-28', to: '2026-10-27' });
  });

  it('基期文本说清楚是哪一段', () => {
    const response = {
      baseFrom: '2026-09-28',
      baseTo: '2026-09-29',
    } as unknown as StoreSiteResponse;
    expect(baseRangeText(response)).toBe('2026-09-28 ~ 2026-09-29');
  });

  it('构成明细的类型标签', () => {
    expect(drilldownKindLabel('ORDER')).toBe('订单级');
    expect(drilldownKindLabel('ORDER_LINE')).toBe('订单行级');
  });
});
