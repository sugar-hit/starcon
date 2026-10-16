import { describe, expect, it } from 'vitest';
import type { FreightEstimateInput, FreightEstimateLine, FreightEstimateResult } from './freight-calculator-model';
import {
  bandRangeText,
  basisLabel,
  chargeModelLabel,
  chargeableText,
  eligibilityLabel,
  eligibilityTone,
  emptyResultText,
  moneyText,
  quoteOutcomeText,
  rateCardSummary,
  rateSourceLabel,
  splitCodes,
  toEstimateRequest,
  totalText,
  transitText,
  validateEstimate,
} from './freight-calculator-model';
import type { FreightQuoteRow, RateCardRow } from './freight-calculator-model';

/**
 * 10.1.2.1 运费计算器 · 前端模型。
 *
 * 钉的是**页面上会误导人的那几处**，不是渲染细节：
 * 算不出钱的行不能显示成 0.00、混币种不能混排、尺寸不能只填一半、
 * 档位区间的闭开要写得让人看懂。
 */

function input(over: Partial<FreightEstimateInput> = {}): FreightEstimateInput {
  return {
    destCountry: 'DE',
    shopCode: '',
    weightKg: '1.5',
    lengthCm: '',
    widthCm: '',
    heightCm: '',
    pieces: '1',
    declaredValue: '',
    declaredCurrency: '',
    categoryCodes: '',
    containsBattery: false,
    containsLiquid: false,
    containsMagnet: false,
    note: '',
    ...over,
  };
}

function line(over: Partial<FreightEstimateLine> = {}): FreightEstimateLine {
  return {
    channelId: 'c1',
    channelCode: 'SF-DE-STD',
    channelName: '顺丰德国标准',
    carrierName: '顺丰速运',
    eligibility: 'PASS',
    eligibilityReasons: [],
    rateSource: 'RATE_CARD',
    basis: 'WEIGHT',
    chargeableQty: 1.5,
    volumetricQty: null,
    baseCharge: 16,
    fuelFee: 2.96,
    remoteFee: 0,
    peakFee: 0,
    insuranceFee: 0,
    totalCharge: 18.96,
    currency: 'USD',
    transitMinDays: 5,
    transitMaxDays: 9,
    quoteable: true,
    trace: [],
    notes: [],
    ...over,
  };
}

describe('10.1.2.1 运费试算：表单校验', () => {
  it('正常输入没有阻断项', () => {
    expect(validateEstimate(input()).ok).toBe(true);
  });

  it('收货国家必须是两位国家代码', () => {
    const result = validateEstimate(input({ destCountry: 'DEU' }));
    expect(result.ok).toBe(false);
    expect(result.errors.join()).toContain('两位国家代码');
  });

  it('🔴 尺寸只填一半被拒（半套尺寸会算出看起来正常的体积重）', () => {
    const result = validateEstimate(input({ lengthCm: '30', widthCm: '20' }));
    expect(result.ok).toBe(false);
    expect(result.errors.join()).toContain('要么三个都填');
  });

  it('三个尺寸齐了才能通过，且必须大于 0', () => {
    expect(validateEstimate(input({ lengthCm: '30', widthCm: '20', heightCm: '20' })).ok).toBe(
      true,
    );
    const zero = validateEstimate(input({ lengthCm: '0', widthCm: '20', heightCm: '20' }));
    expect(zero.ok).toBe(false);
  });

  it('重量不能为空或负数', () => {
    expect(validateEstimate(input({ weightKg: '' })).ok).toBe(false);
    expect(validateEstimate(input({ weightKg: '-1' })).ok).toBe(false);
    expect(validateEstimate(input({ weightKg: '0' })).ok).toBe(true);
  });

  it('件数至少 1，申报币种三位代码', () => {
    expect(validateEstimate(input({ pieces: '0' })).ok).toBe(false);
    expect(validateEstimate(input({ declaredCurrency: 'US' })).ok).toBe(false);
    expect(validateEstimate(input({ declaredCurrency: 'usd' })).ok).toBe(true);
  });

  it('🔴 品类编码只接受字母数字下划线（它会被拼进 SQL）', () => {
    const result = validateEstimate(input({ categoryCodes: "HOME_GOODS,3C'); drop--" }));
    expect(result.ok).toBe(false);
    expect(result.errors.join()).toContain('品类编码');
  });

  it('校验逐条给出，不合并成一句话', () => {
    const result = validateEstimate(input({ destCountry: '', weightKg: 'x' }));
    expect(result.errors.length).toBe(2);
  });
});

