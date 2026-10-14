import { describe, expect, it } from 'vitest';
import type {
  SkuDetail,
  SkuEffectiveAttribute,
  SkuUnitConversion,
} from '../../../../../packages/api-contracts/src/index';
import {
  decimal,
  detailDraft,
  dimensionText,
  draftToInput,
  effectiveText,
  groupAttributes,
  isEmptyProfile,
  unitConversionText,
  validateDraft,
  weightText,
} from './sku-detail-model';

function attribute(patch: Partial<SkuEffectiveAttribute> = {}): SkuEffectiveAttribute {
  return {
    attributeId: 'a1',
    code: 'MATERIAL',
    name: '材质',
    dataType: 'TEXT',
    valueMode: 'COMMON_DEFAULT',
    valueModeLabel: '公共默认 · 可覆写',
    multiple: false,
    required: false,
    variantAxis: false,
    unit: '',
    groupName: '基本参数',
    options: [],
    overrideState: 'INHERIT',
    source: 'SPU_COMMON',
    sourceLabel: 'SPU 公共值',
    rawValues: [],
    effectiveValues: ['棉'],
    inherited: true,
    missingRequired: false,
    ...patch,
  };
}

function detail(patch: Partial<SkuDetail> = {}): SkuDetail {
  return {
    id: 'sku-1',
    code: 'SKU-1',
    name: '测试 SKU',
    status: 'ACTIVE',
    version: 3,
    variantSignature: 'abc',
    createdAt: '2026-01-01T00:00:00Z',
    updatedAt: '2026-01-02T00:00:00Z',
    spuId: 'spu-1',
    spuCode: 'SPU-1',
    spuName: '测试商品',
    brand: '品牌',
    category: '3C 配件',
    spuStatus: 'ON_SALE',
    specs: [],
    attributeSummary: {
      total: 0,
      overridden: 0,
      cleared: 0,
      inherited: 0,
      unset: 0,
      variantAxes: 0,
      missingRequired: 0,
    },
    attributes: [],
    baseUnit: '件',
    unitConversions: [],
    barcode: {
      primary: '6900000001',
      identifierOwner: '3.1.2.3 编码与条码',
      identifierNote: 'seller_sku 属平台属性，不是内部编码',
    },
    packaging: null,
    mediaAvailable: true,
    media: [],
    mediaLimitation: '缺文件键',
    sources: [],
    statusHistory: [],
    ...patch,
  };
}

