import { describe, expect, it } from 'vitest';
import type {
  DropshipLossAlertPage,
  DropshipMarginFactRow,
  DropshipMarginReport,
  DropshipMarginReportGroup,
  DropshipPayableTaskRow,
  DropshipReconciliationRow,
} from '../../../../../packages/api-contracts/src/index';
import {
  PAYABLE_PENDING_HINT,
  SENT_NOT_ACKNOWLEDGED_HINT,
  UNDETERMINED_HINT,
  alertCounters,
  basisBadge,
  groupByLabel,
  groupTotals,
  lossVerdictBadge,
  lossVerdictLabel,
  moneyText,
  payableTaskBadge,
  reconciliationBuckets,
  spreadCells,
  statementStatusLabel,
  skuGroupNote,
} from './dropship-margin-model';

/**
 * 9.6.2.1 代发差价与对账前端模型。
 *
 * <p>这些用例钉的是**界面上最容易做错的几处**，每一条都对应一个会真实亏钱的误读：
 * 把「无法判定」画成「不亏」、把算不出的差价显示成 0.00、跨币种加总、
 * 把未登记项并进合计、把「已发出」画成「已办」。
 */

function fact(over: Partial<DropshipMarginFactRow> = {}): DropshipMarginFactRow {
  return {
    id: 'f1',
    orderId: 'o1',
    orderNo: 'DSO-20260101-000001',
    salesOrderNo: 'SO-1',
    shopCode: 'SHOP-A',
    supplierCode: 'SUP-ACME',
    currencyCode: 'USD',
    businessModel: 'DROPSHIP',
    costBasis: 'PURCHASE_SPREAD',
    sellAmount: 30,
    purchaseAmount: 10,
    supplierShippingAmount: 2,
    dropshipCost: 12,
    spreadBeforeFee: 18,
    platformFeeAmount: null,
    spreadAfterFee: null,
    marginRate: null,
    feeRegistrationStatus: 'UNREGISTERED',
    lossVerdict: 'UNDETERMINED_NO_FEE',
    lossVerdictLabel: '平台费未登记，无法判定',
    lossEvidence: '平台费未登记',
    computedAt: '2026-01-02T00:00:00+08:00',
    ...over,
  } as DropshipMarginFactRow;
}

function group(over: Partial<DropshipMarginReportGroup> = {}): DropshipMarginReportGroup {
  return {
    groupKey: 'SUP-ACME',
    groupLabel: 'SUP-ACME',
    groupBy: 'SUPPLIER',
    businessModel: 'DROPSHIP',
    costBasis: 'PURCHASE_SPREAD',
    currencyCode: 'USD',
    orderCount: 1,
    sellAmount: 30,
    purchaseAmount: 10,
    supplierShippingAmount: 2,
    platformFeeAmount: 4.5,
    feeUnregisteredOrderCount: 0,
    dropshipCost: 12,
    spreadBeforeFee: 18,
    spreadAfterFee: 13.5,
    spreadBeforeFeeSum: 18,
    undeterminedOrderCount: 0,
    lossConfirmedOrderCount: 0,
    notLossOrderCount: 1,
    marginRate: 0.45,
    note: '',
    ...over,
  } as DropshipMarginReportGroup;
}

function report(groups: DropshipMarginReportGroup[]): DropshipMarginReport {
  return {
    groupBy: 'SUPPLIER',
    businessModel: 'DROPSHIP',
    costBasis: 'PURCHASE_SPREAD',
    periodStart: '2026-01-01T00:00:00+08:00',
    periodEnd: '2027-01-01T00:00:00+08:00',
    currencyCode: '按币种分组',
    totalOrderCount: groups.length,
    groupCount: groups.length,
    groups,
    basisNote: '代发模式的成本是采购差价，不是自有库存成本',
    feeNote: '未登记不等于 0',
    currencyNote: '跨币种不相加',
  } as DropshipMarginReport;
}