describe('10.1.2.1 运费试算：请求体组装', () => {
  it('尺寸留空时发 null（不是 0 —— 0 会被当成「体积为零的包裹」）', () => {
    const request = toEstimateRequest(input());
    expect(request.lengthCm).toBeNull();
    expect(request.widthCm).toBeNull();
    expect(request.heightCm).toBeNull();
  });

  it('件数留空按 1 处理', () => {
    expect(toEstimateRequest(input({ pieces: '' })).pieces).toBe(1);
  });

  it('国家与币种统一大写，品类编码按多种分隔符拆开', () => {
    const request = toEstimateRequest(
      input({ destCountry: 'de', declaredCurrency: 'usd', categoryCodes: 'home_goods, 3c；BATTERY' }),
    );
    expect(request.destCountry).toBe('DE');
    expect(request.declaredCurrency).toBe('USD');
    expect(request.categoryCodes).toEqual(['HOME_GOODS', '3C', 'BATTERY']);
  });

  it('带电 / 带液 / 带磁标记原样透传（限制判定在服务端，页面不自己比对）', () => {
    const request = toEstimateRequest(
      input({ containsBattery: true, containsLiquid: false, containsMagnet: true }),
    );
    expect(request.containsBattery).toBe(true);
    expect(request.containsLiquid).toBe(false);
    expect(request.containsMagnet).toBe(true);
  });

  it('拆分编码容忍空格与中文分号', () => {
    expect(splitCodes(' A ，B; C ')).toEqual(['A', 'B', 'C']);
  });
});

describe('10.1.2.1 运费试算：金额与口径展示', () => {
  it('🔴 算不出钱的行显示「规则不全」，绝不显示 0.00', () => {
    const unpriceable = line({ quoteable: false, totalCharge: null, rateSource: 'NO_RULE' });
    expect(totalText(unpriceable)).toBe('规则不全，无法试算');
    expect(totalText(unpriceable)).not.toContain('0.00');
  });

  it('算得出钱的行显示两位小数与币种', () => {
    expect(totalText(line())).toBe('18.96 USD');
  });

  it('金额为 null 时显示破折号而不是 0', () => {
    expect(moneyText(null)).toBe('—');
    expect(moneyText(0)).toBe('0.00');
  });

  it('体积重要一并显示（要能看出这一票是按体积重算的）', () => {
    expect(chargeableText(line({ chargeableQty: 2, volumetricQty: 2 }))).toBe(
      '2 kg（体积重 2kg）',
    );
    expect(chargeableText(line({ basis: 'PIECE', chargeableQty: 3, volumetricQty: null }))).toBe(
      '3 件',
    );
    expect(chargeableText(line({ chargeableQty: null }))).toBe('—');
  });

  it('时效区间未登记时说「未登记」，不编天数', () => {
    expect(transitText(null, null)).toBe('未登记');
    expect(transitText(5, 5)).toBe('5 天');
    expect(transitText(5, 9)).toBe('5–9 天');
  });

  it('可承运性三档各有说法与色调', () => {
    expect(eligibilityLabel('PASS')).toBe('可承运');
    expect(eligibilityLabel('REVIEW')).toBe('需人工确认');
    expect(eligibilityLabel('BLOCK')).toBe('硬拦截');
    expect(eligibilityTone('PASS')).toBe('success');
    expect(eligibilityTone('REVIEW')).toBe('warning');
    expect(eligibilityTone('BLOCK')).toBe('danger');
  });

  it('计费口径来源要让人看出用的是哪一套规则', () => {
    expect(rateSourceLabel('RATE_CARD')).toBe('按费率卡');
    expect(rateSourceLabel('CHANNEL_FLAT')).toBe('渠道单值回退');
    expect(rateSourceLabel('NO_RULE')).toBe('规则不全');
    expect(basisLabel('VOLUME')).toBe('按体积折重');
    expect(chargeModelLabel('FIRST_PLUS_ADDITIONAL')).toBe('首重 + 续重');
  });
});

