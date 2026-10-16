'use client';

import { useCallback, useEffect, useState } from 'react';
import { errorText } from '../inventory/inventory-model';
import {
  adStatusLabel,
  auctionLabel,
  emptyComparableText,
  emptyFieldSpecInput,
  emptyPayloadInput,
  emptyProfileInput,
  fetchAdTypeCapabilities,
  fetchComparable,
  fetchFidelity,
  fetchPayload,
  fetchPayloads,
  fetchProfile,
  fetchProfiles,
  fidelityLine,
  internalTypeLabel,
  isUuid,
  mappingSourceLabel,
  mappingStatusLabel,
  mappingStatusTone,
  money,
  objectiveLabel,
  quantity,
  ratioText,
  ratioTone,
  resolveAdType,
  saveFieldSpec,
  savePayload,
  saveProfile,
  timestampText,
  validateFieldSpec,
  validatePayload,
  validateProfile,
} from './ad-type-diff-model';
import type {
  AdTypeDiffCapabilities,
  AdTypePayloadDetail,
  AdTypePayloadPage,
  AdTypeProfileDetail,
  AdTypeProfilePage,
  ComparableView,
  FieldSpecInput,
  FidelityOverview,
  MappingResolution,
  PayloadInput,
  ProfileInput,
} from './ad-type-diff-model';

/**
 * 14.1.2.2 广告类型差异。
 *
 * 🔴 页面顶部三句话不是提示语，是这一页的设计前提：
 *
 * 1. **归一不在浏览器里做**。内部类型来自上游 2.1.2.1（页面点「按 2.1.2.1 解析」把结果带出来），
 *    可比汇总与派生比率全部由服务端算。浏览器里没有一处把平台原始类型映射到内部类型 ——
 *    前端映射一遍就意味着「界面上的归一」与「落库、上报的归一」会有两个算法。
 * 2. **🔴 原始类型标记与只留在原文里的字段必须看得见**。每一行都同时显示
 *    「原始标记 → 归一类型」与「哪些字段没有内部落点」。把它们藏起来，
 *    蓝图那句「不能丢信息」就只是一句口号。
 * 3. **未映射不是「没有这种广告」**。它在可比表下方单列，带完整原始标记与原因；
 *    「没有可比口径」与「没有这种广告」是相反的两件事。
 */
