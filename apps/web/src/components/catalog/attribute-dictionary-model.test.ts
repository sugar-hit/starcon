import { describe, expect, it } from 'vitest';
import type {
  AttributeDefinitionRow,
  AttributeOptionRow,
  CategoryAttributeRule,
} from '../../../../../packages/api-contracts/src/catalog';
import {
  attributeDraft,
  codeProblem,
  draftToAttributeInput,
  draftToRuleInput,
  emptyAttributeDraft,
  emptyRuleDraft,
  errorText,
  labelOf,
  normalizeCode,
  optionCountHint,
  parseOptions,
  ruleRangeText,
  usageText,
  validateAttribute,
  validateRule,
  valueProblem,
  variantAxisHint,
  DATA_TYPES,
} from './attribute-dictionary-model';

function row(overrides: Partial<AttributeDefinitionRow> = {}): AttributeDefinitionRow {
  return {
    id: 'attr-1',
    code: 'COLOR',
    name: '颜色',
    dataType: 'OPTION',
    valueMode: 'SKU_ONLY',
    usage: 'DESCRIPTION',
    multiple: false,
    required: false,
    filterable: false,
    variantAxis: false,
    enabled: true,
    unit: '',
    groupName: '基本参数',
    position: 0,
    optionCount: 0,
    enabledOptionCount: 0,
    categoryRuleCount: 0,
    spuUsageCount: 0,
    version: 0,
    createdAt: '2026-10-07T00:00:00Z',
    updatedAt: '2026-10-07T00:00:00Z',
    ...overrides,
  };
}

function option(overrides: Partial<AttributeOptionRow> = {}): AttributeOptionRow {
  return {
    value: '红色',
    position: 0,
    enabled: true,
    productValueCount: 0,
    categoryRuleCount: 0,
    inUse: false,
    createdAt: '2026-10-07T00:00:00Z',
    ...overrides,
  };
}

function rule(overrides: Partial<CategoryAttributeRule> = {}): CategoryAttributeRule {
  return {
    category: '服饰',
    attributeId: 'attr-1',
    attributeCode: 'COLOR',
    attributeName: '颜色',
    dataType: 'OPTION',
    required: false,
    minValue: null,
    maxValue: null,
    position: 0,
    productCount: 0,
    allowedOptions: [],
    createdAt: '2026-10-07T00:00:00Z',
    ...overrides,
  };
}

describe('codeProblem', () => {
  it('接受 ASCII 机器标识', () => {
    expect(codeProblem('COLOR')).toBeNull();
    expect(codeProblem('net_weight')).toBeNull();
    expect(codeProblem('A')).toBeNull();
    expect(codeProblem('A'.repeat(64))).toBeNull();
  });

  it('拒绝空值、中文与超长', () => {
    expect(codeProblem('')).toContain('不能为空');
    expect(codeProblem('   ')).toContain('不能为空');
    expect(codeProblem('颜色')).toContain('英文字母');
    expect(codeProblem('A'.repeat(65))).toContain('长度');
  });
});

describe('normalizeCode', () => {
  it('规范化为大写', () => {
    expect(normalizeCode(' color ')).toBe('COLOR');
  });
});

describe('valueProblem', () => {
  it('选项值允许中文但限制长度', () => {
    expect(valueProblem('深空灰')).toBeNull();
    expect(valueProblem('XL')).toBeNull();
    expect(valueProblem('2 件装')).toBeNull();
    expect(valueProblem('  ')).toContain('不能为空');
    expect(valueProblem('A'.repeat(101))).toContain('最多 100');
  });
});

describe('parseOptions', () => {
  it('按行拆分并忽略空行与空白', () => {
    expect(parseOptions('红色\n\n  蓝色  \n')).toEqual(['红色', '蓝色']);
    expect(parseOptions('')).toEqual([]);
  });
});

