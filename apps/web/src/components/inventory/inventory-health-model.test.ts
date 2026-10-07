import { describe, expect, it } from 'vitest';
import type {
  InventoryHealthGate,
  InventoryHealthIssue,
  InventoryHealthMetric,
  InventoryHealthScorecard,
  InventoryHealthTrend,
} from '../../../../../packages/api-contracts/src/catalog';
import {
  availabilityLabels,
  count,
  coverageText,
  gateHeadline,
  gateTone,
  healthGradeLabels,
  isPartialScore,
  issueSeverityLabels,
  metricDisplay,
  percent,
  sortIssues,
  trendAccuracyText,
  trendPointLabel,
} from './inventory-health-model';

/**
 * 7.6.2.1 库存健康前端模型测试。
 *
 * <p>只测「同一个数字怎么读」——这才是前端唯一会自己算错的东西。
 * 请求路径由后端集成测试覆盖（端点层实跑），这里不重复。
 *
 * <p>重点覆盖三件最容易出错的事：
 *
 * <ol>
 *   <li>**不可用的指标不许显示数字**。四个指标依赖未交付的上游，
 *       界面一旦给它们填 0，就会被当成"这项是 0"而不是"这项没测"。
 *   <li>**null 不等于 0**。比率与次数的空值必须显示「—」。
 *   <li>**红线的三种状态分开说**。"没核过账"不能显示成"通过"。
 * </ol>
 */

function metric(over: Partial<InventoryHealthMetric>): InventoryHealthMetric {
  return {
    metricType: 'ACCURACY',
    metricLabel: '库存准确率',
    availability: 'AVAILABLE',
    value: 0.95,
    source: 'RECONCILIATION',
    numerator: 95,
    denominator: 100,
    deduction: 3.5,
    basis: '',
    blockedByModule: null,
    ...over,
  } as InventoryHealthMetric;
}

function card(over: Partial<InventoryHealthScorecard>): InventoryHealthScorecard {
  return {
    scopeType: 'WAREHOUSE',
    scopeKey: 'w1',
    scopeLabel: 'WH-SH 上海仓',
    score: 96.5,
    grade: 'HEALTHY',
    gradeLabel: '健康',
    availableMetricCount: 2,
    totalMetricCount: 6,
    accuracyRate: 0.95,
    accuracyThreshold: 0.99,
    accuracyPassed: false,
    blocked: true,
    blockReason: '低于阈值',
    windowFrom: '2026-10-01T00:00:00+08:00',
    windowTo: '2026-10-07T00:00:00+08:00',
    evaluatedAt: '2026-10-07T00:00:00+08:00',
    metrics: [],
    ...over,
  } as InventoryHealthScorecard;
}

function gate(over: Partial<InventoryHealthGate>): InventoryHealthGate {
  return {
    scopeType: 'TENANT',
    scopeKey: null,
    scopeLabel: '全部仓库',
    accuracyState: 'AVAILABLE',
    accuracyRate: 0.95,
    accuracyThreshold: 0.99,
    blocked: true,
    blockReason: '低于阈值',
    basis: '',
    lastReconciledAt: '2026-10-06T00:00:00+08:00',
    lastStocktakenAt: null,
    evaluatedAt: '2026-10-07T00:00:00+08:00',
    issues: [],
    ...over,
  } as InventoryHealthGate;
}

