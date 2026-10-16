import { describe, expect, it } from 'vitest';
import {
  emptyComparableText,
  emptyPayloadInput,
  emptyProfileInput,
  fidelityLine,
  internalTypeLabel,
  mappingStatusLabel,
  mappingStatusTone,
  money,
  ratioText,
  ratioTone,
  validateFieldSpec,
  validatePayload,
  validateProfile,
} from './ad-type-diff-model';
import type { AdTypePayloadRow, ComparableView } from './ad-type-diff-model';

/**
 * 14.1.2.2 广告类型差异 前端模型测试。
 *
 * 判据集中在这几处**最容易做假**的地方：
 *   1. 归一类型标签覆盖蓝图点名的六类 + 显式的 UNMAPPED（漏一个就等于把一类广告显示成代码名）；
 *   2. 「不可比」必须渲染成「不可比」而不是 0%（0 与 null 含义相反）；
 *   3. 校验必须挡住「声称可比却没有内部落点」与「声称归一了原文里没有的键」；
 *   4. 保真提示必须**同时**出现原始标记与只留在原文里的字段。
 */

describe('14.1.2.2 内部类型标签覆盖蓝图点名的六类 + 显式未映射', () => {
  it('六类都有中文标签', () => {
    expect(internalTypeLabel('AUTO_DISCOVERY')).toBe('自动广告');
    expect(internalTypeLabel('SPONSORED_BRAND')).toBe('SB 品牌广告');
    expect(internalTypeLabel('SPONSORED_DISPLAY')).toBe('SD 展示型品牌广告');
    expect(internalTypeLabel('BRAND_AD')).toBe('品牌广告');
    expect(internalTypeLabel('DSP_PROGRAMMATIC')).toBe('DSP 程序化');
    expect(internalTypeLabel('PROMOTION_EVENT')).toBe('大促广告');
  });

  it('UNMAPPED 是显式标签而不是兜底，且不能与六类混淆', () => {
    expect(internalTypeLabel('UNMAPPED')).toBe('未映射（无归一口径）');
    expect(internalTypeLabel('UNMAPPED')).not.toBe(internalTypeLabel('AUTO_DISCOVERY'));
  });

  it('未知类型原样回显，不被猜成某一类', () => {
    expect(internalTypeLabel('SOMETHING_NEW')).toBe('SOMETHING_NEW');
  });
});

describe('14.1.2.2 派生比率：不可比显示「不可比」而不是 0%', () => {
  it('null 与 0 是两回事', () => {
    expect(ratioText(null)).toBe('不可比');
    expect(ratioText(0)).toBe('0.00%');
    expect(ratioText(null)).not.toBe(ratioText(0));
  });

  it('ACOS 可按小数展示', () => {
    expect(ratioText(0.25, false)).toBe('0.2500');
    expect(ratioText(null, false)).toBe('不可比');
  });

  it('null 用弱化色，0 不弱化', () => {
    expect(ratioTone(null)).toBe('text-muted-foreground');
    expect(ratioTone(0)).toBe('text-foreground');
  });
});

describe('14.1.2.2 金额与数量只展示不做算术', () => {
  it('null 显示破折号而不是 0.00', () => {
    expect(money(null)).toBe('—');
    expect(money(12.5, 'USD')).toBe('12.50 USD');
  });
});

describe('14.1.2.2 保真状态标签不把「部分覆盖」说成「归一失败」', () => {
  it('三种状态各有说法', () => {
    expect(mappingStatusLabel('NORMALIZED')).toBe('完全归一');
    expect(mappingStatusLabel('PARTIAL_FIELD_COVERAGE')).toContain('只留在原文里');
    expect(mappingStatusLabel('UNMAPPED_TYPE')).toContain('类型就没归一');
  });

  it('部分覆盖用琥珀色，未映射用弱化色，完全归一用绿色', () => {
    expect(mappingStatusTone('NORMALIZED')).toBe('text-emerald-600');
    expect(mappingStatusTone('PARTIAL_FIELD_COVERAGE')).toBe('text-amber-600');
    expect(mappingStatusTone('UNMAPPED_TYPE')).toBe('text-muted-foreground');
  });
});

