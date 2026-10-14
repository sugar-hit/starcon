import { describe, expect, it } from 'vitest';
import {
  bulkOperationDisabled,
  documentStatusLabels,
  fetchOrderList,
  formatAmount,
  formatTime,
  listColumns,
  pendingCountText,
  salesOrderListQuery,
  sortLabels,
  summaryLine,
  unavailableCell,
} from './order-list-model';

/**
 * 9.1.1.1 订单列表前端模型。
 *
 * <p>本测试的重点**不是**「查询串拼对了」（那是 vitest 替代不了 typecheck 的部分），
 * 而是本模块真正在守的那条纪律：<b>拿不到的维度必须如实显示「—」，
 * 不能编一个像样的值出来</b>。
 *
 * <p>如果哪天有人把 fulfillmentStatus 填成 documentStatus（拿 DRAFT/SUBMITTED
 * 冒充履约状态），或者把 paymentStatus 填成 paymentMethod（拿付款方式冒充支付状态），
 * 这里会立刻红 —— 而只断言「页面能渲染列表」的测试会照样全绿。
 */

describe('查询串只拼真实存在的筛选维度', () => {
  it('空参数不产生查询串', () => {
    expect(salesOrderListQuery({})).toBe('');
  });

  it('逐条拼出可筛维度', () => {
    const qs = salesOrderListQuery({
      keyword: 'MO-1',
      documentStatus: 'DRAFT',
      currency: 'USD',
      orderSource: 'MANUAL',
      page: 2,
      size: 20,
    });
    expect(qs).toContain('keyword=MO-1');
    expect(qs).toContain('documentStatus=DRAFT');
    expect(qs).toContain('currency=USD');
    expect(qs).toContain('orderSource=MANUAL');
    expect(qs).toContain('page=2');
  });

  it('空白关键字不参与查询（避免拼出 keyword= 匹配一切）', () => {
    expect(salesOrderListQuery({ keyword: '   ' })).toBe('');
    expect(salesOrderListQuery({ buyerKeyword: '  ' })).toBe('');
  });

  it('值里的特殊字符被转义', () => {
    expect(salesOrderListQuery({ keyword: 'a&b=c' })).toContain('keyword=a%26b%3Dc');
  });

  it('请求打到与 9.1.2.2 并列的 list 子路径，不覆盖它', async () => {
    let called = '';
    const original = globalThis.fetch;
    globalThis.fetch = ((url: string) => {
      called = String(url);
      return Promise.resolve(new Response('{}'));
    }) as typeof fetch;
    try {
      await fetchOrderList({ keyword: 'MO-1' });
    } finally {
      globalThis.fetch = original;
    }
    // api() 会拼成 /api/<service>/<path>，所以完整形如
    // /api/trade/trade/sales-orders/list —— 断言的是**末尾那段**业务路径，
    // 它必须是 .../sales-orders/list（与 9.1.2.2 的 .../sales-orders 并列）。
    expect(called.endsWith('/trade/sales-orders/list?keyword=MO-1')).toBe(true);
    expect(called).not.toContain('sales-orders/list/list');
  });
});

describe('拿不到的维度如实显示「—」', () => {
  const dimension = {
    label: '支付状态',
    ownerModule: '12 财务（实收流水）',
    reason: 'sales_order 只有 payment_method（约定），不是实收。',
  };

  it('null 显示破折号而不是空白', () => {
    const cell = unavailableCell(null, dimension);
    expect(cell.text).toBe('—');
    // 空白单元格会被读成「这单没填支付信息」，破折号 + tooltip 才是「本服务没这个概念」。
    expect(cell.text).not.toBe('');
  });

  it('tooltip 讲清归属模块与原因', () => {
    const cell = unavailableCell(null, dimension);
    expect(cell.title).toContain('支付状态');
    expect(cell.title).toContain('12 财务');
    expect(cell.title).toContain('payment_method');
  });

  it('undefined 与空串同样按「不可得」处理', () => {
    expect(unavailableCell(undefined).text).toBe('—');
    expect(unavailableCell('').text).toBe('—');
  });

  it('真有值时原样显示', () => {
    expect(unavailableCell('已发货').text).toBe('已发货');
    expect(unavailableCell('已发货').title).toBe('');
  });

  it('六个不可得维度全部在列定义里占位 —— 缺席会被误读成「系统不管这件事」', () => {
    const unavailable = listColumns.filter((c) => c.unavailable).map((c) => c.key);
    expect(unavailable).toEqual([
      'platformStatus',
      'paymentStatus',
      'fulfillmentStatus',
      'shippingWarehouse',
      'aftersalesStatus',
    ]);
    // 单据状态必须在、且**不**标成 unavailable。
    const doc = listColumns.find((c) => c.key === 'documentStatus');
    expect(doc).toBeDefined();
    expect(doc?.unavailable).toBeFalsy();
  });
});

