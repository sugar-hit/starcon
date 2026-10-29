import { describe, expect, it } from 'vitest';
import {
  amountText,
  availableCalipers,
  baselineLabel,
  baselineScopeLabel,
  baselineTone,
  bucketText,
  bucketValueText,
  caliberFormulaText,
  caliberOptionLabel,
  countText,
  currencyTotalsNote,
  currencyTotalsText,
  currentPeriodText,
  emptyText,
  granularityLabel,
  isOrgScope,
  methodLabel,
  metricAvailable,
  metricCalipers,
  ownerLabel,
  ownerModuleLabel,
  pageState,
  percentText,
  previewReconciliationText,
  previewRemainderText,
  ratioText,
  reconciledLabel,
  reconciledTone,
  scopeLabel,
  statusLabel,
  statusTone,
  targetValueText,
  timezoneText,
  unitLabel,
  validateTargetInput,
} from './target-setting-model';
import type {
  TargetBaselineView,
  TargetBucketView,
  TargetCaliberView,
  TargetCurrentPeriod,
  TargetDecompositionPreview,
  TargetInput,
  TargetListResponse,
  TargetMetricView,
  TargetView,
} from './target-setting-model';

/**
 * 13.1.2.1 目标设定 前端模型单测。
 *
 * <p>测的是**这一页最容易做丢的五件事**：
 * 「算不出来」≠「等于 0」、口径唯一（三个并列销售额口径 / 未登记就如实说）、
 * 多币种不相加、分解合计精确等于目标值、页面状态要把「读不到」与「没有目标」分开。
 */

function caliber(overrides: Partial<TargetCaliberView> = {}): TargetCaliberView {
  return {
    metricCode: 'STORE_GMV_PLATFORM',
    displayName: '平台侧成交金额',
    unit: 'CURRENCY',
    aggregation: 'SUM',
    caliberNote: '平台口径',
    ownerModule: '9.1.1.1',
    ...overrides,
  };
}

function metric(overrides: Partial<TargetMetricView> = {}): TargetMetricView {
  return {
    key: 'target.sales',
    displayName: '销售额',
    unit: 'CURRENCY',
    ownerModule: '9.1.1.1',
    scopeType: 'SHOP',
    available: true,
    calipers: [caliber()],
    unavailableReason: '',
    note: '',
    ...overrides,
  };
}

function bucket(overrides: Partial<TargetBucketView> = {}): TargetBucketView {
  return {
    bucketStart: '2026-01-01',
    bucketEnd: '2026-01-31',
    granularity: 'MONTHLY',
    currency: 'USD',
    targetValue: 333.3333,
    weight: 1,
    ...overrides,
  };
}

function target(overrides: Partial<TargetView> = {}): TargetView {
  return {
    id: '11111111-1111-1111-1111-111111111111',
    metricCode: 'SALES_AMOUNT',
    metricDisplayName: '销售额',
    unit: 'CURRENCY',
    scopeType: 'DEPARTMENT',
    ownerId: 'dept-1',
    ownerCode: 'SALES',
    ownerName: '销售部',
    currency: 'USD',
    targetValue: 1000,
    baseline: {
      status: 'NO_DATA',
      value: null,
      currency: null,
      from: '2025-01-01',
      to: '2025-03-31',
      scope: 'TENANT',
      note: '没有事实',
    },
    periodStart: '2026-01-01',
    periodEnd: '2026-03-31',
    granularity: 'MONTHLY',
    decompositionMethod: 'EVEN',
    status: 'ACTIVE',
    note: '',
    buckets: [],
    bucketCount: 0,
    bucketSum: 0,
    reconciled: true,
    createdAt: '2026-01-01T00:00:00Z',
    updatedAt: '2026-01-01T00:00:00Z',
    ...overrides,
  };
}

function list(overrides: Partial<TargetListResponse> = {}): TargetListResponse {
  return {
    page: 0,
    size: 50,
    total: 0,
    targets: [],
    currencyTotals: [],
    note: '',
    ...overrides,
  };
}

// ================================================================ 「算不出来」≠「等于 0」

