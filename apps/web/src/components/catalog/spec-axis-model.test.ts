import { describe, expect, it } from 'vitest';
import type { SpecAxisOption, SpecAxisPanel } from '../../../../../packages/api-contracts/src/index';
import {
  axisUsageText,
  codeProblem,
  draftToAxisInput,
  draftToCategoryRuleInput,
  draftToOptionInput,
  emptyAxisDraft,
  emptyCategoryRuleDraft,
  emptyOptionDraft,
  errorText,
  matrixHint,
  normalizeCode,
  optionSourceText,
  requiredAxisHint,
  validateAxis,
  validateCategoryRule,
  validateOption,
  valueProblem,
} from './spec-axis-model';

function panel(overrides: Partial<SpecAxisPanel> = {}): SpecAxisPanel {
  return {
    spuId: 'spu-1',
    spuCode: 'SPU-1',
    spuName: '测试商品',
    spuCategory: '服饰',
    axes: [],
    matrixRowCount: 0,
    boundAxisCount: 0,
    missingRequiredAxisCount: 0,
    requiredAxisNotes: [],
    ...overrides,
  };
}

function option(overrides: Partial<SpecAxisOption> = {}): SpecAxisOption {
  return {
    value: '深空灰',
    position: 0,
    enabled: true,
    sourceAttributeId: 'attr-1',
    sourceAttributeCode: 'COLOR',
    sourceAttributeName: '颜色',
    createdAt: '2026-01-01T00:00:00Z',
    ...overrides,
  };
}

describe('轴编码', () => {
  it('接受 ASCII 标识形态并规范化为大写', () => {
    expect(codeProblem('COLOR')).toBeNull();
    expect(codeProblem('SIZE_2')).toBeNull();
    expect(normalizeCode(' color ')).toBe('COLOR');
  });

  it('拒绝空值、中文与超长', () => {
    expect(codeProblem('')).toContain('不能为空');
    // 轴码是机器标识，中文只允许出现在选项值里。
    expect(codeProblem('颜色')).toContain('英文字母');
    expect(codeProblem('A'.repeat(65))).toContain('长度');
  });
});

describe('选项值', () => {
  it('接受真实规格文案', () => {
    expect(valueProblem('深空灰')).toBeNull();
    expect(valueProblem('XL')).toBeNull();
    expect(valueProblem('500ml')).toBeNull();
    expect(valueProblem('2 件装')).toBeNull();
    expect(valueProblem('A'.repeat(100))).toBeNull();
  });

  it('长度上限与属性选项值源一致（100）', () => {
    expect(valueProblem('A'.repeat(101))).toContain('100');
  });

  it('拒绝空值与非法字符', () => {
    expect(valueProblem('   ')).toContain('不能为空');
    expect(valueProblem('红#色')).toContain('只能使用');
  });
});

describe('矩阵提示区分两种「空」', () => {
  it('一条轴都没绑 → 引导去绑轴', () => {
    expect(matrixHint(panel())).toContain('还没有绑定任何规格轴');
  });

  it('绑了但没选项 → 引导去配选项', () => {
    const hint = matrixHint(
      panel({
        boundAxisCount: 1,
        matrixRowCount: 0,
        axes: [
          {
            specAxisId: 'a1',
            specAxisCode: 'COLOR',
            specAxisName: '颜色',
            attributeDefinitionId: 'attr-1',
            attributeCode: 'COLOR',
            attributeName: '颜色',
            position: 0,
            skuCount: 0,
            enabledOptionCount: 0,
            totalOptionCount: 0,
            options: [],
          },
        ],
      }),
    );
    expect(hint).toContain('还没有可用选项');
    expect(hint).not.toContain('还没有绑定任何规格轴');
  });

  it('有配置时给出真实组合数与各轴计数', () => {
    const hint = matrixHint(
      panel({
        boundAxisCount: 2,
        matrixRowCount: 6,
        axes: [
          {
            specAxisId: 'a1',
            specAxisCode: 'COLOR',
            specAxisName: '颜色',
            attributeDefinitionId: 'attr-1',
            attributeCode: 'COLOR',
            attributeName: '颜色',
            position: 0,
            skuCount: 3,
            enabledOptionCount: 2,
            totalOptionCount: 2,
            options: [],
          },
          {
            specAxisId: 'a2',
            specAxisCode: 'SIZE',
            specAxisName: '尺码',
            attributeDefinitionId: 'attr-2',
            attributeCode: 'SIZE',
            attributeName: '尺码',
            position: 1,
            skuCount: 3,
            enabledOptionCount: 3,
            totalOptionCount: 3,
            options: [],
          },
        ],
      }),
    );
    expect(hint).toContain('6');
    expect(hint).toContain('颜色 2');
    expect(hint).toContain('尺码 3');
  });
});

