'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import { Badge, Button, EmptyState, SectionCard, StatCard } from '@starcon/ui';
import { RefreshCw, Save, Trash2, TriangleAlert } from 'lucide-react';
import type {
  ZombieCandidate,
  ZombieCandidateOverview,
  ZombieCleanupRuleInput,
  ZombieCleanupRuleView,
  ZombieCleanupRecord,
  ZombieOptions,
} from '../../../../../packages/api-contracts/src/index';
import { Field, control } from '../common/fields';
import {
  DIMENSION_STATE_LABELS,
  ENTITY_TYPE_LABELS,
  VERDICT_ACTIONS,
  VERDICT_LABELS,
  batchBlockReason,
  blockedLines,
  candidateHeadline,
  deactivateZombies,
  dimensionObservedText,
  dimensionValueText,
  emptyCandidateListQuery,
  fetchCleanupRecords,
  fetchCleanupRules,
  fetchZombieCandidates,
  fetchZombieOverview,
  fetchZombieOptions,
  inactiveDaysProblem,
  isDimensionRow,
  isUnknown,
  maintenanceText,
  requiredDimensionCount,
  requiredDimensionText,
  ruleProblem,
  saveCleanupRule,
  selectable,
  staleDaysProblem,
  toRuleForm,
  zombieErrorText,
  type CandidateListQuery,
  type Verdict,
} from './zombie-candidate-model';

/**
 * 3.9.2.2 僵尸主数据清理工作区。
 *
 * <p>界面上最重要的一件事：**「无法判定」必须能被一眼看见，且绝不能被当成「在用」**。
 *
 * <ul>
 *   <li>三态各有徽标与不同的下一步动作。「在用」是终态说明（这条有人用），
 *       「无法判定」是待办（上游没接 / 快照太旧）—— 混成一句话的话，
 *       运营只会当成"系统不让我停"，然后放弃治理。
 *   <li>每行可展开逐维证据：四个引用维度各自的取值、观测时刻、是否过期。
 *       「未观测」与「观测到 0」在界面上必须不同 —— 那是整个模块的地基。
 *   <li>未接入上游的维度，其开关**禁用并标注归属服务**：让运营知道差的是哪个事件，
 *       而不是以为自己没保存成功。
 *   <li>批量停用按钮在勾选里混有不可停用条目时给出逐条原因；
 *       服务端整批拒绝时（Z4）也逐条显示 —— 界面不替服务端"跳过"。
 * </ul>
 *
 * <p>加载态 / 空态 / 错误态三者分开渲染：清单为空是正常状态（还没有僵尸），
 * 与"加载失败"不能共用一个界面 —— 后者必须让人一眼看出要重试。
 */
