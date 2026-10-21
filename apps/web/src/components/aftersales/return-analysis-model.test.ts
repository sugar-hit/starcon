import { describe, expect, it } from 'vitest';
import {
  ANOMALY_LABELS,
  BASIS_STATUS_LABELS,
  CALIBER_LABELS,
  caliberSourceLabel,
  crossCurrencyTotal,
  currencySummaryText,
  DEFAULT_THRESHOLDS,
  DIMENSION_LABELS,
  formatHours,
  formatMoney,
  formatQty,
  formatRate,
  formatRatio,
  GRANULARITY_LABELS,
  rateTone,
  rejectionTone,
  returnRateReading,
  severityTone,
  snapshotDimensionLabel,
} from './return-analysis-model';

/**
 * 11.5.1.1 退货分析的纯展示派生逻辑。
 *
 * <p>这些函数承载蓝图加粗句「<b>退货率必须与销售额同口径比较</b>」在界面上的语义，
 * 所以单独覆盖：
 *
 * <ul>
 *   <li><b>算不出来 ≠ 0</b>：分母为 0、没有样本时后端给的是 {@code null}，
 *       界面必须显示「—」/「无样本」而不是 0% / 0 小时 ——
 *       否则「这个维度没有销售额」会被读成「这个维度很健康」。</li>
 *   <li><b>比值只换算一次</b>：后端给的是标度 4 的比值（0.0500 = 5%），
 *       换算成百分比只发生在 {@link formatRate} 一处。</li>
 *   <li><b>跨币种不许合计</b>：{@link crossCurrencyTotal} 刻意抛错，
 *       让「想跨币种相加」的意图在编译期/运行期暴露出来。</li>
 *   <li><b>口径逐行标记</b>：单头 / 行级 / 件数三种口径不可比，
 *       标签必须互不相同，且每行都能标出来。</li>
 * </ul>
 */
describe('11.5.1.1 退货率的呈现口径', () => {
  it('比值只在这里换算成百分比', () => {
    expect(formatRate(0.05)).toBe('5.00%');
    expect(formatRate(0.1234)).toBe('12.34%');
    expect(formatRate(0)).toBe('0.00%');
    expect(formatRatio(0.2)).toBe('20.00%');
  });

  it('算不出来显示「—」而不是 0%', () => {
    // ⚠️ 这条是整页最重要的一条断言：null 的语义是「算不出来」，
    //   渲染成 0% 会被读成「这个维度一件没退」。
    expect(formatRate(null)).toBe('—');
    expect(formatRate(undefined)).toBe('—');
    expect(formatRatio(null)).toBe('—');
    expect(formatMoney(null)).toBe('—');
    expect(formatMoney(undefined)).toBe('—');
    expect(formatQty(null)).toBe('—');
    expect(formatHours(null)).toBe('无样本');
    expect(formatHours(undefined)).toBe('无样本');
  });

  it('金额保留两位、数量去掉无意义的小数尾巴', () => {
    expect(formatMoney(1234.5)).toBe('1234.50');
    expect(formatMoney('88.8')).toBe('88.80');
    expect(formatQty(4)).toBe('4');
    expect(formatQty(2.5)).toBe('2.50');
    expect(formatQty('3.000000')).toBe('3');
    expect(formatHours(12.345)).toBe('12.35 小时');
  });

  it('语气：算不出来是中性，不许被读成「很健康」', () => {
    expect(rateTone(null)).toBe('neutral');
    expect(rateTone(0.02)).toBe('success');
    expect(rateTone(0.05)).toBe('warning');
    expect(rateTone(0.1)).toBe('danger');
    expect(rejectionTone(null)).toBe('neutral');
    expect(rejectionTone(0.05)).toBe('success');
    expect(rejectionTone(0.1)).toBe('warning');
    expect(rejectionTone(0.2)).toBe('danger');
    expect(severityTone('HIGH')).toBe('danger');
    expect(severityTone('MEDIUM')).toBe('warning');
    expect(severityTone('LOW')).toBe('neutral');
  });

  it('退货率的读法必须同时说出分子、分母与口径', () => {
    const reading = returnRateReading({
      rate: 0.05,
      returnAmount: 50,
      salesAmount: 1000,
      caliber: 'CASE_LEVEL',
    });
    expect(reading.text).toContain('5.00%');
    expect(reading.text).toContain('单头口径');
    expect(reading.text).toContain('50.00');
    expect(reading.text).toContain('1000.00');
    expect(reading.tone).toBe('warning');
  });

  it('退货率算不出来时，读法明说「算不出来」而不是 0%', () => {
    const reading = returnRateReading({
      rate: null,
      returnAmount: 50,
      salesAmount: 0,
      caliber: 'CASE_LEVEL',
    });
    expect(reading.text).toContain('算不出来');
    expect(reading.text).not.toContain('0.00%');
    expect(reading.tone).toBe('neutral');
  });

  it('跨币种合计刻意不存在', () => {
    // 100 元 + 80 欧 = 180 在对账时是错的，所以这个函数只负责把意图暴露出来。
    expect(() => crossCurrencyTotal()).toThrow(/跨币种/);
  });

  it('币种摘要逐币种给出分子分母', () => {
    const text = currencySummaryText({
      currency: 'USD',
      caseCount: 3,
      returnAmount: 50,
      salesAmount: 1000,
      rate: 0.05,
    });
    expect(text).toContain('USD');
    expect(text).toContain('3 张退货售后单');
    expect(text).toContain('5.00%');
  });
});