describe('10.1.2.1 运费试算：费率卡摘要', () => {
  function card(over: Partial<RateCardRow> = {}): RateCardRow {
    return {
      id: 'card-1',
      channelId: 'c1',
      channelCode: 'SF-DE-STD',
      channelName: '顺丰德国标准',
      carrierName: '顺丰速运',
      channelBillingMode: 'BY_WEIGHT',
      currency: 'USD',
      status: 'ACTIVE',
      chargeModel: 'FLAT_RATE',
      basis: 'WEIGHT',
      roundingMode: 'HALF_UP',
      roundingScale: 2,
      volumetricDivisor: null,
      firstQty: null,
      firstAmount: null,
      additionalQty: null,
      additionalAmount: null,
      bands: [
        { bandNo: 1, fromQty: 0, toQty: 1, toQtyInclusive: false, rateAmount: 10, note: '' },
        { bandNo: 2, fromQty: 1, toQty: null, toQtyInclusive: false, rateAmount: 12, note: '' },
      ],
      note: '',
      version: 1,
      createdAt: '2026-10-15T09:00:00Z',
      updatedAt: '2026-10-15T09:00:00Z',
      ...over,
    };
  }

  it('摘要含计费模型、量纲、舍入口径与档位数', () => {
    expect(rateCardSummary(card())).toContain('计分档位');
    expect(rateCardSummary(card())).toContain('舍入 HALF_UP@2');
    expect(rateCardSummary(card())).toContain('2 段');
  });

  it('首重续重卡摘要给出首重与续重单位', () => {
    const summary = rateCardSummary(
      card({
        chargeModel: 'FIRST_PLUS_ADDITIONAL',
        firstQty: 1,
        firstAmount: 12,
        additionalQty: 0.5,
        additionalAmount: 3,
      }),
    );
    expect(summary).toContain('首重 1kg / 12');
    expect(summary).toContain('续重 0.5kg 每段 3');
  });

  it('档位区间的闭开写法与 DB 语义一致（下界恒闭）', () => {
    expect(
      bandRangeText({ bandNo: 1, fromQty: 0, toQty: 1, toQtyInclusive: false, rateAmount: 10, note: '' }),
    ).toBe('[0, 1)');
    expect(
      bandRangeText({ bandNo: 1, fromQty: 0, toQty: 1, toQtyInclusive: true, rateAmount: 10, note: '' }),
    ).toBe('[0, 1]');
    expect(
      bandRangeText({ bandNo: 3, fromQty: 5, toQty: null, toQtyInclusive: false, rateAmount: 20, note: '' }),
    ).toBe('[5, ∞)');
  });
});

describe('10.1.2.1 运费试算：空态与结果摘要', () => {
  function result(over: Partial<FreightEstimateResult> = {}): FreightEstimateResult {
    return {
      quotedAt: '2026-10-15T09:00:00+08:00',
      destCountry: 'DE',
      shopCode: '',
      weightKg: 1.5,
      volumeCm3: null,
      pieces: 1,
      channelCount: 2,
      quoteableCount: 2,
      passCount: 2,
      reviewCount: 0,
      blockCount: 0,
      currency: 'USD',
      mixedCurrency: false,
      cheapestTotal: 18.96,
      dearestTotal: 25,
      costBasis: 'RULE_ESTIMATE',
      savedQuoteId: null,
      lines: [],
      note: '',
      ...over,
    };
  }

  it('🔴 「没有候选渠道」与「有渠道但算不出钱」是两句不同的话', () => {
    const none = emptyResultText(result({ channelCount: 0, quoteableCount: 0 }));
    expect(none).toContain('没有候选渠道');
    const unpriceable = emptyResultText(result({ channelCount: 2, quoteableCount: 0 }));
    expect(unpriceable).toContain('算不出运费');
    expect(unpriceable).not.toContain('没有候选渠道');
  });

  it('有结果时不显示空态文案', () => {
    expect(emptyResultText(result())).toBe('');
    expect(emptyResultText(null)).toBe('');
  });

  it('试算口径标记恒为 RULE_ESTIMATE（试算用规则、结算用实际）', () => {
    expect(result().costBasis).toBe('RULE_ESTIMATE');
  });

  it('试算单摘要区分「无候选 / 规则不全 / 硬拦截 / 可下单」', () => {
    const base: FreightQuoteRow = {
      id: 'q1',
      costBasis: 'RULE_ESTIMATE',
      destCountry: 'DE',
      shopCode: '',
      weightKg: 1,
      lengthCm: null,
      widthCm: null,
      heightCm: null,
      pieces: 1,
      declaredValue: null,
      declaredCurrency: null,
      containsBattery: false,
      containsLiquid: false,
      containsMagnet: false,
      categoryCodes: [],
      quotedAt: '2026-10-15T09:00:00+08:00',
      currency: 'USD',
      mixedCurrency: false,
      channelCount: 2,
      passCount: 2,
      reviewCount: 0,
      blockCount: 0,
      cheapestTotal: 18.96,
      dearestTotal: 25,
      note: '',
      createdAt: '2026-10-15T09:00:00+08:00',
    };
    expect(quoteOutcomeText(base)).toBe('可直接下单');
    expect(quoteOutcomeText({ ...base, blockCount: 1 })).toBe('1 条硬拦截');
    expect(quoteOutcomeText({ ...base, reviewCount: 2 })).toBe('2 条需确认');
    expect(quoteOutcomeText({ ...base, cheapestTotal: null })).toBe('规则不全');
    expect(quoteOutcomeText({ ...base, channelCount: 0 })).toBe('无候选渠道');
  });
});
