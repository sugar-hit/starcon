import { describe, expect, it } from 'vitest';

import {
  ADJUSTABLE_COMPONENTS,
  COST_SECTIONS,
  EMPTY_COST_FILTERS,
  amountListText,
  costBasisText,
  costMethodBasis,
  costMethodLabel,
  moneyText,
  moneyWithCurrency,
  sourceLabel,
  sourceTone,
  timeText,
  totalCompleteness,
  validateAdjustment,
  validateProfile,
  validateVoidReason,
} from './cost-composition-model';
import type { CostDetail } from './cost-composition-model';

/**
 * 5.2.1.1 成本构成前端模型的纯函数断言。
 *
 * <p>这些函数看着不起眼，但它们守的是蓝图加粗句的**技术落点**：
 * 「成本口径必须在页面上明示用了哪种成本法」—— 以及它旁边那句
 * 「读不到 ≠ 确实是 0」（渲染成 0.00 会让单位成本偏低、毛利偏高，页面上却看不出异常）。
 */

describe('成本法', () => {
  it('三种成本法都有中文名与口径串', () => {
    expect(costMethodLabel('MOVING_AVERAGE')).toBe('移动加权');
    expect(costMethodLabel('FIFO')).toBe('先进先出');
    expect(costMethodLabel('LOT')).toBe('批次');
    // 口径串必须说清「怎么算」—— 只给名字的话，用户看到 12.00 仍然不知道为什么昨天是 12.50。
    expect(costMethodBasis('MOVING_AVERAGE')).toContain('加权平均');
    expect(costMethodBasis('FIFO')).toContain('到货日');
    expect(costMethodBasis('LOT')).toContain('批次');
  });

  it('未维护的成本法显示「未维护」而不是空白', () => {
    expect(costMethodLabel(null)).toBe('未维护');
    expect(costMethodLabel('')).toBe('未维护');
    expect(costMethodLabel('SOMETHING_NEW')).toBe('SOMETHING_NEW');
  });
});

describe('分项来源', () => {
  it('实际到货金额 / 报价 / 上游事实 / 人工参数必须分得开', () => {
    expect(sourceLabel('CATALOG_VENDOR_DELIVERY')).toBe('实际到货金额');
    expect(sourceLabel('CATALOG_QUOTE')).toContain('报价');
    expect(sourceLabel('WAREHOUSE_FIRST_MILE')).toContain('头程');
    expect(sourceLabel('WAREHOUSE_DUTY_TAX')).toContain('关税');
    expect(sourceLabel('CATALOG_PARAMETER')).toBe('人工参数');
    expect(sourceLabel('CATALOG_ADJUSTMENT')).toContain('手工调整');
  });

  it('来源类型决定色标', () => {
    expect(sourceTone('DERIVED')).toBe('success');
    expect(sourceTone('PARAMETER')).toBe('warning');
    expect(sourceTone('MANUAL')).toBe('info');
    expect(sourceTone('REFERENCE')).toBe('neutral');
  });
});

describe('null 不渲染成 0', () => {
  it('取不到的金额显示「取不到」而不是 0.00', () => {
    expect(moneyText(null)).toBe('取不到');
    expect(moneyText(undefined)).toBe('取不到');
    expect(moneyText('')).toBe('取不到');
    expect(moneyText('12.5000')).toBe('12.5000');
  });

  it('带币种的串在取不到时不带币种（避免读成「USD 取不到」）', () => {
    expect(moneyWithCurrency(null, 'USD')).toBe('取不到');
    expect(moneyWithCurrency('10.0000', 'USD')).toBe('USD 10.0000');
    expect(moneyWithCurrency('10.0000', null)).toBe('10.0000');
  });

  it('未维护的参数显示「未维护」', () => {
    expect(moneyText(null, '未维护')).toBe('未维护');
  });
});

describe('时间', () => {
  it('ISO 时刻可读，空值显示未维护', () => {
    expect(timeText('2026-03-01T08:30:00Z')).toContain('2026-03-01 08:30');
    expect(timeText(null)).toBe('未维护');
  });
});

