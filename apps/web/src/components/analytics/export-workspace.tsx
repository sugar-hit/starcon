'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import { Badge, Button, EmptyState, SectionCard } from '@starcon/ui';
import { Download, FileSpreadsheet, RefreshCw, TriangleAlert } from 'lucide-react';
import { api } from '@/lib/api';
import {
  deliveryEmptyText,
  deliveryNote,
  deliveryStatusLabel,
  dispatchResultText,
  downloadState,
  downloadStateText,
  exportByteSizeText,
  exportColumnKindLabel,
  exportColumnNote,
  exportDownloadUrl,
  exportFormatLabel,
  exportJobSummary,
  exportSelectionText,
  exportStatusLabel,
  isRunningStatus,
  jobListEmptyText,
  jobListState,
} from './export-model';
import type {
  ExportSubscriptionColumnInfo,
  ExportSubscriptionDeliveryView,
  ExportSubscriptionDispatchResult,
  ExportSubscriptionJobPage,
  ExportSubscriptionSourcesResponse,
} from './export-model';
import type { CustomReportPage, CustomReportSummary } from '../../../../../packages/api-contracts/src/index';

/**
 * 13.6.2.1「导出与订阅」工作区。
 *
 * <p>蓝图输出：「文件下载链接；订阅投递记录。」
 *
 * <p><b>数据来自 reporting 服务（13 卷的唯一归属服务）</b>：
 *
 * <ul>
 *   <li>{@code GET reporting/custom-report/export/sources} —— 导出能力声明（格式 / 阈值 / 可导出列）；</li>
 *   <li>{@code POST reporting/custom-report/export/jobs} —— 发起异步导出；</li>
 *   <li>{@code GET reporting/custom-report/export/jobs} —— 任务清单（真分页 + 真搜索 + 真筛选）；</li>
 *   <li>{@code GET reporting/custom-report/export/jobs/{id}/file} —— 下载文件（带有效期）；</li>
 *   <li>{@code GET reporting/custom-report/deliveries} —— 投递台账（13.6.1.1 的队列）；</li>
 *   <li>{@code GET reporting/custom-report/deliveries/{id}} —— 单条投递详情；</li>
 *   <li>{@code POST reporting/custom-report/deliveries/dispatch} —— 真正执行一次投递。</li>
 * </ul>
 *
 * <p><b>🔴 页面必须做到的四件事</b>：
 *
 * <ol>
 *   <li><b>「受理」不是「完成」</b>：发起后显示「排队中」，行数与文件要轮询任务详情才拿得到。</li>
 *   <li><b>「已投递」不是「排上队了」</b>：投递状态是**真的投递结果**，失败原因原样显示。</li>
 *   <li><b>过期就是过期</b>：过了有效期显示明确的过期态，不给点了就报错的下载按钮。</li>
 *   <li><b>前端不做业务算术</b>：金额、行数、字节数全部来自服务端。</li>
 * </ol>
 */

/** 轮询间隔（毫秒）：只在还有任务在跑的时候才轮询。 */
const POLL_INTERVAL_MS = 2000;

/** 投递台账的类型（13.6.1.1 的队列页）。 */
type DeliveryLedger = {
  rows: {
    deliveryId: string;
    reportCode: string;
    scheduledFor: string;
    status: string;
    attemptCount: number;
    lastError: string;
  }[];
  total: number;
  page: number;
  size: number;
  statusFilter: string;
};

