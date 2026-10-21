import { describe, expect, it } from 'vitest';
import {
  blacklistReasonLabel,
  cooperationStatusLabel,
  effectMoney,
  effectRatio,
  emptyInfluencerInput,
  emptyInfluencerRatingRuleInput,
  emptyInfluencerRatingTierInput,
  emptyInfluencerTagInput,
  emptyInfluencerTagGroupInput,
  followerText,
  followerTierLabel,
  influencerStatusLabel,
  localDateTimeInput,
  platformSyncStatusLabel,
  ratingText,
  syncSourceLabel,
  tagCodeList,
  timestampText,
  validateInfluencer,
  validateInfluencerRatingRule,
  validateInfluencerRatingTier,
  validateInfluencerTag,
  validateInfluencerTagGroup,
} from './influencer-model';

/**
 * 14.5.1.1 达人库 前端模型测试。
 *
 * 🔴 这里钉住的是**最容易在界面上做丢**的几件事：
 *   1. 🔴 「没录」显示「未录入」而不是 0.00 —— 0 是「录了而且真的是零」；
 *   2. 🔴 评分缺失显示「暂无评分」而不是 0.0000 分；
 *   3. 🔴 数据来源与平台同步状态必须把「本地已录、平台侧还没有」说清楚；
 *   4. 🔴 评分口径的三个权重之和必须为 1（与服务端、DB CHECK 同一口径）；
 *   5. 档位区间必须自洽（上限严格大于下限）；
 *   6. 粉丝层级 / 拉黑原因 / 合作状态都有中文名。
 */
describe('14.5.1.1 达人库 · 展示辅助', () => {
  it('🔴 没录的效果数据显示「未录入」而不是 0.00', () => {
    expect(effectMoney(null)).toBe('未录入');
    expect(effectMoney(undefined)).toBe('未录入');
    expect(effectMoney(0)).toBe('0.00');
    expect(effectMoney(12345.6789)).toBe('12345.68');
    expect(effectMoney(12345.6789, 'USD')).toBe('12345.68 USD');
  });

  it('🔴 没录的比率显示「未录入」而不是 0.00%', () => {
    expect(effectRatio(null)).toBe('未录入');
    expect(effectRatio(undefined)).toBe('未录入');
    expect(effectRatio(0)).toBe('0.00%');
    expect(effectRatio(0.08)).toBe('8.00%');
  });

  it('🔴 评分缺失显示「暂无评分」而不是 0.0000 分', () => {
    expect(ratingText(null)).toBe('暂无评分');
    expect(ratingText(undefined)).toBe('暂无评分');
    expect(ratingText(0)).toBe('0.0000');
    expect(ratingText(0.67)).toBe('0.6700');
  });

  it('🔴 数据来源与平台同步状态必须说清「本地已录、平台侧还没有」', () => {
    expect(syncSourceLabel('MANUAL')).toBe('手动录入');
    expect(syncSourceLabel('PLATFORM_API')).toBe('平台接口同步');
    expect(platformSyncStatusLabel('LOCAL_ONLY')).toContain('平台侧还没有');
    expect(platformSyncStatusLabel('SYNCED')).toContain('平台侧已一致');
    expect(platformSyncStatusLabel('FAILED')).toContain('同步失败');
  });

  it('粉丝层级、拉黑原因、合作状态都有中文名', () => {
    expect(followerTierLabel('NANO')).toContain('素人');
    expect(followerTierLabel('TOP')).toContain('顶级');
    expect(blacklistReasonLabel('FRAUD')).toBe('欺诈');
    expect(blacklistReasonLabel('POOR_QUALITY')).toBe('效果差');
    expect(cooperationStatusLabel('COMPLETED')).toBe('已完成');
    expect(influencerStatusLabel('INACTIVE')).toBe('已停用');
    expect(followerText(120000)).toBe('120,000');
  });

  it('标签编码串拆成数组（null → 空数组）', () => {
    expect(tagCodeList(null)).toEqual([]);
    expect(tagCodeList('')).toEqual([]);
    expect(tagCodeList('A,B')).toEqual(['A', 'B']);
    expect(tagCodeList('A, B ')).toEqual(['A', 'B']);
  });

  it('时刻展示与本地输入框格式', () => {
    expect(timestampText(null)).toBe('—');
    expect(timestampText('not-a-date')).toBe('not-a-date');
    expect(localDateTimeInput(null)).toBe('');
    expect(localDateTimeInput('not-a-date')).toBe('');
  });
});

