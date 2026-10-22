import { describe, expect, it } from 'vitest';
import {
  emptyAdFlashSaleClaimInput,
  emptyAdFlashSaleInput,
  emptyAdFlashSaleRedeemInput,
  flashClaimStatusLabel,
  flashCountText,
  flashLocalDateTimeInput,
  flashMoney,
  flashStatusLabel,
  flashStatusTone,
  flashSubsidyText,
  flashSyncStatusLabel,
  flashTimestampText,
  validateAdFlashSale,
  validateAdFlashSaleClaim,
  validateAdFlashSaleRedeem,
} from './ad-flash-sale-model';

/**
 * 14.4.1.2 秒杀 前端模型的**纯函数**断言。
 *
 * 🔴 钉住这几件事：
 *   * 不可比 / 不可测算显示成「不可比 / 不可测算」，**不是 0.00**；
 *   * 核销表单**没有订单标识就不让提交**（蓝图加粗句）；
 *   * 秒杀起止时间必填且有序、秒杀价必填、限领留空 = 不限领。
 */
describe('14.4.1.2 秒杀前端模型', () => {
  it('金额 null 显示「不可比」，0 显示 0.00', () => {
    expect(flashMoney(null, 'USD')).toBe('不可比');
    expect(flashMoney(0, 'USD')).toBe('0.00 USD');
    expect(flashMoney(9.9, 'USD')).toBe('9.90 USD');
  });

  it('🔴 补贴成本 null 显示「不可测算」，不是 0.00', () => {
    expect(flashSubsidyText(null)).toBe('不可测算');
    expect(flashSubsidyText(0)).toBe('0.00');
    expect(flashSubsidyText(10)).toBe('10.00');
  });

  it('数量 null 显示「—」', () => {
    expect(flashCountText(null)).toBe('—');
    expect(flashCountText(0)).toBe('0');
  });

  it('状态标签齐全', () => {
    expect(flashStatusLabel('ACTIVE')).toBe('进行中');
    expect(flashStatusLabel('CANCELLED')).toBe('已停用');
    expect(flashStatusTone('ACTIVE')).toBe('text-emerald-600');
    expect(flashClaimStatusLabel('CLAIMED')).toBe('已领取未核销');
    expect(flashSyncStatusLabel('LOCAL_ONLY')).toBe('本地已建，平台侧还没有');
  });

  it('时刻展示与 datetime-local 回填用本地时区', () => {
    expect(flashTimestampText(null)).toBe('—');
    expect(flashLocalDateTimeInput(null)).toBe('');
    expect(flashLocalDateTimeInput('2026-10-01T00:00:00Z')).toMatch(
      /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}$/,
    );
  });
});

describe('14.4.1.2 秒杀表单校验', () => {
  it('空表单不通过，并逐条说清缺什么', () => {
    const result = validateAdFlashSale(emptyAdFlashSaleInput());
    expect(result.ok).toBe(false);
    const text = result.errors.join('|');
    expect(text).toContain('秒杀编码必填');
    expect(text).toContain('秒杀商品');
    expect(text).toContain('秒杀价必填');
    expect(text).toContain('开始时间必填');
    expect(text).toContain('结束时间必填');
  });

  it('结束时间必须晚于开始时间', () => {
    const base = {
      ...emptyAdFlashSaleInput(),
      saleCode: 'FS1',
      saleName: '秒杀',
      shopCode: 'S',
      platformId: 'p',
      listingId: 'l',
      flashPrice: '9.9',
      startAt: '2026-10-02T10:00',
      endAt: '2026-10-01T10:00',
    };
    expect(validateAdFlashSale(base).errors.join('|')).toContain('结束时间必须晚于开始时间');
  });

  it('原价留空 = 不可比（合法）；限领留空 = 不限领（合法）', () => {
    const base = {
      ...emptyAdFlashSaleInput(),
      saleCode: 'FS2',
      saleName: '秒杀',
      shopCode: 'S',
      platformId: 'p',
      listingId: 'l',
      flashPrice: '9.9',
      startAt: '2026-10-01T10:00',
      endAt: '2026-10-01T12:00',
      originalPrice: '',
      perUserLimit: '',
    };
    const result = validateAdFlashSale(base);
    expect(result.errors.join('|')).not.toContain('原价');
    expect(result.errors.join('|')).not.toContain('每人限领');
  });

  it('领取表单：领取人标识必填、件数必须大于 0', () => {
    const missing = validateAdFlashSaleClaim(emptyAdFlashSaleClaimInput());
    expect(missing.errors.join('|')).toContain('领取人标识必填');

    const badQuantity = validateAdFlashSaleClaim({ ...emptyAdFlashSaleClaimInput(), quantity: '0' });
    expect(badQuantity.errors.join('|')).toContain('领取件数必须大于 0');

    const ok = validateAdFlashSaleClaim({ recipientKey: 'user-1', quantity: '1' });
    expect(ok.ok).toBe(true);
  });

  it('🔴 核销表单：没有订单标识就不让提交', () => {
    const missing = validateAdFlashSaleRedeem(emptyAdFlashSaleRedeemInput());
    expect(missing.ok).toBe(false);
    expect(missing.errors.join('|')).toContain('订单标识必填');

    expect(validateAdFlashSaleRedeem({ orderRef: 'SO-1', claimId: '' }).ok).toBe(true);
  });
});
