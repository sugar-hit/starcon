import { describe, expect, it } from 'vitest';
import {
  acosTone,
  bulkItemStatusLabel,
  bulkStatusLabel,
  daysAgoUtc,
  emptyBulkActionText,
  emptyFrequencyText,
  emptyKeywordText,
  emptyNegativeText,
  emptyReportText,
  emptySearchTermText,
  emptySuggestionText,
  evidenceLines,
  guardrailSummary,
  internalTypeLabel,
  keywordStatusLabel,
  money,
  negativeStatusLabel,
  newIdempotencyKey,
  quantity,
  rankText,
  ratio,
  ratioRaw,
  suggestionRuleLabel,
  syncStatusText,
  todayUtc,
  validateBulkAction,
  validateKeywordInput,
  validateMetricBatch,
  validateNegative,
  validateSearchTermBatch,
  wasteTone,
} from './ad-keywords-model';
import type { AdKeywordInput } from './ad-keywords-model';

/**
 * 14.1.3.1 关键词管理 前端模型的纯函数单测。
 *
 * 🔴 这里钉住的是**四件最容易在前端做丢的事**：
 * 1. `null`（不可比 / 没数据）与 `0`（可比但真的是零）必须分开渲染；
 * 2. 名次为 null 时**不是 0**（没关联 / 平台没报）；
 * 3. 否定必须写明原因、必须带依据；
 * 4. 批量操作必须带幂等键、不能有重复目标。
 */