function statement(over: Partial<DropshipReconciliationRow> = {}): DropshipReconciliationRow {
  return {
    id: 's1',
    statementNo: 'DRC-20260101-000001',
    supplierCode: 'SUP-ACME',
    periodStart: '2026-01-01T00:00:00+08:00',
    periodEnd: '2027-01-01T00:00:00+08:00',
    cycleKind: 'MONTHLY',
    currencyCode: 'USD',
    businessModel: 'DROPSHIP',
    costBasis: 'PURCHASE_SPREAD',
    status: 'DRAFT',
    orderCount: 3,
    lossOrderCount: 1,
    totalSellAmount: 90,
    totalPurchaseAmount: 30,
    totalSupplierShipping: 6,
    totalPlatformFee: 0,
    totalDropshipCost: 36,
    totalSpread: 54,
    totalPayable: 36,
    undeterminedOrderCount: 1,
    undeterminedPayable: 12,
    feeUnregisteredOrderCount: 2,
    fxStatus: 'UNSET',
    fxRateValue: null,
    ruleEvidence: '应付供应商 = 采购结算价 + 供应商运费',
    basisNote: '代发口径',
    disputeNote: '',
    finalizedAt: null,
    closedAt: null,
    createdAt: '2026-01-02T00:00:00+08:00',
    version: 1,
    ...over,
  } as DropshipReconciliationRow;
}

function task(over: Partial<DropshipPayableTaskRow> = {}): DropshipPayableTaskRow {
  return {
    id: 't1',
    taskNo: 'DPT-1',
    taskKind: 'SUPPLIER_PAYABLE',
    targetSystem: 'FINANCE',
    taskStatus: 'OPEN',
    taskStatusLabel: '待领取',
    statementId: 's1',
    statementNo: 'DRC-1',
    supplierCode: 'SUP-ACME',
    payableAmount: 36,
    payableCurrencyCode: 'USD',
    payload: null,
    idempotencyKey: 'SUPPLIER_PAYABLE:s1',
    attemptCount: 0,
    lastAttemptAt: null,
    lastError: '',
    claimedAt: null,
    sentAt: null,
    acknowledgedAt: null,
    createdAt: '2026-01-02T00:00:00+08:00',
    ...over,
  } as unknown as DropshipPayableTaskRow;
}

// ==========================================================================

describe('亏损结论三态', () => {
  it('「无法判定」刻意不是警告色调 —— 它不是一个已确认的问题', () => {
    const badge = lossVerdictBadge('UNDETERMINED_NO_FEE');
    expect(badge.tone).toBe('unknown');
    expect(badge.tone).not.toBe('caution');
    expect(badge.label).toBe('无法判定');
    expect(badge.hint).toContain('不等于不亏');
    expect(badge.hint).toContain('不按 0 算');
  });

  it('确认亏损是 danger，不亏是 ok —— 三态三种色调', () => {
    expect(lossVerdictBadge('LOSS_CONFIRMED').tone).toBe('danger');
    expect(lossVerdictBadge('NOT_LOSS').tone).toBe('ok');
    const tones = [
      lossVerdictBadge('LOSS_CONFIRMED').tone,
      lossVerdictBadge('NOT_LOSS').tone,
      lossVerdictBadge('UNDETERMINED_NO_FEE').tone,
    ];
    expect(new Set(tones).size).toBe(3);
  });

  it('中文标签三态互不相同', () => {
    const labels = ['LOSS_CONFIRMED', 'NOT_LOSS', 'UNDETERMINED_NO_FEE'].map(lossVerdictLabel);
    expect(new Set(labels).size).toBe(3);
  });
});

