'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import {
  Badge,
  Button,
  EmptyState,
  FormField,
  Input,
  Notice,
  SectionCard,
  StatCard,
} from '@starcon/ui';

import {
  EMPTY_LANDED_COST_FILTERS,
  computeLandedCost,
  fetchLandedCostCapabilities,
  fetchLandedCostDetail,
  fetchLandedCostList,
  fetchLandedCostParameters,
  landedCompleteness,
  landedComponentReason,
  landedComponentState,
  landedMoneyText,
  landedMoneyWithCurrency,
  landedSourceLabel,
  landedSourceTone,
  landedTimeText,
  recomputeLandedCost,
  saveLandedCostParameter,
  validateLandedCostCompute,
  validateLandedCostParameter,
} from './landed-cost-model';
import type {
  LandedCostCapabilities,
  LandedCostDetail,
  LandedCostFilters,
  LandedCostLineRow,
  LandedCostListPage,
  LandedCostParameterInput,
  LandedCostStatementRow,
} from './landed-cost-model';

/**
 * 5.2.1.2 到岸成本工作区（/pricing/cost 页面里的「到岸成本」分区）。
 *
 * <p>🔴 页面最上面那一块**不是提示语，是这一页的设计前提**：
 * 蓝图加粗句「**到岸成本与仓库成本是两个口径**（海外仓 vs 自发货），
 * 定价时必须选对，否则自发货商品按海运成本定价会低估」。
 *
 * <p>所以本页把三件事摆在最显眼的位置：
 *
 * <ol>
 *   <li><b>算了哪六项</b>（采购价 + 国际头程 + 关税 + 清关 + 目的地操作费 + 尾程预备）；
 *   <li><b>明确不算哪两项</b>（仓储费 STORAGE_FEE、尾程派送费实际值 LAST_MILE_DELIVERY_FEE）——
 *       这两项归 10.6.1.1 成本分析与 8 仓储作业，混进来就等于把仓库成本偷偷塞进到岸成本；
 *   <li><b>每一项的来源与覆盖度</b>：事实 / 租户参数分色，缺项点名归谁管、缺了会怎样。
 * </ol>
 *
 * <p>🔴 **null 不渲染成 0**：没关税估算显示「缺口」，参数没维护显示「缺口」，
 * 合计不完整时明写「已知部分合计」。显示 0.00 会让到岸成本系统性低估（尤其是关税），
 * 而页面上看不出任何异常。
 *
 * <p>🔴 **跨币种绝不相加**：目的地对比里币种不同的行各算各的，不合并成一个平均值。
 */
