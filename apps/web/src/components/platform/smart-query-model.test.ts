import { describe, expect, it } from 'vitest';
import {
  aggregationLabel,
  conditionsSummary,
  factLocationLabel,
  factRefLabel,
  filterLabel,
  formatMetric,
  formatPercent,
  formatValue,
  isInternalRoute,
  matchModeLabel,
  matchModeTone,
  measureAvailabilityNote,
  measureCellText,
  operatorLabel,
  periodLabel,
  querySummary,
  toResultView,
  unitLabel,
  valueKindLabel,
  type SmartQueryConditions,
  type SmartQueryEvidence,
  type SmartQueryQueryView,
  type SmartQueryResult,
} from './smart-query-model';

/**
 * 18.4.1.2「智能查询」前端口径单元测试。
 *
 * <p>这些全是**纯函数**（不碰网络、不碰 DOM），测的是「口径对不对」——
 * 查询结果里的数字如果错了，运营照着它做决策就完了，所以口径必须被钉住。
 *
 * <p>🔴 测得最狠的一条是 {@link toResultView}：**没有明细依据的结果不许显示成结果**，
 * 且「解析不出来」与「查到了但没有数据」是**两件事**，不能混为一谈。
 *
 * <p>🔴 另一条是 {@link formatPercent}：13.1.1.1 的 `PERCENT` 口径是**百分数**
 * （25.0000 = 25%），再乘一次 100 就变成了 2500%。
 */

function evidence(overrides: Partial<SmartQueryEvidence> = {}): SmartQueryEvidence {
  return {
    factService: 'reporting',
    factTable: 'reporting_metric_fact',
    factField: 'value',
    factRef: 'metric_code=SALES_AMOUNT; SHOP=1111',
    factLabel: '北美一店 1111',
    metricCode: 'SALES_AMOUNT',
    metricValue: '120.500000',
    metricUnit: 'USD',
    detailRoute: '/analytics/custom-report?reportId=abc',
    detailLabel: '打开这份报表草稿',
    caliberNote: '成交订单的含税成交金额，按订单币种分别统计',
    snapshotAt: '2026-11-01T10:00:00Z',
    ...overrides,
  };
}

function conditions(overrides: Partial<SmartQueryConditions> = {}): SmartQueryConditions {
  return {
    metricCode: 'SALES_AMOUNT',
    metricName: '销售额',
    unit: 'CURRENCY',
    aggregation: 'SUM',
    currencyAware: true,
    dimensionCode: 'SHOP',
    dimensionName: '店铺（uuid）',
    filters: [
      {
        dimensionCode: 'BUCKET_DATE',
        operator: 'BETWEEN',
        valueText: '2026-10-26',
        valueText2: '2026-11-01',
      },
    ],
    sort: { by: 'METRIC', direction: 'DESC' },
    periodCode: 'LAST_WEEK',
    periodFrom: '2026-10-26',
    periodTo: '2026-11-01',
    caliber: '成交订单的含税成交金额',
    ...overrides,
  };
}

function result(overrides: Partial<SmartQueryResult> = {}): SmartQueryResult {
  return {
    queryId: '11111111-1111-1111-1111-111111111111',
    question: '上周各店铺的销售额排名',
    matchMode: 'KEYWORD',
    resolved: true,
    conditions: conditions(),
    reportId: '22222222-2222-2222-2222-222222222222',
    reportCode: 'SQ-ABCDEF0123456789',
    dimensionColumns: [{ dimensionCode: 'SHOP', label: '店铺（uuid）' }],
    metricColumns: [
      {
        metricCode: 'SALES_AMOUNT',
        displayName: '销售额',
        unit: 'CURRENCY',
        currencyAware: true,
        description: '上游登记的口径说明',
      },
    ],
    rows: [
      {
        seq: 1,
        dimensions: [{ dimensionCode: 'SHOP', label: '店铺（uuid）', value: '1111' }],
        measures: [
          {
            metricCode: 'SALES_AMOUNT',
            displayName: '销售额',
            unit: 'CURRENCY',
            currencyAware: true,
            values: [
              { currency: 'USD', value: '120.50', availability: 'AVAILABLE', availabilityNote: '' },
            ],
            availability: 'AVAILABLE',
            availabilityNote: '',
          },
        ],
      },
    ],
    total: 1,
    page: 0,
    size: 200,
    status: 'AVAILABLE',
    statusNote: '',
    dataUpdatedAt: '2026-11-01T10:00:00Z',
    dataUpdatedNote: '最近一次投影落库时刻',
    caliberNotes: ['口径唯一'],
    evidence: [evidence()],
    nextActions: [
      {
        label: '打开这份报表草稿',
        route: '/analytics/custom-report?reportId=abc',
        moduleId: '13.6.1.1',
      },
    ],
    unavailableReason: '',
    ownerModule: '',
    ...overrides,
  };
}

