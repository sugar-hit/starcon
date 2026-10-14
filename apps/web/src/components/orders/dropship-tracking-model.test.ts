import { describe, expect, it } from 'vitest';
import type {
  DropshipTaskRow,
  DropshipTrackingExceptionRow,
  DropshipTrackingPolicyRow,
  DropshipTrackingRow,
} from '../../../../../packages/api-contracts/src/index';
import {
  callbackText,
  canDismiss,
  canDispose,
  disposeFormError,
  exceptionKindLabel,
  ownLegLabel,
  legLines,
  moneyText,
  ownLegIdle,
  overdueNotShipped,
  promisedText,
  refundAmountText,
  supplierLegAbandoned,
  supplierLegLabel,
  taskStatusLabel,
} from './dropship-tracking-model';

function row(overrides: Partial<DropshipTrackingRow> = {}): DropshipTrackingRow {
  return {
    id: '11111111-1111-1111-1111-111111111111',
    trackingNo: 'DTR-20261014-000001',
    orderId: '22222222-2222-2222-2222-222222222222',
    orderNo: 'DSO-20261014-000001',
    salesOrderId: '33333333-3333-3333-3333-333333333333',
    salesOrderNo: 'SO-1',
    supplierCode: 'SUP-ACME',
    activeLeg: 'SUPPLIER',
    supplierLegStatus: 'NOT_SHIPMENT',
    supplierCarrierCode: '',
    supplierTrackingNo: '',
    supplierShippedAt: null,
    supplierLastEventAt: null,
    supplierDeliveredAt: null,
    supplierPromisedShipAt: new Date(Date.now() + 2 * 86400000).toISOString(),
    supplierExceptionNote: '',
    ownLegStatus: 'NOT_HANDED_OVER',
    ownCarrierCode: '',
    ownTrackingNo: '',
    ownHandedOverAt: null,
    ownLastEventAt: null,
    ownDeliveredAt: null,
    ownDeliveryNote: '',
    switchedToOwnAt: null,
    switchedToOwnByMembershipId: null,
    switchReason: '',
    urgeCount: 0,
    lastUrgedAt: null,
    callbackStatus: 'NOT_REQUIRED',
    callbackSentAt: null,
    callbackAttempts: 0,
    callbackLastError: '',
    note: '',
    version: 1,
    createdAt: new Date().toISOString(),
    updatedAt: new Date().toISOString(),
    ...overrides,
  };
}

function exception(
  overrides: Partial<DropshipTrackingExceptionRow> = {},
): DropshipTrackingExceptionRow {
  return {
    id: '44444444-4444-4444-4444-444444444444',
    exceptionNo: 'DEX-20261014-000001',
    trackingId: '11111111-1111-1111-1111-111111111111',
    orderId: '22222222-2222-2222-2222-222222222222',
    orderNo: 'DSO-20261014-000001',
    salesOrderNo: 'SO-1',
    supplierCode: 'SUP-ACME',
    exceptionKind: 'DELAYED_SHIPMENT',
    exceptionKindLabel: '延迟发货',
    severity: 'WARNING',
    ruleCode: 'PAST_PROMISED_SHIP_NO_SHIPMENT',
    ruleSummary: '超时未发货',
    ruleThresholdHours: 0,
    ruleEvidence: '逾期 30 小时仍为「未发货」',
    status: 'OPEN',
    acknowledgedAt: null,
    acknowledgedByMembershipId: null,
    resolutionNote: '',
    resolvedAt: null,
    resolvedByMembershipId: null,
    detectedAt: new Date().toISOString(),
    ...overrides,
  };
}

