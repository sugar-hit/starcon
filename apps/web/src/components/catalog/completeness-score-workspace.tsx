'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import { Badge, Button, EmptyState, SectionCard, StatCard } from '@starcon/ui';
import { RefreshCw, TriangleAlert, ClipboardList, CheckCircle2 } from 'lucide-react';
import type {
  CompletenessGap,
  CompletenessRow,
  CompletenessScorecard,
} from '../../../../../packages/api-contracts/src/index';
import { Field, control } from '../common/fields';
import {
  certificationCompletenessLabel,
  completenessErrorText,
  dimensionProgress,
  emptyScoreListQuery,
  evaluate,
  fetchGaps,
  fetchGapSummary,
  fetchOverview,
  fetchScorecard,
  fetchScores,
  gradeLabel,
  gradeTone,
  ratioText,
  sampleOwnersText,
  scoreText,
  type ScoreListQuery,
} from './completeness-score-model';

/**
 * 3.9.2.1 完整度评分工作台。
 *
 * <p>页面上最要紧的三件事，顺序不能换：
 *
 * <ol>
 *   <li><b>两个就绪结论分开显示</b>（刊登 / 清关）。一个带图有属性但没 HS 编码的商品
 *       可能是"刊登就绪 · 清关未就绪"，合并成一个"完整度 78%"之后
 *       运营看不出自己被哪条链拦住，也就不知道该去补报关还是该去传图。
 *   <li><b>缺失项逐条列，带主体名与下一步</b>。只给一个"缺 12 项"的计数
 *       没法驱动补全 —— 「缺 3 条必填属性」让人不知道填哪三条，
 *       「缺 颜色 / 尺码」可以直接点进去。
 *   <li><b>分数一律现算</b>。改完主数据刷新即可看到变化；
 *       页面不会显示"截至某时"的分数冒充当前状态。
 * </ol>
 *
 * <p>加载态 / 空态 / 错误态三者分开渲染：台账为空是正常状态（还没建商品），
 * 与"加载失败"不能共用一个界面 —— 后者必须让人一眼看出要重试。
 */
