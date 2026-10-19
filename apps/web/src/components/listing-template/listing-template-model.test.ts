import { describe, expect, it } from 'vitest';
import {
  buildApplyRequest,
  buildTemplateRequest,
  emptyTemplateDraft,
  evaluationStatusLabel,
  evaluationTone,
  FIELD_OPTIONS,
  replayNote,
  ruleSummary,
  scopeTargetLabel,
  sourceKindLabel,
  sourcesAllowedFor,
  statusLabel,
  statusTone,
  toTemplateDraft,
  unavailableDimensions,
  validationSummary,
  valueLines,
  type ListingTemplateApplyResult,
  type ListingTemplateCapabilities,
  type ListingTemplateDetail,
} from './listing-template-model';

/**
 * 4.2.2.1 刊登模板 前端纯函数单测。
 *
 * <p>这些函数是页面上「最容易做丢的那几件事」的可执行版本：
 * 模板里不存值、两种作用域互斥、来源必须与字段匹配、「不知道」是一个状态、
 * 必填违规指到具体字段与规则、重复套用的语义写在页面上。
 */

function caps(overrides: Partial<ListingTemplateCapabilities> = {}): ListingTemplateCapabilities {
  return {
    dimensions: [
      {
        dimension: 'editor_entry',
        description: '编辑器侧的套用入口',
        available: false,
        source: '—',
        ownerModule: '4.2.1.1',
        impact: '模板能力已就绪',
      },
      {
        dimension: 'rules_not_snapshots',
        description: '模板只存规则',
        available: true,
        source: 'listing_template_field',
        ownerModule: '',
        impact: '',
      },
    ],
    fields: ['TITLE', 'BULLETS', 'IMAGES', 'CATEGORY_PATH', 'PLATFORM_CATEGORY_ID'],
    sourceKinds: ['LISTING_FIELD', 'PRODUCT_ATTRIBUTE', 'CONTENT_TEMPLATE', 'IMAGE_GROUP', 'LITERAL'],
    evaluationStatuses: ['RESOLVED', 'EMPTY', 'SOURCE_MISSING', 'UNKNOWN_SOURCE', 'LIMIT_EXCEEDED'],
    scopes: ['CATEGORY', 'SHOP'],
    rulesOnly: true,
    rulesOnlyNote: '模板里没有值',
    draftOnly: true,
    draftNote: '只产出草稿',
    idempotencyNote: '重复套用 = 覆盖草稿',
    upstreamNote: '跨服务只读',
    ...overrides,
  };
}

describe('模板定义：作用域互斥', () => {
  it('类目级必须给类目路径，且不能同时给店铺', () => {
    const draft = emptyTemplateDraft();
    draft.code = 'TPL-1';
    draft.name = '模板一';
    draft.platformId = 'platform-1';
    draft.categoryPath = 'Electronics > Audio';
    expect(buildTemplateRequest(draft)).not.toBeNull();

    draft.shopId = 'shop-1';
    expect(buildTemplateRequest(draft), '类目级 + 店铺 = 两个适用范围，必须被拒').toBeNull();
  });

  it('店铺级必须给店铺，且不能同时给类目路径', () => {
    const draft = emptyTemplateDraft();
    draft.code = 'TPL-2';
    draft.name = '模板二';
    draft.platformId = 'platform-1';
    draft.scope = 'SHOP';
    draft.shopId = 'shop-1';
    expect(buildTemplateRequest(draft)).not.toBeNull();

    draft.categoryPath = 'Electronics > Audio';
    expect(buildTemplateRequest(draft), '店铺级 + 类目 = 两个适用范围，必须被拒').toBeNull();
  });

  it('店铺级没给店铺就是没定义完', () => {
    const draft = emptyTemplateDraft();
    draft.code = 'TPL-3';
    draft.name = '模板三';
    draft.platformId = 'platform-1';
    draft.scope = 'SHOP';
    expect(buildTemplateRequest(draft)).toBeNull();
  });
});

