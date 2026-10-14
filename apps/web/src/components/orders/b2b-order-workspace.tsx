'use client';

import { useCallback, useEffect, useState } from 'react';
import { RefreshCw, TriangleAlert } from 'lucide-react';
import { Badge, Button, EmptyState, Input, PageHeader, SectionCard, StatCard } from '@starcon/ui';
import type {
  B2BCapabilities,
  B2BOrderPage,
  B2BOrderRow,
  B2BReceivablePage,
  B2BReceivableRow,
} from '../../../../../packages/api-contracts/src/index';
import {
  b2bCostBasisLabels,
  b2bCustomerLevelLabels,
  b2bInvoiceStateLabels,
  b2bOrderTypeLabels,
  b2bPaymentMethodLabels,
  b2bReceivableStatusLabels,
  b2bStatusLabels,
  fetchCapabilities,
  fetchOrders,
  fetchReceivables,
  formatDueDate,
} from './b2b-order-model';
import { errorText } from '../inventory/inventory-model';

/**
 * 9.7.1.1 批发订单与账期工作区。
 *
 * <p>页面刻意把**「未发货不产生应收」做成一眼能核对的事实**：列表里三列并排显示
 * 「订单总额 / 已确认应收 / 未发货」，而不是只显示一个总额让人猜钱在哪一环产生。
 * 这是蓝图对 B2B 最强调的一条（与零售「平台先收款后发货」现金流方向相反），
 * 界面不给它位置，它就会在口头传达里丢掉。
 *
 * <p>加载 / 空 / 错误三态齐全；下游未交付的环节写明归属模块，不给点下去 404 的按钮。
 */
