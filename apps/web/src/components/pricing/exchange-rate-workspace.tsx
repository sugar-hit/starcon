'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import {
  Badge,
  Button,
  EmptyState,
  FormField,
  Input,
  Notice,
  PageHeader,
  SectionCard,
  StatCard,
} from '@starcon/ui';

import {
  EMPTY_RATE_FILTERS,
  RATE_SECTIONS,
  createRate,
  createRateSource,
  dayText,
  entryModeLabel,
  entryModeTone,
  fetchExternalRate,
  fetchFunctionalCurrencies,
  fetchRateCapabilities,
  fetchRateList,
  fetchRateLogs,
  fetchRateSources,
  fetchStatusLabel,
  fetchStatusTone,
  initializeRateSources,
  inverseText,
  rateDetailText,
  rateText,
  sourceKindLabel,
  timeText,
  updateRateSource,
  validateRateInput,
  validateSourceInput,
} from './exchange-rate-model';
import type {
  FetchLogRow,
  FetchResult,
  FunctionalCurrencyPanel,
  RateCapabilities,
  RateFilters,
  RateInput,
  RateListPage,
  RateSource,
} from './exchange-rate-model';

/**
 * 5.2.2.1 汇率查询工作区。
 *
 * <p>🔴 页面最上面那一块**不是提示语，是这一页的设计前提**：
 * 蓝图加粗句「**汇率方向必须显式**（1 USD = X CNY 与 1 CNY = X USD 混淆是常见错源）」。
 *
 * <p>所以本页把三件事摆在最显眼的位置：
 *
 * <ol>
 *   <li><b>方向规则</b>：基准币种 → 报价币种，rate 表示 1 基准 = rate 报价；
 *   <li><b>每一行都带完整方向</b>：显示的是「1 USD = 7.1234 CNY」这样的整句，
 *       表格里**没有**任何一列是裸的汇率数字；
 *   <li><b>反向汇率标注为推导值</b>：由正向推导出来的反向值写明「由 … 推导」，
 *       不让人误以为它是另一条独立事实。
 * </ol>
 *
 * <p>🔴 **缺一条汇率就是缺一条**：查不到时显示「未登记」而不是 0.0000 ——
 * 「1 USD = 0 CNY」这种话一旦被记下来，后面每一次换算与利润都是错的。
 *
 * <p>🔴 **分区块结构**：5.2.2.2 汇率快照与本模块共用 L3 路由 {@code /pricing/rates}。
 * 本模块只占「汇率查询」这一块；未交付的兄弟分区**显式标成未交付**（不画空白框冒充已交付）。
 */
