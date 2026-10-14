import { describe, expect, it, vi } from 'vitest';
import type {
  TrackingCreateRequest,
  TrackingEventRequest,
  TrackingReceiptRequest,
  DelayThresholdUpdateRequest,
} from '../../../../../packages/api-contracts/src/index';
import {
  acknowledgeDelayAlert,
  closeDelayAlert,
  closeTransitTracking,
  confirmTransitTrackingReceipt,
  createTransitTracking,
  delayLevelLabel,
  delayLevelSeverity,
  dimensionCaption,
  evaluateAllAlerts,
  evaluateOneAlert,
  fetchDelayAlerts,
  fetchDelayThresholds,
  fetchTransitTrackingCapabilities,
  fetchTransitTrackingDetail,
  fetchTransitTrackings,
  legQuantityText,
  recordTransitTrackingEvent,
  remainingWindow,
  sourceCaption,
  trackingActions,
  transitTrackingQuery,
  updateDelayThreshold,
} from './transit-tracking-model';

/** 最小可接受的请求体形状。与后端 record 的字段一一对应。 */
const createBody: TrackingCreateRequest = {
  shipmentId: 's-1',
  currentLocation: null,
  locationOccurredAt: null,
  customsStatus: null,
  customsHoldReason: null,
  trackingStage: null,
  notes: null,
};

const receiptBody: TrackingReceiptRequest = {
  receiptReference: 'GRC-1',
  receivedAt: new Date(0).toISOString(),
  notes: null,
};

const thresholdBody: DelayThresholdUpdateRequest = {
  watchLeadDays: 7,
  warningLeadDays: 3,
  criticalLeadDays: 0,
  notes: null,
};

function eventBody(kind: string): TrackingEventRequest {
  return {
    kind,
    sourceSystem: 'MANUAL',
    referenceId: null,
    locationText: null,
    locationOccurredAt: null,
    etaAfter: null,
    reason: null,
    customsStatus: null,
    customsHoldReason: null,
    toStage: null,
    detail: '备注',
    occurredAt: new Date(0).toISOString(),
  };
}

/**
 * 10.4.1.2 在途跟踪前端模型测试。
 *
 * <p>这里守的是三件容易静默错的事：
 * ① **查询串逐条落参** —— 少落一个筛选条件，页面上「筛选没生效」但类型检查全绿；
 * ② **真实调用点的 URL 与方法** —— 从模型文件反向抽取，少放行一个白名单路径
 *    页面就在浏览器里静默 404（typecheck / lint / vitest 全绿都发现不了）；
 * ③ **口径不从前端抄一份** —— 档位、来源、阈值都不在前端写死第二遍。
 */

describe('查询串', () => {
  it('四个多选筛选逐条落参（不拼成逗号串）', () => {
    expect(
      transitTrackingQuery({
        status: ['TRACKING'],
        stage: ['IN_CUSTOMS'],
        customsStatus: ['HELD'],
        delayLevel: ['CRITICAL'],
      }),
    ).toBe('?status=TRACKING&stage=IN_CUSTOMS&customsStatus=HELD&delayLevel=CRITICAL');
  });

  it('单值筛选落全部标量参数，delayedOnly 落 true 而不是字符串', () => {
    expect(
      transitTrackingQuery({
        shipmentId: 'ship-1',
        destinationWarehouseId: 'wh-1',
        destinationCountryCode: ' us ',
        transportMode: 'SEA',
        delayedOnly: true,
        keyword: ' COSCO ',
        sort: 'LEVEL_DESC',
        page: 2,
        size: 50,
      }),
    ).toBe(
      '?shipmentId=ship-1&destinationWarehouseId=wh-1&destinationCountryCode=us&transportMode=SEA&delayedOnly=true&keyword=COSCO&sort=LEVEL_DESC&page=2&size=50',
    );
  });

  it('空条件不产生裸问号', () => {
    expect(transitTrackingQuery({})).toBe('');
  });
});

