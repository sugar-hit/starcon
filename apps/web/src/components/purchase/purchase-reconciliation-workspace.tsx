'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import { AlertTriangle, Download, FileSpreadsheet, RefreshCw, Scale } from 'lucide-react';
import {
  Badge,
  Button,
  EmptyState,
  FormField,
  Input,
  Modal,
  ModalContent,
  ModalDescription,
  ModalFooter,
  ModalHeader,
  ModalTitle,
  PageHeader,
  SectionCard,
  Segmented,
  StatCard,
} from '@starcon/ui';
import type {
  PurchaseReconciliationConfirmationCreate,
  PurchaseReconciliationCreate,
  PurchaseReconciliationDeductionCreate,
  PurchaseReconciliationDiscrepancyResolve,
  PurchaseReconciliationReopen,
  ReconciliationBasis,
  ReconciliationCapabilities,
  ReconciliationDetail,
  ReconciliationDiscrepancyRow,
  ReconciliationLineRow,
  ReconciliationOptions,
  ReconciliationPage,
  ReconciliationRow,
} from '../../../../../packages/api-contracts/src/index';
import {
  actionAllowed,
  actionBlockedReason,
  addReconciliationDeduction,
  closeReconciliation,
  confirmationText,
  createReconciliation,
  discrepancyStatusTone,
  discrepancyText,
  eventText,
  fetchReconciliation,
  fetchReconciliationCapabilities,
  fetchReconciliationDiscrepancies,
  fetchReconciliationLines,
  fetchReconciliationOptions,
  fetchReconciliations,
  fmtDate,
  fmtMoney,
  fmtQty,
  fmtTime,
  lineAmountText,
  qualityDeductionText,
  recordReconciliationConfirmation,
  reconciliationExportUrl,
  reopenReconciliation,
  resolveReconciliationDiscrepancy,
  sendReconciliation,
  statusTone,
  supplierText,
  type ReconciliationParams,
} from './purchase-reconciliation-model';

/**
 * 6.5.1.1 采购对账工作区。
 *
 * <p>蓝图：「按期间汇总应收数量、单价、金额、代扣代缴费用、索赔，生成对账单发给供应商确认，
 * 差异挂起。」加粗句：「<b>对账由采购发起、12 财务执行付款</b>，两侧数据以对账单为交接点，
 * 避免采购擅自确认应付。」
 *
 * <p>界面纪律：
 *
 * <ul>
 *   <li><b>顶部常驻「未接线」提示</b>：质检扣量（6.4.2.1）与 12 财务**没有**被当成
 *       「没有扣量」或「已付款」—— 页面把它们列出来并写清影响。</li>
 *   <li><b>汇总写「建议应付」</b>：采购侧不确认应付，页面不给「应付已确认」的按钮。</li>
 *   <li><b>差异逐条</b>：差在哪个字段、我方值、供应商值、差额逐条列；差异可挂起、可解决。</li>
 *   <li><b>明细行带质检扣量状态</b>：未接线时显示「上游未接线（未扣量）」，数量显示「无数据」。</li>
 *   <li>加载 / 空 / 错误三态齐全。</li>
 * </ul>
 */
