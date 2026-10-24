import { describe, expect, it } from 'vitest';
import type { ReconciliationDiffActions, ReconciliationDiffRow } from '../../../../../packages/api-contracts/src/index';
import {
  formatReconciliationDiffDifference,
  formatReconciliationDiffMoney,
  reconciliationDiffActionHints,
  reconciliationDiffAdjustmentBasisHint,
  reconciliationDiffAssigneeLabel,
  reconciliationDiffCategoryLabel,
  reconciliationDiffClassifyHint,
  reconciliationDiffCurrencySummaryLabel,
  reconciliationDiffDirectionLabel,
  reconciliationDiffEventLabel,
  reconciliationDiffHint,
  reconciliationDiffStatusLabel,
  reconciliationDiffStatusTone,
  reconciliationDiffTypeLabel,
} from './reconciliation-difference-model';

/**
 * 12.3.2.2 差异处理前端模型的单测。
 *
 * <p>重点守住四条**界面纪律**（它们一旦被破坏，页面就会开始骗人）：
 *
 * <ol>
 *   <li><b>算不出来就说算不出来</b>：汇率不可得时差额显示「—（汇率不可得，不按 1:1 兜底）」，
 *       <b>绝不显示 0</b>（0 会被读成「账是平的」）；</li>
 *   <li><b>绝不跨币种相加</b>：页面只原样展示服务端给的<b>按币种分组</b>的数，
 *       任何一处都**不做前端算术**；</li>
 *   <li><b>「为什么不能点」必须说清楚</b>：每一条被禁用的动作都要有阻断理由，
 *       理由来自服务端，页面不自己推断规则；</li>
 *   <li><b>分类是改进闭环的输入</b>：五个归因分类必须一眼分得清
 *       （我方多记 / 对方多记 / 时间差 / 汇率差 / 手续费遗漏）。</li>
 * </ol>
 */

const row = (over: Partial<ReconciliationDiffRow>): ReconciliationDiffRow => ({
  differenceId: '11111111-1111-1111-1111-111111111111',
  taskId: '22222222-2222-2222-2222-222222222222',
  differenceType: 'BOOK_ONLY',
  differenceTypeLabel: '我方账多出',
  side: 'BOOK',
  sideLabel: '我方账多出',
  bookSource: null,
  groupKey: null,
  externalKind: null,
  externalLineId: null,
  bookLineId: '33333333-3333-3333-3333-333333333333',
  occurredOn: '2026-10-05',
  reference: 'ORD-A',
  transactionType: 'SALE',
  externalAmount: null,
  bookAmount: 25,
  currency: 'USD',
  direction: 'IN',
  differenceAmount: 25,
  exchangeRateAvailable: true,
  attribution: '我方账 · 应收明细 AR-1',
  reason: '我方账有、平台账单没有',
  status: 'OPEN',
  statusLabel: '待处理',
  handlingId: null,
  category: null,
  categoryLabel: null,
  categoryReason: null,
  assigneeId: null,
  claimedAt: null,
  conclusion: '',
  closedAt: null,
  adjustmentCount: 0,
  ...over,
});

const actions = (over: Partial<ReconciliationDiffActions>): ReconciliationDiffActions => ({
  canClaim: true,
  claimBlockReason: '',
  canAssign: false,
  assignBlockReason: '只有在「已认领」的差异上才能改派责任人（当前 待处理）',
  canClassify: true,
  classifyBlockReason: '',
  canRecordAdjustment: false,
  recordAdjustmentBlockReason: '只有在「已认领」的差异上才能登记调整分录（当前 待处理）',
  canAdjust: false,
  adjustBlockReason: '只有在「已认领」的差异上才能判定闭环（当前 待处理）',
  canWriteOff: false,
  writeOffBlockReason: '只有在「已认领」的差异上才能判定闭环（当前 待处理）',
  ...over,
});

