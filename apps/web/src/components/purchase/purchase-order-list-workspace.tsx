'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import { usePrefillKeyword } from '@/lib/global-search-handoff';
import { Download, RefreshCw, Send, TriangleAlert, XCircle } from 'lucide-react';
import {
  Badge,
  Button,
  EmptyState,
  Input,
  Modal,
  ModalContent,
  ModalDescription,
  ModalFooter,
  ModalHeader,
  ModalTitle,
  PageHeader,
  SectionCard,
  StatCard,
} from '@starcon/ui';
import type {
  PurchaseOrderListCapabilities,
  PurchaseOrderListFilterOptions,
  PurchaseOrderListPage,
  PurchaseOrderListRow,
} from '../../../../../packages/api-contracts/src/index';
import {
  arrivalStatusLabel,
  batchResultText,
  closePurchaseOrders,
  dataScopeLabel,
  downloadPurchaseOrderCsv,
  fetchPurchaseOrderList,
  fetchPurchaseOrderListCapabilities,
  fetchPurchaseOrderListFilters,
  fmtAmount,
  fmtDate,
  fmtQty,
  fmtRatio,
  isCloseable,
  isUrgeable,
  orderStatusLabel,
  orderStatusTone,
  overdueText,
  receivedSourceLabel,
  urgePurchaseOrders,
  type PurchaseOrderListParams,
} from './purchase-order-list-model';
import { errorText } from '../inventory/inventory-model';

/**
 * 6.3.1.1 采购单列表工作区。
 *
 * <p>蓝图：「采购单主列表：单号、供应商、SKU 行数、总金额、币种、状态、承诺到货日、
 * 已收数量/比例、逾期标记，支持多维筛选与导出。」输出「分页列表 + 批量操作（催单、关闭、导出）」。
 *
 * <p>界面纪律：
 *
 * <ul>
 *   <li><b>「已收 0」与「没进到货跟踪」是两件事</b>：没跟踪的显示「未跟踪」，
 *       绝不显示 0 —— 0 会被读成「一件都没到，该催货了」，而未跟踪的正解是
 *       「去把单登记进 6.3.2.2 的到货跟踪」。</li>
 *   <li><b>总金额按币种分列</b>，页面**不出现**一个跨币种的合计数。</li>
 *   <li><b>本位币合计可为「无数据」</b>，并把后端写的排除原因原样透出，
 *       不用固定汇率在本地凑一个数。</li>
 *   <li><b>数据范围常驻</b>：当前范围与范围说明一直显示 ——
 *       采购员要能知道「我为什么只看到这几张单」。</li>
 *   <li><b>批量操作逐单成败</b>：失败原因原样显示，不能只剩一个布尔。</li>
 *   <li>加载 / 空 / 错误三态齐全，且三态文案各不相同。</li>
 * </ul>
 */
