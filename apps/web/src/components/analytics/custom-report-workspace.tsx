'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import { Badge, Button, EmptyState, SectionCard } from '@starcon/ui';
import { Link2, RefreshCw, TriangleAlert } from 'lucide-react';
import { api } from '@/lib/api';
import {
  aggregationLabel,
  chartTypeLabel,
  dataUpdatedNote,
  dataUpdatedText,
  definitionFormula,
  deliveryAvailable,
  deliveryStatusLabel,
  deliverySummary,
  deliveryUnavailableReason,
  dimensionColumns,
  emptyText,
  exportAvailable,
  exportUnavailableReason,
  listEmptyText,
  measureAvailabilityText,
  measureColumnLabel,
  measureValueLines,
  nextRunText,
  pageState,
  rowDimensionTexts,
  summaryText,
  visibilityLabel,
} from './custom-report-model';
import type {
  CustomReportCapabilities,
  CustomReportDeliveryPage,
  CustomReportDimensionView,
  CustomReportMeasureCell,
  CustomReportMetricView,
  CustomReportPage,
  CustomReportRunResponse,
  CustomReportShareView,
  CustomReportSubscriptionView,
  CustomReportView,
} from './custom-report-model';

/**
 * 13.6.1.1 自定义报表工作区（卷 13「利润与经营分析」L3 13.6.1 下的第一格）。
 *
 * <p>蓝图输出：「报表结果 + 分享链接 + 定时订阅。」
 *
 * <p><b>数据来自 reporting 服务（13 卷的唯一归属服务）</b>：
 *
 * <ul>
 *   <li>{@code GET reporting/custom-report/metrics} —— 可用的语义层指标清单（口径唯一）；</li>
 *   <li>{@code GET reporting/custom-report/dimensions} —— 可用维度白名单；</li>
 *   <li>{@code GET/POST reporting/custom-report/reports} —— 报表配置（真分页 + 真搜索）；</li>
 *   <li>{@code POST reporting/custom-report/reports/{id}/run} —— 报表结果；</li>
 *   <li>{@code .../shares} —— 分享链接；{@code .../subscriptions} —— 订阅计划；</li>
 *   <li>{@code .../deliveries} —— 待投递记录台账。</li>
 * </ul>
 *
 * <p><b>🔴 页面必须做到的五件事</b>：
 *
 * <ol>
 *   <li><b>「算不出来」≠「数据是 0」</b>：不可比的度量显示「—」并把原因说清楚，绝不用 0 顶替。</li>
 *   <li><b>多币种不相加</b>：涉币指标按币种分行；与货币无关的量才给一个空串币种的合计。</li>
 *   <li><b>前端不做业务算术</b>：派生值与比率全部来自服务端。</li>
 *   <li><b>「未交付」就写未交付</b>：导出与邮件 / IM 实际投递属 13.6.2.1，页面按能力声明原样显示。</li>
 *   <li><b>数据更新时间常驻</b>（卷 13 卷首「数据延迟要显示」）。</li>
 * </ol>
 */

/** 编辑器草稿。 */
interface Draft {
  reportId: string | null;
  reportCode: string;
  displayName: string;
  description: string;
  visibility: string;
  chartType: string;
  windowFrom: string;
  windowTo: string;
  measureMetricCodes: string[];
  dimensionCodes: string[];
  filters: { dimensionCode: string; operator: string; valueText: string; valueText2: string }[];
}

const EMPTY_DRAFT: Draft = {
  reportId: null,
  reportCode: '',
  displayName: '',
  description: '',
  visibility: 'PRIVATE',
  chartType: 'TABLE',
  windowFrom: '',
  windowTo: '',
  measureMetricCodes: [],
  dimensionCodes: [],
  filters: [],
};

/** 筛选运算符白名单（服务端只认这五个）。 */
const OPERATORS = [
  { value: 'EQ', label: '等于' },
  { value: 'IN', label: '属于（逗号分隔）' },
  { value: 'GTE', label: '不早于' },
  { value: 'LTE', label: '不晚于' },
  { value: 'BETWEEN', label: '介于（上下两个值）' },
];

const CHART_TYPES = [
  { value: 'TABLE', label: '表格' },
  { value: 'BAR', label: '柱状图' },
  { value: 'LINE', label: '折线图' },
  { value: 'PIE', label: '饼图' },
];

function today(): string {
  return new Date().toISOString().slice(0, 10);
}

