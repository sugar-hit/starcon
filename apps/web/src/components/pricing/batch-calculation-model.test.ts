import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';

import {
  BATCH_SECTION,
  batchExportUrl,
  buildResultQuery,
  decisionLabel,
  emptyFilters,
  emptyRequest,
  filterSummary,
  flagLabel,
  isProblem,
  moneyText,
  moneyWithCurrency,
  percentText,
  priceSourceLabel,
  quantityText,
  rowTone,
  statusLabel,
  summaryStats,
  validateRequest,
} from './batch-calculation-model';
import type {
  BatchCalculationRequest,
  BatchResultFilters,
  BatchResultRow,
  BatchSummary,
} from './batch-calculation-model';

/**
 * 5.3.1.2 批量测算前端模型的纯函数断言。
 *
 * <p>这些函数守着蓝图那句「标记亏损品与低毛利品，支持导出并筛选（毛利率 &lt; X%、售价 &lt; Y）」
 * 的技术落点，以及它旁边那条隐含的纪律：
 * **「没测准」与「测出来是亏的」是两件事**。
 *
 * <p>🔴 本文件**不断言任何「未交付」字面量**：兄弟模块一旦交付，那个字面量就会消失，
 * 写死会让别的车道一交付就红（本仓 2026-10-20 踩过这个坑，害得整批 L2 返工一轮）。
 * 分区的标题与字段改为**从分区描述符本身断言** —— 组件也从同一个描述符渲染。
 */

function row(overrides: Partial<BatchResultRow> = {}): BatchResultRow {
  return {
    id: 'r-1',
    runId: 'run-1',
    skuId: 'sku-1',
    skuCode: 'SKU-1',
    skuName: '商品一',
    category: '家居',
    brand: 'Acme',
    status: 'CALCULATED',
    skipReason: '',
    unitPrice: 100,
    priceSource: 'INTERNAL_PRICE',
    priceObservedAt: '2026-10-20T00:00Z',
    currency: 'USD',
    knownCostTotal: 16,
    platformFeeAmount: 15,
    logisticsAmount: 5,
    totalDeductions: 36,
    grossProfit: 64,
    grossMarginRate: 0.64,
    breakEvenPrice: 24.7059,
    decisionBasis: 'COMPLETE_FROM_MANUAL_ASSUMPTIONS',
    decisionReady: true,
    loss: false,
    lowMargin: false,
    blockingGaps: [],
    ...overrides,
  };
}

function summary(overrides: Partial<BatchSummary> = {}): BatchSummary {
  return {
    total: 3,
    calculated: 2,
    skippedNoPrice: 1,
    notReady: 0,
    loss: 1,
    lowMargin: 1,
    problem: 2,
    avgGrossMarginRate: 0.325,
    totalUnitPrice: 135,
    totalGrossProfit: 51.5,
    ...overrides,
  };
}

describe('分区描述符（组件与测试同源）', () => {
  it('分区有自己的标题与说明', () => {
    expect(BATCH_SECTION.title.length).toBeGreaterThan(0);
    expect(BATCH_SECTION.description.length).toBeGreaterThan(0);
    expect(BATCH_SECTION.key.length).toBeGreaterThan(0);
  });

  it('分区字段覆盖范围、参数与阈值', () => {
    const ids = BATCH_SECTION.fields.map((field) => field.id);
    // 范围三维度：分类 / 品牌 / 店铺（平台 + 站点）。
    expect(ids).toContain('category');
    expect(ids).toContain('brand');
    expect(ids).toContain('siteCode');
    // 测算参数与阈值。
    expect(ids).toContain('platformFeeRate');
    expect(ids).toContain('logisticsCostPerUnit');
    expect(ids).toContain('lowMarginThreshold');
    // 每个字段都要有标签与提示（页面上不能出现没有说明的输入框）。
    for (const field of BATCH_SECTION.fields) {
      expect(field.label.length, `字段 ${field.id} 缺标签`).toBeGreaterThan(0);
      expect(field.hint.length, `字段 ${field.id} 缺提示`).toBeGreaterThan(0);
    }
    // 字段 id 不重复（重复的 id 会让表单状态串台）。
    expect(new Set(ids).size).toBe(ids.length);
  });

  it('分区组件确实从描述符渲染标题与说明', () => {
    const source = readFileSync(
      fileURLToPath(new URL('./batch-calculation-section.tsx', import.meta.url)),
      'utf8',
    );
    expect(source).toContain('BATCH_SECTION.title');
    expect(source).toContain('BATCH_SECTION.description');
  });
});

describe('金额与毛利率渲染', () => {
  it('取不到不是 0.00', () => {
    // 🔴 渲染成 0.00 的后果：跳过 / 未测准的行会混进清库存名单。
    expect(moneyText(null)).toBe('未计入');
    expect(moneyText(undefined)).toBe('未计入');
    expect(moneyText(0)).toBe('0');
    expect(moneyWithCurrency(null, 'USD')).toBe('未计入');
    expect(moneyWithCurrency(24.7059, 'USD')).toBe('24.7059 USD');
  });

  it('毛利率不可信时不给数字', () => {
    expect(percentText(null)).toBe('不可信（成本不完整）');
    expect(percentText(0.64)).toBe('64%');
    expect(percentText(0.255)).toBe('25.5%');
  });

  it('数量保留 6 位口径', () => {
    expect(quantityText(2)).toBe('2');
    expect(quantityText(1.5)).toBe('1.5');
    expect(quantityText(null)).toBe('—');
  });
});

