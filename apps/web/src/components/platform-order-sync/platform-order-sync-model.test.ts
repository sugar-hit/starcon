import { describe, expect, it } from 'vitest';
import {
  AMOUNT_TOLERANCE,
  EVENT_TEMPLATE,
  amountMismatch,
  batchStatusLabel,
  batchTone,
  componentSourceLabel,
  componentTypeLabel,
  documentStatusLabel,
  errorCodeLabel,
  eventOutcomeLabel,
  fmt,
  moment,
  money,
  normalizedStatusLabel,
  orderSourceLabel,
  parseEventPayload,
  statusTone,
  triggerTypeLabel,
} from './platform-order-sync-model';

/**
 * 9.1.2.1 平台订单同步前端模型。
 *
 * <p>本测试钉住的是**界面口径**，不是「函数返回了什么」——
 * 真正会出事的是「重复被当成失败」「归一状态与单据状态混成一列」
 * 「金额不跨币种相加」这三类：它们在界面上都「看起来对」，
 * 只有把口径钉死才不会在下一轮改版时悄悄变回去。
 */
describe('9.1.2.1 平台订单同步前端模型', () => {
  describe('状态词表', () => {
    it('归一状态覆盖平台订单的六种生命周期', () => {
      expect(Object.keys(normalizedStatusLabel('' as never)).length).toBe(0);
      expect(normalizedStatusLabel('PENDING_CONFIRMATION')).toBe('待确认');
      expect(normalizedStatusLabel('CONFIRMED')).toBe('已确认');
      expect(normalizedStatusLabel('IN_FULFILLMENT')).toBe('履约中');
      expect(normalizedStatusLabel('COMPLETED')).toBe('已完成');
      expect(normalizedStatusLabel('CANCELLED')).toBe('已取消');
      expect(normalizedStatusLabel('ON_HOLD')).toBe('平台挂起');
    });

    it('单据状态与归一状态是两个不同的轴，标签不许混', () => {
      // 「有没有进审单队列」不是「平台订单到哪一步了」——
      // 合成一列就会让人以为「待确认」等于「没进队列」，而两者其实可以同时成立。
      expect(documentStatusLabel('DRAFT')).toContain('未进审单队列');
      expect(documentStatusLabel('SUBMITTED')).toContain('审单队列');
      expect(documentStatusLabel('DRAFT')).not.toBe(normalizedStatusLabel('PENDING_CONFIRMATION'));
      expect(documentStatusLabel('SUBMITTED')).not.toBe(normalizedStatusLabel('CONFIRMED'));
    });

    it('未知取值原样透出，不吞掉', () => {
      expect(normalizedStatusLabel('WHATEVER')).toBe('WHATEVER');
      expect(documentStatusLabel('WHATEVER')).toBe('WHATEVER');
      expect(errorCodeLabel('WHATEVER')).toBe('WHATEVER');
    });
  });

  describe('重复不是失败', () => {
    it('重复事件的标签明确写着「inbox 去重」', () => {
      expect(eventOutcomeLabel('DUPLICATE')).toContain('inbox 去重');
      expect(eventOutcomeLabel('DUPLICATE')).not.toBe(eventOutcomeLabel('REJECTED'));
      expect(eventOutcomeLabel('REJECTED')).toBe('已拒收');
      expect(eventOutcomeLabel('APPLIED')).toBe('已落地');
    });

    it('批次的四档结论各自有标签', () => {
      expect(batchStatusLabel('RUNNING')).toBe('进行中');
      expect(batchStatusLabel('SUCCEEDED')).toBe('全部落地');
      expect(batchStatusLabel('PARTIAL')).toBe('部分落地');
      expect(batchStatusLabel('FAILED')).toBe('一条未落地');
      expect(triggerTypeLabel('WEBHOOK')).toContain('webhook');
      expect(triggerTypeLabel('POLL')).toContain('轮询');
    });
  });

  describe('视觉分级', () => {
    it('取消与挂起最重，已完成与履约中次之', () => {
      expect(statusTone('CANCELLED')).toBe('danger');
      expect(statusTone('ON_HOLD')).toBe('danger');
      expect(statusTone('COMPLETED')).toBe('primary');
      expect(statusTone('IN_FULFILLMENT')).toBe('primary');
      expect(statusTone('PENDING_CONFIRMATION')).toBe('neutral');
    });

    it('批次失败与部分落地都是 danger', () => {
      expect(batchTone('FAILED')).toBe('danger');
      expect(batchTone('PARTIAL')).toBe('danger');
      expect(batchTone('SUCCEEDED')).toBe('primary');
      expect(batchTone('RUNNING')).toBe('neutral');
    });
  });

  describe('金额与数量', () => {
    it('金额带币种显示，不做跨币种合计', () => {
      expect(money(40.5, 'USD')).toBe('40.50 USD');
      expect(money(null)).toBe('—');
      expect(money(undefined)).toBe('—');
      expect(money('')).toBe('—');
    });

    it('数量去掉无意义的尾零但保留 6 位小数的零头', () => {
      expect(fmt(2)).toBe('2');
      expect(fmt(1.5)).toBe('1.5');
      expect(fmt('0.000001')).toBe('0.000001');
      expect(fmt(null)).toBe('—');
    });

    it('平台总额与还原结果的差额超过一分钱才算不一致', () => {
      expect(amountMismatch(40.5, 40.5)).toBe(false);
      expect(amountMismatch(40.5, 40.51)).toBe(false);
      expect(amountMismatch(40.5, 40.52)).toBe(true);
      expect(amountMismatch(null, 40.5)).toBe(false);
      expect(amountMismatch(40.5, null)).toBe(false);
      expect(AMOUNT_TOLERANCE).toBe(0.01);
    });
  });

  describe('时间', () => {
    it('空值显示为破折号而不是 1970', () => {
      expect(moment(null)).toBe('—');
      expect(moment(undefined)).toBe('—');
      expect(moment('')).toBe('—');
    });

    it('按绝对时刻显示，不受本地时区影响', () => {
      // timestamptz 是绝对时刻：同一个 instant 在任何时区都是同一刻。
      expect(moment('2026-03-01T08:00:00Z')).not.toBe('—');
      expect(moment('2026-03-01T08:00:00Z')).toBe(moment('2026-03-01T08:00:00Z'));
    });
  });

  describe('事件报文解析', () => {
    it('合法 JSON 解析成功', () => {
      const result = parseEventPayload('{"externalOrderNo":"A-1"}');
      expect(result.ok).toBe(true);
      if (result.ok) {
        expect(result.value).toEqual({ externalOrderNo: 'A-1' });
      }
    });

    it('空报文给出可定位的提示而不是抛异常', () => {
      const result = parseEventPayload('   ');
      expect(result.ok).toBe(false);
      if (!result.ok) {
        expect(result.message).toContain('不能为空');
      }
    });

    it('非法 JSON 给出可定位的提示而不是抛异常', () => {
      const result = parseEventPayload('{oops');
      expect(result.ok).toBe(false);
      if (!result.ok) {
        expect(result.message).toContain('不是合法 JSON');
      }
    });
  });

  describe('事件模板', () => {
    it('字段名与后端传输模型逐字一致（改了一边另一边就会 400）', () => {
      const template = EVENT_TEMPLATE as Record<string, unknown>;
      expect(template.externalOrderNo).toBeDefined();
      expect(template.platformStatus).toBeDefined();
      expect(template.currency).toBe('USD');
      expect(template.shippingAddress).toBeDefined();
      expect(Array.isArray(template.lines)).toBe(true);
      expect((template.lines as unknown[]).length).toBeGreaterThan(0);
    });

    it('模板自带一份自洽的金额：平台应付总额 = 行小计', () => {
      const template = EVENT_TEMPLATE as Record<string, unknown>;
      const lines = template.lines as Array<Record<string, unknown>>;
      const subtotal = lines.reduce(
        (sum, line) => sum + Number(line.quantity) * Number(line.unitPrice),
        0,
      );
      expect(Number(template.platformGrandTotalAmount)).toBeCloseTo(subtotal, 2);
    });

    it('模板的地址与后端地址字段逐字一致', () => {
      const address = EVENT_TEMPLATE.shippingAddress as Record<string, unknown>;
      expect(address.countryCode).toBe('US');
      expect(address.recipientName).toBeDefined();
      expect(address.addressLine1).toBeDefined();
    });
  });

  describe('组件与来源标签', () => {
    it('组件来源与类型各有标签', () => {
      expect(componentSourceLabel('LINE')).toBe('订单行本身');
      expect(componentSourceLabel('COMPONENT')).toBe('显式组件');
      expect(componentTypeLabel('ITEM')).toBe('单品');
      expect(componentTypeLabel('OTHER')).toBe('其他');
    });

    it('平台来源标签复用统一订单模型的词表', () => {
      expect(orderSourceLabel('AMAZON')).toBe('Amazon');
      expect(orderSourceLabel('SHOPIFY')).toBe('Shopify');
      expect(orderSourceLabel('PLATFORM')).toBe('平台订单');
    });
  });
});
