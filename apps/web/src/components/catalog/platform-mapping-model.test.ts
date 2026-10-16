import { describe, expect, it } from 'vitest';
import type {
  PlatformAttributeMappingRow,
  PlatformCategoryMappingRow,
} from '../../../../../packages/api-contracts/src/index';
import {
  attributeProblem,
  coveragePercent,
  emptyAttributeDraft,
  emptyMappingDraft,
  formatAllowedValues,
  mappingProblem,
  normalizeAllowedValues,
  normalizePlatform,
  normalizePlatformAttributeCode,
  normalizeSite,
  normalizeTransform,
  parseAllowedValues,
  platformAttributeCodeProblem,
  platformAttributeNameProblem,
  platformDataTypeProblem,
  platformProblem,
  requirementProblem,
  siteProblem,
  toAttributeDraft,
  toAttributeInput,
  toMappingDraft,
  toMappingInput,
  transformProblem,
  type AttributeRuleDraft,
} from './platform-mapping-model';

function mappingRow(overrides: Partial<PlatformCategoryMappingRow> = {}): PlatformCategoryMappingRow {
  return {
    id: 'map-1',
    internalCategoryId: '11111111-1111-1111-1111-111111111111',
    internalCategoryCode: 'TSHIRT',
    internalCategoryName: 'T恤',
    platformCode: 'AMAZON',
    siteCode: 'US',
    platformCategoryId: '22222222-2222-2222-2222-222222222222',
    platformCategoryCode: 'AMZ-SHIRT',
    platformCategoryName: 'Amazon 男士上装',
    platformCategoryPath: '服饰 / 男装 / 上装',
    isDefault: true,
    enabled: true,
    mappedInternalCount: 2,
    attributeRuleCount: 3,
    requiredCount: 2,
    unmappedRequiredCount: 1,
    version: 4,
    createdAt: '2026-10-16T00:00:00Z',
    updatedAt: '2026-10-16T01:00:00Z',
    ...overrides,
  };
}

function attributeRow(
  overrides: Partial<PlatformAttributeMappingRow> = {},
): PlatformAttributeMappingRow {
  return {
    id: 'rule-1',
    mappingId: 'map-1',
    platformAttributeCode: 'material',
    platformAttributeName: '材质',
    internalAttributeId: '33333333-3333-3333-3333-333333333333',
    internalAttributeCode: 'MATERIAL',
    internalAttributeName: '材质',
    requirement: 'REQUIRED',
    platformDataType: 'TEXT',
    multiple: false,
    allowedValues: [],
    transform: 'DIRECT',
    enabled: true,
    position: 0,
    version: 1,
    createdAt: '2026-10-16T00:00:00Z',
    updatedAt: '2026-10-16T00:00:00Z',
    ...overrides,
  };
}

function draft(overrides: Partial<AttributeRuleDraft> = {}): AttributeRuleDraft {
  return { ...emptyAttributeDraft(), ...overrides };
}