describe('12.3.2.2 差异处理 · 界面纪律', () => {
  it('差额算不出来就说算不出来 —— 绝不显示 0', () => {
    expect(
      formatReconciliationDiffDifference(null, 'EUR', false),
    ).toContain('汇率不可得');
    expect(formatReconciliationDiffDifference(null, 'EUR', false)).not.toContain('0.0000');
    expect(formatReconciliationDiffDifference(null, 'EUR', true)).toBe('—');
    expect(formatReconciliationDiffDifference(-75, 'USD', true)).toBe('-75.0000 USD');
  });

  it('金额只原样展示服务端给的数', () => {
    expect(formatReconciliationDiffMoney(25, 'USD')).toBe('25.0000 USD');
    expect(formatReconciliationDiffMoney(null, 'USD')).toBe('—');
    expect(formatReconciliationDiffMoney(12.5, null)).toBe('12.5000');
  });

  it('一条差异的一行结论原样带出两侧与差额', () => {
    const hint = reconciliationDiffHint(row({ externalAmount: 100, bookAmount: 25 }));
    expect(hint).toContain('平台 100.0000 USD');
    expect(hint).toContain('我方 25.0000 USD');
    expect(hint).toContain('差额 25.0000 USD');
  });

  it('跨币种差异的差额显示为不可得而不是零', () => {
    const hint = reconciliationDiffHint(
      row({ currency: 'EUR', differenceAmount: null, exchangeRateAvailable: false }),
    );
    expect(hint).toContain('汇率不可得');
    expect(hint).not.toContain('0.0000');
  });

  it('按币种分组的汇总一行只讲同一个币种', () => {
    const label = reconciliationDiffCurrencySummaryLabel({
      currency: 'USD',
      differenceAmount: -75,
      adjustedAmount: 10,
      adjustmentCount: 2,
      openCount: 3,
      claimedCount: 1,
      adjustedCount: 1,
      writtenOffCount: 0,
      exchangeRateAvailable: true,
    });
    expect(label).toContain('-75.0000 USD');
    expect(label).toContain('10.0000 USD');
    expect(label).toContain('待处理 3');
    // 页面**不**给「全部币种合计」这一格。
    expect(label).not.toContain('合计');
  });

  it('没有调整分录时调整额显示为「—」而不是 0', () => {
    const label = reconciliationDiffCurrencySummaryLabel({
      currency: 'EUR',
      differenceAmount: null,
      adjustedAmount: null,
      adjustmentCount: 0,
      openCount: 1,
      claimedCount: 0,
      adjustedCount: 0,
      writtenOffCount: 0,
      exchangeRateAvailable: false,
    });
    expect(label).toContain('调整额 —');
    expect(label).not.toContain('0.0000');
  });

  it('五个归因分类必须一眼分得清', () => {
    expect(reconciliationDiffCategoryLabel('BOOK_OVER_RECORDED')).toBe('我方多记');
    expect(reconciliationDiffCategoryLabel('COUNTERPARTY_OVER_RECORDED')).toBe('对方多记');
    expect(reconciliationDiffCategoryLabel('TIMING_DIFFERENCE')).toBe('时间差');
    expect(reconciliationDiffCategoryLabel('FX_DIFFERENCE')).toBe('汇率差');
    expect(reconciliationDiffCategoryLabel('FEE_OMISSION')).toBe('手续费遗漏');
  });

  it('上游差异类型与归因分类是两回事 —— 文案不许混', () => {
    expect(reconciliationDiffTypeLabel('EXTERNAL_ONLY')).toBe('平台账单多出');
    expect(reconciliationDiffTypeLabel('BOOK_ONLY')).toBe('我方账多出');
    expect(reconciliationDiffTypeLabel('CURRENCY_MISMATCH')).toBe('币种不一致');
    // 归因里没有「平台账单多出」这一说 —— 那是差异类型，不是分类。
    expect(reconciliationDiffCategoryLabel('EXTERNAL_ONLY')).toBe('EXTERNAL_ONLY');
  });

  it('处理状态文案与色调：闭环是终态，待处理是 danger', () => {
    expect(reconciliationDiffStatusLabel('OPEN')).toBe('待处理');
    expect(reconciliationDiffStatusLabel('CLAIMED')).toBe('已认领');
    expect(reconciliationDiffStatusLabel('ADJUSTED')).toBe('已调整（终态）');
    expect(reconciliationDiffStatusLabel('WRITTEN_OFF')).toBe('已核销（终态）');
    expect(reconciliationDiffStatusTone('OPEN')).toBe('danger');
    expect(reconciliationDiffStatusTone('ADJUSTED')).toBe('success');
    expect(reconciliationDiffStatusTone('WRITTEN_OFF')).toBe('success');
  });

  it('事件类型文案覆盖处理时间线的全部事件', () => {
    expect(reconciliationDiffEventLabel('HANDLING_CREATED')).toBe('建立处理记录');
    expect(reconciliationDiffEventLabel('ASSIGNED')).toBe('指定责任人');
    expect(reconciliationDiffEventLabel('CLASSIFIED')).toBe('分类归因');
    expect(reconciliationDiffEventLabel('ADJUSTMENT_RECORDED')).toBe('登记调整分录');
    expect(reconciliationDiffEventLabel('ADJUSTED')).toBe('判定已调整');
    expect(reconciliationDiffEventLabel('WRITTEN_OFF')).toBe('判定已核销');
  });

  it('责任人不编造姓名 —— 只显示 uuid 前 8 位', () => {
    expect(reconciliationDiffAssigneeLabel('abcdef01-2345-6789-abcd-ef0123456789')).toBe('abcdef01');
    expect(reconciliationDiffAssigneeLabel(null)).toBe('未指派');
    expect(reconciliationDiffAssigneeLabel(undefined)).toBe('未指派');
  });

  it('方向文案分得清收入与支出', () => {
    expect(reconciliationDiffDirectionLabel('IN')).toBe('收入');
    expect(reconciliationDiffDirectionLabel('OUT')).toBe('支出');
  });

  it('「为什么不能点」必须说清楚 —— 每条被禁用的动作都有理由', () => {
    const hints = reconciliationDiffActionHints(actions({}));
    expect(hints).toHaveLength(4);
    expect(hints.join('\n')).toContain('改派责任人');
    expect(hints.join('\n')).toContain('登记调整分录');
    expect(hints.join('\n')).toContain('判定闭环');
  });

  it('全部可做时不产生任何阻断理由', () => {
    const hints = reconciliationDiffActionHints(
      actions({
        canAssign: true,
        assignBlockReason: '',
        canRecordAdjustment: true,
        recordAdjustmentBlockReason: '',
        canAdjust: true,
        adjustBlockReason: '',
        canWriteOff: true,
        writeOffBlockReason: '',
      }),
    );
    expect(hints).toEqual([]);
  });

  it('「已调整」缺调整分录时，阻断理由点名缺什么', () => {
    const hints = reconciliationDiffActionHints(
      actions({
        canClaim: false,
        claimBlockReason: '该差异已被处理（已核销），不能再认领',
        canAssign: true,
        assignBlockReason: '',
        canRecordAdjustment: true,
        recordAdjustmentBlockReason: '',
        canWriteOff: true,
        writeOffBlockReason: '',
        adjustBlockReason: '判定「已调整」必须至少有一条调整分录',
      }),
    );
    expect(hints).toEqual([
      '该差异已被处理（已核销），不能再认领',
      '判定「已调整」必须至少有一条调整分录',
    ]);
  });

  it('调整分录的凭证与说明至少要有一个 —— 提示在提交前就说清', () => {
    expect(reconciliationDiffAdjustmentBasisHint({ voucherNo: '', explanation: '' })).toContain(
      '至少要有一个',
    );
    expect(reconciliationDiffAdjustmentBasisHint({ voucherNo: '  ', explanation: '  ' })).toContain(
      '至少要有一个',
    );
    expect(reconciliationDiffAdjustmentBasisHint({ voucherNo: 'VCH-1', explanation: '' })).toBe('');
    expect(reconciliationDiffAdjustmentBasisHint({ voucherNo: '', explanation: '补记手续费' })).toBe('');
  });

  it('分类理由不能为空', () => {
    expect(reconciliationDiffClassifyHint({ reason: '' })).toContain('不能为空');
    expect(reconciliationDiffClassifyHint({ reason: '   ' })).toContain('不能为空');
    expect(reconciliationDiffClassifyHint({ reason: '平台扣了佣金' })).toBe('');
  });
});