describe('模板定义：规则不是值', () => {
  it('没有规则就交不出去（没有规则的模板套不出任何东西）', () => {
    const draft = emptyTemplateDraft();
    draft.code = 'TPL-4';
    draft.name = '模板四';
    draft.platformId = 'platform-1';
    draft.categoryPath = 'Electronics > Audio';
    draft.fields = [];
    expect(buildTemplateRequest(draft)).toBeNull();
  });

  it('LITERAL 必须给常量，其它来源必须给引用', () => {
    const draft = emptyTemplateDraft();
    draft.code = 'TPL-5';
    draft.name = '模板五';
    draft.platformId = 'platform-1';
    draft.categoryPath = 'Electronics > Audio';
    draft.fields = [
      { field: 'TITLE', sourceKind: 'LITERAL', sourceRef: '', valueExpression: '', required: false, slotNo: 0 },
    ];
    expect(buildTemplateRequest(draft), 'LITERAL 没有常量').toBeNull();

    draft.fields = [
      {
        field: 'TITLE',
        sourceKind: 'PRODUCT_ATTRIBUTE',
        sourceRef: '',
        valueExpression: '{{value}}',
        required: false,
        slotNo: 0,
      },
    ];
    expect(buildTemplateRequest(draft), '非 LITERAL 来源没有引用').toBeNull();

    draft.fields = [
      {
        field: 'TITLE',
        sourceKind: 'PRODUCT_ATTRIBUTE',
        sourceRef: 'price',
        valueExpression: 'Special {{value}}',
        required: false,
        slotNo: 0,
      },
    ];
    const payload = buildTemplateRequest(draft);
    expect(payload).not.toBeNull();
    // 🔴 载荷里只有「引用 + 表达式」，没有任何「当前值」字段
    expect(payload?.fields[0]?.sourceRef).toBe('price');
    expect(payload?.fields[0]?.valueExpression).toBe('Special {{value}}');
    expect(Object.keys(payload?.fields[0] ?? {}).sort()).toEqual([
      'field',
      'required',
      'slotNo',
      'sourceKind',
      'sourceRef',
      'valueExpression',
    ]);
  });

  it('来源必须与字段匹配（图片组填不了标题）', () => {
    expect(sourcesAllowedFor('TITLE', 'IMAGE_GROUP')).toBe(false);
    expect(sourcesAllowedFor('IMAGES', 'IMAGE_GROUP')).toBe(true);
    expect(sourcesAllowedFor('IMAGES', 'CONTENT_TEMPLATE')).toBe(false);
    expect(sourcesAllowedFor('BULLETS', 'CONTENT_TEMPLATE')).toBe(true);
    for (const option of FIELD_OPTIONS) {
      expect(option.sources.length, `${option.value} 至少要有一个来源`).toBeGreaterThan(0);
    }
  });
});

describe('套用请求', () => {
  it('模板与目标都选了才能套用', () => {
    expect(buildApplyRequest({ templateId: '', listingId: 'l-1', idempotencyKey: '', note: '' })).toBeNull();
    expect(buildApplyRequest({ templateId: 't-1', listingId: '', idempotencyKey: '', note: '' })).toBeNull();
    const payload = buildApplyRequest({
      templateId: 't-1',
      listingId: 'l-1',
      idempotencyKey: '  KEY-1  ',
      note: '备注',
    });
    expect(payload?.templateId).toBe('t-1');
    expect(payload?.listingId).toBe('l-1');
    expect(payload?.idempotencyKey).toBe('KEY-1');
  });
});

describe('求值状态：不知道是一个状态，不是一个 0', () => {
  it('上游读不到与引用不存在各有各的说法，不合并成「空」', () => {
    expect(evaluationStatusLabel('UNKNOWN_SOURCE')).toBe('上游读不到');
    expect(evaluationStatusLabel('SOURCE_MISSING')).toBe('引用不存在');
    expect(evaluationStatusLabel('EMPTY')).toBe('来源为空');
    expect(evaluationStatusLabel('RESOLVED')).toBe('已取到');
    expect(evaluationStatusLabel('LIMIT_EXCEEDED')).toBe('超出上限');
    // 三种「没取到」各有各的色调，不能都是同一个
    expect(evaluationTone('UNKNOWN_SOURCE')).toBe('danger');
    expect(evaluationTone('SOURCE_MISSING')).toBe('danger');
    expect(evaluationTone('EMPTY')).toBe('warning');
    expect(evaluationTone('RESOLVED')).toBe('success');
  });

  it('值是空的时渲染成空行，不是一个 0', () => {
    expect(valueLines('TITLE', null)).toEqual([]);
    expect(valueLines('TITLE', '')).toEqual([]);
    expect(valueLines('TITLE', 'Special 99.00')).toEqual(['Special 99.00']);
    expect(valueLines('BULLETS', '["卖点一","卖点二"]')).toEqual(['卖点一', '卖点二']);
    expect(valueLines('IMAGES', '["a.jpg","b.jpg"]')).toEqual(['a.jpg', 'b.jpg']);
  });
});

