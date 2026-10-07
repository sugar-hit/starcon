import { describe, expect, it } from 'vitest';
import { ApiError } from './api';
import {
  avgSecondsText,
  completeTaskProblem,
  createTaskProblem,
  defaultRange,
  distanceText,
  durationText,
  errorText,
  exceptionProblem,
  isIdleDay,
  metricIsRate,
  metricValueText,
  rangeText,
  rateText,
  rateTone,
  taskStatusTone,
  trendPointText,
} from './labor-efficiency-model';

/**
 * 8.5.1.1 作业效率前端纯函数。
 *
 * <p>重点盯两件最容易出事的事：
 * 1. **null 绝不显示成 0** —— 0% 会被读成「复核全对」，真实含义是「今天没人复核」；
 * 2. **口径与数字一起出现** —— 提成核算与绩效面谈时用户必须能查到「这个数怎么算的」。
 */

describe('率：分母为 0 时是「不可计算」而不是 0%', () => {
  it('null 显示不可计算，且不画成 0%', () => {
    expect(rateText(null)).toBe('不可计算（分母为 0）');
    expect(rateText(undefined)).toBe('不可计算（分母为 0）');
    expect(rateText(0)).toBe('0.00%');
  });

  it('null 的色调是中性的 —— 不是「好」也不是「坏」', () => {
    expect(rateTone(null)).toBe('neutral');
    expect(rateTone(0)).toBe('warn');
    expect(rateTone(12.5)).toBe('bad');
  });

  it('真实率带 4 位小数', () => {
    expect(rateText(96)).toBe('96.00%');
    // 最多 4 位小数，与后端的 RATE_SCALE 一致：多截断一位会让两个不同的率显示成同一个数
    expect(rateText(6.6667)).toBe('6.6667%');
  });
});

describe('时效：没有可算的样本不是 0 秒', () => {
  it('null 显示「无时效数据」', () => {
    expect(durationText(null)).toBe('无时效数据');
    expect(avgSecondsText(null)).toBe('不可计算（无样本）');
    expect(avgSecondsText(900)).toBe('900.0 秒');
  });

  it('墙钟时长换算成人读得懂的时分秒', () => {
    expect(durationText(45)).toBe('45 秒');
    expect(durationText(600)).toBe('10 分 0 秒');
    expect(durationText(3600)).toBe('1 小时 0 分 0 秒');
    expect(durationText(3725)).toBe('1 小时 2 分 5 秒');
  });
});

describe('距离：走得更少不自动等于更好', () => {
  it('展示单位且不为 null 时不夹带好坏判断', () => {
    expect(distanceText(null)).toBe('—');
    expect(distanceText(45.5)).toBe('45.5 米');
    expect(distanceText(1200)).toBe('1,200 米');
  });
});

describe('排名方向由指标决定', () => {
  it('越低越好的指标带向下箭头，越高越好的带向上', () => {
    expect(metricValueText(0.5, false, true)).toBe('0.50% ↓');
    expect(metricValueText(96, true, true)).toBe('96.00% ↑');
    expect(metricValueText(200, true, false)).toBe('200 ↑');
  });

  it('不可计算的排序值显示为不可计算，不显示 0', () => {
    expect(metricValueText(null, true, false)).toBe('不可计算');
  });

  it('三个率指标被识别为率', () => {
    expect(metricIsRate('REVIEW_ACCURACY')).toBe(true);
    expect(metricIsRate('ERROR_RATE_BY_PIECE')).toBe(true);
    expect(metricIsRate('ERROR_RATE_BY_ORDER')).toBe(true);
    expect(metricIsRate('PICKED_PIECES')).toBe(false);
    expect(metricIsRate('TRAVEL_DISTANCE')).toBe(false);
  });
});

describe('趋势：空白日要说成「当天无作业」', () => {
  it('空白日与「做了一天但产出 0」是两件事', () => {
    expect(isIdleDay({ taskCount: 0, pickedPieces: 0 })).toBe(true);
    // 领了任务但一件没拣：不是空白日，是产出为 0 —— 两者不能混为一谈。
    expect(isIdleDay({ taskCount: 1, pickedPieces: 0 })).toBe(false);
  });

  it('空白日显式说出来，而不是让它看起来像产量没变', () => {
    expect(trendPointText({ workDay: '2026-10-05', taskCount: 0, activeOperators: 0, pickedPieces: 0 })).toBe(
      '2026-10-05：当天无作业',
    );
    expect(
      trendPointText({ workDay: '2026-10-06', taskCount: 3, activeOperators: 2, pickedPieces: 120 }),
    ).toBe('2026-10-06：3 张任务 / 2 人 / 拣货 120 件');
  });
});