describe('variantAxisHint', () => {
  const base = emptyAttributeDraft();

  it('非规格轴属性没有提示', () => {
    expect(variantAxisHint({ ...base, variantAxis: false })).toBeNull();
  });

  it('规格轴必须是必填单选枚举属性', () => {
    const draft = { ...base, variantAxis: true, dataType: 'TEXT', required: true };
    expect(variantAxisHint(draft)).toContain('必填单选枚举属性');
  });

  it('规格轴必须登记为规格用途', () => {
    const draft = {
      ...base,
      variantAxis: true,
      dataType: 'OPTION',
      valueMode: 'SKU_ONLY',
      required: true,
      usage: 'DESCRIPTION',
    };
    expect(variantAxisHint(draft)).toContain('用途必须是「规格」');
  });

  it('形态与用途都正确时没有提示', () => {
    const draft = {
      ...base,
      variantAxis: true,
      dataType: 'OPTION',
      valueMode: 'SKU_ONLY',
      required: true,
      usage: 'SPECIFICATION',
    };
    expect(variantAxisHint(draft)).toBeNull();
  });
});

describe('validateAttribute', () => {
  it('新建时校验编码，编辑时跳过（编码建后不可改）', () => {
    expect(validateAttribute({ ...emptyAttributeDraft(), name: '颜色' }, true).join()).toContain(
      '属性编码不能为空',
    );
    expect(validateAttribute({ ...emptyAttributeDraft(), name: '颜色', code: 'COLOR' }, false)).toEqual(
      [],
    );
  });

  it('枚举属性必须给选项，非枚举属性不能给选项', () => {
    const base = { ...emptyAttributeDraft(), code: 'COLOR', name: '颜色' };
    expect(
      validateAttribute({ ...base, dataType: 'OPTION', options: '' }, true).join(),
    ).toContain('枚举属性请至少提供一个选项');
    expect(
      validateAttribute({ ...base, dataType: 'TEXT', options: '棉' }, true).join(),
    ).toContain('只有枚举属性（OPTION）可以配置选项集合');
  });

  it('拒绝空名称、重复选项与非数字排序', () => {
    const base = { ...emptyAttributeDraft(), code: 'X', dataType: 'OPTION' };
    expect(validateAttribute({ ...base, name: '  ' }, true).join()).toContain('属性名称不能为空');
    expect(
      validateAttribute({ ...base, name: '名称', options: '红色\n红色' }, true).join(),
    ).toContain('选项不可重复');
    expect(
      validateAttribute({ ...base, name: '名称', options: '红色', position: 'x' }, true).join(),
    ).toContain('排序位置需填 0 起的整数');
  });
});

describe('draftToAttributeInput', () => {
  it('规范化编码与名称，选项拆成数组', () => {
    const input = draftToAttributeInput({
      ...emptyAttributeDraft(),
      code: ' color ',
      name: ' 颜色 ',
      dataType: 'OPTION',
      options: '红色\n蓝色',
      position: '3',
    });
    expect(input.code).toBe('COLOR');
    expect(input.name).toBe('颜色');
    expect(input.options).toEqual(['红色', '蓝色']);
    expect(input.position).toBe(3);
  });
});

describe('attributeDraft', () => {
  it('编辑时不带选项：选项有独立子资源，不该被一次改名清空', () => {
    const draft = attributeDraft(row({ optionCount: 2, version: 7 }));
    expect(draft.options).toBe('');
    expect(draft.version).toBe(7);
    expect(draft.code).toBe('COLOR');
  });
});

describe('optionCountHint', () => {
  it('非枚举属性没有提示', () => {
    expect(optionCountHint(row({ dataType: 'TEXT' }))).toBeNull();
  });

  it('区分「还没配取值」与「取值全被停用」', () => {
    expect(optionCountHint(row({ optionCount: 0 }))).toContain('还没有任何取值');
    expect(
      optionCountHint(row({ optionCount: 3, enabledOptionCount: 0 })),
    ).toContain('都已停用');
    expect(optionCountHint(row({ optionCount: 3, enabledOptionCount: 2 }))).toBeNull();
  });
});

