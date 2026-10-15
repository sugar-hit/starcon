import { describe, expect, it } from 'vitest';

import {
  EMPTY_CHANNEL_FORM,
  VOLUME_DIVISOR,
  billingModeLabel,
  isEffectiveAt,
  optionalNumber,
  parseList,
  readinessMeta,
  restrictionKindLabel,
  surchargeText,
  toChannelForm,
  validateChannelForm,
  verdictMeta,
} from './logistics-channel-model';
import type {
  ChannelSurchargeItem,
  LogisticsChannelRow,
  SaveChannelInput,
} from './logistics-channel-model';

/**
 * 10.1.1.2 渠道配置前端模型测试。
 *
 * <p>只测**纯函数**：渲染与取数由 playwright / 集成测试兜。断言重点放在
 * 「错了会算错钱或放行不该发的货」的那几条上 —— 也就是 `validateChannelForm`
 * 里与后端 DB CHECK / 触发器同款的规则。
 *
 * <p>⚠️ 这里断言的每一条前端校验都**不是安全边界**：真正的硬拦截在后端
 * （DB 层双向触发器 + POST eligibility 判定端点）。前端只是提前告诉人，
 * 所以测试断言的是「提前报错」这个行为本身，而不是「只有前端挡得住」。
 */

function input(overrides: Partial<SaveChannelInput> = {}): SaveChannelInput {
  return {
    ...EMPTY_CHANNEL_FORM,
    carrierProfileId: 'carrier-1',
    channelCode: 'SF-US-SMALL',
    channelName: '顺丰美国小包',
    billingCurrency: 'USD',
    baseRate: 28.5,
    scopes: [{ scopeKind: 'COUNTRY', scopeValue: 'US' }],
    ...overrides,
  };
}

function surcharge(overrides: Partial<ChannelSurchargeItem> = {}): ChannelSurchargeItem {
  return {
    surchargeKind: 'FUEL',
    chargeMode: 'PERCENT',
    amount: 18,
    effectiveFrom: null,
    effectiveTo: null,
    note: '',
    ...overrides,
  };
}

describe('validateChannelForm · 必填与基础口径', () => {
  it('一份齐备的配置没有任何问题', () => {
    expect(validateChannelForm(input())).toEqual([]);
  });

  it('没选承运商要报 —— 渠道挂在承运商之下', () => {
    const problems = validateChannelForm(input({ carrierProfileId: null }));
    expect(problems.some((p) => p.includes('承运商'))).toBe(true);
  });

  it('渠道代码与名称为空要报', () => {
    const problems = validateChannelForm(input({ channelCode: '  ', channelName: '' }));
    expect(problems.some((p) => p.includes('渠道代码'))).toBe(true);
    expect(problems.some((p) => p.includes('渠道名称'))).toBe(true);
  });

  it('币种必须是三位大写字母', () => {
    expect(
      validateChannelForm(input({ billingCurrency: 'usd' })).some((p) => p.includes('币种')),
    ).toBe(false);
    expect(
      validateChannelForm(input({ billingCurrency: 'US' })).some((p) => p.includes('币种')),
    ).toBe(true);
  });

  it('基础单价为 0 要报（试算算不出运费）', () => {
    const problems = validateChannelForm(input({ baseRate: 0 }));
    expect(problems.some((p) => p.includes('基础单价'))).toBe(true);
  });

  it('一条适用范围都不登记要报 —— 对谁都不可用的渠道', () => {
    const problems = validateChannelForm(input({ scopes: [] }));
    expect(problems.some((p) => p.includes('适用范围'))).toBe(true);
  });

  it('适用国家必须是两位字母', () => {
    const problems = validateChannelForm(
      input({ scopes: [{ scopeKind: 'COUNTRY', scopeValue: 'USA' }] }),
    );
    expect(problems.some((p) => p.includes('适用国家'))).toBe(true);
  });
});

describe('validateChannelForm · 计费方式', () => {
  it('按件计费时折算基数必须是 1 —— 否则运费系统性偏低', () => {
    const problems = validateChannelForm(input({ billingMode: 'BY_PIECE', chargeDivisor: 0.5 }));
    expect(problems.some((p) => p.includes('按件'))).toBe(true);
  });

  it('按件计费且基数为 1 时不报', () => {
    expect(
      validateChannelForm(input({ billingMode: 'BY_PIECE', chargeDivisor: 1 })).some((p) =>
        p.includes('按件'),
      ),
    ).toBe(false);
  });

  it('按体积计费需要大于 0 的折算基数', () => {
    const problems = validateChannelForm(input({ billingMode: 'BY_VOLUME', chargeDivisor: 0 }));
    expect(problems.some((p) => p.includes('折算基数'))).toBe(true);
  });

  it('按体积的行业通用折算基数是 6000（cm³/kg）', () => {
    expect(VOLUME_DIVISOR).toBe(6000);
    expect(
      validateChannelForm(input({ billingMode: 'BY_VOLUME', chargeDivisor: VOLUME_DIVISOR })),
    ).toEqual([]);
  });
});

