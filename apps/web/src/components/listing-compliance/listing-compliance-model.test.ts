import { describe, expect, it } from 'vitest';
import {
  conclusionLabel,
  conclusionTone,
  dimensionStateLabel,
  dimensionStateTone,
  emptyWordInput,
  fieldAnchorId,
  gateSummary,
  matchedTextLabel,
  riskLevelLabel,
  riskLevelTone,
  riskLocationHref,
  riskLocationLabel,
  risksByField,
  scanSummary,
  severityLabel,
  severityTone,
  splitRisks,
  unavailableDimensions,
  wordCategoryLabel,
  type ListingComplianceCapabilities,
  type ListingCompliancePublishGate,
  type ListingComplianceRiskRow,
  type ListingComplianceScanResult,
} from './listing-compliance-model';

/**
 * 4.2.3.2 合规预检 前端纯函数单测。
 *
 * <p>这些函数是页面上「最容易做丢的那几件事」的可执行版本：
 * 阻断项与提醒项必须分区、命中位置要指到具体字段与第几项、
 * 「不知道」不能被渲染成「通过」、结论 INCOMPLETE 不能写成「合规通过」。
 */

function risk(overrides: Partial<ListingComplianceRiskRow> = {}): ListingComplianceRiskRow {
  return {
    riskId: 'r-1',
    scanId: 's-1',
    dimension: 'PROHIBITED_WORD',
    dimensionLabel: '违禁词',
    severity: 'BLOCKING',
    riskLevel: 'HIGH',
    code: 'WORD_HIT',
    fieldKey: 'TITLE',
    fieldLabel: '标题',
    matchedText: '违禁',
    occurrenceIndex: -1,
    ruleSource: '平台违禁词规则',
    ruleOwnerModule: null,
    suggestion: '删掉这个词',
    message: '内容标题命中违禁',
    ownerModule: '4.2.3.2',
    evidence: {},
    status: 'OPEN',
    createdAt: '2026-10-20T00:00:00Z',
    ...overrides,
  };
}

function scan(overrides: Partial<ListingComplianceScanResult> = {}): ListingComplianceScanResult {
  return {
    scanId: 's-1',
    draftId: 'd-1',
    shopId: 'shop-1',
    platformCode: 'AMAZON',
    marketplaceCode: 'US',
    locale: 'en-US',
    state: 'COMPLETED',
    conclusion: 'PASS',
    triggerSource: 'MANUAL',
    riskCount: 0,
    blockingCount: 0,
    warningCount: 0,
    evaluatedDimensionCount: 7,
    totalDimensionCount: 7,
    dimensions: [],
    risks: [],
    scannedAt: '2026-10-20T00:00:00Z',
    ...overrides,
  };
}

describe('4.2.3.2 阻断项与提醒项必须分区', () => {
  it('按 severity 拆成两段，且已处理的项不计入', () => {
    const { blockers, warnings } = splitRisks([
      risk({ riskId: 'b1', severity: 'BLOCKING' }),
      risk({ riskId: 'w1', severity: 'WARNING' }),
      risk({ riskId: 'b2', severity: 'BLOCKING', status: 'RESOLVED' }),
      risk({ riskId: 'w2', severity: 'WARNING', status: 'IGNORED' }),
    ]);
    expect(blockers.map((r) => r.riskId)).toEqual(['b1']);
    expect(warnings.map((r) => r.riskId)).toEqual(['w1']);
  });

  it('阻断项与提醒项的色调不同', () => {
    expect(severityTone('BLOCKING')).toBe('destructive');
    expect(severityTone('WARNING')).toBe('warning');
    expect(severityLabel('BLOCKING')).toBe('阻断项');
    expect(severityLabel('WARNING')).toBe('提醒项');
  });
});

describe('4.2.3.2 命中位置要能定位到具体字段', () => {
  it('列表字段要指到第几项，普通字段不指序号', () => {
    expect(riskLocationLabel(risk({ fieldKey: 'TITLE', fieldLabel: '标题' }))).toBe('标题');
    expect(
      riskLocationLabel(risk({ fieldKey: 'BULLETS', fieldLabel: '卖点', occurrenceIndex: 1 })),
    ).toBe('卖点 · 第 2 项');
  });

  it('跳转锚点由字段键决定', () => {
    expect(fieldAnchorId('BULLETS')).toBe('compliance-field-BULLETS');
    expect(riskLocationHref(risk({ fieldKey: 'BULLETS' }))).toBe('#compliance-field-BULLETS');
  });

  it('按字段归拢，供跳转落点用', () => {
    const buckets = risksByField([
      risk({ fieldKey: 'TITLE', fieldLabel: '标题' }),
      risk({ fieldKey: 'TITLE', fieldLabel: '标题' }),
      risk({ fieldKey: 'IMAGES', fieldLabel: '图片', severity: 'WARNING' }),
    ]);
    expect(buckets[0]).toMatchObject({ fieldKey: 'TITLE', count: 2, blockingCount: 2 });
    expect(buckets.map((b) => b.fieldKey).sort()).toEqual(['IMAGES', 'TITLE']);
  });

  it('命中的原文没有时写「—」，不写空串', () => {
    expect(matchedTextLabel(risk({ matchedText: null }))).toBe('—');
    expect(matchedTextLabel(risk({ matchedText: '  ' }))).toBe('—');
    expect(matchedTextLabel(risk({ matchedText: '违禁' }))).toBe('违禁');
  });
});

