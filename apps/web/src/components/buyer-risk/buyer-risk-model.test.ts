import { describe, expect, it } from 'vitest';
import {
  actionLabel,
  actionTone,
  contributionText,
  emptyState,
  normalizedText,
  scoreText,
  statusLabel,
  unknownFeatures,
  weightSum,
  weightsBalanced,
  writebackLabel,
  type BuyerRiskFeature,
  type BuyerRiskScore,
} from './buyer-risk-model';

/**
 * 16.5.1.2 风险评分 前端模型的纯函数断言。
 *
 * 🔴 这个页面上最容易做丢的事，前端这一层再挡一道：
 * **「量不到」显示成「未知」而不是 0 分**、**前端不重算后端算出的分数**、
 * **三态空态文案各不相同**。
 */

function feature(overrides: Partial<BuyerRiskFeature> = {}): BuyerRiskFeature {
  return {
    featureId: null,
    dimensionCode: 'REFUND_HISTORY',
    dimensionLabel: '历史退款',
    featureCode: 'REFUND_RATE',
    featureLabel: '历史退款',
    available: true,
    rawValue: 'orders=4;refunded=1;rate=0.250000',
    normalizedValue: 0.833333,
    weight: 0.4,
    contribution: 41.6667,
    reason: '统计窗口内这个买家 4 笔订单中有 1 笔发起过退款（退款率 25.00%，满档阈值 30.00%）。',
    ownerModule: '',
    note: '',
    ...overrides,
  };
}

function score(features: BuyerRiskFeature[]): BuyerRiskScore {
  return {
    scoreId: 'score-1',
    buyerKey: 'a'.repeat(32),
    buyerName: 'Alice Smith',
    buyerPhone: '+1-555-0100',
    buyerEmail: 'alice@example.com',
    buyerLevel: 'STANDARD',
    blacklisted: false,
    blacklistReason: '',
    riskScore: 52.0833,
    evaluationStatus: 'SCORED',
    recommendedAction: 'MANUAL_REVIEW',
    scoredDimensionCount: 4,
    missingDimensionCount: 2,
    ruleId: 'rule-1',
    ruleVersion: 1,
    ruleName: '测试规则',
    missingSignalPolicy: 'SCORE_KNOWN_ONLY',
    evaluatedReferenceAt: '2026-10-23T00:00:00Z',
    ordersScanned: 4,
    orderCount: 4,
    refundedOrderCount: 1,
    lookbackDays: 180,
    conclusion: '总分 52.08',
    note: '统计窗口 180 天',
    createdAt: '2026-10-23T00:00:00Z',
    features,
    writebacks: [],
  };
}

describe('16.5.1.2 风险评分 · 前端模型', () => {
  it('量不到的维度显示「未知」而不是 0', () => {
    // 🔴 0 会被读成「这个买家很干净」，而真相是「我们没查到」。
    expect(scoreText(null)).toBe('未知');
    expect(normalizedText(null)).toBe('未知');
    expect(contributionText(null)).toBe('—');
    // 有值时原样显示后端算好的数（前端不重算）。
    expect(scoreText(52.0833)).toBe('52.08');
    expect(normalizedText(0.833333)).toBe('83.33%');
    expect(contributionText(41.6667)).toBe('41.6667');
  });

  it('量到的维度与量不到的维度都留在清单里', () => {
    const known = feature();
    const unknown = feature({
      dimensionCode: 'DISPUTE_HISTORY',
      dimensionLabel: '纠纷历史',
      featureCode: 'DISPUTE_RATE',
      available: false,
      normalizedValue: null,
      contribution: null,
      ownerModule: '11.4.1.1',
      reason: '平台纠纷没有买家 / 订单 join 键 ⇒ 本维度量不到。',
    });
    const list = unknownFeatures(score([known, unknown]));
    expect(list).toHaveLength(1);
    expect(list[0]?.dimensionCode).toBe('DISPUTE_HISTORY');
    expect(list[0]?.ownerModule).toBe('11.4.1.1');
  });

  it('建议动作与判定状态映射到中文', () => {
    expect(actionLabel('RELEASE')).toBe('建议放行');
    expect(actionLabel('MANUAL_REVIEW')).toBe('建议人工复核');
    expect(actionLabel('HOLD')).toBe('建议暂缓');
    expect(actionTone('HOLD')).toBe('danger');
    expect(statusLabel('SCORED')).toBe('已出分');
    expect(statusLabel('INSUFFICIENT_SIGNALS')).toBe('信号不足');
  });

  it('回写状态区分「已回写 / 未回写 / 跳过」', () => {
    expect(writebackLabel('SUCCEEDED')).toBe('已回写');
    expect(writebackLabel('FAILED')).toBe('未回写');
    expect(writebackLabel('SKIPPED')).toBe('跳过（无信号值）');
  });

  it('权重合计必须恰好 1.0000', () => {
    expect(weightSum([0.4, 0.2, 0.2, 0.1, 0.1])).toBeCloseTo(1, 9);
    expect(weightsBalanced([0.4, 0.2, 0.2, 0.1, 0.1])).toBe(true);
    expect(weightsBalanced([0.5, 0.2, 0.2, 0.1, 0.1])).toBe(false);
  });

  it('四种空态文案各不相同', () => {
    const titles = [
      emptyState('NO_RULE').title,
      emptyState('NO_BUYER').title,
      emptyState('NO_SCORE').title,
      emptyState('ALL_UNKNOWN').title,
    ];
    expect(new Set(titles).size).toBe(4);
    // 「全部量不到」那一档必须说清「没有分数 ≠ 零风险」。
    expect(emptyState('ALL_UNKNOWN').description).toContain('不是');
  });
});
