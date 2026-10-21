import { describe, expect, it } from 'vitest';
import {
  claimKindLabel,
  channelTypeLabel,
  couponCodeStatusLabel,
  couponStatusLabel,
  couponStatusTone,
  couponTypeLabel,
  countText,
  emptyAdClaimInput,
  emptyAdCouponChannelInput,
  emptyAdCouponInput,
  emptyAdRedeemInput,
  localDateTimeInput,
  money,
  ratioText,
  subsidyText,
  syncStatusLabel,
  timestampText,
  validateAdClaim,
  validateAdCoupon,
  validateAdCouponChannel,
  validateAdRedeem,
} from './ad-coupon-model';

/**
 * 14.4.1.2 优惠券 前端模型的**纯函数**断言。
 *
 * 🔴 这些断言钉的是「页面上最容易做丢的几件事」：
 *   * 不可比 / 不可测算显示成「不可比 / 不可测算」，**不是 0.00**；
 *   * 核销表单**没有订单标识就不让提交**（蓝图加粗句）；
 *   * 折扣口径三选一的前端校验与服务端同口径（别让用户白跑一趟）；
 *   * 限领表单按「累计」口径提示。
 */
describe('14.4.1.2 优惠券前端模型', () => {
  it('金额 null 显示「不可比」，0 显示 0.00 —— 两者含义相反', () => {
    expect(money(null, 'USD')).toBe('不可比');
    expect(money(undefined)).toBe('不可比');
    expect(money(0, 'USD')).toBe('0.00 USD');
    expect(money(12.5, 'USD')).toBe('12.50 USD');
  });

  it('🔴 补贴成本 null 显示「不可测算」，不是 0.00', () => {
    expect(subsidyText(null)).toBe('不可测算');
    expect(subsidyText(undefined)).toBe('不可测算');
    expect(subsidyText(0)).toBe('0.00');
    expect(subsidyText(10)).toBe('10.00');
  });

  it('折扣率 null 显示「不可比」，不是 0.00%', () => {
    expect(ratioText(null)).toBe('不可比');
    expect(ratioText(0.1)).toBe('10.00%');
    expect(ratioText(1)).toBe('100.00%');
  });

  it('数量 null 显示「—」，0 显示 0', () => {
    expect(countText(null)).toBe('—');
    expect(countText(0)).toBe('0');
    expect(countText(3)).toBe('3');
  });

  it('平台同步状态必须把「平台侧还没有」说清楚', () => {
    expect(syncStatusLabel('LOCAL_ONLY')).toBe('本地已建，平台侧还没有');
    expect(syncStatusLabel('SYNCED')).toBe('平台侧已一致');
    expect(syncStatusLabel('FAILED')).toBe('回写失败');
  });

  it('取值标签齐全：券类型 / 状态 / 领取方式 / 渠道 / 券码状态', () => {
    expect(couponTypeLabel('DISCOUNT_CODE')).toBe('折扣码');
    expect(couponTypeLabel('FULL_REDUCTION')).toBe('满减');
    expect(couponTypeLabel('BOGO')).toBe('买赠');
    expect(couponStatusLabel('ACTIVE')).toBe('生效中');
    expect(couponStatusLabel('CANCELLED')).toBe('已停用');
    expect(couponStatusTone('ACTIVE')).toBe('text-emerald-600');
    expect(couponStatusTone('DRAFT')).toBe('text-muted-foreground');
    expect(claimKindLabel('PUSH')).toBe('定向发放');
    expect(channelTypeLabel('ON_SITE')).toBe('站内');
    expect(channelTypeLabel('SOCIAL')).toBe('社媒');
    expect(channelTypeLabel('DIRECT')).toBe('定向发放');
    expect(couponCodeStatusLabel('GENERATED')).toBe('已生成未领');
    expect(couponCodeStatusLabel('USED')).toBe('已核销');
  });

  it('时刻展示与 datetime-local 回填用本地时区', () => {
    expect(timestampText(null)).toBe('—');
    expect(timestampText('2026-10-01T00:00:00Z')).not.toBe('—');
    expect(localDateTimeInput(null)).toBe('');
    expect(localDateTimeInput('2026-10-01T00:00:00Z')).toMatch(/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}$/);
  });
});

