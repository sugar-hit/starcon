'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import { Badge, Button, EmptyState, SectionCard, StatCard } from '@starcon/ui';
import { CopyCheck, Info, RefreshCw, ScanSearch, TriangleAlert } from 'lucide-react';
import type {
  DuplicateCandidateDetail,
  DuplicateCandidatePage,
  DuplicateOverview,
  DuplicateScanRow,
} from '../../../../../packages/api-contracts/src/catalog';
import {
  canSubmitDecision,
  DECISION_FILTERS,
  decisionButtonLabel,
  decisionPayload,
  DEFAULT_MIN_SIMILARITY,
  DIMENSIONS,
  durationText,
  errorText,
  executionHint,
  fetchCandidateDetail,
  fetchCandidates,
  fetchOverview,
  fetchScans,
  primaryOptions,
  referenceSummary,
  runScan,
  scopeWarning,
  signalSummary,
  similarityText,
  submitDecision,
  timeText,
  topScore,
  validateMinSimilarity,
  type DecisionDraft,
} from './duplicate-model';
import { useIdentity } from '../iam/identity-shell';

/**
 * 3.9.1.1 重复 SKU 识别工作区（路由 `/products/duplicates`）。
 *
 * <p>蓝图：「识别重复/近重复商品……输出候选与相似度供人工确认」，输出里还有「确认合并/保留决定」。
 * 页面按这条链路分三段：<b>扫描 → 候选清单 → 单个候选的证据与决定</b>。
 *
 * <p>界面纪律：
 *
 * <ul>
 *   <li><b>加载 / 空态 / 错误态三态分开</b>。「还没扫过」与「扫完 0 个候选」是两件完全不同的事：
 *       前者要引导去扫，后者要给出「按什么范围扫的」的交代 —— 把两者混成同一个空白页，
 *       运营会把「没查过」当成「查过了、很干净」。
 *   <li><b>范围收窄的警示摆在概览顶部</b>，不是脚注（见 {@link scopeWarning}）。
 *   <li><b>逐维度证据与引用拆分必须能点开看</b>：候选页只给分数是不够的，
 *       「凭什么说它像」「合并会搬动什么」这两个问题都要在这一页答完。
 *   <li><b>决定只登记不执行</b>，按钮与提示都写死这一点（见 {@link executionHint}）。
 *   <li><b>只读角色看得见但动不了</b>，写操作按 {@code catalog.spu.update} 收敛。
 * </ul>
 */
