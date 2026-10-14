import { describe, expect, it } from 'vitest';
import {
  canSubmit,
  chargeSign,
  chargeSignHint,
  isEditable,
  lineAmount,
  manualKindLabel,
  needsTermsDays,
  normalizeChargeAmount,
  normalizeTermsDays,
  orderSourceLabel,
  previewTotals,
  salesOrderQuery,
  statusLabel,
  statusTone,
  submitBlockers,
} from './manual-order-model';

/**
 * 9.1.2.2 手工订单前端模型。
 *
 * <p>这些断言钉的是**界面上不能搞错的判断**，不是文案：
 * 折扣的符号、账期自洽、金额口径（与服务层和 DB 触发器同一条公式）、
 * 已提交只读、提交前的缺口提示、筛选串的编码。
 *
 * <p>金额那一组尤其重要：与服务层 {@code ManualOrderService.totals} 和 V99
 * {@code guard_sales_order_totals} 是同一条公式，三处任何一处改了另外两处就会对不上，
 * 而界面上算错时用户未必看得出来。
 */

describe('来源是字段不是表', () => {
  it('来源标签覆盖手工与平台两类来源', () => {
    // 「来源是一个字段」在界面上的体现：MANUAL 与平台来源并列在同一张词表里，
    // 而不是「手工单页只认手工、平台单页只认平台」两套。
    expect(orderSourceLabel('MANUAL')).toBe('手工订单');
    expect(orderSourceLabel('AMAZON')).toBe('Amazon');
    expect(orderSourceLabel('SHOPIFY')).toBe('Shopify');
    expect(orderSourceLabel('ALIBABA_1688')).toBe('1688');
  });

  it('未知来源原样透出而不是悄悄显示空白', () => {
    expect(orderSourceLabel('SOMETHING_NEW')).toBe('SOMETHING_NEW');
  });
});

describe('折扣的符号由界面代劳', () => {
  it('折扣记负数、其余记正数', () => {
    expect(chargeSign('DISCOUNT')).toBe(-1);
    expect(chargeSign('SHIPPING')).toBe(1);
    expect(chargeSign('TAX')).toBe(1);
    expect(chargeSign('PACKAGING')).toBe(1);
    expect(chargeSign('OTHER')).toBe(1);
  });

  it('只填幅度，符号由类型补上 —— 人手写负号时最常出的错就是填了正折扣', () => {
    expect(normalizeChargeAmount('DISCOUNT', 30)).toBe(-30);
    expect(normalizeChargeAmount('SHIPPING', 30)).toBe(30);
    // 就算人不小心填了负的折扣幅度，界面仍然归一成负数：
    // 正折扣会让「减钱」变成「加钱」，而列表上完全看不出来。
    expect(normalizeChargeAmount('DISCOUNT', -30)).toBe(-30);
    expect(normalizeChargeAmount('SHIPPING', -30)).toBe(30);
    expect(normalizeChargeAmount('DISCOUNT', 0)).toBe(0);
  });

  it('正负号提示文案与类型一致', () => {
    expect(chargeSignHint('DISCOUNT')).toContain('负数');
    expect(chargeSignHint('SHIPPING')).toContain('正数');
  });
});

describe('账期与非账期自洽', () => {
  it('只有账期才带天数', () => {
    expect(needsTermsDays('CREDIT_TERM')).toBe(true);
    expect(needsTermsDays('PREPAID')).toBe(false);
  });

  it('非账期不许带天数 —— 「预付 + 90 天账期」在界面上就被抹平', () => {
    expect(normalizeTermsDays('CREDIT_TERM', 30)).toBe(30);
    expect(normalizeTermsDays('PREPAID', 30)).toBe(0);
    expect(normalizeTermsDays('BANK_TRANSFER', 90)).toBe(0);
    expect(normalizeTermsDays('CREDIT_TERM', null)).toBe(0);
  });
});

