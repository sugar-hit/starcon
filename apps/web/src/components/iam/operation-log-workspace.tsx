'use client';

import { useCallback, useEffect, useState } from 'react';
import { Badge, Button } from '@starcon/ui';
import { Download, GitBranch, RefreshCw, Search } from 'lucide-react';
import type {
  OperationLogDetail,
  OperationLogTrace,
  OperationLogView,
} from '../../../../../packages/api-contracts/src/catalog';
import { useIdentity } from './identity-shell';
import {
  emptyFilters,
  eventTypeLabel,
  eventTypes,
  fetchOperationLog,
  fetchOperationLogDetail,
  fetchOperationLogTrace,
  formatDuration,
  formatTime,
  levelLabel,
  operationLogExportUrl,
  shortRequestId,
  traceSteps,
  type OperationLogFilters,
} from './operation-log-model';

/**
 * 17.5.3.2 操作日志。
 *
 * <p>排障用：登录流水、API 调用摘要、任务执行、错误堆栈，按用户 / 时间 / 请求 ID 检索。
 * 核心是**链路关联视图**——选中任意一条日志即可按它的 request_id 把这次请求引发的
 * 多条日志按真实时序串起来看，统计量由服务端在数据库里聚合。
 *
 * <p>与审计日志的分野（这里能、审计不能）：操作日志含技术细节（耗时、状态码、堆栈），
 * 留存量按到期时间清理；审计脱敏、不可篡改、长期留存。两者不互相替代。
 */
