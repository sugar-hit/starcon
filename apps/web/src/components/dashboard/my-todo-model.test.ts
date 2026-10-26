import { describe, expect, it } from 'vitest';

import type {
  ApprovalTodoItem,
  CrmMessageRow,
  FinanceSupplierReconciliationRow,
  PendingReviewRow,
  SafetyStockAlertRow,
  StockoutAlertRow,
  SyncFailureView,
  TimelinessOverdueRow,
} from '../../../../../packages/api-contracts/src/index';
import {
  EMPTY_FILTERS,
  TODO_TYPES,
  approvalSeverity,
  canRead,
  dwellFrom,
  filterTodos,
  formatDwell,
  mergeTodos,
  messageSeverity,
  overdueSeverity,
  pendingReviewSeverity,
  reconciliationSeverity,
  scopeModeOf,
  sortTodos,
  sourceAvailabilities,
  summarizeTodos,
  syncFailureSeverity,
  toOrderOverdue,
  toPendingReview,
  toUnreadMessage,
  unreadBadge,
  upstreamSeverity,
  type MyTodo,
  type SessionGrant,
  type SourceResult,
} from './my-todo-model';

/**
 * 1.1.1.1 我的待办 —— 前端聚合模型的单测。
 *
 * <p>本模块最容易做错的三件事，这里逐条钉住：
 *
 * <ol>
 *   <li><b>「判不了」被当成「没有」</b>：风险分 null、超时规则没配、
 *       停留起点缺失 —— 这三种都判不了严重度/停留时长，
 *       用 LOW 或 0 兜底会让它们排到最后然后永远没人处理。</li>
 *   <li><b>上游已处置的事项仍被报成待办</b>：已审的单、已解决的预警、
 *       已重放的同步失败、已读的站内信 —— 报成待办就是在制造假待办。</li>
 *   <li><b>计数与清单不是同一个集合</b>：概览上的数字必须能从清单里点出来。</li>
 * </ol>
 */

/** 固定的「现在」，让停留时长断言可复现。 */
const NOW = Date.parse('2026-10-24T12:00:00.000Z');

function overdueRow(overrides: Partial<TimelinessOverdueRow> = {}): TimelinessOverdueRow {
  return {
    factId: 'f-1',
    factNo: 'FACT-1',
    salesOrderNo: 'SO-1',
    shopRef: 'SHOP-A',
    warehouseRef: 'WH-1',
    carrierCode: 'DHL',
    carrierName: 'DHL',
    handedOverAt: '2026-10-20T00:00:00.000Z',
    orderToHandoverSeconds: 100,
    overdueKind: 'SHIP_SLA',
    overdueSeconds: 3600,
    deadlineAt: '2026-10-23T00:00:00.000Z',
    promiseVsDeliveryState: 'LATE',
    ...overrides,
  };
}

function pendingReviewRow(overrides: Partial<PendingReviewRow> = {}): PendingReviewRow {
  return {
    orderId: 'o-1',
    orderNo: 'SO-1',
    externalOrderNo: 'EXT-1',
    orderSource: 'AMAZON',
    currencyCode: 'USD',
    grandTotal: 100,
    submittedAt: '2026-10-24T10:00:00.000Z',
    waitingHours: 2,
    timelinessScore: 80,
    timelinessState: 'ON_TIME',
    timelinessStateLabel: '准时',
    queueStatus: 'PENDING',
    queueStatusLabel: '待审',
    decidedAt: null,
    decisionReason: null,
    ruleCode: null,
    ruleVersion: null,
    batchId: null,
    riskScore: 80,
    riskRecommendedAction: 'MANUAL_REVIEW',
    riskManualReviewRequired: true,
    riskEvaluationStatus: 'SCORED',
    reviewDecision: null,
    reviewMode: null,
    reviewDecisionReason: null,
    winningRuleCode: null,
    winningRuleName: null,
    matchedRuleCount: 2,
    forcedRuleCount: 1,
    hitRuleCodes: [],
    addressAnomalyCodes: [],
    recipientName: null,
    shippingCountry: 'US',
    ...overrides,
  };
}

