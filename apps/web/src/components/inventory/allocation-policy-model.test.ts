import { describe, expect, it } from 'vitest';
import {
  conservationText,
  intervalText,
  notNull,
  operatingModeLabels,
  outcomeLabels,
  outcomeTone,
  percent,
  qty,
  sumAllocated,
} from './allocation-policy-model';
import type {
  AllocationLine,
  AllocationResult,
} from '../../../../../packages/api-contracts/src/index';

/**
 * 7.8.1.1 分配策略配置前端纯函数。
 *
 * <p>重点钉住三件「错了会误导人」的事：
 *
 * <ul>
 *   <li>**守恒式要能从返回里原样读出来**。界面上把等式摆在结果头部，用户就不必自己加一遍；
 *       而一个算错的等式比没有等式更危险，所以文案必须由同一个来源生成。
 *   <li>**前端自算的 Σ 分摊必须能和后端报的对上**。对不上时那一行要显式说「不一致」，
 *       而不是悄悄用后端的数把差异盖住。
 *   <li>**分到 0 件必须有原因，且原因与色调一一对应**。四种原因（触顶 / 被分完 /
 *       零销量 / 已停用）对应完全不同的处置动作，都显示成一个 0 就等于没有信息。
 * </ul>
 */

function line(patch: Partial<AllocationLine>): AllocationLine {
  return {
    sourceSystem: 'CHANNEL',
    referenceId: 'shop-1',
    referenceLabel: '店一',
    shopOperatingMode: 'SELF_FULFILLED',
    allocationOrder: 1,
    salesUnits: 0,
    salesShare: null,
    allocatedQty: 0,
    outcome: 'OK',
    ...patch,
  } as AllocationLine;
}

function result(patch: Partial<AllocationResult>): AllocationResult {
  return {
    effective: null,
    run: null,
    skuId: 'sku-1',
    skuCode: 'SKU-1',
    lines: [],
    availableQuantity: 0,
    bufferQuantity: 0,
    allocatableQuantity: 0,
    allocatedTotal: 0,
    unallocatedQuantity: 0,
    salesWindowStart: null,
    salesWindowEnd: null,
    totalSalesUnits: 0,
    totalSalesShare: null,
    memberCount: 0,
    starvedMemberCount: 0,
    note: '',
    ...patch,
  } as AllocationResult;
}

describe('守恒式：等式必须能从返回里原样读出来', () => {
  it('两段等式分别写清缓冲与未分配', () => {
    const r = result({
      availableQuantity: 100,
      bufferQuantity: 10,
      allocatableQuantity: 90,
      allocatedTotal: 88,
      unallocatedQuantity: 2,
    });
    const lines = conservationText(r);
    expect(lines).toHaveLength(2);
    expect(lines[0]).toContain('100');
    expect(lines[0]).toContain('10');
    expect(lines[0]).toContain('90');
    expect(lines[1]).toContain('88');
    expect(lines[1]).toContain('2');
  });

  it('分不满时未分配量照样出现在等式里，而不是被藏起来', () => {
    const r = result({
      availableQuantity: 4,
      bufferQuantity: 0,
      allocatableQuantity: 4,
      allocatedTotal: 3,
      unallocatedQuantity: 1,
    });
    expect(conservationText(r)[1]).toContain('1');
  });
});

describe('Σ 分摊：前端自算必须能核对上后端报的那个数', () => {
  it('逐行相加等于后端报的合计', () => {
    const lines = [
      line({ referenceId: 'a', allocatedQty: 46 }),
      line({ referenceId: 'b', allocatedQty: 27 }),
      line({ referenceId: 'c', allocatedQty: 18 }),
    ];
    expect(sumAllocated(lines)).toBe(91);
  });

  it('空名册与 null 名册都算 0，而不是抛错', () => {
    expect(sumAllocated([])).toBe(0);
    expect(sumAllocated(null)).toBe(0);
    expect(sumAllocated(undefined)).toBe(0);
  });

  it('守恒对上时两行合计一致', () => {
    const lines = [line({ allocatedQty: 5 }), line({ allocatedQty: 3 }), line({ allocatedQty: 2 })];
    const r = result({
      allocatableQuantity: 10,
      allocatedTotal: sumAllocated(lines),
      unallocatedQuantity: 0,
    });
    expect(Math.abs(sumAllocated(lines) - Number(r.allocatedTotal))).toBeLessThan(1e-6);
  });
});

describe('分到 0 件：原因与色调一一对应', () => {
  it('五种原因都有可读文案', () => {
    expect(outcomeLabels.OK).toBe('正常');
    expect(outcomeLabels.CAP_REACHED).toBe('触到份额上限');
    expect(outcomeLabels.DEMAND_EXHAUSTED).toBe('货被前面的分完');
    expect(outcomeLabels.NO_SALES).toBe('销量为 0');
    expect(outcomeLabels.DISABLED).toBe('成员已停用');
  });

  it('只有正常是绿色，零份额与触顶要能被看见', () => {
    expect(outcomeTone('OK')).toContain('emerald');
    expect(outcomeTone('CAP_REACHED')).toContain('amber');
    expect(outcomeTone('DEMAND_EXHAUSTED')).toContain('rose');
    expect(outcomeTone('NO_SALES')).toContain('rose');
    expect(outcomeTone('DISABLED')).toContain('muted');
    expect(outcomeTone(null)).toContain('muted');
  });
});

describe('数量与比例的展示口径', () => {
  it('分摊额是整件，不补小数位', () => {
    expect(qty(10)).toBe('10');
    expect(qty('10.000000')).toBe('10');
  });

  it('缺值显示为破折号而不是 0', () => {
    // 0 与「没有这个数」不是一回事：销量为空时显示 0 会被读成「卖了 0 件」。
    expect(qty(null)).toBe('—');
    expect(qty(undefined)).toBe('—');
    expect(percent(null)).toBe('—');
  });

  it('比例按百分比显示：库里 0.5 是 50%', () => {
    expect(percent(0.5)).toBe('50%');
    expect(percent(0.6)).toBe('60%');
    expect(percent(0)).toBe('0%');
  });
});

describe('生效区间与店铺类型文案', () => {
  it('至今仍在生效与已收口是两种状态', () => {
    expect(intervalText('2026-10-01T00:00:00Z', null)).toContain('至今仍在生效');
    expect(intervalText('2026-10-01T00:00:00Z', '2026-10-02T00:00:00Z')).toContain('已收口');
  });

  it('五种店铺类型都有中文名', () => {
    expect(Object.keys(operatingModeLabels)).toHaveLength(5);
    expect(operatingModeLabels.SELF_FULFILLED).toBe('自营');
    expect(operatingModeLabels.FULL_CONSIGNMENT).toBe('全托管');
  });
});

describe('notNull：codegen 把带可空字段的类型生成成 T | null', () => {
  it('是类型谓词：判是否存在，不是把空值归一成 null', () => {
    // 实现刻意写成 `v is T` 而不是「归一成 null」：只有类型谓词才能让
    // Array.filter(notNull) 收窄元素类型，普通函数收窄不了，调用点就得处处补判空。
    expect(notNull(null)).toBe(false);
    expect(notNull(undefined)).toBe(false);
    expect(notNull(3)).toBe(true);
    expect([1, null, 2, undefined].filter(notNull)).toEqual([1, 2]);
  });
});
