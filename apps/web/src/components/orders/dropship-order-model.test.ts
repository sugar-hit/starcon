import { describe, expect, it } from 'vitest';
import type {
  DropshipAlertRow,
  DropshipOrderRow,
} from '../../../../../packages/api-contracts/src/index';
import {
  actualCostText,
  canDispatch,
  canMarkFailed,
  canRecordAck,
  canRecordShipment,
  costDrift,
  dimensionNote,
  driftText,
  endpointFormError,
  hasSupplierAck,
  marginRateText,
  moneyText,
  negativeSpread,
  openCriticalAlertCount,
  orderStatusLabel,
  paymentConfirmed,
  paymentFormError,
  qtyText,
  spreadText,
  supplierAckFormError,
  vendorUnavailableHint,
} from './dropship-order-model';

/**
 * 9.6.1.2 代发单与供应商下单前端模型的展示层断言。
 *
 * <p>重点钉五件最容易做错的事：
 *
 * <ul>
 *   <li><b>差价转负必须能被一眼认出</b>：蓝图说「否则差价转负的订单会静默亏损」，
 *       而「静」首先就是「混在正常行里看不见」。模型必须独立判定负差价。</li>
 *   <li><b>「还没回执」与「回执是 0」不是一回事</b>：null 显示「待回执」，
 *       画成 0 会让人以为供应商回的是零成本。</li>
 *   <li><b>收款确认是人工署名</b>：模型只认 paymentConfirmedAt 的有无，
 *       不得因为「没有支付状态列」就在界面上假装系统读到了已付款。</li>
 *   <li><b>未接线的 vendor 要说人话</b>：画成一个可点的集成会让人反复重试
 *       一个根本不存在的通道。</li>
 *   <li><b>偏离额只做减法不重算差价</b>：差价有 DB 生成列，前端再算一份必然漂移。</li>
 * </ul>
 */

function order(overrides: Partial<DropshipOrderRow> = {}): DropshipOrderRow {
  return {
    id: '11111111-1111-1111-1111-111111111111',
    orderNo: 'DSO-20261014-000001',
    salesOrderId: '22222222-2222-2222-2222-222222222222',
    salesOrderNo: 'SO-1',
    customerName: '测试买家',
    currencyCode: 'USD',
    configId: '33333333-3333-3333-3333-333333333333',
    configCode: 'DSH-1',
    supplierCode: 'SUP-ACME',
    fulfillmentPath: 'SUPPLIER_DIRECT',
    ownershipMode: 'PURE_AGENCY',
    countsAgainstOwnInventory: false,
    status: 'READY',
    dispatchMode: 'MANUAL',
    vendorCode: 'MANUAL_ENTRY',
    supplierOrderNo: '',
    supplierTrackingNo: '',
    supplierShippedAt: null,
    rejectionReason: '',
    sellAmount: 50,
    purchaseAmount: 20,
    supplierShippingAmount: 0,
    actualPurchaseAmount: null,
    actualSupplierShippingAmount: null,
    dropshipCost: 20,
    spreadAmount: 30,
    marginRate: 0.6,
    paymentConfirmedAt: '2026-10-14T10:00:00Z',
    paymentConfirmedByMembershipId: '44444444-4444-4444-4444-444444444444',
    paymentConfirmedSource: 'MANUAL_ATTESTATION',
    paymentConfirmedNote: '银行流水号 BR-1',
    note: '',
    dispatchedAt: null,
    acceptedAt: null,
    createdAt: '2026-10-14T09:00:00Z',
    updatedAt: '2026-10-14T09:00:00Z',
    ...overrides,
  };
}

function alert(overrides: Partial<DropshipAlertRow> = {}): DropshipAlertRow {
  return {
    id: '55555555-5555-5555-5555-555555555555',
    orderId: '11111111-1111-1111-1111-111111111111',
    orderNo: 'DSO-20261014-000001',
    alertKind: 'PRICE_INCREASE',
    alertKindLabel: '供应商涨价',
    severity: 'WARNING',
    expectedAmount: 20,
    actualAmount: 26,
    detail: '实际采购价高于下单时的冻结价',
    status: 'OPEN',
    acknowledgedAt: null,
    acknowledgedByMembershipId: null,
    resolutionNote: '',
    occurredAt: '2026-10-14T10:05:00Z',
    ...overrides,
  };
}

