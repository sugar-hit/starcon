'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import {
  Badge,
  Button,
  EmptyState,
  PageHeader,
  SectionCard,
  StatCard,
} from '@starcon/ui';
import {
  CircleHelp,
  ClipboardCheck,
  History,
  Loader2,
  RefreshCw,
  ShieldAlert,
  TriangleAlert,
} from 'lucide-react';
import {
  dimensionScoreText,
  emptyState,
  fetchCapabilities,
  fetchDrafts,
  fetchReport,
  fetchReports,
  runCheck,
  severityLabel,
  triggerText,
  unknownDimensions,
  unknownFindings,
  verdictLabel,
  verdictTone,
  violations,
  type CompliancePrecheckCapabilities,
  type CompliancePrecheckDimensionScore,
  type CompliancePrecheckDraft,
  type CompliancePrecheckFinding,
  type CompliancePrecheckReport,
  type CompliancePrecheckReportSummary,
  type TriggerKind,
} from './compliance-precheck-model';

/**
 * 16.2.2.1 合规预检 工作区。
 *
 * 蓝图：「发布前的合规体检：内容违规、认证缺失、尺寸规格不符、材质与描述不符、危险品与运输限制、
 * 标签与说明书要求，多维度检查并给出整改建议。输入：listing 内容草稿；商品属性；认证状态；平台要求。
 * 输出：体检报告（分维度得分 + 问题清单 + 整改建议）。关系：上游 = 4.2.1.1 编辑器；下游 = 16.2.1.2 拦截。
 * **预检在保存草稿时就跑一次**（即时反馈），**发布时再跑一次**（强制校验）。」
 *
 * 🔴 这个页面上的三态**文案不同**（不是同一个空态换个图标）：
 *
 * - **没有可体检的草稿** ⇒ 提示去 4.2.1.1 刊登编辑器先存一份草稿。
 * - **体检了但没有问题** ⇒ 说清「已登记的平台要求逐条通过」，并提醒量不到的维度不产生问题项。
 * - **所有维度都量不到** ⇒ 说清「得分是未知而不是 0 分」，并把归属模块逐个列出来。
 *
 * 🔴 页面**没有**「拦截 / 豁免 / 审批」按钮：那归 16.2.1.2 拦截执行（未交付）。
 * 「发布前的强制校验」在这里是一次**体检**，不是一次放行决定。
 */
