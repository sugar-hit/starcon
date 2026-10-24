import { describe, expect, it } from 'vitest';
import {
  formatFundAccountAmount,
  formatFundAccountMoney,
  formatFundAccountNumber,
  fundAccountStatusLabel,
  fundAccountTypeLabel,
  fundBalanceSourceLabel,
} from './fund-account-model';

/**
 * 12.7.1.1 账户管理前端模型的单测。
 *
 * <p>重点守住四条**界面纪律**（它们一旦被破坏，页面就会开始骗人）：
 *
 * <ol>
 *   <li>金额**原样展示**，前端不做任何业务算术；</li>
 *   <li>「算不出」与「0」必须能分开（服务端给 null，前端显示「—」）——
 *       跨币种合计恒为 null，页面**不能**把它显示成 0；</li>
 *   <li>账号掩码**只有服务端一处实现**：页面显示服务端给的掩码，
 *       **不**自己按「前四位 + **** + 后四位」再算一遍；</li>
 *   <li>可读名取**服务端下发**的值（类型标签、状态标签、来源标签），页面不写死。</li>
 * </ol>
 */

describe('账户类型的可读名', () => {
  it('三种受控类型各有可读名', () => {
    expect(fundAccountTypeLabel('BANK')).toBe('银行账户');
    expect(fundAccountTypeLabel('PAYMENT_PLATFORM')).toBe('支付平台账户');
    expect(fundAccountTypeLabel('PLATFORM_RESERVE')).toBe('平台备付金账户');
  });

  it('不认识的类型优先用服务端标签，再兜底成原值', () => {
    expect(fundAccountTypeLabel('CRYPTO_WALLET', '服务端给的标签')).toBe('服务端给的标签');
    expect(fundAccountTypeLabel('CRYPTO_WALLET')).toBe('CRYPTO_WALLET');
  });
});

describe('账户状态的可读名', () => {
  it('启用 / 停用各有可读名', () => {
    expect(fundAccountStatusLabel('ACTIVE')).toBe('启用');
    expect(fundAccountStatusLabel('INACTIVE')).toBe('停用');
    expect(fundAccountStatusLabel('SOMETHING_NEW', '服务端给的标签')).toBe('服务端给的标签');
  });
});

describe('余额来源的可读名', () => {
  it('建档 / 同步 / 手工各有可读名', () => {
    expect(fundBalanceSourceLabel('INITIAL')).toBe('建档初始');
    expect(fundBalanceSourceLabel('SYNC')).toBe('余额同步');
    expect(fundBalanceSourceLabel('MANUAL')).toBe('手工更新');
    expect(fundBalanceSourceLabel('SOMETHING_NEW')).toBe('SOMETHING_NEW');
  });
});

describe('金额的可读形态', () => {
  it('原样展示 4 位小数，不做任何换算', () => {
    expect(formatFundAccountAmount(1234.5)).toBe('1234.5000');
    expect(formatFundAccountAmount(0)).toBe('0.0000');
    expect(formatFundAccountAmount(-12.3456)).toBe('-12.3456');
  });

  it('「算不出」（null / undefined）显示为破折号而不是 0', () => {
    expect(formatFundAccountAmount(null)).toBe('—');
    expect(formatFundAccountAmount(undefined)).toBe('—');
    expect(formatFundAccountMoney(null, 'USD')).toBe('—');
  });

  it('金额 + 币种拼在一起', () => {
    expect(formatFundAccountMoney(1500.5, 'USD')).toBe('1500.5000 USD');
  });
});

describe('账号的可读形态', () => {
  it('显示服务端给的掩码与后四位', () => {
    expect(formatFundAccountNumber('6222****5678', '5678')).toBe('6222****5678（后四位 5678）');
  });

  it('没有账号时显示破折号', () => {
    expect(formatFundAccountNumber('', '')).toBe('—');
    expect(formatFundAccountNumber(null, null)).toBe('—');
  });
});