export function OperationLogWorkspace() {
  const { session } = useIdentity();
  const [filters, setFilters] = useState<OperationLogFilters>(emptyFilters);
  const [applied, setApplied] = useState<OperationLogFilters>(emptyFilters);
  const [rows, setRows] = useState<OperationLogView[]>([]);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(0);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [detail, setDetail] = useState<OperationLogDetail | null>(null);
  const [detailLoading, setDetailLoading] = useState(false);
  const [trace, setTrace] = useState<OperationLogTrace | null>(null);
  const [traceLoading, setTraceLoading] = useState(false);
  const readable = session.permissions.includes('identity.operation_log.read');
  const size = 50;

  const load = useCallback(async () => {
    if (!readable) {
      setLoading(false);
      return;
    }
    setLoading(true);
    setError('');
    try {
      const result = await fetchOperationLog(applied, page, size);
      setRows(result.items);
      setTotal(result.total);
    } catch (e) {
      setError(e instanceof Error ? e.message : '操作日志加载失败');
    } finally {
      setLoading(false);
    }
  }, [applied, page, readable]);

  useEffect(() => {
    void load();
  }, [load]);

  const openDetail = useCallback(async (id: string) => {
    setDetailLoading(true);
    setError('');
    try {
      setDetail(await fetchOperationLogDetail(id));
    } catch (e) {
      setError(e instanceof Error ? e.message : '日志详情加载失败');
    } finally {
      setDetailLoading(false);
    }
  }, []);

  /** 链路关联视图：按请求 ID 拉一次聚合，服务端返回真实时序与统计量。 */
  const openTrace = useCallback(async (requestId: string) => {
    setTraceLoading(true);
    setError('');
    try {
      setTrace(await fetchOperationLogTrace(requestId));
    } catch (e) {
      setError(e instanceof Error ? e.message : '链路加载失败');
      setTrace(null);
    } finally {
      setTraceLoading(false);
    }
  }, []);

  if (!readable)
    return (
      <p className="rounded-lg border p-6 text-sm">
        当前角色没有操作日志查看权限，请联系租户所有者开通。
      </p>
    );

  const pages = Math.max(1, Math.ceil(total / size));
  const update = (patch: Partial<OperationLogFilters>) =>
    setFilters((current) => ({ ...current, ...patch }));

  return (
    <section className="space-y-4">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <h2 className="text-xl font-semibold">操作日志</h2>
        <div className="flex items-center gap-2">
          <a
            href={operationLogExportUrl(applied)}
            download
            className="border-border hover:bg-accent inline-flex h-8 items-center gap-1.5 rounded-md border px-3 text-sm"
          >
            <Download className="h-4 w-4" />
            导出 CSV
          </a>
          <Button variant="ghost" size="sm" onClick={() => void load()}>
            <RefreshCw className="h-4 w-4" />
            刷新
          </Button>
        </div>
      </div>
      <p className="text-muted-foreground text-sm">
        记录登录流水、API 调用摘要、任务执行与错误堆栈，用于运维排障：按用户 / 时间 / 请求 ID
        检索，点任意一条可查看该请求的完整链路。密码、令牌与完整请求体不会进入操作日志；
        错误堆栈超过 8000 字符会被截断并标记。
      </p>

      <form
        className="bg-card space-y-3 rounded-lg border p-4"
        onSubmit={(event) => {
          event.preventDefault();
          setPage(0);
          setApplied(filters);
        }}
      >
        <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
          <label className="space-y-1 text-sm">
            <span className="text-muted-foreground">事件类型</span>
            <select
              value={filters.eventType}
              onChange={(e) => update({ eventType: e.target.value })}
              className="border-border bg-card h-9 w-full rounded-md border px-2"
            >
              <option value="">全部类型</option>
              {eventTypes.map((type) => (
                <option key={type} value={type}>
                  {eventTypeLabel(type)}
                </option>
              ))}
            </select>
          </label>
          <label className="space-y-1 text-sm">
            <span className="text-muted-foreground">级别</span>
            <select
              value={filters.level}
              onChange={(e) => update({ level: e.target.value })}
              className="border-border bg-card h-9 w-full rounded-md border px-2"
            >
              <option value="">全部级别</option>
              <option value="INFO">正常</option>
              <option value="WARNING">告警</option>
              <option value="ERROR">错误</option>
            </select>
          </label>
          <label className="space-y-1 text-sm">
            <span className="text-muted-foreground">操作人 ID</span>
            <input
              value={filters.actorId}
              onChange={(e) => update({ actorId: e.target.value })}
              placeholder="精确匹配"
              className="border-border bg-card h-9 w-full rounded-md border px-2"
            />
          </label>
          <label className="space-y-1 text-sm">
            <span className="text-muted-foreground">请求 ID</span>
            <input
              value={filters.requestId}
              onChange={(e) => update({ requestId: e.target.value })}
              placeholder="按链路 ID 检索"
              className="border-border bg-card h-9 w-full rounded-md border px-2"
            />
          </label>
          <label className="space-y-1 text-sm">
            <span className="text-muted-foreground">开始时间</span>
            <input
              type="datetime-local"
              value={filters.from}
              onChange={(e) => update({ from: e.target.value })}
              className="border-border bg-card h-9 w-full rounded-md border px-2"
            />
          </label>
          <label className="space-y-1 text-sm">
            <span className="text-muted-foreground">结束时间</span>
            <input
              type="datetime-local"
              value={filters.to}
              onChange={(e) => update({ to: e.target.value })}
              className="border-border bg-card h-9 w-full rounded-md border px-2"
            />
          </label>
        </div>
        <div className="flex items-center gap-2">
          <Button type="submit" size="sm">
            <Search className="h-4 w-4" />
            检索
          </Button>
          <Button
            type="button"
            size="sm"
            variant="ghost"
            onClick={() => {
              setFilters(emptyFilters);
              setApplied(emptyFilters);
              setPage(0);
            }}
          >
            重置
          </Button>
        </div>
      </form>

      {error && (
        <div
          role="alert"
          className="border-destructive/30 bg-destructive/5 text-destructive rounded-md border p-3 text-sm"
        >
          {error}
          <Button variant="link" onClick={() => void load()}>
            重试
          </Button>
        </div>
      )}

      {loading ? (
        <p role="status" className="text-muted-foreground text-sm">
          正在加载操作日志…
        </p>
      ) : rows.length === 0 ? (
        <div className="bg-card rounded-lg border p-8 text-center">
          <p className="text-muted-foreground text-sm">
            {applied === emptyFilters ? '还没有操作日志' : '没有符合筛选条件的操作日志'}
          </p>
        </div>
      ) : (
        <>
          <ul className="divide-y rounded-lg border">
            {rows.map((row) => (
              <li key={row.id} className="flex flex-wrap items-center justify-between gap-3 p-4">
                <div className="min-w-0 space-y-1">
                  <p className="flex flex-wrap items-center gap-2 font-medium">
                    <Badge
                      tone={
                        row.level === 'ERROR'
                          ? 'danger'
                          : row.level === 'WARNING'
                            ? 'warning'
                            : 'neutral'
                      }
                    >
                      {levelLabel(row.level)}
                    </Badge>
                    {eventTypeLabel(row.eventType)}
                    <span className="text-muted-foreground text-xs">{row.summary}</span>
                  </p>
                  <p className="text-muted-foreground text-xs">
                    {formatTime(row.occurredAt)}
                    {row.actorName ? ` · ${row.actorName}` : ''}
                    {row.durationMs !== null ? ` · ${formatDuration(row.durationMs)}` : ''}
                    {` · 请求 ${shortRequestId(row.requestId)}`}
                  </p>
                </div>
                <div className="flex items-center gap-2">
                  <Button variant="outline" size="sm" onClick={() => void openTrace(row.requestId)}>
                    <GitBranch className="h-4 w-4" />
                    链路
                  </Button>
                  <Button variant="outline" size="sm" onClick={() => void openDetail(row.id)}>
                    详情
                  </Button>
                </div>
              </li>
            ))}
          </ul>
          <div className="flex items-center justify-between gap-3 text-sm">
            <p className="text-muted-foreground">
              共 {total} 条，第 {page + 1} / {pages} 页
            </p>
            <div className="flex items-center gap-2">
              <Button
                size="sm"
                variant="outline"
                disabled={page === 0}
                onClick={() => setPage((current) => Math.max(0, current - 1))}
              >
                上一页
              </Button>
              <Button
                size="sm"
                variant="outline"
                disabled={page + 1 >= pages}
                onClick={() => setPage((current) => current + 1)}
              >
                下一页
              </Button>
            </div>
          </div>
        </>
      )}

      {traceLoading && (
        <p role="status" className="text-muted-foreground text-sm">
          正在加载链路…
        </p>
      )}

      {trace && !traceLoading && <TracePanel trace={trace} onClose={() => setTrace(null)} />}

      {detailLoading && (
        <p role="status" className="text-muted-foreground text-sm">
          正在加载日志详情…
        </p>
      )}

      {detail && <OperationLogDetailPanel detail={detail} onClose={() => setDetail(null)} />}
    </section>
  );
}

