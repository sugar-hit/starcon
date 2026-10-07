'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import Link from 'next/link';
import { Boxes, PackageCheck, Printer, RefreshCw, Truck, XCircle } from 'lucide-react';
import {
  Badge,
  Button,
  EmptyState,
  FormField,
  Input,
  Notice,
  PageHeader,
  SectionCard,
} from '@starcon/ui';
import type {
  OutboundBulkFailure,
  OutboundCancelResult,
  OutboundEnumOption,
  OutboundOrderCapabilities,
  OutboundOrderPage,
  OutboundOrderSummary,
  OutboundPickRequestResult,
  OutboundPrintResult,
} from '../../../../../packages/api-contracts/src/catalog';
import {
  cancelOutboundOrders,
  canCancel,
  canRequestPicking,
  carrierWarning,
  errorText,
  failureList,
  fetchOutboundCapabilities,
  fetchOutboundOrders,
  isTerminal,
  nextStatuses,
  offsetIso,
  optionLabel,
  pageSummary,
  pieceText,
  printOutboundOrders,
  reasonRequired,
  requestPicking,
  sourceText,
  transitionOutboundOrder,
  volumeText,
} from './outbound-order-model';
import { detailHref } from './outbound-order-detail-model';
import { useIdentity } from '../iam/identity-shell';

/**
 * 8.3.1.1 出库单列表工作区。
 *
 * <p>蓝图：「出库作业主列表：来源（销售/采购退货/调拨/加工出库）、SKU 行数、件数、状态
 * （待拣/拣货中/待复核/待打包/待发货/已发货/取消）、发货仓、承运渠道。」输入：「筛选条件
 * （仓库、来源、状态、时间）；用户数据范围。」输出：「分页列表 + 批量操作（生成拣货任务、
 * 打印、取消）。」
 *
 * <p>界面纪律（每一条都对应一个会被误显示的坑）：
 *
 * <ul>
 *   <li>**出库单 ≠ 销售订单**。外部单号列会出现重复值，那不是脏数据而是分仓/分批 ——
 *       表头必须写出来，否则作业员会去问「这是不是重复建单了」。
 *   <li>**「生成拣货任务」不是「已拣货」**。点了之后状态变成「拣货中」，不是「已完成拣货」：
 *       拣货任务归 8.3.2.1，实拣结果本模块拿不到，也**不许假装拿得到**。
 *   <li>**批量操作逐单反馈**。部分成功必须逐条列出失败原因，不显示成一句「操作完成」。
 *   <li>**打印打印的是内容，不是「已提交」**。点打印直接把要印的单头与明细列出来。
 *   <li>**面单与标签不在这里**。那是 8.3.4.2 的范围，界面上的提示里如实说明。
 *   <li>**未交付的边界要显示出来**：9 订单履约未交付，所以销售/退货/加工三类来源只有外部单号。
 * </ul>
 */
