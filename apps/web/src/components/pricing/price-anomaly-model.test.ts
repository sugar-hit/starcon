import { describe, expect, it } from 'vitest';

import {
  EMPTY_ANOMALY_FILTERS,
  EMPTY_OBSERVATION_FILTERS,
  anomalyTone,
  anomalyTypeLabel,
  changeSourceLabel,
  changeSourceTone,
  costLineText,
  dayText,
  durationText,
  evidenceText,
  moneyText,
  moneyWithCurrency,
  ratioText,
  sitePairText,
  statusTone,
  validateObservation,
  validateRule,
} from './price-anomaly-model';
import type { ObservationInput, EventRow } from './price-anomaly-model';

/**
 * 5.5.2.1 异常告警前端模型测试。
 *
 * <p>🔴 本文件最要紧的三组断言，都是「页面上说错话比不显示更糟」的地方：
 *
 * <ol>
 *   <li><b>null 不渲染成 0</b>：成本线读不到显示「未维护」，没登记的价显示「未登记」，
 *       而不是 0.00 —— 渲染成 0.00 会让「售价低于成本线」看起来比实际更严重。
 *   <li><b>站点两两比对的方向要看得见</b>：固定写成「A 低于 B」，
 *       而不是「A / B」或只给一个价差 —— 蓝图加粗句「德国站定价低于美国站」在界面上必须读得出方向。
 *   <li><b>「长期」与「发生过」分得开</b>：③ 平台自动降价的持续天数不是「持续 0 天」，
 *       而是「发生过的事（不按持续计）」。
 * </ol>
 */
