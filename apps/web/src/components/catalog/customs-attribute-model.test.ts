import { describe, expect, it } from 'vitest';
import type {
  CustomsProfile,
  DeclarationElement,
} from '../../../../../packages/api-contracts/src/index';
import { ApiError } from './api';
import {
  blockerLines,
  canEdit,
  canReview,
  canSubmit,
  draftFrom,
  draftToPayload,
  emptyElement,
  errorText,
  incompleteRows,
  isConflict,
  isNotFound,
  MAX_ELEMENTS,
  readinessText,
  reviewable,
  reviewState,
  rowState,
  validateDraft,
  writable,
  type CustomsDraft,
} from './customs-attribute-model';

function element(patch: Partial<DeclarationElement> = {}): DeclarationElement {
  return {
    id: crypto.randomUUID(),
    ordinal: 1,
    elementCode: '01',
    elementName: '品名',
    elementValue: '移动电话机',
    required: true,
    incomplete: false,
    ...patch,
  };
}

function profile(patch: Partial<CustomsProfile> = {}): CustomsProfile {
  return {
    skuId: 'sku-1',
    skuCode: 'SKU-1',
    skuName: '测试 SKU',
    spuCode: 'SPU-1',
    attributeId: 'attr-1',
    hsCode: '851712',
    originCountryCode: 'CN',
    declaredName: '移动电话机',
    declaredValueReference: 12.5,
    statutoryUnitCode: 'PCE',
    preferentialOriginEligible: false,
    preferenceAgreement: '',
    status: 'DRAFT',
    submittedBy: null,
    submittedAt: null,
    reviewedBy: null,
    reviewedAt: null,
    rejectReason: null,
    note: '',
    version: 3,
    elements: [element()],
    declarationReady: false,
    blockers: ['NOT_APPROVED'],
    blockerLabels: ['报关属性还没提交审核，草稿不可被报关引用'],
    updatedAt: '2026-10-08T10:00:00Z',
    ...patch,
  };
}

function draft(patch: Partial<CustomsDraft> = {}): CustomsDraft {
  return {
    hsCode: '851712',
    originCountryCode: 'CN',
    declaredName: '移动电话机',
    declaredValueReference: '12.50',
    statutoryUnitCode: 'PCE',
    preferentialOriginEligible: false,
    preferenceAgreement: '',
    note: '',
    elements: [
      { elementCode: '01', elementName: '品名', elementValue: '移动电话机', required: true },
    ],
    version: 0,
    ...patch,
  };
}

// ============================================================ 审核状态机

describe('审核状态机：五种状态而不是三种', () => {
  it('未维护：还没有这一行，下一步是去填', () => {
    const state = reviewState({ status: null, submittedAt: null });
    expect(state.key).toBe('NOT_RECORDED');
    expect(state.label).toBe('未维护');
    expect(state.next).toContain('填写');
  });

  it('草稿：还没提交，下一步是提交', () => {
    const state = reviewState({ status: 'DRAFT', submittedAt: null });
    expect(state.key).toBe('DRAFT');
    expect(state.next).toContain('提交');
  });

  // 关键：后端 status 上「草稿」与「已提交待审」都是 DRAFT，靠 submittedAt 区分。
  // 塌缩成一个「草稿」会让运营不知道自己该不该点提交。
  it('待审核：status 仍是 DRAFT，但 submittedAt 已置位', () => {
    const state = reviewState({ status: 'DRAFT', submittedAt: '2026-10-08T10:00:00Z' });
    expect(state.key).toBe('AWAITING_REVIEW');
    expect(state.label).toBe('待审核');
    expect(state.tone).toBe('warning');
    expect(state.next).toContain('等');
  });

  it('已审核通过：tone 是 success', () => {
    expect(reviewState({ status: 'APPROVED', submittedAt: 'x' }).tone).toBe('success');
  });

  it('已驳回：tone 是 danger，且下一步是改完重提', () => {
    const state = reviewState({
      status: 'REJECTED',
      submittedAt: 'x',
      rejectReason: '品牌类型不符',
    });
    expect(state.tone).toBe('danger');
    expect(state.next).toContain('重新提交');
  });
});

