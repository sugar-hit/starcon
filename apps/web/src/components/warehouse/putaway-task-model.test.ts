import { describe, expect, it } from 'vitest';
import {
  allLinesFinished,
  canCancel,
  canComplete,
  canScan,
  canStart,
  deviceTypeLabel,
  entryDirectionLabel,
  formatQuantity,
  formatRatio,
  locationTypeLabel,
  netIsBalanced,
  putawaySortKeys,
  putawayStatusLabel,
  putawayStatusTone,
  putawayTaskQuery,
  qualityRoleLabel,
  qualityRoleTone,
  validateScanQuantity,
} from './putaway-task-model';

/**
 * 8.2.2.1 上架任务前端模型的判定口径测试。
 *
 * <p>这些全是**页面在提交前自己先算一遍**的判定，目的是省掉一次注定被后端
 * 拒绝的往返（后端已在数据库层强制 P1/P2/P3）。所以测的是「页面与后端同一口径」，
 * 而不是「后端会怎么判」—— 后端那侧由集成测试用真实 PostgreSQL 裁决。
 */

describe('上架任务状态标签与配色', () => {
  it('四态各有各的标签', () => {
    expect(putawayStatusLabel('PENDING')).toBe('待执行');
    expect(putawayStatusLabel('IN_PROGRESS')).toBe('执行中');
    expect(putawayStatusLabel('COMPLETED')).toBe('已完成');
    expect(putawayStatusLabel('CANCELLED')).toBe('已取消');
  });

  it('未知状态原样透出，不编一个标签', () => {
    expect(putawayStatusLabel('SOMETHING_NEW')).toBe('SOMETHING_NEW');
  });

  it('四态配色互不相同（终态不能涂成同一种色）', () => {
    const tones = ['PENDING', 'IN_PROGRESS', 'COMPLETED', 'CANCELLED'].map(putawayStatusTone);
    expect(new Set(tones).size).toBe(4);
  });
});

describe('质量与库位类型标签', () => {
  it('质量三档与 V1 的 role 词表逐字对应', () => {
    expect(qualityRoleLabel('SELLABLE')).toBe('可售');
    expect(qualityRoleLabel('PENDING_INSPECTION')).toBe('待检');
    expect(qualityRoleLabel('DEFECTIVE')).toBe('残次');
  });

  it('三档质量配色互不相同（残次必须一眼分得开）', () => {
    const tones = ['SELLABLE', 'PENDING_INSPECTION', 'DEFECTIVE'].map(qualityRoleTone);
    expect(new Set(tones).size).toBe(3);
  });

  it('库位类型覆盖 V2 的六值词表', () => {
    expect(locationTypeLabel('STORAGE')).toBe('存储');
    expect(locationTypeLabel('RECEIVING')).toBe('收货暂存');
    expect(locationTypeLabel('QUARANTINE')).toBe('待检');
    expect(locationTypeLabel('DEFECTIVE')).toBe('残次');
    expect(locationTypeLabel('FROZEN')).toBe('冻结');
  });

  it('扫码设备三种都认得', () => {
    expect(deviceTypeLabel('PDA')).toBe('PDA');
    expect(deviceTypeLabel('MOBILE')).toBe('手机');
    expect(deviceTypeLabel('DESKTOP')).toBe('后台补录');
  });
});

describe('分录方向', () => {
  it('负向是源位出库，正向是目标位入库', () => {
    expect(entryDirectionLabel('SOURCE_OUT')).toBe('源位出库');
    expect(entryDirectionLabel('TARGET_IN')).toBe('目标位入库');
  });
});

describe('查询串', () => {
  it('空条件不产生 ?', () => {
    expect(putawayTaskQuery({})).toBe('');
  });

  it('按出现的顺序拼，跳过空值', () => {
    expect(
      putawayTaskQuery({ status: 'PENDING', keyword: 'PUT-1', page: 0, size: 20 }),
    ).toBe('?status=PENDING&keyword=PUT-1&page=0&size=20');
  });

  it('空白关键字不算条件（否则会发一个恒真的模糊搜）', () => {
    expect(putawayTaskQuery({ keyword: '   ' })).toBe('');
  });
});

describe('P2：完成前每一行都要搬完', () => {
  it('全部搬完 → ok', () => {
    expect(
      allLinesFinished([
        { plannedQuantity: 100, putawayQuantity: 100 },
        { plannedQuantity: 60, putawayQuantity: 60 },
      ]),
    ).toEqual({ ok: true, unfinished: 0, shortfall: 0 });
  });

  it('有一行没搬完 → 不 ok，并报出差多少', () => {
    const r = allLinesFinished([
      { plannedQuantity: 100, putawayQuantity: 100 },
      { plannedQuantity: 60, putawayQuantity: 20 },
    ]);
    expect(r.ok).toBe(false);
    expect(r.unfinished).toBe(1);
    expect(r.shortfall).toBeCloseTo(40, 9);
  });

  it('「基本搬完了」不算完成 —— 差 0.0001 也一样', () => {
    const r = allLinesFinished([{ plannedQuantity: 100, putawayQuantity: 99.9999 }]);
    expect(r.ok).toBe(false);
    expect(r.shortfall).toBeCloseTo(0.0001, 9);
  });

  it('空明细列表不阻塞（后端另有一条「没有明细就不许完成」）', () => {
    expect(allLinesFinished([]).ok).toBe(true);
  });
});

