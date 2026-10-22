import { describe, expect, it } from 'vitest';
import {
  serviceTicketProcessActionLabel,
  serviceTicketProcessActionTitle,
  serviceTicketProcessChannelLabel,
  serviceTicketProcessCloseReasonLabel,
  serviceTicketProcessLinkTypeLabel,
  serviceTicketProcessPriorityLabel,
  serviceTicketProcessPriorityTone,
  serviceTicketProcessRemainingText,
  serviceTicketProcessResolutionLabel,
  serviceTicketProcessSlaStateLabel,
  serviceTicketProcessSlaTargetLabel,
  serviceTicketProcessSlaTone,
  serviceTicketProcessStatusLabel,
  serviceTicketProcessTypeLabel,
} from './service-ticket-process-model';

/**
 * 15.4.1.2 工单处理前端模型的纯规则测试。
 *
 * <p>本文件把**页面最容易做错的几件事**钉死：
 *
 * <ol>
 *   <li><b>剩余量不许被页面换算成自然时间</b>：后端给的是**营业时间口径**的剩余秒数
 *       （且**已扣除暂停区间**），页面只把它变成人话，<b>不乘 24 小时</b>。</li>
 *   <li><b>「暂停中」是第四种状态</b>：既不是「还在走表」（PENDING），
 *       也不是「已经超期」（BREACHED）—— 冻结期间 SLA 不消耗，
 *       把它混进这两档，现场要么以为这单还在走表，要么冤枉客服。</li>
 *   <li><b>「未配营业时间」不是「没有时限」</b>：两种都说「没有时限」，
 *       现场会以为「这单不考核」，而真相是「我们还没拿到这家店的营业时间」。</li>
 *   <li><b>英文枚举码只应出现在接口层</b>：类型 / 状态 / 优先级 / 关闭原因 /
 *       解决结果 / 回复渠道 / 暂停目标都要有可读名。</li>
 * </ol>
 */
