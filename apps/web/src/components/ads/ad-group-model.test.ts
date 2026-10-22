import { describe, expect, it } from 'vitest';
import {
  emptyAdGroupCreateInput,
  emptyAdGroupSettingsInput,
  groupBudgetText,
  groupOperationLabel,
  groupPerformanceText,
  groupReportEntryText,
  groupStatusLabel,
  isUuid,
  money,
  operationResultLabel,
  pendingWritebackText,
  skuMappingText,
  timestampText,
  validateGroupCreate,
  validateGroupSettings,
} from './ad-group-model';
import type {
  AdGroupPerformanceAvailability,
  AdGroupProductRow,
  AdGroupReportEntry,
} from './ad-group-model';

/**
 * 14.1.2.1 广告组管理 前端模型。
 *
 * 🔴 这些断言盯的是**页面最容易说错的四件事**：
 *
 * 1. **组级表现「拿不到」与「没有表现」是两件事**。没有组级指标事实表 ⇒
 *    `available=false`。文案必须说「拿不到」，并说清责任模块；
 *    渲染成「暂无表现」就是在说「这个组没有投放数据」，那是假的。
 * 2. **组级报表入口是入口，不是报表**。文案必须同时给出路由标识与责任模块，
 *    让人知道报表本体归 14.3.1.2。
 * 3. **「本地已改」与「平台侧已改」分得开**，且「组级预算为空 = 继承活动预算」不是 0。
 * 4. **SKU 映射现状 ≠ 归因结果**。
 */

function performance(overrides: Partial<AdGroupPerformanceAvailability> = {}) {
  return {
    available: false,
    ownerModule: '14.3.1.2 广告组与词报表',
    impact: '组级表现拿不到真实指标',
    note: '没有组级指标事实表',
    ...overrides,
  };
}

function reportEntry(overrides: Partial<AdGroupReportEntry> = {}) {
  return {
    available: true,
    ownerModule: '14.3.1.2 广告组与词报表',
    reportRoute: '/ads/reports',
    routeIdentifier: '14.3.1.2',
    impact: '报表本体尚未并入集成头',
    note: '入口与路由标识已给出',
    ...overrides,
  };
}

function product(overrides: Partial<AdGroupProductRow> = {}): AdGroupProductRow {
  return {
    id: '33333333-3333-3333-3333-333333333333',
    groupId: '22222222-2222-2222-2222-222222222222',
    listingId: '11111111-1111-1111-1111-111111111111',
    externalItemId: 'ASIN-1',
    title: '商品一',
    listingStatus: 'ON_SALE',
    listingReviewStatus: null,
    internalSkuIds: [],
    unmappedVariantCount: 0,
    attributionNote: '归因归 14.3.1.3',
    ...overrides,
  };
}

describe('14.1.2.1 状态与动作标签', () => {
  it('广告组状态翻译成中文，未知状态原样归入「状态未知」', () => {
    expect(groupStatusLabel('ENABLED')).toBe('投放中');
    expect(groupStatusLabel('PAUSED')).toBe('已暂停');
    expect(groupStatusLabel('ENDED')).toBe('已结束');
    expect(groupStatusLabel('ARCHIVED')).toBe('已归档');
    expect(groupStatusLabel('PENDING_REVIEW')).toBe('审核中');
    expect(groupStatusLabel('WEIRD')).toBe('状态未知');
  });

  it('组级动作与结果标签', () => {
    expect(groupOperationLabel('CREATE_GROUP')).toBe('新建广告组');
    expect(groupOperationLabel('UPDATE_SETTINGS')).toBe('改组设置');
    expect(groupOperationLabel('SET_PRODUCTS')).toBe('设投放商品');
    expect(operationResultLabel('APPLIED')).toBe('已生效');
    expect(operationResultLabel('REJECTED')).toBe('被拒');
    expect(operationResultLabel('SKIPPED')).toBe('跳过');
  });
});

describe('14.1.2.1 🔴 组级表现：拿不到 ≠ 没有表现', () => {
  it('不可得时文案必须说「不可得」并点出责任模块', () => {
    const text = groupPerformanceText(performance());
    expect(text).toContain('当前不可得');
    expect(text).toContain('拿不到');
    expect(text).toContain('14.3.1.2');
    // 🔴 不许出现「暂无表现」这种把「拿不到」说成「没有过」的措辞
    expect(text).not.toContain('暂无表现');
    expect(text).not.toContain('没有数据');
  });

  it('可得时直接给服务端说明', () => {
    expect(groupPerformanceText(performance({ available: true }))).toContain('已可得');
  });
});

