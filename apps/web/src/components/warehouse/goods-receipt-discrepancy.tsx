'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import { Camera, FileCheck2, Gavel, RefreshCw, TriangleAlert } from 'lucide-react';
import {
  Button,
  EmptyState,
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
  DiscrepancyClaimRequest,
  DiscrepancyDecisionRequest,
  DiscrepancyDispositionOption,
  DiscrepancyEvidenceRequest,
  DiscrepancyHandlingCloseRequest,
  DiscrepancyHandlingDetail,
  DiscrepancyHandlingRow,
  GoodsReceiptDiscrepancyCapabilities,
} from '../../../../../packages/api-contracts/src/catalog';
import {
  addDiscrepancyEvidence,
  allowedDispositions,
  claimDiscrepancy,
  closeDiscrepancyHandling,
  decideDiscrepancy,
  dependencyCaption,
  discrepancyTypeLabels,
  dispositionLabels,
  evidenceTypeLabel,
  fetchDiscrepancyCapabilities,
  fetchDiscrepancyDetail,
  fetchDiscrepancyHandlings,
  fileToEvidencePayload,
  fmt,
  handlingActions,
  handlingQuantityText,
  handlingSortOptions,
  handlingStatusLabels,
  inboundQualityOptions,
  isRiskType,
  moment,
  qualityRoleLabels,
  stockEffectText,
  stockMoveDirectionLabels,
  type DiscrepancyHandlingParams,
} from './goods-receipt-discrepancy-model';
import { errorText, fetchLocations, fetchQualityStatuses } from '../inventory/inventory-model';

interface LocationOption {
  id: string;
  code: string;
  name: string;
}

/**
 * 8.2.1.2 收货差异（处理）工作区。
 *
 * <p>蓝图：「处理收货差异：短少、破损、型号不符、过期、错发；支持拍照留证、拒收、部分接收、
 * 索赔发起」；输出「差异处理记录 + 索赔单（6 采购）+ 库存拒收不入账记录」。
 *
 * <p>界面纪律：
 *
 * <ul>
 *   <li>**「正常入库」对风险差异类型根本不渲染**（破损 / 少收 / 型号不符 / 过期）。
 *       让操作员点一个注定被 422 拒绝的按钮，等于告诉他「系统坏了」，
 *       而真相是这条路径本就不该存在。同理，风险类型不给出「可售」去向选项。
 *   <li>**接受量 + 拒收量等于差异数量**在页面上显式算出来显示，让三个数的关系自解释。
 *   <li>**库存后果常驻显示**：拒收不入账 / 撤出 / 转入待检 / 无需移动是四件不同的事，
 *       混成一句「已处理」就答不出「那批货到底进没进库」。
 *   <li>**未交付的下游直接禁用并写明归属模块**，而不是给一个点下去 404 的按钮。
 *   <li>加载 / 空 / 错误三态齐全：空态说清「还没有待处理的差异」，不假装有数据。
 * </ul>
 */
