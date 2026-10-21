import { describe, expect, it } from 'vitest';
import {
  discountText,
  discountTone,
  emptyAdGuardrailInput,
  emptyAdPromotionInput,
  localDateTimeInput,
  money,
  promotionTypeLabel,
  ruleLabel,
  severityLabel,
  severityTone,
  statusLabel,
  statusTone,
  syncStatusLabel,
  timestampText,
  validateAdGuardrail,
  validateAdPromotion,
} from './ad-promotion-model';

/**
 * 14.4.1.1 促销列表 前端模型测试。
 *
 * 🔴 这里钉住的是**最容易在界面上做丢**的几件事：
 *   1. 不可比的折后价显示「不可比」而不是 0.00 —— 0 是「可比但真的是零」；
 *   2. 平台同步状态必须把「本地已建、平台侧还没有」说清楚；
 *   3. 折扣口径互斥：百分比与固定额不能同时给（折后价只能有一个答案）；
 *   4. 护栏两个阈值都不设 ⇒ 建不出来（一条规则都判不了）；
 *   5. 拦截与预警的语气区分（拦截要显眼）。
 */
describe('14.4.1.1 促销列表 · 展示辅助', () => {
  it('🔴 不可比的折后价显示「不可比」而不是 0.00', () => {
    expect(money(null)).toBe('不可比');
    expect(money(undefined)).toBe('不可比');
    expect(money(80)).toBe('80.00');
    expect(money(80, 'USD')).toBe('80.00 USD');
  });

  it('🔴 折扣幅度不可比时显示「不可比」，0.2 显示 20.00%', () => {
    expect(discountText(null)).toBe('不可比');
    expect(discountText(undefined)).toBe('不可比');
    expect(discountText(0)).toBe('0.00%');
    expect(discountText(0.2)).toBe('20.00%');
    expect(discountTone(null)).toBe('text-muted-foreground');
    expect(discountTone(0.2)).toBe('text-foreground');
    expect(discountTone(0.8)).toBe('text-destructive');
  });

  it('🔴 平台同步状态必须说清「本地已建、平台侧还没有」', () => {
    expect(syncStatusLabel('LOCAL_ONLY')).toContain('平台侧还没有');
    expect(syncStatusLabel('SYNCED')).toContain('平台侧已一致');
    expect(syncStatusLabel('FAILED')).toContain('回写失败');
  });

  it('促销类型与状态有中文名', () => {
    expect(promotionTypeLabel('PERCENT_OFF')).toBe('百分比折扣');
    expect(promotionTypeLabel('FIXED_AMOUNT_OFF')).toBe('固定额减免');
    expect(promotionTypeLabel('FREE_SHIPPING')).toBe('免运费');
    expect(statusLabel('ACTIVE')).toBe('进行中');
    expect(statusLabel('CANCELLED')).toBe('已取消');
    expect(statusTone('ACTIVE')).toBe('text-emerald-600');
    expect(statusTone('CANCELLED')).toBe('text-amber-600');
  });

  it('规则与结论有中文名，拦截的语气比预警重', () => {
    expect(ruleLabel('DISCOUNT_DEPTH')).toBe('折扣幅度');
    expect(ruleLabel('PRICE_FLOOR')).toBe('折后价下限');
    expect(ruleLabel('PROMOTION_STACKING')).toBe('促销叠加');
    expect(ruleLabel('BUDGET_CAP')).toBe('预算上限');
    expect(severityLabel('BLOCKED')).toBe('拦截');
    expect(severityLabel('WARNING')).toBe('预警');
    expect(severityTone('BLOCKED')).toBe('text-destructive');
    expect(severityTone('WARNING')).toBe('text-amber-600');
  });

  it('时间戳空值显示「—」', () => {
    expect(timestampText(null)).toBe('—');
    expect(timestampText('')).toBe('—');
    expect(timestampText('2026-10-01T00:00:00+08:00')).not.toBe('—');
  });

  it('🔴 datetime-local 取本地墙上时间，不被 toISOString 偏成 UTC', () => {
    // 2026-10-01T00:00:00+08:00 = 2026-09-30T16:00:00Z。
    // 直接塞 toISOString() 的串会让运营看到的起止时间整体偏 8 小时。
    expect(localDateTimeInput('2026-10-01T00:00:00+08:00')).toMatch(
      /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}$/,
    );
    expect(localDateTimeInput('2026-10-01T00:00:00+08:00')).not.toContain('Z');
    expect(localDateTimeInput(null)).toBe('');
    expect(localDateTimeInput('not-a-time')).toBe('');
  });
});

