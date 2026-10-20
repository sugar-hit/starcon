import { describe, expect, it } from 'vitest';
import {
  BOX_NAMING_PLACEHOLDERS,
  capturedAtLabel,
  claimSummary,
  DRAFT_ONLY_NOTE,
  emptyBoxMessage,
  emptyCandidatesMessage,
  emptyRulesMessage,
  failedClaimOutcomes,
  IMAGE_URL_ONLY_NOTE,
  itemStateLabel,
  itemStateTone,
  MAPPING_REQUIRED_NOTE,
  mappingRows,
  originLabel,
  parseMappingText,
  previewNaming,
  retainedDraftOutcomes,
  sourcePriceLabel,
  unavailableDimensions,
  validateMappingText,
  validateNamingTemplate,
  type ListingCollectBoxCapabilities,
  type ListingCollectBoxClaimOutcome,
  type ListingCollectBoxClaimResult,
  type ListingCollectBoxRemoveResult,
} from './listing-collect-box-model';

/**
 * 4.4.1.3 采集箱 前端纯函数单测。
 *
 * <p>这些函数是页面上"最容易做丢的那四件事"的可执行版本：
 * 认领只产出草稿、品牌与分类映射是硬要求、删除的语义要说清、图片只透传地址。
 */
describe('4.4.1.3 采集箱 · 条目状态与来源', () => {
  it('状态文案与色调', () => {
    expect(itemStateLabel('PENDING')).toBe('待认领');
    expect(itemStateLabel('CLAIMED')).toBe('已认领');
    expect(itemStateLabel('WHATEVER')).toBe('WHATEVER');
    expect(itemStateTone('CLAIMED')).toBe('success');
    expect(itemStateTone('PENDING')).toBe('warning');
    expect(itemStateTone('WHATEVER')).toBe('neutral');
  });

  it('来源文案（链接采集 / 批量采集 / 插件上报）', () => {
    expect(originLabel('LINK')).toBe('链接采集');
    expect(originLabel('BULK')).toBe('批量采集');
    expect(originLabel('PLUGIN')).toBe('插件上报');
    expect(originLabel('OTHER')).toBe('OTHER');
  });
});

describe('4.4.1.3 采集箱 · 售价与时间措辞', () => {
  it('外部页面原文标价不是我方售价（缺值时明说「未抓到」）', () => {
    expect(sourcePriceLabel(128.5, 'CNY')).toBe('128.5 CNY');
    expect(sourcePriceLabel(128.5, null)).toBe('128.5');
    expect(sourcePriceLabel(null, 'CNY')).toBe('未抓到');
    expect(sourcePriceLabel(undefined, undefined)).toBe('未抓到');
  });

  it('抓取时刻的界面文案', () => {
    expect(capturedAtLabel(null)).toBe('—');
    expect(capturedAtLabel('不是时间')).toBe('不是时间');
    expect(capturedAtLabel('2026-10-20T07:38:16+08:00')).toMatch(
      /^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}$/,
    );
  });
});

describe('4.4.1.3 采集箱 · 认领规则的映射文本', () => {
  it('一行一个「源 = 目标」', () => {
    expect(parseMappingText('Acme = BRAND-ACME\nNike = BRAND-NIKE')).toEqual([
      { name: 'Acme', value: 'BRAND-ACME' },
      { name: 'Nike', value: 'BRAND-NIKE' },
    ]);
  });

  it('空行与不完整的行被丢掉（问题由 validateMappingText 指出来）', () => {
    expect(parseMappingText('\n  \nAcme = BRAND-ACME\n坏行\n')).toEqual([
      { name: 'Acme', value: 'BRAND-ACME' },
    ]);
  });

  it('🔴 品牌映射为空 ⇒ 报「请填写品牌映射」', () => {
    expect(validateMappingText('', '品牌映射')).toContain('请填写品牌映射');
    expect(validateMappingText('   \n  ', '品牌映射')).toContain('请填写品牌映射');
  });

  it('🔴 缺「=」、左边空、右边空都要指出来', () => {
    expect(validateMappingText('Acme', '品牌映射')).toContain('没有「=」');
    expect(validateMappingText('= BRAND-ACME', '品牌映射')).toContain('左边是空的');
    expect(validateMappingText('Acme = ', '品牌映射')).toContain('右边是空的');
  });

  it('🔴 合法的映射文本没有报错', () => {
    expect(validateMappingText('Acme = BRAND-ACME', '品牌映射')).toBeNull();
  });
});

describe('4.4.1.3 采集箱 · 命名规则', () => {
  it('🔴 至少要有一个占位符', () => {
    expect(validateNamingTemplate('')).toContain('请填写命名规则模板');
    expect(validateNamingTemplate('固定名字')).toContain('占位符');
    expect(validateNamingTemplate('{brand}-{title}')).toBeNull();
  });

  it('占位符被换成示例值（运营在保存前就能看到名字长什么样）', () => {
    expect(previewNaming('{brand}-{title}', { brand: 'BRAND-ACME', title: '连衣裙' })).toBe(
      'BRAND-ACME-连衣裙',
    );
    expect(previewNaming('{brand}{title}', {})).toBe('BRAND商品标题');
    expect(previewNaming('', {})).toBe('');
  });

  it('占位符清单与后端一致', () => {
    expect(BOX_NAMING_PLACEHOLDERS).toContain('{brand}');
    expect(BOX_NAMING_PLACEHOLDERS).toContain('{title}');
    expect(BOX_NAMING_PLACEHOLDERS).toContain('{model}');
    expect(BOX_NAMING_PLACEHOLDERS).toContain('{category}');
    expect(BOX_NAMING_PLACEHOLDERS).toContain('{source}');
  });
});

