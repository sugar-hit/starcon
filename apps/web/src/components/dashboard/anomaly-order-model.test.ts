import { describe, expect, it } from 'vitest';
import type {
  AnomalyOrderCapabilities,
  AnomalyOrderListRow,
  AvailableQuantityRow,
  BuyerBlacklistOrderFlagView,
} from '../../../../../packages/api-contracts/src/index';
import {
  ANOMALY_TYPES,
  actionTarget,
  batchActions,
  filterMerged,
  formatAmount,
  formatDwell,
  formatQuantity,
  matchBlacklistFlag,
  matchStockShortages,
  mergeCrossService,
  orderNumberDigest,
  summarizeMerged,
} from './anomaly-order-model';

/**
 * 1.1.1.2 异常订单队列的**跨服务聚合**与口径的单测。
 *
 * <p>本模块最容易做错的三件事，逐条钉在这里：
 *
 * <ol>
 *   <li><b>连接键错了会静默漏判</b>：黑名单按买家身份键（md5 三件套）连、
 *       库存按 skuId 连；键写错的表现是「一条异常都没有」，而不是报错。</li>
 *   <li><b>「查无库存账」被当成「库存为零」</b>：那是一次凭空捏造的判定，
 *       必须单列，不能混进「库存不足」。</li>
 *   <li><b>计数与列表不同源</b>：概览上的数字必须与清单是同一份集合算出来的。</li>
 * </ol>
 */

const ORDER_ID = '11111111-1111-1111-1111-111111111111';
const BUYER_KEY = 'a'.repeat(32);

function row(overrides: Partial<AnomalyOrderListRow> = {}): AnomalyOrderListRow {
  return {
    orderId: ORDER_ID,
    orderNo: 'SO-1',
    orderSource: 'AMAZON',
    shopCode: 'SHOP-1',
    platformCode: 'AMAZON',
    currency: 'USD',
    grandTotalAmount: 210,
    status: 'SUBMITTED',
    submittedAt: '2026-10-01T00:00:00Z',
    dwellSeconds: 3600,
    dwellSince: '2026-10-01T00:00:00Z',
    anomalyTypes: [],
    anomalyLabels: [],
    severity: 'LOW',
    recommendedActions: [],
    buyerKey: '',
    skuNeeds: [],
    lineCount: 0,
    totalQuantity: 0,
    ...overrides,
  } as AnomalyOrderListRow;
}

function flag(overrides: Partial<BuyerBlacklistOrderFlagView> = {}): BuyerBlacklistOrderFlagView {
  return {
    flagId: 'flag-1',
    buyerKey: BUYER_KEY,
    buyerName: '收件人',
    shopCode: 'SHOP-1',
    orderRef: '',
    entryId: 'entry-1',
    matchedScope: 'ALL_SHOPS',
    riskLevel: 'HIGH',
    recommendedAction: 'REVIEW',
    recommendedActionLabel: '人工复核',
    flaggedAt: '2026-10-01T00:00:00Z',
    ...overrides,
  } as BuyerBlacklistOrderFlagView;
}

function sellable(skuId: string, amount: number): AvailableQuantityRow {
  return {
    skuId,
    skuCode: `SKU-${skuId}`,
    skuName: '商品',
    onHand: amount,
    reserved: 0,
    available: amount,
    sellableBeforeBuffer: amount,
    safetyBuffer: 0,
    bufferScopeType: 'NONE',
    bufferScopeLabel: null,
    sellable: amount,
    bufferExceedsBase: false,
    bucketCount: 1,
    warehouseCount: 1,
    locationCount: 1,
    lotCount: null,
  } as AvailableQuantityRow;
}

describe('黑名单匹配', () => {
  it('买家身份键相同且订单引用相容时命中', () => {
    const target = row({ buyerKey: BUYER_KEY });
    expect(matchBlacklistFlag(target, [flag({ orderRef: 'SO-1' })])).not.toBeNull();
    expect(matchBlacklistFlag(target, [flag({ orderRef: ORDER_ID })])).not.toBeNull();
  });

  it('订单引用留空的买家级标记对同一买家的这一单同样成立', () => {
    const target = row({ buyerKey: BUYER_KEY });
    expect(matchBlacklistFlag(target, [flag({ orderRef: '' })])).not.toBeNull();
  });

  it('买家身份键不同就不命中（连接键错了会静默漏判）', () => {
    const target = row({ buyerKey: 'b'.repeat(32) });
    expect(matchBlacklistFlag(target, [flag({ orderRef: 'SO-1' })])).toBeNull();
  });

  it('订单引用指向别的单就不命中', () => {
    const target = row({ buyerKey: BUYER_KEY });
    expect(matchBlacklistFlag(target, [flag({ orderRef: 'SO-OTHER' })])).toBeNull();
  });

  it('没有买家键的订单匹配不上（不是「没有黑名单」，是判不了）', () => {
    expect(matchBlacklistFlag(row({ buyerKey: '' }), [flag()])).toBeNull();
  });
});