describe('validateChannelForm · 申报限制', () => {
  it('申报下限必须小于上限', () => {
    const problems = validateChannelForm(
      input({
        declarationMinValue: 100,
        declarationMaxValue: 100,
        declarationCurrency: 'USD',
      }),
    );
    expect(problems.some((p) => p.includes('申报价值下限'))).toBe(true);
  });

  it('登记了申报上下限就必须填申报币种 —— 不填试算没法换算', () => {
    const problems = validateChannelForm(
      input({ declarationMinValue: 1, declarationMaxValue: 100, declarationCurrency: '' }),
    );
    expect(problems.some((p) => p.includes('申报币种'))).toBe(true);
  });

  it('只登记上限也要填币种', () => {
    const problems = validateChannelForm(
      input({ declarationMaxValue: 100, declarationCurrency: '' }),
    );
    expect(problems.some((p) => p.includes('申报币种'))).toBe(true);
  });
});

describe('validateChannelForm · 保价规则', () => {
  it('不承保却填了费率 / 最低保费是幽灵配置，要报', () => {
    const problems = validateChannelForm(
      input({ insuranceMode: 'NONE', insuranceRatePct: 2, insuranceMinFee: 5 }),
    );
    expect(problems.some((p) => p.includes('幽灵配置'))).toBe(true);
  });

  it('按申报价值投保必须有费率，否则保费恒为 0', () => {
    const problems = validateChannelForm(
      input({ insuranceMode: 'DECLARED_VALUE', insuranceRatePct: 0, insuranceMinFee: 0 }),
    );
    expect(problems.some((p) => p.includes('保费恒为 0'))).toBe(true);
  });

  it('按申报价值投保配了费率与最低保费时不报', () => {
    expect(
      validateChannelForm(
        input({
          insuranceMode: 'DECLARED_VALUE',
          insuranceRatePct: 1.5,
          insuranceMinFee: 2,
          insuranceMaxValue: 500,
        }),
      ),
    ).toEqual([]);
  });
});

describe('validateChannelForm · 🔴 禁限品类不得同时是适用品类', () => {
  it('同一品类既是适用品类又被 BLOCK 时要报 —— 会被承运商拒收产生额外费用', () => {
    const problems = validateChannelForm(
      input({
        scopes: [
          { scopeKind: 'COUNTRY', scopeValue: 'US' },
          { scopeKind: 'CATEGORY', scopeValue: 'POWER_BANK' },
        ],
        restrictions: [
          {
            restrictionKind: 'PROHIBITED_CATEGORY',
            subject: 'POWER_BANK',
            restrictionMode: 'BLOCK',
            carrierReason: '移动电源需 DG 认证',
          },
        ],
      }),
    );
    expect(problems.some((p) => p.includes('POWER_BANK') && p.includes('拒收'))).toBe(true);
  });

  it('MANUAL_REVIEW 级的禁限同样算冲突（它也不该同时是适用品类）', () => {
    const problems = validateChannelForm(
      input({
        scopes: [{ scopeKind: 'CATEGORY', scopeValue: 'AQUEOUS' }],
        restrictions: [
          {
            restrictionKind: 'PROHIBITED_CATEGORY',
            subject: 'AQUEOUS',
            restrictionMode: 'MANUAL_REVIEW',
            carrierReason: '',
          },
        ],
      }),
    );
    expect(problems.some((p) => p.includes('AQUEOUS'))).toBe(true);
  });

  it('大小写不同也算同一个品类 —— 否则 DE/De 能绕过去', () => {
    const problems = validateChannelForm(
      input({
        scopes: [{ scopeKind: 'CATEGORY', scopeValue: 'power_bank' }],
        restrictions: [
          {
            restrictionKind: 'PROHIBITED_CATEGORY',
            subject: 'POWER_BANK',
            restrictionMode: 'BLOCK',
            carrierReason: '',
          },
        ],
      }),
    );
    expect(problems.some((p) => p.includes('power_bank'))).toBe(true);
  });

  it('禁限的是另一个品类时不报', () => {
    const problems = validateChannelForm(
      input({
        scopes: [{ scopeKind: 'CATEGORY', scopeValue: 'POWER_BANK' }],
        restrictions: [
          {
            restrictionKind: 'PROHIBITED_CATEGORY',
            subject: 'AQUEOUS',
            restrictionMode: 'BLOCK',
            carrierReason: '',
          },
        ],
      }),
    );
    expect(problems).toEqual([]);
  });

  it('电池 / 液体 / 磁力是渠道级限制，不与适用品类冲突', () => {
    const problems = validateChannelForm(
      input({
        scopes: [{ scopeKind: 'CATEGORY', scopeValue: 'POWER_BANK' }],
        restrictions: [
          { restrictionKind: 'BATTERY', subject: '', restrictionMode: 'BLOCK', carrierReason: '' },
        ],
      }),
    );
    expect(problems).toEqual([]);
  });
});

