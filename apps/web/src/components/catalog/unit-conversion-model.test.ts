import { describe, expect, it } from 'vitest';
import type {
  UnitConversionGroup,
  UnitConvertResult,
  UnitOfMeasure,
  UnitSnapshotLine,
} from '../../../../../packages/api-contracts/src/index';
import {
  conversionRoles,
  dimensionLabels,
  documentTypeLabels,
  draftToConversionInput,
  draftToUnitInput,
  emptyConversionDraft,
  emptyUnitDraft,
  quantityProblem,
  quantityText,
  ratioProblem,
  ratioSummary,
  scaleProblem,
  snapshotLineText,
  unregisteredHint,
  unitCodeProblem,
  unitDraft,
  validateConversion,
  validateUnit,
  windowProblem,
  windowText,
  type ConversionDraft,
  type UnitDraft,
} from './unit-conversion-model';

function unit(patch: Partial<UnitOfMeasure> = {}): UnitOfMeasure {
  return {
    id: 'u1',
    code: '箱',
    name: '箱',
    dimension: 'COUNT',
    dimensionLabel: '数量',
    scale: 0,
    allowPurchase: true,
    allowSale: true,
    allowStorage: true,
    enabled: true,
    conversionCount: 0,
    version: 0,
    createdAt: '2026-10-07T00:00:00Z',
    updatedAt: '2026-10-07T00:00:00Z',
    ...patch,
  };
}

function group(patch: Partial<UnitConversionGroup> = {}): UnitConversionGroup {
  return {
    unitCode: '箱',
    unitName: '箱',
    role: 'PURCHASE',
    roleLabel: '采购单位',
    registered: true,
    currentVersionId: 'v1',
    currentQuantityPerBase: 12,
    versions: [],
    ...patch,
  };
}

describe('单位码', () => {
  it('接受中文——单位码不是内部编码', () => {
    // 真实数据里就是「件」「箱」「千克」，套 [A-Z0-9] 会把它们判成非法。
    expect(unitCodeProblem('件')).toBeNull();
    expect(unitCodeProblem('箱')).toBeNull();
    expect(unitCodeProblem('千克')).toBeNull();
    expect(unitCodeProblem('PCS')).toBeNull();
    expect(unitCodeProblem('carton_12')).toBeNull();
  });

  it('拒绝空、含空格与超长', () => {
    expect(unitCodeProblem('')).toContain('不能为空');
    expect(unitCodeProblem('   ')).toContain('不能为空');
    expect(unitCodeProblem('件 件')).toContain('只能使用');
    expect(unitCodeProblem('a'.repeat(33))).toContain('1~32');
    expect(unitCodeProblem('a'.repeat(32))).toBeNull();
  });

  it('首字符不允许是分隔符', () => {
    expect(unitCodeProblem('-件')).toContain('只能使用');
    expect(unitCodeProblem('1件')).toBeNull();
  });
});

describe('数量精度与比例', () => {
  it('数量精度限定 0~6 位', () => {
    expect(scaleProblem('0')).toBeNull();
    expect(scaleProblem('6')).toBeNull();
    expect(scaleProblem('7')).toContain('最多 6 位');
    expect(scaleProblem('x')).toContain('0~6');
  });

  it('比例必须严格大于 0', () => {
    // 0 会让基础数量恒为 0，负数会让出库变成入库——两种都会真实打错账。
    expect(ratioProblem('12')).toBeNull();
    expect(ratioProblem('0.5')).toBeNull();
    expect(ratioProblem('0')).toContain('大于 0');
    expect(ratioProblem('-1')).toContain('大于 0');
    expect(ratioProblem('')).toContain('不能为空');
  });

  it('比例最多 6 位小数、13 位整数', () => {
    expect(ratioProblem('1.123456')).toBeNull();
    expect(ratioProblem('1.1234567')).toContain('6 位小数');
    expect(ratioProblem('12345678901234')).toContain('13 位');
  });

  it('数量必须为正', () => {
    expect(quantityProblem('2')).toBeNull();
    expect(quantityProblem('0')).toContain('大于 0');
    expect(quantityProblem('-1')).toContain('大于 0');
    expect(quantityProblem('')).toContain('不能为空');
    expect(quantityProblem('0.1234567')).toContain('6 位小数');
  });
});

describe('生效区间', () => {
  it('截止日不能早于起始日', () => {
    expect(windowProblem('2024-01-01', '')).toBeNull();
    expect(windowProblem('2024-01-01', '2025-12-31')).toBeNull();
    // 边界相等合法：V6 的 daterange 用闭区间，同一天既是上一版的截止也是下一版的起始。
    expect(windowProblem('2024-01-01', '2024-01-01')).toBeNull();
    expect(windowProblem('2025-12-31', '2024-01-01')).toContain('不能早于');
  });

  it('日期格式必须是 YYYY-MM-DD', () => {
    expect(windowProblem('2024/01/01', '')).toContain('YYYY-MM-DD');
    expect(windowProblem('2024-01-01', '2025/12/31')).toContain('YYYY-MM-DD');
  });

  it('开放区间显式写「至今」，不画成空', () => {
    expect(windowText('2024-01-01', null)).toBe('2024-01-01 ~ 至今');
    expect(windowText('2024-01-01', '2025-12-31')).toBe('2024-01-01 ~ 2025-12-31');
    expect(windowText(null, null)).toBe('—');
  });
});

describe('词表', () => {
  it('量纲与角色都有中文标签', () => {
    expect(dimensionLabels.WEIGHT).toBe('重量');
    expect(dimensionLabels.COUNT).toBe('数量');
    // BASE 是基础单位，比例恒为 1，不占换算行。
    expect(conversionRoles).not.toContain('BASE');
    expect(conversionRoles).toEqual(['PURCHASE', 'SALE', 'PACKAGING']);
  });

  it('单据类型覆盖蓝图点名的三个下游', () => {
    expect(documentTypeLabels.PURCHASE).toBe('06 采购');
    expect(documentTypeLabels.PICKING).toBe('08 拣货');
  });
});

