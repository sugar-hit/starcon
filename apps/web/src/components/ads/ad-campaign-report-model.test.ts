import { describe, expect, it } from 'vitest';
import {
  metricDefinitionOf,
  metricHeader,
  reportDimensionLabel,
  reportEmptyText,
  reportExportUrl,
  reportMoney,
  reportQuantity,
  reportRatio,
  reportRatioTone,
  reportTimestampText,
  unavailableMetricText,
  validateReportRange,
} from './ad-campaign-report-model';
import type { AdReportMetricDefinition } from './ad-campaign-report-model';

/**
 * 14.3.1.1 活动报表 前端模型。
 *
 * 🔴 这些断言盯的是**页面最容易说错的四件事**：
 *
 * 1. **口径写在表头**。表头文字必须带公式（`ACOS = Σ花费 / Σ广告销售额`）——
 *    蓝图加粗句「三个口径要在表头写明定义」就是这一条。
 * 2. **TACOS 的「没有」看得见**。未接入的指标渲染成「未接入（原因）」，
 *    **不是** 0、也不是空白。没有分母却给一个数，比不给更糟。
 * 3. **不可比 ≠ 0**。null 渲染成「不可比」，0 才是「可比但真的是零」。
 * 4. **三种「空」必须分开说**。「还没同步到数据」/「这个区间没有投放」/「被筛掉了」
 *    是三件不同的事，渲染成同一种空态就是把「没数据」说成「没投放」。
 */

function definition(overrides: Partial<AdReportMetricDefinition> = {}): AdReportMetricDefinition {
  return {
    metric: 'acos',
    label: 'ACOS',
    available: true,
    formula: 'ACOS = Σ花费 / Σ广告销售额',
    source: 'ad_campaign_daily_metric（V273）聚合',
    ownerModule: '',
    unavailableReason: '',
    ...overrides,
  };
}

describe('14.3.1.1 口径写在表头', () => {
  it('表头文字带公式（不是只有一个指标名）', () => {
    expect(metricHeader(definition())).toBe('ACOS（ACOS = Σ花费 / Σ广告销售额）');
  });

  it('表头文字在定义缺失时是空串，不许编一个出来', () => {
    expect(metricHeader(undefined)).toBe('');
  });

  it('按 key 取出对应指标的定义', () => {
    const defs = [
      definition(),
      definition({ metric: 'roas', label: 'ROAS', formula: 'ROAS = Σ广告销售额 / Σ花费' }),
    ];
    expect(metricDefinitionOf(defs, 'roas')?.formula).toBe('ROAS = Σ广告销售额 / Σ花费');
    expect(metricDefinitionOf(defs, 'tacos')).toBeUndefined();
  });
});

describe('14.3.1.1 TACOS 没有分母就不给数', () => {
  const tacos = definition({
    metric: 'tacos',
    label: 'TACOS',
    available: false,
    formula: 'TACOS = 广告花费 / 总销售额（总销售额含自然单，不是广告销售额）',
    source: '—',
    ownerModule: '13.4 利润模块（未交付：卷 13 的主责服务 reporting 不在集成头）',
    unavailableReason: '总销售额口径未接入，当前不出 TACOS 数值。',
  });

  it('未接入的指标渲染成「未接入（原因）」，不是 0、也不是空白', () => {
    const text = unavailableMetricText(tacos);
    expect(text).toContain('未接入');
    expect(text).toContain('13.4');
    expect(text).toContain('总销售额口径未接入');
    expect(text).not.toBe('0');
    expect(text).not.toBe('');
  });

  it('定义缺失时也照实说「未接入」，不编原因', () => {
    expect(unavailableMetricText(undefined)).toBe('未接入');
  });
});

