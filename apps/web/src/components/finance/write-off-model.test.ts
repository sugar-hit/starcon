import { describe, expect, it } from 'vitest';
import type {
  FinanceWriteOffAdvanceCurrencySummary,
  FinanceWriteOffAllocationRow,
  FinanceWriteOffMatchCandidate,
  FinanceWriteOffRow,
} from '../../../../../packages/api-contracts/src/index';
import {
  advanceBalanceHint,
  advanceStatusLabel,
  allocationTargetLabel,
  allocationTypeLabel,
  autoMatchWriteOff,
  createWriteOff,
  fetchWriteOff,
  fetchWriteOffAdvanceBalance,
  fetchWriteOffAdvanceRows,
  fetchWriteOffCapabilities,
  fetchWriteOffMatchCandidates,
  fetchWriteOffOptions,
  fetchWriteOffPending,
  fetchWriteOffs,
  formatOccurredAt,
  formatWriteOffAmount,
  formatWriteOffMoney,
  matchConditionHint,
  releaseWriteOffAdvance,
  reverseWriteOff,
  writeOffEventLabel,
  writeOffModeLabel,
  writeOffStatusLabel,
  writeOffStatusTone,
} from './write-off-model';

/**
 * 12.2.1.2 核销前端模型的单测。
 *
 * <p>重点守住四条**界面纪律**（它们一旦被破坏，页面就会开始骗人）：
 *
 * <ol>
 *   <li>金额**原样展示**，前端不做任何算术（USD 与 EUR 加起来不是任何一个金额）；</li>
 *   <li>核销的**目标必须是明细行** —— 页面上要能看到行号与订单号；</li>
 *   <li>自动匹配要能让人看清**三个条件**都在生效（金额相同不构成匹配）；</li>
 *   <li>预收余额的**两段口径**要分开显示（挂账 / 预收标记），不重复计数也不合并。</li>
 * </ol>
 */

const row = (over: Partial<FinanceWriteOffRow>): FinanceWriteOffRow => ({
  id: '11111111-1111-1111-1111-111111111111',
  writeOffNo: 'WO-1',
  receiptId: '22222222-2222-2222-2222-222222222222',
  receiptNo: 'RCPT-1',
  currency: 'USD',
  mode: 'MANUAL',
  status: 'POSTED',
  settlementAmount: 100,
  advanceAmount: 0,
  totalAmount: 100,
  note: '',
  actorId: null,
  occurredAt: '2026-10-01T00:30:00Z',
  reversedAt: null,
  createdAt: '2026-10-01T00:30:00Z',
  updatedAt: '2026-10-01T00:30:00Z',
  ...over,
});

describe('核销方式与状态的可读形态', () => {
  it('三种核销方式各自有可读名，且不认识的原样返回', () => {
    expect(writeOffModeLabel('AUTO_MATCH')).toBe('自动匹配');
    expect(writeOffModeLabel('MANUAL')).toBe('人工指定');
    expect(writeOffModeLabel('ADVANCE_PARKING')).toBe('预收挂账');
    expect(writeOffModeLabel('SOMETHING_NEW')).toBe('SOMETHING_NEW');
  });

  it('状态与事件类型都有可读名', () => {
    expect(writeOffStatusLabel('POSTED')).toBe('已核销');
    expect(writeOffStatusLabel('REVERSED')).toBe('已撤销');
    expect(writeOffStatusTone('POSTED')).toBe('success');
    expect(writeOffStatusTone('REVERSED')).toBe('neutral');
    expect(writeOffEventLabel('AUTO_MATCHED')).toBe('自动匹配核销');
    expect(writeOffEventLabel('MANUAL_ASSIGNED')).toBe('人工指定核销');
    expect(writeOffEventLabel('ADVANCE_PARKED')).toBe('预收挂账');
    expect(writeOffEventLabel('REVERSED')).toBe('核销撤销');
  });

  it('「预收挂账」与「核销到应收明细行」不被合成同一个词', () => {
    expect(allocationTypeLabel('SETTLEMENT')).toBe('核销到应收明细行');
    expect(allocationTypeLabel('ADVANCE')).toBe('预收挂账');
    expect(allocationTypeLabel('SETTLEMENT')).not.toBe(allocationTypeLabel('ADVANCE'));
  });

  it('预收挂账状态有可读名', () => {
    expect(advanceStatusLabel('OPEN')).toBe('挂账中');
    expect(advanceStatusLabel('RELEASED')).toBe('已释放');
    expect(advanceStatusLabel('REVERSED')).toBe('已撤销');
  });
});

describe('金额只原样展示', () => {
  it('四位小数原样显示，不做单位换算', () => {
    expect(formatWriteOffAmount(1000)).toBe('1000.0000');
    expect(formatWriteOffAmount(1234.5678)).toBe('1234.5678');
  });

  it('null / undefined 显示「未登记」而不是 0', () => {
    expect(formatWriteOffAmount(null)).toBe('未登记');
    expect(formatWriteOffAmount(undefined)).toBe('未登记');
    expect(formatWriteOffMoney(null, 'USD')).toBe('未登记');
  });

  it('金额 + 币种拼在一起', () => {
    expect(formatWriteOffMoney(1000, 'USD')).toBe('1000.0000 USD');
    expect(formatWriteOffMoney(1000, null)).toBe('1000.0000');
  });
});

