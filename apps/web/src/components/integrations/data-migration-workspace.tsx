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
  RotateCcw,
  ScrollText,
  TriangleAlert,
  Upload,
} from 'lucide-react';
import {
  createMapping,
  createRun,
  deleteMapping,
  expireFiles,
  fetchCapabilities,
  fetchMappings,
  fetchOverview,
  fetchRows,
  fetchRun,
  fetchRuns,
  fetchTargets,
  modeLabel,
  modeTone,
  outcomeLabel,
  outcomeTone,
  progressPercent,
  reasonLabel,
  reportBalanced,
  reportDownloadable,
  reportUrl,
  requestRollback,
  rollbackReportDownloadable,
  rollbackReportUrl,
  rollbackStatusLabel,
  rollbackStatusTone,
  rollbackable,
  rowCounts,
  rowRollbackLabel,
  rowRollbackTone,
  runStatusLabel,
  runStatusTone,
  type Tone,
  unavailableCapabilities,
  unmigratableTargets,
} from './data-migration-model';
import type {
  DataMigrationCapabilities,
  DataMigrationMappingRow,
  DataMigrationOverview,
  DataMigrationRowPage,
  DataMigrationRowRow,
  DataMigrationRunRow,
  DataMigrationTargetRow,
} from '../../../../../packages/api-contracts/src/index';

/**
 * 18.1.3.2「数据迁移」工作区（卷 18 前端入口 `/integrations/data-migrations`）。
 *
 * 蓝图：「<strong>职责</strong>：支持从其他 ERP 迁移历史数据：映射配置（外部字段 → 内部模型）、
 * 试运行预览、正式迁移、失败回滚与核对报告。<strong>输入</strong>：源数据文件；字段映射配置。
 * <strong>输出</strong>：迁移任务 + 迁移核对报告（成功/失败/跳过数量与原因）。
 * <strong>关系</strong>：上游 = 18.1.3.1；下游 = 各业务域。
 * <strong>历史数据迁移必须支持试运行</strong>，直接正式跑会产生大量脏数据难以回退。」
 *
 * 🔴 页面上的七条纪律：
 *
 * 1. **「已受理」不是「已完成」**。提交后只显示「任务已受理，逐行判重与写入在后台跑」，
 *    行数与结论要轮询任务详情才拿得到。
 * 2. **试运行是一等选项**，与正式迁移**并列**摆着，默认选试运行 ——
 *    蓝图那句「直接正式跑会产生大量脏数据难以回退」在页面上的落点就是默认值。
 * 3. **没接通的业务域要显式摆出来**（含缺什么、归谁），发起下拉里只列可用的。
 * 4. **核对报告的三个口径要看得见**：成功 / 失败 / 跳过，加起来等于处理过的行数。
 * 5. **逐行结论要有行号与原因**，不是一句「迁移失败」。
 * 6. **回滚是第二次动作**：只有跑完的正式迁移才能回滚，试运行不能回滚。
 * 7. **加载 / 空 / 错误三态齐全**：空态要区分「还没有任务」与「筛选条件不对」。
 */

type Tab = 'runs' | 'mappings' | 'targets' | 'capabilities';

/** 语气 → Badge 的 tone。 */
const BADGE_TONE: Record<Tone, 'neutral' | 'success' | 'warning' | 'danger'> = {
  ok: 'success',
  warn: 'warning',
  danger: 'danger',
  muted: 'neutral',
};

