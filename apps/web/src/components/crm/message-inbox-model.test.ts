import { describe, expect, it } from 'vitest';
import type { CrmMessageOrderContext, CrmMessageSummary } from '../../../../../packages/api-contracts/src/index';
import {
  batchActionLabel,
  directionLabel,
  hasActiveFilter,
  intentStatusLabel,
  intentTypeLabel,
  messageQuery,
  messageSourceLabel,
  messageTypeLabel,
  orderContextText,
  parseAttachmentDraft,
  priorityLabel,
  readStatusLabel,
  summaryText,
} from './message-inbox-model';

/**
 * 15.1.1.1 消息收件箱前端模型的纯规则测试。
 *
 * <p>本文件把**页面最容易做错的三件事**钉死：
 *
 * <ol>
 *   <li><b>订单上下文查不到 ≠ 没有订单</b>：必须显示「不可用 + 原因」，
 *       绝不能返回空串 —— 空串会被客服读成「这一单没有信息」，
 *       而不是「我们没查到」。</li>
 *   <li><b>「筛选无结果」与「收件箱还是空的」是两件事</b>：
 *       空态文案必须分开，否则运营会把「条件太窄」当成「平台没同步到消息」。</li>
 *   <li><b>金额与数量原样展示</b>：汇总里的计数全部来自服务端，
 *       前端只做拼接，不做任何 JS 侧算术。</li>
 * </ol>
 */