describe('基线与取值的「算不出来」处理', () => {
  it('基线为 null 时显示「—」，不是 0', () => {
    expect(amountText(null, 'USD')).toBe('—');
    expect(amountText(null, null)).toBe('—');
  });

  it('百分比为 null 时显示「不可比」，不是 0%', () => {
    expect(percentText(null)).toBe('不可比');
  });

  it('倍数为 null 时显示「—」，不是 0 倍', () => {
    expect(ratioText(null)).toBe('—');
  });

  it('笔数为 null 时显示「—」，不是 0', () => {
    expect(countText(null)).toBe('—');
  });

  it('按单位分派取值文本（单位来自口径定义，不猜）', () => {
    expect(targetValueText('CURRENCY', 100, 'USD')).toBe('100.00 USD');
    expect(targetValueText('PERCENT', null, null)).toBe('不可比');
    expect(targetValueText('RATIO', 3.2, null)).toBe('3.20×');
    expect(targetValueText('COUNT', 12, null)).toBe('12');
  });

  it('基线状态与维度分开说', () => {
    expect(baselineLabel('AVAILABLE')).toBe('有基线');
    expect(baselineLabel('NO_DATA')).toBe('没有基线');
    expect(baselineTone('AVAILABLE')).toBe('success');
    // 「没有基线」不是错误 ⇒ neutral，不是 danger
    expect(baselineTone('NO_DATA')).toBe('neutral');
    expect(baselineScopeLabel('OWNER')).toBe('归属对象维度');
    expect(baselineScopeLabel('TENANT')).toBe('租户级参考值');
  });
});

// ================================================================ 口径唯一

describe('口径唯一：目标只引用已登记的口径码', () => {
  it('销售额在店铺维度是三个并列口径（不许只挑一个）', () => {
    const sales = metric({
      calipers: [
        caliber({ metricCode: 'STORE_GMV_PLATFORM', displayName: '平台侧成交金额' }),
        caliber({ metricCode: 'STORE_GMV_RESTORED', displayName: '还原口径成交金额' }),
        caliber({ metricCode: 'STORE_CALIBER_GAP', displayName: '口径差异额' }),
      ],
    });
    expect(metricCalipers(sales).map((item) => item.metricCode)).toEqual([
      'STORE_GMV_PLATFORM',
      'STORE_GMV_RESTORED',
      'STORE_CALIBER_GAP',
    ]);
    expect(metricAvailable(sales)).toBe(true);
  });

  it('没有登记口径码的指标就是「不能设」', () => {
    const turnover = metric({
      key: 'target.inventory-turnover',
      displayName: '库存周转',
      available: false,
      calipers: [],
      unavailableReason: '库存周转没有登记口径码',
    });
    expect(metricAvailable(turnover)).toBe(false);
    expect(metricCalipers(turnover)).toHaveLength(0);
    expect(turnover.unavailableReason).toContain('没有登记口径码');
  });

  it('筛选器的候选是**全部可引用的口径码**（摊平 + 去重，未登记的指标不出现）', () => {
    const calipers = availableCalipers([
      metric({
        calipers: [
          caliber({ metricCode: 'STORE_GMV_PLATFORM' }),
          caliber({ metricCode: 'STORE_GMV_RESTORED' }),
        ],
      }),
      // 同一个口径码在两个蓝图指标下出现 ⇒ 只出现一次
      metric({ key: 'target.other', calipers: [caliber({ metricCode: 'STORE_GMV_PLATFORM' })] }),
      // 未登记口径的指标 ⇒ 不进筛选器（没有可引用的口径码）
      metric({
        key: 'target.inventory-turnover',
        available: false,
        calipers: [],
      }),
    ]);
    expect(calipers.map((item) => item.metricCode)).toEqual([
      'STORE_GMV_PLATFORM',
      'STORE_GMV_RESTORED',
    ]);
  });

  it('口径选项把口径码也显示出来（能对着口径表核对）', () => {
    expect(caliberOptionLabel(caliber())).toBe('平台侧成交金额（STORE_GMV_PLATFORM）');
  });

  it('派生型口径说清依赖的是谁', () => {
    expect(caliberFormulaText(caliber({ aggregation: 'DERIVED_RATIO' }))).toContain('派生比');
    expect(caliberFormulaText(caliber({ aggregation: 'DERIVED_DIFF' }))).toContain('派生差');
    expect(caliberFormulaText(caliber({ aggregation: 'SUM' }))).toBe('窗口内相加');
  });

  it('口径归属模块为空时说明是本服务内闭环', () => {
    expect(ownerModuleLabel('')).toBe('本服务内闭环');
    expect(ownerModuleLabel('9.1.1.1')).toBe('9.1.1.1');
  });
});

