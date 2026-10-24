import { describe, expect, it } from 'vitest';
import {
  countryCoverageTone,
  documentTypeLabel,
  formatMoney,
  formatQuantity,
  formatTaxRate,
  invoiceNumberLabel,
  invoiceOptions,
  invoiceStatusLabel,
  platformSyncStatusLabel,
  sequencePeriodLabel,
  sequenceScopeLabel,
} from './invoice-model';

/**
 * 12.6.2.1 发票管理前端模型的单测。
 *
 * <p>重点守住四条**界面纪律**（它们一旦被破坏，页面就会开始骗人）：
 *
 * <ol>
 *   <li><b>调用方填不出假的税额</b>：模型**不提供**任何「自己算税额」的入口 ——
 *       税率由服务端实时取，税额由数据库生成列算；</li>
 *   <li><b>命中不到 ≠ 0%</b>：取税率失败时后端直接报错，模型**不提供**任何
 *       「没有税率就显示 0%」的兜底；</li>
 *   <li><b>多币种绝不相加</b>：金额的展示**永远带币种**，模型**不提供**任何
 *       「把几个币种加在一起」的函数；</li>
 *   <li><b>红冲方向不丢</b>：贷项行的数量是负数，展示时保留符号（取绝对值会把
 *       「红冲了多少」说成「有多少」）。</li>
 * </ol>
 */

describe('单据类型的可读名', () => {
  it('蓝图点名的两种单据各有可读名，不认识的原样返回', () => {
    expect(documentTypeLabel('INVOICE')).toBe('销售发票');
    expect(documentTypeLabel('CREDIT_NOTE')).toBe('贷项通知单');
    expect(documentTypeLabel('SOMETHING_NEW')).toBe('SOMETHING_NEW');
    expect(documentTypeLabel(null)).toBe('');
  });
});

describe('发票状态的可读名', () => {
  it('蓝图点名的四个状态（草稿/开具/作废/红冲）各有可读名', () => {
    expect(invoiceStatusLabel('DRAFT')).toBe('草稿');
    expect(invoiceStatusLabel('ISSUED')).toBe('已开具');
    expect(invoiceStatusLabel('VOID')).toBe('已作废');
    expect(invoiceStatusLabel('REVERSED')).toBe('已红冲');
    expect(invoiceStatusLabel('SOMETHING_NEW')).toBe('SOMETHING_NEW');
    expect(invoiceStatusLabel(null)).toBe('');
  });
});

describe('平台对接状态的可读名', () => {
  it('四种对接状态各有可读名', () => {
    expect(platformSyncStatusLabel('NOT_SUBMITTED')).toBe('未提交');
    expect(platformSyncStatusLabel('SUBMITTED')).toBe('已提交');
    expect(platformSyncStatusLabel('ACKNOWLEDGED')).toBe('平台已确认');
    expect(platformSyncStatusLabel('FAILED')).toBe('提交失败');
    expect(platformSyncStatusLabel('SOMETHING_NEW')).toBe('SOMETHING_NEW');
  });
});

describe('编号规则的可读名', () => {
  it('编号范围与期间口径各有可读名', () => {
    expect(sequenceScopeLabel('TENANT')).toBe('全租户一个号段');
    expect(sequenceScopeLabel('COUNTRY')).toBe('按目的国分号段');
    expect(sequenceScopeLabel('PLATFORM')).toBe('按平台分号段');
    expect(sequenceScopeLabel('SOMETHING_NEW')).toBe('SOMETHING_NEW');

    expect(sequencePeriodLabel('NONE')).toBe('不按期间');
    expect(sequencePeriodLabel('MONTHLY')).toBe('按月');
    expect(sequencePeriodLabel('YEARLY')).toBe('按年');
    expect(sequencePeriodLabel('SOMETHING_NEW')).toBe('SOMETHING_NEW');
  });
});

describe('金额展示永远带币种', () => {
  it('金额带币种，避免有人把两个币种的数字抄到一张表里相加', () => {
    expect(formatMoney(23.8, 'EUR')).toBe('23.80 EUR');
    expect(formatMoney(100, 'USD')).toBe('100.00 USD');
    expect(formatMoney(0, 'GBP')).toBe('0.00 GBP');
  });

  it('🔴 缺金额时是破折号，不是 0 —— 「没有金额」和「金额是 0」是两件事', () => {
    expect(formatMoney(null, 'EUR')).toBe('—');
    expect(formatMoney(undefined, 'EUR')).toBe('—');
    expect(formatMoney(null, 'EUR')).not.toContain('0');
  });

  it('没有币种时只显示数字，不臆造币种', () => {
    expect(formatMoney(12.5, null)).toBe('12.50');
    expect(formatMoney(12.5, '')).toBe('12.50');
  });
});

