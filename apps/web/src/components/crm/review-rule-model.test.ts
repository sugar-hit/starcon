import { describe, expect, it } from 'vitest';
import type { CrmReviewEffectivePolicy, CrmReviewVariantRow } from '../../../../../packages/api-contracts/src/index';
import {
  assignmentQuery,
  averageRatingText,
  effectQuery,
  effectVariantText,
  effectiveQuery,
  effectiveSummary,
  holidayKindLabel,
  holidayQuery,
  logisticsModeLabel,
  pickedVariant,
  recoveryRateText,
  requestStatusLabel,
  ruleQuery,
  ruleStatusLabel,
  templateQuery,
  templateStatusLabel,
} from './review-rule-model';

/**
 * 15.2.2.2 索评规则前端模型的纯规则测试。
 *
 * <p>本文件把**页面最容易做错的事**钉死：
 *
 * <ol>
 *   <li><b>「不计算」不等于 0%</b>：回收率的分母是「已发出的索评」，
 *       分母为 0 时报「不计算」而不是 0% —— 0% 会被读成
 *       「发出去了但一个评价都没回来」，那是两件完全不同的事。</li>
 *   <li><b>「没命中」不等于「有一份默认值」</b>：没命中生效规则时要说清
 *       「没有命中任何生效规则」，而不是回退到一组看起来正常的数字。</li>
 *   <li><b>「说不出分到哪个臂」不等于「分到了 A」</b>：没有分桶种子时
 *       {@link pickedVariant} 必须返回 null。</li>
 *   <li><b>「未分配」不等于「分配了但一个都没发出去」</b>：两者的可读形态必须分开。</li>
 *   <li>各词表的可读名与后端 DB 词表一一对应，未知取值原样透出。</li>
 * </ol>
 */

function variant(overrides: Partial<CrmReviewVariantRow> = {}): CrmReviewVariantRow {
  return {
    id: 'variant-a',
    templateId: 'template-1',
    variantCode: 'A',
    subject: '感谢您的购买',
    body: '方便给个评价吗？',
    weight: 60,
    status: 'ACTIVE',
    createdAt: '2026-10-01T00:00:00Z',
    updatedAt: '2026-10-01T00:00:00Z',
    ...overrides,
  };
}

function policy(overrides: Partial<CrmReviewEffectivePolicy> = {}): CrmReviewEffectivePolicy {
  return {
    ruleId: 'rule-1',
    ruleNo: 'RULE-20261021-000001',
    ruleName: '自发货 48 小时',
    shopId: 'shop-1',
    shopName: '主店铺',
    logisticsMode: 'SELF_FULFILLED',
    delayHours: 48,
    templateId: 'template-1',
    templateName: '索评标准话术',
    abTestEnabled: true,
    variants: [variant(), variant({ id: 'variant-b', variantCode: 'B', weight: 40 })],
    pickedVariantId: 'variant-a',
    baseScheduledAt: '2026-10-03T09:30:00+08:00',
    scheduledAt: '2026-10-03T09:30:00+08:00',
    shiftedDays: 0,
    holidayName: '',
    holidayAvoided: false,
    matchedScope: '店铺 + 物流方式',
    resolvedAt: '2026-10-01T09:30:00+08:00',
    note: '',
    ...overrides,
  };
}

