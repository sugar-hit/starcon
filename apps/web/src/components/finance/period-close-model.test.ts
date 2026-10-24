import { describe, expect, it } from 'vitest';
import {
  periodCloseBlockingSummary,
  periodCloseCarryForwardGuard,
  periodCloseCheckCodeLabel,
  periodCloseClassificationLabel,
  periodCloseClassificationTone,
  periodCloseCurrencyGroupText,
  periodCloseExecutionStatusLabel,
  periodCloseExecutionStatusTone,
  periodCloseItemText,
  periodCloseOutcomeText,
  periodCloseOutcomeTone,
  periodCloseResultLabel,
  periodCloseResultTone,
} from './period-close-model';

/**
 * 12.5.1.3 关账的前端模型断言。
 *
 * <p>这几条守的是**界面纪律**，不是排版：
 *
 * <ul>
 *   <li>🔴 **「不可判定」不能渲染成绿色** —— 上游 12.3.2.1 / 12.3.2.2 未交付时，
 *       「未对账差异」判不了；把它说成「通过」就是撒谎；</li>
 *   <li>🔴 **阻断项是 danger、可结转未清项是 warning** —— 两者混成一句「有问题」
 *       就等于没防错；</li>
 *   <li>🔴 **审批中 / 已驳回都要明说「期间仍然开放」** —— 只有「已关账」才真的锁定；</li>
 *   <li>🔴 **多币种绝不相加** —— 金额一行只说一种币种，且**不产出**跨币种的合计；</li>
 *   <li>🔴 **例外下调必须有依据** —— 规则判定不是阻断项 / 已下调过，都不许再下调；
 *       阻断项必须写依据才能下调。</li>
 * </ul>
 */