describe('11.5.1.1 标签与口径标记', () => {
  it('五个下钻维度逐个对应蓝图职责句', () => {
    expect(DIMENSION_LABELS.SHOP).toBe('店铺');
    expect(DIMENSION_LABELS.SKU).toBe('SKU');
    expect(DIMENSION_LABELS.CATEGORY).toBe('类目');
    expect(DIMENSION_LABELS.REASON).toBe('原因');
    expect(DIMENSION_LABELS.BATCH).toBe('批次');
  });

  it('三种口径的标签互不相同（不可比就得标出来）', () => {
    expect(CALIBER_LABELS.CASE_LEVEL).toBe('单头口径');
    expect(CALIBER_LABELS.LINE_LEVEL).toBe('行级口径');
    expect(CALIBER_LABELS.QTY_LEVEL).toBe('件数口径');
    expect(new Set(Object.values(CALIBER_LABELS)).size).toBe(3);
  });

  it('趋势粒度与异常规则都有中文标签', () => {
    expect(GRANULARITY_LABELS.DAY).toBe('日');
    expect(GRANULARITY_LABELS.WEEK).toBe('周');
    expect(GRANULARITY_LABELS.MONTH).toBe('月');
    expect(ANOMALY_LABELS.RETURN_RATE_SPIKE).toBe('退货率倍数异常');
    expect(ANOMALY_LABELS.REJECTION_RATE_SPIKE).toBe('拒退率异常');
    expect(ANOMALY_LABELS.BATCH_VOLUME_SPIKE).toBe('批次件数异常');
    expect(ANOMALY_LABELS.REASON_CONCENTRATION).toBe('退货原因过度集中');
    expect(ANOMALY_LABELS.TREND_DETERIORATION).toBe('退货率环比恶化');
  });

  it('口径来源分得清租户口径与默认口径', () => {
    expect(caliberSourceLabel('TENANT_BASIS')).toBe('租户口径');
    expect(caliberSourceLabel('DEFAULT')).toBe('默认口径');
    expect(caliberSourceLabel(undefined)).toBe('未知口径');
  });

  it('口径版本状态：已换版不等于作废', () => {
    expect(BASIS_STATUS_LABELS.ACTIVE).toBe('生效中');
    expect(BASIS_STATUS_LABELS.SUPERSEDED).toBe('已换版');
  });

  it('快照的总体行与维度行叫法不同', () => {
    expect(snapshotDimensionLabel('OVERVIEW')).toBe('总体');
    expect(snapshotDimensionLabel('SHOP')).toBe('店铺');
    expect(snapshotDimensionLabel(null)).toBe('未知');
  });

  it('默认阈值与后端常量对齐（未登记口径时用的就是这一套）', () => {
    expect(DEFAULT_THRESHOLDS.rateAlertMultiplier).toBe(2);
    expect(DEFAULT_THRESHOLDS.rateAlertMinSalesShare).toBe(0.01);
    expect(DEFAULT_THRESHOLDS.rejectionAlertGapPp).toBe(20);
    expect(DEFAULT_THRESHOLDS.rejectionAlertMinCases).toBe(5);
    expect(DEFAULT_THRESHOLDS.batchAlertMultiplier).toBe(3);
    expect(DEFAULT_THRESHOLDS.batchAlertMinQty).toBe(10);
    expect(DEFAULT_THRESHOLDS.reasonAlertShare).toBe(0.6);
    expect(DEFAULT_THRESHOLDS.reasonAlertMinCases).toBe(10);
    expect(DEFAULT_THRESHOLDS.trendAlertGapPp).toBe(5);
  });
});
