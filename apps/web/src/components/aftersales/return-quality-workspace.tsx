'use client';

import { useCallback, useEffect, useState } from 'react';
import { RefreshCw, TriangleAlert } from 'lucide-react';
import { Badge, Button, EmptyState, Input, PageHeader, SectionCard, StatCard } from '@starcon/ui';
import type {
  QualityInspectionDetail,
  QualityInspectionPage,
  QualityPendingPage,
  QualityTemplateDetail,
  QualityTemplatePage,
  QualityCategoryPage,
  QualityVerdictPreview,
  ReturnQualityCapabilities,
} from '../../../../../packages/api-contracts/src/index';
import {
  actionable,
  activateQualityTemplate,
  blocksResale,
  cancelQualityInspection,
  CHECK_OUTCOME_OPTIONS,
  concludeBlockedReason,
  concludeInspection,
  createQualityInspection,
  failsMissingNote,
  fetchQualityCapabilities,
  fetchQualityCategories,
  fetchQualityInspection,
  fetchQualityInspections,
  fetchQualityQueue,
  fetchQualityTemplate,
  fetchQualityTemplates,
  fetchVerdictPreview,
  initialDrafts,
  recordQualityResults,
  retireQualityTemplate,
  sortEvents,
  unavailableActions,
  VERDICT_OPTIONS,
  type CheckDraft,
} from './return-quality-model';

/**
 * 11.2.3.1 质检与判定。
 *
 * <p>蓝图：「退回商品质检：外观、功能、配件完整性、是否可再销售；
 * 给出可再销售/残次/报废判定与残值估算。」
 * 输出：「质检结论 + 残值金额 + <b>质量状态指令</b>。」
 * 加粗句：「<b>可再销售判定要保守</b>：包装破损但商品完好可判可再销售，缺少配件则应判残次。」
 *
 * <p><b>页面围绕两条硬规则组织</b>：
 *
 * <ol>
 *   <li><b>判定是三值枚举，页面把它渲染成三张带「允许 / 不允许」的卡片</b>，
 *       不允许的那张<b>连理由一起显示</b>（来自后端 {@code blockedReason}）。
 *       蓝图那句话的两半因此在界面上是两条不同的路径：
 *       「外箱压瘪 + 商品完好」看到「可再销售」可选，
 *       「配件缺失」看到的是「只能判残次」——
 *       而不是让人先选一个再被拒。</li>
 *   <li><b>残值是服务端算出来的，前端一个输入框都不给</b>：
 *       页面只显示试算值与公式的每一步（原值 / 扣减 / 系数 / 档位上限），
 *       并且标注「试算，最终以判定那一刻的 DB 重算为准」。
 *       一个可填的金额等于把损耗口径交给每个操作员自己编。</li>
 * </ol>
 *
 * <p>⚠️ 页面**刻意没有**「执行处置（转可售 / 残次 / 报废）」「残值入账」「发起索赔」三个按钮：
 * 它们属 11.2.3.2 / 12 财务 / 11.2.3.3，均**未交付**。
 * 蓝图加粗句「<b>报废是不可逆的</b>，需审批与原因，不能由客服随手处理」正是这条规则的落点 ——
 * 渲染一个注定被拒的按钮等于告诉质检员系统坏了，而真相是这条路径本就不该存在。
 */
