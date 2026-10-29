import { describe, expect, it } from 'vitest';
import {
  amountText,
  categoryRatioTone,
  categoryScopeNote,
  dataUpdatedNote,
  dataUpdatedText,
  defaultRange,
  emptyText,
  exceptionShareTone,
  freightGapLabel,
  freightGapTone,
  freightScopeNote,
  hoursText,
  onTimeTone,
  pageState,
  percentText,
  quantityText,
  rangeText,
  severityTone,
  shareBarWidth,
  unavailableLabel,
} from './logistics-analysis-model';
import type {
  LogisticsCategoryCostResponse,
  LogisticsFreightPerOrderResponse,
} from './logistics-analysis-model';

/**
 * 13.5.3.1 物流分析前端模型的**口径纪律**测试。
 *
 * <p>🔴 这些断言守的是「**算不出来 ≠ 数值是 0**」和「**多币种绝不相加**」两条纪律：
 * 前端一旦用 0 顶替 {@code null}，页面上的「单均运费 0 元」会被读成
 * 「这批货没花运费」—— 而真相是「这一格没算出来」。
 */
describe('物流分析前端模型', () => {
  describe('金额与比率的「不可比」呈现', () => {
    it('金额为 null ⇒ 「—」，绝不用 0 顶替', () => {
      expect(amountText(null, 'USD')).toBe('—');
      expect(amountText(0, 'USD')).toBe('0.00 USD');
      expect(amountText(12.3456, 'USD')).toBe('12.35 USD');
    });

    it('比率为 null ⇒ 「—（不可比）」，与真的 0% 长得不一样', () => {
      expect(percentText(null)).toBe('—（不可比）');
      expect(percentText(0)).toBe('0.00%');
      expect(percentText(33.3333)).toBe('33.33%');
    });

    it('时长与数量为 null ⇒ 「—」，不是 0', () => {
      expect(hoursText(null)).toBe('—（不可比）');
      expect(hoursText(112.04)).toBe('112.0 小时');
      expect(quantityText(null)).toBe('—');
      expect(quantityText(2.5)).toBe('2.500');
    });
  });

  describe('语义色：不可比是中性，不是异常', () => {
    it('达成率为 null ⇒ neutral（不是 danger）', () => {
      expect(onTimeTone(null)).toBe('neutral');
      expect(onTimeTone(95)).toBe('success');
      expect(onTimeTone(75)).toBe('warning');
      expect(onTimeTone(50)).toBe('danger');
    });

    it('异常件占比与类目占比为 null ⇒ neutral', () => {
      expect(exceptionShareTone(null)).toBe('neutral');
      expect(categoryRatioTone(null)).toBe('neutral');
      expect(exceptionShareTone(2)).toBe('success');
      expect(exceptionShareTone(8)).toBe('warning');
      expect(exceptionShareTone(20)).toBe('danger');
      expect(categoryRatioTone(40)).toBe('warning');
      expect(categoryRatioTone(60)).toBe('danger');
    });

    it('运费登记状态与严重度 ⇒ 可读的徽标', () => {
      expect(freightGapTone('REGISTERED')).toBe('success');
      expect(freightGapTone('NOT_REGISTERED')).toBe('warning');
      expect(freightGapLabel('NOT_REGISTERED')).toBe('未登记');
      expect(severityTone('HIGH')).toBe('danger');
      expect(severityTone('LOW')).toBe('success');
      expect(severityTone(null)).toBe('neutral');
    });
  });

  describe('占比条只做展示钳位', () => {
    it('null ⇒ 0 宽；越界 ⇒ 钳位', () => {
      expect(shareBarWidth(null)).toBe(0);
      expect(shareBarWidth(-5)).toBe(0);
      expect(shareBarWidth(150)).toBe(100);
      expect(shareBarWidth(33.33)).toBe(33.33);
    });
  });

  describe('单均运费的分母口径常驻', () => {
    const response = {
      orderScope: 'WAYBILL_BACKED_ORDER（窗口内有已登记运费的 distinct 销售订单号）',
      orderCount: 3,
      orderCountAll: 4,
      waybillCount: 6,
      registeredWaybillCount: 4,
      notRegisteredWaybillCount: 1,
    } as unknown as LogisticsFreightPerOrderResponse;

    it('写出订单分母口径与票数', () => {
      const note = freightScopeNote(response);
      expect(note).toContain('WAYBILL_BACKED_ORDER');
      expect(note).toContain('6 票');
      expect(note).toContain('4 票');
    });

    it('有「只有未登记运费的票」的订单时必须点出来', () => {
      expect(freightScopeNote(response)).toContain('1 个订单');
    });

    it('没有差异订单时不多说', () => {
      const same = { ...response, orderCountAll: 3 } as LogisticsFreightPerOrderResponse;
      expect(freightScopeNote(same)).not.toContain('只有未登记运费的票');
    });
  });

  describe('类目维度的覆盖边界常驻', () => {
    const response = {
      unattributedLineCount: 2,
      revenueMissingSkuCount: 1,
    } as unknown as LogisticsCategoryCostResponse;

    it('写明尾程没有 SKU 维度', () => {
      expect(categoryScopeNote(response)).toContain('没有 SKU 维度');
    });

    it('挂不上类目的行与销售额必须报数量', () => {
      const note = categoryScopeNote(response);
      expect(note).toContain('2 条分摊明细行');
      expect(note).toContain('1 条销售额事实');
    });
  });

  describe('页面状态：读不到 ≠ 没有数据', () => {
    it('错误 ⇒ error；未加载 ⇒ loading；空 ⇒ empty', () => {
      expect(pageState(null, 'boom')).toBe('error');
      expect(pageState(null, '')).toBe('loading');
      expect(pageState({ rows: [] }, '')).toBe('empty');
      expect(pageState({ rows: [1] }, '')).toBe('ready');
    });

    it('空态文案说清「先刷新投影」而不是「没有成本」', () => {
      expect(emptyText({ rows: [] })).toContain('刷新投影');
      expect(emptyText({ rows: [1] })).toBe('');
    });
  });

  describe('数据更新时间常驻', () => {
    it('没投影过时写「尚未投影」，不是「数据截至 今天」', () => {
      expect(dataUpdatedText({ dataUpdatedAt: null })).toContain('尚未投影任何物流事实');
      expect(dataUpdatedText({ dataUpdatedAt: '2026-10-31T00:00:00Z' })).toContain('2026-10-31');
      expect(dataUpdatedText(null)).toBe('');
      expect(dataUpdatedNote({ dataUpdatedNote: '快照说明' })).toBe('快照说明');
    });
  });

  describe('窗口与标签', () => {
    it('默认窗口是最近 30 天（含今天）', () => {
      const range = defaultRange('2026-10-29');
      expect(range.to).toBe('2026-10-29');
      expect(range.from).toBe('2026-09-30');
      expect(rangeText(range.from, range.to)).toBe('2026-09-30 ~ 2026-10-29');
    });

    it('未接通的徽标必须带归属模块号', () => {
      expect(unavailableLabel('10.3.1.2')).toBe('未接通 · 10.3.1.2');
      expect(unavailableLabel(null)).toBe('未接通');
    });
  });
});