export function B2BOrderWorkspace() {
  const [page, setPage] = useState<B2BOrderPage | null>(null);
  const [receivables, setReceivables] = useState<B2BReceivablePage | null>(null);
  const [capabilities, setCapabilities] = useState<B2BCapabilities | null>(null);
  const [status, setStatus] = useState('');
  const [paymentMethod, setPaymentMethod] = useState('');
  const [keyword, setKeyword] = useState('');
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      // 本仓库开了 exactOptionalPropertyTypes：可选字段**不能**显式传 undefined，
      // 必须条件展开 —— 传 undefined 会在编译期报错，而不是运行时被忽略。
      const [orders, ar, caps] = await Promise.all([
        fetchOrders({
          ...(status ? { status } : {}),
          ...(paymentMethod ? { paymentMethod } : {}),
          ...(keyword.trim() ? { keyword } : {}),
          size: 50,
        }),
        fetchReceivables({ status: 'OPEN', size: 50 }),
        fetchCapabilities(),
      ]);
      setPage(orders);
      setReceivables(ar);
      setCapabilities(caps);
    } catch (e) {
      setError(errorText(e));
    } finally {
      setLoading(false);
    }
  }, [status, paymentMethod, keyword]);

  useEffect(() => {
    void load();
  }, [load]);

  return (
    <div className="space-y-4">
      <PageHeader
        title="批发订单与账期"
        description="B2B 订单：客户分级价、起订量、阶梯价、账期结算。订单类型 / 收款方式 / 成本口径与零售分开统计，未发货不产生应收，发货确认才按账期生成。"
        actions={
          <Button variant="outline" onClick={() => void load()} disabled={loading}>
            <RefreshCw className="mr-2 h-4 w-4" />
            刷新
          </Button>
        }
      />

      {error && (
        <div className="border-destructive/40 bg-destructive/5 text-destructive flex items-center gap-2 rounded-md border px-3 py-2 text-sm">
          <TriangleAlert className="h-4 w-4 shrink-0" />
          {error}
        </div>
      )}

      {capabilities && !capabilities.upstreamPriceBookAvailable && (
        <div className="text-copy-12 text-muted-foreground rounded-md border px-3 py-2">
          05 价目 / 阶梯价（5.1.1.2，D6）未交付：阶梯价目前由 trade 侧的价目投影端点维护
          （source_system = projection）。05 价目交付后由事件同步接管同两张表，
          本页与后端的读法都不需要改。
        </div>
      )}

      {capabilities && !capabilities.downstreamFulfillmentAvailable && (
        <div className="text-copy-12 text-muted-foreground rounded-md border px-3 py-2">
          9.3 履约未接入：批发订单目前不进入拣货与发货编排，发货由本模块的发货确认端点登记。
          账期从发货确认那天起算。
        </div>
      )}

      <div className="grid gap-4 sm:grid-cols-4">
        <StatCard label="批发订单" value={page?.total ?? 0} />
        <StatCard label="已确认待发" value={page?.confirmed ?? 0} />
        <StatCard label="未结应收" value={receivables?.openAmount ?? 0} />
        <StatCard label="已逾期（到期日已过仍未结）" value={receivables?.overdueAmount ?? 0} />
      </div>

      <SectionCard
        title="批发订单"
        description="「订单总额 / 已确认应收 / 未发货」三列并排：未发货的部分不产生应收。"
      >
        <div className="mb-4 flex flex-wrap items-end gap-2">
          <div className="flex flex-col gap-1">
            <span className="text-copy-12 text-muted-foreground">关键字</span>
            <Input
              value={keyword}
              placeholder="订单号 / 备注"
              onChange={(e) => setKeyword(e.target.value)}
            />
          </div>
          <div className="flex flex-col gap-1">
            <span className="text-copy-12 text-muted-foreground">状态</span>
            <select
              className="border-border bg-card text-copy-13 h-9 rounded-md border px-2"
              value={status}
              onChange={(e) => setStatus(e.target.value)}
            >
              <option value="">全部</option>
              {Object.entries(b2bStatusLabels).map(([v, l]) => (
                <option key={v} value={v}>
                  {l}
                </option>
              ))}
            </select>
          </div>
          <div className="flex flex-col gap-1">
            <span className="text-copy-12 text-muted-foreground">收款方式</span>
            <select
              className="border-border bg-card text-copy-13 h-9 rounded-md border px-2"
              value={paymentMethod}
              onChange={(e) => setPaymentMethod(e.target.value)}
            >
              <option value="">全部</option>
              {Object.entries(b2bPaymentMethodLabels).map(([v, l]) => (
                <option key={v} value={v}>
                  {l}
                </option>
              ))}
            </select>
          </div>
        </div>

        {loading ? (
          <p className="text-copy-13 text-muted-foreground py-8 text-center">加载中…</p>
        ) : !page || page.items.length === 0 ? (
          <EmptyState
            title="还没有批发订单"
            description="批发订单需要先有客户档案投影（分级与账期）与阶梯价投影，再录入订单。"
          />
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full text-left text-sm">
              <thead className="text-copy-12 text-muted-foreground border-b">
                <tr>
                  <th className="px-2 py-2 font-medium">订单号</th>
                  <th className="px-2 py-2 font-medium">类型</th>
                  <th className="px-2 py-2 font-medium">客户 / 分级</th>
                  <th className="px-2 py-2 font-medium">收款方式</th>
                  <th className="px-2 py-2 font-medium">成本口径</th>
                  <th className="px-2 py-2 text-right font-medium">订单总额</th>
                  <th className="px-2 py-2 text-right font-medium">已确认应收</th>
                  <th className="px-2 py-2 text-right font-medium">未发货</th>
                  <th className="px-2 py-2 font-medium">状态</th>
                </tr>
              </thead>
              <tbody>
                {page.items.map((row: B2BOrderRow) => (
                  <tr key={row.id} className="border-b last:border-0">
                    <td className="px-2 py-2 font-medium">{row.orderNo}</td>
                    <td className="px-2 py-2">
                      {b2bOrderTypeLabels[row.orderType] ?? row.orderType}
                    </td>
                    <td className="px-2 py-2">
                      {row.customerName ?? '—'}
                      <span className="text-muted-foreground">
                        {' '}
                        / {b2bCustomerLevelLabels[row.customerLevel] ?? row.customerLevel}
                      </span>
                    </td>
                    <td className="px-2 py-2">
                      {b2bPaymentMethodLabels[row.paymentMethod]?.split('（')[0] ??
                        row.paymentMethod}
                      {row.paymentTermsDays > 0 && ` · ${row.paymentTermsDays} 天`}
                    </td>
                    <td className="px-2 py-2">
                      {b2bCostBasisLabels[row.costBasis] ?? row.costBasis}
                    </td>
                    <td className="px-2 py-2 text-right tabular-nums">
                      {moneyOf(row.totalAmount)}
                    </td>
                    <td className="px-2 py-2 text-right tabular-nums">
                      {moneyOf(row.receivableAmount)}
                    </td>
                    <td className="text-muted-foreground px-2 py-2 text-right tabular-nums">
                      {moneyOf(row.unshippedAmount)}
                    </td>
                    <td className="px-2 py-2">
                      <Badge tone={row.status === 'DRAFT' ? 'neutral' : 'primary'}>
                        {b2bStatusLabels[row.status] ?? row.status}
                      </Badge>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </SectionCard>

      <SectionCard
        title="账期应收"
        description="按到期日排序（资金计划按它排）。应收只在发货确认时产生；核销属于 12 财务，未交付。"
      >
        {loading ? (
          <p className="text-copy-13 text-muted-foreground py-8 text-center">加载中…</p>
        ) : !receivables || receivables.items.length === 0 ? (
          <EmptyState
            title="暂无未结应收"
            description="未发货的批发订单不产生应收：账期从发货确认那一刻起算。"
          />
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full text-left text-sm">
              <thead className="text-copy-12 text-muted-foreground border-b">
                <tr>
                  <th className="px-2 py-2 font-medium">应收单号</th>
                  <th className="px-2 py-2 font-medium">订单</th>
                  <th className="px-2 py-2 font-medium">收款方式</th>
                  <th className="px-2 py-2 text-right font-medium">金额</th>
                  <th className="px-2 py-2 font-medium">到期日</th>
                  <th className="px-2 py-2 font-medium">状态</th>
                  <th className="px-2 py-2 font-medium">开票需求</th>
                </tr>
              </thead>
              <tbody>
                {receivables.items.map((row: B2BReceivableRow) => (
                  <tr key={row.id} className="border-b last:border-0">
                    <td className="px-2 py-2 font-medium">{row.receivableNo}</td>
                    <td className="px-2 py-2">{row.orderNo ?? '—'}</td>
                    <td className="px-2 py-2">
                      {b2bPaymentMethodLabels[row.paymentMethod]?.split('（')[0] ??
                        row.paymentMethod}
                      {row.paymentTermsDays > 0 && ` · ${row.paymentTermsDays} 天`}
                    </td>
                    <td className="px-2 py-2 text-right tabular-nums">{moneyOf(row.amount)}</td>
                    <td className="px-2 py-2">{formatDueDate(row.dueDate)}</td>
                    <td className="px-2 py-2">
                      {b2bReceivableStatusLabels[row.status] ?? row.status}
                    </td>
                    <td className="px-2 py-2">
                      {b2bInvoiceStateLabels[row.invoiceState] ?? row.invoiceState}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </SectionCard>
    </div>
  );
}

/** 金额展示：金额一律以字符串从后端来（避免前端浮点），这里只补千分位与对齐。 */
function moneyOf(value: string | number): string {
  const text = typeof value === 'number' ? value.toFixed(2) : value;
  const [whole = '0', fraction] = String(text).split('.');
  const grouped = whole.replace(/\B(?=(\d{3})+(?!\d))/g, ',');
  return fraction ? `${grouped}.${fraction}` : grouped;
}