describe('14.1.3.1 关键词管理 · 前端模型', () => {
  it('金额：null 显示「—」，0 显示 0（两者不是一回事）', () => {
    expect(money(null)).toBe('—');
    expect(money(0)).toBe('0');
    expect(money(12.3456, 'USD')).toBe('12.3456 USD');
    expect(money(0, 'EUR')).toBe('0 EUR');
  });

  it('比率：null 是「不可比」，0 是「0.00%」（分母为 0 ≠ 真的是零）', () => {
    expect(ratio(null)).toBe('不可比');
    expect(ratio(0)).toBe('0.00%');
    expect(ratio(0.25)).toBe('25.00%');
    expect(ratioRaw(null)).toBe('不可比');
    expect(ratioRaw(0.2)).toBe('0.2000');
  });

  it('计数：null 显示「—」', () => {
    expect(quantity(null)).toBe('—');
    expect(quantity(0)).toBe('0');
  });

  it('名次：null 是「—」，不是「第 0 名」', () => {
    expect(rankText(null)).toBe('—');
    expect(rankText(0)).toBe('第 0 名');
    expect(rankText(7)).toBe('第 7 名');
  });

  it('同步状态：区分「没跑这个词」与「还没同步到数据」', () => {
    expect(syncStatusText({ syncStatus: 'NEVER_SYNCED', dataAsOf: null, syncNote: '' })).toContain(
      '没数据',
    );
    expect(
      syncStatusText({ syncStatus: 'SYNCED', dataAsOf: '2026-10-21T03:30:15Z', syncNote: '' }),
    ).toContain('数据截至');
  });

  it('状态标签覆盖全部取值', () => {
    expect(keywordStatusLabel('ACTIVE')).toBe('在投');
    expect(keywordStatusLabel('PAUSED')).toBe('暂停');
    expect(keywordStatusLabel('ARCHIVED')).toBe('已归档');
    expect(negativeStatusLabel('DECIDED')).toContain('待推平台');
    expect(negativeStatusLabel('PUSHED')).toBe('平台已确认');
    expect(negativeStatusLabel('REJECTED')).toBe('平台已拒绝');
    expect(bulkStatusLabel('PARTIAL')).toBe('部分成功');
    expect(bulkStatusLabel('REJECTED')).toBe('整批拒绝');
    expect(bulkItemStatusLabel('SKIPPED')).toBe('跳过');
    expect(suggestionRuleLabel('HIGH_IMPRESSION_ZERO_CONVERSION')).toBe('高曝光零转化');
  });

  it('归一类型：UNMAPPED 是显式值，不是「没有这种广告」', () => {
    expect(internalTypeLabel('UNMAPPED')).toContain('未映射');
    expect(internalTypeLabel('SPONSORED_BRAND')).toBe('品牌推广');
  });

  it('空态文案都写清下一步做什么', () => {
    expect(emptyKeywordText(0, '')).toContain('登记');
    expect(emptyKeywordText(3, 'abc')).toContain('abc');
    expect(emptySearchTermText(true)).toContain('高曝光零转化');
    expect(emptySuggestionText()).toContain('ACOS');
    expect(emptyNegativeText()).toContain('否定词建议');
    expect(emptyBulkActionText()).toContain('批量调整');
    expect(emptyReportText()).toContain('指标数据');
    expect(emptyFrequencyText()).toContain('关键词');
  });

  it('ACOS 档位：null 与超过目标都单独处理', () => {
    expect(acosTone(null, 0.3)).toBe('text-muted-foreground');
    expect(acosTone(0.5, 0.3)).toBe('text-destructive');
    expect(acosTone(0.1, 0.3)).toBe('text-foreground');
    expect(acosTone(0.1, null)).toBe('text-muted-foreground');
  });

  it('浪费标记：只有服务端判定为浪费时才高亮', () => {
    expect(wasteTone(true)).toContain('text-destructive');
    expect(wasteTone(false)).toBe('text-muted-foreground');
  });

  it('护栏摘要来自服务端能力声明（前端不重算）', () => {
    const summary = guardrailSummary({
      maxBatchItems: 200,
      maxBidDeltaRatio: 0.5,
      maxBidAbsolute: 1000,
      maxTotalBidIncrease: 5000,
    });
    expect(summary).toContain('200');
    expect(summary).toContain('50%');
    expect(summary).toContain('1000');
    expect(summary).toContain('5000');
    expect(guardrailSummary(undefined)).toBe('');
  });

  it('依据快照展示：把实际数值列出来，并标出「没有数据」', () => {
    const lines = evidenceLines({
      hasData: true,
      impressions: 1500,
      clicks: 45,
      conversions: 0,
      spend: 150,
      sales: 0,
      acos: null,
      currency: 'USD',
      dataAsOf: '2026-10-21T03:30:15Z',
    });
    expect(lines.join(' ')).toContain('曝光 1,500');
    expect(lines.join(' ')).toContain('点击 45');
    expect(lines.join(' ')).toContain('转化 0');
    expect(lines.join(' ')).toContain('花费 150 USD');
    expect(lines.join(' ')).toContain('ACOS 不可比');

    const noData = evidenceLines({ hasData: false, impressions: 0, clicks: 0, conversions: 0 });
    expect(noData.join(' ')).toContain('没有看到这个词的数据');
  });

  it('登记关键词：竞价必须配币种', () => {
    const base: AdKeywordInput = {
      platformId: 'p',
      shopId: 's',
      externalCampaignId: 'c',
      externalKeywordId: 'k',
      keyword: 'word',
      platformAdType: 'sponsoredProducts',
    };
    expect(validateKeywordInput(base).ok).toBe(true);
    expect(validateKeywordInput({ ...base, bid: 1.2 }).errors).toContain(
      '给了竞价就必须给竞价币种（批量调价按币种计价）',
    );
    expect(validateKeywordInput({ ...base, bid: 1.2, bidCurrency: 'USD' }).ok).toBe(true);
    expect(validateKeywordInput({ ...base, bid: -1, bidCurrency: 'USD' }).errors.join()).toContain(
      '竞价不能为负',
    );
    expect(validateKeywordInput({ ...base, keyword: '  ' }).errors.join()).toContain(
      '必须填关键词原文',
    );
  });

  it('指标同步：必须有幂等键与数据截至时间', () => {
    const request = {
      source: 'API',
      sourceRef: '',
      idempotencyKey: '',
      note: '',
      metrics: [
        {
          keywordId: '',
          bucketDate: '',
          impressions: 0,
          clicks: 0,
          conversions: 0,
          spend: 0,
          sales: 0,
          currency: '',
          syncedAt: '',
        },
      ],
    };
    const result = validateMetricBatch(request);
    expect(result.ok).toBe(false);
    expect(result.errors.join()).toContain('幂等键');
    expect(result.errors.join()).toContain('数据截至时间');
    expect(result.errors.join()).toContain('币种');
  });

  it('搜索词同步：必须填搜索词原文与外部活动 id', () => {
    const result = validateSearchTermBatch({
      source: 'API',
      sourceRef: '',
      idempotencyKey: 'k',
      note: '',
      terms: [
        {
          keywordId: 'k1',
          bucketDate: '2026-10-21',
          searchTerm: '  ',
          externalCampaignId: '',
          impressions: 0,
          clicks: 0,
          conversions: 0,
          spend: 0,
          sales: 0,
          currency: 'USD',
          syncedAt: '2026-10-21T00:00:00Z',
        },
      ],
    });
    expect(result.ok).toBe(false);
    expect(result.errors.join()).toContain('搜索词原文');
    expect(result.errors.join()).toContain('外部活动 id');
  });

  it('否定：必须写明原因（否定要能追溯到依据）', () => {
    const result = validateNegative({
      keywordId: '',
      negationText: '',
      externalCampaignId: '',
      reason: '  ',
    });
    expect(result.ok).toBe(false);
    expect(result.errors.join()).toContain('必须选关键词');
    expect(result.errors.join()).toContain('原因');
  });

  it('批量操作：必须有幂等键、不能有重复目标', () => {
    const result = validateBulkAction({
      actionType: 'BID_ADJUST',
      shopId: 'shop-1',
      bidMode: 'PERCENT',
      currency: 'USD',
      targets: [
        { keywordId: 'k1', bidValue: 10 },
        { keywordId: 'k1', bidValue: 20 },
      ],
      idempotencyKey: '',
      note: '',
    });
    expect(result.ok).toBe(false);
    expect(result.errors.join()).toContain('幂等键');
    expect(result.errors.join()).toContain('关键词重复');
  });

  it('批量否定不需要调价值，但需要幂等键', () => {
    const result = validateBulkAction({
      actionType: 'NEGATE',
      shopId: 'shop-1',
      targets: [{ keywordId: 'k1' }],
      idempotencyKey: 'bulk-1',
      note: '',
    });
    expect(result.ok).toBe(true);
  });

  it('幂等键每次生成都不一样', () => {
    expect(newIdempotencyKey('bulk')).not.toBe(newIdempotencyKey('bulk'));
    expect(newIdempotencyKey('bulk')).toContain('bulk-');
  });

  it('日期工具给的是 UTC 日', () => {
    expect(todayUtc()).toMatch(/^\d{4}-\d{2}-\d{2}$/);
    expect(daysAgoUtc(30)).toMatch(/^\d{4}-\d{2}-\d{2}$/);
  });
});
