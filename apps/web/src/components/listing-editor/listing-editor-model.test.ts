import { describe, expect, it } from 'vitest';
import {
  constraintNote,
  draftBadge,
  emptyFieldDraft,
  fieldItemCount,
  fieldLength,
  idempotencyKey,
  localeHasDraft,
  requirementLabel,
  requirementTone,
  siteOptions,
  submissionBadge,
  toFieldDrafts,
  toFieldValues,
  violationTone,
  type EditorDraftRow,
  type EditorFieldSchema,
} from './listing-editor-model';

/**
 * 4.2.1.1 刊登编辑器 前端模型单测。
 *
 * 🔴 这些断言盯的是「前端不许自己判断」这件事：
 * 必填色、约束措辞、状态措辞**全部由后端给的字段决定**，
 * 前端只做翻译与渲染 —— 前端一旦自己写死必填清单，平台要求一变就悄悄失真。
 */
function schema(patch: Partial<EditorFieldSchema> = {}): EditorFieldSchema {
  return {
    field: 'TITLE',
    label: '标题',
    valueKind: 'TEXT',
    requirement: 'REQUIRED',
    constraintState: 'KNOWN',
    capabilityState: 'NOT_APPLICABLE',
    minLength: null,
    maxLength: 200,
    minItems: null,
    maxItems: null,
    editable: true,
    valueSource: 'OPERATOR',
    ownerModule: null,
    note: '',
    unavailableReason: null,
    ...patch,
  };
}

describe('4.2.1.1 刊登编辑器 · 值形态', () => {
  it('后端值 → 本地编辑态：四种形状各归各位', () => {
    const drafts = toFieldDrafts([
      { field: 'TITLE', text: '标题', items: [], modules: [], pairs: [] },
      { field: 'BULLETS', text: '', items: ['一', '二'], modules: [], pairs: [] },
      {
        field: 'A_PLUS',
        text: '',
        items: [],
        modules: [{ type: 'IMAGE_TEXT', heading: '标题', body: '正文', imageRef: 'key-1' }],
        pairs: [],
      },
      {
        field: 'CATEGORY_ATTRIBUTES',
        text: '',
        items: [],
        modules: [],
        pairs: [{ key: '材质', value: '棉' }],
      },
    ]);
    expect(drafts['TITLE']?.text).toBe('标题');
    expect(drafts['BULLETS']?.items).toEqual(['一', '二']);
    expect(drafts['A_PLUS']?.modules[0]?.imageRef).toBe('key-1');
    expect(drafts['CATEGORY_ATTRIBUTES']?.pairs[0]?.key).toBe('材质');
  });

  it('本地编辑态 → 后端值：**只发 schema 登记了的栏**', () => {
    const drafts = toFieldDrafts([
      { field: 'TITLE', text: '标题', items: [], modules: [], pairs: [] },
      { field: 'BULLETS', text: '', items: ['不该发'], modules: [], pairs: [] },
    ]);
    const values = toFieldValues(drafts, [
      schema(),
      schema({ field: 'BULLETS', valueKind: 'TEXT_LIST' }),
    ]);
    expect(values.map((v) => v.field)).toEqual(['TITLE', 'BULLETS']);

    // 平台没有的栏不进载荷（Shopify 没有要点）
    const onlyTitle = toFieldValues(drafts, [schema()]);
    expect(onlyTitle.map((v) => v.field)).toEqual(['TITLE']);
  });

  it('空的一栏是空对象，不是 undefined', () => {
    const empty = emptyFieldDraft();
    expect(empty.text).toBe('');
    expect(empty.items).toEqual([]);
    expect(empty.modules).toEqual([]);
    expect(empty.pairs).toEqual([]);
  });

  it('长度按码点算（emoji 不算两个字），条数按列表条数算', () => {
    const drafts = toFieldDrafts([
      { field: 'TITLE', text: '😀😀', items: [], modules: [], pairs: [] },
      { field: 'BULLETS', text: '', items: ['a', 'bb'], modules: [], pairs: [] },
    ]);
    expect(fieldLength(drafts['TITLE'])).toBe(2);
    expect(fieldItemCount(drafts['TITLE'])).toBe(1);
    expect(fieldItemCount(drafts['BULLETS'])).toBe(2);
  });
});

