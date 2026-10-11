'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import { Badge, Button } from '@starcon/ui';
import { Plus, RefreshCw, Search, Trash2, X } from 'lucide-react';
import type {
  NumberEntityView,
  NumberIssuedView,
  NumberPreviewView,
  NumberRuleConflictView,
  NumberRuleView,
  NumberSequenceView,
} from '../../../../../packages/api-contracts/src/index';
import { api } from '@/lib/api';
import { useIdentity } from './identity-shell';
import {
  DATE_FORMATS,
  DATE_FORMAT_LABELS,
  RESET_SCOPE_LABELS,
  RULE_KIND_LABELS,
  SEQUENCE_SCOPE_LABELS,
  SEGMENT_TYPE_LABELS,
  STATUS_LABELS,
  type RuleRow,
  type RuleSegment,
  composeSample,
  conflictMessage,
  effectiveSummary,
  findConflicts,
  groupByEntity,
  segmentSummary,
  validateRule,
  validateSegment,
} from './numbering-model';

/**
 * 17.5.1.2 编号规则。
 *
 * 四个区块对应蓝图的四件事：**规则**（前缀 + 日期 + 序号 + 后缀，可多级嵌套）、
 * **冲突**（同实体生效区间交叠，写入前就标红）、**预览**（不占号，真实反映嵌套后的编号）、
 * **取号与计数器**（真占号落库，按实体/日期重置）。
 *
 * 预览与取号都由服务端算：预览走同一段渲染逻辑但不递增计数器，所以两者看到的编号
 * 一定一致——这种不一致是编号系统最难排查的故障之一。
 */