function queryView(overrides: Partial<SmartQueryQueryView> = {}): SmartQueryQueryView {
  return {
    queryId: '11111111-1111-1111-1111-111111111111',
    ownerUserId: '33333333-3333-3333-3333-333333333333',
    question: '上周各店铺的销售额排名',
    displayName: '',
    saved: false,
    matchMode: 'KEYWORD',
    resolved: true,
    metricCode: 'SALES_AMOUNT',
    metricName: '销售额',
    dimensionCode: 'SHOP',
    dimensionName: '店铺（uuid）',
    periodCode: 'LAST_WEEK',
    periodFrom: '2026-10-26',
    periodTo: '2026-11-01',
    reportId: '22222222-2222-2222-2222-222222222222',
    reportCode: 'SQ-ABCDEF0123456789',
    unavailableReason: '',
    ownerModule: '',
    evidenceCount: 3,
    createdAt: '2026-11-01T10:00:00Z',
    updatedAt: '2026-11-01T10:00:00Z',
    ...overrides,
  };
}

describe('formatMetric —— 十进制文本，不转数值', () => {
  it('算不出来显示「—」，绝不显示 0', () => {
    expect(formatMetric(null)).toBe('—');
    expect(formatMetric(undefined)).toBe('—');
    expect(formatMetric('')).toBe('—');
    expect(formatMetric(null, 'USD')).toBe('—');
  });

  it('去掉多余的尾随零，但不动整数', () => {
    expect(formatMetric('120.500000')).toBe('120.5');
    expect(formatMetric('120.50', 'USD')).toBe('120.5 USD');
    expect(formatMetric('120')).toBe('120');
    expect(formatMetric('0.000000')).toBe('0');
  });
});

describe('formatPercent —— 比率已经是百分数，不乘 100', () => {
  it('25.0000 就是 25%，不是 2500%', () => {
    expect(formatPercent('25.0000')).toBe('25%');
    expect(formatPercent('12.3456')).toBe('12.3456%');
  });

  it('算不出来显示「—」', () => {
    expect(formatPercent(null)).toBe('—');
    expect(formatPercent('')).toBe('—');
  });
});

describe('formatValue —— 按单位口径分派', () => {
  it('金额带币种，比率补 %，笔数与数量走十进制', () => {
    expect(formatValue('120.50', 'CURRENCY', 'USD')).toBe('120.5 USD');
    expect(formatValue('25.0000', 'PERCENT')).toBe('25%');
    expect(formatValue('42.000000', 'COUNT')).toBe('42');
    expect(formatValue('12.500000', 'QUANTITY')).toBe('12.5');
  });

  it('算不出来一律「—」', () => {
    expect(formatValue(null, 'CURRENCY', 'USD')).toBe('—');
    expect(formatValue(null, 'PERCENT')).toBe('—');
  });
});

describe('isInternalRoute —— 明细链接必须是站内路由', () => {
  it('协议相对与外链都不算站内明细', () => {
    expect(isInternalRoute('/analytics/custom-report')).toBe(true);
    expect(isInternalRoute('//evil.example.com/x')).toBe(false);
    expect(isInternalRoute('https://evil.example.com/x')).toBe(false);
    expect(isInternalRoute('')).toBe(false);
  });
});

describe('toResultView —— 结构性保证', () => {
  it('解析不出来 ⇒ 没有行、没有依据、说清缺什么与归谁', () => {
    const view = toResultView(
      result({
        resolved: false,
        conditions: null,
        rows: [],
        evidence: [],
        reportId: '',
        reportCode: '',
        unavailableReason: '这句问法没有落到受控语义层的任何一个指标上。',
        ownerModule: '18.4.1.2',
      }),
    );
    expect(view.resolved).toBe(false);
    expect(view.rows).toEqual([]);
    expect(view.evidence).toEqual([]);
    expect(view.headline).toContain('受控语义层');
    expect(view.ownerModule).toBe('18.4.1.2');
    expect(view.tone).toBe('warn');
  });

  it('有行却没有明细依据 ⇒ 按「没出结论」处理，不显示数字', () => {
    const view = toResultView(result({ evidence: [] }));
    expect(view.resolved).toBe(false);
    expect(view.rows).toEqual([]);
    expect(view.evidence).toEqual([]);
    expect(view.tone).toBe('danger');
    expect(view.headline).toContain('明细依据');
  });

  it('查到了但一行都没有 ⇒ 是「没有数据」，不是「没解析出来」', () => {
    const view = toResultView(result({ rows: [], evidence: [], total: 0 }));
    expect(view.resolved).toBe(true);
    expect(view.rows).toEqual([]);
    expect(view.headline).toContain('没有数据');
  });

  it('正常结果 ⇒ 行与依据都在，外链依据被剔掉', () => {
    const view = toResultView(
      result({
        evidence: [evidence(), evidence({ detailRoute: 'https://evil.example.com/x' })],
      }),
    );
    expect(view.resolved).toBe(true);
    expect(view.rows).toHaveLength(1);
    expect(view.evidence).toHaveLength(1);
    expect(view.tone).toBe('ok');
  });

  it('还没有提问时给的是空形态，不是空数组异常', () => {
    const view = toResultView(null);
    expect(view.resolved).toBe(false);
    expect(view.rows).toEqual([]);
    expect(view.evidence).toEqual([]);
    expect(view.headline).toBe('还没有提问');
  });
});