describe('4.4.1.3 采集箱 · 批量操作结果', () => {
  const outcome = (ok: boolean, reason = ''): ListingCollectBoxClaimOutcome =>
    ({ itemId: 'i-1', ok, reason }) as ListingCollectBoxClaimOutcome;

  it('认领汇总与失败项', () => {
    const result = {
      rule: null,
      total: 3,
      succeeded: 2,
      failed: 1,
      outcomes: [outcome(true), outcome(true), outcome(false, '🔴 品牌「X」未在规则里')],
    } as unknown as ListingCollectBoxClaimResult;
    expect(claimSummary(result)).toContain('共 3 条');
    expect(claimSummary(result)).toContain('成功 2 条');
    expect(claimSummary(result)).toContain('失败 1 条');
    expect(failedClaimOutcomes(result)).toHaveLength(1);
    expect(failedClaimOutcomes(result)[0]!.reason).toContain('品牌');
  });

  it('空结果不炸', () => {
    expect(claimSummary(null)).toBe('');
    expect(failedClaimOutcomes(null)).toEqual([]);
    expect(retainedDraftOutcomes(null)).toEqual([]);
  });

  it('🔴 删除结果里「草稿被保留」的那几条要被单独拎出来（语义要说清）', () => {
    const result = {
      total: 2,
      succeeded: 2,
      failed: 0,
      outcomes: [
        {
          itemId: 'i-1',
          ok: true,
          reason: '已移出采集箱，并清理了上游采集草稿。',
          draftRemoved: true,
        },
        { itemId: 'i-2', ok: true, reason: '保留草稿以免破坏结果清单事实。', draftRemoved: false },
      ],
    } as unknown as ListingCollectBoxRemoveResult;
    const retained = retainedDraftOutcomes(result);
    expect(retained).toHaveLength(1);
    expect(retained[0]!.itemId).toBe('i-2');
  });
});

describe('4.4.1.3 采集箱 · 能力声明与边界措辞', () => {
  const caps = {
    dimensions: [
      {
        dimension: 'collect_box',
        description: '采集箱',
        available: true,
        source: 'x',
        ownerModule: '',
        impact: '',
      },
      {
        dimension: 'product_draft_delivery',
        description: '商品草稿落库',
        available: false,
        source: '—',
        ownerModule: '3.1.1.1 商品建档（catalog）',
        impact: '本模块不写 catalog 的表',
      },
    ],
    itemStates: ['PENDING', 'CLAIMED'],
    origins: ['LINK', 'BULK', 'PLUGIN'],
    namingPlaceholders: ['{brand}'],
    mappingRequired: true,
    mappingNote: '认领必须做品牌与分类映射',
    draftOnly: true,
    draftNote: '认领产出的是商品草稿载荷',
    builtInCatalog: false,
    buildNote: '落库归 03 建档',
    mediaDownloaded: false,
    mediaNote: '图片只记 URL',
    upstreamNote: '上游 = 4.4.1.1 / 4.4.1.2',
  } as unknown as ListingCollectBoxCapabilities;

  it('未交付的维度要被单独列出来（不把未交付画成已交付）', () => {
    const undelivered = unavailableDimensions(caps);
    expect(undelivered).toHaveLength(1);
    expect(undelivered[0]!.dimension).toBe('product_draft_delivery');
    expect(undelivered[0]!.ownerModule).toContain('3.1.1.1');
  });

  it('🔴 三条边界的界面措辞都不能空', () => {
    expect(MAPPING_REQUIRED_NOTE).toContain('品牌');
    expect(MAPPING_REQUIRED_NOTE).toContain('分类');
    expect(DRAFT_ONLY_NOTE).toContain('草稿');
    expect(IMAGE_URL_ONLY_NOTE).toContain('媒体库');
  });

  it('空态文案都要说清下一步做什么', () => {
    expect(emptyBoxMessage()).toContain('收录');
    expect(emptyCandidatesMessage()).toContain('4.4.1.1');
    expect(emptyRulesMessage()).toContain('品牌映射');
  });
});

describe('4.4.1.3 采集箱 · 键值项渲染', () => {
  it('空键空值被丢掉', () => {
    expect(
      mappingRows([
        { name: '颜色', value: '红' },
        { name: '', value: 'x' },
        { name: '尺码', value: '' },
      ]),
    ).toEqual([{ name: '颜色', value: '红' }]);
  });

  it('空列表不炸', () => {
    expect(mappingRows(null)).toEqual([]);
    expect(mappingRows(undefined)).toEqual([]);
  });
});
