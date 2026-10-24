import { describe, expect, it } from 'vitest';
import {
  DEFAULT_DUE_HORIZON_DAYS,
  INVENTORY_VALUE_NOTE,
  RISK_SOURCE_KEYS,
  RISK_SOURCE_SPECS,
  buildAgingCurrencies,
  buildCurrencySummaries,
  buildInTransitGoods,
  buildInTransitMoney,
  buildOverdueView,
  buildPendingSettlement,
  buildUpcomingPayables,
  canRead,
  daysBetween,
  describeAging,
  describeInTransitGoods,
  describeOverdue,
  describePayable,
  formatMoney,
  formatQuantity,
  formatRatio,
  fromScaled,
  ratioOf,
  scopeModeOf,
  sourceAvailabilities,
  sumMoney,
  sumMoneyOrNull,
  toScaled,
  type FinanceAgingBucketAmount,
  type FinanceAgingCurrencyTotal,
  type FinanceDeferredSettlementBasisNote,
  type FinanceDeferredSettlementCurrencySummary,
  type FinanceDeferredSettlementSummary,
  type FinancePayableCurrencySummary,
  type FinancePayableUnpaidItem,
  type FinancePayableUnpaidList,
  type FinanceReceivableAgingDistribution,
  type FinanceReceivableAgingOverduePage,
  type FinanceReceivableAgingOverdueRow,
  type FinanceSettlementCurrencySummary,
  type FinanceSettlementPage,
  type RiskProjectionPage,
  type RiskProjectionRow,
  type SessionGrant,
} from './fund-payment-risk-model';

/**
 * 1.1.3.2 资金与回款风险的前端聚合模型单测。
 *
 * <p>重点覆盖四件本模块最容易做错的事：
 *
 * <ol>
 *   <li><b>多币种绝不相加</b> —— 概览里不出现跨币种总额；
 *   <li><b>「不可判定」≠ 0</b> —— 分母为 0、来源没登记、字段缺失一律 null；
 *   <li><b>精确十进制</b> —— 占比与合计不碰浮点；
 *   <li><b>计数与清单同源</b> —— 概览的数由清单同一份集合算出。
 * </ol>
 */

// ---------------------------------------------------------------- 造数据

const BUCKETS = [
  { code: 'NOT_DUE', label: '未到期', overdueFromDays: null, overdueToDays: null, sortOrder: 1 },
  { code: 'D1_30', label: '1-30 天', overdueFromDays: 1, overdueToDays: 30, sortOrder: 2 },
  { code: 'D31_60', label: '31-60 天', overdueFromDays: 31, overdueToDays: 60, sortOrder: 3 },
];

function bucketAmount(code: string, label: string, amount: number, count: number): FinanceAgingBucketAmount {
  return { code, label, amount, count };
}

function currencyTotal(
  overrides: Partial<FinanceAgingCurrencyTotal> & { currency: string },
): FinanceAgingCurrencyTotal {
  return {
    count: 3,
    openAmount: 1000,
    overdueAmount: 300,
    disputeAmount: 100,
    disputeOverdueAmount: 50,
    overdueShareTop: 0.5,
    topDimensionKey: 'SHOP-1',
    topDimensionName: '店铺一',
    buckets: [],
    ...overrides,
  };
}

function distribution(
  totals: FinanceAgingCurrencyTotal[],
  overrides: Partial<FinanceReceivableAgingDistribution> = {},
): FinanceReceivableAgingDistribution {
  return {
    asOfDate: '2026-10-24',
    bucketSource: 'BLUEPRINT_DEFAULT',
    buckets: BUCKETS,
    dimension: 'CURRENCY',
    totalCount: 3,
    rows: [],
    totals,
    ...overrides,
  };
}

function overdueRow(
  overrides: Partial<FinanceReceivableAgingOverdueRow> & { id: string },
): FinanceReceivableAgingOverdueRow {
  return {
    receivableNo: 'AR-001',
    currency: 'USD',
    counterpartyType: 'PLATFORM',
    counterpartyRef: null,
    counterpartyName: 'Amazon',
    shopId: 'shop-1',
    shopName: '美国店',
    dueDate: '2026-10-01',
    overdueDays: 23,
    bucketCode: 'D1_30',
    bucketLabel: '1-30 天',
    amount: 500,
    settledAmount: 100,
    openAmount: 400,
    disputeFlag: false,
    disputeNote: '',
    source: 'PLATFORM',
    ...overrides,
  };
}

