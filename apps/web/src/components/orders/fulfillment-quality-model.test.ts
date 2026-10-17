import { describe, expect, it } from 'vitest';
import {
  basisText,
  capabilityTone,
  comparisonLabel,
  dimensionLabel,
  formatRate,
  metricLabel,
  metricTone,
  severityLabel,
  severityTone,
} from './fulfillment-quality-model';

/**
 * 9.5.2.1 履约质量前端呈现口径。
 *
 * <p>这些断言盯的都是**同一个东西的两种表现**：后端把「算不出来」和
 * 「算出来是 0」分得很清楚（分母为 0 ⇒ rate 为 null），前端一旦把它们
 * 画成同一个东西，指标看板就开始说谎 —— 而纠纷率与错发率直接关联账号安全，
 * 说谎的代价是平台处罚。
 */
describe('9.5.2.1 履约质量 · 呈现口径', () => {
  describe('比率：null 绝不能变成 0%', () => {
    it('分母为 0 时渲染成「无法判定」，不是「0%」', () => {
      expect(formatRate(null)).toBe('无法判定（无样本）');
      expect(formatRate(undefined)).toBe('无法判定（无样本）');
      expect(formatRate(null)).not.toContain('0%');
    });

    it('真实的 0% 照原样显示：0 是算得出来的结论', () => {
      // 刻意区分 null 与 0：分母 3 分子 0 是「这三条里一条缺陷都没有」，
      // 分母 0 则是「一条都没测过」—— 两者的处置完全不同
      expect(formatRate(0)).toBe('0.00%');
      expect(formatRate(0)).not.toBe('无法判定（无样本）');
    });

    it('保留两位小数展示，但服务端 6 位小数的值不被截成整数', () => {
      expect(formatRate(66.666667)).toBe('66.67%');
      expect(formatRate(33.333333)).toBe('33.33%');
      expect(formatRate(50)).toBe('50.00%');
      // 字符串形态（JSON 反序列化后可能是 string）同样成立
      expect(formatRate('66.666667')).toBe('66.67%');
    });
  });

  describe('指标方向：高越好与低越好必须分开判', () => {
    it('高越好的指标：95 以上为绿，90 以下为红', () => {
      expect(metricTone('HIGHER_IS_BETTER', 99)).toBe('ok');
      expect(metricTone('HIGHER_IS_BETTER', 90)).toBe('warn');
      expect(metricTone('HIGHER_IS_BETTER', 50)).toBe('bad');
    });

    it('低越好的指标：1% 以下为绿，5% 以上为红', () => {
      expect(metricTone('LOWER_IS_BETTER', 0)).toBe('ok');
      expect(metricTone('LOWER_IS_BETTER', 3)).toBe('warn');
      expect(metricTone('LOWER_IS_BETTER', 20)).toBe('bad');
    });

    it('同一个 99%：及时率方向是绿，错发率方向是红 —— 方向反了看板就说反了', () => {
      expect(metricTone('HIGHER_IS_BETTER', 99)).toBe('ok');
      expect(metricTone('LOWER_IS_BETTER', 99)).toBe('bad');
    });

    it('同一个 5%：及时率是红，错发率是黄（低但没到 1% 以下）', () => {
      expect(metricTone('HIGHER_IS_BETTER', 5)).toBe('bad');
      expect(metricTone('LOWER_IS_BETTER', 5)).toBe('warn');
    });

    it('分母为 0 一律中性色：不能画成红色坏消息', () => {
      expect(metricTone('LOWER_IS_BETTER', null)).toBe('neutral');
      expect(metricTone('HIGHER_IS_BETTER', null)).toBe('neutral');
    });
  });

  describe('分子/分母必须一起显示', () => {
    it('给「几 ÷ 几」而不是只给一个百分数', () => {
      expect(basisText(2, 3)).toBe('2 ÷ 3');
      expect(basisText(1, 4)).toBe('1 ÷ 4');
    });

    it('分母为 0 时说清楚「没有可判定样本」，而不是显示 0 ÷ 0', () => {
      expect(basisText(0, 0)).toBe('本窗口没有可判定样本');
      expect(basisText(0, 0)).not.toContain('0 ÷ 0');
    });
  });

  describe('标签与占位桶', () => {
    it('指标名优先用服务端返回的展示名', () => {
      expect(metricLabel('SHIP_ON_TIME_RATE', '发货及时率')).toBe('发货及时率');
      expect(metricLabel('DISPUTE_RATE')).toBe('纠纷率');
      expect(metricLabel('NOT_A_METRIC')).toBe('NOT_A_METRIC');
    });

    it('空值维度桶说人话，不把 UNASSIGNED / UNATTACHED 直接甩给用户', () => {
      expect(dimensionLabel('UNASSIGNED')).toBe('（未认领）');
      expect(dimensionLabel('UNATTACHED')).toBe('（无订单归属）');
      expect(dimensionLabel('OP-A')).toBe('OP-A');
    });

    it('服务端给了展示名就优先用服务端的', () => {
      expect(dimensionLabel('UNASSIGNED', '（没人认领）')).toBe('（没人认领）');
    });
  });

  describe('缺口与预警措辞', () => {
    it('不可用维度一律中性色，不画成绿色「已就绪」', () => {
      expect(capabilityTone(false)).toBe('neutral');
      expect(capabilityTone(true)).toBe('ok');
    });

    it('预警级别：严重是红、警告是黄、提示中性', () => {
      expect(severityTone('CRITICAL')).toBe('bad');
      expect(severityTone('WARN')).toBe('warn');
      expect(severityTone('INFO')).toBe('neutral');
      expect(severityLabel('CRITICAL')).toBe('严重');
    });

    it('阈值方向有中文说法：GT / LT 不直接甩给用户', () => {
      expect(comparisonLabel('GT')).toBe('大于');
      expect(comparisonLabel('GTE')).toBe('大于等于');
      expect(comparisonLabel('LT')).toBe('小于');
      expect(comparisonLabel('LTE')).toBe('小于等于');
    });
  });
});
