import { describe, expect, it } from 'vitest';
import {
  NO_DATA_TEXT,
  arrivalStatusLabel,
  batchResultText,
  closePurchaseOrders,
  dataScopeLabel,
  fmtAmount,
  fmtDate,
  fmtQty,
  fmtRatio,
  isCloseable,
  isUrgeable,
  orderStatusLabel,
  orderStatusTone,
  overdueText,
  purchaseOrderListQuery,
  receivedSourceLabel,
  urgePurchaseOrders,
  type PurchaseOrderListParams,
} from './purchase-order-list-model';

/**
 * 6.3.1.1 采购单列表前端模型的纯函数测试。
 *
 * <p>覆盖的是**页面真的会依赖的口径**，不是覆盖率：
 *
 * <ul>
 *   <li><b>null ≠ 0</b>：已收数量 / 比例 / 金额为 null 时一律给「无数据」，
 *       绝不显示 0 —— 「还没登记到货」与「一件都没到」对采购员的动作含义相反。</li>
 *   <li><b>数据范围常驻</b>：ALL / DEPARTMENT / SELF 三种范围各有各的文案，
 *       页面要能讲清「我为什么只看到这几张单」。</li>
 *   <li><b>批量逐单成败</b>：失败原因要能原样透出，不能只剩一个布尔。</li>
 * </ul>
 */

describe('查询串拼装', () => {
  it('空参数不带问号', () => {
    expect(purchaseOrderListQuery({})).toBe('');
  });

  it('只拼给定的参数，顺序稳定', () => {
    const params: PurchaseOrderListParams = {
      status: 'DRAFT',
      supplierRefId: 's-1',
      currency: 'USD',
      page: 0,
      size: 20,
    };
    expect(purchaseOrderListQuery(params)).toBe(
      '?supplierRefId=s-1&status=DRAFT&currency=USD&page=0&size=20',
    );
  });

  it('关键字会去掉首尾空白，空串不拼', () => {
    expect(purchaseOrderListQuery({ keyword: '  PO-1  ' })).toBe('?keyword=PO-1');
    expect(purchaseOrderListQuery({ keyword: '   ' })).toBe('');
  });

  it('只看逾期用布尔真值，false 不拼（后端据此判定「不筛逾期」）', () => {
    expect(purchaseOrderListQuery({ overdueOnly: true })).toBe('?overdueOnly=true');
    expect(purchaseOrderListQuery({ overdueOnly: false })).toBe('');
  });

  it('日期区间与承诺到货日区间各拼各的', () => {
    expect(
      purchaseOrderListQuery({
        createdFrom: '2026-10-01T00:00:00Z',
        promisedFrom: '2026-10-05',
        promisedTo: '2026-10-20',
      }),
    ).toBe(
      '?createdFrom=2026-10-01T00%3A00%3A00Z&promisedFrom=2026-10-05&promisedTo=2026-10-20',
    );
  });
});

describe('状态与标签', () => {
  it('采购单状态的标签与后端枚举一一对应', () => {
    expect(orderStatusLabel('DRAFT')).toBe('草稿');
    expect(orderStatusLabel('PENDING_APPROVAL')).toBe('待审批');
    expect(orderStatusLabel('APPROVED')).toBe('已审批');
    expect(orderStatusLabel('REJECTED')).toBe('已驳回');
    expect(orderStatusLabel('IN_FULFILLMENT')).toBe('执行中');
    expect(orderStatusLabel('CLOSED')).toBe('已关闭');
  });

  it('未知状态原样透出，不编一个标签', () => {
    expect(orderStatusLabel('SOMETHING_NEW')).toBe('SOMETHING_NEW');
  });

  it('已驳回是危险色，已关闭是中性色', () => {
    expect(orderStatusTone('REJECTED')).toBe('danger');
    expect(orderStatusTone('PENDING_APPROVAL')).toBe('warning');
    expect(orderStatusTone('CLOSED')).toBe('neutral');
  });

  it('到货状态原样透传（6.3.2.2 的口径，本模块不重新判定）', () => {
    expect(arrivalStatusLabel('PARTIALLY_RECEIVED')).toBe('部分到货');
    expect(arrivalStatusLabel('FULLY_RECEIVED')).toBe('全部到货');
    expect(arrivalStatusLabel(null)).toBe('无数据');
  });
});

