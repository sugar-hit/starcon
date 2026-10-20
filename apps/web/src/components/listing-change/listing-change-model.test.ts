import { describe, expect, it } from 'vitest';
import {
  actionsBanner,
  changeTypeLabel,
  directionLabel,
  emptyComparisonMessage,
  emptyEventsMessage,
  formatAmount,
  formatCount,
  formatRate,
  formatSignedRate,
  formatTime,
  isCompetitorTarget,
  listingStatusLabel,
  opportunityStatusHint,
  opportunityTypeLabel,
  pageSummary,
  rankBasisLabel,
  rankChangeText,
  severityLabel,
  sourceBanner,
  targetKindLabel,
  unavailableDimensions,
  watchStatusLabel,
  type ListingChangeCapabilities,
  type ListingChangeComparison,
} from './listing-change-model';

/**
 * 4.5.2.2 上新与变更监控 前端模型的单测。
 *
 * 🔴 这些用例盯的是**最容易做丢的判断**：
 *
 * 1. **null 绝不渲染成 0%** —— 服务端在分母（上次价格）为 0 时给 null，
 *    那是「没有基准」，不是「价格没变」。
 * 2. **🔴 方向按维度翻译，不把 direction 直接翻成「上升 / 下降」** ——
 *    排名 INCREASED = 名次数字变大 = **竞品变差**，写成「上升」会把意思读反。
 * 3. **「参考数据」与「不提供抢占/调价动作」必须出现在页面上** —— 蓝图加粗句是硬要求。
 * 4. **「已阅」不等于「已采取动作」** —— 界面必须写明。
 * 5. **空态要分得清** —— 「还没配监控对象」「配了但没抓到」「筛选下没有」不是一回事。
 * 6. **未交付的维度只挑 available=false** —— 别的车道已交付的能力不能被误标。
 */
