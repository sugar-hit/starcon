'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import { Badge, Button, EmptyState, SectionCard } from '@starcon/ui';
import { BookOpen, Plus, RefreshCw, Send, ShieldAlert } from 'lucide-react';
import type {
  CrmCreateEntryInput,
  CrmCreateVersionInput,
  CrmKnowledgeCapabilities,
  CrmKnowledgeEntry,
  CrmKnowledgeEntryDetail,
  CrmKnowledgeReference,
  CrmKnowledgeScope,
} from '../../../../../packages/api-contracts/src/catalog';
import { Field, control } from '../common/fields';
import {
  closeVersion,
  consumerLabel,
  createEntry,
  createVersion,
  fetchCapabilities,
  fetchEffectiveEntries,
  fetchEntry,
  fetchEntryReferences,
  fetchEntries,
  fetchReviewDue,
  fetchScopes,
  formatInterval,
  publishVersion,
  topicTypeLabel,
} from './knowledge-model';
import { useIdentity } from '../iam/identity-shell';

/**
 * 15.4.2.1 客服知识库工作区。
 *
 * <p>页面按「先定位、再判断、最后动作」排。每个区块回答一个独立的问题：
 *
 * <ul>
 *   <li><b>组织范围</b> —— 本租户库里真实存在过哪几个「店铺 × 类目 × 语言」格子。
 *       三个下拉框的候选值全部来自 {@code GET /crm/knowledge/scopes}，
 *       <b>不是</b>前端写死的清单（这是「按店铺/类目/语言组织」不沦为摆设的前提）。</li>
 *   <li><b>知识条目</b> —— 按格子筛选，每条显形「此刻生效的是哪一版、生效区间是什么」。
 *       没有生效版本的条目<b>显红</b>：它此刻拿不出任何答法。</li>
 *   <li><b>平台改版待复核</b> —— 填平台 + 改版生效时刻，一次列出待复核条目，
 *       并把后端返回的判定口径原文显示出来，让运营能判断这个结果可不可信。</li>
 *   <li><b>版本与引用</b> —— 单条知识的全部版本与引用记录。引用<b>只读</b>：
 *       本模块刻意不给「删除引用」按钮，删掉等于把「没人用过」这个答案做出来。</li>
 * </ul>
 *
 * <p><b>与 11.4.2.2 的 /aftersales/knowledge 不是同一个页面</b>：那边是 catalog 服务的
 * 客服话术（按平台规则版本组织），这里是 crm 服务的客服知识（按店铺/类目/语言组织）。
 */
