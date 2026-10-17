import { expect, describe, it } from 'vitest';
import {
  CHECK_CODES,
  FINAL_DISPOSITIONS,
  RECOMMENDED_DISPOSITIONS,
  REASON_CODES,
  checkCodeText,
  evidenceText,
  finalText,
  isPending,
  money,
  observedText,
  rate,
  recommendedText,
  reasonCodeText,
  restrictionEffectText,
  restrictionKindText,
  severityText,
  statusText,
} from './order-intercept-model';
import type { InterceptCheck } from '../../../../../packages/api-contracts/src/index';

/**
 * 9.2.1.3 异常拦截前端模型的展示纪律。
 *
 * 蓝图加粗句：「<b>拦截不自动取消</b>，默认转人工——误拦真实订单的代价通常高于人工看一眼。」
 *
 * <p>本文件钉住的都是**界面层最容易做错、且做错之后没人会发现**的几处：
 * 把「建议取消」显示成「已取消」、把「没测到」显示成 0、
 * 把「上游未交付」显示成「这一项正常」。
 */

function check(over: Partial<InterceptCheck> = {}): InterceptCheck {
  return {
    id: 'c1',
    orderId: 'o1',
    orderNo: 'MO-1',
    orderSource: 'AMAZON',
    buyerKey: null,
    orderCurrency: 'USD',
    orderAmount: 100,
    orderSubmittedAt: '2026-03-01T08:00:00Z',
    shippingCountryCode: 'US',
    policyId: 'p1',
    policyName: '默认拦截策略',
    policyVersion: 1,
    assessedReferenceAt: '2026-03-01T08:00:00Z',
    flaggedCount: 0,
    blockedCount: 0,
    clearedCount: 8,
    missingCount: 0,
    recommendedDisposition: 'RELEASE',
    manualReviewRequired: false,
    finalDisposition: null,
    disposedAt: null,
    disposedBy: null,
    totalsComputedAt: '2026-03-01T09:00:00Z',
    createdBy: null,
    createdAt: '2026-03-01T09:00:00Z',
    ...over,
  } as InterceptCheck;
}

describe('建议处置与最终处置是两句不同的话', () => {
  it('最终处置为空时显示「尚无人工结论」，不是空串也不是「无」', () => {
    expect(finalText(null)).toBe('尚无人工结论');
    expect(finalText('CANCEL')).toBe('人工确认取消');
  });

  it('「建议取消」与「已取消」在文案上可区分', () => {
    expect(recommendedText('CANCEL')).toBe('建议取消（仍需人工）');
    expect(finalText('CANCEL')).toBe('人工确认取消');
    // 这两个字符串只要有一天变成同一个，就是加粗句在 UI 层被违反的那一天
    expect(recommendedText('CANCEL')).not.toBe(finalText('CANCEL'));
  });

  it('建议取消的检查仍被判为「待处置」——它还没被取消', () => {
    const pending = check({
      recommendedDisposition: 'CANCEL',
      manualReviewRequired: true,
      finalDisposition: null,
      blockedCount: 1,
      flaggedCount: 1,
    });
    expect(isPending(pending)).toBe(true);
  });

  it('人工处置之后不再是「待处置」', () => {
    const done = check({
      recommendedDisposition: 'CANCEL',
      manualReviewRequired: true,
      finalDisposition: 'CANCEL',
    });
    expect(isPending(done)).toBe(false);
  });

  it('建议放行的检查不需要人工处置，因此不判为待处置', () => {
    expect(isPending(check())).toBe(false);
  });

  it('最终处置词表里没有任何「自动」取值', () => {
    expect(FINAL_DISPOSITIONS.map((d) => d.value)).toEqual(['RELEASE', 'HOLD', 'CANCEL']);
    expect(FINAL_DISPOSITIONS.map((d) => d.value).join()).not.toContain('AUTO');
    expect(RECOMMENDED_DISPOSITIONS.map((d) => d.value)).toEqual([
      'RELEASE',
      'MANUAL_REVIEW',
      'CANCEL',
    ]);
  });
});

