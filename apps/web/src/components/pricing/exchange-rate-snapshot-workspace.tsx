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
  EMPTY_SNAPSHOT_FILTERS,
  amountText,
  createRoundingRule,
  dayText,
  directionLabel,
  directionTone,
  documentTypeLabel,
  fetchRoundingRules,
  fetchSnapshotCapabilities,
  fetchSnapshotList,
  freezeSnapshot,
  initializeRoundingRules,
  replaySnapshots,
  replayTone,
  roundingModeLabel,
  snapshotConversionText,
  snapshotRateText,
  timeText,
  validateRoundingRuleInput,
  validateSnapshotInput,
} from './exchange-rate-snapshot-model';
import type {
  RoundingRule,
  SnapshotCapabilities,
  SnapshotFilters,
  SnapshotInput,
  SnapshotListPage,
  SnapshotReplayReport,
} from './exchange-rate-snapshot-model';

/**
 * 5.2.2.2 汇率快照工作区。
 *
 * <p>🔴 页面最上面那一块**不是提示语，是这一页的设计前提**：
 * 蓝图加粗句「**汇率快照是利润可追溯的前提**：今天汇率变了不能让上个月的利润报表数字跟着变」。
 *
 * <p>所以本页把三件事摆在最显眼的位置：
 *
 * <ol>
 *   <li><b>冻结规则</b>：汇率、方向、生效日、来源、舍入方式、目标币金额整行冻结，
 *       读快照不 JOIN 汇率表取值；
 *   <li><b>每一行都带完整方向与整条换算过程</b>：显示的是
 *       「100.0 USD × 7.1234 = 712.34 CNY（HALF_UP · 2 位小数）」这样的整句，
 *       表格里**没有**任何一列是裸的汇率数字；
 *   <li><b>复算结论显性</b>：拿冻结的值重算一遍，一致 / 不一致一眼分得开。
 * </ol>
 *
 * <p>🔴 **没有「编辑」「删除」入口**：快照是只追加的冻结事实，
 * 要更正就换单据行标识另冻一条 —— 原快照原样保留，历史不被抹掉。
 *
 * <p>🔴 **分区块结构**：5.2.2.2 汇率快照与 5.2.2.1 汇率查询共用 L3 路由 {@code /pricing/rates}。
 * 本模块只占「汇率快照」这一块，不重写汇率查询的页面骨架。
 */