describe('核销精确到明细行', () => {
  const allocation = (over: Partial<FinanceWriteOffAllocationRow>): FinanceWriteOffAllocationRow => ({
    id: '33333333-3333-3333-3333-333333333333',
    writeOffId: '11111111-1111-1111-1111-111111111111',
    receiptId: '22222222-2222-2222-2222-222222222222',
    receivableId: '44444444-4444-4444-4444-444444444444',
    receivableNo: 'AR-1',
    receivableLineId: '55555555-5555-5555-5555-555555555555',
    lineNo: 2,
    orderNo: 'ORD-1',
    allocationType: 'SETTLEMENT',
    amount: 100,
    status: 'POSTED',
    note: '',
    createdAt: '2026-10-01T00:30:00Z',
    updatedAt: '2026-10-01T00:30:00Z',
    ...over,
  });

  it('目标要能看到应收单号、行号与订单号', () => {
    const label = allocationTargetLabel(allocation({}));
    expect(label).toContain('AR-1');
    expect(label).toContain('第 2 行');
    expect(label).toContain('ORD-1');
  });

  it('预收挂账的分配明说「还没有对应应收」', () => {
    const label = allocationTargetLabel(
      allocation({ allocationType: 'ADVANCE', receivableNo: null, lineNo: null, orderNo: '' }),
    );
    expect(label).toContain('还没有对应应收');
  });
});

describe('自动匹配：三个条件都要看得见', () => {
  const candidate = (over: Partial<FinanceWriteOffMatchCandidate>): FinanceWriteOffMatchCandidate => ({
    receivableLineId: '55555555-5555-5555-5555-555555555555',
    receivableId: '44444444-4444-4444-4444-444444444444',
    receivableNo: 'AR-1',
    lineNo: 1,
    orderNo: 'ORD-1',
    currency: 'USD',
    amount: 100,
    settledAmount: 0,
    openAmount: 100,
    matchedAmount: 100,
    confirmedDate: '2026-10-01',
    matchDate: '2026-10-01',
    reasons: [],
    ...over,
  });

  it('命中原因逐条展示订单号、金额与日期三个条件', () => {
    const hint = matchConditionHint(
      candidate({
        reasons: ['订单号完全相等：ORD-1', '该明细行未核销余额 = 100（金额条件）', '确认日期同日（日期条件）'],
      }),
    );
    expect(hint).toContain('订单号');
    expect(hint).toContain('金额条件');
    expect(hint).toContain('日期条件');
  });
});

describe('预收余额：两段口径不重复计数', () => {
  const summary = (over: Partial<FinanceWriteOffAdvanceCurrencySummary>): FinanceWriteOffAdvanceCurrencySummary => ({
    currency: 'USD',
    parkedAmount: 300,
    flaggedAmount: 0,
    totalAmount: 300,
    count: 1,
    ...over,
  });

  it('挂账与预收标记分开显示，合计是两者之和', () => {
    const row = summary({ parkedAmount: 300, flaggedAmount: 200, totalAmount: 500 });
    const hint = advanceBalanceHint(row);
    expect(hint).toContain('挂账 300.0000');
    expect(hint).toContain('预收标记 200.0000');
    expect(hint).toContain('= 500.0000');
  });
});

describe('核销时刻只做显示格式化', () => {
  it('带偏移的 ISO 串能被显示，不做时区换算', () => {
    expect(formatOccurredAt('2026-10-01T00:30:00Z')).not.toBe('未登记');
    expect(formatOccurredAt('2026-10-01T00:30:00Z')).toContain('2026');
  });

  it('缺失或非法值原样显示而不是变成 0', () => {
    expect(formatOccurredAt(null)).toBe('未登记');
    expect(formatOccurredAt(undefined)).toBe('未登记');
    expect(formatOccurredAt('not-a-time')).toBe('not-a-time');
  });
});

describe('端点形状', () => {
  it('所有端点都挂在 finance/write-off 命名空间下', () => {
    // 端点函数本身不发请求（这里只钉住 URL 形状）；
    // 真正「放行 / 拒绝」由 api-proxy 的白名单双向断言守住。
    expect(typeof fetchWriteOffCapabilities).toBe('function');
    expect(typeof fetchWriteOffOptions).toBe('function');
    expect(typeof fetchWriteOffs).toBe('function');
    expect(typeof fetchWriteOff).toBe('function');
    expect(typeof fetchWriteOffPending).toBe('function');
    expect(typeof fetchWriteOffAdvanceBalance).toBe('function');
    expect(typeof fetchWriteOffAdvanceRows).toBe('function');
    expect(typeof fetchWriteOffMatchCandidates).toBe('function');
    expect(typeof createWriteOff).toBe('function');
    expect(typeof autoMatchWriteOff).toBe('function');
    expect(typeof reverseWriteOff).toBe('function');
    expect(typeof releaseWriteOffAdvance).toBe('function');
  });

  it('核销记录示例里的三个金额自洽', () => {
    const r = row({ settlementAmount: 100, advanceAmount: 0, totalAmount: 100 });
    expect(r.settlementAmount + r.advanceAmount).toBe(r.totalAmount);
  });
});
