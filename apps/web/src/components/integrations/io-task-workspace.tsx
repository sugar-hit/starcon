'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import {
  Badge,
  Button,
  EmptyState,
  Input,
  Notice,
  PageHeader,
  SectionCard,
  Segmented,
  StatCard,
} from '@starcon/ui';
import {
  CircleAlert,
  Download,
  FileSpreadsheet,
  Loader2,
  RefreshCw,
  ScrollText,
  TriangleAlert,
  Upload,
} from 'lucide-react';
import {
  availableDomains,
  cancelTask,
  createExport,
  createImport,
  directionLabel,
  errorCodeText,
  errorReportUrl,
  expiryText,
  fetchCapabilities,
  fetchDomains,
  fetchOverview,
  fetchRowErrors,
  fetchTask,
  fetchTasks,
  fetchTemplate,
  fetchTemplates,
  progressPercent,
  resultFileUrl,
  taskStatusLabel,
  taskStatusTone,
  templateFileUrl,
  unavailableCapabilities,
  unavailableDomains,
  type Tone,
} from './io-task-model';
import type {
  ImportExportCapabilities,
  ImportExportDomainRow,
  ImportExportOverview,
  ImportExportRowErrorRow,
  ImportExportTaskRow,
  ImportExportTemplateDetail,
  ImportExportTemplateRow,
} from '../../../../../packages/api-contracts/src/index';

/**
 * 18.1.3.1「导入导出任务」工作区（卷 18 前端入口 `/integrations/io-tasks`）。
 *
 * 蓝图：「<strong>职责</strong>：统一的数据导入导出通道：任务创建（选择数据域与范围）、异步执行、
 * 进度跟踪、结果下载、失败行报告、模板下载。<strong>输入</strong>：导出/导入请求；文件上传。
 * <strong>输出</strong>：任务记录 + 文件（带有效期）+ 错误报告。<strong>关系</strong>：上游 = 全部业务域；
 * 下游 = 用户本地文件。<strong>大文件一律异步</strong>，并在下载链接上设置有效期，避免存储无限增长。」
 *
 * 🔴 页面上的六条纪律：
 *
 * 1. **「已受理」不是「已完成」**。提交后只显示「任务已受理，文件在后台生成」，
 *    行数与文件要轮询任务详情才拿得到。
 * 2. **没接通的数据域要显式摆出来**（含缺什么、归谁），发起下拉里只列可用的。
 * 3. **读不到 ≠ 没有**：端点读不到时写明原因，**不渲染绿色空态**。
 * 4. **失败行要看到行号与原因**，不是一句「导入失败」。
 * 5. **下载有有效期**：过期后按钮消失并说明「重新发起一次」。
 * 6. **加载 / 空 / 错误三态齐全**：空态要区分「还没有任务」与「筛选条件不对」。
 */

type Tab = 'tasks' | 'domains' | 'templates' | 'capabilities';

/** 语气 → Badge 的 tone。 */
const BADGE_TONE: Record<Tone, 'neutral' | 'success' | 'warning' | 'danger'> = {
  ok: 'success',
  warn: 'warning',
  danger: 'danger',
  muted: 'neutral',
};

