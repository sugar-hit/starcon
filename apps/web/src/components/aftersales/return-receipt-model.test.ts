import { describe, expect, it } from 'vitest';
import {
  bothDimensionsJudged,
  isPostedToPendingInspection,
  postBlockedReason,
  PENDING_INSPECTION_LABEL,
  receiptSellableDelta,
  sortEvents,
  unavailableActions,
} from './return-receipt-model';

/**
 * 11.2.2.1 退货入库的前端纯函数断言。
 *
 * <p>这些是**页面自己**要对硬规则负的那部分责任：后端已经用 CHECK 钉死了，
 * 但页面一旦自己推断出一个「已转可售」的读法，那道闸就等于没有 ——
 * 所以前端这一层也要实跑断言。
 */

describe('硬规则 1：退回商品默认进待检，绝不进可售', () => {
  it('可售增量恒归零 —— 哪怕后端漏回了一个非 0 值', () => {
    expect(receiptSellableDelta({ sellableQtyDelta: 0 })).toBe(0);
    expect(receiptSellableDelta({ sellableQtyDelta: 5 })).toBe(0);
    expect(receiptSellableDelta({ sellableQtyDelta: -3 })).toBe(0);
    expect(receiptSellableDelta({ sellableQtyDelta: null })).toBe(0);
    expect(receiptSellableDelta({})).toBe(0);
    // NaN / Infinity 也不能显示出去
    expect(receiptSellableDelta({ sellableQtyDelta: Number.NaN })).toBe(0);
  });

  it('已入库且每条分录都待检、可售增量为 0 才算「已入待检」', () => {
    // ⚠️ 断言只关心两列，但契约里的 ReturnInventoryPosting 有 16 个字段 ——
    //   填全字段会让「这两列变了会怎样」这件事淹没在一堆样板里，
    //   所以显式只构造关心的两列再断言，类型上用 never 过（只在本文件内用）。
    const posting = {
      inventoryState: 'PENDING_INSPECTION',
      sellableQtyDelta: 0,
    };
    expect(
      isPostedToPendingInspection({ receiptStatus: 'POSTED', postings: [posting as never] }),
    ).toBe(true);
    // 还没入库
    expect(
      isPostedToPendingInspection({ receiptStatus: 'DRAFT', postings: [posting as never] }),
    ).toBe(false);
    // ⚠️ 任何一条分录不是待检 / 可售增量非 0，都不能算「已入待检」——
    //   那是有人绕过了接口改了库存，页面必须不认。
    expect(
      isPostedToPendingInspection({
        receiptStatus: 'POSTED',
        postings: [{ ...posting, inventoryState: 'SELLABLE' } as never],
      }),
    ).toBe(false);
    expect(
      isPostedToPendingInspection({
        receiptStatus: 'POSTED',
        postings: [{ ...posting, sellableQtyDelta: 1 } as never],
      }),
    ).toBe(false);
    expect(isPostedToPendingInspection({ receiptStatus: 'POSTED', postings: [] })).toBe(true);
    expect(isPostedToPendingInspection({ receiptStatus: 'POSTED', postings: null })).toBe(true);
  });

  it('待检标签是固定文案，页面渲染不出第二个取值', () => {
    expect(PENDING_INSPECTION_LABEL).toBe('待检');
  });
});

describe('硬规则 2：外包装与商品状态是两个独立维度', () => {
  it('两个维度都已判才算判定完成', () => {
    expect(bothDimensionsJudged({ packagingCondition: 'INTACT', goodsCondition: 'UNOPENED' })).toBe(
      true,
    );
    // ⚠️ 「外箱损毁 + 商品完好」是蓝图 11.2.3.1 点名的合法组合，必须能表达
    expect(
      bothDimensionsJudged({ packagingCondition: 'DESTROYED', goodsCondition: 'OPENED_UNDAMAGED' }),
    ).toBe(true);
    // 任一维度未判定都不算
    expect(
      bothDimensionsJudged({ packagingCondition: 'NOT_INSPECTED', goodsCondition: 'UNOPENED' }),
    ).toBe(false);
    expect(
      bothDimensionsJudged({ packagingCondition: 'INTACT', goodsCondition: 'NOT_INSPECTED' }),
    ).toBe(false);
    expect(bothDimensionsJudged({ packagingCondition: null, goodsCondition: null })).toBe(false);
  });

  it('「不适用」不算「未判定」', () => {
    // ⚠️ 散装 / 无包装无配件的 SKU 本来就没有这个维度，
    //   合成一个值会让入库闸门把「不适用」当成「还没判」一直拦住。
    expect(
      bothDimensionsJudged({ packagingCondition: 'MISSING', goodsCondition: 'NOT_APPLICABLE' }),
    ).toBe(true);
    // 但外包装的「无」不是「不适用」——外包装这个维度对任何实物都成立
    expect(
      bothDimensionsJudged({ packagingCondition: 'MISSING', goodsCondition: 'UNOPENED' }),
    ).toBe(true);
  });
});