function overduePage(
  items: FinanceReceivableAgingOverdueRow[],
  overrides: Partial<FinanceReceivableAgingOverduePage> = {},
): FinanceReceivableAgingOverduePage {
  return {
    items,
    page: 0,
    size: 50,
    total: items.length,
    asOfDate: '2026-10-24',
    summaryByCurrency: [],
    disputeCount: items.filter((item) => item.disputeFlag).length,
    ...overrides,
  };
}

function settlementPage(
  summaryByCurrency: FinanceSettlementCurrencySummary[],
): FinanceSettlementPage {
  return { items: [], page: 0, size: 50, total: 0, summaryByCurrency };
}

function payableItem(
  overrides: Partial<FinancePayableUnpaidItem> & { payableId: string },
): FinancePayableUnpaidItem {
  return {
    payableNo: 'AP-001',
    source: 'PURCHASE_RECEIPT',
    counterpartyType: 'SUPPLIER',
    counterpartyName: '深圳工厂',
    currency: 'USD',
    amount: 1000,
    paidAmount: 0,
    unpaidAmount: 1000,
    dueDate: '2026-10-26',
    daysOverdue: 0,
    status: 'OPEN',
    ...overrides,
  };
}

function payableSummary(
  overrides: Partial<FinancePayableCurrencySummary> & { currency: string },
): FinancePayableCurrencySummary {
  return {
    amount: 1000,
    paidAmount: 0,
    unpaidAmount: 1000,
    count: 1,
    overdueCount: 0,
    overdueAmount: 0,
    ...overrides,
  };
}

function unpaidList(
  items: FinancePayableUnpaidItem[],
  summaryByCurrency: FinancePayableCurrencySummary[] = [],
): FinancePayableUnpaidList {
  return {
    asOfDate: '2026-10-24',
    summaryByCurrency,
    items,
    itemCount: items.length,
  };
}

const BASIS_NOTE: FinanceDeferredSettlementBasisNote = {
  orderBasis: '平台订单总额（毛额）',
  settledBasis: '已到账结算单里的销售额行合计',
  inTransitBasis: 'max(订单口径 − 已结算口径, 0)',
  basisDifference: '订单口径 − 已结算口径 − 在途未结算',
  currencyPolicy: '按币种分组，USD 与 EUR 不相加',
};

function transitCurrency(
  overrides: Partial<FinanceDeferredSettlementCurrencySummary> & { currency: string },
): FinanceDeferredSettlementCurrencySummary {
  return {
    orderBasisAmount: 1000,
    settledBasisAmount: 400,
    inTransitAmount: 600,
    basisGapAmount: 0,
    count: 5,
    pendingCount: 3,
    settledCount: 2,
    overdueCount: 1,
    ...overrides,
  };
}

function transitSummary(
  summaryByCurrency: FinanceDeferredSettlementCurrencySummary[],
): FinanceDeferredSettlementSummary {
  return {
    summaryByCurrency,
    basis: BASIS_NOTE,
    earliestExpectedArrival: null,
    daysToEarliestExpectedArrival: 0,
    computedAt: '2026-10-24T00:00:00Z',
  };
}

function projectionRow(overrides: Partial<RiskProjectionRow> = {}): RiskProjectionRow {
  return {
    warehouseId: 'w-1',
    warehouseCode: 'WH-LOCAL',
    warehouseName: '本地仓',
    warehouseKind: 'LOCAL',
    warehouseKindLabel: '本地仓',
    skuId: 's-1',
    skuCode: 'SKU-001',
    skuName: '测试商品',
    onHand: 100,
    reserved: 0,
    available: 100,
    sellableBeforeBuffer: 100,
    bucketCount: 1,
    locationCount: 1,
    lotCount: 0,
    inboundDate: '2026-01-01',
    agingDays: 10,
    agingBasis: 'LOT_RECEIVED_ON_OR_BUCKET_CREATED_AT',
    lastMovementAt: '2026-10-20T00:00:00Z',
    minOnHand: null,
    maxOnHand: null,
    reorderPoint: null,
    safetyStock: null,
    thresholdScopeType: 'NONE',
    thresholdScopeKey: '',
    obsoleteDays: 90,
    obsoleteThresholdSource: 'DEFAULT',
    activeReservedQuantity: 0,
    activeReservationCount: 0,
    inTransitQuantity: 10,
    inTransitFirstMileQuantity: 4,
    inTransitPurchaseQuantity: 5,
    inTransitTransferQuantity: 1,
    inTransitLegCount: 2,
    ...overrides,
  };
}

