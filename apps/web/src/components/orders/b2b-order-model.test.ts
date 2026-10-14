import { describe, expect, it } from 'vitest';
import {
  b2bOrderQuery,
  b2bPaymentMethodLabels,
  b2bReceivableQuery,
  b2bStatusLabels,
  canConfirm,
  canRequestInvoice,
  canShip,
  createBlockReason,
  formatDueDate,
  lineBlockReason,
  nextTierHint,
} from './b2b-order-model';
import type {
  B2BOrderDetail,
  B2BPriceQuoteRow,
  B2BReceivableRow,
} from '../../../../../packages/api-contracts/src/index';

/**
 * 9.7.1.1 批发订单与账期前端模型。
 *
 * <p>重点覆盖三类最容易出错、且出错后**界面会骗人**的判定：
 * 起订量闸门、阶梯边界提示、以及「未发货不产生应收」相关的状态/动作可见性。
 */

function quote(over: Partial<B2BPriceQuoteRow> = {}): B2BPriceQuoteRow {
  return {
    skuId: 'sku-1',
    skuCode: 'SKU-1',
    customerLevel: 'STRATEGIC',
    moqQuantity: 10,
    quantity: 10,
    appliedTierMinQuantity: 10,
    unitPrice: 9.5,
    lineAmount: 95,
    lowestTier: false,
    nextTierMinQuantity: 100,
    nextTierUnitPrice: 8,
    ...over,
  } as B2BPriceQuoteRow;
}

describe('起订量是硬校验', () => {
  it('低于起订量时给出可展示的原因', () => {
    expect(lineBlockReason(quote({ quantity: 9 }))).toContain('低于起订量');
  });

  it('恰好等于起订量时放行', () => {
    expect(lineBlockReason(quote({ quantity: 10 }))).toBeNull();
  });

  it('起订量为 0 表示不限起订量，任何数量都放行', () => {
    expect(lineBlockReason(quote({ moqQuantity: 0, quantity: 1 }))).toBeNull();
  });

  it('没有试算结果时不做判定（而不是假装可以提交）', () => {
    expect(lineBlockReason(null)).toBeNull();
  });
});

describe('阶梯边界', () => {
  it('给出「再买多少进下一档」', () => {
    // 当前 10 件、下一档 100 件起 —— 差 90 件。
    expect(nextTierHint(quote({ quantity: 10 }))).toBe('再买 90 件进下一档（100 件起，单价 8）');
  });

  it('恰好等于下一档下界时不再提示「再买」', () => {
    expect(nextTierHint(quote({ quantity: 100, appliedTierMinQuantity: 100 }))).toBe(
      '再买 0 件进下一档（100 件起，单价 8）',
    );
  });

  it('已是最高档时不提示', () => {
    expect(nextTierHint(quote({ lowestTier: true, nextTierMinQuantity: undefined as unknown as number }))).toBeNull();
  });

  it('没有试算结果时不提示', () => {
    expect(nextTierHint(null)).toBeNull();
  });
});

describe('下单表单本地校验与后端同判据', () => {
  const base = {
    orderNo: 'B2B-1',
    customerPartyId: 'party-1',
    currency: 'USD',
    paymentMethod: 'CREDIT_TERM',
    paymentTermsDays: 30,
    lines: [{ quantity: '100' }],
  };

  it('合法输入放行', () => {
    expect(createBlockReason(base)).toBeNull();
  });

  it('账期结算缺账期天数被拦', () => {
    expect(createBlockReason({ ...base, paymentTermsDays: null })).toContain('账期天数');
  });

  it('即时结算带账期天数被拦', () => {
    expect(
      createBlockReason({ ...base, paymentMethod: 'ON_RECEIPT', paymentTermsDays: 30 }),
    ).toContain('不接受账期天数');
  });

  it('订单号、客户、币种、行数与行数量都逐项校验', () => {
    expect(createBlockReason({ ...base, orderNo: '  ' })).toContain('订单号');
    expect(createBlockReason({ ...base, customerPartyId: '' })).toContain('客户');
    expect(createBlockReason({ ...base, currency: 'usd' })).toContain('币种');
    expect(createBlockReason({ ...base, lines: [] })).toContain('至少需要');
    expect(createBlockReason({ ...base, lines: [{ quantity: '0' }] })).toContain('大于 0');
  });
});

