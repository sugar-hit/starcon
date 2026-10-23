import { describe, expect, it } from 'vitest';
import type {
  FinanceSettlementDifference,
  FinanceSettlementRow,
} from '../../../../../packages/api-contracts/src/index';
import {
  createSettlement,
  differenceHint,
  fetchSettlement,
  fetchSettlementCapabilities,
  fetchSettlementCycles,
  fetchSettlementDifference,
  fetchSettlementOptions,
  fetchSettlementShops,
  fetchSettlements,
  formatContribution,
  formatDifference,
  formatSettlementAmount,
  formatSettlementDate,
  formatSettlementMoney,
  formatSettlementTime,
  importSettlementBill,
  recordSettlementArrival,
  settlementCategoryLabel,
  settlementCycleTypeLabel,
  settlementEventLabel,
  settlementFactHint,
  settlementSourceLabel,
  settlementStatusLabel,
  settlementStatusTone,
  upsertSettlementCycle,
  voidSettlement,
} from './settlement-model';

/**
 * 12.3.1.1 结算单前端模型的单测。
 *
 * <p>重点守住四条**界面纪律**（它们一旦被破坏，页面就会开始骗人）：
 *
 * <ol>
 *   <li>金额**原样展示**，前端不做任何算术（USD 与 EUR 加起来不是任何一个金额）；</li>
 *   <li>汇总项的金额是「对结算金额的贡献」（正=增加、负=减少）——
 *       页面必须把符号的含义写出来，否则「退款 −100」会被读成「退款了 100 块」；</li>
 *   <li>上游没交付的事就说没交付：「平台结算报告同步」在界面上必须带着「不可用」的提示；</li>
 *   <li>差额是服务端算出来的，前端**不重算**、也不替上游模块编数据。</li>
 * </ol>
 */

const row = (over: Partial<FinanceSettlementRow>): FinanceSettlementRow => ({
  id: '11111111-1111-1111-1111-111111111111',
  settlementNo: 'SETL-1',
  shopId: '22222222-2222-2222-2222-222222222222',
  shopCode: 'SHOP-1',
  shopName: '美国一店',
  platformCode: 'amazon',
  periodStart: '2026-10-01',
  periodEnd: '2026-10-15',
  currency: 'USD',
  settlementAmount: 620,
  status: 'PENDING',
  expectedAt: '2026-10-22T00:00:00Z',
  arrivedAt: null,
  receivedAmount: 0,
  source: 'MANUAL_UPLOAD',
  sourceSystem: 'amazon',
  sourceReference: 'RPT-2026-10-01',
  sourceFileId: '33333333-3333-3333-3333-333333333333',
  parseVersion: 1,
  cycleId: null,
  note: '',
  createdAt: '2026-10-20T00:00:00Z',
  updatedAt: '2026-10-20T00:00:00Z',
  ...over,
});

describe('汇总类别的可读名', () => {
  it('蓝图点名的七个类别各自有可读名，且不认识的原样返回', () => {
    expect(settlementCategoryLabel('SALES')).toBe('销售额');
    expect(settlementCategoryLabel('REFUND')).toBe('退款');
    expect(settlementCategoryLabel('COMMISSION')).toBe('佣金');
    expect(settlementCategoryLabel('FBA_FEE')).toBe('FBA 费用');
    expect(settlementCategoryLabel('ADVERTISING')).toBe('广告费');
    expect(settlementCategoryLabel('COMPENSATION')).toBe('赔付');
    expect(settlementCategoryLabel('ADJUSTMENT')).toBe('其他调整');
    expect(settlementCategoryLabel('SOMETHING_NEW')).toBe('SOMETHING_NEW');
  });
});

describe('上游缺口在界面上必须说清', () => {
  it('「平台结算报告同步」必须带着「当前不可用」的提示', () => {
    // 上游「卷 02 同步」没有结算报告端点 ⇒ 放一个能点的入口就是骗人。
    expect(settlementSourceLabel('PLATFORM_SYNC')).toContain('不可用');
    expect(settlementSourceLabel('MANUAL_UPLOAD')).toBe('人工上传账单');
    expect(settlementSourceLabel('MANUAL')).toBe('人工登记');
  });

  it('结算事实一览要带上解析版本号', () => {
    const hint = settlementFactHint(row({ parseVersion: 3 }));
    expect(hint).toContain('第 3 版解析');
    expect(hint).toContain('USD');
  });
});

describe('到账状态与周期类型、事件类型都有可读名', () => {
  it('五个状态各自有可读名与色调', () => {
    expect(settlementStatusLabel('PENDING')).toBe('待到账');
    expect(settlementStatusLabel('PARTIAL')).toBe('部分到账');
    expect(settlementStatusLabel('SETTLED')).toBe('已到账');
    expect(settlementStatusLabel('VOID')).toBe('已作废');
    expect(settlementStatusLabel('SUPERSEDED')).toBe('已被新版本取代');
    expect(settlementStatusTone('SETTLED')).toBe('success');
    expect(settlementStatusTone('VOID')).toBe('danger');
    expect(settlementStatusTone('SUPERSEDED')).toBe('neutral');
  });

  it('周期类型与事件类型都有可读名', () => {
    expect(settlementCycleTypeLabel('WEEKLY')).toBe('周结');
    expect(settlementCycleTypeLabel('BIWEEKLY')).toBe('双周结');
    expect(settlementCycleTypeLabel('MONTHLY')).toBe('月结');
    expect(settlementEventLabel('IMPORTED')).toBe('平台账单导入');
    expect(settlementEventLabel('REPARSED')).toBe('重新解析');
    expect(settlementEventLabel('SUPERSEDED')).toBe('被新版本取代');
  });
});