describe('validateChannelForm · 旺季附加费必须带生效区间', () => {
  it('旺季费缺区间要报 —— 没有区间就答不出「现在要不要加」', () => {
    const problems = validateChannelForm(
      input({ surcharges: [surcharge({ surchargeKind: 'PEAK_SEASON' })] }),
    );
    expect(problems.some((p) => p.includes('生效起止时刻'))).toBe(true);
  });

  it('旺季费只有起点也要报', () => {
    const problems = validateChannelForm(
      input({
        surcharges: [
          surcharge({
            surchargeKind: 'PEAK_SEASON',
            effectiveFrom: '2026-11-01T00:00:00Z',
            effectiveTo: null,
          }),
        ],
      }),
    );
    expect(problems.some((p) => p.includes('生效起止时刻'))).toBe(true);
  });

  it('旺季费起点不早于终点要报', () => {
    const problems = validateChannelForm(
      input({
        surcharges: [
          surcharge({
            surchargeKind: 'PEAK_SEASON',
            effectiveFrom: '2026-12-01T00:00:00Z',
            effectiveTo: '2026-11-01T00:00:00Z',
          }),
        ],
      }),
    );
    expect(problems.some((p) => p.includes('早于终点'))).toBe(true);
  });

  it('燃油与偏远是常驻的，不带区间不算问题', () => {
    expect(
      validateChannelForm(
        input({
          surcharges: [
            surcharge({ surchargeKind: 'FUEL' }),
            surcharge({ surchargeKind: 'REMOTE_AREA', chargeMode: 'FIXED', amount: 3 }),
          ],
        }),
      ),
    ).toEqual([]);
  });
});

describe('isEffectiveAt · 旺季区间是左闭右开 [from, to)', () => {
  const peak = surcharge({
    surchargeKind: 'PEAK_SEASON',
    effectiveFrom: '2026-11-01T00:00:00Z',
    effectiveTo: '2026-12-01T00:00:00Z',
  });

  it('起点当天命中（含）', () => {
    expect(isEffectiveAt(peak, new Date('2026-11-01T00:00:00Z'))).toBe(true);
  });

  it('终点当天不命中（不含）', () => {
    expect(isEffectiveAt(peak, new Date('2026-12-01T00:00:00Z'))).toBe(false);
  });

  it('区间内命中', () => {
    expect(isEffectiveAt(peak, new Date('2026-11-15T12:00:00Z'))).toBe(true);
  });

  it('区间外不命中', () => {
    expect(isEffectiveAt(peak, new Date('2026-10-31T23:59:59Z'))).toBe(false);
  });

  it('没有区间的（燃油 / 偏远）恒定生效', () => {
    expect(isEffectiveAt(surcharge(), new Date('2026-01-01T00:00:00Z'))).toBe(true);
  });

  it('时刻带 +08:00 偏移仍按同一瞬间判定', () => {
    // 2026-11-01T08:00:00+08:00 === 2026-11-01T00:00:00Z，是旺季的起点
    expect(isEffectiveAt(peak, new Date('2026-11-01T08:00:00+08:00'))).toBe(true);
  });
});