describe('区间：缺省最近 14 天，跨度说清楚', () => {
  it('缺省 14 天（与后端一致）', () => {
    const range = defaultRange(new Date('2026-10-07T08:00:00Z'));
    expect(range.to).toBe('2026-10-07');
    expect(range.from).toBe('2026-09-24');
  });

  it('天数按闭区间算（含首尾两天）', () => {
    expect(rangeText('2026-10-01', '2026-10-07')).toBe('2026-10-01 至 2026-10-07（共 7 天）');
    expect(rangeText('2026-10-01', '2026-10-01')).toBe('2026-10-01 至 2026-10-01（共 1 天）');
  });
});

describe('状态色', () => {
  it('异常终止一眼可见，已完成是正常', () => {
    expect(taskStatusTone('EXCEPTION')).toBe('bad');
    expect(taskStatusTone('RUNNING')).toBe('warn');
    expect(taskStatusTone('COMPLETED')).toBe('ok');
    expect(taskStatusTone('PENDING')).toBe('neutral');
  });
});

describe('校验：界面上先拦住，用户就不用等 422', () => {
  it('建任务必须选类型、选仓库、填来源模块号', () => {
    expect(createTaskProblem({ workType: '', warehouseId: 'w1', sourceModule: '8.5.1.1' })).toContain('作业类型');
    expect(createTaskProblem({ workType: 'PICKING', warehouseId: '', sourceModule: '8.5.1.1' })).toContain('仓库');
    expect(createTaskProblem({ workType: 'PICKING', warehouseId: 'w1', sourceModule: '  ' })).toContain('来源模块号');
    expect(createTaskProblem({ workType: 'PICKING', warehouseId: 'w1', sourceModule: '8.5.1.1' })).toBeNull();
  });

  it('差错件数不得超过作业件数', () => {
    const problem = completeTaskProblem({
      workType: 'PICKING',
      orderCount: 1,
      pieceCount: 5,
      travelDistanceM: null,
      errorPieceCount: 9,
      errorOrderCount: 0,
    });
    expect(problem).toContain('口径错了');
  });

  it('差错单量不得超过涉及单量', () => {
    const problem = completeTaskProblem({
      workType: 'PICKING',
      orderCount: 2,
      pieceCount: 5,
      travelDistanceM: null,
      errorPieceCount: 0,
      errorOrderCount: 3,
    });
    expect(problem).toContain('差错单量');
  });

  it('行走距离只属于拣货', () => {
    const problem = completeTaskProblem({
      workType: 'PACKING',
      orderCount: 1,
      pieceCount: 5,
      travelDistanceM: 12,
      errorPieceCount: 0,
      errorOrderCount: 0,
    });
    expect(problem).toContain('只属于拣货作业');
    expect(
      completeTaskProblem({
        workType: 'PICKING',
        orderCount: 1,
        pieceCount: 5,
        travelDistanceM: 12,
        errorPieceCount: 0,
        errorOrderCount: 0,
      }),
    ).toBeNull();
  });

  it('异常原因至少 4 个字', () => {
    expect(exceptionProblem('', '缺货停工')).toContain('异常类型');
    expect(exceptionProblem('MISSING_STOCK', '')).toContain('异常原因');
    expect(exceptionProblem('MISSING_STOCK', '缺')).toContain('至少 4 个字');
    expect(exceptionProblem('MISSING_STOCK', '货不在货位等待补货')).toBeNull();
  });
});

describe('错误文案：409 与 403 分开说', () => {
  it('无权限、找不到、状态冲突各有各的话', () => {
    const err = (status: number, message = '') => new ApiError(status, message);
    expect(errorText(err(403))).toContain('warehouse.inventory.update');
    expect(errorText(err(404))).toContain('不属于当前租户');
    expect(errorText(err(409, '作业任务已在 COMPLETED 状态定稿'))).toContain('定稿');
    expect(errorText(err(500))).toContain('请求失败');
    expect(errorText(new Error('网络断了'))).toBe('网络断了');
    expect(errorText('未知')).toBe('未知错误');
  });
});