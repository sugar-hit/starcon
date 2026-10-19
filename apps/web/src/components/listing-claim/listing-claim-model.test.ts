import { describe, expect, it } from 'vitest';
import {
  buildClaimRequest,
  claimStateLabel,
  claimStateTone,
  decisionLabel,
  draftBadge,
  draftBadgeTone,
  FIELD_OPTIONS,
  pendingPlatformItemIdNote,
  siteBoundFields,
  sourceOriginLabel,
  sourceUnchanged,
  targetShopOptions,
  targetSiteOptions,
  type ClaimFieldDrafts,
  type ListingClaimRow,
  type ShopView,
} from './listing-claim-model';

/**
 * 4.4.2.1 商品认领 前端纯函数单测。
 *
 * <p>这些函数是页面上"最容易做丢的那几件事"的可执行版本：
 * 草稿态可见、源不受影响、无价格入口、站点绑定字段不跨站点沿用。
 */

const SITE_US = 'site-us';
const SITE_UK = 'site-uk';

function shop(id: string, code: string, status = 'ACTIVE'): ShopView {
  return {
    id,
    platformId: 'p-1',
    platformCode: 'AMAZON',
    platformName: 'Amazon',
    connectionId: 'c-1',
    connectionAccount: 'acct',
    legalEntityId: 'le-1',
    code,
    name: `店铺 ${code}`,
    platformShopId: 'shop-1',
    operatingMode: 'SELF_FULFILLED',
    ownerId: 'o-1',
    departmentId: 'd-1',
    status,
    version: 0,
    marketplaceCount: 2,
    marketplaces: [
      {
        marketplaceId: SITE_US,
        marketplaceCode: 'US',
        marketplaceName: '美国站',
        status: 'ACTIVE',
        enabledAt: '2026-01-01T00:00:00Z',
      },
      {
        marketplaceId: SITE_UK,
        marketplaceCode: 'UK',
        marketplaceName: '英国站',
        status: 'SUSPENDED',
        enabledAt: '2026-01-01T00:00:00Z',
      },
    ],
    lastSyncAt: '2026-01-01T00:00:00Z',
  };
}

function claim(over: Partial<ListingClaimRow> = {}): ListingClaimRow {
  return {
    claimId: 'c-1',
    sourceListingId: 'l-1',
    sourceExternalItemId: 'ASIN-1',
    sourceTitle: '源商品',
    sourceShopCode: 'A',
    sourceShopName: '店铺 A',
    sourceMarketplaceCode: 'US',
    sourceStatus: 'ON_SALE',
    sourceCurrentStatus: 'ON_SALE',
    sourceReviewStatus: 'APPROVED',
    sourceCurrentReviewStatus: 'APPROVED',
    sourceVersion: 3,
    sourceCurrentVersion: 3,
    targetListingId: 'l-2',
    targetExternalItemId: 'CLAIM-PENDING:c-1',
    targetStatus: 'DRAFT',
    targetReviewStatus: 'NOT_SUBMITTED',
    targetPlatformStockState: 'UNKNOWN',
    targetTitle: '源商品',
    targetShopCode: 'B',
    targetShopName: '店铺 B',
    targetMarketplaceCode: 'US',
    state: 'DRAFT',
    variantCount: 2,
    mappedVariantCount: 1,
    publishBatchId: 'b-1',
    publishTaskId: 't-1',
    actorId: 'u-1',
    note: '',
    createdAt: '2026-10-19T00:00:00Z',
    updatedAt: '2026-10-19T00:00:00Z',
    fields: [],
    ...over,
  };
}

describe('buildClaimRequest', () => {
  const base = {
    sourceListingId: 'l-1',
    targetShopId: 's-2',
    targetMarketplaceId: SITE_US,
    language: '',
    note: '',
  };

  it('标题必须有决策 —— 目标草稿不能没有标题', () => {
    const fields: ClaimFieldDrafts = { BULLETS: { mode: 'KEEP', value: '' } };
    expect(buildClaimRequest({ ...base, fields })).toBeNull();
  });

  it('「重写」必须给出非空值', () => {
    const fields: ClaimFieldDrafts = {
      TITLE: { mode: 'REWRITE', value: '   ' },
    };
    expect(buildClaimRequest({ ...base, fields })).toBeNull();
  });

  it('没给决策的字段不下发（服务端按「未给决策 = 不复用」处理）', () => {
    const fields: ClaimFieldDrafts = { TITLE: { mode: 'KEEP', value: '' } };
    const body = buildClaimRequest({ ...base, fields });
    expect(body).not.toBeNull();
    expect(body!.fieldDecisions.map((d) => d.field)).toEqual(['TITLE']);
  });

  it('源商品 / 目标店铺 / 目标站点缺一不可', () => {
    const fields: ClaimFieldDrafts = { TITLE: { mode: 'KEEP', value: '' } };
    expect(buildClaimRequest({ ...base, sourceListingId: '', fields })).toBeNull();
    expect(buildClaimRequest({ ...base, targetShopId: '', fields })).toBeNull();
    expect(buildClaimRequest({ ...base, targetMarketplaceId: '', fields })).toBeNull();
  });

  it('完整决策原样下发', () => {
    const fields: ClaimFieldDrafts = {
      TITLE: { mode: 'REWRITE', value: '新标题' },
      IMAGES: { mode: 'SKIP', value: '' },
    };
    const body = buildClaimRequest({ ...base, fields });
    expect(body).not.toBeNull();
    expect(body!.sourceListingId).toBe('l-1');
    expect(body!.targetShopId).toBe('s-2');
    expect(body!.targetMarketplaceId).toBe(SITE_US);
    expect(body!.fieldDecisions).toEqual([
      { field: 'TITLE', mode: 'REWRITE', value: '新标题' },
      { field: 'IMAGES', mode: 'SKIP', value: '' },
    ]);
  });
});

