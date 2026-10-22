import { describe, expect, it } from 'vitest';
import {
  coverageStateLabel,
  effectiveStatusLabel,
  effectiveStatusTone,
  emptyState,
  emptyStateKind,
  filingCycleLabel,
  gapTypeLabel,
  severityLabel,
  severityTone,
  subjectText,
  vatStatusLabel,
} from './tax-registration-model';

/**
 * 16.3.1.1 税号与辖区 前端模型的**展示口径**单测。
 *
 * <p>🔴 这组用例守的是页面最容易撒谎的三处：
 *
 * <ol>
 *   <li><b>「读不到」不能被渲染成空白或「未注册」</b>；</li>
 *   <li><b>「没记有效期」与「已过期」是两种状态</b>，显示上不能混；</li>
 *   <li><b>「推不出要求」不等于「都齐了」</b>：空态文案不许把两者说成一回事。</li>
 * </ol>
 */
describe('16.3.1.1 税号与辖区 · 展示口径', () => {
  it('缺失项类型有人话，且每个后端类型都有兜底', () => {
    expect(gapTypeLabel('JURISDICTION_NOT_REGISTERED')).toBe('未注册该辖区');
    expect(gapTypeLabel('VAT_NUMBER_MISSING')).toBe('缺 VAT 号（登记号）');
    expect(gapTypeLabel('EPR_NUMBER_MISSING')).toBe('缺 EPR 注册号');
    expect(gapTypeLabel('REGISTRATION_EXPIRED')).toBe('登记已过有效期');
    expect(gapTypeLabel('REGISTRATION_EXPIRING_SOON')).toBe('登记即将到期');
    expect(gapTypeLabel('REGISTRATION_VALIDITY_UNKNOWN')).toBe('没记有效期（判不了）');
    expect(gapTypeLabel('SOMETHING_NEW')).toBe('SOMETHING_NEW');
  });

  it('严重度有色调区分：高 = danger，中 = warning', () => {
    expect(severityTone('HIGH')).toBe('danger');
    expect(severityTone('MEDIUM')).toBe('warning');
    expect(severityLabel('HIGH')).toContain('合规炸弹');
    expect(severityLabel('MEDIUM')).toContain('要补');
  });

  it('VAT 状态的人话区分「豁免」与「未知」', () => {
    expect(vatStatusLabel('REGISTERED')).toBe('已注册');
    expect(vatStatusLabel('EXEMPT')).toBe('豁免（无本地 VAT 号）');
    expect(vatStatusLabel('UNKNOWN')).toBe('未知（还没录入）');
  });

  it('申报周期未登记时明说「未登记」，不是「按月」', () => {
    expect(filingCycleLabel('MONTHLY')).toBe('按月');
    expect(filingCycleLabel('')).toBe('未登记');
  });

  it('生效状态区分生效中 / 已过期 / 已撤下', () => {
    expect(effectiveStatusLabel('ACTIVE')).toBe('生效中');
    expect(effectiveStatusLabel('EXPIRED')).toBe('已过期');
    expect(effectiveStatusLabel('ARCHIVED')).toBe('已撤下');
    expect(effectiveStatusTone('EXPIRED')).toBe('danger');
    expect(effectiveStatusTone('ACTIVE')).toBe('success');
  });

  it('覆盖状态区分四种情形', () => {
    expect(coverageStateLabel('REGISTERED')).toBe('已登记');
    expect(coverageStateLabel('EXPIRED')).toBe('已过期');
    expect(coverageStateLabel('NOT_REGISTERED')).toBe('未注册');
    expect(coverageStateLabel('MISSING')).toBe('没有登记');
  });

  it('🔴 主体读不到时显示「读不到」并带原因，**不是**空白也不是「未注册」', () => {
    const text = subjectText('LEGAL_ENTITY', '', '', false, 'identity 服务不可达');
    expect(text).toContain('读不到');
    expect(text).toContain('identity 服务不可达');
    expect(text).not.toContain('未注册');
    expect(text.trim()).not.toBe('');
  });

  it('主体读得到时显示名称；读不到且没有原因时也给「读不到」', () => {
    expect(subjectText('SHOP', 'S-1', '德国店', true, '')).toBe('德国店');
    expect(subjectText('LEGAL_ENTITY', 'DE-ENTITY', '德国主体', true, '')).toBe('法人 德国主体');
    expect(subjectText('LEGAL_ENTITY', '', '', false, '')).toBe('读不到');
  });

  it('🔴 空态三态各不相同，且都不把「推不出」说成「都齐了」', () => {
    const noShop = emptyState('NO_SHOP');
    const noMatch = emptyState('NO_FILTER_MATCH');
    const noReg = emptyState('NO_REGISTRATION');
    expect(noShop.title).not.toBe(noMatch.title);
    expect(noMatch.title).not.toBe(noReg.title);
    expect(noReg.description).toContain('税务登记');
    expect(noMatch.description).not.toContain('一条登记都没有');
  });

  it('空态判定：没有店铺 > 一条登记都没有 > 筛选无结果', () => {
    expect(emptyStateKind(0, 0, false)).toBe('NO_SHOP');
    expect(emptyStateKind(3, 0, false)).toBe('NO_REGISTRATION');
    expect(emptyStateKind(3, 0, true)).toBe('NO_FILTER_MATCH');
    expect(emptyStateKind(3, 5, true)).toBe('NO_FILTER_MATCH');
  });
});
