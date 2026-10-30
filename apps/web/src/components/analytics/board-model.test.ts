import { describe, expect, it } from 'vitest';
import {
  chartTypeLabel,
  componentValueNote,
  componentValueText,
  dataUpdatedText,
  GRID_COLUMNS,
  gridColumnStart,
  gridRowStart,
  layoutChanged,
  layoutError,
  layoutText,
  listEmptyText,
  metricLabel,
  moveDown,
  moveLeft,
  moveRight,
  moveUp,
  pageState,
  permissionFilterNote,
  requiredPermissionLabel,
  shareScopeLabel,
  shareTargetLabel,
  sortByLayout,
  summaryText,
  swapLayouts,
  toBoardSaveRequest,
  toComponentSaveRequest,
  toLayoutRequest,
  toShareSaveRequest,
  visibilityLabel,
  viewEmptyText,
  workbenchEmptyText,
} from './board-model';
import type {
  BoardComponentValue,
  BoardComponentView,
  BoardLayoutView,
  BoardPage,
  BoardSummary,
  BoardViewResponse,
  BoardWorkbenchResponse,
} from './board-model';

/**
 * 13.6.1.2 看板搭建 前端模型。
 *
 * 🔴 这些断言盯的是**页面最容易说错的几件事**：
 *
 * 1. **「算不出来」≠「数据是 0」**。服务端给 `null` 的取值必须渲染成「—」并**带上原因**，
 *    **不是** 0% / 0.00。分母为 0 的比率显示成 0% 会被读成「效率为零」。
 * 2. **多币种绝不相加**。一个维度取值下可能有多个币种，**分行**显示。
 * 3. **权限过滤是服务端的事**，但页面必须把「你看得见几个 / 一共几个」如实说出来。
 * 4. **非法布局当场报错**（与服务端同一条规则），**不静默丢弃**。
 * 5. **「读不到」与「没有数据」分开**。
 */

function value(overrides: Partial<BoardComponentValue> = {}): BoardComponentValue {
  return {
    currency: 'USD',
    value: 100,
    availability: 'AVAILABLE',
    availabilityNote: '',
    ...overrides,
  };
}

function component(
  overrides: Partial<BoardComponentView> = {},
): BoardComponentView {
  return {
    componentId: 'c1',
    boardId: 'b1',
    metricCode: 'SALES_AMOUNT',
    metricDisplayName: '销售额',
    unit: 'CURRENCY',
    currencyAware: true,
    dimensionCode: 'SHOP',
    chartType: 'BAR',
    layout: { x: 0, y: 0, w: 3, h: 2 },
    filter: [],
    requiredPermission: '',
    sortOrder: 0,
    createdAt: '',
    updatedAt: '',
    ...overrides,
  };
}

function summary(overrides: Partial<BoardSummary> = {}): BoardSummary {
  return {
    boardId: 'b1',
    boardCode: 'B-1',
    displayName: '看板一',
    description: '',
    visibility: 'TEAM',
    ownerUserId: 'u1',
    ownedByMe: true,
    componentCount: 3,
    visibleComponentCount: 2,
    shareCount: 0,
    updatedAt: '',
    ...overrides,
  };
}