export function ExportWorkspace() {
  const [sources, setSources] = useState<ExportSubscriptionSourcesResponse | null>(null);
  const [reports, setReports] = useState<CustomReportSummary[]>([]);
  const [reportId, setReportId] = useState('');
  const [columns, setColumns] = useState<ExportSubscriptionColumnInfo[]>([]);
  const [format, setFormat] = useState('CSV');
  const [selectedFields, setSelectedFields] = useState<string[]>([]);
  const [formError, setFormError] = useState('');
  const [submitting, setSubmitting] = useState(false);

  const [jobs, setJobs] = useState<ExportSubscriptionJobPage | null>(null);
  const [jobStatus, setJobStatus] = useState('');
  const [jobFormat, setJobFormat] = useState('');
  const [jobKeyword, setJobKeyword] = useState('');
  const [jobPage, setJobPage] = useState(0);
  const [jobError, setJobError] = useState('');
  const [loading, setLoading] = useState(true);

  const [ledger, setLedger] = useState<DeliveryLedger | null>(null);
  const [deliveryStatus, setDeliveryStatus] = useState('');
  const [deliveryPage, setDeliveryPage] = useState(0);
  const [dispatch, setDispatch] = useState<ExportSubscriptionDispatchResult | null>(null);
  const [deliveryDetail, setDeliveryDetail] = useState<ExportSubscriptionDeliveryView | null>(null);
  const [deliveryError, setDeliveryError] = useState('');
  const [dispatching, setDispatching] = useState(false);

  // ── 能力声明与报表清单 ──────────────────────────────────────────────────
  const loadSources = useCallback(async () => {
    setLoading(true);
    setJobError('');
    try {
      const [capabilities, reportPage] = await Promise.all([
        api<ExportSubscriptionSourcesResponse>('reporting/custom-report/export/sources'),
        api<CustomReportPage>('reporting/custom-report/reports?page=0&size=100'),
      ]);
      setSources(capabilities);
      setReports(reportPage.rows);
    } catch (e) {
      setJobError(e instanceof Error ? e.message : '读取导出能力失败，请重试');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void loadSources();
  }, [loadSources]);

  // 选中报表后拉它自己的可导出列。
  useEffect(() => {
    if (!reportId) {
      setColumns([]);
      setSelectedFields([]);
      return;
    }
    let cancelled = false;
    void (async () => {
      try {
        const capabilities = await api<ExportSubscriptionSourcesResponse>(
          `reporting/custom-report/export/sources?reportId=${encodeURIComponent(reportId)}`,
        );
        if (cancelled) return;
        setColumns(capabilities.columns);
        // 默认全选（用户可以取消勾选）。
        setSelectedFields(capabilities.columns.map((column) => column.key));
      } catch (e) {
        if (!cancelled) {
          setColumns([]);
          setSelectedFields([]);
          setFormError(e instanceof Error ? e.message : '读取可导出列失败，请重试');
        }
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [reportId]);

  // ── 任务清单 ───────────────────────────────────────────────────────────
  const loadJobs = useCallback(async () => {
    setJobError('');
    try {
      const query = new URLSearchParams();
      if (jobStatus) query.set('status', jobStatus);
      if (jobFormat) query.set('format', jobFormat);
      if (jobKeyword.trim()) query.set('keyword', jobKeyword.trim());
      query.set('page', String(jobPage));
      query.set('size', '20');
      setJobs(
        await api<ExportSubscriptionJobPage>(
          `reporting/custom-report/export/jobs?${query.toString()}`,
        ),
      );
    } catch (e) {
      setJobError(e instanceof Error ? e.message : '读取导出任务失败，请重试');
    }
  }, [jobPage, jobStatus, jobFormat, jobKeyword]);

  useEffect(() => {
    void loadJobs();
  }, [loadJobs]);

  // 🔴 **只在还有任务在跑的时候轮询**：没有在跑的任务就别占着连接。
  const running = useMemo(
    () => (jobs?.rows ?? []).some((job) => isRunningStatus(job.status)),
    [jobs],
  );
  useEffect(() => {
    if (!running) return;
    const timer = setInterval(() => {
      void loadJobs();
    }, POLL_INTERVAL_MS);
    return () => clearInterval(timer);
  }, [running, loadJobs]);

  // ── 发起导出 ───────────────────────────────────────────────────────────
  const submit = useCallback(async () => {
    setFormError('');
    setSubmitting(true);
    try {
      await api<{ jobId: string }>('reporting/custom-report/export/jobs', 'POST', {
        reportId,
        format,
        fields: selectedFields,
      });
      setJobPage(0);
      await loadJobs();
    } catch (e) {
      setFormError(e instanceof Error ? e.message : '发起导出失败，请重试');
    } finally {
      setSubmitting(false);
    }
  }, [reportId, format, selectedFields, loadJobs]);

  const cancelJob = useCallback(
    async (jobId: string) => {
      setJobError('');
      try {
        await api<void>(
          `reporting/custom-report/export/jobs/${jobId}/cancel`,
          'POST',
          {},
        );
        await loadJobs();
      } catch (e) {
        setJobError(e instanceof Error ? e.message : '作废导出失败，请重试');
      }
    },
    [loadJobs],
  );

  // ── 投递 ───────────────────────────────────────────────────────────────
  const loadLedger = useCallback(async () => {
    setDeliveryError('');
    try {
      const query = new URLSearchParams();
      if (deliveryStatus) query.set('status', deliveryStatus);
      query.set('page', String(deliveryPage));
      query.set('size', '20');
      setLedger(
        await api<DeliveryLedger>(`reporting/custom-report/deliveries?${query.toString()}`),
      );
    } catch (e) {
      setDeliveryError(e instanceof Error ? e.message : '读取投递台账失败，请重试');
    }
  }, [deliveryPage, deliveryStatus]);

  useEffect(() => {
    void loadLedger();
  }, [loadLedger]);

  const runDispatch = useCallback(async () => {
    setDeliveryError('');
    setDispatching(true);
    try {
      const result = await api<ExportSubscriptionDispatchResult>(
        'reporting/custom-report/deliveries/dispatch',
        'POST',
        { limit: 20 },
      );
      setDispatch(result);
      await loadLedger();
    } catch (e) {
      setDeliveryError(e instanceof Error ? e.message : '触发投递失败，请重试');
    } finally {
      setDispatching(false);
    }
  }, [loadLedger]);

  const openDelivery = useCallback(async (deliveryId: string) => {
    setDeliveryError('');
    try {
      setDeliveryDetail(
        await api<ExportSubscriptionDeliveryView>(
          `reporting/custom-report/deliveries/${deliveryId}`,
        ),
      );
    } catch (e) {
      setDeliveryError(e instanceof Error ? e.message : '读取投递详情失败，请重试');
    }
  }, []);

  // ── 渲染辅助 ───────────────────────────────────────────────────────────
  const listState = jobListState(jobs, jobError);
  const jobTone = (status: string) => {
    switch (status) {
      case 'SUCCEEDED':
        return 'success' as const;
      case 'FAILED':
        return 'danger' as const;
      case 'EXPIRED':
      case 'CANCELLED':
        return 'warning' as const;
      default:
        return 'info' as const;
    }
  };
  const deliveryTone = (status: string) => {
    switch (status) {
      case 'DELIVERED':
        return 'success' as const;
      case 'FAILED':
        return 'danger' as const;
      case 'PENDING':
        return 'info' as const;
      default:
        return 'neutral' as const;
    }
  };

  return (
    <div className="space-y-4">
      {/* ── 能力声明横幅 ─────────────────────────────────────────────── */}
      <div className="border-border bg-muted/40 flex flex-wrap items-center justify-between gap-3 rounded-lg border px-4 py-3">
        <div className="text-copy-13 text-foreground">
          <span className="font-medium">导出与订阅：大导出走异步任务并给出有效期</span>
          <span className="text-muted-foreground ml-2">
            导出内容与报表页面上看到的完全一致（口径唯一，不开放任意 SQL）。
          </span>
        </div>
        <div className="text-copy-13 flex flex-wrap items-center gap-2">
          <Badge tone="success">导出：可用</Badge>
          <Badge tone="success">邮件 / IM 投递：可用</Badge>
          <Button variant="outline" size="sm" disabled={loading} onClick={() => void loadSources()}>
            <RefreshCw className="mr-1.5 h-3.5 w-3.5" />
            刷新
          </Button>
        </div>
      </div>

      {sources && (
        <SectionCard
          title="导出能力声明"
          description="格式、可导出列与阈值全部由后端声明，前端不硬编码一份。"
        >
          <div className="text-copy-13 space-y-2">
            <div className="flex flex-wrap items-center gap-2">
              {sources.formats.map((item) => (
                <Badge key={item.code} tone={item.available ? 'primary' : 'warning'}>
                  {item.label}
                </Badge>
              ))}
              <span className="text-muted-foreground">
                同步导出上限 {sources.syncRowThreshold} 行 · 单次导出上限 {sources.maxRows} 行 ·
                下载有效期 {sources.downloadTtlHours} 小时
              </span>
            </div>
            <ul className="text-muted-foreground list-disc space-y-1 pl-5">
              {sources.notes.map((note) => (
                <li key={note}>{note}</li>
              ))}
            </ul>
          </div>
        </SectionCard>
      )}

      {/* ── 发起导出 ─────────────────────────────────────────────────── */}
      <SectionCard
        title="发起导出"
        description="选一张报表 → 选格式 → 勾列 → 发起。受理后是「排队中」，文件在后台生成。"
      >
        <div className="space-y-3">
          <div className="flex flex-wrap items-end gap-3">
            <label className="text-copy-13 text-muted-foreground flex flex-col gap-1">
              报表
              <select
                value={reportId}
                onChange={(e) => setReportId(e.target.value)}
                className="border-border bg-background rounded-md border px-2 py-1.5 text-sm"
              >
                <option value="">请选择报表</option>
                {reports.map((report) => (
                  <option key={report.reportId} value={report.reportId}>
                    {report.displayName}（{report.reportCode}）
                  </option>
                ))}
              </select>
            </label>
            <label className="text-copy-13 text-muted-foreground flex flex-col gap-1">
              格式
              <select
                value={format}
                onChange={(e) => setFormat(e.target.value)}
                className="border-border bg-background rounded-md border px-2 py-1.5 text-sm"
              >
                {(sources?.formats ?? []).map((item) => (
                  <option key={item.code} value={item.code}>
                    {item.label}
                  </option>
                ))}
              </select>
            </label>
            <Button disabled={!reportId || submitting || selectedFields.length === 0} onClick={() => void submit()}>
              <FileSpreadsheet className="mr-1.5 h-3.5 w-3.5" />
              {submitting ? '提交中…' : '发起导出'}
            </Button>
          </div>

          {reportId && (
            <div className="space-y-2">
              <div className="text-copy-13 text-muted-foreground">
                可导出列（{exportSelectionText(columns)}）—— **只有这张报表自己的维度与度量**。
              </div>
              <div className="flex flex-wrap gap-2">
                {columns.map((column) => {
                  const checked = selectedFields.includes(column.key);
                  return (
                    <label
                      key={column.key}
                      title={exportColumnNote(column)}
                      className="border-border bg-background flex items-center gap-1.5 rounded-md border px-2 py-1 text-sm"
                    >
                      <input
                        type="checkbox"
                        checked={checked}
                        onChange={(e) =>
                          setSelectedFields((previous) =>
                            e.target.checked
                              ? [...previous, column.key]
                              : previous.filter((key) => key !== column.key),
                          )
                        }
                      />
                      {column.label}
                      <span className="text-muted-foreground text-label-12">
                        {exportColumnKindLabel(column.kind)}
                      </span>
                    </label>
                  );
                })}
              </div>
            </div>
          )}

          {formError && (
            <div className="text-copy-13 text-destructive flex items-start gap-2">
              <TriangleAlert className="mt-0.5 h-3.5 w-3.5 shrink-0" />
              {formError}
            </div>
          )}
        </div>
      </SectionCard>

      {/* ── 导出任务清单 ─────────────────────────────────────────────── */}
      <SectionCard
        title="导出任务"
        description="大导出走异步任务：受理即「排队中」，行数与文件要轮询任务详情才拿得到。"
      >
        <div className="mb-3 flex flex-wrap items-end gap-3">
          <label className="text-copy-13 text-muted-foreground flex flex-col gap-1">
            搜索
            <input
              type="search"
              value={jobKeyword}
              onChange={(e) => {
                setJobPage(0);
                setJobKeyword(e.target.value);
              }}
              placeholder="报表名称或编码"
              className="border-border bg-background rounded-md border px-2 py-1.5 text-sm"
            />
          </label>
          <label className="text-copy-13 text-muted-foreground flex flex-col gap-1">
            状态
            <select
              value={jobStatus}
              onChange={(e) => {
                setJobPage(0);
                setJobStatus(e.target.value);
              }}
              className="border-border bg-background rounded-md border px-2 py-1.5 text-sm"
            >
              <option value="">全部</option>
              <option value="PENDING">排队中</option>
              <option value="RUNNING">生成中</option>
              <option value="SUCCEEDED">已完成</option>
              <option value="FAILED">失败</option>
              <option value="CANCELLED">已作废</option>
              <option value="EXPIRED">已过期</option>
            </select>
          </label>
          <label className="text-copy-13 text-muted-foreground flex flex-col gap-1">
            格式
            <select
              value={jobFormat}
              onChange={(e) => {
                setJobPage(0);
                setJobFormat(e.target.value);
              }}
              className="border-border bg-background rounded-md border px-2 py-1.5 text-sm"
            >
              <option value="">全部</option>
              <option value="CSV">CSV</option>
              <option value="XLSX">Excel</option>
              <option value="PDF">PDF</option>
            </select>
          </label>
          <Button variant="outline" size="sm" onClick={() => void loadJobs()}>
            <RefreshCw className="mr-1.5 h-3.5 w-3.5" />
            刷新
          </Button>
        </div>

        {jobError && (
          <div className="text-copy-13 text-destructive mb-3 flex items-start gap-2">
            <TriangleAlert className="mt-0.5 h-3.5 w-3.5 shrink-0" />
            {jobError}
          </div>
        )}

        {listState === 'loading' && <div className="text-copy-13 text-muted-foreground">读取中…</div>}

        {listState === 'empty' && (
          <EmptyState
            icon={<FileSpreadsheet className="h-6 w-6" />}
            title="还没有导出任务"
            description={jobListEmptyText(jobs)}
          />
        )}

        {listState === 'ready' && (
          <div className="space-y-2">
            {(jobs?.rows ?? []).map((job) => {
              const state = downloadState(job);
              return (
                <div
                  key={job.jobId}
                  className="border-border flex flex-wrap items-center justify-between gap-3 rounded-md border px-3 py-2"
                >
                  <div className="min-w-0 space-y-1">
                    <div className="text-copy-13 flex flex-wrap items-center gap-2">
                      <Badge tone={jobTone(job.status)}>{exportStatusLabel(job.status)}</Badge>
                      <span className="text-foreground font-medium">
                        {job.reportName}（{job.reportCode}）
                      </span>
                      <span className="text-muted-foreground">{exportFormatLabel(job.format)}</span>
                    </div>
                    <div className="text-muted-foreground text-label-12">
                      {exportJobSummary(job)} · {exportByteSizeText(job)}
                    </div>
                    <div className="text-muted-foreground text-label-12">
                      {downloadStateText(job)}
                    </div>
                  </div>
                  <div className="flex items-center gap-2">
                    {state === 'ready' && (
                      <a
                        href={exportDownloadUrl(job.jobId)}
                        className="border-border bg-background inline-flex items-center rounded-md border px-2 py-1.5 text-sm"
                      >
                        <Download className="mr-1.5 h-3.5 w-3.5" />
                        下载
                      </a>
                    )}
                    {(job.status === 'PENDING' || job.status === 'RUNNING' || job.status === 'SUCCEEDED') && (
                      <Button
                        variant="outline"
                        size="sm"
                        onClick={() => void cancelJob(job.jobId)}
                      >
                        作废
                      </Button>
                    )}
                  </div>
                </div>
              );
            })}
            <div className="text-copy-13 text-muted-foreground flex items-center gap-3">
              <span>
                共 {jobs?.total ?? 0} 条 · 第 {(jobs?.page ?? 0) + 1} 页
              </span>
              <Button
                variant="outline"
                size="sm"
                disabled={(jobs?.page ?? 0) === 0}
                onClick={() => setJobPage((page) => Math.max(page - 1, 0))}
              >
                上一页
              </Button>
              <Button
                variant="outline"
                size="sm"
                disabled={((jobs?.page ?? 0) + 1) * (jobs?.size ?? 20) >= (jobs?.total ?? 0)}
                onClick={() => setJobPage((page) => page + 1)}
              >
                下一页
              </Button>
            </div>
          </div>
        )}
      </SectionCard>

      {/* ── 投递台账 ─────────────────────────────────────────────────── */}
      <SectionCard
        title="订阅投递"
        description="投递记录的状态是**真的投递结果**：没配置 SMTP / Webhook 时会记为失败并写明原因，不会假装「已发送」。"
      >
        <div className="mb-3 flex flex-wrap items-end gap-3">
          <label className="text-copy-13 text-muted-foreground flex flex-col gap-1">
            状态
            <select
              value={deliveryStatus}
              onChange={(e) => {
                setDeliveryPage(0);
                setDeliveryStatus(e.target.value);
              }}
              className="border-border bg-background rounded-md border px-2 py-1.5 text-sm"
            >
              <option value="">全部</option>
              <option value="PENDING">待投递</option>
              <option value="DELIVERED">已投递</option>
              <option value="FAILED">投递失败</option>
              <option value="CANCELLED">已取消</option>
            </select>
          </label>
          <Button variant="outline" size="sm" disabled={dispatching} onClick={() => void runDispatch()}>
            <RefreshCw className="mr-1.5 h-3.5 w-3.5" />
            {dispatching ? '投递中…' : '跑一轮投递'}
          </Button>
          <Button variant="outline" size="sm" onClick={() => void loadLedger()}>
            刷新
          </Button>
          {dispatch && (
            <span className="text-copy-13 text-muted-foreground">{dispatchResultText(dispatch)}</span>
          )}
        </div>

        {deliveryError && (
          <div className="text-copy-13 text-destructive mb-3 flex items-start gap-2">
            <TriangleAlert className="mt-0.5 h-3.5 w-3.5 shrink-0" />
            {deliveryError}
          </div>
        )}

        {dispatch && (
          <div className="text-copy-13 text-muted-foreground mb-3">{dispatch.note}</div>
        )}

        {!ledger || ledger.rows.length === 0 ? (
          <EmptyState
            icon={<FileSpreadsheet className="h-6 w-6" />}
            title="没有投递记录"
            description={deliveryEmptyText()}
          />
        ) : (
          <div className="space-y-2">
            {ledger.rows.map((row) => (
              <div
                key={row.deliveryId}
                className="border-border flex flex-wrap items-center justify-between gap-3 rounded-md border px-3 py-2"
              >
                <div className="min-w-0 space-y-1">
                  <div className="text-copy-13 flex flex-wrap items-center gap-2">
                    <Badge tone={deliveryTone(row.status)}>{deliveryStatusLabel(row.status)}</Badge>
                    <span className="text-foreground font-medium">{row.reportCode}</span>
                  </div>
                  <div className="text-muted-foreground text-label-12">
                    计划时刻 {row.scheduledFor} · 尝试 {row.attemptCount} 次
                  </div>
                  {row.lastError && (
                    <div className="text-destructive text-label-12">失败原因：{row.lastError}</div>
                  )}
                </div>
                <Button variant="outline" size="sm" onClick={() => void openDelivery(row.deliveryId)}>
                  详情
                </Button>
              </div>
            ))}
            <div className="text-copy-13 text-muted-foreground flex items-center gap-3">
              <span>
                共 {ledger.total} 条 · 第 {ledger.page + 1} 页
              </span>
              <Button
                variant="outline"
                size="sm"
                disabled={ledger.page === 0}
                onClick={() => setDeliveryPage((page) => Math.max(page - 1, 0))}
              >
                上一页
              </Button>
              <Button
                variant="outline"
                size="sm"
                disabled={(ledger.page + 1) * ledger.size >= ledger.total}
                onClick={() => setDeliveryPage((page) => page + 1)}
              >
                下一页
              </Button>
            </div>
          </div>
        )}

        {deliveryDetail && (
          <div className="border-border bg-muted/40 mt-3 space-y-1 rounded-md border px-3 py-2">
            <div className="text-copy-13">
              <span className="font-medium">{deliveryDetail.reportName}</span>（
              {deliveryDetail.reportCode}）
            </div>
            <div className="text-muted-foreground text-label-12">
              通道 {deliveryDetail.channel} → {deliveryDetail.target}
            </div>
            <div className="text-muted-foreground text-label-12">
              状态 {deliveryStatusLabel(deliveryDetail.status)} · 尝试 {deliveryDetail.attemptCount} 次
            </div>
            <div className="text-muted-foreground text-label-12">{deliveryNote(deliveryDetail)}</div>
          </div>
        )}
      </SectionCard>
    </div>
  );
}