describe('必填违规的措辞', () => {
  function violation(field: string, status: string) {
    return {
      field,
      sourceKind: 'PRODUCT_ATTRIBUTE',
      ruleRef: `PRODUCT_ATTRIBUTE:${field.toLowerCase()}`,
      sourceRef: field.toLowerCase(),
      status,
      value: '',
      ownerModule: status === 'RESOLVED' ? '' : '3.1.2.2',
      reason: '取不到值',
      violation: true,
      violationCode: 'REQUIRED_SOURCE_UNKNOWN',
    };
  }

  const result = (violations: number, unknown: number, missing: number): ListingTemplateApplyResult =>
    ({
      applyId: 'a-1',
      templateId: 't-1',
      templateCode: 'TPL-1',
      templateName: '模板',
      listingId: 'l-1',
      listingExternalItemId: 'ITEM-1',
      shopCode: 'SHOP-1',
      platformCode: 'AMAZON',
      targetDraftId: 'd-1',
      draftState: 'DRAFT',
      validationStatus: violations > 0 ? 'FAIL' : 'PASS',
      appliedCount: 1,
      unknownCount: unknown,
      violationCount: violations,
      fields: [],
      violations: [
        ...(unknown > 0 ? [violation('TITLE', 'UNKNOWN_SOURCE')] : []),
        ...(missing > 0 ? [violation('BULLETS', 'SOURCE_MISSING')] : []),
      ],
      idempotentReplay: false,
      note: '',
      createdAt: '2026-10-20T00:00:00Z',
    }) as ListingTemplateApplyResult;

  it('校验通过时说清楚取到了几个字段', () => {
    expect(validationSummary(result(0, 0, 0))).toContain('必填校验通过');
  });

  it('校验失败时把「读不到」与「引用不存在」分开报', () => {
    const summary = validationSummary(result(2, 1, 1));
    expect(summary).toContain('2 个必填字段没通过');
    expect(summary).toContain('1 个是上游读不到');
    expect(summary).toContain('1 个是引用不存在');
  });

  it('幂等重放有专门的提示', () => {
    expect(replayNote(result(0, 0, 0))).toBe('');
    const replayed: ListingTemplateApplyResult = { ...result(0, 0, 0), idempotentReplay: true };
    expect(replayNote(replayed)).toContain('幂等键');
  });
});

describe('能力声明与文案', () => {
  it('未接入的维度被单独列出来（编辑器入口 / 发布）', () => {
    const unavailable = unavailableDimensions(caps());
    expect(unavailable.map((d) => d.dimension)).toEqual(['editor_entry']);
    expect(unavailable[0]?.ownerModule).toBe('4.2.1.1');
    expect(unavailable[0]?.impact).toContain('已就绪');
  });

  it('模板状态与适用范围的文案', () => {
    expect(statusLabel('ACTIVE')).toBe('可套用');
    expect(statusLabel('DISABLED')).toBe('已停用');
    expect(statusTone('ACTIVE')).toBe('success');
    expect(sourceKindLabel('IMAGE_GROUP')).toBe('套用图片组');
    expect(sourceKindLabel('PRODUCT_ATTRIBUTE')).toBe('取自商品属性');
  });

  it('规则摘要说清「几条规则、几条必填、几个图片组」', () => {
    const detail = {
      template: {
        id: 't-1',
        code: 'TPL-1',
        name: '模板',
        description: '',
        scope: 'CATEGORY',
        scopeTarget: 'Electronics > Audio',
        platformId: 'p-1',
        platformCode: 'AMAZON',
        marketplaceId: '',
        marketplaceCode: '',
        language: 'en-US',
        status: 'ACTIVE',
        fieldCount: 2,
        groupCount: 1,
        requiredFieldCount: 1,
        version: 0,
        createdAt: '',
        updatedAt: '',
      },
      fields: [
        {
          id: 'f-1',
          field: 'TITLE',
          sourceKind: 'PRODUCT_ATTRIBUTE',
          sourceRef: 'price',
          valueExpression: '{{value}}',
          required: true,
          slotNo: 0,
        },
        {
          id: 'f-2',
          field: 'IMAGES',
          sourceKind: 'IMAGE_GROUP',
          sourceRef: 'main',
          valueExpression: '',
          required: false,
          slotNo: 1,
        },
      ],
      assetGroups: [
        {
          id: 'g-1',
          groupCode: 'main',
          groupKind: 'ASSET_LIST',
          tagCode: '',
          assetIds: ['a-1'],
          maxCount: 9,
          slotNo: 0,
        },
      ],
    } as ListingTemplateDetail;
    expect(ruleSummary(detail)).toBe('2 条规则（1 条必填）· 1 个图片组');
    expect(scopeTargetLabel(detail.template)).toBe('类目路径 Electronics > Audio');

    // 详情 → 编辑草稿：规则与图片组都要在
    const draft = toTemplateDraft(detail);
    expect(draft.fields).toHaveLength(2);
    expect(draft.groups).toHaveLength(1);
    expect(draft.scope).toBe('CATEGORY');
    expect(draft.categoryPath).toBe('Electronics > Audio');
  });
});
