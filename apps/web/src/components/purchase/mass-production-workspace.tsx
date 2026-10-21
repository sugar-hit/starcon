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
  Notice,
  PageHeader,
  SectionCard,
  StatCard,
  type BadgeProps,
} from '@starcon/ui';
import type {
  MassProductionCapabilities,
  MassProductionCatalogSpuRow,
  MassProductionOptions,
  MassProductionPlanDetail,
  MassProductionPlanPage,
  MassProductionPlanRow,
  MassProductionSkuFilingRow,
  MassProductionSummary,
  MassProductionTrialCandidateRow,
  ProductionTrialBomVersionCandidateRow,
} from '../../../../../packages/api-contracts/src/index';
import {
  createMassProductionPlan,
  createMassProductionSkuFiling,
  describeBomLine,
  describeFilingOutcome,
  describeHandoff,
  describeTargetCostComparison,
  fetchMassProductionBomLines,
  fetchMassProductionBomVersionCandidates,
  fetchMassProductionCapabilities,
  fetchMassProductionHandoffs,
  fetchMassProductionOptions,
  fetchMassProductionPlan,
  fetchMassProductionPlans,
  fetchMassProductionSkuFiling,
  fetchMassProductionSpuCandidates,
  fetchMassProductionSummary,
  fetchMassProductionTrialCandidates,
  submitMassProductionSkuFiling,
  transitionMassProductionPlan,
  triggerMassProductionReplenishment,
  type MassProductionDecision,
  type MassProductionDecisionSource,
  type MassProductionPlanParams,
} from './mass-production-model';
import { formatMoney, formatQuantity } from './production-trial-model';
import { errorText } from '../inventory/inventory-model';

/**
 * 6.6.2.2 转量产与建档工作区。
 *
 * <p>蓝图：「试产通过后转正式量产：确定量产 BOM、批量采购/生产计划、目标成本、
 * 正式 SKU 建档（含编码/条码/属性/报关属性），并把量产参数带入采购与价格模型。」
 * 输出「量产计划；正式 SKU（3.1.2.1）；量产 BOM 版本；采购需求（6.2.1.2）」。
 *
 * <p>界面纪律：
 *
 * <ul>
 *   <li><b>正式 SKU 是「交给商品中心建的」</b>：页面显示的是<b>建档请求</b>的状态
 *       与商品中心返回的 SKU id / 编码，<b>不出现「本模块已建 SKU」的说法</b> ——
 *       写入所有权在 catalog（3.1.2.1）。</li>
 *   <li><b>「结果未知」单独一档</b>：商品中心的建档端点<b>不接受幂等键</b>，
 *       超时 / 断连时无法判断建没建出来 ⇒ 页面显示「结果未知 · 不要重试」，
 *       并**不给重试按钮** —— 笼统的「失败」会诱导人点重试，而那可能建出第二个 SKU。</li>
 *   <li><b>「部分完成」可以再提交</b>，但只补报关属性，SKU id 照样显示（它是真的）。</li>
 *   <li><b>目标成本取不到就是取不到</b>：不可比时显示「不可比」+ 原因，<b>不显示 0</b>；
 *       目标成本<b>按币种分组</b>，不跨币种加成一个总数。</li>
 *   <li><b>下游交接如实显示</b>：6.2.1.2 采购需求只做<b>重算触发</b>，
 *       页面明写「量产参数**未**进入 6.2.1.2」，不假装交接过。</li>
 *   <li><b>做不到的事写在页面上</b>：价格模型（5.3.1.1 未开发）、
 *       生产计划下达（8.4.1 组装单）—— 常驻横幅，不假装可用。</li>
 *   <li><b>状态只给合法的去向</b>：下拉来自后端的 {@code allowedTargets}，
 *       页面不维护第二份状态机。</li>
 *   <li>加载 / 空 / 错误三态齐全。</li>
 * </ul>
 */

const PAGE_SIZE = 20;

const DECISIONS: { value: MassProductionDecision; label: string }[] = [
  { value: 'GO', label: '转量产' },
  { value: 'HOLD', label: '暂缓量产' },
];

const DECISION_SOURCES: { value: MassProductionDecisionSource; label: string }[] = [
  { value: 'TRIAL_RECOMMENDATION', label: '跟随上游转量产建议' },
  { value: 'MANUAL_OVERRIDE', label: '人工覆盖上游建议' },
];

type Tone = NonNullable<BadgeProps['tone']>;

/** 量产计划状态 → 徽标色调（tone 跟语义挂钩，不是跟颜色挂钩）。 */
function statusTone(status: string): Tone {
  if (status === 'RELEASED' || status === 'CLOSED') return 'success';
  if (status === 'CANCELLED') return 'danger';
  if (status === 'SKU_FILED') return 'success';
  if (status === 'BOM_FROZEN') return 'info';
  if (status === 'DECIDED') return 'warning';
  return 'neutral';
}

