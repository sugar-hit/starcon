import { describe, expect, it } from 'vitest';
import type {
  MassProductionCostComparisonRow,
  MassProductionHandoffRow,
  MassProductionSkuFilingRow,
} from '../../../../../packages/api-contracts/src/index';
import {
  describeBomLine,
  describeFilingOutcome,
  describeHandoff,
  describeTargetCostComparison,
  formatTargetVarianceRate,
  massProductionAttributeQuery,
  massProductionBomQuery,
  massProductionPlanQuery,
  massProductionSpuQuery,
} from './mass-production-model';

/**
 * 6.6.2.2 前端模型的纯函数测试。
 *
 * <p>盯住四件最容易出错的事：
 *
 * <ul>
 *   <li><b>不可比不是 0</b>：目标成本取不到 ⇒ 显示「不可比」，不显示 0.00%。</li>
 *   <li><b>「结果未知」不许重试</b>：商品中心的建档端点不接受幂等键，
 *       笼统的「失败」会诱导人点重试，而那可能建出第二个 SKU。</li>
 *   <li><b>「部分完成」可以再提交</b>：但再提交只补报关属性，不建第二个 SKU。</li>
 *   <li><b>交接如实说</b>：量产参数**没有**进到 6.2.1.2 的计算里，
 *       页面不能写成「已带入量产参数」。</li>
 * </ul>
 */

// 🔴 契约里 MassProductionSkuFilingRow 整体带 `| null`（详情里的 skuFiling 可能还没有建档请求），
// 而下面这些都是**确实存在的建档请求** ⇒ 取 NonNullable。
const BASE_ROW: NonNullable<MassProductionSkuFilingRow> = {
  id: 'f-1',
  filingNo: 'SKF-MP-1-abcdef012345',
  planId: 'p-1',
  targetSystem: 'catalog',
  targetEndpoint: 'POST /catalog/products/{id}/skus',
  targetSpuId: 'spu-1',
  targetSpuCode: 'SPU-001',
  targetSpuName: '折叠水杯',
  skuCode: 'SKU-001',
  skuName: '折叠水杯 蓝色',
  barcode: '6901234567890',
  baseUnit: '个',
  skuStatus: 'DRAFT',
  idempotencyKey: 'mp-sku:p-1',
  status: 'PENDING',
  statusLabel: '待提交',
  targetSkuId: null,
  targetSkuCode: '',
  customsHsCode: '',
  customsOriginCountryCode: '',
  customsDeclaredName: '',
  customsDeclaredValueReference: null,
  customsStatutoryUnitCode: '',
  customsPreferentialOriginEligible: false,
  customsPreferenceAgreement: '',
  customsNote: '',
  errorMessage: '',
  submittedAt: null,
  confirmedAt: null,
  attributes: [],
  customsElements: [],
  createdBy: 'u-1',
  createdAt: '2026-10-21T09:00:00Z',
  updatedAt: '2026-10-21T09:00:00Z',
};

const COMPARISON: MassProductionCostComparisonRow = {
  currency: 'USD',
  trialCost: 12,
  targetCost: 12.5,
  varianceAmount: -0.5,
  varianceRate: -0.04,
  comparable: true,
  reason: '差额 = 试产实测成本 − 量产目标成本。',
};

const HANDOFF: MassProductionHandoffRow = {
  id: 'h-1',
  planId: 'p-1',
  targetModule: '6.2.1.2',
  targetEndpoint: 'POST /trade/replenishment-suggestions/batches',
  ownerModule: '6.2.1.2 补货建议',
  available: true,
  massProductionInputApplied: false,
  status: 'SUCCEEDED',
  resultRef: 'RS-20261021-abcd1234',
  resultSummary: '补货建议批次 RS-20261021-abcd1234：建议 0 项',
  errorMessage: '',
  impact: '⚠️ 本次交接是**重算触发**，量产参数**未**进入 6.2.1.2。',
  attemptedAt: '2026-10-21T10:00:00Z',
};

describe('查询串', () => {
  it('量产计划清单的查询串是真筛选', () => {
    expect(massProductionPlanQuery({ status: 'DECIDED', keyword: '  MP-1  ' })).toBe(
      '?status=DECIDED&keyword=MP-1',
    );
    expect(massProductionPlanQuery({ page: 2, size: 20 })).toBe('?page=2&size=20');
    expect(massProductionPlanQuery({})).toBe('');
  });

  it('候选下拉的查询串带编码', () => {
    expect(massProductionBomQuery('BOM 1')).toBe('?keyword=BOM%201');
    expect(massProductionBomQuery('   ')).toBe('');
    expect(massProductionSpuQuery('SPU')).toBe('?keyword=SPU');
    expect(massProductionAttributeQuery('spu-1')).toBe('?spuId=spu-1');
  });
});

