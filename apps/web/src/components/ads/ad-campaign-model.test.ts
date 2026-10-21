import { describe, expect, it } from 'vitest';
import {
  adStatusLabel,
  adTypeLine,
  emptyAdImportInput,
  emptyStateText,
  guardrailText,
  internalTypeLabel,
  isUuid,
  mappingSourceLabel,
  money,
  operationLabel,
  operationResultLabel,
  quantity,
  ratioText,
  syncStatusLabel,
  timestampText,
  validateAdImport,
  validateBulkOperation,
} from './ad-campaign-model';
import type { AdBudgetGuardrails, AdCampaignRow } from './ad-campaign-model';

/**
 * 14.1.1.1 广告活动列表 前端模型。
 *
 * 🔴 这些断言盯的是**页面最容易说错的三件事**：
 *
 * 1. **三种「空」必须分开说**。「还没同步到数据」与「真的没有投放」是相反的两件事 ——
 *    渲染成同一种空态就是把「没数据」说成「没投放」，而这正是蓝图加粗句点名要防的。
 * 2. **比率的 null 与 0 含义相反**。null = 不可比（分母为 0），0 = 可比但真的是零。
 *    渲染成 0.00% 会让人以为「一次点击都没有」，真相是「没有展示量，比率无从计算」。
 * 3. **失败的同步不许带水位、必须带原因**。带了就等于让「数据截至 X 时间」虚高。
 */

function row(overrides: Partial<AdCampaignRow> = {}): AdCampaignRow {
  return {
    id: '11111111-1111-1111-1111-111111111111',
    platformId: '22222222-2222-2222-2222-222222222222',
    platformCode: 'AMAZON',
    platformName: 'Amazon',
    shopCode: 'SHOP-1',
    shopName: '店铺一',
    externalCampaignId: 'EXT-1',
    campaignName: '活动一',
    platformAdType: 'sponsored_brand',
    internalAdType: 'SPONSORED_BRAND',
    adTypeMappingSource: 'PLATFORM_DEFAULT',
    adTypeComparable: true,
    status: 'ENABLED',
    currency: 'USD',
    dailyBudget: 100,
    impressions: 1000,
    clicks: 50,
    orders: 5,
    spend: 120,
    sales: 600,
    ctr: 0.05,
    acos: 0.2,
    roas: 5,
    startAt: null,
    endAt: null,
    pendingPlatformWriteback: false,
    localChangedAt: null,
    dataAsOf: '2026-10-01T01:00:00Z',
    lastSyncedAt: '2026-10-01T01:05:00Z',
    note: '',
    version: 1,
    createdAt: '2026-10-01T01:00:00Z',
    updatedAt: '2026-10-01T01:00:00Z',
    ...overrides,
  };
}

describe('14.1.1.1 三种「空」必须分开说', () => {
  it('还没同步到数据 ≠ 没有投放', () => {
    const noSync = emptyStateText('NO_SYNCED_DATA', '');
    const noCampaigns = emptyStateText('NO_CAMPAIGNS', '');
    expect(noSync).toContain('还没同步到数据');
    expect(noSync).not.toBe(noCampaigns);
    expect(noCampaigns).toContain('确实');
  });

  it('被筛掉是第三种空态，不等于没有投放', () => {
    const filtered = emptyStateText('FILTERED_EMPTY', '');
    expect(filtered).toContain('筛选条件');
    expect(filtered).not.toContain('没有投放');
  });

  it('服务端给了说明就以服务端为准（浏览器不自己编口径）', () => {
    expect(emptyStateText('NO_CAMPAIGNS', '服务端给的说明')).toBe('服务端给的说明');
  });

  it('没有空态原因时返回空串（不是一段硬凑的解释）', () => {
    expect(emptyStateText(null, '')).toBe('');
  });
});

