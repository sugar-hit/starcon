'use client';

import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { Badge, Button, EmptyState, SectionCard, StatCard } from '@starcon/ui';
import {
  AlertTriangle,
  ArrowRight,
  Ban,
  Check,
  ChevronDown,
  ChevronUp,
  ClipboardList,
  Download,
  FileSpreadsheet,
  Loader2,
  Plus,
  RefreshCw,
  Save,
  Table2,
  Trash2,
  Upload,
} from 'lucide-react';
import type {
  ImportFieldMappingRow,
  ImportJobPage,
  ImportJobRow,
  ImportJobRowPage,
  ImportRowErrorPage,
  ImportTemplateDetail,
  ImportTemplateRow,
} from '../../../../../packages/api-contracts/src/index';
import { Editor, Field, control } from '../common/fields';
import { useIdentity } from '../iam/identity-shell';
import {
  FORMAT_HINT,
  IMPORT_MODES,
  QUOTE_BOUNDARY_HINT,
  REPORT_BOUNDARY_HINT,
  TARGET_ENTITIES,
  type TemplateInput,
  cancelJob,
  createTemplate,
  deleteMapping,
  deleteTemplate,
  entityLabel,
  errorText,
  executeHint,
  fetchErrors,
  fetchJob,
  fetchJobs,
  fetchMappings,
  fetchRows,
  fetchTemplate,
  fetchTemplates,
  groupErrors,
  ignoredHeaders,
  isRunning,
  isSettled,
  mappingProblem,
  modeHint,
  modeLabel,
  outcomeLabel,
  outcomeTone,
  progressPercent,
  progressText,
  requestExecute,
  requestPrevalidate,
  saveMapping,
  setJobMapping,
  statusLabel,
  statusTone,
  templateFileUrl,
  templateProblem,
  toTemplateInput,
  unmappedRequiredKeys,
  uploadFile,
} from './import-model';

const PAGE_SIZE = 20;
const ROW_PAGE_SIZE = 50;

/** 五个环节。蓝图标题写「四步引导」而正文列了五个动作 —— 按正文这五个实现。 */
const STEPS = [
  { key: 'template', label: '下载模板', hint: '拿一份带列头与示例行的 CSV' },
  { key: 'upload', label: '上传文件', hint: '选模板、选模式、传文件' },
  { key: 'mapping', label: '字段映射', hint: '把文件列头对上模板字段' },
  { key: 'prevalidate', label: '预校验', hint: '逐行逐列挑出错，不写库' },
  { key: 'execute', label: '执行', hint: '异步落库，进度可查' },
] as const;

/**
 * 3.8.1.1 导入向导工作区。
 *
 * <p>五个环节在**同一个页面**上按向导顺序排开，而不是五个孤立页面：
 * 运营的心智是「我在导一批货」，每一步都是上一步的继续。
 * 页面顶部是五步进度条，随时能回到任意一步（改了映射可以重跑预校验，
 * 不用重新上传文件 —— 上传时逐行原样落库，映射只是对它的解释）。
 *
 * <p><b>「提交」永远不等于「导入完成」</b>：蓝图要求导入是异步任务，
 * 大文件不阻塞页面。所以预校验与执行都是「已受理」，页面上用一个真实轮询的
 * 进度条 + 结果统计呈现结论，而不是在 200 返回时就宣布成功。
 *
 * <p><b>红线（3.8.1.2 蓝图原文）</b>：「校验失败的行不落库，不允许『尽力导入』产生半截数据」。
 * 执行按钮上方常驻这句话：有错的行会被明确列出，它们不会进库。
 */
