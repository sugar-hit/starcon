import { describe, expect, it } from 'vitest';
import {
  bulkModeLabel,
  bulkStateLabel,
  bulkStateTone,
  canResume,
  capturedAtLabel,
  emptyAllowlistMessage,
  emptyResultsMessage,
  emptyTasksMessage,
  originLabel,
  policySummary,
  precheckCategoryUrl,
  precheckKeyword,
  precheckPageRange,
  priceLabel,
  rateLimitNote,
  resultStateLabel,
  resultStateTone,
  sourceLabel,
  taskReason,
  taskSummaryLabel,
  unavailableDimensions,
  type BulkCollectTaskRow,
  type ListingBulkCollectCapabilities,
} from './listing-bulk-collect-model';

/**
 * 4.4.1.2 批量/插件采集 前端纯函数单测。
 *
 * <p>这些函数是页面上「最容易做丢的那几件事」的可执行版本：
 * 限频原因不被吞、去重可见、只产生草稿、未交付段明账、空态文案、售价不是我方售价。
 */

function task(overrides: Partial<BulkCollectTaskRow> = {}): BulkCollectTaskRow {
  return {
    taskId: '11111111-1111-1111-1111-111111111111',
    mode: 'CATEGORY_PAGE',
    sourceCode: 'ALI_1688',
    sourceName: '1688 平台',
    categoryUrl: 'https://s.1688.com/category/1',
    keyword: '',
    pageFrom: 1,
    pageTo: 2,
    state: 'SUCCEEDED',
    errorMessage: '',
    totalPages: 2,
    discoveredCount: 4,
    fetchedCount: 4,
    skippedDuplicateCount: 0,
    failedCount: 0,
    skippedRateLimitedCount: 0,
    nextPage: 3,
    startedAt: '2026-10-20T01:00:00Z',
    finishedAt: '2026-10-20T01:00:05Z',
    createdAt: '2026-10-20T01:00:00Z',
    ...overrides,
  };
}

function caps(overrides: Partial<ListingBulkCollectCapabilities> = {}): ListingBulkCollectCapabilities {
  return {
    dimensions: [
      { dimension: 'category_page_collect', description: '分类页批量采集', available: true, source: 't', ownerModule: '', impact: '' },
      { dimension: 'collection_box', description: '采集箱', available: false, source: '—', ownerModule: '4.4.1.3 采集箱', impact: '本模块只负责产出草稿' },
    ],
    sources: [
      {
        code: 'ALI_1688',
        name: '1688 平台',
        host: '1688.com',
        kind: 'PLATFORM',
        minIntervalSeconds: 30,
        bulkMinIntervalSeconds: 2,
        searchUrlTemplate: 'https://s.1688.com/selloffer/offer_search.htm?keywords={keyword}&beginPage={page}',
        categoryPageParam: 'page',
        pageOrigin: 1,
      },
    ],
    policy: {
      code: 'DEFAULT',
      maxConcurrentTasks: 2,
      minTaskStartIntervalSeconds: 2,
      maxPagesPerTask: 10,
      maxItemsPerTask: 100,
      maxItemsPerReport: 100,
      maxTaskSeconds: 60,
    },
    modes: ['CATEGORY_PAGE', 'KEYWORD', 'PLUGIN'],
    states: ['PENDING', 'RUNNING', 'SUCCEEDED', 'PARTIAL', 'FAILED', 'RATE_LIMITED'],
    resultStates: ['FETCHED', 'SKIPPED_DUPLICATE', 'FAILED', 'SKIPPED_RATE_LIMITED'],
    draftOnly: true,
    draftNote: '🔴 批量采集**只产生草稿**',
    deduplicated: true,
    dedupNote: '🔴 同一个商品不会被抓两遍',
    rateLimited: true,
    rateLimitNote: '🔴 批量抓取有频率限制',
    upstreamNote: '上游 = 4.4.1.1 链接采集',
    ...overrides,
  };
}

