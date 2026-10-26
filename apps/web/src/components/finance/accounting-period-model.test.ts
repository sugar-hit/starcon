import { describe, expect, it } from 'vitest';
import {
  accountingApprovalActionLabel,
  accountingCalendarTypeLabel,
  accountingCloseStateText,
  accountingFiscalPositionText,
  accountingPeriodEventText,
  accountingPeriodGenerateSummary,
  accountingPeriodRangeText,
  accountingPeriodStatusLabel,
  accountingPeriodStatusTone,
  accountingPeriodTypeLabel,
  accountingReopenActuallyApplied,
  accountingReopenOutcomeText,
  accountingReopenOutcomeTone,
} from './accounting-period-model';

/**
 * 12.5.1.2 会计期间的前端模型断言。
 *
 * <p>这几条守的是**界面纪律**，不是排版：
 *
 * <ul>
 *   <li>两种账期体系（自然月 / 4-4-5）各有一个可读名 —— 报表按财年取数时必须分得清；
 *   <li>「已关闭」用 **warning** 而不是 success / neutral —— 关闭是需要人盯着的状态；
 *   <li>fiscal 位置：自定义期间**没有** fiscal 周期，界面如实说「自定义」而不是编一个；
 *   <li>「关过又被重开」与「从来没关过」是**两回事**，界面必须分得清；
 *   <li>🔴 **重开的四种结局必须分开说**：只有「已重开」才真的开了，
 *       其余三种期间**仍然关闭** —— 说成「已重开」就是撒谎；
 *   <li>生成期间**必须逐条报出被跳过的编码** —— 「生成了 12 个」不说清哪几个没进来，
 *       现场会以为整年都建好了；
 *   <li>留痕要能看出「从什么状态到什么状态、为什么」。
 * </ul>
 */
