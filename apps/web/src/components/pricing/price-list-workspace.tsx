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
  coverageStats,
  coverageText,
  createPriceList,
  currencyHints,
  deletePriceList,
  effectiveText,
  effectiveTone,
  emptyPriceListInput,
  fetchPriceList,
  fetchPriceListCapabilities,
  fetchPriceListCoverage,
  fetchPriceListScopeDirectory,
  fetchPriceLists,
  fromLocalInput,
  intervalText,
  itemCountText,
  scopeOptions,
  scopeTypeLabel,
  toLocalInput,
  toPriceListInput,
  toSubmitPayload,
  updatePriceList,
  validatePriceList,
} from './price-list-model';
import type {
  CoverageOverview,
  PriceListCapabilities,
  PriceListInput,
  PriceListSummary,
  ScopeDirectory,
  ScopeType,
  ValidationError,
} from './price-list-model';

/**
 * 5.1.1.1 价目表工作区。
 *
 * <p>蓝图：「管理价目表（如"北美自发货零售价"），每张表绑定一个币种、适用法人/店铺/站点、
 * 生效区间，展示表内价目项数量与最近更新时间。」输入「价目表增删改；币种与适用范围选择；
 * 生效区间设置」，输出「价目表记录 + 覆盖范围摘要」，并加粗
 * 「<b>一价目一币种</b>是硬约束——混币种的价目表会让利润计算失效」。
 *
 * <p>这是一个真实工作区而不是占位页：能力声明（七条口径说明，固定在顶部）、
 * 覆盖范围摘要（价目表数 / 启用数 / 此刻生效数 / 价目项合计 + 币种分布 + 范围类型分布）、
 * 价目表台账（真分页 + 关键词 / 币种 / 适用范围 / 状态 / 此刻是否生效筛选）、
 * 价目表表单（币种 + 适用范围 + 生效区间）、价目表详情（覆盖摘要 + 价目项数量 + 最近更新时间），
 * 加载态 / 错误态 / 空态三态齐全。
 *
 * <p>🔴 **一价目一币种**：表单上只有**一个**币种输入框；混币种写法在表单校验里就被拒，
 * 真正的闸在数据库（命名约束 {@code price_list_one_currency}）。
 *
 * <p>🔴 **生效区间是半开区间**：[起点, 终点) —— 终点为空 = 长期有效，
 * 界面上把这条约定写在每一张表旁边。
 *
 * <p>🔴 **表内价目项数量**：由 5.1.1.2 价目项的写入维护；价目项模块未交付前它恒为 0
 * —— 那是**真实的 0**（表里确实还没有价目项），不是「取不到」。
 */