function stockoutRow(overrides: Partial<StockoutAlertRow> = {}): StockoutAlertRow {
  return {
    id: 's-1',
    skuId: 'sku-1',
    skuCode: 'SKU-1',
    warehouseId: 'wh-1',
    warehouseCode: 'WH-1',
    warehouseName: '主仓',
    onHandQuantity: 0,
    reservedQuantity: 0,
    availableQuantity: 0,
    networkOnHandQuantity: 0,
    networkReservedQuantity: 0,
    networkAvailableQuantity: 0,
    runId: null,
    runAt: null,
    runMethod: null,
    runAvailableQuantity: null,
    runAllocatableQuantity: null,
    runUnallocatedQuantity: null,
    runAllocatedTotal: null,
    demandUnits: 10,
    demandRegistered: true,
    demandWindowStart: null,
    demandWindowEnd: null,
    demandShopCount: 1,
    coverageRatio: 0,
    daysOfCover: 0,
    alertState: 'NETWORK_STOCKED_OUT',
    severity: 'CRITICAL',
    demandState: 'KNOWN',
    note: '',
    recommendation: {
      kind: 'REPLENISH',
      target: 'SKU-1',
      targetLabel: '商品一',
      policyId: null,
      referenceId: null,
      warehouseId: 'wh-1',
      currentValue: 0,
      suggestedValue: 20,
      reason: '缺货',
    },
    lifecycleState: 'OPEN',
    acknowledgedByMembershipId: null,
    acknowledgedAt: null,
    resolvedAt: null,
    resolutionNote: null,
    recurrenceCount: 0,
    detectedAt: '2026-10-24T11:00:00.000Z',
    ...overrides,
  };
}

function syncFailureRow(overrides: Partial<SyncFailureView> = {}): SyncFailureView {
  return {
    id: 'sync-1',
    cursorId: 'c-1',
    platformCode: 'AMAZON',
    marketplaceCode: 'US',
    externalKey: 'EXT-1',
    resourceType: 'ORDER',
    reason: 'FIELD_PARSE_FAILED',
    detail: '字段缺失',
    payloadDigest: 'digest',
    attempts: 3,
    status: 'OPEN',
    lastError: 'boom',
    occurredAt: '2026-10-24T11:30:00.000Z',
    ...overrides,
  };
}

function reconciliationRow(
  overrides: Partial<FinanceSupplierReconciliationRow> = {},
): FinanceSupplierReconciliationRow {
  return {
    id: 'r-1',
    reconNo: 'RECON-1',
    upstreamReconId: 'u-1',
    upstreamReconNo: 'U-1',
    supplierRefId: 'sup-1',
    supplierCode: 'S1',
    supplierName: '供应商一',
    periodType: 'MONTH',
    periodStart: '2026-09-01',
    periodEnd: '2026-09-30',
    statementCurrency: 'USD',
    status: 'DIFFERENCES',
    statusLabel: '有差异',
    upstreamStatus: 'DIFFERENCES',
    statementReceivedAmount: 100,
    statementDeductionAmount: 0,
    statementClaimAmount: 0,
    statementSuggestedAmount: 100,
    statementTotalQuantity: 10,
    financeConfirmedAmount: null,
    financeConfirmedQuantity: null,
    financeAdjustedAmount: 0,
    lineCount: 10,
    differenceCount: 3,
    openDifferenceCount: 3,
    matchedLineCount: 7,
    unmatchedLineCount: 3,
    upstreamSupplierConfirmedAt: null,
    upstreamSupplierReference: '',
    confirmedBy: null,
    confirmedAt: null,
    rejectedBy: null,
    rejectedAt: null,
    rejectReason: '',
    payableId: null,
    payableNo: '',
    note: '',
    createdAt: '2026-10-20T00:00:00.000Z',
    updatedAt: '2026-10-23T00:00:00.000Z',
    ...overrides,
  };
}

function messageRow(overrides: Partial<CrmMessageRow> = {}): CrmMessageRow {
  return {
    id: 'm-1',
    messageNo: 'MSG-1',
    shopId: 'shop-1',
    shopName: '美国店',
    platformCode: 'AMAZON',
    buyerReference: 'B-1',
    orderReference: 'SO-1',
    subject: '买家询问发货',
    body: '什么时候发货',
    direction: 'INBOUND',
    messageType: 'BUYER_QUESTION',
    priority: 'URGENT',
    readStatus: 'UNREAD',
    readAt: null,
    readBy: null,
    platformTime: '2026-10-24T11:00:00.000Z',
    attachmentCount: 0,
    source: 'PLATFORM',
    externalMessageId: 'EXT-1',
    createdBy: 'system',
    note: '',
    createdAt: '2026-10-24T11:00:00.000Z',
    updatedAt: '2026-10-24T11:00:00.000Z',
    ...overrides,
  };
}

