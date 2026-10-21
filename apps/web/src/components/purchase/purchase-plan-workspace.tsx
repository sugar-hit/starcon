'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import {
  CalendarClock,
  Download,
  Lock,
  Pencil,
  Plus,
  RefreshCw,
  TriangleAlert,
} from 'lucide-react';
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
  PurchasePlanCapabilities,
  PurchasePlanCampaignRow,
  PurchasePlanLineRow,
  PurchasePlanOptions,
  PurchasePlanRow,
  PurchasePlanSummary,
} from '../../../../../packages/api-contracts/src/index';
import {
  adjustPurchasePlanLine,
  aggregatePurchasePlan,
  campaignStatusLabel,
  confirmPurchasePlan,
  createPurchasePlan,
  createPurchasePlanCampaign,
  deletePurchasePlanCampaign,
  downloadPurchasePlanCsv,
  fetchPurchasePlanCapabilities,
  fetchPurchasePlan,
  fetchPurchasePlanCampaigns,
  fetchPurchasePlanLines,
  fetchPurchasePlanOptions,
  fetchPurchasePlanSummary,
  fetchPurchasePlans,
  fmt,
  isAdjustable,
  isLockable,
  lineStatusLabel,
  lockPurchasePlanLine,
  lockStatusLabel,
  lockTone,
  periodTypeLabel,
  planStatusLabel,
  planTypeLabel,
  planTypeTone,
  priorityLabel,
  suggestedQuantityText,
  type PurchasePlanLineParams,
  type PurchasePlanParams,
} from './purchase-plan-model';
import { errorText } from '../inventory/inventory-model';

/**
 * 6.2.1.3 采购计划工作区。
 *
 * <p>蓝图：「按周期（月/季）、按店铺大促节奏、按仓库制定采购计划，把补货建议汇总成计划行，
 * 支持调整、锁定、导出给采购员。」输出「采购计划（计划行：SKU、数量、目标仓、期望到货日、
 * 优先级）」。
 *
 * <p>界面纪律：
 *
 * <ul>
 *   <li><b>日常与大促分开看</b>：顶部两张汇总卡**并排**显示「日常补货备货量」与
 *       「大促备货备货量」，**不给**一个两者相加的总数字 —— 那个数字会让日常补货失焦，
 *       正是蓝图加粗句「大促备货与日常补货要分开」要防的事。</li>
 *   <li><b>算式随行显示</b>：计划量旁边就是后端给的 {@code quantityBasis} 原文，
 *       「大促计划量为什么是建议量的三倍」不用点进去再猜。</li>
 *   <li><b>锁定状态如实显示</b>：失败时显示失败原因原文；未接线的端口由能力声明横幅说明，
 *       页面**不假装**锁库可用。</li>
 *   <li><b>加载 / 空 / 错误三态齐全</b>。</li>
 * </ul>
 */

const PAGE_SIZE = 20;

function today(): string {
  return new Date().toISOString().slice(0, 10);
}

function monthStart(): string {
  const now = new Date();
  return `${now.getUTCFullYear()}-${String(now.getUTCMonth() + 1).padStart(2, '0')}-01`;
}

function monthEnd(): string {
  const now = new Date();
  const end = new Date(Date.UTC(now.getUTCFullYear(), now.getUTCMonth() + 1, 0));
  return end.toISOString().slice(0, 10);
}

