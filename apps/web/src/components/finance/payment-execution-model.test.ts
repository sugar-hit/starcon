import { describe, expect, it } from 'vitest';
import {
  formatPaymentExecutionAmount,
  formatPaymentExchangeRate,
  paymentAttemptKindLabel,
  paymentAttemptOutcomeLabel,
  paymentAttemptOutcomeTone,
  paymentExecutionActions,
  paymentExecutionEventLabel,
  paymentExecutionMethodLabel,
  paymentExecutionStatusLabel,
  paymentExecutionStatusTone,
  paymentFailureClassLabel,
  paymentRateLockStatusLabel,
  pendingPaymentOrderLabel,
} from './payment-execution-model';

/**
 * 12.2.2.2 付款执行前端模型的单测。
 *
 * <p>重点守住四条**界面纪律**（它们一旦被破坏，页面就会开始骗人）：
 *
 * <ol>
 *   <li>🔴 <b>「结果待查证」（UNKNOWN）不是失败</b> —— 外部支付超时不等于失败。
 *       把它渲染成 danger 等于在催人点「重发」，而重复付款是严重资损事故；</li>
 *   <li>金额**原样展示**，前端不做任何业务算术，币种分开；</li>
 *   <li>「已付款」才给成功态；重发入口只在「该重发」时出现；</li>
 *   <li>凭据只显示**引用**，页面永远不显示明文密钥。</li>
 * </ol>
 */

describe('执行状态的可读名与色调', () => {
  it('五态各有可读名，且不认识的原样返回', () => {
    expect(paymentExecutionStatusLabel('PENDING')).toBe('待发送');
    expect(paymentExecutionStatusLabel('SENDING')).toBe('发送中');
    expect(paymentExecutionStatusLabel('SUCCEEDED')).toBe('已付款');
    expect(paymentExecutionStatusLabel('FAILED')).toBe('失败');
    expect(paymentExecutionStatusLabel('UNKNOWN')).toBe('结果待查证');
    expect(paymentExecutionStatusLabel('SOMETHING_NEW')).toBe('SOMETHING_NEW');
  });

  it('🔴 「结果待查证」绝不能是 danger —— 超时不等于失败', () => {
    expect(paymentExecutionStatusTone('UNKNOWN')).not.toBe('danger');
    expect(paymentExecutionStatusTone('UNKNOWN')).toBe('warning');
    // 确定性失败才是 danger。
    expect(paymentExecutionStatusTone('FAILED')).toBe('danger');
  });

  it('只有「已付款」是成功态', () => {
    expect(paymentExecutionStatusTone('SUCCEEDED')).toBe('success');
    expect(paymentExecutionStatusTone('PENDING')).not.toBe('success');
    expect(paymentExecutionStatusTone('SENDING')).not.toBe('success');
  });

  it('尝试结论里，超时 / 不可达 / 金额不符都不是 danger（结果未定）', () => {
    expect(paymentAttemptOutcomeTone('TIMEOUT')).not.toBe('danger');
    expect(paymentAttemptOutcomeTone('CHANNEL_UNAVAILABLE')).not.toBe('danger');
    expect(paymentAttemptOutcomeTone('AMOUNT_MISMATCH')).not.toBe('danger');
    // 渠道明确拒绝与「没配置」是确定性失败。
    expect(paymentAttemptOutcomeTone('REJECTED')).toBe('danger');
    expect(paymentAttemptOutcomeTone('NOT_CONFIGURED')).toBe('danger');
    expect(paymentAttemptOutcomeTone('SUCCESS')).toBe('success');
  });
});

describe('动作可用性', () => {
  it('🔴 已付款不给重发入口（再发就是重复付款）', () => {
    expect(paymentExecutionActions('SUCCEEDED').canRetry).toBe(false);
    expect(paymentExecutionActions('SUCCEEDED').canVerify).toBe(false);
  });

  it('结果未知与失败才给重发入口', () => {
    expect(paymentExecutionActions('UNKNOWN').canRetry).toBe(true);
    expect(paymentExecutionActions('FAILED').canRetry).toBe(true);
    expect(paymentExecutionActions('PENDING').canRetry).toBe(false);
    expect(paymentExecutionActions('SENDING').canRetry).toBe(false);
  });

  it('除已付款外都能查证（查证不花钱，是「先查证再重发」的第一步）', () => {
    expect(paymentExecutionActions('UNKNOWN').canVerify).toBe(true);
    expect(paymentExecutionActions('FAILED').canVerify).toBe(true);
    expect(paymentExecutionActions('SENDING').canVerify).toBe(true);
  });
});

