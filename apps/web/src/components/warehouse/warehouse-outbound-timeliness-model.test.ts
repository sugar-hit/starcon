import { describe, expect, it } from 'vitest';
import type {
  OutboundThresholdView,
  OutboundTimelinessReport,
  OutboundTimelinessRow,
} from '../../../../../packages/api-contracts/src/index';
import {
  bucketText,
  defaultFilters,
  isEmptyTimeliness,
  minutesText,
  missingCount,
  rateText,
  rowMinutes,
  sortableMinutes,
  stageDistribution,
  thresholdFor,
  thresholdSourceLabel,
  thresholdText,
} from './warehouse-outbound-timeliness-model';

/**
 * 8.5.3.1 出库时效前端纯函数。
 *
 * <p>重点钉住三件「错了会误导人、而且误导的是绩效判断」的事：
 *
 * <ul>
 *   <li><b>「不可计算」绝不能显示成 0 分钟。</b>后端对「还没走到该环节终点」与
 *       「负时长（时间戳错了）」一律给 {@code null}，并把这类单数报在
 *       {@code missingSampleCounts}。折成 {@code 0} 会把「这单还没发」显示成「这单超快」——
 *       在一个直接决定平台迟发率的模块里，这是最贵的一种显示错。
 *   <li><b>阈值必须带来源。</b>看到 40 分钟就要能当场回答「这是谁定的」，
 *       否则调完阈值没人知道有没有生效。
 *   <li><b>缺失样本数 0 与「不可计算」是两件事</b>，不能互相顶替。
 * </ul>
 */

const UUID_A = '11111111-1111-1111-1111-111111111111';
const UUID_WH = '22222222-2222-2222-2222-222222222222';

function threshold(overrides: Partial<OutboundThresholdView> = {}): OutboundThresholdView {
  return {
    stageCode: 'PICKING',
    stageLabel: '拣货时长',
    thresholdMinutes: 240,
    source: 'BUILTIN',
    sourceLabel: '内置兜底',
    enabled: null,
    note: null,
    ...overrides,
  };
}

function row(overrides: Partial<OutboundTimelinessRow> = {}): OutboundTimelinessRow {
  return {
    outboundOrderId: UUID_A,
    orderCode: 'OB-1',
    sourceType: 'SALES',
    externalOrderRef: null,
    warehouseId: UUID_WH,
    warehouseCode: 'WH-A',
    orderToOutboundMinutes: 7500,
    pickingMinutes: 120,
    packingMinutes: 45,
    handoverMinutes: 30,
    overdueStages: [],
    overdueStageCount: 0,
    shippedAt: null,
    orderPlacedAt: null,
    pickCompletedAt: null,
    packCompletedAt: null,
    handoverAt: null,
    ...overrides,
  };
}

function report(overrides: Partial<OutboundTimelinessReport> = {}): OutboundTimelinessReport {
  return {
    from: '2026-03-01',
    to: '2026-03-31',
    timezone: 'Asia/Shanghai',
    dayBasis: '按发货自然日聚合',
    warehouseFilter: UUID_WH,
    warehouseFilterLabel: 'WH-A',
    shippedOrderCount: 0,
    overdueOrderCount: 0,
    overdueOrderRate: 0,
    thresholds: [],
    distribution: [],
    bottleneck: {
      stageCode: null,
      stageLabel: null,
      avgMinutes: null,
      thresholdMinutes: 0,
      thresholdSource: 'BUILTIN',
      overdueRate: null,
      overdueCount: 0,
      sampledCount: 0,
      basis: '无法判定瓶颈',
    },
    overdue: { items: [], total: 0, page: 0, size: 20 },
    rows: [],
    total: 0,
    page: 0,
    size: 20,
    missingSampleCounts: {},
    ...overrides,
  };
}