describe('4.5.2.2 上新与变更监控 · 前端模型', () => {
  describe('🔴 比率与金额：null 绝不渲染成 0', () => {
    it('formatRate 对 null / undefined / NaN 一律显示「—」，不是 0%', () => {
      expect(formatRate(null)).toBe('—');
      expect(formatRate(undefined)).toBe('—');
      expect(formatRate(Number.NaN)).toBe('—');
      expect(formatRate(0)).toBe('0.00%');
      expect(formatRate(0.2)).toBe('20.00%');
    });

    it('formatSignedRate 带正负号，null 仍是「—」', () => {
      expect(formatSignedRate(null)).toBe('—');
      expect(formatSignedRate(0.12)).toBe('+12.00%');
      expect(formatSignedRate(-0.05)).toBe('-5.00%');
      expect(formatSignedRate(0)).toBe('0.00%');
    });

    it('formatAmount 对 null 显示「—」，不是 0.00', () => {
      expect(formatAmount(null, 'USD')).toBe('—');
      expect(formatAmount(undefined)).toBe('—');
      expect(formatAmount(0, 'USD')).toBe('0.00 USD');
      expect(formatAmount(12.5, 'USD')).toBe('12.50 USD');
      expect(formatAmount(12.5)).toBe('12.50');
    });

    it('formatCount 对 null 显示「—」', () => {
      expect(formatCount(null)).toBe('—');
      expect(formatCount(0)).toBe('0');
      expect(formatCount(1234)).toBe('1,234');
    });

    it('formatTime 对空值 / 非法值显示「—」', () => {
      expect(formatTime(null)).toBe('—');
      expect(formatTime('')).toBe('—');
      expect(formatTime('not-a-date')).toBe('—');
      expect(formatTime('2026-10-01T10:30:00Z')).not.toBe('—');
    });
  });

  describe('🔴 方向按维度翻译：排名 INCREASED 是「竞品变差」', () => {
    it('价格的 INCREASED 是「涨价」', () => {
      expect(directionLabel('PRICE', 'INCREASED')).toBe('涨价');
      expect(directionLabel('PRICE', 'DECREASED')).toBe('降价');
    });

    it('🔴 排名的 INCREASED 是「名次下滑（竞品变差）」，不是「上升」', () => {
      expect(directionLabel('RANK', 'INCREASED')).toBe('名次下滑（竞品变差）');
      expect(directionLabel('RANK', 'DECREASED')).toBe('名次上升（竞品变好）');
      // 🔴 绝不能出现裸的「上升」/「下降」—— 名字越大 = 越差，方向会读反
      expect(directionLabel('RANK', 'INCREASED')).not.toBe('上升');
    });

    it('评论数的 INCREASED 是「评论数增加」', () => {
      expect(directionLabel('REVIEW_COUNT', 'INCREASED')).toBe('评论数增加');
      expect(directionLabel('REVIEW_COUNT', 'DECREASED')).toBe('评论数减少');
    });

    it('上下架只有「状态迁移」', () => {
      expect(directionLabel('LISTING_STATUS', 'CHANGED')).toBe('状态迁移');
    });
  });

  describe('🔴 排名变化文案', () => {
    it('名次变大 = 竞品变差', () => {
      expect(rankChangeText(15)).toBe('下滑 15 位（竞品变差）');
    });

    it('名次变小 = 竞品变好', () => {
      expect(rankChangeText(-5)).toBe('上升 5 位（竞品变好）');
    });

    it('🔴 没有基准时是「—（无基准）」，不是「持平」', () => {
      expect(rankChangeText(null)).toBe('—（无基准）');
      expect(rankChangeText(undefined)).toBe('—（无基准）');
    });

    it('持平才显示「持平」', () => {
      expect(rankChangeText(0)).toBe('持平');
    });
  });

  describe('枚举中文名', () => {
    it('变化维度', () => {
      expect(changeTypeLabel('LISTING_STATUS')).toBe('上下架');
      expect(changeTypeLabel('PRICE')).toBe('价格');
      expect(changeTypeLabel('RANK')).toBe('排名');
      expect(changeTypeLabel('REVIEW_COUNT')).toBe('评论数');
      expect(changeTypeLabel('UNKNOWN_TYPE')).toBe('UNKNOWN_TYPE');
    });

    it('上下架状态（null → 「—」）', () => {
      expect(listingStatusLabel('LISTED')).toBe('在售');
      expect(listingStatusLabel('OUT_OF_STOCK')).toBe('断货');
      expect(listingStatusLabel('DELISTED')).toBe('已下架');
      expect(listingStatusLabel('UNKNOWN')).toBe('未上报');
      expect(listingStatusLabel(null)).toBe('—');
    });

    it('目标性质与监控状态', () => {
      expect(targetKindLabel('COMPETITOR')).toBe('竞品');
      expect(targetKindLabel('OWN_ASIN')).toBe('自有 ASIN');
      expect(watchStatusLabel('ACTIVE')).toBe('监控中');
      expect(watchStatusLabel('PAUSED')).toBe('已暂停');
    });

    it('市场机会类型与严重度', () => {
      expect(opportunityTypeLabel('COMPETITOR_OUT_OF_STOCK')).toBe('竞品断货');
      expect(opportunityTypeLabel('COMPETITOR_PRICE_INCREASE')).toBe('竞品涨价');
      expect(opportunityTypeLabel('COMPETITOR_RANK_WORSENED')).toBe('竞品排名变差');
      expect(opportunityTypeLabel('COMPETITOR_LISTED')).toBe('竞品上新');
      expect(severityLabel('HIGH')).toBe('高');
    });

    it('排名口径', () => {
      expect(rankBasisLabel('BSR')).toBe('平台销量榜');
      expect(rankBasisLabel('CATEGORY')).toBe('类目榜');
      expect(rankBasisLabel('KEYWORD')).toBe('关键词下');
    });
  });

  describe('🔴 空态要分得清', () => {
    it('还没配监控对象', () => {
      expect(emptyEventsMessage(0, 0)).toContain('还没有监控任何 ASIN');
    });

    it('配了但没抓到数据', () => {
      const message = emptyEventsMessage(3, 0);
      expect(message).not.toContain('还没有监控任何 ASIN');
      expect(message).toContain('本模块不自己轮询平台');
    });

    it('有监控对象但这个筛选下没有', () => {
      expect(emptyEventsMessage(3, 5)).toBe('没有变更事件。');
    });
  });

  describe('🔴 参考数据与动作声明必须在页面上', () => {
    const caps = {
      dimensions: [
        { dimension: 'a', description: 'x', available: true, source: 's', ownerModule: '', impact: '' },
        {
          dimension: 'real_crawl_scheduler',
          description: 'y',
          available: false,
          source: '—',
          ownerModule: '02',
          impact: 'z',
        },
      ],
      targetKinds: [],
      listingStatuses: [],
      changeTypes: [],
      directions: [],
      opportunityTypes: [],
      severities: [],
      sources: [],
      ingestChannels: [],
      watchStatuses: [],
      opportunityStatuses: [],
      rankBases: [],
      ruleDefinitions: '',
      dataSource: 'PLATFORM_SCRAPED',
      sourceNote: '平台侧抓取观测值，仅供参考',
      actionsNote: '本系统不提供抢占 / 调价 / 铺货等动作',
      relationNote: '',
    } as unknown as ListingChangeCapabilities;

    it('sourceBanner 优先用服务端写明的来源标注', () => {
      expect(sourceBanner(caps)).toContain('仅供参考');
      expect(sourceBanner(null)).toContain('仅供参考');
    });

    it('actionsBanner 写明本模块不提供抢占 / 调价动作', () => {
      expect(actionsBanner(caps)).toContain('不提供抢占');
      expect(actionsBanner(null)).toContain('不提供抢占');
    });

    it('🔴 未交付维度只挑 available=false —— 已交付的能力不能被误标', () => {
      const missing = unavailableDimensions(caps);
      expect(missing).toHaveLength(1);
      expect(missing[0]?.dimension).toBe('real_crawl_scheduler');
      expect(missing[0]?.ownerModule).toBe('02');
    });
  });

  describe('分页摘要与比较空态', () => {
    it('pageSummary 用服务端给的 total', () => {
      expect(pageSummary({ page: 0, size: 20, total: 0 })).toBe('第 0-0 条 / 共 0 条');
      expect(pageSummary({ page: 0, size: 20, total: 45 })).toBe('第 1-20 条 / 共 45 条');
      expect(pageSummary({ page: 2, size: 20, total: 45 })).toBe('第 41-45 条 / 共 45 条');
    });

    it('emptyComparisonMessage 优先用服务端写明的 emptyReason', () => {
      const comparison = {
        emptyReason: '这个监控对象在所选区间内还没有观测数据',
      } as ListingChangeComparison;
      expect(emptyComparisonMessage(comparison)).toContain('还没有观测数据');
      expect(emptyComparisonMessage(null)).toBe('');
    });
  });

  describe('🔴 机会状态：已阅不等于已采取动作', () => {
    it('已阅只是内部状态', () => {
      expect(opportunityStatusHint('ACKNOWLEDGED')).toContain('不代表已采取任何动作');
      expect(opportunityStatusHint('OPEN')).toContain('动作在平台侧');
    });
  });

  describe('目标性质', () => {
    it('只有 COMPETITOR 算竞品', () => {
      expect(isCompetitorTarget('COMPETITOR')).toBe(true);
      expect(isCompetitorTarget('OWN_ASIN')).toBe(false);
    });
  });
});