export function ImportWizardWorkspace() {
  const { session } = useIdentity();
  const writable = session.permissions.includes('catalog.spu.update');

  const [templates, setTemplates] = useState<ImportTemplateRow[]>([]);
  const [entityFilter, setEntityFilter] = useState('');
  const [loadingTemplates, setLoadingTemplates] = useState(true);
  const [templateError, setTemplateError] = useState('');

  const [templateId, setTemplateId] = useState('');
  const [template, setTemplate] = useState<ImportTemplateDetail | null>(null);
  const [importMode, setImportMode] = useState<string>('MATCH_BY_CODE');
  const [mappings, setMappings] = useState<ImportFieldMappingRow[]>([]);

  const [job, setJob] = useState<ImportJobRow | null>(null);
  const [headers, setHeaders] = useState<string[]>([]);
  const [mapping, setMapping] = useState<Record<string, string>>({});
  const [mappingName, setMappingName] = useState('');

  const [rows, setRows] = useState<ImportJobRowPage | null>(null);
  const [rowPage, setRowPage] = useState(0);
  const [rowOutcome, setRowOutcome] = useState('');
  const [errors, setErrors] = useState<ImportRowErrorPage | null>(null);
  const [errorPage, setErrorPage] = useState(0);
  const [severity, setSeverity] = useState('');
  const [showAllRows, setShowAllRows] = useState(false);
  const [showAllErrors, setShowAllErrors] = useState(false);

  const [busy, setBusy] = useState(false);
  const [notice, setNotice] = useState('');
  const [actionError, setActionError] = useState('');
  const [templateEditor, setTemplateEditor] = useState<TemplateInput | null>(null);

  // 轮询句柄：任务在后台跑时用它定期刷新，任务一到终态就停。
  const pollRef = useRef<ReturnType<typeof setInterval> | null>(null);

  const loadTemplates = useCallback(async () => {
    setLoadingTemplates(true);
    setTemplateError('');
    try {
      const page = await fetchTemplates({
        targetEntity: entityFilter || null,
        keyword: null,
        page: 0,
        size: 100,
      });
      setTemplates(page.items);
    } catch (e) {
      setTemplateError(errorText(e));
    } finally {
      setLoadingTemplates(false);
    }
  }, [entityFilter]);

  useEffect(() => {
    void loadTemplates();
  }, [loadTemplates]);

  const loadTemplate = useCallback(
    async (id: string) => {
      if (!id) {
        setTemplate(null);
        return;
      }
      const detail = await fetchTemplate(id);
      setTemplate(detail);
      // 默认映射 = 字段键与列头同名的那部分；剩下的由运营在映射步骤里选。
      const initial: Record<string, string> = {};
      for (const c of detail.columns) {
        if (headers.includes(c.header)) initial[c.fieldKey] = c.header;
        else if (headers.includes(c.fieldKey)) initial[c.fieldKey] = c.fieldKey;
      }
      setMapping((prev) => ({ ...initial, ...prev }));
    },
    [headers],
  );

  useEffect(() => {
    if (!templateId) {
      setTemplate(null);
      return;
    }
    void loadTemplate(templateId).catch((e) => setActionError(errorText(e)));
  }, [templateId, loadTemplate]);

  const loadMappings = useCallback(async (id: string) => {
    if (!id) {
      setMappings([]);
      return;
    }
    try {
      const page = await fetchMappings(id);
      setMappings(page.items);
    } catch {
      setMappings([]);
    }
  }, []);

  useEffect(() => {
    void loadMappings(templateId);
  }, [templateId, loadMappings]);

  /** 任务详情的所有读都走这里，轮询与手动刷新共用同一条路径。 */
  const loadJob = useCallback(
    async (id: string, page = rowPage, outcome = rowOutcome) => {
      const [detail, rowPage_, errorPage_] = await Promise.all([
        fetchJob(id),
        fetchRows(id, outcome || null, page, ROW_PAGE_SIZE),
        fetchErrors(id, severity || null, showAllErrors ? 0 : errorPage, 50),
      ]);
      setJob(detail);
      // headers 只在上传那一步设置一次：它来自上传回执，之后每次轮询都重置
      // 会让「文件里有哪几列」在映射步骤上凭空消失。
      setRows(rowPage_);
      setErrors(errorPage_);
      return detail;
    },
    [rowPage, rowOutcome, errorPage, severity, showAllErrors],
  );

  /**
   * 轮询：只在任务**还在跑**时开，一到终态立刻停。
   *
   * <p>停在这里而不是在 effect 的清理里，是因为「谁启动谁负责停」：
   * 否则用户切换到别的任务之后，旧任务的定时器还会继续改新任务的界面状态。
   */
  const startPolling = useCallback(
    (id: string) => {
      if (pollRef.current) clearInterval(pollRef.current);
      pollRef.current = setInterval(() => {
        void (async () => {
          try {
            const detail = await loadJob(id);
            if (isSettled(detail.status) && pollRef.current) {
              clearInterval(pollRef.current);
              pollRef.current = null;
            }
          } catch {
            /* 轮询失败不弹错误：它只是没刷到，下一次会再试。 */
          }
        })();
      }, 1500);
    },
    [loadJob],
  );

  useEffect(
    () => () => {
      if (pollRef.current) clearInterval(pollRef.current);
    },
    [],
  );

  /** 翻页/筛选变化时重读逐行结果（不重读任务本身）。 */
  const reloadRows = useCallback(async () => {
    if (!job) return;
    try {
      setRows(await fetchRows(job.id, rowOutcome || null, rowPage, ROW_PAGE_SIZE));
    } catch (e) {
      setActionError(errorText(e));
    }
  }, [job, rowOutcome, rowPage]);

  useEffect(() => {
    void reloadRows();
  }, [reloadRows]);

  const reloadErrors = useCallback(async () => {
    if (!job) return;
    try {
      setErrors(await fetchErrors(job.id, severity || null, showAllErrors ? 0 : errorPage, 50));
    } catch (e) {
      setActionError(errorText(e));
    }
  }, [job, severity, errorPage, showAllErrors]);

  useEffect(() => {
    void reloadErrors();
  }, [reloadErrors]);

  async function guard<T>(work: () => Promise<T>, ok?: string): Promise<T | null> {
    setBusy(true);
    setActionError('');
    try {
      const result = await work();
      if (ok) setNotice(ok);
      return result;
    } catch (e) {
      setActionError(errorText(e));
      return null;
    } finally {
      setBusy(false);
    }
  }

  async function doUpload(file: File) {
    if (!templateId) {
      setActionError('先选一个导入模板（第一步），模板决定这份文件按哪些字段校验');
      return;
    }
    // 本地先拦一道：扩展名不是 csv 时直接说清楚原因，
    // 而不是让用户传完 64MB 才被后端拒绝。
    if (!/\.csv$/i.test(file.name)) {
      setActionError(
        `「${file.name}」不是 CSV 文件。当前只支持 CSV，不支持 .xlsx / .xls —— ` +
          '请在 Excel 里「另存为 CSV UTF-8」后重试（要支持 Excel 需要先引入 Apache POI）。',
      );
      return;
    }
    await guard(async () => {
      const summary = await uploadFile(templateId, importMode, file);
      // 上传回执带回了表头 —— 字段映射这一步要靠它。
      setHeaders(summary.headers);
      setRowPage(0);
      setErrorPage(0);
      setMapping({});
      const detail = await loadJob(summary.jobId, 0, '');
      setJob(detail);
      return detail;
    }, '文件已上传。下一步：确认字段映射，然后跑预校验。');
  }

  async function submitMapping() {
    if (!job) return;
    const problem = mappingProblem(template, mapping);
    if (problem) {
      setActionError(problem);
      return;
    }
    await guard(
      async () => setJob(await setJobMapping(job.id, mapping, mappingName || undefined)),
      '字段映射已保存。',
    );
    setMappingName('');
  }

  async function applySavedMapping(row: ImportFieldMappingRow) {
    await guard(async () => {
      setMapping(row.mapping);
      if (!job) return;
      setJob(await setJobMapping(job.id, row.mapping));
    }, `已套用映射「${row.name}」`);
  }

  async function runPrevalidate() {
    if (!job) return;
    await guard(async () => {
      const accepted = await requestPrevalidate(job.id);
      setJob(accepted);
      startPolling(job.id);
    }, '预校验已提交，正在后台逐行校验…');
  }

  async function runExecute() {
    if (!job) return;
    await guard(async () => {
      const accepted = await requestExecute(job.id);
      setJob(accepted);
      startPolling(job.id);
    }, '导入已提交，正在后台写入…');
  }

  async function runCancel() {
    if (!job) return;
    await guard(async () => {
      if (pollRef.current) {
        clearInterval(pollRef.current);
        pollRef.current = null;
      }
      setJob(await cancelJob(job.id));
    }, '任务已取消，互斥槽位已释放，可以开始下一次导入。');
  }

  const missingRequired = useMemo(
    () => unmappedRequiredKeys(template, mapping),
    [template, mapping],
  );
  const ignored = useMemo(
    () => ignoredHeaders(template, headers, mapping),
    [template, headers, mapping],
  );
  const grouped = useMemo(() => groupErrors(errors?.items ?? []), [errors]);
  const busyRunning = job ? isRunning(job.status) : false;

  const stepIndex = (() => {
    if (!job) return 1;
    if (job.status === 'PREVALIDATING' || job.status === 'RUNNING') return 3;
    if (job.status === 'PREVALIDATED') return 3;
    return 4;
  })();

  return (
    <div className="space-y-4">
      {actionError && (
        <div className="border-destructive/40 bg-destructive/5 text-destructive text-copy-13 flex items-start gap-2 rounded-md border px-3 py-2">
          <AlertTriangle className="mt-0.5 size-4 shrink-0" />
          <span>{actionError}</span>
        </div>
      )}
      {notice && (
        <div className="border-border bg-muted/40 text-copy-13 rounded-md border px-3 py-2">
          {notice}
        </div>
      )}

      {/* 五步引导：当前走到第几步由任务的真实状态决定，不是由用户点的按钮决定。 */}
      <div className="border-border bg-card flex flex-wrap gap-1 rounded-lg border p-1">
        {STEPS.map((s, i) => {
          const done = i < stepIndex;
          const current = i === stepIndex;
          return (
            <div
              key={s.key}
              className={
                current
                  ? 'border-primary bg-primary/5 flex min-w-[9rem] flex-1 flex-col rounded-md border px-3 py-2'
                  : 'flex min-w-[9rem] flex-1 flex-col rounded-md px-3 py-2'
              }
            >
              <span className="text-copy-13 flex items-center gap-1.5 font-medium">
                {done ? (
                  <Check className="text-success size-3.5" />
                ) : (
                  <span className="text-muted-foreground text-xs">{i + 1}</span>
                )}
                {s.label}
              </span>
              <span className="text-muted-foreground text-xs">{s.hint}</span>
            </div>
          );
        })}
      </div>

      {/* ------------------------------------------------ 1 下载模板 + 2 上传 */}
      <SectionCard
        title="① 下载模板 → ② 上传文件"
        description={
          <>
            先取一份带列头与示例行的 CSV，照着它填；不同供应商列名不同也没关系，下一步做字段映射。
            <span className="mt-1 block">{FORMAT_HINT}</span>
            <span className="mt-1 block">{QUOTE_BOUNDARY_HINT}</span>
          </>
        }
      >
        <div className="space-y-4">
          <div className="flex flex-wrap items-end gap-3">
            <Field label="导入模板" hint="决定这份文件按哪些字段校验">
              <select
                className={control}
                value={templateId}
                onChange={(e) => setTemplateId(e.target.value)}
                aria-label="导入模板"
              >
                <option value="">选择模板…</option>
                {templates.map((t) => (
                  <option key={t.id} value={t.id}>
                    {t.name}（{t.code} · {entityLabel(t.targetEntity)} · {t.columnCount} 列）
                  </option>
                ))}
              </select>
            </Field>
            <Field label="导入模式" hint={modeHint(importMode)}>
              <select
                className={control}
                value={importMode}
                onChange={(e) => setImportMode(e.target.value)}
                aria-label="导入模式"
              >
                {IMPORT_MODES.map((m) => (
                  <option key={m.value} value={m.value}>
                    {m.label}
                  </option>
                ))}
              </select>
            </Field>
            {writable && (
              <Button variant="outline" onClick={() => setTemplateEditor(emptyEditor())}>
                <Plus className="mr-2 size-4" />
                新建模板
              </Button>
            )}
          </div>

          {loadingTemplates ? (
            <p className="text-muted-foreground text-copy-13 flex items-center gap-2">
              <Loader2 className="size-4 animate-spin" />
              正在读取模板…
            </p>
          ) : templateError ? (
            <div className="border-destructive/40 bg-destructive/5 text-destructive text-copy-13 flex items-start gap-2 rounded-md border px-3 py-2">
              <AlertTriangle className="mt-0.5 size-4 shrink-0" />
              <span>{templateError}</span>
            </div>
          ) : templates.length === 0 ? (
            <EmptyState
              icon={<FileSpreadsheet className="size-7" />}
              title="还没有导入模板"
              description="模板决定这份文件按哪些字段校验（列头、类型、必填、匹配键）。新建一个就会自动带上该对象的标准列。"
            />
          ) : (
            <div className="space-y-2">
              <div className="flex flex-wrap items-center gap-2">
                <Field label="按目标对象筛选">
                  <select
                    className={control}
                    value={entityFilter}
                    onChange={(e) => setEntityFilter(e.target.value)}
                    aria-label="按目标对象筛选"
                  >
                    <option value="">全部</option>
                    {TARGET_ENTITIES.map((e) => (
                      <option key={e.value} value={e.value}>
                        {e.label}
                      </option>
                    ))}
                  </select>
                </Field>
                <Button variant="outline" size="sm" onClick={() => void loadTemplates()}>
                  <RefreshCw className="mr-2 size-4" />
                  刷新
                </Button>
              </div>
              <ul className="border-border divide-border divide-y rounded-md border">
                {templates.map((t) => (
                  <li
                    key={t.id}
                    className="flex flex-wrap items-center justify-between gap-2 px-3 py-2"
                  >
                    <span className="flex items-center gap-2">
                      <span className="font-medium">{t.name}</span>
                      <code className="text-muted-foreground text-xs">{t.code}</code>
                      <Badge tone="neutral">{entityLabel(t.targetEntity)}</Badge>
                      {!t.enabled && <Badge tone="warning">已停用</Badge>}
                    </span>
                    <span className="flex items-center gap-2">
                      <Button
                        variant="ghost"
                        size="sm"
                        onClick={() => window.open(templateFileUrl(t.id))}
                      >
                        <Download className="mr-2 size-4" />
                        下载模板
                      </Button>
                      {writable && t.id === templateId && (
                        <Button
                          variant="ghost"
                          size="sm"
                          onClick={() =>
                            void guard(async () => {
                              await deleteTemplate(t.id);
                              if (t.id === templateId) setTemplateId('');
                              await loadTemplates();
                            }, '模板已删除')
                          }
                        >
                          <Trash2 className="mr-2 size-4" />
                          删除
                        </Button>
                      )}
                    </span>
                  </li>
                ))}
              </ul>
            </div>
          )}

          {templateId && (
            <div className="border-border flex flex-wrap items-center gap-2 rounded-md border px-3 py-2">
              <span className="text-muted-foreground text-copy-13">
                模板共 {template?.columns.length ?? 0} 列，必填{' '}
                {template?.columns.filter((c) => c.required).length ?? 0} 列。
              </span>
              <Button
                variant="secondary"
                size="sm"
                onClick={() => window.open(templateFileUrl(templateId))}
              >
                <Download className="mr-2 size-4" />
                下载这份模板的 CSV
              </Button>
            </div>
          )}

          {/* 上传：真文件通道，带上文件名与大小（那是「导进去的是哪份文件」的凭据） */}
          <label className="border-border hover:border-primary/60 flex cursor-pointer flex-col items-center gap-2 rounded-md border border-dashed px-4 py-6 text-center">
            <Upload className="text-muted-foreground size-6" />
            <span className="text-copy-13 font-medium">
              选择 CSV 文件（{importMode ? modeLabel(importMode) : ''}模式）
            </span>
            <span className="text-muted-foreground text-xs">
              上传后只会解析并逐行存下**原始文本**，一个商品都还没写进库 —— 真正落库要等第 5 步。
            </span>
            <input
              type="file"
              accept=".csv,text/csv"
              className="sr-only"
              disabled={!writable || !templateId}
              onChange={(e) => {
                const file = e.target.files?.[0];
                e.target.value = '';
                if (file) void doUpload(file);
              }}
            />
          </label>
        </div>
      </SectionCard>

      {/* ------------------------------------------------ 3 字段映射 */}
      {job && (
        <SectionCard
          title="③ 字段映射"
          description="把上传文件的列头对上模板的字段。可以存成具名映射，下次同供应商的文件直接套。"
        >
          <div className="space-y-4">
            {headers.length === 0 ? (
              <p className="text-muted-foreground text-copy-13">
                文件列头在上传时已按字段名自动匹配了一部分，下面的下拉就是确认或改选。
              </p>
            ) : (
              <p className="text-muted-foreground text-copy-13">文件里的列：{headers.join('、')}</p>
            )}
            <ul className="border-border divide-border divide-y rounded-md border">
              {(template?.columns ?? []).map((c) => (
                <li key={c.fieldKey} className="flex flex-wrap items-center gap-3 px-3 py-2">
                  <span className="min-w-[12rem] flex-1">
                    <span className="font-medium">{c.header}</span>
                    <code className="text-muted-foreground ml-2 text-xs">{c.fieldKey}</code>
                    {c.required && (
                      <Badge tone="warning" className="ml-2">
                        必填
                      </Badge>
                    )}
                    {c.matchKey && (
                      <Badge tone="neutral" className="ml-2">
                        匹配键
                      </Badge>
                    )}
                    <span className="text-muted-foreground block text-xs">{c.help}</span>
                  </span>
                  <select
                    className={`${control} max-w-xs`}
                    value={mapping[c.fieldKey] ?? ''}
                    aria-label={`${c.header} 对应哪一列`}
                    onChange={(e) =>
                      setMapping((prev) => {
                        const next = { ...prev };
                        if (e.target.value) next[c.fieldKey] = e.target.value;
                        else delete next[c.fieldKey];
                        return next;
                      })
                    }
                  >
                    <option value="">（不导入这一列）</option>
                    {headers.map((h) => (
                      <option key={h} value={h}>
                        {h}
                      </option>
                    ))}
                  </select>
                </li>
              ))}
            </ul>

            {ignored.length > 0 && (
              <p className="text-muted-foreground text-xs">
                文件里这些列不会被导入（没有对应到任何模板字段）：{ignored.join('、')}
              </p>
            )}
            {missingRequired.length > 0 && (
              <p className="text-destructive text-copy-13 flex items-start gap-2">
                <AlertTriangle className="mt-0.5 size-4 shrink-0" />
                <span>还有必填列没对应到文件列：{missingRequired.join('、')}</span>
              </p>
            )}

            {mappings.length > 0 && (
              <div className="space-y-2">
                <span className="text-copy-13 font-medium">已保存的映射（可直接套用）</span>
                <div className="flex flex-wrap gap-2">
                  {mappings.map((m) => (
                    <span
                      key={m.id}
                      className="border-border flex items-center gap-1 rounded border px-2 py-1 text-xs"
                    >
                      <button className="underline" onClick={() => void applySavedMapping(m)}>
                        {m.name}
                      </button>
                      {writable && (
                        <button
                          aria-label={`删除映射 ${m.name}`}
                          onClick={() =>
                            void guard(async () => {
                              await deleteMapping(m.id);
                              await loadMappings(templateId);
                            }, '映射已删除')
                          }
                        >
                          <Trash2 className="size-3" />
                        </button>
                      )}
                    </span>
                  ))}
                </div>
              </div>
            )}

            {writable && (
              <div className="flex flex-wrap items-end gap-3">
                <Field label="顺便存成可复用映射" hint="留空则只改这一次任务">
                  <input
                    className={control}
                    value={mappingName}
                    placeholder="例如：1688 供应商表"
                    onChange={(e) => setMappingName(e.target.value)}
                  />
                </Field>
                <Button onClick={() => void submitMapping()} disabled={busy || !writable}>
                  <Save className="mr-2 size-4" />
                  保存映射
                </Button>
              </div>
            )}
          </div>
        </SectionCard>
      )}

      {/* ------------------------------------------------ 4 预校验 / 5 执行 + 结果统计 */}
      {job && (
        <SectionCard
          title="④ 预校验 → ⑤ 执行"
          description="预校验只标错、不写库；执行是异步任务，大文件不阻塞页面。提交后本页会持续轮询真实进度。"
          extra={
            <div className="flex flex-wrap gap-2">
              {writable && job.status === 'PENDING' && (
                <Button size="sm" onClick={() => void runPrevalidate()} disabled={busy}>
                  <ClipboardList className="mr-2 size-4" />
                  开始预校验
                </Button>
              )}
              {writable && job.status === 'PREVALIDATED' && (
                <Button size="sm" onClick={() => void runExecute()} disabled={busy}>
                  <ArrowRight className="mr-2 size-4" />
                  执行导入（只写校验通过的行）
                </Button>
              )}
              {writable && (job.status === 'PENDING' || job.status === 'PREVALIDATED') && (
                <Button
                  size="sm"
                  variant="outline"
                  onClick={() => void runCancel()}
                  disabled={busy}
                >
                  <Ban className="mr-2 size-4" />
                  取消任务
                </Button>
              )}
              <Button size="sm" variant="outline" onClick={() => void loadJob(job.id)}>
                <RefreshCw className="mr-2 size-4" />
                刷新
              </Button>
            </div>
          }
        >
          <div className="space-y-4">
            {/* 结果统计：四段计数直接读库里可查的事实，页面上不重算 */}
            <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-5">
              <StatCard label="文件行数" value={job.rowCount} />
              <StatCard label="校验通过" value={job.validCount} />
              <StatCard
                label="校验不通过"
                value={job.invalidCount}
                comparisonLabel="这些行不会落库"
              />
              <StatCard label="已导入" value={job.appliedCount} />
              <StatCard
                label="跳过 / 失败"
                value={job.skippedCount + job.failedCount}
                comparisonLabel={`跳过 ${job.skippedCount} · 失败 ${job.failedCount}`}
              />
            </div>

            <div className="flex flex-wrap items-center gap-3">
              <Badge tone={statusTone(job.status)}>{statusLabel(job.status)}</Badge>
              <span className="text-muted-foreground text-copy-13">
                {job.fileName}（{job.fileFormat}）· {progressText(job)}
              </span>
              {busyRunning && (
                <span className="text-muted-foreground text-copy-13 flex items-center gap-1">
                  <Loader2 className="size-3.5 animate-spin" />
                  后台运行中
                </span>
              )}
            </div>

            {busyRunning && (
              <div className="bg-muted h-2 w-full overflow-hidden rounded">
                <div
                  className="bg-primary h-full transition-[width]"
                  style={{ width: `${progressPercent(job)}%` }}
                />
              </div>
            )}

            {job.error && (
              <div className="border-destructive/40 bg-destructive/5 text-destructive text-copy-13 rounded-md border px-3 py-2">
                {job.error}
              </div>
            )}

            {/* 红线：3.8.1.2 蓝图「校验失败的行不落库，不允许尽力导入产生半截数据」 */}
            <p className="border-border bg-muted/40 text-copy-13 rounded-md border px-3 py-2">
              {executeHint(job)}
            </p>

            <p className="text-muted-foreground text-xs">{REPORT_BOUNDARY_HINT}</p>

            {/* 逐行错误摘要：报告页归 3.8.1.2，这里给到「哪一行、哪一列、什么级别」。 */}
            <div className="space-y-2">
              <div className="flex flex-wrap items-center justify-between gap-2">
                <span className="text-copy-14 font-medium">
                  错误明细（必须修正 {errors?.errorCount ?? 0} · 可跳过 {errors?.warningCount ?? 0}
                  ）
                </span>
                <div className="flex items-center gap-2">
                  <select
                    className={`${control} max-w-[12rem]`}
                    value={severity}
                    aria-label="按级别筛选"
                    onChange={(e) => {
                      setSeverity(e.target.value);
                      setErrorPage(0);
                    }}
                  >
                    <option value="">全部级别</option>
                    <option value="ERROR">必须修正（不落库）</option>
                    <option value="WARNING">可跳过</option>
                  </select>
                </div>
              </div>
              {!errors || errors.total === 0 ? (
                <EmptyState
                  icon={<Check className="size-7" />}
                  title="没有错误"
                  description={
                    job.status === 'PENDING'
                      ? '还没有跑预校验。点「开始预校验」逐行逐列挑错。'
                      : '这次文件里没有必填缺失、格式错误、引用不存在或编码重复。'
                  }
                />
              ) : (
                <>
                  <ul className="border-border divide-border divide-y rounded-md border">
                    {grouped.map((g) => (
                      <li key={g.rowNo} className="space-y-1 px-3 py-2">
                        <div className="flex items-center gap-2">
                          <span className="font-medium">第 {g.rowNo} 行</span>
                          <Badge tone="neutral">{g.errorCount} 个问题</Badge>
                        </div>
                        <ul className="space-y-0.5">
                          {g.items.map((it, i) => (
                            <li
                              key={`${it.id}-${i}`}
                              className="flex flex-wrap items-baseline gap-2 text-xs"
                            >
                              <Badge tone={it.severity === 'ERROR' ? 'warning' : 'neutral'}>
                                {it.severity === 'ERROR' ? '必须修正' : '可跳过'}
                              </Badge>
                              <code className="text-muted-foreground">{it.fieldKey || '整行'}</code>
                              <span className="text-muted-foreground">{it.message}</span>
                            </li>
                          ))}
                        </ul>
                      </li>
                    ))}
                  </ul>
                  <Pager
                    total={errors.total}
                    page={errorPage}
                    size={50}
                    onPage={setErrorPage}
                    onShowAll={showAllErrors ? undefined : () => setShowAllErrors(true)}
                    expanded={showAllErrors}
                  />
                </>
              )}
            </div>

            {/* 逐行结果 */}
            <div className="space-y-2">
              <div className="flex flex-wrap items-center justify-between gap-2">
                <span className="text-copy-14 font-medium">逐行结果</span>
                <select
                  className={`${control} max-w-[12rem]`}
                  value={rowOutcome}
                  aria-label="按结论筛选"
                  onChange={(e) => {
                    setRowOutcome(e.target.value);
                    setRowPage(0);
                  }}
                >
                  <option value="">全部</option>
                  {['PENDING', 'VALID', 'INVALID', 'IMPORTED', 'SKIPPED', 'FAILED'].map((o) => (
                    <option key={o} value={o}>
                      {outcomeLabel(o)}
                    </option>
                  ))}
                </select>
              </div>
              {!rows || rows.items.length === 0 ? (
                <EmptyState
                  icon={<Table2 className="size-7" />}
                  title="还没有逐行结果"
                  description="预校验结束后，每一行在这里占一行：它变成了哪条商品记录，或为什么没进来。"
                />
              ) : (
                <>
                  <ul className="border-border divide-border divide-y rounded-md border">
                    {rows.items.map((r) => (
                      <li
                        key={r.id}
                        className="text-copy-13 flex flex-wrap items-center gap-2 px-3 py-2"
                      >
                        <span className="w-20 font-medium">第 {r.rowNo} 行</span>
                        <Badge tone={outcomeTone(r.outcome)}>{outcomeLabel(r.outcome)}</Badge>
                        {r.errorCount > 0 && (
                          <span className="text-muted-foreground">{r.errorCount} 个问题</span>
                        )}
                        <span className="text-muted-foreground truncate">
                          {Object.values(r.values).filter(Boolean).join(' · ') || '（全空）'}
                        </span>
                      </li>
                    ))}
                  </ul>
                  <Pager
                    total={rows.total}
                    page={rowPage}
                    size={ROW_PAGE_SIZE}
                    onPage={setRowPage}
                    onShowAll={showAllRows ? undefined : () => setShowAllRows(true)}
                    expanded={showAllRows}
                  />
                </>
              )}
            </div>
          </div>
        </SectionCard>
      )}

      {/* ------------------------------------------------ 任务历史 */}
      <SectionCard
        title="导入任务"
        description="同一租户同时只允许一个同类导入任务（数据库层约束，不是前端禁用按钮）。任务到终态后槽位自动释放。"
      >
        <JobHistory onOpen={(id) => void loadJob(id, 0, '').then((d) => setJob(d))} />
      </SectionCard>

      {templateEditor && (
        <TemplateEditor
          input={templateEditor}
          onClose={() => setTemplateEditor(null)}
          onSubmit={async (input) => {
            const created = await guard(
              async () => createTemplate(toTemplateInput(input)),
              '模板已创建（已带上该目标对象的标准列）',
            );
            if (created) {
              setTemplateEditor(null);
              await loadTemplates();
              setTemplateId(created.template.id);
            }
          }}
        />
      )}
    </div>
  );
}