describe('差价单元格：算不出来不等于 0', () => {
  it('平台费未登记时含费差价是 null，界面必须能画成「—」', () => {
    const cells = spreadCells(fact());
    expect(cells.spreadAfterFee).toBeNull();
    expect(cells.feeUnregistered).toBe(true);
    expect(cells.spreadBeforeFee).toBe(18);
    expect(cells.note).toContain(UNDETERMINED_HINT);
  });

  it('null 绝不能被 moneyText 格式化成一个假的 0.00', () => {
    const cells = spreadCells(fact());
    expect(moneyText(cells.spreadAfterFee, 'USD')).toBe('—');
    expect(moneyText(cells.spreadAfterFee, 'USD')).not.toContain('0.00');
  });

  it('已登记费率时含费差价有值，且等于售价 − 采购 − 运费 − 平台费', () => {
    const cells = spreadCells(
      fact({
        feeRegistrationStatus: 'REGISTERED',
        platformFeeAmount: 4.5,
        spreadAfterFee: 13.5,
        lossVerdict: 'NOT_LOSS',
      }),
    );
    expect(cells.spreadAfterFee).toBe(13.5);
    expect(cells.feeUnregistered).toBe(false);
    expect(cells.spreadAfterFee).toBe(
      30 - (10 + 2) - 4.5,
    );
    expect(cells.note).toContain('已按登记费率');
  });

  it('已登记费率但为 0 时，仍算「已判定」而不是「无法判定」', () => {
    const cells = spreadCells(
      fact({
        feeRegistrationStatus: 'REGISTERED',
        platformFeeAmount: 0,
        spreadAfterFee: 18,
        lossVerdict: 'NOT_LOSS',
      }),
    );
    expect(cells.feeUnregistered).toBe(false);
    expect(cells.spreadAfterFee).toBe(18);
  });

  it('金额一律带币种展示', () => {
    expect(moneyText(13.5, 'USD')).toContain('USD');
    expect(moneyText(0, 'CNY')).toContain('CNY');
    expect(moneyText(undefined)).toBe('—');
    expect(moneyText(null)).toBe('—');
  });
});

describe('口径隔离：代发不是自营', () => {
  it('逐行标注「代发 · 采购差价口径」', () => {
    expect(basisBadge({ businessModel: 'DROPSHIP', costBasis: 'PURCHASE_SPREAD' })).toBe(
      '代发 · 采购差价口径',
    );
  });

  it('口径不匹配时如实显示后端值，不硬编码成代发', () => {
    expect(basisBadge({ businessModel: 'SELF_OP', costBasis: 'INVENTORY_COST' })).toBe(
      'SELF_OP · INVENTORY_COST',
    );
  });
});

describe('报表：跨币种不相加', () => {
  it('USD 与 CNY 分成两条，而不是加成一个假数', () => {
    const totals = groupTotals(report([group(), group({ groupKey: 'SUP-B', currencyCode: 'CNY', sellAmount: 50 })]));
    expect(totals).toHaveLength(2);
    expect(totals.map((t) => t.currencyCode)).toEqual(['CNY', 'USD']);
    const usd = totals.find((t) => t.currencyCode === 'USD');
    const cny = totals.find((t) => t.currencyCode === 'CNY');
    expect(usd?.sellAmount).toBe(30);
    expect(cny?.sellAmount).toBe(50);
  });

  it('同币种的多行才相加', () => {
    const totals = groupTotals(report([group(), group({ groupKey: 'SUP-B', sellAmount: 20 })]));
    expect(totals).toHaveLength(1);
    expect(totals[0]?.sellAmount).toBe(50);
    expect(totals[0]?.orderCount).toBe(2);
    expect(totals[0]?.spreadAfterFee).toBeCloseTo(27, 6);
  });

  it('只要有一行含费差价算不出来，整组就不给数', () => {
    const totals = groupTotals(
      report([group({ spreadAfterFee: null, undeterminedOrderCount: 1 })]),
    );
    expect(totals[0]?.spreadAfterFee).toBeNull();
    expect(totals[0]?.undeterminedOrderCount).toBe(1);
    // 不含平台费的差价仍然有值 —— 那一支永远可复算
    expect(totals[0]?.spreadBeforeFee).toBe(18);
  });

  it('分组维度四选一且标签互不相同', () => {
    const labels = ['SHOP', 'SUPPLIER', 'SKU', 'ORDER'].map(groupByLabel);
    expect(new Set(labels).size).toBe(4);
  });

  it('SKU 维度不摊运费时必须给出原因，否则会被当成漏算', () => {
    expect(skuGroupNote(group({ groupBy: 'SKU', note: '' }))).toContain('假设分摊规则');
    expect(skuGroupNote(group({ groupBy: 'SKU', note: '自定义原因' }))).toBe('自定义原因');
  });
});

