'use client';

import { useCallback, useEffect, useState } from 'react';
import { RefreshCw } from 'lucide-react';
import {
  Badge,
  Button,
  EmptyState,
  FormField,
  Input,
  PageHeader,
  SectionCard,
  StatCard,
  Tabs,
  TabsContent,
  TabsList,
  TabsTrigger,
} from '@starcon/ui';

import {
  adjustSupplierRating,
  adjustmentLabel,
  changeTypeLabel,
  computeSupplierRatings,
  dimensionLabel,
  EMPTY_SUPPLIER_RATING_FILTERS,
  fetchSupplierRatingCapabilities,
  fetchSupplierRatingChanges,
  fetchSupplierRatingOptions,
  fetchSupplierRatingOverview,
  fetchSupplierRatingPolicy,
  fetchSupplierRatingPriorities,
  fetchSupplierRatings,
  finalScoreLabel,
  gradeLabel,
  gradeTone,
  hoursLabel,
  periodText,
  quantityLabel,
  rateLabel,
  saveSupplierRatingPolicy,
  scoreLabel,
  timeText,
  toPolicyForm,
  unavailableText,
  validateAdjustment,
  validatePolicyForm,
  weightLabel,
  type SupplierRatingAdjustmentInput,
  SupplierRatingCapabilities,
  SupplierRatingChangePage,
  SupplierRatingFilters,
  SupplierRatingOptions,
  SupplierRatingOverview,
  SupplierRatingPage,
  SupplierRatingPolicy,
  SupplierRatingPolicyForm,
  SupplierRatingPriorityList,
  SupplierRatingRow,
} from './supplier-rating-model';

/**
 * 6.1.1.3 供应商评级。
 *
 * <p>🔴 页面顶部那句话不是提示语，是这一页的设计前提：**评级必须由客观数据算出，
 * 人工加权分需单独标记，否则会变成纯主观打分。**
 *
 * <p>所以这一页的三条硬规矩：
 *
 * <ol>
 *   <li><b>客观分与最终分并排显示</b>，人工调整过的行带醒目的「人工调整」标记 ——
 *       折叠成一个「总分」之后，三个月后没人能回答「这个 A 是数据算出来的还是谁拍的」。</li>
 *   <li><b>维度的 null 渲染成「不可用 / 暂无依据」而不是 0</b> ——
 *       「本期没有到货记录」与「到货全部逾期」是相反的两件事。</li>
 *   <li><b>权重和必须精确等于 1</b>，且每个维度的得分旁边都写着它的样本量
 *       （几家到货 / 检了多少件 / 几个响应样本 / 几个可比 SKU / 几笔索赔）——
 *       一个不说清「是按哪一段、几个样本算的」的数字在供应商评审里没有意义。</li>
 * </ol>
 */