function emptyEditor(): TemplateInput {
  return { code: '', name: '', targetEntity: 'SPU', note: '', enabled: true };
}

/** 模板新建弹窗。 */
function TemplateEditor({
  input,
  onClose,
  onSubmit,
}: {
  input: TemplateInput;
  onClose: () => void;
  onSubmit: (input: TemplateInput) => Promise<void>;
}) {
  const [draft, setDraft] = useState(input);
  const [problem, setProblem] = useState('');
  const [busy, setBusy] = useState(false);
  return (
    <Editor
      title="新建导入模板"
      description="模板决定这份文件按哪些字段校验。列不填时自动带上该目标对象的标准列（列头、类型、必填、匹配键）。"
      close={onClose}
    >
      <div className="space-y-3">
        <Field label="模板编码" hint="租户内唯一，例如 SPU-STD">
          <input
            className={control}
            value={draft.code}
            onChange={(e) => setDraft({ ...draft, code: e.target.value })}
          />
        </Field>
        <Field label="模板名称">
          <input
            className={control}
            value={draft.name}
            onChange={(e) => setDraft({ ...draft, name: e.target.value })}
          />
        </Field>
        <Field label="目标对象" hint="决定执行阶段往哪张表写">
          <select
            className={control}
            value={draft.targetEntity}
            onChange={(e) => setDraft({ ...draft, targetEntity: e.target.value })}
          >
            {TARGET_ENTITIES.map((e) => (
              <option key={e.value} value={e.value}>
                {e.label} —— {e.hint}
              </option>
            ))}
          </select>
        </Field>
        <Field label="备注">
          <textarea
            className={control}
            rows={2}
            value={draft.note}
            onChange={(e) => setDraft({ ...draft, note: e.target.value })}
          />
        </Field>
        <p className="text-muted-foreground text-xs">{QUOTE_BOUNDARY_HINT}</p>
        {problem && <p className="text-destructive text-copy-13">{problem}</p>}
        <div className="flex justify-end gap-2">
          <Button variant="outline" onClick={onClose}>
            取消
          </Button>
          <Button
            onClick={() => {
              const p = templateProblem(draft);
              if (p) {
                setProblem(p);
                return;
              }
              setBusy(true);
              void onSubmit(draft).finally(() => setBusy(false));
            }}
            disabled={busy}
          >
            {busy ? '创建中…' : '创建'}
          </Button>
        </div>
      </div>
    </Editor>
  );
}

