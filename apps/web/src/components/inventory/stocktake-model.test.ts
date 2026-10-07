import { describe, expect, it } from 'vitest';
import {
  availableScopes,
  bookText,
  countedQtyProblem,
  lineFilterLabels,
  progressPercent,
  progressText,
  qty,
  sheetStatusLabels,
  taskStatusLabels,
  taskUnitLabels,
  toCountedQty,
  unavailableNote,
  varianceText,
  varianceTone,
} from './stocktake-model';

/**
 * 7.4.2.1 盘点单前端纯函数。
 *
 * <p>重点钉住两个「错了会误导人」的展示口径：
 * 未录入不能显示成 0，以及差异必须带方向（盘盈/盘亏）而不是一个带符号数字。
 */
describe('库存数量：未录入不是 0', () => {
  it('null 显示破折号而不是 0', () => {
    expect(qty(null)).toBe('—');
    expect(qty(undefined)).toBe('—');
    expect(bookText(null)).toBe('—');
  });

  it('真实数量照常显示，0 显示 0', () => {
    expect(qty(0)).toBe('0');
    expect(qty(100)).toBe('100');
    expect(qty(-5)).toBe('-5');
  });

  it('小数保留到 4 位（后端 numeric(19,4) 口径）', () => {
    expect(qty(1.2345)).toBe('1.2345');
    expect(qty(10)).toBe('10');
  });
});

describe('差异文案必须带方向', () => {
  it('正数是盘盈、负数是盘亏、零是账实相符', () => {
    expect(varianceText(5)).toBe('盘盈 5');
    expect(varianceText(-5)).toBe('盘亏 5');
    expect(varianceText(0)).toBe('账实相符');
  });

  it('未录入说「未录入」，绝不说成「账实相符」', () => {
    // 这是本模块最危险的误显示：null 若被当成 0，用户会以为已盘完且相符。
    expect(varianceText(null)).toBe('未录入');
    expect(varianceText(undefined)).toBe('未录入');
  });

  it('盘亏最重、盘盈其次、未录入中性', () => {
    expect(varianceTone(-1)).toBe('bad');
    expect(varianceTone(1)).toBe('warn');
    expect(varianceTone(0)).toBe('ok');
    expect(varianceTone(null)).toBe('ok');
  });
});

describe('录入进度', () => {
  it('总量为 0 时不显示成 100%', () => {
    expect(progressPercent(0, 0)).toBe(0);
    expect(progressText(0, 0)).toBe('0 / 0');
  });

  it('正常进度四舍五入到整数百分比', () => {
    expect(progressPercent(1, 3)).toBe(33);
    expect(progressPercent(3, 3)).toBe(100);
    expect(progressText(2, 3)).toBe('2 / 3');
  });

  it('计数超过总量时百分比夹在 100（不外溢成 133%）', () => {
    expect(progressPercent(4, 3)).toBe(100);
  });
});

describe('实盘数校验', () => {
  it('拒绝负数并说明它为什么没有物理含义', () => {
    expect(countedQtyProblem('-1')).toContain('不能为负');
  });

  it('拒绝空串并指向撤销录入', () => {
    expect(countedQtyProblem('  ')).toContain('撤销录入');
  });

  it('拒绝非数字与超范围值', () => {
    expect(countedQtyProblem('abc')).toContain('必须是数字');
    expect(countedQtyProblem('1e12')).toContain('可信范围');
  });

  it('接受 0 与小数（0 是合法的实盘结果）', () => {
    expect(countedQtyProblem('0')).toBeNull();
    expect(countedQtyProblem('12.5')).toBeNull();
    expect(toCountedQty('12.5')).toBe(12.5);
  });

  it('校验不通过时 toCountedQty 返回 null 而不是悄悄纠正', () => {
    // 悄悄把 -1 改成 0 会让盘点员以为自己记的是「零」。
    expect(toCountedQty('-1')).toBeNull();
    expect(toCountedQty('abc')).toBeNull();
  });
});

describe('盘点范围：不可用的保留原因而不是凭空消失', () => {
  const capabilities = [
    { scopeType: 'WAREHOUSE', label: '按仓库', available: true, note: '' },
    { scopeType: 'LOCATION', label: '按库位', available: true, note: '' },
    { scopeType: 'CATEGORY', label: '按类目', available: false, note: '类目投影未接入' },
    { scopeType: 'ABC', label: '按 ABC 分类', available: false, note: '库里没有 ABC 字段' },
  ];

  it('可用范围只放行真正接入的', () => {
    expect(availableScopes(capabilities).map((c) => c.scopeType)).toEqual([
      'WAREHOUSE',
      'LOCATION',
    ]);
  });

  it('ABC 分类连候选都不进（库里根本没有这个字段）', () => {
    expect(availableScopes(capabilities).some((c) => c.scopeType === 'ABC')).toBe(false);
  });

  it('未接入的范围仍能取到说明文案，用于如实告诉用户', () => {
    expect(unavailableNote(capabilities, 'ABC')).toBe('库里没有 ABC 字段');
    expect(unavailableNote(capabilities, 'CATEGORY')).toBe('类目投影未接入');
    expect(unavailableNote(capabilities, 'WAREHOUSE')).toBeNull();
  });
});

describe('标签词表与后端枚举一一对应', () => {
  it('盘点单状态三种', () => {
    expect(Object.keys(sheetStatusLabels).sort()).toEqual(['COUNTED', 'COUNTING', 'OPEN']);
  });

  it('任务状态三种', () => {
    expect(Object.keys(taskStatusLabels).sort()).toEqual(['COUNTING', 'DONE', 'PENDING']);
  });

  it('明细筛选五个口径', () => {
    expect(Object.keys(lineFilterLabels).sort()).toEqual([
      'ALL',
      'GAIN',
      'LOSS',
      'MATCH',
      'PENDING',
    ]);
  });

  it('任务单元两种', () => {
    expect(Object.keys(taskUnitLabels).sort()).toEqual(['BY_LOCATION', 'BY_SKU']);
  });
});