describe('状态与动作可见性', () => {
  it('只有已确认 / 部分发货能发货，草稿不行', () => {
    expect(canShip('CONFIRMED')).toBe(true);
    expect(canShip('PARTIALLY_SHIPPED')).toBe(true);
    expect(canShip('DRAFT')).toBe(false);
    expect(canShip('SHIPPED')).toBe(false);
  });

  it('只有草稿能确认', () => {
    expect(canConfirm('DRAFT')).toBe(true);
    expect(canConfirm('CONFIRMED')).toBe(false);
    expect(canConfirm('SHIPPED')).toBe(false);
  });

  it('没有详情时不允许提开票', () => {
    expect(canRequestInvoice(null)).toBe(false);
  });

  it('没有未结且未提开票的应收时不允许提开票（未发货就是这种情况）', () => {
    const detail = { receivables: [] } as unknown as B2BOrderDetail;
    expect(canRequestInvoice(detail)).toBe(false);
  });

  it('有未结且未提开票的应收时允许提开票', () => {
    const detail = {
      receivables: [{ status: 'OPEN', invoiceState: 'NOT_REQUESTED' }],
    } as unknown as B2BOrderDetail;
    expect(canRequestInvoice(detail)).toBe(true);
  });

  it('已提出开票需求的应收不再重复给入口', () => {
    const detail = {
      receivables: [{ status: 'OPEN', invoiceState: 'REQUESTED' }],
    } as unknown as B2BOrderDetail;
    expect(canRequestInvoice(detail)).toBe(false);
  });

  it('已结清的应收不参与开票入口判定', () => {
    const detail = {
      receivables: [{ status: 'SETTLED', invoiceState: 'NOT_REQUESTED' }],
    } as unknown as B2BOrderDetail;
    expect(canRequestInvoice(detail)).toBe(false);
  });
});

describe('账期到期日展示', () => {
  it('直接截取后端返回的日期，不在前端做时区换算', () => {
    expect(formatDueDate('2026-11-13T00:00:00Z')).toBe('2026-11-13');
  });

  it('空值如实显示为 —', () => {
    expect(formatDueDate(null)).toBe('—');
  });
});

describe('查询串', () => {
  it('订单查询串逐项编码并跳过空值', () => {
    expect(
      b2bOrderQuery({ status: 'DRAFT', keyword: ' B2B 1 ', page: 0, size: 50 }),
    ).toBe('?status=DRAFT&keyword=B2B%201&page=0&size=50');
    expect(b2bOrderQuery({})).toBe('');
    expect(b2bOrderQuery({ keyword: '   ' })).toBe('');
  });

  it('应收查询串支持按开票需求与订单过滤', () => {
    expect(b2bReceivableQuery({ invoiceState: 'REQUESTED', orderId: 'o-1' })).toBe(
      '?invoiceState=REQUESTED&orderId=o-1',
    );
  });
});

describe('展示口径覆盖全部枚举值', () => {
  it('状态标签含单向状态机的四态', () => {
    expect(Object.keys(b2bStatusLabels)).toEqual([
      'DRAFT',
      'CONFIRMED',
      'PARTIALLY_SHIPPED',
      'SHIPPED',
    ]);
  });

  it('三种收款方式都写明是否产生应收', () => {
    expect(Object.keys(b2bPaymentMethodLabels)).toHaveLength(3);
    expect(b2bPaymentMethodLabels.ORDER_SETTLEMENT).toContain('不产生应收');
    expect(b2bPaymentMethodLabels.ON_RECEIPT).toContain('产生应收');
    expect(b2bPaymentMethodLabels.CREDIT_TERM).toContain('到期日');
  });
});

describe('应收行形状', () => {
  it('到期日是后端给的时间戳字符串，界面按字符串处理', () => {
    const row = {
      id: 'ar-1',
      receivableNo: 'AR-1',
      orderId: 'o-1',
      orderNo: 'B2B-1',
      shipmentId: 's-1',
      customerPartyId: 'party-1',
      customerName: '客户甲',
      currency: 'USD',
      paymentMethod: 'CREDIT_TERM',
      paymentTermsDays: 30,
      amount: '800.0000',
      confirmedAt: '2026-10-14T00:00:00Z',
      dueDate: '2026-11-13T00:00:00Z',
      status: 'OPEN',
      invoiceState: 'NOT_REQUESTED',
      invoiceRequestedAt: null,
      lines: [],
    } as unknown as B2BReceivableRow;
    expect(formatDueDate(row.dueDate)).toBe('2026-11-13');
  });
});