/**
 * 链路关联视图。
 *
 * <p>统计量（条数、错误数、总耗时、跨度）来自服务端在数据库里的聚合，
 * 这里只做展示——不在前端数条目，否则会和列表分页给出两个互相矛盾的口径。
 */
function TracePanel({ trace, onClose }: { trace: OperationLogTrace; onClose: () => void }) {
  const steps = traceSteps(trace);
  return (
    <div className="bg-card space-y-4 rounded-lg border p-5">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <h3 className="flex items-center gap-2 font-semibold">
          <GitBranch className="h-4 w-4" />
          链路关联视图
        </h3>
        <Button size="sm" variant="ghost" onClick={onClose}>
          关闭
        </Button>
      </div>

      <dl className="grid gap-3 text-sm sm:grid-cols-2 lg:grid-cols-4">
        <div>
          <dt className="text-muted-foreground">请求 ID</dt>
          <dd className="break-all font-mono text-xs">{trace.requestId}</dd>
        </div>
        <div>
          <dt className="text-muted-foreground">链路步数</dt>
          <dd>
            {trace.entryCount} 步 · 错误 {trace.errorCount} · 告警 {trace.warningCount}
          </dd>
        </div>
        <div>
          <dt className="text-muted-foreground">累计耗时</dt>
          <dd>{trace.entryCount > 0 ? formatDuration(trace.totalDurationMs) : '—'}</dd>
        </div>
        <div>
          <dt className="text-muted-foreground">时间跨度</dt>
          <dd>
            {trace.firstAt && trace.lastAt
              ? `${formatDuration(trace.spanMs)}（${formatTime(trace.firstAt)} 起）`
              : '—'}
          </dd>
        </div>
      </dl>

      {steps.length === 0 ? (
        <p className="text-muted-foreground text-sm">
          这个请求 ID 没有操作日志记录。链路为空本身也是排障结论：说明它发生在
          操作日志覆盖范围之外，或已被留存期清理。
        </p>
      ) : (
        <ol className="space-y-2">
          {steps.map((step, index) => (
            <li key={step.id} className="flex flex-wrap items-start gap-3 text-sm">
              <span className="text-muted-foreground w-6 shrink-0 font-mono text-xs">
                {index + 1}
              </span>
              <div className="min-w-0 flex-1 space-y-0.5">
                <p className="flex flex-wrap items-center gap-2">
                  <Badge
                    tone={
                      step.level === 'ERROR'
                        ? 'danger'
                        : step.level === 'WARNING'
                          ? 'warning'
                          : 'neutral'
                    }
                  >
                    {levelLabel(step.level)}
                  </Badge>
                  {step.eventLabel}
                  <span>{step.summary}</span>
                </p>
                <p className="text-muted-foreground text-xs">
                  {formatTime(step.at)}
                  {step.actorName ? ` · ${step.actorName}` : ' · 系统'}
                  {step.durationMs !== null ? ` · ${formatDuration(step.durationMs)}` : ''}
                  {step.errorClass ? ` · ${step.errorClass}` : ''}
                </p>
              </div>
            </li>
          ))}
        </ol>
      )}
    </div>
  );
}