function projectionPage(
  rows: RiskProjectionRow[],
  overrides: Partial<RiskProjectionPage> = {},
): RiskProjectionPage {
  return {
    rows,
    total: rows.length,
    page: 0,
    size: 500,
    asOfDate: '2026-10-24',
    computedAt: '2026-10-24T00:00:00Z',
    warehouseKindOptions: [],
    warehouseOptions: [],
    truncationNote: '',
    ...overrides,
  };
}

function grant(overrides: Partial<SessionGrant> = {}): SessionGrant {
  return {
    roleId: 'r-1',
    roleCode: 'FINANCE_VIEWER',
    scopeMode: 'ALL',
    permissions: ['finance.receivable.read'],
    ...overrides,
  };
}

// ---------------------------------------------------------------- 精确十进制

describe('1.1.3.2 精确十进制', () => {
  it('缩放整数往返不丢精度（0.3 在 scale 6 上就是 300000）', () => {
    expect(toScaled(0.3, 6)).toBe(300000n);
    expect(fromScaled(300000n, 6)).toBe(0.3);
  });

  it('0.3 ÷ 0.1 得 1 而不是 0.9999999999999999', () => {
    // 浮点：0.3 / 0.1 === 0.9999999999999999；缩放整数：300000 / 100000 = 3
    expect(0.3 / 0.1).not.toBe(1);
    const a = toScaled(0.3, 6);
    const b = toScaled(0.1, 6);
    expect(a).not.toBeNull();
    expect(b).not.toBeNull();
    expect(Number(a! / b!)).toBe(3);
  });

  it('金额求和不碰浮点（0.1 + 0.2 = 0.3 而不是 0.30000000000000004）', () => {
    expect(0.1 + 0.2).not.toBe(0.3);
    expect(sumMoney([0.1, 0.2])).toBe(0.3);
  });

  it('null / undefined / 非数字转成 null（「没有这个数」）', () => {
    expect(toScaled(null, 4)).toBeNull();
    expect(toScaled(undefined, 4)).toBeNull();
    expect(toScaled(Number.NaN, 4)).toBeNull();
    expect(toScaled(Number.POSITIVE_INFINITY, 4)).toBeNull();
  });

  it('sumMoneyOrNull：全空返回 null（一项都取不到 ≠ 0）', () => {
    expect(sumMoneyOrNull([])).toBeNull();
    expect(sumMoneyOrNull([null, undefined])).toBeNull();
    expect(sumMoneyOrNull([null, 10])).toBe(10);
  });

  it('sumMoney：全空当 0（累加口径，非「可判定」口径）', () => {
    expect(sumMoney([])).toBe(0);
    expect(sumMoney([null, undefined])).toBe(0);
  });

  it('占比：分母为 0 ⇒ null（不可判定，不是 0%）', () => {
    expect(ratioOf(100, 0)).toBeNull();
    expect(ratioOf(100, null)).toBeNull();
    expect(ratioOf(null, 100)).toBeNull();
    expect(ratioOf(0, 100)).toBe(0);
  });

  it('占比按同币种口径算，四舍五入到 4 位', () => {
    expect(ratioOf(300, 1000)).toBe(0.3);
    expect(ratioOf(1, 3)).toBe(0.3333);
    expect(ratioOf(2, 3)).toBe(0.6667);
  });

  it('格式化：null 一律显示「—」', () => {
    expect(formatMoney(null)).toBe('—');
    expect(formatQuantity(null)).toBe('—');
    expect(formatRatio(null)).toBe('—');
    expect(formatMoney(1234.5)).toBe('1,234.5');
  });

  it('日期差：解析不了返回 null', () => {
    expect(daysBetween('2026-10-24', '2026-10-26')).toBe(2);
    expect(daysBetween('2026-10-24', '2026-10-20')).toBe(-4);
    expect(daysBetween('不是日期', '2026-10-20')).toBeNull();
  });
});