export function PriceListWorkspace() {
  const [capabilities, setCapabilities] = useState<PriceListCapabilities | null>(null);
  const [directory, setDirectory] = useState<ScopeDirectory | null>(null);
  const [coverage, setCoverage] = useState<CoverageOverview | null>(null);
  const [lists, setLists] = useState<PriceListSummary[]>([]);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(0);
  const [selected, setSelected] = useState<PriceListSummary | null>(null);
  const [form, setForm] = useState<PriceListInput>(emptyPriceListInput());
  const [editing, setEditing] = useState<string | null>(null);
  const [keyword, setKeyword] = useState('');
  const [currencyFilter, setCurrencyFilter] = useState('');
  const [scopeTypeFilter, setScopeTypeFilter] = useState('');
  const [scopeRefFilter, setScopeRefFilter] = useState('');
  const [enabledFilter, setEnabledFilter] = useState('');
  const [effectiveFilter, setEffectiveFilter] = useState('');
  const [problem, setProblem] = useState<ValidationError | null>(null);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [busy, setBusy] = useState(false);
  const [loaded, setLoaded] = useState(false);

  const load = useCallback(async () => {
    try {
      const [caps, dir, overview] = await Promise.all([
        fetchPriceListCapabilities(),
        fetchPriceListScopeDirectory().catch(() => null),
        fetchPriceListCoverage().catch(() => null),
      ]);
      setCapabilities(caps);
      if (dir) setDirectory(dir);
      if (overview) setCoverage(overview);
    } catch (e) {
      setError(e instanceof Error ? e.message : '价目表读不到');
    } finally {
      setLoaded(true);
    }
  }, []);

  const search = useCallback(
    async (nextPage = 0) => {
      setBusy(true);
      try {
        const result = await fetchPriceLists({
          keyword: keyword.trim() || undefined,
          currency: currencyFilter.trim() || null,
          scopeType: (scopeTypeFilter.trim() || null) as ScopeType | null,
          scopeRef: scopeRefFilter.trim() || null,
          enabled: enabledFilter === '' ? null : enabledFilter === 'true',
          effectiveNow: effectiveFilter === '' ? null : effectiveFilter === 'true',
          page: nextPage,
          size: 20,
        });
        setLists(result.items);
        setTotal(result.total);
        setPage(result.page);
      } catch (e) {
        setError(e instanceof Error ? e.message : '价目表读不到');
      } finally {
        setBusy(false);
      }
    },
    [keyword, currencyFilter, scopeTypeFilter, scopeRefFilter, enabledFilter, effectiveFilter],
  );

  useEffect(() => {
    void load();
    void search(0);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const patch = useCallback((next: Partial<PriceListInput>) => {
    setForm((current) => ({ ...current, ...next }));
  }, []);

  const open = useCallback(async (id: string) => {
    setBusy(true);
    try {
      const detail = await fetchPriceList(id);
      setSelected(detail.summary);
      setForm(toPriceListInput(detail));
      setEditing(detail.summary.id);
      setProblem(null);
    } catch (e) {
      setError(e instanceof Error ? e.message : '价目表详情读不到');
    } finally {
      setBusy(false);
    }
  }, []);

  const reset = useCallback(() => {
    setEditing(null);
    setSelected(null);
    setForm(emptyPriceListInput());
    setProblem(null);
  }, []);

  const submit = useCallback(async () => {
    const invalid = validatePriceList(form);
    if (invalid) {
      setProblem(invalid);
      return;
    }
    setProblem(null);
    setBusy(true);
    try {
      const payload = toSubmitPayload({
        ...form,
        validFrom: fromLocalInput(form.validFrom) ?? form.validFrom,
        validTo: fromLocalInput(form.validTo),
      });
      const saved = editing
        ? await updatePriceList(editing, payload)
        : await createPriceList(payload);
      setEditing(saved.summary.id);
      setSelected(saved.summary);
      setForm(toPriceListInput(saved));
      setNotice(editing ? '价目表已更新' : '价目表已创建');
      await search(0);
      await load();
    } catch (e) {
      setError(e instanceof Error ? e.message : '保存失败');
    } finally {
      setBusy(false);
    }
  }, [editing, form, load, search]);

  const remove = useCallback(async () => {
    if (!editing) return;
    setBusy(true);
    try {
      await deletePriceList(editing);
      setNotice('价目表已删除');
      reset();
      await search(0);
      await load();
    } catch (e) {
      setError(e instanceof Error ? e.message : '删除失败');
    } finally {
      setBusy(false);
    }
  }, [editing, load, reset, search]);

  const addScope = useCallback((type: ScopeType) => {
    setForm((current) => ({
      ...current,
      scopes: [
        ...current.scopes,
        { type, ref: '', platformCode: type === 'LEGAL_ENTITY' ? null : '' },
      ],
    }));
  }, []);

  const patchScope = useCallback(
    (index: number, next: Partial<{ ref: string; platformCode: string | null }>) => {
      setForm((current) => ({
        ...current,
        scopes: current.scopes.map((scope, i) => (i === index ? { ...scope, ...next } : scope)),
      }));
    },
    [],
  );

  const removeScope = useCallback((index: number) => {
    setForm((current) => ({
      ...current,
      scopes: current.scopes.filter((_, i) => i !== index),
    }));
  }, []);

  const stats = useMemo(() => coverageStats(coverage), [coverage]);
  const hints = useMemo(() => currencyHints(directory), [directory]);
  const shopOptions = useMemo(() => scopeOptions(directory, 'SHOP'), [directory]);
  const siteOptions = useMemo(() => scopeOptions(directory, 'SITE'), [directory]);
  const legalEntityOptions = useMemo(() => scopeOptions(directory, 'LEGAL_ENTITY'), [directory]);
  const pages = Math.max(1, Math.ceil(total / 20));

  return (
    <div className="space-y-4">
      <PageHeader
        title="价目表"
        description="每张价目表绑定一个币种、适用法人 / 店铺 / 站点与生效区间；表内价目项数量与最近更新时间一屏可见。"
        actions={
          <Button onClick={() => void submit()} disabled={busy}>
            {editing ? '保存修改' : '新建价目表'}
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
        <Notice tone="warning" title="表单还差一点">
          {problem.message}
        </Notice>
      ) : null}

      {/* ---------------- 能力声明：固定在顶部 ---------------- */}
      <SectionCard
        title="能力声明"
        description="一价目一币种、半开生效区间、价目项数量的来源，以及上游的真实交付状态。"
      >
        {capabilities ? (
          <div className="space-y-3">
            <div className="grid gap-3 md:grid-cols-3">
              {capabilities.scopeTypes.map((type) => (
                <div key={type} className="border-border rounded-md border p-3">
                  <Badge tone="brand">{scopeTypeLabel(type)}</Badge>
                  <p className="text-copy-12 text-muted-foreground mt-2">
                    {type === 'LEGAL_ENTITY'
                      ? '法人编码（17.1.2.2 identity）'
                      : type === 'SHOP'
                        ? '店铺编码（2.3.1.1 channel）'
                        : '站点码（2.1.1.1 channel marketplace）'}
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
            <Notice tone="info" title="上游与下游">
              {capabilities.upstreamNote} {capabilities.downstreamNote}
            </Notice>
            <Notice tone="warning" title="冲突检测不在本模块">
              {capabilities.conflictNote}
            </Notice>
          </div>
        ) : (
          <EmptyState title="能力声明读不到" description="请稍后重试。" />
        )}
      </SectionCard>

      {/* ---------------- 覆盖范围摘要 ---------------- */}
      {stats.length > 0 ? (
        <div className="grid gap-3 md:grid-cols-4">
          {stats.map((stat) => (
            <StatCard
              key={stat.label}
              label={stat.label}
              value={stat.value}
              comparisonLabel={stat.hint}
              polarity="neutral"
            />
          ))}
        </div>
      ) : null}

      {coverage ? (
        <SectionCard
          title="覆盖范围摘要"
          description="本租户全部价目表的币种分布与适用范围分布（只回答「有多少、管到哪里、什么币种、此刻生效没有」）。"
        >
          <div className="space-y-3">
            <div className="flex flex-wrap gap-2">
              {coverage.currencies.map((row) => (
                <Badge key={row.currency} tone="info">
                  {row.currency} · {row.listCount} 张
                </Badge>
              ))}
              {coverage.currencies.length === 0 ? (
                <span className="text-copy-12 text-muted-foreground">还没有价目表。</span>
              ) : null}
            </div>
            <div className="flex flex-wrap gap-2">
              {coverage.scopeTypes.map((row) => (
                <Badge key={row.type} tone="neutral">
                  {row.typeLabel} · {row.listCount} 张用到
                </Badge>
              ))}
            </div>
            <p className="text-copy-12 text-muted-foreground">{coverage.note}</p>
          </div>
        </SectionCard>
      ) : null}

      <div className="grid gap-4 lg:grid-cols-3">
        {/* ---------------- 价目表台账 ---------------- */}
        <SectionCard
          className="lg:col-span-1"
          title="价目表台账"
          description={`共 ${total} 张价目表（真分页 + 筛选）。`}
        >
          <div className="space-y-3">
            <FormField id="pl-keyword" label="关键词（编码 / 名称）">
              <Input
                id="pl-keyword"
                value={keyword}
                onChange={(e) => setKeyword(e.target.value)}
                placeholder="例如 NA-RETAIL"
              />
            </FormField>
            <div className="grid grid-cols-2 gap-2">
              <FormField id="pl-currency" label="币种">
                <Input
                  id="pl-currency"
                  value={currencyFilter}
                  onChange={(e) => setCurrencyFilter(e.target.value.toUpperCase())}
                  placeholder="USD"
                />
              </FormField>
              <FormField id="pl-scope-type" label="范围类型">
                <Input
                  id="pl-scope-type"
                  value={scopeTypeFilter}
                  onChange={(e) => setScopeTypeFilter(e.target.value.toUpperCase())}
                  placeholder="SHOP / SITE / LEGAL_ENTITY"
                />
              </FormField>
            </div>
            <FormField id="pl-scope-ref" label="范围编码（与范围类型配合筛）">
              <Input
                id="pl-scope-ref"
                value={scopeRefFilter}
                onChange={(e) => setScopeRefFilter(e.target.value)}
                placeholder="例如 AMZ-US"
              />
            </FormField>
            <div className="grid grid-cols-2 gap-2">
              <FormField id="pl-enabled" label="状态">
                <Input
                  id="pl-enabled"
                  value={enabledFilter}
                  onChange={(e) => setEnabledFilter(e.target.value)}
                  placeholder="true / false"
                />
              </FormField>
              <FormField id="pl-effective" label="此刻是否生效">
                <Input
                  id="pl-effective"
                  value={effectiveFilter}
                  onChange={(e) => setEffectiveFilter(e.target.value)}
                  placeholder="true / false"
                />
              </FormField>
            </div>
            <Button onClick={() => void search(0)} disabled={busy}>
              检索
            </Button>
          </div>

          <div className="mt-4 space-y-2">
            {lists.length === 0 ? (
              <EmptyState
                title="还没有价目表"
                description="价目表是「这个 SKU 在这个店铺该卖多少钱」的锚：一张表 = 一个币种 + 一组适用范围 + 一个生效区间。"
                action={<Button onClick={reset}>新建价目表</Button>}
              />
            ) : (
              lists.map((item) => (
                <button
                  key={item.id}
                  type="button"
                  onClick={() => void open(item.id)}
                  className="border-border hover:bg-muted w-full rounded-md border p-3 text-left"
                >
                  <div className="flex items-center justify-between gap-2">
                    <span className="text-label-13 font-semibold">{item.name}</span>
                    <Badge tone={effectiveTone(item.effectiveNow)}>
                      {effectiveText(item.effectiveNow)}
                    </Badge>
                  </div>
                  <div className="text-copy-12 text-muted-foreground mt-1 flex flex-wrap gap-2">
                    <span>{item.code}</span>
                    <span>· {item.currency}</span>
                    <span>· {item.enabled ? '启用' : '停用'}</span>
                  </div>
                  <div className="text-copy-12 text-muted-foreground mt-1">
                    {coverageText(item.coverage)} · {itemCountText(item.itemCount)}
                  </div>
                </button>
              ))
            )}
          </div>

          {pages > 1 ? (
            <div className="mt-3 flex items-center justify-between">
              <Button size="sm" disabled={busy || page <= 0} onClick={() => void search(page - 1)}>
                上一页
              </Button>
              <span className="text-copy-12 text-muted-foreground">
                第 {page + 1} / {pages} 页
              </span>
              <Button
                size="sm"
                disabled={busy || page + 1 >= pages}
                onClick={() => void search(page + 1)}
              >
                下一页
              </Button>
            </div>
          ) : null}
        </SectionCard>

        {/* ---------------- 价目表表单 ---------------- */}
        <SectionCard
          className="lg:col-span-2"
          title={editing ? `编辑价目表：${form.name || form.code}` : '新建价目表'}
          description="币种 + 适用范围 + 生效区间。"
          extra={
            editing ? (
              <Button variant="destructive" size="sm" onClick={() => void remove()} disabled={busy}>
                删除
              </Button>
            ) : undefined
          }
        >
          <div className="grid gap-3 md:grid-cols-3">
            <FormField id="pl-code" label="价目表编码" hint="租户内唯一" required>
              <Input
                id="pl-code"
                value={form.code}
                onChange={(e) => patch({ code: e.target.value.toUpperCase() })}
              />
            </FormField>
            <FormField id="pl-name" label="价目表名称" required>
              <Input
                id="pl-name"
                value={form.name}
                onChange={(e) => patch({ name: e.target.value })}
              />
            </FormField>
            <FormField
              id="pl-currency-field"
              label="币种"
              hint="🔴 一张价目表只能有一个币种"
              error={problem?.field === 'currency' ? problem.message : undefined}
              required
            >
              <Input
                id="pl-currency-field"
                value={form.currency}
                onChange={(e) => patch({ currency: e.target.value.toUpperCase() })}
                list="pl-currency-hints"
              />
            </FormField>
          </div>

          <datalist id="pl-currency-hints">
            {hints.map((hint) => (
              <option key={hint} value={hint} />
            ))}
          </datalist>

          <div className="mt-3 grid gap-3 md:grid-cols-2">
            <FormField
              id="pl-valid-from"
              label="生效起点（含）"
              error={problem?.field === 'validFrom' ? problem.message : undefined}
              required
            >
              <Input
                id="pl-valid-from"
                type="datetime-local"
                value={toLocalInput(form.validFrom)}
                onChange={(e) => patch({ validFrom: e.target.value })}
              />
            </FormField>
            <FormField
              id="pl-valid-to"
              label="生效终点（不含，空 = 长期有效）"
              error={problem?.field === 'validTo' ? problem.message : undefined}
            >
              <Input
                id="pl-valid-to"
                type="datetime-local"
                value={toLocalInput(form.validTo)}
                onChange={(e) => patch({ validTo: e.target.value })}
              />
            </FormField>
          </div>

          <div className="mt-3 grid gap-3 md:grid-cols-2">
            <FormField id="pl-description" label="说明">
              <Input
                id="pl-description"
                value={form.description}
                onChange={(e) => patch({ description: e.target.value })}
              />
            </FormField>
            <FormField id="pl-note" label="备注">
              <Input
                id="pl-note"
                value={form.note}
                onChange={(e) => patch({ note: e.target.value })}
              />
            </FormField>
          </div>

          <div className="mt-3">
            <FormField
              id="pl-enabled-field"
              label="启用"
              hint="停用 = 这张表不再参与定价；历史价目项仍然引用它。"
            >
              <Input
                id="pl-enabled-field"
                type="checkbox"
                checked={form.enabled}
                onChange={(e) => patch({ enabled: e.target.checked })}
              />
            </FormField>
          </div>

          {/* ---------------- 适用范围 ---------------- */}
          <div className="mt-4">
            <div className="flex flex-wrap items-center gap-2">
              <span className="text-label-13 font-semibold">适用范围</span>
              <Button size="sm" onClick={() => addScope('LEGAL_ENTITY')} disabled={busy}>
                加法人
              </Button>
              <Button size="sm" onClick={() => addScope('SHOP')} disabled={busy}>
                加店铺
              </Button>
              <Button size="sm" onClick={() => addScope('SITE')} disabled={busy}>
                加站点
              </Button>
            </div>
            <p className="text-copy-12 text-muted-foreground mt-1">
              🔴 编码必须在上游目录里真实存在（服务端会核对）；一条范围都没有 = 适用于全租户。
            </p>
            {problem?.field === 'scopes' ? (
              <Notice tone="warning" title="适用范围有问题">
                {problem.message}
              </Notice>
            ) : null}
            <div className="mt-2 space-y-2">
              {form.scopes.length === 0 ? (
                <span className="text-copy-12 text-muted-foreground">
                  尚未限定范围 —— 这张表将对全租户生效。
                </span>
              ) : (
                form.scopes.map((scope, index) => (
                  <div key={`${scope.type}-${index}`} className="flex flex-wrap items-end gap-2">
                    <Badge tone="brand">{scopeTypeLabel(scope.type)}</Badge>
                    <FormField id={`pl-scope-${index}`} label="编码">
                      <Input
                        id={`pl-scope-${index}`}
                        value={scope.ref}
                        onChange={(e) => patchScope(index, { ref: e.target.value })}
                        placeholder={
                          scope.type === 'LEGAL_ENTITY'
                            ? '例如 US-HQ'
                            : scope.type === 'SHOP'
                              ? '例如 AMZ-US'
                              : '例如 US'
                        }
                      />
                    </FormField>
                    {scope.type !== 'LEGAL_ENTITY' ? (
                      <FormField id={`pl-scope-platform-${index}`} label="平台码">
                        <Input
                          id={`pl-scope-platform-${index}`}
                          value={scope.platformCode ?? ''}
                          onChange={(e) => patchScope(index, { platformCode: e.target.value })}
                          placeholder="AMAZON"
                        />
                      </FormField>
                    ) : null}
                    <Button
                      size="sm"
                      variant="destructive"
                      onClick={() => removeScope(index)}
                      disabled={busy}
                    >
                      移除
                    </Button>
                  </div>
                ))
              )}
            </div>
          </div>

          <div className="mt-4 flex gap-2">
            <Button onClick={() => void submit()} disabled={busy}>
              {editing ? '保存修改' : '创建价目表'}
            </Button>
            {editing ? (
              <Button variant="secondary" onClick={reset} disabled={busy}>
                取消编辑
              </Button>
            ) : null}
          </div>
        </SectionCard>
      </div>

      {/* ---------------- 适用范围目录 ---------------- */}
      <SectionCard
        title="适用范围目录（上游主数据）"
        description="法人来自身份服务，店铺与站点来自渠道服务；本服务不另建这些主数据的副本。"
      >
        {directory ? (
          <div className="grid gap-3 md:grid-cols-3">
            <div>
              <p className="text-label-13 font-semibold">
                法人（{directory.legalEntities.length}）
              </p>
              <ul className="text-copy-12 text-muted-foreground mt-1 space-y-1">
                {directory.legalEntities.map((entity) => (
                  <li key={entity.code}>
                    {entity.code} · {entity.name}
                    {entity.baseCurrency ? ` · 本位币 ${entity.baseCurrency}` : ''}
                  </li>
                ))}
              </ul>
            </div>
            <div>
              <p className="text-label-13 font-semibold">店铺（{directory.shops.length}）</p>
              <ul className="text-copy-12 text-muted-foreground mt-1 space-y-1">
                {directory.shops.map((shop) => (
                  <li key={shop.code}>
                    {shop.code} · {shop.name}
                    {shop.platformCode ? ` · ${shop.platformCode}` : ''}
                    {shop.legalEntityCode ? ` · 法人 ${shop.legalEntityCode}` : ''}
                  </li>
                ))}
              </ul>
            </div>
            <div>
              <p className="text-label-13 font-semibold">站点（{directory.sites.length}）</p>
              <ul className="text-copy-12 text-muted-foreground mt-1 space-y-1">
                {directory.sites.map((site) => (
                  <li key={`${site.platformCode}-${site.siteCode}`}>
                    {site.siteCode} · {site.siteName}
                    {site.platformCode ? ` · ${site.platformCode}` : ''}
                    {site.defaultCurrency ? ` · ${site.defaultCurrency}` : ''}
                  </li>
                ))}
              </ul>
            </div>
            <p className="text-copy-12 text-muted-foreground md:col-span-3">
              {directory.sourceNote}
            </p>
            <p className="text-copy-12 text-muted-foreground md:col-span-3">
              可选店铺编码：
              {shopOptions.length > 0
                ? shopOptions.map((o) => o.ref).join(' / ')
                : '（目录为空）'}{' '}
              · 可选站点码：
              {siteOptions.length > 0
                ? siteOptions.map((o) => o.ref).join(' / ')
                : '（目录为空）'}{' '}
              · 可选法人编码：
              {legalEntityOptions.length > 0
                ? legalEntityOptions.map((o) => o.ref).join(' / ')
                : '（目录为空）'}
            </p>
          </div>
        ) : (
          <EmptyState
            title="适用范围目录读不到"
            description="法人 / 店铺 / 站点来自上游服务；读不到时这里显示「读不到」，而不是「没有」。"
          />
        )}
      </SectionCard>

      {/* ---------------- 详情 ---------------- */}
      {selected ? (
        <SectionCard
          title={`价目表详情：${selected.name}`}
          description="覆盖范围摘要 + 价目项数量 + 最近更新时间。"
        >
          <div className="grid gap-2 md:grid-cols-2">
            <p className="text-copy-12">
              <span className="text-muted-foreground">编码：</span>
              {selected.code}
            </p>
            <p className="text-copy-12">
              <span className="text-muted-foreground">币种：</span>
              {selected.currency}
            </p>
            <p className="text-copy-12">
              <span className="text-muted-foreground">生效区间：</span>
              {intervalText(selected.validFrom, selected.validTo)}
            </p>
            <p className="text-copy-12">
              <span className="text-muted-foreground">状态：</span>
              {selected.enabled ? '启用' : '停用'} · {effectiveText(selected.effectiveNow)}
            </p>
            <p className="text-copy-12">
              <span className="text-muted-foreground">覆盖范围：</span>
              {coverageText(selected.coverage)}
            </p>
            <p className="text-copy-12">
              <span className="text-muted-foreground">表内价目项数量：</span>
              {itemCountText(selected.itemCount)}
            </p>
            <p className="text-copy-12">
              <span className="text-muted-foreground">最近更新时间：</span>
              {intervalText(selected.updatedAt, null).split(' → ')[0]}
            </p>
          </div>
        </SectionCard>
      ) : null}

      {!loaded ? (
        <Notice tone="info" title="加载中">
          正在读取价目表…
        </Notice>
      ) : null}
    </div>
  );
}
