'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import { ClipboardList, RefreshCw, TriangleAlert } from 'lucide-react';
import { Badge, Button, EmptyState, Input, PageHeader, SectionCard, StatCard } from '@starcon/ui';
import type {
  SalesOrderListCapabilities,
  SalesOrderListDimension,
  SalesOrderListPage,
} from '../../../../../packages/api-contracts/src/index';
import {
  bulkOperationDisabled,
  documentStatusLabels,
  fetchOrderList,
  fetchOrderListCapabilities,
  fetchOrderListFilters,
  formatAmount,
  formatTime,
  listColumns,
  orderSourceLabels,
  pendingCountText,
  salesOrderListQuery,
  sortLabels,
  summaryLine,
  unavailableCell,
} from './order-list-model';

/**
 * 9.1.1.1 订单列表（全渠道统一订单列表）。
 *
 * <p>蓝图：「全渠道订单统一列表：订单号、平台店铺、下单时间、买家、SKU 行数、总金额/币种、
 * 支付状态、履约状态、发货仓、平台状态，支持多维筛选、导出与批量操作
 * （审单、备注、取消、发催付）。」
 *
 * <p><b>这个页面最重要的职责是「如实显示拿不到」，而不是「让表格看起来是满的」。</b>
 *
 * <ul>
 *   <li><b>六个不可得维度照常占列，显示「—」</b>，tooltip 里写明归属模块与原因。
 *       把列删掉，运营会以为这个系统不管这件事；填上 0 或「未支付」，运营会照着它做决策。
 *       两种都比「老实说还没有」危险。</li>
 *   <li><b>「单据状态」不叫「履约状态」</b>：DRAFT / SUBMITTED 说的是「有没有进审单队列」，
 *       放在履约那一列下面等于宣称两者是同一件事。</li>
 *   <li><b>批量操作按钮全部禁用并说明前置条件</b>（{@link bulkOperationDisabled}）：
 *       本模块是 sales_order 的读投影，没有写端点。</li>
 *   <li><b>汇总金额按币种分开</b>：USD 与 EUR 加起来不是任何金额。</li>
 * </ul>
 */