export function DuplicateWorkspace() {
  const { session } = useIdentity();
  const canWrite = (session.permissions ?? []).includes('catalog.spu.update');

  const [overview, setOverview] = useState<DuplicateOverview | null>(null);
  const [scans, setScans] = useState<DuplicateScanRow[]>([]);
  const [scanId, setScanId] = useState<string | null>(null);
  const [candidates, setCandidates] = useState<DuplicateCandidatePage | null>(null);
  const [detail, setDetail] = useState<DuplicateCandidateDetail | null>(null);

  const [dimension, setDimension] = useState('');
  const [decision, setDecision] = useState('');
  const [minSimilarity, setMinSimilarity] = useState('');
  const [threshold, setThreshold] = useState(DEFAULT_MIN_SIMILARITY);
  const [category, setCategory] = useState('');
  const [brand, setBrand] = useState('');

  const [draft, setDraft] = useState<DecisionDraft | null>(null);
  const [loading, setLoading] = useState(true);
  const [listing, setListing] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);

  const thresholdCheck = useMemo(() => validateMinSimilarity(threshold), [threshold]);
  const submitCheck = useMemo(() => canSubmitDecision(draft, detail), [draft, detail]);
  const scopeNote = useMemo(() => scopeWarning(overview?.latestScan ?? null), [overview]);

  const loadOverview = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const [next, page] = await Promise.all([fetchOverview(), fetchScans(0, 10)]);
      setOverview(next);
      setScans(page.items ?? []);
      setScanId((current) => current ?? next.latestScan?.id ?? page.items?.[0]?.id ?? null);
    } catch (e) {
      setError(errorText(e));
    } finally {
      setLoading(false);
    }
  }, []);

  const loadCandidates = useCallback(async () => {
    if (!scanId) {
      setCandidates(null);
      return;
    }
    setListing(true);
    setError(null);
    try {
      // exactOptionalPropertyTypes 下不能显式传 undefined，所以逐个条件展开：
      // 「没选筛选」与「传了个 undefined」在类型上是两件事。
      setCandidates(
        await fetchCandidates(scanId, {
          ...(dimension ? { dimension } : {}),
          ...(decision ? { decision } : {}),
          ...(minSimilarity ? { minSimilarity } : {}),
          page: 0,
          size: 50,
        }),
      );
    } catch (e) {
      setError(errorText(e));
      setCandidates(null);
    } finally {
      setListing(false);
    }
  }, [scanId, dimension, decision, minSimilarity]);

  useEffect(() => {
    void loadOverview();
  }, [loadOverview]);
  useEffect(() => {
    void loadCandidates();
  }, [loadCandidates]);

  async function scan() {
    if (!thresholdCheck.ok) {
      setActionError(thresholdCheck.reason);
      return;
    }
    setBusy(true);
    setActionError(null);
    try {
      const result = await runScan(threshold, category.trim(), brand.trim());
      setScanId(result.id);
      await loadOverview();
      setCandidates(null);
      setDetail(null);
    } catch (e) {
      setActionError(errorText(e));
    } finally {
      setBusy(false);
    }
  }

  async function open(candidateId: string) {
    setBusy(true);
    setActionError(null);
    try {
      const loaded = await fetchCandidateDetail(candidateId);
      setDetail(loaded);
      // 已登记的决定回填成草稿：重进页面就能看出「这条已经判过、判成了什么」。
      setDraft(
        loaded.decision
          ? {
              decision: loaded.decision.decision === 'MERGE' ? 'MERGE' : 'KEEP',
              primarySkuId: loaded.decision.primarySkuId ?? '',
              note: loaded.decision.note ?? '',
            }
          : null,
      );
    } catch (e) {
      setActionError(errorText(e));
    } finally {
      setBusy(false);
    }
  }

  async function decide() {
    if (!detail || !draft || !submitCheck.ok) return;
    setBusy(true);
    setActionError(null);
    try {
      await submitDecision(detail.candidate.id, decisionPayload(draft, detail.decision));
      // 登记完重拉一次：候选行上的决定与三态计数都要跟着变。
      await loadCandidates();
      await open(detail.candidate.id);
    } catch (e) {
      setActionError(errorText(e));
    } finally {
      setBusy(false);
    }
  }

  const latest = overview?.latestScan ?? null;
  const hasEverScanned = scans.length > 0;

  return (
    <div className="space-y-5">
      <SectionCard
        title="重复 SKU 识别"
        description="识别同 SPU 重复规格组合、同商品不同 SPU 重复建档、同条码多 SKU、近似标题+相似图片疑似同品，输出候选与相似度供人工确认。"
        extra={
          <div className="flex items-center gap-2">
            <Button variant="ghost" size="sm" onClick={() => void loadOverview()} disabled={loading}>
              <RefreshCw className={`h-4 w-4 ${loading ? 'animate-spin' : ''}`} />
              刷新
            </Button>
          </div>
        }
      >
        {error && (
          <div className="flex items-start gap-2 text-destructive text-copy-13">
            <TriangleAlert className="mt-0.5 h-4 w-4 shrink-0" />
            <span>{error}</span>
          </div>
        )}

        {/* 范围收窄的警示：必须排在统计之前，且不能折叠 */}
        {scopeNote && (
          <div className="border-warning-soft bg-warning-soft text-warning mt-3 flex items-start gap-2 rounded-md px-3 py-2 text-copy-13">
            <TriangleAlert className="mt-0.5 h-4 w-4 shrink-0" />
            <span>{scopeNote}</span>
          </div>
        )}

        {loading && !overview ? (
          <p className="text-muted-foreground text-copy-13 py-4">正在加载扫描概览…</p>
        ) : (
          <div className="mt-4 grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
            <StatCard label="本次扫描 SKU" value={latest?.scannedSkuCount ?? 0} />
            <StatCard label="候选对" value={latest?.candidateCount ?? 0} />
            <StatCard label="待确认" value={overview?.pendingCount ?? 0} />
            <StatCard label="已判合并 / 保留" value={`${overview?.mergeCount ?? 0} / ${overview?.keepCount ?? 0}`} />
          </div>
        )}

        {overview && overview.dimensions?.length > 0 && (
          <div className="mt-4 flex flex-wrap gap-2">
            {overview.dimensions.map((d) => (
              <Badge key={d.dimension} tone={d.pairCount > 0 ? 'brand' : 'neutral'}>
                {d.label} {d.pairCount} 对{d.pairCount > 0 ? ` · 最高 ${similarityText(d.topScore)}` : ''}
              </Badge>
            ))}
          </div>
        )}

        {overview && (
          <p className="text-muted-foreground text-copy-13 mt-3 flex items-start gap-2">
            <Info className="mt-0.5 h-4 w-4 shrink-0" />
            <span>{overview.note}</span>
          </p>
        )}
      </SectionCard>

      <SectionCard
        title="发起扫描"
        description="扫描读全量商品与 SKU 的条码、标题、规格组合与图片特征，按阈值产出候选。大租户可按类目或品牌分片扫，但分片会漏掉跨范围的重复。"
      >
        <div className="flex flex-wrap items-end gap-3">
          <label className="flex flex-col gap-1 text-copy-13">
            <span className="text-muted-foreground">相似度阈值</span>
            <input
              className="border-border w-32 rounded-md border px-2 py-1.5"
              value={threshold}
              onChange={(e) => setThreshold(e.target.value)}
              placeholder={DEFAULT_MIN_SIMILARITY}
              disabled={!canWrite}
            />
          </label>
          <label className="flex flex-col gap-1 text-copy-13">
            <span className="text-muted-foreground">类目（留空 = 全量）</span>
            <input
              className="border-border w-44 rounded-md border px-2 py-1.5"
              value={category}
              onChange={(e) => setCategory(e.target.value)}
              disabled={!canWrite}
            />
          </label>
          <label className="flex flex-col gap-1 text-copy-13">
            <span className="text-muted-foreground">品牌（留空 = 全量）</span>
            <input
              className="border-border w-44 rounded-md border px-2 py-1.5"
              value={brand}
              onChange={(e) => setBrand(e.target.value)}
              disabled={!canWrite}
            />
          </label>
          <Button onClick={() => void scan()} disabled={!canWrite || busy || !thresholdCheck.ok}>
            <ScanSearch className="h-4 w-4" />
            开始扫描
          </Button>
          {!canWrite && (
            <span className="text-muted-foreground text-copy-13">
              当前角色只能查看：发起扫描与登记决定需要商品写权限。
            </span>
          )}
        </div>
        {!thresholdCheck.ok && <p className="text-destructive text-copy-13 mt-2">{thresholdCheck.reason}</p>}
        {actionError && <p className="text-destructive text-copy-13 mt-2">{actionError}</p>}
        {hasEverScanned && (
          <p className="text-muted-foreground text-copy-13 mt-3">
            每次扫描都独立留档（当前共 {scans.length} 次）。治理之后重新扫一次，候选数的变化就是治理效果的证据。
          </p>
        )}
      </SectionCard>

      <SectionCard
        title="重复候选清单"
        description="蓝图输出：相似度、重叠维度、影响引用数。点一行看逐维度证据与引用拆分，再登记合并/保留决定。"
      >
        {scans.length > 1 && (
          <div className="mb-3 flex flex-wrap items-center gap-2 text-copy-13">
            <span className="text-muted-foreground">扫描：</span>
            {scans.map((s) => (
              <Button
                key={s.id}
                variant={s.id === scanId ? 'brand' : 'ghost'}
                size="sm"
                onClick={() => setScanId(s.id)}
              >
                {timeText(s.startedAt)}（{s.candidateCount} 对）
              </Button>
            ))}
          </div>
        )}

        <div className="mb-3 flex flex-wrap items-end gap-3">
          <label className="flex flex-col gap-1 text-copy-13">
            <span className="text-muted-foreground">维度</span>
            <select
              className="border-border rounded-md border px-2 py-1.5"
              value={dimension}
              onChange={(e) => setDimension(e.target.value)}
            >
              <option value="">全部</option>
              {DIMENSIONS.map((d) => (
                <option key={d.value} value={d.value}>
                  {d.label}
                </option>
              ))}
            </select>
          </label>
          <label className="flex flex-col gap-1 text-copy-13">
            <span className="text-muted-foreground">决定</span>
            <select
              className="border-border rounded-md border px-2 py-1.5"
              value={decision}
              onChange={(e) => setDecision(e.target.value)}
            >
              {DECISION_FILTERS.map((d) => (
                <option key={d.value || 'ALL'} value={d.value}>
                  {d.label}
                </option>
              ))}
            </select>
          </label>
          <label className="flex flex-col gap-1 text-copy-13">
            <span className="text-muted-foreground">最低相似度</span>
            <input
              className="border-border w-32 rounded-md border px-2 py-1.5"
              value={minSimilarity}
              onChange={(e) => setMinSimilarity(e.target.value)}
              placeholder="不限"
            />
          </label>
        </div>

        {listing ? (
          <p className="text-muted-foreground text-copy-13 py-4">正在加载候选清单…</p>
        ) : !hasEverScanned ? (
          <EmptyState
            icon={<ScanSearch className="h-6 w-6" />}
            title="还没有扫描过"
            description="扫一次才会产出重复候选清单。点上面的「开始扫描」读全量商品与 SKU 的条码、标题、规格组合与图片特征。"
          />
        ) : !candidates || candidates.items.length === 0 ? (
          <EmptyState
            icon={<CopyCheck className="h-6 w-6" />}
            title="这份扫描没有重复候选"
            description={
              scopeNote
                ? `${scopeNote} 如果刚收窄过范围，这份「0 个候选」只能说明范围内干净。`
                : '按当前筛选条件下没有候选。若刚改过商品数据，重新扫一次再看。'
            }
          />
        ) : (
          <div className="space-y-2">
            {candidates.items.map((row) => (
              <button
                key={row.id}
                type="button"
                onClick={() => void open(row.id)}
                className="border-border hover:bg-muted/40 block w-full rounded-md border px-3 py-2 text-left"
              >
                <div className="flex flex-wrap items-center justify-between gap-2">
                  <span className="text-copy-14 font-medium">
                    {row.leftSkuCode}（{row.leftSpuCode}） ↔ {row.rightSkuCode}（{row.rightSpuCode}）
                  </span>
                  <span className="flex items-center gap-2">
                    <Badge tone={row.overlapDimensionCount > 1 ? 'brand' : 'info'}>
                      {similarityText(topScore(row))}
                    </Badge>
                    <Badge
                      tone={
                        row.decision === 'MERGE'
                          ? 'warning'
                          : row.decision === 'KEEP'
                            ? 'success'
                            : 'neutral'
                      }
                    >
                      {row.decisionLabel}
                    </Badge>
                  </span>
                </div>
                <div className="text-muted-foreground text-copy-13 mt-1">
                  {signalSummary(row)} · 引用 {row.leftReferenceCount} / {row.rightReferenceCount}
                </div>
              </button>
            ))}
            <p className="text-muted-foreground text-copy-13">
              共 {candidates.total} 对（当前筛选下显示前 {candidates.items.length} 条）。
            </p>
          </div>
        )}
      </SectionCard>

      {detail && (
        <SectionCard
          title="候选详情"
          description="两侧商品身份 + 现算的规格组合 + 全部条码 + 逐维度证据 + 引用逐项拆分 + 已登记的决定。"
        >
          <div className="grid gap-4 md:grid-cols-2">
            {[detail.leftVariantCombination, detail.rightVariantCombination].map((signature, index) => (
              <div key={index} className="border-border rounded-md border px-3 py-2">
                <div className="text-copy-14 font-medium">
                  {index === 0
                    ? `${detail.candidate.leftSkuCode} · ${detail.candidate.leftSkuName}`
                    : `${detail.candidate.rightSkuCode} · ${detail.candidate.rightSkuName}`}
                </div>
                <div className="text-muted-foreground text-copy-13 mt-1">
                  所属商品 {index === 0 ? detail.candidate.leftSpuCode : detail.candidate.rightSpuCode} ·{' '}
                  {index === 0 ? detail.candidate.leftSpuName : detail.candidate.rightSpuName}
                </div>
                <div className="text-muted-foreground text-copy-13 mt-1">
                  规格组合：
                  {signature === ''
                    ? '解析不出轴值（导入占位签名的 SKU 常见），不参与规格组合这一维'
                    : signature}
                </div>
                <div className="text-muted-foreground text-copy-13 mt-1">
                  条码：{(index === 0 ? detail.leftBarcodeValues : detail.rightBarcodeValues).join('、') || '无'}
                </div>
                <div className="text-muted-foreground text-copy-13 mt-1">
                  引用：{referenceSummary(index === 0 ? detail.leftReferences : detail.rightReferences)}
                </div>
              </div>
            ))}
          </div>

          <div className="mt-4">
            <h4 className="text-copy-14 font-medium">逐维度证据</h4>
            <ul className="mt-2 space-y-1">
              {detail.signals.map((signal) => (
                <li key={signal.dimension} className="text-copy-13">
                  <Badge tone="brand">{signal.label}</Badge>{' '}
                  <span className="text-muted-foreground">{similarityText(signal.score)}</span> ·{' '}
                  {signal.evidence}
                </li>
              ))}
            </ul>
          </div>

          <div className="border-border mt-4 rounded-md border px-3 py-3">
            <h4 className="text-copy-14 font-medium">登记决定（本模块不执行合并）</h4>
            {detail.decision && (
              <p className="text-muted-foreground text-copy-13 mt-1">
                已登记：{detail.decision.decisionLabel}
                {detail.decision.primarySkuCode
                  ? ` · 保留 ${detail.decision.primarySkuCode}，被合并 ${detail.decision.secondarySkuCode}`
                  : ''}
                {detail.decision.note ? ` · 理由：${detail.decision.note}` : ''}
              </p>
            )}
            {draft && (
              <>
                <div className="mt-3 flex flex-wrap items-end gap-3">
                  <label className="flex flex-col gap-1 text-copy-13">
                    <span className="text-muted-foreground">决定</span>
                    <select
                      className="border-border rounded-md border px-2 py-1.5"
                      value={draft.decision}
                      onChange={(e) =>
                        setDraft({ ...draft, decision: e.target.value as 'MERGE' | 'KEEP' })
                      }
                      disabled={!canWrite}
                    >
                      <option value="MERGE">确认合并</option>
                      <option value="KEEP">保留两条</option>
                    </select>
                  </label>
                  {draft.decision === 'MERGE' && (
                    <label className="flex flex-col gap-1 text-copy-13">
                      <span className="text-muted-foreground">保留哪一条（主记录）</span>
                      <select
                        className="border-border rounded-md border px-2 py-1.5"
                        value={draft.primarySkuId}
                        onChange={(e) => setDraft({ ...draft, primarySkuId: e.target.value })}
                        disabled={!canWrite}
                      >
                        <option value="">请选择</option>
                        {primaryOptions(detail).map((option) => (
                          <option key={option.value} value={option.value}>
                            {option.label}
                          </option>
                        ))}
                      </select>
                    </label>
                  )}
                  <label className="flex flex-1 flex-col gap-1 text-copy-13">
                    <span className="text-muted-foreground">理由</span>
                    <input
                      className="border-border rounded-md border px-2 py-1.5"
                      value={draft.note}
                      onChange={(e) => setDraft({ ...draft, note: e.target.value })}
                      disabled={!canWrite}
                      placeholder="为什么合并 / 为什么保留两条"
                    />
                  </label>
                  <Button
                    onClick={() => void decide()}
                    disabled={!canWrite || busy || !submitCheck.ok}
                  >
                    {decisionButtonLabel(draft.decision)}
                  </Button>
                </div>
                {!submitCheck.ok && (
                  <p className="text-destructive text-copy-13 mt-2">{submitCheck.reason}</p>
                )}
                <p className="text-muted-foreground text-copy-13 mt-2">{executionHint(draft.decision)}</p>
              </>
            )}
            {!canWrite && (
              <p className="text-muted-foreground text-copy-13 mt-2">
                当前角色只能查看：登记决定需要商品写权限。
              </p>
            )}
          </div>
        </SectionCard>
      )}

      {latest && (
        <p className="text-muted-foreground text-copy-13">
          最近一次扫描：{timeText(latest.startedAt)} 开始，耗时 {durationText(latest.durationMs)}，
          覆盖 {latest.scannedSkuCount} 个 SKU / {latest.scannedSpuCount} 个 SPU，
          阈值 {similarityText(latest.minSimilarity)}。
        </p>
      )}
    </div>
  );
}