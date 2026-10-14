'use client';

import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { Badge, Button, EmptyState, SectionCard, StatCard } from '@starcon/ui';
import {
  AlertTriangle,
  Check,
  Download,
  FileSpreadsheet,
  Loader2,
  RefreshCw,
  ShieldAlert,
  Trash2,
} from 'lucide-react';
import { Field, control } from './fields';
import type { ExportFilterInfo, ExportJobRow, ExportSourceInfo } from './export-model';
import {
  PII_HINT,
  QUOTE_BOUNDARY_HINT,
  TTL_HINT,
  availableFilters,
  byteText,
  cancelJob,
  conditionText,
  createJob,
  defaultFields,
  errorText,
  fetchJobs,
  fetchSources,
  isRunning,
  jobFileUrl,
  localTime,
  piiFieldsText,
  requestProblem,
  rowCountText,
  sortOptions,
  statusLabel,
  statusTone,
  unavailableFilters,
} from './export-model';
import type { ExportJobInput } from '../../../../../packages/api-contracts/src/index';

const PAGE_SIZE = 20;

/**
 * 3.8.2.1 导出工作区。
 *
 * <p>四条必须写在界面上的纪律：
 *
 * <ul>
 *   <li><b>提交后显示的是「已受理」，不是「已完成」。</b>导出是异步任务，
 *       POST 只推进任务状态；行数与文件要轮询记录才拿得到，
 *       在响应里显示「导出完成」会让用户关掉页面而后台一个字节都还没写。</li>
 *   <li><b>敏感字段要显式授权。</b>勾了邮箱/电话/手机就必须写用途，
 *       不写就不让提交 —— 授权理由会与操作人、条件、字段清单一并留档。</li>
 *   <li><b>下载有效期由后端判。</b>本页只回显剩余时间与「不可下载」的原因，
 *       不自己算过期、不自己禁用按钮 —— 那两件事各算一次必然漂移。</li>
 *   <li><b>0 行是合法结果。</b>条件没命中就是 0 行，必须一眼能看出这不是失败。</li>
 * </ul>
 */
