import { describe, expect, it } from 'vitest';
import {
  canCreateRequest,
  collectedAtText,
  confidenceLabel,
  confidenceTone,
  decisionLabel,
  EMPTY_SOURCE_ALTERNATIVE_FILTERS,
  leadTimeLabel,
  matchTypeLabel,
  moqLabel,
  numeric,
  priceRangeLabel,
  ratingLabel,
  requestBlockedReason,
  scoreLabel,
  toPolicyForm,
  validateDecisionForm,
  validatePolicyForm,
  validateRequestForm,
  type SourceAlternativeCandidate,
  type SourceAlternativePolicy,
} from './source-alternative-model';

/**
 * 6.1.2.2 同款货源推荐前端模型的单测。
 *
 * <p>钉住的是**页面上最容易出错的那几处**：
 *
 * <ul>
 *   <li>置信度与排序分**不是一回事**（置信度是百分比、排序分也是百分比，但标签必须不同）；</li>
 *   <li>价格**永远是区间**（不压成精确价）；</li>
 *   <li>历史交期没有就是「暂无」，**不是 0 天**；</li>
 *   <li>评级的**客观分与最终分并排**，人工调整单独标记；</li>
 *   <li>弱匹配没确认 ⇒ 按钮是灰的，而且**要说清为什么**；</li>
 *   <li>权重和必须精确等于 1；否决必须写理由；数量不得低于起订量。</li>
 * </ul>
 */