describe('差价判定与展示', () => {
  it('差价为负时判定为亏损', () => {
    expect(negativeSpread(order({ spreadAmount: -5 }))).toBe(true);
    expect(negativeSpread(order({ spreadAmount: 0 }))).toBe(false);
    expect(negativeSpread(order({ spreadAmount: 30 }))).toBe(false);
  });

  it('差价转负的展示带负号前缀，便于扫视', () => {
    expect(spreadText(-5)).toBe('-5.00');
    expect(spreadText(30)).toBe('30.00');
    expect(spreadText(null)).toBe('—');
  });

  it('差价率只格式化后端生成的比例，不在前端重算', () => {
    expect(marginRateText(0.6)).toBe('60.00%');
    expect(marginRateText(null)).toBe('—');
  });

  it('金额与数量格式化不在前端二次取整成别的口径', () => {
    expect(moneyText(12.3456)).toBe('12.35');
    expect(qtyText(2)).toBe('2');
    expect(qtyText(1.5)).toBe('1.5');
    expect(qtyText(null)).toBe('—');
  });
});

describe('「还没回执」不是「回执是 0」', () => {
  it('null 显示待回执而不是 0', () => {
    expect(actualCostText(null)).toBe('待回执');
    expect(actualCostText(undefined)).toBe('待回执');
  });

  it('真实的 0 仍显示为 0（供应商回的是零，不是没回）', () => {
    expect(actualCostText(0)).toBe('0.00');
  });

  it('hasSupplierAck 只在真的回执后为真', () => {
    expect(hasSupplierAck(order())).toBe(false);
    expect(
      hasSupplierAck(order({ actualPurchaseAmount: 26, actualSupplierShippingAmount: 2 })),
    ).toBe(true);
    // 0 是有效回执
    expect(
      hasSupplierAck(order({ actualPurchaseAmount: 0, actualSupplierShippingAmount: 0 })),
    ).toBe(true);
  });
});

describe('偏离冻结价的金额', () => {
  it('没回执时没有偏离额', () => {
    expect(costDrift(order())).toBeNull();
    expect(driftText(null)).toBe('—');
  });

  it('涨价显示带加号的偏离额', () => {
    // 实际 (26 + 2) − 预计 (20 + 0) = +8
    expect(costDrift(order({ actualPurchaseAmount: 26, actualSupplierShippingAmount: 2 }))).toBe(8);
    expect(driftText(8)).toBe('+8.00');
  });

  it('比预计便宜显示负偏离额', () => {
    expect(costDrift(order({ actualPurchaseAmount: 15, actualSupplierShippingAmount: 0 }))).toBe(
      -5,
    );
    expect(driftText(-5)).toBe('-5.00');
    expect(driftText(0)).toBe('0.00');
  });
});

describe('收款确认是人工署名而不是系统支付状态', () => {
  it('有确认时刻才算已确认', () => {
    expect(paymentConfirmed(order())).toBe(true);
    expect(paymentConfirmed(order({ paymentConfirmedAt: null }))).toBe(false);
  });

  it('依据为空时表单自校验拦住', () => {
    expect(paymentFormError({ note: '' })).toContain('必须写明确认依据');
    expect(paymentFormError({ note: '   ' })).toContain('必须写明确认依据');
    expect(paymentFormError({ note: '银行流水号 BR-1' })).toBeNull();
  });
});

describe('状态机在前端的可操作性', () => {
  it('只有已确认收款才允许提交供应商', () => {
    expect(canDispatch(order({ status: 'READY' }))).toBe(true);
    expect(canDispatch(order({ status: 'DRAFT' }))).toBe(false);
    expect(canDispatch(order({ status: 'SHIPPED' }))).toBe(false);
  });

  it('只有「已提交待回执」才允许录回执', () => {
    expect(canRecordAck(order({ status: 'DISPATCHED' }))).toBe(true);
    expect(canRecordAck(order({ status: 'READY' }))).toBe(false);
  });

  it('只有「供应商已受理」才允许记发货', () => {
    expect(canRecordShipment(order({ status: 'ACCEPTED' }))).toBe(true);
    expect(canRecordShipment(order({ status: 'DISPATCHED' }))).toBe(false);
  });

  it('终态与已取消不可再置失败', () => {
    expect(canMarkFailed(order({ status: 'READY' }))).toBe(true);
    expect(canMarkFailed(order({ status: 'ACCEPTED' }))).toBe(true);
    expect(canMarkFailed(order({ status: 'SHIPPED' }))).toBe(false);
    expect(canMarkFailed(order({ status: 'CANCELLED' }))).toBe(false);
  });

  it('状态有中文标签，未知值原样返回', () => {
    expect(orderStatusLabel('DISPATCHED')).toBe('已提交供应商，待回执');
    expect(orderStatusLabel('WHATEVER')).toBe('WHATEVER');
  });
});