describe('三种「没有」在页面上必须长得不一样', () => {
  it('跳过（无价格观测）不是 danger', () => {
    // 🔴 跳过的行不是「有问题」，它是「没测」—— 标成 danger 会让待补数据的 SKU 混进异常列表。
    const skipped = row({
      status: 'SKIPPED_NO_PRICE',
      unitPrice: null,
      grossProfit: null,
      grossMarginRate: null,
      decisionReady: false,
      skipReason: '该 SKU 在该店铺没有价格观测',
    });
    expect(rowTone(skipped)).toBe('neutral');
    expect(statusLabel(skipped.status)).toBe('跳过（无价格观测）');
    expect(flagLabel(skipped)).toBe('—');
    expect(isProblem(skipped)).toBe(false);
  });

  it('成本不完整不是低毛利品', () => {
    const notReady = row({ decisionReady: false, grossProfit: null, grossMarginRate: null });
    expect(rowTone(notReady)).toBe('info');
    expect(flagLabel(notReady)).toBe('—');
  });

  it('真的是 0 与取不到分得开', () => {
    const zero = row({ grossProfit: 0, grossMarginRate: 0 });
    expect(moneyText(zero.grossProfit)).toBe('0');
    expect(percentText(zero.grossMarginRate)).toBe('0%');
    expect(rowTone(zero)).toBe('success');
  });
});

describe('异常标记', () => {
  it('亏损品与低毛利品分得开', () => {
    expect(flagLabel(row({ loss: true }))).toBe('亏损品');
    expect(rowTone(row({ loss: true }))).toBe('danger');
    expect(flagLabel(row({ lowMargin: true }))).toBe('低毛利品');
    expect(rowTone(row({ lowMargin: true }))).toBe('warning');
    expect(flagLabel(row())).toBe('—');
  });

  it('售价来源与决策就绪度有名字', () => {
    expect(priceSourceLabel('INTERNAL_PRICE')).toBe('我方价');
    expect(priceSourceLabel('PLATFORM_PRICE')).toBe('平台在售价');
    expect(priceSourceLabel(null)).toBe('—');
    expect(decisionLabel('COMPLETE_FROM_MANUAL_ASSUMPTIONS')).toBe('含手工假设');
  });
});

describe('汇总卡', () => {
  it('问题品合计 = 亏损品 + 低毛利品（互斥，不重复计数）', () => {
    const stats = summaryStats(summary());
    const problem = stats.find((stat) => stat.label === '问题品合计');
    expect(problem?.value).toBe('2');
    expect(stats.find((stat) => stat.label === '亏损品')?.value).toBe('1');
    expect(stats.find((stat) => stat.label === '低毛利品')?.value).toBe('1');
  });

  it('没有汇总时返回空数组而不是占位', () => {
    expect(summaryStats(null)).toEqual([]);
  });

  it('有成本不完整的行时，平均毛利率要写明', () => {
    const stats = summaryStats(summary({ notReady: 1 }));
    const avg = stats.find((stat) => stat.label === '平均毛利率');
    expect(avg?.hint).toContain('成本不完整');
  });
});

describe('输入校验', () => {
  it('必须指定店铺 —— 没有店铺就没有售价', () => {
    const request: BatchCalculationRequest = { ...emptyRequest(), platformCode: 'AMAZON' };
    expect(validateRequest(request)?.field).toBe('siteCode');
  });

  it('跨币种没给汇率时拒绝提交', () => {
    const request: BatchCalculationRequest = {
      ...emptyRequest(),
      platformCode: 'AMAZON',
      siteCode: 'US',
      currency: 'EUR',
      costCurrency: 'USD',
    };
    const error = validateRequest(request);
    expect(error?.field).toBe('fxRate');
    expect(error?.message).toContain('绝不按 1:1 折算');
  });

  it('阈值与费率都是小数口径', () => {
    const base: BatchCalculationRequest = {
      ...emptyRequest(),
      platformCode: 'AMAZON',
      siteCode: 'US',
    };
    expect(validateRequest({ ...base, lowMarginThreshold: 15 })?.field).toBe('lowMarginThreshold');
    expect(validateRequest({ ...base, platformFeeRate: 15 })?.field).toBe('platformFeeRate');
  });

  it('合法的输入通过校验', () => {
    const request: BatchCalculationRequest = {
      ...emptyRequest(),
      platformCode: 'AMAZON',
      siteCode: 'US',
      platformFeeRate: 0.15,
      logisticsCostPerUnit: 5,
      lowMarginThreshold: 0.15,
    };
    expect(validateRequest(request)).toBeNull();
  });
});

describe('筛选与导出', () => {
  it('列表与导出共用同一套筛选拼装', () => {
    const filters: BatchResultFilters = {
      ...emptyFilters(),
      marginBelow: 0.05,
      priceBelow: 30,
      flag: 'LOSS',
    };
    const query = buildResultQuery(filters);
    expect(query).toContain('marginBelow=0.05');
    expect(query).toContain('priceBelow=30');
    expect(query).toContain('flag=LOSS');
    // 导出的地址带同一套筛选参数。
    expect(batchExportUrl('run-1', filters)).toContain('flag=LOSS');
    expect(batchExportUrl('run-1', filters)).toContain('marginBelow=0.05');
  });

  it('没有筛选时只带默认排序', () => {
    // 排序必须**显式**带上：前端默认值与后端默认值写在一起才不会漂。
    expect(buildResultQuery(emptyFilters())).toBe('?sort=MARGIN_ASC');
  });

  it('筛选摘要说得清当前在看哪一批', () => {
    expect(filterSummary(emptyFilters())).toBe('未筛选（全部结果）');
    const filters: BatchResultFilters = { ...emptyFilters(), marginBelow: 0.05, flag: 'LOSS' };
    const summaryText = filterSummary(filters);
    expect(summaryText).toContain('毛利率');
    expect(summaryText).toContain('亏损品');
  });
});
