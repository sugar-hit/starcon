import { describe, expect, it } from 'vitest';
import {
  errorText,
  isUnknownRow,
  notNull,
  percent,
  qty,
  recommendationText,
  severityTone,
  shopOptionsOf,
  stateTone,
  starvationCoverageText,
  stockoutCoverageText,
  timeText,
} from './stockout-alert-model';

/**
 * 7.8.2.2 前端模型的纯函数单测。
 *
 * <p>这些函数是**展示口径**：后端算的是「谁没分到货、谁没货卖」，
 * 界面算的是「这句话怎么写、这个数要不要被看见」。
 *
 * <p>本文件最该守住的一条：**null（本地没有这个数）绝不能被渲染成 0**。
 * 「查不到库存记录」与「一件都没有」的处置完全相反 —— 前者该去核对台账，
 * 后者该去补货；而「没登记销量」与「不需要货」也是两回事 ——
 * 前者该去登记销量，后者什么也不用做。
 */
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
  });

  it('null 显示破折号而不是 0%', () => {
    // 0% 会被读成「这家店一点货都没分到」，而真相是「我们不知道它该分到多少」。
    expect(percent(null)).toBe('—');
    expect(percent(0)).toBe('0%');
  });
});

describe('timeText', () => {
  it('null 显示破折号', () => {
    expect(timeText(null)).toBe('—');
    expect(timeText('')).toBe('—');
  });

  it('能解析 ISO 时刻', () => {
    expect(timeText('2026-10-19T08:00:00+08:00')).not.toBe('—');
  });
});

describe('notNull', () => {
  it('是类型谓词，能收窄数组元素', () => {
    const rows = [{ a: 1 }, null, { a: 2 }];
    expect(rows.filter(notNull)).toHaveLength(2);
  });
});

describe('isUnknownRow', () => {
  it('需求未登记的行是「不知道」', () => {
    expect(isUnknownRow({ alertState: 'STARVED', demandState: 'UNKNOWN' })).toBe(true);
  });

  it('账本里查不到记录的行是「不知道」', () => {
    expect(isUnknownRow({ alertState: 'NO_LEDGER_RECORD', demandState: 'UNKNOWN' })).toBe(true);
  });

  it('正常判定的行不是「不知道」', () => {
    expect(isUnknownRow({ alertState: 'STARVED', demandState: 'KNOWN' })).toBe(false);
    expect(isUnknownRow({ alertState: 'HEALTHY', demandState: 'KNOWN' })).toBe(false);
  });
});

describe('stateTone / severityTone', () => {
  it('缺货与饥饿要显眼，判不出来要中性', () => {
    expect(stateTone('NETWORK_STOCKED_OUT')).toContain('rose');
    expect(stateTone('WAREHOUSE_STOCKED_OUT')).toContain('rose');
    expect(stateTone('STARVED')).toContain('rose');
    expect(stateTone('AT_RISK')).toContain('amber');
    // 「不知道」不是「出错了」—— 红色会让人以为系统坏了。
    expect(stateTone('UNKNOWN_DEMAND')).toBe('text-muted-foreground');
    expect(stateTone('NO_LEDGER_RECORD')).toBe('text-muted-foreground');
  });

  it('严重度只有 CRITICAL / WARNING 有色', () => {
    expect(severityTone('CRITICAL')).toContain('rose');
    expect(severityTone('WARNING')).toContain('amber');
    expect(severityTone('INFO')).toBe('text-muted-foreground');
  });
});

describe('recommendationText', () => {
  it('给出可执行建议时把目标对象、当前值与建议值都摊开', () => {
    const text = recommendationText({
      kind: 'RAISE_FLOOR',
      target: 'allocation_floor',
      targetLabel: '7.8.1.2 保底档（按件数）',
      policyId: 'p',
      referenceId: 'r',
      warehouseId: null,
      currentValue: 5,
      suggestedValue: 10,
      reason: '平均每轮应得 10 件。',
    });
    expect(text).toContain('调保底');
    expect(text).toContain('7.8.1.2 保底档（按件数）');
    expect(text).toContain('5 → 10');
    expect(text).toContain('平均每轮应得 10 件。');
  });

  it('没有可执行建议时只讲清为什么', () => {
    const text = recommendationText({
      kind: 'NONE',
      target: '',
      targetLabel: '',
      policyId: null,
      referenceId: null,
      warehouseId: null,
      currentValue: null,
      suggestedValue: null,
      reason: '该店不在任何分摊策略的成员名册里。',
    });
    expect(text).toContain('无可执行建议');
    expect(text).toContain('该店不在任何分摊策略的成员名册里。');
    expect(text).not.toContain('→');
  });

  it('当前值或建议值缺失时显示破折号而不是 0', () => {
    const text = recommendationText({
      kind: 'REPLENISH',
      target: '6.2.1.2',
      targetLabel: '6.2.1.2 补货需求（尚未交付）',
      policyId: null,
      referenceId: null,
      warehouseId: null,
      currentValue: null,
      suggestedValue: 100,
      reason: '缺 100 件。',
    });
    expect(text).toContain('— → 100');
  });
});

describe('starvationCoverageText', () => {
  it('把零分配轮次与总轮次放在一起', () => {
    const text = starvationCoverageText({
      observedRunCount: 5,
      zeroRoundCount: 3,
      allocatedTotal: 0,
      demandUnits: 30,
      maxRunAvailableQuantity: 100,
    });
    expect(text).toContain('5 轮有 3 轮分到 0 件');
    expect(text).toContain('登记需求 30 件');
    expect(text).toContain('同期总可用量最高 100 件');
  });

  it('没有销量登记时说「尚未回填」而不是 0 件', () => {
    const text = starvationCoverageText({
      observedRunCount: 2,
      zeroRoundCount: 2,
      allocatedTotal: 0,
      demandUnits: null,
      maxRunAvailableQuantity: 100,
    });
    expect(text).toContain('登记需求 尚未回填');
    expect(text).not.toContain('登记需求 0 件');
  });
});

describe('stockoutCoverageText', () => {
  it('并排给出该仓与全网的可用量', () => {
    const text = stockoutCoverageText({
      availableQuantity: 0,
      networkAvailableQuantity: 20,
      demandUnits: 50,
      daysOfCover: 4,
    });
    expect(text).toContain('该仓可用 0 件');
    expect(text).toContain('全网合计 20 件');
    expect(text).toContain('登记需求 50 件');
    expect(text).toContain('可售 4 天');
  });

  it('查不到库存记录时说「尚未回填」而不是 0 件', () => {
    const text = stockoutCoverageText({
      availableQuantity: null,
      networkAvailableQuantity: null,
      demandUnits: null,
      daysOfCover: null,
    });
    expect(text).toContain('该仓可用 尚未回填');
    expect(text).toContain('全网合计 尚未回填');
    expect(text).toContain('可售 尚未回填');
    expect(text).not.toContain('该仓可用 0 件');
  });
});

describe('shopOptionsOf', () => {
  it('从当前结果集里去重出店铺', () => {
    const options = shopOptionsOf([
      { referenceId: 's1', referenceLabel: '店一' } as never,
      { referenceId: 's1', referenceLabel: '店一' } as never,
      { referenceId: 's2', referenceLabel: '' } as never,
    ]);
    expect(options).toEqual([
      { id: 's1', label: '店一' },
      { id: 's2', label: 's2' },
    ]);
  });
});

describe('errorText', () => {
  it('普通错误原样带出', () => {
    expect(errorText(new Error('boom'))).toBe('boom');
  });

  it('非 Error 也带出字符串', () => {
    expect(errorText('oops')).toBe('oops');
  });
});
