import { describe, expect, it } from 'vitest';
import type {
  ExchangeOrderAuthorizationOption,
  ExchangeOrderCapabilities,
  ExchangeOrderDetail,
  ExchangeOrderRow,
  ExchangeOrderSummary,
} from '../../../../../packages/api-contracts/src/index';
import {
  attentionSummary,
  authorizationBlocker,
  canCancel,
  canSubmit,
  canSync,
  cancelBlocking,
  closureBlockers,
  closureHeadline,
  closureLabel,
  closureTone,
  downstreamNotices,
  emptyHint,
  exchangeOrderListQuery,
  formatAmount,
  formatQty,
  formatTime,
  hardRules,
  newOrderLabel,
  newOrderTone,
  oldGoodsLabel,
  oldGoodsTone,
  shipmentBadge,
  shipmentTone,
  submitBlocking,
  syncExplainer,
  unavailable,
} from './exchange-order-link-model';

/**
 * 11.3.2.2 换货新单前端模型的钉桩测试。
 *
 * <p>重点钉三件容易在界面上说错话的事：
 * <ol>
 *   <li><b>「已建立」不能被渲染成中性色</b>：它是一张要人推的单，用 neutral 会让它
 *       看起来和「一切正常」一样，于是没人管它；</li>
 *   <li><b>没发货记录时必须说「尚无发货记录」</b>，不能留空 ——
 *       空白会被读成「还没显示」，而真相是「8 出库还没接手」；</li>
 *   <li><b>没闭环的原因要逐条透出</b>，前端不许自己编一句「处理中」 ——
 *       那句话在只发不收的场景里是有害的。</li>
 * </ol>
 */

function row(overrides: Partial<ExchangeOrderRow> = {}): ExchangeOrderRow {
  return {
    id: 'k1',
    linkNo: 'EXL-20260401-000001',
    authorizationId: 'a1',
    authorizationNo: 'EX-20260401-000001',
    caseId: 'c1',
    caseNo: 'AS-1',
    originalOrderId: 'o1',
    originalOrderNo: 'SO-1',
    newOrderId: 'n1',
    newOrderNo: 'EXO-20260401-000002',
    newOrderStatus: 'DRAFT',
    newOrderStatusLabel: '草稿',
    closureStatus: 'CREATED',
    closureStatusLabel: '已建立（待提交履约）',
    currency: 'USD',
    totalQuantity: '2.000000',
    replacementSkuCount: 1,
    lineCount: 1,
    newOrderAmount: '0.0000',
    shipmentCount: 0,
    firstShippedAt: '',
    shipmentRecordNo: '',
    lastTrackingStatus: 'UNSET',
    oldGoodsReturnStatus: 'WAITING',
    oldGoodsReturnStatusLabel: '等买家寄回',
    reasonNote: '',
    createdBy: 'u1',
    createdAt: '2026-04-01T08:00:00Z',
    submittedAt: '',
    dispatchedAt: '',
    closedAt: '',
    cancelledBy: '',
    cancelledAt: '',
    cancelReason: '',
    version: 1,
    ...overrides,
  };
}

function detail(overrides: Partial<ExchangeOrderRow> = {}, blockers: string[] = []): ExchangeOrderDetail {
  return {
    link: row(overrides),
    lines: [],
    events: [],
    allowedActions: ['SUBMIT', 'SYNC', 'CANCEL'],
    closureBlockers: blockers,
  };
}

function caps(overrides: Partial<ExchangeOrderCapabilities> = {}): ExchangeOrderCapabilities {
  return {
    exchangeAuthorizationDelivered: true,
    realNewSalesOrder: true,
    shipmentProjectionReadable: true,
    shipmentProjectionOwnerModule: '9.4.1.1',
    outboundHandoffDelivered: false,
    outboundHandoffOwnerModule: '9.3.3.1',
    oldGoodsReturnReceivedDelivered: false,
    oldGoodsReturnOwnerModule: '11.2',
    closureStatuses: ['CREATED', 'DISPATCHED', 'CLOSED', 'CANCELLED'],
    closureStatusLabels: ['已建立（待提交履约）', '替换货已发出', '换货已闭环', '已撤销'],
    allowedNextActions: ['SUBMIT', 'SYNC', 'CANCEL'],
    note: '',
    hardRules: ['防重复换货'],
    ...overrides,
  };
}

