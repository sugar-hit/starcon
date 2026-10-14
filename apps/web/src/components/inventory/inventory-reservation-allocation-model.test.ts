import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  RESERVATION_ALLOCATION_SORT_OPTIONS,
  RESERVATION_ALLOCATION_STATUS_OPTIONS,
  RESERVATION_ALLOCATION_TRANSITION_OPTIONS,
  UNALLOCATED_NOTE,
  allocationLevelLabel,
  allocationStatusLabel,
  allocationTransitionLabel,
  bucketText,
  commitReservationAllocation,
  consumeReservationAllocation,
  createReservationAllocation,
  entryFromLabel,
  fetchReservationAllocationOptions,
  fetchReservationAllocationTimeline,
  fetchReservationAllocations,
  isTerminalAllocation,
} from './inventory-reservation-allocation-model';

/**
 * 7.2.1.2 预占分配明细前端模型。
 *
 * <p>重点钉住三件「写错了没人会发现」的事：
 *
 * <ul>
 *   <li><b>建行那一条时间线的来源状态显示「新建」，不是 HELD</b>。
 *       「此前无状态」与「此前就是 HELD」在审计上不是一回事。
 *   <li><b>bucket 缺哪一维就少一段，不拼成空字符串</b>。空白格会被读成「没加载出来」，
 *       而「—」读起来就是「没有」——虚拟仓没有货位、非批次管理品没有批次，都是合法状态。
 *   <li><b>可触发迁移不含释放与过期</b>。那属 7.2.1.3，列进来等于给一个点下去
 *       什么也不发生的按钮。
 * </ul>
 */

afterEach(() => vi.unstubAllGlobals());

/** 取出 mock 记录的第 n 个请求 URL。noUncheckedIndexedAccess 下直接索引 calls[n] 会报 TS2532。 */
function urls(fetcher: { mock: { calls: unknown[][] } }, n: number): string {
  return String(fetcher.mock.calls[n]?.[0]);
}

function bodyOf(fetcher: { mock: { calls: unknown[][] } }, n: number): unknown {
  return fetcher.mock.calls[n]?.[1] as { body?: string } | undefined;
}

describe('bucket 三维展示', () => {
  it('三维齐全时按仓 → 货位 → 批次拼开', () => {
    expect(bucketText('WH-1', 'L-01', 'LOT-A')).toBe('WH-1 / L-01 / LOT-A');
  });

  it('缺哪一维就少一段而不是留下空档', () => {
    // 虚拟仓没有货位档案、非批次管理品没有批次 —— 两种都是合法状态。
    expect(bucketText('WH-1', null, 'LOT-A')).toBe('WH-1 / LOT-A');
    expect(bucketText('WH-1', 'L-01', null)).toBe('WH-1 / L-01');
    expect(bucketText('WH-1', null, null)).toBe('WH-1');
  });

  it('全缺时给「—」而不是空字符串', () => {
    // 空白格会被读成「数据没加载出来」；「—」读起来就是「没有」。
    expect(bucketText(null, undefined, null)).toBe('—');
    expect(bucketText(null, null, null)).not.toBe('');
  });
});

describe('时间线文案', () => {
  it('建行那一条的来源显示「新建」而不是 HELD', () => {
    // 「此前无状态」与「此前就是 HELD」在审计上不是一回事：
    // 写成 HELD 会让人以为它是从另一个 HELD 迁过来的。
    expect(entryFromLabel(null)).toBe('新建');
    expect(entryFromLabel(undefined)).toBe('新建');
    expect(entryFromLabel('HELD')).toBe('预占中');
    expect(entryFromLabel('COMMITTED')).toBe('已承诺');
  });

  it('迁移类型缺失时回退到内置文案而不是空白格', () => {
    expect(allocationTransitionLabel('ALLOCATED')).toBe('登记分配');
    expect(allocationTransitionLabel('COMMITTED')).toBe('承诺分配');
    expect(allocationTransitionLabel('EXPIRED')).toBe('已过期');
    expect(allocationTransitionLabel('WEIRD')).toBe('WEIRD');
    expect(allocationTransitionLabel(undefined)).toBe('未知迁移');
    // 服务端给了标签就以它为准
    expect(allocationTransitionLabel('ALLOCATED', '服务端文案')).toBe('服务端文案');
  });

  it('分录层级说清是分配级还是预占级', () => {
    expect(allocationLevelLabel('ALLOCATION')).toBe('分配级');
    expect(allocationLevelLabel('RESERVATION')).toBe('预占级');
  });
});

