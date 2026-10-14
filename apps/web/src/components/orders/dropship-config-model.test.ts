import { describe, expect, it } from 'vitest';
import type {
  DropshipConfigRow,
  DropshipEvaluationRow,
} from '../../../../../packages/api-contracts/src/index';
import {
  configFormError,
  configStatusLabel,
  dimensionUnavailableReason,
  exemptFromOwnInventory,
  fulfillmentPathLabel,
  isSupplierDirectButOurOwnership,
  marginShareText,
  marginText,
  moneyText,
  nextStatuses,
  ownershipConfirmationMissing,
  qtyText,
  sellReasonLabel,
  supplierStockText,
} from './dropship-config-model';

/**
 * 9.6.1.1 代发模式配置前端模型的展示层断言。
 *
 * <p>重点钉三件最容易做错的事：
 *
 * <ul>
 *   <li><b>物流 ≠ 货权，必须分两列</b>：「供应商直发 + 我方货权」（VMI 型）
 *       不经我方仓但货权在我方，模型必须能独立识别出来并标警告，
 *       不能只靠「两个下拉框选了什么」让用户自己看出来。</li>
 *   <li><b>不记我方库存只读后端生成列</b>：前端不得自己再算一遍
 *       「直发 && 纯代理」—— 两份算法迟早漂移，漂移后界面显示的结论就会
 *       和 9.6.1.2 实际预占与否对不上。</li>
 *   <li><b>「未观测」不是 0</b>：0 是「供应商确实没货」，null 是「没人报过」，
 *       两者画成同一个 0 会让人以为这单确定发不出。</li>
 * </ul>
 */

function config(overrides: Partial<DropshipConfigRow> = {}): DropshipConfigRow {
  return {
    id: '11111111-1111-1111-1111-111111111111',
    configCode: 'DSH-1',
    configName: '纯代理代发',
    scopeKind: 'SHOP',
    shopCode: 'SHOP-US-1',
    skuRef: '',
    fulfillmentPath: 'SUPPLIER_DIRECT',
    supplierCode: 'SUP-ACME',
    ownershipMode: 'PURE_AGENCY',
    countsAgainstOwnInventory: false,
    pureAgencyExempt: true,
    settlementTiming: 'ON_RECEIPT',
    settlementBasis: 'MARGIN_SHARE',
    marginShareRate: 0.5,
    currencyCode: 'USD',
    supplierCost: 100,
    referenceSellPrice: 150,
    grossSpread: 50,
    expectedMarginAmount: 25,
    expectedSupplierAmount: 125,
    availabilityCheckMode: 'SUPPLIER_STOCK',
    minSupplierStockBuffer: 5,
    listingPolicy: 'PAUSE_WHEN_UNSELLABLE',
    priority: 10,
    status: 'ACTIVE',
    effectiveFrom: '2026-10-01T00:00:00Z',
    effectiveTo: null,
    ownershipConfirmedAt: null,
    ownershipConfirmedNote: null,
    ownershipReviewedWith: '',
    note: '',
    version: 1,
    createdAt: '2026-10-01T00:00:00Z',
    updatedAt: '2026-10-01T00:00:00Z',
    ...overrides,
  };
}

function evaluation(overrides: Partial<DropshipEvaluationRow> = {}): DropshipEvaluationRow {
  return {
    id: '22222222-2222-2222-2222-222222222222',
    configId: '11111111-1111-1111-1111-111111111111',
    configLineId: null,
    skuRef: 'SKU-1',
    shopCode: 'SHOP-US-1',
    supplierCode: 'SUP-ACME',
    requestedQty: 10,
    requiredQty: 15,
    supplierAvailableQty: 500,
    sellable: true,
    listable: true,
    reasonCode: 'SELLABLE',
    reasonDetail: '',
    settlementBasis: 'MARGIN_SHARE',
    expectedMarginAmount: 25,
    countsAgainstOwnInventory: false,
    evaluatedAt: '2026-10-01T00:00:00Z',
    ...overrides,
  };
}