// ================================================================ 多币种不相加

describe('多币种绝不相加', () => {
  it('合计按币种分列', () => {
    const text = currencyTotalsText([
      { currency: 'USD', total: 1000, count: 2 },
      { currency: 'EUR', total: 500, count: 1 },
    ]);
    expect(text).toContain('USD 1,000.00（2 条）');
    expect(text).toContain('EUR 500.00（1 条）');
  });

  it('一个币种都没有时显示「—」，不是 0', () => {
    expect(currencyTotalsText([])).toBe('—');
  });

  it('多币种时提醒没有跨币种相加', () => {
    const note = currencyTotalsNote([
      { currency: 'USD', total: 1, count: 1 },
      { currency: 'EUR', total: 1, count: 1 },
    ]);
    expect(note).toContain('没有跨币种相加');
  });

  it('单币种时也说明合计口径', () => {
    const note = currencyTotalsNote([{ currency: 'USD', total: 1, count: 1 }]);
    expect(note).toContain('绝不相加');
  });
});

// ================================================================ 分解计划

describe('分解计划：合计精确等于目标值', () => {
  it('分解项与合计都能展示', () => {
    expect(bucketText(bucket())).toBe('2026-01-01 ~ 2026-01-31');
    expect(bucketValueText(bucket())).toBe('333.33 USD');
  });

  it('合计一致时给出确认，不一致时显式报警', () => {
    expect(reconciledLabel(true)).toBe('合计与目标值一致');
    expect(reconciledLabel(false)).toBe('合计与目标值不一致');
    expect(reconciledTone(true)).toBe('success');
    expect(reconciledTone(false)).toBe('danger');
  });

  it('预览的余数归属说明要出现在页面上（不静默差 0.01）', () => {
    const preview: TargetDecompositionPreview = {
      granularity: 'MONTHLY',
      decompositionMethod: 'EVEN',
      currency: 'USD',
      targetValue: 1000,
      buckets: [bucket()],
      bucketSum: 1000,
      reconciled: true,
      remainderNote: '差额（0.0001）归最后一个分解项',
    };
    expect(previewRemainderText(preview)).toContain('差额');
    expect(previewReconciliationText(preview)).toContain('一致');
    expect(previewReconciliationText({ ...preview, reconciled: false }).length).toBeGreaterThan(0);
  });

  it('粒度与分解方式有中文标签', () => {
    expect(granularityLabel('MONTHLY')).toBe('按月分解');
    expect(granularityLabel('WEEKLY')).toBe('按周分解');
    expect(methodLabel('EVEN')).toBe('均分');
    expect(methodLabel('WEIGHTED')).toBe('按权重');
  });
});

// ================================================================ 页面状态

describe('页面状态：读不到 / 没有数据 / 就绪三者分开', () => {
  it('加载中', () => {
    expect(pageState(null, '', true)).toBe('loading');
  });

  it('读不到是 error（不是 empty）', () => {
    expect(pageState(null, '上游挂了', false)).toBe('error');
  });

  it('读到了但一条都没有是 empty', () => {
    expect(pageState(list({ total: 0 }), '', false)).toBe('empty');
  });

  it('有目标是 ready', () => {
    expect(pageState(list({ total: 1 }), '', false)).toBe('ready');
  });

  it('空态说明写清「目标要挂在组织对象上」', () => {
    expect(emptyText(list({ total: 0 }))).toContain('组织对象');
  });
});

// ================================================================ 归属对象与周期

