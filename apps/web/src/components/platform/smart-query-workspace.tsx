'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import { Badge, Button, EmptyState, Input, PageHeader, SectionCard } from '@starcon/ui';
import { CircleAlert, Link2, Loader2, RefreshCw, Search, Star, Table2 } from 'lucide-react';
import {
  aggregationLabel,
  conditionsSummary,
  detailLinkLabel,
  factLocationLabel,
  factRefLabel,
  filterLabel,
  formatValue,
  isInternalRoute,
  matchModeLabel,
  matchModeTone,
  measureAvailabilityNote,
  measureCellText,
  periodLabel,
  querySummary,
  toResultView,
  unitLabel,
  valueKindLabel,
  type SmartQueryCapabilities,
  type SmartQueryPage,
  type SmartQueryResult,
  type SmartQuerySemanticLayer,
} from './smart-query-model';
import { api } from '../../lib/api';

/**
 * 18.4.1.2「智能查询」工作区（前端入口 `/platform/smart-query`）。
 *
 * 蓝图：「**职责**：结构化智能查询：自然语言转查询条件（筛选、聚合、排序），
 * 生成报表草稿，支持保存为常用查询。**输入**：自然语言查询意图；可用指标与维度清单。
 * **输出**：查询结果表 / 报表配置草稿。**关系**：上游 = 18.4.1.1；下游 = 13.6.1.1 自定义报表。
 * **自然语言只映射到受控语义层指标，不生成任意 SQL。**」
 *
 * 🔴 页面上的七条纪律（与 `smart-query-model.ts` 一一对应）：
 *
 * 1. **解析不出来就不显示结果**：后端 `resolved=false` ⇒ 显示「没有得出结论 + 缺什么 + 归谁」，
 *    **不显示任何像样的表格**。
 * 2. **没有明细依据的结果不显示成结果**：后端说有行却没给依据（不该发生），页面按「没出结论」处理。
 * 3. **数值全程十进制文本**：后端给的是 `NUMERIC` 字符串，前端不转 `number`。
 * 4. **算不出来 ≠ 0**：后端给 `null` 的取值显示「—」。
 * 5. **比率已经是百分数**：只补 % 不乘 100。
 * 6. **口径与时间窗口常驻**：每条结果都带着受控条件、口径与窗口。
 * 7. **加载 / 空 / 错误三态齐全**：目录空、没查过、请求失败、上游读不到（503）各有各的说法。
 *
 * ⚠️ **本模块没有「自由 SQL」入口**：页面上唯一能做的事就是「问一句话」或
 * 「从受控词表里显式挑一个指标 / 维度」—— 没有输入 SQL 的地方。
 */

const base = 'platform/smart-query';

type Tab = 'ask' | 'layer' | 'ledger' | 'capabilities';

const TABS: Array<{ value: Tab; label: string }> = [
  { value: 'ask', label: '提问' },
  { value: 'layer', label: '受控语义层' },
  { value: 'ledger', label: '查询台账 / 常用查询' },
  { value: 'capabilities', label: '能力声明' },
];

const BADGE_TONE = {
  ok: 'success',
  warn: 'warning',
  danger: 'danger',
  muted: 'neutral',
} as const;

