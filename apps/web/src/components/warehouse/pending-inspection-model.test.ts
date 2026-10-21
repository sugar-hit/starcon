import { describe, expect, it } from 'vitest';
import {
  canCancel,
  canDispose,
  canRecordResult,
  canRequestInspection,
  dispositionLabel,
  dispositionStaysInWarehouse,
  formatQuantity,
  formatSignedQuantity,
  inspectionQuantities,
  locationTypeLabel,
  netOnHandDeltaText,
  pendingInspectionStatusLabel,
  pendingInspectionStatusTone,
  qualityRoleLabel,
  validateCreateQuantity,
  validateDispositionTotals,
  validateSellableLimit,
} from './pending-inspection-model';

/**
 * 8.2.2.2 待检处理前端模型的纯函数单测。
 *
 * <p>重点覆盖三条**页面自己也要守住的纪律**（后端会挡，但页面先说清省一次往返）：
 *
 * <ol>
 *   <li>处置必须把这批待检货<b>全部</b>处置掉（各去向之和 == 任务批量）；</li>
 *   <li><b>不合格品不能转可售</b>：转可售的量不得超过质检结论里的可售量
 *       （合格量 + 让步接收量）；</li>
 *   <li>建单批量不得超过库存行的实际在手量。</li>
 * </ol>
 */
describe('8.2.2.2 待检处理前端模型', () => {
  it('状态标签与色调逐个对应，不认识的原样返回', () => {
    expect(pendingInspectionStatusLabel('PENDING')).toBe('待发起质检');
    expect(pendingInspectionStatusLabel('INSPECTION_REQUESTED')).toBe('已发起质检');
    expect(pendingInspectionStatusLabel('RESULT_RECORDED')).toBe('已录入结果');
    expect(pendingInspectionStatusLabel('DISPOSED')).toBe('已处置');
    expect(pendingInspectionStatusLabel('CANCELLED')).toBe('已取消');
    expect(pendingInspectionStatusLabel('WEIRD')).toBe('WEIRD');
    expect(pendingInspectionStatusTone('DISPOSED')).toBe('success');
    expect(pendingInspectionStatusTone('WEIRD')).toBe('neutral');
  });

  it('去向标签写清「离仓」两个字：报废与退货的货不在本仓了', () => {
    expect(dispositionLabel('SELLABLE')).toBe('转可售');
    expect(dispositionLabel('DEFECTIVE')).toBe('转残次');
    expect(dispositionLabel('SCRAP')).toBe('报废（离仓）');
    expect(dispositionLabel('RETURN')).toBe('退货（离仓）');
    // 只有留仓的两个去向需要目标库位。
    expect(dispositionStaysInWarehouse('SELLABLE')).toBe(true);
    expect(dispositionStaysInWarehouse('DEFECTIVE')).toBe(true);
    expect(dispositionStaysInWarehouse('SCRAP')).toBe(false);
    expect(dispositionStaysInWarehouse('RETURN')).toBe(false);
  });

  it('质量角色与库位类型都有中文名', () => {
    expect(qualityRoleLabel('PENDING_INSPECTION')).toBe('待检');
    expect(qualityRoleLabel('SELLABLE')).toBe('可售');
    expect(qualityRoleLabel('DEFECTIVE')).toBe('残次');
    expect(locationTypeLabel('QUARANTINE')).toBe('待检');
    expect(locationTypeLabel('DEFECTIVE')).toBe('残次');
  });

  it('质检三量：可售量 = 合格量 + 让步接收量（让步是「不合格里被批准放行的那部分」）', () => {
    const q = inspectionQuantities({
      qualifiedQuantity: 50,
      unqualifiedQuantity: 10,
      concessionQuantity: 5,
    });
    expect(q.qualified).toBe(50);
    expect(q.unqualified).toBe(10);
    expect(q.concession).toBe(5);
    // 让步接收的那 5 件也是能卖的 —— 页面不能把它算进不可售。
    expect(q.sellable).toBe(55);
  });

  it('质检三量为空时按 0 处理，不出现 NaN', () => {
    const q = inspectionQuantities({
      qualifiedQuantity: null,
      unqualifiedQuantity: null,
      concessionQuantity: null,
    });
    expect(q.sellable).toBe(0);
    expect(q.unqualified).toBe(0);
  });

  it('处置必须把这一批全部处置掉：差多少说清多少', () => {
    const ok = validateDispositionTotals([{ quantity: 40 }, { quantity: 20 }], 60);
    expect(ok.ok).toBe(true);
    expect(ok.diff).toBe(0);

    const short = validateDispositionTotals([{ quantity: 40 }], 60);
    expect(short.ok).toBe(false);
    expect(short.diff).toBe(20);
    expect(short.message).toContain('20');
    expect(short.message).toContain('全部处置掉');

    const over = validateDispositionTotals([{ quantity: 80 }], 60);
    expect(over.ok).toBe(false);
    expect(over.diff).toBe(-20);
    expect(over.message).toContain('多处置了');
  });

  it('不合格品不能转可售：转可售的量超过可售量就被拦下', () => {
    const ok = validateSellableLimit(
      [
        { dispositionType: 'SELLABLE', quantity: 40 },
        { dispositionType: 'SCRAP', quantity: 20 },
      ],
      40,
    );
    expect(ok.ok).toBe(true);
    expect(ok.sellableSum).toBe(40);

    const bad = validateSellableLimit([{ dispositionType: 'SELLABLE', quantity: 60 }], 40);
    expect(bad.ok).toBe(false);
    expect(bad.message).toContain('不合格品不能被转成可售');
  });

  it('建单批量不得超过库存行在手量', () => {
    expect(validateCreateQuantity(10, 60).ok).toBe(true);
    const bad = validateCreateQuantity(61, 60);
    expect(bad.ok).toBe(false);
    expect(bad.message).toContain('实际在手量');
    expect(validateCreateQuantity(0, 60).ok).toBe(false);
  });

  it('动作可用性：处置之后什么都点不动（终态不可逆）', () => {
    expect(canRequestInspection('PENDING')).toBe(true);
    expect(canRequestInspection('INSPECTION_REQUESTED')).toBe(false);
    expect(canRecordResult('INSPECTION_REQUESTED')).toBe(true);
    expect(canRecordResult('PENDING')).toBe(false);
    expect(canDispose('RESULT_RECORDED')).toBe(true);
    expect(canDispose('DISPOSED')).toBe(false);
    expect(canCancel('PENDING')).toBe(true);
    expect(canCancel('INSPECTION_REQUESTED')).toBe(true);
    expect(canCancel('RESULT_RECORDED')).toBe(false);
    expect(canCancel('DISPOSED')).toBe(false);
  });

  it('分录净额的说法：0 不等于「没动」', () => {
    expect(netOnHandDeltaText(0)).toContain('货还在本仓');
    expect(netOnHandDeltaText(-20)).toContain('净离仓');
    expect(formatSignedQuantity(50)).toBe('+50');
    expect(formatSignedQuantity(-10)).toBe('-10');
    expect(formatQuantity(null)).toBe('—');
    expect(formatQuantity(12.5)).toContain('12.5');
  });
});