describe('值源说明', () => {
  it('显式点破规格选项同时是属性选项', () => {
    expect(optionSourceText(option())).toContain('属性');
    expect(optionSourceText(option())).toContain('颜色');
  });

  it('承载属性缺失时给出警示而不是画成正常', () => {
    const text = optionSourceText(
      option({ sourceAttributeId: '' as unknown as string, sourceAttributeName: '' }),
    );
    expect(text).toContain('找不到承载属性');
  });
});

describe('类目必绑提示', () => {
  it('无规则时说明未配置', () => {
    expect(requiredAxisHint(panel())).toContain('没有配置');
  });

  it('有缺口时报出缺口数量', () => {
    const hint = requiredAxisHint(
      panel({ missingRequiredAxisCount: 2, requiredAxisNotes: ['a', 'b'] }),
    );
    expect(hint).toContain('2');
    expect(hint).toContain('尚未绑定');
  });

  it('全部绑定时报已齐', () => {
    expect(
      requiredAxisHint(panel({ boundAxisCount: 1, requiredAxisNotes: ['已绑定'] })),
    ).toContain('已全部绑定');
  });
});

describe('表单校验', () => {
  it('新建轴校验编码，编辑轴不校验编码（建后不可改）', () => {
    expect(
      validateAxis({ ...emptyAxisDraft(), code: '颜色', name: '颜色' }, true).join(''),
    ).not.toBe('');
    expect(validateAxis({ ...emptyAxisDraft(), code: '颜色', name: '颜色' }, false)).toHaveLength(0);
  });

  it('轴名称必填且限长', () => {
    expect(validateAxis({ ...emptyAxisDraft(), code: 'COLOR', name: '  ' }, true)).toContain(
      '规格轴名称不能为空',
    );
    expect(
      validateAxis({ ...emptyAxisDraft(), code: 'COLOR', name: 'x'.repeat(101) }, true).join(''),
    ).toContain('100');
  });

  it('重复选项提示为幂等更新而不是新增', () => {
    const errors = validateOption({ ...emptyOptionDraft(), value: '红' }, [option({ value: '红' })]);
    expect(errors.join('')).toContain('已存在');
  });

  it('选项位置必须是非负整数', () => {
    expect(
      validateOption({ ...emptyOptionDraft(), value: '红', position: '-1' }, []).join(''),
    ).toContain('排序');
    expect(
      validateOption({ ...emptyOptionDraft(), value: '红', position: 'x' }, []).join(''),
    ).toContain('排序');
  });

  it('类目规则校验类目与位置', () => {
    expect(validateCategoryRule(emptyCategoryRuleDraft(''))).toContain('类目不能为空');
    expect(
      validateCategoryRule({ ...emptyCategoryRuleDraft('服饰'), position: 'a' }).join(''),
    ).toContain('排序');
  });
});

describe('草稿转换', () => {
  it('轴草稿提交大写编码与去除空白名称', () => {
    const input = draftToAxisInput({ code: ' color ', name: ' 颜色 ', enabled: true, version: 0 });
    expect(input.code).toBe('COLOR');
    expect(input.name).toBe('颜色');
  });

  it('选项草稿把位置转成数字', () => {
    const input = draftToOptionInput({ value: ' 红 ', position: '3', enabled: false });
    expect(input.value).toBe('红');
    expect(input.position).toBe(3);
    expect(input.enabled).toBe(false);
  });

  it('类目规则草稿去空白并转数字', () => {
    const input = draftToCategoryRuleInput({
      category: ' 服饰 ',
      required: true,
      position: '2',
    });
    expect(input).toEqual({ category: '服饰', required: true, position: 2 });
  });
});

describe('摘要与错误文案', () => {
  it('轴字典行摘要带出两个删除前必看的计数', () => {
    const text = axisUsageText({
      id: 'a1',
      code: 'COLOR',
      name: '颜色',
      enabled: true,
      optionCount: 3,
      enabledOptionCount: 2,
      boundSpuCount: 5,
      version: 0,
      createdAt: '2026-01-01T00:00:00Z',
      updatedAt: '2026-01-01T00:00:00Z',
    });
    expect(text).toContain('3 个选项');
    expect(text).toContain('5 个 SPU');
  });

  it('错误文案保留后端给出的可读原因', () => {
    expect(errorText(new Error('规格轴已被他人修改，请刷新后重试'))).toContain('已被他人修改');
    expect(errorText(undefined, '兜底')).toBe('兜底');
  });
});