export function LandedCostWorkspace() {
  const [capabilities, setCapabilities] = useState<LandedCostCapabilities | null>(null);
  const [page, setPage] = useState<LandedCostListPage | null>(null);
  const [filters, setFilters] = useState<LandedCostFilters>(EMPTY_LANDED_COST_FILTERS);
  const [detail, setDetail] = useState<LandedCostDetail | null>(null);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [busy, setBusy] = useState(false);
  const [loaded, setLoaded] = useState(false);

  // 计算入参：按上游头程发运单定位（id 或单号）。
  const [computeForm, setComputeForm] = useState({
    sourceShipmentId: '',
    sourceShipmentCode: '',
  });
  const [computeProblems, setComputeProblems] = useState<string[]>([]);

  // 参数维护（清关费 / 尾程预备）。
  const [parameterForm, setParameterForm] = useState<LandedCostParameterInput>({
    componentCode: 'CUSTOMS_CLEARANCE',
    destinationCountryCode: 'US',
    currency: 'USD',
    unitAmount: 0,
    note: '',
    version: 0,
  });
  const [parameterProblems, setParameterProblems] = useState<string[]>([]);

  const load = useCallback(async () => {
    setBusy(true);
    setError('');
    try {
      const [next, caps] = await Promise.all([
        fetchLandedCostList(filters),
        fetchLandedCostCapabilities(),
      ]);
      setPage(next);
      setCapabilities(caps);
      setLoaded(true);
    } catch (e) {
      setError(e instanceof Error ? e.message : '到岸成本台账读不到');
    } finally {
      setBusy(false);
    }
  }, [filters]);

  useEffect(() => {
    void load();
  }, [load]);

  const openDetail = useCallback(async (statementId: string) => {
    setBusy(true);
    setError('');
    try {
      const next = await fetchLandedCostDetail(statementId);
      setDetail(next);
      setSelectedId(statementId);
    } catch (e) {
      setError(e instanceof Error ? e.message : '到岸成本明细读不到');
    } finally {
      setBusy(false);
    }
  }, []);

  const runCompute = useCallback(async () => {
    const problems = validateLandedCostCompute({
      sourceShipmentId: computeForm.sourceShipmentId.trim() || null,
      sourceShipmentCode: computeForm.sourceShipmentCode.trim() || null,
    });
    setComputeProblems(problems);
    if (problems.length) return;
    setBusy(true);
    setError('');
    setNotice('');
    try {
      const computed = await computeLandedCost({
        sourceShipmentId: computeForm.sourceShipmentId.trim() || null,
        sourceShipmentCode: computeForm.sourceShipmentCode.trim() || null,
      });
      setNotice(
        `已${computed.created ? '新建' : '重算'}到岸成本单 ${computed.statementCode}` +
          `（第 ${computed.computeCount} 次计算）：` +
          `合计 ${landedMoneyWithCurrency(computed.totalAmount, computed.currency)}，` +
          `计入 ${computed.includedComponentCount} 项，缺 ${computed.missingComponentCount} 项。`,
      );
      await load();
      await openDetail(computed.statementId);
    } catch (e) {
      setError(e instanceof Error ? e.message : '到岸成本计算失败');
    } finally {
      setBusy(false);
    }
  }, [computeForm, load, openDetail]);

  const runRecompute = useCallback(async () => {
    if (!detail) return;
    setBusy(true);
    setError('');
    setNotice('');
    try {
      const computed = await recomputeLandedCost(detail.id);
      setNotice(
        `已重算 ${computed.statementCode}（第 ${computed.computeCount} 次计算）：` +
          `合计 ${landedMoneyWithCurrency(computed.totalAmount, computed.currency)}。`,
      );
      await load();
      await openDetail(computed.statementId);
    } catch (e) {
      setError(e instanceof Error ? e.message : '重算失败');
    } finally {
      setBusy(false);
    }
  }, [detail, load, openDetail]);

  const runSaveParameter = useCallback(async () => {
    const problems = validateLandedCostParameter(parameterForm);
    setParameterProblems(problems);
    if (problems.length) return;
    setBusy(true);
    setError('');
    setNotice('');
    try {
      const saved = await saveLandedCostParameter(parameterForm);
      setNotice(
        `已保存参数 ${saved.componentLabel}（${saved.destinationCountryCode} / ${saved.currency}）：` +
          `每件 ${saved.unitAmount}。改完参数需要重算受影响的到岸成本单才会生效。`,
      );
      setParameterForm({ ...parameterForm, version: saved.version });
    } catch (e) {
      setError(e instanceof Error ? e.message : '参数保存失败');
    } finally {
      setBusy(false);
    }
  }, [parameterForm]);

  const completeness = useMemo(
    () => landedCompleteness(detail?.missingComponents),
    [detail],
  );

  return (
    <div className="space-y-4">
      {/* ============================================================ 口径声明 */}
      <SectionCard
        title="到岸成本口径（必看）"
        description="蓝图加粗句：到岸成本与仓库成本是两个口径，定价时必须选对，否则自发货商品按海运成本定价会低估。"
      >
        {capabilities ? (
          <div className="space-y-4">
            <Notice tone="warning" title="这个数字里有什么、没有什么">
              <div className="space-y-1">
                <p className="font-semibold">{capabilities.basis.label}</p>
                <p>算了六项：{capabilities.basis.includedComponents.join('、')}</p>
                <p>
                  🔴 明确**不算**两项：
                  {capabilities.basis.excludedComponents.join('、')} —— 仓储费与尾程派送费实际值
                  归 10.6.1.1 成本分析与 8 仓储作业，不在本模块里。
                </p>
                <p>{capabilities.basis.note}</p>
              </div>
            </Notice>

            <div>
              <p className="text-label-13 mb-2 font-semibold">六个分量的来源</p>
              <div className="space-y-2">
                {capabilities.components.map((c) => (
                  <div key={c.code} className="border-border rounded-md border p-3">
                    <div className="flex flex-wrap items-center gap-2">
                      <span className="text-label-13 font-semibold">{c.label}</span>
                      <Badge tone={landedSourceTone(c.kind)}>{landedSourceLabel(c.kind)}</Badge>
                      <code className="text-copy-12 text-muted-foreground">{c.code}</code>
                      <span className="text-copy-12 text-muted-foreground">{c.source}</span>
                    </div>
                    <p className="text-copy-12 text-muted-foreground mt-1">{c.basis}</p>
                    {!c.available ? (
                      <p className="text-copy-12 text-muted-foreground mt-1">
                        🔴 取不到：归 {c.ownerModule} 管。{c.impact}
                      </p>
                    ) : null}
                  </div>
                ))}
              </div>
            </div>

            {capabilities.gaps.length ? (
              <div>
                <p className="text-label-13 mb-2 font-semibold">未交付 / 取不到的上游依赖</p>
                <div className="space-y-2">
                  {capabilities.gaps.map((g) => (
                    <div key={g.moduleId} className="border-border rounded-md border p-3">
                      <div className="flex flex-wrap items-center gap-2">
                        <span className="text-label-13 font-semibold">{g.moduleName}</span>
                        <code className="text-copy-12 text-muted-foreground">{g.moduleId}</code>
                        <Badge tone="warning">未交付</Badge>
                      </div>
                      <p className="text-copy-12 text-muted-foreground mt-1">{g.impact}</p>
                      <p className="text-copy-12 text-muted-foreground">{g.note}</p>
                    </div>
                  ))}
                </div>
              </div>
            ) : null}

            <div className="grid gap-2 md:grid-cols-2">
              <Notice tone="info" title="上游真值">
                {capabilities.upstreamNote}
              </Notice>
              <Notice tone="info" title="下游与读法">
                {capabilities.downstreamNote}
              </Notice>
              <Notice tone="info" title="落库口径">
                {capabilities.sourceNote}
              </Notice>
              <Notice tone="info" title="可做的动作">
                {capabilities.actionsNote}
              </Notice>
            </div>
          </div>
        ) : busy && !loaded ? (
          <p className="text-muted-foreground text-copy-13">正在读能力声明……</p>
        ) : (
          <p className="text-muted-foreground text-copy-13">能力声明读不到。</p>
        )}
      </SectionCard>

      {/* ============================================================ 计算 */}
      <SectionCard
        title="计算一张到岸成本单"
        description="到岸成本是按上游 10.4.1.1 头程发运单算的：一张发运单对应一张到岸成本单，重复提交是重算同一张单。"
      >
        <div className="grid gap-3 md:grid-cols-3">
          <FormField id="landed-shipment-id" label="头程发运单 id" hint="二选一：给 id 或单号">
            <Input
              id="landed-shipment-id"
              value={computeForm.sourceShipmentId}
              placeholder="UUID"
              onChange={(e) => setComputeForm({ ...computeForm, sourceShipmentId: e.target.value })}
            />
          </FormField>
          <FormField id="landed-shipment-code" label="头程发运单号" hint="例如 FM-1a2b3c4d">
            <Input
              id="landed-shipment-code"
              value={computeForm.sourceShipmentCode}
              placeholder="单号"
              onChange={(e) =>
                setComputeForm({ ...computeForm, sourceShipmentCode: e.target.value })
              }
            />
          </FormField>
          <div className="flex items-end">
            <Button disabled={busy} onClick={() => void runCompute()}>
              计算 / 重算
            </Button>
          </div>
        </div>
        {computeProblems.length ? (
          <ul className="text-copy-12 mt-2 space-y-1 text-destructive">
            {computeProblems.map((p) => (
              <li key={p}>{p}</li>
            ))}
          </ul>
        ) : null}
      </SectionCard>

      {/* ============================================================ 筛选 */}
      <SectionCard title="筛选">
        <div className="grid gap-3 md:grid-cols-4">
          <FormField id="landed-kw" label="关键词" hint="到岸成本单号 / 头程发运单号">
            <Input
              id="landed-kw"
              value={filters.keyword}
              placeholder="全部"
              onChange={(e) => setFilters({ ...filters, keyword: e.target.value, page: 0 })}
            />
          </FormField>
          <FormField id="landed-country" label="目的国" hint="两位国家码；跨币种绝不相加">
            <Input
              id="landed-country"
              value={filters.destinationCountryCode}
              placeholder="全部"
              onChange={(e) =>
                setFilters({ ...filters, destinationCountryCode: e.target.value, page: 0 })
              }
            />
          </FormField>
          <FormField id="landed-currency" label="计价币种" hint="ISO 4217 三位字母码">
            <Input
              id="landed-currency"
              value={filters.currency}
              placeholder="全部"
              onChange={(e) => setFilters({ ...filters, currency: e.target.value, page: 0 })}
            />
          </FormField>
          <FormField id="landed-status" label="状态" hint="COMPUTED / VOID">
            <select
              id="landed-status"
              className="border-input h-9 w-full rounded-md border px-2 text-sm"
              value={filters.status}
              onChange={(e) => setFilters({ ...filters, status: e.target.value, page: 0 })}
            >
              <option value="">全部</option>
              <option value="COMPUTED">已计算</option>
              <option value="VOID">已作废</option>
            </select>
          </FormField>
        </div>
      </SectionCard>

      {/* ============================================================ 台账 */}
      <SectionCard title={`到岸成本台账（${page?.total ?? 0}）`}>
        {error ? (
          <Notice tone="danger" title="读不到">
            {error}
          </Notice>
        ) : null}
        {notice ? (
          <Notice tone="success" title="已完成">
            {notice}
          </Notice>
        ) : null}
        {busy && !page ? (
          <p className="text-muted-foreground text-copy-13">正在读到岸成本台账……</p>
        ) : page && page.items.length > 0 ? (
          <div className="space-y-3">
            <div className="overflow-x-auto">
              <table className="w-full text-copy-13">
                <thead>
                  <tr className="border-b text-left">
                    <th className="p-2">到岸成本单</th>
                    <th className="p-2">来源发运单</th>
                    <th className="p-2">目的地</th>
                    <th className="p-2">贸易条款</th>
                    <th className="p-2">合计</th>
                    <th className="p-2">覆盖度</th>
                    <th className="p-2">计算时间</th>
                    <th className="p-2">操作</th>
                  </tr>
                </thead>
                <tbody>
                  {page.items.map((row) => (
                    <LandedCostRowView
                      key={row.id}
                      row={row}
                      active={row.id === selectedId}
                      onOpen={() => void openDetail(row.id)}
                    />
                  ))}
                </tbody>
              </table>
            </div>
            <div className="flex flex-wrap items-center gap-2">
              <Button
                size="sm"
                variant="ghost"
                disabled={filters.page <= 0}
                onClick={() => setFilters({ ...filters, page: filters.page - 1 })}
              >
                上一页
              </Button>
              <span className="text-muted-foreground text-xs">
                第 {filters.page + 1} 页 / 每页 {filters.size} 条 / 共 {page.total} 条
              </span>
              <Button
                size="sm"
                variant="ghost"
                disabled={(filters.page + 1) * filters.size >= page.total}
                onClick={() => setFilters({ ...filters, page: filters.page + 1 })}
              >
                下一页
              </Button>
            </div>
            <p className="text-muted-foreground text-copy-12">{page.basisNote}</p>
          </div>
        ) : loaded ? (
          <EmptyState
            title="这个筛选下没有到岸成本单"
            description="到岸成本是按上游 10.4.1.1 头程发运单算出来的单据级事实。台账为空通常意味着还没有算过，或者筛选条件太窄。"
            action={
              <Button size="sm" variant="ghost" onClick={() => setFilters(EMPTY_LANDED_COST_FILTERS)}>
                重置筛选
              </Button>
            }
          />
        ) : null}
      </SectionCard>

      {/* ============================================================ 明细 */}
      {detail ? (
        <SectionCard
          title={`到岸成本明细 · ${detail.statementCode}`}
          description={`${detail.sourceShipmentCode} → ${detail.destinationName || detail.destinationCountryCode}｜口径 ${detail.costBasis}`}
          extra={<Badge tone="brand">{detail.currency}</Badge>}
        >
          <div className="grid gap-3 md:grid-cols-4">
            <StatCard label="计价币种" value={detail.currency} polarity="neutral" />
            <StatCard
              label="批次合计"
              value={landedMoneyText(detail.totalAmount, '算不出来')}
              unit={detail.missingComponents.length ? '（已知部分）' : detail.currency}
              polarity="neutral"
            />
            <StatCard label="行数" value={detail.totalLineCount} unit="行" polarity="neutral" />
            <StatCard
              label="计算次数"
              value={detail.computeCount}
              unit="次"
              polarity="neutral"
            />
          </div>

          <div className="mt-3">
            <Notice tone={completeness.tone} title="合计完整度">
              {completeness.text.replace(/\*\*/g, '')}
            </Notice>
          </div>

          <div className="mt-4 space-y-4">
            {detail.lines.map((line) => (
              <LandedCostLineView key={line.id} line={line} currency={detail.currency} />
            ))}
          </div>

          <div className="mt-4 flex flex-wrap items-center gap-2">
            <Button size="sm" variant="ghost" disabled={busy} onClick={() => void runRecompute()}>
              按当前上游重算这张单
            </Button>
            <span className="text-muted-foreground text-copy-12">
              重算是幂等的：覆盖同一张单并把计算次数加一，「这个到岸成本是按什么时候的头程算出来的」必须可追溯。
            </span>
          </div>

          <p className="text-muted-foreground text-copy-12 mt-3">{detail.note}</p>
        </SectionCard>
      ) : null}

      {/* ============================================================ 参数 */}
      <SectionCard
        title="租户参数（清关费 / 尾程预备）"
        description="🔴 只有这两个是参数：全仓没有任何清关费事实，而尾程预备按定义就是租户为定价留的预留，不是尾程派送费实际值。其余四项是上游事实，不许人工填。"
      >
        <div className="grid gap-3 md:grid-cols-3">
          <FormField id="landed-param-component" label="分量">
            <select
              id="landed-param-component"
              className="border-input h-9 w-full rounded-md border px-2 text-sm"
              value={parameterForm.componentCode}
              onChange={(e) =>
                setParameterForm({ ...parameterForm, componentCode: e.target.value, version: 0 })
              }
            >
              <option value="CUSTOMS_CLEARANCE">清关费</option>
              <option value="LAST_MILE_RESERVE">尾程预备</option>
            </select>
          </FormField>
          <FormField id="landed-param-country" label="目的国" hint="两位国家码">
            <Input
              id="landed-param-country"
              value={parameterForm.destinationCountryCode}
              onChange={(e) =>
                setParameterForm({ ...parameterForm, destinationCountryCode: e.target.value })
              }
            />
          </FormField>
          <FormField id="landed-param-currency" label="币种" hint="ISO 4217 三位字母码">
            <Input
              id="landed-param-currency"
              value={parameterForm.currency}
              onChange={(e) => setParameterForm({ ...parameterForm, currency: e.target.value })}
            />
          </FormField>
          <FormField id="landed-param-amount" label="单件金额" hint="每基础单位的钱">
            <Input
              id="landed-param-amount"
              value={String(parameterForm.unitAmount)}
              onChange={(e) =>
                setParameterForm({ ...parameterForm, unitAmount: Number(e.target.value) })
              }
            />
          </FormField>
          <FormField id="landed-param-note" label="备注" hint="写清这个数是怎么来的">
            <Input
              id="landed-param-note"
              value={parameterForm.note}
              onChange={(e) => setParameterForm({ ...parameterForm, note: e.target.value })}
            />
          </FormField>
          <div className="flex items-end">
            <Button disabled={busy} onClick={() => void runSaveParameter()}>
              保存参数
            </Button>
          </div>
        </div>
        {parameterProblems.length ? (
          <ul className="text-copy-12 mt-2 space-y-1 text-destructive">
            {parameterProblems.map((p) => (
              <li key={p}>{p}</li>
            ))}
          </ul>
        ) : null}
        <LandedCostParameterTable />
      </SectionCard>
    </div>
  );
}

