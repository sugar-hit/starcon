import { expect, describe, it } from 'vitest';
import {
  DELIVERY_STATUSES,
  EMBARGO_LEVELS,
  ITEM_CODES,
  ITEM_ORDER,
  MISSING_REFERENCE_POLICIES,
  PAYMENT_STATUSES,
  RISK_LEVELS,
  RULE_SEVERITIES,
  deliveryText,
  deliveryTone,
  embargoLevelText,
  embargoListNote,
  evidenceText,
  insuranceGapNote,
  itemTone,
  missingNote,
  missingPolicyText,
  money,
  observedText,
  paymentStatusText,
  riskText,
  riskTone,
  ruleSeverityText,
  severityText,
  sortItems,
  statusText,
} from './order-address-payment-check-model';
import type {
  AddressPaymentCheck,
  AddressPaymentCheckItem,
} from '../../../../../packages/api-contracts/src/index';

/**
 * 9.2.2.1 地址与支付校验前端模型的展示纪律。
 *
 * 蓝图加粗句：「<b>货到付款订单的履约风险最高</b>，需额外风控与保险策略。」
 *
 * <p>本文件钉住的都是**界面层最容易做错、且做错之后没人会发现**的几处：
 * 把货到付款的风险与其它命中混档、把「没测到」显示成 0、
 * 把「保险策略未交付」显示成「这一项正常」、把「禁运清单为空」显示成「没有禁运风险」。
 */

function check(over: Partial<AddressPaymentCheck> = {}): AddressPaymentCheck {
  return {
    id: 'c1',
    orderId: 'o1',
    orderNo: 'SO-1',
    orderSource: 'AMAZON',
    orderCurrency: 'USD',
    orderAmount: 100,
    orderSubmittedAt: '2026-03-01T08:00:00Z',
    paymentMethod: 'PREPAID',
    paymentStatus: 'PAID',
    paymentStatusSource: 'PLATFORM_CALLBACK',
    paymentStatusObservedAt: '2026-03-01T09:00:00Z',
    shippingCountryCode: 'US',
    shippingPostcode: '94105',
    shippingStreet: '1 Market Street',
    policyName: '默认校验策略',
    policyVersion: 1,
    assessedReferenceAt: '2026-03-01T08:00:00Z',
    deliveryStatus: 'DELIVERABLE',
    riskLevel: 'LOW',
    codFlagged: false,
    itemCount: 7,
    flaggedCount: 0,
    clearedCount: 7,
    noReferenceCount: 0,
    upstreamUnavailableCount: 0,
    notApplicableCount: 0,
    blockedCount: 0,
    warnCount: 0,
    criticalCount: 0,
    createdAt: '2026-03-01T09:00:00Z',
    ...over,
  } as AddressPaymentCheck;
}

function item(over: Partial<AddressPaymentCheckItem> = {}): AddressPaymentCheckItem {
  return {
    itemCode: 'POSTCODE_FORMAT',
    itemLabel: '邮编格式',
    status: 'CLEARED',
    severity: 'INFO',
    evidenceSource: 'TENANT_COUNTRY_RULE',
    observedValue: 0,
    thresholdValue: 0,
    matchCount: 0,
    evidenceReference: '',
    explanation: '邮编符合规则',
    ...over,
  } as AddressPaymentCheckItem;
}

describe('输出是两件：校验结果与风险等级', () => {
  it('两个字段各有各的文案，不共用一个标签', () => {
    expect(deliveryText('DELIVERABLE')).toBe('可投递');
    expect(deliveryText('NOT_DELIVERABLE')).toBe('不可投递');
    expect(deliveryText('UNDETERMINED')).toBe('未定（缺参照）');
    expect(riskText('LOW')).toBe('低风险');
    expect(riskText('CRITICAL')).toBe('最高风险（货到付款专属）');
  });

  it('「未定」不是「可投递」：色调必须分开', () => {
    expect(deliveryTone('DELIVERABLE')).toBe('ok');
    expect(deliveryTone('NOT_DELIVERABLE')).toBe('block');
    expect(deliveryTone('UNDETERMINED')).toBe('unknown');
  });

  it('货到付款单的地址照样可投递 —— 两件事不合成一个字段', () => {
    const cod = check({ codFlagged: true, riskLevel: 'CRITICAL', deliveryStatus: 'DELIVERABLE' });
    expect(deliveryText(cod.deliveryStatus)).toBe('可投递');
    expect(riskText(cod.riskLevel)).toContain('最高风险');
  });
});

describe('最高风险档是货到付款专属', () => {
  it('CRITICAL 与 HIGH 的色调分开', () => {
    expect(riskTone('CRITICAL')).toBe('critical');
    expect(riskTone('HIGH')).toBe('block');
    expect(riskTone('MEDIUM')).toBe('warn');
    expect(riskTone('LOW')).toBe('ok');
  });

  it('CRITICAL 这一档的文案永远带「货到付款」', () => {
    expect(riskText('CRITICAL')).toContain('货到付款');
    // HIGH 不得冒充最高档。
    expect(riskText('HIGH')).not.toContain('最高');
  });

  it('词表里只有一个值是「最高风险」', () => {
    expect(RISK_LEVELS.filter((r) => r.label.includes('最高风险'))).toHaveLength(1);
  });
});