function approvalItem(overrides: Partial<ApprovalTodoItem> = {}): ApprovalTodoItem {
  return {
    id: 'a-1',
    instanceNo: 'INST-1',
    documentTypeCode: 'PURCHASE_ORDER',
    documentTypeName: '采购单',
    flowName: '采购审批',
    subjectTitle: '采购 100 件',
    amount: 100,
    currencyCode: 'USD',
    applicantId: '11111111-1111-1111-1111-111111111111',
    applicantName: '张三',
    currentNodeKey: 'N1',
    currentNodeName: '经理审批',
    currentNodeOrder: 1,
    currentStageIndex: 0,
    approverType: 'USER',
    sourceSystem: 'PURCHASE',
    sourceReferenceId: 'PO-1',
    submittedAt: '2026-10-24T08:00:00.000Z',
    nodeEnteredAt: '2026-10-24T08:00:00.000Z',
    dwellSeconds: 4 * 3600,
    totalSeconds: 4 * 3600,
    timeoutSeconds: 3600,
    overdueSeconds: 1800,
    amountThresholdName: null,
    amountThresholdMin: null,
    amountThresholdMax: null,
    amountThresholdCurrency: null,
    status: 'PENDING',
    ...overrides,
  };
}

function safetyStockRow(overrides: Partial<SafetyStockAlertRow> = {}): SafetyStockAlertRow {
  return {
    id: 'ss-1',
    evaluationId: 'e-1',
    policyId: 'p-1',
    policyScopeType: 'SKU',
    warehouseId: 'wh-1',
    warehouseCode: 'WH-1',
    warehouseName: '主仓',
    skuId: 'sku-1',
    skuCode: 'SKU-1',
    skuLabel: '商品一',
    alertType: 'OWN_WAREHOUSE_DEPLETION',
    severity: 'WARNING',
    effectiveSeverity: 'WARNING',
    availableQuantity: 3,
    thresholdQuantity: 10,
    shortfallQuantity: 7,
    dailyAverageSales: 1,
    daysOfCover: 3,
    suggestedQuantity: 20,
    status: 'OPEN',
    detectedAt: '2026-10-24T09:00:00.000Z',
    acknowledgedAt: null,
    resolvedAt: null,
    reviewNote: '',
    ...overrides,
  };
}

function ok<T>(rows: T[], total = rows.length): SourceResult<T> {
  return { rows, total, error: '' };
}

function failed<T>(reason: string): SourceResult<T> {
  return { rows: [], total: 0, error: reason };
}

function merge(overrides: Partial<Parameters<typeof mergeTodos>[0]> = {}, now = NOW) {
  const base = {
    orderOverdue: ok<TimelinessOverdueRow>([]),
    pendingReview: ok<PendingReviewRow>([]),
    stockShortage: ok<StockoutAlertRow>([]),
    syncFailed: ok<SyncFailureView>([]),
    reconciliationDiff: ok<FinanceSupplierReconciliationRow>([]),
    messageUnread: ok<CrmMessageRow>([]),
    approvalPending: ok<ApprovalTodoItem>([]),
    safetyStockBelow: ok<SafetyStockAlertRow>([]),
  };
  return mergeTodos({ ...base, ...overrides }, now);
}

describe('1.1.1.1 八类待办一一对应蓝图点名的八种动作', () => {
  it('类型表就是蓝图「覆盖：」那一行的八项，顺序一致', () => {
    expect([...TODO_TYPES]).toEqual([
      'ORDER_OVERDUE',
      'ORDER_PENDING_REVIEW',
      'STOCK_SHORTAGE',
      'SYNC_FAILED',
      'RECONCILIATION_DIFF',
      'MESSAGE_UNREAD',
      'APPROVAL_PENDING',
      'SAFETY_STOCK_BELOW',
    ]);
  });
});

