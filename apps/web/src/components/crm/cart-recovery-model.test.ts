import { describe, expect, it } from 'vitest';
import {
  cartAbandonmentQuery,
  cartAbandonmentStatusLabel,
  cartAmountText,
  cartChannelLabel,
  cartMessageQuery,
  cartMessageStatusLabel,
  cartPlatformTypeLabel,
  cartRecoveryRateText,
  cartRecoveryStateLabel,
  cartStageLabel,
  cartTouchStatusLabel,
  cartTransportLabel,
  cartUnsubscribeQuery,
} from './cart-recovery-model';

/**
 * 15.6.2.2 购物车与弃单挽回前端模型的纯规则测试。
 *
 * <p>本文件把**页面最容易做错的三件事**钉死：
 *
 * <ol>
 *   <li><b>「挽回到哪一步」必须分四态</b>：还没触达 / 已登记待发 / 已发出 / 触达失败。
 *       把后两者混成一句「操作失败」会让人重复触达 —— 而重复打扰买家的代价正是退订与投诉。</li>
 *   <li><b>「已审核」与「已启用」是两件事</b>：只有「已启用」的话术能被触达流程选用。
 *       混成一个词会让人以为「审核过就能发」。</li>
 *   <li><b>挽回率分母为 0 时显示「—」而不是 0%</b>：「没有弃单」和「弃单都没挽回回来」
 *       是两件完全不同的事，显示 0% 会把前者说成后者。</li>
 * </ol>
 */