describe('保险策略未交付不许显示成「这一项正常」', () => {
  it('保险策略项的色调是「未知」而不是「通过」', () => {
    const insurance = item({
      itemCode: 'COD_INSURANCE_POLICY',
      status: 'UPSTREAM_UNAVAILABLE',
      severity: 'INFO',
      observedValue: null,
      evidenceSource: 'MODULE_10_INSURANCE_NOT_DELIVERED',
    });
    expect(itemTone(insurance)).toBe('unknown');
    expect(itemTone(insurance)).not.toBe('ok');
  });

  it('货到付款单会显式提示保险缺口', () => {
    const cod = check({
      codFlagged: true,
      riskLevel: 'CRITICAL',
      upstreamUnavailableCount: 1,
    });
    expect(insuranceGapNote(cod)).toContain('未交付');
    // 非货到付款单不该有这条提示。
    expect(insuranceGapNote(check())).toBeNull();
  });

  it('证据来源里保险策略明写「未交付」', () => {
    expect(evidenceText('MODULE_10_INSURANCE_NOT_DELIVERED')).toContain('未交付');
  });
});

describe('「没量到」不是 0', () => {
  it('观测值为空时显示「未测到」，不显示 0', () => {
    expect(observedText(null)).toBeNull();
    expect(observedText(undefined)).toBeNull();
    expect(observedText(0)).toBe('0');
  });

  it('缺参照项的色调不是「通过」', () => {
    expect(itemTone(item({ status: 'NO_REFERENCE', observedValue: null }))).toBe('muted');
    expect(itemTone(item({ status: 'NOT_APPLICABLE', observedValue: null }))).toBe('muted');
  });

  it('有缺参照项时给出提示', () => {
    expect(missingNote(check({ noReferenceCount: 3 }))).toContain('3');
    expect(missingNote(check())).toBeNull();
  });
});

describe('禁运清单为空不等于「没有禁运风险」', () => {
  it('空清单的提示语必须说清是租户自己的选择', () => {
    expect(embargoListNote(0)).toContain('没有禁运任何国家');
    expect(embargoListNote(0)).toContain('不是');
    expect(embargoListNote(3)).toContain('3');
  });
});

describe('命中项的严重度与状态', () => {
  it('状态文案区分五种', () => {
    expect(statusText('FLAGGED')).toBe('命中');
    expect(statusText('CLEARED')).toBe('已核对，未命中');
    expect(statusText('NO_REFERENCE')).toBe('缺参照');
    expect(statusText('UPSTREAM_UNAVAILABLE')).toBe('上游未交付');
    expect(statusText('NOT_APPLICABLE')).toBe('不适用');
  });

  it('CRITICAL 严重度的文案带「货到付款专属」', () => {
    expect(severityText('CRITICAL')).toContain('货到付款');
  });

  it('命中项按判读顺序排列：先地址，后支付，最后两行货到付款专项', () => {
    const shuffled = [
      item({ itemCode: 'COD_INSURANCE_POLICY' }),
      item({ itemCode: 'POSTCODE_FORMAT' }),
      item({ itemCode: 'COD_FULFILLMENT_RISK' }),
      item({ itemCode: 'COUNTRY_EMBARGO' }),
    ];
    expect(sortItems(shuffled).map((i) => i.itemCode)).toEqual([
      'COUNTRY_EMBARGO',
      'POSTCODE_FORMAT',
      'COD_FULFILLMENT_RISK',
      'COD_INSURANCE_POLICY',
    ]);
  });
});

describe('码表与词表', () => {
  it('七个检查项与判读顺序一一对应，不多不少', () => {
    expect(ITEM_CODES).toHaveLength(7);
    expect(ITEM_ORDER).toHaveLength(7);
    expect(new Set(ITEM_ORDER).size).toBe(7);
    for (const code of ITEM_ORDER) {
      expect(ITEM_CODES.map((c) => c.value)).toContain(code);
    }
  });

  it('支付状态、禁运强度、规则严重度各有词表', () => {
    expect(PAYMENT_STATUSES.map((s) => s.value)).toEqual([
      'PAID',
      'UNPAID',
      'PARTIAL',
      'COD',
    ]);
    expect(EMBARGO_LEVELS.map((s) => s.value)).toEqual(['BLOCK', 'REVIEW']);
    expect(RULE_SEVERITIES.map((s) => s.value)).toEqual(['OK', 'WARN', 'BLOCK']);
    expect(DELIVERY_STATUSES.map((s) => s.value)).toEqual([
      'DELIVERABLE',
      'NOT_DELIVERABLE',
      'UNDETERMINED',
    ]);
  });

  it('未提供支付状态时显示「未提供」而不是「未付」', () => {
    // 「没量到」与「量过了、结论是未付」是两件事。
    expect(paymentStatusText(null)).toBe('未提供');
    expect(paymentStatusText('UNPAID')).toBe('未付');
  });

  it('缺参照口径的两种说法都能查到', () => {
    expect(missingPolicyText('ASSESS_KNOWN_ONLY')).toBe('按已知项判定');
    expect(missingPolicyText('BLOCK_DECISION')).toBe('缺参照即不出结论');
    expect(MISSING_REFERENCE_POLICIES).toHaveLength(2);
  });

  it('禁运强度与规则严重度有中文说法', () => {
    expect(embargoLevelText('BLOCK')).toBe('不可发');
    expect(ruleSeverityText('OK')).toBe('自洽');
    expect(ruleSeverityText('BLOCK')).toBe('矛盾，不可放行');
  });

  it('金额按 4 位小数显示，空值显示破折号', () => {
    expect(money(19.99)).toBe('19.9900');
    expect(money(null)).toBe('—');
  });
});