describe('合计完整度', () => {
  const base: CostDetail = {
    skuId: 's',
    skuCode: 'S',
    skuName: 'S',
    spuCode: 'S',
    baseUnit: 'PCS',
    maintained: true,
    costMethod: 'MOVING_AVERAGE',
    costMethodLabel: '移动加权',
    costMethodBasis: '加权平均',
    currency: 'USD',
    effectiveFrom: '2026-03-01T08:30:00Z',
    components: [],
    totals: [],
    totalAmount: '10.0000',
    totalComplete: true,
    missingComponents: [],
    partialComponents: [],
    excludedDeliveryCount: 0,
    foreignAmounts: [],
    adjustments: [],
    recentRevisions: [],
    lots: [],
    quoteReference: {
      available: false,
      currency: null,
      unitPrice: null,
      minQuantity: null,
      purchaseUnitCode: null,
      supplierRoleId: null,
      status: null,
      validFrom: null,
      validTo: null,
      reason: null,
      note: '',
    },
    windowFrom: '2026-01-01',
    windowTo: '2026-03-01',
    basis: '',
    note: '',
  };

  it('完整时说明合计是完整成本', () => {
    const result = totalCompleteness(base);
    expect(result.tone).toBe('success');
    expect(result.text).toContain('完整');
  });

  it('缺分项时明写「已知部分合计」并点名缺哪几项', () => {
    const result = totalCompleteness({
      ...base,
      totalComplete: false,
      missingComponents: ['FIRST_MILE', 'DUTY'],
    });
    expect(result.tone).toBe('danger');
    expect(result.text).toContain('已知部分合计');
    expect(result.text).toContain('FIRST_MILE');
    expect(result.text).toContain('DUTY');
  });

  it('部分覆盖时是警示而不是危险（值有，只是没覆盖全部事实）', () => {
    const result = totalCompleteness({
      ...base,
      totalComplete: false,
      partialComponents: ['PURCHASE'],
    });
    expect(result.tone).toBe('warning');
    expect(result.text).toContain('PURCHASE');
  });
});

describe('成本口径一句话', () => {
  it('把成本法、币种、单位、生效时间摆在同一句里', () => {
    const text = costBasisText({
      skuId: 's',
      skuCode: 'S',
      skuName: 'S',
      spuCode: 'S',
      baseUnit: 'PCS',
      maintained: true,
      costMethod: 'FIFO',
      costMethodLabel: '先进先出',
      costMethodBasis: '',
      currency: 'USD',
      effectiveFrom: '2026-03-01T08:30:00Z',
      components: [],
      totals: [],
      totalAmount: null,
      totalComplete: false,
      missingComponents: [],
      partialComponents: [],
      excludedDeliveryCount: 0,
      foreignAmounts: [],
      adjustments: [],
      recentRevisions: [],
      lots: [],
      quoteReference: {
        available: false,
        currency: null,
        unitPrice: null,
        minQuantity: null,
        purchaseUnitCode: null,
        supplierRoleId: null,
        status: null,
        validFrom: null,
        validTo: null,
        reason: null,
        note: '',
      },
      windowFrom: '',
      windowTo: '',
      basis: '',
      note: '',
    });
    expect(text).toContain('先进先出');
    expect(text).toContain('USD');
    expect(text).toContain('PCS');
    expect(text).toContain('2026-03-01');
  });
});

describe('逐币种金额', () => {
  it('跨币种不相加，逐个列出', () => {
    expect(
      amountListText([
        { currency: 'USD', amount: '10.0000' },
        { currency: 'EUR', amount: '9.0000' },
      ]),
    ).toBe('USD 10.0000 / EUR 9.0000');
    expect(amountListText([])).toBe('—');
  });
});