export function PurchaseOrderListWorkspace() {
  const [params, setParams] = useState<PurchaseOrderListParams>({ page: 0, size: 20 });
  const [data, setData] = useState<PurchaseOrderListPage | null>(null);
  const [caps, setCaps] = useState<PurchaseOrderListCapabilities | null>(null);
  const [options, setOptions] = useState<PurchaseOrderListFilterOptions | null>(null);
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [selected, setSelected] = useState<string[]>([]);
  const [urging, setUrging] = useState(false);
  const [closing, setClosing] = useState(false);

  // 筛选器的输入态：查询时才落到 params（输入过程中的半截值不该打后端）。
  const [supplierRefId, setSupplierRefId] = useState('');
  const [status, setStatus] = useState('');
  const [targetWarehouseId, setTargetWarehouseId] = useState('');
  const [currency, setCurrency] = useState('');
  const [keyword, setKeyword] = usePrefillKeyword();
  const [sort, setSort] = useState('CREATED_AT_DESC');
  const [promisedFrom, setPromisedFrom] = useState('');
  const [promisedTo, setPromisedTo] = useState('');
  const [overdueOnly, setOverdueOnly] = useState(false);

  const load = useCallback(async () => {
    setLoading(true);
    setError('');
    try {
      setData(await fetchPurchaseOrderList(params));
    } catch (e) {
      setError(errorText(e));
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
        setCaps(await fetchPurchaseOrderListCapabilities());
      } catch {
        // capabilities 拿不到不该让整页空掉：页面不假装自己知道边界。
        setCaps(null);
      }
      try {
        setOptions(await fetchPurchaseOrderListFilters());
      } catch {
        // 筛选项词表拿不到不该让整页空掉：清单仍然可用，只是下拉是空的。
        setOptions(null);
      }
    })();
  }, []);

  // ⚠️ 刻意用 useMemo 包一层：`data?.rows ?? []` 在 data 为 null 时每次渲染都是**新的**空数组，
  // 直接用会让依赖它的 useCallback / useMemo 每次渲染都失效。
  const rows = useMemo(() => data?.rows ?? [], [data]);
  const summary = data?.summary;

  const currencyAmounts = useMemo(() => summary?.amountsByCurrency ?? [], [summary]);

  const nextParams = useCallback((): PurchaseOrderListParams => {
    const next: PurchaseOrderListParams = { page: 0, size: params.size ?? 20, sort };
    if (supplierRefId) next.supplierRefId = supplierRefId;
    if (status) next.status = status;
    if (targetWarehouseId) next.targetWarehouseId = targetWarehouseId;
    if (currency) next.currency = currency;
    if (keyword.trim()) next.keyword = keyword.trim();
    if (promisedFrom) next.promisedFrom = promisedFrom;
    if (promisedTo) next.promisedTo = promisedTo;
    if (overdueOnly) next.overdueOnly = true;
    return next;
  }, [
    currency,
    keyword,
    overdueOnly,
    params.size,
    promisedFrom,
    promisedTo,
    sort,
    status,
    supplierRefId,
    targetWarehouseId,
  ]);

  const run = useCallback(
    async (work: () => Promise<void>) => {
      setBusy(true);
      setError('');
      setNotice('');
      try {
        await work();
      } catch (e) {
        setError(errorText(e));
      } finally {
        setBusy(false);
      }
    },
    [],
  );

  const toggle = useCallback((id: string) => {
    setSelected((prev) => (prev.includes(id) ? prev.filter((x) => x !== id) : [...prev, id]));
  }, []);

  const toggleAll = useCallback(() => {
    setSelected((prev) => (prev.length === rows.length ? [] : rows.map((r) => r.id)));
  }, [rows]);

  const selectedRows = useMemo(
    () => rows.filter((r) => selected.includes(r.id)),
    [rows, selected],
  );

  // 已关闭的单不再催单也不再关：批量按钮据此禁用，
  // 而不是让用户点下去再从逐单失败原因里读出来。
  const urgeableCount = useMemo(
    () => selectedRows.filter((r) => isUrgeable(r.status)).length,
    [selectedRows],
  );
  const closeableCount = useMemo(
    () => selectedRows.filter((r) => isCloseable(r.status)).length,
    [selectedRows],
  );

  const doUrge = useCallback(
    async (note: string) => {
      await run(async () => {
        const result = await urgePurchaseOrders(selected, note);
        setNotice(batchResultText(result));
        setSelected([]);
        await load();
      });
    },
    [load, run, selected],
  );

  const doClose = useCallback(
    async (reason: string) => {
      await run(async () => {
        const result = await closePurchaseOrders(selected, reason);
        setNotice(batchResultText(result));
        setSelected([]);
        await load();
      });
    },
    [load, run, selected],
  );

  const doExport = useCallback(async () => {
    await run(async () => {
      await downloadPurchaseOrderCsv(nextParams());
      setNotice('导出已发起（与当前筛选条件一致）。');
    });
  }, [nextParams, run]);

  const goPage = useCallback(
    (page: number) => {
      setParams((prev) => ({ ...prev, page }));
    },
    [],
  );

  const total = data?.total ?? 0;
  const size = data?.size ?? 20;
  const page = data?.page ?? 0;
  const lastPage = Math.max(0, Math.ceil(total / size) - 1);

  return (
    <div className="flex flex-col gap-4">
      <PageHeader
        title="采购单"
        description="采购单主列表：单号、供应商、SKU 行数、总金额、币种、状态、承诺到货日、已收数量与比例、逾期标记，支持多维筛选、批量催单 / 关闭与导出。"
        actions={
          <div className="flex gap-2">
            <Button variant="ghost" onClick={() => void load()} disabled={loading}>
              <RefreshCw className={loading ? 'animate-spin' : ''} />
              刷新
            </Button>
            <Button variant="secondary" onClick={() => void doExport()} disabled={busy}>
              <Download />
              导出
            </Button>
          </div>
        }
      />

      {/* 口径常驻：已收数量的来源与总金额的分组口径必须一直摆在眼前 */}
      {caps && (
        <div className="flex items-start gap-2 rounded-md border border-sky-500/40 bg-sky-500/10 p-3 text-sm">
          <TriangleAlert className="mt-0.5 size-4 shrink-0" />
          <div className="flex flex-col gap-1">
            <p className="font-medium">已收数量与总金额的口径</p>
            <p className="text-muted-foreground text-xs">{caps.receivedQuantityNote}</p>
            <p className="text-muted-foreground text-xs">{caps.filterableNote}</p>
            <p className="text-muted-foreground text-xs">{caps.exportNote}</p>
          </div>
        </div>
      )}

      {summary && (
        <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
          <StatCard label="采购单总数" value={summary.totalCount} />
          <StatCard label="逾期" value={summary.overdueCount} polarity="negative" />
          {currencyAmounts.length === 0 ? (
            <StatCard label="总金额" value="无数据" />
          ) : (
            currencyAmounts.map((c) => (
              <StatCard
                key={c.currency}
                label={`总金额（${c.currency}）`}
                value={fmtAmount(c.amount)}
                comparisonLabel={`${c.orderCount} 张单`}
              />
            ))
          )}
          <StatCard
            label={
              summary.functionalTotal
                ? `本位币合计（${summary.functionalTotal.functionalCurrency}）`
                : '本位币合计'
            }
            value={summary.functionalTotal ? fmtAmount(summary.functionalTotal.amount) : '无数据'}
            comparisonLabel={
              summary.functionalTotal
                ? `${summary.functionalTotal.includedOrderCount} 张参与 / ${summary.functionalTotal.excludedOrderCount} 张排除`
                : '未冻结到汇率快照，不折算'
            }
          />
        </div>
      )}

      {summary?.functionalTotal?.note && (
        <p className="text-muted-foreground text-xs">{summary.functionalTotal.note}</p>
      )}
      {summary?.totalAmountNote && <p className="text-muted-foreground text-xs">{summary.totalAmountNote}</p>}

      {/* 数据范围常驻：采购员要能知道「我为什么只看到这几张单」 */}
      {data && (
        <div className="flex flex-wrap items-center gap-2 text-xs">
          <Badge tone="info">数据范围：{dataScopeLabel(data.dataScope)}</Badge>
          <span className="text-muted-foreground">{data.dataScopeNote}</span>
        </div>
      )}

      <SectionCard title="筛选">
        <div className="flex flex-wrap items-end gap-3">
          <label className="flex flex-col gap-1 text-sm">
            <span className="text-muted-foreground">供应商</span>
            <select
              className="h-9 rounded-md border px-2"
              value={supplierRefId}
              onChange={(e) => setSupplierRefId(e.target.value)}
            >
              <option value="">全部</option>
              {(options?.suppliers ?? []).map((s) => (
                <option key={s.supplierRefId} value={s.supplierRefId}>
                  {s.supplierCode} · {s.supplierName}（{s.orderCount}）
                </option>
              ))}
            </select>
          </label>
          <label className="flex flex-col gap-1 text-sm">
            <span className="text-muted-foreground">状态</span>
            <select
              className="h-9 rounded-md border px-2"
              value={status}
              onChange={(e) => setStatus(e.target.value)}
            >
              <option value="">全部</option>
              {(options?.statuses ?? []).map((s) => (
                <option key={s} value={s}>
                  {orderStatusLabel(s)}
                </option>
              ))}
            </select>
          </label>
          <label className="flex flex-col gap-1 text-sm">
            <span className="text-muted-foreground">目标仓</span>
            <select
              className="h-9 rounded-md border px-2"
              value={targetWarehouseId}
              onChange={(e) => setTargetWarehouseId(e.target.value)}
            >
              <option value="">全部</option>
              {(options?.warehouses ?? []).map((w) => (
                <option key={w.warehouseId} value={w.warehouseId}>
                  {w.warehouseCode} · {w.warehouseName}（{w.orderCount}）
                </option>
              ))}
            </select>
          </label>
          <label className="flex flex-col gap-1 text-sm">
            <span className="text-muted-foreground">币种</span>
            <select
              className="h-9 rounded-md border px-2"
              value={currency}
              onChange={(e) => setCurrency(e.target.value)}
            >
              <option value="">全部</option>
              {(options?.currencies ?? []).map((c) => (
                <option key={c} value={c}>
                  {c}
                </option>
              ))}
            </select>
          </label>
          <label className="flex flex-col gap-1 text-sm">
            <span className="text-muted-foreground">关键字</span>
            <Input
              value={keyword}
              placeholder="单号 / 供应商编码或名称"
              onChange={(e) => setKeyword(e.target.value)}
              className="w-56"
            />
          </label>
          <label className="flex flex-col gap-1 text-sm">
            <span className="text-muted-foreground">承诺到货日从</span>
            <Input
              type="date"
              value={promisedFrom}
              onChange={(e) => setPromisedFrom(e.target.value)}
              className="w-40"
            />
          </label>
          <label className="flex flex-col gap-1 text-sm">
            <span className="text-muted-foreground">到</span>
            <Input
              type="date"
              value={promisedTo}
              onChange={(e) => setPromisedTo(e.target.value)}
              className="w-40"
            />
          </label>
          <label className="flex flex-col gap-1 text-sm">
            <span className="text-muted-foreground">排序</span>
            <select
              className="h-9 rounded-md border px-2"
              value={sort}
              onChange={(e) => setSort(e.target.value)}
            >
              {(options?.sorts ?? []).map((s) => (
                <option key={s.code} value={s.code}>
                  {s.label}
                </option>
              ))}
            </select>
          </label>
          <label className="flex items-center gap-2 text-sm">
            <input
              type="checkbox"
              checked={overdueOnly}
              onChange={(e) => setOverdueOnly(e.target.checked)}
            />
            只看逾期
          </label>
          <Button onClick={() => setParams(nextParams())}>查询</Button>
          <Button
            variant="ghost"
            onClick={() => {
              setSupplierRefId('');
              setStatus('');
              setTargetWarehouseId('');
              setCurrency('');
              setKeyword('');
              setPromisedFrom('');
              setPromisedTo('');
              setOverdueOnly(false);
              setSort('CREATED_AT_DESC');
              setParams({ page: 0, size: params.size ?? 20, sort: 'CREATED_AT_DESC' });
            }}
          >
            重置
          </Button>
        </div>
      </SectionCard>

      {error && (
        <div className="flex items-start gap-2 rounded-md border border-red-500/40 bg-red-500/10 p-3 text-sm text-red-600">
          <TriangleAlert className="mt-0.5 size-4 shrink-0" />
          <p>{error}</p>
        </div>
      )}

      {notice && (
        <div className="flex items-start gap-2 rounded-md border border-emerald-500/40 bg-emerald-500/10 p-3 text-sm text-emerald-700">
          <p>{notice}</p>
        </div>
      )}

      {/* 批量操作条：只有选中时才出现，且逐单成败要说清楚 */}
      {selected.length > 0 && (
        <SectionCard title={`已选 ${selected.length} 张采购单`}>
          <div className="flex flex-wrap items-center gap-2">
            <Button onClick={() => setUrging(true)} disabled={busy || urgeableCount === 0}>
              <Send />
              批量催单
            </Button>
            <Button
              variant="secondary"
              onClick={() => setClosing(true)}
              disabled={busy || closeableCount === 0}
            >
              <XCircle />
              批量关闭
            </Button>
            <span className="text-muted-foreground text-xs">
              已选 {selected.length} 张，可催 {urgeableCount} 张、可关 {closeableCount} 张
              （已关闭的采购单不再催单也不再关）。催单只留痕（催单次数 + 最近催单时刻），
              **不改承诺到货日** —— 交期变更归 6.3.2.1 跟单。关闭必须填理由，关闭后整单冻结。
            </span>
          </div>
        </SectionCard>
      )}

      {loading && <p className="text-muted-foreground text-sm">正在加载采购单列表…</p>}

      {!loading && rows.length === 0 && !error && (
        <EmptyState
          title="没有采购单"
          description="当前筛选条件下没有采购单。采购单由 6.2.2.1 的转单事实生成 —— 先把已审批的采购申请转单，列表才会有内容。"
        />
      )}

      {!loading && rows.length > 0 && (
        <SectionCard title={`采购单列表（${total}）`}>
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead className="text-muted-foreground text-left">
                <tr>
                  <th className="px-2 py-1">
                    <input
                      type="checkbox"
                      aria-label="全选当前页"
                      checked={rows.length > 0 && selected.length === rows.length}
                      onChange={toggleAll}
                    />
                  </th>
                  <th className="px-2 py-1">采购单号</th>
                  <th className="px-2 py-1">供应商</th>
                  <th className="px-2 py-1">SKU 行数</th>
                  <th className="px-2 py-1">总金额</th>
                  <th className="px-2 py-1">状态</th>
                  <th className="px-2 py-1">承诺到货日</th>
                  <th className="px-2 py-1">已收数量 / 比例</th>
                  <th className="px-2 py-1">逾期</th>
                  <th className="px-2 py-1">目标仓</th>
                  <th className="px-2 py-1">催单</th>
                </tr>
              </thead>
              <tbody>
                {rows.map((row) => (
                  <OrderRow
                    key={row.id}
                    row={row}
                    checked={selected.includes(row.id)}
                    onToggle={() => toggle(row.id)}
                  />
                ))}
              </tbody>
            </table>
          </div>

          {total > size && (
            <div className="mt-3 flex items-center justify-end gap-2 text-sm">
              <Button
                variant="ghost"
                disabled={page <= 0}
                onClick={() => goPage(Math.max(0, page - 1))}
              >
                上一页
              </Button>
              <span className="text-muted-foreground">
                第 {page + 1} / {lastPage + 1} 页（共 {total} 张）
              </span>
              <Button
                variant="ghost"
                disabled={page >= lastPage}
                onClick={() => goPage(Math.min(lastPage, page + 1))}
              >
                下一页
              </Button>
            </div>
          )}
        </SectionCard>
      )}

      {urging && (
        <UrgeDialog
          busy={busy}
          count={selected.length}
          onCancel={() => setUrging(false)}
          onRun={async (note) => {
            setUrging(false);
            await doUrge(note);
          }}
        />
      )}

      {closing && (
        <CloseDialog
          busy={busy}
          count={selected.length}
          onCancel={() => setClosing(false)}
          onRun={async (reason) => {
            setClosing(false);
            await doClose(reason);
          }}
        />
      )}
    </div>
  );
}