export function PurchasePlanWorkspace() {
  const [params, setParams] = useState<PurchasePlanParams>({ page: 0, size: PAGE_SIZE });
  const [plans, setPlans] = useState<Awaited<ReturnType<typeof fetchPurchasePlans>> | null>(null);
  const [caps, setCaps] = useState<PurchasePlanCapabilities | null>(null);
  const [options, setOptions] = useState<PurchasePlanOptions | null>(null);
  const [summary, setSummary] = useState<PurchasePlanSummary | null>(null);
  const [campaigns, setCampaigns] = useState<PurchasePlanCampaignRow[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [keyword, setKeyword] = useState('');
  const [planType, setPlanType] = useState('');
  const [status, setStatus] = useState('');
  const [sort, setSort] = useState('period');

  const [detail, setDetail] = useState<{
    plan: PurchasePlanRow;
    lines: PurchasePlanLineRow[];
  } | null>(null);
  const [lineParams, setLineParams] = useState<PurchasePlanLineParams>({
    page: 0,
    size: PAGE_SIZE,
  });
  const [lines, setLines] = useState<Awaited<ReturnType<typeof fetchPurchasePlanLines>> | null>(
    null,
  );

  const [creating, setCreating] = useState(false);
  const [aggregating, setAggregating] = useState(false);
  const [campaignModal, setCampaignModal] = useState(false);
  const [adjusting, setAdjusting] = useState<PurchasePlanLineRow | null>(null);
  const [busy, setBusy] = useState(false);

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const [list, sum] = await Promise.all([
        fetchPurchasePlans(params),
        fetchPurchasePlanSummary({
          periodStart: params.periodStart,
          periodEnd: params.periodEnd,
          targetWarehouseId: params.targetWarehouseId,
        }),
      ]);
      setPlans(list);
      setSummary(sum);
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
        setCaps(await fetchPurchasePlanCapabilities());
      } catch {
        // capabilities 拿不到不该让整页空掉：页面不假装自己知道规则。
        setCaps(null);
      }
      try {
        setOptions(await fetchPurchasePlanOptions());
      } catch {
        setOptions(null);
      }
      try {
        const page = await fetchPurchasePlanCampaigns({ size: 50 });
        setCampaigns(page.rows);
      } catch {
        setCampaigns([]);
      }
    })();
  }, []);

  const loadLines = useCallback(
    async (planId: string) => {
      try {
        setLines(await fetchPurchasePlanLines(planId, lineParams));
      } catch (e) {
        setError(errorText(e));
      }
    },
    [lineParams],
  );

  const run = useCallback(
    async (work: () => Promise<unknown>) => {
      setBusy(true);
      setError(null);
      try {
        await work();
        await load();
      } catch (e) {
        setError(errorText(e));
      } finally {
        setBusy(false);
      }
    },
    [load],
  );

  const openDetail = useCallback(
    async (planId: string) => {
      setError(null);
      try {
        const plan = await fetchPurchasePlan(planId);
        setDetail({ plan: plan.plan, lines: plan.lines });
        setLineParams({ page: 0, size: PAGE_SIZE });
        await loadLines(planId);
      } catch (e) {
        setError(errorText(e));
      }
      // eslint-disable-next-line react-hooks/exhaustive-deps
    },
    [loadLines],
  );

  const rows = plans?.rows ?? [];

  const campaignOptions = useMemo(
    () => (options?.campaigns ?? []).map((c) => ({ id: c.id, label: c.code })),
    [options],
  );

  const nextParams = (): PurchasePlanParams => {
    const next: PurchasePlanParams = { page: 0, size: PAGE_SIZE, sort };
    if (keyword.trim()) next.keyword = keyword.trim();
    if (planType) next.planType = planType;
    if (status) next.status = status;
    return next;
  };

  return (
    <div className="flex flex-col gap-4">
      <PageHeader
        title="采购计划"
        description="按周期（大促 / 月 / 季）、按目标仓把补货建议汇总成计划行，支持调整、锁定与导出给采购员。大促备货与日常补货分开建、分开算、分开看。"
        actions={
          <div className="flex gap-2">
            <Button variant="ghost" onClick={() => void load()} disabled={loading}>
              <RefreshCw className={loading ? 'animate-spin' : ''} />
              刷新
            </Button>
            <Button variant="ghost" onClick={() => setCampaignModal(true)} disabled={busy}>
              <CalendarClock />
              大促档期
            </Button>
            <Button onClick={() => setCreating(true)} disabled={busy}>
              <Plus />
              新建采购计划
            </Button>
          </div>
        }
      />

      {caps && !caps.lockPortAvailable && (
        <div className="flex items-start gap-2 rounded-md border border-amber-500/40 bg-amber-500/10 p-3 text-sm">
          <TriangleAlert className="mt-0.5 size-4 shrink-0" />
          <div>
            <p className="font-medium">锁库端口未接线</p>
            <p className="text-muted-foreground">{caps.lockPortNote}</p>
          </div>
        </div>
      )}

      {caps && (
        <div className="flex items-start gap-2 rounded-md border border-sky-500/40 bg-sky-500/10 p-3 text-sm">
          <CalendarClock className="mt-0.5 size-4 shrink-0" />
          <div>
            <p className="font-medium">计划量 = 日常取建议量；大促取建议量 × 销量系数 × 备货系数</p>
            <p className="text-muted-foreground">{caps.quantityFormula}</p>
            <p className="text-muted-foreground mt-1">{caps.arrivalFormula}</p>
          </div>
        </div>
      )}

      {/* 汇总：**两段分开**，不相加 */}
      {summary && (
        <div className="grid gap-3 lg:grid-cols-2">
          <SectionCard title="日常补货" description="日常备货量 —— 与大促分开的独立一段">
            <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
              <StatCard label="计划数" value={summary.routine.planCount} />
              <StatCard label="计划行" value={summary.routine.lineCount} />
              <StatCard label="备货量" value={fmt(summary.routine.totalPlanQuantity)} />
              <StatCard label="已锁定" value={summary.routine.lockedLineCount} />
            </div>
            <p className="text-muted-foreground mt-2 text-xs">
              建议量合计 {fmt(summary.routine.totalSuggestedQuantity)}；
              {summary.routine.failedLineCount > 0
                ? `有 ${summary.routine.failedLineCount} 行锁定失败。`
                : '没有锁定失败的行。'}
            </p>
          </SectionCard>
          <SectionCard title="大促备货" description="大促备货量 —— 备货量大、周期长，单独一段">
            <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
              <StatCard label="计划数" value={summary.campaign.planCount} />
              <StatCard label="计划行" value={summary.campaign.lineCount} />
              <StatCard label="备货量" value={fmt(summary.campaign.totalPlanQuantity)} />
              <StatCard label="已锁定" value={summary.campaign.lockedLineCount} />
            </div>
            <p className="text-muted-foreground mt-2 text-xs">
              建议量合计 {fmt(summary.campaign.totalSuggestedQuantity)}；
              {summary.campaign.failedLineCount > 0
                ? `有 ${summary.campaign.failedLineCount} 行锁定失败。`
                : '没有锁定失败的行。'}
            </p>
          </SectionCard>
        </div>
      )}

      {summary?.note && <p className="text-muted-foreground text-xs">{summary.note}</p>}

      <SectionCard title="筛选">
        <div className="flex flex-wrap items-end gap-3">
          <label className="flex flex-col gap-1 text-sm">
            <span className="text-muted-foreground">关键字</span>
            <Input
              value={keyword}
              placeholder="计划号"
              onChange={(e) => setKeyword(e.target.value)}
              className="w-56"
            />
          </label>
          <label className="flex flex-col gap-1 text-sm">
            <span className="text-muted-foreground">计划类型</span>
            <select
              className="h-9 rounded-md border px-2"
              value={planType}
              onChange={(e) => setPlanType(e.target.value)}
            >
              <option value="">全部</option>
              <option value="ROUTINE">日常补货</option>
              <option value="CAMPAIGN">大促备货</option>
            </select>
          </label>
          <label className="flex flex-col gap-1 text-sm">
            <span className="text-muted-foreground">状态</span>
            <select
              className="h-9 rounded-md border px-2"
              value={status}
              onChange={(e) => setStatus(e.target.value)}
            >
              <option value="">全部</option>
              <option value="DRAFT">草稿</option>
              <option value="CONFIRMED">已确认</option>
              <option value="LOCKING">锁定中</option>
              <option value="LOCKED">已锁定</option>
              <option value="PARTIALLY_LOCKED">部分锁定</option>
              <option value="LOCK_FAILED">锁定失败</option>
            </select>
          </label>
          <label className="flex flex-col gap-1 text-sm">
            <span className="text-muted-foreground">排序</span>
            <select
              className="h-9 rounded-md border px-2"
              value={sort}
              onChange={(e) => setSort(e.target.value)}
            >
              <option value="period">周期倒序</option>
              <option value="created">创建时间倒序</option>
              <option value="priority">优先级</option>
              <option value="status">状态</option>
              <option value="type">类型</option>
            </select>
          </label>
          <Button variant="secondary" onClick={() => setParams(nextParams())} disabled={loading}>
            应用筛选
          </Button>
        </div>
      </SectionCard>

      {error && (
        <div className="rounded-md border border-red-500/40 bg-red-500/10 p-3 text-sm text-red-700">
          {error}
        </div>
      )}

      <SectionCard title="采购计划">
        {loading && !plans ? (
          <p className="text-muted-foreground text-sm">加载中…</p>
        ) : rows.length === 0 ? (
          <EmptyState
            title="还没有采购计划"
            description="先新建一张计划（选周期、类型与目标仓），再从 6.2.1.2 的补货建议汇总成计划行。"
          />
        ) : (
          <div className="flex flex-col gap-2">
            {rows.map((plan) => (
              <div
                key={plan.id}
                className="flex flex-wrap items-center justify-between gap-3 rounded-md border p-3"
              >
                <div className="flex flex-col gap-1">
                  <div className="flex flex-wrap items-center gap-2">
                    <span className="font-medium">{plan.planNo}</span>
                    <Badge tone={planTypeTone(plan.planType)}>{planTypeLabel(plan.planType)}</Badge>
                    <Badge tone="neutral">{planStatusLabel(plan.status)}</Badge>
                    <Badge tone="neutral">优先级 {priorityLabel(plan.priority)}</Badge>
                  </div>
                  <p className="text-muted-foreground text-xs">
                    {periodTypeLabel(plan.periodType)} {plan.periodStart} ~ {plan.periodEnd} ·
                    目标仓 {plan.targetWarehouseName || plan.targetWarehouseCode || '—'}
                    {plan.campaignCode ? ` · 大促 ${plan.campaignCode}` : ''} · {plan.lineCount} 行
                    · 备货量 {fmt(plan.totalPlanQuantity)}
                  </p>
                  {plan.note && <p className="text-muted-foreground text-xs">{plan.note}</p>}
                </div>
                <div className="flex gap-2">
                  <Button variant="ghost" onClick={() => void openDetail(plan.id)}>
                    查看计划行
                  </Button>
                  <Button
                    variant="ghost"
                    onClick={() =>
                      void run(async () => {
                        await downloadPurchasePlanCsv(plan.id, plan.planNo);
                      })
                    }
                  >
                    <Download />
                    导出 CSV
                  </Button>
                </div>
              </div>
            ))}
            {plans && (
              <p className="text-muted-foreground text-xs">
                共 {plans.total} 张 · 第 {plans.page + 1} 页
              </p>
            )}
          </div>
        )}
      </SectionCard>

      {detail && (
        <SectionCard
          title={`计划行 · ${detail.plan.planNo}`}
          description={`${planTypeLabel(detail.plan.planType)} · 目标仓 ${
            detail.plan.targetWarehouseName || detail.plan.targetWarehouseCode || '—'
          }`}
        >
          <div className="flex flex-wrap gap-2">
            {detail.plan.status === 'DRAFT' && (
              <Button
                variant="secondary"
                disabled={busy}
                onClick={() =>
                  void run(async () => {
                    await confirmPurchasePlan(detail.plan.id);
                    await openDetail(detail.plan.id);
                  })
                }
              >
                确认计划
              </Button>
            )}
            <Button variant="secondary" disabled={busy} onClick={() => setAggregating(true)}>
              从补货建议汇总
            </Button>
            <Button
              variant="ghost"
              onClick={() =>
                void run(async () => {
                  await downloadPurchasePlanCsv(detail.plan.id, detail.plan.planNo);
                })
              }
            >
              <Download />
              导出 CSV
            </Button>
          </div>

          {lines && lines.rows.length === 0 && (
            <EmptyState
              title="这张计划还没有计划行"
              description="点「从补货建议汇总」，把 6.2.1.2 的补货建议批次汇进来。"
            />
          )}

          {lines && lines.rows.length > 0 && (
            <div className="flex flex-col gap-2">
              {lines.rows.map((line) => (
                <div
                  key={line.id}
                  className="flex flex-wrap items-start justify-between gap-3 rounded-md border p-3"
                >
                  <div className="flex flex-col gap-1">
                    <div className="flex flex-wrap items-center gap-2">
                      <span className="font-medium">{line.skuCode}</span>
                      <span className="text-sm">{line.skuName}</span>
                      <Badge tone="neutral">{priorityLabel(line.priority)}</Badge>
                      <Badge tone="neutral">{lineStatusLabel(line.status)}</Badge>
                      <Badge tone={lockTone(line.lockStatus)}>
                        {lockStatusLabel(line.lockStatus)}
                      </Badge>
                    </div>
                    <p className="text-muted-foreground text-xs">
                      建议量 {suggestedQuantityText(line.suggestedQuantity)} → 计划量{' '}
                      {fmt(line.planQuantity)} · 目标仓{' '}
                      {line.targetWarehouseName || line.targetWarehouseCode || '—'} · 期望到货日{' '}
                      {line.expectedArrivalDate}
                    </p>
                    <p className="text-muted-foreground text-xs">算式：{line.quantityBasis}</p>
                    {line.lockFailureReason && (
                      <p className="text-xs text-red-600">锁定失败原因：{line.lockFailureReason}</p>
                    )}
                    {line.lockReservationNo && (
                      <p className="text-muted-foreground text-xs">
                        预占单号 {line.lockReservationNo}
                      </p>
                    )}
                  </div>
                  <div className="flex gap-2">
                    <Button
                      variant="ghost"
                      disabled={busy || !isAdjustable(line.lockStatus)}
                      onClick={() => setAdjusting(line)}
                    >
                      <Pencil />
                      调整
                    </Button>
                    <Button
                      variant="ghost"
                      disabled={busy || !isLockable(line.lockStatus)}
                      onClick={() =>
                        void run(async () => {
                          await lockPurchasePlanLine(detail.plan.id, line.id, { note: null });
                          await openDetail(detail.plan.id);
                        })
                      }
                    >
                      <Lock />
                      锁定
                    </Button>
                  </div>
                </div>
              ))}
              <p className="text-muted-foreground text-xs">
                共 {lines.total} 行 · 第 {lines.page + 1} 页
              </p>
            </div>
          )}
        </SectionCard>
      )}

      {creating && (
        <CreatePlanModal
          options={options}
          campaigns={campaignOptions}
          onClose={() => setCreating(false)}
          onSubmit={async (input) => {
            await run(async () => {
              await createPurchasePlan(input);
              setCreating(false);
            });
          }}
        />
      )}

      {aggregating && detail && (
        <AggregateModal
          onClose={() => setAggregating(false)}
          onSubmit={async (batchId) => {
            await run(async () => {
              await aggregatePurchasePlan(detail.plan.id, {
                sourceBatchId: batchId,
                skuCode: null,
                keyword: null,
                defaultPriority: null,
                note: null,
              });
              setAggregating(false);
              await openDetail(detail.plan.id);
            });
          }}
        />
      )}

      {adjusting && detail && (
        <AdjustLineModal
          line={adjusting}
          onClose={() => setAdjusting(null)}
          onSubmit={async (input) => {
            await run(async () => {
              await adjustPurchasePlanLine(detail.plan.id, adjusting.id, input);
              setAdjusting(null);
              await openDetail(detail.plan.id);
            });
          }}
        />
      )}

      {campaignModal && (
        <CampaignModal
          campaigns={campaigns}
          onClose={() => setCampaignModal(false)}
          onSubmit={async (input) => {
            await run(async () => {
              await createPurchasePlanCampaign(input);
              const page = await fetchPurchasePlanCampaigns({ size: 50 });
              setCampaigns(page.rows);
            });
          }}
          onDelete={async (id) => {
            await run(async () => {
              await deletePurchasePlanCampaign(id);
              const page = await fetchPurchasePlanCampaigns({ size: 50 });
              setCampaigns(page.rows);
            });
          }}
        />
      )}
    </div>
  );
}