describe('12.5.1.3 关账 · 前端模型', () => {
  it('四类未处理事项各有一个可读名，未知值原样返回', () => {
    expect(periodCloseCheckCodeLabel('UNRECONCILED_DIFFERENCE')).toBe('未对账差异');
    expect(periodCloseCheckCodeLabel('UNCARRIED_COST')).toBe('未结转成本');
    expect(periodCloseCheckCodeLabel('UNSETTLED_PAYMENT')).toBe('未核销款项');
    expect(periodCloseCheckCodeLabel('UNPOSTED_VOUCHER')).toBe('未过账凭证');
    expect(periodCloseCheckCodeLabel('UNKNOWN')).toBe('UNKNOWN');
  });

  it('分类各有一个可读名', () => {
    expect(periodCloseClassificationLabel('BLOCKING')).toBe('阻断项');
    expect(periodCloseClassificationLabel('CARRYWARD')).toBe('可结转未清项');
    expect(periodCloseClassificationLabel('UNKNOWN')).toBe('UNKNOWN');
  });

  it('🔴 不可判定就是不可判定，不能显示成「通过」', () => {
    expect(periodCloseResultLabel('PASS')).toBe('通过');
    expect(periodCloseResultLabel('FAIL')).toBe('未通过');
    expect(periodCloseResultLabel('NOT_DETERMINABLE')).toBe('不可判定');
    expect(periodCloseResultLabel('NOT_DETERMINABLE')).not.toBe('通过');
  });

  it('🔴 「不可判定」绝不能渲染成绿色 —— 那等于说「没问题」', () => {
    expect(periodCloseResultTone('PASS')).toBe('success');
    expect(periodCloseResultTone('FAIL')).toBe('danger');
    expect(periodCloseResultTone('NOT_DETERMINABLE')).toBe('warning');
    expect(periodCloseResultTone('NOT_DETERMINABLE')).not.toBe('success');
  });

  it('🔴 阻断项是 danger，可结转未清项是 warning', () => {
    expect(periodCloseClassificationTone('BLOCKING')).toBe('danger');
    expect(periodCloseClassificationTone('CARRYWARD')).toBe('warning');
  });

  it('🔴 审批中与已驳回都要明说「期间仍然开放」', () => {
    expect(periodCloseExecutionStatusLabel('PENDING_APPROVAL')).toContain('期间仍然开放');
    expect(periodCloseExecutionStatusLabel('REJECTED')).toContain('期间仍然开放');
    // 只有「已关账」才真的锁定。
    expect(periodCloseExecutionStatusLabel('CLOSED')).toContain('已锁定');
    expect(periodCloseExecutionStatusLabel('CLOSED')).toContain('禁止过账');
    expect(periodCloseExecutionStatusLabel('CLOSED')).not.toContain('仍然开放');
  });

  it('已关账是 warning 而不是 success —— 关账是需要人盯着的状态', () => {
    expect(periodCloseExecutionStatusTone('CLOSED')).toBe('warning');
    expect(periodCloseExecutionStatusTone('CLOSED')).not.toBe('success');
    expect(periodCloseExecutionStatusTone('REJECTED')).toBe('danger');
    expect(periodCloseExecutionStatusTone('PENDING_APPROVAL')).toBe('neutral');
  });

  it('🔴 提交结局：被阻断要明说是被阻断项拦下，不是「系统出错」', () => {
    expect(periodCloseOutcomeText('BLOCKED')).toContain('被阻断项拦下');
    expect(periodCloseOutcomeText('BLOCKED')).toContain('期间仍然开放');
    expect(periodCloseOutcomeText('PENDING_APPROVAL')).toContain('期间仍然开放');
    expect(periodCloseOutcomeText('CLOSED')).toContain('已锁定');
    expect(periodCloseOutcomeTone('BLOCKED')).toBe('danger');
    expect(periodCloseOutcomeTone('PENDING_APPROVAL')).toBe('warning');
    expect(periodCloseOutcomeTone('CLOSED')).toBe('success');
  });

  it('🔴 阻断原因清单逐条列出来，且每条都带依据', () => {
    const text = periodCloseBlockingSummary([
      {
        checkName: '未过账凭证',
        itemCount: 3,
        basis: '本期业务日范围内共有 3 张草稿凭证',
      },
      {
        checkName: '未结转成本',
        itemCount: 0,
        basis: '该期间没有任何成本计算运行记录',
      },
    ]);
    expect(text).toContain('1. 未过账凭证（3 条）');
    expect(text).toContain('2. 未结转成本（0 条）');
    expect(text).toContain('本期业务日范围内共有 3 张草稿凭证');
    expect(text).toContain('该期间没有任何成本计算运行记录');
    // 一个阻断项都没有时不要留一句「没有阻断项」的废话堆在界面上。
    expect(periodCloseBlockingSummary([])).toBe('没有阻断项');
  });

  it('🔴 多币种绝不相加：一行只说一种币种', () => {
    const text = periodCloseCurrencyGroupText({
      currency: 'USD',
      amount: 1080,
      itemCount: 5,
      baseAmount: 1080,
      exchangeRate: 1,
      rateEffectiveDate: '2026-10-31',
      conversionNote: '本币种，无需折算',
    });
    expect(text).toContain('USD 1080（5 条）');
    expect(text).toContain('本币种，无需折算');
    // 文本里**没有**第二个币种被加进来。
    expect(text).not.toContain('CNY');
  });

  it('汇率不可得就只分组呈现，明说「未折算」', () => {
    const text = periodCloseCurrencyGroupText({
      currency: 'CNY',
      amount: 50,
      itemCount: 1,
      baseAmount: null,
      exchangeRate: null,
      rateEffectiveDate: null,
      conversionNote: '汇率不可得，未折算：catalog 服务查不到 CNY → USD 的汇率',
    });
    expect(text).toContain('CNY 50（1 条）');
    expect(text).toContain('汇率不可得');
    expect(text).not.toContain('本位币折算');
  });

  it('🔴 例外下调的守卫：规则判定不是阻断项 / 已下调过，都不许再下调', () => {
    const notBlocking = periodCloseCarryForwardGuard({
      ruleClassification: 'CARRYWARD',
      classification: 'CARRYWARD',
      exceptionBasis: '',
    });
    expect(notBlocking.allowed).toBe(false);
    expect(notBlocking.reason).toContain('无需例外下调');

    const already = periodCloseCarryForwardGuard({
      ruleClassification: 'BLOCKING',
      classification: 'CARRYWARD',
      exceptionBasis: '本期无存货业务',
    });
    expect(already.allowed).toBe(false);
    expect(already.reason).toContain('不能重复下调');
  });

  it('🔴 阻断项下调前必须写例外依据', () => {
    const guard = periodCloseCarryForwardGuard({
      ruleClassification: 'BLOCKING',
      classification: 'BLOCKING',
      exceptionBasis: '',
    });
    expect(guard.allowed).toBe(true);
    expect(guard.reason).toContain('例外依据');
  });

  it('明细一行要说清「来自哪个模块、哪一单、多少钱、为什么」', () => {
    const text = periodCloseItemText({
      sourceModule: '12.5.2.1',
      sourceType: 'VOUCHER',
      sourceNo: 'V-001',
      currency: 'USD',
      amount: 100,
      reason: '凭证仍为草稿（未过账）',
    });
    expect(text).toContain('12.5.2.1');
    expect(text).toContain('V-001');
    expect(text).toContain('USD 100');
    expect(text).toContain('凭证仍为草稿');
  });
});
