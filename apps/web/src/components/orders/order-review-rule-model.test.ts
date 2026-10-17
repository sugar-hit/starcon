import { describe, expect, it } from 'vitest';
import type { HitCondition, ReviewDecision, RuleHit } from '../../../../../packages/api-contracts/src/index';
import {
  ADDRESS_ANOMALY_LABELS,
  anomalyText,
  blacklistText,
  buyerLevelText,
  canActivate,
  canDisable,
  conditionSummary,
  conditionToInput,
  decisionCounts,
  decisionTone,
  discountRateText,
  effectiveText,
  errorText,
  hitConditionLines,
  hitSummary,
  modeText,
  operatorsFor,
  toConditionInput,
} from './order-review-rule-model';

/**
 * 9.2.1.2 审单规则前端模型的展示纪律。
 *
 * <p>这些断言盯的是**最容易在界面上说错话**的几处：把「未知」显示成默认值、
 * 把「不可计算」显示成 0、把未命中的规则藏起来。每一处都会让客服在买家面前
 * 说出一句与系统实际判定不一致的话。
 */

const decision = (over: Partial<ReviewDecision> = {}): ReviewDecision =>
  ({
    id: 'e1',
    orderId: 'o1',
    orderNo: 'SO-1',
    orderSource: 'AMAZON',
    currencyCode: 'USD',
    orderTotal: 200,
    discountRatePercent: 42.5,
    mode: 'RECORDED',
    modeLabel: '生效判定',
    evaluatedScope: 'EFFECTIVE',
    decision: 'MANUAL_REVIEW',
    decisionLabel: '转人工',
    decisionReason: '命中强制转人工规则 R-BIG（金额大额）：命中：全部 1 个条件满足',
    winningRuleId: 'r1',
    winningRuleCode: 'R-BIG',
    winningRuleName: '金额大额强制转人工',
    consideredRuleCount: 4,
    matchedRuleCount: 2,
    forcedRuleCount: 1,
    addressAnomalyCodes: [],
    evaluatedAt: '2026-03-05T16:30:45+08:00',
    evaluatedByMembershipId: null,
    createdAt: '2026-03-05T16:30:45+08:00',
    ...over,
  }) as ReviewDecision;

const hit = (over: Partial<RuleHit> = {}): RuleHit =>
  ({
    id: 'h1',
    ruleId: 'r1',
    ruleCode: 'R-BIG',
    ruleName: '金额大额强制转人工',
    priority: 500,
    action: 'MANUAL_REVIEW',
    actionLabel: '转人工',
    forcedManual: true,
    evalRank: 1,
    matched: true,
    reason: '命中：全部 1 个条件满足',
    conditions: [],
    ...over,
  }) as RuleHit;

const condition = (over: Partial<HitCondition> = {}): HitCondition =>
  ({
    seqNo: 1,
    dimension: 'AMOUNT',
    dimensionLabel: '订单金额',
    operator: 'GTE',
    expected: '100.0000',
    actualField: 'sales_order.grand_total_amount',
    actualValue: '200.0000',
    satisfied: true,
    explanation: '订单总额 200.0000 大于等于 阈值 100.0000 → 满足',
    ...over,
  }) as HitCondition;

describe('9.2.1.2 审单规则 —— 维度与运算符的配对', () => {
  it('数值型只给金额与折扣比例，标记型只给黑名单，其余维度只给集合型', () => {
    // 与后端 V149 的 operator_fits_dimension CHECK 同口径：
    // 前端不按这条过滤，就会让人配出「收货国家 ≥ 50」这种永远判不出结果的规则。
    expect(operatorsFor('AMOUNT')).toEqual(['GTE', 'GT', 'LTE', 'LT', 'EQ']);
    expect(operatorsFor('DISCOUNT_RATIO')).toEqual(['GTE', 'GT', 'LTE', 'LT', 'EQ']);
    expect(operatorsFor('BUYER_BLACKLIST')).toEqual(['IS_TRUE', 'IS_FALSE']);
    expect(operatorsFor('SHOP')).toEqual(['IN', 'NOT_IN']);
    expect(operatorsFor('ITEM')).toEqual(['IN', 'NOT_IN']);
    expect(operatorsFor('SHIPPING_COUNTRY')).toEqual(['IN', 'NOT_IN']);
    expect(operatorsFor('BUYER_LEVEL')).toEqual(['IN', 'NOT_IN']);
    expect(operatorsFor('ADDRESS_ANOMALY')).toEqual(['IN', 'NOT_IN']);
  });

  it('任何维度都拿不到不属于自己的运算符', () => {
    expect(operatorsFor('AMOUNT')).not.toContain('IN');
    expect(operatorsFor('BUYER_BLACKLIST')).not.toContain('GTE');
    expect(operatorsFor('SHOP')).not.toContain('EQ');
    expect(operatorsFor('BUYER_LEVEL')).not.toContain('IS_TRUE');
  });

  it('未知的维度退化成集合型，而不是给出空下拉框', () => {
    // 空下拉框会让用户以为这个维度不能配条件，实际上后端按集合型处理。
    expect(operatorsFor('SOMETHING_NEW')).toEqual(['IN', 'NOT_IN']);
  });
});

