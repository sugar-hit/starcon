import { describe, expect, it } from 'vitest';
import {
  allowedHosts,
  attemptLabel,
  canRetry,
  capturedAtLabel,
  collectStateLabel,
  collectStateTone,
  COLLECT_STATES,
  deduplicatedNote,
  emptyAllowlistMessage,
  emptyCollectsMessage,
  failureReason,
  keyValueMap,
  keyValueRows,
  precheckUrl,
  sourceKindLabel,
  sourcePriceLabel,
  unavailableDimensions,
  type CollectListingResult,
  type ListingCollectCapabilities,
  type ListingCollectTaskRow,
} from './listing-collect-model';

/**
 * 4.4.1.1 链接采集 前端纯函数单测。
 *
 * <p>这些函数是页面上"最容易做丢的那几件事"的可执行版本：
 * 白名单外的明确拒绝、去重可见、失败原因不被吞、只产生草稿、图片只记 URL、售价不是我方售价。
 */

function task(overrides: Partial<ListingCollectTaskRow> = {}): ListingCollectTaskRow {
  return {
    taskId: '11111111-1111-1111-1111-111111111111',
    sourceUrl: 'https://detail.1688.com/offer/1.html',
    normalizedUrl: 'https://detail.1688.com/offer/1.html',
    finalUrl: 'https://detail.1688.com/offer/1.html',
    host: 'detail.1688.com',
    sourceCode: 'ALI_1688',
    sourceName: '1688 平台',
    state: 'SUCCEEDED',
    errorMessage: '',
    httpStatus: 200,
    attemptNo: 1,
    draftId: '22222222-2222-2222-2222-222222222222',
    capturedAt: '2026-10-20T04:00:00Z',
    startedAt: '2026-10-20T04:00:00Z',
    finishedAt: '2026-10-20T04:00:01Z',
    createdAt: '2026-10-20T04:00:01Z',
    ...overrides,
  };
}

function caps(overrides: Partial<ListingCollectCapabilities> = {}): ListingCollectCapabilities {
  return {
    dimensions: [
      {
        dimension: 'source_allowlist',
        description: '平台白名单',
        available: true,
        source: 'listing_collect_source',
        ownerModule: '',
        impact: '',
      },
      {
        dimension: 'media_library',
        description: '图片下载入媒体库',
        available: false,
        source: '—',
        ownerModule: '03 商品主数据（catalog）媒体库',
        impact: '只记录图片 URL，不下载二进制、不写媒体表。',
      },
    ],
    sources: [
      {
        code: 'ALI_1688',
        name: '1688 平台',
        host: '1688.com',
        kind: 'PLATFORM',
        minIntervalSeconds: 30,
      },
      {
        code: 'MADE_IN_CHINA',
        name: '中国制造网（供应商站）',
        host: 'made-in-china.com',
        kind: 'SUPPLIER',
        minIntervalSeconds: 60,
      },
    ],
    states: ['SUCCEEDED', 'FAILED'],
    draftOnly: true,
    draftNote: '采集只产生草稿',
    mediaDownloaded: false,
    mediaNote: '只记录图片 URL',
    deduplicated: true,
    dedupNote: '同 URL 不重复抓',
    rateLimited: true,
    rateLimitNote: '同 host 限频',
    upstreamNote: '上游 = 外部商品页面',
    ...overrides,
  };
}

describe('precheckUrl', () => {
  it('空链接 / 非 http(s) / 缺主机名都被挡下', () => {
    expect(precheckUrl('')).toBe('请粘贴商品链接');
    expect(precheckUrl('   ')).toBe('请粘贴商品链接');
    expect(precheckUrl('detail.1688.com/offer/1')).toBe('链接必须以 http:// 或 https:// 开头');
    expect(precheckUrl('ftp://detail.1688.com/x')).toBe('链接必须以 http:// 或 https:// 开头');
    expect(precheckUrl('http://')).toBe('链接格式不正确');
  });

  it('正常的 http / https 链接放行', () => {
    expect(precheckUrl('https://detail.1688.com/offer/1.html')).toBeNull();
    expect(precheckUrl('http://detail.1688.com/offer/1.html')).toBeNull();
    expect(precheckUrl('  https://detail.1688.com/offer/1.html  ')).toBeNull();
  });
});

describe('抓取状态', () => {
  it('状态取值域与后端一致', () => {
    expect(COLLECT_STATES).toEqual(['SUCCEEDED', 'FAILED']);
  });

  it('成功 / 失败各有各的文案与色调', () => {
    expect(collectStateLabel('SUCCEEDED')).toBe('采集成功');
    expect(collectStateLabel('FAILED')).toBe('采集失败');
    expect(collectStateTone('SUCCEEDED')).toBe('success');
    expect(collectStateTone('FAILED')).toBe('warning');
  });
});

