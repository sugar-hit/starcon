import { describe, expect, it } from 'vitest';
import {
  formatTaxRate,
  jurisdictionLabel,
  resolvedByLabel,
  taxEffectiveMoment,
  taxEffectiveWindow,
  taxRateDecimal,
  taxRateStatusLabel,
  taxRegimeLabel,
  taxTypeLabel,
} from './tax-rate-model';

/**
 * 12.6.1.1 税率配置前端模型的单测。
 *
 * <p>重点守住四条**界面纪律**（它们一旦被破坏，页面就会开始骗人）：
 *
 * <ol>
 *   <li><b>系统提供结构与辖区选项，不替用户决定税率</b>：模型只提供「可读名」，
 *       **不提供任何「推荐税率」** —— 税率是租户自己的税务事实，
 *       系统替用户默认一个税率就是替用户记账；</li>
 *   <li><b>命中不到 ≠ 0%</b>：命中不到时后端直接报错，模型**不提供**任何
 *       「没有税率就显示 0%」的措辞或兜底；</li>
 *   <li><b>税率只做单位换算</b>：小数率 → 百分数，不做任何业务算术；</li>
 *   <li>可读名覆盖**全部词表取值**，且不认识的原样返回（新增取值时页面不会显示 undefined）。</li>
 * </ol>
 */

describe('税种的可读名', () => {
  it('蓝图点名的四个税种各自有可读名，不认识的原样返回', () => {
    expect(taxTypeLabel('SALES_TAX')).toBe('目的国/地区销售税率');
    expect(taxTypeLabel('VAT')).toBe('增值税');
    expect(taxTypeLabel('IOSS')).toBe('IOSS 适用税率');
    expect(taxTypeLabel('WITHHOLDING')).toBe('代扣代缴税');
    expect(taxTypeLabel('SOMETHING_NEW')).toBe('SOMETHING_NEW');
    expect(taxTypeLabel(null)).toBe('');
  });

  it('不提供任何「推荐税率」—— 系统不替用户决定税务事实', () => {
    const labels = ['SALES_TAX', 'VAT', 'IOSS', 'WITHHOLDING'].map((t) => taxTypeLabel(t));
    for (const label of labels) {
      expect(label).not.toContain('推荐');
      expect(label).not.toContain('默认');
      expect(label).not.toContain('建议');
    }
  });
});

describe('状态与命中方式的可读名', () => {
  it('三种状态各有可读名', () => {
    expect(taxRateStatusLabel('ACTIVE')).toBe('生效中');
    expect(taxRateStatusLabel('SUPERSEDED')).toBe('已被取代');
    expect(taxRateStatusLabel('ARCHIVED')).toBe('已撤下');
    expect(taxRateStatusLabel('SOMETHING_NEW')).toBe('SOMETHING_NEW');
  });

  it('取税率命中方式各有可读名', () => {
    expect(resolvedByLabel('SHOP_BINDING')).toBe('按店铺站点绑定');
    expect(resolvedByLabel('JURISDICTION')).toBe('按辖区取税率');
    expect(resolvedByLabel('SOMETHING_NEW')).toBe('SOMETHING_NEW');
  });

  it('辖区的税制各有可读名', () => {
    expect(taxRegimeLabel('VAT')).toBe('增值税');
    expect(taxRegimeLabel('GST')).toBe('商品与服务税');
    expect(taxRegimeLabel('SALES_TAX')).toBe('销售税');
    expect(taxRegimeLabel('CONSUMPTION_TAX')).toBe('消费税');
    expect(taxRegimeLabel('NONE')).toBe('无');
    expect(taxRegimeLabel('SOMETHING_NEW')).toBe('SOMETHING_NEW');
  });
});

describe('税率展示只做单位换算', () => {
  it('小数率换算成百分数', () => {
    expect(formatTaxRate(0.19)).toBe('19.00%');
    expect(formatTaxRate(0.07)).toBe('7.00%');
    expect(formatTaxRate(0.0725)).toBe('7.25%');
    expect(formatTaxRate(0)).toBe('0.00%');
  });

  it('原始小数原样展示，让人看到「19% = 0.19」', () => {
    expect(taxRateDecimal(0.19)).toBe('0.19');
    expect(taxRateDecimal(0.0725)).toBe('0.0725');
  });

  it('🔴 命中不到时显示破折号，不显示 0.00%', () => {
    expect(formatTaxRate(null)).toBe('—');
    expect(formatTaxRate(undefined)).toBe('—');
    expect(formatTaxRate(null)).not.toContain('0');
    expect(taxRateDecimal(null)).toBe('—');
  });

  it('0% 照实显示 —— 0% 是真实的税率，不是「没有数据」', () => {
    expect(formatTaxRate(0)).toBe('0.00%');
  });
});

describe('生效区间', () => {
  it('结束时间为空表示一直生效，不是「没填」', () => {
    expect(taxEffectiveWindow('2026-01-01T00:00:00Z', null)).toBe('2026-01-01 起（一直生效）');
  });

  it('有结束时间时给出区间', () => {
    expect(taxEffectiveWindow('2026-01-01T00:00:00Z', '2026-07-01T00:00:00Z')).toBe(
      '2026-01-01 → 2026-07-01',
    );
  });

  it('生效边界是时刻不是日期 —— 只显示日期会把「哪一版生效」说错', () => {
    expect(taxEffectiveMoment('2026-07-01T08:30:00Z')).toBe('2026-07-01 08:30');
    expect(taxEffectiveMoment(null)).toBe('—');
  });
});

describe('辖区可读名', () => {
  it('有名称用名称，没名称退回编码', () => {
    expect(jurisdictionLabel('德国', 'EU-DE')).toBe('德国');
    expect(jurisdictionLabel('', 'EU-DE')).toBe('EU-DE');
    expect(jurisdictionLabel(null, null)).toBe('');
  });
});
