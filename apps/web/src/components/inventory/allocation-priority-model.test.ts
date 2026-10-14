import { describe, expect, it } from 'vitest';
import type { PriorityAllocationResult } from '../../../../../packages/api-contracts/src/index';
import {
  conservationText,
  floorModeLabels,
  floorStatusLabels,
  floorStatusTone,
  floorText,
  intervalText,
  notNull,
  percent,
  qty,
  shortfallReasonLabels,
  suppressedBy,
  sumAllocated,
  suppressionReasonLabels,
} from './allocation-priority-model';

/**
 * 7.8.1.2 前端模型的纯函数单测。
 *
 * <p>这些函数是**展示口径**：后端算的是「有没有货、给了多少、为什么」，
 * 界面算的是「这句话怎么写、这个数要不要被看见」。后者一旦与前者脱节，
 * 就会出现「数据库里明明兑现了保底，界面上却显示欠账」这种查不出来的错。
 *
 * <p>契约类型由 codegen 生成（`packages/api-contracts`），本文件不手写联合类型。
 */

function line(over: Partial<Parameters<typeof floorText>[0]> = {}) {
  return {
    floorMode: 'QUANTITY',
    floorQuantity: 6,
    floorRatio: 0,
    safetyQuantity: 0,
    ...over,
  };
}

describe('qty', () => {
  it('按整件显示，不补小数位', () => {
    expect(qty(6)).toBe('6');
    expect(qty(6.0)).toBe('6');
  });

  it('null / undefined / NaN 显示破折号而不是 0', () => {
    // 0 与「没有值」在界面上必须分得开：把「无数据」显示成 0 会让人以为货是 0 件。
    expect(qty(null)).toBe('—');
    expect(qty(undefined)).toBe('—');
    expect(qty('abc')).toBe('—');
    expect(qty(0)).toBe('0');
  });

  it('接受契约里的 string 形态（BigDecimal 序列化可能是字符串）', () => {
    expect(qty('12')).toBe('12');
  });
});

describe('percent', () => {
  it('库里存 0.2，界面显示 20%', () => {
    expect(percent(0.2)).toBe('20%');
    expect(percent(1)).toBe('100%');
    expect(percent(0)).toBe('0%');
  });

  it('0.5 不能被读成 0.5%', () => {
    expect(percent(0.5)).not.toContain('0.5%');
  });

  it('null 显示破折号', () => {
    expect(percent(null)).toBe('—');
  });
});

describe('floorText', () => {
  it('按件数保底显示件数', () => {
    expect(floorText(line())).toBe('6 件');
  });

  it('按比例保底显示百分比而不是裸数字', () => {
    // 保底 6 件与保底 6% 是两件完全不同的事，而它们在库里都只是一个数字。
    expect(floorText(line({ floorMode: 'RATIO', floorRatio: 0.2 }))).toBe('可分配量的 20%');
  });

  it('安全垫为 0 时不出现「+ 安全垫 0 件」这种噪声', () => {
    expect(floorText(line({ safetyQuantity: 0 }))).toBe('6 件');
  });

  it('安全垫大于 0 时显式叠加', () => {
    expect(floorText(line({ safetyQuantity: 4 }))).toBe('6 件 + 安全垫 4 件');
  });
});

describe('conservationText', () => {
  const result = {
    availableQuantity: 100,
    bufferQuantity: 10,
    allocatableQuantity: 90,
    floorPool: 20,
    residualPool: 70,
    allocatedTotal: 80,
    unallocatedQuantity: 10,
  } as unknown as PriorityAllocationResult;

  it('摆三段：两条 7.8.1.1 的守恒式 + 本模块新增的保底池那一条', () => {
    const lines = conservationText(result);
    expect(lines).toHaveLength(3);
    expect(lines[0]).toContain('缓冲');
    expect(lines[2]).toContain('未分配');
  });

  it('第三段是保底池 + 余量池 = 可分配量 —— 保底是在分摊之前先发的', () => {
    // 少这一段时，「保底把货吃穿了」在界面上完全看不出来。
    expect(conservationText(result)[1]).toContain('保底池');
    expect(conservationText(result)[1]).toContain('余量池');
  });
});