describe('14.1.2.2 保真提示同时给出原始标记与只留在原文里的字段', () => {
  const base: AdTypePayloadRow = {
    id: 'p1',
    adTypeProfileId: 'pr1',
    platformAdType: 'sponsored_display',
    internalAdType: 'SPONSORED_DISPLAY',
    platformCode: 'AMAZON',
    displayName: 'SD',
    auctionModel: 'CPC',
    objective: 'SALES',
    shopCode: 'SHOP-1',
    externalAdId: 'AD-1',
    externalCampaignId: '',
    adName: '广告',
    adStatus: 'ENABLED',
    currency: 'USD',
    dailyBudget: 100,
    defaultBid: 1.25,
    bidFloor: 0.1,
    startAt: null,
    endAt: null,
    impressions: 2000,
    clicks: 100,
    conversions: 5,
    spend: 125,
    attributedRevenue: 500,
    ctr: 0.05,
    cpc: 1.25,
    cvr: 0.05,
    acos: 0.25,
    normalizedFieldKeys: ['bid'],
    rawOnlyFieldKeys: ['audienceSegments', 'placement'],
    mappingStatus: 'PARTIAL_FIELD_COVERAGE',
    rawFetchedAt: '2026-10-01T01:00:00Z',
    ingestedAt: '2026-10-01T01:00:00Z',
    sourceRef: '',
    note: '',
    version: 0,
    createdAt: '2026-10-01T01:00:00Z',
    updatedAt: '2026-10-01T01:00:00Z',
  };

  it('原始标记出现在提示里（归一不能把它吃掉）', () => {
    expect(fidelityLine(base)).toContain('sponsored_display');
    expect(fidelityLine(base)).toContain('归一');
  });

  it('只留在原文里的字段逐个列出，而不是概括成「部分覆盖」', () => {
    const line = fidelityLine(base);
    expect(line).toContain('audienceSegments');
    expect(line).toContain('placement');
  });

  it('全部有归一落点时说清楚，而不是留空让人猜', () => {
    const full = { ...base, rawOnlyFieldKeys: [], mappingStatus: 'NORMALIZED' as const };
    expect(fidelityLine(full)).toContain('原始字段全部有归一落点');
  });
});

describe('14.1.2.2 档案校验', () => {
  const valid = {
    ...emptyProfileInput,
    platformId: '11111111-1111-1111-1111-111111111111',
    platformAdType: 'sponsored_display',
    internalAdType: 'SPONSORED_DISPLAY',
    displayName: 'SD',
  };

  it('完整输入通过', () => {
    expect(validateProfile(valid, 'PLATFORM_DEFAULT').ok).toBe(true);
  });

  it('原始类型标记必填 —— 它是「平台原文是什么」的唯一依据', () => {
    const result = validateProfile({ ...valid, platformAdType: '' }, 'PLATFORM_DEFAULT');
    expect(result.ok).toBe(false);
    expect(result.errors.join()).toContain('原始平台类型标记');
  });

  it('未映射时归一类型只能是 UNMAPPED（不落兜底）', () => {
    const result = validateProfile({ ...valid, internalAdType: 'AUTO_DISCOVERY' }, 'UNMAPPED');
    expect(result.ok).toBe(false);
    expect(result.errors.join()).toContain('不能落到兜底类型');
  });

  it('声明来源与归一类型不自洽时报错', () => {
    const result = validateProfile({ ...valid, internalAdType: 'UNMAPPED' }, 'PLATFORM_DEFAULT');
    expect(result.ok).toBe(false);
    expect(result.errors.join()).toContain('自洽');
  });

  it('平台必须是 uuid', () => {
    expect(validateProfile({ ...valid, platformId: 'nope' }, 'PLATFORM_DEFAULT').ok).toBe(false);
  });
});

