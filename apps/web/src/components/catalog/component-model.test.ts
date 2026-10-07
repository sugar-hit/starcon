import { describe, expect, it } from 'vitest';
import {
  MAX_COMPONENTS_PER_VERSION,
  PROBLEM_CODES,
  QUANTITY_SCALE,
  addComponentProblem,
  canEditComponent,
  checkSummary,
  componentOrigin,
  componentSummary,
  frozenHint,
  groupProblems,
  nestedHint,
  noteProblem,
  problemHint,
  problemLabel,
  problemTone,
  quantityProblem,
  quantityText,
  roleProblem,
  toQuantityNumber,
} from './component-model';
import type {
  BundleComponentCheck,
  BundleComponentItem,
  BundleComponentProblem,
} from '../../../../../packages/api-contracts/src/catalog';

function item(overrides: Partial<BundleComponentItem> = {}): BundleComponentItem {
  return {
    id: 'c1',
    versionId: 'v1',
    bundleId: 'b1',
    bundleCode: 'BND-SUMMER',
    versionNo: 'v1',
    versionName: '夏季套装',
    versionStatus: 'DRAFT',
    versionStatusLabel: '草稿',
    skuId: 's1',
    skuCode: 'SKU-001',
    skuName: '主机',
    quantity: 2,
    role: '',
    note: '',
    nested: false,
    linkedBundleId: null,
    linkedBundleCode: null,
    editable: true,
    rowVersion: 0,
    ...overrides,
  };
}

function problem(
  code: string,
  overrides: Partial<BundleComponentProblem> = {},
): BundleComponentProblem {
  return {
    code,
    message: `${code} 的说明`,
    skuId: null,
    skuCode: null,
    componentId: null,
    ...overrides,
  };
}

function check(overrides: Partial<BundleComponentCheck> = {}): BundleComponentCheck {
  return {
    versionId: 'v1',
    bundleId: 'b1',
    bundleCode: 'BND-SUMMER',
    versionNo: 'v1',
    versionStatus: 'DRAFT',
    versionStatusLabel: '草稿',
    componentCount: 1,
    totalQuantity: 2,
    editable: true,
    ok: true,
    problems: [],
    note: '',
    ...overrides,
  };
}

// ================================================================ 可写性

describe('已发布版本的组件一律冻结', () => {
  it('草稿可改、已发布不可改', () => {
    expect(canEditComponent(item({ editable: true }))).toBe(true);
    expect(canEditComponent(item({ editable: false }))).toBe(false);
  });

  it('冻结提示要指出唯一正确的动作是新建版本', () => {
    const hint = frozenHint(item({ bundleCode: 'BND-SUMMER', versionNo: 'v1', editable: false }));
    expect(hint).toContain('BND-SUMMER');
    expect(hint).toContain('已发布');
    expect(hint).toContain('新建一个版本');
    // 蓝图纪律：历史订单冻结的是当时的组件清单。
    expect(hint).toContain('历史订单冻结');
  });
});

// ================================================================ 嵌套

describe('嵌套组件必须一眼看出来', () => {
  it('非嵌套行没有嵌套说明', () => {
    expect(nestedHint(item({ nested: false }))).toBe('');
  });

  it('嵌套行说明「这行不是终点」并解释为什么防环', () => {
    const hint = nestedHint(item({ nested: true, linkedBundleCode: 'BND-BASE' }));
    expect(hint).toContain('BND-BASE');
    expect(hint).toContain('不是终点');
    // 循环检测存在的理由要写在界面上，否则没人知道为什么要防它。
    expect(hint).toContain('无限递归');
  });
});

// ================================================================ 数量校验
//
// 与后端 BundleRules.quantityProblem / numeric(19,6) 同一口径。

