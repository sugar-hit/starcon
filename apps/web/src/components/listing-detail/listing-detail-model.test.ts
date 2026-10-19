import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  cellText,
  contentSourceLabel,
  diffLabel,
  diffSummary,
  diffTone,
  editAvailability,
  fetchListingDetail,
  fetchListingDetailCapabilities,
  fetchListingDetailContent,
  fetchListingDetailDiff,
  fetchPlatformOperations,
  fetchPublishHistory,
  groupLabel,
  mappingLabel,
  operationLabel,
  operationStatusHint,
  operationStatusLabel,
  publishSummary,
  quantityText,
  ratingText,
  reviewLabel,
  saveContentSnapshot,
  statusLabel,
  stockLabel,
  timeText,
  unavailableDimensions,
  variantMappingLabel,
  writebackAvailability,
  type DiffStatus,
  type ListingDetailCapabilities,
  type ListingDiffReport,
  type ListingDiffRow,
  type ListingPublishRecordView,
} from './listing-detail-model';

/**
 * 4.1.1.2 Listing 详情 前端模型 单测。
 *
 * 🔴 这些用例钉的都是**「不许画假数据」**那几件事：缺一侧不得显示成一致、
 * 未接入不得显示成 0 或 `--`、按钮不可用时必须带归属与理由。
 */

const ID = '11111111-1111-1111-1111-111111111111';

function row(patch: Partial<ListingDiffRow> = {}): ListingDiffRow {
  return {
    group: 'PRICE_STOCK',
    dimension: 'price',
    label: '售价',
    platformLabel: '回写登记簿当前价快照',
    platformValue: null,
    platformKnown: false,
    internalLabel: '内部定价（05）',
    internalValue: null,
    internalKnown: false,
    status: 'UNAVAILABLE',
    available: false,
    ownerModule: '05 定价与价目系统',
    impact: '售价差异本批次不可计算',
    note: '',
    ...patch,
  };
}

describe('🔴 差异状态：UNAVAILABLE 与 SAME 是两件不同的事', () => {
  it('状态文案互不重叠，且 UNAVAILABLE 绝不显示成「相同」', () => {
    expect(diffLabel('UNAVAILABLE')).toBe('未接入');
    expect(diffLabel('SAME')).toBe('相同');
    expect(diffLabel('DIFFERENT')).toBe('不同');
    expect(diffLabel('PLATFORM_ONLY')).toBe('只有平台侧有值');
    expect(diffLabel('INTERNAL_ONLY')).toBe('只有内部侧有值');
    expect(diffLabel('UNKNOWN')).toBe('两侧都没值');

    const labels = (['DIFFERENT', 'SAME', 'PLATFORM_ONLY', 'INTERNAL_ONLY', 'UNAVAILABLE', 'UNKNOWN'] as DiffStatus[])
      .map(diffLabel);
    expect(new Set(labels).size).toBe(labels.length);
  });

  it('配色：未接入是 warning，绝不是 success（绿色会让「没接入」读成「已核对一致」）', () => {
    expect(diffTone('DIFFERENT')).toBe('danger');
    expect(diffTone('SAME')).toBe('success');
    expect(diffTone('UNAVAILABLE')).toBe('warning');
    expect(diffTone('UNKNOWN')).toBe('neutral');
    expect(diffTone('PLATFORM_ONLY')).toBe('info');
    expect(diffTone('UNAVAILABLE')).not.toBe(diffTone('SAME'));
  });

  it('差异摘要用服务端给的计数，并如实带出「无法比对」的条数', () => {
    const report: ListingDiffReport = {
      listingId: ID,
      rows: [row()],
      differentCount: 2,
      sameCount: 5,
      unavailableCount: 11,
      note: '',
    };
    const text = diffSummary(report);
    expect(text).toContain('可比对且不同 2 条');
    expect(text).toContain('相同 5 条');
    // 未接入的条数必须出现在摘要里：隐藏它等于隐藏缺口
    expect(text).toContain('因一侧未接入而无法比对 11 条');
    expect(diffSummary(null)).toBe('');
  });
});

