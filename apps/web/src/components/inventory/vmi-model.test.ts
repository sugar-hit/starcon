import { describe, expect, it } from 'vitest';

import {
  auditLabel,
  canFulfil,
  errorText,
  formatAmount,
  formatCoverageDays,
  formatOwnedAssetValue,
  formatQty,
  isAgreementActive,
  isSettlementLocked,
  isSignalOpen,
  missingStockHint,
  monitorOwnershipCards,
  needsSignal,
  ownershipSplitNote,
  projectedHint,
  safetyGapText,
  separationWarning,
  signalTargetHint,
  signalTone,
  stockSourceLabel,
  stockSourceTone,
  waterLevelLabel,
  waterLevelTone,
} from './vmi-model';
import type { VmiLine, VmiOwnershipSplit, VmiWaterLevelMonitor } from '../../../../../packages/api-contracts/src/index';

/**
 * 7.7.2.1 前端纯函数单测。
 *
 * <p>重点覆盖本模块最容易在界面上说错的一件事：**「可售」≠「自有」**。
 * 具体是四条：
 *
 * 1. 自有货值是 null，**不能画成 0**（0 会被读成「这些货值 0 元」，那是谎报）；
 * 2. VMI 可售额与我方自有量**永不合并**成「库存价值」；
 * 3. 缺来源（MISSING）要显眼，且要说清后果（按 0 计入水位会误判断货）；
 * 4. 覆盖率没有销量时是 null 而不是 0 天。
 */

function split(over: Partial<VmiOwnershipSplit> = {}): VmiOwnershipSplit {
  return {
    sellableQuantity: 30,
    sellableValue: 375,
    ownedAssetQuantity: 0,
    ownedAssetValue: null,
    countsAsOwnedAsset: false,
    ownershipNote: '',
    separationNote: '',
    ...over,
  };
}

function line(over: Partial<VmiLine> = {}): VmiLine {
  return {
    id: 'l1',
    agreementId: 'a1',
    skuId: 's1',
    skuCode: 'SKU-1',
    skuName: '商品一',
    warehouseId: 'w1',
    warehouseCode: 'WH-1',
    stockOwnerId: 'o1',
    stockOwnerCode: 'OWNER-VMI',
    stockOwnerKind: 'SUPPLIER_CONSIGNMENT',
    safetyLevel: 20,
    replenishmentMultiple: 2,
    settlementPrice: 12.5,
    suppliedQuantity: 100,
    soldQuantity: 10,
    withdrawnQuantity: 0,
    onHandQuantity: 30,
    currentSellableQuantity: 8,
    stockSource: 'WAREHOUSE_BUCKET',
    stockSourceLabel: '自有仓账本算出',
    observedAt: null,
    waterLevelStatus: 'BELOW_SAFETY_LEVEL',
    waterLevelStatusLabel: '低于安全水位',
    safetyGap: 12,
    bufferLine: 60,
    projectedQuantity: 24,
    soldQuantity30d: 60,
    coverageDays: 4,
    unsettleableQuantity: 10,
    unsettleableAmount: 125,
    openSignalCode: null,
    ownershipSplit: split(),
    ...over,
  };
}

describe('可售 ≠ 自有：自有货值是 null，不能画成 0', () => {
  it('null 显示成「不计入自有资产」而不是 0 元', () => {
    expect(formatOwnedAssetValue(null)).toContain('不计入自有资产');
    expect(formatOwnedAssetValue(null)).not.toMatch(/0\.00/);
    expect(formatOwnedAssetValue(undefined)).toContain('不计入自有资产');
  });

  it('真的有值时才按金额展示（护住未来万一有别的口径接进来）', () => {
    expect(formatOwnedAssetValue(1234.5)).toContain('1,234.50');
  });

  it('口径文案同时讲两个方向：可售额计入、自有货值不计入', () => {
    const note = ownershipSplitNote(split(), 'CNY');
    expect(note).toContain('计入可售');
    expect(note).toContain('不计入我方自有资产');
    expect(note).toContain('30');
  });

  it('货主被错标成自有时要显式报异常，而不是照常显示', () => {
    const note = ownershipSplitNote(split({ countsAsOwnedAsset: true }), 'CNY');
    expect(note).toContain('异常');
    expect(note).toContain('自有资产');
  });
});