describe('4.4.1.2 输入预检（只挡明显不合法的输入，不代替服务端判定）', () => {
  it('分类页 URL 必须非空且是 http(s)', () => {
    expect(precheckCategoryUrl('')).toBe('请填写分类页 URL');
    expect(precheckCategoryUrl('   ')).toBe('请填写分类页 URL');
    expect(precheckCategoryUrl('s.1688.com/category')).toBe(
      '分类页 URL 必须以 http:// 或 https:// 开头',
    );
    expect(precheckCategoryUrl('ht!tp://x')).toBe(
      '分类页 URL 必须以 http:// 或 https:// 开头',
    );
    expect(precheckCategoryUrl('http://')).toBe('分类页 URL 格式不正确');
    expect(precheckCategoryUrl('https://s.1688.com/category/1')).toBeNull();
  });

  it('关键词必须非空且不太长', () => {
    expect(precheckKeyword('')).toBe('请填写关键词');
    expect(precheckKeyword('蓝牙耳机')).toBeNull();
    expect(precheckKeyword('x'.repeat(201))).toBe('关键词太长了（最多 200 个字符）');
  });

  it('页码范围必须自洽', () => {
    expect(precheckPageRange(1, 3)).toBeNull();
    expect(precheckPageRange(0, 3)).toBe('起始页从 1 起');
    expect(precheckPageRange(3, 1)).toBe('结束页不能小于起始页');
    expect(precheckPageRange(1.5, 3)).toBe('页码必须是整数');
  });
});

describe('4.4.1.2 文案映射', () => {
  it('入口模式 / 任务状态 / 结果状态都有中文文案', () => {
    expect(bulkModeLabel('CATEGORY_PAGE')).toBe('分类页批量');
    expect(bulkModeLabel('KEYWORD')).toBe('关键词批量');
    expect(bulkModeLabel('PLUGIN')).toBe('浏览器插件上报');

    expect(bulkStateLabel('PENDING')).toBe('待运行');
    expect(bulkStateLabel('RUNNING')).toBe('采集中');
    expect(bulkStateLabel('SUCCEEDED')).toBe('已采完');
    expect(bulkStateLabel('PARTIAL')).toBe('部分采完');
    expect(bulkStateLabel('FAILED')).toBe('失败');
    expect(bulkStateLabel('RATE_LIMITED')).toBe('被限频打断');

    expect(resultStateLabel('FETCHED')).toBe('已落成草稿');
    expect(resultStateLabel('SKIPPED_DUPLICATE')).toBe('已存在，跳过');
    expect(resultStateLabel('FAILED')).toBe('抓取失败');
    expect(resultStateLabel('SKIPPED_RATE_LIMITED')).toBe('被限频跳过');

    expect(originLabel('BULK')).toBe('批量链路');
    expect(originLabel('PLUGIN')).toBe('插件上报');
  });

  it('未知值原样返回，不吞掉', () => {
    expect(bulkModeLabel('WHAT')).toBe('WHAT');
    expect(bulkStateLabel('WHAT')).toBe('WHAT');
    expect(resultStateLabel('WHAT')).toBe('WHAT');
  });

  it('状态色调：成功是绿色，失败是红色，限频是警告色', () => {
    expect(bulkStateTone('SUCCEEDED')).toBe('success');
    expect(bulkStateTone('FAILED')).toBe('danger');
    expect(bulkStateTone('RATE_LIMITED')).toBe('warning');
    expect(bulkStateTone('PARTIAL')).toBe('warning');
    expect(bulkStateTone('RUNNING')).toBe('info');
    expect(resultStateTone('FETCHED')).toBe('success');
    expect(resultStateTone('SKIPPED_DUPLICATE')).toBe('warning');
    expect(resultStateTone('FAILED')).toBe('danger');
  });
});

describe('4.4.1.2 限频原因不被吞', () => {
  it('被限频打断时给出服务端原文', () => {
    const limited = task({
      state: 'RATE_LIMITED',
      errorMessage: '🔴 抓取过于频繁：还需要等 12 秒',
    });
    expect(rateLimitNote(limited)).toBe('🔴 抓取过于频繁：还需要等 12 秒');
  });

  it('被限频打断但服务端没给原因时也给得出话', () => {
    const limited = task({ state: 'RATE_LIMITED', errorMessage: '' });
    expect(rateLimitNote(limited)).toContain('继续采集');
  });

  it('没被限频时不显示限频提示', () => {
    expect(rateLimitNote(task())).toBe('');
    expect(rateLimitNote(null)).toBe('');
  });

  it('限频策略的文案把四个阈值都说出来', () => {
    const text = policySummary(caps().policy);
    expect(text).toContain('同时最多 2 个批量任务');
    expect(text).toContain('两次发起至少间隔 2 秒');
    expect(text).toContain('单任务最多 10 页');
    expect(text).toContain('单次运行预算 60 秒');
    expect(policySummary(null)).toBe('');
  });
});

