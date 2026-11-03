import { describe, expect, it } from 'vitest';

import {
  coverageStats,
  coverageText,
  currencyHints,
  effectiveText,
  effectiveTone,
  emptyPriceListInput,
  formatInstant,
  intervalText,
  isCurrencyShape,
  itemCountText,
  normalizeCurrency,
  scopeOptions,
  scopeTypeLabel,
  toPriceListInput,
  toSubmitPayload,
  validatePriceList,
} from './price-list-model';
import type { PriceListDetail, PriceListInput, ScopeDirectory } from './price-list-model';

/**
 * 5.1.1.1 价目表前端模型的纯函数断言。
 *
 * <p>这些函数守的是蓝图那条加粗句的**技术落点**：
 * 「**一价目一币种**是硬约束——混币种的价目表会让利润计算失效」，
 * 以及紧挨着它的半开区间约定与「适用范围为空 = 全租户」。
 */

function directory(): ScopeDirectory {
  return {
    legalEntities: [
      {
        id: '11111111-1111-1111-1111-111111111111',
        code: 'US-HQ',
        name: '美国总部',
        baseCurrency: 'USD',
        status: 'ACTIVE',
      },
    ],
    shops: [
      {
        code: 'AMZ-US',
        name: '亚马逊美国自发货店',
        platformCode: 'AMAZON',
        legalEntityCode: 'US-HQ',
        legalEntityName: '美国总部',
        status: 'ACTIVE',
        siteCodes: ['US'],
      },
    ],
    sites: [
      {
        platformCode: 'AMAZON',
        platformName: 'Amazon',
        siteCode: 'US',
        siteName: '美国站',
        countryCode: 'US',
        defaultCurrency: 'USD',
      },
    ],
    sourceNote: '夹具目录',
  };
}

function input(overrides: Partial<PriceListInput> = {}): PriceListInput {
  return { ...emptyPriceListInput(), code: 'NA-RETAIL', name: '北美自发货零售价', ...overrides };
}

function detail(overrides: Partial<PriceListDetail> = {}): PriceListDetail {
  return {
    summary: {
      id: '22222222-2222-2222-2222-222222222222',
      code: 'NA-RETAIL',
      name: '北美自发货零售价',
      currency: 'USD',
      validFrom: '2026-10-01T00:00:00Z',
      validTo: null,
      enabled: true,
      itemCount: 0,
      coverage: {
        scopeTotal: 0,
        legalEntityCount: 0,
        shopCount: 0,
        siteCount: 0,
        appliesToWholeTenant: true,
        text: '全租户（未限定法人 / 店铺 / 站点）',
      },
      effectiveNow: true,
      createdBy: null,
      updatedAt: '2026-10-01T00:00:00Z',
    },
    description: '',
    currency: 'USD',
    validFrom: '2026-10-01T00:00:00Z',
    validTo: null,
    enabled: true,
    note: '',
    version: 3,
    scopes: [],
    coverage: {
      scopeTotal: 0,
      legalEntityCount: 0,
      shopCount: 0,
      siteCount: 0,
      appliesToWholeTenant: true,
      text: '全租户（未限定法人 / 店铺 / 站点）',
    },
    createdAt: '2026-10-01T00:00:00Z',
    updatedAt: '2026-10-01T00:00:00Z',
    ...overrides,
  };
}

describe('币种（一价目一币种）', () => {
  it('归一化成三位大写字母', () => {
    expect(normalizeCurrency(' usd ')).toBe('USD');
    expect(isCurrencyShape('usd')).toBe(true);
    expect(isCurrencyShape('cny')).toBe(true);
  });

  it('混币种写法不合法', () => {
    expect(isCurrencyShape('USD,CNY')).toBe(false);
    expect(isCurrencyShape('USDCNY')).toBe(false);
    expect(isCurrencyShape('US')).toBe(false);
    expect(isCurrencyShape('')).toBe(false);
  });

  it('表单校验拦住混币种', () => {
    const error = validatePriceList(input({ currency: 'USD,CNY' }));
    expect(error?.field).toBe('currency');
    expect(error?.message).toContain('一张价目表只能有一个币种');
  });

  it('币种缺失时被拦住', () => {
    expect(validatePriceList(input({ currency: '' }))?.field).toBe('currency');
  });
});

describe('生效区间（半开区间）', () => {
  it('终点为空写成长久有效而不是「未填」', () => {
    expect(intervalText('2026-10-01T00:00:00Z', null)).toContain('长期有效');
  });

  it('终点标成不含', () => {
    expect(intervalText('2026-10-01T00:00:00Z', '2026-12-01T00:00:00Z')).toContain('不含');
  });

  it('终点不晚于起点时被拦住', () => {
    const error = validatePriceList(
      input({ validFrom: '2026-10-01T00:00:00Z', validTo: '2026-09-01T00:00:00Z' }),
    );
    expect(error?.field).toBe('validTo');
    expect(error?.message).toContain('必须晚于生效起点');
  });

  it('终点与起点相同时也被拦住（空区间没有意义）', () => {
    const error = validatePriceList(
      input({ validFrom: '2026-10-01T00:00:00Z', validTo: '2026-10-01T00:00:00Z' }),
    );
    expect(error?.field).toBe('validTo');
  });

  it('没有生效起点时被拦住', () => {
    expect(validatePriceList(input({ validFrom: '' }))?.field).toBe('validFrom');
  });
});