describe('suppressedBy', () => {
  it('基线比最终多出来的件数就是「被压低多少」', () => {
    expect(suppressedBy({ baseAllocatedQty: 10, allocatedQty: 4 })).toBe(6);
  });

  it('没被压低时是 0', () => {
    expect(suppressedBy({ baseAllocatedQty: 6, allocatedQty: 6 })).toBe(0);
  });

  it('多分了也夹到 0 —— 界面上的「被压低 -3 件」只会让人以为算错了', () => {
    expect(suppressedBy({ baseAllocatedQty: 4, allocatedQty: 6 })).toBe(0);
  });

  it('缺字段时是 0 而不是 NaN', () => {
    expect(suppressedBy({ baseAllocatedQty: null, allocatedQty: null })).toBe(0);
  });
});

describe('sumAllocated', () => {
  it('逐行加总，供前端与后端的 allocatedTotal 对照', () => {
    expect(sumAllocated([{ allocatedQty: 4 }, { allocatedQty: 6 }])).toBe(10);
  });

  it('空列表与 null 都是 0', () => {
    expect(sumAllocated([])).toBe(0);
    expect(sumAllocated(null)).toBe(0);
  });
});

describe('词表', () => {
  it('四种保底没兑现的原因都有可读文案', () => {
    // 枚举而非自由文本：处置动作完全不同，不能都显示成一个 0。
    expect(Object.keys(shortfallReasonLabels)).toHaveLength(4);
    expect(shortfallReasonLabels.POOL_EXHAUSTED).toBeTruthy();
    expect(shortfallReasonLabels.MEMBER_DISABLED).toBeTruthy();
    expect(shortfallReasonLabels.NO_STOCK).toBeTruthy();
    expect(shortfallReasonLabels.NOT_A_MEMBER).toBeTruthy();
  });

  it('被压低的四种原因都有可读文案', () => {
    expect(Object.keys(suppressionReasonLabels)).toHaveLength(4);
    expect(suppressionReasonLabels.RESERVED_FOR_FLOOR).toBeTruthy();
  });

  it('三种保底状态都有可读文案', () => {
    expect(Object.keys(floorStatusLabels)).toHaveLength(3);
    expect(Object.keys(floorModeLabels)).toHaveLength(2);
  });
});

describe('floorStatusTone', () => {
  it('只有没兑现才是红的，部分兑现次之', () => {
    expect(floorStatusTone('FULFILLED')).toContain('emerald');
    expect(floorStatusTone('PARTIAL')).toContain('amber');
    expect(floorStatusTone('UNFULFILLED')).toContain('rose');
  });

  it('未知状态退回中性色而不是假装正常', () => {
    expect(floorStatusTone(undefined)).toContain('muted');
    expect(floorStatusTone('SOMETHING_NEW')).toContain('muted');
  });
});

describe('intervalText', () => {
  it('未收口显示「至今仍在生效」', () => {
    expect(intervalText('2026-10-01T00:00:00Z', null)).toContain('至今仍在生效');
  });

  it('已收口显式标出（已收口）', () => {
    expect(intervalText('2026-10-01T00:00:00Z', '2026-10-07T00:00:00Z')).toContain('已收口');
  });

  it('起始为空时显示破折号而不是 Invalid Date', () => {
    expect(intervalText(null, null)).toBe('— 起至今仍在生效');
  });
});

describe('notNull', () => {
  it('是类型谓词，filter 之后元素类型被收窄', () => {
    const rows = [{ v: 1 }, null, { v: 2 }].filter(notNull);
    expect(rows.map((r) => r.v)).toEqual([1, 2]);
  });
});