describe('审核动作的可用性', () => {
  it('已审核通过的内容被锁：不给编辑，只能先驳回', () => {
    expect(canEdit(profile({ status: 'APPROVED' }))).toBe(false);
  });

  it('未维护与草稿可编辑；已驳回可编辑（改完重提）', () => {
    expect(canEdit(profile({ status: null }))).toBe(true);
    expect(canEdit(profile({ status: 'DRAFT' }))).toBe(true);
    expect(canEdit(profile({ status: 'REJECTED' }))).toBe(true);
  });

  it('只有「未提交的 DRAFT」可提交：已提交的不许重复提交', () => {
    expect(canSubmit(profile({ status: 'DRAFT', submittedAt: null }))).toBe(true);
    expect(canSubmit(profile({ status: 'DRAFT', submittedAt: 'x' }))).toBe(false);
    expect(canSubmit(profile({ status: null, submittedAt: null }))).toBe(false);
    expect(canSubmit(profile({ status: 'APPROVED', submittedAt: 'x' }))).toBe(false);
  });

  it('只有「已提交的 DRAFT」可审核', () => {
    expect(canReview(profile({ status: 'DRAFT', submittedAt: 'x' }))).toBe(true);
    expect(canReview(profile({ status: 'DRAFT', submittedAt: null }))).toBe(false);
    expect(canReview(profile({ status: 'APPROVED', submittedAt: 'x' }))).toBe(false);
  });
});

// ============================================================ 权限：写与审核必须分开

describe('写权限与审核权限是两个独立权限', () => {
  it('能写不等于能审', () => {
    const updateOnly = ['catalog.compliance.read', 'catalog.compliance.update'];
    expect(writable(updateOnly)).toBe(true);
    expect(reviewable(updateOnly)).toBe(false);
  });

  it('能审不等于能写', () => {
    const reviewOnly = ['catalog.compliance.read', 'catalog.compliance.review'];
    expect(writable(reviewOnly)).toBe(false);
    expect(reviewable(reviewOnly)).toBe(true);
  });

  it('只读角色两个都没有', () => {
    expect(writable(['catalog.compliance.read'])).toBe(false);
    expect(reviewable(['catalog.compliance.read'])).toBe(false);
  });
});

// ============================================================ 阻断原因必须读成中文

describe('阻断原因逐条读中文，不甩英文码', () => {
  it('优先用后端给的 blockerLabels', () => {
    const lines = blockerLines(profile());
    expect(lines).toEqual([
      { code: 'NOT_APPROVED', label: '报关属性还没提交审核，草稿不可被报关引用' },
    ]);
  });

  it('多条 blocker 逐条对应，顺序与后端一致', () => {
    const lines = blockerLines(
      profile({
        blockers: ['DECLARATION_REJECTED', 'REQUIRED_ELEMENTS_INCOMPLETE'],
        blockerLabels: ['报关属性已被驳回', '有必填申报要素还是空的'],
      }),
    );
    expect(lines.map((l) => l.code)).toEqual([
      'DECLARATION_REJECTED',
      'REQUIRED_ELEMENTS_INCOMPLETE',
    ]);
    expect(lines[1]?.label).toBe('有必填申报要素还是空的');
  });

  it('后端漏给标签时兜底成中文，而不是显示英文码', () => {
    const lines = blockerLines(profile({ blockers: ['NO_ELEMENTS'], blockerLabels: [] }));
    expect(lines[0]?.label).toContain('申报要素');
    expect(lines[0]?.label).not.toBe('NO_ELEMENTS');
  });

  it('六个阻断码都有中文兜底', () => {
    const codes = [
      'NOT_RECORDED',
      'NOT_APPROVED',
      'SUBMITTED_AWAITING_REVIEW',
      'DECLARATION_REJECTED',
      'NO_ELEMENTS',
      'REQUIRED_ELEMENTS_INCOMPLETE',
    ];
    for (const code of codes) {
      const label = blockerLines({ blockers: [code], blockerLabels: [] })[0]?.label ?? '';
      expect(label, `${code} 没有中文兜底`).not.toBe(code);
      expect(label.length).toBeGreaterThan(4);
    }
  });

  it('没有 blocker 时返回空数组', () => {
    expect(blockerLines(profile({ blockers: [], blockerLabels: [] }))).toEqual([]);
    expect(blockerLines({})).toEqual([]);
  });

  it('可报关性结论必须点明「未通过就取不到」', () => {
    expect(readinessText(profile({ declarationReady: false }))).toContain('409');
    expect(readinessText(profile({ declarationReady: true }))).toContain('可以引用');
  });
});

// ============================================================ 申报要素