/**
 * 一行采购单。
 *
 * <p>已收数量那一格刻意是**两个数 + 一个来源**：
 * 「40 / 100（40.0%）· 到货跟踪」与「未跟踪」是两种完全不同的状态，
 * 合成一个数字就分不出来了。
 */
function OrderRow({
  row,
  checked,
  onToggle,
}: {
  row: PurchaseOrderListRow;
  checked: boolean;
  onToggle: () => void;
}) {
  const tracked = row.receivedQuantitySource === 'ARRIVAL_TRACKING';
  return (
    <tr className="border-t">
      <td className="px-2 py-1">
        <input
          type="checkbox"
          aria-label={`选择 ${row.orderNo}`}
          checked={checked}
          onChange={onToggle}
        />
      </td>
      <td className="px-2 py-1 font-medium">{row.orderNo}</td>
      <td className="px-2 py-1">
        {row.supplierCode}
        {row.supplierName && (
          <span className="text-muted-foreground"> · {row.supplierName}</span>
        )}
      </td>
      <td className="px-2 py-1">
        {row.lineCount}
        <span className="text-muted-foreground"> 行 / {row.skuCount} SKU</span>
      </td>
      <td className="px-2 py-1">
        {fmtAmount(row.totalAmount)}
        <span className="text-muted-foreground"> {row.currency}</span>
      </td>
      <td className="px-2 py-1">
        <Badge tone={orderStatusTone(row.status)}>{orderStatusLabel(row.status)}</Badge>
      </td>
      <td className="px-2 py-1">{fmtDate(row.promisedArrivalDate)}</td>
      <td className="px-2 py-1">
        {tracked ? (
          <span>
            {fmtQty(row.receivedQuantity)} / {fmtQty(row.receivableQuantity)}
            <span className="text-muted-foreground">（{fmtRatio(row.receivedRatio)}）</span>
            <span className="text-muted-foreground block text-xs">
              {receivedSourceLabel(row.receivedQuantitySource)} ·{' '}
              {arrivalStatusLabel(row.arrivalStatus)}
              {row.warehouseReceiptCode ? ` · 收货单 ${row.warehouseReceiptCode}` : ''}
            </span>
          </span>
        ) : (
          <span
            className="text-muted-foreground"
            title="这张单还没进 6.3.2.2 的到货跟踪 —— 0 与「没登记」不是一回事"
          >
            未跟踪
            <span className="block text-xs">还没登记到货</span>
          </span>
        )}
      </td>
      <td className="px-2 py-1">
        {row.overdue ? (
          <Badge tone="danger">{overdueText(row)}</Badge>
        ) : (
          <span className="text-muted-foreground">{overdueText(row)}</span>
        )}
      </td>
      <td className="px-2 py-1">
        {row.targetWarehouseCode}
        {row.targetWarehouseName && (
          <span className="text-muted-foreground"> · {row.targetWarehouseName}</span>
        )}
      </td>
      <td className="px-2 py-1">
        {row.urgeCount > 0 ? (
          <span>
            {row.urgeCount} 次
            {row.lastUrgedAt && (
              <span className="text-muted-foreground block text-xs">
                {row.lastUrgedAt.slice(0, 16).replace('T', ' ')}
              </span>
            )}
          </span>
        ) : (
          <span className="text-muted-foreground">未催</span>
        )}
      </td>
    </tr>
  );
}