describe('「没测到」与「测到了是 0」必须渲染成两句话', () => {
  it('观测值为 null 时返回 null（调用方显示「未测到」）', () => {
    expect(observedText(null)).toBeNull();
    expect(observedText(undefined)).toBeNull();
  });

  it('观测值为 0 时返回 "0.0000" 而不是 null', () => {
    // 这是最容易写反的一格：把 0 当成「没测到」，界面就会说
    // 「未测到」而实际是认真量过了、结果就是 0
    expect(observedText(0)).toBe('0.0000');
  });

  it('非零值按 4 位格式化（对齐 DB 的 numeric(19,6) / numeric(9,4)）', () => {
    expect(observedText(85)).toBe('85.0000');
    expect(observedText(0.0001)).toBe('0.0001');
  });
});

describe('上游未交付不显示成「这一项正常」', () => {
  it('UPSTREAM_UNAVAILABLE 有独立文案，不复用「已核对」', () => {
    expect(statusText('UPSTREAM_UNAVAILABLE')).toBe('上游未交付');
    expect(statusText('NO_REFERENCE')).toBe('缺参照');
    expect(statusText('CLEARED')).toBe('已核对，未命中');
    // 三种「没结论」的说法必须互不相同：合成一句就等于宣称量过了
    const unavailables = [
      statusText('UPSTREAM_UNAVAILABLE'),
      statusText('NO_REFERENCE'),
      statusText('CLEARED'),
    ];
    expect(new Set(unavailables).size).toBe(3);
  });

  it('BLOCK 严重度的文案里带「仍需人工」', () => {
    expect(severityText('BLOCK')).toContain('仍需人工');
    expect(severityText('BLOCK')).toContain('建议取消');
  });
});

describe('词表与文案映射', () => {
  it('八个命中项与后端词表一字不差', () => {
    expect(CHECK_CODES.map((c) => c.value)).toEqual([
      'ADDRESS_INCOMPLETE',
      'ADDRESS_DELIVERABILITY',
      'BLACKLISTED_BUYER',
      'DUPLICATE_ORDER',
      'BELOW_COST_DISCOUNT',
      'SUSPECTED_FRAUD',
      'COUNTRY_RESTRICTED',
      'PLATFORM_COMPLIANCE',
    ]);
    CHECK_CODES.forEach((c) => expect(checkCodeText(c.value)).toBe(c.label));
  });

  it('未知枚举值原样透出而不是渲染成 undefined', () => {
    expect(checkCodeText('NOT_A_CODE')).toBe('NOT_A_CODE');
    expect(statusText('NOT_A_STATUS')).toBe('NOT_A_STATUS');
    expect(severityText('NOT_A_SEVERITY')).toBe('NOT_A_SEVERITY');
    expect(evidenceText('NOT_A_SOURCE')).toBe('NOT_A_SOURCE');
    expect(recommendedText('NOT_A_ACTION')).toBe('NOT_A_ACTION');
    expect(reasonCodeText('NOT_A_CODE')).toBe('NOT_A_CODE');
    expect(restrictionKindText('NOT_A_KIND')).toBe('NOT_A_KIND');
    expect(restrictionEffectText('NOT_AN_EFFECT')).toBe('NOT_AN_EFFECT');
  });

  it('误拦原因码在词表里（它是加粗句最关心的那一档）', () => {
    expect(REASON_CODES.map((r) => r.value)).toContain('FALSE_POSITIVE');
    expect(reasonCodeText('FALSE_POSITIVE')).toContain('误拦');
  });

  it('证据来源区分「本服务现算」与「上游未交付」', () => {
    expect(evidenceText('DERIVED_FROM_ORDERS')).toBe('本服务订单簇现算');
    expect(evidenceText('UPSTREAM_NOT_DELIVERED')).toBe('上游未交付');
    expect(evidenceText('MODULE_9_2_2_2_RISK_ASSESSMENT')).toContain('9.2.2.2');
  });
});

describe('金额与比率格式化', () => {
  it('金额恒 4 位（对齐 numeric(19,4)），空值给「—」', () => {
    expect(money(42.5)).toBe('42.5000');
    expect(money(null)).toBe('—');
    expect(money(undefined)).toBe('—');
  });

  it('比率按百分数显示（0.0500 → 5.00%），不用裸小数让人读成 0.05%', () => {
    expect(rate(0.05)).toBe('5.00%');
    expect(rate(0)).toBe('0.00%');
  });
});
