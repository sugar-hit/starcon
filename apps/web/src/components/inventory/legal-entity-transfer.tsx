'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import {
  ArrowRightLeft,
  BadgeCheck,
  Building2,
  Plus,
  RefreshCw,
  Scale,
  TriangleAlert,
} from 'lucide-react';
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
  LegalEntityTransferDetail,
  LegalEntityTransferSaved,
} from '../../../../../packages/api-contracts/src/catalog';
import {
  cancelCrossEntityTransfer,
  closeCrossEntityTransfer,
  confirmSourceSide,
  confirmTargetSide,
  conservationText,
  createCrossEntityTransfer,
  crossEntityActions,
  crossEntitySettlementLabels,
  crossEntitySortOptions,
  crossEntityStatusLabels,
  crossEntityValuationLabels,
  fetchCrossEntityTransferDetail,
  fetchCrossEntityTransfers,
  fmt,
  moment,
  money,
  partiesText,
  receivable,
  receiveCrossEntityTransfer,
  remainingToReceive,
  routeText,
  shipCrossEntityTransfer,
  type CrossEntityTransferParams,
} from './legal-entity-transfer-model';
import { errorText, fetchLegalEntities, fetchQualityStatuses, fetchSkus, fetchWarehouses } from './inventory-model';
import { useIdentity } from '../iam/identity-shell';

/**
 * 7.5.2.1 跨法人调拨工作区。
 *
 * <p>蓝图：「法人之间的库存转移（业务上是**货权转移 + 内部结算**），需要定价、生成内部往来单据、
 * 双方确认，**不能当作普通同仓调拨**」；输出「跨法人调拨单 + 内部结算单据 + 双方库存变更」。
 *
 * <p>界面纪律：
 *
 * <ul>
 *   <li>**两个法人是页面的第一视觉要素**，不是筛选器里的两个下拉。货权变了意味着利润归属变了，
 *       把它排成「调拨单的一个筛选项」就抹掉了本模块的全部意义。
 *   <li>**定价与金额必须显形**：单价、定价口径、金额三列并排。看不到定价就看不到内部结算凭据。
 *   <li>**两个时刻分开显示**：发出时开凭据、实收时货权才转移。合成一句话会让运营以为
 *       「已开凭据 = 货已归对方」，而那正是内部结算最容易出错的地方。
 *   <li>**授权现状如实展示**（详情页 `authorizationNote`）：能点确认不等于有权代表该法人，
 *       逐法人授权属 17 权限、尚未交付。不写出来就是让人误以为已经接好了。
 *   <li>**金额按币种分行汇总**：整单单币种但列表跨单，跨币种直接相加是错的。
 *   <li>**按钮按状态给**（`crossEntityActions`），终态不再显示发货/确认。
 * </ul>
 */