describe('物流路径与货权分开判断', () => {
  it('只有纯代理直发才不记我方库存', () => {
    expect(exemptFromOwnInventory(config())).toBe(true);
    expect(
      exemptFromOwnInventory(config({ ownershipMode: 'OUR_ACCOUNT', pureAgencyExempt: false })),
    ).toBe(false);
    expect(
      exemptFromOwnInventory(
        config({ fulfillmentPath: 'SELF_FULFILLED', pureAgencyExempt: false }),
      ),
    ).toBe(false);
  });

  it('「供应商直发 + 我方货权」被识别为需要显式警告的那一格', () => {
    expect(isSupplierDirectButOurOwnership(config({ ownershipMode: 'OUR_ACCOUNT' }))).toBe(true);
    expect(isSupplierDirectButOurOwnership(config())).toBe(false);
    expect(
      isSupplierDirectButOurOwnership(
        config({ fulfillmentPath: 'SELF_FULFILLED', ownershipMode: 'OUR_ACCOUNT' }),
      ),
    ).toBe(false);
  });

  it('前端只读后端生成列，不自己重算 —— 故意让两列矛盾时以生成列为准', () => {
    // 后端说 exempt=true（供应商直发 + 纯代理），模型必须照它显示，
    // 即便有人手工构造出一个 ownershipMode=OUR_ACCOUNT 的行也不能改结论。
    const row = config({ ownershipMode: 'OUR_ACCOUNT', pureAgencyExempt: true });
    expect(exemptFromOwnInventory(row)).toBe(true);
  });

  it('非纯代理缺确认时报「货权待确认」', () => {
    expect(ownershipConfirmationMissing(config({ ownershipMode: 'OUR_ACCOUNT' }))).toBe(true);
    expect(
      ownershipConfirmationMissing(
        config({ ownershipMode: 'OUR_ACCOUNT', ownershipConfirmedAt: '2026-10-01T00:00:00Z' }),
      ),
    ).toBe(false);
    expect(ownershipConfirmationMissing(config())).toBe(false);
  });

  it('词表标签可读，且未知值原样透出而不是画成空白', () => {
    expect(fulfillmentPathLabel('SUPPLIER_DIRECT')).toBe('供应商直发');
    expect(configStatusLabel('ACTIVE')).toBe('生效中');
    expect(sellReasonLabel('NEGATIVE_MARGIN')).toBe('差价为负');
    expect(fulfillmentPathLabel('SOMETHING_NEW')).toBe('SOMETHING_NEW');
  });
});

describe('「未观测」不等于 0', () => {
  it('null 显示为「未观测」，0 显示为 0', () => {
    expect(supplierStockText(evaluation({ supplierAvailableQty: null }))).toBe('未观测');
    expect(supplierStockText(evaluation({ supplierAvailableQty: 0 }))).toBe('0');
    expect(supplierStockText(evaluation({ supplierAvailableQty: 500 }))).toBe('500');
  });

  it('数量与金额缺值时显示破折号而不是 0', () => {
    expect(qtyText(null)).toBe('—');
    expect(moneyText(null)).toBe('—');
    expect(qtyText(10)).toBe('10');
    expect(qtyText(10.5)).toBe('10.5');
    expect(moneyText(12.3)).toBe('12.30');
  });
});

describe('金额不做前端二次取整', () => {
  it('毛利为负时带负号前缀，便于扫视', () => {
    expect(marginText(25)).toBe('25.00');
    expect(marginText(-8.5)).toBe('-8.50');
    expect(marginText(null)).toBe('—');
  });

  it('抽成比例只格式化，不重算', () => {
    expect(marginShareText(0.5)).toBe('50.0000%');
    expect(marginShareText(0.333333)).toBe('33.3333%');
    expect(marginShareText(null)).toBe('—');
  });
});

