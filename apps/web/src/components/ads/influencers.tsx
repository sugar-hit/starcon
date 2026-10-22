'use client';

import { useCallback, useEffect, useState } from 'react';
import { errorText } from '../inventory/inventory-model';
import {
  blacklistInfluencer,
  blacklistReasonLabel,
  cooperationStatusLabel,
  deleteInfluencer,
  effectCount,
  effectMoney,
  effectRatio,
  emptyInfluencerInput,
  emptyInfluencerRatingRuleInput,
  emptyInfluencerRatingTierInput,
  emptyInfluencerTagGroupInput,
  emptyInfluencerTagInput,
  fetchInfluencer,
  fetchInfluencerBlacklist,
  fetchInfluencerCapabilities,
  fetchInfluencerCooperations,
  fetchInfluencerOptions,
  fetchInfluencerRatingRule,
  fetchInfluencerRatingTiers,
  fetchInfluencerSyncStatus,
  fetchInfluencerTagGroups,
  fetchInfluencerTags,
  fetchInfluencers,
  followerText,
  followerTierLabel,
  influencerStatusLabel,
  platformSyncStatusLabel,
  ratingText,
  recomputeInfluencerRating,
  releaseInfluencerBlacklist,
  saveInfluencer,
  saveInfluencerRatingRule,
  saveInfluencerRatingTier,
  saveInfluencerTag,
  saveInfluencerTagGroup,
  syncInfluencers,
  syncSourceLabel,
  tagCodeList,
  tagInfluencer,
  timestampText,
  untagInfluencer,
  updateInfluencer,
  validateInfluencer,
  validateInfluencerRatingRule,
  validateInfluencerRatingTier,
  validateInfluencerTag,
  validateInfluencerTagGroup,
} from './influencer-model';
import type {
  InfluencerBlacklistPage,
  InfluencerCapabilities,
  InfluencerCooperationPage,
  InfluencerDetail,
  InfluencerInputState,
  InfluencerOptions,
  InfluencerPage,
  InfluencerQuery,
  InfluencerRatingRuleInputState,
  InfluencerRatingRuleRow,
  InfluencerRatingTierInputState,
  InfluencerRatingTierPage,
  InfluencerRow,
  InfluencerSyncStatus,
  InfluencerTagGroupInputState,
  InfluencerTagGroupPage,
  InfluencerTagInputState,
  InfluencerTagPage,
} from './influencer-model';

/**
 * 14.5.1.1 达人库。
 *
 * 🔴 页面顶部那段话不是提示语，是这一页的设计前提：
 *
 * 1. **「没录」必须看得见**。GMV / 互动率 / 退款率没录显示「未录入」，
 *    **不是 0.00** —— 0 会被读成「这个达人一单没带过货」。
 * 2. **评级是算出来的**。评分口径（权重 + 归一化上限）与档位都可配；
 *    **必需的效果数据缺任何一项 ⇒ 该达人暂无评分**（不是 0 分）。
 * 3. **「本地已录」不是「平台侧已录」**。平台达人数据适配器未交付 ⇒
 *    同步入口明确返回「未接入」并**不写任何数据**，页面常驻这条说明。
 * 4. **黑名单是独立事实，且要有独立入口**。拉黑后从正常检索里排除，
 *    解除留痕（时刻 + 理由）；黑名单页签让运营随时能看见「谁被拉黑了、为什么」。
 * 5. **两个 GMV 口径分开**。档案 GMV（入库值）与合作历史合计（本地求和）
 *    是两列，归因口径归 14.5.2.1，未交付。
 */
