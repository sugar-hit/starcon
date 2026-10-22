import { describe, expect, it } from 'vitest';
import {
  businessHourWeekdayLabel,
  serviceTicketPriorityLabel,
  serviceTicketPriorityTone,
  serviceTicketRemainingText,
  serviceTicketSlaStateLabel,
  serviceTicketSlaTone,
  serviceTicketStatusLabel,
  serviceTicketTypeLabel,
} from './service-ticket-list-model';

/**
 * 15.4.1.1 工单列表前端模型的纯规则测试。
 *
 * <p>本文件把**页面最容易做错的几件事**钉死：
 *
 * <ol>
 *   <li><b>剩余量不许被页面换算成自然时间</b>：后端给的是**营业时间口径**的剩余秒数，
 *       页面只把它变成人话，<b>不乘 24 小时</b>。页面一旦自己换算，
 *       加粗句「夜间积压会造成误判」就又回来了，而且看起来一切正常。</li>
 *   <li><b>「未配营业时间」不是「没有时限」</b>：两种都说「没有时限」，
 *       现场会以为「这单不考核」，而真相是「我们还没拿到这家店的营业时间」。</li>
 *   <li><b>逾期与已达标必须是两种颜色</b>：页面按后端给的状态上色，
 *       不自己从剩余秒数推断状态（推断的那一套迟早和后端漂移）。</li>
 *   <li><b>类型 / 状态 / 优先级都要有可读名</b>：英文枚举码只应出现在接口层。</li>
 * </ol>
 */
describe('15.4.1.1 工单列表前端模型', () => {
  describe('SLA 状态（营业时间口径）', () => {
    it('五种状态各有可读名，其中「未配营业时间」与「无时限」**不是同一件事**', () => {
      expect(serviceTicketSlaStateLabel('NO_DEADLINE')).toBe('无时限');
      expect(serviceTicketSlaStateLabel('NO_CALENDAR')).toBe('未配营业时间');
      expect(serviceTicketSlaStateLabel('PENDING')).toBe('计时中');
      expect(serviceTicketSlaStateLabel('MET')).toBe('已达标');
      expect(serviceTicketSlaStateLabel('BREACHED')).toBe('已超期');
      expect(serviceTicketSlaStateLabel('NO_CALENDAR')).not.toBe(
        serviceTicketSlaStateLabel('NO_DEADLINE'),
      );
    });

    it('语义色跟状态挂钩：达标绿、计时黄、超期红，其余中性', () => {
      expect(serviceTicketSlaTone('MET')).toBe('success');
      expect(serviceTicketSlaTone('PENDING')).toBe('warning');
      expect(serviceTicketSlaTone('BREACHED')).toBe('danger');
      expect(serviceTicketSlaTone('NO_CALENDAR')).toBe('neutral');
      expect(serviceTicketSlaTone('NO_DEADLINE')).toBe('neutral');
    });

    it('未知状态原样返回，不编一个看起来像真的名字', () => {
      expect(serviceTicketSlaStateLabel('SOMETHING_NEW')).toBe('SOMETHING_NEW');
    });
  });

  describe('剩余时间文案（营业时间口径）', () => {
    it('正数是「还剩」，并且写明是营业时间', () => {
      expect(serviceTicketRemainingText(3 * 3600 + 20 * 60)).toBe(
        '还剩 3 小时 20 分钟（营业时间）',
      );
      expect(serviceTicketRemainingText(7200)).toBe('还剩 2 小时（营业时间）');
    });

    it('负数是「已超期」，写明超了多少（营业时间）', () => {
      expect(serviceTicketRemainingText(-(3600 + 300))).toBe('已超期 1 小时 5 分钟（营业时间）');
    });

    it('不足一分钟也有一句话，不显示空白', () => {
      expect(serviceTicketRemainingText(30)).toBe('还剩 不足 1 分钟（营业时间）');
    });

    it('超过一天用「天」表达，不静默截断', () => {
      expect(serviceTicketRemainingText(2 * 86400 + 3600)).toBe('还剩 2 天 1 小时（营业时间）');
    });

    it('非有限值显示占位符，不显示 NaN', () => {
      expect(serviceTicketRemainingText(Number.NaN)).toBe('—');
    });
  });

  describe('类型 / 状态 / 优先级', () => {
    it('蓝图点名的四类工单都有可读名', () => {
      expect(serviceTicketTypeLabel('INQUIRY')).toBe('咨询');
      expect(serviceTicketTypeLabel('COMPLAINT')).toBe('投诉');
      expect(serviceTicketTypeLabel('ORDER_CHASE')).toBe('催单');
      expect(serviceTicketTypeLabel('POLICY_CONSULT')).toBe('平台政策咨询');
    });

    it('五态工单都有可读名', () => {
      expect(serviceTicketStatusLabel('OPEN')).toBe('待处理');
      expect(serviceTicketStatusLabel('IN_PROGRESS')).toBe('处理中');
      expect(serviceTicketStatusLabel('WAITING_BUYER')).toBe('待买家');
      expect(serviceTicketStatusLabel('RESOLVED')).toBe('已关闭');
      expect(serviceTicketStatusLabel('CANCELLED')).toBe('已取消');
    });

    it('四档优先级都有可读名，紧急最重', () => {
      expect(serviceTicketPriorityLabel('LOW')).toBe('低');
      expect(serviceTicketPriorityLabel('NORMAL')).toBe('普通');
      expect(serviceTicketPriorityLabel('HIGH')).toBe('高');
      expect(serviceTicketPriorityLabel('URGENT')).toBe('紧急');
      expect(serviceTicketPriorityTone('URGENT')).toBe('danger');
      expect(serviceTicketPriorityTone('HIGH')).toBe('warning');
      expect(serviceTicketPriorityTone('NORMAL')).toBe('info');
      expect(serviceTicketPriorityTone('LOW')).toBe('neutral');
    });

    it('后端给了标签就用后端的（页面不自己另立一套中文名）', () => {
      expect(serviceTicketTypeLabel('INQUIRY', '咨询（来自消息）')).toBe('咨询（来自消息）');
      expect(serviceTicketStatusLabel('OPEN', '待处理（客服）')).toBe('待处理（客服）');
      expect(serviceTicketPriorityLabel('HIGH', '高（可升级）')).toBe('高（可升级）');
    });
  });

  describe('营业时间工作日', () => {
    it('ISO-8601：1 = 周一 … 7 = 周日', () => {
      expect(businessHourWeekdayLabel(1)).toBe('周一');
      expect(businessHourWeekdayLabel(5)).toBe('周五');
      expect(businessHourWeekdayLabel(7)).toBe('周日');
    });
  });
});