export function ReturnQualityWorkspace() {
  const [page, setPage] = useState(0);
  const [status, setStatus] = useState('');
  const [verdict, setVerdict] = useState('');
  const [keyword, setKeyword] = useState('');
  const [data, setData] = useState<QualityInspectionPage | null>(null);
  const [queue, setQueue] = useState<QualityPendingPage | null>(null);
  const [templates, setTemplates] = useState<QualityTemplatePage | null>(null);
  const [categories, setCategories] = useState<QualityCategoryPage | null>(null);
  const [caps, setCaps] = useState<ReturnQualityCapabilities | null>(null);
  const [detail, setDetail] = useState<QualityInspectionDetail | null>(null);
  const [preview, setPreview] = useState<QualityVerdictPreview | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);

  // 判定输入：结论 + 理由。⚠️ **没有残值输入框**（硬规则 2）。
  const [verdictPick, setVerdictPick] = useState('');
  const [conclusionNote, setConclusionNote] = useState('');
  // 逐项结果草稿（本地编辑，提交时整单替换）。
  const [drafts, setDrafts] = useState<CheckDraft[]>([]);
  const [cancelReason, setCancelReason] = useState('');

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      // ⚠️ exactOptionalPropertyTypes：筛选条件只能「要么有要么没有」，
      //   不能把 undefined 当成一个已给的键塞进去。
      const filters: Parameters<typeof fetchQualityInspections>[0] = { page, size: 20 };
      if (status) filters.status = status;
      if (verdict) filters.verdict = verdict;
      if (keyword) filters.keyword = keyword;
      setData(await fetchQualityInspections(filters));
      setQueue(await fetchQualityQueue({ page: 0, size: 10 }));
      setTemplates(await fetchQualityTemplates({ page: 0, size: 10 }));
      setCategories(await fetchQualityCategories({ page: 0, size: 50 }));
      setCaps(await fetchQualityCapabilities());
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setLoading(false);
    }
  }, [page, status, verdict, keyword]);

  useEffect(() => {
    void load();
  }, [load]);

  /** 打开一张质检单：详情与**判定预览**一起拉 —— 判定卡片必须与逐项结果同屏。 */
  const open = useCallback(async (id: string) => {
    setError(null);
    try {
      const d = await fetchQualityInspection(id);
      setDetail(d);
      // ⚠️ 分两路而不是一个三元：待检检项带 `itemRequired`，已录结果行没有这个字段，
      //   把两者塞进同一个联合类型去喂 initialDrafts 会把 `id` 逼成 optional。
      const seeded: CheckDraft[] =
        d.pendingItems.length > 0 ? initialDrafts(d.pendingItems) : d.results.map(toDraftFromRow);
      setDrafts(seeded);
      setVerdictPick(d.head.verdict ?? '');
      setConclusionNote('');
      setCancelReason('');
      try {
        setPreview(await fetchVerdictPreview(d.head.postingId));
      } catch {
        // 预览是**尽力而为**的辅助读数，拿不到不该挡住质检员干活。
        setPreview(null);
      }
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    }
  }, []);

  /** 从待检队列直接开单。 */
  const start = useCallback(
    async (postingId: string) => {
      setError(null);
      try {
        const created = await createQualityInspection({ postingId, templateId: null });
        await load();
        await open(created.head.id);
      } catch (e) {
        setError(e instanceof Error ? e.message : String(e));
      }
    },
    [load, open],
  );

  const run = useCallback(
    async (work: () => Promise<QualityInspectionDetail>) => {
      setBusy(true);
      setError(null);
      try {
        const d = await work();
        setDetail(d);
        await load();
      } catch (e) {
        setError(e instanceof Error ? e.message : String(e));
      } finally {
        setBusy(false);
      }
    },
    [load],
  );

  const saveResults = () => {
    if (!detail) return;
    const version = detail.head.version;
    void run(async () => {
      const saved = await recordQualityResults(detail.head.id, {
        results: drafts.map((d) => ({
          templateItemId: d.templateItemId,
          checkOutcome: d.checkOutcome,
          checkNote: d.checkNote || null,
          photoId: d.photoId,
        })),
        expectedVersion: version,
      });
      return saved;
    });
  };

  const conclude = () => {
    if (!detail) return;
    const version = detail.head.version;
    void run(async () => {
      const done = await concludeInspection(detail.head.id, {
        verdict: verdictPick,
        conclusionNote,
        expectedVersion: version,
      });
      setConclusionNote('');
      return done;
    });
  };

  const cancel = () => {
    if (!detail) return;
    const version = detail.head.version;
    void run(async () => {
      const done = await cancelQualityInspection(detail.head.id, {
        cancelReason,
        expectedVersion: version,
      });
      setCancelReason('');
      return done;
    });
  };

  const toggleTemplate = async (id: string, status: string) => {
    setError(null);
    try {
      const d: QualityTemplateDetail =
        status === 'DRAFT' ? await activateQualityTemplate(id) : await retireQualityTemplate(id);
      setTemplates((t) =>
        t
          ? {
              ...t,
              rows: t.rows.map((r) => (r.id === d.template.id ? d.template : r)),
            }
          : t,
      );
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    }
  };

  const summary = data?.summary;
  const concludeBlock = detail ? concludeBlockedReason(detail) : null;
  const missingNotes = failsMissingNote(drafts);

  return (
    <div className="space-y-4">
      <PageHeader
        title="质检与判定"
        description="退回商品的质检结论、残值估算与质量状态指令"
        actions={
          <Button onClick={() => void load()} disabled={loading}>
            <RefreshCw className="mr-1 h-4 w-4" />
            刷新
          </Button>
        }
      />

      {error ? (
        <div className="border-destructive/40 bg-destructive/10 text-destructive flex items-start gap-2 rounded-lg border p-3 text-sm">
          <TriangleAlert className="mt-0.5 h-4 w-4 shrink-0" />
          <div>
            <div className="font-medium">操作失败</div>
            <div className="mt-1 break-words">{error}</div>
          </div>
        </div>
      ) : null}

      {/* ---- 第一屏：现在有多少件货等着我判 ---- */}
      <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
        <StatCard label="待检队列" value={summary?.pendingQueue ?? queue?.total ?? 0} polarity="neutral" />
        <StatCard label="草稿" value={summary?.draft ?? 0} polarity="neutral" />
        <StatCard label="可再销售" value={summary?.resalable ?? 0} polarity="positive" />
        <StatCard label="残次 / 报废" value={(summary?.defective ?? 0) + (summary?.scrapped ?? 0)} polarity="negative" />
      </div>

      {/* ---- 待检队列：质检员上班第一屏 ---- */}
      <SectionCard title="待检队列（已入库但还没开质检单）">
        {loading ? (
          <div className="text-muted-foreground py-6 text-center text-sm">加载中…</div>
        ) : queue && queue.rows.length === 0 ? (
          <EmptyState
            title="待检队列是空的"
            description="11.2.2.1 退货入库还没有产出待检分录。退回商品默认进待检 —— 这一格为空说明货还没收到，不是「已经检完了」。"
          />
        ) : queue ? (
          <div className="space-y-2">
            {queue.rows.map((r) => (
              <div
                key={r.postingId}
                className="hover:bg-muted/50 flex flex-wrap items-center gap-2 rounded border p-2"
              >
                <div className="min-w-0 flex-1">
                  <div className="text-sm font-medium">{r.skuName || r.skuCode}</div>
                  {/* ⚠️ 两个判定维度**并排**显示：外箱压瘪但商品完好是蓝图点名的合法组合。 */}
                  <div className="text-muted-foreground text-xs">
                    {r.skuCode} · 待检 <b>{String(r.pendingQty)}</b> · 外包装 {r.packagingConditionLabel}
                    <span className="mx-1">|</span>
                    商品 {r.goodsConditionLabel}
                  </div>
                  <div className="text-muted-foreground mt-0.5 text-xs">
                    {r.hasTemplate ? (
                      <>模板：{r.templateName}</>
                    ) : (
                      <span className="text-amber-600">
                        该 SKU 还没有归质检类目，也没有可用模板 —— 归类后才能开单
                      </span>
                    )}
                  </div>
                </div>
                <Button disabled={busy || !r.hasTemplate} onClick={() => void start(r.postingId)}>
                  开质检单
                </Button>
              </div>
            ))}
          </div>
        ) : null}
      </SectionCard>

      {/* ---- 质检单列表 ---- */}
      <SectionCard title="质检单">
        <div className="mb-3 grid gap-2 md:grid-cols-4">
          <Input placeholder="关键字（单号 / SKU / 退货单号）" value={keyword} onChange={(e) => setKeyword(e.target.value)} />
          <select className="rounded border px-2 py-1 text-sm" value={status} onChange={(e) => setStatus(e.target.value)}>
            <option value="">全部状态</option>
            <option value="DRAFT">草稿</option>
            <option value="CONCLUDED">已判定</option>
            <option value="CANCELLED">已撤销</option>
          </select>
          <select className="rounded border px-2 py-1 text-sm" value={verdict} onChange={(e) => setVerdict(e.target.value)}>
            <option value="">全部结论</option>
            <option value="RESALABLE">可再销售</option>
            <option value="DEFECTIVE">残次</option>
            <option value="SCRAPPED">报废</option>
          </select>
        </div>

        {loading ? (
          <div className="text-muted-foreground py-6 text-center text-sm">加载中…</div>
        ) : data && data.rows.length === 0 ? (
          <EmptyState
            title="没有质检单"
            description="从上面的待检队列开一张，或者先建好质检类目与模板再开单。"
          />
        ) : data ? (
          <table className="w-full text-sm">
            <thead>
              <tr className="border-b text-left">
                <th className="py-2">质检单</th>
                <th className="py-2">状态</th>
                <th className="py-2">结论</th>
                <th className="py-2">残值</th>
              </tr>
            </thead>
            <tbody>
              {data.rows.map((r) => (
                <tr key={r.id} className="hover:bg-muted/50 cursor-pointer border-b" onClick={() => void open(r.id)}>
                  <td className="py-2">
                    <div>{r.inspectionNo}</div>
                    <div className="text-muted-foreground text-xs">
                      {r.skuName || r.skuCode} · {r.templateName} v{r.templateVersion}
                    </div>
                  </td>
                  <td className="py-2">
                    <Badge tone={r.inspectionStatus === 'CONCLUDED' ? 'success' : 'neutral'}>
                      {r.inspectionStatusLabel}
                    </Badge>
                  </td>
                  <td className="py-2">{r.verdictLabel ?? <span className="text-muted-foreground">—</span>}</td>
                  <td className="py-2 text-xs">
                    {r.verdict ? (
                      <>
                        {String(r.salvageAmount)} {r.salvageCurrency}
                        <span className="text-muted-foreground"> / 原值 {String(r.originalValue)}</span>
                      </>
                    ) : (
                      <span className="text-muted-foreground">未判定</span>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        ) : null}

        {data && data.total > data.size ? (
          <div className="mt-3 flex gap-2">
            <Button disabled={page === 0} onClick={() => setPage((p) => p - 1)}>上一页</Button>
            <span className="text-muted-foreground self-center text-sm">
              第 {data.page + 1} 页 / 共 {data.total} 条
            </span>
            <Button disabled={(page + 1) * data.size >= data.total} onClick={() => setPage((p) => p + 1)}>
              下一页
            </Button>
          </div>
        ) : null}
      </SectionCard>

      {/* ---- 单张质检单 ---- */}
      {detail ? (
        <SectionCard title={`质检单 ${detail.head.inspectionNo}`}>
          <div className="space-y-4">
            <div className="grid gap-2 text-sm md:grid-cols-3">
              <div>
                <div className="text-muted-foreground text-xs">商品</div>
                <div>{detail.head.skuName || detail.head.skuCode}</div>
              </div>
              <div>
                <div className="text-muted-foreground text-xs">被检数量</div>
                <div>{String(detail.head.inspectedQty)}</div>
              </div>
              <div>
                <div className="text-muted-foreground text-xs">模板口径</div>
                <div>
                  {detail.head.templateName} v{detail.head.templateVersion}
                </div>
              </div>
            </div>

            {/* ---- 判定路径：蓝图加粗句的两半在这里是两条不同的路 ---- */}
            <div className="bg-muted/40 rounded border p-3">
              <div className="text-sm font-medium">判定路径：{detail.requiredPathLabel}</div>
              <div className="mt-2 grid gap-2 md:grid-cols-3">
                {VERDICT_OPTIONS.map((o) => {
                  const server = detail.verdictOptions.find((v) => v.code === o.code);
                  const allowed = server ? server.allowed : true;
                  return (
                    <div
                      key={o.code}
                      className={
                        allowed
                          ? 'border-border rounded border p-2 text-sm'
                          : 'border-destructive/40 bg-destructive/10 rounded border p-2 text-sm'
                      }
                    >
                      <div className="flex items-center gap-1">
                        <span className="font-medium">{o.label}</span>
                        <Badge tone={allowed ? 'success' : 'danger'}>
                          {allowed ? '允许' : '不允许'}
                        </Badge>
                      </div>
                      {server?.blockedReason ? (
                        <div className="text-muted-foreground mt-1 text-xs">{server.blockedReason}</div>
                      ) : null}
                    </div>
                  );
                })}
              </div>
            </div>

            {/* ---- 残值：只读试算，公式每一步都摆出来 ---- */}
            {preview ? (
              <div className="rounded border p-3">
                <div className="text-sm font-medium">残值试算</div>
                <div className="text-muted-foreground mt-1 grid gap-1 text-xs md:grid-cols-4">
                  <div>原值 {String(preview.originalValue)} {preview.salvageCurrency}</div>
                  <div>扣减 {String(preview.salvageDeduction)}</div>
                  <div>成色系数 {String(preview.gradeCoefficient)}</div>
                  <div>档位上限 {String(preview.salvageCap)}</div>
                </div>
                <div className="mt-2 text-sm">
                  试算残值 <b>{String(preview.salvageEstimate)} {preview.salvageCurrency}</b>
                </div>
                <div className="text-muted-foreground mt-1 text-xs">{preview.note}</div>
              </div>
            ) : null}

            {/* ---- 逐项结果 ---- */}
            <div>
              <div className="mb-2 flex items-center gap-2">
                <span className="text-sm font-medium">逐项检查结果</span>
                {detail.mandatoryMissingCount > 0 ? (
                  <Badge tone="danger">还有 {detail.mandatoryMissingCount} 个强制检项没有结果</Badge>
                ) : null}
              </div>
              {drafts.length === 0 ? (
                <div className="text-muted-foreground text-sm">这张质检单还没有可录的检项。</div>
              ) : (
                <div className="space-y-2">
                  {drafts.map((d, idx) => {
                    // ⚠️ 「强制」只存在于模板检项上（结果行是快照，不带这个标记），
                    //   所以两个来源分开找，而不是合成一个联合类型再取属性。
                    const pendingItem = detail.pendingItems.find((i) => i.id === d.templateItemId);
                    const row = detail.results.find((r) => r.templateItemId === d.templateItemId);
                    const item = pendingItem ?? row;
                    const blocking = blocksResale(item?.itemDimension ?? null);
                    return (
                      <div key={d.templateItemId} className="flex flex-wrap items-center gap-2 rounded border p-2">
                        <div className="min-w-0 flex-1">
                          <div className="text-sm">
                            {item?.itemName ?? d.templateItemId}
                            {pendingItem?.itemRequired ? <span className="ml-1 text-xs">· 强制</span> : null}
                          </div>
                          <div className="text-muted-foreground text-xs">
                            {item?.itemDimensionLabel ?? item?.itemDimension ?? ''}
                            {blocking ? ' · 本项不合格将禁止判可再销售' : ' · 本项不合格只扣成色系数'}
                          </div>
                        </div>
                        <select
                          className="rounded border px-2 py-1 text-sm"
                          value={d.checkOutcome}
                          onChange={(e) =>
                            setDrafts((prev) =>
                              prev.map((x, i) =>
                                i === idx ? { ...x, checkOutcome: e.target.value } : x,
                              ),
                            )
                          }
                        >
                          {CHECK_OUTCOME_OPTIONS.map((o) => (
                            <option key={o.code} value={o.code}>
                              {o.label}
                            </option>
                          ))}
                        </select>
                        <Input
                          className="max-w-56"
                          placeholder="不合格必须写说明"
                          value={d.checkNote}
                          onChange={(e) =>
                            setDrafts((prev) =>
                              prev.map((x, i) =>
                                i === idx ? { ...x, checkNote: e.target.value } : x,
                              ),
                            )
                          }
                        />
                      </div>
                    );
                  })}
                </div>
              )}
              {missingNotes.length > 0 ? (
                <div className="text-destructive mt-2 text-xs">
                  还有 {missingNotes.length} 项判了不合格但没写说明 —— 判残次要拿去向供应商与平台索赔，
                  没有理由的「不合格」在纠纷里等于没有不合格。
                </div>
              ) : null}
              <Button className="mt-2" disabled={busy} onClick={saveResults}>
                保存逐项结果
              </Button>
            </div>

            {/* ---- 判定 ---- */}
            <div className="rounded border p-3">
              <div className="text-sm font-medium">判定</div>
              <div className="mt-2 grid gap-2 md:grid-cols-2">
                <select
                  className="rounded border px-2 py-1 text-sm"
                  value={verdictPick}
                  onChange={(e) => setVerdictPick(e.target.value)}
                >
                  <option value="">选择结论</option>
                  {VERDICT_OPTIONS.map((o) => {
                    const server = detail.verdictOptions.find((v) => v.code === o.code);
                    return (
                      <option key={o.code} value={o.code} disabled={server ? !server.allowed : false}>
                        {o.label}
                        {server && !server.allowed ? '（不允许）' : ''}
                      </option>
                    );
                  })}
                </select>
                <Input
                  placeholder="判定理由（必填：判残次要索赔，判报废要经审批）"
                  value={conclusionNote}
                  onChange={(e) => setConclusionNote(e.target.value)}
                />
              </div>
              <div className="text-muted-foreground mt-1 text-xs">
                ⚠️ 这里**没有残值输入框**：残值由「原值 × 生效系数」算出后写库，
                再由数据库触发器重算比对 —— 填一个数等于把损耗口径交给操作员自己编。
              </div>
              {concludeBlock ? (
                <div className="text-muted-foreground mt-2 text-xs">判定不可用：{concludeBlock}</div>
              ) : null}
              <Button
                className="mt-2"
                disabled={busy || !!concludeBlock || !verdictPick || !conclusionNote.trim()}
                onClick={conclude}
              >
                判定
              </Button>

              <div className="mt-3 flex items-center gap-2">
                <Input
                  className="max-w-72"
                  placeholder="撤销理由（必填）"
                  value={cancelReason}
                  onChange={(e) => setCancelReason(e.target.value)}
                />
                <Button
                  variant="secondary"
                  disabled={busy || !cancelReason.trim() || detail.head.inspectionStatus !== 'DRAFT'}
                  onClick={cancel}
                >
                  撤销
                </Button>
              </div>
            </div>

            {/* ---- 质量状态指令：只出不消费 ---- */}
            {detail.instruction ? (
              <div className="rounded border p-3">
                <div className="text-sm font-medium">
                  质量状态指令 · {detail.instruction.instructionTypeLabel}
                </div>
                <div className="text-muted-foreground mt-1 grid gap-1 text-xs md:grid-cols-3">
                  <div>目标库存状态 {detail.instruction.targetInventoryState}</div>
                  <div>数量 {String(detail.instruction.qty)}</div>
                  <div>
                    残值 {String(detail.instruction.salvageAmount)} {detail.instruction.salvageCurrency}
                  </div>
                </div>
                <div className="text-muted-foreground mt-1 text-xs">
                  状态恒为 {detail.instruction.instructionStatus}，消费方恒为 {detail.instruction.consumerModule}
                  （未交付）—— 本模块只出指令，不改库存。
                </div>
              </div>
            ) : null}

            {/* ---- 留痕 ---- */}
            <div>
              <div className="mb-1 text-sm font-medium">留痕</div>
              <div className="space-y-1">
                {sortEvents(detail.events).map((e) => (
                  <div key={e.id} className="text-muted-foreground text-xs">
                    {e.occurredAt} · {e.eventTypeLabel} · {e.reasonNote}
                  </div>
                ))}
              </div>
            </div>
          </div>
        </SectionCard>
      ) : null}

      {/* ---- 模板与类目 ---- */}
      <div className="grid gap-4 lg:grid-cols-2">
        <SectionCard title="质检模板（按类目取用）">
          {templates && templates.rows.length === 0 ? (
            <EmptyState
              title="还没有质检模板"
              description="模板是判定口径的来源：先建类目、把 SKU 归进去，再建模板并启用。"
            />
          ) : templates ? (
            <div className="space-y-2">
              {templates.rows.map((t) => (
                <div key={t.id} className="flex flex-wrap items-center gap-2 rounded border p-2">
                  <div className="min-w-0 flex-1">
                    <div className="text-sm">
                      {t.templateName} <span className="text-muted-foreground">v{t.templateVersion}</span>
                    </div>
                    <div className="text-muted-foreground text-xs">
                      {t.categoryName} · {t.itemCount} 项（强制 {t.mandatoryItemCount}） · 成色基准{' '}
                      {String(t.baseCoefficient)}
                    </div>
                  </div>
                  <Badge tone={t.templateStatus === 'ACTIVE' ? 'success' : 'neutral'}>
                    {t.templateStatusLabel}
                  </Badge>
                  {t.templateStatus === 'DRAFT' ? (
                    <Button size="sm" disabled={busy} onClick={() => void toggleTemplate(t.id, t.templateStatus)}>
                      启用
                    </Button>
                  ) : null}
                  {t.templateStatus === 'ACTIVE' ? (
                    <Button size="sm" variant="secondary" disabled={busy} onClick={() => void toggleTemplate(t.id, t.templateStatus)}>
                      停用
                    </Button>
                  ) : null}
                </div>
              ))}
            </div>
          ) : null}
        </SectionCard>

        <SectionCard title="质检类目（租户自维护）">
          {categories && categories.rows.length === 0 ? (
            <EmptyState
              title="还没有质检类目"
              description="商品类目真值在 catalog 服务的独立数据库里，按模块边界纪律跨服务读不到 —— 所以质检类目是租户自己维护的作业口径。"
            />
          ) : categories ? (
            <div className="space-y-2">
              {categories.rows.map((c) => (
                <div key={c.id} className="flex flex-wrap items-center gap-2 rounded border p-2">
                  <div className="min-w-0 flex-1">
                    <div className="text-sm">{c.categoryName}</div>
                    <div className="text-muted-foreground text-xs">
                      {c.categoryCode} · {c.skuCount} 个 SKU · 模板 {c.templateCount} 个（启用{' '}
                      {c.activeTemplateCount}）
                    </div>
                  </div>
                  <Badge tone={c.categoryStatus === 'ACTIVE' ? 'success' : 'neutral'}>
                    {c.categoryStatusLabel}
                  </Badge>
                </div>
              ))}
            </div>
          ) : null}
        </SectionCard>
      </div>

      {/* ---- 能力边界：未交付的下游与刻意不做的事 ---- */}
      {caps ? (
        <SectionCard title="能力边界">
          <div className="space-y-2 text-xs">
            {caps.sections
              .filter((s) => !s.available)
              .map((s) => (
                <div key={s.section} className="rounded border p-2">
                  <div className="font-medium">
                    {s.label} · 归属 {s.ownerModule}
                  </div>
                  <div className="text-muted-foreground mt-0.5">{s.reason}</div>
                </div>
              ))}
            <div className="text-muted-foreground">{caps.verdictRuleNote}</div>
            <div className="text-muted-foreground">{caps.dispositionNote}</div>
          </div>
        </SectionCard>
      ) : null}
    </div>
  );
}

/** 已录结果 → 本地草稿（重开一张已录过部分结果的质检单时不至于清空）。 */
function toDraftFromRow(r: { templateItemId: string; checkOutcome: string; checkNote: string | null }): CheckDraft {
  return {
    templateItemId: r.templateItemId,
    checkOutcome: r.checkOutcome,
    checkNote: r.checkNote ?? '',
    photoId: null,
  };
}
