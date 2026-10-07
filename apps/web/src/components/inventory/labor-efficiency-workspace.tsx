'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import { Gauge, LineChart, RefreshCw, ScrollText, TrendingUp, UserCheck } from 'lucide-react';
import {
  Button,
  EmptyState,
  FormField,
  Input,
  Notice,
  PageHeader,
  SectionCard,
  StatCard,
} from '@starcon/ui';
import type {
  LaborCapabilities,
  LaborRanking,
  LaborReport,
  LaborTrend,
  LaborWorkTask,
  LaborWorkTaskDetail,
  LaborWorkTaskPage,
} from '../../../../../packages/api-contracts/src/catalog';
import { fetchWarehouses } from './inventory-model';
import {
  assignLaborTask,
  avgSecondsText,
  completeLaborTask,
  completeTaskProblem,
  createLaborTask,
  createTaskProblem,
  distanceText,
  durationText,
  errorText,
  exceptionProblem,
  fetchLaborCapabilities,
  fetchLaborRanking,
  fetchLaborReport,
  fetchLaborTask,
  fetchLaborTasks,
  fetchLaborTrend,
  formatDay,
  formatMoment,
  isIdleDay,
  metricIsRate,
  metricValueText,
  qty,
  rangeText,
  rateText,
  rateTone,
  rankingMetricOptions,
  reportLaborException,
  rowSummary,
  startLaborTask,
  taskStatusLabels,
  taskStatusOptions,
  taskStatusTone,
  trendPointText,
  workTypeLabels,
  workTypeOptions,
} from './labor-efficiency-model';
import { useIdentity } from '../iam/identity-shell';

interface WarehouseRef {
  id: string;
  code: string;
  name: string;
}

/**
 * 8.5.1.1 作业效率（人效）工作区。
 *
 * <p>蓝图：「统计仓库人效：人均日拣货件数/单量、拣货行走距离、打包时效、复核准确率、差错率，
 * 按人按日统计。」输出：「人效报表 + 排名 + 趋势。」以及本模块的红线：
 * 「**人效数据用于提成核算与排班，需明确口径避免争议**（ERP 人效报表是绩效争议高发区）。」
 *
 * <p>界面纪律（每一条都对应一个会被误显示、或者会被误读的坑）：
 *
 * <ul>
 *   <li>**口径必须显示出来**。人效数字要拿去做提成核算与绩效面谈，用户需要看到的是
 *       「这个数怎么算出来的」，而不只是一个百分比。所以口径文本由后端随响应下发
 *       （{@code dayBasis} / {@code metricBasis} / 各类 note），界面原样展示 ——
 *       在前端重写一遍口径，迟早会与服务端对不上，而对不上时没人知道该信谁。
 *   <li>**率与时效为 null 时显示「不可计算」而不是 0**。0% 会被读成「复核全对」，
 *       真实含义是「今天没人复核」。这是本模块最容易出事的一处误显示。
 *   <li>**排名一次只排一个指标**，并按指标方向标箭头。差错率越低越好、准确率越高越好；
 *       把方向相反的指标塞进同一个榜再让人自己看，是绩效争议的标准起手式。
 *   <li>**差错 ≠ 异常**，界面上分开说。缺货停工不是「做错了」，不该进差错率。
 *   <li>**趋势里空白日显示为「当天无作业」**，不跳过。跳过会让「停了两天」
 *       看起来像「产量没变」，而那正是排班要看的事实。
 *   <li>**完成即定稿**：计量列在完成那一刻落定后由数据库触发器冻结，界面必须先说清
 *       「不能反悔」，而不是让人填完才知道。
 * </ul>
 */
