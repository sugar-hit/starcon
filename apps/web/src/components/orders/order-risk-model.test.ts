import { describe, expect, it } from 'vitest';
import {
  actionText,
  canReview,
  componentStatusText,
  dimensionText,
  errorText,
  evaluationStatusText,
  finalActionText,
  money,
  pageText,
  rawValueText,
  reasonText,
  score,
  scoreText,
  signalCodeText,
  sourceText,
  verdictText,
  weight,
} from './order-risk-model';
import type { RiskAssessment, RiskComponent } from '../../../../../packages/api-contracts/src/index';

/**
 * 9.2.2.2 风控校验前端模型。
 *
 * <p>重点保护四条「最容易在界面上说错」的语义 —— 每一条都对应一次真实的误伤：
 * 没测到 ≠ 0、建议拒发 ≠ 已拒发、没出分 ≠ 0 分、信号来源要说清是谁登记的。
 */

const component = (over: Partial<RiskComponent>): RiskComponent =>
  ({
    id: 'c1',
    dimension: 'BUYER_HISTORY',
    status: 'SCORED',
    weight: 0.2,
    rawValue: 0.1,
    normalizedScore: 10,
    contribution: 2,
    signalSource: 'TENANT_MAINTAINED',
    signalCapturedAt: '2026-02-20T08:00:00Z',
    explanation: '',
    ...over,
  }) as RiskComponent;

const assessment = (over: Partial<RiskAssessment>): RiskAssessment =>
  ({
    id: 'a1',
    orderId: 'o1',
    orderNo: 'MO-1',
    buyerFingerprint: 'P:abc',
    orderCurrency: 'USD',
    orderAmount: 100,
    orderSubmittedAt: '2026-03-01T08:00:00Z',
    ruleId: 'r1',
    ruleName: '默认风控',
    ruleVersion: 0,
    evaluatedReferenceAt: '2026-03-01T08:00:00Z',
    riskScore: 37.5,
    recommendedAction: 'MANUAL_REVIEW',
    manualReviewRequired: true,
    evaluationStatus: 'SCORED',
    scoredDimensionCount: 4,
    missingDimensionCount: 1,
    finalAction: null,
    reviewedAt: null,
    reviewedBy: null,
    totalsComputedAt: '2026-03-01T08:00:01Z',
    createdBy: 'u1',
    createdAt: '2026-03-01T08:00:00Z',
    ...over,
  }) as RiskAssessment;

describe('风控分与建议动作的界面语义', () => {
  it('没出分时绝不显示成 0.0000', () => {
    expect(scoreText({ riskScore: null, evaluationStatus: 'INSUFFICIENT_SIGNALS' })).toBe(
      '未出分（信号不足）',
    );
    // 这是本模块最危险的一处：0 分在风控语境里读成「量过了，安全」。
    expect(scoreText({ riskScore: 0, evaluationStatus: 'SCORED' })).toBe('0.0000');
    expect(score(null)).toBe('—');
    expect(score(37.5)).toBe('37.5000');
  });

  it('建议拒发时永远带着「尚无人工结论」', () => {
    const hold = assessment({ recommendedAction: 'HOLD_SHIPMENT', riskScore: 100 });
    expect(verdictText(hold)).toContain('建议拒发');
    expect(verdictText(hold)).toContain('尚无人工结论');
    expect(verdictText(hold)).toContain('分数不能单独作为拒绝依据');
    expect(hold.finalAction).toBeNull();
  });

  it('人工复核之后才出现最终动作', () => {
    const rejected = assessment({
      recommendedAction: 'HOLD_SHIPMENT',
      finalAction: 'REJECT',
    });
    expect(verdictText(rejected)).toContain('最终动作');
    expect(verdictText(rejected)).toContain('人工复核结论');
    expect(finalActionText(null)).toBe('尚未复核');
    expect(finalActionText('REJECT')).toBe('复核后拒发');
    expect(finalActionText('SHIP')).toBe('复核后发货');
  });

  it('建议放行的单不能补复核', () => {
    const released = assessment({
      recommendedAction: 'RELEASE',
      manualReviewRequired: false,
      finalAction: null,
    });
    expect(canReview(released)).toBe(false);
    expect(canReview(assessment({ recommendedAction: 'HOLD_SHIPMENT' }))).toBe(true);
    // 已复核过的不再可复核（风控留痕不可覆写）
    expect(canReview(assessment({ finalAction: 'REJECT' }))).toBe(false);
  });

  it('评估状态与建议动作各有各的中文名', () => {
    expect(evaluationStatusText('SCORED')).toBe('已出分');
    expect(evaluationStatusText('INSUFFICIENT_SIGNALS')).toBe('信号不足，未出分');
    expect(actionText('HOLD_SHIPMENT')).toBe('建议拒发');
    expect(actionText('RELEASE')).toBe('放行');
  });
});