describe('状态与终态', () => {
  it('COMMITTED 不是终态', () => {
    // 蓝图 7.2.1.3：「承诺分配（committed）后不再自动 TTL 释放」。
    // 把它算成终态会让界面显示成「已结束」，与后端的超时口径直接矛盾。
    expect(isTerminalAllocation('COMMITTED')).toBe(false);
    expect(isTerminalAllocation('HELD')).toBe(false);
    expect(isTerminalAllocation('CONSUMED')).toBe(true);
    expect(isTerminalAllocation('RELEASED')).toBe(true);
    expect(isTerminalAllocation('EXPIRED')).toBe(true);
    expect(isTerminalAllocation(undefined)).toBe(false);
  });

  it('下拉词表与终态标注一致', () => {
    const byCode = new Map(RESERVATION_ALLOCATION_STATUS_OPTIONS.map((o) => [o.code, o.terminal]));
    expect(byCode.get('HELD')).toBe(false);
    expect(byCode.get('COMMITTED')).toBe(false);
    expect(byCode.get('CONSUMED')).toBe(true);
    expect(byCode.get('RELEASED')).toBe(true);
    expect(byCode.get('EXPIRED')).toBe(true);
  });

  it('状态标签缺失时回退到内置文案', () => {
    expect(allocationStatusLabel('HELD')).toBe('预占中');
    expect(allocationStatusLabel('WEIRD')).toBe('WEIRD');
    expect(allocationStatusLabel(undefined)).toBe('未知状态');
  });
});

describe('可触发迁移不含释放与过期', () => {
  it('下拉里没有释放与过期 —— 那属 7.2.1.3', () => {
    expect(RESERVATION_ALLOCATION_TRANSITION_OPTIONS.map((o) => o.code)).toEqual([
      'ALLOCATE',
      'COMMIT',
      'CONSUME',
    ]);
    expect(RESERVATION_ALLOCATION_TRANSITION_OPTIONS.map((o) => o.code)).not.toContain('RELEASE');
    expect(RESERVATION_ALLOCATION_TRANSITION_OPTIONS.map((o) => o.code)).not.toContain('EXPIRE');
  });

  it('排序键与后端白名单一致', () => {
    expect(RESERVATION_ALLOCATION_SORT_OPTIONS.map((o) => o.code).sort()).toEqual(
      ['ALLOCATED_AT', 'BUCKET', 'QUANTITY', 'REMAINING', 'STATUS'].sort(),
    );
  });
});

describe('口径说明', () => {
  it('未分配量与剩余量写成两个问题而不是一个', () => {
    expect(UNALLOCATED_NOTE).toContain('未分配量');
    expect(UNALLOCATED_NOTE).toContain('剩余量');
    expect(UNALLOCATED_NOTE).toContain('bucket');
  });
});

describe('请求形状', () => {
  it('明细 / 时间线 / 下拉项各走各的路径，空条件不带尾随问号', async () => {
    const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
    vi.stubGlobal('fetch', fetcher);
    const id = '11111111-1111-1111-1111-111111111111';
    try {
      await fetchReservationAllocations(id);
      expect(urls(fetcher, 0)).toBe(
        `/api/warehouse/warehouse/inventory/reservations/${id}/allocations`,
      );

      await fetchReservationAllocations(id, {
        status: ['HELD', 'COMMITTED'],
        warehouseId: 'wh-1',
        keyword: ' L-01 ',
        sort: 'BUCKET',
        page: 1,
        size: 20,
      });
      expect(urls(fetcher, 1)).toBe(
        `/api/warehouse/warehouse/inventory/reservations/${id}/allocations` +
          `?status=HELD&status=COMMITTED&warehouseId=wh-1&keyword=L-01&sort=BUCKET&page=1&size=20`,
      );

      // 时间线走 /timeline 而不是 /entries：对外说的是「时间线」这个概念，
      // reservation_entry 是表的内部名字。
      await fetchReservationAllocationTimeline(id, { transition: 'COMMITTED' });
      expect(urls(fetcher, 2)).toBe(
        `/api/warehouse/warehouse/inventory/reservations/${id}/timeline?transition=COMMITTED`,
      );

      await fetchReservationAllocationOptions(id);
      expect(urls(fetcher, 3)).toBe(
        `/api/warehouse/warehouse/inventory/reservations/${id}/allocation-options`,
      );
    } finally {
      vi.unstubAllGlobals();
    }
  });

  it('写动作带上分配 id：commit / consume 不能少了最后一段', async () => {
    // 白名单里 commit / consume 必须在 allocations 之下再挂一段 id；
    // 少了就点一下 404，而页面上的按钮照常显示。
    const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
    vi.stubGlobal('fetch', fetcher);
    const id = '11111111-1111-1111-1111-111111111111';
    const allocationId = '22222222-2222-2222-2222-222222222222';
    try {
      await createReservationAllocation(id, {
        warehouseId: 'wh-1',
        allocatedQuantity: 12,
      });
      expect(urls(fetcher, 0)).toBe(
        `/api/warehouse/warehouse/inventory/reservations/${id}/allocations`,
      );
      expect(bodyOf(fetcher, 0)).toMatchObject({
        body: '{"warehouseId":"wh-1","allocatedQuantity":12}',
      });

      await commitReservationAllocation(id, allocationId, {});
      expect(urls(fetcher, 1)).toBe(
        `/api/warehouse/warehouse/inventory/reservations/${id}/allocations/${allocationId}/commit`,
      );

      await consumeReservationAllocation(id, allocationId, { quantity: 4 });
      expect(urls(fetcher, 2)).toBe(
        `/api/warehouse/warehouse/inventory/reservations/${id}/allocations/${allocationId}/consume`,
      );
      expect(bodyOf(fetcher, 2)).toMatchObject({ body: '{"quantity":4}' });
    } finally {
      vi.unstubAllGlobals();
    }
  });
});
