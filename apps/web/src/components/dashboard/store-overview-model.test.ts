import { describe, expect, it } from 'vitest';
import {
  DIMENSION_AD_SPEND,
  DIMENSION_ORDER_COUNT,
  DIMENSION_PROFIT_MARGIN,
  DIMENSION_SALES,
  SALES_CALIBERS,
  caliberTooltip,
  capabilityOf,
  classificationLabel,
  classificationTone,
  currenciesOf,
  dimensionLines,
  dimensionOf,
  emptyState,
  formatAmount,
  formatCount,
  formatDecimal,
  formatValue,
  isNotConnected,
  metricValueOf,
  notConnectedDimensions,
  platformOptions,
  salesLines,
  statusLabel,
  statusTone,
  valueOf,
  windowLabel,
  type StoreOverviewCapabilities,
  type StoreOverviewDimension,
  type StoreOverviewResponse,
  type StoreOverviewRow,
  type StoreOverviewShop,
} from './store-overview-model';

/**
 * 1.1.2.2 店铺概览 前端模型单测。
 *
 * <p>这里钉住的是**前端最容易做错的那几件事**：
 *
 * <ol>
 *   <li>「销售额」**必须三个口径并列**（平台侧 / 我方还原 / 口径差）——
 *       少一条就等于抹掉口径差异；</li>
 *   <li>「算不出来」不能显示成 0；</li>
 *   <li>接不通的维度必须写明**归属模块号**，而且不给公式也不给分母；</li>
 *   <li>金额在展示层走**十进制字面串 + BigInt 缩放整数**（不碰浮点）；
 *       HALF_UP 下 1.005 ⇒ 1.01，浮点会算成 1.00；</li>
 *   <li>多币种**不相加**；</li>
 *   <li>空态要区分「没有店铺档案」/「有店铺但这个窗口没投影」/「服务不可达」。</li>
 * </ol>
 */

function metric(
  metricCode: string,
  currency: string,
  value: number | null,
  overrides: Partial<{
    unit: string;
    availability: string;
    availabilityNote: string;
    displayName: string;
  }> = {},
) {
  return {
    metricCode,
    displayName: overrides.displayName ?? metricCode,
    unit: overrides.unit ?? 'CURRENCY',
    ownerModule: '9.1.1.1 平台单列表（trade）',
    currency,
    value,
    availability: overrides.availability ?? (value === null ? 'NO_DATA' : 'AVAILABLE'),
    availabilityNote:
      overrides.availabilityNote ??
      (value === null ? '该店铺在这个窗口、这个币种下没有这个口径的事实数据（不是 0）。' : ''),
  };
}

function dimension(
  key: string,
  displayName: string,
  overrides: Partial<StoreOverviewDimension> = {},
): StoreOverviewDimension {
  return {
    key,
    displayName,
    unit: 'CURRENCY',
    currencyAware: true,
    available: true,
    ownerModule: '9.1.1.1 平台单列表（trade）',
    caliber: '',
    status: 'AVAILABLE',
    values: [],
    statusNote: '',
    ...overrides,
  };
}

function salesDimension(overrides: Partial<StoreOverviewDimension> = {}): StoreOverviewDimension {
  return dimension(DIMENSION_SALES, '销售额', {
    values: [
      metric('STORE_GMV_PLATFORM', 'USD', 1234.565),
      metric('STORE_GMV_RESTORED', 'USD', 1200),
      metric('STORE_CALIBER_GAP', 'USD', 34.565),
    ],
    ...overrides,
  });
}

function row(overrides: Partial<StoreOverviewRow> = {}): StoreOverviewRow {
  return {
    shopCode: 'shop-a',
    shopName: '美国旗舰店',
    platformCode: 'AMAZON',
    platformName: 'Amazon',
    currencies: ['USD'],
    hasFactsInWindow: true,
    dimensions: [],
    classification: 'STABLE',
    classificationNote: '',
    analysisPath: '/analytics/dashboard/store-comparison',
    analysisTargetName: '13.1.1.2 多店对比（13 店铺分析）',
    ordersPath: '/orders/platform-sync',
    note: '',
    ...overrides,
  };
}

