'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import { Badge, Button, EmptyState, SectionCard, StatCard } from '@starcon/ui';
import {
  AlertTriangle,
  Ban,
  Check,
  Download,
  FileSpreadsheet,
  Loader2,
  RefreshCw,
} from 'lucide-react';
import type {
  ImportJobPage,
  ImportJobRow,
  ImportValidationExportRow,
  ImportValidationIssuePage,
  ImportValidationSummary,
} from '../../../../../packages/api-contracts/src/catalog';
import { control } from '../common/fields';
import { useIdentity } from '../iam/identity-shell';
import { fetchJobs, statusLabel } from './import-model';
import {
  CATEGORIES,
  EXPORT_KINDS,
  SEVERITY_FILTERS,
  type ExportKind,
  blockingNotice,
  categoryIssueCount,
  createExport,
  distinctFieldCount,
  errorText,
  exportFileUrl,
  fetchExports,
  fetchIssues,
  fetchReport,
  formatTime,
  groupIssuesByRow,
  importablePercent,
  kindHint,
  problemColumns,
  regenerateReport,
  reportJobId,
  severityLabel,
  severityTone,
  staleNotice,
} from './validation-report-model';

const PAGE_SIZE = 50;

/** 只有跑过预校验的任务才有校验报告，列表里只给这些，省得用户挑到一个空报告。 */
function hasConclusion(job: ImportJobRow): boolean {
  return ['PREVALIDATED', 'RUNNING', 'SUCCEEDED', 'PARTIAL', 'FAILED'].includes(job.status);
}

/**
 * 3.8.1.2 校验报告工作区。
 *
 * <p>页面上必须同时说清三件事，少一件都会让运营做错决定：
 *
 * <ol>
 *   <li><b>哪些行永远不会进库。</b>不是「有几条错误」，而是「有几行不落库」——
 *       蓝图那半句是「不允许尽力导入产生半截数据」，所以顶部必须能一眼看到
 *       「必须修正 N 行 / 可落库 M 行」。</li>
 *   <li><b>报告是不是当前结论。</b>报告是某一轮预校验的快照，上游预校验可重跑，
 *       过期时必须显式提示并引导重新生成，而不是把上一轮的数字继续用。</li>
 *   <li><b>怎么把文件改对。</b>导出分成「带标注的错误文件」与「修正版模板」两种，
 *       两种的含义写在按钮下面 —— 运营不该靠猜。</li>
 * </ol>
 */
