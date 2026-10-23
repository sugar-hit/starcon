import { describe, expect, it } from 'vitest';
import type { FinanceVoucherRow } from '../../../../../packages/api-contracts/src/index';
import {
  formatVoucherAmount,
  formatVoucherMoney,
  formatVoucherPeriod,
  formatVoucherQuantity,
  voucherEditable,
  voucherEventTypeLabel,
  voucherLineTypeLabel,
  voucherPeriodStatusLabel,
  voucherReversible,
  voucherSourceTypeLabel,
  voucherStatusLabel,
  voucherStatusTone,
  voucherVoidable,
} from './voucher-model';

/**
 * 12.5.2.1 凭证管理前端模型的单测。
 *
 * <p>重点守住四条**界面纪律**（它们一旦被破坏，页面就会开始骗人）：
 *
 * <ol>
 *   <li><b>已过账 ≠ 可改</b>：可编辑 / 可红冲 / 可作废三个判断必须互斥且正确 ——
 *       「已过账还能改」是蓝图明令禁止的事；</li>
 *   <li><b>红冲不是成功</b>：语气必须与「已过账」区分开，否则人会以为它还在账上；</li>
 *   <li>金额**原样展示**，前端不做任何业务算术；</li>
 *   <li>可读名覆盖**全部词表取值**，且不认识的原样返回（新增取值时页面不会显示 undefined）。</li>
 * </ol>
 */

function voucher(overrides: Partial<FinanceVoucherRow> = {}): FinanceVoucherRow {
  return {
    id: '11111111-1111-1111-1111-111111111111',
    voucherNo: 'VC-202610-0001',
    sourceType: 'MANUAL',
    sourceTypeLabel: '手工录入',
    sourceId: null,
    sourceNo: '',
    reversalOfId: null,
    reversalVoucherId: null,
    periodId: '22222222-2222-2222-2222-222222222222',
    periodCode: '2026-10',
    voucherDate: '2026-10-20',
    description: '测试凭证',
    currency: 'USD',
    status: 'DRAFT',
    statusLabel: '草稿',
    totalDebit: 0,
    totalCredit: 0,
    lineCount: 0,
    postedAt: null,
    postedBy: null,
    reversedAt: null,
    note: '',
    version: 0,
    createdBy: null,
    createdAt: '2026-10-20T02:00:00Z',
    updatedAt: '2026-10-20T02:00:00Z',
    ...overrides,
  } as FinanceVoucherRow;
}

describe('凭证状态的可读名', () => {
  it('四个状态各自有可读名，不认识的原样返回', () => {
    expect(voucherStatusLabel('DRAFT')).toBe('草稿');
    expect(voucherStatusLabel('POSTED')).toBe('已过账');
    expect(voucherStatusLabel('REVERSED')).toBe('已红冲');
    expect(voucherStatusLabel('VOID')).toBe('已作废');
    expect(voucherStatusLabel('SOMETHING_NEW')).toBe('SOMETHING_NEW');
  });

  it('「已红冲」的语气独立于「已过账」—— 它已被反向凭证冲掉，不该显示成成功', () => {
    expect(voucherStatusTone('POSTED')).toBe('success');
    expect(voucherStatusTone('REVERSED')).toBe('warning');
    expect(voucherStatusTone('REVERSED')).not.toBe(voucherStatusTone('POSTED'));
    expect(voucherStatusTone('DRAFT')).toBe('neutral');
    expect(voucherStatusTone('VOID')).toBe('danger');
    expect(voucherStatusTone('SOMETHING_NEW')).toBe('neutral');
  });
});

describe('来源类型与分录方向的可读名', () => {
  it('全部来源类型都有可读名', () => {
    expect(voucherSourceTypeLabel('MANUAL')).toBe('手工录入');
    expect(voucherSourceTypeLabel('SHIPMENT')).toBe('发货');
    expect(voucherSourceTypeLabel('RECEIPT')).toBe('收货');
    expect(voucherSourceTypeLabel('PAYMENT')).toBe('收付款');
    expect(voucherSourceTypeLabel('EXPENSE_ACCRUAL')).toBe('费用计提');
    expect(voucherSourceTypeLabel('REVERSAL')).toBe('红冲');
    expect(voucherSourceTypeLabel('SOMETHING_NEW')).toBe('SOMETHING_NEW');
  });

  it('借贷方向是「借」「贷」，不认识的原样返回', () => {
    expect(voucherLineTypeLabel('DEBIT')).toBe('借');
    expect(voucherLineTypeLabel('CREDIT')).toBe('贷');
    expect(voucherLineTypeLabel('SOMETHING_NEW')).toBe('SOMETHING_NEW');
  });
});

