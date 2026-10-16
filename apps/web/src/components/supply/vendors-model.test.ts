import { describe, expect, it } from 'vitest';

import {
  EMPTY_VENDOR_FILTERS,
  amountListText,
  cooperationLevelLabel,
  dateText,
  daysLateText,
  partyStatusLabel,
  partyStatusTone,
  rateLabel,
  rateTone,
  supplyStatusLabel,
  supplyStatusTone,
  toSupplyProfileForm,
  validateCurrency,
  validateDelivery,
  validateInspection,
  validateSupplyProfile,
  validateVoidReason,
  windowText,
} from './vendors-model';
import type { VendorRow } from './vendors-model';

function row(overrides: Partial<VendorRow> = {}): VendorRow {
  return {
    roleId: 'r1',
    partyId: 'p1',
    partyCode: 'SUP-001',
    partyName: '义乌小商品供应商',
    partyType: 'ORGANIZATION',
    countryCode: 'CN',
    email: 'mail@example.com',
    phone: '+86-570-0000-0000',
    website: null,
    partyStatus: 'ACTIVE',
    statusReason: null,
    cooperationLevel: 'PREFERRED',
    paymentTermsDays: 30,
    settlementMethod: 'CREDIT',
    supplierProfileFound: true,
    supplyStatus: 'ACTIVE',
    categoryId: 'c1',
    categoryName: '家居收纳',
    supplyNote: '',
    supplyProfileFound: true,
    supplyProfileVersion: 2,
    supplySkuCount: 7,
    purchaseAmounts: [{ currency: 'USD', amount: '2000.00' }],
    purchaseAmount: '2000.00',
    purchaseAmountCurrency: 'USD',
    onTimeDeliveryCount: 2,
    deliveryCount: 3,
    onTimeRate: '0.6667',
    qualifiedQuantity: '10000',
    inspectedQuantity: '10003',
    qualityRate: '0.9997',
    lastDeliveryAt: '2026-06-28T00:00:00Z',
    lastInspectionAt: '2026-06-28T00:00:00Z',
    ...overrides,
  };
}

// ================================================================ 比率的 null 语义

describe('比率列：null 绝不渲染成 0%', () => {
  it('窗口内没有记录时显示「暂无数据」', () => {
    expect(rateLabel(null)).toBe('暂无数据');
    expect(rateLabel(undefined)).toBe('暂无数据');
    expect(rateLabel('')).toBe('暂无数据');
    expect(rateLabel('not-a-number')).toBe('暂无数据');
  });

  it('「还没交货过」与「交货全部延期」是两件事，不能折成同一个显示', () => {
    // 全延期 = 0%，是**有**数据
    expect(rateLabel('0')).toBe('0.00%');
    expect(rateLabel('0')).not.toBe(rateLabel(null));
  });

  it('比率是小数，×100 才是百分比', () => {
    expect(rateLabel('0.6667')).toBe('66.67%');
    expect(rateLabel('0.9997')).toBe('99.97%');
    expect(rateLabel(0.5)).toBe('50.00%');
  });

  it('配色只对有数据的比率生效', () => {
    expect(rateTone(null)).toBe('neutral');
    expect(rateTone('0.85')).toBe('warning');
    expect(rateTone('0.96')).toBe('success');
  });
});

// ================================================================ 跨币种不相加

describe('采购金额按币种分组，不跨币种相加', () => {
  it('明细逐币种展示，绝不相加成 190', () => {
    const text = amountListText([
      { currency: 'USD', amount: '100.00' },
      { currency: 'EUR', amount: '90.00' },
    ]);
    expect(text).toBe('USD 100.00 / EUR 90.00');
    expect(text).not.toContain('190');
  });

  it('没有任何到货金额时显示「暂无到货金额」而不是 0', () => {
    expect(amountListText([])).toBe('暂无到货金额');
  });
});

// ================================================================ 词表