describe('1.1.1.1 「判不了」不被当成「没有」', () => {
  it('风险分 null ⇒ 判不了严重度，而不是零风险（LOW）', () => {
    const row = pendingReviewRow({ riskScore: null, riskManualReviewRequired: false });
    expect(pendingReviewSeverity(row)).toBe('UNKNOWN');
    expect(pendingReviewSeverity(pendingReviewRow({ riskScore: 0, riskManualReviewRequired: false }))).toBe(
      'LOW',
    );
  });

  it('风控明确要求人工复核 ⇒ 直接定到「高」（它比风险分更权威）', () => {
    expect(
      pendingReviewSeverity(pendingReviewRow({ riskScore: 10, riskManualReviewRequired: true })),
    ).toBe('HIGH');
  });

  it('审批节点没配时限 ⇒ 判不了超时，不拿写死的 SLA 冒充', () => {
    const item = approvalItem({ timeoutSeconds: null, overdueSeconds: null, dwellSeconds: 60 });
    expect(approvalSeverity(item)).toBe('UNKNOWN');
    // 配了时限且已超时 ⇒ 严重。
    expect(approvalSeverity(approvalItem({ timeoutSeconds: 3600, overdueSeconds: 10 }))).toBe(
      'CRITICAL',
    );
  });

  it('停留起点缺失 ⇒ 停留时长判不了（null），不是 0', () => {
    expect(dwellFrom(null, NOW)).toBeNull();
    expect(dwellFrom('不是时刻', NOW)).toBeNull();
    expect(dwellFrom('2026-10-24T11:00:00.000Z', NOW)).toBe(3600);
    expect(formatDwell(null)).toBe('停留时长判不了');
    expect(formatDwell(0)).toBe('不到 1 分钟');
  });

  it('上游给了没见过的严重度词 ⇒ 判不了，不静默回落成 LOW', () => {
    expect(upstreamSeverity('WHATEVER')).toBe('UNKNOWN');
    expect(upstreamSeverity(null)).toBe('UNKNOWN');
  });

  it('同步失败的失败原因没认得 ⇒ 判不了', () => {
    expect(syncFailureSeverity('SOMETHING_NEW')).toBe('UNKNOWN');
  });

  it('站内信优先级没认得 ⇒ 判不了', () => {
    expect(messageSeverity('WHATEVER')).toBe('UNKNOWN');
  });
});

describe('1.1.1.1 上游已处置的事项不再被报成待办', () => {
  it('已审的单 / 已解决的预警 / 已重放的失败 / 已读的站内信都不进队列', () => {
    const merged = merge({
      pendingReview: ok([pendingReviewRow({ queueStatus: 'APPROVED' })]),
      stockShortage: ok([stockoutRow({ lifecycleState: 'RESOLVED' })]),
      syncFailed: ok([syncFailureRow({ status: 'REPLAYED' })]),
      messageUnread: ok([messageRow({ readStatus: 'READ' })]),
      approvalPending: ok([approvalItem({ status: 'APPROVED' })]),
      safetyStockBelow: ok([safetyStockRow({ status: 'RESOLVED' })]),
    });
    expect(merged.todos).toHaveLength(0);
  });

  it('健康（HEALTHY）的缺货行不是缺口', () => {
    const merged = merge({ stockShortage: ok([stockoutRow({ alertState: 'HEALTHY' })]) });
    expect(merged.todos).toHaveLength(0);
  });

  it('发出去的站内信（OUTBOUND）不是「待回复」', () => {
    const merged = merge({ messageUnread: ok([messageRow({ direction: 'OUTBOUND' })]) });
    expect(merged.todos).toHaveLength(0);
  });

  it('没有未结差异的对账单不是待办', () => {
    const merged = merge({
      reconciliationDiff: ok([reconciliationRow({ openDifferenceCount: 0, differenceCount: 0 })]),
    });
    expect(merged.todos).toHaveLength(0);
  });

  it('已认领（ACKNOWLEDGED）的预警**仍然**是待办 —— 认领不等于处理完', () => {
    const merged = merge({ safetyStockBelow: ok([safetyStockRow({ status: 'ACKNOWLEDGED' })]) });
    expect(merged.todos).toHaveLength(1);
  });
});

