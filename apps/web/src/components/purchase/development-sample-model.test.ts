import { describe, expect, it } from 'vitest';
import type { DevelopmentSampleOptions } from '../../../../../packages/api-contracts/src/index';
import {
  allowedOrderTargetOptions,
  allowedSampleTargetOptions,
  capabilityGaps,
  confirmationNeedsIssueNote,
  confirmationResultForTarget,
  confirmationSummary,
  costByCurrencyText,
  costLineSummary,
  developmentSampleLedgerQuery,
  developmentSampleOrderQuery,
  expensePostedText,
  fmt,
  isConfirmationTarget,
  money,
  orderStatusTone,
  orderTransitionSummary,
  sampleStatusTone,
  timeText,
  trackingText,
  transitionSummary,
} from './development-sample-model';

/**
 * 6.6.1.2 打样与样品管理前端模型的展示口径断言。
 *
 * <p>这些口径是「蓝图加粗句」在界面上的落点，断言它们是为了让口径改坏时**先红在这里**，
 * 而不是等到页面上没人看出来。
 *
 * <p>🔴 最要紧的三条：
 *
 * <ol>
 *   <li><b>打样与达人寄样分得开</b>：确认类目标状态与制作进度分两个入口，
 *       「修改」必须带修改意见。</li>
 *   <li><b>没做到的事说「没做到」</b>：未进费用归集就说未进，物流轨迹取不到就说取不到 ——
 *       界面显示「已入账」「轨迹可查」就是在说假话。</li>
 *   <li><b>成本不跨币种相加</b>：概览逐币种列，不给一个假的「总成本」。</li>
 * </ol>
 */

const options = {
  sourceTypes: [
    { value: 'SUPPLIER', label: '供应商打样' },
    { value: 'OWN_FACTORY', label: '自有产打样' },
    { value: 'STALL', label: '档口打样' },
  ],
  usageTypes: [{ value: 'DEVELOPMENT', label: '开发打样' }],
  orderStatuses: [
    {
      value: 'REQUESTED',
      label: '已申请',
      terminal: false,
      allowedTargets: ['IN_PROGRESS', 'CANCELLED'],
    },
    {
      value: 'IN_PROGRESS',
      label: '进行中',
      terminal: false,
      allowedTargets: ['COMPLETED', 'CANCELLED'],
    },
    {
      value: 'COMPLETED',
      label: '已完成',
      terminal: false,
      allowedTargets: ['CLOSED'],
    },
    { value: 'CLOSED', label: '已关闭', terminal: true, allowedTargets: [] },
    { value: 'CANCELLED', label: '已取消', terminal: true, allowedTargets: [] },
  ],
  sampleStatuses: [
    {
      value: 'REQUESTED',
      label: '已申请',
      terminal: false,
      allowedTargets: ['MAKING', 'ABANDONED'],
    },
    {
      value: 'MAKING',
      label: '打样中',
      terminal: false,
      allowedTargets: ['SHIPPED', 'ABANDONED'],
    },
    {
      value: 'SHIPPED',
      label: '已发出',
      terminal: false,
      allowedTargets: ['RECEIVED', 'ABANDONED'],
    },
    {
      value: 'RECEIVED',
      label: '已到样',
      terminal: false,
      allowedTargets: ['CONFIRMED', 'REVISING', 'ABANDONED'],
    },
    {
      value: 'CONFIRMED',
      label: '已确认',
      terminal: true,
      allowedTargets: [],
    },
    {
      value: 'REVISING',
      label: '修改中',
      terminal: false,
      allowedTargets: ['MAKING', 'CONFIRMED', 'ABANDONED'],
    },
    { value: 'ABANDONED', label: '已放弃', terminal: true, allowedTargets: [] },
  ],
  confirmationResults: [
    { value: 'PASSED', label: '通过' },
    { value: 'MODIFY', label: '修改' },
    { value: 'ABANDONED', label: '放弃' },
  ],
  costTypes: [],
  logisticsStatuses: [],
} as unknown as DevelopmentSampleOptions;

