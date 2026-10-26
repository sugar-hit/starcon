import { describe, expect, it } from 'vitest';
import {
  emptyState,
  emptyStateKind,
  filingObligationLabel,
  formatAmount,
  formatRate,
  groupTotalsByCurrency,
  reconciliationStatusLabel,
  reconciliationStatusTone,
  registrationStatusLabel,
  varianceMeaning,
  varianceTone,
} from './sales-tax-model';

/**
 * 16.3.2.3 销售税与 Marketplace Facilitator 前端模型测试。
 *
 * 🔴 这组用例守的是页面上最容易做丢的五件事：
 *
 * 1. **应缴与已代缴是两条线**：差异的符号约定必须写清（正数 = 平台少收了，卖家要补缴）；
 * 2. **「判不了」是独立的一档**：没有生效规则 / 没登记所在州，**不许**渲染成「应缴 0」或绿色；
 * 3. **税率是数据不是常量**：前端只做**单位换算**（小数率 → 百分数），不内置任何州税率表；
 * 4. **金额按币种分组**：不同币种绝不直接相加；
 * 5. **三态空态各不相同**：「没有州」「筛选无结果」「还没有对账记录」分开说。
 */
describe('16.3.2.3 销售税前端模型', () => {
  describe('差异的符号约定', () => {
    it('正数 = 平台少收了，卖家要补缴', () => {
      expect(varianceMeaning(25)).toContain('补缴');
      expect(varianceTone(25)).toBe('danger');
    });

    it('负数 = 平台多收了，可能要退', () => {
      expect(varianceMeaning(-25)).toContain('退');
      expect(varianceTone(-25)).toBe('warning');
    });

    it('零 = 已对平', () => {
      expect(varianceMeaning(0)).toContain('对平');
      expect(varianceTone(0)).toBe('success');
    });
  });

  describe('「判不了」是独立的一档', () => {
    it('没有生效规则 ⇒ 判不了（不是「应缴 0」的绿色）', () => {
      expect(reconciliationStatusLabel('RULE_MISSING')).toContain('判不了');
      expect(reconciliationStatusTone('RULE_MISSING')).toBe('warning');
    });

    it('没登记店铺所在州 ⇒ 判不了', () => {
      expect(reconciliationStatusLabel('NEXUS_MISSING')).toContain('判不了');
      expect(reconciliationStatusTone('NEXUS_MISSING')).toBe('warning');
    });

    it('未达阈值 ≠ 免税（是「期间无申报义务」）', () => {
      expect(reconciliationStatusLabel('BELOW_THRESHOLD')).toContain('阈值');
      expect(reconciliationStatusTone('BELOW_THRESHOLD')).toBe('neutral');
      expect(filingObligationLabel('BELOW_THRESHOLD')).toContain('不需要申报');
    });

    it('平台代缴不抵消申报义务', () => {
      expect(filingObligationLabel('FILING_REQUIRED')).toContain('不抵消申报义务');
    });
  });

  describe('税率只是单位换算（页面不内置州税率表）', () => {
    it('小数率换算成百分数', () => {
      expect(formatRate(0.0725)).toBe('7.25%');
      expect(formatRate(0.065)).toBe('6.5%');
    });

    it('没有生效规则时显示「—」（不是 0%）', () => {
      expect(formatRate(null)).toBe('—');
    });
  });

  describe('金额按币种分组', () => {
    it('不同币种绝不直接相加', () => {
      const grouped = groupTotalsByCurrency([
        { currency: 'USD', expectedTax: 725, facilitatorTax: 700 },
        { currency: 'EUR', expectedTax: 100, facilitatorTax: 90 },
        { currency: 'USD', expectedTax: 25, facilitatorTax: 25 },
      ]);
      expect(grouped).toHaveLength(2);
      const usd = grouped.find((g) => g.currency === 'USD');
      const eur = grouped.find((g) => g.currency === 'EUR');
      expect(usd?.expectedTax).toBe(750);
      expect(eur?.expectedTax).toBe(100);
      // 🔴 绝不能出现「725 + 100 = 825」这种把两种币种加起来的数
      expect(grouped.some((g) => g.expectedTax === 825)).toBe(false);
    });

    it('金额为空时显示「—」', () => {
      expect(formatAmount(null)).toBe('—');
      expect(formatAmount(0)).toBe('0.00');
    });
  });

  describe('三态空态各不相同', () => {
    it('没有州 / 筛选无结果 / 还没有对账记录分开说', () => {
      expect(emptyState('NO_STATE').title).toContain('还没有销售税州');
      expect(emptyState('NO_FILTER_MATCH').title).toContain('没有符合条件的州');
      expect(emptyState('NO_RECONCILIATION').title).toContain('还没有对账记录');
    });

    it('有筛选 ⇒ 判为「筛选无结果」；无筛选且零条 ⇒ 判为「没有州」', () => {
      expect(emptyStateKind({ stateCode: 'US-CA' }, 0)).toBe('NO_FILTER_MATCH');
      expect(emptyStateKind({}, 0)).toBe('NO_STATE');
      expect(emptyStateKind({}, 3)).toBe('NO_RECONCILIATION');
    });
  });

  describe('登记状态的人话', () => {
    it('三种登记状态各有各的说法', () => {
      expect(registrationStatusLabel('REGISTERED')).toBe('已登记');
      expect(registrationStatusLabel('PARTIAL')).toBe('部分已登记');
      expect(registrationStatusLabel('NOT_REGISTERED')).toBe('未登记');
    });
  });
});
