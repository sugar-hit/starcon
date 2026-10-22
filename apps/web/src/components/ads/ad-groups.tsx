'use client';

import { useCallback, useEffect, useState } from 'react';
import { errorText } from '../inventory/inventory-model';
import {
  createAdGroup,
  emptyAdGroupCreateInput,
  emptyAdGroupSettingsInput,
  fetchAdGroupCapabilities,
  fetchAdGroupDetail,
  fetchAdGroupOptions,
  fetchAdGroupProductCandidates,
  fetchAdGroups,
  fetchAdGroupSummary,
  groupBudgetText,
  groupOperationLabel,
  groupPerformanceText,
  groupReportEntryText,
  groupStatusLabel,
  money,
  operationResultLabel,
  operationResultTone,
  pendingWritebackText,
  setAdGroupProducts,
  skuMappingText,
  timestampText,
  updateAdGroupSettings,
  validateGroupCreate,
  validateGroupSettings,
} from './ad-group-model';
import type {
  AdGroupCreateInput,
  AdGroupCreateResult,
  AdGroupDetail,
  AdGroupManagementCapabilities,
  AdGroupOptions,
  AdGroupProductCandidateRow,
  AdGroupRow,
  AdGroupSettingsInput,
  AdGroupSummary,
} from './ad-group-model';

/**
 * 14.1.2.1 广告组管理。
 *
 * 🔴 页面顶部那段话不是提示语，是这一页的设计前提：
 *
 * 1. **组级表现当前拿不到**。现有事实表里**没有组级指标表**
 *    （`ad_campaign_daily_metric` 是 campaign 维度，没有 group_id）⇒ 组级指标
 *    `available=false`。页面显示「拿不到 + 责任模块」，**不拿活动级指标冒充组级指标**。
 * 2. **组级报表入口是入口，不是报表**。入口已交付（路由标识 + 责任模块都写出来），
 *    报表本体归 14.3.1.2（同批并行开发，下一批并入集成头）。
 * 3. **本地已改 ≠ 平台侧已改**。平台回写队列未交付 ⇒ 所有本地写只落本地，
 *    组被标成「待回写平台」时页面显式标出来。
 * 4. **定向与匹配类型是平台原文**，本模块不解读 —— 页面也只原样展示，不给「建议」。
 */