export function CompletenessScoreWorkspace({ writable }: { writable: boolean }) {
  const [overview, setOverview] = useState<Awaited<ReturnType<typeof fetchOverview>> | null>(null);
  const [rows, setRows] = useState<CompletenessRow[]>([]);
  const [total, setTotal] = useState(0);
  const [query, setQuery] = useState<ScoreListQuery>(emptyScoreListQuery());
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [card, setCard] = useState<CompletenessScorecard | null>(null);
  const [gaps, setGaps] = useState<CompletenessGap[]>([]);
  const [gapSummary, setGapSummary] = useState<Awaited<ReturnType<typeof fetchGapSummary>> | null>(
    null,
  );
  const [tab, setTab] = useState<'scores' | 'gaps'>('scores');

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const [ov, page, gapPage, summary] = await Promise.all([
        fetchOverview(),
        fetchScores(query),
        fetchGaps({
          ...(query.ownerType ? { ownerType: query.ownerType } : {}),
          ...(query.keyword ? { keyword: query.keyword } : {}),
          size: 20,
        }),
        fetchGapSummary(query.ownerType ?? undefined),
      ]);
      setOverview(ov);
      setRows(page.items);
      setTotal(page.total);
      setGaps(gapPage.items);
      setGapSummary(summary);
    } catch (e) {
      setError(completenessErrorText(e));
    } finally {
      setLoading(false);
    }
  }, [query]);

  useEffect(() => {
    void load();
  }, [load]);

  const patch = (next: Partial<ScoreListQuery>) =>
    setQuery((current) => ({ ...current, ...next, page: 0 }));

  const pages = Math.max(1, Math.ceil(total / (query.size ?? 20)));

  const openCard = async (row: CompletenessRow) => {
    setError(null);
    setNotice(null);
    try {
      setCard(await fetchScorecard(row.ownerType, row.ownerId));
    } catch (e) {
      setError(completenessErrorText(e));
    }
  };

  /** 批量评估当前页：只传主体 id，分数由服务端现算（前端不许自填分数）。 */
  const evaluatePage = async () => {
    setError(null);
    setNotice(null);
    const ownerIds = rows.map((row) => row.ownerId);
    if (ownerIds.length === 0 || !query.ownerType) return;
    try {
      const result = await evaluate(
        query.ownerType,
        ownerIds,
        `台账第 ${(query.page ?? 0) + 1} 页`,
      );
      setNotice(
        `已评估 ${result.succeeded} 条` +
          (result.failed ? `，${result.failed} 条失败（不存在或跨租户）` : '') +
          '。评估快照只回答「当时为什么被拦住」，评分卡仍然是现算的。',
      );
      await load();
    } catch (e) {
      setError(completenessErrorText(e));
    }
  };

  return (
    <SectionCard
      title="完整度评分（3.9.2.1）"
      description="给每个 SPU / SKU 打主数据完整度分：必填属性、类目必填、图片数量与尺寸、报关属性、包装尺寸重量、认证，按分数分档并驱动补全清单。完整度直接决定能否刊登与能否清关。"
      extra={
        <div className="flex gap-2">
          <Button size="sm" variant="ghost" disabled={loading} onClick={() => void load()}>
            <RefreshCw className="h-4 w-4" />
            刷新
          </Button>
          {writable && (
            <Button
              size="sm"
              disabled={loading || rows.length === 0}
              onClick={() => void evaluatePage()}
            >
              <ClipboardList className="h-4 w-4" />
              评估本页并存快照
            </Button>
          )}
        </div>
      }
    >
      <div className="space-y-5">
        {/* 治理数字：分档分布与两条链的就绪数。正常情况下 blockedCount 应该很小。 */}
        {overview && (
          <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
            <StatCard
              label="在评主体"
              value={overview.skuCount + overview.spuCount}
              unit={`SPU ${overview.spuCount} · SKU ${overview.skuCount}`}
            />
            <StatCard
              label="刊登就绪"
              value={overview.listingReadyCount}
              comparisonLabel="必填属性 / 图片 / 类目必填 / 认证 四维无缺失且总分过门槛"
            />
            <StatCard
              label="清关就绪"
              value={overview.customsReadyCount}
              comparisonLabel="报关属性与包装尺寸重量两维无缺失"
            />
            <StatCard
              label="两条链都被拦"
              value={overview.bothBlockedCount}
              comparisonLabel="既不能刊登也不能清关"
            />
          </div>
        )}

        {overview && (
          <div className="flex flex-wrap items-center gap-2 text-xs">
            <span className="text-muted-foreground">分档分布：</span>
            {overview.gradeCounts.map((grade) => (
              <Badge key={grade.grade} tone={gradeTone(grade.grade) as never}>
                {gradeLabel(grade.grade)} {grade.count}
              </Badge>
            ))}
          </div>
        )}

        {error && (
          <div
            role="alert"
            className="border-destructive/40 bg-destructive/10 flex items-start gap-2 rounded-md border p-3 text-sm"
          >
            <TriangleAlert className="mt-0.5 h-4 w-4 shrink-0" />
            <span>{error}</span>
          </div>
        )}

        {notice && (
          <div className="border-border bg-muted/40 flex items-start gap-2 rounded-md border p-3 text-sm">
            <CheckCircle2 className="mt-0.5 h-4 w-4 shrink-0" />
            <span>{notice}</span>
          </div>
        )}

        <div className="flex gap-2 text-sm">
          <Button
            size="sm"
            variant={tab === 'scores' ? 'default' : 'ghost'}
            onClick={() => setTab('scores')}
          >
            评分台账
          </Button>
          <Button
            size="sm"
            variant={tab === 'gaps' ? 'default' : 'ghost'}
            onClick={() => setTab('gaps')}
          >
            补全清单
          </Button>
        </div>

        {tab === 'scores' && (
          <>
            <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
              <Field label="关键词">
                <input
                  className={control}
                  value={query.keyword ?? ''}
                  onChange={(e) => patch({ keyword: e.target.value })}
                  placeholder="编码或名称"
                />
              </Field>
              <Field label="主体类型">
                <select
                  className={control}
                  value={query.ownerType ?? 'SKU'}
                  onChange={(e) => patch({ ownerType: e.target.value })}
                >
                  <option value="SKU">SKU</option>
                  <option value="SPU">SPU</option>
                </select>
              </Field>
              <Field label="只看有缺失的">
                <select
                  className={control}
                  value={query.missingOnly ? '1' : ''}
                  onChange={(e) => patch({ missingOnly: e.target.value === '1' })}
                >
                  <option value="">全部</option>
                  <option value="1">仅有缺失项</option>
                </select>
              </Field>
              <Field label="只看刊登未就绪">
                <select
                  className={control}
                  value={query.listingReadyOnly ? '1' : ''}
                  onChange={(e) => patch({ listingReadyOnly: e.target.value === '1' })}
                >
                  <option value="">全部</option>
                  <option value="1">仅刊登未就绪</option>
                </select>
              </Field>
            </div>

            {loading ? (
              <p className="text-muted-foreground text-sm">正在按当前主数据现算完整度…</p>
            ) : rows.length === 0 ? (
              <EmptyState
                title="还没有可评分的主体"
                description="先建 SPU 与 SKU，完整度会立刻按当前主数据算出来。台账为空是正常状态，不是加载失败。"
              />
            ) : (
              <>
                <div className="overflow-x-auto">
                  <table className="w-full text-sm">
                    <thead className="text-muted-foreground text-left text-xs">
                      <tr>
                        <th className="py-2 pr-3">编码</th>
                        <th className="py-2 pr-3">名称</th>
                        <th className="py-2 pr-3">分数</th>
                        <th className="py-2 pr-3">分档</th>
                        <th className="py-2 pr-3">缺失</th>
                        <th className="py-2 pr-3">刊登</th>
                        <th className="py-2 pr-3">清关</th>
                        <th className="py-2" />
                      </tr>
                    </thead>
                    <tbody>
                      {rows.map((row) => (
                        <tr
                          key={`${row.ownerType}-${row.ownerId}`}
                          className="border-border border-t"
                        >
                          <td className="py-2 pr-3 font-mono text-xs">{row.ownerCode}</td>
                          <td className="py-2 pr-3">{row.ownerName}</td>
                          <td className="py-2 pr-3 tabular-nums">
                            {scoreText(row.score, row.scorePercent)}
                          </td>
                          <td className="py-2 pr-3">
                            <Badge tone={badgeTone(gradeTone(row.grade))}>
                              {gradeLabel(row.grade)}
                            </Badge>
                          </td>
                          <td className="py-2 pr-3 tabular-nums">{row.missingCount}</td>
                          <td className="py-2 pr-3">
                            <Badge tone={row.listingReady ? 'success' : 'danger'}>
                              {row.listingReady ? '就绪' : '未就绪'}
                            </Badge>
                          </td>
                          <td className="py-2 pr-3">
                            <Badge tone={row.customsReady ? 'success' : 'danger'}>
                              {row.customsReady ? '就绪' : '未就绪'}
                            </Badge>
                          </td>
                          <td className="py-2 text-right">
                            <Button size="sm" variant="ghost" onClick={() => void openCard(row)}>
                              评分卡
                            </Button>
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
                <div className="flex items-center justify-between text-sm">
                  <span className="text-muted-foreground">共 {total} 条</span>
                  <div className="flex gap-2">
                    <Button
                      size="sm"
                      variant="ghost"
                      disabled={(query.page ?? 0) <= 0}
                      onClick={() => patch({ page: (query.page ?? 0) - 1 })}
                    >
                      上一页
                    </Button>
                    <span className="text-muted-foreground self-center">
                      第 {(query.page ?? 0) + 1} / {pages} 页
                    </span>
                    <Button
                      size="sm"
                      variant="ghost"
                      disabled={(query.page ?? 0) + 1 >= pages}
                      onClick={() => patch({ page: (query.page ?? 0) + 1 })}
                    >
                      下一页
                    </Button>
                  </div>
                </div>
              </>
            )}
          </>
        )}

        {tab === 'gaps' && (
          <>
            {gapSummary && (
              <div className="grid gap-3 sm:grid-cols-3">
                <StatCard label="在评主体" value={gapSummary.ownerCount} />
                <StatCard label="缺失项条数" value={gapSummary.missingCheckCount} />
                <StatCard
                  label="零缺失主体"
                  value={gapSummary.cleanCount}
                  comparisonLabel="补全工作的进度条"
                />
              </div>
            )}
            {loading ? (
              <p className="text-muted-foreground text-sm">正在汇总缺失项…</p>
            ) : gaps.length === 0 ? (
              <EmptyState
                title="没有缺失项"
                description="当前筛选范围内每个主体的六个维度都已齐全。补全清单为空是正常状态。"
              />
            ) : (
              <div className="overflow-x-auto">
                <table className="w-full text-sm">
                  <thead className="text-muted-foreground text-left text-xs">
                    <tr>
                      <th className="py-2 pr-3">缺失项</th>
                      <th className="py-2 pr-3">维度</th>
                      <th className="py-2 pr-3">影响主体</th>
                      <th className="py-2 pr-3">样本</th>
                      <th className="py-2 pr-3">下一步</th>
                    </tr>
                  </thead>
                  <tbody>
                    {gaps.map((gap) => (
                      <tr
                        key={`${gap.dimension}-${gap.code}`}
                        className="border-border border-t align-top"
                      >
                        <td className="py-2 pr-3">
                          <div className="font-medium">{gap.detail}</div>
                          <div className="text-muted-foreground font-mono text-xs">{gap.code}</div>
                        </td>
                        <td className="py-2 pr-3">{gap.dimensionLabel}</td>
                        <td className="py-2 pr-3 tabular-nums">{gap.affectedCount}</td>
                        <td className="text-muted-foreground py-2 pr-3 text-xs">
                          {sampleOwnersText(gap.sampleOwners)}
                        </td>
                        <td className="py-2 pr-3">{gap.remedy}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </>
        )}

        {card && (
          <section className="border-border space-y-3 rounded-md border p-4">
            <div className="flex flex-wrap items-center justify-between gap-2">
              <div>
                <h3 className="text-base font-semibold">
                  {card.ownerCode} · {card.ownerName}
                </h3>
                <p className="text-muted-foreground text-xs">
                  {card.ownerType} · {card.skuCount} 个变体
                  {card.categoryName ? ` · ${card.categoryName}` : ''}
                </p>
              </div>
              <div className="flex items-center gap-2">
                <span className="text-lg font-semibold tabular-nums">
                  {scoreText(card.score, card.scorePercent)}
                </span>
                <Badge tone={badgeTone(gradeTone(card.grade))}>{gradeLabel(card.grade)}</Badge>
                <Button size="sm" variant="ghost" onClick={() => setCard(null)}>
                  关闭
                </Button>
              </div>
            </div>

            {/* 两个就绪结论必须各自带阻塞原因，不能只给一个布尔。 */}
            <div className="grid gap-2 sm:grid-cols-2">
              <div className="border-border rounded-md border p-3 text-sm">
                <div className="flex items-center gap-2">
                  <span className="font-medium">刊登就绪</span>
                  <Badge tone={card.listingReady ? 'success' : 'danger'}>
                    {card.listingReady ? '就绪' : '未就绪'}
                  </Badge>
                </div>
                {!card.listingReady && (
                  <p className="text-muted-foreground mt-1 text-xs">
                    阻塞：{card.listingBlockers.join('、') || '总分未过门槛'}
                  </p>
                )}
              </div>
              <div className="border-border rounded-md border p-3 text-sm">
                <div className="flex items-center gap-2">
                  <span className="font-medium">清关就绪</span>
                  <Badge tone={card.customsReady ? 'success' : 'danger'}>
                    {card.customsReady ? '就绪' : '未就绪'}
                  </Badge>
                </div>
                {!card.customsReady && (
                  <p className="text-muted-foreground mt-1 text-xs">
                    阻塞：{card.customsBlockers.join('、') || '总分未过门槛'}
                  </p>
                )}
              </div>
            </div>

            <div className="grid gap-2 sm:grid-cols-2 lg:grid-cols-3">
              {card.dimensions.map((dimension) => (
                <div
                  key={dimension.dimension}
                  className="border-border rounded-md border p-3 text-sm"
                >
                  <div className="flex items-center justify-between">
                    <span className="font-medium">{dimension.dimensionLabel}</span>
                    <span className="tabular-nums">{ratioText(dimension.ratio)}</span>
                  </div>
                  <p className="text-muted-foreground mt-1 text-xs">
                    权重 {dimension.weight} ·{' '}
                    {dimensionProgress(dimension.satisfied, dimension.total)} 项已满足
                    {dimension.blockedFor.length
                      ? ` · 卡住${dimension.blockedFor.join('/')}链`
                      : ''}
                  </p>
                </div>
              ))}
            </div>

            {card.missing.length === 0 ? (
              <p className="text-sm">六个维度都已齐全，没有缺失项。</p>
            ) : (
              <div className="overflow-x-auto">
                <table className="w-full text-sm">
                  <thead className="text-muted-foreground text-left text-xs">
                    <tr>
                      <th className="py-2 pr-3">缺失项</th>
                      <th className="py-2 pr-3">主体</th>
                      <th className="py-2 pr-3">说明</th>
                      <th className="py-2">下一步</th>
                    </tr>
                  </thead>
                  <tbody>
                    {card.missing.map((check, index) => (
                      <tr
                        key={`${check.code}-${check.subjectLabel}-${index}`}
                        className="border-border border-t align-top"
                      >
                        <td className="py-2 pr-3 font-mono text-xs">{check.code}</td>
                        <td className="py-2 pr-3">{check.subjectLabel}</td>
                        <td className="py-2 pr-3">{check.detail}</td>
                        <td className="py-2">{check.remedy}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
            <p className="text-muted-foreground text-xs">{card.note}</p>
          </section>
        )}
      </div>
    </SectionCard>
  );
}

/** 评分模型里的 tone（ok / warn / danger / muted）→ Badge 词表。 */
function badgeTone(tone: 'danger' | 'warn' | 'ok' | 'muted') {
  if (tone === 'ok') return 'success' as const;
  if (tone === 'warn') return 'warning' as const;
  if (tone === 'muted') return 'neutral' as const;
  return 'danger' as const;
}

/**
 * 认证维度在评分卡上的说明：四种结论各有各的下一步动作，
 * 合成一条「认证缺失」会让人反复去填一个填不了的字段。
 */
export function certificationHint(completeness: string | null | undefined) {
  return certificationCompletenessLabel(completeness);
}