describe('usageText', () => {
  it('把三个计数都讲出来', () => {
    expect(usageText(row({ optionCount: 2, categoryRuleCount: 1, spuUsageCount: 4 }))).toBe(
      '2 个选项 · 1 条类目规则 · 4 个商品在用',
    );
  });
});

describe('ruleRangeText', () => {
  it('数字属性展示上下限', () => {
    expect(ruleRangeText(rule({ dataType: 'DECIMAL', minValue: 0.5, maxValue: 20 }))).toBe(
      '取值范围 0.5 ~ 20',
    );
    expect(ruleRangeText(rule({ dataType: 'DECIMAL' }))).toBe('不限取值范围');
    expect(ruleRangeText(rule({ dataType: 'DECIMAL', minValue: 1, maxValue: null }))).toBe(
      '取值范围 1 ~ 不限',
    );
  });

  it('枚举属性展示可选值子集，不勾时表示允许全部', () => {
    expect(ruleRangeText(rule({ allowedOptions: ['红色', '蓝色'] }))).toBe('只允许：红色、蓝色');
    expect(ruleRangeText(rule({ allowedOptions: [] }))).toBe('允许全部选项');
  });

  it('其他类型不限范围', () => {
    expect(ruleRangeText(rule({ dataType: 'TEXT' }))).toBe('不限取值范围');
  });
});

describe('validateRule', () => {
  const options = [option(), option({ value: '蓝色' }), option({ value: '灰色', enabled: false })];

  it('类目不能为空', () => {
    expect(validateRule(emptyRuleDraft(), 'OPTION', options).join()).toContain('类目不能为空');
  });

  it('数字属性接受上下限，枚举属性拒绝上下限', () => {
    const draft = { ...emptyRuleDraft('服饰'), minValue: '1', maxValue: '5' };
    expect(validateRule(draft, 'DECIMAL', options)).toEqual([]);
    expect(validateRule(draft, 'OPTION', options).join()).toContain(
      '取值上下限只对数字属性有意义',
    );
  });

  it('下限大于上限被拒', () => {
    expect(
      validateRule(
        { ...emptyRuleDraft('服饰'), minValue: '9', maxValue: '1' },
        'DECIMAL',
        options,
      ).join(),
    ).toContain('下限不能大于上限');
  });

  it('可选值必须在可用取值里', () => {
    expect(
      validateRule({ ...emptyRuleDraft('服饰'), allowedOptions: ['红色'] }, 'OPTION', options),
    ).toEqual([]);
    expect(
      validateRule({ ...emptyRuleDraft('服饰'), allowedOptions: ['灰色'] }, 'OPTION', options).join(),
    ).toContain('不在该属性的可用选项里');
    expect(
      validateRule({ ...emptyRuleDraft('服饰'), allowedOptions: ['不存在'] }, 'OPTION', options).join(),
    ).toContain('不在该属性的可用选项里');
  });
});

describe('draftToRuleInput', () => {
  it('空上下限变 null，位置变数字', () => {
    const input = draftToRuleInput({
      ...emptyRuleDraft(' 服饰 '),
      minValue: '',
      maxValue: '20',
      position: '2',
      allowedOptions: ['红色'],
    });
    expect(input.category).toBe('服饰');
    expect(input.minValue).toBeNull();
    expect(input.maxValue).toBe(20);
    expect(input.position).toBe(2);
    expect(input.allowedOptions).toEqual(['红色']);
  });
});

describe('labelOf', () => {
  it('返回中文标签，未知值原样返回', () => {
    expect(labelOf(DATA_TYPES, 'OPTION')).toBe('枚举');
    expect(labelOf(DATA_TYPES, 'WEIRD')).toBe('WEIRD');
  });
});

describe('errorText', () => {
  it('保留后端给出的可读原因', () => {
    expect(errorText(new Error('属性编码建后不可修改'), '兜底')).toBe('属性编码建后不可修改');
    expect(errorText({}, '兜底')).toBe('兜底');
  });
});