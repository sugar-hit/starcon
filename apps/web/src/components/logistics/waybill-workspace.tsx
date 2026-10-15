'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import { AlertTriangle, Filter, PackagePlus, RefreshCw, Truck } from 'lucide-react';
import {
  Button,
  EmptyState,
  FormField,
  Input,
  Notice,
  PageHeader,
  SectionCard,
  StatCard,
} from '@starcon/ui';
import type {
  WaybillBatchResult,
  WaybillCapabilities,
  WaybillCarrierPage,
  WaybillCarrierRow,
  WaybillDispatchResult,
  WaybillRequestPage,
  WaybillRequestRow,
} from '../../../../../packages/api-contracts/src/index';
import {
  cancelWaybillRequest,
  canRetry,
  declarationCountText,
  declarationWarning,
  declaredValueText,
  dimensionText,
  dispatchWaybillQueue,
  errorText,
  fetchWaybillCapabilities,
  fetchWaybillCarriers,
  fetchWaybillQueue,
  fetchWaybillRequests,
  noWaybillHint,
  queuePositionText,
  requestStatusLabel,
  retryBlockedReason,
  retryWaybillRequest,
  statusSeverity,
  timeText,
  unavailableDimensions,
  weightText,
} from './waybill-model';

const PAGE_SIZE = 20;

const STATUS_OPTIONS = [
  { value: '', label: '全部状态' },
  { value: 'QUEUED', label: '已入队' },
  { value: 'SUBMITTING', label: '提交中' },
  { value: 'ISSUED', label: '已出号' },
  { value: 'FAILED', label: '申请失败' },
  { value: 'CANCELLED', label: '已作废' },
];

function SelectField(props: {
  id: string;
  label: string;
  value: string;
  onChange: (value: string) => void;
  options: { value: string; label: string }[];
}) {
  return (
    <div className="flex flex-col gap-1">
      <label htmlFor={props.id} className="text-sm font-medium">
        {props.label}
      </label>
      <select
        id={props.id}
        className="border-input h-9 rounded-md border bg-transparent px-2 text-sm"
        value={props.value}
        onChange={(e) => props.onChange(e.target.value)}
      >
        {props.options.map((option) => (
          <option key={option.value} value={option.value}>
            {option.label}
          </option>
        ))}
      </select>
    </div>
  );
}

const SEVERITY_CLASS: Record<string, string> = {
  ok: 'text-emerald-600 dark:text-emerald-400',
  warn: 'text-amber-600 dark:text-amber-400',
  danger: 'text-red-600 dark:text-red-400',
  muted: 'text-muted-foreground',
};

/**
 * 10.2.1.2 运单申请工作区。
 *
 * <p>蓝图：「向承运商申请运单号：传入收寄信息、包裹明细、申报信息，支持批量申请、
 * 失败重试、接口限流控制。」输出是「运单号 + 面单数据；失败原因」。
 *
 * <p>四个区块按现场的判断顺序排：
 * <b>① 能力与缺口 → ② 承运商登记 → ③ 限流队列 → ④ 申请清单</b>。
 * 把能力放在最前不是凑数：10.1 物流渠道整卷未交付，
 * 一个不先说清「哪些渠道还没接通」的页面，会让运营在大促期点下去才发现拿不到单号。
 */
