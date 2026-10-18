import { describe, expect, it } from 'vitest';
import type {
  WritebackBatchPreview,
  WritebackBatchView,
  WritebackItemView,
  WritebackTargetView,
} from '../../../../../packages/api-contracts/src/index';
import {
  BATCH_HARD_LIMIT,
  batchLine,
  batchScopeText,
  buildBatchInput,
  emptyBatchForm,
  itemResultText,
  itemTone,
  issueLabel,
  issueTone,
  localBlockReason,
  outcomeTone,
  previewHeadline,
  price,
  quantity,
  targetSummaryRows,
  unknownHint,
  type BatchForm,
} from './writeback-batch-model';

/**
 * 2.5.2.1 前端数据层的口径断言。
 *
 * <p>这些函数看着只是格式化，但每一处都对应蓝图加粗句「**部分失败要如实回报**」的一次具体决策：
 * 把「没发出去的」和「平台拒绝的」分开说、把 UNKNOWN 从失败里摘出来、把范围写在脸上。
 * 措辞一旦被改成「失败」，界面就会诱导出重试 —— 那是本模块最贵的错误方向。
 */

function target(over: Partial<WritebackTargetView> = {}): WritebackTargetView {
  return {
    id: '11111111-1111-1111-1111-111111111111',
    shopId: '22222222-2222-2222-2222-222222222222',
    shopName: '美国店',
    platformCode: 'AMAZON',
    targetType: 'LISTING',
    externalRef: 'AMZ-ITEM-1',
    listingRef: 'L-1',
    skuCode: 'SKU-1',
    currentStock: 10,
    currentPrice: 19.99,
    currency: 'USD',
    status: 'ACTIVE',
    blockedReason: '',
    createdAt: '2026-10-18T00:00:00Z',
    updatedAt: '2026-10-18T00:00:00Z',
    ...over,
  };
}

function item(over: Partial<WritebackItemView> = {}): WritebackItemView {
  return {
    position: 1,
    targetId: '11111111-1111-1111-1111-111111111111',
    shopId: '22222222-2222-2222-2222-222222222222',
    targetType: 'LISTING',
    externalRef: 'AMZ-ITEM-1',
    listingRef: 'L-1',
    skuCode: 'SKU-1',
    itemResult: 'QUEUED',
    operationId: '33333333-3333-3333-3333-333333333333',
    operationStatus: 'PENDING',
    idempotencyKey: 'wbo-abc',
    reason: '',
    shippedAt: null,
    stock: 42.5,
    price: null,
    currency: null,
    trackingNumber: null,
    carrierCode: null,
    createdAt: '2026-10-18T00:00:00Z',
    ...over,
  };
}

describe('结局色调：部分成功必须一眼可见，结果未知不是危险色', () => {
  it('PARTIAL 与 SUCCEEDED 不同色 —— 否则最该看的批次混在历史里', () => {
    expect(outcomeTone('PARTIAL')).toBe('warning');
    expect(outcomeTone('SUCCEEDED')).toBe('success');
    expect(outcomeTone('FAILED')).toBe('danger');
    expect(outcomeTone('IN_PROGRESS')).toBe('info');
  });

  it('UNKNOWN 是提醒色不是危险色：并进 danger 就是在暗示「重试」', () => {
    expect(itemTone(item({ operationStatus: 'UNKNOWN' }))).toBe('warning');
    expect(itemTone(item({ operationStatus: 'FAILED' }))).toBe('danger');
    // 提醒色 ≠ 失败色，这一点必须被钉住 —— 它是资损的分水岭。
    expect(itemTone(item({ operationStatus: 'UNKNOWN' }))).not.toBe(
      itemTone(item({ operationStatus: 'FAILED' })),
    );
  });

  it('结果未知的那条说的是「结果未知」而不是「失败」，并提示先核验再决定重发', () => {
    const unknown = item({ operationStatus: 'UNKNOWN' });
    expect(itemResultText(unknown)).toBe('结果未知');
    expect(unknownHint).toContain('核验');
    expect(unknownHint).toContain('不要直接重试');
  });
});

describe('逐条结果：「没发出去的」不等于「平台拒绝的」', () => {
  it('REJECTED 说「没发出去」且是中性色，不说失败', () => {
    const rejected = item({ itemResult: 'REJECTED', operationId: null, reason: '目标已被封禁' });
    expect(itemResultText(rejected)).toBe('没发出去');
    expect(itemTone(rejected)).toBe('neutral');
    // 生成前被拦下的那条压根没发往平台 —— 说成「失败」会让人去平台侧查一条不存在的记录。
    expect(itemResultText(rejected)).not.toBe('平台已拒绝');
  });

  it('已发出的按平台真实状态说话', () => {
    expect(itemResultText(item({ operationStatus: 'SUCCEEDED' }))).toBe('平台已执行');
    expect(itemResultText(item({ operationStatus: 'FAILED' }))).toBe('平台已拒绝');
    expect(itemResultText(item({ operationStatus: 'PENDING' }))).toBe('待发送');
  });
});

