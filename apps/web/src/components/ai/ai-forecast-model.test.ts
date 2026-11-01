import { describe, expect, it } from 'vitest';
import type {
  AiForecastElasticityRow,
  AiForecastFactorRow,
  AiForecastHotItemRow,
  AiForecastOpportunityRow,
  AiForecastRunRow,
} from '../../../../../packages/api-contracts/src/index';
import {
  adviceNotice,
  algorithmLabel,
  elasticityRows,
  factorRows,
  factorTone,
  formatRate,
  formatUnits,
  historySourceLabel,
  hotItemRows,
  intervalGeometry,
  intervalTone,
  opportunityRows,
  runConnectivityNote,
  suggestionStatusLabel,
  suggestionTone,
} from './ai-forecast-model';

/**
 * 18.4.2.2 选品与需求预测 前端模型的单测。
 *
 * 🔴 这组用例守的是四件最容易做丢的事：
 *
 * 1. **区间必须真的显示出来** —— 只给一个点估计的页面 = 未完成（蓝图加粗句）。
 *    `intervalGeometry` 把点估计在区间里的位置算出来；区间越宽越不可信。
 * 2. **影响因素不是标签，是「方向 + 取值 + 口径说明」** —— 口径说明直接用后端原文。
 * 3. **建议只作参考，不自动执行** —— 采纳的提示语必须写明「不会替你下单 / 改价」。
 * 4. **算不出来就是算不出来** —— 弹性、增长率、连通性都不能用 0 / 「暂无」冒充。
 */

const factor = (over: Partial<AiForecastFactorRow>): AiForecastFactorRow => ({
  id: '00000000-0000-4000-8000-000000000001',
  resultId: '00000000-0000-4000-8000-000000000002',
  factorCode: 'PROMOTION',
  factorLabel: '促销 / 大促',
  direction: 'UP',
  value: 0.3,
  valueText: '双十一',
  impact: 90,
  explanation: '促销「双十一」在预测期内生效，预期销量提升 30%，因此预测量上移 90 件。',
  source: 'platform_forecast_promotion（租户录入，非上游同步）',
  createdAt: '2026-11-02T00:00:00Z',
  ...over,
});

const run = (over: Partial<AiForecastRunRow>): AiForecastRunRow => ({
  id: '00000000-0000-4000-8000-000000000003',
  runNo: 'FC-ABCD1234-000001',
  algorithm: 'MOVING_AVERAGE',
  maWindowDays: 3,
  smoothingAlpha: 0.3,
  windowDays: 30,
  horizonDays: 30,
  confidenceLevel: 0.95,
  zValue: 1.96,
  historySource: 'MANUAL',
  status: 'COMPLETED',
  evaluatedSkuCount: 3,
  forecastSkuCount: 2,
  skippedSkuCount: 1,
  skippedReason: '有 1 个 SKU 因样本不足 4 天被跳过',
  inventoryConnected: true,
  inTransitConnected: true,
  purchaseConnected: true,
  note: '',
  startedAt: '2026-11-02T00:00:00Z',
  finishedAt: '2026-11-02T00:00:05Z',
  ...over,
});

describe('18.4.2.2 区间可视化', () => {
  it('点估计落在区间里的位置算得出来', () => {
    const geometry = intervalGeometry(80, 100, 120);
    expect(geometry.valid).toBe(true);
    expect(geometry.pointPct).toBeCloseTo(50, 5);
    expect(geometry.widthRatio).toBeCloseTo(0.2, 5);
  });

  it('点估计贴近下界时位置靠左', () => {
    const geometry = intervalGeometry(80, 84, 120);
    expect(geometry.pointPct).toBeLessThan(20);
  });

  it('区间宽度相对点估计的比例越大越不可信', () => {
    expect(intervalTone(0.1)).toBe('ok');
    expect(intervalTone(0.3)).toBe('warn');
    expect(intervalTone(0.8)).toBe('danger');
  });

  it('算不出区间时不当成零宽区间', () => {
    const geometry = intervalGeometry(null, null, null);
    expect(geometry.valid).toBe(false);
    expect(geometry.pointPct).toBe(0);
  });
});

describe('18.4.2.2 影响因素', () => {
  it('方向分三档，NEUTRAL 不是「没影响」而是「不影响点估计」', () => {
    expect(factorTone('UP')).toBe('ok');
    expect(factorTone('DOWN')).toBe('danger');
    expect(factorTone('NEUTRAL')).toBe('muted');
  });

  it('每条因素都带口径说明（后端原文，前端不自己编）', () => {
    const rows = factorRows([factor({})]);
    expect(rows).toHaveLength(1);
    expect(rows[0]?.explanation).toContain('预期销量提升 30%');
    expect(rows[0]?.value).toBe('双十一');
  });

  it('没有数值的因素显示「不适用」而不是 0', () => {
    const rows = factorRows([factor({ value: null, valueText: '' })]);
    expect(rows[0]?.value).toBe('不适用');
  });
});

