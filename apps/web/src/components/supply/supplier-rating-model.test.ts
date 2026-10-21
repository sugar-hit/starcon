import { describe, expect, it } from 'vitest';
import {
  adjustmentLabel,
  finalScoreLabel,
  changeTypeLabel,
  dimensionLabel,
  EMPTY_SUPPLIER_RATING_FILTERS,
  gradeLabel,
  gradeTone,
  hoursLabel,
  periodText,
  quantityLabel,
  rateLabel,
  scoreLabel,
  timeText,
  toPolicyForm,
  unavailableText,
  validateAdjustment,
  validatePolicyForm,
  weightLabel,
  type SupplierRatingPolicy,
} from './supplier-rating-model';

/**
 * 6.1.1.3 供应商评级前端模型的单测。
 *
 * <p>钉住的是**页面上最容易出错的那几处**：
 *
 * <ul>
 *   <li>比率的 null 渲染成「暂无依据」而**不是 0%**（不可用 ≠ 0 分）；</li>
 *   <li>维度得分的 null 渲染成「不可用」而**不是 0.00**；</li>
 *   <li>人工调整的标记（有值 / 无值 / 正负号）；</li>
 *   <li>权重和必须精确等于 1（保存前就拦一道）；</li>
 *   <li>人工调整的理由必填、调整量不能为 0。</li>
 * </ul>
 */
