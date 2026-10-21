import { describe, expect, it } from 'vitest';
import {
  buyerEmailQuery,
  buyerEmailTemplateQuery,
  emailDirectionLabel,
  emailSourceLabel,
  emailStatusLabel,
  repliedStateLabel,
  sendStatusLabel,
  templateStatusLabel,
  transportLabel,
} from './buyer-email-model';

/**
 * 15.1.2.1 邮件收发前端模型的纯规则测试。
 *
 * <p>本文件把**页面最容易做错的三件事**钉死：
 *
 * <ol>
 *   <li><b>「回过没有」必须分三态</b>：「已成功回复过」/「尝试过但没有成功发出」/「还没有回过」。
 *       把后两者混成一句「操作失败」会让人再回一次 —— 而重复回复买家的代价正是平台违规。</li>
 *   <li><b>收到的与发出的状态词表不共用</b>：收到的邮件只有「已收到 / 已回复」，
 *       发出的那一行才有「已登记待发 / 已发出 / 发送失败」，
 *       混用会让客服以为「失败的那封已经回过了」。</li>
 *   <li><b>筛选串必须原样透传</b>：查询串拼错会让页面「看起来有筛选、其实没筛」，
 *       而这种错误 typecheck 与 lint 都抓不到。</li>
 * </ol>
 */
describe('15.1.2.1 邮件收发前端模型', () => {
  describe('「回过没有」的三态', () => {
    it('已成功回复过 / 尝试过但没发出 / 还没回过 —— 三句话互不相同', () => {
      expect(repliedStateLabel({ status: 'REPLIED', sendRecordCount: 1 })).toBe('已成功回复过');
      expect(repliedStateLabel({ status: 'RECEIVED', sendRecordCount: 1 })).toContain(
        '没有成功发出',
      );
      expect(repliedStateLabel({ status: 'RECEIVED', sendRecordCount: 0 })).toBe('还没有回过');
      // 关键：失败尝试的那一封**仍然是「已收到」**，不能被画成「已回复」。
      expect(repliedStateLabel({ status: 'RECEIVED', sendRecordCount: 1 })).not.toContain('已回复');
    });
  });

  describe('状态可读名', () => {
    it('收到的与发出的状态各用各的词表', () => {
      expect(emailStatusLabel('RECEIVED')).toBe('已收到（尚未回复）');
      expect(emailStatusLabel('REPLIED')).toBe('已回复');
      expect(emailStatusLabel('QUEUED')).toBe('已登记待发');
      expect(emailStatusLabel('SENT')).toBe('已发出');
      expect(emailStatusLabel('FAILED')).toBe('发送失败');
    });

    it('方向、来源、通道、模板状态各有各的可读名', () => {
      expect(emailDirectionLabel('INBOUND')).toBe('收到的');
      expect(emailDirectionLabel('OUTBOUND')).toBe('发出的');
      expect(emailSourceLabel('MANUAL_ENTRY')).toBe('人工登记');
      expect(emailSourceLabel('PLATFORM_SYNC')).toBe('平台同步');
      expect(transportLabel('MANUAL_HANDOFF')).toBe('人工交接');
      expect(templateStatusLabel('ACTIVE')).toBe('启用');
      expect(templateStatusLabel('DISABLED')).toBe('停用');
    });

    it('发送记录状态与邮件状态不共用词表', () => {
      expect(sendStatusLabel('QUEUED')).toBe('已登记待发');
      expect(sendStatusLabel('SENT')).toBe('已发出');
      expect(sendStatusLabel('FAILED')).toBe('发送失败');
      expect(sendStatusLabel('CANCELLED')).toBe('已作废');
    });

    it('未知取值原样透出，不编一个像模像样的说法', () => {
      expect(emailStatusLabel('WHATEVER')).toBe('WHATEVER');
      expect(sendStatusLabel('WHATEVER')).toBe('WHATEVER');
      expect(emailDirectionLabel('WHATEVER')).toBe('WHATEVER');
    });
  });

  describe('查询串拼装', () => {
    it('筛选条件原样透传，空值不进串', () => {
      const query = buyerEmailQuery({
        shopId: 'shop-1',
        direction: 'INBOUND',
        buyerRef: 'buyer 1',
        subject: '退款',
        page: 2,
        size: 20,
      });
      expect(query).toContain('shopId=shop-1');
      expect(query).toContain('direction=INBOUND');
      expect(query).toContain('buyerRef=buyer%201');
      expect(query).toContain('subject=%E9%80%80%E6%AC%BE');
      expect(query).toContain('page=2');
      expect(query).toContain('size=20');
      expect(query).not.toContain('status=');
      expect(query).not.toContain('conversationKey=');
    });

    it('没有筛选条件时不产生问号', () => {
      expect(buyerEmailQuery()).toBe('');
      expect(buyerEmailQuery({})).toBe('');
      expect(buyerEmailTemplateQuery()).toBe('');
    });

    it('会话键筛选在串里（与站内信对齐用的键）', () => {
      expect(buyerEmailQuery({ conversationKey: 'shop|buyer|so-1' })).toContain(
        'conversationKey=shop%7Cbuyer%7Cso-1',
      );
    });

    it('模板查询串带上店铺、语言、状态与关键字', () => {
      const query = buyerEmailTemplateQuery({
        shopId: 'shop-1',
        locale: 'zh-CN',
        status: 'ACTIVE',
        keyword: 'REFUND',
      });
      expect(query).toContain('shopId=shop-1');
      expect(query).toContain('locale=zh-CN');
      expect(query).toContain('status=ACTIVE');
      expect(query).toContain('keyword=REFUND');
    });
  });
});
