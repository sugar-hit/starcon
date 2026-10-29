import { describe, expect, it } from 'vitest';
import {
  attributionModeText,
  attributionModeTone,
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
  toNumber,
  unavailableMetricText,
  validateReportRange,
} from './ad-product-report-model';
import type { AdProductMetricDefinition } from './ad-product-report-model';

/**
 * 14.3.1.3 商品维度报表 前端模型。
 *
 * 🔴 这些断言盯的是**页面最容易说错的六件事**：
 *
 * 1. **归因方式必须分得开**。`DIRECT`（平台原值）/ `ALLOCATED_EVENLY`（分摊值）/ `MIXED`
 *    渲染成同一个数字，运营就会把分摊值当成平台原值看 —— 那是完全错误的投放决策。
 * 2. **不可比 ≠ 0**。`null` 渲染成「不可比」，`0` 才是「可比但真的是零」。
 * 3. **口径写在表头**。表头文字必须带公式（`ACOS = Σ广告花费 / Σ广告销售额`）。
 * 4. **未接入的指标不给数**。利润上游读不到时是「未接入（原因）」，不是 0、也不是空白。
 * 5. **四种「空」分开说**。「还没同步到数据」/「这个区间没有投放」/「被筛掉了」/
 *    「没登记投放商品」是四件不同的事。
 * 6. **前端不做任何算术**。比率与金额都原样展示（`toNumber` 只做类型转换，不做四则运算）。
 */

function definition(overrides: Partial<AdProductMetricDefinition> = {}): AdProductMetricDefinition {
  return {
    metric: 'acos',
    label: 'ACOS',
    available: true,
    formula: 'ACOS = Σ广告花费 / Σ广告销售额',
    source: 'ad_campaign_daily_metric（V273）经两级等分',
    ownerModule: '',
    unavailableReason: '',
    ...overrides,
  };
}

describe('14.3.1.3 归因方式分得开', () => {
  it('直接归属 / 等分摊销 / 混合是三种不同的说法', () => {
    expect(attributionModeText('DIRECT')).toBe('直接归属');
    expect(attributionModeText('ALLOCATED_EVENLY')).toBe('等分摊销');
    expect(attributionModeText('MIXED')).toBe('部分直接 / 部分等分');
  });

  it('语气也分得开（等分摊销是弱化色，直接归属是正常色）', () => {
    expect(attributionModeTone('DIRECT')).toBe('text-emerald-600');
    expect(attributionModeTone('MIXED')).toBe('text-amber-600');
    expect(attributionModeTone('ALLOCATED_EVENLY')).toBe('text-muted-foreground');
  });

  it('认不出的取值退化成「等分摊销」（最保守的一侧，不假装是平台原值）', () => {
    expect(attributionModeText('WHATEVER')).toBe('等分摊销');
  });
});

describe('14.3.1.3 不可比 ≠ 0', () => {
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

describe('14.3.1.3 口径写在表头', () => {
  it('表头文字带公式（不是只有一个指标名）', () => {
    expect(metricHeader(definition())).toBe('ACOS（ACOS = Σ广告花费 / Σ广告销售额）');
  });

  it('表头文字在定义缺失时是空串，不许编一个出来', () => {
    expect(metricHeader(undefined)).toBe('');
  });

  it('按 key 取出对应指标的定义', () => {
    const defs = [
      definition(),
      definition({
        metric: 'ad_sales_share',
        label: '广告销售占比',
        formula: '广告销售占比 = 广告销售额 / 总销售额',
      }),
    ];
    expect(metricDefinitionOf(defs, 'ad_sales_share')?.label).toBe('广告销售占比');
    expect(metricDefinitionOf(defs, 'tacos')).toBeUndefined();
  });
});

describe('14.3.1.3 上游读不到就不给数', () => {
  const tacos = definition({
    metric: 'tacos',
    label: 'TACOS',
    available: false,
    formula: 'TACOS = Σ广告花费 / 总销售额',
    source: '—',
    ownerModule: '13.4.1.2（reporting 服务）',
    unavailableReason: '上游 13.4.1.2 这次没读到利润事实。',
  });

  it('未接入的指标渲染成「未接入（原因）」，不是 0、也不是空白', () => {
    const text = unavailableMetricText(tacos);
    expect(text).toContain('未接入');
    expect(text).toContain('13.4.1.2');
    expect(text).toContain('没读到利润事实');
    expect(text).not.toBe('0');
    expect(text).not.toBe('');
  });

  it('定义缺失时也照实说「未接入」，不编原因', () => {
    expect(unavailableMetricText(undefined)).toBe('未接入');
  });
});

describe('14.3.1.3 金额与数量只原样展示', () => {
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
    expect(reportRatio(4.5, false)).toBe('4.5000');
  });

  it('后端 BigDecimal 序列化成字符串时也照样展示（两种序列化都不能崩）', () => {
    // 🔴 Jackson 默认给 JSON number，OpenAPI 契约标注的是 string —— 两种都得兜住。
    expect(reportMoney('120.50', 'USD')).toBe('120.50 USD');
    expect(reportRatio('0.166667')).toBe('16.67%');
    expect(reportQuantity('1000')).toBe('1,000');
  });

  it('读不出来的值一律当 null（不可比），不当 0', () => {
    expect(toNumber('')).toBeNull();
    expect(toNumber('abc')).toBeNull();
    expect(toNumber(null)).toBeNull();
    expect(toNumber(undefined)).toBeNull();
    // 🔴 0 必须原样保留：0 是「可比但真的是零」。
    expect(toNumber(0)).toBe(0);
    expect(toNumber('0')).toBe(0);
  });
});