export function PurchaseReconciliationWorkspace() {
  const [params, setParams] = useState<ReconciliationParams>({ page: 0, size: 20 });
  const [data, setData] = useState<ReconciliationPage | null>(null);
  const [caps, setCaps] = useState<ReconciliationCapabilities | null>(null);
  const [options, setOptions] = useState<ReconciliationOptions | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [keyword, setKeyword] = useState('');
  const [status, setStatus] = useState('');
  const [supplierRefId, setSupplierRefId] = useState('');
  const [periodType, setPeriodType] = useState('');
  const [creating, setCreating] = useState(false);
  const [busy, setBusy] = useState(false);
  const [detailId, setDetailId] = useState<string | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const [page, capability, opts] = await Promise.all([
        fetchReconciliations(params),
        fetchReconciliationCapabilities(),
        fetchReconciliationOptions(),
      ]);
      setData(page);
      setCaps(capability);
      setOptions(opts);
    } catch (e) {
      setError(e instanceof Error ? e.message : '加载对账单失败，请重试');
    } finally {
      setLoading(false);
    }
  }, [params]);

  useEffect(() => {
    void load();
  }, [load]);

  // 稳定引用：子组件的 useCallback 依赖它们，每帧新建会让详情反复重载。
  const reportError = useCallback((message: string) => setError(message), []);
  const reload = useCallback(() => void load(), [load]);

  const summary = data?.summary;
  const rows = data?.rows ?? [];

  return (
    <div className="space-y-6">
      <PageHeader
        title="采购对账"
        description="按期间与供应商汇总应收数量、单价、金额、代扣代缴费用与索赔，生成对账单发给供应商确认，差异逐条挂起处理。对账由采购发起，付款由 12 财务执行。"
        actions={
          <div className="flex items-center gap-2">
            <Button variant="ghost" onClick={() => void load()} disabled={loading}>
              <RefreshCw className={loading ? 'animate-spin' : ''} />
              刷新
            </Button>
            <Button onClick={() => setCreating(true)}>
              <Scale className="h-4 w-4" />
              发起对账
            </Button>
          </div>
        }
      />

      {/* 未接线事实的显式提示：不让空白冒充「这些能力没做」。 */}
      {caps && <UnavailableBanner caps={caps} />}

      {summary && (
        <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
          <StatCard label="对账单总数" value={summary.total} unit="单" />
          <StatCard label="已到货应收" value={fmtMoney(summary.totalReceivedAmount)} />
          <StatCard label="索赔冲减" value={fmtMoney(summary.totalClaimAmount)} />
          <StatCard
            label="采购侧建议应付（未确认）"
            value={fmtMoney(summary.totalSuggestedPayableAmount)}
            polarity="neutral"
          />
        </div>
      )}

      <SectionCard
        title="筛选"
        description="状态、供应商、期间类型与关键字可叠加。金额按币种分桶，跨币种不相加。"
      >
        <div className="grid gap-3 md:grid-cols-4">
          <FormField id="recon-status" label="对账单状态">
            <Input
              id="recon-status"
              value={status}
              placeholder="草稿 / 已发送待确认 / 有差异待处理 / 已确认 / 已关闭"
              onChange={(e) => {
                setStatus(e.target.value);
                setParams((p) => ({ ...p, status: e.target.value, page: 0 }));
              }}
            />
          </FormField>
          <FormField id="recon-supplier" label="供应商">
            <Input
              id="recon-supplier"
              value={options?.suppliers.find((s) => s.supplierRefId === supplierRefId)
                ? `${supplierText(options.suppliers.find((s) => s.supplierRefId === supplierRefId)!)}`
                : ''}
              placeholder={
                options?.suppliers.length
                  ? options.suppliers
                      .map((s) => `${s.supplierCode} ${s.supplierName}`)
                      .join(' / ')
                  : '暂无可对账的供应商（已交付事实里还没有）'
              }
              onChange={(e) => {
                const found = options?.suppliers.find(
                  (s) => `${s.supplierCode} ${s.supplierName}` === e.target.value.trim(),
                );
                setSupplierRefId(found?.supplierRefId ?? '');
                setParams((p) => ({
                  ...p,
                  supplierRefId: found?.supplierRefId ?? '',
                  page: 0,
                }));
              }}
            />
          </FormField>
          <FormField id="recon-period" label="期间类型">
            <Input
              id="recon-period"
              value={periodType}
              placeholder="MONTHLY / QUARTERLY"
              onChange={(e) => {
                setPeriodType(e.target.value);
                setParams((p) => ({ ...p, periodType: e.target.value, page: 0 }));
              }}
            />
          </FormField>
          <FormField id="recon-keyword" label="关键字">
            <Input
              id="recon-keyword"
              value={keyword}
              placeholder="对账单号 / 供应商编码 / 供应商名称"
              onChange={(e) => {
                setKeyword(e.target.value);
                setParams((p) => ({ ...p, keyword: e.target.value, page: 0 }));
              }}
            />
          </FormField>
        </div>
      </SectionCard>

      {error && (
        <SectionCard title="加载失败">
          <div className="text-destructive text-copy-13">{error}</div>
          <div className="mt-3">
            <Button variant="ghost" onClick={() => void load()}>
              重试
            </Button>
          </div>
        </SectionCard>
      )}

      {loading && !data ? (
        <SectionCard title="对账单列表">
          <div className="text-muted-foreground text-copy-13">加载中…</div>
        </SectionCard>
      ) : rows.length === 0 ? (
        <EmptyState
          icon={<FileSpreadsheet className="h-6 w-6" />}
          title="还没有对账单"
          description="对账基数取自已交付事实（6.3.2.2 到货流水 + 11.2.3.3 供应商索赔）。该期间内没有到货与索赔时，这里是空的。"
          action={
            <Button onClick={() => setCreating(true)}>
              <Scale className="h-4 w-4" />
              发起对账
            </Button>
          }
        />
      ) : (
        <SectionCard title="对账单列表" description="点一行看明细、差异清单与确认状态。">
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead>
                <tr className="text-muted-foreground border-border border-b text-left">
                  <th className="p-2 font-medium">对账单号</th>
                  <th className="p-2 font-medium">供应商</th>
                  <th className="p-2 font-medium">期间</th>
                  <th className="p-2 font-medium">币种</th>
                  <th className="p-2 font-medium">已到货应收</th>
                  <th className="p-2 font-medium">代扣代缴</th>
                  <th className="p-2 font-medium">索赔冲减</th>
                  <th className="p-2 font-medium">建议应付</th>
                  <th className="p-2 font-medium">差异</th>
                  <th className="p-2 font-medium">状态</th>
                </tr>
              </thead>
              <tbody>
                {rows.map((row) => (
                  <tr
                    key={row.id}
                    className="border-border hover:bg-muted/50 cursor-pointer border-b last:border-0"
                    onClick={() => setDetailId(row.id)}
                  >
                    <td className="p-2 font-medium">{row.reconNo}</td>
                    <td className="p-2">{supplierText(row)}</td>
                    <td className="p-2">
                      {fmtDate(row.periodStart)} ~ {fmtDate(row.periodEnd)}
                      <div className="text-muted-foreground text-copy-12">
                        {row.periodTypeLabel || row.periodType}
                      </div>
                    </td>
                    <td className="p-2">{row.statementCurrency}</td>
                    <td className="p-2">{fmtMoney(row.receivedAmount)}</td>
                    <td className="p-2">{fmtMoney(row.deductionAmount)}</td>
                    <td className="p-2">{fmtMoney(row.claimAmount)}</td>
                    <td className="p-2">
                      {fmtMoney(row.suggestedPayableAmount)}
                      <div className="text-muted-foreground text-copy-12">采购侧建议数</div>
                    </td>
                    <td className="p-2">
                      {row.openDiscrepancyCount > 0 ? (
                        <Badge tone="danger">{row.openDiscrepancyCount} 条待处理</Badge>
                      ) : (
                        <span className="text-muted-foreground text-copy-12">
                          共 {row.discrepancyCount} 条
                        </span>
                      )}
                    </td>
                    <td className="p-2">
                      <Badge tone={statusTone(row.status)}>
                        {row.statusLabel || row.status}
                      </Badge>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          <div className="text-muted-foreground mt-3 text-copy-12">
            共 {data?.total ?? 0} 单 · 本页 {rows.length} 单 ·{' '}
            {data?.paginationNote ?? '真分页（LIMIT/OFFSET + 独立 COUNT）'}
          </div>
          <div className="mt-3 flex gap-2">
            <Button
              variant="ghost"
              disabled={(params.page ?? 0) <= 0}
              onClick={() => setParams((p) => ({ ...p, page: Math.max(0, (p.page ?? 0) - 1) }))}
            >
              上一页
            </Button>
            <Button
              variant="ghost"
              disabled={(params.page ?? 0) + 1 >= Math.ceil((data?.total ?? 0) / (params.size ?? 20))}
              onClick={() => setParams((p) => ({ ...p, page: (p.page ?? 0) + 1 }))}
            >
              下一页
            </Button>
          </div>
        </SectionCard>
      )}

      {creating && options && (
        <CreateDialog
          options={options}
          busy={busy}
          onClose={() => setCreating(false)}
          onSubmit={async (input) => {
            setBusy(true);
            try {
              await createReconciliation(input);
              setCreating(false);
              await load();
            } catch (e) {
              setError(e instanceof Error ? e.message : '发起对账失败');
            } finally {
              setBusy(false);
            }
          }}
        />
      )}

      <StatementDetail
        id={detailId}
        onClose={() => setDetailId(null)}
        onChanged={reload}
        onError={reportError}
      />
    </div>
  );
}

/** 顶部常驻的「未接线」提示。 */
function UnavailableBanner({ caps }: { caps: ReconciliationCapabilities }) {
  const sections = caps.sections.filter((s) => s.available === false);
  if (sections.length === 0) return null;
  return (
    <SectionCard
      title="本次对账的已知缺口"
      description="这些能力当前**不可用**（不是「没有数据」）：页面上相关数字按「未接线 / 未确认」标注。"
    >
      <div className="space-y-2">
        {sections.map((s) => (
          <div key={s.section} className="flex items-start gap-2">
            <AlertTriangle className="text-warning mt-0.5 h-4 w-4 shrink-0" />
            <div className="text-copy-13">
              <div className="font-medium">
                {s.label}
                {s.ownerModule ? `（归属 ${s.ownerModule}）` : ''}
              </div>
              <div className="text-muted-foreground">{s.reason}</div>
            </div>
          </div>
        ))}
      </div>
    </SectionCard>
  );
}

/**
 * 对账单详情（抽屉式 Modal）：明细 + 差异 + 回执 + 留痕 + 基数说明 + 可执行动作。
 *
 * <p>单独抽成组件是因为它要管自己的加载与局部状态，放进列表组件会让列表每次刷新都重挂。
 */
function StatementDetail({
  id,
  onClose,
  onChanged,
  onError,
}: {
  id: string | null;
  onClose: () => void;
  onChanged: () => void;
  onError: (message: string) => void;
}) {
  const [detail, setDetail] = useState<ReconciliationDetail | null>(null);
  const [loading, setLoading] = useState(false);
  const [busy, setBusy] = useState(false);
  const [tab, setTab] = useState<'lines' | 'discrepancies' | 'confirmations' | 'events' | 'basis'>(
    'lines',
  );
  const [deducting, setDeducting] = useState(false);
  const [confirming, setConfirming] = useState(false);
  const [resolving, setResolving] = useState<ReconciliationDiscrepancyRow | null>(null);
  const [reopening, setReopening] = useState(false);
  const [lines, setLines] = useState<ReconciliationLineRow[]>([]);
  const [discrepancies, setDiscrepancies] = useState<ReconciliationDiscrepancyRow[]>([]);

  const load = useCallback(async (id: string) => {
    setLoading(true);
    try {
      const [d, l, diff] = await Promise.all([
        fetchReconciliation(id),
        fetchReconciliationLines(id, { size: 200 }),
        fetchReconciliationDiscrepancies(id, { size: 200 }),
      ]);
      setDetail(d);
      setLines(l.rows);
      setDiscrepancies(diff.rows);
    } catch (e) {
      onError(e instanceof Error ? e.message : '加载对账单详情失败');
    } finally {
      setLoading(false);
    }
  }, [onError]);

  // 打开哪张单由父组件决定（列表点行 → 父组件记 id）。
  useEffect(() => {
    if (!id) {
      setDetail(null);
      return;
    }
    setTab('lines');
    void load(id);
  }, [id, load]);

  const run = useCallback(
    async (id: string, action: () => Promise<unknown>) => {
      setBusy(true);
      try {
        await action();
        await load(id);
        onChanged();
      } catch (e) {
        onError(e instanceof Error ? e.message : '操作失败，请重试');
      } finally {
        setBusy(false);
      }
    },
    [load, onChanged, onError],
  );

  if (!id || !detail) return null;
  const head = detail.head;

  return (
    <>
      <Modal open onOpenChange={(open) => !open && onClose()}>
        <ModalContent className="max-w-4xl">
          <ModalHeader>
            <ModalTitle>对账单 {head.reconNo}</ModalTitle>
            <ModalDescription>
              {supplierText(head)} · {fmtDate(head.periodStart)} ~ {fmtDate(head.periodEnd)} ·{' '}
              {head.periodTypeLabel || head.periodType} · 币种 {head.statementCurrency} ·{' '}
              <Badge tone={statusTone(head.status)}>{head.statusLabel || head.status}</Badge>
            </ModalDescription>
          </ModalHeader>

          <div className="grid gap-3 sm:grid-cols-4">
            <StatCard label="已到货应收" value={fmtMoney(head.receivedAmount)} />
            <StatCard label="代扣代缴费用" value={fmtMoney(head.deductionAmount)} />
            <StatCard label="索赔冲减" value={fmtMoney(head.claimAmount)} />
            <StatCard
              label="采购侧建议应付（未确认）"
              value={fmtMoney(head.suggestedPayableAmount)}
              polarity="neutral"
            />
          </div>

          <Segmented
            value={tab}
            onChange={setTab}
            aria-label="详情分区"
            options={[
              { value: 'lines', label: `明细（${lines.length}）` },
              { value: 'discrepancies', label: `差异清单（${discrepancies.length}）` },
              { value: 'confirmations', label: `供应商回执（${detail.confirmations.length}）` },
              { value: 'events', label: `留痕（${detail.events.length}）` },
              { value: 'basis', label: '对账基数与口径' },
            ]}
          />

          {loading && <div className="text-muted-foreground text-copy-13">加载中…</div>}

          {!loading && tab === 'lines' && (
            <LinesTable lines={lines} currency={head.statementCurrency} />
          )}

          {!loading && tab === 'discrepancies' && (
            <DiscrepancyTable
              discrepancies={discrepancies}
              onResolve={(d) => setResolving(d)}
            />
          )}

          {!loading && tab === 'confirmations' && (
            <div className="space-y-2">
              {detail.confirmations.length === 0 ? (
                <div className="text-muted-foreground text-copy-13">
                  还没有供应商回执。发出对账单后，供应商答复会逐行比对并落成差异。
                </div>
              ) : (
                detail.confirmations.map((c) => (
                  <div key={c.id} className="border-border rounded-md border p-2 text-copy-13">
                    <div className="font-medium">{confirmationText(c)}</div>
                    <div className="text-muted-foreground">
                      答复时刻 {fmtTime(c.supplierRepliedAt)} · 答复人 {c.supplierRepliedBy || '无'}
                    </div>
                    {c.note && <div className="text-muted-foreground">{c.note}</div>}
                  </div>
                ))
              )}
            </div>
          )}

          {!loading && tab === 'events' && (
            <div className="space-y-1">
              {detail.events.length === 0 ? (
                <div className="text-muted-foreground text-copy-13">暂无留痕。</div>
              ) : (
                detail.events.map((e) => (
                  <div key={e.id} className="border-border border-b py-1.5 text-copy-13 last:border-0">
                    <div>{eventText(e)}</div>
                    <div className="text-muted-foreground text-copy-12">{fmtTime(e.occurredAt)}</div>
                  </div>
                ))
              )}
            </div>
          )}

          {!loading && tab === 'basis' && <BasisBlock basis={detail.basis} />}

          <ModalFooter>
            <Button
              variant="ghost"
              disabled={busy || !actionAllowed(detail.actions, 'ADD_DEDUCTION')}
              title={actionBlockedReason(detail.actions, 'ADD_DEDUCTION')}
              onClick={() => setDeducting(true)}
            >
              登记代扣代缴
            </Button>
            <Button
              variant="ghost"
              disabled={busy || !actionAllowed(detail.actions, 'SEND')}
              title={actionBlockedReason(detail.actions, 'SEND')}
              onClick={() => void run(head.id, () => sendReconciliation(head.id))}
            >
              发给供应商
            </Button>
            <Button
              variant="ghost"
              disabled={busy || !actionAllowed(detail.actions, 'RECORD_CONFIRMATION')}
              title={actionBlockedReason(detail.actions, 'RECORD_CONFIRMATION')}
              onClick={() => setConfirming(true)}
            >
              录入供应商回执
            </Button>
            <Button
              variant="ghost"
              disabled={busy || !actionAllowed(detail.actions, 'REOPEN')}
              title={actionBlockedReason(detail.actions, 'REOPEN')}
              onClick={() => setReopening(true)}
            >
              重开
            </Button>
            <Button
              variant="ghost"
              disabled={busy || !actionAllowed(detail.actions, 'CLOSE')}
              title={actionBlockedReason(detail.actions, 'CLOSE')}
              onClick={() => void run(head.id, () => closeReconciliation(head.id))}
            >
              关闭
            </Button>
            <a
              href={reconciliationExportUrl(head.id)}
              className="text-copy-13 inline-flex items-center gap-1 underline"
            >
              <Download className="h-4 w-4" />
              导出 CSV
            </a>
          </ModalFooter>
        </ModalContent>
      </Modal>

      {deducting && (
        <DeductionDialog
          currency={head.statementCurrency}
          busy={busy}
          onClose={() => setDeducting(false)}
          onSubmit={async (input: PurchaseReconciliationDeductionCreate) => {
            setDeducting(false);
            await run(head.id, () => addReconciliationDeduction(head.id, input));
          }}
        />
      )}

      {confirming && (
        <ConfirmationDialog
          lines={lines}
          currency={head.statementCurrency}
          busy={busy}
          onClose={() => setConfirming(false)}
          onSubmit={async (input: PurchaseReconciliationConfirmationCreate) => {
            setConfirming(false);
            await run(head.id, () => recordReconciliationConfirmation(head.id, input));
          }}
        />
      )}

      {resolving && (
        <ResolveDialog
          discrepancy={resolving}
          busy={busy}
          onClose={() => setResolving(null)}
          onSubmit={async (input: PurchaseReconciliationDiscrepancyResolve) => {
            setResolving(null);
            await run(head.id, () =>
              resolveReconciliationDiscrepancy(head.id, resolving.id, input),
            );
          }}
        />
      )}

      {reopening && (
        <ReopenDialog
          busy={busy}
          onClose={() => setReopening(false)}
          onSubmit={async (input: PurchaseReconciliationReopen) => {
            setReopening(false);
            await run(head.id, () => reopenReconciliation(head.id, input));
          }}
        />
      )}
    </>
  );
}

/** 明细表：数量 / 单位 / 单价 / 币种 / 汇率 / 金额 / 质检扣量状态。 */
function LinesTable({
  lines,
  currency,
}: {
  lines: ReconciliationLineRow[];
  currency: string;
}) {
  if (lines.length === 0) {
    return (
      <div className="text-muted-foreground text-copy-13">
        这张对账单还没有明细行（期间内没有到货与索赔，或还没登记代扣代缴费用）。
      </div>
    );
  }
  return (
    <div className="overflow-x-auto">
      <table className="w-full text-sm">
        <thead>
          <tr className="text-muted-foreground border-border border-b text-left">
            <th className="p-2 font-medium">行</th>
            <th className="p-2 font-medium">类型</th>
            <th className="p-2 font-medium">来源</th>
            <th className="p-2 font-medium">数量</th>
            <th className="p-2 font-medium">单价</th>
            <th className="p-2 font-medium">金额</th>
            <th className="p-2 font-medium">质检扣量</th>
          </tr>
        </thead>
        <tbody>
          {lines.map((line) => {
            const quality = qualityDeductionText(line.qualityDeductionStatus);
            return (
              <tr key={line.id} className="border-border border-b last:border-0">
                <td className="p-2">{line.lineNo}</td>
                <td className="p-2">
                  {line.lineTypeLabel || line.lineType}
                  {line.deductionTypeLabel ? ` · ${line.deductionTypeLabel}` : ''}
                </td>
                <td className="p-2">
                  <div>{line.sourceRefCode || '—'}</div>
                  <div className="text-muted-foreground text-copy-12">
                    {line.sourceTypeLabel || line.sourceType}
                  </div>
                </td>
                <td className="p-2">
                  {fmtQty(line.quantity)}
                  <div className="text-muted-foreground text-copy-12">
                    {line.unitCode ? `单位 ${line.unitCode}` : '无数量'}
                    {line.unitConversion !== null && line.unitConversion !== undefined
                      ? ` · 换算 ${fmtQty(line.unitConversion)}`
                      : ''}
                  </div>
                </td>
                <td className="p-2">
                  {fmtMoney(line.unitPrice)}
                  {line.fxRateSource && line.currency !== currency ? (
                    <div className="text-muted-foreground text-copy-12">
                      汇率 {fmtQty(line.fxRate)} · {line.fxRateSource}
                    </div>
                  ) : null}
                </td>
                <td className="p-2">{lineAmountText(line)}</td>
                <td className="p-2">
                  <Badge tone={quality.tone}>{quality.label}</Badge>
                  {line.qualityDeductionQuantity !== null &&
                  line.qualityDeductionQuantity !== undefined ? (
                    <div className="text-muted-foreground text-copy-12">
                      扣量 {fmtQty(line.qualityDeductionQuantity)}
                    </div>
                  ) : null}
                </td>
              </tr>
            );
          })}
        </tbody>
      </table>
    </div>
  );
}

/** 差异清单：逐条列出，可挂起 / 解决。 */
function DiscrepancyTable({
  discrepancies,
  onResolve,
}: {
  discrepancies: ReconciliationDiscrepancyRow[];
  onResolve: (row: ReconciliationDiscrepancyRow) => void;
}) {
  if (discrepancies.length === 0) {
    return (
      <div className="text-muted-foreground text-copy-13">
        没有差异。差异是**逐条**列出的：与供应商回执不一致的每一行都会单列一条。
      </div>
    );
  }
  return (
    <div className="space-y-2">
      {discrepancies.map((d) => (
        <div key={d.id} className="border-border rounded-md border p-2 text-copy-13">
          <div className="flex items-center justify-between gap-2">
            <div className="font-medium">
              {d.lineNo !== null && d.lineNo !== undefined ? `第 ${d.lineNo} 行 · ` : ''}
              {discrepancyText(d)}
            </div>
            <div className="flex items-center gap-2">
              <Badge tone={discrepancyStatusTone(d.status)}>{d.statusLabel || d.status}</Badge>
              {d.status !== 'RESOLVED' && (
                <Button variant="ghost" onClick={() => onResolve(d)}>
                  处理
                </Button>
              )}
            </div>
          </div>
          {d.note && <div className="text-muted-foreground">{d.note}</div>}
          {d.resolution && (
            <div className="text-muted-foreground">
              处理方式：{d.resolution}
              {d.resolutionNote ? ` · ${d.resolutionNote}` : ''}
            </div>
          )}
        </div>
      ))}
    </div>
  );
}

/** 对账基数与口径说明。 */
function BasisBlock({ basis }: { basis: ReconciliationBasis }) {
  return (
    <div className="space-y-3 text-copy-13">
      <div>
        <div className="font-medium">基数来源</div>
        {basis.sources.map((s) => (
          <div key={s.key} className="text-muted-foreground">
            {s.label}：{s.detail}
          </div>
        ))}
      </div>
      {basis.unavailableSources.length > 0 && (
        <div>
          <div className="font-medium">未接线的事实（影响如下）</div>
          {basis.unavailableSources.map((s) => (
            <div key={s.key} className="text-muted-foreground">
              {s.label}（{s.ownerModule}）：{s.reason} → {s.impact}
            </div>
          ))}
        </div>
      )}
      <div>
        <div className="font-medium">口径</div>
        <div className="text-muted-foreground">{basis.receiptBasisNote}</div>
        <div className="text-muted-foreground">{basis.qualityBasisNote}</div>
        <div className="text-muted-foreground">{basis.financeHandoffNote}</div>
        <div className="text-muted-foreground">{basis.periodNote}</div>
        <div className="text-muted-foreground">{basis.fxNote}</div>
        <div className="text-muted-foreground">{basis.unitNote}</div>
      </div>
    </div>
  );
}

/** 发起对账。 */
function CreateDialog({
  options,
  busy,
  onClose,
  onSubmit,
}: {
  options: ReconciliationOptions;
  busy: boolean;
  onClose: () => void;
  onSubmit: (input: PurchaseReconciliationCreate) => Promise<void>;
}) {
  const [supplierRefId, setSupplierRefId] = useState(options.suppliers[0]?.supplierRefId ?? '');
  const [periodType, setPeriodType] = useState('MONTHLY');
  const [periodStart, setPeriodStart] = useState(options.periods[0]?.periodStart ?? '');
  const [statementCurrency, setStatementCurrency] = useState('USD');
  const [fxCurrency, setFxCurrency] = useState('');
  const [fxRate, setFxRate] = useState('');
  const [fxSource, setFxSource] = useState('');
  const [note, setNote] = useState('');

  const fxRates = useMemo(
    () =>
      fxCurrency && fxRate && fxSource
        ? [{ currency: fxCurrency, rate: Number(fxRate), source: fxSource }]
        : [],
    [fxCurrency, fxRate, fxSource],
  );

  return (
    <Modal open onOpenChange={(open) => !open && onClose()}>
      <ModalContent>
        <ModalHeader>
          <ModalTitle>发起对账</ModalTitle>
          <ModalDescription>
            按期间与供应商采集基数：应收取 6.3.2.2 到货流水，索赔取 11.2.3.3 索赔明细。
            期间由服务端裁决（月份 1 号起），跨币种必须给汇率快照。
          </ModalDescription>
        </ModalHeader>
        <div className="grid gap-3 md:grid-cols-2">
          <FormField id="create-supplier" label="供应商">
            <select
              id="create-supplier"
              className="border-input h-9 w-full rounded-md border bg-transparent px-3 text-sm"
              value={supplierRefId}
              onChange={(e) => setSupplierRefId(e.target.value)}
            >
              {options.suppliers.map((s) => (
                <option key={s.supplierRefId} value={s.supplierRefId}>
                  {s.supplierCode} {s.supplierName}
                </option>
              ))}
            </select>
          </FormField>
          <FormField id="create-period-type" label="期间类型">
            <select
              id="create-period-type"
              className="border-input h-9 w-full rounded-md border bg-transparent px-3 text-sm"
              value={periodType}
              onChange={(e) => setPeriodType(e.target.value)}
            >
              <option value="MONTHLY">月度</option>
              <option value="QUARTERLY">季度</option>
            </select>
          </FormField>
          <FormField id="create-period-start" label="期间起始日">
            <Input
              id="create-period-start"
              type="date"
              value={periodStart}
              onChange={(e) => setPeriodStart(e.target.value)}
            />
          </FormField>
          <FormField id="create-currency" label="对账单币种">
            <Input
              id="create-currency"
              value={statementCurrency}
              onChange={(e) => setStatementCurrency(e.target.value.toUpperCase())}
            />
          </FormField>
          <FormField id="create-fx-currency" label="汇率快照（原币，可留空）">
            <Input
              id="create-fx-currency"
              placeholder="如 USD"
              value={fxCurrency}
              onChange={(e) => setFxCurrency(e.target.value.toUpperCase())}
            />
          </FormField>
          <FormField id="create-fx-rate" label="汇率">
            <Input
              id="create-fx-rate"
              placeholder="如 7.2500"
              value={fxRate}
              onChange={(e) => setFxRate(e.target.value)}
            />
          </FormField>
          <FormField id="create-fx-source" label="汇率来源">
            <Input
              id="create-fx-source"
              placeholder="如 财务提供的 11 月结汇汇率"
              value={fxSource}
              onChange={(e) => setFxSource(e.target.value)}
            />
          </FormField>
          <FormField id="create-note" label="备注">
            <Input id="create-note" value={note} onChange={(e) => setNote(e.target.value)} />
          </FormField>
        </div>
        <ModalFooter>
          <Button variant="ghost" onClick={onClose}>
            取消
          </Button>
          <Button
            disabled={busy || !supplierRefId || !periodStart || !statementCurrency}
            onClick={() =>
              void onSubmit({
                supplierRefId,
                periodType,
                periodStart,
                periodEnd: null,
                statementCurrency,
                fxRates,
                note: note || null,
              })
            }
          >
            发起
          </Button>
        </ModalFooter>
      </ModalContent>
    </Modal>
  );
}

/** 登记代扣代缴费用。 */
function DeductionDialog({
  currency,
  busy,
  onClose,
  onSubmit,
}: {
  currency: string;
  busy: boolean;
  onClose: () => void;
  onSubmit: (input: PurchaseReconciliationDeductionCreate) => Promise<void>;
}) {
  const [deductionType, setDeductionType] = useState('TAX');
  const [amount, setAmount] = useState('');
  const [lineCurrency, setLineCurrency] = useState(currency);
  const [fxRate, setFxRate] = useState('');
  const [fxSource, setFxSource] = useState('');
  const [note, setNote] = useState('');

  return (
    <Modal open onOpenChange={(open) => !open && onClose()}>
      <ModalContent>
        <ModalHeader>
          <ModalTitle>登记代扣代缴费用</ModalTitle>
          <ModalDescription>
            代扣代缴的金额与币种要能追到出处；币种与对账单币种不同必须给汇率快照（不按 1:1 折算）。
          </ModalDescription>
        </ModalHeader>
        <div className="grid gap-3 md:grid-cols-2">
          <FormField id="ded-type" label="费用类型">
            <select
              id="ded-type"
              className="border-input h-9 w-full rounded-md border bg-transparent px-3 text-sm"
              value={deductionType}
              onChange={(e) => setDeductionType(e.target.value)}
            >
              <option value="TAX">代扣代缴税费</option>
              <option value="WITHHOLDING">代扣</option>
              <option value="FREIGHT">代付运费</option>
              <option value="REBATE">折让</option>
              <option value="OTHER">其它</option>
            </select>
          </FormField>
          <FormField id="ded-amount" label="金额">
            <Input
              id="ded-amount"
              value={amount}
              placeholder="如 12.5000"
              onChange={(e) => setAmount(e.target.value)}
            />
          </FormField>
          <FormField id="ded-currency" label="币种">
            <Input
              id="ded-currency"
              value={lineCurrency}
              onChange={(e) => setLineCurrency(e.target.value.toUpperCase())}
            />
          </FormField>
          <FormField id="ded-fx" label="汇率（跨币种必填）">
            <Input id="ded-fx" value={fxRate} onChange={(e) => setFxRate(e.target.value)} />
          </FormField>
          <FormField id="ded-fx-source" label="汇率来源">
            <Input
              id="ded-fx-source"
              value={fxSource}
              onChange={(e) => setFxSource(e.target.value)}
            />
          </FormField>
          <FormField id="ded-note" label="备注">
            <Input id="ded-note" value={note} onChange={(e) => setNote(e.target.value)} />
          </FormField>
        </div>
        <ModalFooter>
          <Button variant="ghost" onClick={onClose}>
            取消
          </Button>
          <Button
            disabled={busy || !amount}
            onClick={() =>
              void onSubmit({
                deductionType,
                amount: Number(amount),
                currency: lineCurrency,
                fxRate: fxRate ? Number(fxRate) : null,
                fxRateSource: fxSource || null,
                note: note || null,
              })
            }
          >
            登记
          </Button>
        </ModalFooter>
      </ModalContent>
    </Modal>
  );
}

/** 录入供应商回执（逐行供应商值 → 差异）。 */
function ConfirmationDialog({
  lines,
  currency,
  busy,
  onClose,
  onSubmit,
}: {
  lines: ReconciliationLineRow[];
  currency: string;
  busy: boolean;
  onClose: () => void;
  onSubmit: (input: PurchaseReconciliationConfirmationCreate) => Promise<void>;
}) {
  const [supplierRepliedAt, setSupplierRepliedAt] = useState('');
  const [supplierRepliedBy, setSupplierRepliedBy] = useState('');
  const [supplierReference, setSupplierReference] = useState('');
  const [supplierResult, setSupplierResult] = useState('DIFFERENCES');
  const [quantities, setQuantities] = useState<Record<string, string>>({});
  const [amounts, setAmounts] = useState<Record<string, string>>({});

  return (
    <Modal open onOpenChange={(open) => !open && onClose()}>
      <ModalContent className="max-w-3xl">
        <ModalHeader>
          <ModalTitle>录入供应商确认回执</ModalTitle>
          <ModalDescription>
            回执要**逐行**给供应商的值：只给一个总额就答不出「差在哪一行」。
            留空的字段不参与比对。
          </ModalDescription>
        </ModalHeader>
        <div className="grid gap-3 md:grid-cols-2">
          <FormField id="cfm-at" label="供应商答复时刻">
            <Input
              id="cfm-at"
              placeholder="2026-11-20T10:00:00Z"
              value={supplierRepliedAt}
              onChange={(e) => setSupplierRepliedAt(e.target.value)}
            />
          </FormField>
          <FormField id="cfm-by" label="答复人">
            <Input
              id="cfm-by"
              value={supplierRepliedBy}
              onChange={(e) => setSupplierRepliedBy(e.target.value)}
            />
          </FormField>
          <FormField id="cfm-ref" label="回执号">
            <Input
              id="cfm-ref"
              value={supplierReference}
              onChange={(e) => setSupplierReference(e.target.value)}
            />
          </FormField>
          <FormField id="cfm-result" label="答复结果">
            <select
              id="cfm-result"
              className="border-input h-9 w-full rounded-md border bg-transparent px-3 text-sm"
              value={supplierResult}
              onChange={(e) => setSupplierResult(e.target.value)}
            >
              <option value="CONFIRMED">供应商确认无误</option>
              <option value="DIFFERENCES">供应商有差异</option>
            </select>
          </FormField>
        </div>

        <div className="overflow-x-auto">
          <table className="w-full text-sm">
            <thead>
              <tr className="text-muted-foreground border-border border-b text-left">
                <th className="p-2 font-medium">行</th>
                <th className="p-2 font-medium">我方数量</th>
                <th className="p-2 font-medium">供应商数量</th>
                <th className="p-2 font-medium">我方金额</th>
                <th className="p-2 font-medium">供应商金额</th>
              </tr>
            </thead>
            <tbody>
              {lines.map((line) => (
                <tr key={line.id} className="border-border border-b last:border-0">
                  <td className="p-2">
                    {line.lineNo} · {line.lineTypeLabel || line.lineType}
                    <div className="text-muted-foreground text-copy-12">
                      {line.sourceRefCode || '—'} {line.currency}
                    </div>
                  </td>
                  <td className="p-2">{fmtQty(line.quantity)}</td>
                  <td className="p-2">
                    <Input
                      value={quantities[line.id] ?? ''}
                      onChange={(e) =>
                        setQuantities((prev) => ({ ...prev, [line.id]: e.target.value }))
                      }
                    />
                  </td>
                  <td className="p-2">{fmtMoney(line.amount)}</td>
                  <td className="p-2">
                    <Input
                      value={amounts[line.id] ?? ''}
                      onChange={(e) =>
                        setAmounts((prev) => ({ ...prev, [line.id]: e.target.value }))
                      }
                    />
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>

        <ModalFooter>
          <Button variant="ghost" onClick={onClose}>
            取消
          </Button>
          <Button
            disabled={busy || !supplierRepliedAt}
            onClick={() =>
              void onSubmit({
                supplierRepliedAt,
                supplierRepliedBy: supplierRepliedBy || null,
                supplierReference: supplierReference || null,
                supplierResult,
                supplierConfirmedAmount: null,
                supplierConfirmedCurrency: null,
                supplierLines: lines
                  .filter((l) => quantities[l.id] || amounts[l.id])
                  .map((l) => ({
                    lineId: l.id,
                    supplierLineRef: null,
                    quantity: quantities[l.id] ? Number(quantities[l.id]) : null,
                    unitPrice: null,
                    amount: amounts[l.id] ? Number(amounts[l.id]) : null,
                  })),
                note: null,
              })
            }
          >
            录入回执
          </Button>
        </ModalFooter>
      </ModalContent>
    </Modal>
  );
}

/** 处理差异：挂起或解决。 */
function ResolveDialog({
  discrepancy,
  busy,
  onClose,
  onSubmit,
}: {
  discrepancy: ReconciliationDiscrepancyRow;
  busy: boolean;
  onClose: () => void;
  onSubmit: (input: PurchaseReconciliationDiscrepancyResolve) => Promise<void>;
}) {
  const [status, setStatus] = useState('HELD');
  const [resolution, setResolution] = useState('');
  const [resolutionNote, setResolutionNote] = useState('');

  return (
    <Modal open onOpenChange={(open) => !open && onClose()}>
      <ModalContent>
        <ModalHeader>
          <ModalTitle>处理差异</ModalTitle>
          <ModalDescription>{discrepancyText(discrepancy)}</ModalDescription>
        </ModalHeader>
        <div className="grid gap-3">
          <FormField id="res-status" label="处理结果">
            <select
              id="res-status"
              className="border-input h-9 w-full rounded-md border bg-transparent px-3 text-sm"
              value={status}
              onChange={(e) => setStatus(e.target.value)}
            >
              <option value="HELD">挂起（稍后处理）</option>
              <option value="RESOLVED">解决</option>
            </select>
          </FormField>
          {status === 'RESOLVED' && (
            <>
              <FormField id="res-way" label="处理方式">
                <Input
                  id="res-way"
                  placeholder="如 按供应商实收数量调整"
                  value={resolution}
                  onChange={(e) => setResolution(e.target.value)}
                />
              </FormField>
              <FormField id="res-note" label="处理说明">
                <Input
                  id="res-note"
                  value={resolutionNote}
                  onChange={(e) => setResolutionNote(e.target.value)}
                />
              </FormField>
            </>
          )}
        </div>
        <ModalFooter>
          <Button variant="ghost" onClick={onClose}>
            取消
          </Button>
          <Button
            disabled={busy || (status === 'RESOLVED' && (!resolution || !resolutionNote))}
            onClick={() => void onSubmit({ status, resolution, resolutionNote })}
          >
            提交
          </Button>
        </ModalFooter>
      </ModalContent>
    </Modal>
  );
}

/** 重开对账。 */
function ReopenDialog({
  busy,
  onClose,
  onSubmit,
}: {
  busy: boolean;
  onClose: () => void;
  onSubmit: (input: PurchaseReconciliationReopen) => Promise<void>;
}) {
  const [reason, setReason] = useState('');
  return (
    <Modal open onOpenChange={(open) => !open && onClose()}>
      <ModalContent>
        <ModalHeader>
          <ModalTitle>重开对账</ModalTitle>
          <ModalDescription>
            重开意味着之前发给供应商的那一版不作数了 —— 必须写理由。
          </ModalDescription>
        </ModalHeader>
        <FormField id="reopen-reason" label="重开理由">
          <Input
            id="reopen-reason"
            value={reason}
            onChange={(e) => setReason(e.target.value)}
          />
        </FormField>
        <ModalFooter>
          <Button variant="ghost" onClick={onClose}>
            取消
          </Button>
          <Button disabled={busy || !reason} onClick={() => void onSubmit({ reason })}>
            重开
          </Button>
        </ModalFooter>
      </ModalContent>
    </Modal>
  );
}
