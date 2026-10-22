import { describe, expect, it } from 'vitest';
import type { CrmMessageTargetState } from '../../../../../packages/api-contracts/src/index';
import {
  bodySourceLabel,
  hasActiveSendFilter,
  messageSendQuery,
  messageSendTargetQuery,
  newIdempotencyKey,
  parseSendAttachmentDraft,
  repliedStateLabel,
  sendOutcomeText,
  sendStatusLabel,
  sendSummaryText,
  targetStateText,
  templateVariablesText,
  toAttachmentInputs,
  transportLabel,
} from './message-send-model';

/**
 * 15.1.1.2 消息发送前端模型的纯规则测试。
 *
 * <p>本文件把**页面最容易做错的三件事**钉死：
 *
 * <ol>
 *   <li><b>🔴 「没回过」与「回过但失败了」是两件事</b>：
 *       后端的「已回复」由「存在一条 SENT 的发送记录」推导，
 *       失败**不**改变它 —— 页面把它显示成「已回复」会让客服以为不用再回，
 *       显示成「没回过」又会让客服重复回复。</li>
 *   <li><b>幂等重放要说清楚</b>：界面不能让人以为「又发了一次」。</li>
 *   <li><b>金额与数量原样展示</b>：汇总里的计数全部来自服务端，
 *       前端只做拼接，不做任何 JS 侧算术。</li>
 * </ol>
 */