describe('14.4.1.2 优惠券表单校验', () => {
  it('空表单不通过，并逐条说清缺什么', () => {
    const result = validateAdCoupon(emptyAdCouponInput());
    expect(result.ok).toBe(false);
    expect(result.errors.join('|')).toContain('券编码必填');
    expect(result.errors.join('|')).toContain('券名称必填');
    expect(result.errors.join('|')).toContain('参与店铺必填');
  });

  it('折扣码：折扣率与面额必须二选一（两个都填让利额有两个答案）', () => {
    const base = { ...emptyAdCouponInput(), couponCode: 'C1', couponName: '券', shopCode: 'S', platformId: 'p' };
    const both = validateAdCoupon({ ...base, discountRate: '0.1', discountValue: '5' });
    expect(both.ok).toBe(false);
    expect(both.errors.join('|')).toContain('两个都填让利额会有两个答案');

    const neither = validateAdCoupon({ ...base });
    expect(neither.ok).toBe(false);

    const rateOnly = validateAdCoupon({ ...base, discountRate: '0.1' });
    expect(rateOnly.errors.join('|')).not.toContain('两个都填让利额会有两个答案');

    const valueOnly = validateAdCoupon({ ...base, discountValue: '5' });
    expect(valueOnly.errors.join('|')).not.toContain('两个都填让利额会有两个答案');
  });

  it('满减：门槛与减免额都必填', () => {
    const base = {
      ...emptyAdCouponInput(),
      couponCode: 'C2',
      couponName: '券',
      shopCode: 'S',
      platformId: 'p',
      couponType: 'FULL_REDUCTION',
    };
    const missing = validateAdCoupon(base);
    expect(missing.errors.join('|')).toContain('满减券必须给减免额');
    expect(missing.errors.join('|')).toContain('满减券必须给门槛');

    const ok = validateAdCoupon({ ...base, discountValue: '5', thresholdAmount: '20' });
    expect(ok.errors.join('|')).not.toContain('满减券必须给减免额');
  });

  it('买赠：买 N 送 M 都必填，且不许给金额口径', () => {
    const base = {
      ...emptyAdCouponInput(),
      couponCode: 'C3',
      couponName: '券',
      shopCode: 'S',
      platformId: 'p',
      couponType: 'BOGO',
    };
    const missing = validateAdCoupon(base);
    expect(missing.errors.join('|')).toContain('买赠券必须给买 N 与送 M');

    const withMoney = validateAdCoupon({ ...base, buyQuantity: '2', giftQuantity: '1', discountValue: '5' });
    expect(withMoney.errors.join('|')).toContain('买赠券没有金额口径');
  });

  it('限领留空 = 不限领；填了必须是大于 0 的数', () => {
    const base = {
      ...emptyAdCouponInput(),
      couponCode: 'C4',
      couponName: '券',
      shopCode: 'S',
      platformId: 'p',
      discountRate: '0.1',
      perUserLimit: '',
    };
    const unlimited = validateAdCoupon(base);
    expect(unlimited.errors.join('|')).not.toContain('每人限领');

    const bad = validateAdCoupon({ ...base, perUserLimit: '0' });
    expect(bad.errors.join('|')).toContain('每人限领张数必须大于 0');
  });

  it('券码随机段长度必须是 4-32 的整数', () => {
    const base = {
      ...emptyAdCouponInput(),
      couponCode: 'C5',
      couponName: '券',
      shopCode: 'S',
      platformId: 'p',
      discountRate: '0.1',
    };
    expect(validateAdCoupon({ ...base, codeLength: '3' }).errors.join('|')).toContain('4-32');
    expect(validateAdCoupon({ ...base, codeLength: '33' }).errors.join('|')).toContain('4-32');
    expect(validateAdCoupon({ ...base, codeLength: '8' }).errors.join('|')).not.toContain('4-32');
  });

  it('失效时间必须晚于生效时间', () => {
    const base = {
      ...emptyAdCouponInput(),
      couponCode: 'C6',
      couponName: '券',
      shopCode: 'S',
      platformId: 'p',
      discountRate: '0.1',
    };
    const bad = validateAdCoupon({ ...base, startAt: '2026-10-02T10:00', endAt: '2026-10-01T10:00' });
    expect(bad.errors.join('|')).toContain('失效时间必须晚于生效时间');
  });

  it('🔴 领取表单：收件人标识必填，张数必须大于 0', () => {
    const missing = validateAdClaim(emptyAdClaimInput());
    expect(missing.errors.join('|')).toContain('收件人标识必填');

    const badQuantity = validateAdClaim({ ...emptyAdClaimInput(), quantity: '0' });
    expect(badQuantity.errors.join('|')).toContain('领取张数必须大于 0');

    const ok = validateAdClaim({ ...emptyAdClaimInput(), recipientKey: 'customer-1', quantity: '1' });
    expect(ok.ok).toBe(true);
  });

  it('🔴 核销表单：没有订单标识就不让提交', () => {
    const missing = validateAdRedeem(emptyAdRedeemInput());
    expect(missing.ok).toBe(false);
    expect(missing.errors.join('|')).toContain('订单标识必填');

    const ok = validateAdRedeem({ orderRef: 'SO-1', code: '', orderAmount: '100' });
    expect(ok.ok).toBe(true);
  });

  it('投放渠道表单：投放位必填、时间窗有序', () => {
    const missing = validateAdCouponChannel(emptyAdCouponChannelInput());
    expect(missing.errors.join('|')).toContain('投放位必填');

    const bad = validateAdCouponChannel({
      ...emptyAdCouponChannelInput(),
      placement: '首页 banner',
      planStartAt: '2026-10-02T10:00',
      planEndAt: '2026-10-01T10:00',
    });
    expect(bad.errors.join('|')).toContain('投放结束时间必须晚于开始时间');
  });
});