describe('6.1.1.3 供应商评级前端模型', () => {
  describe('比率与得分', () => {
    it('比率为 null 时显示「暂无依据」而不是 0%', () => {
      expect(rateLabel(null)).toBe('暂无依据');
      expect(rateLabel(undefined)).toBe('暂无依据');
      expect(rateLabel('')).toBe('暂无依据');
    });

    it('比率按百分比展示两位小数', () => {
      expect(rateLabel('0.92')).toBe('92.00%');
      expect(rateLabel(0.5)).toBe('50.00%');
      expect(rateLabel('0')).toBe('0.00%');
    });

    it('维度得分为 null 时显示「不可用」而不是 0.00', () => {
      expect(scoreLabel(null)).toBe('不可用');
      expect(scoreLabel(undefined)).toBe('不可用');
      expect(scoreLabel('0')).toBe('0.00');
    });

    it('最终分与人工调整量分别展示', () => {
      expect(finalScoreLabel('95.5')).toBe('95.50');
      expect(adjustmentLabel(null)).toBe('无人工调整');
      expect(adjustmentLabel(0)).toBe('无人工调整');
      expect(adjustmentLabel(5)).toBe('+5.00');
      expect(adjustmentLabel(-5)).toBe('-5.00');
    });
  });

  describe('标签与色', () => {
    it('维度与等级有中文名', () => {
      expect(dimensionLabel('DELIVERY')).toBe('交期达成率');
      expect(dimensionLabel('PRICE')).toBe('价格竞争力');
      expect(gradeLabel('A')).toBe('A 级');
      expect(gradeLabel(null)).toBe('未定级');
    });

    it('等级配色区分好与差', () => {
      expect(gradeTone('A')).toBe('success');
      expect(gradeTone('B')).toBe('success');
      expect(gradeTone('C')).toBe('warning');
      expect(gradeTone('D')).toBe('danger');
      expect(gradeTone(null)).toBe('neutral');
    });

    it('变动来源有中文名', () => {
      expect(changeTypeLabel('PERIOD_ROLL')).toBe('跨周期滚动');
      expect(changeTypeLabel('MANUAL_ADJUSTMENT')).toBe('人工调整');
    });

    it('不可用维度清单拼成人话', () => {
      expect(unavailableText('PRICE,SERVICE')).toBe('价格竞争力、售后配合度');
      expect(unavailableText('')).toBe('');
      expect(unavailableText(null)).toBe('');
    });
  });

  describe('展示串', () => {
    it('覆盖权重与周期区间', () => {
      expect(weightLabel(0.6)).toBe('60%');
      expect(weightLabel(null)).toBe('—');
      expect(periodText('2026-10-01', '2026-10-31')).toBe('2026-10-01 ~ 2026-10-31');
      expect(periodText(null, null)).toBe('本期');
    });

    it('响应时长与数量的展示', () => {
      expect(hoursLabel(null)).toBe('暂无依据');
      expect(hoursLabel('48.0')).toBe('48.0 小时');
      expect(quantityLabel('100.5')).toBe('100.5');
      expect(quantityLabel(null)).toBe('0');
    });

    it('时刻只到分钟', () => {
      expect(timeText('2026-10-21T09:30:00Z')).toBe('2026-10-21 09:30');
      expect(timeText(null)).toBe('—');
    });
  });

  describe('权重校验', () => {
    const base = {
      periodType: 'MONTHLY' as const,
      deliveryWeight: '0.30',
      qualityWeight: '0.30',
      responseWeight: '0.15',
      priceWeight: '0.15',
      serviceWeight: '0.10',
      responseTargetHours: '72',
      note: '',
    };

    it('权重和精确等于 1 时通过', () => {
      expect(validatePolicyForm(base)).toEqual([]);
    });

    it('权重和不为 1 时被拦下', () => {
      const problems = validatePolicyForm({ ...base, serviceWeight: '0.05' });
      expect(problems.join('；')).toContain('精确等于 1');
    });

    it('非数字权重被拦下', () => {
      const problems = validatePolicyForm({ ...base, deliveryWeight: 'abc' });
      expect(problems.join('；')).toContain('请填数字');
    });

    it('响应目标小时数必须大于 0', () => {
      expect(validatePolicyForm({ ...base, responseTargetHours: '0' }).join('；')).toContain(
        '响应目标小时数必须大于 0',
      );
      expect(validatePolicyForm({ ...base, responseTargetHours: '' }).join('；')).toContain(
        '响应目标小时数必须大于 0',
      );
    });

    it('某个维度权重为 0 是合法表达', () => {
      expect(
        validatePolicyForm({
          ...base,
          deliveryWeight: '0.50',
          qualityWeight: '0.50',
          responseWeight: '0',
          priceWeight: '0',
          serviceWeight: '0',
        }),
      ).toEqual([]);
    });
  });

  describe('人工调整校验', () => {
    it('理由必填', () => {
      const problems = validateAdjustment({ adjustment: '5', reason: '  ' });
      expect(problems.join('；')).toContain('理由必填');
    });

    it('调整量为 0 被拒', () => {
      expect(validateAdjustment({ adjustment: '0', reason: '扶持' }).join('；')).toContain(
        '不能为 0',
      );
    });

    it('调整量超出范围被拒', () => {
      expect(validateAdjustment({ adjustment: '80', reason: '扶持' }).join('；')).toContain(
        '−50 ~ +50',
      );
    });

    it('合法的调整通过', () => {
      expect(validateAdjustment({ adjustment: '-5', reason: '复核后下调' })).toEqual([]);
    });
  });

  describe('表单初值', () => {
    it('从后端配置铺出权重表单', () => {
      const policy: SupplierRatingPolicy = {
        periodType: 'MONTHLY',
        deliveryWeight: 0.4,
        qualityWeight: 0.3,
        responseWeight: 0.1,
        priceWeight: 0.1,
        serviceWeight: 0.1,
        responseTargetHours: 48,
        gradeAMin: 0.85,
        gradeBMin: 0.7,
        gradeCMin: 0.55,
        configured: true,
        defaultDeliveryWeight: 0.3,
        defaultQualityWeight: 0.3,
        defaultResponseWeight: 0.15,
        defaultPriceWeight: 0.15,
        defaultServiceWeight: 0.1,
        version: 3,
        note: '更看重交期',
      };
      const form = toPolicyForm(policy);
      expect(form.deliveryWeight).toBe('0.4');
      expect(form.responseTargetHours).toBe('48');
      expect(form.note).toBe('更看重交期');
      expect(validatePolicyForm(form)).toEqual([]);
    });

    it('空筛选条件是月度 + 按最终分倒序', () => {
      expect(EMPTY_SUPPLIER_RATING_FILTERS.periodType).toBe('MONTHLY');
      expect(EMPTY_SUPPLIER_RATING_FILTERS.sort).toBe('finalScore');
      expect(EMPTY_SUPPLIER_RATING_FILTERS.desc).toBe(true);
    });
  });
});
