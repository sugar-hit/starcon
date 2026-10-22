import { describe, expect, it } from 'vitest';
import type {
  CrmMessageClassificationMatchBasis,
  CrmMessageClassificationRow,
  CrmMessageClassificationSummary,
} from '../../../../../packages/api-contracts/src/index';
import {
  classificationQuery,
  classificationText,
  hasActiveFilter,
  labelLabel,
  matchBasisText,
  matchModeLabel,
  parseCodeDraft,
  parseKeywordDraft,
  priorityLabel,
  priorityText,
  ruleQuery,
  ruleText,
  sampleQuery,
  statisticsText,
  summaryText,
  trainingSampleText,
} from './message-classification-model';

/**
 * 15.1.1.3 消息分类前端模型的纯规则测试。
 *
 * <p>本文件把**页面最容易做错的三件事**钉死：
 *
 * <ol>
 *   <li><b>人工值 / 建议值 / 生效值 / 是否冲突，四件事都要显示</b>：
 *       只显示一个数字的话，看的人会以为那就是唯一结论 ——
 *       而「机器建议低」和「客服设的紧急」本来就是两回事。</li>
 *   <li><b>命中依据不能是空白</b>：没有命中任何规则时也要显示
 *       「没有规则命中」—— 空白会被读成「还没分类」。</li>
 *   <li><b>训练样本必须带正文</b>：少了正文就只剩标签对，
 *       训不出「哪句话被判成了什么」。</li>
 * </ol>
 */