function shop(overrides: Partial<StoreOverviewShop> = {}): StoreOverviewShop {
  return {
    shopCode: 'shop-a',
    shopName: '美国旗舰店',
    platformCode: 'AMAZON',
    platformName: 'Amazon',
    hasFactsInWindow: true,
    lastFactAt: '2026-10-26T02:00:00Z',
    ...overrides,
  };
}

describe('十进制精确的展示格式化', () => {
  it('HALF_UP：1.005 ⇒ 1.01（浮点会算成 1.00）', () => {
    expect(formatDecimal(1.005, 2, 2, false)).toBe('1.01');
  });

  it('HALF_UP 远离零：-1.005 ⇒ -1.01', () => {
    expect(formatDecimal(-1.005, 2, 2, false)).toBe('-1.01');
  });

  it('0.1 + 0.2 的浮点尾巴不会被带进展示串', () => {
    expect(formatDecimal(0.1 + 0.2, 2, 2, false)).toBe('0.30');
  });

  it('金额千分位 + 固定两位小数', () => {
    expect(formatAmount(1234.565)).toBe('1,234.57');
    expect(formatAmount(1234567.5)).toBe('1,234,567.50');
    expect(formatAmount(0)).toBe('0.00');
  });

  it('计数是整数 + 千分位', () => {
    expect(formatCount(1200)).toBe('1,200');
    expect(formatCount(0)).toBe('0');
  });

  it('null / undefined ⇒ 「—」，**不是 0**', () => {
    expect(formatAmount(null)).toBe('—');
    expect(formatAmount(undefined)).toBe('—');
    expect(formatCount(null)).toBe('—');
    expect(formatValue(null, 'CURRENCY')).toBe('—');
    expect(formatValue(null, 'COUNT')).toBe('—');
  });

  it('0 本身仍然是 0.00（「没有事实」与「事实是 0」是两件事）', () => {
    expect(formatAmount(0)).toBe('0.00');
  });
});

describe('销售额一格：三个口径并列（核心验收项）', () => {
  it('每个币种恒定三条：平台侧 / 我方还原 / 口径差', () => {
    const lines = salesLines(salesDimension());
    expect(lines).toHaveLength(3);
    expect(lines.map((line) => line.label)).toEqual(['平台侧', '我方还原', '口径差']);
    expect(lines.map((line) => line.metricCode)).toEqual([
      'STORE_GMV_PLATFORM',
      'STORE_GMV_RESTORED',
      'STORE_CALIBER_GAP',
    ]);
  });

  it('三个口径的值原样透传 —— 前端不自己算差值', () => {
    const lines = salesLines(salesDimension());
    expect(lines[0]?.text).toBe('1,234.57');
    expect(lines[1]?.text).toBe('1,200.00');
    expect(lines[2]?.text).toBe('34.57');
  });

  it('口径差缺值时是「—」，**不是 0、也不是自己减出来的 0**', () => {
    const lines = salesLines(
      salesDimension({
        values: [
          metric('STORE_GMV_PLATFORM', 'USD', 1200),
          metric('STORE_GMV_RESTORED', 'USD', 1200),
          metric('STORE_CALIBER_GAP', 'USD', null),
        ],
      }),
    );
    expect(lines).toHaveLength(3);
    expect(lines[2]?.text).toBe('—');
    expect(lines[2]?.availability).toBe('NO_DATA');
  });

  it('多币种时每个币种各三条，**不跨币种合并**', () => {
    const lines = salesLines(
      salesDimension({
        values: [
          metric('STORE_GMV_PLATFORM', 'USD', 100),
          metric('STORE_GMV_RESTORED', 'USD', 90),
          metric('STORE_CALIBER_GAP', 'USD', 10),
          metric('STORE_GMV_PLATFORM', 'EUR', 50),
          metric('STORE_GMV_RESTORED', 'EUR', 45),
          metric('STORE_CALIBER_GAP', 'EUR', 5),
        ],
      }),
    );
    expect(lines).toHaveLength(6);
    expect(lines.filter((line) => line.currency === 'USD')).toHaveLength(3);
    expect(lines.filter((line) => line.currency === 'EUR')).toHaveLength(3);
    // 界面上永远没有跨币种的「总计」—— 没有第四种行。
    expect(lines.every((line) => line.currency === 'USD' || line.currency === 'EUR')).toBe(true);
  });

  it('未接通的维度一条取值都不给', () => {
    const lines = salesLines(
      salesDimension({ available: false, status: 'NOT_CONNECTED', values: [] }),
    );
    expect(lines).toHaveLength(0);
  });

  it('SALES_CALIBERS 恰好三条，且顺序固定', () => {
    expect(SALES_CALIBERS.map((caliber) => caliber.metricCode)).toEqual([
      'STORE_GMV_PLATFORM',
      'STORE_GMV_RESTORED',
      'STORE_CALIBER_GAP',
    ]);
  });
});

