import { describe, expect, it } from 'vitest';
import type {
  CategoryAttributeRequirement,
  CategoryAttributeRequirementInput,
  CategoryNode,
  CategoryTree,
} from '../../../../../packages/api-contracts/src/catalog';
import {
  conditionText,
  conditionValueTexts,
  emptyRequirementInput,
  errorText,
  internalCategories,
  operatorLabel,
  operatorProblem,
  operatorsFor,
  overviewHint,
  rangeProblem,
  requirementInputProblem,
  requirementLabel,
  requirementProblem,
  sourceText,
  toRequirementInput,
} from './category-requirements-model';

function tree(id: string, kind: string): CategoryTree {
  return {
    id,
    code: kind,
    name: kind,
    treeKind: kind,
    platformCode: kind === 'PLATFORM' ? 'AMAZON' : null,
    enabled: true,
    version: 0,
    createdAt: '2026-01-01T00:00:00Z',
    updatedAt: '2026-01-01T00:00:00Z',
  };
}

function node(id: string, treeId: string, enabled = true): CategoryNode {
  return {
    id,
    treeId,
    treeCode: 'T',
    treeKind: 'INTERNAL',
    platformCode: null,
    parentId: null,
    code: id.slice(0, 4),
    name: id.slice(0, 4),
    position: 0,
    enabled,
    depth: 1,
    path: id.slice(0, 4),
    directProductCount: 0,
    subtreeProductCount: 0,
    secondaryProductCount: 0,
    attributeRuleCount: 0,
    specAxisRuleCount: 0,
    names: {},
    version: 0,
    createdAt: '2026-01-01T00:00:00Z',
    updatedAt: '2026-01-01T00:00:00Z',
  };
}

function rule(overrides: Partial<CategoryAttributeRequirement> = {}): CategoryAttributeRequirement {
  return {
    categoryId: 'c1',
    categoryName: '上装',
    attributeId: 'a1',
    attributeCode: 'COLOR',
    attributeName: '颜色',
    dataType: 'OPTION',
    requirement: 'REQUIRED',
    enabled: true,
    inherited: false,
    depth: 0,
    sourceCategoryId: 'c1',
    sourceCategoryName: '上装',
    minValue: null,
    maxValue: null,
    maxLength: null,
    allowFreeText: false,
    position: 10,
    allowedOptions: [],
    condition: null,
    conditionValues: [] as string[],
    version: 0,
    updatedAt: null,
    ...overrides,
  };
}

describe('3.3.1.2 词表与文案', () => {
  it('要求形态与算子都有可读中文，且未知值原样返回', () => {
    expect(requirementLabel('REQUIRED')).toBe('必填');
    expect(requirementLabel('OPTIONAL')).toBe('可选');
    expect(requirementLabel('CONDITIONAL_REQUIRED')).toBe('条件必填');
    expect(requirementLabel('WHATEVER')).toBe('WHATEVER');
    expect(operatorLabel('IN')).toBe('属于');
    expect(operatorLabel('GTE')).toBe('大于等于');
    expect(operatorLabel('??')).toBe('??');
  });

  it('枚举属性不给排序类算子（不可求值）', () => {
    expect(operatorsFor('OPTION')).not.toContain('GT');
    expect(operatorsFor('OPTION')).toContain('IN');
    expect(operatorsFor('DECIMAL')).toContain('GT');
    expect(operatorsFor('DATE')).toContain('LTE');
    expect(operatorsFor('BOOLEAN')).not.toContain('LT');
    expect(operatorProblem('GT', 'OPTION')).toContain('没有大小关系');
    expect(operatorProblem('GT', 'DECIMAL')).toBeNull();
    expect(operatorProblem('IN', 'OPTION')).toBeNull();
    expect(operatorProblem('', 'DECIMAL')).toContain('不能为空');
    expect(operatorProblem('BETWEEN', 'DECIMAL')).toContain('只能是');
  });

  it('条件说明句把前置属性、算子与取值都写出来', () => {
    expect(
      conditionText(
        {
          attributeId: 'a1',
          attributeCode: 'COLOR',
          attributeName: '颜色',
          dataType: 'OPTION',
          operator: 'IN',
        },
        ['红色', '蓝色'],
      ),
    ).toBe('当【颜色】属于 红色 / 蓝色 时必填');
    expect(conditionText(null, ['红色'])).toBe('');
    // 表单 schema 的 dependency 用 label 而不是 attributeName，同一句话必须一致
    expect(
      conditionText(
        {
          attributeId: 'a1',
          attributeCode: 'COLOR',
          label: '颜色',
          operator: 'IN',
          values: ['红色'],
          active: true,
        } as never,
        ['红色', '蓝色'],
      ),
    ).toBe('当【颜色】属于 红色 / 蓝色 时必填');
  });

  it('规则的来源说明区分本节点与继承', () => {
    expect(sourceText(rule())).toBe('本类目配置');
    expect(sourceText(rule({ inherited: true, depth: 0, sourceCategoryName: '服饰' }))).toBe(
      '继承自【服饰】（根类目）',
    );
    expect(sourceText(rule({ inherited: true, depth: 1, sourceCategoryName: '上装' }))).toBe(
      '继承自【上装】（第 1 层祖先）',
    );
  });

  it('覆盖率提示在全覆盖与有缺口时给出不同的话', () => {
    expect(
      overviewHint({
        categoryCount: 10,
        categoriesWithRequirement: 10,
        categoriesWithoutRequirement: 0,
        requirementCount: 30,
        conditionalRequirementCount: 2,
        disabledRequirementCount: 0,
        note: '',
      }),
    ).toBe('10 个内部类目都配了属性要求');
    expect(
      overviewHint({
        categoryCount: 10,
        categoriesWithRequirement: 7,
        categoriesWithoutRequirement: 3,
        requirementCount: 30,
        conditionalRequirementCount: 2,
        disabledRequirementCount: 0,
        note: '',
      }),
    ).toContain('3 个还没有');
  });
});

