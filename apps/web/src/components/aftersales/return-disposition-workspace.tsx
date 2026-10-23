'use client';

import { useCallback, useEffect, useState } from 'react';
import { RefreshCw, TriangleAlert } from 'lucide-react';
import { Badge, Button, EmptyState, Input, Notice, PageHeader, SectionCard, StatCard } from '@starcon/ui';
import type {
  ReturnInventoryDispositionCapabilities,
  ReturnInventoryDispositionDetail,
  ReturnInventoryDispositionInstructionPage,
  ReturnInventoryDispositionOptions,
  ReturnInventoryDispositionPage,
} from '../../../../../packages/api-contracts/src/index';
import {
  approveReturnInventoryDisposition,
  canApprove,
  canCancel,
  canExecute,
  canSubmit,
  cancelReturnInventoryDisposition,
  createReturnInventoryDisposition,
  DISPOSITION_STATUS_OPTIONS,
  executeReturnInventoryDisposition,
  fetchDispositionOptions,
  fetchPendingInstructions,
  fetchReturnDisposition,
  fetchReturnDispositionCapabilities,
  fetchReturnDispositions,
  financePending,
  rejectReturnInventoryDisposition,
  requiresTargetLocation,
  SCRAP_IRREVERSIBLE_NOTE,
  SCRAP_REASON_OPTIONS,
  scrapReady,
  scrapReasonLabel,
  statusLabel,
  submitReturnInventoryDisposition,
  TARGET_STATE_OPTIONS,
  targetStateLabel,
  updateReturnInventoryDisposition,
} from './return-disposition-model';

/**
 * 11.2.3.2 转可售/残次/报废。
 *
 * <p>蓝图：「按质检结论把退货转入对应状态：可再销售转可售库存、残次转残次库存（可用于清仓）、
 * 报废则核销并计入损耗。」输出：「库存状态变更分录；可售量更新；报废损耗记录。」
 * 加粗句：「<b>报废是不可逆的</b>，需审批与原因，不能由客服随手处理。」
 *
 * <p><b>页面围绕那条加粗句组织</b>：
 *
 * <ol>
 *   <li><b>报废不是一个按钮，是一条四步链</b>：起草 → 提交待审 → 另一个人审批 → 执行。
 *       页面按后端给的状态渲染当前能做的动作（{@link canSubmit} / {@link canApprove} /
 *       {@link canExecute} / {@link canCancel}），没有「一键报废」。</li>
 *   <li><b>来源库位与目标库位都从 warehouse 的真数据里选</b>：页面不让人手打库位，
 *       也不猜「货在哪」—— 候选清单来自后端（{@code GET /trade/return-dispositions/options}）。</li>
 *   <li><b>数量是只读的</b>：处置数量由质检结论决定，页面把它显示出来而不是让人填。</li>
 * </ol>
 */