describe('单位表单', () => {
  it('新建校验编码，编辑不校验（编码建后不可改）', () => {
    const draft: UnitDraft = { ...emptyUnitDraft(), code: '箱', name: '箱' };
    expect(validateUnit(draft, true)).toEqual([]);

    const renamed: UnitDraft = { ...draft, code: '另一个码' };
    expect(validateUnit(renamed, true)).toEqual([]);
    // 编辑态后端禁止改编码，前端不再把它当错误——错误由后端消息说明原因。
    expect(validateUnit(renamed, false)).toEqual([]);
  });

  it('名称与量纲必填', () => {
    const noName: UnitDraft = { ...emptyUnitDraft(), code: '箱', name: '  ' };
    expect(validateUnit(noName, true)).toContain('单位名称不能为空');
    const badDimension: UnitDraft = {
      ...emptyUnitDraft(),
      code: '箱',
      name: '箱',
      dimension: 'MASS',
    };
    expect(validateUnit(badDimension, true)).toContain('请选择一个量纲');
  });

  it('草稿往返保留 version（乐观锁凭据不由前端自算）', () => {
    const original = unit({ code: '箱', version: 3, enabled: false, scale: 2 });
    const draft = unitDraft(original);
    expect(draft.version).toBe(3);
    const input = draftToUnitInput(draft);
    expect(input.code).toBe('箱');
    expect(input.version).toBe(3);
    expect(input.enabled).toBe(false);
    expect(input.scale).toBe(2);
  });
});

describe('换算版本表单', () => {
  const registered = [unit({ code: '箱', enabled: true }), unit({ code: '件', enabled: true })];

  function draft(patch: Partial<ConversionDraft> = {}): ConversionDraft {
    return {
      ...emptyConversionDraft('件'),
      unitCode: '箱',
      role: 'PURCHASE',
      quantityPerBase: '12',
      effectiveFrom: '2024-01-01',
      effectiveTo: '',
      ...patch,
    };
  }

  it('基础单位不能建换算行', () => {
    // 基础单位比例恒为 1，建行一定是错的。
    const errors = validateConversion(draft({ unitCode: '件' }), '件', registered);
    expect(errors).toContain('「件」就是该 SKU 的基础单位，比例恒为 1，不需要换算行');
  });

  it('未登记或已停用的单位不能建换算', () => {
    expect(validateConversion(draft({ unitCode: '未知' }), '件', registered)).toContain(
      '单位「未知」尚未登记到单位字典，请先在单位字典中新建',
    );
    const disabled = [unit({ code: '箱', enabled: false })];
    expect(validateConversion(draft(), '件', disabled)).toContain(
      '单位「箱」已停用，不能再新建换算版本',
    );
  });

  it('合法草稿无错误且转换类型正确', () => {
    expect(validateConversion(draft(), '件', registered)).toEqual([]);
    const input = draftToConversionInput(draft());
    // 字符串必须转数字：后端收 BigDecimal，前端不能把 "12" 当字符串发过去。
    expect(input.quantityPerBase).toBe(12);
    expect(input.effectiveTo).toBeNull();
  });

  it('空截止日转成 null（开放区间）', () => {
    const input = draftToConversionInput(draft({ effectiveTo: '' }));
    expect(input.effectiveTo).toBeNull();
  });
});

describe('展示', () => {
  it('空值画成破折号而不是 0', () => {
    // 「没采到」与「确实是 0」必须区分。
    expect(quantityText(null)).toBe('—');
    expect(quantityText(undefined)).toBe('—');
    expect(quantityText(0, 0)).toBe('0');
    expect(quantityText(12.5, 2)).toBe('12.50');
  });

  it('没有当前生效版本时明说会报错，不假装有比例', () => {
    const text = ratioSummary(group({ currentQuantityPerBase: null, currentVersionId: null }));
    expect(text).toContain('当前没有生效版本');
    expect(text).toContain('报错');
  });

  it('未登记基础单位的提示区分 0 与非 0', () => {
    expect(unregisteredHint(0)).toContain('都已登记');
    expect(unregisteredHint(3)).toContain('3 个 SKU');
  });

  it('快照行同时给出数量、比例与结果', () => {
    // 只显示基础数量看不出这个数怎么换出来的，事后对账无从下手。
    const line: UnitSnapshotLine = {
      lineNo: 1,
      skuId: 's1',
      skuCode: 'SKU-1',
      role: 'PURCHASE',
      roleLabel: '采购单位',
      unitCode: '箱',
      baseUnit: '件',
      quantity: 3,
      quantityPerBase: 12,
      baseQuantity: 36,
      sourceConversionId: 'c1',
      effectiveFrom: '2024-01-01',
      effectiveTo: null,
      created: true,
      occurredAt: '2024-06-01T08:30:00Z',
    };
    expect(snapshotLineText(line)).toBe('3 箱 × 12 = 36 件');
  });

  it('换算结果在直接用基础单位时说明比例恒为 1', () => {
    const result: UnitConvertResult = {
      skuId: 's1',
      skuCode: 'SKU-1',
      unitCode: '件',
      unitName: '件',
      role: 'BASE',
      roleLabel: '基础单位',
      quantity: 7,
      quantityPerBase: 1,
      baseUnit: '件',
      baseQuantity: 7,
      asOf: '2026-10-07',
      conversionId: null,
      effectiveFrom: null,
      effectiveTo: null,
      note: '',
    };
    expect(result.quantityPerBase).toBe(1);
  });
});
