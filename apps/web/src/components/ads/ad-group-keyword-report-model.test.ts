import { describe, expect, it } from 'vitest';
import {
  cacheStatusText,
  cacheStatusTone,
  evaluationOutcomeText,
  evaluationOutcomeTone,
  exportCsvUrl,
  MAX_SELECTED_KEYS,
  metricDefinitionOf,
  metricHeader,
  reportDimensionLabel,
  reportEmptyText,
  reportMoney,
  reportQuantity,
  reportRatio,
  reportRatioTone,
  reportTimestampText,
  stillReportingText,
  unavailableMetricText,
  validateExportSelection,
  validateReportRange,
} from './ad-group-keyword-report-model';
import type { AdGroupReportMetricDefinition } from './ad-group-keyword-report-model';

/**
 * 14.3.1.2 广告组与词报表 前端模型。
 *
 * 🔴 这些断言盯的是**页面最容易说错的六件事**：
 *
 * 1. **组级指标的「没有」看得见**。未接入渲染成「未接入（原因）」，**不是 0、也不是空白** ——
 *    没有事实源却给一个数，比不给更糟。
 * 2. **口径写在表头**。表头文字必须带公式（`ACOS = Σ花费 / Σ销售额`）。
 * 3. **不可比 ≠ 0**。null 渲染成「不可比」，0 才是「可比但真的是零」。
 * 4. **「否定后仍有数据」不是 0**，也不能被渲染成「否」——
 *    `null`（无法评估）必须写「无法评估」。
 * 5. **三种「空」必须分开说**。「还没同步到数据」/「这个区间没有投放」/「被筛掉了」
 *    是三件不同的事，渲染成同一种空态就是把「没数据」说成「没投放」。
 * 6. **缓存的过期时刻看得见**。不会出现「缓存了但不知道什么时候过期」。
 */

function definition(
  overrides: Partial<AdGroupReportMetricDefinition> = {},
): AdGroupReportMetricDefinition {
  return {
    metric: 'acos',
    label: 'ACOS',
    available: true,
    formula: 'ACOS = Σ花费 / Σ销售额',
    source: 'V274 指标表聚合（本服务层现算）',
    ownerModule: '',
    unavailableReason: '',
    ...overrides,
  };
}

describe('14.3.1.2 口径写在表头', () => {
  it('表头文字带公式（不是只有一个指标名）', () => {
    expect(metricHeader(definition())).toBe('ACOS（ACOS = Σ花费 / Σ销售额）');
  });

  it('表头文字在定义缺失时是空串，不许编一个出来', () => {
    expect(metricHeader(undefined)).toBe('');
  });

  it('指标定义按 key 找，找不到就是 undefined', () => {
    const list = [definition(), definition({ metric: 'roas', label: 'ROAS' })];
    expect(metricDefinitionOf(list, 'roas')?.label).toBe('ROAS');
    expect(metricDefinitionOf(list, 'tacos')).toBeUndefined();
  });
});

describe('14.3.1.2 组级指标的「没有」看得见', () => {
  it('未接入渲染成「未接入（责任模块：原因）」，不是 0 也不是空白', () => {
    const text = unavailableMetricText(
      definition({
        metric: 'group_metrics',
        label: '广告组指标',
        available: false,
        ownerModule: '14.1.2.1 广告组管理',
        unavailableReason: '现有事实表里没有组级指标表',
      }),
    );
    expect(text).toContain('未接入');
    expect(text).toContain('14.1.2.1 广告组管理');
    expect(text).toContain('现有事实表里没有组级指标表');
    expect(text).not.toBe('0');
  });

  it('定义缺失时也是「未接入」，不许渲染成空格子（空格子会被读成「数据是 0」）', () => {
    expect(unavailableMetricText(undefined)).toBe('未接入');
  });
});

describe('14.3.1.2 不可比 ≠ 0', () => {
  it('null 渲染成「不可比」', () => {
    expect(reportRatio(null)).toBe('不可比');
    expect(reportRatio(undefined)).toBe('不可比');
  });

  it('0 渲染成「0.00%」（可比但真的是零）', () => {
    expect(reportRatio(0)).toBe('0.00%');
    expect(reportRatio(0, false)).toBe('0.0000');
  });

  it('null 的语气色是弱化的，0 是正常色', () => {
    expect(reportRatioTone(null)).toBe('text-muted-foreground');
    expect(reportRatioTone(0)).toBe('text-foreground');
  });

  it('金额与数量：null 是「—」而不是 0', () => {
    expect(reportMoney(null)).toBe('—');
    expect(reportMoney(null, 'USD')).toBe('—');
    expect(reportQuantity(null)).toBe('—');
    expect(reportMoney(12.5, 'USD')).toBe('12.50 USD');
  });
});