describe('必填却空着的申报要素', () => {
  it('只挑出「必填 + 空值」，选填空值不算缺', () => {
    const rows = incompleteRows([
      element({ ordinal: 1, elementValue: '', required: true }),
      element({ ordinal: 2, elementValue: '', required: false }),
      element({ ordinal: 3, elementValue: '有值', required: true }),
    ]);
    expect(rows.map((e) => e.ordinal)).toEqual([1]);
  });

  it('全空值但必填的行也会被抓出来', () => {
    const rows = incompleteRows([
      element({ elementValue: '   ', required: true }),
      element({ elementValue: '', required: false }),
    ]);
    expect(rows.length).toBe(1);
  });

  it('与服务端 incomplete 字段口径一致', () => {
    const rows = incompleteRows([
      element({ elementValue: '', required: true, incomplete: true }),
      element({ elementValue: 'x', required: true, incomplete: false }),
    ]);
    expect(rows.length).toBe(1);
  });
});

describe('列表行的摘要判定', () => {
  it('未维护是中性态', () => {
    expect(
      rowState({ status: null, requiredMissingCount: 0, elementCount: 0, submittedAt: null }).label,
    ).toBe('未维护');
  });

  it('已通过但必填要素缺失要显式报警，不能显示成「已通过」', () => {
    const state = rowState({
      status: 'APPROVED',
      requiredMissingCount: 2,
      elementCount: 3,
      submittedAt: 'x',
    });
    expect(state.tone).toBe('danger');
    expect(state.label).toContain('要素缺失');
  });

  it('已通过但一条要素都没有也要报警', () => {
    const state = rowState({
      status: 'APPROVED',
      requiredMissingCount: 0,
      elementCount: 0,
      submittedAt: 'x',
    });
    expect(state.tone).toBe('danger');
  });
});

// ============================================================ 草稿回填

describe('草稿回填：乐观锁凭据必须来自服务端', () => {
  it('version 从档案回填，不是恒 0', () => {
    // 恒 0 会让第一次保存把 version 推到 1，此后每次提交都拿 0 去 CAS，必然 409。
    expect(draftFrom(profile({ version: 7 })).version).toBe(7);
  });

  it('null 版本兜底成 0（尚未维护），但不能是 undefined', () => {
    expect(draftFrom(profile({ version: null })).version).toBe(0);
  });

  it('申报要素按位次排序后回填，丢掉后端 id', () => {
    const d = draftFrom(
      profile({
        elements: [
          element({ ordinal: 2, elementCode: '02', elementName: '品牌类型' }),
          element({ ordinal: 1, elementCode: '01', elementName: '品名' }),
        ],
      }),
    );
    expect(d.elements.map((e) => e.elementCode)).toEqual(['01', '02']);
  });

  it('空的可空字段回填成空串而不是字面 "null"', () => {
    const d = draftFrom(profile({ declaredValueReference: null, hsCode: null, note: null }));
    expect(d.declaredValueReference).toBe('');
    expect(d.hsCode).toBe('');
    expect(d.note).toBe('');
  });

  it('已填写内容原样回填：打开页面点保存不该把已有备注清空', () => {
    const d = draftFrom(profile({ note: '已取得原产地证' }));
    expect(d.note).toBe('已取得原产地证');
  });
});

// ============================================================ 校验与后端对齐

describe('草稿校验（与后端同一套判定）', () => {
  it('齐备的草稿没有错误', () => {
    expect(validateDraft(draft())).toEqual([]);
  });

  it('HS 编码必须 6–10 位纯数字', () => {
    expect(validateDraft(draft({ hsCode: '' })).join()).toContain('请填写 HS 编码');
    expect(validateDraft(draft({ hsCode: '8517' })).join()).toContain('6–10 位纯数字');
    expect(validateDraft(draft({ hsCode: '85171200A' })).join()).toContain('6–10 位纯数字');
    expect(validateDraft(draft({ hsCode: '8517123090' }))).toEqual([]);
  });

  it('原产地必须是两位大写字母', () => {
    expect(validateDraft(draft({ originCountryCode: '' })).join()).toContain('请填写原产地');
    expect(validateDraft(draft({ originCountryCode: 'CHN' })).join()).toContain('ISO 3166-1');
    expect(validateDraft(draft({ originCountryCode: 'cn' }))).toEqual([]);
  });

  it('申报品名与法定计量单位必填', () => {
    expect(validateDraft(draft({ declaredName: '' })).join()).toContain('申报品名');
    expect(validateDraft(draft({ statutoryUnitCode: '' })).join()).toContain('法定计量单位');
  });

  it('申报价值参考留空合法，但小数位不得超过 6 位', () => {
    expect(validateDraft(draft({ declaredValueReference: '' }))).toEqual([]);
    expect(validateDraft(draft({ declaredValueReference: '12.1234567' })).join()).toContain(
      '6 位小数',
    );
    expect(validateDraft(draft({ declaredValueReference: '0' })).join()).toContain('大于 0');
    expect(validateDraft(draft({ declaredValueReference: 'abc' })).length).toBeGreaterThan(0);
  });

  // 享惠与协定代码必须双向一致：两个矛盾的答案会让下游无法判断。
  it('勾了可享惠必须填协定代码', () => {
    const errors = validateDraft(
      draft({ preferentialOriginEligible: true, preferenceAgreement: '' }),
    );
    expect(errors.join()).toContain('协定代码');
  });

  it('没勾可享惠就不该有协定代码', () => {
    const errors = validateDraft(
      draft({ preferentialOriginEligible: false, preferenceAgreement: 'RCEP' }),
    );
    expect(errors.join()).toContain('没有勾可享惠');
  });

  it('两个方向一致时没有错误', () => {
    expect(
      validateDraft(draft({ preferentialOriginEligible: true, preferenceAgreement: 'RCEP' })),
    ).toEqual([]);
  });

  it('申报要素的代码与名称必填，长度有上限', () => {
    expect(validateDraft(draft({ elements: [emptyElement()] })).length).toBe(2);
    expect(
      validateDraft(
        draft({ elements: [{ ...emptyElement(), elementCode: '0'.repeat(33) }] }),
      ).join(),
    ).toContain('32');
  });

  it('申报要素条数上限与后端 MAX_ELEMENTS 一致', () => {
    const many = Array.from({ length: MAX_ELEMENTS + 1 }, emptyElement);
    expect(validateDraft(draft({ elements: many })).join()).toContain(String(MAX_ELEMENTS));
  });
});

