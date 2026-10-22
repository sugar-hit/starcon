import { describe, expect, it } from 'vitest';
import {
  batchSummaryLabel,
  deliveryOutcomeLabel,
  replyStatusLabel,
  replyTypeLabel,
  reviewReplyBatchQuery,
  reviewReplyPolicyQuery,
  reviewReplyQuery,
  reviewReplyTemplateQuery,
  reviewScopeLabel,
  templateStatusLabel,
  templateVersionLabel,
} from './review-reply-model';

/**
 * 15.2.1.2 评价回复前端模型的纯规则测试。
 *
 * <p>本文件把**页面最容易做错的三件事**钉死：
 *
 * <ol>
 *   <li><b>「待投递」不能说成「已回复」</b>：平台适配器未接入时，回复状态停在
 *       {@code PENDING}，页面上**必须**写清「还没发到平台，买家还没收到」——
 *       只写「已回复」会让运营以为事情办完了。</li>
 *   <li><b>版本要看得见</b>：模板版本与规范版本必须出现在界面上 ——
 *       平台政策会变，没有版本号就答不出「当时那条回复算不算违规」。</li>
 *   <li><b>批量结果的成功数与跳过数必须一起说</b>：只报成功数会让人以为
 *       「这一批全都回上了」，而被跳过的那几条其实一条没回。</li>
 * </ol>
 */
describe('15.2.1.2 评价回复前端模型', () => {
  describe('筛选条件', () => {
    it('空筛选不产生问号', () => {
      expect(reviewReplyQuery()).toBe('');
      expect(reviewReplyPolicyQuery()).toBe('');
      expect(reviewReplyTemplateQuery()).toBe('');
      expect(reviewReplyBatchQuery()).toBe('');
    });

    it('可选值为空串时不下参数（前端把「没填」表达成空串）', () => {
      expect(reviewReplyQuery({ platformCode: '', status: undefined })).toBe('');
      expect(reviewReplyQuery({ platformCode: 'AMAZON' })).toBe('?platformCode=AMAZON');
    });

    it('分页参数与筛选参数在同一条查询串里', () => {
      const query = reviewReplyQuery({ status: 'PENDING', page: 2, size: 20 });
      expect(query).toContain('status=PENDING');
      expect(query).toContain('page=2');
      expect(query).toContain('size=20');
    });

    it('参数值要转义（关键字里可能有 & 与 =）', () => {
      expect(reviewReplyQuery({ keyword: 'a&b=c' })).toBe('?keyword=a%26b%3Dc');
    });
  });

  describe('可读名', () => {
    it('两种回复形态各有各的说法', () => {
      expect(replyTypeLabel('PUBLIC_REPLY')).toBe('公开回复');
      expect(replyTypeLabel('SELLER_COMMENT_APPEND')).toBe('卖家评论追加');
      expect(replyTypeLabel('WHATEVER')).toBe('WHATEVER');
    });

    it('四个档位各有各的说法', () => {
      expect(reviewScopeLabel('POSITIVE')).toBe('好评（4-5 星）');
      expect(reviewScopeLabel('NEUTRAL')).toBe('中评（3 星）');
      expect(reviewScopeLabel('NEGATIVE')).toBe('差评（1-2 星）');
      expect(reviewScopeLabel('ALL')).toBe('不限档位');
    });

    it('模板状态说清「可编辑」与「冻结」', () => {
      expect(templateStatusLabel('DRAFT')).toBe('草稿（可编辑）');
      expect(templateStatusLabel('ACTIVE')).toBe('已生效（内容冻结）');
      expect(templateStatusLabel('ARCHIVED')).toBe('已停用');
    });

    it('投递结果说清「没发出去」', () => {
      expect(deliveryOutcomeLabel('NOT_INTEGRATED')).toBe('未接入（没发出去）');
      expect(deliveryOutcomeLabel('DELIVERED')).toBe('平台已确认');
    });
  });

  describe('「待投递」不等于「已回复」', () => {
    it('待投递的说法必须带上「买家还没收到」', () => {
      const label = replyStatusLabel('PENDING');
      expect(label).toContain('待投递');
      expect(label).toContain('买家还没收到');
      expect(label).not.toBe('已回复');
    });

    it('已确认送达与待投递是两种说法，不能混', () => {
      expect(replyStatusLabel('DELIVERED')).toBe('平台已确认送达');
      expect(replyStatusLabel('PENDING')).not.toBe(replyStatusLabel('DELIVERED'));
      expect(replyStatusLabel('CANCELLED')).toBe('已撤回');
    });
  });

  describe('版本追溯', () => {
    it('用了模板就要显示模板版本与规范版本', () => {
      const label = templateVersionLabel({
        templateKey: 'NEGATIVE_APOLOGY',
        templateVersion: 3,
        templateLocale: 'en-US',
        policyVersion: 2,
      });
      expect(label).toContain('NEGATIVE_APOLOGY');
      expect(label).toContain('第 3 版');
      expect(label).toContain('规范第 2 版');
      expect(label).toContain('en-US');
    });

    it('手写回复要说清「没套模板」，不能显示成模板版本', () => {
      const label = templateVersionLabel({
        templateKey: null,
        templateVersion: null,
        templateLocale: null,
        policyVersion: 2,
      });
      expect(label).toContain('手写回复');
      expect(label).toContain('规范第 2 版');
    });
  });

  describe('批量结果', () => {
    it('成功数与跳过数必须一起说', () => {
      const label = batchSummaryLabel({
        batch: { targetCount: 5, createdCount: 3, skippedCount: 2 },
      });
      expect(label).toContain('5');
      expect(label).toContain('3');
      expect(label).toContain('跳过');
      expect(label).toContain('2');
    });

    it('一条都没跳过时也要说清目标与写成', () => {
      const label = batchSummaryLabel({
        batch: { targetCount: 4, createdCount: 4, skippedCount: 0 },
      });
      expect(label).toContain('4');
      expect(label).not.toContain('跳过');
    });

    it('没有目标时要说「没有匹配到」，不能显示成 0 条成功', () => {
      const label = batchSummaryLabel({
        batch: { targetCount: 0, createdCount: 0, skippedCount: 0 },
      });
      expect(label).toContain('没有匹配到');
    });
  });
});
