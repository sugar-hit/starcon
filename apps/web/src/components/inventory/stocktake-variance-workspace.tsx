'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import { ClipboardCheck, ClipboardList, FileWarning, RefreshCw, ScrollText } from 'lucide-react';
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
  StocktakeSheet,
  StocktakeSheetPage,
  StocktakeVarianceAdjustmentDetail,
  StocktakeVarianceAdjustmentPage,
  StocktakeVarianceAuditEntry,
  StocktakeVarianceCapabilities,
  StocktakeVarianceLinePage,
  StocktakeVarianceReport,
} from '../../../../../packages/api-contracts/src/catalog';
import { fetchStocktakes } from './stocktake-model';
import {
  adjustmentStatusLabels,
  adjustmentStatusOptions,
  adjustmentTone,
  approveVarianceAdjustment,
  attributionProblem,
  attributeVarianceLine,
  cancelVarianceAdjustment,
  createAdjustmentProblem,
  createVarianceAdjustment,
  decisionOptions,
  dispositionText,
  errorText,
  fetchVarianceAdjustment,
  fetchVarianceAdjustments,
  fetchVarianceAudit,
  fetchVarianceCapabilities,
  fetchVarianceLines,
  fetchVarianceReport,
  formatMoment,
  lineFilterOptions,
  postVarianceAdjustment,
  progressPercent,
  progressText,
  qty,
  rateText,
  rateTone,
  reasonLabels,
  reasonOptions,
  rejectVarianceAdjustment,
  submitVarianceAdjustment,
  varianceText,
} from './stocktake-variance-model';
import { useIdentity } from '../iam/identity-shell';

/**
 * 7.4.2.2 盘点差异处理工作区。
 *
 * <p>蓝图：「处理盘点差异：按差异原因（损耗、错发、偷盗、未入账、时点差）分类，生成调整单走审批，
 * 盘盈盘亏入账并可追责。」输出：「调整单 + 盘点完成报告（含盘盈率、差异率指标）。」
 *
 * <p>界面纪律（每一条都对应一个会被误显示的坑）：
 *
 * <ul>
 *   <li>**「归因」和「批准」都不改余额**，只有「入账」那一步改。所以每一步之后都要把这句话
 *       说出来：差异处理进行到哪一步、余额到底动没动。处理人最怕的是以为「已经处理完了」。
 *   <li>**差异是只读的**。界面上没有任何地方能改差异数或改调整量 —— 它们分别由
 *       实盘减账面、以及归因那一刻的差异算出。
 *   <li>**忽略/复盘不是「已解决」**。它们各有各的后果（终态留痕 / 回到未录），
 *       处置列必须显示出来，不能让两行看起来一样。
 *   <li>**率为 null 时显示「不可计算（分母为 0）」**，不画 0%：账面合计为 0 的一盘
 *       与「没有差异」的一盘是两件事。
 *   <li>**金额口径算不出来**要显示给用户（`amountBasisNote`），而不是让人以为系统只会按数量算。
 * </ul>
 */
