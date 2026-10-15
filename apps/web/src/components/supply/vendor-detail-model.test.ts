import { describe, expect, it } from 'vitest';

import {
  EMPTY_TERMS_FORM,
  amountListText,
  creditLimitText,
  daysLateText,
  invoiceTypeLabel,
  operationDisabledReason,
  qualificationTypeLabel,
  rateLabel,
  rateTone,
  settlementMethodLabel,
  taxRateText,
  toTermsInput,
  validityLabel,
  validityTone,
  validateQualification,
  validateTerms,
  windowText,
} from './vendor-detail-model';
import type {
  VendorOperationEntry,
  VendorPurchaseAmount,
  VendorQualificationInput,
  VendorQualificationRow,
  VendorTermsInput,
} from './vendor-detail-model';

function terms(overrides: Partial<VendorTermsInput> = {}): VendorTermsInput {
  return {
    currency: 'USD',
    paymentTermsDays: 45,
    settlementMethod: 'CREDIT',
    invoiceType: 'VAT_SPECIAL',
    invoiceTitle: '某某（深圳）贸易有限公司',
    taxId: '91440300MA5EXAMPLE',
    taxRate: '0.13',
    creditLimit: '500000.00',
    depositRatio: '0',
    effectiveFrom: '2026-01-01',
    note: '',
    version: 0,
    ...overrides,
  };
}

function qualification(
  overrides: Partial<VendorQualificationInput> = {},
): VendorQualificationInput {
  return {
    qualificationType: 'BUSINESS_LICENSE',
    title: '营业执照',
    certNo: 'CERT-1',
    issuer: '市场监督管理局',
    issuedOn: '2020-01-01',
    expiresOn: '2030-01-01',
    fileKey: 'vendor-qual/license.pdf',
    fileName: 'license.pdf',
    mimeType: 'application/pdf',
    fileSizeBytes: 2048,
    note: '',
    version: 0,
    ...overrides,
  };
}

/** 问题清单里按**子串**查找（`toContain` 在数组上是元素相等，前缀匹配要用它）。 */
function hasProblem(problems: string[], text: string): boolean {
  return problems.some((p) => p.includes(text));
}

function row(overrides: Partial<VendorQualificationRow> = {}): VendorQualificationRow {
  return {
    id: 'q1',
    roleId: 'r1',
    qualificationType: 'CE',
    title: 'CE 认证',
    certNo: 'C-1',
    issuer: '机构',
    issuedOn: '2020-01-01',
    expiresOn: '2030-01-01',
    validity: 'VALID',
    daysToExpiry: 1000,
    fileKey: 'k',
    fileName: 'ce.pdf',
    mimeType: 'application/pdf',
    fileSizeBytes: 1024,
    note: '',
    version: 0,
    ...overrides,
  };
}

describe('比率列的 null 必须与 0 长得不一样', () => {
  it('null 渲染成「暂无数据」而不是 0%', () => {
    expect(rateLabel(null)).toBe('暂无数据');
    expect(rateLabel(undefined)).toBe('暂无数据');
    expect(rateLabel('')).toBe('暂无数据');
    // 「还没交货过」与「交货全部延期」是相反的两件事
    expect(rateLabel('0')).not.toBe(rateLabel(null));
    expect(rateLabel('0')).toBe('0.0%');
  });

  it('null 的配色是中性而不是警告', () => {
    expect(rateTone(null)).toBe('neutral');
    expect(rateTone('0')).toBe('negative');
    expect(rateTone('0.99')).toBe('positive');
    expect(rateTone('0.85')).toBe('neutral');
  });

  it('平均延期天数区分早到与晚到', () => {
    expect(daysLateText('-5.00')).toBe('平均早到 5.00 天');
    expect(daysLateText('3.00')).toBe('平均晚到 3.00 天');
    expect(daysLateText(null)).toBe('暂无数据');
  });
});

describe('金额与额度', () => {
  it('跨币种分列不相加', () => {
    const amounts: VendorPurchaseAmount[] = [
      { currency: 'USD', amount: '1000.00' },
      { currency: 'EUR', amount: '900.00' },
    ];
    expect(amountListText(amounts)).toBe('1000.00 USD / 900.00 EUR');
    expect(amountListText([])).toBe('暂无数据');
  });

  it('未设额度与额度为 0 是两件事', () => {
    expect(creditLimitText(null)).toContain('未设额度');
    expect(creditLimitText('0')).toContain('额度已用尽');
    expect(creditLimitText('500000.00')).toBe('500000.00');
  });

  it('税率显示成百分数', () => {
    expect(taxRateText('0.13')).toBe('13%');
    expect(taxRateText('0')).toBe('0%');
    expect(taxRateText(null)).toBe('未登记');
  });
});

