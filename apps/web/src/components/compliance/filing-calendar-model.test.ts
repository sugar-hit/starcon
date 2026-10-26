import { describe, expect, it } from 'vitest';
import {
  canAdvance,
  deadlineText,
  emptyStateKind,
  emptyStateText,
  filingCycleLabel,
  readinessText,
  reminderKindLabel,
  responsibleText,
  statusLabel,
  statusRank,
  statusTone,
  taxTypeLabel,
  urgencyLabel,
  urgencyOf,
  urgencyTone,
} from './filing-calendar-model';

/**
 * 16.3.1.2 申报日历前端模型的**纯函数**断言。
 *
 * 🔴 守的是蓝图那句加粗句：「**漏报罚款远高于系统能提供的价值**，日历提醒是最低成本的
 * 合规保障。」页面上的错法有四种，这几组用例各堵一种：
 *
 * 1. **把「读不到」渲染成「数据没就绪」**（前者要去查上游，后者是「查过了，确实没有」，
 *    处置动作完全相反）；
 * 2. **把逾期 / 临近截止的申报义务淹在列表里**（漏报就是这么发生的）；
 * 3. **把截止日换算成浏览器时区**（德国 23:59 截止显示成第二天早上，运营会以为还有时间）；
 * 4. **给出「推进到已提交」却不要求凭据号**（系统不代造受理号，没凭据号的「已提交」是假的）。
 */

describe('16.3.1.2 状态：受控词表 + 只许前进', () => {
  it('四档 rank 单调', () => {
    expect(statusRank('NOT_STARTED')).toBe(0);
    expect(statusRank('DATA_READY')).toBe(1);
    expect(statusRank('SUBMITTED')).toBe(2);
    expect(statusRank('CONFIRMED')).toBe(3);
    expect(statusRank('NOT_A_STATUS')).toBe(0);
  });

  it('倒退一律不允许，同状态是幂等', () => {
    expect(canAdvance('NOT_STARTED', 'DATA_READY')).toBe(true);
    expect(canAdvance('DATA_READY', 'SUBMITTED')).toBe(true);
    expect(canAdvance('SUBMITTED', 'CONFIRMED')).toBe(true);
    expect(canAdvance('SUBMITTED', 'DATA_READY')).toBe(false);
    expect(canAdvance('CONFIRMED', 'SUBMITTED')).toBe(false);
    expect(canAdvance('DATA_READY', 'NOT_STARTED')).toBe(false);
    expect(canAdvance('SUBMITTED', 'SUBMITTED')).toBe(true);
  });

  it('未开始不是推进目标（它是初始状态）', () => {
    expect(canAdvance('NOT_STARTED', 'NOT_STARTED')).toBe(false);
  });

  it('人话与色调', () => {
    expect(statusLabel('NOT_STARTED')).toBe('未开始');
    expect(statusLabel('DATA_READY')).toBe('数据就绪');
    expect(statusLabel('SUBMITTED')).toBe('已提交');
    expect(statusLabel('CONFIRMED')).toBe('已确认');
    expect(statusTone('SUBMITTED')).toBe('success');
    expect(statusTone('DATA_READY')).toBe('warning');
    expect(statusTone('NOT_STARTED')).toBe('neutral');
  });
});

describe('16.3.1.2 紧迫度：逾期与临近截止必须刺眼', () => {
  it('已申报的排到最后', () => {
    expect(urgencyOf({ status: 'SUBMITTED', overdue: false, daysToDue: -3 })).toBe('DONE');
    expect(urgencyOf({ status: 'CONFIRMED', overdue: false, daysToDue: -3 })).toBe('DONE');
  });

  it('逾期未提交最刺眼', () => {
    expect(urgencyOf({ status: 'NOT_STARTED', overdue: true, daysToDue: -2 })).toBe('OVERDUE');
    expect(urgencyTone('OVERDUE')).toBe('danger');
  });

  it('7 天内到期次之', () => {
    expect(urgencyOf({ status: 'NOT_STARTED', overdue: false, daysToDue: 3 })).toBe('DUE_SOON');
    expect(urgencyTone('DUE_SOON')).toBe('warning');
  });

  it('还早的在计划内', () => {
    expect(urgencyOf({ status: 'NOT_STARTED', overdue: false, daysToDue: 30 })).toBe('SCHEDULED');
    expect(urgencyTone('SCHEDULED')).toBe('neutral');
    expect(urgencyLabel('OVERDUE')).toBe('已逾期未提交');
  });

  it('判不了天数（null）时落在「在计划内」，不假装知道', () => {
    expect(urgencyOf({ status: 'NOT_STARTED', overdue: false, daysToDue: null })).toBe(
      'SCHEDULED',
    );
  });
});

