import { describe, expect, it } from 'vitest';

import {
  basisRuleBasis,
  basisRuleLabel,
  basisNoteText,
  diffChangeLabel,
  diffTone,
  moneyText,
  moneyWithCurrency,
  sourceTypeLabel,
  timeText,
  validateCreate,
  validateRecalculate,
  varianceText,
} from './cost-allocation-model';
import type { PricingCostAllocationLineRow } from './cost-allocation-model';

/**
 * 5.2.1.3 成本分摊前端模型的纯函数断言。
 *
 * <p>这些函数看着不起眼，但它们守的是蓝图加粗句的**技术落点**：
 * 「**分摊规则变更会导致历史利润变化**，因此调整必须留历史版本，不能就地覆盖」——
 * 以及它旁边那句「读不到 ≠ 确实是 0」（渲染成 0.00 会让人以为这一行不用花钱）。
 */
describe('分摊规则', () => {
  it('四种规则都有中文名与口径串', () => {
    expect(basisRuleLabel('WEIGHT')).toBe('按重量');
    expect(basisRuleLabel('VOLUME')).toBe('按体积');
    expect(basisRuleLabel('AMOUNT')).toBe('按金额');
    expect(basisRuleLabel('QUANTITY')).toBe('按数量');
    // 口径串必须说清「怎么算」—— 只给名字的话，用户看到 12.00 仍然不知道为什么昨天是 18.00。
    expect(basisRuleBasis('WEIGHT')).toContain('毛重');
    expect(basisRuleBasis('VOLUME')).toContain('体积');
    expect(basisRuleBasis('AMOUNT')).toContain('货值');
    expect(basisRuleBasis('QUANTITY')).toContain('数量');
  });

  it('未维护的规则显示「未维护」而不是空白', () => {
    expect(basisRuleLabel(null)).toBe('未维护');
    expect(basisRuleLabel('')).toBe('未维护');
    expect(basisRuleLabel('SOMETHING_NEW')).toBe('SOMETHING_NEW');
    expect(basisRuleBasis('SOMETHING_NEW')).toBe('');
  });
});

describe('来源', () => {
  it('两种来源分得开', () => {
    expect(sourceTypeLabel('FIRST_MILE_SHIPMENT')).toBe('头程发运单');
    expect(sourceTypeLabel('MANUAL')).toBe('人工货件');
    expect(sourceTypeLabel(null)).toBe('未知来源');
  });
});

describe('null 不渲染成 0', () => {
  it('取不到的金额显示「取不到」而不是 0.00', () => {
    expect(moneyText(null)).toBe('取不到');
    expect(moneyText(undefined)).toBe('取不到');
    expect(moneyText('')).toBe('取不到');
    expect(moneyText('12.3400')).toBe('12.3400');
    expect(moneyWithCurrency(null, 'USD')).toBe('取不到');
    expect(moneyWithCurrency('12.3400', 'USD')).toBe('USD 12.3400');
  });

  it('基数不可得的行显示「基数不可得」而不是一个安静的 0', () => {
    const line: PricingCostAllocationLineRow = {
      skuId: 's1',
      skuCode: 'A',
      skuName: 'SKU A',
      baseQuantity: '1.000000',
      weightGrams: null,
      volumeCm3: null,
      amountBasis: null,
      basisValue: null,
      basisAvailable: false,
      basisNote: '货件行没有申报毛重，SKU 包装也没有维护毛重',
      allocatedAmount: '0.0000',
      adjustmentAmount: '0.0000',
      adjustmentReason: '',
      finalAmount: '0.0000',
    };
    expect(basisNoteText(line)).toContain('毛重');
    // 🔴 基数不可得不是「摊到 0」：basisValue 是 null，不是 0。
    expect(line.basisValue).toBeNull();
    expect(moneyText(line.basisValue, '—')).toBe('—');
  });
});

describe('差额必须显示', () => {
  it('没有差额时说一句「一致」', () => {
    expect(varianceText('0.0000').tone).toBe('success');
    expect(varianceText(null).tone).toBe('success');
  });

  it('有差额时明写「这是人工微调造成的，不是错误」', () => {
    const result = varianceText('5.0000');
    expect(result.tone).toBe('warning');
    expect(result.text).toContain('5.0000');
    expect(result.text).toContain('人工微调');
  });
});

describe('差异类型', () => {
  it('新增 / 移除 / 变化 / 未变分得开', () => {
    expect(diffChangeLabel('ADDED')).toBe('新增');
    expect(diffChangeLabel('REMOVED')).toBe('移除');
    expect(diffChangeLabel('CHANGED')).toBe('变化');
    expect(diffChangeLabel('SAME')).toBe('未变');
    expect(diffTone('ADDED')).toBe('info');
    expect(diffTone('CHANGED')).toBe('warning');
    expect(diffTone('SAME')).toBe('neutral');
  });
});

describe('时间', () => {
  it('ISO 时刻只到秒，空值有兜底', () => {
    expect(timeText('2026-03-04T05:06:07Z')).toBe('2026-03-04 05:06:07Z');
    expect(timeText(null)).toBe('—');
  });
});

describe('本地校验', () => {
  const valid = {
    sourceType: 'FIRST_MILE_SHIPMENT' as const,
    sourceRef: 'ship-1',
    sourceLabel: '发运单',
    currency: 'USD',
    totalCost: '100.00',
    basisRule: 'WEIGHT' as const,
    effectiveFrom: '2026-03-04T05:06',
    changeReason: '按重量摊',
  };

  it('合法输入没有报错', () => {
    expect(validateCreate(valid)).toEqual([]);
  });

  it('总额、币种、规则、生效时间、原因都要有', () => {
    expect(validateCreate({ ...valid, totalCost: '' })).toContain(
      '货件成本总额必填：没有总额就没有可摊的钱',
    );
    expect(validateCreate({ ...valid, totalCost: '0' })).toContain(
      '货件成本总额不能是 0：摊出一张全 0 的单会让下游以为这个货件不用花钱',
    );
    expect(validateCreate({ ...valid, currency: 'US' })).toContain(
      '计价币种请用 ISO 4217 三位字母码：US',
    );
    expect(validateCreate({ ...valid, changeReason: '  ' })).toContainEqual(
      expect.stringContaining('变更原因必填'),
    );
    expect(validateCreate({ ...valid, effectiveFrom: '' })).toContainEqual(
      expect.stringContaining('生效时间必填'),
    );
  });

  it('人工微调必须带原因且金额不能为 0', () => {
    const problems = validateRecalculate({
      basisRule: 'AMOUNT',
      totalCost: '',
      effectiveFrom: '2026-03-04T05:06',
      changeReason: '换规则',
      adjustments: [{ skuId: 's1', amount: '0', reason: '' }],
    });
    expect(problems).toContain('人工微调金额不能为 0（金额为 0 的微调行没有信息量）');
    expect(problems).toContain('人工微调原因必填：没有理由的微调会让「成本为什么突然变了」永远答不上来');
  });

  it('应用重算时必须填原因与生效时间', () => {
    const problems = validateRecalculate(
      {
        basisRule: 'AMOUNT',
        totalCost: '',
        effectiveFrom: '',
        changeReason: '',
        adjustments: [],
      },
      { requireReason: true, requireEffectiveFrom: true },
    );
    expect(problems).toContain('变更原因必填：换规则 / 改总额 / 人工微调，每一次都要说清为什么');
    expect(problems).toContain('生效时间必填：这一版从哪一刻开始算数');
  });
});