// ================================================================ 新建计划

function CreatePlanModal({
  options,
  campaigns,
  onClose,
  onSubmit,
}: {
  options: PurchasePlanOptions | null;
  campaigns: { id: string; label: string }[];
  onClose: () => void;
  onSubmit: (input: {
    planType: 'ROUTINE' | 'CAMPAIGN';
    campaignId: string | null;
    periodType: 'MONTHLY' | 'QUARTERLY';
    periodStart: string;
    periodEnd: string;
    targetWarehouseId: string;
    legalEntityId: string;
    priority: 'CRITICAL' | 'HIGH' | 'NORMAL' | 'LOW';
    note: string | null;
  }) => Promise<void>;
}) {
  const [planType, setPlanType] = useState<'ROUTINE' | 'CAMPAIGN'>('ROUTINE');
  const [campaignId, setCampaignId] = useState('');
  const [periodType, setPeriodType] = useState<'MONTHLY' | 'QUARTERLY'>('MONTHLY');
  const [periodStart, setPeriodStart] = useState(monthStart());
  const [periodEnd, setPeriodEnd] = useState(monthEnd());
  const [warehouseId, setWarehouseId] = useState('');
  const [legalEntityId, setLegalEntityId] = useState('');
  const [priority, setPriority] = useState<'CRITICAL' | 'HIGH' | 'NORMAL' | 'LOW'>('NORMAL');

  return (
    <Modal open onOpenChange={(open) => !open && onClose()}>
      <ModalContent>
        <ModalHeader>
          <ModalTitle>新建采购计划</ModalTitle>
          <ModalDescription>
            周期（月 / 季）、类型（日常 / 大促）与目标仓。计划行随后从 6.2.1.2 的补货建议汇总。
          </ModalDescription>
        </ModalHeader>
        <div className="grid gap-3 sm:grid-cols-2">
          <label className="flex flex-col gap-1 text-sm">
            <span className="text-muted-foreground">计划类型</span>
            <select
              className="h-9 rounded-md border px-2"
              value={planType}
              onChange={(e) => setPlanType(e.target.value as 'ROUTINE' | 'CAMPAIGN')}
            >
              <option value="ROUTINE">日常补货</option>
              <option value="CAMPAIGN">大促备货</option>
            </select>
          </label>
          {planType === 'CAMPAIGN' && (
            <label className="flex flex-col gap-1 text-sm">
              <span className="text-muted-foreground">大促档期</span>
              <select
                className="h-9 rounded-md border px-2"
                value={campaignId}
                onChange={(e) => setCampaignId(e.target.value)}
              >
                <option value="">请选择</option>
                {campaigns.map((c) => (
                  <option key={c.id} value={c.id}>
                    {c.label}
                  </option>
                ))}
              </select>
            </label>
          )}
          <label className="flex flex-col gap-1 text-sm">
            <span className="text-muted-foreground">周期类型</span>
            <select
              className="h-9 rounded-md border px-2"
              value={periodType}
              onChange={(e) => setPeriodType(e.target.value as 'MONTHLY' | 'QUARTERLY')}
            >
              <option value="MONTHLY">月度</option>
              <option value="QUARTERLY">季度</option>
            </select>
          </label>
          <label className="flex flex-col gap-1 text-sm">
            <span className="text-muted-foreground">周期起点</span>
            <Input
              type="date"
              value={periodStart}
              onChange={(e) => setPeriodStart(e.target.value)}
            />
          </label>
          <label className="flex flex-col gap-1 text-sm">
            <span className="text-muted-foreground">周期终点</span>
            <Input type="date" value={periodEnd} onChange={(e) => setPeriodEnd(e.target.value)} />
          </label>
          <label className="flex flex-col gap-1 text-sm">
            <span className="text-muted-foreground">目标仓</span>
            <select
              className="h-9 rounded-md border px-2"
              value={warehouseId}
              onChange={(e) => setWarehouseId(e.target.value)}
            >
              <option value="">请选择</option>
              {(options?.warehouses ?? []).map((w) => (
                <option key={w.id} value={w.id}>
                  {w.name || w.code}
                </option>
              ))}
            </select>
          </label>
          <label className="flex flex-col gap-1 text-sm">
            <span className="text-muted-foreground">法人</span>
            <select
              className="h-9 rounded-md border px-2"
              value={legalEntityId}
              onChange={(e) => setLegalEntityId(e.target.value)}
            >
              <option value="">请选择</option>
              {(options?.legalEntities ?? []).map((l) => (
                <option key={l.id} value={l.id}>
                  {l.name || l.code}
                </option>
              ))}
            </select>
          </label>
          <label className="flex flex-col gap-1 text-sm">
            <span className="text-muted-foreground">优先级</span>
            <select
              className="h-9 rounded-md border px-2"
              value={priority}
              onChange={(e) =>
                setPriority(e.target.value as 'CRITICAL' | 'HIGH' | 'NORMAL' | 'LOW')
              }
            >
              <option value="CRITICAL">紧急</option>
              <option value="HIGH">高</option>
              <option value="NORMAL">中</option>
              <option value="LOW">低</option>
            </select>
          </label>
        </div>
        {options?.warehouseNote && (
          <p className="text-muted-foreground text-xs">{options.warehouseNote}</p>
        )}
        <ModalFooter>
          <Button variant="ghost" onClick={onClose}>
            取消
          </Button>
          <Button
            disabled={
              !warehouseId ||
              !legalEntityId ||
              !periodStart ||
              !periodEnd ||
              (planType === 'CAMPAIGN' && !campaignId)
            }
            onClick={() =>
              void onSubmit({
                planType,
                campaignId: planType === 'CAMPAIGN' ? campaignId : null,
                periodType,
                periodStart,
                periodEnd,
                targetWarehouseId: warehouseId,
                legalEntityId,
                priority,
                note: null,
              })
            }
          >
            创建
          </Button>
        </ModalFooter>
      </ModalContent>
    </Modal>
  );
}

