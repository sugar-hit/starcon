import { describe, expect, it } from 'vitest';
import type {
  CrmEmailRuleHitRow,
  CrmEmailRuleMatchBasis,
  CrmEmailRuleRow,
  CrmEmailRuleSnapshot,
  CrmEmailRuleStatistics,
} from '../../../../../packages/api-contracts/src/index';
import {
  actionLabel,
  assignmentText,
  emailRuleAssignmentQuery,
  emailRuleHitQuery,
  emailRuleQuery,
  emailRuleTagQuery,
  hasActiveFilter,
  hitActionText,
  hitText,
  importantSourceLabel,
  matchBasisText,
  matchModeLabel,
  parseCodeDraft,
  parseKeywordDraft,
  ruleStatText,
  ruleText,
  snapshotText,
  statisticsText,
  targetTypeLabel,
} from './email-rule-model';

/**
 * 15.1.2.2 邮件规则前端模型的纯规则测试。
 *
 * <p>本文件把**页面最容易做错的三件事**钉死：
 *
 * <ol>
 *   <li><b>「规则声明的动作」与「实际生效的动作」两个都要显示</b>：
 *       静默被拒时后端给的是 {@code ESCALATE_MANUAL} —— 只显示「静默」的话，
 *       看的人会以为这封重要沟通真的被静默了，而那正是平台违规的起点。</li>
 *   <li><b>命中依据不能是空白</b>：没有命中任何规则时也要显示「没有规则命中」——
 *       空白会被读成「还没评估」。</li>
 *   <li><b>命中凭据冻结当时的规则</b>：快照要显示「第几版」，
 *       否则改完规则再看历史命中，会以为历史也是新规则判的。</li>
 * </ol>
 */

/** 造一条命中记录。 */
function hit(overrides: Partial<CrmEmailRuleHitRow> = {}): CrmEmailRuleHitRow {
  return {
    id: 'hit-1',
    hitNo: 'ERT-20261022-000001',
    ruleId: 'rule-1',
    ruleCode: 'MAIL-001',
    ruleName: '静默促销邮件',
    ruleVersion: 3,
    emailId: 'email-1',
    emailNo: 'BE-0001',
    buyerEmail: 'buyer@example.com',
    subject: '促销',
    matchBasis: { winningRuleId: 'rule-1', winningRuleCode: 'MAIL-001', matches: [] },
    action: 'SILENCE',
    effectiveAction: 'SILENCE',
    silenceRefused: false,
    refusalReason: '',
    important: false,
    importantSource: '',
    classificationId: null,
    classificationLabel: '',
    assignmentId: null,
    deliveryStatus: null,
    matchedAt: '2026-10-22T10:00:00Z',
    createdAt: '2026-10-22T10:00:00Z',
    ...overrides,
  };
}

/** 造一条规则。 */
function rule(overrides: Partial<CrmEmailRuleRow> = {}): CrmEmailRuleRow {
  return {
    id: 'rule-1',
    ruleCode: 'MAIL-001',
    ruleName: '静默促销邮件',
    priorityOrder: 10,
    enabled: true,
    action: 'SILENCE',
    senderEmails: [],
    senderDomains: [],
    keywords: ['促销'],
    ticketTagCodes: [],
    matchMode: 'ANY',
    assignTargetType: 'TEAM',
    assignTargetCode: '',
    replyTemplateCode: '',
    replySubject: '',
    replyBody: '',
    importantMarkers: [],
    version: 1,
    note: '',
    createdAt: '2026-10-22T10:00:00Z',
    updatedAt: '2026-10-22T10:00:00Z',
    ...overrides,
  };
}