export function ExchangeRateWorkspace() {
  const [capabilities, setCapabilities] = useState<RateCapabilities | null>(null);
  const [sources, setSources] = useState<RateSource[]>([]);
  const [page, setPage] = useState<RateListPage | null>(null);
  const [filters, setFilters] = useState<RateFilters>(EMPTY_RATE_FILTERS);
  const [panel, setPanel] = useState<FunctionalCurrencyPanel | null>(null);
  const [panelError, setPanelError] = useState('');
  const [logs, setLogs] = useState<FetchLogRow[]>([]);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [busy, setBusy] = useState(false);
  const [loaded, setLoaded] = useState(false);

  // 手工录入表单
  const [form, setForm] = useState<RateInput>({
    baseCurrency: 'USD',
    quoteCurrency: 'CNY',
    rate: '',
    effectiveDate: today(),
    sourceCode: '',
    sourceRef: '',
    note: '',
  });
  const [problems, setProblems] = useState<string[]>([]);

  // 来源配置表单
  const [sourceForm, setSourceForm] = useState({
    code: '',
    name: '',
    kind: 'MANUAL',
    endpoint: '',
  });
  const [sourceProblems, setSourceProblems] = useState<string[]>([]);

  // 外部拉取表单
  const [fetchForm, setFetchForm] = useState({
    sourceCode: '',
    baseCurrency: 'USD',
    quoteCurrency: 'CNY',
    effectiveDate: today(),
  });
  const [fetchResult, setFetchResult] = useState<FetchResult | null>(null);

  const load = useCallback(async () => {
    setBusy(true);
    setError('');
    try {
      const [next, caps, sourceList] = await Promise.all([
        fetchRateList(filters),
        fetchRateCapabilities(),
        fetchRateSources(),
      ]);
      setPage(next);
      setCapabilities(caps);
      setSources(sourceList);
      setLoaded(true);
    } catch (e) {
      setError(e instanceof Error ? e.message : '汇率台账读不到');
    } finally {
      setBusy(false);
    }
  }, [filters]);

  useEffect(() => {
    void load();
  }, [load]);

  /** 本位币面板单独读：读不到时只影响这一块，不影响台账。 */
  const loadPanel = useCallback(async () => {
    setPanelError('');
    try {
      setPanel(await fetchFunctionalCurrencies());
    } catch (e) {
      setPanel(null);
      setPanelError(e instanceof Error ? e.message : '本位币读不到');
    }
  }, []);

  useEffect(() => {
    void loadPanel();
  }, [loadPanel]);

  const loadLogs = useCallback(async () => {
    try {
      setLogs(await fetchRateLogs(0, 20));
    } catch {
      // 拉取日志读不到不影响主流程（错误已在主 load 里报过）。
    }
  }, []);

  useEffect(() => {
    void loadLogs();
  }, [loadLogs]);

  const enabledSources = useMemo(() => sources.filter((s) => s.enabled), [sources]);

  async function submitRate() {
    const found = validateRateInput(form);
    setProblems(found);
    if (found.length) return;
    setBusy(true);
    setError('');
    setNotice('');
    try {
      const saved = await createRate(form);
      setNotice(
        `已登记汇率：${rateText(saved)}（生效日 ${dayText(saved.effectiveDate)}，来源 ${saved.sourceCode}）。` +
          `反向 ${inverseText(saved)} —— 由正向推导，没有另存一条。`,
      );
      setForm((f) => ({ ...f, rate: '' }));
      await load();
    } catch (e) {
      setError(e instanceof Error ? e.message : '汇率登记失败');
    } finally {
      setBusy(false);
    }
  }

  async function submitSource() {
    const found = validateSourceInput(sourceForm);
    setSourceProblems(found);
    if (found.length) return;
    setBusy(true);
    setError('');
    setNotice('');
    try {
      const saved = await createRateSource({
        code: sourceForm.code.trim().toUpperCase(),
        name: sourceForm.name.trim(),
        kind: sourceForm.kind,
        enabled: true,
        priority: 100,
        endpoint: sourceForm.endpoint.trim() || null,
        note: '',
      });
      setNotice(`已登记汇率来源「${saved.name}」（${saved.code}）。`);
      setSourceForm({ code: '', name: '', kind: 'MANUAL', endpoint: '' });
      await load();
    } catch (e) {
      setError(e instanceof Error ? e.message : '汇率来源登记失败');
    } finally {
      setBusy(false);
    }
  }

  async function toggleSource(source: RateSource) {
    setBusy(true);
    setError('');
    setNotice('');
    try {
      const saved = await updateRateSource(source.code, {
        name: source.name,
        kind: source.kind,
        enabled: !source.enabled,
        priority: source.priority,
        endpoint: source.endpoint,
        note: source.note,
        version: source.version,
      });
      setNotice(
        `来源「${saved.name}」已${saved.enabled ? '启用' : '停用'}：` +
          `停用只挡住「再用它录入」，已登记的汇率仍可读。`,
      );
      await load();
    } catch (e) {
      setError(e instanceof Error ? e.message : '汇率来源更新失败');
    } finally {
      setBusy(false);
    }
  }

  async function initSources() {
    setBusy(true);
    setError('');
    setNotice('');
    try {
      const list = await initializeRateSources();
      setNotice(
        `已初始化内置来源（${list.length} 个）：` +
          `${list.map((s) => s.name).join('、')}。外部来源还需要在下面填地址才能拉取。`,
      );
      await load();
    } catch (e) {
      setError(e instanceof Error ? e.message : '内置来源初始化失败');
    } finally {
      setBusy(false);
    }
  }

  async function runFetch() {
    setBusy(true);
    setError('');
    setNotice('');
    setFetchResult(null);
    try {
      const result = await fetchExternalRate(fetchForm);
      setFetchResult(result);
      if (result.status === 'FAILED') {
        setError(result.errorMessage || '外部拉取失败');
      } else {
        setNotice(
          `外部拉取成功：取到 ${result.rowsFetched} 条，入库 ${result.rowsSaved} 条。` +
            (result.errorMessage ? `有跳过项：${result.errorMessage}` : ''),
        );
      }
      await Promise.all([load(), loadLogs()]);
    } catch (e) {
      setError(e instanceof Error ? e.message : '外部拉取失败');
    } finally {
      setBusy(false);
    }
  }

  const totalPages = page ? Math.max(1, Math.ceil(page.total / Math.max(1, page.size))) : 1;

  return (
    <div className="space-y-5">
      <PageHeader
        title="汇率查询"
        description="各币种对各本位币的汇率：方向显式、生效日与来源可见，支持手工录入与外部拉取。"
        actions={
          <Badge tone="brand">5.2.2.1 汇率查询</Badge>
        }
      />

      {error && (
        <Notice tone="danger" title="操作失败">
          {error}
        </Notice>
      )}
      {notice && (
        <Notice tone="success" title="已完成">
          {notice}
        </Notice>
      )}

      {/* 能力声明：方向规则摆在最显眼的位置 */}
      {capabilities && (
        <SectionCard
          title="本页口径声明"
          description="方向、唯一性与反向汇率的处理规则 —— 页面上的任何一处显示汇率都按这三条走。"
        >
          <div className="space-y-3">
            <Notice tone="info" title="汇率方向必须显式">
              {capabilities.directionRule}
            </Notice>
            <Notice tone="warning" title="同一 币种对 + 同一生效日 只能有一条">
              {capabilities.uniquenessRule}
            </Notice>
            <Notice tone="warning" title="反向汇率只能推导">
              {capabilities.inverseRule}
            </Notice>
            <div className="grid gap-3 md:grid-cols-2">
              <div className="rounded-md border border-control-border p-3">
                <p className="text-label-13 font-semibold">下游状态</p>
                <p className="text-copy-13 text-muted-foreground mt-1">
                  {capabilities.downstreamNote}
                </p>
              </div>
              <div className="rounded-md border border-control-border p-3">
                <p className="text-label-13 font-semibold">数据归属</p>
                <p className="text-copy-13 text-muted-foreground mt-1">
                  {capabilities.ownershipNote}
                </p>
              </div>
            </div>
            <p className="text-copy-12 text-muted-foreground">{capabilities.functionalCurrencyNote}</p>
          </div>
        </SectionCard>
      )}

      {/* 概览 */}
      <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
        <StatCard label="已登记汇率" value={page ? page.total : 0} />
        <StatCard label="启用的来源" value={enabledSources.length} />
        <StatCard label="本位币（法人）" value={panel ? panel.rows.length : 0} />
        <StatCard
          label="最近一次拉取"
          value={logs[0] ? fetchStatusLabel(logs[0].status) : '未拉取'}
        />
      </div>

      {/* 汇率台账 */}
      <SectionCard
        title="汇率台账"
        description="每一行都带完整方向。表格里没有裸的汇率数字 —— 裸数字加一句「汇率：7.12」正是方向混淆的来源。"
      >
        <div className="space-y-4">
          <div className="grid gap-3 md:grid-cols-6">
            <FormField id="filter-base" label="基准币种">
              <Input
                id="filter-base"
                value={filters.baseCurrency}
                placeholder="USD"
                onChange={(e) =>
                  setFilters({ ...filters, baseCurrency: e.target.value, page: 0 })
                }
              />
            </FormField>
            <FormField id="filter-quote" label="报价币种">
              <Input
                id="filter-quote"
                value={filters.quoteCurrency}
                placeholder="CNY"
                onChange={(e) =>
                  setFilters({ ...filters, quoteCurrency: e.target.value, page: 0 })
                }
              />
            </FormField>
            <FormField id="filter-source" label="来源">
              <Input
                id="filter-source"
                value={filters.sourceCode}
                placeholder="MANUAL"
                onChange={(e) =>
                  setFilters({ ...filters, sourceCode: e.target.value, page: 0 })
                }
              />
            </FormField>
            <FormField id="filter-from" label="生效日（起）">
              <Input
                id="filter-from"
                type="date"
                value={filters.dateFrom}
                onChange={(e) => setFilters({ ...filters, dateFrom: e.target.value, page: 0 })}
              />
            </FormField>
            <FormField id="filter-to" label="生效日（止）">
              <Input
                id="filter-to"
                type="date"
                value={filters.dateTo}
                onChange={(e) => setFilters({ ...filters, dateTo: e.target.value, page: 0 })}
              />
            </FormField>
            <FormField id="filter-keyword" label="关键词">
              <Input
                id="filter-keyword"
                value={filters.keyword}
                placeholder="币种 / 来源 / 凭据"
                onChange={(e) => setFilters({ ...filters, keyword: e.target.value, page: 0 })}
              />
            </FormField>
          </div>

          {!loaded && busy && (
            <p className="text-copy-13 text-muted-foreground">正在读取汇率台账…</p>
          )}

          {loaded && page && page.items.length === 0 && (
            <EmptyState
              title="还没有登记任何汇率"
              description="用下面的「手工录入」登记某一天的汇率，或先在「汇率来源配置」里初始化内置来源（中行 / 央行 / 平台结算 / 手工录入）。"
            />
          )}

          {page && page.items.length > 0 && (
            <div className="overflow-x-auto">
              <table className="w-full text-left text-copy-13">
                <thead className="text-muted-foreground">
                  <tr>
                    <th className="py-2 pr-3 font-medium">汇率（方向显式）</th>
                    <th className="py-2 pr-3 font-medium">反向（推导值）</th>
                    <th className="py-2 pr-3 font-medium">生效日</th>
                    <th className="py-2 pr-3 font-medium">来源</th>
                    <th className="py-2 pr-3 font-medium">录入方式</th>
                    <th className="py-2 font-medium">凭据 / 采集时刻</th>
                  </tr>
                </thead>
                <tbody>
                  {page.items.map((row) => (
                    <tr key={row.id} className="border-t border-control-border">
                      <td className="py-2 pr-3 font-medium">{rateText(row)}</td>
                      <td className="py-2 pr-3 text-muted-foreground">{inverseText(row)}</td>
                      <td className="py-2 pr-3">{dayText(row.effectiveDate)}</td>
                      <td className="py-2 pr-3">
                        {row.sourceName || row.sourceCode}
                        <span className="text-muted-foreground ml-1">({row.sourceCode})</span>
                      </td>
                      <td className="py-2 pr-3">
                        <Badge tone={entryModeTone(row.entryMode)}>
                          {entryModeLabel(row.entryMode)}
                        </Badge>
                      </td>
                      <td className="py-2 text-muted-foreground">
                        {row.sourceRef || '—'}
                        <span className="ml-1">{timeText(row.acquiredAt)}</span>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}

          {page && page.total > 0 && (
            <div className="flex items-center justify-between">
              <p className="text-copy-12 text-muted-foreground">
                共 {page.total} 条，第 {page.page + 1} / {totalPages} 页
              </p>
              <div className="flex gap-2">
                <Button
                  variant="outline"
                  size="sm"
                  disabled={page.page <= 0}
                  onClick={() => setFilters({ ...filters, page: page.page - 1 })}
                >
                  上一页
                </Button>
                <Button
                  variant="outline"
                  size="sm"
                  disabled={page.page + 1 >= totalPages}
                  onClick={() => setFilters({ ...filters, page: page.page + 1 })}
                >
                  下一页
                </Button>
              </div>
            </div>
          )}

          {page?.note && <p className="text-copy-12 text-muted-foreground">{page.note}</p>}
        </div>
      </SectionCard>

      {/* 手工录入 */}
      <SectionCard
        title="手工录入汇率"
        description="录入某一天的汇率。方向由「基准币种 / 报价币种」两个字段确定 —— 少一个都录不进来。"
      >
        <div className="space-y-4">
          {problems.length > 0 && (
            <Notice tone="danger" title="请先修正">
              <ul className="list-disc pl-4">
                {problems.map((p) => (
                  <li key={p}>{p}</li>
                ))}
              </ul>
            </Notice>
          )}
          <div className="grid gap-3 md:grid-cols-3">
            <FormField id="rate-base" label="基准币种（1 这个币）" required>
              <Input
                id="rate-base"
                value={form.baseCurrency}
                placeholder="USD"
                onChange={(e) => setForm({ ...form, baseCurrency: e.target.value })}
              />
            </FormField>
            <FormField id="rate-quote" label="报价币种（= 几个这个币）" required>
              <Input
                id="rate-quote"
                value={form.quoteCurrency}
                placeholder="CNY"
                onChange={(e) => setForm({ ...form, quoteCurrency: e.target.value })}
              />
            </FormField>
            <FormField id="rate-value" label="汇率" required>
              <Input
                id="rate-value"
                value={form.rate}
                placeholder="7.1234"
                onChange={(e) => setForm({ ...form, rate: e.target.value })}
              />
            </FormField>
            <FormField id="rate-date" label="生效日" required>
              <Input
                id="rate-date"
                type="date"
                value={form.effectiveDate}
                onChange={(e) => setForm({ ...form, effectiveDate: e.target.value })}
              />
            </FormField>
            <FormField id="rate-source" label="来源" required>
              <Input
                id="rate-source"
                value={form.sourceCode}
                placeholder="MANUAL"
                list="rate-source-options"
                onChange={(e) => setForm({ ...form, sourceCode: e.target.value })}
              />
            </FormField>
            <datalist id="rate-source-options">
              {sources.map((s) => (
                <option key={s.id} value={s.code}>
                  {s.name}
                </option>
              ))}
            </datalist>
            <FormField id="rate-ref" label="来源凭据">
              <Input
                id="rate-ref"
                value={form.sourceRef}
                placeholder="如中行牌价凭据号、平台结算单号"
                onChange={(e) => setForm({ ...form, sourceRef: e.target.value })}
              />
            </FormField>
          </div>
          <p className="text-copy-12 text-muted-foreground">
            录进去的方向：<span className="font-medium">1 {form.baseCurrency || '?'} = {form.rate || '?'} {form.quoteCurrency || '?'}</span>
            　·　反向由正向推导，不另存一条（手工再录一条方向相反的会被数据库拒绝）。
          </p>
          <Button variant="brand" disabled={busy} onClick={() => void submitRate()}>
            登记这条汇率
          </Button>
        </div>
      </SectionCard>

      {/* 汇率来源配置 */}
      <SectionCard
        title="汇率来源配置"
        description="来源是租户自己的配置：可改名、可停用、可指向自己的平台结算地址。停用只挡住「再用它录入」，已登记的汇率仍可读。"
        extra={
          <Button variant="outline" size="sm" disabled={busy} onClick={() => void initSources()}>
            初始化内置来源
          </Button>
        }
      >
        <div className="space-y-4">
          {sources.length === 0 && (
            <EmptyState
              title="还没有登记任何汇率来源"
              description="点「初始化内置来源」写入中行 / 央行 / 平台结算 / 手工录入四条来源，或在下面逐条新建。外部来源还需要填地址才能拉取。"
            />
          )}
          {sources.length > 0 && (
            <div className="overflow-x-auto">
              <table className="w-full text-left text-copy-13">
                <thead className="text-muted-foreground">
                  <tr>
                    <th className="py-2 pr-3 font-medium">来源</th>
                    <th className="py-2 pr-3 font-medium">类型</th>
                    <th className="py-2 pr-3 font-medium">外部地址</th>
                    <th className="py-2 pr-3 font-medium">已登记汇率</th>
                    <th className="py-2 font-medium">状态</th>
                  </tr>
                </thead>
                <tbody>
                  {sources.map((source) => (
                    <tr key={source.id} className="border-t border-control-border">
                      <td className="py-2 pr-3">
                        {source.name}
                        <span className="text-muted-foreground ml-1">({source.code})</span>
                      </td>
                      <td className="py-2 pr-3">{sourceKindLabel(source.kind)}</td>
                      <td className="py-2 pr-3 text-muted-foreground">
                        {source.endpoint || '未配置（不可拉取）'}
                      </td>
                      <td className="py-2 pr-3">{source.rateCount}</td>
                      <td className="py-2">
                        <Badge tone={source.enabled ? 'success' : 'neutral'}>
                          {source.enabled ? '启用' : '停用'}
                        </Badge>
                        <Button
                          variant="ghost"
                          size="sm"
                          className="ml-2"
                          disabled={busy}
                          onClick={() => void toggleSource(source)}
                        >
                          {source.enabled ? '停用' : '启用'}
                        </Button>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}

          {sourceProblems.length > 0 && (
            <Notice tone="danger" title="请先修正">
              <ul className="list-disc pl-4">
                {sourceProblems.map((p) => (
                  <li key={p}>{p}</li>
                ))}
              </ul>
            </Notice>
          )}
          <div className="grid gap-3 md:grid-cols-4">
            <FormField id="source-code" label="来源代码" required>
              <Input
                id="source-code"
                value={sourceForm.code}
                placeholder="PLATFORM_SETTLEMENT"
                onChange={(e) => setSourceForm({ ...sourceForm, code: e.target.value })}
              />
            </FormField>
            <FormField id="source-name" label="来源名称" required>
              <Input
                id="source-name"
                value={sourceForm.name}
                placeholder="平台结算汇率"
                onChange={(e) => setSourceForm({ ...sourceForm, name: e.target.value })}
              />
            </FormField>
            <FormField id="source-kind" label="来源类型" required>
              <Input
                id="source-kind"
                value={sourceForm.kind}
                placeholder="MANUAL / EXTERNAL"
                onChange={(e) => setSourceForm({ ...sourceForm, kind: e.target.value })}
              />
            </FormField>
            <FormField id="source-endpoint" label="外部地址（仅外部拉取）">
              <Input
                id="source-endpoint"
                value={sourceForm.endpoint}
                placeholder="https://…"
                onChange={(e) => setSourceForm({ ...sourceForm, endpoint: e.target.value })}
              />
            </FormField>
          </div>
          <Button variant="outline" disabled={busy} onClick={() => void submitSource()}>
            新建这个来源
          </Button>
        </div>
      </SectionCard>

      {/* 外部拉取 */}
      <SectionCard
        title="外部拉取"
        description="真的向来源配置的地址发起 HTTP 请求。拉取失败会留下失败日志与具体原因，不会静默返回空。"
        extra={
          capabilities ? (
            <span className="text-copy-12 text-muted-foreground max-w-xl text-right">
              {capabilities.fetchContractNote}
            </span>
          ) : null
        }
      >
        <div className="space-y-4">
          <div className="grid gap-3 md:grid-cols-4">
            <FormField id="fetch-source" label="来源" required>
              <Input
                id="fetch-source"
                value={fetchForm.sourceCode}
                placeholder="BANK_OF_CHINA"
                list="fetch-source-options"
                onChange={(e) => setFetchForm({ ...fetchForm, sourceCode: e.target.value })}
              />
            </FormField>
            <datalist id="fetch-source-options">
              {sources
                .filter((s) => s.kind === 'EXTERNAL')
                .map((s) => (
                  <option key={s.id} value={s.code}>
                    {s.name}
                  </option>
                ))}
            </datalist>
            <FormField id="fetch-base" label="基准币种" required>
              <Input
                id="fetch-base"
                value={fetchForm.baseCurrency}
                onChange={(e) => setFetchForm({ ...fetchForm, baseCurrency: e.target.value })}
              />
            </FormField>
            <FormField id="fetch-quote" label="报价币种" required>
              <Input
                id="fetch-quote"
                value={fetchForm.quoteCurrency}
                onChange={(e) => setFetchForm({ ...fetchForm, quoteCurrency: e.target.value })}
              />
            </FormField>
            <FormField id="fetch-date" label="生效日" required>
              <Input
                id="fetch-date"
                type="date"
                value={fetchForm.effectiveDate}
                onChange={(e) => setFetchForm({ ...fetchForm, effectiveDate: e.target.value })}
              />
            </FormField>
          </div>
          <Button variant="brand" disabled={busy} onClick={() => void runFetch()}>
            从外部源拉取
          </Button>

          {fetchResult && (
            <Notice
              tone={fetchStatusTone(fetchResult.status)}
              title={`外部拉取${fetchStatusLabel(fetchResult.status)}`}
            >
              <p>
                取到 {fetchResult.rowsFetched} 条，入库 {fetchResult.rowsSaved} 条
                {fetchResult.httpStatus ? `（源返回 ${fetchResult.httpStatus}）` : ''}。
              </p>
              {fetchResult.errorMessage && <p className="mt-1">{fetchResult.errorMessage}</p>}
              {fetchResult.rates.map((r) => (
                <p key={r.id} className="mt-1">
                  {rateDetailText(r)}
                </p>
              ))}
            </Notice>
          )}

          <div>
            <p className="text-label-13 font-semibold">拉取日志（只追加）</p>
            {logs.length === 0 ? (
              <p className="text-copy-13 text-muted-foreground mt-1">还没有拉取记录。</p>
            ) : (
              <ul className="mt-2 space-y-1">
                {logs.map((log) => (
                  <li key={log.id} className="text-copy-12 flex items-center gap-2">
                    <Badge tone={fetchStatusTone(log.status)}>
                      {fetchStatusLabel(log.status)}
                    </Badge>
                    <span>
                      {log.sourceCode} · {log.baseCurrency ?? '?'} → {log.quoteCurrency ?? '?'} ·{' '}
                      {dayText(log.requestedDate)}
                    </span>
                    <span className="text-muted-foreground">
                      取到 {log.rowsFetched} / 入库 {log.rowsSaved}
                    </span>
                    {log.errorMessage && (
                      <span className="text-destructive">{log.errorMessage}</span>
                    )}
                    <span className="text-muted-foreground">{timeText(log.requestedAt)}</span>
                  </li>
                ))}
              </ul>
            )}
          </div>
        </div>
      </SectionCard>

      {/* 各币种对各本位币的汇率 */}
      <SectionCard
        title="各币种对各本位币的汇率"
        description="本位币归法人（17.1.2.2，identity 服务）。每条汇率按记录本身的显示句呈现方向。"
      >
        {panelError ? (
          <Notice tone="danger" title="本位币读不到">
            {panelError}
          </Notice>
        ) : !panel ? (
          <p className="text-copy-13 text-muted-foreground">正在读取本位币…</p>
        ) : panel.rows.length === 0 ? (
          <EmptyState
            title="本租户还没有登记法人"
            description="本位币归法人（17.1.2.2），先在「组织与权限」里登记法人并设置本位币，这里才会有「各币种对各本位币」的视图。"
          />
        ) : (
          <div className="space-y-4">
            {panel.rows.map((row) => (
              <div key={row.legalEntityId} className="rounded-md border border-control-border p-3">
                <p className="text-label-13 font-semibold">
                  {row.legalEntityName}
                  <span className="text-muted-foreground ml-1">({row.legalEntityCode})</span>
                  <Badge tone="brand" className="ml-2">
                    本位币 {row.baseCurrency}
                  </Badge>
                </p>
                {row.rates.length === 0 ? (
                  <p className="text-copy-13 text-muted-foreground mt-1">
                    与 {row.baseCurrency} 相关的汇率一条都还没登记。
                  </p>
                ) : (
                  <ul className="mt-2 space-y-1">
                    {row.rates.map((rate) => (
                      <li key={rate.id} className="text-copy-13">
                        <span className="font-medium">{rateText(rate)}</span>
                        <span className="text-muted-foreground ml-2">
                          {dayText(rate.effectiveDate)} · {rate.sourceName || rate.sourceCode}
                        </span>
                      </li>
                    ))}
                  </ul>
                )}
              </div>
            ))}
            <p className="text-copy-12 text-muted-foreground">{panel.note}</p>
          </div>
        )}
      </SectionCard>

      {/* 分区：与 5.2.2.2 共用 L3 路由 */}
      <SectionCard
        title="5.2 汇率 · 分区"
        description="两个兄弟模块共用 L3 路由 /pricing/rates，各占一个独立分区。未交付的分区显式标成未交付。"
      >
        <div className="space-y-3">
          {RATE_SECTIONS.map((section) => (
            <div key={section.id} className="rounded-md border border-control-border p-3">
              <p className="text-label-13 font-semibold">
                {section.id} {section.title}
                <Badge tone={section.delivered ? 'success' : 'warning'} className="ml-2">
                  {section.delivered ? '已交付' : '未交付'}
                </Badge>
              </p>
              <p className="text-copy-13 text-muted-foreground mt-1">{section.description}</p>
            </div>
          ))}
        </div>
      </SectionCard>
    </div>
  );
}

/** 今天（本地日历日）。 */
function today(): string {
  return new Date().toISOString().slice(0, 10);
}