function authorization(
  overrides: Partial<ExchangeOrderAuthorizationOption> = {},
): ExchangeOrderAuthorizationOption {
  return {
    authorizationId: 'a1',
    authorizationNo: 'EX-1',
    caseId: 'c1',
    caseNo: 'AS-1',
    originalOrderId: 'o1',
    originalOrderNo: 'SO-1',
    status: 'ACTIVE',
    statusLabel: '生效中',
    decision: 'APPROVED',
    decisionLabel: '整量批准',
    authorizedQuantity: '2.000000',
    replacementSkuCount: 1,
    hasShippingSnapshot: true,
    lines: [],
    ...overrides,
  };
}

describe('查询串', () => {
  it('空条件不产生问号', () => {
    expect(exchangeOrderListQuery({})).toBe('');
  });

  it('逐项编码并跳过空值', () => {
    expect(
      exchangeOrderListQuery({ status: 'CREATED', keyword: 'EXO 2026', page: 2, size: 20 }),
    ).toBe('?status=CREATED&keyword=EXO%202026&page=2&size=20');
  });

  it('空白字符串视为未填 —— 否则会发一个 status= 的空筛选', () => {
    expect(exchangeOrderListQuery({ status: '   ', keyword: '' })).toBe('');
  });
});

describe('闭环状态的语气', () => {
  it('已建立是警告而不是中性：它是一张要人推的单', () => {
    expect(closureTone('CREATED')).toBe('warning');
  });

  it('已发出是品牌色、已闭环是成功、已撤销是中性', () => {
    expect(closureTone('DISPATCHED')).toBe('brand');
    expect(closureTone('CLOSED')).toBe('success');
    expect(closureTone('CANCELLED')).toBe('neutral');
  });

  it('未知状态不许悄悄回落成中性 —— 未知更接近「要人看一眼」', () => {
    expect(closureTone('SOMETHING_NEW')).toBe('warning');
    expect(closureTone(null)).toBe('warning');
  });

  it('优先用后端下发的标签', () => {
    expect(closureLabel(row())).toBe('已建立（待提交履约）');
    expect(closureLabel(row({ closureStatusLabel: '' }))).toBe('已建立（待提交履约）');
  });
});

describe('发货情况', () => {
  it('没有发货记录时明确说「尚无发货记录」，不留空', () => {
    // 留空会被读成「还没显示」，而真相是「8 出库还没接手」。
    expect(shipmentBadge(row())).toBe('尚无发货记录');
    expect(shipmentTone(row())).toBe('warning');
  });

  it('有记录时连单号一起显示 —— 「已发出」必须能追到具体运单', () => {
    const r = row({ shipmentCount: 2, shipmentRecordNo: 'SR-20260401-000001' });
    expect(shipmentBadge(r)).toBe('2 条发货记录 · SR-20260401-000001');
    expect(shipmentTone(r)).toBe('success');
  });
});

describe('旧货回寄', () => {
  it('还在等回寄是警告：只发不收的敞口还开着', () => {
    expect(oldGoodsTone('WAITING')).toBe('warning');
    expect(oldGoodsTone('WAIVED')).toBe('neutral');
    expect(oldGoodsLabel(row())).toBe('等买家寄回');
  });
});

describe('新单单据状态', () => {
  it('草稿是警告（还没进审单队列），已提交是品牌色', () => {
    expect(newOrderTone('DRAFT')).toBe('warning');
    expect(newOrderTone('SUBMITTED')).toBe('brand');
    expect(newOrderLabel(row())).toBe('草稿');
  });
});

describe('没闭环的真实原因', () => {
  it('逐条透出后端下发的原因，不自己编', () => {
    const d = detail({}, ['还没有真实发货记录', '还有 1 行旧货在等买家寄回']);
    expect(closureBlockers(d)).toHaveLength(2);
    expect(closureHeadline(d)).toContain('还差 2 项');
    expect(closureHeadline(d)).toContain('旧货在等买家寄回');
  });

  it('已闭环时不再报原因', () => {
    const d = detail({ closureStatus: 'CLOSED', closureStatusLabel: '换货已闭环' });
    expect(closureBlockers(d)).toEqual([]);
    expect(closureHeadline(d)).toContain('换货闭环');
  });

  it('已撤销不是「还没闭环」', () => {
    const d = detail({ closureStatus: 'CANCELLED', closureStatusLabel: '已撤销' });
    expect(closureHeadline(d)).toContain('已撤销');
  });

  it('没有详情时返回空数组而不是抛错', () => {
    expect(closureBlockers(null)).toEqual([]);
    expect(closureHeadline(null)).toBe('');
  });
});

