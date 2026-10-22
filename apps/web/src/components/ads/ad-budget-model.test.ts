import { describe, expect, it } from 'vitest';
import {
  deltaMoney,
  emptyAdBudgetPlanInput,
  emptyAdBudgetPoolInput,
  emptyAdBudgetRuleInput,
  guardrailText,
  isPositiveAmount,
  isUuid,
  money,
  planStatusLabel,
  ratioText,
  ratioTone,
  recommendationLabel,
  recommendationTone,
  ruleStatusLabel,
  skipReasonLabel,
  taskResultLabel,
  taskResultTone,
  taskStatusLabel,
  taskStatusTone,
  validateAdBudgetPlan,
  validateAdBudgetPool,
  validateAdBudgetRule,
  weightSourceLabel,
  weightSourceTone,
} from './ad-budget-model';

/**
 * 14.2.2.1 预算分配 前端模型测试。
 *
 * 🔴 这里钉住的是**最容易在界面上做丢**的几件事：
 *   1. 不可比的比率显示「不可比」而不是 0%；
 *   2. 利润权重当前**必须**被挡住，并说得出「为什么现在不行」；
 *   3. 空态是三件不同的事，不能渲染成同一种；
 *   4. 状态 / 结果的语气区分（被护栏拒绝的那几条要显眼）。
 */
describe('14.2.2.1 预算分配 · 展示辅助', () => {
  it('金额原样展示，null 显示「—」', () => {
    expect(money(1000)).toBe('1000.00');
    expect(money(1000, 'USD')).toBe('1000.00 USD');
    expect(money(null)).toBe('—');
    expect(money(undefined)).toBe('—');
  });

  it('增减额必须看得出方向', () => {
    expect(deltaMoney(100)).toBe('+100.00');
    expect(deltaMoney(-100)).toBe('-100.00');
    expect(deltaMoney(0)).toBe('0.00');
    expect(deltaMoney(null)).toBe('—');
  });

  it('🔴 不可比的比率显示「不可比」而不是 0.00%', () => {
    expect(ratioText(null)).toBe('不可比');
    expect(ratioText(undefined)).toBe('不可比');
    expect(ratioText(0)).toBe('0.00%');
    expect(ratioText(0.9525)).toBe('95.25%');
    expect(ratioText(5, false)).toBe('5.0000');
    expect(ratioTone(null)).toBe('text-muted-foreground');
    expect(ratioTone(0)).toBe('text-foreground');
  });

  it('利润权重来源必须显式标出「当前不可用」', () => {
    expect(weightSourceLabel('SALES_TARGET')).toBe('销售目标');
    expect(weightSourceLabel('ROAS')).toBe('历史 ROAS');
    expect(weightSourceLabel('PROFIT')).toBe('利润（当前不可用）');
    expect(weightSourceTone('PROFIT')).toBe('text-muted-foreground');
  });

  it('调整建议标签与语气', () => {
    expect(recommendationLabel('INCREASE')).toBe('建议加预算');
    expect(recommendationLabel('DECREASE')).toBe('建议减预算');
    expect(recommendationLabel('PAUSE_CANDIDATE')).toBe('建议收回预算并保持暂停');
    expect(recommendationLabel('START_CANDIDATE')).toBe('建议开预算');
    expect(recommendationLabel('HOLD')).toBe('维持');
    expect(recommendationTone('INCREASE')).toBe('text-emerald-600');
    expect(recommendationTone('DECREASE')).toBe('text-amber-600');
    expect(recommendationTone('HOLD')).toBe('text-muted-foreground');
  });

  it('未纳入分配的原因标签', () => {
    expect(skipReasonLabel('CURRENCY_MISMATCH')).toBe('币种与预算池不一致');
    expect(skipReasonLabel('NO_SCORE_BASIS')).toBe('没有可用的分配依据');
    expect(skipReasonLabel('STATUS_NOT_ELIGIBLE')).toBe('活动状态不该参与分配');
    expect(skipReasonLabel('OUT_OF_SCOPE')).toBe('不在规则范围内');
  });

  it('状态标签', () => {
    expect(ruleStatusLabel('ACTIVE')).toBe('启用');
    expect(planStatusLabel('COMPUTED')).toBe('已计算');
    expect(planStatusLabel('APPLIED')).toBe('已执行');
    expect(taskStatusLabel('PENDING')).toBe('待执行');
    expect(taskStatusLabel('PARTIALLY_APPLIED')).toBe('部分生效');
    expect(taskStatusTone('FAILED')).toBe('text-destructive');
    expect(taskStatusTone('APPLIED')).toBe('text-emerald-600');
  });

  it('🔴 被护栏拒绝的条目要显眼', () => {
    expect(taskResultLabel('REJECTED')).toBe('被护栏拒绝');
    expect(taskResultTone('REJECTED')).toBe('text-destructive');
    expect(taskResultLabel('APPLIED')).toBe('已生效');
    expect(taskResultTone('APPLIED')).toBe('text-emerald-600');
    expect(taskResultTone('SKIPPED')).toBe('text-muted-foreground');
  });

  it('护栏上限的一行说明把天花板写出来', () => {
    const text = guardrailText({
      maxSingleBudgetDelta: 5000,
      maxTotalBudgetDelta: 20000,
      maxCampaignBudget: 100000,
      maxCampaignsPerTask: 200,
      note: '',
    });
    expect(text).toContain('5000');
    expect(text).toContain('20000');
    expect(text).toContain('100000');
    expect(text).toContain('200');
    expect(guardrailText(null)).toBe('');
  });

  it('UUID 与金额的形状校验', () => {
    expect(isUuid('11111111-1111-1111-1111-111111111111')).toBe(true);
    expect(isUuid('not-a-uuid')).toBe(false);
    expect(isPositiveAmount('100')).toBe(true);
    expect(isPositiveAmount('0')).toBe(false);
    expect(isPositiveAmount('-1')).toBe(false);
    expect(isPositiveAmount('')).toBe(false);
  });
});

