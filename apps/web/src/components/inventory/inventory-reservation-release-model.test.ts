import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  AVAILABLE_OWNER_NOTE,
  EXPIRY_RULE_NOTE,
  RESERVATION_RELEASE_BASE,
  RESERVATION_RELEASE_SORT_OPTIONS,
  RESERVATION_RELEASE_TRIGGER_OPTIONS,
  RESTORE_QUANTITY_NOTE,
  SWEEP_ONLY_TRIGGER_NOTE,
  expireOverdueReservations,
  fetchReservationReleaseCandidates,
  fetchReservationReleaseRecords,
  fetchReservationReleaseRun,
  fetchReservationReleaseRuns,
  releaseModeLabel,
  releaseReservation,
  releaseTriggerLabel,
  restoredText,
  runSummaryText,
  setReservationExpiry,
  overdueText,
} from './inventory-reservation-release-model';

/**
 * 7.2.1.3 释放与过期前端模型。
 *
 * <p>重点钉住四件「写错了没人会发现」的事：
 *
 * <ul>
 *   <li><b>还回可用池的量与需求释放的量不合并</b>。需求 12、只圈到 10 的货整笔释放时，
 *       两者是 10 与 12；合成一个数字，可用量会凭空多出 2。
 *   <li><b>超时时长为 null 显示「—」而不是 0</b>。0 会把「没超时」「没设过期时刻」
 *       「超时 0 秒」画成同一个数。
 *   <li><b>干跑与真跑的运行摘要一眼可分</b>。「干跑了但没执行」被当成已清理是最贵的错。
 *   <li><b>可空字段补 null</b>：codegen 把 nullable 生成 <code>T | null</code> 而不是可选字段，
 *       漏一个就报 TS2345。
 * </ul>
 */

afterEach(() => vi.unstubAllGlobals());

/** 取出 mock 记录的第 n 个请求 URL。noUncheckedIndexedAccess 下直接索引 calls[n] 会报 TS2532。 */
function urls(fetcher: { mock: { calls: unknown[][] } }, n: number): string {
  return String(fetcher.mock.calls[n]?.[0]);
}

function bodyOf(
  fetcher: { mock: { calls: unknown[][] } },
  n: number,
): { body?: string } | undefined {
  return fetcher.mock.calls[n]?.[1] as { body?: string } | undefined;
}

function bodyJson(fetcher: { mock: { calls: unknown[][] } }, n: number): Record<string, unknown> {
  return JSON.parse(String(bodyOf(fetcher, n)?.body));
}

function stubFetch() {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);
  return fetcher;
}

describe('端点形状', () => {
  it('候选清单把筛选与判定时刻拼进 query string', async () => {
    const fetcher = stubFetch();
    await fetchReservationReleaseCandidates({
      keyword: ' SO-1 ',
      sourceType: 'SALES_ORDER',
      sort: 'RESTORABLE',
      evaluatedAt: '2026-10-14T02:00:00+08:00',
      page: 1,
      size: 50,
    });
    const url = urls(fetcher, 0);
    expect(url).toContain(`${RESERVATION_RELEASE_BASE}/release-candidates?`);
    // 关键字两边空白要去掉再拼：否则用户输入的空格会变成一个永远匹配不到的结果集
    expect(url).toContain('keyword=SO-1');
    expect(url).toContain('sourceType=SALES_ORDER');
    expect(url).toContain('sort=RESTORABLE');
    // 判定时刻要原样带上（带偏移量），服务端据此返回同一个候选集
    expect(url).toContain('evaluatedAt=2026-10-14T02%3A00%3A00%2B08%3A00');
  });

  it('空筛选不带任何 query 参数', async () => {
    const fetcher = stubFetch();
    await fetchReservationReleaseCandidates();
    expect(urls(fetcher, 0)).toBe(`/api/warehouse/${RESERVATION_RELEASE_BASE}/release-candidates`);
  });

  it('运行历史、运行详情、释放凭据各自是各自的形状', async () => {
    const fetcher = stubFetch();
    await fetchReservationReleaseRuns(2, 30);
    expect(urls(fetcher, 0)).toContain('/expiry-runs?page=2&size=30');
    await fetchReservationReleaseRun('run-1');
    expect(urls(fetcher, 1)).toContain('/expiry-runs/run-1');
    await fetchReservationReleaseRecords({ reservationId: 'res-1' });
    expect(urls(fetcher, 2)).toContain('/release-records?reservationId=res-1');
  });

  it('写动作打 POST，显式 undefined 的字段补成 null', async () => {
    const fetcher = stubFetch();
    // 调用方写了 undefined 的字段必须变成 null：codegen 生成 T | null 而非可选字段，
    // 漏补一个就报 TS2345；漏发的字段则整个不出现在请求体里，服务端按缺省（null）处理。
    await releaseReservation('res-1', {
      triggerSource: 'MANUAL',
      reason: '人工释放',
    });
    expect(urls(fetcher, 0)).toContain('/reservations/res-1/release');
    const body = bodyJson(fetcher, 0);
    expect(body.triggerSource).toBe('MANUAL');
    expect(body.reason).toBe('人工释放');
    // 没传的字段整个不出现在请求体里，服务端按缺省（null）处理 —— 与传 null 同义。
    expect(body.quantity).toBeUndefined();
    expect(body.allocations).toBeUndefined();
  });

  it('显式写了 undefined 的字段被补成 null 而不是被 JSON 丢掉', async () => {
    const fetcher = stubFetch();
    // fill() 的存在意义：调用方拼对象时不必手工给每个可空字段写 null。
    await setReservationExpiry('res-1', { expiresAt: null, clear: false });
    expect(bodyJson(fetcher, 0)).toEqual({ expiresAt: null, clear: false });
  });

  it('批量清理与过期时刻补写各走自己的形状', async () => {
    const fetcher = stubFetch();
    await expireOverdueReservations({ dryRun: true, limitCount: 100 });
    expect(urls(fetcher, 0)).toContain('/reservations/expire-overdue');
    expect(bodyOf(fetcher, 0)?.body).toContain('"dryRun":true');

    await setReservationExpiry('res-1', { clear: true });
    expect(urls(fetcher, 1)).toContain('/reservations/res-1/expiry');
    expect(bodyOf(fetcher, 1)?.body).toContain('"clear":true');
  });
});

