import { describe, expect, it } from 'vitest';
import {
  NO_DATA_TEXT,
  attachmentTypeLabel,
  conversionStatusLabel,
  emptyLine,
  emptyOrder,
  estimateTotal,
  fmtAmount,
  fmtDate,
  fmtQty,
  fmtRate,
  fxStatusLabel,
  priceSourceLabel,
  quoteSelectionLabel,
  stockStatusLabel,
  taxStatusLabel,
  unavailableCapabilities,
  type PurchaseOrderEditCapabilities,
} from './purchase-order-edit-model';

/**
 * 6.3.1.2 采购单创建与编辑前端模型的纯函数测试。
 *
 * <p>覆盖的是**页面真的会依赖的口径**，不是覆盖率：
 *
 * <ul>
 *   <li><b>null ≠ 0</b>：汇率 / 税额 / 库存 / 箱规取不到时一律给「无数据」，
 *       绝不显示 0 —— 0 会被读成「不用折算 / 不用交税 / 一件都没有」，
 *       而真实含义是「**还不知道**」。</li>
 *   <li><b>试算不是入账</b>：编辑中的总额是本页试算；缺数量或单价时给 null，
 *       不给一个看起来完整的数。</li>
 *   <li><b>歧义报价不替用户挑</b>：并列的候选要能逐条区分。</li>
 *   <li><b>能力边界</b>：未交付的下游（6.3.1.3 审批 / 12 财务）要能被单独挑出来。</li>
 * </ul>
 */

describe('金额 / 数量的展示', () => {
  it('null 一律给「无数据」，绝不显示 0', () => {
    expect(fmtAmount(null)).toBe(NO_DATA_TEXT);
    expect(fmtAmount(undefined)).toBe(NO_DATA_TEXT);
    expect(fmtQty(null)).toBe(NO_DATA_TEXT);
    expect(fmtRate(null)).toBe(NO_DATA_TEXT);
  });

  it('0 是真值：显示 0 而不是「无数据」', () => {
    expect(fmtAmount(0)).toBe('0.00');
    expect(fmtQty(0)).toBe('0');
  });

  it('金额保留两位到四位小数，数量最多六位', () => {
    expect(fmtAmount(1234.56789)).toBe('1,234.5679');
    expect(fmtQty(1.23456789)).toBe('1.234568');
  });

  it('汇率保留四位到八位小数（汇率的精度比金额高）', () => {
    expect(fmtRate(7.2)).toBe('7.2000');
    expect(fmtRate(7.12345678)).toBe('7.12345678');
  });

  it('交期没定显示「未定」而不是空', () => {
    expect(fmtDate('')).toBe('未定');
    expect(fmtDate(null)).toBe('未定');
    expect(fmtDate('2026-11-01')).toBe('2026-11-01');
  });
});

describe('本页试算', () => {
  it('缺数量或单价时给 null —— 不给一个看起来完整的数', () => {
    expect(estimateTotal([])).toBe(0);
    expect(
      estimateTotal([{ ...emptyLine(1), quantity: 10, unitPrice: null as unknown as number }]),
    ).toBeNull();
    expect(
      estimateTotal([{ ...emptyLine(1), quantity: null as unknown as number, unitPrice: 2.5 }]),
    ).toBeNull();
  });

  it('全部填了就给出数量 × 单价的和', () => {
    expect(
      estimateTotal([
        { ...emptyLine(1), quantity: 10, unitPrice: 2.5 },
        { ...emptyLine(2), quantity: 4, unitPrice: 1.25 },
      ]),
    ).toBe(30);
  });
});

describe('空表单的默认值', () => {
  it('新行的数量与单价是 null 而不是 0', () => {
    const line = emptyLine(1);
    expect(line.quantity).toBeNull();
    expect(line.unitPrice).toBeNull();
    expect(line.priceSource).toBe('MANUAL');
    expect(line.quoteId).toBeNull();
  });

  it('新表单带一行空行，且没有发货地址与附件', () => {
    const order = emptyOrder();
    expect(order.lines).toHaveLength(1);
    expect(order.shipAddress).toBeNull();
    expect(order.attachments).toHaveLength(0);
    expect(order.currency).toBe('');
  });
});

describe('状态标签', () => {
  it('汇率快照的三种状态各有各的说法', () => {
    expect(fxStatusLabel('CAPTURED')).toBe('已冻结');
    expect(fxStatusLabel('UNAVAILABLE')).toBe('取不到汇率快照');
    expect(fxStatusLabel('NOT_APPLICABLE')).toBe('还没提交 / 不需要折算');
  });

  it('单位换算：未取到箱规要说出来，不许含糊', () => {
    expect(conversionStatusLabel('CATALOG_UNIT_CONVERSION')).toBe('已取到箱规');
    expect(conversionStatusLabel('UNKNOWN')).toBe('未取到箱规');
  });

  it('库存快照取不到要单独说', () => {
    expect(stockStatusLabel('CAPTURED')).toBe('已冻结');
    expect(stockStatusLabel('UNAVAILABLE')).toBe('取不到库存快照');
  });

  it('税额归 12 财务', () => {
    expect(taxStatusLabel('NOT_AVAILABLE')).toBe('归 12 财务算');
  });

  it('单价来源三态', () => {
    expect(priceSourceLabel('QUOTE')).toBe('报价');
    expect(priceSourceLabel('MANUAL')).toBe('手工填写');
    expect(priceSourceLabel('REQUEST_ESTIMATE')).toBe('申请预估');
  });

  it('附件类型四态', () => {
    expect(attachmentTypeLabel('CONTRACT')).toBe('合同');
    expect(attachmentTypeLabel('OTHER')).toBe('其它');
  });
});

describe('报价候选', () => {
  it('并列的候选要说清「要你选」', () => {
    expect(quoteSelectionLabel('SELECTED')).toBe('默认采用');
    expect(quoteSelectionLabel('AMBIGUOUS')).toBe('优先级并列（要你选）');
    expect(quoteSelectionLabel('LOWER_PRIORITY')).toBe('备选');
  });
});

describe('能力边界', () => {
  const caps: PurchaseOrderEditCapabilities = {
    capabilities: [
      {
        code: 'manualCreate',
        label: '手工建单',
        available: true,
        ownerModule: '6.3.1.2',
        reason: '',
      },
      {
        code: 'approval',
        label: '审批',
        available: false,
        ownerModule: '6.3.1.3 采购单审批',
        reason: '6.3.1.3 未交付',
      },
      {
        code: 'taxAmount',
        label: '税额计算',
        available: false,
        ownerModule: '12 财务',
        reason: 'finance 服务当前不存在',
      },
    ],
    note: '',
  };

  it('只挑出没交付的那些（页面底部要如实列出来）', () => {
    const unavailable = unavailableCapabilities(caps);
    expect(unavailable.map((c) => c.code)).toEqual(['approval', 'taxAmount']);
    expect(unavailable[0]?.ownerModule).toBe('6.3.1.3 采购单审批');
  });

  it('能力声明拿不到时给空数组，不报错', () => {
    expect(unavailableCapabilities(null)).toEqual([]);
    expect(unavailableCapabilities(undefined)).toEqual([]);
  });
});