describe('单维明细的界面语义', () => {
  it('没测到显示「未测到」而不是 0', () => {
    expect(rawValueText(component({ rawValue: null, status: 'MISSING_SIGNAL' }))).toBe('未测到');
    expect(rawValueText(component({ rawValue: 0 }))).toBe('0');
    expect(rawValueText(component({ rawValue: 0.1 }))).toBe('0.1');
    expect(rawValueText(component({ rawValue: 5 }))).toBe('5');
  });

  it('四种不可用各有各的说法', () => {
    expect(componentStatusText('MISSING_SIGNAL')).toBe('缺信号');
    expect(componentStatusText('STALE_SIGNAL')).toBe('信号已过期');
    expect(componentStatusText('INSUFFICIENT_HISTORY')).toBe('历史笔数不足');
    expect(componentStatusText('NOT_APPLICABLE')).toBe('该单不适用');
    expect(componentStatusText('SCORED')).toBe('已计分');
  });

  it('维度与信号码都有中文名', () => {
    expect(dimensionText('BUYER_HISTORY')).toBe('买家历史（退款率 / 纠纷率）');
    expect(dimensionText('ADDRESS_CONCENTRATION')).toBe('地址集中度');
    expect(signalCodeText('REFUND_RATE')).toBe('退款率');
    expect(signalCodeText('SHARED_IP_COUNT')).toBe('共用 IP 数');
  });

  it('信号来源说清是谁登记的', () => {
    expect(sourceText('TENANT_MAINTAINED')).toBe('租户登记');
    expect(sourceText('MODULE_15_CUSTOMER_PROFILE')).toContain('未交付');
    expect(sourceText('MODULE_16_RISK_LINKAGE')).toContain('未交付');
    expect(sourceText('DERIVED_FROM_ORDERS')).toBe('本服务订单簇现算');
  });
});

describe('格式化', () => {
  it('权重与金额只做展示格式化，不改值', () => {
    expect(weight(0.2)).toBe('0.2000');
    expect(weight(0.3333)).toBe('0.3333');
    expect(money(100)).toBe('100');
    expect(money(100.5)).toBe('100.5');
    expect(money(null)).toBe('—');
  });

  it('分页器在空集时说「共 0 条」', () => {
    expect(pageText(0, 20, 0)).toBe('共 0 条');
    expect(pageText(0, 20, 45)).toBe('第 1–20 条 / 共 45 条');
    expect(pageText(2, 20, 45)).toBe('第 41–45 条 / 共 45 条');
  });

  it('复核原因码里「误伤正常复购客户」有正式名字', () => {
    expect(reasonText('FALSE_POSITIVE')).toBe('误判，疑似正常复购客户');
    expect(reasonText('CONFIRMED_RISK')).toBe('复核确认有风险');
  });

  it('错误信息把后端的人话带出来', () => {
    expect(errorText(new Error('boom'))).toBe('boom');
    expect(errorText(new Error('网络断了'))).toBe('网络断了');
    expect(errorText('别的')).toBe('未知错误');
  });
});