describe('6.1.2.2 同款货源推荐前端模型', () => {
  describe('数值与置信度', () => {
    it('空值读成 null 而不是 0', () => {
      expect(numeric(null)).toBeNull();
      expect(numeric(undefined)).toBeNull();
      expect(numeric('')).toBeNull();
      expect(numeric('abc')).toBeNull();
      expect(numeric('0')).toBe(0);
      expect(numeric('0.5500')).toBe(0.55);
    });

    it('置信度显示成百分比一位小数', () => {
      expect(confidenceLabel('1.0000')).toBe('100.0%');
      expect(confidenceLabel('0.5500')).toBe('55.0%');
      expect(confidenceLabel('0.4000')).toBe('40.0%');
      expect(confidenceLabel(null)).toBe('置信度未知');
    });

    it('置信度色调分三档：确证绿 / 强线索黄 / 弱线索红', () => {
      // 确证（人工关联过的 SKU）= 1.0000；历史供应商最高 0.7500 ⇒ 仍是黄。
      expect(confidenceTone('1.0000')).toBe('success');
      expect(confidenceTone('0.9000')).toBe('success');
      expect(confidenceTone('0.7500')).toBe('warning');
      expect(confidenceTone('0.5500')).toBe('warning');
      expect(confidenceTone('0.4000')).toBe('danger');
      expect(confidenceTone('0.3000')).toBe('danger');
      expect(confidenceTone(null)).toBe('neutral');
    });

    it('排序分的标签与置信度不同（两个数不是一回事）', () => {
      expect(scoreLabel('0.8125')).toBe('排序分 81.3');
      expect(scoreLabel(null)).toBe('排序分未知');
    });
  });

  describe('价格与起订量', () => {
    it('价格永远显示成区间', () => {
      expect(priceRangeLabel('8.0000', '12.0000', 'CNY')).toBe('CNY 8.0000~12.0000');
      expect(priceRangeLabel('8.0000', '8.0000', 'CNY')).toBe('CNY 8.0000');
      expect(priceRangeLabel(null, '12.0000', 'CNY')).toBe('暂无价格');
      expect(priceRangeLabel('8.0000', null, null)).toBe('8.0000');
    });

    it('起订量没有就是「未知」而不是 0', () => {
      expect(moqLabel('100')).toBe('起订量 100');
      expect(moqLabel(null)).toBe('起订量未知');
    });

    it('历史交期没有就是「暂无」而不是 0 天', () => {
      expect(leadTimeLabel('24.50')).toBe('历史平均交期 24.5 天');
      expect(leadTimeLabel(null)).toBe('暂无历史交期');
    });

    it('采集时刻缺失时如实说未知', () => {
      expect(collectedAtText(null)).toBe('采集时刻未知');
      expect(collectedAtText('2026-10-21T12:34:56Z')).toContain('采集于');
    });
  });

  describe('评级', () => {
    it('客观分与最终分并排，人工调整单独标记', () => {
      expect(ratingLabel('70.0000', '75.0000', 'B', true)).toBe(
        '客观 70.00 / 最终 75.00（B） · 人工调整',
      );
      expect(ratingLabel('80.0000', '80.0000', 'A', false)).toBe(
        '客观 80.00 / 最终 80.00（A）',
      );
      expect(ratingLabel(null, null, null, null)).toBe('暂无评级');
    });
  });

  describe('判定类型与判定状态', () => {
    it('判定类型有本地兜底文案', () => {
      expect(matchTypeLabel('SKU_LINK')).toBe('已确认同款（人工关联）');
      expect(matchTypeLabel('PURCHASE_HISTORY')).toBe('历史采购过该货的供应商');
      expect(matchTypeLabel('TITLE_SPEC_MATCH')).toBe('疑似同款（标题与规格一致）');
      // 后端给的中文优先。
      expect(matchTypeLabel('SKU_LINK', '后端文案')).toBe('后端文案');
    });

    it('判定状态文案', () => {
      expect(decisionLabel('CONFIRMED')).toBe('已人工确认是同款');
      expect(decisionLabel('REJECTED')).toBe('已否决');
      expect(decisionLabel('REQUESTED')).toBe('已生成采购申请');
      expect(decisionLabel(null)).toBe('待处置');
    });
  });

  describe('能不能生成采购申请', () => {
    const base: SourceAlternativeCandidate = {
      id: 'c1',
      runId: 'r1',
      itemId: 'i1',
      stallId: 's1',
      stallName: '档口 A',
      platform: 'ALIBABA_1688',
      platformItemId: 'ITEM-A',
      supplierId: 'sup-1',
      supplierCode: 'SUP-A',
      supplierName: '供应商 A',
      title: '加厚棉质手套 冬季款',
      spec: 'L',
      imageUrl: '',
      priceMin: '10.0000',
      priceMax: '15.0000',
      currency: 'CNY',
      moq: '100',
      collectedAt: '2026-10-21T12:34:56Z',
      onSale: true,
      matchType: 'SKU_LINK',
      matchTypeLabel: '已确认同款（人工关联）',
      confidence: '1.0000',
      matchBasis: '已建立货源关联',
      requiresManualConfirmation: false,
      historyOrderCount: 0,
      historyQuantity: '0',
      leadTimeDays: null,
      ratingObjectiveScore: null,
      ratingFinalScore: null,
      ratingGrade: null,
      ratingManuallyAdjusted: null,
      ratingPeriodStart: null,
      ratingPeriodEnd: null,
      priceRank: 1,
      lowestPrice: true,
      score: '1.0000',
      sortRank: 0,
      decision: null,
      decisionLabel: null,
      purchaseRequestNo: null,
    };

    it('确证候选可以直接生成', () => {
      expect(canCreateRequest(base)).toBe(true);
      expect(requestBlockedReason(base)).toBe('');
    });

    it('弱匹配没确认 ⇒ 不给生成，且说清为什么', () => {
      const weak = { ...base, matchType: 'TITLE_SPEC_MATCH' as const, requiresManualConfirmation: true };
      expect(canCreateRequest(weak)).toBe(false);
      expect(requestBlockedReason(weak)).toContain('必须先人工确认');
    });

    it('弱匹配确认过 ⇒ 可以生成', () => {
      const weak = {
        ...base,
        matchType: 'TITLE_SPEC_MATCH' as const,
        requiresManualConfirmation: true,
        decision: 'CONFIRMED',
      };
      expect(canCreateRequest(weak)).toBe(true);
    });

    it('没有供应商 ⇒ 不给生成（没有下单对象）', () => {
      const orphan = { ...base, supplierId: null };
      expect(canCreateRequest(orphan)).toBe(false);
      expect(requestBlockedReason(orphan)).toContain('没有关联供应商');
    });
  });

  describe('规则与权重', () => {
    const policy: SourceAlternativePolicy = {
      configured: true,
      weakMatchMinConfidence: 0.3,
      confirmedMatchMinConfidence: 0.9,
      requireManualConfirmation: true,
      maxCandidates: 50,
      priceWeight: 0.3,
      ratingWeight: 0.25,
      historyWeight: 0.2,
      leadTimeWeight: 0.25,
      version: 3,
      updatedAt: '2026-10-21T12:00:00Z',
    };

    it('策略转表单', () => {
      const form = toPolicyForm(policy);
      expect(form.priceWeight).toBe('0.3');
      expect(form.maxCandidates).toBe('50');
      expect(form.requireManualConfirmation).toBe(true);
    });

    it('权重和必须精确等于 1', () => {
      const form = toPolicyForm(policy);
      expect(validatePolicyForm(form)).toEqual([]);
      expect(validatePolicyForm({ ...form, priceWeight: '0.5' }).join()).toContain(
        '权重之和必须精确等于 1',
      );
    });

    it('权重为负或越界要报错', () => {
      const form = toPolicyForm(policy);
      expect(validatePolicyForm({ ...form, priceWeight: '-0.1' }).join()).toContain(
        '不小于 0',
      );
      expect(validatePolicyForm({ ...form, maxCandidates: '0' }).join()).toContain(
        '单次候选上限',
      );
      expect(validatePolicyForm({ ...form, weakMatchMinConfidence: '1.5' }).join()).toContain(
        '弱匹配最低置信度',
      );
    });
  });

  describe('表单校验', () => {
    it('否决必须写理由', () => {
      expect(validateDecisionForm('REJECTED', '   ').join()).toContain('否决必须写理由');
      expect(validateDecisionForm('REJECTED', '规格对不上')).toEqual([]);
      expect(validateDecisionForm('CONFIRMED', '')).toEqual([]);
    });

    it('数量必须大于 0 且不低于起订量', () => {
      expect(validateRequestForm('0', 'wh', 'le', '100').join()).toContain('必须大于 0');
      expect(validateRequestForm('50', 'wh', 'le', '100').join()).toContain('起订量');
      expect(validateRequestForm('100', 'wh', 'le', '100')).toEqual([]);
      expect(validateRequestForm('100', '', 'le', '100').join()).toContain('目标仓');
      expect(validateRequestForm('100', 'wh', '', '100').join()).toContain('法人');
    });
  });

  describe('默认筛选', () => {
    it('默认筛选是空的第一页', () => {
      expect(EMPTY_SOURCE_ALTERNATIVE_FILTERS.page).toBe(0);
      expect(EMPTY_SOURCE_ALTERNATIVE_FILTERS.skuId).toBe('');
      expect(EMPTY_SOURCE_ALTERNATIVE_FILTERS.reason).toBe('');
    });
  });
});