describe('账上净额不是完成标志', () => {
  it('0 视为平衡', () => {
    expect(netIsBalanced(0)).toBe(true);
    expect(netIsBalanced('0.0000')).toBe(true);
  });

  it('null / undefined / 空串视为「还没有分录」，不是错账', () => {
    expect(netIsBalanced(null)).toBe(true);
    expect(netIsBalanced(undefined)).toBe(true);
    expect(netIsBalanced('')).toBe(true);
  });

  it('非 0 判为不平衡（有人塞了不成对的分录）', () => {
    expect(netIsBalanced(-10)).toBe(false);
    expect(netIsBalanced('0.0001')).toBe(false);
  });
});

describe('动作可用性', () => {
  it('只有待执行能开始', () => {
    expect(canStart({ status: 'PENDING' })).toBe(true);
    expect(canStart({ status: 'IN_PROGRESS' })).toBe(false);
    expect(canStart({ status: 'COMPLETED' })).toBe(false);
  });

  it('待执行与执行中都能扫码（第一次扫码会自动推进到执行中）', () => {
    expect(canScan({ status: 'PENDING' })).toBe(true);
    expect(canScan({ status: 'IN_PROGRESS' })).toBe(true);
    expect(canScan({ status: 'COMPLETED' })).toBe(false);
    expect(canScan({ status: 'CANCELLED' })).toBe(false);
  });

  it('完成要求「执行中 + 每行搬完」', () => {
    const done = [{ plannedQuantity: 10, putawayQuantity: 10 }];
    const half = [{ plannedQuantity: 10, putawayQuantity: 4 }];
    expect(canComplete({ status: 'IN_PROGRESS' }, done)).toBe(true);
    expect(canComplete({ status: 'IN_PROGRESS' }, half)).toBe(false);
    // 待执行态即便明细都搬完（理论上不可能）也不能完成
    expect(canComplete({ status: 'PENDING' }, done)).toBe(false);
  });

  it('已扫码的任务不能取消（货已经在往目标库位搬了）', () => {
    expect(canCancel({ status: 'PENDING', scanCount: 0 })).toBe(true);
    expect(canCancel({ status: 'IN_PROGRESS', scanCount: 0 })).toBe(true);
    expect(canCancel({ status: 'IN_PROGRESS', scanCount: 1 })).toBe(false);
    expect(canCancel({ status: 'COMPLETED', scanCount: 3 })).toBe(false);
    expect(canCancel({ status: 'CANCELLED', scanCount: 0 })).toBe(false);
  });
});

describe('扫码量本地校验（提交前先挡，省一次往返）', () => {
  it('空值 / 非正数被拒', () => {
    expect(validateScanQuantity('', 0, 100).ok).toBe(false);
    expect(validateScanQuantity('   ', 0, 100).ok).toBe(false);
    expect(validateScanQuantity('0', 0, 100).ok).toBe(false);
    expect(validateScanQuantity('-5', 0, 100).ok).toBe(false);
    expect(validateScanQuantity('abc', 0, 100).ok).toBe(false);
  });

  it('超过 4 位小数被拒（不静默取整：取整会让「只改位置」看起来成立而实物对不上）', () => {
    const r = validateScanQuantity('1.00005', 0, 100);
    expect(r.ok).toBe(false);
    expect(r.reason).toContain('4 位小数');
  });

  it('恰好 4 位小数通过', () => {
    expect(validateScanQuantity('1.2345', 0, 100).ok).toBe(true);
  });

  it('累计超量被拒，并说清会超到多少', () => {
    const r = validateScanQuantity('20', 90, 100);
    expect(r.ok).toBe(false);
    expect(r.reason).toContain('超过应上架量');
    expect(r.reason).toContain('110');
  });

  it('累计恰好等于应上架量通过', () => {
    expect(validateScanQuantity('10', 90, 100).ok).toBe(true);
  });
});

describe('展示格式化', () => {
  it('空值渲染成破折号而不是 0（0 会被读成「真的没有」）', () => {
    expect(formatQuantity(null)).toBe('—');
    expect(formatQuantity(undefined)).toBe('—');
    expect(formatQuantity('')).toBe('—');
  });

  it('0 渲染成 0', () => {
    expect(formatQuantity(0)).toBe('0');
  });

  it('最多 6 位小数', () => {
    expect(formatQuantity(1.123456789)).toBe('1.123457');
  });

  it('没量过显示「未评估」而不是 0%（与后端 evaluated 同一口径）', () => {
    expect(formatRatio(0, false)).toBe('未评估');
    expect(formatRatio(null, true)).toBe('未评估');
    expect(formatRatio(12.5, true)).toBe('12.5');
  });
});

describe('排序白名单', () => {
  it('覆盖五个排序键', () => {
    expect(putawaySortKeys).toEqual([
      'CREATED_AT',
      'TASK_CODE',
      'PLANNED_QUANTITY',
      'STATUS',
      'COMPLETED_AT',
    ]);
  });
});