import { describe, expect, it } from 'vitest';
import {
  adjustmentStatusLabel,
  adjustmentStatusTone,
  dispatchActionLabel,
  dispatchStatusLabel,
  dispatchStatusTone,
  dynamicStrategyLabel,
  dynamicStrategyTone,
  guardrailLabel,
  money,
  multiplierText,
  policyStatusLabel,
  policyStatusTone,
  profitStatusLabel,
  ratioText,
  runModeLabel,
  scopeLabel,
  suggestionStatusLabel,
  validateBiddingPolicy,
} from './ad-bidding-model';

/**
 * 14.2.1.1 竞价管理 前端模型测试。
 *
 * 🔴 这里钉住的是**最容易在界面上做丢**的几件事：
 *   1. 🔴 **动态竞价策略只有三档**（只增只降 / 只降低 / 只提高）——
 *      界面上一旦出现自由输入框，平台侧就会收到一个它不认识的策略；
 *   2. 🔴 **不可比 ≠ 零**：ACOS / ROAS 为 null 时显示「—（不可比）」而不是 0%；
 *   3. 🔴 **两道闸要分开说**：竞价范围上限（闸①）与单次调整额度（闸②）不是一回事；
 *   4. 🔴 **「待下发」≠「已生效」**，**「撤销」≠「回滚」**；
 *   5. 🔴 **目标 ACOS / ROAS 二选一**（互为倒数，同时给两个就是两个互相矛盾的答案）；
 *   6. 🔴 **试运行 ≠ 已生效**（「本来会变成多少」不能显示成「已改成」）。
 */
describe('14.2.1.1 竞价管理 · 展示辅助', () => {
  it('🔴 动态竞价策略只有三档，各自有名字与语气', () => {
    expect(dynamicStrategyLabel('UP_AND_DOWN')).toBe('只增只降');
    expect(dynamicStrategyLabel('DOWN_ONLY')).toBe('只降低');
    expect(dynamicStrategyLabel('UP_ONLY')).toBe('只提高');

    expect(dynamicStrategyTone('UP_AND_DOWN')).toBe('warn');
    expect(dynamicStrategyTone('DOWN_ONLY')).toBe('good');
    expect(dynamicStrategyTone('UP_ONLY')).toBe('muted');
    // 未知档位原样透出（不能悄悄当成某一档）
    expect(dynamicStrategyLabel('WHATEVER')).toBe('WHATEVER');
  });

  it('🔴 比率为 null 时显示「不可比」而不是 0%（分母为 0 ≠ 真的是零）', () => {
    expect(ratioText(null)).toBe('—（不可比）');
    expect(ratioText(0)).toBe('0.00%');
    expect(ratioText(0.25)).toBe('25.00%');
    expect(ratioText(4)).toBe('400.00%');
  });

  it('🔴 倍率为 null 时显示「算不出来」，0 倍率原样显示', () => {
    expect(multiplierText(null)).toBe('—（算不出来）');
    expect(multiplierText(0)).toBe('0.0000');
    expect(multiplierText(2.5)).toBe('2.5000');
  });

  it('🔴 金额为 null 时显示「—」，不显示 0', () => {
    expect(money(null)).toBe('—');
    expect(money(0)).toBe('0.0000');
    expect(money(2.5)).toBe('2.5000');
  });

  it('🔴 两道闸的门牌号分开（竞价范围上限 ≠ 单次调整额度）', () => {
    expect(guardrailLabel('RANGE_MIN')).toContain('竞价范围');
    expect(guardrailLabel('RANGE_MAX')).toContain('竞价范围');
    expect(guardrailLabel('ADJUST_RATIO')).toContain('单次调整');
    expect(guardrailLabel('ADJUST_AMOUNT')).toContain('单次调整');
    expect(guardrailLabel('UPSTREAM_TOTAL')).toContain('总上调');
    expect(guardrailLabel('NO_BASIS')).toBe('算不出来');
    expect(guardrailLabel('ABOVE_BREAK_EVEN')).toBe('高于盈亏平衡');
    expect(guardrailLabel('')).toBe('—');
  });

  it('🔴 待下发 ≠ 已生效；撤销 ≠ 回滚（文案不出现「已生效」）', () => {
    expect(dispatchStatusLabel('PENDING')).toBe('待下发');
    expect(dispatchStatusLabel('CANCELLED')).toBe('已撤销');
    expect(dispatchStatusTone('PENDING')).toBe('warn');
    expect(dispatchStatusTone('CANCELLED')).toBe('muted');
    expect(dispatchActionLabel('SET_BID')).toBe('改竞价');
    expect(dispatchActionLabel('SET_BIDDING_STRATEGY')).toBe('改动态竞价策略');
  });

  it('🔴 试运行与真调分开显示', () => {
    expect(runModeLabel('DRY_RUN')).toContain('试运行');
    expect(runModeLabel('APPLY')).toContain('真调');
  });

  it('🔴 策略状态与调整状态各有名字', () => {
    expect(policyStatusLabel('DRAFT')).toBe('草稿');
    expect(policyStatusLabel('ACTIVE')).toBe('生效中');
    expect(policyStatusTone('ACTIVE')).toBe('good');
    expect(adjustmentStatusLabel('APPLIED')).toBe('本地已改');
    expect(adjustmentStatusTone('REJECTED')).toBe('bad');
    expect(scopeLabel('SHOP')).toBe('店铺级');
    expect(scopeLabel('CAMPAIGN')).toBe('活动级');
    expect(profitStatusLabel('AVAILABLE')).toBe('利润可算');
    expect(profitStatusLabel('NOT_COMPARABLE')).toContain('不可比');
    expect(suggestionStatusLabel('PARTIAL')).toBe('部分算不出来');
  });
});