describe('14.4.1.1 促销列表 · 表单校验', () => {
  it('必填项缺失时报错', () => {
    const result = validateAdPromotion(emptyAdPromotionInput());
    expect(result.ok).toBe(false);
    expect(result.errors).toContain('促销编码必填');
    expect(result.errors).toContain('促销名称必填');
    expect(result.errors).toContain('参与店铺必填');
  });

  it('🔴 百分比促销不能同时给减免额（折后价只能有一个答案）', () => {
    const result = validateAdPromotion({
      ...emptyAdPromotionInput(),
      promotionCode: 'P-1',
      promotionName: '促销 1',
      shopCode: 'SHOP-1',
      promotionType: 'PERCENT_OFF',
      discountRate: '0.2',
      discountAmount: '5',
    });
    expect(result.ok).toBe(false);
    expect(result.errors.join('；')).toContain('折后价只能有一个答案');
  });

  it('百分比促销必须给一个 (0, 1] 的折扣率', () => {
    const result = validateAdPromotion({
      ...emptyAdPromotionInput(),
      promotionCode: 'P-1',
      promotionName: '促销 1',
      shopCode: 'SHOP-1',
      promotionType: 'PERCENT_OFF',
      discountRate: '1.5',
    });
    expect(result.ok).toBe(false);
    expect(result.errors.join('；')).toContain('折扣率必须在 (0, 1] 之间');
  });

  it('🔴 非价格型促销不给折扣率 / 减免额（折后价不可比，不是 0）', () => {
    const result = validateAdPromotion({
      ...emptyAdPromotionInput(),
      promotionCode: 'P-1',
      promotionName: '促销 1',
      shopCode: 'SHOP-1',
      promotionType: 'FREE_SHIPPING',
      discountRate: '0.2',
    });
    expect(result.ok).toBe(false);
    expect(result.errors.join('；')).toContain('不可比');
  });

  it('结束时间必须晚于开始时间', () => {
    const result = validateAdPromotion({
      ...emptyAdPromotionInput(),
      promotionCode: 'P-1',
      promotionName: '促销 1',
      shopCode: 'SHOP-1',
      promotionType: 'PERCENT_OFF',
      discountRate: '0.2',
      startAt: '2026-10-02T00:00',
      endAt: '2026-10-01T00:00',
    });
    expect(result.ok).toBe(false);
    expect(result.errors.join('；')).toContain('结束时间必须晚于开始时间');
  });

  it('基准价留空是允许的（折后价不可比，不是 0）', () => {
    const result = validateAdPromotion({
      ...emptyAdPromotionInput(),
      promotionCode: 'P-1',
      promotionName: '促销 1',
      shopCode: 'SHOP-1',
      promotionType: 'PERCENT_OFF',
      discountRate: '0.2',
      products: [{ listingId: 'listing-1', basePrice: '', note: '' }],
    });
    expect(result.ok).toBe(true);
  });

  it('🔴 护栏两个阈值都不设 ⇒ 建不出来', () => {
    const result = validateAdGuardrail({
      ...emptyAdGuardrailInput(),
      guardrailCode: 'G-1',
      guardrailName: '护栏 1',
    });
    expect(result.ok).toBe(false);
    expect(result.errors.join('；')).toContain('至少要有一个');
  });

  it('护栏只给一个阈值就够', () => {
    const result = validateAdGuardrail({
      ...emptyAdGuardrailInput(),
      guardrailCode: 'G-1',
      guardrailName: '护栏 1',
      maxDiscountRate: '0.5',
    });
    expect(result.ok).toBe(true);
  });

  it('护栏的折扣幅度上限必须在 (0, 1] 之间', () => {
    const result = validateAdGuardrail({
      ...emptyAdGuardrailInput(),
      guardrailCode: 'G-1',
      guardrailName: '护栏 1',
      maxDiscountRate: '2',
    });
    expect(result.ok).toBe(false);
    expect(result.errors.join('；')).toContain('(0, 1]');
  });
});
