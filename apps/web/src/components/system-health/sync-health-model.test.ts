import { describe, expect, it } from 'vitest';
import type {
  SyncHealthBacklogSource,
  SyncHealthConnectionCard,
  SyncHealthPendingItem,
  SyncHealthRuleDoc,
} from '../../../../../packages/api-contracts/src/index';
import {
  EMPTY_FILTERS,
  GREEN,
  RED,
  YELLOW,
  backlogTotal,
  buildQuery,
  filterCards,
  formatDuration,
  formatFailureCount,
  formatStaleHours,
  formatThresholds,
  formatTimestamp,
  pendingHref,
  platformGapTone,
  rulesBySeverity,
  severityRank,
  sortBySeverity,
  sortPendingItems,
  summarize,
} from './sync-health-model';

/**
 * 1.5.1.1 同步与 API 健康的前端模型单测。
 *
 * <p>本模块最容易做错的三件事，逐条钉在这里：
 *
 * <ol>
 *   <li><b>前端不重算红黄绿</b>：严重度来自服务端，前端只做展示与**过滤** ——
 *       过滤不会改写结论。</li>
 *   <li><b>「读不到」与「没有」分开</b>：时间读不到渲染成「—」，
 *       不是纪元时间（1970-01-01 会让人以为数据坏了）。</li>
 *   <li><b>积压不是一个来源不明的数字</b>：合计 = 逐张表相加，
 *       少一张表 / 多一行「合计」都会算错。</li>
 * </ol>
 */

const CONNECTION_ID = '11111111-1111-1111-1111-111111111111';

function card(overrides: Partial<SyncHealthConnectionCard> = {}): SyncHealthConnectionCard {
  return {
    connectionId: CONNECTION_ID,
    platformCode: 'AMAZON',
    platformName: 'Amazon',
    authorizedAccount: 'acct-1',
    displayName: '北美店',
    status: 'ACTIVE',
    syncPaused: false,
    severity: GREEN,
    reasons: [],
    shops: [{ shopId: '22222222-2222-2222-2222-222222222222', shopCode: 'AMZ-01', shopName: '北美店' }],
    shopCount: 1,
    lastSyncAt: null,
    lastSyncAtSource: 'RUN_FINISHED',
    effectiveLastSyncAt: '2026-10-23T10:00:00Z',
    staleHours: 2,
    lastRunStatus: 'SUCCEEDED',
    lastRunTriggerSource: 'SCHEDULER',
    lastRunStartedAt: '2026-10-23T09:59:00Z',
    lastRunFinishedAt: '2026-10-23T10:00:00Z',
    lastRunDurationMs: 1000,
    failureCountWindow: 0,
    failureCountTotal: 0,
    rateLimitedCountWindow: 0,
    rateLimitedCountTotal: 0,
    openFailureCount: 0,
    failureByReason: [],
    ...overrides,
  };
}

describe('1.5.1.1 严重度', () => {
  it('红在前、黄其次、绿最后', () => {
    expect(severityRank(RED)).toBeLessThan(severityRank(YELLOW));
    expect(severityRank(YELLOW)).toBeLessThan(severityRank(GREEN));
  });

  it('排序真的按严重度排', () => {
    const cards = [
      card({ connectionId: 'a', severity: GREEN }),
      card({ connectionId: 'b', severity: RED }),
      card({ connectionId: 'c', severity: YELLOW }),
    ];
    expect(sortBySeverity(cards).map((c) => c.connectionId)).toEqual(['b', 'c', 'a']);
  });

  it('汇总的红黄绿条数与卡片一致', () => {
    const counts = summarize([
      card({ severity: RED }),
      card({ severity: YELLOW }),
      card({ severity: YELLOW }),
      card({ severity: GREEN }),
    ]);
    expect(counts).toEqual({ red: 1, yellow: 2, green: 1, total: 4 });
  });
});

describe('1.5.1.1 筛选', () => {
  const cards = [
    card({ connectionId: 'a', severity: RED, platformCode: 'AMAZON' }),
    card({
      connectionId: 'b',
      severity: YELLOW,
      platformCode: 'SHOPIFY',
      displayName: '欧洲店',
      authorizedAccount: 'acct-2',
      shops: [{ shopId: '33333333-3333-3333-3333-333333333333', shopCode: 'SHOP-02', shopName: '欧洲店' }],
    }),
  ];

  it('按严重度筛', () => {
    expect(filterCards(cards, { ...EMPTY_FILTERS, severity: RED }).map((c) => c.connectionId)).toEqual([
      'a',
    ]);
  });

  it('按平台筛', () => {
    expect(
      filterCards(cards, { ...EMPTY_FILTERS, platformCode: 'shopify' }).map((c) => c.connectionId),
    ).toEqual(['b']);
  });

  it('按关键字筛（连接名 / 平台账号 / 店铺名都算）', () => {
    expect(filterCards(cards, { ...EMPTY_FILTERS, keyword: '欧洲' })).toHaveLength(1);
    expect(filterCards(cards, { ...EMPTY_FILTERS, keyword: 'acct-1' })).toHaveLength(1);
    expect(filterCards(cards, { ...EMPTY_FILTERS, keyword: 'AMZ-01' })).toHaveLength(1);
    expect(filterCards(cards, { ...EMPTY_FILTERS, keyword: '不存在的关键字' })).toHaveLength(0);
  });

  it('空筛选等于不筛', () => {
    expect(filterCards(cards, EMPTY_FILTERS)).toHaveLength(2);
  });

  it('查询串带真分页参数', () => {
    const query = buildQuery({ ...EMPTY_FILTERS, platformCode: 'AMAZON' }, 2, 50);
    expect(query).toContain('platformCode=AMAZON');
    expect(query).toContain('page=2');
    expect(query).toContain('size=50');
  });
});