export function ReturnInventoryDispositionWorkspace() {
  const [page, setPage] = useState(0);
  const [status, setStatus] = useState('');
  const [targetState, setTargetState] = useState('');
  const [keyword, setKeyword] = useState('');
  const [data, setData] = useState<ReturnInventoryDispositionPage | null>(null);
  const [instructions, setInstructions] = useState<ReturnInventoryDispositionInstructionPage | null>(
    null,
  );
  const [caps, setCaps] = useState<ReturnInventoryDispositionCapabilities | null>(null);
  const [detail, setDetail] = useState<ReturnInventoryDispositionDetail | null>(null);
  const [options, setOptions] = useState<ReturnInventoryDispositionOptions | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);

  // ---- 起草态
  const [draftInstructionId, setDraftInstructionId] = useState<string | null>(null);
  const [draftSourceBucketId, setDraftSourceBucketId] = useState('');
  const [draftTargetLocationId, setDraftTargetLocationId] = useState('');
  const [draftReason, setDraftReason] = useState('');
  const [draftScrapReason, setDraftScrapReason] = useState('');
  // ---- 审批态
  const [decisionNote, setDecisionNote] = useState('');
  const [cancelReason, setCancelReason] = useState('');

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const filters: Parameters<typeof fetchReturnDispositions>[0] = { page, size: 20 };
      if (status) filters.status = status;
      if (targetState) filters.targetState = targetState;
      if (keyword) filters.keyword = keyword;
      setData(await fetchReturnDispositions(filters));
      setInstructions(await fetchPendingInstructions({ page: 0, size: 20 }));
      setCaps(await fetchReturnDispositionCapabilities());
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setLoading(false);
    }
  }, [page, status, targetState, keyword]);

  useEffect(() => {
    void load();
  }, [load]);

  const openDetail = async (id: string) => {
    setError(null);
    try {
      setDetail(await fetchReturnDisposition(id));
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    }
  };

  /** 起一张处置单草稿：拉候选库位，把默认项预选好。 */
  const startDraft = async (instructionId: string, skuId: string, state: string) => {
    setError(null);
    setBusy(true);
    try {
      const opts = await fetchDispositionOptions({ skuId, targetState: state });
      setOptions(opts);
      setDraftInstructionId(instructionId);
      setDraftSourceBucketId(opts.pendingBuckets[0]?.bucketId ?? '');
      setDraftTargetLocationId(requiresTargetLocation(state) ? (opts.targetLocations[0]?.locationId ?? '') : '');
      setDraftReason('');
      setDraftScrapReason('');
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setBusy(false);
    }
  };

  const run = async (work: () => Promise<ReturnInventoryDispositionDetail>) => {
    setBusy(true);
    setError(null);
    try {
      const done = await work();
      setDetail(done);
      await load();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setBusy(false);
    }
  };

  const create = () => {
    if (!draftInstructionId) return;
    const instruction = instructions?.instructions.find((i) => i.id === draftInstructionId);
    void run(async () => {
      const done = await createReturnInventoryDisposition({
        instructionId: draftInstructionId,
        sourceBucketId: draftSourceBucketId,
        targetLocationId: requiresTargetLocation(instruction?.targetInventoryState ?? '')
          ? draftTargetLocationId
          : null,
        qty: instruction?.qty ?? null,
        reasonNote: draftReason,
        scrapReasonCode: instruction?.targetInventoryState === 'SCRAP' ? draftScrapReason : null,
      });
      setDraftInstructionId(null);
      return done;
    });
  };

  const submit = () => {
    if (!detail) return;
    const id = detail.disposition.id;
    const version = detail.disposition.version;
    void run(() =>
      submitReturnInventoryDisposition(id, { expectedVersion: version, note: null }),
    );
  };

  const approve = () => {
    if (!detail) return;
    const id = detail.disposition.id;
    const version = detail.disposition.version;
    void run(async () => {
      const done = await approveReturnInventoryDisposition(id, {
        expectedVersion: version,
        decisionNote,
      });
      setDecisionNote('');
      return done;
    });
  };

  const reject = () => {
    if (!detail) return;
    const id = detail.disposition.id;
    const version = detail.disposition.version;
    void run(async () => {
      const done = await rejectReturnInventoryDisposition(id, {
        expectedVersion: version,
        decisionNote,
      });
      setDecisionNote('');
      return done;
    });
  };

  const cancel = () => {
    if (!detail) return;
    const id = detail.disposition.id;
    const version = detail.disposition.version;
    void run(async () => {
      const done = await cancelReturnInventoryDisposition(id, {
        expectedVersion: version,
        cancelReason,
      });
      setCancelReason('');
      return done;
    });
  };

  const execute = () => {
    if (!detail) return;
    const id = detail.disposition.id;
    const version = detail.disposition.version;
    void run(() => executeReturnInventoryDisposition(id, { expectedVersion: version }));
  };

  const summary = data?.summary;
  const d = detail?.disposition;
  const instruction = instructions?.instructions.find((i) => i.id === draftInstructionId);
  const draftState = instruction?.targetInventoryState ?? '';
  const draftBlocked =
    draftState === 'SCRAP' ? !scrapReady(draftScrapReason, draftReason) : draftReason.trim() === '';

  return (
    <div className="space-y-4">
      <PageHeader
        title="转可售 / 残次 / 报废"
        description="按质检结论把退回商品转入对应库存状态；报废不可逆，需审批与原因"
        actions={
          <Button onClick={() => void load()} disabled={loading}>
            <RefreshCw className="mr-1 h-4 w-4" />
            刷新
          </Button>
        }
      />

      {error ? (
        <div className="border-destructive/40 bg-destructive/10 text-destructive flex items-start gap-2 rounded-lg border p-3 text-sm">
          <TriangleAlert className="mt-0.5 h-4 w-4 shrink-0" />
          <div>
            <div className="font-medium">操作失败</div>
            <div className="mt-1 break-words">{error}</div>
          </div>
        </div>
      ) : null}

      {/* ---- 第一屏：现在有多少件货等着处置 ---- */}
      <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
        <StatCard
          label="待处置指令"
          value={instructions?.total ?? 0}
          polarity="neutral"
        />
        <StatCard label="待审批" value={summary?.pendingApprovalCount ?? 0} polarity="neutral" />
        <StatCard
          label="已执行"
          value={summary?.executedCount ?? 0}
          polarity="positive"
        />
        <StatCard
          label="报废件数"
          value={summary?.executedScrapQty ?? 0}
          polarity="negative"
        />
      </div>

      {/* ---- 待处置指令：11.2.3.1 判完了还没处置的 ---- */}
      <SectionCard title="待处置的质量状态指令（11.2.3.1 产出）">
        {loading ? (
          <div className="text-muted-foreground py-6 text-center text-sm">加载中…</div>
        ) : instructions && instructions.instructions.length === 0 ? (
          <EmptyState
            title="没有待处置的指令"
            description="质检单还没判定，或者判定的结论都已经被处置过了。这一格为空说明「没有货等着处置」，不是「处置功能坏了」。"
          />
        ) : instructions ? (
          <div className="space-y-2">
            {instructions.instructions.map((i) => (
              <div
                key={i.id}
                className="hover:bg-muted/50 flex flex-wrap items-center gap-2 rounded border p-2"
              >
                <div className="min-w-0 flex-1">
                  <div className="text-sm font-medium">{i.skuName || i.skuCode}</div>
                  <div className="text-muted-foreground text-xs">
                    {i.skuCode} · 数量 <b>{String(i.qty)}</b> · 批次 {i.batchNo || '无批次'} · 判定为{' '}
                    <b>{targetStateLabel(i.targetInventoryState)}</b>
                  </div>
                  <div className="text-muted-foreground mt-0.5 text-xs">
                    残值 {String(i.salvageAmount)} {i.salvageCurrency} · 原值 {String(i.originalValue)} ·
                    成色系数 {String(i.gradeCoefficient)}
                  </div>
                </div>
                <Button
                  disabled={busy}
                  onClick={() =>
                    void startDraft(
                      i.id,
                      i.skuId ?? '',
                      i.targetInventoryState,
                    )
                  }
                >
                  发起处置
                </Button>
              </div>
            ))}
          </div>
        ) : null}
      </SectionCard>

      {/* ---- 起草处置单 ---- */}
      {draftInstructionId ? (
        <SectionCard title="起草处置决定单">
          <Notice tone="warning" title="处置决定：报废不可逆">
            {SCRAP_IRREVERSIBLE_NOTE}
          </Notice>
          <div className="mt-3 space-y-3">
            <div className="text-muted-foreground text-xs">
              质检结论数量 <b>{String(instruction?.qty ?? '')}</b> 件 ——{' '}
              <b>数量不可改</b>：一批退货必须被全部处置掉（处置一半意味着另一半永远没有结论）。
            </div>
            <label className="block text-sm">
              <span className="mb-1 block">来源库位（待检库存行）</span>
              <select
                className="w-full rounded border px-2 py-1 text-sm"
                value={draftSourceBucketId}
                onChange={(e) => setDraftSourceBucketId(e.target.value)}
              >
                {options?.pendingBuckets.map((b) => (
                  <option key={b.bucketId} value={b.bucketId}>
                    {b.warehouseCode} / {b.locationCode} · 在手 {String(b.onHand)} · {b.qualityStatusCode}
                  </option>
                ))}
              </select>
            </label>
            {requiresTargetLocation(draftState) ? (
              <label className="block text-sm">
                <span className="mb-1 block">目标库位</span>
                <select
                  className="w-full rounded border px-2 py-1 text-sm"
                  value={draftTargetLocationId}
                  onChange={(e) => setDraftTargetLocationId(e.target.value)}
                >
                  {options?.targetLocations.map((l) => (
                    <option key={l.locationId} value={l.locationId}>
                      {l.warehouseCode} / {l.code}（{l.type}）
                    </option>
                  ))}
                </select>
                <span className="text-muted-foreground mt-1 block text-xs">
                  库位类型必须与目标质量状态匹配（可售进 STORAGE 位、残次进 DEFECTIVE 位）——
                  待检货在 QUARANTINE 位上。
                </span>
              </label>
            ) : null}
            <label className="block text-sm">
              <span className="mb-1 block">处置理由（必填）</span>
              <textarea
                className="w-full rounded border px-2 py-1 text-sm"
                rows={2}
                value={draftReason}
                onChange={(e) => setDraftReason(e.target.value)}
              />
            </label>
            {draftState === 'SCRAP' ? (
              <label className="block text-sm">
                <span className="mb-1 block">损耗原因码（必填）</span>
                <select
                  className="w-full rounded border px-2 py-1 text-sm"
                  value={draftScrapReason}
                  onChange={(e) => setDraftScrapReason(e.target.value)}
                >
                  <option value="">请选择</option>
                  {SCRAP_REASON_OPTIONS.map((o) => (
                    <option key={o.code} value={o.code}>
                      {o.label}
                    </option>
                  ))}
                </select>
              </label>
            ) : null}
            <div className="flex gap-2">
              <Button disabled={busy || draftBlocked} onClick={() => void create()}>
                保存草稿
              </Button>
              <Button variant="outline" onClick={() => setDraftInstructionId(null)}>
                取消
              </Button>
            </div>
          </div>
        </SectionCard>
      ) : null}

      {/* ---- 处置单列表 ---- */}
      <SectionCard title="处置单">
        <div className="mb-3 grid gap-2 md:grid-cols-3">
          <Input
            placeholder="关键字（单号 / SKU）"
            value={keyword}
            onChange={(e) => setKeyword(e.target.value)}
          />
          <select
            className="rounded border px-2 py-1 text-sm"
            value={status}
            onChange={(e) => setStatus(e.target.value)}
          >
            <option value="">全部状态</option>
            {DISPOSITION_STATUS_OPTIONS.map((o) => (
              <option key={o.code} value={o.code}>
                {o.label}
              </option>
            ))}
          </select>
          <select
            className="rounded border px-2 py-1 text-sm"
            value={targetState}
            onChange={(e) => setTargetState(e.target.value)}
          >
            <option value="">全部去向</option>
            {TARGET_STATE_OPTIONS.map((o) => (
              <option key={o.code} value={o.code}>
                {o.label}
              </option>
            ))}
          </select>
        </div>

        {loading ? (
          <div className="text-muted-foreground py-6 text-center text-sm">加载中…</div>
        ) : data && data.dispositions.length === 0 ? (
          <EmptyState
            title="还没有处置单"
            description="还没有人按质检结论下过处置决定。处置决定要写理由，报废还要走审批。"
          />
        ) : data ? (
          <div className="space-y-2">
            {data.dispositions.map((row) => (
              <div
                key={row.id}
                className="hover:bg-muted/50 flex flex-wrap items-center gap-2 rounded border p-2"
              >
                <div className="min-w-0 flex-1">
                  <div className="text-sm font-medium">
                    {row.dispositionNo} · {row.skuName || row.skuCode}
                  </div>
                  <div className="text-muted-foreground text-xs">
                    去向 <b>{targetStateLabel(row.targetInventoryState)}</b> · 数量 {String(row.qty)} ·{' '}
                    {row.sourceWarehouseCode} / {row.sourceLocationCode} →{' '}
                    {row.targetLocationCode || '核销（不在任何库位）'}
                  </div>
                  <div className="text-muted-foreground mt-0.5 text-xs">
                    状态 <b>{statusLabel(row.status)}</b>
                    {row.executionFailureReason ? ` · 失败原因：${row.executionFailureReason}` : ''}
                  </div>
                </div>
                <Badge>{row.statusLabel}</Badge>
                <Button variant="outline" onClick={() => void openDetail(row.id)}>
                  详情
                </Button>
              </div>
            ))}
          </div>
        ) : null}
      </SectionCard>

      {/* ---- 处置单详情 ---- */}
      {detail && d ? (
        <SectionCard title={`处置单 ${d.dispositionNo}`}>
          <div className="space-y-3 text-sm">
            <div className="grid gap-2 md:grid-cols-2">
              <div>
                <div className="text-muted-foreground text-xs">去向</div>
                <div>
                  {targetStateLabel(d.targetInventoryState)} · {String(d.qty)} 件
                </div>
              </div>
              <div>
                <div className="text-muted-foreground text-xs">状态</div>
                <div>{d.statusLabel}</div>
              </div>
              <div>
                <div className="text-muted-foreground text-xs">来源库位</div>
                <div>
                  {d.sourceWarehouseCode} / {d.sourceLocationCode}
                  {d.sourceLotCode ? ` · 批次 ${d.sourceLotCode}` : ''}
                </div>
              </div>
              <div>
                <div className="text-muted-foreground text-xs">目标库位</div>
                <div>{d.targetLocationCode || '核销（报废的货不在任何库位上）'}</div>
              </div>
              <div className="md:col-span-2">
                <div className="text-muted-foreground text-xs">处置理由</div>
                <div>{d.reasonNote}</div>
              </div>
              {d.scrapReasonCode ? (
                <div>
                  <div className="text-muted-foreground text-xs">损耗原因码</div>
                  <div>{scrapReasonLabel(d.scrapReasonCode)}</div>
                </div>
              ) : null}
            </div>

            {d.targetInventoryState === 'SCRAP' ? (
              <Notice tone="danger" title="这张单是报废">
                {SCRAP_IRREVERSIBLE_NOTE}
              </Notice>
            ) : null}

            {d.status === 'EXECUTION_FAILED' ? (
              <Notice tone="danger" title="上次执行失败">
                {d.executionFailureReason || '原因未记录'}
                　（货还没动，可以原样重试；审批仍然有效。）
              </Notice>
            ) : null}

            {/* 报废损耗记录：财务侧字段恒为 null —— 显示「待 12 财务确认」而不是 0 */}
            {detail.loss ? (
              <div className="rounded border p-3">
                <div className="mb-1 text-sm font-medium">报废损耗记录</div>
                <div className="text-muted-foreground text-xs">
                  报废 {String(detail.loss.scrapQty)} 件 · 原值 {String(detail.loss.originalValue)}{' '}
                  {detail.loss.salvageCurrency} · 残值 {String(detail.loss.salvageAmount)} ·
                  本模块口径损耗{' '}
                  <b>{String(detail.loss.proposedLossAmount)}</b>
                </div>
                {financePending(detail.loss) ? (
                  <div className="mt-1 text-xs text-amber-600">
                    12 财务尚未确认损耗金额（该字段恒为 null，不填 0 冒充「这批货零损耗」）。
                  </div>
                ) : (
                  <div className="mt-1 text-xs">
                    财务已确认：{String(detail.loss.financeLossAmount)} · 凭证{' '}
                    {detail.loss.financeReference || '—'}
                  </div>
                )}
              </div>
            ) : null}

            {/* 审批留痕 */}
            <div className="rounded border p-3">
              <div className="mb-1 text-sm font-medium">留痕</div>
              {detail.events.length === 0 ? (
                <div className="text-muted-foreground text-xs">暂无留痕</div>
              ) : (
                <ul className="space-y-1 text-xs">
                  {detail.events.map((e) => (
                    <li key={e.id}>
                      <b>{e.eventType}</b> · {e.fromStatus || '—'} → {e.toStatus} ·{' '}
                      {e.reasonNote || '—'}
                    </li>
                  ))}
                </ul>
              )}
            </div>

            {/* 动作区：按状态渲染，报废永远要走审批 */}
            <div className="space-y-2">
              {canSubmit(d.status) ? (
                <Button disabled={busy} onClick={() => void submit()}>
                  提交待审
                </Button>
              ) : null}
              {canApprove(d.status) ? (
                <div className="space-y-2">
                  <label className="block text-sm">
                    <span className="mb-1 block">审批意见（必填）</span>
                    <textarea
                      className="w-full rounded border px-2 py-1 text-sm"
                      rows={2}
                      value={decisionNote}
                      onChange={(e) => setDecisionNote(e.target.value)}
                    />
                  </label>
                  <div className="flex gap-2">
                    <Button disabled={busy || decisionNote.trim() === ''} onClick={() => void approve()}>
                      批准
                    </Button>
                    <Button
                      variant="outline"
                      disabled={busy || decisionNote.trim() === ''}
                      onClick={() => void reject()}
                    >
                      驳回
                    </Button>
                  </div>
                </div>
              ) : null}
              {canExecute(d.status) ? (
                <Button disabled={busy} onClick={() => void execute()}>
                  执行库存状态变更
                </Button>
              ) : null}
              {canCancel(d.status) ? (
                <div className="space-y-2">
                  <label className="block text-sm">
                    <span className="mb-1 block">撤销理由（必填）</span>
                    <input
                      className="w-full rounded border px-2 py-1 text-sm"
                      value={cancelReason}
                      onChange={(e) => setCancelReason(e.target.value)}
                    />
                  </label>
                  <Button
                    variant="outline"
                    disabled={busy || cancelReason.trim() === ''}
                    onClick={() => void cancel()}
                  >
                    撤销
                  </Button>
                </div>
              ) : null}
            </div>
          </div>
        </SectionCard>
      ) : null}

      {/* ---- 能力边界（未交付的下游 + 刻意不做的事） ---- */}
      {caps ? (
        <SectionCard title="能力边界">
          <div className="space-y-2 text-xs">
            {caps.boundaries.map((b) => (
              <div key={b.boundary}>
                <b>{b.boundary}</b>
                <span className="text-muted-foreground">（{b.ownerModule}）</span>
                <div className="text-muted-foreground">{b.reason}</div>
              </div>
            ))}
            <div className="border-t pt-2">
              {caps.downstreams.map((d2) => (
                <div key={d2.downstream}>
                  <b>{d2.downstream}</b>
                  <span className="text-muted-foreground">
                    　{d2.delivered ? '已交付' : `未交付（${d2.ownerModule}）`}
                  </span>
                  <div className="text-muted-foreground">{d2.note}</div>
                </div>
              ))}
            </div>
          </div>
        </SectionCard>
      ) : null}
    </div>
  );
}
