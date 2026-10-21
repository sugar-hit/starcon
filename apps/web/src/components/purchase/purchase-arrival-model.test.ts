import { describe, expect, it } from 'vitest';
import {
  alertTypeLabel,
  arrivalStatusLabel,
  arrivalStatusTone,
  basisFactEntries,
  dispositionActionLabel,
  dwellText,
  fmtDays,
  fmtEvaluatedAt,
  fmtMoney,
  fmtPercent,
  fmtQty,
  overdueDaysText,
  payableStatusLabel,
  promiseCountdownText,
  purchaseArrivalAlertQuery,
  purchaseArrivalQuery,
  severityLabel,
  severityTone,
  supplierText,
} from './purchase-arrival-model';

/**
 * 6.3.2.2 到货与逾期前端模型的单测。
 *
 * <p>重点覆盖**三条容易做错的展示口径**：
 *
 * <ol>
 *   <li><b>表格里不出现裸数字</b>：数量 / 金额 / 天数 / 百分比都要带单位。</li>
 *   <li><b>在途不是逾期</b>：在途单的逾期天数显示「不适用」而不是「0 天」——
 *       「0 天」会被读成「刚好没逾期」，而真实口径是「不参与判定」。</li>
 *   <li><b>处置建议的依据数字能被读出来</b>：basis_facts 解析不出来时降级为空，
 *       但人话依据照样显示 —— 页面不因为附加信息解析不了就崩。</li>
 * </ol>
 */