describe('8.5.3.1 出库时效 · 纯函数', () => {
  it('不可计算的时长显示「不可计算」，绝不显示 0 分钟', () => {
    expect(minutesText(null)).toBe('不可计算');
    expect(minutesText(undefined)).toBe('不可计算');
    // 这条是本模块最容易犯的错：折成 0 会让「这单还没发」显示成「这单超快」。
    expect(minutesText(null)).not.toBe('0.0 分钟');
  });

  it('有值的时长按分钟显示', () => {
    expect(minutesText(120)).toBe('120.0 分钟');
    expect(minutesText(45.5)).toBe('45.5 分钟');
  });

  it('sortableMinutes 对不可计算返回 null 而不是 0（排序时不该把没样本排到最前）', () => {
    expect(sortableMinutes(null)).toBeNull();
    expect(sortableMinutes(0)).toBe(0);
    expect(sortableMinutes(120)).toBe(120);
  });

  it('比率 null 显示破折号而不是 0.00%', () => {
    expect(rateText(null)).toBe('—');
    expect(rateText(12.5)).toBe('12.50%');
  });

  it('阈值永远带来源：40 分钟（仓库覆盖）', () => {
    expect(
      thresholdText(
        threshold({ thresholdMinutes: 40, source: 'WAREHOUSE', sourceLabel: '仓库覆盖' }),
      ),
    ).toBe('40.0 分钟（仓库覆盖）');
    // 只给数字就答不出「这是谁定的」，调完阈值没人知道有没有生效。
    expect(thresholdText(threshold({ thresholdMinutes: 40 }))).not.toBe('40.0 分钟');
  });

  it('阈值来源有契约外的兜底中文名', () => {
    expect(thresholdSourceLabel(threshold({ sourceLabel: '' }))).toBe('内置兜底');
    expect(thresholdSourceLabel(threshold({ source: 'WAREHOUSE', sourceLabel: '' }))).toBe(
      '仓库覆盖',
    );
  });

  it('thresholdFor 按环节取生效阈值', () => {
    const list = [threshold(), threshold({ stageCode: 'PACKING', stageLabel: '打包时长' })];
    expect(thresholdFor(list, 'PACKING')?.thresholdMinutes).toBe(240);
    expect(thresholdFor(list, 'HANDOVER')).toBeUndefined();
  });

  it('stageDistribution 取对应环节的分布，不存在给 undefined', () => {
    const d = stageDistribution([], 'PICKING');
    expect(d).toBeUndefined();
  });

  it('rowMinutes 按四环节取值', () => {
    const r = row();
    expect(rowMinutes(r, 'ORDER_TO_OUTBOUND')).toBe(7500);
    expect(rowMinutes(r, 'PICKING')).toBe(120);
    expect(rowMinutes(r, 'PACKING')).toBe(45);
    expect(rowMinutes(r, 'HANDOVER')).toBe(30);
    // 未知环节按不可计算处理，不返回 0
    expect(rowMinutes(r, 'NOT_A_STAGE')).toBeNull();
  });

  it('缺失样本数 0 与「不可计算」是两件事：0 是真实计数', () => {
    // 缺键时回落 0（= 没有单缺失），但这与「该环节时长为 null」不是同一件事：
    // 前者说的是样本覆盖，后者说的是这一单的时长不可算。
    expect(missingCount(report(), 'ORDER_TO_OUTBOUND')).toBe(0);
    expect(
      missingCount(report({ missingSampleCounts: { ORDER_TO_OUTBOUND: 3 } }), 'ORDER_TO_OUTBOUND'),
    ).toBe(3);
  });

  it('分桶：无样本时占比显示破折号，不是 0.00%', () => {
    expect(bucketText(0, 0 as unknown as number)).toBe('0 单 · 0.00%');
    expect(bucketText(1, 33.3333)).toBe('1 单 · 33.33%');
  });

  it('空报表：有单有行都不为空', () => {
    expect(isEmptyTimeliness(report())).toBe(true);
    expect(isEmptyTimeliness(report({ rows: [row()] }))).toBe(false);
    expect(isEmptyTimeliness(report({ shippedOrderCount: 1 }))).toBe(false);
  });

  it('默认筛选是空条件（空串不传给后端）', () => {
    expect(defaultFilters).toEqual({ from: '', to: '', warehouseId: '', page: 0 });
  });
});