/** 任务列表：最近的任务在最上面，这是最常见的查看动机。 */
function JobHistory({ onOpen }: { onOpen: (id: string) => void }) {
  const [page, setPage] = useState(0);
  const [status, setStatus] = useState('');
  const [data, setData] = useState<ImportJobPage | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');

  const load = useCallback(async () => {
    setLoading(true);
    setError('');
    try {
      setData(await fetchJobs(null, status || null, page, PAGE_SIZE));
    } catch (e) {
      setError(errorText(e));
    } finally {
      setLoading(false);
    }
  }, [page, status]);

  useEffect(() => {
    void load();
  }, [load]);

  if (loading && !data) {
    return (
      <p className="text-muted-foreground text-copy-13 flex items-center gap-2 px-4 py-6">
        <Loader2 className="size-4 animate-spin" />
        正在读取导入任务…
      </p>
    );
  }
  if (error && !data) {
    return (
      <div className="space-y-2 p-4">
        <p className="text-destructive text-copy-13">{error}</p>
        <Button variant="outline" size="sm" onClick={() => void load()}>
          <RefreshCw className="mr-2 size-4" />
          重试
        </Button>
      </div>
    );
  }
  if (!data || data.items.length === 0) {
    return (
      <EmptyState
        icon={<ClipboardList className="size-7" />}
        title="还没有导入任务"
        description="在上面选好模板与模式后上传一个 CSV，这里就会出现它的进度与结果统计。"
      />
    );
  }
  return (
    <div className="space-y-3">
      <div className="flex items-center gap-2 px-4 pt-3">
        <select
          className={`${control} max-w-[12rem]`}
          value={status}
          aria-label="按状态筛选"
          onChange={(e) => {
            setStatus(e.target.value);
            setPage(0);
          }}
        >
          <option value="">全部状态</option>
          {[
            'PENDING',
            'PREVALIDATING',
            'PREVALIDATED',
            'RUNNING',
            'SUCCEEDED',
            'PARTIAL',
            'FAILED',
            'CANCELLED',
          ].map((s) => (
            <option key={s} value={s}>
              {statusLabel(s)}
            </option>
          ))}
        </select>
      </div>
      <ul className="border-border divide-border divide-y">
        {data.items.map((j) => (
          <li key={j.id} className="flex flex-wrap items-center justify-between gap-2 px-4 py-2">
            <span className="flex flex-wrap items-center gap-2">
              <span className="font-medium">{j.fileName}</span>
              <Badge tone="neutral">{entityLabel(j.targetEntity)}</Badge>
              <Badge tone={statusTone(j.status)}>{statusLabel(j.status)}</Badge>
              <span className="text-muted-foreground text-xs">
                {j.rowCount} 行 · 成功 {j.appliedCount} · 跳过 {j.skippedCount} · 失败{' '}
                {j.failedCount}
              </span>
            </span>
            <Button size="sm" variant="outline" onClick={() => onOpen(j.id)}>
              查看
            </Button>
          </li>
        ))}
      </ul>
      <Pager total={data.total} page={page} size={PAGE_SIZE} onPage={setPage} />
    </div>
  );
}