describe('14.2.2.1 预算分配 · 分配规则表单校验', () => {
  it('空表单会被拦下', () => {
    const result = validateAdBudgetRule(emptyAdBudgetRuleInput);
    expect(result.ok).toBe(false);
    expect(result.errors.join(' ')).toContain('规则编码必填');
    expect(result.errors.join(' ')).toContain('规则名称必填');
  });

  it('🔴 非零利润权重被拦下，并说得出「为什么现在不行」', () => {
    const result = validateAdBudgetRule({
      ...emptyAdBudgetRuleInput,
      ruleCode: 'R-1',
      ruleName: '规则一',
      profitWeight: '2',
    });
    expect(result.ok).toBe(false);
    expect(result.errors.join(' ')).toContain('13.4');
    expect(result.errors.join(' ')).toContain('利润权重当前不可用');
  });

  it('利润权重为 0 时可以通过', () => {
    const result = validateAdBudgetRule({
      ...emptyAdBudgetRuleInput,
      ruleCode: 'R-1',
      ruleName: '规则一',
      salesTargetWeight: '1',
      roasWeight: '1',
      profitWeight: '0',
    });
    expect(result.errors).toEqual([]);
    expect(result.ok).toBe(true);
  });

  it('全 0 权重被拦下（全 0 的规则算出来的分配额全是 0）', () => {
    const result = validateAdBudgetRule({
      ...emptyAdBudgetRuleInput,
      ruleCode: 'R-1',
      ruleName: '规则一',
      salesTargetWeight: '0',
      roasWeight: '0',
      profitWeight: '0',
    });
    expect(result.ok).toBe(false);
    expect(result.errors.join(' ')).toContain('至少一个权重大于 0');
  });

  it('受限阈值必须高于闲置阈值', () => {
    const result = validateAdBudgetRule({
      ...emptyAdBudgetRuleInput,
      ruleCode: 'R-1',
      ruleName: '规则一',
      constrainedUtilization: '0.2',
      idleUtilization: '0.3',
    });
    expect(result.ok).toBe(false);
    expect(result.errors.join(' ')).toContain('预算受限阈值必须高于闲置阈值');
  });

  it('上限低于下限被拦下', () => {
    const result = validateAdBudgetRule({
      ...emptyAdBudgetRuleInput,
      ruleCode: 'R-1',
      ruleName: '规则一',
      minCampaignBudget: '100',
      maxCampaignBudget: '50',
    });
    expect(result.ok).toBe(false);
    expect(result.errors.join(' ')).toContain('上限不能低于下限');
  });

  it('销售目标必须点名合法活动与大于 0 的目标额', () => {
    const bad = validateAdBudgetRule({
      ...emptyAdBudgetRuleInput,
      ruleCode: 'R-1',
      ruleName: '规则一',
      targetCampaignId: 'not-a-uuid',
      targetSalesTarget: '0',
    });
    expect(bad.ok).toBe(false);
    expect(bad.errors.join(' ')).toContain('活动 id 必须是合法 UUID');
    expect(bad.errors.join(' ')).toContain('销售目标必须大于 0');
  });
});