describe('15.1.1.3 消息分类前端模型', () => {
  describe('可读名', () => {
    it('七个标签各有各的说法', () => {
      expect(labelLabel('PRE_SALE')).toBe('售前咨询');
      expect(labelLabel('LOGISTICS')).toBe('物流查询');
      expect(labelLabel('RETURN_REFUND')).toBe('退货退款');
      expect(labelLabel('COMPLAINT')).toBe('投诉');
      expect(labelLabel('PLATFORM_COMPLIANCE')).toBe('平台合规');
      expect(labelLabel('SPAM')).toBe('垃圾信息');
      expect(labelLabel('UNCLASSIFIED')).toBe('未分类');
    });

    it('优先级与命中方式各有各的说法', () => {
      expect(priorityLabel('URGENT')).toBe('紧急');
      expect(priorityLabel('LOW')).toBe('低');
      expect(matchModeLabel('ANY')).toBe('任一命中');
      expect(matchModeLabel('ALL')).toBe('全部命中');
    });

    it('未知取值原样透出，不编一个像模像样的说法', () => {
      expect(labelLabel('WHATEVER')).toBe('WHATEVER');
      expect(priorityLabel('WHATEVER')).toBe('WHATEVER');
      expect(matchModeLabel('WHATEVER')).toBe('WHATEVER');
    });
  });

  describe('🔴 人工值 / 建议值 / 冲突', () => {
    it('冲突时三个值与冲突标记都要说清', () => {
      const text = priorityText({
        suggestedPriority: 'LOW',
        manualPriority: 'URGENT',
        effectivePriority: 'URGENT',
        priorityConflict: true,
      });
      expect(text).toContain('建议 低');
      expect(text).toContain('人工 紧急');
      expect(text).toContain('生效 紧急');
      expect(text).toContain('冲突');
      expect(text).toContain('按人工值生效');
    });

    it('不冲突时说清楚「一致」，不制造假警报', () => {
      const text = priorityText({
        suggestedPriority: 'HIGH',
        manualPriority: 'HIGH',
        effectivePriority: 'HIGH',
        priorityConflict: false,
      });
      expect(text).toContain('一致');
      expect(text).not.toContain('⚠️');
    });
  });

  describe('命中依据', () => {
    const basis: CrmMessageClassificationMatchBasis = {
      winningRuleId: '11111111-1111-1111-1111-111111111111',
      winningRuleCode: 'LOG-001',
      matches: [
        {
          ruleId: '11111111-1111-1111-1111-111111111111',
          ruleCode: 'LOG-001',
          ruleName: '物流查询关键词',
          ruleOrder: 10,
          targetLabel: 'LOGISTICS',
          won: true,
          keywords: [{ keyword: 'tracking', field: 'BODY' }],
        },
        {
          ruleId: '22222222-2222-2222-2222-222222222222',
          ruleCode: 'SPAM-010',
          ruleName: '垃圾词',
          ruleOrder: 20,
          targetLabel: 'SPAM',
          won: false,
          keywords: [{ keyword: 'free', field: 'BODY' }],
        },
      ],
    };

    it('命中了哪条规则的哪个关键词、在哪个字段命中，都要说出来', () => {
      const text = matchBasisText(basis);
      expect(text).toContain('LOG-001');
      expect(text).toContain('tracking');
      expect(text).toContain('正文');
      expect(text).toContain('生效');
    });

    it('落败的规则也显示出来 —— 只记赢家就看不出「为什么不是它」', () => {
      const text = matchBasisText(basis);
      expect(text).toContain('SPAM-010');
      expect(text).toContain('未生效');
    });

    it('没有命中任何规则时不返回空白', () => {
      const empty: CrmMessageClassificationMatchBasis = {
        winningRuleId: null,
        winningRuleCode: null,
        matches: [],
      };
      const text = matchBasisText(empty);
      expect(text.trim().length).toBeGreaterThan(0);
      expect(text).toContain('没有规则命中');
      expect(text).toContain('未分类');
    });

    it('还没有分类过时也不返回空白', () => {
      expect(matchBasisText(null).trim().length).toBeGreaterThan(0);
      expect(matchBasisText(undefined)).toContain('还没分类');
    });
  });

  describe('结论与规则摘要', () => {
    const row: CrmMessageClassificationRow = {
      id: '11111111-1111-1111-1111-111111111111',
      classificationNo: 'CLS-20260601-000001',
      messageId: '22222222-2222-2222-2222-222222222222',
      messageNo: 'MSG-20260601-000001',
      subject: '物流查询',
      platformCode: 'AMAZON',
      buyerReference: 'buyer-1',
      orderReference: 'SO-1',
      label: 'LOGISTICS',
      suggestedPriority: 'HIGH',
      manualPriority: 'HIGH',
      effectivePriority: 'HIGH',
      priorityConflict: false,
      autoReplyEligible: true,
      matchedRuleId: '33333333-3333-3333-3333-333333333333',
      matchedRuleCode: 'LOG-001',
      matchedRuleName: '物流查询关键词',
      matchBasis: {
        winningRuleId: null,
        winningRuleCode: null,
        matches: [],
      },
      classifierVersion: 'RULE_MATCH_V1',
      classifiedAt: '2026-06-01T09:30:00Z',
      createdAt: '2026-06-01T09:30:00Z',
      updatedAt: '2026-06-01T09:30:00Z',
      correctionCount: 2,
      latestCorrectedLabel: 'RETURN_REFUND',
    };

    it('结论摘要把标签、优先级、可自动回复与纠正次数都带出来', () => {
      const text = classificationText(row);
      expect(text).toContain('物流查询');
      expect(text).toContain('可自动回复');
      expect(text).toContain('判定结果');
      expect(text).toContain('LOG-001');
      expect(text).toContain('已纠正 2 次');
    });

    it('规则摘要说明关键词、适用范围与启用状态', () => {
      const text = ruleText({
        id: '33333333-3333-3333-3333-333333333333',
        ruleCode: 'LOG-001',
        ruleName: '物流查询关键词',
        priorityOrder: 10,
        targetLabel: 'LOGISTICS',
        suggestedPriority: 'HIGH',
        autoReplyEligible: true,
        keywords: ['tracking', '运单'],
        matchMode: 'ANY',
        platformCodes: [],
        messageTypes: ['BUYER_MESSAGE'],
        enabled: true,
        note: '',
        createdAt: '2026-06-01T09:30:00Z',
        updatedAt: '2026-06-01T09:30:00Z',
      });
      expect(text).toContain('顺序 10');
      expect(text).toContain('物流查询');
      expect(text).toContain('tracking');
      expect(text).toContain('适用全部平台');
      expect(text).toContain('BUYER_MESSAGE');
      expect(text).toContain('已启用');
    });
  });

  describe('空态区分', () => {
    it('没有任何筛选条件时是「还没分类过」，有条件时是「筛选无结果」', () => {
      expect(hasActiveFilter({})).toBe(false);
      expect(hasActiveFilter({ page: 0, size: 20 })).toBe(false);
      expect(hasActiveFilter({ label: 'SPAM' })).toBe(true);
      expect(hasActiveFilter({ keyword: '退款' })).toBe(true);
      expect(hasActiveFilter({ priorityConflict: true })).toBe(true);
      expect(hasActiveFilter({ autoReplyEligible: false })).toBe(true);
    });
  });

  describe('查询串', () => {
    it('空值一律不拼，省得后端收到空串', () => {
      expect(classificationQuery({})).toBe('');
      expect(classificationQuery({ label: '', keyword: '' })).toBe('');
      expect(classificationQuery({ label: 'SPAM' })).toBe('?label=SPAM');
      expect(ruleQuery({})).toBe('');
      expect(sampleQuery({})).toBe('');
    });

    it('带查询串的值要转义', () => {
      expect(classificationQuery({ keyword: '退款 100%' })).toBe(
        '?keyword=%E9%80%80%E6%AC%BE%20100%25',
      );
    });

    it('布尔筛选要真的传出去（false 也要传 —— 那是一个真实的筛选）', () => {
      expect(classificationQuery({ priorityConflict: false })).toBe('?priorityConflict=false');
      expect(ruleQuery({ enabled: false })).toBe('?enabled=false');
    });
  });

  describe('汇总与统计', () => {
    it('汇总计数原样展示，JS 侧不做算术', () => {
      const summary: CrmMessageClassificationSummary = {
        totalCount: 12,
        conflictCount: 3,
        autoReplyEligibleCount: 5,
        correctedCount: 2,
        byLabel: [{ code: 'LOGISTICS', label: '物流查询', count: 4 }],
        bySuggestedPriority: [],
      };
      const text = summaryText(summary);
      expect(text).toContain('共 12 条结论');
      expect(text).toContain('冲突 3 条');
      expect(text).toContain('可自动回复 5 条');
      expect(text).toContain('已纠正 2 条');
      expect(text).toContain('物流查询×4');
    });

    it('没有汇总时也要有话说，不能是一段空白', () => {
      expect(summaryText(null).trim().length).toBeGreaterThan(0);
      expect(statisticsText(null).trim().length).toBeGreaterThan(0);
    });
  });

  describe('训练样本', () => {
    it('机器判错了要标出来，并带上原因', () => {
      const text = trainingSampleText({
        feedbackId: '11111111-1111-1111-1111-111111111111',
        messageId: '22222222-2222-2222-2222-222222222222',
        messageNo: 'MSG-1',
        subject: '物流查询',
        body: 'where is my tracking number',
        platformCode: 'AMAZON',
        messageType: 'BUYER_MESSAGE',
        buyerReference: 'buyer-1',
        orderReference: 'SO-1',
        predictedLabel: 'LOGISTICS',
        predictedPriority: 'HIGH',
        predictedRuleCode: 'LOG-001',
        correctedLabel: 'RETURN_REFUND',
        correctedPriority: 'URGENT',
        correctedAutoReplyEligible: false,
        reason: '买家其实要退货',
        correctedBy: '44444444-4444-4444-4444-444444444444',
        correctedAt: '2026-06-01T09:30:00Z',
        labelAgreed: false,
      });
      expect(text).toContain('机器判 物流查询');
      expect(text).toContain('人工改成 退货退款');
      expect(text).toContain('机器判错了');
      expect(text).toContain('LOG-001');
    });
  });

  describe('草稿解析', () => {
    it('关键词一行一个，空行跳过', () => {
      expect(parseKeywordDraft('tracking\n\n  运单  ')).toEqual(['tracking', '运单']);
      expect(parseKeywordDraft('  \n ')).toEqual([]);
    });

    it('平台 / 类型逗号分隔，留空表示适用全部', () => {
      expect(parseCodeDraft('AMAZON, SHOPIFY')).toEqual(['AMAZON', 'SHOPIFY']);
      expect(parseCodeDraft('')).toEqual([]);
    });
  });
});
