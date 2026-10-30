import { describe, expect, it } from 'vitest';
import {
  aggregationLabel,
  chartTypeLabel,
  dataUpdatedNote,
  dataUpdatedText,
  definitionFormula,
  deliveryStatusLabel,
  deliverySummary,
  dimensionValueText,
  emptyText,
  exportAvailable,
  exportUnavailableReason,
  deliveryAvailable,
  deliveryUnavailableReason,
  isCrossCurrencyTotal,
  listEmptyText,
  measureAvailabilityText,
  measureColumnLabel,
  measureValueLines,
  measureValueText,
  nextRunText,
  pageState,
  rowDimensionTexts,
  summaryText,
  visibilityLabel,
} from './custom-report-model';
import type {
  CustomReportCapabilities,
  CustomReportCurrencyValue,
  CustomReportDeliveryView,
  CustomReportMeasureCell,
  CustomReportMetricView,
  CustomReportPage,
  CustomReportRow,
  CustomReportRunResponse,
  CustomReportSubscriptionView,
  CustomReportSummary,
} from './custom-report-model';

/**
 * 13.6.1.1 自定义报表 前端模型。
 *
 * 🔴 这些断言盯的是**页面最容易说错的几件事**：
 *
 * 1. **「算不出来」≠「数据是 0」**。服务端给 `null` 的取值必须渲染成「—」并**带上原因**，
 *    **不是** 0% / 0.00。分母为 0 的比率显示成 0% 会被读成「效率为零」。
 * 2. **多币种绝不相加**。涉币指标按币种分行；与货币无关的量才给一个空串币种的合计。
 * 3. **「未交付」就写未交付**。导出与实际投递属 13.6.2.1，能力声明说 false 就必须显示 false。
 * 4. **前端不做业务算术**。派生值、比率全部来自服务端，这里只做格式化。
 * 5. **「读不到」与「没有数据」分开**。
 */

function currencyValue(
  overrides: Partial<CustomReportCurrencyValue> = {},
): CustomReportCurrencyValue {
  return {
    currency: 'USD',
    value: 100,
    availability: 'AVAILABLE',
    availabilityNote: '',
    ...overrides,
  };
}

function measure(overrides: Partial<CustomReportMeasureCell> = {}): CustomReportMeasureCell {
  return {
    metricCode: 'SALES_AMOUNT',
    displayName: '销售额',
    unit: 'CURRENCY',
    currencyAware: true,
    values: [currencyValue()],
    availability: 'AVAILABLE',
    availabilityNote: '',
    ...overrides,
  };
}

function metricView(overrides: Partial<CustomReportMetricView> = {}): CustomReportMetricView {
  return {
    metricCode: 'SALES_AMOUNT',
    displayName: '销售额',
    unit: 'CURRENCY',
    aggregation: 'SUM',
    numeratorMetricCode: null,
    denominatorMetricCode: null,
    currencyAware: true,
    description: '口径说明',
    ownerModule: '9.1.1.1',
    ...overrides,
  };
}

function runResponse(overrides: Partial<CustomReportRunResponse> = {}): CustomReportRunResponse {
  return {
    reportId: 'r-1',
    reportCode: 'R-1',
    displayName: '销售报表',
    description: '',
    chartType: 'TABLE',
    from: '2026-10-01',
    to: '2026-10-02',
    dimensions: [],
    measures: [metricView()],
    rows: [],
    total: 0,
    page: 0,
    size: 50,
    caliberNotes: [],
    dataUpdatedAt: '2026-10-03T01:02:03Z',
    dataUpdatedNote: '最近一次投影落库时刻',
    status: 'AVAILABLE',
    statusNote: '',
    emptyReason: null,
    emptyNote: '',
    ...overrides,
  };
}

function capabilities(overrides: Partial<CustomReportCapabilities> = {}): CustomReportCapabilities {
  return {
    upstreamConnected: true,
    reportAvailable: true,
    subscriptionAvailable: true,
    exportAvailable: false,
    exportOwnerModule: '13.6.2.1 导出与订阅',
    exportUnavailableReason: '大文件导出未交付',
    deliveryAvailable: false,
    deliveryOwnerModule: '13.6.2.1 导出与订阅',
    deliveryUnavailableReason: '实际投递未交付',
    upstreamModules: [],
    notDelivered: [],
    rules: [],
    ...overrides,
  };
}

