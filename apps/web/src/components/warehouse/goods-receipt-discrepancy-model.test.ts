import { describe, expect, it } from 'vitest';
import {
  allowedDispositions,
  discrepancyHandlingQuery,
  dependencyCaption,
  discrepancyTypeLabels,
  dispositionLabels,
  evidenceTypeLabel,
  handlingActions,
  handlingQuantityText,
  handlingSortOptions,
  handlingStatusLabels,
  inboundQualityOptions,
  isRiskType,
  moment,
  qualityRoleLabels,
  riskDiscrepancyTypes,
  stockEffectText,
  stockMoveDirectionLabels,
} from './goods-receipt-discrepancy-model';

const allDispositions = [
  { value: 'REJECT', allowedForRiskTypes: true },
  { value: 'PARTIAL_ACCEPT', allowedForRiskTypes: true },
  { value: 'HOLD_FOR_INSPECTION', allowedForRiskTypes: true },
  { value: 'ACCEPT', allowedForRiskTypes: false },
];

const qualities = [
  { id: 'q1', code: 'SELLABLE', name: '可售', role: 'SELLABLE' },
  { id: 'q2', code: 'PENDING_INSPECTION', name: '待检', role: 'PENDING_INSPECTION' },
  { id: 'q3', code: 'DEFECTIVE', name: '残次', role: 'DEFECTIVE' },
];

describe('差异处理查询串', () => {
  it('空条件不带问号', () => {
    expect(discrepancyHandlingQuery({})).toBe('');
  });

  it('每个筛选条件都真的进查询串', () => {
    const q = discrepancyHandlingQuery({
      receiptId: 'r1',
      warehouseId: 'w1',
      status: ['PENDING', 'DECIDED'],
      disposition: ['REJECT'],
      discrepancyType: ['DAMAGED'],
      claimStatus: ['INITIATED'],
      keyword: '  外箱压塌 ',
      sort: 'DECIDED_AT',
      page: 1,
      size: 20,
    });
    expect(q.startsWith('?')).toBe(true);
    expect(q).toContain('receiptId=r1');
    expect(q).toContain('warehouseId=w1');
    expect(q).toContain('status=PENDING');
    expect(q).toContain('status=DECIDED');
    expect(q).toContain('disposition=REJECT');
    expect(q).toContain('discrepancyType=DAMAGED');
    expect(q).toContain('claimStatus=INITIATED');
    expect(q).toContain('keyword=%E5%A4%96%E7%AE%B1%E5%8E%8B%E5%A1%8C');
    expect(q).toContain('sort=DECIDED_AT');
    expect(q).toContain('page=1');
    expect(q).toContain('size=20');
  });

  it('空关键字与空数组都不进查询串', () => {
    expect(discrepancyHandlingQuery({ keyword: '   ', status: [] })).toBe('');
  });
});

describe('可售硬规则在界面上的体现', () => {
  it('风险差异类型就是破损短少型号不符过期这四种', () => {
    expect([...riskDiscrepancyTypes]).toEqual([
      'DAMAGED',
      'SHORT_RECEIPT',
      'WRONG_ITEM',
      'EXPIRED',
    ]);
    expect(isRiskType('DAMAGED')).toBe(true);
    expect(isRiskType('SHORT_RECEIPT')).toBe(true);
    expect(isRiskType('WRONG_ITEM')).toBe(true);
    expect(isRiskType('EXPIRED')).toBe(true);
    // 超收与错发是「货本身没问题、只是单据对不上」，允许正常入库
    expect(isRiskType('OVER_RECEIPT')).toBe(false);
    expect(isRiskType('MISDELIVERED')).toBe(false);
  });

  it('破损与短少拿掉「正常入库」', () => {
    const allowed = allowedDispositions('DAMAGED', allDispositions);
    expect(allowed).not.toContain('ACCEPT');
    expect(allowed).toEqual(['REJECT', 'PARTIAL_ACCEPT', 'HOLD_FOR_INSPECTION']);
  });

  it('超收与错发保留「正常入库」', () => {
    expect(allowedDispositions('OVER_RECEIPT', allDispositions)).toContain('ACCEPT');
    expect(allowedDispositions('MISDELIVERED', allDispositions)).toContain('ACCEPT');
  });

  it('风险类型不给「可售」去向选项，避免界面直接把人送进被拒的路径', () => {
    const options = inboundQualityOptions('DAMAGED', qualities);
    expect(options.map((o) => o.role)).toEqual(['PENDING_INSPECTION', 'DEFECTIVE']);
    expect(options.map((o) => o.role)).not.toContain('SELLABLE');
  });

  it('非风险类型保留全部去向', () => {
    expect(inboundQualityOptions('OVER_RECEIPT', qualities)).toHaveLength(3);
  });
});