export function KnowledgeWorkspace() {
  const { session } = useIdentity();
  const writable = session.permissions.includes('crm.knowledge.update');

  const [capabilities, setCapabilities] = useState<CrmKnowledgeCapabilities | null>(null);
  const [scopes, setScopes] = useState<CrmKnowledgeScope[]>([]);
  const [rows, setRows] = useState<CrmKnowledgeEntry[]>([]);
  const [effectiveOnly, setEffectiveOnly] = useState(true);

  const [shopId, setShopId] = useState('');
  const [categoryId, setCategoryId] = useState('');
  const [locale, setLocale] = useState('');
  const [platformCode, setPlatformCode] = useState('');
  const [topicType, setTopicType] = useState('');

  const [ready, setReady] = useState(false);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);
  const [notice, setNotice] = useState('');
  const [busy, setBusy] = useState(false);

  const [detail, setDetail] = useState<CrmKnowledgeEntryDetail | null>(null);
  const [references, setReferences] = useState<CrmKnowledgeReference[]>([]);
  const [draft, setDraft] = useState<CrmCreateEntryInput | null>(null);
  const [newVersion, setNewVersion] = useState<CrmCreateVersionInput | null>(null);

  const [reviewPlatform, setReviewPlatform] = useState('');
  const [reviewAt, setReviewAt] = useState('');
  const [review, setReview] = useState<Awaited<ReturnType<typeof fetchReviewDue>> | null>(null);
  const [reviewError, setReviewError] = useState<string | null>(null);

  // 三个维度的候选值：按已选的上游维度收敛，避免出现「选了类目 A 又列出类目 B」的组合。
  const shopOptions = useMemo(() => {
    const seen = new Map<string, string>();
    for (const scope of scopes) seen.set(scope.shopId, scope.shopName || scope.shopId);
    return [...seen.entries()];
  }, [scopes]);

  const categoryOptions = useMemo(() => {
    const seen = new Map<string, string>();
    for (const scope of scopes) {
      if (shopId && scope.shopId !== shopId) continue;
      seen.set(scope.categoryId, scope.categoryName || scope.categoryId);
    }
    return [...seen.entries()];
  }, [scopes, shopId]);

  const localeOptions = useMemo(() => {
    const seen = new Set<string>();
    for (const scope of scopes) {
      if (shopId && scope.shopId !== shopId) continue;
      if (categoryId && scope.categoryId !== categoryId) continue;
      seen.add(scope.locale);
    }
    return [...seen].sort();
  }, [scopes, shopId, categoryId]);

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const params = {
        shopId: shopId || undefined,
        categoryId: categoryId || undefined,
        locale: locale || undefined,
        platformCode: platformCode || undefined,
        topicType: topicType || undefined,
      };
      const [caps, scopeList, entries] = await Promise.all([
        fetchCapabilities(),
        fetchScopes(),
        effectiveOnly ? fetchEffectiveEntries(params) : fetchEntries(params),
      ]);
      setCapabilities(caps);
      setScopes(scopeList);
      setRows(entries);
      if (!ready) setReady(true);
    } catch (e) {
      setError(errorText(e, '知识库加载失败'));
    } finally {
      setLoading(false);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [shopId, categoryId, locale, platformCode, topicType, effectiveOnly]);

  useEffect(() => {
    void load();
  }, [load]);

  async function openDetail(entryId: string) {
    setBusy(true);
    setActionError(null);
    try {
      const [detailResult, referenceList] = await Promise.all([
        fetchEntry(entryId),
        fetchEntryReferences(entryId),
      ]);
      setDetail(detailResult);
      setReferences(referenceList);
      setNewVersion(null);
    } catch (e) {
      setActionError(errorText(e, '知识详情加载失败'));
    } finally {
      setBusy(false);
    }
  }

  async function runReviewDue() {
    if (!reviewPlatform.trim() || !reviewAt.trim()) {
      setReviewError('平台与改版生效时刻都要填 —— 这是「按平台问」的查询，两个缺一就没有答案。');
      return;
    }
    setBusy(true);
    setReviewError(null);
    try {
      setReview(await fetchReviewDue(reviewPlatform.trim(), reviewAt.trim()));
    } catch (e) {
      setReview(null);
      setReviewError(errorText(e, '待复核查询失败'));
    } finally {
      setBusy(false);
    }
  }

  async function submitEntry() {
    if (!draft) return;
    setBusy(true);
    setActionError(null);
    try {
      await createEntry(draft);
      setDraft(null);
      setNotice('知识档案已建立。接下来给它建一个版本并发布 —— 草稿不会对客服生效。');
      await load();
    } catch (e) {
      setActionError(errorText(e, '建立知识档案失败'));
    } finally {
      setBusy(false);
    }
  }

  async function submitVersion() {
    if (!detail || !newVersion) return;
    setBusy(true);
    setActionError(null);
    try {
      const created = await createVersion(detail.entry.id, newVersion);
      await publishVersion(created.id, { note: '' });
      setNewVersion(null);
      setNotice(`版本 ${created.versionNo} 已发布。它从 ${created.effectiveFrom} 起生效。`);
      await openDetail(detail.entry.id);
      await load();
    } catch (e) {
      setActionError(errorText(e, '新建 / 发布版本失败'));
    } finally {
      setBusy(false);
    }
  }

  async function closeInterval(versionId: string, versionNo: number) {
    const to = window.prompt(
      '向前关闭生效区间（只能收窄到一个仍在未来的时刻，例如 2027-01-01T00:00:00+08:00）',
    );
    if (!to) return;
    setBusy(true);
    setActionError(null);
    try {
      await closeVersion(versionId, { effectiveTo: to });
      setNotice(`版本 ${versionNo} 的生效区间已向前关闭。`);
      if (detail) await openDetail(detail.entry.id);
      await load();
    } catch (e) {
      setActionError(errorText(e, '关闭生效区间失败'));
    } finally {
      setBusy(false);
    }
  }

  const filtered = Boolean(shopId || categoryId || locale || platformCode || topicType);

  return (
    <div className="space-y-4">
      <SectionCard
        title="组织范围"
        description="店铺 / 类目 / 语言 —— 候选值来自本租户库里真实存在过的格子。"
      >
        <div className="grid gap-3 md:grid-cols-4">
          <Field label="店铺">
            <select
              className={control}
              value={shopId}
              onChange={(e) => {
                setShopId(e.target.value);
                setCategoryId('');
                setLocale('');
              }}
            >
              <option value="">全部店铺</option>
              {shopOptions.map(([id, name]) => (
                <option key={id} value={id}>
                  {name}
                </option>
              ))}
            </select>
          </Field>
          <Field label="类目">
            <select
              className={control}
              value={categoryId}
              onChange={(e) => {
                setCategoryId(e.target.value);
                setLocale('');
              }}
            >
              <option value="">全部类目</option>
              {categoryOptions.map(([id, name]) => (
                <option key={id} value={id}>
                  {name}
                </option>
              ))}
            </select>
          </Field>
          <Field label="语言">
            <select className={control} value={locale} onChange={(e) => setLocale(e.target.value)}>
              <option value="">全部语言</option>
              {localeOptions.map((code) => (
                <option key={code} value={code}>
                  {code}
                </option>
              ))}
            </select>
          </Field>
          <Field label="适用平台">
            <input
              className={control}
              value={platformCode}
              placeholder="例如 AMAZON"
              onChange={(e) => setPlatformCode(e.target.value.toUpperCase())}
            />
          </Field>
        </div>
        <div className="mt-3 flex flex-wrap items-center gap-2">
          <label className="flex items-center gap-2 text-sm">
            <input
              type="checkbox"
              checked={effectiveOnly}
              onChange={(e) => setEffectiveOnly(e.target.checked)}
            />
            只看此刻生效的条目
          </label>
          <Button variant="outline" size="sm" onClick={() => void load()} disabled={loading}>
            <RefreshCw className={loading ? 'animate-spin' : ''} />
            刷新
          </Button>
          {writable && (
            <Button
              size="sm"
              onClick={() =>
                setDraft({
                  topicKey: '',
                  title: '',
                  shopId: shopId || (shopOptions[0]?.[0] ?? ''),
                  shopName: shopOptions.find(([id]) => id === shopId)?.[1] ?? '',
                  categoryId: categoryId || (categoryOptions[0]?.[0] ?? ''),
                  categoryName: categoryOptions.find(([id]) => id === categoryId)?.[1] ?? '',
                  locale: locale || localeOptions[0] || 'zh-CN',
                  platformCode: platformCode || 'AMAZON',
                  topicType: 'FAQ',
                  note: '',
                })
              }
            >
              <Plus />
              新建知识
            </Button>
          )}
          {filtered && (
            <Button
              variant="ghost"
              size="sm"
              onClick={() => {
                setShopId('');
                setCategoryId('');
                setLocale('');
                setPlatformCode('');
                setTopicType('');
              }}
            >
              清空筛选
            </Button>
          )}
        </div>
        {scopes.length === 0 && ready && !loading && (
          <p className="text-muted-foreground mt-3 text-copy-13">
            本租户还没有任何知识格子。先新建一条知识，三个组织维度会随之出现。
          </p>
        )}
      </SectionCard>

      {actionError && (
        <p role="alert" className="text-destructive text-copy-13">
          {actionError}
        </p>
      )}
      {notice && <p className="text-muted-foreground text-copy-13">{notice}</p>}

      <SectionCard
        title="知识条目"
        description={
          effectiveOnly
            ? '当前生效的条目：每条带此刻生效的那一版与它的半开生效区间 [from, to)。'
            : '全部条目（含没有生效版本的）。没有生效版本的条目无法用来答复买家。'
        }
      >
        {loading && <p className="text-muted-foreground text-copy-13">正在加载知识条目…</p>}
        {!loading && error && (
          <p role="alert" className="text-destructive text-copy-13">
            {error}
          </p>
        )}
        {!loading && !error && rows.length === 0 && (
          <EmptyState
            icon={<BookOpen />}
            title="这个范围里还没有知识"
            description={
              filtered
                ? '换个筛选条件，或新建一条知识。一个格子只能有一条标准答法。'
                : '新建第一条知识：常见问题标准答法、平台政策说明、产品使用指导或退换货规则。'
            }
          />
        )}
        {!loading && !error && rows.length > 0 && (
          <ul className="divide-control-border divide-y">
            {rows.map((row) => {
              const effective = row.effectiveVersion;
              return (
                <li key={row.id} className="flex flex-wrap items-start gap-3 py-3">
                  <div className="min-w-0 flex-1 space-y-1">
                    <div className="flex flex-wrap items-center gap-2">
                      <span className="text-copy-14 font-medium">{row.title}</span>
                      <Badge>{topicTypeLabel(row.topicType)}</Badge>
                      <Badge>{row.platformCode}</Badge>
                      <Badge>{row.locale}</Badge>
                    </div>
                    <p className="text-muted-foreground text-copy-13">
                      {row.shopName || row.shopId} / {row.categoryName || row.categoryId} · 业务键{' '}
                      <code>{row.topicKey}</code>
                    </p>
                    {effective ? (
                      <p className="text-muted-foreground text-copy-13">
                        生效 v{effective.versionNo}：{formatInterval(effective.effectiveFrom, effective.effectiveTo)}
                      </p>
                    ) : (
                      <p className="text-destructive flex items-center gap-1 text-copy-13">
                        <ShieldAlert />
                        此刻没有生效版本 —— 这一条现在答复不了买家
                      </p>
                    )}
                  </div>
                  <div className="text-muted-foreground flex shrink-0 flex-col items-end gap-1 text-copy-13">
                    <span>{row.versionCount} 个版本</span>
                    <span>{row.referenceCount} 次引用</span>
                    <Button variant="outline" size="sm" onClick={() => void openDetail(row.id)}>
                      版本与引用
                    </Button>
                  </div>
                </li>
              );
            })}
          </ul>
        )}
      </SectionCard>

      <SectionCard
        title="平台改版待复核"
        description="知识条目必须标注适用平台与生效时间 —— 这一页把它们变成一条可执行的查询。"
      >
        <div className="grid gap-3 md:grid-cols-2">
          <Field label="改版平台">
            <input
              className={control}
              value={reviewPlatform}
              placeholder="例如 AMAZON"
              onChange={(e) => setReviewPlatform(e.target.value.toUpperCase())}
            />
          </Field>
          <Field label="新政策生效时刻" hint="必须带时区偏移量，例如 2026-06-01T00:00:00+08:00">
            <input
              className={control}
              value={reviewAt}
              placeholder="2026-06-01T00:00:00+08:00"
              onChange={(e) => setReviewAt(e.target.value)}
            />
          </Field>
        </div>
        <Button className="mt-3" size="sm" onClick={() => void runReviewDue()} disabled={busy}>
          <Send />
          查待复核条目
        </Button>
        {reviewError && (
          <p role="alert" className="text-destructive mt-3 text-copy-13">
            {reviewError}
          </p>
        )}
        {review && (
          <div className="mt-3 space-y-2">
            <p className="text-muted-foreground text-copy-13">
              <strong>判定口径：</strong>
              {review.criterion}
            </p>
            {review.entries.length === 0 ? (
              <EmptyState
                icon={<BookOpen />}
                title="没有待复核的条目"
                description="该平台在改版时刻之前写下的知识，要么已经被接替过，要么这一格还没有知识。"
              />
            ) : (
              <ul className="divide-control-border divide-y">
                {review.entries.map((item) => (
                  <li key={item.entryId} className="flex flex-wrap items-start gap-3 py-2">
                    <div className="min-w-0 flex-1 space-y-1">
                      <span className="text-copy-14 font-medium">{item.title}</span>
                      <p className="text-muted-foreground text-copy-13">
                        {item.shopName || item.shopId} / {item.categoryName || item.categoryId} ·{' '}
                        {item.locale} · 当前生效 v{item.versionNo}，写于 {item.effectiveFrom}
                      </p>
                    </div>
                    <div className="flex shrink-0 items-center gap-2">
                      <Badge>已 {item.staleDays} 天没复核</Badge>
                      <Button variant="outline" size="sm" onClick={() => void openDetail(item.entryId)}>
                        处理
                      </Button>
                    </div>
                  </li>
                ))}
              </ul>
            )}
          </div>
        )}
      </SectionCard>

      {draft && (
        <SectionCard title="新建知识档案" description="三个组织维度与适用平台必填 —— 它们决定这条知识管哪个格子、按哪个平台口径写。">
          <div className="grid gap-3 md:grid-cols-2">
            <Field label="业务键" hint="同一格子里唯一，建后不可改">
              <input
                className={control}
                value={draft.topicKey}
                onChange={(e) => setDraft({ ...draft, topicKey: e.target.value })}
              />
            </Field>
            <Field label="标题">
              <input
                className={control}
                value={draft.title}
                onChange={(e) => setDraft({ ...draft, title: e.target.value })}
              />
            </Field>
            <Field label="店铺">
              <select
                className={control}
                value={draft.shopId}
                onChange={(e) =>
                  setDraft({
                    ...draft,
                    shopId: e.target.value,
                    shopName: shopOptions.find(([id]) => id === e.target.value)?.[1] ?? '',
                  })
                }
              >
                <option value="">请选择</option>
                {shopOptions.map(([id, name]) => (
                  <option key={id} value={id}>
                    {name}
                  </option>
                ))}
              </select>
            </Field>
            <Field label="类目">
              <select
                className={control}
                value={draft.categoryId}
                onChange={(e) =>
                  setDraft({
                    ...draft,
                    categoryId: e.target.value,
                    categoryName:
                      categoryOptions.find(([id]) => id === e.target.value)?.[1] ?? '',
                  })
                }
              >
                <option value="">请选择</option>
                {categoryOptions.map(([id, name]) => (
                  <option key={id} value={id}>
                    {name}
                  </option>
                ))}
              </select>
            </Field>
            <Field label="语言" hint="BCP-47 简写，例如 zh-CN">
              <input
                className={control}
                value={draft.locale}
                onChange={(e) => setDraft({ ...draft, locale: e.target.value })}
              />
            </Field>
            <Field label="适用平台" hint="平台政策变更时按它定位待复核条目">
              <input
                className={control}
                value={draft.platformCode}
                onChange={(e) => setDraft({ ...draft, platformCode: e.target.value.toUpperCase() })}
              />
            </Field>
            <Field label="知识类型">
              <select
                className={control}
                value={draft.topicType}
                onChange={(e) => setDraft({ ...draft, topicType: e.target.value })}
              >
                {(capabilities?.topicTypes ?? ['FAQ']).map((type) => (
                  <option key={type} value={type}>
                    {topicTypeLabel(type)}
                  </option>
                ))}
              </select>
            </Field>
          </div>
          <div className="mt-3 flex gap-2">
            <Button size="sm" onClick={() => void submitEntry()} disabled={busy}>
              建立档案
            </Button>
            <Button variant="ghost" size="sm" onClick={() => setDraft(null)}>
              取消
            </Button>
          </div>
        </SectionCard>
      )}

      {detail && (
        <SectionCard
          title={`${detail.entry.title} · 版本与引用`}
          description={`${detail.entry.shopName || detail.entry.shopId} / ${detail.entry.categoryName || detail.entry.categoryId} / ${detail.entry.locale} / ${detail.entry.platformCode}`}
        >
          {busy && <p className="text-muted-foreground text-copy-13">加载中…</p>}
          <ul className="divide-control-border divide-y">
            {detail.versions.map((version) => (
              <li key={version.id} className="flex flex-wrap items-start gap-3 py-3">
                <div className="min-w-0 flex-1 space-y-1">
                  <div className="flex flex-wrap items-center gap-2">
                    <span className="text-copy-14 font-medium">v{version.versionNo}</span>
                    <Badge>{version.status === 'PUBLISHED' ? '已发布' : '草稿'}</Badge>
                    <span className="text-muted-foreground text-copy-13">
                      {formatInterval(version.effectiveFrom, version.effectiveTo)}
                    </span>
                  </div>
                  <p className="text-copy-13">{version.question}</p>
                  <p className="text-muted-foreground text-copy-13">{version.answer}</p>
                </div>
                <div className="flex shrink-0 flex-col items-end gap-1">
                  <span className="text-muted-foreground text-copy-13">
                    {version.referenceCount} 次引用
                  </span>
                  {writable && version.status === 'PUBLISHED' && !version.effectiveTo && (
                    <Button
                      variant="outline"
                      size="sm"
                      onClick={() => void closeInterval(version.id, version.versionNo)}
                    >
                      向前关闭区间
                    </Button>
                  )}
                </div>
              </li>
            ))}
          </ul>

          {writable && (
            <div className="mt-4 space-y-2">
              {newVersion ? (
                <div className="grid gap-3 md:grid-cols-2">
                  <Field label="版本号">
                    <input
                      type="number"
                      className={control}
                      value={newVersion.versionNo}
                      onChange={(e) =>
                        setNewVersion({ ...newVersion, versionNo: Number(e.target.value) })
                      }
                    />
                  </Field>
                  <Field label="生效起点" hint="必须带时区偏移量">
                    <input
                      className={control}
                      value={newVersion.effectiveFrom}
                      placeholder="2026-06-01T00:00:00+08:00"
                      onChange={(e) => setNewVersion({ ...newVersion, effectiveFrom: e.target.value })}
                    />
                  </Field>
                  <Field label="生效终点" hint="留空 = 一直有效。半开区间 [起点, 终点)">
                    <input
                      className={control}
                      value={newVersion.effectiveTo ?? ''}
                      onChange={(e) =>
                        setNewVersion({ ...newVersion, effectiveTo: e.target.value || null })
                      }
                    />
                  </Field>
                  <Field label="问题">
                    <input
                      className={control}
                      value={newVersion.question}
                      onChange={(e) => setNewVersion({ ...newVersion, question: e.target.value })}
                    />
                  </Field>
                  <div className="md:col-span-2">
                    <Field label="标准答法">
                      <textarea
                        className={`${control} min-h-24`}
                        value={newVersion.answer}
                        onChange={(e) => setNewVersion({ ...newVersion, answer: e.target.value })}
                      />
                    </Field>
                  </div>
                  <div className="flex gap-2 md:col-span-2">
                    <Button size="sm" onClick={() => void submitVersion()} disabled={busy}>
                      保存并发布
                    </Button>
                    <Button variant="ghost" size="sm" onClick={() => setNewVersion(null)}>
                      取消
                    </Button>
                  </div>
                </div>
              ) : (
                <Button
                  variant="outline"
                  size="sm"
                  onClick={() =>
                    setNewVersion({
                      versionNo: (detail.versions[0]?.versionNo ?? 0) + 1,
                      question: '',
                      answer: '',
                      effectiveFrom: new Date().toISOString(),
                      effectiveTo: null,
                      note: '',
                    })
                  }
                >
                  <Plus />
                  新建版本并发布
                </Button>
              )}
            </div>
          )}

          <h3 className="mt-5 text-copy-14 font-medium">引用记录</h3>
          <p className="text-muted-foreground text-copy-13">
            引用记录只读：谁在什么时候引用了哪一版是审计事实，允许改删就等于把「没人用过」
            这个答案做出来 —— 而「多少条知识根本没人用」恰恰是运营最该先看到的数。
          </p>
          {references.length === 0 ? (
            <EmptyState
              icon={<BookOpen />}
              title="还没有人引用过这些版本"
              description="15.1.1.2 消息发送与 15.5.1 自动回复都尚未开发；它们落地后引用会从这里长出来。"
            />
          ) : (
            <ul className="divide-control-border divide-y mt-2">
              {references.map((reference) => (
                <li key={reference.id} className="flex flex-wrap items-center gap-3 py-2 text-copy-13">
                  <span className="min-w-0 flex-1">
                    {reference.entryTitle} v{reference.versionNo}
                  </span>
                  <Badge>{consumerLabel(reference.consumer)}</Badge>
                  <span className="text-muted-foreground">{reference.referencedAt}</span>
                  {reference.sourceRef && (
                    <span className="text-muted-foreground">来源 {reference.sourceRef}</span>
                  )}
                </li>
              ))}
            </ul>
          )}
        </SectionCard>
      )}
    </div>
  );
}

function errorText(e: unknown, fallback: string): string {
  if (e instanceof Error && e.message) return `${fallback}：${e.message}`;
  return fallback;
}