describe('15.2.2.2 索评规则前端模型', () => {
  describe('回收率与平均星级：null 绝不渲染成 0', () => {
    it('分母为 0 时报「不计算」而不是 0%', () => {
      expect(recoveryRateText(null)).toContain('不计算');
      expect(recoveryRateText(null)).not.toContain('0.0%');
      expect(recoveryRateText(undefined)).toContain('不计算');
    });

    it('真实数值按一位小数展示', () => {
      expect(recoveryRateText(0)).toBe('0.0%');
      expect(recoveryRateText(0.25)).toBe('25.0%');
      expect(recoveryRateText(1)).toBe('100.0%');
    });

    it('平均星级没有回收时显示「暂无」而不是 0 星', () => {
      expect(averageRatingText(null)).toBe('暂无');
      expect(averageRatingText(undefined)).toBe('暂无');
      expect(averageRatingText(4.5)).toBe('4.5 星');
    });
  });

  describe('生效策略：没有命中就说没有命中', () => {
    it('命中时摘要点名规则与范围', () => {
      const summary = effectiveSummary(policy());
      expect(summary).toContain('RULE-20261021-000001');
      expect(summary).toContain('店铺 + 物流方式');
      expect(summary).toContain('变体 A');
    });

    it('没命中时如实说没命中，而不是给一份看起来正常的默认值', () => {
      const summary = effectiveSummary(policy({ ruleId: null, ruleNo: '', ruleName: '' }));
      expect(summary).toContain('没有命中');
      expect(summary).not.toContain('延后');
    });

    it('避开了节假日时摘要带出顺延天数', () => {
      const summary = effectiveSummary(
        policy({ holidayAvoided: true, holidayName: '国庆', shiftedDays: 2 }),
      );
      expect(summary).toContain('国庆');
      expect(summary).toContain('顺延 2 天');
    });

    it('没有生效策略时如实说不可用', () => {
      expect(effectiveSummary(null)).toContain('不可用');
    });
  });

  describe('A/B 分桶：说不出分到哪个臂就说不出', () => {
    it('有 pickedVariantId 时能定位到那个变体', () => {
      expect(pickedVariant(policy())?.variantCode).toBe('A');
    });

    it('没有 pickedVariantId 时返回 null —— 不能随便挑一个显示', () => {
      expect(pickedVariant(policy({ pickedVariantId: null }))).toBeNull();
    });

    it('pickedVariantId 指向不存在的变体时返回 null 而不是抛错', () => {
      expect(pickedVariant(policy({ pickedVariantId: 'ghost' }))).toBeNull();
    });

    it('没有策略时返回 null', () => {
      expect(pickedVariant(null)).toBeNull();
    });
  });

  describe('效果对比：未分配与「分配了但没发出去」是两句不同的话', () => {
    it('一条都没分配时显示「未分配」', () => {
      const text = effectVariantText({
        variantCode: 'B',
        templateName: '索评标准话术',
        assigned: 0,
        sent: 0,
        recovered: 0,
        recoveryRate: null,
        averageRating: null,
      });
      expect(text).toContain('未分配');
      expect(text).not.toContain('回收率');
    });

    it('分配过但没发出去时如实显示分配数与「不计算」', () => {
      const text = effectVariantText({
        variantCode: 'A',
        templateName: '索评标准话术',
        assigned: 3,
        sent: 0,
        recovered: 0,
        recoveryRate: null,
        averageRating: null,
      });
      expect(text).toContain('分配 3');
      expect(text).toContain('不计算');
    });
  });

  describe('词表可读名', () => {
    it('规则状态与后端 DB 词表一一对应', () => {
      expect(ruleStatusLabel('DRAFT')).toBe('草稿');
      expect(ruleStatusLabel('ACTIVE')).toBe('生效中');
      expect(ruleStatusLabel('PAUSED')).toBe('已暂停');
    });

    it('物流方式区分自发货与平台仓', () => {
      expect(logisticsModeLabel('ANY')).toBe('不限');
      expect(logisticsModeLabel('SELF_FULFILLED')).toBe('自发货');
      expect(logisticsModeLabel('PLATFORM_WAREHOUSE')).toBe('平台仓');
      // 「不限」与「自发货」不能混成同一句。
      expect(logisticsModeLabel('ANY')).not.toBe(logisticsModeLabel('SELF_FULFILLED'));
    });

    it('模板状态、节假日类型与索评请求状态各有各的可读名', () => {
      expect(templateStatusLabel('ACTIVE')).toBe('启用中');
      expect(templateStatusLabel('PAUSED')).toBe('已停用');
      expect(holidayKindLabel('HOLIDAY')).toBe('法定节假日');
      expect(holidayKindLabel('PROMOTION')).toBe('大促');
      expect(requestStatusLabel('SENT')).toBe('已发出');
      expect(requestStatusLabel('REJECTED')).toBe('被频次规则拒绝');
    });

    it('未知取值原样透出，不编一个像模像样的说法', () => {
      expect(ruleStatusLabel('WHATEVER')).toBe('WHATEVER');
      expect(logisticsModeLabel('WHATEVER')).toBe('WHATEVER');
      expect(holidayKindLabel('WHATEVER')).toBe('WHATEVER');
    });
  });

  describe('列表筛选', () => {
    it('只把填了的条件放进查询串', () => {
      expect(ruleQuery({})).toBe('');
      expect(ruleQuery({ status: 'ACTIVE' })).toBe('?status=ACTIVE');
      expect(ruleQuery({ shopId: 'shop-1', logisticsMode: 'SELF_FULFILLED' })).toBe(
        '?shopId=shop-1&logisticsMode=SELF_FULFILLED',
      );
    });

    it('条件里的特殊字符被转义，不会拼出第二个参数', () => {
      expect(ruleQuery({ keyword: 'a b&c=d' })).toBe('?keyword=a%20b%26c%3Dd');
      expect(templateQuery({ keyword: 'x&y' })).toBe('?keyword=x%26y');
      expect(holidayQuery({ year: '2026' })).toBe('?year=2026');
      expect(assignmentQuery({ ruleId: 'r1', page: 2 })).toBe('?ruleId=r1&page=2');
      expect(effectiveQuery({ seedKey: 'k' })).toBe('?seedKey=k');
      expect(effectQuery({ days: 30 })).toBe('?days=30');
    });

    it('空串与 undefined 都不进查询串', () => {
      expect(ruleQuery({ status: '', keyword: undefined })).toBe('');
    });
  });
});