describe('3.3.1.2 表单校验', () => {
  it('要求形态词表封闭', () => {
    expect(requirementProblem('REQUIRED')).toBeNull();
    expect(requirementProblem('')).toContain('不能为空');
    expect(requirementProblem('MAYBE')).toContain('REQUIRED');
  });

  it('取值约束按数据类型分流，与服务层同口径', () => {
    const base = {
      minValue: '',
      maxValue: '',
      maxLength: '',
      allowFreeText: false,
      allowedOptions: [] as string[],
      requirement: 'REQUIRED',
      conditionValues: [] as string[],
    };
    expect(
      rangeProblem({ ...base, dataType: 'DECIMAL', minValue: '1', maxValue: '10' }),
    ).toBeNull();
    expect(rangeProblem({ ...base, dataType: 'DECIMAL', minValue: '10', maxValue: '1' })).toContain(
      '下限不能大于上限',
    );
    expect(rangeProblem({ ...base, dataType: 'DECIMAL', minValue: '1', maxValue: '' })).toBeNull();
    expect(rangeProblem({ ...base, dataType: 'BOOLEAN', minValue: '1', maxValue: '' })).toContain(
      'DECIMAL',
    );
    expect(rangeProblem({ ...base, dataType: 'TEXT', maxLength: '10' })).toBeNull();
    expect(rangeProblem({ ...base, dataType: 'DECIMAL', maxLength: '10' })).toContain('TEXT');
    expect(rangeProblem({ ...base, dataType: 'TEXT', maxLength: '0' })).toContain('大于 0');
    expect(rangeProblem({ ...base, dataType: 'OPTION', allowedOptions: ['红色'] })).toBeNull();
    expect(rangeProblem({ ...base, dataType: 'OPTION', allowFreeText: true })).toBeNull();
    // 自由填写 + 白名单互相矛盾
    expect(
      rangeProblem({ ...base, dataType: 'OPTION', allowFreeText: true, allowedOptions: ['红色'] }),
    ).toContain('互相矛盾');
    // 非枚举属性不允许自由填写
    expect(rangeProblem({ ...base, dataType: 'TEXT', allowFreeText: true })).toContain('OPTION');
  });

  it('条件取值必须与条件必填成套出现', () => {
    const base = {
      dataType: 'OPTION',
      minValue: '',
      maxValue: '',
      maxLength: '',
      allowFreeText: false,
      allowedOptions: [] as string[],
    };
    // 空集合：条件恒不成立
    expect(
      rangeProblem({ ...base, requirement: 'CONDITIONAL_REQUIRED', conditionValues: [] }),
    ).toContain('至少一个条件取值');
    expect(
      rangeProblem({ ...base, requirement: 'CONDITIONAL_REQUIRED', conditionValues: ['红色'] }),
    ).toBeNull();
    // 非条件必填却带取值
    expect(rangeProblem({ ...base, requirement: 'REQUIRED', conditionValues: ['红色'] })).toContain(
      '才能配置条件取值',
    );
  });

  it('编辑表单的整体校验先看形态再看取值', () => {
    const input = emptyRequirementInput('a1', 'DECIMAL');
    expect(requirementInputProblem(input, 'DECIMAL')).toBeNull();

    expect(requirementInputProblem({ ...input, requirement: 'NOPE' }, 'DECIMAL')).toContain(
      'REQUIRED',
    );
    expect(requirementInputProblem({ ...input, attributeId: '' }, 'DECIMAL')).toContain(
      '请选择属性',
    );

    // 条件算子与前置属性类型不相容
    expect(
      requirementInputProblem(
        {
          ...input,
          requirement: 'CONDITIONAL_REQUIRED',
          condition: {
            attributeId: 'a2',
            attributeCode: 'COLOR',
            attributeName: '颜色',
            dataType: 'OPTION',
            operator: 'GT',
          },
          conditionValues: [{ value: '红色', position: 0 }],
        },
        'DECIMAL',
      ),
    ).toContain('没有大小关系');
  });
});