describe('14.1.1.1 比率：null 与 0 含义相反', () => {
  it('null 渲染成「不可比」，不是 0.00%', () => {
    expect(ratioText(null)).toBe('不可比');
    expect(ratioText(null, false)).toBe('不可比');
  });

  it('0 渲染成 0.00%（可比但真的是零）', () => {
    expect(ratioText(0)).toBe('0.00%');
    expect(ratioText(0, false)).toBe('0.0000');
  });

  it('有值时按百分比 / 小数两种形态渲染', () => {
    expect(ratioText(0.2)).toBe('20.00%');
    expect(ratioText(0.2, false)).toBe('0.2000');
  });
});

describe('14.1.1.1 金额与数量只原样展示', () => {
  it('null 渲染成破折号而不是 0', () => {
    expect(money(null)).toBe('—');
    expect(quantity(null)).toBe('—');
  });

  it('带币种时拼在后面', () => {
    expect(money(120, 'USD')).toBe('120.00 USD');
    expect(money(120)).toBe('120.00');
  });
});

describe('14.1.1.1 类型：原始标记与归一类型成对出现', () => {
  it('类型提示同时给出原始标记与归一类型', () => {
    const line = adTypeLine(row());
    expect(line).toContain('sponsored_brand');
    expect(line).toContain('SB 品牌广告');
  });

  it('未映射是显式合法值，不是「没有这种广告」', () => {
    expect(internalTypeLabel('UNMAPPED')).toContain('未映射');
    expect(mappingSourceLabel('UNMAPPED')).toBe('未映射');
    expect(mappingSourceLabel('TENANT_OVERRIDE')).toBe('租户覆盖');
  });

  it('状态标签覆盖六个归一状态', () => {
    expect(adStatusLabel('ENABLED')).toBe('投放中');
    expect(adStatusLabel('PAUSED')).toBe('已暂停');
    expect(adStatusLabel('ENDED')).toBe('已结束');
    expect(adStatusLabel('ARCHIVED')).toBe('已归档');
    expect(adStatusLabel('PENDING_REVIEW')).toBe('审核中');
    expect(adStatusLabel('WHATEVER')).toBe('状态未知');
  });
});

describe('14.1.1.1 同步状态与操作结果', () => {
  it('失败同步有独立标签', () => {
    expect(syncStatusLabel('FAILED')).toBe('同步失败');
    expect(syncStatusLabel('PARTIAL')).toBe('部分成功');
    expect(syncStatusLabel('SUCCESS')).toBe('同步成功');
  });

  it('批量结果三种状态各有标签', () => {
    expect(operationResultLabel('APPLIED')).toBe('已生效');
    expect(operationResultLabel('REJECTED')).toBe('被拒');
    expect(operationResultLabel('SKIPPED')).toBe('跳过');
    expect(operationLabel('ADJUST_BUDGET')).toBe('调整预算');
  });

  it('护栏上限被写成一句人话（界面上要看得见天花板）', () => {
    const guardrails: AdBudgetGuardrails = {
      maxSingleBudgetDelta: 5000,
      maxTotalBudgetDelta: 20000,
      maxCampaignBudget: 100000,
      maxCampaignsPerRequest: 200,
      note: '',
    };
    const text = guardrailText(guardrails);
    expect(text).toContain('5000');
    expect(text).toContain('20000');
    expect(text).toContain('100000');
    expect(text).toContain('200');
    expect(guardrailText(null)).toBe('');
  });
});