export function NumberRuleWorkspace() {
  const { session } = useIdentity();
  const [ready, setReady] = useState(false);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [busy, setBusy] = useState(false);

  const [entities, setEntities] = useState<NumberEntityView[]>([]);
  const [rules, setRules] = useState<NumberRuleView[]>([]);
  const [sequences, setSequences] = useState<NumberSequenceView[]>([]);
  const [issued, setIssued] = useState<NumberIssuedView[]>([]);
  const [conflicts, setConflicts] = useState<NumberRuleConflictView[]>([]);
  const [search, setSearch] = useState('');
  const [editing, setEditing] = useState<NumberRuleView | 'new' | null>(null);
  const [preview, setPreview] = useState<NumberPreviewView | null>(null);
  const [previewDate, setPreviewDate] = useState('');
  const [previewBusy, setPreviewBusy] = useState(false);

  const can = useCallback(
    (permission: string) => session.permissions.includes(permission),
    [session.permissions],
  );
  const readable = can('identity.numbering_rule.read');

  const load = useCallback(async () => {
    setError('');
    if (!readable) {
      // 没有读权限就不请求：空数组会被渲染成「还没有任何规则」，那是骗人。
      setReady(true);
      return;
    }
    try {
      const [entityList, ruleList, sequenceList, issuedList, conflictList] = await Promise.all([
        api<NumberEntityView[]>('identity/numbering/entities'),
        api<NumberRuleView[]>('identity/numbering/rules'),
        api<NumberSequenceView[]>('identity/numbering/sequences'),
        api<NumberIssuedView[]>('identity/numbering/issued'),
        api<NumberRuleConflictView[]>('identity/numbering/rules/conflicts'),
      ]);
      setEntities(entityList);
      setRules(ruleList);
      setSequences(sequenceList);
      setIssued(issuedList);
      setConflicts(conflictList);
    } catch (e) {
      setError(e instanceof Error ? e.message : '请求失败，请重试');
    } finally {
      setReady(true);
    }
  }, [readable]);

  useEffect(() => {
    void load();
  }, [load]);

  const componentsById = useMemo(() => {
    const map = new Map<string, RuleRow>();
    for (const r of rules) if (r.ruleKind === 'COMPONENT') map.set(r.id, r);
    return map;
  }, [rules]);

  const term = search.trim().toLowerCase();
  const visibleRules = useMemo(
    () =>
      rules.filter((row) =>
        `${row.entityCode} ${row.entityName ?? ''} ${row.ruleName} ${row.sampleNumber}`
          .toLowerCase()
          .includes(term),
      ),
    [rules, term],
  );
  const conflictIds = useMemo(() => {
    const ids = new Set<string>();
    for (const c of conflicts) if (c.overlaps) ids.add(c.ruleId);
    return ids;
  }, [conflicts]);

  /** 预览：不占号，反复预览同一个规则都得到同一个编号。 */
  async function runPreview(ruleId: string) {
    setPreviewBusy(true);
    setError('');
    try {
      const body: Record<string, string> = { ruleId };
      if (previewDate) body.documentDate = new Date(previewDate).toISOString();
      setPreview(await api<NumberPreviewView>('identity/numbering/preview', 'POST', body));
    } catch (e) {
      setError(e instanceof Error ? e.message : '预览失败，请重试');
      setPreview(null);
    } finally {
      setPreviewBusy(false);
    }
  }

  /** 取号：真占号并落已发放记录，与预览用的是同一段渲染逻辑。 */
  async function issue(row: NumberRuleView) {
    if (!window.confirm(`按规则「${row.ruleName}」生成一个正式编号？这一步会占用序号且不可撤销。`))
      return;
    setBusy(true);
    setError('');
    try {
      const issued = await api<NumberIssuedView>(
        `identity/numbering/rules/${row.id}/next`,
        'POST',
        {},
      );
      setNotice(`已生成编号 ${issued.number}，序号已占用`);
      await load();
    } catch (e) {
      setError(e instanceof Error ? e.message : '取号失败，请重试');
    } finally {
      setBusy(false);
    }
  }

  async function remove(row: NumberRuleView) {
    if (!window.confirm(`删除规则「${row.ruleName}」？已发放过编号的规则不允许删除。`)) return;
    setBusy(true);
    setError('');
    try {
      await api(`identity/numbering/rules/${row.id}`, 'DELETE');
      setNotice('规则已删除');
      if (preview?.ruleId === row.id) setPreview(null);
      await load();
    } catch (e) {
      setError(e instanceof Error ? e.message : '删除失败，请重试');
    } finally {
      setBusy(false);
    }
  }

  if (!ready)
    return (
      <p role="status" className="text-muted-foreground p-6 text-sm">
        正在加载编号规则…
      </p>
    );

  if (!readable)
    return (
      <p className="rounded-lg border p-6 text-sm">
        当前角色没有编号规则查看权限，请联系租户所有者开通。
      </p>
    );

  return (
    <div className="space-y-6">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <p className="text-copy-13 text-muted-foreground">
          编号由前缀、日期、序号、后缀按顺序拼成，序号按实体与业务日期分桶重置。
          预��不占用序号，正式取号才会。
        </p>
        <div className="flex flex-wrap items-center gap-2">
          <label className="relative flex items-center">
            <Search className="text-muted-foreground pointer-events-none absolute left-2.5 h-4 w-4" />
            <input
              value={search}
              onChange={(e) => setSearch(e.target.value)}
              placeholder="搜索规则"
              aria-label="搜索编号规则"
              className="border-border bg-card text-copy-13 focus-visible:border-ring h-8 w-48 rounded-md border pl-8 pr-2 outline-none"
            />
          </label>
          <Button variant="ghost" size="sm" onClick={() => void load()}>
            <RefreshCw className="h-4 w-4" />
            刷新
          </Button>
          {can('identity.numbering_rule.create') && (
            <Button size="sm" onClick={() => setEditing('new')}>
              <Plus className="h-4 w-4" />
              新建规则
            </Button>
          )}
        </div>
      </div>

      {error && (
        <div
          role="alert"
          className="border-destructive/30 bg-destructive/5 text-destructive rounded-md border p-3 text-sm"
        >
          {error}
          <Button variant="link" onClick={() => void load()}>
            重试
          </Button>
        </div>
      )}
      {notice && (
        <p role="status" className="text-success text-sm">
          {notice}
        </p>
      )}

      {editing && (
        <RuleForm
          editing={editing === 'new' ? null : editing}
          entities={entities}
          components={rules.filter((r) => r.ruleKind === 'COMPONENT')}
          componentsById={componentsById}
          existing={rules}
          busy={busy}
          onCancel={() => setEditing(null)}
          onSubmit={async (input, id) => {
            setBusy(true);
            setError('');
            try {
              if (id) {
                await api(`identity/numbering/rules/${id}`, 'PUT', input);
                setNotice('规则已更新');
              } else {
                await api('identity/numbering/rules', 'POST', input);
                setNotice('规则已创建');
              }
              setEditing(null);
              await load();
            } catch (e) {
              setError(e instanceof Error ? e.message : '保存失败，请重试');
            } finally {
              setBusy(false);
            }
          }}
        />
      )}

      {/* ---------------- 规则列表 ---------------- */}
      <section className="space-y-3">
        <div className="flex flex-wrap items-baseline justify-between gap-2">
          <h2 className="text-copy-15 font-medium">编号规则</h2>
          <p className="text-copy-12 text-muted-foreground">
            共 {visibleRules.length} 条。主规则给实体发号，子规则只作为嵌套片段被复用。
          </p>
        </div>
        {visibleRules.length === 0 ? (
          <div className="bg-card rounded-lg border p-8 text-center">
            <p className="text-copy-13 text-muted-foreground">
              {rules.length === 0
                ? '还没有任何编号规则。新建一条规则后，各业务系统创建单据时就能取到编号。'
                : '没有匹配当前搜索的规则'}
            </p>
          </div>
        ) : (
          groupByEntity(visibleRules).map((group) => (
            <div key={group.entityCode} className="space-y-2">
              <p className="text-copy-12 text-muted-foreground">{group.entityCode}</p>
              <ul className="space-y-2">
                {group.rows.map((row) => (
                  <li key={row.id} className="bg-card rounded-md border p-3">
                    <div className="flex flex-wrap items-start justify-between gap-2">
                      <div className="min-w-0">
                        <p className="text-copy-14 flex flex-wrap items-center gap-2 font-medium">
                          {row.ruleName}
                          <Badge tone={row.ruleKind === 'PRIMARY' ? 'info' : 'neutral'}>
                            {RULE_KIND_LABELS[row.ruleKind] ?? row.ruleKind}
                          </Badge>
                          <Badge tone={row.status === 'ACTIVE' ? 'success' : 'neutral'}>
                            {STATUS_LABELS[row.status] ?? row.status}
                          </Badge>
                          {conflictIds.has(row.id) && <Badge tone="danger">生效区间冲突</Badge>}
                        </p>
                        <p className="text-copy-12 text-muted-foreground mt-1">
                          {effectiveSummary(row)} · 时区 {row.timezone}
                          {row.description ? ` · ${row.description}` : ''}
                        </p>
                        {row.sampleNumber && (
                          <p className="text-copy-13 mt-1 font-mono">{row.sampleNumber}</p>
                        )}
                        <ul className="text-copy-12 text-muted-foreground mt-2 space-y-0.5">
                          {row.segments.map((s) => (
                            <li key={s.id}>
                              {s.position + 1}.{' '}
                              {SEGMENT_TYPE_LABELS[s.segmentType] ?? s.segmentType}
                              {segmentSummary(s) ? ` · ${segmentSummary(s)}` : ''}
                              {s.rendered ? ` → ${s.rendered}` : ''}
                            </li>
                          ))}
                        </ul>
                      </div>
                      <div className="flex flex-wrap items-center gap-2">
                        <Button
                          size="sm"
                          variant="ghost"
                          disabled={previewBusy}
                          onClick={() => void runPreview(row.id)}
                        >
                          预览
                        </Button>
                        {row.ruleKind === 'PRIMARY' && can('identity.numbering_rule.create') && (
                          <Button
                            size="sm"
                            variant="ghost"
                            disabled={busy}
                            onClick={() => void issue(row)}
                          >
                            取号
                          </Button>
                        )}
                        {can('identity.numbering_rule.update') && (
                          <Button size="sm" variant="ghost" onClick={() => setEditing(row)}>
                            编辑
                          </Button>
                        )}
                        {can('identity.numbering_rule.delete') && (
                          <Button
                            size="sm"
                            variant="ghost"
                            disabled={busy}
                            aria-label={`删除规则 ${row.ruleName}`}
                            onClick={() => void remove(row)}
                          >
                            <Trash2 className="h-4 w-4" />
                          </Button>
                        )}
                      </div>
                    </div>
                  </li>
                ))}
              </ul>
            </div>
          ))
        )}
      </section>

      {/* ---------------- 预览 ---------------- */}
      <section className="bg-card space-y-3 rounded-lg border p-4">
        <div className="flex flex-wrap items-baseline justify-between gap-2">
          <div>
            <p className="text-copy-14 font-medium">编号预览</p>
            <p className="text-copy-12 text-muted-foreground mt-1">
              预览不占用序号，可以反复看。业务日期同时决定日期段与序号分桶。
            </p>
          </div>
          <label className="flex items-center gap-2">
            <span className="text-copy-12 text-muted-foreground">业务日期</span>
            <input
              type="date"
              value={previewDate}
              onChange={(e) => setPreviewDate(e.target.value)}
              aria-label="预览业务日期"
              className="border-border bg-background text-copy-13 focus-visible:border-ring h-9 rounded-md border px-2 outline-none"
            />
          </label>
        </div>
        {previewBusy ? (
          <p role="status" className="text-copy-13 text-muted-foreground">
            正在生成预览…
          </p>
        ) : preview ? (
          <div className="space-y-2">
            <p className="text-copy-14 break-all font-mono">{preview.number}</p>
            <p className="text-copy-12 text-muted-foreground">
              规则「{preview.ruleName}」· 时区 {preview.timezone} · 未占用序号
            </p>
            <ul className="text-copy-12 space-y-0.5">
              {preview.parts.map((part, index) => (
                <li
                  key={`${part.segmentId}-${index}`}
                  style={{ paddingLeft: `${part.depth * 12}px` }}
                >
                  <span className="text-muted-foreground">
                    {part.depth > 0 ? '└ 嵌套 · ' : ''}
                    {SEGMENT_TYPE_LABELS[part.segmentType] ?? part.segmentType}
                  </span>{' '}
                  <span className="font-mono">{part.value}</span>
                </li>
              ))}
            </ul>
            {preview.sequences.length > 0 && (
              <p className="text-copy-12 text-muted-foreground">
                取号计划：
                {preview.sequences
                  .map(
                    (s) =>
                      `${RESET_SCOPE_LABELS[s.resetScope] ?? s.resetScope} · 桶 ${s.resetKey} · 下一个 ${s.rendered}`,
                  )
                  .join('；')}
              </p>
            )}
          </div>
        ) : (
          <p className="text-copy-13 text-muted-foreground">
            选择一条规则后点「预览」查看生成效果。
          </p>
        )}
      </section>

      {/* ---------------- 序号计数器 ---------------- */}
      <section className="space-y-3">
        <h2 className="text-copy-15 font-medium">序号计数器</h2>
        {sequences.length === 0 ? (
          <div className="bg-card rounded-lg border p-6 text-center">
            <p className="text-copy-13 text-muted-foreground">
              还没有任何计数器。取号时才会按实体与重置桶建立。
            </p>
          </div>
        ) : (
          <ul className="space-y-2">
            {sequences.map((s) => (
              <li
                key={`${s.entityCode}-${s.scopeKey}-${s.resetKey}`}
                className="bg-card rounded-md border p-3"
              >
                <p className="text-copy-14 flex flex-wrap items-center gap-2 font-medium">
                  {s.entityCode}
                  <Badge tone="neutral">
                    {s.scopeKey === 'ENTITY'
                      ? '按实体连续'
                      : `按规则 ${s.scopeKey.replace('RULE:', '')}`}
                  </Badge>
                  <Badge tone="info">桶 {s.resetKey}</Badge>
                </p>
                <p className="text-copy-12 text-muted-foreground mt-1">
                  当前序号 {s.currentValue} · 更新于 {s.updatedAt}
                </p>
              </li>
            ))}
          </ul>
        )}
      </section>

      {/* ---------------- 已发放编号 ---------------- */}
      <section className="space-y-3">
        <h2 className="text-copy-15 font-medium">已发放编号</h2>
        {issued.length === 0 ? (
          <div className="bg-card rounded-lg border p-6 text-center">
            <p className="text-copy-13 text-muted-foreground">
              还没有发放过编号。编号一经发出即为业务单据的可读标识，不可修改或删除。
            </p>
          </div>
        ) : (
          <ul className="space-y-2">
            {issued.slice(0, 50).map((row) => (
              <li key={row.id} className="bg-card rounded-md border p-3">
                <p className="text-copy-14 font-mono">{row.number}</p>
                <p className="text-copy-12 text-muted-foreground mt-1">
                  {row.entityCode} · {row.ruleName} · 序号 {row.sequenceValue} · 桶 {row.resetKey}
                  {row.documentNo ? ` · 单据 ${row.documentType ?? ''} ${row.documentNo}` : ''}
                  {row.actorName ? ` · ${row.actorName}` : ''} · {row.issuedAt}
                </p>
              </li>
            ))}
          </ul>
        )}
        {issued.length > 50 && (
          <p className="text-copy-12 text-muted-foreground">
            仅显示最近 50 条，共 {issued.length} 条。
          </p>
        )}
      </section>
    </div>
  );
}