describe('库存缺口判定', () => {
  it('要的量超过可售量才算缺口', () => {
    const target = row({
      skuNeeds: [{ skuId: 'sku-1', skuCode: 'SKU-1', quantity: 5 }],
    });
    const map = new Map([['sku-1', sellable('sku-1', 3)]]);
    const result = matchStockShortages(target, map);
    expect(result.shortages).toHaveLength(1);
    expect(result.shortages[0]?.need).toBe(5);
    expect(result.shortages[0]?.sellable).toBe(3);
    expect(result.unknownSkus).toHaveLength(0);
  });

  it('可售量够就不算缺口', () => {
    const target = row({
      skuNeeds: [{ skuId: 'sku-1', skuCode: 'SKU-1', quantity: 2 }],
    });
    const map = new Map([['sku-1', sellable('sku-1', 3)]]);
    const result = matchStockShortages(target, map);
    expect(result.shortages).toHaveLength(0);
  });

  it('仓库查无此 SKU 的库存账判不了，而不是判成库存充足', () => {
    const target = row({
      skuNeeds: [{ skuId: 'sku-9', skuCode: 'SKU-9', quantity: 2 }],
    });
    const result = matchStockShortages(target, new Map());
    expect(result.shortages).toHaveLength(0);
    expect(result.unknownSkus).toEqual(['SKU-9']);
  });
});

describe('跨服务合并', () => {
  it('黑名单与库存两类被并进同一行', () => {
    const target = row({
      buyerKey: BUYER_KEY,
      anomalyTypes: ['ADDRESS_UNDELIVERABLE'],
      recommendedActions: ['OPEN_ORDER', 'ESCALATE_MANUAL'],
      skuNeeds: [{ skuId: 'sku-1', skuCode: 'SKU-1', quantity: 5 }],
    });
    const map = new Map([['sku-1', sellable('sku-1', 1)]]);
    const merged = mergeCrossService([target], [flag()], map);
    expect(merged[0]?.types).toContain('BLACKLIST_BUYER');
    expect(merged[0]?.types).toContain('INSUFFICIENT_STOCK');
    expect(merged[0]?.types).toContain('ADDRESS_UNDELIVERABLE');
    expect(merged[0]?.actions).toContain('OPEN_BLACKLIST');
    expect(merged[0]?.actions).toContain('OPEN_INVENTORY');
  });

  it('严重度按黑名单的风险等级抬升', () => {
    const target = row({ buyerKey: BUYER_KEY, anomalyTypes: ['BUYER_MESSAGE_PENDING'] });
    const merged = mergeCrossService(
      [target],
      [flag({ riskLevel: 'CRITICAL' })],
      new Map(),
    );
    expect(merged[0]?.severity).toBe('CRITICAL');
  });

  it('类型顺序恒定按蓝图的顺序，不随命中顺序漂移', () => {
    const target = row({
      buyerKey: BUYER_KEY,
      anomalyTypes: ['DUPLICATE_ORDER', 'ADDRESS_UNDELIVERABLE'],
    });
    const merged = mergeCrossService([target], [flag()], new Map());
    const positions = (merged[0]?.types ?? []).map((type) => ANOMALY_TYPES.indexOf(type as never));
    expect([...positions].sort((a, b) => a - b)).toEqual(positions);
  });

  it('没有跨服务信号时就是本服务侧那一行', () => {
    const target = row({ anomalyTypes: ['ADDRESS_UNDELIVERABLE'] });
    const merged = mergeCrossService([target], [], new Map());
    expect(merged[0]?.types).toEqual(['ADDRESS_UNDELIVERABLE']);
    expect(merged[0]?.blacklist).toBeNull();
    expect(merged[0]?.shortages).toHaveLength(0);
  });
});

