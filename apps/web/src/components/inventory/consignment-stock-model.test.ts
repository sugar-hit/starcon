import { describe, expect, it } from 'vitest';
import {
  auditLabel,
  canAddLine,
  canCancel,
  canClose,
  canCloseWithSettlement,
  canConfirmReceipt,
  canRecordReceipt,
  canRecordSettlement,
  canShip,
  differenceTone,
  errorText,
  formatAmount,
  formatOwnInventory,
  formatQty,
  formatUnsettled,
  measureCards,
  operatingModeLabel,
  receiptTypeLabel,
  segregationTone,
  settlementBasisHint,
  settlementBasisView,
  settlementStateTone,
  statusTone,
  supportsConsignmentStock,
} from './consignment-stock-model';
import type { PlatformConsignmentLedger } from '../../../../../packages/api-contracts/src/index';

/**
 * 7.7.2.2 前端模型的纯函数断言。
 *
 * <p>重点测三件最容易做错的事：
 *
 * <ol>
 *   <li>**未结量是 null 时不能显示 0**（「没给回执」≠「没有未结」）；</li>
 *   <li>**四个口径是四张卡，不是一个合计数**（合计会把平台在库算成自有库存）；</li>
 *   <li>**自有库存那一侧必须带「不含托管量」的口径**。</li>
 * </ol>
 */