export function WaybillWorkspace() {
  const [capabilities, setCapabilities] = useState<WaybillCapabilities | null>(null);
  const [carriers, setCarriers] = useState<WaybillCarrierPage | null>(null);
  const [queue, setQueue] = useState<Awaited<ReturnType<typeof fetchWaybillQueue>>>([]);
  const [page, setPage] = useState<WaybillRequestPage | null>(null);

  const [status, setStatus] = useState('');
  const [keyword, setKeyword] = useState('');
  const [carrierFilter, setCarrierFilter] = useState('');
  const [selectedCarrier, setSelectedCarrier] = useState('');

  const [dispatchResult, setDispatchResult] = useState<WaybillDispatchResult | null>(null);
  const [actionError, setActionError] = useState('');
  const [busy, setBusy] = useState(false);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState('');

  const load = useCallback(async () => {
    setLoading(true);
    setLoadError('');
    try {
      const [caps, carrierPage, requests, queueRows] = await Promise.all([
        fetchWaybillCapabilities(),
        fetchWaybillCarriers({ size: 50 }),
        fetchWaybillRequests({ status, keyword, page: 0, size: PAGE_SIZE }),
        selectedCarrier ? fetchWaybillQueue(selectedCarrier) : Promise.resolve([]),
      ]);
      setCapabilities(caps);
      setCarriers(carrierPage);
      setPage(requests);
      setQueue(queueRows);
    } catch (error) {
      setLoadError(errorText(error));
    } finally {
      setLoading(false);
    }
  }, [status, keyword, selectedCarrier]);

  useEffect(() => {
    void load();
  }, [load]);

  const gaps = useMemo(() => (capabilities ? unavailableDimensions(capabilities) : []), [capabilities]);
  const items = page?.items ?? [];
  const totalPages = page?.totalPages ?? 0;
  const connectedCount = (carriers?.items ?? []).filter((c) => c.connected).length;

  async function run(action: () => Promise<void>) {
    setBusy(true);
    setActionError('');
    try {
      await action();
    } catch (error) {
      setActionError(errorText(error));
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="flex flex-col gap-6">
      <PageHeader
        title="运单申请"
        description="向承运商申请运单号：批量申请、失败重试、按渠道限流排队"
      />

      {/* ① 能力与缺口 —— 10.1 未交付，必须先说清，否则运营会白点 */}
      <SectionCard title="能力与未接入" description="本模块做不到的事逐条列出，不用空列表或 0 冒充">
        {loading && !capabilities ? (
          <p className="text-muted-foreground text-sm">加载中…</p>
        ) : capabilities ? (
          <div className="flex flex-col gap-3">
            <div className="grid gap-3 sm:grid-cols-3">
              <StatCard label="承运商登记" value={carriers?.totalElements ?? 0} />
              <StatCard label="已接通" value={connectedCount} />
              <StatCard label="队列中" value={queue.length} />
            </div>
            {gaps.length === 0 ? (
              <Notice tone="success" title="全部能力可用" />
            ) : (
              <ul className="flex flex-col gap-2">
                {gaps.map((gap) => (
                  <li
                    key={gap.dimension}
                    className="border-border rounded-md border px-3 py-2 text-sm"
                  >
                    <div className="flex items-center gap-2">
                      <AlertTriangle className="h-4 w-4 text-amber-500" aria-hidden />
                      <span className="font-medium">{gap.label}</span>
                      <span className="text-muted-foreground">未交付：{gap.ownerModule}</span>
                    </div>
                    <p className="text-muted-foreground mt-1">{gap.impact}</p>
                  </li>
                ))}
              </ul>
            )}
          </div>
        ) : null}
      </SectionCard>

      {/* ② 承运商与渠道登记 */}
      <SectionCard
        title="承运商与渠道登记"
        description="登记 ≠ 接通：未接通的渠道提交会如实失败并写明原因，不会凭空生成运单号"
      >
        {carriers && carriers.items.length === 0 ? (
          <EmptyState
            title="还没有登记承运商"
            description="10.1 物流渠道未交付，承运商与渠道目前只能在本模块本地登记"
          />
        ) : (
          <div className="flex flex-col gap-2">
            {(carriers?.items ?? []).map((carrier: WaybillCarrierRow) => (
              <div
                key={carrier.id}
                className="border-border flex flex-wrap items-center gap-3 rounded-md border px-3 py-2 text-sm"
              >
                <span className="font-medium">{carrier.displayName}</span>
                <span className="text-muted-foreground">{carrier.code}</span>
                <span className="text-muted-foreground">{carrier.carrierChannelLabel}</span>
                <span className={carrier.connected ? SEVERITY_CLASS.ok : SEVERITY_CLASS.warn}>
                  {carrier.connected ? '已登记为接通' : '未接通'}
                </span>
                <span className="text-muted-foreground">
                  每 {carrier.rateWindowSeconds}s 最多 {carrier.rateLimitPerWindow} 次
                </span>
                {/* 「能不能下单」要给可执行的原因，而不是一句「不可用」 */}
                {carrier.submitBlockReason ? (
                  <span className="text-muted-foreground w-full">{carrier.submitBlockReason}</span>
                ) : null}
              </div>
            ))}
          </div>
        )}
      </SectionCard>

      {/* ③ 限流队列：排队必须是看得见的事实 */}
      <SectionCard
        title="渠道限流队列"
        description="批量申请按渠道排队，逐条放行；不并成一次同步调用"
      >
        <div className="flex flex-col gap-3">
          <div className="flex flex-wrap items-end gap-3">
            <SelectField
              id="queue-carrier"
              label="承运商"
              value={selectedCarrier}
              onChange={setSelectedCarrier}
              options={[
                { value: '', label: '选择承运商' },
                ...(carriers?.items ?? []).map((c) => ({ value: c.code, label: c.displayName })),
              ]}
            />
            <Button
              type="button"
              disabled={!selectedCarrier || busy}
              onClick={() =>
                void run(async () => {
                  const result = await dispatchWaybillQueue(selectedCarrier);
                  setDispatchResult(result);
                  await load();
                })
              }
            >
              <Truck className="mr-1 h-4 w-4" aria-hidden />
              按窗口放行
            </Button>
          </div>

          {dispatchResult ? (
            <Notice
              tone={dispatchResult.failed > 0 ? 'danger' : 'success'}
              title={`放行 ${dispatchResult.dispatched} 条`}
            >
              出号 {dispatchResult.succeeded}、限流 {dispatchResult.rateLimited}、失败{' '}
              {dispatchResult.failed}；仍在窗口外等待 {dispatchResult.skippedNotYetDue} 条。
              {dispatchResult.notes.map((note) => (
                <span key={note} className="mt-1 block">
                  {note}
                </span>
              ))}
            </Notice>
          ) : null}

          {selectedCarrier && queue.length === 0 ? (
            <EmptyState title="这个渠道没有排队的申请" description="所有票都已经放行或还未提交" />
          ) : (
            <ul className="flex flex-col gap-1">
              {queue.map((entry) => (
                <li
                  key={entry.id}
                  className="border-border flex flex-wrap items-center gap-3 rounded-md border px-3 py-2 text-sm"
                >
                  <span className="font-medium">{entry.requestNo}</span>
                  <span className="text-muted-foreground">{queuePositionText(entry)}</span>
                  {entry.lastFailureReason ? (
                    <span className="text-red-600 dark:text-red-400">{entry.lastFailureReason}</span>
                  ) : null}
                </li>
              ))}
            </ul>
          )}
        </div>
      </SectionCard>

      {/* ④ 申请清单 */}
      <SectionCard title="运单申请" description="逐包裹一张申请；重复提交同一幂等键不会产生第二张运单">
        <div className="flex flex-col gap-3">
          <div className="flex flex-wrap items-end gap-3">
            <FormField id="waybill-keyword" label="搜索">
              <Input
                id="waybill-keyword"
                value={keyword}
                onChange={(e) => setKeyword(e.target.value)}
                placeholder="申请单号 / 出库单号 / 销售订单号 / 运单号"
              />
            </FormField>
            <SelectField
              id="waybill-status"
              label="状态"
              value={status}
              onChange={setStatus}
              options={STATUS_OPTIONS}
            />
            <Button type="button" onClick={() => void load()} disabled={loading}>
              <RefreshCw className="mr-1 h-4 w-4" aria-hidden />
              刷新
            </Button>
          </div>

          {loadError ? <Notice tone="danger" title="加载失败">{loadError}</Notice> : null}
          {actionError ? <Notice tone="danger" title="操作失败">{actionError}</Notice> : null}

          {loading ? (
            <p className="text-muted-foreground text-sm">加载中…</p>
          ) : items.length === 0 ? (
            <EmptyState
              title="还没有运单申请"
              description="从待发货（9.4.1.1）挑一个包裹，填上收寄信息与申报明细后提交"
            />
          ) : (
            <div className="flex flex-col gap-2">
              {items.map((row: WaybillRequestRow) => (
                <RequestCard
                  key={row.id}
                  row={row}
                  busy={busy}
                  onRetry={() =>
                    void run(async () => {
                      await retryWaybillRequest(row.id, '人工重试');
                      await load();
                    })
                  }
                  onCancel={() =>
                    void run(async () => {
                      await cancelWaybillRequest(row.id, '人工作废');
                      await load();
                    })
                  }
                />
              ))}
              <p className="text-muted-foreground text-xs">
                共 {page?.totalElements ?? 0} 票
                {totalPages > 1 ? ` · ${totalPages} 页（当前显示前 ${PAGE_SIZE} 票）` : ''}
              </p>
            </div>
          )}
        </div>
      </SectionCard>
    </div>
  );
}

/**
 * 一张申请的卡片。
 *
 * <p>刻意把「运单号 / 没拿到运单号的原因」「失败原因」「能不能重试」三件事
 * 分成三行独立显示：它们回答的是三个不同的问题，挤成一行就会出现
 * 「显示已出号但其实没出号」或「给一个点了必然失败的��试按钮」。
 */
function RequestCard(props: {
  row: WaybillRequestRow;
  busy: boolean;
  onRetry: () => void;
  onCancel: () => void;
}) {
  const { row, busy } = props;
  const severity = statusSeverity(row);
  const warning = declarationWarning(row);
  return (
    <article className="border-border flex flex-col gap-2 rounded-md border px-3 py-3">
      <div className="flex flex-wrap items-center gap-3">
        <span className="font-medium">{row.requestNo}</span>
        <span className={SEVERITY_CLASS[severity]}>{requestStatusLabel(row)}</span>
        <span className="text-muted-foreground">
          {row.carrierCode} · {row.carrierChannelLabel}
        </span>
        <span className="text-muted-foreground">
          {row.outboundOrderNo} / {row.salesOrderNo}
        </span>
      </div>

      {/* 第一行：运单号 / 没有运单号的确切原因 */}
      <p className={`text-sm ${SEVERITY_CLASS[severity]}`}>{noWaybillHint(row)}</p>

      {/* 第二行：包裹与申报的事实 */}
      <p className="text-muted-foreground text-xs">
        重量 {weightText(row)} · 尺寸 {dimensionText(row)} · 申报 {declarationCountText(row)} ·{' '}
        {declaredValueText(row)}
      </p>
      {warning ? <p className="text-amber-600 dark:text-amber-400 text-xs">{warning}</p> : null}

      {/* 第三行：失败原因与重试入口 */}
      {row.failureReason ? (
        <p className="text-red-600 dark:text-red-400 text-xs">
          失败原因：{row.failureReason}
          {row.notBefore ? `（已排入下一次窗口：${timeText(row.notBefore)}）` : ''}
        </p>
      ) : null}

      <div className="flex flex-wrap items-center gap-2">
        {canRetry(row) ? (
          <Button type="button" size="sm" disabled={busy} onClick={props.onRetry}>
            <PackagePlus className="mr-1 h-4 w-4" aria-hidden />
            重试
          </Button>
        ) : (
          <span className="text-muted-foreground text-xs">{retryBlockedReason(row)}</span>
        )}
        {row.status !== 'ISSUED' && row.status !== 'CANCELLED' ? (
          <Button type="button" size="sm" variant="outline" disabled={busy} onClick={props.onCancel}>
            作废
          </Button>
        ) : null}
        <span className="text-muted-foreground text-xs">
          幂等键 {row.idempotencyKey} · 创建 {timeText(row.createdAt)}
          {row.issuedAt ? ` · 出号 ${timeText(row.issuedAt)}` : ''}
        </span>
      </div>
    </article>
  );
}
