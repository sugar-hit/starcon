import { describe, expect, it } from 'vitest';
import {
  buildInventoryRiskList,
  classifyRisks,
  describeRiskRow,
  divideFloor,
  formatQuantity,
  selectCurrentVelocities,
  summarizeRiskList,
  thresholdScopeLabel,
  toScaledDecimal,
  type StockProjectionRow,
  type VelocityRow,
} from './inventory-risk-model';

/**
 * 1.1.3.1 库存风险的前端聚合模型单测。
 *
 * <p>重点覆盖三件最容易做错的事：
 *
 * <ol>
 *   <li>「没配置阈值」不是「阈值 = 0」——没配 {@code min_on_hand} 的行**不参与缺货判定**；
 *   <li>「没登记销售速度」不是「日均销 = 0」——可用天数是**不可判定**（null）；
 *   <li>除法不碰浮点——{@code 0.3 ÷ 0.1} 必须得 3 而不是 2。
 * </ol>
 */

/** 造一行投影。默认是一行「什么风险都没有」的干净行，用例只覆盖自己关心的字段。 */
function projection(overrides: Partial<StockProjectionRow> = {}): StockProjectionRow {
  return {
    warehouseId: 'w-1',
    warehouseCode: 'WH-LOCAL',
    warehouseName: '本地仓',
    warehouseKind: 'LOCAL',
    warehouseKindLabel: '本地仓',
    skuId: 's-1',
    skuCode: 'SKU-001',
    skuName: '测试商品',
    onHand: 100,
    reserved: 0,
    available: 100,
    sellableBeforeBuffer: 100,
    bucketCount: 1,
    locationCount: 1,
    lotCount: 0,
    inboundDate: '2026-01-01',
    agingDays: 10,
    agingBasis: 'LOT_RECEIVED_ON_OR_BUCKET_CREATED_AT',
    lastMovementAt: '2026-10-20T00:00:00Z',
    minOnHand: null,
    maxOnHand: null,
    reorderPoint: null,
    safetyStock: null,
    thresholdScopeType: 'NONE',
    thresholdScopeKey: '',
    obsoleteDays: 90,
    obsoleteThresholdSource: 'DEFAULT',
    activeReservedQuantity: 0,
    activeReservationCount: 0,
    inTransitQuantity: 0,
    inTransitFirstMileQuantity: 0,
    inTransitPurchaseQuantity: 0,
    inTransitTransferQuantity: 0,
    inTransitLegCount: 0,
    ...overrides,
  };
}

/** 造一条销售速度登记。 */
function velocity(overrides: Partial<VelocityRow> = {}): VelocityRow {
  return {
    id: 'v-1',
    skuId: 's-1',
    skuCode: 'SKU-001',
    skuName: '测试商品',
    dailySalesQuantity: 10,
    windowStart: '2026-09-01',
    windowEnd: '2026-09-30',
    effectiveFrom: '2026-10-01',
    effectiveTo: null,
    sourceKind: 'MANUAL',
    sourceKindLabel: '人工登记',
    sourceRef: '',
    note: '',
    createdAt: '2026-10-01T00:00:00Z',
    effective: true,
    ...overrides,
  };
}

describe('精确十进制', () => {
  it('0.3 ÷ 0.1 得 3 而不是 2（浮点会算成 2.9999999999999996）', () => {
    expect(divideFloor(0.3, 0.1)).toBe(3);
  });

  it('向下取整为整数天（10 ÷ 3 = 3 天，不是 3.33 天）', () => {
    expect(divideFloor(10, 3)).toBe(3);
  });

  it('分母为 0 时不可判定，既不是 0 也不是无穷大', () => {
    expect(divideFloor(100, 0)).toBeNull();
  });

  it('分母缺失时同样不可判定', () => {
    expect(divideFloor(100, null)).toBeNull();
    expect(divideFloor(null, 10)).toBeNull();
  });

  it('缩放整数按 6 位小数（与 numeric(19,6) 同精度）', () => {
    expect(toScaledDecimal(1)).toBe(1000000n);
    expect(toScaledDecimal(0.5)).toBe(500000n);
    expect(toScaledDecimal('12.500000')).toBe(12500000n);
    expect(toScaledDecimal(null)).toBeNull();
  });

  it('数量格式化去掉多余尾随零', () => {
    expect(formatQuantity(3000)).toBe('3000');
    expect(formatQuantity(12.5)).toBe('12.5');
    expect(formatQuantity(null)).toBe('—');
  });
});

describe('销售速度的当前生效判定', () => {
  it('只取生效期覆盖评估日的那一条', () => {
    const current = selectCurrentVelocities(
      [
        velocity({ id: 'old', effectiveFrom: '2026-09-01', effectiveTo: '2026-10-01' }),
        velocity({ id: 'now', effectiveFrom: '2026-10-01', effectiveTo: null }),
      ],
      '2026-10-15',
    );
    expect(current.get('s-1')?.id).toBe('now');
  });

  it('未来生效的登记不算当前（否则会拿还没生效的日均销算天数）', () => {
    const current = selectCurrentVelocities(
      [velocity({ id: 'future', effectiveFrom: '2026-11-01', effectiveTo: null })],
      '2026-10-15',
    );
    expect(current.size).toBe(0);
  });

  it('已收口的登记不算当前', () => {
    const current = selectCurrentVelocities(
      [velocity({ id: 'closed', effectiveFrom: '2026-09-01', effectiveTo: '2026-10-01' })],
      '2026-10-15',
    );
    expect(current.size).toBe(0);
  });
});