describe('打样与样品展示口径', () => {
  it('来源只有供应商 / 自有产 / 档口，用途只有开发打样 —— 没有「其它」桶', () => {
    expect(options.sourceTypes.map((item) => item.value)).toEqual([
      'SUPPLIER',
      'OWN_FACTORY',
      'STALL',
    ]);
    expect(options.sourceTypes.map((item) => item.value)).not.toContain('OTHER');
    expect(options.sourceTypes.map((item) => item.value)).not.toContain('MARKETING');
    expect(options.usageTypes).toHaveLength(1);
    expect(options.usageTypes[0]?.value).toBe('DEVELOPMENT');
  });

  it('目标状态只给后端说的合法去向，页面不自己维护状态机', () => {
    expect(
      allowedOrderTargetOptions(options, 'REQUESTED').map((item) => item.value),
    ).toEqual(['IN_PROGRESS', 'CANCELLED']);
    expect(
      allowedSampleTargetOptions(options, 'RECEIVED').map((item) => item.value),
    ).toEqual(['CONFIRMED', 'REVISING', 'ABANDONED']);
    // 终态没有出边。
    expect(allowedOrderTargetOptions(options, 'CLOSED')).toEqual([]);
    expect(allowedOrderTargetOptions(options, 'CANCELLED')).toEqual([]);
    expect(allowedSampleTargetOptions(options, 'CONFIRMED')).toEqual([]);
    expect(allowedSampleTargetOptions(options, 'ABANDONED')).toEqual([]);
  });

  it('确认类状态与制作进度分得开', () => {
    expect(isConfirmationTarget('CONFIRMED')).toBe(true);
    expect(isConfirmationTarget('REVISING')).toBe(true);
    expect(isConfirmationTarget('ABANDONED')).toBe(true);
    expect(isConfirmationTarget('MAKING')).toBe(false);
    expect(isConfirmationTarget('SHIPPED')).toBe(false);
    expect(isConfirmationTarget('RECEIVED')).toBe(false);
  });

  it('确认结果与目标状态一一对应', () => {
    expect(confirmationResultForTarget('CONFIRMED')).toBe('PASSED');
    expect(confirmationResultForTarget('REVISING')).toBe('MODIFY');
    expect(confirmationResultForTarget('ABANDONED')).toBe('ABANDONED');
  });

  it('「修改」必须带修改意见，「通过」「放弃」不带', () => {
    expect(confirmationNeedsIssueNote('MODIFY')).toBe(true);
    expect(confirmationNeedsIssueNote('PASSED')).toBe(false);
    expect(confirmationNeedsIssueNote('ABANDONED')).toBe(false);
  });

  it('未进费用归集就说未进，不显示「已入账」', () => {
    expect(expensePostedText(false, '12 费用')).toBe('尚未进费用归集（归 12 费用 管）');
    expect(expensePostedText(false, '12 费用')).not.toContain('已进');
    expect(expensePostedText(true, '12 费用')).toBe('已进费用归集');
  });

  it('物流轨迹取不到就说取不到，不假装已接通物流平台', () => {
    expect(trackingText(false, '10 物流')).toBe('轨迹不可查（归 10 物流 管）');
    expect(trackingText(false, '10 物流')).not.toContain('可查物流轨迹');
    expect(trackingText(true, '10 物流')).toBe('可查物流轨迹');
  });

  it('成本按币种分组，不跨币种加成一个总数', () => {
    expect(
      costByCurrencyText([
        { currency: 'USD', totalAmount: 150.5, orderCount: 2 },
        { currency: 'CNY', totalAmount: 80, orderCount: 1 },
      ]),
    ).toBe('150.50 USD（2 张单） / 80.00 CNY（1 张单）');
    expect(costByCurrencyText([])).toBe('暂无成本');
  });

  it('金额与数量的 null 显示成「无数据」而不是 0', () => {
    expect(fmt(null)).toBe('无数据');
    expect(fmt(undefined)).toBe('无数据');
    expect(fmt(0)).toBe('0');
    expect(money(null)).toBe('无数据');
    expect(money(150.5, 'USD')).toBe('150.50 USD');
  });

  it('时间缺失显示「未安排」', () => {
    expect(timeText(null)).toBe('未安排');
    expect(timeText(undefined)).toBe('未安排');
    expect(timeText('2026-10-21T08:30:00Z')).toContain('2026-10-21');
  });

  it('终态是危险色，通过是成功色', () => {
    expect(orderStatusTone('CANCELLED')).toBe('danger');
    expect(orderStatusTone('COMPLETED')).toBe('success');
    expect(sampleStatusTone('ABANDONED')).toBe('danger');
    expect(sampleStatusTone('CONFIRMED')).toBe('success');
    expect(sampleStatusTone('REVISING')).toBe('warning');
  });

  it('能力缺口逐条说出来，不合并成一句「部分可用」', () => {
    const gaps = capabilityGaps({
      projectReferenceAvailable: true,
      projectReferenceSource: '',
      projectReferenceNote: '',
      stallReferenceAvailable: true,
      stallReferenceSource: '',
      stallReferenceNote: '',
      supplierDirectoryAvailable: true,
      supplierDirectorySource: '',
      supplierDirectoryNote: '',
      mediaLibraryAvailable: true,
      mediaLibrarySource: '',
      mediaLibraryNote: '',
      expenseAggregationAvailable: false,
      expenseAggregationOwnerModule: '12 费用',
      expenseAggregationNote: 'finance 服务不在本仓',
      logisticsTrackingAvailable: false,
      logisticsTrackingOwnerModule: '10 物流',
      logisticsTrackingNote: '没有可读的真实轨迹来源',
      pilotOrderAvailable: false,
      pilotOrderOwnerModule: '6.6.2.1 试产单',
      pilotOrderNote: '未交付',
      marketingSampleDelivered: false,
      marketingSampleOwnerModule: '14.5.1.2 达人寄样',
      marketingSampleNote: '不归本模块',
      sourceUsageNote: '',
      orderStateMachineNote: '',
      sampleStateMachineNote: '',
      costAggregationNote: '',
      boundaryNote: '',
    });
    expect(gaps).toHaveLength(4);
    expect(gaps.join(' ')).toContain('12 费用');
    expect(gaps.join(' ')).toContain('10 物流');
    expect(gaps.join(' ')).toContain('6.6.2.1 试产单');
    expect(gaps.join(' ')).toContain('14.5.1.2 达人寄样');
  });

  it('已接通的模块不产生缺口文案', () => {
    expect(
      capabilityGaps({
        expenseAggregationAvailable: true,
        logisticsTrackingAvailable: true,
        pilotOrderAvailable: true,
        marketingSampleDelivered: true,
      } as never),
    ).toEqual([]);
  });

  it('确认记录摘要带轮次、结论、时刻与修改意见', () => {
    const summary = confirmationSummary({
      id: 'x',
      sampleId: 'y',
      result: 'MODIFY',
      resultLabel: '修改',
      confirmSeq: 2,
      reason: '颜色不对',
      issueNote: '改成墨绿色',
      actorId: 'a',
      actorName: '',
      confirmedAt: '2026-10-21T08:30:00Z',
      appliedAt: null,
    });
    expect(summary).toContain('第 2 次确认');
    expect(summary).toContain('修改');
    expect(summary).toContain('改成墨绿色');
  });

  it('迁移摘要与成本行摘要把关键事实说全', () => {
    expect(
      transitionSummary({
        id: 'x',
        sampleId: 'y',
        fromStatus: 'REQUESTED',
        fromStatusLabel: '已申请',
        toStatus: 'MAKING',
        toStatusLabel: '打样中',
        reason: '开工',
        actorId: 'a',
        actorName: '',
        occurredAt: '2026-10-21T08:30:00Z',
        appliedAt: null,
      }),
    ).toContain('已申请 → 打样中');
    expect(
      orderTransitionSummary({
        id: 'x',
        orderId: 'y',
        fromStatus: 'REQUESTED',
        fromStatusLabel: '已申请',
        toStatus: 'IN_PROGRESS',
        toStatusLabel: '进行中',
        reason: '开工',
        actorId: 'a',
        actorName: '',
        occurredAt: '2026-10-21T08:30:00Z',
        appliedAt: null,
      }),
    ).toContain('已申请 → 进行中');
    expect(
      costLineSummary({
        costTypeLabel: '打样费',
        amount: 120,
        currency: 'USD',
        description: '首轮打样',
        incurredAt: '2026-10-21T08:30:00Z',
      }),
    ).toContain('打样费 120.00 USD');
  });

  it('查询串只带真正填了的筛选项', () => {
    expect(developmentSampleOrderQuery({ page: 0, size: 20 })).toBe('?page=0&size=20');
    expect(developmentSampleOrderQuery({ status: 'REQUESTED', keyword: '  SO-1  ' })).toBe(
      '?status=REQUESTED&keyword=SO-1',
    );
    expect(developmentSampleOrderQuery({ keyword: '   ' })).toBe('');
    expect(developmentSampleLedgerQuery({ orderId: 'abc', sourceType: 'STALL' })).toBe(
      '?sourceType=STALL&orderId=abc',
    );
  });
});
