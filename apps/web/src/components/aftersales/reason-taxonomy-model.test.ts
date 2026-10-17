import { describe, expect, it } from 'vitest';
import type {
  AftersalesReasonAttributionView,
  AftersalesReasonReport,
  AftersalesReasonTree,
  AftersalesReasonTreeNode,
} from '../../../../../packages/api-contracts/src/index';
import {
  type AftersalesReasonDraft,
  assignableNodes,
  draftFromAttribution,
  draftItemCount,
  draftWeightSum,
  emptyDraft,
  flattenTree,
  nodePresentation,
  notePresentation,
  pickPrimary,
  setWeight,
  shareText,
  toAttributionCreate,
  toggleSecondary,
  validateAttributionDraft,
  weightSumHint,
} from './reason-taxonomy-model';

/**
 * 11.1.2.1 售后原因分类前端的**判据**测试。
 *
 * <p>本文件只测纯函数 —— 真正重要的不是「界面画得对不对」，
 * 而是<b>界面构造出的请求与后端接受的请求是不是同一套判据</b>。
 * 加粗句「必须强制选择分类而不是留自由文本」在界面上的全部重量都压在这里：
 * 运营点「保存」之前，本地校验必须给出与后端<b>同一句原因</b>，
 * 而不是让他提交后被 400 打回、再也搞不清哪里不对。
 *
 * <p>⚠️ 注意这些测试**不能**替代后端校验（有人会直接调接口，
 * DB 的提交期约束触发器才是最后一道）；它们保证的是「界面上根本构造不出非法请求」。
 */

const uuid = (n: number) => `0000000${n}-0000-0000-0000-000000000000`;

function node(over: Partial<AftersalesReasonTreeNode> = {}): AftersalesReasonTreeNode {
  return {
    id: uuid(1),
    parentId: null,
    categoryCode: 'ROOT.QUALITY_DEFECT',
    categoryName: '质量问题',
    rootKind: 'QUALITY_DEFECT',
    rootKindLabel: '质量问题',
    categoryStatus: 'ACTIVE',
    categoryStatusLabel: '启用',
    sortOrder: 0,
    depth: 1,
    childCount: 0,
    attributionCount: 0,
    assignable: true,
    notAssignableReason: null,
    codePath: 'ROOT.QUALITY_DEFECT',
    namePath: '质量问题',
    description: '',
    ...over,
  };
}

const quality = node({ id: uuid(1), categoryCode: 'QD', categoryName: '质量问题' });
const size = node({
  id: uuid(2),
  categoryCode: 'SM',
  categoryName: '尺寸不符',
  rootKind: 'SIZE_MISMATCH',
  rootKindLabel: '尺寸不符',
  sortOrder: 10,
});
const logistics = node({
  id: uuid(3),
  categoryCode: 'LD',
  categoryName: '物流损坏',
  rootKind: 'LOGISTICS_DAMAGE',
  rootKindLabel: '物流损坏',
  sortOrder: 20,
});

describe('加粗句：必须强制选择分类而不是留自由文本', () => {
  it('一个原因都没选 ⇒ 拒绝，且报错说清「不能只写说明」', () => {
    const draft: AftersalesReasonDraft = { ...emptyDraft(uuid(9)), note: '只是一段话' };
    const v = validateAttributionDraft(draft);
    expect(v.ok).toBe(false);
    expect(v.severity).toBe('error');
    // 报错必须指向「没选分类」这件事，而不是笼统的「保存失败」。
    expect(v.message).toContain('必须选一个主因');
    expect(v.message).toContain('不能只写一段说明文字');
  });

  it('主因是单值 ⇒ 类型上就构造不出「两个主因」', () => {
    const draft = pickPrimary(pickPrimary(emptyDraft(uuid(9)), quality), size);
    expect(draft.primary?.categoryId).toBe(size.id);
    // 换主因时旧主因**降级成次因**而不是被丢掉：
    // 运营改判主因时，前一个原因往往仍然成立。
    expect(draft.secondaries.map((s) => s.categoryId)).toEqual([quality.id]);
  });

  it('同一个分类不能既是主因又是次因', () => {
    const draft = pickPrimary(emptyDraft(uuid(9)), quality);
    const same = toggleSecondary(draft, quality);
    expect(same.secondaries).toHaveLength(0);
    const v = validateAttributionDraft(same);
    expect(v.ok).toBe(true);
  });

  it('权重为空 / 为 0 / 大于 1 一律被拒（不给默认 1）', () => {
    const base = pickPrimary(emptyDraft(uuid(9)), quality);
    for (const bad of ['', '0', '-0.5', '1.000001', 'abc']) {
      const draft = setWeight(base, quality.id, bad);
      expect(validateAttributionDraft(draft).ok, `权重 ${bad} 必须被拒`).toBe(false);
    }
  });

  it('权重恰好 1 合法（只有一个原因时）', () => {
    const draft = setWeight(pickPrimary(emptyDraft(uuid(9)), quality), quality.id, '1');
    expect(validateAttributionDraft(draft).ok).toBe(true);
  });

  it('合法归因转成后端入参：主因在前，权重是数字', () => {
    let draft = pickPrimary(emptyDraft(uuid(9)), quality, '0.700000');
    draft = toggleSecondary(draft, size, '0.300000');
    const payload = toAttributionCreate(draft);
    expect(payload.caseId).toBe(uuid(9));
    expect(payload.items).toHaveLength(2);
    expect(payload.items[0]).toEqual({ categoryId: quality.id, isPrimary: true, weight: 0.7 });
    expect(payload.items[1]).toEqual({ categoryId: size.id, isPrimary: false, weight: 0.3 });
    expect(typeof payload.items[0]!.weight).toBe('number');
  });

  it('draftWeightSum 与条目数', () => {
    let draft = pickPrimary(emptyDraft(uuid(9)), quality, '0.600000');
    draft = toggleSecondary(draft, size, '0.300000');
    draft = toggleSecondary(draft, logistics, '0.100000');
    expect(draftItemCount(draft)).toBe(3);
    expect(draftWeightSum(draft)).toBe('1.000000');
  });

  it('toggleSecondary 取消勾选后条目消失', () => {
    let draft = pickPrimary(emptyDraft(uuid(9)), quality);
    draft = toggleSecondary(draft, size);
    expect(draftItemCount(draft)).toBe(2);
    draft = toggleSecondary(draft, size);
    expect(draftItemCount(draft)).toBe(1);
  });
});

