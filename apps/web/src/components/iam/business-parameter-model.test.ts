import { describe, expect, it } from 'vitest';
import {
  CATEGORY_LABELS,
  SCOPE_PRIORITY,
  type ParameterDefinition,
  effectiveSummary,
  findExistingOverride,
  formatValue,
  groupByCategory,
  historySummary,
  scopeLabel,
  sourceLabel,
  splitCodes,
  validateScope,
  validateValue,
} from './business-parameter-model';

const enumParam: ParameterDefinition = {
  code: 'COST_BASIS',
  name: '成本口径默认值',
  category: 'COST',
  valueType: 'ENUM',
  defaultValue: 'MOVING_AVERAGE',
  allowedValueCodes: ['MOVING_AVERAGE', 'FIFO', 'LIFO', 'IMPORTED_COST'],
  validationPattern: '',
  unit: '',
  description: '',
};

const multiParam: ParameterDefinition = {
  code: 'AFTERSALE_MUST_REFUND_REASONS',
  name: '售后必退情形',
  category: 'AFTERSALE',
  valueType: 'MULTI_ENUM',
  defaultValue: 'DAMAGED_ON_ARRIVAL,MISSING_ITEM',
  allowedValueCodes: ['DAMAGED_ON_ARRIVAL', 'MISSING_ITEM', 'WRONG_ITEM'],
  validationPattern: '^[A-Z][A-Z0-9_]*(,[A-Z][A-Z0-9_]*)*$',
  unit: '',
  description: '',
};

const daysParam: ParameterDefinition = {
  code: 'AFTERSALE_RETURN_WINDOW_DAYS',
  name: '售后政策期限',
  category: 'AFTERSALE',
  valueType: 'INTEGER',
  defaultValue: '30',
  allowedValueCodes: [],
  validationPattern: '^[0-9]{1,4}$',
  unit: '天',
  description: '',
};

const currencyParam: ParameterDefinition = {
  code: 'DISPLAY_CURRENCY',
  name: '币种展示默认值',
  category: 'DISPLAY',
  valueType: 'STRING',
  defaultValue: 'CNY',
  allowedValueCodes: [],
  validationPattern: '^[A-Z]{3}$',
  unit: '',
  description: '',
};

describe('splitCodes', () => {
  it('拆开逗号分隔的取值并去掉空项', () => {
    expect(splitCodes('A,B , C')).toEqual(['A', 'B', 'C']);
    expect(splitCodes('')).toEqual([]);
    expect(splitCodes(null)).toEqual([]);
  });
});

describe('formatValue', () => {
  it('数值型带单位，多选拼成可读列表', () => {
    expect(formatValue(daysParam, '45')).toBe('45 天');
    expect(formatValue(multiParam, 'MISSING_ITEM,WRONG_ITEM')).toBe('MISSING_ITEM、WRONG_ITEM');
  });

  it('多选为空时显示"未设置"，而不是一个空行', () => {
    expect(formatValue(multiParam, '')).toBe('未设置');
  });

  it('缺值显示占位符而不是渲染出 undefined', () => {
    expect(formatValue(currencyParam, null)).toBe('—');
    expect(formatValue(currencyParam, undefined)).toBe('—');
  });
});

describe('sourceLabel', () => {
  it('继承来的值必须标成"出厂默认"，不能显示成空', () => {
    expect(sourceLabel({ sourceScope: 'DEFAULT', inherited: true })).toBe('出厂默认');
    expect(sourceLabel({ sourceScope: 'SHOP', inherited: false })).toBe('店铺配置');
  });

  it('未知来源回退成原始编码而不是 undefined', () => {
    expect(sourceLabel({ sourceScope: 'WEIRD', inherited: false })).toBe('WEIRD');
  });
});

describe('scopeLabel', () => {
  it('三档各自给出可读范围', () => {
    expect(scopeLabel('GLOBAL', {})).toBe('全租户');
    expect(
      scopeLabel('LEGAL_ENTITY', { legalEntityCode: 'US01', legalEntityName: '美国公司' }),
    ).toBe('法人 · US01');
    expect(scopeLabel('SHOP', { shopLabel: '美国店铺' })).toBe('店铺 · 美国店铺');
  });

  it('法人没有编码时退回名称，店铺没有标签时给出兜底', () => {
    expect(scopeLabel('LEGAL_ENTITY', { legalEntityName: '美国公司' })).toBe('法人 · 美国公司');
    expect(scopeLabel('SHOP', {})).toBe('店铺 · 未命名');
  });
});

