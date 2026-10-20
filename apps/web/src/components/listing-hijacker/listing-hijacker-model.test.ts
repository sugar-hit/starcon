import { describe, expect, it } from 'vitest';
import {
  actionsBanner,
  alertStatusHint,
  alertTypeLabel,
  ALERT_TYPE_LABELS,
  emptyComparisonMessage,
  emptyRecordsMessage,
  formatAmount,
  formatCount,
  formatRate,
  formatTime,
  gapDirection,
  gapText,
  hijackerKindLabel,
  pageSummary,
  sourceBanner,
  unavailableDimensions,
  watchStatusLabel,
  type ListingHijackerCapabilities,
  type ListingHijackerComparisonPage,
} from './listing-hijacker-model';

/**
 * 4.5.2.1 跟卖监控 前端模型的单测。
 *
 * 🔴 这些用例盯的是**最容易做丢的判断**：
 *
 * 1. **null 绝不渲染成 0%** —— 服务端在分母（自家参考价）为 0 时给 null，
 *    那是「没有参考价」，不是「跟卖价和自家价一样」。
 * 2. **「仅供参考」与「处置在平台侧」必须出现在页面上** —— 蓝图加粗句是硬要求。
 * 3. **「已阅」不等于「已投诉」** —— 界面必须写明。
 * 4. **空态要分得清** —— 「还没配监控对象」与「配了但没抓到」不是一回事。
 * 5. **未交付的维度只挑 available=false** —— 别的车道已交付的能力不能被误标。
 */