export function ExchangeRateSnapshotWorkspace() {
  const [capabilities, setCapabilities] = useState<SnapshotCapabilities | null>(null);
  const [rules, setRules] = useState<RoundingRule[]>([]);
  const [page, setPage] = useState<SnapshotListPage | null>(null);
  const [filters, setFilters] = useState<SnapshotFilters>(EMPTY_SNAPSHOT_FILTERS);
  const [report, setReport] = useState<SnapshotReplayReport | null>(null);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [busy, setBusy] = useState(false);
  const [loaded, setLoaded] = useState(false);

  // 冻结表单
  const [form, setForm] = useState<SnapshotInput>({
    rateId: '',
    baseCurrency: 'USD',
    quoteCurrency: 'CNY',
    asOf: today(),
    sourceCurrency: 'USD',
    sourceAmount: '',
    documentType: 'PRICE_LIST',
    documentId: '',
    documentNo: '',
    lineKey: '',
    roundingRuleCode: '',
    note: '',
  });
  const [problems, setProblems] = useState<string[]>([]);

  // 舍入规则表单
  const [ruleForm, setRuleForm] = useState({
    code: '',
    name: '',
    targetCurrency: '',
    roundingMode: 'HALF_UP',
    roundingScale: 2,
  });
  const [ruleProblems, setRuleProblems] = useState<string[]>([]);

  // 复算表单
  const [replayForm, setReplayForm] = useState({ documentType: 'PRICE_LIST', documentId: '' });

  const load = useCallback(async () => {
    setBusy(true);
    setError('');
    try {
      const [next, caps, ruleList] = await Promise.all([
        fetchSnapshotList(filters),
        fetchSnapshotCapabilities(),
        fetchRoundingRules(),
      ]);
      setPage(next);
      setCapabilities(caps);
      setRules(ruleList);
      setLoaded(true);
    } catch (e) {
      setError(e instanceof Error ? e.message : '汇率快照台账读不到');
    } finally {
      setBusy(false);
    }
  }, [filters]);

  useEffect(() => {
    void load();
  }, [load]);

  const enabledRules = useMemo(() => rules.filter((r) => r.enabled), [rules]);

  async function submitSnapshot() {
    const found = validateSnapshotInput(form);
    setProblems(found);
    if (found.length) return;
    setBusy(true);
    setError('');
    setNotice('');
    try {
      const saved = await freezeSnapshot(form);
      setNotice(
        `已冻结汇率快照：${snapshotConversionText(saved)}。` +
          `冻结的汇率是 ${snapshotRateText(saved)}（生效日 ${dayText(saved.rateEffectiveDate)}，来源 ${saved.rateSourceCode}）。` +
          `这条快照不可改写 —— 今天汇率变了它也不会跟着变。`,
      );
      setForm((f) => ({ ...f, sourceAmount: '' }));
      await load();
    } catch (e) {
      setError(e instanceof Error ? e.message : '汇率快照冻结失败');
    } finally {
      setBusy(false);
    }
  }

  async function submitRule() {
    const found = validateRoundingRuleInput(ruleForm);
    setRuleProblems(found);
    if (found.length) return;
    setBusy(true);
    setError('');
    setNotice('');
    try {
      const saved = await createRoundingRule({
        code: ruleForm.code,
        name: ruleForm.name,
        targetCurrency: ruleForm.targetCurrency.trim() || null,
        roundingMode: ruleForm.roundingMode,
        roundingScale: ruleForm.roundingScale,
        enabled: true,
        priority: 100,
        note: '',
      });
      setNotice(
        `已登记舍入规则「${saved.name}」（${saved.code}）：${saved.roundingModeLabel} · ${saved.roundingScale} 位小数。` +
          `改规则不动历史快照 —— 快照里冻的是当时的模式与位数。`,
      );
      setRuleForm({ code: '', name: '', targetCurrency: '', roundingMode: 'HALF_UP', roundingScale: 2 });
      await load();
    } catch (e) {
      setError(e instanceof Error ? e.message : '舍入规则登记失败');
    } finally {
      setBusy(false);
    }
  }

  async function initRules() {
    setBusy(true);
    setError('');
    setNotice('');
    try {
      const list = await initializeRoundingRules();
      setNotice(
        `已初始化内置舍入规则（${list.length} 条）：通用两位小数 + 常见币种 + 日元/韩元零位。` +
          `解析顺序是先具体后通用：日元按零位小数入账。`,
      );
      await load();
    } catch (e) {
      setError(e instanceof Error ? e.message : '内置舍入规则初始化失败');
    } finally {
      setBusy(false);
    }
  }

  async function runReplay() {
    if (!replayForm.documentId.trim()) {
      setError('复算需要单据 id：填「单据类型 + 单据 id」复算整张单据，或在台账里复算单条快照。');
      return;
    }
    setBusy(true);
    setError('');
    setNotice('');
    try {
      const result = await replaySnapshots({
        snapshotId: null,
        documentType: replayForm.documentType,
        documentId: replayForm.documentId.trim(),
      });
      setReport(result);
    } catch (e) {
      setReport(null);
      setError(e instanceof Error ? e.message : '复算失败');
    } finally {
      setBusy(false);
    }
  }

  async function replayOne(snapshotId: string) {
    setBusy(true);
    setError('');
    setNotice('');
    try {
      const result = await replaySnapshots({ snapshotId, documentType: null, documentId: null });
      setReport(result);
    } catch (e) {
      setReport(null);
      setError(e instanceof Error ? e.message : '复算失败');
    } finally {
      setBusy(false);
    }
  }

  const totalPages = page ? Math.max(1, Math.ceil(page.total / Math.max(1, page.size))) : 1;

  return (
    <div className="space-y-5">
      <PageHeader
        title="汇率快照"
        description="在单据/定价生效时冻结所用汇率，保留换算过程（原价、汇率、舍入方式、目标币金额），保证历史利润可复算。"
        actions={<Badge tone="brand">5.2.2.2 汇率快照</Badge>}
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

      {/* 能力声明：冻结规则摆在最显眼的位置 */}
      {capabilities && (
        <SectionCard
          title="本页口径声明"
          description="冻结、方向与舍入三条规则 —— 页面上的任何一处显示都按这三条走。"
        >
          <div className="space-y-3">
            <Notice tone="warning" title="快照是冻结的历史事实">
              {capabilities.immutabilityRule}
            </Notice>
            <Notice tone="info" title="汇率方向必须显式">
              {capabilities.directionRule}
            </Notice>
            <Notice tone="info" title="舍入规则先具体后通用">
              {capabilities.roundingRule}
            </Notice>
            <div className="grid gap-3 md:grid-cols-2">
              <div className="rounded-md border border-control-border p-3">
                <p className="text-label-13 font-semibold">上游</p>
                <p className="text-copy-13 text-muted-foreground mt-1">{capabilities.upstreamNote}</p>
              </div>
              <div className="rounded-md border border-control-border p-3">
                <p className="text-label-13 font-semibold">下游</p>
                <p className="text-copy-13 text-muted-foreground mt-1">{capabilities.downstreamNote}</p>
              </div>
            </div>
            <p className="text-copy-12 text-muted-foreground">{capabilities.ownershipNote}</p>
          </div>
        </SectionCard>
      )}

      {/* 概览 */}
      <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
        <StatCard label="已冻结快照" value={page ? page.total : 0} />
        <StatCard label="可用舍入规则" value={enabledRules.length} />
        <StatCard
          label="复算结论"
          value={report ? (report.mismatched === 0 ? '全部一致' : `${report.mismatched} 笔对不上`) : '未复算'}
        />
        <StatCard label="舍入方式" value={capabilities ? capabilities.roundingModes.length : 0} />
      </div>

      {/* 冻结快照 */}
      <SectionCard
        title="冻结一条汇率快照"
        description="冻结的是 5.2.2.1 里**已登记**的汇率事实。想冻结一条还没登记的汇率，先去「汇率查询」登记它。"
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
          <div className="grid gap-3 md:grid-cols-4">
            <FormField id="snap-base" label="基准币种（1 这个币）">
              <Input
                id="snap-base"
                value={form.baseCurrency}
                placeholder="USD"
                onChange={(e) => setForm({ ...form, baseCurrency: e.target.value })}
              />
            </FormField>
            <FormField id="snap-quote" label="报价币种（= 几个这个币）">
              <Input
                id="snap-quote"
                value={form.quoteCurrency}
                placeholder="CNY"
                onChange={(e) => setForm({ ...form, quoteCurrency: e.target.value })}
              />
            </FormField>
            <FormField id="snap-asof" label="按生效日取（截至）">
              <Input
                id="snap-asof"
                type="date"
                value={form.asOf}
                onChange={(e) => setForm({ ...form, asOf: e.target.value })}
              />
            </FormField>
            <FormField id="snap-rate-id" label="或直接指定汇率 id">
              <Input
                id="snap-rate-id"
                value={form.rateId}
                placeholder="留空则按上面的币种对取最新一条"
                onChange={(e) => setForm({ ...form, rateId: e.target.value })}
              />
            </FormField>
            <FormField id="snap-source-currency" label="原价币种" required>
              <Input
                id="snap-source-currency"
                value={form.sourceCurrency}
                placeholder="USD"
                onChange={(e) => setForm({ ...form, sourceCurrency: e.target.value })}
              />
            </FormField>
            <FormField id="snap-source-amount" label="原价" required>
              <Input
                id="snap-source-amount"
                value={form.sourceAmount}
                placeholder="100.00"
                onChange={(e) => setForm({ ...form, sourceAmount: e.target.value })}
              />
            </FormField>
            <FormField id="snap-doc-type" label="单据类型" required>
              <Input
                id="snap-doc-type"
                value={form.documentType}
                list="snap-doc-type-options"
                onChange={(e) => setForm({ ...form, documentType: e.target.value })}
              />
            </FormField>
            <datalist id="snap-doc-type-options">
              {(capabilities?.documentTypes ?? []).map((d) => (
                <option key={d.value} value={d.value}>
                  {d.label}
                </option>
              ))}
            </datalist>
            <FormField id="snap-doc-id" label="单据 id" required>
              <Input
                id="snap-doc-id"
                value={form.documentId}
                placeholder="uuid"
                onChange={(e) => setForm({ ...form, documentId: e.target.value })}
              />
            </FormField>
            <FormField id="snap-doc-no" label="单据号">
              <Input
                id="snap-doc-no"
                value={form.documentNo}
                placeholder="PL-2026-001"
                onChange={(e) => setForm({ ...form, documentNo: e.target.value })}
              />
            </FormField>
            <FormField id="snap-line" label="单据行标识">
              <Input
                id="snap-line"
                value={form.lineKey}
                placeholder="L1"
                onChange={(e) => setForm({ ...form, lineKey: e.target.value })}
              />
            </FormField>
            <FormField id="snap-rule" label="舍入规则（留空按目标币种解析）">
              <Input
                id="snap-rule"
                value={form.roundingRuleCode}
                placeholder="DEFAULT / JPY"
                list="snap-rule-options"
                onChange={(e) => setForm({ ...form, roundingRuleCode: e.target.value })}
              />
            </FormField>
            <datalist id="snap-rule-options">
              {enabledRules.map((r) => (
                <option key={r.id} value={r.code}>
                  {r.name}
                </option>
              ))}
            </datalist>
          </div>
          <div className="flex flex-wrap items-center gap-2">
            <Button variant="brand" disabled={busy} onClick={() => void submitSnapshot()}>
              冻结这条汇率
            </Button>
            <Button
              variant="outline"
              size="sm"
              disabled={busy}
              onClick={() =>
                setForm((f) => ({ ...f, documentId: newUuid(), documentNo: `PL-${Date.now()}` }))
              }
            >
              用新的单据 id
            </Button>
            <p className="text-copy-12 text-muted-foreground">
              方向说明：原价币是这条汇率的基准币就乘、是报价币就除（反向汇率由正向推导，不另存一条）。
            </p>
          </div>
        </div>
      </SectionCard>

      {/* 快照台账 */}
      <SectionCard
        title="汇率快照台账"
        description="每一行都带完整方向与整条换算过程。表格里没有裸的汇率数字 —— 裸数字加一句「汇率：7.12」正是方向混淆的来源。"
      >
        <div className="space-y-4">
          <div className="grid gap-3 md:grid-cols-6">
            <FormField id="snap-filter-doctype" label="单据类型">
              <Input
                id="snap-filter-doctype"
                value={filters.documentType}
                placeholder="PRICE_LIST"
                onChange={(e) =>
                  setFilters({ ...filters, documentType: e.target.value, page: 0 })
                }
              />
            </FormField>
            <FormField id="snap-filter-docid" label="单据 id">
              <Input
                id="snap-filter-docid"
                value={filters.documentId}
                placeholder="uuid"
                onChange={(e) => setFilters({ ...filters, documentId: e.target.value, page: 0 })}
              />
            </FormField>
            <FormField id="snap-filter-source" label="原价币种">
              <Input
                id="snap-filter-source"
                value={filters.sourceCurrency}
                placeholder="USD"
                onChange={(e) =>
                  setFilters({ ...filters, sourceCurrency: e.target.value, page: 0 })
                }
              />
            </FormField>
            <FormField id="snap-filter-target" label="目标币种">
              <Input
                id="snap-filter-target"
                value={filters.targetCurrency}
                placeholder="CNY"
                onChange={(e) =>
                  setFilters({ ...filters, targetCurrency: e.target.value, page: 0 })
                }
              />
            </FormField>
            <FormField id="snap-filter-from" label="汇率生效日（起）">
              <Input
                id="snap-filter-from"
                type="date"
                value={filters.dateFrom}
                onChange={(e) => setFilters({ ...filters, dateFrom: e.target.value, page: 0 })}
              />
            </FormField>
            <FormField id="snap-filter-keyword" label="关键词">
              <Input
                id="snap-filter-keyword"
                value={filters.keyword}
                placeholder="单据号 / 行标识 / 来源"
                onChange={(e) => setFilters({ ...filters, keyword: e.target.value, page: 0 })}
              />
            </FormField>
          </div>

          {!loaded && busy && (
            <p className="text-copy-13 text-muted-foreground">正在读取汇率快照台账…</p>
          )}

          {loaded && page && page.items.length === 0 && (
            <EmptyState
              title="还没有冻结过任何汇率快照"
              description="用上面的「冻结一条汇率快照」在单据/定价生效时冻结当时的汇率。冻结之后这条快照就不可改写了 —— 今天汇率变了它也不会跟着变。"
            />
          )}

          {page && page.items.length > 0 && (
            <div className="overflow-x-auto">
              <table className="w-full text-left text-copy-13">
                <thead className="text-muted-foreground">
                  <tr>
                    <th className="py-2 pr-3 font-medium">换算过程</th>
                    <th className="py-2 pr-3 font-medium">冻结的汇率</th>
                    <th className="py-2 pr-3 font-medium">方向</th>
                    <th className="py-2 pr-3 font-medium">单据</th>
                    <th className="py-2 pr-3 font-medium">舍入</th>
                    <th className="py-2 font-medium">冻结时刻</th>
                  </tr>
                </thead>
                <tbody>
                  {page.items.map((row) => (
                    <tr key={row.id} className="border-t border-control-border">
                      <td className="py-2 pr-3 font-medium">{snapshotConversionText(row)}</td>
                      <td className="py-2 pr-3">
                        {snapshotRateText(row)}
                        <span className="text-muted-foreground ml-1">
                          {dayText(row.rateEffectiveDate)} · {row.rateSourceCode}
                        </span>
                      </td>
                      <td className="py-2 pr-3">
                        <Badge tone={directionTone(row.conversionDirection)}>
                          {directionLabel(row.conversionDirection)}
                        </Badge>
                      </td>
                      <td className="py-2 pr-3">
                        {row.documentNo || '（无单据号）'}
                        <span className="text-muted-foreground ml-1">
                          {documentTypeLabel(row.documentType)}
                          {row.lineKey ? ` · ${row.lineKey}` : ''}
                        </span>
                      </td>
                      <td className="py-2 pr-3">
                        {row.roundingModeLabel || roundingModeLabel(row.roundingMode)}
                        <span className="text-muted-foreground ml-1">
                          · {row.roundingScale} 位小数 · {row.roundingRuleCode}
                        </span>
                      </td>
                      <td className="py-2 text-muted-foreground">
                        {timeText(row.frozenAt)}
                        <Button
                          variant="ghost"
                          size="sm"
                          className="ml-2"
                          disabled={busy}
                          onClick={() => void replayOne(row.id)}
                        >
                          复算
                        </Button>
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

      {/* 复算 */}
      <SectionCard
        title="利润复算"
        description="拿快照里冻结的原价、汇率、方向、舍入方式重新算一遍，与冻结时写下的目标币金额逐笔比对。"
      >
        <div className="space-y-4">
          <div className="grid gap-3 md:grid-cols-3">
            <FormField id="replay-doctype" label="单据类型">
              <Input
                id="replay-doctype"
                value={replayForm.documentType}
                list="snap-doc-type-options"
                onChange={(e) => setReplayForm({ ...replayForm, documentType: e.target.value })}
              />
            </FormField>
            <FormField id="replay-docid" label="单据 id">
              <Input
                id="replay-docid"
                value={replayForm.documentId}
                placeholder="uuid"
                onChange={(e) => setReplayForm({ ...replayForm, documentId: e.target.value })}
              />
            </FormField>
            <div className="flex items-end">
              <Button variant="brand" disabled={busy} onClick={() => void runReplay()}>
                复算这张单据
              </Button>
            </div>
          </div>

          {!report && (
            <p className="text-copy-13 text-muted-foreground">
              还没有复算记录。复算是纯计算，不写库。
            </p>
          )}

          {report && (
            <div className="space-y-3">
              <Notice tone={report.mismatched === 0 ? 'success' : 'danger'} title="复算结论">
                {report.note}
              </Notice>
              {report.total === 0 ? (
                <EmptyState
                  title="这张单据上还没有冻结过汇率快照"
                  description="没有可复算的对象 —— 复算结果不是「通过」而是「无从复算」。"
                />
              ) : (
                <div className="overflow-x-auto">
                  <table className="w-full text-left text-copy-13">
                    <thead className="text-muted-foreground">
                      <tr>
                        <th className="py-2 pr-3 font-medium">单据行</th>
                        <th className="py-2 pr-3 font-medium">冻结的目标币金额</th>
                        <th className="py-2 pr-3 font-medium">复算结果</th>
                        <th className="py-2 font-medium">结论</th>
                      </tr>
                    </thead>
                    <tbody>
                      {report.rows.map((row) => (
                        <tr key={row.snapshotId} className="border-t border-control-border">
                          <td className="py-2 pr-3">
                            {row.documentNo || '（无单据号）'}
                            <span className="text-muted-foreground ml-1">
                              {row.lineKey || '整单'}
                            </span>
                          </td>
                          <td className="py-2 pr-3">
                            {amountText(row.targetAmount)} {row.targetCurrency}
                          </td>
                          <td className="py-2 pr-3">
                            {amountText(row.recomputedTargetAmount)} {row.targetCurrency}
                            <span className="text-muted-foreground ml-1">
                              {row.rateDisplay} · {row.roundingModeLabel || row.roundingMode} ·{' '}
                              {row.roundingScale} 位
                            </span>
                          </td>
                          <td className="py-2">
                            <Badge tone={replayTone(row.matched)}>
                              {row.matched ? '一致' : '对不上'}
                            </Badge>
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              )}
            </div>
          )}

          {capabilities && (
            <p className="text-copy-12 text-muted-foreground">{capabilities.replayContractNote}</p>
          )}
        </div>
      </SectionCard>

      {/* 舍入规则配置 */}
      <SectionCard
        title="舍入规则配置"
        description="模式 + 小数位 + 适用币种。解析顺序是先具体后通用：先找适用币种正好等于目标币种的规则，找不到再找通用兜底规则。"
        extra={
          <Button variant="outline" size="sm" disabled={busy} onClick={() => void initRules()}>
            初始化内置规则
          </Button>
        }
      >
        <div className="space-y-4">
          {rules.length === 0 ? (
            <EmptyState
              title="还没有登记任何舍入规则"
              description="点「初始化内置规则」写入通用两位小数 + 常见币种 + 日元/韩元零位，或在下面逐条新建。没有可用规则时冻结会被明确拒绝 —— 本服务不会偷偷按默认口径舍入。"
            />
          ) : (
            <div className="overflow-x-auto">
              <table className="w-full text-left text-copy-13">
                <thead className="text-muted-foreground">
                  <tr>
                    <th className="py-2 pr-3 font-medium">规则</th>
                    <th className="py-2 pr-3 font-medium">适用币种</th>
                    <th className="py-2 pr-3 font-medium">舍入方式</th>
                    <th className="py-2 pr-3 font-medium">小数位</th>
                    <th className="py-2 font-medium">状态</th>
                  </tr>
                </thead>
                <tbody>
                  {rules.map((rule) => (
                    <tr key={rule.id} className="border-t border-control-border">
                      <td className="py-2 pr-3">
                        {rule.name}
                        <span className="text-muted-foreground ml-1">({rule.code})</span>
                      </td>
                      <td className="py-2 pr-3">{rule.targetCurrency ?? '通用'}</td>
                      <td className="py-2 pr-3">
                        {rule.roundingModeLabel || roundingModeLabel(rule.roundingMode)}
                      </td>
                      <td className="py-2 pr-3">{rule.roundingScale}</td>
                      <td className="py-2">
                        <Badge tone={rule.enabled ? 'success' : 'neutral'}>
                          {rule.enabled ? '启用' : '停用'}
                        </Badge>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}

          {ruleProblems.length > 0 && (
            <Notice tone="danger" title="请先修正">
              <ul className="list-disc pl-4">
                {ruleProblems.map((p) => (
                  <li key={p}>{p}</li>
                ))}
              </ul>
            </Notice>
          )}
          <div className="grid gap-3 md:grid-cols-5">
            <FormField id="rule-code" label="规则代码" required>
              <Input
                id="rule-code"
                value={ruleForm.code}
                placeholder="JPY_SETTLEMENT"
                onChange={(e) => setRuleForm({ ...ruleForm, code: e.target.value })}
              />
            </FormField>
            <FormField id="rule-name" label="规则名称" required>
              <Input
                id="rule-name"
                value={ruleForm.name}
                placeholder="日元结算口径"
                onChange={(e) => setRuleForm({ ...ruleForm, name: e.target.value })}
              />
            </FormField>
            <FormField id="rule-currency" label="适用币种（留空=通用）">
              <Input
                id="rule-currency"
                value={ruleForm.targetCurrency}
                placeholder="JPY"
                onChange={(e) => setRuleForm({ ...ruleForm, targetCurrency: e.target.value })}
              />
            </FormField>
            <FormField id="rule-mode" label="舍入方式" required>
              <Input
                id="rule-mode"
                value={ruleForm.roundingMode}
                list="rule-mode-options"
                onChange={(e) => setRuleForm({ ...ruleForm, roundingMode: e.target.value })}
              />
            </FormField>
            <datalist id="rule-mode-options">
              {(capabilities?.roundingModes ?? []).map((m) => (
                <option key={m.value} value={m.value}>
                  {m.label}
                </option>
              ))}
            </datalist>
            <FormField id="rule-scale" label="小数位" required>
              <Input
                id="rule-scale"
                type="number"
                min={0}
                max={6}
                value={ruleForm.roundingScale}
                onChange={(e) =>
                  setRuleForm({ ...ruleForm, roundingScale: Number(e.target.value) })
                }
              />
            </FormField>
          </div>
          <Button variant="outline" disabled={busy} onClick={() => void submitRule()}>
            新建这条规则
          </Button>
        </div>
      </SectionCard>
    </div>
  );
}

/** 今天（本地日历日）。 */
function today(): string {
  return new Date().toISOString().slice(0, 10);
}

/** 新的单据 id（浏览器原生 uuid）。 */
function newUuid(): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID();
  }
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (c) => {
    const r = (Math.random() * 16) | 0;
    const v = c === 'x' ? r : (r & 0x3) | 0x8;
    return v.toString(16);
  });
}