describe('14.1.1.1 登记同步结果的表单校验', () => {
  it('失败的同步必须写原因、且不许带数据截至时刻', () => {
    const input = {
      ...emptyAdImportInput,
      platformId: '11111111-1111-1111-1111-111111111111',
      shopCode: 'SHOP-1',
      syncStatus: 'FAILED',
      startedAt: '2026-10-01T08:00:00+08:00',
      externalCampaignId: 'EXT-1',
      adTypeProfileId: '22222222-2222-2222-2222-222222222222',
      metricDate: '2026-10-01',
    };
    const missingReason = validateAdImport(input);
    expect(missingReason.ok).toBe(false);
    expect(missingReason.errors.join(' ')).toContain('失败原因');

    const withWatermark = validateAdImport({
      ...input,
      message: '平台接口 503',
      dataAsOf: '2026-10-01T09:00:00+08:00',
    });
    expect(withWatermark.ok).toBe(false);
    expect(withWatermark.errors.join(' ')).toContain('不推进水位');
  });

  it('成功的同步必须给出数据截至时刻', () => {
    const input = {
      ...emptyAdImportInput,
      platformId: '11111111-1111-1111-1111-111111111111',
      shopCode: 'SHOP-1',
      syncStatus: 'SUCCESS',
      startedAt: '2026-10-01T08:00:00+08:00',
      externalCampaignId: 'EXT-1',
      adTypeProfileId: '22222222-2222-2222-2222-222222222222',
      metricDate: '2026-10-01',
    };
    const result = validateAdImport(input);
    expect(result.ok).toBe(false);
    expect(result.errors.join(' ')).toContain('数据截至时刻');

    const ok = validateAdImport({ ...input, dataAsOf: '2026-10-01T09:00:00+08:00' });
    expect(ok.errors).toEqual([]);
    expect(ok.ok).toBe(true);
  });

  it('平台 id 与广告类型档案 id 必须是合法 UUID', () => {
    const input = {
      ...emptyAdImportInput,
      platformId: 'not-a-uuid',
      shopCode: 'SHOP-1',
      syncStatus: 'SUCCESS',
      dataAsOf: '2026-10-01T09:00:00+08:00',
      startedAt: '2026-10-01T08:00:00+08:00',
      externalCampaignId: 'EXT-1',
      adTypeProfileId: 'also-not-a-uuid',
      metricDate: '2026-10-01',
    };
    const result = validateAdImport(input);
    expect(result.ok).toBe(false);
    expect(result.errors.join(' ')).toContain('UUID');
  });

  it('负的消耗 / 销售额被挡下', () => {
    const input = {
      ...emptyAdImportInput,
      platformId: '11111111-1111-1111-1111-111111111111',
      shopCode: 'SHOP-1',
      syncStatus: 'SUCCESS',
      dataAsOf: '2026-10-01T09:00:00+08:00',
      startedAt: '2026-10-01T08:00:00+08:00',
      externalCampaignId: 'EXT-1',
      adTypeProfileId: '22222222-2222-2222-2222-222222222222',
      metricDate: '2026-10-01',
      spend: '-1',
    };
    const result = validateAdImport(input);
    expect(result.ok).toBe(false);
    expect(result.errors.join(' ')).toContain('消耗');
  });
});

describe('14.1.1.1 批量操作的表单校验', () => {
  it('一条都没勾选时挡住', () => {
    const result = validateBulkOperation([], 'PAUSE', '', '');
    expect(result.ok).toBe(false);
    expect(result.errors.join(' ')).toContain('勾选');
  });

  it('设为预算必须给出非负数字', () => {
    expect(validateBulkOperation(['a'], 'SET_BUDGET', '', '').ok).toBe(false);
    expect(validateBulkOperation(['a'], 'SET_BUDGET', '-5', '').ok).toBe(false);
    expect(validateBulkOperation(['a'], 'SET_BUDGET', '100', '').ok).toBe(true);
  });

  it('调整额允许是负数（减预算），但必须是数字', () => {
    expect(validateBulkOperation(['a'], 'ADJUST_BUDGET', '', 'abc').ok).toBe(false);
    expect(validateBulkOperation(['a'], 'ADJUST_BUDGET', '', '-100').ok).toBe(true);
  });
});

describe('14.1.1.1 小工具', () => {
  it('UUID 形状校验只认完整形状', () => {
    expect(isUuid('11111111-1111-1111-1111-111111111111')).toBe(true);
    expect(isUuid('capabilities')).toBe(false);
    expect(isUuid('11111111-1111-1111-1111-1111111111111')).toBe(false);
  });

  it('时间戳为空时渲染成破折号', () => {
    expect(timestampText(null)).toBe('—');
    expect(timestampText('')).toBe('—');
    expect(timestampText('2026-10-01T01:00:00Z')).not.toBe('—');
  });
});