describe('四类风险判定', () => {
  it('缺货：可用量低于最低保有量', () => {
    expect(classifyRisks(projection({ available: 5, minOnHand: 10 }))).toContain('STOCKOUT');
  });

  it('未配置最低保有量时不判缺货（不是「阈值 = 0」）', () => {
    const risks = classifyRisks(projection({ available: 0, minOnHand: null }));
    expect(risks).not.toContain('STOCKOUT');
  });

  it('可用量等于最低保有量时不判缺货（阈值是「低于」不是「不高于」）', () => {
    expect(classifyRisks(projection({ available: 10, minOnHand: 10 }))).not.toContain('STOCKOUT');
  });

  it('超卖：活跃预占未了结量超过可用量', () => {
    expect(classifyRisks(projection({ available: 10, activeReservedQuantity: 11 }))).toContain(
      'OVERSELL',
    );
  });

  it('活跃预占等于可用量时不判超卖', () => {
    expect(classifyRisks(projection({ available: 10, activeReservedQuantity: 10 }))).not.toContain(
      'OVERSELL',
    );
  });

  it('呆滞：库龄达到呆滞线', () => {
    expect(classifyRisks(projection({ agingDays: 90, obsoleteDays: 90 }))).toContain('SLOW_MOVING');
    expect(classifyRisks(projection({ agingDays: 89, obsoleteDays: 90 }))).not.toContain(
      'SLOW_MOVING',
    );
  });

  it('超储：在手量高于最高保有量', () => {
    expect(classifyRisks(projection({ onHand: 101, maxOnHand: 100 }))).toContain('OVERSTOCK');
    expect(classifyRisks(projection({ onHand: 100, maxOnHand: 100 }))).not.toContain('OVERSTOCK');
  });

  it('未配置最高保有量时不判超储', () => {
    expect(classifyRisks(projection({ onHand: 9999, maxOnHand: null }))).not.toContain('OVERSTOCK');
  });

  it('一行可以同时命中多类风险', () => {
    const risks = classifyRisks(
      projection({ available: 1, minOnHand: 10, activeReservedQuantity: 50, agingDays: 200 }),
    );
    expect(risks).toContain('STOCKOUT');
    expect(risks).toContain('OVERSELL');
    expect(risks).toContain('SLOW_MOVING');
  });
});

describe('风险清单聚合', () => {
  it('没登记销售速度时可用天数不可判定（不是 0）', () => {
    const rows = buildInventoryRiskList([projection()], [], '2026-10-15');
    expect(rows[0]?.coverageStatus).toBe('NO_VELOCITY');
    expect(rows[0]?.coverageDays).toBeNull();
    expect(rows[0]?.dailySales).toBeNull();
  });

  it('登记的日均销为 0 时同样不可判定（除法没有意义）', () => {
    const rows = buildInventoryRiskList(
      [projection()],
      [velocity({ dailySalesQuantity: 0 })],
      '2026-10-15',
    );
    expect(rows[0]?.coverageStatus).toBe('ZERO_VELOCITY');
    expect(rows[0]?.coverageDays).toBeNull();
  });

  it('有日均销时给出可用天数', () => {
    const rows = buildInventoryRiskList(
      [projection({ available: 100 })],
      [velocity({ dailySalesQuantity: 10 })],
      '2026-10-15',
    );
    expect(rows[0]?.coverageStatus).toBe('OK');
    expect(rows[0]?.coverageDays).toBe(10);
  });

  it('缺货且有在途时建议催在途而不是直接生成采购单', () => {
    const rows = buildInventoryRiskList(
      [projection({ available: 1, minOnHand: 10, inTransitPurchaseQuantity: 50 })],
      [],
      '2026-10-15',
    );
    expect(rows[0]?.suggestedAction).toContain('催在途到货');
  });

  it('缺货且无在途时建议生成采购单', () => {
    const rows = buildInventoryRiskList(
      [projection({ available: 1, minOnHand: 10 })],
      [],
      '2026-10-15',
    );
    expect(rows[0]?.suggestedAction).toContain('生成采购单');
  });

  it('超储且别的仓有货时建议调拨', () => {
    const rows = buildInventoryRiskList(
      [
        projection({ warehouseId: 'w-1', warehouseCode: 'WH-A', onHand: 500, maxOnHand: 100 }),
        projection({ warehouseId: 'w-2', warehouseCode: 'WH-B', onHand: 30, maxOnHand: null }),
      ],
      [],
      '2026-10-15',
    );
    expect(rows[0]?.suggestedAction).toContain('调拨到 WH-B');
  });

  it('汇总按风险类型计数，并单独统计「判不了」的行', () => {
    const rows = buildInventoryRiskList(
      [
        projection({ skuId: 's-1', available: 1, minOnHand: 10, thresholdScopeType: 'SKU' }),
        projection({ skuId: 's-2', warehouseId: 'w-2', available: 100 }),
      ],
      [],
      '2026-10-15',
    );
    const summary = summarizeRiskList(rows);
    expect(summary.rowCount).toBe(2);
    expect(summary.riskRowCount).toBe(1);
    expect(summary.byRisk.STOCKOUT).toBe(1);
    expect(summary.byRisk.OVERSELL).toBe(0);
    expect(summary.noThresholdCount).toBe(1);
    expect(summary.noVelocityCount).toBe(2);
    expect(summary.byWarehouseKind.length).toBeGreaterThan(0);
  });

  it('明细里把「不可判定」写清楚，而不是留空', () => {
    const rows = buildInventoryRiskList([projection()], [], '2026-10-15');
    const lines = describeRiskRow(rows[0]!);
    expect(lines.join('\n')).toContain('不可判定');
    expect(lines.join('\n')).toContain('未配置');
  });

  it('阈值档位有中文标签', () => {
    expect(thresholdScopeLabel('SKU')).toBe('SKU 档');
    expect(thresholdScopeLabel('NONE')).toBe('未配置');
  });
});