/** 批量催单弹窗。催单不写承诺到货日，所以这里只收一句说明。 */
function UrgeDialog({
  busy,
  count,
  onCancel,
  onRun,
}: {
  busy: boolean;
  count: number;
  onCancel: () => void;
  onRun: (note: string) => Promise<void>;
}) {
  const [note, setNote] = useState('');
  return (
    <Modal open onOpenChange={(open) => !open && onCancel()}>
      <ModalContent>
        <ModalHeader>
          <ModalTitle>批量催单（{count} 张）</ModalTitle>
          <ModalDescription>
            催单只留痕：催单次数 + 最近催单时刻 + 一条留痕。**不会**改承诺到货日 ——
            交期变更历史归 6.3.2.1 跟单。
          </ModalDescription>
        </ModalHeader>
        <label className="flex flex-col gap-1 text-sm">
          <span className="text-muted-foreground">催单说明（会进留痕）</span>
          <Input
            value={note}
            placeholder="例如：供应商还没回复发货时间"
            onChange={(e) => setNote(e.target.value)}
          />
        </label>
        <ModalFooter>
          <Button variant="ghost" onClick={onCancel} disabled={busy}>
            取消
          </Button>
          <Button onClick={() => void onRun(note)} disabled={busy}>
            确认催单
          </Button>
        </ModalFooter>
      </ModalContent>
    </Modal>
  );
}

