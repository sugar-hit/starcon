import { describe, expect, it } from 'vitest';
import type {
  FinanceReconCurrencySummary,
  FinanceReconDifferenceRow,
  FinanceReconMatchRow,
  FinanceReconTaskRow,
} from '../../../../../packages/api-contracts/src/index';
import {
  formatReconciliationDifference,
  formatReconciliationMoney,
  reconciliationBookSourceLabel,
  reconciliationCurrencySummaryLabel,
  reconciliationDifferenceActionHint,
  reconciliationDifferenceStatusLabel,
  reconciliationDifferenceTone,
  reconciliationDifferenceTypeLabel,
  reconciliationDimensionLabel,
  reconciliationDimensionsLabel,
  reconciliationDirectionLabel,
  reconciliationEventLabel,
  reconciliationExternalKindLabel,
  reconciliationMatchBlockReason,
  reconciliationMatchHint,
  reconciliationScopeFrozenHint,
  reconciliationScopeLabel,
  reconciliationShapeLabel,
  reconciliationShapeTone,
  reconciliationSideLabel,
  reconciliationStatusLabel,
  reconciliationStatusTone,
} from './reconciliation-task-model';

/**
 * 12.3.2.1 对账任务前端模型的单测。
 *
 * <p>重点守住四条**界面纪律**（它们一旦被破坏，页面就会开始骗人）：
 *
 * <ol>
 *   <li>「<b>不允许金额相同即匹配</b>」在界面上的落点是「比对了哪几个维度」——
 *       一对一 / 一对多 / 多对一必须一眼分得清，且匹配依据必须能被说出来；</li>
 *   <li>「<b>算不出来就说算不出来</b>」：汇率不可得时差额显示「—（汇率不可得，不按 1:1 兜底）」，
 *       **绝不显示 0**（0 会被读成「账是平的」）；</li>
 *   <li>「<b>已执行的任务不可改写</b>」：MATCHED 态必须同时禁用「改范围」与「跑匹配」，
 *       并把「要重来请新建一个任务」说清楚；</li>
 *   <li>「<b>12.3.2.2 未交付</b>」：差异清单页不许出现任何处理动作的暗示。</li>
 * </ol>
 */

const task = (over: Partial<FinanceReconTaskRow>): FinanceReconTaskRow => ({
  id: '11111111-1111-1111-1111-111111111111',
  taskNo: 'RT-20261024-A1B2C3D4',
  accountId: '22222222-2222-2222-2222-222222222222',
  accountNo: 'ACC-001',
  accountName: '亚马逊美国账户',
  currency: 'USD',
  legalEntityName: '星通科技',
  shopId: '33333333-3333-3333-3333-333333333333',
  shopName: '亚马逊美国站',
  platformCode: 'AMAZON',
  periodStart: '2026-10-01',
  periodEnd: '2026-10-31',
  bookSources: ['RECEIVABLE'],
  dateWindowDays: 7,
  amountTolerance: 0,
  status: 'DRAFT',
  statusLabel: '待执行匹配',
  externalLineCount: 0,
  matchedGroupCount: 0,
  matchedLineCount: 0,
  differenceCount: 0,
  matchedAt: null,
  note: '',
  version: 0,
  createdAt: '2026-10-24T01:00:00Z',
  updatedAt: '2026-10-24T01:00:00Z',
  ...over,
});

const match = (over: Partial<FinanceReconMatchRow>): FinanceReconMatchRow => ({
  id: '44444444-4444-4444-4444-444444444444',
  taskId: '11111111-1111-1111-1111-111111111111',
  groupKey: '55555555-5555-5555-5555-555555555555',
  groupNo: 1,
  shape: 'ONE_TO_ONE',
  shapeLabel: '一对一',
  bookSource: 'RECEIVABLE',
  bookSourceLabel: '应收明细',
  externalKind: 'SETTLEMENT_LINE',
  externalKindLabel: '结算明细行',
  externalLineId: '66666666-6666-6666-6666-666666666666',
  externalNo: 'STL-1',
  externalReference: 'SO-2026-0001',
  externalType: 'ORDER',
  externalOccurredOn: '2026-10-10',
  externalAmount: 100,
  bookLineId: '77777777-7777-7777-7777-777777777777',
  bookNo: 'AR-1',
  bookReference: 'SO-2026-0001',
  bookType: 'SALE',
  bookOccurredOn: '2026-10-10',
  bookAmount: 100,
  currency: 'USD',
  direction: 'IN',
  groupExternalAmount: 100,
  groupBookAmount: 100,
  groupAmountDifference: 0,
  matchedDimensions: ['REFERENCE', 'DATE'],
  dimensionCount: 2,
  matchBasis: '订单号 + 日期 相同，金额相等',
  dayDifference: 0,
  matchedAt: '2026-10-24T02:00:00Z',
  ...over,
});

