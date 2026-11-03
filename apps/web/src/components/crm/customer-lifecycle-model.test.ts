import { describe, expect, it } from 'vitest';
import type { CrmLifecycleRiskFactor } from '../../../../../packages/api-contracts/src/index';
import {
  lifecycleAlertEmptyStateText,
  lifecycleAlertStatusLabel,
  lifecycleBaselineText,
  lifecycleEmptyStateText,
  lifecycleMonetaryText,
  lifecyclePolicyNote,
  lifecyclePolicyScopeLabel,
  lifecycleRfmText,
  lifecycleRiskFactorText,
  lifecycleRiskLevelLabel,
  lifecycleRiskText,
  lifecycleSegmentLabel,
  lifecycleStatsNote,
  lifecycleSuggestionOwnerLabel,
  lifecycleThresholdSourceLabel,
  lifecycleThresholdText,
} from './customer-lifecycle-model';

/**
 * 15.6.2.1 生命周期与流失预警前端模型的纯规则测试。
 *
 * <p>本文件把**页面最容易做错的几件事**钉死：
 *
 * <ol>
 *   <li><b>阈值来源不能画错</b>：{@code ORDER_HISTORY_BASELINE}（用租户真实订单历史的中位
 *       复购间隔）**不是**「按类目生效」。页面若把它画成「按类目配置」，运营会以为
 *       快消与家具用的是各自的天数 —— 那是本模块要防的头号事故。</li>
 *   <li><b>「不可判定」与「0 天」是两句话</b>：客户只下一单时没有自己的复购间隔基线，
 *       画成「基线 0 天」会被读成「他每天都买」。</li>
 *   <li><b>风险分必须能拆开看</b>：只给一个「56 分」没法复核，也没法排召回优先级。</li>
 *   <li><b>召回只是建议</b>：归属必须写出来（15.5.2 触达 / 15.6.2.2 弃单挽回）。</li>
 *   <li><b>空态分两种</b>：「筛选无结果」与「还没算过」要的行动完全不同。</li>
 * </ol>
 */
