'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import { PackageSearch, Plus, RefreshCw, TriangleAlert } from 'lucide-react';
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
  DevelopmentMarketObservationRow,
  DevelopmentProjectCapabilities,
  DevelopmentProjectDetail,
  DevelopmentProjectOptions,
  DevelopmentProjectSummary,
  DevelopmentTransitionRow,
} from '../../../../../packages/api-contracts/src/index';
import {
  allowedTargetOptions,
  applyDevelopmentCostEstimate,
  costSourceLabel,
  costText,
  createDevelopmentHandoff,
  createDevelopmentMarket,
  createDevelopmentMarketObservation,
  createDevelopmentMilestone,
  createDevelopmentProject,
  createDevelopmentSpecCandidate,
  deleteDevelopmentMarket,
  deleteDevelopmentMilestone,
  deleteDevelopmentSpecCandidate,
  fetchDevelopmentCostEstimate,
  fetchDevelopmentProject,
  fetchDevelopmentProjectCapabilities,
  fetchDevelopmentProjectOptions,
  fetchDevelopmentProjectSummary,
  fetchDevelopmentProjects,
  fetchDevelopmentSkuCandidate,
  handoffStatusText,
  marginText,
  milestoneStatusLabel,
  money,
  stateMachineHint,
  statusLabel,
  statusTone,
  timeText,
  transitionDevelopmentProject,
  transitionNeedsExemption,
  type DevelopmentHandoffType,
  type DevelopmentMilestoneType,
  type DevelopmentProjectParams,
  type DevelopmentStatus,
} from './development-project-model';
import { errorText } from '../inventory/inventory-model';

/**
 * 6.6.1.1 开发立项工作区。
 *
 * <p>蓝图：「管理新品开发立项：立项编号、品类、目标市场与平台、目标售价与毛利、开发负责人、
 * 打样与量产时间计划、状态（评估/立项/开发中/打样/试产/量产/终止）。立项通过后可派生打样单与试产单，
 * 并作为 SKU 建档的前置。」输出「开发单（立项记录 + 里程碑计划）；可转 SKU 建档的候选参数」。
 *
 * <p>界面纪律：
 *
 * <ul>
 *   <li><b>目标毛利取不到就显示「取不到」</b>：成本不可得时后端给 null，页面把它渲染成
 *       0.00% 就是在说假话（成本 0 的毛利率是 100%）。</li>
 *   <li><b>状态只给合法的去向</b>：目标状态下拉来自后端的 allowedTargets，
 *       页面不自己维护第二份状态机。</li>
 *   <li><b>跳过打样 / 试产要显式填豁免</b>：页面不提供「悄悄跳过去」的路径。</li>
 *   <li><b>下游未交付的模块不假装可用</b>：打样 / 试产只产交接单，
 *       页面显示「已交接，待 6.6.1.2 消费」。</li>
 *   <li><b>市场数据缺口常驻</b>：14 广告的 reporting 服务不在本仓，
 *       页面明说「系统侧市场数据未交付」，手工录入必须填来源与采集时间。</li>
 *   <li>加载 / 空 / 错误三态齐全。</li>
 * </ul>
 */

const PAGE_SIZE = 20;

/** 里程碑类型下拉（与后端枚举一致，标签取自后端的 milestoneTypeLabel）。 */
const MILESTONE_TYPES: { value: DevelopmentMilestoneType; label: string }[] = [
  { value: 'SAMPLING', label: '打样' },
  { value: 'PILOT', label: '试产' },
  { value: 'MASS_PRODUCTION', label: '量产' },
  { value: 'LAUNCH', label: '上市' },
];

const HANDOFF_TYPES: { value: DevelopmentHandoffType; label: string; target: string }[] = [
  { value: 'SAMPLING_ORDER', label: '打样单', target: '6.6.1.2' },
  { value: 'PILOT_ORDER', label: '试产单', target: '6.6.2.1' },
  { value: 'SKU_ONBOARDING', label: 'SKU 建档', target: '3.1.1.1' },
];