const difference = (
  over: Partial<FinanceReconDifferenceRow>,
): FinanceReconDifferenceRow => ({
  id: '88888888-8888-8888-8888-888888888888',
  taskId: '11111111-1111-1111-1111-111111111111',
  differenceType: 'AMOUNT_MISMATCH',
  differenceTypeLabel: '金额对不齐',
  side: 'EXTERNAL',
  sideLabel: '平台账单多出',
  bookSource: 'RECEIVABLE',
  groupKey: null,
  externalKind: 'SETTLEMENT_LINE',
  externalLineId: '66666666-6666-6666-6666-666666666666',
  bookLineId: '77777777-7777-7777-7777-777777777777',
  occurredOn: '2026-10-10',
  reference: 'SO-2026-0001',
  transactionType: 'ORDER',
  externalAmount: 100,
  bookAmount: 98.5,
  currency: 'USD',
  direction: 'IN',
  differenceAmount: -1.5,
  exchangeRateAvailable: true,
  attribution: '结算明细行 STL-1 ⇄ 应收明细 AR-1',
  reason: '组内金额不等',
  status: 'OPEN',
  statusLabel: '待处理（12.3.2.2 未交付）',
  ...over,
});

const currencySummary = (
  over: Partial<FinanceReconCurrencySummary>,
): FinanceReconCurrencySummary => ({
  currency: 'USD',
  exchangeRateAvailable: true,
  externalAmount: 1000,
  bookAmount: 998.5,
  differenceAmount: -1.5,
  matchedGroupCount: 8,
  differenceCount: 2,
  note: '',
  ...over,
});

describe('12.3.2.1 对账任务 · 状态与形态', () => {
  it('DRAFT 与 MATCHED 是两件事，措辞与色调都不同', () => {
    expect(reconciliationStatusLabel('DRAFT')).toBe('待执行匹配');
    expect(reconciliationStatusLabel('MATCHED')).toBe('已匹配（结果不可改写）');
    expect(reconciliationStatusLabel('DRAFT')).not.toBe(reconciliationStatusLabel('MATCHED'));
    expect(reconciliationStatusTone('DRAFT')).toBe('warning');
    expect(reconciliationStatusTone('MATCHED')).toBe('success');
  });

  it('一对一 / 一对多 / 多对一 / 多对多是四个不同的文案', () => {
    const labels = [
      reconciliationShapeLabel('ONE_TO_ONE'),
      reconciliationShapeLabel('ONE_TO_MANY'),
      reconciliationShapeLabel('MANY_TO_ONE'),
      reconciliationShapeLabel('MANY_TO_MANY'),
    ];
    expect(labels).toEqual(['一对一', '一对多', '多对一', '多对多']);
    expect(new Set(labels).size).toBe(4);
    expect(reconciliationShapeTone('ONE_TO_ONE')).toBe('success');
    expect(reconciliationShapeTone('ONE_TO_MANY')).toBe('warning');
    expect(reconciliationShapeTone('MANY_TO_ONE')).toBe('warning');
    expect(reconciliationShapeTone('MANY_TO_MANY')).toBe('danger');
  });
});

describe('12.3.2.1 对账任务 · 金额只原样展示', () => {
  it('空金额显示「—」而不是 0', () => {
    expect(formatReconciliationMoney(null, 'USD')).toBe('—');
    expect(formatReconciliationMoney(undefined, 'USD')).toBe('—');
    expect(formatReconciliationMoney(0, 'USD')).toBe('0.0000 USD');
  });

  it('金额带币种，四位小数原样呈现', () => {
    expect(formatReconciliationMoney(98.5, 'USD')).toBe('98.5000 USD');
    expect(formatReconciliationMoney(98.5, null)).toBe('98.5000');
  });

  it('汇率不可得时差额显示「算不出来」，绝不显示 0', () => {
    const text = formatReconciliationDifference(null, 'USD', false);
    expect(text).toContain('汇率不可得');
    expect(text).not.toBe('0.0000 USD');
    expect(text).not.toContain('0.0000');
  });

  it('差额算得出来时按币种原样显示', () => {
    expect(formatReconciliationDifference(-1.5, 'USD', true)).toBe('-1.5000 USD');
  });
});