describe('真实调用点（从模型文件反向抽取）', () => {
  /**
   * 每个调用点跑一次真实调用，捕获 **api() 实际发出的请求**。
   *
   * <p>断言的是 `fetcher` 收到的 URL 与方法，而不是把字符串摆在用例里自我表扬 ——
   * 白名单漏登记时页面只在浏览器里静默 404，typecheck / lint / vitest 全绿都发现不了，
   * 所以这一步必须真的穿过 `api()` 打到 fetch。
   */
  async function capture(work: () => Promise<unknown>): Promise<{ url: string; method: string }> {
    let seen: { url: string; method: string } | null = null;
    const fetcher = vi.fn((input: RequestInfo | URL, init?: RequestInit) => {
      seen = {
        url: String(input),
        method: init?.method ?? 'GET',
      };
      return Promise.resolve(new Response('{}', { status: 200 }));
    });
    vi.stubGlobal('fetch', fetcher);
    await work();
    vi.unstubAllGlobals();
    if (!seen) throw new Error('这次调用没有发出任何请求 —— 用例本身失效了');
    return seen;
  }

  it.each([
    ['列表', () => fetchTransitTrackings({}), 'GET', '/api/warehouse/warehouse/transit-trackings'],
    [
      '能力',
      () => fetchTransitTrackingCapabilities(),
      'GET',
      '/api/warehouse/warehouse/transit-trackings/capabilities',
    ],
    [
      '详情',
      () => fetchTransitTrackingDetail('t-1'),
      'GET',
      '/api/warehouse/warehouse/transit-trackings/t-1',
    ],
    [
      '建跟踪',
      () => createTransitTracking(createBody),
      'POST',
      '/api/warehouse/warehouse/transit-trackings',
    ],
    [
      '登记节点',
      () => recordTransitTrackingEvent('t-1', eventBody('NOTE')),
      'POST',
      '/api/warehouse/warehouse/transit-trackings/t-1/events',
    ],
    [
      '收货确认',
      () => confirmTransitTrackingReceipt('t-1', receiptBody),
      'POST',
      '/api/warehouse/warehouse/transit-trackings/t-1/receipt',
    ],
    [
      '关闭跟踪',
      () => closeTransitTracking('t-1', '闭环'),
      'POST',
      '/api/warehouse/warehouse/transit-trackings/t-1/close',
    ],
    [
      '预警清单',
      () => fetchDelayAlerts({}),
      'GET',
      '/api/warehouse/warehouse/transit-trackings/alerts',
    ],
    [
      '单条重算',
      () => evaluateOneAlert('t-1'),
      'POST',
      '/api/warehouse/warehouse/transit-trackings/t-1/alerts/evaluate',
    ],
    [
      '批量重算',
      () => evaluateAllAlerts(),
      'POST',
      '/api/warehouse/warehouse/transit-trackings/alerts/evaluate-all',
    ],
    [
      '预警确认',
      () => acknowledgeDelayAlert('a-1', { note: null }),
      'POST',
      '/api/warehouse/warehouse/transit-trackings/alerts/a-1/acknowledge',
    ],
    [
      '预警关闭',
      () => closeDelayAlert('a-1', { reason: '货到了' }),
      'POST',
      '/api/warehouse/warehouse/transit-trackings/alerts/a-1/close',
    ],
    [
      '阈值清单',
      () => fetchDelayThresholds(),
      'GET',
      '/api/warehouse/warehouse/transit-trackings/thresholds',
    ],
    [
      '改阈值',
      () => updateDelayThreshold('th-1', thresholdBody),
      'PUT',
      '/api/warehouse/warehouse/transit-trackings/thresholds/th-1',
    ],
  ] as Array<[string, () => Promise<unknown>, string, string]>)(
    '%s → %s %s',
    async (_name, work, method, url) => {
      const seen = await capture(work);
      expect(seen.method).toBe(method);
      expect(seen.url).toContain(url);
    },
  );

  it('筛选参数真的进了 URL（不是只落在内存里）', async () => {
    const seen = await capture(() =>
      fetchTransitTrackings({ delayedOnly: true, delayLevel: ['CRITICAL'], keyword: 'COSCO' }),
    );
    expect(seen.url).toContain('delayedOnly=true');
    expect(seen.url).toContain('delayLevel=CRITICAL');
    expect(seen.url).toContain('keyword=COSCO');
  });
});