describe('null 与 0 不折叠', () => {
  it('未跟踪的已收数量显示「无数据」而不是 0', () => {
    expect(fmtQty(null)).toBe(NO_DATA_TEXT);
    expect(fmtQty(undefined)).toBe(NO_DATA_TEXT);
    expect(fmtQty(0)).toBe('0');
  });

  it('已收比例为 null 时不是 0%', () => {
    expect(fmtRatio(null)).toBe(NO_DATA_TEXT);
    expect(fmtRatio(0)).toBe('0.0%');
    expect(fmtRatio(0.4)).toBe('40.0%');
  });

  it('金额为 null 时不是 0', () => {
    expect(fmtAmount(null)).toBe(NO_DATA_TEXT);
    expect(fmtAmount(0)).toBe('0.00');
  });

  it('交期未定说「未定」', () => {
    expect(fmtDate(null)).toBe('未定');
    expect(fmtDate('2026-10-20')).toBe('2026-10-20');
  });

  it('已收数量的来源标签能区分「有流水撑着」与「还没登记」', () => {
    expect(receivedSourceLabel('ARRIVAL_TRACKING')).toBe('到货跟踪');
    expect(receivedSourceLabel('NOT_TRACKED')).toBe('未跟踪');
  });
});

describe('逾期标记', () => {
  it('不逾期给「否」而不是空白', () => {
    expect(overdueText({ overdue: false, overdueDays: null })).toBe('否');
  });

  it('逾期时把天数一起给出', () => {
    expect(overdueText({ overdue: true, overdueDays: 3 })).toBe('是（逾期 3 天）');
  });

  it('逾期但天数取不到时不编一个天数', () => {
    expect(overdueText({ overdue: true, overdueDays: null })).toBe('是');
  });
});

describe('数据范围', () => {
  it('三种范围各有各的文案', () => {
    expect(dataScopeLabel('ALL')).toBe('全租户');
    expect(dataScopeLabel('DEPARTMENT')).toBe('本部门');
    expect(dataScopeLabel('SELF')).toBe('本人负责');
  });

  it('范围未知时说「未知范围」，不假装是全租户', () => {
    expect(dataScopeLabel(null)).toBe('未知范围');
    expect(dataScopeLabel('SOMETHING')).toBe('未知范围');
  });
});

describe('批量操作的前置条件', () => {
  it('已关闭的单不再催单也不再关', () => {
    expect(isUrgeable('CLOSED')).toBe(false);
    expect(isCloseable('CLOSED')).toBe(false);
    expect(isUrgeable('IN_FULFILLMENT')).toBe(true);
    expect(isCloseable('IN_FULFILLMENT')).toBe(true);
  });
});

describe('批量结果的逐单成败', () => {
  it('全成功时只报成功数', () => {
    expect(
      batchResultText({ requested: 2, succeeded: 2, failures: [], note: '' }),
    ).toBe('已处理 2 张采购单');
  });

  it('有失败时把原因原样带出来，而不是只留一个布尔', () => {
    const text = batchResultText({
      requested: 2,
      succeeded: 1,
      failures: [{ orderId: 'id-1', orderNo: 'PO-1', reason: '这张采购单已经关闭了' }],
      note: '',
    });
    expect(text).toContain('成功 1 张');
    expect(text).toContain('PO-1');
    expect(text).toContain('这张采购单已经关闭了');
  });
});

describe('调用点拼出的路径', () => {
  it('催单与关闭走各自的批量端点', async () => {
    const calls: string[] = [];
    const original = globalThis.fetch;
    globalThis.fetch = (async (input: RequestInfo | URL) => {
      calls.push(String(input));
      return new Response('{}', { status: 200 });
    }) as typeof fetch;
    try {
      await urgePurchaseOrders(['id-1'], '催一下');
      await closePurchaseOrders(['id-1'], '供应商停业');
    } finally {
      globalThis.fetch = original;
    }
    expect(calls[0]).toContain('/api/trade/trade/purchase-orders/batch/urge');
    expect(calls[1]).toContain('/api/trade/trade/purchase-orders/batch/close');
  });
});