describe('6.3.2.2 到货与逾期前端模型', () => {
  describe('查询参数', () => {
    it('空参数不产生问号', () => {
      expect(purchaseArrivalQuery({})).toBe('');
      expect(purchaseArrivalAlertQuery({})).toBe('');
    });

    it('筛选条件逐个透传', () => {
      const q = purchaseArrivalQuery({
        page: 0,
        size: 20,
        status: 'OVERDUE',
        keyword: ' PO-1 ',
        overdueOnly: true,
      });
      expect(q).toContain('page=0');
      expect(q).toContain('size=20');
      expect(q).toContain('status=OVERDUE');
      expect(q).toContain('keyword=PO-1');
      expect(q).toContain('overdueOnly=true');
    });

    it('false 布尔不出现（只有 true 才传）', () => {
      expect(purchaseArrivalQuery({ overdueOnly: false, inTransitOnly: false })).toBe('');
    });

    it('看板的预警类型与最小逾期天数可传', () => {
      const q = purchaseArrivalAlertQuery({ alertType: 'IN_TRANSIT_STALLED', minOverdueDays: 3 });
      expect(q).toContain('alertType=IN_TRANSIT_STALLED');
      expect(q).toContain('minOverdueDays=3');
    });
  });

  describe('数量 / 金额 / 比例（不出现裸数字）', () => {
    it('数量带单位并去掉无意义的尾零', () => {
      expect(fmtQty(100)).toBe('100 件');
      expect(fmtQty('100.000000')).toBe('100 件');
      expect(fmtQty(40.5)).toBe('40.5 件');
      expect(fmtQty(12.345678)).toBe('12.345678 件');
    });

    it('数量缺失显示破折号而不是 0', () => {
      expect(fmtQty(null)).toBe('—');
      expect(fmtQty(undefined)).toBe('—');
      expect(fmtQty('')).toBe('—');
    });

    it('金额带币种', () => {
      expect(fmtMoney(100, 'USD')).toBe('100 USD');
      expect(fmtMoney(2.5, 'USD')).toBe('2.5 USD');
      expect(fmtMoney(null, 'USD')).toBe('—');
    });

    it('比例转百分比保留一位小数', () => {
      expect(fmtPercent(0.4)).toBe('40.0%');
      expect(fmtPercent(1)).toBe('100.0%');
      expect(fmtPercent(0)).toBe('0.0%');
    });

    it('天数带单位', () => {
      expect(fmtDays(12)).toBe('12 天');
      expect(fmtDays(0)).toBe('0 天');
      expect(fmtDays(null)).toBe('—');
    });
  });

  describe('状态与徽标', () => {
    it('六个状态都有中文名', () => {
      expect(arrivalStatusLabel('PENDING_SHIPMENT')).toBe('待发货');
      expect(arrivalStatusLabel('IN_TRANSIT')).toBe('已发货（在途）');
      expect(arrivalStatusLabel('PARTIALLY_RECEIVED')).toBe('部分到货');
      expect(arrivalStatusLabel('FULLY_RECEIVED')).toBe('全部到货');
      expect(arrivalStatusLabel('OVERDUE')).toBe('逾期未到');
      expect(arrivalStatusLabel('CANCELLED')).toBe('取消');
    });

    it('未知状态原样返回而不是空白', () => {
      expect(arrivalStatusLabel('WAT')).toBe('WAT');
      expect(arrivalStatusLabel(null)).toBe('—');
    });

    it('色调跟语义挂钩', () => {
      expect(arrivalStatusTone('FULLY_RECEIVED')).toBe('success');
      expect(arrivalStatusTone('OVERDUE')).toBe('danger');
      expect(arrivalStatusTone('IN_TRANSIT')).toBe('warning');
      expect(arrivalStatusTone('PARTIALLY_RECEIVED')).toBe('info');
      expect(arrivalStatusTone('CANCELLED')).toBe('neutral');
    });

    it('预警类型区分逾期与在途滞留', () => {
      expect(alertTypeLabel('OVERDUE')).toBe('逾期未到');
      expect(alertTypeLabel('IN_TRANSIT_STALLED')).toBe('在途滞留');
    });

    it('处置动作四个都有中文名', () => {
      expect(dispositionActionLabel('URGE_SUPPLIER')).toBe('催货');
      expect(dispositionActionLabel('SWITCH_SUPPLIER')).toBe('改供应商');
      expect(dispositionActionLabel('ADJUST_STOCK')).toBe('调库存');
      expect(dispositionActionLabel('DELAY_SALE')).toBe('延期售卖');
    });

    it('严重度与应付确认状态都有中文名', () => {
      expect(severityLabel('CRITICAL')).toBe('严重');
      expect(severityTone('CRITICAL')).toBe('danger');
      expect(severityTone('INFO')).toBe('info');
      expect(payableStatusLabel('PENDING_FINANCE_CONFIRM')).toBe('待财务确认');
      expect(payableStatusLabel('CONFIRMED')).toBe('财务已确认');
    });
  });

  describe('逾期口径', () => {
    it('在途显示「不适用」而不是 0 天', () => {
      expect(overdueDaysText({ status: 'IN_TRANSIT', overdueDays: 0 })).toBe(
        '不适用（在途不判逾期）',
      );
    });

    it('逾期单显示已逾期天数', () => {
      expect(overdueDaysText({ status: 'OVERDUE', overdueDays: 12 })).toBe('已逾期 12 天');
    });

    it('未逾期单明说未逾期', () => {
      expect(overdueDaysText({ status: 'PENDING_SHIPMENT', overdueDays: 0 })).toBe('未逾期');
    });

    it('距承诺日：正数是还有几天，负数是已过几天', () => {
      expect(promiseCountdownText({ status: 'PENDING_SHIPMENT', daysUntilPromise: 5 })).toBe(
        '还有 5 天到期',
      );
      expect(promiseCountdownText({ status: 'OVERDUE', daysUntilPromise: -3 })).toBe(
        '已过承诺日 3 天',
      );
      expect(promiseCountdownText({ status: 'PENDING_SHIPMENT', daysUntilPromise: 0 })).toBe(
        '今天就是承诺到货日',
      );
    });

    it('终态不显示倒计时', () => {
      expect(promiseCountdownText({ status: 'FULLY_RECEIVED', daysUntilPromise: 2 })).toBe('—');
      expect(promiseCountdownText({ status: 'CANCELLED', daysUntilPromise: 2 })).toBe('—');
    });
  });

  describe('在途快照', () => {
    it('没同步过就明说未同步', () => {
      expect(dwellText({ inTransitDwellDays: 0, inTransitSyncedAt: null })).toBe('未同步在途状态');
    });

    it('同步过就显示滞留天数', () => {
      expect(dwellText({ inTransitDwellDays: 12, inTransitSyncedAt: '2026-10-21T00:00:00Z' })).toBe(
        '滞留 12 天',
      );
    });
  });

  describe('依据事实', () => {
    it('解析 basis_facts 并给出中文标签', () => {
      const entries = basisFactEntries(
        '{"overdueDays":12,"outstandingQty":100.000000,"gapRatio":0.4,"supplierOverdueCount":2}',
      );
      const map = Object.fromEntries(entries.map((e) => [e.key, e.value]));
      expect(map.overdueDays).toBe('12 天');
      expect(map.outstandingQty).toBe('100 件');
      expect(map.gapRatio).toBe('40.0%');
      expect(map.supplierOverdueCount).toBe('2 单');
      expect(entries.find((e) => e.key === 'overdueDays')?.label).toBe('逾期天数');
    });

    it('解析不出来就降级为空数组（页面照样显示人话依据）', () => {
      expect(basisFactEntries('not json')).toEqual([]);
      expect(basisFactEntries('[1,2]')).toEqual([]);
      expect(basisFactEntries(null)).toEqual([]);
    });
  });

  describe('供应商展示', () => {
    it('有名字用名字，没有名字退回编码，都没有就明说', () => {
      expect(supplierText({ supplierCode: 'S-1', supplierName: '供应商甲' })).toBe('供应商甲');
      expect(supplierText({ supplierCode: 'S-1', supplierName: '' })).toBe('S-1');
      expect(supplierText({ supplierCode: '', supplierName: '' })).toBe('未填供应商');
    });
  });

  describe('判定时刻', () => {
    it('有效时刻显示成可读时间', () => {
      expect(fmtEvaluatedAt('2026-10-21T00:00:00Z')).not.toBe('—');
      expect(fmtEvaluatedAt('2026-10-21T00:00:00Z')).toContain('2026');
    });

    it('缺失或非法时刻不显示假时间', () => {
      expect(fmtEvaluatedAt(null)).toBe('—');
      expect(fmtEvaluatedAt('not-a-date')).toBe('not-a-date');
    });
  });
});