describe('3.1.2.2 SKU 详情前端模型', () => {
  it('有效值展示区分空值与破折号，布尔按是/否翻译', () => {
    expect(effectiveText(attribute({ effectiveValues: ['棉', '麻'] }))).toBe('棉 / 麻');
    expect(effectiveText(attribute({ effectiveValues: [] }))).toBe('—');
    expect(
      effectiveText(attribute({ dataType: 'BOOLEAN', effectiveValues: ['true', 'false'] })),
    ).toBe('是 / 否');
  });

  it('NUMERIC 去掉无意义的末尾零但不做四舍五入', () => {
    // 重量与体积重不能被悄悄改掉：只去零，不改有效数字。
    expect(decimal(250.0)).toBe('250');
    expect(decimal(0.5)).toBe('0.5');
    expect(decimal(123.456789)).toBe('123.456789');
    expect(decimal(null)).toBe('—');
    expect(decimal(undefined)).toBe('—');
  });

  it('包装尺寸缺一维时明确标注未采齐，不拿 0 凑一个完整尺寸', () => {
    const full = {
      version: 1,
      lengthMm: 200,
      widthMm: 150,
      heightMm: 100,
      netWeightG: 250,
      grossWeightG: 300,
      packagingType: '彩盒',
      volumeCm3: 3000,
      updatedAt: '2026-01-02T00:00:00Z',
    };
    expect(dimensionText(full)).toBe('200 × 150 × 100 mm');
    expect(dimensionText({ ...full, widthMm: null, heightMm: null })).toBe('200 mm（未采齐）');
    expect(dimensionText({ ...full, lengthMm: null, widthMm: null, heightMm: null })).toBe('—');
    expect(dimensionText(null)).toBe('—');
    expect(weightText(null)).toBe('—');
    expect(weightText({ ...full, netWeightG: null, grossWeightG: null })).toBe('—');
    expect(weightText({ ...full, netWeightG: null })).toBe('净重 — g · 毛重 300 g');
  });

  it('换算文本标出生效区间与历史版本', () => {
    const current: SkuUnitConversion = {
      unitCode: '箱',
      role: 'PURCHASE',
      roleLabel: '采购单位',
      quantityPerBase: 12,
      current: true,
      effectiveFrom: '2024-01-01',
      effectiveTo: null,
    };
    expect(unitConversionText(current, '件')).toBe('1 箱 = 12 件 · 2024-01-01 起');
    expect(unitConversionText({ ...current, current: false }, '件')).toContain('（历史版本）');
    expect(
      unitConversionText({ ...current, effectiveTo: '2023-12-31', current: false }, '件'),
    ).toContain('2024-01-01 ~ 2023-12-31');
  });

  it('属性按属性组分组，保持后端顺序', () => {
    const groups = groupAttributes([
      attribute({ attributeId: '1', groupName: '基本参数' }),
      attribute({ attributeId: '2', groupName: '技术参数' }),
      attribute({ attributeId: '3', groupName: '基本参数' }),
    ]);
    expect(groups.map((g) => g.name)).toEqual(['基本参数', '技术参数']);
    expect(groups[0]!.items.map((i) => i.attributeId)).toEqual(['1', '3']);
    // 空组名兜底到"基本参数"，不能出现一个空白分组标题。
    expect(groupAttributes([attribute({ groupName: '' })])[0]!.name).toBe('基本参数');
  });

  it('空白档案与有内容的档案能被区分开（空态判定）', () => {
    expect(isEmptyProfile(detail())).toBe(true);
    expect(isEmptyProfile(detail({ attributes: [attribute()] }))).toBe(false);
    expect(isEmptyProfile(detail({ statusHistory: [{ id: 'x' } as never] }))).toBe(false);
  });

  it('草稿从详情摊平，空包装字段是空串而不是 0', () => {
    const draft = detailDraft(
      detail({
        packaging: {
          version: 2,
          lengthMm: 200,
          widthMm: null,
          heightMm: null,
          netWeightG: 250,
          grossWeightG: null,
          packagingType: '',
          volumeCm3: null,
          updatedAt: '2026-01-02T00:00:00Z',
        },
      }),
    );
    expect(draft.name).toBe('测试 SKU');
    expect(draft.barcode).toBe('6900000001');
    expect(draft.version).toBe(3);
    expect(draft.lengthMm).toBe('200');
    expect(draft.widthMm).toBe('');
    expect(draft.packagingType).toBe('');
    // 没有包装时全部是空串，而不是 0 —— 空串才是"没维护"。
    expect(detailDraft(detail()).lengthMm).toBe('');
  });

  it('空串提交为 null，不会被当成 0 写进数据库', () => {
    const input = draftToInput(detailDraft(detail()));
    expect(input.lengthMm).toBeNull();
    expect(input.netWeightG).toBeNull();
    expect(input.packagingType).toBeNull();
    expect(input.version).toBe(3);
    expect(input.baseUnit).toBe('件');
  });

  it('草稿校验挡住空名称、负数、小数位超限与毛重小于净重', () => {
    const base = detailDraft(detail());
    expect(validateDraft(base)).toEqual([]);
    expect(validateDraft({ ...base, name: '  ' })).toContain('SKU 名称不能为空');
    expect(validateDraft({ ...base, baseUnit: '' })).toContain('基础单位不能为空');
    expect(validateDraft({ ...base, lengthMm: '-1' })).toContain('长只能是非负数字');
    expect(validateDraft({ ...base, widthMm: '1.1234567' })).toContain('宽最多 6 位小数');
    expect(validateDraft({ ...base, netWeightG: '500', grossWeightG: '300' })).toContain(
      '毛重不能小于净重',
    );
    // 等于是允许的：毛重 == 净重（零包装重量）是合法业务值。
    expect(validateDraft({ ...base, netWeightG: '300', grossWeightG: '300' })).toEqual([]);
    expect(validateDraft({ ...base, packagingType: 'x'.repeat(65) })).toContain(
      '包装方式最多 64 个字符',
    );
  });
});
