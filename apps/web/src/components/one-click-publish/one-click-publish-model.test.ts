import { describe, expect, it } from 'vitest';
import {
  buildCreateRequest,
  canRetry,
  contentSourceCandidates,
  dispatchSummary,
  emptyStateMessage,
  outcomeLabel,
  priceStateNotice,
  progressSummary,
  retryHint,
  stockStateNotice,
  targetInputKey,
  targetOptionKey,
  targetOptionLabel,
  targetStateLabel,
  targetStateTone,
  validateCreateInput,
  type ListingPublishContentSources,
  type ListingPublishJobRow,
  type ListingPublishProgress,
  type ListingPublishTargetOption,
  type ListingPublishTargetRow,
} from './one-click-publish-model';

/**
 * 4.3.1.1 一键刊登 前端模型的纯函数断言。
 *
 * 🔴 这里钉住的是**页面上最容易做丢的几件事**：
 *   1. 「审核中」不是「已发布」；
 *   2. 取价不可用时显示「不可用 + 归属模块」，**不是** 0、也不是「没有这一栏」；
 *   3. 只有失败的目标能重试，被驳回的目标不能；
 *   4. 进度与结论原样显示服务端的数，前端不重算；
 *   5. 派发结果逐目标列，不合并成一个总数。
 */

function job(overrides: Partial<ListingPublishJobRow> = {}): ListingPublishJobRow {
  return {
    jobId: 'job-1',
    sourceSkuId: 'sku-1',
    contentSource: 'EDITOR_SUBMISSION',
    contentSourceIds: [],
    priceSource: 'PRICING_SERVICE',
    priceAmount: null,
    priceCurrency: null,
    priceState: 'UNAVAILABLE',
    priceStateReason: '取价来源 05 定价（5.1.1.1）尚未交付：本次一键刊登不带平台售价',
    stockSource: 'KEEP_UNSET',
    stockQuantity: null,
    stockState: 'NOT_SET',
    stockStateReason: '本次不设置库存（由平台侧自行维护）',
    state: 'DRAFT',
    primaryShopId: 'shop-1',
    idempotencyKey: 'key-1',
    note: '',
    progress: {
      total: 2,
      draft: 2,
      inReview: 0,
      succeeded: 0,
      rejected: 0,
      failed: 0,
      percent: 0,
      outcome: 'NOT_DISPATCHED',
    },
    createdAt: '2026-10-20T00:00:00Z',
    updatedAt: '2026-10-20T00:00:00Z',
    dispatchedAt: null,
    completedAt: null,
    ...overrides,
  };
}

function target(overrides: Partial<ListingPublishTargetRow> = {}): ListingPublishTargetRow {
  return {
    targetId: 'target-1',
    jobId: 'job-1',
    position: 0,
    platformId: 'platform-1',
    platformCode: 'AMAZON',
    shopId: 'shop-1',
    shopCode: 'AMZ-1',
    marketplaceId: 'site-1',
    marketplaceCode: 'US',
    locale: 'en-US',
    categoryPath: '',
    state: 'SUBMITTED',
    contentSource: 'EDITOR_SUBMISSION',
    contentSourceId: 'submission-1',
    contentSnapshot: null,
    externalItemId: null,
    platformRequestId: '',
    failureReason: '',
    attempts: 1,
    platformOperationId: null,
    dispatchedAt: null,
    completedAt: null,
    ...overrides,
  };
}

describe('4.3.1.1 目标状态文案', () => {
  it('「审核中」与「已成功」刻意不同', () => {
    expect(targetStateLabel('SUBMITTED')).toBe('审核中（等平台回执）');
    expect(targetStateLabel('SUCCEEDED')).toBe('已成功');
    expect(targetStateLabel('SUBMITTED')).not.toContain('成功');
  });

  it('驳回与失败是两种状态，文案不同', () => {
    expect(targetStateLabel('REJECTED')).toBe('平台驳回');
    expect(targetStateLabel('FAILED')).toBe('失败');
    expect(targetStateTone('REJECTED')).toBe('warning');
    expect(targetStateTone('FAILED')).toBe('danger');
    expect(targetStateTone('SUCCEEDED')).toBe('success');
    expect(targetStateTone('SUBMITTED')).toBe('info');
  });
});