export function CompliancePrecheckWorkspace() {
  const [caps, setCaps] = useState<CompliancePrecheckCapabilities | null>(null);
  const [drafts, setDrafts] = useState<CompliancePrecheckDraft[]>([]);
  const [draftTotal, setDraftTotal] = useState(0);
  const [draftPage, setDraftPage] = useState(0);
  const [selectedDraftId, setSelectedDraftId] = useState<string | null>(null);
  const [trigger, setTrigger] = useState<TriggerKind>('DRAFT_SAVE');

  const [report, setReport] = useState<CompliancePrecheckReport | null>(null);
  const [history, setHistory] = useState<CompliancePrecheckReportSummary[]>([]);
  const [historyTotal, setHistoryTotal] = useState(0);
  const [historyPage, setHistoryPage] = useState(0);

  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const DRAFT_PAGE_SIZE = 10;
  const HISTORY_PAGE_SIZE = 10;

  const loadDrafts = useCallback(async (page: number) => {
    const result = await fetchDrafts(page, DRAFT_PAGE_SIZE);
    setDrafts(result.items);
    setDraftTotal(result.total);
    return result;
  }, []);

  const loadHistory = useCallback(async (page: number) => {
    const result = await fetchReports(page, HISTORY_PAGE_SIZE);
    setHistory(result.items);
    setHistoryTotal(result.total);
    return result;
  }, []);

  const refresh = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const [capsResult, draftResult] = await Promise.all([fetchCapabilities(), loadDrafts(0)]);
      setCaps(capsResult);
      setDraftPage(0);
      const nextSelected =
        selectedDraftId && draftResult.items.some((d) => d.draftId === selectedDraftId)
          ? selectedDraftId
          : (draftResult.items[0]?.draftId ?? null);
      setSelectedDraftId(nextSelected);
      await loadHistory(0);
      setHistoryPage(0);
    } catch (e) {
      setError(e instanceof Error ? e.message : '加载失败，请重试');
    } finally {
      setLoading(false);
    }
  }, [loadDrafts, loadHistory, selectedDraftId]);

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

  const triggerCheck = useCallback(
    () =>
      run(async () => {
        if (!selectedDraftId) return;
        const result = await runCheck(selectedDraftId, trigger);
        setReport(result);
        await loadHistory(historyPage);
      }),
    [run, selectedDraftId, trigger, loadHistory, historyPage],
  );

  const openReport = useCallback(
    (reportId: string) =>
      run(async () => {
        setReport(await fetchReport(reportId));
      }),
    [run],
  );

  const unknown = useMemo(() => unknownDimensions(report), [report]);
  const problems = useMemo(() => violations(report), [report]);
  const notMeasurable = useMemo(() => unknownFindings(report), [report]);
  const allUnknown = Boolean(report) && report!.dimensions.length > 0 && unknown.length === report!.dimensions.length;
  const selectedDraft = useMemo(
    () => drafts.find((draft) => draft.draftId === selectedDraftId) ?? null,
    [drafts, selectedDraftId],
  );

  // 🔴 三态：加载中 / 加载失败 / 加载成功但**没有可体检的草稿**。
  if (loading) {
    return (
      <div className="flex items-center justify-center gap-2 py-16 text-muted-foreground">
        <Loader2 className="size-4 animate-spin" />
        <span className="text-label-13">正在加载合规预检…</span>
      </div>
    );
  }

  if (error && !caps) {
    return (
      <SectionCard title="合规预检">
        <EmptyState
          icon={<TriangleAlert className="size-8 text-destructive" />}
          title="合规预检加载失败"
          description={error}
          action={
            <Button variant="secondary" onClick={() => void refresh()}>
              <RefreshCw className="mr-2 size-4" />
              重试
            </Button>
          }
        />
      </SectionCard>
    );
  }

  if (!caps) {
    return (
      <SectionCard title="合规预检">
        <EmptyState
          icon={<TriangleAlert className="size-8 text-destructive" />}
          title="合规预检加载失败"
          description="没有拿到能力声明，请重试。"
          action={
            <Button variant="secondary" onClick={() => void refresh()}>
              <RefreshCw className="mr-2 size-4" />
              重试
            </Button>
          }
        />
      </SectionCard>
    );
  }

  const noDraft = emptyState('NO_DRAFT');

  return (
    <div className="space-y-4">
      <PageHeader
        title="合规预检"
        description="发布前的合规体检：多维度检查，给出分维度得分、问题清单与整改建议。保存草稿时跑一次（即时反馈），发布前再跑一次（强制校验）。"
        actions={
          <Button variant="ghost" size="sm" onClick={() => void refresh()} disabled={busy}>
            <RefreshCw className="mr-2 size-4" />
            刷新
          </Button>
        }
      />

      {error && (
        <div className="border-destructive/40 bg-destructive/5 text-destructive flex items-start gap-2 rounded-lg border px-4 py-3">
          <TriangleAlert className="mt-0.5 size-4 shrink-0" />
          <p className="text-label-13">{error}</p>
        </div>
      )}

      {/* ---- 🔴 触发入口：两次体检，语义不同 ---- */}
      <SectionCard
        title="触发体检"
        description="两种触发语义产出两份报告，报告头里带着这次的触发语义，报告历史里分得清。"
      >
        <div className="space-y-4">
          <div className="flex flex-wrap gap-2">
            {(['DRAFT_SAVE', 'PUBLISH_GATE'] as TriggerKind[]).map((kind) => (
              <Button
                key={kind}
                variant={trigger === kind ? 'default' : 'secondary'}
                size="sm"
                onClick={() => setTrigger(kind)}
                disabled={busy}
              >
                {triggerText(kind)}
              </Button>
            ))}
          </div>

          <div className="text-copy-13 text-muted-foreground space-y-1">
            <p>
              🔴 <b>本模块不执行拦截</b>：{caps.enforcementNote}
            </p>
            <p>🔴 {caps.boundaryNote}</p>
          </div>

          {drafts.length === 0 ? (
            <EmptyState
              icon={<ClipboardCheck className="size-8 text-muted-foreground" />}
              title={noDraft.title}
              description={noDraft.description}
            />
          ) : (
            <div className="space-y-3">
              <div className="text-copy-13 text-muted-foreground">
                选择内容来源（4.2.1.1 刊登编辑器的草稿）· 共 {draftTotal} 份
              </div>
              <div className="grid gap-2">
                {drafts.map((draft) => (
                  <button
                    key={draft.draftId}
                    type="button"
                    onClick={() => setSelectedDraftId(draft.draftId)}
                    disabled={busy}
                    className={`border-border rounded-lg border px-4 py-3 text-left transition-colors ${
                      selectedDraftId === draft.draftId
                        ? 'border-brand bg-brand-soft/40'
                        : 'bg-card hover:bg-muted/50'
                    }`}
                  >
                    <div className="text-copy-14 text-foreground font-medium">
                      {draft.title || '（未命名草稿）'}
                    </div>
                    <div className="text-copy-12 text-muted-foreground mt-1 flex flex-wrap items-center gap-2">
                      <span>{draft.platformCode}</span>
                      <span>·</span>
                      <span>{draft.marketplaceCode}</span>
                      <span>·</span>
                      <span>{draft.locale}</span>
                      <Badge tone="neutral">{draft.state}</Badge>
                    </div>
                  </button>
                ))}
              </div>

              <div className="flex items-center gap-3">
                <Button onClick={() => void triggerCheck()} disabled={busy || !selectedDraftId}>
                  {busy ? (
                    <Loader2 className="mr-2 size-4 animate-spin" />
                  ) : (
                    <ShieldAlert className="mr-2 size-4" />
                  )}
                  {triggerText(trigger)}
                </Button>
                <span className="text-copy-12 text-muted-foreground">
                  {selectedDraft ? `已选：${selectedDraft.title || '（未命名草稿）'}` : '请先选一份草稿'}
                </span>
              </div>

              {draftTotal > DRAFT_PAGE_SIZE && (
                <div className="flex items-center gap-3">
                  <Button
                    variant="ghost"
                    size="sm"
                    disabled={draftPage === 0 || busy}
                    onClick={() =>
                      void loadDrafts(draftPage - 1).then(() => setDraftPage((p) => Math.max(0, p - 1)))
                    }
                  >
                    上一页
                  </Button>
                  <span className="text-copy-12 text-muted-foreground">
                    第 {draftPage + 1} 页 · 共 {draftTotal} 份
                  </span>
                  <Button
                    variant="ghost"
                    size="sm"
                    disabled={(draftPage + 1) * DRAFT_PAGE_SIZE >= draftTotal || busy}
                    onClick={() =>
                      void loadDrafts(draftPage + 1).then(() => setDraftPage((p) => p + 1))
                    }
                  >
                    下一页
                  </Button>
                </div>
              )}
            </div>
          )}
        </div>
      </SectionCard>

      {/* ---- 🔴 分维度得分卡 ---- */}
      {report && (
        <SectionCard
          title="体检报告"
          description={`${report.header.triggerLabel} · ${report.header.platformCode} / ${report.header.marketplaceCode} · ${report.header.locale}`}
          extra={
            <Badge tone={verdictTone(report.header.verdict)}>
              {verdictLabel(report.header.verdict)}
            </Badge>
          }
        >
          <div className="space-y-4">
            <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
              <StatCard
                label="总体得分"
                // 🔴 原样显示后端算好的展示串：未知就是「未知」，不显示 0。
                value={report.header.overallScoreText}
                icon={<ShieldAlert className="size-4" />}
                polarity="neutral"
              />
              <StatCard
                label="可评分维度 / 维度总数"
                value={`${report.header.scoredDimensionCount} / ${report.header.totalDimensionCount}`}
                polarity="neutral"
              />
              <StatCard
                label="违规项"
                value={report.header.failedItemCount}
                polarity="negative"
                icon={<TriangleAlert className="size-4" />}
              />
              <StatCard
                label="量不到的检查项"
                value={report.header.unknownItemCount}
                polarity="neutral"
                icon={<CircleHelp className="size-4" />}
              />
            </div>

            {report.header.hasUnknownDimensions && (
              <div className="border-warning/40 bg-warning/5 text-warning flex items-start gap-2 rounded-lg border px-4 py-3">
                <CircleHelp className="mt-0.5 size-4 shrink-0" />
                <p className="text-label-13">
                  有 {report.header.unknownDimensionCount} 个维度<b>量不到</b>（得分是「未知」，不是 0 分，
                  也不计入总分分母）。每个维度的归属模块见下面的得分卡。
                </p>
              </div>
            )}

            {/* 🔴 三态之一：所有维度都量不到 ⇒ 说清「得分是未知而不是 0 分」 */}
            {allUnknown && (
              <EmptyState
                icon={<CircleHelp className="size-8 text-warning" />}
                title={emptyState('ALL_UNKNOWN').title}
                description={emptyState('ALL_UNKNOWN').description}
              />
            )}

            <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
              {report.dimensions.map((dimension) => (
                <DimensionCard key={dimension.dimensionCode} dimension={dimension} />
              ))}
            </div>

            <p className="text-copy-12 text-muted-foreground">{caps.scoreSemantics}</p>
            <p className="text-copy-12 text-muted-foreground">{caps.unknownSemantics}</p>
            <p className="text-copy-12 text-muted-foreground">{caps.verdictSemantics}</p>
          </div>
        </SectionCard>
      )}

      {/* ---- 🔴 问题清单 ---- */}
      {report && (
        <SectionCard
          title="问题清单"
          description="每条含维度、等级、命中位置、建议与所属模块。量不到的条目也在清单里（不是悄悄消失）。"
          extra={
            problems.length > 0 ? (
              <Badge tone="danger">违规 {problems.length} 条</Badge>
            ) : notMeasurable.length > 0 ? (
              <Badge tone="warning">量不到 {notMeasurable.length} 条</Badge>
            ) : undefined
          }
        >
          {report.findings.length === 0 ? (
            <EmptyState
              icon={<ClipboardCheck className="size-8 text-muted-foreground" />}
              title={emptyState('NO_FINDING').title}
              description={emptyState('NO_FINDING').description}
            />
          ) : (
            <div className="space-y-2">
              {report.findings.map((finding) => (
                <FindingRow key={finding.findingId} finding={finding} />
              ))}
            </div>
          )}
        </SectionCard>
      )}

      {/* ---- 整改建议 ---- */}
      {report && report.remediation.length > 0 && (
        <SectionCard title="整改建议" description="按维度归并的行动项，带优先级与归属模块。">
          <div className="space-y-2">
            {report.remediation.map((action) => (
              <div
                key={`${action.dimensionCode}-${action.priority}`}
                className="border-border flex items-start gap-3 rounded-lg border px-4 py-3"
              >
                <Badge
                  tone={
                    action.priority === 'HIGH'
                      ? 'danger'
                      : action.priority === 'MEDIUM'
                        ? 'warning'
                        : 'neutral'
                  }
                >
                  {action.priority}
                </Badge>
                <div className="min-w-0 flex-1">
                  <div className="text-copy-14 text-foreground">{action.action}</div>
                  <div className="text-copy-12 text-muted-foreground mt-1">
                    维度：{action.dimensionLabel} · 归属模块：{action.ownerModule}
                  </div>
                </div>
              </div>
            ))}
          </div>
        </SectionCard>
      )}

      {/* ---- 报告历史（真分页） ---- */}
      <SectionCard
        title="报告历史"
        description="每一次体检都留一份报告 —— 那是「当时凭什么这么判」的凭据，事后改不了。"
        extra={
          <Badge tone="neutral">
            <History className="mr-1 size-3" />
            共 {historyTotal} 份
          </Badge>
        }
      >
        {history.length === 0 ? (
          <EmptyState
            icon={<History className="size-8 text-muted-foreground" />}
            title="还没有体检记录"
            description="选一份内容草稿跑一次体检，这里就会出现那一次的报告。"
          />
        ) : (
          <div className="space-y-2">
            {history.map((row) => (
              <button
                key={row.reportId}
                type="button"
                onClick={() => void openReport(row.reportId)}
                disabled={busy}
                className="border-border bg-card hover:bg-muted/50 flex w-full items-center gap-3 rounded-lg border px-4 py-3 text-left"
              >
                <Badge tone={verdictTone(row.verdict)}>{verdictLabel(row.verdict)}</Badge>
                <div className="min-w-0 flex-1">
                  <div className="text-copy-14 text-foreground truncate">
                    {row.draftTitle || '（未命名草稿）'}
                  </div>
                  <div className="text-copy-12 text-muted-foreground mt-1 flex flex-wrap items-center gap-2">
                    <Badge tone="info">{row.triggerLabel}</Badge>
                    <span>{row.platformCode}</span>
                    <span>·</span>
                    <span>{row.marketplaceCode}</span>
                    <span>·</span>
                    <span>{row.checkedAt}</span>
                  </div>
                </div>
                <div className="text-right">
                  <div className="text-copy-14 text-foreground">{row.overallScoreText}</div>
                  <div className="text-copy-12 text-muted-foreground">
                    违规 {row.failedItemCount} · 未知 {row.unknownItemCount}
                  </div>
                </div>
              </button>
            ))}
            <div className="flex items-center gap-3">
              <Button
                variant="ghost"
                size="sm"
                disabled={historyPage === 0 || busy}
                onClick={() =>
                  void loadHistory(historyPage - 1).then(() => setHistoryPage((p) => Math.max(0, p - 1)))
                }
              >
                上一页
              </Button>
              <span className="text-copy-12 text-muted-foreground">
                第 {historyPage + 1} 页 · 共 {historyTotal} 份
              </span>
              <Button
                variant="ghost"
                size="sm"
                disabled={(historyPage + 1) * HISTORY_PAGE_SIZE >= historyTotal || busy}
                onClick={() =>
                  void loadHistory(historyPage + 1).then(() => setHistoryPage((p) => p + 1))
                }
              >
                下一页
              </Button>
            </div>
          </div>
        )}
      </SectionCard>

      {/* ---- 能力声明：哪些维度量得到、量不到归谁 ---- */}
      <SectionCard title="能力声明" description="逐维度报出「量得到 / 量不到」，量不到的归哪个模块。">
        <div className="space-y-3">
          {caps.dimensions.map((dimension) => (
            <div key={dimension.code} className="border-border rounded-lg border px-4 py-3">
              <div className="flex flex-wrap items-center gap-2">
                <span className="text-copy-14 text-foreground">{dimension.label}</span>
                <Badge tone={dimension.available ? 'success' : 'warning'}>
                  {dimension.available ? '量得到' : '量不到'}
                </Badge>
                {!dimension.available && dimension.ownerModule && (
                  <Badge tone="neutral">归 {dimension.ownerModule}</Badge>
                )}
              </div>
              {!dimension.available && dimension.impact && (
                <p className="text-copy-12 text-muted-foreground mt-2">{dimension.impact}</p>
              )}
            </div>
          ))}
          <p className="text-copy-12 text-muted-foreground">{caps.upstreamNote}</p>
          <p className="text-copy-12 text-muted-foreground">{caps.reportNote}</p>
        </div>
      </SectionCard>
    </div>
  );
}

