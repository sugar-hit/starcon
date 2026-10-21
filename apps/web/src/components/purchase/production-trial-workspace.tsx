'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import { Factory, PackageSearch, Plus, RefreshCw, TriangleAlert } from 'lucide-react';
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
  type BadgeProps,
} from '@starcon/ui';
import type {
  ProductionTrialBomVersionCandidateRow,
  ProductionTrialCapabilities,
  ProductionTrialCostView,
  ProductionTrialOptions,
  ProductionTrialOrderDetail,
  ProductionTrialOrderPage,
  ProductionTrialOrderRow,
  ProductionTrialSummary,
} from '../../../../../packages/api-contracts/src/index';
import {
  addProductionTrialCostLine,
  createProductionTrialInspectionItem,
  createProductionTrialOrder,
  deleteProductionTrialCostLine,
  fetchProductionTrialBomVersionCandidates,
  fetchProductionTrialCapabilities,
  fetchProductionTrialCostComparison,
  fetchProductionTrialOptions,
  fetchProductionTrialOrder,
  fetchProductionTrialOrders,
  fetchProductionTrialSummary,
  formatMoney,
  formatQuantity,
  formatVarianceRate,
  formatYieldRate,
  judgeProductionTrialInspectionItem,
  recordProductionTrialMassProductionRecommendation,
  recordProductionTrialResult,
  releaseProductionTrialRequirement,
  submitProductionTrialRequirement,
  transitionProductionTrialOrder,
  type ProductionTrialCostType,
  type ProductionTrialOrderParams,
  type ProductionTrialMassProductionDecision,
  type ProductionTrialStatus,
} from './production-trial-model';
import { errorText } from '../inventory/inventory-model';

/**
 * 6.6.2.1 试产单工作区。
 *
 * <p>蓝图：「管理试产：小批量试产（数量、BOM 版本、组件需求、产线/加工厂、质检标准、试产成本与良率），
 * 试产结果记录并对比量产目标，输出转量产建议。」输出「试产单 + 组件领用需求（→ 7 库存）；良率与成本结果；转量产建议」。
 *
 * <p>界面纪律：
 *
 * <ul>
 *   <li><b>组件领用需求是「需求」，不是「已领用」</b>：页面**不出现任何「已扣库存 / 已领用」**的说法 ——
 *       本模块**不扣库存**，真实领用由 warehouse 侧 8.4.1 组装单承接（常驻横幅写明）。</li>
 *   <li><b>良率是比例</b>：后端给的是 {@code 0.95}（不是 95）。页面显示成 {@code 95.00%} 时那只是
 *       <b>同一个值的显示格式</b>，tooltip 里给出原始比例；横幅里另有一行口径说明。</li>
 *   <li><b>成本不跨币种加成一个总数</b>：概览逐币种列；不可比时显示「不可比」，
 *       <b>不显示 0</b>（量产目标成本取不到 ≠ 目标成本是 0）。</li>
 *   <li><b>做不到的事写在页面上</b>：库存扣减（归 8.4.1 组装单）、转量产建档（归 6.6.2.2）、
 *       汇率（归 5.2.2.1）—— 常驻横幅，不假装可用。</li>
 *   <li><b>转量产建议只是建议</b>：6.6.2.2 未开发，页面明写「这里只出建议，不建 SKU / 不建量产单」。</li>
 *   <li><b>状态只给合法的去向</b>：下拉来自后端的 {@code allowedTargets}，页面不维护第二份状态机。</li>
 *   <li>加载 / 空 / 错误三态齐全。</li>
 * </ul>
 */

const PAGE_SIZE = 20;

/** 成本类型下拉（**无「其它」**：留一个「其它」桶就永远答不出「钱花在哪」）。 */
const COST_TYPES: { value: ProductionTrialCostType; label: string }[] = [
  { value: 'MATERIAL', label: '物料费' },
  { value: 'PROCESSING', label: '加工费' },
  { value: 'MOLD', label: '模具费' },
  { value: 'INSPECTION', label: '检品费' },
  { value: 'OVERHEAD', label: '制造费用' },
];

const DECISIONS: { value: ProductionTrialMassProductionDecision; label: string }[] = [
  { value: 'GO', label: '建议转量产' },
  { value: 'NO_GO', label: '暂不建议转量产' },
];

type Tone = NonNullable<BadgeProps['tone']>;

/** 试产单状态 → 徽标色调（tone 跟语义挂钩，不是跟颜色挂钩）。 */
function statusTone(status: string): Tone {
  if (status === 'COMPLETED') return 'success';
  if (status === 'CANCELLED') return 'danger';
  if (status === 'IN_PROGRESS') return 'info';
  if (status === 'SCHEDULED') return 'warning';
  return 'neutral';
}

/** 组件领用需求状态 → 徽标色调。 */
function requirementTone(status: string): Tone {
  if (status === 'RELEASED') return 'success';
  if (status === 'CANCELLED') return 'danger';
  if (status === 'SUBMITTED') return 'info';
  return 'neutral';
}

