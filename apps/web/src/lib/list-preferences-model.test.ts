import { describe, expect, it } from 'vitest';
import {
  PAGE_SIZE_OPTIONS,
  buildPreferencePayload,
  describePreference,
  isSafeFilterValue,
  normalizeColumns,
  pickPersistableFilters,
  resolveListPreferences,
  type ListPreferenceOptions,
} from './list-preferences-model';

/**
 * 1.4.1.2 列表与表格偏好 —— 前端模型的单元测试。
 *
 * <p>本模型守的是四件**静默**的事：缺键要套默认值（而不是报错）、存回来的列必须是这一页
 * 认识的列、「用户隐藏了某列」与「这一列当时还不存在」要分得开、自由文本不落库。
 *
 * <p>蓝图原话：「**偏好按页面 key 存储**，新增字段不影响旧偏好。」——
 * 「新增字段不影响旧偏好」在这里被钉成可执行断言：老载荷（只有 {@code pageSize}）
 * 读回来必须是一份**完整可用**的视图状态，而不是缺一截。
 */

const OPTIONS: ListPreferenceOptions = {
  pageKey: 'orders.pending-shipment',
  columns: [
    { key: 'outbound', label: '出库单' },
    { key: 'warehouse', label: '仓库' },
    { key: 'carrier', label: '承运商' },
    { key: 'sla', label: '时限' },
  ],
  sortOptions: [
    { key: 'PACKED_AT_DESC', label: '打包时间（新→旧）' },
    { key: 'SLA_DEADLINE_AT_ASC', label: '发货时限（近→远）' },
  ],
  pageSize: 20,
  pageSizeOptions: [10, 20, 50, 100],
  filters: { groupBy: 'CARRIER' },
  persistableFilterKeys: ['groupBy', 'carrierCode'],
};

describe('1.4.1.2 resolveListPreferences：缺键套默认值而不是报错', () => {
  it('载荷为空时整份落到页面默认值', () => {
    const state = resolveListPreferences(null, OPTIONS);
    expect(state.columns).toEqual(['outbound', 'warehouse', 'carrier', 'sla']);
    expect(state.sortKey).toBe('PACKED_AT_DESC');
    expect(state.pageSize).toBe(20);
    expect(state.filters).toEqual({ groupBy: 'CARRIER' });
  });

  it('载荷不是对象时同样落到默认值（不抛异常）', () => {
    for (const broken of ['x', 42, true, [], [1, 2]]) {
      expect(() => resolveListPreferences(broken, OPTIONS)).not.toThrow();
      expect(resolveListPreferences(broken, OPTIONS).pageSize).toBe(20);
    }
  });

  it('老偏好只有 pageSize 时，其余键各自套默认值', () => {
    // 这是「新增字段不影响旧偏好」最直接的形态：老行没有新加的字段。
    const state = resolveListPreferences({ pageSize: 50 }, OPTIONS);
    expect(state.pageSize).toBe(50);
    expect(state.columns).toEqual(['outbound', 'warehouse', 'carrier', 'sla']);
    expect(state.sortKey).toBe('PACKED_AT_DESC');
    expect(state.filters).toEqual({ groupBy: 'CARRIER' });
  });

  it('载荷里的 pageSize 不在可选值里时用默认值（不硬塞一个下拉框里没有的值）', () => {
    expect(resolveListPreferences({ pageSize: 37 }, OPTIONS).pageSize).toBe(20);
    expect(resolveListPreferences({ pageSize: 999 }, OPTIONS).pageSize).toBe(20);
  });

  it('载荷里的排序键这一页不认时用第一个已知排序', () => {
    expect(resolveListPreferences({ sortKey: 'NOT_A_SORT' }, OPTIONS).sortKey).toBe(
      'PACKED_AT_DESC',
    );
    expect(resolveListPreferences({ sortKey: 'SLA_DEADLINE_AT_ASC' }, OPTIONS).sortKey).toBe(
      'SLA_DEADLINE_AT_ASC',
    );
  });
});

describe('1.4.1.2 可见列：只认这一页认识的列', () => {
  it('存下来的列里不属于这一页的直接丢掉', () => {
    const state = resolveListPreferences(
      { columns: ['outbound', 'not_a_column_of_this_page'], columnSet: ['outbound'] },
      OPTIONS,
    );
    expect(state.columns).toEqual(['outbound', 'warehouse', 'carrier', 'sla']);
  });

  it('用户显式隐藏的列不再出现', () => {
    const state = resolveListPreferences(
      {
        columns: ['outbound', 'carrier', 'sla'],
        columnSet: ['outbound', 'warehouse', 'carrier', 'sla'],
      },
      OPTIONS,
    );
    expect(state.columns).not.toContain('warehouse');
    expect(state.columns).toEqual(['outbound', 'carrier', 'sla']);
  });

  it('这一页后来新增的列默认可见（不在当时的列集合里）', () => {
    const grown: ListPreferenceOptions = {
      ...OPTIONS,
      columns: [...OPTIONS.columns, { key: 'waybill', label: '运单号' }],
    };
    const state = resolveListPreferences(
      {
        columns: ['outbound', 'warehouse', 'carrier', 'sla'],
        columnSet: ['outbound', 'warehouse', 'carrier', 'sla'],
      },
      grown,
    );
    expect(state.columns).toContain('waybill');
  });

  it('visible 顺序按用户保存的顺序', () => {
    const state = resolveListPreferences(
      { columns: ['sla', 'outbound'], columnSet: ['outbound', 'warehouse', 'carrier', 'sla'] },
      OPTIONS,
    );
    expect(state.columns.slice(0, 2)).toEqual(['sla', 'outbound']);
  });

  it('normalizeColumns：没有列信息时全部可见', () => {
    expect(normalizeColumns(undefined, undefined, ['a', 'b'])).toEqual(['a', 'b']);
  });
});