describe('数量展示保留红冲方向', () => {
  it('🔴 贷项行的负数量保留符号 —— 取绝对值会把「红冲了多少」说成「有多少」', () => {
    expect(formatQuantity(-3)).toContain('-');
    expect(formatQuantity(3)).toBe('3');
  });

  it('缺数量时是破折号', () => {
    expect(formatQuantity(null)).toBe('—');
    expect(formatQuantity(undefined)).toBe('—');
  });
});

describe('税率展示只做单位换算', () => {
  it('小数率换算成百分数', () => {
    expect(formatTaxRate(0.19)).toBe('19.00%');
    expect(formatTaxRate(0.0725)).toBe('7.25%');
    expect(formatTaxRate(0)).toBe('0.00%');
  });

  it('🔴 取不到税率时显示破折号，不显示 0.00%', () => {
    expect(formatTaxRate(null)).toBe('—');
    expect(formatTaxRate(undefined)).toBe('—');
    expect(formatTaxRate(null)).not.toContain('0');
  });
});

describe('发票号的展示', () => {
  it('草稿显示「未编号（开具时分配）」而不是破折号', () => {
    expect(invoiceNumberLabel({ invoiceNumber: null, status: 'DRAFT' })).toBe(
      '未编号（开具时分配）',
    );
  });

  it('已开具的单据显示真编号', () => {
    expect(invoiceNumberLabel({ invoiceNumber: 'INV-DE-000001', status: 'ISSUED' })).toBe(
      'INV-DE-000001',
    );
  });

  it('已作废 / 已红冲但没有编号时是破折号 —— 那是异常，不是「还没开具」', () => {
    expect(invoiceNumberLabel({ invoiceNumber: null, status: 'VOID' })).toBe('—');
    expect(invoiceNumberLabel({ invoiceNumber: null, status: 'REVERSED' })).toBe('—');
  });
});

describe('各国覆盖的结论', () => {
  it('模板与税率都到位才是 success', () => {
    expect(countryCoverageTone({ templateConfigured: true, taxRateResolvable: true })).toBe(
      'success',
    );
  });

  it('有模板没税率是 warning —— 差一步就能开票', () => {
    expect(countryCoverageTone({ templateConfigured: true, taxRateResolvable: false })).toBe(
      'warning',
    );
  });

  it('没模板就是 danger —— 「按模板生成」在这里落空', () => {
    expect(countryCoverageTone({ templateConfigured: false, taxRateResolvable: true })).toBe(
      'danger',
    );
    expect(countryCoverageTone({ templateConfigured: false, taxRateResolvable: false })).toBe(
      'danger',
    );
  });
});

describe('options 收窄', () => {
  it('正常形状原样取出', () => {
    const parsed = invoiceOptions({
      documentTypes: ['INVOICE', 'CREDIT_NOTE'],
      statuses: ['DRAFT', 'ISSUED', 'VOID', 'REVERSED'],
      platformSyncStatuses: ['NOT_SUBMITTED'],
      currencies: ['EUR', 'USD'],
      templates: [{ code: 'TPL-DE', name: '德国模板' }],
    });
    expect(parsed.documentTypes).toEqual(['INVOICE', 'CREDIT_NOTE']);
    expect(parsed.currencies).toEqual(['EUR', 'USD']);
    expect(parsed.templates).toEqual([{ code: 'TPL-DE', name: '德国模板' }]);
  });

  it('🔴 形状不对的条目直接丢掉，不塞默认值', () => {
    const parsed = invoiceOptions({
      documentTypes: ['INVOICE', 42, null, 'CREDIT_NOTE'],
      currencies: 'not-an-array',
      templates: [{ name: '没有编码' }, { code: 'TPL-DE', name: '德国模板' }, null],
    });
    expect(parsed.documentTypes).toEqual(['INVOICE', 'CREDIT_NOTE']);
    expect(parsed.currencies).toEqual([]);
    // 没有 code 的模板与 null 都被丢掉 —— 把坏的模板代码当成可用模板比少一个选项糟糕得多。
    expect(parsed.templates).toEqual([{ code: 'TPL-DE', name: '德国模板' }]);
  });

  it('响应缺失时给出空形状，不炸', () => {
    const parsed = invoiceOptions(undefined);
    expect(parsed.documentTypes).toEqual([]);
    expect(parsed.statuses).toEqual([]);
    expect(parsed.templates).toEqual([]);
  });
});