describe('金额口径与服务层、DB 触发器同源', () => {
  it('行小计 = 单价 × 数量，4 位小数 HALF_UP', () => {
    expect(lineAmount({ unitPrice: 10.5, quantity: 2 })).toBe(21);
    expect(lineAmount({ unitPrice: 3.33, quantity: 3 })).toBe(9.99);
    // 0.1 × 3 在二进制浮点下是 0.30000000000000004，必须由 HALF_UP 收口。
    expect(lineAmount({ unitPrice: 0.1, quantity: 3 })).toBe(0.3);
    expect(lineAmount({ unitPrice: 12.345, quantity: 3 })).toBe(37.035);
  });

  it('订单汇总逐项与服务端测试里的那笔单一致', () => {
    // 与 ManualOrderIntegrationTest「硬要求2」用的是同一组数：
    //   行1 2×10.50=21.0000 / 行折扣 -1.0000 / 行税 +2.0000
    //   行2 1×5.00=5.0000   / 行折扣 0        / 行税 0
    //   费用 SHIPPING 8.00 / DISCOUNT -3.00 / TAX 1.50 / PACKAGING 2.00
    //   小计 26.0000 ｜ 折扣 4.0000 ｜ 税 3.5000 ｜ 运费 8.0000 ｜ 其他 2.0000 ｜ 合计 35.5000
    const totals = previewTotals(
      [
        { unitPrice: 10.5, quantity: 2, discountAmount: -1, taxAmount: 2 },
        { unitPrice: 5, quantity: 1, discountAmount: 0, taxAmount: 0 },
      ],
      [
        { chargeType: 'SHIPPING', amount: 8 },
        { chargeType: 'DISCOUNT', amount: -3 },
        { chargeType: 'TAX', amount: 1.5 },
        { chargeType: 'PACKAGING', amount: 2 },
      ],
    );
    expect(totals.subtotal).toBe(26);
    expect(totals.discount).toBe(4);
    expect(totals.tax).toBe(3.5);
    expect(totals.shipping).toBe(8);
    expect(totals.other).toBe(2);
    expect(totals.grandTotal).toBe(35.5);
  });

  it('空明细不炸：没有费用时其他三档都是 0', () => {
    const totals = previewTotals(
      [{ unitPrice: 10, quantity: 1, discountAmount: 0, taxAmount: 0 }],
      [],
    );
    expect(totals.shipping).toBe(0);
    expect(totals.other).toBe(0);
    expect(totals.discount).toBe(0);
    expect(totals.grandTotal).toBe(10);
  });

  it('折扣大于小计时合计可以为负 —— 界面不私自截断，如实算出来', () => {
    // 后端 DB CHECK 要求 grand_total >= 0，所以这种单提交会被 DB 拒。
    // 界面**不**偷偷夹到 0：夹了就与后端算出两个不同的数，
    // 用户看到的总额和他提交后拿到的结果会对不上。
    const totals = previewTotals(
      [{ unitPrice: 10, quantity: 1, discountAmount: -20, taxAmount: 0 }],
      [],
    );
    expect(totals.discount).toBe(20);
    expect(totals.grandTotal).toBe(-10);
  });
});

describe('状态与只读', () => {
  it('只有草稿能编辑、能提交', () => {
    expect(isEditable('DRAFT')).toBe(true);
    expect(isEditable('SUBMITTED')).toBe(false);
    expect(canSubmit('DRAFT')).toBe(true);
    expect(canSubmit('SUBMITTED')).toBe(false);
  });

  it('状态标签与后端枚举一一对应，未知值原样透出', () => {
    expect(statusLabel('DRAFT')).toBe('草稿');
    expect(statusLabel('SUBMITTED')).toBe('已提交待审');
    expect(statusLabel('APPROVED')).toBe('APPROVED');
  });

  it('徽章只有两档：本模块只有两个状态，不为不存在的状态编一档颜色', () => {
    // tone 必须落在 @starcon/ui Badge 的真实词表里（neutral / primary / success /
    // warning / danger / brand / info）—— 写一个库里不存在的 tone，
    // 编译期就会拦下，而不是渲染出一个没有样式的徽章。
    expect(statusTone('DRAFT')).toBe('neutral');
    expect(statusTone('SUBMITTED')).toBe('primary');
  });

  it('手工单子类标签覆盖蓝图点名的四种非平台来源', () => {
    expect(manualKindLabel('OFFLINE_STORE')).toBe('独立站线下');
    expect(manualKindLabel('B2B')).toBe('B2B 批发');
    expect(manualKindLabel('DROPSHIP')).toBe('代发');
    expect(manualKindLabel('SAMPLE')).toBe('样机单');
  });
});

describe('提交前的缺口提示', () => {
  it('三道闸与 DB 延迟约束触发器逐条对应', () => {
    expect(
      submitBlockers({ lines: [], addresses: [], customer: null }),
    ).toHaveLength(3);
  });

  it('行 / 收货地址 / 客户都齐了才放行', () => {
    expect(
      submitBlockers({
        lines: [{}],
        addresses: [{ addressRole: 'SHIPPING' }],
        customer: {},
      }),
    ).toEqual([]);
  });

  it('只有开票地址、没有收货地址仍然拦住 —— 开票地址不能顶替收货地址', () => {
    const blockers = submitBlockers({
      lines: [{}],
      addresses: [{ addressRole: 'BILLING' }],
      customer: {},
    });
    expect(blockers).toHaveLength(1);
    expect(blockers[0]).toContain('收货地址');
  });

  it('缺口提示写清为什么，而不是只说「不合法」', () => {
    const [first] = submitBlockers({ lines: [], addresses: [], customer: null });
    expect(first).toContain('商品行');
  });
});

describe('筛选串', () => {
  it('空条件不带问号', () => {
    expect(salesOrderQuery({})).toBe('');
  });

  it('逐项编码，含空格与中文也要编码', () => {
    expect(salesOrderQuery({ keyword: 'acme trading' })).toBe('?keyword=acme%20trading');
    expect(salesOrderQuery({ manualKind: 'B2B' })).toBe('?manualKind=B2B');
    expect(salesOrderQuery({ status: 'SUBMITTED', page: 0, size: 20 })).toBe(
      '?status=SUBMITTED&page=0&size=20',
    );
  });

  it('纯空白的关键字不进串 —— 否则会多发一个 keyword= 的空查询', () => {
    expect(salesOrderQuery({ keyword: '   ' })).toBe('');
  });
});
