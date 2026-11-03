import { describe, expect, it } from 'vitest';
import {
  pointsAccountKeyText,
  pointsBalanceText,
  pointsBenefitTypeLabel,
  pointsDirectionLabel,
  pointsEarnSourceLabel,
  pointsEmptyStateText,
  pointsEntitlementNote,
  pointsExpiryText,
  pointsLedgerEmptyStateText,
  pointsLiabilityNote,
  pointsPercentText,
  pointsReasonLabel,
  pointsRedemptionEmptyStateText,
  pointsRedemptionNote,
  pointsRedemptionStatusLabel,
  pointsRuleNote,
  pointsRuleTypeLabel,
  pointsSnapshotValue,
  pointsStatsNote,
  pointsText,
} from './customer-points-model';
import type { CrmPointsLiability } from '../../../../../packages/api-contracts/src/index';

/**
 * 15.6.1.2 积分与权益的**口径层**测试（纯函数，不碰网络）。
 *
 * <p>这些函数是页面与后端之间的**口径翻译层**：页面照着它们渲染，
 * 不各自解释「这个数是什么意思」。所以口径错了，界面就会骗人 ——
 * 比如把「未在财务侧入账」显示成「已入账」、把单积分价值没配时显示成 0、
 * 或者用浮点乘出「减免 7.000000000000001%」。
 *
 * <p>每一条断言背后都是蓝图 15.6.1.2 的硬约束：
 * 积分比率是租户数据、十进制全程不碰浮点、
 * 「不可判定」不是 0、未兑付积分是负债但**不能假装已入账**。
 */