describe('18.4.2.2 建议只作参考', () => {
  it('采纳提示写明不会替你下单 / 改价', () => {
    expect(adviceNotice('REPLENISH')).toContain('不会创建补货单');
    expect(adviceNotice('PRICE')).toContain('不会改价');
    expect(adviceNotice('REPLENISH')).toContain('不自动执行');
  });

  it('建议状态分三态，没有「已执行」', () => {
    expect(suggestionStatusLabel('SUGGESTED')).toContain('建议已生成');
    expect(suggestionStatusLabel('ADOPTED')).toContain('未执行');
    expect(suggestionStatusLabel('REJECTED')).toContain('已驳回');
    expect(suggestionTone('ADOPTED')).toBe('ok');
    expect(suggestionTone('REJECTED')).toBe('danger');
    expect(suggestionTone('SUGGESTED')).toBe('muted');
  });
});

describe('18.4.2.2 上游未接通如实显示', () => {
  it('未接通的上游写明「未接通 + 归属系统」', () => {
    const note = runConnectivityNote(
      run({ inventoryConnected: false, inTransitConnected: false, purchaseConnected: false }),
    );
    expect(note).toContain('未接通');
    expect(note).toContain('7 库存');
    expect(note).toContain('6 采购');
  });

  it('没跑过预测时不说上游可用', () => {
    expect(runConnectivityNote(null)).toContain('还没有运行过预测');
  });
});

describe('18.4.2.2 算不出来就说算不出来', () => {
  const elasticity = (over: Partial<AiForecastElasticityRow>): AiForecastElasticityRow => ({
    skuCode: 'SKU-A',
    skuName: '测试款 A',
    available: false,
    elasticity: null,
    samplePairs: 0,
    windowDays: 30,
    currentPrice: null,
    currency: null,
    method: '相邻两日 Δln(销量) / Δln(单价) 的中位数',
    reason: '窗口内没有可用的价格/销量样本对',
    basis: '',
    ...over,
  });

  it('弹性算不出来时显示「算不出来」而不是 0', () => {
    const rows = elasticityRows([elasticity({})]);
    expect(rows[0]?.value).toBe('算不出来');
    expect(rows[0]?.reason).toContain('没有可用的价格/销量样本对');
    expect(rows[0]?.tone).toBe('muted');
  });

  it('弹性算得出来时显示数值', () => {
    const rows = elasticityRows([
      elasticity({ available: true, elasticity: -1.8, samplePairs: 3 }),
    ]);
    expect(rows[0]?.value).toBe('-1.800');
    expect(rows[0]?.tone).toBe('ok');
  });

  it('爆品没有基期时增长率显示「—」而不是 0%', () => {
    const item = (over: Partial<AiForecastHotItemRow>): AiForecastHotItemRow => ({
      skuCode: 'SKU-HOT',
      skuName: '爆款',
      detected: false,
      windowUnits: 500,
      previousWindowUnits: null,
      growthRate: null,
      windowDays: 30,
      activeDays: 1,
      unitsThreshold: 100,
      growthThreshold: 0.2,
      reason: '没有基期（上一个等长窗口没有记录），算不出增长率',
      basis: '',
      ...over,
    });
    const rows = hotItemRows([item({})]);
    expect(rows[0]?.growth).toBe('—');
    expect(rows[0]?.reason).toContain('没有基期');
  });

  it('机会品趋势比算不出来时显示「—」', () => {
    const item = (over: Partial<AiForecastOpportunityRow>): AiForecastOpportunityRow => ({
      skuCode: 'SKU-UP',
      skuName: '上升款',
      detected: false,
      recentDailyUnits: 1,
      previousDailyUnits: 0,
      trendRatio: null,
      forecastPoint: null,
      forecastUpper: null,
      inventoryQty: null,
      coverageDays: null,
      gapQty: null,
      windowDays: 30,
      trendThreshold: 1.3,
      minDailyUnitsThreshold: 1,
      reason: '前 7 天日均为 0，算不出趋势比（不是「增长 0%」）',
      basis: '',
      ...over,
    });
    const rows = opportunityRows([item({})]);
    expect(rows[0]?.trend).toBe('—');
    expect(rows[0]?.reason).toContain('不是「增长 0%」');
  });
});

describe('18.4.2.2 展示口径', () => {
  it('来源必须显示出来（预测用的销量是哪来的）', () => {
    expect(historySourceLabel('REPORTING')).toBe('13 分析同步');
    expect(historySourceLabel('MANUAL')).toBe('人工录入');
    expect(historySourceLabel('MIXED')).toBe('人工 + 上游混合');
  });

  it('算法名说人话', () => {
    expect(algorithmLabel('MOVING_AVERAGE')).toBe('移动平均');
    expect(algorithmLabel('EXPONENTIAL_SMOOTHING')).toBe('指数平滑');
  });

  it('空值显示「—」而不是 0', () => {
    expect(formatUnits(null)).toBe('—');
    expect(formatUnits(undefined)).toBe('—');
    expect(formatRate(null)).toBe('—');
    expect(formatUnits(12.5)).toBe('12.5');
    expect(formatRate(0.3)).toBe('30%');
  });
});