describe('14.1.2.2 特有字段定义校验', () => {
  it('声称可比却没有内部落点 ⇒ 拦下并给中文理由', () => {
    const result = validateFieldSpec({
      id: '',
      fieldKey: 'audienceSegments',
      fieldLabel: '受众包',
      dataType: 'LIST',
      unit: '',
      enumValues: '',
      comparable: true,
      internalRepresentation: '',
      requiredOnInput: false,
      position: '10',
      note: '',
    });
    expect(result.ok).toBe(false);
    expect(result.errors.join()).toContain('内部落点');
  });

  it('不可比 + 没有内部落点**不拦** —— 那正是「只留在原文里」的字段', () => {
    const result = validateFieldSpec({
      id: '',
      fieldKey: 'headlineText',
      fieldLabel: '广告标题',
      dataType: 'STRING',
      unit: '',
      enumValues: '',
      comparable: false,
      internalRepresentation: '',
      requiredOnInput: false,
      position: '10',
      note: '',
    });
    expect(result.ok).toBe(true);
  });
});

describe('14.1.2.2 归一后广告数据校验', () => {
  const valid = {
    ...emptyPayloadInput,
    adTypeProfileId: '11111111-1111-1111-1111-111111111111',
    shopCode: 'SHOP-1',
    externalAdId: 'AD-1',
    rawPayload: '{"bid":1.5,"placement":"top"}',
    rawFetchedAt: '2026-10-01T09:00:00+08:00',
    normalizedFieldKeys: 'bid',
  };

  it('完整输入通过', () => {
    expect(validatePayload(valid).ok).toBe(true);
  });

  it('原始报文必填 —— 它就是「不能丢信息」的兜底', () => {
    const result = validatePayload({ ...valid, rawPayload: '' });
    expect(result.ok).toBe(false);
    expect(result.errors.join()).toContain('不能丢信息');
  });

  it('原始报文必须是 JSON 对象，数组与标量都拒', () => {
    expect(validatePayload({ ...valid, rawPayload: '[1,2]' }).errors.join()).toContain('对象');
    expect(validatePayload({ ...valid, rawPayload: '123' }).errors.join()).toContain('对象');
  });

  it('🔴 声称归一了原文里没有的键 ⇒ 覆盖率虚报，拦下', () => {
    const result = validatePayload({ ...valid, normalizedFieldKeys: 'bid,ghostField' });
    expect(result.ok).toBe(false);
    expect(result.errors.join()).toContain('不在原始报文里');
  });

  it('金额不能是负数', () => {
    const result = validatePayload({ ...valid, spend: '-1' });
    expect(result.ok).toBe(false);
    expect(result.errors.join()).toContain('花费');
  });

  it('缺少平台侧广告 id 时报错 —— 没有它认不出「这一条」', () => {
    const result = validatePayload({ ...valid, externalAdId: '' });
    expect(result.ok).toBe(false);
    expect(result.errors.join()).toContain('平台侧广告 id');
  });
});

describe('14.1.2.2 可比表为空时的文案区分两种「空」', () => {
  const unmappedOnly: ComparableView = {
    types: [],
    unmappedTypes: [
      {
        platformId: 'p',
        platformCode: 'AMAZON',
        platformAdType: 'mystery',
        displayName: '未知',
        adCount: 3,
        reason: '未登记',
      },
    ],
    note: '',
  };

  it('没有任何输入时说「还没有可归一的输入」', () => {
    const message = emptyComparableText({ types: [], unmappedTypes: [], note: '' });
    expect(message).toContain('还没有可归一');
  });

  it('只有未映射类型时明确说它们存在、只是不可横比', () => {
    const message = emptyComparableText(unmappedOnly);
    expect(message).toContain('存在');
    expect(message).toContain('没有给归一口径');
  });

  it('有可比类型时不显示空态文案', () => {
    expect(
      emptyComparableText({
        types: [
          {
            internalAdType: 'SPONSORED_DISPLAY',
            adCount: 1,
            platformCount: 1,
            markers: [],
            auctionModels: [],
            objectives: [],
            impressions: 0,
            clicks: 0,
            conversions: 0,
            spend: 0,
            attributedRevenue: 0,
            comparabilityNote: '',
          },
        ],
        unmappedTypes: [],
        note: '',
      }),
    ).toBe('');
  });
});