describe('4.2.1.1 刊登编辑器 · 措辞由后端决定', () => {
  it('必填语义色与文案', () => {
    expect(requirementTone('REQUIRED')).toBe('danger');
    expect(requirementTone('RECOMMENDED')).toBe('warning');
    expect(requirementTone('OPTIONAL')).toBe('neutral');
    expect(requirementLabel('REQUIRED')).toBe('必填');
    expect(requirementLabel('UNSUPPORTED')).toBe('平台不支持');
  });

  it('「不知道」是一个状态：约束未知与细则归别的模块各有措辞', () => {
    expect(
      constraintNote(
        schema({ constraintState: 'UNKNOWN', ownerModule: '2.1.1.2', maxLength: null }),
      ),
    ).toContain('未知');
    expect(
      constraintNote(schema({ constraintState: 'DELEGATED', ownerModule: '4.2.3.1' })),
    ).toContain('4.2.3.1');
    expect(
      constraintNote(
        schema({ constraintState: 'KNOWN', capabilityState: 'UNKNOWN', ownerModule: '2.1.1.2' }),
      ),
    ).toContain('未知');
    expect(constraintNote(schema({ maxLength: 200 }))).toContain('200');
  });

  it('草稿 / 入队状态措辞：入队不是发布', () => {
    expect(draftBadge('DRAFT').label).toContain('草稿');
    expect(draftBadge('SUBMITTED').label).toContain('入队');
    expect(submissionBadge('QUEUED').label).toContain('未发布');
    expect(submissionBadge('CANCELLED').label).toContain('撤销');
  });

  it('违规严重度色', () => {
    expect(violationTone('BLOCKING')).toBe('danger');
    expect(violationTone('WARNING')).toBe('warning');
  });
});

describe('4.2.1.1 刊登编辑器 · 目标与页签', () => {
  it('只给启用的站点', () => {
    const shop = {
      id: 's1',
      platformId: 'p1',
      platformCode: 'AMAZON',
      platformName: 'Amazon',
      connectionId: 'c1',
      connectionAccount: '',
      legalEntityId: '',
      code: 'AMZ',
      name: '美国店',
      platformShopId: '',
      operatingMode: 'SELF_FULFILLED',
      ownerId: '',
      departmentId: '',
      status: 'ACTIVE',
      version: 0,
      marketplaceCount: 2,
      marketplaces: [
        {
          marketplaceId: 'm1',
          marketplaceCode: 'US',
          marketplaceName: '美国站',
          status: 'ACTIVE',
          enabledAt: '',
        },
        {
          marketplaceId: 'm2',
          marketplaceCode: 'DE',
          marketplaceName: '德国站',
          status: 'SUSPENDED',
          enabledAt: '',
        },
      ],
      lastSyncAt: '',
    };
    expect(siteOptions(shop).map((m) => m.marketplaceCode)).toEqual(['US']);
    expect(siteOptions(null)).toEqual([]);
  });

  it('语言页签看的是「有没有草稿」，不是界面上摆了几个 Tab', () => {
    const drafts = [
      { locale: 'en-US', state: 'DRAFT' } as EditorDraftRow,
      { locale: 'ja-JP', state: 'DISCARDED' } as EditorDraftRow,
    ];
    expect(localeHasDraft('en-US', drafts)).toBe(true);
    expect(localeHasDraft('ja-JP', drafts)).toBe(false);
    expect(localeHasDraft('de-DE', drafts)).toBe(false);
  });

  it('幂等键跟着草稿走：同一草稿 + 同一版本 ⇒ 同一个键', () => {
    expect(idempotencyKey('draft-1', 3)).toBe(idempotencyKey('draft-1', 3));
    expect(idempotencyKey('draft-1', 3)).not.toBe(idempotencyKey('draft-1', 4));
  });
});
