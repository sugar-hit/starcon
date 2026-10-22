'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import {
  Badge,
  Button,
  EmptyState,
  Input,
  Modal,
  ModalContent,
  ModalDescription,
  ModalFooter,
  ModalHeader,
  ModalTitle,
  PageHeader,
  SectionCard,
  StatCard,
} from '@starcon/ui';
import {
  CircleAlert,
  Clock,
  Gavel,
  Layers,
  Loader2,
  RefreshCw,
  ScrollText,
  ShieldAlert,
  TriangleAlert,
} from 'lucide-react';
import {
  RULE_TYPE_OPTIONS,
  SEVERITIES,
  STATUS_OPTIONS,
  TERM_KIND_OPTIONS,
  addScopes,
  addTerms,
  createRule,
  createVersion,
  emptyState,
  emptyStateKind,
  fetchCapabilities,
  fetchEffectiveSet,
  fetchEffectiveVersion,
  fetchRule,
  fetchRules,
  fetchShops,
  fetchVersion,
  fetchVersions,
  isWordRuleType,
  ruleTypeLabel,
  scopeText,
  severityLabel,
  severityTone,
  statusLabel,
  termKindLabel,
  updateRuleStatus,
  windowText,
  type ComplianceRuleCapabilities,
  type ComplianceRuleDetail,
  type ComplianceRuleEffectiveSet,
  type ComplianceRuleEffectiveVersion,
  type ComplianceRulePage,
  type ComplianceRuleScope,
  type ComplianceRuleShopPage,
  type ComplianceRuleTerm,
  type ComplianceRuleVersion,
  type ComplianceRuleVersionDetail,
} from './compliance-rule-model';

/**
 * 16.2.1.1 规则库 工作区。
 *
 * 蓝图：「管理合规规则库：规则类型（违禁词、极限词、侵权词、认证要求、禁限售、标签要求、材质要求）、
 * 严重度（阻断/警告/提示）、适用平台与站点、生效时间与版本。输入：规则定义与词库上传；版本更新；适用范围配置。
 * 输出：规则集 + 生效版本。关系：上游 = 平台政策同步 + 人工维护；下游 = 16.2.1.2 执行、4.2.3.2 预检。
 * **规则版本化是关键**：政策变化时历史商品的判定结果要可解释，不能因为规则库更新就说过去的商品当时违规。」
 *
 * 🔴 页面上的六条纪律：
 *
 * 1. **界面上没有「改词」「改严重度」** —— 只有「发新版本」。改规则 = 新增一个版本。
 * 2. **严重度显示的是当前生效那一版的严重度**，页面**不重算**、不缓存。
 * 3. **生效时刻不许回填** —— 只能选「现在或将来」，并把这条纪律的原文显示出来。
 * 4. **历史可解释** —— 「当时生效的是哪一版」能查：版本详情里能看到该版本**当时**的词条与适用范围。
 * 5. **未交付的下游灰掉**：16.2.1.2 拦截执行当前**未交付**，界面上如实标出，**不编**放行入口。
 * 6. **空态四态分开说**（没有店铺 / 还没有规则 / 规则还没有版本 / 筛选无结果）。
 */
const PAGE_SIZE = 20;

