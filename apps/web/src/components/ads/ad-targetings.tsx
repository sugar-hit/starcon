'use client';

import { useCallback, useEffect, useState } from 'react';
import { errorText } from '../inventory/inventory-model';
import {
  archiveAdTargeting,
  bidFactorText,
  countText,
  createAdTargeting,
  daypartWindowText,
  dimensionLabel,
  emptyAdTargetingCreateInput,
  emptyAdTargetingInterpretInput,
  emptyAdTargetingMetricInput,
  fetchAdTargetingCapabilities,
  fetchAdTargetingDaypartProfile,
  fetchAdTargetingDetail,
  fetchAdTargetingEffects,
  fetchAdTargetingOptions,
  fetchAdTargetings,
  fetchAdTargetingSuggestions,
  fetchAdTargetingSummary,
  ingestAdTargetingMetrics,
  interpretAdTargeting,
  money,
  narrowAdTargeting,
  pendingWritebackText,
  percentText,
  ratioText,
  scopeLabel,
  suggestionActionLabel,
  suggestionRuleLabel,
  suggestionSeverityLabel,
  suggestionSeverityTone,
  targetingOperationLabel,
  targetingStatusLabel,
  timestampText,
  updateAdTargeting,
  validateAdTargetingInterpret,
  validateAdTargetingMetric,
  validateTargetingCreate,
} from './ad-targeting-model';
import type {
  AdTargetingCapabilities,
  AdTargetingCreateInput,
  AdTargetingDaypartProfile,
  AdTargetingDetail,
  AdTargetingEffectPage,
  AdTargetingInterpretInput,
  AdTargetingInterpretResult,
  AdTargetingMetricBatchResult,
  AdTargetingMetricInput,
  AdTargetingOptions,
  AdTargetingSettingRow,
  AdTargetingSettingSummary,
  AdTargetingSuggestionResult,
} from './ad-targeting-model';

/**
 * 14.1.3.2 投放定位。
 *
 * 🔴 页面顶部那段话不是提示语，是这一页的设计前提：
 *
 * 1. **平台原文 ≠ 归一定位**。上游 V281 的 `targetingSettings` 是**平台原文串**，
 *    本模块负责把它**拆解 + 归一**成定位维度；原文一字不改地留在上游列里，
 *    归一结果落在本模块自己的表上，并保留原文以便回溯。
 * 2. **「不可比」≠ 0**。销售额为 0 时 ACOS 是 `null`（算不出来），页面显示「不可比」。
 * 3. **跨币种不相加**。对比表按币种分行，汇总的金额按币种分列。
 * 4. **建议必须有依据**。每条建议都带依据数值与口径；`actionable=false` 的**不能照做**。
 * 5. **本地已收窄 ≠ 平台侧已收窄**。平台回写队列未交付 ⇒ 本地写只落本地，页面显式标出。
 */