describe('12.3.2.1 对账任务 · 按币种分组，绝不相加', () => {
  it('汇总会把平台侧、我方侧、差额与计数说在一行里', () => {
    const text = reconciliationCurrencySummaryLabel(currencySummary({}));
    expect(text).toContain('平台 1000.0000 USD');
    expect(text).toContain('我方 998.5000 USD');
    expect(text).toContain('差额 -1.5000 USD');
    expect(text).toContain('匹配组 8 个');
    expect(text).toContain('差异 2 条');
  });

  it('汇率不可得的币种：差额显示「算不出来」，不显示 0', () => {
    const text = reconciliationCurrencySummaryLabel(
      currencySummary({ differenceAmount: null, exchangeRateAvailable: false }),
    );
    expect(text).toContain('汇率不可得');
    expect(text).not.toContain('差额 0.0000');
  });

  it('两个币种各算各的，谁也不进谁的合计', () => {
    const usd = reconciliationCurrencySummaryLabel(currencySummary({ currency: 'USD' }));
    const eur = reconciliationCurrencySummaryLabel(
      currencySummary({ currency: 'EUR', externalAmount: 200, bookAmount: 200 }),
    );
    expect(usd).toContain('USD');
    expect(eur).toContain('EUR');
    expect(usd).not.toContain('EUR');
    expect(eur).not.toContain('USD');
  });
});

describe('12.3.2.1 对账任务 · 不允许「金额相同即匹配」在界面可见', () => {
  it('匹配依据必须说出「比对了哪几个维度」', () => {
    const text = reconciliationMatchHint(match({}));
    expect(text).toContain('一对一');
    expect(text).toContain('订单号 + 日期');
    expect(text).toContain('2 个');
  });

  it('只对上 1 个维度时，维度说明里只有那一个维度（不冒充两个）', () => {
    const text = reconciliationMatchHint(
      match({ matchedDimensions: ['AMOUNT'], dimensionCount: 1 }),
    );
    expect(text).toContain('金额（1 个）');
  });

  it('维度标签是受控词表', () => {
    expect(reconciliationDimensionLabel('REFERENCE')).toBe('订单号');
    expect(reconciliationDimensionLabel('DATE')).toBe('日期');
    expect(reconciliationDimensionLabel('TYPE')).toBe('类型');
    expect(reconciliationDimensionLabel('AMOUNT')).toBe('金额');
    expect(reconciliationDimensionsLabel(['REFERENCE', 'DATE'])).toBe('订单号 + 日期');
  });

  it('相差几天要说清楚', () => {
    expect(reconciliationMatchHint(match({ dayDifference: 0 }))).toContain('同一天');
    expect(reconciliationMatchHint(match({ dayDifference: 3 }))).toContain('相差 3 天');
  });
});

describe('12.3.2.1 对账任务 · 差异清单', () => {
  it('五类差异各有各的说法', () => {
    expect(reconciliationDifferenceTypeLabel('EXTERNAL_ONLY')).toBe('平台账单多出');
    expect(reconciliationDifferenceTypeLabel('BOOK_ONLY')).toBe('我方账多出');
    expect(reconciliationDifferenceTypeLabel('AMOUNT_MISMATCH')).toBe('金额对不齐');
    expect(reconciliationDifferenceTypeLabel('CURRENCY_MISMATCH')).toBe('币种不一致');
    expect(reconciliationDifferenceTypeLabel('DIMENSION_MISMATCH')).toBe('维度对不上');
  });

  it('币种不一致与维度对不上是危险级（红色），不是普通提醒', () => {
    expect(reconciliationDifferenceTone('CURRENCY_MISMATCH')).toBe('danger');
    expect(reconciliationDifferenceTone('DIMENSION_MISMATCH')).toBe('danger');
    expect(reconciliationDifferenceTone('AMOUNT_MISMATCH')).toBe('warning');
  });

  it('未处理差异明确说「处理动作属 12.3.2.2（未交付）」', () => {
    const hint = reconciliationDifferenceActionHint(difference({ status: 'OPEN' }));
    expect(hint).toContain('12.3.2.2');
    expect(hint).toContain('未交付');
    expect(hint).toContain('不提供处理动作');
  });

  it('差异状态文案说明只有「待处理」一种真实状态', () => {
    expect(reconciliationDifferenceStatusLabel('OPEN')).toContain('12.3.2.2');
  });

  it('差异所在侧说清楚是哪一侧多出来的', () => {
    expect(reconciliationSideLabel('EXTERNAL')).toBe('平台账单多出');
    expect(reconciliationSideLabel('BOOK')).toBe('我方账多出');
  });
});