export function ImportExportWorkspace() {
  const [tab, setTab] = useState<Tab>('tasks');

  const [capabilities, setCapabilities] = useState<ImportExportCapabilities | null>(null);
  const [overview, setOverview] = useState<ImportExportOverview | null>(null);
  const [domains, setDomains] = useState<ImportExportDomainRow[]>([]);

  const [tasks, setTasks] = useState<ImportExportTaskRow[]>([]);
  const [taskTotal, setTaskTotal] = useState(0);
  const [taskPage, setTaskPage] = useState(0);
  const [taskDirection, setTaskDirection] = useState('');
  const [taskStatus, setTaskStatus] = useState('');
  const [taskDomain, setTaskDomain] = useState('');

  const [selected, setSelected] = useState<ImportExportTaskRow | null>(null);
  const [rowErrors, setRowErrors] = useState<ImportExportRowErrorRow[]>([]);

  const [templates, setTemplates] = useState<ImportExportTemplateRow[]>([]);
  const [templateDetail, setTemplateDetail] = useState<ImportExportTemplateDetail | null>(null);

  // 发起导出
  const [exportDomain, setExportDomain] = useState('');
  const [exportScope, setExportScope] = useState('');
  // 发起导入
  const [importDomain, setImportDomain] = useState('');
  const [importFile, setImportFile] = useState<File | null>(null);

  const [loading, setLoading] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');

  const fail = (e: unknown) => {
    setError(e instanceof Error ? e.message : '导入导出任务暂时读不到，请稍后重试');
  };

  const run = async (work: () => Promise<void>, ok: string) => {
    setBusy(true);
    setError('');
    setNotice('');
    try {
      await work();
      setNotice(ok);
    } catch (e) {
      fail(e);
    } finally {
      setBusy(false);
    }
  };

  // ================================================================ 读取

  const loadTasks = useCallback(async () => {
    const page = await fetchTasks({
      direction: taskDirection || undefined,
      status: taskStatus || undefined,
      domainCode: taskDomain || undefined,
      page: taskPage,
      size: 10,
    });
    setTasks(page.items);
    setTaskTotal(page.total);
  }, [taskDirection, taskStatus, taskDomain, taskPage]);

  const load = useCallback(async () => {
    setLoading(true);
    setError('');
    try {
      const [caps, ov, doms, tmpl] = await Promise.all([
        fetchCapabilities(),
        fetchOverview(),
        fetchDomains(),
        fetchTemplates({ size: 50 }),
      ]);
      setCapabilities(caps);
      setOverview(ov);
      setDomains(doms);
      setTemplates(tmpl.items);
      await loadTasks();
    } catch (e) {
      fail(e);
    } finally {
      setLoading(false);
    }
  }, [loadTasks]);

  useEffect(() => {
    void load();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [loadTasks]);

  // 任务在跑 ⇒ 轮询进度（**这是唯一的进度来源**：页面不自己编进度）。
  useEffect(() => {
    const running = tasks.some((t) => t.status === 'PENDING' || t.status === 'RUNNING');
    if (!running) return;
    const timer = setInterval(() => {
      void loadTasks();
    }, 3000);
    return () => clearInterval(timer);
  }, [tasks, loadTasks]);

  const exportDomains = useMemo(() => availableDomains(domains, 'EXPORT'), [domains]);
  const importDomains = useMemo(() => availableDomains(domains, 'IMPORT'), [domains]);
  const exportUnavailable = useMemo(() => unavailableDomains(domains, 'EXPORT'), [domains]);
  const importUnavailable = useMemo(() => unavailableDomains(domains, 'IMPORT'), [domains]);

  // ================================================================ 动作

  const submitExport = async () => {
    if (!exportDomain) {
      setError('请先选择要导出的数据域');
      return;
    }
    await run(async () => {
      const scope: Record<string, string> = {};
      const key = exportScope.trim();
      if (key) scope.keyword = key;
      await createExport(exportDomain, scope);
      await loadTasks();
    }, '任务已受理。文件在后台生成，稍后刷新任务详情查看行数与下载。');
  };

  const submitImport = async () => {
    if (!importDomain) {
      setError('请先选择要导入的数据域');
      return;
    }
    if (!importFile) {
      setError('请先选择要导入的文件');
      return;
    }
    await run(async () => {
      const content = await importFile.arrayBuffer();
      await createImport(importDomain, importFile.name, content, {});
      setImportFile(null);
      await loadTasks();
    }, '导入任务已受理。逐行校验在后台进行，稍后查看失败行报告。');
  };

  const openTask = async (task: ImportExportTaskRow) => {
    await run(async () => {
      const detail = await fetchTask(task.id);
      setSelected(detail);
      const errors = await fetchRowErrors(task.id, { size: 50 });
      setRowErrors(errors.items);
    }, '');
  };

  const doCancel = async (task: ImportExportTaskRow) => {
    await run(async () => {
      await cancelTask(task.id);
      await loadTasks();
    }, '任务已作废。');
  };

  const openTemplate = async (template: ImportExportTemplateRow) => {
    await run(async () => {
      setTemplateDetail(await fetchTemplate(template.id));
    }, '');
  };

  // ================================================================ 渲染

  return (
    <div className="space-y-4">
      <PageHeader
        title="导入导出任务"
        description="统一的数据导入导出通道：选数据域与范围 → 异步执行 → 进度跟踪 → 结果下载 → 失败行报告。"
      />

      {error ? (
        <Notice tone="danger" title="读不到">
          {error}
          <div className="mt-2">
            <Button size="sm" variant="ghost" onClick={() => void load()}>
              <RefreshCw className="h-3.5 w-3.5" /> 重试
            </Button>
          </div>
        </Notice>
      ) : null}

      {notice ? (
        <Notice tone="success" title="已受理">
          {notice}
        </Notice>
      ) : null}

      {/* ---------------------------------------------------- 概览 */}
      <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
        <StatCard
          label="任务总数"
          value={overview?.totalTasks ?? '—'}
          icon={<ScrollText className="h-4 w-4" />}
        />
        <StatCard label="导出任务" value={overview?.exportTasks ?? '—'} />
        <StatCard label="导入任务" value={overview?.importTasks ?? '—'} />
        <StatCard
          label="失败行"
          value={overview?.rowErrorCount ?? '—'}
          polarity="negative"
          icon={<TriangleAlert className="h-4 w-4" />}
        />
      </div>

      <Segmented
        value={tab}
        onChange={setTab}
        aria-label="导入导出任务分区"
        options={[
          { value: 'tasks', label: '任务与发起' },
          { value: 'domains', label: '数据域目录' },
          { value: 'templates', label: '模板' },
          { value: 'capabilities', label: '能力声明' },
        ]}
      />

      {tab === 'tasks' ? (
        <div className="space-y-4">
          {/* ------------------------------ 发起导出 */}
          <SectionCard
            title="发起导出"
            description="大文件一律异步：提交后任务进入队列，文件在后台生成，用任务详情看真实行数。"
          >
            <div className="flex flex-wrap items-end gap-3">
              <label className="space-y-1">
                <span className="text-label-12 text-muted-foreground">数据域</span>
                <select
                  className="border-border bg-background text-copy-13 h-9 rounded-md border px-2"
                  value={exportDomain}
                  onChange={(e) => setExportDomain(e.target.value)}
                >
                  <option value="">请选择…</option>
                  {exportDomains.map((d) => (
                    <option key={d.code} value={d.code}>
                      {d.label}（{d.ownerModule}）
                    </option>
                  ))}
                </select>
              </label>
              <label className="space-y-1">
                <span className="text-label-12 text-muted-foreground">关键字（可选）</span>
                <Input
                  value={exportScope}
                  placeholder="编码 / 名称 / 端点地址…"
                  onChange={(e) => setExportScope(e.target.value)}
                />
              </label>
              <Button disabled={busy} onClick={() => void submitExport()}>
                {busy ? (
                  <Loader2 className="h-4 w-4 animate-spin" />
                ) : (
                  <Download className="h-4 w-4" />
                )}
                发起导出
              </Button>
            </div>
            {exportUnavailable.length > 0 ? (
              <p className="text-copy-12 text-muted-foreground mt-3">
                暂不可导出的数据域：
                {exportUnavailable.map((d) => `${d.label}（${d.ownerModule}）`).join('、')}
                —— 原因见「数据域目录」。
              </p>
            ) : null}
          </SectionCard>

          {/* ------------------------------ 发起导入 */}
          <SectionCard
            title="发起导入"
            description="上传文件后逐行校验：校验失败的行不落库，逐行进失败行报告，可下载报告 CSV。"
          >
            <div className="flex flex-wrap items-end gap-3">
              <label className="space-y-1">
                <span className="text-label-12 text-muted-foreground">数据域</span>
                <select
                  className="border-border bg-background text-copy-13 h-9 rounded-md border px-2"
                  value={importDomain}
                  onChange={(e) => setImportDomain(e.target.value)}
                >
                  <option value="">请选择…</option>
                  {importDomains.map((d) => (
                    <option key={d.code} value={d.code}>
                      {d.label}（{d.ownerModule}）
                    </option>
                  ))}
                </select>
              </label>
              <label className="space-y-1">
                <span className="text-label-12 text-muted-foreground">文件（CSV）</span>
                <input
                  type="file"
                  accept=".csv,text/csv"
                  className="text-copy-13"
                  onChange={(e) => setImportFile(e.target.files?.[0] ?? null)}
                />
              </label>
              <Button disabled={busy} onClick={() => void submitImport()}>
                {busy ? (
                  <Loader2 className="h-4 w-4 animate-spin" />
                ) : (
                  <Upload className="h-4 w-4" />
                )}
                发起导入
              </Button>
            </div>
            {importUnavailable.length > 0 ? (
              <p className="text-copy-12 text-muted-foreground mt-3">
                暂不可导入的数据域：
                {importUnavailable.map((d) => `${d.label}（${d.ownerModule}）`).join('、')}
                —— 原因见「数据域目录」。
              </p>
            ) : null}
            <p className="text-copy-12 text-muted-foreground mt-2">
              先下载模板再填：模板里的列头就是解析器认的列（见「模板」分区）。
            </p>
          </SectionCard>

          {/* ------------------------------ 任务列表 */}
          <SectionCard
            title="任务记录"
            description="进度与行数都来自任务记录本身（不是页面临时算的）。"
            extra={
              <Button size="sm" variant="ghost" onClick={() => void load()}>
                <RefreshCw className="h-3.5 w-3.5" /> 刷新
              </Button>
            }
            bodyClassName="p-0"
          >
            <div className="flex flex-wrap items-center gap-2 px-5 py-3">
              <select
                className="border-border bg-background text-copy-13 h-8 rounded-md border px-2"
                value={taskDirection}
                onChange={(e) => {
                  setTaskDirection(e.target.value);
                  setTaskPage(0);
                }}
              >
                <option value="">全部方向</option>
                <option value="EXPORT">导出</option>
                <option value="IMPORT">导入</option>
              </select>
              <select
                className="border-border bg-background text-copy-13 h-8 rounded-md border px-2"
                value={taskStatus}
                onChange={(e) => {
                  setTaskStatus(e.target.value);
                  setTaskPage(0);
                }}
              >
                <option value="">全部状态</option>
                {['PENDING', 'RUNNING', 'SUCCEEDED', 'FAILED', 'CANCELLED', 'EXPIRED'].map((s) => (
                  <option key={s} value={s}>
                    {taskStatusLabel(s)}
                  </option>
                ))}
              </select>
              <select
                className="border-border bg-background text-copy-13 h-8 rounded-md border px-2"
                value={taskDomain}
                onChange={(e) => {
                  setTaskDomain(e.target.value);
                  setTaskPage(0);
                }}
              >
                <option value="">全部数据域</option>
                {domains
                  .filter((d, i, all) => all.findIndex((x) => x.code === d.code) === i)
                  .map((d) => (
                    <option key={d.code} value={d.code}>
                      {d.label}
                    </option>
                  ))}
              </select>
            </div>

            {loading && tasks.length === 0 ? (
              <div className="text-copy-13 text-muted-foreground flex items-center gap-2 px-5 py-10">
                <Loader2 className="h-4 w-4 animate-spin" /> 正在读取任务…
              </div>
            ) : tasks.length === 0 ? (
              <EmptyState
                icon={<ScrollText className="h-5 w-5" />}
                title={taskTotal === 0 ? '还没有导入导出任务' : '这个筛选条件下没有任务'}
                description={
                  taskTotal === 0
                    ? '在上方「发起导出」或「发起导入」里选一个数据域开始。'
                    : '换个方向 / 状态 / 数据域再看看。'
                }
              />
            ) : (
              <div
                role="region"
                aria-label="导入导出任务列表"
                tabIndex={0}
                className="overflow-x-auto"
              >
                <table className="text-copy-13 w-full text-left">
                  <thead className="bg-muted/50">
                    <tr>
                      <th className="whitespace-nowrap px-4 py-3 font-medium">单号</th>
                      <th className="whitespace-nowrap px-4 py-3 font-medium">方向</th>
                      <th className="whitespace-nowrap px-4 py-3 font-medium">数据域</th>
                      <th className="whitespace-nowrap px-4 py-3 font-medium">状态</th>
                      <th className="whitespace-nowrap px-4 py-3 font-medium">进度</th>
                      <th className="whitespace-nowrap px-4 py-3 font-medium">下载有效期</th>
                      <th className="whitespace-nowrap px-4 py-3 font-medium">操作</th>
                    </tr>
                  </thead>
                  <tbody>
                    {tasks.map((task) => {
                      const percent = progressPercent(task);
                      const expired = task.status === 'EXPIRED';
                      return (
                        <tr key={task.id} className="border-border border-t">
                          <td className="text-copy-12 px-4 py-3 font-mono">{task.taskNo}</td>
                          <td className="px-4 py-3">{directionLabel(task.direction)}</td>
                          <td className="px-4 py-3">{task.domainLabel}</td>
                          <td className="px-4 py-3">
                            <Badge tone={BADGE_TONE[taskStatusTone(task.status)]}>
                              {taskStatusLabel(task.status)}
                            </Badge>
                            {task.error ? (
                              <p className="text-copy-12 text-destructive mt-1 max-w-xs">
                                {task.error}
                              </p>
                            ) : null}
                          </td>
                          <td className="px-4 py-3">
                            {percent === null ? (
                              <span className="text-muted-foreground">—</span>
                            ) : (
                              <span>
                                {percent}%（{task.processedRows}/{task.totalRows}）
                              </span>
                            )}
                            {task.failedRows > 0 ? (
                              <span className="text-destructive ml-2">
                                失败 {task.failedRows} 行
                              </span>
                            ) : null}
                          </td>
                          <td className="text-copy-12 px-4 py-3">{expiryText(task)}</td>
                          <td className="px-4 py-3">
                            <div className="flex flex-wrap items-center gap-2">
                              <Button size="sm" variant="ghost" onClick={() => void openTask(task)}>
                                详情
                              </Button>
                              {task.status === 'SUCCEEDED' && !expired ? (
                                <a href={resultFileUrl(task.id)} download>
                                  <Button size="sm" variant="ghost">
                                    <Download className="h-3.5 w-3.5" /> 下载
                                  </Button>
                                </a>
                              ) : null}
                              {task.status === 'PENDING' || task.status === 'RUNNING' ? (
                                <Button
                                  size="sm"
                                  variant="ghost"
                                  onClick={() => void doCancel(task)}
                                >
                                  作废
                                </Button>
                              ) : null}
                            </div>
                          </td>
                        </tr>
                      );
                    })}
                  </tbody>
                </table>
              </div>
            )}

            {taskTotal > 10 ? (
              <div className="flex items-center justify-between px-5 py-3">
                <span className="text-copy-12 text-muted-foreground">共 {taskTotal} 条</span>
                <div className="flex gap-2">
                  <Button
                    size="sm"
                    variant="ghost"
                    disabled={taskPage === 0}
                    onClick={() => setTaskPage((p) => Math.max(0, p - 1))}
                  >
                    上一页
                  </Button>
                  <Button
                    size="sm"
                    variant="ghost"
                    disabled={(taskPage + 1) * 10 >= taskTotal}
                    onClick={() => setTaskPage((p) => p + 1)}
                  >
                    下一页
                  </Button>
                </div>
              </div>
            ) : null}
          </SectionCard>

          {/* ------------------------------ 任务详情 */}
          {selected ? (
            <SectionCard
              title={`任务详情 · ${selected.taskNo}`}
              description={`${directionLabel(selected.direction)} · ${selected.domainLabel} · ${taskStatusLabel(selected.status)}`}
              extra={
                <Button size="sm" variant="ghost" onClick={() => setSelected(null)}>
                  关闭
                </Button>
              }
            >
              <dl className="grid gap-3 sm:grid-cols-3">
                <div>
                  <dt className="text-label-12 text-muted-foreground">总行数</dt>
                  <dd className="text-copy-13">{selected.totalRows}</dd>
                </div>
                <div>
                  <dt className="text-label-12 text-muted-foreground">成功 / 失败</dt>
                  <dd className="text-copy-13">
                    {selected.succeededRows} / {selected.failedRows}
                  </dd>
                </div>
                <div>
                  <dt className="text-label-12 text-muted-foreground">范围快照</dt>
                  <dd className="text-copy-12 break-all font-mono">
                    {JSON.stringify(selected.scope)}
                  </dd>
                </div>
              </dl>

              {selected.error ? (
                <Notice tone="danger" title="执行失败">
                  {selected.error}
                </Notice>
              ) : null}

              <div className="mt-4 space-y-2">
                <h4 className="text-label-12 text-muted-foreground">
                  失败行报告（逐行逐列：哪一行、哪一列、为什么）
                </h4>
                {rowErrors.length === 0 ? (
                  <p className="text-copy-13 text-muted-foreground">这个任务没有失败行。</p>
                ) : (
                  <>
                    <div className="overflow-x-auto">
                      <table className="text-copy-13 w-full text-left">
                        <thead className="bg-muted/50">
                          <tr>
                            <th className="px-4 py-2 font-medium">行号</th>
                            <th className="px-4 py-2 font-medium">列</th>
                            <th className="px-4 py-2 font-medium">原始值</th>
                            <th className="px-4 py-2 font-medium">原因</th>
                          </tr>
                        </thead>
                        <tbody>
                          {rowErrors.map((e) => (
                            <tr key={e.id} className="border-border border-t">
                              <td className="px-4 py-2">{e.rowNo}</td>
                              <td className="px-4 py-2">{e.columnName || '（整行）'}</td>
                              <td className="text-copy-12 px-4 py-2 font-mono">
                                {e.rawValue || '—'}
                              </td>
                              <td className="px-4 py-2">{errorCodeText(e.errorCode)}</td>
                            </tr>
                          ))}
                        </tbody>
                      </table>
                    </div>
                    <a href={errorReportUrl(selected.id)} download>
                      <Button size="sm" variant="ghost">
                        <FileSpreadsheet className="h-3.5 w-3.5" /> 下载失败行报告 CSV
                      </Button>
                    </a>
                  </>
                )}
              </div>
            </SectionCard>
          ) : null}
        </div>
      ) : null}

      {tab === 'domains' ? (
        <SectionCard
          title="数据域目录"
          description="「上游 = 全部业务域」按三条通道落地：platform 自有表 / 属主服务授权只读端点 / 属主服务文件型导入作业。没接通的数据域如实列出原因与归属。"
          bodyClassName="p-0"
        >
          <div className="overflow-x-auto">
            <table className="text-copy-13 w-full text-left">
              <thead className="bg-muted/50">
                <tr>
                  <th className="px-4 py-3 font-medium">数据域</th>
                  <th className="px-4 py-3 font-medium">方向</th>
                  <th className="px-4 py-3 font-medium">可用</th>
                  <th className="px-4 py-3 font-medium">来源 / 归属</th>
                  <th className="px-4 py-3 font-medium">说明</th>
                </tr>
              </thead>
              <tbody>
                {domains.map((d) => (
                  <tr key={`${d.code}-${d.direction}`} className="border-border border-t">
                    <td className="px-4 py-3">
                      {d.label}
                      <span className="text-copy-12 text-muted-foreground ml-2">{d.code}</span>
                    </td>
                    <td className="px-4 py-3">{directionLabel(d.direction)}</td>
                    <td className="px-4 py-3">
                      <Badge tone={d.available ? 'success' : 'neutral'}>
                        {d.available ? '已接通' : '未接通'}
                      </Badge>
                    </td>
                    <td className="px-4 py-3">
                      {d.source}
                      <span className="text-copy-12 text-muted-foreground ml-2">
                        {d.ownerModule}
                      </span>
                    </td>
                    <td className="px-4 py-3">
                      {d.available ? d.limitation || '—' : d.reason || '—'}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </SectionCard>
      ) : null}

      {tab === 'templates' ? (
        <SectionCard
          title="导入模板"
          description="模板里的列头就是解析器认的列 —— 模板与解析器共用一份定义，不会各写一套。"
          bodyClassName="p-0"
        >
          <div className="px-5 py-4">
            {templates.length === 0 ? (
              <p className="text-copy-13 text-muted-foreground">正在读取模板…</p>
            ) : (
              <ul className="space-y-2">
                {templates.map((t) => (
                  <li
                    key={t.id}
                    className="border-border flex flex-wrap items-center justify-between gap-2 rounded-md border px-3 py-2"
                  >
                    <div>
                      <span className="text-copy-13 font-medium">{t.name}</span>
                      <span className="text-copy-12 text-muted-foreground ml-2">
                        {t.domainCode} / {t.code}
                      </span>
                    </div>
                    <div className="flex items-center gap-2">
                      <Button size="sm" variant="ghost" onClick={() => void openTemplate(t)}>
                        查看列定义
                      </Button>
                      <a href={templateFileUrl(t.id)} download>
                        <Button size="sm" variant="ghost">
                          <Download className="h-3.5 w-3.5" /> 下载模板
                        </Button>
                      </a>
                    </div>
                  </li>
                ))}
              </ul>
            )}

            {templateDetail ? (
              <div className="mt-4">
                <h4 className="text-label-12 text-muted-foreground">
                  {templateDetail.template.name} 的列定义
                </h4>
                <div className="mt-2 overflow-x-auto">
                  <table className="text-copy-13 w-full text-left">
                    <thead className="bg-muted/50">
                      <tr>
                        <th className="px-4 py-2 font-medium">列头</th>
                        <th className="px-4 py-2 font-medium">字段</th>
                        <th className="px-4 py-2 font-medium">类型</th>
                        <th className="px-4 py-2 font-medium">必填</th>
                        <th className="px-4 py-2 font-medium">示例</th>
                        <th className="px-4 py-2 font-medium">说明</th>
                      </tr>
                    </thead>
                    <tbody>
                      {templateDetail.columns.map((c) => (
                        <tr key={c.fieldKey} className="border-border border-t">
                          <td className="px-4 py-2">{c.header}</td>
                          <td className="text-copy-12 px-4 py-2 font-mono">{c.fieldKey}</td>
                          <td className="px-4 py-2">{c.dataType}</td>
                          <td className="px-4 py-2">{c.required ? '必填' : '选填'}</td>
                          <td className="px-4 py-2">{c.sampleValue || '—'}</td>
                          <td className="px-4 py-2">{c.help || '—'}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              </div>
            ) : null}
          </div>
        </SectionCard>
      ) : null}

      {tab === 'capabilities' ? (
        <div className="space-y-4">
          <SectionCard
            title="能力声明"
            description="哪些真接通、依据是什么、缺在谁身上 —— 没接通的格子如实列出，不藏进空态。"
            bodyClassName="p-0"
          >
            <div className="overflow-x-auto">
              <table className="text-copy-13 w-full text-left">
                <thead className="bg-muted/50">
                  <tr>
                    <th className="px-4 py-3 font-medium">能力</th>
                    <th className="px-4 py-3 font-medium">可用</th>
                    <th className="px-4 py-3 font-medium">依据 / 限制</th>
                  </tr>
                </thead>
                <tbody>
                  {(capabilities?.capabilities ?? []).map((c) => (
                    <tr key={c.name} className="border-border border-t">
                      <td className="px-4 py-3">{c.name}</td>
                      <td className="px-4 py-3">
                        <Badge tone={c.available ? 'success' : 'neutral'}>
                          {c.available ? '已接通' : '未接通'}
                        </Badge>
                      </td>
                      <td className="px-4 py-3">
                        {c.basis}
                        {c.limitation ? (
                          <p className="text-copy-12 text-muted-foreground mt-1">{c.limitation}</p>
                        ) : null}
                        {c.ownerModule ? (
                          <p className="text-copy-12 text-muted-foreground mt-1">
                            归属模块：{c.ownerModule}
                          </p>
                        ) : null}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </SectionCard>

          {unavailableCapabilities(capabilities).length > 0 ? (
            <Notice tone="warning" title="尚未接通的能力">
              <ul className="space-y-1">
                {unavailableCapabilities(capabilities).map((c) => (
                  <li key={c.name}>
                    <CircleAlert className="mr-1 inline h-3.5 w-3.5" />
                    <span className="font-medium">{c.name}</span>：{c.basis}
                    {c.ownerModule ? `（归属 ${c.ownerModule}）` : ''}
                  </li>
                ))}
              </ul>
            </Notice>
          ) : null}
        </div>
      ) : null}
    </div>
  );
}
