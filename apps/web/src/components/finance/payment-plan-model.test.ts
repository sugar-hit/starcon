import { describe, expect, it } from 'vitest';
import type { FinancePaymentPlanRow } from '../../../../../packages/api-contracts/src/index';
import {
  forecastNetHint,
  formatMilestoneRatio,
  formatPaymentAmount,
  formatPaymentMoney,
  paymentConditionHint,
  paymentConditionLabel,
  paymentExecutionLabel,
  paymentPlanDateHint,
  paymentPlanEventLabel,
  paymentPlanStatusLabel,
  paymentPlanStatusTone,
  reminderPushLabel,
  reminderStatusLabel,
  reminderStatusTone,
  reminderTypeLabel,
  reminderTypeTone,
  termBaseKindLabel,
} from './payment-plan-model';

/**
 * 12.1.2.2 付款计划前端模型的单测。
 *
 * <p>重点守住四条**界面纪律**（它们一旦被破坏，页面就会开始骗人）：
 *
 * <ol>
 *   <li>金额**原样展示**，前端不做任何业务算术；</li>
 *   <li>「已安排」**不等于**「已付款」—— 页面绝不能把它渲染成成功态；</li>
 *   <li>提醒的推送状态**恒为未推送**（通道属 1.3，未交付）—— 页面不许显示「已通知」；</li>
 *   <li>条件日与计划日不一致时**必须让人看见**（否则「为什么这笔钱提前/延后付」答不出来）。</li>
 * </ol>
 */

const plan = (over: Partial<FinancePaymentPlanRow>): FinancePaymentPlanRow => ({
  id: '11111111-1111-1111-1111-111111111111',
  planNo: 'PLN-ABC',
  payableId: '22222222-2222-2222-2222-222222222222',
  payableNo: 'AP-ABC',
  batchId: null,
  batchNo: null,
  paymentCondition: 'DUE_DATE',
  termRuleId: null,
  milestoneId: null,
  milestoneName: null,
  conditionDate: '2026-10-31',
  plannedPaidDate: '2026-10-31',
  clamped: false,
  currency: 'USD',
  plannedAmount: 100,
  unpaidSnapshot: 100,
  currentUnpaid: 100,
  status: 'PLANNED',
  executionOwnerModule: '12.2.2.1',
  executionAvailable: false,
  note: '',
  createdAt: '2026-10-23T00:00:00Z',
  updatedAt: '2026-10-23T00:00:00Z',
  ...over,
});

describe('付款条件的可读名与口径', () => {
  it('三种条件各有可读名，且不认识的原样返回', () => {
    expect(paymentConditionLabel('TERM')).toBe('账期');
    expect(paymentConditionLabel('MILESTONE')).toBe('里程碑');
    expect(paymentConditionLabel('DUE_DATE')).toBe('到期日');
    expect(paymentConditionLabel('SOMETHING_NEW')).toBe('SOMETHING_NEW');
  });

  it('三种条件是三种口径，不能被渲染成同一件事', () => {
    const hints = [
      paymentConditionHint('TERM'),
      paymentConditionHint('MILESTONE'),
      paymentConditionHint('DUE_DATE'),
    ];
    expect(new Set(hints).size).toBe(3);
  });
});

describe('计划状态', () => {
  it('「已安排」不等于「已付款」—— 不能渲染成成功态', () => {
    expect(paymentPlanStatusLabel('PLANNED')).toBe('已生成');
    expect(paymentPlanStatusLabel('SCHEDULED')).toBe('已安排');
    expect(paymentPlanStatusLabel('CANCELLED')).toBe('已取消');
    // ⚠️ 已安排与已生成都只是「还没付」⇒ 都不能是 success。
    expect(paymentPlanStatusTone('SCHEDULED')).not.toBe('success');
    expect(paymentPlanStatusTone('PLANNED')).not.toBe('success');
    expect(paymentPlanStatusTone('CANCELLED')).toBe('neutral');
  });
});