describe('档位：从前端不抄一份阈值', () => {
  it('四档都有中文名与严重度，ON_TRACK 最低', () => {
    expect(delayLevelLabel('ON_TRACK')).toBe('正常在途');
    expect(delayLevelLabel('WATCH')).toBe('临近到港');
    expect(delayLevelLabel('WARNING')).toBe('延误预警');
    expect(delayLevelLabel('CRITICAL')).toBe('严重延误');
    // 逐档取出来比（用索引直接比）更严：noUncheckedIndexedAccess 下索引读出的是
    // number | undefined，写成链式比较会在类型检查里报错而不是在运行期炸。
    const order = [
      delayLevelSeverity.ON_TRACK,
      delayLevelSeverity.WATCH,
      delayLevelSeverity.WARNING,
      delayLevelSeverity.CRITICAL,
    ];
    expect(order.every((v) => typeof v === 'number')).toBe(true);
    expect(order).toEqual([0, 1, 2, 3]);
  });

  it('未收录的档位原样返回，不静默吞掉', () => {
    expect(delayLevelLabel('SOMETHING_NEW')).toBe('SOMETHING_NEW');
  });
});

describe('剩余窗口：负数不是「提前」', () => {
  it('正数说「还有几天」，负数说「已过预计到港仍未到货」', () => {
    const soon = remainingWindow({ remainingDays: 3.25, overdue: false });
    expect(soon.text).toBe('距预计到港还有 3.3 天');
    expect(soon.late).toBe(false);

    const late = remainingWindow({ remainingDays: -2, overdue: true });
    expect(late.text).toContain('已过预计到港');
    expect(late.text).toContain('仍未到货');
    expect(late.late).toBe(true);
  });

  it('没有 ETA 时说「无法分级」，不显示 0 天冒充正常', () => {
    const none = remainingWindow({ remainingDays: null, overdue: false });
    expect(none.text).toContain('未给 ETA');
    expect(none.late).toBe(false);
  });
});

describe('在途腿守恒等式', () => {
  it('对得上时给出等式，对不上时明确说对不上', () => {
    expect(
      legQuantityText({ shippedQuantity: 100, settledQuantity: 60, remainingQuantity: 40 }),
    ).toEqual({ ok: true, text: '原发 100 = 已了结 60 + 未达 40' });
    const broken = legQuantityText({
      shippedQuantity: 100,
      settledQuantity: 60,
      remainingQuantity: 0,
    });
    expect(broken.ok).toBe(false);
    expect(broken.text).toContain('对不上');
  });
});

describe('可执行动作：终态不给动作，清关未放行不给收货确认', () => {
  it('跟踪中才有动作', () => {
    expect(
      trackingActions({
        status: 'TRACKING',
        trackingStage: 'IN_CUSTOMS',
        customsStatus: 'CLEARED',
      }),
    ).toContain('receipt');
    expect(
      trackingActions({
        status: 'CLOSED',
        trackingStage: 'RECEIVED',
        customsStatus: 'CLEARED',
      }),
    ).toEqual([]);
    expect(
      trackingActions({
        status: 'CANCELLED',
        trackingStage: 'AT_ORIGIN',
        customsStatus: 'NOT_STARTED',
      }),
    ).toEqual([]);
  });

  it('清关被扣 / 退单时不给「已收货」，避免「货扣在关口却已入库」同时成立', () => {
    for (const customsStatus of ['HELD', 'REJECTED']) {
      expect(
        trackingActions({
          status: 'TRACKING',
          trackingStage: 'IN_CUSTOMS',
          customsStatus,
        }),
      ).not.toContain('receipt');
    }
  });
});

describe('外部来源必须留单号', () => {
  it('外部来源带单号，人工不显示单号', () => {
    expect(sourceCaption({ sourceSystem: 'FORWARDER', referenceId: 'FF-1' })).toBe('货代 · FF-1');
    expect(sourceCaption({ sourceSystem: 'CARRIER', referenceId: null })).toBe('船司/航空公司');
    expect(sourceCaption({ sourceSystem: 'MANUAL', referenceId: null })).toBe('人工登记');
  });
});

describe('未交付依赖如实标注', () => {
  it('不可用的维度带归属模块，而不是静默当成可用', () => {
    expect(
      dimensionCaption({
        code: 'FORWARDER_CONNECTOR',
        label: '货代状态同步连接器',
        available: false,
        ownerModule: '10.4.2.1',
        href: '/logistics/overseas-warehouses',
        note: '未交付',
      }),
    ).toBe('货代状态同步连接器（10.4.2.1 未交付）');
    expect(
      dimensionCaption({
        code: 'IN_TRANSIT_STOCK',
        label: '在途库存余量',
        available: true,
        ownerModule: '7.5.1.2',
        href: '/warehouse/inventory/in-transit',
        note: '已交付',
      }),
    ).toBe('在途库存余量');
  });
});
