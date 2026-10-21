import { describe, expect, it } from 'vitest';
import {
  adStatusLabel,
  changeHistoryText,
  copyResultText,
  creativeTypeLabel,
  emptyAdCopyStructureInput,
  isUuid,
  money,
  operationLabel,
  operationResultLabel,
  productModeLabel,
  quantity,
  ratioText,
  ratioTone,
  timestampText,
  validateBidUpdate,
  validateCopyStructure,
} from './ad-campaign-detail-model';
import type { AdCampaignCopyResult } from './ad-campaign-detail-model';

/**
 * 14.1.1.2 活动详情 前端模型。
 *
 * 🔴 这些断言盯的是**页面最容易说错的三件事**：
 *
 * 1. **「平台侧修改历史拿不到」与「没有修改过」是两件事**。上游没有平台广告 API 同步器 ⇒
 *    没有同步源 ⇒ 平台侧改历史**无法获得**。文案必须说「拿不到」并说清当前展示的是
 *    **本地**操作审计；渲染成「暂无修改记录」就是在说「平台侧没改过」，那是假的。
 * 2. **「复制结构」的副本是真的，平台侧还不是**。结果文案必须同时说出
 *    「结构真落库了」「平台侧还没有」「关键词没有复制」三件事。
 * 3. **比率的 null 与 0 含义相反**。null = 不可比（分母为 0），0 = 可比但真的是零。
 */

function copyResult(overrides: Partial<AdCampaignCopyResult> = {}): AdCampaignCopyResult {
  return {
    sourceCampaignId: '11111111-1111-1111-1111-111111111111',
    targetCampaignId: '22222222-2222-2222-2222-222222222222',
    targetCampaignName: '活动一（结构副本）',
    targetStatus: 'PAUSED',
    targetShopCode: 'SHOP-2',
    productMode: 'KEEP_PRODUCTS',
    targetListingId: null,
    groupCount: 2,
    productCount: 3,
    creativeCount: 4,
    keywordCount: 0,
    pendingPlatformWriteback: true,
    note: '服务端说明',
    ...overrides,
  };
}

describe('14.1.1.2 状态与类型标签', () => {
  it('活动状态翻译成中文，未知状态原样返回', () => {
    expect(adStatusLabel('ENABLED')).toBe('投放中');
    expect(adStatusLabel('PAUSED')).toBe('已暂停');
    expect(adStatusLabel('ENDED')).toBe('已结束');
    expect(adStatusLabel('ARCHIVED')).toBe('已归档');
    expect(adStatusLabel('PENDING_REVIEW')).toBe('审核中');
    expect(adStatusLabel('WEIRD')).toBe('状态未知');
  });

  it('创意类型覆盖蓝图点名的视频 / 图片，另含图集与文本', () => {
    expect(creativeTypeLabel('IMAGE')).toBe('图片');
    expect(creativeTypeLabel('VIDEO')).toBe('视频');
    expect(creativeTypeLabel('CAROUSEL')).toBe('图集');
    expect(creativeTypeLabel('TEXT')).toBe('文本');
  });

  it('投放商品处理方式的中文说法分得开「沿用」与「改投」', () => {
    expect(productModeLabel('KEEP_PRODUCTS')).toBe('沿用源活动的投放商品');
    expect(productModeLabel('TARGET_LISTING')).toBe('改投指定的 ASIN');
  });

  it('操作与结果标签', () => {
    expect(operationLabel('PAUSE')).toBe('暂停');
    expect(operationLabel('SET_BUDGET')).toBe('设为预算');
    expect(operationResultLabel('APPLIED')).toBe('已生效');
    expect(operationResultLabel('REJECTED')).toBe('被拒');
    expect(operationResultLabel('SKIPPED')).toBe('跳过');
  });
});

describe('14.1.1.2 🔴 平台侧修改历史：拿不到 ≠ 没有修改过', () => {
  it('不可用时文案必须说清「拿不到」与「当前展示的是本地审计」', () => {
    const text = changeHistoryText(false, '平台广告 API 同步未交付。', '当前展示的是本地操作审计。');
    expect(text).toContain('平台广告 API 同步未交付');
    expect(text).toContain('本地操作审计');
    // 🔴 不许出现「暂无修改」这种把「拿不到」说成「没有过」的措辞
    expect(text).not.toContain('暂无修改');
  });

  it('可用时直接给服务端说明', () => {
    expect(changeHistoryText(true, '有数据了。', '本地审计。')).toContain('有数据了');
  });
});

describe('14.1.1.2 🔴 复制结构：副本是真的，平台侧还不是', () => {
  it('文案同时说出结构落库、平台侧还没有、关键词没有复制三件事', () => {
    const text = copyResultText(copyResult());
    expect(text).toContain('结构副本已落库');
    expect(text).toContain('2 个广告组');
    expect(text).toContain('3 个投放商品');
    expect(text).toContain('4 个创意');
    expect(text).toContain('平台侧还没有这份结构');
    expect(text).toContain('待回写平台');
    expect(text).toContain('暂停');
    expect(text).toContain('关键词没有复制');
  });

  it('待回写标记为 false 时不再说「平台侧还没有」', () => {
    const text = copyResultText(copyResult({ pendingPlatformWriteback: false }));
    expect(text).not.toContain('平台侧还没有这份结构');
    expect(text).toContain('结构副本已落库');
  });
});

