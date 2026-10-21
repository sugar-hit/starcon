import { describe, expect, it } from 'vitest';

import {
  assumptionGaps,
  decisionLabel,
  decisionTone,
  emptyRequest,
  missingGaps,
  missingRows,
  moneyText,
  moneyWithCurrency,
  percentText,
  quantityText,
  rowStatusText,
  rowStatusTone,
  sourceKindLabel,
  sourceTone,
  summaryStats,
  tierSummary,
  validateRequest,
  waterfallRows,
} from './profit-calculator-model';
import type { CalculateRequest, ProfitCalculation, WaterfallLine } from './profit-calculator-model';

/**
 * 5.3.1.1 利润测算器前端模型的纯函数断言。
 *
 * <p>这些函数看着不起眼，但它们守的是蓝图加粗句的**技术落点**：
 * 「必须显式列出每一项扣减并允许展开查看来源，不能只给一个毛利率数字」——
 * 以及它旁边那条隐含的纪律：「读不到 ≠ 确实是 0」「成本不完整 ≠ 毛利率是 0%」。
 */

function line(overrides: Partial<WaterfallLine> = {}): WaterfallLine {
  return {
    code: 'PURCHASE',
    label: '采购成本',
    deduction: true,
    available: true,
    partial: false,
    sourceKind: 'DERIVED',
    ownerModule: '5.2.1.1 成本构成',
    basis: '按移动加权派生',
    unavailableReason: null,
    amount: 10,
    totalAmount: 10,
    currency: 'USD',
    source: {
      ownerModule: '5.2.1.1 成本构成',
      service: 'catalog',
      table: 'vendor_delivery_record',
      kind: 'DERIVED',
      basis: '按移动加权派生',
      effectiveFrom: '2026-10-10T00:00Z',
      trace: '同一份成本构成',
      details: [{ label: '成本法', value: '移动加权' }],
    },
    ...overrides,
  };
}

function calculation(overrides: Partial<ProfitCalculation> = {}): ProfitCalculation {
  return {
    skuId: 'sku-1',
    skuCode: 'SKU-1',
    skuName: '商品一',
    platformCode: 'AMAZON',
    siteCode: 'US',
    currency: 'USD',
    costCurrency: 'USD',
    mode: 'PRICE',
    quantity: 1,
    decisionBasis: 'COMPLETE_FROM_RULES',
    decisionReady: true,
    blockingGaps: [],
    fx: {
      costCurrency: 'USD',
      currency: 'USD',
      rate: 1,
      direction: '1 USD = 1 USD',
      sourceKind: 'SAME_CURRENCY',
      ownerModule: '—',
      available: true,
      note: '同币种',
    },
    waterfall: [],
    revenue: 100,
    knownDeductionTotal: 16,
    revenueLeftForMissingDeductions: 84,
    grossProfit: 64,
    grossMarginRate: 0.64,
    breakEvenPrice: 24.7059,
    targetMarginPrice: 35,
    breakEvenVolume: 2,
    unitContribution: 64,
    logisticsBands: [],
    costMethod: '移动加权',
    costEffectiveFrom: '2026-10-10T00:00Z',
    grossProfitSuppressedReason: '',
    breakEvenPriceNote: '',
    targetMarginPriceNote: '',
    breakEvenVolumeNote: '',
    decisionNote: '',
    ...overrides,
  };
}

describe('金额与毛利率渲染', () => {
  it('取不到与未维护都渲染成文字而不是 0.00', () => {
    // 🔴 渲染成 0.00 的后果：单位成本偏低、毛利偏高，而页面上看不出任何异常。
    expect(moneyText(null)).toBe('未计入');
    expect(moneyText(undefined)).toBe('未计入');
    expect(moneyText(null, '未维护')).toBe('未维护');
    expect(moneyText(0)).toBe('0');
  });

  it('金额带币种与完整精度', () => {
    expect(moneyWithCurrency(24.7059, 'USD')).toBe('24.7059 USD');
    // 🔴 取不到时不补币种：「未计入 USD」会让人以为这是个金额。
    expect(moneyWithCurrency(null, 'USD')).toBe('未计入');
    // 末尾的 0 去掉（10.0000 → 10），但 0 仍然是 0 而不是空。
    expect(moneyText(10)).toBe('10');
    expect(moneyText(0)).toBe('0');
  });

  it('毛利率不可信时不给数字', () => {
    expect(percentText(null)).toBe('不可信（成本不完整）');
    expect(percentText(0.64)).toBe('64%');
    expect(percentText(0.255)).toBe('25.5%');
  });

  it('数量保留 6 位口径', () => {
    expect(quantityText(2)).toBe('2');
    expect(quantityText(1.5)).toBe('1.5');
    expect(quantityText(null)).toBe('—');
  });
});

describe('决策就绪度', () => {
  it('成本不完整必须是 danger 且写明不可信', () => {
    expect(decisionTone('INCOMPLETE')).toBe('danger');
    expect(decisionLabel('INCOMPLETE')).toBe('成本不完整');
    expect(decisionTone('COMPLETE_FROM_MANUAL_ASSUMPTIONS')).toBe('warning');
    expect(decisionTone('COMPLETE_FROM_RULES')).toBe('success');
  });

  it('手工假设与上游规则要分得开', () => {
    expect(sourceKindLabel('MANUAL_ASSUMPTION')).toBe('手工假设');
    expect(sourceTone('MANUAL_ASSUMPTION')).toBe('warning');
    expect(sourceKindLabel('DERIVED')).toBe('派生值');
    expect(sourceKindLabel('INPUT')).toBe('本次输入');
  });
});