describe('告警工作台', () => {
  it('只数未处置的严重告警', () => {
    const rows = [
      alert({ severity: 'CRITICAL', status: 'OPEN' }),
      alert({
        id: '66666666-6666-6666-6666-666666666666',
        severity: 'CRITICAL',
        status: 'RESOLVED',
      }),
      alert({ id: '77777777-7777-7777-7777-777777777777', severity: 'WARNING', status: 'OPEN' }),
    ];
    expect(openCriticalAlertCount(rows)).toBe(1);
  });

  it('没有告警时数为 0（不是空数组冒充「已检查」）', () => {
    expect(openCriticalAlertCount([])).toBe(0);
  });
});

describe('未接线的 vendor 说人话', () => {
  const vendors = [
    {
      vendorCode: 'MANUAL_ENTRY',
      displayName: '人工录入（已接线）',
      wired: true,
      wiringRequirement: '',
    },
    {
      vendorCode: 'ALIBABA_1688',
      displayName: '1688 开放平台（未接线）',
      wired: false,
      wiringRequirement: '尚未登记 1688 开放平台网关地址与凭证引用',
    },
  ];

  it('已接线的 vendor 不给提示', () => {
    expect(vendorUnavailableHint(vendors, 'MANUAL_ENTRY')).toBeNull();
  });

  it('未接线的 vendor 给出「还差什么」', () => {
    expect(vendorUnavailableHint(vendors, 'ALIBABA_1688')).toContain('尚未登记');
  });

  it('未登记的 vendor 也返回 null（不凭空造一条提示）', () => {
    expect(vendorUnavailableHint(vendors, 'NOT_REGISTERED')).toBeNull();
  });
});

describe('能力维度不可用时把原因带出来', () => {
  it('可用的维度没有提示', () => {
    const dims = [{ code: 'ORDER', available: true, note: null, ownerModule: null }];
    expect(dimensionNote(dims, 'ORDER')).toBeNull();
  });

  it('不可用的维度返回说明', () => {
    const dims = [
      {
        code: 'PAYMENT',
        available: false,
        note: '9.1 没有支付状态列',
        ownerModule: '12 财务',
      },
    ];
    expect(dimensionNote(dims, 'PAYMENT')).toContain('没有支付状态列');
  });

  it('没有 note 时退回 ownerModule', () => {
    const dims = [{ code: 'X', available: false, note: null, ownerModule: '9.6.1.3' }];
    expect(dimensionNote(dims, 'X')).toBe('归属 9.6.1.3');
  });
});

describe('表单自校验', () => {
  it('供应商单号不能为空', () => {
    expect(
      supplierAckFormError({
        supplierOrderNo: '  ',
        actualPurchaseAmount: '13',
        actualSupplierShippingAmount: '0',
      }),
    ).toContain('供应商单号');
  });

  it('实际采购价与实际运费都必须给出（缺运费差价会算错）', () => {
    expect(
      supplierAckFormError({
        supplierOrderNo: 'SO-EXT-1',
        actualPurchaseAmount: '13',
        actualSupplierShippingAmount: '',
      }),
    ).toContain('实际运费');
    expect(
      supplierAckFormError({
        supplierOrderNo: 'SO-EXT-1',
        actualPurchaseAmount: 'abc',
        actualSupplierShippingAmount: '0',
      }),
    ).toContain('实际采购价');
  });

  it('金额为负时拦住', () => {
    expect(
      supplierAckFormError({
        supplierOrderNo: 'SO-EXT-1',
        actualPurchaseAmount: '-1',
        actualSupplierShippingAmount: '0',
      }),
    ).toContain('不能为负');
  });

  it('合法回执通过', () => {
    expect(
      supplierAckFormError({
        supplierOrderNo: 'SO-EXT-1',
        actualPurchaseAmount: '13',
        actualSupplierShippingAmount: '2.5',
      }),
    ).toBeNull();
  });

  it('声称已接线必须有 baseUrl', () => {
    expect(
      endpointFormError({ vendorName: '1688', baseUrl: '', credentialRef: 'ref', wired: true }),
    ).toContain('网关地址');
    expect(
      endpointFormError({ vendorName: '1688', baseUrl: '', credentialRef: 'ref', wired: false }),
    ).toBeNull();
  });

  it('不接受 key=value 形态的明文凭证', () => {
    expect(
      endpointFormError({
        vendorName: '1688',
        baseUrl: 'https://x',
        credentialRef: 'appKey=abc',
        wired: true,
      }),
    ).toContain('明文凭证');
  });
});