describe('5.5.2.1 异常告警前端模型', () => {
  describe('文案与语气', () => {
    it('四类异常各有中文名', () => {
      expect(anomalyTypeLabel('PLATFORM_INTERNAL_DIVERGENCE')).toBe('平台价与内部价长期不一致');
      expect(anomalyTypeLabel('BELOW_COST')).toBe('售价低于成本线');
      expect(anomalyTypeLabel('PLATFORM_AUTO_DISCOUNT')).toBe('平台自动降价');
      expect(anomalyTypeLabel('CROSS_SITE_INVERSION')).toBe('多站点价格倒挂');
      expect(anomalyTypeLabel('')).toBe('未知异常');
    });

    it('平台自动降价与内部调价的语气必须分开', () => {
      // 🔴 两者都标成同一种颜色，就看不出到底是谁在动价了。
      expect(changeSourceTone('AUTOMATIC_PLATFORM')).toBe('danger');
      expect(changeSourceTone('MANUAL_INTERNAL')).toBe('info');
      expect(changeSourceTone('PLATFORM_FEEDBACK')).toBe('warning');
      expect(changeSourceTone('UNKNOWN')).toBe('neutral');
      expect(changeSourceLabel('AUTOMATIC_PLATFORM')).toBe('平台自动降价');
      expect(changeSourceLabel('MANUAL_INTERNAL')).toBe('内部调价');
    });

    it('直接亏损与窜货风险用最重的语气', () => {
      expect(anomalyTone('BELOW_COST')).toBe('danger');
      expect(anomalyTone('CROSS_SITE_INVERSION')).toBe('danger');
      expect(anomalyTone('PLATFORM_INTERNAL_DIVERGENCE')).toBe('warning');
      expect(anomalyTone('PLATFORM_AUTO_DISCOUNT')).toBe('info');
    });

    it('已消解的事件用中性色，别让历史一直占着红色的位置', () => {
      expect(statusTone('OPEN')).toBe('danger');
      expect(statusTone('ACKNOWLEDGED')).toBe('warning');
      expect(statusTone('RESOLVED')).toBe('neutral');
    });
  });

  describe('🔴 null 绝不渲染成 0', () => {
    it('没登记的价显示「未登记」而不是 0.00', () => {
      expect(moneyText(null)).toBe('未登记');
      expect(moneyText('')).toBe('未登记');
      expect(moneyText(undefined)).toBe('未登记');
      expect(moneyText('10.0000')).toBe('10.0000');
    });

    it('带币种的金额在取不到时也不显示币种 0', () => {
      expect(moneyWithCurrency(null, 'USD')).toBe('未登记');
      expect(moneyWithCurrency('8.0000', 'USD')).toBe('USD 8.0000');
    });

    it('没有成本构成时写明「不参与判定」而不是当成 0 成本', () => {
      expect(costLineText({ costLine: null, costCurrency: '', costComplete: true })).toContain(
        '未维护成本构成',
      );
      expect(costLineText({ costLine: null, costCurrency: '', costComplete: true })).toContain(
        '不参与判定',
      );
    });

    it('成本快照不完整时标出「已知部分合计」', () => {
      const text = costLineText({ costLine: '10.0000', costCurrency: 'USD', costComplete: false });
      expect(text).toContain('USD 10.0000');
      expect(text).toContain('已知部分合计');
    });

    it('成本快照完整时不做多余的警告', () => {
      const text = costLineText({ costLine: '10.0000', costCurrency: 'USD', costComplete: true });
      expect(text).toBe('USD 10.0000');
    });
  });

  describe('🔴 站点两两比对的方向要看得见', () => {
    it('站点倒挂固定写成「低的那一边 低于 贵的那一边」', () => {
      expect(sitePairText({ siteCode: 'DE', relatedSiteCode: 'US' })).toBe('DE 低于 US');
    });

    it('没有参照站点时只显示自己那一边', () => {
      expect(sitePairText({ siteCode: 'DE', relatedSiteCode: '' })).toBe('DE');
    });
  });

  describe('「长期」与「发生过」分得开', () => {
    const base: EventRow = {
      id: 'e1',
      anomalyType: 'PLATFORM_INTERNAL_DIVERGENCE',
      anomalyTypeLabel: '平台价与内部价长期不一致',
      skuId: 's1',
      skuCode: 'SKU-1',
      skuName: '商品一',
      platformCode: 'AMZN',
      siteCode: 'US',
      relatedPlatformCode: '',
      relatedSiteCode: '',
      currency: 'USD',
      subjectKind: '',
      subjectPrice: '8.0000',
      referencePrice: '10.0000',
      gapAmount: '-2.0000',
      gapRatio: '0.200000',
      costLine: null,
      costCurrency: '',
      costComplete: true,
      costSnapshotAt: null,
      firstSeenAt: '2026-09-20T00:00:00Z',
      lastSeenAt: '2026-10-18T00:00:00Z',
      durationDays: 28,
      severity: 'MEDIUM',
      severityLabel: '中',
      status: 'OPEN',
      statusLabel: '待处理',
      ruleId: null,
      fingerprint: 'f1',
      evidence: '{}',
      updatedAt: '2026-10-18T00:00:00Z',
    };

    it('状态型异常报持续天数', () => {
      expect(durationText(base)).toBe('持续 28 天');
    });

    it('平台自动降价是发生过的事，不说「持续 0 天」', () => {
      const discount = { ...base, anomalyType: 'PLATFORM_AUTO_DISCOUNT', durationDays: 0 };
      expect(durationText(discount)).toContain('发生过的事');
      expect(durationText(discount)).not.toContain('持续 0 天');
    });

    it('不到一天时也不说「持续 0 天」', () => {
      expect(durationText({ ...base, durationDays: 0 })).toBe('不到 1 天');
    });
  });

  describe('比例与时刻', () => {
    it('比例转成百分比', () => {
      expect(ratioText('0.050000')).toBe('5.00%');
      expect(ratioText('0.240000')).toBe('24.00%');
      expect(ratioText(null)).toBe('—');
    });

    it('时刻只到日', () => {
      expect(dayText('2026-10-20T08:30:00Z')).toBe('2026-10-20');
      expect(dayText(null)).toBe('—');
    });
  });

  describe('判定证据', () => {
    it('JSON 文本转成可读串', () => {
      expect(evidenceText('{"threshold":0.05,"spanDays":28}')).toContain('threshold = 0.05');
      expect(evidenceText('{"threshold":0.05,"spanDays":28}')).toContain('spanDays = 28');
    });

    it('不是 JSON 时原样返回，不吞掉', () => {
      expect(evidenceText('随便一段')).toBe('随便一段');
      expect(evidenceText('')).toBe('');
    });
  });

  describe('🔴 登记观测的本地校验', () => {
    const valid: ObservationInput = {
      skuId: '11111111-1111-1111-1111-111111111111',
      platformCode: 'AMZN',
      siteCode: 'US',
      currency: 'USD',
      internalPrice: '10.00',
      platformPrice: '8.00',
      priceChangeSource: 'AUTOMATIC_PLATFORM',
      source: 'PLATFORM_FEEDBACK',
      comparisonCurrency: null,
      fxRate: null,
      fxRateDate: null,
      fxRateSource: null,
      observedAt: '2026-10-20T00:00:00',
      note: '',
    };

    it('合法的观测没有校验问题', () => {
      expect(validateObservation(valid)).toEqual([]);
    });

    it('两个价都不给要说清「没有任何信息量」', () => {
      const problems = validateObservation({ ...valid, internalPrice: null, platformPrice: null });
      expect(problems.join('；')).toContain('至少要给一个');
    });

    it('比价币种与汇率必须同生共死', () => {
      const onlyFx = validateObservation({
        ...valid,
        comparisonCurrency: null,
        fxRate: '1.10',
        fxRateDate: '2026-10-20',
        fxRateSource: '汇率表',
      });
      expect(onlyFx.join('；')).toContain('同时给比价币种');

      const onlyCurrency = validateObservation({
        ...valid,
        comparisonCurrency: 'USD',
        fxRate: null,
      });
      expect(onlyCurrency.join('；')).toContain('同时给汇率');
    });

    it('给了汇率必须说清是哪天的、哪来的', () => {
      const noDate = validateObservation({
        ...valid,
        comparisonCurrency: 'USD',
        fxRate: '1.10',
        fxRateDate: null,
        fxRateSource: '汇率表',
      });
      expect(noDate.join('；')).toContain('哪天的汇率');

      const noSource = validateObservation({
        ...valid,
        comparisonCurrency: 'USD',
        fxRate: '1.10',
        fxRateDate: '2026-10-20',
        fxRateSource: null,
      });
      expect(noSource.join('；')).toContain('哪来的汇率');
    });

    it('负价与非法币种都要挡', () => {
      expect(validateObservation({ ...valid, platformPrice: '-1' }).join('；')).toContain('不能为负');
      expect(validateObservation({ ...valid, currency: 'US' }).join('；')).toContain('ISO 4217');
    });

    it('缺 SKU 或缺观测时刻都要挡', () => {
      expect(validateObservation({ ...valid, skuId: '' }).join('；')).toContain('SKU');
      expect(validateObservation({ ...valid, observedAt: '' }).join('；')).toContain('观测时刻');
    });
  });

  describe('规则校验', () => {
    it('比例必须在 [0,1) 之间', () => {
      const problems = validateRule({
        anomalyType: 'BELOW_COST',
        name: '越界',
        enabled: true,
        ratioThreshold: '1',
        minDurationDays: 1,
        severity: 'HIGH',
        note: '',
      });
      expect(problems.join('；')).toContain('[0,1)');
    });

    it('规则名与异常类型必填', () => {
      const problems = validateRule({
        anomalyType: '',
        name: '',
        enabled: true,
        ratioThreshold: null,
        minDurationDays: null,
        severity: '',
        note: '',
      });
      expect(problems.join('；')).toContain('异常类型');
      expect(problems.join('；')).toContain('规则名');
      expect(problems.join('；')).toContain('严重度');
    });

    it('阈值可空（用内置默认值）是合法的', () => {
      const problems = validateRule({
        anomalyType: 'BELOW_COST',
        name: '默认',
        enabled: true,
        ratioThreshold: null,
        minDurationDays: null,
        severity: 'HIGH',
        note: '',
      });
      expect(problems).toEqual([]);
    });
  });

  describe('筛选默认值', () => {
    it('异常筛选与观测筛选各有独立的空态', () => {
      expect(EMPTY_ANOMALY_FILTERS.page).toBe(0);
      expect(EMPTY_ANOMALY_FILTERS.anomalyType).toBe('');
      expect(EMPTY_OBSERVATION_FILTERS.page).toBe(0);
      expect(EMPTY_OBSERVATION_FILTERS.priceChangeSource).toBe('');
    });
  });
});