// ================================================================ 汇总

function AggregateModal({
  onClose,
  onSubmit,
}: {
  onClose: () => void;
  onSubmit: (sourceBatchId: string) => Promise<void>;
}) {
  const [batchId, setBatchId] = useState('');
  return (
    <Modal open onOpenChange={(open) => !open && onClose()}>
      <ModalContent>
        <ModalHeader>
          <ModalTitle>从补货建议汇总成计划行</ModalTitle>
          <ModalDescription>
            来源是 6.2.1.2 的补货建议批次。只有「建议补货」且未被忽略的建议会变成计划行；
            已经在这张计划里的 SKU 会被跳过，不覆盖已调整的数量。
          </ModalDescription>
        </ModalHeader>
        <label className="flex flex-col gap-1 text-sm">
          <span className="text-muted-foreground">补货建议批次 id</span>
          <Input
            value={batchId}
            placeholder="RS 批次的 id"
            onChange={(e) => setBatchId(e.target.value)}
          />
        </label>
        <ModalFooter>
          <Button variant="ghost" onClick={onClose}>
            取消
          </Button>
          <Button disabled={!batchId.trim()} onClick={() => void onSubmit(batchId.trim())}>
            汇总
          </Button>
        </ModalFooter>
      </ModalContent>
    </Modal>
  );
}