describe('4.4.1.2 继续采集', () => {
  it('被限频打断 / 部分采完 / 失败 都能接着采', () => {
    expect(canResume(task({ state: 'RATE_LIMITED' }))).toBe(true);
    expect(canResume(task({ state: 'PARTIAL' }))).toBe(true);
    expect(canResume(task({ state: 'FAILED' }))).toBe(true);
  });

  it('已采完的不能再跑', () => {
    expect(canResume(task({ state: 'SUCCEEDED' }))).toBe(false);
    expect(canResume(null)).toBe(false);
  });
});

describe('4.4.1.2 统计与原因', () => {
  it('统计文案把四个数字都说出来', () => {
    const text = taskSummaryLabel(
      task({ discoveredCount: 10, fetchedCount: 6, skippedDuplicateCount: 3, failedCount: 1 }),
    );
    expect(text).toContain('发现 10 条');
    expect(text).toContain('落成草稿 6 条');
    expect(text).toContain('跳过 3 条');
    expect(text).toContain('失败 1 条');
  });

  it('成功态不显示失败原因', () => {
    expect(taskReason(task())).toBe('');
    expect(taskReason(task({ state: 'FAILED', errorMessage: '页面抓取失败' }))).toBe('页面抓取失败');
  });
});

describe('4.4.1.2 金额：外部标价不是我方售价', () => {
  it('有价显示币种，没价说"未抓到"', () => {
    expect(priceLabel(88.8, 'CNY')).toBe('88.8 CNY');
    expect(priceLabel(null, 'CNY')).toBe('未抓到');
    expect(priceLabel(undefined, undefined)).toBe('未抓到');
    expect(priceLabel(88.8, null)).toBe('88.8');
  });
});

describe('4.4.1.2 时间与平台', () => {
  it('抓取时刻格式化，非法值原样返回', () => {
    expect(capturedAtLabel('2026-10-20T01:02:03Z')).toMatch(/^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}$/);
    expect(capturedAtLabel('not-a-date')).toBe('not-a-date');
    expect(capturedAtLabel(null)).toBe('—');
  });

  it('平台文案带上域名（用户要知道能采哪儿）', () => {
    expect(sourceLabel(caps().sources[0])).toBe('1688 平台（1688.com）');
    expect(sourceLabel(null)).toBe('');
  });
});

describe('4.4.1.2 未交付段明账与空态', () => {
  it('未交付的维度被单独挑出来（采集箱不能画成已完成）', () => {
    const list = unavailableDimensions(caps());
    expect(list).toHaveLength(1);
    expect(list[0]?.dimension).toBe('collection_box');
    expect(list[0]?.ownerModule).toContain('4.4.1.3');
    expect(unavailableDimensions(null)).toEqual([]);
  });

  it('空态文案说清下一步怎么做', () => {
    expect(emptyTasksMessage()).toContain('分类页 URL');
    expect(emptyTasksMessage()).toContain('关键词');
  });

  it('白名单为空时明确提示运维', () => {
    expect(emptyAllowlistMessage(caps())).toBe('');
    expect(emptyAllowlistMessage(caps({ sources: [] }))).toContain('白名单是空的');
    expect(emptyAllowlistMessage(null)).toBe('');
  });

  it('结果空态按任务状态给不同的说法', () => {
    expect(emptyResultsMessage(task({ state: 'PENDING' }))).toContain('还没跑');
    expect(emptyResultsMessage(task({ discoveredCount: 0 }))).toContain('没有发现任何商品链接');
    expect(emptyResultsMessage(task())).toContain('还没有结果');
    expect(emptyResultsMessage(null)).toBe('');
  });
});