describe('14.3.1.2 否定效果评估：结论不能被渲染成 0', () => {
  it('四种结论各有各的说法', () => {
    expect(evaluationOutcomeText('STILL_REPORTING')).toBe('否定后仍有数据');
    expect(evaluationOutcomeText('NO_DATA_AFTER')).toBe('否定后无数据');
    expect(evaluationOutcomeText('ZERO_SPEND_AFTER')).toBe('否定后有数据但无花费');
    expect(evaluationOutcomeText('NOT_EVALUABLE')).toBe('无法评估（没有生效日）');
    expect(evaluationOutcomeText(null)).toBe('—');
  });

  it('「否定后仍有数据」是红色（需要人去看），无法评估是琥珀色', () => {
    expect(evaluationOutcomeTone('STILL_REPORTING')).toBe('text-destructive');
    expect(evaluationOutcomeTone('NOT_EVALUABLE')).toBe('text-amber-600');
    expect(evaluationOutcomeTone('NO_DATA_AFTER')).toBe('text-foreground');
  });

  it('null（无法评估）写「无法评估」，不是「否」（「否」会被读成「否定生效了」）', () => {
    expect(stillReportingText(null)).toBe('无法评估');
    expect(stillReportingText(undefined)).toBe('无法评估');
    expect(stillReportingText(true)).toBe('是');
    expect(stillReportingText(false)).toBe('否');
  });
});

describe('14.3.1.2 三种「空」必须分开说', () => {
  it('三种原因各有各的文案', () => {
    expect(reportEmptyText('NO_SYNCED_DATA', '还没同步到数据。')).toContain('还没同步到数据');
    expect(reportEmptyText('NO_METRICS_IN_RANGE', '这个区间没有投放。')).toContain('没有投放');
    expect(reportEmptyText('FILTERED_EMPTY', '被筛掉了。')).toContain('被筛掉了');
  });

  it('没有原因就没有空态文案', () => {
    expect(reportEmptyText(null, '')).toBe('');
  });
});

describe('14.3.1.2 缓存的过期时刻看得见', () => {
  it('四种缓存状态各有各的说法', () => {
    expect(cacheStatusText('HIT')).toBe('命中缓存');
    expect(cacheStatusText('MISS')).toContain('现算');
    expect(cacheStatusText('STALE')).toContain('失效');
    expect(cacheStatusText('BYPASS')).toContain('不缓存');
    expect(cacheStatusText(null)).toBe('—');
  });

  it('命中是弱化色，不缓存是琥珀色（要让人知道为什么没缓存）', () => {
    expect(cacheStatusTone('HIT')).toBe('text-muted-foreground');
    expect(cacheStatusTone('BYPASS')).toBe('text-amber-600');
  });
});

describe('14.3.1.2 维度与形状', () => {
  it('四个维度各有各的显示名', () => {
    expect(reportDimensionLabel('GROUP')).toBe('按广告组');
    expect(reportDimensionLabel('KEYWORD')).toBe('按关键词');
    expect(reportDimensionLabel('SEARCH_TERM')).toBe('按搜索词');
    expect(reportDimensionLabel('NEGATIVE_EFFECT')).toBe('按否定记录');
  });

  it('时间戳渲染成可读时间，空值是「—」', () => {
    expect(reportTimestampText(null)).toBe('—');
    expect(reportTimestampText('2026-10-01T09:00:00+08:00')).toContain('2026');
  });

  it('导出下载链接指向导出留痕的 id', () => {
    expect(exportCsvUrl('abc-123')).toBe(
      '/api/channel/ad-group-keyword-reports/exports/abc-123/csv',
    );
  });
});

describe('14.3.1.2 表单校验', () => {
  it('时间范围必填', () => {
    expect(validateReportRange('', '').ok).toBe(false);
    expect(validateReportRange('', '2026-10-01').errors).toContain('请选择统计起始日');
    expect(validateReportRange('2026-10-01', '').errors).toContain('请选择统计截止日');
  });

  it('起止颠倒要报错', () => {
    const result = validateReportRange('2026-10-02', '2026-10-01');
    expect(result.ok).toBe(false);
    expect(result.errors).toContain('统计起始日不能晚于截止日');
  });

  it('空选中是合法的（= 导出当前筛选的全部）', () => {
    expect(validateExportSelection([]).ok).toBe(true);
  });

  it('选中超过上限要报错', () => {
    const keys = Array.from({ length: MAX_SELECTED_KEYS + 1 }, (_, i) => `k-${i}`);
    const result = validateExportSelection(keys);
    expect(result.ok).toBe(false);
    expect(result.errors[0]).toContain(`最多选中 ${MAX_SELECTED_KEYS} 条`);
  });
});