export function LaborEfficiencyWorkspace() {
  const { session } = useIdentity();
  const readable = session.permissions.includes('warehouse.inventory.read');
  const writable = session.permissions.includes('warehouse.inventory.update');

  const [capabilities, setCapabilities] = useState<LaborCapabilities | null>(null);
  const [warehouses, setWarehouses] = useState<WarehouseRef[]>([]);
  const [report, setReport] = useState<LaborReport | null>(null);
  const [ranking, setRanking] = useState<LaborRanking | null>(null);
  const [trend, setTrend] = useState<LaborTrend | null>(null);
  const [tasks, setTasks] = useState<LaborWorkTaskPage | null>(null);
  const [openTask, setOpenTask] = useState<LaborWorkTaskDetail | null>(null);
  const [loading, setLoading] = useState(true);
  const [drillLoading, setDrillLoading] = useState(false);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');

  const [from, setFrom] = useState('2026-01-01');
  const [to, setTo] = useState('2026-12-31');
  const [warehouseId, setWarehouseId] = useState('');
  const [workType, setWorkType] = useState('');
  const [metric, setMetric] = useState('PICKED_PIECES');

  const [taskStatus, setTaskStatus] = useState('');
  const [taskKeyword, setTaskKeyword] = useState('');
  const [taskPage, setTaskPage] = useState(0);


  // 派工草稿
  const [newType, setNewType] = useState('PICKING');
  const [newWarehouse, setNewWarehouse] = useState('');
  const [newOperator, setNewOperator] = useState('');
  const [newReference, setNewReference] = useState('');
  const [newNote, setNewNote] = useState('');

  // 完成草稿
  const [completing, setCompleting] = useState<LaborWorkTask | null>(null);
  const [completeOrders, setCompleteOrders] = useState('0');
  const [completeLines, setCompleteLines] = useState('0');
  const [completePieces, setCompletePieces] = useState('0');
  const [completeDistance, setCompleteDistance] = useState('');
  const [completeErrorPieces, setCompleteErrorPieces] = useState('0');
  const [completeErrorOrders, setCompleteErrorOrders] = useState('0');

  // 异常草稿
  const [failing, setFailing] = useState<LaborWorkTask | null>(null);
  const [exceptionCode, setExceptionCode] = useState('MISSING_STOCK');
  const [exceptionNote, setExceptionNote] = useState('');

  /** 首屏：能力（含口径）+ 仓库选项 + 默认区间。区间缺省与后端一致（最近 14 天）。 */
  const load = useCallback(async () => {
    setLoading(true);
    setError('');
    try {
      const [caps, whs] = await Promise.all([fetchLaborCapabilities(), fetchWarehouses()]);
      setCapabilities(caps);
      setWarehouses(whs as WarehouseRef[]);
    } catch (e) {
      setError(errorText(e));
    } finally {
      setLoading(false);
    }
  }, []);

  /** 报表 / 排名 / 趋势 / 作业任务一次拉回来：四处数据同源，缺一处就会自相矛盾。 */
  const reload = useCallback(async () => {
    setDrillLoading(true);
    setError('');
    try {
      const scope = { from, to, warehouseId: warehouseId || undefined, workType: workType || undefined };
      const [rep, rank, tr, taskResult] = await Promise.all([
        fetchLaborReport({ ...scope, page: 0, size: 200 }),
        fetchLaborRanking({ ...scope, metric, size: 50 }),
        fetchLaborTrend(scope),
        fetchLaborTasks({
          status: taskStatus || undefined,
          workType: workType || undefined,
          warehouseId: warehouseId || undefined,
          keyword: taskKeyword || undefined,
          page: taskPage,
          size: 20,
        }),
      ]);
      setReport(rep);
      setRanking(rank);
      setTrend(tr);
      setTasks(taskResult);
    } catch (e) {
      setError(errorText(e));
    } finally {
      setDrillLoading(false);
    }
  }, [from, to, warehouseId, workType, metric, taskStatus, taskKeyword, taskPage]);

  useEffect(() => {
    if (!readable) return;
    void load();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [readable]);

  // 首次拿到仓库列表后给派工下拉一个默认值，避免用户面对空白下拉。
  useEffect(() => {
    if (newWarehouse || warehouses.length === 0) return;
    setNewWarehouse(warehouses[0]?.id ?? '');
  }, [warehouses, newWarehouse]);

  // 默认区间 = 最近 14 天（与后端缺省一致，界面上显式给出而不是让用户猜）。
  useEffect(() => {
    if (capabilities) {
      const toDate = new Date();
      const fromDate = new Date(toDate.getTime());
      fromDate.setDate(fromDate.getDate() - 13);
      const iso = (d: Date) => {
        const offset = d.getTimezoneOffset() * 60000;
        return new Date(d.getTime() - offset).toISOString().slice(0, 10);
      };
      setFrom(iso(fromDate));
      setTo(iso(toDate));
    }
  }, [capabilities]);

  useEffect(() => {
    if (!capabilities) return;
    void reload();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [capabilities, from, to, warehouseId, workType, metric, taskStatus, taskKeyword, taskPage]);

  const summary = report?.summary;
  const basisOf = useCallback(
    (code: string) => capabilities?.metricBasis.find((b) => b.metric === code),
    [capabilities],
  );

  const runCreate = useCallback(async () => {
    const problem = createTaskProblem({
      workType: newType,
      warehouseId: newWarehouse,
      sourceModule: '8.5.1.1',
    });
    if (problem) {
      setError(problem);
      return;
    }
    setSaving(true);
    setError('');
    try {
      const created = await createLaborTask({
        workType: newType,
        warehouseId: newWarehouse,
        sourceModule: '8.5.1.1',
        referenceNo: newReference.trim(),
        operatorUserId: newOperator.trim() || null,
        note: newNote.trim() || null,
      });
      setNewReference('');
      setNewNote('');
      setNotice(
        `已派工 ${created.code}（${workTypeLabels[created.workType] ?? created.workType}）。` +
          '此刻还没有任何产出进入人效 —— 要等它开工并完成，计量才会落定。',
      );
      await reload();
    } catch (e) {
      setError(errorText(e));
    } finally {
      setSaving(false);
    }
  }, [newType, newWarehouse, newOperator, newReference, newNote, reload]);

  const openDetail = useCallback(async (id: string) => {
    setError('');
    setDrillLoading(true);
    try {
      setOpenTask(await fetchLaborTask(id));
    } catch (e) {
      setError(errorText(e));
    } finally {
      setDrillLoading(false);
    }
  }, []);

  const startTask = useCallback(
    async (id: string) => {
      setSaving(true);
      setError('');
      try {
        await startLaborTask(id);
        setNotice('已开工。开始时刻由服务端记录 —— 打包时效从这一刻开始算。');
        await reload();
        await openDetail(id);
      } catch (e) {
        setError(errorText(e));
      } finally {
        setSaving(false);
      }
    },
    [reload, openDetail],
  );

  const reassignTask = useCallback(
    async (id: string) => {
      const operator = window.prompt('改派给哪个用户 id（已开工的任务不允许改派）');
      if (!operator) return;
      setSaving(true);
      setError('');
      try {
        await assignLaborTask(id, operator.trim(), '界面改派');
        setNotice('已改派。人效归属跟着任务走 —— 尚未开工，所以改派不影响任何已产生的时长。');
        await reload();
      } catch (e) {
        setError(errorText(e));
      } finally {
        setSaving(false);
      }
    },
    [reload],
  );

  const submitComplete = useCallback(async () => {
    if (!completing) return;
    const numbers = {
      orderCount: Number(completeOrders) || 0,
      lineCount: Number(completeLines) || 0,
      pieceCount: Number(completePieces) || 0,
      travelDistanceM: completeDistance.trim() === '' ? null : Number(completeDistance),
      errorPieceCount: Number(completeErrorPieces) || 0,
      errorOrderCount: Number(completeErrorOrders) || 0,
    };
    const problem = completeTaskProblem({ workType: completing.workType, ...numbers });
    if (problem) {
      setError(problem);
      return;
    }
    setSaving(true);
    setError('');
    try {
      await completeLaborTask(completing.id, numbers);
      setCompleting(null);
      setNotice(
        '已完成，计量已定稿：这些数字之后不能再改（数据库触发器冻结）。发现记错只能另报异常留痕。',
      );
      await reload();
    } catch (e) {
      setError(errorText(e));
    } finally {
      setSaving(false);
    }
  }, [completing, completeOrders, completeLines, completePieces, completeDistance, completeErrorPieces, completeErrorOrders, reload]);

  const submitException = useCallback(async () => {
    if (!failing) return;
    const problem = exceptionProblem(exceptionCode, exceptionNote);
    if (problem) {
      setError(problem);
      return;
    }
    setSaving(true);
    setError('');
    try {
      await reportLaborException(failing.id, {
        exceptionCode,
        exceptionNote: exceptionNote.trim(),
      });
      setFailing(null);
      setExceptionNote('');
      setNotice(
        '已记录异常。它计入任务张数与异常次数，但**不进差错率** —— 缺货不是「做错了」，' +
          '把两者混在一起会让缺货的人被算成错得最离谱。',
      );
      await reload();
    } catch (e) {
      setError(errorText(e));
    } finally {
      setSaving(false);
    }
  }, [failing, exceptionCode, exceptionNote, reload]);

  const idleDays = useMemo(() => (trend?.points ?? []).filter(isIdleDay).length, [trend]);

  if (!readable) {
    return (
      <div className="space-y-4">
        <PageHeader title="作业效率" description="按人按日统计仓库人效。" />
        <Notice tone="danger" title="没有查看权限">
          当前角色缺少 warehouse.inventory.read，看不到人效报表。人效涉及绩效与提成数据，
          按 ADR-0005 需要由目标租户成员的角色显式授予。
        </Notice>
      </div>
    );
  }

  return (
    <div className="space-y-4">
      <PageHeader
        title="作业效率"
        description="人均日拣货件数/单量、拣货行走距离、打包时效、复核准确率、差错率，按人按日统计。人效数据用于提成核算与排班，口径与分母在下方逐项写明。"
        actions={
          <Button variant="outline" size="sm" onClick={() => void load()} disabled={loading}>
            <RefreshCw className={loading ? 'mr-2 h-3.5 w-3.5 animate-spin' : 'mr-2 h-3.5 w-3.5'} />
            刷新
          </Button>
        }
      />

      {error && (
        <Notice tone="danger" title="这一步没能完成">
          {error}
        </Notice>
      )}
      {notice && (
        <Notice tone="info" title="已处理">
          {notice}
        </Notice>
      )}

      {/* ---------------- 区间与筛选 ---------------- */}
      <SectionCard
        title="统计区间"
        description={`「日」按 ${capabilities?.timezone ?? 'Asia/Shanghai'} 归属完成时刻。只统计已定稿的任务（已完成 + 异常终止）。`}
      >
        <div className="grid gap-3 md:grid-cols-4">
          <FormField label="开始日期" id="labor-from">
            <Input id="labor-from" type="date" value={from} onChange={(e) => setFrom(e.target.value)} />
          </FormField>
          <FormField label="结束日期" id="labor-to">
            <Input id="labor-to" type="date" value={to} onChange={(e) => setTo(e.target.value)} />
          </FormField>
          <FormField label="仓库" id="labor-warehouse">
            <select
              id="labor-warehouse"
              className="border-control-border bg-background h-9 w-full rounded-md border px-2 text-sm"
              value={warehouseId}
              onChange={(e) => setWarehouseId(e.target.value)}
            >
              <option value="">全部仓库</option>
              {warehouses.map((w) => (
                <option key={w.id} value={w.id}>
                  {w.code} · {w.name}
                </option>
              ))}
            </select>
          </FormField>
          <FormField label="作业类型" id="labor-type">
            <select
              id="labor-type"
              className="border-control-border bg-background h-9 w-full rounded-md border px-2 text-sm"
              value={workType}
              onChange={(e) => setWorkType(e.target.value)}
            >
              <option value="">全部类型</option>
              {workTypeOptions.map((o) => (
                <option key={o.value} value={o.value}>
                  {o.label}
                </option>
              ))}
            </select>
          </FormField>
        </div>
        <p className="text-muted-foreground mt-3 text-xs">
          当前区间：{rangeText(from, to)}
          {report ? ` · 实际统计 ${report.from} 至 ${report.to}` : ''}
        </p>
      </SectionCard>

      {/* ---------------- 五个指标 ---------------- */}
      {summary && (
        <div className="grid gap-4 md:grid-cols-4">
          <StatCard
            label="人均日拣货件数"
            value={
              summary.avgPickedPiecesPerPersonDay === null
                ? '不可计算'
                : `${summary.avgPickedPiecesPerPersonDay} 件`
            }
            polarity="neutral"
            comparisonLabel={`合计 ${qty(summary.pickedPieces)} 件 ÷ (${summary.headcount} 人 × ${summary.workDays} 作业日)`}
          />
          <StatCard
            label="人均日拣货单量"
            value={
              summary.avgPickedOrdersPerPersonDay === null
                ? '不可计算'
                : `${summary.avgPickedOrdersPerPersonDay} 单`
            }
            polarity="neutral"
            comparisonLabel={`合计 ${summary.pickedOrders} 单 · 行走 ${distanceText(summary.travelDistanceM)}`}
          />
          <StatCard
            label="平均打包时效"
            value={avgSecondsText(summary.avgPackingSeconds)}
            polarity={summary.avgPackingSeconds === null ? 'neutral' : 'positive'}
            comparisonLabel={`分母：${summary.packingTaskCount} 张打包任务（异常终止与无开工时刻的不计）`}
          />
          <StatCard
            label="复核准确率"
            value={rateText(summary.reviewAccuracy)}
            polarity={rateTone(summary.reviewAccuracy) === 'bad' ? 'negative' : 'positive'}
            comparisonLabel={`分母：复核 ${qty(summary.reviewedPieces)} 件，差错 ${qty(summary.reviewErrorPieces)} 件`}
          />
        </div>
      )}

      {summary && (
        <div className="grid gap-4 md:grid-cols-2">
          <StatCard
            label="差错率（按件数）"
            value={rateText(summary.errorRateByPiece)}
            polarity={rateTone(summary.errorRateByPiece) === 'bad' ? 'negative' : 'positive'}
            comparisonLabel={`分母：全部作业件数合计；差错 ${qty(summary.errorPieces)} 件（跨三种作业类型统计）`}
          />
          <StatCard
            label="差错率（按单量）"
            value={rateText(summary.errorRateByOrder)}
            polarity={rateTone(summary.errorRateByOrder) === 'bad' ? 'negative' : 'positive'}
            comparisonLabel={`分母：涉及单量合计；差错单量 ${summary.errorOrders} 单`}
          />
        </div>
      )}

      {/* ---------------- 口径 ---------------- */}
      {capabilities && (
        <SectionCard
          title="指标口径"
          description="人效数字要拿去做提成核算与绩效面谈，所以口径写在这里而不是只留在服务端注释里。口径与数字必须一起看。"
        >
          <dl className="space-y-3 text-sm">
            {capabilities.metricBasis.map((basis) => (
              <div key={basis.metric} className="border-control-border border-t pt-2 first:border-0 first:pt-0">
                <dt className="font-medium">
                  {basis.label}（{basis.unit}）
                </dt>
                <dd className="text-muted-foreground mt-1">{basis.definition}</dd>
                <dd className="text-muted-foreground mt-1 text-xs">分母：{basis.denominator}</dd>
              </div>
            ))}
          </dl>
          <div className="text-muted-foreground mt-4 space-y-2 text-xs">
            <p>【日】{report?.dayBasis ?? capabilities.dayBasisNote}</p>
            <p>【打包时效】{capabilities.packingEfficiencyBasisNote}</p>
            <p>【复核准确率】{capabilities.reviewAccuracyBasisNote}</p>
            <p>【差错率】{capabilities.errorBasisNote}</p>
            <p>【人均】{capabilities.perCapitaBasisNote}</p>
            <p>【差错 vs 异常】{capabilities.errorVsExceptionNote}</p>
            <p>【上游】{capabilities.upstreamNote}</p>
            <p>【写入所有权】{capabilities.ownershipNote}</p>
          </div>
        </SectionCard>
      )}

      {/* ---------------- 人效报表（按人按日） ---------------- */}
      <SectionCard
        title="人效报表 · 按人按日"
        description="蓝图原文「按人按日统计」。一行 = 一个人在一天的产出与质量。"
        extra={drillLoading ? <span className="text-muted-foreground text-xs">加载中…</span> : null}
      >
        {report === null || report.rows.length === 0 ? (
          <EmptyState
            icon={<Gauge className="h-5 w-5" />}
            title={drillLoading ? '加载中' : '这个区间还没有人效数据'}
            description={
              drillLoading
                ? '正在按人按日聚合作业记录。'
                : '人效只统计已定稿的作业任务（已完成 / 异常终止）。先在下方「作业任务」里派一张任务并完成它，这里才会有数。'
            }
          />
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead className="text-muted-foreground text-left text-xs">
                <tr>
                  <th className="py-2">操作人 / 日期</th>
                  <th className="py-2 text-right">任务</th>
                  <th className="py-2 text-right">拣货件数</th>
                  <th className="py-2 text-right">拣货单量</th>
                  <th className="py-2 text-right">行走距离</th>
                  <th className="py-2 text-right">打包时效</th>
                  <th className="py-2 text-right">复核准确率</th>
                  <th className="py-2 text-right">差错率（件）</th>
                  <th className="py-2 text-right">差错率（单）</th>
                </tr>
              </thead>
              <tbody>
                {report.rows.map((row) => (
                  <tr key={`${row.operatorUserId}-${row.workDay}`} className="border-control-border border-t">
                    <td className="py-2">
                      <div className="font-medium">
                        {row.operatorUserId.slice(0, 8)}…
                      </div>
                      <div className="text-muted-foreground text-xs">{formatDay(row.workDay)}</div>
                    </td>
                    <td className="py-2 text-right tabular-nums">{row.taskCount}</td>
                    <td className="py-2 text-right tabular-nums">{qty(row.pickedPieces)}</td>
                    <td className="py-2 text-right tabular-nums">{row.pickedOrders}</td>
                    <td className="py-2 text-right tabular-nums">{distanceText(row.travelDistanceM)}</td>
                    <td className="py-2 text-right tabular-nums">{avgSecondsText(row.avgPackingSeconds)}</td>
                    <td className="py-2 text-right tabular-nums">{rateText(row.reviewAccuracy)}</td>
                    <td className="py-2 text-right tabular-nums">{rateText(row.errorRateByPiece)}</td>
                    <td className="py-2 text-right tabular-nums">{rateText(row.errorRateByOrder)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
            <p className="text-muted-foreground mt-2 text-xs">
              {report.rows[0] ? `${rowSummary(report.rows[0])}（首行示例）· ` : ''}共 {report.total} 行
              {report.total > report.rows.length ? '，当前只显示前 ' + report.rows.length + ' 行' : ''}
            </p>
          </div>
        )}
      </SectionCard>

      {/* ---------------- 排名 ---------------- */}
      <SectionCard
        title="人效排名"
        description="一次只排一个指标。方向相反的指标混在一个榜里，是绩效争议的标准起手式。"
        extra={
          <select
            className="border-control-border bg-background h-9 rounded-md border px-2 text-sm"
            value={metric}
            onChange={(e) => setMetric(e.target.value)}
          >
            {rankingMetricOptions.map((o) => (
              <option key={o.value} value={o.value}>
                {o.label}
              </option>
            ))}
          </select>
        }
      >
        {ranking === null || ranking.rows.length === 0 ? (
          <EmptyState
            icon={<TrendingUp className="h-5 w-5" />}
            title={drillLoading ? '加载中' : '这个区间还没有可排名的人效'}
            description={drillLoading ? '正在按区间合计排序。' : '排名只统计区间内有过完成作业的操作人。'}
          />
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead className="text-muted-foreground text-left text-xs">
                <tr>
                  <th className="py-2">名次</th>
                  <th className="py-2">操作人</th>
                  <th className="py-2 text-right">{ranking.metricLabel}</th>
                  <th className="py-2 text-right">作业日</th>
                  <th className="py-2 text-right">拣货件数</th>
                  <th className="py-2 text-right">行走距离</th>
                  <th className="py-2 text-right">复核准确率</th>
                  <th className="py-2 text-right">差错率（件）</th>
                </tr>
              </thead>
              <tbody>
                {ranking.rows.map((row) => (
                  <tr key={row.operatorUserId} className="border-control-border border-t">
                    <td className="py-2 tabular-nums">{row.rank}</td>
                    <td className="py-2 font-medium">{row.operatorUserId.slice(0, 8)}…</td>
                    <td className="py-2 text-right font-medium tabular-nums">
                      {metricValueText(row.metricValue, ranking.higherIsBetter, metricIsRate(metric))}
                    </td>
                    <td className="py-2 text-right tabular-nums">{row.workDays}</td>
                    <td className="py-2 text-right tabular-nums">{qty(row.pickedPieces)}</td>
                    <td className="py-2 text-right tabular-nums">{distanceText(row.travelDistanceM)}</td>
                    <td className="py-2 text-right tabular-nums">{rateText(row.reviewAccuracy)}</td>
                    <td className="py-2 text-right tabular-nums">{rateText(row.errorRateByPiece)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
            <p className="text-muted-foreground mt-2 text-xs">{ranking.metricBasis}</p>
          </div>
        )}
      </SectionCard>

      {/* ---------------- 趋势 ---------------- */}
      <SectionCard
        title="人效趋势"
        description="逐日。区间内没有作业的日子也会出现 —— 跳过空白日会让「停了两天」看起来像「产量没变」。"
        extra={idleDays > 0 ? <span className="text-muted-foreground text-xs">其中 {idleDays} 天无作业</span> : null}
      >
        {trend === null || trend.points.length === 0 ? (
          <EmptyState
            icon={<LineChart className="h-5 w-5" />}
            title={drillLoading ? '加载中' : '这个区间没有趋势数据'}
            description={drillLoading ? '正在逐日聚合。' : '改宽区间，或先去完成一张作业任务。'}
          />
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead className="text-muted-foreground text-left text-xs">
                <tr>
                  <th className="py-2">日期</th>
                  <th className="py-2">当日</th>
                  <th className="py-2 text-right">拣货件数</th>
                  <th className="py-2 text-right">拣货单量</th>
                  <th className="py-2 text-right">行走距离</th>
                  <th className="py-2 text-right">打包时效</th>
                  <th className="py-2 text-right">复核准确率</th>
                  <th className="py-2 text-right">差错率（件）</th>
                </tr>
              </thead>
              <tbody>
                {trend.points.map((point) => (
                  <tr
                    key={point.workDay}
                    className={isIdleDay(point) ? 'border-control-border text-muted-foreground border-t' : 'border-control-border border-t'}
                  >
                    <td className="py-2">{formatDay(point.workDay)}</td>
                    <td className="py-2 text-xs">{trendPointText(point)}</td>
                    <td className="py-2 text-right tabular-nums">{qty(point.pickedPieces)}</td>
                    <td className="py-2 text-right tabular-nums">{point.pickedOrders}</td>
                    <td className="py-2 text-right tabular-nums">{distanceText(point.travelDistanceM)}</td>
                    <td className="py-2 text-right tabular-nums">{avgSecondsText(point.avgPackingSeconds)}</td>
                    <td className="py-2 text-right tabular-nums">{rateText(point.reviewAccuracy)}</td>
                    <td className="py-2 text-right tabular-nums">{rateText(point.errorRateByPiece)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
            <p className="text-muted-foreground mt-2 text-xs">{trend.scopeNote} · {trend.dayBasisNote}</p>
          </div>
        )}
      </SectionCard>

      {/* ---------------- 作业记录（写入入口） ---------------- */}
      <SectionCard
        title="作业任务"
        description="人效的原始事实。这里写入的每一张任务，都会按人按日进入上面的报表 —— 报表不是从别处算来的二手数据。"
        extra={
          <div className="flex flex-wrap gap-2">
            <Input
              className="max-w-xs"
              placeholder="搜单号 / 上游单据号"
              value={taskKeyword}
              onChange={(e) => {
                setTaskKeyword(e.target.value);
                setTaskPage(0);
              }}
            />
            <select
              className="border-control-border bg-background h-9 rounded-md border px-2 text-sm"
              value={taskStatus}
              onChange={(e) => {
                setTaskStatus(e.target.value);
                setTaskPage(0);
              }}
            >
              <option value="">全部状态</option>
              {taskStatusOptions.map((o) => (
                <option key={o.value} value={o.value}>
                  {o.label}
                </option>
              ))}
            </select>
          </div>
        }
      >
        {writable && (
          <div className="border-control-border mb-4 grid gap-3 rounded-md border p-3 md:grid-cols-5">
            <FormField label="作业类型" id="labor-new-type">
              <select
                id="labor-new-type"
                className="border-control-border bg-background h-9 w-full rounded-md border px-2 text-sm"
                value={newType}
                onChange={(e) => setNewType(e.target.value)}
              >
                {workTypeOptions.map((o) => (
                  <option key={o.value} value={o.value}>
                    {o.label}
                  </option>
                ))}
              </select>
            </FormField>
            <FormField label="仓库" id="labor-new-warehouse">
              <select
                id="labor-new-warehouse"
                className="border-control-border bg-background h-9 w-full rounded-md border px-2 text-sm"
                value={newWarehouse}
                onChange={(e) => setNewWarehouse(e.target.value)}
              >
                <option value="">请选择</option>
                {warehouses.map((w) => (
                  <option key={w.id} value={w.id}>
                    {w.code} · {w.name}
                  </option>
                ))}
              </select>
            </FormField>
            <FormField label="操作人 user id" id="labor-new-operator">
              <Input
                id="labor-new-operator"
                placeholder="已认证用户 id（可留空＝待派工）"
                value={newOperator}
                onChange={(e) => setNewOperator(e.target.value)}
              />
            </FormField>
            <FormField label="上游单据号" id="labor-new-reference">
              <Input
                id="labor-new-reference"
                placeholder="出库单号等，可留空"
                value={newReference}
                onChange={(e) => setNewReference(e.target.value)}
              />
            </FormField>
            <div className="flex items-end">
              <Button size="sm" onClick={() => void runCreate()} disabled={saving}>
                派工
              </Button>
            </div>
          </div>
        )}

        {tasks === null || tasks.tasks.length === 0 ? (
          <EmptyState
            icon={<ScrollText className="h-5 w-5" />}
            title={drillLoading ? '加载中' : '还没有作业任务'}
            description={
              drillLoading
                ? '正在读取作业任务列表。'
                : writable
                  ? '派一张任务，然后开工 → 完成，计量才会落定并进入人效报表。'
                  : '当前角色只能查看，不能派工。'
            }
          />
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead className="text-muted-foreground text-left text-xs">
                <tr>
                  <th className="py-2">单号 / 类型</th>
                  <th className="py-2">操作人</th>
                  <th className="py-2">状态</th>
                  <th className="py-2 text-right">件数 / 差错</th>
                  <th className="py-2 text-right">行走距离</th>
                  <th className="py-2 text-right">墙钟时长</th>
                  <th className="py-2 text-right">归属日</th>
                  <th className="py-2" />
                </tr>
              </thead>
              <tbody>
                {tasks.tasks.map((task) => (
                  <tr key={task.id} className="border-control-border border-t">
                    <td className="py-2">
                      <div className="font-medium">{task.code}</div>
                      <div className="text-muted-foreground text-xs">
                        {workTypeLabels[task.workType] ?? task.workType} · {task.warehouseCode}
                        {task.referenceNo ? ` · ${task.referenceNo}` : ''} · 来源 {task.sourceModule}
                      </div>
                    </td>
                    <td className="py-2 text-xs">
                      {task.operatorUserId ? `${task.operatorUserId.slice(0, 8)}…` : '未派工'}
                    </td>
                    <td className="py-2">
                      <span
                        className={
                          taskStatusTone(task.status) === 'bad'
                            ? 'text-destructive text-xs'
                            : taskStatusTone(task.status) === 'warn'
                              ? 'text-amber-600 text-xs dark:text-amber-400'
                              : 'text-muted-foreground text-xs'
                        }
                      >
                        {taskStatusLabels[task.status] ?? task.status}
                      </span>
                      {task.exceptionCode && (
                        <div className="text-muted-foreground text-xs">
                          {task.exceptionCode} · {task.exceptionNote}
                        </div>
                      )}
                    </td>
                    <td className="py-2 text-right tabular-nums">
                      {qty(task.pieceCount)} / {qty(task.errorPieceCount)}
                    </td>
                    <td className="py-2 text-right tabular-nums">{distanceText(task.travelDistanceM)}</td>
                    <td className="py-2 text-right tabular-nums">{durationText(task.durationSeconds)}</td>
                    <td className="py-2 text-right text-xs">{formatDay(task.workDay)}</td>
                    <td className="py-2 text-right">
                      <div className="flex flex-wrap justify-end gap-1">
                        <Button variant="outline" size="sm" onClick={() => void openDetail(task.id)}>
                          时间线
                        </Button>
                        {writable && task.status === 'PENDING' && task.operatorUserId && (
                          <Button size="sm" onClick={() => void startTask(task.id)} disabled={saving}>
                            开工
                          </Button>
                        )}
                        {writable && task.status === 'PENDING' && (
                          <Button variant="outline" size="sm" onClick={() => void reassignTask(task.id)} disabled={saving}>
                            改派
                          </Button>
                        )}
                        {writable && task.status === 'RUNNING' && (
                          <>
                            <Button
                              size="sm"
                              onClick={() => {
                                setCompleting(task);
                                setCompletePieces(String(task.pieceCount ?? 0));
                              }}
                              disabled={saving}
                            >
                              完成
                            </Button>
                            <Button variant="outline" size="sm" onClick={() => setFailing(task)} disabled={saving}>
                              异常
                            </Button>
                          </>
                        )}
                      </div>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
            <div className="mt-3 flex items-center justify-between text-xs">
              <span className="text-muted-foreground">共 {tasks.total} 张 · 第 {tasks.page + 1} 页</span>
              <div className="flex gap-2">
                <Button
                  variant="outline"
                  size="sm"
                  disabled={tasks.page === 0}
                  onClick={() => setTaskPage(Math.max(0, tasks.page - 1))}
                >
                  上一页
                </Button>
                <Button
                  variant="outline"
                  size="sm"
                  disabled={(tasks.page + 1) * tasks.size >= tasks.total}
                  onClick={() => setTaskPage(tasks.page + 1)}
                >
                  下一页
                </Button>
              </div>
            </div>
          </div>
        )}
      </SectionCard>

      {/* ---------------- 完成表单 ---------------- */}
      {completing && (
        <SectionCard
          title={`完成作业 · ${completing.code}`}
          description="提交后计量即定稿，数据库触发器会冻结这些列 —— 发现记错只能另报异常留痕，不能擦掉重写。"
        >
          <div className="grid gap-3 md:grid-cols-3">
            <FormField label="涉及单量" id="labor-c-orders">
              <Input id="labor-c-orders" type="number" value={completeOrders} onChange={(e) => setCompleteOrders(e.target.value)} />
            </FormField>
            <FormField label="作业行数" id="labor-c-lines">
              <Input id="labor-c-lines" type="number" value={completeLines} onChange={(e) => setCompleteLines(e.target.value)} />
            </FormField>
            <FormField label="作业件数" id="labor-c-pieces">
              <Input id="labor-c-pieces" type="number" value={completePieces} onChange={(e) => setCompletePieces(e.target.value)} />
            </FormField>
            <FormField
              label={completing.workType === 'PICKING' ? '拣货行走距离（米）' : '行走距离（不适用于本类型）'}
              id="labor-c-distance"
            >
              <Input
                id="labor-c-distance"
                type="number"
                disabled={completing.workType !== 'PICKING'}
                value={completeDistance}
                onChange={(e) => setCompleteDistance(e.target.value)}
              />
            </FormField>
            <FormField label="差错件数" id="labor-c-error-pieces">
              <Input
                id="labor-c-error-pieces"
                type="number"
                value={completeErrorPieces}
                onChange={(e) => setCompleteErrorPieces(e.target.value)}
              />
            </FormField>
            <FormField label="差错单量" id="labor-c-error-orders">
              <Input
                id="labor-c-error-orders"
                type="number"
                value={completeErrorOrders}
                onChange={(e) => setCompleteErrorOrders(e.target.value)}
              />
            </FormField>
          </div>
          <p className="text-muted-foreground mt-3 text-xs">
            {basisOf(completing.workType === 'REVIEW' ? 'REVIEW_ACCURACY' : 'PICKED_PIECES')?.denominator}
          </p>
          <div className="mt-3 flex gap-2">
            <Button size="sm" onClick={() => void submitComplete()} disabled={saving}>
              确认完成（定稿）
            </Button>
            <Button variant="outline" size="sm" onClick={() => setCompleting(null)}>
              取消
            </Button>
          </div>
        </SectionCard>
      )}

      {/* ---------------- 异常表单 ---------------- */}
      {failing && (
        <SectionCard
          title={`上报异常 · ${failing.code}`}
          description="异常（缺货 / 设备 / 取消）进异常，不进差错率 —— 混在一起会让缺货的人被算成错得最离谱。"
        >
          <div className="grid gap-3 md:grid-cols-3">
            <FormField label="异常类型" id="labor-ex-code">
              <select
                id="labor-ex-code"
                className="border-control-border bg-background h-9 w-full rounded-md border px-2 text-sm"
                value={exceptionCode}
                onChange={(e) => setExceptionCode(e.target.value)}
              >
                {(capabilities?.exceptionCodes ?? []).map((o) => (
                  <option key={o.value} value={o.value}>
                    {o.label}
                  </option>
                ))}
              </select>
            </FormField>
            <div className="md:col-span-2">
              <FormField label="异常原因" id="labor-ex-note">
                <Input
                  id="labor-ex-note"
                  placeholder="写清结论与依据，至少 4 个字（复盘时靠它决定该改什么）"
                  value={exceptionNote}
                  onChange={(e) => setExceptionNote(e.target.value)}
                />
              </FormField>
            </div>
          </div>
          <div className="mt-3 flex gap-2">
            <Button size="sm" onClick={() => void submitException()} disabled={saving}>
              确认上报异常
            </Button>
            <Button variant="outline" size="sm" onClick={() => setFailing(null)}>
              取消
            </Button>
          </div>
        </SectionCard>
      )}

      {/* ---------------- 任务时间线 ---------------- */}
      {openTask && (
        <SectionCard
          title={`作业时间线 · ${openTask.task.code}`}
          description="append-only：什么时候接的、什么时候开工、中间报没报异常，全部留痕且不可改写。"
          extra={
            <Button variant="outline" size="sm" onClick={() => setOpenTask(null)}>
              关闭
            </Button>
          }
        >
          <ol className="space-y-2 text-sm">
            {openTask.timeline.map((event) => (
              <li key={event.id} className="border-control-border border-t pt-2 first:border-0 first:pt-0">
                <div className="flex flex-wrap items-baseline gap-2">
                  <span className="font-medium">{event.eventType}</span>
                  <span className="text-muted-foreground text-xs">{formatMoment(event.occurredAt)}</span>
                  <span className="text-muted-foreground text-xs">操作人 {event.actorUserId.slice(0, 8)}…</span>
                </div>
                <div className="text-muted-foreground text-xs">{event.detail}</div>
              </li>
            ))}
          </ol>
        </SectionCard>
      )}

      {!writable && (
        <Notice tone="warning" title="当前角色只能查看">
          派工 / 开工 / 完成 / 异常上报需要 warehouse.inventory.update 权限。人效是绩效与提成数据，
          写入权限应按目标租户成员的角色显式授予，而不是对所有人开放。
        </Notice>
      )}
    </div>
  );
}