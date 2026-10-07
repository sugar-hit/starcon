'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import { AlertTriangle, PackageCheck, Plus, RefreshCw, Truck } from 'lucide-react';
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
  Notice,
  PageHeader,
  SectionCard,
  Segmented,
  StatCard,
} from '@starcon/ui';
import type { InTransitDetail, InTransitRow } from '../../../../../packages/api-contracts/src/catalog';
import type { LotRef } from '../../../../../packages/api-contracts/src/catalog';
import {
  arriveBlockers,
  arriveInTransitLeg,
  arriveNeedsLocation,
  buildRegisterPayload,
  conservationText,
  discrepancyBlockers,
  etaText,
  fetchInTransitAlerts,
  fetchInTransitLeg,
  fetchInTransitLegs,
  fetchInTransitNetting,
  fmt,
  inTransitActions,
  inTransitEventLabels,
  inTransitKindLabels,
  inTransitNettingSortOptions,
  inTransitOverdueLabels,
  inTransitReasonLabels,
  inTransitSortOptions,
  inTransitStatusLabels,
  moment,
  needsAttention,
  overdueHoursText,
  overdueSeverity,
  recordInTransitDiscrepancy,
  registerBlockers,
  registerInTransit,
  registrableTransitKinds,
  routeText,
  transferRefText,
  type InTransitParams,
  type RegisterPayload,
} from './inventory-in-transit-model';
import {
  errorText,
  fetchLegalEntities,
  fetchLocations,
  fetchLots,
  fetchQualityStatuses,
  fetchSkus,
  fetchWarehouses,
} from './inventory-model';
import { useIdentity } from '../iam/identity-shell';

type Ref = { id: string; code: string; name: string };

/**
 * 7.5.1.2 在途库存工作区。
 *
 * <p>蓝图：「跟踪所有在途库存（调拨在途、头程在途、采购在途、FBA 发货在途），含起止仓、批次、
 * 数量、预计到达」，输入「调拨发出与头程发运事件；预计到达时间；收货事件」，
 * 输出「在途库存清单 + 到达预警（逾期未达）」。
 *
 * <p>界面纪律：
 *
 * <ul>
 *   <li>**四类在途同一个口径**。首屏是全量汇总（不受分页影响），不是四张各说各话的表 ——
 *       重复补货的根因就是「在途漏算」，所以漏掉任何一类都会立刻变成钱。
 *   <li>**未达量 / 已了结量 / 原发量分三列**。缺口要扣的是未达量；原发量只是「当初发了多少」。
 *   <li>**逾期是算出来的**：ETA 与此刻相减得档位。`NO_ETA` 单列提示，
 *       不把「没登记 ETA」当成准点。
 *   <li>**每行都带归属说明**（`attribution`）。调拨腿的 `settledQuantity` 恒为 0
 *       （7.5.1.1 把到达量拆成独立 RECEIVED 行），这个口径差异逐行返回、逐行显示，
 *       不靠人猜。
 * </ul>
 *
 * <p>**本模块刻意不做的事**（都在界面上写明，不假装有）：
 * 到货与差异登记是直接落库的业务事件，**不经审批流、不进常驻调度**；
 * 「补货净额」只交数、不给补货建议（需求预测与下单属 6.2.1.1）；
 * 上游发运事件靠 `sourceSystem` + `sourceReference` 指回原文，本模块不复制对方系统。
 */

type Tab = 'legs' | 'alerts' | 'netting';

/** 逾期档位 → Badge 色调。严重度越高越显眼；未登记 ETA 用中性色（算不出，不是「没事」）。 */
function overdueTone(level: string): 'neutral' | 'success' | 'warning' | 'danger' {
  const severity = overdueSeverity(level);
  if (severity >= 5) return 'danger';
  if (severity >= 3) return 'warning';
  if (severity >= 1) return 'success';
  return 'neutral';
}