describe('状态流转', () => {
  it('已关闭是终态，没有任何下一步', () => {
    expect(nextStatuses('DRAFT')).toEqual(['ACTIVE']);
    expect(nextStatuses('ACTIVE')).toEqual(['SUSPENDED', 'CLOSED']);
    expect(nextStatuses('SUSPENDED')).toEqual(['ACTIVE', 'CLOSED']);
    expect(nextStatuses('CLOSED')).toEqual([]);
  });
});

describe('录入表单自校验（省得白跑一趟后端）', () => {
  const base = {
    fulfillmentPath: 'SUPPLIER_DIRECT',
    supplierCode: 'SUP-ACME',
    ownershipMode: 'PURE_AGENCY',
    ownershipConfirmedAt: '',
    ownershipConfirmedNote: '',
    ownershipReviewedWith: '',
    settlementBasis: 'FLAT_COST',
    marginShareRate: '',
    availabilityCheckMode: 'SKIP',
    minSupplierStockBuffer: '',
  };

  it('供应商直发必须填供应商', () => {
    expect(configFormError({ ...base, supplierCode: '' })).toContain('代发供应商');
  });

  it('自营发货不得挂供应商、不得声明纯代理', () => {
    expect(
      configFormError({ ...base, fulfillmentPath: 'SELF_FULFILLED', supplierCode: 'SUP-ACME' }),
    ).toContain('自营发货不该挂代发供应商');
    expect(
      configFormError({ ...base, fulfillmentPath: 'SELF_FULFILLED', supplierCode: '' }),
    ).toContain('不可能是「纯代理」');
  });

  it('我方货权必须确认时间、依据与联合评审对象', () => {
    expect(configFormError({ ...base, ownershipMode: 'OUR_ACCOUNT' })).toContain('确认时间');
    expect(
      configFormError({
        ...base,
        ownershipMode: 'OUR_ACCOUNT',
        ownershipConfirmedAt: '2026-10-01',
      }),
    ).toContain('确认依据');
    expect(
      configFormError({
        ...base,
        ownershipMode: 'OUR_ACCOUNT',
        ownershipConfirmedAt: '2026-10-01',
        ownershipConfirmedNote: '评审过',
      }),
    ).toContain('联合评审对象');
  });

  it('纯代理不得携带确认字段', () => {
    expect(configFormError({ ...base, ownershipConfirmedAt: '2026-10-01' })).toContain(
      '不需要「单独确认」',
    );
  });

  it('抽成比例与安全余量必须与结算方式 / 校验方式配套', () => {
    expect(configFormError({ ...base, settlementBasis: 'MARGIN_SHARE' })).toContain('抽成');
    expect(
      configFormError({ ...base, settlementBasis: 'FLAT_COST', marginShareRate: '0.2' }),
    ).toContain('不该带抽成比例');
    expect(configFormError({ ...base, availabilityCheckMode: 'SUPPLIER_STOCK' })).toContain(
      '安全余量',
    );
    expect(
      configFormError({ ...base, availabilityCheckMode: 'SKIP', minSupplierStockBuffer: '5' }),
    ).toContain('必须留空');
  });

  it('一组自洽的表单没有报错', () => {
    expect(configFormError(base)).toBeNull();
  });
});

describe('能力维度', () => {
  it('不可用时把原因一并返回，而不是画成 0 或「—」', () => {
    const dims = [
      { code: 'OWN_STOCK', available: false, unavailableReason: '蓝图明写代发不查自有库存' },
      { code: 'SHOP', available: true, unavailableReason: null },
    ];
    expect(dimensionUnavailableReason(dims, 'OWN_STOCK')).toBe('蓝图明写代发不查自有库存');
    expect(dimensionUnavailableReason(dims, 'SHOP')).toBeNull();
    expect(dimensionUnavailableReason(dims, 'MISSING')).toBeNull();
  });
});