describe('恢复量与释放量是两个数', () => {
  it('两者相等时只显示一个数', () => {
    expect(restoredText(12, 12)).toBe('12');
  });

  it('两者不等时两个都显示，并写明差额来自哪一侧', () => {
    // 需求 12、只圈到 10 的货整笔释放：还回 10、需求侧放弃 12
    expect(restoredText(10, 12)).toBe('10（需求侧放弃 12）');
  });

  it('缺值时按 0 处理而不是 NaN', () => {
    expect(restoredText(null, null)).toBe('0');
    expect(restoredText(undefined, 3)).toBe('0（需求侧放弃 3）');
  });

  it('口径说明常驻显示，否则「还回 10 / 释放 12」会被当成算错', () => {
    expect(RESTORE_QUANTITY_NOTE).toContain('可用池');
    expect(RESTORE_QUANTITY_NOTE).toContain('需求释放');
  });
});

describe('超时时长文案', () => {
  it('null 显示「—」而不是 0', () => {
    // 0 会把「没超时」「没设过期时刻」「超时 0 秒」画成同一个数
    expect(overdueText(null)).toBe('—');
    expect(overdueText(undefined)).toBe('—');
  });

  it('不足一天按小时 / 分钟显示，超过一天带上天数', () => {
    expect(overdueText(0)).toBe('0 秒');
    expect(overdueText(90)).toBe('1 分 30 秒');
    expect(overdueText(3660)).toBe('1 小时 1 分');
    expect(overdueText(90000)).toBe('1 天 1 小时');
  });
});

describe('运行摘要', () => {
  const base = {
    id: 'run-1',
    dryRun: false,
    evaluatedAt: '2026-10-14T02:00:00+08:00',
    limitCount: 50,
    candidateCount: 3,
    processedCount: 3,
    skippedCount: 0,
    releaseCount: 3,
    restoredQuantity: 30,
    releasedDemandQuantity: 36,
    status: 'SUCCEEDED',
    actorId: null,
    reason: null,
    idempotencyKey: null,
    createdAt: '2026-10-14T02:00:00+08:00',
    completedAt: '2026-10-14T02:00:00+08:00',
    replayed: false,
  };

  it('干跑与真跑的摘要一眼可分', () => {
    // 「干跑了但没执行」被当成已清理是最贵的错
    expect(
      runSummaryText({ ...base, dryRun: true, processedCount: 0, restoredQuantity: 0 }),
    ).toContain('未做任何变更');
    expect(runSummaryText(base)).toContain('回收 3 条');
    expect(runSummaryText(base)).toContain('还回可用量 30');
  });

  it('有跳过时把跳过的条数写出来', () => {
    // 跳过的多半是「名下还有承诺」的预占，不说出来运营会以为清干净了
    expect(runSummaryText({ ...base, processedCount: 2, skippedCount: 1 })).toContain('跳过 1 条');
  });
});

describe('词表与说明', () => {
  it('释放方式与来源标签有兜底，不渲染空白格', () => {
    expect(releaseModeLabel('RELEASE')).toBe('释放');
    expect(releaseModeLabel('EXPIRE')).toBe('超时回收');
    expect(releaseModeLabel('RELEASE', '服务端给的标签')).toBe('服务端给的标签');
    expect(releaseModeLabel(undefined)).toBe('未知方式');
    expect(releaseTriggerLabel('ORDER_CANCELLED')).toBe('订单取消');
    expect(releaseTriggerLabel('EXPIRY_SWEEP')).toBe('超时回收');
    expect(releaseTriggerLabel('WHATEVER')).toBe('WHATEVER');
  });

  it('超时回收来源标注为仅批量可用', () => {
    const sweepOnly = RESERVATION_RELEASE_TRIGGER_OPTIONS.filter((o) => o.sweepOnly);
    expect(sweepOnly.map((o) => o.code)).toEqual(['EXPIRY_SWEEP']);
    expect(SWEEP_ONLY_TRIGGER_NOTE).toContain('批量过期清理');
  });

  it('规则说明点名承诺不自动过期', () => {
    expect(EXPIRY_RULE_NOTE).toContain('COMMITTED');
    expect(RESERVATION_RELEASE_SORT_OPTIONS.map((o) => o.code)).toEqual([
      'EXPIRES_AT',
      'OVERDUE',
      'RESTORABLE',
      'RESERVATION_NO',
    ]);
  });

  it('可用量口径归属写明 7.1.1.3，本模块不重复实现', () => {
    expect(AVAILABLE_OWNER_NOTE).toContain('7.1.1.3');
    expect(AVAILABLE_OWNER_NOTE).toContain('不重复实现');
  });
});