export function AdGroupsWorkspace() {
  const [capabilities, setCapabilities] = useState<AdGroupManagementCapabilities | null>(null);
  const [options, setOptions] = useState<AdGroupOptions | null>(null);

  const [groups, setGroups] = useState<AdGroupRow[]>([]);
  const [summary, setSummary] = useState<AdGroupSummary | null>(null);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(0);
  const [size] = useState(20);

  const [campaignId, setCampaignId] = useState('');
  const [status, setStatus] = useState('');
  const [keyword, setKeyword] = useState('');
  const [sort, setSort] = useState('updatedAtDesc');

  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [detail, setDetail] = useState<AdGroupDetail | null>(null);

  const [createInput, setCreateInput] = useState<AdGroupCreateInput>(emptyAdGroupCreateInput);
  const [createErrors, setCreateErrors] = useState<string[]>([]);
  const [createResult, setCreateResult] = useState<AdGroupCreateResult | null>(null);

  const [settingsInput, setSettingsInput] = useState<AdGroupSettingsInput>(emptyAdGroupSettingsInput);
  const [settingsErrors, setSettingsErrors] = useState<string[]>([]);

  const [candidates, setCandidates] = useState<AdGroupProductCandidateRow[]>([]);
  const [candidateKeyword, setCandidateKeyword] = useState('');
  const [selectedListings, setSelectedListings] = useState<string[]>([]);
  const [productReason, setProductReason] = useState('');

  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');

  const load = useCallback(async () => {
    setLoading(true);
    setError('');
    try {
      const query = {
        campaignId: campaignId || undefined,
        status: status || undefined,
        keyword: keyword || undefined,
        sort,
        page,
        size,
      };
      const [list, sum] = await Promise.all([fetchAdGroups(query), fetchAdGroupSummary(query)]);
      setGroups(list.items);
      setTotal(list.total);
      setSummary(sum);
    } catch (e) {
      setError(errorText(e));
    } finally {
      setLoading(false);
    }
  }, [campaignId, status, keyword, sort, page, size]);

  useEffect(() => {
    void load();
  }, [load]);

  useEffect(() => {
    let alive = true;
    void (async () => {
      try {
        const [caps, opts] = await Promise.all([fetchAdGroupCapabilities(), fetchAdGroupOptions()]);
        if (!alive) return;
        setCapabilities(caps);
        setOptions(opts);
      } catch (e) {
        if (alive) setError(errorText(e));
      }
    })();
    return () => {
      alive = false;
    };
  }, []);

  const loadDetail = useCallback(async (groupId: string) => {
    setError('');
    try {
      const view = await fetchAdGroupDetail(groupId);
      setDetail(view);
      setSettingsInput({
        groupName: view.group.groupName,
        status: view.group.status,
        dailyBudget: view.group.dailyBudget === null ? '' : String(view.group.dailyBudget),
        clearDailyBudget: view.group.dailyBudget === null,
        targetingType: view.group.targetingType,
        matchType: view.group.matchType,
        targetingSettings: view.group.targetingSettings,
        reason: '',
      });
    } catch (e) {
      setError(errorText(e));
    }
  }, []);

  const loadCandidates = useCallback(
    async (targetCampaignId: string, groupId?: string) => {
      setError('');
      try {
        const page = await fetchAdGroupProductCandidates(targetCampaignId, {
          groupId,
          keyword: candidateKeyword || undefined,
          size: 50,
        });
        setCandidates(page.items);
      } catch (e) {
        setError(errorText(e));
      }
    },
    [candidateKeyword],
  );

  async function submitCreate() {
    const check = validateGroupCreate(createInput);
    setCreateErrors(check.errors);
    if (!check.ok) return;
    setBusy(true);
    setError('');
    try {
      const result = await createAdGroup({
        campaignId: createInput.campaignId.trim(),
        groupName: createInput.groupName.trim(),
        defaultBid: createInput.defaultBid.trim() ? Number(createInput.defaultBid) : undefined,
        dailyBudget: createInput.dailyBudget.trim() ? Number(createInput.dailyBudget) : undefined,
        targetingType: createInput.targetingType.trim() || undefined,
        matchType: createInput.matchType.trim() || undefined,
        targetingSettings: createInput.targetingSettings.trim() || undefined,
        reason: createInput.reason.trim() || undefined,
      });
      setCreateResult(result);
      setCreateInput({ ...emptyAdGroupCreateInput, campaignId: createInput.campaignId });
      await load();
    } catch (e) {
      setError(errorText(e));
    } finally {
      setBusy(false);
    }
  }

  async function submitSettings() {
    if (!detail) return;
    const check = validateGroupSettings(settingsInput);
    setSettingsErrors(check.errors);
    if (!check.ok) return;
    setBusy(true);
    setError('');
    try {
      await updateAdGroupSettings(detail.group.id, {
        groupName: settingsInput.groupName,
        status: settingsInput.status || undefined,
        dailyBudget: settingsInput.dailyBudget.trim() ? Number(settingsInput.dailyBudget) : undefined,
        clearDailyBudget: settingsInput.clearDailyBudget ? true : undefined,
        targetingType: settingsInput.targetingType,
        matchType: settingsInput.matchType,
        targetingSettings: settingsInput.targetingSettings,
        reason: settingsInput.reason.trim() || undefined,
      });
      await Promise.all([load(), loadDetail(detail.group.id)]);
    } catch (e) {
      setError(errorText(e));
    } finally {
      setBusy(false);
    }
  }

  async function submitProducts() {
    if (!detail) return;
    setBusy(true);
    setError('');
    try {
      await setAdGroupProducts(detail.group.id, {
        listingIds: selectedListings,
        reason: productReason.trim() || undefined,
      });
      setProductReason('');
      await Promise.all([load(), loadDetail(detail.group.id)]);
    } catch (e) {
      setError(errorText(e));
    } finally {
      setBusy(false);
    }
  }

  if (loading && groups.length === 0) {
    return <p className="text-muted-foreground text-sm">正在加载广告组…</p>;
  }

  return (
    <main className="space-y-4">
      <header className="space-y-1">
        <h1 className="text-lg font-medium">广告组管理</h1>
        <p className="text-muted-foreground text-xs">
          14.1.2.1 · 关联投放商品、匹配类型、定向设置、默认竞价；组级报表入口指向 14.3.1.2。
        </p>
        <p className="text-muted-foreground text-xs">
          🔴 组级表现指标当前**不可得**（没有组级指标事实表），页面不会拿活动级指标冒充；
          🔴 本地写只落本地并标「待回写平台」，平台回写队列未交付。
        </p>
      </header>

      {error ? <p className="text-destructive text-sm">操作失败：{error}</p> : null}

      {/* ================= 能力声明 ================= */}
      {capabilities ? (
        <Section title="能力声明">
          <table className="w-full text-sm">
            <thead>
              <tr className="text-muted-foreground border-b text-left text-xs">
                <th className="p-2">维度</th>
                <th className="p-2">说明</th>
                <th className="p-2">可用</th>
                <th className="p-2">来源 / 责任模块</th>
                <th className="p-2">影响</th>
              </tr>
            </thead>
            <tbody>
              {capabilities.dimensions.map((d) => (
                <tr key={d.dimension} className="border-b">
                  <td className="p-2">{d.dimension}</td>
                  <td className="p-2">{d.description}</td>
                  <td className="p-2">{d.available ? '已交付' : '未交付'}</td>
                  <td className="p-2">{d.available ? d.source : d.ownerModule}</td>
                  <td className="p-2 text-muted-foreground text-xs">{d.impact}</td>
                </tr>
              ))}
            </tbody>
          </table>
          <p className="text-muted-foreground text-xs">{capabilities.targetingNote}</p>
          <p className="text-muted-foreground text-xs">{capabilities.platformWritebackNote}</p>
          <p className="text-muted-foreground text-xs">
            护栏：组级日预算上限 {money(capabilities.guardrails.maxGroupDailyBudget)}，
            默认竞价上限 {money(capabilities.guardrails.maxDefaultBid)}，
            单组最多关联 {capabilities.guardrails.maxProductsPerGroup} 个投放商品。
            {capabilities.guardrails.note}
          </p>
        </Section>
      ) : null}

      {/* ================= 列表 ================= */}
      <Section title={`广告组（${total}）`}>
        <div className="flex flex-wrap items-end gap-2">
          <Field label="活动 id">
            <input
              className="rounded border px-2 py-1 text-sm"
              value={campaignId}
              onChange={(e) => {
                setPage(0);
                setCampaignId(e.target.value);
              }}
              placeholder="按活动过滤"
            />
          </Field>
          <Field label="状态">
            <select
              className="rounded border px-2 py-1 text-sm"
              value={status}
              onChange={(e) => {
                setPage(0);
                setStatus(e.target.value);
              }}
            >
              <option value="">全部</option>
              {(options?.statuses ?? []).map((s) => (
                <option key={s.value} value={s.value}>
                  {s.label}
                </option>
              ))}
            </select>
          </Field>
          <Field label="关键词">
            <input
              className="rounded border px-2 py-1 text-sm"
              value={keyword}
              onChange={(e) => {
                setPage(0);
                setKeyword(e.target.value);
              }}
              placeholder="组名或平台侧 id"
            />
          </Field>
          <Field label="排序">
            <select
              className="rounded border px-2 py-1 text-sm"
              value={sort}
              onChange={(e) => {
                setPage(0);
                setSort(e.target.value);
              }}
            >
              {(options?.sorts ?? []).map((s) => (
                <option key={s.value} value={s.value}>
                  {s.label}
                </option>
              ))}
            </select>
          </Field>
          <button
            type="button"
            className="rounded border px-2 py-1 text-sm"
            onClick={() => void load()}
          >
            刷新
          </button>
        </div>

        {summary ? (
          <p className="text-muted-foreground text-xs">
            汇总：共 {summary.total} 个组，覆盖 {summary.campaignCount} 个活动，其中{' '}
            {summary.pendingWritebackCount} 个标着「本地已改、尚未回写平台」。
            {summary.note}
          </p>
        ) : null}

        {groups.length === 0 ? (
          <p className="text-muted-foreground text-sm">
            没有符合条件的广告组。这**不等于**「没有投放」—— 结构数据由 14.1.1.2 的登记入口与
            本模块的写入口落库；换成别的筛选条件再看看。
          </p>
        ) : (
          <table className="w-full text-sm">
            <thead>
              <tr className="text-muted-foreground border-b text-left text-xs">
                <th className="p-2">广告组</th>
                <th className="p-2">活动 / 店铺</th>
                <th className="p-2">状态</th>
                <th className="p-2">默认竞价</th>
                <th className="p-2">组级日预算</th>
                <th className="p-2">定向 / 匹配类型</th>
                <th className="p-2">商品</th>
                <th className="p-2">回写</th>
              </tr>
            </thead>
            <tbody>
              {groups.map((g) => (
                <tr key={g.id} className="border-b">
                  <td className="p-2">
                    <button
                      type="button"
                      className="underline"
                      onClick={() => {
                        setSelectedId(g.id);
                        setSelectedListings([]);
                        void loadDetail(g.id);
                      }}
                    >
                      {g.groupName}
                    </button>
                    <div className="text-muted-foreground text-xs">{g.externalGroupId}</div>
                  </td>
                  <td className="p-2">
                    {g.campaignName}
                    <div className="text-muted-foreground text-xs">{g.shopCode}</div>
                  </td>
                  <td className="p-2">{groupStatusLabel(g.status)}</td>
                  <td className="p-2">{money(g.defaultBid, g.bidCurrency)}</td>
                  <td className="p-2">{groupBudgetText(g.dailyBudget, g.currency)}</td>
                  <td className="p-2">
                    {g.targetingType || '—'}
                    <div className="text-muted-foreground text-xs">{g.matchType || '—'}</div>
                  </td>
                  <td className="p-2">{g.productCount}</td>
                  <td className="p-2">
                    {g.pendingPlatformWriteback ? (
                      <span className="text-amber-600 text-xs">待回写平台</span>
                    ) : (
                      <span className="text-muted-foreground text-xs">—</span>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}

        <div className="flex items-center gap-2">
          <button
            type="button"
            className="rounded border px-2 py-1 text-sm"
            disabled={page <= 0}
            onClick={() => setPage(Math.max(page - 1, 0))}
          >
            上一页
          </button>
          <span className="text-muted-foreground text-xs">
            第 {page + 1} 页 / 共 {Math.max(Math.ceil(total / size), 1)} 页
          </span>
          <button
            type="button"
            className="rounded border px-2 py-1 text-sm"
            disabled={(page + 1) * size >= total}
            onClick={() => setPage(page + 1)}
          >
            下一页
          </button>
        </div>
      </Section>

      {/* ================= 新建广告组 ================= */}
      <Section title="新建广告组">
        <div className="flex flex-wrap items-end gap-2">
          <Field label="活动 id">
            <input
              className="rounded border px-2 py-1 text-sm"
              value={createInput.campaignId}
              onChange={(e) =>
                setCreateInput({ ...createInput, campaignId: e.target.value })
              }
              placeholder="所属活动"
            />
          </Field>
          <Field label="组名">
            <input
              className="rounded border px-2 py-1 text-sm"
              value={createInput.groupName}
              onChange={(e) => setCreateInput({ ...createInput, groupName: e.target.value })}
            />
          </Field>
          <Field label="默认竞价">
            <input
              className="rounded border px-2 py-1 text-sm"
              value={createInput.defaultBid}
              onChange={(e) => setCreateInput({ ...createInput, defaultBid: e.target.value })}
              placeholder="留空 = 平台侧没给"
            />
          </Field>
          <Field label="组级日预算">
            <input
              className="rounded border px-2 py-1 text-sm"
              value={createInput.dailyBudget}
              onChange={(e) => setCreateInput({ ...createInput, dailyBudget: e.target.value })}
              placeholder="留空 = 继承活动预算"
            />
          </Field>
          <Field label="定向类型（原文）">
            <input
              className="rounded border px-2 py-1 text-sm"
              value={createInput.targetingType}
              onChange={(e) => setCreateInput({ ...createInput, targetingType: e.target.value })}
            />
          </Field>
          <Field label="匹配类型（原文）">
            <input
              className="rounded border px-2 py-1 text-sm"
              value={createInput.matchType}
              onChange={(e) => setCreateInput({ ...createInput, matchType: e.target.value })}
            />
          </Field>
          <Field label="定向设置（原文）">
            <input
              className="rounded border px-2 py-1 text-sm"
              value={createInput.targetingSettings}
              onChange={(e) =>
                setCreateInput({ ...createInput, targetingSettings: e.target.value })
              }
              placeholder="平台原文，不解读"
            />
          </Field>
          <Field label="理由">
            <input
              className="rounded border px-2 py-1 text-sm"
              value={createInput.reason}
              onChange={(e) => setCreateInput({ ...createInput, reason: e.target.value })}
              placeholder="写进审计"
            />
          </Field>
          <button
            type="button"
            className="rounded border px-2 py-1 text-sm"
            disabled={busy}
            onClick={() => void submitCreate()}
          >
            新建
          </button>
        </div>
        {createErrors.length > 0 ? (
          <ul className="text-destructive text-xs">
            {createErrors.map((e) => (
              <li key={e}>{e}</li>
            ))}
          </ul>
        ) : null}
        {createResult ? (
          <div className="space-y-1 text-sm">
            <p>
              广告组「{createResult.groupName}」已本地落库，状态 {groupStatusLabel(createResult.status)}，
              平台侧 id {createResult.externalGroupId}。
            </p>
            <p className="text-muted-foreground text-xs">{createResult.note}</p>
          </div>
        ) : null}
      </Section>

      {/* ================= 详情 ================= */}
      {selectedId && detail ? (
        <Section title={`广告组详情 · ${detail.group.groupName}`}>
          <dl className="grid grid-cols-2 gap-3 md:grid-cols-4">
            <Stat label="活动" value={`${detail.group.campaignName}（${detail.group.shopCode}）`} />
            <Stat label="状态" value={groupStatusLabel(detail.group.status)} />
            <Stat
              label="默认竞价"
              value={money(detail.group.defaultBid, detail.group.bidCurrency)}
            />
            <Stat
              label="组级日预算"
              value={groupBudgetText(detail.group.dailyBudget, detail.group.currency)}
            />
            <Stat label="定向类型（原文）" value={detail.group.targetingType || '—'} />
            <Stat label="匹配类型（原文）" value={detail.group.matchType || '—'} />
            <Stat label="定向设置（原文）" value={detail.group.targetingSettings || '—'} />
            <Stat label="组内创意" value={String(detail.creativeCount)} />
            <Stat label="回写状态" value={pendingWritebackText(detail.group.pendingPlatformWriteback)} />
            <Stat label="本地改动时刻" value={timestampText(detail.group.localChangedAt)} />
            <Stat label="结构截至" value={timestampText(detail.group.dataAsOf)} />
            <Stat label="平台侧 id" value={detail.group.externalGroupId} />
          </dl>

          {/* 🔴 组级表现：拿不到就写拿不到 */}
          <div className="space-y-1">
            <h3 className="font-medium text-sm">组级表现</h3>
            <p className="text-muted-foreground text-xs whitespace-pre-line">
              {groupPerformanceText(detail.performance)}
            </p>
          </div>

          {/* 🔴 组级报表入口 */}
          <div className="space-y-1">
            <h3 className="font-medium text-sm">组级报表入口</h3>
            <p className="text-muted-foreground text-xs whitespace-pre-line">
              {groupReportEntryText(detail.reportEntry)}
            </p>
          </div>

          {/* 下游入口 */}
          <div className="space-y-1">
            <h3 className="font-medium text-sm">下游入口</h3>
            <ul className="space-y-1 text-xs">
              {detail.entries.map((e) => (
                <li key={e.entry}>
                  <span className={e.available ? '' : 'text-muted-foreground'}>
                    {e.label}：{e.available ? '可用' : '不可用'}
                  </span>
                  {e.available ? (
                    <span className="text-muted-foreground"> （{e.source}）{e.note}</span>
                  ) : (
                    <span className="text-muted-foreground">
                      {' '}
                      — 责任模块 {e.ownerModule}；{e.impact}
                    </span>
                  )}
                </li>
              ))}
            </ul>
          </div>

          {/* 投放商品 */}
          <div className="space-y-1">
            <h3 className="font-medium text-sm">投放商品（{detail.products.length}）</h3>
            {detail.products.length === 0 ? (
              <p className="text-muted-foreground text-xs">
                这个组还没有关联投放商品。
              </p>
            ) : (
              <table className="w-full text-sm">
                <thead>
                  <tr className="text-muted-foreground border-b text-left text-xs">
                    <th className="p-2">商品</th>
                    <th className="p-2">平台侧 id</th>
                    <th className="p-2">listing 状态</th>
                    <th className="p-2">内部 SKU 映射现状</th>
                  </tr>
                </thead>
                <tbody>
                  {detail.products.map((p) => (
                    <tr key={p.id} className="border-b">
                      <td className="p-2">{p.title || '—'}</td>
                      <td className="p-2">{p.externalItemId || '—'}</td>
                      <td className="p-2">{p.listingStatus || '—'}</td>
                      <td className="p-2">
                        {skuMappingText(p)}
                        <div className="text-muted-foreground text-xs">{p.attributionNote}</div>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
          </div>

          {/* 改组设置 */}
          <div className="space-y-2">
            <h3 className="font-medium text-sm">改组设置</h3>
            <div className="flex flex-wrap items-end gap-2">
              <Field label="组名">
                <input
                  className="rounded border px-2 py-1 text-sm"
                  value={settingsInput.groupName}
                  onChange={(e) =>
                    setSettingsInput({ ...settingsInput, groupName: e.target.value })
                  }
                />
              </Field>
              <Field label="状态">
                <select
                  className="rounded border px-2 py-1 text-sm"
                  value={settingsInput.status}
                  onChange={(e) =>
                    setSettingsInput({ ...settingsInput, status: e.target.value })
                  }
                >
                  {(options?.statuses ?? []).map((s) => (
                    <option key={s.value} value={s.value}>
                      {s.label}
                    </option>
                  ))}
                </select>
              </Field>
              <Field label="组级日预算">
                <input
                  className="rounded border px-2 py-1 text-sm"
                  value={settingsInput.dailyBudget}
                  onChange={(e) =>
                    setSettingsInput({ ...settingsInput, dailyBudget: e.target.value })
                  }
                  placeholder="留空 = 不改"
                />
              </Field>
              <label className="flex items-center gap-1 text-sm">
                <input
                  type="checkbox"
                  checked={settingsInput.clearDailyBudget}
                  onChange={(e) =>
                    setSettingsInput({
                      ...settingsInput,
                      clearDailyBudget: e.target.checked,
                      dailyBudget: e.target.checked ? '' : settingsInput.dailyBudget,
                    })
                  }
                />
                恢复继承活动预算
              </label>
              <Field label="定向类型（原文）">
                <input
                  className="rounded border px-2 py-1 text-sm"
                  value={settingsInput.targetingType}
                  onChange={(e) =>
                    setSettingsInput({ ...settingsInput, targetingType: e.target.value })
                  }
                />
              </Field>
              <Field label="匹配类型（原文）">
                <input
                  className="rounded border px-2 py-1 text-sm"
                  value={settingsInput.matchType}
                  onChange={(e) =>
                    setSettingsInput({ ...settingsInput, matchType: e.target.value })
                  }
                />
              </Field>
              <Field label="定向设置（原文）">
                <input
                  className="rounded border px-2 py-1 text-sm"
                  value={settingsInput.targetingSettings}
                  onChange={(e) =>
                    setSettingsInput({ ...settingsInput, targetingSettings: e.target.value })
                  }
                />
              </Field>
              <Field label="理由">
                <input
                  className="rounded border px-2 py-1 text-sm"
                  value={settingsInput.reason}
                  onChange={(e) => setSettingsInput({ ...settingsInput, reason: e.target.value })}
                />
              </Field>
              <button
                type="button"
                className="rounded border px-2 py-1 text-sm"
                disabled={busy}
                onClick={() => void submitSettings()}
              >
                保存设置
              </button>
            </div>
            {settingsErrors.length > 0 ? (
              <ul className="text-destructive text-xs">
                {settingsErrors.map((e) => (
                  <li key={e}>{e}</li>
                ))}
              </ul>
            ) : null}
            <p className="text-muted-foreground text-xs">
              🔴 默认竞价**不在**这里改：改竞价是钱，走 14.1.1.2 的改竞价入口（带护栏与审计）。
            </p>
          </div>

          {/* 关联投放商品 */}
          <div className="space-y-2">
            <h3 className="font-medium text-sm">关联投放商品</h3>
            <div className="flex flex-wrap items-end gap-2">
              <Field label="候选商品关键词">
                <input
                  className="rounded border px-2 py-1 text-sm"
                  value={candidateKeyword}
                  onChange={(e) => setCandidateKeyword(e.target.value)}
                  placeholder="只列该活动所在店铺的 listing"
                />
              </Field>
              <button
                type="button"
                className="rounded border px-2 py-1 text-sm"
                onClick={() => void loadCandidates(detail.group.campaignId, detail.group.id)}
              >
                拉候选
              </button>
              <Field label="理由">
                <input
                  className="rounded border px-2 py-1 text-sm"
                  value={productReason}
                  onChange={(e) => setProductReason(e.target.value)}
                />
              </Field>
              <button
                type="button"
                className="rounded border px-2 py-1 text-sm"
                disabled={busy}
                onClick={() => void submitProducts()}
              >
                保存投放商品（替换语义）
              </button>
            </div>
            {candidates.length === 0 ? (
              <p className="text-muted-foreground text-xs">
                还没有拉候选商品 —— 点「拉候选」按该活动所在店铺的 listing 选。
              </p>
            ) : (
              <ul className="space-y-1 text-sm">
                {candidates.map((c) => (
                  <li key={c.listingId}>
                    <label className="flex items-center gap-2">
                      <input
                        type="checkbox"
                        checked={selectedListings.includes(c.listingId)}
                        onChange={(e) =>
                          setSelectedListings((prev) =>
                            e.target.checked
                              ? [...prev, c.listingId]
                              : prev.filter((id) => id !== c.listingId),
                          )
                        }
                      />
                      <span>{c.title || c.externalItemId}</span>
                      <span className="text-muted-foreground text-xs">{c.externalItemId}</span>
                      {c.selected ? (
                        <span className="text-muted-foreground text-xs">（已挂在这个组上）</span>
                      ) : null}
                    </label>
                  </li>
                ))}
              </ul>
            )}
          </div>

          {/* 操作审计 */}
          <div className="space-y-1">
            <h3 className="font-medium text-sm">组级操作审计（最近 {detail.recentOperations.length} 条）</h3>
            {detail.recentOperations.length === 0 ? (
              <p className="text-muted-foreground text-xs">这个组还没有本地改动记录。</p>
            ) : (
              <table className="w-full text-sm">
                <thead>
                  <tr className="text-muted-foreground border-b text-left text-xs">
                    <th className="p-2">时刻</th>
                    <th className="p-2">动作</th>
                    <th className="p-2">结果</th>
                    <th className="p-2">说明</th>
                    <th className="p-2">理由</th>
                  </tr>
                </thead>
                <tbody>
                  {detail.recentOperations.map((o) => (
                    <tr key={o.id} className="border-b">
                      <td className="p-2">{timestampText(o.createdAt)}</td>
                      <td className="p-2">{groupOperationLabel(o.operation)}</td>
                      <td className={`p-2 ${operationResultTone(o.result)}`}>
                        {operationResultLabel(o.result)}
                      </td>
                      <td className="p-2">{o.message}</td>
                      <td className="p-2">{o.reason || '—'}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
          </div>

          <p className="text-muted-foreground text-xs">{detail.note}</p>
        </Section>
      ) : null}
    </main>
  );
}

function Section({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <section className="flex flex-col gap-3 rounded border p-4">
      <h2 className="font-medium">{title}</h2>
      {children}
    </section>
  );
}

function Stat({ label, value }: { label: string; value: string }) {
  return (
    <div>
      <dt className="text-muted-foreground text-xs">{label}</dt>
      <dd>{value}</dd>
    </div>
  );
}

function Field({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <label className="flex flex-col gap-1 text-sm">
      <span>{label}</span>
      {children}
    </label>
  );
}