describe('15.4.1.2 工单处理前端模型', () => {
  describe('SLA 状态（营业时间口径，暂停感知）', () => {
    it('六种状态各有可读名，其中「暂停中」是**独立的一档**', () => {
      expect(serviceTicketProcessSlaStateLabel('NO_DEADLINE')).toBe('无时限');
      expect(serviceTicketProcessSlaStateLabel('NO_CALENDAR')).toBe('未配营业时间');
      expect(serviceTicketProcessSlaStateLabel('PENDING')).toBe('计时中');
      expect(serviceTicketProcessSlaStateLabel('PAUSED')).toBe('暂停中');
      expect(serviceTicketProcessSlaStateLabel('MET')).toBe('已达标');
      expect(serviceTicketProcessSlaStateLabel('BREACHED')).toBe('已超期');
      // 🔴 暂停中既不是「计时中」也不是「已超期」。
      expect(serviceTicketProcessSlaStateLabel('PAUSED')).not.toBe(
        serviceTicketProcessSlaStateLabel('PENDING'),
      );
      expect(serviceTicketProcessSlaStateLabel('PAUSED')).not.toBe(
        serviceTicketProcessSlaStateLabel('BREACHED'),
      );
      // 「未配营业时间」与「无时限」不是同一件事。
      expect(serviceTicketProcessSlaStateLabel('NO_CALENDAR')).not.toBe(
        serviceTicketProcessSlaStateLabel('NO_DEADLINE'),
      );
    });

    it('语义色跟状态挂钩：达标绿、计时黄、暂停蓝、超期红，其余中性', () => {
      expect(serviceTicketProcessSlaTone('MET')).toBe('success');
      expect(serviceTicketProcessSlaTone('PENDING')).toBe('warning');
      expect(serviceTicketProcessSlaTone('PAUSED')).toBe('info');
      expect(serviceTicketProcessSlaTone('BREACHED')).toBe('danger');
      expect(serviceTicketProcessSlaTone('NO_CALENDAR')).toBe('neutral');
      expect(serviceTicketProcessSlaTone('NO_DEADLINE')).toBe('neutral');
    });

    it('未知状态原样返回，不编一个看起来像真的名字', () => {
      expect(serviceTicketProcessSlaStateLabel('SOMETHING_NEW')).toBe('SOMETHING_NEW');
    });
  });

  describe('剩余时间文案（营业时间口径）', () => {
    it('正数是「还剩」，并且写明是营业时间', () => {
      expect(serviceTicketProcessRemainingText(3 * 3600 + 20 * 60)).toBe(
        '还剩 3 小时 20 分钟（营业时间）',
      );
      expect(serviceTicketProcessRemainingText(7200)).toBe('还剩 2 小时（营业时间）');
      expect(serviceTicketProcessRemainingText(86400 + 3600)).toBe('还剩 1 天 1 小时（营业时间）');
    });

    it('负数是「已超期」，而且同样写明是营业时间口径', () => {
      expect(serviceTicketProcessRemainingText(-(3 * 3600 + 20 * 60))).toBe(
        '已超期 3 小时 20 分钟（营业时间）',
      );
    });

    it('不足 1 分钟也给一句话，不显示空白', () => {
      expect(serviceTicketProcessRemainingText(30)).toBe('还剩 不足 1 分钟（营业时间）');
      expect(serviceTicketProcessRemainingText(0)).toBe('还剩 不足 1 分钟（营业时间）');
    });

    it('非有限数返回占位符，不显示 NaN', () => {
      expect(serviceTicketProcessRemainingText(Number.NaN)).toBe('—');
    });
  });

  describe('枚举可读名（英文码只应出现在接口层）', () => {
    it('工单类型 / 状态 / 优先级都有可读名', () => {
      expect(serviceTicketProcessTypeLabel('COMPLAINT')).toBe('投诉');
      expect(serviceTicketProcessStatusLabel('WAITING_BUYER')).toBe('待买家');
      expect(serviceTicketProcessPriorityLabel('URGENT')).toBe('紧急');
    });

    it('后端已经给了可读名时优先用后端的，不自己重编一套', () => {
      expect(serviceTicketProcessTypeLabel('COMPLAINT', '客诉')).toBe('客诉');
      expect(serviceTicketProcessStatusLabel('OPEN', '新工单')).toBe('新工单');
      expect(serviceTicketProcessPriorityLabel('HIGH', '加急')).toBe('加急');
    });

    it('优先级语义色：紧急红、高黄、普通蓝、其余中性', () => {
      expect(serviceTicketProcessPriorityTone('URGENT')).toBe('danger');
      expect(serviceTicketProcessPriorityTone('HIGH')).toBe('warning');
      expect(serviceTicketProcessPriorityTone('NORMAL')).toBe('info');
      expect(serviceTicketProcessPriorityTone('LOW')).toBe('neutral');
    });

    it('关闭原因都有可读名 —— 关闭原因决定这单落哪个终态', () => {
      expect(serviceTicketProcessCloseReasonLabel('RESOLVED')).toBe('已解决');
      expect(serviceTicketProcessCloseReasonLabel('NO_RESPONSE')).toBe('买家未回应');
      expect(serviceTicketProcessCloseReasonLabel('DUPLICATE')).toBe('重复工单');
      expect(serviceTicketProcessCloseReasonLabel('CANCELLED')).toBe('与本单无关');
      expect(serviceTicketProcessCloseReasonLabel('ESCALATED')).toBe('已升级转单');
      expect(serviceTicketProcessCloseReasonLabel('ABANDONED')).toBe('不再跟进');
    });

    it('解决结果类型都有可读名 —— 「退款到账」与「换货发出」必须分得开', () => {
      expect(serviceTicketProcessResolutionLabel('REFUND_RECEIVED')).toBe('退款已到账');
      expect(serviceTicketProcessResolutionLabel('REPLACEMENT_SHIPPED')).toBe('换货已发出');
      expect(serviceTicketProcessResolutionLabel('RETURN_RECEIVED')).toBe('退货已签收');
      expect(serviceTicketProcessResolutionLabel('ORDER_CANCELLED')).toBe('订单已取消');
      expect(serviceTicketProcessResolutionLabel('GUIDANCE_ACCEPTED')).toBe('买家接受方案');
      expect(serviceTicketProcessResolutionLabel('OTHER')).toBe('其它结果');
      // 🔴 退款与换货不是同一件事，不能共用一个名字。
      expect(serviceTicketProcessResolutionLabel('REFUND_RECEIVED')).not.toBe(
        serviceTicketProcessResolutionLabel('REPLACEMENT_SHIPPED'),
      );
    });

    it('回复渠道都有可读名 —— 没有渠道的回复事后说不清发到哪儿去了', () => {
      expect(serviceTicketProcessChannelLabel('PLATFORM_MESSAGE')).toBe('平台站内信');
      expect(serviceTicketProcessChannelLabel('EMAIL')).toBe('邮件');
      expect(serviceTicketProcessChannelLabel('CHAT')).toBe('在线会话');
      expect(serviceTicketProcessChannelLabel('PHONE')).toBe('电话');
      expect(serviceTicketProcessChannelLabel('OTHER')).toBe('其它渠道');
    });

    it('SLA 暂停目标都有可读名', () => {
      expect(serviceTicketProcessSlaTargetLabel('REPLY')).toBe('回复时限');
      expect(serviceTicketProcessSlaTargetLabel('RESOLVE')).toBe('处理时限');
      expect(serviceTicketProcessSlaTargetLabel('BOTH')).toBe('回复与处理时限');
    });

    it('关联类型与动作类型都有可读名', () => {
      expect(serviceTicketProcessLinkTypeLabel('ORDER')).toBe('关联订单');
      expect(serviceTicketProcessLinkTypeLabel('AFTER_SALES_CASE')).toBe('关联售后单');
      expect(serviceTicketProcessActionLabel('RESOLUTION_CONFIRM')).toBe('解决确认');
      expect(serviceTicketProcessActionLabel('SLA_PAUSE')).toBe('SLA 暂停');
      expect(serviceTicketProcessActionTitle('CLOSE')).toBe('关闭工单');
    });
  });
});
