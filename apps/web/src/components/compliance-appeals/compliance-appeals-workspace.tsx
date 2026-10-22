'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
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
import {
  CircleAlert,
  Gavel,
  Loader2,
  RefreshCw,
  Scale,
  ScrollText,
  ShieldCheck,
} from 'lucide-react';
import {
  APPEAL_TYPE_OPTIONS,
  EVIDENCE_TYPE_OPTIONS,
  STATUS_OPTIONS,
  addEvidence,
  appealTypeLabel,
  createAppeal,
  decideAppeal,
  emptyState,
  emptyStateKind,
  evidenceTypeLabel,
  fetchAppeals,
  fetchAppeal,
  fetchCapabilities,
  fetchPriorityQueue,
  fetchQuotas,
  fetchShops,
  fetchStats,
  impactLabel,
  impactTone,
  outcomeLabel,
  quotaText,
  remainingText,
  reopenAppeal,
  setQuota,
  statusLabel,
  statusTone,
  submitAppeal,
  updateAppealText,
  withdrawAppeal,
  type ComplianceAppeal,
  type ComplianceAppealCapabilities,
  type ComplianceAppealDetail,
  type ComplianceAppealPage,
  type ComplianceAppealPriorityPage,
  type ComplianceAppealQuotaPage,
  type ComplianceAppealStats,
} from './compliance-appeals-model';

/**
 * 16.1.2.2 申诉管理 工作区。
 *
 * 蓝图：「管理平台申诉：申诉类型、举证材料收集、申诉文本、提交跟踪、结果记录与申诉次数统计。
 * 输入：违规记录；申诉材料；提交动作。输出：申诉单 + 提交记录 + 结果（通过/驳回）。
 * 关系：上游 = 16.1.2.1；下游 = 平台申诉接口。**申诉配额有限**，需记录已用次数并优先申诉高影响违规。」
 *
 * 🔴 页面上的六条纪律：
 *
 * 1. **配额永远摆在明面上** —— 上限 / 已用 / 剩几次三样都显示；「没配配额」显示成
 *    「未配置」，**不显示成 0**（没配 ≠ 无限）。
 * 2. **优先申诉高影响违规** —— 队列按影响档位从高到低排，运营照着队列提申诉。
 * 3. **影响档位是派生的** —— 页面只显示后端给的档位，自己不重算严重度。
 * 4. **举证材料 / 提交记录 / 结果记录三样只追加** —— 界面上没有「编辑 / 删除」。
 * 5. **状态机在服务端** —— 页面只显示后端给的合法下一步，点了非法的一律 422。
 * 6. **未交付的下游灰掉** —— 平台申诉接口本仓不存在，页面上如实标出，
 *    **不编**「已提交到平台」。
 */
const PAGE_SIZE = 20;

interface Props {
  /** 从别处跳进来时的定位（真实存在的页面参数）。 */
  focus?: { shopId?: string };
}