export function InfluencerWorkspace() {
  const [capabilities, setCapabilities] = useState<InfluencerCapabilities | null>(null);
  const [options, setOptions] = useState<InfluencerOptions | null>(null);
  const [syncStatus, setSyncStatus] = useState<InfluencerSyncStatus | null>(null);
  const [tab, setTab] = useState<'library' | 'blacklist' | 'tags' | 'rating' | 'cooperations'>(
    'library',
  );
  const [error, setError] = useState('');
  const [syncPlatform, setSyncPlatform] = useState('');
  const [syncShop, setSyncShop] = useState('');
  const [syncing, setSyncing] = useState(false);

  useEffect(() => {
    let alive = true;
    void (async () => {
      try {
        const [caps, opts, sync] = await Promise.all([
          fetchInfluencerCapabilities(),
          fetchInfluencerOptions(),
          fetchInfluencerSyncStatus(),
        ]);
        if (!alive) return;
        setCapabilities(caps);
        setOptions(opts);
        setSyncStatus(sync);
      } catch (e) {
        if (alive) setError(errorText(e));
      }
    })();
    return () => {
      alive = false;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  /** 触发同步：适配器未接入时后端明确拒绝（409），这里原样把原因显示出来。 */
  async function triggerSync() {
    setSyncing(true);
    setError('');
    try {
      const result = await syncInfluencers({ platformCode: syncPlatform, shopCode: syncShop });
      setError(result.message);
    } catch (e) {
      setError(errorText(e));
    } finally {
      setSyncing(false);
    }
  }

  return (
    <main className="flex flex-col gap-4 p-4">
      <header className="flex flex-col gap-1">
        <h1 className="text-xl font-medium">达人库</h1>
        <p className="text-muted-foreground text-sm">
          14.5.1.1 · 达人档案（类目 / 粉丝层级 / 带货 GMV / 互动率 / 退款率）+ 标签分组 + 评级 +
          黑名单 + 合作历史，支持多维筛选检索。
        </p>
      </header>

      {capabilities ? (
        <Section title="能力声明（未交付段显式标注）">
          <ul className="flex flex-col gap-1 text-sm">
            {capabilities.dimensions.map((dim) => (
              <li key={dim.dimension} className="flex flex-col">
                <span className={dim.available ? 'text-emerald-600' : 'text-amber-600'}>
                  {dim.available ? '✓' : '✗'} {dim.description}
                </span>
                {!dim.available ? (
                  <span className="text-muted-foreground text-xs">
                    责任模块 {dim.ownerModule}：{dim.impact}
                  </span>
                ) : null}
              </li>
            ))}
          </ul>
          <p className="text-muted-foreground text-xs">{capabilities.upstreamNote}</p>
          <p className="text-muted-foreground text-xs">{capabilities.boundaryNote}</p>
        </Section>
      ) : null}

      <div className="flex flex-wrap gap-2">
        <TabButton active={tab === 'library'} onClick={() => setTab('library')}>
          达人库
        </TabButton>
        <TabButton active={tab === 'blacklist'} onClick={() => setTab('blacklist')}>
          黑名单
        </TabButton>
        <TabButton active={tab === 'tags'} onClick={() => setTab('tags')}>
          标签与分组
        </TabButton>
        <TabButton active={tab === 'rating'} onClick={() => setTab('rating')}>
          评级
        </TabButton>
        <TabButton active={tab === 'cooperations'} onClick={() => setTab('cooperations')}>
          合作历史
        </TabButton>
      </div>

      {tab === 'library' ? <LibraryTab options={options} /> : null}
      {tab === 'blacklist' ? <BlacklistTab /> : null}
      {tab === 'tags' ? <TagsTab /> : null}
      {tab === 'rating' ? <RatingTab /> : null}
      {tab === 'cooperations' ? <CooperationsTab /> : null}

      {syncStatus ? (
        <Section title="平台达人数据同步">
          <p className="text-muted-foreground text-xs">{syncStatus.syncNote}</p>
          <div className="flex flex-wrap items-end gap-2">
            <Field label="平台">
              <select
                className="rounded border px-2 py-1 text-sm"
                value={syncPlatform}
                onChange={(e) => setSyncPlatform(e.target.value)}
              >
                <option value="">请选择平台</option>
                {syncStatus.platforms.map((p) => (
                  <option key={p.platformCode} value={p.platformCode}>
                    {p.platformName}（{p.platformCode}）
                  </option>
                ))}
              </select>
            </Field>
            <Field label="店铺">
              <select
                className="rounded border px-2 py-1 text-sm"
                value={syncShop}
                onChange={(e) => setSyncShop(e.target.value)}
              >
                <option value="">请选择店铺</option>
                {(options?.shopCodes ?? []).map((code) => (
                  <option key={code} value={code}>
                    {code}
                  </option>
                ))}
              </select>
            </Field>
            <button
              className="rounded border px-3 py-1 text-sm"
              disabled={!syncPlatform || !syncShop || syncing}
              onClick={() => void triggerSync()}
            >
              {syncing ? '正在同步…' : '触发同步'}
            </button>
          </div>
          <p className="text-muted-foreground text-xs">
            🔴 平台达人数据适配器未交付 ⇒ 点「触发同步」会明确返回「未接入」并且
            <strong>不写任何数据</strong>（不编造平台接口，也不返回硬编码的达人列表）。
          </p>
          <div className="overflow-auto">
            <table className="w-full text-sm">
              <thead>
                <tr className="text-muted-foreground text-left text-xs">
                  <th className="py-1">平台</th>
                  <th className="py-1">接入状态</th>
                  <th className="py-1">库里已登记达人数</th>
                  <th className="py-1">原因</th>
                </tr>
              </thead>
              <tbody>
                {syncStatus.platforms.map((p) => (
                  <tr key={p.platformCode} className="border-t">
                    <td className="py-1">
                      {p.platformName}（{p.platformCode}）
                    </td>
                    <td className={`py-1 ${p.available ? 'text-emerald-600' : 'text-amber-600'}`}>
                      {p.available ? '已接入' : '未接入'}
                    </td>
                    <td className="py-1">{p.registeredCount}</td>
                    <td className="text-muted-foreground py-1 text-xs">{p.reason}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </Section>
      ) : null}

      {error ? <p className="text-destructive text-sm">{error}</p> : null}
    </main>
  );
}

function TabButton({
  active,
  onClick,
  children,
}: {
  active: boolean;
  onClick: () => void;
  children: React.ReactNode;
}) {
  return (
    <button
      className={`rounded border px-3 py-1 text-sm ${active ? 'bg-primary text-primary-foreground' : ''}`}
      onClick={onClick}
    >
      {children}
    </button>
  );
}

// ==================================================================
// 达人库
// ==================================================================

function LibraryTab({ options }: { options: InfluencerOptions | null }) {
  const [query, setQuery] = useState<InfluencerQuery>({ sort: 'GMV_DESC', page: 0, size: 20 });
  const [page, setPage] = useState<InfluencerPage | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [selected, setSelected] = useState<InfluencerDetail | null>(null);
  const [form, setForm] = useState<InfluencerInputState | null>(null);
  const [formErrors, setFormErrors] = useState<string[]>([]);
  /** 🔴 正在编辑的达人 id；null = 新建。用显式状态区分，不靠名字猜。 */
  const [editingId, setEditingId] = useState<string | null>(null);

  const load = useCallback(async (next: InfluencerQuery) => {
    setLoading(true);
    setError('');
    try {
      setPage(await fetchInfluencers(next));
    } catch (e) {
      setError(errorText(e));
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void load(query);
  }, [load, query]);

  function applyFilter(patch: Partial<InfluencerQuery>) {
    setQuery((prev) => ({ ...prev, page: 0, ...patch }));
  }

  async function openDetail(id: string) {
    setError('');
    try {
      setSelected(await fetchInfluencer(id));
    } catch (e) {
      setError(errorText(e));
    }
  }

  async function submitForm() {
    if (!form) return;
    const validation = validateInfluencer(form);
    setFormErrors(validation.errors);
    if (!validation.ok) return;
    setError('');
    try {
      const detail =
        editingId !== null
          ? await updateInfluencer(editingId, {
              platformId: form.platformId,
              shopCode: form.shopCode,
              externalInfluencerId: form.externalInfluencerId,
              displayName: form.displayName,
              handle: form.handle,
              category: form.category,
              followerCount: form.followerCount.trim() === '' ? null : Number(form.followerCount),
              engagementRate: form.engagementRate.trim() === '' ? null : Number(form.engagementRate),
              gmv: form.gmv.trim() === '' ? null : Number(form.gmv),
              gmvCurrency: form.gmvCurrency,
              refundRate: form.refundRate.trim() === '' ? null : Number(form.refundRate),
              status: form.status,
              note: form.note,
            })
          : await saveInfluencer({
              platformId: form.platformId,
              shopCode: form.shopCode,
              externalInfluencerId: form.externalInfluencerId,
              displayName: form.displayName,
              handle: form.handle,
              category: form.category,
              followerCount: form.followerCount.trim() === '' ? null : Number(form.followerCount),
              engagementRate: form.engagementRate.trim() === '' ? null : Number(form.engagementRate),
              gmv: form.gmv.trim() === '' ? null : Number(form.gmv),
              gmvCurrency: form.gmvCurrency,
              refundRate: form.refundRate.trim() === '' ? null : Number(form.refundRate),
              status: form.status,
              note: form.note,
            });
      setSelected(detail);
      setForm(null);
      setEditingId(null);
      await load(query);
    } catch (e) {
      setError(errorText(e));
    }
  }

  async function remove(id: string) {
    setError('');
    try {
      await deleteInfluencer(id);
      if (selected?.influencer.id === id) setSelected(null);
      await load(query);
    } catch (e) {
      setError(errorText(e));
    }
  }

  const startEdit = (row: InfluencerRow) => {
    setEditingId(row.id);
    setForm({
      platformId: row.platformId,
      shopCode: row.shopCode,
      externalInfluencerId: row.externalInfluencerId,
      displayName: row.displayName,
      handle: row.handle,
      category: row.category,
      followerCount: String(row.followerCount),
      engagementRate: row.engagementRate === null ? '' : String(row.engagementRate),
      gmv: row.gmv === null ? '' : String(row.gmv),
      gmvCurrency: row.gmvCurrency || 'USD',
      refundRate: row.refundRate === null ? '' : String(row.refundRate),
      status: row.status,
      note: row.note,
    });
    void openDetail(row.id);
  };

  return (
    <>
      <Section title="筛选（多维）">
        <div className="flex flex-wrap gap-3">
          <Field label="类目">
            <select
              className="rounded border px-2 py-1 text-sm"
              value={query.category ?? ''}
              onChange={(e) => applyFilter({ category: e.target.value })}
            >
              <option value="">全部类目</option>
              {(options?.categories ?? []).map((c) => (
                <option key={c} value={c}>
                  {c}
                </option>
              ))}
            </select>
          </Field>
          <Field label="粉丝层级">
            <select
              className="rounded border px-2 py-1 text-sm"
              value={query.followerTier ?? ''}
              onChange={(e) => applyFilter({ followerTier: e.target.value })}
            >
              <option value="">全部层级</option>
              {(options?.followerTiers ?? []).map((t) => (
                <option key={t.code} value={t.code}>
                  {t.label}
                </option>
              ))}
            </select>
          </Field>
          <Field label="店铺">
            <select
              className="rounded border px-2 py-1 text-sm"
              value={query.shopCode ?? ''}
              onChange={(e) => applyFilter({ shopCode: e.target.value })}
            >
              <option value="">全部店铺</option>
              {(options?.shopCodes ?? []).map((s) => (
                <option key={s} value={s}>
                  {s}
                </option>
              ))}
            </select>
          </Field>
          <Field label="GMV 下限">
            <input
              type="number"
              className="rounded border px-2 py-1 text-sm"
              value={query.minGmv ?? ''}
              onChange={(e) =>
                applyFilter({ minGmv: e.target.value === '' ? undefined : Number(e.target.value) })
              }
            />
          </Field>
          <Field label="GMV 上限">
            <input
              type="number"
              className="rounded border px-2 py-1 text-sm"
              value={query.maxGmv ?? ''}
              onChange={(e) =>
                applyFilter({ maxGmv: e.target.value === '' ? undefined : Number(e.target.value) })
              }
            />
          </Field>
          <Field label="互动率下限">
            <input
              type="number"
              step="0.01"
              className="rounded border px-2 py-1 text-sm"
              value={query.minEngagementRate ?? ''}
              onChange={(e) =>
                applyFilter({
                  minEngagementRate:
                    e.target.value === '' ? undefined : Number(e.target.value),
                })
              }
            />
          </Field>
          <Field label="互动率上限">
            <input
              type="number"
              step="0.01"
              className="rounded border px-2 py-1 text-sm"
              value={query.maxEngagementRate ?? ''}
              onChange={(e) =>
                applyFilter({
                  maxEngagementRate:
                    e.target.value === '' ? undefined : Number(e.target.value),
                })
              }
            />
          </Field>
          <Field label="关键字">
            <input
              className="rounded border px-2 py-1 text-sm"
              value={query.keyword ?? ''}
              onChange={(e) => applyFilter({ keyword: e.target.value })}
            />
          </Field>
          <Field label="排序">
            <select
              className="rounded border px-2 py-1 text-sm"
              value={query.sort ?? 'GMV_DESC'}
              onChange={(e) => applyFilter({ sort: e.target.value })}
            >
              <option value="GMV_DESC">GMV 从高到低</option>
              <option value="GMV_ASC">GMV 从低到高</option>
              <option value="FOLLOWERS_DESC">粉丝数从高到低</option>
              <option value="ENGAGEMENT_DESC">互动率从高到低</option>
              <option value="RATING_DESC">评级从高到低</option>
              <option value="CREATED_DESC">最近录入</option>
            </select>
          </Field>
          <Field label="含黑名单中的达人">
            <input
              type="checkbox"
              checked={query.includeBlacklisted ?? false}
              onChange={(e) => applyFilter({ includeBlacklisted: e.target.checked })}
            />
          </Field>
        </div>
        <p className="text-muted-foreground text-xs">
          🔴 默认<strong>排除</strong>黑名单中的达人；勾选后才会看到他们。
        </p>
      </Section>

      <Section title="录入 / 编辑达人">
        {form === null ? (
          <button
            className="rounded border px-3 py-1 text-sm"
            onClick={() => {
              setForm(emptyInfluencerInput());
              setFormErrors([]);
              setEditingId(null);
              setSelected(null);
            }}
          >
            手动录入一个达人
          </button>
        ) : (
          <div className="flex flex-col gap-3">
            <div className="flex flex-wrap gap-3">
              <Field label="平台">
                <select
                  className="rounded border px-2 py-1 text-sm"
                  value={form.platformId}
                  onChange={(e) => setForm({ ...form, platformId: e.target.value })}
                >
                  <option value="">请选择平台</option>
                  {(options?.platforms ?? []).map((p) => (
                    <option key={p.id} value={p.id}>
                      {p.name}（{p.code}）
                    </option>
                  ))}
                </select>
              </Field>
              <Field label="店铺">
                <select
                  className="rounded border px-2 py-1 text-sm"
                  value={form.shopCode}
                  onChange={(e) => setForm({ ...form, shopCode: e.target.value })}
                >
                  <option value="">请选择店铺</option>
                  {(options?.shopCodes ?? []).map((s) => (
                    <option key={s} value={s}>
                      {s}
                    </option>
                  ))}
                </select>
              </Field>
              <Field label="达人名称">
                <input
                  className="rounded border px-2 py-1 text-sm"
                  value={form.displayName}
                  onChange={(e) => setForm({ ...form, displayName: e.target.value })}
                />
              </Field>
              <Field label="账号名">
                <input
                  className="rounded border px-2 py-1 text-sm"
                  value={form.handle}
                  onChange={(e) => setForm({ ...form, handle: e.target.value })}
                />
              </Field>
              <Field label="类目">
                <input
                  className="rounded border px-2 py-1 text-sm"
                  value={form.category}
                  onChange={(e) => setForm({ ...form, category: e.target.value })}
                />
              </Field>
              <Field label="平台侧达人 id（可空）">
                <input
                  className="rounded border px-2 py-1 text-sm"
                  value={form.externalInfluencerId}
                  onChange={(e) => setForm({ ...form, externalInfluencerId: e.target.value })}
                />
              </Field>
              <Field label="粉丝数（层级自动推导）">
                <input
                  type="number"
                  className="rounded border px-2 py-1 text-sm"
                  value={form.followerCount}
                  onChange={(e) => setForm({ ...form, followerCount: e.target.value })}
                />
              </Field>
              <Field label="互动率（0~1，可空 = 未录入）">
                <input
                  className="rounded border px-2 py-1 text-sm"
                  value={form.engagementRate}
                  onChange={(e) => setForm({ ...form, engagementRate: e.target.value })}
                />
              </Field>
              <Field label="带货 GMV（可空 = 未录入）">
                <input
                  className="rounded border px-2 py-1 text-sm"
                  value={form.gmv}
                  onChange={(e) => setForm({ ...form, gmv: e.target.value })}
                />
              </Field>
              <Field label="GMV 币种">
                <input
                  className="rounded border px-2 py-1 text-sm"
                  value={form.gmvCurrency}
                  onChange={(e) => setForm({ ...form, gmvCurrency: e.target.value })}
                />
              </Field>
              <Field label="退款率（0~1，可空 = 未录入）">
                <input
                  className="rounded border px-2 py-1 text-sm"
                  value={form.refundRate}
                  onChange={(e) => setForm({ ...form, refundRate: e.target.value })}
                />
              </Field>
              <Field label="状态">
                <select
                  className="rounded border px-2 py-1 text-sm"
                  value={form.status}
                  onChange={(e) => setForm({ ...form, status: e.target.value })}
                >
                  <option value="ACTIVE">在库可用</option>
                  <option value="INACTIVE">已停用</option>
                </select>
              </Field>
              <Field label="备注">
                <input
                  className="rounded border px-2 py-1 text-sm"
                  value={form.note}
                  onChange={(e) => setForm({ ...form, note: e.target.value })}
                />
              </Field>
            </div>
            {formErrors.length > 0 ? (
              <ul className="text-destructive text-xs">
                {formErrors.map((e) => (
                  <li key={e}>{e}</li>
                ))}
              </ul>
            ) : null}
            <div className="flex gap-2">
              <button className="rounded border px-3 py-1 text-sm" onClick={() => void submitForm()}>
                保存
              </button>
              <button
                className="rounded border px-3 py-1 text-sm"
                onClick={() => {
                  setForm(null);
                  setFormErrors([]);
                }}
              >
                取消
              </button>
            </div>
          </div>
        )}
      </Section>

      <Section title="达人库">
        {loading ? (
          <p className="text-muted-foreground text-sm">正在加载达人库…</p>
        ) : error ? (
          <p className="text-destructive text-sm">{error}</p>
        ) : page && page.items.length === 0 ? (
          <p className="text-sm text-amber-600">
            {query.keyword || query.category || query.followerTier
              ? '没有符合筛选条件的达人。放宽筛选条件再试。'
              : '达人库还是空的 —— 先手动录入一个达人（平台达人数据同步尚未接入）。'}
          </p>
        ) : page ? (
          <>
            <div className="overflow-auto">
              <table className="w-full text-sm">
                <thead>
                  <tr className="text-muted-foreground text-left text-xs">
                    <th className="py-1">达人</th>
                    <th className="py-1">类目</th>
                    <th className="py-1">粉丝数 / 层级</th>
                    <th className="py-1">互动率</th>
                    <th className="py-1">带货 GMV</th>
                    <th className="py-1">退款率</th>
                    <th className="py-1">评级</th>
                    <th className="py-1">合作历史</th>
                    <th className="py-1">标签</th>
                    <th className="py-1">状态</th>
                    <th className="py-1">操作</th>
                  </tr>
                </thead>
                <tbody>
                  {page.items.map((row) => (
                    <tr key={row.id} className="border-t">
                      <td className="py-1">
                        <div>{row.displayName}</div>
                        {row.handle ? (
                          <div className="text-muted-foreground text-xs">{row.handle}</div>
                        ) : null}
                        {row.externalInfluencerId ? (
                          <div className="text-muted-foreground text-xs">
                            {row.externalInfluencerId}
                          </div>
                        ) : null}
                      </td>
                      <td className="py-1">{row.category}</td>
                      <td className="py-1">
                        {followerText(row.followerCount)}
                        <div className="text-muted-foreground text-xs">
                          {followerTierLabel(row.followerTier)}
                        </div>
                      </td>
                      <td className="py-1">{effectRatio(row.engagementRate)}</td>
                      <td className="py-1">{effectMoney(row.gmv, row.gmvCurrency)}</td>
                      <td className="py-1">{effectRatio(row.refundRate)}</td>
                      <td className="py-1">
                        {ratingText(row.ratingScore)}
                        {row.ratingTierName ? (
                          <div className="text-muted-foreground text-xs">{row.ratingTierName}</div>
                        ) : null}
                      </td>
                      <td className="py-1">
                        {row.cooperationCount} 条
                        <div className="text-muted-foreground text-xs">
                          合计 {effectMoney(row.gmvFromCooperations, row.gmvCurrency)}
                        </div>
                      </td>
                      <td className="py-1 text-xs">{tagCodeList(row.tagCodes).join('、') || '—'}</td>
                      <td className="py-1">
                        <div>{influencerStatusLabel(row.status)}</div>
                        <div className="text-muted-foreground text-xs">
                          {syncSourceLabel(row.syncSource)}
                        </div>
                        <div className="text-muted-foreground text-xs">
                          {platformSyncStatusLabel(row.platformSyncStatus)}
                        </div>
                        {row.blacklisted ? (
                          <div className="text-destructive text-xs">
                            黑名单中（{blacklistReasonLabel(row.blacklistReasonCode)}）
                          </div>
                        ) : null}
                      </td>
                      <td className="py-1">
                        <div className="flex flex-col gap-1">
                          <button
                            className="rounded border px-2 py-0.5 text-xs"
                            onClick={() => void openDetail(row.id)}
                          >
                            详情 / 打标
                          </button>
                          <button
                            className="rounded border px-2 py-0.5 text-xs"
                            onClick={() => startEdit(row)}
                          >
                            编辑
                          </button>
                          <button
                            className="rounded border px-2 py-0.5 text-xs"
                            onClick={() => void remove(row.id)}
                          >
                            删除
                          </button>
                        </div>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            <Pagination
              page={page.page}
              size={page.size}
              total={page.total}
              onChange={(next) => setQuery((prev) => ({ ...prev, page: next }))}
            />
          </>
        ) : null}
      </Section>

      {selected ? (
        <DetailPanel
          detail={selected}
          onChanged={async () => {
            await load(query);
          }}
        />
      ) : null}
    </>
  );
}

function DetailPanel({
  detail,
  onChanged,
}: {
  detail: InfluencerDetail;
  onChanged: () => Promise<void>;
}) {
  const [tagCode, setTagCode] = useState('');
  const [reasonCode, setReasonCode] = useState('POOR_QUALITY');
  const [reason, setReason] = useState('');
  const [error, setError] = useState('');
  const [tags, setTags] = useState<InfluencerTagPage | null>(null);

  const loadTags = useCallback(async () => {
    try {
      setTags(await fetchInfluencerTags({ size: 200 }));
    } catch (e) {
      setError(errorText(e));
    }
  }, []);

  useEffect(() => {
    void loadTags();
  }, [loadTags]);

  async function run(work: () => Promise<unknown>) {
    setError('');
    try {
      await work();
      await onChanged();
      await loadTags();
    } catch (e) {
      setError(errorText(e));
    }
  }

  return (
    <Section title={`达人详情 · ${detail.influencer.displayName}`}>
      <p className="text-muted-foreground text-xs">{detail.note}</p>
      <p className="text-muted-foreground text-xs">{detail.effectNote}</p>
      <p className="text-muted-foreground text-xs">{detail.ratingNote}</p>
      <p className="text-muted-foreground text-xs">{detail.syncNote}</p>

      <div className="flex flex-col gap-2">
        <p className="text-sm font-medium">标签</p>
        {detail.tags.length === 0 ? (
          <p className="text-muted-foreground text-sm">还没有打标签。</p>
        ) : (
          <ul className="flex flex-col gap-1 text-sm">
            {detail.tags.map((tag) => (
              <li key={tag.id} className="flex items-center gap-2">
                <span>
                  {tag.groupName} / {tag.tagName}（{tag.tagCode}）
                </span>
                <button
                  className="rounded border px-2 py-0.5 text-xs"
                  onClick={() =>
                    void run(() => untagInfluencer(detail.influencer.id, tag.id))
                  }
                >
                  移除
                </button>
              </li>
            ))}
          </ul>
        )}
        <div className="flex flex-wrap items-end gap-2">
          <Field label="打标（按标签编码）">
            <select
              className="rounded border px-2 py-1 text-sm"
              value={tagCode}
              onChange={(e) => setTagCode(e.target.value)}
            >
              <option value="">请选择标签</option>
              {(tags?.items ?? []).map((tag) => (
                <option key={tag.id} value={tag.tagCode}>
                  {tag.groupName} / {tag.tagName}（{tag.tagCode}）
                </option>
              ))}
            </select>
          </Field>
          <button
            className="rounded border px-3 py-1 text-sm"
            disabled={!tagCode}
            onClick={() =>
              void run(() =>
                tagInfluencer(detail.influencer.id, {
                  tagIds: [],
                  tagCodes: [tagCode],
                  note: '工作区打标',
                }),
              )
            }
          >
            打标
          </button>
        </div>
      </div>

      <div className="flex flex-col gap-2">
        <p className="text-sm font-medium">黑名单</p>
        {detail.blacklist === null ? (
          <p className="text-muted-foreground text-sm">不在黑名单里。</p>
        ) : (
          <>
            <p className="text-sm">
              {blacklistReasonLabel(detail.blacklist.reasonCode)} · 生效于{' '}
              {timestampText(detail.blacklist.effectiveAt)}
            </p>
            {detail.blacklist.reason ? (
              <p className="text-muted-foreground text-xs">{detail.blacklist.reason}</p>
            ) : null}
          </>
        )}
        <div className="flex flex-wrap items-end gap-2">
          <Field label="拉黑原因">
            <select
              className="rounded border px-2 py-1 text-sm"
              value={reasonCode}
              onChange={(e) => setReasonCode(e.target.value)}
            >
              <option value="FRAUD">欺诈</option>
              <option value="INFRINGEMENT">侵权</option>
              <option value="POOR_QUALITY">效果差</option>
              <option value="NON_COMPLIANCE">违规</option>
              <option value="BRAND_SAFETY">品牌安全</option>
              <option value="DATA_QUALITY">数据质量</option>
            </select>
          </Field>
          <Field label="拉黑理由">
            <input
              className="rounded border px-2 py-1 text-sm"
              value={reason}
              onChange={(e) => setReason(e.target.value)}
            />
          </Field>
          <button
            className="rounded border px-3 py-1 text-sm"
            disabled={detail.blacklist !== null}
            onClick={() =>
              void run(() =>
                blacklistInfluencer({
                  influencerId: detail.influencer.id,
                  reasonCode,
                  reason,
                  effectiveAt: null,
                  note: '工作区拉黑',
                }),
              )
            }
          >
            拉黑
          </button>
          {detail.blacklist ? (
            <button
              className="rounded border px-3 py-1 text-sm"
              onClick={() =>
                void run(() => releaseInfluencerBlacklist(detail.blacklist!.id, '工作区解除'))
              }
            >
              解除黑名单
            </button>
          ) : null}
          <button
            className="rounded border px-3 py-1 text-sm"
            onClick={() => void run(() => recomputeInfluencerRating(detail.influencer.id))}
          >
            重算评级
          </button>
        </div>
      </div>

      <div className="flex flex-col gap-2">
        <p className="text-sm font-medium">合作历史（达人 × 活动）</p>
        {detail.cooperations.length === 0 ? (
          <p className="text-muted-foreground text-sm">还没有合作记录。</p>
        ) : (
          <div className="overflow-auto">
            <table className="w-full text-sm">
              <thead>
                <tr className="text-muted-foreground text-left text-xs">
                  <th className="py-1">活动</th>
                  <th className="py-1">状态</th>
                  <th className="py-1">时间</th>
                  <th className="py-1">GMV</th>
                  <th className="py-1">订单量</th>
                  <th className="py-1">佣金</th>
                  <th className="py-1">退款率</th>
                </tr>
              </thead>
              <tbody>
                {detail.cooperations.map((c) => (
                  <tr key={c.id} className="border-t">
                    <td className="py-1">
                      {c.campaignName}
                      <div className="text-muted-foreground text-xs">{c.externalCampaignId}</div>
                    </td>
                    <td className="py-1">{cooperationStatusLabel(c.status)}</td>
                    <td className="text-muted-foreground py-1 text-xs">
                      {timestampText(c.startedAt)} ~ {timestampText(c.endedAt)}
                    </td>
                    <td className="py-1">{effectMoney(c.gmv, c.currency)}</td>
                    <td className="py-1">{effectCount(c.orderCount)}</td>
                    <td className="py-1">{effectMoney(c.commission, c.currency)}</td>
                    <td className="py-1">{effectRatio(c.refundRate)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>

      {error ? <p className="text-destructive text-sm">{error}</p> : null}
    </Section>
  );
}

// ==================================================================
// 黑名单
// ==================================================================

function BlacklistTab() {
  const [status, setStatus] = useState('');
  const [page, setPage] = useState<InfluencerBlacklistPage | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');

  const load = useCallback(async (nextStatus: string) => {
    setLoading(true);
    setError('');
    try {
      setPage(await fetchInfluencerBlacklist({ status: nextStatus, size: 50 }));
    } catch (e) {
      setError(errorText(e));
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void load(status);
  }, [load, status]);

  return (
    <Section title="黑名单（拉黑原因 / 生效时间 / 解除记录）">
      <div className="flex flex-wrap gap-3">
        <Field label="状态">
          <select
            className="rounded border px-2 py-1 text-sm"
            value={status}
            onChange={(e) => setStatus(e.target.value)}
          >
            <option value="">全部</option>
            <option value="ACTIVE">生效中</option>
            <option value="RELEASED">已解除</option>
          </select>
        </Field>
      </div>
      {loading ? (
        <p className="text-muted-foreground text-sm">正在加载黑名单…</p>
      ) : error ? (
        <p className="text-destructive text-sm">{error}</p>
      ) : page && page.items.length === 0 ? (
        <p className="text-sm text-amber-600">黑名单里还没有记录。</p>
      ) : page ? (
        <div className="overflow-auto">
          <table className="w-full text-sm">
            <thead>
              <tr className="text-muted-foreground text-left text-xs">
                <th className="py-1">达人</th>
                <th className="py-1">原因</th>
                <th className="py-1">生效时刻</th>
                <th className="py-1">解除时刻</th>
                <th className="py-1">解除理由</th>
                <th className="py-1">状态</th>
              </tr>
            </thead>
            <tbody>
              {page.items.map((row) => (
                <tr key={row.id} className="border-t">
                  <td className="py-1">
                    {row.influencerName}
                    <div className="text-muted-foreground text-xs">
                      {row.category} · {followerTierLabel(row.followerTier)}
                    </div>
                  </td>
                  <td className="py-1">
                    {blacklistReasonLabel(row.reasonCode)}
                    {row.reason ? (
                      <div className="text-muted-foreground text-xs">{row.reason}</div>
                    ) : null}
                  </td>
                  <td className="py-1">{timestampText(row.effectiveAt)}</td>
                  <td className="py-1">{timestampText(row.releasedAt)}</td>
                  <td className="text-muted-foreground py-1 text-xs">{row.releasedReason || '—'}</td>
                  <td className="py-1">{row.status === 'ACTIVE' ? '生效中' : '已解除'}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      ) : null}
    </Section>
  );
}

// ==================================================================
// 标签与分组
// ==================================================================

function TagsTab() {
  const [groups, setGroups] = useState<InfluencerTagGroupPage | null>(null);
  const [tags, setTags] = useState<InfluencerTagPage | null>(null);
  const [groupForm, setGroupForm] = useState<InfluencerTagGroupInputState | null>(null);
  const [tagForm, setTagForm] = useState<InfluencerTagInputState | null>(null);
  const [errors, setErrors] = useState<string[]>([]);
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(true);

  const load = useCallback(async () => {
    setLoading(true);
    setError('');
    try {
      const [g, t] = await Promise.all([
        fetchInfluencerTagGroups({ size: 100 }),
        fetchInfluencerTags({ size: 200 }),
      ]);
      setGroups(g);
      setTags(t);
    } catch (e) {
      setError(errorText(e));
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  async function submitGroup() {
    if (!groupForm) return;
    const validation = validateInfluencerTagGroup(groupForm);
    setErrors(validation.errors);
    if (!validation.ok) return;
    setError('');
    try {
      await saveInfluencerTagGroup({
        groupCode: groupForm.groupCode,
        groupName: groupForm.groupName,
        description: groupForm.description,
        status: groupForm.status,
        note: groupForm.note,
      });
      setGroupForm(null);
      await load();
    } catch (e) {
      setError(errorText(e));
    }
  }

  async function submitTag() {
    if (!tagForm) return;
    const validation = validateInfluencerTag(tagForm);
    setErrors(validation.errors);
    if (!validation.ok) return;
    setError('');
    try {
      await saveInfluencerTag({
        groupId: tagForm.groupId,
        tagCode: tagForm.tagCode,
        tagName: tagForm.tagName,
        description: tagForm.description,
        status: tagForm.status,
        note: tagForm.note,
      });
      setTagForm(null);
      await load();
    } catch (e) {
      setError(errorText(e));
    }
  }

  return (
    <>
      <Section title="标签分组">
        {loading ? (
          <p className="text-muted-foreground text-sm">正在加载…</p>
        ) : error ? (
          <p className="text-destructive text-sm">{error}</p>
        ) : groups && groups.items.length === 0 ? (
          <p className="text-sm text-amber-600">还没有标签分组 —— 先建一个（如「美妆达人」）。</p>
        ) : groups ? (
          <div className="overflow-auto">
            <table className="w-full text-sm">
              <thead>
                <tr className="text-muted-foreground text-left text-xs">
                  <th className="py-1">编码</th>
                  <th className="py-1">名称</th>
                  <th className="py-1">标签数</th>
                  <th className="py-1">状态</th>
                </tr>
              </thead>
              <tbody>
                {groups.items.map((g) => (
                  <tr key={g.id} className="border-t">
                    <td className="py-1">{g.groupCode}</td>
                    <td className="py-1">{g.groupName}</td>
                    <td className="py-1">{g.tagCount}</td>
                    <td className="py-1">{g.status}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        ) : null}
        <div className="flex gap-2">
          {groupForm === null ? (
            <button
              className="rounded border px-3 py-1 text-sm"
              onClick={() => {
                setGroupForm(emptyInfluencerTagGroupInput());
                setErrors([]);
              }}
            >
              新建标签分组
            </button>
          ) : (
            <div className="flex flex-wrap items-end gap-2">
              <Field label="分组编码">
                <input
                  className="rounded border px-2 py-1 text-sm"
                  value={groupForm.groupCode}
                  onChange={(e) => setGroupForm({ ...groupForm, groupCode: e.target.value })}
                />
              </Field>
              <Field label="分组名称">
                <input
                  className="rounded border px-2 py-1 text-sm"
                  value={groupForm.groupName}
                  onChange={(e) => setGroupForm({ ...groupForm, groupName: e.target.value })}
                />
              </Field>
              <Field label="说明">
                <input
                  className="rounded border px-2 py-1 text-sm"
                  value={groupForm.description}
                  onChange={(e) => setGroupForm({ ...groupForm, description: e.target.value })}
                />
              </Field>
              <button className="rounded border px-3 py-1 text-sm" onClick={() => void submitGroup()}>
                保存
              </button>
              <button
                className="rounded border px-3 py-1 text-sm"
                onClick={() => setGroupForm(null)}
              >
                取消
              </button>
            </div>
          )}
        </div>
      </Section>

      <Section title="标签">
        {tags && tags.items.length === 0 ? (
          <p className="text-sm text-amber-600">还没有标签。</p>
        ) : tags ? (
          <div className="overflow-auto">
            <table className="w-full text-sm">
              <thead>
                <tr className="text-muted-foreground text-left text-xs">
                  <th className="py-1">分组</th>
                  <th className="py-1">标签</th>
                  <th className="py-1">打标达人数</th>
                  <th className="py-1">状态</th>
                </tr>
              </thead>
              <tbody>
                {tags.items.map((t) => (
                  <tr key={t.id} className="border-t">
                    <td className="py-1">
                      {t.groupName}（{t.groupCode}）
                    </td>
                    <td className="py-1">
                      {t.tagName}（{t.tagCode}）
                    </td>
                    <td className="py-1">{t.influencerCount}</td>
                    <td className="py-1">{t.status}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        ) : null}
        <div className="flex gap-2">
          {tagForm === null ? (
            <button
              className="rounded border px-3 py-1 text-sm"
              onClick={() => {
                setTagForm(emptyInfluencerTagInput());
                setErrors([]);
              }}
            >
              新建标签
            </button>
          ) : (
            <div className="flex flex-wrap items-end gap-2">
              <Field label="所属分组">
                <select
                  className="rounded border px-2 py-1 text-sm"
                  value={tagForm.groupId}
                  onChange={(e) => setTagForm({ ...tagForm, groupId: e.target.value })}
                >
                  <option value="">请选择分组</option>
                  {(groups?.items ?? []).map((g) => (
                    <option key={g.id} value={g.id}>
                      {g.groupName}（{g.groupCode}）
                    </option>
                  ))}
                </select>
              </Field>
              <Field label="标签编码">
                <input
                  className="rounded border px-2 py-1 text-sm"
                  value={tagForm.tagCode}
                  onChange={(e) => setTagForm({ ...tagForm, tagCode: e.target.value })}
                />
              </Field>
              <Field label="标签名称">
                <input
                  className="rounded border px-2 py-1 text-sm"
                  value={tagForm.tagName}
                  onChange={(e) => setTagForm({ ...tagForm, tagName: e.target.value })}
                />
              </Field>
              <button className="rounded border px-3 py-1 text-sm" onClick={() => void submitTag()}>
                保存
              </button>
              <button className="rounded border px-3 py-1 text-sm" onClick={() => setTagForm(null)}>
                取消
              </button>
            </div>
          )}
        </div>
        {errors.length > 0 ? (
          <ul className="text-destructive text-xs">
            {errors.map((e) => (
              <li key={e}>{e}</li>
            ))}
          </ul>
        ) : null}
      </Section>
    </>
  );
}

// ==================================================================
// 评级
// ==================================================================

function RatingTab() {
  const [rule, setRule] = useState<InfluencerRatingRuleRow | null>(null);
  const [tiers, setTiers] = useState<InfluencerRatingTierPage | null>(null);
  const [ruleForm, setRuleForm] = useState<InfluencerRatingRuleInputState | null>(null);
  const [tierForm, setTierForm] = useState<InfluencerRatingTierInputState | null>(null);
  const [errors, setErrors] = useState<string[]>([]);
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(true);

  const load = useCallback(async () => {
    setLoading(true);
    setError('');
    try {
      const [r, t] = await Promise.all([
        fetchInfluencerRatingRule(),
        fetchInfluencerRatingTiers({ size: 100 }),
      ]);
      setRule(r);
      setTiers(t);
    } catch (e) {
      setError(errorText(e));
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  async function submitRule() {
    if (!ruleForm) return;
    const validation = validateInfluencerRatingRule(ruleForm);
    setErrors(validation.errors);
    if (!validation.ok) return;
    setError('');
    try {
      await saveInfluencerRatingRule({
        ruleCode: ruleForm.ruleCode,
        weightGmv: ruleForm.weightGmv.trim() === '' ? null : Number(ruleForm.weightGmv),
        weightEngagement:
          ruleForm.weightEngagement.trim() === '' ? null : Number(ruleForm.weightEngagement),
        weightRefund: ruleForm.weightRefund.trim() === '' ? null : Number(ruleForm.weightRefund),
        gmvCap: ruleForm.gmvCap.trim() === '' ? null : Number(ruleForm.gmvCap),
        engagementCap:
          ruleForm.engagementCap.trim() === '' ? null : Number(ruleForm.engagementCap),
        refundCap: ruleForm.refundCap.trim() === '' ? null : Number(ruleForm.refundCap),
        status: ruleForm.status,
        note: ruleForm.note,
      });
      setRuleForm(null);
      await load();
    } catch (e) {
      setError(errorText(e));
    }
  }

  async function submitTier() {
    if (!tierForm) return;
    const validation = validateInfluencerRatingTier(tierForm);
    setErrors(validation.errors);
    if (!validation.ok) return;
    setError('');
    try {
      await saveInfluencerRatingTier({
        tierCode: tierForm.tierCode,
        tierName: tierForm.tierName,
        minScore: tierForm.minScore.trim() === '' ? null : Number(tierForm.minScore),
        maxScore: tierForm.maxScore.trim() === '' ? null : Number(tierForm.maxScore),
        sortOrder: tierForm.sortOrder.trim() === '' ? null : Number(tierForm.sortOrder),
        status: tierForm.status,
        note: tierForm.note,
      });
      setTierForm(null);
      await load();
    } catch (e) {
      setError(errorText(e));
    }
  }

  return (
    <>
      <Section title="评分口径（权重 + 归一化上限）">
        {loading ? (
          <p className="text-muted-foreground text-sm">正在加载…</p>
        ) : rule === null ? (
          <p className="text-sm text-amber-600">
            还没有配置评分口径 —— 全库暂无评分（**不是 0 分**）。配好口径后达人的评分会自动算出来。
          </p>
        ) : (
          <dl className="grid grid-cols-2 gap-3 md:grid-cols-4">
            <Stat label="GMV 权重" value={String(rule.weightGmv)} />
            <Stat label="互动率权重" value={String(rule.weightEngagement)} />
            <Stat label="退款率权重" value={String(rule.weightRefund)} />
            <Stat label="GMV 上限" value={String(rule.gmvCap)} />
            <Stat label="互动率上限" value={String(rule.engagementCap)} />
            <Stat label="退款率上限" value={String(rule.refundCap)} />
            <Stat label="状态" value={rule.status} />
            <Stat label="更新时刻" value={timestampText(rule.updatedAt)} />
          </dl>
        )}
        <div className="flex gap-2">
          {ruleForm === null ? (
            <button
              className="rounded border px-3 py-1 text-sm"
              onClick={() => {
                setRuleForm(
                  rule === null
                    ? emptyInfluencerRatingRuleInput()
                    : {
                        ruleCode: rule.ruleCode,
                        weightGmv: String(rule.weightGmv ?? '0'),
                        weightEngagement: String(rule.weightEngagement ?? '0'),
                        weightRefund: String(rule.weightRefund ?? '0'),
                        gmvCap: String(rule.gmvCap ?? ''),
                        engagementCap: String(rule.engagementCap ?? ''),
                        refundCap: String(rule.refundCap ?? ''),
                        status: rule.status,
                        note: rule.note,
                      },
                );
                setErrors([]);
              }}
            >
              配置评分口径
            </button>
          ) : (
            <div className="flex flex-wrap items-end gap-2">
              <Field label="GMV 权重">
                <input
                  className="rounded border px-2 py-1 text-sm"
                  value={ruleForm.weightGmv}
                  onChange={(e) => setRuleForm({ ...ruleForm, weightGmv: e.target.value })}
                />
              </Field>
              <Field label="互动率权重">
                <input
                  className="rounded border px-2 py-1 text-sm"
                  value={ruleForm.weightEngagement}
                  onChange={(e) => setRuleForm({ ...ruleForm, weightEngagement: e.target.value })}
                />
              </Field>
              <Field label="退款率权重">
                <input
                  className="rounded border px-2 py-1 text-sm"
                  value={ruleForm.weightRefund}
                  onChange={(e) => setRuleForm({ ...ruleForm, weightRefund: e.target.value })}
                />
              </Field>
              <Field label="GMV 上限">
                <input
                  className="rounded border px-2 py-1 text-sm"
                  value={ruleForm.gmvCap}
                  onChange={(e) => setRuleForm({ ...ruleForm, gmvCap: e.target.value })}
                />
              </Field>
              <Field label="互动率上限">
                <input
                  className="rounded border px-2 py-1 text-sm"
                  value={ruleForm.engagementCap}
                  onChange={(e) => setRuleForm({ ...ruleForm, engagementCap: e.target.value })}
                />
              </Field>
              <Field label="退款率上限">
                <input
                  className="rounded border px-2 py-1 text-sm"
                  value={ruleForm.refundCap}
                  onChange={(e) => setRuleForm({ ...ruleForm, refundCap: e.target.value })}
                />
              </Field>
              <button className="rounded border px-3 py-1 text-sm" onClick={() => void submitRule()}>
                保存并重算评级
              </button>
              <button className="rounded border px-3 py-1 text-sm" onClick={() => setRuleForm(null)}>
                取消
              </button>
            </div>
          )}
        </div>
      </Section>

      <Section title="评级档位">
        {tiers && tiers.items.length === 0 ? (
          <p className="text-sm text-amber-600">还没有档位 —— 评分算出来也没有归属。</p>
        ) : tiers ? (
          <div className="overflow-auto">
            <table className="w-full text-sm">
              <thead>
                <tr className="text-muted-foreground text-left text-xs">
                  <th className="py-1">档位</th>
                  <th className="py-1">名称</th>
                  <th className="py-1">分数区间</th>
                  <th className="py-1">排序位</th>
                  <th className="py-1">状态</th>
                </tr>
              </thead>
              <tbody>
                {tiers.items.map((t) => (
                  <tr key={t.id} className="border-t">
                    <td className="py-1">{t.tierCode}</td>
                    <td className="py-1">{t.tierName}</td>
                    <td className="py-1">
                      {String(t.minScore)} ~ {String(t.maxScore)}
                    </td>
                    <td className="py-1">{t.sortOrder}</td>
                    <td className="py-1">{t.status}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        ) : null}
        <div className="flex gap-2">
          {tierForm === null ? (
            <button
              className="rounded border px-3 py-1 text-sm"
              onClick={() => {
                setTierForm(emptyInfluencerRatingTierInput());
                setErrors([]);
              }}
            >
              新建档位
            </button>
          ) : (
            <div className="flex flex-wrap items-end gap-2">
              <Field label="档位编码">
                <input
                  className="rounded border px-2 py-1 text-sm"
                  value={tierForm.tierCode}
                  onChange={(e) => setTierForm({ ...tierForm, tierCode: e.target.value })}
                />
              </Field>
              <Field label="档位名称">
                <input
                  className="rounded border px-2 py-1 text-sm"
                  value={tierForm.tierName}
                  onChange={(e) => setTierForm({ ...tierForm, tierName: e.target.value })}
                />
              </Field>
              <Field label="分数下限">
                <input
                  className="rounded border px-2 py-1 text-sm"
                  value={tierForm.minScore}
                  onChange={(e) => setTierForm({ ...tierForm, minScore: e.target.value })}
                />
              </Field>
              <Field label="分数上限">
                <input
                  className="rounded border px-2 py-1 text-sm"
                  value={tierForm.maxScore}
                  onChange={(e) => setTierForm({ ...tierForm, maxScore: e.target.value })}
                />
              </Field>
              <Field label="排序位">
                <input
                  type="number"
                  className="rounded border px-2 py-1 text-sm"
                  value={tierForm.sortOrder}
                  onChange={(e) => setTierForm({ ...tierForm, sortOrder: e.target.value })}
                />
              </Field>
              <button className="rounded border px-3 py-1 text-sm" onClick={() => void submitTier()}>
                保存
              </button>
              <button className="rounded border px-3 py-1 text-sm" onClick={() => setTierForm(null)}>
                取消
              </button>
            </div>
          )}
        </div>
        {errors.length > 0 ? (
          <ul className="text-destructive text-xs">
            {errors.map((e) => (
              <li key={e}>{e}</li>
            ))}
          </ul>
        ) : null}
        {error ? <p className="text-destructive text-sm">{error}</p> : null}
      </Section>
    </>
  );
}

// ==================================================================
// 合作历史
// ==================================================================

function CooperationsTab() {
  const [page, setPage] = useState<InfluencerCooperationPage | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');

  const load = useCallback(async () => {
    setLoading(true);
    setError('');
    try {
      setPage(await fetchInfluencerCooperations({ size: 100 }));
    } catch (e) {
      setError(errorText(e));
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  return (
    <Section title="合作历史（达人 × 活动）">
      {loading ? (
        <p className="text-muted-foreground text-sm">正在加载合作历史…</p>
      ) : error ? (
        <p className="text-destructive text-sm">{error}</p>
      ) : page && page.items.length === 0 ? (
        <p className="text-sm text-amber-600">
          还没有合作记录 —— 在达人详情里能看到某个达人的合作历史。
        </p>
      ) : page ? (
        <div className="overflow-auto">
          <table className="w-full text-sm">
            <thead>
              <tr className="text-muted-foreground text-left text-xs">
                <th className="py-1">达人</th>
                <th className="py-1">活动</th>
                <th className="py-1">状态</th>
                <th className="py-1">时间</th>
                <th className="py-1">GMV</th>
                <th className="py-1">订单量</th>
                <th className="py-1">佣金</th>
                <th className="py-1">退款率</th>
              </tr>
            </thead>
            <tbody>
              {page.items.map((c) => (
                <tr key={c.id} className="border-t">
                  <td className="py-1">{c.influencerName}</td>
                  <td className="py-1">
                    {c.campaignName}
                    <div className="text-muted-foreground text-xs">{c.externalCampaignId}</div>
                  </td>
                  <td className="py-1">{cooperationStatusLabel(c.status)}</td>
                  <td className="text-muted-foreground py-1 text-xs">
                    {timestampText(c.startedAt)} ~ {timestampText(c.endedAt)}
                  </td>
                  <td className="py-1">{effectMoney(c.gmv, c.currency)}</td>
                  <td className="py-1">{effectCount(c.orderCount)}</td>
                  <td className="py-1">{effectMoney(c.commission, c.currency)}</td>
                  <td className="py-1">{effectRatio(c.refundRate)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      ) : null}
    </Section>
  );
}

// ==================================================================
// 通用零件
// ==================================================================

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

function Pagination({
  page,
  size,
  total,
  onChange,
}: {
  page: number;
  size: number;
  total: number;
  onChange: (page: number) => void;
}) {
  const last = Math.max(Math.ceil(total / Math.max(size, 1)) - 1, 0);
  return (
    <div className="flex items-center gap-2 text-sm">
      <button
        className="rounded border px-2 py-1 disabled:opacity-40"
        disabled={page <= 0}
        onClick={() => onChange(page - 1)}
      >
        上一页
      </button>
      <span className="text-muted-foreground text-xs">
        第 {page + 1} / {last + 1} 页 · 共 {total} 条
      </span>
      <button
        className="rounded border px-2 py-1 disabled:opacity-40"
        disabled={page >= last}
        onClick={() => onChange(page + 1)}
      >
        下一页
      </button>
    </div>
  );
}