describe('14.3.1.3 四种「空」分开说', () => {
  it('没同步到数据', () => {
    expect(reportEmptyText('NO_SYNCED_DATA', '')).toContain('还没同步到数据');
  });

  it('这个区间没有投放数据', () => {
    expect(reportEmptyText('NO_METRICS_IN_RANGE', '')).toContain('没有任何投放指标');
  });

  it('被筛掉了', () => {
    expect(reportEmptyText('FILTERED_EMPTY', '')).toContain('筛选条件');
  });

  it('没登记投放商品（广告花费归不到任何商品）', () => {
    expect(reportEmptyText('NO_PRODUCT_BINDING', '')).toContain('没有登记投放商品');
  });

  it('有内容时是空串（不是一段占位文案）', () => {
    expect(reportEmptyText(null, '')).toBe('');
  });

  it('服务端给了说明就用服务端的（前端不自己改写口径）', () => {
    expect(reportEmptyText('NO_SYNCED_DATA', '服务端说的话')).toBe('服务端说的话');
  });
});

describe('14.3.1.3 时间范围必填', () => {
  it('缺起止日直接不通过', () => {
    const result = validateReportRange('', '');
    expect(result.ok).toBe(false);
    expect(result.errors).toContain('请选择统计起始日');
    expect(result.errors).toContain('请选择统计截止日');
  });

  it('起止颠倒不通过', () => {
    const result = validateReportRange('2026-10-02', '2026-10-01');
    expect(result.ok).toBe(false);
    expect(result.errors).toContain('统计起始日不能晚于统计截止日');
  });

  it('区间完整就通过', () => {
    expect(validateReportRange('2026-10-01', '2026-10-02').ok).toBe(true);
  });
});

describe('14.3.1.3 导出链接', () => {
  it('导出走 GET + query（与当前筛选完全一致）', () => {
    const url = reportExportUrl({
      from: '2026-10-01',
      to: '2026-10-31',
      dimension: 'SPU',
      shopCode: 'SHOP-1',
    });
    expect(url).toContain('/api/channel/ad-product-reports/export?');
    expect(url).toContain('from=2026-10-01');
    expect(url).toContain('to=2026-10-31');
    expect(url).toContain('dimension=SPU');
    expect(url).toContain('shopCode=SHOP-1');
  });

  it('空条件不进 query string', () => {
    expect(reportExportUrl({ from: '', to: '' })).toBe('/api/channel/ad-product-reports/export');
  });
});

describe('14.3.1.3 其它展示辅助', () => {
  it('商品维度有中文名', () => {
    expect(reportDimensionLabel('LISTING')).toBe('按平台商品（Listing）');
    expect(reportDimensionLabel('SKU')).toBe('按内部 SKU');
    expect(reportDimensionLabel('SPU')).toBe('按标准产品单元（SPU）');
  });

  it('时刻格式化（空值是破折号）', () => {
    expect(reportTimestampText(null)).toBe('—');
    expect(reportTimestampText('2026-10-01T09:00:00+08:00')).not.toBe('—');
  });
});
