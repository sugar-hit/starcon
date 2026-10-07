import { describe, expect, it } from 'vitest';
import {
  allocationComplete,
  expiryStatusLabels,
  expiryStatusTone,
  expiryStrategyLabels,
  expiryText,
  leadDaysText,
  parseQuantity,
  policyEnabledText,
  shortfallText,
} from './inventory-expiry-model';

/**
 * 7.3.1.2 效期与先进先出的展示口径。
 *
 * <p>这几个函数承载的是「同一个数字怎么读」与「缺口该往哪派工」的问题，
 * 不是格式化问题：
 *
 * <ul>
 *   <li>过期批次的负数天数如果显示成「剩余 -2471 天」，用户会以为系统算错了；
 *   <li>临期阈值逐 SKU 不同，不标来源的话用户会以为系统算错了；
 *   <li>缺口不拆开说，派工的人就还得自己回去翻库存才知道该找谁处理。
 * </ul>
 */
describe('效期剩余天数文案', () => {
  it('无失效日期显示为非效期品而不是空白或 0 天', () => {
    expect(expiryText(null, null)).toBe('非效期品');
  });

  it('已过期批次的剩余天数按正数表述', () => {
    expect(expiryText('2020-01-01', -2471)).toBe('2020-01-01（已过期 2471 天）');
  });

  it('今天到期与还有 N 天分开表述', () => {
    expect(expiryText('2026-10-07', 0)).toBe('2026-10-07（今天到期）');
    expect(expiryText('2026-12-31', 85)).toBe('2026-12-31（还有 85 天）');
  });

  it('有失效日期但天数读不回来时只显示日期,不编一个天数', () => {
    // 后端纯文本 SQL 取整型表达式若类型不匹配会退化成 null；此时宁可少说，
    // 也不能显示一个假的 0 天 —— 那会让货看起来是今天到期。
    expect(expiryText('2026-12-31', null)).toBe('2026-12-31');
  });
});

describe('效期档位的语气与标签', () => {
  it('过期最重、临期次之、非效期品中性、正常无色', () => {
    expect(expiryStatusTone('EXPIRED')).toBe('danger');
    expect(expiryStatusTone('EXPIRING')).toBe('warning');
    expect(expiryStatusTone('NO_EXPIRY')).toBe('muted');
    expect(expiryStatusTone('HEALTHY')).toBe('');
  });

  it('过期与临期是两个不同的处置动作,标签不能混', () => {
    expect(expiryStatusLabels.EXPIRED).toBe('已过期');
    expect(expiryStatusLabels.EXPIRING).toBe('临期');
  });
});

describe('临期阈值来源', () => {
  it('区分本 SKU 配置与默认兜底', () => {
    // 同样是 3 天到期，配 7 天窗口的报临期、配 1 天的报正常；
    // 不标来源用户会以为系统算错了。
    expect(leadDaysText('POLICY', 7)).toBe('7 天（本 SKU 配置）');
    expect(leadDaysText('DEFAULT', 30)).toBe('30 天（默认）');
  });
});

describe('分配缺口的可读说明', () => {
  it('四类不可用量逐条说出,不合成一句', () => {
    const lines = shortfallText({
      expiredAvailable: 20,
      nonSellableAvailable: 15,
      nonPickableAvailable: 40,
      noExpiryAvailable: 30,
      noLotAvailable: 0,
    });
    expect(lines).toHaveLength(4);
    expect(lines[0]).toContain('已过期 20');
    expect(lines[0]).toContain('临期品处理');
    expect(lines[1]).toContain('非可售 15');
    expect(lines[2]).toContain('不可拣库位 40');
    expect(lines[3]).toContain('无失效日期 30');
  });

  it('各类都为 0 时不编出缺口说明', () => {
    expect(
      shortfallText({
        expiredAvailable: 0,
        nonSellableAvailable: 0,
        nonPickableAvailable: 0,
        noExpiryAvailable: 0,
        noLotAvailable: 0,
      }),
    ).toEqual([]);
  });

  it('全额可出库时判定为完整', () => {
    expect(allocationComplete({ allocatedQuantity: 100, shortfallQuantity: 0 })).toBe(true);
    expect(allocationComplete({ allocatedQuantity: 80, shortfallQuantity: 20 })).toBe(false);
  });
});

describe('分配策略文案', () => {
  it('未启用策略时也给出可读名称,不显示 NONE 原样', () => {
    expect(expiryStrategyLabels.NONE).toContain('未启用策略');
    expect(expiryStrategyLabels.FEFO).toBe('先到期先出');
  });

  it('未配置与已配置但停用分开表述', () => {
    expect(policyEnabledText({ configured: false, enabled: false })).toContain('未配置');
    expect(policyEnabledText({ configured: true, enabled: false })).toContain('已停用');
    expect(policyEnabledText({ configured: true, enabled: true })).toBe('已启用');
  });
});

describe('分配数量的输入解析', () => {
  it('空串与非法值返回 null 而不是静默当 0', () => {
    // 静默把空串当 0 会让「忘了填数量」变成一次「出库 0 件」的合法请求。
    expect(parseQuantity('')).toBeNull();
    expect(parseQuantity('   ')).toBeNull();
    expect(parseQuantity('abc')).toBeNull();
  });

  it('合法数值原样解析,含小数', () => {
    expect(parseQuantity('120')).toBe(120);
    expect(parseQuantity(' 12.5 ')).toBe(12.5);
  });
});
