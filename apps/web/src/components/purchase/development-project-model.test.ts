import { describe, expect, it } from 'vitest';
import type { DevelopmentProjectOptions } from '../../../../../packages/api-contracts/src/index';
import {
  allowedTargetOptions,
  costSourceLabel,
  costText,
  developmentProjectQuery,
  fmt,
  handoffStatusText,
  marginNumberText,
  marginText,
  milestoneStatusLabel,
  stateMachineHint,
  statusLabel,
  statusTone,
  timeText,
  transitionNeedsExemption,
} from './development-project-model';

/**
 * 6.6.1.1 开发立项前端模型的展示口径断言。
 *
 * <p>这些口径是「蓝图加粗句」在界面上的落点，断言它们是为了让口径改坏时**先红在这里**，
 * 而不是等到页面上没人看出来。
 *
 * <p>🔴 最要紧的一条：**目标毛利取不到时绝不显示 0.00% 或 100%** ——
 * 成本 0 的毛利率是 100%，是彻头彻尾的假数据。
 */

describe('开发立项展示口径', () => {
  it('目标毛利取不到时显示「取不到」而不是 0.00% 或 100%', () => {
    expect(marginText(null)).toBe('取不到（成本不可得）');
    expect(marginText(undefined)).toBe('取不到（成本不可得）');
    expect(marginText(null)).not.toContain('0.00');
    expect(marginText(null)).not.toContain('100');
    expect(marginNumberText(null)).toBe('无数据');
  });

  it('目标毛利按小数口径只做一次百分比格式化', () => {
    // 后端给 0.253456（小数口径，6 位 HALF_UP）⇒ 显示 25.35%。
    expect(marginText(0.253456)).toBe('25.35%');
    expect(marginText(0.4)).toBe('40.00%');
    expect(marginText(0)).toBe('0.00%');
  });

  it('目标成本取不到时显示「取不到」而不是 0.00', () => {
    expect(costText(null, 'USD', false)).toBe('取不到');
    expect(costText(0, 'USD', true)).toBe('0.00 USD');
    expect(costText(62.5, 'USD', true)).toBe('62.50 USD');
  });

  it('金额与数量的 null 显示成「无数据」而不是 0', () => {
    expect(fmt(null)).toBe('无数据');
    expect(fmt(undefined)).toBe('无数据');
    expect(fmt(0)).toBe('0');
  });

  it('七个状态各有各的名字，不折叠', () => {
    expect(statusLabel({ status: 'EVALUATION', statusLabel: '评估' })).toBe('评估');
    expect(statusLabel({ status: 'APPROVED', statusLabel: '立项' })).toBe('立项');
    expect(statusLabel({ status: 'IN_DEVELOPMENT', statusLabel: '开发中' })).toBe('开发中');
    expect(statusLabel({ status: 'SAMPLING', statusLabel: '打样' })).toBe('打样');
    expect(statusLabel({ status: 'PILOT', statusLabel: '试产' })).toBe('试产');
    expect(statusLabel({ status: 'MASS_PRODUCTION', statusLabel: '量产' })).toBe('量产');
    expect(statusLabel({ status: 'TERMINATED', statusLabel: '终止' })).toBe('终止');
    // 「终止」与「量产」是两个状态 —— 折叠成「结束」会让人以为还能恢复。
    expect(statusLabel({ status: 'TERMINATED' })).not.toBe(statusLabel({ status: 'MASS_PRODUCTION' }));
  });

  it('终止是危险色，量产是成功色', () => {
    expect(statusTone('TERMINATED')).toBe('danger');
    expect(statusTone('MASS_PRODUCTION')).toBe('success');
    expect(statusTone('EVALUATION')).toBe('neutral');
  });

  it('里程碑状态各有各的名字', () => {
    expect(milestoneStatusLabel('PLANNED')).toBe('计划中');
    expect(milestoneStatusLabel('REACHED')).toBe('已达成');
    expect(milestoneStatusLabel('MISSED')).toBe('错过');
  });

  it('下游未交付时交接单显示「已交接，待 X 消费」而不是「已创建」', () => {
    expect(handoffStatusText('PENDING', '6.6.1.2')).toBe('已交接，待 6.6.1.2 消费');
    expect(handoffStatusText('ACCEPTED', '6.6.1.2')).toBe('6.6.1.2 已接收');
    expect(handoffStatusText('CANCELLED', '6.6.1.2')).toBe('已撤销');
    expect(handoffStatusText('PENDING', '6.6.1.2')).not.toContain('已创建');
  });

  it('成本来源如实区分系统来源与手工估算', () => {
    expect(costSourceLabel('CATALOG_ESTIMATE')).toContain('5.3.1.1');
    expect(costSourceLabel('MANUAL_ESTIMATE')).toBe('手工估算');
    expect(costSourceLabel('UNAVAILABLE')).toBe('取不到');
  });

  it('时间未安排时显示「未安排」', () => {
    expect(timeText(null)).toBe('未安排');
    expect(timeText(undefined)).toBe('未安排');
    expect(timeText('2026-02-01T00:00:00Z')).toContain('2026-02-01');
  });
});