describe('1.1.1.1 读不到与没有是两件不同的事', () => {
  it('读不到的来源单列成 unreadable，不进清单也不当成 0 条', () => {
    const merged = merge({
      syncFailed: failed('服务暂时不可用'),
      orderOverdue: ok([overdueRow()]),
    });
    expect(merged.todos).toHaveLength(1);
    expect(merged.unreadable).toEqual([
      { type: 'SYNC_FAILED', reason: '服务暂时不可用' },
    ]);
  });

  it('汇总把「判不了严重度」的条数单独报出来', () => {
    const merged = merge({
      pendingReview: ok([pendingReviewRow({ riskScore: null, riskManualReviewRequired: false })]),
    });
    const summary = summarizeTodos(merged.todos, merged.unreadable.length);
    expect(summary.total).toBe(1);
    expect(summary.unknownSeverityCount).toBe(1);
    expect(summary.bySeverity.UNKNOWN).toBe(1);
  });
});

describe('1.1.1.1 按紧急度排序', () => {
  const critical: MyTodo = {
    key: 'a',
    type: 'STOCK_SHORTAGE',
    reference: 'A',
    referenceLabel: '',
    severity: 'CRITICAL',
    dwellSeconds: 10,
    href: '/a',
    actionLabel: '',
    detail: '',
    scopeLabel: '',
  };
  const high: MyTodo = { ...critical, key: 'b', reference: 'B', severity: 'HIGH', dwellSeconds: 999999 };
  const unknown: MyTodo = { ...critical, key: 'c', reference: 'C', severity: 'UNKNOWN' };

  it('严重度降序', () => {
    expect(sortTodos([unknown, high, critical]).map((t) => t.severity)).toEqual([
      'CRITICAL',
      'HIGH',
      'UNKNOWN',
    ]);
  });

  it('同级按停留时长降序（停得久的先看）', () => {
    const slow = { ...critical, key: 'd', reference: 'D', dwellSeconds: 7200 };
    const fast = { ...critical, key: 'e', reference: 'E', dwellSeconds: 60 };
    expect(sortTodos([fast, slow]).map((t) => t.reference)).toEqual(['D', 'E']);
  });

  it('停留时长判不了的排在同级有读数的**后面**（不等于「停了很久」）', () => {
    const unknownDwell = { ...critical, key: 'f', reference: 'F', dwellSeconds: null };
    const known = { ...critical, key: 'g', reference: 'G', dwellSeconds: 60 };
    expect(sortTodos([known, unknownDwell]).map((t) => t.reference)).toEqual(['G', 'F']);
  });

  it('同严重度同停留 ⇒ 按类型与引用稳定排，两次排序结果一致', () => {
    const one = { ...critical, key: '1', reference: 'X' };
    const two = { ...critical, key: '2', reference: 'Y' };
    expect(sortTodos([two, one])).toEqual(sortTodos([one, two]));
  });
});

describe('1.1.1.1 计数与清单同源', () => {
  it('概览计数由清单同一份集合算出', () => {
    const merged = merge({
      orderOverdue: ok([overdueRow(), overdueRow({ factId: 'f-2', factNo: 'FACT-2' })]),
      messageUnread: ok([messageRow()]),
    });
    const summary = summarizeTodos(merged.todos, merged.unreadable.length);
    expect(summary.total).toBe(merged.todos.length);
    expect(summary.byType.ORDER_OVERDUE).toBe(2);
    expect(summary.byType.MESSAGE_UNREAD).toBe(1);
    expect(summary.unreadCount).toBe(1);
    expect(unreadBadge(summary)).toBe('1');
  });

  it('没有未读时角标为空串（不是 "0"）', () => {
    expect(unreadBadge({ unreadCount: 0 })).toBe('');
  });

  it('筛选后的集合与概览分别算 —— 概览是整集合的数，不随筛选变', () => {
    const merged = merge({
      orderOverdue: ok([overdueRow(), overdueRow({ factId: 'f-2', factNo: 'FACT-2' })]),
      messageUnread: ok([messageRow()]),
    });
    const filtered = filterTodos(merged.todos, { ...EMPTY_FILTERS, type: 'MESSAGE_UNREAD' });
    expect(filtered).toHaveLength(1);
    const whole = summarizeTodos(merged.todos, 0);
    expect(whole.total).toBe(3);
  });
});

