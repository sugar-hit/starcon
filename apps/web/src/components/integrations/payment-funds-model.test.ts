import { describe, expect, it } from 'vitest';
import type {
  PaymentOperationRow,
  PaymentProviderAvailability,
} from '../../../../../packages/api-contracts/src/index';
import {
  canRetry,
  capabilityTone,
  directProviderCapabilities,
  directionLabel,
  failureClassLabel,
  flowTypeLabel,
  formatAmount,
  formatMoney,
  formatRate,
  idempotencyHint,
  operationStatusLabel,
  operationTone,
  operationTypeLabel,
  receiptProviderNotice,
  unavailableHint,
  validatePayment,
  validateRate,
  validateReceipt,
} from './payment-funds-model';

/**
 * 18.2.2.1「支付与资金」前端模型单测。
 *
 * <p>🔴 重点盯三件**最容易被做丢**的事：
 * <ol>
 *   <li>「结果未知」**不能**显示成失败（会让人直接重发 ⇒ 可能付两次）；</li>
 *   <li>重发按钮**只**在「查证明确说服务商侧没有」时才出现；</li>
 *   <li>收款登记在服务商未接入时**必须**提示「未向服务商登记」。</li>
 * </ol>
 */

function operation(overrides: Partial<PaymentOperationRow> = {}): PaymentOperationRow {
  return {
    id: '00000000-0000-4000-8000-000000000000',
    idempotencyKey: 'idem-1',
    operationType: 'PAYMENT_EXECUTE',
    operationTypeLabel: '付款执行',
    providerId: '00000000-0000-4000-8000-000000000001',
    providerCode: 'PAYPAL',
    providerLabel: 'PayPal',
    direction: 'OUTBOUND',
    status: 'SUCCEEDED',
    statusLabel: '成功',
    amount: 100,
    currency: 'USD',
    baseAmount: null,
    baseCurrency: '',
    exchangeRate: null,
    reference: 'PO-1',
    counterpartyName: '供应商',
    counterpartyRef: '',
    counterpartyType: '',
    providerSerialNo: 'SER-1',
    failureReason: '',
    failureClass: '',
    providerNotified: true,
    providerNotice: '',
    source: 'DIRECT',
    attemptCount: 1,
    retryAllowed: false,
    requestDigest: 'digest',
    replayed: false,
    createdBy: '00000000-0000-4000-8000-0000000000aa',
    createdAt: '2026-10-31T00:00:00Z',
    updatedAt: '2026-10-31T00:00:00Z',
    ...overrides,
  };
}

describe('金额与汇率格式化', () => {
  it('空金额显示破折号而不是 0', () => {
    expect(formatAmount(null)).toBe('—');
    expect(formatAmount(undefined)).toBe('—');
    expect(formatAmount(Number.NaN)).toBe('—');
    expect(formatMoney(null, 'USD')).toBe('—');
  });

  it('金额保留币种且不吞小数', () => {
    expect(formatMoney(100, 'USD')).toBe('100.00 USD');
    expect(formatMoney(1234.56, 'USD')).toBe('1,234.56 USD');
    expect(formatMoney(0.123456, 'USD')).toBe('0.123456 USD');
  });

  it('汇率的方向写在句子里', () => {
    expect(formatRate(7.1234, 'USD', 'CNY')).toBe('1 USD = 7.1234 CNY');
    expect(formatRate(null, 'USD', 'CNY')).toBe('—');
  });
});

describe('状态语气：结果未知不是失败', () => {
  it('UNKNOWN 是 warning，FAILED 才是 danger', () => {
    expect(operationTone('UNKNOWN')).toBe('warn');
    expect(operationTone('FAILED')).toBe('danger');
    expect(operationTone('SUCCEEDED')).toBe('ok');
    expect(operationTone('NOT_CONNECTED')).toBe('muted');
  });

  it('状态中文说法分得开「失败」与「结果未知」', () => {
    expect(operationStatusLabel('FAILED')).toBe('失败');
    expect(operationStatusLabel('UNKNOWN')).toBe('结果未知');
    expect(operationStatusLabel('NOT_CONNECTED')).toBe('服务商未接入');
  });

  it('失败分类能区分拒绝 / 超时 / 不可达 / 未接入', () => {
    expect(failureClassLabel('REJECTED')).toBe('服务商明确拒绝');
    expect(failureClassLabel('TIMEOUT')).toBe('超时');
    expect(failureClassLabel('UNAVAILABLE')).toBe('服务商不可达');
    expect(failureClassLabel('NOT_CONFIGURED')).toBe('未接入');
    expect(failureClassLabel('')).toBe('');
  });

  it('类型与方向的中文说法', () => {
    expect(operationTypeLabel('PAYMENT_EXECUTE')).toBe('付款执行');
    expect(operationTypeLabel('RECEIPT_REGISTER')).toBe('收款登记');
    expect(flowTypeLabel('PAYMENT')).toBe('付款');
    expect(directionLabel('OUTBOUND')).toBe('出');
    expect(directionLabel('')).toBe('—');
  });
});