describe('12.3.2.1 对账任务 · 已执行的任务不可改写', () => {
  it('MATCHED 态禁用跑匹配并说明为什么', () => {
    const reason = reconciliationMatchBlockReason(task({ status: 'MATCHED' }));
    expect(reason).toContain('不可改写');
    expect(reason).toContain('新建一个任务');
  });

  it('DRAFT 态不拦着跑匹配', () => {
    expect(reconciliationMatchBlockReason(task({ status: 'DRAFT' }))).toBe('');
  });

  it('MATCHED 态对账范围已冻结，提示里写明', () => {
    expect(reconciliationScopeFrozenHint(task({ status: 'MATCHED' }))).toContain('范围已冻结');
    expect(reconciliationScopeFrozenHint(task({ status: 'DRAFT' }))).toBe('');
  });
});

describe('12.3.2.1 对账任务 · 对账范围', () => {
  it('店铺为空时显示「全部授权店铺」，不是 null / undefined', () => {
    const label = reconciliationScopeLabel({
      accountNo: 'ACC-001',
      accountName: '亚马逊美国账户',
      shopName: '',
      shopId: null,
      platformCode: '',
      periodStart: '2026-10-01',
      periodEnd: '2026-10-31',
      bookSources: ['RECEIVABLE'],
    });
    expect(label).toContain('全部授权店铺');
    expect(label).not.toContain('null');
    expect(label).not.toContain('undefined');
  });

  it('范围文案带上账户、店铺、周期与我方账来源', () => {
    const label = reconciliationScopeLabel({
      accountNo: 'ACC-001',
      accountName: '亚马逊美国账户',
      shopName: '亚马逊美国站',
      shopId: '33333333-3333-3333-3333-333333333333',
      platformCode: 'AMAZON',
      periodStart: '2026-10-01',
      periodEnd: '2026-10-31',
      bookSources: ['RECEIVABLE', 'BANK_STATEMENT'],
    });
    expect(label).toContain('ACC-001');
    expect(label).toContain('亚马逊美国站');
    expect(label).toContain('2026-10-01 → 2026-10-31');
    expect(label).toContain('应收明细');
    expect(label).toContain('银行流水');
  });
});

describe('12.3.2.1 对账任务 · 词表', () => {
  it('我方账来源、平台账单侧、方向、事件类型都有中文说法', () => {
    expect(reconciliationBookSourceLabel('RECEIVABLE')).toContain('12.1.1.1');
    expect(reconciliationBookSourceLabel('PAYMENT_ORDER')).toContain('12.2.2.1');
    expect(reconciliationBookSourceLabel('BANK_STATEMENT')).toContain('12.7.1.2');
    expect(reconciliationExternalKindLabel('SETTLEMENT')).toContain('12.3.1.1');
    expect(reconciliationExternalKindLabel('SETTLEMENT_LINE')).toContain('12.3.1.2');
    expect(reconciliationDirectionLabel('IN')).toBe('收入');
    expect(reconciliationDirectionLabel('OUT')).toBe('支出');
    expect(reconciliationEventLabel('TASK_CREATED')).toBe('建立对账任务');
    expect(reconciliationEventLabel('TASK_SCOPE_UPDATED')).toBe('改动对账范围');
    expect(reconciliationEventLabel('MATCH_COMPLETED')).toBe('完成自动匹配');
  });

  it('未知取值原样透出（不吞掉后端新加的词）', () => {
    expect(reconciliationStatusLabel('WHAT_IS_THIS')).toBe('WHAT_IS_THIS');
    expect(reconciliationShapeLabel('WHAT_IS_THIS')).toBe('WHAT_IS_THIS');
    expect(reconciliationDifferenceTypeLabel('WHAT_IS_THIS')).toBe('WHAT_IS_THIS');
    expect(reconciliationEventLabel('WHAT_IS_THIS')).toBe('WHAT_IS_THIS');
  });
});