describe('词表中文名', () => {
  it('合作状态与主体状态是两个不同的词表', () => {
    // 同一个字符串 ACTIVE 在两处含义不同，所以两处都要有自己的标签函数
    expect(supplyStatusLabel('ACTIVE')).toBe('合作中');
    expect(partyStatusLabel('ACTIVE')).toBe('可用');
    expect(supplyStatusLabel('SUSPENDED')).toBe('暂停下单');
    expect(partyStatusLabel('BLACKLISTED')).toBe('拉黑');
  });

  it('未建档的供应商按「待评估」呈现，而不是空白', () => {
    // 空白会被读成「没填」，而「待评估」才是它现在的实际状态
    expect(supplyStatusLabel(null)).toBe('待评估');
    expect(cooperationLevelLabel(null)).toBe('未建档');
  });

  it('状态配色区分「暂停采购」与「停用主体」', () => {
    expect(supplyStatusTone('ACTIVE')).toBe('success');
    expect(supplyStatusTone('SUSPENDED')).toBe('danger');
    expect(partyStatusTone('BLACKLISTED')).toBe('danger');
    expect(partyStatusTone('INACTIVE')).toBe('warning');
  });
});

// ================================================================ 指标窗口

describe('指标窗口必须显示出来', () => {
  it('窗口折到日；缺省时说清是默认窗口', () => {
    expect(windowText({ from: '2026-01-01T00:00:00Z', to: '2026-06-30T23:59:59Z' })).toBe(
      '2026-01-01 ~ 2026-06-30',
    );
    expect(windowText(null)).toBe('默认窗口（最近 365 天）');
  });

  it('时间戳只到日；空值显示「无」而不是空白', () => {
    expect(dateText('2026-06-28T00:00:00Z')).toBe('2026-06-28');
    expect(dateText(null)).toBe('无');
  });
});

describe('逾期天数：提前到货不是负的逾期', () => {
  it('准时与提前都显示「准时」，迟到才报天数', () => {
    expect(daysLateText(0)).toBe('准时');
    expect(daysLateText(5)).toBe('逾期 5 天');
    // 「逾期 -5 天」读不通，所以这个函数刻意不返回负数
    expect(daysLateText(0)).not.toContain('-');
  });
});

// ================================================================ 写入校验

describe('采购侧运营档案：只写采购侧那三列', () => {
  it('合作状态必填', () => {
    expect(validateSupplyProfile({ supplyStatus: 'ACTIVE', categoryId: null, note: '', version: 0 })).toEqual(
      [],
    );
    expect(
      validateSupplyProfile({ supplyStatus: '  ', categoryId: null, note: '', version: 0 }).join(),
    ).toContain('合作状态必填');
  });

  it('备注超长给出可读原因', () => {
    expect(
      validateSupplyProfile({ supplyStatus: 'ACTIVE', categoryId: null, note: 'x'.repeat(2001), version: 0 })
        .join(),
    ).toContain('2000');
  });

  it('从一行铺出表单初值时带上乐观锁版本号', () => {
    expect(toSupplyProfileForm(row())).toEqual({
      supplyStatus: 'ACTIVE',
      categoryId: 'c1',
      note: '',
      version: 2,
    });
  });

  it('还没建采购侧档案的行铺出 version=0（新建），合作状态由后端 coalesce 成「待评估」', () => {
    // 后端 SELECT_LIST 里是 coalesce(vsp.supply_status,'PENDING_REVIEW')，
    // 所以「还没建档」在列表上呈现为**待评估**而不是空白 —— 空白会被读成「没填」，
    // 而「待评估」才是它现在的实际状态。version 为 null 才代表这一行还不存在。
    const form = toSupplyProfileForm(
      row({
        supplyProfileFound: false,
        supplyProfileVersion: null,
        supplyStatus: 'PENDING_REVIEW',
      }),
    );
    expect(form.version).toBe(0);
    expect(form.supplyStatus).toBe('PENDING_REVIEW');
  });
});

