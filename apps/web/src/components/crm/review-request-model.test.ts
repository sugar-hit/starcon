import { describe, expect, it } from 'vitest';
import type { CrmReviewFrequencyPolicy } from '../../../../../packages/api-contracts/src/index';
import {
  ratingDistributionText,
  recoveryRateText,
  rejectCodeLabel,
  reviewRequestQuery,
  reviewStatusLabel,
  ruleAgainstPolicy,
  sendOutcomeLabel,
} from './review-request-model';

/**
 * 15.2.2.1 Request a Review（索评）前端模型的纯规则测试。
 *
 * <p>本文件把**页面最容易做错的三件事**钉死：
 *
 * <ol>
 *   <li><b>「被拒绝」是一条真实的索评请求</b>，不是「没发出去」——
 *       状态与原因码的可读名必须分开，否则界面上会把两者混成一句「操作失败」。</li>
 *   <li><b>回收率的分母是「已发出的索评」</b>：分母为 0 时报「不计算」而不是 0% ——
 *       0% 会被读成「发出去了但一个评价都没回来」，那是两件完全不同的事。</li>
 *   <li><b>规则只能比租户硬上限更严</b>：前端当场把「填了也发不出去」的输入标出来
 *       （真正拦在后端与 DB 触发器，这里只是提前告诉运营）。</li>
 * </ol>
 */
describe('15.2.2.1 索评前端模型', () => {
  const policy: CrmReviewFrequencyPolicy = {
    id: 'policy-1',
    maxPerBuyerPerWindow: 3,
    windowDays: 30,
    minCooldownHours: 72,
    maxPerOrder: 1,
    minDelayHours: 24,
    maxBatchSize: 200,
    updatedAt: '2026-06-01T00:00:00Z',
  };

  describe('状态可读名', () => {
    it('「被频次规则拒绝」是独立状态，不与「已取消」混为一谈', () => {
      expect(reviewStatusLabel('SCHEDULED')).toBe('已排期');
      expect(reviewStatusLabel('SENT')).toBe('已发出');
      expect(reviewStatusLabel('CANCELLED')).toBe('已取消');
      expect(reviewStatusLabel('REJECTED')).toBe('被频次规则拒绝');
      // 三个状态三种说法 —— 界面上不能把「被挡」画成「没发」。
      expect(reviewStatusLabel('REJECTED')).not.toBe(reviewStatusLabel('CANCELLED'));
    });

    it('触达结果与拒绝原因码各有各的可读名', () => {
      expect(sendOutcomeLabel('QUEUED')).toBe('已登记待发');
      expect(sendOutcomeLabel('SENT')).toBe('已发出');
      expect(sendOutcomeLabel('FAILED')).toBe('失败');
      expect(rejectCodeLabel('PER_ORDER_LIMIT')).toBe('每订单上限');
      expect(rejectCodeLabel('PER_BUYER_LIMIT')).toBe('每买家上限');
      expect(rejectCodeLabel('COOLDOWN_ACTIVE')).toBe('冷却窗口未到');
    });

    it('未知取值原样透出，不编一个像模像样的说法', () => {
      expect(reviewStatusLabel('WHATEVER')).toBe('WHATEVER');
      expect(rejectCodeLabel('WHATEVER')).toBe('WHATEVER');
    });
  });

  describe('回收率展示', () => {
    it('分母为 0 时报「不计算」而不是 0%', () => {
      expect(recoveryRateText(null)).toContain('不计算');
      expect(recoveryRateText(null)).not.toContain('0.0%');
    });

    it('真实数值按一位小数展示', () => {
      expect(recoveryRateText(0)).toBe('0.0%');
      expect(recoveryRateText(0.25)).toBe('25.0%');
      expect(recoveryRateText(1)).toBe('100.0%');
    });

    it('星级分布逐档列出，空桶显示 0 而不是跳过', () => {
      expect(
        ratingDistributionText([
          { rating: 1, count: 0 },
          { rating: 2, count: 0 },
          { rating: 3, count: 1 },
          { rating: 4, count: 0 },
          { rating: 5, count: 2 },
        ]),
      ).toBe('1★×0 · 2★×0 · 3★×1 · 4★×0 · 5★×2');
      expect(ratingDistributionText([])).toBe('暂无回收');
      expect(ratingDistributionText(undefined)).toBe('暂无回收');
    });
  });

  describe('规则与租户硬上限', () => {
    const rule = { delayHours: 48, cooldownHours: 96, maxPerBuyer: 3, maxPerOrder: 1 };

    it('不比硬上限更松时没有意见', () => {
      expect(ruleAgainstPolicy(rule, policy)).toBeNull();
    });

    it('比硬上限更松时逐条点名是哪一条更松', () => {
      expect(
        ruleAgainstPolicy({ ...rule, maxPerBuyer: 9 }, policy),
      ).toContain('每买家上限');
      expect(
        ruleAgainstPolicy({ ...rule, maxPerOrder: 3 }, policy),
      ).toContain('每订单上限');
      expect(ruleAgainstPolicy({ ...rule, cooldownHours: 1 }, policy)).toContain('冷却窗口');
      expect(ruleAgainstPolicy({ ...rule, delayHours: 1 }, policy)).toContain('时点');
    });

    it('没配置硬上限时说清「没有上限就不许索评」', () => {
      const problem = ruleAgainstPolicy(rule, null);
      expect(problem).not.toBeNull();
      expect(problem).toContain('频次策略');
    });
  });

  describe('列表筛选', () => {
    it('只把填了的条件放进查询串', () => {
      expect(reviewRequestQuery({})).toBe('');
      expect(reviewRequestQuery({ status: 'REJECTED' })).toBe('?status=REJECTED');
      expect(reviewRequestQuery({ shopId: 'shop-1', status: 'SENT' })).toBe(
        '?shopId=shop-1&status=SENT',
      );
    });

    it('条件里的特殊字符被转义，不会拼出第二个参数', () => {
      expect(reviewRequestQuery({ buyerRef: 'a b&c=d' })).toBe('?buyerRef=a%20b%26c%3Dd');
    });
  });
});