describe('对账：三个桶互不吞并', () => {
  it('汇率未登记的应付不并进合计', () => {
    const buckets = reconciliationBuckets(statement());
    expect(buckets.included.orderCount).toBe(3);
    expect(buckets.included.payableText).toContain('USD');
    expect(buckets.fxUnset.orderCount).toBe(1);
    expect(buckets.fxUnset.payable).toBe(12);
    expect(buckets.fxUnset.note).toContain('不并入上方合计');
  });

  it('平台费未登记时合计里的平台费被明确标为不完整', () => {
    const buckets = reconciliationBuckets(statement());
    expect(buckets.feeUnregistered.orderCount).toBe(2);
    expect(buckets.feeUnregistered.note).toContain('未登记不等于 0');
    // 合计里的平台费是 0，但**不能**因此被读成「这批单的平台费真的是 0」
    expect(buckets.included.feeText).toContain('USD');
    expect(buckets.feeUnregistered.note).toContain('2 行');
  });

  it('全部已登记时两个未登记桶如实说「没有」', () => {
    const buckets = reconciliationBuckets(
      statement({ undeterminedOrderCount: 0, feeUnregisteredOrderCount: 0, totalPlatformFee: 13.5 }),
    );
    expect(buckets.fxUnset.note).toContain('没有未登记桶');
    expect(buckets.feeUnregistered.note).toContain('都已登记');
  });

  it('对账逐行带代发口径', () => {
    expect(basisBadge(statement())).toBe('代发 · 采购差价口径');
  });

  it('状态标签四态互不相同', () => {
    const labels = ['DRAFT', 'FINALIZED', 'DISPUTED', 'CLOSED'].map(statementStatusLabel);
    expect(new Set(labels).size).toBe(4);
  });
});

describe('应付待办：已发出 ≠ 已受理', () => {
  it('OPEN 显示「待应付（12.1.2.1 未交付）」而不是「已生成应付单」', () => {
    const badge = payableTaskBadge(task());
    expect(badge.tone).toBe('pending');
    expect(badge.label).toBe(PAYABLE_PENDING_HINT);
    expect(badge.label).toContain('12.1.2.1 未交付');
    expect(badge.label).not.toContain('已生成');
  });

  it('SENT 是「已交给下游」，且明确提示尚未受理', () => {
    const badge = payableTaskBadge(task({ taskStatus: 'SENT', sentAt: '2026-01-02T00:00:00+08:00' }));
    expect(badge.tone).toBe('progress');
    expect(badge.hint).toContain(SENT_NOT_ACKNOWLEDGED_HINT);
  });

  it('只有 ACKNOWLEDGED 才是完成态', () => {
    const badge = payableTaskBadge(
      task({ taskStatus: 'ACKNOWLEDGED', acknowledgedAt: '2026-01-03T00:00:00+08:00' }),
    );
    expect(badge.tone).toBe('done');
    expect(badge.label).toBe('下游已受理');
  });

  it('SENT 与 ACKNOWLEDGED 的色调不同 —— 两者不是同一件事', () => {
    expect(payableTaskBadge(task({ taskStatus: 'SENT' })).tone).not.toBe(
      payableTaskBadge(task({ taskStatus: 'ACKNOWLEDGED' })).tone,
    );
  });

  it('FAILED 必须带失败原因', () => {
    expect(payableTaskBadge(task({ taskStatus: 'FAILED', lastError: '' })).tone).toBe('failed');
    expect(payableTaskBadge(task({ taskStatus: 'FAILED', lastError: '下游 500' })).hint).toBe(
      '下游 500',
    );
  });
});

describe('预警工作台两个计数', () => {
  const page = {
    items: [],
    total: 0,
    lossConfirmedTotal: 3,
    undeterminedTotal: 5,
    page: 0,
    size: 20,
  } as unknown as DropshipLossAlertPage;

  it('确认亏损与无法判定分开计数', () => {
    const counters = alertCounters(page);
    expect(counters.lossConfirmed).toBe(3);
    expect(counters.undetermined).toBe(5);
  });

  it('note 明写「不是不亏，真实亏损可能藏在里面」', () => {
    expect(alertCounters(page).note).toContain('不是「不亏」');
    expect(alertCounters(page).note).toContain('5');
  });
});