describe('14.2.1.1 竞价管理 · 新建策略的前置校验', () => {
  const base = {
    policyCode: 'P-1',
    policyName: '策略 1',
    shopCode: 'SHOP-A',
    dynamicBiddingStrategy: 'DOWN_ONLY',
    currency: 'USD',
    scope: 'SHOP',
    targetCampaignId: '',
    minBid: '',
    maxBid: '',
    singleAdjustCapRatio: '',
    singleAdjustCapAmount: '',
    targetAcos: '0.25',
    targetRoas: '',
  };

  it('一个完整的目标 + 三档策略 ⇒ 通过', () => {
    expect(validateBiddingPolicy(base)).toEqual([]);
  });

  it('🔴 动态竞价策略不是三档之一 ⇒ 被拒（自由文本进不来）', () => {
    expect(validateBiddingPolicy({ ...base, dynamicBiddingStrategy: '随便写' }).join()).toContain(
      '三档',
    );
  });

  it('🔴 目标 ACOS 与目标 ROAS 同时给 ⇒ 被拒（互为倒数，二选一）', () => {
    const errors = validateBiddingPolicy({ ...base, targetAcos: '0.25', targetRoas: '4' });
    expect(errors.join()).toContain('只能给一个');
  });

  it('🔴 两个目标都不给 ⇒ 被拒（没有目标就反推不出建议）', () => {
    const errors = validateBiddingPolicy({ ...base, targetAcos: '', targetRoas: '' });
    expect(errors.join()).toContain('必须给一个目标');
  });

  it('🔴 竞价范围上限低于下限 ⇒ 被拒', () => {
    const errors = validateBiddingPolicy({ ...base, minBid: '5', maxBid: '1' });
    expect(errors.join()).toContain('上限不能低于下限');
  });

  it('🔴 单次调整比例额度不在 0..1 ⇒ 被拒', () => {
    expect(validateBiddingPolicy({ ...base, singleAdjustCapRatio: '2' }).join()).toContain(
      '0 与 1 之间',
    );
    expect(validateBiddingPolicy({ ...base, singleAdjustCapRatio: '-1' }).join()).toContain(
      '0 与 1 之间',
    );
  });

  it('🔴 活动级范围没给目标活动 ⇒ 被拒', () => {
    expect(validateBiddingPolicy({ ...base, scope: 'CAMPAIGN' }).join()).toContain('目标活动');
  });

  it('🔴 币种不是三位字母 ⇒ 被拒；小写会被归一（与后端同口径）；编码 / 名为空 ⇒ 被拒', () => {
    expect(validateBiddingPolicy({ ...base, currency: 'US' }).join()).toContain('三位大写字母');
    expect(validateBiddingPolicy({ ...base, currency: 'US$' }).join()).toContain('三位大写字母');
    // 小写会被归一成大写（与后端 `upper(currency)` 同一口径，不是错别字级拒绝）
    expect(validateBiddingPolicy({ ...base, currency: 'usd' })).toEqual([]);
    expect(validateBiddingPolicy({ ...base, policyCode: ' ' }).join()).toContain('策略编码');
    expect(validateBiddingPolicy({ ...base, policyName: ' ' }).join()).toContain('策略名');
    expect(validateBiddingPolicy({ ...base, shopCode: '' }).join()).toContain('店铺');
  });
});