describe('两段物流必须分开呈现（蓝图核心）', () => {
  it('给出两行而不是一行拼接', () => {
    const lines = legLines(
      row({
        supplierLegStatus: 'SHIPPED',
        supplierCarrierCode: 'SUP-CARRIER',
        supplierTrackingNo: 'SF-1',
        ownLegStatus: 'HANDED_OVER',
        ownCarrierCode: 'OWN-CARRIER',
        ownTrackingNo: 'OW-1',
      }),
    );
    expect(lines).toHaveLength(2);
    expect(lines[0]?.leg).toBe('supplier');
    expect(lines[1]?.leg).toBe('own');
  });

  it('两组运单号与承运商各自独立，互不覆盖', () => {
    const [supplier, own] = legLines(
      row({
        supplierLegStatus: 'SHIPPED',
        supplierCarrierCode: 'SUP-CARRIER',
        supplierTrackingNo: 'SF-1',
        ownLegStatus: 'HANDED_OVER',
        ownCarrierCode: 'OWN-CARRIER',
        ownTrackingNo: 'OW-1',
      }),
    );
    expect(supplier?.trackingNo).toBe('SF-1');
    expect(own?.trackingNo).toBe('OW-1');
    expect(supplier?.carrierCode).toBe('SUP-CARRIER');
    expect(own?.carrierCode).toBe('OWN-CARRIER');
  });

  it('切段后供应商段是「保留的凭据」，不是当前承运', () => {
    const switched = row({
      activeLeg: 'OWN',
      supplierLegStatus: 'ABANDONED',
      supplierCarrierCode: 'SUP-CARRIER',
      supplierTrackingNo: 'SF-1',
      ownLegStatus: 'HANDED_OVER',
      ownCarrierCode: 'OWN-CARRIER',
      ownTrackingNo: 'OW-1',
      switchedToOwnAt: new Date().toISOString(),
      switchReason: '供应商连续延迟',
    });
    const [supplier, own] = legLines(switched);
    expect(supplier?.active).toBe(false);
    expect(own?.active).toBe(true);
    // 关键：废弃的供应商段**仍然带着运单号** —— 那是它确实发过这一票的证据
    expect(supplier?.trackingNo).toBe('SF-1');
    expect(supplier?.statusLabel).toContain('已废弃');
    expect(own?.trackingNo).toBe('OW-1');
    expect(supplierLegAbandoned(switched)).toBe(true);
  });

  it('纯代发时我方段「未启用」不是异常', () => {
    expect(ownLegIdle(row())).toBe(true);
    expect(ownLegIdle(row({ activeLeg: 'OWN' }))).toBe(false);
  });
});

describe('异常派生判断', () => {
  it('承诺时限未过不算逾期', () => {
    expect(overdueNotShipped(row())).toBe(false);
  });

  it('承诺时限已过且仍未发货才算逾期', () => {
    expect(
      overdueNotShipped(
        row({ supplierPromisedShipAt: new Date(Date.now() - 86400000).toISOString() }),
      ),
    ).toBe(true);
  });

  it('已发货就不再算「逾期未发货」', () => {
    expect(
      overdueNotShipped(
        row({
          supplierLegStatus: 'SHIPPED',
          supplierPromisedShipAt: new Date(Date.now() - 86400000).toISOString(),
        }),
      ),
    ).toBe(false);
  });

  it('只有未结案异常才能处置与判误报', () => {
    expect(canDispose(exception())).toBe(true);
    expect(canDismiss(exception())).toBe(true);
    expect(canDispose(exception({ status: 'DISPOSED' }))).toBe(false);
    expect(canDismiss(exception({ status: 'DISMISSED' }))).toBe(false);
    expect(canDispose(exception({ status: 'ACKNOWLEDGED' }))).toBe(true);
  });

  it('枚举标签齐全，未知值原样返回', () => {
    expect(supplierLegLabel('IN_TRANSIT')).toBe('在途');
    expect(ownLegLabel('HANDED_OVER')).toBe('我方已交运');
    expect(exceptionKindLabel('PARCEL_LOST')).toBe('丢件（轨迹停滞）');
    expect(taskStatusLabel('ACKNOWLEDGED')).toBe('下游已受理');
    expect(supplierLegLabel('SOMETHING_NEW')).toBe('SOMETHING_NEW');
  });
});

