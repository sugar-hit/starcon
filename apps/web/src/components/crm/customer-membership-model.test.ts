import { describe, expect, it } from 'vitest';
import {
  membershipBenefitConfigNote,
  membershipBenefitText,
  membershipChangeKindLabel,
  membershipEmptyStateText,
  membershipLevelChangeEmptyStateText,
  membershipLevelLabel,
  membershipLevelRuleNote,
  membershipMemberKeyText,
  membershipMonetaryText,
  membershipOriginLabel,
  membershipPercentText,
  membershipRepurchaseText,
  membershipStatsNote,
  membershipThresholdsText,
} from './customer-membership-model';

/**
 * 15.6.1.1 会员与等级的**口径层**测试（纯函数，不碰网络）。
 *
 * <p>这些函数是页面与后端之间的**口径翻译层**：页面照着它们渲染，
 * 不各自解释「这个数是什么意思」。所以口径错了，界面就会骗人 ——
 * 比如把「未达任何等级」画成「普通会员」、把金额跨币种加总、
 * 或者把折扣率用浮点乘出「减免 7.000000000000001%」。
 *
 * <p>每一条断言背后都是蓝图 15.6.1.1 的硬约束：
 * 等级只能由真实消费数据算出、金额不跨币种相加、
 * 「不可判定」不是 0、十进制全程不碰浮点。
 */