describe('1.1.1.1 筛选', () => {
  const todos: MyTodo[] = [
    {
      key: '1',
      type: 'ORDER_OVERDUE',
      reference: 'SO-1',
      referenceLabel: '发货超期',
      severity: 'HIGH',
      dwellSeconds: 7200,
      href: '/a',
      actionLabel: '',
      detail: 'DHL',
      scopeLabel: '店铺 SHOP-A',
    },
    {
      key: '2',
      type: 'MESSAGE_UNREAD',
      reference: 'MSG-1',
      referenceLabel: '美国店',
      severity: 'LOW',
      dwellSeconds: 60,
      href: '/b',
      actionLabel: '',
      detail: '买家来信',
      scopeLabel: '店铺 美国店',
    },
  ];

  it('按类型筛', () => {
    expect(filterTodos(todos, { ...EMPTY_FILTERS, type: 'MESSAGE_UNREAD' })).toHaveLength(1);
  });

  it('按严重度筛（含「判不了」这一档）', () => {
    expect(filterTodos(todos, { ...EMPTY_FILTERS, severity: 'HIGH' })).toHaveLength(1);
    expect(filterTodos(todos, { ...EMPTY_FILTERS, severity: 'UNKNOWN' })).toHaveLength(0);
  });

  it('按关键字筛（跨引用 / 说明 / 范围）', () => {
    expect(filterTodos(todos, { ...EMPTY_FILTERS, keyword: 'shop-a' })).toHaveLength(1);
    expect(filterTodos(todos, { ...EMPTY_FILTERS, keyword: 'SO-1' })).toHaveLength(1);
    expect(filterTodos(todos, { ...EMPTY_FILTERS, keyword: '没有这个' })).toHaveLength(0);
  });

  it('停留时长下限**不排除**判不了停留时长的条目（排除掉就等于假装它没停够久）', () => {
    const unknownDwell: MyTodo = { ...todos[0]!, key: '3', dwellSeconds: null };
    const filtered = filterTodos([...todos, unknownDwell], {
      ...EMPTY_FILTERS,
      minDwellHours: 1,
    });
    // SO-1 停了 2 小时（保留）+ 判不了的那条（保留）+ MSG-1 只停 1 分钟（排除）
    expect(filtered.map((t) => t.key)).toEqual(['1', '3']);
  });
});

describe('1.1.1.1 归一：每个来源都折成同一种卡片', () => {
  it('超期未发货：停留时长取超期秒数，范围标签取店铺', () => {
    const todo = toOrderOverdue(overdueRow({ overdueSeconds: 7200 }), NOW);
    expect(todo.type).toBe('ORDER_OVERDUE');
    expect(todo.reference).toBe('SO-1');
    expect(todo.dwellSeconds).toBe(7200);
    expect(todo.scopeLabel).toBe('店铺 SHOP-A');
    expect(todo.href).toContain('/orders/fulfillment/timeliness');
  });

  it('待审订单：停留时长取已等候小时数换算的秒数', () => {
    const todo = toPendingReview(pendingReviewRow({ waitingHours: 3 }), NOW);
    expect(todo.dwellSeconds).toBe(3 * 3600);
    expect(todo.severity).toBe('HIGH');
  });

  it('站内信：引用取消息号，下一步带上定位参数', () => {
    const todo = toUnreadMessage(messageRow(), NOW);
    expect(todo.reference).toBe('MSG-1');
    expect(todo.href).toContain('/crm/messages?keyword=');
    expect(todo.href).toContain(encodeURIComponent('MSG-1'));
  });

  it('超期严重度分档：24 小时以上 ⇒ 严重', () => {
    expect(overdueSeverity(25 * 3600)).toBe('CRITICAL');
    expect(overdueSeverity(5 * 3600)).toBe('HIGH');
    expect(overdueSeverity(60)).toBe('MEDIUM');
    expect(overdueSeverity(0)).toBe('LOW');
    expect(overdueSeverity(null)).toBe('UNKNOWN');
  });

  it('对账差异严重度按未结条数分档', () => {
    expect(reconciliationSeverity(12)).toBe('HIGH');
    expect(reconciliationSeverity(3)).toBe('MEDIUM');
    expect(reconciliationSeverity(0)).toBe('LOW');
    expect(reconciliationSeverity(null)).toBe('UNKNOWN');
  });
});