describe('组件数量校验', () => {
  it('必须大于 0', () => {
    expect(quantityProblem('')).toContain('不能为空');
    expect(quantityProblem('0')).toContain('必须大于 0');
    expect(quantityProblem('-1')).toContain('必须大于 0');
    expect(quantityProblem('abc')).toContain('不是数字');
  });

  it('小数位不超过 6（numeric(19,6)）', () => {
    expect(quantityProblem('0.5')).toBeNull();
    expect(quantityProblem('1.123456')).toBeNull();
    expect(quantityProblem('1.1234567')).toContain(`最多 ${QUANTITY_SCALE} 位小数`);
  });

  it('加组件前先查 SKU 与数量', () => {
    expect(addComponentProblem('', '1')).toContain('都要选一个 SKU');
    expect(addComponentProblem('s1', '0')).toContain('必须大于 0');
    expect(addComponentProblem('s1', '1')).toBeNull();
  });

  it('角色与备注有长度上限', () => {
    expect(roleProblem('')).toBeNull();
    expect(roleProblem('x'.repeat(33))).toContain('32');
    expect(noteProblem('x'.repeat(201))).toContain('200');
    expect(noteProblem('x'.repeat(200))).toBeNull();
  });

  it('数量上限与后端共用同一个数（50）', () => {
    // 前后端不一致会出现「前端放行、后端拒绝」的往返浪费。
    expect(MAX_COMPONENTS_PER_VERSION).toBe(50);
  });
});

// ================================================================ 展示

describe('组件行的展示', () => {
  it('版本归属写成「套装编码 · 版本号」', () => {
    expect(componentOrigin(item({ bundleCode: 'BND-A', versionNo: 'v2' }))).toBe('BND-A · v2');
  });

  it('数量不做前端二次运算，只按后端给的值显示', () => {
    expect(quantityText(2)).toBe('2');
    expect(quantityText(0.5)).toBe('0.5');
    expect(quantityText(null)).toBe('—');
  });

  it('空清单说清是「还没填」而不是「这个套装没有组件」', () => {
    expect(componentSummary([], null, 0)).toContain('还没有任何组件');
  });

  it('清单总结带上合计与嵌套数量', () => {
    const rows = [item({ quantity: 2 }), item({ quantity: 0.5, nested: true, linkedBundleCode: 'BND-B' })];
    const summary = componentSummary(rows, 2.5, 1);
    expect(summary).toContain('2 个组件');
    expect(summary).toContain('每件合计 2.5');
    expect(summary).toContain('1 个是嵌套套装');
  });

  it('编辑期的字符串数量转成契约要求的 number', () => {
    expect(toQuantityNumber('0.5')).toBe(0.5);
    expect(toQuantityNumber('3')).toBe(3);
  });
});

// ================================================================ 校验结果

describe('校验结果按问题码分组', () => {
  it('通过时说清「没有发现问题」，不通过时给剩余条数', () => {
    expect(checkSummary(check())).toContain('没有发现');
    expect(checkSummary(check({ ok: false, problems: [problem('QUANTITY_INVALID')] })))
      .toContain('1 条问题');
  });

  it('按 PROBLEM_CODES 的顺序分组：先结构性、后具体行', () => {
    const groups = groupProblems([
      problem('QUANTITY_INVALID'),
      problem('VERSION_FROZEN'),
      problem('CIRCULAR_REFERENCE'),
    ]);
    expect(groups.map((g) => g.code)).toEqual([
      'VERSION_FROZEN',
      'CIRCULAR_REFERENCE',
      'QUANTITY_INVALID',
    ]);
  });

  it('同码的问题归到同一组', () => {
    const groups = groupProblems([
      problem('QUANTITY_INVALID', { skuCode: 'A' }),
      problem('QUANTITY_INVALID', { skuCode: 'B' }),
    ]);
    expect(groups).toHaveLength(1);
    expect(groups[0]?.items).toHaveLength(2);
  });

  it('后端返回词表外的问题码时照样显示，不静默丢掉', () => {
    // 丢一条问题 = 让人以为「校验通过了」，那比报错更糟。
    const groups = groupProblems([problem('SOMETHING_NEW')]);
    expect(groups).toHaveLength(1);
    expect(groups[0]?.code).toBe('OTHER');
    expect(groups[0]?.items).toHaveLength(1);
  });

  it('没有问题时返回空数组而不是 null', () => {
    expect(groupProblems([])).toEqual([]);
  });

  it('每个问题码都有中文标签与说明', () => {
    for (const { code } of PROBLEM_CODES) {
      expect(problemLabel(code)).not.toBe(code);
      expect(problemHint(code).length).toBeGreaterThan(0);
    }
  });

  it('结构性与成环是红，个别行的错是黄', () => {
    expect(problemTone('VERSION_FROZEN')).toBe('danger');
    expect(problemTone('CIRCULAR_REFERENCE')).toBe('danger');
    expect(problemTone('EMPTY_COMPONENT_LIST')).toBe('danger');
    expect(problemTone('QUANTITY_INVALID')).toBe('warning');
    expect(problemTone('COMPONENT_SKU_MISSING')).toBe('warning');
  });
});