export function LegalEntityTransferWorkspace() {
  const { session } = useIdentity();
  const readable = session.permissions.includes('warehouse.inventory.read');
  const writable = session.permissions.includes('warehouse.inventory.update');

  const [page, setPage] = useState<Awaited<ReturnType<typeof fetchCrossEntityTransfers>> | null>(
    null,
  );
  const [warehouses, setWarehouses] = useState<{ id: string; code: string; name: string }[]>([]);
  const [entities, setEntities] = useState<{ id: string; code: string; name: string }[]>([]);
  const [skus, setSkus] = useState<{ id: string; code: string; name: string }[]>([]);
  const [qualities, setQualities] = useState<{ id: string; code: string; name: string }[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');

  const [expanded, setExpanded] = useState<string | null>(null);
  const [detail, setDetail] = useState<LegalEntityTransferDetail | null>(null);
  const [detailError, setDetailError] = useState('');
  const [detailLoading, setDetailLoading] = useState(false);
  const [actionError, setActionError] = useState('');
  const [busy, setBusy] = useState('');

  const [keyword, setKeyword] = useState('');
  const [fromEntity, setFromEntity] = useState('');
  const [toEntity, setToEntity] = useState('');
  const [status, setStatus] = useState('');
  const [sort, setSort] = useState('CREATED_AT');
  const [pageNumber, setPageNumber] = useState(0);

  const [createOpen, setCreateOpen] = useState(false);
  const [draftFromWarehouse, setDraftFromWarehouse] = useState('');
  const [draftToWarehouse, setDraftToWarehouse] = useState('');
  const [draftFromEntity, setDraftFromEntity] = useState('');
  const [draftToEntity, setDraftToEntity] = useState('');
  const [draftBasis, setDraftBasis] = useState('TRANSFER_COST');
  const [draftCurrency, setDraftCurrency] = useState('CNY');
  const [draftSku, setDraftSku] = useState('');
  const [draftQuality, setDraftQuality] = useState('');
  const [draftQuantity, setDraftQuantity] = useState('');
  const [draftUnitPrice, setDraftUnitPrice] = useState('');
  const [draftReason, setDraftReason] = useState('');

  const [shipOpen, setShipOpen] = useState(false);
  const [receiveOpen, setReceiveOpen] = useState(false);
  const [receiveAmounts, setReceiveAmounts] = useState<Record<string, string>>({});
  const [receiveLocation, setReceiveLocation] = useState('');
  const [receiveNotes, setReceiveNotes] = useState('');
  const [cancelOpen, setCancelOpen] = useState(false);
  const [cancelReason, setCancelReason] = useState('');
  const [confirmOpen, setConfirmOpen] = useState<{ id: string; side: 'SOURCE' | 'TARGET'; version: number } | null>(
    null,
  );
  const [confirmNotes, setConfirmNotes] = useState('');

  const load = useCallback(async () => {
    if (!readable) {
      setLoading(false);
      return;
    }
    setLoading(true);
    setError('');
    const params: CrossEntityTransferParams = { sort, page: pageNumber, size: 50 };
    if (keyword.trim()) params.keyword = keyword.trim();
    if (fromEntity) params.fromLegalEntityId = fromEntity;
    if (toEntity) params.toLegalEntityId = toEntity;
    if (status) params.status = [status];
    try {
      setPage(await fetchCrossEntityTransfers(params));
    } catch (e) {
      setError(errorText(e));
    } finally {
      setLoading(false);
    }
  }, [readable, sort, pageNumber, keyword, fromEntity, toEntity, status]);

  useEffect(() => {
    void load();
  }, [load]);

  useEffect(() => {
    if (!readable) return;
    void Promise.all([fetchWarehouses(), fetchLegalEntities(), fetchSkus(), fetchQualityStatuses()])
      .then(([w, e, s, q]) => {
        setWarehouses(w);
        setEntities(e);
        setSkus(s);
        setQualities(q);
        const first = q[0];
        if (first && !draftQuality) setDraftQuality(first.id);
      })
      .catch((e) => setError(errorText(e)));
  }, [readable, draftQuality]);

  const openDetail = useCallback(
    async (id: string) => {
      if (expanded === id) {
        setExpanded(null);
        setDetail(null);
        return;
      }
      setExpanded(id);
      setDetail(null);
      setDetailError('');
      setDetailLoading(true);
      try {
        setDetail(await fetchCrossEntityTransferDetail(id));
      } catch (e) {
        setDetailError(errorText(e));
      } finally {
        setDetailLoading(false);
      }
    },
    [expanded],
  );

  /** 动作统一出口：回读最新版本再刷新列表，避免乐观锁版本对不上。 */
  const run = useCallback(
    async (key: string, work: () => Promise<LegalEntityTransferSaved>) => {
      setBusy(key);
      setActionError('');
      try {
        await work();
        await load();
        if (expanded) {
          setDetail(await fetchCrossEntityTransferDetail(expanded));
        }
      } catch (e) {
        setActionError(errorText(e));
      } finally {
        setBusy('');
      }
    },
    [load, expanded],
  );

  const draftAmount = useMemo(() => {
    const q = Number(draftQuantity) || 0;
    const p = Number(draftUnitPrice) || 0;
    return q * p;
  }, [draftQuantity, draftUnitPrice]);

  const draftReady =
    Boolean(draftFromWarehouse) &&
    Boolean(draftToWarehouse) &&
    Boolean(draftFromEntity) &&
    Boolean(draftToEntity) &&
    draftFromEntity !== draftToEntity &&
    draftFromWarehouse !== draftToWarehouse &&
    Boolean(draftSku) &&
    Boolean(draftQuality) &&
    Number(draftQuantity) > 0 &&
    draftUnitPrice !== '' &&
    draftCurrency.trim().length === 3;

  if (!readable) {
    return (
      <PageHeader
        title="跨法人调拨"
        description="没有 warehouse.inventory.read 权限，看不到跨法人调拨单。"
      />
    );
  }

  const summary = page?.summary;
  const rows = page?.rows ?? [];

  return (
    <div className="space-y-6">
      <PageHeader
        title="跨法人调拨"
        description="法人之间的库存转移 = 货权转移 + 内部结算。需要定价、双方确认，不能当作普通同仓调拨。"
        actions={
          <div className="flex gap-2">
            <Button variant="outline" onClick={() => void load()} disabled={loading}>
              <RefreshCw className="mr-2 h-4 w-4" />
              刷新
            </Button>
            {writable && (
              <Button onClick={() => setCreateOpen(true)}>
                <Plus className="mr-2 h-4 w-4" />
                新建跨法人调拨
              </Button>
            )}
          </div>
        }
      />

      {error && (
        <div className="rounded-md border border-destructive/40 bg-destructive/10 p-3 text-sm">
          {error}
        </div>
      )}
      {actionError && (
        <div className="rounded-md border border-destructive/40 bg-destructive/10 p-3 text-sm">
          {actionError}
        </div>
      )}

      <div className="grid gap-4 md:grid-cols-2 xl:grid-cols-4">
        <StatCard
          label="跨法人调拨单"
          value={String(summary?.transferCount ?? 0)}
          comparisonLabel="货权转移 + 内部结算"
        />
        <StatCard
          label="待对方确认"
          value={String(summary?.awaitingTargetCount ?? 0)}
          comparisonLabel="转出方已确认、转入方还没签"
        />
        <StatCard
          label="在途"
          value={fmt(summary?.inTransitQuantity)}
          comparisonLabel="货权尚未转到转入方"
        />
        <StatCard
          label="凭据已开具"
          value={String(summary?.settlementIssuedCount ?? 0)}
          comparisonLabel="待 12 财务按 source_system + reference_id 取数"
        />
      </div>

      {/* 金额按币种分行：跨币种直接相加是错的。 */}
      {page && page.currencyAmounts.length > 0 && (
        <SectionCard title="金额汇总（按币种）">
          <div className="flex flex-wrap gap-4">
            {page.currencyAmounts.map((c) => (
              <div key={c.currencyCode} className="rounded-md border px-3 py-2">
                <div className="text-xs text-muted-foreground">{c.currencyCode}</div>
                <div className="text-lg font-semibold">{money(c.amount, c.currencyCode)}</div>
                <div className="text-xs text-muted-foreground">{c.count} 张单</div>
              </div>
            ))}
          </div>
          <p className="mt-3 text-xs text-muted-foreground">
            整单单币种，但列表跨单，因此金额按币种分行汇总 —— 不同币种直接相加没有意义。
            跨币种换算与汇兑损益属 12 财务职责，本模块不存汇率、也不换算。
          </p>
        </SectionCard>
      )}

      <SectionCard title="跨法人调拨单">
        <div className="mb-4 flex flex-wrap gap-2">
          <input
            className="rounded-md border px-3 py-1.5 text-sm"
            placeholder="单号 / 原因 / SKU"
            value={keyword}
            onChange={(e) => {
              setKeyword(e.target.value);
              setPageNumber(0);
            }}
          />
          <select
            className="rounded-md border px-3 py-1.5 text-sm"
            value={fromEntity}
            onChange={(e) => {
              setFromEntity(e.target.value);
              setPageNumber(0);
            }}
          >
            <option value="">全部转出法人</option>
            {page?.legalEntityOptions.map((o) => (
              <option key={o.id} value={o.id}>
                {o.code} {o.name}
              </option>
            ))}
          </select>
          <select
            className="rounded-md border px-3 py-1.5 text-sm"
            value={toEntity}
            onChange={(e) => {
              setToEntity(e.target.value);
              setPageNumber(0);
            }}
          >
            <option value="">全部转入法人</option>
            {page?.legalEntityOptions.map((o) => (
              <option key={o.id} value={o.id}>
                {o.code} {o.name}
              </option>
            ))}
          </select>
          <select
            className="rounded-md border px-3 py-1.5 text-sm"
            value={status}
            onChange={(e) => {
              setStatus(e.target.value);
              setPageNumber(0);
            }}
          >
            <option value="">全部状态</option>
            {page?.statusOptions.map((o) => (
              <option key={o.value} value={o.value}>
                {o.label}（{o.transferCount}）
              </option>
            ))}
          </select>
          <select
            className="rounded-md border px-3 py-1.5 text-sm"
            value={sort}
            onChange={(e) => setSort(e.target.value)}
          >
            {crossEntitySortOptions.map((o) => (
              <option key={o.value} value={o.value}>
                {o.label}
              </option>
            ))}
          </select>
        </div>

        {loading ? (
          <p className="text-sm text-muted-foreground">加载中…</p>
        ) : rows.length === 0 ? (
          <EmptyState
            title="还没有跨法人调拨单"
            description="跨法人调拨用于法人之间的货权转移：需要转移定价、双方确认，并生成内部结算凭据。"
          />
        ) : (
          <div className="space-y-2">
            {rows.map((row) => {
              const actions = crossEntityActions(row.status);
              return (
                <div key={row.id} className="rounded-md border">
                  <button
                    type="button"
                    className="flex w-full flex-col gap-2 p-3 text-left"
                    onClick={() => void openDetail(row.id)}
                  >
                    <div className="flex flex-wrap items-center gap-2">
                      <span className="font-mono text-sm">{row.transferCode}</span>
                      <span className="rounded bg-primary/10 px-2 py-0.5 text-xs text-primary">
                        {crossEntityStatusLabels[row.status] ?? row.status}
                      </span>
                      {/* 两个法人是本模块的第一视觉要素。 */}
                      <span className="flex items-center gap-1 text-sm font-medium">
                        <Building2 className="h-3.5 w-3.5" />
                        {partiesText(row)}
                      </span>
                      <span className="text-xs text-muted-foreground">{routeText(row)}</span>
                    </div>
                    <div className="flex flex-wrap items-center gap-4 text-sm">
                      <span className="flex items-center gap-1 font-medium">
                        <Scale className="h-3.5 w-3.5" />
                        {money(row.totalAmount, row.currencyCode)}
                      </span>
                      <span className="text-xs text-muted-foreground">
                        定价口径：{crossEntityValuationLabels[row.valuationBasis] ?? row.valuationBasis}
                      </span>
                      <span className="text-xs text-muted-foreground">
                        调拨 {fmt(row.totalQuantity)} · 实收 {fmt(row.receivedQuantity)} · 在途{' '}
                        {fmt(row.inTransitQuantity)} · 差异 {fmt(row.discrepancyQuantity)}
                      </span>
                      {row.settlementStatus && (
                        <span className="text-xs text-muted-foreground">
                          凭据 {row.settlementCode}：{' '}
                          {crossEntitySettlementLabels[row.settlementStatus] ?? row.settlementStatus}
                        </span>
                      )}
                    </div>
                  </button>
                  {writable && actions.length > 0 && (
                    <div className="flex flex-wrap gap-2 border-t px-3 py-2">
                      {actions.includes('source-confirm') && (
                        <Button
                          size="sm"
                          variant="outline"
                          disabled={busy === 'source'}
                          onClick={() => {
                            setConfirmOpen({ id: row.id, side: 'SOURCE', version: row.version });
                            setConfirmNotes('');
                          }}
                        >
                          <BadgeCheck className="mr-2 h-4 w-4" />
                          转出方确认
                        </Button>
                      )}
                      {actions.includes('target-confirm') && (
                        <Button
                          size="sm"
                          variant="outline"
                          disabled={busy === 'target'}
                          onClick={() => {
                            setConfirmOpen({ id: row.id, side: 'TARGET', version: row.version });
                            setConfirmNotes('');
                          }}
                        >
                          <BadgeCheck className="mr-2 h-4 w-4" />
                          转入方确认
                        </Button>
                      )}
                      {actions.includes('ship') && (
                        <Button
                          size="sm"
                          disabled={busy === 'ship'}
                          onClick={() => {
                            setExpanded(row.id);
                            setShipOpen(true);
                          }}
                        >
                          <ArrowRightLeft className="mr-2 h-4 w-4" />
                          发出（整单）
                        </Button>
                      )}
                      {actions.includes('receive') && (
                        <Button size="sm" variant="outline" onClick={() => setReceiveOpen(true)}>
                          实收（货权转入转入方）
                        </Button>
                      )}
                      {actions.includes('close') && (
                        <Button
                          size="sm"
                          variant="outline"
                          disabled={busy === 'close'}
                          onClick={() =>
                            void run('close', () =>
                              closeCrossEntityTransfer(row.id, {
                                expectedVersion: row.version,
                                reason: '运输破损',
                                notes: '',
                              }),
                            )
                          }
                        >
                          结案（短少记差异）
                        </Button>
                      )}
                      {actions.includes('cancel') && (
                        <Button size="sm" variant="ghost" onClick={() => setCancelOpen(true)}>
                          作废
                        </Button>
                      )}
                    </div>
                  )}
                  {expanded === row.id && (
                    <div className="border-t p-3">
                      {detailLoading ? (
                        <p className="text-sm text-muted-foreground">加载详情…</p>
                      ) : detailError ? (
                        <p className="text-sm text-destructive">{detailError}</p>
                      ) : detail ? (
                        <DetailBody
                          detail={detail}
                          onConfirm={(side, version) => {
                            setConfirmOpen({ id: row.id, side, version });
                            setConfirmNotes('');
                          }}
                          receiveOpen={receiveOpen}
                          setReceiveOpen={setReceiveOpen}
                          receiveAmounts={receiveAmounts}
                          setReceiveAmounts={setReceiveAmounts}
                          receiveLocation={receiveLocation}
                          setReceiveLocation={setReceiveLocation}
                          receiveNotes={receiveNotes}
                          setReceiveNotes={setReceiveNotes}
                          shipOpen={shipOpen}
                          setShipOpen={setShipOpen}
                          cancelOpen={cancelOpen}
                          setCancelOpen={setCancelOpen}
                          cancelReason={cancelReason}
                          setCancelReason={setCancelReason}
                          busy={busy}
                          run={run}
                          writable={writable}
                        />
                      ) : null}
                    </div>
                  )}
                </div>
              );
            })}
          </div>
        )}

        {page && page.total > page.size && (
          <div className="mt-3 flex items-center justify-end gap-2 text-sm">
            <Button
              size="sm"
              variant="outline"
              disabled={pageNumber === 0}
              onClick={() => setPageNumber(Math.max(0, pageNumber - 1))}
            >
              上一页
            </Button>
            <span className="text-muted-foreground">
              第 {pageNumber + 1} 页 / 共 {Math.ceil(page.total / page.size)} 页（{page.total} 张）
            </span>
            <Button
              size="sm"
              variant="outline"
              disabled={(pageNumber + 1) * page.size >= page.total}
              onClick={() => setPageNumber(pageNumber + 1)}
            >
              下一页
            </Button>
          </div>
        )}
      </SectionCard>

      {/* ---------------------------------------------------------- 建单 */}
      <Modal open={createOpen} onOpenChange={setCreateOpen}>
        <ModalContent className="max-w-2xl">
          <ModalHeader>
            <ModalTitle>新建跨法人调拨</ModalTitle>
            <ModalDescription>
              货权从转出法人转到转入法人：必须定价，并分别由两个不同的人确认两个方向。
            </ModalDescription>
          </ModalHeader>
          <div className="space-y-3">
            <div className="grid grid-cols-2 gap-3">
              <label className="text-sm">
                转出仓
                <select
                  className="mt-1 w-full rounded-md border px-2 py-1.5"
                  value={draftFromWarehouse}
                  onChange={(e) => setDraftFromWarehouse(e.target.value)}
                >
                  <option value="">请选择</option>
                  {warehouses.map((w) => (
                    <option key={w.id} value={w.id}>
                      {w.code} {w.name}
                    </option>
                  ))}
                </select>
              </label>
              <label className="text-sm">
                转入仓
                <select
                  className="mt-1 w-full rounded-md border px-2 py-1.5"
                  value={draftToWarehouse}
                  onChange={(e) => setDraftToWarehouse(e.target.value)}
                >
                  <option value="">请选择</option>
                  {warehouses.map((w) => (
                    <option key={w.id} value={w.id}>
                      {w.code} {w.name}
                    </option>
                  ))}
                </select>
              </label>
              <label className="text-sm">
                转出法人
                <select
                  className="mt-1 w-full rounded-md border px-2 py-1.5"
                  value={draftFromEntity}
                  onChange={(e) => setDraftFromEntity(e.target.value)}
                >
                  <option value="">请选择</option>
                  {entities.map((e) => (
                    <option key={e.id} value={e.id}>
                      {e.code} {e.name}
                    </option>
                  ))}
                </select>
              </label>
              <label className="text-sm">
                转入法人
                <select
                  className="mt-1 w-full rounded-md border px-2 py-1.5"
                  value={draftToEntity}
                  onChange={(e) => setDraftToEntity(e.target.value)}
                >
                  <option value="">请选择</option>
                  {entities.map((e) => (
                    <option key={e.id} value={e.id}>
                      {e.code} {e.name}
                    </option>
                  ))}
                </select>
              </label>
              <label className="text-sm">
                转移定价口径
                <select
                  className="mt-1 w-full rounded-md border px-2 py-1.5"
                  value={draftBasis}
                  onChange={(e) => setDraftBasis(e.target.value)}
                >
                  {Object.entries(crossEntityValuationLabels).map(([v, l]) => (
                    <option key={v} value={v}>
                      {l}
                    </option>
                  ))}
                </select>
              </label>
              <label className="text-sm">
                币种（ISO 4217 三字母）
                <input
                  className="mt-1 w-full rounded-md border px-2 py-1.5 uppercase"
                  maxLength={3}
                  value={draftCurrency}
                  onChange={(e) => setDraftCurrency(e.target.value.toUpperCase())}
                />
              </label>
            </div>
            <div className="grid grid-cols-3 gap-3">
              <label className="text-sm">
                SKU
                <select
                  className="mt-1 w-full rounded-md border px-2 py-1.5"
                  value={draftSku}
                  onChange={(e) => setDraftSku(e.target.value)}
                >
                  <option value="">请选择</option>
                  {skus.map((s) => (
                    <option key={s.id} value={s.id}>
                      {s.code} {s.name}
                    </option>
                  ))}
                </select>
              </label>
              <label className="text-sm">
                质量状态
                <select
                  className="mt-1 w-full rounded-md border px-2 py-1.5"
                  value={draftQuality}
                  onChange={(e) => setDraftQuality(e.target.value)}
                >
                  {qualities.map((q) => (
                    <option key={q.id} value={q.id}>
                      {q.name}
                    </option>
                  ))}
                </select>
              </label>
              <label className="text-sm">
                数量
                <input
                  className="mt-1 w-full rounded-md border px-2 py-1.5"
                  inputMode="decimal"
                  value={draftQuantity}
                  onChange={(e) => setDraftQuantity(e.target.value)}
                />
              </label>
            </div>
            <label className="block text-sm">
              转移单价（0 允许，但会与定价口径一起显示，避免被当成「没填」）
              <input
                className="mt-1 w-full rounded-md border px-2 py-1.5"
                inputMode="decimal"
                value={draftUnitPrice}
                onChange={(e) => setDraftUnitPrice(e.target.value)}
              />
            </label>
            <label className="block text-sm">
              调拨原因
              <input
                className="mt-1 w-full rounded-md border px-2 py-1.5"
                maxLength={200}
                value={draftReason}
                onChange={(e) => setDraftReason(e.target.value)}
              />
            </label>
            <div className="rounded-md border bg-muted/40 p-2 text-sm">
              金额 = 数量 × 单价 ={' '}
              <span className="font-semibold">{money(draftAmount, draftCurrency)}</span>
              <span className="ml-2 text-xs text-muted-foreground">
                由后端生成列算出并四舍五入到 4 位小数，前端这里只是预览
              </span>
            </div>
            {draftFromEntity && draftToEntity && draftFromEntity === draftToEntity && (
              <p className="flex items-start gap-1 text-sm text-destructive">
                <TriangleAlert className="mt-0.5 h-4 w-4 shrink-0" />
                转出与转入不能是同一个法人 —— 同一法人之间的转移是普通调拨，不需要定价与内部结算。
              </p>
            )}
          </div>
          <ModalFooter>
            <Button variant="outline" onClick={() => setCreateOpen(false)}>
              取消
            </Button>
            <Button
              disabled={!draftReady || busy === 'create'}
              onClick={() =>
                void run('create', async () => {
                  const saved = await createCrossEntityTransfer({
                    fromWarehouseId: draftFromWarehouse,
                    toWarehouseId: draftToWarehouse,
                    fromLegalEntityId: draftFromEntity,
                    toLegalEntityId: draftToEntity,
                    valuationBasis: draftBasis,
                    currencyCode: draftCurrency,
                    expectedShipAt: '',
                    expectedArriveAt: '',
                    reason: draftReason,
                    notes: '',
                    lines: [
                      {
                        skuId: draftSku,
                        qualityStatusId: draftQuality,
                        quantity: Number(draftQuantity),
                        unitPrice: Number(draftUnitPrice),
                      },
                    ],
                  });
                  setCreateOpen(false);
                  return saved;
                })
              }
            >
              建单（草稿）
            </Button>
          </ModalFooter>
        </ModalContent>
      </Modal>

      {/* ---------------------------------------------------------- 双方确认 */}
      <Modal
        open={confirmOpen !== null}
        onOpenChange={(open) => !open && setConfirmOpen(null)}
      >
        <ModalContent>
          <ModalHeader>
            <ModalTitle>
              {confirmOpen?.side === 'SOURCE' ? '转出方确认' : '转入方确认'}
            </ModalTitle>
            <ModalDescription>
              {confirmOpen?.side === 'SOURCE'
                ? '确认转出价格与明细。确认后单据进入「待转入方确认」，由另一个人签署。'
                : '确认按此价接收这批货。两个方向必须由两个不同的人签署，否则后端会拒。'}
            </ModalDescription>
          </ModalHeader>
          <label className="block text-sm">
            确认备注
            <input
              className="mt-1 w-full rounded-md border px-2 py-1.5"
              value={confirmNotes}
              onChange={(e) => setConfirmNotes(e.target.value)}
            />
          </label>
          <ModalFooter>
            <Button variant="outline" onClick={() => setConfirmOpen(null)}>
              取消
            </Button>
            <Button
              disabled={busy === 'confirm'}
              onClick={() => {
                const target = confirmOpen;
                if (!target) return;
                void run('confirm', async () => {
                  const saved =
                    target.side === 'SOURCE'
                      ? await confirmSourceSide(target.id, target.version, confirmNotes)
                      : await confirmTargetSide(target.id, target.version, confirmNotes);
                  setConfirmOpen(null);
                  return saved;
                });
              }}
            >
              确认
            </Button>
          </ModalFooter>
        </ModalContent>
      </Modal>
    </div>
  );
}

/** 详情：明细定价 + 双方确认留痕 + 结算凭据 + 三条口径说明。 */
function DetailBody({
  detail,
  onConfirm,
  receiveOpen,
  setReceiveOpen,
  receiveAmounts,
  setReceiveAmounts,
  receiveLocation,
  setReceiveLocation,
  receiveNotes,
  setReceiveNotes,
  shipOpen,
  setShipOpen,
  cancelOpen,
  setCancelOpen,
  cancelReason,
  setCancelReason,
  busy,
  run,
  writable,
}: {
  detail: LegalEntityTransferDetail;
  onConfirm: (side: 'SOURCE' | 'TARGET', version: number) => void;
  receiveOpen: boolean;
  setReceiveOpen: (v: boolean) => void;
  receiveAmounts: Record<string, string>;
  setReceiveAmounts: (v: Record<string, string>) => void;
  receiveLocation: string;
  setReceiveLocation: (v: string) => void;
  receiveNotes: string;
  setReceiveNotes: (v: string) => void;
  shipOpen: boolean;
  setShipOpen: (v: boolean) => void;
  cancelOpen: boolean;
  setCancelOpen: (v: boolean) => void;
  cancelReason: string;
  setCancelReason: (v: string) => void;
  busy: string;
  run: (key: string, work: () => Promise<LegalEntityTransferSaved>) => Promise<void>;
  writable: boolean;
}) {
  const order = detail.transfer;
  const sum = conservationText({
    totalQuantity: order.totalQuantity,
    receivedQuantity: order.receivedQuantity,
    discrepancyQuantity: order.discrepancyQuantity,
    inTransitQuantity: order.inTransitQuantity,
  });

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-center gap-3 text-sm">
        <span className="rounded bg-primary/10 px-2 py-0.5 text-xs text-primary">
          {crossEntityStatusLabels[order.status] ?? order.status}
        </span>
        <span className="font-medium">{partiesText(order)}</span>
        <span className="text-muted-foreground">{routeText(order)}</span>
        <span className="font-semibold">{money(order.totalAmount, order.currencyCode)}</span>
      </div>

      <div
        className={`rounded-md border p-2 text-xs ${sum.ok ? 'text-muted-foreground' : 'border-destructive/40 text-destructive'}`}
      >
        {sum.text}
      </div>

      <div>
        <div className="mb-1 text-sm font-medium">明细与转移定价</div>
        <table className="w-full text-sm">
          <thead>
            <tr className="border-b text-left text-xs text-muted-foreground">
              <th className="py-1">SKU</th>
              <th className="py-1">质量状态</th>
              <th className="py-1 text-right">数量</th>
              <th className="py-1 text-right">单价</th>
              <th className="py-1 text-right">金额</th>
              <th className="py-1 text-right">实收</th>
              <th className="py-1 text-right">在途</th>
              <th className="py-1 text-right">差异</th>
            </tr>
          </thead>
          <tbody>
            {detail.lines.map((line) => (
              <tr key={line.id} className="border-b">
                <td className="py-1">
                  {line.skuCode} {line.skuName}
                </td>
                <td className="py-1">{line.qualityStatusName}</td>
                <td className="py-1 text-right">{fmt(line.quantity)}</td>
                <td className="py-1 text-right">{fmt(line.unitPrice)}</td>
                <td className="py-1 text-right">{money(line.amount, order.currencyCode)}</td>
                <td className="py-1 text-right">{fmt(line.receivedQuantity)}</td>
                <td className="py-1 text-right">{fmt(line.inTransitQuantity)}</td>
                <td className="py-1 text-right">{fmt(line.discrepancyQuantity)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      <div>
        <div className="mb-1 text-sm font-medium">双方确认留痕</div>
        {detail.approvals.length === 0 ? (
          <p className="text-sm text-muted-foreground">尚未有任何一方确认。</p>
        ) : (
          <ul className="space-y-1 text-sm">
            {detail.approvals.map((a) => (
              <li key={a.side} className="flex flex-wrap items-center gap-2">
                <BadgeCheck className="h-4 w-4" />
                <span className="font-medium">{a.side === 'SOURCE' ? '转出方' : '转入方'}</span>
                <span className="text-muted-foreground">{moment(a.approvedAt)}</span>
                {a.notes && <span className="text-muted-foreground">「{a.notes}」</span>}
              </li>
            ))}
          </ul>
        )}
        {writable && order.status === 'DRAFT' && (
          <Button size="sm" variant="outline" className="mt-2" onClick={() => onConfirm('SOURCE', order.version)}>
            转出方确认
          </Button>
        )}
        {writable && order.status === 'PENDING_TARGET_CONFIRM' && (
          <Button size="sm" variant="outline" className="mt-2" onClick={() => onConfirm('TARGET', order.version)}>
            转入方确认
          </Button>
        )}
      </div>

      <div>
        <div className="mb-1 text-sm font-medium">内部结算凭据（12 财务取数入口）</div>
        {detail.settlement ? (
          <div className="space-y-1 rounded-md border p-2 text-sm">
            <div>
              凭据号 <span className="font-mono">{detail.settlement.settlementCode}</span> ·{' '}
              {crossEntitySettlementLabels[detail.settlement.status] ?? detail.settlement.status}
            </div>
            <div>
              金额{' '}
              <span className="font-semibold">
                {money(detail.settlement.amount, detail.settlement.currencyCode)}
              </span>
              （不含税；税额与科目属 12 财务自有权威）
            </div>
            <div className="font-mono text-xs text-muted-foreground">
              定位键 source_system={detail.settlement.sourceSystem} · reference_id=
              {detail.settlement.referenceId}
            </div>
            <div className="text-xs text-muted-foreground">
              开具于 {moment(detail.settlement.issuedAt)}
              {detail.settlement.acknowledgedAt &&
                ` · 转入方确认于 ${moment(detail.settlement.acknowledgedAt)}`}
            </div>
          </div>
        ) : (
          <p className="text-sm text-muted-foreground">
            尚未开具凭据 —— 凭据在**发出**时开具（那时转出方已交付、转入方已确认定价）。
          </p>
        )}
      </div>

      {/* 三条口径说明必须显示：不写清楚就会被误读成「货已经归对方了」/「已经接好权限了」。 */}
      <div className="space-y-1 rounded-md border bg-muted/40 p-2 text-xs text-muted-foreground">
        <p>定价口径：{detail.pricingNote}</p>
        <p>货权口径：{detail.ownershipNote}</p>
        <p>授权口径：{detail.authorizationNote}</p>
      </div>

      {writable && order.status === 'CONFIRMED' && (
        <div className="flex flex-wrap gap-2">
          <Button size="sm" onClick={() => setShipOpen(true)}>
            发出（整单）
          </Button>
          <Button size="sm" variant="ghost" onClick={() => setCancelOpen(true)}>
            作废
          </Button>
        </div>
      )}

      <Modal open={shipOpen} onOpenChange={setShipOpen}>
        <ModalContent>
          <ModalHeader>
            <ModalTitle>发出（整单）</ModalTitle>
            <ModalDescription>
              扣转出法人的库存、货进入在途，并开具内部结算凭据。货权在**实收**时才转到转入方。
            </ModalDescription>
          </ModalHeader>
          <p className="text-sm text-muted-foreground">
            共 {fmt(order.totalQuantity)} 件，金额 {money(order.totalAmount, order.currencyCode)}。
            跨法人调拨整单发运：转移定价是整单口径，部分发运会让「一张凭据对应哪几行货」失去唯一答案。
          </p>
          <ModalFooter>
            <Button variant="outline" onClick={() => setShipOpen(false)}>
              取消
            </Button>
            <Button
              disabled={busy === 'ship'}
              onClick={() =>
                void run('ship', async () => {
                  const saved = await shipCrossEntityTransfer(order.id, {
                    expectedVersion: order.version,
                    shippedAt: '',
                    notes: '',
                  });
                  setShipOpen(false);
                  return saved;
                })
              }
            >
              确认发出
            </Button>
          </ModalFooter>
        </ModalContent>
      </Modal>

      <Modal open={receiveOpen} onOpenChange={setReceiveOpen}>
        <ModalContent>
          <ModalHeader>
            <ModalTitle>实收（货权转入转入方）</ModalTitle>
            <ModalDescription>
              货进入**转入法人**的库存余额行 —— 这就是货权转移本身。落库库位必填。
            </ModalDescription>
          </ModalHeader>
          <label className="block text-sm">
            转入仓库位 id
            <input
              className="mt-1 w-full rounded-md border px-2 py-1.5"
              value={receiveLocation}
              onChange={(e) => setReceiveLocation(e.target.value)}
            />
          </label>
          <div className="mt-3 space-y-2">
            {detail.lines
              .filter((l) => receivable(l) > 0)
              .map((line) => (
                <label key={line.id} className="flex items-center justify-between gap-2 text-sm">
                  <span>
                    {line.skuCode} · 可收 {fmt(receivable(line))}（应收{' '}
                    {fmt(remainingToReceive(line))}）
                  </span>
                  <input
                    className="w-28 rounded-md border px-2 py-1.5"
                    inputMode="decimal"
                    placeholder="本次实收"
                    value={receiveAmounts[line.id] ?? ''}
                    onChange={(e) =>
                      setReceiveAmounts({ ...receiveAmounts, [line.id]: e.target.value })
                    }
                  />
                </label>
              ))}
          </div>
          <label className="mt-3 block text-sm">
            备注
            <input
              className="mt-1 w-full rounded-md border px-2 py-1.5"
              value={receiveNotes}
              onChange={(e) => setReceiveNotes(e.target.value)}
            />
          </label>
          <ModalFooter>
            <Button variant="outline" onClick={() => setReceiveOpen(false)}>
              取消
            </Button>
            <Button
              disabled={
                busy === 'receive' ||
                !receiveLocation ||
                detail.lines.filter((l) => receivable(l) > 0).length === 0
              }
              onClick={() =>
                void run('receive', async () => {
                  const saved = await receiveCrossEntityTransfer(order.id, {
                    expectedVersion: order.version,
                    receivedAt: '',
                    locationId: receiveLocation,
                    lotId: '',
                    notes: receiveNotes,
                    lines: detail.lines
                      .filter((l) => Number(receiveAmounts[l.id] ?? 0) > 0)
                      .map((l) => ({
                        lineId: l.id,
                        quantity: Number(receiveAmounts[l.id] ?? 0),
                      })),
                  });
                  setReceiveOpen(false);
                  setReceiveAmounts({});
                  return saved;
                })
              }
            >
              确认实收
            </Button>
          </ModalFooter>
        </ModalContent>
      </Modal>

      <Modal open={cancelOpen} onOpenChange={setCancelOpen}>
        <ModalContent>
          <ModalHeader>
            <ModalTitle>作废</ModalTitle>
            <ModalDescription>仅发运之前可用。已发运的单只能结案，短少如实记为差异。</ModalDescription>
          </ModalHeader>
          <label className="block text-sm">
            作废原因（必填）
            <input
              className="mt-1 w-full rounded-md border px-2 py-1.5"
              value={cancelReason}
              onChange={(e) => setCancelReason(e.target.value)}
            />
          </label>
          <ModalFooter>
            <Button variant="outline" onClick={() => setCancelOpen(false)}>
              返回
            </Button>
            <Button
              variant="destructive"
              disabled={busy === 'cancel' || !cancelReason.trim()}
              onClick={() =>
                void run('cancel', async () => {
                  const saved = await cancelCrossEntityTransfer(order.id, {
                    expectedVersion: order.version,
                    reason: cancelReason,
                  });
                  setCancelOpen(false);
                  return saved;
                })
              }
            >
              作废
            </Button>
          </ModalFooter>
        </ModalContent>
      </Modal>
    </div>
  );
}
