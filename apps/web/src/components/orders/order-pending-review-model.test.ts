import { describe, expect, it } from 'vitest';
import {
  approveDisabledReason,
  canSelect,
  errorText,
  hitRuleText,
  isoText,
  money,
  outcomeText,
  pageText,
  queueStatusText,
  reviewDecisionText,
  reviewModeText,
  riskScoreHint,
  riskScoreText,
  timelinessText,
  waitingText,
} from './order-pending-review-model';
import type { PendingReviewRow } from '../../../../../packages/api-contracts/src/index';

/**
 * 9.2.1.1 待审队列前端模型。
 *
 * <p>重点保护四条「最容易在界面上说错」的语义 ——
 * 每一条都对应一次真实的误判：
 * 未评估 ≠ 0 分、试跑 ≠ 生效判定、「跳过」必须带原因、批量通过必须先挑规则。
 */

const row = (over: Partial<PendingReviewRow>): PendingReviewRow =>
  ({
    orderId: 'o1',
    orderNo: 'MO-1',
    externalOrderNo: 'EXT-1',
    orderSource: 'AMAZON',
    currencyCode: 'USD',
    grandTotal: 50,
    submittedAt: '2026-03-01T08:00:00Z',
    waitingHours: 3,
    timelinessScore: 25,
    timelinessState: 'FRESH',
    timelinessStateLabel: '未超时',
    queueStatus: 'PENDING',
    queueStatusLabel: '待审',
    decidedAt: null,
    decisionReason: null,
    ruleCode: null,
    ruleVersion: null,
    batchId: null,
    riskScore: 80,
    riskRecommendedAction: 'MANUAL_REVIEW',
    riskManualReviewRequired: true,
    riskEvaluationStatus: 'SCORED',
    reviewDecision: 'AUTO_APPROVE',
    reviewMode: 'RECORDED',
    reviewDecisionReason: '命中小额自动通过',
    winningRuleCode: 'AUTO-SMALL',
    winningRuleName: '小额自动通过',
    matchedRuleCount: 1,
    forcedRuleCount: 0,
    hitRuleCodes: ['AUTO-SMALL'],
    addressAnomalyCodes: [],
    recipientName: '收件人',
    shippingCountry: 'US',
    ...over,
  }) as PendingReviewRow;

describe('9.2.1.1 待审队列模型', () => {
  it('未评估的风险分显示「未评估」，绝不显示 0', () => {
    expect(riskScoreText(null)).toBe('未评估');
    expect(riskScoreText(undefined)).toBe('未评估');
    expect(riskScoreText(0)).toBe('0.0000');
    expect(riskScoreText(80)).toBe('80.0000');
  });

  it('未评估时要说清是谁没评估，而不是只留一个破折号', () => {
    const hint = riskScoreHint({ riskScore: null, riskEvaluationStatus: null });
    expect(hint).toContain('未评估');
    expect(hint).toContain('9.2.2.2');
    expect(hint).not.toContain('0.0000');
  });

  it('试跑结果与生效判定分开标', () => {
    expect(reviewModeText('RECORDED')).toBe('生效判定');
    expect(reviewModeText('DRY_RUN')).toBe('试跑结果');
    expect(reviewModeText(null)).toBe('—');
    expect(reviewDecisionText('AUTO_APPROVE')).toBe('规则判定通过');
    expect(reviewDecisionText('MANUAL_REVIEW')).toBe('规则判定转人工');
    expect(reviewDecisionText(null)).toBe('未判定');
  });

  it('命中的规则为空时说「无」而不是留白', () => {
    expect(hitRuleText(row({ hitRuleCodes: [] }))).toBe('无');
    expect(hitRuleText(row({ hitRuleCodes: ['AUTO-SMALL', 'FORCE-HUMAN'] }))).toBe(
      'AUTO-SMALL、FORCE-HUMAN',
    );
  });

  it('终态的单不能再去勾选批量审单', () => {
    expect(canSelect(row({ queueStatus: 'PENDING' }))).toBe(true);
    expect(canSelect(row({ queueStatus: 'SUSPENDED' }))).toBe(true);
    expect(canSelect(row({ queueStatus: 'APPROVED' }))).toBe(false);
    expect(canSelect(row({ queueStatus: 'REJECTED' }))).toBe(false);
  });

  it('批量通过没挑规则时按钮必须禁用，并说清为什么', () => {
    expect(approveDisabledReason(0, '', 1)).toBe('先勾选要审的订单');
    expect(approveDisabledReason(2, '', 1)).toContain('必须先选一条依据的规则');
    expect(approveDisabledReason(2, 'AUTO-SMALL', 0)).toContain('没有生效中的「自动通过」规则');
    expect(approveDisabledReason(2, 'AUTO-SMALL', 1)).toBeNull();
  });

  it('队列状态与时效状态各有各的中文名', () => {
    expect(queueStatusText('PENDING')).toBe('待审');
    expect(queueStatusText('SUSPENDED')).toBe('已挂起');
    expect(queueStatusText('APPROVED')).toBe('已通过');
    expect(queueStatusText('REJECTED')).toBe('已驳回');
    expect(timelinessText('FRESH')).toBe('未超时');
    expect(timelinessText('DUE_SOON')).toBe('临近超时');
    expect(timelinessText('OVERDUE')).toBe('已超时');
    expect(timelinessText('NO_DEADLINE')).toBe('无时限');
  });

  it('逐条结果的中文名覆盖四种', () => {
    expect(outcomeText('APPROVED')).toBe('已通过');
    expect(outcomeText('REJECTED')).toBe('已驳回');
    expect(outcomeText('SUSPENDED')).toBe('已挂起');
    expect(outcomeText('SKIPPED')).toBe('已跳过');
  });

  it('金额 / 时长 / 时刻的格式化都不算错', () => {
    expect(money(50)).toBe('50');
    expect(money(50.5)).toBe('50.5');
    expect(money(null)).toBe('—');
    expect(waitingText(3.25)).toBe('3.3 小时');
    expect(waitingText(null)).toBe('—');
    expect(isoText(null)).toBe('—');
    expect(isoText('2026-03-01T08:00:00Z')).not.toBe('Invalid Date');
  });

  it('分页器文案', () => {
    expect(pageText(0, 20, 0)).toBe('共 0 条');
    expect(pageText(0, 20, 45)).toBe('第 1–20 条 / 共 45 条');
    expect(pageText(2, 20, 45)).toBe('第 41–45 条 / 共 45 条');
  });

  it('错误信息把后端的人话原样带出来', () => {
    expect(errorText(new Error('boom'))).toBe('boom');
    expect(errorText('x')).toBe('未知错误');
  });
});
