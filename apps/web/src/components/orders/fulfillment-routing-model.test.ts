import { describe, expect, it } from 'vitest';
import {
  canAdvance,
  dimensionUnavailableReason,
  mcfStatusLabel,
  mcfUsable,
  moneyText,
  pathLabel,
  qtyText,
  reasonLabel,
  switchTriggerLabel,
  timeText,
} from './fulfillment-routing-model';

/**
 * 9.3.2.2 前端模型单测。
 *
 * <p>重点不是「函数能不能跑」，而是几个**会被误显示**的地方：
 * 「没有快照」不能显示成 0、「未核验」不能显示成「有资质」、
 * 金额不能在展示时二次取整。
 */
describe('9.3.2.2 履约路径展示口径', () => {
  it('缺货切换的每个触发原因都有中文名', () => {
    for (const code of [
      'PLATFORM_STOCK_SHORTAGE',
      'PLATFORM_STOCK_MISSING',
      'MCF_NOT_AUTHORIZED',
      'COST_ADVANTAGE',
      'LEAD_TIME_ADVANTAGE',
      'MANUAL_OVERRIDE',
    ]) {
      expect(switchTriggerLabel(code), `${code} 没有中文名`).not.toBe(code);
    }
  });

  it('决策原因码都有中文名，未知的原样返回', () => {
    expect(reasonLabel('PLATFORM_STOCK_INSUFFICIENT')).toBe('平台仓库存不足');
    expect(reasonLabel('SOMETHING_NEW')).toBe('SOMETHING_NEW');
  });

  it('三条履约路径都有中文名', () => {
    expect(pathLabel('PLATFORM_FBA')).toBe('平台仓 FBA');
    expect(pathLabel('MERCHANT_OWN')).toBe('自有仓自发货');
    expect(pathLabel('THIRD_PARTY')).toBe('第三方仓自发货');
  });

  it('没有库存快照显示成「—」而不是 0', () => {
    // 0 与「没测过」在决策上不等价：画成 0 会让人以为这单确实没货。
    expect(qtyText(null)).toBe('—');
    expect(qtyText(undefined)).toBe('—');
    expect(qtyText(0)).toBe('0');
  });

  it('金额按后端给的结算口径显示，不做二次取整', () => {
    expect(moneyText(4.26)).toBe('4.26 USD');
    expect(moneyText(6.5)).toBe('6.50 USD');
    expect(moneyText(null)).toBe('—');
    // 4.255 若在展示层再取整会变成 4.26，与后端已取整的值一致；
    // 真正的风险是前端自己 round 出一个与账面不同的数，所以这里断言「原样两位小数」。
    expect(moneyText(4.2)).toBe('4.20 USD');
  });

  it('「未核验」与「已授权但已过期」在页面上被分开', () => {
    const base = {
      id: 'x',
      platformCode: 'AMAZON',
      shopCode: 'SHOP-US',
      authorizationSource: 'TENANT_MAINTAINED',
      sourceReference: '',
      note: '',
      createdByMembershipId: 'u',
      createdAt: '2026-10-01T00:00:00Z',
      updatedAt: '2026-10-01T00:00:00Z',
    };
    expect(
      mcfUsable({
        ...base,
        status: 'AUTHORIZED',
        expired: false,
        verifiedAt: null,
        expiresAt: null,
      }),
    ).toBe(true);
    // 有资质但已过期：仍不可用 —— 页面据此提示「需先续期」而不是「需先核验」。
    expect(
      mcfUsable({
        ...base,
        status: 'AUTHORIZED',
        expired: true,
        verifiedAt: null,
        expiresAt: null,
      }),
    ).toBe(false);
    // 从未核验：状态名与「已授权」差得足够远，不会被误读成可用。
    expect(mcfStatusLabel('UNKNOWN')).toBe('未核验');
    expect(
      mcfUsable({ ...base, status: 'UNKNOWN', expired: false, verifiedAt: null, expiresAt: null }),
    ).toBe(false);
  });

  it('只有待确认的决策可以推进状态', () => {
    expect(canAdvance('DECIDED')).toBe(true);
    // CONFIRMED / CANCELLED 是终态：按钮不出现，避免界面许诺一个做不到的动作。
    expect(canAdvance('CONFIRMED')).toBe(false);
    expect(canAdvance('CANCELLED')).toBe(false);
  });

  it('不可用的输入维度把原因带出来，而不是画成 0', () => {
    const dims = [
      { code: 'ORDER_LINE', available: false, unavailableReason: '订单表尚未交付' },
      { code: 'CHANNEL_STOCK', available: true, unavailableReason: null },
    ];
    expect(dimensionUnavailableReason(dims, 'ORDER_LINE')).toBe('订单表尚未交付');
    // 可用的维度不返回原因：界面上不该为可用项显示警告。
    expect(dimensionUnavailableReason(dims, 'CHANNEL_STOCK')).toBeNull();
  });

  it('时刻无法解析时返回原串而不是「Invalid Date」', () => {
    // 一个能被用户原样报给后端的串，比一个错位的「看起来对」的时间有用。
    expect(timeText('not-a-time')).toBe('not-a-time');
    expect(timeText(null)).toBe('—');
    expect(timeText('2026-10-08T08:30:00+08:00')).toContain('2026-10-08');
  });
});