describe('指标窗口与词表', () => {
  it('界面上必须显示统计窗口', () => {
    expect(windowText({ from: '2026-01-01T00:00:00Z', to: '2026-06-30T23:59:59Z' })).toBe(
      '2026-01-01 ~ 2026-06-30',
    );
    expect(windowText(null)).toBe('');
  });

  it('词表标签与后端闭集一致', () => {
    expect(settlementMethodLabel('PREPAID')).toBe('预付');
    expect(settlementMethodLabel('CREDIT')).toBe('月结 / 账期');
    expect(settlementMethodLabel('ON_DELIVERY')).toBe('货到付款');
    expect(invoiceTypeLabel('VAT_SPECIAL')).toBe('增值税专票');
    expect(qualificationTypeLabel('BUSINESS_LICENSE')).toBe('营业执照');
    expect(settlementMethodLabel(null)).toBe('未登记');
  });

  it('资质有效期四态各自分配', () => {
    expect(validityLabel(row({ validity: 'EXPIRED', daysToExpiry: -12 }))).toBe('已过期 12 天');
    expect(validityLabel(row({ validity: 'EXPIRING_SOON', daysToExpiry: 9 }))).toBe('9 天后到期');
    expect(validityLabel(row({ validity: 'VALID' }))).toContain('有效至');
    expect(validityLabel(row({ validity: 'UNKNOWN', daysToExpiry: null }))).toBe('未填到期日');
    expect(validityTone('EXPIRED')).toBe('negative');
    expect(validityTone('EXPIRING_SOON')).toBe('negative');
    expect(validityTone('UNKNOWN')).toBe('neutral');
  });
});

describe('账期与开票：付款审批的输入', () => {
  it('未建档时用空表单，版本为 0（新建）', () => {
    const input = toTermsInput(EMPTY_TERMS_FORM, '某某供应商');
    expect(input.version).toBe(0);
    expect(input.currency).toBe('USD');
  });

  it('发票抬头留空时回落到主体名，但校验先拦住', () => {
    const input = toTermsInput({ ...EMPTY_TERMS_FORM, invoiceTitle: '  ' }, '某某供应商');
    expect(input.invoiceTitle).toBe('某某供应商');
    expect(hasProblem(validateTerms({ ...input, invoiceTitle: '' }), '发票抬头不能为空')).toBe(
      true,
    );
  });

  it('自相矛盾的组合被前端先拦住（提交后才被后端 400 拒等于白填）', () => {
    expect(
      hasProblem(
        validateTerms(terms({ settlementMethod: 'PREPAID', depositRatio: '0' })),
        '结算方式是「预付」时预付比例必须大于 0',
      ),
    ).toBe(true);
    expect(
      hasProblem(
        validateTerms(terms({ invoiceType: 'VAT_SPECIAL', taxId: '' })),
        '开票方式需要税号',
      ),
    ).toBe(true);
    // 无票 / 形式发票不需要税号
    expect(validateTerms(terms({ invoiceType: 'NONE', taxId: '' }))).toEqual([]);
    expect(validateTerms(terms({ invoiceType: 'PROFORMA', taxId: '' }))).toEqual([]);
  });

  it('税率按比例校验，13 会被拒', () => {
    expect(hasProblem(validateTerms(terms({ taxRate: '13' })), '税率要填 0~1 的比例')).toBe(true);
    expect(validateTerms(terms({ taxRate: '0.13' }))).toEqual([]);
  });

  it('账期上限 365 天，币种必须是三位大写', () => {
    expect(hasProblem(validateTerms(terms({ paymentTermsDays: 400 })), '账期要填 0~365 天')).toBe(
      true,
    );
    expect(hasProblem(validateTerms(terms({ currency: 'usd' })), 'ISO 4217')).toBe(true);
    expect(
      validateTerms(terms({ currency: 'USD', paymentTermsDays: 0, depositRatio: '0' })),
    ).toEqual([]);
  });

  it('额度留空传 null 而不是 0（0 会被当成额度已用尽）', () => {
    expect(toTermsInput({ ...EMPTY_TERMS_FORM, creditLimit: '' }, 'X').creditLimit).toBeNull();
    expect(toTermsInput({ ...EMPTY_TERMS_FORM, creditLimit: '0' }, 'X').creditLimit).toBe('0');
  });
});

describe('附件资质校验', () => {
  it('必填项与日期区间', () => {
    expect(validateQualification(qualification())).toEqual([]);
    expect(
      hasProblem(validateQualification(qualification({ title: ' ' })), '证件名称不能为空'),
    ).toBe(true);
    expect(
      hasProblem(validateQualification(qualification({ fileKey: '' })), '附件文件不能为空'),
    ).toBe(true);
    expect(
      hasProblem(
        validateQualification(qualification({ issuedOn: '2026-06-01', expiresOn: '2026-01-01' })),
        '到期日不能早于签发日',
      ),
    ).toBe(true);
    // 没有到期日是常态（产地证是单次文件），不该被当成错
    expect(validateQualification(qualification({ expiresOn: null }))).toEqual([]);
  });
});

describe('操作入口', () => {
  const entry = (overrides: Partial<VendorOperationEntry> = {}): VendorOperationEntry => ({
    key: 'NEW_PURCHASE_ORDER',
    label: '新建采购单',
    available: false,
    href: null,
    reason: '6.3.1.2 采购单尚未交付，本页不造占位入口。',
    ...overrides,
  });

  it('灰掉的按钮要给出理由，而不是让运营以为系统坏了', () => {
    expect(operationDisabledReason(entry())).toContain('尚未交付');
    expect(operationDisabledReason(entry({ available: true }))).toBe('');
  });
});