export function ComplianceRuleWorkspace() {
  const [caps, setCaps] = useState<ComplianceRuleCapabilities | null>(null);
  const [shops, setShops] = useState<ComplianceRuleShopPage | null>(null);
  const [rules, setRules] = useState<ComplianceRulePage | null>(null);
  const [effective, setEffective] = useState<ComplianceRuleEffectiveSet | null>(null);

  const [ruleType, setRuleType] = useState('');
  const [status, setStatus] = useState('');
  const [keyword, setKeyword] = useState('');
  const [page, setPage] = useState(0);
  const [resolvedAt, setResolvedAt] = useState('');

  const [detail, setDetail] = useState<ComplianceRuleDetail | null>(null);
  const [versionDetail, setVersionDetail] = useState<ComplianceRuleVersionDetail | null>(null);
  const [historical, setHistorical] = useState<ComplianceRuleEffectiveVersion | null>(null);

  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  // ---- 新建规则 ----
  const [newOpen, setNewOpen] = useState(false);
  const [newCode, setNewCode] = useState('');
  const [newType, setNewType] = useState('PROHIBITED_WORD');

  // ---- 发新版本 ----
  const [versionOpen, setVersionOpen] = useState(false);
  const [versionFor, setVersionFor] = useState<ComplianceRuleVersionDetail | null>(null);
  const [severity, setSeverity] = useState('BLOCKING');
  const [versionTitle, setVersionTitle] = useState('');
  const [versionNote, setVersionNote] = useState('');
  const [changeNote, setChangeNote] = useState('');
  const [termsText, setTermsText] = useState('');
  const [scopePlatform, setScopePlatform] = useState('');
  const [scopeShop, setScopeShop] = useState('');

  const loadRules = useCallback(
    async (target: number) => {
      const result = await fetchRules(target, PAGE_SIZE, { ruleType, status, keyword });
      setRules(result);
      return result;
    },
    [ruleType, status, keyword],
  );

  const refresh = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const [capsResult, shopResult, ruleResult, effectiveResult] = await Promise.all([
        fetchCapabilities(),
        fetchShops(0, 100, {}),
        loadRules(page),
        fetchEffectiveSet(0, PAGE_SIZE, resolvedAt ? { at: resolvedAt } : {}),
      ]);
      setCaps(capsResult);
      setShops(shopResult);
      setRules(ruleResult);
      setEffective(effectiveResult);
    } catch (e) {
      setError(e instanceof Error ? e.message : '加载失败，请重试');
    } finally {
      setLoading(false);
    }
  }, [loadRules, page, resolvedAt]);

  useEffect(() => {
    void refresh();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const run = useCallback(async (work: () => Promise<void>) => {
    setBusy(true);
    setError(null);
    try {
      await work();
    } catch (e) {
      setError(e instanceof Error ? e.message : '操作失败，请重试');
    } finally {
      setBusy(false);
    }
  }, []);

  const openDetail = useCallback(
    (ruleId: string) =>
      run(async () => {
        setDetail(await fetchRule(ruleId));
        setVersionDetail(null);
        setHistorical(null);
      }),
    [run],
  );

  const openVersion = useCallback(
    (ruleId: string, versionId: string) =>
      run(async () => {
        setVersionDetail(await fetchVersion(ruleId, versionId));
      }),
    [run],
  );

  const openHistorical = useCallback(
    (ruleId: string, at: string) =>
      run(async () => {
        setHistorical(await fetchEffectiveVersion(ruleId, at));
      }),
    [run],
  );

  const doCreateRule = useCallback(
    () =>
      run(async () => {
        const created = await createRule({
          ruleCode: newCode.trim(),
          ruleType: newType,
          title: `规则 ${newCode.trim()}`,
          description: '',
        });
        setNewOpen(false);
        setNewCode('');
        await loadRules(0);
        setPage(0);
        setDetail(await fetchRule(created.rule.ruleId));
      }),
    [loadRules, newCode, newType, run],
  );

  const doCreateVersion = useCallback(
    () =>
      run(async () => {
        if (!versionFor) return;
        const scopes = scopePlatform
          ? [
              {
                platformId: scopePlatform,
                marketplaceId: null,
                shopId: scopeShop || null,
              },
            ]
          : [];
        const created = await createVersion(versionFor.version.ruleId, {
          severity,
          title: versionTitle.trim() || versionFor.version.title,
          description: versionFor.version.description,
          effectiveFrom: null,
          changeNote: changeNote.trim(),
          terms: isWordRuleType(versionFor.rule?.ruleType)
            ? parseTermsText(termsText)
            : [],
          scopes,
        });
        setVersionOpen(false);
        setVersionFor(null);
        setTermsText('');
        setChangeNote('');
        setScopePlatform('');
        setScopeShop('');
        setVersionDetail(created);
        await Promise.all([loadRules(page), fetchEffectiveSet(0, PAGE_SIZE, {})]);
      }),
    [
      changeNote,
      loadRules,
      page,
      run,
      scopePlatform,
      scopeShop,
      severity,
      termsText,
      versionFor,
      versionTitle,
    ],
  );

  const doToggleStatus = useCallback(
    (ruleId: string, next: string) =>
      run(async () => {
        await updateRuleStatus(ruleId, next);
        await loadRules(page);
        await fetchEffectiveSet(0, PAGE_SIZE, resolvedAt ? { at: resolvedAt } : {}).then(
          setEffective,
        );
      }),
    [loadRules, page, resolvedAt, run],
  );

  const doAddTerms = useCallback(
    (version: ComplianceRuleVersion, terms: { term: string; termKind: string }[]) =>
      run(async () => {
        await addTerms(version.ruleId, version.versionId, terms);
        setVersionDetail(await fetchVersion(version.ruleId, version.versionId));
      }),
    [run],
  );

  const doAddScope = useCallback(
    (version: ComplianceRuleVersion, scope: { platformId: string; shopId?: string | null }) =>
      run(async () => {
        await addScopes(version.ruleId, version.versionId, [scope]);
        setVersionDetail(await fetchVersion(version.ruleId, version.versionId));
      }),
    [run],
  );

  const shopItems = useMemo(() => shops?.items ?? [], [shops]);
  const platformOptions = useMemo(() => {
    const seen = new Map<string, string>();
    for (const shop of shopItems) seen.set(shop.platformId, shop.platformCode);
    return [...seen.entries()].map(([platformId, platformCode]) => ({ platformId, platformCode }));
  }, [shopItems]);

  const filtered = !!(ruleType || status || keyword);
  const emptyKind = emptyStateKind(
    shops?.total ?? 0,
    rules?.total ?? 0,
    filtered,
    detail?.versions.length ?? 1,
  );
  const empty = emptyState(emptyKind);

  if (loading && !caps) {
    return (
      <div className="flex items-center gap-2 py-10">
        <Loader2 className="text-muted-foreground size-4 animate-spin" />
        <span className="text-label-13">正在加载规则库…</span>
      </div>
    );
  }

  if (error && !caps) {
    return (
      <SectionCard title="规则库">
        <EmptyState
          icon={<TriangleAlert className="text-destructive size-8" />}
          title="规则库加载失败"
          description={error}
          action={
            <Button variant="secondary" onClick={() => void refresh()}>
              <RefreshCw className="mr-2 size-4" />
              重试
            </Button>
          }
        />
      </SectionCard>
    );
  }

  return (
    <div className="space-y-4">
      <PageHeader
        title="规则库"
        description="合规规则库：规则类型、严重度、适用平台与站点、生效时间与版本。改规则 = 发一个新版本。"
      />

      {/* ---- 统计卡（服务端算的计数，覆盖整个筛选结果集） ---- */}
      <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
        <StatCard
          label="规则总数"
          value={rules?.total ?? 0}
          polarity="neutral"
          icon={<ScrollText className="size-4" />}
        />
        <StatCard
          label="在用"
          value={rules?.activeCount ?? 0}
          polarity="positive"
          icon={<ShieldAlert className="size-4" />}
        />
        <StatCard
          label="已停用"
          value={rules?.retiredCount ?? 0}
          polarity="neutral"
          icon={<CircleAlert className="size-4" />}
        />
        <StatCard
          label="此刻生效"
          value={effective?.total ?? 0}
          polarity="negative"
          icon={<Clock className="size-4" />}
        />
      </div>

      {error && (
        <div className="border-destructive-soft bg-destructive-soft text-destructive rounded-md border px-4 py-3 text-copy-13">
          {error}
        </div>
      )}

      {/* ---- 能力声明：未交付的下游灰掉 ---- */}
      <SectionCard
        title="能力声明"
        description="哪些下游真的可用、哪些还没交付 —— 逐条可查，不是文档里的一句话。"
      >
        <ul className="space-y-2">
          {(caps?.gaps ?? []).map((gap) => (
            <li
              key={gap.moduleId}
              className={
                gap.available
                  ? 'border-border flex items-start gap-3 rounded-md border px-3 py-2'
                  : 'border-border bg-muted/40 flex items-start gap-3 rounded-md border px-3 py-2'
              }
            >
              <Badge tone={gap.available ? 'success' : 'neutral'} className="mt-0.5 shrink-0">
                {gap.available ? '已交付' : '未交付'}
              </Badge>
              <div className="min-w-0">
                <div className="text-copy-13 text-foreground font-medium">
                  {gap.moduleId} · {gap.moduleName}
                </div>
                <div className="text-copy-13 text-muted-foreground">{gap.impact}</div>
                {!gap.available && (
                  <div className="text-copy-12 text-muted-foreground mt-1">
                    归属模块：{gap.ownerModule}
                  </div>
                )}
              </div>
            </li>
          ))}
        </ul>
        {caps && (
          <div className="text-copy-12 text-muted-foreground mt-3 space-y-1">
            <div>{caps.versioningNote}</div>
            <div>{caps.severityNote}</div>
            <div>{caps.wordLibraryNote}</div>
            <div>{caps.scopeNote}</div>
          </div>
        )}
      </SectionCard>

      {/* ---- 规则集 ---- */}
      <SectionCard
        title="规则集"
        description="规则身份（编码 + 类型 + 生命周期）。点开一条看它的全部版本。"
        extra={
          <div className="flex items-center gap-2">
            <Button variant="secondary" size="sm" onClick={() => void refresh()} disabled={busy}>
              <RefreshCw className="mr-2 size-4" />
              刷新
            </Button>
            <Button size="sm" onClick={() => setNewOpen(true)} disabled={busy}>
              新建规则
            </Button>
          </div>
        }
      >
        <div className="mb-4 flex flex-wrap items-center gap-2">
          <Input
            className="w-56"
            placeholder="规则编码 / 标题"
            value={keyword}
            onChange={(e) => {
              setKeyword(e.target.value);
              setPage(0);
            }}
          />
          <select
            className="border-input bg-background h-9 rounded-md border px-2 text-copy-13"
            value={ruleType}
            onChange={(e) => {
              setRuleType(e.target.value);
              setPage(0);
            }}
          >
            {RULE_TYPE_OPTIONS.map((option) => (
              <option key={option.value} value={option.value}>
                {option.label}
              </option>
            ))}
          </select>
          <select
            className="border-input bg-background h-9 rounded-md border px-2 text-copy-13"
            value={status}
            onChange={(e) => {
              setStatus(e.target.value);
              setPage(0);
            }}
          >
            {STATUS_OPTIONS.map((option) => (
              <option key={option.value} value={option.value}>
                {option.label}
              </option>
            ))}
          </select>
        </div>

        {(rules?.items.length ?? 0) === 0 ? (
          <EmptyState
            icon={<ShieldAlert className="size-8" />}
            title={empty.title}
            description={empty.description}
          />
        ) : (
          <div className="space-y-2">
            {rules?.items.map((rule) => (
              <button
                key={rule.ruleId}
                type="button"
                onClick={() => void openDetail(rule.ruleId)}
                className="border-border hover:bg-muted/40 rounded-md border px-3 py-2 text-left"
              >
                <div className="flex flex-wrap items-center gap-2">
                  <span className="text-copy-13 text-foreground font-medium">{rule.ruleCode}</span>
                  <Badge tone="neutral">{ruleTypeLabel(rule.ruleType)}</Badge>
                  {/* 🔴 严重度是**当前生效那一版**的严重度，页面不重算。 */}
                  {rule.severity ? (
                    <Badge tone={severityTone(rule.severity)}>
                      {severityLabel(rule.severity)}
                    </Badge>
                  ) : (
                    <Badge tone="neutral">无生效版本</Badge>
                  )}
                  <Badge tone={rule.status === 'ACTIVE' ? 'success' : 'neutral'}>
                    {statusLabel(rule.status)}
                  </Badge>
                  <span className="text-copy-12 text-muted-foreground">
                    {rule.versionCount} 个版本 · {rule.termCount} 个词条 · {rule.scopeCount} 条适用范围
                  </span>
                </div>
                <div className="text-copy-12 text-muted-foreground mt-1">
                  {rule.title}
                  {rule.inForce && rule.currentEffectiveFrom
                    ? ` · 生效中（v${rule.currentVersionNo}）· ${windowText(
                        rule.currentEffectiveFrom,
                        rule.currentEffectiveTo,
                      )}`
                    : ' · 当前没有生效的版本'}
                </div>
              </button>
            ))}
          </div>
        )}

        {(rules?.total ?? 0) > PAGE_SIZE && (
          <div className="mt-4 flex items-center gap-2">
            <Button
              variant="secondary"
              size="sm"
              disabled={page === 0}
              onClick={() => {
                const next = page - 1;
                setPage(next);
                void loadRules(next);
              }}
            >
              上一页
            </Button>
            <span className="text-copy-12 text-muted-foreground">
              第 {page + 1} 页 / 共 {rules?.total ?? 0} 条
            </span>
            <Button
              variant="secondary"
              size="sm"
              disabled={(page + 1) * PAGE_SIZE >= (rules?.total ?? 0)}
              onClick={() => {
                const next = page + 1;
                setPage(next);
                void loadRules(next);
              }}
            >
              下一页
            </Button>
          </div>
        )}
      </SectionCard>

      {/* ---- 生效规则集（规则集 + 生效版本） ---- */}
      <SectionCard
        title="生效规则集"
        description="某一刻真正生效的规则 + 它们生效的那一版。解析时刻可指定，用来回答「当时按哪一版判的」。"
        extra={
          <div className="flex items-center gap-2">
            <Input
              className="w-60"
              placeholder="解析时刻（ISO，可空 = 此刻）"
              value={resolvedAt}
              onChange={(e) => setResolvedAt(e.target.value)}
            />
            <Button
              variant="secondary"
              size="sm"
              onClick={() =>
                void run(async () => {
                  setEffective(await fetchEffectiveSet(0, PAGE_SIZE, resolvedAt ? { at: resolvedAt } : {}));
                })
              }
              disabled={busy}
            >
              按此刻查
            </Button>
          </div>
        }
      >
        {(effective?.items.length ?? 0) === 0 ? (
          <EmptyState
            icon={<Clock className="size-8" />}
            title="这个时刻没有生效的规则"
            description="生效规则集为空 —— 要么还没有规则生效，要么这一刻它们都还没到生效时刻。"
          />
        ) : (
          <div className="space-y-2">
            {effective?.items.map((entry) => (
              <div key={entry.rule.ruleId} className="border-border rounded-md border px-3 py-2">
                <div className="flex flex-wrap items-center gap-2">
                  <span className="text-copy-13 text-foreground font-medium">
                    {entry.rule.ruleCode}
                  </span>
                  <Badge tone="neutral">{ruleTypeLabel(entry.rule.ruleType)}</Badge>
                  <Badge tone={severityTone(entry.version.severity)}>
                    {severityLabel(entry.version.severity)}
                  </Badge>
                  <span className="text-copy-12 text-muted-foreground">
                    v{entry.version.versionNo} · {entry.termCount} 个词条
                  </span>
                </div>
                <div className="text-copy-12 text-muted-foreground mt-1">
                  生效窗口 {windowText(entry.version.effectiveFrom, entry.version.effectiveTo)} · 适用{' '}
                  {entry.platformCodes.join(' / ') || '（未配适用范围）'}
                  {entry.marketplaceCodes.length > 0 ? ` · 站点 ${entry.marketplaceCodes.join(' / ')}` : ''}
                  {entry.shopCodes.length > 0 ? ` · 店铺 ${entry.shopCodes.join(' / ')}` : ''}
                </div>
              </div>
            ))}
          </div>
        )}
        {effective && (
          <div className="text-copy-12 text-muted-foreground mt-3">
            解析时刻：{new Date(effective.resolvedAt).toLocaleString('zh-CN')} · {effective.note}
          </div>
        )}
      </SectionCard>

      {/* ---- 店铺范围 ---- */}
      <SectionCard
        title="店铺范围"
        description="店铺清单来自本服务自己的店铺目录（2.1.1.1），不是写死的常量。"
      >
        {shopItems.length === 0 ? (
          <EmptyState
            icon={<ShieldAlert className="size-8" />}
            title={empty.title}
            description={empty.description}
          />
        ) : (
          <div className="grid gap-2 sm:grid-cols-2 lg:grid-cols-3">
            {shopItems.map((shop) => (
              <div key={shop.shopId} className="border-border rounded-md border px-3 py-2">
                <div className="text-copy-13 text-foreground font-medium">
                  {shop.shopCode} · {shop.shopName}
                </div>
                <div className="text-copy-12 text-muted-foreground">
                  {shop.platformName} · 适用规则 {shop.ruleCount} 条（此刻生效 {shop.effectiveRuleCount} 条）
                </div>
              </div>
            ))}
          </div>
        )}
      </SectionCard>

      {/* ---- 新建规则 ---- */}
      <Modal open={newOpen} onOpenChange={setNewOpen}>
        <ModalContent>
          <ModalHeader>
            <ModalTitle>新建规则</ModalTitle>
            <ModalDescription>
              这里只建**规则身份**（编码 + 类型）。严重度、词条、适用范围与生效时间都在**版本**上 ——
              建完规则再发第一个版本。
            </ModalDescription>
          </ModalHeader>
          <div className="space-y-3">
            <Input
              placeholder="规则编码（租户内唯一，例如 RULE-001）"
              value={newCode}
              onChange={(e) => setNewCode(e.target.value)}
            />
            <select
              className="border-input bg-background h-9 w-full rounded-md border px-2 text-copy-13"
              value={newType}
              onChange={(e) => setNewType(e.target.value)}
            >
              {RULE_TYPE_OPTIONS.filter((o) => o.value !== '').map((option) => (
                <option key={option.value} value={option.value}>
                  {option.label}
                </option>
              ))}
            </select>
          </div>
          <ModalFooter>
            <Button variant="secondary" onClick={() => setNewOpen(false)}>
              取消
            </Button>
            <Button disabled={busy || !newCode.trim()} onClick={() => void doCreateRule()}>
              建立
            </Button>
          </ModalFooter>
        </ModalContent>
      </Modal>

      {/* ---- 规则详情 / 版本 ---- */}
      <Modal open={!!detail} onOpenChange={(open) => !open && setDetail(null)}>
        <ModalContent>
          <ModalHeader>
            <ModalTitle>{detail?.rule.ruleCode ?? '规则详情'}</ModalTitle>
            <ModalDescription>
              {detail?.rule.ruleTypeLabel} · {detail?.rule.statusLabel} ·{' '}
              {detail?.note ?? '版本不可改写：改规则 = 新增一个版本。'}
            </ModalDescription>
          </ModalHeader>
          <div className="space-y-3">
            {detail?.versions.length === 0 ? (
              <EmptyState
                icon={<Layers className="size-8" />}
                title={emptyState('NO_VERSION').title}
                description={emptyState('NO_VERSION').description}
              />
            ) : (
              <div className="space-y-2">
                {detail?.versions.map((version) => (
                  <div key={version.versionId} className="border-border rounded-md border px-3 py-2">
                    <div className="flex flex-wrap items-center gap-2">
                      <span className="text-copy-13 text-foreground font-medium">
                        v{version.versionNo}
                      </span>
                      <Badge tone={severityTone(version.severity)}>
                        {severityLabel(version.severity)}
                      </Badge>
                      {version.inForce ? (
                        <Badge tone="success">生效中</Badge>
                      ) : (
                        <Badge tone="neutral">历史版本</Badge>
                      )}
                      <span className="text-copy-12 text-muted-foreground">
                        {version.termCount} 个词条 · {version.scopeCount} 条适用范围
                      </span>
                    </div>
                    <div className="text-copy-12 text-muted-foreground mt-1">
                      {windowText(version.effectiveFrom, version.effectiveTo)}
                      {version.changeNote ? ` · ${version.changeNote}` : ''}
                    </div>
                    <div className="mt-2 flex flex-wrap gap-2">
                      <Button
                        variant="secondary"
                        size="sm"
                        onClick={() => void openVersion(version.ruleId, version.versionId)}
                      >
                        看这一版的词条与适用范围
                      </Button>
                    </div>
                  </div>
                ))}
              </div>
            )}
            <div className="flex flex-wrap gap-2">
              <Button
                onClick={() => {
                  setVersionFor(
                    detail
                      ? {
                          rule: detail.rule,
                          version: {
                            versionId: '',
                            ruleId: detail.rule.ruleId,
                            ruleCode: detail.rule.ruleCode,
                            versionNo: (detail.versions[0]?.versionNo ?? 0) + 1,
                            severity: 'BLOCKING',
                            severityLabel: '阻断',
                            title: detail.rule.title,
                            description: detail.rule.description,
                            effectiveFrom: new Date().toISOString(),
                            effectiveTo: null,
                            inForce: false,
                            changeNote: '',
                            termCount: 0,
                            scopeCount: 0,
                            createdAt: new Date().toISOString(),
                          },
                          terms: [],
                          scopes: [],
                          note: '',
                        }
                      : null,
                  );
                  setSeverity('BLOCKING');
                  setVersionTitle(detail?.rule.title ?? '');
                  setVersionOpen(true);
                }}
                disabled={busy}
              >
                发新版本（改规则）
              </Button>
              {detail && detail.rule.status === 'ACTIVE' ? (
                <Button
                  variant="secondary"
                  onClick={() => void doToggleStatus(detail.rule.ruleId, 'RETIRED')}
                  disabled={busy}
                >
                  停用这条规则
                </Button>
              ) : (
                detail && (
                  <Button
                    variant="secondary"
                    onClick={() => void doToggleStatus(detail.rule.ruleId, 'ACTIVE')}
                    disabled={busy}
                  >
                    重新启用
                  </Button>
                )
              )}
            </div>
          </div>
          <ModalFooter>
            <Button variant="secondary" onClick={() => setDetail(null)}>
              关闭
            </Button>
          </ModalFooter>
        </ModalContent>
      </Modal>

      {/* ---- 发新版本 ---- */}
      <Modal open={versionOpen} onOpenChange={setVersionOpen}>
        <ModalContent>
          <ModalHeader>
            <ModalTitle>发新版本</ModalTitle>
            <ModalDescription>
              🔴 改规则 = **新增一个版本**。旧版本原样留着 —— 这样「当时按哪一版判的」永远答得出来。
              生效时刻**不能早于现在**（不许回填）。
            </ModalDescription>
          </ModalHeader>
          <div className="space-y-3">
            <select
              className="border-input bg-background h-9 w-full rounded-md border px-2 text-copy-13"
              value={severity}
              onChange={(e) => setSeverity(e.target.value)}
            >
              {SEVERITIES.map((code) => (
                <option key={code} value={code}>
                  {severityLabel(code)}
                </option>
              ))}
            </select>
            <Input
              placeholder="这一版的标题"
              value={versionTitle}
              onChange={(e) => setVersionTitle(e.target.value)}
            />
            <Input
              placeholder="变更说明（为什么改这一版）"
              value={changeNote}
              onChange={(e) => setChangeNote(e.target.value)}
            />
            {isWordRuleType(versionFor?.rule?.ruleType) ? (
              <textarea
                className="border-input bg-background min-h-24 w-full rounded-md border px-2 py-1 text-copy-13"
                placeholder={'词库（每行一个词，空行与 # 注释行忽略）\n例：\n违禁词一\n违禁词二'}
                value={termsText}
                onChange={(e) => setTermsText(e.target.value)}
              />
            ) : (
              <div className="text-copy-12 text-muted-foreground">
                这一类规则**不带词条**（只有违禁词 / 极限词 / 侵权词三类有词条集合）。
              </div>
            )}
            <div className="flex flex-wrap items-center gap-2">
              <select
                className="border-input bg-background h-9 rounded-md border px-2 text-copy-13"
                value={scopePlatform}
                onChange={(e) => setScopePlatform(e.target.value)}
              >
                <option value="">不限平台（先不配适用范围）</option>
                {platformOptions.map((option) => (
                  <option key={option.platformId} value={option.platformId}>
                    适用平台 {option.platformCode}
                  </option>
                ))}
              </select>
              <select
                className="border-input bg-background h-9 rounded-md border px-2 text-copy-13"
                value={scopeShop}
                onChange={(e) => setScopeShop(e.target.value)}
              >
                <option value="">该平台下全部店铺</option>
                {shopItems
                  .filter(
                    (shop) =>
                      !scopePlatform ||
                      shop.platformId === scopePlatform ||
                      shop.platformCode ===
                        platformOptions.find((p) => p.platformId === scopePlatform)?.platformCode,
                  )
                  .map((shop) => (
                    <option key={shop.shopId} value={shop.shopId}>
                      店铺 {shop.shopCode}
                    </option>
                  ))}
              </select>
            </div>
          </div>
          <ModalFooter>
            <Button variant="secondary" onClick={() => setVersionOpen(false)}>
              取消
            </Button>
            <Button disabled={busy} onClick={() => void doCreateVersion()}>
              发布这一版
            </Button>
          </ModalFooter>
        </ModalContent>
      </Modal>

      {/* ---- 版本详情 ---- */}
      <Modal open={!!versionDetail} onOpenChange={(open) => !open && setVersionDetail(null)}>
        <ModalContent>
          <ModalHeader>
            <ModalTitle>
              v{versionDetail?.version.versionNo} · {versionDetail?.version.title}
            </ModalTitle>
            <ModalDescription>
              {versionDetail ? (
                <>
                  {severityLabel(versionDetail.version.severity)} ·{' '}
                  {windowText(versionDetail.version.effectiveFrom, versionDetail.version.effectiveTo)} ·{' '}
                  {versionDetail.note}
                </>
              ) : (
                ''
              )}
            </ModalDescription>
          </ModalHeader>
          <div className="space-y-3">
            <div>
              <div className="text-copy-13 text-foreground font-medium">
                词条（{versionDetail?.terms.length ?? 0} 条 · 属于这一版）
              </div>
              {(versionDetail?.terms.length ?? 0) === 0 ? (
                <div className="text-copy-12 text-muted-foreground mt-1">
                  这一版没有词条 —— 要么它本来就不带词条，要么词还没上传。
                </div>
              ) : (
                <div className="mt-1 flex flex-wrap gap-1">
                  {versionDetail?.terms.map((term) => (
                    <Badge key={term.termId} tone="neutral">
                      {term.term} · {termKindLabel(term.termKind)}
                    </Badge>
                  ))}
                </div>
              )}
            </div>
            <div>
              <div className="text-copy-13 text-foreground font-medium">
                适用范围（{versionDetail?.scopes.length ?? 0} 条 · 属于这一版）
              </div>
              {(versionDetail?.scopes.length ?? 0) === 0 ? (
                <div className="text-copy-12 text-muted-foreground mt-1">
                  这一版还没配适用范围 —— 规则不配适用范围就不会落到任何一家店。
                </div>
              ) : (
                <ul className="text-copy-12 text-muted-foreground mt-1 space-y-1">
                  {versionDetail?.scopes.map((scope) => (
                    <li key={scope.scopeId}>{scopeText(scope)}</li>
                  ))}
                </ul>
              )}
            </div>
            {versionDetail && (
              <VersionAdder
                version={versionDetail.version}
                wordRule={isWordRuleType(versionDetail.rule?.ruleType)}
                platforms={platformOptions}
                shops={shopItems}
                onAddTerms={doAddTerms}
                onAddScope={doAddScope}
              />
            )}
            {versionDetail && (
              <div className="flex flex-wrap items-center gap-2">
                <Input
                  className="w-60"
                  placeholder="查这一刻生效的是哪一版（ISO）"
                  onChange={(e) => {
                    const value = e.target.value;
                    if (value) void openHistorical(versionDetail.version.ruleId, value);
                  }}
                />
              </div>
            )}
          </div>
          <ModalFooter>
            <Button variant="secondary" onClick={() => setVersionDetail(null)}>
              关闭
            </Button>
          </ModalFooter>
        </ModalContent>
      </Modal>

      {/* ---- 历史可解释 ---- */}
      <Modal open={!!historical} onOpenChange={(open) => !open && setHistorical(null)}>
        <ModalContent>
          <ModalHeader>
            <ModalTitle>
              {historical?.version
                ? `那一刻生效的是 v${historical.version.versionNo}`
                : '那一刻没有生效的版本'}
            </ModalTitle>
            <ModalDescription>
              {historical
                ? `解析时刻 ${new Date(historical.resolvedAt).toLocaleString('zh-CN')} · ${historical.note}`
                : ''}
            </ModalDescription>
          </ModalHeader>
          {historical?.version ? (
            <div className="space-y-2">
              <div className="text-copy-13">
                严重度：{severityLabel(historical.version.severity)} · 生效窗口{' '}
                {windowText(historical.version.effectiveFrom, historical.version.effectiveTo)}
              </div>
              <div className="text-copy-12 text-muted-foreground">
                词条（{historical.terms.length} 条）：{historical.terms.map((t) => t.term).join('、') || '（无）'}
              </div>
              <ul className="text-copy-12 text-muted-foreground space-y-1">
                {historical.scopes.map((scope) => (
                  <li key={scope.scopeId}>{scopeText(scope)}</li>
                ))}
              </ul>
            </div>
          ) : (
            <EmptyState
              icon={<Clock className="size-8" />}
              title="那一刻没有生效的版本"
              description="这条规则在那一刻不生效（还没到第一版的生效时刻，或已被停用）。**不是「查不到就是没问题」**。"
            />
          )}
          <ModalFooter>
            <Button variant="secondary" onClick={() => setHistorical(null)}>
              关闭
            </Button>
          </ModalFooter>
        </ModalContent>
      </Modal>
    </div>
  );
}

/** 版本详情里的「追加词条 / 追加适用范围」小控件。 */
function VersionAdder({
  version,
  wordRule,
  platforms,
  shops,
  onAddTerms,
  onAddScope,
}: {
  version: ComplianceRuleVersion;
  wordRule: boolean;
  platforms: { platformId: string; platformCode: string }[];
  shops: { shopId: string; shopCode: string; platformId: string }[];
  onAddTerms: (
    version: ComplianceRuleVersion,
    terms: { term: string; termKind: string }[],
  ) => Promise<void>;
  onAddScope: (
    version: ComplianceRuleVersion,
    scope: { platformId: string; shopId?: string | null },
  ) => Promise<void>;
}) {
  const [term, setTerm] = useState('');
  const [kind, setKind] = useState('GENERAL');
  const [platform, setPlatform] = useState('');
  const [shop, setShop] = useState('');

  return (
    <div className="border-border space-y-2 rounded-md border px-3 py-2">
      <div className="text-copy-12 text-muted-foreground">
        🔴 已生效的版本**锁死** —— 下面这些只在「尚未生效」或「刚创建的那一笔」里可用；
        真正的改法是**发一个新版本**。
      </div>
      {wordRule && (
        <div className="flex flex-wrap items-center gap-2">
          <Input
            className="w-48"
            placeholder="追加一个词"
            value={term}
            onChange={(e) => setTerm(e.target.value)}
          />
          <select
            className="border-input bg-background h-9 rounded-md border px-2 text-copy-13"
            value={kind}
            onChange={(e) => setKind(e.target.value)}
          >
            {TERM_KIND_OPTIONS.map((option) => (
              <option key={option.value} value={option.value}>
                {option.label}
              </option>
            ))}
          </select>
          <Button
            variant="secondary"
            size="sm"
            disabled={!term.trim()}
            onClick={() => {
              const value = term.trim();
              setTerm('');
              void onAddTerms(version, [{ term: value, termKind: kind }]);
            }}
          >
            追加词条
          </Button>
        </div>
      )}
      <div className="flex flex-wrap items-center gap-2">
        <select
          className="border-input bg-background h-9 rounded-md border px-2 text-copy-13"
          value={platform}
          onChange={(e) => setPlatform(e.target.value)}
        >
          <option value="">选平台</option>
          {platforms.map((option) => (
            <option key={option.platformId} value={option.platformId}>
              {option.platformCode}
            </option>
          ))}
        </select>
        <select
          className="border-input bg-background h-9 rounded-md border px-2 text-copy-13"
          value={shop}
          onChange={(e) => setShop(e.target.value)}
        >
          <option value="">该平台下全部店铺</option>
          {shops
            .filter((s) => !platform || s.platformId === platform)
            .map((s) => (
              <option key={s.shopId} value={s.shopId}>
                {s.shopCode}
              </option>
            ))}
        </select>
        <Button
          variant="secondary"
          size="sm"
          disabled={!platform}
          onClick={() => {
            const platformId = platform;
            const shopId = shop || null;
            setPlatform('');
            setShop('');
            void onAddScope(version, { platformId, shopId });
          }}
        >
          追加适用范围
        </Button>
      </div>
    </div>
  );
}

/** 把词库原文拆成词条数组（每行一个词；空行与 # 注释行忽略）。 */
function parseTermsText(content: string): { term: string; termKind: string }[] {
  const seen = new Set<string>();
  const terms: { term: string; termKind: string }[] = [];
  for (const line of content.split(/\r?\n/)) {
    const value = line.trim();
    if (!value || value.startsWith('#')) continue;
    if (seen.has(value)) continue;
    seen.add(value);
    terms.push({ term: value, termKind: 'GENERAL' });
  }
  return terms;
}