describe('两张卡片永不合并成「库存价值」', () => {
  function monitor(over: Partial<VmiWaterLevelMonitor> = {}): VmiWaterLevelMonitor {
    return {
      agreementId: 'a1',
      agreementCode: 'VMI-1',
      supplierName: '供应商一',
      currency: 'CNY',
      replenishmentMode: 'SYSTEM_SIGNAL',
      replenishmentModeLabel: '系统出补货信号',
      signalTarget: 'SUPPLIER',
      signalTargetLabel: '推给供应商',
      status: 'ACTIVE',
      lineCount: 1,
      healthyLineCount: 0,
      belowBufferLineCount: 0,
      belowSafetyLineCount: 1,
      stockoutLineCount: 0,
      missingStockSourceLineCount: 0,
      totalSafetyLevel: 20,
      totalCurrentSellable: 30,
      totalProjectedQuantity: 24,
      openSignalCount: 0,
      ownershipSplit: split(),
      selfOwnedSplit: {
        selfOwnedQuantity: 15,
        selfOwnedBucketCount: 1,
        note: '',
      },
      lines: [],
      ...over,
    };
  }

  it('两张卡各自给数，自有那张恒为「不计入自有资产」', () => {
    const cards = monitorOwnershipCards(monitor());
    expect(cards.sellable).toContain('30');
    expect(cards.sellableValue).toContain('375');
    expect(cards.owned).toContain('不计入自有资产');
  });

  it('提示语明确说「不可相加」，并解释为什么', () => {
    const text = separationWarning(30, 15);
    expect(text).toContain('不可相加');
    expect(text).toContain('供应商');
    expect(cardsNoteHasNoSum(monitor())).toBe(true);
  });

  it('卡片对象里不存在任何把两者相加的字段', () => {
    const cards = monitorOwnershipCards(monitor());
    // 只有四组值：可售量、可售额、自有货值、口径提示。没有「合计」这一项。
    expect(Object.keys(cards).sort()).toEqual(['note', 'owned', 'sellable', 'sellableValue']);
  });
});

function cardsNoteHasNoSum(m: VmiWaterLevelMonitor): boolean {
  return monitorOwnershipCards(m).note.includes('不可相加');
}

describe('缺来源要显眼', () => {
  it('MISSING 用危险语气，其它来源按可信度区分', () => {
    expect(stockSourceTone('MISSING')).toBe('danger');
    expect(stockSourceTone('WAREHOUSE_BUCKET')).toBe('ok');
    expect(stockSourceTone('TENANT_MAINTAINED')).toBe('muted');
    expect(stockSourceLabel('MISSING')).toBe('查不到来源');
    expect(stockSourceLabel('WAREHOUSE_BUCKET')).toContain('账本');
  });

  it('缺来源提示要说清后果与补救', () => {
    const hint = missingStockHint();
    expect(hint).toContain('按 0 计入水位');
    expect(hint).toContain('登记在库观测');
  });
});

describe('水位四档分开，语气跟着分开', () => {
  it('断货最重、水位不足次之、健康最轻', () => {
    expect(waterLevelTone('STOCKOUT')).toBe('danger');
    expect(waterLevelTone('BELOW_SAFETY_LEVEL')).toBe('warn');
    expect(waterLevelTone('BELOW_BUFFER')).toBe('muted');
    expect(waterLevelTone('HEALTHY')).toBe('ok');
    expect(waterLevelLabel('STOCKOUT')).toBe('断货');
  });

  it('只有水位不健康才需要出信号', () => {
    expect(needsSignal(line({ waterLevelStatus: 'HEALTHY' }))).toBe(false);
    expect(needsSignal(line({ waterLevelStatus: 'BELOW_BUFFER' }))).toBe(true);
    expect(needsSignal(line({ waterLevelStatus: 'STOCKOUT' }))).toBe(true);
  });

  it('缺口文本把方向讲清楚，不只给一个带符号的数', () => {
    expect(safetyGapText(12)).toContain('还差');
    expect(safetyGapText(-5)).toContain('高于安全水位');
    expect(safetyGapText(0)).toContain('水位线上');
  });

  it('建议补货量把算式写出来，让人能复算', () => {
    const hint = projectedHint(line());
    expect(hint).toContain('安全水位');
    expect(hint).toContain('补货倍数');
    expect(hint).toContain('向上取整');
  });
});