/** 批量关闭弹窗。理由**必填** —— 「关掉了但不知道为什么」等于没关。 */
function CloseDialog({
  busy,
  count,
  onCancel,
  onRun,
}: {
  busy: boolean;
  count: number;
  onCancel: () => void;
  onRun: (reason: string) => Promise<void>;
}) {
  const [reason, setReason] = useState('');
  const valid = reason.trim().length > 0;
  return (
    <Modal open onOpenChange={(open) => !open && onCancel()}>
      <ModalContent>
        <ModalHeader>
          <ModalTitle>批量关闭采购单（{count} 张）</ModalTitle>
          <ModalDescription>
            关闭后整单冻结：数量、单价、供应商、承诺到货日都不可再改（后端与 DB 触发器双重保证）。
          </ModalDescription>
        </ModalHeader>
        <label className="flex flex-col gap-1 text-sm">
          <span className="text-muted-foreground">关闭理由（必填）</span>
          <Input
            value={reason}
            placeholder="例如：供应商停业，改用别家"
            onChange={(e) => setReason(e.target.value)}
          />
        </label>
        {!valid && (
          <p className="text-muted-foreground text-xs">
            关闭理由必填 —— 空理由会被后端与数据库层直接拒。
          </p>
        )}
        <ModalFooter>
          <Button variant="ghost" onClick={onCancel} disabled={busy}>
            取消
          </Button>
          <Button
            variant="destructive"
            onClick={() => void onRun(reason.trim())}
            disabled={busy || !valid}
          >
            确认关闭
          </Button>
        </ModalFooter>
      </ModalContent>
    </Modal>
  );
}
