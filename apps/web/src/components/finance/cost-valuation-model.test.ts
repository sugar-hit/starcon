import { describe, expect, it } from 'vitest';
import {
  formatValuationMoney,
  formatValuationQuantity,
  recalculationStatusLabel,
  sortTrialResults,
  valuationDifferenceLabel,
  valuationEffectiveWindow,
  valuationMethodLabel,
  valuationScopeTypeLabel,
  valuationStatusLabel,
} from './cost-valuation-model';

/**
 * 12.4.1.1 计价方法配置前端模型的单测。
 *
 * <p>重点守住四条**界面纪律**（它们一旦被破坏，页面就会开始骗人）：
 *
 * <ol>
 *   <li><b>系统提供选项，不替用户决定政策</b>：模型只提供「可读名」，**不提供任何
 *       「推荐方法」** —— 计价方法是租户自己的会计政策，系统替用户默认一个就是替用户记账；</li>
 *   <li><b>算不出来 ≠ 0</b>：服务端给 {@code null} 的地方前端一律显示「—」，
 *       绝不显示 0.0000（那会被读成「成本是 0」）；</li>
 *   <li><b>金额只原样展示</b>：前端不做任何业务算术，差额由服务端算；</li>
 *   <li>可读名覆盖**全部词表取值**，且不认识的原样返回（新增取值时页面不会显示 undefined）。</li>
 * </ol>
 */

describe('计价方法的可读名', () => {
  it('四个方法各自有可读名，不认识的原样返回', () => {
    expect(valuationMethodLabel('FIFO')).toBe('先进先出');
    expect(valuationMethodLabel('MOVING_AVERAGE')).toBe('移动加权平均');
    expect(valuationMethodLabel('BATCH')).toBe('批次成本');
    expect(valuationMethodLabel('SPECIFIED')).toBe('指定成本法');
    expect(valuationMethodLabel('SOMETHING_NEW')).toBe('SOMETHING_NEW');
  });

  it('不提供任何「推荐方法」—— 系统不替用户决定政策', () => {
    // 模型层没有「推荐 / 默认 / 建议」这类措辞，页面上也就不会出现。
    const labels = ['FIFO', 'MOVING_AVERAGE', 'BATCH', 'SPECIFIED'].map((m) =>
      valuationMethodLabel(m),
    );
    for (const label of labels) {
      expect(label).not.toContain('推荐');
      expect(label).not.toContain('默认');
      expect(label).not.toContain('建议');
    }
  });
});

describe('适用范围与状态的可读名', () => {
  it('两类范围各有可读名', () => {
    expect(valuationScopeTypeLabel('LEGAL_ENTITY')).toBe('按法人');
    expect(valuationScopeTypeLabel('CATEGORY')).toBe('按品类');
    expect(valuationScopeTypeLabel('SOMETHING_NEW')).toBe('SOMETHING_NEW');
  });

  it('三种状态各有可读名', () => {
    expect(valuationStatusLabel('ACTIVE')).toBe('生效中');
    expect(valuationStatusLabel('SUPERSEDED')).toBe('已被取代');
    expect(valuationStatusLabel('CANCELLED')).toBe('已作废');
    expect(valuationStatusLabel('SOMETHING_NEW')).toBe('SOMETHING_NEW');
  });

  it('重算标记写明归属 12.4.1.2 —— 页面不许说「已经重算」', () => {
    expect(recalculationStatusLabel('NOT_REQUIRED')).toBe('无需重算');
    expect(recalculationStatusLabel('PENDING')).toBe('待重算（12.4.1.2）');
    expect(recalculationStatusLabel('PENDING')).not.toContain('已重算');
    expect(recalculationStatusLabel('SOMETHING_NEW')).toBe('SOMETHING_NEW');
  });
});

describe('算不出来不是 0', () => {
  it('金额为空时显示破折号，不显示 0.0000', () => {
    expect(formatValuationMoney(null)).toBe('—');
    expect(formatValuationMoney(undefined)).toBe('—');
    expect(formatValuationMoney(null)).not.toContain('0');
  });

  it('有金额时按四位小数原样展示', () => {
    expect(formatValuationMoney(1600)).toBe('1600.0000');
    expect(formatValuationMoney(9.5)).toBe('9.5000');
  });

  it('数量按六位小数展示', () => {
    expect(formatValuationQuantity(null)).toBe('—');
    expect(formatValuationQuantity(150)).toBe('150.000000');
  });
});

describe('与生效方法的差额', () => {
  it('正数说「更贵」，负数说「更便宜」，零说「相同」', () => {
    expect(valuationDifferenceLabel(50)).toBe('更贵 50.0000');
    expect(valuationDifferenceLabel(-175)).toBe('更便宜 175.0000');
    expect(valuationDifferenceLabel(0)).toBe('与生效方法相同');
  });

  it('没有基准（差额为空）时显示破折号', () => {
    expect(valuationDifferenceLabel(null)).toBe('—');
  });
});

describe('生效区间的展示', () => {
  it('结束时间为空表示「一直生效」，不是「没填」', () => {
    expect(valuationEffectiveWindow('2026-01-01T00:00:00Z', null)).toBe('2026-01-01 起（一直生效）');
  });

  it('有结束时间时展示区间', () => {
    expect(valuationEffectiveWindow('2026-01-01T00:00:00Z', '2026-07-01T00:00:00Z')).toBe(
      '2026-01-01 → 2026-07-01',
    );
  });
});

describe('试算结果的排序', () => {
  it('可比的结果按成本从低到高，不可比的排在最后', () => {
    const results = [
      { comparable: false, costAmount: null, valuationMethod: 'BATCH' },
      { comparable: true, costAmount: 1650, valuationMethod: 'MOVING_AVERAGE' },
      { comparable: true, costAmount: 1600, valuationMethod: 'FIFO' },
    ];
    expect(sortTrialResults(results).map((r) => r.valuationMethod)).toEqual([
      'FIFO',
      'MOVING_AVERAGE',
      'BATCH',
    ]);
  });

  it('不改动原数组', () => {
    const results = [
      { comparable: true, costAmount: 1650, valuationMethod: 'MOVING_AVERAGE' },
      { comparable: true, costAmount: 1600, valuationMethod: 'FIFO' },
    ];
    const sorted = sortTrialResults(results);
    expect(results[0]?.valuationMethod).toBe('MOVING_AVERAGE');
    expect(sorted[0]?.valuationMethod).toBe('FIFO');
  });
});