export function OutboundOrderWorkspace() {
  const { session } = useIdentity();
  const readable = session.permissions.includes('warehouse.inventory.read');
  const writable = session.permissions.includes('warehouse.inventory.update');

  const [capabilities, setCapabilities] = useState<OutboundOrderCapabilities | null>(null);
  const [page, setPage] = useState<OutboundOrderPage | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');

  const [warehouses, setWarehouses] = useState<string[]>([]);
  const [sources, setSources] = useState<string[]>([]);
  const [statuses, setStatuses] = useState<string[]>([]);
  const [keyword, setKeyword] = useState('');
  const [from, setFrom] = useState('');
  const [to, setTo] = useState('');
  const [pageNo, setPageNo] = useState(0);

  const [selected, setSelected] = useState<string[]>([]);
  const [busy, setBusy] = useState('');
  const [failures, setFailures] = useState<string[]>([]);
  const [printed, setPrinted] = useState<OutboundPrintResult | null>(null);
  const [cancelReason, setCancelReason] = useState('');

  const sourceOptions: OutboundEnumOption[] = capabilities?.sourceTypes ?? [];
  const statusOptions: OutboundEnumOption[] = capabilities?.statuses ?? [];

  const load = useCallback(async () => {
    if (!readable) {
      setLoading(false);
      return;
    }
    setLoading(true);
    setError('');
    try {
      const result = await fetchOutboundOrders({
        warehouseIds: warehouses,
        sourceTypes: sources,
        statuses,
        keyword: keyword.trim() || undefined,
        // 时间筛选一律转成带时区的 ISO：后端拒绝裸本地时间串。
        createdFrom: from ? offsetIso(new Date(from)) : undefined,
        createdTo: to ? offsetIso(new Date(to)) : undefined,
        page: pageNo,
        size: 20,
      });
      setPage(result);
    } catch (e) {
      setError(errorText(e));
    } finally {
      setLoading(false);
    }
  }, [readable, warehouses, sources, statuses, keyword, from, to, pageNo]);

  useEffect(() => {
    void load();
  }, [load]);

  useEffect(() => {
    if (!readable) return;
    fetchOutboundCapabilities()
      .then(setCapabilities)
      .catch((e) => setError(errorText(e)));
  }, [readable]);

  const orders = useMemo(() => page?.items ?? [], [page]);
  const allSelected = orders.length > 0 && orders.every((o) => selected.includes(o.id));
  const terminalCount = useMemo(() => orders.filter(isTerminal).length, [orders]);

  function toggle(id: string) {
    setSelected((prev) => (prev.includes(id) ? prev.filter((x) => x !== id) : [...prev, id]));
  }

  function toggleAll() {
    setSelected(allSelected ? [] : orders.map((o) => o.id));
  }

  /** 刷新并把选择清掉：批量操作后原选择往往已经失效（已发货/已取消）。 */
  function reset() {
    setSelected([]);
    setFailures([]);
  }

  async function runPicking() {
    setBusy('picking');
    setNotice('');
    setFailures([]);
    try {
      const result: OutboundPickRequestResult = await requestPicking(selected);
      setNotice(
        `已为 ${result.requestedCount} 张出库单生成拣货任务（待拣 → 拣货中）。` +
          '拣货任务由 8.3.2.1 接手，本模块不产生拣货明细。',
      );
      setFailures(failureList(result.failures));
      reset();
      await load();
    } catch (e) {
      setError(errorText(e));
    } finally {
      setBusy('');
    }
  }

  async function runPrint(document: string) {
    setBusy('print');
    setError('');
    setFailures([]);
    try {
      const result = await printOutboundOrders(selected, document);
      setPrinted(result);
      setNotice(`已生成 ${result.orderCount} 张${result.documentLabel}，内容见下。`);
      await load();
    } catch (e) {
      setError(errorText(e));
    } finally {
      setBusy('');
    }
  }

  async function runCancel() {
    if (!cancelReason.trim()) {
      setError('取消必须写原因：没有原因的取消在事后无法解释。');
      return;
    }
    setBusy('cancel');
    setNotice('');
    setFailures([]);
    try {
      const result: OutboundCancelResult = await cancelOutboundOrders(selected, cancelReason.trim());
      setNotice(`已取消 ${result.cancelledCount} 张出库单。`);
      setFailures(failureList(result.failures));
      reset();
      await load();
    } catch (e) {
      setError(errorText(e));
    } finally {
      setBusy('');
    }
  }

  async function runTransition(order: OutboundOrderSummary, target: string, note?: string) {
    setBusy(`${order.id}:${target}`);
    setError('');
    try {
      await transitionOutboundOrder(order.id, target, note);
      setNotice(`${order.orderCode} 已推进到「${optionLabel(statusOptions, target)}」。`);
      await load();
    } catch (e) {
      setError(errorText(e));
    } finally {
      setBusy('');
    }
  }

  if (!readable) {
    return (
      <PageHeader title="出库单" description="按来源、状态、发货仓查看出库作业，并执行批量操作。" />
    );
  }

  return (
    <div className="space-y-5">
      <PageHeader
        title="出库单"
        description="出库作业主列表：一订单可多出库单（分仓/分批），出库单是履约动作的载体。"
        actions={
          <Button variant="secondary" onClick={() => void load()} disabled={loading}>
            <RefreshCw className="mr-1.5 h-4 w-4" />
            刷新
          </Button>
        }
      />

      {error && (
        <Notice tone="danger" title="出库单工作台出问题了">
          {error}
        </Notice>
      )}

      {notice && (
        <Notice tone="success" title="操作已完成">
          {notice}
        </Notice>
      )}

      {failures.length > 0 && (
        <Notice tone="warning" title={`有 ${failures.length} 张单没有处理成功`}>
          <ul className="mt-1 list-disc space-y-0.5 pl-4">
            {failures.map((line) => (
              <li key={line}>{line}</li>
            ))}
          </ul>
        </Notice>
      )}

      {capabilities && (
        <SectionCard title="模块边界（这几条会影响你怎么用这个页面）">
          <ul className="text-copy-12 text-muted-foreground space-y-1.5">
            <li>· {capabilities.pickingHandoverNote}</li>
            <li>· {capabilities.orderFulfillmentNote}</li>
            <li>· {capabilities.dataScopeNote}</li>
            <li>· {capabilities.printScopeNote}</li>
          </ul>
        </SectionCard>
      )}

      <SectionCard title="筛选" description="仓库 / 来源 / 状态 / 时间四个维度都是真的后端筛选，不是前端过滤。">
        <div className="grid gap-4 md:grid-cols-5">
          <FormField id="ob-warehouse" label="发货仓（仓库编码，逗号分隔）">
            <Input
              value={warehouses.join(',')}
              onChange={(e) => setWarehouses(e.target.value.split(',').map((s) => s.trim()).filter(Boolean))}
              placeholder="全部仓库"
            />
          </FormField>
          <FormField id="ob-source" label="来源">
            <select
              className="border-control-border bg-background h-9 rounded-md border px-2 text-sm"
              value={sources[0] ?? ''}
              onChange={(e) => setSources(e.target.value ? [e.target.value] : [])}
            >
              <option value="">全部来源</option>
              {sourceOptions.map((s) => (
                <option key={s.name} value={s.name}>
                  {s.label}
                </option>
              ))}
            </select>
          </FormField>
          <FormField id="ob-status" label="状态">
            <select
              className="border-control-border bg-background h-9 rounded-md border px-2 text-sm"
              value={statuses[0] ?? ''}
              onChange={(e) => setStatuses(e.target.value ? [e.target.value] : [])}
            >
              <option value="">全部状态</option>
              {statusOptions.map((s) => (
                <option key={s.name} value={s.name}>
                  {s.label}
                </option>
              ))}
            </select>
          </FormField>
          <FormField id="ob-from" label="创建时间起">
            <Input type="datetime-local" value={from} onChange={(e) => setFrom(e.target.value)} />
          </FormField>
          <FormField id="ob-to" label="创建时间止">
            <Input type="datetime-local" value={to} onChange={(e) => setTo(e.target.value)} />
          </FormField>
        </div>
        <div className="mt-4 flex flex-wrap items-end gap-3">
          <FormField id="ob-keyword" label="关键字" hint="单号 / 外部单号 / 调拨单号 / 仓库 / 承运渠道">
            <Input
              value={keyword}
              onChange={(e) => setKeyword(e.target.value)}
              placeholder="例如 SO-1001 或 TR-1"
              className="min-w-64"
            />
          </FormField>
          <Button
            variant="secondary"
            onClick={() => {
              setWarehouses([]);
              setSources([]);
              setStatuses([]);
              setKeyword('');
              setFrom('');
              setTo('');
              setPageNo(0);
            }}
          >
            清空筛选
          </Button>
        </div>
      </SectionCard>

      {selected.length > 0 && (
        <SectionCard
          title={`已选 ${selected.length} 张出库单`}
          description="批量操作逐单独立执行：失败的单会单独列出原因，不会被算成成功。"
        >
          <div className="flex flex-wrap items-end gap-3">
            <Button onClick={() => void runPicking()} disabled={!writable || busy !== ''}>
              <PackageCheck className="mr-1.5 h-4 w-4" />
              生成拣货任务
            </Button>
            <Button variant="secondary" onClick={() => void runPrint('PICK_LIST')} disabled={!readable || busy !== ''}>
              <Printer className="mr-1.5 h-4 w-4" />
              打印拣货单
            </Button>
            <Button variant="secondary" onClick={() => void runPrint('OUTBOUND_ORDER')} disabled={!readable || busy !== ''}>
              <Printer className="mr-1.5 h-4 w-4" />
              打印出库单
            </Button>
            <FormField id="ob-cancel-reason" label="取消原因" required hint="取消是留痕操作，原因必填。">
              <Input
                value={cancelReason}
                onChange={(e) => setCancelReason(e.target.value)}
                placeholder="例如：客户取消订单"
                className="min-w-64"
              />
            </FormField>
            <Button variant="destructive" onClick={() => void runCancel()} disabled={!writable || busy !== ''}>
              <XCircle className="mr-1.5 h-4 w-4" />
              取消出库单
            </Button>
          </div>
        </SectionCard>
      )}

      {printed && (
        <SectionCard title={`打印预览 · ${printed.documentLabel}`} description={`生成于 ${printed.generatedAt}`}>
          <div className="space-y-4">
            {printed.orders.map((order) => (
              <div key={order.orderId} className="border-border rounded-md border p-3">
                <div className="text-copy-12 text-muted-foreground flex flex-wrap gap-x-4 gap-y-1">
                  <span>单号 {order.orderCode}</span>
                  <span>
                    {order.statusLabel} · {order.warehouseName}
                  </span>
                  {order.carrierCode && <span>承运 {order.carrierCode}</span>}
                  <span>{pieceText(order.totalPieceCount)} 件</span>
                </div>
                <table className="mt-2 w-full text-sm">
                  <thead className="text-muted-foreground text-left text-xs">
                    <tr>
                      <th className="py-1">#</th>
                      <th className="py-1">SKU</th>
                      <th className="py-1">商品</th>
                      <th className="py-1 text-right">件数</th>
                    </tr>
                  </thead>
                  <tbody>
                    {order.lines.map((line) => (
                      <tr key={`${order.orderId}-${line.lineNo}`} className="border-control-border border-t">
                        <td className="py-1">{line.lineNo}</td>
                        <td className="py-1">{line.skuCode}</td>
                        <td className="py-1">{line.skuName}</td>
                        <td className="py-1 text-right">{pieceText(line.quantity)}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            ))}
          </div>
        </SectionCard>
      )}

      <SectionCard
        title="出库单"
        description={page ? pageSummary(page) : undefined}
        extra={page && page.total > 0 ? <Badge tone="info">{page.total} 张</Badge> : undefined}
      >
        {loading && <p className="text-copy-13 text-muted-foreground py-6 text-center">正在加载出库单…</p>}

        {!loading && orders.length === 0 && (
          <EmptyState
            icon={<Boxes className="h-6 w-6" />}
            title="这里还没有出库单"
            description="出库单由履约需求驱动：销售 / 采购退货 / 加工三类来源目前只登记外部单号（上游服务尚未交付），调拨来源则真实关联 7.5.1.1 的调拨单。"
          />
        )}

        {!loading && orders.length > 0 && (
          <table className="w-full text-sm">
            <thead className="text-muted-foreground text-left text-xs">
              <tr>
                <th className="py-2">
                  <input
                    type="checkbox"
                    aria-label="全选当前页"
                    checked={allSelected}
                    onChange={toggleAll}
                  />
                </th>
                <th className="py-2">出库单号</th>
                <th className="py-2">来源 / 单号</th>
                <th className="py-2 text-right">行数与件数</th>
                <th className="py-2">状态</th>
                <th className="py-2">发货仓</th>
                <th className="py-2">承运渠道</th>
                <th className="py-2" />
              </tr>
            </thead>
            <tbody>
              {orders.map((order) => {
                const transitions = nextStatuses(capabilities, order.status);
                return (
                  <tr key={order.id} className="border-control-border border-t">
                    <td className="py-2">
                      <input
                        type="checkbox"
                        aria-label={`选择 ${order.orderCode}`}
                        checked={selected.includes(order.id)}
                        onChange={() => toggle(order.id)}
                      />
                    </td>
                    <td className="py-2">
                      {/* 8.3.1.2 出库单详情：单号可点进去 —— 详情页串联整条出库链路。 */}
                      <Link href={detailHref(order.id)} className="font-medium hover:underline">
                        {order.orderCode}
                      </Link>
                      {order.sourceExternalRef && order.sourceType !== 'TRANSFER' && (
                        <div className="text-copy-12 text-muted-foreground">
                          外部单号 {order.sourceExternalRef}
                          {orders.filter((o) => o.sourceExternalRef === order.sourceExternalRef).length >
                          1 && '（同一订单已分多张出库单）'}
                        </div>
                      )}
                    </td>
                    <td className="py-2">
                      <div>{order.sourceTypeLabel}</div>
                      <div className="text-copy-12 text-muted-foreground">{sourceText(order)}</div>
                    </td>
                    <td className="py-2 text-right">{volumeText(order)}</td>
                    <td className="py-2">
                      <Badge tone={isTerminal(order) ? 'neutral' : 'info'}>{order.statusLabel}</Badge>
                      {order.pickRequested && order.status === 'PENDING_PICKING' && (
                        <div className="text-copy-12 text-warning mt-1">已交接拣货</div>
                      )}
                      {order.status === 'CANCELLED' && order.cancelReason && (
                        <div className="text-copy-12 text-muted-foreground mt-1">
                          原因：{order.cancelReason}
                        </div>
                      )}
                    </td>
                    <td className="py-2">
                      <div>{order.warehouseCode}</div>
                      <div className="text-copy-12 text-muted-foreground">{order.warehouseName}</div>
                    </td>
                    <td className="py-2">
                      {order.carrierCode ? (
                        <span className="inline-flex items-center gap-1">
                          <Truck className="h-3.5 w-3.5" />
                          {order.carrierCode}
                        </span>
                      ) : (
                        <span className="text-copy-12 text-muted-foreground">未指定</span>
                      )}
                      {carrierWarning(order) && (
                        <div className="text-copy-12 text-warning mt-1">{carrierWarning(order)}</div>
                      )}
                    </td>
                    <td className="py-2 text-right">
                      <div className="flex flex-wrap justify-end gap-1.5">
                        <Button
                          size="sm"
                          variant="secondary"
                          disabled={!writable || busy !== '' || !canRequestPicking(order)}
                          onClick={() => {
                            setSelected([order.id]);
                            void runPicking();
                          }}
                        >
                          生成拣货任务
                        </Button>
                        {transitions.map((t) => (
                          <Button
                            key={`${t.from}-${t.to}`}
                            size="sm"
                            variant="ghost"
                            disabled={!writable || busy !== '' || reasonRequired(capabilities, t.from, t.to)}
                            title={reasonRequired(capabilities, t.from, t.to) ? '该跃迁必须填写说明，请在批量操作里带说明' : t.note}
                            onClick={() => void runTransition(order, t.to, t.note)}
                          >
                            {t.toLabel}
                          </Button>
                        ))}
                      </div>
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        )}

        {page && page.total > page.size && (
          <div className="mt-4 flex items-center justify-between">
            <Button variant="secondary" disabled={pageNo === 0} onClick={() => setPageNo((p) => p - 1)}>
              上一页
            </Button>
            <span className="text-copy-12 text-muted-foreground">第 {pageNo + 1} 页</span>
            <Button
              variant="secondary"
              disabled={(pageNo + 1) * page.size >= page.total}
              onClick={() => setPageNo((p) => p + 1)}
            >
              下一页
            </Button>
          </div>
        )}
      </SectionCard>

      {terminalCount > 0 && (
        <p className="text-copy-12 text-muted-foreground">
          当前页有 {terminalCount} 张终态单（已发货 / 已取消）：终态行的来源、发货仓与承运渠道在数据库层被冻结，不能再改。
        </p>
      )}
    </div>
  );
}