export function OrderListWorkspace() {
  const [params, setParams] = useState({
    keyword: '',
    orderSource: '',
    documentStatus: '',
    currency: '',
    sort: 'ORDERED_AT_DESC',
    page: 0,
    size: 20,
  });
  const [page, setPage] = useState<SalesOrderListPage | null>(null);
  const [caps, setCaps] = useState<SalesOrderListCapabilities | null>(null);
  const [options, setOptions] = useState<{ currencies: string[]; sorts: string[] }>({
    currencies: [],
    sorts: [],
  });
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      setPage(await fetchOrderList(params));
    } catch (e) {
      setError(e instanceof Error ? e.message : '订单列表加载失败');
    } finally {
      setLoading(false);
    }
  }, [params]);

  useEffect(() => {
    void load();
  }, [load]);

  useEffect(() => {
    void (async () => {
      try {
        setCaps(await fetchOrderListCapabilities());
        const filters = await fetchOrderListFilters();
        setOptions({
          currencies: filters.currencies ?? [],
          sorts: (filters.sorts ?? []).map((s) => s.code),
        });
      } catch {
        // 能力声明拿不到不影响列表本身，页面仍要能看单。
      }
    })();
  }, []);

  const dimensions = useMemo(() => {
    const map = new Map<string, SalesOrderListDimension>();
    for (const d of caps?.unavailableDimensions ?? []) map.set(d.field, d);
    return map;
  }, [caps]);

  const totalPages = page ? Math.max(1, Math.ceil(page.total / page.size)) : 1;

  return (
    <div className="flex flex-col gap-6">
      <PageHeader
        title="订单列表"
        description="全渠道统一订单列表。支付状态、履约状态、发货仓、平台状态、售后状态与平台店铺尚不可得，归属模块见下方「能力」。"
      />

      {error && (
        <div className="border-destructive-soft bg-destructive-soft text-destructive flex items-center gap-2 rounded-lg border px-4 py-3">
          <TriangleAlert className="h-4 w-4 shrink-0" />
          <span className="text-copy-13">{error}</span>
          <Button variant="outline" size="sm" onClick={() => void load()} className="ml-auto">
            重试
          </Button>
        </div>
      )}

      {/* 汇总条：订单数与金额可算；「待处理数」口径未定，如实说明而不拿 DRAFT 冒充。 */}
      <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
        <StatCard label="订单数" value={page?.summary.totalCount ?? 0} />
        <StatCard
          label="草稿"
          value={page?.summary.draftCount ?? 0}
          icon={<ClipboardList className="h-4 w-4" />}
        />
        <StatCard label="已提交待审" value={page?.summary.submittedCount ?? 0} />
        <StatCard
          label="待处理数"
          value={
            page ? pendingCountText(page.summary.pendingCount, page.summary.pendingCountOwner) : '—'
          }
        />
      </div>

      <SectionCard
        title="筛选"
        description="可筛的维度都对应真实存在的列或聚合。平台、店铺、支付状态、履约状态、发货仓、售后状态**刻意不提供**——它们在本服务不存在，给一个永远筛不出东西的下拉框会让人误以为该链路已通。"
      >
        <div className="flex flex-wrap items-end gap-3">
          <Input
            className="w-56"
            placeholder="单号 / 外部单号 / 买家名"
            value={params.keyword}
            onChange={(e) => setParams({ ...params, keyword: e.target.value, page: 0 })}
          />
          <select
            className="border-border bg-background text-copy-13 rounded-md border px-3 py-2"
            value={params.orderSource}
            onChange={(e) => setParams({ ...params, orderSource: e.target.value, page: 0 })}
          >
            <option value="">全部来源</option>
            {(caps?.availableDimensions ?? []).length > 0 &&
              Object.keys(orderSourceLabels).map((k) => (
                <option key={k} value={k}>
                  {orderSourceLabels[k]}
                </option>
              ))}
          </select>
          <select
            className="border-border bg-background text-copy-13 rounded-md border px-3 py-2"
            value={params.documentStatus}
            onChange={(e) => setParams({ ...params, documentStatus: e.target.value, page: 0 })}
          >
            <option value="">全部单据状态</option>
            {Object.keys(documentStatusLabels).map((k) => (
              <option key={k} value={k}>
                {documentStatusLabels[k]}
              </option>
            ))}
          </select>
          <select
            className="border-border bg-background text-copy-13 rounded-md border px-3 py-2"
            value={params.currency}
            onChange={(e) => setParams({ ...params, currency: e.target.value, page: 0 })}
          >
            <option value="">全部币种</option>
            {options.currencies.map((c) => (
              <option key={c} value={c}>
                {c}
              </option>
            ))}
          </select>
          <select
            className="border-border bg-background text-copy-13 rounded-md border px-3 py-2"
            value={params.sort}
            onChange={(e) => setParams({ ...params, sort: e.target.value, page: 0 })}
          >
            {(options.sorts.length ? options.sorts : Object.keys(sortLabels)).map((k) => (
              <option key={k} value={k}>
                {sortLabels[k] ?? k}
              </option>
            ))}
          </select>
          <Button variant="outline" size="sm" onClick={() => void load()}>
            <RefreshCw className="mr-2 h-4 w-4" />
            刷新
          </Button>
        </div>
      </SectionCard>

      <SectionCard
        title="批量操作"
        description="蓝图职责句里的四条批量操作当前全部不可用：本模块是 sales_order 的读投影，审单 / 取消 / 催付的归属模块尚未交付，备注的写入所有者是 9.1.2.2。"
        extra={
          <div className="flex flex-wrap gap-2">
            {(caps?.bulkOperations ?? []).map((op) => (
              <Button
                key={op.code}
                size="sm"
                variant="outline"
                disabled={bulkOperationDisabled(op).disabled}
                title={bulkOperationDisabled(op).title}
              >
                {op.label}
              </Button>
            ))}
          </div>
        }
      >
        <ul className="text-copy-13 text-muted-foreground flex flex-col gap-1">
          {(caps?.bulkOperations ?? []).map((op) => (
            <li key={op.code}>
              <span className="text-foreground font-medium">{op.label}</span>：归属 {op.ownerModule}{' '}
              —— {op.reason}
            </li>
          ))}
        </ul>
      </SectionCard>

      <SectionCard
        title="订单"
        description={
          page
            ? `${summaryLine(page.summary.totalCount, page.summary.amountsByCurrency)} · 数据范围 ${page.dataScope}`
            : undefined
        }
        extra={
          <div className="text-copy-13 text-muted-foreground flex items-center gap-2">
            <Button
              size="sm"
              variant="outline"
              disabled={page === null || params.page <= 0}
              onClick={() => setParams({ ...params, page: params.page - 1 })}
            >
              上一页
            </Button>
            <span>
              第 {params.page + 1} / {totalPages} 页
            </span>
            <Button
              size="sm"
              variant="outline"
              disabled={page === null || params.page + 1 >= totalPages}
              onClick={() => setParams({ ...params, page: params.page + 1 })}
            >
              下一页
            </Button>
          </div>
        }
      >
        {loading && !page ? (
          <p className="text-copy-13 text-muted-foreground py-8 text-center">加载中…</p>
        ) : page && page.rows.length === 0 ? (
          <EmptyState
            title="没有命中的订单"
            description="调整筛选条件后重试。空集表示「当前条件下没有订单」，不是「数据没接上」。"
          />
        ) : (
          <div className="overflow-x-auto">
            <table className="text-copy-13 w-full border-collapse text-left">
              <thead>
                <tr className="border-border border-b">
                  {listColumns.map((c) => (
                    <th key={c.key} className="text-muted-foreground px-3 py-2 font-medium">
                      {c.label}
                      {c.unavailable && (
                        <span className="text-muted-foreground ml-1 text-[10px]">（未接入）</span>
                      )}
                    </th>
                  ))}
                </tr>
              </thead>
              <tbody>
                {(page?.rows ?? []).map((row) => {
                  const platform = unavailableCell(
                    row.platformStatus,
                    dimensions.get('platformStatus'),
                  );
                  const payment = unavailableCell(
                    row.paymentStatus,
                    dimensions.get('paymentStatus'),
                  );
                  const fulfillment = unavailableCell(
                    row.fulfillmentStatus,
                    dimensions.get('fulfillmentStatus'),
                  );
                  const warehouse = unavailableCell(
                    row.shippingWarehouse,
                    dimensions.get('shippingWarehouse'),
                  );
                  const aftersales = unavailableCell(
                    row.aftersalesStatus,
                    dimensions.get('aftersalesStatus'),
                  );
                  return (
                    <tr key={row.id} className="border-border/60 border-b last:border-0">
                      <td className="px-3 py-2 font-mono">{row.orderNo}</td>
                      <td className="px-3 py-2">
                        <Badge tone="neutral">
                          {orderSourceLabels[row.orderSource] ?? row.orderSource}
                        </Badge>
                      </td>
                      <td className="px-3 py-2">{row.buyerName ?? '—'}</td>
                      <td className="px-3 py-2">
                        {row.lineCount}
                        {row.skuCodes.length > 0 && (
                          <span className="text-muted-foreground ml-1 text-[11px]">
                            {row.skuCodes.slice(0, 3).join(' / ')}
                          </span>
                        )}
                      </td>
                      <td className="px-3 py-2">
                        {formatAmount(row.grandTotalAmount, row.currency)}
                      </td>
                      <td className="px-3 py-2">
                        {documentStatusLabels[row.documentStatus] ?? row.documentStatus}
                      </td>
                      <td className="px-3 py-2">{formatTime(row.createdAt)}</td>
                      <td className="text-muted-foreground px-3 py-2" title={platform.title}>
                        {platform.text}
                      </td>
                      <td className="text-muted-foreground px-3 py-2" title={payment.title}>
                        {payment.text}
                      </td>
                      <td className="text-muted-foreground px-3 py-2" title={fulfillment.title}>
                        {fulfillment.text}
                      </td>
                      <td className="text-muted-foreground px-3 py-2" title={warehouse.title}>
                        {warehouse.text}
                      </td>
                      <td className="text-muted-foreground px-3 py-2" title={aftersales.title}>
                        {aftersales.text}
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        )}
        {page && <p className="text-copy-13 text-muted-foreground mt-3">{page.dataScopeNote}</p>}
      </SectionCard>

      <SectionCard
        title="能力"
        description="蓝图要而本服务拿不到的维度，逐条报出归属模块与原因。拿不到就说拿不到，不用空集冒充成功。"
      >
        <div className="flex flex-col gap-3">
          <div>
            <h4 className="text-copy-13 font-medium">可用维度</h4>
            <ul className="text-copy-13 text-muted-foreground mt-1 flex flex-col gap-1">
              {(caps?.availableDimensions ?? []).map((d) => (
                <li key={d}>✓ {d}</li>
              ))}
            </ul>
          </div>
          <div>
            <h4 className="text-copy-13 font-medium">尚不可得</h4>
            <ul className="text-copy-13 text-muted-foreground mt-1 flex flex-col gap-2">
              {(caps?.unavailableDimensions ?? []).map((d) => (
                <li key={d.field}>
                  <span className="text-foreground font-medium">{d.label}</span>
                  <Badge tone="warning" className="mx-2">
                    available = false
                  </Badge>
                  <span className="mx-1">归属 {d.ownerModule}</span>
                  <div>{d.reason}</div>
                </li>
              ))}
            </ul>
          </div>
          {caps?.filterableNote && (
            <div>
              <h4 className="text-copy-13 font-medium">筛选口径</h4>
              <p className="text-copy-13 text-muted-foreground">{caps.filterableNote}</p>
            </div>
          )}
          {caps?.exportNote && (
            <div>
              <h4 className="text-copy-13 font-medium">导出</h4>
              <p className="text-copy-13 text-muted-foreground">{caps.exportNote}</p>
            </div>
          )}
        </div>
      </SectionCard>
    </div>
  );
}
