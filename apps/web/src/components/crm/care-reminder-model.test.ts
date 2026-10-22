import { describe, expect, it } from 'vitest';
import {
  careReminderBlockLabel,
  careReminderBlockText,
  careReminderEventLabel,
  careReminderMomentText,
  careReminderPaymentDemandAllowed,
  careReminderPaymentDemandHint,
  careReminderStatsText,
  careReminderStatusLabel,
  careReminderTransportLabel,
  careReminderTypeLabel,
} from './care-reminder-model';

/**
 * 15.5.2.1 关怀与催办前端模型的纯规则测试。
 *
 * <p>本文件把**页面最容易做错的几件事**钉死：
 *
 * <ol>
 *   <li><b>拦截原因不能被吃掉</b>：被退订闸 / 频次闸挡下的触达，页面必须显示
 *       <b>后端给的原因原文</b> —— 只写「已拦截」三个字等于把原因藏起来了，
 *       而加粗句要的正是「拒绝并给出明确原因」。</li>
 *   <li><b>催付白名单只有一份</b>：白名单来自服务端能力声明，
 *       页面**不**自己维护一份 —— 两份名单打架的方向往往是
 *       「页面说能配，后端说不能」。</li>
 *   <li><b>统计口径不重算</b>：页面原样展示服务端算好的那串说明，
 *       不自己算打开率 / 点击率。</li>
 *   <li><b>类型 / 状态 / 通道都要有可读名</b>：页面显示的是中文，
 *       英文枚举码只应出现在接口层。</li>
 * </ol>
 */
describe('15.5.2.1 关怀与催办前端模型', () => {
  describe('触达类型', () => {
    it('蓝图点名的六类都有可读名', () => {
      expect(careReminderTypeLabel('SHIPMENT_NOTICE')).toBe('发货通知');
      expect(careReminderTypeLabel('LOGISTICS_REMINDER')).toBe('物流提醒');
      expect(careReminderTypeLabel('PAYMENT_DEMAND')).toBe('催付（未付款订单）');
      expect(careReminderTypeLabel('REVIEW_REQUEST')).toBe('催评');
      expect(careReminderTypeLabel('AFTERSALES_PROGRESS')).toBe('售后进度通知');
      expect(careReminderTypeLabel('CARE_MAIL')).toBe('关怀邮件（节假日 / 生日）');
    });

    it('未知枚举码原样返回，不编一个看起来像真的名字', () => {
      expect(careReminderTypeLabel('SOMETHING_NEW')).toBe('SOMETHING_NEW');
    });
  });

  describe('触达任务状态', () => {
    it('「被拦截」是与「发送失败」**不同**的两件事', () => {
      expect(careReminderStatusLabel('QUEUED')).toBe('已排期待发');
      expect(careReminderStatusLabel('SENT')).toBe('已发出');
      expect(careReminderStatusLabel('FAILED')).toBe('发送失败');
      expect(careReminderStatusLabel('BLOCKED')).toBe('被拦截');
      expect(careReminderStatusLabel('CANCELLED')).toBe('已作废');
      expect(careReminderStatusLabel('BLOCKED')).not.toBe(careReminderStatusLabel('FAILED'));
    });
  });

  describe('拦截原因（加粗句「拒绝并给出明确原因」的落点）', () => {
    it('拦截码有两档，且各有可读名', () => {
      expect(careReminderBlockLabel('OPT_OUT')).toBe('退订拦截');
      expect(careReminderBlockLabel('FREQUENCY_LIMIT')).toBe('频次超限');
    });

    it('原因原文**原样**带出来，不能只剩「已拦截」三个字', () => {
      const reason = '客户已退订（范围 ALL：不要再联系我）：全部触达都排除，本次不发送。';
      const text = careReminderBlockText('OPT_OUT', reason);
      expect(text).toContain('退订拦截');
      expect(text).toContain('不要再联系我');
      expect(text).toContain('ALL');
      // 关键：不能被截断成一句没有原因的「已拦截」。
      expect(text).not.toBe(careReminderBlockLabel('OPT_OUT'));
    });

    it('频次超限的原因里要能看到「上限」这个数字', () => {
      const reason = '近 24 小时内该买家在【催评】下已触达 2 次（已发 1 次 / 已排期 1 次），上限 2 次。';
      const text = careReminderBlockText('FREQUENCY_LIMIT', reason);
      expect(text).toContain('频次超限');
      expect(text).toContain('上限 2 次');
    });

    it('没有原因时退回拦截码本身，不显示空白', () => {
      expect(careReminderBlockText('OPT_OUT', '')).toBe('退订拦截');
      expect(careReminderBlockText('OPT_OUT', '   ')).toBe('退订拦截');
    });
  });

  describe('催付平台白名单（合规边界）', () => {
    const allowed = ['SHOPIFY', 'EBAY', 'TIKTOK_SHOP', 'ALIBABA_1688'];

    it('白名单内的平台允许催付', () => {
      expect(careReminderPaymentDemandAllowed('SHOPIFY', allowed)).toBe(true);
      expect(careReminderPaymentDemandAllowed('EBAY', allowed)).toBe(true);
    });

    it('白名单外的平台**不许**催付', () => {
      expect(careReminderPaymentDemandAllowed('AMAZON', allowed)).toBe(false);
      expect(careReminderPaymentDemandAllowed('TIKTOK', allowed)).toBe(false);
    });

    it('平台码大小写与空格不该让白名单判定翻车', () => {
      expect(careReminderPaymentDemandAllowed(' shopify ', allowed)).toBe(true);
    });

    it('提示语要说清「为什么」与「怎么办」', () => {
      const hint = careReminderPaymentDemandHint(allowed);
      expect(hint).toContain('SHOPIFY');
      expect(hint).toContain('必须按平台配置');
      expect(hint).toContain('催款工具');
    });
  });

  describe('统计（口径服务端算）', () => {
    it('统计摘要**原样**展示，不重算', () => {
      const note = '触达总数 2（已发 1 / 失败 0 / 被拦截 1 / 待发 0 / 已作废 0）；打开 2 次、点击 1 次。';
      expect(careReminderStatsText(note)).toBe(note);
    });
  });

  describe('时刻与通道', () => {
    it('时刻原样显示（去掉 T 与时区尾巴），不做时区换算', () => {
      expect(careReminderMomentText('2026-10-22T02:00:00Z')).toBe('2026-10-22 02:00:00');
      expect(careReminderMomentText('2026-10-22T10:00:00+08:00')).toBe('2026-10-22 10:00:00');
    });

    it('空时刻显示占位符，不显示空白', () => {
      expect(careReminderMomentText(null)).toBe('—');
      expect(careReminderMomentText('')).toBe('—');
    });

    it('通道必须说清「平台侧未接入」', () => {
      expect(careReminderTransportLabel('MANUAL_HANDOFF')).toBe('人工交接');
      expect(careReminderTransportLabel('PLATFORM_API')).toContain('未接入');
    });

    it('统计事件类型可读名', () => {
      expect(careReminderEventLabel('OPEN')).toBe('打开');
      expect(careReminderEventLabel('CLICK')).toBe('点击');
    });
  });
});