describe('接不通的维度：写明归属模块号，不给公式也不给分母', () => {
  const notConnected = dimension(DIMENSION_PROFIT_MARGIN, '利润率', {
    unit: 'PERCENT',
    available: false,
    status: 'NOT_CONNECTED',
    ownerModule: '13.2 利润分析（未交付）/ 12.4.1.2 成本计算（finance）',
    statusNote: '店铺口径表里没有利润 / 毛利类的店铺级指标码。',
    values: [],
  });

  it('isNotConnected 认得出未接通', () => {
    expect(isNotConnected(notConnected)).toBe(true);
    expect(isNotConnected(salesDimension())).toBe(false);
    expect(isNotConnected(undefined)).toBe(true);
  });

  it('归属模块号不为空', () => {
    expect(notConnected.ownerModule).toContain('13.2');
  });

  it('一个取值都不给（不给公式也不给分母）', () => {
    expect(notConnected.values).toHaveLength(0);
    expect(salesLines(notConnected)).toHaveLength(0);
    expect(dimensionLines(notConnected)).toHaveLength(0);
  });

  it('tooltip 里带上归属', () => {
    expect(caliberTooltip(notConnected)).toContain('口径归属');
  });
});

describe('订单量与广告花费', () => {
  it('订单量是计数', () => {
    const orders = dimension(DIMENSION_ORDER_COUNT, '订单量', {
      unit: 'COUNT',
      values: [metric('STORE_ORDER_COUNT', 'USD', 1200, { unit: 'COUNT' })],
    });
    const lines = dimensionLines(orders);
    expect(lines).toHaveLength(1);
    expect(lines[0]?.text).toBe('1,200');
  });

  it('广告花费没有事实时是「—」+ 原因，**不是 0**', () => {
    const ads = dimension(DIMENSION_AD_SPEND, '广告花费', {
      values: [metric('STORE_AD_SPEND', 'USD', null)],
    });
    const lines = dimensionLines(ads);
    expect(lines[0]?.text).toBe('—');
    expect(lines[0]?.availability).toBe('NO_DATA');
    expect(lines[0]?.note).not.toBe('');
  });
});

describe('取值定位', () => {
  it('dimensionOf 按 key 取维度', () => {
    const sales = salesDimension();
    const target = row({ dimensions: [sales] });
    expect(dimensionOf(target, DIMENSION_SALES)).toBe(sales);
    expect(dimensionOf(target, DIMENSION_AD_SPEND)).toBeUndefined();
  });

  it('currenciesOf 去重且保序', () => {
    const dims = salesDimension({
      values: [
        metric('STORE_GMV_PLATFORM', 'USD', 1),
        metric('STORE_GMV_RESTORED', 'EUR', 1),
        metric('STORE_CALIBER_GAP', 'USD', 1),
      ],
    });
    expect(currenciesOf(dims)).toEqual(['USD', 'EUR']);
  });

  it('metricValueOf / valueOf 取不到时是 undefined / null（**不是 0**）', () => {
    const dims = salesDimension();
    expect(metricValueOf(dims, 'STORE_GMV_PLATFORM', 'USD')?.value).toBe(1234.565);
    expect(metricValueOf(dims, 'STORE_GMV_PLATFORM', 'JPY')).toBeUndefined();
    expect(valueOf(dims, 'STORE_GMV_PLATFORM', 'JPY')).toBeNull();
  });
});