describe('请求体组装：范围与优先级必须与后端 resolve 一致', () => {
  it('勾选了目标就只按勾选发，不夹带筛选条件', () => {
    const form: BatchForm = {
      ...emptyBatchForm(),
      shopId: '22222222-2222-2222-2222-222222222222',
      keyword: 'AMZ',
      selected: [target()],
      stock: '42.500',
    };
    const input = buildBatchInput(form);
    expect(input.targetIds).toEqual(['11111111-1111-1111-1111-111111111111']);
    // 勾选优先：筛选条件只是解析候选集用的，同时发出去会让后端取到与界面不同的范围。
    expect(input.filter).toBeNull();
    expect(input.shopId).toBe('22222222-2222-2222-2222-222222222222');
    expect(batchScopeText(form)).toBe('按勾选的 1 条执行');
  });

  it('没勾选时按筛选条件发，filter 原样带上', () => {
    const form: BatchForm = {
      ...emptyBatchForm(),
      targetType: 'LISTING',
      status: 'ACTIVE',
      keyword: ' AMZ-ITEM ',
      stock: '3',
    };
    const input = buildBatchInput(form);
    expect(input.targetIds).toBeNull();
    expect(input.filter).toEqual({
      shopId: null,
      targetType: 'LISTING',
      status: 'ACTIVE',
      keyword: 'AMZ-ITEM',
    });
    expect(batchScopeText(form)).toBe('按筛选条件命中的全部目标执行');
  });

  it('DELIST 不带任何数值参数 —— 下架没有数量也没有金额', () => {
    const input = buildBatchInput({ ...emptyBatchForm(), kind: 'DELIST', selected: [target()] });
    expect(input.parameters.stock).toBeNull();
    expect(input.parameters.price).toBeNull();
    expect(input.parameters.currency).toBeNull();
  });

  it('币种原样大写，空值给 null 而不是空串（空串会被 @Pattern 拒成 400）', () => {
    const delist = buildBatchInput({ ...emptyBatchForm(), kind: 'DELIST', selected: [target()] });
    expect(delist.parameters.currency).toBeNull();
    const priced = buildBatchInput({
      ...emptyBatchForm(),
      kind: 'UPDATE_PRICE',
      selected: [target()],
      price: '25',
      currency: 'usd',
    });
    expect(priced.parameters.currency).toBe('USD');
  });

  it('逐目标覆盖按 targetId 平铺，空覆盖不发（发了等于凭空多一条）', () => {
    const base = { ...emptyBatchForm(), kind: 'SHIP_UPDATE' as const, selected: [target()] };
    expect(buildBatchInput({ ...base, trackingNumber: 'TRACK-1' }).parameters.perTarget).toBeNull();
    const withOverride = buildBatchInput({
      ...base,
      trackingNumber: 'TRACK-1',
      perTarget: {
        '11111111-1111-1111-1111-111111111111': {
          stock: '',
          price: '',
          trackingNumber: 'TRACK-2-INDIVIDUAL',
          carrierCode: 'FEDEX',
          shippedAt: '',
        },
      },
    });
    expect(withOverride.parameters.perTarget).toEqual([
      {
        targetId: '11111111-1111-1111-1111-111111111111',
        stock: null,
        price: null,
        trackingNumber: 'TRACK-2-INDIVIDUAL',
        carrierCode: 'FEDEX',
        shippedAt: null,
      },
    ]);
  });

  it('填错的数量给 null 而不是 0 —— 塞 0 是「合法地把库存改成 0」', () => {
    const input = buildBatchInput({
      ...emptyBatchForm(),
      selected: [target()],
      stock: '不是数字',
    });
    expect(input.parameters.stock).toBeNull();
  });
});

describe('发货时刻按本地时区解析，不静默偏掉时差', () => {
  it('datetime-local 的字符串按浏览器本地时区转成 UTC 瞬间', () => {
    const input = buildBatchInput({
      ...emptyBatchForm(),
      kind: 'SHIP_UPDATE',
      selected: [target({ targetType: 'SHIPMENT' })],
      trackingNumber: 'TRACK-1',
      shippedAt: '2026-03-04T05:06',
    });
    const sent = new Date(input.parameters.shippedAt as string);
    // 转出的瞬间必须等于「本地 05:06」，而不是把这个字符串当 UTC 直接发出去。
    expect(sent.getHours()).toBe(5);
    expect(sent.getMinutes()).toBe(6);
  });
});

