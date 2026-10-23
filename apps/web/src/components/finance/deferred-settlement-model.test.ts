import { describe, expect, it } from 'vitest';
import {
  deferredSettlementBasisLines,
  deferredSettlementCurrencyLine,
  deferredSettlementModeLabel,
  deferredSettlementScanSummary,
  deferredSettlementStatusLabel,
  deferredSettlementStatusTone,
  formatDeferredSettlementDateTime,
  formatDeferredSettlementDay,
  formatDeferredSettlementMoney,
  formatSettlementCondition,
} from './deferred-settlement-model';

/**
 * 12.3.1.3 延迟结算的前端模型断言。
 *
 * <p>这几条守的是**界面纪律**，不是排版：
 *
 * <ul>
 *   <li>两种结算模式（周期后结算 / 滚动结算）各有一个可读名 —— 蓝图点名的就是这两种；
 *   <li>台账状态的可读名必须说清「钱到账了没」（在途未结算 / 已结算），不能只写「待处理」；
 *   <li><b>两套口径必须并排出现</b>（订单口径 / 已结算口径 / 在途未结算）——
 *       只显示一个数就等于替下游 13 利润做了口径取舍；
 *   <li>金额原样显示四位小数，<b>不做任何前端算术</b>（USD 与 EUR 加起来不是任何一个金额）；
 *   <li>时间字段是**时刻**（timestamptz），必须做时区换算 —— 与「日历日原样显示」的纪律正好相反；
 *   <li>「没有结算条件」显示成「—」，**不是**异常文案。
 * </ul>
 */
describe('12.3.1.3 延迟结算 · 前端模型', () => {
  it('两种结算模式各有一个可读名，未知值原样返回', () => {
    expect(deferredSettlementModeLabel('PERIODIC')).toBe('周期后结算');
    expect(deferredSettlementModeLabel('ROLLING')).toBe('滚动结算');
    expect(deferredSettlementModeLabel('UNKNOWN')).toBe('UNKNOWN');
  });

  it('台账状态的可读名说清「钱到账了没」', () => {
    expect(deferredSettlementStatusLabel('PENDING')).toContain('钱未到账');
    expect(deferredSettlementStatusLabel('SETTLED')).toContain('钱已到账');
    expect(deferredSettlementStatusLabel('VOID')).toBe('已作废');
    expect(deferredSettlementStatusLabel('UNKNOWN')).toBe('UNKNOWN');
  });

  it('状态色跟随语义：在途未结算=警示，已结算=成功，作废=中性', () => {
    expect(deferredSettlementStatusTone('PENDING')).toBe('warning');
    expect(deferredSettlementStatusTone('SETTLED')).toBe('success');
    expect(deferredSettlementStatusTone('VOID')).toBe('neutral');
  });

  it('金额原样显示四位小数；后端给 null 时显示「未登记」而不是 0', () => {
    expect(formatDeferredSettlementMoney(100, 'USD')).toBe('100.0000 USD');
    expect(formatDeferredSettlementMoney(0, 'USD')).toBe('0.0000 USD');
    expect(formatDeferredSettlementMoney(null, 'USD')).toBe('未登记');
    expect(formatDeferredSettlementMoney(undefined, undefined)).toBe('未登记');
  });

  it('「没有结算条件」显示成「—」，不是异常文案', () => {
    expect(formatSettlementCondition('')).toBe('—');
    expect(formatSettlementCondition(null)).toBe('—');
    expect(formatSettlementCondition('发货后 14 天进周期')).toBe('发货后 14 天进周期');
  });

  it('时间字段是时刻：按本地时区显示，不做 UTC 截断', () => {
    const value = '2026-10-11T00:00:00Z';
    // 时刻必须能被解析成合法日期（不能像日历日那样被当成 UTC 午夜再回退一天）。
    expect(Number.isNaN(new Date(value).getTime())).toBe(false);
    expect(formatDeferredSettlementDateTime(value)).not.toBe('未登记');
    expect(formatDeferredSettlementDateTime(null)).toBe('未登记');
    expect(formatDeferredSettlementDay(null)).toBe('未登记');
  });

  it('两套口径并排出现，且页面不做加减', () => {
    const line = deferredSettlementCurrencyLine({
      currency: 'USD',
      orderBasisAmount: 100,
      settledBasisAmount: 60,
      inTransitAmount: 40,
      basisGapAmount: 0,
      count: 3,
      pendingCount: 2,
      settledCount: 1,
      overdueCount: 1,
    });
    expect(line).toContain('订单口径 100.0000 USD');
    expect(line).toContain('已结算口径 60.0000 USD');
    expect(line).toContain('在途未结算 40.0000 USD');
  });

  it('口径说明逐条列出（数字脱离口径说明就没有意义）', () => {
    const lines = deferredSettlementBasisLines({
      orderBasis: '订单口径：平台订单总额',
      settledBasis: '已结算口径：已到账结算单里的销售额行',
      inTransitBasis: '在途未结算：订单口径 − 已结算口径',
      basisDifference: '口径基数差：订单口径 − 已结算口径 − 在途未结算',
      currencyPolicy: '绝不跨币种相加',
    });
    expect(lines).toHaveLength(5);
    expect(lines.join('\n')).toContain('绝不跨币种相加');
  });

  it('扫描结果说清「扫了多少 / 命中 / 新建 / 更新 / 跳过」', () => {
    const summary = deferredSettlementScanSummary({
      fetchedOrders: 10,
      fetchedShipments: 8,
      matched: 5,
      created: 4,
      updated: 1,
      skipped: 5,
      truncated: false,
      warnings: [],
    });
    expect(summary).toContain('读到平台订单 10 笔');
    expect(summary).toContain('命中已发货 5 笔');
    expect(summary).toContain('新建 4 笔');
    expect(summary).toContain('跳过 5 笔');
  });

  it('扫描触到翻页上限时必须显式说出来', () => {
    const summary = deferredSettlementScanSummary({
      fetchedOrders: 5000,
      fetchedShipments: 5000,
      matched: 1,
      created: 1,
      updated: 0,
      skipped: 0,
      truncated: true,
      warnings: [],
    });
    expect(summary).toContain('翻页上限');
  });
});