describe('处理状态与动作', () => {
  it('四个状态的标签齐全', () => {
    expect(Object.keys(handlingStatusLabels).sort()).toEqual([
      'CLAIMED',
      'CLOSED',
      'DECIDED',
      'PENDING',
    ]);
  });

  it('待处理只能留证与决定', () => {
    expect(handlingActions('PENDING')).toEqual(['evidence', 'decide']);
  });

  it('已决定可以索赔与结案，但不能改判', () => {
    expect(handlingActions('DECIDED')).toEqual(['evidence', 'claim', 'close']);
    expect(handlingActions('DECIDED')).not.toContain('decide');
  });

  it('已索赔可以结案但不能重复索赔', () => {
    expect(handlingActions('CLAIMED')).toEqual(['evidence', 'close']);
    expect(handlingActions('CLAIMED')).not.toContain('claim');
  });

  it('已关闭是终态，不给任何动作', () => {
    expect(handlingActions('CLOSED')).toEqual([]);
  });

  it('未知状态不给动作而不是兜底成全部按钮', () => {
    expect(handlingActions('SOMETHING_NEW')).toEqual([]);
  });
});

describe('数量口径自检', () => {
  it('接受量加拒收量等于差异数量时自检通过', () => {
    const r = handlingQuantityText({ quantity: 10, acceptedQuantity: 4, rejectedQuantity: 6 });
    expect(r.ok).toBe(true);
    expect(r.text).toContain('差异 10 = 接受 4 + 拒收 6');
  });

  it('对不上时自检失败并在文案里说清', () => {
    const r = handlingQuantityText({ quantity: 10, acceptedQuantity: 4, rejectedQuantity: 5 });
    expect(r.ok).toBe(false);
    expect(r.text).toContain('对不上');
  });

  it('未决定时两个量都是 0，与差异数量的差额就是全部待处理', () => {
    const r = handlingQuantityText({ quantity: 10, acceptedQuantity: 0, rejectedQuantity: 0 });
    expect(r.ok).toBe(false);
  });
});

describe('库存后果说明', () => {
  it('没有任何流转时说清是还没提交还是从未入库', () => {
    expect(stockEffectText([])).toContain('尚未产生库存流转');
  });

  it('逐条列出方向与数量', () => {
    const text = stockEffectText([
      { direction: 'WITHDRAW', quantity: 6 } as never,
      { direction: 'INBOUND_TO_HOLD', quantity: 4 } as never,
    ]);
    expect(text).toContain('撤出 6');
    expect(text).toContain('转入待检 4');
  });

  it('拒收不入账与无需移动是两个不同的方向，不混为一谈', () => {
    expect(stockMoveDirectionLabels.NOT_POSTED).toBe('拒收不入账');
    expect(stockMoveDirectionLabels.NO_MOVE).toBe('无需移动');
    expect(stockMoveDirectionLabels.WITHDRAW).toBe('撤出');
  });
});

describe('词表', () => {
  it('七类差异标签齐全', () => {
    expect(Object.keys(discrepancyTypeLabels)).toHaveLength(7);
    expect(discrepancyTypeLabels.DAMAGED).toBe('破损');
    expect(discrepancyTypeLabels.SHORT_RECEIPT).toBe('少收');
  });

  it('四种处理决定标签齐全', () => {
    expect(Object.keys(dispositionLabels)).toHaveLength(4);
    expect(dispositionLabels.HOLD_FOR_INSPECTION).toBe('待检入库');
  });

  it('凭证类型标签齐全', () => {
    expect(evidenceTypeLabel('PHOTO')).toBe('照片');
    expect(evidenceTypeLabel('WEIGHING')).toBe('复磅单');
    expect(evidenceTypeLabel('INSPECTION')).toBe('质检报告');
    expect(evidenceTypeLabel('DOCUMENT')).toBe('单据');
  });

  it('质量状态 role 有中文文案', () => {
    expect(qualityRoleLabels.SELLABLE).toBe('可售');
    expect(qualityRoleLabels.PENDING_INSPECTION).toBe('待检');
    expect(qualityRoleLabels.DEFECTIVE).toBe('残次');
  });

  it('未交付的下游带归属模块后缀，界面上直接禁用', () => {
    expect(
      dependencyCaption({ label: '对账索赔受理与结算', available: false, ownerModule: '6.5.1' }),
    ).toBe('对账索赔受理与结算（6.5.1 未交付）');
    expect(dependencyCaption({ label: '已交付', available: true, ownerModule: '6.5.1' })).toBe(
      '已交付',
    );
  });

  it('排序选项四个键都在', () => {
    expect(handlingSortOptions.map((o) => o.value)).toEqual([
      'UPDATED_AT',
      'CREATED_AT',
      'DECIDED_AT',
      'QUANTITY',
    ]);
  });
});

describe('时间展示', () => {
  it('空值显示占位符而不是 Invalid Date', () => {
    expect(moment(null)).toBe('—');
    expect(moment(undefined)).toBe('—');
    expect(moment('')).toBe('—');
  });

  it('非法串原样返回，不伪装成合法时间', () => {
    expect(moment('不是时间')).toBe('不是时间');
  });

  it('合法时间被本地化展示', () => {
    expect(moment('2026-03-14T15:09:26+08:00')).toContain('2026');
  });
});