export function SupplierRatingWorkspace() {
  const [capabilities, setCapabilities] = useState<SupplierRatingCapabilities | null>(null);
  const [options, setOptions] = useState<SupplierRatingOptions | null>(null);
  const [overview, setOverview] = useState<SupplierRatingOverview | null>(null);
  const [page, setPage] = useState<SupplierRatingPage | null>(null);
  const [changes, setChanges] = useState<SupplierRatingChangePage | null>(null);
  const [priorities, setPriorities] = useState<SupplierRatingPriorityList | null>(null);
  const [policy, setPolicy] = useState<SupplierRatingPolicy | null>(null);
  const [policyForm, setPolicyForm] = useState<SupplierRatingPolicyForm | null>(null);
  const [filters, setFilters] = useState<SupplierRatingFilters>(EMPTY_SUPPLIER_RATING_FILTERS);
  const [adjusting, setAdjusting] = useState<SupplierRatingRow | null>(null);
  const [adjustment, setAdjustment] = useState<SupplierRatingAdjustmentInput>({
    adjustment: '',
    reason: '',
  });
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [busy, setBusy] = useState(false);
  const [loading, setLoading] = useState(true);

  const load = useCallback(async () => {
    setBusy(true);
    setError('');
    try {
      const [next, opts, caps, ov, pol, ch, pri] = await Promise.all([
        fetchSupplierRatings(filters),
        fetchSupplierRatingOptions(),
        fetchSupplierRatingCapabilities(),
        fetchSupplierRatingOverview(filters.periodType, filters.periodStart),
        fetchSupplierRatingPolicy(filters.periodType),
        fetchSupplierRatingChanges(0, 20),
        fetchSupplierRatingPriorities(filters.periodType, filters.periodStart),
      ]);
      setPage(next);
      setOptions(opts);
      setCapabilities(caps);
      setOverview(ov);
      setPolicy(pol);
      setPolicyForm(toPolicyForm(pol));
      setChanges(ch);
      setPriorities(pri);
    } catch (e) {
      setError(e instanceof Error ? e.message : '供应商评级读不到');
    } finally {
      setBusy(false);
      setLoading(false);
    }
  }, [filters]);

  useEffect(() => {
    void load();
  }, [load]);

  async function runCompute() {
    setBusy(true);
    setError('');
    setNotice('');
    try {
      const result = await computeSupplierRatings(filters.periodType, filters.periodStart);
      setNotice(
        `已算完 ${result.periodStart} ~ ${result.periodEnd}：覆盖 ${result.computedCount} 家供应商` +
          `（新建 ${result.createdCount} 家 / 更新 ${result.updatedCount} 家），` +
          `留了 ${result.changeRecordCount} 条变动历史。` +
          (result.unavailableDimensions.length > 0
            ? `本次没有依据的维度：${result.unavailableDimensions.map(dimensionLabel).join('、')}（已排除在合成之外，不是 0 分）。`
            : ''),
      );
      await load();
    } catch (e) {
      setError(e instanceof Error ? e.message : '评级计算失败');
    } finally {
      setBusy(false);
    }
  }

  async function submitPolicy(form: SupplierRatingPolicyForm) {
    const problems = validatePolicyForm(form);
    if (problems.length > 0) {
      setError(problems.join('；'));
      return;
    }
    setBusy(true);
    setError('');
    setNotice('');
    try {
      const saved = await saveSupplierRatingPolicy(form);
      setPolicy(saved);
      setPolicyForm(toPolicyForm(saved));
      setNotice('权重配置已保存。下次计算评级时按新权重合成。');
    } catch (e) {
      setError(e instanceof Error ? e.message : '权重配置保存失败');
    } finally {
      setBusy(false);
    }
  }

  async function submitAdjustment(row: SupplierRatingRow, input: SupplierRatingAdjustmentInput) {
    const problems = validateAdjustment(input);
    if (problems.length > 0) {
      setError(problems.join('；'));
      return;
    }
    setBusy(true);
    setError('');
    setNotice('');
    try {
      await adjustSupplierRating(row.id, input);
      setAdjusting(null);
      setAdjustment({ adjustment: '', reason: '' });
      setNotice(
        `已对 ${row.supplierName} 记录人工加权分 ${adjustmentLabel(Number(input.adjustment))}。` +
          '客观分不变，最终分随之变化；调整留痕与变动历史各记一行。',
      );
      await load();
    } catch (e) {
      setError(e instanceof Error ? e.message : '人工加权分失败');
    } finally {
      setBusy(false);
    }
  }

  const totalPages = page ? Math.max(Math.ceil(page.total / Math.max(page.size, 1)), 1) : 1;

  return (
    <>
      <PageHeader
        title="供应商评级"
        description="按周期（默认月度）自动统计供应商表现：交期达成率、质量合格率、响应时长、价格竞争力、售后配合度，综合打分并支持人工调整权重。评级由客观数据算出，人工加权分单独标记。"
      />

      {error ? <p className="text-destructive text-sm">{error}</p> : null}
      {notice ? <p className="text-muted-foreground text-sm">{notice}</p> : null}
      {loading ? <p className="text-muted-foreground text-sm">正在读评级…</p> : null}

      <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
        <StatCard
          label="已评级供应商"
          value={String(overview?.ratedSupplierCount ?? 0)}
          comparisonLabel={`${periodText(overview?.periodStart, overview?.periodEnd)} · ${
            overview?.partialDimensionCount ?? 0
          } 家覆盖权重不足 100%`}
        />
        <StatCard
          label="平均最终分"
          value={finalScoreLabel(overview?.averageScore ?? null)}
          comparisonLabel={`平均客观分 ${finalScoreLabel(overview?.averageObjectiveScore ?? null)}`}
        />
        <StatCard
          label="等级分布"
          value={`A ${overview?.gradeACount ?? 0} · B ${overview?.gradeBCount ?? 0}`}
          comparisonLabel={`C ${overview?.gradeCCount ?? 0} · D ${overview?.gradeDCount ?? 0}`}
        />
        <StatCard
          label="人工调整过"
          value={String(overview?.manuallyAdjustedCount ?? 0)}
          polarity={overview?.manuallyAdjustedCount ? 'negative' : 'neutral'}
          comparisonLabel="带标记的分数不是纯数据算出来的"
        />
      </div>

      <Tabs defaultValue="ratings" className="mt-6">
        <TabsList>
          <TabsTrigger value="ratings">评级结果</TabsTrigger>
          <TabsTrigger value="policy">权重配置</TabsTrigger>
          <TabsTrigger value="changes">变动历史</TabsTrigger>
          <TabsTrigger value="priorities">默认供应商优先级</TabsTrigger>
        </TabsList>

        {/* ============================================================ 评级结果 */}
        <TabsContent value="ratings">
          <SectionCard
            title="筛选与计算"
            description="评级是按周期算的：周期区间是闭区间（含首尾两天）。计算是显式的写动作，不会静默覆盖历史。"
          >
            <div className="grid gap-3 md:grid-cols-4">
              <FormField id="period-type" label="周期" hint="默认月度">
                <select
                  id="period-type"
                  className="border-input h-9 w-full rounded-md border px-2 text-sm"
                  value={filters.periodType}
                  onChange={(e) =>
                    setFilters({
                      ...filters,
                      periodType: e.target.value as SupplierRatingFilters['periodType'],
                      page: 0,
                    })
                  }
                >
                  {(options?.periodTypes ?? []).map((o) => (
                    <option key={o.value} value={o.value}>
                      {o.label}
                    </option>
                  ))}
                </select>
              </FormField>
              <FormField id="period-start" label="周期内任一天" hint="用来定位是哪一个周期">
                <Input
                  id="period-start"
                  type="date"
                  value={filters.periodStart}
                  onChange={(e) =>
                    setFilters({ ...filters, periodStart: e.target.value, page: 0 })
                  }
                />
              </FormField>
              <FormField id="grade" label="等级">
                <select
                  id="grade"
                  className="border-input h-9 w-full rounded-md border px-2 text-sm"
                  value={filters.grade}
                  onChange={(e) => setFilters({ ...filters, grade: e.target.value, page: 0 })}
                >
                  <option value="">全部</option>
                  {(options?.grades ?? []).map((o) => (
                    <option key={o.value} value={o.value}>
                      {o.label}
                    </option>
                  ))}
                </select>
              </FormField>
              <FormField id="kw" label="关键词" hint="供应商编码 / 名称">
                <Input
                  id="kw"
                  value={filters.keyword}
                  placeholder="全部"
                  onChange={(e) => setFilters({ ...filters, keyword: e.target.value, page: 0 })}
                />
              </FormField>
            </div>
            <div className="mt-3 flex items-center gap-2">
              <Button size="sm" onClick={() => void runCompute()} disabled={busy}>
                <RefreshCw className="h-4 w-4" />
                计算本期评级
              </Button>
              <span className="text-muted-foreground text-xs">
                计算会覆盖同周期的评级行（人工调整保留），并在分数真的变了时留一条变动历史。
              </span>
            </div>
          </SectionCard>

          {page && page.items.length === 0 ? (
            <EmptyState
              title="本期还没有评级结果"
              description="评级不会自动生成：先点「计算本期评级」，系统会按本期的到货、质检、跟单、报价与索赔事实算一遍。"
            />
          ) : null}

          <div className="mt-4 grid gap-3">
            {(page?.items ?? []).map((row) => (
              <RatingCard
                key={row.id}
                row={row}
                busy={busy}
                onAdjust={() => {
                  setAdjusting(row);
                  setAdjustment({ adjustment: '', reason: '' });
                }}
              />
            ))}
          </div>

          {page && page.items.length > 0 ? (
            <div className="mt-3 flex items-center gap-2">
              <Button
                size="sm"
                variant="ghost"
                disabled={page.page <= 0 || busy}
                onClick={() => setFilters({ ...filters, page: filters.page - 1 })}
              >
                上一页
              </Button>
              <span className="text-muted-foreground text-xs">
                第 {page.page + 1} / {totalPages} 页 · 共 {page.total} 家
              </span>
              <Button
                size="sm"
                variant="ghost"
                disabled={page.page + 1 >= totalPages || busy}
                onClick={() => setFilters({ ...filters, page: filters.page + 1 })}
              >
                下一页
              </Button>
            </div>
          ) : null}

          {adjusting ? (
            <SectionCard
              title={`人工加权分 · ${adjusting.supplierName}`}
              description="客观分不会变，只有最终分变。理由必填 —— 没有理由的人工调整等于没留痕，三个月后无法解释这个名次。"
            >
              <div className="grid gap-3 md:grid-cols-3">
                <FormField id="adjustment" label="调整量" hint="−50 ~ +50，正数加分">
                  <Input
                    id="adjustment"
                    value={adjustment.adjustment}
                    placeholder="例如 -5"
                    onChange={(e) =>
                      setAdjustment({ ...adjustment, adjustment: e.target.value })
                    }
                  />
                </FormField>
                <FormField id="reason" label="理由" hint="必填">
                  <Input
                    id="reason"
                    value={adjustment.reason}
                    placeholder="例如：战略供应商，本期给予扶持"
                    onChange={(e) => setAdjustment({ ...adjustment, reason: e.target.value })}
                  />
                </FormField>
                <div className="flex items-end gap-2">
                  <Button
                    size="sm"
                    disabled={busy}
                    onClick={() => void submitAdjustment(adjusting, adjustment)}
                  >
                    记录人工加权分
                  </Button>
                  <Button size="sm" variant="ghost" onClick={() => setAdjusting(null)}>
                    取消
                  </Button>
                </div>
              </div>
              <p className="text-muted-foreground mt-2 text-xs">
                当前客观分 {finalScoreLabel(adjusting.objectiveScore)}，最终分{' '}
                {finalScoreLabel(adjusting.finalScore)}。
              </p>
            </SectionCard>
          ) : null}
        </TabsContent>

        {/* ============================================================ 权重配置 */}
        <TabsContent value="policy">
          <SectionCard
            title="权重配置"
            description="五个维度的权重之和必须精确等于 1。「某个维度不参与」的正确表达是该维度权重为 0，不是权重和不为 1。"
          >
            {policyForm ? (
              <>
                <p className="text-muted-foreground text-xs">
                  {policy?.configured
                    ? '当前使用租户自己配置的权重。'
                    : '租户还没配置权重，当前显示的是系统默认口径（configured=false）。'}
                </p>
                <div className="mt-3 grid gap-3 md:grid-cols-3">
                  <FormField id="w-delivery" label="交期达成率权重">
                    <Input
                      id="w-delivery"
                      value={policyForm.deliveryWeight}
                      onChange={(e) =>
                        setPolicyForm({ ...policyForm, deliveryWeight: e.target.value })
                      }
                    />
                  </FormField>
                  <FormField id="w-quality" label="质量合格率权重">
                    <Input
                      id="w-quality"
                      value={policyForm.qualityWeight}
                      onChange={(e) =>
                        setPolicyForm({ ...policyForm, qualityWeight: e.target.value })
                      }
                    />
                  </FormField>
                  <FormField id="w-response" label="响应时长权重">
                    <Input
                      id="w-response"
                      value={policyForm.responseWeight}
                      onChange={(e) =>
                        setPolicyForm({ ...policyForm, responseWeight: e.target.value })
                      }
                    />
                  </FormField>
                  <FormField id="w-price" label="价格竞争力权重">
                    <Input
                      id="w-price"
                      value={policyForm.priceWeight}
                      onChange={(e) =>
                        setPolicyForm({ ...policyForm, priceWeight: e.target.value })
                      }
                    />
                  </FormField>
                  <FormField id="w-service" label="售后配合度权重">
                    <Input
                      id="w-service"
                      value={policyForm.serviceWeight}
                      onChange={(e) =>
                        setPolicyForm({ ...policyForm, serviceWeight: e.target.value })
                      }
                    />
                  </FormField>
                  <FormField id="w-hours" label="响应目标小时数" hint="超过两倍目标得 0 分">
                    <Input
                      id="w-hours"
                      value={policyForm.responseTargetHours}
                      onChange={(e) =>
                        setPolicyForm({ ...policyForm, responseTargetHours: e.target.value })
                      }
                    />
                  </FormField>
                </div>
                <div className="mt-3 flex items-center gap-2">
                  <Button
                    size="sm"
                    disabled={busy}
                    onClick={() => void submitPolicy(policyForm)}
                  >
                    保存权重配置
                  </Button>
                  <Button
                    size="sm"
                    variant="ghost"
                    onClick={() => policy && setPolicyForm(toPolicyForm(policy))}
                  >
                    还原
                  </Button>
                </div>
              </>
            ) : (
              <p className="text-muted-foreground text-sm">权重配置读不到。</p>
            )}
          </SectionCard>

          <SectionCard title="能力声明" description="哪些维度真有依据、哪些下游接上了没有。">
            <ul className="grid gap-2 text-sm">
              {(capabilities?.capabilities ?? []).map((cap) => (
                <li key={cap.name} className="border-border rounded-md border p-2">
                  <div className="flex flex-wrap items-center gap-2">
                    <span className="font-medium">{cap.label}</span>
                    <Badge tone={cap.available ? 'success' : 'warning'}>
                      {cap.available ? '已具备' : `归 ${cap.ownerModule ?? '未指派'}`}
                    </Badge>
                  </div>
                  {cap.impact ? (
                    <p className="text-muted-foreground mt-1 text-xs">{cap.impact}</p>
                  ) : null}
                </li>
              ))}
            </ul>
            <ul className="text-muted-foreground mt-3 grid gap-1 text-xs">
              {(capabilities?.notes ?? []).map((note) => (
                <li key={note}>· {note}</li>
              ))}
            </ul>
          </SectionCard>
        </TabsContent>

        {/* ============================================================ 变动历史 */}
        <TabsContent value="changes">
          <SectionCard
            title="评级变动历史"
            description="只在「真的变了」时留行：分数与等级都没动时不留噪声。"
          >
            {changes && changes.items.length === 0 ? (
              <EmptyState
                title="还没有评级变动"
                description="首次评级不算「变动」；跨周期滚动、同周期重算与人工调整都会在这里留痕。"
              />
            ) : null}
            <ul className="grid gap-2">
              {(changes?.items ?? []).map((row) => (
                <li key={row.id} className="border-border rounded-md border p-3">
                  <div className="flex flex-wrap items-center gap-2">
                    <span className="font-medium">{row.supplierName || row.supplierCode}</span>
                    <Badge tone={gradeTone(row.currentGrade)}>{gradeLabel(row.currentGrade)}</Badge>
                    {row.previousGrade ? (
                      <Badge tone="neutral">原 {gradeLabel(row.previousGrade)}</Badge>
                    ) : null}
                    <Badge tone="info">{changeTypeLabel(row.changeType)}</Badge>
                    {row.gradeChanged ? <Badge tone="warning">等级变了</Badge> : null}
                  </div>
                  <p className="text-muted-foreground mt-1 text-xs">
                    {periodText(row.periodStart, row.periodEnd)} · 分数{' '}
                    {finalScoreLabel(row.previousScore)} → {finalScoreLabel(row.currentScore)}
                    {row.scoreDelta !== null
                      ? `（${row.scoreDelta > 0 ? '+' : ''}${finalScoreLabel(row.scoreDelta)}）`
                      : ''}{' '}
                    · {timeText(row.occurredAt)}
                  </p>
                  {row.reason ? (
                    <p className="text-muted-foreground mt-1 text-xs">原因：{row.reason}</p>
                  ) : null}
                </li>
              ))}
            </ul>
          </SectionCard>
        </TabsContent>

        {/* ============================================================ 优先级 */}
        <TabsContent value="priorities">
          <SectionCard
            title="默认供应商优先级"
            description="供 6.2.1.2 补货建议消费。只给排序与依据，不替下游选供应商 —— 补货建议还要看 MOQ / 箱规 / 起订额 / 交期。"
          >
            {priorities && priorities.items.length === 0 ? (
              <EmptyState
                title="本期还没有评级"
                description="先在「评级结果」里计算本期评级，优先级清单随之出现。"
              />
            ) : null}
            <ul className="grid gap-2">
              {(priorities?.items ?? []).map((row) => (
                <li key={row.supplierRefId} className="border-border rounded-md border p-3">
                  <div className="flex flex-wrap items-center gap-2">
                    <span className="font-medium">#{row.rank}</span>
                    <span>{row.supplierName || row.supplierCode}</span>
                    {row.supplierCode ? (
                      <span className="text-muted-foreground text-xs">{row.supplierCode}</span>
                    ) : null}
                    <Badge tone={gradeTone(row.grade)}>{gradeLabel(row.grade)}</Badge>
                    <Badge tone="info">最终分 {finalScoreLabel(row.finalScore)}</Badge>
                    <Badge tone="neutral">客观分 {finalScoreLabel(row.objectiveScore)}</Badge>
                    {row.manuallyAdjusted ? <Badge tone="warning">人工调整</Badge> : null}
                  </div>
                  {row.unavailableDimensions ? (
                    <p className="text-muted-foreground mt-1 text-xs">
                      没有依据的维度：{unavailableText(row.unavailableDimensions)}（已排除在合成之外）
                    </p>
                  ) : null}
                </li>
              ))}
            </ul>
          </SectionCard>
        </TabsContent>
      </Tabs>
    </>
  );
}