describe('4.2.3.1 平台类目映射 · 编码规范化与校验（与后端同口径）', () => {
  it('平台码与站点码去空白后转大写', () => {
    expect(normalizePlatform(' amazon ')).toBe('AMAZON');
    expect(normalizeSite(' us ')).toBe('US');
  });

  it('平台码与站点码各自给出可分辨的原因', () => {
    expect(platformProblem('amazon')).toBeNull();
    expect(siteProblem('de')).toBeNull();
    expect(platformProblem('AMAZON-UK')).toBeNull();

    expect(platformProblem('')).toContain('平台编码不能为空');
    expect(platformProblem('亚马逊')).toContain('平台编码请使用');
    expect(platformProblem('-AMAZON')).toContain('平台编码请使用');
    expect(platformProblem('A'.repeat(33))).toContain('最多 32');
    expect(platformProblem('A'.repeat(32))).toBeNull();

    // 两条消息必须能分辨是平台栏还是站点栏错了，否则运营不知道该改哪一栏。
    expect(platformProblem('亚马逊')).not.toBe(siteProblem('美国站'));
    expect(siteProblem('')).toContain('站点编码不能为空');
    expect(siteProblem('美国站')).toContain('站点编码请使用');
    expect(siteProblem('A'.repeat(33))).toContain('最多 32');
  });

  it('平台属性码保留原始大小写（要原样进平台请求体）', () => {
    expect(normalizePlatformAttributeCode(' bullet_point_1 ')).toBe('bullet_point_1');
    expect(platformAttributeCodeProblem('bullet_point_1')).toBeNull();
    expect(platformAttributeCodeProblem('item-type.name')).toBeNull();
    expect(platformAttributeCodeProblem('材质')).toContain('平台属性编码请使用');
    expect(platformAttributeCodeProblem('_x')).toContain('平台属性编码请使用');
    expect(platformAttributeCodeProblem('B'.repeat(65))).toContain('最多 64');
    expect(platformAttributeNameProblem(' ')).toContain('平台属性显示名不能为空');
    expect(platformAttributeNameProblem('材质')).toBeNull();
  });

  it('词表校验给出可读原因', () => {
    expect(requirementProblem('REQUIRED')).toBeNull();
    expect(requirementProblem('required')).toContain('要求强度只能是');
    expect(requirementProblem('')).toContain('要求强度不能为空');

    expect(platformDataTypeProblem('ENUM')).toBeNull();
    expect(platformDataTypeProblem('OPTION')).toContain('平台数据类型只能是');

    expect(transformProblem('')).toBeNull();
    expect(normalizeTransform('')).toBe('DIRECT');
    expect(normalizeTransform(' upper ')).toBe('UPPER');
    expect(transformProblem('LOWERCASE')).toContain('值变换只能是');
    expect(transformProblem('DIRECT')).toBeNull();
  });

  it('候选值去空白、去重、丢弃空项，并能在编辑框与数组之间往返', () => {
    expect(normalizeAllowedValues(null)).toEqual([]);
    expect(normalizeAllowedValues(['  红色 ', '蓝色', '红色', '', '   '])).toEqual(['红色', '蓝色']);

    const text = '红色\n\n  蓝色 \n红色';
    expect(parseAllowedValues(text)).toEqual(['红色', '蓝色']);
    expect(formatAllowedValues(['红色', '蓝色', '红色'])).toBe('红色\n蓝色');
  });
});

describe('4.2.3.1 平台类目映射 · 属性规则逐项校验', () => {
  it('合法规则不报错', () => {
    expect(
      attributeProblem(
        draft({ platformAttributeCode: 'material', platformAttributeName: '材质' }),
      ),
    ).toBeNull();
  });

  it('ENUM 必须给候选值，否则刊登时渲染不出下拉也校验不了取值', () => {
    expect(
      attributeProblem(
        draft({
          platformAttributeCode: 'color',
          platformAttributeName: '颜色',
          platformDataType: 'ENUM',
        }),
      ),
    ).toContain('必须提供候选值');

    expect(
      attributeProblem(
        draft({
          platformAttributeCode: 'color',
          platformAttributeName: '颜色',
          platformDataType: 'ENUM',
          allowedValuesText: '红\n蓝',
        }),
      ),
    ).toBeNull();
  });

  it('顺序：编码 → 名称 → 强度 → 类型 → 变换 → 候选值 → 排序位', () => {
    expect(attributeProblem(draft({ platformAttributeCode: '' }))).toContain(
      '平台属性编码不能为空',
    );
    expect(
      attributeProblem(
        draft({ platformAttributeCode: 'material', platformAttributeName: '' }),
      ),
    ).toContain('平台属性显示名不能为空');
    expect(
      attributeProblem(
        draft({
          platformAttributeCode: 'material',
          platformAttributeName: '材质',
          requirement: 'NOPE',
        }),
      ),
    ).toContain('要求强度只能是');
    expect(
      attributeProblem(
        draft({
          platformAttributeCode: 'material',
          platformAttributeName: '材质',
          platformDataType: 'NOPE',
        }),
      ),
    ).toContain('平台数据类型只能是');
    expect(
      attributeProblem(
        draft({
          platformAttributeCode: 'material',
          platformAttributeName: '材质',
          transform: 'NOPE',
        }),
      ),
    ).toContain('值变换只能是');
    expect(
      attributeProblem(
        draft({
          platformAttributeCode: 'material',
          platformAttributeName: '材质',
          allowedValuesText: 'x'.repeat(101),
        }),
      ),
    ).toContain('单个候选值最多');
    expect(
      attributeProblem(
        draft({
          platformAttributeCode: 'material',
          platformAttributeName: '材质',
          position: -1,
        }),
      ),
    ).toContain('排序位不能为负数');
  });
});