export function ExportWorkspace() {
  const [sources, setSources] = useState<ExportSourceInfo[]>([]);
  const [sourceKey, setSourceKey] = useState<string | null>(null);
  const [fields, setFields] = useState<string[]>([]);
  const [sortKey, setSortKey] = useState<string>('');
  const [sortOrder, setSortOrder] = useState<'asc' | 'desc'>('desc');
  const [filters, setFilters] = useState<Record<string, string>>({});
  const [piiPurpose, setPiiPurpose] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  const [rows, setRows] = useState<ExportJobRow[]>([]);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(0);
  const [loading, setLoading] = useState(true);
  const [listError, setListError] = useState<string | null>(null);

  const source = useMemo(
    () => sources.find((s) => s.key === sourceKey) ?? null,
    [sources, sourceKey],
  );
  const piiSelected = useMemo(
    () => (source?.fields ?? []).filter((f) => f.pii && fields.includes(f.key)),
    [source, fields],
  );

  const loadSources = useCallback(async () => {
    const page_ = await fetchSources();
    setSources(page_.items);
    setSourceKey((current) => current ?? page_.items.find((s) => s.available)?.key ?? null);
  }, []);

  const loadJobs = useCallback(async (target: number) => {
    setLoading(true);
    setListError(null);
    try {
      const result = await fetchJobs({ sourceKey: null, status: null, page: target, size: PAGE_SIZE });
      setRows(result.items);
      setTotal(result.total);
      setPage(result.page);
    } catch (e) {
      setListError(errorText(e));
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void loadSources().catch((e) => setError(errorText(e)));
    void loadJobs(0);
  }, [loadSources, loadJobs]);

  // 换数据源时重置字段 / 排序 / 条件：它们都属于上一个数据源，继续留着就是错配。
  const firstSourceLoaded = useRef(false);
  useEffect(() => {
    if (!source) return;
    if (firstSourceLoaded.current) {
      setFields(defaultFields(source));
      setSortKey(source.defaultSortKey || source.sorts[0]?.key || '');
      setSortOrder('desc');
      setFilters({});
      setPiiPurpose('');
    } else {
      firstSourceLoaded.current = true;
      setFields(defaultFields(source));
      setSortKey(source.defaultSortKey || source.sorts[0]?.key || '');
    }
  }, [source]);

  // 有任务在跑就轮询，跑到终态为止；这是「异步任务」的正常交互，不是假进度条。
  const hasRunning = rows.some(isRunning);
  useEffect(() => {
    if (!hasRunning) return;
    const timer = setInterval(() => void loadJobs(page), 1500);
    return () => clearInterval(timer);
  }, [hasRunning, page, loadJobs]);

  const submit = async () => {
    const problem = requestProblem(fields, piiPurpose, sources, sourceKey);
    if (problem) {
      setError(problem);
      return;
    }
    setSubmitting(true);
    setError(null);
    setNotice(null);
    try {
      const body: Record<string, unknown> = {
        sourceKey,
        fields,
        sort: { key: sortKey || undefined, order: sortOrder },
      };
      const used = availableFilters(source).filter((f) => (filters[f.key] ?? '').trim() !== '');
      if (used.length > 0) {
        const filterBody: Record<string, unknown> = {};
        for (const f of used) {
          const raw = (filters[f.key] ?? '').trim();
          if (f.kind === 'BOOLEAN') filterBody[f.key] = raw === 'true';
          else if (f.kind === 'DECIMAL') filterBody[f.key] = Number(raw);
          else if (f.key === 'spuId' || f.key === 'partyId') filterBody[f.key] = raw;
          else filterBody[f.key] = raw;
        }
        body.filters = filterBody;
      }
      if (piiPurpose.trim()) body.piiPurpose = piiPurpose.trim();
      const job = await createJob(body as unknown as ExportJobInput);
      setNotice(
        `已受理导出「${job.sourceLabel}」：任务 ${job.id.slice(0, 8)}。` +
          '文件在后台生成，完成后本列表会自动刷新。',
      );
      setPage(0);
      await loadJobs(0);
    } catch (e) {
      setError(errorText(e));
    } finally {
      setSubmitting(false);
    }
  };

  const revoke = async (id: string) => {
    setError(null);
    try {
      await cancelJob(id);
      await loadJobs(page);
    } catch (e) {
      setError(errorText(e));
    }
  };

  const downloadable = rows.filter((r) => r.downloadable).length;
  const piiCount = rows.filter((r) => r.includesPii).length;

  return (
    <div className="space-y-4">
      <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
        <StatCard
          label="可导出数据源"
          value={sources.filter((s) => s.available).length}
          comparisonLabel="字段与筛选由后端声明"
        />
        <StatCard label="导出记录" value={total} comparisonLabel="可追溯到操作人与条件" />
        <StatCard label="当前可下载" value={downloadable} comparisonLabel="过期由后端清理" />
        <StatCard label="含敏感字段" value={piiCount} comparisonLabel="均带授权用途留档" />
      </div>

      {error && (
        <div className="border-danger/40 bg-danger/5 text-danger flex items-start gap-2 rounded-lg border px-4 py-3 text-copy-13">
          <AlertTriangle className="mt-0.5 size-4 shrink-0" />
          <span>{error}</span>
        </div>
      )}
      {notice && (
        <div className="border-ok/40 bg-ok/5 text-ok flex items-start gap-2 rounded-lg border px-4 py-3 text-copy-13">
          <Check className="mt-0.5 size-4 shrink-0" />
          <span>{notice}</span>
        </div>
      )}

      <SectionCard title="发起导出" description="按列表页当前的筛选条件导出结果数据：选数据源 → 勾字段 → 填条件 → 选排序。">
        <div className="space-y-5">
          <Field label="数据源">
            <select
              className={control}
              value={sourceKey ?? ''}
              onChange={(e) => setSourceKey(e.target.value || null)}
            >
              <option value="">请选择数据源</option>
              {sources.map((s) => (
                <option key={s.key} value={s.key} disabled={!s.available}>
                  {s.label}（{s.upstreamModule}）{s.available ? '' : ' — 尚未交付'}
                </option>
              ))}
            </select>
          </Field>

          {source && (
            <>
              <p className="text-muted-foreground text-copy-12">
                导出结果与「{source.upstreamModule}」同一份数据，上限 {source.maxRows.toLocaleString()} 行，
                下载有效期 {source.downloadTtlHours} 小时。{TTL_HINT}
              </p>

              <div className="space-y-2">
                <div className="text-copy-13 font-medium">
                  选择字段
                  <span className="text-muted-foreground ml-2 text-copy-12">
                    已选 {fields.length} / {source.fields.length}（按数据源定义的列顺序输出）
                  </span>
                </div>
                <div className="grid gap-2 sm:grid-cols-2 lg:grid-cols-3">
                  {source.fields.map((f) => (
                    <label
                      key={f.key}
                      className="hover:bg-muted/60 flex items-start gap-2 rounded-md border px-3 py-2 text-copy-13"
                    >
                      <input
                        type="checkbox"
                        className="mt-0.5"
                        checked={fields.includes(f.key)}
                        onChange={(e) =>
                          setFields((prev) =>
                            e.target.checked ? [...prev, f.key] : prev.filter((k) => k !== f.key),
                          )
                        }
                      />
                      <span className="min-w-0">
                        <span className="block">{f.label}</span>
                        {f.pii && (
                          <span className="text-danger flex items-center gap-1 text-copy-12">
                            <ShieldAlert className="size-3" />
                            敏感字段 · 需授权
                          </span>
                        )}
                      </span>
                    </label>
                  ))}
                </div>
              </div>

              <FilterGrid
                filters={availableFilters(source)}
                values={filters}
                onChange={(key, value) => setFilters((prev) => ({ ...prev, [key]: value }))}
              />

              {unavailableFilters(source).length > 0 && (
                <details className="text-muted-foreground border-control-border rounded-md border px-3 py-2 text-copy-12">
                  <summary className="cursor-pointer">
                    有 {unavailableFilters(source).length} 个列表页筛选条件当前无法用于导出
                  </summary>
                  <ul className="mt-2 space-y-1">
                    {unavailableFilters(source).map((f: ExportFilterInfo) => (
                      <li key={f.key}>
                        <span className="text-foreground">{f.label}</span>：{f.note}
                      </li>
                    ))}
                  </ul>
                </details>
              )}

              <div className="grid gap-3 sm:grid-cols-2">
                <Field label="排序" hint="还原列表页当前的排序，导出的行序与你看到的一致">
                  <select
                    className={control}
                    value={sortKey}
                    onChange={(e) => setSortKey(e.target.value)}
                  >
                    {sortOptions(source).map((s) => (
                      <option key={s.key} value={s.key}>
                        {s.label}
                      </option>
                    ))}
                  </select>
                </Field>
                <Field label="排序方向">
                  <select
                    className={control}
                    value={sortOrder}
                    onChange={(e) => setSortOrder(e.target.value as 'asc' | 'desc')}
                  >
                    <option value="desc">降序</option>
                    <option value="asc">升序</option>
                  </select>
                </Field>
              </div>

              {piiSelected.length > 0 && (
                <Field label="导出用途（必填）" hint={PII_HINT}>
                  <input
                    className={control}
                    value={piiPurpose}
                    onChange={(e) => setPiiPurpose(e.target.value)}
                    placeholder="例如：跨境客服工单派单"
                  />
                </Field>
              )}

              <div className="flex items-center gap-2">
                <Button onClick={() => void submit()} disabled={submitting}>
                  {submitting ? <Loader2 className="size-4 animate-spin" /> : <Download className="size-4" />}
                  发起导出
                </Button>
                <span className="text-muted-foreground text-copy-12">
                  提交后是「已受理」，文件在后台生成，本列表会自动刷新进度。
                </span>
              </div>
            </>
          )}

          <p className="text-muted-foreground border-control-border border-t pt-3 text-copy-12">
            {QUOTE_BOUNDARY_HINT}
          </p>
        </div>
      </SectionCard>

      <SectionCard
        title="导出记录"
        description="条件、字段、行数、时间与操作人都留在这条记录上；过期与作废由后端裁决。"
        extra={
          <Button size="sm" variant="outline" onClick={() => void loadJobs(page)}>
            <RefreshCw className="size-4" />
            刷新
          </Button>
        }
      >
        {loading && rows.length === 0 ? (
          <div className="text-muted-foreground flex items-center justify-center gap-2 py-10 text-copy-13">
            <Loader2 className="size-4 animate-spin" />
            正在加载导出记录…
          </div>
        ) : listError ? (
          <div className="border-danger/40 bg-danger/5 text-danger flex items-center gap-2 rounded-md border px-3 py-3 text-copy-13">
            <AlertTriangle className="size-4" />
            {listError}
            <Button size="sm" variant="ghost" onClick={() => void loadJobs(page)}>
              重试
            </Button>
          </div>
        ) : rows.length === 0 ? (
          <EmptyState
            icon={<FileSpreadsheet className="size-7" />}
            title="还没有导出记录"
            description="在上方选好数据源与字段后发起一次导出，任务与文件都会记在这里。"
          />
        ) : (
          <div className="space-y-3">
            {rows.map((row) => (
              <JobRow key={row.id} row={row} onRevoke={() => void revoke(row.id)} />
            ))}
            <div className="text-muted-foreground flex items-center justify-between pt-1 text-copy-12">
              <span>
                共 {total} 条 · 第 {page + 1} 页
              </span>
              <div className="flex gap-2">
                <Button
                  size="sm"
                  variant="outline"
                  disabled={page === 0}
                  onClick={() => void loadJobs(page - 1)}
                >
                  上一页
                </Button>
                <Button
                  size="sm"
                  variant="outline"
                  disabled={(page + 1) * PAGE_SIZE >= total}
                  onClick={() => void loadJobs(page + 1)}
                >
                  下一页
                </Button>
              </div>
            </div>
          </div>
        )}
      </SectionCard>
    </div>
  );
}

/** 筛选条件按后端声明的词表渲染：key 与 kind 都在服务端，前端不硬编码字段清单。 */
function FilterGrid({
  filters,
  values,
  onChange,
}: {
  filters: ExportFilterInfo[];
  values: Record<string, string>;
  onChange: (key: string, value: string) => void;
}) {
  if (filters.length === 0)
    return (
      <p className="text-muted-foreground text-copy-12">
        该数据源没有列表页筛选条件，将导出当前数据范围内的全部数据。
      </p>
    );
  return (
    <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
      {filters.map((f) => (
        <Field key={f.key} label={f.label}>
          {f.kind === 'ENUM' && f.key === 'status' ? (
            <select
              className={control}
              value={values[f.key] ?? ''}
              onChange={(e) => onChange(f.key, e.target.value)}
            >
              <option value="">全部</option>
              {['DRAFT', 'ACTIVE', 'INACTIVE', 'ARCHIVED'].map((s) => (
                <option key={s} value={s}>
                  {statusLabel(s) === s ? s : `${s}（${statusLabel(s)}）`}
                </option>
              ))}
            </select>
          ) : f.kind === 'BOOLEAN' ? (
            <select
              className={control}
              value={values[f.key] ?? ''}
              onChange={(e) => onChange(f.key, e.target.value)}
            >
              <option value="">不限</option>
              <option value="true">是</option>
              <option value="false">否</option>
            </select>
          ) : (
            <input
              className={control}
              value={values[f.key] ?? ''}
              onChange={(e) => onChange(f.key, e.target.value)}
              placeholder={f.kind === 'DECIMAL' ? '数值' : '留空表示不限'}
            />
          )}
        </Field>
      ))}
    </div>
  );
}

/** 一条导出记录：状态、行数、条件、字段、有效期、授权留痕与下载入口。 */
function JobRow({ row, onRevoke }: { row: ExportJobRow; onRevoke: () => void }) {
  const canRevoke = ['PENDING', 'RUNNING', 'SUCCEEDED'].includes(row.status);
  return (
    <div className="border-control-border space-y-2 rounded-lg border px-4 py-3">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div className="min-w-0 space-y-1">
          <div className="flex flex-wrap items-center gap-2">
            <span className="text-copy-14 font-medium">{row.sourceLabel}</span>
            <Badge tone={statusTone(row.status)}>{statusLabel(row.status)}</Badge>
            {row.includesPii && (
              <Badge tone="danger">
                <ShieldAlert className="size-3" />
                含敏感字段
              </Badge>
            )}
            <span className="text-muted-foreground font-mono text-copy-12">{row.id.slice(0, 8)}</span>
          </div>
          <div className="text-muted-foreground text-copy-12">
            {rowCountText(row)} · {conditionText(row.appliedFilters)} · 排序 {row.sortKey ?? '—'}{' '}
            {row.sortOrder === 'asc' ? '升序' : '降序'} · 操作人 {row.actorId?.slice(0, 8)} · 申请于{' '}
            {localTime(row.requestedAt)}
          </div>
          <div className="text-muted-foreground text-copy-12">
            字段（{row.headers.length}）：{row.headers.join(' / ')}
          </div>
          {row.includesPii && (
            <div className="text-danger text-copy-12">
              敏感字段：{piiFieldsText(row)} · 用途：{row.piiPurpose}
            </div>
          )}
          {row.error && <div className="text-danger text-copy-12">失败原因：{row.error}</div>}
          <div className="text-muted-foreground text-copy-12">
            {row.fileName ? `文件 ${row.fileName} · ${byteText(Number(row.byteSize))} · ` : ''}
            {row.expiresAt ? `有效期至 ${localTime(row.expiresAt)}` : ''}
            {row.downloadNote ? ` · ${row.downloadNote}` : ''}
          </div>
        </div>
        <div className="flex shrink-0 items-center gap-2">
          {row.downloadable ? (
            <a href={jobFileUrl(row.id)}>
              <Button size="sm">
                <Download className="size-4" />
                下载
              </Button>
            </a>
          ) : (
            <Button size="sm" variant="outline" disabled>
              不可下载
            </Button>
          )}
          {canRevoke && (
            <Button size="sm" variant="ghost" onClick={onRevoke}>
              <Trash2 className="size-4" />
              作废
            </Button>
          )}
        </div>
      </div>
    </div>
  );
}