export function ZombieCleanupWorkspace({ writable }: { writable: boolean }) {
  const [overview, setOverview] = useState<ZombieCandidateOverview | null>(null);
  const [options, setOptions] = useState<ZombieOptions | null>(null);
  const [rules, setRules] = useState<ZombieCleanupRuleView[]>([]);
  const [rows, setRows] = useState<ZombieCandidate[]>([]);
  const [total, setTotal] = useState(0);
  const [counts, setCounts] = useState({ candidate: 0, inUse: 0, incomplete: 0 });
  const [records, setRecords] = useState<ZombieCleanupRecord[]>([]);
  const [query, setQuery] = useState<CandidateListQuery>(emptyCandidateListQuery());
  const [selected, setSelected] = useState<string[]>([]);
  const [expanded, setExpanded] = useState<string | null>(null);
  const [draft, setDraft] = useState<ZombieCleanupRuleInput | null>(null);
  const [draftType, setDraftType] = useState<string>('SKU');
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [blocked, setBlocked] = useState<string[]>([]);
  const [reason, setReason] = useState('');
  const [notice, setNotice] = useState<string | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const [ov, opts, ruleViews, page, recordPage] = await Promise.all([
        fetchZombieOverview(),
        fetchZombieOptions(),
        fetchCleanupRules(),
        fetchZombieCandidates(query),
        fetchCleanupRecords({ entityType: query.entityType, size: 10 }),
      ]);
      setOverview(ov);
      setOptions(opts);
      setRules(ruleViews);
      setRows(page.items);
      setTotal(page.total);
      setCounts({
        candidate: page.candidateCount,
        inUse: page.inUseCount,
        incomplete: page.incompleteCount,
      });
      setRecords(recordPage.items);
    } catch (e) {
      setError(zombieErrorText(e));
    } finally {
      setLoading(false);
    }
  }, [query]);

  useEffect(() => {
    void load();
  }, [load]);

  const patch = (next: Partial<CandidateListQuery>) =>
    setQuery((current) => ({ ...current, ...next, page: 0 }));

  const pages = Math.max(1, Math.ceil(total / query.size));
  const selectedRows = useMemo(
    () => rows.filter((row) => selected.includes(row.entityId)),
    [rows, selected],
  );
  const blockReason = batchBlockReason(selectedRows);
  const wiredMetrics = useMemo(
    () => new Map((options?.metrics ?? []).map((metric) => [metric.code, metric])),
    [options],
  );

  const editRule = (view: ZombieCleanupRuleView) => {
    setDraftType(view.entityType);
    setDraft(toRuleForm(view));
    setError(null);
  };

  const submitRule = async () => {
    if (!draft) return;
    const problem = ruleProblem(draft);
    if (problem) {
      setError(problem);
      return;
    }
    setError(null);
    try {
      await saveCleanupRule(draftType, draft);
      setNotice(`已保存「${ENTITY_TYPE_LABELS[draftType] ?? draftType}」的清理规则。`);
      setDraft(null);
      await load();
    } catch (e) {
      setError(zombieErrorText(e));
    }
  };

  const runDeactivate = async () => {
    if (blockReason || selected.length === 0) return;
    setError(null);
    setBlocked([]);
    setNotice(null);
    try {
      const result = await deactivateZombies(query.entityType, selected, reason.trim());
      setNotice(
        `已停用 ${result.affected} 条${ENTITY_TYPE_LABELS[query.entityType] ?? ''}主数据` +
          `（状态改为 ${result.entries[0]?.newStatus ?? 'INACTIVE'}，只停用不删除，历史单据仍可展示）。`,
      );
      setSelected([]);
      await load();
    } catch (e) {
      const lines = blockedLines(e);
      setBlocked(lines);
      setError(zombieErrorText(e));
    }
  };

  const activeRule = rules.find((rule) => rule.entityType === query.entityType);

  return (
    <SectionCard
      title="僵尸主数据清理（3.9.2.2）"
      description="识别从未被订单引用、无库存、无 listing、成本为 0、长期未维护的 SKU / 供应商 / 客户，输出候选并支持批量停用。停用而非删除：主数据被引用后只能停用，历史单据仍可展示。"
      extra={
        <Button size="sm" variant="ghost" disabled={loading} onClick={() => void load()}>
          <RefreshCw className="h-4 w-4" />
          刷新
        </Button>
      }
    >
      {overview && (
        <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
          <StatCard label="活跃主数据" value={overview.rows[0]?.totalActive ?? 0} unit="条" />
          <StatCard label="僵尸候选" value={overview.rows[0]?.candidates ?? 0} unit="条" />
          <StatCard label="无法判定" value={overview.rows[0]?.incomplete ?? 0} unit="条" />
          <StatCard
            label="有观测的主数据"
            value={overview.coveredEntities}
            unit="条"
            polarity="neutral"
          />
        </div>
      )}

      {overview && overview.unmappedDimensions.length > 0 && (
        <div className="border-warning text-warning flex items-start gap-2 rounded-md border px-3 py-2 text-sm">
          <TriangleAlert className="mt-0.5 h-4 w-4 shrink-0" />
          <div>
            <p>以下引用维度的上游服务尚未接入，因此对应开关不可启用：</p>
            <ul className="mt-1 list-disc pl-4">
              {overview.unmappedDimensions.map((item) => (
                <li key={item}>{item}</li>
              ))}
            </ul>
            <p className="text-muted-foreground mt-1">
              这些维度一旦被判为启用而没有观测值，主数据会判「无法判定」而不是「候选」——
              这是有意的：未知不等于零。
            </p>
          </div>
        </div>
      )}

      {/* 规则配置 */}
      <div className="space-y-2">
        <h3 className="text-sm font-medium">清理规则</h3>
        <div className="overflow-x-auto">
          <table className="w-full text-left text-sm">
            <thead className="text-muted-foreground border-b text-xs">
              <tr>
                <th className="py-2 pr-3 font-medium">主体</th>
                <th className="py-2 pr-3 font-medium">维护阈值</th>
                <th className="py-2 pr-3 font-medium">快照过期</th>
                <th className="py-2 pr-3 font-medium">引用维度</th>
                <th className="py-2 pr-3 font-medium">扫描</th>
                <th className="py-2 font-medium">操作</th>
              </tr>
            </thead>
            <tbody>
              {rules.map((rule) => (
                <tr key={rule.entityType} className="border-b last:border-0">
                  <td className="py-2 pr-3">{rule.entityTypeLabel}</td>
                  <td className="py-2 pr-3 text-xs">{rule.inactiveDays} 天未维护</td>
                  <td className="py-2 pr-3 text-xs">{rule.snapshotStaleDays} 天</td>
                  <td className="py-2 pr-3 text-xs">
                    {requiredDimensionText(requiredDimensionCount(rule))}
                  </td>
                  <td className="py-2 pr-3 text-xs">
                    <Badge tone={rule.enabled ? 'success' : 'neutral'}>
                      {rule.enabled ? '参与扫描' : '已停用'}
                    </Badge>
                  </td>
                  <td className="py-2">
                    {writable && (
                      <Button size="sm" variant="ghost" onClick={() => editRule(rule)}>
                        配置
                      </Button>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>

      {draft && (
        <div className="space-y-3 rounded-md border p-3">
          <h4 className="text-sm font-medium">
            配置「{ENTITY_TYPE_LABELS[draftType] ?? draftType}」的清理规则
          </h4>
          <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
            <Field
              label="维护阈值（天）"
              hint={[inactiveDaysProblem(draft.inactiveDays) ?? '距最后一次维护超过这个天数才进入候选']
                .filter(Boolean)
                .join('；')}
            >
              <input
                type="number"
                className={control}
                value={draft.inactiveDays}
                onChange={(e) =>
                  setDraft({ ...draft, inactiveDays: Number(e.target.value) })
                }
              />
            </Field>
            <Field
              label="快照过期（天）"
              hint={[staleDaysProblem(draft.snapshotStaleDays) ?? '观测值超过这个天数即视为未知，不参与判定']
                .filter(Boolean)
                .join('；')}
            >
              <input
                type="number"
                className={control}
                value={draft.snapshotStaleDays}
                onChange={(e) =>
                  setDraft({ ...draft, snapshotStaleDays: Number(e.target.value) })
                }
              />
            </Field>
            <Field label="停用备注" hint="写入停用记录，可按需补充">
              <input
                className={control}
                value={draft.deactivateNote}
                onChange={(e) => setDraft({ ...draft, deactivateNote: e.target.value })}
              />
            </Field>
          </div>
          <div className="flex flex-wrap items-center gap-4 text-sm">
            <label className="flex items-center gap-2">
              <input
                type="checkbox"
                checked={draft.enabled}
                onChange={(e) => setDraft({ ...draft, enabled: e.target.checked })}
              />
              参与候选扫描
            </label>
            {(
              [
                ['ORDER_LINE_COUNT', 'requireZeroOrderReference'],
                ['INVENTORY_ON_HAND', 'requireZeroInventory'],
                ['LISTING_COUNT', 'requireZeroListing'],
                ['UNIT_COST', 'requireZeroCost'],
              ] as const
            ).map(([metric, field]) => {
              const info = wiredMetrics.get(metric);
              return (
                <label
                  key={metric}
                  className={`flex items-center gap-2 ${info?.wired ? '' : 'text-muted-foreground'}`}
                  title={
                    info?.wired
                      ? `${info.label}（归属 ${info.owner}）`
                      : `${info?.label ?? metric} 归属 ${info?.owner ?? ''}，该服务尚未接入`
                  }
                >
                  <input
                    type="checkbox"
                    disabled={!info?.wired}
                    checked={draft[field]}
                    onChange={(e) => setDraft({ ...draft, [field]: e.target.checked })}
                  />
                  {info?.label ?? metric}
                  {!info?.wired && <span className="text-xs">（上游未接入）</span>}
                </label>
              );
            })}
          </div>
          <div className="flex gap-2">
            <Button size="sm" onClick={() => void submitRule()}>
              <Save className="h-4 w-4" />
              保存规则
            </Button>
            <Button size="sm" variant="ghost" onClick={() => setDraft(null)}>
              取消
            </Button>
          </div>
        </div>
      )}

      {notice && <p className="text-success text-sm">{notice}</p>}

      {/* 筛选 */}
      <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
        <Field label="主体类型">
          <select
            className={control}
            value={query.entityType}
            onChange={(e) => {
              setSelected([]);
              patch({ entityType: e.target.value });
            }}
          >
            {(options?.entityTypes ?? []).map((option) => (
              <option key={option.code} value={option.code}>
                {option.label}
              </option>
            ))}
          </select>
        </Field>
        <Field label="关键词" hint="编码或名称">
          <input
            className={control}
            value={query.keyword}
            placeholder="如 SKU-001 或 螺丝刀"
            onChange={(e) => patch({ keyword: e.target.value })}
          />
        </Field>
        <Field label="判定状态">
          <select
            className={control}
            value={query.verdict}
            onChange={(e) => patch({ verdict: e.target.value })}
          >
            <option value="">全部</option>
            {(options?.verdicts ?? []).map((option) => (
              <option key={option.code} value={option.code}>
                {option.label}
              </option>
            ))}
          </select>
        </Field>
      </div>

      <div className="text-muted-foreground flex flex-wrap gap-4 text-xs">
        <span>候选 {counts.candidate}</span>
        <span>在用 {counts.inUse}</span>
        <span>无法判定 {counts.incomplete}</span>
        {activeRule && (
          <span>
            当前规则：{activeRule.inactiveDays} 天未维护 ·{' '}
            {requiredDimensionText(requiredDimensionCount(activeRule))}
          </span>
        )}
      </div>

      {error && (
        <div className="border-destructive text-destructive rounded-md border px-3 py-2 text-sm">
          <p>{error}</p>
          {blocked.length > 0 && (
            <ul className="mt-1 list-disc pl-4">
              {blocked.map((line) => (
                <li key={line}>{line}</li>
              ))}
            </ul>
          )}
        </div>
      )}

      {writable && (
        <div className="flex flex-wrap items-center gap-2">
          <Button size="sm" disabled={Boolean(blockReason)} onClick={() => void runDeactivate()}>
            <Trash2 className="h-4 w-4" />
            批量停用（已选 {selected.length}）
          </Button>
          {blockReason && <span className="text-muted-foreground text-xs">{blockReason}</span>}
          <input
            className={`${control} max-w-xs`}
            placeholder="停用理由（留空则用规则里的默认文案）"
            aria-label="停用理由"
            value={reason}
            onChange={(e) => setReason(e.target.value)}
          />
          {selectedRows.some((row) => !selectable(row)) && (
            <span className="text-destructive text-xs">
              选中项里有「在用」或「无法判定」的主数据，服务端会整批拒绝（不会跳过它们继续停用其余）。
            </span>
          )}
        </div>
      )}

      {loading ? (
        <p className="text-muted-foreground text-sm">加载中…</p>
      ) : rows.length === 0 ? (
        <EmptyState
          icon={<TriangleAlert className="h-6 w-6" />}
          title={hasFilter(query) ? '没有符合条件的主数据' : '当前没有僵尸候选'}
          description={
            hasFilter(query)
              ? '换个关键词或清掉筛选再试。'
              : '这是好事：说明这段时间没有长期未维护且未被引用的主数据。维护阈值可在上面的规则里调整。'
          }
        />
      ) : (
        <div className="space-y-3">
          <div className="overflow-x-auto">
            <table className="w-full text-left text-sm">
              <thead className="text-muted-foreground border-b text-xs">
                <tr>
                  <th className="py-2 pr-3 font-medium">主数据</th>
                  <th className="py-2 pr-3 font-medium">最后维护</th>
                  <th className="py-2 pr-3 font-medium">判定</th>
                  <th className="py-2 pr-3 font-medium">引用维度</th>
                  <th className="py-2 font-medium">操作</th>
                </tr>
              </thead>
              <tbody>
                {rows.map((candidate) => {
                  const reason = selectable(candidate) ? null : '不可停用';
                  return (
                    <tr key={candidate.entityId} className="border-b last:border-0 align-top">
                      <td className="py-2 pr-3">
                        {writable && (
                          <input
                            type="checkbox"
                            className="mr-2"
                            aria-label={`选择 ${candidate.code}`}
                            checked={selected.includes(candidate.entityId)}
                            onChange={(e) =>
                              setSelected((current) =>
                                e.target.checked
                                  ? [...current, candidate.entityId]
                                  : current.filter((id) => id !== candidate.entityId),
                              )
                            }
                          />
                        )}
                        <div className="font-medium">{candidateHeadline(candidate)}</div>
                        <div className="text-muted-foreground text-xs">
                          {ENTITY_TYPE_LABELS[candidate.entityType] ?? candidate.entityType} ·{' '}
                          {candidate.status}
                        </div>
                      </td>
                      <td className="py-2 pr-3 text-xs">{maintenanceText(candidate)}</td>
                      <td className="py-2 pr-3">
                        <Badge tone={verdictTone(candidate.verdict as Verdict)}>
                          {VERDICT_LABELS[candidate.verdict as Verdict] ?? candidate.verdictLabel}
                        </Badge>
                        <div className="text-muted-foreground mt-1 max-w-[18rem] text-xs">
                          {VERDICT_ACTIONS[candidate.verdict as Verdict] ??
                            candidate.verdictLabel}
                        </div>
                      </td>
                      <td className="py-2 pr-3 text-xs">
                        <div className="space-y-0.5">
                          {candidate.dimensions
                            .filter(isDimensionRow)
                            .map((dimension) => (
                              <div key={dimension.metric} className="flex items-center gap-1">
                                <span
                                  className={
                                    isUnknown(dimension) ? 'text-warning' : 'text-muted-foreground'
                                  }
                                >
                                  {dimensionLabel(dimension.metric)}
                                </span>
                                <span>{dimensionValueText(dimension)}</span>
                                <span className="text-muted-foreground">
                                  {DIMENSION_STATE_LABELS[dimension.state] ?? dimension.state}
                                </span>
                                {dimension.stale && (
                                  <span className="text-warning">{dimensionObservedText(dimension)}</span>
                                )}
                              </div>
                            ))}
                        </div>
                      </td>
                      <td className="py-2">
                        <Button
                          size="sm"
                          variant="ghost"
                          onClick={() =>
                            setExpanded(expanded === candidate.entityId ? null : candidate.entityId)
                          }
                        >
                          {expanded === candidate.entityId ? '收起' : '为什么'}
                        </Button>
                        {reason && (
                          <span className="text-muted-foreground ml-2 text-xs">{reason}</span>
                        )}
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>

          {expanded && (
            <div className="rounded-md border p-3 text-xs">
              {rows
                .filter((candidate) => candidate.entityId === expanded)
                .map((candidate) => (
                  <div key={candidate.entityId} className="space-y-2">
                    <p className="font-medium">{candidateHeadline(candidate)}</p>
                    <ul className="list-disc pl-4">
                      {candidate.reasons.map((reason) => (
                        <li key={reason}>{reason}</li>
                      ))}
                    </ul>
                    <table className="w-full text-left">
                      <thead className="text-muted-foreground">
                        <tr>
                          <th className="py-1 pr-3 font-medium">维度</th>
                          <th className="py-1 pr-3 font-medium">取值</th>
                          <th className="py-1 pr-3 font-medium">观测时刻</th>
                          <th className="py-1 font-medium">说明</th>
                        </tr>
                      </thead>
                      <tbody>
                        {candidate.dimensions
                          .filter(isDimensionRow)
                          .map((dimension) => (
                            <tr key={dimension.metric}>
                              <td className="py-1 pr-3">{dimensionLabel(dimension.metric)}</td>
                              <td className="py-1 pr-3">{dimensionValueText(dimension)}</td>
                              <td className="py-1 pr-3">{dimensionObservedText(dimension)}</td>
                              <td className="py-1">{dimension.note}</td>
                            </tr>
                          ))}
                      </tbody>
                    </table>
                  </div>
                ))}
            </div>
          )}

          <div className="flex items-center justify-between text-sm">
            <Button
              size="sm"
              variant="ghost"
              disabled={query.page <= 0}
              onClick={() => setQuery((current) => ({ ...current, page: current.page - 1 }))}
            >
              上一页
            </Button>
            <span className="text-muted-foreground text-xs">
              第 {query.page + 1} / {pages} 页 · 共 {total} 条
            </span>
            <Button
              size="sm"
              variant="ghost"
              disabled={query.page + 1 >= pages}
              onClick={() => setQuery((current) => ({ ...current, page: current.page + 1 }))}
            >
              下一页
            </Button>
          </div>
        </div>
      )}

      {/* 停用台账 */}
      <div className="space-y-2">
        <h3 className="text-sm font-medium">停用记录</h3>
        {records.length === 0 ? (
          <p className="text-muted-foreground text-sm">还没有停用记录。</p>
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full text-left text-sm">
              <thead className="text-muted-foreground border-b text-xs">
                <tr>
                  <th className="py-2 pr-3 font-medium">主数据</th>
                  <th className="py-2 pr-3 font-medium">状态变化</th>
                  <th className="py-2 pr-3 font-medium">理由</th>
                  <th className="py-2 pr-3 font-medium">依据</th>
                  <th className="py-2 font-medium">时间</th>
                </tr>
              </thead>
              <tbody>
                {records.map((record) => (
                  <tr key={record.id} className="border-b last:border-0 align-top">
                    <td className="py-2 pr-3">
                      <div className="font-medium">{record.entityCode}</div>
                      <div className="text-muted-foreground text-xs">{record.entityName}</div>
                    </td>
                    <td className="py-2 pr-3 text-xs">
                      {record.previousStatus} → {record.newStatus}
                    </td>
                    <td className="py-2 pr-3 text-xs">{record.reason}</td>
                    <td className="text-muted-foreground py-2 pr-3 text-xs">
                      {record.ruleSnapshot
                        ? `${record.ruleSnapshot.inactiveDays} 天未维护 · 依据 ${evidenceVerdictLabel(
                            record.evidence?.verdict,
                          )}`
                        : '—'}
                    </td>
                    <td className="py-2 text-xs">{record.createdAt}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>
    </SectionCard>
  );
}

const METRIC_LABELS: Record<string, string> = {
  ORDER_LINE_COUNT: '订单',
  INVENTORY_ON_HAND: '库存',
  LISTING_COUNT: 'Listing',
  UNIT_COST: '成本',
};

function dimensionLabel(metric: string): string {
  return METRIC_LABELS[metric] ?? metric;
}

function verdictTone(verdict: Verdict): 'success' | 'warning' | 'neutral' {
  if (verdict === 'CANDIDATE') return 'warning';
  if (verdict === 'INCOMPLETE') return 'warning';
  return 'neutral';
}

function evidenceVerdictLabel(verdict?: string): string {
  return verdict ? (VERDICT_LABELS[verdict as Verdict] ?? verdict) : '未知';
}

function hasFilter(query: CandidateListQuery): boolean {
  return Boolean(query.keyword.trim() || query.verdict);
}