describe('4.2.3.1 平台类目映射 · 草稿与契约输入', () => {
  it('映射草稿回填后原样带回契约（含 version 乐观锁）', () => {
    const draft = toMappingDraft(mappingRow());
    expect(draft.id).toBe('map-1');
    expect(draft.version).toBe(4);
    expect(draft.isDefault).toBe(true);
    expect(toMappingInput(draft)).toEqual({
      internalCategoryId: '11111111-1111-1111-1111-111111111111',
      platformCode: 'AMAZON',
      siteCode: 'US',
      platformCategoryId: '22222222-2222-2222-2222-222222222222',
      isDefault: true,
      enabled: true,
      version: 4,
    });
  });

  it('属性草稿回填：候选值拆成行，未绑定内部属性时落 null', () => {
    const draft = toAttributeDraft(
      attributeRow({ internalAttributeId: null, allowedValues: ['棉', '涤纶'] }),
    );
    expect(draft.allowedValuesText).toBe('棉\n涤纶');
    expect(toAttributeInput(draft).internalAttributeId).toBeNull();
    expect(toAttributeInput(draft).allowedValues).toEqual(['棉', '涤纶']);
  });

  it('空的内部属性选择不会被当成空字符串提交（服务端会当 id 查不到）', () => {
    const input = toAttributeInput(
      draft({ platformAttributeCode: 'material', platformAttributeName: '材质', internalAttributeId: '  ' }),
    );
    expect(input.internalAttributeId).toBeNull();
  });

  it('新建草稿默认「启用 + 设为默认」，运营只需补四项', () => {
    const fresh = emptyMappingDraft('amazon', 'us');
    expect(fresh.id).toBeNull();
    expect(fresh.version).toBe(0);
    expect(fresh.isDefault).toBe(true);
    expect(fresh.enabled).toBe(true);
    // 平台/站点小写输入在提交时被规范化成大写，与后端一致。
    expect(toMappingInput(fresh).platformCode).toBe('AMAZON');
    expect(toMappingInput(fresh).siteCode).toBe('US');
  });

  it('映射表单校验先挡类目，再逐字校验平台与站点', () => {
    expect(mappingProblem(emptyMappingDraft('AMAZON', 'US'))).toBe('请选择内部分类（左侧）');

    const noRight = emptyMappingDraft('AMAZON', 'US');
    noRight.internalCategoryId = '11111111-1111-1111-1111-111111111111';
    expect(mappingProblem(noRight)).toBe('请选择平台类目（右侧）');

    const same = emptyMappingDraft('AMAZON', 'US');
    same.internalCategoryId = '22222222-2222-2222-2222-222222222222';
    same.platformCategoryId = '22222222-2222-2222-2222-222222222222';
    expect(mappingProblem(same)).toBe('内部分类与平台类目不能是同一个');

    const ok = emptyMappingDraft('AMAZON', 'US');
    ok.internalCategoryId = '11111111-1111-1111-1111-111111111111';
    ok.platformCategoryId = '22222222-2222-2222-2222-222222222222';
    expect(mappingProblem(ok)).toBeNull();
  });
});

describe('4.2.3.1 平台类目映射 · 覆盖率口径', () => {
  it('分母为 0 时返回 0，不出现 NaN%', () => {
    expect(coveragePercent(0, 0)).toBe(0);
  });

  it('按「已映射内部分类数 ÷ 内部分类总数」算，而不是按映射条数', () => {
    expect(coveragePercent(3, 12)).toBe(25);
    // 一个类目配 10 条映射不代表 10 个类目能刊登。
    expect(coveragePercent(1, 12)).toBe(8);
  });
});
