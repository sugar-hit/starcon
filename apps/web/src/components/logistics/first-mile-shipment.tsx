'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import { Anchor, PackagePlus, RefreshCw, Ship, TriangleAlert } from 'lucide-react';
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
  FirstMileCapabilities,
  FirstMileRow,
} from '../../../../../packages/api-contracts/src/catalog';
import {
  allowedRiskBearers,
  cancelFirstMileShipment,
  confirmFirstMileShipment,
  createFirstMileShipment,
  dimensionCaption,
  dispatchFirstMileShipment,
  fetchFirstMileCapabilities,
  fetchFirstMileDetail,
  fetchFirstMileShipments,
  fetchSourceCandidates,
  firstMileQuery,
  fmt,
  legQuantityText,
  lineCaption,
  moment,
  money,
  pending,
  riskBearerLabel,
  shipmentActions,
  shipmentSortOptions,
  shipmentStatusLabels,
  transportModeLabels,
  type FirstMileParams,
} from './first-mile-shipment-model';
import { errorText, fetchLegalEntities, fetchWarehouses } from '../inventory/inventory-model';

interface Option {
  id: string;
  code: string;
  name: string;
}

interface Candidate {
  receiptEventLineId: string;
  receiptId: string;
  receiptCode: string;
  waybillNo: string;
  skuId: string;
  skuCode: string;
  skuName: string;
  qualityStatusId: string;
  qualityStatusName: string;
  lotId: string | null;
  lotCode: string | null;
  lotExpiryDate: string | null;
  receivedQuantity: number;
  alreadyShippedQuantity: number;
  shippableQuantity: number;
  warehouseCode: string;
  warehouseName: string;
}

/** 四个成本预估列。键名与表单字段同名，写回时逐个 switch（见表单里的注释）。 */
const COST_FIELDS = [
  ['freightCost', '运费预估'],
  ['insuranceCost', '保险费预估'],
  ['dutyEstimate', '关税预估'],
  ['handlingCost', '操作费预估'],
] as const;

interface DraftLine {
  key: string;
  sourceReceiptEventLineId: string;
  skuId: string;
  skuCode: string;
  qualityStatusId: string;
  qualityStatusName: string;
  lotId: string | null;
  lotCode: string | null;
  plannedQuantity: string;
  declaredItemName: string;
  hsCode: string;
  brand: string;
  originCountryCode: string;
  declaredUnitValue: string;
  declaredValue: string;
  grossWeightKg: string;
  netWeightKg: string;
  volumeCbm: string;
  cartonCount: string;
}

function emptyDraft(): DraftLine {
  return {
    key: Math.random().toString(36).slice(2),
    sourceReceiptEventLineId: '',
    skuId: '',
    skuCode: '',
    qualityStatusId: '',
    qualityStatusName: '',
    lotId: null,
    lotCode: null,
    plannedQuantity: '',
    declaredItemName: '',
    hsCode: '',
    brand: '',
    originCountryCode: 'CN',
    declaredUnitValue: '',
    declaredValue: '',
    grossWeightKg: '',
    netWeightKg: '',
    volumeCbm: '',
    cartonCount: '',
  };
}

function num(value: string): number | undefined {
  const trimmed = value.trim();
  if (!trimmed) return undefined;
  const parsed = Number(trimmed);
  return Number.isFinite(parsed) ? parsed : undefined;
}

/**
 * 10.4.1.1 头程发运单工作区。
 *
 * <p>蓝图：「管理国内到海外的货权转移发运：选择货物与批次（国内仓/供应商）、选择运输方式
 * （海运/空运/铁运/快递）、申报信息、目的仓、预计到港时间、成本预估」；输出「头程发运单 +
 * 发货分录（转在途）+ 报关资料 + 预计到港时间」。
 *
 * <p>界面纪律：
 *
 * <ul>
 *   <li>**货权与成本节点是列，不是页脚**：贸易术语（成本转嫁方式）与风险承担方决定
 *       发出之后的利润口径，列表、详情、表单三处都必须能读到。风险承担方的可选集合
 *       从 `/capabilities` 读，不在前端抄一份枚举（抄了会在后端加值时静默漂移）。
 *   <li>**发出不扣在手**这条口径常驻页面。头程货在到货前从未进过我方库存账，
 *       界面上不写清这句，「发出后库存没少」会被读成账丢货。
 *   <li>**每行同时显示计划量 / 发出量 / 余量**：部分发运是常态，
 *       而「发 0」会被拒（已发出是终态，那一行会永远留在计划里发不出去）。
 *   <li>**未交付的来源与下游直接禁用并写明归属模块**，而不是给一个点下去报错的选项。
 *   <li>加载 / 空 / 错误三态齐全：空态说清「还没有头程发运单」，不假装有数据。
 * </ul>
 */