describe('自由文本只是补充，不是归因本身', () => {
  it('有文字 ⇒ 标成「补充说明（非归因本身）」且 muted', () => {
    const p = notePresentation('  外箱压扁  ');
    expect(p.text).toBe('外箱压扁');
    expect(p.label).toContain('非归因本身');
    expect(p.muted).toBe(true);
  });

  it('空 ⇒ 明确说「可留空」，而不是「原因」', () => {
    const p = notePresentation('');
    expect(p.text).toBe('');
    expect(p.label).toContain('可留空');
    expect(p.muted).toBe(true);
  });

  it('未归因的单与归因为空是两件事', () => {
    const view: AftersalesReasonAttributionView = {
      caseId: uuid(9),
      caseNo: 'AS1',
      attributed: false,
      current: null,
      versionCount: 0,
      noteSemantics: '',
      enforcementNote: '',
    };
    const draft = draftFromAttribution(view);
    expect(draft.primary).toBeNull();
    expect(draft.secondaries).toHaveLength(0);
    // 未归因 ⇒ 草稿是空的，界面据此显示「还没有归因」而不是「归因为空」。
    expect(validateAttributionDraft(draft).ok).toBe(false);
  });

  it('读回已有归因时按 isPrimary 判定主因，不按排序位置', () => {
    const view: AftersalesReasonAttributionView = {
      caseId: uuid(9),
      caseNo: 'AS1',
      attributed: true,
      current: {
        setId: uuid(8),
        note: '复判',
        items: [
          // ⚠️ 主因**故意排在第二位**：后端把主因排第一是展示顺序，
          // 前端按 isPrimary 判定才不会在排序规则变更时静默取错。
          { id: uuid(1), categoryId: size.id, categoryCode: 'SM', categoryName: '尺寸不符', rootKind: 'SIZE_MISMATCH', rootKindLabel: '尺寸不符', depth: 1, isPrimary: false, weight: 0.3, codePath: 'SM' },
          { id: uuid(2), categoryId: quality.id, categoryCode: 'QD', categoryName: '质量问题', rootKind: 'QUALITY_DEFECT', rootKindLabel: '质量问题', depth: 1, isPrimary: true, weight: 0.7, codePath: 'QD' },
        ],
        itemCount: 2,
        primaryCount: 1,
        weightSum: 1,
        attributedBy: null,
        attributedAt: '2026-03-01T00:00:00Z',
        current: true,
        supersededAt: null,
      },
      versionCount: 2,
      noteSemantics: '',
      enforcementNote: '',
    };
    const draft = draftFromAttribution(view);
    expect(draft.primary?.categoryId).toBe(quality.id);
    expect(draft.secondaries.map((s) => s.categoryId)).toEqual([size.id]);
    expect(draft.note).toBe('复判');
    expect(validateAttributionDraft(draft).ok).toBe(true);
  });
});