export function GoodsReceiptDiscrepancyWorkspace() {
  const [params, setParams] = useState<DiscrepancyHandlingParams>({ page: 0, size: 20 });
  const [data, setData] = useState<Awaited<ReturnType<typeof fetchDiscrepancyHandlings>> | null>(
    null,
  );
  const [caps, setCaps] = useState<GoodsReceiptDiscrepancyCapabilities | null>(null);
  const [locations, setLocations] = useState<LocationOption[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [detail, setDetail] = useState<DiscrepancyHandlingDetail | null>(null);
  const [deciding, setDeciding] = useState<DiscrepancyHandlingDetail | null>(null);
  const [claiming, setClaiming] = useState<DiscrepancyHandlingDetail | null>(null);
  const [evidenceFor, setEvidenceFor] = useState<DiscrepancyHandlingDetail | null>(null);
  const [closing, setClosing] = useState<DiscrepancyHandlingDetail | null>(null);
  const [busy, setBusy] = useState(false);

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      setData(await fetchDiscrepancyHandlings(params));
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
        setCaps(await fetchDiscrepancyCapabilities());
      } catch {
        // capabilities 拿不到不该让整页空掉：列表仍然可用，只是硬规则提示与
        // 去向质量状态选择器会退化。页面不假装自己知道规则。
        setCaps(null);
      }
    })();
    void (async () => {
      try {
        setLocations((await fetchLocations()) as unknown as LocationOption[]);
      } catch {
        setLocations([]);
      }
    })();
  }, []);

  const summary = data?.summary;
  const rows = data?.rows ?? [];

  const openDetail = useCallback(async (id: string) => {
    try {
      setDetail(await fetchDiscrepancyDetail(id));
    } catch (e) {
      setError(errorText(e));
    }
  }, []);

  const run = useCallback(
    async (work: () => Promise<unknown>, after?: (d: DiscrepancyHandlingDetail) => void) => {
      setBusy(true);
      setError(null);
      try {
        await work();
        await load();
        if (after) {
          const fresh = detail ?? deciding ?? claiming ?? evidenceFor ?? closing;
          if (fresh) {
            const updated = await fetchDiscrepancyDetail(fresh.handling.id);
            after(updated);
          }
        }
        setDetail(null);
        setDeciding(null);
        setClaiming(null);
        setEvidenceFor(null);
        setClosing(null);
      } catch (e) {
        setError(errorText(e));
      } finally {
        setBusy(false);
      }
    },
    [load, detail, deciding, claiming, evidenceFor, closing],
  );

  return (
    <div className="flex flex-col gap-4">
      <PageHeader
        title="收货差异"
        description="处理收货差异：拍照留证、拒收、部分接收、索赔发起。破损与短少必须拒收或待检入库。"
        actions={
          <Button variant="ghost" onClick={() => void load()} disabled={loading}>
            <RefreshCw className={loading ? 'animate-spin' : ''} />刷新
          </Button>
        }
      />

      {/* 硬规则常驻：这是本模块唯一一条不能被界面「灵活处理」的规则 */}
      <div className="flex items-start gap-2 rounded-md border border-amber-500/40 bg-amber-500/10 p-3 text-sm">
        <TriangleAlert className="mt-0.5 size-4 shrink-0" />
        <div>
          <p className="font-medium">破损与短少必须拒收或待检入库</p>
          <p className="text-muted-foreground">
            {caps?.sellableRuleNote ??
              '直接入可售会把问题货卖出去，所以破损 / 短收 / 型号不符 / 过期只能拒收、部分接收或转待检。'}
          </p>
        </div>
      </div>

      {caps && (
        <SectionCard title="下游出口接入情况">
          <div className="flex flex-wrap gap-2">
            {caps.downstream.map((d) => (
              <span
                key={d.key}
                title={d.note}
                className={
                  d.available
                    ? 'rounded-md border px-2 py-1 text-xs'
                    : 'rounded-md border border-dashed px-2 py-1 text-xs text-muted-foreground'
                }
              >
                {dependencyCaption(d)}
              </span>
            ))}
          </div>
          <p className="mt-2 text-xs text-muted-foreground">{caps.claimNote}</p>
        </SectionCard>
      )}

      {summary && (
        <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
          <StatCard
            label="处理记录"
            value={String(summary.handlingCount)}
            comparisonLabel={`未结案 ${summary.openCount} 条`}
          />
          <StatCard
            label="待处理"
            value={String(summary.pendingCount)}
            comparisonLabel="已接手但尚未决定"
          />
          <StatCard
            label="风险差异"
            value={String(summary.riskCount)}
            comparisonLabel="破损 / 短少 / 型号不符 / 过期"
          />
          <StatCard
            label="未索赔的风险差异"
            value={String(summary.unclaimedRiskCount)}
            comparisonLabel="已决定但还没发起索赔"
          />
        </div>
      )}

      <SectionCard title="筛选">
        <div className="grid gap-3 md:grid-cols-4">
          <label className="flex flex-col gap-1 text-sm">
            <span className="text-muted-foreground">关键字</span>
            <input
              className="rounded-md border px-3 py-2"
              placeholder="收货单号 / SKU / 差异原因 / 索赔单号"
              defaultValue={params.keyword ?? ''}
              onBlur={(e) => setParams((p) => ({ ...p, keyword: e.target.value, page: 0 }))}
            />
          </label>
          <label className="flex flex-col gap-1 text-sm">
            <span className="text-muted-foreground">处理状态</span>
            <select
              className="rounded-md border px-3 py-2"
              value={params.status?.[0] ?? ''}
              onChange={(e) =>
                setParams((p) => {
                  const next: DiscrepancyHandlingParams = { ...p, page: 0 };
                  if (e.target.value) next.status = [e.target.value];
                  else delete next.status;
                  return next;
                })
              }
            >
              <option value="">全部状态</option>
              {(data?.statusOptions ?? []).map((o) => (
                <option key={o.value} value={o.value}>
                  {o.label}（{o.count}）
                </option>
              ))}
            </select>
          </label>
          <label className="flex flex-col gap-1 text-sm">
            <span className="text-muted-foreground">差异类型</span>
            <select
              className="rounded-md border px-3 py-2"
              value={params.discrepancyType?.[0] ?? ''}
              onChange={(e) =>
                setParams((p) => {
                  const next: DiscrepancyHandlingParams = { ...p, page: 0 };
                  if (e.target.value) next.discrepancyType = [e.target.value];
                  else delete next.discrepancyType;
                  return next;
                })
              }
            >
              <option value="">全部类型</option>
              {Object.entries(discrepancyTypeLabels).map(([value, label]) => (
                <option key={value} value={value}>
                  {label}
                </option>
              ))}
            </select>
          </label>
          <label className="flex flex-col gap-1 text-sm">
            <span className="text-muted-foreground">排序</span>
            <select
              className="rounded-md border px-3 py-2"
              value={params.sort ?? 'UPDATED_AT'}
              onChange={(e) => setParams((p) => ({ ...p, sort: e.target.value, page: 0 }))}
            >
              {handlingSortOptions.map((o) => (
                <option key={o.value} value={o.value}>
                  {o.label}
                </option>
              ))}
            </select>
          </label>
        </div>
      </SectionCard>

      {error && (
        <div className="rounded-md border border-red-500/40 bg-red-500/10 p-3 text-sm">{error}</div>
      )}

      <SectionCard title="差异处理记录">
        {loading ? (
          <p className="text-sm text-muted-foreground">正在加载差异处理记录…</p>
        ) : rows.length === 0 ? (
          <EmptyState
            title="还没有待处理的差异"
            description="收货现场登记的差异（破损 / 短少 / 型号不符 / 过期 / 错发）需要在这里接手并给出处理决定。"
          />
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead>
                <tr className="border-b text-left">
                  <th className="py-2 pr-3">收货单</th>
                  <th className="py-2 pr-3">差异</th>
                  <th className="py-2 pr-3 text-right">数量</th>
                  <th className="py-2 pr-3">状态</th>
                  <th className="py-2 pr-3">处理决定</th>
                  <th className="py-2 pr-3 text-right">接受 / 拒收</th>
                  <th className="py-2 pr-3 text-right">凭证</th>
                  <th className="py-2 pr-3">索赔</th>
                  <th className="py-2">更新时间</th>
                </tr>
              </thead>
              <tbody>
                {rows.map((row) => (
                  <tr key={row.id} className="border-b last:border-0">
                    <td className="py-2 pr-3">
                      <button
                        className="text-left underline-offset-2 hover:underline"
                        onClick={() => void openDetail(row.id)}
                      >
                        {row.receiptCode}
                      </button>
                      <div className="text-xs text-muted-foreground">
                        {row.skuCode ? `${row.skuCode} ${row.skuName}` : '单级差异（无 SKU）'}
                      </div>
                    </td>
                    <td className="py-2 pr-3">
                      <span
                        className={
                          isRiskType(row.discrepancyType)
                            ? 'rounded border border-amber-500/50 px-1.5 py-0.5 text-xs'
                            : 'text-xs'
                        }
                      >
                        {row.discrepancyTypeLabel}
                      </span>
                    </td>
                    <td className="py-2 pr-3 text-right tabular-nums">{fmt(row.quantity)}</td>
                    <td className="py-2 pr-3">{row.statusLabel}</td>
                    <td className="py-2 pr-3">
                      {row.dispositionLabel ??
                        (row.status === 'PENDING' ? (
                          <span className="text-muted-foreground">待决定</span>
                        ) : (
                          '—'
                        ))}
                      {row.inboundQualityStatusName && (
                        <div className="text-xs text-muted-foreground">
                          去向 {row.inboundQualityStatusName}
                        </div>
                      )}
                    </td>
                    <td className="py-2 pr-3 text-right tabular-nums">
                      {row.status === 'PENDING'
                        ? '—'
                        : `${fmt(row.acceptedQuantity)} / ${fmt(row.rejectedQuantity)}`}
                    </td>
                    <td className="py-2 pr-3 text-right tabular-nums">{row.evidenceCount}</td>
                    <td className="py-2 pr-3 text-xs">
                      {row.claimed ? row.claimStatus : <span className="text-muted-foreground">未索赔</span>}
                    </td>
                    <td className="py-2 text-xs text-muted-foreground">{moment(row.updatedAt)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
        {data && data.total > 0 && (
          <div className="mt-3 flex items-center gap-2 text-sm">
            <Button
              variant="ghost"
              disabled={(params.page ?? 0) === 0}
              onClick={() => setParams((p) => ({ ...p, page: Math.max(0, (p.page ?? 0) - 1) }))}
            >
              上一页
            </Button>
            <span className="text-muted-foreground">
              第 {(data.page ?? 0) + 1} / {Math.max(1, Math.ceil(data.total / data.size))} 页，共{' '}
              {data.total} 条
            </span>
            <Button
              variant="ghost"
              disabled={(params.page ?? 0) + 1 >= Math.ceil(data.total / data.size)}
              onClick={() => setParams((p) => ({ ...p, page: (p.page ?? 0) + 1 }))}
            >
              下一页
            </Button>
          </div>
        )}
      </SectionCard>

      {detail && (
        <DetailDialog
          detail={detail}
          qualityOptions={data?.qualityStatusOptions ?? []}
          dispositionOptions={caps?.dispositionOptions ?? []}
          busy={busy}
          onClose={() => setDetail(null)}
          onDecide={() => {
            setDeciding(detail);
            setDetail(null);
          }}
          onClaim={() => {
            setClaiming(detail);
            setDetail(null);
          }}
          onEvidence={() => {
            setEvidenceFor(detail);
            setDetail(null);
          }}
          onClose2={() => {
            setClosing(detail);
            setDetail(null);
          }}
        />
      )}

      {deciding && (
        <DecideDialog
          detail={deciding}
          qualityOptions={data?.qualityStatusOptions ?? []}
          dispositionOptions={caps?.dispositionOptions ?? []}
          locations={locations}
          busy={busy}
          onCancel={() => setDeciding(null)}
          onSubmit={(input) =>
            void run(
              () => decideDiscrepancy(deciding.handling.id, input),
              (d) => setDetail(d),
            )
          }
        />
      )}

      {evidenceFor && (
        <EvidenceDialog
          detail={evidenceFor}
          busy={busy}
          onCancel={() => setEvidenceFor(null)}
          onSubmit={(input) =>
            void run(
              () => addDiscrepancyEvidence(evidenceFor.handling.id, input),
              (d) => setDetail(d),
            )
          }
        />
      )}

      {claiming && (
        <ClaimDialog
          detail={claiming}
          busy={busy}
          onCancel={() => setClaiming(null)}
          onSubmit={(input) =>
            void run(() => claimDiscrepancy(claiming.handling.id, input), (d) => setDetail(d))
          }
        />
      )}

      {closing && (
        <CloseDialog
          detail={closing}
          busy={busy}
          onCancel={() => setClosing(null)}
          onSubmit={(input) =>
            void run(
              () => closeDiscrepancyHandling(closing.handling.id, input),
              (d) => setDetail(d),
            )
          }
        />
      )}
    </div>
  );
}

function DetailDialog(props: {
  detail: DiscrepancyHandlingDetail;
  qualityOptions: Array<{ id: string; code: string; name: string; role: string }>;
  dispositionOptions: DiscrepancyDispositionOption[];
  busy: boolean;
  onClose: () => void;
  onDecide: () => void;
  onClaim: () => void;
  onEvidence: () => void;
  onClose2: () => void;
}) {
  const { detail } = props;
  const h = detail.handling;
  const actions = handlingActions(h.status);
  const qty = handlingQuantityText(h);
  return (
    <Modal open onOpenChange={props.onClose}>
      <ModalContent className="max-w-3xl">
        <ModalHeader>
          <ModalTitle>
            {h.receiptCode} · {h.discrepancyTypeLabel} · {fmt(h.quantity)}
          </ModalTitle>
          <ModalDescription>
            处理状态 {h.statusLabel}
            {h.dispositionLabel ? ` · 处理决定 ${h.dispositionLabel}` : ''}
          </ModalDescription>
        </ModalHeader>
        <div className="flex flex-col gap-4 text-sm">
          <p className={qty.ok ? 'text-muted-foreground' : 'text-red-600'}>{qty.text}</p>
          {detail.discrepancy && (
            <section>
              <h3 className="mb-1 font-medium">差异事实（8.2.1.1 登记）</h3>
              <p className="text-muted-foreground">
                原因码 {detail.discrepancy.reasonCode || '—'} · {detail.discrepancy.reasonNote || '—'}
              </p>
              <p className="text-xs text-muted-foreground">
                记录于 {moment(detail.discrepancy.recordedAt)}
              </p>
            </section>
          )}
          <section>
            <h3 className="mb-1 font-medium">凭证（{detail.evidence.length}）</h3>
            {detail.evidence.length === 0 ? (
              <p className="text-muted-foreground">还没有留证。索赔时拿不出照片等于没留证。</p>
            ) : (
              <ul className="flex flex-col gap-2">
                {detail.evidence.map((e) => (
                  <li key={e.id} className="flex items-start gap-3">
                    {e.contentType.startsWith('image/') ? (
                      // eslint-disable-next-line @next/next/no-img-element
                      <img
                        src={e.dataUrl}
                        alt={e.note || e.fileName || '凭证照片'}
                        className="size-20 rounded-md border object-cover"
                      />
                    ) : (
                      <div className="flex size-20 items-center justify-center rounded-md border text-xs">
                        {evidenceTypeLabel(e.evidenceType)}
                      </div>
                    )}
                    <div className="text-xs text-muted-foreground">
                      <p>
                        {e.evidenceTypeLabel} · {fmt(e.byteSize / 1024)} KB ·{' '}
                        {moment(e.capturedAt)}
                      </p>
                      {e.note && <p>{e.note}</p>}
                      <p className="font-mono">{e.sha256.slice(0, 16)}…</p>
                    </div>
                  </li>
                ))}
              </ul>
            )}
          </section>
          <section>
            <h3 className="mb-1 font-medium">库存流转（{detail.stockMoves.length}）</h3>
            <p className="text-muted-foreground">{stockEffectText(detail.stockMoves)}</p>
            <ul className="mt-1 flex flex-col gap-1 text-xs text-muted-foreground">
              {detail.stockMoves.map((m) => (
                <li key={m.id}>
                  {stockMoveDirectionLabels[m.direction] ?? m.direction} {fmt(m.quantity)}
                  {m.fromQualityStatusName ? `（自 ${m.fromQualityStatusName}）` : ''}
                  {m.toQualityStatusName ? ` → ${m.toQualityStatusName}` : ''} · {m.note}
                </li>
              ))}
            </ul>
            <p className="mt-1 text-xs text-muted-foreground">{detail.notPostedNote}</p>
          </section>
          <section>
            <h3 className="mb-1 font-medium">索赔</h3>
            {detail.claim ? (
              <div className="text-xs text-muted-foreground">
                <p>
                  {detail.claim.claimCode} · {detail.claim.claimTypeLabel} ·{' '}
                  {detail.claim.statusLabel} ·{' '}
                  {detail.claim.claimedAmount !== null && detail.claim.claimedAmount !== undefined
                    ? `${detail.claim.currency} ${fmt(detail.claim.claimedAmount)}`
                    : '金额待报价'}
                </p>
                <p>
                  来源单据 {detail.claim.sourceRefCode || '—'}；对账索赔单号{' '}
                  {detail.claim.claimRefId ?? '（6.5.1 未交付，尚无单号）'}
                </p>
              </div>
            ) : (
              <p className="text-muted-foreground">尚未发起索赔。</p>
            )}
          </section>
        </div>
        <ModalFooter>
          {actions.includes('evidence') && (
            <Button variant="ghost" onClick={props.onEvidence} disabled={props.busy}>
              <Camera />拍照留证
            </Button>
          )}
          {actions.includes('decide') && (
            <Button onClick={props.onDecide} disabled={props.busy}>
              <FileCheck2 />给出处理决定
            </Button>
          )}
          {actions.includes('claim') && (
            <Button variant="ghost" onClick={props.onClaim} disabled={props.busy}>
              <Gavel />发起索赔
            </Button>
          )}
          {actions.includes('close') && (
            <Button variant="ghost" onClick={props.onClose2} disabled={props.busy}>
              结案
            </Button>
          )}
          <Button variant="ghost" onClick={props.onClose}>
            关闭
          </Button>
        </ModalFooter>
      </ModalContent>
    </Modal>
  );
}

function DecideDialog(props: {
  detail: DiscrepancyHandlingDetail;
  qualityOptions: Array<{ id: string; code: string; name: string; role: string }>;
  dispositionOptions: DiscrepancyDispositionOption[];
  locations: LocationOption[];
  busy: boolean;
  onCancel: () => void;
  onSubmit: (input: DiscrepancyDecisionRequest) => void;
}) {
  const { detail } = props;
  const h = detail.handling;
  const risk = isRiskType(h.discrepancyType);
  const options = useMemo(
    () => allowedDispositions(h.discrepancyType, props.dispositionOptions),
    [h.discrepancyType, props.dispositionOptions],
  );
  const qualityOptions = useMemo(
    () => inboundQualityOptions(h.discrepancyType, props.qualityOptions),
    [h.discrepancyType, props.qualityOptions],
  );
  const [disposition, setDisposition] = useState(options[0] ?? 'REJECT');
  const [accepted, setAccepted] = useState<string>('');
  const [rejected, setRejected] = useState<string>('');
  const [inbound, setInbound] = useState<string>(qualityOptions[0]?.id ?? '');
  const [from, setFrom] = useState<string>('');
  const [location, setLocation] = useState<string>(props.locations[0]?.id ?? '');
  const [reason, setReason] = useState('');

  const quantity = Number(h.quantity) || 0;
  const acceptedNum = disposition === 'PARTIAL_ACCEPT' ? Number(accepted || 0) : quantity;
  const rejectedNum = disposition === 'PARTIAL_ACCEPT' ? Number(rejected || 0) : 0;
  const sumOk = Math.abs(acceptedNum + rejectedNum - quantity) < 0.0001;

  const submit = () => {
    // 契约里每个字段都是必填、可空列用 null：漏一个字段就是 400，
    // 而漏「可空列」在前端表现为「不传」—— 两者在服务端含义完全不同。
    const isPartial = disposition === 'PARTIAL_ACCEPT';
    props.onSubmit({
      expectedVersion: h.version,
      disposition,
      acceptedQuantity: isPartial ? acceptedNum : null,
      rejectedQuantity: isPartial ? rejectedNum : null,
      // 接受量 > 0 时才有去向；没有货入库就不存在去向。
      inboundQualityStatusId: acceptedNum > 0 ? inbound : null,
      // 给了库位 + 给了原质量状态 = 货已在库里，要挪走（总量守恒）；
      // 给了库位但没给原质量状态 = 货第一次进库。留空库位 = 从不入库。
      fromQualityStatusId: from || null,
      locationId: location || null,
      lotId: null,
      reason,
      note: '',
    });
  };

  return (
    <Modal open onOpenChange={props.onCancel}>
      <ModalContent className="max-w-xl">
        <ModalHeader>
          <ModalTitle>给出处理决定</ModalTitle>
          <ModalDescription>
            {h.receiptCode} · {h.discrepancyTypeLabel} · 差异数量 {fmt(h.quantity)}
          </ModalDescription>
        </ModalHeader>
        <div className="flex flex-col gap-3 text-sm">
          {risk && (
            <div className="rounded-md border border-amber-500/40 bg-amber-500/10 p-2 text-xs">
              {h.discrepancyTypeLabel}不允许按「正常入库」处理：该选项已从界面上移除。
            </div>
          )}
          <label className="flex flex-col gap-1">
            <span className="text-muted-foreground">处理决定</span>
            <select
              className="rounded-md border px-3 py-2"
              value={disposition}
              onChange={(e) => setDisposition(e.target.value)}
            >
              {options.map((value) => (
                <option key={value} value={value}>
                  {dispositionLabels[value] ?? value}
                </option>
              ))}
            </select>
          </label>
          {disposition === 'PARTIAL_ACCEPT' ? (
            <div className="grid grid-cols-2 gap-3">
              <label className="flex flex-col gap-1">
                <span className="text-muted-foreground">接受数量</span>
                <input
                  className="rounded-md border px-3 py-2"
                  type="number"
                  min={0}
                  step="0.0001"
                  value={accepted}
                  onChange={(e) => setAccepted(e.target.value)}
                />
              </label>
              <label className="flex flex-col gap-1">
                <span className="text-muted-foreground">拒收数量</span>
                <input
                  className="rounded-md border px-3 py-2"
                  type="number"
                  min={0}
                  step="0.0001"
                  value={rejected}
                  onChange={(e) => setRejected(e.target.value)}
                />
              </label>
              <p className={`col-span-2 text-xs ${sumOk ? 'text-muted-foreground' : 'text-red-600'}`}>
                差异 {fmt(quantity)} = 接受 {fmt(acceptedNum)} + 拒收 {fmt(rejectedNum)}
                {sumOk ? '' : '（必须恰好等于差异数量）'}
              </p>
            </div>
          ) : (
            <p className="text-xs text-muted-foreground">
              {disposition === 'REJECT'
                ? `全部 ${fmt(quantity)} 拒收。`
                : `全部 ${fmt(quantity)} 按下列去向入库。`}
            </p>
          )}

          {acceptedNum > 0 && (
            <label className="flex flex-col gap-1">
              <span className="text-muted-foreground">入库去向质量状态</span>
              <select
                className="rounded-md border px-3 py-2"
                value={inbound}
                onChange={(e) => setInbound(e.target.value)}
              >
                {qualityOptions.map((q) => (
                  <option key={q.id} value={q.id}>
                    {q.name}（{qualityRoleLabels[q.role] ?? q.role}）
                  </option>
                ))}
              </select>
              {risk && (
                <span className="text-xs text-muted-foreground">
                  风险差异不提供「可售」去向：直接入可售会把问题货卖出去。
                </span>
              )}
            </label>
          )}

          <label className="flex flex-col gap-1">
            <span className="text-muted-foreground">
              落库库位（拒收时留空 = 这批货从未进过库）
            </span>
            <select
              className="rounded-md border px-3 py-2"
              value={location}
              onChange={(e) => setLocation(e.target.value)}
            >
              <option value="">（不入库）</option>
              {props.locations.map((l) => (
                <option key={l.id} value={l.id}>
                  {l.code} {l.name}
                </option>
              ))}
            </select>
          </label>

          {location && (
            <label className="flex flex-col gap-1">
              <span className="text-muted-foreground">货当前所在的���质状态（留空 = 尚未入库）</span>
              <select
                className="rounded-md border px-3 py-2"
                value={from}
                onChange={(e) => setFrom(e.target.value)}
              >
                <option value="">（尚未入库，属纯入库）</option>
                {props.qualityOptions.map((q) => (
                  <option key={q.id} value={q.id}>
                    {q.name}
                  </option>
                ))}
              </select>
            </label>
          )}

          <label className="flex flex-col gap-1">
            <span className="text-muted-foreground">决定原因</span>
            <input
              className="rounded-md border px-3 py-2"
              value={reason}
              onChange={(e) => setReason(e.target.value)}
              placeholder="例：外箱压塌整箱拒收"
            />
          </label>
        </div>
        <ModalFooter>
          <Button
            onClick={submit}
            disabled={props.busy || !sumOk || (acceptedNum > 0 && (!inbound || !location))}
          >
            提交处理决定
          </Button>
          <Button variant="ghost" onClick={props.onCancel}>
            取消
          </Button>
        </ModalFooter>
      </ModalContent>
    </Modal>
  );
}

function EvidenceDialog(props: {
  detail: DiscrepancyHandlingDetail;
  busy: boolean;
  onCancel: () => void;
  onSubmit: (input: DiscrepancyEvidenceRequest) => void;
}) {
  const [evidenceType, setEvidenceType] = useState('PHOTO');
  const [note, setNote] = useState('');
  const [payload, setPayload] = useState<{
    fileName: string;
    contentType: string;
    contentBase64: string;
  } | null>(null);
  const [fileError, setFileError] = useState<string | null>(null);

  const onFile = async (file: File | undefined) => {
    setFileError(null);
    setPayload(null);
    if (!file) return;
    try {
      setPayload(await fileToEvidencePayload(file));
    } catch (e) {
      setFileError(e instanceof Error ? e.message : String(e));
    }
  };

  return (
    <Modal open onOpenChange={props.onCancel}>
      <ModalContent className="max-w-lg">
        <ModalHeader>
          <ModalTitle>拍照留证</ModalTitle>
          <ModalDescription>凭证本体真落库，不是「将来会有的」外链。</ModalDescription>
        </ModalHeader>
        <div className="flex flex-col gap-3 text-sm">
          <label className="flex flex-col gap-1">
            <span className="text-muted-foreground">凭证类型</span>
            <select
              className="rounded-md border px-3 py-2"
              value={evidenceType}
              onChange={(e) => setEvidenceType(e.target.value)}
            >
              <option value="PHOTO">照片</option>
              <option value="WEIGHING">复磅单</option>
              <option value="INSPECTION">质检报告</option>
              <option value="DOCUMENT">单据</option>
            </select>
          </label>
          <label className="flex flex-col gap-1">
            <span className="text-muted-foreground">选择照片</span>
            <input
              type="file"
              accept="image/*"
              className="rounded-md border px-3 py-2 text-xs"
              onChange={(e) => void onFile(e.target.files?.[0])}
            />
            <span className="text-xs text-muted-foreground">
              会在浏览器里压到长边 1280px 的 JPEG 再上传，原始时刻与 sha256 一并留痕。
            </span>
          </label>
          {fileError && <p className="text-xs text-red-600">{fileError}</p>}
          {payload && (
            <p className="text-xs text-muted-foreground">
              已压缩至 {fmt(Math.round((payload.contentBase64.length * 3) / 4 / 1024))} KB。
            </p>
          )}
          <label className="flex flex-col gap-1">
            <span className="text-muted-foreground">凭证说明</span>
            <input
              className="rounded-md border px-3 py-2"
              value={note}
              onChange={(e) => setNote(e.target.value)}
              placeholder="例：外箱压塌，可见内件完好"
            />
          </label>
        </div>
        <ModalFooter>
          <Button
            disabled={props.busy || !payload}
            onClick={() =>
              payload &&
              props.onSubmit({
                evidenceType,
                fileName: payload.fileName,
                contentType: payload.contentType,
                contentBase64: payload.contentBase64,
                capturedAt: null,
                note,
              })
            }
          >
            上传凭证
          </Button>
          <Button variant="ghost" onClick={props.onCancel}>
            取消
          </Button>
        </ModalFooter>
      </ModalContent>
    </Modal>
  );
}

function ClaimDialog(props: {
  detail: DiscrepancyHandlingDetail;
  busy: boolean;
  onCancel: () => void;
  onSubmit: (input: DiscrepancyClaimRequest) => void;
}) {
  const { detail } = props;
  const h = detail.handling;
  const defaultType =
    h.discrepancyType === 'SHORT_RECEIPT'
      ? 'SHORTAGE'
      : h.discrepancyType === 'MISDELIVERED'
        ? 'MISDELIVERY'
        : h.discrepancyType === 'DAMAGED'
          ? 'DAMAGE'
          : h.discrepancyType;
  const [claimType, setClaimType] = useState(defaultType);
  const [amount, setAmount] = useState('');
  const [currency, setCurrency] = useState('CNY');
  const [reason, setReason] = useState('');

  const amountNum = Number(amount);
  const pairOk = (amount.trim() === '') === (currency.trim() === '');

  return (
    <Modal open onOpenChange={props.onCancel}>
      <ModalContent className="max-w-lg">
        <ModalHeader>
          <ModalTitle>发起索赔</ModalTitle>
          <ModalDescription>
            只记录来源单据与索赔意图。6.3.2.2 交付后由上游建自己的索赔单并回填单号。
          </ModalDescription>
        </ModalHeader>
        <div className="flex flex-col gap-3 text-sm">
          <label className="flex flex-col gap-1">
            <span className="text-muted-foreground">索赔类型</span>
            <select
              className="rounded-md border px-3 py-2"
              value={claimType}
              onChange={(e) => setClaimType(e.target.value)}
            >
              <option value="SHORTAGE">短少</option>
              <option value="DAMAGE">货损</option>
              <option value="WRONG_ITEM">型号不符</option>
              <option value="EXPIRED">过期</option>
              <option value="MISDELIVERY">错发</option>
              <option value="OVER_RECEIPT">超收</option>
            </select>
          </label>
          <div className="grid grid-cols-2 gap-3">
            <label className="flex flex-col gap-1">
              <span className="text-muted-foreground">索赔金额（可空）</span>
              <input
                className="rounded-md border px-3 py-2"
                type="number"
                min={0}
                step="0.01"
                value={amount}
                onChange={(e) => setAmount(e.target.value)}
              />
            </label>
            <label className="flex flex-col gap-1">
              <span className="text-muted-foreground">币种</span>
              <input
                className="rounded-md border px-3 py-2"
                value={currency}
                onChange={(e) => setCurrency(e.target.value)}
                placeholder="CNY"
              />
            </label>
          </div>
          {!pairOk && (
            <p className="text-xs text-red-600">金额与币种必须成对：供应商还没报价就两处都留空。</p>
          )}
          <label className="flex flex-col gap-1">
            <span className="text-muted-foreground">索赔说明</span>
            <input
              className="rounded-md border px-3 py-2"
              value={reason}
              onChange={(e) => setReason(e.target.value)}
              placeholder="例：外箱压塌 10 件，按采购单价索赔"
            />
          </label>
        </div>
        <ModalFooter>
          <Button
            disabled={props.busy || !pairOk || (amount.trim() !== '' && amountNum <= 0)}
            onClick={() =>
              props.onSubmit({
                expectedVersion: h.version,
                claimType,
                // 不填索赔数量就按整条差异数量索赔，与「默认取整条差异」的服务层行为一致。
                claimedQuantity: null,
                claimedAmount: amount.trim() === '' ? null : amountNum,
                currency: amount.trim() === '' ? null : currency,
                // 来源单据默认取收货单上的来源（服务层行为），这里不覆盖。
                sourceRefId: null,
                sourceRefCode: '',
                reason,
              })
            }
          >
            发起索赔
          </Button>
          <Button variant="ghost" onClick={props.onCancel}>
            取消
          </Button>
        </ModalFooter>
      </ModalContent>
    </Modal>
  );
}

function CloseDialog(props: {
  detail: DiscrepancyHandlingDetail;
  busy: boolean;
  onCancel: () => void;
  onSubmit: (input: DiscrepancyHandlingCloseRequest) => void;
}) {
  const [reason, setReason] = useState('');
  return (
    <Modal open onOpenChange={props.onCancel}>
      <ModalContent className="max-w-md">
        <ModalHeader>
          <ModalTitle>结案</ModalTitle>
          <ModalDescription>已关闭是终态：不再接受新凭证与处理动作。</ModalDescription>
        </ModalHeader>
        <label className="flex flex-col gap-1 text-sm">
          <span className="text-muted-foreground">关闭原因</span>
          <input
            className="rounded-md border px-3 py-2"
            value={reason}
            onChange={(e) => setReason(e.target.value)}
          />
        </label>
        <ModalFooter>
          <Button
            disabled={props.busy}
            onClick={() =>
              props.onSubmit({ expectedVersion: props.detail.handling.version, reason })
            }
          >
            确认结案
          </Button>
          <Button variant="ghost" onClick={props.onCancel}>
            取消
          </Button>
        </ModalFooter>
      </ModalContent>
    </Modal>
  );
}

export type { DiscrepancyHandlingRow };
