import { describe, expect, it } from 'vitest';
import type {
  CrmCustomerListAmount,
  CrmCustomerListRow,
} from '../../../../../packages/api-contracts/src/index';
import {
  customerAmountText,
  customerContactIsPlain,
  customerContactText,
  customerEmptyStateText,
  customerOptOutStateLabel,
  customerPurchaseWindowText,
  customerRefundRateText,
  customerRiskFlagLabel,
  customerValueTierLabel,
} from './customer-list-model';

/**
 * 15.3.1.1 客户列表前端模型的纯规则测试。
 *
 * <p>本文件把**页面最容易做错的几件事**钉死：
 *
 * <ol>
 *   <li><b>「不可判定」与「0%」是两句话</b>：分母为 0 时画成「退款率 0%」，
 *       会让「这个人没被判过」看起来像「这个人没问题」。</li>
 *   <li><b>「样本不足」与「无风险」是两句话</b>：把 INSUFFICIENT_SAMPLE 画成
 *       「无风险标记」，那是在替后端下结论。</li>
 *   <li><b>金额按币种分行</b>：USD 与 EUR 加起来不是任何一个金额，
 *       页面把两个币种合成一个数就是造数据。</li>
 *   <li><b>空态分两种</b>：「筛选无结果」与「还没有客户档案」要的行动完全不同。</li>
 * </ol>
 */
describe('15.3.1.1 客户列表前端模型', () => {
  describe('退款率的显示', () => {
    it('分母为 0 时是「不可判定」，**不是** 0%', () => {
      expect(customerRefundRateText(null)).toBe('不可判定');
      expect(customerRefundRateText(undefined)).toBe('不可判定');
      expect(customerRefundRateText('')).toBe('不可判定');
      expect(customerRefundRateText(null)).not.toContain('0');
    });

    it('有值时原样显示（前端不做任何算术）', () => {
      expect(customerRefundRateText('0.333333')).toBe('0.333333');
      expect(customerRefundRateText(0)).toBe('0');
      // 100% 是后端算好的「10 单退 10 单」；页面不把它折成百分号以外的任何东西。
      expect(customerRefundRateText('1.000000')).toBe('1.000000');
    });
  });

  describe('风险标记的可读名', () => {
    it('「样本不足」与「无风险」是**两句话**，不能混', () => {
      expect(customerRiskFlagLabel('INSUFFICIENT_SAMPLE')).toBe('样本不足，不可判定');
      expect(customerRiskFlagLabel('NONE')).toBe('无风险标记');
      expect(customerRiskFlagLabel('INSUFFICIENT_SAMPLE')).toContain('不可判定');
      expect(customerRiskFlagLabel('INSUFFICIENT_SAMPLE')).not.toBe(customerRiskFlagLabel('NONE'));
    });

    it('其余三种风险标记各有各的话', () => {
      expect(customerRiskFlagLabel('HIGH_RETURN_RATE')).toContain('退款率');
      expect(customerRiskFlagLabel('OPT_OUT')).toContain('退订');
      expect(customerRiskFlagLabel('HIGH_RETURN_RATE_AND_OPT_OUT')).toContain('退订');
    });
  });

  describe('价值分层的可读名', () => {
    it('四个分层各有各的话，未知码原样返回', () => {
      expect(customerValueTierLabel('VIP')).toBe('高价值');
      expect(customerValueTierLabel('HIGH')).toBe('较高价值');
      expect(customerValueTierLabel('NORMAL')).toBe('常规');
      expect(customerValueTierLabel('LOW')).toBe('无有效消费');
      expect(customerValueTierLabel('WHAT')).toBe('WHAT');
    });
  });

  describe('金额按币种分行', () => {
    const amounts: CrmCustomerListAmount[] = [
      { currency: 'USD', totalAmount: 315.6, orderCount: 5, avgOrderAmount: 63.12 },
      { currency: 'EUR', totalAmount: 12, orderCount: 1, avgOrderAmount: 12 },
    ];

    it('两个币种就是两行，**不加成一个数**', () => {
      const text = customerAmountText(amounts);
      expect(text).toContain('USD 315.6');
      expect(text).toContain('EUR 12');
      expect(text).toContain('63.12');
      // 315.60 + 12.00 = 327.60 —— 这个数**不该**出现在页面上。
      expect(text).not.toContain('327.6');
    });

    it('客单价在订单数为 0 时是「—」（不可判定），不是 0', () => {
      const text = customerAmountText([
        { currency: 'USD', totalAmount: 0, orderCount: 0, avgOrderAmount: null },
      ]);
      expect(text).toContain('客单价 —');
    });

    it('没有金额行时明说「无消费记录」', () => {
      expect(customerAmountText([])).toBe('无消费记录');
      expect(customerAmountText(undefined)).toBe('无消费记录');
    });
  });

  describe('联系方式', () => {
    const row = {
      contactEmail: 'a***@***.com',
      contactPhone: '138****1234',
      contactMasked: true,
    } as unknown as CrmCustomerListRow;

    it('默认展示的是**脱敏**形态', () => {
      expect(customerContactText(row)).toContain('a***@***.com');
      expect(customerContactText(row)).toContain('138****1234');
      // 脱敏的行**不是**明文 ⇒ 不标「明文」。
      expect(customerContactIsPlain(row)).toBe(false);
    });

    it('授权之后才标「明文」', () => {
      const plain = { ...row, contactMasked: false } as unknown as CrmCustomerListRow;
      expect(customerContactIsPlain(plain)).toBe(true);
    });
  });

  describe('首末购买时间', () => {
    it('有时刻时显示日期区间，缺时刻时写「—」', () => {
      const row = {
        firstOrderAt: '2026-01-05T08:00:00+08:00',
        lastOrderAt: '2026-10-10T08:00:00+08:00',
      } as unknown as CrmCustomerListRow;
      expect(customerPurchaseWindowText(row)).toBe('2026-01-05 ~ 2026-10-10');
      const empty = {} as unknown as CrmCustomerListRow;
      expect(customerPurchaseWindowText(empty)).toBe('— ~ —');
    });
  });

  describe('退订状态', () => {
    it('三种状态各有各的话', () => {
      expect(customerOptOutStateLabel('NONE')).toBe('无退订记录');
      expect(customerOptOutStateLabel('ACTIVE')).toBe('退订生效中');
      expect(customerOptOutStateLabel('REVOKED')).toBe('退订已撤销');
    });
  });

  describe('空态要分两种', () => {
    it('筛选无结果 ≠ 还没有客户档案', () => {
      const filtered = customerEmptyStateText(true, true);
      const empty = customerEmptyStateText(false, false);
      expect(filtered.title).not.toBe(empty.title);
      expect(filtered.description).toContain('筛选');
      expect(empty.description).toContain('刷新');
    });

    it('刷新过但仍然为空时，说的不是「还没刷新过」', () => {
      const afterRun = customerEmptyStateText(false, true);
      expect(afterRun.description).not.toContain('点「刷新客户档案」');
    });
  });
});