describe('4.5.2.1 跟卖监控 · 前端模型', () => {
  describe('🔴 比率与金额：null 绝不渲染成 0', () => {
    it('formatRate 对 null / undefined / NaN 一律显示「—」，不是 0%', () => {
      expect(formatRate(null)).toBe('—');
      expect(formatRate(undefined)).toBe('—');
      expect(formatRate(Number.NaN)).toBe('—');
      expect(formatRate(0)).toBe('0.00%');
      expect(formatRate(0.2)).toBe('20.00%');
    });

    it('formatAmount 对 null 显示「—」，不是 0.00', () => {
      expect(formatAmount(null, 'USD')).toBe('—');
      expect(formatAmount(undefined)).toBe('—');
      expect(formatAmount(0, 'USD')).toBe('0.00 USD');
      expect(formatAmount(12.5, 'USD')).toBe('12.50 USD');
      expect(formatAmount(12.5)).toBe('12.50');
    });

    it('formatCount 对 null 显示「—」', () => {
      expect(formatCount(null)).toBe('—');
      expect(formatCount(0)).toBe('0');
      expect(formatCount(1234)).toBe('1,234');
    });

    it('formatTime 对空值 / 非法值显示「—」', () => {
      expect(formatTime(null)).toBe('—');
      expect(formatTime('')).toBe('—');
      expect(formatTime('not-a-date')).toBe('—');
    });
  });

  describe('🔴 价格差方向：不知道 ≠ 持平', () => {
    it('null 比例是 unknown，不是 flat', () => {
      expect(gapDirection(null)).toBe('unknown');
      expect(gapDirection(undefined)).toBe('unknown');
      expect(gapDirection(Number.NaN)).toBe('unknown');
    });

    it('正数 = 跟卖更便宜（更糟），负数 = 跟卖更贵', () => {
      expect(gapDirection(0.2)).toBe('worse');
      expect(gapDirection(-0.1)).toBe('better');
      expect(gapDirection(0)).toBe('flat');
    });

    it('gapText 对 null 写明「没有参考价」，不是 0%', () => {
      expect(gapText(null)).toBe('—（没有参考价）');
      expect(gapText(0.2)).toBe('低 20.00%');
      expect(gapText(-0.1)).toBe('高 10.00%');
      expect(gapText(0)).toBe('持平（0.00%）');
    });
  });

  describe('🔴 「仅供参考」与「处置在平台侧」的标注', () => {
    it('sourceBanner / actionsBanner 在能力表缺位时也给出兜底文案', () => {
      expect(sourceBanner(null)).toContain('仅供参考');
      expect(sourceBanner(null)).toContain('不构成处置结论');
      expect(actionsBanner(null)).toContain('不提供');
      expect(actionsBanner(null)).toContain('平台侧');
    });

    it('sourceBanner / actionsBanner 优先用服务端给的文案', () => {
      const caps = {
        sourceNote: '服务端写的来源说明',
        actionsNote: '服务端写的处置说明',
      } as ListingHijackerCapabilities;
      expect(sourceBanner(caps)).toBe('服务端写的来源说明');
      expect(actionsBanner(caps)).toBe('服务端写的处置说明');
    });

    it('🔴 「已阅」必须写明它不代表已在平台侧处置', () => {
      expect(alertStatusHint('ACKNOWLEDGED')).toContain('不代表已在平台侧');
      expect(alertStatusHint('OPEN')).toContain('平台侧');
    });
  });

  describe('🔴 空态要分得清', () => {
    it('没配监控对象 ≠ 配了但没数据', () => {
      const noWatch = emptyRecordsMessage(0, 0);
      const noData = emptyRecordsMessage(3, 0);
      expect(noWatch).toContain('还没有监控任何商品');
      expect(noWatch).not.toBe(noData);
      expect(noData).toContain('这个筛选条件下没有跟卖记录');
    });

    it('对比视图优先用服务端写明的 emptyReason', () => {
      const empty = {
        emptyReason: '服务端写的空态原因',
      } as ListingHijackerComparisonPage;
      expect(emptyComparisonMessage(empty)).toBe('服务端写的空态原因');
      expect(emptyComparisonMessage(null)).toBe('');
    });
  });

  describe('🔴 未交付的维度只挑 available=false', () => {
    it('已交付的维度不能被误标成未接入', () => {
      const caps = {
        dimensions: [
          { dimension: 'anomaly_detection', available: true, description: '', ownerModule: '', impact: '' },
          { dimension: 'brand_protection_enforcement', available: false, description: '', ownerModule: '', impact: '' },
          { dimension: 'workbench_alert_notification', available: false, description: '', ownerModule: '', impact: '' },
        ],
      } as ListingHijackerCapabilities;
      const missing = unavailableDimensions(caps);
      expect(missing.map((d) => d.dimension)).toEqual([
        'brand_protection_enforcement',
        'workbench_alert_notification',
      ]);
    });

    it('能力表缺位时返回空数组（不编造未接入项）', () => {
      expect(unavailableDimensions(null)).toEqual([]);
    });
  });

  describe('标签与分页', () => {
    it('预警类型 / 跟卖者性质 / 监控对象状态都有中文名', () => {
      expect(alertTypeLabel('MALICIOUS_PRICE_CUT')).toBe('恶意压价');
      expect(alertTypeLabel('ANOMALOUS_HIJACK')).toBe('异常跟卖');
      expect(hijackerKindLabel('OWN_SHOP')).toBe('己方其他店铺');
      expect(hijackerKindLabel('EXTERNAL')).toBe('外部跟卖者');
      expect(watchStatusLabel('ACTIVE')).toBe('监控中');
      expect(watchStatusLabel('PAUSED')).toBe('已暂停');
    });

    it('未知取值原样返回（不编一个中文名出来）', () => {
      expect(alertTypeLabel('SOMETHING_NEW')).toBe('SOMETHING_NEW');
      expect(hijackerKindLabel('???')).toBe('???');
      expect(watchStatusLabel('???')).toBe('???');
    });

    it('预警类型词表与后端一致（两条）', () => {
      expect(Object.keys(ALERT_TYPE_LABELS)).toEqual(['MALICIOUS_PRICE_CUT', 'ANOMALOUS_HIJACK']);
    });

    it('分页摘要用服务端给的 total', () => {
      expect(pageSummary({ page: 0, size: 20, total: 0 })).toBe('第 0-0 条 / 共 0 条');
      expect(pageSummary({ page: 0, size: 20, total: 5 })).toBe('第 1-5 条 / 共 5 条');
      expect(pageSummary({ page: 1, size: 20, total: 45 })).toBe('第 21-40 条 / 共 45 条');
    });
  });
});