describe('会员与等级 · 口径层', () => {
  describe('等级来源：页面必须把「规则算出来的」摆在脸上', () => {
    it('RULE 显示为「规则算出来的（真实消费数据驱动）」', () => {
      expect(membershipOriginLabel('RULE')).toBe('规则算出来的（真实消费数据驱动）');
    });

    it('MANUAL_OVERRIDE 显式标出「本轮未交付」—— 页面没有手工挂等级的入口', () => {
      expect(membershipOriginLabel('MANUAL_OVERRIDE')).toContain('本轮未交付');
    });

    it('未知来源原样透出，不猜', () => {
      expect(membershipOriginLabel('SOMETHING_NEW')).toBe('SOMETHING_NEW');
      expect(membershipOriginLabel(undefined)).toBe('');
    });
  });

  describe('「未达任何等级」是一等状态', () => {
    it('NONE 显示为「未达任何等级」，不是「普通会员」', () => {
      expect(membershipLevelLabel('NONE')).toBe('未达任何等级');
    });

    it('其它等级代码原样显示（等级名由后端给，前端不编）', () => {
      expect(membershipLevelLabel('GOLD')).toBe('GOLD');
      expect(membershipLevelLabel(undefined)).toBe('');
    });
  });

  describe('等级变更性质', () => {
    it('三种变更各有中文名', () => {
      expect(membershipChangeKindLabel('REGISTERED')).toBe('首次建档定级');
      expect(membershipChangeKindLabel('UPGRADE')).toBe('升级');
      expect(membershipChangeKindLabel('DOWNGRADE')).toBe('降级');
    });
  });

  describe('金额：带币种、分币种、不跨币种相加', () => {
    it('金额带币种显示', () => {
      expect(membershipMonetaryText('300.00', 'EUR')).toBe('EUR 300.00');
    });

    it('没有币种时只显示数值，不编一个币种出来', () => {
      expect(membershipMonetaryText('300.00', null)).toBe('300.00');
    });

    it('空值显示「—」，不显示 0', () => {
      expect(membershipMonetaryText(null, 'USD')).toBe('—');
      expect(membershipMonetaryText(undefined, 'USD')).toBe('—');
      expect(membershipMonetaryText('', 'USD')).toBe('—');
    });
  });

  describe('复购率：「不可判定」不是 0', () => {
    it('null 显示「不可判定」，绝不显示 0%', () => {
      expect(membershipRepurchaseText(null)).toContain('不可判定');
      expect(membershipRepurchaseText(undefined)).toContain('不可判定');
      expect(membershipRepurchaseText('')).toContain('不可判定');
    });

    it('有值时原样显示十进制文本（前端不做任何算术）', () => {
      expect(membershipRepurchaseText('0.750000')).toBe('0.750000');
    });
  });

  describe('🔴 比率全程不碰浮点：百分比靠字符串移位', () => {
    it('0.05 → 5%（不是 5.000000000000001%）', () => {
      expect(membershipPercentText('0.05')).toBe('5');
      expect(membershipPercentText('0.05')).not.toContain('0000000000000');
    });

    it('0.07 → 7%（浮点乘法会得到 7.000000000000001）', () => {
      expect(membershipPercentText('0.07')).toBe('7');
    });

    it('0.075 → 7.5%、0.123456 → 12.3456%', () => {
      expect(membershipPercentText('0.075')).toBe('7.5');
      expect(membershipPercentText('0.123456')).toBe('12.3456');
    });

    it('1.5 → 150%（移位而不是乘）', () => {
      expect(membershipPercentText('1.5')).toBe('150');
    });

    it('0 → 0%、空值 → 「—」', () => {
      expect(membershipPercentText('0')).toBe('0');
      expect(membershipPercentText(null)).toBe('—');
      expect(membershipPercentText('')).toBe('—');
    });

    it('权益摘要用移位后的百分比', () => {
      expect(membershipBenefitText({ discountRate: '0.05', freeShipping: true })).toBe(
        '减免 5% · 免邮',
      );
      expect(membershipBenefitText({ discountRate: '0.08', priorityShipping: true })).toBe(
        '减免 8% · 优先发货',
      );
    });

    it('没有权益时显示「无权益」', () => {
      expect(membershipBenefitText({ discountRate: null })).toBe('无权益');
      expect(membershipBenefitText({})).toBe('无权益');
    });
  });

  describe('门槛口径：三条门槛一起显示', () => {
    it('累计消费带币种，订单数与复购率一起出现', () => {
      const text = membershipThresholdsText({
        minTotalSpend: '1.00',
        minTotalSpendCurrency: 'USD',
        minOrderCount: 4,
        minRepurchaseRate: '0.5',
      });
      expect(text).toContain('累计消费 ≥ 1.00 USD');
      expect(text).toContain('订单数 ≥ 4');
      expect(text).toContain('复购率 ≥ 0.5');
    });

    it('一条门槛都没配时如实说「无门槛」', () => {
      expect(membershipThresholdsText({})).toContain('无门槛');
    });
  });

  describe('会员主键：已合并的要说清合并了几个来源客户行', () => {
    it('已合并：逻辑客户号 + 合并来源数', () => {
      expect(
        membershipMemberKeyText({
          memberKey: 'LC-20261102-000001',
          logicalRef: 'LC-20261102-000001',
          memberCount: 2,
        }),
      ).toBe('LC-20261102-000001（合并了 2 个来源客户行）');
    });

    it('未合并：原样显示来源店铺:客户标识', () => {
      expect(membershipMemberKeyText({ memberKey: 'AMAZON abc-123' })).toBe('AMAZON abc-123');
    });

    it('没有会员主键时显示「—」', () => {
      expect(membershipMemberKeyText({})).toBe('—');
    });
  });

  describe('空态：区分「筛选无结果」与「还没有算过」', () => {
    it('筛选无结果 ⇒ 提示放宽筛选', () => {
      const text = membershipEmptyStateText(true, true);
      expect(text.title).toBe('没有命中任何会员');
      expect(text.description).toContain('放宽筛选条件');
    });

    it('还没算过 ⇒ 提示先重算一次', () => {
      const text = membershipEmptyStateText(false, false);
      expect(text.title).toBe('还没有算过会员等级');
      expect(text.description).toContain('重算');
    });

    it('算过但没产出 ⇒ 说清「上一次重算没有产出会员档案」', () => {
      const text = membershipEmptyStateText(false, true);
      expect(text.description).toContain('没有产出会员档案');
    });

    it('等级变更的空态区分「筛选无结果」与「还没有变更记录」', () => {
      expect(membershipLevelChangeEmptyStateText(true).title).toBe('没有命中任何等级变更');
      expect(membershipLevelChangeEmptyStateText(false).title).toBe('还没有等级变更记录');
    });
  });

  describe('统计与规则的一句话说明', () => {
    it('统计说明带会员数与未达任何等级数', () => {
      const note = membershipStatsNote({
        totalMembers: 4,
        unmatchedMembers: 2,
        upgradedMembers: 1,
        downgradedMembers: 1,
        byLevel: [],
        spendByCurrency: [],
        note: '统计口径说明',
      } as never);
      expect(note).toContain('会员 4 位');
      expect(note).toContain('未达任何等级 2 位');
      expect(note).toContain('统计口径说明');
    });

    it('统计缺失时返回空串（不编数字）', () => {
      expect(membershipStatsNote(undefined)).toBe('');
    });

    it('规则说明带上等级名、代码、等级序与状态', () => {
      const note = membershipLevelRuleNote({
        id: 'id',
        levelCode: 'GOLD',
        levelName: '黄金会员',
        levelRank: 2,
        enabled: true,
        effectiveNow: true,
        minTotalSpend: '1.00',
        minTotalSpendCurrency: 'USD',
        minOrderCount: 4,
        minRepurchaseRate: null,
        freeShipping: true,
        priorityShipping: true,
        discountRate: '0.05',
        effectiveFrom: '2020-01-01T00:00:00Z',
        effectiveTo: null,
        note: '',
        createdAt: '2026-11-03T00:00:00Z',
      } as never);
      expect(note).toContain('黄金会员');
      expect(note).toContain('GOLD');
      expect(note).toContain('等级序 2');
      expect(note).toContain('生效中');
      expect(note).toContain('累计消费 ≥ 1.00 USD');
    });

    it('权益配置说明带等级与生效条件', () => {
      const note = membershipBenefitConfigNote({
        levelCode: 'GOLD',
        levelName: '黄金会员',
        levelRank: 2,
        discountRate: '0.05',
        freeShipping: true,
        priorityShipping: true,
        benefitSummary: '减免 5% · 免邮 · 优先发货',
        appliesTo: '累计消费 ≥ 1.00 USD 且 订单数 ≥ 4',
        effectiveFrom: '2020-01-01T00:00:00Z',
        effectiveTo: null,
        effectiveNow: true,
        downstreamConsumers: ['5 定价', '14 促销'],
        note: '',
      } as never);
      expect(note).toContain('黄金会员');
      expect(note).toContain('累计消费 ≥ 1.00 USD');
    });
  });
});
