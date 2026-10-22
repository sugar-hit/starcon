import { describe, expect, it } from 'vitest';
import {
  BASE,
  CONCLUSION_LABELS,
  CONCLUSIONS,
  EXEMPTION_STATUS_LABELS,
  REASON_CODES,
  SCENES,
  SCENE_LABELS,
  SUBJECT_TYPES,
  blockingReasons,
  conclusionLabel,
  conclusionTone,
  decisionEmptyKind,
  decisionEmptyState,
  exemptionEmptyKind,
  exemptionEmptyState,
  subjectText,
  undecidableReasons,
  validityText,
  waivedReasons,
  type ComplianceInterceptDecision,
  type ComplianceInterceptExemptionRow,
  type ComplianceInterceptReasonRow,
} from './compliance-intercept-model';

/**
 * 16.2.1.2 拦截执行 前端模型的纯逻辑单测。
 *
 * 🔴 这组用例守的是页面上最容易做丢的三件事：
 *
 * 1. **「判不了」与「通过」分开** —— 结论四值、`conclusionTone` 不把它染成绿色。
 * 2. **因豁免放行的原因照样显示**（不隐藏），并且**不算**成阻断。
 * 3. **空态四态分开说** —— 「没有判定」「筛选无结果」「没有阻断」是三句不同的话。
 */

/** 造一条判定。 */
function decision(
  conclusion: string,
  reasons: Partial<ComplianceInterceptReasonRow>[],
): ComplianceInterceptDecision {
  return {
    decisionId: 'd-1',
    scene: 'LISTING_PUBLISH',
    sceneLabel: '刊登发布前',
    subjectType: 'PRODUCT',
    subjectTypeLabel: '商品',
    subjectId: 'sku-1',
    shopId: null,
    platformId: null,
    marketplaceId: null,
    conclusion,
    conclusionLabel: CONCLUSION_LABELS[conclusion] ?? conclusion,
    blocked: conclusion === 'BLOCK',
    releaseGranted: conclusion === 'PASS' || conclusion === 'WARN',
    ruleCount: 1,
    reasonCount: reasons.length,
    blockingReasonCount: 0,
    waivedReasonCount: 0,
    undecidableReasonCount: 0,
    reasons: reasons.map((reason, index) => ({
      reasonId: `r-${index}`,
      reasonCode: 'WORD_TERM_HIT',
      reasonCodeLabel: '命中词条',
      reasonText: '原因',
      ruleId: 'rule-1',
      ruleVersionId: 'v-1',
      ruleCode: 'R-1',
      ruleType: 'PROHIBITED_WORD',
      severity: 'BLOCKING',
      fieldKey: 'TITLE',
      matchedTerm: '违禁词',
      waived: false,
      exemptionId: null,
      undecidable: false,
      ...reason,
    })),
    exemptions: [],
    remedies: [],
    decidedAt: '2026-10-23T10:00:00Z',
    decidedBy: null,
    note: '',
  };
}

/** 造一张豁免单。 */
function exemption(
  status: string,
  validFrom: string,
  validTo: string,
): ComplianceInterceptExemptionRow {
  return {
    exemptionId: 'e-1',
    scene: 'LISTING_PUBLISH',
    sceneLabel: '刊登发布前',
    subjectType: 'PRODUCT',
    subjectTypeLabel: '商品',
    subjectId: 'sku-1',
    ruleId: 'rule-1',
    ruleCode: 'R-1',
    ruleTitle: '规则',
    status,
    statusLabel: EXEMPTION_STATUS_LABELS[status] ?? status,
    applicantId: 'u-1',
    applicantReason: '理由',
    approverId: null,
    decisionNote: '',
    validFrom,
    validTo,
    expired: false,
    effective: false,
    decidable: true,
    decidedAt: null,
    createdAt: '2026-10-23T10:00:00Z',
  };
}