describe('本地校验', () => {
  it('变更原因必填', () => {
    const problems = validateProfile({
      costMethod: 'MOVING_AVERAGE',
      currency: 'USD',
      effectiveFrom: '2026-03-01T08:30',
      packagingCost: null,
      changeReason: '   ',
      version: 0,
    });
    expect(problems.join('|')).toContain('变更原因');
  });

  it('成本法与币种与生效时间必填', () => {
    const problems = validateProfile({
      costMethod: '',
      currency: '',
      effectiveFrom: '',
      packagingCost: null,
      changeReason: '换口径',
      version: 0,
    });
    expect(problems.length).toBeGreaterThanOrEqual(3);
  });

  it('包装成本可以留空（= 尚未维护）但不能是负数', () => {
    expect(
      validateProfile({
        costMethod: 'LOT',
        currency: 'USD',
        effectiveFrom: '2026-03-01T08:30',
        packagingCost: null,
        changeReason: '换口径',
        version: 0,
      }),
    ).toEqual([]);
    expect(
      validateProfile({
        costMethod: 'LOT',
        currency: 'USD',
        effectiveFrom: '2026-03-01T08:30',
        packagingCost: '-1',
        changeReason: '换口径',
        version: 0,
      }).join('|'),
    ).toContain('不能为负');
  });

  it('手工调整的原因必填，派生分项不可调', () => {
    const problems = validateAdjustment({
      component: 'PURCHASE',
      amount: '1.00',
      currency: 'USD',
      reason: '',
      effectiveFrom: '2026-03-01T08:30',
    });
    const text = problems.join('|');
    expect(text).toContain('原因');
    expect(text).toContain('派生值');
  });

  it('调整金额不允许为 0', () => {
    const problems = validateAdjustment({
      component: 'OTHER_DIRECT',
      amount: '0',
      currency: 'USD',
      reason: '供应商加价',
      effectiveFrom: '2026-03-01T08:30',
    });
    expect(problems.join('|')).toContain('不能为 0');
  });

  it('负数调整是允许的（冲回）', () => {
    expect(
      validateAdjustment({
        component: 'OTHER_DIRECT',
        amount: '-3.50',
        currency: 'USD',
        reason: '冲回上一笔录错的调整',
        effectiveFrom: '2026-03-01T08:30',
      }),
    ).toEqual([]);
  });

  it('可手工调整的分项只有两个', () => {
    expect(ADJUSTABLE_COMPONENTS.map((c) => c.value)).toEqual(['OTHER_DIRECT', 'PACKAGING']);
  });

  it('作废理由必填', () => {
    expect(validateVoidReason('  ').length).toBe(1);
    expect(validateVoidReason('录错了')).toEqual([]);
  });
});

describe('分区块约定（给兄弟模块的扩展位）', () => {
  it('三个兄弟分区各有唯一 id', () => {
    const ids = COST_SECTIONS.map((s) => s.id);
    expect(new Set(ids).size).toBe(ids.length);
  });

  it('本模块 5.2.1.1 标为已交付', () => {
    const composition = COST_SECTIONS.find((s) => s.moduleId === '5.2.1.1');
    expect(composition?.delivered).toBe(true);
    expect(composition?.title).toBe('成本构成');
  });

  it('5.2.1.2 与 5.2.1.3 显式标为未交付并写明为什么', () => {
    for (const moduleId of ['5.2.1.2', '5.2.1.3']) {
      const section = COST_SECTIONS.find((s) => s.moduleId === moduleId);
      expect(section, `${moduleId} 必须在分区表里`).toBeTruthy();
      expect(section?.delivered).toBe(false);
      expect(section?.summary).toContain('未交付');
    }
  });
});

describe('默认筛选', () => {
  it('空窗口 = 用后端默认的最近 90 天（不是本页自己算窗口）', () => {
    expect(EMPTY_COST_FILTERS.windowFrom).toBe('');
    expect(EMPTY_COST_FILTERS.windowTo).toBe('');
    expect(EMPTY_COST_FILTERS.maintained).toBe('');
  });
});