describe('开发立项状态机的界面口径', () => {
  const options: DevelopmentProjectOptions = {
    statuses: [
      { value: 'EVALUATION', label: '评估', terminal: false, allowedTargets: ['APPROVED', 'TERMINATED'] },
      { value: 'APPROVED', label: '立项', terminal: false, allowedTargets: ['IN_DEVELOPMENT', 'TERMINATED'] },
      { value: 'IN_DEVELOPMENT', label: '开发中', terminal: false, allowedTargets: ['SAMPLING', 'PILOT', 'MASS_PRODUCTION', 'TERMINATED'] },
      { value: 'SAMPLING', label: '打样', terminal: false, allowedTargets: ['PILOT', 'MASS_PRODUCTION', 'TERMINATED'] },
      { value: 'PILOT', label: '试产', terminal: false, allowedTargets: ['MASS_PRODUCTION', 'TERMINATED'] },
      { value: 'MASS_PRODUCTION', label: '量产', terminal: false, allowedTargets: ['TERMINATED'] },
      { value: 'TERMINATED', label: '终止', terminal: true, allowedTargets: [] },
    ],
    milestoneTypes: [],
    costSources: [],
    handoffTypes: [],
  };

  it('目标状态下拉只给后端说的合法去向', () => {
    const targets = allowedTargetOptions(options, 'APPROVED');
    expect(targets.map((item) => item.value)).toEqual(['IN_DEVELOPMENT', 'TERMINATED']);
  });

  it('终止之后没有目标状态可选', () => {
    expect(allowedTargetOptions(options, 'TERMINATED')).toEqual([]);
  });

  it('跳过打样与试产需要显式豁免', () => {
    expect(transitionNeedsExemption('IN_DEVELOPMENT', 'PILOT')).toBe(true);
    expect(transitionNeedsExemption('IN_DEVELOPMENT', 'MASS_PRODUCTION')).toBe(true);
    expect(transitionNeedsExemption('SAMPLING', 'MASS_PRODUCTION')).toBe(true);
    expect(transitionNeedsExemption('IN_DEVELOPMENT', 'SAMPLING')).toBe(false);
    expect(transitionNeedsExemption('SAMPLING', 'PILOT')).toBe(false);
    expect(transitionNeedsExemption('PILOT', 'MASS_PRODUCTION')).toBe(false);
    expect(transitionNeedsExemption('IN_DEVELOPMENT', 'TERMINATED')).toBe(false);
  });

  it('状态机提示在能力声明拿不到时也有兜底文案', () => {
    expect(stateMachineHint(null)).toContain('终止是终态');
  });
});

describe('开发立项清单查询参数', () => {
  it('空参数不带问号', () => {
    expect(developmentProjectQuery({})).toBe('');
  });

  it('筛选条件进 query 且做了 URL 编码', () => {
    const query = developmentProjectQuery({
      status: 'APPROVED',
      category: '家居',
      keyword: 'DP 2026',
      page: 0,
      size: 20,
    });
    expect(query).toContain('status=APPROVED');
    expect(query).toContain('category=%E5%AE%B6%E5%B1%85');
    expect(query).toContain('keyword=DP%202026');
    expect(query).toContain('page=0');
    expect(query).toContain('size=20');
  });

  it('空白关键字不进 query', () => {
    expect(developmentProjectQuery({ keyword: '   ' })).toBe('');
  });
});
