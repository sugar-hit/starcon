import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  REMAINING_NOTE,
  RESERVATION_SORT_OPTIONS,
  RESERVATION_STATUS_OPTIONS,
  allocatedText,
  fetchReservations,
  isTerminalStatus,
  overdueIsSevere,
  overdueText,
  reservationSourceSystemLabel,
  reservationSourceTypeLabel,
  reservationStatusLabel,
} from './inventory-reservation-model';

/**
 * 7.2.1.1 预占需求列表前端模型。
 *
 * <p>重点钉住三件「写成 0 就完了」的事：
 *
 * <ul>
 *   <li><b>超时长 null → 「—」而不是 0</b>。null 与 0 在界面上含义完全不同，
 *       写死成 '0 秒' 会让每一条正常预占都显示「超时 0 秒」。
 *   <li><b>已分配量是回写的真值</b>：7.2.1.2 交付后由分配行汇总，
 *       0 就是「确实还没圈」，不能挂一个「未交付」后缀冒充数据缺失。
 *   <li><b>终态判定</b>：COMMITTED **不是**终态 —— 蓝图明确承诺分配后不再自动 TTL 释放，
 *       把它算成终态会让界面显示成「已结束」，与后端的超时口径直接矛盾。
 * </ul>
 */

afterEach(() => vi.unstubAllGlobals());

/** 取出 mock 记录的第 n 个请求 URL。noUncheckedIndexedAccess 下直接索引 calls[n] 会报 TS2532。 */
function urls(fetcher: { mock: { calls: unknown[][] } }, n: number): string {
  return String(fetcher.mock.calls[n]?.[0]);
}

describe('超时长展示', () => {
  it('null 显示为「—」而不是 0 秒', () => {
    // 这是本组最重要的一条：null 的含义是「没超时 / 已终态 / 没有过期时间」
    expect(overdueText(null)).toBe('—');
    expect(overdueText(undefined)).toBe('—');
    expect(overdueText(null)).not.toBe('0 秒');
    expect(overdueText(null)).not.toBe('0');
  });

  it('真正超时才给出数值且逐级取整', () => {
    expect(overdueText(45)).toBe('45 秒');
    expect(overdueText(600)).toBe('10 分钟');
    expect(overdueText(3660)).toBe('1 小时 1 分');
    expect(overdueText(90000)).toBe('1 天 1 小时');
    // 负值（时钟回拨）不显示成负天数
    expect(overdueText(-10)).toBe('—');
  });

  it('严重程度以一小时为界', () => {
    expect(overdueIsSevere(null)).toBe(false);
    expect(overdueIsSevere(0)).toBe(false);
    expect(overdueIsSevere(3599)).toBe(false);
    expect(overdueIsSevere(3600)).toBe(true);
    expect(overdueIsSevere(86400)).toBe(true);
  });
});

describe('已分配量展示', () => {
  it('7.2.1.2 交付后 0 就是 0，不加「未交付」后缀', () => {
    // 该值现在由 reservation_allocation 的分配行回写：0 表示「确实还没圈到任何 bucket」。
    // 留着「分配明细未交付」后缀会让人以为功能没接通，而它其实已经跑通 ——
    // 一个恒挂的后缀比一个诚实的 0 更误导人。
    expect(allocatedText(0)).toBe('0');
    expect(allocatedText(12)).toBe('12');
    expect(allocatedText(null)).toBe('—');
  });
});

describe('终态判定', () => {
  it('COMMITTED 不是终态', () => {
    // 蓝图 7.2.1.3：「承诺分配（committed）后不再自动 TTL 释放」。
    // 把 COMMITTED 算成终态会让界面显示成「已结束」，
    // 与后端「COMMITTED 不参与超时筛选」的口径直接矛盾。
    expect(isTerminalStatus('COMMITTED')).toBe(false);
    expect(isTerminalStatus('HELD')).toBe(false);
  });

  it('三个终态判定为真', () => {
    expect(isTerminalStatus('CONSUMED')).toBe(true);
    expect(isTerminalStatus('RELEASED')).toBe(true);
    expect(isTerminalStatus('EXPIRED')).toBe(true);
    expect(isTerminalStatus(undefined)).toBe(false);
  });

  it('下拉词表与终态标注一致', () => {
    const byCode = new Map(RESERVATION_STATUS_OPTIONS.map((o) => [o.code, o.terminal]));
    expect(byCode.get('HELD')).toBe(false);
    expect(byCode.get('COMMITTED')).toBe(false);
    expect(byCode.get('CONSUMED')).toBe(true);
    expect(byCode.get('RELEASED')).toBe(true);
    expect(byCode.get('EXPIRED')).toBe(true);
  });
});

describe('标签回退', () => {
  it('缺标签时回退到内置文案而不是空白格', () => {
    expect(reservationStatusLabel('HELD')).toBe('预占中');
    expect(reservationStatusLabel('EXPIRED')).toBe('已过期');
    expect(reservationSourceTypeLabel('SALES_ORDER')).toBe('销售订单');
    expect(reservationSourceSystemLabel('TRADE')).toBe('订单履约');
    // 词表外的值原样显示，不吞掉
    expect(reservationStatusLabel('WEIRD')).toBe('WEIRD');
    expect(reservationStatusLabel(undefined)).toBe('未知状态');
  });

  it('服务端给了标签就以它为准', () => {
    expect(reservationStatusLabel('HELD', '服务端文案')).toBe('服务端文案');
  });
});

describe('口径说明', () => {
  it('剩余量口径写明不含已分配未出库', () => {
    expect(REMAINING_NOTE).toContain('已分配');
    expect(REMAINING_NOTE).toContain('剩余量');
  });

  it('排序键与后端白名单一致', () => {
    expect(RESERVATION_SORT_OPTIONS.map((o) => o.code).sort()).toEqual(
      ['EXPIRES_AT', 'QUANTITY', 'REMAINING', 'REQUESTED_AT', 'RESERVATION_NO', 'STATUS'].sort(),
    );
  });
});

describe('列表查询参数', () => {
  it('只拼非空参数：空条件不带尾随问号，多状态逐个展开', async () => {
    // 拼 URL 的形状决定了后端能不能解析。多一个 & 或少一个 &，
    // 「全部状态」就会变成一个永远筛不出东西的查询。
    const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
    vi.stubGlobal('fetch', fetcher);
    try {
      await fetchReservations({});
      expect(urls(fetcher, 0)).toBe('/api/warehouse/warehouse/inventory/reservations');

      await fetchReservations({ status: ['HELD', 'EXPIRED'], sort: 'QUANTITY', page: 1, size: 20 });
      expect(urls(fetcher, 1)).toBe(
        '/api/warehouse/warehouse/inventory/reservations?status=HELD&status=EXPIRED&sort=QUANTITY&page=1&size=20',
      );

      await fetchReservations({ keyword: '  SO-1  ', overdueOnly: true });
      expect(urls(fetcher, 2)).toBe(
        '/api/warehouse/warehouse/inventory/reservations?overdueOnly=true&keyword=SO-1',
      );
    } finally {
      vi.unstubAllGlobals();
    }
  });
});