describe('可执行动作只认后端', () => {
  it('SUBMIT / SYNC / CANCEL 分别由 allowedActions 决定', () => {
    expect(canSubmit(detail())).toBe(true);
    expect(canSync(detail())).toBe(true);
    expect(canCancel(detail())).toBe(true);

    const onlySync = { ...detail(), allowedActions: ['SYNC'] };
    expect(canSubmit(onlySync)).toBe(false);
    expect(canCancel(onlySync)).toBe(false);
  });

  it('没有详情时一律不可执行', () => {
    expect(canSubmit(null)).toBe(false);
    expect(canSync(null)).toBe(false);
    expect(canCancel(null)).toBe(false);
  });

  it('按钮为什么灰要给理由，不能凭空消失', () => {
    expect(cancelBlocking(null)).toBe('先打开一张换货新单');
    expect(cancelBlocking(detail())).toBeNull();
    expect(
      cancelBlocking({ ...detail(), allowedActions: ['SYNC'], link: row({ newOrderStatus: 'SUBMITTED' }) }),
    ).toContain('9.2 审单');
    expect(cancelBlocking({ ...detail(), allowedActions: ['SYNC'], link: row({ closureStatus: 'CANCELLED' }) })).toContain(
      '已撤销',
    );
    expect(submitBlocking(null)).toBe('先打开一张换货新单');
    expect(submitBlocking(detail())).toBeNull();
  });

  it('同步说明必须写清它读真实表、不接受手工指定状态', () => {
    expect(syncExplainer()).toContain('不接受任何手工指定的状态');
  });
});

describe('下游未交付如实报出', () => {
  it('新出库未交付时写出归属模块', () => {
    const notices = downstreamNotices(caps());
    expect(notices.join('；')).toContain('9.3.3.1');
  });

  it('旧货实收未交付时说明「已闭环」不代表旧货已入库', () => {
    const notices = downstreamNotices(caps());
    expect(notices.join('；')).toContain('11.2');
    expect(notices.join('；')).toContain('不代表旧货已入库');
  });

  it('全部已交付时不制造多余的提示', () => {
    const notices = downstreamNotices(
      caps({ outboundHandoffDelivered: true, oldGoodsReturnReceivedDelivered: true }),
    );
    expect(notices).toEqual([]);
  });

  it('没有 capabilities 时返回空数组', () => {
    expect(downstreamNotices(null)).toEqual([]);
    expect(hardRules(null)).toEqual([]);
  });
});

describe('硬规则逐条显示', () => {
  it('逐条返回后端下发的原文', () => {
    expect(hardRules(caps({ hardRules: ['规则一', '规则二'] }))).toEqual(['规则一', '规则二']);
  });
});

describe('建单可用性', () => {
  it('原单没有收货地址快照时提前给出理由，而不是让人填完再收 422', () => {
    expect(authorizationBlocker(authorization({ hasShippingSnapshot: false }))).toContain('收货地址快照');
    expect(authorizationBlocker(authorization())).toBeNull();
    expect(authorizationBlocker(undefined)).toBeNull();
  });
});

describe('展示工具', () => {
  it('数量去尾零但保留整数形态', () => {
    expect(formatQty('2.000000')).toBe('2');
    expect(formatQty('2.500000')).toBe('2.5');
    expect(formatQty(undefined)).toBe('0');
  });

  it('金额显示出来是为了让人看见「不二次收费」', () => {
    expect(formatAmount('0.0000')).toBe('0');
    expect(formatAmount('12.5000')).toBe('12.5');
  });

  it('时间缺失显示破折号而不是空白', () => {
    expect(formatTime('')).toBe('—');
    expect(formatTime(null)).toBe('—');
    expect(formatTime('2026-04-01T08:00:00Z')).not.toBe('—');
  });

  it('缺值统一破折号，0 是真实的数不能写成破折号', () => {
    expect(unavailable('')).toBe('—');
    expect(unavailable('0')).toBe('0');
  });
});

describe('汇总', () => {
  const summary: ExchangeOrderSummary = {
    totalCount: 5,
    createdCount: 3,
    dispatchedCount: 1,
    closedCount: 1,
    cancelledCount: 0,
    totalQuantity: '7.000000',
    pendingDispatchCount: 3,
  };

  it('待处理数来自后端，不在前端重算', () => {
    expect(attentionSummary(summary)).toEqual({ pendingDispatch: 3, totalQuantity: '7' });
  });

  it('没有汇总时给 0 而不是 NaN', () => {
    expect(attentionSummary(null)).toEqual({ pendingDispatch: 0, totalQuantity: '0' });
  });
});

describe('空态文案', () => {
  it('说清为什么空以及怎么才会有', () => {
    expect(emptyHint(caps())).toContain('换货授权');
    expect(emptyHint(caps({ exchangeAuthorizationDelivered: false }))).toContain('尚未接入');
  });
});