describe('金额与汇率只原样展示', () => {
  it('金额按 4 位小数 + 币种展示，不做任何前端算术', () => {
    expect(formatPaymentExecutionAmount(100, 'USD')).toBe('100.0000 USD');
    expect(formatPaymentExecutionAmount(99.5, 'EUR')).toBe('99.5000 EUR');
    expect(formatPaymentExecutionAmount(null, 'USD')).toBe('—');
    expect(formatPaymentExecutionAmount(100, null)).toBe('100.0000');
  });

  it('汇率展示成「1 A = rate B」', () => {
    expect(formatPaymentExchangeRate(7.2, 'USD', 'CNY')).toBe('1 USD = 7.20000000 CNY');
    expect(formatPaymentExchangeRate(null, 'USD', 'CNY')).toBe('—');
  });
});

describe('其它可读形态', () => {
  it('失败分类各有可读名', () => {
    expect(paymentFailureClassLabel('CHANNEL_REJECTED')).toBe('渠道拒绝');
    expect(paymentFailureClassLabel('CHANNEL_UNAVAILABLE')).toBe('渠道不可用');
    expect(paymentFailureClassLabel('NOT_CONFIGURED')).toBe('渠道未配置');
    expect(paymentFailureClassLabel('TIMEOUT')).toBe('渠道超时');
    expect(paymentFailureClassLabel('AMOUNT_MISMATCH')).toBe('金额不符');
    expect(paymentFailureClassLabel('NONE')).toBe('无');
  });

  it('尝试类型与结论各有可读名', () => {
    expect(paymentAttemptKindLabel('SEND')).toBe('发送');
    expect(paymentAttemptKindLabel('QUERY')).toBe('查证');
    expect(paymentAttemptOutcomeLabel('SUCCESS')).toBe('成功');
    expect(paymentAttemptOutcomeLabel('NOT_FOUND')).toBe('渠道无此笔');
  });

  it('锁汇状态与付款方式各有可读名', () => {
    expect(paymentRateLockStatusLabel('LOCKED')).toBe('生效中');
    expect(paymentRateLockStatusLabel('RELEASED')).toBe('已释放');
    expect(paymentExecutionMethodLabel('BANK_TRANSFER')).toBe('银行电汇');
  });

  it('事件类型各有可读名', () => {
    expect(paymentExecutionEventLabel('SEND_SUCCEEDED')).toBe('发送成功');
    expect(paymentExecutionEventLabel('VERIFY_SUCCEEDED')).toBe('查证发现已成功');
    expect(paymentExecutionEventLabel('VERIFY_NOT_FOUND')).toBe('查证：渠道无此笔');
    expect(paymentExecutionEventLabel('RATE_LOCKED')).toBe('锁定汇率');
  });
});

describe('可发起付款执行的候选项', () => {
  it('没有分批时显示整单', () => {
    expect(
      pendingPaymentOrderLabel({
        orderNo: 'PO-1',
        counterpartyName: '测试供应商',
        currency: 'USD',
        amount: 100,
        installmentSeq: null,
      }),
    ).toBe('PO-1 · 测试供应商 · 100.0000 USD');
  });

  it('有分批时逐批显示（页面必须让人看清付的是哪一批）', () => {
    expect(
      pendingPaymentOrderLabel({
        orderNo: 'PO-2',
        counterpartyName: '测试供应商',
        currency: 'USD',
        amount: 100,
        installmentSeq: 2,
        plannedPaidDate: '2026-10-31',
      }),
    ).toBe('PO-2 · 测试供应商 · 100.0000 USD · 第 2 批 · 计划 2026-10-31');
  });
});
