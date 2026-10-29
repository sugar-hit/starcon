import { describe, expect, it } from 'vitest';
import {
  DIMENSION_OPTIONS,
  GRANULARITY_OPTIONS,
  PRICE_DIMENSION_OPTIONS,
  amountText,
  changeText,
  concentrationRiskText,
  costToSalesTone,
  crossCurrencyNote,
  cycleTone,
  dataUpdatedNote,
  dataUpdatedText,
  daysText,
  defaultRange,
  emptyText,
  onTimeStatusLabel,
  onTimeTone,
  pageState,
  percentText,
  periodLabel,
  priceStatusLabel,
  priceText,
  quantityText,
  rangeText,
  ratingTone,
  riskLabel,
  riskTone,
  shareBarWidth,
  unavailableLabel,
} from './procurement-analysis-model';
import type { ProcurementConcentrationResponse } from './procurement-analysis-model';

/**
 * 13.5.2.1 采购分析 前端模型测试。
 *
 * <p>🔴 这些断言盯的是**最容易做丢的几件事**：
 *
 * <ol>
 *   <li>「算不出来」≠「数值是 0」：{@code null} 一律渲染成「—」或「—（暂无数据）」，
 *       **绝不能**渲染成 0 / 0% / 0 天。</li>
 *   <li>「算不出来」用**中性**配色（neutral），不用警告色 —— 「这一格没数据」不是异常。</li>
 *   <li>多币种：跨币种合计不存在时给出提示，且没有币种时**不**提示。</li>
 *   <li>集中度：风险等级 / 风险说明必须来自服务端，前端**不重算**。</li>
 *   <li>页面三态（加载 / 错误 / 空）分开说。</li>
 * </ol>
 */

describe('金额与数量展示', () => {
  it('金额带币种，null 渲染成「—」而不是 0 元', () => {
    expect(amountText(1234.5, 'USD')).toBe('1,234.50 USD');
    expect(amountText(null, 'USD')).toBe('—');
    expect(amountText(null, null)).toBe('—');
  });

  it('金额没有币种时也不留尾巴空格', () => {
    expect(amountText(10, null)).toBe('10.00');
  });

  it('单价 null 是「—」', () => {
    expect(priceText(3.5, 'USD')).toBe('3.50 USD');
    expect(priceText(null, 'USD')).toBe('—');
  });

  it('数量 null 是「—」', () => {
    expect(quantityText(12.5)).toBe('12.500');
    expect(quantityText(null)).toBe('—');
  });
});

describe('比率展示：null 与 0 必须长得不一样', () => {
  it('占比 null 是「—（暂无数据）」，0 是「0.00%」', () => {
    expect(percentText(null)).toBe('—（暂无数据）');
    expect(percentText(0)).toBe('0.00%');
    expect(percentText(74.4186)).toBe('74.42%');
    // 🔴 两者**绝不能**相同 —— 相同就等于把「没有数据」读成「占比为零」。
    expect(percentText(null)).not.toBe(percentText(0));
  });

  it('变化率带正负号，null 仍是「—（暂无数据）」', () => {
    expect(changeText(11.1111)).toBe('+11.11%');
    expect(changeText(-5)).toBe('-5.00%');
    expect(changeText(null)).toBe('—（暂无数据）');
  });

  it('天数 null 是「—（暂无数据）」，不是 0 天', () => {
    expect(daysText(8.5)).toBe('8.5 天');
    expect(daysText(null)).toBe('—（暂无数据）');
    expect(daysText(null)).not.toBe(daysText(0));
  });
});

describe('语义色：算不出来是中性，不是警告', () => {
  it('集中度风险等级映射', () => {
    expect(riskTone('HIGH')).toBe('danger');
    expect(riskTone('MEDIUM')).toBe('warning');
    expect(riskTone('LOW')).toBe('success');
    // 🔴 没有风险等级 ⇒ **中性**（不是警告色）
    expect(riskTone(null)).toBe('neutral');
    expect(riskTone(undefined)).toBe('neutral');
    expect(riskTone('')).toBe('neutral');
  });

  it('风险等级中文说明', () => {
    expect(riskLabel('HIGH')).toBe('高风险');
    expect(riskLabel('MEDIUM')).toBe('中风险');
    expect(riskLabel('LOW')).toBe('低风险');
    expect(riskLabel(null)).toBe('暂无数据');
  });

  it('准时率 / 采购周期 / 成本占比：null 一律中性', () => {
    expect(onTimeTone(95)).toBe('success');
    expect(onTimeTone(75)).toBe('warning');
    expect(onTimeTone(50)).toBe('danger');
    expect(onTimeTone(null)).toBe('neutral');

    expect(cycleTone(20)).toBe('success');
    expect(cycleTone(45)).toBe('warning');
    expect(cycleTone(90)).toBe('danger');
    expect(cycleTone(null)).toBe('neutral');

    expect(costToSalesTone(30)).toBe('success');
    expect(costToSalesTone(70)).toBe('warning');
    expect(costToSalesTone(90)).toBe('danger');
    expect(costToSalesTone(null)).toBe('neutral');
  });

  it('评级档位映射，未评级是中性', () => {
    expect(ratingTone('A')).toBe('success');
    expect(ratingTone('B')).toBe('primary');
    expect(ratingTone('C')).toBe('warning');
    expect(ratingTone('D')).toBe('danger');
    expect(ratingTone(null)).toBe('neutral');
  });
});