export function DevelopmentProjectWorkspace() {
  const [params, setParams] = useState<DevelopmentProjectParams>({ page: 0, size: PAGE_SIZE });
  const [data, setData] = useState<Awaited<ReturnType<typeof fetchDevelopmentProjects>> | null>(
    null,
  );
  const [caps, setCaps] = useState<DevelopmentProjectCapabilities | null>(null);
  const [options, setOptions] = useState<DevelopmentProjectOptions | null>(null);
  const [summary, setSummary] = useState<DevelopmentProjectSummary | null>(null);
  const [detail, setDetail] = useState<DevelopmentProjectDetail | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [keyword, setKeyword] = useState('');
  const [status, setStatus] = useState('');
  const [creating, setCreating] = useState(false);

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      setData(await fetchDevelopmentProjects(params));
    } catch (e) {
      setError(errorText(e));
    } finally {
      setLoading(false);
    }
  }, [params]);

  const loadSummary = useCallback(async () => {
    try {
      setSummary(await fetchDevelopmentProjectSummary());
    } catch {
      // 概览拿不到不该让整页空掉：清单仍然可用。
      setSummary(null);
    }
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  useEffect(() => {
    void (async () => {
      try {
        setCaps(await fetchDevelopmentProjectCapabilities());
      } catch {
        // capabilities 拿不到不该让整页空掉：页面不假装自己知道上下游。
        setCaps(null);
      }
      try {
        setOptions(await fetchDevelopmentProjectOptions());
      } catch {
        setOptions(null);
      }
    })();
    void loadSummary();
  }, [loadSummary]);

  const run = useCallback(
    async (work: () => Promise<unknown>) => {
      setBusy(true);
      setError(null);
      try {
        await work();
        await load();
        await loadSummary();
      } catch (e) {
        setError(errorText(e));
      } finally {
        setBusy(false);
      }
    },
    [load, loadSummary],
  );

  const openDetail = useCallback(async (id: string) => {
    try {
      setDetail(await fetchDevelopmentProject(id));
    } catch (e) {
      setError(errorText(e));
    }
  }, []);

  const rows = data?.rows ?? [];

  const stats = useMemo(() => {
    const counts = summary?.statuses ?? [];
    const by = (status: string) => counts.find((item) => item.status === status)?.count ?? 0;
    return {
      total: summary?.total ?? 0,
      approved: by('APPROVED'),
      running: by('IN_DEVELOPMENT') + by('SAMPLING') + by('PILOT'),
      massProduction: by('MASS_PRODUCTION'),
      terminated: by('TERMINATED'),
    };
  }, [summary]);

  const nextParams = (): DevelopmentProjectParams => {
    const next: DevelopmentProjectParams = { page: 0, size: PAGE_SIZE };
    if (keyword.trim()) next.keyword = keyword.trim();
    if (status) next.status = status;
    return next;
  };

  return (
    <div className="flex flex-col gap-4">
      <PageHeader
        title="开发立项"
        description="管理新品开发立项：立项编号、品类、目标市场与平台、目标售价与毛利、开发负责人、打样与量产时间计划，并把「开发-打样-试产-量产」纳入同一编号体系。"
        actions={
          <div className="flex gap-2">
            <Button variant="ghost" onClick={() => void load()} disabled={loading}>
              <RefreshCw className={loading ? 'animate-spin' : ''} />
              刷新
            </Button>
            <Button onClick={() => setCreating(true)} disabled={busy}>
              <Plus />
              立项
            </Button>
          </div>
        }
      />

      {/* 口径常驻：这一页的数从哪来、缺什么，必须一直摆在眼前 */}
      <div className="flex items-start gap-2 rounded-md border border-sky-500/40 bg-sky-500/10 p-3 text-sm">
        <PackageSearch className="mt-0.5 size-4 shrink-0" />
        <div className="flex flex-col gap-1">
          <p className="font-medium">目标毛利率由服务端算，成本取不到就不给毛利</p>
          <p className="text-muted-foreground">{stateMachineHint(caps)}</p>
          {caps && !caps.marketDataAvailable && (
            <p className="text-muted-foreground">
              🔴 系统侧市场数据（类目规模 / 竞品价位 / 搜索热度）**未交付**（归{' '}
              {caps.marketDataOwnerModule} 管）：{caps.marketDataNote}
            </p>
          )}
          {caps && (
            <p className="text-muted-foreground">
              下游：{caps.samplingModuleDelivered ? '6.6.1.2 已交付' : '6.6.1.2 未交付'} ·{' '}
              {caps.pilotModuleDelivered ? '6.6.2.1 已交付' : '6.6.2.1 未交付'} ·{' '}
              {caps.skuOnboardingDelivered
                ? '3.1.1.1 建档在 catalog，本模块只产候选参数'
                : '3.1.1.1 未交付'}
            </p>
          )}
        </div>
      </div>

      {summary && (
        <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-5">
          <StatCard label="立项总数" value={stats.total} />
          <StatCard label="已立项" value={stats.approved} />
          <StatCard label="推进中" value={stats.running} />
          <StatCard label="已量产" value={stats.massProduction} />
          <StatCard label="已终止" value={stats.terminated} />
        </div>
      )}

      <SectionCard title="筛选">
        <div className="flex flex-wrap items-end gap-3">
          <label className="flex flex-col gap-1 text-sm">
            <span className="text-muted-foreground">关键字</span>
            <Input
              value={keyword}
              placeholder="立项编号 / 名称 / 品类"
              onChange={(e) => setKeyword(e.target.value)}
              className="w-64"
            />
          </label>
          <label className="flex flex-col gap-1 text-sm">
            <span className="text-muted-foreground">状态</span>
            <select
              className="h-9 rounded-md border px-2"
              value={status}
              onChange={(e) => setStatus(e.target.value)}
            >
              <option value="">全部</option>
              {(options?.statuses ?? []).map((item) => (
                <option key={item.value} value={item.value}>
                  {item.label}
                </option>
              ))}
            </select>
          </label>
          <Button onClick={() => setParams(nextParams())}>查询</Button>
        </div>
      </SectionCard>

      {error && (
        <div className="flex items-start gap-2 rounded-md border border-red-500/40 bg-red-500/10 p-3 text-sm text-red-600">
          <TriangleAlert className="mt-0.5 size-4 shrink-0" />
          <p>{error}</p>
        </div>
      )}

      {loading && <p className="text-muted-foreground text-sm">正在加载开发立项清单…</p>}

      {!loading && rows.length === 0 && !error && (
        <EmptyState
          title="还没有开发立项"
          description="当前筛选条件下没有立项。先点右上角「立项」登记一条，或放宽筛选条件。"
        />
      )}

      {!loading && rows.length > 0 && (
        <SectionCard title={`开发立项清单（${data?.total ?? 0}）`}>
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead className="text-muted-foreground text-left">
                <tr>
                  <th className="px-2 py-1">立项编号</th>
                  <th className="px-2 py-1">名称 / 品类</th>
                  <th className="px-2 py-1">状态</th>
                  <th className="px-2 py-1">负责人</th>
                  <th className="px-2 py-1">目标售价</th>
                  <th className="px-2 py-1">目标成本</th>
                  <th className="px-2 py-1">目标毛利</th>
                  <th className="px-2 py-1">打样计划</th>
                  <th className="px-2 py-1">量产计划</th>
                  <th className="px-2 py-1" />
                </tr>
              </thead>
              <tbody>
                {rows.map((row) => (
                  <tr key={row.id} className="border-t">
                    <td className="px-2 py-1 font-medium">{row.projectNo}</td>
                    <td className="px-2 py-1">
                      {row.name}
                      <span className="text-muted-foreground"> / {row.category}</span>
                    </td>
                    <td className="px-2 py-1">
                      <Badge tone={statusTone(row.status)}>{statusLabel(row)}</Badge>
                    </td>
                    <td className="px-2 py-1">{row.ownerName || '未指定'}</td>
                    <td className="px-2 py-1">{money(row.targetPrice, row.currency)}</td>
                    <td className="px-2 py-1">
                      {costText(row.targetCost, row.costCurrency, row.costAvailable)}
                    </td>
                    <td className="px-2 py-1" title={row.marginBasis ?? undefined}>
                      {marginText(row.targetMarginRate)}
                    </td>
                    <td className="px-2 py-1">{timeText(row.samplingPlannedAt)}</td>
                    <td className="px-2 py-1">{timeText(row.massProductionPlannedAt)}</td>
                    <td className="px-2 py-1 text-right">
                      <Button variant="ghost" onClick={() => void openDetail(row.id)}>
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

      {creating && (
        <CreateDialog
          busy={busy}
          onCancel={() => setCreating(false)}
          onCreate={async (input) => {
            setCreating(false);
            await run(() => createDevelopmentProject(input));
          }}
        />
      )}

      {detail && (
        <DetailDialog
          detail={detail}
          options={options}
          caps={caps}
          busy={busy}
          onClose={() => setDetail(null)}
          onTransition={async (toStatus, reason, exemptionCode, exemptionNote) => {
            const updated = await transitionDevelopmentProject(detail.project.id, {
              toStatus,
              reason,
              exemptionCode,
              exemptionNote,
              occurredAt: null,
            });
            setDetail(updated);
            await load();
            await loadSummary();
          }}
          onRefresh={async () => {
            setDetail(await fetchDevelopmentProject(detail.project.id));
          }}
          onError={(message: string) => setError(message)}
        />
      )}
    </div>
  );
}

// ================================================================ 立项表单

function CreateDialog({
  busy,
  onCancel,
  onCreate,
}: {
  busy: boolean;
  onCancel: () => void;
  onCreate: (input: {
    projectNo: string;
    name: string;
    category: string;
    ownerId: string;
    ownerName: string;
    targetPrice: number;
    currency: string;
    targetCost: number | null;
    costBasis: string | null;
    costEstimatedAt: string | null;
    referenceSkuId: string | null;
    referenceSkuCode: string | null;
    decisionNote: string | null;
    launchTargetAt: string | null;
    note: string | null;
  }) => Promise<void>;
}) {
  const [projectNo, setProjectNo] = useState('');
  const [name, setName] = useState('');
  const [category, setCategory] = useState('');
  const [ownerId, setOwnerId] = useState('');
  const [ownerName, setOwnerName] = useState('');
  const [targetPrice, setTargetPrice] = useState('');
  const [currency, setCurrency] = useState('USD');
  const [targetCost, setTargetCost] = useState('');
  const [costBasis, setCostBasis] = useState('');
  const [referenceSkuId, setReferenceSkuId] = useState('');
  const [decisionNote, setDecisionNote] = useState('');
  const [note, setNote] = useState('');
  const [error, setError] = useState<string | null>(null);

  const submit = async () => {
    setError(null);
    if (!projectNo.trim() || !name.trim() || !category.trim() || !ownerId.trim()) {
      setError('立项编号、名称、品类、负责人 ID 必填');
      return;
    }
    const price = Number(targetPrice);
    if (!Number.isFinite(price) || price <= 0) {
      setError('目标售价必须大于 0');
      return;
    }
    const cost = targetCost.trim() === '' ? null : Number(targetCost);
    if (cost !== null && (!Number.isFinite(cost) || cost < 0)) {
      setError('目标成本不能为负');
      return;
    }
    await onCreate({
      projectNo: projectNo.trim(),
      name: name.trim(),
      category: category.trim(),
      ownerId: ownerId.trim(),
      ownerName: ownerName.trim(),
      targetPrice: price,
      currency: currency.trim().toUpperCase(),
      targetCost: cost,
      costBasis: costBasis.trim() || null,
      costEstimatedAt: null,
      referenceSkuId: referenceSkuId.trim() || null,
      referenceSkuCode: null,
      decisionNote: decisionNote.trim() || null,
      launchTargetAt: null,
      note: note.trim() || null,
    });
  };

  return (
    <Modal open onOpenChange={(open) => !open && onCancel()}>
      <ModalContent>
        <ModalHeader>
          <ModalTitle>立项</ModalTitle>
          <ModalDescription>
            立项编号在租户内唯一。目标毛利由服务端按「(目标售价 − 目标成本) ÷ 目标售价」算，
            成本留空时毛利是「取不到」，不会拿 0 顶替。
          </ModalDescription>
        </ModalHeader>
        <div className="grid gap-3 md:grid-cols-2">
          <label className="flex flex-col gap-1 text-sm">
            <span className="text-muted-foreground">立项编号 *</span>
            <Input value={projectNo} onChange={(e) => setProjectNo(e.target.value)} />
          </label>
          <label className="flex flex-col gap-1 text-sm">
            <span className="text-muted-foreground">名称 *</span>
            <Input value={name} onChange={(e) => setName(e.target.value)} />
          </label>
          <label className="flex flex-col gap-1 text-sm">
            <span className="text-muted-foreground">品类 *</span>
            <Input value={category} onChange={(e) => setCategory(e.target.value)} />
          </label>
          <label className="flex flex-col gap-1 text-sm">
            <span className="text-muted-foreground">开发负责人 ID *</span>
            <Input value={ownerId} onChange={(e) => setOwnerId(e.target.value)} />
          </label>
          <label className="flex flex-col gap-1 text-sm">
            <span className="text-muted-foreground">开发负责人姓名</span>
            <Input value={ownerName} onChange={(e) => setOwnerName(e.target.value)} />
          </label>
          <label className="flex flex-col gap-1 text-sm">
            <span className="text-muted-foreground">币种 *</span>
            <Input value={currency} onChange={(e) => setCurrency(e.target.value)} />
          </label>
          <label className="flex flex-col gap-1 text-sm">
            <span className="text-muted-foreground">目标售价 *</span>
            <Input
              value={targetPrice}
              onChange={(e) => setTargetPrice(e.target.value)}
              placeholder="例如 39.99"
            />
          </label>
          <label className="flex flex-col gap-1 text-sm">
            <span className="text-muted-foreground">目标成本（留空 = 取不到）</span>
            <Input
              value={targetCost}
              onChange={(e) => setTargetCost(e.target.value)}
              placeholder="例如 18.50"
            />
          </label>
          <label className="flex flex-col gap-1 text-sm md:col-span-2">
            <span className="text-muted-foreground">成本口径说明</span>
            <Input
              value={costBasis}
              onChange={(e) => setCostBasis(e.target.value)}
              placeholder="例如：供应商报价 + BOM 估算"
            />
          </label>
          <label className="flex flex-col gap-1 text-sm md:col-span-2">
            <span className="text-muted-foreground">参考 SKU（成本估算基准）</span>
            <Input
              value={referenceSkuId}
              onChange={(e) => setReferenceSkuId(e.target.value)}
              placeholder="catalog 里的既有 SKU id；有它才能从 5.3.1.1 取到系统成本"
            />
          </label>
          <label className="flex flex-col gap-1 text-sm md:col-span-2">
            <span className="text-muted-foreground">评审结论</span>
            <Input value={decisionNote} onChange={(e) => setDecisionNote(e.target.value)} />
          </label>
          <label className="flex flex-col gap-1 text-sm md:col-span-2">
            <span className="text-muted-foreground">备注</span>
            <Input value={note} onChange={(e) => setNote(e.target.value)} />
          </label>
        </div>
        {error && <p className="text-sm text-red-600">{error}</p>}
        <ModalFooter>
          <Button variant="ghost" onClick={onCancel}>
            取消
          </Button>
          <Button onClick={() => void submit()} disabled={busy}>
            立项
          </Button>
        </ModalFooter>
      </ModalContent>
    </Modal>
  );
}

// ================================================================ 详情

function DetailDialog({
  detail,
  options,
  caps,
  busy,
  onClose,
  onTransition,
  onRefresh,
  onError,
}: {
  detail: DevelopmentProjectDetail;
  options: DevelopmentProjectOptions | null;
  caps: DevelopmentProjectCapabilities | null;
  busy: boolean;
  onClose: () => void;
  onTransition: (
    toStatus: DevelopmentStatus,
    reason: string,
    exemptionCode: string | null,
    exemptionNote: string | null,
  ) => Promise<void>;
  onRefresh: () => Promise<void>;
  onError: (message: string) => void;
}) {
  const project = detail.project;
  const [toStatus, setToStatus] = useState('');
  const [reason, setReason] = useState('');
  const [exemptionCode, setExemptionCode] = useState('');
  const [exemptionNote, setExemptionNote] = useState('');

  const targets = allowedTargetOptions(options, project.status);
  const needsExemption =
    toStatus !== '' && transitionNeedsExemption(project.status, toStatus);

  return (
    <Modal open onOpenChange={(open) => !open && onClose()}>
      <ModalContent className="max-w-4xl">
        <ModalHeader>
          <ModalTitle>
            {project.projectNo} · {project.name}
          </ModalTitle>
          <ModalDescription>
            {project.category} · 负责人 {project.ownerName || '未指定'} · 当前状态{' '}
            {statusLabel(project)}
          </ModalDescription>
        </ModalHeader>
        <div className="flex flex-col gap-3">
          {/* 立项记录 */}
          <SectionCard title="立项记录">
            <div className="grid gap-2 text-sm md:grid-cols-2">
              <p>
                目标售价：{money(project.targetPrice, project.currency)}
              </p>
              <p>
                目标成本：
                {costText(project.targetCost, project.costCurrency, project.costAvailable)}
                <span className="text-muted-foreground">
                  （来源：{costSourceLabel(project.costSource)}）
                </span>
              </p>
              <p className="md:col-span-2">
                目标毛利：<span className="font-medium">{marginText(project.targetMarginRate)}</span>
                {project.marginBasis && (
                  <span className="text-muted-foreground block text-xs">{project.marginBasis}</span>
                )}
              </p>
              {caps && (
                <p className="text-muted-foreground md:col-span-2 text-xs">
                  舍入口径：{caps.marginRoundingNote}
                </p>
              )}
              {project.note && <p className="md:col-span-2">备注：{project.note}</p>}
            </div>
          </SectionCard>

          {/* 状态推进 */}
          <SectionCard title="推进状态">
            <div className="flex flex-col gap-2 text-sm">
              <p className="text-muted-foreground">
                {caps?.stateMachineNote ??
                  '状态只能沿合法迁移推进；终止是终态，不提供恢复。'}
              </p>
              {targets.length === 0 ? (
                <p className="text-muted-foreground">
                  当前状态是终态，没有可迁到的状态（终止不提供恢复）。
                </p>
              ) : (
                <>
                  <div className="flex flex-wrap items-end gap-2">
                    <label className="flex flex-col gap-1">
                      <span className="text-muted-foreground">目标状态</span>
                      <select
                        className="h-9 rounded-md border px-2"
                        value={toStatus}
                        onChange={(e) => setToStatus(e.target.value)}
                      >
                        <option value="">请选择</option>
                        {targets.map((item) => (
                          <option key={item.value} value={item.value}>
                            {item.label}
                          </option>
                        ))}
                      </select>
                    </label>
                    <label className="flex flex-1 flex-col gap-1">
                      <span className="text-muted-foreground">迁移原因 *</span>
                      <Input
                        value={reason}
                        onChange={(e) => setReason(e.target.value)}
                        placeholder="一句「推进一下」不是留痕"
                      />
                    </label>
                  </div>
                  {needsExemption && (
                    <div className="rounded-md border border-amber-500/40 bg-amber-500/10 p-2 text-xs">
                      🔴 这一步跳过了打样 / 试产，**必须**给出显式的豁免记录（豁免码 + 豁免说明），
                      否则服务端与数据库层都会拒绝。
                      <div className="mt-2 flex flex-wrap gap-2">
                        <Input
                          value={exemptionCode}
                          onChange={(e) => setExemptionCode(e.target.value)}
                          placeholder="豁免码，例如 SKIP-SAMPLING"
                          className="w-64"
                        />
                        <Input
                          value={exemptionNote}
                          onChange={(e) => setExemptionNote(e.target.value)}
                          placeholder="豁免说明：为什么可以跳过"
                          className="w-80"
                        />
                      </div>
                    </div>
                  )}
                  <div>
                    <Button
                      disabled={busy || !toStatus || !reason.trim()}
                      onClick={() => {
                        void (async () => {
                          try {
                            await onTransition(
                              toStatus as DevelopmentStatus,
                              reason.trim(),
                              exemptionCode.trim() || null,
                              exemptionNote.trim() || null,
                            );
                            setToStatus('');
                            setReason('');
                            setExemptionCode('');
                            setExemptionNote('');
                          } catch (e) {
                            onError(errorText(e));
                          }
                        })();
                      }}
                    >
                      推进状态
                    </Button>
                  </div>
                </>
              )}
            </div>
          </SectionCard>

          <CostEstimateSection detail={detail} busy={busy} onRefresh={onRefresh} onError={onError} />

          <MilestoneSection detail={detail} busy={busy} onRefresh={onRefresh} onError={onError} />

          <MarketSection detail={detail} busy={busy} onRefresh={onRefresh} onError={onError} />

          <SpecSection detail={detail} busy={busy} onRefresh={onRefresh} onError={onError} />

          <ObservationSection detail={detail} busy={busy} onRefresh={onRefresh} onError={onError} />

          <TransitionSection transitions={detail.transitions} />

          <HandoffSection detail={detail} busy={busy} onRefresh={onRefresh} onError={onError} />

          <SkuCandidateSection detail={detail} />
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

// ================================================================ 目标成本估算

function CostEstimateSection({
  detail,
  busy,
  onRefresh,
  onError,
}: {
  detail: DevelopmentProjectDetail;
  busy: boolean;
  onRefresh: () => Promise<void>;
  onError: (message: string) => void;
}) {
  const [skuId, setSkuId] = useState('');
  const [skuCode, setSkuCode] = useState('');
  const [estimate, setEstimate] = useState<Awaited<
    ReturnType<typeof fetchDevelopmentCostEstimate>
  > | null>(null);

  return (
    <SectionCard title="目标成本（5.3.1.1 利润测算器）">
      <div className="flex flex-col gap-2 text-sm">
        <p className="text-muted-foreground text-xs">
          目标成本的唯一系统来源是 catalog 的 5.3.1.1 利润测算器（跨库视图与跨服务 SQL 均被禁止）。
          取不到时会如实标「取不到」并点名归谁管，**不会**拿 0 顶替。
        </p>
        <div className="flex flex-wrap items-end gap-2">
          <label className="flex flex-col gap-1">
            <span className="text-muted-foreground">参考 SKU id</span>
            <Input value={skuId} onChange={(e) => setSkuId(e.target.value)} className="w-72" />
          </label>
          <label className="flex flex-col gap-1">
            <span className="text-muted-foreground">参考 SKU 编码</span>
            <Input value={skuCode} onChange={(e) => setSkuCode(e.target.value)} className="w-48" />
          </label>
          <Button
            variant="ghost"
            disabled={busy}
            onClick={() => {
              void (async () => {
                try {
                  setEstimate(
                    await fetchDevelopmentCostEstimate(
                      detail.project.id,
                      skuId.trim() || undefined,
                    ),
                  );
                } catch (e) {
                  onError(errorText(e));
                }
              })();
            }}
          >
            预览估算
          </Button>
          <Button
            disabled={busy}
            onClick={() => {
              void (async () => {
                try {
                  await applyDevelopmentCostEstimate(
                    detail.project.id,
                    skuId.trim() || undefined,
                    skuCode.trim() || undefined,
                  );
                  await onRefresh();
                } catch (e) {
                  onError(errorText(e));
                }
              })();
            }}
          >
            应用到立项
          </Button>
        </div>
        {estimate && (
          <p className="text-xs">
            {estimate.available ? (
              <>
                单位成本：{money(estimate.unitCost, estimate.costCurrency)}
                {estimate.basis ? ` · ${estimate.basis}` : ''}
              </>
            ) : (
              <>
                🔴 取不到：{estimate.unavailableReason}
                {estimate.ownerModule ? `（归 ${estimate.ownerModule} 管）` : ''}
              </>
            )}
          </p>
        )}
      </div>
    </SectionCard>
  );
}

// ================================================================ 里程碑

function MilestoneSection({
  detail,
  busy,
  onRefresh,
  onError,
}: {
  detail: DevelopmentProjectDetail;
  busy: boolean;
  onRefresh: () => Promise<void>;
  onError: (message: string) => void;
}) {
  const [type, setType] = useState<DevelopmentMilestoneType>('SAMPLING');
  const [plannedAt, setPlannedAt] = useState('');
  const [note, setNote] = useState('');

  return (
    <SectionCard title="里程碑计划（打样 / 试产 / 量产 / 上市）">
      <div className="flex flex-col gap-2 text-sm">
        {detail.milestones.length === 0 ? (
          <p className="text-muted-foreground">还没有里程碑计划（未排期是真实状态，不是查不到）。</p>
        ) : (
          <table className="w-full text-sm">
            <thead className="text-muted-foreground text-left">
              <tr>
                <th className="px-2 py-1">类型</th>
                <th className="px-2 py-1">计划时间</th>
                <th className="px-2 py-1">实际时间</th>
                <th className="px-2 py-1">状态</th>
                <th className="px-2 py-1">备注</th>
                <th className="px-2 py-1" />
              </tr>
            </thead>
            <tbody>
              {detail.milestones.map((milestone) => (
                <tr key={milestone.id} className="border-t">
                  <td className="px-2 py-1">{milestone.milestoneTypeLabel}</td>
                  <td className="px-2 py-1">{timeText(milestone.plannedAt)}</td>
                  <td className="px-2 py-1">{timeText(milestone.actualAt)}</td>
                  <td className="px-2 py-1">
                    <Badge tone="neutral">{milestoneStatusLabel(milestone.status)}</Badge>
                  </td>
                  <td className="text-muted-foreground px-2 py-1">{milestone.note}</td>
                  <td className="px-2 py-1 text-right">
                    <Button
                      variant="ghost"
                      disabled={busy}
                      onClick={() => {
                        void (async () => {
                          try {
                            await deleteDevelopmentMilestone(
                              detail.project.id,
                              milestone.id,
                            );
                            await onRefresh();
                          } catch (e) {
                            onError(errorText(e));
                          }
                        })();
                      }}
                    >
                      删除
                    </Button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
        <div className="flex flex-wrap items-end gap-2">
          <label className="flex flex-col gap-1">
            <span className="text-muted-foreground">类型</span>
            <select
              className="h-9 rounded-md border px-2"
              value={type}
              onChange={(e) => setType(e.target.value as DevelopmentMilestoneType)}
            >
              {MILESTONE_TYPES.map((item) => (
                <option key={item.value} value={item.value}>
                  {item.label}
                </option>
              ))}
            </select>
          </label>
          <label className="flex flex-col gap-1">
            <span className="text-muted-foreground">计划时间 *</span>
            <Input
              type="datetime-local"
              value={plannedAt}
              onChange={(e) => setPlannedAt(e.target.value)}
            />
          </label>
          <label className="flex flex-col gap-1">
            <span className="text-muted-foreground">备注</span>
            <Input value={note} onChange={(e) => setNote(e.target.value)} />
          </label>
          <Button
            disabled={busy || !plannedAt}
            onClick={() => {
              void (async () => {
                try {
                  await createDevelopmentMilestone(detail.project.id, {
                    milestoneType: type,
                    plannedAt: new Date(plannedAt).toISOString(),
                    actualAt: null,
                    status: 'PLANNED',
                    note: note.trim() || null,
                  });
                  setPlannedAt('');
                  setNote('');
                  await onRefresh();
                } catch (e) {
                  onError(errorText(e));
                }
              })();
            }}
          >
            登记里程碑
          </Button>
        </div>
      </div>
    </SectionCard>
  );
}

// ================================================================ 目标市场

function MarketSection({
  detail,
  busy,
  onRefresh,
  onError,
}: {
  detail: DevelopmentProjectDetail;
  busy: boolean;
  onRefresh: () => Promise<void>;
  onError: (message: string) => void;
}) {
  const [marketCode, setMarketCode] = useState('');
  const [platformCode, setPlatformCode] = useState('');
  const [targetPrice, setTargetPrice] = useState('');
  const [currency, setCurrency] = useState('');

  return (
    <SectionCard title="目标市场与平台">
      <div className="flex flex-col gap-2 text-sm">
        {detail.markets.length === 0 ? (
          <p className="text-muted-foreground">
            还没有登记目标市场与平台（同一款产品在不同市场不是一个价）。
          </p>
        ) : (
          <table className="w-full text-sm">
            <thead className="text-muted-foreground text-left">
              <tr>
                <th className="px-2 py-1">市场</th>
                <th className="px-2 py-1">平台</th>
                <th className="px-2 py-1">目标售价</th>
                <th className="px-2 py-1">优先级</th>
                <th className="px-2 py-1" />
              </tr>
            </thead>
            <tbody>
              {detail.markets.map((market) => (
                <tr key={market.id} className="border-t">
                  <td className="px-2 py-1">{market.marketCode}</td>
                  <td className="px-2 py-1">{market.platformCode}</td>
                  <td className="px-2 py-1">{money(market.targetPrice, market.currency)}</td>
                  <td className="px-2 py-1">{market.priority}</td>
                  <td className="px-2 py-1 text-right">
                    <Button
                      variant="ghost"
                      disabled={busy}
                      onClick={() => {
                        void (async () => {
                          try {
                            await deleteDevelopmentMarket(detail.project.id, market.id);
                            await onRefresh();
                          } catch (e) {
                            onError(errorText(e));
                          }
                        })();
                      }}
                    >
                      删除
                    </Button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
        <div className="flex flex-wrap items-end gap-2">
          <label className="flex flex-col gap-1">
            <span className="text-muted-foreground">市场 *</span>
            <Input
              value={marketCode}
              onChange={(e) => setMarketCode(e.target.value)}
              placeholder="US"
              className="w-32"
            />
          </label>
          <label className="flex flex-col gap-1">
            <span className="text-muted-foreground">平台 *</span>
            <Input
              value={platformCode}
              onChange={(e) => setPlatformCode(e.target.value)}
              placeholder="AMAZON"
              className="w-40"
            />
          </label>
          <label className="flex flex-col gap-1">
            <span className="text-muted-foreground">目标售价 *</span>
            <Input
              value={targetPrice}
              onChange={(e) => setTargetPrice(e.target.value)}
              className="w-32"
            />
          </label>
          <label className="flex flex-col gap-1">
            <span className="text-muted-foreground">币种 *</span>
            <Input
              value={currency}
              onChange={(e) => setCurrency(e.target.value)}
              placeholder="USD"
              className="w-28"
            />
          </label>
          <Button
            disabled={busy || !marketCode.trim() || !platformCode.trim() || !targetPrice}
            onClick={() => {
              void (async () => {
                try {
                  await createDevelopmentMarket(detail.project.id, {
                    marketCode: marketCode.trim(),
                    platformCode: platformCode.trim(),
                    targetPrice: Number(targetPrice),
                    currency: currency.trim().toUpperCase(),
                    priority: 1,
                    sortRank: 0,
                  });
                  setMarketCode('');
                  setPlatformCode('');
                  setTargetPrice('');
                  await onRefresh();
                } catch (e) {
                  onError(errorText(e));
                }
              })();
            }}
          >
            登记目标市场
          </Button>
        </div>
      </div>
    </SectionCard>
  );
}

// ================================================================ 规格候选

function SpecSection({
  detail,
  busy,
  onRefresh,
  onError,
}: {
  detail: DevelopmentProjectDetail;
  busy: boolean;
  onRefresh: () => Promise<void>;
  onError: (message: string) => void;
}) {
  const [specName, setSpecName] = useState('');
  const [specValue, setSpecValue] = useState('');

  return (
    <SectionCard title="规格候选（可转 SKU 建档）">
      <div className="flex flex-col gap-2 text-sm">
        {detail.specCandidates.length === 0 ? (
          <p className="text-muted-foreground">还没有规格候选。</p>
        ) : (
          <ul className="flex flex-wrap gap-2">
            {detail.specCandidates.map((spec) => (
              <li key={spec.id} className="flex items-center gap-2 rounded-md border px-2 py-1 text-xs">
                <span>
                  {spec.specName}：{spec.specValue}
                </span>
                <Button
                  variant="ghost"
                  disabled={busy}
                  onClick={() => {
                    void (async () => {
                      try {
                        await deleteDevelopmentSpecCandidate(detail.project.id, spec.id);
                        await onRefresh();
                      } catch (e) {
                        onError(errorText(e));
                      }
                    })();
                  }}
                >
                  删除
                </Button>
              </li>
            ))}
          </ul>
        )}
        <div className="flex flex-wrap items-end gap-2">
          <label className="flex flex-col gap-1">
            <span className="text-muted-foreground">规格名 *</span>
            <Input
              value={specName}
              onChange={(e) => setSpecName(e.target.value)}
              placeholder="颜色"
              className="w-40"
            />
          </label>
          <label className="flex flex-col gap-1">
            <span className="text-muted-foreground">规格值 *</span>
            <Input
              value={specValue}
              onChange={(e) => setSpecValue(e.target.value)}
              placeholder="蓝"
              className="w-40"
            />
          </label>
          <Button
            disabled={busy || !specName.trim() || !specValue.trim()}
            onClick={() => {
              void (async () => {
                try {
                  await createDevelopmentSpecCandidate(detail.project.id, {
                    specName: specName.trim(),
                    specValue: specValue.trim(),
                    sortRank: 0,
                  });
                  setSpecName('');
                  setSpecValue('');
                  await onRefresh();
                } catch (e) {
                  onError(errorText(e));
                }
              })();
            }}
          >
            登记规格候选
          </Button>
        </div>
      </div>
    </SectionCard>
  );
}

// ================================================================ 市场观察

function ObservationSection({
  detail,
  busy,
  onRefresh,
  onError,
}: {
  detail: DevelopmentProjectDetail;
  busy: boolean;
  onRefresh: () => Promise<void>;
  onError: (message: string) => void;
}) {
  const [marketCode, setMarketCode] = useState('');
  const [metricCode, setMetricCode] = useState('CATEGORY_SIZE');
  const [metricValue, setMetricValue] = useState('');
  const [valueUnit, setValueUnit] = useState('');
  const [source, setSource] = useState('');
  const [collectedAt, setCollectedAt] = useState('');

  const observations: DevelopmentMarketObservationRow[] = detail.marketObservations;

  return (
    <SectionCard title="市场数据（手工录入）">
      <div className="flex flex-col gap-2 text-sm">
        <p className="text-muted-foreground text-xs">
          🔴 系统侧市场数据未交付（14 广告的 reporting 服务不在本仓）。这里只记**人工采集**的观察值，
          来源与采集时间必填 —— 没有来源的市场数据比没有市场数据更危险。
        </p>
        {observations.length === 0 ? (
          <p className="text-muted-foreground">还没有录入市场观察。</p>
        ) : (
          <table className="w-full text-sm">
            <thead className="text-muted-foreground text-left">
              <tr>
                <th className="px-2 py-1">市场</th>
                <th className="px-2 py-1">指标</th>
                <th className="px-2 py-1">数值</th>
                <th className="px-2 py-1">来源</th>
                <th className="px-2 py-1">采集时间</th>
              </tr>
            </thead>
            <tbody>
              {observations.map((observation) => (
                <tr key={observation.id} className="border-t">
                  <td className="px-2 py-1">{observation.marketCode}</td>
                  <td className="px-2 py-1">{observation.metricCode}</td>
                  <td className="px-2 py-1">
                    {observation.metricValue === null
                      ? '无数据'
                      : `${observation.metricValue}${observation.valueUnit ? ` ${observation.valueUnit}` : ''}`}
                  </td>
                  <td className="px-2 py-1">{observation.source}</td>
                  <td className="px-2 py-1">{timeText(observation.collectedAt)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
        <div className="flex flex-wrap items-end gap-2">
          <label className="flex flex-col gap-1">
            <span className="text-muted-foreground">市场 *</span>
            <Input
              value={marketCode}
              onChange={(e) => setMarketCode(e.target.value)}
              className="w-28"
            />
          </label>
          <label className="flex flex-col gap-1">
            <span className="text-muted-foreground">指标 *</span>
            <select
              className="h-9 rounded-md border px-2"
              value={metricCode}
              onChange={(e) => setMetricCode(e.target.value)}
            >
              <option value="CATEGORY_SIZE">类目规模</option>
              <option value="COMPETITOR_PRICE">竞品价位</option>
              <option value="SEARCH_VOLUME">搜索热度</option>
              <option value="OTHER">其它</option>
            </select>
          </label>
          <label className="flex flex-col gap-1">
            <span className="text-muted-foreground">数值</span>
            <Input
              value={metricValue}
              onChange={(e) => setMetricValue(e.target.value)}
              className="w-32"
            />
          </label>
          <label className="flex flex-col gap-1">
            <span className="text-muted-foreground">单位</span>
            <Input
              value={valueUnit}
              onChange={(e) => setValueUnit(e.target.value)}
              className="w-24"
            />
          </label>
          <label className="flex flex-col gap-1">
            <span className="text-muted-foreground">来源 *</span>
            <Input
              value={source}
              onChange={(e) => setSource(e.target.value)}
              placeholder="例如：亚马逊美国站类目页 2026-10"
              className="w-72"
            />
          </label>
          <label className="flex flex-col gap-1">
            <span className="text-muted-foreground">采集时间 *</span>
            <Input
              type="datetime-local"
              value={collectedAt}
              onChange={(e) => setCollectedAt(e.target.value)}
            />
          </label>
          <Button
            disabled={busy || !marketCode.trim() || !source.trim() || !collectedAt}
            onClick={() => {
              void (async () => {
                try {
                  await createDevelopmentMarketObservation(detail.project.id, {
                    marketCode: marketCode.trim(),
                    platformCode: null,
                    metricCode: metricCode.trim(),
                    metricValue: metricValue.trim() === '' ? null : Number(metricValue),
                    valueUnit: valueUnit.trim() || null,
                    source: source.trim(),
                    collectedAt: new Date(collectedAt).toISOString(),
                    note: null,
                  });
                  setMetricValue('');
                  setValueUnit('');
                  setSource('');
                  setCollectedAt('');
                  await onRefresh();
                } catch (e) {
                  onError(errorText(e));
                }
              })();
            }}
          >
            录入市场观察
          </Button>
        </div>
      </div>
    </SectionCard>
  );
}

// ================================================================ 迁移历史

function TransitionSection({ transitions }: { transitions: DevelopmentTransitionRow[] }) {
  return (
    <SectionCard title="状态迁移历史（不可改写）">
      {transitions.length === 0 ? (
        <p className="text-muted-foreground text-sm">还没有状态迁移记录。</p>
      ) : (
        <ul className="flex flex-col gap-2 text-sm">
          {transitions.map((event) => (
            <li key={event.id} className="rounded-md border p-2">
              <p>
                <Badge tone="neutral">{event.fromStatusLabel}</Badge> →{' '}
                <Badge tone={statusTone(event.toStatus)}>{event.toStatusLabel}</Badge>
              </p>
              <p className="mt-1">{event.reason}</p>
              {event.exemptionCode && (
                <p className="text-amber-600 text-xs">
                  豁免：{event.exemptionCode} · {event.exemptionNote}
                </p>
              )}
              <p className="text-muted-foreground text-xs">
                {timeText(event.occurredAt)} · 操作人 {event.actorId}
              </p>
            </li>
          ))}
        </ul>
      )}
    </SectionCard>
  );
}

// ================================================================ 交接单

function HandoffSection({
  detail,
  busy,
  onRefresh,
  onError,
}: {
  detail: DevelopmentProjectDetail;
  busy: boolean;
  onRefresh: () => Promise<void>;
  onError: (message: string) => void;
}) {
  return (
    <SectionCard title="下游交接单">
      <div className="flex flex-col gap-2 text-sm">
        <p className="text-muted-foreground text-xs">
          6.6.1.2 打样 / 6.6.2.1 试产**未交付**，3.1.1.1 建档归 catalog ⇒ 本模块**只产交接单**，
          不代下游建单。交接单里带的是候选参数。
        </p>
        {detail.handoffs.length === 0 ? (
          <p className="text-muted-foreground">还没有交接单。</p>
        ) : (
          <ul className="flex flex-col gap-2">
            {detail.handoffs.map((handoff) => (
              <li key={handoff.id} className="rounded-md border p-2">
                <p>
                  <Badge tone="neutral">{handoff.handoffTypeLabel}</Badge>{' '}
                  <span className="text-muted-foreground">
                    {handoffStatusText(handoff.status, handoff.targetModule)}
                  </span>
                </p>
                {handoff.note && <p className="text-muted-foreground text-xs">{handoff.note}</p>}
              </li>
            ))}
          </ul>
        )}
        <div className="flex flex-wrap gap-2">
          {HANDOFF_TYPES.map((item) => (
            <Button
              key={item.value}
              variant="ghost"
              disabled={busy}
              onClick={() => {
                void (async () => {
                  try {
                    await createDevelopmentHandoff(detail.project.id, {
                      handoffType: item.value,
                      note: null,
                    });
                    await onRefresh();
                  } catch (e) {
                    onError(errorText(e));
                  }
                })();
              }}
            >
              产出{item.label}交接单
            </Button>
          ))}
        </div>
      </div>
    </SectionCard>
  );
}

// ================================================================ SKU 候选

function SkuCandidateSection({ detail }: { detail: DevelopmentProjectDetail }) {
  const [candidate, setCandidate] = useState<Awaited<
    ReturnType<typeof fetchDevelopmentSkuCandidate>
  > | null>(null);

  useEffect(() => {
    void (async () => {
      try {
        setCandidate(await fetchDevelopmentSkuCandidate(detail.project.id));
      } catch {
        setCandidate(null);
      }
    })();
  }, [detail.project.id]);

  return (
    <SectionCard title="可转 SKU 建档的候选参数">
      {!candidate ? (
        <p className="text-muted-foreground text-sm">候选参数读不到。</p>
      ) : (
        <div className="flex flex-col gap-2 text-sm">
          <p className="text-muted-foreground text-xs">{candidate.note}</p>
          <div className="grid gap-2 md:grid-cols-2">
            <p>品类：{candidate.category}</p>
            <p>
              目标售价区间：
              {candidate.targetPriceMin === null || candidate.targetPriceMax === null
                ? '无数据'
                : `${money(candidate.targetPriceMin, candidate.currency)} ~ ${money(
                    candidate.targetPriceMax,
                    candidate.currency,
                  )}`}
            </p>
            <p>上市时间目标：{timeText(candidate.launchTargetAt)}</p>
            <p>打样计划：{timeText(candidate.samplingPlannedAt)}</p>
            <p>量产计划：{timeText(candidate.massProductionPlannedAt)}</p>
            <p>
              交接目标：{candidate.targetModule}
              {candidate.handoffReady ? '（可交接）' : '（尚不可交接）'}
            </p>
          </div>
        </div>
      )}
    </SectionCard>
  );
}