describe('归属对象与周期', () => {
  it('归属对象类型有中文标签', () => {
    expect(scopeLabel('DEPARTMENT')).toBe('部门');
    expect(scopeLabel('MEMBER')).toBe('人员');
    expect(scopeLabel('SHOP')).toBe('店铺');
    expect(scopeLabel('PRODUCT')).toBe('产品');
  });

  it('组织维度（部门 / 人员 / 产品）与店铺维度分开', () => {
    expect(isOrgScope('DEPARTMENT')).toBe(true);
    expect(isOrgScope('MEMBER')).toBe(true);
    expect(isOrgScope('PRODUCT')).toBe(true);
    expect(isOrgScope('SHOP')).toBe(false);
  });

  it('归属对象显示快照（编码 + 显示名）', () => {
    expect(ownerLabel(target())).toBe('销售部（SALES）');
    expect(ownerLabel(target({ ownerName: '', ownerCode: '' }))).toBe('dept-1');
  });

  it('周期边界按本租户时区算，时区回退要说清楚', () => {
    const period: TargetCurrentPeriod = {
      granularity: 'MONTHLY',
      from: '2026-01-01',
      to: '2026-01-31',
      asOfDate: '2026-01-15',
      timezone: 'Asia/Shanghai',
      timezoneSource: 'PROJECTION',
      note: '',
    };
    expect(currentPeriodText(period)).toBe('2026-01-01 ~ 2026-01-31');
    expect(timezoneText(period)).toContain('Asia/Shanghai');
    expect(timezoneText(period)).not.toContain('暂按 UTC');

    const fallback: TargetCurrentPeriod = { ...period, timezone: 'UTC', timezoneSource: 'FALLBACK_UTC' };
    expect(timezoneText(fallback)).toContain('暂按 UTC');
  });

  it('目标状态有语义色与中文标签', () => {
    expect(statusLabel('ACTIVE')).toBe('生效中');
    expect(statusLabel('ARCHIVED')).toBe('已归档');
    expect(statusTone('ACTIVE')).toBe('primary');
    expect(statusTone('ARCHIVED')).toBe('neutral');
  });
});

// ================================================================ 写入前校验

describe('写入前的本地校验（不代替服务端校验）', () => {
  const base: TargetInput = {
    metricCode: 'SALES_AMOUNT',
    scopeType: 'DEPARTMENT',
    ownerId: 'dept-1',
    currency: 'USD',
    targetValue: 1000,
    periodStart: '2026-01-01',
    periodEnd: '2026-03-31',
    granularity: 'MONTHLY',
    decompositionMethod: 'EVEN',
    weights: null,
    status: null,
    note: null,
  };

  it('正确的入参没有问题', () => {
    expect(validateTargetInput(base)).toEqual([]);
  });

  it('归属对象必须有（目标要挂在组织对象上）', () => {
    expect(validateTargetInput({ ...base, ownerId: '' }).join()).toContain('归属对象');
  });

  it('币种必须是 3 位大写字母', () => {
    expect(validateTargetInput({ ...base, currency: 'usd' }).join()).toContain('币种');
  });

  it('周期起点必须晚于终点时报错', () => {
    expect(validateTargetInput({ ...base, periodStart: '2026-03-01', periodEnd: '2026-01-01' }).join()).toContain(
      '不能晚于',
    );
  });

  it('按月分解时起点必须是 1 日、终点必须是月末日', () => {
    expect(validateTargetInput({ ...base, periodStart: '2026-01-15' }).join()).toContain('1 日');
    expect(validateTargetInput({ ...base, periodEnd: '2026-03-30' }).join()).toContain('最后一天');
  });

  it('按周分解时起点必须是周一、终点必须是周日', () => {
    expect(
      validateTargetInput({
        ...base,
        granularity: 'WEEKLY',
        periodStart: '2026-01-06',
        periodEnd: '2026-01-18',
      }).join(),
    ).toContain('周一');
    expect(
      validateTargetInput({
        ...base,
        granularity: 'WEEKLY',
        periodStart: '2026-01-05',
        periodEnd: '2026-01-17',
      }).join(),
    ).toContain('周日');
  });

  it('按权重分解必须给权重', () => {
    expect(validateTargetInput({ ...base, decompositionMethod: 'WEIGHTED' }).join()).toContain('权重');
  });
});