describe('瀑布图与缺口', () => {
  it('每一项扣减都有一行，缺项也在行里', () => {
    const rows = waterfallRows(
      calculation({
        waterfall: [
          line({ code: 'REVENUE', label: '售价', deduction: false, sourceKind: 'INPUT' }),
          line({ code: 'PLATFORM_FEE', label: '平台费用', available: false, amount: null }),
          line({ code: 'LOGISTICS', label: '物流成本', available: false, amount: null }),
        ],
      }),
    );
    expect(rows).toHaveLength(3);
    // 缺的那两行也在，而且被单独挑出来。
    expect(missingRows(calculation({ waterfall: rows })).map((r) => r.code)).toEqual([
      'PLATFORM_FEE',
      'LOGISTICS',
    ]);
  });

  it('缺项行的状态写明「未交付」而不是空白', () => {
    const missing = line({
      available: false,
      amount: null,
      unavailableReason: '未交付：该扣减未计入（5.2.3.1 平台费用规则未交付）',
    });
    expect(rowStatusText(missing)).toContain('未交付');
    expect(rowStatusTone(missing)).toBe('danger');
    // 部分取到要与「已计入」分开。
    expect(rowStatusTone(line({ partial: true }))).toBe('warning');
    expect(rowStatusText(line({ partial: true }))).toBe('部分取到');
  });

  it('真缺的与「已用手工假设补上」的分得开', () => {
    const gaps = [
      {
        code: 'PLATFORM_FEE',
        label: '平台费用',
        ownerModule: '5.2.3.1 平台费用',
        impact: '虚高',
        remedy: '手工填入',
        manualAssumptionAccepted: false,
      },
      {
        code: 'FX',
        label: '汇率',
        ownerModule: '5.2.2.1 汇率查询',
        impact: '口径提醒',
        remedy: '手工填入',
        manualAssumptionAccepted: true,
      },
    ];
    const value = calculation({ blockingGaps: gaps });
    expect(missingGaps(value).map((g) => g.code)).toEqual(['PLATFORM_FEE']);
    expect(assumptionGaps(value).map((g) => g.code)).toEqual(['FX']);
  });
});

describe('统计卡', () => {
  it('成本不完整时毛利与毛利率都写不可信而不是 0', () => {
    const stats = summaryStats(
      calculation({
        decisionReady: false,
        decisionBasis: 'INCOMPLETE',
        grossProfit: null,
        grossMarginRate: null,
        breakEvenPrice: null,
        targetMarginPrice: null,
        breakEvenVolume: null,
      }),
    );
    const profit = stats.find((s) => s.label === '毛利额（每单位）');
    const margin = stats.find((s) => s.label === '毛利率');
    expect(profit?.value).toBe('不可信');
    expect(profit?.tone).toBe('danger');
    expect(margin?.value).toBe('不可信（成本不完整）');
    expect(margin?.tone).toBe('danger');
  });

  it('「剩余可覆盖缺失扣减的金额」不是毛利，标签要写清楚', () => {
    const stats = summaryStats(calculation());
    const left = stats.find((s) => s.label === '剩余可覆盖缺失扣减的金额');
    expect(left?.value).toBe('84 USD');
    expect(left?.hint).toContain('不是毛利');
  });

  it('保本价 / 目标毛利价 / 盈亏平衡销量算不出来时写「算不出来」', () => {
    const stats = summaryStats(
      calculation({ breakEvenPrice: null, targetMarginPrice: null, breakEvenVolume: null }),
    );
    expect(stats.find((s) => s.label === '保本价')?.value).toBe('算不出来');
    expect(stats.find((s) => s.label === '目标毛利价')?.value).toBe('算不出来');
    expect(stats.find((s) => s.label === '盈亏平衡销量')?.value).toBe('算不出来');
  });
});

describe('输入校验', () => {
  it('跨币种没给汇率时拒绝提交', () => {
    const request: CalculateRequest = {
      ...emptyRequest(),
      skuId: 'sku-1',
      currency: 'EUR',
      costCurrency: 'USD',
      mode: 'PRICE',
      inputPrice: 100,
    };
    const error = validateRequest(request);
    expect(error?.field).toBe('fxRate');
    expect(error?.message).toContain('绝不按 1:1 折算');
  });

  it('目标毛利率是小数口径', () => {
    const request: CalculateRequest = {
      ...emptyRequest(),
      skuId: 'sku-1',
      mode: 'TARGET_MARGIN',
      targetMarginRate: 25,
    };
    expect(validateRequest(request)?.field).toBe('targetMarginRate');
  });

  it('按售价测算时售价必填且大于 0', () => {
    const request: CalculateRequest = { ...emptyRequest(), skuId: 'sku-1' };
    expect(validateRequest(request)?.field).toBe('inputPrice');
  });

  it('物流阶梯最后一段必须无上限', () => {
    const request: CalculateRequest = {
      ...emptyRequest(),
      skuId: 'sku-1',
      mode: 'PRICE',
      inputPrice: 100,
      logisticsTiers: [{ upToQuantity: 20, perUnitCost: 5 }],
    };
    expect(validateRequest(request)?.field).toBe('tiers');
  });

  it('合法的输入通过校验', () => {
    const request: CalculateRequest = {
      ...emptyRequest(),
      skuId: 'sku-1',
      mode: 'PRICE',
      inputPrice: 100,
      platformFeeRate: 0.15,
      logisticsCostPerUnit: 5,
    };
    expect(validateRequest(request)).toBeNull();
  });
});

describe('物流阶梯摘要', () => {
  it('阶梯要能一眼看懂分段', () => {
    expect(tierSummary([])).toBe('无阶梯（按每单位运费）');
    expect(
      tierSummary([
        { upToQuantity: 20, perUnitCost: 5 },
        { upToQuantity: null, perUnitCost: 3 },
      ]),
    ).toBe('第 1~20 单 5；超出之后 3');
  });
});
