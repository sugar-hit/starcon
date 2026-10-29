import { describe, expect, it } from 'vitest';
import {
  amountText,
  availabilityLabel,
  availabilityTone,
  capabilitiesComponents,
  capabilitiesNotDelivered,
  componentReason,
  componentText,
  crossModeNote,
  dataUpdatedNote,
  dataUpdatedText,
  defaultRange,
  drilldownPageNote,
  emptyText,
  isCrossMode,
  netProfitLabel,
  netProfitText,
  netProfitTone,
  netMarginText,
  operatingModeLabel,
  orderModeText,
  orderScopeText,
  pageState,
  quantityText,
  rangeText,
  waterfallCumulativeText,
  waterfallNetProfitText,
  waterfallStepText,
} from './sku-profit-model';
import type {
  SkuProfitComponentAmount,
  SkuProfitOrderPage,
  SkuProfitOrderRow,
  SkuProfitResponse,
  SkuProfitRow,
  SkuProfitWaterfall,
  SkuProfitWaterfallStep,
} from './sku-profit-model';

/**
 * 13.4.1.2 SKU 利润 前端模型。
 *
 * 🔴 这些断言盯的是**页面最容易说错的五件事**：
 *
 * 1. **「算不出来」≠「等于 0」**。服务端给 `null` 的成本项与净利必须渲染成「—」
 *    与「不可比」，**不是** 0.00（假 0 会被读成「完全没花钱」）。
 * 2. **「不可归属到 SKU」与「缺这一项」分开说** —— 两者都让净利不可比，但原因不同。
 * 3. **退款是逐笔枚举的事实**：这个 SKU 不在其中 ⇒ 退款损失**真的是 0**。
 * 4. **多币种绝不相加**。金额必须带自己的币种；跨币种合计不存在。
 * 5. **前端不做业务算术**。净利、净利率、成本合计一律来自服务端，这里只做格式化。
 */

function component(
  overrides: Partial<SkuProfitComponentAmount> = {},
): SkuProfitComponentAmount {
  return {
    component: 'COGS',
    label: '采购成本',
    amount: 40,
    quantity: 0,
    status: 'AVAILABLE',
    available: true,
    reason: '',
    ...overrides,
  };
}

function row(overrides: Partial<SkuProfitRow> = {}): SkuProfitRow {
  return {
    skuCode: 'SKU-1',
    currency: 'USD',
    components: [
      component({ component: 'REVENUE', label: '单品收入', amount: 100 }),
      component({ component: 'COGS', label: '采购成本', amount: 40 }),
    ],
    totalCost: 40,
    netProfit: 60,
    netMargin: 60,
    netProfitComparable: true,
    missingComponents: [],
    modeBreakdown: [
      { operatingMode: 'SELF_FULFILLED', operatingModeLabel: '自发货', revenue: 100, note: '' },
    ],
    factCount: 2,
    note: '单品收入取订单行金额口径。',
    ...overrides,
  };
}

function response(overrides: Partial<SkuProfitResponse> = {}): SkuProfitResponse {
  return {
    from: '2026-10-01',
    to: '2026-10-01',
    dimension: 'SKU',
    rows: [row()],
    skuCount: 1,
    comparableCount: 1,
    notComparableCount: 0,
    dataUpdatedAt: '2026-10-02T03:00:00Z',
    dataUpdatedNote: '最近一次投影落库时刻。',
    caliberNote: '口径说明。',
    warnings: [],
    ...overrides,
  };
}

function step(overrides: Partial<SkuProfitWaterfallStep> = {}): SkuProfitWaterfallStep {
  return {
    step: 1,
    component: 'REVENUE',
    label: '单品收入',
    amount: 100,
    sign: '+',
    cumulative: 100,
    available: true,
    reason: '',
    ...overrides,
  };
}