describe('计划日与条件日', () => {
  it('两者一致时只显示计划付款日', () => {
    expect(paymentPlanDateHint(plan({ clamped: false }))).toBe('计划付款日 2026-10-31');
  });

  it('被付款窗口压过时必须显示「条件日 → 计划日」，让人看出为什么改了', () => {
    const hint = paymentPlanDateHint(
      plan({ clamped: true, conditionDate: '2026-12-01', plannedPaidDate: '2026-11-30' }),
    );
    expect(hint).toContain('2026-12-01');
    expect(hint).toContain('2026-11-30');
    expect(hint).toContain('→');
  });
});

describe('提醒', () => {
  it('三种提醒类型各有可读名与色调', () => {
    expect(reminderTypeLabel('UPCOMING')).toBe('即将到期');
    expect(reminderTypeLabel('DUE')).toBe('今日到期');
    expect(reminderTypeLabel('OVERDUE')).toBe('已逾期');
    expect(reminderTypeTone('OVERDUE')).toBe('danger');
    expect(reminderTypeTone('UPCOMING')).not.toBe('danger');
  });

  it('处理状态可读名与色调', () => {
    expect(reminderStatusLabel('PENDING')).toBe('待处理');
    expect(reminderStatusLabel('ACKNOWLEDGED')).toBe('已确认');
    expect(reminderStatusLabel('DISMISSED')).toBe('已忽略');
    expect(reminderStatusTone('ACKNOWLEDGED')).toBe('success');
  });

  it('推送通道属 1.3（未交付）⇒ 显示「未推送」，绝不显示「已通知」', () => {
    const label = reminderPushLabel('NOT_PUSHED');
    expect(label).toContain('未推送');
    expect(label).toContain('1.3');
    expect(label).not.toContain('已通知');
    expect(label).not.toContain('已发送');
  });
});

describe('付款执行', () => {
  it('执行不可用时要说清归属模块，不许显示成「已付」', () => {
    const label = paymentExecutionLabel(false, '12.2.2.1');
    expect(label).toContain('不可用');
    expect(label).toContain('12.2.2.1');
    expect(label).not.toContain('已付');
  });
});

describe('金额形态', () => {
  it('原样展示四位小数，不在前端做算术', () => {
    expect(formatPaymentAmount(12.3456)).toBe('12.3456');
    expect(formatPaymentMoney(12.3456, 'USD')).toBe('12.3456 USD');
  });

  it('没有值时显示「—」而不是 0（两者含义不同）', () => {
    expect(formatPaymentAmount(null)).toBe('—');
    expect(formatPaymentAmount(undefined)).toBe('—');
    expect(formatPaymentMoney(null, 'USD')).toBe('—');
  });
});

describe('里程碑比例与预测净额', () => {
  it('比例换算成百分数', () => {
    expect(formatMilestoneRatio(0.3)).toBe('30.00%');
    expect(formatMilestoneRatio(0.7)).toBe('70.00%');
    expect(formatMilestoneRatio(null)).toBe('—');
  });

  it('净额只是显示，不重算；净流出要标出风险', () => {
    expect(forecastNetHint(-60, true)).toContain('-60.0000');
    expect(forecastNetHint(-60, true)).toContain('现金流断裂风险');
    expect(forecastNetHint(40, false)).toBe('+40.0000');
  });
});

describe('其它可读形态', () => {
  it('账期起算基准', () => {
    expect(termBaseKindLabel('CONFIRMED_DATE')).toBe('确认日');
    expect(termBaseKindLabel('DUE_DATE')).toBe('到期日');
  });

  it('事件类型', () => {
    expect(paymentPlanEventLabel('GENERATED')).toBe('生成计划');
    expect(paymentPlanEventLabel('SCHEDULED')).toBe('排入付款窗口');
    expect(paymentPlanEventLabel('RESCHEDULED')).toBe('重新排期');
    expect(paymentPlanEventLabel('CANCELLED')).toBe('取消计划');
    expect(paymentPlanEventLabel('REMINDER_ACKNOWLEDGED')).toBe('确认提醒');
    expect(paymentPlanEventLabel('REMINDER_DISMISSED')).toBe('忽略提醒');
  });
});