export function InventoryInTransitWorkspace() {
  const { session } = useIdentity();
  const readable = session.permissions.includes('warehouse.inventory.read');
  const writable = session.permissions.includes('warehouse.inventory.update');

  const [tab, setTab] = useState<Tab>('legs');
  const [page, setPage] = useState<Awaited<ReturnType<typeof fetchInTransitLegs>> | null>(null);
  const [alerts, setAlerts] = useState<Awaited<ReturnType<typeof fetchInTransitAlerts>> | null>(null);
  const [netting, setNetting] = useState<Awaited<ReturnType<typeof fetchInTransitNetting>> | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [actionError, setActionError] = useState('');
  const [busy, setBusy] = useState('');

  const [warehouses, setWarehouses] = useState<Ref[]>([]);
  const [skus, setSkus] = useState<Ref[]>([]);
  const [qualities, setQualities] = useState<Ref[]>([]);
  const [entities, setEntities] = useState<Ref[]>([]);

  const [keyword, setKeyword] = useState('');
  const [transitKind, setTransitKind] = useState('');
  const [fromWarehouseId, setFromWarehouseId] = useState('');
  const [toWarehouseId, setToWarehouseId] = useState('');
  const [skuId, setSkuId] = useState('');
  const [overdueLevel, setOverdueLevel] = useState('');
  const [overdueOnly, setOverdueOnly] = useState(false);
  const [sort, setSort] = useState('EXPECTED_ARRIVE');
  const [pageNumber, setPageNumber] = useState(0);
  const [nettingSort, setNettingSort] = useState('QUANTITY');

  const [expanded, setExpanded] = useState<string | null>(null);
  const [detail, setDetail] = useState<InTransitDetail | null>(null);
  const [detailError, setDetailError] = useState('');

  const [registerOpen, setRegisterOpen] = useState(false);
  const [arriveOpen, setArriveOpen] = useState(false);
  const [discrepancyOpen, setDiscrepancyOpen] = useState(false);
  const [targetLegId, setTargetLegId] = useState('');
  const [arriveAmount, setArriveAmount] = useState('');
  const [arriveLocation, setArriveLocation] = useState('');
  const [arriveNotes, setArriveNotes] = useState('');
  const [locations, setLocations] = useState<Ref[]>([]);
  const [discrepancyAmount, setDiscrepancyAmount] = useState('');
  const [discrepancyReason, setDiscrepancyReason] = useState('LOST');
  const [discrepancyNotes, setDiscrepancyNotes] = useState('');
  const [draft, setDraft] = useState<Partial<RegisterPayload>>({});
  const [lotOptions, setLotOptions] = useState<Pick<LotRef, 'id' | 'lotCode'>[]>([]);

  const load = useCallback(async () => {
    if (!readable) {
      setLoading(false);
      return;
    }
    setLoading(true);
    setError('');
    const params: InTransitParams = { sort, page: pageNumber, size: 50 };
    if (keyword.trim()) params.keyword = keyword.trim();
    if (transitKind) params.transitKind = transitKind;
    if (fromWarehouseId) params.fromWarehouseId = fromWarehouseId;
    if (toWarehouseId) params.toWarehouseId = toWarehouseId;
    if (skuId) params.skuId = skuId;
    if (overdueLevel) params.overdueLevel = [overdueLevel];
    if (overdueOnly) params.overdueOnly = true;
    try {
      const [list, alertPage, nettingPage] = await Promise.all([
        fetchInTransitLegs(params),
        fetchInTransitAlerts(),
        fetchInTransitNetting({ sort: nettingSort, size: 50 }),
      ]);
      setPage(list);
      setAlerts(alertPage);
      setNetting(nettingPage);
    } catch (e) {
      setError(errorText(e));
    } finally {
      setLoading(false);
    }
  }, [readable, sort, pageNumber, keyword, transitKind, fromWarehouseId, toWarehouseId, skuId, overdueLevel, overdueOnly, nettingSort]);

  useEffect(() => {
    if (!readable) return;
    let alive = true;
    Promise.all([fetchWarehouses(), fetchSkus(), fetchQualityStatuses(), fetchLegalEntities()])
      .then(([w, s, q, e]) => {
        if (!alive) return;
        setWarehouses(w);
        setSkus(s);
        setQualities(q);
        setEntities(e);
      })
      .catch(() => {
        // 参照数据拿不到不阻塞清单：筛选下拉会空着，但主列表仍可用。
      });
    return () => {
      alive = false;
    };
  }, [readable]);

  useEffect(() => {
    void load();
  }, [load]);

  const summary = page?.summary;

  const openDetail = useCallback(async (legId: string) => {
    if (expanded === legId) {
      setExpanded(null);
      setDetail(null);
      setDetailError('');
      return;
    }
    setExpanded(legId);
    setDetail(null);
    setDetailError('');
    try {
      setDetail(await fetchInTransitLeg(legId));
    } catch (e) {
      setDetailError(errorText(e));
    }
  }, [expanded]);

  const openArrive = useCallback(
    async (legId: string, needsLocation: boolean, destinationWarehouseId: string | null) => {
      setTargetLegId(legId);
      setArriveAmount('');
      setArriveLocation('');
      setArriveNotes('');
      setActionError('');
      setArriveOpen(true);
      if (needsLocation && destinationWarehouseId) {
        try {
          setLocations(await fetchLocations(destinationWarehouseId));
        } catch {
          setLocations([]);
        }
      } else {
        setLocations([]);
      }
    },
    [],
  );

  const openDiscrepancy = useCallback((legId: string) => {
    setTargetLegId(legId);
    setDiscrepancyAmount('');
    setDiscrepancyReason('LOST');
    setDiscrepancyNotes('');
    setActionError('');
    setDiscrepancyOpen(true);
  }, []);

  const targetRow = useMemo(
    () => page?.rows.find((r) => r.id === targetLegId) ?? null,
    [page, targetLegId],
  );

  const arriveIssues = useMemo(() => {
    if (!targetRow) return [];
    return arriveBlockers({
      version: targetRow.version,
      transitKind: targetRow.transitKind,
      outstandingQuantity: targetRow.outstandingQuantity,
      quantity: Number(arriveAmount) || 0,
      ...(arriveLocation ? { locationId: arriveLocation } : {}),
    });
  }, [targetRow, arriveAmount, arriveLocation]);

  const discrepancyIssues = useMemo(() => {
    if (!targetRow) return [];
    return discrepancyBlockers({
      outstandingQuantity: targetRow.outstandingQuantity,
      quantity: Number(discrepancyAmount) || 0,
      reason: discrepancyReason,
    });
  }, [targetRow, discrepancyAmount, discrepancyReason]);

  const registerIssues = useMemo(
    () =>
      registerBlockers({
        transitKind: draft.transitKind ?? '',
        skuId: draft.skuId ?? '',
        qualityStatusId: draft.qualityStatusId ?? '',
        legalEntityId: draft.legalEntityId ?? '',
        sourceSystem: draft.sourceSystem ?? '',
        sourceReference: draft.sourceReference ?? '',
        quantity: Number(draft.quantity) || 0,
        shippedAt: draft.shippedAt ?? '',
        ...(draft.expectedArriveAt ? { expectedArriveAt: draft.expectedArriveAt } : {}),
        ...(draft.fromWarehouseId ? { fromWarehouseId: draft.fromWarehouseId } : {}),
        ...(draft.fromWarehouseName ? { fromWarehouseName: draft.fromWarehouseName } : {}),
        ...(draft.toWarehouseId ? { toWarehouseId: draft.toWarehouseId } : {}),
        ...(draft.toWarehouseName ? { toWarehouseName: draft.toWarehouseName } : {}),
      }),
    [draft],
  );

  const submitRegister = useCallback(async () => {
    if (registerIssues.length) {
      setActionError(registerIssues.join('；'));
      return;
    }
    setBusy('register');
    setActionError('');
    try {
      // buildRegisterPayload 把可空字段整键省略：发空串会被 Jackson 按 UUID / 时间解析挡成 400。
      const payload = buildRegisterPayload({
        transitKind: draft.transitKind ?? '',
        skuId: draft.skuId ?? '',
        qualityStatusId: draft.qualityStatusId ?? '',
        legalEntityId: draft.legalEntityId ?? '',
        quantity: Number(draft.quantity) || 0,
        shippedAt: draft.shippedAt ?? '',
        sourceSystem: draft.sourceSystem ?? '',
        sourceReference: draft.sourceReference ?? '',
        ...(draft.fromWarehouseId ? { fromWarehouseId: draft.fromWarehouseId } : {}),
        ...(draft.fromWarehouseName ? { fromWarehouseName: draft.fromWarehouseName } : {}),
        ...(draft.toWarehouseId ? { toWarehouseId: draft.toWarehouseId } : {}),
        ...(draft.toWarehouseName ? { toWarehouseName: draft.toWarehouseName } : {}),
        ...(draft.lotId ? { lotId: draft.lotId } : {}),
        ...(draft.expectedArriveAt ? { expectedArriveAt: draft.expectedArriveAt } : {}),
        ...(draft.carrierName ? { carrierName: draft.carrierName } : {}),
        ...(draft.trackingNo ? { trackingNo: draft.trackingNo } : {}),
      });
      await registerInTransit(payload);
      setRegisterOpen(false);
      setDraft({});
      await load();
    } catch (e) {
      setActionError(errorText(e));
    } finally {
      setBusy('');
    }
  }, [draft, registerIssues, load]);

  const submitArrive = useCallback(async () => {
    if (!targetRow) return;
    if (arriveIssues.length) {
      setActionError(arriveIssues.join('；'));
      return;
    }
    setBusy('arrive');
    setActionError('');
    try {
      await arriveInTransitLeg(targetRow.id, {
        // 乐观锁：并发到达 / 结案时必须给当前版本，不给后端直接拒。
        expectedVersion: targetRow.version,
        quantity: Number(arriveAmount) || 0,
        ...(arriveLocation ? { locationId: arriveLocation } : {}),
        ...(arriveNotes.trim() ? { notes: arriveNotes.trim() } : {}),
      });
      setArriveOpen(false);
      await load();
      if (expanded === targetRow.id) setDetail(await fetchInTransitLeg(targetRow.id));
    } catch (e) {
      setActionError(errorText(e));
    } finally {
      setBusy('');
    }
  }, [targetRow, arriveIssues, arriveAmount, arriveLocation, arriveNotes, load, expanded]);

  const submitDiscrepancy = useCallback(async () => {
    if (!targetRow) return;
    if (discrepancyIssues.length) {
      setActionError(discrepancyIssues.join('；'));
      return;
    }
    setBusy('discrepancy');
    setActionError('');
    try {
      await recordInTransitDiscrepancy(targetRow.id, {
        expectedVersion: targetRow.version,
        quantity: Number(discrepancyAmount) || 0,
        reason: discrepancyReason,
        ...(discrepancyNotes.trim() ? { notes: discrepancyNotes.trim() } : {}),
      });
      setDiscrepancyOpen(false);
      await load();
      if (expanded === targetRow.id) setDetail(await fetchInTransitLeg(targetRow.id));
    } catch (e) {
      setActionError(errorText(e));
    } finally {
      setBusy('');
    }
  }, [targetRow, discrepancyIssues, discrepancyAmount, discrepancyReason, discrepancyNotes, load, expanded]);

  const loadLots = useCallback(async (id: string) => {
    if (!id) {
      setLotOptions([]);
      return;
    }
    try {
      const lots = await fetchLots(id);
      setLotOptions(lots.map((l) => ({ id: l.id, lotCode: l.lotCode })));
    } catch {
      setLotOptions([]);
    }
  }, []);

  if (!readable) {
    return (
      <SectionCard title="在途库存">
        <EmptyState
          title="没有查看权限"
          description="当前角色没有 warehouse.inventory.read，无法查看在途库存。"
        />
      </SectionCard>
    );
  }

  const totalPages = page && page.size > 0 ? Math.max(1, Math.ceil(page.total / page.size)) : 1;

  const legTable = (rows: InTransitRow[]) => (
    <div className="overflow-x-auto">
      <table className="w-full text-sm">
        <thead className="text-muted-foreground border-b text-left">
          <tr>
            <th className="p-2">类型</th>
            <th className="p-2">SKU</th>
            <th className="p-2">路线</th>
            <th className="p-2 text-right">原发</th>
            <th className="p-2 text-right">已了结</th>
            <th className="p-2 text-right">仍在途</th>
            <th className="p-2">预计到达</th>
            <th className="p-2">逾期</th>
            <th className="p-2">状态</th>
            <th className="p-2">来源</th>
            <th className="p-2" />
          </tr>
        </thead>
        <tbody>
          {rows.map((row) => {
            const actions = inTransitActions(row);
            const conservation = conservationText(row);
            return (
              <tr key={row.id} className="border-b last:border-0">
                <td className="p-2">
                  <Badge tone="brand">{row.transitKindLabel || inTransitKindLabels[row.transitKind] || row.transitKind}</Badge>
                </td>
                <td className="p-2">
                  <div>{row.skuCode}</div>
                  <div className="text-muted-foreground text-xs">{row.skuName}</div>
                </td>
                <td className="p-2 text-xs">{routeText(row)}</td>
                <td className="p-2 text-right tabular-nums">{fmt(row.declaredQuantity)}</td>
                <td className="p-2 text-right tabular-nums">{fmt(row.settledQuantity)}</td>
                <td className="p-2 text-right font-medium tabular-nums">{fmt(row.outstandingQuantity)}</td>
                <td className="p-2 text-xs">{etaText(row)}</td>
                <td className="p-2">
                  <div className="flex flex-col gap-1">
                    <Badge tone={overdueTone(row.overdueLevel)}>
                      {row.overdueLevelLabel || inTransitOverdueLabels[row.overdueLevel] || row.overdueLevel}
                    </Badge>
                    <span className="text-muted-foreground text-xs">{overdueHoursText(row)}</span>
                  </div>
                </td>
                <td className="p-2 text-xs">{inTransitStatusLabels[row.status] || row.status}</td>
                <td className="text-muted-foreground p-2 text-xs">{transferRefText(row)}</td>
                <td className="p-2 text-right">
                  <div className="flex justify-end gap-1">
                    {writable && actions.includes('arrive') && (
                      <Button
                        size="sm"
                        variant="ghost"
                        onClick={() =>
                          void openArrive(
                            row.id,
                            arriveNeedsLocation(row.transitKind),
                            row.toWarehouseId,
                          )
                        }
                      >
                        <PackageCheck /> 到货
                      </Button>
                    )}
                    {writable && actions.includes('discrepancy') && (
                      <Button size="sm" variant="ghost" onClick={() => openDiscrepancy(row.id)}>
                        <AlertTriangle /> 差异
                      </Button>
                    )}
                    <Button size="sm" variant="ghost" onClick={() => void openDetail(row.id)}>
                      详情
                    </Button>
                  </div>
                </td>
              </tr>
            );
          })}
          {rows.length === 0 && (
            <tr>
              <td colSpan={11} className="text-muted-foreground p-4 text-center">
                没有符合条件的在途腿
              </td>
            </tr>
          )}
        </tbody>
      </table>
    </div>
  );

  return (
    <div className="space-y-6">
      <PageHeader
        title="在途库存"
        description="调拨 / 头程 / 采购 / FBA 发货四类在途的同一个口径，附到达预警与补货可扣净额。"
        actions={
          <>
            <Button variant="ghost" onClick={() => void load()} disabled={loading}>
              <RefreshCw className={loading ? 'animate-spin' : ''} /> 刷新
            </Button>
            {writable && (
              <Button
                onClick={() => {
                  setActionError('');
                  setDraft({});
                  setLotOptions([]);
                  setRegisterOpen(true);
                }}
              >
                <Plus /> 登记外部在途
              </Button>
            )}
          </>
        }
      />

      {actionError && (
        <div className="flex items-start gap-2 rounded-lg border border-amber-500/40 bg-amber-500/10 p-3 text-sm">
          <AlertTriangle className="mt-0.5 size-4 shrink-0" />
          <span>{actionError}</span>
        </div>
      )}

      {error && (
        <Notice tone="danger" title="在途数据加载失败">
          {error}
        </Notice>
      )}

      {summary && (
        <div className="grid gap-4 md:grid-cols-4">
          <StatCard
            label="在途腿"
            value={String(summary.legCount)}
            comparisonLabel={`未达合计 ${fmt(summary.outstandingQuantity)}（不受分页影响）`}
          />
          <StatCard
            label="逾期未达"
            value={String(summary.overdueLegCount)}
            comparisonLabel={`逾期在途 ${fmt(summary.overdueQuantity)}`}
          />
          <StatCard
            label="未登记 ETA"
            value={String(summary.noEtaLegCount)}
            comparisonLabel={
              summary.earliestExpectedArriveAt
                ? `最早预计到达 ${moment(summary.earliestExpectedArriveAt)}`
                : '没有登记 ETA 的腿算不出逾期'
            }
          />
          <StatCard
            label="四类在途"
            value={fmt(summary.outstandingQuantity)}
            comparisonLabel={`调拨 ${fmt(summary.outstandingTransfer)} · 头程 ${fmt(summary.outstandingFirstMile)} · 采购 ${fmt(summary.outstandingPurchase)} · FBA ${fmt(summary.outstandingFbaOutbound)}`}
          />
        </div>
      )}

      <Segmented<Tab>
        value={tab}
        onChange={setTab}
        aria-label="在途库存视图"
        options={[
          { value: 'legs', label: `在途清单（${page?.total ?? 0}）` },
          { value: 'alerts', label: `到达预警（${alerts?.totalOverdueLegs ?? 0}）` },
          { value: 'netting', label: '补货可扣净额' },
        ]}
      />

      {tab === 'legs' && (
        <SectionCard
          title="在途清单"
          extra={
            <div className="flex flex-wrap items-center gap-2">
              <Input
                className="h-8 w-44"
                placeholder="SKU / 运单 / 来源单据"
                value={keyword}
                onChange={(e) => {
                  setKeyword(e.target.value);
                  setPageNumber(0);
                }}
              />
              <select
                className="rounded-md border px-2 py-1 text-sm"
                value={transitKind}
                onChange={(e) => {
                  setTransitKind(e.target.value);
                  setPageNumber(0);
                }}
              >
                <option value="">全部类型</option>
                {(page?.kindOptions ?? []).map((o) => (
                  <option key={o.value} value={o.value}>
                    {o.label}（{o.legCount}）
                  </option>
                ))}
              </select>
              <select
                className="rounded-md border px-2 py-1 text-sm"
                value={fromWarehouseId}
                onChange={(e) => {
                  setFromWarehouseId(e.target.value);
                  setPageNumber(0);
                }}
              >
                <option value="">全部起点仓</option>
                {(page?.warehouseOptions ?? []).map((o) => (
                  <option key={o.id} value={o.id}>
                    {o.code} {o.name}
                  </option>
                ))}
              </select>
              <select
                className="rounded-md border px-2 py-1 text-sm"
                value={toWarehouseId}
                onChange={(e) => {
                  setToWarehouseId(e.target.value);
                  setPageNumber(0);
                }}
              >
                <option value="">全部终点仓</option>
                {(page?.warehouseOptions ?? []).map((o) => (
                  <option key={o.id} value={o.id}>
                    {o.code} {o.name}
                  </option>
                ))}
              </select>
              <select
                className="rounded-md border px-2 py-1 text-sm"
                value={skuId}
                onChange={(e) => {
                  setSkuId(e.target.value);
                  setPageNumber(0);
                }}
              >
                <option value="">全部 SKU</option>
                {skus.map((s) => (
                  <option key={s.id} value={s.id}>
                    {s.code} {s.name}
                  </option>
                ))}
              </select>
              <select
                className="rounded-md border px-2 py-1 text-sm"
                value={overdueLevel}
                onChange={(e) => {
                  setOverdueLevel(e.target.value);
                  setPageNumber(0);
                }}
              >
                <option value="">全部逾期档</option>
                {(page?.overdueOptions ?? []).map((o) => (
                  <option key={o.value} value={o.value}>
                    {o.label}（{o.legCount}）
                  </option>
                ))}
              </select>
              <label className="flex items-center gap-1 text-sm">
                <input
                  type="checkbox"
                  checked={overdueOnly}
                  onChange={(e) => {
                    setOverdueOnly(e.target.checked);
                    setPageNumber(0);
                  }}
                />
                只看逾期
              </label>
              <select
                className="rounded-md border px-2 py-1 text-sm"
                value={sort}
                onChange={(e) => {
                  setSort(e.target.value);
                  setPageNumber(0);
                }}
              >
                {inTransitSortOptions.map((o) => (
                  <option key={o.value} value={o.value}>
                    {o.label}
                  </option>
                ))}
              </select>
            </div>
          }
        >
          {page && legTable(page.rows)}

          <p className="text-muted-foreground mt-3 text-xs">
            缺口计算要扣的是<strong>仍在途</strong>，不是原发量。
            调拨腿的已了结恒为 0（7.5.1.1 把到达量拆成独立 RECEIVED 行），到达权威在调拨单的已实收列。
          </p>
          <p className="text-muted-foreground mt-1 text-xs">
            守恒式（原发 = 已了结 + 仍在途）是<strong>逐行</strong>成立的，在详情里显式摆出；
            全量汇总只给未达量与逾期量，没有全量的原发/已了结，所以这里不做全量守恒自检。
          </p>

          {expanded && (
            <div className="mt-4 space-y-2 border-t pt-3">
              {detailError && (
                    <Notice tone="danger" title="详情读取失败">
                      {detailError}
                    </Notice>
                  )}
              {!detail && !detailError && (
                <p className="text-muted-foreground text-sm">正在读取在途腿详情…</p>
              )}
              {detail && (
                <>
                  <div className="flex flex-wrap items-center gap-2">
                    <Truck className="size-4" />
                    <span className="font-medium">{detail.leg.skuCode} 在途腿</span>
                    <Badge tone={overdueTone(detail.leg.overdueLevel)}>
                      {detail.leg.overdueLevelLabel}
                    </Badge>
                    <span className="text-muted-foreground text-xs">
                      {conservationText(detail.leg).text}
                    </span>
                  </div>
                  {detail.leg.attribution && (
                    <Notice tone="info" title="口径说明">
                      {detail.leg.attribution}
                    </Notice>
                  )}
                  {detail.metricNote && (
                    <p className="text-muted-foreground text-xs">{detail.metricNote}</p>
                  )}
                  <ul className="mt-2 space-y-1">
                    {detail.events.map((event) => (
                      <li key={event.id} className="flex flex-wrap items-center gap-2 text-sm">
                        <Badge tone={event.kind === 'ARRIVED' ? 'success' : 'warning'}>
                          {inTransitEventLabels[event.kind] || event.kind}
                        </Badge>
                        <span className="tabular-nums">{fmt(event.quantity)}</span>
                        <span className="text-muted-foreground">{moment(event.occurredAt)}</span>
                        {event.locationCode && (
                          <span className="text-muted-foreground text-xs">
                            落库位 {event.locationCode}
                          </span>
                        )}
                        {event.reason && (
                          <span className="text-muted-foreground text-xs">
                            {inTransitReasonLabels[event.reason] || event.reason}
                          </span>
                        )}
                        {event.notes && (
                          <span className="text-muted-foreground text-xs">{event.notes}</span>
                        )}
                      </li>
                    ))}
                    {detail.events.length === 0 && (
                      <li className="text-muted-foreground text-sm">还没有到达或差异记录</li>
                    )}
                  </ul>
                </>
              )}
            </div>
          )}

          <div className="mt-4 flex items-center justify-between text-sm">
            <span className="text-muted-foreground">
              第 {pageNumber + 1} / {totalPages} 页 · 共 {page?.total ?? 0} 条
            </span>
            <div className="flex gap-2">
              <Button
                size="sm"
                variant="ghost"
                disabled={pageNumber === 0}
                onClick={() => setPageNumber((n) => Math.max(0, n - 1))}
              >
                上一页
              </Button>
              <Button
                size="sm"
                variant="ghost"
                disabled={pageNumber + 1 >= totalPages}
                onClick={() => setPageNumber((n) => n + 1)}
              >
                下一页
              </Button>
            </div>
          </div>
        </SectionCard>
      )}

      {tab === 'alerts' && (
        <SectionCard title="到达预警（逾期未达）">
          <p className="text-muted-foreground mb-3 text-xs">
            档位与清单用<strong>同一套阈值</strong>（都是「ETA + 此刻 + 仍在途」算出来的），
            所以同一条在途腿在两个页面上不会给出互相矛盾的逾期档。
            计算时刻：{alerts ? moment(alerts.generatedAt) : '—'}。
          </p>
          {alerts && alerts.rows.length === 0 && (
            <EmptyState title="没有逾期在途" description="所有在途腿都还在预计到达时间之内，或已了结。" />
          )}
          {alerts && alerts.rows.length > 0 && legTable(alerts.rows)}
        </SectionCard>
      )}

      {tab === 'netting' && (
        <SectionCard
          title="补货可扣在途净额"
          extra={
            <select
              className="rounded-md border px-2 py-1 text-sm"
              value={nettingSort}
              onChange={(e) => setNettingSort(e.target.value)}
            >
              {inTransitNettingSortOptions.map((o) => (
                <option key={o.value} value={o.value}>
                  {o.label}
                </option>
              ))}
            </select>
          }
        >
          {netting && (
            <Notice tone="info" title="口径说明">
              {netting.usageNote}
            </Notice>
          )}
          <p className="text-muted-foreground mt-2 mb-3 text-xs">
            本页只交数、<strong>不给补货建议</strong>：需求预测与下单属 6.2.1.1，不在本模块。
            也没有接入审批流或常驻调度 —— 到货与差异登记是直接落库的业务事件。
          </p>
          {netting && (
            <div className="overflow-x-auto">
              <table className="w-full text-sm">
                <thead className="text-muted-foreground border-b text-left">
                  <tr>
                    <th className="p-2">目的仓</th>
                    <th className="p-2">SKU</th>
                    <th className="p-2 text-right">可扣在途</th>
                    <th className="p-2 text-right">其中无 ETA</th>
                    <th className="p-2 text-right">在途腿数</th>
                    <th className="p-2">最晚预计到达</th>
                  </tr>
                </thead>
                <tbody>
                  {netting.rows.map((row) => (
                    <tr key={`${row.toWarehouseId}:${row.skuId}`} className="border-b last:border-0">
                      <td className="p-2">
                        {row.toWarehouseCode} {row.toWarehouseName}
                      </td>
                      <td className="p-2">
                        <div>{row.skuCode}</div>
                        <div className="text-muted-foreground text-xs">{row.skuName}</div>
                      </td>
                      <td className="p-2 text-right font-medium tabular-nums">
                        {fmt(row.outstandingQuantity)}
                      </td>
                      <td className="text-muted-foreground p-2 text-right tabular-nums">
                        {fmt(row.noEtaQuantity)}
                      </td>
                      <td className="p-2 text-right tabular-nums">{row.legCount}</td>
                      <td className="p-2 text-xs">{moment(row.latestExpectedArriveAt)}</td>
                    </tr>
                  ))}
                  {netting.rows.length === 0 && (
                    <tr>
                      <td colSpan={6} className="text-muted-foreground p-4 text-center">
                        没有可扣的在途净额
                      </td>
                    </tr>
                  )}
                </tbody>
              </table>
              <p className="text-muted-foreground mt-3 text-xs">
                合计可扣在途 {fmt(netting.totalOutstandingQuantity)}（共 {netting.total} 组「目的仓 × SKU」）。
                无 ETA 的那部分要单独看：扣了它，缺口会算小，但货什么时候到没人说得清。
              </p>
            </div>
          )}
        </SectionCard>
      )}

      {/* 登记：调拨腿不在这里 —— 它由 7.5.1.1 调拨单的发出动作产生，两处都写会让在途翻倍。 */}
      <Modal open={registerOpen} onOpenChange={setRegisterOpen}>
        <ModalContent>
          <ModalHeader>
            <ModalTitle>登记外部在途腿</ModalTitle>
            <ModalDescription>
              登记头程发运 / 采购发运 / FBA 发货运单事件。
              <strong>调拨在途不在这里登记</strong> —— 它由 7.5.1.1 调拨单的发出动作产生。
              来源系统 + 来源单据号是幂等锚点，上游重复投递会被后端挡成 409。
            </ModalDescription>
          </ModalHeader>
          <div className="grid gap-3 md:grid-cols-2">
            <FormField id="transit-kind" label="在途类型" required>
              <select
                id="transit-kind"
                className="w-full rounded-md border px-2 py-1 text-sm"
                value={draft.transitKind ?? ''}
                onChange={(e) => setDraft({ ...draft, transitKind: e.target.value })}
              >
                <option value="">请选择</option>
                {registrableTransitKinds.map((kind) => (
                  <option key={kind} value={kind}>
                    {inTransitKindLabels[kind]}
                  </option>
                ))}
              </select>
            </FormField>
            <FormField id="quantity" label="在途数量" required>
              <Input
                id="quantity"
                type="number"
                min="0"
                step="any"
                value={draft.quantity ?? ''}
                onChange={(e) => setDraft({ ...draft, quantity: Number(e.target.value) })}
              />
            </FormField>
            <FormField id="sku" label="SKU" required>
              <select
                id="sku"
                className="w-full rounded-md border px-2 py-1 text-sm"
                value={draft.skuId ?? ''}
                onChange={(e) => {
                  setDraft({ ...draft, skuId: e.target.value, lotId: '' });
                  void loadLots(e.target.value);
                }}
              >
                <option value="">请选择</option>
                {skus.map((s) => (
                  <option key={s.id} value={s.id}>
                    {s.code} {s.name}
                  </option>
                ))}
              </select>
            </FormField>
            <FormField id="lot" label="批次（可选）" hint="留空表示未指定批次；填了必须属于所选 SKU">
              <select
                id="lot"
                className="w-full rounded-md border px-2 py-1 text-sm"
                value={draft.lotId ?? ''}
                onChange={(e) => setDraft({ ...draft, lotId: e.target.value })}
              >
                <option value="">不指定</option>
                {lotOptions.map((l) => (
                  <option key={l.id} value={l.id}>
                    {l.lotCode}
                  </option>
                ))}
              </select>
            </FormField>
            <FormField id="quality" label="质量状态" required>
              <select
                id="quality"
                className="w-full rounded-md border px-2 py-1 text-sm"
                value={draft.qualityStatusId ?? ''}
                onChange={(e) => setDraft({ ...draft, qualityStatusId: e.target.value })}
              >
                <option value="">请选择</option>
                {qualities.map((q) => (
                  <option key={q.id} value={q.id}>
                    {q.code}
                  </option>
                ))}
              </select>
            </FormField>
            <FormField id="entity" label="货权法人" required>
              <select
                id="entity"
                className="w-full rounded-md border px-2 py-1 text-sm"
                value={draft.legalEntityId ?? ''}
                onChange={(e) => setDraft({ ...draft, legalEntityId: e.target.value })}
              >
                <option value="">请选择</option>
                {entities.map((l) => (
                  <option key={l.id} value={l.id}>
                    {l.code} {l.name}
                  </option>
                ))}
              </select>
            </FormField>
            <FormField
              id="from-warehouse"
              label="起点仓 / 起点名称"
              hint="不在本租户（如供应商工厂、平台仓）时只填名称"
            >
              <select
                id="from-warehouse"
                className="w-full rounded-md border px-2 py-1 text-sm"
                value={draft.fromWarehouseId ?? ''}
                onChange={(e) => setDraft({ ...draft, fromWarehouseId: e.target.value })}
              >
                <option value="">不在本租户</option>
                {warehouses.map((w) => (
                  <option key={w.id} value={w.id}>
                    {w.code} {w.name}
                  </option>
                ))}
              </select>
            </FormField>
            <FormField id="from-name" label="起点名称">
              <Input
                id="from-name"
                value={draft.fromWarehouseName ?? ''}
                onChange={(e) => setDraft({ ...draft, fromWarehouseName: e.target.value })}
              />
            </FormField>
            <FormField id="to-warehouse" label="终点仓 / 终点名称" required>
              <select
                id="to-warehouse"
                className="w-full rounded-md border px-2 py-1 text-sm"
                value={draft.toWarehouseId ?? ''}
                onChange={(e) => setDraft({ ...draft, toWarehouseId: e.target.value })}
              >
                <option value="">不在本租户</option>
                {warehouses.map((w) => (
                  <option key={w.id} value={w.id}>
                    {w.code} {w.name}
                  </option>
                ))}
              </select>
            </FormField>
            <FormField id="to-name" label="终点名称">
              <Input
                id="to-name"
                value={draft.toWarehouseName ?? ''}
                onChange={(e) => setDraft({ ...draft, toWarehouseName: e.target.value })}
              />
            </FormField>
            <FormField id="shipped-at" label="发出时间" required>
              <Input
                id="shipped-at"
                type="datetime-local"
                value={draft.shippedAt ?? ''}
                onChange={(e) => setDraft({ ...draft, shippedAt: e.target.value })}
              />
            </FormField>
            <FormField
              id="eta"
              label="预计到达（可选）"
              hint="不填就归入「未登记 ETA」，逾期算不出来；可以早于此刻（补录旧批次）"
            >
              <Input
                id="eta"
                type="datetime-local"
                value={draft.expectedArriveAt ?? ''}
                onChange={(e) => setDraft({ ...draft, expectedArriveAt: e.target.value })}
              />
            </FormField>
            <FormField id="source-system" label="来源系统" required>
              <Input
                id="source-system"
                value={draft.sourceSystem ?? ''}
                onChange={(e) => setDraft({ ...draft, sourceSystem: e.target.value })}
                placeholder="LOGISTICS / PURCHASING / FBA"
              />
            </FormField>
            <FormField id="source-ref" label="来源单据号" required>
              <Input
                id="source-ref"
                value={draft.sourceReference ?? ''}
                onChange={(e) => setDraft({ ...draft, sourceReference: e.target.value })}
              />
            </FormField>
            <FormField id="carrier" label="承运商（可选）">
              <Input
                id="carrier"
                value={draft.carrierName ?? ''}
                onChange={(e) => setDraft({ ...draft, carrierName: e.target.value })}
              />
            </FormField>
            <FormField id="tracking" label="运单号（可选）">
              <Input
                id="tracking"
                value={draft.trackingNo ?? ''}
                onChange={(e) => setDraft({ ...draft, trackingNo: e.target.value })}
              />
            </FormField>
          </div>

          {registerIssues.length > 0 && (
            <ul className="text-destructive mt-3 space-y-1 text-xs">
              {registerIssues.map((issue) => (
                <li key={issue}>· {issue}</li>
              ))}
            </ul>
          )}

          <ModalFooter>
            <Button variant="ghost" onClick={() => setRegisterOpen(false)}>
              取消
            </Button>
            <Button onClick={() => void submitRegister()} disabled={busy === 'register'}>
              登记
            </Button>
          </ModalFooter>
        </ModalContent>
      </Modal>

      {/* 到货：可分批。落目的仓在手并写 RECEIPT 分录。 */}
      <Modal open={arriveOpen} onOpenChange={setArriveOpen}>
        <ModalContent>
          <ModalHeader>
            <ModalTitle>登记到货</ModalTitle>
            <ModalDescription>
              可分批到货。本次数量不得超过该腿未达量
              {targetRow ? `（当前未达 ${fmt(targetRow.outstandingQuantity)}）` : ''}。
              携带版本号 {targetRow?.version ?? '—'} 做乐观锁：有人先改过会拿到 409。
            </ModalDescription>
          </ModalHeader>
          <div className="grid gap-3">
            <FormField id="arrive-amount" label="本次到货数量" required>
              <Input
                id="arrive-amount"
                type="number"
                min="0"
                step="any"
                value={arriveAmount}
                onChange={(e) => setArriveAmount(e.target.value)}
              />
            </FormField>
            {targetRow && arriveNeedsLocation(targetRow.transitKind) && (
              <FormField
                id="arrive-location"
                label="落库库位"
                required
                hint="进我方仓的到货必须回答「到了哪个库位」，且该库位要属于本腿的目的仓"
              >
                <select
                  id="arrive-location"
                  className="w-full rounded-md border px-2 py-1 text-sm"
                  value={arriveLocation}
                  onChange={(e) => setArriveLocation(e.target.value)}
                >
                  <option value="">请选择</option>
                  {locations.map((l) => (
                    <option key={l.id} value={l.id}>
                      {l.code} {l.name}
                    </option>
                  ))}
                </select>
              </FormField>
            )}
            <FormField id="arrive-notes" label="备注（可选）">
              <Input
                id="arrive-notes"
                value={arriveNotes}
                onChange={(e) => setArriveNotes(e.target.value)}
              />
            </FormField>
          </div>

          {arriveIssues.length > 0 && (
            <ul className="text-destructive mt-3 space-y-1 text-xs">
              {arriveIssues.map((issue) => (
                <li key={issue}>· {issue}</li>
              ))}
            </ul>
          )}

          <ModalFooter>
            <Button variant="ghost" onClick={() => setArriveOpen(false)}>
              取消
            </Button>
            <Button onClick={() => void submitArrive()} disabled={busy === 'arrive'}>
              确认到货
            </Button>
          </ModalFooter>
        </ModalContent>
      </Modal>

      {/* 差异登记：到不了的部分如实记为短少，在途随之归零。原因必填，不自动抹平。 */}
      <Modal open={discrepancyOpen} onOpenChange={setDiscrepancyOpen}>
        <ModalContent>
          <ModalHeader>
            <ModalTitle>登记差异（短少）</ModalTitle>
            <ModalDescription>
              把到不了的部分如实记为短少。差异原因<strong>必填</strong>，系统不自动抹平 ——
              差异必须有人认领原因才闭合。
            </ModalDescription>
          </ModalHeader>
          <div className="grid gap-3">
            <FormField
              id="discrepancy-amount"
              label="差异数量"
              required
              {...(targetRow
                ? { hint: `不得超过未达量 ${fmt(targetRow.outstandingQuantity)}` }
                : {})}
            >
              <Input
                id="discrepancy-amount"
                type="number"
                min="0"
                step="any"
                value={discrepancyAmount}
                onChange={(e) => setDiscrepancyAmount(e.target.value)}
              />
            </FormField>
            <FormField id="discrepancy-reason" label="差异原因" required>
              <select
                id="discrepancy-reason"
                className="w-full rounded-md border px-2 py-1 text-sm"
                value={discrepancyReason}
                onChange={(e) => setDiscrepancyReason(e.target.value)}
              >
                {Object.entries(inTransitReasonLabels).map(([value, label]) => (
                  <option key={value} value={value}>
                    {label}
                  </option>
                ))}
              </select>
            </FormField>
            <FormField id="discrepancy-notes" label="备注（可选）">
              <Input
                id="discrepancy-notes"
                value={discrepancyNotes}
                onChange={(e) => setDiscrepancyNotes(e.target.value)}
              />
            </FormField>
          </div>

          {discrepancyIssues.length > 0 && (
            <ul className="text-destructive mt-3 space-y-1 text-xs">
              {discrepancyIssues.map((issue) => (
                <li key={issue}>· {issue}</li>
              ))}
            </ul>
          )}

          <ModalFooter>
            <Button variant="ghost" onClick={() => setDiscrepancyOpen(false)}>
              取消
            </Button>
            <Button onClick={() => void submitDiscrepancy()} disabled={busy === 'discrepancy'}>
              登记差异
            </Button>
          </ModalFooter>
        </ModalContent>
      </Modal>
    </div>
  );
}