describe('13.6.1.2 看板搭建 · 前端模型', () => {
  // ================================================================ 不可比 ≠ 0

  it('算不出来的取值显示「—」而不是 0', () => {
    expect(componentValueText(value({ value: null, availability: 'NOT_COMPARABLE' }), 'CURRENCY')).toBe(
      '—',
    );
    expect(componentValueText(value({ value: null, availability: 'NO_DATA' }), 'PERCENT')).toBe('—');
  });

  it('可用的取值带单位与币种', () => {
    expect(componentValueText(value({ value: 1234.5 }), 'CURRENCY')).toBe('1,234.50 USD');
    expect(componentValueText(value({ value: 60, currency: '' }), 'PERCENT')).toBe('60.00%');
    expect(componentValueText(value({ value: 12.345678, currency: '' }), 'QUANTITY')).toBe('12.346');
    expect(componentValueText(value({ value: 7, currency: '' }), 'COUNT')).toBe('7');
  });

  it('不可比与没有数据的原因分开说', () => {
    expect(
      componentValueNote(
        value({ value: null, availability: 'NOT_COMPARABLE', availabilityNote: '分母为 0' }),
      ),
    ).toBe('分母为 0');
    expect(
      componentValueNote(
        value({ value: null, availability: 'NO_DATA', availabilityNote: '没有事实数据' }),
      ),
    ).toBe('没有事实数据');
    expect(componentValueNote(value())).toBe('');
  });

  // ================================================================ 布局

  it('非法布局当场报错（与服务端同一条规则）', () => {
    expect(layoutError({ x: -1, y: 0, w: 3, h: 2 })).toContain('负数');
    expect(layoutError({ x: 0, y: 0, w: 0, h: 2 })).toContain('1×1');
    expect(layoutError({ x: 10, y: 0, w: 4, h: 2 })).toContain('超出看板宽度');
    expect(layoutError({ x: 0, y: 0, w: 3, h: 2 })).toBe('');
  });

  it('布局越界时挪不动，而不是被静默丢弃', () => {
    const left = { x: 0, y: 0, w: 3, h: 2 };
    expect(moveLeft(left)).toEqual(left);
    expect(moveRight({ x: GRID_COLUMNS - 3, y: 0, w: 3, h: 2 })).toEqual({
      x: GRID_COLUMNS - 3,
      y: 0,
      w: 3,
      h: 2,
    });
    expect(moveRight(left).x).toBe(1);
    expect(moveUp(left)).toEqual(left);
    expect(moveDown(left).y).toBe(1);
  });

  it('拖拽落位是交换两个组件的位置', () => {
    const a = component({ componentId: 'a', layout: { x: 0, y: 0, w: 3, h: 2 } });
    const b = component({ componentId: 'b', layout: { x: 6, y: 0, w: 3, h: 2 } });
    const swapped = swapLayouts([a, b], 'a', 'b');
    expect(swapped.find((c) => c.componentId === 'a')?.layout).toEqual({ x: 6, y: 0, w: 3, h: 2 });
    expect(swapped.find((c) => c.componentId === 'b')?.layout).toEqual({ x: 0, y: 0, w: 3, h: 2 });
  });

  it('拖到自己身上不改变任何东西', () => {
    const a = component({ componentId: 'a', layout: { x: 0, y: 0, w: 3, h: 2 } });
    expect(swapLayouts([a], 'a', 'a')).toEqual([a]);
  });

  it('布局按 y 再按 x 排序', () => {
    const a = component({ componentId: 'a', layout: { x: 3, y: 0, w: 3, h: 2 } });
    const b = component({ componentId: 'b', layout: { x: 0, y: 0, w: 3, h: 2 } });
    const c = component({ componentId: 'c', layout: { x: 0, y: 4, w: 3, h: 2 } });
    expect(sortByLayout([a, b, c]).map((item) => item.componentId)).toEqual(['b', 'a', 'c']);
  });

  it('保存布局的请求体带上位置与顺序', () => {
    const request = toLayoutRequest([
      component({ componentId: 'b', layout: { x: 6, y: 0, w: 3, h: 2 } }),
      component({ componentId: 'a', layout: { x: 0, y: 0, w: 6, h: 2 } }),
    ]);
    expect(request.components.map((item) => item.componentId)).toEqual(['a', 'b']);
    expect(request.components[0]).toMatchObject({ x: 0, y: 0, w: 6, h: 2, sortOrder: 0 });
  });

  it('布局没改就不算改（用来决定要不要提示「未保存」）', () => {
    const a = component();
    expect(layoutChanged([a], [component()])).toBe(false);
    expect(layoutChanged([a], [component({ layout: { x: 1, y: 0, w: 3, h: 2 } })])).toBe(true);
    expect(layoutChanged([a], [])).toBe(true);
  });

  it('布局的展示文本与 CSS Grid 位置', () => {
    const layout: BoardLayoutView = { x: 2, y: 1, w: 3, h: 2 };
    expect(layoutText(layout)).toBe('第 3 列起 · 宽 3 × 高 2');
    expect(gridColumnStart(layout)).toBe(3);
    expect(gridRowStart(layout)).toBe(2);
  });

  // ================================================================ 权限过滤

  it('权限过滤要如实说出「看得见几个 / 一共几个」', () => {
    expect(permissionFilterNote(2, 3)).toContain('只看得见 2 个');
    expect(permissionFilterNote(3, 3)).toContain('都能看见');
    expect(permissionFilterNote(0, 0)).toContain('还没有组件');
  });

  it('组件的权限标签：空串 = 所有人可见', () => {
    expect(requiredPermissionLabel('')).toBe('所有人可见');
    expect(requiredPermissionLabel('reporting.profit.read')).toContain('reporting.profit.read');
  });

  it('共享的目标与范围标签', () => {
    expect(
      shareTargetLabel({
        shareId: 's1',
        boardId: 'b1',
        roleId: 'r1',
        permissionCode: '',
        scope: 'ROLE',
        createdBy: 'u1',
        createdAt: '',
        revokedAt: null,
      }),
    ).toContain('角色');
    expect(
      shareTargetLabel({
        shareId: 's1',
        boardId: 'b1',
        roleId: null,
        permissionCode: 'reporting.profit.read',
        scope: 'ROLE',
        createdBy: 'u1',
        createdAt: '',
        revokedAt: null,
      }),
    ).toContain('reporting.profit.read');
    expect(shareScopeLabel('TENANT')).toBe('租户内成员');
    expect(shareScopeLabel('ROLE')).toBe('仅命中角色的人');
  });

  // ================================================================ 页面状态

  it('「读不到」与「没有数据」是两种状态', () => {
    expect(pageState(null, 'boom')).toBe('error');
    expect(pageState(null, '')).toBe('loading');
    expect(pageState({ status: 'NO_DATA' } as unknown as BoardViewResponse, '')).toBe('empty');
    expect(pageState({ status: 'AVAILABLE' } as unknown as BoardViewResponse, '')).toBe('ready');
  });

  it('空态文案区分「没组件」与「没数据」', () => {
    expect(viewEmptyText({ components: [] } as unknown as BoardViewResponse)).toContain(
      '一个组件都没有',
    );
    expect(
      viewEmptyText({
        components: [{ emptyReason: 'NO_FACTS', emptyNote: '这个窗口没有事实' }],
        statusNote: '',
      } as unknown as BoardViewResponse),
    ).toContain('这个窗口没有事实');
  });

  it('清单空态区分「筛选无结果」与「还没建过看板」', () => {
    expect(listEmptyText({ keyword: 'x', visibilityFilter: '' } as unknown as BoardPage)).toContain(
      '筛选条件',
    );
    expect(listEmptyText({ keyword: '', visibilityFilter: '' } as unknown as BoardPage)).toContain(
      '还没有建过看板',
    );
  });

  it('工作台空态说清楚「还没有能打开的看板」', () => {
    expect(workbenchEmptyText({ boards: [] } as unknown as BoardWorkbenchResponse)).toContain(
      '能打开的看板',
    );
  });

  it('数据更新时间常驻，空表时不是假时间', () => {
    expect(dataUpdatedText(null)).toBe('');
    expect(dataUpdatedText({ dataUpdatedAt: null } as unknown as BoardViewResponse)).toContain(
      '尚未投影',
    );
    expect(
      dataUpdatedText({
        dataUpdatedAt: '2026-10-29T10:00:00Z',
      } as unknown as BoardViewResponse),
    ).toContain('数据截至');
  });

  // ================================================================ 标签

  it('可见范围与图表类型的中文标签', () => {
    expect(visibilityLabel('TEAM')).toBe('团队可见');
    expect(visibilityLabel('PRIVATE')).toBe('仅自己可见');
    expect(chartTypeLabel('BAR')).toBe('柱状图');
    expect(chartTypeLabel('LINE')).toBe('折线图');
    expect(chartTypeLabel('PIE')).toBe('饼图');
    expect(chartTypeLabel('TABLE')).toBe('表格');
  });

  it('清单一行的摘要含「可见 / 总数」', () => {
    expect(summaryText(summary())).toContain('2/3 个组件可见');
  });

  it('指标找不到时退回指标码，不编一个名字', () => {
    expect(metricLabel(undefined, 'SALES_AMOUNT')).toBe('SALES_AMOUNT');
    expect(
      metricLabel(
        { metricCode: 'SALES_AMOUNT', displayName: '销售额' } as never,
        'SALES_AMOUNT',
      ),
    ).toBe('销售额');
  });

  // ================================================================ 请求体

  it('保存请求把空串转成 null（日期、valueText2）', () => {
    const request = toBoardSaveRequest({
      boardCode: ' B-1 ',
      displayName: ' 看板一 ',
      description: ' 说明 ',
      visibility: 'TEAM',
      defaultFilters: [
        { dimensionCode: 'SHOP', operator: 'EQ', valueText: 'x', valueText2: '' },
      ],
      defaultWindowFrom: '',
      defaultWindowTo: '',
    });
    expect(request.boardCode).toBe('B-1');
    expect(request.displayName).toBe('看板一');
    expect(request.defaultWindowFrom).toBeNull();
    expect(request.defaultFilters[0]?.valueText2).toBeNull();
  });

  it('组件请求带布局与所需权限码', () => {
    const request = toComponentSaveRequest({
      metricCode: 'SALES_AMOUNT',
      dimensionCode: 'SHOP',
      chartType: 'BAR',
      layout: { x: 1, y: 2, w: 3, h: 2 },
      filter: [],
      requiredPermission: ' reporting.profit.read ',
    });
    expect(request.layout).toEqual({ x: 1, y: 2, w: 3, h: 2 });
    expect(request.requiredPermission).toBe('reporting.profit.read');
    expect(request.sortOrder).toBeNull();
  });

  it('共享请求里角色与权限码都是字符串（空 = null）', () => {
    expect(toShareSaveRequest({ roleId: '', permissionCode: 'r.read', scope: 'ROLE' })).toEqual({
      roleId: null,
      permissionCode: 'r.read',
      scope: 'ROLE',
    });
  });
});