describe('14.5.1.1 达人库 · 表单校验', () => {
  it('空表单能通过达人档案校验', () => {
    const state = {
      ...emptyInfluencerInput(),
      platformId: '11111111-1111-1111-1111-111111111111',
      shopCode: 'SHOP-1',
      displayName: '露西',
      category: '美妆',
    };
    expect(validateInfluencer(state).ok).toBe(true);
  });

  it('必填项缺失时报错', () => {
    const result = validateInfluencer(emptyInfluencerInput());
    expect(result.ok).toBe(false);
    expect(result.errors.join('|')).toContain('平台必填');
    expect(result.errors.join('|')).toContain('店铺必填');
    expect(result.errors.join('|')).toContain('达人名称必填');
    expect(result.errors.join('|')).toContain('类目必填');
  });

  it('互动率 / 退款率必须在 0 ~ 1 之间', () => {
    const base = {
      ...emptyInfluencerInput(),
      platformId: 'p',
      shopCode: 's',
      displayName: 'n',
      category: 'c',
    };
    expect(validateInfluencer({ ...base, engagementRate: '1.5' }).ok).toBe(false);
    expect(validateInfluencer({ ...base, refundRate: '-0.1' }).ok).toBe(false);
    expect(validateInfluencer({ ...base, engagementRate: '0.08' }).ok).toBe(true);
    // 留空 = 还没录，允许
    expect(validateInfluencer({ ...base, engagementRate: '', refundRate: '' }).ok).toBe(true);
  });

  it('GMV 不能是负数；粉丝数必须是不小于 0 的整数', () => {
    const base = {
      ...emptyInfluencerInput(),
      platformId: 'p',
      shopCode: 's',
      displayName: 'n',
      category: 'c',
    };
    expect(validateInfluencer({ ...base, gmv: '-1' }).ok).toBe(false);
    expect(validateInfluencer({ ...base, followerCount: '1.5' }).ok).toBe(false);
    expect(validateInfluencer({ ...base, followerCount: '-1' }).ok).toBe(false);
    expect(validateInfluencer({ ...base, followerCount: '120000' }).ok).toBe(true);
  });

  it('标签与标签分组的必填项', () => {
    expect(validateInfluencerTag(emptyInfluencerTagInput()).ok).toBe(false);
    expect(validateInfluencerTagGroup(emptyInfluencerTagGroupInput()).ok).toBe(false);
    expect(
      validateInfluencerTag({
        ...emptyInfluencerTagInput(),
        groupId: 'g',
        tagCode: 'T1',
        tagName: '标签一',
      }).ok,
    ).toBe(true);
    expect(
      validateInfluencerTagGroup({
        ...emptyInfluencerTagGroupInput(),
        groupCode: 'G1',
        groupName: '分组一',
      }).ok,
    ).toBe(true);
  });

  it('🔴 评分口径的三个权重之和必须为 1', () => {
    const base = emptyInfluencerRatingRuleInput();
    expect(validateInfluencerRatingRule(base).ok).toBe(true);
    const bad = validateInfluencerRatingRule({ ...base, weightRefund: '0.3' });
    expect(bad.ok).toBe(false);
    expect(bad.errors.join('|')).toContain('权重之和必须等于 1');
  });

  it('🔴 归一化上限必须大于 0', () => {
    const base = emptyInfluencerRatingRuleInput();
    expect(validateInfluencerRatingRule({ ...base, gmvCap: '0' }).ok).toBe(false);
    expect(validateInfluencerRatingRule({ ...base, gmvCap: '' }).ok).toBe(false);
  });

  it('评级档位区间必须自洽（上限严格大于下限）', () => {
    const base = { ...emptyInfluencerRatingTierInput(), tierCode: 'S', tierName: '顶级达人' };
    expect(validateInfluencerRatingTier({ ...base, minScore: '0.9', maxScore: '1' }).ok).toBe(true);
    expect(validateInfluencerRatingTier({ ...base, minScore: '0.8', maxScore: '0.8' }).ok).toBe(false);
    expect(validateInfluencerRatingTier({ ...base, minScore: '0.9', maxScore: '0.8' }).ok).toBe(false);
    expect(validateInfluencerRatingTier({ ...base, minScore: '', maxScore: '1' }).ok).toBe(false);
    expect(validateInfluencerRatingTier({ ...base, minScore: '0', maxScore: '1.5' }).ok).toBe(false);
  });
});