describe('toChannelForm · 从一行铺出编辑初值', () => {
  function row(overrides: Partial<LogisticsChannelRow> = {}): LogisticsChannelRow {
    return {
      id: 'ch-1',
      carrierProfileId: 'carrier-1',
      carrierCode: 'SF',
      carrierName: '顺丰速运',
      carrierAvailability: 'AVAILABLE',
      channelCode: 'SF-US-SMALL',
      channelName: '顺丰美国小包',
      status: 'ACTIVE',
      billingMode: 'BY_VOLUME',
      billingCurrency: 'USD',
      baseRate: 28.5,
      chargeDivisor: 6000,
      minCharge: 3,
      declarationMinValue: null,
      declarationMaxValue: 200,
      declarationCurrency: 'USD',
      insuranceMode: 'FIXED',
      insuranceRatePct: 0,
      insuranceMinFee: 2,
      insuranceMaxValue: 500,
      scopes: [{ scopeKind: 'COUNTRY', scopeValue: 'US' }],
      surcharges: [surcharge()],
      activeSurcharges: [],
      restrictions: [
        { restrictionKind: 'LIQUID', subject: '', restrictionMode: 'BLOCK', carrierReason: '' },
      ],
      readiness: 'READY',
      readinessMessage: '配置齐备，可参与运费试算与自动匹配',
      peakSeasonActive: false,
      note: '',
      version: 7,
      createdAt: '2026-10-15T02:00:00Z',
      updatedAt: '2026-10-15T03:00:00Z',
      ...overrides,
    };
  }

  it('带回 id 与 version —— 少了 version 保存会被乐观锁拒', () => {
    const form = toChannelForm(row());
    expect(form.channelId).toBe('ch-1');
    expect(form.version).toBe(7);
  });

  it('带的是全量限制而不是「当前生效」附加费 —— 少发一条就是静默放行', () => {
    const form = toChannelForm(row());
    expect(form.restrictions).toHaveLength(1);
    expect(form.restrictions[0]?.restrictionKind).toBe('LIQUID');
    expect(form.surcharges).toHaveLength(1);
  });

  it('载回的行应能通过本地校验（往返不被自相矛盾卡住）', () => {
    expect(validateChannelForm(toChannelForm(row()))).toEqual([]);
  });

  it('null 的申报币种铺成空串而不是 null', () => {
    const form = toChannelForm(
      row({ declarationCurrency: null, declarationMinValue: null, declarationMaxValue: null }),
    );
    expect(form.declarationCurrency).toBe('');
  });

  it('子表是深拷贝，改表单不会就地改到原行', () => {
    const source = row();
    const form = toChannelForm(source);
    form.scopes.push({ scopeKind: 'CATEGORY', scopeValue: 'X' });
    form.restrictions.pop();
    expect(source.scopes).toHaveLength(1);
    expect(source.restrictions).toHaveLength(1);
  });
});

describe('parseList', () => {
  it('逗号 / 分号 / 空格混排都能拆开', () => {
    expect(parseList('a, b; c  d')).toEqual(['A', 'B', 'C', 'D']);
  });

  it('去重且统一大写', () => {
    expect(parseList('de,DE,De')).toEqual(['DE']);
  });

  it('空输入返回空数组而不是含空串的数组', () => {
    expect(parseList('  ,, ')).toEqual([]);
  });
});

describe('optionalNumber', () => {
  it('空串与空白都转 null（未登记 ≠ 登记为 0）', () => {
    expect(optionalNumber('')).toBeNull();
    expect(optionalNumber('   ')).toBeNull();
  });

  it('0 是有效值，不转 null', () => {
    expect(optionalNumber('0')).toBe(0);
  });

  it('非数字转 null 而不是 NaN —— NaN 提交上去会被后端拒', () => {
    expect(optionalNumber('abc')).toBeNull();
  });
});

describe('文案与展示元数据', () => {
  it('计费方式三档都有中文标签', () => {
    expect(billingModeLabel('BY_WEIGHT')).toBe('按重');
    expect(billingModeLabel('BY_PIECE')).toBe('按件');
    expect(billingModeLabel('BY_VOLUME')).toBe('按体积');
  });

  it('未知码原样返回而不是空串（空串会被读成「没有值」）', () => {
    expect(billingModeLabel('WHATEVER')).toBe('WHATEVER');
    expect(restrictionKindLabel('WHATEVER')).toBe('WHATEVER');
  });

  it('限制四类都有中文标签', () => {
    expect(restrictionKindLabel('PROHIBITED_CATEGORY')).toBe('禁限品类');
    expect(restrictionKindLabel('BATTERY')).toBe('电池');
    expect(restrictionKindLabel('LIQUID')).toBe('液体');
    expect(restrictionKindLabel('MAGNET')).toBe('磁力');
  });

  it('缺配置（灰）与已停用（橙）分开 —— 前者是待补的活，后者是有意识的决定', () => {
    expect(readinessMeta('NO_BASE_RATE').tone).toBe('neutral');
    expect(readinessMeta('NO_SCOPE').tone).toBe('neutral');
    expect(readinessMeta('SUSPENDED').tone).toBe('warning');
    expect(readinessMeta('READY').tone).toBe('success');
  });

  it('判定三档的色调互不相同，硬拦截必须是 danger', () => {
    expect(verdictMeta('PASS').tone).toBe('success');
    expect(verdictMeta('REVIEW').tone).toBe('warning');
    expect(verdictMeta('BLOCK').tone).toBe('danger');
  });

  it('附加费渲染：百分比带 % 号，固定额不带', () => {
    expect(surchargeText(surcharge({ chargeMode: 'PERCENT', amount: 18 }))).toBe('燃油附加费 18%');
    expect(
      surchargeText(surcharge({ surchargeKind: 'REMOTE_AREA', chargeMode: 'FIXED', amount: 3 })),
    ).toBe('偏远附加费 3');
  });
});