describe('回传状态不得画成已完成', () => {
  it('未登记回传时显示无需回传', () => {
    expect(callbackText(row())).toBe('无需回传');
  });

  it('未交付下游时显示「待回传平台」而不是已回传', () => {
    expect(callbackText(row({ callbackStatus: 'PENDING' }))).toContain('待回传平台');
    expect(callbackText(row({ callbackStatus: 'QUEUED' }))).toContain('待回传平台');
  });

  it('只有下游受理后才显示已受理', () => {
    expect(callbackText(row({ callbackStatus: 'ACKNOWLEDGED' }))).toBe('平台已受理');
  });

  it('失败必须带原因', () => {
    expect(
      callbackText(row({ callbackStatus: 'FAILED', callbackLastError: '平台返回 403' })),
    ).toContain('403');
  });
});

describe('处置表单校验', () => {
  it('处置必须写依据', () => {
    expect(disposeFormError('URGE_SUPPLIER', '', '', '  ')).toContain('依据');
  });

  it('催单与切换自营不需要金额', () => {
    expect(disposeFormError('URGE_SUPPLIER', '', '', '催一下')).toBe('');
    expect(disposeFormError('SWITCH_TO_SELF', '', '', '改我方仓发')).toBe('');
  });

  it('退款必须给金额与币种，缺一不可', () => {
    expect(disposeFormError('REQUEST_REFUND', '', 'USD', '退款')).toContain('退款金额');
    expect(disposeFormError('REQUEST_REFUND', '10', '', '退款')).toContain('币种');
    expect(disposeFormError('REQUEST_REFUND', '0', 'USD', '退款')).toContain('大于 0');
    expect(disposeFormError('REQUEST_REFUND', '10', 'US', '退款')).toContain('三位字母');
    expect(disposeFormError('REQUEST_REFUND', '31.5678', 'USD', '退款')).toBe('');
  });

  it('退款金额按四位小数提交，不走浮点尾巴', () => {
    expect(refundAmountText('31.5678')).toBe('31.5678');
    expect(refundAmountText('10')).toBe('10.0000');
  });
});

describe('格式化', () => {
  it('金额为空显示「—」而不是 0', () => {
    expect(moneyText(null)).toBe('—');
    expect(moneyText('')).toBe('—');
    expect(moneyText('31.5678', 'USD')).toBe('31.5678 USD');
  });

  it('承诺时限已过显示逾期时长', () => {
    expect(promisedText(new Date(Date.now() - 3 * 86400000).toISOString())).toContain('已逾期');
    expect(promisedText(new Date(Date.now() + 2 * 86400000).toISOString())).toContain('还有');
  });

  it('策略未登记时如实报 policyRegistered=false', () => {
    const policy: DropshipTrackingPolicyRow = {
      id: null,
      policyRegistered: false,
      delayToleranceHours: 0,
      criticalDelayHours: 24,
      stallThresholdHours: 72,
      criticalStallHours: 120,
      requireTrackingNoOnShipped: true,
      note: '尚未登记：当前使用迁移内置默认值',
      updatedAt: null,
    };
    expect(policy.policyRegistered).toBe(false);
    expect(policy.id).toBeNull();
  });

  it('任务载荷允许为空字段（不同任务种类的字段集合不同）', () => {
    const task: DropshipTaskRow = {
      id: '1',
      taskNo: 'DTK-1',
      taskKind: 'REFUND_REQUEST',
      taskKindLabel: '退款待办',
      taskStatus: 'OPEN',
      trackingId: '1',
      orderId: '1',
      orderNo: 'DSO-1',
      salesOrderNo: 'SO-1',
      dispositionId: null,
      exceptionId: null,
      targetSystem: 'AFTER_SALE',
      targetOwner: '11 售后',
      payload: {
        orderNo: 'DSO-1',
        salesOrderNo: 'SO-1',
        supplierCode: null,
        supplierTrackingNo: null,
        supplierCarrierCode: null,
        supplierShippedAt: null,
        refundAmount: '31.5678',
        refundCurrencyCode: 'USD',
        urgedAt: null,
        switchedAt: null,
        note: '供应商跑单',
      },
      idempotencyKey: 'RF-1',
      attemptCount: 0,
      lastAttemptAt: null,
      lastError: '',
      claimedByMembershipId: null,
      claimedAt: null,
      finishedAt: null,
      createdAt: new Date().toISOString(),
    };
    expect(task.payload.refundAmount).toBe('31.5678');
    expect(task.payload.supplierTrackingNo).toBeNull();
  });
});