export function ComplianceAppealsWorkspace({ focus }: Props) {
  const [caps, setCaps] = useState<ComplianceAppealCapabilities | null>(null);
  const [appeals, setAppeals] = useState<ComplianceAppealPage | null>(null);
  const [queue, setQueue] = useState<ComplianceAppealPriorityPage | null>(null);
  const [quotas, setQuotas] = useState<ComplianceAppealQuotaPage | null>(null);
  const [stats, setStats] = useState<ComplianceAppealStats | null>(null);

  const [keyword, setKeyword] = useState('');
  const [status, setStatus] = useState('');
  const [appealType, setAppealType] = useState('');
  const [shopId, setShopId] = useState(focus?.shopId ?? '');
  const [page, setPage] = useState(0);

  const [detail, setDetail] = useState<ComplianceAppealDetail | null>(null);
  const [newAppeal, setNewAppeal] = useState<{ violationId: string; title: string } | null>(null);
  const [newType, setNewType] = useState('POLICY_MISINTERPRETATION');
  const [newText, setNewText] = useState('');

  const [evidenceType, setEvidenceType] = useState('DOCUMENT');
  const [evidenceRef, setEvidenceRef] = useState('');
  const [evidenceSummary, setEvidenceSummary] = useState('');

  const [decisionOutcome, setDecisionOutcome] = useState('APPROVED');
  const [decisionReason, setDecisionReason] = useState('');
  const [platformReference, setPlatformReference] = useState('');

  const [quotaShopId, setQuotaShopId] = useState('');
  const [quotaLimit, setQuotaLimit] = useState('5');

  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const loadAppeals = useCallback(
    async (target: number) => {
      const result = await fetchAppeals(target, PAGE_SIZE, {
        keyword,
        status,
        appealType,
        shopId,
        sort: 'priority',
      });
      setAppeals(result);
      return result;
    },
    [keyword, status, appealType, shopId],
  );

  const refresh = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const [capsResult, appealResult, queueResult, quotaResult, statResult] = await Promise.all([
        fetchCapabilities(),
        loadAppeals(page),
        fetchPriorityQueue(0, PAGE_SIZE, { shopId }),
        fetchQuotas(0, PAGE_SIZE, { shopId }),
        fetchStats({ shopId }),
      ]);
      setCaps(capsResult);
      setAppeals(appealResult);
      setQueue(queueResult);
      setQuotas(quotaResult);
      setStats(statResult);
    } catch (e) {
      setError(e instanceof Error ? e.message : '加载失败，请重试');
    } finally {
      setLoading(false);
    }
  }, [loadAppeals, page, shopId]);

  useEffect(() => {
    void refresh();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const run = useCallback(async (work: () => Promise<void>) => {
    setBusy(true);
    setError(null);
    try {
      await work();
    } catch (e) {
      setError(e instanceof Error ? e.message : '操作失败，请重试');
    } finally {
      setBusy(false);
    }
  }, []);

  const openDetail = useCallback(
    (appealId: string) =>
      run(async () => {
        setDetail(await fetchAppeal(appealId));
      }),
    [run],
  );

  const reloadDetail = useCallback(async (appealId: string) => {
    const result = await fetchAppeal(appealId);
    setDetail(result);
  }, []);

  const doSubmit = useCallback(
    (appealId: string) =>
      run(async () => {
        const result = await submitAppeal(appealId, { platformReference, note: '' });
        await reloadDetail(appealId);
        await refresh();
        void result;
      }),
    [platformReference, reloadDetail, refresh, run],
  );

  const doDecide = useCallback(
    (appealId: string) =>
      run(async () => {
        await decideAppeal(appealId, {
          outcome: decisionOutcome,
          reason: decisionReason,
          platformReference,
        });
        setDecisionReason('');
        await reloadDetail(appealId);
        await refresh();
      }),
    [decisionOutcome, decisionReason, platformReference, reloadDetail, refresh, run],
  );

  const doWithdraw = useCallback(
    (appealId: string) =>
      run(async () => {
        await withdrawAppeal(appealId);
        await reloadDetail(appealId);
        await refresh();
      }),
    [reloadDetail, refresh, run],
  );

  const doReopen = useCallback(
    (appealId: string) =>
      run(async () => {
        await reopenAppeal(appealId);
        await reloadDetail(appealId);
        await refresh();
      }),
    [reloadDetail, refresh, run],
  );

  const doAddEvidence = useCallback(
    (appealId: string) =>
      run(async () => {
        await addEvidence(appealId, {
          evidenceType,
          evidenceRef,
          summary: evidenceSummary,
        });
        setEvidenceRef('');
        setEvidenceSummary('');
        await reloadDetail(appealId);
      }),
    [evidenceRef, evidenceSummary, evidenceType, reloadDetail, run],
  );

  const doUpdateText = useCallback(
    (appealId: string) =>
      run(async () => {
        await updateAppealText(appealId, newText);
        await reloadDetail(appealId);
      }),
    [newText, reloadDetail, run],
  );

  const doCreate = useCallback(
    () =>
      run(async () => {
        if (!newAppeal) return;
        const created = await createAppeal({
          violationId: newAppeal.violationId,
          appealType: newType,
          appealText: newText,
        });
        setNewAppeal(null);
        setNewText('');
        await refresh();
        await openDetail(created.appealId);
      }),
    [newAppeal, newText, newType, openDetail, refresh, run],
  );

  const doSetQuota = useCallback(
    () =>
      run(async () => {
        if (!quotaShopId) return;
        await setQuota({ shopId: quotaShopId, quotaLimit: Number(quotaLimit) });
        await refresh();
      }),
    [quotaLimit, quotaShopId, refresh, run],
  );

  const empty = useMemo(() => {
    const filtered = Boolean(keyword || status || appealType || shopId);
    const kind = emptyStateKind(filtered, appeals?.total ?? 0);
    return { kind, title: emptyState(kind), description: filtered ? '换个筛选条件试试。' : '' };
  }, [appealType, appeals?.total, keyword, shopId, status]);

  const platformApi = caps?.gaps.find((g) => g.moduleId === 'PLATFORM_APPEAL_API');
  const totalQuota = (quotas?.items ?? []).reduce(
    (sum, q) => sum + (q.configured ? q.remaining : 0),
    0,
  );

  return (
    <div className="space-y-4">
      <PageHeader
        title="申诉管理"
        description="16.1.2.2 · 申诉类型 / 举证材料 / 申诉文本 / 提交跟踪 / 结果记录 / 申诉次数统计"
        actions={
          <Button variant="secondary" size="sm" disabled={busy} onClick={() => void refresh()}>
            {loading ? (
              <Loader2 className="mr-2 size-4 animate-spin" />
            ) : (
              <RefreshCw className="mr-2 size-4" />
            )}
            刷新
          </Button>
        }
      />

      {error && (
        <div className="border-destructive/40 bg-destructive-soft text-destructive flex items-center gap-2 rounded-md border px-3 py-2">
          <CircleAlert className="size-4 shrink-0" />
          <span className="text-copy-13">{error}</span>
        </div>
      )}

      {/* 🔴 平台申诉接口未交付 ⇒ 灰掉并说明，不编「已提交到平台」。 */}
      {platformApi && !platformApi.available && (
        <div className="border-border bg-muted/40 text-muted-foreground rounded-md border px-3 py-2">
          <div className="text-copy-13 text-foreground font-medium">
            下游「{platformApi.moduleName}」尚未交付（归属：{platformApi.ownerModule}）
          </div>
          <div className="text-copy-12 mt-1">{platformApi.impact}</div>
        </div>
      )}

      <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
        <StatCard
          label="申诉单"
          value={stats?.totalAppeals ?? 0}
          unit="单"
          icon={<Scale className="size-4" />}
        />
        {/* 🔴 申诉次数 = 提交总次数（驳回后再次提交会再计一次）。 */}
        <StatCard
          label="申诉次数（提交总次数）"
          value={stats?.totalSubmissions ?? 0}
          unit="次"
          icon={<Gavel className="size-4" />}
        />
        <StatCard
          label="裁定通过"
          value={stats?.approvedCount ?? 0}
          unit="次"
          icon={<ShieldCheck className="size-4" />}
        />
        <StatCard
          label="裁定驳回"
          value={stats?.rejectedCount ?? 0}
          unit="次"
          icon={<CircleAlert className="size-4" />}
        />
      </div>

      {/* ---- 优先申诉队列（蓝图加粗句） ---- */}
      <SectionCard
        title="优先申诉队列"
        description="🔴 按**影响档位**从高到低排（派生自上游违规的处罚措施集合，不落库）。队列里不含已有在办申诉、也不含已关闭的违规。"
      >
        {(queue?.items ?? []).length === 0 ? (
          <EmptyState
            icon={<Scale className="size-8" />}
            title="队列里没有待申诉的违规"
            description="所有违规都已在办申诉、已关闭，或当前租户下没有违规记录。"
          />
        ) : (
          <div className="space-y-2">
            {(queue?.items ?? []).map((item) => (
              <div
                key={item.violationId}
                className="border-border flex flex-wrap items-center gap-2 rounded-md border px-3 py-2"
              >
                <Badge tone={impactTone(item.impactLevel)}>{impactLabel(item.impactLevel)}</Badge>
                <Badge tone="info">{item.violationTypeLabel}</Badge>
                <span className="text-copy-13 text-foreground font-medium">{item.title}</span>
                <span className="text-copy-12 text-muted-foreground">
                  {item.shopCode} · 发生于 {item.occurredAt}
                </span>
                <span className="text-copy-12 text-muted-foreground">
                  {remainingText(item.quotaConfigured, item.quotaRemaining)}
                </span>
                <span className="text-copy-12 text-muted-foreground ml-auto">{item.reason}</span>
                {item.appealable && (
                  <Button
                    size="sm"
                    onClick={() => {
                      setNewAppeal({ violationId: item.violationId, title: item.title });
                      setNewType('POLICY_MISINTERPRETATION');
                      setNewText('');
                    }}
                  >
                    发起申诉
                  </Button>
                )}
              </div>
            ))}
          </div>
        )}
      </SectionCard>

      {/* ---- 申诉单清单 ---- */}
      <SectionCard
        title="申诉单"
        description="🔴 默认按影响档位优先排序。各状态计数与提交次数覆盖整个筛选结果集（不是当前页）。"
      >
        <div className="mb-4 flex flex-wrap items-center gap-2">
          <Input
            className="w-56"
            placeholder="申诉文本 / 违规标题"
            value={keyword}
            onChange={(e) => {
              setKeyword(e.target.value);
              setPage(0);
            }}
          />
          <select
            className="border-input bg-background text-copy-13 h-9 rounded-md border px-2"
            value={status}
            onChange={(e) => {
              setStatus(e.target.value);
              setPage(0);
            }}
          >
            {STATUS_OPTIONS.map((option) => (
              <option key={option.value} value={option.value}>
                {option.label}
              </option>
            ))}
          </select>
          <select
            className="border-input bg-background text-copy-13 h-9 rounded-md border px-2"
            value={appealType}
            onChange={(e) => {
              setAppealType(e.target.value);
              setPage(0);
            }}
          >
            {APPEAL_TYPE_OPTIONS.map((option) => (
              <option key={option.value} value={option.value}>
                {option.label}
              </option>
            ))}
          </select>
          {shopId && (
            <Button
              variant="ghost"
              size="sm"
              onClick={() => {
                setShopId('');
                setPage(0);
              }}
            >
              清除店铺筛选
            </Button>
          )}
        </div>

        {loading && appeals === null ? (
          <div className="text-copy-13 text-muted-foreground flex items-center gap-2 py-8">
            <Loader2 className="size-4 animate-spin" />
            加载中…
          </div>
        ) : (appeals?.items ?? []).length === 0 ? (
          <EmptyState
            icon={<Scale className="size-8" />}
            title={empty.title}
            description={empty.description}
          />
        ) : (
          <div className="space-y-2">
            {(appeals?.items ?? []).map((item: ComplianceAppeal) => (
              <button
                key={item.appealId}
                type="button"
                onClick={() => void openDetail(item.appealId)}
                className="border-border hover:bg-muted/40 w-full rounded-md border px-3 py-2 text-left"
              >
                <div className="flex flex-wrap items-center gap-2">
                  <Badge tone={impactTone(item.impactLevel)}>{impactLabel(item.impactLevel)}</Badge>
                  <Badge tone={statusTone(item.status)}>{statusLabel(item.status)}</Badge>
                  <Badge tone="info">{appealTypeLabel(item.appealType)}</Badge>
                  <span className="text-copy-13 text-foreground font-medium">
                    {item.violationTitle}
                  </span>
                </div>
                <div className="text-copy-12 text-muted-foreground mt-1">
                  {item.shopCode} · 提交 {item.submissionCount} 次 · 举证材料 {item.evidenceCount}{' '}
                  条 · 结果记录 {item.decisionCount} 条
                  {item.lastOutcome ? ` · 最近结果 ${outcomeLabel(item.lastOutcome)}` : ''}
                </div>
              </button>
            ))}
          </div>
        )}

        {appeals && appeals.total > PAGE_SIZE && (
          <div className="mt-4 flex items-center justify-between">
            <span className="text-copy-12 text-muted-foreground">
              第 {appeals.page + 1} / {Math.ceil(appeals.total / PAGE_SIZE)} 页，共 {appeals.total}{' '}
              条
            </span>
            <div className="flex gap-2">
              <Button
                variant="secondary"
                size="sm"
                disabled={appeals.page <= 0}
                onClick={() => {
                  const target = Math.max(page - 1, 0);
                  setPage(target);
                  void loadAppeals(target);
                }}
              >
                上一页
              </Button>
              <Button
                variant="secondary"
                size="sm"
                disabled={(appeals.page + 1) * PAGE_SIZE >= appeals.total}
                onClick={() => {
                  const target = page + 1;
                  setPage(target);
                  void loadAppeals(target);
                }}
              >
                下一页
              </Button>
            </div>
          </div>
        )}
      </SectionCard>

      {/* ---- 申诉配额 ---- */}
      <SectionCard
        title="申诉配额"
        description="🔴 申诉配额有限：每一次提交消耗一次配额，驳回后再次提交会再消耗一次。没配配额的店铺提交会被拒（没配 ≠ 无限）。"
      >
        <div className="mb-4 flex flex-wrap items-end gap-2">
          <Input
            className="w-64"
            placeholder="店铺 id（从店铺范围清单里取）"
            value={quotaShopId}
            onChange={(e) => setQuotaShopId(e.target.value)}
          />
          <Input
            className="w-32"
            placeholder="本周期上限"
            value={quotaLimit}
            onChange={(e) => setQuotaLimit(e.target.value)}
          />
          <Button size="sm" disabled={busy || !quotaShopId} onClick={() => void doSetQuota()}>
            配置配额
          </Button>
          <span className="text-copy-12 text-muted-foreground">
            当前筛选范围剩余配额合计 {totalQuota} 次
          </span>
        </div>

        {(quotas?.items ?? []).length === 0 ? (
          <EmptyState
            icon={<Gavel className="size-8" />}
            title="还没有店铺"
            description="渠道与店铺同步之后这里才会有配额清单。"
          />
        ) : (
          <div className="space-y-2">
            {(quotas?.items ?? []).map((q) => (
              <div key={q.shopId} className="border-border rounded-md border px-3 py-2">
                <div className="flex flex-wrap items-center gap-2">
                  <span className="text-copy-13 text-foreground font-medium">
                    {q.shopCode} · {q.shopName}
                  </span>
                  {/* 🔴 没配配额显示成「未配置」，不是「0」。 */}
                  {q.configured ? (
                    <Badge tone={q.remaining > 0 ? 'success' : 'danger'}>剩 {q.remaining} 次</Badge>
                  ) : (
                    <Badge tone="neutral">未配置配额</Badge>
                  )}
                  <span className="text-copy-12 text-muted-foreground">{quotaText(q)}</span>
                </div>
                {q.periodStart && (
                  <div className="text-copy-12 text-muted-foreground mt-1">
                    周期 {q.periodStart} ~ {q.periodEnd} · 已用 {q.usedCount} / {q.quotaLimit} 次
                  </div>
                )}
              </div>
            ))}
          </div>
        )}
      </SectionCard>

      {/* ---- 申诉单详情 ---- */}
      <Modal open={detail !== null} onOpenChange={(open) => !open && setDetail(null)}>
        <ModalContent className="max-w-3xl">
          <ModalHeader>
            <ModalTitle>{detail?.appeal.violationTitle ?? '申诉详情'}</ModalTitle>
            <ModalDescription>
              {detail
                ? `${detail.appeal.shopCode} · ${detail.appeal.appealTypeLabel} · ${statusLabel(detail.appeal.status)}`
                : ''}
            </ModalDescription>
          </ModalHeader>

          {detail && (
            <div className="space-y-4">
              <div className="flex flex-wrap items-center gap-2">
                <Badge tone={impactTone(detail.appeal.impactLevel)}>
                  {impactLabel(detail.appeal.impactLevel)}
                </Badge>
                <Badge tone={statusTone(detail.appeal.status)}>
                  {statusLabel(detail.appeal.status)}
                </Badge>
                <Badge tone="info">{detail.appeal.appealTypeLabel}</Badge>
                <span className="text-copy-12 text-muted-foreground">
                  配额：{quotaText(detail.quota)}
                </span>
              </div>

              <div className="text-copy-13 text-foreground whitespace-pre-wrap">
                {detail.appeal.appealText}
              </div>

              {/* ---- 举证材料（只追加） ---- */}
              <div>
                <div className="text-copy-13 text-foreground mb-2 font-medium">
                  举证材料（{detail.evidence.length} 条 · 只追加，不可改写不可删）
                </div>
                {detail.evidence.length === 0 ? (
                  <div className="text-copy-12 text-muted-foreground">还没有举证材料。</div>
                ) : (
                  <div className="space-y-1">
                    {detail.evidence.map((e) => (
                      <div key={e.evidenceId} className="border-border rounded-md border px-3 py-2">
                        <div className="flex flex-wrap items-center gap-2">
                          <Badge tone="info">{evidenceTypeLabel(e.evidenceType)}</Badge>
                          <span className="text-copy-13 text-foreground">
                            {e.evidenceRef || '—'}
                          </span>
                          <span className="text-copy-12 text-muted-foreground">{e.summary}</span>
                          <span className="text-copy-12 text-muted-foreground ml-auto">
                            {e.submittedAt}
                          </span>
                        </div>
                      </div>
                    ))}
                  </div>
                )}
                {detail.appeal.status === 'DRAFT' && (
                  <div className="mt-2 flex flex-wrap items-center gap-2">
                    <select
                      className="border-input bg-background text-copy-13 h-9 rounded-md border px-2"
                      value={evidenceType}
                      onChange={(e) => setEvidenceType(e.target.value)}
                    >
                      {EVIDENCE_TYPE_OPTIONS.map((option) => (
                        <option key={option.value} value={option.value}>
                          {option.label}
                        </option>
                      ))}
                    </select>
                    <Input
                      className="w-56"
                      placeholder="引用（文件名 / 链接）"
                      value={evidenceRef}
                      onChange={(e) => setEvidenceRef(e.target.value)}
                    />
                    <Input
                      className="w-56"
                      placeholder="说明"
                      value={evidenceSummary}
                      onChange={(e) => setEvidenceSummary(e.target.value)}
                    />
                    <Button
                      size="sm"
                      disabled={busy || (!evidenceRef && !evidenceSummary)}
                      onClick={() => void doAddEvidence(detail.appeal.appealId)}
                    >
                      追加举证材料
                    </Button>
                  </div>
                )}
              </div>

              {/* ---- 提交记录（只追加） ---- */}
              <div>
                <div className="text-copy-13 text-foreground mb-2 font-medium">
                  提交记录（{detail.submissions.length} 次 · 只追加）
                </div>
                {detail.submissions.length === 0 ? (
                  <div className="text-copy-12 text-muted-foreground">还没有提交过。</div>
                ) : (
                  <div className="space-y-1">
                    {detail.submissions.map((s) => (
                      <div
                        key={s.submissionId}
                        className="border-border rounded-md border px-3 py-2"
                      >
                        <div className="flex flex-wrap items-center gap-2">
                          <Badge tone="info">第 {s.attemptNo} 次</Badge>
                          <span className="text-copy-12 text-muted-foreground">
                            {s.submittedAt}
                            {s.platformReference ? ` · 平台受理号 ${s.platformReference}` : ''}
                          </span>
                        </div>
                      </div>
                    ))}
                  </div>
                )}
              </div>

              {/* ---- 结果记录（只追加） ---- */}
              <div>
                <div className="text-copy-13 text-foreground mb-2 font-medium">
                  结果记录（{detail.decisions.length} 条 · 只追加）
                </div>
                {detail.decisions.length === 0 ? (
                  <div className="text-copy-12 text-muted-foreground">平台还没裁定。</div>
                ) : (
                  <div className="space-y-1">
                    {detail.decisions.map((d) => (
                      <div key={d.decisionId} className="border-border rounded-md border px-3 py-2">
                        <div className="flex flex-wrap items-center gap-2">
                          <Badge tone={d.outcome === 'APPROVED' ? 'success' : 'danger'}>
                            {outcomeLabel(d.outcome)}
                          </Badge>
                          <span className="text-copy-13 text-foreground">{d.reason || '—'}</span>
                          <span className="text-copy-12 text-muted-foreground ml-auto">
                            {d.decidedAt}
                          </span>
                        </div>
                      </div>
                    ))}
                  </div>
                )}
              </div>

              {/* ---- 操作区 ---- */}
              <div className="border-border flex flex-wrap items-center gap-2 rounded-md border px-3 py-2">
                {detail.appeal.status === 'DRAFT' && (
                  <>
                    <Input
                      className="w-40"
                      placeholder="平台侧受理号"
                      value={platformReference}
                      onChange={(e) => setPlatformReference(e.target.value)}
                    />
                    <Button
                      size="sm"
                      disabled={busy}
                      onClick={() => void doSubmit(detail.appeal.appealId)}
                    >
                      提交申诉（消耗一次配额）
                    </Button>
                    <Button
                      variant="secondary"
                      size="sm"
                      disabled={busy}
                      onClick={() => void doWithdraw(detail.appeal.appealId)}
                    >
                      撤回
                    </Button>
                  </>
                )}
                {detail.appeal.status === 'SUBMITTED' && (
                  <>
                    <select
                      className="border-input bg-background text-copy-13 h-9 rounded-md border px-2"
                      value={decisionOutcome}
                      onChange={(e) => setDecisionOutcome(e.target.value)}
                    >
                      <option value="APPROVED">通过</option>
                      <option value="REJECTED">驳回</option>
                    </select>
                    <Input
                      className="w-56"
                      placeholder="裁定理由"
                      value={decisionReason}
                      onChange={(e) => setDecisionReason(e.target.value)}
                    />
                    <Button
                      size="sm"
                      disabled={busy}
                      onClick={() => void doDecide(detail.appeal.appealId)}
                    >
                      记录平台裁定
                    </Button>
                  </>
                )}
                {detail.appeal.status === 'REJECTED' && (
                  <Button
                    variant="secondary"
                    size="sm"
                    disabled={busy}
                    onClick={() => void doReopen(detail.appeal.appealId)}
                  >
                    回到草稿（补充材料后再次申诉）
                  </Button>
                )}
              </div>

              {detail.appeal.status === 'DRAFT' && (
                <div className="flex flex-wrap items-center gap-2">
                  <Input
                    className="h-9 flex-1"
                    placeholder="改申诉文本（只有草稿态能改）"
                    value={newText}
                    onChange={(e) => setNewText(e.target.value)}
                  />
                  <Button
                    variant="secondary"
                    size="sm"
                    disabled={busy || !newText}
                    onClick={() => void doUpdateText(detail.appeal.appealId)}
                  >
                    保存文本
                  </Button>
                </div>
              )}

              <div className="text-copy-12 text-muted-foreground">{detail.note}</div>
            </div>
          )}

          <ModalFooter>
            <Button variant="secondary" onClick={() => setDetail(null)}>
              关闭
            </Button>
          </ModalFooter>
        </ModalContent>
      </Modal>

      {/* ---- 新建申诉单 ---- */}
      <Modal open={newAppeal !== null} onOpenChange={(open) => !open && setNewAppeal(null)}>
        <ModalContent className="max-w-2xl">
          <ModalHeader>
            <ModalTitle>发起申诉</ModalTitle>
            <ModalDescription>{newAppeal ? `关联违规：${newAppeal.title}` : ''}</ModalDescription>
          </ModalHeader>
          <div className="space-y-3">
            <div>
              <label className="text-label-13 text-foreground mb-1 block">申诉类型</label>
              <select
                className="border-input bg-background text-copy-13 h-9 w-full rounded-md border px-2"
                value={newType}
                onChange={(e) => setNewType(e.target.value)}
              >
                {APPEAL_TYPE_OPTIONS.filter((option) => option.value !== '').map((option) => (
                  <option key={option.value} value={option.value}>
                    {option.label}
                  </option>
                ))}
              </select>
            </div>
            <div>
              <label className="text-label-13 text-foreground mb-1 block">申诉文本</label>
              <textarea
                className="border-input bg-background text-copy-13 min-h-28 w-full rounded-md border px-2 py-1.5"
                value={newText}
                onChange={(e) => setNewText(e.target.value)}
                placeholder="说明为什么这条违规不成立、要附什么材料"
              />
            </div>
          </div>
          <ModalFooter>
            <Button variant="secondary" onClick={() => setNewAppeal(null)}>
              取消
            </Button>
            <Button disabled={busy || !newText.trim()} onClick={() => void doCreate()}>
              建单（草稿）
            </Button>
          </ModalFooter>
        </ModalContent>
      </Modal>

      {/* 店铺范围（真实存在的店铺目录，用来挑要配额的店铺） */}
      <ShopScope shopId={shopId} onPick={(id) => setQuotaShopId(id)} />
    </div>
  );
}

/** 店铺范围清单（真分页；数据来自后端自己的店铺目录，不是硬编码）。 */
function ShopScope({ shopId, onPick }: { shopId: string; onPick: (id: string) => void }) {
  const [shops, setShops] = useState<Awaited<ReturnType<typeof fetchShops>> | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let alive = true;
    fetchShops(0, 50)
      .then((result) => {
        if (alive) setShops(result);
      })
      .catch((e: unknown) => {
        if (alive) setError(e instanceof Error ? e.message : '店铺清单加载失败');
      });
    return () => {
      alive = false;
    };
  }, []);

  if (error) {
    return (
      <SectionCard title="店铺范围" description="本服务自己的店铺目录。">
        <div className="text-copy-13 text-destructive">{error}</div>
      </SectionCard>
    );
  }

  return (
    <SectionCard title="店铺范围" description="本服务自己的店铺目录（真分页数据，不是硬编码）。">
      {(shops?.items ?? []).length === 0 ? (
        <EmptyState
          icon={<ScrollText className="size-8" />}
          title="还没有店铺"
          description="渠道与店铺同步之后这里才会有店铺。"
        />
      ) : (
        <div className="flex flex-wrap gap-2">
          {(shops?.items ?? []).map((s) => (
            <button
              key={s.shopId}
              type="button"
              onClick={() => onPick(s.shopId)}
              className={
                shopId === s.shopId
                  ? 'border-primary bg-muted/40 text-copy-13 rounded-md border px-3 py-1.5'
                  : 'border-border hover:bg-muted/40 text-copy-13 rounded-md border px-3 py-1.5'
              }
            >
              {s.shopCode} · 申诉 {s.appealCount} 单（在办 {s.openAppealCount}）· 配额剩{' '}
              {s.quotaRemaining} 次
            </button>
          ))}
        </div>
      )}
    </SectionCard>
  );
}