describe('14.2.2.1 预算分配 · 预算池表单校验', () => {
  it('空表单会被拦下', () => {
    const result = validateAdBudgetPool(emptyAdBudgetPoolInput);
    expect(result.ok).toBe(false);
    expect(result.errors.join(' ')).toContain('预算池编码必填');
    expect(result.errors.join(' ')).toContain('预算总额必须大于 0');
  });

  it('周期结束不能早于周期开始', () => {
    const result = validateAdBudgetPool({
      ...emptyAdBudgetPoolInput,
      poolCode: 'P-1',
      poolName: '池一',
      totalAmount: '1000',
      periodStart: '2026-10-02',
      periodEnd: '2026-10-01',
      ruleId: '11111111-1111-1111-1111-111111111111',
    });
    expect(result.ok).toBe(false);
    expect(result.errors.join(' ')).toContain('周期结束不能早于周期开始');
  });

  it('合法表单通过', () => {
    const result = validateAdBudgetPool({
      ...emptyAdBudgetPoolInput,
      poolCode: 'P-1',
      poolName: '池一',
      totalAmount: '1000',
      periodStart: '2026-10-01',
      periodEnd: '2026-10-31',
      ruleId: '11111111-1111-1111-1111-111111111111',
    });
    expect(result.errors).toEqual([]);
    expect(result.ok).toBe(true);
  });
});

describe('14.2.2.1 预算分配 · 计算方案表单校验', () => {
  it('🔴 统计窗口必须显式给出（不从墙上时钟推导）', () => {
    const result = validateAdBudgetPlan(emptyAdBudgetPlanInput);
    expect(result.ok).toBe(false);
    expect(result.errors.join(' ')).toContain('统计窗口开始必填');
    expect(result.errors.join(' ')).toContain('统计窗口结束必填');
  });

  it('窗口结束不能早于开始', () => {
    const result = validateAdBudgetPlan({
      ...emptyAdBudgetPlanInput,
      poolId: '11111111-1111-1111-1111-111111111111',
      windowStart: '2026-10-02',
      windowEnd: '2026-10-01',
    });
    expect(result.ok).toBe(false);
    expect(result.errors.join(' ')).toContain('统计窗口结束不能早于开始');
  });

  it('合法表单通过', () => {
    const result = validateAdBudgetPlan({
      ...emptyAdBudgetPlanInput,
      poolId: '11111111-1111-1111-1111-111111111111',
      windowStart: '2026-10-01',
      windowEnd: '2026-10-31',
    });
    expect(result.errors).toEqual([]);
    expect(result.ok).toBe(true);
  });
});