/** 一行评级。 */
function RatingCard({
  row,
  busy,
  onAdjust,
}: {
  row: SupplierRatingRow;
  busy: boolean;
  onAdjust: () => void;
}) {
  return (
    <div className="border-border rounded-md border p-3">
      <div className="flex flex-wrap items-start justify-between gap-2">
        <div>
          <div className="flex flex-wrap items-center gap-2">
            <span className="text-muted-foreground text-xs">
              {row.rankInPeriod !== null ? `第 ${row.rankInPeriod} 名` : '未排名'}
            </span>
            <span className="font-medium">{row.supplierName || '未登记名称'}</span>
            {row.supplierCode ? (
              <span className="text-muted-foreground text-xs">{row.supplierCode}</span>
            ) : null}
            <Badge tone={gradeTone(row.grade)}>{gradeLabel(row.grade)}</Badge>
            {row.manuallyAdjusted ? <Badge tone="warning">人工调整</Badge> : null}
            {row.availableWeight < 1 ? (
              <Badge tone="neutral">覆盖权重 {weightLabel(row.availableWeight)}</Badge>
            ) : null}
          </div>
          <p className="text-muted-foreground mt-1 text-xs">
            {periodText(row.periodStart, row.periodEnd)} · 客观分{' '}
            <b className="text-foreground">{finalScoreLabel(row.objectiveScore)}</b> · 最终分{' '}
            <b className="text-foreground">{finalScoreLabel(row.finalScore)}</b> · 人工调整{' '}
            {adjustmentLabel(row.manualAdjustment)}
            {row.manuallyAdjusted && row.manualAdjustmentReason
              ? `（${row.manualAdjustmentReason}）`
              : ''}
          </p>
        </div>
        <Button size="sm" variant="outline" onClick={onAdjust} disabled={busy}>
          人工加权分
        </Button>
      </div>

      <div className="text-muted-foreground mt-3 grid gap-2 text-xs sm:grid-cols-5">
        <span>
          交期达成率 <b className="text-foreground">{rateLabel(row.deliveryOnTimeRate)}</b>
          <br />
          <span className="text-muted-foreground">
            {row.deliveryArrivedCount} 次到货 · 得分 {scoreLabel(row.deliveryScore)}
          </span>
        </span>
        <span>
          质量合格率 <b className="text-foreground">{rateLabel(row.qualityPassRate)}</b>
          <br />
          <span className="text-muted-foreground">
            检 {quantityLabel(row.qualityInspectedQuantity)} 件 · 得分 {scoreLabel(row.qualityScore)}
          </span>
        </span>
        <span>
          响应时长 <b className="text-foreground">{hoursLabel(row.responseAvgHours)}</b>
          <br />
          <span className="text-muted-foreground">
            {row.responseSampleCount} 个样本 · 得分 {scoreLabel(row.responseScore)}
          </span>
        </span>
        <span>
          价格竞争力 <b className="text-foreground">{rateLabel(row.priceIndex)}</b>
          <br />
          <span className="text-muted-foreground">
            {row.priceSampleCount} 个可比 SKU · 得分 {scoreLabel(row.priceScore)}
          </span>
        </span>
        <span>
          售后配合度 <b className="text-foreground">{rateLabel(row.serviceCooperationRate)}</b>
          <br />
          <span className="text-muted-foreground">
            {row.serviceClaimCount} 笔索赔 · 得分 {scoreLabel(row.serviceScore)}
          </span>
        </span>
      </div>

      {unavailableText(row.unavailableDimensions) ? (
        <p className="text-muted-foreground mt-2 text-xs">
          没有依据的维度：{unavailableText(row.unavailableDimensions)}（已排除在合成之外，
          <b>不是 0 分</b>）
          {row.unavailableReason ? ` · ${row.unavailableReason}` : ''}
        </p>
      ) : null}
    </div>
  );
}
