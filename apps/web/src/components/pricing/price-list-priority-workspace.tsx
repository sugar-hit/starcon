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

import { api } from '../inventory/api';
import {
  ambiguityCandidatesFromError,
  conflictContext,
  conflictDetailsFromError,
  conflictTitle,
  dimensionLabel,
  emptyPriorityRule,
  fetchPriceListConflicts,
  fetchPriceListPriorityRules,
  fetchPriorityCapabilities,
  fetchPriorityConflicts,
  fetchPriorityRules,
  formatInstant,
  overlapText,
  priorityScopeLabel,
  priorityText,
  resolvePriceList,
  savePriceListPriorityRules,
  scopeRefPlaceholder,
  validatePriorityRules,
} from './price-list-priority-model';
import type {
  ConflictDetail,
  PriceListPriorityCapabilities,
  PriorityRuleInput,
  PriorityRuleView,
  Resolution,
  ResolutionCandidate,
  TenantConflictOverview,
  ValidationError,
} from './price-list-priority-model';

/**
 * 5.1.2.1 优先级与冲突检测工作区。
 *
 * <p>蓝图：「定义多张价目表的适用优先级，保存时检测冲突（同 SKU + 同店铺 + 同站点 +
 * 同时段生效的多个价目），冲突即拒绝并列出冲突明细」，输出「冲突检测结果；最终的生效价目解析规则」，
 * 并加粗「**这是价格正确性的守门员**：允许多价目但必须能唯一解析出"这个 SKU 在这个店铺此刻该卖多少"」。
 *
 * <p>这是一个真实工作区而不是占位页：能力声明（六个维度的覆盖情况 + 口径说明）、
 * 冲突概览（真冲突明细：哪两张表、哪个店铺、哪个站点、区间重叠在哪、两边各是什么优先级）、
 * 优先级规则台账与编辑器（租户可配置，数字越小优先级越高）、
 * 解析面板（给定店铺 / 站点 / 时刻 ⇒ 唯一的生效价目 + 解析依据），
 * 加载态 / 错误态 / 空态三态齐全。
 *
 * <p>🔴 **同优先级 ⇒ 不可唯一解析**：界面**绝不**替用户挑一条 ——
 * 后端返回 409 + 并列候选，界面把候选**全部**列出来让用户选。
 *
 * <p>🔴 **「同 SKU」维度未覆盖**：价目项表 {@code price_list_item} 属 **5.1.1.2**，
 * 尚未交付 ⇒ 界面在能力声明与解析结果两处都写明这件事，不显示成「没有冲突」。
 */

/** 价目表下拉项（只取本模块用得到的字段）。 */
interface PriceListOption {
  id: string;
  code: string;
  name: string;
  currency: string;
  enabled: boolean;
}

/** 价目表台账（5.1.1.1 的集合端点，只读）。 */
interface PriceListPageLite {
  items: PriceListOption[];
  total: number;
}

const SCOPE_TYPES = ['GLOBAL', 'LEGAL_ENTITY', 'SHOP', 'SITE'] as const;