describe('14.1.2.1 🔴 组级报表入口：入口已交付 ≠ 报表已交付', () => {
  it('文案同时给出路由标识与责任模块', () => {
    const text = groupReportEntryText(reportEntry());
    expect(text).toContain('/ads/reports');
    expect(text).toContain('14.3.1.2');
    expect(text).toContain('报表本体归');
  });

  it('入口不可用时说清原因与责任模块', () => {
    const text = groupReportEntryText(reportEntry({ available: false }));
    expect(text).toContain('不可用');
    expect(text).toContain('14.3.1.2');
  });
});

describe('14.1.2.1 🔴 本地已改 ≠ 平台侧已改', () => {
  it('待回写与未待回写两种状态文案分得开', () => {
    expect(pendingWritebackText(true)).toContain('尚未回写平台');
    expect(pendingWritebackText(false)).toContain('与平台侧一致');
  });
});

describe('14.1.2.1 🔴 组级预算为空 = 继承活动预算，不是 0', () => {
  it('空预算显示「继承活动预算」而不是 0.00', () => {
    expect(groupBudgetText(null)).toBe('继承活动预算');
    expect(groupBudgetText(undefined)).toBe('继承活动预算');
  });

  it('有预算时照常展示金额', () => {
    expect(groupBudgetText(50, 'USD')).toBe('50.00 USD');
    expect(money(null)).toBe('—');
  });
});

describe('14.1.2.1 🔴 SKU 映射现状 ≠ 归因结果', () => {
  it('未映射时说明未映射，不说成「没有商品」', () => {
    const text = skuMappingText(product({ unmappedVariantCount: 2 }));
    expect(text).toContain('未映射到内部 SKU');
    expect(text).toContain('2');
  });

  it('已映射时给出映射个数与未映射个数', () => {
    const text = skuMappingText(
      product({ internalSkuIds: ['sku-1', 'sku-2'], unmappedVariantCount: 1 }),
    );
    expect(text).toContain('已映射 2 个内部 SKU');
    expect(text).toContain('1 个变体未映射');
  });

  it('没有变体记录时如实说明', () => {
    expect(skuMappingText(product())).toBe('该商品没有变体记录');
  });
});

describe('14.1.2.1 时间与形状', () => {
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

describe('14.1.2.1 新建广告组的表单校验', () => {
  it('活动与组名必填', () => {
    const result = validateGroupCreate({ ...emptyAdGroupCreateInput });
    expect(result.ok).toBe(false);
    expect(result.errors.join()).toContain('所属活动必填');
    expect(result.errors.join()).toContain('广告组名必填');
  });

  it('金额不能为负', () => {
    const negativeBid = validateGroupCreate({
      ...emptyAdGroupCreateInput,
      campaignId: '11111111-1111-1111-1111-111111111111',
      groupName: '组',
      defaultBid: '-1',
    });
    expect(negativeBid.ok).toBe(false);
    expect(negativeBid.errors.join()).toContain('默认竞价不能为负');

    const negativeBudget = validateGroupCreate({
      ...emptyAdGroupCreateInput,
      campaignId: '11111111-1111-1111-1111-111111111111',
      groupName: '组',
      dailyBudget: '-1',
    });
    expect(negativeBudget.ok).toBe(false);
    expect(negativeBudget.errors.join()).toContain('组级日预算不能为负');
  });

  it('金额留空是合法的（竞价 = 平台侧没给，预算 = 继承活动预算）', () => {
    const result = validateGroupCreate({
      ...emptyAdGroupCreateInput,
      campaignId: '11111111-1111-1111-1111-111111111111',
      groupName: '组',
    });
    expect(result.ok).toBe(true);
  });
});

describe('14.1.2.1 改组设置的表单校验', () => {
  it('「预算数值」与「恢复继承活动预算」互斥', () => {
    const result = validateGroupSettings({
      ...emptyAdGroupSettingsInput,
      dailyBudget: '50',
      clearDailyBudget: true,
    });
    expect(result.ok).toBe(false);
    expect(result.errors.join()).toContain('不能同时给');
  });

  it('负预算被拒', () => {
    const result = validateGroupSettings({
      ...emptyAdGroupSettingsInput,
      dailyBudget: '-1',
    });
    expect(result.ok).toBe(false);
    expect(result.errors.join()).toContain('组级日预算不能为负');
  });

  it('只勾「恢复继承活动预算」是合法的', () => {
    const result = validateGroupSettings({
      ...emptyAdGroupSettingsInput,
      clearDailyBudget: true,
    });
    expect(result.ok).toBe(true);
  });
});