describe('15.6.2.2 购物车与弃单挽回前端模型', () => {
  describe('「挽回到哪一步」的四态', () => {
    it('还没触达 / 已触达 / 已挽回成交 —— 三句话互不相同', () => {
      expect(cartRecoveryStateLabel({ status: 'ABANDONED', touchCount: 0, recoveredOrderNo: '' })).toBe(
        '还没触达',
      );
      expect(
        cartRecoveryStateLabel({ status: 'ABANDONED', touchCount: 2, recoveredOrderNo: '' }),
      ).toContain('已触达');
      expect(
        cartRecoveryStateLabel({ status: 'RECOVERED', touchCount: 1, recoveredOrderNo: 'SO-1' }),
      ).toContain('已挽回成交');
      expect(
        cartRecoveryStateLabel({ status: 'RECOVERED', touchCount: 1, recoveredOrderNo: 'SO-1' }),
      ).toContain('SO-1');
    });

    it('已挽回成交优先于「还没触达」', () => {
      // 成交是事实，不该被「触达数 = 0」盖掉。
      expect(
        cartRecoveryStateLabel({ status: 'RECOVERED', touchCount: 0, recoveredOrderNo: 'SO-9' }),
      ).toContain('已挽回成交');
    });
  });

  describe('状态可读名', () => {
    it('话术的「已审核」与「已启用」分开说', () => {
      expect(cartMessageStatusLabel('DRAFT')).toBe('草稿（未审核）');
      expect(cartMessageStatusLabel('APPROVED')).toBe('已审核（未启用）');
      expect(cartMessageStatusLabel('ENABLED')).toBe('已启用（可被选用）');
      expect(cartMessageStatusLabel('DISABLED')).toBe('已停用');
      // 关键：「已审核」**不能**读成可以选用。
      expect(cartMessageStatusLabel('APPROVED')).not.toContain('可被选用');
    });

    it('触达记录状态不读成「已发送」', () => {
      expect(cartTouchStatusLabel('REGISTERED')).toBe('已登记待发');
      expect(cartTouchStatusLabel('SENT')).toContain('人工交接');
      expect(cartTouchStatusLabel('FAILED')).toBe('触达失败');
      // 「已登记待发」**不能**读成「已发出」—— 15.5.2.1 触达未交付。
      expect(cartTouchStatusLabel('REGISTERED')).not.toContain('已发出');
    });

    it('平台类型、阶段、弃单状态、通道与运输方式各有各的可读名', () => {
      expect(cartPlatformTypeLabel('DTC')).toBe('独立站');
      expect(cartPlatformTypeLabel('MARKETPLACE')).toBe('平台店铺');
      expect(cartPlatformTypeLabel('UNDECLARED')).toBe('未声明');
      expect(cartStageLabel('CART')).toBe('只加购');
      expect(cartStageLabel('CHECKOUT')).toBe('进了结账');
      expect(cartAbandonmentStatusLabel('ABANDONED')).toBe('待挽回');
      expect(cartAbandonmentStatusLabel('RECOVERED')).toBe('已挽回成交');
      expect(cartAbandonmentStatusLabel('EXPIRED')).toBe('已过期（未挽回）');
      expect(cartChannelLabel('EMAIL')).toBe('邮件');
      expect(cartChannelLabel('SMS')).toBe('短信');
      expect(cartChannelLabel('IN_APP')).toBe('站内信');
      expect(cartTransportLabel('MANUAL_HANDOFF')).toBe('人工交接');
    });

    it('未知取值原样透出，不编一个像模像样的说法', () => {
      expect(cartMessageStatusLabel('WHATEVER')).toBe('WHATEVER');
      expect(cartTouchStatusLabel('WHATEVER')).toBe('WHATEVER');
      expect(cartPlatformTypeLabel('WHATEVER')).toBe('WHATEVER');
      expect(cartChannelLabel('WHATEVER')).toBe('WHATEVER');
    });
  });

  describe('挽回率与金额', () => {
    it('分母为 0 时显示「—」而不是 0%', () => {
      expect(cartRecoveryRateText(null)).toBe('—');
      expect(cartRecoveryRateText(undefined)).toBe('—');
      // 「没有弃单」不等于「弃单都没挽回回来」—— 0% 会把前者说成后者。
      expect(cartRecoveryRateText(null)).not.toContain('0');
    });

    it('有数据时原样展示服务端算出来的比率', () => {
      expect(cartRecoveryRateText(33.33)).toBe('33.33%');
      expect(cartRecoveryRateText(0)).toBe('0%');
      expect(cartRecoveryRateText(100)).toBe('100%');
    });

    it('金额只做格式化，不做前端算术', () => {
      expect(cartAmountText(129.9, 'USD')).toBe('USD 129.9');
      expect(cartAmountText(129.9)).toBe('129.9');
      expect(cartAmountText(null)).toBe('—');
      expect(cartAmountText(undefined)).toBe('—');
    });
  });

  describe('查询串拼装', () => {
    it('筛选条件原样透传，空值不进串', () => {
      const query = cartAbandonmentQuery({
        shopId: 'shop-1',
        status: 'ABANDONED',
        stage: 'CART',
        buyerRef: 'buyer 1',
        cartRef: 'cart 1',
        keyword: '雨伞',
        page: 2,
        size: 20,
      });
      expect(query).toContain('shopId=shop-1');
      expect(query).toContain('status=ABANDONED');
      expect(query).toContain('stage=CART');
      expect(query).toContain('buyerRef=buyer%201');
      expect(query).toContain('cartRef=cart%201');
      expect(query).toContain('keyword=%E9%9B%A8%E4%BC%9E');
      expect(query).toContain('page=2');
      expect(query).toContain('size=20');
    });

    it('没有筛选条件时不产生问号', () => {
      expect(cartAbandonmentQuery()).toBe('');
      expect(cartAbandonmentQuery({})).toBe('');
      expect(cartMessageQuery()).toBe('');
      expect(cartUnsubscribeQuery()).toBe('');
    });

    it('话术与退订名单的查询串带上各自的筛选项', () => {
      const messages = cartMessageQuery({ status: 'ENABLED', channel: 'EMAIL', keyword: 'CART' });
      expect(messages).toContain('status=ENABLED');
      expect(messages).toContain('channel=EMAIL');
      expect(messages).toContain('keyword=CART');

      const unsubscribes = cartUnsubscribeQuery({
        contactType: 'EMAIL',
        contactValue: 'a@example.com',
      });
      expect(unsubscribes).toContain('contactType=EMAIL');
      expect(unsubscribes).toContain('contactValue=a%40example.com');
    });
  });
});