function OperationLogDetailPanel({
  detail,
  onClose,
}: {
  detail: OperationLogDetail;
  onClose: () => void;
}) {
  return (
    <div className="bg-card space-y-4 rounded-lg border p-5">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <h3 className="flex items-center gap-2 font-semibold">
          <Badge
            tone={
              detail.level === 'ERROR'
                ? 'danger'
                : detail.level === 'WARNING'
                  ? 'warning'
                  : 'neutral'
            }
          >
            {levelLabel(detail.level)}
          </Badge>
          {eventTypeLabel(detail.eventType)}
        </h3>
        <Button size="sm" variant="ghost" onClick={onClose}>
          关闭
        </Button>
      </div>
      <dl className="grid gap-3 text-sm sm:grid-cols-2">
        <div>
          <dt className="text-muted-foreground">摘要</dt>
          <dd>{detail.summary}</dd>
        </div>
        <div>
          <dt className="text-muted-foreground">发生时间</dt>
          <dd>{formatTime(detail.occurredAt)}</dd>
        </div>
        <div>
          <dt className="text-muted-foreground">操作人</dt>
          <dd>{detail.actorName ?? '系统'}</dd>
        </div>
        <div>
          <dt className="text-muted-foreground">请求 ID</dt>
          <dd className="break-all font-mono text-xs">{detail.requestId}</dd>
        </div>
        {detail.traceId && (
          <div>
            <dt className="text-muted-foreground">链路 ID</dt>
            <dd className="break-all font-mono text-xs">{detail.traceId}</dd>
          </div>
        )}
        {detail.httpMethod && (
          <div>
            <dt className="text-muted-foreground">API 调用</dt>
            <dd>
              {detail.httpMethod} {detail.path}
              {detail.statusCode !== null ? ` → ${detail.statusCode}` : ''}
              {detail.durationMs !== null ? ` · ${formatDuration(detail.durationMs)}` : ''}
            </dd>
          </div>
        )}
        {detail.taskName && (
          <div>
            <dt className="text-muted-foreground">任务执行</dt>
            <dd>
              {detail.taskName} · {detail.taskStatus}
            </dd>
          </div>
        )}
        <div>
          <dt className="text-muted-foreground">留存至</dt>
          <dd>{formatTime(detail.expiresAt)}（到期后可被清理）</dd>
        </div>
      </dl>

      {detail.errorMessage && (
        <div className="space-y-1">
          <p className="text-muted-foreground text-xs">错误信息</p>
          <p className="text-destructive break-all text-sm">{detail.errorMessage}</p>
        </div>
      )}

      {detail.stackTrace && (
        <div className="space-y-1">
          <p className="text-muted-foreground text-xs">
            错误堆栈
            {detail.stackTruncated ? '（超过 8000 字符，已截断）' : ''}
          </p>
          <pre className="bg-muted max-h-80 overflow-auto whitespace-pre-wrap rounded-md p-3 text-xs">
            {detail.stackTrace}
          </pre>
        </div>
      )}

      {detail.detail && Object.keys(detail.detail).length > 0 && (
        <div className="space-y-1">
          <p className="text-muted-foreground text-xs">技术明细（写入时已脱敏）</p>
          <pre className="bg-muted max-h-60 overflow-auto rounded-md p-3 text-xs">
            {JSON.stringify(detail.detail, null, 2)}
          </pre>
        </div>
      )}
    </div>
  );
}