describe('4.2.3.2 「不知道」不能被渲染成「通过」', () => {
  it('维度状态 UNKNOWN 单独成态', () => {
    expect(dimensionStateLabel('UNKNOWN')).toBe('未能判定');
    expect(dimensionStateLabel('CHECKED')).toBe('已判定');
    expect(dimensionStateTone('UNKNOWN')).toBe('warning');
    expect(dimensionStateTone('CHECKED')).toBe('brand');
  });

  it('结论 INCOMPLETE 的文案与色调都不是「通过」', () => {
    expect(conclusionLabel('INCOMPLETE')).toBe('结论不完整');
    expect(conclusionLabel('PASS')).toBe('通过');
    expect(conclusionTone('INCOMPLETE')).toBe('warning');
    expect(conclusionTone('BLOCKED')).toBe('destructive');
  });

  it('扫描摘要按结论分文案', () => {
    expect(
      scanSummary(scan({ conclusion: 'BLOCKED', blockingCount: 2, warningCount: 1 })),
    ).toContain('2 条阻断项');
    expect(
      scanSummary(
        scan({ conclusion: 'INCOMPLETE', evaluatedDimensionCount: 5, totalDimensionCount: 7 }),
      ),
    ).toContain('2 个维度未能判定');
    expect(scanSummary(scan({ conclusion: 'PASS' }))).toContain('7/7');
  });
});

describe('4.2.3.2 发布闸门：未修复不允许发布', () => {
  const gate = (
    overrides: Partial<ListingCompliancePublishGate> = {},
  ): ListingCompliancePublishGate => ({
    draftId: 'd-1',
    allowed: false,
    state: 'SCANNED',
    conclusion: 'BLOCKED',
    blockingCount: 1,
    warningCount: 0,
    evaluatedDimensionCount: 7,
    totalDimensionCount: 7,
    blockers: [risk()],
    warnings: [],
    scanId: 's-1',
    note: '',
    ...overrides,
  });

  it('有阻断项时文案写明「未修复不允许发布（服务端已拦截）」', () => {
    expect(gateSummary(gate())).toContain('未修复不允许发布');
    expect(gateSummary(gate())).toContain('服务端已拦截');
  });

  it('没扫过时不能说成「合规通过」', () => {
    expect(gateSummary(gate({ state: 'NOT_SCANNED', allowed: true }))).toContain('还没有扫描过');
  });

  it('结论不完整时也不是「合规通过」', () => {
    expect(gateSummary(gate({ allowed: true, conclusion: 'INCOMPLETE' }))).toContain(
      '不是「合规通过」',
    );
  });
});

describe('4.2.3.2 风险等级展示', () => {
  it('等级有文案与色调', () => {
    expect(riskLevelLabel('CRITICAL')).toBe('严重');
    expect(riskLevelTone('CRITICAL')).toBe('destructive');
    expect(riskLevelTone('LOW')).toBe('muted');
  });

  it('词的类型有文案', () => {
    expect(wordCategoryLabel('PROHIBITED')).toBe('违禁词');
    expect(wordCategoryLabel('SUPERLATIVE')).toBe('极限词');
  });
});

describe('4.2.3.2 能力声明里未交付的维度要单独列出来', () => {
  it('available=false 的维度带归属模块与影响说明', () => {
    const caps = {
      dimensions: [
        {
          dimension: 'certification',
          description: '认证缺失',
          available: false,
          source: '16.2.2.2 认证与资质（未交付）',
          ownerModule: '16.2.2.2',
          impact: '没有认证档案来源',
        },
        {
          dimension: 'image_compliance',
          description: '图片不合规',
          available: true,
          source: 'catalog',
          ownerModule: '',
          impact: '',
        },
      ],
    } as ListingComplianceCapabilities;
    const missing = unavailableDimensions(caps);
    expect(missing).toHaveLength(1);
    expect(missing[0]?.ownerModule).toBe('16.2.2.2');
    expect(missing[0]?.impact).not.toBe('');
  });
});

describe('4.2.3.2 新建词的表单初值', () => {
  it('规则来源与建议替换留空（它们是风险清单四要素，不能给默认值糊弄过去）', () => {
    const draft = emptyWordInput();
    expect(draft.ruleSource).toBe('');
    expect(draft.suggestion).toBe('');
    expect(draft.category).toBe('PROHIBITED');
    expect(draft.severity).toBe('BLOCKING');
  });
});
