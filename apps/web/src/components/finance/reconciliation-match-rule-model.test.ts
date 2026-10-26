import { describe, expect, it } from 'vitest';
import {
  MATCH_RATE_BELOW_TARGET_HINT,
  MATCH_RULE_HARD_CONSTRAINT_HINT,
  MATCH_RULE_KEY_OPTIONS,
  MATCH_RULE_MIN_DIMENSION_COUNT,
  MATCH_RULE_MIN_KEYS,
  formatMatchRate,
  matchRateReasonHint,
  matchRateTargetVerdict,
  matchRuleActivateBlockReason,
  matchRuleDimensionCountValid,
  matchRuleEventLabel,
  matchRuleKeyLabel,
  matchRuleKeysLabel,
  matchRuleKeysValid,
  matchRuleReasonLabel,
  matchRuleScopeLabel,
  matchRuleSpecLabel,
  matchRuleStatusLabel,
  matchRuleStatusTone,
} from './reconciliation-match-rule-model';

/**
 * 12.3.2.3 自动匹配规则的前端模型测试。
 *
 * <p>🔴 本文件最要紧的一组断言是「**匹配键里没有金额**」：卷 12 的加粗硬约束是
 * 「不允许『金额相同即匹配』——金额相同只是候选，必须结合订单号、日期、类型」。
 * 光靠后端与 DB 拦住不够 —— **用户在界面上也不该看到「只按金额」这个选项**。
 */
