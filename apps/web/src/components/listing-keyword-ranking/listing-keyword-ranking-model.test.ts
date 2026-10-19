import { describe, expect, it } from 'vitest';
import {
  accuracyBanner,
  accuracyLabel,
  alertTypeHint,
  alertTypeLabel,
  crawledAtLabel,
  emptyAlertsMessage,
  emptyDetailMessage,
  emptyKeywordsMessage,
  formatAverageRank,
  formatRank,
  pageSummary,
  rankChangeDirection,
  rankChangeText,
  rankDefinitionBanner,
  siteLabel,
  thresholdLabel,
  trendSeries,
  unavailableDimensions,
  ALERT_TYPE_OPTIONS,
  type ListingKeywordRankingCapabilities,
  type ListingKeywordRankingDetail,
  type ListingKeywordRankingTrendPoint,
} from './listing-keyword-ranking-model';

/**
 * 4.5.1.2 关键词排名 前端模型单测。
 *
 * 🔴 这里钉住的都是**页面上最容易做丢**的几件事：
 *   1. null 名次**绝不**渲染成 0（那是「排第一」或「完全没排名」的误读）
 *   2. null 变化量**绝不**渲染成「0 位」（那是「没有可比的上次」）
 *   3. 名次数字变大 = **变差**（从第 5 名掉到第 20 名是退步，不是进步）
 *   4. **抓取时间与不准确窗口**必须出现在名次的标注里
 *   5. 阈值没配 = 「该维度不预警」，不是「阈值为 0」
 *   6. 趋势没有数据时是**空数组**，不是平线
 *   7. 未交付的下游显式列出来，不假装具备
 */

const caps = (available: boolean): ListingKeywordRankingCapabilities => ({
  dimensions: [
    {
      dimension: 'keyword_set',
      description: '关键词集合',
      available,
      source: 'listing_keyword',
      ownerModule: available ? '' : '02 渠道与店铺',
      impact: available ? '' : '不交付的影响',
    },
    {
      dimension: 'workbench_alert_delivery',
      description: '1 工作台预警推送',
      available: false,
      source: '—',
      ownerModule: '1 工作台与门户（整卷未交付）',
      impact: '本模块只把预警记录下来，不发通知',
    },
  ],
  sources: ['PLATFORM'],
  ingestChannels: ['API'],
  alertTypes: ['NATURAL_BELOW_THRESHOLD'],
  granularities: ['DAY'],
  accuracyNote: '🔴 排名依赖抓取频率与平台限制：每个数据点都带抓取时间与不准确窗口。',
  rankDefinitions: '名次是位置；平台没报名次时是 null，不是 0。',
  relationNote: '上游 = 抓取任务（02 通道）。',
});

const detail = (hasData: boolean, emptyReason: string): ListingKeywordRankingDetail => ({
  keywordId: 'k1',
  listingId: 'l1',
  externalItemId: 'E-1',
  title: '商品',
  shopCode: 'SHOP-1',
  platformCode: 'AMAZON',
  siteCode: 'US',
  keyword: 'keyword',
  naturalRankThreshold: 10,
  adRankThreshold: null,
  rankDropThreshold: null,
  active: true,
  note: '',
  latestNaturalRank: null,
  latestAdRank: null,
  naturalRankChange: null,
  adRankChange: null,
  latestCrawledAt: null,
  latestAccuracyWindowMinutes: null,
  accuracyNote: 'note',
  crawlCount: 0,
  snapshotCount: 0,
  openAlertCount: 0,
  totalAlertCount: 0,
  hasData,
  emptyReason,
  lastCrawledAt: null,
});

const point = (over: Partial<ListingKeywordRankingTrendPoint> = {}): ListingKeywordRankingTrendPoint => ({
  bucket: '2026-10-01',
  lastNaturalRank: 5,
  lastAdRank: null,
  bestNaturalRank: 3,
  worstNaturalRank: 9,
  bestAdRank: null,
  worstAdRank: null,
  sampleCount: 2,
  lastCrawledAt: '2026-10-01T03:00:00Z',
  ...over,
});

describe('名次格式化', () => {
  it('🔴 null 名次显示成「—」，绝不显示成 0', () => {
    expect(formatRank(null)).toBe('—');
    expect(formatRank(undefined)).toBe('—');
    expect(formatRank(Number.NaN)).toBe('—');
  });

  it('名次按整数显示（带千分位），小数保留两位', () => {
    expect(formatRank(5)).toBe('5');
    expect(formatRank(1200)).toBe('1,200');
    expect(formatRank(12.345)).toBe('12.35');
  });

  it('🔴 平均名次 null 显示成「—」而不是 0（没有样本不是平均第 0 名）', () => {
    expect(formatAverageRank(null)).toBe('—');
    expect(formatAverageRank(undefined)).toBe('—');
    expect(formatAverageRank(20)).toBe('20.00');
    expect(formatAverageRank(19.5)).toBe('19.50');
  });
});

describe('名次变化', () => {
  it('🔴 名次数字变大 = 变差（退步），变小 = 变好', () => {
    expect(rankChangeDirection(5)).toBe('down');
    expect(rankChangeDirection(-5)).toBe('up');
    expect(rankChangeDirection(0)).toBe('flat');
  });

  it('🔴 null 变化量是「unknown」，不是「持平」', () => {
    expect(rankChangeDirection(null)).toBe('unknown');
    expect(rankChangeDirection(undefined)).toBe('unknown');
    expect(rankChangeText(null)).toBe('—');
  });

  it('🔴 变化文案区分「进了 / 退了 / 持平」，null 不显示 0 位', () => {
    expect(rankChangeText(3)).toBe('↓ 退了 3 位');
    expect(rankChangeText(-2)).toBe('↑ 进了 2 位');
    expect(rankChangeText(0)).toBe('持平（0 位）');
    expect(rankChangeText(null)).not.toContain('0 位');
  });
});