describe('1.1.1.1 数据范围（法人/店铺/仓库/部门）', () => {
  function grant(scopeMode: string, permissions: string[]): SessionGrant {
    return {
      roleId: '11111111-1111-1111-1111-111111111111',
      roleCode: 'R',
      scopeMode,
      permissions,
    };
  }

  it('ALL 授权 ⇒ 租户内全部；SELF 授权 ⇒ 仅本人', () => {
    expect(scopeModeOf([grant('ALL', ['trade.orders.read'])], 'trade.orders.read', false)).toBe('ALL');
    expect(scopeModeOf([grant('SELF', ['trade.orders.read'])], 'trade.orders.read', false)).toBe('SELF');
  });

  it('DEPARTMENT 授权且会话带部门 ⇒ 本部门；不带部门 ⇒ 退化为仅本人', () => {
    const grants = [grant('DEPARTMENT', ['trade.orders.read'])];
    expect(scopeModeOf(grants, 'trade.orders.read', true)).toBe('DEPARTMENT');
    expect(scopeModeOf(grants, 'trade.orders.read', false)).toBe('SELF');
  });

  it('取**最宽**的一条授权（ALL > DEPARTMENT > SELF）—— 与后端 GrantPermissions 同式', () => {
    const grants = [grant('SELF', ['trade.orders.read']), grant('ALL', ['trade.orders.read'])];
    expect(scopeModeOf(grants, 'trade.orders.read', false)).toBe('ALL');
    const mixed = [grant('SELF', ['trade.orders.read']), grant('DEPARTMENT', ['trade.orders.read'])];
    expect(scopeModeOf(mixed, 'trade.orders.read', true)).toBe('DEPARTMENT');
  });

  it('没有该权限码 ⇒ 判不出范围（退化为仅本人，最严的一侧）', () => {
    expect(scopeModeOf([grant('ALL', ['other.read'])], 'trade.orders.read', true)).toBe('SELF');
    expect(scopeModeOf([], 'trade.orders.read', true)).toBe('SELF');
    expect(scopeModeOf(null, 'trade.orders.read', true)).toBe('SELF');
  });

  it('「能写不能读」的组合不被放行 —— 必须同时有动作权限与同页 .read 权限', () => {
    // 只有 .read 而没有动作权限 ⇒ 不成立。
    expect(canRead([grant('ALL', ['trade.orders'])], 'trade.orders.read', false)).toBe(false);
    // 动作权限与同页 .read 都在 ⇒ 成立。
    expect(canRead([grant('ALL', ['trade.orders', 'trade.orders.read'])], 'trade.orders.read', false)).toBe(
      true,
    );
  });

  it('DEPARTMENT 授权但会话无部门 ⇒ 没有读权限', () => {
    const grants = [grant('DEPARTMENT', ['trade.orders', 'trade.orders.read'])];
    expect(canRead(grants, 'trade.orders.read', false)).toBe(false);
    expect(canRead(grants, 'trade.orders.read', true)).toBe(true);
  });

  it('八个来源逐个判可达性：无权限的来源被标出来，不与「服务读不到」混同', () => {
    const access = {
      userId: 'u-1',
      tenantId: 't-1',
      tenantName: '测试租户',
      membershipId: 'm-1',
      departmentId: null,
      permissions: [],
      grants: [grant('ALL', ['trade.orders.read', 'trade.fulfillment.read'])],
    };
    const avail = sourceAvailabilities(access);
    expect(avail.ORDER_PENDING_REVIEW.permitted).toBe(true);
    expect(avail.ORDER_OVERDUE.permitted).toBe(true);
    // 这几个权限码没在授权里 ⇒ 对该角色不开放。
    expect(avail.SYNC_FAILED.permitted).toBe(false);
    expect(avail.MESSAGE_UNREAD.permitted).toBe(false);
    expect(avail.APPROVAL_PENDING.permitted).toBe(false);
    expect(avail.SAFETY_STOCK_BELOW.permitted).toBe(false);
    // 八个类型都有判定结果 —— 漏一个就是漏一类待办。
    expect(Object.keys(avail).sort()).toEqual([...TODO_TYPES].sort());
  });

  it('会话读不到时（null）全部来源判为不可读 —— 而不是默认为全部可读', () => {
    const avail = sourceAvailabilities(null);
    expect(Object.values(avail).every((item) => !item.permitted)).toBe(true);
  });
});