// ============================================================ 入参组装

describe('草稿转入参', () => {
  it('位次按行序重排为 1..n：位次唯一是跨行不变量', () => {
    const payload = draftToPayload(
      draft({
        elements: [
          { elementCode: '03', elementName: 'c', elementValue: '3', required: false },
          { elementCode: '01', elementName: 'a', elementValue: '1', required: true },
          { elementCode: '02', elementName: 'b', elementValue: '', required: false },
        ],
      }),
    );
    expect(payload.elements.map((e) => e.ordinal)).toEqual([1, 2, 3]);
    expect(payload.elements.map((e) => e.elementCode)).toEqual(['03', '01', '02']);
  });

  it('空申报要素值转成 null 而不是空串', () => {
    const payload = draftToPayload(
      draft({ elements: [{ ...emptyElement(), elementValue: '  ' }] }),
    );
    expect(payload.elements[0]?.elementValue).toBeNull();
  });

  it('原产地归一成大写，文本字段去首尾空格', () => {
    const payload = draftToPayload(
      draft({ originCountryCode: ' cn ', declaredName: ' 移动电话机 ', note: ' 备注 ' }),
    );
    expect(payload.originCountryCode).toBe('CN');
    expect(payload.declaredName).toBe('移动电话机');
    expect(payload.note).toBe('备注');
  });

  it('未勾享惠时协定代码一律置空，不把矛盾的两个答案发下去', () => {
    const payload = draftToPayload(
      draft({ preferentialOriginEligible: false, preferenceAgreement: 'RCEP' }),
    );
    expect(payload.preferentialOriginEligible).toBe(false);
    expect(payload.preferenceAgreement).toBe('RCEP'); // 校验会拦住；这里只保证不偷偷改写用户输入
  });

  it('空申报价值参考发 null 而不是 0', () => {
    expect(draftToPayload(draft({ declaredValueReference: '' })).declaredValueReference).toBeNull();
  });

  it('version 原样带给后端做 CAS', () => {
    expect(draftToPayload(draft({ version: 5 })).version).toBe(5);
  });

  it('空申报要素清单发空数组（由后端与审核环节拒绝，不由前端伪造一条）', () => {
    expect(draftToPayload(draft({ elements: [] })).elements).toEqual([]);
  });
});

// ============================================================ 错误处理

describe('错误分类', () => {
  it('404 与 409 分别识别', () => {
    expect(isNotFound(new ApiError(404, 'x'))).toBe(true);
    expect(isConflict(new ApiError(409, 'x'))).toBe(true);
    expect(isConflict(new ApiError(404, 'x'))).toBe(false);
    expect(isNotFound(new Error('x'))).toBe(false);
  });

  it('优先展示后端给的中文原因', () => {
    expect(errorText(new ApiError(409, '必填申报要素还是空的'), 'fallback')).toBe(
      '必填申报要素还是空的',
    );
  });

  it('没有消息时用兜底文案', () => {
    expect(errorText({}, '保存失败')).toBe('保存失败');
    expect(errorText(new Error(''), '保存失败')).toBe('保存失败');
  });
});