// ---------------------------------------------------------------- 来源声明

describe('1.1.3.2 上游来源声明', () => {
  it('六个来源逐个绑定一个只读端点与一个权限码', () => {
    expect(RISK_SOURCE_KEYS).toHaveLength(6);
    for (const key of RISK_SOURCE_KEYS) {
      const spec = RISK_SOURCE_SPECS[key];
      expect(spec.endpoint).toMatch(/^(finance|warehouse)\//);
      expect(spec.permission).toMatch(/\.read$/);
      expect(spec.owner.length).toBeGreaterThan(0);
      expect(spec.impact.length).toBeGreaterThan(0);
    }
  });

  it('六个端点都在 api-proxy 白名单里已放行的形状内', () => {
    expect(RISK_SOURCE_SPECS.AGING.endpoint).toBe('finance/receivable-aging/distribution');
    expect(RISK_SOURCE_SPECS.OVERDUE.endpoint).toBe('finance/receivable-aging/overdue');
    expect(RISK_SOURCE_SPECS.SETTLEMENT.endpoint).toBe('finance/settlement');
    expect(RISK_SOURCE_SPECS.PAYABLE.endpoint).toBe('finance/payables/unpaid');
    expect(RISK_SOURCE_SPECS.DEFERRED.endpoint).toBe('finance/deferred-settlement/summary');
    expect(RISK_SOURCE_SPECS.INVENTORY.endpoint).toBe('warehouse/inventory-risk/projection');
  });

  it('canRead：没有授权就是读不了', () => {
    expect(canRead(null, 'finance.receivable.read', false)).toBe(false);
    expect(canRead([], 'finance.receivable.read', false)).toBe(false);
    expect(canRead([grant()], 'finance.receivable.read', false)).toBe(true);
  });

  it('canRead：DEPARTMENT 没有部门时退化（读不了）', () => {
    const scoped = [grant({ scopeMode: 'DEPARTMENT' })];
    expect(canRead(scoped, 'finance.receivable.read', false)).toBe(false);
    expect(canRead(scoped, 'finance.receivable.read', true)).toBe(true);
  });

  it('scopeModeOf：取最宽的一条授权（ALL > DEPARTMENT > SELF）', () => {
    const grants = [
      grant({ scopeMode: 'SELF' }),
      grant({ scopeMode: 'DEPARTMENT' }),
      grant({ scopeMode: 'ALL' }),
    ];
    expect(scopeModeOf(grants, 'finance.receivable.read', true)).toBe('ALL');
    expect(scopeModeOf([grant({ scopeMode: 'SELF' })], 'finance.receivable.read', true)).toBe(
      'SELF',
    );
    expect(
      scopeModeOf([grant({ scopeMode: 'DEPARTMENT' })], 'finance.receivable.read', false),
    ).toBe('SELF');
  });

  it('sourceAvailabilities：逐来源判定，权限码不同结果不同', () => {
    const availability = sourceAvailabilities({
      userId: 'u-1',
      tenantId: 't-1',
      tenantName: '租户',
      membershipId: 'm-1',
      departmentId: null,
      permissions: [],
      grants: [grant()],
    });
    expect(availability.AGING.permitted).toBe(true);
    expect(availability.OVERDUE.permitted).toBe(true);
    expect(availability.SETTLEMENT.permitted).toBe(false);
    expect(availability.PAYABLE.permitted).toBe(false);
    expect(availability.DEFERRED.permitted).toBe(false);
    expect(availability.INVENTORY.permitted).toBe(false);
  });
});

// ---------------------------------------------------------------- 账龄分布

describe('1.1.3.2 应收账龄分布', () => {
  it('按币种分组，不产出跨币种合计', () => {
    const currencies = buildAgingCurrencies(
      distribution([
        currencyTotal({
          currency: 'USD',
          openAmount: 1000,
          buckets: [bucketAmount('NOT_DUE', '未到期', 700, 2)],
        }),
        currencyTotal({
          currency: 'EUR',
          openAmount: 500,
          buckets: [bucketAmount('NOT_DUE', '未到期', 500, 1)],
        }),
      ]),
    );
    expect(currencies.map((item) => item.currency)).toEqual(['USD', 'EUR']);
    expect(currencies).toHaveLength(2);
  });

  it('缺的档位补 0（服务端只对有数据的档位发行 ⇒ 缺档就是「这一档没有应收」）', () => {
    const [usd] = buildAgingCurrencies(
      distribution([
        currencyTotal({
          currency: 'USD',
          openAmount: 1000,
          buckets: [bucketAmount('NOT_DUE', '未到期', 700, 2)],
        }),
      ]),
    );
    expect(usd?.buckets).toHaveLength(3);
    expect(usd?.buckets[0]?.amount).toBe(700);
    expect(usd?.buckets[1]?.amount).toBe(0);
    expect(usd?.buckets[1]?.count).toBe(0);
    expect(usd?.buckets[1]?.bucketLabel).toBe('1-30 天');
  });

  it('档位占比按同币种未核销额算；分母为 0 ⇒ null（不可判定，不是 0%）', () => {
    const [usd] = buildAgingCurrencies(
      distribution([
        currencyTotal({
          currency: 'USD',
          openAmount: 1000,
          buckets: [bucketAmount('NOT_DUE', '未到期', 300, 1)],
        }),
      ]),
    );
    expect(usd?.buckets[0]?.share).toBe(0.3);
    const [empty] = buildAgingCurrencies(
      distribution([
        currencyTotal({
          currency: 'USD',
          openAmount: 0,
          buckets: [],
        }),
      ]),
    );
    expect(empty?.buckets[0]?.share).toBeNull();
  });

  it('分布读不到时返回空数组（不是「零逾期」）', () => {
    expect(buildAgingCurrencies(null)).toEqual([]);
  });

  it('口径说明里带得出档位金额与占比', () => {
    const [usd] = buildAgingCurrencies(
      distribution([
        currencyTotal({
          currency: 'USD',
          openAmount: 1000,
          overdueAmount: 300,
          buckets: [bucketAmount('NOT_DUE', '未到期', 300, 1)],
        }),
      ]),
    );
    const lines = describeAging(usd!);
    expect(lines.some((line) => line.includes('未核销应收'))).toBe(true);
    expect(lines.some((line) => line.includes('30.0%'))).toBe(true);
  });
});

// ---------------------------------------------------------------- 逾期应收

describe('1.1.3.2 逾期应收清单', () => {
  it('按逾期天数降序排，同逾期按币种与单号稳定排', () => {
    const view = buildOverdueView(
      overduePage([
        overdueRow({ id: 'a', receivableNo: 'AR-001', overdueDays: 5 }),
        overdueRow({ id: 'b', receivableNo: 'AR-002', overdueDays: 40 }),
        overdueRow({ id: 'c', receivableNo: 'AR-003', overdueDays: 5 }),
      ]),
    );
    expect(view.rows.map((row) => row.id)).toEqual(['b', 'a', 'c']);
  });

  it('分页取回少于总数时如实标 truncated', () => {
    const view = buildOverdueView(
      overduePage([overdueRow({ id: 'a' }), overdueRow({ id: 'b' })], { total: 10 }),
    );
    expect(view.truncated).toBe(true);
    expect(view.fetchedCount).toBe(2);
    expect(view.total).toBe(10);
  });

  it('争议中的单据带出争议说明', () => {
    const view = buildOverdueView(
      overduePage([
        overdueRow({ id: 'a', disputeFlag: true, disputeNote: '平台申诉中' }),
      ]),
    );
    const lines = describeOverdue(view.rows[0]!);
    expect(lines.some((line) => line.includes('平台申诉中'))).toBe(true);
  });

  it('每行都带一个跳到 12 财务的出口', () => {
    const view = buildOverdueView(overduePage([overdueRow({ id: 'a' })]));
    expect(view.rows[0]?.href).toContain('/finance/aging');
  });

  it('读不到时返回空视图（不是「没有逾期」）', () => {
    const view = buildOverdueView(null);
    expect(view.rows).toEqual([]);
    expect(view.total).toBe(0);
  });
});

// ---------------------------------------------------------------- 平台待结算

describe('1.1.3.2 平台待结算', () => {
  it('待到账与部分到账两档按币种合并', () => {
    const merged = buildPendingSettlement(
      settlementPage([
        { currency: 'USD', settlementAmount: 1000, receivedAmount: 0, count: 2, pendingCount: 2 },
      ]),
      settlementPage([
        { currency: 'USD', settlementAmount: 500, receivedAmount: 200, count: 1, pendingCount: 1 },
      ]),
    );
    expect(merged).toHaveLength(1);
    expect(merged[0]?.currency).toBe('USD');
    expect(merged[0]?.settlementAmount).toBe(1500);
    expect(merged[0]?.receivedAmount).toBe(200);
    expect(merged[0]?.pendingCount).toBe(2);
    expect(merged[0]?.partialCount).toBe(1);
    expect(merged[0]?.count).toBe(3);
  });

  it('未到账金额 = 结算金额 − 已到账金额（精确十进制）', () => {
    const merged = buildPendingSettlement(
      settlementPage([
        { currency: 'USD', settlementAmount: 0.1, receivedAmount: 0, count: 1, pendingCount: 1 },
      ]),
      settlementPage([
        { currency: 'USD', settlementAmount: 0.2, receivedAmount: 0, count: 1, pendingCount: 1 },
      ]),
    );
    expect(merged[0]?.unsettledAmount).toBe(0.3);
  });

  it('不同币种分成两行，绝不相加', () => {
    const merged = buildPendingSettlement(
      settlementPage([
        { currency: 'USD', settlementAmount: 1000, receivedAmount: 0, count: 1, pendingCount: 1 },
      ]),
      settlementPage([
        { currency: 'EUR', settlementAmount: 2000, receivedAmount: 0, count: 1, pendingCount: 1 },
      ]),
    );
    expect(merged.map((item) => item.currency)).toEqual(['EUR', 'USD']);
    expect(merged).toHaveLength(2);
  });
});

// ---------------------------------------------------------------- 即将到期的供应商应付

describe('1.1.3.2 即将到期的供应商应付', () => {
  it('落在窗口内的进集合，窗口外的排除', () => {
    const view = buildUpcomingPayables(
      unpaidList([
        payableItem({ payableId: 'a', dueDate: '2026-10-26', daysOverdue: 0 }),
        payableItem({ payableId: 'b', dueDate: '2026-11-30', daysOverdue: 0 }),
      ]),
    );
    expect(view.rows.map((row) => row.payableId)).toEqual(['a']);
    expect(view.dueSoonCount).toBe(1);
  });

  it('已逾期的恒计入，不受窗口影响', () => {
    const view = buildUpcomingPayables(
      unpaidList([payableItem({ payableId: 'a', dueDate: '2026-01-01', daysOverdue: 200 })]),
    );
    expect(view.rows).toHaveLength(1);
    expect(view.rows[0]?.urgency).toBe('OVERDUE');
    expect(view.overdueCount).toBe(1);
    expect(view.dueSoonCount).toBe(0);
  });

  it('默认只取供应商应付（蓝图的「供应商应付」）', () => {
    const view = buildUpcomingPayables(
      unpaidList([
        payableItem({ payableId: 'a', counterpartyType: 'SUPPLIER' }),
        payableItem({ payableId: 'b', counterpartyType: 'CARRIER' }),
        payableItem({ payableId: 'c', counterpartyType: 'PLATFORM' }),
      ]),
    );
    expect(view.rows.map((row) => row.payableId)).toEqual(['a']);
  });

  it('窗口可调（默认 7 天）', () => {
    // 基准日 2026-10-24 ⇒ 2026-11-05 是 12 天后：7 天窗口装不下，30 天窗口装得下。
    const list = unpaidList([payableItem({ payableId: 'a', dueDate: '2026-11-05', daysOverdue: 0 })]);
    expect(DEFAULT_DUE_HORIZON_DAYS).toBe(7);
    expect(buildUpcomingPayables(list, 7).rows).toHaveLength(0);
    expect(buildUpcomingPayables(list, 30).rows).toHaveLength(1);
  });

  it('计数与清单同源：按币种未付余额由本页这份集合算出', () => {
    const view = buildUpcomingPayables(
      unpaidList([
        payableItem({ payableId: 'a', currency: 'USD', unpaidAmount: 100 }),
        payableItem({ payableId: 'b', currency: 'USD', unpaidAmount: 250 }),
        payableItem({ payableId: 'c', currency: 'EUR', unpaidAmount: 400 }),
      ]),
      7,
      '',
    );
    expect(view.summaryByCurrency).toHaveLength(2);
    const usd = view.summaryByCurrency.find((item) => item.currency === 'USD');
    expect(usd?.count).toBe(2);
    expect(usd?.unpaidAmount).toBe(350);
    expect(usd?.unpaidAmount).not.toBe(350.00000000000006);
  });

  it('到期日排序：逾期的在前，然后按到期日近的', () => {
    const view = buildUpcomingPayables(
      unpaidList([
        payableItem({ payableId: 'a', dueDate: '2026-10-28', daysOverdue: 0 }),
        payableItem({ payableId: 'b', dueDate: '2026-10-25', daysOverdue: 0 }),
        payableItem({ payableId: 'c', dueDate: '2026-01-01', daysOverdue: 100 }),
      ]),
    );
    expect(view.rows.map((row) => row.payableId)).toEqual(['c', 'b', 'a']);
  });

  it('每行都带一个跳到 12 财务应付页的出口', () => {
    const view = buildUpcomingPayables(unpaidList([payableItem({ payableId: 'a' })]));
    expect(view.rows[0]?.href).toContain('/finance/payables');
  });

  it('口径说明写明窗口口径与「逾期恒计入」', () => {
    const view = buildUpcomingPayables(unpaidList([payableItem({ payableId: 'a' })]));
    const lines = describePayable(view.rows[0]!, 7);
    expect(lines.some((line) => line.includes('已逾期的恒计入'))).toBe(true);
  });
});

// ---------------------------------------------------------------- 在途资金占用

describe('1.1.3.2 在途资金占用', () => {
  it('在途未结算资金按币种给出，两套口径原样透传', () => {
    const money = buildInTransitMoney(
      transitSummary([
        transitCurrency({ currency: 'USD', orderBasisAmount: 1000, settledBasisAmount: 400, inTransitAmount: 600 }),
      ]),
    );
    expect(money).toHaveLength(1);
    expect(money[0]?.inTransitAmount).toBe(600);
    expect(money[0]?.orderBasisAmount).toBe(1000);
    expect(money[0]?.settledBasisAmount).toBe(400);
  });

  it('在途库存：数量口径可算', () => {
    const goods = buildInTransitGoods(
      projectionPage([
        projectionRow({ inTransitQuantity: 10, inTransitFirstMileQuantity: 4 }),
        projectionRow({ skuId: 's-2', inTransitQuantity: 5, inTransitFirstMileQuantity: 1 }),
      ]),
    );
    expect(goods.totalQuantity).toBe(15);
    expect(goods.firstMileQuantity).toBe(5);
    expect(goods.legCount).toBe(4);
    expect(goods.truncated).toBe(false);
  });

  it('库存货值恒为 null（不可判定），并说明原因', () => {
    const goods = buildInTransitGoods(projectionPage([projectionRow()]));
    expect(goods.amount).toBeNull();
    expect(goods.amountNote).toBe(INVENTORY_VALUE_NOTE);
    expect(goods.amountNote).toContain('库存成本列');
  });

  it('截断时数量口径也判不了（报部分和会让人以为那是总量）', () => {
    const goods = buildInTransitGoods(
      projectionPage([projectionRow({ inTransitQuantity: 10 })], { total: 900 }),
    );
    expect(goods.truncated).toBe(true);
    expect(goods.totalQuantity).toBeNull();
    expect(goods.firstMileQuantity).toBeNull();
    expect(goods.legCount).toBeNull();
    expect(goods.fetchedRows).toBe(1);
    expect(goods.totalRows).toBe(900);
    expect(goods.truncationNote).toContain('不可判定');
  });

  it('截断说明与货值说明都写进口径文案', () => {
    const goods = buildInTransitGoods(
      projectionPage([projectionRow()], { total: 900 }),
    );
    const lines = describeInTransitGoods(goods);
    expect(lines.some((line) => line.includes('不可判定'))).toBe(true);
    expect(lines.some((line) => line.includes('库存成本列'))).toBe(true);
  });

  it('读不到时全部判不了（不是 0）', () => {
    const goods = buildInTransitGoods(null);
    expect(goods.totalQuantity).toBeNull();
    expect(goods.legCount).toBeNull();
    expect(goods.amount).toBeNull();
  });
});

// ---------------------------------------------------------------- 概览汇总

describe('1.1.3.2 按币种概览', () => {
  const allAvailable = {
    AGING: true,
    OVERDUE: true,
    SETTLEMENT: true,
    PAYABLE: true,
    DEFERRED: true,
    INVENTORY: true,
  };

  it('概览的每一格都来自它自己那份清单', () => {
    const aging = buildAgingCurrencies(
      distribution([currencyTotal({ currency: 'USD', openAmount: 1000, overdueAmount: 300 })]),
    );
    const overdue = buildOverdueView(
      overduePage([overdueRow({ id: 'a' })], {
        summaryByCurrency: [
          {
            currency: 'USD',
            count: 2,
            openAmount: 1000,
            overdueAmount: 300,
            disputeAmount: 0,
            disputeCount: 0,
          },
        ],
      }),
    );
    const settlement = buildPendingSettlement(
      settlementPage([
        { currency: 'USD', settlementAmount: 800, receivedAmount: 0, count: 1, pendingCount: 1 },
      ]),
      null,
    );
    const payables = buildUpcomingPayables(
      unpaidList([payableItem({ payableId: 'a', currency: 'USD', unpaidAmount: 400 })]),
    );
    const transit = buildInTransitMoney(transitSummary([transitCurrency({ currency: 'USD' })]));

    const summaries = buildCurrencySummaries(
      aging,
      overdue,
      settlement,
      payables,
      transit,
      allAvailable,
    );
    expect(summaries).toHaveLength(1);
    expect(summaries[0]?.currency).toBe('USD');
    expect(summaries[0]?.receivableOpenAmount).toBe(1000);
    expect(summaries[0]?.receivableOverdueAmount).toBe(300);
    expect(summaries[0]?.overdueCount).toBe(2);
    expect(summaries[0]?.pendingSettlementAmount).toBe(800);
    expect(summaries[0]?.payableUnpaidAmount).toBe(400);
    expect(summaries[0]?.inTransitMoneyAmount).toBe(600);
  });

  it('来源不可达时那一格是 null（不是 0）', () => {
    const summaries = buildCurrencySummaries(
      [],
      buildOverdueView(null),
      [],
      buildUpcomingPayables(null),
      [],
      { ...allAvailable, OVERDUE: false },
    );
    expect(summaries).toHaveLength(0);
  });

  it('来源不可达但已有该币种数据时是 null，不是 0', () => {
    const aging = buildAgingCurrencies(
      distribution([currencyTotal({ currency: 'USD', openAmount: 1000 })]),
    );
    const summaries = buildCurrencySummaries(
      aging,
      buildOverdueView(null),
      [],
      buildUpcomingPayables(null),
      [],
      { ...allAvailable, OVERDUE: false },
    );
    expect(summaries[0]?.currency).toBe('USD');
    expect(summaries[0]?.receivableOpenAmount).toBe(1000);
    expect(summaries[0]?.receivableOverdueAmount).toBeNull();
    expect(summaries[0]?.overdueCount).toBeNull();
  });

  it('概览里没有跨币种总额这一格（类型上就没有出口）', () => {
    const summaries = buildCurrencySummaries(
      buildAgingCurrencies(
        distribution([
          currencyTotal({ currency: 'USD', openAmount: 1000 }),
          currencyTotal({ currency: 'EUR', openAmount: 5000 }),
        ]),
      ),
      buildOverdueView(null),
      [],
      buildUpcomingPayables(null),
      [],
      allAvailable,
    );
    expect(summaries.map((item) => item.currency)).toEqual(['EUR', 'USD']);
    const keys = Object.keys(summaries[0] ?? {});
    expect(keys.some((key) => /total|grand|sum/i.test(key))).toBe(false);
  });
});