describe('目标店铺与站点候选', () => {
  it('排除源商品所在的店铺 —— 认领到同一个店铺不构成跨店搬家', () => {
    const shops = [shop('s-1', 'A'), shop('s-2', 'B'), shop('s-3', 'C', 'SUSPENDED')];
    const options = targetShopOptions(shops, 's-1');
    expect(options.map((s) => s.code)).toEqual(['B']);
  });

  it('只给目标店铺已启用的站点', () => {
    const options = targetSiteOptions(shop('s-2', 'B'));
    expect(options.map((m) => m.marketplaceCode)).toEqual(['US']);
  });

  it('没有目标店铺时没有站点可选', () => {
    expect(targetSiteOptions(null)).toEqual([]);
  });
});

describe('站点绑定字段', () => {
  it('同站点时没有字段被禁用', () => {
    expect(siteBoundFields(SITE_US, SITE_US)).toEqual([]);
  });

  it('跨站点时平台类目 ID / 类目属性 / 合规声明不能沿用源值', () => {
    const blocked = siteBoundFields(SITE_US, SITE_UK);
    expect(blocked).toContain('PLATFORM_CATEGORY_ID');
    expect(blocked).toContain('CATEGORY_ATTRIBUTES');
    expect(blocked).toContain('COMPLIANCE_STATEMENTS');
    expect(blocked).not.toContain('TITLE');
  });

  it('源商品没有归站点时按"跨站点"处理（保守）', () => {
    expect(siteBoundFields(null, SITE_UK)).toContain('PLATFORM_CATEGORY_ID');
  });
});

describe('源 listing 未受影响', () => {
  it('两组快照一致 ⇒ 源没被动过', () => {
    expect(sourceUnchanged(claim())).toBe(true);
  });

  it('版本号前进 ⇒ 源在认领之后被改过，页面必须说出来', () => {
    expect(sourceUnchanged(claim({ sourceCurrentVersion: 4 }))).toBe(false);
  });

  it('状态变了也一样', () => {
    expect(sourceUnchanged(claim({ sourceCurrentStatus: 'DELISTED_BY_PLATFORM' }))).toBe(false);
  });
});

describe('草稿态一眼可见', () => {
  it('目标恒为草稿时徽章写「尚未过平台审核」', () => {
    expect(draftBadge(claim())).toBe('草稿 · 尚未过平台审核');
    expect(draftBadgeTone(claim())).toBe('warning');
  });

  it('目标离开草稿态后徽章显示真实状态，但认领状态仍不写"平台已批准"', () => {
    const c = claim({ targetStatus: 'UNDER_REVIEW', state: 'PUBLISHED' });
    expect(draftBadge(c)).toBe('目标 UNDER_REVIEW');
    expect(claimStateLabel(c.state)).toBe('已离开草稿态（不代表平台审核通过）');
    expect(claimStateLabel(c.state)).not.toBe('已发布');
    expect(claimStateLabel(c.state)).not.toBe('已批准');
  });

  it('目标平台商品 ID 未产生时给出保留位说明', () => {
    expect(pendingPlatformItemIdNote(claim())).toContain('保留位');
  });

  it('已有真实平台商品 ID 时不再提示', () => {
    expect(pendingPlatformItemIdNote(claim({ targetExternalItemId: 'B0REALID' }))).toBe('');
  });
});

describe('文案与字段表', () => {
  it('售价与库存不在可选字段里（归 05 / 07）', () => {
    const values = FIELD_OPTIONS.map((o) => o.value);
    expect(values).not.toContain('PRICE');
    expect(values).not.toContain('STOCK');
    expect(values).not.toContain('RATING');
    expect(values).not.toContain('CURRENCY');
  });

  it('字段集与后端一致（10 个）', () => {
    expect(FIELD_OPTIONS.map((o) => o.value)).toEqual([
      'TITLE',
      'BULLETS',
      'IMAGES',
      'DESCRIPTION',
      'A_PLUS',
      'VIDEO_URL',
      'CATEGORY_PATH',
      'PLATFORM_CATEGORY_ID',
      'CATEGORY_ATTRIBUTES',
      'COMPLIANCE_STATEMENTS',
    ]);
  });

  it('三种决策与源值来源都有文案', () => {
    expect(decisionLabel('KEEP')).toBe('保留源值');
    expect(decisionLabel('REWRITE')).toBe('重写');
    expect(decisionLabel('SKIP')).toBe('跳过（目标留空）');
    expect(sourceOriginLabel('NOT_PRESENT')).toBe('源侧没有这个字段');
  });

  it('待发布只入队，不写成"已发布"', () => {
    expect(claimStateLabel('DRAFT')).toBe('待发布（草稿）');
    expect(claimStateTone('DRAFT')).toBe('warning');
  });
});