describe('14.3.1.1 不可比 ≠ 0', () => {
  it('null（不可比）渲染成「不可比」', () => {
    expect(reportRatio(null)).toBe('不可比');
    expect(reportRatio(undefined)).toBe('不可比');
  });

  it('0（可比但真的是零）渲染成 0.00%', () => {
    expect(reportRatio(0)).toBe('0.00%');
    expect(reportRatio(0, false)).toBe('0.0000');
  });

  it('语气区分：不可比是弱化色，可比是正常色', () => {
    expect(reportRatioTone(null)).toBe('text-muted-foreground');
    expect(reportRatioTone(0)).toBe('text-foreground');
  });
});

describe('14.3.1.1 金额与数量只原样展示', () => {
  it('金额带币种', () => {
    expect(reportMoney(120.5, 'USD')).toBe('120.50 USD');
    expect(reportMoney(null, 'USD')).toBe('—');
  });

  it('数量是计数，最多两位小数', () => {
    expect(reportQuantity(1000)).toBe('1,000');
    expect(reportQuantity(null)).toBe('—');
  });

  it('比率按百分比或原值展示（不做任何额外算术）', () => {
    expect(reportRatio(0.275)).toBe('27.50%');
    expect(reportRatio(3.636364, false)).toBe('3.6364');
  });
});

describe('14.3.1.1 三种「空」分开说', () => {
  it('没同步到数据', () => {
    expect(reportEmptyText('NO_SYNCED_DATA', '')).toContain('还没同步到数据');
  });

  it('这个区间没有投放数据', () => {
    expect(reportEmptyText('NO_METRICS_IN_RANGE', '')).toContain('没有任何投放指标');
  });

  it('被筛掉了', () => {
    expect(reportEmptyText('FILTERED_EMPTY', '')).toContain('筛选条件');
  });

  it('有内容时是空串（不是一段占位文案）', () => {
    expect(reportEmptyText(null, '')).toBe('');
  });
});

describe('14.3.1.1 时间范围必填', () => {
  it('缺起止日直接不通过', () => {
    const result = validateReportRange('', '');
    expect(result.ok).toBe(false);
    expect(result.errors).toContain('请选择统计起始日');
    expect(result.errors).toContain('请选择统计截止日');
  });

  it('起止颠倒不通过', () => {
    const result = validateReportRange('2026-10-02', '2026-10-01');
    expect(result.ok).toBe(false);
    expect(result.errors).toContain('统计起始日不能晚于截止日');
  });

  it('区间完整就通过', () => {
    expect(validateReportRange('2026-10-01', '2026-10-02').ok).toBe(true);
  });
});

describe('14.3.1.1 导出链接', () => {
  it('导出走 GET + query（与当前筛选完全一致）', () => {
    const url = reportExportUrl({
      from: '2026-10-01',
      to: '2026-10-31',
      granularity: 'WEEK',
      dimension: 'PLATFORM',
      shopCode: 'SHOP-1',
    });
    expect(url).toContain('/api/channel/ad-reports/export?');
    expect(url).toContain('from=2026-10-01');
    expect(url).toContain('to=2026-10-31');
    expect(url).toContain('granularity=WEEK');
    expect(url).toContain('dimension=PLATFORM');
    expect(url).toContain('shopCode=SHOP-1');
  });

  it('空条件不进 query string', () => {
    expect(reportExportUrl({ from: '', to: '' })).toBe('/api/channel/ad-reports/export');
  });
});

describe('14.3.1.1 其它展示辅助', () => {
  it('分组维度有中文名', () => {
    expect(reportDimensionLabel('CAMPAIGN')).toBe('按活动');
    expect(reportDimensionLabel('PLATFORM')).toBe('按平台');
    expect(reportDimensionLabel('SHOP')).toBe('按店铺');
    expect(reportDimensionLabel('AD_TYPE')).toBe('按归一后广告类型');
    expect(reportDimensionLabel('CURRENCY')).toBe('按币种');
  });

  it('时刻格式化（空值是破折号）', () => {
    expect(reportTimestampText(null)).toBe('—');
    expect(reportTimestampText('2026-10-01T09:00:00+08:00')).not.toBe('—');
  });
});
