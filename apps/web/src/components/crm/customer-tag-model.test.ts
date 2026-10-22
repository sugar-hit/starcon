import { describe, expect, it } from 'vitest';
import {
  customerTagAmounts,
  customerTagCategoryLabel,
  customerTagEvidence,
  customerTagEvidenceSummary,
  customerTagMetricLabel,
  customerTagOutcomeLabel,
  customerTagShareText,
  customerTagSourceLabel,
  customerTagStateLabel,
} from './customer-tag-model';

/**
 * 15.3.2.1 客户标签前端模型的纯规则测试。
 *
 * <p>本文件把**页面最容易做错的几件事**钉死：
 *
 * <ol>
 *   <li><b>「不可判定」与「不命中」必须是两句话</b>：把「样本不足」画成「判定为不命中」，
 *       会让「这个人没问题」这个结论看起来成立 —— 而它其实没被判过。</li>
 *   <li><b>占比不在前端算</b>：百分数由后端算好（{@code sharePercent}），
 *       前端只原样拼一个 {@code %}；分母为 0 时显示「不计算」而不是 0%。</li>
 *   <li><b>来源必须分得清</b>：人工打标与规则打标是两条不同的记录，标签上要看得出来。</li>
 *   <li><b>判定依据要能摘要出订单号 / 售后单号</b>：没有依据的标签没法复核，也没法纠正。</li>
 * </ol>
 */
describe('15.3.2.1 客户标签前端模型', () => {
  describe('判定结论的可读名', () => {
    it('「不可判定」与「不命中」是**两句话**，不能混', () => {
      expect(customerTagOutcomeLabel('INDETERMINATE')).toBe('不可判定（样本不足）');
      expect(customerTagOutcomeLabel('NOT_MATCHED')).toBe('判定为不命中');
      // 关键：不可判定那句话里必须出现「样本不足」——
      //   运营要能一眼看出「这个人没被判过」，而不是「判了，不是」。
      expect(customerTagOutcomeLabel('INDETERMINATE')).toContain('样本不足');
      expect(customerTagOutcomeLabel('NOT_MATCHED')).not.toContain('样本');
    });

    it('幂等与人工优先两种结论各有各的话', () => {
      expect(customerTagOutcomeLabel('ALREADY_TAGGED')).toContain('幂等');
      expect(customerTagOutcomeLabel('SKIPPED_REVOKED')).toContain('人工已撤销');
      expect(customerTagOutcomeLabel('REVOKED')).toContain('撤销');
    });
  });

  describe('占比不在前端算', () => {
    it('百分数原样显示，分母为 0 时报「不计算」而不是 0%', () => {
      expect(customerTagShareText(33.33)).toBe('33.33%');
      expect(customerTagShareText(null)).toBe('不计算');
      expect(customerTagShareText(undefined)).toBe('不计算');
      // 「不计算」里绝不能出现 0% —— 那会被读成「占比为零」。
      expect(customerTagShareText(null)).not.toContain('0%');
    });
  });

  describe('来源必须分得清', () => {
    it('人工打标与规则打标有不同的名字', () => {
      expect(customerTagSourceLabel('MANUAL')).toBe('人工打标');
      expect(customerTagSourceLabel('RULE')).toBe('规则打标');
      expect(customerTagSourceLabel('MANUAL')).not.toBe(customerTagSourceLabel('RULE'));
    });
  });

  describe('分类 / 口径 / 状态的可读名', () => {
    it('六个分类都有可读名', () => {
      expect(customerTagCategoryLabel('VALUE')).toBe('价值分层');
      expect(customerTagCategoryLabel('BEHAVIOR')).toBe('行为');
      expect(customerTagCategoryLabel('RISK')).toBe('风险与售后');
      expect(customerTagCategoryLabel('SOURCE')).toBe('渠道来源');
      expect(customerTagCategoryLabel('CUSTOM')).toBe('自定义');
    });

    it('五种判定口径都有可读名', () => {
      expect(customerTagMetricLabel('ORDER_COUNT')).toContain('订单数');
      expect(customerTagMetricLabel('REPEAT_PURCHASE')).toContain('复购');
      expect(customerTagMetricLabel('HIGH_VALUE')).toContain('累计消费');
      expect(customerTagMetricLabel('RETURN_RATE')).toContain('基线');
      expect(customerTagMetricLabel('CHANNEL_SOURCE')).toContain('渠道来源');
    });

    it('标签记录状态分生效与已撤销', () => {
      expect(customerTagStateLabel('ACTIVE')).toBe('生效');
      expect(customerTagStateLabel('REVOKED')).toBe('已撤销');
    });
  });

  describe('判定依据快照', () => {
    it('摘要里要能看到依据哪几笔订单、哪几张售后单', () => {
      const lines = customerTagEvidenceSummary({
        windowOrderCount: 3,
        orderNos: ['SO-1', 'SO-2', 'SO-3'],
        returnCaseNos: ['AS-1'],
        returnRate: 0.333333,
        baseline: 0.1,
      });
      expect(lines.join('\n')).toContain('SO-1');
      expect(lines.join('\n')).toContain('AS-1');
      expect(lines.join('\n')).toContain('窗口内订单 3 单');
    });

    it('空快照不该炸，也不该编出依据', () => {
      expect(customerTagEvidenceSummary({})).toEqual([]);
      expect(customerTagEvidence(null)).toEqual({});
      expect(customerTagAmounts(undefined)).toEqual({});
    });
  });
});