describe('13.4.1.2 SKU 利润 · 金额格式化', () => {
  it('null 渲染成「—」，绝不用 0 顶替', () => {
    expect(amountText(null, 'USD')).toBe('—');
    expect(amountText(0, 'USD')).toBe('0.00 USD');
  });

  it('金额带自己的币种（多币种绝不相加）', () => {
    expect(amountText(1234.5, 'USD')).toBe('1,234.50 USD');
    expect(amountText(1234.5, 'EUR')).toBe('1,234.50 EUR');
  });

  it('净利 null ⇒ 「—（不可比）」，不是 0', () => {
    expect(netProfitText(null, 'USD')).toBe('—（不可比）');
    expect(netProfitText(0, 'USD')).toBe('0.00 USD');
  });

  it('净利率 null ⇒ 「不可比」，不是 0%', () => {
    expect(netMarginText(null)).toBe('不可比');
    expect(netMarginText(0)).toBe('0.00%');
    expect(netMarginText(12.3456)).toBe('12.35%');
  });

  it('成本项没有值时是「—」并带原因，不是 0', () => {
    const missing = component({
      component: 'PLATFORM_FEE',
      label: '平台费',
      amount: null,
      quantity: null,
      status: 'NOT_ATTRIBUTABLE',
      available: false,
      reason: '上游有这笔成本，但只分摊到店铺 / 订单 —— 不可归属，不是 0。',
    });
    expect(componentText(missing, 'USD')).toBe('—');
    expect(componentReason(missing)).toContain('不是 0');
  });

  it('数量没有值时是「—」', () => {
    expect(quantityText(null)).toBe('—');
    expect(quantityText(2.5)).toBe('2.500');
  });
});

describe('13.4.1.2 SKU 利润 · 四种取值状态分开说', () => {
  it('状态的中文名与语义色', () => {
    expect(availabilityLabel('AVAILABLE')).toBe('有事实');
    expect(availabilityLabel('MISSING')).toBe('缺这一项');
    expect(availabilityLabel('NOT_ATTRIBUTABLE')).toBe('不可归属到 SKU');
    expect(availabilityLabel('NOT_APPLICABLE')).toBe('不适用');
    expect(availabilityLabel('SOMETHING_NEW')).toBe('SOMETHING_NEW');
  });

  it('语义色：可用 success / 缺 warning / 不可归属 danger / 不适用 neutral', () => {
    expect(availabilityTone('AVAILABLE')).toBe('success');
    expect(availabilityTone('MISSING')).toBe('warning');
    expect(availabilityTone('NOT_ATTRIBUTABLE')).toBe('danger');
    expect(availabilityTone('NOT_APPLICABLE')).toBe('neutral');
  });

  it('「不可归属」与「缺这一项」不是同一件事（文案不同、色不同）', () => {
    expect(availabilityLabel('NOT_ATTRIBUTABLE')).not.toBe(availabilityLabel('MISSING'));
    expect(availabilityTone('NOT_ATTRIBUTABLE')).not.toBe(availabilityTone('MISSING'));
  });
});

describe('13.4.1.2 SKU 利润 · 净利语义', () => {
  it('净利语义色：盈利 success / 亏损 danger / 不可比 neutral', () => {
    expect(netProfitTone(row({ netProfit: 10, netProfitComparable: true }))).toBe('success');
    expect(netProfitTone(row({ netProfit: -10, netProfitComparable: true }))).toBe('danger');
    expect(netProfitTone(row({ netProfit: null, netProfitComparable: false }))).toBe('neutral');
  });

  it('净利状态标签', () => {
    expect(netProfitLabel(row({ netProfit: 10, netProfitComparable: true }))).toBe('盈利');
    expect(netProfitLabel(row({ netProfit: -10, netProfitComparable: true }))).toBe('亏损');
    expect(netProfitLabel(row({ netProfit: null, netProfitComparable: false }))).toBe('不可比');
  });
});

describe('13.4.1.2 SKU 利润 · 跨模式口径要标出来', () => {
  it('一个 SKU 在多个经营模式下有销售 ⇒ 跨模式口径', () => {
    const cross = row({
      modeBreakdown: [
        { operatingMode: 'SELF_FULFILLED', operatingModeLabel: '自发货', revenue: 60, note: '' },
        { operatingMode: 'FULL_CONSIGNMENT', operatingModeLabel: '全托管', revenue: 40, note: '' },
      ],
    });
    expect(isCrossMode(cross)).toBe(true);
    expect(crossModeNote(cross)).toContain('跨模式口径');
    expect(isCrossMode(row())).toBe(false);
    expect(crossModeNote(row())).toBe('');
  });

  it('经营模式的中文名来自服务端，未知模式原样显示', () => {
    expect(operatingModeLabel('SELF_FULFILLED', '自发货')).toBe('自发货');
    expect(operatingModeLabel('SOMETHING_NEW', 'SOMETHING_NEW')).toBe('SOMETHING_NEW');
    expect(operatingModeLabel('UNKNOWN', '未归属')).toBe('未归属');
  });
});

