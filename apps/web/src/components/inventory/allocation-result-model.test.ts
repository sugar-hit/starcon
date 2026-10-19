import { describe, expect, it } from 'vitest';
import {
  consistencyStateLabels,
  consistencyTone,
  errorText,
  exposureStateLabels,
  exposureText,
  exposureTone,
  isUnknown,
  methodLabels,
  notNull,
  percent,
  qty,
  scopeLabels,
  sourceKindLabels,
  summaryText,
  timeText,
  writebackStateLabels,
  writebackTone,
} from './allocation-result-model';

/**
 * 7.8.2.1 前端模型的纯函数单测。
 *
 * <p>这些函数是**展示口径**：后端算的是「平台挂着的比我方剩余多多少、
 * 缓冲兜不兜得住」，界面算的是「这句话怎么写、这个数要不要被看见」。
 *
 * <p>本文件最该守住的一条：**null（本地没有这个数）绝不能被渲染成 0**。
 * 「尚未回填」与「一件都没有」的处置完全相反 —— 前者该去接同步，后者该去补货。
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

  it('null 显示破折号', () => {
    expect(percent(null)).toBe('—');
  });
});

describe('UNKNOWN 语义', () => {
  it('回写状态里有 UNKNOWN 且标签是「尚未回填」', () => {
    expect(writebackStateLabels.UNKNOWN).toBe('尚未回填');
    expect(writebackStateLabels.FAILED).toBe('回写失败');
  });

  it('isUnknown 认得未回填的行', () => {
    expect(isUnknown({ writebackState: 'UNKNOWN' })).toBe(true);
    expect(isUnknown({ writebackState: 'SUCCEEDED' })).toBe(false);
  });

  it('UNKNOWN 用中性色而不是红色', () => {
    // 渲染成红色会让人以为系统坏了，而它其实是在提示「该去接同步」。
    expect(writebackTone('UNKNOWN')).toBe('text-muted-foreground');
    expect(writebackTone('FAILED')).toContain('rose');
  });

  it('敞口算不出来时给「无法计算」而不是 0 件', () => {
    expect(exposureStateLabels.UNKNOWN).toBe('无法计算');
    expect(exposureTone('UNKNOWN')).toBe('text-muted-foreground');
    const text = exposureText({
      exposureQuantity: null,
      bufferQuantity: null,
      exposureState: 'UNKNOWN',
    });
    expect(text).toContain('无法计算');
    expect(text).not.toContain('0 件');
  });
});

describe('exposureText', () => {
  it('缓冲不足时把「超出多少」与「缓冲多少」都摆出来', () => {
    const text = exposureText({
      exposureQuantity: 25,
      bufferQuantity: 6,
      exposureState: 'BEYOND_BUFFER',
    });
    expect(text).toContain('25');
    expect(text).toContain('6');
    expect(text).toContain('会超卖');
  });

  it('缓冲兜得住时说的是「兜得住」而不是「没问题」', () => {
    const text = exposureText({
      exposureQuantity: 5,
      bufferQuantity: 8,
      exposureState: 'WITHIN_BUFFER',
    });
    expect(text).toContain('兜得住');
  });

  it('没配口径时如实说是「缓冲按 0 计」', () => {
    const text = exposureText({
      exposureQuantity: 3,
      bufferQuantity: null,
      exposureState: 'BEYOND_BUFFER',
    });
    expect(text).toContain('没有配置平台回写缓冲');
  });

  it('无敞口时说清楚「不会因此超卖」', () => {
    const text = exposureText({
      exposureQuantity: 0,
      bufferQuantity: 8,
      exposureState: 'NO_EXPOSURE',
    });
    expect(text).toContain('不会因此超卖');
  });
});

describe('summaryText', () => {
  it('把「已知的行数」与「总行数」一起给出', () => {
    // 只给一个「已用合计」的话，看的人没法知道这个数覆盖了多少家店。
    const text = summaryText({
      rowCount: 15,
      consumedKnownCount: 12,
      platformKnownCount: 13,
      unknownStateCount: 2,
      exposureComputableCount: 13,
      exposureTotal: 25,
      beyondBufferCount: 1,
    });
    expect(text).toContain('15');
    expect(text).toContain('12/15');
    expect(text).toContain('13/15');
    expect(text).toContain('尚未回填 2 行');
    expect(text).toContain('缓冲不足 1 行');
  });
});

describe('词表', () => {
  it('一致性词表区分「挂多了」与「挂少了」', () => {
    expect(consistencyStateLabels.OVER_ADVERTISED).toBe('平台挂多了');
    expect(consistencyStateLabels.UNDER_ADVERTISED).toBe('平台挂少了');
    expect(consistencyTone('OVER_ADVERTISED')).toContain('rose');
    expect(consistencyTone('UNDER_ADVERTISED')).toContain('amber');
    expect(consistencyTone('CONSISTENT')).toContain('emerald');
  });

  it('scope 词表把四种不同的病分成四个取值', () => {
    expect(Object.keys(scopeLabels)).toEqual([
      'ALL',
      'EXPOSURE',
      'BEYOND_BUFFER',
      'INCONSISTENT',
      'UNKNOWN',
    ]);
  });

  it('来源与分摊方法词表齐全', () => {
    expect(Object.keys(sourceKindLabels)).toEqual(['EVENT', 'API', 'MANUAL']);
    expect(Object.keys(methodLabels)).toHaveLength(3);
  });
});

describe('工具', () => {
  it('notNull 是类型谓词，能收窄列表元素', () => {
    const list = [1, null, 2, undefined].filter(notNull);
    expect(list).toEqual([1, 2]);
  });

  it('timeText 对空值与非法值都给破折号', () => {
    expect(timeText(null)).toBe('—');
    expect(timeText('')).toBe('—');
    expect(timeText('not-a-date')).toBe('—');
  });

  it('errorText 把状态码与原因都带出来', () => {
    expect(errorText(new Error('炸了'))).toBe('炸了');
    expect(errorText('裸字符串')).toBe('裸字符串');
  });
});