describe('4.3.1.1 价格与库存状态', () => {
  it('取价不可用时如实显示不可用并给出来源模块，不显示成 0', () => {
    const unavailable = job();
    const notice = priceStateNotice(unavailable);
    expect(notice).toContain('5.1.1.1');
    // 🔴 不许出现"金额 + 币种"这种形状 —— 那就是一个被编出来的价
    expect(notice).not.toMatch(/\d+(\.\d+)?\s*(USD|CNY|EUR|JPY|GBP|RMB)/i);
    expect(unavailable.priceAmount).toBeNull();
  });

  it('价格已冻结时显示服务端给的金额与币种（前端不自己换算）', () => {
    const notice = priceStateNotice(
      job({ priceState: 'SET', priceAmount: 19.99, priceCurrency: 'USD' }),
    );
    expect(notice).toContain('19.99');
    expect(notice).toContain('USD');
  });

  it('库存已冻结时显示可售量；未设置时说明原因', () => {
    expect(stockStateNotice(job({ stockState: 'SET', stockQuantity: 7.5 }))).toContain('7.5');
    expect(stockStateNotice(job())).toContain('不设置库存');
  });
});

describe('4.3.1.1 进度与结论', () => {
  it('进度摘要原样使用服务端给的计数与百分比', () => {
    const progress: ListingPublishProgress = {
      total: 3,
      draft: 0,
      inReview: 1,
      succeeded: 1,
      rejected: 1,
      failed: 0,
      percent: 67,
      outcome: 'PARTIAL',
    };
    const summary = progressSummary(progress);
    expect(summary).toContain('共 3 个目标');
    expect(summary).toContain('审核中 1');
    expect(summary).toContain('成功 1');
    expect(summary).toContain('驳回 1');
    expect(summary).toContain('67%');
    expect(summary).toContain('部分成功');
  });

  it('总体结论文案', () => {
    expect(outcomeLabel('COMPLETED')).toBe('全部成功');
    expect(outcomeLabel('PARTIAL')).toBe('部分成功');
    expect(outcomeLabel('FAILED')).toBe('全部失败');
    expect(outcomeLabel('NOT_DISPATCHED')).toBe('还有目标没出结果');
  });
});

describe('4.3.1.1 独立重试', () => {
  it('只有失败的目标能重试', () => {
    expect(canRetry(target({ state: 'FAILED' }))).toBe(true);
    expect(canRetry(target({ state: 'SUBMITTED' }))).toBe(false);
    expect(canRetry(target({ state: 'SUCCEEDED' }))).toBe(false);
  });

  it('被驳回的目标不能重试（内容没改，重发只会再被驳回一次）', () => {
    expect(canRetry(target({ state: 'REJECTED' }))).toBe(false);
    expect(retryHint(target({ state: 'REJECTED' }))).toContain('驳回');
  });

  it('失败目标的重试提示写明发的是同一份内容', () => {
    expect(retryHint(target({ state: 'FAILED', attempts: 2 }))).toContain('同一份');
    expect(retryHint(target({ state: 'FAILED', attempts: 2 }))).toContain('2');
  });
});

describe('4.3.1.1 建作业请求', () => {
  const base = {
    sourceSkuId: 'sku-1',
    targets: [{ shopId: 'shop-1', marketplaceId: 'site-1', locale: 'en-US', categoryPath: '' }],
    contentSource: 'EDITOR_SUBMISSION',
    contentSourceIds: ['submission-1'],
    priceSource: 'PRICING_SERVICE',
    priceAmount: '',
    priceCurrency: '',
    stockSource: 'KEEP_UNSET',
    idempotencyKey: 'key-1',
    note: '',
  };

  it('取价不可用时不下发金额与币种（不是空串，是不下发）', () => {
    const body = buildCreateRequest(base);
    expect(body.priceSource).toBe('PRICING_SERVICE');
    expect('priceAmount' in body).toBe(false);
    expect('priceCurrency' in body).toBe(false);
  });

  it('运营手填时金额与币种必须带上，币种转大写', () => {
    const body = buildCreateRequest({
      ...base,
      priceSource: 'MANUAL',
      priceAmount: '19.99',
      priceCurrency: 'usd',
    });
    expect(body.priceAmount).toBe(19.99);
    expect(body.priceCurrency).toBe('USD');
  });

  it('本地校验拦住 obviously 不完整的请求', () => {
    expect(validateCreateInput({ ...base, sourceSkuId: '  ' })).toContain('源 SKU');
    expect(validateCreateInput({ ...base, targets: [] })).toContain('目标店铺');
    expect(validateCreateInput({ ...base, contentSourceIds: [] })).toContain('内容来源');
    expect(
      validateCreateInput({
        ...base,
        priceSource: 'MANUAL',
        priceAmount: '',
        priceCurrency: 'USD',
      }),
    ).toContain('金额');
    expect(
      validateCreateInput({ ...base, priceSource: 'MANUAL', priceAmount: '1', priceCurrency: '' }),
    ).toContain('币种');
    expect(validateCreateInput({ ...base, idempotencyKey: '' })).toContain('幂等键');
    expect(validateCreateInput(base)).toBeNull();
  });
});