describe('本地闸门：只拦范围缺失与超上限，其余交给后端逐条报', () => {
  it('既没勾选也没筛选条件 → 拦住（一次误传就是全租户范围的事故）', () => {
    expect(localBlockReason(emptyBatchForm())).toContain('请先勾选');
  });

  it('给了任一筛选条件即放行', () => {
    expect(localBlockReason({ ...emptyBatchForm(), keyword: 'AMZ' })).toBeNull();
    expect(localBlockReason({ ...emptyBatchForm(), shopId: 'x' })).toBeNull();
    expect(localBlockReason({ ...emptyBatchForm(), selected: [target()] })).toBeNull();
  });

  it('超过单次上限 → 拦住并说清怎么缩小范围', () => {
    const many = Array.from({ length: BATCH_HARD_LIMIT + 1 }, (_, i) =>
      target({ id: `00000000-0000-0000-0000-${String(i).padStart(12, '0')}` }),
    );
    expect(localBlockReason({ ...emptyBatchForm(), selected: many })).toContain('分批执行');
    expect(
      localBlockReason({ ...emptyBatchForm(), selected: many.slice(0, BATCH_HARD_LIMIT) }),
    ).toBeNull();
  });
});

describe('试算与批次摘要', () => {
  it('试算摘要把「可发出」排在最前，被拦下的紧随其后', () => {
    const preview = {
      kind: 'UPDATE_PRICE',
      matched: 10,
      applicable: 7,
      blocked: 2,
      missingParameters: 1,
      issues: [],
      parameters: null,
    } as unknown as WritebackBatchPreview;
    expect(previewHeadline(preview)).toBe('命中 10 条 · 可发出 7 条 · 会被拦下 3 条');
  });

  it('问题 code 翻成人话，且区分「必被拦下」与「只是提醒」', () => {
    const blocked = { targetId: 't', externalRef: 'A', code: 'TARGET_BLOCKED', message: 'x' };
    const currency = { targetId: 't', externalRef: 'A', code: 'CURRENCY_MISMATCH', message: 'x' };
    expect(issueLabel(blocked)).toBe('目标已封禁');
    expect(issueLabel(currency)).toBe('币种不一致');
    expect(issueTone(blocked)).toBe('danger');
    expect(issueTone(currency)).toBe('warning');
  });

  it('批次一行把「部分成功」与逐条计数都写出来，不能只写「已结束」', () => {
    const batch = {
      kind: 'UPDATE_STOCK',
      summary: {
        outcome: 'PARTIAL',
        selected: 5,
        queued: 4,
        rejected: 1,
        succeeded: 2,
        failed: 1,
        unknown: 1,
        pending: 0,
      },
    } as unknown as WritebackBatchView;
    const line = batchLine(batch);
    expect(line).toContain('批量改库存');
    expect(line).toContain('部分成功');
    expect(line).toContain('被拒 1');
    expect(line).toContain('失败 1');
    expect(line).toContain('未知 1');
  });

  it('零失败零被拒的行不出现空字段（没有失败就别显示「失败 0」）', () => {
    const batch = {
      kind: 'DELIST',
      summary: {
        outcome: 'IN_PROGRESS',
        selected: 3,
        queued: 3,
        rejected: 0,
        succeeded: 0,
        failed: 0,
        unknown: 0,
        pending: 3,
      },
    } as unknown as WritebackBatchView;
    expect(batchLine(batch)).toBe('批量下架 · 进行中 · 选中 3 · 已发出 3 · 被拒 0');
  });
});

describe('金额与数量原样展示，不做前端算术', () => {
  it('价格必须带币种才读得懂；没币种时明说无币种而不是裸数字', () => {
    expect(price(19.99, 'USD')).toBe('19.99 USD');
    expect(price(19.99, null)).toContain('无币种');
    expect(price(null, 'USD')).toBe('—');
  });

  it('数量原样展示，空值是「—」而不是 0', () => {
    expect(quantity(null)).toBe('—');
    expect(quantity('')).toBe('—');
    expect(quantity(0)).toBe('0');
    expect(quantity(42.5)).toBe('42.5');
  });

  it('候选池汇总原样给出，不受分页牵动', () => {
    expect(
      targetSummaryRows({
        total: 12,
        activeCount: 10,
        blockedCount: 2,
        listingCount: 8,
        shipmentCount: 4,
        shops: 3,
      }),
    ).toEqual([
      { label: '可回写目标', value: 12 },
      { label: '可发起回写', value: 10 },
      { label: '已封禁', value: 2 },
      { label: '平台商品', value: 8 },
      { label: '平台订单', value: 4 },
      { label: '涉及店铺', value: 3 },
    ]);
    expect(targetSummaryRows(undefined)).toEqual([]);
  });
});