export function AdTypeDiffWorkspace() {
  const [capabilities, setCapabilities] = useState<AdTypeDiffCapabilities | null>(null);
  const [comparable, setComparable] = useState<ComparableView | null>(null);
  const [fidelity, setFidelity] = useState<FidelityOverview | null>(null);
  const [profiles, setProfiles] = useState<AdTypeProfilePage | null>(null);
  const [profileDetail, setProfileDetail] = useState<AdTypeProfileDetail | null>(null);
  const [payloads, setPayloads] = useState<AdTypePayloadPage | null>(null);
  const [payloadDetail, setPayloadDetail] = useState<AdTypePayloadDetail | null>(null);

  const [profileInput, setProfileInput] = useState<ProfileInput>(emptyProfileInput);
  const [resolution, setResolution] = useState<MappingResolution | null>(null);
  const [mappingSource, setMappingSource] = useState('UNMAPPED');
  const [profileErrors, setProfileErrors] = useState<string[]>([]);

  const [fieldSpecInput, setFieldSpecInput] = useState<FieldSpecInput>(emptyFieldSpecInput);
  const [fieldSpecErrors, setFieldSpecErrors] = useState<string[]>([]);

  const [payloadInput, setPayloadInput] = useState<PayloadInput>(emptyPayloadInput);
  const [payloadErrors, setPayloadErrors] = useState<string[]>([]);

  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');

  const loadAll = useCallback(async () => {
    setLoading(true);
    setError('');
    try {
      const [caps, view, fid, prof, pays] = await Promise.all([
        fetchAdTypeCapabilities(),
        fetchComparable(),
        fetchFidelity(),
        fetchProfiles({ size: 50 }),
        fetchPayloads({ size: 20 }),
      ]);
      setCapabilities(caps);
      setComparable(view);
      setFidelity(fid);
      setProfiles(prof);
      setPayloads(pays);
    } catch (e) {
      setError(errorText(e));
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void loadAll();
  }, [loadAll]);

  // ---- 归一口径：走 2.1.2.1 两层解析（无副作用）----

  async function onResolve() {
    setBusy(true);
    setError('');
    try {
      const result = await resolveAdType(profileInput.platformId, profileInput.platformAdType.trim());
      setResolution(result);
      // 🔴 归一类型由解析结果带出来，页面**不让人手填** ——
      //    手填就等于让页面成为归一口径的第二个来源。
      setProfileInput((prev) => ({ ...prev, internalAdType: result.internalAdType }));
      setMappingSource(result.mappingSource);
    } catch (e) {
      setError(errorText(e));
    } finally {
      setBusy(false);
    }
  }

  async function onSaveProfile() {
    const validation = validateProfile(profileInput, mappingSource);
    setProfileErrors(validation.errors);
    if (!validation.ok) return;
    setBusy(true);
    setError('');
    try {
      await saveProfile(profileInput, mappingSource);
      setProfileInput(emptyProfileInput);
      setResolution(null);
      setMappingSource('UNMAPPED');
      await loadAll();
    } catch (e) {
      setError(errorText(e));
    } finally {
      setBusy(false);
    }
  }

  async function onOpenProfile(id: string) {
    setBusy(true);
    setError('');
    try {
      setProfileDetail(await fetchProfile(id));
      setPayloadInput((prev) => ({ ...prev, adTypeProfileId: id }));
    } catch (e) {
      setError(errorText(e));
    } finally {
      setBusy(false);
    }
  }

  async function onSaveFieldSpec() {
    if (!profileDetail) return;
    const validation = validateFieldSpec(fieldSpecInput);
    setFieldSpecErrors(validation.errors);
    if (!validation.ok) return;
    setBusy(true);
    setError('');
    try {
      await saveFieldSpec(profileDetail.profile.id, fieldSpecInput);
      setFieldSpecInput(emptyFieldSpecInput);
      setProfileDetail(await fetchProfile(profileDetail.profile.id));
      await loadAll();
    } catch (e) {
      setError(errorText(e));
    } finally {
      setBusy(false);
    }
  }

  async function onSavePayload() {
    const validation = validatePayload(payloadInput);
    setPayloadErrors(validation.errors);
    if (!validation.ok) return;
    setBusy(true);
    setError('');
    try {
      // 🔴 这里的响应带回 DB 派生的 mappingStatus 与 rawOnlyFieldKeys ——
      //    服务层声称的保真结果在界面上是不作数的。
      const saved = await savePayload(payloadInput);
      setPayloadErrors([]);
      setPayloadInput({ ...emptyPayloadInput, adTypeProfileId: payloadInput.adTypeProfileId });
      await loadAll();
      setPayloadDetail(await fetchPayload(saved.row.id));
    } catch (e) {
      setError(errorText(e));
    } finally {
      setBusy(false);
    }
  }

  async function onOpenPayload(id: string) {
    setBusy(true);
    setError('');
    try {
      setPayloadDetail(await fetchPayload(id));
    } catch (e) {
      setError(errorText(e));
    } finally {
      setBusy(false);
    }
  }

  return (
    <main className="flex flex-col gap-6 p-6">
      <header className="flex flex-col gap-2">
        <h1 className="text-2xl font-semibold">广告类型差异</h1>
        <p className="text-sm text-muted-foreground max-w-4xl">
          把自动广告、SB/SD、品牌广告、DSP、大促广告在各个平台上的字段差异归一到内部模型，
          让跨平台广告第一次可比。归一口径来自上游 2.1.2.1 平台枚举映射（租户覆盖 &gt; 平台默认 &gt;
          未映射），<strong>原始类型标记与原始字段原文始终保留</strong>。
        </p>
      </header>

      {error ? <p className="text-destructive text-sm">{error}</p> : null}

      {loading ? (
        <p className="text-sm text-muted-foreground">正在加载广告类型定义与归一后的广告数据…</p>
      ) : null}

      {/* ================= 归一后的广告类型视图 ================= */}
      <Section title="归一后的广告类型视图（跨平台可比）">
        <p className="text-xs text-muted-foreground">{comparable?.note}</p>
        {!loading && (comparable?.types.length ?? 0) === 0 ? (
          <p className="text-sm text-muted-foreground">{emptyComparableText(comparable)}</p>
        ) : null}
        {(comparable?.types ?? []).map((summary) => (
          <article key={summary.internalAdType} className="rounded border p-3 flex flex-col gap-2">
            <h3 className="font-medium">
              {internalTypeLabel(summary.internalAdType)}{' '}
              <span className="text-xs text-muted-foreground">{summary.internalAdType}</span>
            </h3>
            <p className="text-xs text-muted-foreground">{summary.comparabilityNote}</p>
            {/* 🔴 原始标记必须与归一类型同排出现 —— 这是「可比」与「可追溯」同时成立的唯一办法 */}
            <ul className="flex flex-wrap gap-2 text-xs">
              {summary.markers.map((marker) => (
                <li key={`${marker.platformId}-${marker.platformAdType}`} className="rounded border px-2 py-1">
                  {marker.platformCode} · <code>{marker.platformAdType}</code> · {marker.displayName} ·{' '}
                  {marker.adCount} 条
                </li>
              ))}
            </ul>
            <dl className="grid grid-cols-2 md:grid-cols-4 gap-2 text-sm">
              <Stat label="广告条数" value={String(summary.adCount)} />
              <Stat label="覆盖平台" value={String(summary.platformCount)} />
              <Stat label="拍卖方式" value={summary.auctionModels.map(auctionLabel).join('、') || '—'} />
              <Stat label="投放目标" value={summary.objectives.map(objectiveLabel).join('、') || '—'} />
              <Stat label="展示量" value={quantity(summary.impressions)} />
              <Stat label="点击量" value={quantity(summary.clicks)} />
              <Stat label="花费合计" value={money(summary.spend)} />
              <Stat label="归因销售额" value={money(summary.attributedRevenue)} />
            </dl>
          </article>
        ))}

        {/* 🔴 未映射的类型单列，不混进可比汇总 —— 它存在，只是没有可比口径 */}
        {(comparable?.unmappedTypes.length ?? 0) > 0 ? (
          <div className="rounded border p-3 flex flex-col gap-2">
            <h3 className="font-medium text-sm">
              未映射的原始类型（{comparable?.unmappedTypes.length} 个，不参与横向比较）
            </h3>
            <ul className="flex flex-col gap-2 text-sm">
              {comparable?.unmappedTypes.map((unmapped) => (
                <li key={`${unmapped.platformId}-${unmapped.platformAdType}`} className="flex flex-col">
                  <span>
                    {unmapped.platformCode} · <code>{unmapped.platformAdType}</code> ·{' '}
                    {unmapped.displayName} · {unmapped.adCount} 条
                  </span>
                  <span className="text-xs text-muted-foreground">{unmapped.reason}</span>
                </li>
              ))}
            </ul>
          </div>
        ) : null}
      </Section>

      {/* ================= 保真概览 ================= */}
      <Section title="保真概览（系统没有归一哪些字段）">
        <p className="text-xs text-muted-foreground">{fidelity?.note}</p>
        {fidelity ? (
          <dl className="grid grid-cols-2 md:grid-cols-4 gap-2 text-sm">
            <Stat label="归一后广告总数" value={String(fidelity.totalAds)} />
            <Stat label="完全归一" value={String(fidelity.normalizedAds)} />
            <Stat label="部分覆盖" value={String(fidelity.partialAds)} />
            <Stat label="未映射类型" value={String(fidelity.unmappedAds)} />
          </dl>
        ) : null}
        {(fidelity?.rawOnlyFields.length ?? 0) > 0 ? (
          <table className="w-full text-sm">
            <thead>
              <tr className="text-left border-b">
                <th className="py-1">只留在原始报文里的字段</th>
                <th className="py-1">出现条数</th>
              </tr>
            </thead>
            <tbody>
              {fidelity?.rawOnlyFields.map((field) => (
                <tr key={field.fieldKey} className="border-b">
                  <td className="py-1">
                    <code>{field.fieldKey}</code>
                  </td>
                  <td className="py-1">{field.occurrences}</td>
                </tr>
              ))}
            </tbody>
          </table>
        ) : (
          <p className="text-sm text-muted-foreground">
            没有任何字段只留在原始报文里 —— 全部原始字段都有归一落点。
          </p>
        )}
      </Section>

      {/* ================= 广告类型档案 ================= */}
      <Section title="各平台广告类型定义（原始标记 → 归一后内部类型）">
        <div className="grid md:grid-cols-2 gap-3">
          <Field label="平台 id（上游 2.1.1.1 平台目录）">
            <input
              className="border rounded px-2 py-1"
              value={profileInput.platformId}
              placeholder="11111111-1111-1111-1111-111111111111"
              onChange={(e) => setProfileInput({ ...profileInput, platformId: e.target.value })}
            />
          </Field>
          <Field label="🔴 原始平台类型标记（原样存，不折叠）">
            <input
              className="border rounded px-2 py-1"
              value={profileInput.platformAdType}
              placeholder="sponsored_display"
              onChange={(e) => setProfileInput({ ...profileInput, platformAdType: e.target.value })}
            />
          </Field>
        </div>
        <div className="flex flex-wrap items-end gap-3">
          <button
            className="border rounded px-3 py-1 text-sm"
            disabled={busy || !isUuid(profileInput.platformId) || !profileInput.platformAdType.trim()}
            onClick={onResolve}
          >
            按 2.1.2.1 解析
          </button>
          {resolution ? (
            <p className="text-sm">
              {resolution.mapped ? (
                <>
                  归一到 <strong>{internalTypeLabel(resolution.internalAdType)}</strong>
                  <span className="text-muted-foreground">
                    （{resolution.mappingSource === 'TENANT_OVERRIDE' ? '租户覆盖' : '平台默认'}）
                  </span>
                </>
              ) : (
                <span className="text-amber-600">🔴 未映射 —— {resolution.unmappedReason}</span>
              )}
            </p>
          ) : null}
        </div>
        <div className="grid md:grid-cols-2 gap-3">
          <Field label="归一后内部类型（由解析带出，不可手填）">
            <input
              className="border rounded px-2 py-1 bg-muted/40"
              value={profileInput.internalAdType}
              readOnly
              placeholder="先点「按 2.1.2.1 解析」"
            />
          </Field>
          <Field label="展示名">
            <input
              className="border rounded px-2 py-1"
              value={profileInput.displayName}
              onChange={(e) => setProfileInput({ ...profileInput, displayName: e.target.value })}
            />
          </Field>
          <Field label="拍卖方式（跨平台可比的关键口径）">
            <select
              className="border rounded px-2 py-1"
              value={profileInput.auctionModel}
              onChange={(e) => setProfileInput({ ...profileInput, auctionModel: e.target.value })}
            >
              {(capabilities?.auctionModels ?? ['CPC']).map((model) => (
                <option key={model} value={model}>
                  {auctionLabel(model)}
                </option>
              ))}
            </select>
          </Field>
          <Field label="投放目标（跨平台可比的关键口径）">
            <select
              className="border rounded px-2 py-1"
              value={profileInput.objective}
              onChange={(e) => setProfileInput({ ...profileInput, objective: e.target.value })}
            >
              {(capabilities?.objectives ?? ['SALES']).map((objective) => (
                <option key={objective} value={objective}>
                  {objectiveLabel(objective)}
                </option>
              ))}
            </select>
          </Field>
        </div>
        <div className="flex flex-wrap gap-4 text-sm">
          {(
            [
              ['supportsTargeting', '支持定向'],
              ['supportsBidSchedule', '支持分时竞价'],
              ['supportsNegativeKw', '支持否定关键词'],
              ['supportsCreative', '支持创意素材'],
              ['supportsAsciiAd', '支持 ASCII 广告位'],
            ] as const
          ).map(([key, label]) => (
            <label key={key} className="flex items-center gap-1">
              <input
                type="checkbox"
                checked={profileInput[key]}
                onChange={(e) => setProfileInput({ ...profileInput, [key]: e.target.checked })}
              />
              {label}
            </label>
          ))}
        </div>
        {profileErrors.length > 0 ? (
          <ul className="text-destructive text-sm">
            {profileErrors.map((message) => (
              <li key={message}>{message}</li>
            ))}
          </ul>
        ) : null}
        <div>
          <button className="border rounded px-3 py-1 text-sm" disabled={busy} onClick={onSaveProfile}>
            登记档案
          </button>
        </div>

        {(profiles?.items.length ?? 0) === 0 && !loading ? (
          <p className="text-sm text-muted-foreground">
            本租户还没有登记任何广告类型档案。空结果不代表「这些广告不存在」，
            它代表「还没有输入」—— 先登记一个平台 × 原始类型，再录入平台 API 返回的广告。
          </p>
        ) : null}

        {profiles && profiles.items.length > 0 ? (
          <table className="w-full text-sm">
            <thead>
              <tr className="text-left border-b">
                <th className="py-1">平台</th>
                <th className="py-1">🔴 原始标记</th>
                <th className="py-1">🔴 归一后类型</th>
                <th className="py-1">拍卖 / 目标</th>
                <th className="py-1">归一口径来源</th>
                <th className="py-1">特有字段 / 广告数</th>
                <th className="py-1" />
              </tr>
            </thead>
            <tbody>
              {profiles.items.map((profile) => (
                <tr key={profile.id} className="border-b">
                  <td className="py-1">{profile.platformCode}</td>
                  <td className="py-1">
                    <code>{profile.platformAdType}</code>
                  </td>
                  <td className="py-1">{internalTypeLabel(profile.internalAdType)}</td>
                  <td className="py-1 text-xs">
                    {auctionLabel(profile.auctionModel)} / {objectiveLabel(profile.objective)}
                  </td>
                  <td className="py-1 text-xs">{mappingSourceLabel(profile.mappingSource)}</td>
                  <td className="py-1 text-xs">
                    {profile.fieldSpecCount} / {profile.payloadCount}
                  </td>
                  <td className="py-1">
                    <button className="border rounded px-2 py-0.5 text-xs" onClick={() => onOpenProfile(profile.id)}>
                      详情
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        ) : null}
      </Section>

      {/* ================= 特有字段定义 ================= */}
      {profileDetail ? (
        <Section title={`特有字段定义 · ${profileDetail.profile.platformAdType}`}>
          <p className="text-xs text-muted-foreground">{profileDetail.note}</p>
          {profileDetail.fieldSpecs.length > 0 ? (
            <table className="w-full text-sm">
              <thead>
                <tr className="text-left border-b">
                  <th className="py-1">原始字段键</th>
                  <th className="py-1">标签</th>
                  <th className="py-1">类型</th>
                  <th className="py-1">可比</th>
                  <th className="py-1">归一后的内部落点</th>
                </tr>
              </thead>
              <tbody>
                {profileDetail.fieldSpecs.map((spec) => (
                  <tr key={spec.id} className="border-b">
                    <td className="py-1">
                      <code>{spec.fieldKey}</code>
                    </td>
                    <td className="py-1">{spec.fieldLabel}</td>
                    <td className="py-1 text-xs">
                      {spec.dataType}
                      {spec.unit ? `（${spec.unit}）` : ''}
                    </td>
                    <td className="py-1 text-xs">{spec.comparable ? '是' : '否'}</td>
                    <td className="py-1 text-xs">
                      {spec.internalRepresentation || (
                        <span className="text-amber-600">无（只原样留在原始报文里）</span>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          ) : (
            <p className="text-sm text-muted-foreground">
              还没有为这个类型定义任何特有字段。没有定义的话，所有原始字段都会落进
              「只留在原文里」的清单，且界面上没有标签可显示。
            </p>
          )}
          {profileDetail.undocumentedKeys.length > 0 ? (
            <p className="text-sm text-amber-600">
              🔴 原始报文里出现、但没有任何字段定义覆盖的键：
              {profileDetail.undocumentedKeys.map((key) => (
                <code key={key} className="ml-1">
                  {key}
                </code>
              ))}
            </p>
          ) : null}

          <div className="grid md:grid-cols-3 gap-3">
            <Field label="🔴 原始字段键">
              <input
                className="border rounded px-2 py-1"
                value={fieldSpecInput.fieldKey}
                onChange={(e) => setFieldSpecInput({ ...fieldSpecInput, fieldKey: e.target.value })}
              />
            </Field>
            <Field label="界面标签">
              <input
                className="border rounded px-2 py-1"
                value={fieldSpecInput.fieldLabel}
                onChange={(e) => setFieldSpecInput({ ...fieldSpecInput, fieldLabel: e.target.value })}
              />
            </Field>
            <Field label="数据类型">
              <select
                className="border rounded px-2 py-1"
                value={fieldSpecInput.dataType}
                onChange={(e) => setFieldSpecInput({ ...fieldSpecInput, dataType: e.target.value })}
              >
                {(capabilities?.fieldDataTypes ?? ['STRING']).map((type) => (
                  <option key={type} value={type}>
                    {type}
                  </option>
                ))}
              </select>
            </Field>
            <Field label="归一后的内部落点（可比时必填）">
              <input
                className="border rounded px-2 py-1"
                value={fieldSpecInput.internalRepresentation}
                placeholder="default_bid"
                onChange={(e) =>
                  setFieldSpecInput({ ...fieldSpecInput, internalRepresentation: e.target.value })
                }
              />
            </Field>
            <Field label="展示顺序">
              <input
                className="border rounded px-2 py-1"
                value={fieldSpecInput.position}
                onChange={(e) => setFieldSpecInput({ ...fieldSpecInput, position: e.target.value })}
              />
            </Field>
            <label className="flex items-end gap-1 text-sm">
              <input
                type="checkbox"
                checked={fieldSpecInput.comparable}
                onChange={(e) => setFieldSpecInput({ ...fieldSpecInput, comparable: e.target.checked })}
              />
              参与跨平台可比
            </label>
          </div>
          {fieldSpecErrors.length > 0 ? (
            <ul className="text-destructive text-sm">
              {fieldSpecErrors.map((message) => (
                <li key={message}>{message}</li>
              ))}
            </ul>
          ) : null}
          <div>
            <button className="border rounded px-3 py-1 text-sm" disabled={busy} onClick={onSaveFieldSpec}>
              登记特有字段
            </button>
          </div>
        </Section>
      ) : null}

      {/* ================= 归一后的广告数据（平台 API 返回） ================= */}
      <Section title="归一后的广告数据（平台 API 返回 → 内部模型）">
        <div className="grid md:grid-cols-2 gap-3">
          <Field label="档案 id（先在上方点「详情」自动带入）">
            <input
              className="border rounded px-2 py-1"
              value={payloadInput.adTypeProfileId}
              onChange={(e) => setPayloadInput({ ...payloadInput, adTypeProfileId: e.target.value })}
            />
          </Field>
          <Field label="店铺编码">
            <input
              className="border rounded px-2 py-1"
              value={payloadInput.shopCode}
              onChange={(e) => setPayloadInput({ ...payloadInput, shopCode: e.target.value })}
            />
          </Field>
          <Field label="平台侧广告 id">
            <input
              className="border rounded px-2 py-1"
              value={payloadInput.externalAdId}
              onChange={(e) => setPayloadInput({ ...payloadInput, externalAdId: e.target.value })}
            />
          </Field>
          <Field label="归一后状态">
            <select
              className="border rounded px-2 py-1"
              value={payloadInput.adStatus}
              onChange={(e) => setPayloadInput({ ...payloadInput, adStatus: e.target.value })}
            >
              {['ENABLED', 'PAUSED', 'ENDED', 'ARCHIVED', 'PENDING_REVIEW', 'UNKNOWN'].map((status) => (
                <option key={status} value={status}>
                  {adStatusLabel(status)}
                </option>
              ))}
            </select>
          </Field>
          <Field label="日预算">
            <input
              className="border rounded px-2 py-1"
              value={payloadInput.dailyBudget}
              onChange={(e) => setPayloadInput({ ...payloadInput, dailyBudget: e.target.value })}
            />
          </Field>
          <Field label="默认出价">
            <input
              className="border rounded px-2 py-1"
              value={payloadInput.defaultBid}
              onChange={(e) => setPayloadInput({ ...payloadInput, defaultBid: e.target.value })}
            />
          </Field>
          <Field label="展示量">
            <input
              className="border rounded px-2 py-1"
              value={payloadInput.impressions}
              onChange={(e) => setPayloadInput({ ...payloadInput, impressions: e.target.value })}
            />
          </Field>
          <Field label="点击量">
            <input
              className="border rounded px-2 py-1"
              value={payloadInput.clicks}
              onChange={(e) => setPayloadInput({ ...payloadInput, clicks: e.target.value })}
            />
          </Field>
          <Field label="转化量">
            <input
              className="border rounded px-2 py-1"
              value={payloadInput.conversions}
              onChange={(e) => setPayloadInput({ ...payloadInput, conversions: e.target.value })}
            />
          </Field>
          <Field label="花费">
            <input
              className="border rounded px-2 py-1"
              value={payloadInput.spend}
              onChange={(e) => setPayloadInput({ ...payloadInput, spend: e.target.value })}
            />
          </Field>
          <Field label="归因销售额">
            <input
              className="border rounded px-2 py-1"
              value={payloadInput.attributedRevenue}
              onChange={(e) => setPayloadInput({ ...payloadInput, attributedRevenue: e.target.value })}
            />
          </Field>
          <Field label="原始报文抓取时刻">
            <input
              className="border rounded px-2 py-1"
              value={payloadInput.rawFetchedAt}
              placeholder="2026-10-01T09:00:00+08:00"
              onChange={(e) => setPayloadInput({ ...payloadInput, rawFetchedAt: e.target.value })}
            />
          </Field>
        </div>
        <Field label="🔴 原始差异字段原文（JSON 对象，一个字不改 —— 这是「不能丢信息」的兜底）">
          <textarea
            className="border rounded px-2 py-1 font-mono text-xs"
            rows={5}
            value={payloadInput.rawPayload}
            onChange={(e) => setPayloadInput({ ...payloadInput, rawPayload: e.target.value })}
          />
        </Field>
        <Field label="🔴 已归一的键（逗号分隔；声称归一了原文里没有的键会被服务端拒）">
          <input
            className="border rounded px-2 py-1"
            value={payloadInput.normalizedFieldKeys}
            placeholder="bid, headlineText"
            onChange={(e) => setPayloadInput({ ...payloadInput, normalizedFieldKeys: e.target.value })}
          />
        </Field>
        {payloadErrors.length > 0 ? (
          <ul className="text-destructive text-sm">
            {payloadErrors.map((message) => (
              <li key={message}>{message}</li>
            ))}
          </ul>
        ) : null}
        <div>
          <button className="border rounded px-3 py-1 text-sm" disabled={busy} onClick={onSavePayload}>
            登记归一后的广告
          </button>
        </div>

        {(payloads?.items.length ?? 0) === 0 && !loading ? (
          <p className="text-sm text-muted-foreground">
            本租户还没有归一后的广告数据。空结果不代表「平台没有广告」，
            它代表「还没有录入平台 API 返回的内容」。
          </p>
        ) : null}

        {payloads && payloads.items.length > 0 ? (
          <table className="w-full text-sm">
            <thead>
              <tr className="text-left border-b">
                <th className="py-1">🔴 原始标记 → 归一类型</th>
                <th className="py-1">平台广告 id</th>
                <th className="py-1">状态</th>
                <th className="py-1">花费</th>
                <th className="py-1">CTR / CVR / ACOS</th>
                <th className="py-1">保真</th>
                <th className="py-1" />
              </tr>
            </thead>
            <tbody>
              {payloads.items.map((payload) => (
                <tr key={payload.id} className="border-b">
                  <td className="py-1 text-xs">
                    <code>{payload.platformAdType}</code> → {internalTypeLabel(payload.internalAdType)}
                  </td>
                  <td className="py-1">{payload.externalAdId}</td>
                  <td className="py-1">{adStatusLabel(payload.adStatus)}</td>
                  <td className="py-1">{money(payload.spend, payload.currency)}</td>
                  <td className="py-1 text-xs">
                    <span className={ratioTone(payload.ctr)}>{ratioText(payload.ctr)}</span> /{' '}
                    <span className={ratioTone(payload.cvr)}>{ratioText(payload.cvr)}</span> /{' '}
                    <span className={ratioTone(payload.acos)}>{ratioText(payload.acos)}</span>
                  </td>
                  <td className={`py-1 text-xs ${mappingStatusTone(payload.mappingStatus)}`}>
                    {mappingStatusLabel(payload.mappingStatus)}
                    {payload.rawOnlyFieldKeys.length > 0 ? (
                      <span className="block text-muted-foreground">
                        {payload.rawOnlyFieldKeys.join('、')}
                      </span>
                    ) : null}
                  </td>
                  <td className="py-1">
                    <button className="border rounded px-2 py-0.5 text-xs" onClick={() => onOpenPayload(payload.id)}>
                      详情
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        ) : null}
      </Section>

      {/* ================= 详情：特有字段补充展示 ================= */}
      {payloadDetail ? (
        <Section title={`特有字段补充展示 · ${payloadDetail.payload.externalAdId}`}>
          <p className="text-sm">{fidelityLine(payloadDetail.payload)}</p>
          <p className="text-xs text-muted-foreground">{payloadDetail.fidelityNote}</p>
          {payloadDetail.extraFields.length > 0 ? (
            <table className="w-full text-sm">
              <thead>
                <tr className="text-left border-b">
                  <th className="py-1">字段</th>
                  <th className="py-1">🔴 原始值（平台原样）</th>
                  <th className="py-1">是否可比</th>
                  <th className="py-1">解释</th>
                </tr>
              </thead>
              <tbody>
                {payloadDetail.extraFields.map((field) => (
                  <tr key={field.fieldKey} className="border-b">
                    <td className="py-1">
                      {field.fieldLabel}
                      <span className="block text-xs text-muted-foreground">
                        <code>{field.fieldKey}</code>
                        {field.unit ? ` · ${field.unit}` : ''}
                      </span>
                    </td>
                    <td className="py-1">
                      <code className="text-xs">{field.rawValue || '—'}</code>
                    </td>
                    <td className="py-1 text-xs">{field.normalized ? '是' : '否'}</td>
                    <td className="py-1 text-xs text-muted-foreground">{field.comparabilityNote}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          ) : (
            <p className="text-sm text-muted-foreground">
              这个广告类型还没有定义任何特有字段，因此没有可展示的补充字段。
            </p>
          )}
          {payloadDetail.undocumentedKeys.length > 0 ? (
            <p className="text-sm text-amber-600">
              🔴 原始报文里没有任何字段定义覆盖的键：
              {payloadDetail.undocumentedKeys.map((key) => (
                <code key={key} className="ml-1">
                  {key}
                </code>
              ))}
            </p>
          ) : null}
          <details>
            <summary className="text-sm cursor-pointer">原始差异字段原文（平台 API 返回，一个字没改）</summary>
            <pre className="text-xs bg-muted/40 rounded p-2 overflow-auto">
              {JSON.stringify(payloadDetail.rawPayload, null, 2)}
            </pre>
          </details>
        </Section>
      ) : null}
    </main>
  );
}

function Section({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <section className="rounded border p-4 flex flex-col gap-3">
      <h2 className="font-medium">{title}</h2>
      {children}
    </section>
  );
}

function Stat({ label, value }: { label: string; value: string }) {
  return (
    <div>
      <dt className="text-xs text-muted-foreground">{label}</dt>
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