describe('口径文案', () => {
  it('价格波动状态写明是哪一侧不可比', () => {
    expect(priceStatusLabel('COMPARABLE')).toBe('可比');
    expect(priceStatusLabel('NO_DATA')).toBe('两个期间都没有可算的均价');
    expect(priceStatusLabel('CURRENT_MISSING')).toBe('当前期间没有数量');
    expect(priceStatusLabel('PREVIOUS_MISSING')).toBe('上一期间没有数量');
    expect(priceStatusLabel('PREVIOUS_ZERO')).toBe('上一期间均价为 0');
  });

  it('准时状态中文', () => {
    expect(onTimeStatusLabel('ON_TIME')).toBe('准时');
    expect(onTimeStatusLabel('LATE')).toBe('逾期');
    expect(onTimeStatusLabel('PENDING')).toBe('未到货');
    expect(onTimeStatusLabel('NOT_APPLICABLE')).toBe('不适用');
  });

  it('未接通的指标要带归属模块号', () => {
    expect(unavailableLabel('13.1.1.1 经营总览的投影')).toBe('未接通 · 13.1.1.1 经营总览的投影');
    expect(unavailableLabel(null)).toBe('未接通');
  });

  it('期间标签按粒度说清是日 / 周 / 月', () => {
    expect(periodLabel('2026-10-01', 'DAY')).toBe('2026-10-01');
    expect(periodLabel('2026-10-01', 'WEEK')).toBe('2026-10-01（当周）');
    expect(periodLabel('2026-10', 'MONTH')).toBe('2026-10');
  });
});

describe('多币种：跨币种的合计不存在', () => {
  it('一个币种时不提示', () => {
    expect(crossCurrencyNote([{ currency: 'USD' }], 'USD')).toBe('');
  });

  it('多个币种时给出提示', () => {
    const note = crossCurrencyNote([{ currency: 'USD' }, { currency: 'EUR' }], null);
    expect(note).toContain('跨币种的合计不存在');
    expect(note).toContain('2');
  });
});

describe('集中度风险说明来自服务端，前端不重算', () => {
  const response = {
    riskLevel: 'HIGH',
    riskNote: '最大供应商「甲供应商」占采购金额 74.4186%……断供时 74.4186% 的采购额会直接中断',
    topSupplierName: '甲供应商',
    topSupplierShare: 74.4186,
  } as ProcurementConcentrationResponse;

  it('原样透传服务端的风险说明', () => {
    expect(concentrationRiskText(response)).toBe(response.riskNote);
    expect(concentrationRiskText(response)).toContain('甲供应商');
  });

  it('服务端没给说明时给一句兜底，不留空白', () => {
    expect(concentrationRiskText(null)).toBe('');
    expect(
      concentrationRiskText({ ...response, riskNote: '' } as ProcurementConcentrationResponse),
    ).not.toBe('');
  });
});

describe('页面状态与时间', () => {
  it('三态分开：读不到 / 没数据 / 有数据', () => {
    expect(pageState(null, 'boom')).toBe('error');
    expect(pageState(null, '')).toBe('loading');
    expect(pageState({ rows: [] }, '')).toBe('empty');
    expect(pageState({ rows: [1] }, '')).toBe('ready');
  });

  it('空态文案说清是「没有事实」而不是「算不出来」', () => {
    expect(emptyText({ rows: [] })).toContain('没有任何采购流水');
    expect(emptyText({ rows: [1] })).toBe('');
    expect(emptyText(null)).toBe('');
  });

  it('「数据截至」常驻；没有投影过时不是「截至今天」', () => {
    expect(dataUpdatedText({ dataUpdatedAt: null })).toBe('数据截至：尚未投影任何采购事实');
    expect(dataUpdatedText({ dataUpdatedAt: '2026-10-29T08:00:00Z' })).toBe(
      '数据截至 2026-10-29T08:00:00Z',
    );
    expect(dataUpdatedText(null)).toBe('');
  });

  it('数据口径说明透传', () => {
    expect(dataUpdatedNote({ dataUpdatedNote: '投影，不是实时的' })).toBe('投影，不是实时的');
    expect(dataUpdatedNote(null)).toBe('');
  });

  it('默认窗口是最近 30 天（含今天）', () => {
    const range = defaultRange('2026-10-29');
    expect(range.to).toBe('2026-10-29');
    expect(range.from).toBe('2026-09-30');
    expect(rangeText(range.from, range.to)).toBe('2026-09-30 ~ 2026-10-29');
  });
});

describe('结构条宽度只做展示钳位', () => {
  it('null / 越界都钳在 0~100', () => {
    expect(shareBarWidth(null)).toBe(0);
    expect(shareBarWidth(-1)).toBe(0);
    expect(shareBarWidth(140)).toBe(100);
    expect(shareBarWidth(42.5)).toBe(42.5);
  });
});

describe('筛选选项', () => {
  it('维度 / 粒度下拉都是受控词表', () => {
    expect(DIMENSION_OPTIONS.map((o) => o.value)).toEqual([
      'ALL',
      'SUPPLIER',
      'CATEGORY',
      'SHOP',
      'SKU',
    ]);
    expect(GRANULARITY_OPTIONS.map((o) => o.value)).toEqual(['DAY', 'WEEK', 'MONTH']);
    expect(PRICE_DIMENSION_OPTIONS.map((o) => o.value)).toEqual(['SKU', 'SUPPLIER']);
  });
});