describe('12.5.1.2 会计期间 · 前端模型', () => {
  it('两种账期体系各有一个可读名，未知值原样返回', () => {
    expect(accountingPeriodTypeLabel('CALENDAR_MONTH')).toBe('自然月');
    expect(accountingPeriodTypeLabel('FISCAL_445')).toBe('4-4-5 周期');
    expect(accountingPeriodTypeLabel('CUSTOM')).toBe('自定义');
    expect(accountingPeriodTypeLabel('UNKNOWN')).toBe('UNKNOWN');
    expect(accountingCalendarTypeLabel('FISCAL_445')).toBe('4-4-5 周期');
  });

  it('期间状态各有一个可读名', () => {
    expect(accountingPeriodStatusLabel('OPEN')).toBe('开放');
    expect(accountingPeriodStatusLabel('CLOSED')).toBe('关闭');
    expect(accountingPeriodStatusLabel('UNKNOWN')).toBe('UNKNOWN');
  });

  it('「已关闭」是 warning 而不是 success —— 关闭不是「完成」', () => {
    expect(accountingPeriodStatusTone('OPEN')).toBe('success');
    expect(accountingPeriodStatusTone('CLOSED')).toBe('warning');
    // 关闭绝不能被渲染成绿色（那会让人以为这期没问题）。
    expect(accountingPeriodStatusTone('CLOSED')).not.toBe('success');
  });

  it('fiscal 位置：自定义期间没有 fiscal 周期，如实说「自定义」', () => {
    expect(
      accountingFiscalPositionText({
        periodType: 'CUSTOM',
        fiscalYear: null,
        fiscalPeriodNo: null,
      }),
    ).toBe('自定义（不绑定会计日历）');
    expect(
      accountingFiscalPositionText({
        periodType: 'CALENDAR_MONTH',
        fiscalYear: 2026,
        fiscalPeriodNo: 4,
      }),
    ).toBe('财年 2026 第 4 期');
    // 日历派生的期间缺了 fiscal 字段时不能编一个出来。
    expect(
      accountingFiscalPositionText({
        periodType: 'FISCAL_445',
        fiscalYear: null,
        fiscalPeriodNo: null,
      }),
    ).toBe('—');
  });

  it('「关过又被重开」与「从来没关过」是两回事', () => {
    expect(
      accountingCloseStateText({
        status: 'CLOSED',
        closedAt: '2026-10-31T02:00:00Z',
        closedReason: '月末关账',
        reopenCount: 0,
      }),
    ).toBe('已关账（月末关账）');
    expect(
      accountingCloseStateText({
        status: 'OPEN',
        closedAt: '2026-10-31T02:00:00Z',
        closedReason: '月末关账',
        reopenCount: 2,
      }),
    ).toBe('开放（曾关账，已重开 2 次）');
    expect(
      accountingCloseStateText({
        status: 'OPEN',
        closedAt: null,
        closedReason: '',
        reopenCount: 0,
      }),
    ).toBe('开放');
  });

  it('🔴 重开的四种结局分开说 —— 只有「已重开」才真的开了', () => {
    expect(accountingReopenOutcomeText('REOPEN_REQUESTED')).toBe('重开申请已提交审批');
    expect(accountingReopenOutcomeText('PENDING_APPROVAL')).toBe('审批中');
    expect(accountingReopenOutcomeText('REJECTED')).toBe('审批已驳回');
    expect(accountingReopenOutcomeText('REOPENED')).toBe('已重开');

    // 关键断言：只有 REOPENED 才算真的执行了重开。
    expect(accountingReopenActuallyApplied('REOPENED')).toBe(true);
    expect(accountingReopenActuallyApplied('REOPEN_REQUESTED')).toBe(false);
    expect(accountingReopenActuallyApplied('PENDING_APPROVAL')).toBe(false);
    expect(accountingReopenActuallyApplied('REJECTED')).toBe(false);
  });

  it('重开结局的语义色：驳回是 danger，在审是 warning', () => {
    expect(accountingReopenOutcomeTone('REOPENED')).toBe('success');
    expect(accountingReopenOutcomeTone('REOPEN_REQUESTED')).toBe('warning');
    expect(accountingReopenOutcomeTone('PENDING_APPROVAL')).toBe('warning');
    expect(accountingReopenOutcomeTone('REJECTED')).toBe('danger');
  });

  it('生成期间必须逐条报出被跳过的编码', () => {
    const summary = accountingPeriodGenerateSummary({
      fiscalYear: 2026,
      created: 12,
      skipped: 2,
      skippedCodes: ['STD-FY2026-01', 'STD-FY2026-02'],
    });
    expect(summary).toContain('财年 2026');
    expect(summary).toContain('12 个期间');
    expect(summary).toContain('跳过 2 个');
    expect(summary).toContain('STD-FY2026-01');
    expect(summary).toContain('STD-FY2026-02');

    // 一个都没跳过时不要留「跳过 0 个」的废话。
    expect(
      accountingPeriodGenerateSummary({
        fiscalYear: 2026,
        created: 12,
        skipped: 0,
        skippedCodes: [],
      }),
    ).not.toContain('跳过');
  });

  it('期间区间连周期长度一起说', () => {
    expect(
      accountingPeriodRangeText({
        periodStart: '2026-04-01',
        periodEnd: '2026-04-30',
        periodLengthDays: 30,
      }),
    ).toBe('2026-04-01 ~ 2026-04-30（30 天）');
  });

  it('留痕要能看出「从什么状态到什么状态、为什么」', () => {
    expect(
      accountingPeriodEventText({
        eventType: 'CLOSED',
        eventTypeLabel: '关账',
        fromStatus: 'OPEN',
        toStatus: 'CLOSED',
        reason: '月末关账',
        detail: '期间关闭',
      }),
    ).toBe('开放 → 关闭 · 月末关账 · 期间关闭');
  });

  it('审批动作各有一个可读名', () => {
    expect(accountingApprovalActionLabel('SUBMIT')).toBe('提交');
    expect(accountingApprovalActionLabel('APPROVE')).toBe('批准');
    expect(accountingApprovalActionLabel('REJECT')).toBe('驳回');
    expect(accountingApprovalActionLabel('TRANSFER')).toBe('转交');
    expect(accountingApprovalActionLabel('UNKNOWN')).toBe('UNKNOWN');
  });
});
