import { describe, expect, it } from 'vitest';
import type { SupplierQuote, SupplierQuotePriceTier } from '../../../../../packages/api-contracts/src/catalog';
import {
  applicableTierIndex,
  emptyQuoteForm,
  quoteFormProblem,
  quoteHeadline,
  selectionStateLabel,
  tierLabel,
  toQuoteForm,
  toQuoteInput,
} from './supplier-quote-model';

/**
 * 3.6.2.1 供应商报价前端模型的离线单测。
 *
 * <p>刻意钉死三组断言，它们是「后端与前端各判一次」最容易漂的地方：
 * 阶梯取价边界、档位升序提交、以及**歧义状态下不许前端自己挑一条**。
 */

function tier(min: string, price: string): SupplierQuotePriceTier {
  return {
    minQuantity: Number(min),
    unitPrice: Number(price),
    tierRangeLabel: null,
    note: null,
  };
}

function quote(overrides: Partial<SupplierQuote> = {}): SupplierQuote {
  return {
    id: 'q-1',
    supplierRoleId: 'r-1',
    supplierId: 'p-1',
    supplierCode: 'SUP-1',
    supplierName: '星尘科技',
    supplierRoleStatus: 'ACTIVE',
    skuId: 's-1',
    skuCode: 'SKU-000123',
    skuName: '六角扳手',
    supplierSkuCode: 'PN-001',
    purchaseUnitCode: 'PCS',
    purchaseUnitName: '件',
    currency: 'USD',
    moqQuantity: 100,
    moqLabel: '起订 100 件',
    leadTimeDays: 30,
    leadTimeLabel: '交期 30 天',
    validFrom: '2026-01-01',
    validTo: null,
    validityLabel: '2026-01-01 起长期有效',
    daysToExpiry: 9223372036854775807,
    priority: 1,
    priorityLabel: '首选',
    status: 'ACTIVE',
    statusLabel: '启用中',
    selectionState: 'SELECTED',
    selectionStateLabel: '默认采用',
    effective: true,
    tiers: [tier('1', '3.8'), tier('100', '3.2'), tier('500', '2.9')],
    note: null,
    version: 0,
    warnings: [],
    createdAt: null,
    updatedAt: null,
    ...overrides,
  };
}

describe('quoteFormProblem（规则 R3 / R4 / R5）', () => {
  const base = () => ({
    ...emptyQuoteForm('supplier-1', 'sku-1'),
    supplierSkuCode: 'PN-001',
    moqQuantity: '100',
    tiers: [{ minQuantity: '1', unitPrice: '3.8', note: '' }],
  });

  it('必填项齐全时通过', () => {
    expect(quoteFormProblem(base())).toBeNull();
  });

  it('供应商货号必填', () => {
    expect(quoteFormProblem({ ...base(), supplierSkuCode: '  ' })).toContain('供应商货号必填');
  });

  it('币种只校验 ISO 4217 三位格式，不查词表', () => {
    expect(quoteFormProblem({ ...base(), currency: 'US' })).toContain('三位');
    expect(quoteFormProblem({ ...base(), currency: 'cny' })).toBeNull();
    // 少见的币种也要能填进来：判"在不在词表里"会把它们挡在门外
    expect(quoteFormProblem({ ...base(), currency: 'ZWL' })).toBeNull();
  });

  it('起订量必填且大于零', () => {
    expect(quoteFormProblem({ ...base(), moqQuantity: '' })).toContain('起订量必填');
    expect(quoteFormProblem({ ...base(), moqQuantity: '0' })).toContain('必须大于 0');
    expect(quoteFormProblem({ ...base(), moqQuantity: '-1' })).toContain('必须大于 0');
  });

  it('交期 0 是当日发货而不是漏填', () => {
    expect(quoteFormProblem({ ...base(), leadTimeDays: '0' })).toBeNull();
    expect(quoteFormProblem({ ...base(), leadTimeDays: '' })).toBeNull();
    expect(quoteFormProblem({ ...base(), leadTimeDays: '366' })).toContain('365');
    expect(quoteFormProblem({ ...base(), leadTimeDays: '-1' })).toContain('负数');
  });

  it('有效期结束日不能早于生效日', () => {
    const form = { ...base(), validFrom: '2026-03-01', validTo: '2026-02-01' };
    expect(quoteFormProblem(form)).toContain('不能早于');
    // 结束日留空 = 长期有效（常年协议价）
    expect(quoteFormProblem({ ...base(), validTo: '' })).toBeNull();
  });

  it('优先级从 1 开始', () => {
    expect(quoteFormProblem({ ...base(), priority: '0' })).toContain('从 1 开始');
    expect(quoteFormProblem({ ...base(), priority: '100' })).toContain('最多 99');
    expect(quoteFormProblem({ ...base(), priority: '2' })).toBeNull();
  });

  it('阶梯至少一档', () => {
    expect(quoteFormProblem({ ...base(), tiers: [] })).toContain('至少一档');
  });

  it('阶梯起订量不允许重复', () => {
    const form = {
      ...base(),
      tiers: [
        { minQuantity: '100', unitPrice: '3.2', note: '' },
        { minQuantity: '100', unitPrice: '3.1', note: '' },
      ],
    };
    expect(quoteFormProblem(form)).toContain('必须大于');
  });

  it('阶梯单价为 0 非法', () => {
    const form = { ...base(), tiers: [{ minQuantity: '1', unitPrice: '0', note: '' }] };
    expect(quoteFormProblem(form)).toContain('必须大于 0');
  });

  it('必须选择供应商与 SKU', () => {
    expect(quoteFormProblem({ ...base(), supplierId: '' })).toContain('供应商');
    expect(quoteFormProblem({ ...base(), skuId: '' })).toContain('SKU');
  });
});

