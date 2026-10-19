'use client';

import { useCallback, useEffect, useState } from 'react';
import { RefreshCw, TriangleAlert } from 'lucide-react';
import { Badge, Button, EmptyState, Input, PageHeader, SectionCard, StatCard } from '@starcon/ui';
import type {
  PlatformOrderSyncBatchDetail,
  PlatformOrderSyncBatchPage,
  PlatformOrderSyncCapabilities,
  PlatformOrderSyncExceptionPage,
  PlatformOrderSyncOrderDetail,
  PlatformOrderSyncOrderPage,
  PlatformOrderSyncResult,
} from '../../../../../packages/api-contracts/src/index';
import {
  EVENT_TEMPLATE,
  batchStatusLabel,
  batchTone,
  componentSourceLabel,
  componentTypeLabel,
  documentStatusLabel,
  errorCodeLabel,
  eventOutcomeLabel,
  fetchBatch,
  fetchBatches,
  fetchCapabilities,
  fetchExceptions,
  fetchOrder,
  fetchOrders,
  fmt,
  money,
  moment,
  normalizedStatusLabel,
  orderSourceLabel,
  parseEventPayload,
  statusTone,
  submitPoll,
  submitWebhook,
  triggerTypeLabel,
} from './platform-order-sync-model';

/**
 * 9.1.2.1 平台订单同步工作区。
 *
 * <p>页面刻意把**「重复不是失败」**做成一眼能核对的事实：批次列表里「落地 / 更新 / 重复 /
 * 失败」四格并排，重复单独占一格 —— 把重复混进失败会让运营以为平台侧出了问题，
 * 而它其实是 inbox 去重**正常工作**的证据。
 *
 * <p>同样把**「归一状态」与「单据状态」分成两列**（不是一列）：前者是平台订单的生命周期，
 * 后者是「有没有进 9.2 审单队列」。合成一列就会出现「这单到底在哪个阶段」答不出来。
 *
 * <p>加载 / 空 / 错误三态齐全；下游未交付的环节写明归属模块，不给点下去 404 的按钮。
 */