const inputClass =
  'border-border bg-background text-copy-13 focus-visible:border-ring h-9 rounded-md border px-2 outline-none';

type RuleInput = {
  entityCode: string;
  ruleName: string;
  ruleKind: string;
  status: string;
  timezone: string;
  effectiveFrom: string | null;
  effectiveTo: string | null;
  description: string;
  segments: RuleSegment[];
};

/**
 * 规则编辑表单。
 *
 * <p>段的增删改都在这里完成，但**校验与服务端是最终判定**：这里先给出可读原因，
 * 避免用户提交后只看到一个 400。冲突判定用 {@link findConflicts}，与服务端同一套口径。
 */
function RuleForm({
  editing,
  entities,
  components,
  componentsById,
  existing,
  busy,
  onCancel,
  onSubmit,
}: {
  editing: NumberRuleView | null;
  entities: NumberEntityView[];
  components: NumberRuleView[];
  componentsById: ReadonlyMap<string, RuleRow>;
  existing: readonly NumberRuleView[];
  busy: boolean;
  onCancel: () => void;
  onSubmit: (input: RuleInput, id: string | null) => Promise<void>;
}) {
  const [entityCode, setEntityCode] = useState(editing?.entityCode ?? entities[0]?.code ?? '');
  const [ruleName, setRuleName] = useState(editing?.ruleName ?? '');
  const [ruleKind, setRuleKind] = useState(editing?.ruleKind ?? 'PRIMARY');
  const [status, setStatus] = useState(editing?.status ?? 'ACTIVE');
  const [timezone, setTimezone] = useState(editing?.timezone ?? 'Asia/Shanghai');
  const [effectiveFrom, setEffectiveFrom] = useState(editing?.effectiveFrom?.slice(0, 10) ?? '');
  const [effectiveTo, setEffectiveTo] = useState(editing?.effectiveTo?.slice(0, 10) ?? '');
  const [description, setDescription] = useState(editing?.description ?? '');
  const [segments, setSegments] = useState<RuleSegment[]>(
    editing?.segments?.map((s) => ({ ...s })) ?? [{ segmentType: 'PREFIX', literalText: '' }],
  );

  const candidate = {
    id: editing?.id,
    entityCode,
    ruleKind,
    status,
    effectiveFrom: effectiveFrom ? new Date(effectiveFrom).toISOString() : null,
    effectiveTo: effectiveTo ? new Date(effectiveTo).toISOString() : null,
  };
  const clash = findConflicts(existing, candidate);
  const segmentProblem = segments
    .map((s) =>
      validateSegment(
        s,
        components.map((c) => c.id),
      ),
    )
    .find((p) => p !== '');
  const problem =
    segmentProblem ??
    validateRule(
      {
        entityCode,
        ruleName,
        ruleKind,
        timezone,
        effectiveFrom: candidate.effectiveFrom,
        effectiveTo: candidate.effectiveTo,
        segments,
      },
      existing,
      componentsById,
    );
  const sample = composeSample(segments, new Date().toISOString());

  function update(index: number, patch: Partial<RuleSegment>) {
    setSegments((prev) => prev.map((s, i) => (i === index ? { ...s, ...patch } : s)));
  }

  return (
    <div className="bg-card space-y-4 rounded-md border p-4">
      <div className="flex items-center justify-between">
        <p className="text-copy-14 font-medium">{editing ? '编辑规则' : '新建规则'}</p>
        <Button size="sm" variant="ghost" onClick={onCancel} aria-label="关闭表单">
          <X className="h-4 w-4" />
        </Button>
      </div>
      <div className="grid gap-3 md:grid-cols-3">
        <Field label="单据实体" hint="决定序号按哪个实体分桶">
          <select
            value={entityCode}
            onChange={(e) => setEntityCode(e.target.value)}
            // 编辑时实体不可改：换实体等于换一条规则，已发放的编号来源会错乱
            disabled={!!editing}
            className={inputClass}
          >
            <option value="">请选择</option>
            {entities.map((e) => (
              <option key={e.code} value={e.code}>
                {e.code} · {e.name}
              </option>
            ))}
          </select>
        </Field>
        <Field label="规则名称">
          <input
            value={ruleName}
            onChange={(e) => setRuleName(e.target.value)}
            className={inputClass}
          />
        </Field>
        <Field label="规则种类" hint="主规则给实体发号；子规则只被嵌套复用">
          <select
            value={ruleKind}
            onChange={(e) => setRuleKind(e.target.value)}
            disabled={!!editing}
            className={inputClass}
          >
            <option value="PRIMARY">主规则</option>
            <option value="COMPONENT">可复用子规则</option>
          </select>
        </Field>
        <Field label="状态">
          <select value={status} onChange={(e) => setStatus(e.target.value)} className={inputClass}>
            <option value="ACTIVE">启用</option>
            <option value="DISABLED">停用</option>
          </select>
        </Field>
        <Field label="时区" hint="日期段按这个时区渲染，整棵嵌套树共用">
          <input
            value={timezone}
            onChange={(e) => setTimezone(e.target.value)}
            className={inputClass}
          />
        </Field>
        <Field label="生效开始" hint="留空表示从现在开始">
          <input
            type="date"
            value={effectiveFrom}
            onChange={(e) => setEffectiveFrom(e.target.value)}
            className={inputClass}
          />
        </Field>
        <Field label="生效结束" hint="留空表示长期有效">
          <input
            type="date"
            value={effectiveTo}
            onChange={(e) => setEffectiveTo(e.target.value)}
            className={inputClass}
          />
        </Field>
        <Field label="备注">
          <input
            value={description}
            onChange={(e) => setDescription(e.target.value)}
            className={inputClass}
          />
        </Field>
      </div>

      {/* ---------------- 组成段 ---------------- */}
      <div className="space-y-2">
        <div className="flex items-center justify-between">
          <p className="text-copy-13 font-medium">组成段（按顺序拼成编号）</p>
          <div className="flex flex-wrap gap-1">
            {(['PREFIX', 'DATE', 'SEQUENCE', 'SUFFIX', 'LITERAL', 'NESTED'] as const).map(
              (type) => (
                <Button
                  key={type}
                  size="sm"
                  variant="ghost"
                  onClick={() => setSegments((prev) => [...prev, { segmentType: type }])}
                >
                  + {SEGMENT_TYPE_LABELS[type]}
                </Button>
              ),
            )}
          </div>
        </div>
        {segments.length === 0 ? (
          <p className="text-copy-13 text-muted-foreground">至少需要一个组成段。</p>
        ) : (
          <ul className="space-y-2">
            {segments.map((segment, index) => (
              <li key={index} className="border-border rounded-md border p-2">
                <div className="flex flex-wrap items-center gap-2">
                  <span className="text-copy-12 text-muted-foreground">{index + 1}.</span>
                  <span className="text-copy-13 font-medium">
                    {SEGMENT_TYPE_LABELS[segment.segmentType] ?? segment.segmentType}
                  </span>
                  <span className="flex-1" />
                  <Button
                    size="sm"
                    variant="ghost"
                    disabled={index === 0}
                    aria-label={`上移第 ${index + 1} 段`}
                    onClick={() =>
                      setSegments((prev) => {
                        const a = prev[index];
                        const b = prev[index - 1];
                        if (!a || !b) return prev;
                        const next = [...prev];
                        next[index] = b;
                        next[index - 1] = a;
                        return next;
                      })
                    }
                  >
                    上移
                  </Button>
                  <Button
                    size="sm"
                    variant="ghost"
                    disabled={index === segments.length - 1}
                    aria-label={`下移第 ${index + 1} 段`}
                    onClick={() =>
                      setSegments((prev) => {
                        const a = prev[index];
                        const b = prev[index + 1];
                        if (!a || !b) return prev;
                        const next = [...prev];
                        next[index] = b;
                        next[index + 1] = a;
                        return next;
                      })
                    }
                  >
                    下移
                  </Button>
                  <Button
                    size="sm"
                    variant="ghost"
                    aria-label={`删除第 ${index + 1} 段`}
                    onClick={() => setSegments((prev) => prev.filter((_, i) => i !== index))}
                  >
                    <Trash2 className="h-4 w-4" />
                  </Button>
                </div>
                <div className="mt-2 grid gap-2 md:grid-cols-3">
                  {(['PREFIX', 'SUFFIX', 'LITERAL'] as const).includes(
                    segment.segmentType as never,
                  ) && (
                    <Field label="文本">
                      <input
                        value={segment.literalText ?? ''}
                        onChange={(e) => update(index, { literalText: e.target.value })}
                        className={inputClass}
                      />
                    </Field>
                  )}
                  {segment.segmentType === 'DATE' && (
                    <Field label="日期格式">
                      <select
                        value={segment.dateFormat ?? ''}
                        onChange={(e) => update(index, { dateFormat: e.target.value })}
                        className={inputClass}
                      >
                        <option value="">请选择</option>
                        {DATE_FORMATS.map((f) => (
                          <option key={f} value={f}>
                            {DATE_FORMAT_LABELS[f]}
                          </option>
                        ))}
                      </select>
                    </Field>
                  )}
                  {segment.segmentType === 'SEQUENCE' && (
                    <>
                      <Field label="序号宽度">
                        <input
                          type="number"
                          min={1}
                          max={12}
                          value={segment.sequenceWidth ?? ''}
                          onChange={(e) =>
                            update(index, {
                              sequenceWidth: e.target.value === '' ? null : Number(e.target.value),
                            })
                          }
                          className={inputClass}
                        />
                      </Field>
                      <Field label="起始序号">
                        <input
                          type="number"
                          min={0}
                          value={segment.sequenceStart ?? ''}
                          onChange={(e) =>
                            update(index, {
                              sequenceStart: e.target.value === '' ? null : Number(e.target.value),
                            })
                          }
                          className={inputClass}
                        />
                      </Field>
                      <Field label="重置口径">
                        <select
                          value={segment.sequenceReset ?? ''}
                          onChange={(e) => update(index, { sequenceReset: e.target.value })}
                          className={inputClass}
                        >
                          <option value="">请选择</option>
                          {Object.entries(RESET_SCOPE_LABELS).map(([code, label]) => (
                            <option key={code} value={code}>
                              {label}
                            </option>
                          ))}
                        </select>
                      </Field>
                      <Field label="计数作用域" hint="按实体：换规则接着数；按规则：新规则从头数">
                        <select
                          value={segment.sequenceScope ?? ''}
                          onChange={(e) => update(index, { sequenceScope: e.target.value })}
                          className={inputClass}
                        >
                          <option value="">请选择</option>
                          {Object.entries(SEQUENCE_SCOPE_LABELS).map(([code, label]) => (
                            <option key={code} value={code}>
                              {label}
                            </option>
                          ))}
                        </select>
                      </Field>
                    </>
                  )}
                  {segment.segmentType === 'NESTED' && (
                    <Field label="嵌套子规则" hint="只能选择可复用子规则">
                      <select
                        value={segment.childRuleId ?? ''}
                        onChange={(e) =>
                          update(index, {
                            childRuleId: e.target.value || null,
                            childRuleName:
                              components.find((c) => c.id === e.target.value)?.ruleName ?? null,
                          })
                        }
                        className={inputClass}
                      >
                        <option value="">请选择</option>
                        {components.map((c) => (
                          <option key={c.id} value={c.id}>
                            {c.ruleName}
                          </option>
                        ))}
                      </select>
                    </Field>
                  )}
                </div>
                {validateSegment(
                  segment,
                  components.map((c) => c.id),
                ) && (
                  <p className="text-copy-12 text-destructive mt-1">
                    {validateSegment(
                      segment,
                      components.map((c) => c.id),
                    )}
                  </p>
                )}
              </li>
            ))}
          </ul>
        )}
      </div>

      <p className="text-copy-13">
        本地预览：
        <span className="font-mono">{sample || '—'}</span>
        <span className="text-copy-12 text-muted-foreground">
          （序号段用起始值占位，嵌套段需服务端展开；以列表里的「预览」为准）
        </span>
      </p>

      {clash && (
        <p role="alert" className="text-copy-12 text-destructive">
          {conflictMessage(clash)}
        </p>
      )}
      {problem && !clash && (
        <p role="alert" className="text-copy-12 text-destructive">
          {problem}
        </p>
      )}

      <div className="flex gap-2">
        <Button
          size="sm"
          disabled={busy || !!problem}
          onClick={() =>
            void onSubmit(
              {
                entityCode,
                ruleName: ruleName.trim(),
                ruleKind,
                status,
                timezone: timezone.trim(),
                effectiveFrom: effectiveFrom ? new Date(effectiveFrom).toISOString() : null,
                effectiveTo: effectiveTo ? new Date(effectiveTo).toISOString() : null,
                description: description.trim(),
                segments,
              },
              editing?.id ?? null,
            )
          }
        >
          保存
        </Button>
        <Button size="sm" variant="ghost" onClick={onCancel}>
          取消
        </Button>
      </div>
    </div>
  );
}

function Field({
  label,
  hint,
  children,
}: {
  label: string;
  hint?: string;
  children: React.ReactNode;
}) {
  return (
    <label className="block">
      <span className="text-copy-13 mb-1 block font-medium">{label}</span>
      {children}
      {hint && <span className="text-copy-12 text-muted-foreground mt-1 block">{hint}</span>}
    </label>
  );
}