describe('4.3.1.1 内容来源候选', () => {
  const sources: ListingPublishContentSources = {
    editorSubmissions: [
      {
        submissionId: 'submission-1',
        platformCode: 'AMAZON',
        shopId: 'shop-1',
        shopCode: 'AMZ-1',
        marketplaceId: 'site-1',
        marketplaceCode: 'US',
        locale: 'en-US',
        state: 'QUEUED',
        title: '标题',
        imageCount: 2,
        queuedAt: '2026-10-20T00:00:00Z',
      },
    ],
    templates: [
      {
        templateId: 'template-1',
        code: 'TPL-1',
        name: '模板',
        scope: 'SHOP',
        scopeTarget: '店铺 shop-1',
        platformId: 'platform-1',
        platformCode: 'AMAZON',
        marketplaceId: null,
        marketplaceCode: null,
        language: 'en-US',
        status: 'ACTIVE',
        fieldCount: 3,
        assetGroupCount: 1,
      },
    ],
  };

  it('两类候选按内容来源分开，不混在一起', () => {
    const editor = contentSourceCandidates(sources, 'EDITOR_SUBMISSION');
    expect(editor.submissions).toHaveLength(1);
    expect(editor.templates).toHaveLength(0);

    const template = contentSourceCandidates(sources, 'TEMPLATE');
    expect(template.templates).toHaveLength(1);
    expect(template.submissions).toHaveLength(0);
  });
});

describe('4.3.1.1 派发结果', () => {
  it('逐目标列出，不合并成一个总数', () => {
    const lines = dispatchSummary({
      jobId: 'job-1',
      jobState: 'DISPATCHED',
      entries: [
        {
          targetId: 't1',
          shopCode: 'AMZ-1',
          marketplaceCode: 'US',
          platformCode: 'AMAZON',
          result: 'DISPATCHED',
          note: '已提交平台审核',
        },
        {
          targetId: 't2',
          shopCode: 'SHP-1',
          marketplaceCode: 'US',
          platformCode: 'SHOPIFY',
          result: 'FAILED',
          note: '派发失败（不影响其它目标）',
        },
      ],
      progress: {
        total: 2,
        draft: 0,
        inReview: 1,
        succeeded: 0,
        rejected: 0,
        failed: 1,
        percent: 50,
        outcome: 'NOT_DISPATCHED',
      },
    });
    expect(lines).toHaveLength(2);
    expect(lines[0]).toContain('AMAZON');
    expect(lines[1]).toContain('SHOPIFY');
    expect(lines[1]).toContain('派发失败');
  });

  it('没有可派发的目标时给出明确说法，而不是空字符串', () => {
    const lines = dispatchSummary({
      jobId: 'job-1',
      jobState: 'DISPATCHED',
      entries: [],
      progress: {
        total: 1,
        draft: 0,
        inReview: 1,
        succeeded: 0,
        rejected: 0,
        failed: 0,
        percent: 0,
        outcome: 'NOT_DISPATCHED',
      },
    });
    expect(lines[0]).toContain('没有可派发');
  });
});

describe('4.3.1.1 空态与目标键', () => {
  it('三种「空」的文案不同', () => {
    const jobs = emptyStateMessage('jobs');
    const targets = emptyStateMessage('targets');
    const sources = emptyStateMessage('sources');
    expect(new Set([jobs, targets, sources]).size).toBe(3);
  });

  it('目标键由 店铺 + 站点 + 语言 组成', () => {
    const option: ListingPublishTargetOption = {
      shopId: 'shop-1',
      shopCode: 'AMZ-1',
      platformId: 'platform-1',
      platformCode: 'AMAZON',
      platformName: 'Amazon',
      supportStatus: 'AVAILABLE',
      marketplaceId: 'site-1',
      marketplaceCode: 'US',
      marketplaceName: '美国',
      countryCode: 'US',
      defaultLocale: 'en-US',
      capabilities: {},
    };
    expect(targetOptionKey(option)).toBe('shop-1|site-1|en-US');
    expect(targetInputKey({ shopId: 'shop-1', marketplaceId: 'site-1', locale: 'en-US' })).toBe(
      'shop-1|site-1|en-US',
    );
    expect(targetOptionLabel(option)).toContain('AMAZON');
  });
});