export function PlatformOrderSyncWorkspace() {
  const [batches, setBatches] = useState<PlatformOrderSyncBatchPage | null>(null);
  const [orders, setOrders] = useState<PlatformOrderSyncOrderPage | null>(null);
  const [exceptions, setExceptions] = useState<PlatformOrderSyncExceptionPage | null>(null);
  const [capabilities, setCapabilities] = useState<PlatformOrderSyncCapabilities | null>(null);
  const [selectedBatch, setSelectedBatch] = useState<PlatformOrderSyncBatchDetail | null>(null);
  const [selectedOrder, setSelectedOrder] = useState<PlatformOrderSyncOrderDetail | null>(null);
  const [platformCode, setPlatformCode] = useState('');
  const [shopCode, setShopCode] = useState('');
  const [status, setStatus] = useState('');
  const [keyword, setKeyword] = useState('');
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      // 本仓库开了 exactOptionalPropertyTypes：可选字段**不能**显式传 undefined，
      // 必须条件展开 —— 传 undefined 会在编译期报错，而不是运行时被忽略。
      const [b, o, x, c] = await Promise.all([
        fetchBatches({
          ...(platformCode ? { platformCode } : {}),
          ...(shopCode ? { shopCode } : {}),
          ...(status ? { status } : {}),
          size: 50,
        }),
        fetchOrders({
          ...(platformCode ? { platformCode } : {}),
          ...(shopCode ? { shopCode } : {}),
          ...(keyword.trim() ? { keyword } : {}),
          size: 50,
        }),
        fetchExceptions({ ...(platformCode ? { platformCode } : {}), size: 50 }),
        fetchCapabilities(),
      ]);
      setBatches(b);
      setOrders(o);
      setExceptions(x);
      setCapabilities(c);
    } catch (e) {
      setError(e instanceof Error ? e.message : '加载失败，请稍后重试');
    } finally {
      setLoading(false);
    }
  }, [platformCode, shopCode, status, keyword]);

  useEffect(() => {
    void load();
  }, [load]);

  return (
    <div className="space-y-4">
      <PageHeader
        title="平台订单同步"
        description="webhook 实时接入 + 轮询补拉，字段解析、状态归一、金额与税费还原、地址快照、组件展开（按映射版本）。同一外部订单号在同一来源范围内唯一，重复事件走 inbox 去重，绝不产生第二张订单。"
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

      {capabilities && !capabilities.enumMappingSyncAvailable && (
        <div className="text-copy-12 text-muted-foreground rounded-md border px-3 py-2">
          2.1.2.1 平台枚举映射的同步入口当前不可用：平台状态查不到映射时本模块**拒收**该事件
          （不猜内部状态码），请在「映射副本」里租户自维护一条。
        </div>
      )}

      {capabilities && (
        <div className="text-copy-12 text-muted-foreground rounded-md border px-3 py-2">
          {capabilities.statusNormalizationNote} {capabilities.amountRestorationNote}
        </div>
      )}

      {capabilities && capabilities.downstreamGaps.filter((g) => !g.available).length > 0 && (
        <div className="text-copy-12 text-muted-foreground rounded-md border px-3 py-2">
          未交付的下游：
          {capabilities.downstreamGaps
            .filter((g) => !g.available)
            .map((g) => `${g.moduleId} ${g.name}（归属 ${g.service}）`)
            .join('、')}
          。平台单已进统一订单模型，但这些环节还没有出口。
        </div>
      )}

      <div className="grid gap-4 sm:grid-cols-4">
        <StatCard label="同步批次" value={batches?.total ?? 0} />
        <StatCard label="平台单" value={orders?.total ?? 0} />
        <StatCard label="重复事件（inbox 去重）" value={orders?.duplicateEventCount ?? 0} />
        <StatCard label="异常明细" value={exceptions?.total ?? 0} />
      </div>

      <SectionCard
        title="同步批次"
        description="「接收 / 落地 / 更新 / 重复 / 失败」五格并排：重复单独占一格 —— 它是 inbox 去重正常工作的证据，不是失败。"
      >
        <div className="mb-4 flex flex-wrap items-end gap-2">
          <div className="flex flex-col gap-1">
            <span className="text-copy-12 text-muted-foreground">平台</span>
            <Input
              value={platformCode}
              placeholder="AMAZON / SHOPIFY ..."
              onChange={(e) => setPlatformCode(e.target.value)}
            />
          </div>
          <div className="flex flex-col gap-1">
            <span className="text-copy-12 text-muted-foreground">店铺</span>
            <Input
              value={shopCode}
              placeholder="SHOP-A"
              onChange={(e) => setShopCode(e.target.value)}
            />
          </div>
          <div className="flex flex-col gap-1">
            <span className="text-copy-12 text-muted-foreground">批次结论</span>
            <select
              className="border-border bg-card text-copy-13 h-9 rounded-md border px-2"
              value={status}
              onChange={(e) => setStatus(e.target.value)}
            >
              <option value="">全部</option>
              {['RUNNING', 'SUCCEEDED', 'PARTIAL', 'FAILED'].map((v) => (
                <option key={v} value={v}>
                  {batchStatusLabel(v)}
                </option>
              ))}
            </select>
          </div>
        </div>

        {loading ? (
          <p className="text-copy-13 text-muted-foreground py-8 text-center">加载中…</p>
        ) : !batches || batches.rows.length === 0 ? (
          <EmptyState
            title="还没有同步批次"
            description="批次在受理平台事件时产生：webhook 实时接入或轮询补拉各记一批。"
          />
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full text-left text-sm">
              <thead className="text-copy-12 text-muted-foreground border-b">
                <tr>
                  <th className="px-2 py-2 font-medium">批次</th>
                  <th className="px-2 py-2 font-medium">来源</th>
                  <th className="px-2 py-2 font-medium">方式</th>
                  <th className="px-2 py-2 font-medium">结论</th>
                  <th className="px-2 py-2 text-right font-medium">接收</th>
                  <th className="px-2 py-2 text-right font-medium">落地</th>
                  <th className="px-2 py-2 text-right font-medium">更新</th>
                  <th className="px-2 py-2 text-right font-medium">重复</th>
                  <th className="px-2 py-2 text-right font-medium">失败</th>
                  <th className="px-2 py-2 font-medium">时间窗</th>
                  <th className="px-2 py-2 font-medium">开始</th>
                </tr>
              </thead>
              <tbody>
                {batches.rows.map((row) => (
                  <tr
                    key={row.id}
                    className="hover:bg-muted/40 cursor-pointer border-b last:border-0"
                    onClick={() => {
                      void fetchBatch(row.id)
                        .then(setSelectedBatch)
                        .catch(() => setSelectedBatch(null));
                    }}
                  >
                    <td className="px-2 py-2 font-mono text-xs">{row.id.slice(0, 8)}</td>
                    <td className="px-2 py-2">
                      {row.platformCode} / {row.shopCode}
                    </td>
                    <td className="px-2 py-2">{triggerTypeLabel(row.triggerType)}</td>
                    <td className="px-2 py-2">
                      <Badge tone={batchTone(row.status)}>{batchStatusLabel(row.status)}</Badge>
                    </td>
                    <td className="px-2 py-2 text-right">{row.receivedCount}</td>
                    <td className="px-2 py-2 text-right">{row.createdCount}</td>
                    <td className="px-2 py-2 text-right">{row.updatedCount}</td>
                    <td className="px-2 py-2 text-right">{row.duplicateCount}</td>
                    <td className="px-2 py-2 text-right">{row.failedCount}</td>
                    <td className="px-2 py-2 text-xs">
                      {row.windowStartAt && row.windowEndAt
                        ? `${moment(row.windowStartAt)} → ${moment(row.windowEndAt)}`
                        : '—'}
                    </td>
                    <td className="px-2 py-2 text-xs">{moment(row.startedAt)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}

        {selectedBatch && (
          <div className="border-border mt-4 rounded-md border">
            <div className="border-border flex items-center justify-between border-b px-4 py-2">
              <span className="text-copy-13 font-medium">
                批次 {selectedBatch.batch.id.slice(0, 8)} 的逐条受理结果
              </span>
              <Button variant="ghost" onClick={() => setSelectedBatch(null)}>
                收起
              </Button>
            </div>
            <div className="overflow-x-auto">
              <table className="w-full text-left text-sm">
                <thead className="text-copy-12 text-muted-foreground border-b">
                  <tr>
                    <th className="px-2 py-2 font-medium">外部单号</th>
                    <th className="px-2 py-2 font-medium">事件</th>
                    <th className="px-2 py-2 font-medium">受理结论</th>
                    <th className="px-2 py-2 font-medium">平台状态</th>
                    <th className="px-2 py-2 font-medium">归一状态</th>
                    <th className="px-2 py-2 font-medium">错误</th>
                  </tr>
                </thead>
                <tbody>
                  {selectedBatch.events.map((e) => (
                    <tr key={e.id} className="border-b last:border-0">
                      <td className="px-2 py-2 font-mono text-xs">{e.externalOrderNo}</td>
                      <td className="px-2 py-2 text-xs">{e.eventType}</td>
                      <td className="px-2 py-2 text-xs">{eventOutcomeLabel(e.outcome)}</td>
                      <td className="px-2 py-2 text-xs">{e.platformStatus || '—'}</td>
                      <td className="px-2 py-2 text-xs">
                        {e.normalizedStatus ? normalizedStatusLabel(e.normalizedStatus) : '—'}
                      </td>
                      <td className="px-2 py-2 text-xs">
                        {e.errorCode ? `${errorCodeLabel(e.errorCode)}：${e.errorMessage}` : '—'}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            {selectedBatch.exceptions.length > 0 && (
              <div className="border-border border-t px-4 py-3">
                <div className="text-copy-13 mb-2 font-medium">异常明细</div>
                <ul className="space-y-1">
                  {selectedBatch.exceptions.map((x) => (
                    <li key={x.id} className="text-copy-12 text-muted-foreground">
                      <span className="text-foreground">{x.externalOrderNo || '（无单号）'}</span> ·{' '}
                      {errorCodeLabel(x.errorCode)} · {x.errorMessage}
                    </li>
                  ))}
                </ul>
              </div>
            )}
          </div>
        )}
      </SectionCard>

      <SectionCard
        title="平台单"
        description="平台单与手工单共用同一张统一订单模型（sales_order），只有来源标记不同 —— 所以它们在订单列表里是同一批单。"
      >
        <div className="mb-4 flex flex-wrap items-end gap-2">
          <div className="flex flex-col gap-1">
            <span className="text-copy-12 text-muted-foreground">关键字</span>
            <Input
              value={keyword}
              placeholder="外部单号 / 我方单号"
              onChange={(e) => setKeyword(e.target.value)}
            />
          </div>
        </div>

        {loading ? (
          <p className="text-copy-13 text-muted-foreground py-8 text-center">加载中…</p>
        ) : !orders || orders.rows.length === 0 ? (
          <EmptyState
            title="还没有平台单"
            description="平台单在受理平台事件时产生。它落的是统一订单模型，所以也会出现在订单列表里。"
          />
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full text-left text-sm">
              <thead className="text-copy-12 text-muted-foreground border-b">
                <tr>
                  <th className="px-2 py-2 font-medium">我方单号</th>
                  <th className="px-2 py-2 font-medium">外部单号</th>
                  <th className="px-2 py-2 font-medium">来源</th>
                  <th className="px-2 py-2 font-medium">平台状态</th>
                  <th className="px-2 py-2 font-medium">单据状态</th>
                  <th className="px-2 py-2 text-right font-medium">平台应付</th>
                  <th className="px-2 py-2 text-right font-medium">本侧还原</th>
                  <th className="px-2 py-2 font-medium">映射版本</th>
                  <th className="px-2 py-2 font-medium">同步时刻</th>
                </tr>
              </thead>
              <tbody>
                {orders.rows.map((row) => (
                  <tr
                    key={row.id}
                    className="hover:bg-muted/40 cursor-pointer border-b last:border-0"
                    onClick={() => {
                      void fetchOrder(row.id)
                        .then(setSelectedOrder)
                        .catch(() => setSelectedOrder(null));
                    }}
                  >
                    <td className="px-2 py-2 font-mono text-xs">{row.orderNo}</td>
                    <td className="px-2 py-2 font-mono text-xs">{row.externalOrderNo}</td>
                    <td className="px-2 py-2 text-xs">
                      {orderSourceLabel(row.orderSource)} / {row.shopCode}
                    </td>
                    <td className="px-2 py-2">
                      <Badge tone={statusTone(row.normalizedStatus)}>
                        {normalizedStatusLabel(row.normalizedStatus)}
                      </Badge>
                      <span className="text-copy-12 text-muted-foreground ml-1">
                        ({row.platformStatus || '—'})
                      </span>
                    </td>
                    <td className="px-2 py-2 text-xs">{documentStatusLabel(row.documentStatus)}</td>
                    <td className="px-2 py-2 text-right">
                      {money(row.platformGrandTotalAmount, row.currency)}
                    </td>
                    <td className="px-2 py-2 text-right">
                      {money(row.restoredGrandTotalAmount, row.currency)}
                    </td>
                    <td className="px-2 py-2 font-mono text-xs">{row.mappingVersion || '—'}</td>
                    <td className="px-2 py-2 text-xs">{moment(row.syncedAt)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}

        {selectedOrder && (
          <div className="border-border mt-4 rounded-md border">
            <div className="border-border flex items-center justify-between border-b px-4 py-2">
              <span className="text-copy-13 font-medium">
                {selectedOrder.order.orderNo} 的组件展开（按映射版本）
              </span>
              <Button variant="ghost" onClick={() => setSelectedOrder(null)}>
                收起
              </Button>
            </div>
            <div className="overflow-x-auto">
              <table className="w-full text-left text-sm">
                <thead className="text-copy-12 text-muted-foreground border-b">
                  <tr>
                    <th className="px-2 py-2 font-medium">行</th>
                    <th className="px-2 py-2 font-medium">来源</th>
                    <th className="px-2 py-2 font-medium">组件码</th>
                    <th className="px-2 py-2 font-medium">名称</th>
                    <th className="px-2 py-2 font-medium">类型</th>
                    <th className="px-2 py-2 text-right font-medium">数量</th>
                    <th className="px-2 py-2 text-right font-medium">单价</th>
                    <th className="px-2 py-2 font-medium">映射版本</th>
                  </tr>
                </thead>
                <tbody>
                  {selectedOrder.components.map((c) => (
                    <tr key={c.id} className="border-b last:border-0">
                      <td className="px-2 py-2">{c.lineNo}</td>
                      <td className="px-2 py-2 text-xs">
                        {componentSourceLabel(c.componentSource)}
                      </td>
                      <td className="px-2 py-2 font-mono text-xs">{c.componentCode}</td>
                      <td className="px-2 py-2 text-xs">{c.componentName || '—'}</td>
                      <td className="px-2 py-2 text-xs">
                        {componentTypeLabel(c.componentType)}
                        {c.externalType && c.externalType !== c.componentType ? (
                          <span className="text-copy-12 text-muted-foreground ml-1">
                            (平台报 {c.externalType})
                          </span>
                        ) : null}
                      </td>
                      <td className="px-2 py-2 text-right">{fmt(c.quantity)}</td>
                      <td className="px-2 py-2 text-right">
                        {money(c.unitAmount, selectedOrder.order.currency)}
                      </td>
                      <td className="px-2 py-2 font-mono text-xs">{c.mappingVersion || '—'}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </div>
        )}
      </SectionCard>

      <SectionCard
        title="异常明细"
        description="被拒收的事件：状态没映射 / 金额对不上 / 订单内容已被平台改过 / 外部单号撞号。错误码是机器可判定的，处置建议按它给。"
      >
        {loading ? (
          <p className="text-copy-13 text-muted-foreground py-8 text-center">加载中…</p>
        ) : !exceptions || exceptions.rows.length === 0 ? (
          <EmptyState
            title="没有异常"
            description="每一批平台事件的逐条结论都在「同步批次」里；这里只列被拒收的那几条。"
          />
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full text-left text-sm">
              <thead className="text-copy-12 text-muted-foreground border-b">
                <tr>
                  <th className="px-2 py-2 font-medium">外部单号</th>
                  <th className="px-2 py-2 font-medium">来源</th>
                  <th className="px-2 py-2 font-medium">错误码</th>
                  <th className="px-2 py-2 font-medium">说明</th>
                  <th className="px-2 py-2 font-medium">时刻</th>
                </tr>
              </thead>
              <tbody>
                {exceptions.rows.map((row) => (
                  <tr key={row.id} className="border-b last:border-0">
                    <td className="px-2 py-2 font-mono text-xs">{row.externalOrderNo || '—'}</td>
                    <td className="px-2 py-2 text-xs">
                      {row.platformCode || '—'} / {row.shopCode || '—'}
                    </td>
                    <td className="px-2 py-2">
                      <Badge tone="danger">{errorCodeLabel(row.errorCode)}</Badge>
                    </td>
                    <td className="px-2 py-2 text-xs">{row.errorMessage}</td>
                    <td className="px-2 py-2 text-xs">{moment(row.createdAt)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </SectionCard>

      <ReplayPanel platformCode={platformCode} shopCode={shopCode} onDone={load} />
    </div>
  );
}

/**
 * 手工重放平台事件（**真实写入**，不是演练）。
 *
 * <p>平台事件本来就是平台给的东西：运营手里通常已经有原始报文。把报文粘进来重放，
 * 比照着界面重新拼一遍更接近真实场景 —— 而且字段名拼错时后端会**明确拒收**，
 * 不会半落地。
 */
function ReplayPanel({
  platformCode,
  shopCode,
  onDone,
}: {
  platformCode: string;
  shopCode: string;
  onDone: () => void | Promise<void>;
}) {
  const [raw, setRaw] = useState(JSON.stringify(EVENT_TEMPLATE, null, 2));
  const [busy, setBusy] = useState(false);
  const [result, setResult] = useState<PlatformOrderSyncResult | null>(null);
  const [failure, setFailure] = useState<string | null>(null);

  const replay = async (mode: 'webhook' | 'poll') => {
    setBusy(true);
    setFailure(null);
    setResult(null);
    try {
      const parsed = parseEventPayload(raw);
      if (!parsed.ok) {
        setFailure(parsed.message);
        return;
      }
      const platform = platformCode.trim() || 'AMAZON';
      const shop = shopCode.trim() || 'SHOP-A';
      const response =
        mode === 'webhook'
          ? await submitWebhook({
              platformCode: platform,
              shopCode: shop,
              events: [parsed.value as never],
            })
          : await submitPoll({
              platformCode: platform,
              shopCode: shop,
              windowStart: '2026-03-01T00:00:00Z',
              windowEnd: '2026-03-02T00:00:00Z',
              orders: [parsed.value as never],
            });
      setResult(response);
      await onDone();
    } catch (e) {
      setFailure(e instanceof Error ? e.message : '重放失败');
    } finally {
      setBusy(false);
    }
  };

  return (
    <SectionCard
      title="手工重放平台事件"
      description="把平台报文粘进来重放（真实写入，不是演练）。webhook 与轮询补拉走同一条落库路径：同一份事件重复重放会被 inbox 去重，不会产生第二张订单。"
    >
      <textarea
        className="border-border bg-card text-copy-12 min-h-64 w-full rounded-md border p-3 font-mono"
        value={raw}
        onChange={(e) => setRaw(e.target.value)}
      />
      <div className="mt-3 flex flex-wrap items-center gap-2">
        <Button onClick={() => void replay('webhook')} disabled={busy}>
          按 webhook 受理
        </Button>
        <Button variant="outline" onClick={() => void replay('poll')} disabled={busy}>
          按轮询补拉受理
        </Button>
        <Button variant="ghost" onClick={() => setRaw(JSON.stringify(EVENT_TEMPLATE, null, 2))}>
          恢复模板
        </Button>
      </div>
      {failure && (
        <div className="border-destructive/40 bg-destructive/5 text-destructive mt-3 flex items-center gap-2 rounded-md border px-3 py-2 text-sm">
          <TriangleAlert className="h-4 w-4 shrink-0" />
          {failure}
        </div>
      )}
      {result && (
        <div className="text-copy-12 mt-3 rounded-md border px-3 py-2">
          批次 {result.batchId.slice(0, 8)}：接收 {result.receivedCount} · 落地{' '}
          {result.createdCount} · 更新 {result.updatedCount} · 重复 {result.duplicateCount} · 失败{' '}
          {result.failedCount}
          {result.results.map((r, i) => (
            <div key={i} className="text-muted-foreground mt-1">
              {r.externalOrderNo} → {eventOutcomeLabel(r.outcome)}
              {r.errorCode ? `（${errorCodeLabel(r.errorCode)}：${r.errorMessage}）` : ''}
            </div>
          ))}
        </div>
      )}
    </SectionCard>
  );
}