describe('16.3.1.2 数据就绪：读不到 ≠ 没就绪', () => {
  it('读不到时显示「读不到」，绝不说「数据没就绪」', () => {
    const text = readinessText({
      dataReady: false,
      dataReadinessAvailable: false,
      dataReadinessNote: 'finance 服务不可达 —— 申报底稿读不到',
    });
    expect(text).toContain('读不到');
    expect(text).not.toContain('没有生效中的底稿');
  });

  it('读到了但没有底稿 ⇒ 显示「没就绪」（这是查过了，确实没有）', () => {
    const text = readinessText({
      dataReady: false,
      dataReadinessAvailable: true,
      dataReadinessNote: '这个辖区这个申报期当前没有生效中的底稿',
    });
    expect(text).toContain('没有生效中的底稿');
  });

  it('就绪时显示就绪', () => {
    const text = readinessText({
      dataReady: true,
      dataReadinessAvailable: true,
      dataReadinessNote: '12.6.1.2 有一份生效中的底稿覆盖这个申报期',
    });
    expect(text).toContain('生效中的底稿');
  });

  it('没有说明时也给得出人话（不许退化成空白）', () => {
    expect(
      readinessText({ dataReady: false, dataReadinessAvailable: false, dataReadinessNote: '' }),
    ).toContain('读不到');
  });
});

describe('16.3.1.2 责任人：未指定 / 读不到 / 有名字，三态分开', () => {
  it('没指定责任人说「未指定责任人」', () => {
    expect(
      responsibleText({
        responsibleMembershipId: '',
        responsibleName: '',
        responsibleAvailable: false,
        responsibleNote: '',
      }),
    ).toBe('未指定责任人');
  });

  it('读不到时说「读不到」，不说「没有责任人」', () => {
    const text = responsibleText({
      responsibleMembershipId: 'm-1',
      responsibleName: '',
      responsibleAvailable: false,
      responsibleNote: '成员目录读不到',
    });
    expect(text).toContain('读不到');
    expect(text).not.toBe('没有责任人');
  });

  it('有名字就显示名字', () => {
    expect(
      responsibleText({
        responsibleMembershipId: 'm-1',
        responsibleName: '合规负责人',
        responsibleAvailable: true,
        responsibleNote: '',
      }),
    ).toBe('合规负责人');
  });
});

describe('16.3.1.2 截止日：显示当地时刻，不换算成浏览器时区', () => {
  it('带时区名显示', () => {
    expect(deadlineText('2026-07-10T23:00+02:00', 'Europe/Berlin')).toBe(
      '2026-07-10 23:00（Europe/Berlin）',
    );
  });

  it('没截止日就说没截止日', () => {
    expect(deadlineText('', 'UTC')).toBe('未设截止日');
  });
});

describe('16.3.1.2 空态：三种空各说各的', () => {
  it('没有配置 ⇒ 先去登记配置', () => {
    expect(emptyStateKind({ totalConfigs: 0, totalEntries: 0, filtered: false })).toBe('NO_CONFIG');
    expect(emptyStateText('NO_CONFIG')).toContain('申报周期配置');
  });

  it('有配置但没条目 ⇒ 去生成', () => {
    expect(emptyStateKind({ totalConfigs: 3, totalEntries: 0, filtered: false })).toBe('NO_ENTRY');
    expect(emptyStateText('NO_ENTRY')).toContain('生成');
  });

  it('筛选无结果 ⇒ 说筛选', () => {
    expect(emptyStateKind({ totalConfigs: 3, totalEntries: 9, filtered: true })).toBe('NO_MATCH');
    expect(emptyStateText('NO_MATCH')).toContain('筛选');
  });
});

describe('16.3.1.2 词表人话', () => {
  it('周期 / 申报线 / 提醒档位', () => {
    expect(filingCycleLabel('QUARTERLY')).toBe('按季');
    expect(filingCycleLabel('MONTHLY')).toBe('按月');
    expect(taxTypeLabel('VAT')).toBe('增值税（VAT）');
    expect(reminderKindLabel('DUE_SOON')).toBe('即将到期');
    expect(reminderKindLabel('DUE')).toBe('截止日');
  });
});