describe('9.2.1.2 审单规则 —— 「未知」不能显示成默认值', () => {
  it('买家等级 null 显示成「未登记」，绝不是 STANDARD', () => {
    expect(buyerLevelText(null)).toBe('未登记（等级未知）');
    expect(buyerLevelText(undefined)).toBe('未登记（等级未知）');
    expect(buyerLevelText('')).toBe('未登记（等级未知）');
    expect(buyerLevelText('VIP')).toBe('VIP');
    // 兜一个 STANDARD 会让「VIP 自动通过」把全网买家都放过去。
    expect(buyerLevelText(null)).not.toContain('STANDARD');
  });

  it('黑名单 null 显示成「未知」，绝不是「不在黑名单」', () => {
    expect(blacklistText(null)).toContain('未知');
    expect(blacklistText(undefined)).toContain('未知');
    expect(blacklistText(true)).toBe('在黑名单里');
    expect(blacklistText(false)).toBe('不在黑名单');
    expect(blacklistText(null)).not.toBe('不在黑名单');
  });

  it('折扣率 null 显示成「不可计算」，绝不是 0', () => {
    expect(discountRateText(null)).toContain('不可计算');
    expect(discountRateText(undefined)).toContain('不可计算');
    // 0 会被读成「零元订单打了 0 折」—— 那是结论，不是缺口。
    expect(discountRateText(null)).not.toBe('0');
    expect(discountRateText(42.5)).toBe('42.5%');
    expect(discountRateText(0)).toBe('0%');
  });

  it('命中明细取不到实际值时显示成「（无值）」，不能渲染成空白', () => {
    const lines = hitConditionLines(
      condition({ actualField: 'order_review_buyer_profile.buyer_level', actualValue: '' }),
    );
    expect(lines[0]?.field).toBe('order_review_buyer_profile.buyer_level');
    expect(lines[0]?.value).toBe('（无值）');
    // 空白会被读成「没查」，实际是「查了，取不到」。
    expect(lines[0]?.value).not.toBe('');
  });

  it('地址异常为空数组显示「无异常」，并把码翻成人话', () => {
    expect(anomalyText([])).toBe('无异常');
    expect(anomalyText(null)).toBe('无异常');
    expect(anomalyText(['RESERVED_POSTCODE'])).toContain('占位号');
    expect(Object.keys(ADDRESS_ANOMALY_LABELS)).toHaveLength(5);
  });
});

describe('9.2.1.2 审单规则 —— 命中链要连未命中的规则一起显示', () => {
  it('未命中行照样给出摘要与次序', () => {
    const miss = hit({
      ruleCode: 'R-DE',
      evalRank: 3,
      matched: false,
      forcedManual: false,
      action: 'AUTO_APPROVE',
      actionLabel: '自动通过',
      reason: '未命中：收货国家 US 属于 [DE] → 不满足',
    });
    expect(hitSummary(miss)).toContain('未命中');
    expect(hitSummary(miss)).toContain('第 3 位');
    expect(hitSummary(miss)).toContain('US');
    // 命中行也要标出「强制转人工」，否则运营分不清它压不压得住自动通过。
    expect(hitSummary(hit())).toContain('强制转人工');
    expect(hitSummary(hit())).toContain('命中');
  });

  it('条件摘要说清「拿什么比了什么」', () => {
    expect(conditionSummary(condition())).toBe('订单金额 大于等于 100.0000');
  });
});