describe('null 口径一律不画成 0', () => {
  it('覆盖率没有销量时显示「无销量」而不是 0 天', () => {
    expect(formatCoverageDays(null)).toContain('无销量');
    // 不能断言 not.toContain('0 天')：真话「近 30 天无销量」本身就含子串「0 天」。
    // 真正要否掉的是「宣称覆盖 0 天」这种说法。
    expect(formatCoverageDays(null)).not.toMatch(/^约 0 天/);
    expect(formatCoverageDays(4)).toContain('4');
  });

  it('数量与金额的缺失值一律破折号', () => {
    expect(formatQty(null)).toBe('—');
    expect(formatAmount(null, 'CNY')).toBe('—');
    expect(formatAmount(12.5, 'CNY')).toContain('CNY');
  });
});

describe('信号状态机与出处', () => {
  it('未了结的信号不允许再出第二条', () => {
    expect(isSignalOpen({ status: 'OPEN' })).toBe(true);
    expect(isSignalOpen({ status: 'ACKNOWLEDGED' })).toBe(true);
    expect(isSignalOpen({ status: 'DISPATCHED' })).toBe(true);
    expect(isSignalOpen({ status: 'FULFILLED' })).toBe(false);
    expect(isSignalOpen({ status: 'CANCELLED' })).toBe(false);
  });

  it('只有已下发的信号能登记到货', () => {
    expect(canFulfil({ status: 'DISPATCHED' })).toBe(true);
    expect(canFulfil({ status: 'ACKNOWLEDGED' })).toBe(false);
    expect(canFulfil({ status: 'FULFILLED' })).toBe(false);
  });

  it('待处理的信号最显眼', () => {
    expect(signalTone('OPEN')).toBe('danger');
    expect(signalTone('FULFILLED')).toBe('ok');
    expect(signalTone('CANCELLED')).toBe('muted');
  });

  it('转 6 采购要说明下游未交付', () => {
    expect(signalTargetHint('PROCUREMENT')).toContain('6 采购');
    expect(signalTargetHint('PROCUREMENT')).toContain('交接键');
    expect(signalTargetHint('SUPPLIER')).toContain('供应商');
  });
});

describe('单据状态与审计标签', () => {
  it('确认后的对账单不再给改数量的暗示', () => {
    expect(isSettlementLocked({ status: 'OPEN' })).toBe(false);
    expect(isSettlementLocked({ status: 'CONFIRMED' })).toBe(true);
    expect(isSettlementLocked({ status: 'CANCELLED' })).toBe(true);
  });

  it('协议只在生效中才能记售出与出对账单', () => {
    expect(isAgreementActive({ status: 'ACTIVE' })).toBe(true);
    expect(isAgreementActive({ status: 'DRAFT' })).toBe(false);
    expect(isAgreementActive({ status: 'SUSPENDED' })).toBe(false);
  });

  it('审计事件有中文标签，未知事件回退成原值', () => {
    expect(auditLabel('SIGNAL_GENERATED')).toBe('出补货信号');
    expect(auditLabel('SETTLEMENT_CONFIRMED')).toContain('12 结算');
    expect(auditLabel('WHATEVER')).toBe('WHATEVER');
  });

  it('错误信息能直接展示', () => {
    expect(errorText(new Error('boom'))).toBe('boom');
    expect(errorText('nope')).toBe('操作失败，请重试。');
  });
});