describe('库存健康前端模型', () => {
  describe('标签词表', () => {
    it('四个评级都有中文标签，且"无法判定"不是"健康"', () => {
      expect(healthGradeLabels.HEALTHY).toBe('健康');
      expect(healthGradeLabels.WATCH).toBe('关注');
      expect(healthGradeLabels.RISK).toBe('风险');
      expect(healthGradeLabels.UNVERIFIABLE).toBe('无法判定');
      expect(healthGradeLabels.UNVERIFIABLE).not.toBe(healthGradeLabels.HEALTHY);
    });

    it('三种可用状态区分"上游没交付"与"窗口内没样本"', () => {
      expect(availabilityLabels.AVAILABLE).toBe('可算');
      expect(availabilityLabels.UNAVAILABLE).toBe('上游未交付');
      expect(availabilityLabels.NO_DATA).toBe('窗口内无样本');
      expect(availabilityLabels.UNAVAILABLE).not.toBe(availabilityLabels.NO_DATA);
    });

    it('严重度三档都有中文标签', () => {
      expect(issueSeverityLabels.INFO).toBe('提示');
      expect(issueSeverityLabels.WARN).toBe('警告');
      expect(issueSeverityLabels.CRITICAL).toBe('严重');
    });
  });

  describe('比率与次数', () => {
    it('比率显示成百分比', () => {
      expect(percent('0.950000')).toBe('95.00%');
      expect(percent('1.000000')).toBe('100.00%');
    });

    it('空值显示破折号，绝不显示 0%', () => {
      // 这是最危险的一个：不可算的指标显示成 0%，会被读成"这项是 0"。
      expect(percent(null)).toBe('—');
      expect(percent(undefined)).toBe('—');
      expect(percent(null)).not.toBe('0.00%');
      expect(count(null)).toBe('—');
      expect(count(null)).not.toBe('0');
    });

    it('次数显示成整数', () => {
      expect(count('0')).toBe('0');
      expect(count('3')).toBe('3');
    });
  });

  describe('不可用指标不显示数字', () => {
    it('上游未交付：不给数值位，只说等谁', () => {
      const d = metricDisplay(
        metric({
          metricType: 'TURNOVER',
          metricLabel: '周转率',
          availability: 'UNAVAILABLE',
          value: null,
          source: null,
          numerator: null,
          denominator: null,
          deduction: 0,
          basis: '依赖销售聚合，13 销量未交付。',
          blockedByModule: '13',
        }),
      );
      expect(d.hasValue).toBe(false);
      expect(d.value).toBe('');
      expect(d.hint).toContain('上游未交付');
      expect(d.hint).toContain('13');
      expect(d.source).toBe('');
    });

    it('窗口内无样本：不给数值位，也不假装是上游的锅', () => {
      const d = metricDisplay(
        metric({
          availability: 'NO_DATA',
          value: null,
          source: null,
          numerator: null,
          denominator: null,
          basis: '窗口内既没有核对批次，也没有已录实盘行。',
          blockedByModule: null,
        }),
      );
      expect(d.hasValue).toBe(false);
      expect(d.hint).toContain('窗口内无样本');
      // NO_DATA 不该显示"待 xx"——上游是在的，只是这个窗口没数据
      expect(d.hint).not.toContain('待');
    });

    it('可算：给出百分比、口径来源与分子分母', () => {
      const d = metricDisplay(metric({}));
      expect(d.hasValue).toBe(true);
      expect(d.value).toBe('95.00%');
      expect(d.source).toBe('账实核对');
      expect(d.fraction).toBe('95 / 100');
    });

    it('负库存是次数不是比率，按整数显示', () => {
      const d = metricDisplay(
        metric({
          metricType: 'NEGATIVE_STOCK',
          metricLabel: '负库存发生次数',
          value: 0,
          source: 'INVENTORY_BALANCE',
          numerator: 0,
          denominator: null,
        }),
      );
      expect(d.value).toBe('0');
      expect(d.value).not.toContain('%');
      expect(d.source).toBe('库存余额');
    });
  });

  describe('覆盖度与部分得分', () => {
    it('覆盖度写成 2/6', () => {
      expect(coverageText(card({}))).toBe('2/6');
    });

    it('算不满六个指标时必须被识别为"部分得分"', () => {
      expect(isPartialScore(card({ availableMetricCount: 2, totalMetricCount: 6 }))).toBe(true);
      expect(isPartialScore(card({ availableMetricCount: 6, totalMetricCount: 6 }))).toBe(false);
    });

    it('只有一个指标可算时仍然是部分得分', () => {
      expect(isPartialScore(card({ availableMetricCount: 1 }))).toBe(true);
    });
  });

  describe('红线三种状态', () => {
    it('有值且低于阈值 → blocked', () => {
      const g = gate({ accuracyState: 'AVAILABLE', accuracyRate: 0.95, blocked: true });
      expect(gateTone(g)).toBe('blocked');
      expect(gateHeadline(g)).toContain('95.00%');
      expect(gateHeadline(g)).toContain('99.00%');
    });

    it('有值且达标 → passed', () => {
      const g = gate({ accuracyState: 'AVAILABLE', accuracyRate: 0.998, blocked: false });
      expect(gateTone(g)).toBe('passed');
      expect(gateHeadline(g)).toContain('未触发阻断');
    });

    it('无证据但仍阻断时，不能显示成"通过"', () => {
      // "从没核过账"在结算场景下与"账错了"同样危险，不能显示成好消息。
      const g = gate({ accuracyState: 'NO_DATA', accuracyRate: null, blocked: true });
      expect(gateTone(g)).toBe('unverified');
      expect(gateTone(g)).not.toBe('passed');
      expect(gateHeadline(g)).toContain('无证据');
      expect(gateHeadline(g)).not.toContain('达标');
    });

    it('无证据时标题里不出现 NaN 或 0%', () => {
      const g = gate({ accuracyState: 'NO_DATA', accuracyRate: null, blocked: true });
      expect(gateHeadline(g)).not.toContain('NaN');
      expect(gateHeadline(g)).not.toContain('0.00%');
    });
  });

  describe('问题排序', () => {
    function issue(severity: string, scopeLabel: string, metricType: string) {
      return {
        severity,
        metricType,
        scopeLabel,
        title: 't',
        detail: 'd',
        recommendation: 'r',
        evidence: 'e',
      } as InventoryHealthIssue;
    }

    it('严重的排在最前', () => {
      const sorted = sortIssues([
        issue('INFO', 'A', 'ACCURACY'),
        issue('CRITICAL', 'A', 'ACCURACY'),
        issue('WARN', 'A', 'ACCURACY'),
      ]);
      expect(sorted.map((i) => i.severity)).toEqual(['CRITICAL', 'WARN', 'INFO']);
    });

    it('同级按范围名再按指标名', () => {
      const sorted = sortIssues([
        issue('CRITICAL', 'B 仓', 'ACCURACY'),
        issue('CRITICAL', 'A 仓', 'TURNOVER'),
        issue('CRITICAL', 'A 仓', 'ACCURACY'),
      ]);
      expect(sorted.map((i) => `${i.scopeLabel}/${i.metricType}`)).toEqual([
        'A 仓/ACCURACY',
        'A 仓/TURNOVER',
        'B 仓/ACCURACY',
      ]);
    });

    it('不改动传入的数组', () => {
      const input = [issue('INFO', 'A', 'ACCURACY')];
      sortIssues(input);
      expect(input[0]?.severity).toBe('INFO');
    });
  });

  describe('趋势', () => {
    function point(source: string, sourceLabel: string, accuracy: number | null) {
      return {
        source,
        sourceId: 'id',
        sourceLabel,
        at: '2026-10-06T00:00:00+08:00',
        accuracy,
        sampleCount: 10,
        discrepancyCount: 1,
      } as InventoryHealthTrend['points'][number];
    }

    it('标签带上口径名，让人知道这个点是核对来的还是盘点来的', () => {
      expect(trendPointLabel(point('RECONCILIATION', '全部余额行', 0.9))).toBe(
        '账实核对 · 全部余额行',
      );
      expect(trendPointLabel(point('STOCKTAKE', 'PD-1 上海仓', 1))).toBe('盘点 · PD-1 上海仓');
    });

    it('样本为 0 时准确率显示破折号而不是 0%', () => {
      expect(trendAccuracyText(point('RECONCILIATION', 'x', null))).toBe('—');
    });
  });
});