/** 台账里的一行。 */
function LandedCostRowView({
  row,
  active,
  onOpen,
}: {
  row: LandedCostStatementRow;
  active: boolean;
  onOpen: () => void;
}) {
  const completeness = landedCompleteness(row.missingComponents);
  return (
    <tr className="border-b">
      <td className="p-2">
        <div className="font-mono">{row.statementCode}</div>
        <div className="text-copy-12 text-muted-foreground">
          {row.totalLineCount} 行｜{row.currency}
        </div>
      </td>
      <td className="p-2 font-mono">
        {row.sourceShipmentCode || '—'}
        <div className="text-copy-12 text-muted-foreground">
          贸易条款 {row.incoterm || '—'}｜风险 {row.riskBearer || '—'}
        </div>
      </td>
      <td className="p-2">
        {row.destinationCountryCode}
        <div className="text-copy-12 text-muted-foreground">{row.destinationName}</div>
      </td>
      <td className="p-2">{row.incoterm || '—'}</td>
      <td className="p-2 font-mono">
        {landedMoneyWithCurrency(row.totalAmount, row.currency)}
      </td>
      <td className="p-2">
        <Badge tone={completeness.tone === 'success' ? 'success' : 'warning'}>
          计入 {row.includedComponentCount} / 缺 {row.missingComponentCount}
        </Badge>
        {row.missingComponents.length ? (
          <div className="text-copy-12 text-muted-foreground">
            缺 {row.missingComponents.join('、')}
          </div>
        ) : null}
      </td>
      <td className="p-2">
        {landedTimeText(row.computedAt)}
        <div className="text-copy-12 text-muted-foreground">第 {row.computeCount} 次计算</div>
      </td>
      <td className="p-2">
        <Button size="sm" variant={active ? 'default' : 'ghost'} onClick={onOpen}>
          {active ? '已展开' : '查看明细'}
        </Button>
      </td>
    </tr>
  );
}

