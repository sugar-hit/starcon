'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import { ClipboardCheck, RefreshCw, TriangleAlert } from 'lucide-react';
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
  IncomingOrderDetail,
  IncomingOrderItemInput,
  IncomingOrderRow,
  IncomingQualityInspectionCapabilities,
  IncomingQualityInspectionOptions,
  IncomingTemplateDetail,
  IncomingTemplateInput,
} from '../../../../../packages/api-contracts/src/index';
import {
  CONCESSION_REQUIRED_HINT,
  ITEM_OPTIONS,
  ITEM_RESULT_OPTIONS,
  RESULT_OPTIONS,
  SAMPLING_HINT,
  STATUS_OPTIONS,
  addConcession,
  balanceIsBalanced,
  balanceText,
  cancelOrder,
  canComplete,
  completeOrder,
  createOrder,
  createTemplate,
  dispositionText,
  exportUrl,
  fetchCapabilities,
  fetchOptions,
  fetchOrder,
  fetchOrders,
  fetchSummary,
  fetchTemplate,
  fetchTemplates,
  fmtQuantity,
  itemLabel,
  itemResultLabel,
  isTerminal,
  missingItemsText,
  qualityInspectionQuery,
  recordItem,
  samplingText,
  totalConcession,
  totalDeducted,
  type QualityInspectionParams,
} from './quality-inspection-model';
import { errorText } from '../inventory/inventory-model';

/**
 * 6.4.2.1 质检单工作区。
 *
 * <p>蓝图：「来料/成品质检：抽样比例、检验项（外观、功能、规格、包装、认证标识）、检验结果记录、
 * 不合格判定与扣量。」输出「质检单（合格量/不合格量/让步接收量）；触发 7 库存的质量状态分流」。
 *
 * <p>界面纪律：
 *
 * <ul>
 *   <li><b>三量永远并排出现</b>，并把「合格 + 不合格 + 让步接收 = 本次质检覆盖的批量」
 *       这个等式直接写出来；等式不成立就显红。
 *   <li><b>抽样不让人手填</b>：应抽数量由后端按模板算，页面只显示算式原文。
 *   <li><b>逐项结论不是一个总分</b>：五项检验项逐条显示，每项的扣量单列。
 *   <li><b>让步接收要原因与权限码</b>：表单两者必填，缺一会被后端拒。
 *   <li><b>分流状态照实显示</b>：warehouse 没有可用的授权写端点 ⇒ 页面写「未分流」并写明影响，
 *       绝不显示「已分流」。
 *   <li>加载 / 空 / 错误三态齐全。
 * </ul>
 */