export function ProductionTrialWorkspace() {
  const [caps, setCaps] = useState<ProductionTrialCapabilities | null>(null);
  const [options, setOptions] = useState<ProductionTrialOptions | null>(null);
  const [summary, setSummary] = useState<ProductionTrialSummary | null>(null);
  const [orders, setOrders] = useState<ProductionTrialOrderPage | null>(null);
  const [status, setStatus] = useState('');
  const [keyword, setKeyword] = useState('');
  const [page, setPage] = useState(0);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [creating, setCreating] = useState(false);
  const [detailId, setDetailId] = useState<string | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const params: ProductionTrialOrderParams = { page, size: PAGE_SIZE };
      if (status.trim()) params.status = status.trim();
      if (keyword.trim()) params.keyword = keyword.trim();
      const [capabilities, opts, stat, pageResult] = await Promise.all([
        fetchProductionTrialCapabilities(),
        fetchProductionTrialOptions(),
        fetchProductionTrialSummary(),
        fetchProductionTrialOrders(params),
      ]);
      setCaps(capabilities);
      setOptions(opts);
      setSummary(stat);
      setOrders(pageResult);
    } catch (e) {
      setError(errorText(e));
    } finally {
      setLoading(false);
    }
  }, [status, keyword, page]);

  useEffect(() => {
    void load();
  }, [load]);

  const rows = orders?.rows ?? [];
  const total = orders?.total ?? 0;
  const pageCount = Math.max(1, Math.ceil(total / PAGE_SIZE));

  const gaps = useMemo(() => {
    if (!caps) return [];
    const list: string[] = [];
    if (!caps.inventoryDeductionAvailable)
      list.push(`库存扣减归 ${caps.inventoryDeductionOwnerModule} 管：${caps.inventoryDeductionNote}`);
    if (!caps.massProductionHandoffAvailable)
      list.push(`转量产建档归 ${caps.massProductionHandoffOwnerModule} 管：${caps.massProductionHandoffNote}`);
    if (!caps.exchangeRateAvailable)
      list.push(`汇率归 ${caps.exchangeRateOwnerModule} 管：${caps.exchangeRateNote}`);
    return list;
  }, [caps]);

  const stats = useMemo(() => {
    const byStatus = new Map((summary?.statuses ?? []).map((row) => [row.status, row.count]));
    return {
      total: summary?.totalTrials ?? 0,
      inProgress: byStatus.get('IN_PROGRESS') ?? 0,
      completed: byStatus.get('COMPLETED') ?? 0,
      requirements: summary?.totalRequirements ?? 0,
      pendingRequirements: summary?.pendingRequirements ?? 0,
      recommendations: summary?.recommendationCount ?? 0,
    };
  }, [summary]);

  return (
    <div className="flex flex-col gap-4">
      <PageHeader
        title="试产单"
        description="管理试产：小批量试产（数量、BOM 版本、组件需求、产线 / 加工厂、质检标准、试产成本与良率），试产结果记录并对比量产目标，输出转量产建议。"
        actions={
          <div className="flex gap-2">
            <Button variant="ghost" onClick={() => void load()} disabled={loading}>
              <RefreshCw className={loading ? 'animate-spin' : ''} />
              刷新
            </Button>
            <Button onClick={() => setCreating(true)} disabled={busy}>
              <Plus />
              新建试产单
            </Button>
          </div>
        }
      />

      {/* 口径常驻：本页的边界与缺口必须一直摆在眼前 */}
      <div className="flex items-start gap-2 rounded-md border border-sky-500/40 bg-sky-500/10 p-3 text-sm">
        <PackageSearch className="mt-0.5 size-4 shrink-0" />
        <div className="flex flex-col gap-1">
          <p className="font-medium">本页只出「试产单 + 组件领用需求 + 良率与成本结果 + 转量产建议」</p>
          <p className="text-muted-foreground">
            🔴 组件领用需求是<b>需求</b>，不是「已领用」：本页<b>不扣库存</b> —— 试产消耗真实组件库存，
            领用需走 8 加工单，由 warehouse 侧 8.4.1 组装单承接。
          </p>
          <p className="text-muted-foreground">
            良率按<b>比例</b>存储（0.95 = 95%），页面显示的百分数只是同一份值的显示格式；
            试产成本与量产目标成本<b>只在同一币种内可比</b>，跨币种按币种分组，不做固定汇率折算。
          </p>
          {caps && <p className="text-muted-foreground">{caps.boundaryNote}</p>}
          {gaps.map((gap) => (
            <p key={gap} className="text-muted-foreground">
              🔴 {gap}
            </p>
          ))}
        </div>
      </div>

      {summary && (
        <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-6">
          <StatCard label="试产单总数" value={stats.total} />
          <StatCard label="试产中" value={stats.inProgress} />
          <StatCard label="已完成" value={stats.completed} />
          <StatCard label="组件领用需求" value={stats.requirements} />
          <StatCard label="待提交需求" value={stats.pendingRequirements} />
          <StatCard label="转量产建议" value={stats.recommendations} />
        </div>
      )}

      {summary && summary.costComparison.length > 0 && (
        <SectionCard title="试产成本 vs 量产目标成本（按币种分组，不跨币种相加）">
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead className="text-muted-foreground text-left">
                <tr>
                  <th className="px-2 py-1">币种</th>
                  <th className="px-2 py-1">试产成本</th>
                  <th className="px-2 py-1">量产目标成本</th>
                  <th className="px-2 py-1">差额 / 偏差率</th>
                  <th className="px-2 py-1">口径</th>
                </tr>
              </thead>
              <tbody>
                {summary.costComparison.map((row) => (
                  <tr key={row.currency} className="border-t">
                    <td className="px-2 py-1 font-mono">{row.currency}</td>
                    <td className="px-2 py-1">{formatMoney(row.trialCostTotal, row.currency)}</td>
                    <td className="px-2 py-1">
                      {row.comparable ? formatMoney(row.massTargetCost, row.currency) : '不可比'}
                    </td>
                    <td className="px-2 py-1">
                      {row.comparable
                        ? `${formatMoney(row.varianceAmount, row.currency)}（${formatVarianceRate(row.varianceRate)}）`
                        : '—'}
                    </td>
                    <td className="text-muted-foreground px-2 py-1">{row.note}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </SectionCard>
      )}

      <SectionCard title="筛选">
        <div className="flex flex-wrap items-end gap-3">
          <label className="flex flex-col gap-1 text-sm">
            <span className="text-muted-foreground">状态</span>
            <select
              className="rounded-md border px-2 py-1"
              value={status}
              onChange={(e) => {
                setStatus(e.target.value);
                setPage(0);
              }}
            >
              <option value="">全部状态</option>
              {(options?.statuses ?? []).map((row) => (
                <option key={row.value} value={row.value}>
                  {row.label}
                </option>
              ))}
            </select>
          </label>
          <label className="flex flex-col gap-1 text-sm">
            <span className="text-muted-foreground">关键词</span>
            <Input
              value={keyword}
              placeholder="试产单号 / BOM 版本 / 成品 SKU / 产线 / 加工厂"
              onChange={(e) => {
                setKeyword(e.target.value);
                setPage(0);
              }}
            />
          </label>
          <Button variant="ghost" onClick={() => void load()} disabled={loading}>
            查询
          </Button>
        </div>
      </SectionCard>

      {error && (
        <div className="flex items-start gap-2 rounded-md border border-red-500/40 bg-red-500/10 p-3 text-sm text-red-600">
          <TriangleAlert className="mt-0.5 size-4 shrink-0" />
          <p>{error}</p>
        </div>
      )}

      {loading && <p className="text-muted-foreground text-sm">正在加载试产单…</p>}

      {!loading && rows.length === 0 && !error && (
        <EmptyState
          title="还没有试产单"
          description="当前筛选条件下没有试产单。先点右上角「新建试产单」开一张（要选一个已发布的 BOM 版本），或放宽筛选条件。"
        />
      )}

      {!loading && rows.length > 0 && (
        <SectionCard title={`试产单清单（${total}）`}>
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead className="text-muted-foreground text-left">
                <tr>
                  <th className="px-2 py-1">试产单号</th>
                  <th className="px-2 py-1">BOM 版本 / 成品</th>
                  <th className="px-2 py-1">目标数量</th>
                  <th className="px-2 py-1">产线 / 加工厂</th>
                  <th className="px-2 py-1">状态</th>
                  <th className="px-2 py-1">良率</th>
                  <th className="px-2 py-1">试产成本</th>
                  <th className="px-2 py-1">领用需求</th>
                  <th className="px-2 py-1">转量产建议</th>
                  <th className="px-2 py-1" />
                </tr>
              </thead>
              <tbody>
                {rows.map((row) => (
                  <tr key={row.id} className="border-t">
                    <td className="px-2 py-1 font-mono">{row.trialNo}</td>
                    <td className="px-2 py-1">
                      <div>{row.bomVersionNo}</div>
                      <div className="text-muted-foreground">{row.outputSkuCode}</div>
                    </td>
                    <td className="px-2 py-1">{formatQuantity(row.targetQuantity)}</td>
                    <td className="px-2 py-1">
                      <div>{row.productionLineName || row.productionLineCode || '—'}</div>
                      <div className="text-muted-foreground">{row.factoryName || row.factoryCode || '—'}</div>
                    </td>
                    <td className="px-2 py-1">
                      <Badge tone={statusTone(row.status)}>{row.statusLabel}</Badge>
                    </td>
                    <td className="px-2 py-1" title={row.hasResult ? `比例 ${row.yieldRate}` : undefined}>
                      {row.hasResult ? formatYieldRate(row.yieldRate) : '—'}
                    </td>
                    <td className="px-2 py-1">{formatMoney(row.trialCostTotal, row.currency)}</td>
                    <td className="px-2 py-1">
                      {row.requirementCount === 0
                        ? '—'
                        : `${row.requirementCount - row.pendingRequirementCount}/${row.requirementCount} 已提交`}
                    </td>
                    <td className="px-2 py-1">
                      {row.hasRecommendation ? (
                        <Badge tone={row.recommendationDecision === 'GO' ? 'success' : 'warning'}>
                          {row.recommendationDecisionLabel}
                        </Badge>
                      ) : (
                        '—'
                      )}
                    </td>
                    <td className="px-2 py-1">
                      <Button variant="ghost" onClick={() => setDetailId(row.id)}>
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

      {total > 0 && (
        <div className="flex items-center justify-end gap-2 text-sm">
          <Button variant="ghost" disabled={page <= 0} onClick={() => setPage((p) => Math.max(0, p - 1))}>
            上一页
          </Button>
          <span className="text-muted-foreground">
            第 {page + 1} / {pageCount} 页
          </span>
          <Button
            variant="ghost"
            disabled={page + 1 >= pageCount}
            onClick={() => setPage((p) => p + 1)}
          >
            下一页
          </Button>
        </div>
      )}

      {creating && (
        <CreateTrialModal
          onClose={() => setCreating(false)}
          onCreated={() => {
            setCreating(false);
            void load();
          }}
        />
      )}

      {detailId && (
        <TrialDetailModal
          orderId={detailId}
          onClose={() => setDetailId(null)}
          onChanged={() => void load()}
        />
      )}
    </div>
  );
}

// ================================================================ 新建试产单

function CreateTrialModal({
  onClose,
  onCreated,
}: {
  onClose: () => void;
  onCreated: () => void;
}) {
  const [trialNo, setTrialNo] = useState('');
  const [bomVersionId, setBomVersionId] = useState('');
  const [targetQuantity, setTargetQuantity] = useState('100');
  const [productionLine, setProductionLine] = useState('');
  const [factory, setFactory] = useState('');
  const [qualityStandard, setQualityStandard] = useState('');
  const [currency, setCurrency] = useState('USD');
  const [massTargetCost, setMassTargetCost] = useState('');
  const [requestedAt, setRequestedAt] = useState(() => new Date().toISOString().slice(0, 16));
  const [purpose, setPurpose] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [candidates, setCandidates] = useState<ProductionTrialBomVersionCandidateRow[]>([]);
  const [loadingRefs, setLoadingRefs] = useState(true);

  useEffect(() => {
    void (async () => {
      setLoadingRefs(true);
      try {
        setCandidates(await fetchProductionTrialBomVersionCandidates());
      } catch (e) {
        setError(errorText(e));
      } finally {
        setLoadingRefs(false);
      }
    })();
  }, []);

  const submit = async () => {
    setError(null);
    if (!trialNo.trim()) {
      setError('试产单号必填（租户内唯一）');
      return;
    }
    if (!bomVersionId) {
      setError('必须选择一个 BOM 版本 —— 试产单挂在 BOM 版本上（组件需求量由它算出）');
      return;
    }
    const quantity = Number(targetQuantity);
    if (!Number.isFinite(quantity) || quantity <= 0) {
      setError('目标数量必须大于 0');
      return;
    }
    const target = massTargetCost.trim() === '' ? null : Number(massTargetCost);
    if (target !== null && (!Number.isFinite(target) || target < 0)) {
      setError('量产目标成本不能为负；取不到就留空（留空表示「不可比」，不是 0）');
      return;
    }
    setBusy(true);
    try {
      await createProductionTrialOrder({
        trialNo: trialNo.trim(),
        bomVersionId,
        targetQuantity: quantity,
        productionLineCode: '',
        productionLineName: productionLine.trim(),
        factoryCode: '',
        factoryName: factory.trim(),
        qualityStandardCode: qualityStandard.trim(),
        qualityStandardNote: '',
        currency: currency.trim().toUpperCase(),
        massTargetCost: target,
        requesterId: null,
        requesterName: '',
        requestedAt: new Date(requestedAt).toISOString(),
        plannedStartAt: null,
        plannedEndAt: null,
        developmentSampleOrderId: null,
        developmentSampleOrderNo: null,
        purposeNote: purpose.trim(),
        note: '',
        inspectionItems: [],
      });
      onCreated();
    } catch (e) {
      setError(errorText(e));
    } finally {
      setBusy(false);
    }
  };

  return (
    <Modal open onOpenChange={(open) => !open && onClose()}>
      <ModalContent className="max-w-3xl">
        <ModalHeader>
          <ModalTitle>新建试产单</ModalTitle>
          <ModalDescription>
            一次试产一张单。建单时会按 BOM 版本算出组件领用需求（**需求，不是库存扣减**），
            并建好成本头（一个币种）。BOM 版本必须是**已发布**的版本。
          </ModalDescription>
        </ModalHeader>
        <div className="grid gap-3 md:grid-cols-2">
          <label className="flex flex-col gap-1 text-sm">
            <span className="text-muted-foreground">试产单号 *</span>
            <Input value={trialNo} onChange={(e) => setTrialNo(e.target.value)} />
          </label>
          <label className="flex flex-col gap-1 text-sm">
            <span className="text-muted-foreground">BOM 版本 *</span>
            <select
              className="rounded-md border px-2 py-1"
              value={bomVersionId}
              onChange={(e) => setBomVersionId(e.target.value)}
              disabled={loadingRefs}
            >
              <option value="">{loadingRefs ? '加载中…' : '请选择 BOM 版本'}</option>
              {candidates.map((row) => (
                <option key={row.id} value={row.id}>
                  {row.bomCode} / {row.versionNo} / {row.outputSkuCode}（{row.statusLabel}）
                </option>
              ))}
            </select>
          </label>
          <label className="flex flex-col gap-1 text-sm">
            <span className="text-muted-foreground">目标数量 *</span>
            <Input
              value={targetQuantity}
              onChange={(e) => setTargetQuantity(e.target.value)}
              inputMode="decimal"
            />
          </label>
          <label className="flex flex-col gap-1 text-sm">
            <span className="text-muted-foreground">币种 *</span>
            <Input value={currency} onChange={(e) => setCurrency(e.target.value)} />
          </label>
          <label className="flex flex-col gap-1 text-sm">
            <span className="text-muted-foreground">产线</span>
            <Input value={productionLine} onChange={(e) => setProductionLine(e.target.value)} />
          </label>
          <label className="flex flex-col gap-1 text-sm">
            <span className="text-muted-foreground">加工厂</span>
            <Input value={factory} onChange={(e) => setFactory(e.target.value)} />
          </label>
          <label className="flex flex-col gap-1 text-sm">
            <span className="text-muted-foreground">质检标准编号</span>
            <Input
              value={qualityStandard}
              onChange={(e) => setQualityStandard(e.target.value)}
            />
          </label>
          <label className="flex flex-col gap-1 text-sm">
            <span className="text-muted-foreground">量产目标成本（可留空 = 不可比）</span>
            <Input
              value={massTargetCost}
              onChange={(e) => setMassTargetCost(e.target.value)}
              inputMode="decimal"
            />
          </label>
          <label className="flex flex-col gap-1 text-sm">
            <span className="text-muted-foreground">申请时刻 *</span>
            <Input
              type="datetime-local"
              value={requestedAt}
              onChange={(e) => setRequestedAt(e.target.value)}
            />
          </label>
          <label className="flex col-span-2 flex-col gap-1 text-sm">
            <span className="text-muted-foreground">试产目的</span>
            <Input value={purpose} onChange={(e) => setPurpose(e.target.value)} />
          </label>
        </div>
        {error && <p className="text-sm text-red-600">{error}</p>}
        <ModalFooter>
          <Button variant="ghost" onClick={onClose}>
            取消
          </Button>
          <Button onClick={() => void submit()} disabled={busy || loadingRefs}>
            建单
          </Button>
        </ModalFooter>
      </ModalContent>
    </Modal>
  );
}

// ================================================================ 试产单详情

function TrialDetailModal({
  orderId,
  onClose,
  onChanged,
}: {
  orderId: string;
  onClose: () => void;
  onChanged: () => void;
}) {
  const [detail, setDetail] = useState<ProductionTrialOrderDetail | null>(null);
  const [comparison, setComparison] = useState<{
    currency: string;
    trialCostTotal: number | null;
    massTargetCost: number | null;
    varianceAmount: number | null;
    varianceRate: number | null;
    comparable: boolean;
    note: string;
  } | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const [reason, setReason] = useState('');
  const [assemblyOrderNo, setAssemblyOrderNo] = useState('');
  const [assemblyOrderRefId, setAssemblyOrderRefId] = useState('');
  const [itemCode, setItemCode] = useState('');
  const [itemName, setItemName] = useState('');
  const [itemStandard, setItemStandard] = useState('');
  const [itemCritical, setItemCritical] = useState(false);
  const [outputQuantity, setOutputQuantity] = useState('');
  const [qualifiedQuantity, setQualifiedQuantity] = useState('');
  const [resultConclusion, setResultConclusion] = useState('');
  const [costType, setCostType] = useState<ProductionTrialCostType>('MATERIAL');
  const [costAmount, setCostAmount] = useState('');
  const [costDescription, setCostDescription] = useState('');
  const [decision, setDecision] = useState<ProductionTrialMassProductionDecision>('GO');
  const [rationale, setRationale] = useState('');
  const [conditionNote, setConditionNote] = useState('');
  const [minYield, setMinYield] = useState('0.95');
  const [maxVariance, setMaxVariance] = useState('0.10');

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const data = await fetchProductionTrialOrder(orderId);
      setDetail(data);
      try {
        setComparison(await fetchProductionTrialCostComparison(orderId));
      } catch {
        // 没有成本记录时对比不可得 —— 详情里如实留空，不让整个详情挂掉。
        setComparison(null);
      }
    } catch (e) {
      setError(errorText(e));
    } finally {
      setLoading(false);
    }
  }, [orderId]);

  useEffect(() => {
    void load();
  }, [load]);

  const run = async (work: () => Promise<unknown>) => {
    setBusy(true);
    setError(null);
    try {
      await work();
      await load();
      onChanged();
    } catch (e) {
      setError(errorText(e));
    } finally {
      setBusy(false);
    }
  };

  const order = detail?.order;
  const allowed = detail?.allowedTargets ?? [];
  const cost: ProductionTrialCostView | null = detail?.cost ?? null;

  return (
    <Modal open onOpenChange={(open) => !open && onClose()}>
      <ModalContent className="max-w-4xl">
        <ModalHeader>
          <ModalTitle>试产单详情{order ? ` · ${order.trialNo}` : ''}</ModalTitle>
          <ModalDescription>
            组件领用需求是<b>需求</b>不是库存扣减；良率按比例存储；成本只与<b>同一币种</b>的量产目标成本可比。
          </ModalDescription>
        </ModalHeader>

        {loading && <p className="text-muted-foreground text-sm">正在加载详情…</p>}
        {error && <p className="text-sm text-red-600">{error}</p>}

        {order && (
          <div className="flex flex-col gap-4">
            <SectionCard title="试产单记录">
              <div className="grid gap-2 text-sm sm:grid-cols-2 lg:grid-cols-3">
                <div>
                  <span className="text-muted-foreground">状态：</span>
                  <Badge tone={statusTone(order.status)}>{order.statusLabel}</Badge>
                </div>
                <div>
                  <span className="text-muted-foreground">BOM 版本：</span>
                  {order.bomVersionNo}（{order.bomCode}）
                </div>
                <div>
                  <span className="text-muted-foreground">成品 SKU：</span>
                  {order.outputSkuCode} {order.outputSkuName}
                </div>
                <div>
                  <span className="text-muted-foreground">目标数量：</span>
                  {formatQuantity(order.targetQuantity)}
                </div>
                <div>
                  <span className="text-muted-foreground">产线 / 加工厂：</span>
                  {order.productionLineName || order.productionLineCode || '—'} /{' '}
                  {order.factoryName || order.factoryCode || '—'}
                </div>
                <div>
                  <span className="text-muted-foreground">质检标准：</span>
                  {order.qualityStandardCode || '—'}
                </div>
                <div>
                  <span className="text-muted-foreground">试产成本：</span>
                  {formatMoney(order.trialCostTotal, order.currency)}
                </div>
                <div>
                  <span className="text-muted-foreground">量产目标成本：</span>
                  {order.massTargetCost === null
                    ? '不可比（目标成本不可得）'
                    : formatMoney(order.massTargetCost, order.currency)}
                </div>
                <div>
                  <span className="text-muted-foreground">良率：</span>
                  <span title={order.hasResult ? `比例 ${order.yieldRate}` : undefined}>
                    {order.hasResult ? formatYieldRate(order.yieldRate) : '尚未记录结果'}
                  </span>
                </div>
              </div>
            </SectionCard>

            <SectionCard title="推进状态（只列后端说的合法去向）">
              <div className="flex flex-wrap items-end gap-2">
                <label className="flex flex-col gap-1 text-sm">
                  <span className="text-muted-foreground">目标状态</span>
                  <select
                    className="rounded-md border px-2 py-1"
                    value=""
                    onChange={(e) => e.target.value && setReason(e.target.value)}
                  >
                    <option value="">选择状态</option>
                    {allowed.map((value) => (
                      <option key={value} value={value}>
                        {value}
                      </option>
                    ))}
                  </select>
                </label>
                <label className="flex flex-col gap-1 text-sm">
                  <span className="text-muted-foreground">理由 *</span>
                  <Input value={reason} onChange={(e) => setReason(e.target.value)} />
                </label>
                {allowed.map((value) => (
                  <Button
                    key={value}
                    variant="ghost"
                    disabled={busy || !reason.trim()}
                    onClick={() =>
                      void run(() =>
                        transitionProductionTrialOrder(orderId, {
                          toStatus: value as ProductionTrialStatus,
                          reason: reason.trim(),
                        }),
                      )
                    }
                  >
                    推进到 {value}
                  </Button>
                ))}
              </div>
            </SectionCard>

            <SectionCard title={`组件领用需求（${detail?.componentRequirements.length ?? 0}）`}>
              <p className="text-muted-foreground mb-2 text-sm">
                🔴 这些是<b>需求</b>：本页<b>不扣库存</b>，真实领用由 8.4.1 组装单（warehouse）承接。
                提交需求 ≠ 已领用。
              </p>
              <div className="overflow-x-auto">
                <table className="w-full text-sm">
                  <thead className="text-muted-foreground text-left">
                    <tr>
                      <th className="px-2 py-1">#</th>
                      <th className="px-2 py-1">组件</th>
                      <th className="px-2 py-1">单位用量</th>
                      <th className="px-2 py-1">损耗率</th>
                      <th className="px-2 py-1">需求量</th>
                      <th className="px-2 py-1">状态</th>
                      <th className="px-2 py-1">加工单（8.4.1）</th>
                      <th className="px-2 py-1" />
                    </tr>
                  </thead>
                  <tbody>
                    {(detail?.componentRequirements ?? []).map((row) => (
                      <tr key={row.id} className="border-t">
                        <td className="px-2 py-1">{row.lineNo}</td>
                        <td className="px-2 py-1">
                          <div className="font-mono">{row.skuCode}</div>
                          {row.skuName && <div className="text-muted-foreground">{row.skuName}</div>}
                        </td>
                        <td className="px-2 py-1">{formatQuantity(row.unitQuantity)}</td>
                        <td className="px-2 py-1">{formatQuantity(row.scrapRate)}%</td>
                        <td className="px-2 py-1">{formatQuantity(row.requiredQuantity)}</td>
                        <td className="px-2 py-1">
                          <Badge tone={requirementTone(row.requirementStatus)}>
                            {row.requirementStatusLabel}
                          </Badge>
                        </td>
                        <td className="px-2 py-1">
                          {row.assemblyOrderNo ? (
                            <span className="font-mono">{row.assemblyOrderNo}</span>
                          ) : (
                            <span className="text-muted-foreground">未承接</span>
                          )}
                        </td>
                        <td className="px-2 py-1">
                          {row.requirementStatus === 'DRAFT' && (
                            <Button
                              variant="ghost"
                              disabled={busy}
                              onClick={() =>
                                void run(() => submitProductionTrialRequirement(orderId, row.id))
                              }
                            >
                              提交领用需求
                            </Button>
                          )}
                          {row.requirementStatus === 'SUBMITTED' && (
                            <span className="text-muted-foreground">
                              待 warehouse 承接后回填组装单号
                            </span>
                          )}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
              {detail?.componentRequirements.some((row) => row.requirementStatus === 'SUBMITTED') && (
                <div className="mt-3 flex flex-wrap items-end gap-2">
                  <label className="flex flex-col gap-1 text-sm">
                    <span className="text-muted-foreground">组装单号（8.4.1）</span>
                    <Input
                      value={assemblyOrderNo}
                      onChange={(e) => setAssemblyOrderNo(e.target.value)}
                    />
                  </label>
                  <label className="flex flex-col gap-1 text-sm">
                    <span className="text-muted-foreground">组装单 id</span>
                    <Input
                      value={assemblyOrderRefId}
                      onChange={(e) => setAssemblyOrderRefId(e.target.value)}
                    />
                  </label>
                  <Button
                    variant="ghost"
                    disabled={
                      busy ||
                      !assemblyOrderNo.trim() ||
                      !assemblyOrderRefId.trim() ||
                      !detail?.componentRequirements.some(
                        (row) => row.requirementStatus === 'SUBMITTED',
                      )
                    }
                    onClick={() => {
                      const target = detail?.componentRequirements.find(
                        (row) => row.requirementStatus === 'SUBMITTED',
                      );
                      if (!target) return;
                      void run(() =>
                        releaseProductionTrialRequirement(orderId, target.id, {
                          assemblyOrderRefId: assemblyOrderRefId.trim(),
                          assemblyOrderNo: assemblyOrderNo.trim(),
                          note: '',
                        }),
                      );
                    }}
                  >
                    记录「已由加工单承接」
                  </Button>
                </div>
              )}
            </SectionCard>

            <SectionCard title={`质检标准（${detail?.inspectionItems.length ?? 0}）`}>
              <div className="overflow-x-auto">
                <table className="w-full text-sm">
                  <thead className="text-muted-foreground text-left">
                    <tr>
                      <th className="px-2 py-1">编号</th>
                      <th className="px-2 py-1">检验项</th>
                      <th className="px-2 py-1">标准值</th>
                      <th className="px-2 py-1">关键项</th>
                      <th className="px-2 py-1">判定</th>
                      <th className="px-2 py-1">实测值</th>
                      <th className="px-2 py-1" />
                    </tr>
                  </thead>
                  <tbody>
                    {(detail?.inspectionItems ?? []).map((row) => (
                      <tr key={row.id} className="border-t">
                        <td className="px-2 py-1 font-mono">{row.itemCode}</td>
                        <td className="px-2 py-1">{row.itemName}</td>
                        <td className="px-2 py-1">{row.standardValue || '—'}</td>
                        <td className="px-2 py-1">{row.criticalItem ? '关键项' : '—'}</td>
                        <td className="px-2 py-1">
                          <Badge
                            tone={
                              row.judgment === 'PASS'
                                ? 'success'
                                : row.judgment === 'FAIL'
                                  ? 'danger'
                                  : 'neutral'
                            }
                          >
                            {row.judgmentLabel}
                          </Badge>
                        </td>
                        <td className="px-2 py-1">{row.measuredValue || '—'}</td>
                        <td className="px-2 py-1">
                          <div className="flex gap-1">
                            {row.judgment !== 'PASS' && (
                              <Button
                                variant="ghost"
                                disabled={busy}
                                onClick={() =>
                                  void run(() =>
                                    judgeProductionTrialInspectionItem(orderId, row.id, {
                                      judgment: 'PASS',
                                      measuredValue: row.measuredValue,
                                      note: '',
                                    }),
                                  )
                                }
                              >
                                合格
                              </Button>
                            )}
                            {row.judgment !== 'FAIL' && (
                              <Button
                                variant="ghost"
                                disabled={busy}
                                onClick={() =>
                                  void run(() =>
                                    judgeProductionTrialInspectionItem(orderId, row.id, {
                                      judgment: 'FAIL',
                                      measuredValue: row.measuredValue,
                                      note: '',
                                    }),
                                  )
                                }
                              >
                                不合格
                              </Button>
                            )}
                          </div>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
              <div className="mt-3 flex flex-wrap items-end gap-2">
                <label className="flex flex-col gap-1 text-sm">
                  <span className="text-muted-foreground">检验项编号 *</span>
                  <Input value={itemCode} onChange={(e) => setItemCode(e.target.value)} />
                </label>
                <label className="flex flex-col gap-1 text-sm">
                  <span className="text-muted-foreground">检验项名称 *</span>
                  <Input value={itemName} onChange={(e) => setItemName(e.target.value)} />
                </label>
                <label className="flex flex-col gap-1 text-sm">
                  <span className="text-muted-foreground">标准值</span>
                  <Input value={itemStandard} onChange={(e) => setItemStandard(e.target.value)} />
                </label>
                <label className="flex items-center gap-1 text-sm">
                  <input
                    type="checkbox"
                    checked={itemCritical}
                    onChange={(e) => setItemCritical(e.target.checked)}
                  />
                  <span>关键项</span>
                </label>
                <Button
                  variant="ghost"
                  disabled={busy || !itemCode.trim() || !itemName.trim()}
                  onClick={() =>
                    void run(() =>
                      createProductionTrialInspectionItem(orderId, {
                        itemCode: itemCode.trim(),
                        itemName: itemName.trim(),
                        standardValue: itemStandard.trim(),
                        inspectionMethod: '',
                        unit: '',
                        criticalItem: itemCritical,
                        sortOrder: (detail?.inspectionItems.length ?? 0) + 1,
                        note: '',
                      }),
                    )
                  }
                >
                  登记质检标准项
                </Button>
              </div>
            </SectionCard>

            <SectionCard title="试产结果（良率由后端按 合格 ÷ 产出 推导）">
              {detail?.result ? (
                <div className="grid gap-2 text-sm sm:grid-cols-3">
                  <div>
                    <span className="text-muted-foreground">产出：</span>
                    {formatQuantity(detail.result.outputQuantity)}
                  </div>
                  <div>
                    <span className="text-muted-foreground">合格：</span>
                    {formatQuantity(detail.result.qualifiedQuantity)}
                  </div>
                  <div>
                    <span className="text-muted-foreground">不良：</span>
                    {formatQuantity(detail.result.defectiveQuantity)}
                  </div>
                  <div>
                    <span className="text-muted-foreground">良率：</span>
                    <span title={`比例 ${detail.result.yieldRate}`}>
                      {formatYieldRate(detail.result.yieldRate)}
                    </span>
                  </div>
                  <div className="sm:col-span-2">
                    <span className="text-muted-foreground">结论：</span>
                    {detail.result.conclusion}
                  </div>
                </div>
              ) : (
                <p className="text-muted-foreground text-sm">
                  尚未记录试产结果 —— 没有结果就没有良率，转量产建议也不能出。
                </p>
              )}
              <div className="mt-3 flex flex-wrap items-end gap-2">
                <label className="flex flex-col gap-1 text-sm">
                  <span className="text-muted-foreground">产出数量 *</span>
                  <Input
                    value={outputQuantity}
                    onChange={(e) => setOutputQuantity(e.target.value)}
                    inputMode="decimal"
                  />
                </label>
                <label className="flex flex-col gap-1 text-sm">
                  <span className="text-muted-foreground">合格数量 *</span>
                  <Input
                    value={qualifiedQuantity}
                    onChange={(e) => setQualifiedQuantity(e.target.value)}
                    inputMode="decimal"
                  />
                </label>
                <label className="flex flex-col gap-1 text-sm">
                  <span className="text-muted-foreground">结论 *</span>
                  <Input
                    value={resultConclusion}
                    onChange={(e) => setResultConclusion(e.target.value)}
                  />
                </label>
                <Button
                  variant="ghost"
                  disabled={
                    busy ||
                    !outputQuantity.trim() ||
                    !qualifiedQuantity.trim() ||
                    !resultConclusion.trim()
                  }
                  onClick={() =>
                    void run(() =>
                      recordProductionTrialResult(orderId, {
                        outputQuantity: Number(outputQuantity),
                        qualifiedQuantity: Number(qualifiedQuantity),
                        conclusion: resultConclusion.trim(),
                        recordedAt: new Date().toISOString(),
                        note: '',
                      }),
                    )
                  }
                >
                  记录试产结果
                </Button>
              </div>
            </SectionCard>

            <SectionCard title="试产成本">
              <div className="grid gap-2 text-sm sm:grid-cols-2">
                <div>
                  <span className="text-muted-foreground">合计：</span>
                  {formatMoney(cost?.totalAmount, cost?.currency)}
                </div>
                <div>
                  <span className="text-muted-foreground">与量产目标成本：</span>
                  {comparison
                    ? comparison.comparable
                      ? `${formatMoney(comparison.varianceAmount, comparison.currency)}（${formatVarianceRate(comparison.varianceRate)}）`
                      : `不可比 —— ${comparison.note}`
                    : '尚无成本记录'}
                </div>
              </div>
              <div className="mt-2 overflow-x-auto">
                <table className="w-full text-sm">
                  <thead className="text-muted-foreground text-left">
                    <tr>
                      <th className="px-2 py-1">类型</th>
                      <th className="px-2 py-1">金额</th>
                      <th className="px-2 py-1">说明</th>
                      <th className="px-2 py-1" />
                    </tr>
                  </thead>
                  <tbody>
                    {(cost?.lines ?? []).map((row) => (
                      <tr key={row.id} className="border-t">
                        <td className="px-2 py-1">{row.costTypeLabel}</td>
                        <td className="px-2 py-1">{formatMoney(row.amount, row.currency)}</td>
                        <td className="px-2 py-1">{row.description}</td>
                        <td className="px-2 py-1">
                          <Button
                            variant="ghost"
                            disabled={busy}
                            onClick={() =>
                              void run(() => deleteProductionTrialCostLine(orderId, row.id))
                            }
                          >
                            删除
                          </Button>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
              <div className="mt-3 flex flex-wrap items-end gap-2">
                <label className="flex flex-col gap-1 text-sm">
                  <span className="text-muted-foreground">成本类型</span>
                  <select
                    className="rounded-md border px-2 py-1"
                    value={costType}
                    onChange={(e) => setCostType(e.target.value as ProductionTrialCostType)}
                  >
                    {COST_TYPES.map((row) => (
                      <option key={row.value} value={row.value}>
                        {row.label}
                      </option>
                    ))}
                  </select>
                </label>
                <label className="flex flex-col gap-1 text-sm">
                  <span className="text-muted-foreground">金额 *</span>
                  <Input
                    value={costAmount}
                    onChange={(e) => setCostAmount(e.target.value)}
                    inputMode="decimal"
                  />
                </label>
                <label className="flex flex-col gap-1 text-sm">
                  <span className="text-muted-foreground">说明 *</span>
                  <Input
                    value={costDescription}
                    onChange={(e) => setCostDescription(e.target.value)}
                  />
                </label>
                <Button
                  variant="ghost"
                  disabled={busy || !costAmount.trim() || !costDescription.trim()}
                  onClick={() =>
                    void run(() =>
                      addProductionTrialCostLine(orderId, {
                        costType,
                        amount: Number(costAmount),
                        description: costDescription.trim(),
                        incurredAt: new Date().toISOString(),
                      }),
                    )
                  }
                >
                  追加成本行
                </Button>
              </div>
            </SectionCard>

            <SectionCard title="转量产建议">
              <p className="text-muted-foreground mb-2 text-sm">
                🔴 6.6.2.2 转量产与建档<b>未开发</b>：这里只落一条<b>建议事实</b>，
                <b>不建 SKU、不建量产单</b>。「建议转量产」需要举证（良率达标 + 成本门槛达标）。
              </p>
              {detail?.recommendation ? (
                <div className="grid gap-2 text-sm sm:grid-cols-2">
                  <div>
                    <span className="text-muted-foreground">结论：</span>
                    <Badge tone={detail.recommendation.decision === 'GO' ? 'success' : 'warning'}>
                      {detail.recommendation.decisionLabel}
                    </Badge>
                  </div>
                  <div>
                    <span className="text-muted-foreground">生成条件：</span>
                    {detail.recommendation.conditionNote}
                  </div>
                  <div>
                    <span className="text-muted-foreground">实测良率：</span>
                    <span title={`比例 ${detail.recommendation.observedYieldRate}`}>
                      {formatYieldRate(detail.recommendation.observedYieldRate)}
                    </span>
                  </div>
                  <div>
                    <span className="text-muted-foreground">实测成本偏差率：</span>
                    {formatVarianceRate(detail.recommendation.observedCostVarianceRate)}
                  </div>
                  <div className="sm:col-span-2">
                    <span className="text-muted-foreground">建议文本：</span>
                    {detail.recommendation.rationale}
                  </div>
                </div>
              ) : (
                <p className="text-muted-foreground text-sm">尚未给出转量产建议。</p>
              )}
              <div className="mt-3 flex flex-wrap items-end gap-2">
                <label className="flex flex-col gap-1 text-sm">
                  <span className="text-muted-foreground">结论</span>
                  <select
                    className="rounded-md border px-2 py-1"
                    value={decision}
                    onChange={(e) => setDecision(e.target.value as ProductionTrialMassProductionDecision)}
                  >
                    {DECISIONS.map((row) => (
                      <option key={row.value} value={row.value}>
                        {row.label}
                      </option>
                    ))}
                  </select>
                </label>
                <label className="flex flex-col gap-1 text-sm">
                  <span className="text-muted-foreground">良率门槛（比例）*</span>
                  <Input value={minYield} onChange={(e) => setMinYield(e.target.value)} />
                </label>
                <label className="flex flex-col gap-1 text-sm">
                  <span className="text-muted-foreground">成本偏差上限（比例）</span>
                  <Input value={maxVariance} onChange={(e) => setMaxVariance(e.target.value)} />
                </label>
                <label className="flex col-span-2 flex-col gap-1 text-sm">
                  <span className="text-muted-foreground">建议文本 *</span>
                  <Input value={rationale} onChange={(e) => setRationale(e.target.value)} />
                </label>
                <label className="flex col-span-2 flex-col gap-1 text-sm">
                  <span className="text-muted-foreground">生成条件说明 *</span>
                  <Input value={conditionNote} onChange={(e) => setConditionNote(e.target.value)} />
                </label>
                <Button
                  variant="ghost"
                  disabled={
                    busy ||
                    !rationale.trim() ||
                    !conditionNote.trim() ||
                    !minYield.trim() ||
                    !!detail?.recommendation
                  }
                  onClick={() =>
                    void run(() =>
                      recordProductionTrialMassProductionRecommendation(orderId, {
                        decision,
                        rationale: rationale.trim(),
                        conditionNote: conditionNote.trim(),
                        minYieldThreshold: Number(minYield),
                        maxCostVarianceRate: maxVariance.trim() === '' ? null : Number(maxVariance),
                      }),
                    )
                  }
                >
                  记录转量产建议
                </Button>
              </div>
            </SectionCard>

            <SectionCard title={`迁移历史（${detail?.transitions.length ?? 0}）`}>
              <ul className="flex flex-col gap-1 text-sm">
                {(detail?.transitions ?? []).map((row) => (
                  <li key={row.id} className="border-t pt-1">
                    <span className="font-mono">{row.fromStatusLabel}</span> →{' '}
                    <span className="font-mono">{row.toStatusLabel}</span>：{row.reason}
                  </li>
                ))}
              </ul>
            </SectionCard>
          </div>
        )}

        <ModalFooter>
          <Button variant="ghost" onClick={onClose}>
            关闭
          </Button>
        </ModalFooter>
      </ModalContent>
    </Modal>
  );
}