/** 一个 SKU 的到岸成本行 + 它的六个分量。 */
function LandedCostLineView({ line, currency }: { line: LandedCostLineRow; currency: string }) {
  return (
    <div className="border-border rounded-md border p-3">
      <div className="flex flex-wrap items-center gap-2">
        <span className="text-label-13 font-semibold">{line.skuCode}</span>
        <span className="text-copy-12 text-muted-foreground">{line.skuName}</span>
        <Badge tone={line.missingComponents.length ? 'warning' : 'success'}>
          计入 {line.includedComponentCount} / 缺 {line.missingComponentCount}
        </Badge>
        <span className="text-copy-12 text-muted-foreground">
          数量 {line.baseQuantity} {line.baseUnit}
        </span>
        <span className="font-mono text-copy-13">
          单位到岸成本 {landedMoneyWithCurrency(line.unitLandedCost, currency)}
        </span>
        <span className="font-mono text-copy-13">
          行合计 {landedMoneyWithCurrency(line.totalAmount, currency)}
        </span>
      </div>

      <div className="mt-3 overflow-x-auto">
        <table className="w-full text-copy-13">
          <thead>
            <tr className="border-b text-left">
              <th className="p-2">分量</th>
              <th className="p-2">来源</th>
              <th className="p-2">来源单号</th>
              <th className="p-2">金额</th>
              <th className="p-2">单位金额</th>
              <th className="p-2">状态与原因</th>
            </tr>
          </thead>
          <tbody>
            {line.components.map((c) => {
              const state = landedComponentState(c);
              return (
                <tr key={c.code} className="border-b">
                  <td className="p-2">
                    {c.label}
                    <div className="text-copy-12 text-muted-foreground">{c.code}</div>
                  </td>
                  <td className="p-2">
                    <Badge tone={landedSourceTone(c.sourceKind)}>{landedSourceLabel(c.sourceKind)}</Badge>
                    <div className="text-copy-12 text-muted-foreground">{c.sourceModule}</div>
                  </td>
                  <td className="p-2 font-mono">{c.sourceDocumentCode || '—'}</td>
                  <td className="p-2 font-mono">{landedMoneyWithCurrency(c.amount, currency)}</td>
                  <td className="p-2 font-mono">{landedMoneyWithCurrency(c.unitAmount, currency)}</td>
                  <td className="p-2">
                    <Badge tone={state.tone}>{state.text}</Badge>
                    <div className="text-copy-12 text-muted-foreground">
                      {landedComponentReason(c)}
                    </div>
                    {c.code === 'DUTY' && c.taxDutyAmount !== null ? (
                      <div className="text-copy-12 text-muted-foreground">
                        其中关税 {landedMoneyText(c.taxDutyAmount)}，增值税/GST{' '}
                        {landedMoneyText(c.taxOtherAmount)}
                        {c.taxBearer ? `｜承担方 ${c.taxBearer}` : ''}
                      </div>
                    ) : null}
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
      </div>
    </div>
  );
}

/** 当前的参数清单。 */
function LandedCostParameterTable() {
  const [rows, setRows] = useState<
    { id: string; componentLabel: string; destinationCountryCode: string; currency: string; unitAmount: number; updatedAt: string }[]
  >([]);
  const [error, setError] = useState('');

  const load = useCallback(async () => {
    try {
      const page = await fetchLandedCostParameters();
      setRows(page.items);
    } catch (e) {
      setError(e instanceof Error ? e.message : '参数读不到');
    }
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  if (error) {
    return (
      <Notice tone="danger" title="参数读不到">
        {error}
      </Notice>
    );
  }
  if (!rows.length) {
    return (
      <p className="text-muted-foreground text-copy-12 mt-3">
        还没有维护任何参数 —— 未维护的参数在到岸成本里显示为**缺口**，不是 0。
      </p>
    );
  }
  return (
    <div className="mt-3 overflow-x-auto">
      <table className="w-full text-copy-13">
        <thead>
          <tr className="border-b text-left">
            <th className="p-2">分量</th>
            <th className="p-2">目的国</th>
            <th className="p-2">币种</th>
            <th className="p-2">单件金额</th>
            <th className="p-2">更新时间</th>
          </tr>
        </thead>
        <tbody>
          {rows.map((r) => (
            <tr key={r.id} className="border-b">
              <td className="p-2">{r.componentLabel}</td>
              <td className="p-2">{r.destinationCountryCode}</td>
              <td className="p-2">{r.currency}</td>
              <td className="p-2 font-mono">{r.unitAmount}</td>
              <td className="p-2">{landedTimeText(r.updatedAt)}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