export function QualityInspectionWorkspace() {
  const [params, setParams] = useState<QualityInspectionParams>({ page: 0, size: 20 });
  const [data, setData] = useState<Awaited<ReturnType<typeof fetchOrders>> | null>(null);
  const [caps, setCaps] = useState<IncomingQualityInspectionCapabilities | null>(null);
  const [options, setOptions] = useState<IncomingQualityInspectionOptions | null>(null);
  const [summary, setSummary] = useState<Awaited<ReturnType<typeof fetchSummary>> | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [keyword, setKeyword] = useState('');
  const [status, setStatus] = useState('');
  const [result, setResult] = useState('');

  const [detail, setDetail] = useState<IncomingOrderDetail | null>(null);
  const [detailError, setDetailError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const [creating, setCreating] = useState(false);
  const [templatesOpen, setTemplatesOpen] = useState(false);

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      setData(await fetchOrders(params));
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
        setCaps(await fetchCapabilities());
      } catch {
        // capabilities 拿不到不该让整页空掉：列表仍然可用，只是边界说明退化。
        setCaps(null);
      }
      try {
        setOptions(await fetchOptions());
      } catch {
        setOptions(null);
      }
      try {
        setSummary(await fetchSummary());
      } catch {
        setSummary(null);
      }
    })();
  }, []);

  const orders = data?.rows ?? [];

  const openDetail = useCallback(async (orderId: string) => {
    setDetailError(null);
    try {
      setDetail(await fetchOrder(orderId));
    } catch (e) {
      setDetail(null);
      setDetailError(errorText(e));
    }
  }, []);

  const refreshDetail = useCallback(async (orderId: string) => {
    try {
      setDetail(await fetchOrder(orderId));
    } catch {
      /* 详情刷新失败不打断当前操作，列表刷新时会重新拉。 */
    }
  }, []);

  const dispositionUnavailable = useMemo(
    () => caps?.downstream.find((d) => d.code === 'INVENTORY_DISPOSITION'),
    [caps],
  );

  return (
    <div className="flex flex-col gap-4">
      <PageHeader
        title="质检单"
        description="来料 / 成品质检：按模板算抽样，逐项记录检验结论，判出合格量 / 不合格量 / 让步接收量。"
        actions={
          <div className="flex gap-2">
            <Button variant="ghost" onClick={() => void load()} disabled={loading}>
              <RefreshCw className={loading ? 'animate-spin' : ''} />
              刷新
            </Button>
            <Button variant="outline" onClick={() => setTemplatesOpen(true)}>
              质检模板
            </Button>
            <Button onClick={() => setCreating(true)} disabled={busy}>
              <ClipboardCheck />
              新建质检单
            </Button>
          </div>
        }
      />

      {/* 模块边界常驻：不合格的货不能直接进可售库存 */}
      <div className="flex items-start gap-2 rounded-md border border-amber-500/40 bg-amber-500/10 p-3 text-sm">
        <TriangleAlert className="mt-0.5 size-4 shrink-0" />
        <div>
          <p className="font-medium">质检不合格的货不能直接进可售库存</p>
          <p className="text-muted-foreground">
            {caps?.quantityBalanceNote ??
              '合格量 + 不合格量 + 让步接收量 = 本次质检覆盖的批量；不合格量单列，按模板指定去向往待检或残次。'}
          </p>
        </div>
      </div>

      {dispositionUnavailable && !dispositionUnavailable.available && (
        <div className="flex items-start gap-2 rounded-md border border-sky-500/40 bg-sky-500/10 p-3 text-sm">
          <TriangleAlert className="mt-0.5 size-4 shrink-0" />
          <div>
            <p className="font-medium">
              库存分流未接线 → {dispositionUnavailable.ownerModule}
            </p>
            <p className="text-muted-foreground">{dispositionUnavailable.impact}</p>
          </div>
        </div>
      )}

      {caps && caps.downstream.length > 0 && (
        <SectionCard title="上下游接入情况">
          <div className="flex flex-wrap gap-2">
            {caps.upstream.map((item) => (
              <span
                key={item.code}
                title={item.reason ?? ''}
                className={
                  item.available
                    ? 'rounded-md border px-2 py-1 text-xs'
                    : 'text-muted-foreground rounded-md border border-dashed px-2 py-1 text-xs'
                }
              >
                {item.label}
                {item.available ? '（已接入）' : ` · 未交付 → ${item.ownerModule}`}
              </span>
            ))}
            {caps.downstream.map((item) => (
              <span
                key={item.code}
                title={item.impact ?? ''}
                className={
                  item.available
                    ? 'rounded-md border px-2 py-1 text-xs'
                    : 'text-muted-foreground rounded-md border border-dashed px-2 py-1 text-xs'
                }
              >
                {item.label}
                {item.available ? '（已接入）' : ` · 未交付 → ${item.ownerModule}`}
              </span>
            ))}
          </div>
          <p className="text-muted-foreground mt-2 text-xs">{caps.note}</p>
        </SectionCard>
      )}

      {summary && (
        <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
          <StatCard label="质检单" value={summary.orderCount} />
          <StatCard
            label="合格量"
            value={fmtQuantity(summary.qualifiedQuantity)}
            comparisonLabel={`已检 ${fmtQuantity(summary.inspectedQuantity)}`}
          />
          <StatCard
            label="不合格量"
            value={fmtQuantity(summary.defectiveQuantity)}
            polarity="negative"
          />
          <StatCard
            label="让步接收量"
            value={fmtQuantity(summary.concessionQuantity)}
            comparisonLabel={`可售 ${fmtQuantity(summary.sellableQuantity)}`}
          />
        </div>
      )}

      <SectionCard title="筛选">
        <div className="flex flex-wrap items-end gap-3">
          <label className="flex flex-col gap-1 text-sm">
            <span className="text-muted-foreground">关键字</span>
            <Input
              value={keyword}
              placeholder="质检单号 / SKU / 模板 / 收货单号"
              onChange={(e) => setKeyword(e.target.value)}
              className="w-72"
            />
          </label>
          <label className="flex flex-col gap-1 text-sm">
            <span className="text-muted-foreground">状态</span>
            <select
              className="h-9 rounded-md border px-2"
              value={status}
              onChange={(e) => setStatus(e.target.value)}
            >
              {STATUS_OPTIONS.map((o) => (
                <option key={o.value} value={o.value}>
                  {o.label}
                </option>
              ))}
            </select>
          </label>
          <label className="flex flex-col gap-1 text-sm">
            <span className="text-muted-foreground">结论</span>
            <select
              className="h-9 rounded-md border px-2"
              value={result}
              onChange={(e) => setResult(e.target.value)}
            >
              {RESULT_OPTIONS.map((o) => (
                <option key={o.value} value={o.value}>
                  {o.label}
                </option>
              ))}
            </select>
          </label>
          <Button
            onClick={() => setParams({ ...params, keyword, status, result, page: 0 })}
          >
            查询
          </Button>
        </div>
        <p className="text-muted-foreground mt-2 text-xs">{SAMPLING_HINT}</p>
      </SectionCard>

      {error && (
        <div className="flex items-start gap-2 rounded-md border border-red-500/40 bg-red-500/10 p-3 text-sm text-red-600">
          <TriangleAlert className="mt-0.5 size-4 shrink-0" />
          <p>{error}</p>
        </div>
      )}

      {loading && <p className="text-muted-foreground text-sm">正在加载质检单…</p>}

      {!loading && orders.length === 0 && !error && (
        <EmptyState
          title="还没有质检单"
          description="新建质检单时选模板、填收货单号与 SKU —— 到货量与应抽数量都由服务端按上游收货单与抽样规则算出来。"
        />
      )}

      {!loading && orders.length > 0 && (
        <SectionCard title={`质检单（${data?.total ?? 0}）`}>
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead className="text-muted-foreground text-left">
                <tr>
                  <th className="px-2 py-1">质检单号</th>
                  <th className="px-2 py-1">SKU / 模板</th>
                  <th className="px-2 py-1">收货单</th>
                  <th className="px-2 py-1">到货 / 批量</th>
                  <th className="px-2 py-1">应抽 / 实抽</th>
                  <th className="px-2 py-1">合格</th>
                  <th className="px-2 py-1">不合格</th>
                  <th className="px-2 py-1">让步接收</th>
                  <th className="px-2 py-1">结论</th>
                  <th className="px-2 py-1">分流</th>
                  <th className="px-2 py-1" />
                </tr>
              </thead>
              <tbody>
                {orders.map((order: IncomingOrderRow) => (
                  <tr key={order.id} className="border-t">
                    <td className="px-2 py-1">{order.inspectionNo}</td>
                    <td className="px-2 py-1">
                      {order.skuCode}
                      <span className="text-muted-foreground block text-xs">
                        {order.templateName}
                      </span>
                    </td>
                    <td className="px-2 py-1">{order.receiptCode ?? '—'}</td>
                    <td className="px-2 py-1">
                      {fmtQuantity(order.arrivedQuantity)} / {fmtQuantity(order.lotSize)}
                    </td>
                    <td className="px-2 py-1">
                      {fmtQuantity(order.sampleQuantity)} / {fmtQuantity(order.sampledQuantity)}
                    </td>
                    <td className="px-2 py-1">{fmtQuantity(order.qualifiedQuantity)}</td>
                    <td className="px-2 py-1 text-destructive">
                      {fmtQuantity(order.defectiveQuantity)}
                    </td>
                    <td className="px-2 py-1">{fmtQuantity(order.concessionQuantity)}</td>
                    <td className="px-2 py-1">
                      {order.resultLabel ?? '—'}
                      {order.criticalFailure && (
                        <Badge className="ml-1" tone="danger">
                          关键项不合格
                        </Badge>
                      )}
                    </td>
                    <td className="px-2 py-1">
                      <span className="text-xs">{dispositionText(order)}</span>
                    </td>
                    <td className="px-2 py-1">
                      <Button variant="ghost" size="sm" onClick={() => void openDetail(order.id)}>
                        详情
                      </Button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </SectionCard>
      )}

      {detail && (
        <DetailModal
          detail={detail}
          busy={busy}
          onClose={() => setDetail(null)}
          onChanged={(orderId) => {
            void refreshDetail(orderId);
            void load();
          }}
          onBusy={setBusy}
        />
      )}

      {detailError && (
        <div className="flex items-start gap-2 rounded-md border border-red-500/40 bg-red-500/10 p-3 text-sm text-red-600">
          <TriangleAlert className="mt-0.5 size-4 shrink-0" />
          <p>{detailError}</p>
        </div>
      )}

      {creating && (
        <CreateOrderModal
          options={options}
          busy={busy}
          onClose={() => setCreating(false)}
          onCreated={(orderId) => {
            setCreating(false);
            void load();
            void openDetail(orderId);
          }}
          onBusy={setBusy}
        />
      )}

      {templatesOpen && (
        <TemplatesModal busy={busy} onClose={() => setTemplatesOpen(false)} onBusy={setBusy} />
      )}
    </div>
  );
}

// ================================================================ 详情

function DetailModal({
  detail,
  busy,
  onClose,
  onChanged,
  onBusy,
}: {
  detail: IncomingOrderDetail;
  busy: boolean;
  onClose: () => void;
  onChanged: (orderId: string) => void;
  onBusy: (busy: boolean) => void;
}) {
  const order = detail.order;
  const balanced = balanceIsBalanced(order);
  const [itemError, setItemError] = useState<string | null>(null);
  const [concessionError, setConcessionError] = useState<string | null>(null);

  // 逐项结论录入
  const [itemTarget, setItemTarget] = useState<string>('');
  const [itemResult, setItemResult] = useState('PASS');
  const [failedSample, setFailedSample] = useState('0');
  const [deducted, setDeducted] = useState('0');
  const [defectDescription, setDefectDescription] = useState('');

  // 让步接收
  const [concessionQuantity, setConcessionQuantity] = useState('0');
  const [concessionReason, setConcessionReason] = useState('');
  const [concessionPermission, setConcessionPermission] = useState(
    'trade.quality-inspection.concession',
  );

  const pending = detail.pendingItems;
  const terminal = isTerminal(order.status);

  const submitItem = async () => {
    setItemError(null);
    const templateItemId = itemTarget || pending[0]?.id;
    if (!templateItemId) {
      setItemError('没有可录入的检验项');
      return;
    }
    const input: IncomingOrderItemInput = {
      templateItemId,
      result: itemResult as IncomingOrderItemInput['result'],
      failedSampleQuantity: Number(failedSample || '0'),
      deductedQuantity: Number(deducted || '0'),
      defectType: null,
      defectDescription,
      sampledQuantity: null,
      inspectedAt: null,
    };
    onBusy(true);
    try {
      await recordItem(order.id, input);
      setDefectDescription('');
      setFailedSample('0');
      setDeducted('0');
      onChanged(order.id);
    } catch (e) {
      setItemError(errorText(e));
    } finally {
      onBusy(false);
    }
  };

  const submitConcession = async () => {
    setConcessionError(null);
    onBusy(true);
    try {
      await addConcession(order.id, {
        quantity: Number(concessionQuantity || '0'),
        reason: concessionReason,
        permissionCode: concessionPermission,
        itemCode: null,
      });
      setConcessionReason('');
      onChanged(order.id);
    } catch (e) {
      setConcessionError(errorText(e));
    } finally {
      onBusy(false);
    }
  };

  const complete = async () => {
    setItemError(null);
    onBusy(true);
    try {
      await completeOrder(order.id, { conclusionNote: '', inspectedAt: null });
      onChanged(order.id);
    } catch (e) {
      setItemError(errorText(e));
    } finally {
      onBusy(false);
    }
  };

  const cancel = async () => {
    setItemError(null);
    onBusy(true);
    try {
      await cancelOrder(order.id, { cancelReason: '质检员作废' });
      onChanged(order.id);
    } catch (e) {
      setItemError(errorText(e));
    } finally {
      onBusy(false);
    }
  };

  return (
    <Modal open onOpenChange={(open) => !open && onClose()}>
      <ModalContent className="max-w-4xl">
        <ModalHeader>
          <ModalTitle>
            {order.inspectionNo} · {order.skuCode}
          </ModalTitle>
          <ModalDescription>
            {order.templateName} · 状态 {order.statusLabel}
            {order.resultLabel ? ` · 结论 ${order.resultLabel}` : ''}
          </ModalDescription>
        </ModalHeader>

        {/* 三量 + 等式 */}
        <SectionCard title="三量（合格 / 不合格 / 让步接收）">
          <div className="grid gap-3 sm:grid-cols-4">
            <StatCard label="合格量" value={fmtQuantity(order.qualifiedQuantity)} />
            <StatCard
              label="不合格量"
              value={fmtQuantity(order.defectiveQuantity)}
              polarity="negative"
            />
            <StatCard label="让步接收量" value={fmtQuantity(order.concessionQuantity)} />
            <StatCard label="本次质检覆盖的批量" value={fmtQuantity(order.lotSize)} />
          </div>
          <p
            className={
              balanced
                ? 'text-muted-foreground mt-2 text-sm'
                : 'mt-2 text-sm text-destructive'
            }
          >
            {balanceText(order)}
            {balanced ? '（自洽）' : '（**不自洽** —— 这三个数对不上，请重新核对）'}
          </p>
          <p className="text-muted-foreground mt-1 text-xs">
            到货量 {fmtQuantity(order.arrivedQuantity)} ·{' '}
            {samplingText(order)} · 抽样算式：{detail.samplingBasis}
          </p>
          <p className="text-muted-foreground mt-1 text-xs">{detail.balanceNote}</p>
          <p className="text-muted-foreground mt-1 text-xs">{detail.safetyNote}</p>
        </SectionCard>

        {/* 分流 */}
        <SectionCard title="库存质量状态分流">
          <p className="text-sm">{dispositionText(order)}</p>
          <p className="text-muted-foreground mt-1 text-xs">{detail.dispositionNote}</p>
        </SectionCard>

        {/* 逐项结论 */}
        <SectionCard title="逐项检验结论">
          {detail.items.length === 0 ? (
            <p className="text-muted-foreground text-sm">还没有录入任何检验结论。</p>
          ) : (
            <table className="w-full text-sm">
              <thead className="text-muted-foreground text-left">
                <tr>
                  <th className="px-2 py-1">检验项</th>
                  <th className="px-2 py-1">结论</th>
                  <th className="px-2 py-1">抽检不合格件数</th>
                  <th className="px-2 py-1">批次扣量</th>
                  <th className="px-2 py-1">缺陷描述</th>
                </tr>
              </thead>
              <tbody>
                {detail.items.map((item) => (
                  <tr key={item.id} className="border-t">
                    <td className="px-2 py-1">
                      {itemLabel(item.itemCode, item.itemCodeLabel)}
                      {item.critical && (
                        <Badge className="ml-1" tone="danger">
                          关键项
                        </Badge>
                      )}
                    </td>
                    <td className="px-2 py-1">{itemResultLabel(item.result, item.resultLabel)}</td>
                    <td className="px-2 py-1">{fmtQuantity(item.failedSampleQuantity)}</td>
                    <td className="px-2 py-1">{fmtQuantity(item.deductedQuantity)}</td>
                    <td className="px-2 py-1">{item.defectDescription || '—'}</td>
                  </tr>
                ))}
              </tbody>
              <tfoot>
                <tr className="border-t">
                  <td className="px-2 py-1 font-medium">合计</td>
                  <td className="px-2 py-1" />
                  <td className="px-2 py-1" />
                  <td className="px-2 py-1 font-medium">
                    {fmtQuantity(totalDeducted(detail.items))}
                  </td>
                  <td className="px-2 py-1" />
                </tr>
              </tfoot>
            </table>
          )}
          {missingItemsText(detail) && (
            <p className="text-muted-foreground mt-2 text-xs">{missingItemsText(detail)}</p>
          )}

          {!terminal && (
            <div className="mt-3 flex flex-col gap-2 border-t pt-3">
              <p className="text-sm font-medium">录入检验结论</p>
              <div className="flex flex-wrap items-end gap-2">
                <label className="flex flex-col gap-1 text-sm">
                  <span className="text-muted-foreground">检验项</span>
                  <select
                    className="h-9 rounded-md border px-2"
                    value={itemTarget || pending[0]?.id || ''}
                    onChange={(e) => setItemTarget(e.target.value)}
                  >
                    {pending.map((item) => (
                      <option key={item.id} value={item.id}>
                        {itemLabel(item.itemCode, item.itemCodeLabel)}
                        {item.critical ? '（关键项）' : ''}
                      </option>
                    ))}
                  </select>
                </label>
                <label className="flex flex-col gap-1 text-sm">
                  <span className="text-muted-foreground">结论</span>
                  <select
                    className="h-9 rounded-md border px-2"
                    value={itemResult}
                    onChange={(e) => setItemResult(e.target.value)}
                  >
                    {ITEM_RESULT_OPTIONS.map((o) => (
                      <option key={o.value} value={o.value}>
                        {o.label}
                      </option>
                    ))}
                  </select>
                </label>
                <label className="flex flex-col gap-1 text-sm">
                  <span className="text-muted-foreground">抽检不合格件数</span>
                  <Input
                    type="number"
                    min="0"
                    value={failedSample}
                    onChange={(e) => setFailedSample(e.target.value)}
                    className="w-32"
                  />
                </label>
                <label className="flex flex-col gap-1 text-sm">
                  <span className="text-muted-foreground">批次扣量</span>
                  <Input
                    type="number"
                    min="0"
                    value={deducted}
                    onChange={(e) => setDeducted(e.target.value)}
                    className="w-32"
                  />
                </label>
                <label className="flex flex-col gap-1 text-sm">
                  <span className="text-muted-foreground">缺陷描述</span>
                  <Input
                    value={defectDescription}
                    onChange={(e) => setDefectDescription(e.target.value)}
                    className="w-64"
                  />
                </label>
                <Button onClick={() => void submitItem()} disabled={busy}>
                  录入
                </Button>
              </div>
            </div>
          )}
        </SectionCard>

        {/* 让步接收 */}
        <SectionCard title="让步接收">
          {detail.concessions.length === 0 ? (
            <p className="text-muted-foreground text-sm">还没有登记让步接收。</p>
          ) : (
            <table className="w-full text-sm">
              <thead className="text-muted-foreground text-left">
                <tr>
                  <th className="px-2 py-1">量</th>
                  <th className="px-2 py-1">检验项</th>
                  <th className="px-2 py-1">原因</th>
                  <th className="px-2 py-1">权限码</th>
                </tr>
              </thead>
              <tbody>
                {detail.concessions.map((concession) => (
                  <tr key={concession.id} className="border-t">
                    <td className="px-2 py-1">{fmtQuantity(concession.quantity)}</td>
                    <td className="px-2 py-1">
                      {concession.itemCode
                        ? itemLabel(concession.itemCode, concession.itemCodeLabel)
                        : '整批'}
                    </td>
                    <td className="px-2 py-1">{concession.reason}</td>
                    <td className="px-2 py-1 text-xs">{concession.permissionCode}</td>
                  </tr>
                ))}
              </tbody>
              <tfoot>
                <tr className="border-t">
                  <td className="px-2 py-1 font-medium">
                    {fmtQuantity(totalConcession(detail.concessions))}
                  </td>
                  <td className="px-2 py-1" />
                  <td className="px-2 py-1" />
                  <td className="px-2 py-1" />
                </tr>
              </tfoot>
            </table>
          )}
          {!terminal && (
            <div className="mt-3 flex flex-col gap-2 border-t pt-3">
              <p className="text-sm font-medium">登记让步接收</p>
              <p className="text-muted-foreground text-xs">{CONCESSION_REQUIRED_HINT}</p>
              <div className="flex flex-wrap items-end gap-2">
                <label className="flex flex-col gap-1 text-sm">
                  <span className="text-muted-foreground">让步量</span>
                  <Input
                    type="number"
                    min="0"
                    value={concessionQuantity}
                    onChange={(e) => setConcessionQuantity(e.target.value)}
                    className="w-32"
                  />
                </label>
                <label className="flex flex-col gap-1 text-sm">
                  <span className="text-muted-foreground">原因（必填）</span>
                  <Input
                    value={concessionReason}
                    onChange={(e) => setConcessionReason(e.target.value)}
                    className="w-64"
                  />
                </label>
                <label className="flex flex-col gap-1 text-sm">
                  <span className="text-muted-foreground">权限码（必填）</span>
                  <Input
                    value={concessionPermission}
                    onChange={(e) => setConcessionPermission(e.target.value)}
                    className="w-72"
                  />
                </label>
                <Button onClick={() => void submitConcession()} disabled={busy}>
                  登记
                </Button>
              </div>
            </div>
          )}
          {concessionError && (
            <p className="mt-2 text-sm text-destructive">{concessionError}</p>
          )}
        </SectionCard>

        {itemError && <p className="text-sm text-destructive">{itemError}</p>}

        <ModalFooter>
          {!terminal && (
            <>
              <Button
                variant="outline"
                onClick={() => void complete()}
                disabled={busy || !canComplete(detail)}
                title={
                  canComplete(detail)
                    ? '完结质检单并判出三量'
                    : '还有检验项没有结论，不能完结'
                }
              >
                完结并判三量
              </Button>
              <Button variant="ghost" onClick={() => void cancel()} disabled={busy}>
                作废
              </Button>
            </>
          )}
          <a
            className="text-sm underline underline-offset-4"
            href={exportUrl(order.id)}
            target="_blank"
            rel="noreferrer"
          >
            导出 CSV
          </a>
          <Button variant="outline" onClick={onClose}>
            关闭
          </Button>
        </ModalFooter>
      </ModalContent>
    </Modal>
  );
}

// ================================================================ 新建质检单

function CreateOrderModal({
  options,
  busy,
  onClose,
  onCreated,
  onBusy,
}: {
  options: IncomingQualityInspectionOptions | null;
  busy: boolean;
  onClose: () => void;
  onCreated: (orderId: string) => void;
  onBusy: (busy: boolean) => void;
}) {
  const [templateId, setTemplateId] = useState(options?.templates[0]?.id ?? '');
  const [skuCode, setSkuCode] = useState('');
  const [receiptCode, setReceiptCode] = useState('');
  const [batchNo, setBatchNo] = useState('');
  const [lotSize, setLotSize] = useState('');
  const [supplierName, setSupplierName] = useState('');
  const [currencyCode, setCurrencyCode] = useState('');
  const [unitPrice, setUnitPrice] = useState('');
  const [formError, setFormError] = useState<string | null>(null);

  const submit = async () => {
    setFormError(null);
    onBusy(true);
    try {
      const created = await createOrder({
        templateId,
        skuCode,
        skuId: null,
        skuName: null,
        supplierId: null,
        supplierName: supplierName || null,
        receiptCode,
        receiptLineId: null,
        sourceRefCode: null,
        batchNo: batchNo || null,
        lotSize: lotSize ? Number(lotSize) : null,
        currencyCode: currencyCode || null,
        exchangeRate: null,
        unitConversionFactor: null,
        unitPrice: unitPrice ? Number(unitPrice) : null,
        note: null,
      });
      onCreated(created.id);
    } catch (e) {
      setFormError(errorText(e));
    } finally {
      onBusy(false);
    }
  };

  return (
    <Modal open onOpenChange={(open) => !open && onClose()}>
      <ModalContent className="max-w-2xl">
        <ModalHeader>
          <ModalTitle>新建质检单</ModalTitle>
          <ModalDescription>
            到货量读 8 收货的收货单（服务端写入，不认客户端报的数量）；应抽数量按模板的抽样规则在服务端算。
          </ModalDescription>
        </ModalHeader>

        <div className="flex flex-col gap-3">
          <label className="flex flex-col gap-1 text-sm">
            <span className="text-muted-foreground">质检模板</span>
            <select
              className="h-9 rounded-md border px-2"
              value={templateId}
              onChange={(e) => setTemplateId(e.target.value)}
            >
              {(options?.templates ?? []).map((t) => (
                <option key={t.id} value={t.id}>
                  {t.code} · {t.name}（{t.inspectionTypeLabel} · {t.itemCount} 个检验项）
                </option>
              ))}
            </select>
          </label>
          <label className="flex flex-col gap-1 text-sm">
            <span className="text-muted-foreground">SKU 编码（必填）</span>
            <Input value={skuCode} onChange={(e) => setSkuCode(e.target.value)} />
          </label>
          <label className="flex flex-col gap-1 text-sm">
            <span className="text-muted-foreground">来源收货单号（必填）</span>
            <Input
              value={receiptCode}
              onChange={(e) => setReceiptCode(e.target.value)}
              placeholder="例如 GR-2026-0001"
            />
          </label>
          <div className="grid gap-3 sm:grid-cols-2">
            <label className="flex flex-col gap-1 text-sm">
              <span className="text-muted-foreground">本次质检覆盖的批量（留空 = 整批到货）</span>
              <Input
                type="number"
                min="0"
                value={lotSize}
                onChange={(e) => setLotSize(e.target.value)}
                placeholder="分批到货时填这一批的量"
              />
            </label>
            <label className="flex flex-col gap-1 text-sm">
              <span className="text-muted-foreground">批次号</span>
              <Input value={batchNo} onChange={(e) => setBatchNo(e.target.value)} />
            </label>
            <label className="flex flex-col gap-1 text-sm">
              <span className="text-muted-foreground">供应商</span>
              <Input value={supplierName} onChange={(e) => setSupplierName(e.target.value)} />
            </label>
            <label className="flex flex-col gap-1 text-sm">
              <span className="text-muted-foreground">币种</span>
              <Input
                value={currencyCode}
                onChange={(e) => setCurrencyCode(e.target.value)}
                placeholder="例如 USD"
              />
            </label>
            <label className="flex flex-col gap-1 text-sm">
              <span className="text-muted-foreground">单价</span>
              <Input
                type="number"
                min="0"
                step="0.0001"
                value={unitPrice}
                onChange={(e) => setUnitPrice(e.target.value)}
                placeholder="录了单价才会算扣量金额"
              />
            </label>
          </div>
          <p className="text-muted-foreground text-xs">
            三量之和 = 本次质检覆盖的批量（不是到货量）—— 分批到货时每次质检只覆盖其中一批。
          </p>
          {formError && <p className="text-sm text-destructive">{formError}</p>}
        </div>

        <ModalFooter>
          <Button onClick={() => void submit()} disabled={busy || !templateId || !skuCode || !receiptCode}>
            建单
          </Button>
          <Button variant="outline" onClick={onClose}>
            取消
          </Button>
        </ModalFooter>
      </ModalContent>
    </Modal>
  );
}

// ================================================================ 质检模板

function TemplatesModal({
  busy,
  onClose,
  onBusy,
}: {
  busy: boolean;
  onClose: () => void;
  onBusy: (busy: boolean) => void;
}) {
  const [templates, setTemplates] = useState<Awaited<ReturnType<typeof fetchTemplates>> | null>(null);
  const [selected, setSelected] = useState<IncomingTemplateDetail | null>(null);
  const [formError, setFormError] = useState<string | null>(null);

  const [code, setCode] = useState('');
  const [name, setName] = useState('');
  const [ratio, setRatio] = useState('0.1');
  const [minSample, setMinSample] = useState('1');
  const [maxSample, setMaxSample] = useState('0');
  const [roundingMode, setRoundingMode] = useState('CEILING');
  const [criticalFailureAction, setCriticalFailureAction] = useState('REJECT_LOT');
  const [defectiveTarget, setDefectiveTarget] = useState('PENDING_INSPECTION');
  const [items, setItems] = useState<
    { itemCode: string; name: string; critical: boolean }[]
  >([{ itemCode: 'APPEARANCE', name: '外观检查', critical: true }]);

  const load = useCallback(async () => {
    try {
      setTemplates(await fetchTemplates({ page: 0, size: 50 }));
    } catch (e) {
      setFormError(errorText(e));
    }
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  const open = async (templateId: string) => {
    setFormError(null);
    try {
      const detail = await fetchTemplate(templateId);
      setSelected(detail);
    } catch (e) {
      setFormError(errorText(e));
    }
  };

  const submit = async () => {
    setFormError(null);
    const input: IncomingTemplateInput = {
      code,
      name,
      categoryId: null,
      categoryName: null,
      inspectionType: 'INCOMING',
      samplingRatio: Number(ratio),
      minSampleQuantity: Number(minSample),
      maxSampleQuantity: Number(maxSample),
      roundingMode: roundingMode as IncomingTemplateInput['roundingMode'],
      criticalFailureAction:
        criticalFailureAction as IncomingTemplateInput['criticalFailureAction'],
      defectiveTarget: defectiveTarget as IncomingTemplateInput['defectiveTarget'],
      status: 'ACTIVE',
      note: null,
      items: items.map((item, index) => ({
        itemCode: item.itemCode as IncomingTemplateInput['items'][number]['itemCode'],
        name: item.name,
        critical: item.critical,
        acceptanceCriteria: '',
        sampleCoverage: 1,
        sortOrder: index,
      })),
    };
    onBusy(true);
    try {
      await createTemplate(input);
      setCode('');
      setName('');
      setItems([{ itemCode: 'APPEARANCE', name: '外观检查', critical: true }]);
      await load();
    } catch (e) {
      setFormError(errorText(e));
    } finally {
      onBusy(false);
    }
  };

  return (
    <Modal open onOpenChange={(open) => !open && onClose()}>
      <ModalContent className="max-w-4xl">
        <ModalHeader>
          <ModalTitle>质检模板</ModalTitle>
          <ModalDescription>
            模板按类目维护，抽样规则（比例 / 最小 / 最大 / 舍入口径）与检验项都在模板上。
          </ModalDescription>
        </ModalHeader>

        <SectionCard title="现有模板">
          {templates && templates.rows.length > 0 ? (
            <table className="w-full text-sm">
              <thead className="text-muted-foreground text-left">
                <tr>
                  <th className="px-2 py-1">编码</th>
                  <th className="px-2 py-1">名称</th>
                  <th className="px-2 py-1">类型</th>
                  <th className="px-2 py-1">抽样比例</th>
                  <th className="px-2 py-1">检验项</th>
                  <th className="px-2 py-1">状态</th>
                  <th className="px-2 py-1" />
                </tr>
              </thead>
              <tbody>
                {templates.rows.map((t) => (
                  <tr key={t.id} className="border-t">
                    <td className="px-2 py-1">{t.code}</td>
                    <td className="px-2 py-1">{t.name}</td>
                    <td className="px-2 py-1">{t.inspectionTypeLabel}</td>
                    <td className="px-2 py-1">{t.samplingRatio}</td>
                    <td className="px-2 py-1">
                      {t.itemCount}（关键项 {t.criticalItemCount}）
                    </td>
                    <td className="px-2 py-1">{t.statusLabel}</td>
                    <td className="px-2 py-1">
                      <Button variant="ghost" size="sm" onClick={() => void open(t.id)}>
                        检验项
                      </Button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          ) : (
            <p className="text-muted-foreground text-sm">还没有质检模板。</p>
          )}
        </SectionCard>

        {selected && (
          <SectionCard title={`${selected.template.code} 的检验项`}>
            <table className="w-full text-sm">
              <thead className="text-muted-foreground text-left">
                <tr>
                  <th className="px-2 py-1">检验项</th>
                  <th className="px-2 py-1">关键项</th>
                  <th className="px-2 py-1">判定标准</th>
                </tr>
              </thead>
              <tbody>
                {selected.items.map((item) => (
                  <tr key={item.id} className="border-t">
                    <td className="px-2 py-1">{itemLabel(item.itemCode, item.itemCodeLabel)}</td>
                    <td className="px-2 py-1">{item.critical ? '是' : '否'}</td>
                    <td className="px-2 py-1">{item.acceptanceCriteria || '—'}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </SectionCard>
        )}

        <SectionCard title="新建模板">
          <div className="flex flex-wrap items-end gap-2">
            <label className="flex flex-col gap-1 text-sm">
              <span className="text-muted-foreground">编码</span>
              <Input value={code} onChange={(e) => setCode(e.target.value)} className="w-40" />
            </label>
            <label className="flex flex-col gap-1 text-sm">
              <span className="text-muted-foreground">名称</span>
              <Input value={name} onChange={(e) => setName(e.target.value)} className="w-56" />
            </label>
            <label className="flex flex-col gap-1 text-sm">
              <span className="text-muted-foreground">抽样比例（0~1）</span>
              <Input
                type="number"
                min="0"
                max="1"
                step="0.001"
                value={ratio}
                onChange={(e) => setRatio(e.target.value)}
                className="w-32"
              />
            </label>
            <label className="flex flex-col gap-1 text-sm">
              <span className="text-muted-foreground">最小抽样数</span>
              <Input
                type="number"
                min="0"
                value={minSample}
                onChange={(e) => setMinSample(e.target.value)}
                className="w-28"
              />
            </label>
            <label className="flex flex-col gap-1 text-sm">
              <span className="text-muted-foreground">最大抽样数（0 = 不限）</span>
              <Input
                type="number"
                min="0"
                value={maxSample}
                onChange={(e) => setMaxSample(e.target.value)}
                className="w-32"
              />
            </label>
            <label className="flex flex-col gap-1 text-sm">
              <span className="text-muted-foreground">舍入口径</span>
              <select
                className="h-9 rounded-md border px-2"
                value={roundingMode}
                onChange={(e) => setRoundingMode(e.target.value)}
              >
                <option value="CEILING">向上取整</option>
                <option value="ROUND_HALF_UP">四舍五入</option>
                <option value="FLOOR">向下取整</option>
              </select>
            </label>
            <label className="flex flex-col gap-1 text-sm">
              <span className="text-muted-foreground">关键项不合格时</span>
              <select
                className="h-9 rounded-md border px-2"
                value={criticalFailureAction}
                onChange={(e) => setCriticalFailureAction(e.target.value)}
              >
                <option value="REJECT_LOT">整批拒收</option>
                <option value="DEDUCT_ONLY">仅扣量</option>
              </select>
            </label>
            <label className="flex flex-col gap-1 text-sm">
              <span className="text-muted-foreground">不合格量去向</span>
              <select
                className="h-9 rounded-md border px-2"
                value={defectiveTarget}
                onChange={(e) => setDefectiveTarget(e.target.value)}
              >
                <option value="PENDING_INSPECTION">待检</option>
                <option value="DEFECTIVE">残次</option>
              </select>
            </label>
          </div>

          <div className="mt-3 flex flex-col gap-2">
            <p className="text-sm font-medium">检验项</p>
            {items.map((item, index) => (
              <div key={index} className="flex flex-wrap items-end gap-2">
                <label className="flex flex-col gap-1 text-sm">
                  <span className="text-muted-foreground">项目</span>
                  <select
                    className="h-9 rounded-md border px-2"
                    value={item.itemCode}
                    onChange={(e) =>
                      setItems((prev) =>
                        prev.map((row, i) =>
                          i === index ? { ...row, itemCode: e.target.value } : row,
                        ),
                      )
                    }
                  >
                    {ITEM_OPTIONS.map((o) => (
                      <option key={o.value} value={o.value}>
                        {o.label}
                      </option>
                    ))}
                  </select>
                </label>
                <label className="flex flex-col gap-1 text-sm">
                  <span className="text-muted-foreground">名称</span>
                  <Input
                    value={item.name}
                    onChange={(e) =>
                      setItems((prev) =>
                        prev.map((row, i) => (i === index ? { ...row, name: e.target.value } : row)),
                      )
                    }
                    className="w-48"
                  />
                </label>
                <label className="flex items-center gap-2 text-sm">
                  <input
                    type="checkbox"
                    checked={item.critical}
                    onChange={(e) =>
                      setItems((prev) =>
                        prev.map((row, i) =>
                          i === index ? { ...row, critical: e.target.checked } : row,
                        ),
                      )
                    }
                  />
                  关键项
                </label>
                <Button
                  variant="ghost"
                  size="sm"
                  onClick={() => setItems((prev) => prev.filter((_, i) => i !== index))}
                  disabled={items.length <= 1}
                >
                  删除
                </Button>
              </div>
            ))}
            <Button
              variant="outline"
              size="sm"
              onClick={() =>
                setItems((prev) => [...prev, { itemCode: 'FUNCTION', name: '功能检查', critical: false }])
              }
            >
              加一个检验项
            </Button>
          </div>

          {formError && <p className="text-sm text-destructive">{formError}</p>}
        </SectionCard>

        <ModalFooter>
          <Button onClick={() => void submit()} disabled={busy || !code || !name}>
            建模板
          </Button>
          <Button variant="outline" onClick={onClose}>
            关闭
          </Button>
        </ModalFooter>
      </ModalContent>
    </Modal>
  );
}