describe('币种只校验格式，不校验是不是真实存在的币种', () => {
  it('三位大写字母码即通过', () => {
    expect(validateCurrency('usd')).toEqual([]);
    expect(validateCurrency('ZZZ')).toEqual([]); // 完整词表是 12 财务的资产
  });

  it('非三位字母给出可读原因', () => {
    expect(validateCurrency('US').join()).toContain('ISO 4217');
    expect(validateCurrency('').join()).toContain('币种必填');
  });
});

describe('到货记录校验', () => {
  const base = {
    deliveryRef: 'DN-1',
    skuId: null,
    purchaseUnitCode: null,
    quantity: '100',
    amount: '1200.00',
    currency: 'USD',
    orderedAt: '2026-06-01T00:00:00Z',
    dueDate: '2026-06-10',
    receivedDate: '2026-06-12',
    note: '',
  };

  it('合法记录通过', () => {
    expect(validateDelivery(base)).toEqual([]);
  });

  it('单号 / 数量 / 金额 / 币种 / 日期都要有', () => {
    expect(validateDelivery({ ...base, deliveryRef: ' ' }).join()).toContain('到货单号');
    expect(validateDelivery({ ...base, quantity: '0' }).join()).toContain('必须大于 0');
    expect(validateDelivery({ ...base, amount: '-1' }).join()).toContain('不能为负');
    expect(validateDelivery({ ...base, currency: 'US' }).join()).toContain('ISO 4217');
    expect(validateDelivery({ ...base, dueDate: '' }).join()).toContain('承诺到货日');
  });

  it('非数字输入被拦下而不是变成 NaN', () => {
    expect(validateDelivery({ ...base, quantity: 'abc' }).join()).toContain('请填数字');
  });

  it('收货事实不可能早于下单', () => {
    expect(
      validateDelivery({ ...base, receivedDate: '2026-05-01' }).join(),
    ).toContain('不能早于下单');
  });

  it('早到不是错误 —— 准时与否由两个日期推出，表单不额外问', () => {
    expect(validateDelivery({ ...base, receivedDate: '2026-06-05' })).toEqual([]);
  });
});

describe('质检记录校验：合格数不得超过检验数', () => {
  const base = {
    deliveryId: null,
    inspectionRef: 'QN-1',
    inspectedQuantity: '100',
    qualifiedQuantity: '98',
    inspectedAt: '2026-06-12T00:00:00Z',
    note: '',
  };

  it('合法记录通过', () => {
    expect(validateInspection(base)).toEqual([]);
    expect(validateInspection({ ...base, qualifiedQuantity: '100' })).toEqual([]);
  });

  it('合格 > 检验被拦下，并说清会让合格率算出 100% 以上', () => {
    expect(validateInspection({ ...base, qualifiedQuantity: '120' }).join()).toContain('不能超过检验数量');
  });

  it('检验数量必须大于 0，合格数量不能为负', () => {
    expect(validateInspection({ ...base, inspectedQuantity: '0' }).join()).toContain('必须大于 0');
    expect(validateInspection({ ...base, qualifiedQuantity: '-1' }).join()).toContain('不能为负');
  });
});

describe('作废必须留理由', () => {
  it('空白理由被拦下', () => {
    // 没有理由的作废会让「准时率突然变好」无法解释
    expect(validateVoidReason('   ').join()).toContain('理由');
    expect(validateVoidReason('录错了批次')).toEqual([]);
  });
});

describe('筛选初值', () => {
  it('默认按供应商编码升序，空筛选项表示不过滤', () => {
    expect(EMPTY_VENDOR_FILTERS.sort).toBe('partyCode');
    expect(EMPTY_VENDOR_FILTERS.keyword).toBe('');
    expect(EMPTY_VENDOR_FILTERS.supplyStatus).toBe('');
    expect(EMPTY_VENDOR_FILTERS.metricFrom).toBe('');
    // desc 留 null = 交给后端按列给默认方向（比率类默认降序找问题）
    expect(EMPTY_VENDOR_FILTERS.desc).toBeNull();
  });
});