describe('重发：只有查证确认没有才允许', () => {
  it('UNKNOWN 且允许重试 ⇒ 可以重发', () => {
    expect(canRetry(operation({ status: 'UNKNOWN', retryAllowed: true }))).toBe(true);
  });

  it('UNKNOWN 但查证没给出结论 ⇒ 不许重发', () => {
    expect(canRetry(operation({ status: 'UNKNOWN', retryAllowed: false }))).toBe(false);
  });

  it('已成功 / 已失败 / 未接入 ⇒ 都不许重发', () => {
    expect(canRetry(operation({ status: 'SUCCEEDED', retryAllowed: true }))).toBe(false);
    expect(canRetry(operation({ status: 'FAILED', retryAllowed: true }))).toBe(false);
    expect(canRetry(operation({ status: 'NOT_CONNECTED', retryAllowed: true }))).toBe(false);
  });
});

describe('收款登记：本地台账成功 ≠ 服务商侧已登记', () => {
  it('未通知服务商时必须给出提示', () => {
    const notice = receiptProviderNotice(
      operation({
        operationType: 'RECEIPT_REGISTER',
        status: 'SUCCEEDED',
        providerNotified: false,
        providerNotice: '「Stripe」的**直连适配器未接入**',
      }),
    );
    expect(notice).toBeTruthy();
    expect(notice).toContain('未接入');
  });

  it('已通知服务商时不提示', () => {
    expect(
      receiptProviderNotice(
        operation({ operationType: 'RECEIPT_REGISTER', providerNotified: true }),
      ),
    ).toBeNull();
  });

  it('不是收款登记时不提示', () => {
    expect(receiptProviderNotice(operation({ providerNotified: false }))).toBeNull();
  });
});

describe('幂等键', () => {
  it('重放时明确告诉运营「没再调一次服务商」', () => {
    const hint = idempotencyHint(operation({ replayed: true }));
    expect(hint).toContain('幂等重放');
    expect(hint).toContain('未再次调用服务商');
  });

  it('首次提交说明同键只产生一笔', () => {
    expect(idempotencyHint(operation())).toContain('同键重复提交只会产生一笔');
  });
});

describe('表单校验', () => {
  it('付款必须带幂等键', () => {
    const errors = validatePayment({
      idempotencyKey: '',
      reference: 'PO-1',
      currency: 'USD',
      amount: 100,
      counterpartyName: '供应商',
    });
    expect(errors).toContain('幂等键不能为空');
  });

  it('金额必须大于 0、单号与收款方必填', () => {
    const errors = validatePayment({
      idempotencyKey: 'idem-1',
      reference: '',
      currency: 'USD',
      amount: 0,
      counterpartyName: '',
    });
    expect(errors).toContain('业务单号不能为空');
    expect(errors).toContain('付款金额必须大于 0');
    expect(errors).toContain('收款方名称不能为空');
  });

  it('完整表单没有错误', () => {
    expect(
      validatePayment({
        idempotencyKey: 'idem-1',
        reference: 'PO-1',
        currency: 'USD',
        amount: 100,
        counterpartyName: '供应商',
      }),
    ).toEqual([]);
    expect(
      validateReceipt({
        idempotencyKey: 'idem-1',
        reference: 'RC-1',
        currency: 'USD',
        amount: 100,
        counterpartyName: '客户',
      }),
    ).toEqual([]);
  });

  it('汇率查询不接受相同币种', () => {
    expect(validateRate({ baseCurrency: 'USD', quoteCurrency: 'usd' })).toContain(
      '基准币种与报价币种不能相同',
    );
    expect(validateRate({ baseCurrency: 'USD', quoteCurrency: 'CNY' })).toEqual([]);
  });
});

describe('能力声明与未接通提示', () => {
  it('直连服务商那一维被单独挑出来', () => {
    const rows = directProviderCapabilities([
      {
        moduleId: '18.2.2.1',
        name: '直连 PayPal',
        available: false,
        evidence: '本仓目前没有任何真实的 PayPal 适配器',
        ownerModule: '18.2.2',
        note: '',
      },
      {
        moduleId: '18.2.2.1',
        name: '支付服务商连接档案',
        available: true,
        evidence: 'platform_payment_provider 表',
        ownerModule: '',
        note: '',
      },
    ]);
    expect(rows).toHaveLength(1);
    expect(rows[0]?.name).toBe('直连 PayPal');
  });

  it('能力语气：可用是 ok，不可用是 muted', () => {
    expect(capabilityTone(true)).toBe('ok');
    expect(capabilityTone(false)).toBe('muted');
  });

  it('未接通提示写明归谁与缺什么', () => {
    const hint = unavailableHint({
      providerCode: 'PAYPAL',
      providerLabel: 'PayPal',
      connected: false,
      reason: '本仓目前没有任何真实的 PayPal 适配器',
      ownerModule: '18.2.2',
    });
    expect(hint).toContain('18.2.2');
    expect(hint).toContain('适配器');
  });
});