describe('failureReason —— 失败原因不被吞掉', () => {
  it('失败时原样返回服务端的失败原因', () => {
    const reason = failureReason(
      task({ state: 'FAILED', errorMessage: '外部站点返回 HTTP 404（来源：detail.1688.com）' }),
    );
    expect(reason).toContain('404');
    expect(reason).toContain('detail.1688.com');
  });

  it('成功时没有失败原因', () => {
    expect(failureReason(task())).toBe('');
  });

  it('服务端没给原因时也不编一个', () => {
    expect(failureReason(task({ state: 'FAILED', errorMessage: '' }))).toBe(
      '采集失败（服务端未给出原因）',
    );
  });

  it('没有任务就没有失败原因', () => {
    expect(failureReason(null)).toBe('');
  });
});

describe('canRetry —— 失败可重试', () => {
  it('失败的可以重试，成功的不可重试（重试 = 再发同一个 URL）', () => {
    expect(canRetry(task({ state: 'FAILED' }))).toBe(true);
    expect(canRetry(task({ state: 'SUCCEEDED' }))).toBe(false);
    expect(canRetry(null)).toBe(false);
  });
});

describe('deduplicatedNote —— 去重要看得见', () => {
  it('命中去重时说明没有再次请求来源站点', () => {
    const result = {
      task: task(),
      draft: null,
      deduplicated: true,
    } as unknown as CollectListingResult;
    const note = deduplicatedNote(result);
    expect(note).toContain('已经采过');
    expect(note).toContain('没有再次请求来源站点');
  });

  it('没命中去重时不显示', () => {
    const result = {
      task: task(),
      draft: null,
      deduplicated: false,
    } as unknown as CollectListingResult;
    expect(deduplicatedNote(result)).toBe('');
    expect(deduplicatedNote(null)).toBe('');
  });
});

describe('capturedAtLabel —— 抓取时刻', () => {
  it('格式化成可读时间', () => {
    expect(capturedAtLabel('2026-10-20T04:05:06Z')).toMatch(/^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}$/);
  });

  it('空值与非法值都如实显示', () => {
    expect(capturedAtLabel(null)).toBe('—');
    expect(capturedAtLabel('not-a-date')).toBe('not-a-date');
  });
});

describe('键值项（规格 / 属性）', () => {
  const items = [
    { name: '材质', value: '纯棉' },
    { name: '尺码', value: 'M' },
  ];

  it('转成 Map 便于查', () => {
    expect(keyValueMap(items)).toEqual({ 材质: '纯棉', 尺码: 'M' });
  });

  it('渲染行过滤掉空键与空值', () => {
    expect(
      keyValueRows([
        { name: '材质', value: '纯棉' },
        { name: '', value: 'x' },
        { name: '空值', value: '' },
      ]),
    ).toEqual([{ name: '材质', value: '纯棉' }]);
  });

  it('空输入不炸', () => {
    expect(keyValueMap(null)).toEqual({});
    expect(keyValueRows(undefined)).toEqual([]);
  });
});

describe('sourcePriceLabel —— 外部标价不是我方售价', () => {
  it('带币种展示', () => {
    expect(sourcePriceLabel(88.8, 'CNY')).toBe('88.8 CNY');
  });

  it('没抓到就明说未抓到（不是 0）', () => {
    expect(sourcePriceLabel(null, 'CNY')).toBe('未抓到');
    expect(sourcePriceLabel(undefined, undefined)).toBe('未抓到');
  });

  it('没币种时只显示金额', () => {
    expect(sourcePriceLabel(12.5, '')).toBe('12.5');
  });
});

describe('能力表', () => {
  it('未交付的维度被挑出来（媒体库 / 采集箱 / 批量采集 / 认领 / 售价）', () => {
    const unavailable = unavailableDimensions(caps());
    expect(unavailable.map((d) => d.dimension)).toEqual(['media_library']);
    expect(unavailable[0]?.ownerModule).toContain('catalog');
    expect(unavailable[0]?.impact).toContain('只记录图片 URL');
  });

  it('白名单里的域名可读', () => {
    expect(allowedHosts(caps())).toEqual(['1688.com', 'made-in-china.com']);
  });

  it('白名单为空时给出明确提示（不是空白页）', () => {
    expect(emptyAllowlistMessage(caps({ sources: [] }))).toContain('白名单是空的');
    expect(emptyAllowlistMessage(caps())).toBe('');
    expect(emptyAllowlistMessage(null)).toBe('');
  });

  it('平台类别的文案', () => {
    expect(sourceKindLabel('PLATFORM')).toBe('平台');
    expect(sourceKindLabel('SUPPLIER')).toBe('供应商站');
    expect(sourceKindLabel('PEER')).toBe('同行站');
  });
});

describe('空态与尝试次数', () => {
  it('空态文案说清"还没采过"与"怎么采"', () => {
    expect(emptyCollectsMessage()).toContain('还没有采集记录');
    expect(emptyCollectsMessage()).toContain('白名单');
  });

  it('尝试次数的文案', () => {
    expect(attemptLabel(1)).toBe('首次采集');
    expect(attemptLabel(3)).toBe('第 3 次尝试');
  });
});