describe('分类树展示口径：判定全部来自后端', () => {
  it('可归因 ⇒ assignable，无提示', () => {
    const p = nodePresentation(quality);
    expect(p.tone).toBe('assignable');
    expect(p.hint).toBe('');
    expect(p.level).toBe(0);
  });

  it('自己停用 ⇒ disabled', () => {
    const p = nodePresentation(node({ categoryStatus: 'DISABLED', assignable: false, notAssignableReason: '分类已停用' }));
    expect(p.tone).toBe('disabled');
    expect(p.hint).toContain('已停用');
  });

  it('上级停用 ⇒ blocked（本地数据看不出来，必须用后端给的 assignable）', () => {
    const p = nodePresentation(
      node({ depth: 2, categoryStatus: 'ACTIVE', assignable: false, notAssignableReason: '上级分类已停用' }),
    );
    expect(p.tone).toBe('blocked');
    expect(p.hint).toContain('上级');
    expect(p.level).toBe(1);
    expect(p.prefix).toContain('└');
  });

  it('flattenTree 按 depth → sortOrder → 编码 排序', () => {
    const tree: AftersalesReasonTree = {
      nodes: [
        node({ id: uuid(3), categoryCode: 'C', categoryName: 'C', sortOrder: 5, depth: 1 }),
        node({ id: uuid(1), categoryCode: 'A', categoryName: 'A', sortOrder: 5, depth: 1 }),
        node({ id: uuid(2), categoryCode: 'B', categoryName: 'B', sortOrder: 1, depth: 1 }),
        node({ id: uuid(4), categoryCode: 'D', categoryName: 'D', sortOrder: 0, depth: 2 }),
      ],
      totalCount: 4,
      activeCount: 4,
      disabledCount: 0,
      maxDepth: 3,
      baselineVocabularySeeded: true,
      baselineSeededOnThisRead: false,
      orderedBy: '',
      treeRules: '',
      scope: '',
    };
    expect(flattenTree(tree).map((n) => n.categoryCode)).toEqual(['B', 'A', 'C', 'D']);
  });

  it('assignableNodes 只留后端判定可归因的', () => {
    const tree: AftersalesReasonTree = {
      nodes: [quality, node({ id: uuid(4), categoryCode: 'X', assignable: false, notAssignableReason: '上级分类已停用' })],
      totalCount: 2,
      activeCount: 2,
      disabledCount: 0,
      maxDepth: 3,
      baselineVocabularySeeded: true,
      baselineSeededOnThisRead: false,
      orderedBy: '',
      treeRules: '',
      scope: '',
    };
    // quality 夹具的编码是 QD（见文件头的常量），不是它 default 里的 ROOT.QUALITY_DEFECT。
    expect(assignableNodes(tree).map((n) => n.categoryCode)).toEqual(['QD']);
  });
});

describe('报表展示口径', () => {
  it('占比永远两位小数，分母为 0 显示 0.00% 而不是破折号', () => {
    expect(shareText(0)).toBe('0.00%');
    expect(shareText(66.666)).toBe('66.67%');
    expect(shareText(100)).toBe('100.00%');
    expect(shareText(Number.NaN)).toBe('—');
  });

  it('权重合计只提示不阻断（权重是相对值，不要求合计为 1）', () => {
    expect(weightSumHint('1.000000').tone).toBe('ok');
    expect(weightSumHint('0.700000').tone).toBe('warn');
    expect(weightSumHint('0').text).toContain('0');
  });

  it('报表：七类零也出现 ⇒ 根口径行恒为 7', () => {
    const report: AftersalesReasonReport = {
      rows: [],
      rootTotals: [
        { rootKind: 'QUALITY_DEFECT', rootKindLabel: '质量问题', primaryCaseCount: 0, attributionCaseCount: 0, caseSharePercent: 0 },
        { rootKind: 'DESCRIPTION_MISMATCH', rootKindLabel: '描述不符', primaryCaseCount: 0, attributionCaseCount: 0, caseSharePercent: 0 },
        { rootKind: 'SIZE_MISMATCH', rootKindLabel: '尺寸不符', primaryCaseCount: 0, attributionCaseCount: 0, caseSharePercent: 0 },
        { rootKind: 'LOGISTICS_DAMAGE', rootKindLabel: '物流损坏', primaryCaseCount: 0, attributionCaseCount: 0, caseSharePercent: 0 },
        { rootKind: 'BUYER_REASON', rootKindLabel: '买家原因', primaryCaseCount: 0, attributionCaseCount: 0, caseSharePercent: 0 },
        { rootKind: 'MISTAKE_BUY', rootKindLabel: '误购', primaryCaseCount: 0, attributionCaseCount: 0, caseSharePercent: 0 },
        { rootKind: 'FRAUD_RETURN', rootKindLabel: '欺诈退货', primaryCaseCount: 0, attributionCaseCount: 0, caseSharePercent: 0 },
      ],
      evaluatedAt: '2026-03-01T00:00:00Z',
      attributedCaseCount: 0,
      unattributedCaseCount: 3,
      attributionItemCount: 0,
      totalWeight: 0,
      semantics: '',
      downstreamNote: '',
    };
    expect(report.rootTotals).toHaveLength(7);
    expect(report.rootTotals.every((t) => t.attributionCaseCount === 0)).toBe(true);
    expect(shareText(report.rootTotals[0]!.caseSharePercent)).toBe('0.00%');
  });
});