describe('13.4.1.2 SKU 利润 · 瀑布图', () => {
  it('每一步拿不到就是「—」，不是 0', () => {
    expect(waterfallStepText(step())).toBe('100.00');
    expect(waterfallStepText(step({ amount: null, available: false, cumulative: null }))).toBe('—');
    expect(waterfallCumulativeText(step({ cumulative: null }))).toBe('—');
  });

  it('瀑布的净利 null ⇒ 「—（不可比）」', () => {
    const waterfall: SkuProfitWaterfall = {
      skuCode: 'SKU-1',
      currency: 'USD',
      steps: [step()],
      netProfit: null,
      netProfitComparable: false,
      note: '',
    };
    expect(waterfallNetProfitText(waterfall)).toBe('—（不可比）');
    expect(waterfallNetProfitText(null)).toBe('—（不可比）');
    expect(waterfallNetProfitText({ ...waterfall, netProfit: 60, netProfitComparable: true })).toBe(
      '60.00',
    );
  });
});

describe('13.4.1.2 SKU 利润 · 订单明细钻取', () => {
  const item: SkuProfitOrderRow = {
    component: 'COGS',
    componentLabel: '采购成本',
    operatingMode: 'SELF_FULFILLED',
    operatingModeLabel: '自发货',
    orderRef: 'ORDER-1',
    skuCode: 'SKU-1',
    periodStart: '2026-10-01',
    periodEnd: '2026-10-01',
    currency: 'USD',
    amount: 40,
    quantity: 1,
    sourceService: 'finance',
    sourceReference: 'cost-allocation:1',
    note: '',
  };

  it('下钻分页说明带真计数', () => {
    const page: SkuProfitOrderPage = {
      items: [],
      page: 1,
      size: 50,
      total: 120,
      skuCode: 'SKU-1',
      currency: 'USD',
      note: '',
    };
    expect(drilldownPageNote(page)).toContain('共 120 条');
    expect(drilldownPageNote(null)).toBe('');
  });

  it('下钻一行的归属取服务端原值', () => {
    expect(orderScopeText(item)).toContain('订单 ORDER-1');
    expect(orderScopeText({ ...item, orderRef: '' })).toContain('没有订单归属');
  });

  it('未归属的模式要标出来 —— 它不是一种经营模式', () => {
    expect(orderModeText(item)).toBe('自发货');
    expect(orderModeText({ ...item, operatingMode: 'UNKNOWN', operatingModeLabel: '未归属' })).toContain(
      '未归属',
    );
  });
});

describe('13.4.1.2 SKU 利润 · 页面状态', () => {
  it('数据更新时间常驻：没投影过也要有一句话', () => {
    expect(dataUpdatedText(null)).toBe('');
    expect(dataUpdatedText(response({ dataUpdatedAt: null }))).toBe(
      '数据更新时间：尚未投影任何 SKU 级事实',
    );
    expect(dataUpdatedText(response())).toContain('数据截至');
    expect(dataUpdatedNote(response())).toBe('最近一次投影落库时刻。');
  });

  it('「读不到」与「没有数据」分开说', () => {
    expect(pageState(null, 'boom')).toBe('error');
    expect(pageState(null, '')).toBe('loading');
    expect(pageState(response({ rows: [] }), '')).toBe('empty');
    expect(pageState(response(), '')).toBe('ready');
  });

  it('空态文案说清楚「不是利润为 0」', () => {
    expect(emptyText(response({ rows: [] }))).toContain('不是「利润为 0」');
    expect(emptyText(response())).toBe('');
  });

  it('时间范围与默认窗口', () => {
    expect(rangeText('2026-10-01', '2026-10-31')).toBe('2026-10-01 ~ 2026-10-31');
    const range = defaultRange('2026-10-31');
    expect(range.to).toBe('2026-10-31');
    expect(range.from).toBe('2026-10-02');
  });
});

describe('13.4.1.2 SKU 利润 · 能力声明', () => {
  it('覆盖的成本项与确实算不出来的一段文本', () => {
    const capabilities = {
      upstreamConnected: true,
      components: ['单品收入', '采购成本', '退款损失'],
      dimensions: ['SKU'],
      upstreams: ['13.4.1.1 的利润事实'],
      notDelivered: ['SKU 级成本常常拿不到'],
      ownerNote: '口径归属。',
    };
    expect(capabilitiesComponents(capabilities)).toContain('退款损失');
    expect(capabilitiesNotDelivered(capabilities)).toContain('SKU 级成本常常拿不到');
    expect(capabilitiesComponents(null)).toBe('');
    expect(capabilitiesNotDelivered(null)).toBe('');
  });
});