export function MassProductionWorkspace() {
  const [caps, setCaps] = useState<MassProductionCapabilities | null>(null);
  const [options, setOptions] = useState<MassProductionOptions | null>(null);
  const [summary, setSummary] = useState<MassProductionSummary | null>(null);
  const [plans, setPlans] = useState<MassProductionPlanPage | null>(null);
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
      const params: MassProductionPlanParams = { page, size: PAGE_SIZE };
      if (status.trim()) params.status = status.trim();
      if (keyword.trim()) params.keyword = keyword.trim();
      const [capabilities, opts, stat, pageResult] = await Promise.all([
        fetchMassProductionCapabilities(),
        fetchMassProductionOptions(),
        fetchMassProductionSummary(),
        fetchMassProductionPlans(params),
      ]);
      setCaps(capabilities);
      setOptions(opts);
      setSummary(stat);
      setPlans(pageResult);
    } catch (e) {
      setError(errorText(e));
    } finally {
      setLoading(false);
    }
  }, [page, status, keyword]);

  useEffect(() => {
    void load();
  }, [load]);

  const rows = plans?.rows ?? [];
  const total = plans?.total ?? 0;
  const pageCount = plans?.totalPages ?? 1;

  return (
    <div className="space-y-4">
      <PageHeader
        title="转量产与建档"
        description="试产通过后转正式量产：确定量产 BOM、批量采购/生产计划、目标成本，正式 SKU 交给商品中心建档，并把量产参数如实交给采购与价格模型。"
        actions={
          <div className="flex gap-2">
            <Button variant="ghost" onClick={() => void load()} disabled={loading}>
              <RefreshCw className="size-4" />
              刷新
            </Button>
            <Button onClick={() => setCreating(true)}>
              <Plus className="size-4" />
              新建量产计划
            </Button>
          </div>
        }
      />

      {caps && (
        <Notice tone="warning" title="本页的三条边界（做不到的事不假装可用）">
          <ul className="list-disc space-y-1 pl-4">
            <li>
              <b>价格模型（{caps.pricingOwner}）</b>：{caps.pricingNote}
            </li>
            <li>
              <b>生产计划下达（{caps.assemblyOrderOwner}）</b>：{caps.assemblyOrderNote}
            </li>
            <li>
              <b>采购需求（{caps.purchaseDemandOwner}）</b>：{caps.purchaseDemandNote}
            </li>
            <li>
              <b>正式 SKU（{caps.skuFilingOwner}）</b>：{caps.skuFilingNote}
            </li>
          </ul>
        </Notice>
      )}

      {summary && (
        <div className="grid gap-3 sm:grid-cols-3 lg:grid-cols-7">
          <StatCard label="量产计划" value={summary.totalPlans} />
          <StatCard label="已决策" value={summary.decidedPlans} />
          <StatCard label="BOM 已定版" value={summary.frozenBomPlans} />
          <StatCard label="SKU 已建档" value={summary.filedSkuPlans} />
          <StatCard label="已下达" value={summary.releasedPlans} />
          <StatCard label="待提交建档" value={summary.pendingFilings} />
          <StatCard label="建档异常" value={summary.failedFilings} />
        </div>
      )}

      {summary && summary.targetCostByCurrency.length > 0 && (
        <SectionCard title="量产目标成本（按币种分组，不跨币种相加）">
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead className="text-muted-foreground text-left">
                <tr>
                  <th className="px-2 py-1">币种</th>
                  <th className="px-2 py-1">目标成本合计</th>
                  <th className="px-2 py-1">计划数</th>
                </tr>
              </thead>
              <tbody>
                {summary.targetCostByCurrency.map((row) => (
                  <tr key={row.currency} className="border-t">
                    <td className="px-2 py-1 font-mono">{row.currency}</td>
                    <td className="px-2 py-1">{formatMoney(row.totalTargetCost, row.currency)}</td>
                    <td className="px-2 py-1">{row.count}</td>
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
                <option key={row.status} value={row.status}>
                  {row.label}
                </option>
              ))}
            </select>
          </label>
          <label className="flex flex-col gap-1 text-sm">
            <span className="text-muted-foreground">关键词</span>
            <Input
              value={keyword}
              placeholder="量产计划号 / 试产单号 / BOM 编码 / 成品 SKU"
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

      {loading && <p className="text-muted-foreground text-sm">正在加载量产计划…</p>}

      {!loading && rows.length === 0 && !error && (
        <EmptyState
          title="还没有量产计划"
          description="当前筛选条件下没有量产计划。先点右上角「新建量产计划」（要选一张已完成的试产单与一个已发布的 BOM 版本），或放宽筛选条件。"
        />
      )}

      {!loading && rows.length > 0 && (
        <SectionCard title={`量产计划清单（${total}）`}>
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead className="text-muted-foreground text-left">
                <tr>
                  <th className="px-2 py-1">计划号</th>
                  <th className="px-2 py-1">上游试产单</th>
                  <th className="px-2 py-1">BOM 版本 / 成品</th>
                  <th className="px-2 py-1">计划批量</th>
                  <th className="px-2 py-1">目标成本</th>
                  <th className="px-2 py-1">工厂</th>
                  <th className="px-2 py-1">状态</th>
                  <th className="px-2 py-1">建档</th>
                  <th className="px-2 py-1" />
                </tr>
              </thead>
              <tbody>
                {rows.map((row) => (
                  <tr key={row.id} className="border-t">
                    <td className="px-2 py-1 font-mono">{row.planNo}</td>
                    <td className="px-2 py-1">
                      <div className="font-mono">{row.trialNo}</div>
                      <div className="text-muted-foreground">
                        上游建议：
                        {row.trialRecommendationDecision === 'GO'
                          ? '建议转量产'
                          : row.trialRecommendationDecision === 'NO_GO'
                            ? '暂不建议转量产'
                            : '（没有出建议）'}
                      </div>
                    </td>
                    <td className="px-2 py-1">
                      <div>{row.bomVersionNo}</div>
                      <div className="text-muted-foreground">{row.outputSkuCode}</div>
                    </td>
                    <td className="px-2 py-1">{formatQuantity(row.planQuantity)}</td>
                    <td className="px-2 py-1">
                      {row.targetCost === null ? (
                        <span className="text-muted-foreground">未填（取不到 ≠ 0）</span>
                      ) : (
                        formatMoney(row.targetCost, row.currency)
                      )}
                    </td>
                    <td className="px-2 py-1">{row.factoryName || row.factoryCode || '—'}</td>
                    <td className="px-2 py-1">
                      <Badge tone={statusTone(row.status)}>{row.statusLabel}</Badge>
                    </td>
                    <td className="px-2 py-1">
                      {row.filingStatus ? (
                        <Badge
                          tone={
                            row.filingStatus === 'CONFIRMED'
                              ? 'success'
                              : row.filingStatus === 'FAILED' || row.filingStatus === 'UNKNOWN'
                                ? 'danger'
                                : 'neutral'
                          }
                        >
                          {row.filingStatusLabel}
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
        <CreatePlanModal
          onClose={() => setCreating(false)}
          onCreated={() => {
            setCreating(false);
            void load();
          }}
        />
      )}

      {detailId && (
        <PlanDetailModal planId={detailId} onClose={() => setDetailId(null)} />
      )}
    </div>
  );
}

// ================================================================ 新建量产计划

function CreatePlanModal({
  onClose,
  onCreated,
}: {
  onClose: () => void;
  onCreated: () => void;
}) {
  const [trials, setTrials] = useState<MassProductionTrialCandidateRow[]>([]);
  const [boms, setBoms] = useState<ProductionTrialBomVersionCandidateRow[]>([]);
  const [planNo, setPlanNo] = useState('');
  const [trialOrderId, setTrialOrderId] = useState('');
  const [bomVersionId, setBomVersionId] = useState('');
  const [planQuantity, setPlanQuantity] = useState('1000');
  const [targetCost, setTargetCost] = useState('');
  const [currency, setCurrency] = useState('USD');
  const [factoryCode, setFactoryCode] = useState('');
  const [factoryName, setFactoryName] = useState('');
  const [plannedStartAt, setPlannedStartAt] = useState('');
  const [note, setNote] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    void (async () => {
      try {
        const [trialList, bomList] = await Promise.all([
          fetchMassProductionTrialCandidates(),
          fetchMassProductionBomVersionCandidates(),
        ]);
        setTrials(trialList);
        setBoms(bomList);
        const first = trialList[0];
        if (first) {
          setTrialOrderId(first.trialOrderId);
          setBomVersionId(first.bomVersionId);
        }
      } catch (e) {
        setError(errorText(e));
      }
    })();
  }, []);

  const submit = async () => {
    setBusy(true);
    setError(null);
    try {
      await createMassProductionPlan({
        planNo: planNo.trim(),
        trialOrderId,
        bomVersionId,
        planQuantity: Number(planQuantity),
        targetCost: targetCost.trim() ? Number(targetCost) : null,
        currency: currency.trim().toUpperCase(),
        productionLineCode: '',
        productionLineName: '',
        factoryCode: factoryCode.trim(),
        factoryName: factoryName.trim(),
        plannedStartAt: plannedStartAt ? new Date(plannedStartAt).toISOString() : null,
        plannedEndAt: null,
        note: note.trim(),
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
      <ModalContent className="max-w-2xl">
        <ModalHeader>
          <ModalTitle>新建量产计划</ModalTitle>
          <ModalDescription>
            计划必须挂在一条**已完成的试产单**上（没做完的试产没有结果、没有良率、没有成本对比），
            并用一个**已发布**的 BOM 版本。目标成本可以先不填 —— 填了才可比。
          </ModalDescription>
        </ModalHeader>
        <div className="space-y-3">
          {error && (
            <div className="rounded-md border border-red-500/40 bg-red-500/10 p-3 text-sm text-red-600">
              {error}
            </div>
          )}
          <label className="flex flex-col gap-1 text-sm">
            <span className="text-muted-foreground">量产计划号 *</span>
            <Input value={planNo} onChange={(e) => setPlanNo(e.target.value)} placeholder="MP-20261021-a1b2c3d4" />
          </label>
          <label className="flex flex-col gap-1 text-sm">
            <span className="text-muted-foreground">上游试产单 *</span>
            <select
              className="rounded-md border px-2 py-1"
              value={trialOrderId}
              onChange={(e) => {
                setTrialOrderId(e.target.value);
                const picked = trials.find((t) => t.trialOrderId === e.target.value);
                if (picked?.bomVersionId) setBomVersionId(picked.bomVersionId);
              }}
            >
              <option value="">请选择</option>
              {trials.map((row) => (
                <option key={row.trialOrderId} value={row.trialOrderId}>
                  {row.trialNo}（{row.recommendationDecisionLabel}）
                </option>
              ))}
            </select>
          </label>
          <label className="flex flex-col gap-1 text-sm">
            <span className="text-muted-foreground">量产 BOM 版本 *</span>
            <select
              className="rounded-md border px-2 py-1"
              value={bomVersionId}
              onChange={(e) => setBomVersionId(e.target.value)}
            >
              <option value="">请选择</option>
              {boms.map((row) => (
                <option key={row.id} value={row.id}>
                  {row.bomCode} {row.versionNo} · {row.outputSkuCode}（{row.statusLabel}）
                </option>
              ))}
            </select>
          </label>
          <div className="grid gap-3 sm:grid-cols-2">
            <label className="flex flex-col gap-1 text-sm">
              <span className="text-muted-foreground">计划批量 *</span>
              <Input value={planQuantity} onChange={(e) => setPlanQuantity(e.target.value)} />
            </label>
            <label className="flex flex-col gap-1 text-sm">
              <span className="text-muted-foreground">币种 *</span>
              <Input value={currency} onChange={(e) => setCurrency(e.target.value)} placeholder="USD" />
            </label>
            <label className="flex flex-col gap-1 text-sm">
              <span className="text-muted-foreground">量产目标成本（可留空 = 取不到）</span>
              <Input value={targetCost} onChange={(e) => setTargetCost(e.target.value)} placeholder="12.5000" />
            </label>
            <label className="flex flex-col gap-1 text-sm">
              <span className="text-muted-foreground">计划开始时间</span>
              <Input
                type="datetime-local"
                value={plannedStartAt}
                onChange={(e) => setPlannedStartAt(e.target.value)}
              />
            </label>
            <label className="flex flex-col gap-1 text-sm">
              <span className="text-muted-foreground">工厂编码</span>
              <Input value={factoryCode} onChange={(e) => setFactoryCode(e.target.value)} />
            </label>
            <label className="flex flex-col gap-1 text-sm">
              <span className="text-muted-foreground">工厂名称</span>
              <Input value={factoryName} onChange={(e) => setFactoryName(e.target.value)} />
            </label>
          </div>
          <label className="flex flex-col gap-1 text-sm">
            <span className="text-muted-foreground">备注</span>
            <Input value={note} onChange={(e) => setNote(e.target.value)} />
          </label>
        </div>
        <ModalFooter>
          <Button variant="ghost" onClick={onClose} disabled={busy}>
            取消
          </Button>
          <Button onClick={() => void submit()} disabled={busy || !planNo.trim() || !trialOrderId}>
            {busy ? '提交中…' : '创建'}
          </Button>
        </ModalFooter>
      </ModalContent>
    </Modal>
  );
}

// ================================================================ 量产计划详情

function PlanDetailModal({ planId, onClose }: { planId: string; onClose: () => void }) {
  const [detail, setDetail] = useState<MassProductionPlanDetail | null>(null);
  const [filing, setFiling] = useState<MassProductionSkuFilingRow | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  // 决策表单
  const [decision, setDecision] = useState<MassProductionDecision>('GO');
  const [decisionSource, setDecisionSource] = useState<MassProductionDecisionSource>(
    'TRIAL_RECOMMENDATION',
  );
  const [decisionRationale, setDecisionRationale] = useState('');
  const [overrideReason, setOverrideReason] = useState('');
  const [transitionReason, setTransitionReason] = useState('');

  // 建档表单
  const [spus, setSpus] = useState<MassProductionCatalogSpuRow[]>([]);
  const [targetSpuId, setTargetSpuId] = useState('');
  const [skuCode, setSkuCode] = useState('');
  const [skuName, setSkuName] = useState('');
  const [barcode, setBarcode] = useState('');
  const [baseUnit, setBaseUnit] = useState('个');
  const [hsCode, setHsCode] = useState('');
  const [originCountryCode, setOriginCountryCode] = useState('');
  const [declaredName, setDeclaredName] = useState('');
  const [statutoryUnitCode, setStatutoryUnitCode] = useState('');

  const load = useCallback(async () => {
    setError(null);
    try {
      const [plan, skuFiling] = await Promise.all([
        fetchMassProductionPlan(planId),
        fetchMassProductionSkuFiling(planId),
      ]);
      setDetail(plan);
      setFiling(skuFiling);
    } catch (e) {
      setError(errorText(e));
    }
  }, [planId]);

  useEffect(() => {
    void load();
  }, [load]);

  useEffect(() => {
    void (async () => {
      try {
        setSpus(await fetchMassProductionSpuCandidates());
      } catch {
        // 候选拉不到不影响详情展示（建档表单会提示）。
      }
    })();
  }, []);

  const plan = detail?.plan ?? null;
  const upstreamNoGo = plan?.trialRecommendationDecision === 'NO_GO';
  const comparison = detail?.costComparison ?? null;
  const filingOutcome = filing ? describeFilingOutcome(filing) : null;

  const runTransition = async (target: string) => {
    setBusy(true);
    setError(null);
    try {
      await transitionMassProductionPlan(planId, {
        targetStatus: target,
        decision: target === 'DECIDED' ? decision : null,
        decisionSource: target === 'DECIDED' ? decisionSource : null,
        decisionRationale: target === 'DECIDED' ? decisionRationale : null,
        overrideReason: target === 'DECIDED' ? overrideReason : null,
        reason: transitionReason,
      });
      await load();
    } catch (e) {
      setError(errorText(e));
    } finally {
      setBusy(false);
    }
  };

  const createFiling = async () => {
    setBusy(true);
    setError(null);
    try {
      await createMassProductionSkuFiling(planId, {
        targetSpuId,
        skuCode: skuCode.trim(),
        skuName: skuName.trim(),
        barcode: barcode.trim(),
        baseUnit: baseUnit.trim(),
        skuStatus: 'DRAFT',
        customsHsCode: hsCode.trim(),
        customsOriginCountryCode: originCountryCode.trim(),
        customsDeclaredName: declaredName.trim(),
        customsDeclaredValueReference: null,
        customsStatutoryUnitCode: statutoryUnitCode.trim(),
        customsPreferentialOriginEligible: false,
        customsPreferenceAgreement: '',
        customsNote: '',
        attributes: [],
        customsElements: [],
      });
      await load();
    } catch (e) {
      setError(errorText(e));
    } finally {
      setBusy(false);
    }
  };

  const submitFiling = async () => {
    if (!filing) return;
    setBusy(true);
    setError(null);
    try {
      await submitMassProductionSkuFiling(planId, filing.id);
    } catch (e) {
      // 非 2xx 也带着建档请求本身（后端按落库的状态映射状态码）⇒ 仍然刷新一次。
      setError(errorText(e));
    } finally {
      setBusy(false);
      await load();
    }
  };

  const retrigger = async () => {
    setBusy(true);
    setError(null);
    try {
      await triggerMassProductionReplenishment(planId);
    } catch (e) {
      setError(errorText(e));
    } finally {
      setBusy(false);
      await load();
    }
  };

  return (
    <Modal open onOpenChange={(open) => !open && onClose()}>
      <ModalContent className="max-w-4xl">
        <ModalHeader>
          <ModalTitle>{plan ? `量产计划 ${plan.planNo}` : '量产计划详情'}</ModalTitle>
          <ModalDescription>
            转量产是开发与供应链的交接口：SKU 编码 / 条码在此时生成，后续全链路引用此编码。
            正式 SKU 由**商品中心**建（写入所有权在 3.1.2.1），本页只落建档请求事实。
          </ModalDescription>
        </ModalHeader>
        <div className="max-h-[70vh] space-y-3 overflow-y-auto">
          {error && (
            <div className="rounded-md border border-red-500/40 bg-red-500/10 p-3 text-sm text-red-600">
              {error}
            </div>
          )}
          {!plan && <p className="text-muted-foreground text-sm">正在加载…</p>}

          {plan && (
            <>
              <SectionCard title="量产计划记录">
                <dl className="grid gap-2 text-sm sm:grid-cols-2">
                  <div>
                    <dt className="text-muted-foreground">上游试产单</dt>
                    <dd className="font-mono">{plan.trialNo}</dd>
                  </div>
                  <div>
                    <dt className="text-muted-foreground">上游转量产建议</dt>
                    <dd>
                      {plan.trialRecommendationDecision === 'GO'
                        ? '建议转量产'
                        : plan.trialRecommendationDecision === 'NO_GO'
                          ? '暂不建议转量产'
                          : '（没有出建议）'}
                    </dd>
                  </div>
                  <div>
                    <dt className="text-muted-foreground">量产 BOM 版本</dt>
                    <dd>
                      {plan.bomCode} {plan.bomVersionNo} · {plan.outputSkuCode}
                    </dd>
                  </div>
                  <div>
                    <dt className="text-muted-foreground">计划批量</dt>
                    <dd>{formatQuantity(plan.planQuantity)}</dd>
                  </div>
                  <div>
                    <dt className="text-muted-foreground">量产目标成本</dt>
                    <dd>
                      {plan.targetCost === null
                        ? '未填（取不到，不是 0）'
                        : formatMoney(plan.targetCost, plan.currency)}
                    </dd>
                  </div>
                  <div>
                    <dt className="text-muted-foreground">状态</dt>
                    <dd>
                      <Badge tone={statusTone(plan.status)}>{plan.statusLabel}</Badge>
                    </dd>
                  </div>
                  <div>
                    <dt className="text-muted-foreground">量产决策</dt>
                    <dd>
                      {plan.decision
                        ? `${plan.decisionLabel} · ${plan.decisionSourceLabel}`
                        : '尚未决策'}
                    </dd>
                  </div>
                  <div>
                    <dt className="text-muted-foreground">工厂</dt>
                    <dd>{plan.factoryName || plan.factoryCode || '—'}</dd>
                  </div>
                </dl>
                {plan.decisionRationale && (
                  <p className="text-muted-foreground mt-2 text-sm">决策理由：{plan.decisionRationale}</p>
                )}
                {plan.overrideReason && (
                  <p className="text-muted-foreground mt-1 text-sm">
                    覆盖上游建议的理由：{plan.overrideReason}
                  </p>
                )}
              </SectionCard>

              {comparison && (
                <SectionCard title="量产目标成本 vs 上游试产实测成本">
                  <p className="text-sm">
                    {describeTargetCostComparison(comparison).headline}
                  </p>
                  <p className="text-muted-foreground mt-1 text-sm">
                    {describeTargetCostComparison(comparison).detail}
                  </p>
                </SectionCard>
              )}

              <SectionCard title="推进状态（只列后端说的合法去向）">
                {upstreamNoGo && (
                  <Notice tone="warning" title="上游建议是「暂不建议转量产」">
                    要转量产的话必须写明**覆盖理由** —— 那是刻意推翻上游结论，必须留下痕迹。
                  </Notice>
                )}
                <div className="mt-2 space-y-2">
                  <label className="flex flex-col gap-1 text-sm">
                    <span className="text-muted-foreground">迁移理由</span>
                    <Input
                      value={transitionReason}
                      onChange={(e) => setTransitionReason(e.target.value)}
                      placeholder="为什么现在推进"
                    />
                  </label>
                  {detail?.allowedTargets?.includes('DECIDED') && (
                    <div className="grid gap-2 sm:grid-cols-2">
                      <label className="flex flex-col gap-1 text-sm">
                        <span className="text-muted-foreground">决策结论 *</span>
                        <select
                          className="rounded-md border px-2 py-1"
                          value={decision}
                          onChange={(e) => setDecision(e.target.value as MassProductionDecision)}
                        >
                          {DECISIONS.map((row) => (
                            <option key={row.value} value={row.value}>
                              {row.label}
                            </option>
                          ))}
                        </select>
                      </label>
                      <label className="flex flex-col gap-1 text-sm">
                        <span className="text-muted-foreground">决策来源 *</span>
                        <select
                          className="rounded-md border px-2 py-1"
                          value={decisionSource}
                          onChange={(e) =>
                            setDecisionSource(e.target.value as MassProductionDecisionSource)
                          }
                        >
                          {DECISION_SOURCES.map((row) => (
                            <option key={row.value} value={row.value}>
                              {row.label}
                            </option>
                          ))}
                        </select>
                      </label>
                      <label className="flex flex-col gap-1 text-sm sm:col-span-2">
                        <span className="text-muted-foreground">决策理由 *</span>
                        <Input
                          value={decisionRationale}
                          onChange={(e) => setDecisionRationale(e.target.value)}
                          placeholder="良率与成本都达标，转正式量产"
                        />
                      </label>
                      <label className="flex flex-col gap-1 text-sm sm:col-span-2">
                        <span className="text-muted-foreground">
                          覆盖上游建议的理由{upstreamNoGo ? ' *' : ''}
                        </span>
                        <Input
                          value={overrideReason}
                          onChange={(e) => setOverrideReason(e.target.value)}
                          placeholder="上游建议暂不转量产时必填"
                        />
                      </label>
                    </div>
                  )}
                  <div className="flex flex-wrap gap-2">
                    {(detail?.allowedTargets ?? []).map((target) => (
                      <Button
                        key={target}
                        variant="ghost"
                        disabled={busy}
                        onClick={() => void runTransition(target)}
                      >
                        {target}
                      </Button>
                    ))}
                    {detail?.allowedTargets?.length === 0 && (
                      <span className="text-muted-foreground text-sm">
                        已关闭 / 已取消是终态，不提供恢复。
                      </span>
                    )}
                  </div>
                </div>
              </SectionCard>

              <BomLinesSection planId={planId} />

              <SectionCard title="正式 SKU 建档（写入所有权在商品中心 3.1.2.1）">
                {filing && filingOutcome ? (
                  <div className="space-y-2">
                    <div className="flex flex-wrap items-center gap-2">
                      <Badge tone={filingOutcome.tone}>{filingOutcome.headline}</Badge>
                      <span className="text-muted-foreground text-sm">
                        编码 {filing.skuCode} · 条码 {filing.barcode} · 建在 {filing.targetSpuCode}
                      </span>
                    </div>
                    <p className="text-muted-foreground text-sm">{filingOutcome.detail}</p>
                    {filing.targetSkuId && (
                      <p className="text-sm">
                        商品中心返回的 SKU：{filing.targetSkuCode || filing.targetSkuId}（id{' '}
                        {filing.targetSkuId}）
                      </p>
                    )}
                    <div className="flex gap-2">
                      <Button
                        variant="ghost"
                        disabled={busy || !filingOutcome.retryAllowed}
                        onClick={() => void submitFiling()}
                      >
                        提交建档
                      </Button>
                      {!filingOutcome.retryAllowed && filing.status === 'UNKNOWN' && (
                        <span className="text-muted-foreground self-center text-sm">
                          结果未知时**不提供**重试按钮 —— 盲目重试可能建出第二个 SKU。
                        </span>
                      )}
                    </div>
                  </div>
                ) : (
                  <div className="space-y-2">
                    <p className="text-muted-foreground text-sm">
                      还没有建档请求。一张量产计划**只允许一条**建档请求：两条建档请求 = 两个 SKU。
                    </p>
                    <div className="grid gap-2 sm:grid-cols-2">
                      <label className="flex flex-col gap-1 text-sm">
                        <span className="text-muted-foreground">建在哪个 SPU 下面 *</span>
                        <select
                          className="rounded-md border px-2 py-1"
                          value={targetSpuId}
                          onChange={(e) => setTargetSpuId(e.target.value)}
                        >
                          <option value="">请选择</option>
                          {spus.map((row) => (
                            <option key={row.id} value={row.id}>
                              {row.code} · {row.name}
                            </option>
                          ))}
                        </select>
                      </label>
                      <label className="flex flex-col gap-1 text-sm">
                        <span className="text-muted-foreground">SKU 编码 *</span>
                        <Input value={skuCode} onChange={(e) => setSkuCode(e.target.value)} />
                      </label>
                      <label className="flex flex-col gap-1 text-sm">
                        <span className="text-muted-foreground">SKU 名称 *</span>
                        <Input value={skuName} onChange={(e) => setSkuName(e.target.value)} />
                      </label>
                      <label className="flex flex-col gap-1 text-sm">
                        <span className="text-muted-foreground">条码 *</span>
                        <Input value={barcode} onChange={(e) => setBarcode(e.target.value)} />
                      </label>
                      <label className="flex flex-col gap-1 text-sm">
                        <span className="text-muted-foreground">基础单位 *</span>
                        <Input value={baseUnit} onChange={(e) => setBaseUnit(e.target.value)} />
                      </label>
                    </div>
                    <details className="text-sm">
                      <summary className="cursor-pointer">报关属性（可留空 —— 留空时建档照样成立）</summary>
                      <div className="mt-2 grid gap-2 sm:grid-cols-2">
                        <label className="flex flex-col gap-1 text-sm">
                          <span className="text-muted-foreground">HS 编码</span>
                          <Input value={hsCode} onChange={(e) => setHsCode(e.target.value)} />
                        </label>
                        <label className="flex flex-col gap-1 text-sm">
                          <span className="text-muted-foreground">原产国</span>
                          <Input
                            value={originCountryCode}
                            onChange={(e) => setOriginCountryCode(e.target.value)}
                            placeholder="CN"
                          />
                        </label>
                        <label className="flex flex-col gap-1 text-sm">
                          <span className="text-muted-foreground">申报品名</span>
                          <Input value={declaredName} onChange={(e) => setDeclaredName(e.target.value)} />
                        </label>
                        <label className="flex flex-col gap-1 text-sm">
                          <span className="text-muted-foreground">法定单位</span>
                          <Input
                            value={statutoryUnitCode}
                            onChange={(e) => setStatutoryUnitCode(e.target.value)}
                            placeholder="PCS"
                          />
                        </label>
                      </div>
                    </details>
                    <Button
                      disabled={busy || !targetSpuId || !skuCode.trim() || !skuName.trim() || !barcode.trim()}
                      onClick={() => void createFiling()}
                    >
                      登记建档请求
                    </Button>
                  </div>
                )}
              </SectionCard>

              <SectionCard title="下游交接留痕（成功 / 失败 / 跳过都留一行）">
                <HandoffsSection planId={planId} onRetrigger={retrigger} busy={busy} />
              </SectionCard>

              <SectionCard title="状态迁移历史">
                {(detail?.transitions ?? []).length === 0 ? (
                  <p className="text-muted-foreground text-sm">还没有迁移记录。</p>
                ) : (
                  <ul className="space-y-1 text-sm">
                    {(detail?.transitions ?? []).map((row) => (
                      <li key={row.id} className="border-t pt-1">
                        <span className="font-mono">{row.occurredAt}</span> · {row.fromStatusLabel} →{' '}
                        {row.toStatusLabel}
                        {row.reason ? ` · ${row.reason}` : ''}
                      </li>
                    ))}
                  </ul>
                )}
              </SectionCard>
            </>
          )}
        </div>
        <ModalFooter>
          <Button variant="ghost" onClick={onClose}>
            关闭
          </Button>
        </ModalFooter>
      </ModalContent>
    </Modal>
  );
}

// ================================================================ 量产 BOM 组件行

function BomLinesSection({ planId }: { planId: string }) {
  const [lines, setLines] = useState<
    Awaited<ReturnType<typeof fetchMassProductionBomLines>>
  >([]);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    void (async () => {
      try {
        setLines(await fetchMassProductionBomLines(planId));
      } catch (e) {
        setError(errorText(e));
      }
    })();
  }, [planId]);

  return (
    <>
      {error && <p className="text-sm text-red-600">{error}</p>}
      {lines.length === 0 ? (
        <p className="text-muted-foreground text-sm">
          量产 BOM 还没定版。定版会把 catalog 算好的组件需求量**原样搬过来**（本服务不重算）。
        </p>
      ) : (
        <ul className="space-y-1 text-sm">
          {lines.map((row) => (
            <li key={row.id} className="border-t pt-1">
              {row.lineNo}. {describeBomLine(row)}
            </li>
          ))}
        </ul>
      )}
    </>
  );
}

// ================================================================ 下游交接

function HandoffsSection({
  planId,
  onRetrigger,
  busy,
}: {
  planId: string;
  onRetrigger: () => Promise<void>;
  busy: boolean;
}) {
  const [handoffs, setHandoffs] = useState<
    Awaited<ReturnType<typeof fetchMassProductionHandoffs>>
  >([]);

  useEffect(() => {
    void (async () => {
      try {
        setHandoffs(await fetchMassProductionHandoffs(planId));
      } catch {
        // 交接读不到不影响主流程。
      }
    })();
  }, [planId, busy]);

  return (
    <div className="space-y-2">
      {handoffs.length === 0 ? (
        <p className="text-muted-foreground text-sm">
          还没有交接留痕。计划**下达**时会触发 6.2.1.2 补货建议重算，并如实记下结果。
        </p>
      ) : (
        handoffs.map((row) => {
          const view = describeHandoff(row);
          return (
            <div key={row.id} className="border-t pt-2 text-sm">
              <div className="flex flex-wrap items-center gap-2">
                <Badge tone={view.tone}>{view.headline}</Badge>
                <span className="text-muted-foreground">
                  目标模块 {row.targetModule}（归管 {row.ownerModule}）
                </span>
              </div>
              <p className="mt-1">{view.detail}</p>
              <p className="text-muted-foreground mt-1">{view.applied}</p>
            </div>
          );
        })
      )}
      <Button variant="ghost" disabled={busy} onClick={() => void onRetrigger()}>
        <Factory className="size-4" />
        重新触发 6.2.1.2 补货建议重算
      </Button>
      <p className="text-muted-foreground text-sm">
        <PackageSearch className="mr-1 inline size-4" />
        这一步只是**重算触发**：6.2.1.2 的起订额与交期是人工维护的策略，本模块不顶掉它，
        量产参数**进不去** —— 留痕里如实标着。
      </p>
    </div>
  );
}