describe('🔴 缺值不得被画成 0 / -- / 空串', () => {
  it('cellText：没有值时返回「未接入」', () => {
    expect(cellText('25.5 USD', true)).toBe('25.5 USD');
    expect(cellText(null, false)).toBe('未接入');
    expect(cellText(null, true)).toBe('未接入');
    expect(cellText('', true)).toBe('未接入');
    // 绝不能返回 0 或 '--'：那两种都会被读成「平台上卖 0 块」或「平台上有、只是没显示」
    expect(cellText(null, false)).not.toBe('0');
    expect(cellText(null, false)).not.toBe('--');
    expect(cellText(null, false)).not.toBe('');
  });

  it('quantityText：null 显示「平台未回报」，不回 0', () => {
    expect(quantityText(3)).toBe('3');
    expect(quantityText(0)).toBe('0');
    expect(quantityText(null)).toBe('平台未回报');
    expect(quantityText(null)).not.toBe('0');
    expect(quantityText(null, '未登记')).toBe('未登记');
  });

  it('ratingText：平台没评分与平台给了 0 分是两回事', () => {
    expect(ratingText(null, 100)).toBe('平台未评分');
    expect(ratingText(0, 100)).toBe('0（100 条评论）');
    expect(ratingText(4.7, 128)).toBe('4.7（128 条评论）');
    expect(ratingText(4.7, null)).toBe('4.7');
  });

  it('timeText：没有时刻显示「—」，不显示 Invalid Date', () => {
    expect(timeText(null)).toBe('—');
    expect(timeText(undefined)).toBe('—');
    expect(timeText('not-a-date')).toBe('—');
    expect(timeText('2026-10-03T10:15:00+08:00')).not.toBe('—');
  });
});

describe('🔴 编辑入口与回写按钮：不可用时必须带归属与理由', () => {
  const caps = {
    editSupported: false,
    editOwnerModule: '4.2.1.1 刊登编辑器',
    editReason: '内容草稿与刊登编辑器归 4.2.1.1，尚未交付。',
    writebackSupported: false,
    writebackOwnerModule: '4.3.2.1 内容回写',
    writebackReason: '4.3.2.1 内容回写尚未交付。',
    dimensions: [],
  } as unknown as ListingDetailCapabilities;

  it('能力声明缺失时也不许「看起来可用」', () => {
    for (const fn of [editAvailability, writebackAvailability]) {
      const result = fn(null);
      expect(result.supported).toBe(false);
      expect(result.reason).toBeTruthy();
      expect(result.ownerModule).toBeTruthy();
    }
  });

  it('按服务端声明返回不可用 + 归属 + 理由', () => {
    const edit = editAvailability(caps);
    expect(edit.supported).toBe(false);
    expect(edit.ownerModule).toContain('4.2.1.1');
    expect(edit.reason).toContain('4.2.1.1');

    const wb = writebackAvailability(caps);
    expect(wb.supported).toBe(false);
    expect(wb.ownerModule).toContain('4.3.2.1');
    expect(wb.reason).toContain('4.3.2.1');
  });
});

describe('未接入维度明账', () => {
  it('只挑 available=false 的维度', () => {
    const caps = {
      dimensions: [
        { dimension: 'platform_profile', available: true },
        { dimension: 'price', available: false },
        { dimension: 'internal_stock', available: false },
      ],
    } as unknown as ListingDetailCapabilities;
    expect(unavailableDimensions(caps).map((d) => d.dimension)).toEqual([
      'price',
      'internal_stock',
    ]);
    expect(unavailableDimensions(null)).toEqual([]);
  });
});