describe('9.2.1.2 审单规则 —— 结论展示', () => {
  it('结论一句话原样用后端给的，不在前端另拼概括', () => {
    const d = decision();
    expect(modeText(d)).toBe('生效判定');
    expect(decisionCounts(d)).toBe('参与判定 4 条 · 命中 2 条 · 强制转人工 1 条 · 决定规则 R-BIG');
    expect(decisionTone(d.decision)).toBe('warning');
    expect(decisionTone('AUTO_APPROVE')).toBe('success');
  });

  it('试跑结论必须一眼与生产结论分开', () => {
    expect(modeText(decision({ mode: 'DRY_RUN' }))).toContain('不是生产结论');
  });

  it('没有任何规则命中时决定规则为空，汇总里不编一个规则号', () => {
    const d = decision({ winningRuleCode: '', matchedRuleCount: 0, forcedRuleCount: 0 });
    expect(decisionCounts(d)).toBe('参与判定 4 条 · 命中 0 条');
    expect(decisionCounts(d)).not.toContain('决定规则');
  });
});

describe('9.2.1.2 审单规则 —— 生效状态把状态与生效期分开说', () => {
  it('草稿与停用都不参与判定', () => {
    expect(effectiveText({ status: 'DRAFT', effectiveFrom: 'a', effectiveTo: '', effectiveNow: false }))
      .toContain('不参与判定');
    expect(
      effectiveText({ status: 'DISABLED', effectiveFrom: 'a', effectiveTo: '', effectiveNow: false }),
    ).toContain('不参与判定');
  });

  it('ACTIVE 但不在生效期内要说清，否则会以为它已经在管事了', () => {
    expect(
      effectiveText({ status: 'ACTIVE', effectiveFrom: '2026-03-10', effectiveTo: '', effectiveNow: false }),
    ).toContain('不在生效期内');
    expect(
      effectiveText({
        status: 'ACTIVE',
        effectiveFrom: '2026-03-01',
        effectiveTo: '2026-03-10',
        effectiveNow: true,
      }),
    ).toBe('生效中，至 2026-03-10');
    expect(
      effectiveText({ status: 'ACTIVE', effectiveFrom: '2026-03-01', effectiveTo: '', effectiveNow: true }),
    ).toBe('生效中，长期有效');
  });
});

describe('9.2.1.2 审单规则 —— 动作可用性', () => {
  it('零条件规则不能启用：ANY 会放行每一张单，ALL 永不命中', () => {
    expect(canActivate({ status: 'DRAFT', conditionCount: 0 })).toBe(false);
    expect(canActivate({ status: 'DRAFT', conditionCount: 1 })).toBe(true);
    expect(canActivate({ status: 'ACTIVE', conditionCount: 3 })).toBe(false);
    expect(canDisable({ status: 'ACTIVE' })).toBe(true);
    expect(canDisable({ status: 'DRAFT' })).toBe(false);
  });
});

describe('9.2.1.2 审单规则 —— 条件表单取值', () => {
  it('空串取值被剔除，空阈值变成 null 而不是 0', () => {
    const input = toConditionInput({
      seqNo: 1,
      dimension: 'SHOP',
      operator: 'IN',
      valueSet: ['AMAZON', '', '  ', 'SHOPIFY'],
      valueNumeric: null,
      description: '',
    });
    expect(input.valueSet).toEqual(['AMAZON', 'SHOPIFY']);
    // 0 会被当成「阈值是 0」，让这条规则命中每一张单 —— 所以「没填阈值」是 null。
    expect(input.valueNumeric).toBeNull();
  });

  it('详情里的条件回填表单时保留序号与取值', () => {
    const input = conditionToInput({
      id: 'c1',
      seqNo: 2,
      dimension: 'AMOUNT',
      dimensionLabel: '订单金额',
      operator: 'GTE',
      operatorLabel: '大于等于',
      valueSet: [],
      valueNumeric: 100,
      description: '大额',
    });
    expect(input.seqNo).toBe(2);
    expect(input.valueNumeric).toBe(100);
    expect(input.valueSet).toEqual([]);
  });
});

describe('9.2.1.2 审单规则 —— 错误态', () => {
  it('未知错误也有一句可读的话，不显示 undefined', () => {
    expect(errorText(new Error('boom'))).toBe('boom');
    expect(errorText('x')).toContain('暂时不可用');
    expect(errorText(undefined)).toContain('暂时不可用');
  });
});