export function CustomReportWorkspace() {
  const [capabilities, setCapabilities] = useState<CustomReportCapabilities | null>(null);
  const [metrics, setMetrics] = useState<CustomReportMetricView[]>([]);
  const [dimensions, setDimensions] = useState<CustomReportDimensionView[]>([]);
  const [page, setPage] = useState<CustomReportPage | null>(null);
  const [keyword, setKeyword] = useState('');
  const [visibility, setVisibility] = useState('');
  const [listError, setListError] = useState('');
  const [loading, setLoading] = useState(true);

  const [draft, setDraft] = useState<Draft | null>(null);
  const [saveError, setSaveError] = useState('');
  const [saving, setSaving] = useState(false);

  const [current, setCurrent] = useState<CustomReportView | null>(null);
  const [result, setResult] = useState<CustomReportRunResponse | null>(null);
  const [resultError, setResultError] = useState('');
  const [from, setFrom] = useState(today());
  const [to, setTo] = useState(today());
  const [resultPage, setResultPage] = useState(0);

  const [shares, setShares] = useState<CustomReportShareView[]>([]);
  const [subscriptions, setSubscriptions] = useState<CustomReportSubscriptionView[]>([]);
  const [deliveries, setDeliveries] = useState<CustomReportDeliveryPage | null>(null);
  const [cron, setCron] = useState('0 9 * * *');
  const [channel, setChannel] = useState('EMAIL');
  const [target, setTarget] = useState('');
  const [actionError, setActionError] = useState('');
  /** 分享与订阅只有创建者能管 ⇒ 非创建者打开团队报表时如实说明，不报错。 */
  const [manageNote, setManageNote] = useState('');

  // ── 目录与清单 ────────────────────────────────────────────────────────
  const loadLists = useCallback(async () => {
    setLoading(true);
    setListError('');
    try {
      const query = new URLSearchParams();
      if (keyword.trim()) query.set('keyword', keyword.trim());
      if (visibility) query.set('visibility', visibility);
      query.set('page', '0');
      query.set('size', '20');
      const [caps, metricDefs, dimensionDefs, reportPage] = await Promise.all([
        api<CustomReportCapabilities>('reporting/custom-report/capabilities'),
        api<CustomReportMetricView[]>('reporting/custom-report/metrics'),
        api<CustomReportDimensionView[]>('reporting/custom-report/dimensions'),
        api<CustomReportPage>(`reporting/custom-report/reports?${query.toString()}`),
      ]);
      setCapabilities(caps);
      setMetrics(metricDefs);
      setDimensions(dimensionDefs);
      setPage(reportPage);
    } catch (e) {
      setListError(e instanceof Error ? e.message : '读取失败，请重试');
    } finally {
      setLoading(false);
    }
  }, [keyword, visibility]);

  useEffect(() => {
    void loadLists();
  }, [loadLists]);

  // ── 打开一份报表 ──────────────────────────────────────────────────────
  const openReport = useCallback(async (reportId: string) => {
    setActionError('');
    setResultError('');
    setResult(null);
    try {
      const view = await api<CustomReportView>(`reporting/custom-report/reports/${reportId}`);
      setCurrent(view);
      setDraft({
        reportId: view.reportId,
        reportCode: view.reportCode,
        displayName: view.displayName,
        description: view.description,
        visibility: view.visibility,
        chartType: view.chartType,
        windowFrom: view.windowFrom ?? '',
        windowTo: view.windowTo ?? '',
        measureMetricCodes: view.measures.map((measure) => measure.metricCode),
        dimensionCodes: [...view.dimensions],
        filters: view.filters.map((filter) => ({
          dimensionCode: filter.dimensionCode,
          operator: filter.operator,
          valueText: filter.valueText,
          valueText2: filter.valueText2,
        })),
      });
      setFrom(view.windowFrom ?? today());
      setTo(view.windowTo ?? today());
    } catch (e) {
      setActionError(e instanceof Error ? e.message : '读取报表失败，请重试');
      return;
    }

    // 🔴 分享与订阅是**创建者才管得了**的东西（服务端对非创建者返回 404）。
    //   这里**单独**加载并单独兜住：非创建者打开一份团队报表时**不该整个打不开**。
    setManageNote('');
    try {
      setShares(
        await api<CustomReportShareView[]>(`reporting/custom-report/reports/${reportId}/shares`),
      );
    } catch {
      setShares([]);
      setManageNote('分享与订阅只有这份报表的创建者能管理。');
    }
    try {
      setSubscriptions(
        await api<CustomReportSubscriptionView[]>(
          `reporting/custom-report/reports/${reportId}/subscriptions`,
        ),
      );
    } catch {
      setSubscriptions([]);
    }
  }, []);

  // ── 跑报表 ────────────────────────────────────────────────────────────
  const runReport = useCallback(
    async (reportId: string, targetPage = 0) => {
      setResultError('');
      try {
        const response = await api<CustomReportRunResponse>(
          `reporting/custom-report/reports/${reportId}/run`,
          'POST',
          { from, to, page: targetPage, size: 50 },
        );
        setResult(response);
        setResultPage(targetPage);
      } catch (e) {
        setResult(null);
        setResultError(e instanceof Error ? e.message : '跑报表失败，请重试');
      }
    },
    [from, to],
  );

  // ── 保存 ──────────────────────────────────────────────────────────────
  const save = useCallback(async () => {
    if (!draft) return;
    setSaving(true);
    setSaveError('');
    try {
      const body = {
        reportCode: draft.reportCode.trim(),
        displayName: draft.displayName.trim(),
        description: draft.description.trim(),
        visibility: draft.visibility,
        chartType: draft.chartType,
        windowFrom: draft.windowFrom || null,
        windowTo: draft.windowTo || null,
        measureMetricCodes: draft.measureMetricCodes,
        dimensionCodes: draft.dimensionCodes,
        filters: draft.filters.map((filter) => ({
          dimensionCode: filter.dimensionCode,
          operator: filter.operator,
          valueText: filter.valueText,
          valueText2: filter.valueText2 || null,
        })),
      };
      const saved = draft.reportId
        ? await api<CustomReportView>(
            `reporting/custom-report/reports/${draft.reportId}`,
            'PUT',
            body,
          )
        : await api<CustomReportView>('reporting/custom-report/reports', 'POST', body);
      setDraft(null);
      setCurrent(saved);
      await loadLists();
      await openReport(saved.reportId);
    } catch (e) {
      setSaveError(e instanceof Error ? e.message : '保存失败，请检查输入后重试');
    } finally {
      setSaving(false);
    }
  }, [draft, loadLists, openReport]);

  const removeReport = useCallback(async () => {
    if (!current) return;
    setActionError('');
    try {
      await api<void>(`reporting/custom-report/reports/${current.reportId}`, 'DELETE');
      setCurrent(null);
      setResult(null);
      setShares([]);
      setSubscriptions([]);
      await loadLists();
    } catch (e) {
      setActionError(e instanceof Error ? e.message : '删除失败，请重试');
    }
  }, [current, loadLists]);

  // ── 分享 ──────────────────────────────────────────────────────────────
  const createShare = useCallback(async () => {
    if (!current) return;
    setActionError('');
    try {
      await api<CustomReportShareView>(
        `reporting/custom-report/reports/${current.reportId}/shares`,
        'POST',
        { visibility: 'TEAM' },
      );
      setShares(
        await api<CustomReportShareView[]>(
          `reporting/custom-report/reports/${current.reportId}/shares`,
        ),
      );
    } catch (e) {
      setActionError(e instanceof Error ? e.message : '生成分享链接失败，请重试');
    }
  }, [current]);

  const revokeShare = useCallback(
    async (shareId: string) => {
      if (!current) return;
      setActionError('');
      try {
        await api<void>(`reporting/custom-report/shares/${shareId}`, 'DELETE');
        setShares(
          await api<CustomReportShareView[]>(
            `reporting/custom-report/reports/${current.reportId}/shares`,
          ),
        );
      } catch (e) {
        setActionError(e instanceof Error ? e.message : '撤销分享失败，请重试');
      }
    },
    [current],
  );

  // ── 订阅与投递 ────────────────────────────────────────────────────────
  const createSubscription = useCallback(async () => {
    if (!current) return;
    setActionError('');
    try {
      await api<CustomReportSubscriptionView>(
        `reporting/custom-report/reports/${current.reportId}/subscriptions`,
        'POST',
        { cronExpression: cron.trim(), channel, target: target.trim(), enabled: true },
      );
      setTarget('');
      setSubscriptions(
        await api<CustomReportSubscriptionView[]>(
          `reporting/custom-report/reports/${current.reportId}/subscriptions`,
        ),
      );
    } catch (e) {
      setActionError(e instanceof Error ? e.message : '保存订阅失败，请检查输入后重试');
    }
  }, [current, cron, channel, target]);

  const toggleSubscription = useCallback(
    async (subscriptionId: string, enabled: boolean) => {
      if (!current) return;
      setActionError('');
      try {
        await api<CustomReportSubscriptionView>(
          `reporting/custom-report/subscriptions/${subscriptionId}`,
          'PUT',
          { enabled },
        );
        setSubscriptions(
          await api<CustomReportSubscriptionView[]>(
            `reporting/custom-report/reports/${current.reportId}/subscriptions`,
          ),
        );
      } catch (e) {
        setActionError(e instanceof Error ? e.message : '切换订阅失败，请重试');
      }
    },
    [current],
  );

  const deleteSubscription = useCallback(
    async (subscriptionId: string) => {
      if (!current) return;
      setActionError('');
      try {
        await api<void>(`reporting/custom-report/subscriptions/${subscriptionId}`, 'DELETE');
        setSubscriptions(
          await api<CustomReportSubscriptionView[]>(
            `reporting/custom-report/reports/${current.reportId}/subscriptions`,
          ),
        );
      } catch (e) {
        setActionError(e instanceof Error ? e.message : '删除订阅失败，请重试');
      }
    },
    [current],
  );

  const loadDeliveries = useCallback(async () => {
    setActionError('');
    try {
      setDeliveries(
        await api<CustomReportDeliveryPage>('reporting/custom-report/deliveries?page=0&size=20'),
      );
    } catch (e) {
      setActionError(e instanceof Error ? e.message : '读取待投递记录失败，请重试');
    }
  }, []);

  const tick = useCallback(async () => {
    setActionError('');
    try {
      await api<{ enqueued: number; advanced: number; note: string }>(
        'reporting/custom-report/deliveries/tick',
        'POST',
      );
      await loadDeliveries();
    } catch (e) {
      setActionError(e instanceof Error ? e.message : '跑一轮失败，请重试');
    }
  }, [loadDeliveries]);

  // ── 渲染辅助 ──────────────────────────────────────────────────────────
  const state = pageState(result, resultError);
  const metricByCode = useMemo(() => {
    const map = new Map<string, CustomReportMetricView>();
    for (const metric of metrics) map.set(metric.metricCode, metric);
    return map;
  }, [metrics]);

  function measureCellText(cell: CustomReportMeasureCell): string {
    const lines = measureValueLines(cell);
    if (lines.length === 0) return '—';
    return lines.join(' / ');
  }

  return (
    <div className="space-y-4">
      {/* ── 能力声明横幅（导出 / 投递未交付就写未交付）──────────────── */}
      <div className="border-border bg-muted/40 flex flex-wrap items-center justify-between gap-3 rounded-lg border px-4 py-3">
        <div className="text-copy-13 text-foreground">
          <span className="font-medium">自定义报表：只按已登记的语义层指标自助</span>
          <span className="text-muted-foreground ml-2">
            口径唯一（reporting_metric_definition），不开放任意 SQL。
          </span>
        </div>
        <div className="text-copy-13 flex flex-wrap items-center gap-2">
          <Badge tone={exportAvailable(capabilities) ? 'success' : 'warning'}>
            导出：{exportAvailable(capabilities) ? '可用' : '未交付'}
          </Badge>
          <Badge tone={deliveryAvailable(capabilities) ? 'success' : 'warning'}>
            邮件 / IM 投递：{deliveryAvailable(capabilities) ? '可用' : '未交付'}
          </Badge>
          <Button variant="outline" size="sm" disabled={loading} onClick={() => void loadLists()}>
            <RefreshCw className="mr-1.5 h-3.5 w-3.5" />
            刷新
          </Button>
        </div>
      </div>

      {capabilities && (!exportAvailable(capabilities) || !deliveryAvailable(capabilities)) && (
        <SectionCard title="这一版还不能做什么" description="如实写清楚，比做一个假按钮好。">
          <ul className="text-copy-13 text-muted-foreground list-disc space-y-1 pl-5">
            {!exportAvailable(capabilities) && <li>{exportUnavailableReason(capabilities)}</li>}
            {!deliveryAvailable(capabilities) && <li>{deliveryUnavailableReason(capabilities)}</li>}
          </ul>
        </SectionCard>
      )}

      {capabilities && (
        <SectionCard title="判定规则与口径纪律" description="页面上照着显示，不在代码注释里。">
          <ul className="text-copy-13 text-muted-foreground list-disc space-y-1 pl-5">
            {capabilities.rules.map((rule) => (
              <li key={rule.ruleCode}>{rule.description}</li>
            ))}
          </ul>
        </SectionCard>
      )}

      {/* ── 报表清单 ──────────────────────────────────────────────────── */}
      <SectionCard
        title="报表清单"
        description="业务人员自助构建：选数据源（已登记的语义层指标）→ 选维度 → 加筛选 → 保存。"
      >
        <div className="flex flex-wrap items-end gap-3">
          <label className="text-copy-13 text-muted-foreground flex flex-col gap-1">
            搜索
            <input
              type="search"
              value={keyword}
              onChange={(e) => setKeyword(e.target.value)}
              placeholder="报表名称或编码"
              className="border-border bg-background rounded-md border px-2 py-1.5 text-sm"
            />
          </label>
          <label className="text-copy-13 text-muted-foreground flex flex-col gap-1">
            可见范围
            <select
              value={visibility}
              onChange={(e) => setVisibility(e.target.value)}
              className="border-border bg-background rounded-md border px-2 py-1.5 text-sm"
            >
              <option value="">全部</option>
              <option value="PRIVATE">仅自己可见</option>
              <option value="TEAM">团队可见</option>
            </select>
          </label>
          <Button
            onClick={() =>
              setDraft({
                ...EMPTY_DRAFT,
                windowFrom: from,
                windowTo: to,
              })
            }
          >
            新建报表
          </Button>
        </div>

        {listError && <p className="text-copy-13 text-destructive mt-3">读取失败：{listError}</p>}

        {!listError && page && page.rows.length === 0 && (
          <EmptyState title="没有报表" description={listEmptyText(page)} />
        )}

        {!listError && page && page.rows.length > 0 && (
          <div className="mt-4 overflow-x-auto">
            <table className="w-full text-left text-sm">
              <thead className="text-muted-foreground border-border border-b">
                <tr>
                  <th className="py-2 pr-3 font-medium">报表</th>
                  <th className="py-2 pr-3 font-medium">构成</th>
                  <th className="py-2 pr-3 font-medium">可见范围</th>
                  <th className="py-2 font-medium">操作</th>
                </tr>
              </thead>
              <tbody>
                {page.rows.map((row) => (
                  <tr key={row.reportId} className="border-border/60 border-b">
                    <td className="py-2 pr-3">
                      <div className="font-medium">{row.displayName}</div>
                      <div className="text-muted-foreground text-copy-13">{row.reportCode}</div>
                    </td>
                    <td className="text-muted-foreground py-2 pr-3">{summaryText(row)}</td>
                    <td className="py-2 pr-3">
                      <Badge tone="neutral">{visibilityLabel(row.visibility)}</Badge>
                    </td>
                    <td className="py-2">
                      <Button
                        size="sm"
                        variant="outline"
                        onClick={() => void openReport(row.reportId)}
                      >
                        打开
                      </Button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
            <p className="text-copy-13 text-muted-foreground mt-2">共 {page.total} 份报表。</p>
          </div>
        )}
      </SectionCard>

      {/* ── 编辑器 ────────────────────────────────────────────────────── */}
      {draft && (
        <SectionCard
          title={draft.reportId ? '编辑报表配置' : '新建报表配置'}
          description="🔴 度量只能选**已登记**的指标码；维度只能选白名单里的码；筛选值只作为参数传给服务端。"
        >
          <div className="flex flex-wrap gap-4">
            <label className="text-copy-13 text-muted-foreground flex flex-col gap-1">
              报表编码
              <input
                value={draft.reportCode}
                onChange={(e) => setDraft({ ...draft, reportCode: e.target.value })}
                className="border-border bg-background rounded-md border px-2 py-1.5 text-sm"
              />
            </label>
            <label className="text-copy-13 text-muted-foreground flex flex-col gap-1">
              报表名称
              <input
                value={draft.displayName}
                onChange={(e) => setDraft({ ...draft, displayName: e.target.value })}
                className="border-border bg-background rounded-md border px-2 py-1.5 text-sm"
              />
            </label>
            <label className="text-copy-13 text-muted-foreground flex flex-col gap-1">
              可见范围
              <select
                value={draft.visibility}
                onChange={(e) => setDraft({ ...draft, visibility: e.target.value })}
                className="border-border bg-background rounded-md border px-2 py-1.5 text-sm"
              >
                <option value="PRIVATE">仅自己可见</option>
                <option value="TEAM">团队可见</option>
              </select>
            </label>
            <label className="text-copy-13 text-muted-foreground flex flex-col gap-1">
              图表类型
              <select
                value={draft.chartType}
                onChange={(e) => setDraft({ ...draft, chartType: e.target.value })}
                className="border-border bg-background rounded-md border px-2 py-1.5 text-sm"
              >
                {CHART_TYPES.map((type) => (
                  <option key={type.value} value={type.value}>
                    {type.label}
                  </option>
                ))}
              </select>
            </label>
            <label className="text-copy-13 text-muted-foreground flex flex-col gap-1">
              默认起始日
              <input
                type="date"
                value={draft.windowFrom}
                onChange={(e) => setDraft({ ...draft, windowFrom: e.target.value })}
                className="border-border bg-background rounded-md border px-2 py-1.5 text-sm"
              />
            </label>
            <label className="text-copy-13 text-muted-foreground flex flex-col gap-1">
              默认结束日
              <input
                type="date"
                value={draft.windowTo}
                onChange={(e) => setDraft({ ...draft, windowTo: e.target.value })}
                className="border-border bg-background rounded-md border px-2 py-1.5 text-sm"
              />
            </label>
          </div>

          <label className="text-copy-13 text-muted-foreground mt-4 flex flex-col gap-1">
            报表说明
            <textarea
              value={draft.description}
              onChange={(e) => setDraft({ ...draft, description: e.target.value })}
              rows={2}
              className="border-border bg-background rounded-md border px-2 py-1.5 text-sm"
            />
          </label>

          <div className="mt-4">
            <p className="text-copy-13 text-muted-foreground mb-2">
              度量（可用的语义层指标）—— 口径全部取自服务端的口径表，页面上原样显示。
            </p>
            <div className="grid gap-2 sm:grid-cols-2 lg:grid-cols-3">
              {metrics.map((metric) => {
                const checked = draft.measureMetricCodes.includes(metric.metricCode);
                return (
                  <label
                    key={metric.metricCode}
                    className="border-border flex items-start gap-2 rounded-md border px-2 py-1.5 text-sm"
                  >
                    <input
                      type="checkbox"
                      checked={checked}
                      onChange={() =>
                        setDraft({
                          ...draft,
                          measureMetricCodes: checked
                            ? draft.measureMetricCodes.filter((code) => code !== metric.metricCode)
                            : [...draft.measureMetricCodes, metric.metricCode],
                        })
                      }
                    />
                    <span>
                      <span className="font-medium">{metric.displayName}</span>
                      <span className="text-muted-foreground">（{metric.metricCode}）</span>
                      <span className="text-muted-foreground text-copy-13 block">
                        {aggregationLabel(metric.aggregation)}
                        {metric.aggregation === 'DERIVED_RATIO' ||
                        metric.aggregation === 'DERIVED_DIFF'
                          ? ` · ${definitionFormula(metric)}`
                          : ''}
                      </span>
                      <span className="text-muted-foreground text-copy-13 block">
                        {metric.description}
                      </span>
                    </span>
                  </label>
                );
              })}
            </div>
          </div>

          <div className="mt-4">
            <p className="text-copy-13 text-muted-foreground mb-2">
              维度（白名单）—— 🔴 法人 / 店铺给的是 <strong>uuid 文本</strong>，语义层不带名称档案。
            </p>
            <div className="flex flex-wrap gap-3">
              {dimensions.map((dimension) => {
                const checked = draft.dimensionCodes.includes(dimension.dimensionCode);
                return (
                  <label key={dimension.dimensionCode} className="flex items-center gap-2 text-sm">
                    <input
                      type="checkbox"
                      checked={checked}
                      onChange={() =>
                        setDraft({
                          ...draft,
                          dimensionCodes: checked
                            ? draft.dimensionCodes.filter(
                                (code) => code !== dimension.dimensionCode,
                              )
                            : [...draft.dimensionCodes, dimension.dimensionCode],
                        })
                      }
                    />
                    <span>{dimension.label}</span>
                  </label>
                );
              })}
            </div>
          </div>

          <div className="mt-4">
            <p className="text-copy-13 text-muted-foreground mb-2">
              筛选 —— 值只作为<strong>绑定参数</strong>传给服务端，绝不拼进 SQL。
            </p>
            {draft.filters.map((filter, index) => (
              <div
                key={`${filter.dimensionCode}-${index}`}
                className="border-border/60 mb-2 flex flex-wrap items-end gap-2 border-b pb-2"
              >
                <label className="text-copy-13 text-muted-foreground flex flex-col gap-1">
                  维度
                  <select
                    value={filter.dimensionCode}
                    onChange={(e) => {
                      const next = [...draft.filters];
                      next[index] = { ...filter, dimensionCode: e.target.value };
                      setDraft({ ...draft, filters: next });
                    }}
                    className="border-border bg-background rounded-md border px-2 py-1.5 text-sm"
                  >
                    {dimensions.map((dimension) => (
                      <option key={dimension.dimensionCode} value={dimension.dimensionCode}>
                        {dimension.label}
                      </option>
                    ))}
                  </select>
                </label>
                <label className="text-copy-13 text-muted-foreground flex flex-col gap-1">
                  运算符
                  <select
                    value={filter.operator}
                    onChange={(e) => {
                      const next = [...draft.filters];
                      next[index] = { ...filter, operator: e.target.value };
                      setDraft({ ...draft, filters: next });
                    }}
                    className="border-border bg-background rounded-md border px-2 py-1.5 text-sm"
                  >
                    {OPERATORS.map((operator) => (
                      <option key={operator.value} value={operator.value}>
                        {operator.label}
                      </option>
                    ))}
                  </select>
                </label>
                <label className="text-copy-13 text-muted-foreground flex flex-col gap-1">
                  值
                  <input
                    value={filter.valueText}
                    onChange={(e) => {
                      const next = [...draft.filters];
                      next[index] = { ...filter, valueText: e.target.value };
                      setDraft({ ...draft, filters: next });
                    }}
                    className="border-border bg-background rounded-md border px-2 py-1.5 text-sm"
                  />
                </label>
                {filter.operator === 'BETWEEN' && (
                  <label className="text-copy-13 text-muted-foreground flex flex-col gap-1">
                    上界
                    <input
                      value={filter.valueText2}
                      onChange={(e) => {
                        const next = [...draft.filters];
                        next[index] = { ...filter, valueText2: e.target.value };
                        setDraft({ ...draft, filters: next });
                      }}
                      className="border-border bg-background rounded-md border px-2 py-1.5 text-sm"
                    />
                  </label>
                )}
                <Button
                  size="sm"
                  variant="ghost"
                  onClick={() =>
                    setDraft({
                      ...draft,
                      filters: draft.filters.filter((_, position) => position !== index),
                    })
                  }
                >
                  移除
                </Button>
              </div>
            ))}
            <Button
              size="sm"
              variant="outline"
              onClick={() =>
                setDraft({
                  ...draft,
                  filters: [
                    ...draft.filters,
                    {
                      dimensionCode: dimensions[0]?.dimensionCode ?? 'SHOP',
                      operator: 'EQ',
                      valueText: '',
                      valueText2: '',
                    },
                  ],
                })
              }
            >
              加一条筛选
            </Button>
          </div>

          {saveError && <p className="text-copy-13 text-destructive mt-3">{saveError}</p>}

          <div className="mt-4 flex gap-2">
            <Button disabled={saving} onClick={() => void save()}>
              {saving ? '保存中…' : '保存报表'}
            </Button>
            <Button variant="outline" onClick={() => setDraft(null)}>
              取消
            </Button>
          </div>
        </SectionCard>
      )}

      {/* ── 打开的报表 ────────────────────────────────────────────────── */}
      {current && !draft && (
        <SectionCard
          title={current.displayName}
          description={`${current.reportCode} · ${visibilityLabel(current.visibility)} · ${chartTypeLabel(current.chartType)}`}
        >
          {actionError && <p className="text-copy-13 text-destructive mb-3">{actionError}</p>}
          {manageNote && <p className="text-copy-13 text-muted-foreground mb-3">{manageNote}</p>}

          <div className="flex flex-wrap items-end gap-3">
            <label className="text-copy-13 text-muted-foreground flex flex-col gap-1">
              起始日期
              <input
                type="date"
                value={from}
                max={to}
                onChange={(e) => setFrom(e.target.value)}
                className="border-border bg-background rounded-md border px-2 py-1.5 text-sm"
              />
            </label>
            <label className="text-copy-13 text-muted-foreground flex flex-col gap-1">
              结束日期
              <input
                type="date"
                value={to}
                min={from}
                onChange={(e) => setTo(e.target.value)}
                className="border-border bg-background rounded-md border px-2 py-1.5 text-sm"
              />
            </label>
            <Button onClick={() => void runReport(current.reportId, 0)}>跑报表</Button>
            <Button
              variant="outline"
              onClick={() => {
                setDraft({
                  reportId: current.reportId,
                  reportCode: current.reportCode,
                  displayName: current.displayName,
                  description: current.description,
                  visibility: current.visibility,
                  chartType: current.chartType,
                  windowFrom: current.windowFrom ?? '',
                  windowTo: current.windowTo ?? '',
                  measureMetricCodes: current.measures.map((measure) => measure.metricCode),
                  dimensionCodes: [...current.dimensions],
                  filters: current.filters.map((filter) => ({
                    dimensionCode: filter.dimensionCode,
                    operator: filter.operator,
                    valueText: filter.valueText,
                    valueText2: filter.valueText2,
                  })),
                });
              }}
            >
              编辑配置
            </Button>
            <Button variant="ghost" onClick={() => void removeReport()}>
              删除
            </Button>
          </div>

          {/* 结果 */}
          <div className="mt-4">
            <div className="text-copy-13 text-muted-foreground mb-2">
              <span className="text-foreground font-medium">{dataUpdatedText(result)}</span>
              <span className="ml-2">{dataUpdatedNote(result)}</span>
            </div>

            {state === 'error' && (
              <EmptyState
                icon={<TriangleAlert className="h-6 w-6" />}
                title="跑不了这份报表"
                description={resultError || '请稍后重试。'}
              />
            )}
            {state === 'loading' && (
              <EmptyState title="还没有跑过这份报表…" description="选好时间范围后点「跑报表」。" />
            )}
            {state === 'empty' && (
              <EmptyState
                icon={<TriangleAlert className="h-6 w-6" />}
                title="这个窗口里没有数据"
                description={emptyText(result)}
              />
            )}
            {state === 'ready' && result && (
              <>
                <div className="overflow-x-auto">
                  <table className="w-full text-left text-sm">
                    <thead className="text-muted-foreground border-border border-b">
                      <tr>
                        {dimensionColumns(result).map((label) => (
                          <th key={label} className="py-2 pr-3 font-medium">
                            {label}
                          </th>
                        ))}
                        {result.measures.map((measure) => (
                          <th key={measure.metricCode} className="py-2 pr-3 font-medium">
                            {measureColumnLabel(measure)}
                          </th>
                        ))}
                      </tr>
                    </thead>
                    <tbody>
                      {result.rows.map((row, index) => (
                        <tr key={`${index}-${row.dimensions.map((d) => d.value).join('|')}`}>
                          {rowDimensionTexts(row).map((text, position) => (
                            <td key={`d-${position}`} className="py-2 pr-3">
                              {text}
                            </td>
                          ))}
                          {row.measures.map((measure) => (
                            <td key={measure.metricCode} className="py-2 pr-3">
                              <div>{measureCellText(measure)}</div>
                              {measureAvailabilityText(measure) && (
                                <div className="text-muted-foreground text-copy-13">
                                  {measureAvailabilityText(measure)}
                                </div>
                              )}
                            </td>
                          ))}
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
                <p className="text-copy-13 text-muted-foreground mt-2">
                  共 {result.total} 个维度组合（本页 {result.rows.length} 个）。
                </p>
                <div className="mt-2 flex gap-2">
                  <Button
                    size="sm"
                    variant="outline"
                    disabled={resultPage === 0}
                    onClick={() => void runReport(current.reportId, resultPage - 1)}
                  >
                    上一页
                  </Button>
                  <Button
                    size="sm"
                    variant="outline"
                    disabled={(resultPage + 1) * 50 >= result.total}
                    onClick={() => void runReport(current.reportId, resultPage + 1)}
                  >
                    下一页
                  </Button>
                </div>
              </>
            )}
          </div>

          {/* 口径 */}
          {current.measures.length > 0 && (
            <div className="mt-4">
              <p className="text-copy-13 text-muted-foreground mb-2">
                口径（取自服务端的口径表，原样显示）：
              </p>
              <ul className="text-copy-13 space-y-1">
                {current.measures.map((measure) => (
                  <li key={measure.metricCode}>
                    <span className="font-medium">{measure.displayName}</span>
                    <span className="text-muted-foreground">（{measure.metricCode}）</span>
                    <span className="text-muted-foreground">
                      {' · '}
                      {aggregationLabel(measure.aggregation)}
                      {measure.currencyAware ? ' · 涉币（不跨币种相加）' : ' · 与币种无关'}
                    </span>
                    <span className="text-muted-foreground block">{measure.description}</span>
                    {metricByCode.get(measure.metricCode)?.ownerModule && (
                      <span className="text-muted-foreground block">
                        口径归属：{metricByCode.get(measure.metricCode)?.ownerModule}
                      </span>
                    )}
                  </li>
                ))}
              </ul>
            </div>
          )}

          {/* 分享 */}
          <div className="border-border mt-4 border-t pt-4">
            <p className="text-copy-13 text-muted-foreground mb-2">
              分享链接 —— 🔴 分享链接<strong>不等于公开访问</strong>：令牌绑定租户 + 绑定归属，
              未授权访问与「报表不存在」返回同样的 404。
            </p>
            <Button size="sm" variant="outline" onClick={() => void createShare()}>
              <Link2 className="mr-1.5 h-3.5 w-3.5" />
              生成分享链接
            </Button>
            {shares.length > 0 && (
              <ul className="text-copy-13 mt-2 space-y-1">
                {shares.map((share) => (
                  <li key={share.shareId} className="flex flex-wrap items-center gap-2">
                    <span className="font-mono">{share.sharePath}</span>
                    <Badge tone="neutral">{visibilityLabel(share.visibility)}</Badge>
                    {share.revokedAt ? (
                      <Badge tone="neutral">已撤销</Badge>
                    ) : (
                      <Button
                        size="sm"
                        variant="ghost"
                        onClick={() => void revokeShare(share.shareId)}
                      >
                        撤销
                      </Button>
                    )}
                  </li>
                ))}
              </ul>
            )}
          </div>

          {/* 订阅 */}
          <div className="border-border mt-4 border-t pt-4">
            <p className="text-copy-13 text-muted-foreground mb-2">
              定时订阅 —— 🔴 本模块只登记<strong>订阅计划</strong>与<strong>待投递记录</strong>；
              邮件 / IM 的实际投递属 13.6.2.1（未交付）。
            </p>
            <div className="flex flex-wrap items-end gap-2">
              <label className="text-copy-13 text-muted-foreground flex flex-col gap-1">
                cron（分 时 日 月 周）
                <input
                  value={cron}
                  onChange={(e) => setCron(e.target.value)}
                  className="border-border bg-background rounded-md border px-2 py-1.5 text-sm"
                />
              </label>
              <label className="text-copy-13 text-muted-foreground flex flex-col gap-1">
                通道
                <select
                  value={channel}
                  onChange={(e) => setChannel(e.target.value)}
                  className="border-border bg-background rounded-md border px-2 py-1.5 text-sm"
                >
                  <option value="EMAIL">邮件</option>
                  <option value="IM">企业 IM</option>
                </select>
              </label>
              <label className="text-copy-13 text-muted-foreground flex flex-col gap-1">
                收件目标
                <input
                  value={target}
                  onChange={(e) => setTarget(e.target.value)}
                  placeholder="邮箱 / IM 群 id"
                  className="border-border bg-background rounded-md border px-2 py-1.5 text-sm"
                />
              </label>
              <Button size="sm" onClick={() => void createSubscription()}>
                新增订阅
              </Button>
            </div>
            {subscriptions.length > 0 && (
              <ul className="text-copy-13 mt-2 space-y-1">
                {subscriptions.map((subscription) => (
                  <li
                    key={subscription.subscriptionId}
                    className="flex flex-wrap items-center gap-2"
                  >
                    <span className="font-mono">{subscription.cronExpression}</span>
                    <span>{subscription.channel}</span>
                    <span>{subscription.target}</span>
                    <span className="text-muted-foreground">{nextRunText(subscription)}</span>
                    <Button
                      size="sm"
                      variant="ghost"
                      onClick={() =>
                        void toggleSubscription(subscription.subscriptionId, !subscription.enabled)
                      }
                    >
                      {subscription.enabled ? '停用' : '启用'}
                    </Button>
                    <Button
                      size="sm"
                      variant="ghost"
                      onClick={() => void deleteSubscription(subscription.subscriptionId)}
                    >
                      删除
                    </Button>
                  </li>
                ))}
              </ul>
            )}
          </div>
        </SectionCard>
      )}

      {/* ── 待投递记录台账 ────────────────────────────────────────────── */}
      <SectionCard
        title="待投递记录台账"
        description="🔴 这些记录是「待投递」，不是「已发送」—— 实际投递属 13.6.2.1（未交付）。"
      >
        <div className="flex gap-2">
          <Button variant="outline" size="sm" onClick={() => void loadDeliveries()}>
            刷新
          </Button>
          <Button variant="outline" size="sm" onClick={() => void tick()}>
            跑一轮（外部调度触发）
          </Button>
        </div>
        {deliveries && deliveries.rows.length === 0 && (
          <EmptyState title="还没有待投递记录" description="订阅计划到点后会落成待投递记录。" />
        )}
        {deliveries && deliveries.rows.length > 0 && (
          <div className="mt-3 overflow-x-auto">
            <table className="w-full text-left text-sm">
              <thead className="text-muted-foreground border-border border-b">
                <tr>
                  <th className="py-2 pr-3 font-medium">报表</th>
                  <th className="py-2 pr-3 font-medium">计划时刻</th>
                  <th className="py-2 font-medium">状态</th>
                </tr>
              </thead>
              <tbody>
                {deliveries.rows.map((delivery) => (
                  <tr key={delivery.deliveryId} className="border-border/60 border-b">
                    <td className="py-2 pr-3">{delivery.reportCode}</td>
                    <td className="text-muted-foreground py-2 pr-3">{delivery.scheduledFor}</td>
                    <td className="py-2">
                      <Badge tone="neutral">{deliveryStatusLabel(delivery.status)}</Badge>
                      <span className="text-muted-foreground text-copy-13 ml-2">
                        {deliverySummary(delivery)}
                      </span>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </SectionCard>
    </div>
  );
}