describe('findExistingOverride', () => {
  const overrides = [
    { parameterCode: 'COST_BASIS', scopeType: 'GLOBAL', legalEntityId: null, shopId: null },
    {
      parameterCode: 'COST_BASIS',
      scopeType: 'LEGAL_ENTITY',
      legalEntityId: 'e-1',
      shopId: null,
    },
    {
      parameterCode: 'COST_BASIS',
      scopeType: 'LEGAL_ENTITY',
      legalEntityId: 'e-2',
      shopId: null,
    },
    { parameterCode: 'COST_BASIS', scopeType: 'SHOP', legalEntityId: null, shopId: 's-1' },
  ];

  it('三档各自命中自己那一条', () => {
    expect(
      findExistingOverride(overrides, 'COST_BASIS', {
        scopeType: 'GLOBAL',
        legalEntityId: null,
        shopId: null,
      })?.scopeType,
    ).toBe('GLOBAL');
    expect(
      findExistingOverride(overrides, 'COST_BASIS', {
        scopeType: 'LEGAL_ENTITY',
        legalEntityId: 'e-2',
        shopId: null,
      })?.legalEntityId,
    ).toBe('e-2');
    expect(
      findExistingOverride(overrides, 'COST_BASIS', {
        scopeType: 'SHOP',
        legalEntityId: null,
        shopId: 's-1',
      })?.shopId,
    ).toBe('s-1');
  });

  it('法人档必须比对法人 id：只比档位会把两条法人配置混成一条', () => {
    expect(
      findExistingOverride(overrides, 'COST_BASIS', {
        scopeType: 'LEGAL_ENTITY',
        legalEntityId: 'e-3',
        shopId: null,
      }),
    ).toBeNull();
  });

  it('参数不同或档位不同都算未配置', () => {
    expect(
      findExistingOverride(overrides, 'INVENTORY_POLICY', {
        scopeType: 'GLOBAL',
        legalEntityId: null,
        shopId: null,
      }),
    ).toBeNull();
    expect(
      findExistingOverride(overrides, 'COST_BASIS', {
        scopeType: 'SHOP',
        legalEntityId: null,
        shopId: 's-9',
      }),
    ).toBeNull();
  });
});

describe('validateValue', () => {
  it('枚举必须落在码表允许范围内', () => {
    expect(validateValue(enumParam, 'FIFO')).toBe('');
    expect(validateValue(enumParam, 'SOMETHING')).toContain('MOVING_AVERAGE');
    expect(validateValue(enumParam, '')).toContain('必须是');
  });

  it('多选逐项校验：其中一项非法即整体不通过', () => {
    expect(validateValue(multiParam, 'MISSING_ITEM,WRONG_ITEM')).toBe('');
    expect(validateValue(multiParam, 'MISSING_ITEM,NOPE')).toContain('NOPE');
  });

  it('多选允许空串——"当前不设必退情形"是配置不是漏填', () => {
    expect(validateValue(multiParam, '')).toBe('');
  });

  it('数值必须是非负且匹配码表正则', () => {
    expect(validateValue(daysParam, '45')).toBe('');
    expect(validateValue(daysParam, '')).toContain('需要一个数值');
    expect(validateValue(daysParam, 'abc')).toContain('需要一个数值');
    expect(validateValue(daysParam, '-5')).toContain('负数');
    // 超出码表里 ^[0-9]{1,4}$ 的位数上限
    expect(validateValue(daysParam, '99999')).toContain('格式需满足');
  });

  it('文本参数不能为空且要满足正则', () => {
    expect(validateValue(currencyParam, 'USD')).toBe('');
    expect(validateValue(currencyParam, '')).toContain('不能为空');
    expect(validateValue(currencyParam, 'usd')).toContain('格式需满足');
  });

  it('码表里的正则坏了不崩界面：放行，交由后端报配置问题', () => {
    expect(validateValue({ ...currencyParam, validationPattern: '([' }, 'USD')).toBe('');
  });
});