describe('15.1.1.2 消息发送前端模型', () => {
  describe('可读名', () => {
    it('状态、通道、正文来源各有各的说法', () => {
      expect(sendStatusLabel('QUEUED')).toBe('待发');
      expect(sendStatusLabel('SENT')).toBe('已发出');
      expect(sendStatusLabel('FAILED')).toBe('失败');
      expect(transportLabel('MANUAL_HANDOFF')).toBe('人工交接');
      expect(transportLabel('PLATFORM_API')).toContain('未接入');
      expect(bodySourceLabel('TEMPLATE_EDITED')).toBe('模板改过');
      expect(bodySourceLabel('KNOWLEDGE')).toBe('知识库原文');
      expect(repliedStateLabel('REPLIED')).toBe('已回复');
      expect(repliedStateLabel('UNREPLIED')).toBe('未回复');
    });

    it('未知取值原样透出，不编一个像模像样的说法', () => {
      expect(sendStatusLabel('WHATEVER')).toBe('WHATEVER');
      expect(bodySourceLabel('WHATEVER')).toBe('WHATEVER');
      expect(transportLabel('WHATEVER')).toBe('WHATEVER');
    });
  });

  describe('🔴 已回复状态', () => {
    const base: CrmMessageTargetState = {
      messageId: 'm-1',
      messageNo: 'MSG-1',
      repliedState: 'UNREPLIED',
      repliedSendId: null,
      repliedSendNo: null,
      lastSendStatus: null,
      lastFailureReason: null,
      note: '这条消息还没有一条「已发出」的发送记录 ⇒ 仍是**未回复**。',
    };

    it('没有已发出的发送记录 ⇒ 未回复', () => {
      const text = targetStateText(base);
      expect(text).toContain('未回复');
      expect(text).not.toContain('已回复（');
    });

    it('失败的那一次尝试**不**把状态变成已回复', () => {
      const failed: CrmMessageTargetState = {
        ...base,
        lastSendStatus: 'FAILED',
        lastFailureReason: '平台侧返回限流',
      };
      const text = targetStateText(failed);
      expect(text).toContain('未回复');
      expect(text).toContain('平台侧返回限流');
      expect(text).not.toContain('已回复（');
    });

    it('有一条已发出的发送记录 ⇒ 已回复，并说清是哪一条', () => {
      const replied: CrmMessageTargetState = {
        ...base,
        repliedState: 'REPLIED',
        repliedSendId: 's-1',
        repliedSendNo: 'MSGS-20261022-000001',
      };
      const text = targetStateText(replied);
      expect(text).toContain('已回复');
      expect(text).toContain('MSGS-20261022-000001');
    });

    it('没有状态对象时也不返回空串', () => {
      expect(targetStateText(null).trim().length).toBeGreaterThan(0);
      expect(targetStateText(undefined).trim().length).toBeGreaterThan(0);
    });
  });

  describe('幂等', () => {
    it('重放要说清楚「没有重复发送」', () => {
      const text = sendOutcomeText({
        send: {
          id: 's-1',
          sendNo: 'MSGS-1',
          targetMessageId: 'm-1',
          shopId: 'shop-1',
          shopName: '店',
          platformCode: 'AMAZON',
          buyerReference: 'b1',
          orderReference: 'SO-1',
          templateId: null,
          templateCode: '',
          knowledgeEntryId: null,
          knowledgeVersionId: null,
          knowledgeVersionNo: 0,
          subject: '',
          body: 'x',
          bodySource: 'MANUAL',
          transport: 'MANUAL_HANDOFF',
          status: 'QUEUED',
          failureReason: '',
          platformReceipt: '',
          platformMessageId: '',
          attachmentCount: 0,
          attemptCount: 1,
          lastAttemptAt: null,
          sentAt: null,
          idempotencyKey: 'k1',
          batchId: null,
          createdBy: 'u1',
          note: '',
          createdAt: '2026-10-22T00:00:00Z',
          updatedAt: '2026-10-22T00:00:00Z',
        },
        replayed: true,
        target: {
          messageId: 'm-1',
          messageNo: 'MSG-1',
          repliedState: 'UNREPLIED',
          repliedSendId: null,
          repliedSendNo: null,
          lastSendStatus: 'QUEUED',
          lastFailureReason: null,
          note: '',
        },
        message: 'x',
      });
      expect(text).toContain('没有');
      expect(text).toContain('重复发送');
      expect(text).toContain('MSGS-1');
    });

    it('没有结果时也要有话说，不能是一段空白', () => {
      expect(sendOutcomeText(null).trim().length).toBeGreaterThan(0);
    });

    it('幂等键每次生成都不一样，但格式可读', () => {
      const a = newIdempotencyKey();
      const b = newIdempotencyKey();
      expect(a).not.toBe(b);
      expect(a.startsWith('send-')).toBe(true);
      expect(newIdempotencyKey('batch').startsWith('batch-')).toBe(true);
    });
  });

  describe('空态区分', () => {
    it('没有任何筛选条件时列表就是空的，有条件时是「筛选无结果」', () => {
      expect(hasActiveSendFilter({})).toBe(false);
      expect(hasActiveSendFilter({ page: 0, size: 20 })).toBe(false);
      expect(hasActiveSendFilter({ status: 'FAILED' })).toBe(true);
      expect(hasActiveSendFilter({ keyword: '退款' })).toBe(true);
      expect(hasActiveSendFilter({ targetMessageId: 'm-1' })).toBe(true);
    });
  });

  describe('查询串', () => {
    it('空值一律不拼，省得后端收到空串', () => {
      expect(messageSendQuery({})).toBe('');
      expect(messageSendQuery({ shopId: '', keyword: '' })).toBe('');
      expect(messageSendQuery({ shopId: 'shop-1' })).toBe('?shopId=shop-1');
    });

    it('带查询串的值要转义', () => {
      expect(messageSendQuery({ keyword: '退款 100%' })).toBe('?keyword=%E9%80%80%E6%AC%BE%20100%25');
    });

    it('「只看未回复」是一个显式开关', () => {
      expect(messageSendTargetQuery({ onlyUnreplied: true })).toBe('?onlyUnreplied=true');
      expect(messageSendTargetQuery({})).toBe('');
    });
  });

  describe('汇总展示', () => {
    it('计数原样展示，JS 侧不做算术', () => {
      const text = sendSummaryText({
        totalCount: 9,
        sentCount: 4,
        failedCount: 2,
        queuedCount: 3,
        cancelledCount: 0,
        byStatus: [{ code: 'SENT', label: '已发出', count: 4 }],
        byTransport: [{ code: 'MANUAL_HANDOFF', label: '人工交接', count: 9 }],
      });
      expect(text).toContain('共 9 条');
      expect(text).toContain('已发出 4 条');
      expect(text).toContain('失败 2 条');
      expect(text).toContain('待发 3 条');
      expect(text).toContain('已发出×4');
    });

    it('没有汇总时也要有话说，不能是一段空白', () => {
      expect(sendSummaryText(null).trim().length).toBeGreaterThan(0);
    });
  });

  describe('附件草稿', () => {
    it('一行一个附件，支持「文件名 | 链接」', () => {
      expect(parseSendAttachmentDraft('photo.png')).toEqual([
        { fileName: 'photo.png', fileUrl: '' },
      ]);
      expect(parseSendAttachmentDraft('photo.png | https://cdn/p.png')).toEqual([
        { fileName: 'photo.png', fileUrl: 'https://cdn/p.png' },
      ]);
    });

    it('空行跳过，但名字缺失的行**不静默丢弃**', () => {
      expect(parseSendAttachmentDraft('\n  \n')).toEqual([]);
      // 文件名为空的行仍然交给后端去报「文件名不能为空」——
      // 前端悄悄丢掉一条附件，用户会以为文件已经附上去了。
      expect(parseSendAttachmentDraft(' | https://cdn/p.png')).toEqual([
        { fileName: '', fileUrl: 'https://cdn/p.png' },
      ]);
    });

    it('转成后端输入时大小留 null（未登记），不是 0 字节', () => {
      expect(toAttachmentInputs([{ fileName: 'a.png', fileUrl: '' }])).toEqual([
        { fileName: 'a.png', fileUrl: '', contentType: '', sizeBytes: null, note: '' },
      ]);
    });
  });

  describe('模板变量', () => {
    it('把可用变量列出来，避免写了没定义的变量', () => {
      const text = templateVariablesText(['buyer_name', 'order_no']);
      expect(text).toContain('{{buyer_name}}');
      expect(text).toContain('{{order_no}}');
    });

    it('没有变量时也要有话说', () => {
      expect(templateVariablesText([]).trim().length).toBeGreaterThan(0);
      expect(templateVariablesText(null).trim().length).toBeGreaterThan(0);
    });
  });
});