describe('15.1.1.1 消息收件箱前端模型', () => {
  describe('可读名', () => {
    it('已读状态、方向、类型、优先级各有各的说法', () => {
      expect(readStatusLabel('UNREAD')).toBe('未读');
      expect(readStatusLabel('READ')).toBe('已读');
      expect(readStatusLabel('ARCHIVED')).toBe('已归档');
      expect(directionLabel('INBOUND')).toBe('收到');
      expect(directionLabel('OUTBOUND')).toBe('发出');
      expect(messageTypeLabel('PLATFORM_INBOX')).toBe('平台站内信');
      expect(messageTypeLabel('BUYER_MESSAGE')).toBe('买家消息');
      expect(priorityLabel('URGENT')).toBe('紧急');
    });

    it('未接入的平台同步在来源上就说清楚', () => {
      expect(messageSourceLabel('MANUAL_ENTRY')).toBe('人工登记');
      expect(messageSourceLabel('PLATFORM_SYNC')).toContain('未接入');
    });

    it('处置意图与批量动作各有各的说法', () => {
      expect(intentTypeLabel('AFTERSALE_CASE')).toBe('转售后');
      expect(intentTypeLabel('SERVICE_TICKET')).toBe('建工单');
      expect(intentStatusLabel('RECORDED')).toBe('已登记');
      expect(intentStatusLabel('CANCELLED')).toBe('已撤销');
      expect(batchActionLabel('MARK_READ')).toBe('标记已读');
      expect(batchActionLabel('RECORD_INTENT')).toBe('登记处置意图');
    });

    it('未知取值原样透出，不编一个像模像样的说法', () => {
      expect(readStatusLabel('WHATEVER')).toBe('WHATEVER');
      expect(priorityLabel('WHATEVER')).toBe('WHATEVER');
      expect(messageTypeLabel('WHATEVER')).toBe('WHATEVER');
    });
  });

  describe('订单上下文', () => {
    const unavailable: CrmMessageOrderContext = {
      available: false,
      orderNo: null,
      externalOrderNo: null,
      orderSource: null,
      currency: null,
      grandTotalAmount: null,
      lineCount: null,
      buyerName: null,
      skuCodes: null,
      documentStatus: null,
      platformStatus: null,
      paymentStatus: null,
      fulfillmentStatus: null,
      shippingWarehouse: null,
      aftersalesStatus: null,
      submittedAt: null,
      reason: '上游 trade 订单服务里没有订单号 SO-1 这一单',
    };

    it('查不到时显示「不可用 + 原因」，绝不显示空白', () => {
      const text = orderContextText(unavailable);
      expect(text).toContain('订单上下文不可用');
      expect(text).toContain('SO-1');
      expect(text.trim().length).toBeGreaterThan(0);
    });

    it('没有上下文对象时也不返回空串', () => {
      expect(orderContextText(null).trim().length).toBeGreaterThan(0);
      expect(orderContextText(undefined)).toContain('不可用');
    });

    it('可用时把订单号 / 平台 / 金额 / 状态都列出来，金额原样展示', () => {
      const available: CrmMessageOrderContext = {
        ...unavailable,
        available: true,
        reason: null,
        orderNo: 'SO-1001',
        externalOrderNo: 'AMZ-1001',
        orderSource: 'AMAZON',
        currency: 'USD',
        grandTotalAmount: 39.9,
        documentStatus: 'SUBMITTED',
        platformStatus: 'AWAITING_SHIPMENT',
        buyerName: 'Ada Buyer',
      };
      const text = orderContextText(available);
      expect(text).toContain('SO-1001');
      expect(text).toContain('AMZ-1001');
      expect(text).toContain('AMAZON');
      expect(text).toContain('USD 39.9');
      expect(text).toContain('AWAITING_SHIPMENT');
      expect(text).toContain('Ada Buyer');
    });
  });

  describe('空态区分', () => {
    it('没有任何筛选条件时收件箱就是空的，有条件时是「筛选无结果」', () => {
      expect(hasActiveFilter({})).toBe(false);
      expect(hasActiveFilter({ page: 0, size: 20 })).toBe(false);
      expect(hasActiveFilter({ readStatus: 'UNREAD' })).toBe(true);
      expect(hasActiveFilter({ keyword: '退款' })).toBe(true);
      expect(hasActiveFilter({ shopId: 'shop-1' })).toBe(true);
      expect(hasActiveFilter({ platformTimeFrom: '2026-06-01T00:00:00Z' })).toBe(true);
    });
  });

  describe('查询串', () => {
    it('空值一律不拼，省得后端收到空串', () => {
      expect(messageQuery({})).toBe('');
      expect(messageQuery({ shopId: '', keyword: '' })).toBe('');
      expect(messageQuery({ shopId: 'shop-1' })).toBe('?shopId=shop-1');
    });

    it('带查询串的值要转义', () => {
      expect(messageQuery({ keyword: '退款 100%' })).toBe('?keyword=%E9%80%80%E6%AC%BE%20100%25');
    });
  });

  describe('汇总展示', () => {
    it('计数原样展示，JS 侧不做算术', () => {
      const summary: CrmMessageSummary = {
        totalCount: 12,
        unreadCount: 5,
        readCount: 6,
        archivedCount: 1,
        withAttachmentCount: 3,
        byPriority: [{ code: 'HIGH', label: '高', count: 4 }],
        byType: [{ code: 'BUYER_MESSAGE', label: '买家消息', count: 9 }],
        byShop: [],
      };
      const text = summaryText(summary);
      expect(text).toContain('共 12 条');
      expect(text).toContain('未读 5 条');
      expect(text).toContain('带附件 3 条');
      expect(text).toContain('高×4');
      expect(text).toContain('买家消息×9');
    });

    it('没有汇总时也要有话说，不能是一段空白', () => {
      expect(summaryText(null).trim().length).toBeGreaterThan(0);
    });
  });

  describe('附件草稿', () => {
    it('一行一个附件，支持「文件名 | 链接」', () => {
      expect(parseAttachmentDraft('photo.png')).toEqual([{ fileName: 'photo.png', fileUrl: '' }]);
      expect(parseAttachmentDraft('photo.png | https://cdn/p.png')).toEqual([
        { fileName: 'photo.png', fileUrl: 'https://cdn/p.png' },
      ]);
    });

    it('空行跳过，但名字缺失的行**不静默丢弃**', () => {
      expect(parseAttachmentDraft('\n  \n')).toEqual([]);
      // 文件名为空的行仍然交给后端去报「文件名不能为空」——
      // 前端悄悄丢掉一条附件，用户会以为文件已经登记上去了。
      expect(parseAttachmentDraft(' | https://cdn/p.png')).toEqual([
        { fileName: '', fileUrl: 'https://cdn/p.png' },
      ]);
    });
  });
});