describe('审计事件与期间状态的可读名', () => {
  it('全部审计事件都有可读名', () => {
    expect(voucherEventTypeLabel('CREATED')).toBe('创建');
    expect(voucherEventTypeLabel('UPDATED')).toBe('修改');
    expect(voucherEventTypeLabel('AUTO_GENERATED')).toBe('自动生成');
    expect(voucherEventTypeLabel('POSTED')).toBe('过账');
    expect(voucherEventTypeLabel('REVERSAL_CREATED')).toBe('生成红冲凭证');
    expect(voucherEventTypeLabel('REVERSED')).toBe('已红冲');
    expect(voucherEventTypeLabel('VOIDED')).toBe('作废');
    expect(voucherEventTypeLabel('EXPORTED')).toBe('导出');
    expect(voucherEventTypeLabel('VALIDATION_FAILED')).toBe('校验未通过');
    expect(voucherEventTypeLabel('SOMETHING_NEW')).toBe('SOMETHING_NEW');
  });

  it('期间状态有可读名', () => {
    expect(voucherPeriodStatusLabel('OPEN')).toBe('开放');
    expect(voucherPeriodStatusLabel('CLOSED')).toBe('已关闭');
    expect(voucherPeriodStatusLabel('SOMETHING_NEW')).toBe('SOMETHING_NEW');
  });
});

describe('可执行动作的判断', () => {
  it('草稿可改、不可红冲、不可作废之外——它可作废', () => {
    const draft = voucher({ status: 'DRAFT' });
    expect(voucherEditable(draft)).toBe(true);
    expect(voucherReversible(draft)).toBe(false);
    expect(voucherVoidable(draft)).toBe(true);
  });

  it('已过账不可改，只能红冲', () => {
    const posted = voucher({ status: 'POSTED' });
    expect(voucherEditable(posted)).toBe(false);
    expect(voucherReversible(posted)).toBe(true);
    expect(voucherVoidable(posted)).toBe(false);
  });

  it('已红冲 / 已作废都是终态：三个动作都不给', () => {
    for (const status of ['REVERSED', 'VOID']) {
      const row = voucher({ status });
      expect(voucherEditable(row)).toBe(false);
      expect(voucherReversible(row)).toBe(false);
      expect(voucherVoidable(row)).toBe(false);
    }
  });

  it('没有凭证时三个判断都是 false（不做「允许」的默认）', () => {
    expect(voucherEditable(null)).toBe(false);
    expect(voucherReversible(null)).toBe(false);
    expect(voucherVoidable(null)).toBe(false);
  });
});

describe('金额与数量的可读形态', () => {
  it('金额原样透传，四位小数', () => {
    expect(formatVoucherAmount(100)).toBe('100.0000');
    expect(formatVoucherAmount(128.5)).toBe('128.5000');
    expect(formatVoucherAmount(0)).toBe('0.0000');
  });

  it('「算不出」与「0」必须能分开（服务端给 null，前端显示「—」）', () => {
    expect(formatVoucherAmount(null)).toBe('—');
    expect(formatVoucherAmount(undefined)).toBe('—');
    expect(formatVoucherAmount(0)).not.toBe('—');
  });

  it('金额 + 币种拼在一起', () => {
    expect(formatVoucherMoney(100, 'USD')).toBe('100.0000 USD');
    expect(formatVoucherMoney(null, 'USD')).toBe('—');
    expect(formatVoucherMoney(100, null)).toBe('100.0000');
  });

  it('数量原样透传（numeric(19,6)）', () => {
    expect(formatVoucherQuantity(3)).toBe('3');
    expect(formatVoucherQuantity(3.5)).toBe('3.5');
    expect(formatVoucherQuantity(null)).toBe('—');
  });

  it('期间区间只做拼接，不做日期差计算', () => {
    expect(formatVoucherPeriod('2026-10-01', '2026-10-31')).toBe('2026-10-01 ~ 2026-10-31');
    expect(formatVoucherPeriod(null, '2026-10-31')).toBe('—');
    expect(formatVoucherPeriod(undefined, undefined)).toBe('—');
  });
});