describe('状态与分组文案', () => {
  it('平台商品状态 / 可售状态 / 映射状态各自独立', () => {
    expect(statusLabel('ON_SALE')).toBe('在售');
    expect(statusLabel('DELISTED_BY_PLATFORM')).toBe('平台下架');
    expect(statusLabel('DELISTED_BY_US')).toBe('主动下架');
    expect(statusLabel('UNKNOWN_STATUS')).toBe('UNKNOWN_STATUS');

    expect(stockLabel('IN_STOCK')).toBe('有货');
    expect(stockLabel('OUT_OF_STOCK')).toBe('平台缺货');
    // UNKNOWN 是「平台没回报」，不是「有货」
    expect(stockLabel('UNKNOWN')).toBe('平台未回报');
    expect(stockLabel('UNKNOWN')).not.toBe(stockLabel('IN_STOCK'));

    expect(mappingLabel('UNMAPPED')).toBe('未映射');
    expect(mappingLabel('PARTIAL')).toBe('部分映射');
    expect(mappingLabel('MAPPED')).toBe('已映射');
    expect(variantMappingLabel('BUNDLE')).toBe('套装');

    expect(reviewLabel('REJECTED')).toBe('已驳回');
    expect(groupLabel('REGISTRY')).toBe('系统登记 vs 平台回报');
    expect(groupLabel('CONTENT')).toBe('内容对照');
    expect(contentSourceLabel('SYNC')).toBe('02 同步');
  });
});

describe('发布与平台操作文案', () => {
  const record = (patch: Partial<ListingPublishRecordView> = {}): ListingPublishRecordView => ({
    id: 'r1',
    snapshotId: null,
    action: 'PUBLISH',
    outcome: 'SUCCEEDED',
    platformRequestId: 'req-1',
    reason: '',
    actorId: null,
    occurredAt: '2026-10-04T21:45:30+08:00',
    ...patch,
  });

  it('成功与失败都有明确措辞，失败必须带原因', () => {
    expect(publishSummary(record())).toContain('成功');
    expect(publishSummary(record())).toContain('req-1');
    expect(
      publishSummary(record({ outcome: 'FAILED', reason: '平台拒收：类目属性缺失' })),
    ).toContain('平台拒收：类目属性缺失');
    // 没写原因时也不许静默成一句「失败」
    expect(publishSummary(record({ outcome: 'FAILED' }))).toContain('未写明原因');
  });

  it('UNKNOWN 是「不知道平台上成没成」，文案不许压成「失败」', () => {
    expect(operationStatusLabel('UNKNOWN')).toBe('结果未知');
    expect(operationStatusLabel('UNKNOWN')).not.toBe('失败');
    expect(operationStatusHint('UNKNOWN')).toContain('先查证再决定重发');
    expect(operationStatusHint('SUCCEEDED')).toBe('');
    expect(operationLabel('UPDATE_STOCK')).toBe('改库存');
    expect(operationLabel('PUBLISH')).toBe('发布 / 下架');
  });
});

describe('API 路径：全部落在本模块自己的前缀下', () => {
  afterEach(() => vi.unstubAllGlobals());

  /** 真跑一次取数，断言打到的 URL 与方法（不是只看函数名对不对）。 */
  async function urlOf(call: () => Promise<unknown>): Promise<{ url: string; method: string }> {
    const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
    vi.stubGlobal('fetch', fetcher);
    await call();
    const [url, init] = fetcher.mock.calls[0] as [string, RequestInit];
    return { url, method: init.method ?? 'GET' };
  }

  it('七个读 / 写入口都走 channel/listing-details，且前缀正确', async () => {
    // 🔴 前缀写错的后果是静默 404（api-proxy 白名单只放行 listing-details）
    expect((await urlOf(fetchListingDetailCapabilities)).url).toBe(
      '/api/channel/channel/listing-details/capabilities',
    );
    expect((await urlOf(() => fetchListingDetail(ID))).url).toBe(
      `/api/channel/channel/listing-details/${ID}`,
    );
    expect((await urlOf(() => fetchListingDetailDiff(ID))).url).toBe(
      `/api/channel/channel/listing-details/${ID}/diff`,
    );
    expect((await urlOf(() => fetchListingDetailContent(ID))).url).toBe(
      `/api/channel/channel/listing-details/${ID}/content`,
    );
    expect((await urlOf(() => fetchPublishHistory(ID))).url).toBe(
      `/api/channel/channel/listing-details/${ID}/publish-history`,
    );
    expect((await urlOf(() => fetchPlatformOperations(ID))).url).toBe(
      `/api/channel/channel/listing-details/${ID}/platform-operations`,
    );

    const write = await urlOf(() => saveContentSnapshot(ID, { language: 'en-US' }));
    expect(write.url).toBe(`/api/channel/channel/listing-details/${ID}/content-snapshots`);
    expect(write.method).toBe('POST');
  });
});
