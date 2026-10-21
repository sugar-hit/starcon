import { describe, expect, it } from 'vitest';
import {
  arrivalStatusLabel,
  averageDelayText,
  delayDaysText,
  delayDaysTone,
  followupChannelLabel,
  followupTypeLabel,
  fmtDate,
  onTimeRateText,
  overdueDaysText,
  promiseText,
  purchaseFollowupChangeQuery,
  purchaseFollowupDeliveryQuery,
  purchaseFollowupQuery,
  timelineKindLabel,
  writebackStatusText,
  writebackTargetLabel,
  writebackTone,
} from './purchase-followup-model';

/**
 * 6.3.2.1 跟单与交期的模型单测。
 *
 * <p>重点钉三件事：
 *
 * <ul>
 *   <li><b>null 与 0 严格分开</b>：没到货（null）与准时（0）是两件事，
 *       混成一个会让「还没到」看起来像「刚好到」。</li>
 *   <li><b>负数延迟是「提前」不是「逾期」</b>：供应商提前到货是好事，
 *       把它显示成逾期会把守约说成违约。</li>
 *   <li><b>两个承诺口径都要显示</b>：不一致时不能只显示一个。</li>
 * </ul>
 */
describe('6.3.2.1 跟单与交期 · 模型', () => {
  it('延迟天数：null 是「未到货（不计延迟）」，0 是「准时」', () => {
    expect(delayDaysText(null)).toBe('未到货（不计延迟）');
    expect(delayDaysText(undefined)).toBe('未到货（不计延迟）');
    expect(delayDaysText(0)).toBe('准时');
  });

  it('延迟天数：正数是延迟，负数是提前', () => {
    expect(delayDaysText(5)).toBe('延迟 5 天');
    expect(delayDaysText(-2)).toBe('提前 2 天');
  });

  it('延迟天数色调：正数为负面、负数为正面、null 与 0 为中性', () => {
    expect(delayDaysTone(5)).toBe('negative');
    expect(delayDaysTone(-2)).toBe('positive');
    expect(delayDaysTone(0)).toBe('neutral');
    expect(delayDaysTone(null)).toBe('neutral');
  });

  it('平均延迟天数：没有已到货样本时是「无数据」而不是 0', () => {
    expect(averageDelayText(null)).toBe('无数据（尚无已到货样本）');
    expect(averageDelayText(0)).toBe('0 天');
    expect(averageDelayText(3.25)).toBe('延迟 3.25 天');
    expect(averageDelayText(-1.5)).toBe('提前 1.5 天');
  });

  it('准时率：后端给的是百分数，不是 0~1 的比例', () => {
    expect(onTimeRateText(null)).toBe('无数据（尚无已到货样本）');
    expect(onTimeRateText(100)).toBe('100.00%');
    expect(onTimeRateText(85.71)).toBe('85.71%');
  });

  it('已逾期天数：没有值时是破折号而不是 0 天', () => {
    expect(overdueDaysText(null)).toBe('—');
    expect(overdueDaysText(3)).toBe('已逾期 3 天');
  });

  it('两个承诺口径一致时只显示一个，不一致时两个都摆出来', () => {
    expect(
      promiseText({
        documentPromiseDate: '2026-04-10',
        followupPromiseDate: '2026-04-10',
        promiseDiverged: false,
      }),
    ).toBe('承诺 2026-04-10');
    expect(
      promiseText({
        documentPromiseDate: '2026-04-10',
        followupPromiseDate: '2026-04-20',
        promiseDiverged: true,
      }),
    ).toBe('单据承诺 2026-04-10 / 跟单承诺 2026-04-20（两个口径不同）');
  });

  it('承诺日缺失时不显示 undefined', () => {
    expect(
      promiseText({
        documentPromiseDate: null,
        followupPromiseDate: null,
        promiseDiverged: false,
      }),
    ).toBe('承诺 —');
  });

  it('回写状态：成功 / 跳过 / 失败各有文案，「交期未进参数」单独标出来', () => {
    const base = {
      id: 'w1',
      targetModule: '6.2.1.1',
      targetEndpoint: 'POST /trade/stockout-risk/snapshots',
      ownerModule: '6.2.1.1',
      resultRef: 'SNAP-1',
      resultSummary: '',
      errorMessage: '',
      impact: '',
      attemptedAt: '2026-03-12T09:30:00Z',
    };
    expect(
      writebackStatusText({
        ...base,
        available: true,
        deliveryInputApplied: true,
        status: 'SUCCEEDED',
      }),
    ).toBe('已回写（交期已进参数）');
    expect(
      writebackStatusText({
        ...base,
        available: true,
        deliveryInputApplied: false,
        status: 'SUCCEEDED',
      }),
    ).toBe('已触发重算（交期数值未进参数）');
    expect(
      writebackStatusText({
        ...base,
        available: false,
        deliveryInputApplied: false,
        status: 'SKIPPED',
      }),
    ).toBe('未回写（缺少前提事实）');
    expect(
      writebackStatusText({
        ...base,
        available: false,
        deliveryInputApplied: false,
        status: 'FAILED',
      }),
    ).toBe('回写失败');
  });

  it('回写色调：失败为负面、跳过为中性', () => {
    const base = {
      id: 'w1',
      targetModule: '6.2.1.2',
      targetEndpoint: 'POST /trade/replenishment-suggestions/batches',
      ownerModule: '6.2.1.2',
      resultRef: '',
      resultSummary: '',
      errorMessage: '',
      impact: '',
      attemptedAt: '2026-03-12T09:30:00Z',
    };
    expect(
      writebackTone({ ...base, available: true, deliveryInputApplied: false, status: 'SUCCEEDED' }),
    ).toBe('positive');
    expect(
      writebackTone({ ...base, available: false, deliveryInputApplied: false, status: 'FAILED' }),
    ).toBe('negative');
    expect(
      writebackTone({ ...base, available: false, deliveryInputApplied: false, status: 'SKIPPED' }),
    ).toBe('neutral');
  });

  it('回写目标模块有中文名，未知模块原样透出', () => {
    expect(writebackTargetLabel('6.2.1.1')).toBe('6.2.1.1 断货风险');
    expect(writebackTargetLabel('6.2.1.2')).toBe('6.2.1.2 补货建议');
    expect(writebackTargetLabel('9.9.9.9')).toBe('9.9.9.9');
  });

  it('跟单类型、渠道、到货状态、时间线类型都有中文名', () => {
    expect(followupTypeLabel('URGING')).toBe('催单');
    expect(followupTypeLabel('CONFIRM')).toBe('确认');
    expect(followupTypeLabel('EXCEPTION')).toBe('异常反馈');
    expect(followupTypeLabel('OTHER')).toBe('其他');
    expect(followupChannelLabel('PHONE')).toBe('电话');
    expect(followupChannelLabel('WECHAT')).toBe('微信');
    expect(arrivalStatusLabel('FULLY_RECEIVED')).toBe('全部到货');
    expect(arrivalStatusLabel('PARTIALLY_RECEIVED')).toBe('部分到货');
    expect(arrivalStatusLabel(null)).toBe('未跟踪');
    expect(timelineKindLabel('DELIVERY_CHANGE')).toBe('交期变更');
    expect(timelineKindLabel('FOLLOWUP')).toBe('跟单记录');
  });

  it('日期原样显示（承诺到货日是日历日，不做时区换算）', () => {
    expect(fmtDate('2026-04-10')).toBe('2026-04-10');
    expect(fmtDate(null)).toBe('—');
    expect(fmtDate('')).toBe('—');
  });

  it('查询串：空条件不产生问号，条件逐个编码', () => {
    expect(purchaseFollowupQuery({ page: 0, size: 20 })).toBe('?page=0&size=20');
    expect(purchaseFollowupQuery({})).toBe('');
    expect(purchaseFollowupQuery({ purchaseOrderNo: 'PO 1' })).toBe('?purchaseOrderNo=PO%201');
    expect(purchaseFollowupChangeQuery({ keyword: '  交期  ' })).toBe(
      '?keyword=%E4%BA%A4%E6%9C%9F',
    );
    expect(purchaseFollowupDeliveryQuery({ onlyOverdue: true, onlyDelayed: false })).toBe(
      '?onlyOverdue=true',
    );
  });
});
