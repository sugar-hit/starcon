import { describe, expect, it } from 'vitest';
import {
  dimensionScoreText,
  emptyState,
  severityLabel,
  triggerText,
  unknownDimensions,
  unknownFindings,
  verdictLabel,
  verdictTone,
  violations,
  type CompliancePrecheckDimensionScore,
  type CompliancePrecheckReport,
} from './compliance-precheck-model';

/**
 * 16.2.2.1 合规预检 前端模型的纯函数断言。
 *
 * 🔴 这个页面上最容易做丢的事，前端这一层再挡一道：
 * **「量不到」显示成「未知」而不是 0 分**、**前端不重算后端算出的分数**、
 * **三态空态文案各不相同**。
 */

function dimension(
  overrides: Partial<CompliancePrecheckDimensionScore> = {},
): CompliancePrecheckDimensionScore {
  return {
    dimensionCode: 'CONTENT',
    dimensionLabel: '内容合规',
    weight: 0.3,
    score: 0.75,
    scoreText: '75.00%',
    state: 'SCORED',
    checkItemCount: 4,
    passedItemCount: 3,
    failedItemCount: 1,
    unknownItemCount: 0,
    ownerModule: null,
    unavailableReason: '',
    note: '',
    ...overrides,
  };
}

function report(overrides: Partial<CompliancePrecheckReport> = {}): CompliancePrecheckReport {
  return {
    header: {
      reportId: 'r-1',
      draftId: 'd-1',
      triggerKind: 'DRAFT_SAVE',
      triggerLabel: '保存草稿时的即时反馈',
      state: 'SCORED',
      overallScore: 0.75,
      overallState: 'SCORED',
      overallScoreText: '75.00%',
      verdict: 'ATTENTION',
      hasUnknownDimensions: true,
      totalDimensionCount: 6,
      scoredDimensionCount: 1,
      unknownDimensionCount: 5,
      checkItemCount: 10,
      failedItemCount: 1,
      unknownItemCount: 6,
      platformCode: 'AMAZON',
      marketplaceCode: 'US',
      locale: 'en-US',
      checkedAt: '2026-10-20T10:00:00Z',
    },
    dimensions: [dimension()],
    findings: [],
    remediation: [],
    ...overrides,
  };
}

describe('16.2.2.1 维度得分：不可知显示成「未知」而不是 0 分', () => {
  it('量不到的维度显示「未知」，不显示 0%', () => {
    const unknown = dimension({
      dimensionCode: 'CERTIFICATION',
      dimensionLabel: '认证缺失',
      score: null,
      scoreText: '未知',
      state: 'UNKNOWN',
      passedItemCount: 0,
      failedItemCount: 0,
      unknownItemCount: 1,
      ownerModule: '16.2.2.2',
      unavailableReason: '认证档案在 16.2.2.2（未交付）',
    });
    expect(dimensionScoreText(unknown)).toBe('未知');
    expect(dimensionScoreText(unknown)).not.toBe('0%');
    expect(dimensionScoreText(unknown)).not.toBe('0.00%');
  });

  it('可评的维度原样显示后端算好的展示串（前端不重算）', () => {
    const scored = dimension({ score: 0.75, scoreText: '75.00%' });
    expect(dimensionScoreText(scored)).toBe('75.00%');
    // 后端给什么就显示什么：前端不自己乘 100、不自己四舍五入
    const odd = dimension({ score: 0.3333, scoreText: '33.33%' });
    expect(dimensionScoreText(odd)).toBe('33.33%');
  });

  it('后端说 SCORED 但分数是 null 时也显示「未知」（不显示 0）', () => {
    const broken = dimension({ score: null, scoreText: '未知' });
    expect(dimensionScoreText(broken)).toBe('未知');
  });
});

describe('16.2.2.1 量不到的条目必须显式出现在清单里', () => {
  it('unknownDimensions 只收 state=UNKNOWN 的维度', () => {
    const r = report({
      dimensions: [
        dimension(),
        dimension({ dimensionCode: 'HAZMAT', state: 'UNKNOWN', score: null, scoreText: '未知' }),
      ],
    });
    expect(unknownDimensions(r).map((d) => d.dimensionCode)).toEqual(['HAZMAT']);
  });

  it('violations 只收 VIOLATION，unknownFindings 只收 UNKNOWN', () => {
    const r = report({
      findings: [
        {
          findingId: 'f-1',
          dimensionCode: 'CONTENT',
          dimensionLabel: '内容合规',
          checkCode: 'FIELD_MAX_LENGTH',
          subjectRef: 'TITLE',
          location: '标题',
          severity: 'BLOCKING',
          state: 'VIOLATION',
          message: '标题长度 250 超出平台上限 200',
          suggestion: '删到 200 字以内',
          ownerModule: '16.2.2.1',
          evidence: {},
        },
        {
          findingId: 'f-2',
          dimensionCode: 'CONTENT',
          dimensionLabel: '内容合规',
          checkCode: 'CONTENT_RULE_LIBRARY',
          subjectRef: 'WORD_AND_INFRINGEMENT',
          location: '标题 / 要点 / 描述 / 图片',
          severity: 'INFO',
          state: 'UNKNOWN',
          message: '违禁词扫描归 4.2.3.2',
          suggestion: '接入 4.2.3.2 后自动可评',
          ownerModule: '4.2.3.2',
          evidence: {},
        },
      ],
    });
    expect(violations(r).map((f) => f.findingId)).toEqual(['f-1']);
    expect(unknownFindings(r).map((f) => f.findingId)).toEqual(['f-2']);
  });

  it('没有报告时返回空数组而不是抛错', () => {
    expect(unknownDimensions(null)).toEqual([]);
    expect(violations(null)).toEqual([]);
    expect(unknownFindings(null)).toEqual([]);
  });
});

describe('16.2.2.1 结论与标签', () => {
  it('三种结论各有各的说法与色调', () => {
    expect(verdictLabel('PASS')).toBe('无违规');
    expect(verdictLabel('ATTENTION')).toBe('有违规');
    expect(verdictLabel('UNKNOWN')).toBe('判不了');
    expect(verdictTone('PASS')).toBe('success');
    expect(verdictTone('ATTENTION')).toBe('danger');
    expect(verdictTone('UNKNOWN')).toBe('neutral');
  });

  it('严重度与触发语义有中文说法', () => {
    expect(severityLabel('BLOCKING')).toBe('阻断项');
    expect(severityLabel('WARNING')).toBe('警告项');
    expect(triggerText('DRAFT_SAVE')).toBe('保存草稿时的即时反馈');
    expect(triggerText('PUBLISH_GATE')).toBe('发布前的强制校验');
  });
});

describe('16.2.2.1 三态空态文案各不相同', () => {
  it('没有草稿 / 没有问题 / 全部量不到，三段文案互不相同', () => {
    const noDraft = emptyState('NO_DRAFT');
    const noFinding = emptyState('NO_FINDING');
    const allUnknown = emptyState('ALL_UNKNOWN');

    expect(noDraft.title).not.toBe(noFinding.title);
    expect(noFinding.title).not.toBe(allUnknown.title);
    expect(noDraft.title).not.toBe(allUnknown.title);
    expect(noDraft.description).not.toBe(noFinding.description);
    expect(noFinding.description).not.toBe(allUnknown.description);

    // 🔴 关键：量不到那一段必须说清「未知不是 0 分」
    expect(allUnknown.description).toContain('未知');
    expect(allUnknown.description).not.toContain('0%');
    // 没有草稿那一段要指去 4.2.1.1 编辑器
    expect(noDraft.description).toContain('4.2.1.1');
  });
});