describe('入库闸门：禁用时必须给理由', () => {
  const lines = (n: number, judged = true) =>
    Array.from({ length: n }, () =>
      judged
        ? { packagingCondition: 'INTACT', goodsCondition: 'UNOPENED' }
        : { packagingCondition: 'NOT_INSPECTED', goodsCondition: 'UNOPENED' },
    );

  it('草稿且全部已判 → 可点（返回 null）', () => {
    expect(postBlockedReason({ receiptStatus: 'DRAFT', lines: lines(2) })).toBeNull();
  });

  it('已入库 / 已撤销 → 不可点且理由说清归属', () => {
    expect(postBlockedReason({ receiptStatus: 'POSTED', lines: lines(2) })).toContain('11.2.3.2');
    expect(postBlockedReason({ receiptStatus: 'CANCELLED', lines: lines(2) })).toContain('已撤销');
  });

  it('空单 → 不可点', () => {
    expect(postBlockedReason({ receiptStatus: 'DRAFT', lines: [] })).toContain('没有任何明细行');
  });

  it('有未判定行 → 不可点且报出还差几行', () => {
    const reason = postBlockedReason({
      receiptStatus: 'DRAFT',
      lines: [
        { packagingCondition: 'INTACT', goodsCondition: 'UNOPENED' },
        { packagingCondition: 'NOT_INSPECTED', goodsCondition: 'UNOPENED' },
        { packagingCondition: 'INTACT', goodsCondition: 'NOT_INSPECTED' },
      ],
    });
    expect(reason).toContain('2 行');
  });

  it('缺字段（lines 为 null）也按空单处理，不抛异常', () => {
    expect(postBlockedReason({ receiptStatus: 'DRAFT', lines: null })).toContain('没有任何明细行');
  });
});

describe('刻意不可点的动作必须带归属模块', () => {
  it('RESELL / INSPECT / ALLOCATE 一律 allowed=false 且有 ownerModule', () => {
    const actions = [
      { code: 'POST', label: '入库', allowed: true, ownerModule: null },
      { code: 'RESELL', label: '转可售', allowed: false, ownerModule: '11.2.3.2' },
      { code: 'INSPECT', label: '质检判定', allowed: false, ownerModule: '11.2.3.1' },
      { code: 'ALLOCATE', label: '分配到订单行', allowed: false, ownerModule: '11.2.2.2' },
    ] as never[];
    const off = unavailableActions(actions);
    expect(off.map((a) => a.code)).toEqual(['RESELL', 'INSPECT', 'ALLOCATE']);
    for (const a of off) {
      expect(a.ownerModule, `${a.code} 必须带归属模块`).toBeTruthy();
    }
    expect(unavailableActions(null)).toEqual([]);
  });
});

describe('留痕按发生时刻排序', () => {
  it('同一时刻按原顺序稳定排序（不重新打乱）', () => {
    const events = [
      { id: 'b', occurredAt: '2026-03-01T00:00:00Z' },
      { id: 'a', occurredAt: '2026-03-01T00:00:00Z' },
      { id: 'c', occurredAt: '2026-02-28T00:00:00Z' },
    ] as never[];
    expect(sortEvents(events).map((e) => e.id)).toEqual(['c', 'b', 'a']);
    expect(sortEvents(null)).toEqual([]);
  });
});
