import { describe, expect, it } from 'vitest';
import {
  accountAmountBasisLabel,
  accountBalanceDirectionLabel,
  accountMappingBalanceText,
  accountSubjectCategoryLabel,
  accountSubjectDetailLabel,
  accountSubjectEventText,
  accountSubjectStatusLabel,
  accountSubjectStatusTone,
  accountTaxTreatmentLabel,
  accountTemplateApplySummary,
  formatAccountTaxRate,
} from './account-subject-model';

/**
 * 12.5.1.1 会计科目的前端模型断言。
 *
 * <p>这几条守的是**界面纪律**，不是排版：
 *
 * <ul>
 *   <li>蓝图点名的**五个类别**（资产 / 负债 / 权益 / 成本 / 损益）各有一个可读名；
 *   <li>余额方向与类别**各说各的** —— 累计折旧是资产类却记贷方余额，
 *       所以两者不联动、可读名互不推导；
 *   <li>税率是 0.13 = 13% 的**分数**，显示时换算成百分数，但**不固定税率**时要说「未固定税率」
 *       而不是 0%；
 *   <li>「借贷齐备」由**服务端**判定，页面原样显示服务端给的行数与结论；
 *   <li>模板导入**必须逐条报出被跳过的编码** —— 「导入了 20 个」不说清哪几个没进来，
 *       现场会以为模板全导进去了；
 *   <li>变更留痕要能看出「改了哪个字段、从什么改成什么」。
 * </ul>
 */
describe('12.5.1.1 会计科目 · 前端模型', () => {
  it('五个科目类别各有一个可读名，未知值原样返回', () => {
    expect(accountSubjectCategoryLabel('ASSET')).toBe('资产');
    expect(accountSubjectCategoryLabel('LIABILITY')).toBe('负债');
    expect(accountSubjectCategoryLabel('EQUITY')).toBe('权益');
    expect(accountSubjectCategoryLabel('COST')).toBe('成本');
    expect(accountSubjectCategoryLabel('PROFIT_LOSS')).toBe('损益');
    expect(accountSubjectCategoryLabel('UNKNOWN')).toBe('UNKNOWN');
  });

  it('余额方向独立于类别 —— 累计折旧是资产类却记贷方余额', () => {
    expect(accountBalanceDirectionLabel('DEBIT')).toBe('借方');
    expect(accountBalanceDirectionLabel('CREDIT')).toBe('贷方');
    expect(accountBalanceDirectionLabel('UNKNOWN')).toBe('UNKNOWN');
    // 可读名不得由类别推导：资产不必就是借方。
    expect(accountBalanceDirectionLabel('CREDIT')).not.toBe(
      accountSubjectCategoryLabel('ASSET'),
    );
  });

  it('税务属性各有一个可读名', () => {
    expect(accountTaxTreatmentLabel('TAXABLE')).toBe('应税');
    expect(accountTaxTreatmentLabel('EXEMPT')).toBe('免税');
    expect(accountTaxTreatmentLabel('ZERO_RATED')).toBe('零税率');
    expect(accountTaxTreatmentLabel('NON_TAXABLE')).toBe('不征税');
    expect(accountTaxTreatmentLabel('REVERSE_CHARGE')).toBe('视同应税');
    expect(accountTaxTreatmentLabel('UNKNOWN')).toBe('UNKNOWN');
  });

  it('科目性质区分明细与汇总 —— 汇总科目不能被凭证直接记账', () => {
    expect(accountSubjectDetailLabel(true)).toBe('明细科目');
    expect(accountSubjectDetailLabel(false)).toBe('汇总科目');
  });

  it('科目状态：在用=成功，停用=中性（停用不是删除）', () => {
    expect(accountSubjectStatusLabel('ACTIVE')).toBe('在用');
    expect(accountSubjectStatusLabel('DISABLED')).toBe('停用');
    expect(accountSubjectStatusTone('ACTIVE')).toBe('success');
    expect(accountSubjectStatusTone('DISABLED')).toBe('neutral');
  });

  it('税率是分数（0.13 = 13%）；没有固定税率时说「未固定税率」而不是 0%', () => {
    expect(formatAccountTaxRate(0.13)).toBe('13%');
    expect(formatAccountTaxRate(0.06)).toBe('6%');
    expect(formatAccountTaxRate(0)).toBe('0%');
    expect(formatAccountTaxRate(null)).toBe('未固定税率');
    expect(formatAccountTaxRate(undefined)).toBe('未固定税率');
  });

  it('金额口径给的是「取哪个量」，不是金额本身', () => {
    expect(accountAmountBasisLabel('ORDER_ITEM_AMOUNT')).toBe('商品金额');
    expect(accountAmountBasisLabel('ITEM_COST')).toBe('商品成本');
    expect(accountAmountBasisLabel('SHIPPING_AMOUNT')).toBe('运费');
    expect(accountAmountBasisLabel('TAX_AMOUNT')).toBe('税额');
    expect(accountAmountBasisLabel('PLATFORM_FEE')).toBe('平台费用');
    expect(accountAmountBasisLabel('REFUND_AMOUNT')).toBe('退款金额');
    expect(accountAmountBasisLabel('ADJUSTMENT_AMOUNT')).toBe('调整金额');
    expect(accountAmountBasisLabel('OTHER')).toBe('其他');
    expect(accountAmountBasisLabel('UNKNOWN')).toBe('UNKNOWN');
  });

  it('借贷齐备由服务端判定：页面原样显示行数与结论', () => {
    expect(accountMappingBalanceText(1, 1, true)).toContain('借贷齐备');
    expect(accountMappingBalanceText(1, 1, true)).toContain('借方 1 条');
    expect(accountMappingBalanceText(1, 1, true)).toContain('贷方 1 条');
    expect(accountMappingBalanceText(1, 0, false)).toContain('借贷不齐备');
    expect(accountMappingBalanceText(1, 0, false)).toContain('贷方 0 条');
  });

  it('模板导入必须逐条报出被跳过的编码', () => {
    const summary = accountTemplateApplySummary({
      templateKey: 'CN_STANDARD_V1',
      created: 20,
      skipped: 1,
      skippedCodes: ['1001'],
    });
    expect(summary).toContain('CN_STANDARD_V1');
    expect(summary).toContain('导入 20 个科目');
    expect(summary).toContain('跳过 1 个');
    expect(summary).toContain('1001');
  });

  it('没有跳过时不说「跳过」', () => {
    const summary = accountTemplateApplySummary({
      templateKey: 'CN_STANDARD_V1',
      created: 32,
      skipped: 0,
      skippedCodes: [],
    });
    expect(summary).toContain('导入 32 个科目');
    expect(summary).not.toContain('跳过');
  });

  it('变更留痕能看出「改了哪个字段、从什么改成什么」', () => {
    expect(
      accountSubjectEventText({
        eventType: 'FIELD_CHANGED',
        fieldName: 'subject_name',
        fromValue: '库存现金',
        toValue: '库存现金（改过名）',
        reason: '修改科目字段',
      }),
    ).toContain('subject_name');
    expect(
      accountSubjectEventText({
        eventType: 'FIELD_CHANGED',
        fieldName: 'subject_name',
        fromValue: '库存现金',
        toValue: '库存现金（改过名）',
        reason: '修改科目字段',
      }),
    ).toContain('库存现金（改过名）');
  });

  it('没有字段名的留痕显示理由，而不是空字符串', () => {
    expect(
      accountSubjectEventText({
        eventType: 'CREATED',
        fieldName: '',
        fromValue: '',
        toValue: '',
        reason: '新建科目 1001',
      }),
    ).toBe('新建科目 1001');
  });
});