describe('适用范围', () => {
  it('空范围是合法的（= 适用于全租户）', () => {
    expect(validatePriceList(input({ scopes: [] }))).toBeNull();
  });

  it('站点必须带平台码', () => {
    const error = validatePriceList(
      input({ scopes: [{ type: 'SITE', ref: 'US', platformCode: null }] }),
    );
    expect(error?.field).toBe('scopes');
    expect(error?.message).toContain('站点必须给平台码');
  });

  it('法人不能带平台码', () => {
    const error = validatePriceList(
      input({ scopes: [{ type: 'LEGAL_ENTITY', ref: 'US-HQ', platformCode: 'AMAZON' }] }),
    );
    expect(error?.field).toBe('scopes');
    expect(error?.message).toContain('法人没有平台');
  });

  it('重复的适用对象被拦住', () => {
    const error = validatePriceList(
      input({
        scopes: [
          { type: 'SHOP', ref: 'AMZ-US', platformCode: 'AMAZON' },
          { type: 'SHOP', ref: 'AMZ-US', platformCode: 'AMAZON' },
        ],
      }),
    );
    expect(error?.field).toBe('scopes');
    expect(error?.message).toContain('重复');
  });

  it('目录能给出三类可选项', () => {
    expect(scopeOptions(directory(), 'LEGAL_ENTITY').map((o) => o.ref)).toEqual(['US-HQ']);
    expect(scopeOptions(directory(), 'SHOP').map((o) => o.ref)).toEqual(['AMZ-US']);
    expect(scopeOptions(directory(), 'SITE')[0]?.platformCode).toBe('AMAZON');
    expect(scopeOptions(null, 'SITE')).toEqual([]);
  });

  it('范围类型有中文名', () => {
    expect(scopeTypeLabel('LEGAL_ENTITY')).toBe('法人');
    expect(scopeTypeLabel('SHOP')).toBe('店铺');
    expect(scopeTypeLabel('SITE')).toBe('站点');
    expect(scopeTypeLabel(null)).toBe('未指定');
  });
});

describe('覆盖范围摘要', () => {
  it('用服务端算好的文案，不在前端重算', () => {
    expect(
      coverageText({
        scopeTotal: 3,
        legalEntityCount: 1,
        shopCount: 1,
        siteCount: 1,
        appliesToWholeTenant: false,
        text: '1 法人 / 1 店铺 / 1 站点',
      }),
    ).toBe('1 法人 / 1 店铺 / 1 站点');
    expect(coverageText(null)).toBe('—');
  });

  it('统计卡给出四个数', () => {
    const stats = coverageStats({
      listCount: 3,
      enabledCount: 2,
      effectiveNowCount: 1,
      totalItemCount: 7,
      currencies: [],
      scopeTypes: [],
      lists: [],
      note: '',
    });
    expect(stats.map((s) => s.value)).toEqual([3, 2, 1, 7]);
    expect(coverageStats(null)).toEqual([]);
  });
});

describe('其它显示约定', () => {
  it('价目项数量是真实计数', () => {
    expect(itemCountText(0)).toBe('0 个价目项');
    expect(itemCountText(12)).toBe('12 个价目项');
    expect(itemCountText(null)).toBe('未维护');
  });

  it('生效状态有语气与文案', () => {
    expect(effectiveTone(true)).toBe('success');
    expect(effectiveTone(false)).toBe('neutral');
    expect(effectiveText(true)).toBe('此刻生效');
    expect(effectiveText(false)).toBe('此刻不生效');
  });

  it('币种提示来自上游主数据（不是权威字典）', () => {
    expect(currencyHints(directory())).toEqual(['USD']);
    expect(currencyHints(null)).toEqual([]);
  });

  it('时刻保留时区偏移', () => {
    expect(formatInstant('2026-10-01T00:00:00Z')).toContain('UTC');
    expect(formatInstant(null)).toBe('—');
  });
});

describe('表单与载荷', () => {
  it('空表单带上生效起点', () => {
    expect(emptyPriceListInput().validFrom).not.toBe('');
    expect(emptyPriceListInput().scopes).toEqual([]);
  });

  it('详情能回填表单', () => {
    const form = toPriceListInput(
      detail({
        scopes: [
          {
            id: '33333333-3333-3333-3333-333333333333',
            type: 'SHOP',
            typeLabel: '店铺',
            ref: 'AMZ-US',
            name: '亚马逊美国自发货店',
            platformCode: 'AMAZON',
          },
        ],
      }),
    );
    expect(form.code).toBe('NA-RETAIL');
    expect(form.currency).toBe('USD');
    expect(form.scopes).toEqual([{ type: 'SHOP', ref: 'AMZ-US', platformCode: 'AMAZON' }]);
    expect(form.version).toBe(3);
  });

  it('提交载荷把编码与币种归一化', () => {
    const payload = toSubmitPayload(
      input({
        code: '  na-retail ',
        currency: ' usd ',
        scopes: [{ type: 'SITE', ref: ' US ', platformCode: ' amazon ' }],
      }),
    );
    expect(payload.code).toBe('NA-RETAIL');
    expect(payload.currency).toBe('USD');
    expect(payload.scopes[0]).toEqual({ type: 'SITE', ref: 'US', platformCode: 'AMAZON' });
  });
});
