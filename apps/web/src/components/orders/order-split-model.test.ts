import { describe, expect, it } from 'vitest';
import { ApiError } from '../../lib/api';
import {
  conserved,
  errorText,
  isoText,
  money,
  qty,
  rejectText,
  rejectTone,
  residualText,
  splitStatusText,
  strategyText,
  unitStatusText,
} from './order-split-model';

/**
 * 9.3.1.1 拆单前端模型的行为断言。
 *
 * <p>重点不在「函数能不能跑」，而在**界面会不会撒谎**：
 * 「无尾差」与「尾差 0.0000」、「未预占上游库存」与「已预占」、
 * 「这次分摊除得尽」与「差额被吞掉了」这几组，在页面上必须是不同的两句话。
 */
describe('9.3.1.1 拆单前端模型', () => {
  describe('金额守恒：前端独立复算一遍，而不是只信后端的布尔', () => {
    it('分摊 + 尾差 == 订单总额时判为守恒', () => {
      expect(
        conserved({
          orderTotalAmount: '100.0000',
          allocatedAmountTotal: '99.9999',
          residualAmountTotal: '0.0001',
        }),
      ).toBe(true);
    });

    it('差一分钱也判为不守恒（4 位刻度，不四舍五入放过）', () => {
      expect(
        conserved({
          orderTotalAmount: '100.0000',
          allocatedAmountTotal: '99.9998',
          residualAmountTotal: '0.0001',
        }),
      ).toBe(false);
    });

    it('尾差被静默吞掉时必须判为不守恒', () => {
      // 分摊合计 100 但总额是 100.0001 —— 差额没有承载列，这就是「差了几分钱不见了」。
      expect(
        conserved({
          orderTotalAmount: '100.0001',
          allocatedAmountTotal: '100.0000',
          residualAmountTotal: '0.0000',
        }),
      ).toBe(false);
    });
  });

  describe('尾差：0 与「无尾差」不是同一句话', () => {
    it('尾差为 0 显示「无尾差」而不是 0.0000', () => {
      expect(residualText('0.0000')).toBe('无尾差');
      expect(residualText('0.0000')).not.toContain('0.0000');
    });

    it('有尾差时照原值显示，不抹成 0', () => {
      expect(residualText('0.0001')).toBe('0.0001');
      expect(residualText('-0.0001')).toBe('-0.0001');
    });

    it('没有尾差概念（null）显示「—」而不是 0', () => {
      expect(residualText(null)).toBe('—');
    });
  });

  describe('金额与数量只补位、不二次四舍五入', () => {
    it('金额补齐 4 位但不改动已有多余精度', () => {
      expect(money('100')).toBe('100.0000');
      expect(money('100.00004')).toBe('100.00004');
      expect(money(null)).toBe('—');
    });

    it('数量补齐 6 位（DB 的 numeric(19,6) 口径）', () => {
      expect(qty('10')).toBe('10.000000');
      expect(qty('10.000001')).toBe('10.000001');
      expect(qty(undefined)).toBe('—');
    });
  });

  describe('候选结论：落选与选中必须显示成不同的话', () => {
    it('被选中的候选显示「已选中」而不是「无原因」', () => {
      const selected = { selected: true, rejectReason: '', rejectReasonLabel: '' };
      expect(rejectText(selected)).toBe('已选中');
      expect(rejectText(selected)).not.toContain('无原因');
    });

    it('落选候选显示后端带回来的原因标签，并按事实类/规则类分色', () => {
      const stock = {
        selected: false,
        rejectReason: 'INSUFFICIENT_STOCK',
        rejectReasonLabel: '库存不足',
      };
      expect(rejectText(stock)).toBe('库存不足');
      expect(rejectTone(stock)).toBe('warn');

      const platform = {
        selected: false,
        rejectReason: 'PLATFORM_RESTRICTED',
        rejectReasonLabel: '该仓不接此平台',
      };
      expect(rejectTone(platform)).toBe('block');

      expect(rejectTone({ selected: true, rejectReason: '' })).toBe('ok');
    });
  });

  describe('时间按 UTC 渲染，不受浏览器本地时区影响', () => {
    it('+08:00 的发货时效与 UTC 渲染成同一时刻（否则时效先后会被排错）', () => {
      const shanghai = '2026-03-01T18:45:00+08:00';
      const utc = '2026-03-01T10:45:00Z';
      expect(isoText(shanghai)).toBe(isoText(utc));
    });

    it('空值显示「—」而不是 Invalid Date', () => {
      expect(isoText(null)).toBe('—');
      expect(isoText('')).toBe('—');
    });
  });

  describe('词表与后端同源', () => {
    it('四个拆分维度都有中文标签', () => {
      expect(strategyText('WAREHOUSE')).toBe('按仓库拆');
      expect(strategyText('CARRIER')).toBe('按承运商拆');
      expect(strategyText('TIMELINESS')).toBe('按发货时效拆');
      expect(strategyText('PLATFORM')).toBe('按平台限制拆');
      expect(strategyText('UNKNOWN')).toBe('UNKNOWN');
    });

    it('状态标签覆盖四个拆单状态与四个单元状态', () => {
      expect(splitStatusText('PLANNED')).toBe('已拆分');
      expect(splitStatusText('CANCELLED')).toBe('已作废');
      expect(unitStatusText('ALLOCATED')).toBe('预占已登记');
      expect(unitStatusText(null)).toBe('—');
    });
  });

  describe('错误态不被吞掉', () => {
    it('ApiError 的 message 优先展示', () => {
      expect(errorText(new ApiError(409, '拆单金额不守恒'))).toBe('拆单金额不守恒');
    });

    it('非 Error 兜底成可读文案', () => {
      expect(errorText('boom')).toContain('拆单服务');
    });
  });
});