export function AdTargetingsWorkspace() {
  const [capabilities, setCapabilities] = useState<AdTargetingCapabilities | null>(null);
  const [options, setOptions] = useState<AdTargetingOptions | null>(null);

  // ---- 定位设置列表 ----
  const [settings, setSettings] = useState<AdTargetingSettingRow[]>([]);
  const [summary, setSummary] = useState<AdTargetingSettingSummary | null>(null);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(0);
  const [size] = useState(20);

  const [campaignId, setCampaignId] = useState('');
  const [dimension, setDimension] = useState('');
  const [status, setStatus] = useState('');
  const [keyword, setKeyword] = useState('');
  const [sort, setSort] = useState('updatedAtDesc');

  // ---- 效果对比表 ----
  const [effects, setEffects] = useState<AdTargetingEffectPage | null>(null);
  const [effectCampaignId, setEffectCampaignId] = useState('');
  const [effectDimension, setEffectDimension] = useState('');
  const [effectCurrency, setEffectCurrency] = useState('');
  const [from, setFrom] = useState('');
  const [to, setTo] = useState('');
  const [effectSort, setEffectSort] = useState('spendDesc');
  const [effectPage, setEffectPage] = useState(0);

  // ---- 建议 / 时段画像 ----
  const [suggestions, setSuggestions] = useState<AdTargetingSuggestionResult | null>(null);
  const [dayparts, setDayparts] = useState<AdTargetingDaypartProfile | null>(null);

  // ---- 详情 ----
  const [detail, setDetail] = useState<AdTargetingDetail | null>(null);

  // ---- 写表单 ----
  const [createInput, setCreateInput] = useState<AdTargetingCreateInput>(emptyAdTargetingCreateInput);
  const [createErrors, setCreateErrors] = useState<string[]>([]);
  const [interpretInput, setInterpretInput] = useState<AdTargetingInterpretInput>(
    emptyAdTargetingInterpretInput,
  );
  const [interpretErrors, setInterpretErrors] = useState<string[]>([]);
  const [interpretResult, setInterpretResult] = useState<AdTargetingInterpretResult | null>(null);
  const [metricInput, setMetricInput] = useState<AdTargetingMetricInput>(emptyAdTargetingMetricInput);
  const [metricErrors, setMetricErrors] = useState<string[]>([]);
  const [metricResult, setMetricResult] = useState<AdTargetingMetricBatchResult | null>(null);
  const [narrowReason, setNarrowReason] = useState('');
  const [narrowOverride, setNarrowOverride] = useState(false);

  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');

  const listQuery = {
    campaignId: campaignId || undefined,
    dimension: dimension || undefined,
    status: status || undefined,
    keyword: keyword || undefined,
    sort,
    page,
    size,
  };

  const load = useCallback(async () => {
    setLoading(true);
    setError('');
    try {
      const [list, sum] = await Promise.all([
        fetchAdTargetings(listQuery),
        fetchAdTargetingSummary(listQuery),
      ]);
      setSettings(list.items);
      setTotal(list.total);
      setSummary(sum);
    } catch (e) {
      setError(errorText(e));
    } finally {
      setLoading(false);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [campaignId, dimension, status, keyword, sort, page, size]);

  useEffect(() => {
    void load();
  }, [load]);

  useEffect(() => {
    let alive = true;
    void (async () => {
      try {
        const [caps, opts] = await Promise.all([
          fetchAdTargetingCapabilities(),
          fetchAdTargetingOptions(),
        ]);
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

  const effectQuery = {
    campaignId: effectCampaignId || undefined,
    dimension: effectDimension || undefined,
    currency: effectCurrency || undefined,
    from: from || undefined,
    to: to || undefined,
    sort: effectSort,
    page: effectPage,
    size,
  };

  const loadEffects = useCallback(async () => {
    setError('');
    try {
      setEffects(await fetchAdTargetingEffects(effectQuery));
    } catch (e) {
      setError(errorText(e));
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [effectCampaignId, effectDimension, effectCurrency, from, to, effectSort, effectPage, size]);

  const loadSuggestions = useCallback(async () => {
    setError('');
    try {
      const query = {
        campaignId: effectCampaignId || undefined,
        dimension: effectDimension || undefined,
        from: from || undefined,
        to: to || undefined,
      };
      const [sug, profile] = await Promise.all([
        fetchAdTargetingSuggestions(query),
        fetchAdTargetingDaypartProfile(query),
      ]);
      setSuggestions(sug);
      setDayparts(profile);
    } catch (e) {
      setError(errorText(e));
    }
  }, [effectCampaignId, effectDimension, from, to]);

  useEffect(() => {
    void loadEffects();
  }, [loadEffects]);

  async function loadDetail(settingId: string) {
    setError('');
    try {
      setDetail(await fetchAdTargetingDetail(settingId));
    } catch (e) {
      setError(errorText(e));
    }
  }

  async function submitCreate() {
    const check = validateTargetingCreate(createInput);
    setCreateErrors(check.errors);
    if (!check.ok) return;
    setBusy(true);
    setError('');
    try {
      await createAdTargeting({
        campaignId: createInput.campaignId.trim(),
        groupId: createInput.groupId.trim() || undefined,
        dimension: createInput.dimension.trim(),
        dimensionValue: createInput.dimensionValue.trim(),
        platformRawKey: createInput.platformRawKey.trim() || undefined,
        platformRawValue: createInput.platformRawValue.trim() || undefined,
        bidFactor: createInput.bidFactor.trim() ? Number(createInput.bidFactor) : undefined,
        reason: createInput.reason.trim() || undefined,
      });
      setCreateInput({ ...emptyAdTargetingCreateInput, campaignId: createInput.campaignId });
      await load();
    } catch (e) {
      setError(errorText(e));
    } finally {
      setBusy(false);
    }
  }

  async function submitInterpret() {
    const check = validateAdTargetingInterpret(interpretInput);
    setInterpretErrors(check.errors);
    if (!check.ok) return;
    setBusy(true);
    setError('');
    try {
      const result = await interpretAdTargeting({
        campaignId: interpretInput.campaignId.trim(),
        groupId: interpretInput.groupId.trim() || undefined,
        targetingSettings: interpretInput.targetingSettings.trim() || undefined,
        matchType: interpretInput.matchType.trim() || undefined,
        apply: interpretInput.apply,
        reason: interpretInput.reason.trim() || undefined,
      });
      setInterpretResult(result);
      if (interpretInput.apply) await load();
    } catch (e) {
      setError(errorText(e));
    } finally {
      setBusy(false);
    }
  }

  async function submitMetrics() {
    const check = validateAdTargetingMetric(metricInput);
    setMetricErrors(check.errors);
    if (!check.ok) return;
    setBusy(true);
    setError('');
    try {
      const result = await ingestAdTargetingMetrics({
        entries: [
          {
            settingId: metricInput.settingId.trim(),
            bucketDate: metricInput.bucketDate.trim(),
            impressions: Number(metricInput.impressions.trim() || '0'),
            clicks: Number(metricInput.clicks.trim() || '0'),
            conversions: Number(metricInput.conversions.trim() || '0'),
            spend: metricInput.spend.trim() ? Number(metricInput.spend) : undefined,
            sales: metricInput.sales.trim() ? Number(metricInput.sales) : undefined,
            currency: metricInput.currency.trim(),
            note: metricInput.reason.trim() || undefined,
          },
        ],
        batchKey: metricInput.batchKey.trim() || undefined,
        reason: metricInput.reason.trim() || undefined,
      });
      setMetricResult(result);
      await loadEffects();
    } catch (e) {
      setError(errorText(e));
    } finally {
      setBusy(false);
    }
  }

  async function submitUpdate(setting: AdTargetingSettingRow, status: string) {
    setBusy(true);
    setError('');
    try {
      await updateAdTargeting(setting.id, { status, reason: `在页面上把状态改成 ${status}` });
      await load();
      await loadDetail(setting.id);
    } catch (e) {
      setError(errorText(e));
    } finally {
      setBusy(false);
    }
  }

  async function submitArchive(setting: AdTargetingSettingRow) {
    setBusy(true);
    setError('');
    try {
      await archiveAdTargeting(setting.id, '在页面上归档');
      await load();
      await loadDetail(setting.id);
    } catch (e) {
      setError(errorText(e));
    } finally {
      setBusy(false);
    }
  }

  async function submitNarrow(setting: AdTargetingSettingRow) {
    setBusy(true);
    setError('');
    try {
      await narrowAdTargeting(setting.id, {
        reason: narrowReason.trim() || undefined,
        override: narrowOverride ? true : undefined,
      });
      setNarrowReason('');
      setNarrowOverride(false);
      await load();
      await loadSuggestions();
      if (detail && detail.setting.id === setting.id) await loadDetail(setting.id);
    } catch (e) {
      setError(errorText(e));
    } finally {
      setBusy(false);
    }
  }

  return (
    <main className="flex flex-col gap-4 p-4">
      <header className="flex flex-col gap-1">
        <h1 className="text-lg font-medium">投放定位</h1>
        <p className="text-muted-foreground text-sm">
          管理投放定位（地域 / 人群 / 设备 / 时段 / 匹配类型），支持按定位维度看效果，指导出价调整。
          <strong>定位效果差异大时应收窄投放，无效定位的花费是最容易省下来的钱。</strong>
        </p>
      </header>

      {error ? (
        <p className="rounded border border-destructive/40 p-2 text-destructive text-sm">{error}</p>
      ) : null}

      {/* ================= 能力声明 ================= */}
      <Section title="能力声明">
        {capabilities ? (
          <div className="flex flex-col gap-2">
            <table className="w-full text-sm">
              <thead>
                <tr className="text-muted-foreground border-b text-left text-xs">
                  <th className="p-2">维度</th>
                  <th className="p-2">说明</th>
                  <th className="p-2">是否已交付</th>
                  <th className="p-2">来源 / 责任模块</th>
                </tr>
              </thead>
              <tbody>
                {capabilities.dimensions.map((d) => (
                  <tr key={d.dimension} className="border-b">
                    <td className="p-2">{d.dimension}</td>
                    <td className="p-2">
                      {d.description}
                      {d.available ? '' : <div className="text-muted-foreground text-xs">{d.impact}</div>}
                    </td>
                    <td className="p-2">
                      {d.available ? (
                        <span className="text-emerald-600 text-xs">已交付</span>
                      ) : (
                        <span className="text-amber-600 text-xs">未交付</span>
                      )}
                    </td>
                    <td className="p-2 text-xs">
                      {d.source}
                      {d.ownerModule ? <div>责任模块：{d.ownerModule}</div> : null}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
            <p className="text-muted-foreground text-xs whitespace-pre-line">{capabilities.interpretationNote}</p>
            <p className="text-muted-foreground text-xs whitespace-pre-line">{capabilities.platformWritebackNote}</p>
            <p className="text-muted-foreground text-xs whitespace-pre-line">{capabilities.guardrails.note}</p>
          </div>
        ) : (
          <p className="text-muted-foreground text-sm">正在读能力声明…</p>
        )}
      </Section>

      {/* ================= 定位设置 ================= */}
      <Section title="定位设置">
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
          <Field label="定位维度">
            <select
              className="rounded border px-2 py-1 text-sm"
              value={dimension}
              onChange={(e) => {
                setPage(0);
                setDimension(e.target.value);
              }}
            >
              <option value="">全部</option>
              {(options?.dimensions ?? []).map((d) => (
                <option key={d.value} value={d.value}>
                  {d.label}
                </option>
              ))}
            </select>
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
              placeholder="取值或平台原文"
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
          <button type="button" className="rounded border px-2 py-1 text-sm" onClick={() => void load()}>
            刷新
          </button>
        </div>

        {summary ? (
          <p className="text-muted-foreground text-xs whitespace-pre-line">
            汇总：共 {summary.total} 条，覆盖 {summary.campaignCount} 个活动，其中{' '}
            {summary.pendingWritebackCount} 条标着「本地已改、尚未回写平台」。
            {summary.note}
          </p>
        ) : null}

        {loading ? (
          <p className="text-muted-foreground text-sm">正在读定位设置…</p>
        ) : settings.length === 0 ? (
          <p className="text-muted-foreground text-sm">
            没有符合条件的定位设置。这**不等于**「没有投放」—— 定位设置来自平台原文解读与人工登记；
            先用下面的「解读平台原文」把上游的 targeting_settings 拆成定位维度，或者换个筛选条件再看看。
          </p>
        ) : (
          <table className="w-full text-sm">
            <thead>
              <tr className="text-muted-foreground border-b text-left text-xs">
                <th className="p-2">定位</th>
                <th className="p-2">归属</th>
                <th className="p-2">状态</th>
                <th className="p-2">出价系数</th>
                <th className="p-2">平台原文</th>
                <th className="p-2">时段窗口</th>
                <th className="p-2">回写</th>
                <th className="p-2">操作</th>
              </tr>
            </thead>
            <tbody>
              {settings.map((row) => (
                <tr key={row.id} className="border-b">
                  <td className="p-2">
                    <button
                      type="button"
                      className="underline"
                      onClick={() => void loadDetail(row.id)}
                    >
                      {dimensionLabel(row.dimension)}：{row.dimensionValue}
                    </button>
                  </td>
                  <td className="p-2">
                    {scopeLabel(row.scopeKind)}
                    <div className="text-muted-foreground text-xs">
                      {row.campaignName}
                      {row.groupName ? ` / ${row.groupName}` : ''}
                    </div>
                  </td>
                  <td className="p-2">{targetingStatusLabel(row.status)}</td>
                  <td className="p-2">{bidFactorText(row.bidFactor)}</td>
                  <td className="p-2 text-xs">
                    {row.platformRawKey ? `${row.platformRawKey}=${row.platformRawValue}` : '—'}
                  </td>
                  <td className="p-2 text-xs">
                    {row.dimension === 'DAYPART'
                      ? daypartWindowText(
                          row.daypartStartHour,
                          row.daypartEndHour,
                          row.daypartHours,
                          row.daypartCrossesMidnight,
                        )
                      : '—'}
                  </td>
                  <td className="p-2">
                    {row.pendingPlatformWriteback ? (
                      <span className="text-amber-600 text-xs">待回写平台</span>
                    ) : (
                      <span className="text-muted-foreground text-xs">—</span>
                    )}
                  </td>
                  <td className="p-2">
                    <div className="flex flex-wrap gap-1">
                      {row.status === 'ACTIVE' ? (
                        <>
                          <button
                            type="button"
                            className="rounded border px-2 py-1 text-xs"
                            disabled={busy}
                            onClick={() => void submitNarrow(row)}
                          >
                            收窄投放
                          </button>
                          <button
                            type="button"
                            className="rounded border px-2 py-1 text-xs"
                            disabled={busy}
                            onClick={() => void submitUpdate(row, 'PAUSED')}
                          >
                            暂停
                          </button>
                        </>
                      ) : null}
                      {row.status !== 'ARCHIVED' ? (
                        <button
                          type="button"
                          className="rounded border px-2 py-1 text-xs"
                          disabled={busy}
                          onClick={() => void submitArchive(row)}
                        >
                          归档
                        </button>
                      ) : null}
                    </div>
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

        <div className="flex flex-wrap items-end gap-2">
          <Field label="收窄理由（写进留痕）">
            <input
              className="rounded border px-2 py-1 text-sm"
              value={narrowReason}
              onChange={(e) => setNarrowReason(e.target.value)}
              placeholder="收窄投放的理由"
            />
          </Field>
          <label className="flex items-center gap-1 text-sm">
            <input
              type="checkbox"
              checked={narrowOverride}
              onChange={(e) => setNarrowOverride(e.target.checked)}
            />
            证据不足也要收窄（人工决定，留痕会记下）
          </label>
        </div>
      </Section>

      {/* ================= 新建定位设置 ================= */}
      <Section title="新建定位设置">
        <div className="flex flex-wrap items-end gap-2">
          <Field label="活动 id">
            <input
              className="rounded border px-2 py-1 text-sm"
              value={createInput.campaignId}
              onChange={(e) => setCreateInput({ ...createInput, campaignId: e.target.value })}
              placeholder="所属活动"
            />
          </Field>
          <Field label="广告组 id（留空 = 活动级）">
            <input
              className="rounded border px-2 py-1 text-sm"
              value={createInput.groupId}
              onChange={(e) => setCreateInput({ ...createInput, groupId: e.target.value })}
            />
          </Field>
          <Field label="定位维度">
            <select
              className="rounded border px-2 py-1 text-sm"
              value={createInput.dimension}
              onChange={(e) => setCreateInput({ ...createInput, dimension: e.target.value })}
            >
              {(options?.dimensions ?? []).map((d) => (
                <option key={d.value} value={d.value}>
                  {d.label}
                </option>
              ))}
            </select>
          </Field>
          <Field label="定位取值">
            <input
              className="rounded border px-2 py-1 text-sm"
              value={createInput.dimensionValue}
              onChange={(e) => setCreateInput({ ...createInput, dimensionValue: e.target.value })}
              placeholder="如 US / MOBILE / 09:00-17:00"
            />
          </Field>
          <Field label="平台原文键">
            <input
              className="rounded border px-2 py-1 text-sm"
              value={createInput.platformRawKey}
              onChange={(e) => setCreateInput({ ...createInput, platformRawKey: e.target.value })}
            />
          </Field>
          <Field label="平台原文取值">
            <input
              className="rounded border px-2 py-1 text-sm"
              value={createInput.platformRawValue}
              onChange={(e) => setCreateInput({ ...createInput, platformRawValue: e.target.value })}
            />
          </Field>
          <Field label="出价系数（留空 = 1）">
            <input
              className="rounded border px-2 py-1 text-sm"
              value={createInput.bidFactor}
              onChange={(e) => setCreateInput({ ...createInput, bidFactor: e.target.value })}
            />
          </Field>
          <Field label="理由">
            <input
              className="rounded border px-2 py-1 text-sm"
              value={createInput.reason}
              onChange={(e) => setCreateInput({ ...createInput, reason: e.target.value })}
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
      </Section>

      {/* ================= 🔴 解读平台原文 ================= */}
      <Section title="解读平台原文定位串（归一）">
        <p className="text-muted-foreground text-xs">
          上游的 <code>targeting_settings</code> 是**平台原文串**；这里把它拆解 + 归一成定位维度。
          原文留在上游列里不动，归一结果落在本模块自己的表上，并保留原文以便回溯。
        </p>
        <div className="flex flex-wrap items-end gap-2">
          <Field label="活动 id">
            <input
              className="rounded border px-2 py-1 text-sm"
              value={interpretInput.campaignId}
              onChange={(e) => setInterpretInput({ ...interpretInput, campaignId: e.target.value })}
            />
          </Field>
          <Field label="广告组 id（不给就从组里读原文）">
            <input
              className="rounded border px-2 py-1 text-sm"
              value={interpretInput.groupId}
              onChange={(e) => setInterpretInput({ ...interpretInput, groupId: e.target.value })}
            />
          </Field>
          <Field label="平台原文（不给 = 从广告组读）">
            <input
              className="rounded border px-2 py-1 text-sm"
              value={interpretInput.targetingSettings}
              onChange={(e) =>
                setInterpretInput({ ...interpretInput, targetingSettings: e.target.value })
              }
              placeholder="geo=US;audience=premium;device=mobile;schedule=09:00-17:00"
            />
          </Field>
          <Field label="匹配类型原文">
            <input
              className="rounded border px-2 py-1 text-sm"
              value={interpretInput.matchType}
              onChange={(e) => setInterpretInput({ ...interpretInput, matchType: e.target.value })}
              placeholder="BROAD"
            />
          </Field>
          <label className="flex items-center gap-1 text-sm">
            <input
              type="checkbox"
              checked={interpretInput.apply}
              onChange={(e) => setInterpretInput({ ...interpretInput, apply: e.target.checked })}
            />
            解读后落库（不勾 = 只预览）
          </label>
          <Field label="理由">
            <input
              className="rounded border px-2 py-1 text-sm"
              value={interpretInput.reason}
              onChange={(e) => setInterpretInput({ ...interpretInput, reason: e.target.value })}
            />
          </Field>
          <button
            type="button"
            className="rounded border px-2 py-1 text-sm"
            disabled={busy}
            onClick={() => void submitInterpret()}
          >
            解读
          </button>
        </div>
        {interpretErrors.length > 0 ? (
          <ul className="text-destructive text-xs">
            {interpretErrors.map((e) => (
              <li key={e}>{e}</li>
            ))}
          </ul>
        ) : null}
        {interpretResult ? (
          <div className="flex flex-col gap-2">
            <p className="text-muted-foreground text-xs whitespace-pre-line">{interpretResult.note}</p>
            {interpretResult.items.length === 0 ? (
              <p className="text-muted-foreground text-sm">平台原文里没有可解读的定位内容。</p>
            ) : (
              <table className="w-full text-sm">
                <thead>
                  <tr className="text-muted-foreground border-b text-left text-xs">
                    <th className="p-2">归一定位</th>
                    <th className="p-2">识别</th>
                    <th className="p-2">平台原文</th>
                    <th className="p-2">时段窗口</th>
                    <th className="p-2">说明</th>
                  </tr>
                </thead>
                <tbody>
                  {interpretResult.items.map((row, index) => (
                    <tr key={`${row.dimension}-${row.dimensionValue}-${index}`} className="border-b">
                      <td className="p-2">
                        {row.recognized ? dimensionLabel(row.dimension) : '未识别'}：{row.dimensionValue}
                      </td>
                      <td className="p-2">
                        {row.recognized ? (
                          <span className="text-emerald-600 text-xs">已识别</span>
                        ) : (
                          <span className="text-amber-600 text-xs">未识别</span>
                        )}
                      </td>
                      <td className="p-2 text-xs">
                        {row.platformRawKey ? `${row.platformRawKey}=${row.platformRawValue}` : '—'}
                      </td>
                      <td className="p-2 text-xs">
                        {row.dimension === 'DAYPART'
                          ? daypartWindowText(
                              row.daypartStartHour,
                              row.daypartEndHour,
                              row.daypartHours,
                              row.daypartCrossesMidnight,
                            )
                          : '—'}
                      </td>
                      <td className="p-2 text-xs whitespace-pre-line">{row.note}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
          </div>
        ) : null}
      </Section>

      {/* ================= 🔴 定位效果对比表 ================= */}
      <Section title="定位效果对比表">
        <div className="flex flex-wrap items-end gap-2">
          <Field label="活动 id">
            <input
              className="rounded border px-2 py-1 text-sm"
              value={effectCampaignId}
              onChange={(e) => {
                setEffectPage(0);
                setEffectCampaignId(e.target.value);
              }}
            />
          </Field>
          <Field label="定位维度">
            <select
              className="rounded border px-2 py-1 text-sm"
              value={effectDimension}
              onChange={(e) => {
                setEffectPage(0);
                setEffectDimension(e.target.value);
              }}
            >
              <option value="">全部</option>
              {(options?.dimensions ?? []).map((d) => (
                <option key={d.value} value={d.value}>
                  {d.label}
                </option>
              ))}
            </select>
          </Field>
          <Field label="币种">
            <input
              className="rounded border px-2 py-1 text-sm"
              value={effectCurrency}
              onChange={(e) => {
                setEffectPage(0);
                setEffectCurrency(e.target.value);
              }}
              placeholder="USD"
            />
          </Field>
          <Field label="开始（UTC 日）">
            <input
              className="rounded border px-2 py-1 text-sm"
              value={from}
              onChange={(e) => {
                setEffectPage(0);
                setFrom(e.target.value);
              }}
              placeholder="2026-10-01"
            />
          </Field>
          <Field label="结束（UTC 日）">
            <input
              className="rounded border px-2 py-1 text-sm"
              value={to}
              onChange={(e) => {
                setEffectPage(0);
                setTo(e.target.value);
              }}
              placeholder="2026-10-31"
            />
          </Field>
          <Field label="排序">
            <select
              className="rounded border px-2 py-1 text-sm"
              value={effectSort}
              onChange={(e) => {
                setEffectPage(0);
                setEffectSort(e.target.value);
              }}
            >
              <option value="spendDesc">花费从高到低</option>
              <option value="spendAsc">花费从低到高</option>
              <option value="acosDesc">ACOS 从高到低</option>
              <option value="acosAsc">ACOS 从低到高</option>
              <option value="wastedSpendDesc">无转化花费从高到低</option>
              <option value="clicksDesc">点击从高到低</option>
              <option value="conversionsDesc">转化从高到低</option>
              <option value="salesDesc">销售额从高到低</option>
              <option value="dimensionAsc">维度 / 取值</option>
            </select>
          </Field>
          <button
            type="button"
            className="rounded border px-2 py-1 text-sm"
            onClick={() => void loadEffects()}
          >
            刷新
          </button>
          <button
            type="button"
            className="rounded border px-2 py-1 text-sm"
            onClick={() => void loadSuggestions()}
          >
            读建议与时段画像
          </button>
        </div>

        {effects ? (
          <>
            <p className="text-muted-foreground text-xs whitespace-pre-line">{effects.summary.note}</p>
            {effects.items.length === 0 ? (
              <p className="text-muted-foreground text-sm">
                这个区间 / 筛选下**没有定位效果数据** ⇒ 没有可比的效果，也没有建议。
                先用下面的「同步定位维度效果数据」把数据落进来，或换个时间范围。
              </p>
            ) : (
              <table className="w-full text-sm">
                <thead>
                  <tr className="text-muted-foreground border-b text-left text-xs">
                    <th className="p-2">定位</th>
                    <th className="p-2">活动 / 组</th>
                    <th className="p-2">币种</th>
                    <th className="p-2">展示 / 点击 / 转化</th>
                    <th className="p-2">花费 / 销售额</th>
                    <th className="p-2">CTR / CPC</th>
                    <th className="p-2">ACOS / ROAS</th>
                    <th className="p-2">无转化花费</th>
                    <th className="p-2">占同维度花费</th>
                    <th className="p-2">数据截至</th>
                  </tr>
                </thead>
                <tbody>
                  {effects.items.map((row) => (
                    <tr key={`${row.campaignId}-${row.groupId ?? 'campaign'}-${row.dimension}-${row.dimensionValue}-${row.currency}`} className="border-b">
                      <td className="p-2">
                        {dimensionLabel(row.dimension)}：{row.dimensionValue}
                        <div className="text-muted-foreground text-xs">{scopeLabel(row.scopeKind)}</div>
                      </td>
                      <td className="p-2">
                        {row.campaignName}
                        <div className="text-muted-foreground text-xs">
                          {row.groupName ?? '活动级'} · {row.shopCode}
                        </div>
                      </td>
                      <td className="p-2">{row.currency}</td>
                      <td className="p-2">
                        {countText(row.impressions)} / {countText(row.clicks)} / {countText(row.conversions)}
                      </td>
                      <td className="p-2">
                        {money(row.spend, row.currency)} / {money(row.sales, row.currency)}
                      </td>
                      <td className="p-2">
                        {percentText(row.ctr)} / {money(row.cpc, row.currency)}
                      </td>
                      <td className="p-2">
                        {ratioText(row.acos, 4)} / {ratioText(row.roas, 2)}
                      </td>
                      <td className="p-2">
                        {money(row.wastedSpend, row.currency)}
                        <div className="text-muted-foreground text-xs">{percentText(row.wastedRatio)}</div>
                      </td>
                      <td className="p-2">{percentText(row.spendShare)}</td>
                      <td className="p-2 text-xs">{timestampText(row.dataAsOf)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}

            <p className="text-muted-foreground text-xs">
              汇总：展示 {countText(effects.summary.impressions)} / 点击{' '}
              {countText(effects.summary.clicks)} / 转化 {countText(effects.summary.conversions)}；
              花费（按币种）
              {Object.entries(effects.summary.spendByCurrency)
                .map(([ccy, value]) => `${ccy} ${money(value)}`)
                .join('、') || '—'}
              ；销售额（按币种）
              {Object.entries(effects.summary.salesByCurrency)
                .map(([ccy, value]) => `${ccy} ${money(value)}`)
                .join('、') || '—'}
            </p>

            <div className="flex items-center gap-2">
              <button
                type="button"
                className="rounded border px-2 py-1 text-sm"
                disabled={effectPage <= 0}
                onClick={() => setEffectPage(Math.max(effectPage - 1, 0))}
              >
                上一页
              </button>
              <span className="text-muted-foreground text-xs">
                第 {effectPage + 1} 页 / 共 {Math.max(Math.ceil(effects.total / size), 1)} 页
              </span>
              <button
                type="button"
                className="rounded border px-2 py-1 text-sm"
                disabled={(effectPage + 1) * size >= effects.total}
                onClick={() => setEffectPage(effectPage + 1)}
              >
                下一页
              </button>
            </div>
          </>
        ) : (
          <p className="text-muted-foreground text-sm">正在读定位效果对比表…</p>
        )}
      </Section>

      {/* ================= 🔴 调整建议 ================= */}
      <Section title="调整建议（带依据）">
        {!suggestions ? (
          <p className="text-muted-foreground text-sm">
            点上面的「读建议与时段画像」读取建议。每条建议都带**依据数值**与**口径**；
            样本不足的建议标成「别照做」。
          </p>
        ) : suggestions.items.length === 0 ? (
          <p className="text-muted-foreground text-sm whitespace-pre-line">{suggestions.note}</p>
        ) : (
          <>
            <p className="text-muted-foreground text-xs whitespace-pre-line">{suggestions.note}</p>
            <ul className="flex flex-col gap-3">
              {suggestions.items.map((s, index) => (
                <li key={`${s.ruleId}-${s.campaignId}-${s.dimension}-${s.dimensionValue}-${index}`} className="rounded border p-3">
                  <div className="flex flex-wrap items-center gap-2">
                    <span className="font-medium">
                      {dimensionLabel(s.dimension)}：{s.dimensionValue}
                    </span>
                    <span className={`text-xs ${suggestionSeverityTone(s.severity)}`}>
                      {suggestionSeverityLabel(s.severity)}
                    </span>
                    <span className="rounded border px-1 text-xs">{suggestionRuleLabel(s.ruleId)}</span>
                    <span className="rounded border px-1 text-xs">建议：{suggestionActionLabel(s.action)}</span>
                    {!s.actionable ? (
                      <span className="rounded border border-amber-600 px-1 text-xs text-amber-600">
                        样本不足，别照做
                      </span>
                    ) : null}
                  </div>
                  <p className="text-muted-foreground text-xs">{s.campaignName} · {s.groupName ?? '活动级'}</p>
                  <ul className="mt-1 list-disc pl-5 text-xs">
                    {s.evidence.map((e) => (
                      <li key={e}>{e}</li>
                    ))}
                  </ul>
                  <p className="text-muted-foreground mt-1 text-xs whitespace-pre-line">{s.basis}</p>
                  {s.bidFactor !== null ? (
                    <p className="text-xs">建议出价系数：{bidFactorText(s.bidFactor)}</p>
                  ) : null}
                  <p className="text-muted-foreground mt-1 text-xs whitespace-pre-line">{s.note}</p>
                </li>
              ))}
            </ul>
          </>
        )}
      </Section>

      {/* ================= 🔴 时段画像（供下游 14.2.1.2） ================= */}
      <Section title="时段画像（供下游分时策略复用）">
        {!dayparts ? (
          <p className="text-muted-foreground text-sm">点上面的「读建议与时段画像」读取时段画像。</p>
        ) : dayparts.items.length === 0 ? (
          <p className="text-muted-foreground text-sm whitespace-pre-line">{dayparts.note}</p>
        ) : (
          <>
            <p className="text-muted-foreground text-xs whitespace-pre-line">{dayparts.note}</p>
            <table className="w-full text-sm">
              <thead>
                <tr className="text-muted-foreground border-b text-left text-xs">
                  <th className="p-2">时段</th>
                  <th className="p-2">活动 / 组</th>
                  <th className="p-2">窗口</th>
                  <th className="p-2">展示 / 点击 / 转化</th>
                  <th className="p-2">花费 / 销售额</th>
                  <th className="p-2">ACOS / ROAS</th>
                </tr>
              </thead>
              <tbody>
                {dayparts.items.map((row, index) => (
                  <tr key={`${row.campaignId}-${row.groupId ?? 'campaign'}-${row.dimensionValue}-${index}`} className="border-b">
                    <td className="p-2">{row.dimensionValue}</td>
                    <td className="p-2">
                      {row.campaignName}
                      <div className="text-muted-foreground text-xs">{row.groupName ?? '活动级'}</div>
                    </td>
                    <td className="p-2 text-xs">
                      {daypartWindowText(row.startHour, row.endHour, row.hours, row.crossesMidnight)}
                    </td>
                    <td className="p-2">
                      {countText(row.impressions)} / {countText(row.clicks)} / {countText(row.conversions)}
                    </td>
                    <td className="p-2">
                      {money(row.spend, row.currency)} / {money(row.sales, row.currency)}
                    </td>
                    <td className="p-2">
                      {ratioText(row.acos, 4)} / {ratioText(row.roas, 2)}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </>
        )}
      </Section>

      {/* ================= 🔴 同步定位维度效果数据 ================= */}
      <Section title="同步定位维度效果数据">
        <p className="text-muted-foreground text-xs">
          指标里的**维度与归属由定位设置决定**，这里不能自报（否则一条指标能挂到别的维度上）。
          同一个批次键重投不重复落；不合法的那条逐条被拒，其余照落。
        </p>
        <div className="flex flex-wrap items-end gap-2">
          <Field label="定位设置 id">
            <input
              className="rounded border px-2 py-1 text-sm"
              value={metricInput.settingId}
              onChange={(e) => setMetricInput({ ...metricInput, settingId: e.target.value })}
            />
          </Field>
          <Field label="指标日（UTC 日）">
            <input
              className="rounded border px-2 py-1 text-sm"
              value={metricInput.bucketDate}
              onChange={(e) => setMetricInput({ ...metricInput, bucketDate: e.target.value })}
              placeholder="2026-10-01"
            />
          </Field>
          <Field label="展示数">
            <input
              className="rounded border px-2 py-1 text-sm"
              value={metricInput.impressions}
              onChange={(e) => setMetricInput({ ...metricInput, impressions: e.target.value })}
            />
          </Field>
          <Field label="点击数">
            <input
              className="rounded border px-2 py-1 text-sm"
              value={metricInput.clicks}
              onChange={(e) => setMetricInput({ ...metricInput, clicks: e.target.value })}
            />
          </Field>
          <Field label="转化数">
            <input
              className="rounded border px-2 py-1 text-sm"
              value={metricInput.conversions}
              onChange={(e) => setMetricInput({ ...metricInput, conversions: e.target.value })}
            />
          </Field>
          <Field label="花费">
            <input
              className="rounded border px-2 py-1 text-sm"
              value={metricInput.spend}
              onChange={(e) => setMetricInput({ ...metricInput, spend: e.target.value })}
            />
          </Field>
          <Field label="销售额">
            <input
              className="rounded border px-2 py-1 text-sm"
              value={metricInput.sales}
              onChange={(e) => setMetricInput({ ...metricInput, sales: e.target.value })}
            />
          </Field>
          <Field label="币种">
            <input
              className="rounded border px-2 py-1 text-sm"
              value={metricInput.currency}
              onChange={(e) => setMetricInput({ ...metricInput, currency: e.target.value })}
              placeholder="USD"
            />
          </Field>
          <Field label="批次幂等键">
            <input
              className="rounded border px-2 py-1 text-sm"
              value={metricInput.batchKey}
              onChange={(e) => setMetricInput({ ...metricInput, batchKey: e.target.value })}
            />
          </Field>
          <Field label="理由">
            <input
              className="rounded border px-2 py-1 text-sm"
              value={metricInput.reason}
              onChange={(e) => setMetricInput({ ...metricInput, reason: e.target.value })}
            />
          </Field>
          <button
            type="button"
            className="rounded border px-2 py-1 text-sm"
            disabled={busy}
            onClick={() => void submitMetrics()}
          >
            同步
          </button>
        </div>
        {metricErrors.length > 0 ? (
          <ul className="text-destructive text-xs">
            {metricErrors.map((e) => (
              <li key={e}>{e}</li>
            ))}
          </ul>
        ) : null}
        {metricResult ? (
          <div className="flex flex-col gap-1 text-xs">
            <p>
              落库 {metricResult.accepted} 条 / 覆盖 {metricResult.replaced} 条 / 被拒{' '}
              {metricResult.rejected} 条
            </p>
            {metricResult.rejectedMessages.map((m) => (
              <p key={m} className="text-destructive">
                {m}
              </p>
            ))}
            <p className="text-muted-foreground whitespace-pre-line">{metricResult.note}</p>
          </div>
        ) : null}
      </Section>

      {/* ================= 详情 ================= */}
      {detail ? (
        <Section title="定位详情">
          <div className="flex flex-col gap-1 text-sm">
            <p>
              {dimensionLabel(detail.setting.dimension)}：{detail.setting.dimensionValue}（
              {targetingStatusLabel(detail.setting.status)}）
            </p>
            <p className="text-muted-foreground text-xs">
              归属：{scopeLabel(detail.setting.scopeKind)} · {detail.setting.campaignName}
              {detail.setting.groupName ? ` / ${detail.setting.groupName}` : ''} · 平台原文：
              {detail.setting.platformRawKey
                ? `${detail.setting.platformRawKey}=${detail.setting.platformRawValue}`
                : '—'}
            </p>
            <p className="text-muted-foreground text-xs">
              出价系数 {bidFactorText(detail.setting.bidFactor)} · 回写：
              {pendingWritebackText(detail.setting.pendingPlatformWriteback)} · 生效：
              {timestampText(detail.setting.effectiveFrom)}
            </p>
            {detail.setting.dimension === 'DAYPART' ? (
              <p className="text-muted-foreground text-xs">
                时段窗口：
                {daypartWindowText(
                  detail.setting.daypartStartHour,
                  detail.setting.daypartEndHour,
                  detail.setting.daypartHours,
                  detail.setting.daypartCrossesMidnight,
                )}
              </p>
            ) : null}
          </div>

          <div className="flex flex-col gap-1">
            <h3 className="text-sm font-medium">下游入口</h3>
            {detail.entries.map((e) => (
              <p key={e.entry} className="text-muted-foreground text-xs whitespace-pre-line">
                {e.label}：{e.available ? '已交付' : '未交付'} · {e.source}
                {e.ownerModule ? ` · 责任模块 ${e.ownerModule}` : ''}
                {e.impact ? ` · ${e.impact}` : ''}
                {e.note ? ` · ${e.note}` : ''}
              </p>
            ))}
          </div>

          <div className="flex flex-col gap-1">
            <h3 className="text-sm font-medium">最近操作留痕</h3>
            {detail.recentOperations.length === 0 ? (
              <p className="text-muted-foreground text-sm">还没有留痕。</p>
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
                      <td className="p-2">{targetingOperationLabel(o.operation)}</td>
                      <td className="p-2">{o.result}</td>
                      <td className="p-2">{o.message}</td>
                      <td className="p-2">{o.reason || '—'}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
          </div>

          <p className="text-muted-foreground text-xs whitespace-pre-line">{detail.note}</p>
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

function Field({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <label className="flex flex-col gap-1 text-sm">
      <span>{label}</span>
      {children}
    </label>
  );
}