describe('🔴 抓取时间与不准确窗口的标注', () => {
  it('名次必须带「抓取于 …」与「±N 分钟偏差」', () => {
    const label = accuracyLabel('2026-10-01T03:00:00Z', 45);
    expect(label).toContain('抓取于');
    expect(label).toContain('±45 分钟偏差');
  });

  it('还没抓过时显示「尚未抓取」，不显示 0 分钟偏差', () => {
    expect(accuracyLabel(null, 45)).toBe('尚未抓取');
    expect(accuracyLabel(null, null)).toBe('尚未抓取');
  });

  it('抓取时间单独显示时也不编造窗口', () => {
    expect(crawledAtLabel(null)).toBe('尚未抓取');
    expect(crawledAtLabel('2026-10-01T03:00:00Z')).toContain('2026');
  });

  it('说明横幅带抓取时间与不准确窗口的硬要求', () => {
    const banner = accuracyBanner(caps(true));
    expect(banner).toContain('抓取时间');
    expect(banner).toContain('不准确窗口');
    // 没有能力表时也有兜底文案，不能是空串
    expect(accuracyBanner(null)).toContain('抓取时间');
  });

  it('口径说明里点名「不是 0」', () => {
    expect(rankDefinitionBanner(caps(true))).toContain('null');
    expect(rankDefinitionBanner(null)).toContain('null');
  });
});

describe('阈值与站点', () => {
  it('🔴 阈值没配 = 「未配置（不预警）」，不是 0', () => {
    expect(thresholdLabel(null)).toBe('未配置（不预警）');
    expect(thresholdLabel(undefined)).toBe('未配置（不预警）');
    expect(thresholdLabel(10)).toBe('前 10 名');
    expect(thresholdLabel(0)).not.toBe('未配置（不预警）');
  });

  it('没有站点时显示「—」', () => {
    expect(siteLabel('')).toBe('—');
    expect(siteLabel(null)).toBe('—');
    expect(siteLabel('US')).toBe('US');
  });
});

describe('预警类型', () => {
  it('四类预警都有中文名与含义', () => {
    expect(ALERT_TYPE_OPTIONS).toHaveLength(4);
    for (const option of ALERT_TYPE_OPTIONS) {
      expect(alertTypeLabel(option.value)).not.toBe(option.value);
      expect(alertTypeHint(option.value)).not.toBe('');
    }
  });

  it('未知类型原样返回，不编造一个中文名', () => {
    expect(alertTypeLabel('WHATEVER')).toBe('WHATEVER');
    expect(alertTypeHint('WHATEVER')).toBe('');
  });
});

describe('空态文案', () => {
  it('🔴 「没关键词」与「筛选条件下没关键词」文案不同', () => {
    expect(emptyKeywordsMessage(0)).not.toBe(emptyKeywordsMessage(12));
    expect(emptyKeywordsMessage(0)).toContain('还没有');
    expect(emptyKeywordsMessage(12)).toContain('筛选条件');
  });

  it('🔴 明细空态优先用服务端写明的 emptyReason', () => {
    expect(emptyDetailMessage(detail(false, '服务端写的理由'))).toBe('服务端写的理由');
    expect(emptyDetailMessage(detail(true, ''))).toContain('还没有排名数据');
    expect(emptyDetailMessage(null)).toBe('');
  });

  it('🔴 预警空态区分「没有预警」与「都确认过了」', () => {
    expect(emptyAlertsMessage(0)).toContain('没有未确认的预警');
    expect(emptyAlertsMessage(3)).not.toBe(emptyAlertsMessage(0));
  });
});

describe('趋势', () => {
  it('🔴 没有数据时是空数组（不画平线）', () => {
    expect(trendSeries([], 'lastNaturalRank')).toEqual([]);
  });

  it('🔴 平台没报名次的桶保留为 null 断点，而不是补 0', () => {
    const series = trendSeries([point({ lastAdRank: null })], 'lastAdRank');
    expect(series).toEqual([{ bucket: '2026-10-01', value: null }]);
  });

  it('正常桶带出数值', () => {
    const series = trendSeries([point()], 'lastNaturalRank');
    expect(series[0]).toEqual({ bucket: '2026-10-01', value: 5 });
  });
});

describe('能力声明与分页', () => {
  it('🔴 未交付维度只挑 available=false 的那些', () => {
    const missing = unavailableDimensions(caps(true));
    expect(missing).toHaveLength(1);
    // 🔴 noUncheckedIndexedAccess：下标访问可能是 undefined，这里显式断言存在
    const first = missing[0];
    expect(first).toBeDefined();
    expect(first?.dimension).toBe('workbench_alert_delivery');
    expect(first?.ownerModule).not.toBe('');
    expect(first?.impact).not.toBe('');
  });

  it('没有能力表时未交付维度是空数组', () => {
    expect(unavailableDimensions(null)).toEqual([]);
  });

  it('真分页的页码摘要来自服务端 count', () => {
    expect(pageSummary({ page: 0, size: 20, total: 0 })).toBe('第 0-0 条 / 共 0 条');
    expect(pageSummary({ page: 0, size: 20, total: 5 })).toBe('第 1-5 条 / 共 5 条');
    expect(pageSummary({ page: 1, size: 20, total: 45 })).toBe('第 21-40 条 / 共 45 条');
  });
});