describe('validateScope', () => {
  it('全租户档不许带法人或店铺', () => {
    expect(
      validateScope({ scopeType: 'GLOBAL', legalEntityId: 'e-1', shopId: null }, ''),
    ).toContain('不能指定');
    expect(validateScope({ scopeType: 'GLOBAL', legalEntityId: null, shopId: null }, '')).toBe('');
  });

  it('法人档必须有法人且不能同时带店铺', () => {
    expect(
      validateScope({ scopeType: 'LEGAL_ENTITY', legalEntityId: null, shopId: null }, ''),
    ).toContain('必须指定法人');
    expect(
      validateScope({ scopeType: 'LEGAL_ENTITY', legalEntityId: 'e-1', shopId: 's-1' }, ''),
    ).toContain('不能同时指定店铺');
  });

  it('店铺档必须有店铺与店铺名称', () => {
    expect(
      validateScope({ scopeType: 'SHOP', legalEntityId: null, shopId: null }, '美国店铺'),
    ).toContain('必须指定店铺');
    expect(
      validateScope({ scopeType: 'SHOP', legalEntityId: null, shopId: 's-1' }, '  '),
    ).toContain('店铺名称');
    expect(
      validateScope({ scopeType: 'SHOP', legalEntityId: null, shopId: 's-1' }, '美国店铺'),
    ).toBe('');
  });
});

describe('effectiveSummary', () => {
  it('值与来源必须一起出现：同一个值可能来自默认值也可能来自配置', () => {
    expect(
      effectiveSummary(daysParam, {
        parameterCode: daysParam.code,
        value: '45',
        valueCodes: [],
        sourceScope: 'LEGAL_ENTITY',
        inherited: false,
      }),
    ).toBe('45 天（法人配置）');
    expect(
      effectiveSummary(daysParam, {
        parameterCode: daysParam.code,
        value: '30',
        valueCodes: [],
        sourceScope: 'DEFAULT',
        inherited: true,
      }),
    ).toBe('30 天（出厂默认）');
  });
});

describe('historySummary', () => {
  it('新增 / 修改 / 删除三种形态都能读成一句话', () => {
    expect(
      historySummary({
        parameterName: '成本口径默认值',
        action: 'UPDATED',
        beforeValue: 'FIFO',
        afterValue: 'LIFO',
        actorName: 'local-admin',
      }),
    ).toBe('成本口径默认值 修改：FIFO → LIFO · local-admin');
    expect(
      historySummary({
        parameterName: '成本口径默认值',
        action: 'CREATED',
        beforeValue: null,
        afterValue: 'FIFO',
        actorName: null,
      }),
    ).toBe('成本口径默认值 新增：（无） → FIFO');
    expect(
      historySummary({
        parameterName: '成本口径默认值',
        action: 'DELETED',
        beforeValue: 'FIFO',
        afterValue: null,
        actorName: 'local-admin',
      }),
    ).toBe('成本口径默认值 删除（退回继承）：FIFO → （无） · local-admin');
  });
});

describe('groupByCategory', () => {
  it('按固定分类顺序分区，空分类不出现在结果里', () => {
    const rows = [
      { category: 'DISPLAY', code: 'DISPLAY_CURRENCY' },
      { category: 'COST', code: 'COST_BASIS' },
    ];
    const groups = groupByCategory(rows);
    expect(groups.map((g) => g.category)).toEqual(['COST', 'DISPLAY']);
    // 首个分区是 COST，标签取中文名而不是原始编码
    expect(groups[0]?.label).toBe(CATEGORY_LABELS.COST);
    expect(groups[0]?.rows.map((r) => r.code)).toEqual(['COST_BASIS']);
    expect(groupByCategory(rows, ['AFTERSALE'])).toEqual([]);
  });
});

describe('SCOPE_PRIORITY', () => {
  it('优先级顺序必须是 店铺 > 法人 > 全局 > 出厂默认', () => {
    // Record 索引在 noUncheckedIndexedAccess 下是 number | undefined：
    // 未知档位排到最末（最不优先），而不是让比较拿到 undefined。
    const priority = (scope: string) => SCOPE_PRIORITY[scope] ?? Number.MAX_SAFE_INTEGER;
    expect(priority('SHOP')).toBeLessThan(priority('LEGAL_ENTITY'));
    expect(priority('LEGAL_ENTITY')).toBeLessThan(priority('GLOBAL'));
    expect(priority('GLOBAL')).toBeLessThan(priority('DEFAULT'));
    expect(priority('NOT_A_SCOPE')).toBe(Number.MAX_SAFE_INTEGER);
  });
});