describe('成本偏差显示', () => {
  it('比例只做显示格式，null 显示不可比而不是 0.00%', () => {
    expect(formatTargetVarianceRate(-0.04)).toBe('-4.00%');
    expect(formatTargetVarianceRate(null)).toBe('不可比');
    expect(formatTargetVarianceRate(undefined)).toBe('不可比');
  });

  it('不可比时显示不可比加原因', () => {
    const row = describeTargetCostComparison({
      ...COMPARISON,
      comparable: false,
      targetCost: null,
      varianceAmount: null,
      varianceRate: null,
      reason: '量产目标成本**未填**（取不到就是取不到，不是 0）。',
    });
    expect(row.headline).toBe('不可比');
    expect(row.detail).toContain('不是 0');
  });

  it('可比时显示差额与偏差率', () => {
    const row = describeTargetCostComparison(COMPARISON);
    expect(row.headline).toContain('-0.5');
    expect(row.headline).toContain('-4.00%');
  });
});

describe('SKU 建档请求的显示', () => {
  it('已建成不许再提交', () => {
    const row = describeFilingOutcome({
      ...BASE_ROW,
      status: 'CONFIRMED',
      targetSkuId: 'sku-1',
      targetSkuCode: 'SKU-001',
    });
    expect(row.headline).toBe('已建成');
    expect(row.retryAllowed).toBe(false);
    expect(row.detail).toContain('sku-1');
    expect(row.tone).toBe('success');
  });

  it('结果未知是独立的一档且明写不要重试', () => {
    const row = describeFilingOutcome({
      ...BASE_ROW,
      status: 'UNKNOWN',
      errorMessage: '调用商品中心超时或连接中断。',
    });
    expect(row.headline).toContain('结果未知');
    expect(row.headline).toContain('不要重试');
    expect(row.retryAllowed).toBe(false);
    expect(row.detail).toContain('不接受幂等键');
  });

  it('失败可以重试', () => {
    const row = describeFilingOutcome({
      ...BASE_ROW,
      status: 'FAILED',
      errorMessage: '商品中心返回 400：sku code already exists',
    });
    expect(row.headline).toBe('失败');
    expect(row.retryAllowed).toBe(true);
    expect(row.detail).toContain('sku code already exists');
  });

  it('部分完成可以再提交但只补报关属性', () => {
    const row = describeFilingOutcome({
      ...BASE_ROW,
      status: 'PARTIAL',
      targetSkuId: 'sku-1',
      errorMessage: 'SKU 已经建到商品中心了，但报关属性没写进去',
    });
    expect(row.headline).toBe('部分完成');
    expect(row.retryAllowed).toBe(true);
    expect(row.detail).toContain('只补报关属性');
    expect(row.detail).toContain('不会建第二个 SKU');
  });

  it('待提交与已提交', () => {
    expect(describeFilingOutcome(BASE_ROW).headline).toBe('待提交');
    expect(describeFilingOutcome({ ...BASE_ROW, status: 'SUBMITTED' }).retryAllowed).toBe(false);
  });
});

describe('下游交接的显示', () => {
  it('量产参数没有进到计算里时如实说', () => {
    const row = describeHandoff(HANDOFF);
    expect(row.applied).toContain('未');
    expect(row.detail).toContain('重算触发');
    expect(row.tone).toBe('success');
  });

  it('已跳过时给出归管模块', () => {
    const row = describeHandoff({
      ...HANDOFF,
      targetModule: '5.3.1.1',
      ownerModule: '5.3.1.1 目标定价',
      available: false,
      status: 'SKIPPED',
      resultRef: '',
      resultSummary: '',
      impact: '⚠️ 5.3.1.1 目标定价**未交付** ⇒ 本模块**不 mock 算价**。',
    });
    expect(row.headline).toBe('已跳过');
    expect(row.detail).toContain('不 mock 算价');
    expect(row.applied).toContain('未');
  });

  it('交接失败时给出原因', () => {
    const row = describeHandoff({
      ...HANDOFF,
      status: 'FAILED',
      errorMessage: '仓库服务暂时不可达',
    });
    expect(row.headline).toBe('交接失败');
    expect(row.detail).toContain('仓库服务暂时不可达');
    expect(row.tone).toBe('danger');
  });
});

describe('量产 BOM 组件行', () => {
  it('需求量原样显示，不二次计算', () => {
    const text = describeBomLine({
      id: 'l-1',
      planId: 'p-1',
      lineNo: 1,
      skuId: 'mat-1',
      skuCode: 'MAT-001',
      skuName: '杯盖',
      unitQuantity: 2,
      scrapRate: 5,
      requiredQuantity: 2100,
      unit: '个',
      createdAt: '2026-10-21T09:00:00Z',
    });
    expect(text).toContain('MAT-001');
    expect(text).toContain('2100');
    expect(text).toContain('损耗率 5%');
  });
});