/** 🔴 一个维度的得分卡：量不到时显示「未知」+ 原因 + 归属模块。 */
function DimensionCard({ dimension }: { dimension: CompliancePrecheckDimensionScore }) {
  const unknown = dimension.state === 'UNKNOWN' || dimension.score === null;
  return (
    <div
      className={`rounded-lg border px-4 py-3 ${
        unknown ? 'border-warning/40 bg-warning/5' : 'border-border bg-card'
      }`}
    >
      <div className="flex items-start justify-between gap-2">
        <span className="text-copy-14 text-foreground font-medium">{dimension.dimensionLabel}</span>
        <Badge tone={unknown ? 'warning' : 'success'}>{unknown ? '未知' : '已评分'}</Badge>
      </div>
      <div className="text-h-24 text-foreground mt-2">
        {dimensionScoreText(dimension)}
        {!unknown && (
          <span className="text-copy-12 text-muted-foreground ml-2">
            权重 {(dimension.weight * 100).toFixed(0)}%
          </span>
        )}
      </div>
      {unknown ? (
        <div className="text-copy-12 text-muted-foreground mt-2 space-y-1">
          <p>这一维度<b>量不到</b>，不计入总分分母（未知不是 0 分）。</p>
          {dimension.ownerModule && <p>归属模块：{dimension.ownerModule}</p>}
          {dimension.unavailableReason && <p>{dimension.unavailableReason}</p>}
        </div>
      ) : (
        <div className="text-copy-12 text-muted-foreground mt-2">
          检查项 {dimension.checkItemCount} · 通过 {dimension.passedItemCount} · 违规{' '}
          {dimension.failedItemCount} · 未知 {dimension.unknownItemCount}
        </div>
      )}
    </div>
  );
}

/** 🔴 问题清单里的一条：维度 / 等级 / 命中位置 / 建议 / 所属模块。 */
function FindingRow({ finding }: { finding: CompliancePrecheckFinding }) {
  const unknown = finding.state === 'UNKNOWN';
  return (
    <div
      className={`rounded-lg border px-4 py-3 ${
        unknown ? 'border-warning/40 bg-warning/5' : 'border-border bg-card'
      }`}
    >
      <div className="flex flex-wrap items-center gap-2">
        <Badge tone={unknown ? 'warning' : finding.severity === 'BLOCKING' ? 'danger' : 'info'}>
          {unknown ? '量不到' : severityLabel(finding.severity)}
        </Badge>
        <span className="text-copy-13 text-foreground">{finding.dimensionLabel}</span>
        {finding.location && (
          <span className="text-copy-12 text-muted-foreground">命中位置：{finding.location}</span>
        )}
        <span className="text-copy-12 text-muted-foreground">所属模块：{finding.ownerModule}</span>
      </div>
      <p className="text-copy-13 text-foreground mt-2">{finding.message}</p>
      {finding.suggestion && (
        <p className="text-copy-12 text-muted-foreground mt-1">建议：{finding.suggestion}</p>
      )}
    </div>
  );
}