export function SmartQueryWorkspace() {
  const [tab, setTab] = useState<Tab>('ask');
  const [question, setQuestion] = useState('');
  const [metricOverride, setMetricOverride] = useState('');
  const [dimensionOverride, setDimensionOverride] = useState('');
  const [result, setResult] = useState<SmartQueryResult | null>(null);
  const [layer, setLayer] = useState<SmartQuerySemanticLayer | null>(null);
  const [capabilities, setCapabilities] = useState<SmartQueryCapabilities | null>(null);
  const [ledger, setLedger] = useState<SmartQueryPage | null>(null);
  const [ledgerKeyword, setLedgerKeyword] = useState('');
  const [ledgerSavedOnly, setLedgerSavedOnly] = useState(false);
  const [loading, setLoading] = useState(false);
  const [loadingCatalog, setLoadingCatalog] = useState(true);
  const [loadingLedger, setLoadingLedger] = useState(false);
  const [error, setError] = useState('');
  const [ledgerError, setLedgerError] = useState('');

  // ---- 语义层目录与能力声明（页面一进来就拉）
  useEffect(() => {
    let cancelled = false;
    Promise.all([
      api<SmartQuerySemanticLayer>(`${base}/semantic-layer`),
      api<SmartQueryCapabilities>(`${base}/capabilities`),
    ])
      .then(([semanticLayer, capabilityView]) => {
        if (cancelled) return;
        setLayer(semanticLayer);
        setCapabilities(capabilityView);
      })
      .catch((cause: unknown) => {
        if (cancelled) return;
        setError(cause instanceof Error ? cause.message : '受控语义层目录加载失败');
      })
      .finally(() => {
        if (!cancelled) setLoadingCatalog(false);
      });
    return () => {
      cancelled = true;
    };
  }, []);

  // ---- 查询台账（真分页 + 真搜索 + 真计数）
  const loadLedger = useCallback(async (keyword: string, savedOnly: boolean) => {
    setLoadingLedger(true);
    setLedgerError('');
    try {
      const params = new URLSearchParams();
      if (keyword.trim()) params.set('keyword', keyword.trim());
      if (savedOnly) params.set('saved', 'true');
      params.set('page', '0');
      params.set('size', '50');
      setLedger(await api<SmartQueryPage>(`${base}/queries?${params.toString()}`));
    } catch (cause: unknown) {
      setLedgerError(
        cause instanceof Error ? cause.message : '查询台账加载失败；若是「读不到」，那是上游没取到',
      );
    } finally {
      setLoadingLedger(false);
    }
  }, []);

  useEffect(() => {
    void loadLedger('', false);
  }, [loadLedger]);

  // ---- 提问（自然语言 → 受控查询条件 → 结果表 + 明细依据）
  const ask = useCallback(
    async (text: string) => {
      const trimmed = text.trim();
      if (!trimmed) {
        setError('问句不能为空');
        return;
      }
      setLoading(true);
      setError('');
      try {
        const answer = await api<SmartQueryResult>(`${base}/query`, 'POST', {
          question: trimmed,
          ...(metricOverride ? { metricCode: metricOverride } : {}),
          ...(dimensionOverride ? { dimensionCode: dimensionOverride } : {}),
        });
        setResult(answer);
        void loadLedger(ledgerKeyword, ledgerSavedOnly);
      } catch (cause: unknown) {
        setError(
          cause instanceof Error
            ? cause.message
            : '查询失败；若是「读不到」，那是上游事实没取到，不是「没有数据」',
        );
      } finally {
        setLoading(false);
      }
    },
    [dimensionOverride, ledgerKeyword, ledgerSavedOnly, loadLedger, metricOverride],
  );

  // ---- 常用查询：存 / 取消存 / 重跑
  const saveFavorite = useCallback(
    async (queryId: string, displayName: string) => {
      setError('');
      try {
        await api<unknown>(`${base}/queries/${queryId}/save`, 'POST', { displayName });
        await loadLedger(ledgerKeyword, ledgerSavedOnly);
      } catch (cause: unknown) {
        setError(cause instanceof Error ? cause.message : '保存常用查询失败');
      }
    },
    [ledgerKeyword, ledgerSavedOnly, loadLedger],
  );

  const removeFavorite = useCallback(
    async (queryId: string) => {
      setError('');
      try {
        await api<unknown>(`${base}/queries/${queryId}/save`, 'DELETE');
        await loadLedger(ledgerKeyword, ledgerSavedOnly);
      } catch (cause: unknown) {
        setError(cause instanceof Error ? cause.message : '取消常用查询失败');
      }
    },
    [ledgerKeyword, ledgerSavedOnly, loadLedger],
  );

  const rerun = useCallback(async (queryId: string) => {
    setLoading(true);
    setError('');
    try {
      setResult(await api<SmartQueryResult>(`${base}/queries/${queryId}/run`, 'POST'));
      setTab('ask');
    } catch (cause: unknown) {
      setError(cause instanceof Error ? cause.message : '重跑失败');
    } finally {
      setLoading(false);
    }
  }, []);

  const view = useMemo(() => toResultView(result), [result]);
  const availableMetrics = useMemo(
    () => (layer?.metrics ?? []).filter((metric) => metric.available),
    [layer],
  );

  return (
    <div className="space-y-4">
      <PageHeader
        title="智能查询"
        description="把一句中文变成受控的查询条件（筛选 / 聚合 / 排序），并给出带明细链接的结果表。自然语言只映射到受控语义层指标，不生成任意 SQL。"
        actions={
          <div className="flex items-center gap-2">
            {TABS.map((item) => (
              <Button
                key={item.value}
                size="sm"
                variant={tab === item.value ? 'default' : 'secondary'}
                onClick={() => setTab(item.value)}
              >
                {item.label}
              </Button>
            ))}
          </div>
        }
      />

      {tab === 'ask' && (
        <>
          <SectionCard
            title="提问"
            description="问句只会落到受控语义层的指标与维度上；解析不到就如实答「不支持这个问法 + 缺什么 + 归谁」，不会编一个数字出来。"
            extra={
              loading ? (
                <span className="text-copy-13 text-muted-foreground flex items-center gap-1">
                  <Loader2 className="h-3.5 w-3.5 animate-spin" /> 正在查
                </span>
              ) : null
            }
          >
            <form
              className="flex flex-col gap-3"
              onSubmit={(event) => {
                event.preventDefault();
                void ask(question);
              }}
            >
              <Input
                value={question}
                placeholder="例如：上周各店铺的销售额排名 / 本月各店铺的毛利率 / 最近 7 天订单量"
                onChange={(event) => setQuestion(event.target.value)}
                disabled={loading}
              />
              <div className="flex flex-wrap items-center gap-2">
                <select
                  className="rounded border px-2 py-1 text-sm"
                  value={metricOverride}
                  onChange={(event) => setMetricOverride(event.target.value)}
                  disabled={loading}
                  aria-label="显式指定受控指标"
                >
                  <option value="">指标：按问句自动匹配</option>
                  {availableMetrics.map((metric) => (
                    <option key={metric.metricCode} value={metric.metricCode}>
                      {metric.displayName}（{metric.metricCode}）
                    </option>
                  ))}
                </select>
                <select
                  className="rounded border px-2 py-1 text-sm"
                  value={dimensionOverride}
                  onChange={(event) => setDimensionOverride(event.target.value)}
                  disabled={loading}
                  aria-label="显式指定受控维度"
                >
                  <option value="">维度：按问句自动匹配</option>
                  {(layer?.dimensions ?? []).map((dimension) => (
                    <option key={dimension.dimensionCode} value={dimension.dimensionCode}>
                      {dimension.displayName}（{dimension.dimensionCode}）
                    </option>
                  ))}
                </select>
                <Button type="submit" disabled={loading || !question.trim()}>
                  {loading ? '正在查…' : '查询'}
                </Button>
                <Button
                  variant="ghost"
                  size="sm"
                  onClick={() => {
                    setQuestion('');
                    setResult(null);
                    setError('');
                  }}
                >
                  <RefreshCw className="mr-1 h-3.5 w-3.5" /> 清空
                </Button>
              </div>
              <p className="text-copy-12 text-muted-foreground">
                🔴 页面**没有**输入 SQL
                的地方：指标与维度只能从受控词表里挑（见「受控语义层」页签）。
              </p>
            </form>

            {error ? (
              <div className="border-destructive/40 bg-destructive-soft mt-4 flex items-start gap-2 rounded-md border px-3 py-2">
                <CircleAlert className="text-destructive mt-0.5 h-4 w-4 shrink-0" />
                <div className="text-copy-13 text-foreground">
                  <div className="font-medium">这一轮没跑通</div>
                  <div className="text-muted-foreground">{error}</div>
                </div>
              </div>
            ) : null}
          </SectionCard>

          {result ? (
            <SectionCard
              title="查询结果"
              extra={<Badge tone={BADGE_TONE[view.tone]}>{matchModeLabel(result.matchMode)}</Badge>}
              description={
                view.conditions
                  ? conditionsSummary(view.conditions)
                  : '这句话没有落到受控语义层的任何条目上'
              }
            >
              {view.resolved ? (
                <>
                  {view.conditions ? (
                    <div className="text-copy-13 text-muted-foreground space-y-1">
                      <div>
                        聚合口径：{aggregationLabel(view.conditions.aggregation)}
                        （口径表登记的聚合方式）
                      </div>
                      <div>
                        时间窗口：{view.conditions.periodFrom} ~ {view.conditions.periodTo}（
                        {periodLabel(view.conditions.periodCode)}）
                      </div>
                      {view.conditions.filters.length ? (
                        <div>筛选：{view.conditions.filters.map(filterLabel).join('；')}</div>
                      ) : null}
                      {view.conditions.caliber ? (
                        <div className="text-copy-12">口径：{view.conditions.caliber}</div>
                      ) : null}
                    </div>
                  ) : null}

                  {view.rows.length ? (
                    <div className="mt-4 overflow-x-auto">
                      <table className="w-full text-sm">
                        <thead>
                          <tr className="text-muted-foreground border-b text-left">
                            {result.dimensionColumns.map((column) => (
                              <th key={column.dimensionCode} className="py-2">
                                {column.label}
                              </th>
                            ))}
                            {result.metricColumns.map((column) => (
                              <th key={column.metricCode} className="py-2">
                                {column.displayName}
                                <span className="text-muted-foreground ml-1 text-xs">
                                  {unitLabel(column.unit)}
                                </span>
                              </th>
                            ))}
                          </tr>
                        </thead>
                        <tbody>
                          {view.rows.map((row) => (
                            <tr key={row.seq} className="border-b last:border-0">
                              {result.dimensionColumns.map((column) => {
                                const cell = row.dimensions.find(
                                  (item) => item.dimensionCode === column.dimensionCode,
                                );
                                return (
                                  <td key={column.dimensionCode} className="py-2">
                                    {cell?.value || '—'}
                                  </td>
                                );
                              })}
                              {result.metricColumns.map((column) => {
                                const measure = row.measures.find(
                                  (item) => item.metricCode === column.metricCode,
                                );
                                const note = measure ? measureAvailabilityNote(measure) : '';
                                return (
                                  <td key={column.metricCode} className="py-2">
                                    <div>{measure ? measureCellText(measure) : '—'}</div>
                                    {note ? (
                                      <div className="text-copy-12 text-muted-foreground">
                                        {note}
                                      </div>
                                    ) : null}
                                  </td>
                                );
                              })}
                            </tr>
                          ))}
                        </tbody>
                      </table>
                    </div>
                  ) : null}

                  {result.caliberNotes.length ? (
                    <div className="mt-4 space-y-1">
                      {result.caliberNotes.map((note) => (
                        <div key={note} className="text-copy-12 text-muted-foreground">
                          {note}
                        </div>
                      ))}
                    </div>
                  ) : null}

                  <div className="mt-4 space-y-2">
                    <div className="text-copy-13 text-foreground font-medium">
                      明细依据（{view.evidence.length} 条）
                    </div>
                    {view.evidence.map((item, index) => (
                      <div
                        key={`${item.factRef}-${index}`}
                        className="border-border rounded-md border px-3 py-2"
                      >
                        <div className="flex flex-wrap items-center justify-between gap-2">
                          <span className="text-copy-14 text-foreground font-medium">
                            {item.factLabel}
                          </span>
                          <span className="text-copy-13 text-foreground">
                            {formatValue(item.metricValue, 'CURRENCY', item.metricUnit)}
                          </span>
                        </div>
                        <div className="text-copy-12 text-muted-foreground mt-1">
                          {factLocationLabel(item)}
                          {factRefLabel(item) ? ` · ${factRefLabel(item)}` : ''}
                        </div>
                        {item.caliberNote ? (
                          <div className="text-copy-12 text-muted-foreground mt-1">
                            {item.caliberNote}
                          </div>
                        ) : null}
                        {isInternalRoute(item.detailRoute) ? (
                          <a
                            className="text-copy-13 text-brand mt-2 inline-flex items-center gap-1 underline"
                            href={item.detailRoute}
                          >
                            <Link2 className="h-3.5 w-3.5" />
                            {detailLinkLabel(item)}
                          </a>
                        ) : null}
                      </div>
                    ))}
                  </div>

                  {view.nextActions.length ? (
                    <div className="mt-4">
                      <div className="text-copy-13 text-foreground font-medium">建议的下一步</div>
                      <div className="mt-2 flex flex-wrap gap-2">
                        {view.nextActions.map((action) => (
                          <a
                            key={action.route}
                            className="border-border text-copy-13 hover:bg-muted rounded-md border px-3 py-1.5"
                            href={action.route}
                          >
                            {action.label}
                            <span className="text-muted-foreground ml-1">{action.moduleId}</span>
                          </a>
                        ))}
                      </div>
                    </div>
                  ) : null}
                </>
              ) : (
                <div className="flex items-start gap-2">
                  <CircleAlert
                    className={
                      view.tone === 'danger'
                        ? 'text-destructive mt-0.5 h-4 w-4 shrink-0'
                        : 'text-warning mt-0.5 h-4 w-4 shrink-0'
                    }
                  />
                  <div>
                    <p className="text-copy-14 text-foreground font-medium">
                      {view.resolved ? '没有得出结论' : '没有得出结论'}
                    </p>
                    <p className="text-copy-13 text-muted-foreground mt-1">{view.headline}</p>
                    {view.ownerModule ? (
                      <p className="text-copy-13 text-muted-foreground mt-1">
                        归口模块：{view.ownerModule}
                      </p>
                    ) : null}
                  </div>
                </div>
              )}
            </SectionCard>
          ) : null}
        </>
      )}

      {tab === 'layer' && (
        <>
          <SectionCard
            title="受控语义层目录"
            description="这是「这个模块能查什么」的唯一真相：问句只允许落到下面这些条目上；词表里没有的指标解析不出来，如实答「不支持这个问法」。"
            extra={
              loadingCatalog ? (
                <span className="text-copy-13 text-muted-foreground">正在加载…</span>
              ) : null
            }
          >
            {loadingCatalog ? null : !layer ? (
              <EmptyState title="语义层目录还没加载出来" description="请稍后重试。" />
            ) : (
              <div className="space-y-4">
                <div>
                  <div className="text-copy-13 text-foreground font-medium">
                    指标（{layer.metrics.length} 条）
                  </div>
                  <div className="mt-2 space-y-2">
                    {layer.metrics.map((metric) => (
                      <div
                        key={metric.metricCode}
                        className="border-border rounded-md border px-3 py-2"
                      >
                        <div className="flex flex-wrap items-center justify-between gap-2">
                          <span className="text-copy-14 text-foreground font-medium">
                            {metric.displayName}
                            <span className="text-muted-foreground ml-1 text-xs">
                              {metric.metricCode}
                            </span>
                          </span>
                          <Badge tone={metric.available ? 'success' : 'warning'}>
                            {metric.available ? '已接通' : '未接通'}
                          </Badge>
                        </div>
                        <div className="text-copy-13 text-muted-foreground mt-1">
                          单位 {unitLabel(metric.unit)} · {aggregationLabel(metric.aggregation)} ·
                          数据源 {metric.sourceService} · {metric.sourceEndpoint}
                        </div>
                        <div className="text-copy-12 text-muted-foreground mt-1">
                          {metric.sourceCaliber}
                        </div>
                        {metric.unavailableReason ? (
                          <div className="text-copy-12 text-warning mt-1">
                            {metric.unavailableReason}
                          </div>
                        ) : null}
                        <div className="mt-2 flex flex-wrap items-center gap-2">
                          <span className="text-copy-12 text-muted-foreground">问法示例：</span>
                          {metric.strongKeywords.slice(0, 3).map((keyword) => (
                            <Button
                              key={keyword}
                              size="sm"
                              variant="secondary"
                              onClick={() => {
                                setTab('ask');
                                setQuestion(keyword);
                                void ask(keyword);
                              }}
                            >
                              {keyword}
                            </Button>
                          ))}
                        </div>
                      </div>
                    ))}
                  </div>
                </div>

                <div>
                  <div className="text-copy-13 text-foreground font-medium">
                    维度（{layer.dimensions.length} 条）
                  </div>
                  <div className="mt-2 space-y-2">
                    {layer.dimensions.map((dimension) => (
                      <div
                        key={dimension.dimensionCode}
                        className="border-border rounded-md border px-3 py-2"
                      >
                        <div className="flex flex-wrap items-center justify-between gap-2">
                          <span className="text-copy-14 text-foreground font-medium">
                            {dimension.displayName}
                            <span className="text-muted-foreground ml-1 text-xs">
                              {dimension.dimensionCode}
                            </span>
                          </span>
                          <Badge tone={dimension.available ? 'success' : 'warning'}>
                            {dimension.available ? '已接通' : '未接通'}
                          </Badge>
                        </div>
                        <div className="text-copy-13 text-muted-foreground mt-1">
                          取值形态 {valueKindLabel(dimension.valueKind)} · 运算符{' '}
                          {dimension.allowedOperators.join(' / ')}
                        </div>
                        {dimension.unavailableReason ? (
                          <div className="text-copy-12 text-warning mt-1">
                            {dimension.unavailableReason}
                          </div>
                        ) : null}
                      </div>
                    ))}
                  </div>
                </div>

                {layer.rules.length ? (
                  <div>
                    <div className="text-copy-13 text-foreground font-medium">口径规则</div>
                    <div className="mt-2 space-y-1">
                      {layer.rules.map((rule) => (
                        <div key={rule} className="text-copy-12 text-muted-foreground">
                          {rule}
                        </div>
                      ))}
                    </div>
                  </div>
                ) : null}
              </div>
            )}
          </SectionCard>
        </>
      )}

      {tab === 'ledger' && (
        <SectionCard
          title="查询台账 / 常用查询"
          description="每次查询都会落台账（连同明细依据条数）；常用查询是**固化下来的一条查询条件**，重跑沿用它当初解析出来的口径，不重新猜。"
          extra={
            loadingLedger ? (
              <span className="text-copy-13 text-muted-foreground flex items-center gap-1">
                <Loader2 className="h-3.5 w-3.5 animate-spin" /> 正在加载
              </span>
            ) : null
          }
        >
          <div className="flex flex-wrap items-center gap-2">
            <Input
              className="max-w-xs"
              value={ledgerKeyword}
              placeholder="搜索问句 / 常用查询名"
              onChange={(event) => setLedgerKeyword(event.target.value)}
              onKeyDown={(event) => {
                if (event.key === 'Enter') void loadLedger(ledgerKeyword, ledgerSavedOnly);
              }}
            />
            <Button
              size="sm"
              variant="secondary"
              onClick={() => void loadLedger(ledgerKeyword, ledgerSavedOnly)}
            >
              <Search className="mr-1 h-3.5 w-3.5" /> 搜索
            </Button>
            <label className="text-copy-13 text-muted-foreground flex items-center gap-1">
              <input
                type="checkbox"
                checked={ledgerSavedOnly}
                onChange={(event) => {
                  setLedgerSavedOnly(event.target.checked);
                  void loadLedger(ledgerKeyword, event.target.checked);
                }}
              />
              只看常用查询
            </label>
            {ledger ? (
              <span className="text-copy-13 text-muted-foreground">共 {ledger.total} 条</span>
            ) : null}
          </div>

          {ledgerError ? (
            <div className="border-destructive/40 bg-destructive-soft mt-4 flex items-start gap-2 rounded-md border px-3 py-2">
              <CircleAlert className="text-destructive mt-0.5 h-4 w-4 shrink-0" />
              <div className="text-copy-13 text-foreground">{ledgerError}</div>
            </div>
          ) : null}

          {!loadingLedger && ledger && ledger.rows.length === 0 ? (
            <EmptyState
              icon={<Table2 className="h-6 w-6" />}
              title={ledgerKeyword || ledgerSavedOnly ? '没有匹配的查询' : '还没有查询记录'}
              description="问一句话，这里会记下它的受控条件与明细依据条数。"
            />
          ) : null}

          {ledger && ledger.rows.length ? (
            <div className="mt-4 overflow-x-auto">
              <table className="w-full text-sm">
                <thead>
                  <tr className="text-muted-foreground border-b text-left">
                    <th className="py-2">问句</th>
                    <th className="py-2">解析结果</th>
                    <th className="py-2">窗口</th>
                    <th className="py-2">依据</th>
                    <th className="py-2">操作</th>
                  </tr>
                </thead>
                <tbody>
                  {ledger.rows.map((row) => (
                    <tr key={row.queryId} className="border-b last:border-0">
                      <td className="py-2">
                        <div>{row.displayName || row.question}</div>
                        {row.displayName ? (
                          <div className="text-copy-12 text-muted-foreground">{row.question}</div>
                        ) : null}
                      </td>
                      <td className="py-2">
                        <div className="text-copy-13">{querySummary(row)}</div>
                        <div className="text-copy-12 text-muted-foreground mt-0.5">
                          <Badge tone={BADGE_TONE[matchModeTone(row.matchMode)]}>
                            {matchModeLabel(row.matchMode)}
                          </Badge>
                          {row.ownerModule ? (
                            <span className="text-muted-foreground ml-1">
                              归口 {row.ownerModule}
                            </span>
                          ) : null}
                        </div>
                      </td>
                      <td className="text-copy-13 py-2">
                        {row.periodFrom} ~ {row.periodTo}
                        <div className="text-copy-12 text-muted-foreground">
                          {periodLabel(row.periodCode)}
                        </div>
                      </td>
                      <td className="text-copy-13 py-2">{row.evidenceCount} 条</td>
                      <td className="py-2">
                        <div className="flex flex-wrap items-center gap-2">
                          <Button
                            size="sm"
                            variant="secondary"
                            onClick={() => void rerun(row.queryId)}
                          >
                            重跑
                          </Button>
                          {row.saved ? (
                            <Button
                              size="sm"
                              variant="ghost"
                              onClick={() => void removeFavorite(row.queryId)}
                            >
                              取消常用
                            </Button>
                          ) : (
                            <Button
                              size="sm"
                              variant="ghost"
                              onClick={() =>
                                void saveFavorite(
                                  row.queryId,
                                  row.question.slice(0, 60) || '常用查询',
                                )
                              }
                            >
                              <Star className="mr-1 h-3.5 w-3.5" /> 存为常用
                            </Button>
                          )}
                        </div>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          ) : null}
        </SectionCard>
      )}

      {tab === 'capabilities' && (
        <SectionCard title="能力声明" description="哪些真接通、哪些还没交付、归谁。">
          {capabilities === null ? (
            <EmptyState title="能力声明还没加载出来" description="请稍后重试。" />
          ) : (
            <div className="space-y-4">
              <div className="space-y-2">
                {capabilities.capabilities.map((capability) => (
                  <div key={capability.name} className="border-border rounded-md border px-3 py-2">
                    <div className="flex flex-wrap items-center justify-between gap-2">
                      <span className="text-copy-14 text-foreground font-medium">
                        {capability.moduleId} {capability.name}
                      </span>
                      <Badge tone={capability.available ? 'success' : 'warning'}>
                        {capability.available ? '已接通' : '未接通'}
                      </Badge>
                    </div>
                    <div className="text-copy-13 text-muted-foreground mt-1">
                      {capability.evidence}
                    </div>
                    {capability.note ? (
                      <div className="text-copy-13 text-muted-foreground mt-1">
                        {capability.note}
                      </div>
                    ) : null}
                    {capability.ownerModule ? (
                      <div className="text-copy-12 text-muted-foreground mt-1">
                        归口模块：{capability.ownerModule}
                      </div>
                    ) : null}
                  </div>
                ))}
              </div>
              {capabilities.gaps.length ? (
                <div>
                  <div className="text-copy-13 text-foreground font-medium">下游与兄弟能力缺口</div>
                  <div className="mt-2 space-y-2">
                    {capabilities.gaps.map((gap) => (
                      <div key={gap.moduleId} className="border-border rounded-md border px-3 py-2">
                        <div className="flex flex-wrap items-center justify-between gap-2">
                          <span className="text-copy-13 text-foreground">
                            {gap.moduleId} {gap.name}
                          </span>
                          <Badge tone={gap.available ? 'success' : 'warning'}>
                            {gap.available ? '已接通' : '未接通'}
                          </Badge>
                        </div>
                        <div className="text-copy-13 text-muted-foreground mt-1">{gap.note}</div>
                      </div>
                    ))}
                  </div>
                </div>
              ) : null}
            </div>
          )}
        </SectionCard>
      )}
    </div>
  );
}