describe('7.7.2.2 平台托管与全托管备货前端模型', () => {
  const ledger: PlatformConsignmentLedger = {
    orderId: 'o1',
    orderCode: 'CSP-20261101-000001',
    shopCode: 'SHOP-1',
    shopName: '全托管店铺',
    operatingMode: 'FULL_CONSIGNMENT',
    operatingModeLabel: '全托管',
    settlementBasis: 'PER_ITEM',
    settlementBasisLabel: '按件结算',
    totalStockedQuantity: 100,
    totalPlatformReceivedQuantity: 60,
    totalPlatformSoldQuantity: 20,
    totalPlatformOnHandQuantity: 40,
    totalInTransitQuantity: 40,
    totalSettledQuantity: 0,
    totalUnsettledQuantity: 20,
    totalOwnInventoryOnHandQuantity: 35,
    rows: [],
    measureNote: '',
    separationNote: '平台仓在库数是平台回执的观测值，不得并入自有库存余额。',
  };

  it('四个口径是四张分开的卡，不是一个合计', () => {
    const cards = measureCards(ledger);
    expect(cards).toHaveLength(4);
    expect(cards.map((c) => c.key)).toEqual(['stocked', 'onHand', 'sold', 'unsettled']);
    expect(cards[0]?.value).toBe('100');
    expect(cards[1]?.value).toBe('40');
    expect(cards[2]?.value).toBe('20');
    expect(cards[3]?.value).toBe('20');
    // 平台在库那一栏必须说清「不计入自有库存」。
    expect(cards[1]?.hint).toContain('不计入自有库存');
  });

  it('没有台账时四张卡也有骨架，不返回空数组', () => {
    const cards = measureCards(null);
    expect(cards).toHaveLength(4);
    expect(cards.every((c) => c.value === '—')).toBe(true);
  });

  it('未结量是 null 时显示「—（平台还没给回执）」而不是 0', () => {
    expect(formatUnsettled(null, false)).toBe('—（平台还没给回执）');
    expect(formatUnsettled(0, false)).toBe('—（平台还没给回执）');
    // 已知时 0 就是 0 —— 那才是「真的结清了」。
    expect(formatUnsettled(0, true)).toBe('0');
    expect(formatUnsettled(12.5, true)).toBe('12.5');
  });

  it('自有库存那一侧永远带「不含托管量」的口径', () => {
    expect(formatOwnInventory(35)).toContain('35');
    expect(formatOwnInventory(35)).toContain('不含托管量');
    expect(formatOwnInventory(null)).toContain('不含托管量');
  });

  it('数量与金额的显示口径跟后端对齐', () => {
    expect(formatQty(100)).toBe('100');
    expect(formatQty(null)).toBe('—');
    expect(formatAmount('12.5', 'CNY')).toBe('12.50 CNY');
    expect(formatAmount(null, 'CNY')).toBe('—');
  });

  it('结算口径的说法与后端一致，按件与按日不同', () => {
    expect(settlementBasisHint('PER_ITEM')).toContain('件数');
    expect(settlementBasisHint('PER_DAY')).toContain('日期区间');
    expect(settlementBasisHint(null)).toBe('结算口径未定');
  });

  it('差异口径与分账结论的色调：只有对平与在途是中性', () => {
    expect(differenceTone('MATCHED')).toBe('ok');
    expect(differenceTone('IN_TRANSIT')).toBe('neutral');
    expect(differenceTone('OVER_RECEIVED')).toBe('bad');
    expect(differenceTone('NEGATIVE_ON_HAND')).toBe('bad');
    expect(segregationTone('SEPARATED')).toBe('ok');
    expect(segregationTone('VIOLATION')).toBe('bad');
    expect(statusTone('CLOSED')).toBe('ok');
    expect(statusTone('CANCELLED')).toBe('bad');
    expect(settlementStateTone('PENDING')).toBe('warn');
    expect(settlementStateTone('SETTLED')).toBe('ok');
  });

  it('经营模式：只有全托管与半托管能建平台托管备货单', () => {
    expect(operatingModeLabel('FULL_CONSIGNMENT')).toBe('全托管');
    expect(operatingModeLabel('SEMI_CONSIGNMENT')).toBe('半托管');
    expect(operatingModeLabel('SELF_FULFILLED')).toBe('自发货');
    expect(operatingModeLabel(null)).toBe('未知模式');
    expect(supportsConsignmentStock('FULL_CONSIGNMENT')).toBe(true);
    expect(supportsConsignmentStock('SEMI_CONSIGNMENT')).toBe(true);
    expect(supportsConsignmentStock('SELF_FULFILLED')).toBe(false);
    expect(supportsConsignmentStock(null)).toBe(false);
  });

  it('动作可用性跟着单据状态走', () => {
    expect(canShip('DRAFT')).toBe(true);
    expect(canShip('SHIPPED')).toBe(false);
    expect(canAddLine('DRAFT')).toBe(true);
    expect(canAddLine('SHIPPED')).toBe(false);
    expect(canConfirmReceipt('SHIPPED')).toBe(true);
    expect(canConfirmReceipt('DRAFT')).toBe(false);
    expect(canClose('RECEIVED')).toBe(true);
    expect(canCancel('DRAFT')).toBe(true);
    expect(canCancel('RECEIVED')).toBe(true);
    expect(canCancel('CLOSED')).toBe(false);
    expect(canCancel('CANCELLED')).toBe(false);
    // 货没发出去时平台仓不可能有收发回执。
    expect(canRecordReceipt('DRAFT')).toBe(false);
    expect(canRecordReceipt('SHIPPED')).toBe(true);
    expect(canRecordSettlement('RECEIVED')).toBe(true);
    expect(canRecordSettlement('CLOSED')).toBe(false);
  });

  it('未结不为 0（或平台还没给回执）时不允许关单', () => {
    expect(canCloseWithSettlement('RECEIVED', 0, true)).toBe(true);
    expect(canCloseWithSettlement('RECEIVED', 5, true)).toBe(false);
    expect(canCloseWithSettlement('RECEIVED', null, false)).toBe(false);
    expect(canCloseWithSettlement('DRAFT', 0, true)).toBe(false);
  });

  it('结算口径视图把未结量的「未知」如实带出来', () => {
    const unknown = settlementBasisView({
      orderId: 'o1',
      orderCode: 'CSP-1',
      basis: 'PER_ITEM',
      basisLabel: '按件结算',
      basisRule: '按件：未结 = 已售件数 − 已结算件数',
      platformSoldQuantity: 0,
      settledQuantity: 0,
      unsettledQuantity: null,
      unsettledKnown: false,
      settlementState: 'NOT_STARTED',
      settlementStateLabel: '未发生',
      settledPeriods: [],
      note: '',
    });
    expect(unknown?.unsettled).toBe('—（平台还没给回执）');
    expect(unknown?.unsettledKnown).toBe(false);
    expect(unknown?.periods).toEqual([]);

    const known = settlementBasisView({
      orderId: 'o1',
      orderCode: 'CSP-1',
      basis: 'PER_DAY',
      basisLabel: '按日结算',
      basisRule: '按日：未结 = 未结算区间内的售出件数',
      platformSoldQuantity: 50,
      settledQuantity: 25,
      unsettledQuantity: 20,
      unsettledKnown: true,
      settlementState: 'PENDING',
      settlementStateLabel: '有未结',
      settledPeriods: [
        {
          periodStart: '2026-11-01',
          periodEnd: '2026-11-03',
          settledQuantity: 25,
          basis: 'PER_DAY',
        },
      ],
      note: '',
    });
    expect(known?.unsettled).toBe('20');
    expect(known?.periods[0]).toContain('2026-11-01');
  });

  it('错误文本原样带出后端的人话', () => {
    expect(errorText(new Error('平台出库累计不能超过入库累计'))).toBe(
      '平台出库累计不能超过入库累计',
    );
    expect(errorText('别的')).toBe('操作失败，请稍后重试');
  });

  it('审计动作与回执类型有中文说法', () => {
    expect(auditLabel('ORDER_CREATED')).toBe('建立备货单');
    expect(auditLabel('RECEIPT_RECORDED')).toBe('登记平台仓回执');
    expect(auditLabel('SETTLEMENT_RECEIPT_RECORDED')).toBe('登记平台结算回执');
    expect(auditLabel(null)).toBe('未知动作');
    expect(receiptTypeLabel('INBOUND')).toBe('平台入库');
    expect(receiptTypeLabel('OUTBOUND')).toBe('平台出库');
  });
});