export function PriceListPriorityWorkspace() {
  const [capabilities, setCapabilities] = useState<PriceListPriorityCapabilities | null>(null);
  const [overview, setOverview] = useState<TenantConflictOverview | null>(null);
  const [rules, setRules] = useState<PriorityRuleView[]>([]);
  const [priceLists, setPriceLists] = useState<PriceListOption[]>([]);
  const [selectedList, setSelectedList] = useState('');
  const [form, setForm] = useState<PriorityRuleInput[]>([emptyPriorityRule()]);
  const [conflicts, setConflicts] = useState<ConflictDetail[]>([]);
  const [resolution, setResolution] = useState<Resolution | null>(null);
  const [ambiguity, setAmbiguity] = useState<ResolutionCandidate[] | null>(null);
  const [shopCode, setShopCode] = useState('');
  const [siteCode, setSiteCode] = useState('');
  const [sku, setSku] = useState('');
  const [problem, setProblem] = useState<ValidationError | null>(null);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [busy, setBusy] = useState(false);
  const [loaded, setLoaded] = useState(false);

  const load = useCallback(async () => {
    try {
      const [caps, conflictsOverview, allRules, lists] = await Promise.all([
        fetchPriorityCapabilities(),
        fetchPriorityConflicts().catch(() => null),
        fetchPriorityRules().catch(() => []),
        api<PriceListPageLite>('catalog/price-lists/lists?size=200').catch(() => null),
      ]);
      setCapabilities(caps);
      if (conflictsOverview) setOverview(conflictsOverview);
      setRules(allRules);
      if (lists) setPriceLists(lists.items);
    } catch (e) {
      setError(e instanceof Error ? e.message : '优先级读不到');
    } finally {
      setLoaded(true);
    }
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  /** 选中一张价目表 ⇒ 读它的优先级规则与冲突明细。 */
  const open = useCallback(async (priceListId: string) => {
    setSelectedList(priceListId);
    setAmbiguity(null);
    setResolution(null);
    if (!priceListId) {
      setForm([emptyPriorityRule()]);
      setConflicts([]);
      return;
    }
    setBusy(true);
    try {
      const [listRules, report] = await Promise.all([
        fetchPriceListPriorityRules(priceListId),
        fetchPriceListConflicts(priceListId),
      ]);
      setForm(
        listRules.length
          ? listRules.map((rule) => ({
              scopeType: rule.scopeType,
              scopeRef: rule.scopeRef,
              priority: rule.priority,
              note: rule.note,
            }))
          : [emptyPriorityRule()],
      );
      setConflicts(report.conflicts);
    } catch (e) {
      setError(e instanceof Error ? e.message : '价目表的优先级读不到');
    } finally {
      setBusy(false);
    }
  }, []);

  const patch = useCallback((index: number, next: Partial<PriorityRuleInput>) => {
    setForm((current) => current.map((rule, i) => (i === index ? { ...rule, ...next } : rule)));
  }, []);

  const addRule = useCallback(() => {
    setForm((current) => [...current, emptyPriorityRule('SHOP')]);
  }, []);

  const removeRule = useCallback((index: number) => {
    setForm((current) => current.filter((_, i) => i !== index));
  }, []);

  const submit = useCallback(async () => {
    const invalid = validatePriorityRules(form);
    if (invalid) {
      setProblem(invalid);
      return;
    }
    if (!selectedList) {
      setProblem({ field: 'priceListId', message: '先选一张价目表' });
      return;
    }
    setProblem(null);
    setBusy(true);
    try {
      const saved = await savePriceListPriorityRules(selectedList, form);
      setNotice(`「${saved.priceListCode}」的优先级已保存`);
      setConflicts(saved.report.conflicts);
      await load();
    } catch (e) {
      const details = conflictDetailsFromError(e);
      if (details) {
        // 🔴 冲突即拒绝：什么都没保存。把明细摆出来，让人按条改。
        setConflicts(details);
        setError(e instanceof Error ? e.message : '保存被拒：存在同优先级的价目表，无法唯一解析');
      } else {
        setError(e instanceof Error ? e.message : '优先级保存失败');
      }
    } finally {
      setBusy(false);
    }
  }, [form, selectedList, load]);

  const runResolve = useCallback(async () => {
    if (!shopCode.trim()) {
      setProblem({ field: 'shopCode', message: '必须给出店铺编码' });
      return;
    }
    setProblem(null);
    setBusy(true);
    setAmbiguity(null);
    try {
      const result = await resolvePriceList({
        shopCode,
        siteCode,
        sku,
        at: null,
      });
      setResolution(result);
    } catch (e) {
      const candidates = ambiguityCandidatesFromError(e);
      if (candidates) {
        // 🔴 同优先级 ⇒ 不可解析。把并列候选**全部**列出来让用户选，绝不替他挑一条。
        setAmbiguity(candidates);
        setResolution(null);
        setError(e instanceof Error ? e.message : '不可唯一解析');
      } else {
        setError(e instanceof Error ? e.message : '解析失败');
      }
    } finally {
      setBusy(false);
    }
  }, [shopCode, siteCode, sku]);

  const stats = useMemo(
    () => [
      {
        label: '价目表',
        value: String(overview?.priceListCount ?? 0),
        hint: '本租户',
      },
      {
        label: '已排位',
        value: String(overview?.rankedPriceListCount ?? 0),
        hint: '配了优先级的',
      },
      {
        label: '冲突配对',
        value: String(overview?.conflictPairCount ?? 0),
        hint: '同上下文 + 区间重叠',
      },
      {
        label: '阻断级冲突',
        value: String(overview?.blockingPairCount ?? 0),
        hint: '同优先级 ⇒ 不可解析',
      },
    ],
    [overview],
  );

  if (!loaded) {
    return <EmptyState title="加载中" description="正在读取优先级与冲突检测结果。" />;
  }

  return (
    <div className="space-y-4">
      <PageHeader
        title="优先级与冲突检测"
        description="定义多张价目表的适用优先级，保存时检测冲突：同店铺 / 同站点 / 同时段生效且优先级相同 ⇒ 不可唯一解析 ⇒ 拒绝保存并列出明细。"
        actions={
          <Button onClick={() => void load()} disabled={busy}>
            刷新
          </Button>
        }
      />

      {error ? (
        <Notice tone="danger" title="出错了">
          {error}
        </Notice>
      ) : null}
      {notice ? (
        <Notice tone="success" title="已完成">
          {notice}
        </Notice>
      ) : null}
      {problem ? (
        <Notice tone="warning" title="还差一点">
          {problem.message}
        </Notice>
      ) : null}

      {/* ---------------- 能力声明 ---------------- */}
      <SectionCard
        title="能力声明"
        description="优先级语义、specificity 顺序、六个冲突维度的覆盖情况（含未覆盖的维度与原因）。"
      >
        {capabilities ? (
          <div className="space-y-3">
            <div className="grid gap-3 md:grid-cols-2">
              {capabilities.scopeTypes.map((type) => (
                <div key={type} className="border-border rounded-md border p-3">
                  <Badge tone="brand">{priorityScopeLabel(type)}</Badge>
                  <p className="text-copy-12 text-muted-foreground mt-2">
                    {scopeRefPlaceholder(type)}
                  </p>
                </div>
              ))}
            </div>
            {capabilities.notes.map((note) => (
              <div key={note.title} className="border-border rounded-md border p-3">
                <p className="text-label-13 font-semibold">{note.title}</p>
                <p className="text-copy-12 text-muted-foreground mt-1">{note.content}</p>
              </div>
            ))}
            <div className="space-y-2">
              <p className="text-label-13 font-semibold">冲突维度覆盖情况</p>
              {capabilities.dimensionCoverage.map((coverage) => (
                <div key={coverage.dimension} className="border-border rounded-md border p-3">
                  <Badge tone={coverage.covered ? 'success' : 'warning'}>
                    {coverage.label}：{coverage.covered ? '已覆盖' : '未覆盖'}
                  </Badge>
                  <p className="text-copy-12 text-muted-foreground mt-2">{coverage.detail}</p>
                </div>
              ))}
            </div>
            <Notice tone="info" title="优先级语义">
              {capabilities.prioritySemantics} {capabilities.tieNote}
            </Notice>
            <Notice tone="warning" title="同 SKU 维度未覆盖">
              {capabilities.skuNote}
            </Notice>
          </div>
        ) : (
          <EmptyState title="能力声明读不到" description="请稍后重试。" />
        )}
      </SectionCard>

      {/* ---------------- 冲突概览 ---------------- */}
      <div className="grid gap-3 md:grid-cols-4">
        {stats.map((stat) => (
          <StatCard
            key={stat.label}
            label={stat.label}
            value={stat.value}
            comparisonLabel={stat.hint}
            polarity={stat.label === '阻断级冲突' && stat.value !== '0' ? 'negative' : 'neutral'}
          />
        ))}
      </div>

      <SectionCard
        title="冲突明细"
        description="每条冲突写清：哪两张价目表、哪个店铺 / 站点 / 法人、生效区间重叠在哪、两边各是什么优先级。"
      >
        {overview && overview.conflicts.length ? (
          <div className="space-y-3">
            <Notice tone={overview.blockingPairCount ? 'danger' : 'info'} title="检测结论">
              {overview.note}
            </Notice>
            {overview.conflicts.map((detail, index) => (
              <ConflictRow
                key={`${detail.leftPriceListId}-${detail.rightPriceListId}-${index}`}
                detail={detail}
              />
            ))}
          </div>
        ) : (
          <EmptyState
            title="没有冲突明细"
            description={
              overview
                ? overview.note
                : '冲突概览读不到（读不到上游适用范围目录时会显示失败，而不是显示「没有冲突」）。'
            }
          />
        )}
      </SectionCard>

      {/* ---------------- 优先级规则 ---------------- */}
      <SectionCard
        title="优先级规则"
        description="租户可配置：数字越小优先级越高（1 最高）；每张价目表必须有一条全局规则。"
      >
        <div className="space-y-4">
          <FormField id="plp-list" label="价目表">
            <select
              id="plp-list"
              className="border-border bg-background h-9 w-full rounded-md border px-2 text-sm"
              value={selectedList}
              onChange={(event) => void open(event.target.value)}
            >
              <option value="">（选一张价目表）</option>
              {priceLists.map((item) => (
                <option key={item.id} value={item.id}>
                  {item.code} · {item.name}
                </option>
              ))}
            </select>
          </FormField>

          {selectedList ? (
            <div className="space-y-2">
              {form.map((rule, index) => (
                <div
                  key={index}
                  className="border-border grid gap-2 rounded-md border p-3 md:grid-cols-4"
                >
                  <FormField id={`plp-scope-${index}`} label="上下文">
                    <select
                      id={`plp-scope-${index}`}
                      className="border-border bg-background h-9 w-full rounded-md border px-2 text-sm"
                      value={rule.scopeType}
                      onChange={(event) =>
                        patch(index, {
                          scopeType: event.target.value as PriorityRuleInput['scopeType'],
                          scopeRef:
                            event.target.value === 'GLOBAL'
                              ? '*'
                              : rule.scopeRef === '*'
                                ? ''
                                : rule.scopeRef,
                        })
                      }
                    >
                      {SCOPE_TYPES.map((type) => (
                        <option key={type} value={type}>
                          {priorityScopeLabel(type)}
                        </option>
                      ))}
                    </select>
                  </FormField>
                  <FormField id={`plp-ref-${index}`} label="取值">
                    <Input
                      id={`plp-ref-${index}`}
                      value={rule.scopeType === 'GLOBAL' ? '*' : rule.scopeRef}
                      disabled={rule.scopeType === 'GLOBAL'}
                      placeholder={scopeRefPlaceholder(rule.scopeType)}
                      onChange={(event) => patch(index, { scopeRef: event.target.value })}
                    />
                  </FormField>
                  <FormField id={`plp-priority-${index}`} label="优先级（越小越高）">
                    <Input
                      id={`plp-priority-${index}`}
                      type="number"
                      min={0}
                      max={9999}
                      value={String(rule.priority)}
                      onChange={(event) => patch(index, { priority: Number(event.target.value) })}
                    />
                  </FormField>
                  <div className="flex items-end">
                    <Button variant="ghost" onClick={() => removeRule(index)}>
                      移除
                    </Button>
                  </div>
                </div>
              ))}
              <div className="flex gap-2">
                <Button variant="secondary" onClick={addRule}>
                  添加规则
                </Button>
                <Button onClick={() => void submit()} disabled={busy}>
                  保存优先级
                </Button>
              </div>
              {conflicts.length ? (
                <div className="space-y-2">
                  <p className="text-label-13 font-semibold">这张价目表的冲突</p>
                  {conflicts.map((detail, index) => (
                    <ConflictRow
                      key={`${detail.leftPriceListId}-${detail.rightPriceListId}-${index}`}
                      detail={detail}
                    />
                  ))}
                </div>
              ) : null}
            </div>
          ) : (
            <EmptyState
              title="选一张价目表开始"
              description="优先级是「这张表在某个上下文里排第几」，所以要挂在具体的价目表上。"
            />
          )}
        </div>
      </SectionCard>

      {/* ---------------- 规则台账 ---------------- */}
      <SectionCard
        title="本租户的优先级规则"
        description="全部已配置的规则（含全局默认与上下文排位）。"
      >
        {rules.length ? (
          <div className="space-y-2">
            {rules.map((rule) => (
              <div
                key={rule.id}
                className="border-border flex flex-wrap items-center gap-2 rounded-md border p-3"
              >
                <Badge tone="brand">{rule.priceListCode}</Badge>
                <Badge tone="neutral">
                  {priorityScopeLabel(rule.scopeType)} {rule.scopeRef}
                </Badge>
                <span className="text-copy-12 text-muted-foreground">
                  {priorityText(rule.priority)}
                </span>
                <span className="text-copy-12 text-muted-foreground">
                  更新于 {formatInstant(rule.updatedAt)}
                </span>
              </div>
            ))}
          </div>
        ) : (
          <EmptyState
            title="还没有优先级规则"
            description="没配优先级的价目表不参与解析（它没有排进这场赛跑），但它仍会出现在冲突明细里。"
          />
        )}
      </SectionCard>

      {/* ---------------- 解析 ---------------- */}
      <SectionCard
        title="生效价目解析"
        description="给定店铺 / 站点 / 时刻 ⇒ 唯一生效的价目表 + 解析依据（命中了哪条优先级规则）。"
      >
        <div className="space-y-3">
          <div className="grid gap-3 md:grid-cols-4">
            <FormField id="plp-shop" label="店铺编码">
              <Input
                id="plp-shop"
                value={shopCode}
                placeholder="AMZ-US"
                onChange={(event) => setShopCode(event.target.value)}
              />
            </FormField>
            <FormField id="plp-site" label="站点码（可选）">
              <Input
                id="plp-site"
                value={siteCode}
                placeholder="US"
                onChange={(event) => setSiteCode(event.target.value)}
              />
            </FormField>
            <FormField id="plp-sku" label="SKU（可选）">
              <Input
                id="plp-sku"
                value={sku}
                placeholder="SKU-1"
                onChange={(event) => setSku(event.target.value)}
              />
            </FormField>
            <div className="flex items-end">
              <Button onClick={() => void runResolve()} disabled={busy}>
                解析
              </Button>
            </div>
          </div>

          {resolution ? (
            <div className="space-y-2">
              <Notice tone={resolution.resolved ? 'success' : 'warning'} title="解析结果">
                {resolution.reason}
              </Notice>
              {resolution.resolved && resolution.priceList ? (
                <div className="border-border rounded-md border p-3">
                  <p className="text-label-13 font-semibold">
                    生效价目表：{resolution.priceList.code} · {resolution.priceList.name}（
                    {resolution.priceList.currency}）
                  </p>
                  {resolution.basis ? (
                    <p className="text-copy-12 text-muted-foreground mt-1">
                      {resolution.basis.explanation}
                    </p>
                  ) : null}
                </div>
              ) : null}
              {resolution.candidates.length ? (
                <div className="space-y-2">
                  <p className="text-label-13 font-semibold">参与解析的候选</p>
                  {resolution.candidates.map((candidate) => (
                    <div
                      key={candidate.priceListId}
                      className="border-border flex flex-wrap items-center gap-2 rounded-md border p-3"
                    >
                      <Badge tone={candidate.selected ? 'success' : 'neutral'}>
                        {candidate.priceListCode}
                      </Badge>
                      <span className="text-copy-12 text-muted-foreground">
                        {priorityText(candidate.priority)} · 命中「
                        {candidate.ruleScopeLabel} {candidate.ruleScopeRef}」
                      </span>
                      {candidate.selected ? <Badge tone="success">这一刻用它</Badge> : null}
                    </div>
                  ))}
                </div>
              ) : null}
              {resolution.excluded.length ? (
                <div className="space-y-2">
                  <p className="text-label-13 font-semibold">被排除的价目表</p>
                  {resolution.excluded.map((item) => (
                    <div
                      key={item.priceListId}
                      className="border-border flex flex-wrap items-center gap-2 rounded-md border p-3"
                    >
                      <Badge tone="warning">{item.reasonLabel}</Badge>
                      <span className="text-copy-12 text-muted-foreground">
                        {item.priceListCode} · {item.detail}
                      </span>
                    </div>
                  ))}
                </div>
              ) : null}
              <Notice tone="warning" title="同 SKU 维度未覆盖">
                {resolution.skuCoverage.reason}
              </Notice>
            </div>
          ) : null}

          {ambiguity ? (
            <div className="space-y-2">
              <Notice tone="danger" title="不可唯一解析">
                这些价目表在同一上下文里优先级相同 ⇒
                无法唯一确定这一刻该用哪张表。请把它们的优先级排开。
              </Notice>
              {ambiguity.map((candidate) => (
                <div
                  key={candidate.priceListId}
                  className="border-border flex flex-wrap items-center gap-2 rounded-md border p-3"
                >
                  <Badge tone="brand">{candidate.priceListCode}</Badge>
                  <span className="text-copy-12 text-muted-foreground">
                    {priorityText(candidate.priority)} · 命中「{candidate.ruleScopeLabel}{' '}
                    {candidate.ruleScopeRef}」
                  </span>
                </div>
              ))}
            </div>
          ) : null}
        </div>
      </SectionCard>
    </div>
  );
}

/** 一条冲突明细（哪两张表、哪个上下文、区间重叠在哪、两边各是什么优先级）。 */
function ConflictRow({ detail }: { detail: ConflictDetail }) {
  return (
    <div className="border-border space-y-2 rounded-md border p-3">
      <div className="flex flex-wrap items-center gap-2">
        <Badge tone={detail.blocking ? 'danger' : 'neutral'}>
          {detail.blocking ? '阻断级' : '不阻断'}
        </Badge>
        <span className="text-label-13 font-semibold">{conflictTitle(detail)}</span>
        {detail.dimensions.map((dimension) => (
          <Badge key={dimension} tone="brand">
            {dimensionLabel(dimension)}
          </Badge>
        ))}
      </div>
      <p className="text-copy-12 text-muted-foreground">
        {detail.leftPriceListCode}：{priorityText(detail.leftPriority)} ·{' '}
        {detail.rightPriceListCode}：{priorityText(detail.rightPriority)}
      </p>
      <p className="text-copy-12 text-muted-foreground">
        上下文 {conflictContext(detail)} · 区间重叠{' '}
        {overlapText(detail.overlapFrom, detail.overlapTo)}
      </p>
      <p className="text-copy-12 text-muted-foreground">{detail.reason}</p>
    </div>
  );
}