export function StocktakeVarianceWorkspace() {
  const { session } = useIdentity();
  const readable = session.permissions.includes('warehouse.inventory.read');
  const writable = session.permissions.includes('warehouse.inventory.update');

  const [capabilities, setCapabilities] = useState<StocktakeVarianceCapabilities | null>(null);
  const [sheets, setSheets] = useState<StocktakeSheetPage | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');

  const [sheet, setSheet] = useState<StocktakeSheet | null>(null);
  const [lines, setLines] = useState<StocktakeVarianceLinePage | null>(null);
  const [report, setReport] = useState<StocktakeVarianceReport | null>(null);
  const [audit, setAudit] = useState<StocktakeVarianceAuditEntry[]>([]);
  const [adjustments, setAdjustments] = useState<StocktakeVarianceAdjustmentPage | null>(null);
  const [openAdjustment, setOpenAdjustment] = useState<StocktakeVarianceAdjustmentDetail | null>(null);
  const [drillLoading, setDrillLoading] = useState(false);
  const [saving, setSaving] = useState(false);

  const [lineFilter, setLineFilter] = useState('ALL');
  const [lineKeyword, setLineKeyword] = useState('');
  const [linePage, setLinePage] = useState(0);
  const [adjustmentStatus, setAdjustmentStatus] = useState('');
  const [adjustmentPage, setAdjustmentPage] = useState(0);

  // 归因草稿
  const [draftLineId, setDraftLineId] = useState<string | null>(null);
  const [draftReason, setDraftReason] = useState('');
  const [draftDecision, setDraftDecision] = useState('ADJUST');
  const [draftNote, setDraftNote] = useState('');

  // 生成调整单
  const [selected, setSelected] = useState<string[]>([]);
  const [allAttributed, setAllAttributed] = useState(false);
  const [adjustNote, setAdjustNote] = useState('');
  const [decisionNote, setDecisionNote] = useState('');

  const load = useCallback(async () => {
    setLoading(true);
    setError('');
    try {
      const [caps, sheetPage] = await Promise.all([
        fetchVarianceCapabilities(),
        fetchStocktakes({ size: 20 }),
      ]);
      setCapabilities(caps);
      setSheets(sheetPage);
    } catch (e) {
      setError(errorText(e));
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    if (!readable) return;
    void load();
  }, [readable, load]);

  /** 一次把差异明细、报告、审计、调整单全拉回来：四处数据同源，缺一处就会自相矛盾。 */
  const openSheet = useCallback(
    async (id: string) => {
      setError('');
      setDrillLoading(true);
      try {
        const [lineResult, rep, trail, adjPage] = await Promise.all([
          fetchVarianceLines(id, { filter: lineFilter, keyword: lineKeyword, page: linePage, size: 50 }),
          fetchVarianceReport(id),
          fetchVarianceAudit(id, 200),
          fetchVarianceAdjustments({ sheetId: id, status: adjustmentStatus, page: adjustmentPage, size: 20 }),
        ]);
        setLines(lineResult);
        setReport(rep);
        setAudit(trail);
        setAdjustments(adjPage);
      } catch (e) {
        setError(errorText(e));
      } finally {
        setDrillLoading(false);
      }
    },
    [lineFilter, lineKeyword, linePage, adjustmentStatus, adjustmentPage],
  );

  const openStocktake = useCallback(
    async (id: string) => {
      setError('');
      setNotice('');
      setSelected([]);
      setDraftLineId(null);
      setDraftReason('');
      setDraftNote('');
      setOpenAdjustment(null);
      const found = sheets?.sheets.find((s) => s.id === id) ?? null;
      setSheet(found);
      await openSheet(id);
    },
    [sheets, openSheet],
  );

  // 筛选变了就把当前单重新拉一遍（而不是让表格停在旧筛选结果上）。
  useEffect(() => {
    if (!sheet) return;
    void openSheet(sheet.id);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [lineFilter, lineKeyword, linePage, adjustmentStatus, adjustmentPage]);

  const afterMutation = useCallback(
    async (message: string) => {
      setNotice(message);
      if (sheet) await openSheet(sheet.id);
    },
    [sheet, openSheet],
  );

  const submitAttribution = useCallback(async () => {
    if (!sheet || !draftLineId) return;
    const problem = attributionProblem(draftReason, draftDecision, draftNote);
    if (problem) {
      setError(problem);
      return;
    }
    setSaving(true);
    setError('');
    try {
      await attributeVarianceLine(sheet.id, draftLineId, {
        reason: draftReason,
        decision: draftDecision,
        note: draftNote.trim(),
      });
      const consequence =
        draftDecision === 'ADJUST'
          ? '已归因。差异仍未改动库存余额 —— 它要先进调整单、经审批，再由「入账」那一步才动账。'
          : draftDecision === 'RECOUNT'
            ? '已复盘：这一行的实盘数退回未录，要重新去数一次。账面数没有被改动。'
            : '已忽略：这条差异永远不入账，但谁在什么时候、为什么放过都留了痕，且不能再被改成调整。';
      setDraftLineId(null);
      setDraftReason('');
      setDraftNote('');
      await afterMutation(consequence);
    } catch (e) {
      setError(errorText(e));
    } finally {
      setSaving(false);
    }
  }, [sheet, draftLineId, draftReason, draftDecision, draftNote, afterMutation]);

  const runCreateAdjustment = useCallback(async () => {
    if (!sheet) return;
    const problem = createAdjustmentProblem(allAttributed, selected);
    if (problem) {
      setError(problem);
      return;
    }
    setSaving(true);
    setError('');
    try {
      const created = await createVarianceAdjustment(sheet.id, {
        ...(allAttributed ? { allAttributed: true } : { lineIds: selected }),
        ...(adjustNote.trim() ? { note: adjustNote.trim() } : {}),
      });
      setSelected([]);
      setAdjustNote('');
      setOpenAdjustment(created);
      await afterMutation(
        `已生成调整单 ${created.adjustment.code}（草稿），收进 ${created.adjustment.lineCount} 行差异。此刻库存余额一个数都没动。`,
      );
    } catch (e) {
      setError(errorText(e));
    } finally {
      setSaving(false);
    }
  }, [sheet, allAttributed, selected, adjustNote, afterMutation]);

  const actOnAdjustment = useCallback(
    async (action: 'submit' | 'approve' | 'reject' | 'cancel' | 'post', id: string) => {
      setSaving(true);
      setError('');
      try {
        let message = '';
        if (action === 'submit') {
          await submitVarianceAdjustment(id, decisionNote.trim() || undefined);
          message = '已提交审批。提交人不能审批自己提的单 —— 需要另一位有权限的同事批准。';
        } else if (action === 'approve') {
          await approveVarianceAdjustment(id, decisionNote.trim() || undefined);
          message = '已批准。注意：批准只是允许入账，库存余额此刻仍未改动，还要再走「入账」。';
        } else if (action === 'reject') {
          if (!decisionNote.trim()) {
            setError('驳回必须写意见：只给一个「已驳回」，提交的人无法知道该改什么。');
            return;
          }
          await rejectVarianceAdjustment(id, decisionNote.trim());
          message = '已驳回。相关差异行已释放，可以重新归因后再次生成调整单。';
        } else if (action === 'cancel') {
          await cancelVarianceAdjustment(id, decisionNote.trim() || undefined);
          message = '已作废。相关差异行已释放。';
        } else {
          const posted = await postVarianceAdjustment(id, decisionNote.trim() || undefined);
          message = `${posted.note} 盘点差异至此才真正改变了库存余额。`;
          setOpenAdjustment(null);
        }
        setDecisionNote('');
        await afterMutation(message);
      } catch (e) {
        setError(errorText(e));
      } finally {
        setSaving(false);
      }
    },
    [decisionNote, afterMutation],
  );

  const openAdjustmentDetail = useCallback(async (id: string) => {
    setError('');
    setDrillLoading(true);
    try {
      setOpenAdjustment(await fetchVarianceAdjustment(id));
    } catch (e) {
      setError(errorText(e));
    } finally {
      setDrillLoading(false);
    }
  }, []);

  const toggleSelected = useCallback((id: string) => {
    setSelected((prev) => (prev.includes(id) ? prev.filter((x) => x !== id) : [...prev, id]));
  }, []);

  const selectable = useMemo(
    () => (lines?.lines ?? []).filter((l) => l.varianceDecision === 'ADJUST' && !l.postedAt),
    [lines],
  );

  const metrics = report?.metrics;

  return (
    <div className="space-y-4">
      <PageHeader
        title="盘点差异处理"
        description="按差异原因分类、生成调整单走审批、盘盈盘亏入账并可追责。差异不直接改余额 —— 必须先生成调整单并经审批。"
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

      {/* ---------------- 指标 ---------------- */}
      {metrics && (
        <div className="grid gap-4 md:grid-cols-4">
          <StatCard
            label="差异行"
            value={progressText(metrics.varianceLineCount, metrics.countedLineCount)}
            polarity={metrics.varianceLineCount > 0 ? 'negative' : 'positive'}
            comparisonLabel={`已盘 ${metrics.countedLineCount} 行 / 共 ${metrics.lineCount} 行`}
          />
          <StatCard
            label="行差异率"
            value={rateText(metrics.lineVarianceRate)}
            polarity={rateTone(metrics.lineVarianceRate) === 'bad' ? 'negative' : 'positive'}
            comparisonLabel="有差异行数 ÷ 已盘行数"
          />
          <StatCard
            label="盘盈率"
            value={rateText(metrics.gainRate)}
            polarity={rateTone(metrics.gainRate) === 'bad' ? 'negative' : 'positive'}
            comparisonLabel={`盘盈 ${qty(metrics.gainQty)} ÷ 账面合计 ${qty(metrics.bookTotal)}`}
          />
          <StatCard
            label="归因完成度"
            value={progressText(metrics.attributedLineCount, metrics.varianceLineCount)}
            polarity={metrics.openLineCount > 0 ? 'neutral' : 'positive'}
            comparisonLabel={`进度 ${progressPercent(metrics.attributedLineCount, metrics.varianceLineCount)}%`}
          />
        </div>
      )}

      {/* ---------------- 选盘点单 ---------------- */}
      <SectionCard
        title="选择盘点单"
        description="差异三列由 7.4.2.1 冻结的账面数算出，本模块只处理「处置」，不重算差异。"
      >
        {sheets === null || sheets.sheets.length === 0 ? (
          <EmptyState
            icon={<ClipboardList className="h-5 w-5" />}
            title={loading ? '加载中' : '还没有盘点单'}
            description={loading ? '正在读取盘点单列表。' : '先在「盘点单」里发起一次盘点并录入实盘数。'}
          />
        ) : (
          <div className="flex flex-wrap gap-2">
            {sheets.sheets.map((s) => (
              <Button
                key={s.id}
                variant={sheet?.id === s.id ? 'default' : 'outline'}
                size="sm"
                onClick={() => void openStocktake(s.id)}
              >
                {s.code} · {s.scopeLabel} · 差异 {s.varianceLineCount} 行
              </Button>
            ))}
          </div>
        )}
      </SectionCard>

      {sheet && (
        <>
          {/* ---------------- 差异处理 ---------------- */}
          <SectionCard
            title={`差异处理 · ${sheet.code}`}
            description={`冻结于 ${formatMoment(sheet.frozenAt)} · 账面合计 ${qty(sheet.bookTotal)}（冻结值，不是今天的库存余额）。归因只是记录决定，不改余额。`}
            extra={
              <div className="flex flex-wrap gap-2">
                <Input
                  className="max-w-xs"
                  placeholder="搜 SKU / 库位 / 批次 / 条码 / 归因说明"
                  value={lineKeyword}
                  onChange={(e) => {
                    setLineKeyword(e.target.value);
                    setLinePage(0);
                  }}
                />
                <select
                  className="border-control-border bg-background h-9 rounded-md border px-2 text-sm"
                  value={lineFilter}
                  onChange={(e) => {
                    setLineFilter(e.target.value);
                    setLinePage(0);
                  }}
                >
                  {lineFilterOptions.map((o) => (
                    <option key={o.value} value={o.value}>
                      {o.label}
                    </option>
                  ))}
                </select>
              </div>
            }
          >
            {lines === null || lines.lines.length === 0 ? (
              <EmptyState
                icon={<ClipboardCheck className="h-5 w-5" />}
                title={drillLoading ? '加载中' : lineFilter === 'ALL' ? '这张单没有差异' : '该筛选下没有差异行'}
                description={
                  drillLoading
                    ? '正在读取差异明细。'
                    : lineFilter === 'ALL'
                      ? '账实全部相符，或实盘还没录完 —— 这两种情况都不会出现在差异工作台里。'
                      : '换个筛选看全部差异。'
                }
              />
            ) : (
              <table className="w-full text-sm">
                <thead className="text-muted-foreground text-left text-xs">
                  <tr>
                    <th className="py-2">商品 / 库位</th>
                    <th className="py-2 text-right">账面</th>
                    <th className="py-2 text-right">实盘</th>
                    <th className="py-2 text-right">差异</th>
                    <th className="py-2">处置</th>
                    <th className="py-2">归因</th>
                    <th className="py-2" />
                  </tr>
                </thead>
                <tbody>
                  {lines.lines.map((line) => (
                    <tr key={line.id} className="border-control-border border-t">
                      <td className="py-2">
                        <div className="font-medium">
                          {line.skuCode} {line.skuName}
                        </div>
                        <div className="text-muted-foreground text-xs">
                          {line.warehouseCode}/{line.locationCode}
                          {line.lotCode ? ` · ${line.lotCode}` : ''} · {line.qualityStatusCode}
                        </div>
                      </td>
                      <td className="py-2 text-right tabular-nums">{qty(line.bookOnHand)}</td>
                      <td className="py-2 text-right tabular-nums">{qty(line.countedQty)}</td>
                      <td className="py-2 text-right tabular-nums">
                        <span
                          className={
                            (line.variance ?? 0) < 0
                              ? 'text-destructive font-medium'
                              : (line.variance ?? 0) > 0
                                ? 'text-amber-600 dark:text-amber-400 font-medium'
                                : ''
                          }
                        >
                          {varianceText(line.variance)}
                        </span>
                      </td>
                      <td className="py-2 text-xs">
                        <div
                          className={
                            line.postedAt
                              ? 'text-emerald-600 dark:text-emerald-400'
                              : line.varianceDecision === 'IGNORE'
                                ? 'text-muted-foreground'
                                : ''
                          }
                        >
                          {dispositionText(line)}
                        </div>
                        {line.postedAt && (
                          <div className="text-muted-foreground text-xs">
                            {formatMoment(line.postedAt)}
                          </div>
                        )}
                      </td>
                      <td className="py-2 text-xs">
                        {line.varianceReason ? (
                          <>
                            <div className="font-medium">{reasonLabels[line.varianceReason]}</div>
                            <div className="text-muted-foreground max-w-xs">{line.varianceNote}</div>
                            <div className="text-muted-foreground text-xs">
                              {formatMoment(line.attributedAt)}
                            </div>
                          </>
                        ) : (
                          <span className="text-muted-foreground">未归因</span>
                        )}
                      </td>
                      <td className="py-2 text-right">
                        {!line.postedAt && !line.varianceDecision && (
                          <Button
                            size="sm"
                            variant="outline"
                            disabled={!writable}
                            onClick={() => {
                              setDraftLineId(line.id);
                              setDraftReason('');
                              setDraftDecision('ADJUST');
                              setDraftNote('');
                            }}
                          >
                            归因
                          </Button>
                        )}
                        {!line.postedAt &&
                          line.varianceDecision === 'ADJUST' &&
                          !line.adjustmentStatus && (
                            <Button
                              size="sm"
                              variant="outline"
                              onClick={() => setSelected([line.id])}
                              disabled={!writable}
                            >
                              选中建单
                            </Button>
                          )}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
            {lines && lines.total > lines.size && (
              <div className="mt-3 flex items-center gap-2">
                <Button
                  size="sm"
                  variant="outline"
                  disabled={linePage === 0}
                  onClick={() => setLinePage((p) => Math.max(0, p - 1))}
                >
                  上一页
                </Button>
                <span className="text-muted-foreground text-xs">
                  第 {linePage + 1} 页 · 共 {lines.total} 行
                </span>
                <Button
                  size="sm"
                  variant="outline"
                  disabled={(linePage + 1) * lines.size >= lines.total}
                  onClick={() => setLinePage((p) => p + 1)}
                >
                  下一页
                </Button>
              </div>
            )}
          </SectionCard>

          {/* ---------------- 归因表单 ---------------- */}
          {draftLineId && (
            <SectionCard
              title="差异归因"
              description="五类原因 × 三种决定。「调整」进调整单、「复盘」退回未录、「忽略」留痕且不可翻案 —— 三者都不会立刻改余额。"
            >
              <div className="grid gap-3 md:grid-cols-3">
                <FormField id="variance-reason" label="差异原因" required>
                  <select
                    id="variance-reason"
                    className="border-control-border bg-background h-9 w-full rounded-md border px-2 text-sm"
                    value={draftReason}
                    onChange={(e) => setDraftReason(e.target.value)}
                    disabled={!writable}
                  >
                    <option value="">请选择</option>
                    {reasonOptions.map((o) => (
                      <option key={o.value} value={o.value}>
                        {o.label}
                      </option>
                    ))}
                  </select>
                </FormField>
                <FormField id="variance-decision" label="处理决定" required>
                  <select
                    id="variance-decision"
                    className="border-control-border bg-background h-9 w-full rounded-md border px-2 text-sm"
                    value={draftDecision}
                    onChange={(e) => setDraftDecision(e.target.value)}
                    disabled={!writable}
                  >
                    {decisionOptions.map((o) => (
                      <option key={o.value} value={o.value}>
                        {o.label}
                      </option>
                    ))}
                  </select>
                </FormField>
                <FormField
                  id="variance-note"
                  label="判断依据"
                  required={draftDecision === 'IGNORE'}
                  {...(draftDecision === 'IGNORE'
                    ? { hint: '忽略至少 4 个字：它让这条差异永远不入账' }
                    : {})}
                >
                  <Input
                    id="variance-note"
                    value={draftNote}
                    onChange={(e) => setDraftNote(e.target.value)}
                    placeholder="例如：搬运破损两件 / 怀疑库位串了 / 冻结之后正常发运走了"
                    disabled={!writable}
                  />
                </FormField>
              </div>
              {capabilities && (
                <p className="text-muted-foreground mt-2 text-xs">
                  {reasonOptions
                    .map((o) => {
                      const cap = capabilities.reasons.find((r) => r.value === o.value);
                      return cap ? `${o.label}：${cap.description}` : '';
                    })
                    .filter(Boolean)
                    .join('；')}
                </p>
              )}
              <div className="mt-3 flex gap-2">
                <Button onClick={() => void submitAttribution()} disabled={!writable || saving}>
                  {saving ? '提交中…' : '提交归因'}
                </Button>
                <Button
                  variant="outline"
                  onClick={() => {
                    setDraftLineId(null);
                    setError('');
                  }}
                >
                  取消
                </Button>
              </div>
            </SectionCard>
          )}

          {/* ---------------- 调整单 ---------------- */}
          <SectionCard
            title="调整单"
            description="调整单只是「谁、因为什么、要改多少」的凭据。它不动余额；余额只在「入账」那一步、且必须已审批。"
            extra={
              <div className="flex items-center gap-2">
                <select
                  className="border-control-border bg-background h-9 rounded-md border px-2 text-sm"
                  value={adjustmentStatus}
                  onChange={(e) => {
                    setAdjustmentStatus(e.target.value);
                    setAdjustmentPage(0);
                  }}
                >
                  <option value="">全部状态</option>
                  {adjustmentStatusOptions.map((o) => (
                    <option key={o.value} value={o.value}>
                      {o.label}
                    </option>
                  ))}
                </select>
              </div>
            }
          >
            {selectable.length > 0 && (
              <div className="mb-3 flex flex-wrap items-end gap-3">
                <FormField
                  id="adjust-note"
                  label="单据说明"
                  {...{ hint: '调整量的来源是归因那一刻的差异，不接受手工填' }}
                >
                  <Input
                    id="adjust-note"
                    className="max-w-sm"
                    value={adjustNote}
                    onChange={(e) => setAdjustNote(e.target.value)}
                    placeholder="例如：A 仓月末盘点损耗"
                    disabled={!writable}
                  />
                </FormField>
                <label className="text-muted-foreground flex items-center gap-2 text-xs">
                  <input
                    type="checkbox"
                    checked={allAttributed}
                    onChange={(e) => setAllAttributed(e.target.checked)}
                    disabled={!writable}
                  />
                  收走全部已归因为调整的行
                </label>
                <Button onClick={() => void runCreateAdjustment()} disabled={!writable || saving}>
                  {saving ? '生成中…' : '生成调整单'}
                </Button>
                <span className="text-muted-foreground text-xs">已选 {selected.length} 行</span>
              </div>
            )}

            {adjustments === null || adjustments.adjustments.length === 0 ? (
              <EmptyState
                icon={<FileWarning className="h-5 w-5" />}
                title={drillLoading ? '加载中' : '还没有调整单'}
                description={
                  drillLoading
                    ? '正在读取调整单。'
                    : '先把差异归因为「调整」，再勾选行生成调整单。归因本身不改余额。'
                }
              />
            ) : (
              <table className="w-full text-sm">
                <thead className="text-muted-foreground text-left text-xs">
                  <tr>
                    <th className="py-2">单号</th>
                    <th className="py-2">状态</th>
                    <th className="py-2 text-right">行数</th>
                    <th className="py-2 text-right">盘盈 / 盘亏</th>
                    <th className="py-2 text-right">净调整</th>
                    <th className="py-2">轨迹</th>
                    <th className="py-2" />
                  </tr>
                </thead>
                <tbody>
                  {adjustments.adjustments.map((a) => (
                    <tr key={a.id} className="border-control-border border-t">
                      <td className="py-2">
                        <div className="font-medium">{a.code}</div>
                        <div className="text-muted-foreground text-xs">{a.sheetCode}</div>
                      </td>
                      <td className="py-2">
                        <span
                          className={
                            adjustmentTone(a.status) === 'warn'
                              ? 'text-amber-600 dark:text-amber-400'
                              : adjustmentTone(a.status) === 'bad'
                                ? 'text-destructive'
                                : 'text-emerald-600 dark:text-emerald-400'
                          }
                        >
                          {adjustmentStatusLabels[a.status] ?? a.status}
                        </span>
                      </td>
                      <td className="py-2 text-right tabular-nums">{a.lineCount}</td>
                      <td className="py-2 text-right tabular-nums">
                        {qty(a.gainQty)} / {qty(a.lossQty)}
                      </td>
                      <td className="py-2 text-right tabular-nums">{qty(a.netQty)}</td>
                      <td className="text-muted-foreground py-2 text-xs">
                        {a.decidedAt ? `审批 ${formatMoment(a.decidedAt)}` : '—'}
                        {a.postedAt ? ` · 入账 ${formatMoment(a.postedAt)}` : ''}
                      </td>
                      <td className="py-2 text-right">
                        <Button
                          size="sm"
                          variant="outline"
                          onClick={() => void openAdjustmentDetail(a.id)}
                        >
                          详情
                        </Button>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
          </SectionCard>

          {/* ---------------- 调整单详情 + 审批动作 ---------------- */}
          {openAdjustment && (
            <SectionCard
              title={`调整单 ${openAdjustment.adjustment.code}`}
              description={`盘盈 ${qty(openAdjustment.adjustment.gainQty)} · 盘亏 ${qty(openAdjustment.adjustment.lossQty)} · 净调整 ${qty(openAdjustment.adjustment.netQty)}`}
            >
              <table className="w-full text-sm">
                <thead className="text-muted-foreground text-left text-xs">
                  <tr>
                    <th className="py-2">商品 / 库位</th>
                    <th className="py-2 text-right">调整量</th>
                    <th className="py-2">原因</th>
                    <th className="py-2">入账</th>
                  </tr>
                </thead>
                <tbody>
                  {openAdjustment.lines.map((l) => (
                    <tr key={l.id} className="border-control-border border-t">
                      <td className="py-2">
                        <div className="font-medium">
                          {l.skuCode} {l.skuName}
                        </div>
                        <div className="text-muted-foreground text-xs">
                          {l.warehouseCode}/{l.locationCode}
                          {l.lotCode ? ` · ${l.lotCode}` : ''}
                        </div>
                      </td>
                      <td className="py-2 text-right tabular-nums">{varianceText(l.qtyDelta)}</td>
                      <td className="py-2 text-xs">{l.note || '—'}</td>
                      <td className="text-muted-foreground py-2 text-xs">
                        {l.postedAt ? formatMoment(l.postedAt) : '未入账'}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
              <div className="mt-3 grid gap-3 md:grid-cols-2">
                <FormField
                  id="adjustment-decision-note"
                  label="审批意见 / 入账说明"
                  {...{ hint: '驳回时必填；入账时留空则用单据说明' }}
                >
                  <Input
                    id="adjustment-decision-note"
                    value={decisionNote}
                    onChange={(e) => setDecisionNote(e.target.value)}
                    disabled={!writable}
                  />
                </FormField>
                <div className="flex flex-wrap items-end gap-2">
                  {openAdjustment.adjustment.status === 'DRAFT' && (
                    <>
                      <Button onClick={() => void actOnAdjustment('submit', openAdjustment.adjustment.id)} disabled={!writable || saving}>
                        提交审批
                      </Button>
                      <Button
                        variant="outline"
                        onClick={() => void actOnAdjustment('cancel', openAdjustment.adjustment.id)}
                        disabled={!writable || saving}
                      >
                        作废
                      </Button>
                    </>
                  )}
                  {openAdjustment.adjustment.status === 'PENDING_APPROVAL' && (
                    <>
                      <Button onClick={() => void actOnAdjustment('approve', openAdjustment.adjustment.id)} disabled={!writable || saving}>
                        批准
                      </Button>
                      <Button
                        variant="outline"
                        onClick={() => void actOnAdjustment('reject', openAdjustment.adjustment.id)}
                        disabled={!writable || saving}
                      >
                        驳回
                      </Button>
                    </>
                  )}
                  {openAdjustment.adjustment.status === 'APPROVED' && (
                    <>
                      <Button onClick={() => void actOnAdjustment('post', openAdjustment.adjustment.id)} disabled={!writable || saving}>
                        入账（改动库存余额）
                      </Button>
                      <Button
                        variant="outline"
                        onClick={() => void actOnAdjustment('cancel', openAdjustment.adjustment.id)}
                        disabled={!writable || saving}
                      >
                        作废
                      </Button>
                    </>
                  )}
                  {openAdjustment.adjustment.status === 'POSTED' && (
                    <p className="text-muted-foreground text-xs">
                      已于 {formatMoment(openAdjustment.adjustment.postedAt)} 入账，余额已改动。
                      已过账的单据不能作废 —— 冲正请发起新的调整单。
                    </p>
                  )}
                  {(openAdjustment.adjustment.status === 'REJECTED' ||
                    openAdjustment.adjustment.status === 'CANCELLED') && (
                    <p className="text-muted-foreground text-xs">
                      这张单已终止，相关差异行已释放，可以重新归因后再生成调整单。
                    </p>
                  )}
                </div>
              </div>
            </SectionCard>
          )}

          {/* ---------------- 完成报告 ---------------- */}
          {report && (
            <SectionCard title="盘点完成报告" description={report.rateBasis}>
              <div className="grid gap-3 md:grid-cols-2">
                <table className="w-full text-sm">
                  <thead className="text-muted-foreground text-left text-xs">
                    <tr>
                      <th className="py-2">差异原因</th>
                      <th className="py-2 text-right">行数</th>
                      <th className="py-2 text-right">盘盈 / 盘亏</th>
                      <th className="py-2 text-right">净</th>
                      <th className="py-2 text-right">调整中 / 已入账</th>
                    </tr>
                  </thead>
                  <tbody>
                    {report.byReason.map((r) => (
                      <tr key={r.reason} className="border-control-border border-t">
                        <td className="py-2">{r.label}</td>
                        <td className="py-2 text-right tabular-nums">{r.lineCount}</td>
                        <td className="py-2 text-right tabular-nums">
                          {qty(r.gainQty)} / {qty(r.lossQty)}
                        </td>
                        <td className="py-2 text-right tabular-nums">{qty(r.netQty)}</td>
                        <td className="py-2 text-right tabular-nums">
                          {r.inAdjustmentCount} / {r.postedCount}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
                <table className="w-full text-sm">
                  <thead className="text-muted-foreground text-left text-xs">
                    <tr>
                      <th className="py-2">处理决定</th>
                      <th className="py-2 text-right">做过</th>
                      <th className="py-2 text-right">未结</th>
                    </tr>
                  </thead>
                  <tbody>
                    {report.byDecision.map((d) => (
                      <tr key={d.decision} className="border-control-border border-t">
                        <td className="py-2">{d.label}</td>
                        <td className="py-2 text-right tabular-nums">{d.lineCount}</td>
                        <td className="py-2 text-right tabular-nums">{d.openLineCount}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
              <div className="mt-3 grid gap-3 md:grid-cols-3">
                <StatCard
                  label="数量差异率"
                  value={rateText(metrics?.qtyVarianceRate)}
                  polarity={rateTone(metrics?.qtyVarianceRate) === 'bad' ? 'negative' : 'positive'}
                  comparisonLabel="(盘盈 + 盘亏) ÷ 账面合计"
                />
                <StatCard
                  label="盘亏率"
                  value={rateText(metrics?.lossRate)}
                  polarity={rateTone(metrics?.lossRate) === 'bad' ? 'negative' : 'positive'}
                  comparisonLabel={`盘亏 ${qty(metrics?.lossQty)} ÷ 账面合计`}
                />
                <StatCard
                  label="已入账 / 待处理调整单"
                  value={`${metrics?.postedLineCount ?? 0} / ${metrics?.pendingAdjustmentCount ?? 0}`}
                  polarity="neutral"
                  comparisonLabel="已过账的差异行数 / 还在流程里的单据数"
                />
              </div>
              {capabilities && (
                <div className="mt-3 space-y-2">
                  <Notice tone="warning" title="金额口径的率算不出来">
                    {capabilities.amountBasisNote}
                  </Notice>
                  <Notice tone="info" title="审批用的是替代机制">
                    {capabilities.approvalNote}
                  </Notice>
                  <Notice tone="info" title="调整单实体与 7.4.1.1 的关系">
                    {capabilities.adjustmentHandoffNote}
                  </Notice>
                </div>
              )}
            </SectionCard>
          )}

          {/* ---------------- 审计 ---------------- */}
          <SectionCard
            title="差异处理审计"
            description="谁归因、谁提交、谁审批、谁入账、什么时候 —— append-only，界面上没有任何删除入口。"
          >
            {audit.length === 0 ? (
              <EmptyState
                icon={<ScrollText className="h-5 w-5" />}
                title={drillLoading ? '加载中' : '还没有处理记录'}
                description="一旦开始归因，这里会按时间顺序留下每一步。"
              />
            ) : (
              <ol className="space-y-2">
                {audit.map((e) => (
                  <li key={e.id} className="border-control-border border-l-2 pl-3 text-sm">
                    <div className="text-muted-foreground text-xs">
                      {formatMoment(e.occurredAt)} · {e.actionLabel}
                    </div>
                    <div>{e.detail}</div>
                  </li>
                ))}
              </ol>
            )}
          </SectionCard>
        </>
      )}
    </div>
  );
}