describe('measureCellText —— 多币种逐个列出，不跨币种相加', () => {
  it('两个币种就显示两个值', () => {
    const measure = {
      metricCode: 'SALES_AMOUNT',
      displayName: '销售额',
      unit: 'CURRENCY',
      currencyAware: true,
      values: [
        { currency: 'USD', value: '10.00', availability: 'AVAILABLE', availabilityNote: '' },
        { currency: 'EUR', value: '20.00', availability: 'AVAILABLE', availabilityNote: '' },
      ],
      availability: 'AVAILABLE',
      availabilityNote: '',
    };
    expect(measureCellText(measure)).toBe('10 USD / 20 EUR');
  });

  it('没有取值显示「—」', () => {
    const measure = {
      metricCode: 'SALES_AMOUNT',
      displayName: '销售额',
      unit: 'CURRENCY',
      currencyAware: true,
      values: [],
      availability: 'NOT_COMPARABLE',
      availabilityNote: '',
    };
    expect(measureCellText(measure)).toBe('—');
  });
});

describe('measureAvailabilityNote —— 算不出来要说清为什么', () => {
  it('取值的说明优先于度量的说明', () => {
    const measure = {
      metricCode: 'GROSS_MARGIN',
      displayName: '毛利率',
      unit: 'PERCENT',
      currencyAware: true,
      values: [
        {
          currency: 'USD',
          value: null,
          availability: 'NOT_COMPARABLE',
          availabilityNote: '分母为 0，比率算不出来',
        },
      ],
      availability: 'NOT_COMPARABLE',
      availabilityNote: '度量级说明',
    };
    expect(measureAvailabilityNote(measure)).toBe('分母为 0，比率算不出来');
  });

  it('都为空时没有说明', () => {
    const measure = {
      metricCode: 'SALES_AMOUNT',
      displayName: '销售额',
      unit: 'CURRENCY',
      currencyAware: true,
      values: [{ currency: 'USD', value: '1', availability: 'AVAILABLE', availabilityNote: '' }],
      availability: 'AVAILABLE',
      availabilityNote: '',
    };
    expect(measureAvailabilityNote(measure)).toBe('');
  });
});

describe('受控条件的可读摘要', () => {
  it('筛选条件的中文标签', () => {
    expect(
      filterLabel({
        dimensionCode: 'BUCKET_DATE',
        operator: 'BETWEEN',
        valueText: '2026-10-26',
        valueText2: '2026-11-01',
      }),
    ).toBe('BUCKET_DATE 介于 2026-10-26 ~ 2026-11-01');
    expect(
      filterLabel({ dimensionCode: 'CURRENCY', operator: 'EQ', valueText: 'USD', valueText2: '' }),
    ).toBe('CURRENCY 等于 USD');
  });

  it('摘要里带着指标、维度、聚合、窗口与排序', () => {
    const summary = conditionsSummary(conditions());
    expect(summary).toContain('SALES_AMOUNT');
    expect(summary).toContain('SHOP');
    expect(summary).toContain('求和');
    expect(summary).toContain('2026-10-26 ~ 2026-11-01');
    expect(summary).toContain('按指标值');
    expect(summary).toContain('从高到低');
  });

  it('没有维度时说的是「公司口径（不分组）」', () => {
    const summary = conditionsSummary(conditions({ dimensionCode: '', dimensionName: '' }));
    expect(summary).toContain('公司口径（不分组）');
  });

  it('没有条件就是空串', () => {
    expect(conditionsSummary(null)).toBe('');
  });
});

describe('标签函数', () => {
  it('命中方式 / 时间范围 / 单位 / 聚合 / 运算符 / 取值形态', () => {
    expect(matchModeLabel('KEYWORD')).toBe('命中受控词表');
    expect(matchModeLabel('EXPLICIT')).toBe('显式指定指标 / 维度');
    expect(matchModeTone('KEYWORD')).toBe('ok');
    expect(matchModeTone('EXPLICIT')).toBe('warn');
    expect(periodLabel('LAST_WEEK')).toContain('自然周');
    expect(unitLabel('CURRENCY')).toBe('金额');
    expect(aggregationLabel('LAST')).toContain('不能累加');
    expect(operatorLabel('BETWEEN')).toBe('介于');
    expect(valueKindLabel('UUID')).toContain('uuid');
  });
});

describe('台账摘要与事实定位', () => {
  it('解析成功时给出指标 / 维度 / 窗口', () => {
    expect(querySummary(queryView())).toContain('销售额');
    expect(querySummary(queryView())).toContain('店铺（uuid）');
  });

  it('解析失败时如实说没得出结论', () => {
    const summary = querySummary(
      queryView({
        resolved: false,
        unavailableReason: '不支持这个问法',
        metricName: '',
        dimensionName: '',
      }),
    );
    expect(summary).toBe('不支持这个问法');
  });

  it('事实定位带服务 / 真表名 / 真字段名', () => {
    expect(factLocationLabel(evidence())).toBe('reporting · reporting_metric_fact · value');
    expect(factRefLabel(evidence())).toContain('引用');
    expect(factRefLabel(evidence({ factRef: '' }))).toBe('');
  });
});