describe('筛选与汇总', () => {
  const rows = mergeCrossService(
    [
      row({
        orderId: '11111111-1111-1111-1111-111111111111',
        orderNo: 'SO-A',
        shopCode: 'SHOP-1',
        buyerKey: BUYER_KEY,
        anomalyTypes: ['ADDRESS_UNDELIVERABLE'],
        dwellSeconds: 7200,
      }),
      row({
        orderId: '22222222-2222-2222-2222-222222222222',
        orderNo: 'SO-B',
        shopCode: 'SHOP-2',
        anomalyTypes: ['DUPLICATE_ORDER'],
        dwellSeconds: 360000,
      }),
    ],
    [flag({ orderRef: 'SO-A' })],
    new Map(),
  );

  it('按异常类型筛选', () => {
    expect(filterMerged(rows, { type: 'BLACKLIST_BUYER', shopCode: '', keyword: '', minDwellHours: 0 })).toHaveLength(1);
    expect(filterMerged(rows, { type: 'DUPLICATE_ORDER', shopCode: '', keyword: '', minDwellHours: 0 })).toHaveLength(1);
  });

  it('按店铺与关键字筛选', () => {
    expect(filterMerged(rows, { type: '', shopCode: 'SHOP-2', keyword: '', minDwellHours: 0 })).toHaveLength(1);
    expect(filterMerged(rows, { type: '', shopCode: '', keyword: 'SO-A', minDwellHours: 0 })).toHaveLength(1);
  });

  it('按停留时长筛选', () => {
    expect(filterMerged(rows, { type: '', shopCode: '', keyword: '', minDwellHours: 24 })).toHaveLength(1);
    expect(filterMerged(rows, { type: '', shopCode: '', keyword: '', minDwellHours: 0 })).toHaveLength(2);
  });

  it('汇总计数与清单同源，且翻页不变', () => {
    const summary = summarizeMerged(rows);
    expect(summary.total).toBe(2);
    expect(summary.byType['ADDRESS_UNDELIVERABLE']).toBe(1);
    expect(summary.byType['BLACKLIST_BUYER']).toBe(1);
    expect(summary.byType['INSUFFICIENT_STOCK']).toBe(0);
    expect(summary.oldestDwellSeconds).toBe(360000);
  });
});

describe('文案与导航', () => {
  it('停留时长按人话给', () => {
    expect(formatDwell(30)).toBe('不到 1 分钟');
    expect(formatDwell(120)).toBe('2 分钟');
    expect(formatDwell(3600)).toBe('1 小时');
    expect(formatDwell(5400)).toBe('1 小时 30 分钟');
    expect(formatDwell(86400)).toBe('1 天');
    expect(formatDwell(90000)).toBe('1 天 1 小时');
    expect(formatDwell(-1)).toBe('—');
  });

  it('金额与数量按原样给，不在前端参与计算', () => {
    expect(formatAmount('USD', 210)).toBe('USD 210.00');
    expect(formatAmount('', null)).toBe('—');
    expect(formatQuantity(2)).toBe('2');
    expect(formatQuantity(1.5)).toBe('1.50');
    expect(formatQuantity(null)).toBe('—');
  });

  it('建议动作指向归属系统的真实页面', () => {
    expect(actionTarget('OPEN_ORDER', ORDER_ID)).toBe(`/orders/${ORDER_ID}`);
    expect(actionTarget('ESCALATE_MANUAL')).toBe('/compliance/intercept');
    expect(actionTarget('TRANSFER_AFTERSALES')).toBe('/aftersales/intake');
    expect(actionTarget('QUERY_PLATFORM')).toBe('/channels/writeback');
    expect(actionTarget('OPEN_BLACKLIST')).toBe('/compliance/buyer-blacklist');
    expect(actionTarget('OPEN_INVENTORY')).toBe('/inventory');
    expect(actionTarget('MARK_IGNORED')).toBe('');
  });

  it('批量动作沿用后端能力声明，「标记忽略」没有入口就不能渲染成能点的按钮', () => {
    const caps = {
      actions: [
        {
          code: 'ESCALATE_MANUAL',
          label: '转人工（异常拦截处置）',
          ownerModule: '09 异常拦截',
          available: true,
          impact: '',
        },
        {
          code: 'MARK_IGNORED',
          label: '标记忽略',
          ownerModule: '09 订单处理页',
          available: false,
          impact: '归属系统当前没有「忽略一条异常」的写端点',
        },
      ],
    } as unknown as AnomalyOrderCapabilities;
    const actions = batchActions(caps);
    expect(actions).toHaveLength(2);
    const ignored = actions.find((action) => action.code === 'MARK_IGNORED');
    expect(ignored?.available).toBe(false);
    expect(ignored?.impact).toContain('没有');
  });

  it('选中订单号可以交接给归属系统', () => {
    const merged = mergeCrossService([row({ orderNo: 'SO-A' })], [], new Map());
    expect(orderNumberDigest(merged)).toBe('SO-A');
  });
});
