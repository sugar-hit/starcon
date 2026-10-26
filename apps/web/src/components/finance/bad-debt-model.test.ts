import { describe, expect, it } from 'vitest';
import type {
  FinanceBadDebtAction,
  FinanceBadDebtRow,
  FinanceBadDebtStatusOption,
} from '../../../../../packages/api-contracts/src/index';
import {
  badDebtAccountingLabel,
  badDebtActionBlockedReason,
  badDebtEmptyHint,
  badDebtEvidenceTypeLabel,
  badDebtReasonLabel,
  badDebtStatusLabel,
  badDebtWriteOffMethodLabel,
  formatBadDebtAmount,
  formatBadDebtMoney,
} from './bad-debt-model';

/**
 * 12.1.1.3 坏账与核销的**前端模型**断言。
 *
 * <p>断言的不是「函数返回了某个字符串」，而是**界面纪律**：
 * 币种不混算、金额原样展示、**会计处理不可用不被显示成 0**、
 * 不可执行的动作要**说清楚为什么**、空态区分「筛选无结果」与「还没有申请」。
 */

const STATUS_OPTIONS: FinanceBadDebtStatusOption[] = [
  { code: 'DRAFT', label: '草稿' },
  { code: 'PENDING_APPROVAL', label: '待审批' },
  { code: 'APPROVED', label: '已批准待核销' },
  { code: 'REJECTED', label: '已驳回' },
  { code: 'WRITTEN_OFF', label: '已核销' },
  { code: 'VOID', label: '已作废' },
];

function row(overrides: Partial<FinanceBadDebtRow> = {}): FinanceBadDebtRow {
  return {
    id: '00000000-0000-4000-8000-000000000001',
    caseNo: 'BD-ABCDEF123456',
    receivableId: '00000000-0000-4000-8000-000000000002',
    receivableNo: 'AR-ABCDEF123456',
    shopId: null,
    shopName: '',
    counterpartyRef: null,
    counterpartyName: '客户一',
    currency: 'USD',
    receivableAmount: 100,
    receivableSettled: 0,
    openBalance: 100,
    writeOffAmount: 60,
    reasonType: 'PLATFORM_FROZEN',
    reasonDetail: '平台冻结无法收回',
    disputeId: null,
    disputeNo: '',
    disputeStatus: '',
    disputeAmount: null,
    disputeCurrency: null,
    status: 'DRAFT',
    submittedBy: null,
    submittedAt: null,
    decidedBy: null,
    decidedAt: null,
    decisionComment: '',
    writeOffAt: null,
    writeOffMethod: null,
    voidedReceivableNo: '',
    replacementReceivableId: null,
    replacementReceivableNo: '',
    replacementDueDateAdjusted: false,
    accountingStatus: 'PENDING_FINANCE',
    accountingOwnerModule: '12.5.2.1',
    accountingRef: '',
    note: '',
    createdAt: '2026-03-01T00:00:00Z',
    updatedAt: '2026-03-01T00:00:00Z',
    ...overrides,
  };
}

describe('金额展示', () => {
  it('四位小数原样展示，不在前端做算术', () => {
    expect(formatBadDebtAmount(60)).toBe('60.0000');
    expect(formatBadDebtAmount(0)).toBe('0.0000');
  });

  it('空值显示成破折号而不是 0', () => {
    expect(formatBadDebtAmount(null)).toBe('—');
    expect(formatBadDebtAmount(undefined)).toBe('—');
    expect(formatBadDebtMoney(null, 'USD')).toBe('—');
  });

  it('金额与币种拼在一起，币种不丢', () => {
    expect(formatBadDebtMoney(60, 'USD')).toBe('60.0000 USD');
    expect(formatBadDebtMoney(60, 'EUR')).toBe('60.0000 EUR');
  });
});

describe('可读名', () => {
  it('状态与事由取服务端下发的中文标签', () => {
    expect(badDebtStatusLabel('PENDING_APPROVAL', STATUS_OPTIONS)).toBe('待审批');
    expect(badDebtStatusLabel('WRITTEN_OFF', STATUS_OPTIONS)).toBe('已核销');
  });

  it('服务端没给标签时回落到代码，不留空白', () => {
    expect(badDebtStatusLabel('SOMETHING_NEW', STATUS_OPTIONS)).toBe('SOMETHING_NEW');
    expect(badDebtStatusLabel('DRAFT', undefined)).toBe('DRAFT');
  });

  it('依据类型的标签同样取服务端下发', () => {
    expect(
      badDebtEvidenceTypeLabel('PLATFORM_FROZEN_NOTICE', [
        { code: 'PLATFORM_FROZEN_NOTICE', label: '平台冻结通知' },
      ]),
    ).toBe('平台冻结通知');
  });
});

describe('会计处理不可用', () => {
  it('下游未交付时显示「待凭证 + 归属模块」，绝不显示成已入账 0 块', () => {
    const label = badDebtAccountingLabel(row());
    expect(label).toContain('12.5.2.1');
    expect(label).toContain('待凭证');
    expect(label).not.toContain('0');
  });

  it('有凭证号时才显示凭证号', () => {
    const label = badDebtAccountingLabel(
      row({ accountingStatus: 'PENDING_FINANCE', accountingRef: 'VOUCHER-1' }),
    );
    expect(label).toContain('VOUCHER-1');
  });
});

describe('核销路径', () => {
  it('作废 + 新单是可读名', () => {
    expect(badDebtWriteOffMethodLabel(row({ writeOffMethod: 'VOID_AND_REPLACE' }))).toBe(
      '作废原单 + 新建承接单',
    );
  });

  it('还没核销时显示破折号', () => {
    expect(badDebtWriteOffMethodLabel(row())).toBe('—');
  });
});

describe('不可执行的动作要说清楚为什么', () => {
  function action(overrides: Partial<FinanceBadDebtAction>): FinanceBadDebtAction {
    return {
      code: 'SUBMIT',
      label: '提交审批',
      allowed: true,
      method: 'POST',
      path: '/finance/bad-debt/x/submit',
      blockedReason: '',
      ...overrides,
    };
  }

  it('可执行时没有原因', () => {
    expect(badDebtActionBlockedReason(action({}))).toBe('');
  });

  it('不可执行时给出服务端给的原因', () => {
    expect(
      badDebtActionBlockedReason(
        action({ allowed: false, blockedReason: '还没有依据材料：核销是终审动作，必须有完整依据链' }),
      ),
    ).toContain('依据材料');
  });

  it('服务端没给原因时也有兜底文案，不留空白按钮', () => {
    expect(badDebtActionBlockedReason(action({ allowed: false, blockedReason: '' }))).toBe(
      '当前状态不允许这个动作',
    );
  });
});

describe('空态', () => {
  it('筛选无结果与「还没有申请」要说不同的话', () => {
    expect(badDebtEmptyHint(0, true)).toContain('当前筛选条件下没有');
    expect(badDebtEmptyHint(0, false)).toContain('还没有坏账核销申请');
  });
});