describe('积分与权益 · 口径层', () => {
  describe('流水方向与事由', () => {
    it('三个方向各有中文名', () => {
      expect(pointsDirectionLabel('EARN')).toBe('获取');
      expect(pointsDirectionLabel('SPEND')).toBe('消耗');
      expect(pointsDirectionLabel('EXPIRE')).toBe('过期');
    });

    it('七种事由各有中文名', () => {
      expect(pointsReasonLabel('PURCHASE')).toBe('消费获取');
      expect(pointsReasonLabel('REVIEW')).toBe('评价获取');
      expect(pointsReasonLabel('CHECKIN')).toBe('签到获取');
      expect(pointsReasonLabel('MANUAL')).toBe('人工发放');
      expect(pointsReasonLabel('DEDUCT')).toBe('订单抵扣');
      expect(pointsReasonLabel('REDEEM')).toBe('权益兑换');
      expect(pointsReasonLabel('EXPIRE')).toBe('到期过期');
    });

    it('未知取值原样透出，不猜', () => {
      expect(pointsDirectionLabel('SOMETHING_NEW')).toBe('SOMETHING_NEW');
      expect(pointsDirectionLabel(undefined)).toBe('');
    });
  });

  describe('获取来源与规则类型', () => {
    it('四种获取来源各有中文名', () => {
      expect(pointsEarnSourceLabel('PURCHASE')).toBe('消费获取');
      expect(pointsEarnSourceLabel('REVIEW')).toBe('评价获取');
      expect(pointsEarnSourceLabel('CHECKIN')).toBe('签到获取');
      expect(pointsEarnSourceLabel('MANUAL')).toBe('人工发放');
    });

    it('三种规则类型各有中文名', () => {
      expect(pointsRuleTypeLabel('EARN')).toBe('获取规则');
      expect(pointsRuleTypeLabel('SPEND')).toBe('消耗规则（抵扣）');
      expect(pointsRuleTypeLabel('VALUATION')).toBe('估值规则（单积分价值）');
    });

    it('四种权益形态各有中文名', () => {
      expect(pointsBenefitTypeLabel('DISCOUNT')).toBe('折扣');
      expect(pointsBenefitTypeLabel('FREE_SHIPPING')).toBe('免邮');
      expect(pointsBenefitTypeLabel('PHYSICAL_GIFT')).toBe('实物');
      expect(pointsBenefitTypeLabel('COUPON')).toBe('优惠券');
    });
  });

  describe('十进制：全程文本，全程不碰浮点', () => {
    it('积分数原样显示（后端给的是十进制文本，页面不改写）', () => {
      expect(pointsText('300')).toBe('300');
      expect(pointsText('0.500000')).toBe('0.500000');
      expect(pointsText('300.0000')).toBe('300.0000');
    });

    it('空值显示为「—」，不是 0', () => {
      expect(pointsText(null)).toBe('—');
      expect(pointsText(undefined)).toBe('—');
      expect(pointsText('')).toBe('—');
    });

    it('百分比走字符串移位：0.07 → 7（不是 7.000000000000001）', () => {
      expect(pointsPercentText('0.05')).toBe('5');
      expect(pointsPercentText('0.075')).toBe('7.5');
      expect(pointsPercentText('0.07')).toBe('7');
      expect(pointsPercentText('1.5')).toBe('150');
      expect(pointsPercentText('0')).toBe('0');
    });

    it('百分比空值显示「—」，不显示 0%', () => {
      expect(pointsPercentText(null)).toBe('—');
      expect(pointsPercentText(undefined)).toBe('—');
    });
  });

  describe('有效期：按绝对时刻，永不过期是一等状态', () => {
    it('给了到期时刻就显示绝对时刻', () => {
      expect(pointsExpiryText('2027-11-01T00:00:00Z')).toBe('2027-11-01T00:00:00Z 到期');
    });

    it('null 是「永不过期」，不是「没查到到期时间」', () => {
      expect(pointsExpiryText(null)).toBe('永不过期');
      expect(pointsExpiryText(undefined)).toBe('永不过期');
    });
  });

  describe('账户展示', () => {
    it('有买家名时显示「名字（账户主键）」', () => {
      expect(pointsAccountKeyText({ accountKey: 'AMAZON CUST-A', customerName: '买家甲' })).toBe(
        '买家甲（AMAZON CUST-A）',
      );
    });

    it('没有买家名时只显示账户主键', () => {
      expect(pointsAccountKeyText({ accountKey: 'AMAZON CUST-A' })).toBe('AMAZON CUST-A');
    });

    it('余额摘要把四个口径都摆出来', () => {
      expect(
        pointsBalanceText({
          balance: '100',
          earnedTotal: '150',
          spentTotal: '30',
          expiredTotal: '20',
        }),
      ).toBe('余额 100 · 累计获取 150 · 累计消耗 30 · 累计过期 20');
    });
  });

  describe('权益快照：jsonb 不能直接索引，走安全取值器', () => {
    it('取到值就显示', () => {
      expect(
        pointsSnapshotValue({ benefitValue: '10', benefitCurrency: 'USD' }, 'benefitValue'),
      ).toBe('10');
    });

    it('取不到 / 不是对象都显示「—」', () => {
      expect(pointsSnapshotValue({}, 'benefitValue')).toBe('—');
      expect(pointsSnapshotValue(null, 'benefitValue')).toBe('—');
      expect(pointsSnapshotValue('not-an-object', 'benefitValue')).toBe('—');
    });
  });

  describe('规则口径：积分比率是租户数据', () => {
    it('获取规则把来源、换算、有效期都摆出来', () => {
      const note = pointsRuleNote({
        id: '11111111-1111-1111-1111-111111111111',
        ruleCode: 'EARN-PURCHASE',
        ruleName: '消费获取规则',
        ruleType: 'EARN',
        earnSource: 'PURCHASE',
        pointsPerUnit: '1',
        fixedPoints: null,
        thresholdAmount: null,
        thresholdCurrency: null,
        validMonths: 12,
        maxPerDay: null,
        maxDeductRate: null,
        minDeductPoints: null,
        pointValueRate: null,
        liabilityCurrency: null,
        enabled: true,
        effectiveFrom: '2026-01-01T00:00:00Z',
        effectiveTo: null,
        effectiveNow: true,
        note: '',
        createdAt: '2026-01-01T00:00:00Z',
      });
      expect(note).toContain('消费获取规则');
      expect(note).toContain('每 1 单位 1 分');
      expect(note).toContain('有效期 12 个月');
      expect(note).toContain('生效中');
    });

    it('获取规则不写有效期时显示「永不过期」', () => {
      const note = pointsRuleNote({
        id: '11111111-1111-1111-1111-111111111111',
        ruleCode: 'EARN-CHECKIN',
        ruleName: '签到规则',
        ruleType: 'EARN',
        earnSource: 'CHECKIN',
        pointsPerUnit: null,
        fixedPoints: '5',
        thresholdAmount: null,
        thresholdCurrency: null,
        validMonths: null,
        maxPerDay: null,
        maxDeductRate: null,
        minDeductPoints: null,
        pointValueRate: null,
        liabilityCurrency: null,
        enabled: true,
        effectiveFrom: '2026-01-01T00:00:00Z',
        effectiveTo: null,
        effectiveNow: true,
        note: '',
        createdAt: '2026-01-01T00:00:00Z',
      });
      expect(note).toContain('永不过期');
    });
  });

  describe('🔴 积分负债：如实标注，不假装已入账', () => {
    const liability: CrmPointsLiability = {
      asOf: '2026-11-01T00:00:00Z',
      pointValueRate: '0.01',
      liabilityCurrency: 'USD',
      outstandingPoints: '100',
      liabilityAmount: '1.00',
      accountsWithBalance: 1,
      expiringPoints: '0',
      expiringWithin: '30 天内到期',
      bookedInFinance: false,
      financeNote: '🔴 12 财务侧**未交付**积分负债科目 / 备忘。',
      note: '未兑付积分 100 分。',
    };

    it('未入账时明确写出「未在 12 财务侧入账」', () => {
      const note = pointsLiabilityNote(liability);
      expect(note).toContain('未在 12 财务侧入账');
      expect(note).toContain('未兑付积分 100 分');
      expect(note).toContain('1.00 USD');
    });

    it('单积分价值没配时，负债金额显示「—」，不是 0', () => {
      const note = pointsLiabilityNote({
        ...liability,
        pointValueRate: null,
        liabilityCurrency: null,
        liabilityAmount: null,
      });
      expect(note).toContain('负债 —');
      expect(note).not.toContain('负债 0 ');
    });

    it('空汇总不报错', () => {
      expect(pointsLiabilityNote(undefined)).toBe('');
    });
  });

  describe('空态：区分「筛选无结果」与「还没发过积分」', () => {
    it('筛选无结果时告诉用户放宽条件 —— 不代表没有积分账户', () => {
      const empty = pointsEmptyStateText(true);
      expect(empty.title).toBe('没有命中任何积分账户');
      expect(empty.description).toContain('放宽筛选条件');
    });

    it('没筛选时告诉用户先发一次积分', () => {
      const empty = pointsEmptyStateText(false);
      expect(empty.title).toBe('还没有积分账户');
      expect(empty.description).toContain('第一次发放积分');
    });

    it('流水空态区分筛选与还没有流水', () => {
      expect(pointsLedgerEmptyStateText(true).title).toBe('没有命中任何积分流水');
      expect(pointsLedgerEmptyStateText(false).title).toBe('还没有积分流水');
    });

    it('核销记录空态提示先兑换', () => {
      expect(pointsRedemptionEmptyStateText(false).description).toContain('用积分兑换');
      expect(pointsRedemptionEmptyStateText(true).title).toBe('没有命中任何权益核销记录');
    });
  });

  describe('其它展示口径', () => {
    it('统计说明带上未兑付积分与到期批次', () => {
      const note = pointsStatsNote({
        totalAccounts: 2,
        accountsWithBalance: 1,
        outstandingPoints: '100',
        expiringLotCount: 1,
        expiringPoints: '50',
        ledgerCount: 3,
        note: '未兑付积分是负债。',
      });
      expect(note).toContain('账户 2 个');
      expect(note).toContain('未兑付积分 100 分');
      expect(note).toContain('30 天内到期');
    });

    it('权益说明带兑换所需积分与形态', () => {
      const note = pointsEntitlementNote({
        id: '11111111-1111-1111-1111-111111111111',
        entitlementCode: 'GIFT-10',
        entitlementName: '10 元券',
        pointCost: '30',
        benefitType: 'COUPON',
        benefitTypeLabel: '优惠券',
        benefitValue: '10',
        benefitCurrency: 'USD',
        stock: 5,
        perMemberLimit: 2,
        enabled: true,
        effectiveFrom: '2026-01-01T00:00:00Z',
        effectiveTo: null,
        effectiveNow: true,
        note: '',
        createdAt: '2026-01-01T00:00:00Z',
      });
      expect(note).toContain('30 分');
      expect(note).toContain('优惠券');
      expect(note).toContain('库存 5');
      expect(note).toContain('每人限兑 2 份');
    });

    it('核销记录说明带状态与核销号', () => {
      const note = pointsRedemptionNote({
        id: '11111111-1111-1111-1111-111111111111',
        redemptionNo: 'PR-20261101-000001',
        accountKey: 'AMAZON CUST-A',
        memberKey: 'AMAZON CUST-A',
        entitlementCode: 'GIFT-10',
        entitlementName: '10 元券',
        pointCost: '30',
        benefitSnapshot: {},
        status: 'REDEEMED',
        statusLabel: '已兑换待核销',
        redeemedAt: '2026-11-01T00:00:00Z',
        usedAt: null,
        orderRef: '',
        note: '',
      });
      expect(note).toContain('已兑换待核销');
      expect(note).toContain('PR-20261101-000001');
      expect(note).toContain('消耗 30 分');
    });

    it('核销状态两态各有中文名', () => {
      expect(pointsRedemptionStatusLabel('REDEEMED')).toBe('已兑换待核销');
      expect(pointsRedemptionStatusLabel('USED')).toBe('已核销');
    });
  });
});