export function DataMigrationWorkspace() {
  const [tab, setTab] = useState<Tab>('runs');

  const [capabilities, setCapabilities] = useState<DataMigrationCapabilities | null>(null);
  const [overview, setOverview] = useState<DataMigrationOverview | null>(null);
  const [targets, setTargets] = useState<DataMigrationTargetRow[]>([]);

  const [runs, setRuns] = useState<DataMigrationRunRow[]>([]);
  const [runTotal, setRunTotal] = useState(0);
  const [runPage, setRunPage] = useState(0);
  const [runMode, setRunMode] = useState('');
  const [runStatus, setRunStatus] = useState('');

  const [selected, setSelected] = useState<DataMigrationRunRow | null>(null);
  const [rows, setRows] = useState<DataMigrationRowRow[]>([]);
  const [rowPage, setRowPage] = useState<DataMigrationRowPage | null>(null);

  const [mappings, setMappings] = useState<DataMigrationMappingRow[]>([]);
  const [mappingTotal, setMappingTotal] = useState(0);
  const [mappingPage, setMappingPage] = useState(0);

  // 发起迁移
  const [mappingId, setMappingId] = useState('');
  const [mode, setMode] = useState<'DRY_RUN' | 'APPLY'>('DRY_RUN');
  const [file, setFile] = useState<File | null>(null);

  // 新建映射配置
  const [newCode, setNewCode] = useState('');
  const [newName, setNewName] = useState('');
  const [newSystem, setNewSystem] = useState('');
  const [newModel, setNewModel] = useState('');

  const [loading, setLoading] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');

  const fail = (e: unknown) => {
    setError(e instanceof Error ? e.message : '数据迁移暂时读不到，请稍后重试');
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

  const loadRuns = useCallback(async () => {
    const page = await fetchRuns({
      mode: runMode || undefined,
      status: runStatus || undefined,
      page: runPage,
      size: 10,
    });
    setRuns(page.items);
    setRunTotal(page.total);
  }, [runMode, runStatus, runPage]);

  const loadMappings = useCallback(async () => {
    const page = await fetchMappings({ page: mappingPage, size: 10 });
    setMappings(page.items);
    setMappingTotal(page.total);
  }, [mappingPage]);

  const load = useCallback(async () => {
    setLoading(true);
    setError('');
    try {
      const [caps, ov, tg] = await Promise.all([
        fetchCapabilities(),
        fetchOverview(),
        fetchTargets(),
      ]);
      setCapabilities(caps);
      setOverview(ov);
      setTargets(tg);
      await Promise.all([loadRuns(), loadMappings()]);
    } catch (e) {
      fail(e);
    } finally {
      setLoading(false);
    }
  }, [loadRuns, loadMappings]);

  useEffect(() => {
    void load();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [loadRuns, loadMappings]);

  // 任务在跑 ⇒ 轮询进度（**这是唯一的进度来源**：页面不自己编进度）。
  useEffect(() => {
    const busy = runs.some((r) => r.status === 'PENDING' || r.status === 'RUNNING');
    if (!busy) return;
    const timer = setInterval(() => {
      void loadRuns();
    }, 3000);
    return () => clearInterval(timer);
  }, [runs, loadRuns]);

  const migratable = useMemo(() => targets.filter((t) => t.fields.length > 0), [targets]);
  const notMigratable = useMemo(() => unmigratableTargets(targets), [targets]);

  // ================================================================ 动作

  const submitRun = async () => {
    if (!mappingId) {
      setError('请先选择一份映射配置');
      return;
    }
    if (!file) {
      setError('请先选择源数据文件（CSV）');
      return;
    }
    await run(async () => {
      const content = await file.arrayBuffer();
      await createRun(mappingId, mode, file.name, content);
      setFile(null);
      await loadRuns();
    }, '迁移任务已受理。逐行判重与写入在后台执行，稍后刷新任务详情看真实行数与结论。');
  };

  const submitMapping = async () => {
    if (!newCode.trim() || !newName.trim() || !newModel) {
      setError('映射编码、映射名称、目标模型都要填');
      return;
    }
    const model = targets.find((t) => t.code === newModel);
    if (!model || model.fields.length === 0) {
      setError('这个目标模型还没接通迁移，建了也跑不起来');
      return;
    }
    await run(async () => {
      // 🔴 **必填字段必须全被映射**：源文件缺了「商品编码」这一列，
      //   运行时会让**每一行**都失败，而根因在配置里 —— 与其跑一遍才发现，不如建时就拒。
      const fields = model.fields.map((f) => ({
        source: f.label,
        target: f.key,
        required: f.required,
        transform: 'TRIM',
        defaultValue: f.defaultValue,
      }));
      await createMapping({
        code: newCode.trim(),
        name: newName.trim(),
        sourceSystem: newSystem.trim(),
        targetModel: newModel,
        fields,
        enabled: true,
        remark: '',
      });
      setNewCode('');
      setNewName('');
      setNewSystem('');
      setNewModel('');
      await loadMappings();
    }, '映射配置已建好。发起迁移时选它。');
  };

  const openRun = async (item: DataMigrationRunRow) => {
    await run(async () => {
      const [detail, page] = await Promise.all([
        fetchRun(item.id),
        fetchRows(item.id, { size: 50 }),
      ]);
      setSelected(detail);
      setRowPage(page);
      setRows(page.items);
    }, '');
  };

  const doRollback = async (item: DataMigrationRunRow) => {
    await run(async () => {
      await requestRollback(item.id);
      await loadRuns();
    }, '回滚已受理。正在去目标库删掉这次迁移建出来的记录（已受理不是已删完，请稍后刷新）。');
  };

  const doDeleteMapping = async (mapping: DataMigrationMappingRow) => {
    await run(async () => {
      await deleteMapping(mapping.id);
      await loadMappings();
    }, '映射配置已删除。');
  };

  const doExpire = async () => {
    await run(async () => {
      await expireFiles();
      await load();
    }, '过期文件已清理（迁移任务记录保留 —— 它是审计事实）。');
  };

  // ================================================================ 渲染

  return (
    <div className="space-y-4">
      <PageHeader
        title="数据迁移"
        description="从其它 ERP 迁移历史数据：映射配置 → 试运行预览 → 正式迁移 → 失败回滚 → 核对报告。"
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
          label="迁移任务"
          value={overview?.totalRuns ?? '—'}
          icon={<ScrollText className="h-4 w-4" />}
        />
        <StatCard label="试运行" value={overview?.dryRunRuns ?? '—'} />
        <StatCard label="正式迁移" value={overview?.applyRuns ?? '—'} />
        <StatCard
          label="已回滚"
          value={overview?.rolledBackRuns ?? '—'}
          icon={<RotateCcw className="h-4 w-4" />}
        />
      </div>

      <Segmented
        value={tab}
        onChange={setTab}
        aria-label="数据迁移分区"
        options={[
          { value: 'runs', label: '迁移任务与发起' },
          { value: 'mappings', label: '映射配置' },
          { value: 'targets', label: '目标模型目录' },
          { value: 'capabilities', label: '能力声明' },
        ]}
      />

      {tab === 'runs' ? (
        <div className="space-y-4">
          {/* ------------------------------ 发起迁移 */}
          <SectionCard
            title="发起迁移"
            description="历史数据迁移必须先试运行：试运行只读不写，正式迁移才真的写目标库。"
          >
            <div className="flex flex-wrap items-end gap-3">
              <label className="space-y-1">
                <span className="text-label-12 text-muted-foreground">映射配置</span>
                <select
                  className="border-border bg-background text-copy-13 h-9 rounded-md border px-2"
                  value={mappingId}
                  onChange={(e) => setMappingId(e.target.value)}
                >
                  <option value="">请选择…</option>
                  {mappings.map((m) => (
                    <option key={m.id} value={m.id}>
                      {m.name}（{m.code} → {m.targetModel}）
                    </option>
                  ))}
                </select>
              </label>

              <div className="space-y-1">
                <span className="text-label-12 text-muted-foreground">模式</span>
                <div className="flex gap-2">
                  <Button
                    size="sm"
                    variant={mode === 'DRY_RUN' ? 'default' : 'ghost'}
                    onClick={() => setMode('DRY_RUN')}
                  >
                    试运行（不写目标库）
                  </Button>
                  <Button
                    size="sm"
                    variant={mode === 'APPLY' ? 'default' : 'ghost'}
                    onClick={() => setMode('APPLY')}
                  >
                    正式迁移（真的写）
                  </Button>
                </div>
              </div>

              <label className="space-y-1">
                <span className="text-label-12 text-muted-foreground">源文件（CSV）</span>
                <input
                  type="file"
                  accept=".csv,text/csv"
                  className="text-copy-13"
                  onChange={(e) => setFile(e.target.files?.[0] ?? null)}
                />
              </label>

              <Button disabled={busy} onClick={() => void submitRun()}>
                {busy ? (
                  <Loader2 className="h-4 w-4 animate-spin" />
                ) : (
                  <Upload className="h-4 w-4" />
                )}
                发起{mode === 'DRY_RUN' ? '试运行' : '正式迁移'}
              </Button>
            </div>

            {mode === 'APPLY' ? (
              <Notice tone="warning" title="正式迁移会真的写目标库">
                正式迁移会把源文件里的数据写进商品库（落库的一律是草稿）。
                建议先用「试运行」跑一遍，确认逐行结论都对得上再正式跑 —— 跑错了可以回滚，
                但回滚只会删掉**这次迁移建出来的**记录。
              </Notice>
            ) : (
              <p className="text-copy-12 text-muted-foreground mt-3">
                试运行只解析与判重，**一个写端点都不调**；跑完会出逐行结论与核对报告。
              </p>
            )}
          </SectionCard>

          {/* ------------------------------ 任务列表 */}
          <SectionCard
            title="迁移任务"
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
                value={runMode}
                onChange={(e) => {
                  setRunMode(e.target.value);
                  setRunPage(0);
                }}
              >
                <option value="">全部模式</option>
                <option value="DRY_RUN">试运行</option>
                <option value="APPLY">正式迁移</option>
              </select>
              <select
                className="border-border bg-background text-copy-13 h-8 rounded-md border px-2"
                value={runStatus}
                onChange={(e) => {
                  setRunStatus(e.target.value);
                  setRunPage(0);
                }}
              >
                <option value="">全部状态</option>
                {['PENDING', 'RUNNING', 'SUCCEEDED', 'FAILED', 'CANCELLED'].map((s) => (
                  <option key={s} value={s}>
                    {runStatusLabel(s)}
                  </option>
                ))}
              </select>
            </div>

            {loading && runs.length === 0 ? (
              <div className="text-copy-13 text-muted-foreground flex items-center gap-2 px-5 py-10">
                <Loader2 className="h-4 w-4 animate-spin" /> 正在读取迁移任务…
              </div>
            ) : runs.length === 0 ? (
              <EmptyState
                icon={<ScrollText className="h-5 w-5" />}
                title={runTotal === 0 ? '还没有迁移任务' : '这个筛选条件下没有任务'}
                description={
                  runTotal === 0
                    ? '先在上方选一份映射配置与源文件，建议从「试运行」开始。'
                    : '换个模式 / 状态再看看。'
                }
              />
            ) : (
              <div role="region" aria-label="迁移任务列表" tabIndex={0} className="overflow-x-auto">
                <table className="text-copy-13 w-full text-left">
                  <thead className="bg-muted/50">
                    <tr>
                      <th className="whitespace-nowrap px-4 py-3 font-medium">单号</th>
                      <th className="whitespace-nowrap px-4 py-3 font-medium">模式</th>
                      <th className="whitespace-nowrap px-4 py-3 font-medium">目标模型</th>
                      <th className="whitespace-nowrap px-4 py-3 font-medium">状态</th>
                      <th className="whitespace-nowrap px-4 py-3 font-medium">核对结论</th>
                      <th className="whitespace-nowrap px-4 py-3 font-medium">回滚</th>
                      <th className="whitespace-nowrap px-4 py-3 font-medium">操作</th>
                    </tr>
                  </thead>
                  <tbody>
                    {runs.map((item) => {
                      const percent = progressPercent(item);
                      const balanced = reportBalanced(item);
                      return (
                        <tr key={item.id} className="border-border border-t">
                          <td className="text-copy-12 px-4 py-3 font-mono">{item.runNo}</td>
                          <td className="px-4 py-3">
                            <Badge tone={BADGE_TONE[modeTone(item.mode)]}>
                              {modeLabel(item.mode)}
                            </Badge>
                          </td>
                          <td className="px-4 py-3">{item.targetModel}</td>
                          <td className="px-4 py-3">
                            <Badge tone={BADGE_TONE[runStatusTone(item.status)]}>
                              {runStatusLabel(item.status)}
                            </Badge>
                            {item.error ? (
                              <p className="text-copy-12 text-destructive mt-1 max-w-xs">
                                {item.error}
                              </p>
                            ) : null}
                          </td>
                          <td className="px-4 py-3">
                            <span>
                              成功 {item.succeededRows} / 失败 {item.failedRows} / 跳过{' '}
                              {item.skippedRows}
                            </span>
                            <span className="text-copy-12 text-muted-foreground ml-2">
                              （共 {item.processedRows} 行{percent === null ? '' : `，${percent}%`}
                              ）
                            </span>
                            {!balanced ? (
                              <p className="text-copy-12 text-destructive mt-1">
                                三个口径对不上处理过的行数 —— 这份核对报告在账上不平，别拿它对账。
                              </p>
                            ) : null}
                          </td>
                          <td className="px-4 py-3">
                            <Badge tone={BADGE_TONE[rollbackStatusTone(item.rollbackStatus)]}>
                              {rollbackStatusLabel(item.rollbackStatus)}
                            </Badge>
                            {item.rolledBackRows > 0 ? (
                              <span className="text-copy-12 text-muted-foreground ml-2">
                                删了 {item.rolledBackRows} 条
                              </span>
                            ) : null}
                          </td>
                          <td className="px-4 py-3">
                            <div className="flex flex-wrap items-center gap-2">
                              <Button size="sm" variant="ghost" onClick={() => void openRun(item)}>
                                详情
                              </Button>
                              {reportDownloadable(item) ? (
                                <a href={reportUrl(item.id)} download>
                                  <Button size="sm" variant="ghost">
                                    <Download className="h-3.5 w-3.5" /> 核对报告
                                  </Button>
                                </a>
                              ) : null}
                              {rollbackReportDownloadable(item) ? (
                                <a href={rollbackReportUrl(item.id)} download>
                                  <Button size="sm" variant="ghost">
                                    <FileSpreadsheet className="h-3.5 w-3.5" /> 回滚报告
                                  </Button>
                                </a>
                              ) : null}
                              {rollbackable(item) ? (
                                <Button
                                  size="sm"
                                  variant="ghost"
                                  onClick={() => void doRollback(item)}
                                >
                                  <RotateCcw className="h-3.5 w-3.5" /> 回滚
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

            {runTotal > 10 ? (
              <div className="flex items-center justify-between px-5 py-3">
                <span className="text-copy-12 text-muted-foreground">共 {runTotal} 条</span>
                <div className="flex gap-2">
                  <Button
                    size="sm"
                    variant="ghost"
                    disabled={runPage === 0}
                    onClick={() => setRunPage((p) => Math.max(0, p - 1))}
                  >
                    上一页
                  </Button>
                  <Button
                    size="sm"
                    variant="ghost"
                    disabled={(runPage + 1) * 10 >= runTotal}
                    onClick={() => setRunPage((p) => p + 1)}
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
              title={`迁移详情 · ${selected.runNo}`}
              description={`${modeLabel(selected.mode)} · ${selected.targetModel} · ${runStatusLabel(selected.status)}`}
              extra={
                <Button size="sm" variant="ghost" onClick={() => setSelected(null)}>
                  关闭
                </Button>
              }
            >
              <dl className="grid gap-3 sm:grid-cols-4">
                <div>
                  <dt className="text-label-12 text-muted-foreground">总行数</dt>
                  <dd className="text-copy-13">{selected.totalRows}</dd>
                </div>
                <div>
                  <dt className="text-label-12 text-muted-foreground">成功 / 失败 / 跳过</dt>
                  <dd className="text-copy-13">
                    {selected.succeededRows} / {selected.failedRows} / {selected.skippedRows}
                  </dd>
                </div>
                <div>
                  <dt className="text-label-12 text-muted-foreground">源文件</dt>
                  <dd className="text-copy-12 break-all font-mono">{selected.sourceFilename}</dd>
                </div>
                <div>
                  <dt className="text-label-12 text-muted-foreground">映射配置</dt>
                  <dd className="text-copy-12 break-all font-mono">{selected.mappingCode}</dd>
                </div>
              </dl>

              {selected.error ? (
                <Notice tone="danger" title="执行失败">
                  {selected.error}
                </Notice>
              ) : null}

              {selected.mode === 'DRY_RUN' ? (
                <Notice tone="info" title="这是试运行">
                  试运行**没有写任何业务数据** —— 目标库里一个记录都没多。
                  这里的「成功」是「这一行可以迁成」的预判，不是「已经迁进去了」。
                </Notice>
              ) : null}

              <div className="mt-4 space-y-2">
                <h4 className="text-label-12 text-muted-foreground">
                  逐行结论（哪一行、什么结论、为什么）
                </h4>
                {rows.length === 0 ? (
                  <p className="text-copy-13 text-muted-foreground">这个任务还没有逐行结论。</p>
                ) : (
                  <>
                    <p className="text-copy-12 text-muted-foreground">
                      共 {rowPage ? rowPage.total : rows.length} 行 · 成功{' '}
                      {rowCounts(rowPage).succeeded} / 失败 {rowCounts(rowPage).failed} / 跳过{' '}
                      {rowCounts(rowPage).skipped}
                    </p>
                    <div className="overflow-x-auto">
                      <table className="text-copy-13 w-full text-left">
                        <thead className="bg-muted/50">
                          <tr>
                            <th className="px-4 py-2 font-medium">行号</th>
                            <th className="px-4 py-2 font-medium">结论</th>
                            <th className="px-4 py-2 font-medium">目标编码</th>
                            <th className="px-4 py-2 font-medium">原因</th>
                            <th className="px-4 py-2 font-medium">上游记录 id</th>
                            <th className="px-4 py-2 font-medium">回滚结论</th>
                          </tr>
                        </thead>
                        <tbody>
                          {rows.map((r) => (
                            <tr key={r.id} className="border-border border-t">
                              <td className="px-4 py-2">{r.rowNo}</td>
                              <td className="px-4 py-2">
                                <Badge tone={BADGE_TONE[outcomeTone(r.outcome)]}>
                                  {outcomeLabel(r.outcome)}
                                </Badge>
                              </td>
                              <td className="text-copy-12 px-4 py-2 font-mono">
                                {r.targetCode || '—'}
                              </td>
                              <td className="px-4 py-2">
                                <span>{reasonLabel(r.reasonCode)}</span>
                                {r.message ? (
                                  <span className="text-copy-12 text-muted-foreground ml-2">
                                    {r.message}
                                  </span>
                                ) : null}
                              </td>
                              <td className="text-copy-12 px-4 py-2 font-mono">
                                {r.upstreamId || '—'}
                              </td>
                              <td className="px-4 py-2">
                                <Badge tone={BADGE_TONE[rowRollbackTone(r.rollbackStatus)]}>
                                  {rowRollbackLabel(r.rollbackStatus)}
                                </Badge>
                                {r.rollbackMessage ? (
                                  <span className="text-copy-12 text-muted-foreground ml-2">
                                    {r.rollbackMessage}
                                  </span>
                                ) : null}
                              </td>
                            </tr>
                          ))}
                        </tbody>
                      </table>
                    </div>
                    {reportDownloadable(selected) ? (
                      <a href={reportUrl(selected.id)} download>
                        <Button size="sm" variant="ghost">
                          <FileSpreadsheet className="h-3.5 w-3.5" /> 下载核对报告 CSV
                        </Button>
                      </a>
                    ) : null}
                  </>
                )}
              </div>
            </SectionCard>
          ) : null}
        </div>
      ) : null}

      {tab === 'mappings' ? (
        <div className="space-y-4">
          <SectionCard
            title="新建映射配置"
            description="外部列名 → 内部字段。源文件的列必须与这里的「外部列名」逐字一致。"
          >
            <div className="flex flex-wrap items-end gap-3">
              <label className="space-y-1">
                <span className="text-label-12 text-muted-foreground">映射编码</span>
                <Input
                  value={newCode}
                  placeholder="如 JST_SPU_V1"
                  onChange={(e) => setNewCode(e.target.value)}
                />
              </label>
              <label className="space-y-1">
                <span className="text-label-12 text-muted-foreground">映射名称</span>
                <Input
                  value={newName}
                  placeholder="如 聚水潭商品映射"
                  onChange={(e) => setNewName(e.target.value)}
                />
              </label>
              <label className="space-y-1">
                <span className="text-label-12 text-muted-foreground">外部系统</span>
                <Input
                  value={newSystem}
                  placeholder="如 聚水潭"
                  onChange={(e) => setNewSystem(e.target.value)}
                />
              </label>
              <label className="space-y-1">
                <span className="text-label-12 text-muted-foreground">目标模型</span>
                <select
                  className="border-border bg-background text-copy-13 h-9 rounded-md border px-2"
                  value={newModel}
                  onChange={(e) => setNewModel(e.target.value)}
                >
                  <option value="">请选择…</option>
                  {migratable.map((t) => (
                    <option key={t.code} value={t.code}>
                      {t.label}（{t.ownerModule}）
                    </option>
                  ))}
                </select>
              </label>
              <Button disabled={busy} onClick={() => void submitMapping()}>
                {busy ? (
                  <Loader2 className="h-4 w-4 animate-spin" />
                ) : (
                  <Upload className="h-4 w-4" />
                )}
                新建映射配置
              </Button>
            </div>
            <p className="text-copy-12 text-muted-foreground mt-2">
              新建时按目标模型的字段清单**逐字生成**外部列名（用字段的中文名当列名）——
              真实使用时请把源文件的表头改成与之一致，或改这份映射的列名。
            </p>
          </SectionCard>

          <SectionCard
            title="映射配置"
            description="已被迁移任务引用的不能删（审计要回答的是「当时按什么映射迁的」）。"
            bodyClassName="p-0"
          >
            {loading && mappings.length === 0 ? (
              <div className="text-copy-13 text-muted-foreground flex items-center gap-2 px-5 py-10">
                <Loader2 className="h-4 w-4 animate-spin" /> 正在读取映射配置…
              </div>
            ) : mappings.length === 0 ? (
              <EmptyState
                icon={<ScrollText className="h-5 w-5" />}
                title={mappingTotal === 0 ? '还没有映射配置' : '这个筛选条件下没有映射配置'}
                description={
                  mappingTotal === 0
                    ? '先在上方新建一份：外部列名 → 内部字段。'
                    : '换个筛选条件再看看。'
                }
              />
            ) : (
              <div className="overflow-x-auto">
                <table className="text-copy-13 w-full text-left">
                  <thead className="bg-muted/50">
                    <tr>
                      <th className="px-4 py-3 font-medium">编码</th>
                      <th className="px-4 py-3 font-medium">名称</th>
                      <th className="px-4 py-3 font-medium">外部系统</th>
                      <th className="px-4 py-3 font-medium">目标模型</th>
                      <th className="px-4 py-3 font-medium">字段映射</th>
                      <th className="px-4 py-3 font-medium">状态</th>
                      <th className="px-4 py-3 font-medium">操作</th>
                    </tr>
                  </thead>
                  <tbody>
                    {mappings.map((m) => (
                      <tr key={m.id} className="border-border border-t">
                        <td className="text-copy-12 px-4 py-3 font-mono">{m.code}</td>
                        <td className="px-4 py-3">{m.name}</td>
                        <td className="px-4 py-3">{m.sourceSystem || '—'}</td>
                        <td className="px-4 py-3">{m.targetModel}</td>
                        <td className="text-copy-12 px-4 py-3">
                          {m.fields.map((f) => f.source).join(' / ')}
                        </td>
                        <td className="px-4 py-3">
                          <Badge tone={m.enabled ? 'success' : 'neutral'}>
                            {m.enabled ? '启用' : '停用'}
                          </Badge>
                        </td>
                        <td className="px-4 py-3">
                          <Button size="sm" variant="ghost" onClick={() => void doDeleteMapping(m)}>
                            删除
                          </Button>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}

            {mappingTotal > 10 ? (
              <div className="flex items-center justify-between px-5 py-3">
                <span className="text-copy-12 text-muted-foreground">共 {mappingTotal} 条</span>
                <div className="flex gap-2">
                  <Button
                    size="sm"
                    variant="ghost"
                    disabled={mappingPage === 0}
                    onClick={() => setMappingPage((p) => Math.max(0, p - 1))}
                  >
                    上一页
                  </Button>
                  <Button
                    size="sm"
                    variant="ghost"
                    disabled={(mappingPage + 1) * 10 >= mappingTotal}
                    onClick={() => setMappingPage((p) => p + 1)}
                  >
                    下一页
                  </Button>
                </div>
              </div>
            ) : null}
          </SectionCard>
        </div>
      ) : null}

      {tab === 'targets' ? (
        <SectionCard
          title="目标内部模型目录"
          description="没接通的那几个**照样出现**并标「未接通」—— 混进空态等于告诉运营「订单也已经能迁历史数据了」。"
          bodyClassName="p-0"
        >
          {loading && targets.length === 0 ? (
            <div className="text-copy-13 text-muted-foreground flex items-center gap-2 px-5 py-10">
              <Loader2 className="h-4 w-4 animate-spin" /> 正在读取目标模型…
            </div>
          ) : targets.length === 0 ? (
            <EmptyState icon={<CircleAlert className="h-5 w-5" />} title="读不到目标模型目录" />
          ) : (
            <div className="overflow-x-auto">
              <table className="text-copy-13 w-full text-left">
                <thead className="bg-muted/50">
                  <tr>
                    <th className="px-4 py-3 font-medium">目标模型</th>
                    <th className="px-4 py-3 font-medium">属主服务</th>
                    <th className="px-4 py-3 font-medium">可迁</th>
                    <th className="px-4 py-3 font-medium">字段</th>
                    <th className="px-4 py-3 font-medium">边界</th>
                  </tr>
                </thead>
                <tbody>
                  {targets.map((t) => (
                    <tr key={t.code} className="border-border border-t">
                      <td className="px-4 py-3">
                        {t.label}
                        <span className="text-copy-12 text-muted-foreground ml-2">{t.code}</span>
                      </td>
                      <td className="px-4 py-3">
                        {t.ownerService}
                        <span className="text-copy-12 text-muted-foreground ml-2">
                          {t.ownerModule}
                        </span>
                      </td>
                      <td className="px-4 py-3">
                        <Badge tone={t.fields.length > 0 ? 'success' : 'neutral'}>
                          {t.fields.length > 0 ? '已接通' : '未接通'}
                        </Badge>
                      </td>
                      <td className="text-copy-12 px-4 py-3">
                        {t.fields.length === 0
                          ? '—'
                          : t.fields
                              .map((f) => `${f.label}${f.required ? '（必填）' : ''}`)
                              .join(' / ')}
                      </td>
                      <td className="text-copy-12 px-4 py-3">{t.limitation || '—'}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </SectionCard>
      ) : null}

      {tab === 'capabilities' ? (
        <div className="space-y-4">
          <SectionCard
            title="能力声明"
            description="逐格说清：有没有、依据是什么、缺了会怎样、归谁。"
            bodyClassName="p-0"
          >
            {unavailableCapabilities(capabilities).length > 0 ? (
              <div className="border-border bg-muted/40 border-b px-5 py-3">
                <p className="text-copy-12 text-muted-foreground">
                  还没接通：
                  {unavailableCapabilities(capabilities)
                    .map((c) => `${c.name}（归 ${c.ownerModule || '—'}）`)
                    .join('、')}
                  —— 混进空态等于告诉运营「这些也已经能迁了」。
                </p>
              </div>
            ) : null}
            {loading && !capabilities ? (
              <div className="text-copy-13 text-muted-foreground flex items-center gap-2 px-5 py-10">
                <Loader2 className="h-4 w-4 animate-spin" /> 正在读取能力声明…
              </div>
            ) : (
              <div className="overflow-x-auto">
                <table className="text-copy-13 w-full text-left">
                  <thead className="bg-muted/50">
                    <tr>
                      <th className="px-4 py-3 font-medium">能力</th>
                      <th className="px-4 py-3 font-medium">可用</th>
                      <th className="px-4 py-3 font-medium">依据</th>
                      <th className="px-4 py-3 font-medium">边界</th>
                    </tr>
                  </thead>
                  <tbody>
                    {(capabilities?.capabilities ?? []).map((c, i) => (
                      <tr key={`${c.module}-${c.name}-${i}`} className="border-border border-t">
                        <td className="px-4 py-3">
                          {c.name}
                          <span className="text-copy-12 text-muted-foreground ml-2">
                            {c.module}
                          </span>
                        </td>
                        <td className="px-4 py-3">
                          <Badge tone={c.available ? 'success' : 'neutral'}>
                            {c.available ? '已接通' : '未接通'}
                          </Badge>
                        </td>
                        <td className="text-copy-12 px-4 py-3">{c.basis}</td>
                        <td className="text-copy-12 px-4 py-3">
                          {c.limitation || '—'}
                          {c.ownerModule ? (
                            <span className="text-muted-foreground ml-2">归 {c.ownerModule}</span>
                          ) : null}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </SectionCard>

          <SectionCard
            title="接线状态"
            description="上游 / 邻居 / 下游：这一格归谁、接通到什么程度。"
            bodyClassName="p-0"
          >
            <div className="overflow-x-auto">
              <table className="text-copy-13 w-full text-left">
                <thead className="bg-muted/50">
                  <tr>
                    <th className="px-4 py-3 font-medium">模块</th>
                    <th className="px-4 py-3 font-medium">关系</th>
                    <th className="px-4 py-3 font-medium">状态</th>
                    <th className="px-4 py-3 font-medium">说明</th>
                  </tr>
                </thead>
                <tbody>
                  {(capabilities?.gaps ?? []).map((g, i) => (
                    <tr key={`${g.module}-${i}`} className="border-border border-t">
                      <td className="px-4 py-3">
                        {g.name}
                        <span className="text-copy-12 text-muted-foreground ml-2">{g.module}</span>
                      </td>
                      <td className="px-4 py-3">{g.relation}</td>
                      <td className="px-4 py-3">
                        <Badge tone={g.available ? 'success' : 'warning'}>
                          {g.available ? '已接通' : '未接通'}
                        </Badge>
                      </td>
                      <td className="text-copy-12 px-4 py-3">{g.note}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </SectionCard>

          <SectionCard
            title="维护"
            description="清理过期文件（迁移任务记录保留 —— 它是审计事实：谁在什么时候按什么映射迁了什么）。"
          >
            <Button size="sm" variant="ghost" onClick={() => void doExpire()}>
              <TriangleAlert className="h-3.5 w-3.5" /> 清理过期文件
            </Button>
          </SectionCard>
        </div>
      ) : null}
    </div>
  );
}
