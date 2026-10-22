import { describe, expect, it } from 'vitest';
import {
  countryStatusLabel,
  countryStatusTone,
  emptyState,
  emptyStateKind,
  feeStatusLabel,
  filingCycleLabel,
  filingStatusLabel,
  filingStatusTone,
  groupTotalsByCurrency,
  paidByLabel,
  reasonLabel,
  reasonTone,
  regimeLabel,
  statusTone,
  validityKindLabel,
} from './epr-registration-model';

/**
 * 16.3.2.2 EPR/包装法 前端模型的**展示口径**单测。
 *
 * <p>🔴 这组用例守的是页面最容易撒谎的四处：
 *
 * <ol>
 *   <li><b>「不适用」与「未注册」必须分开显示</b> —— 混为一谈会把不该拦的全拦了；</li>
 *   <li><b>「判不了」是第三态</b>：仍然不放行，但显示上不能与「明确未注册」混；</li>
 *   <li><b>金额按币种分组</b>：前端**不许**把不同币种直接相加；</li>
 *   <li><b>空态三态各不相同</b>，且不许把「没登记」说成「没问题」。</li>
 * </ol>
 */
describe('16.3.2.2 EPR / 包装法 · 展示口径', () => {
  it('判定原因有人话，且每个后端原因都有兜底', () => {
    expect(reasonLabel('OK')).toBe('已覆盖');
    expect(reasonLabel('NOT_APPLICABLE')).toBe('不适用');
    expect(reasonLabel('EXEMPTED')).toBe('不适用（已声明）');
    expect(reasonLabel('NOT_REGISTERED')).toBe('未注册');
    expect(reasonLabel('EXPIRED')).toBe('登记已过有效期');
    expect(reasonLabel('VALIDITY_UNKNOWN')).toBe('有效期判不了');
    expect(reasonLabel('NOT_EFFECTIVE_YET')).toBe('当前没有生效的登记版本');
    expect(reasonLabel('DISABLED')).toBe('登记已停用');
    expect(reasonLabel('CATEGORY_NOT_COVERED')).toBe('适用品类没覆盖');
    expect(reasonLabel('SOMETHING_NEW')).toBe('SOMETHING_NEW');
  });

  it('🔴 色调把「不适用」与「未注册」分开：前者中性、后者危险', () => {
    expect(reasonTone('OK')).toBe('success');
    expect(reasonTone('NOT_APPLICABLE')).toBe('neutral');
    expect(reasonTone('EXEMPTED')).toBe('neutral');
    expect(reasonTone('NOT_REGISTERED')).toBe('danger');
    expect(reasonTone('EXPIRED')).toBe('danger');
    expect(reasonTone('CATEGORY_NOT_COVERED')).toBe('danger');
  });

  it('🔴 「判不了」是警告色，不是危险色 —— 它与「明确违规」不是一回事', () => {
    expect(reasonTone('VALIDITY_UNKNOWN')).toBe('warning');
    expect(reasonTone('NOT_EFFECTIVE_YET')).toBe('warning');
    expect(reasonTone('VALIDITY_UNKNOWN')).not.toBe(reasonTone('NOT_REGISTERED'));
  });

  it('制度有人话，四种制度都认得', () => {
    expect(regimeLabel('LUCID')).toBe('德国 LUCID 包装登记');
    expect(regimeLabel('PACKAGING_REGISTRATION')).toBe('包装注册');
    expect(regimeLabel('BATTERY')).toBe('电池法');
    expect(regimeLabel('WEEE')).toBe('电子废弃物（WEEE）');
    expect(regimeLabel('SOMETHING_NEW')).toBe('SOMETHING_NEW');
  });

  it('申报周期未登记时明说「未登记」，不是「按月」', () => {
    expect(filingCycleLabel('MONTHLY')).toBe('按月');
    expect(filingCycleLabel('QUARTERLY')).toBe('按季');
    expect(filingCycleLabel('ANNUAL')).toBe('按年');
    expect(filingCycleLabel('ONE_TIME')).toBe('一次性');
    expect(filingCycleLabel('')).toBe('未登记');
  });

  it('有效期形态区分「没记有效期（判不了）」与「长期有效」', () => {
    expect(validityKindLabel('FIXED')).toBe('有效期到某一天');
    expect(validityKindLabel('OPEN_ENDED')).toBe('长期有效（未设失效日）');
    expect(validityKindLabel('UNKNOWN')).toBe('没记有效期（判不了）');
    expect(validityKindLabel('UNKNOWN')).not.toBe(validityKindLabel('OPEN_ENDED'));
  });

  it('申报期状态区分「在期内」「不在期内」「一次性」「判不了」', () => {
    expect(filingStatusLabel('IN_PERIOD')).toBe('当前在申报期内');
    expect(filingStatusLabel('OUT_OF_PERIOD')).toBe('当前不在申报期内');
    expect(filingStatusLabel('NOT_RECURRING')).toBe('一次性申报（无周期）');
    expect(filingStatusLabel('UNKNOWN')).toBe('没记申报月份（判不了）');
    expect(filingStatusTone('IN_PERIOD')).toBe('success');
    expect(filingStatusTone('OUT_OF_PERIOD')).toBe('warning');
    expect(filingStatusTone('UNKNOWN')).toBe('neutral');
  });

  it('登记状态：启用 / 停用', () => {
    expect(statusTone('ACTIVE')).toBe('success');
    expect(statusTone('DISABLED')).toBe('danger');
  });

  it('按国家的合规状态区分四种', () => {
    expect(countryStatusLabel('COMPLIANT')).toBe('已覆盖');
    expect(countryStatusLabel('NON_COMPLIANT')).toBe('未覆盖（阻断刊登）');
    expect(countryStatusLabel('INDETERMINATE')).toBe('判不了（仍不放行）');
    expect(countryStatusLabel('NOT_APPLICABLE')).toBe('不适用（该国不要求 EPR）');
    expect(countryStatusTone('COMPLIANT')).toBe('success');
    expect(countryStatusTone('NON_COMPLIANT')).toBe('danger');
    expect(countryStatusTone('INDETERMINATE')).toBe('warning');
    expect(countryStatusTone('NOT_APPLICABLE')).toBe('neutral');
  });

  it('费用状态与承担方有人话', () => {
    expect(feeStatusLabel('RECORDED')).toBe('已记录（未付）');
    expect(feeStatusLabel('PAID')).toBe('已付');
    expect(feeStatusLabel('WAIVED')).toBe('已豁免');
    expect(paidByLabel('SELLER')).toBe('卖家承担');
    expect(paidByLabel('PLATFORM')).toBe('平台承担');
    expect(paidByLabel('SERVICE_PROVIDER')).toBe('服务商承担');
  });

  it('🔴 金额按币种分组：前端**不许**把不同币种直接相加', () => {
    const grouped = groupTotalsByCurrency([
      { currency: 'EUR', total: '120.7500', count: 2 },
      { currency: 'USD', total: '50.0000', count: 1 },
    ]);
    expect(grouped).toHaveLength(2);
    expect(grouped[0]).toEqual({ currency: 'EUR', total: '120.7500' });
    expect(grouped[1]).toEqual({ currency: 'USD', total: '50.0000' });
    // 🔴 没有「合计」这个字段 —— 不同币种加起来不是钱
    expect(grouped).not.toHaveProperty('total');
  });

  it('🔴 空态三态各不相同，且都不把「没登记」说成「没问题」', () => {
    const noShop = emptyState('NO_SHOP');
    const noMatch = emptyState('NO_FILTER_MATCH');
    const noReg = emptyState('NO_REGISTRATION');
    expect(noShop.title).not.toBe(noMatch.title);
    expect(noMatch.title).not.toBe(noReg.title);
    expect(noReg.description).toContain('EPR 登记');
    expect(noMatch.description).not.toContain('一条登记都没有');
    expect(noShop.description).not.toContain('没问题');
  });

  it('空态判定：没有店铺 > 一条登记都没有 > 筛选无结果', () => {
    expect(emptyStateKind(0, 0, false)).toBe('NO_SHOP');
    expect(emptyStateKind(3, 0, false)).toBe('NO_REGISTRATION');
    expect(emptyStateKind(3, 0, true)).toBe('NO_FILTER_MATCH');
    expect(emptyStateKind(3, 5, true)).toBe('NO_FILTER_MATCH');
  });
});