/** 分页条。「全部展开」给的是「我要看全部行」，不是一个分页按钮。 */
function Pager({
  total,
  page,
  size,
  onPage,
  onShowAll,
  expanded,
}: {
  total: number;
  page: number;
  size: number;
  onPage: (page: number) => void;
  onShowAll?: (() => void) | undefined;
  expanded?: boolean;
}) {
  const pages = Math.max(1, Math.ceil(total / size));
  if (pages <= 1 && !onShowAll) return null;
  return (
    <div className="flex flex-wrap items-center gap-2">
      <span className="text-muted-foreground text-xs">
        共 {total} 条 · 第 {Math.min(page + 1, pages)} / {pages} 页
      </span>
      {onPage !== undefined && page > 0 && (
        <Button size="sm" variant="outline" onClick={() => onPage(page - 1)}>
          <ChevronUp className="mr-1 size-4" />
          上一页
        </Button>
      )}
      {page + 1 < pages && (
        <Button size="sm" variant="outline" onClick={() => onPage(page + 1)}>
          下一页
          <ChevronDown className="ml-1 size-4" />
        </Button>
      )}
      {onShowAll && (
        <Button size="sm" variant="ghost" onClick={onShowAll}>
          看全部
        </Button>
      )}
      {expanded && <span className="text-muted-foreground text-xs">已展开前 50 条</span>}
    </div>
  );
}