export function FirstMileWorkspace() {
  const [params, setParams] = useState<FirstMileParams>({ page: 0, size: 20 });
  const [data, setData] = useState<Awaited<ReturnType<typeof fetchFirstMileShipments>> | null>(
    null,
  );
  const [caps, setCaps] = useState<FirstMileCapabilities | null>(null);
  const [warehouses, setWarehouses] = useState<Option[]>([]);
  const [legalEntities, setLegalEntities] = useState<Option[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [detailId, setDetailId] = useState<string | null>(null);
  const [creating, setCreating] = useState(false);
  const [candidates, setCandidates] = useState<Candidate[] | null>(null);
  const [draftLines, setDraftLines] = useState<DraftLine[]>([]);
  const [form, setForm] = useState({
    originWarehouseId: '',
    destinationWarehouseId: '',
    destinationCountryCode: 'US',
    destinationName: '',
    transportMode: 'SEA',
    carrierName: '',
    bookingNo: '',
    vesselOrFlightNo: '',
    incoterm: 'FOB',
    riskBearer: 'SELLER',
    legalEntityId: '',
    currency: 'USD',
    freightCost: '',
    insuranceCost: '',
    dutyEstimate: '',
    handlingCost: '',
    customsBroker: '',
    departAt: '',
    expectedArriveAt: '',
    notes: '',
  });
  const [submitting, setSubmitting] = useState(false);
  const [formError, setFormError] = useState<string | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      setData(await fetchFirstMileShipments(params));
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
        setCaps(await fetchFirstMileCapabilities());
      } catch {
        // capabilities 拿不到不该让整页空掉：列表仍然可用，只是术语/风险的可选集合退化为宽放，
        // 最终裁判仍在后端（DB 两条 CHECK 照旧生效）。
        setCaps(null);
      }
    })();
    void (async () => {
      try {
        setWarehouses((await fetchWarehouses()) as unknown as Option[]);
      } catch {
        setWarehouses([]);
      }
    })();
    void (async () => {
      try {
        setLegalEntities((await fetchLegalEntities()) as unknown as Option[]);
      } catch {
        setLegalEntities([]);
      }
    })();
  }, []);

  const rows = data?.rows ?? [];
  const summary = data?.summary;
  const bearers = useMemo(() => allowedRiskBearers(caps, form.incoterm), [caps, form.incoterm]);
  const goodsReceiptSource = caps?.sources?.find((s) => s.value === 'GOODS_RECEIPT');
  const unavailable = (caps?.sources ?? []).filter((s) => !s.available);

  const openCreate = () => {
    setForm({
      originWarehouseId: '',
      destinationWarehouseId: '',
      destinationCountryCode: 'US',
      destinationName: '',
      transportMode: 'SEA',
      carrierName: '',
      bookingNo: '',
      vesselOrFlightNo: '',
      incoterm: 'FOB',
      riskBearer: 'SELLER',
      legalEntityId: legalEntities[0]?.id ?? '',
      currency: 'USD',
      freightCost: '',
      insuranceCost: '',
      dutyEstimate: '',
      handlingCost: '',
      customsBroker: '',
      departAt: '',
      expectedArriveAt: '',
      notes: '',
    });
    setDraftLines([emptyDraft()]);
    setCandidates(null);
    setFormError(null);
    setCreating(true);
  };

  const loadCandidates = async (receiptId?: string) => {
    try {
      const page = await fetchSourceCandidates({
        ...(receiptId ? { receiptId } : {}),
        size: 50,
      });
      setCandidates(page.rows as unknown as Candidate[]);
    } catch (e) {
      setFormError(errorText(e));
    }
  };

  /** 把一条可发批次填进第一个空行；没有空行就新起一行。 */
  const addCandidate = (c: Candidate) => {
    setDraftLines((prev) => {
      const filled: DraftLine = {
        ...emptyDraft(),
        sourceReceiptEventLineId: c.receiptEventLineId,
        skuId: c.skuId,
        skuCode: c.skuCode,
        qualityStatusId: c.qualityStatusId,
        qualityStatusName: c.qualityStatusName,
        lotId: c.lotId,
        lotCode: c.lotCode,
        plannedQuantity: String(c.shippableQuantity),
        // 预填商品名只是省一次输入：**申报品名必须由人确认**，
        // 照搬销售标题是 10.5.1.2 点名的退单主因，所以留空而不是替用户填好。
        declaredItemName: '',
        originCountryCode: 'CN',
      };
      const index = prev.findIndex((l) => !l.sourceReceiptEventLineId);
      if (index < 0) return [...prev, filled];
      return prev.map((l, i) => (i === index ? filled : l));
    });
  };

  const submit = async () => {
    setSubmitting(true);
    setFormError(null);
    try {
      const lines = draftLines.filter((l) => l.sourceReceiptEventLineId);
      const head = lines[0];
      if (!head) {
        throw new Error('至少要选一条可发批次：头程发运的输入是「选择货物与批次」');
      }
      const first = candidates?.find((c) => c.receiptEventLineId === head.sourceReceiptEventLineId);
      const receiptId = first?.receiptId;
      if (!receiptId) {
        throw new Error('选中的批次找不到来源收货单，请重新选择批次');
      }
      if (lines.length > 1) {
        const ids = new Set(lines.map((l) => l.sourceReceiptEventLineId));
        if (ids.size !== lines.length) {
          throw new Error('同一张发运单里不能有两个相同批次');
        }
        const receipts = new Set(
          lines.map(
            (l) =>
              candidates?.find((c) => c.receiptEventLineId === l.sourceReceiptEventLineId)
                ?.receiptId,
          ),
        );
        if (receipts.size !== 1) {
          throw new Error('一张发运单的明细必须来自同一张收货单（货物来源是单级的）');
        }
      }
      await createFirstMileShipment({
        sourceType: 'GOODS_RECEIPT',
        sourceRefId: receiptId,
        sourceRefCode: first?.receiptCode ?? '',
        legalEntityId: form.legalEntityId,
        originWarehouseId: form.originWarehouseId,
        destinationWarehouseId: form.destinationWarehouseId,
        destinationCountryCode: form.destinationCountryCode,
        destinationName: form.destinationName,
        transportMode: form.transportMode,
        carrierName: form.carrierName,
        bookingNo: form.bookingNo,
        vesselOrFlightNo: form.vesselOrFlightNo,
        incoterm: form.incoterm,
        riskBearer: form.riskBearer,
        currency: form.currency,
        freightCost: num(form.freightCost) ?? 0,
        insuranceCost: num(form.insuranceCost) ?? 0,
        dutyEstimate: num(form.dutyEstimate) ?? 0,
        handlingCost: num(form.handlingCost) ?? 0,
        customsBroker: form.customsBroker,
        departAt: form.departAt ? new Date(form.departAt).toISOString() : null,
        expectedArriveAt: form.expectedArriveAt
          ? new Date(form.expectedArriveAt).toISOString()
          : null,
        notes: form.notes,
        lines: lines.map((l) => ({
          skuId: l.skuId,
          qualityStatusId: l.qualityStatusId,
          sourceReceiptEventLineId: l.sourceReceiptEventLineId,
          lotId: l.lotId,
          plannedQuantity: num(l.plannedQuantity) ?? 0,
          declaredItemName: l.declaredItemName,
          hsCode: l.hsCode,
          brand: l.brand,
          originCountryCode: l.originCountryCode,
          declaredUnitValue: num(l.declaredUnitValue) ?? 0,
          declaredValue: num(l.declaredValue) ?? 0,
          grossWeightKg: num(l.grossWeightKg) ?? 0,
          netWeightKg: num(l.netWeightKg) ?? 0,
          volumeCbm: num(l.volumeCbm) ?? 0,
          cartonCount: Number(l.cartonCount) || 0,
        })),
      });
      setCreating(false);
      await load();
    } catch (e) {
      setFormError(errorText(e));
    } finally {
      setSubmitting(false);
    }
  };

  const act = async (row: FirstMileRow, action: 'confirm' | 'dispatch' | 'cancel') => {
    setFormError(null);
    try {
      const notes =
        action === 'cancel' ? '界面取消' : action === 'dispatch' ? '界面发出' : '界面确认';
      if (action === 'cancel') {
        const reason = window.prompt('取消原因（必填）：取消会改变发运计划与货权归属');
        if (!reason?.trim()) {
          setFormError('取消必须给原因');
          return;
        }
        await cancelFirstMileShipment(row.id, {
          expectedVersion: row.version,
          reason: reason.trim(),
          notes,
        });
      } else if (action === 'confirm') {
        await confirmFirstMileShipment(row.id, {
          expectedVersion: row.version,
          departAt: row.departAt,
          expectedArriveAt: row.expectedArriveAt,
          notes,
        });
      } else {
        // lines 传空数组 = 全部按计划量整行发出（后端语义，不是「什么都不发」）。
        await dispatchFirstMileShipment(row.id, {
          expectedVersion: row.version,
          shippedAt: row.departAt,
          notes,
          lines: [],
        });
      }
      await load();
    } catch (e) {
      setFormError(errorText(e));
    }
  };

  return (
    <div className="space-y-6">
      <PageHeader
        title="头程发运单"
        description="国内到海外的货权转移发运：选批次、选运输方式、定申报与成本、发出即转在途"
        actions={
          <>
            <Button variant="ghost" onClick={() => void load()}>
              <RefreshCw className="size-4" />
              刷新
            </Button>
            <Button onClick={openCreate} disabled={!goodsReceiptSource?.available}>
              <PackagePlus className="size-4" />
              新建发运单
            </Button>
          </>
        }
      />

      {/* 口径说明常驻：这条口径不写清，「发出后库存没少」会被读成账丢货 */}
      <SectionCard title="落账口径">
        <ul className="text-muted-foreground space-y-1 text-sm">
          <li>
            <strong className="text-foreground">发出不扣在手</strong>
            ：头程货在发出前根本没进过我方库存账（它来自供应商或本租户另一张收货单）， 发出只向
            7.5.1.2 登记在途腿，到货入库由 7.5.1.2 的到货动作落目的仓。
          </li>
          <li>
            <strong className="text-foreground">货权与成本节点</strong>
            ：贸易术语（成本转嫁方式）与风险承担方从「已发出」起决定利润口径。 EXW
            只能配买方承担、DDP 只能配卖方承担，其余九种术语两者皆可。
          </li>
          <li>
            <strong className="text-foreground">申报品名不是销售标题</strong>
            ：它是清关口径的独立必填项，混用是常见被退单原因。
          </li>
          {caps && (
            <>
              <li>{caps.transitRegistrationNote}</li>
              <li>{caps.costNote}</li>
            </>
          )}
          {unavailable.map((s) => (
            <li key={s.value}>
              <strong className="text-foreground">{dimensionCaption(s)}</strong>：{s.note}
            </li>
          ))}
          {(caps?.dependencies ?? [])
            .filter((d) => !d.available)
            .map((d) => (
              <li key={d.value}>
                <strong className="text-foreground">{dimensionCaption(d)}</strong>：{d.note}
              </li>
            ))}
        </ul>
      </SectionCard>

      {summary && (
        <div className="grid gap-4 md:grid-cols-4">
          <StatCard
            label="头程发运单"
            value={String(summary.shipmentCount)}
            comparisonLabel={`草稿 ${summary.draftCount} · 已确认 ${summary.confirmedCount} · 已发出 ${summary.dispatchedCount} · 已取消 ${summary.cancelledCount}`}
          />
          <StatCard
            label="在路上的单"
            value={String(summary.inTransitShipmentCount)}
            comparisonLabel={`已发出合计 ${fmt(summary.shippedQuantity)} 件`}
          />
          <StatCard
            label="申报货值"
            value={money(summary.declaredValue)}
            comparisonLabel="按行申报货值合计，不含运费与关税"
          />
          <StatCard
            label="成本预估"
            value={money(summary.totalCost)}
            comparisonLabel="运费 + 保险 + 关税预估 + 操作费"
          />
        </div>
      )}

      <SectionCard title="筛选">
        <div className="grid gap-3 md:grid-cols-4">
          <label className="flex flex-col gap-1 text-sm">
            <span className="text-muted-foreground">关键字</span>
            <input
              className="rounded-md border px-3 py-2"
              placeholder="发运单号 / 来源单号 / 订舱号 / 船名航班号 / 备注"
              defaultValue={params.keyword ?? ''}
              onBlur={(e) => setParams((p) => ({ ...p, keyword: e.target.value, page: 0 }))}
            />
          </label>
          <label className="flex flex-col gap-1 text-sm">
            <span className="text-muted-foreground">状态</span>
            <select
              className="rounded-md border px-3 py-2"
              value={params.status?.[0] ?? ''}
              onChange={(e) =>
                setParams((p) => {
                  const next: FirstMileParams = { ...p, page: 0 };
                  if (e.target.value) next.status = [e.target.value];
                  else delete next.status;
                  return next;
                })
              }
            >
              <option value="">全部状态</option>
              {Object.entries(shipmentStatusLabels).map(([value, label]) => (
                <option key={value} value={value}>
                  {label}
                </option>
              ))}
            </select>
          </label>
          <label className="flex flex-col gap-1 text-sm">
            <span className="text-muted-foreground">运输方式</span>
            <select
              className="rounded-md border px-3 py-2"
              value={params.transportMode ?? ''}
              onChange={(e) =>
                setParams((p) => {
                  const next: FirstMileParams = { ...p, page: 0 };
                  if (e.target.value) next.transportMode = e.target.value;
                  else delete next.transportMode;
                  return next;
                })
              }
            >
              <option value="">全部方式</option>
              {Object.entries(transportModeLabels).map(([value, label]) => (
                <option key={value} value={value}>
                  {label}
                </option>
              ))}
            </select>
          </label>
          <label className="flex flex-col gap-1 text-sm">
            <span className="text-muted-foreground">贸易术语</span>
            <select
              className="rounded-md border px-3 py-2"
              value={params.incoterm ?? ''}
              onChange={(e) =>
                setParams((p) => {
                  const next: FirstMileParams = { ...p, page: 0 };
                  if (e.target.value) next.incoterm = e.target.value;
                  else delete next.incoterm;
                  return next;
                })
              }
            >
              <option value="">全部术语</option>
              {(caps?.incoterms ?? []).map((i) => (
                <option key={i.value} value={i.value}>
                  {i.label}
                </option>
              ))}
            </select>
          </label>
          <label className="flex flex-col gap-1 text-sm">
            <span className="text-muted-foreground">目的国</span>
            <input
              className="rounded-md border px-3 py-2"
              placeholder="两位国家代码，如 US"
              defaultValue={params.destinationCountryCode ?? ''}
              onBlur={(e) =>
                setParams((p) => ({ ...p, destinationCountryCode: e.target.value, page: 0 }))
              }
            />
          </label>
          <label className="flex flex-col gap-1 text-sm">
            <span className="text-muted-foreground">排序</span>
            <select
              className="rounded-md border px-3 py-2"
              value={params.sort ?? 'UPDATED_AT'}
              onChange={(e) => setParams((p) => ({ ...p, sort: e.target.value, page: 0 }))}
            >
              {shipmentSortOptions.map((o) => (
                <option key={o.value} value={o.value}>
                  {o.label}
                </option>
              ))}
            </select>
          </label>
          <label className="flex items-center gap-2 text-sm">
            <input
              type="checkbox"
              checked={params.inTransitOnly ?? false}
              onChange={(e) =>
                setParams((p) => {
                  // exactOptionalPropertyTypes 下不能写 `inTransitOnly: undefined`，
                  // 只能删键 —— 否则「不筛」和「筛一个空值」在类型上分不开。
                  const next: FirstMileParams = { ...p, page: 0 };
                  if (e.target.checked) next.inTransitOnly = true;
                  else delete next.inTransitOnly;
                  return next;
                })
              }
            />
            <span>只看在路上（已确认 + 已发出）</span>
          </label>
        </div>
      </SectionCard>

      {formError && (
        <SectionCard title="操作失败">
          <div className="flex items-start gap-2">
            <TriangleAlert className="text-destructive mt-0.5 size-5" />
            <p className="text-destructive text-sm">{formError}</p>
          </div>
        </SectionCard>
      )}

      {loading && !data ? (
        <SectionCard title="头程发运单列表">
          <p className="text-muted-foreground text-sm">正在加载头程发运单…</p>
        </SectionCard>
      ) : error ? (
        <SectionCard title="头程发运单列表">
          <div className="flex flex-col items-start gap-2">
            <TriangleAlert className="text-destructive size-5" />
            <p className="text-destructive text-sm">{error}</p>
            <Button variant="ghost" onClick={() => void load()}>
              重试
            </Button>
          </div>
        </SectionCard>
      ) : rows.length === 0 ? (
        <SectionCard title="头程发运单列表">
          <EmptyState
            icon={<Anchor className="size-6 w-6" />}
            title="还没有头程发运单"
            description="先从 8.2.1.1 的已收货批次里选货建单（草稿不动任何账），确认申报与订舱后再发出。"
          />
        </SectionCard>
      ) : (
        <SectionCard title={`头程发运单列表（共 ${data?.total ?? 0} 张）`}>
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead>
                <tr className="text-muted-foreground border-b text-left">
                  <th className="p-2">发运单号</th>
                  <th className="p-2">路线</th>
                  <th className="p-2">运输</th>
                  <th className="p-2">货权 / 风险</th>
                  <th className="p-2">预计到港</th>
                  <th className="p-2 text-right">计划</th>
                  <th className="p-2 text-right">已发</th>
                  <th className="p-2 text-right">申报货值</th>
                  <th className="p-2 text-right">成本预估</th>
                  <th className="p-2">状态</th>
                  <th className="p-2">操作</th>
                </tr>
              </thead>
              <tbody>
                {rows.map((r) => (
                  <tr key={r.id} className="border-b last:border-0">
                    <td className="p-2">
                      <button
                        className="underline-offset-2 hover:underline"
                        onClick={() => setDetailId(r.id)}
                      >
                        {r.shipmentCode}
                      </button>
                      <div className="text-muted-foreground text-xs">{moment(r.updatedAt)}</div>
                    </td>
                    <td className="p-2">
                      {r.originWarehouseCode} → {r.destinationWarehouseCode}
                      <div className="text-muted-foreground text-xs">
                        目的国 {r.destinationCountryCode}
                        {r.bookingNo ? ` · 订舱 ${r.bookingNo}` : ''}
                      </div>
                    </td>
                    <td className="p-2">
                      {r.transportModeLabel}
                      {r.carrierName ? (
                        <div className="text-muted-foreground text-xs">{r.carrierName}</div>
                      ) : null}
                    </td>
                    <td className="p-2">
                      {r.incotermLabel}
                      <div className="text-muted-foreground text-xs">{r.riskBearerLabel}</div>
                    </td>
                    <td className="p-2">{moment(r.expectedArriveAt)}</td>
                    <td className="p-2 text-right">{fmt(r.plannedQuantity)}</td>
                    <td className="p-2 text-right">
                      {fmt(r.shippedQuantity)}
                      {r.transitLegCount > 0 ? (
                        <div className="text-muted-foreground text-xs">
                          {r.transitLegCount} 条在途腿
                        </div>
                      ) : null}
                    </td>
                    <td className="p-2 text-right">{money(r.declaredValue, r.currency)}</td>
                    <td className="p-2 text-right">{money(r.totalCost, r.currency)}</td>
                    <td className="p-2">{r.statusLabel}</td>
                    <td className="p-2">
                      <div className="flex flex-wrap gap-1">
                        <Button size="sm" variant="ghost" onClick={() => setDetailId(r.id)}>
                          详情
                        </Button>
                        {shipmentActions(r.status).includes('confirm') && (
                          <Button
                            size="sm"
                            variant="outline"
                            onClick={() => void act(r, 'confirm')}
                          >
                            确认
                          </Button>
                        )}
                        {shipmentActions(r.status).includes('dispatch') && (
                          <Button size="sm" onClick={() => void act(r, 'dispatch')}>
                            <Ship className="size-3.5" />
                            发出
                          </Button>
                        )}
                        {shipmentActions(r.status).includes('cancel') && (
                          <Button size="sm" variant="ghost" onClick={() => void act(r, 'cancel')}>
                            取消
                          </Button>
                        )}
                      </div>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          <div className="mt-3 flex items-center justify-between text-sm">
            <span className="text-muted-foreground">
              第 {(data?.page ?? 0) + 1} 页 / 共 {data?.total ?? 0} 张
            </span>
            <div className="flex gap-2">
              <Button
                size="sm"
                variant="outline"
                disabled={(data?.page ?? 0) === 0}
                onClick={() => setParams((p) => ({ ...p, page: Math.max(0, (p.page ?? 0) - 1) }))}
              >
                上一页
              </Button>
              <Button
                size="sm"
                variant="outline"
                disabled={((data?.page ?? 0) + 1) * (data?.size ?? 20) >= (data?.total ?? 0)}
                onClick={() => setParams((p) => ({ ...p, page: (p.page ?? 0) + 1 }))}
              >
                下一页
              </Button>
            </div>
          </div>
        </SectionCard>
      )}

      {detailId && (
        <DetailModal
          shipmentId={detailId}
          onClose={() => setDetailId(null)}
          onChanged={() => void load()}
        />
      )}

      {creating && (
        <Modal open onOpenChange={(open) => !open && setCreating(false)}>
          <ModalContent className="max-w-4xl">
            <ModalHeader>
              <ModalTitle>新建头程发运单</ModalTitle>
              <ModalDescription>
                草稿不动任何账。发出时才会向 7.5.1.2 登记在途腿（不扣在手库存）。
              </ModalDescription>
            </ModalHeader>
            <div className="space-y-4">
              <div className="grid gap-3 md:grid-cols-3">
                <label className="flex flex-col gap-1 text-sm">
                  <span className="text-muted-foreground">起运仓（国内）</span>
                  <select
                    className="rounded-md border px-3 py-2"
                    value={form.originWarehouseId}
                    onChange={(e) => setForm((f) => ({ ...f, originWarehouseId: e.target.value }))}
                  >
                    <option value="">请选择</option>
                    {warehouses.map((w) => (
                      <option key={w.id} value={w.id}>
                        {w.code} {w.name}
                      </option>
                    ))}
                  </select>
                </label>
                <label className="flex flex-col gap-1 text-sm">
                  <span className="text-muted-foreground">目的仓（海外）</span>
                  <select
                    className="rounded-md border px-3 py-2"
                    value={form.destinationWarehouseId}
                    onChange={(e) =>
                      setForm((f) => ({ ...f, destinationWarehouseId: e.target.value }))
                    }
                  >
                    <option value="">请选择</option>
                    {warehouses.map((w) => (
                      <option key={w.id} value={w.id}>
                        {w.code} {w.name}
                      </option>
                    ))}
                  </select>
                </label>
                <label className="flex flex-col gap-1 text-sm">
                  <span className="text-muted-foreground">目的地国家</span>
                  <input
                    className="rounded-md border px-3 py-2"
                    value={form.destinationCountryCode}
                    onChange={(e) =>
                      setForm((f) => ({
                        ...f,
                        destinationCountryCode: e.target.value.toUpperCase(),
                      }))
                    }
                  />
                </label>
                <label className="flex flex-col gap-1 text-sm">
                  <span className="text-muted-foreground">目的地名称</span>
                  <input
                    className="rounded-md border px-3 py-2"
                    value={form.destinationName}
                    onChange={(e) => setForm((f) => ({ ...f, destinationName: e.target.value }))}
                  />
                </label>
                <label className="flex flex-col gap-1 text-sm">
                  <span className="text-muted-foreground">货权法人</span>
                  <select
                    className="rounded-md border px-3 py-2"
                    value={form.legalEntityId}
                    onChange={(e) => setForm((f) => ({ ...f, legalEntityId: e.target.value }))}
                  >
                    <option value="">请选择</option>
                    {legalEntities.map((le) => (
                      <option key={le.id} value={le.id}>
                        {le.code} {le.name}
                      </option>
                    ))}
                  </select>
                </label>
                <label className="flex flex-col gap-1 text-sm">
                  <span className="text-muted-foreground">运输方式</span>
                  <select
                    className="rounded-md border px-3 py-2"
                    value={form.transportMode}
                    onChange={(e) => setForm((f) => ({ ...f, transportMode: e.target.value }))}
                  >
                    {Object.entries(transportModeLabels).map(([value, label]) => (
                      <option key={value} value={value}>
                        {label}
                      </option>
                    ))}
                  </select>
                </label>
                <label className="flex flex-col gap-1 text-sm">
                  <span className="text-muted-foreground">贸易术语（成本转嫁）</span>
                  <select
                    className="rounded-md border px-3 py-2"
                    value={form.incoterm}
                    onChange={(e) => {
                      const incoterm = e.target.value;
                      const next = allowedRiskBearers(caps, incoterm);
                      setForm((f) => ({
                        ...f,
                        incoterm,
                        riskBearer: next.includes(f.riskBearer)
                          ? f.riskBearer
                          : (next[0] ?? 'SELLER'),
                      }));
                    }}
                  >
                    {(caps?.incoterms ?? []).map((i) => (
                      <option key={i.value} value={i.value}>
                        {i.label}
                      </option>
                    ))}
                  </select>
                </label>
                <label className="flex flex-col gap-1 text-sm">
                  <span className="text-muted-foreground">风险承担方</span>
                  <select
                    className="rounded-md border px-3 py-2"
                    value={form.riskBearer}
                    onChange={(e) => setForm((f) => ({ ...f, riskBearer: e.target.value }))}
                  >
                    {bearers.map((b) => (
                      <option key={b} value={b}>
                        {riskBearerLabel(b)}
                      </option>
                    ))}
                  </select>
                </label>
                <label className="flex flex-col gap-1 text-sm">
                  <span className="text-muted-foreground">承运商</span>
                  <input
                    className="rounded-md border px-3 py-2"
                    value={form.carrierName}
                    onChange={(e) => setForm((f) => ({ ...f, carrierName: e.target.value }))}
                  />
                </label>
                <label className="flex flex-col gap-1 text-sm">
                  <span className="text-muted-foreground">订舱号 / 运单号</span>
                  <input
                    className="rounded-md border px-3 py-2"
                    value={form.bookingNo}
                    onChange={(e) => setForm((f) => ({ ...f, bookingNo: e.target.value }))}
                  />
                </label>
                <label className="flex flex-col gap-1 text-sm">
                  <span className="text-muted-foreground">船名 / 航班号</span>
                  <input
                    className="rounded-md border px-3 py-2"
                    value={form.vesselOrFlightNo}
                    onChange={(e) => setForm((f) => ({ ...f, vesselOrFlightNo: e.target.value }))}
                  />
                </label>
                <label className="flex flex-col gap-1 text-sm">
                  <span className="text-muted-foreground">计划开船 / 起飞</span>
                  <input
                    type="datetime-local"
                    className="rounded-md border px-3 py-2"
                    value={form.departAt}
                    onChange={(e) => setForm((f) => ({ ...f, departAt: e.target.value }))}
                  />
                </label>
                <label className="flex flex-col gap-1 text-sm">
                  <span className="text-muted-foreground">预计到港（确认前必填）</span>
                  <input
                    type="datetime-local"
                    className="rounded-md border px-3 py-2"
                    value={form.expectedArriveAt}
                    onChange={(e) => setForm((f) => ({ ...f, expectedArriveAt: e.target.value }))}
                  />
                </label>
                <label className="flex flex-col gap-1 text-sm">
                  <span className="text-muted-foreground">币种</span>
                  <input
                    className="rounded-md border px-3 py-2"
                    value={form.currency}
                    onChange={(e) =>
                      setForm((f) => ({ ...f, currency: e.target.value.toUpperCase() }))
                    }
                  />
                </label>
                {/* 四个成本列共用一个渲染：键名用 union 显式列出，
                    写回时逐个 switch —— `{ ...f, [key]: v }` 里的 key 是计算属性，
                    TS 推不出新对象的形状，会把 setForm 的类型收窄坏。 */}
                {COST_FIELDS.map(([key, label]) => (
                  <label key={key} className="flex flex-col gap-1 text-sm">
                    <span className="text-muted-foreground">{label}</span>
                    <input
                      className="rounded-md border px-3 py-2"
                      value={form[key]}
                      onChange={(e) => {
                        const value = e.target.value;
                        setForm((f) => {
                          switch (key) {
                            case 'freightCost':
                              return { ...f, freightCost: value };
                            case 'insuranceCost':
                              return { ...f, insuranceCost: value };
                            case 'dutyEstimate':
                              return { ...f, dutyEstimate: value };
                            case 'handlingCost':
                              return { ...f, handlingCost: value };
                          }
                        });
                      }}
                    />
                  </label>
                ))}
                <label className="flex flex-col gap-1 text-sm">
                  <span className="text-muted-foreground">报关行</span>
                  <input
                    className="rounded-md border px-3 py-2"
                    value={form.customsBroker}
                    onChange={(e) => setForm((f) => ({ ...f, customsBroker: e.target.value }))}
                  />
                </label>
              </div>

              <div className="space-y-2">
                <div className="flex items-center justify-between">
                  <h3 className="text-sm font-medium">货物与批次（来自 8.2.1.1 已收货批次）</h3>
                  <div className="flex gap-2">
                    <Button size="sm" variant="outline" onClick={() => void loadCandidates()}>
                      刷新可发批次
                    </Button>
                    <Button
                      size="sm"
                      variant="ghost"
                      onClick={() => setDraftLines((p) => [...p, emptyDraft()])}
                    >
                      空白行
                    </Button>
                  </div>
                </div>
                {!goodsReceiptSource?.available && (
                  <p className="text-destructive text-sm">
                    货物来源「8.2.1.1 收货单」当前不可用：
                    {goodsReceiptSource?.note ?? '能力数据加载中'}
                  </p>
                )}
                {candidates && candidates.length === 0 && (
                  <p className="text-muted-foreground text-sm">
                    没有可发批次了 —— 要么还没收货，要么这一批已经被发运单发完。
                  </p>
                )}
                {candidates && candidates.length > 0 && (
                  <div className="max-h-48 overflow-y-auto rounded-md border">
                    <table className="w-full text-xs">
                      <thead>
                        <tr className="text-muted-foreground border-b text-left">
                          <th className="p-2">批次</th>
                          <th className="p-2">来源</th>
                          <th className="p-2 text-right">实收</th>
                          <th className="p-2 text-right">已发</th>
                          <th className="p-2 text-right">还能发</th>
                          <th className="p-2" />
                        </tr>
                      </thead>
                      <tbody>
                        {candidates.map((c) => (
                          <tr key={c.receiptEventLineId} className="border-b last:border-0">
                            <td className="p-2">
                              {c.skuCode} {c.skuName}
                              <div className="text-muted-foreground">
                                {c.qualityStatusName}
                                {c.lotCode ? ` · ${c.lotCode}` : ' · 无批次'}
                                {c.lotExpiryDate ? ` · 效期 ${c.lotExpiryDate}` : ''}
                              </div>
                            </td>
                            <td className="p-2">
                              {c.receiptCode}
                              <div className="text-muted-foreground">{c.waybillNo}</div>
                            </td>
                            <td className="p-2 text-right">{fmt(c.receivedQuantity)}</td>
                            <td className="p-2 text-right">{fmt(c.alreadyShippedQuantity)}</td>
                            <td className="p-2 text-right">{fmt(c.shippableQuantity)}</td>
                            <td className="p-2">
                              <Button size="sm" variant="ghost" onClick={() => addCandidate(c)}>
                                选入
                              </Button>
                            </td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  </div>
                )}

                {draftLines
                  .filter((l) => l.sourceReceiptEventLineId)
                  .map((l) => (
                    <div key={l.key} className="rounded-md border p-3 text-sm">
                      <div className="flex items-center justify-between">
                        <span>
                          {l.skuCode} · {l.qualityStatusName}
                          {l.lotCode ? ` · 批次 ${l.lotCode}` : ''}
                        </span>
                        <Button
                          size="sm"
                          variant="ghost"
                          onClick={() =>
                            setDraftLines((p) =>
                              p.filter((x) => x.key !== l.key).concat(emptyDraft()),
                            )
                          }
                        >
                          移除
                        </Button>
                      </div>
                      <div className="mt-2 grid gap-2 md:grid-cols-4">
                        <label className="flex flex-col gap-1">
                          <span className="text-muted-foreground">计划发运数量</span>
                          <input
                            className="rounded-md border px-2 py-1"
                            value={l.plannedQuantity}
                            onChange={(e) =>
                              setDraftLines((p) =>
                                p.map((x) =>
                                  x.key === l.key ? { ...x, plannedQuantity: e.target.value } : x,
                                ),
                              )
                            }
                          />
                        </label>
                        <label className="flex flex-col gap-1">
                          <span className="text-muted-foreground">申报品名（必填）</span>
                          <input
                            className="rounded-md border px-2 py-1"
                            value={l.declaredItemName}
                            onChange={(e) =>
                              setDraftLines((p) =>
                                p.map((x) =>
                                  x.key === l.key ? { ...x, declaredItemName: e.target.value } : x,
                                ),
                              )
                            }
                          />
                        </label>
                        <label className="flex flex-col gap-1">
                          <span className="text-muted-foreground">
                            HS 编码（字典未交付，自由填）
                          </span>
                          <input
                            className="rounded-md border px-2 py-1"
                            value={l.hsCode}
                            onChange={(e) =>
                              setDraftLines((p) =>
                                p.map((x) =>
                                  x.key === l.key ? { ...x, hsCode: e.target.value } : x,
                                ),
                              )
                            }
                          />
                        </label>
                        <label className="flex flex-col gap-1">
                          <span className="text-muted-foreground">原产国</span>
                          <input
                            className="rounded-md border px-2 py-1"
                            value={l.originCountryCode}
                            onChange={(e) =>
                              setDraftLines((p) =>
                                p.map((x) =>
                                  x.key === l.key
                                    ? { ...x, originCountryCode: e.target.value.toUpperCase() }
                                    : x,
                                ),
                              )
                            }
                          />
                        </label>
                        <label className="flex flex-col gap-1">
                          <span className="text-muted-foreground">申报单价</span>
                          <input
                            className="rounded-md border px-2 py-1"
                            value={l.declaredUnitValue}
                            onChange={(e) =>
                              setDraftLines((p) =>
                                p.map((x) =>
                                  x.key === l.key ? { ...x, declaredUnitValue: e.target.value } : x,
                                ),
                              )
                            }
                          />
                        </label>
                        <label className="flex flex-col gap-1">
                          <span className="text-muted-foreground">申报货值</span>
                          <input
                            className="rounded-md border px-2 py-1"
                            value={l.declaredValue}
                            onChange={(e) =>
                              setDraftLines((p) =>
                                p.map((x) =>
                                  x.key === l.key ? { ...x, declaredValue: e.target.value } : x,
                                ),
                              )
                            }
                          />
                        </label>
                        <label className="flex flex-col gap-1">
                          <span className="text-muted-foreground">毛重 kg</span>
                          <input
                            className="rounded-md border px-2 py-1"
                            value={l.grossWeightKg}
                            onChange={(e) =>
                              setDraftLines((p) =>
                                p.map((x) =>
                                  x.key === l.key ? { ...x, grossWeightKg: e.target.value } : x,
                                ),
                              )
                            }
                          />
                        </label>
                        <label className="flex flex-col gap-1">
                          <span className="text-muted-foreground">净重 kg</span>
                          <input
                            className="rounded-md border px-2 py-1"
                            value={l.netWeightKg}
                            onChange={(e) =>
                              setDraftLines((p) =>
                                p.map((x) =>
                                  x.key === l.key ? { ...x, netWeightKg: e.target.value } : x,
                                ),
                              )
                            }
                          />
                        </label>
                        <label className="flex flex-col gap-1">
                          <span className="text-muted-foreground">体积 m³</span>
                          <input
                            className="rounded-md border px-2 py-1"
                            value={l.volumeCbm}
                            onChange={(e) =>
                              setDraftLines((p) =>
                                p.map((x) =>
                                  x.key === l.key ? { ...x, volumeCbm: e.target.value } : x,
                                ),
                              )
                            }
                          />
                        </label>
                        <label className="flex flex-col gap-1">
                          <span className="text-muted-foreground">箱数</span>
                          <input
                            className="rounded-md border px-2 py-1"
                            value={l.cartonCount}
                            onChange={(e) =>
                              setDraftLines((p) =>
                                p.map((x) =>
                                  x.key === l.key ? { ...x, cartonCount: e.target.value } : x,
                                ),
                              )
                            }
                          />
                        </label>
                      </div>
                    </div>
                  ))}
              </div>
              {formError && <p className="text-destructive text-sm">{formError}</p>}
            </div>
            <ModalFooter>
              <Button variant="ghost" onClick={() => setCreating(false)}>
                取消
              </Button>
              <Button onClick={() => void submit()} disabled={submitting}>
                {submitting ? '提交中…' : '建单（草稿）'}
              </Button>
            </ModalFooter>
          </ModalContent>
        </Modal>
      )}
    </div>
  );
}

function DetailModal({
  shipmentId,
  onClose,
  onChanged,
}: {
  shipmentId: string;
  onClose: () => void;
  onChanged: () => void;
}) {
  const [detail, setDetail] = useState<Awaited<ReturnType<typeof fetchFirstMileDetail>> | null>(
    null,
  );
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const load = useCallback(async () => {
    setError(null);
    try {
      setDetail(await fetchFirstMileDetail(shipmentId));
    } catch (e) {
      setError(errorText(e));
    }
  }, [shipmentId]);

  useEffect(() => {
    void load();
  }, [load]);

  const actions = detail ? shipmentActions(detail.shipment.status) : [];
  const run = async (kind: 'confirm' | 'dispatch' | 'cancel') => {
    if (!detail) return;
    setBusy(true);
    setError(null);
    try {
      const row = detail.shipment;
      if (kind === 'cancel') {
        const reason = window.prompt('取消原因（必填）');
        if (!reason?.trim()) {
          setError('取消必须给原因');
          return;
        }
        await cancelFirstMileShipment(row.id, {
          expectedVersion: row.version,
          reason: reason.trim(),
          notes: '',
        });
      } else if (kind === 'confirm') {
        await confirmFirstMileShipment(row.id, {
          expectedVersion: row.version,
          departAt: row.departAt,
          expectedArriveAt: row.expectedArriveAt,
          notes: '',
        });
      } else {
        // lines 传空数组 = 全部按计划量整行发出（后端语义，不是「什么都不发」）。
        await dispatchFirstMileShipment(row.id, {
          expectedVersion: row.version,
          shippedAt: row.departAt,
          notes: '',
          lines: [],
        });
      }
      await load();
      onChanged();
    } catch (e) {
      setError(errorText(e));
    } finally {
      setBusy(false);
    }
  };

  return (
    <Modal open onOpenChange={(open) => !open && onClose()}>
      <ModalContent className="max-w-4xl">
        <ModalHeader>
          <ModalTitle>{detail?.shipment.shipmentCode ?? '头程发运单详情'}</ModalTitle>
          <ModalDescription>
            {detail
              ? `${detail.shipment.originWarehouseName} → ${detail.shipment.destinationWarehouseName}（${detail.shipment.destinationCountryCode}）· ${detail.shipment.incotermLabel} · ${detail.shipment.riskBearerLabel}`
              : '正在加载…'}
          </ModalDescription>
        </ModalHeader>
        <div className="space-y-3">
          {error && (
            <div className="flex items-start gap-2">
              <TriangleAlert className="text-destructive mt-0.5 size-5" />
              <p className="text-destructive text-sm">{error}</p>
            </div>
          )}
          {!detail && !error && <p className="text-muted-foreground text-sm">正在加载详情…</p>}
          {detail && (
            <>
              <div className="grid gap-2 text-sm md:grid-cols-3">
                <div>
                  <span className="text-muted-foreground">状态</span>
                  <div>{detail.shipment.statusLabel}</div>
                </div>
                <div>
                  <span className="text-muted-foreground">计划开船</span>
                  <div>{moment(detail.shipment.departAt)}</div>
                </div>
                <div>
                  <span className="text-muted-foreground">预计到港</span>
                  <div>{moment(detail.shipment.expectedArriveAt)}</div>
                </div>
                <div>
                  <span className="text-muted-foreground">来源</span>
                  <div>
                    {detail.shipment.sourceTypeLabel} · {detail.shipment.sourceRefCode || '—'}
                  </div>
                </div>
                <div>
                  <span className="text-muted-foreground">成本预估</span>
                  <div>{money(detail.shipment.totalCost, detail.shipment.currency)}</div>
                </div>
                <div>
                  <span className="text-muted-foreground">申报货值</span>
                  <div>{money(detail.shipment.declaredValue, detail.shipment.currency)}</div>
                </div>
                {detail.shipment.cancelReason ? (
                  <div>
                    <span className="text-muted-foreground">取消原因</span>
                    <div>{detail.shipment.cancelReason}</div>
                  </div>
                ) : null}
              </div>

              <p className="bg-muted/40 text-muted-foreground rounded-md p-2 text-xs">
                {detail.titleTransferNote}
              </p>

              <div>
                <h3 className="text-sm font-medium">明细（批次逐行可见）</h3>
                <ul className="mt-1 space-y-1 text-sm">
                  {detail.lines.map((l) => (
                    <li key={l.id} className="rounded-md border p-2">
                      {lineCaption(l)}
                      <div className="text-muted-foreground text-xs">
                        HS {l.hsCode || '未填'} · 品牌 {l.brand || '未填'} · 原产国{' '}
                        {l.originCountryCode} · 申报货值 {money(l.declaredValue)}
                        {l.transitLegId
                          ? ` · 在途腿 ${l.transitLegStatus ?? '已登记'}`
                          : ' · 未登记在途腿'}
                      </div>
                    </li>
                  ))}
                </ul>
              </div>

              <div>
                <h3 className="text-sm font-medium">发货分录（在途腿）</h3>
                {detail.transitLegs.length === 0 ? (
                  <p className="text-muted-foreground text-sm">
                    还没有在途腿 —— 发货分录在「发出」那一刻由 7.5.1.2 登记。
                  </p>
                ) : (
                  <ul className="mt-1 space-y-1 text-sm">
                    {detail.transitLegs.map((g) => {
                      const check = legQuantityText(g);
                      return (
                        <li key={g.id} className="rounded-md border p-2">
                          第 {g.lineNo} 行 {g.skuCode} {g.skuName}：{check.text}
                          <div className="text-muted-foreground text-xs">
                            锚点 {g.sourceSystem}/{g.sourceReference} · 承运 {g.carrierName || '—'}{' '}
                            · 运单 {g.trackingNo || '—'} · 预计到港 {moment(g.expectedArriveAt)}
                          </div>
                        </li>
                      );
                    })}
                  </ul>
                )}
                <p className="bg-muted/40 text-muted-foreground mt-1 rounded-md p-2 text-xs">
                  {detail.transitNote}
                </p>
              </div>

              <div>
                <h3 className="text-sm font-medium">事件时间线</h3>
                <ul className="text-muted-foreground mt-1 space-y-1 text-xs">
                  {detail.events.map((e) => (
                    <li key={e.id}>
                      {moment(e.occurredAt)} · {e.kind}
                      {e.fromStatus && e.toStatus && e.fromStatus !== e.toStatus
                        ? `（${e.fromStatus} → ${e.toStatus}）`
                        : ''}
                      {e.reason ? ` · ${e.reason}` : ''}
                    </li>
                  ))}
                </ul>
              </div>

              {actions.length === 0 && (
                <p className="text-muted-foreground text-sm">
                  {detail.shipment.status === 'DISPATCHED'
                    ? '已发出是终态：货权已转移、在途腿已在 7.5.1.2 名下。要改这批货请走 7.5.1.2 的差异登记。'
                    : '已取消是终态，不再接受任何动作。'}
                </p>
              )}
            </>
          )}
        </div>
        <ModalFooter>
          <Button variant="ghost" onClick={onClose}>
            关闭
          </Button>
          {actions.includes('confirm') && (
            <Button variant="outline" disabled={busy} onClick={() => void run('confirm')}>
              确认（申报齐备 · 已订舱）
            </Button>
          )}
          {actions.includes('dispatch') && (
            <Button disabled={busy} onClick={() => void run('dispatch')}>
              <Ship className="size-4" />
              发出（转在途，不扣在手）
            </Button>
          )}
          {actions.includes('cancel') && (
            <Button variant="ghost" disabled={busy} onClick={() => void run('cancel')}>
              取消
            </Button>
          )}
        </ModalFooter>
      </ModalContent>
    </Modal>
  );
}