describe('14.1.1.2 🔴 比率的 null 与 0 含义相反', () => {
  it('null 显示「不可比」而不是 0.00%', () => {
    expect(ratioText(null)).toBe('不可比');
    expect(ratioText(undefined)).toBe('不可比');
    expect(ratioTone(null)).toBe('text-muted-foreground');
  });

  it('0 显示 0.00%（可比但真的是零）', () => {
    expect(ratioText(0)).toBe('0.00%');
    expect(ratioTone(0)).toBe('text-foreground');
    expect(ratioText(0, false)).toBe('0.0000');
  });

  it('正常比率按百分比或四位小数展示', () => {
    expect(ratioText(0.2)).toBe('20.00%');
    expect(ratioText(5, false)).toBe('5.0000');
  });
});

describe('14.1.1.2 金额与数量只原样展示', () => {
  it('null 显示「—」而不是 0', () => {
    expect(money(null)).toBe('—');
    expect(quantity(null)).toBe('—');
  });

  it('带币种时拼在后面', () => {
    expect(money(100, 'USD')).toBe('100.00 USD');
    expect(money(0, 'USD')).toBe('0.00 USD');
  });
});

describe('14.1.1.2 时间与形状', () => {
  it('空时间显示「—」，非法时间原样返回', () => {
    expect(timestampText(null)).toBe('—');
    expect(timestampText('')).toBe('—');
    expect(timestampText('不是时间')).toBe('不是时间');
  });

  it('UUID 形状校验', () => {
    expect(isUuid('11111111-1111-1111-1111-111111111111')).toBe(true);
    expect(isUuid('not-a-uuid')).toBe(false);
    expect(isUuid('11111111-1111-1111-1111-1111111111111')).toBe(false);
  });
});

describe('14.1.1.2 复制结构的表单校验', () => {
  it('目标店铺必填', () => {
    const result = validateCopyStructure({ ...emptyAdCopyStructureInput });
    expect(result.ok).toBe(false);
    expect(result.errors.join()).toContain('目标店铺编码必填');
  });

  it('改投指定 ASIN 时必须给出合法 listing id', () => {
    const missing = validateCopyStructure({
      ...emptyAdCopyStructureInput,
      targetShopCode: 'SHOP-2',
      productMode: 'TARGET_LISTING',
    });
    expect(missing.ok).toBe(false);
    expect(missing.errors.join()).toContain('必须给出目标 listing id');

    const bad = validateCopyStructure({
      ...emptyAdCopyStructureInput,
      targetShopCode: 'SHOP-2',
      productMode: 'TARGET_LISTING',
      targetListingId: 'not-a-uuid',
    });
    expect(bad.ok).toBe(false);
    expect(bad.errors.join()).toContain('合法 UUID');

    const good = validateCopyStructure({
      ...emptyAdCopyStructureInput,
      targetShopCode: 'SHOP-2',
      productMode: 'TARGET_LISTING',
      targetListingId: '11111111-1111-1111-1111-111111111111',
    });
    expect(good.ok).toBe(true);
  });

  it('沿用源投放商品时不能同时指定目标 ASIN（两者语义相反）', () => {
    const result = validateCopyStructure({
      ...emptyAdCopyStructureInput,
      targetShopCode: 'SHOP-2',
      productMode: 'KEEP_PRODUCTS',
      targetListingId: '11111111-1111-1111-1111-111111111111',
    });
    expect(result.ok).toBe(false);
    expect(result.errors.join()).toContain('语义相反');
  });

  it('非法处理方式被拒', () => {
    const result = validateCopyStructure({
      ...emptyAdCopyStructureInput,
      targetShopCode: 'SHOP-2',
      productMode: 'WHATEVER',
    });
    expect(result.ok).toBe(false);
    expect(result.errors.join()).toContain('投放商品处理方式不合法');
  });
});

describe('14.1.1.2 改竞价的表单校验', () => {
  it('广告组 id 与竞价都要合法', () => {
    const badGroup = validateBidUpdate({ groupId: 'x', bid: '1.5', reason: '' });
    expect(badGroup.ok).toBe(false);
    expect(badGroup.errors.join()).toContain('合法 UUID');

    const emptyBid = validateBidUpdate({
      groupId: '11111111-1111-1111-1111-111111111111',
      bid: '',
      reason: '',
    });
    expect(emptyBid.ok).toBe(false);
    expect(emptyBid.errors.join()).toContain('目标竞价必填');

    const negative = validateBidUpdate({
      groupId: '11111111-1111-1111-1111-111111111111',
      bid: '-1',
      reason: '',
    });
    expect(negative.ok).toBe(false);
    expect(negative.errors.join()).toContain('不能为负');

    const good = validateBidUpdate({
      groupId: '11111111-1111-1111-1111-111111111111',
      bid: '1.5',
      reason: '提价',
    });
    expect(good.ok).toBe(true);
  });
});