// ================================================================ 调整

function AdjustLineModal({
  line,
  onClose,
  onSubmit,
}: {
  line: PurchasePlanLineRow;
  onClose: () => void;
  onSubmit: (input: {
    planQuantity: number | null;
    expectedArrivalDate: string | null;
    priority: 'CRITICAL' | 'HIGH' | 'NORMAL' | 'LOW' | null;
    targetWarehouseId: string | null;
    note: string | null;
    reason: string;
  }) => Promise<void>;
}) {
  const [quantity, setQuantity] = useState(String(line.planQuantity));
  const [arrival, setArrival] = useState(line.expectedArrivalDate);
  const [priority, setPriority] = useState<'CRITICAL' | 'HIGH' | 'NORMAL' | 'LOW'>(
    (line.priority as 'CRITICAL' | 'HIGH' | 'NORMAL' | 'LOW') ?? 'NORMAL',
  );
  const [reason, setReason] = useState('');

  return (
    <Modal open onOpenChange={(open) => !open && onClose()}>
      <ModalContent>
        <ModalHeader>
          <ModalTitle>调整计划行 · {line.skuCode}</ModalTitle>
          <ModalDescription>
            当前计划量 {fmt(line.planQuantity)}，算式：{line.quantityBasis}
          </ModalDescription>
        </ModalHeader>
        <div className="grid gap-3 sm:grid-cols-2">
          <label className="flex flex-col gap-1 text-sm">
            <span className="text-muted-foreground">计划量</span>
            <Input value={quantity} onChange={(e) => setQuantity(e.target.value)} />
          </label>
          <label className="flex flex-col gap-1 text-sm">
            <span className="text-muted-foreground">期望到货日</span>
            <Input type="date" value={arrival} onChange={(e) => setArrival(e.target.value)} />
          </label>
          <label className="flex flex-col gap-1 text-sm">
            <span className="text-muted-foreground">优先级</span>
            <select
              className="h-9 rounded-md border px-2"
              value={priority}
              onChange={(e) =>
                setPriority(e.target.value as 'CRITICAL' | 'HIGH' | 'NORMAL' | 'LOW')
              }
            >
              <option value="CRITICAL">紧急</option>
              <option value="HIGH">高</option>
              <option value="NORMAL">中</option>
              <option value="LOW">低</option>
            </select>
          </label>
          <label className="flex flex-col gap-1 text-sm sm:col-span-2">
            <span className="text-muted-foreground">调整理由（必填）</span>
            <Input
              value={reason}
              placeholder="为什么改 —— 一句「调整一下」不是留痕"
              onChange={(e) => setReason(e.target.value)}
            />
          </label>
        </div>
        <ModalFooter>
          <Button variant="ghost" onClick={onClose}>
            取消
          </Button>
          <Button
            disabled={!reason.trim() || !quantity.trim()}
            onClick={() =>
              void onSubmit({
                planQuantity: Number(quantity),
                expectedArrivalDate: arrival || null,
                priority,
                targetWarehouseId: null,
                note: null,
                reason: reason.trim(),
              })
            }
          >
            保存调整
          </Button>
        </ModalFooter>
      </ModalContent>
    </Modal>
  );
}