describe('15.1.2.2 邮件规则前端模型', () => {
  describe('可读名', () => {
    it('三种动作与降级动作各有各的说法', () => {
      expect(actionLabel('AUTO_ASSIGN')).toBe('自动分配');
      expect(actionLabel('AUTO_REPLY')).toBe('自动回复');
      expect(actionLabel('SILENCE')).toBe('静默');
      expect(actionLabel('ESCALATE_MANUAL')).toBe('不静默 + 转人工');
    });

    it('命中方式、目标类型、重要判定来源各有各的说法', () => {
      expect(matchModeLabel('ANY')).toBe('任一命中');
      expect(matchModeLabel('ALL')).toBe('全部命中');
      expect(targetTypeLabel('TEAM')).toBe('组');
      expect(targetTypeLabel('AGENT')).toBe('人');
      expect(importantSourceLabel('CLASSIFICATION')).toContain('分类结果');
      expect(importantSourceLabel('RULE_MARKER')).toContain('规则标记');
    });

    it('未知取值原样透出，不编一个像模像样的说法', () => {
      expect(actionLabel('WHAT_IS_THIS')).toBe('WHAT_IS_THIS');
      expect(matchModeLabel('SOME')).toBe('SOME');
      expect(importantSourceLabel('')).toBe('不是重要沟通');
    });
  });

  describe('🔴 声明的动作 vs 实际生效的动作', () => {
    it('两者一致时只说一次', () => {
      expect(hitActionText(hit())).toBe('规则动作 静默');
    });

    it('静默被拒时必须把降级与理由都显示出来', () => {
      const text = hitActionText(
        hit({
          action: 'SILENCE',
          effectiveAction: 'ESCALATE_MANUAL',
          silenceRefused: true,
          refusalReason: '重要沟通不可静默：静默重要沟通会造成平台违规',
        }),
      );
      expect(text).toContain('规则动作 静默');
      expect(text).toContain('实际生效 不静默 + 转人工');
      expect(text).toContain('静默被拒');
      expect(text).toContain('平台违规');
    });

    it('重要沟通的判定来源要显示出来', () => {
      const text = hitText(
        hit({
          action: 'SILENCE',
          effectiveAction: 'ESCALATE_MANUAL',
          silenceRefused: true,
          important: true,
          importantSource: 'CLASSIFICATION',
          classificationLabel: 'PLATFORM_COMPLIANCE',
        }),
      );
      expect(text).toContain('重要沟通');
      expect(text).toContain('分类结果');
    });

    it('自动回复待下游投递时不能说成「已发出」', () => {
      const text = hitText(
        hit({
          action: 'AUTO_REPLY',
          effectiveAction: 'AUTO_REPLY',
          deliveryStatus: 'PENDING_DOWNSTREAM',
        }),
      );
      expect(text).toContain('待下游投递');
      expect(text).not.toContain('已发出');
    });
  });

  describe('命中依据', () => {
    it('没有依据时不能是空白', () => {
      expect(matchBasisText(null)).toContain('还没评估');
      expect(matchBasisText({ winningRuleId: null, winningRuleCode: null, matches: [] })).toContain(
        '没有规则命中',
      );
    });

    it('逐维度显示命中了什么，并标出哪条规则生效', () => {
      const basis: CrmEmailRuleMatchBasis = {
        winningRuleId: 'rule-1',
        winningRuleCode: 'MAIL-001',
        matches: [
          {
            ruleId: 'rule-1',
            ruleCode: 'MAIL-001',
            ruleName: '静默促销邮件',
            ruleOrder: 10,
            ruleVersion: 1,
            action: 'SILENCE',
            won: true,
            conditions: {
              sender: {
                matched: true,
                emails: [],
                domains: [],
                matchedEmails: [],
                matchedDomains: [],
              },
              keywords: { matched: true, keywords: ['促销'], matchedKeywords: ['促销'] },
              ticketTags: { matched: false, codes: [], matchedCodes: [] },
            },
          },
        ],
      };
      const text = matchBasisText(basis);
      expect(text).toContain('规则 MAIL-001');
      expect(text).toContain('关键词命中 促销');
      expect(text).toContain('生效');
    });
  });

  describe('规则摘要', () => {
    it('三个条件维度与顺序、版本、启用状态都要显示', () => {
      const text = ruleText(
        rule({
          senderEmails: ['vip@example.com'],
          senderDomains: ['example.com'],
          keywords: ['退款'],
          ticketTagCodes: ['VIP'],
          importantMarkers: ['attorney'],
        }),
      );
      expect(text).toContain('顺序 10');
      expect(text).toContain('版本 1');
      expect(text).toContain('发件人邮箱 vip@example.com');
      expect(text).toContain('发件人域名 example.com');
      expect(text).toContain('关键词 退款');
      expect(text).toContain('工单标签 VIP');
      expect(text).toContain('重要标记 attorney');
      expect(text).toContain('已启用');
    });

    it('一个维度都没配的规则要显式点出来（它永远不会命中）', () => {
      const text = ruleText(rule({ keywords: [] }));
      expect(text).toContain('没有配置任何条件维度');
    });

    it('自动分配规则要显示分给谁', () => {
      const text = ruleText(
        rule({ action: 'AUTO_ASSIGN', keywords: ['退款'], assignTargetCode: 'VIP-GROUP' }),
      );
      expect(text).toContain('分给 组 VIP-GROUP');
    });
  });

  describe('规则快照', () => {
    it('要显示是第几版 —— 改规则不追溯改变历史命中记录', () => {
      const snapshot: CrmEmailRuleSnapshot = {
        ruleCode: 'MAIL-001',
        ruleName: '静默促销邮件',
        priorityOrder: 10,
        enabled: true,
        action: 'SILENCE',
        senderEmails: [],
        senderDomains: [],
        keywords: ['促销'],
        ticketTagCodes: [],
        matchMode: 'ANY',
        assignTargetType: 'TEAM',
        assignTargetCode: '',
        replyTemplateCode: '',
        replySubject: '',
        replyBody: '',
        importantMarkers: [],
        version: 2,
      };
      const text = snapshotText(snapshot);
      expect(text).toContain('第 2 版');
      expect(text).toContain('关键词 促销');
      expect(snapshotText(null)).toBe('没有规则快照。');
    });
  });

  describe('自动分配结果', () => {
    it('等下游取的时候不能出现工单字样（下游未交付）', () => {
      const text = assignmentText({
        id: 'a-1',
        assignmentNo: 'EAS-20261022-000001',
        hitId: 'hit-1',
        ruleId: 'rule-1',
        ruleCode: 'MAIL-001',
        ruleVersion: 1,
        emailId: 'email-1',
        emailNo: 'BE-0001',
        buyerEmail: 'buyer@example.com',
        subject: '促销',
        assignTargetType: 'TEAM',
        assignTargetCode: 'VIP-GROUP',
        status: 'PENDING_DISPATCH',
        dispatchNote: '',
        cancelReason: '',
        cancelledBy: null,
        cancelledAt: null,
        assignedAt: '2026-10-22T10:00:00Z',
        createdAt: '2026-10-22T10:00:00Z',
      });
      expect(text).toContain('等下游取');
      expect(text).not.toContain('工单已创建');
    });
  });

  describe('统计', () => {
    it('静默被拒的次数要单独说出来', () => {
      const statistics: CrmEmailRuleStatistics = {
        totalHitCount: 12,
        refusedCount: 3,
        importantCount: 5,
        pendingAssignmentCount: 2,
        byAction: [{ code: 'SILENCE', label: '静默', count: 12 }],
        byEffectiveAction: [{ code: 'ESCALATE_MANUAL', label: '不静默 + 转人工', count: 3 }],
        byRule: [],
        note: '备注',
      };
      const text = statisticsText(statistics);
      expect(text).toContain('命中 12 次');
      expect(text).toContain('静默被拒 3 次');
      expect(text).toContain('重要沟通 5 次');
      expect(text).toContain('待下游取 2 条');
      expect(
        ruleStatText({
          ruleCode: 'MAIL-001',
          ruleName: '静默促销邮件',
          hitCount: 12,
          refusedCount: 3,
          assignmentCount: 0,
          enabled: true,
        }),
      ).toContain('静默被拒 3 次');
    });
  });

  describe('筛选与草稿解析', () => {
    it('命中记录的筛选串只拼非空值', () => {
      expect(emailRuleHitQuery({ ruleCode: 'MAIL-001', page: 0, size: 20 })).toBe(
        '?ruleCode=MAIL-001&page=0&size=20',
      );
      expect(emailRuleHitQuery({})).toBe('');
    });

    it('规则与分配结果与标签的筛选串同样只拼非空值', () => {
      expect(emailRuleQuery({ action: 'SILENCE', page: 0 })).toBe('?action=SILENCE&page=0');
      expect(emailRuleAssignmentQuery({ status: 'PENDING_DISPATCH' })).toBe(
        '?status=PENDING_DISPATCH',
      );
      expect(emailRuleTagQuery({ keyword: 'VIP' })).toBe('?keyword=VIP');
    });

    it('有没有筛选条件要分得清（空态据此换文案）', () => {
      expect(hasActiveFilter({})).toBe(false);
      expect(hasActiveFilter({ ruleCode: 'MAIL-001' })).toBe(true);
      expect(hasActiveFilter({ silenceRefused: false })).toBe(true);
    });

    it('关键词草稿一行一个，空行跳过', () => {
      expect(parseKeywordDraft('退款\n\n  发票 \n')).toEqual(['退款', '发票']);
    });

    it('域名 / 标签码草稿逗号分隔，留空 = 该维度不参与判定', () => {
      expect(parseCodeDraft('example.com, vip-partner.com')).toEqual([
        'example.com',
        'vip-partner.com',
      ]);
      expect(parseCodeDraft('')).toEqual([]);
    });
  });
});