describe('toQuoteInput', () => {
  it('档位按起订量升序提交', () => {
    // 运营按金额从小到大录档是很自然的，提交时按起订量排好
    const form = {
      ...emptyQuoteForm('sup-1', 'sku-1'),
      supplierSkuCode: 'PN-001',
      tiers: [
        { minQuantity: '500', unitPrice: '2.9', note: '' },
        { minQuantity: '1', unitPrice: '3.8', note: '' },
        { minQuantity: '100', unitPrice: '3.2', note: '' },
      ],
    };
    const input = toQuoteInput(form);
    expect(input.tiers.map((t) => t.minQuantity)).toEqual([1, 100, 500]);
  });

  it('空值转成 null 而不是 0 或空串', () => {
    const input = toQuoteInput({
      ...emptyQuoteForm('sup-1', 'sku-1'),
      validTo: '',
      note: '  ',
    });
    expect(input.validTo).toBeNull();
    expect(input.currency).toBe('USD');
  });
});

describe('toQuoteForm / 空表单', () => {
  it('空表单的三个默认值都有确切业务含义', () => {
    const form = emptyQuoteForm();
    expect(form.moqQuantity).toBe('1');
    expect(form.leadTimeDays).toBe('0');
    expect(form.priority).toBe('1');
    expect(form.tiers).toHaveLength(1);
  });

  it('报价详情能往返成表单（乐观锁 version 与 id 都要带回来）', () => {
    const form = toQuoteForm(quote({ version: 3, id: 'q-42' }));
    expect(form.id).toBe('q-42');
    expect(form.version).toBe(3);
    expect(form.supplierSkuCode).toBe('PN-001');
    expect(form.currency).toBe('USD');
    expect(form.tiers.map((t) => t.minQuantity)).toEqual(['1', '100', '500']);
    expect(form.validTo).toBe('');
  });

  it('新建表单的 id 为空串（保存时据此走 POST 而不是 PUT）', () => {
    expect(emptyQuoteForm().id).toBe('');
  });
});

describe('阶梯取价', () => {
  const tiers = [tier('1', '3.8'), tier('100', '3.2'), tier('500', '2.9')];

  it('按数量定位适用档', () => {
    expect(tiers[applicableTierIndex(tiers, 1)]!.unitPrice).toBe(3.8);
    expect(tiers[applicableTierIndex(tiers, 99)]!.unitPrice).toBe(3.8);
    expect(tiers[applicableTierIndex(tiers, 100)]!.unitPrice).toBe(3.2);
    expect(tiers[applicableTierIndex(tiers, 137)]!.unitPrice).toBe(3.2);
    expect(tiers[applicableTierIndex(tiers, 100000)]!.unitPrice).toBe(2.9);
  });

  it('低于最低档时没有适用档（不退回高档价）', () => {
    expect(applicableTierIndex(tiers, 0)).toBe(-1);
  });

  it('阶梯是开口的，最后一档标注为 ≥ 而不是有限上界', () => {
    // 写成「100 ~ 500」会让运营以为买 600 落在别的档
    expect(tierLabel(tiers, 2)).toBe('≥ 500 → 2.9');
    expect(tierLabel(tiers, 0)).toContain('~');
  });
});

describe('选择状态与展示', () => {
  it('三态各有各的文案', () => {
    expect(selectionStateLabel('SELECTED')).toBe('默认采用');
    expect(selectionStateLabel('AMBIGUOUS')).toBe('需人工选择');
    expect(selectionStateLabel('LOWER_PRIORITY')).toBe('备选');
    expect(selectionStateLabel(null)).toBe('不参与有效报价');
  });

  it('headline 带上供应商、价格、单位与有效期', () => {
    expect(quoteHeadline(quote())).toContain('星尘科技');
    expect(quoteHeadline(quote())).toContain('最低 2.9 USD');
    expect(quoteHeadline(quote())).toContain('件');
    expect(quoteHeadline(quote())).toContain('长期有效');
  });

  it('没有阶梯价时 headline 不编造价格', () => {
    expect(quoteHeadline(quote({ tiers: [] }))).toContain('未设阶梯价');
  });
});