describe('3.3.1.2 类型与来源', () => {
  it('枚举属性的空表单不带条件（避免开局就红一片）', () => {
    expect(emptyRequirementInput('a1', 'OPTION').condition).toBeNull();
    // 非枚举属性给出条件骨架，让「条件必填」有地方可填
    expect(emptyRequirementInput('a1', 'DECIMAL').condition?.operator).toBe('EQ');
  });

  it('规则转表单时保留全部约束与乐观锁版本', () => {
    const input = toRequirementInput(
      rule({
        requirement: 'CONDITIONAL_REQUIRED',
        maxLength: null,
        position: 30,
        version: 7,
        condition: {
          attributeId: 'a2',
          attributeCode: 'COLOR',
          attributeName: '颜色',
          dataType: 'OPTION',
          operator: 'IN',
        },
      }),
    );
    expect(input.attributeId).toBe('a1');
    expect(input.requirement).toBe('CONDITIONAL_REQUIRED');
    expect(input.position).toBe(30);
    expect(input.version).toBe(7);
    expect(input.condition?.attributeId).toBe('a2');
    // 条件取值另表存储，不在 DTO 里重复携带，因此这里是空数组而不是丢数据
    expect(input.conditionValues).toEqual([]);
  });

  it('条件取值的文本与序号互转', () => {
    expect(
      conditionValueTexts([
        { value: '红色', position: 0 },
        { value: '蓝色', position: 1 },
      ]),
    ).toEqual(['红色', '蓝色']);
    expect(conditionValueTexts(null)).toEqual([]);
    expect(conditionValueTexts([null as never])).toEqual([]);
  });

  it('类目下拉只给内部管理分类树的启用节点', () => {
    const trees = [tree('t-internal', 'INTERNAL'), tree('t-amazon', 'PLATFORM')];
    const nodes = [
      node('internal-1', 't-internal'),
      node('internal-off', 't-internal', false),
      node('amazon-1', 't-amazon'),
    ];
    expect(internalCategories(trees, nodes).map((n) => n.id)).toEqual(['internal-1']);
  });

  it('错误文案对未知异常也有兜底', () => {
    expect(errorText(new Error('boom'))).toBe('boom');
    expect(errorText('raw string')).toBe('请求失败，请稍后重试');
  });
});

// 类型层面的编译期约束：输入记录的必填字段不能漏。
describe('3.3.1.2 契约形状', () => {
  it('写入记录包含服务层要求的全部字段', () => {
    const input: CategoryAttributeRequirementInput = emptyRequirementInput('a1', 'TEXT');
    expect(Object.keys(input).sort()).toEqual(
      [
        'allowFreeText',
        'allowedOptions',
        'attributeId',
        'condition',
        'conditionValues',
        'enabled',
        'maxLength',
        'maxValue',
        'minValue',
        'position',
        'requirement',
        'version',
      ].sort(),
    );
  });
});