describe('1.4.1.2 筛选条件：白名单 + 安全 token', () => {
  it('白名单外的键一律不落库', () => {
    const picked = pickPersistableFilters(
      { groupBy: 'CARRIER', keyword: '张三', secretNote: 'x' },
      ['groupBy', 'carrierCode'],
    );
    expect(picked).toEqual({ groupBy: 'CARRIER' });
  });

  it('自由文本的值不落库（可能含 PII）', () => {
    expect(isSafeFilterValue('张伟')).toBe(false);
    expect(isSafeFilterValue('carrier name')).toBe(false);
    expect(isSafeFilterValue('')).toBe(false);
    expect(pickPersistableFilters({ carrierCode: 'SF Express' }, ['carrierCode'])).toEqual({});
  });

  it('枚举 / 编号 / 日期 / UUID 是安全 token', () => {
    for (const value of ['PENDING', 'OUT_FOR_DELIVERY', '2026-10-01', 'SF', '3f1b7a52-0c4d-4a1e']) {
      expect(isSafeFilterValue(value)).toBe(true);
    }
  });

  it('看起来像凭据的值不落库', () => {
    expect(isSafeFilterValue('Bearer abcdefghijklmnop')).toBe(false);
    expect(isSafeFilterValue('password=hunter2')).toBe(false);
  });

  it('恢复时也只认白名单里的键', () => {
    const state = resolveListPreferences(
      { filters: { groupBy: 'WAREHOUSE', keyword: '张三', carrierCode: 'SF' } },
      OPTIONS,
    );
    expect(state.filters).toEqual({ groupBy: 'WAREHOUSE', carrierCode: 'SF' });
  });
});

describe('1.4.1.2 buildPreferencePayload：只写页面声明过的东西', () => {
  it('写入可见列、保存时的列集合、排序与每页条数', () => {
    const payload = buildPreferencePayload(
      {
        columns: ['outbound', 'sla'],
        sortKey: 'SLA_DEADLINE_AT_ASC',
        pageSize: 50,
        filters: { groupBy: 'CARRIER' },
      },
      OPTIONS,
    );
    expect(payload.columns).toEqual(['outbound', 'sla']);
    expect(payload.columnSet).toEqual(['outbound', 'warehouse', 'carrier', 'sla']);
    expect(payload.sortKey).toBe('SLA_DEADLINE_AT_ASC');
    expect(payload.pageSize).toBe(50);
    expect(payload.filters).toEqual({ groupBy: 'CARRIER' });
  });

  it('这一页没有可选每页条数时不写 pageSize', () => {
    const payload = buildPreferencePayload(
      { columns: ['outbound'], sortKey: '', pageSize: 20, filters: {} },
      { ...OPTIONS, pageSizeOptions: [] },
    );
    expect(payload.pageSize).toBeUndefined();
  });

  it('没有可持久化的筛选条件时完全不写 filters 这一项', () => {
    const payload = buildPreferencePayload(
      { columns: ['outbound'], sortKey: '', pageSize: 20, filters: { keyword: '张三' } },
      OPTIONS,
    );
    expect(payload.filters).toBeUndefined();
  });
});

describe('1.4.1.2 常量', () => {
  it('每页条数的可选值是一组有限值', () => {
    expect(PAGE_SIZE_OPTIONS).toEqual([10, 20, 50, 100]);
  });
});

describe('1.4.1.2 describePreference：设置页读得懂的人话', () => {
  it('把载荷渲染成摘要行', () => {
    expect(
      describePreference({
        columns: ['outbound', 'sla'],
        columnSet: ['outbound', 'sla'],
        sortKey: 'PACKED_AT_DESC',
        pageSize: 50,
        filters: { groupBy: 'CARRIER' },
      }),
    ).toEqual([
      '列显示：outbound、sla',
      '排序：PACKED_AT_DESC',
      '每页：50 条',
      '筛选：groupBy=CARRIER',
    ]);
  });

  it('载荷不是对象时返回空摘要而不是抛异常', () => {
    expect(describePreference(null)).toEqual([]);
    expect(describePreference('x')).toEqual([]);
  });
});