describe('13.6.1.1 自定义报表 前端模型', () => {
  describe('取值展示', () => {
    it('金额带币种', () => {
      expect(measureValueText(currencyValue({ value: 1234.5 }), 'CURRENCY')).toBe('1,234.50 USD');
    });

    it('百分比带百分号', () => {
      expect(measureValueText(currencyValue({ value: 46.6667 }), 'PERCENT')).toBe('46.67%');
    });

    it('数量保留三位', () => {
      expect(measureValueText(currencyValue({ currency: '', value: 12.5 }), 'QUANTITY')).toBe(
        '12.500',
      );
    });

    it('计数十位', () => {
      expect(measureValueText(currencyValue({ currency: '', value: 7 }), 'COUNT')).toBe('7');
    });

    it('与币种无关的合计不显示币种', () => {
      const value = currencyValue({ currency: '', value: 6 });
      expect(measureValueText(value, 'COUNT')).toBe('6');
      expect(isCrossCurrencyTotal(value)).toBe(true);
      expect(isCrossCurrencyTotal(currencyValue())).toBe(false);
    });

    it('算不出来显示破折号而不是 0', () => {
      const value = currencyValue({ value: null, availability: 'NOT_COMPARABLE' });
      expect(measureValueText(value, 'PERCENT')).toBe('—');
      expect(measureValueText(value, 'PERCENT')).not.toBe('0.00%');
    });
  });

  describe('可得性', () => {
    it('不可比要说清原因', () => {
      const cell = measure({
        availability: 'NOT_COMPARABLE',
        availabilityNote: '分母为 0 或缺数据 ⇒ 算不出来（NULL），不是 0',
        values: [currencyValue({ value: null, availability: 'NOT_COMPARABLE' })],
      });
      expect(measureAvailabilityText(cell)).toContain('分母为 0');
    });

    it('没有事实数据也要说清不是 0', () => {
      const cell = measure({ availability: 'NO_DATA', values: [] });
      expect(measureAvailabilityText(cell)).toContain('不是 0');
    });

    it('可用时没有多余说明', () => {
      expect(measureAvailabilityText(measure())).toBe('');
    });
  });

  describe('多币种', () => {
    it('多币种分行显示不相加', () => {
      const cell = measure({
        values: [
          currencyValue({ currency: 'EUR', value: 80 }),
          currencyValue({ currency: 'USD', value: 50 }),
        ],
      });
      expect(measureValueLines(cell)).toEqual(['80.00 EUR', '50.00 USD']);
    });
  });

  describe('列头与口径', () => {
    it('列头带单位', () => {
      expect(measureColumnLabel(metricView({ unit: 'CURRENCY' }))).toBe('销售额（金额）');
      expect(measureColumnLabel(metricView({ unit: 'PERCENT' }))).toBe('销售额（%）');
    });

    it('派生型口径写清分子分母', () => {
      const definition = metricView({
        aggregation: 'DERIVED_RATIO',
        numeratorMetricCode: 'GROSS_PROFIT',
        denominatorMetricCode: 'SALES_AMOUNT',
      });
      expect(definitionFormula(definition)).toBe('GROSS_PROFIT ÷ SALES_AMOUNT');
      expect(aggregationLabel('DERIVED_RATIO')).toBe('派生比（分子 ÷ 分母）');
      expect(aggregationLabel('LAST')).toBe('取最近一天的快照');
    });
  });

  describe('维度', () => {
    it('空值显示为不适用', () => {
      expect(dimensionValueText('', 'UUID')).toBe('—（不适用）');
      expect(dimensionValueText('SHOP', 'TEXT')).toBe('SHOP');
    });

    it('一行按列顺序给出维度取值', () => {
      const row: CustomReportRow = {
        dimensions: [
          { dimensionCode: 'SHOP', label: '店铺（uuid）', value: 'shop-1' },
          { dimensionCode: 'LEGAL_ENTITY', label: '法人（uuid）', value: '' },
        ],
        measures: [],
      };
      expect(rowDimensionTexts(row)).toEqual(['shop-1', '—（不适用）']);
    });
  });

  describe('能力声明', () => {
    it('导出与投递未交付就说未交付', () => {
      const caps = capabilities();
      expect(exportAvailable(caps)).toBe(false);
      expect(exportUnavailableReason(caps)).toContain('13.6.2.1');
      expect(deliveryAvailable(caps)).toBe(false);
      expect(deliveryUnavailableReason(caps)).toContain('13.6.2.1');
    });

    it('没有能力声明时按未交付处理', () => {
      expect(exportAvailable(null)).toBe(false);
      expect(deliveryAvailable(null)).toBe(false);
    });
  });

  describe('报表清单', () => {
    it('可见范围与图表类型有中文标签', () => {
      expect(visibilityLabel('TEAM')).toBe('团队可见');
      expect(visibilityLabel('PRIVATE')).toBe('仅自己可见');
      expect(chartTypeLabel('PIE')).toBe('饼图');
    });

    it('摘要含度量与维度个数', () => {
      const summary: CustomReportSummary = {
        reportId: 'r-1',
        reportCode: 'R-1',
        displayName: '销售报表',
        description: '',
        visibility: 'TEAM',
        chartType: 'BAR',
        measureCount: 2,
        dimensionCount: 1,
        ownerUserId: 'u-1',
        ownedByMe: true,
        updatedAt: '2026-10-29T00:00:00Z',
      };
      expect(summaryText(summary)).toBe('2 个度量 · 1 个维度 · 柱状图');
    });

    it('空态区分筛选无结果与还没建过', () => {
      const base: CustomReportPage = {
        rows: [],
        total: 0,
        page: 0,
        size: 20,
        keyword: '',
        visibilityFilter: '',
      };
      expect(listEmptyText(base)).toContain('还没有建过报表');
      expect(listEmptyText({ ...base, keyword: '毛利' })).toContain('没有报表匹配当前筛选条件');
    });
  });

  describe('订阅与投递', () => {
    it('待投递不是已发送', () => {
      expect(deliveryStatusLabel('PENDING')).toBe('待投递');
      expect(deliveryStatusLabel('DELIVERED')).toBe('已投递');
    });

    it('投递摘要带失败原因', () => {
      const delivery: CustomReportDeliveryView = {
        deliveryId: 'd-1',
        reportId: 'r-1',
        reportCode: 'R-1',
        subscriptionId: 's-1',
        scheduledFor: '2026-10-29T09:00:00Z',
        status: 'FAILED',
        attemptCount: 2,
        lastError: '收件人无效',
        createdAt: '2026-10-29T09:00:00Z',
      };
      expect(deliverySummary(delivery)).toContain('收件人无效');
    });

    it('停用的订阅不说下一次执行', () => {
      const subscription: CustomReportSubscriptionView = {
        subscriptionId: 's-1',
        reportId: 'r-1',
        reportCode: 'R-1',
        reportName: '销售报表',
        cronExpression: '0 9 * * *',
        channel: 'EMAIL',
        target: 'ops@example.com',
        enabled: false,
        nextRunAt: '2026-10-30T09:00:00Z',
        lastEnqueuedAt: null,
        createdAt: '2026-10-29T00:00:00Z',
      };
      expect(nextRunText(subscription)).toBe('已停用');
      expect(nextRunText({ ...subscription, enabled: true })).toContain('下一次执行');
    });
  });

  describe('数据更新时间', () => {
    it('没有投影时也给一句话', () => {
      expect(dataUpdatedText(runResponse({ dataUpdatedAt: null }))).toContain('尚未投影任何数据');
      expect(dataUpdatedText(runResponse())).toContain('数据截至');
      expect(dataUpdatedNote(runResponse())).toContain('投影落库时刻');
    });
  });

  describe('页面状态', () => {
    it('读不到是错误态', () => {
      expect(pageState(null, '服务不可用')).toBe('error');
    });

    it('还没读到是加载态', () => {
      expect(pageState(null, '')).toBe('loading');
    });

    it('没有事实是空态', () => {
      expect(pageState(runResponse({ status: 'NO_DATA' }), '')).toBe('empty');
      expect(emptyText(runResponse({ status: 'NO_DATA', emptyReason: 'NO_FACTS' }))).toContain(
        '不是 0',
      );
    });

    it('有数据是就绪态', () => {
      expect(pageState(runResponse(), '')).toBe('ready');
    });
  });
});