describe('单据状态不冒充履约状态', () => {
  it('标签只映射 DRAFT / SUBMITTED', () => {
    expect(Object.keys(documentStatusLabels).sort()).toEqual(['DRAFT', 'SUBMITTED']);
    // 任何「已发货 / 已签收 / 待发货」都**不在**这个词表里 ——
    // 本服务没有履约状态这个词表。
    expect(documentStatusLabels['SHIPPED']).toBeUndefined();
    expect(documentStatusLabels['FULFILLED']).toBeUndefined();
  });

  it('可排序维度只有四个，履约 / 支付不可排', () => {
    expect(Object.keys(sortLabels).sort()).toEqual([
      'AMOUNT_ASC',
      'AMOUNT_DESC',
      'ORDERED_AT_ASC',
      'ORDERED_AT_DESC',
    ]);
  });
});

describe('批量操作一律禁用并说明前置条件', () => {
  const op = {
    code: 'NOTE',
    label: '备注',
    available: false,
    ownerModule: '9.1.2.2 手工订单',
    precondition: '需要先明确写入所有者与并发语义',
    reason: '本模块是读投影，不抢写 sales_order.note。',
  };

  it('永远 disabled', () => {
    expect(bulkOperationDisabled(op).disabled).toBe(true);
    // 即使后端误报 available=true，前端也不给可点的按钮 ——
    // 本模块没有写端点，点下去只可能是 404。
    expect(bulkOperationDisabled({ ...op, available: true }).disabled).toBe(true);
  });

  it('tooltip 带归属、前置条件与原因', () => {
    const t = bulkOperationDisabled(op).title;
    expect(t).toContain('9.1.2.2');
    expect(t).toContain('前置条件');
    expect(t).toContain('读投影');
  });
});

describe('金额按币种分开，不跨币种相加', () => {
  it('格式化带币种', () => {
    expect(formatAmount('27.0000', 'USD')).toBe('27.00 USD');
    expect(formatAmount(27, 'EUR')).toBe('27.00 EUR');
  });

  it('缺失值显示破折号而不是 0', () => {
    // 金额取不到不是「这单是 0 元」，两者对账的含义完全不同。
    expect(formatAmount(null)).toBe('—');
    expect(formatAmount(undefined)).toBe('—');
    expect(formatAmount('')).toBe('—');
  });

  it('汇总行按币种并列，绝不合并成一个总额', () => {
    const line = summaryLine(3, [
      { currency: 'USD', amount: 100, orderCount: 2 },
      { currency: 'EUR', amount: 50, orderCount: 1 },
    ]);
    expect(line).toContain('3 张订单');
    expect(line).toContain('100.00 USD');
    expect(line).toContain('50.00 EUR');
    // 「150.00」这种跨币种加总绝不允许出现。
    expect(line).not.toContain('150');
  });

  it('没有金额时只报单数', () => {
    expect(summaryLine(0, [])).toBe('0 张订单');
    expect(summaryLine(2, undefined)).toBe('2 张订单');
  });
});

describe('待处理数不自造口径', () => {
  it('为 null 时说明暂无口径并给出归属', () => {
    const text = pendingCountText(null, '9.2 审单与异常');
    expect(text).toContain('暂无口径');
    expect(text).toContain('9.2');
    // 绝不能拿 DRAFT 的数量冒充「待处理数」。
    expect(text).not.toMatch(/\d+\s*张$/);
  });

  it('真有值时正常显示', () => {
    expect(pendingCountText(7, '9.2')).toBe('待处理 7 张');
  });
});

describe('时间展示', () => {
  it('合法 ISO 串转可读格式', () => {
    expect(formatTime('2026-01-15T08:30:00Z')).toMatch(/^2026-01-15/);
  });

  it('缺失显示破折号', () => {
    expect(formatTime(null)).toBe('—');
    expect(formatTime(undefined)).toBe('—');
  });

  it('解析不了时原样返回而不是显示 Invalid Date', () => {
    expect(formatTime('not-a-date')).toBe('not-a-date');
  });
});