describe('12.3.2.3 自动匹配规则前端模型', () => {
  describe('🔴 硬约束：匹配键里没有金额', () => {
    it('候选档位只有订单号 / 日期 / 类型三档', () => {
      expect(MATCH_RULE_KEY_OPTIONS.map((o) => o.value)).toEqual([
        'REFERENCE',
        'DATE',
        'TYPE',
      ]);
      expect(MATCH_RULE_KEY_OPTIONS.map((o) => o.value)).not.toContain('AMOUNT');
    });

    it('金额没有对应的匹配键标签（落到 default 分支也不该被当成一个可用维度）', () => {
      // 未知键会原样返回 —— 但它**不在候选档位里**，所以界面上根本选不到。
      expect(MATCH_RULE_KEY_OPTIONS.some((o) => o.value === 'AMOUNT')).toBe(false);
      expect(matchRuleKeyLabel('AMOUNT')).toBe('AMOUNT');
    });

    it('匹配键至少两个才算合法', () => {
      expect(MATCH_RULE_MIN_KEYS).toBe(2);
      expect(matchRuleKeysValid(['REFERENCE'])).toBe(false);
      expect(matchRuleKeysValid(['REFERENCE', 'DATE'])).toBe(true);
      expect(matchRuleKeysValid(['REFERENCE', 'DATE', 'TYPE'])).toBe(true);
    });

    it('含「金额」的匹配键不合法', () => {
      expect(matchRuleKeysValid(['AMOUNT', 'REFERENCE'])).toBe(false);
      expect(matchRuleKeysValid(['AMOUNT', 'DATE'])).toBe(false);
    });

    it('至少对上的维度数下限是 2（金额 + 单个维度不构成匹配）', () => {
      expect(MATCH_RULE_MIN_DIMENSION_COUNT).toBe(2);
      expect(matchRuleDimensionCountValid(1, 3)).toBe(false);
      expect(matchRuleDimensionCountValid(2, 3)).toBe(true);
      expect(matchRuleDimensionCountValid(3, 3)).toBe(true);
      // 也不能超过匹配键的维度数。
      expect(matchRuleDimensionCountValid(3, 2)).toBe(false);
    });

    it('硬约束提示把「金额从匹配键里剔出去」讲清楚', () => {
      expect(MATCH_RULE_HARD_CONSTRAINT_HINT).toContain('金额');
      expect(MATCH_RULE_HARD_CONSTRAINT_HINT).toContain('订单号');
      expect(MATCH_RULE_HARD_CONSTRAINT_HINT).toContain('至少');
    });
  });

  describe('匹配键 / 规则状态文案', () => {
    it('匹配键标签', () => {
      expect(matchRuleKeyLabel('REFERENCE')).toBe('订单号');
      expect(matchRuleKeyLabel('DATE')).toBe('日期');
      expect(matchRuleKeyLabel('TYPE')).toBe('类型');
      expect(matchRuleKeyLabel('WAT')).toBe('WAT');
    });

    it('匹配键列表拼装', () => {
      expect(matchRuleKeysLabel(['REFERENCE', 'DATE'])).toBe('订单号 + 日期');
      expect(matchRuleKeysLabel([])).toBe('');
    });

    it('规则状态文案与色调', () => {
      expect(matchRuleStatusLabel('DRAFT')).toBe('未启用');
      expect(matchRuleStatusLabel('ACTIVE')).toBe('已启用');
      expect(matchRuleStatusLabel('DISABLED')).toBe('已停用');
      expect(matchRuleStatusLabel('WHAT')).toBe('WHAT');
      expect(matchRuleStatusTone('ACTIVE')).toBe('success');
      expect(matchRuleStatusTone('DRAFT')).toBe('warning');
      expect(matchRuleStatusTone('DISABLED')).toBe('neutral');
    });

    it('事件类型文案', () => {
      expect(matchRuleEventLabel('RULE_CREATED')).toBe('建立匹配规则');
      expect(matchRuleEventLabel('RULE_UPDATED')).toBe('改动匹配规则');
      expect(matchRuleEventLabel('RULE_ACTIVATED')).toBe('启用匹配规则');
      expect(matchRuleEventLabel('RULE_DISABLED')).toBe('停用匹配规则');
      expect(matchRuleEventLabel('RATE_SNAPSHOT')).toBe('记录匹配率快照');
      expect(matchRuleEventLabel('WHAT')).toBe('WHAT');
    });

    it('未匹配原因文案（含容差归因出来的两类）', () => {
      expect(matchRuleReasonLabel('EXTERNAL_ONLY')).toBe('平台账单多出');
      expect(matchRuleReasonLabel('BOOK_ONLY')).toBe('我方账多出');
      expect(matchRuleReasonLabel('AMOUNT_MISMATCH')).toBe('金额对不齐');
      expect(matchRuleReasonLabel('CURRENCY_MISMATCH')).toBe('币种不一致');
      expect(matchRuleReasonLabel('DIMENSION_MISMATCH')).toBe('维度对不上');
      expect(matchRuleReasonLabel('FEE_DIFFERENCE')).toBe('手续费差异');
      expect(matchRuleReasonLabel('EXCHANGE_RATE_DIFFERENCE')).toBe('汇率差');
      expect(matchRuleReasonLabel('WHAT')).toBe('WHAT');
    });
  });

  describe('🔴 匹配率：判不了是一档独立状态', () => {
    it('分母为 0 时显示「—」而不是 0', () => {
      expect(formatMatchRate(null)).toContain('—');
      expect(formatMatchRate(null)).not.toContain('0.00%');
      expect(formatMatchRate(undefined)).toContain('—');
      expect(formatMatchRate(0)).toBe('0.00%');
      expect(formatMatchRate(1)).toBe('100.00%');
      expect(formatMatchRate(0.125)).toBe('12.50%');
    });

    it('没有启用中的规则（没有目标）⇒ 判不了，不是「未达标」', () => {
      const verdict = matchRateTargetVerdict(0.5, null, null);
      expect(verdict.label).toContain('判不了');
      expect(verdict.tone).toBe('neutral');
      expect(verdict.label).not.toContain('未达标');
    });

    it('算不出比率（分母为 0）⇒ 判不了', () => {
      const verdict = matchRateTargetVerdict(null, 90, null);
      expect(verdict.label).toContain('判不了');
      expect(verdict.tone).toBe('neutral');
    });

    it('低于目标 ⇒ 未达标（danger）', () => {
      const verdict = matchRateTargetVerdict(0.5, 90, false);
      expect(verdict.label).toContain('未达标');
      expect(verdict.tone).toBe('danger');
      expect(verdict.label).toContain('50.00%');
      expect(verdict.label).toContain('90');
    });

    it('达到目标 ⇒ 达标（success）', () => {
      const verdict = matchRateTargetVerdict(0.95, 90, true);
      expect(verdict.label).toContain('达标');
      expect(verdict.tone).toBe('success');
    });

    it('低于目标时的处置口径：先查上游数据质量，不是先调规则', () => {
      expect(MATCH_RATE_BELOW_TARGET_HINT).toContain('上游数据质量');
      expect(MATCH_RATE_BELOW_TARGET_HINT).toContain('而不是先调规则');
    });
  });

  describe('规则口径 / 适用范围 / 动作闸', () => {
    it('口径说明把三种容差分开讲', () => {
      const label = matchRuleSpecLabel({
        matchKeys: ['REFERENCE', 'DATE'],
        minDimensionCount: 2,
        dateWindowDays: 7,
        amountTolerance: 0.5,
        feeTolerance: 2,
        exchangeRateTolerance: 1,
        fuzzyEnabled: true,
        fuzzyReferencePrefixLength: 8,
        fuzzyDateWindowDays: 31,
      });
      expect(label).toContain('订单号 + 日期');
      expect(label).toContain('至少对上 2 个');
      expect(label).toContain('金额容差 0.5000');
      expect(label).toContain('手续费容差 2.0000');
      expect(label).toContain('汇率差容差 1.0000');
      expect(label).toContain('模糊兜底');
      expect(label).toContain('仍需对上 2 个维度');
    });

    it('不开模糊兜底时口径说明里写明「不开」', () => {
      const label = matchRuleSpecLabel({
        matchKeys: ['REFERENCE', 'DATE', 'TYPE'],
        minDimensionCount: 2,
        dateWindowDays: 7,
        amountTolerance: 0,
        feeTolerance: 0,
        exchangeRateTolerance: 0,
        fuzzyEnabled: false,
        fuzzyReferencePrefixLength: 8,
        fuzzyDateWindowDays: 31,
      });
      expect(label).toContain('不开模糊兜底');
    });

    it('适用范围：空 = 全部', () => {
      expect(
        matchRuleScopeLabel({ currency: null, accountId: null, shopId: null }),
      ).toContain('全部币种');
      expect(
        matchRuleScopeLabel({ currency: null, accountId: null, shopId: null }),
      ).toContain('全部账户');
      expect(
        matchRuleScopeLabel({ currency: 'USD', accountId: 'a', shopId: 'b' }),
      ).toContain('币种 USD');
    });

    it('已启用的规则不能再「启用」；不合规的规则给出原因', () => {
      expect(
        matchRuleActivateBlockReason({
          status: 'ACTIVE',
          matchKeys: ['REFERENCE', 'DATE'],
          minDimensionCount: 2,
        }),
      ).toContain('已启用');
      expect(
        matchRuleActivateBlockReason({
          status: 'DRAFT',
          matchKeys: ['REFERENCE'],
          minDimensionCount: 2,
        }),
      ).toContain('匹配键至少两个');
      expect(
        matchRuleActivateBlockReason({
          status: 'DRAFT',
          matchKeys: ['REFERENCE', 'DATE'],
          minDimensionCount: 1,
        }),
      ).toContain('至少对上的维度数至少 2');
      expect(
        matchRuleActivateBlockReason({
          status: 'DRAFT',
          matchKeys: ['REFERENCE', 'DATE'],
          minDimensionCount: 2,
        }),
      ).toBe('');
    });

    it('未匹配原因分布的一行结论', () => {
      const hint = matchRateReasonHint({
        currency: 'USD',
        reasonType: 'FEE_DIFFERENCE',
        lineCount: 3,
        ratio: 0.25,
      });
      expect(hint).toContain('USD');
      expect(hint).toContain('手续费差异');
      expect(hint).toContain('3 条');
      expect(hint).toContain('25.00%');
    });
  });
});