describe('状态与分类', () => {
  it('维度状态标签', () => {
    expect(statusLabel('AVAILABLE')).toBe('有数据');
    expect(statusLabel('NO_DATA')).toBe('该窗口没有事实');
    expect(statusLabel('NOT_CONNECTED')).toBe('未接通');
    expect(statusTone('AVAILABLE')).toBe('success');
    expect(statusTone('NO_DATA')).toBe('warning');
    expect(statusTone('NOT_CONNECTED')).toBe('neutral');
  });

  it('店铺分类标签', () => {
    expect(classificationLabel('GROWTH')).toBe('增长');
    expect(classificationLabel('LAGGARD')).toBe('掉队');
    expect(classificationLabel('STABLE')).toBe('稳定');
    expect(classificationLabel('UNCLASSIFIED')).toBe('不可比');
    expect(classificationTone('LAGGARD')).toBe('warning');
    expect(classificationTone('GROWTH')).toBe('success');
    expect(classificationTone('UNCLASSIFIED')).toBe('neutral');
  });

  it('时间口径标签', () => {
    expect(windowLabel('TODAY')).toBe('今日');
    expect(windowLabel('YESTERDAY')).toBe('昨日');
    expect(windowLabel('THIS_MONTH')).toBe('本月');
    expect(windowLabel('CUSTOM')).toBe('自定义区间');
  });
});

describe('平台筛选项', () => {
  it('同一个平台只出现一次', () => {
    const options = platformOptions([
      shop({ shopCode: 'shop-a', platformCode: 'AMAZON', platformName: 'Amazon' }),
      shop({ shopCode: 'shop-b', platformCode: 'AMAZON', platformName: 'Amazon' }),
      shop({ shopCode: 'shop-c', platformCode: 'SHOPEE', platformName: 'Shopee' }),
    ]);
    expect(options).toHaveLength(2);
    expect(options.map((option) => option.platformCode)).toEqual(['AMAZON', 'SHOPEE']);
  });

  it('没有平台归属的店铺不产生筛选项', () => {
    const options = platformOptions([shop({ platformCode: '', platformName: '' })]);
    expect(options).toHaveLength(0);
  });
});

describe('能力声明', () => {
  const caps: StoreOverviewCapabilities = {
    upstreamConnected: true,
    dimensions: [
      {
        key: DIMENSION_SALES,
        displayName: '销售额',
        available: true,
        ownerModule: '9.1.1.1 平台单列表（trade）',
        caliber: '',
        unit: 'CURRENCY',
        currencyAware: true,
        sourceKind: 'STORE_METRIC',
        calibers: [],
        unavailableReason: '',
        note: '',
      },
      {
        key: DIMENSION_PROFIT_MARGIN,
        displayName: '利润率',
        available: false,
        ownerModule: '13.2 利润分析（未交付）/ 12.4.1.2 成本计算（finance）',
        caliber: '',
        unit: 'PERCENT',
        currencyAware: true,
        sourceKind: 'NOT_CONNECTED',
        calibers: [],
        unavailableReason: '店铺口径表里没有利润 / 毛利类的店铺级指标码。',
        note: '',
      },
    ],
    upstreamModules: [],
    notDelivered: [],
    rules: [],
    notes: [],
  };

  it('能按 key 取到某个维度的声明', () => {
    expect(capabilityOf(caps, DIMENSION_PROFIT_MARGIN)?.available).toBe(false);
    expect(capabilityOf(caps, 'store.not-a-dimension')).toBeUndefined();
    expect(capabilityOf(null, DIMENSION_SALES)).toBeUndefined();
  });

  it('未接通的维度逐条列出来', () => {
    const missing = notConnectedDimensions(caps);
    expect(missing).toHaveLength(1);
    expect(missing[0]?.ownerModule).toContain('13.2');
  });
});

describe('空态：三种原因文案不同', () => {
  it('加载失败', () => {
    const state = emptyState(false, null);
    expect(state.title).toContain('加载失败');
  });

  it('没有店铺档案', () => {
    const response = { emptyReason: 'NO_STORES' } as unknown as StoreOverviewResponse;
    const state = emptyState(true, response);
    expect(state.title).toContain('店铺档案');
  });

  it('有店铺但这个窗口没投影', () => {
    const response = { emptyReason: 'NOT_PROJECTED' } as unknown as StoreOverviewResponse;
    const state = emptyState(true, response);
    expect(state.title).toContain('投影');
    expect(state.title).not.toBe(
      emptyState(true, { emptyReason: 'NO_STORES' } as StoreOverviewResponse).title,
    );
  });

  it('有数据时不该给空态', () => {
    const response = {
      emptyReason: null,
      rows: [row()],
    } as unknown as StoreOverviewResponse;
    expect(emptyState(true, response).title).toContain('没有可对比的店铺');
  });
});