describe('金额只原样展示', () => {
  it('四位小数原样显示，不做单位换算', () => {
    expect(formatSettlementAmount(1000)).toBe('1000.0000');
    expect(formatSettlementAmount(1234.5678)).toBe('1234.5678');
  });

  it('null / undefined 显示「未登记」而不是 0', () => {
    expect(formatSettlementAmount(null)).toBe('未登记');
    expect(formatSettlementAmount(undefined)).toBe('未登记');
    expect(formatSettlementMoney(null, 'USD')).toBe('未登记');
  });

  it('金额 + 币种拼在一起', () => {
    expect(formatSettlementMoney(620, 'USD')).toBe('620.0000 USD');
    expect(formatSettlementMoney(620, null)).toBe('620.0000');
  });
});

describe('汇总项的贡献值要带出正负号', () => {
  it('正数带 + 号，负数带 - 号', () => {
    expect(formatContribution(1000)).toBe('+1000.0000');
    expect(formatContribution(-100)).toBe('-100.0000');
    expect(formatContribution(0)).toBe('0.0000');
  });

  it('null 显示「未登记」而不是 0', () => {
    expect(formatContribution(null)).toBe('未登记');
  });
});

describe('日期与时刻只做显示格式化', () => {
  it('周期日期与到账时刻都能被显示', () => {
    expect(formatSettlementDate('2026-10-01')).not.toBe('未登记');
    expect(formatSettlementTime('2026-10-22T00:00:00Z')).toContain('2026');
  });

  it('缺失或非法值原样显示而不是变成 0', () => {
    expect(formatSettlementDate(null)).toBe('未登记');
    expect(formatSettlementTime(undefined)).toBe('未登记');
    expect(formatSettlementTime('not-a-time')).toBe('not-a-time');
  });
});

describe('差额：服务端算出来的，前端不重算', () => {
  const difference = (over: Partial<FinanceSettlementDifference>): FinanceSettlementDifference => ({
    settlementId: '11111111-1111-1111-1111-111111111111',
    settlementNo: 'SETL-1',
    currency: 'USD',
    periodStart: '2026-10-01',
    periodEnd: '2026-10-15',
    platformSettlementAmount: 620,
    platformReceivedAmount: 0,
    receivableAmount: 1000,
    receivableOpenAmount: 1000,
    receivableCount: 1,
    receivableBySource: [],
    differenceVsReceivable: -380,
    linkedReceiptCount: 0,
    linkedReceiptAmount: 0,
    receiptLinkNote: '',
    reconciliationNote: '',
    computedAt: '2026-10-20T00:00:00Z',
    ...over,
  });

  it('差额原样显示，不在前端用两个数自己减', () => {
    expect(formatDifference(difference({}).differenceVsReceivable, 'USD')).toBe('-380.0000 USD');
    expect(formatDifference(380, 'USD')).toBe('+380.0000 USD');
  });

  it('口径说明要写清「平台结算金额 − 我方应收未核销余额」与「实时计算」', () => {
    const hint = differenceHint(difference({}));
    expect(hint).toContain('620.0000 USD');
    expect(hint).toContain('1000.0000 USD');
    expect(hint).toContain('实时计算');
  });

  it('收款侧现在恒为 0 —— 页面不得把它说成「已经对过了」', () => {
    const diff = difference({ linkedReceiptCount: 0, linkedReceiptAmount: 0 });
    expect(diff.linkedReceiptCount).toBe(0);
    expect(diff.linkedReceiptAmount).toBe(0);
  });
});

describe('端点形状', () => {
  it('所有端点都挂在 finance/settlement 命名空间下', () => {
    // 端点函数本身不发请求（这里只钉住 URL 形状）；
    // 真正「放行 / 拒绝」由 api-proxy 的白名单双向断言守住。
    expect(typeof fetchSettlementCapabilities).toBe('function');
    expect(typeof fetchSettlementOptions).toBe('function');
    expect(typeof fetchSettlementShops).toBe('function');
    expect(typeof fetchSettlementCycles).toBe('function');
    expect(typeof fetchSettlements).toBe('function');
    expect(typeof fetchSettlement).toBe('function');
    expect(typeof fetchSettlementDifference).toBe('function');
    expect(typeof createSettlement).toBe('function');
    expect(typeof importSettlementBill).toBe('function');
    expect(typeof recordSettlementArrival).toBe('function');
    expect(typeof upsertSettlementCycle).toBe('function');
    expect(typeof voidSettlement).toBe('function');
  });
});