describe('1.5.1.1 读不到 ≠ 没有', () => {
  it('时间读不到渲染成「—」，不是纪元时间', () => {
    expect(formatTimestamp(null)).toBe('—');
    expect(formatTimestamp(undefined)).toBe('—');
    expect(formatTimestamp('not-a-date')).toBe('—');
    expect(formatTimestamp('2026-10-23T10:00:00Z')).not.toBe('—');
    expect(formatTimestamp('2026-10-23T10:00:00Z')).not.toContain('1970');
  });

  it('距今小时数读不到也是「—」', () => {
    expect(formatStaleHours(null)).toBe('—');
    expect(formatStaleHours(2)).toContain('2');
    expect(formatStaleHours(50)).toContain('2 天');
  });

  it('耗时读不到也是「—」', () => {
    expect(formatDuration(null)).toBe('—');
    expect(formatDuration(500)).toContain('500');
    expect(formatDuration(90_000)).toContain('1 分');
  });
});

describe('1.5.1.1 积压', () => {
  const sources: SyncHealthBacklogSource[] = [
    {
      table: 'outbox_event',
      label: '事件发件箱积压',
      pendingCondition: 'published_at IS NULL',
      pendingCount: 3,
      semantics: '已产生但还没发布',
    },
    {
      table: 'listing_content_writeback_task',
      label: '内容回写任务积压',
      pendingCondition: "state = 'PENDING'",
      pendingCount: 0,
      semantics: '排队未派发',
    },
  ];

  it('合计 = 逐张表相加（不多不少）', () => {
    expect(backlogTotal(sources)).toBe(3);
    expect(backlogTotal([])).toBe(0);
  });

  it('平台缺口按「尚未接入 / 还没接」分两种语气', () => {
    expect(
      platformGapTone({
        platformCode: 'WAYFAIR',
        platformName: 'Wayfair',
        supportStatus: 'PLANNED',
        state: 'NOT_INTEGRATED',
        message: '',
      }),
    ).toBe('neutral');
    expect(
      platformGapTone({
        platformCode: 'SHOPIFY',
        platformName: 'Shopify',
        supportStatus: 'AVAILABLE',
        state: 'NO_CONNECTION',
        message: '',
      }),
    ).toBe('warning');
  });
});

describe('1.5.1.1 需处理项', () => {
  const item = (overrides: Partial<SyncHealthPendingItem> = {}): SyncHealthPendingItem => ({
    kind: 'REAUTHORIZE',
    severity: RED,
    title: '重新授权',
    detail: '连接状态是 EXPIRED',
    targetModule: '2.2.1.2',
    targetRoute: '/channels',
    targetHint: '渠道与店铺 → 授权连接',
    targetAvailable: true,
    targetNote: '',
    ...overrides,
  });

  it('有真实路由才给跳转，没有就返回 null（不渲染死链）', () => {
    expect(pendingHref(item())).toBe('/channels');
    expect(pendingHref(item({ targetAvailable: false }))).toBeNull();
    expect(pendingHref(item({ targetRoute: null }))).toBeNull();
  });

  it('排序红在前', () => {
    const items = [item({ severity: YELLOW }), item({ severity: RED })];
    expect(sortPendingItems(items)[0]?.severity).toBe(RED);
  });
});

describe('1.5.1.1 规则表', () => {
  const rules: SyncHealthRuleDoc[] = [
    { code: 'CONNECTION_INVALID', severity: RED, condition: 'x', reason: 'y', source: 'z' },
    { code: 'CONNECTION_EXPIRING', severity: YELLOW, condition: 'x', reason: 'y', source: 'z' },
    { code: 'ALL_CLEAR', severity: GREEN, condition: 'x', reason: 'y', source: 'z' },
  ];

  it('按严重度分组，且用的是服务端下发的表', () => {
    const groups = rulesBySeverity(rules);
    expect(groups.map((g) => g.severity)).toEqual([RED, YELLOW, GREEN]);
    expect(groups[0]?.rules[0]?.code).toBe('CONNECTION_INVALID');
  });

  it('阈值的人话把三个数字都说清', () => {
    const text = formatThresholds({
      staleSyncWarnHours: 24,
      staleSyncCriticalHours: 72,
      failureWindowHours: 24,
    });
    expect(text).toContain('24');
    expect(text).toContain('72');
  });

  it('失败计数的人话带窗口与累计两个口径', () => {
    const text = formatFailureCount({ reason: 'RATE_LIMITED', windowCount: 3, totalCount: 9 });
    expect(text).toContain('命中限流');
    expect(text).toContain('3');
    expect(text).toContain('9');
  });
});