describe('15.6.2.1 生命周期与流失预警前端模型', () => {
  describe('阈值来源的可读名', () => {
    it('「订单历史基线」**不是**「按类目生效」', () => {
      const label = lifecycleThresholdSourceLabel('ORDER_HISTORY_BASELINE');
      expect(label).toContain('租户真实订单历史');
      expect(label).not.toContain('类目策略');
      expect(lifecycleThresholdSourceLabel('CATEGORY_POLICY')).toBe('命中类目策略');
      expect(lifecycleThresholdSourceLabel('GLOBAL_POLICY')).toBe('命中全局策略');
    });

    it('「不可判定」有**自己**的说法，不能画成「无阈值」', () => {
      expect(lifecycleThresholdSourceLabel('NONE')).toContain('不可判定');
    });
  });

  describe('阈值的显示', () => {
    it('阈值不可判定时显示「不可判定」，**不是**「—」', () => {
      expect(
        lifecycleThresholdText({
          expectedRepurchaseDays: null,
          toleranceDays: null,
          thresholdSource: 'NONE',
        }),
      ).toBe('不可判定');
      expect(
        lifecycleThresholdText({
          expectedRepurchaseDays: null,
          toleranceDays: null,
          thresholdSource: 'NONE',
        }),
      ).not.toBe('—');
    });

    it('有阈值时把「预期复购 + 容差」与来源一起显示', () => {
      const text = lifecycleThresholdText({
        expectedRepurchaseDays: 14,
        toleranceDays: 7,
        thresholdSource: 'CATEGORY_POLICY',
      });
      expect(text).toContain('预期复购 14 天');
      expect(text).toContain('容差 7 天');
      expect(text).toContain('命中类目策略');
    });
  });

  describe('客户本人复购间隔基线', () => {
    it('样本不足是「不可判定」，**不是**「0 天」', () => {
      const text = lifecycleBaselineText({
        customerBaselineDays: null,
        customerBaselineSamples: 0,
        baselineSource: 'INSUFFICIENT_SAMPLE',
      });
      expect(text).toContain('不可判定');
      expect(text).not.toContain('0 天');
    });

    it('有基线时把中位数与样本数一起显示（可复核）', () => {
      const text = lifecycleBaselineText({
        customerBaselineDays: 20,
        customerBaselineSamples: 3,
        baselineSource: 'ORDER_HISTORY_MEDIAN',
      });
      expect(text).toContain('20 天');
      expect(text).toContain('3 段间隔');
    });
  });

  describe('风险分', () => {
    it('分数与等级一起显示', () => {
      expect(lifecycleRiskText({ riskScore: 56, riskLevel: 'HIGH' })).toBe('56（高）');
      expect(lifecycleRiskLevelLabel('CRITICAL')).toBe('极高');
    });

    it('一个因子要能说清「这个分是怎么来的」', () => {
      const factor: CrmLifecycleRiskFactor = {
        code: 'OVERDUE_RATIO',
        label: '逾期倍数',
        weight: 40,
        rawValue: '15 天 / 阈值 25 天',
        points: 24,
        explanation: '已超过「预期复购间隔 + 容差」15 天',
      };
      const text = lifecycleRiskFactorText(factor);
      expect(text).toContain('逾期倍数');
      expect(text).toContain('24 / 40 分');
      expect(text).toContain('15 天 / 阈值 25 天');
      expect(text).toContain('已超过');
    });
  });

  describe('RFM 与金额', () => {
    it('R/F/M 为 0 时是「不可判定」（不是 0 分）', () => {
      expect(lifecycleRfmText({ rScore: 0, fScore: 5, mScore: 3 })).toContain('R 不可判定');
      expect(lifecycleRfmText({ rScore: 4, fScore: 2, mScore: 1 })).toBe('R 4 / F 2 / M 1');
    });

    it('金额带币种显示，页面不做任何换算', () => {
      expect(lifecycleMonetaryText(1, 'USD')).toBe('USD 1');
      expect(lifecycleMonetaryText(null, 'USD')).toBe('—');
      expect(lifecycleMonetaryText(12.5, null)).toBe('12.5');
    });
  });

  describe('分层与状态的可读名', () => {
    it('五个分层各有各的话', () => {
      expect(lifecycleSegmentLabel('NEW')).toBe('新客');
      expect(lifecycleSegmentLabel('ACTIVE')).toBe('活跃');
      expect(lifecycleSegmentLabel('DORMANT')).toBe('沉睡');
      expect(lifecycleSegmentLabel('RECALL')).toBe('待召回');
      expect(lifecycleSegmentLabel('CHURNED')).toBe('已流失');
    });

    it('预警状态只能向前推进，三态各有各的话', () => {
      expect(lifecycleAlertStatusLabel('OPEN')).toBe('待处理');
      expect(lifecycleAlertStatusLabel('ACKNOWLEDGED')).toBe('已知悉');
      expect(lifecycleAlertStatusLabel('RESOLVED')).toBe('已处理');
    });

    it('建议归属写明模块（触达 / 弃单挽回）', () => {
      expect(lifecycleSuggestionOwnerLabel('15.5.2')).toContain('触达');
      expect(lifecycleSuggestionOwnerLabel('15.6.2.2')).toContain('弃单挽回');
    });
  });

  describe('阈值策略', () => {
    it('作用域与阈值一起显示', () => {
      const note = lifecyclePolicyNote({
        id: 'x',
        scope: 'CATEGORY',
        scopeLabel: '按类目',
        categoryCode: 'FAST_MOVING',
        expectedRepurchaseDays: 14,
        toleranceDays: 7,
        rfmWindowDays: 365,
        recencyScoreDays: 30,
        frequencyMinOrders: 2,
        monetaryMinAmount: null,
        monetaryCurrency: null,
        enabled: true,
        effectiveFrom: '2026-01-01T00:00:00Z',
        effectiveTo: null,
        effectiveNow: true,
        note: '',
        createdAt: '2026-01-01T00:00:00Z',
      });
      expect(note).toContain('按类目');
      expect(note).toContain('FAST_MOVING');
      expect(note).toContain('预期复购 14 天');
      expect(note).toContain('容差 7 天');
      expect(lifecyclePolicyScopeLabel('GLOBAL')).toBe('全局兜底');
    });
  });

  describe('空态', () => {
    it('分层空态区分「筛选无结果」与「还没算过」', () => {
      expect(lifecycleEmptyStateText(true, true).title).toBe('没有命中任何客户');
      expect(lifecycleEmptyStateText(false, false).title).toBe('还没有算过生命周期分层');
      expect(lifecycleEmptyStateText(false, false).description).toContain('重算');
    });

    it('预警空态区分「筛选无结果」与「还没算过」', () => {
      expect(lifecycleAlertEmptyStateText(true, true).title).toBe('没有命中任何预警');
      expect(lifecycleAlertEmptyStateText(false, false).title).toBe('还没有预警清单');
    });
  });

  describe('统计说明', () => {
    it('统计来自独立查询，翻页不变', () => {
      const note = lifecycleStatsNote({
        totalCustomers: 4,
        totalAlerts: 2,
        overdueCustomers: 2,
        bySegment: [],
        byCategory: [],
        note: '统计不受分页影响',
      });
      expect(note).toContain('客户 4 位');
      expect(note).toContain('预警 2 条');
      expect(note).toContain('不受分页影响');
    });
  });
});