// ================================================================ 大促档期

function CampaignModal({
  campaigns,
  onClose,
  onSubmit,
  onDelete,
}: {
  campaigns: PurchasePlanCampaignRow[];
  onClose: () => void;
  onSubmit: (input: {
    code: string;
    name: string;
    shopCode: string | null;
    startDate: string;
    endDate: string;
    salesMultiplier: number;
    stockFactor: number;
    arrivalLeadDays: number;
    status: 'PLANNED' | 'ACTIVE' | 'CLOSED' | null;
    note: string | null;
  }) => Promise<void>;
  onDelete: (id: string) => Promise<void>;
}) {
  const [code, setCode] = useState('');
  const [name, setName] = useState('');
  const [shopCode, setShopCode] = useState('');
  const [startDate, setStartDate] = useState(today());
  const [endDate, setEndDate] = useState(today());
  const [salesMultiplier, setSalesMultiplier] = useState('2');
  const [stockFactor, setStockFactor] = useState('1');
  const [arrivalLeadDays, setArrivalLeadDays] = useState('15');

  return (
    <Modal open onOpenChange={(open) => !open && onClose()}>
      <ModalContent>
        <ModalHeader>
          <ModalTitle>大促档期</ModalTitle>
          <ModalDescription>
            档期与系数决定大促计划的备货量与期望到货日：计划量 = 建议量 × 销量系数 × 备货系数；
            期望到货日 = 大促开始日 − 提前天数。
          </ModalDescription>
        </ModalHeader>
        <div className="grid gap-3 sm:grid-cols-2">
          <label className="flex flex-col gap-1 text-sm">
            <span className="text-muted-foreground">档期编码</span>
            <Input value={code} onChange={(e) => setCode(e.target.value)} />
          </label>
          <label className="flex flex-col gap-1 text-sm">
            <span className="text-muted-foreground">档期名</span>
            <Input value={name} onChange={(e) => setName(e.target.value)} />
          </label>
          <label className="flex flex-col gap-1 text-sm">
            <span className="text-muted-foreground">店铺引用码（可空 = 全店铺）</span>
            <Input value={shopCode} onChange={(e) => setShopCode(e.target.value)} />
          </label>
          <label className="flex flex-col gap-1 text-sm">
            <span className="text-muted-foreground">开始日</span>
            <Input type="date" value={startDate} onChange={(e) => setStartDate(e.target.value)} />
          </label>
          <label className="flex flex-col gap-1 text-sm">
            <span className="text-muted-foreground">结束日</span>
            <Input type="date" value={endDate} onChange={(e) => setEndDate(e.target.value)} />
          </label>
          <label className="flex flex-col gap-1 text-sm">
            <span className="text-muted-foreground">预期销量系数</span>
            <Input value={salesMultiplier} onChange={(e) => setSalesMultiplier(e.target.value)} />
          </label>
          <label className="flex flex-col gap-1 text-sm">
            <span className="text-muted-foreground">备货系数</span>
            <Input value={stockFactor} onChange={(e) => setStockFactor(e.target.value)} />
          </label>
          <label className="flex flex-col gap-1 text-sm">
            <span className="text-muted-foreground">到货提前天数</span>
            <Input value={arrivalLeadDays} onChange={(e) => setArrivalLeadDays(e.target.value)} />
          </label>
        </div>
        <ModalFooter>
          <Button variant="ghost" onClick={onClose}>
            关闭
          </Button>
          <Button
            disabled={!code.trim() || !name.trim() || !startDate || !endDate}
            onClick={() =>
              void onSubmit({
                code: code.trim(),
                name: name.trim(),
                shopCode: shopCode.trim() || null,
                startDate,
                endDate,
                salesMultiplier: Number(salesMultiplier) || 1,
                stockFactor: Number(stockFactor) || 1,
                arrivalLeadDays: Number(arrivalLeadDays) || 0,
                status: null,
                note: null,
              })
            }
          >
            登记档期
          </Button>
        </ModalFooter>
        <div className="flex flex-col gap-2">
          {campaigns.length === 0 ? (
            <p className="text-muted-foreground text-xs">还没有登记大促档期。</p>
          ) : (
            campaigns.map((c) => (
              <div
                key={c.id}
                className="flex flex-wrap items-center justify-between gap-2 rounded-md border p-2 text-sm"
              >
                <div>
                  <span className="font-medium">{c.code}</span>
                  <span className="text-muted-foreground"> · {c.name}</span>
                  <p className="text-muted-foreground text-xs">
                    {c.startDate} ~ {c.endDate} · 销量系数 {fmt(c.salesMultiplier)} · 备货系数{' '}
                    {fmt(c.stockFactor)} · 提前 {c.arrivalLeadDays} 天 ·{' '}
                    {campaignStatusLabel(c.status)}
                  </p>
                </div>
                <Button variant="ghost" onClick={() => void onDelete(c.id)}>
                  删除
                </Button>
              </div>
            ))
          )}
        </div>
      </ModalContent>
    </Modal>
  );
}