export function ValidationReportWorkspace() {
  const { session } = useIdentity();
  const writable = session.permissions.includes('catalog.spu.update');

  const [jobs, setJobs] = useState<ImportJobRow[]>([]);
  const [jobId, setJobId] = useState('');
  const [jobsLoading, setJobsLoading] = useState(true);
  const [jobsError, setJobsError] = useState('');

  const [report, setReport] = useState<ImportValidationSummary | null>(null);
  const [reportLoading, setReportLoading] = useState(false);
  const [reportError, setReportError] = useState('');

  const [severity, setSeverity] = useState('ALL');
  const [category, setCategory] = useState('');
  const [rowNo, setRowNo] = useState('');
  const [page, setPage] = useState(0);
  const [issues, setIssues] = useState<ImportValidationIssuePage | null>(null);
  const [issuesLoading, setIssuesLoading] = useState(false);
  const [issuesError, setIssuesError] = useState('');

  const [exportKind, setExportKind] = useState<ExportKind>('ANNOTATED_ERRORS');
  const [exportFilter, setExportFilter] = useState('ALL');
  const [exports, setExports] = useState<ImportValidationExportRow[]>([]);
  const [exportBusy, setExportBusy] = useState(false);
  const [exportError, setExportError] = useState('');

  // 从 URL 的 ?jobId= 进页面时直接落到那个任务（导入向导里点「查看校验报告」带过来）
  useEffect(() => {
    const initial = reportJobId(window.location.search);
    if (initial) setJobId(initial);
  }, []);

  useEffect(() => {
    let alive = true;
    setJobsLoading(true);
    setJobsError('');
    fetchJobs(null, null, 0, 50)
      .then((data: ImportJobPage) => {
        if (!alive) return;
        setJobs(data.items.filter(hasConclusion));
      })
      .catch((e: unknown) => alive && setJobsError(errorText(e)))
      .finally(() => alive && setJobsLoading(false));
    return () => {
      alive = false;
    };
  }, []);

  const loadReport = useCallback(async (target: string, force = false) => {
    if (!target) return;
    setReportLoading(true);
    setReportError('');
    try {
      if (force) await regenerateReport(target);
      setReport(await fetchReport(target));
    } catch (e) {
      setReportError(errorText(e));
      setReport(null);
    } finally {
      setReportLoading(false);
    }
  }, []);

  const loadIssues = useCallback(
    async (target: string) => {
      if (!target) return;
      setIssuesLoading(true);
      setIssuesError('');
      try {
        setIssues(
          await fetchIssues(
            target,
            severity,
            category || null,
            rowNo ? Number(rowNo) : null,
            page,
            PAGE_SIZE,
          ),
        );
      } catch (e) {
        setIssuesError(errorText(e));
        setIssues(null);
      } finally {
        setIssuesLoading(false);
      }
    },
    [severity, category, rowNo, page],
  );

  const loadExports = useCallback(async (target: string) => {
    if (!target) return;
    try {
      setExports((await fetchExports(target)).items);
    } catch {
      // 导出历史读不到不该挡住报告主体：它只是「导过什么」的记录
      setExports([]);
    }
  }, []);

  useEffect(() => {
    if (!jobId) return;
    void loadReport(jobId);
    void loadIssues(jobId);
    void loadExports(jobId);
  }, [jobId, loadReport, loadIssues, loadExports]);

  const grouped = useMemo(() => groupIssuesByRow(issues?.items ?? []), [issues]);
  const summary = report?.report;
  const percent = summary ? importablePercent(summary) : 0;
  const totalPages = issues ? Math.max(1, Math.ceil(issues.total / issues.size)) : 1;

  async function runExport() {
    if (!jobId) return;
    setExportBusy(true);
    setExportError('');
    try {
      await createExport(jobId, { exportKind, severityFilter: exportFilter });
      await loadExports(jobId);
    } catch (e) {
      setExportError(errorText(e));
    } finally {
      setExportBusy(false);
    }
  }

  return (
    <div className="space-y-4">
      {/* ------------------------------------------------ 选任务 */}
      <SectionCard
        title="选一个导入任务"
        description="只有跑过预校验的任务才有校验报告（预校验结果才是报告的输入）。"
      >
        {jobsLoading ? (
          <Loading label="正在读导入任务…" />
        ) : jobsError ? (
          <ErrorBox message={jobsError} />
        ) : jobs.length === 0 ? (
          <EmptyState
            icon={<FileSpreadsheet className="size-7" />}
            title="还没有跑过预校验的导入任务"
            description="先到「导入向导」上传文件并完成预校验，这里才有校验报告可读。"
          />
        ) : (
          <div className="flex flex-wrap items-center gap-2">
            <select
              className={`${control} max-w-[28rem]`}
              value={jobId}
              aria-label="选择导入任务"
              onChange={(e) => setJobId(e.target.value)}
            >
              <option value="">请选择任务…</option>
              {jobs.map((j) => (
                <option key={j.id} value={j.id}>
                  {j.fileName} · {j.targetEntity} · {statusLabel(j.status)} · {j.rowCount} 行 ·{' '}
                  {formatTime(j.createdAt)}
                </option>
              ))}
            </select>
            {jobId && (
              <Button variant="outline" size="sm" onClick={() => void loadReport(jobId, true)} disabled={!writable}>
                <RefreshCw className={reportLoading ? 'animate-spin' : ''} />
                重新生成报告
              </Button>
            )}
          </div>
        )}
        {!writable && jobId && (
          <p className="text-copy-13 text-muted-foreground mt-2">
            你只有读权限：可以查看报告与已导出的文件，但不能重新生成报告或发起新的导出。
          </p>
        )}
      </SectionCard>

      {!jobId ? null : reportLoading && !report ? (
        <SectionCard>
          <Loading label="正在生成并读取校验报告…" />
        </SectionCard>
      ) : reportError ? (
        <SectionCard>
          <ErrorBox message={reportError} />
        </SectionCard>
      ) : !report || !summary ? null : (
        <>
          {/* ------------------------------------------------ 概览 */}
          <SectionCard
            title="校验结论"
            description={`报告基于 ${formatTime(summary.sourcePrevalidatedAt)} 的预校验结果生成（任务当时状态：${summary.sourceStatusLabel}）`}
          >
            {summary.stale && (
              <div className="bg-warning-soft text-warning mb-4 flex items-start gap-2 rounded-md p-3">
                <AlertTriangle className="mt-0.5 size-4 shrink-0" />
                <div className="space-y-2 text-copy-13">
                  <p>{staleNotice(summary)}</p>
                  <Button
                    size="sm"
                    variant="outline"
                    disabled={!writable || reportLoading}
                    onClick={() => void loadReport(jobId, true)}
                  >
                    <RefreshCw className={reportLoading ? 'animate-spin' : ''} />
                    重新生成报告
                  </Button>
                </div>
              </div>
            )}

            <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
              <StatCard label="文件总行数" value={summary.totalRowCount} icon={<FileSpreadsheet className="size-4" />} />
              <StatCard
                label="必须修正（不落库）"
                value={summary.blockingRowCount}
                polarity="negative"
                icon={<Ban className="size-4" />}
              />
              <StatCard label="可跳过（照常落库）" value={summary.skippableRowCount} polarity="neutral" />
              <StatCard label="校验通过" value={summary.cleanRowCount} polarity="positive" icon={<Check className="size-4" />} />
            </div>

            {/* 进度条的口径是行：一条错误落在 1000 行文件里就是 1 行，不是三分之一 */}
            <div className="mt-4 space-y-1.5">
              <div className="text-copy-13 flex items-center justify-between">
                <span>可落库行占比</span>
                <span className="text-foreground font-medium">{percent}%</span>
              </div>
              <div className="bg-muted h-2 w-full overflow-hidden rounded-full">
                <div
                  className={summary.blockingRowCount > 0 ? 'bg-warning h-full' : 'bg-success h-full'}
                  style={{ width: `${percent}%` }}
                />
              </div>
              <p className="text-copy-13 text-muted-foreground">{blockingNotice(summary)}</p>
            </div>
          </SectionCard>

          {/* ------------------------------------------------ 分类 */}
          <SectionCard
            title="按问题类别"
            description="蓝图点名的五类都在这儿（含 0 条的类），运营按类批量改比按错误码快。"
          >
            <div className="grid gap-4 lg:grid-cols-2">
              <ul className="space-y-1.5">
                {report.categories.map((c) => (
                  <li key={c.category} className="flex items-center justify-between gap-3 text-copy-13">
                    <span className="flex items-center gap-2">
                      <Badge tone={categoryIssueCount(c) === 0 ? 'neutral' : c.errorCount > 0 ? 'danger' : 'warning'}>
                        {c.label}
                      </Badge>
                      {c.category}
                    </span>
                    <span className="text-muted-foreground shrink-0">
                      {c.rowCount} 行 · {c.errorCount} 条必须修正 · {c.warningCount} 条可跳过
                    </span>
                  </li>
                ))}
              </ul>
              <div className="space-y-2">
                <p className="text-copy-13 text-muted-foreground">错得最多的列（按文件里的列头）：</p>
                {problemColumns(report.columns).length === 0 ? (
                  <p className="text-copy-13 text-muted-foreground">没有任何一列出错。</p>
                ) : (
                  <ul className="space-y-1.5">
                    {problemColumns(report.columns).map((c) => (
                      <li key={c.fieldKey} className="flex items-center justify-between gap-3 text-copy-13">
                        <span className="text-foreground font-medium">{c.fieldLabel}</span>
                        <span className="text-muted-foreground shrink-0">
                          {c.rowCount} 行 · {c.errorCount + c.warningCount} 条
                        </span>
                      </li>
                    ))}
                  </ul>
                )}
              </div>
            </div>
          </SectionCard>

          {/* ------------------------------------------------ 错误明细 */}
          <SectionCard
            title={`错误明细（必须修正 ${issues?.errorCount ?? 0} · 可跳过 ${issues?.warningCount ?? 0}）`}
            description="逐行逐列列出，带该格在文件里的原始值。表头是第 1 行，所以数据从第 2 行开始。"
          >
            <div className="mb-3 flex flex-wrap items-center gap-2">
              <select
                className={`${control} max-w-[12rem]`}
                value={severity}
                aria-label="按级别筛选"
                onChange={(e) => {
                  setSeverity(e.target.value);
                  setPage(0);
                }}
              >
                {SEVERITY_FILTERS.map((f) => (
                  <option key={f.value} value={f.value}>
                    {f.label}
                  </option>
                ))}
              </select>
              <select
                className={`${control} max-w-[12rem]`}
                value={category}
                aria-label="按类别筛选"
                onChange={(e) => {
                  setCategory(e.target.value);
                  setPage(0);
                }}
              >
                <option value="">全部类别</option>
                {CATEGORIES.map((c) => (
                  <option key={c.value} value={c.value}>
                    {c.label}
                  </option>
                ))}
              </select>
              <input
                className={`${control} max-w-[10rem]`}
                value={rowNo}
                inputMode="numeric"
                placeholder="定位到第几行"
                aria-label="定位到第几行"
                onChange={(e) => {
                  setRowNo(e.target.value.replace(/\D/g, ''));
                  setPage(0);
                }}
              />
              <Button variant="ghost" size="sm" onClick={() => void loadIssues(jobId)}>
                刷新
              </Button>
            </div>

            {issuesLoading && !issues ? (
              <Loading label="正在读错误明细…" />
            ) : issuesError ? (
              <ErrorBox message={issuesError} />
            ) : !issues || issues.total === 0 ? (
              <EmptyState
                icon={<Check className="size-7" />}
                title="当前筛选下没有错误"
                description={
                  severity === 'ERROR'
                    ? '这一批没有必须修正的行，全部可落库。'
                    : '这个筛选条件下没有问题，换个类别或级别看看。'
                }
              />
            ) : (
              <>
                <ul className="border-border divide-border divide-y rounded-md border">
                  {grouped.map((g) => (
                    <li key={g.rowNo} className="space-y-2 px-3 py-2.5">
                      <div className="flex flex-wrap items-center gap-2">
                        <span className="font-medium">第 {g.rowNo} 行</span>
                        <Badge tone={g.blocking ? 'danger' : 'warning'}>
                          {g.blocking ? '必须修正 · 不落库' : '可跳过 · 照常落库'}
                        </Badge>
                        <Badge tone="neutral">要改 {distinctFieldCount(g.items)} 处</Badge>
                      </div>
                      <ul className="space-y-1">
                        {g.items.map((it, i) => (
                          <li key={`${it.id}-${i}`} className="flex flex-wrap items-baseline gap-2 text-xs">
                            <Badge tone={severityTone(it.severity)}>{severityLabel(it.severity)}</Badge>
                            <code className="text-muted-foreground">{it.fieldLabel || '整行'}</code>
                            <Badge tone="neutral">{it.categoryLabel}</Badge>
                            <span className="text-muted-foreground">{it.message}</span>
                            <span className="text-muted-foreground">
                              原值：
                              <code className="text-foreground">{it.rawValue === '' ? '（空）' : it.rawValue}</code>
                            </span>
                          </li>
                        ))}
                      </ul>
                    </li>
                  ))}
                </ul>
                <div className="text-copy-13 text-muted-foreground mt-2 flex items-center justify-between">
                  <span>
                    第 {issues.page + 1} / {totalPages} 页，共 {issues.total} 条
                  </span>
                  <span className="flex items-center gap-2">
                    <Button
                      variant="outline"
                      size="sm"
                      disabled={issues.page <= 0}
                      onClick={() => setPage((p) => Math.max(0, p - 1))}
                    >
                      上一页
                    </Button>
                    <Button
                      variant="outline"
                      size="sm"
                      disabled={issues.page + 1 >= totalPages}
                      onClick={() => setPage((p) => p + 1)}
                    >
                      下一页
                    </Button>
                  </span>
                </div>
              </>
            )}
          </SectionCard>

          {/* ------------------------------------------------ 导出 */}
          <SectionCard
            title="导出带标注的错误文件"
            description="导出的 CSV 带 UTF-8 BOM，Excel 直接打开不乱码；标注列追加在数据列之后，重新上传时会被忽略，所以改完可以原样传回。"
          >
            <div className="flex flex-wrap items-end gap-3">
              <label className="space-y-1">
                <span className="text-copy-13 text-muted-foreground">导出哪种</span>
                <select
                  className={`${control} max-w-[16rem]`}
                  value={exportKind}
                  aria-label="导出种类"
                  onChange={(e) => setExportKind(e.target.value as ExportKind)}
                >
                  {EXPORT_KINDS.map((k) => (
                    <option key={k.value} value={k.value}>
                      {k.label}
                    </option>
                  ))}
                </select>
              </label>
              <label className="space-y-1">
                <span className="text-copy-13 text-muted-foreground">只看哪一档问题</span>
                <select
                  className={`${control} max-w-[12rem]`}
                  value={exportFilter}
                  aria-label="导出的严重级别筛选"
                  onChange={(e) => setExportFilter(e.target.value)}
                >
                  {SEVERITY_FILTERS.map((f) => (
                    <option key={f.value} value={f.value}>
                      {f.label}
                    </option>
                  ))}
                </select>
              </label>
              <Button onClick={() => void runExport()} disabled={!writable || exportBusy || summary.stale}>
                {exportBusy ? <Loader2 className="animate-spin" /> : <Download />}
                生成导出文件
              </Button>
            </div>
            <p className="text-copy-13 text-muted-foreground mt-2">{kindHint(exportKind)}</p>
            {summary.stale && (
              <p className="text-copy-13 text-warning mt-2">
                报告已过期，先重新生成再导出 —— 否则导出的标注对应的是上一轮结论。
              </p>
            )}
            {exportError && (
              <p className="text-copy-13 text-destructive mt-2">{exportError}</p>
            )}

            <div className="mt-4 space-y-2">
              {exports.length === 0 ? (
                <EmptyState
                  icon={<Download className="size-7" />}
                  title="还没有导出过"
                  description="生成后会在这里留下记录：基于哪一轮结论、导了多少行、内容哈希是多少。"
                />
              ) : (
                <ul className="border-border divide-border divide-y rounded-md border">
                  {exports.map((e) => (
                    <li key={e.id} className="flex flex-wrap items-center justify-between gap-2 px-3 py-2.5">
                      <div className="space-y-1">
                        <div className="flex flex-wrap items-center gap-2">
                          <span className="font-medium">{e.fileName}</span>
                          <Badge tone="neutral">{e.exportKindLabel}</Badge>
                          <Badge tone="neutral">
                            {e.severityFilter === 'ALL'
                              ? '全部问题'
                              : severityLabel(e.severityFilter)}
                          </Badge>
                          {e.stale && <Badge tone="warning">基于已过期的报告</Badge>}
                        </div>
                        <p className="text-copy-13 text-muted-foreground">
                          {e.rowCount} 行 · {e.issueCount} 条问题 · {e.byteSize} 字节 ·{' '}
                          {formatTime(e.createdAt)} · sha256 {e.contentSha256.slice(0, 12)}…
                        </p>
                      </div>
                      <Button asChild variant="outline" size="sm">
                        <a href={exportFileUrl(jobId, e.id)} download={e.fileName}>
                          <Download />
                          下载
                        </a>
                      </Button>
                    </li>
                  ))}
                </ul>
              )}
            </div>
          </SectionCard>
        </>
      )}
    </div>
  );
}

function Loading({ label }: { label: string }) {
  return (
    <div className="text-copy-13 text-muted-foreground flex items-center gap-2 py-6">
      <Loader2 className="size-4 animate-spin" />
      {label}
    </div>
  );
}

function ErrorBox({ message }: { message: string }) {
  return (
    <div className="bg-destructive-soft text-destructive flex items-start gap-2 rounded-md p-3 text-copy-13">
      <AlertTriangle className="mt-0.5 size-4 shrink-0" />
      <span>{message}</span>
    </div>
  );
}