describe('16.2.1.2 结论是三态 + 一个「判不了」', () => {
  it('结论值域是四个，「判不了」不在通过里', () => {
    expect(CONCLUSIONS).toEqual(['PASS', 'WARN', 'BLOCK', 'UNDECIDABLE']);
    expect(CONCLUSION_LABELS.UNDECIDABLE).toBe('判不了');
  });

  it('「判不了」既不是危险色也不是成功色', () => {
    expect(conclusionTone('PASS')).toBe('success');
    expect(conclusionTone('WARN')).toBe('warning');
    expect(conclusionTone('BLOCK')).toBe('danger');
    // 🔴 判不了用中性灰：用绿色会放行，用红色会冤枉规则。
    expect(conclusionTone('UNDECIDABLE')).toBe('neutral');
  });

  it('结论人话优先用后端给的', () => {
    const item = decision('UNDECIDABLE', []);
    item.conclusionLabel = '判不了（规则读不到）';
    expect(conclusionLabel(item)).toBe('判不了（规则读不到）');
  });

  it('判不了类的原因能被单独挑出来', () => {
    const item = decision('UNDECIDABLE', [
      { reasonCode: 'RULE_SET_EMPTY', undecidable: true, severity: null },
    ]);
    expect(undecidableReasons(item)).toHaveLength(1);
    expect(undecidableReasons(item)[0]?.reasonCode).toBe('RULE_SET_EMPTY');
  });
});

describe('16.2.1.2 因豁免放行的原因照样显示', () => {
  it('被豁免放行的原因不算成阻断', () => {
    const item = decision('PASS', [{ waived: true, exemptionId: 'e-1', severity: 'BLOCKING' }]);
    expect(waivedReasons(item)).toHaveLength(1);
    expect(blockingReasons(item)).toHaveLength(0);
    expect(waivedReasons(item)[0]?.exemptionId).toBe('e-1');
  });

  it('未豁免的阻断级原因才算阻断', () => {
    const item = decision('BLOCK', [{ severity: 'BLOCKING' }]);
    expect(blockingReasons(item)).toHaveLength(1);
    expect(waivedReasons(item)).toHaveLength(0);
  });
});

describe('16.2.1.2 空态分开说', () => {
  it('判定空态：没有判定 / 筛选无结果 / 没有阻断是三句不同的话', () => {
    expect(decisionEmptyKind(0, false, 0)).toBe('NO_DATA');
    expect(decisionEmptyKind(0, true, 0)).toBe('NO_MATCH');
    expect(decisionEmptyKind(5, true, 0)).toBe('NO_BLOCK');
    const noData = decisionEmptyState('NO_DATA');
    const noMatch = decisionEmptyState('NO_MATCH');
    const noBlock = decisionEmptyState('NO_BLOCK');
    expect(noData.title).not.toBe(noMatch.title);
    expect(noMatch.title).not.toBe(noBlock.title);
    expect(noBlock.description).toContain('判不了');
  });

  it('豁免空态：没有豁免 / 筛选无结果 / 没有待审批是三句不同的话', () => {
    expect(exemptionEmptyKind(0, false, 0)).toBe('NO_DATA');
    expect(exemptionEmptyKind(0, true, 0)).toBe('NO_MATCH');
    expect(exemptionEmptyKind(3, false, 0)).toBe('NO_PENDING');
    expect(exemptionEmptyState('NO_PENDING').description).toContain('待审批');
  });
});

describe('16.2.1.2 值域与显示', () => {
  it('拦截点、校验对象、原因码都是受控值域', () => {
    expect(SCENES).toEqual(['LISTING_PUBLISH', 'SHIPMENT', 'WITHDRAWAL']);
    expect(SUBJECT_TYPES).toEqual(['PRODUCT', 'ORDER', 'SHOP']);
    expect(REASON_CODES).toContain('RULE_SET_EMPTY');
    expect(REASON_CODES).toContain('WORD_TERM_HIT');
    expect(SCENE_LABELS.LISTING_PUBLISH).toBe('刊登发布前');
  });

  it('白名单前缀是独立的一条', () => {
    expect(BASE).toBe('channel/compliance-intercepts');
  });

  it('主体标签与有效期可读', () => {
    const item = decision('PASS', []);
    expect(subjectText(item)).toBe('商品 · sku-1');
    expect(validityText(exemption('PENDING', '2026-12-01T00:00:00Z', '2026-12-08T00:00:00Z'))).toBe(
      '2026-12-01 ~ 2026-12-08',
    );
  });
});
