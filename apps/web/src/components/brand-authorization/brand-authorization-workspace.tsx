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
  ClipboardCheck,
  FileCheck2,
  Loader2,
  RefreshCw,
  ShieldAlert,
  TriangleAlert,
} from 'lucide-react';
import {
  candidatesUnavailable,
  conclusionLabel,
  conclusionTone,
  createAuthorization,
  daysToExpiryText,
  emptyState,
  expiryStateLabel,
  expiryStateTone,
  fetchAuthorization,
  fetchAuthorizations,
  fetchBrandCandidates,
  fetchCapabilities,
  fetchChecks,
  fetchExpiryReminders,
  fetchShops,
  fetchSupplierCandidates,
  gateSummary,
  licenseeTypeLabel,
  runCheck,
  runPublishGate,
  scopeSummary,
  setAuthorizationEnabled,
  severityLabel,
  severityTone,
  updateAuthorization,
  type BrandAuthorizationCandidatePage,
  type BrandAuthorizationCapabilities,
  type BrandAuthorizationCheckPage,
  type BrandAuthorizationCheckSummary,
  type BrandAuthorizationDetail,
  type BrandAuthorizationExpiryPage,
  type BrandAuthorizationRegistrationInput,
  type BrandAuthorizationPage,
  type BrandAuthorizationPublishGate,
  type BrandAuthorizationRecord,
  type BrandAuthorizationShopOption,
} from './brand-authorization-model';

/**
 * 16.4.2.1 品牌授权 工作区。
 *
 * 蓝图：「管理品牌授权：授权方、授权范围（店铺/国家/类目/期限）、授权文件、被授权主体，
 * 校验刊登时的品牌使用是否在授权范围内。输入：授权录入；文件上传；有效期。
 * 输出：授权档案 + 校验结果。关系：上游 = 6.1.2.3 供应商资质；下游 = 3.3.2 品牌状态、4.2.3.2 预检。
 * **授权过期是「平时没事、上新时出事」的典型**，需到期提醒与刊登阻断。」
 *
 * 🔴 页面上的四条纪律：
 *
 * 1. **到期提醒单列一节**，不混在授权档案里 —— 「平时没事、上新时出事」的那一半必须一眼看到。
 * 2. **判定结论原样显示后端算的**（`conclusion` / `severity` / `reasons`），前端不重算、不编原因。
 * 3. **「判不了」是第三种状态**：显示成「判不了（上下文不全）」，且**闸门不放行**。
 * 4. **候选读不到就说读不到**（catalog / trade 挂掉时），不显示成「暂无候选」。
 */
const PAGE_SIZE = 10;

interface FormState {
  brandId: string;
  brandName: string;
  grantor: string;
  licenseeName: string;
  licenseeType: string;
  qualificationId: string;
  effectiveFrom: string;
  effectiveTo: string;
  countries: string;
  categoryIds: string;
  shopIds: string[];
  fileName: string;
  contentType: string;
  sizeBytes: string;
  storageKey: string;
  note: string;
}

const EMPTY_FORM: FormState = {
  brandId: '',
  brandName: '',
  grantor: '',
  licenseeName: '',
  licenseeType: 'SUPPLIER',
  qualificationId: '',
  effectiveFrom: '',
  effectiveTo: '',
  countries: '',
  categoryIds: '',
  shopIds: [],
  fileName: '',
  contentType: 'application/pdf',
  sizeBytes: '0',
  storageKey: '',
  note: '',
};

export function BrandAuthorizationWorkspace() {
  const [caps, setCaps] = useState<BrandAuthorizationCapabilities | null>(null);
  const [authorizations, setAuthorizations] = useState<BrandAuthorizationPage | null>(null);
  const [reminders, setReminders] = useState<BrandAuthorizationExpiryPage | null>(null);
  const [checks, setChecks] = useState<BrandAuthorizationCheckPage | null>(null);
  const [shops, setShops] = useState<BrandAuthorizationShopOption[]>([]);
  const [detail, setDetail] = useState<BrandAuthorizationDetail | null>(null);
  const [gate, setGate] = useState<BrandAuthorizationPublishGate | null>(null);

  const [keyword, setKeyword] = useState('');
  const [state, setState] = useState('');
  const [page, setPage] = useState(0);
  const [reminderState, setReminderState] = useState('');
  const [reminderPage, setReminderPage] = useState(0);
  const [checkPage, setCheckPage] = useState(0);

  const [brandId, setBrandId] = useState('');
  const [shopId, setShopId] = useState('');
  const [countryCode, setCountryCode] = useState('');
  const [categoryId, setCategoryId] = useState('');
  const [brandCandidates, setBrandCandidates] = useState<BrandAuthorizationCandidatePage | null>(null);
  const [supplierCandidates, setSupplierCandidates] = useState<BrandAuthorizationCandidatePage | null>(null);

  const [editing, setEditing] = useState<BrandAuthorizationRecord | null>(null);
  const [formOpen, setFormOpen] = useState(false);
  const [form, setForm] = useState<FormState>(EMPTY_FORM);

  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const loadAuthorizations = useCallback(async (target: number) => {
    const result = await fetchAuthorizations(target, PAGE_SIZE, { keyword, state });
    setAuthorizations(result);
    return result;
  }, [keyword, state]);

  const loadReminders = useCallback(async (target: number) => {
    const result = await fetchExpiryReminders(target, PAGE_SIZE, { state: reminderState });
    setReminders(result);
    return result;
  }, [reminderState]);

  const loadChecks = useCallback(async (target: number) => {
    const result = await fetchChecks(target, PAGE_SIZE);
    setChecks(result);
    return result;
  }, []);

  const refresh = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const [capsResult] = await Promise.all([
        fetchCapabilities(),
        loadAuthorizations(page),
        loadReminders(reminderPage),
        loadChecks(checkPage),
        fetchShops().then(setShops),
      ]);
      setCaps(capsResult);
    } catch (e) {
      setError(e instanceof Error ? e.message : '加载失败，请重试');
    } finally {
      setLoading(false);
    }
  }, [loadAuthorizations, loadReminders, loadChecks, page, reminderPage, checkPage]);

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
    (id: string) =>
      run(async () => {
        setDetail(await fetchAuthorization(id));
      }),
    [run],
  );

  const toggleEnabled = useCallback(
    (record: BrandAuthorizationRecord) =>
      run(async () => {
        await setAuthorizationEnabled(record.authorizationId, !record.enabled);
        await loadAuthorizations(page);
        await loadReminders(reminderPage);
      }),
    [run, loadAuthorizations, loadReminders, page, reminderPage],
  );

  const doCheck = useCallback(
    () =>
      run(async () => {
        if (!brandId.trim()) return;
        await runCheck({
          brandId: brandId.trim(),
          shopId: shopId.trim() || null,
          countryCode: countryCode.trim() || null,
          categoryId: categoryId.trim() || null,
        });
        setGate(null);
        setDetail(null);
        await loadChecks(checkPage);
      }),
    [run, brandId, shopId, countryCode, categoryId, checkPage, loadChecks],
  );

  const doGate = useCallback(
    () =>
      run(async () => {
        if (!brandId.trim()) return;
        const result = await runPublishGate({
          brandId: brandId.trim(),
          shopId: shopId.trim() || null,
          countryCode: countryCode.trim() || null,
          categoryId: categoryId.trim() || null,
        });
        setGate(result);
        await loadChecks(checkPage);
      }),
    [run, brandId, shopId, countryCode, categoryId, checkPage, loadChecks],
  );

  const lookupBrands = useCallback(
    () =>
      run(async () => {
        setBrandCandidates(await fetchBrandCandidates(brandId.trim() || undefined));
      }),
    [run, brandId],
  );

  const lookupSuppliers = useCallback(
    () =>
      run(async () => {
        setSupplierCandidates(await fetchSupplierCandidates(licenseeNameOf(form)));
      }),
    [run, form],
  );

  const openCreate = useCallback(() => {
    setEditing(null);
    setForm({ ...EMPTY_FORM, brandId: brandId.trim(), brandName: '' });
    setFormOpen(true);
  }, [brandId]);

  const openEdit = useCallback(
    (record: BrandAuthorizationRecord) =>
      run(async () => {
        const loaded = await fetchAuthorization(record.authorizationId);
        setEditing(record);
        setForm({
          brandId: loaded.record.brandId,
          brandName: loaded.record.brandName,
          grantor: loaded.record.grantor,
          licenseeName: loaded.record.licenseeName,
          licenseeType: loaded.record.licenseeType,
          qualificationId: loaded.record.qualificationId ?? '',
          effectiveFrom: loaded.record.effectiveFrom.slice(0, 10),
          effectiveTo: loaded.record.effectiveTo.slice(0, 10),
          countries: loaded.scopes
            .filter((scope) => scope.scopeKind === 'COUNTRY')
            .map((scope) => scope.scopeValue)
            .join(', '),
          categoryIds: loaded.scopes
            .filter((scope) => scope.scopeKind === 'CATEGORY')
            .map((scope) => scope.scopeValue)
            .join(', '),
          shopIds: loaded.scopes
            .filter((scope) => scope.scopeKind === 'SHOP')
            .map((scope) => scope.scopeValue),
          fileName: loaded.documents[0]?.fileName ?? '',
          contentType: loaded.documents[0]?.contentType ?? 'application/pdf',
          sizeBytes: String(loaded.documents[0]?.sizeBytes ?? 0),
          storageKey: loaded.documents[0]?.storageKey ?? '',
          note: loaded.record.note,
        });
        setFormOpen(true);
      }),
    [run],
  );

  const submitForm = useCallback(
    () =>
      run(async () => {
        const input = buildInput(form);
        if (editing) {
          await updateAuthorization(editing.authorizationId, input);
        } else {
          await createAuthorization(input);
        }
        setFormOpen(false);
        await loadAuthorizations(page);
        await loadReminders(reminderPage);
      }),
    [run, form, editing, loadAuthorizations, loadReminders, page, reminderPage],
  );

  const authorizationItems = authorizations?.items ?? [];
  const reminderItems = reminders?.items ?? [];
  const checkItems = useMemo(() => checks?.items ?? [], [checks]);

  const latestCheck = useMemo<BrandAuthorizationCheckSummary | null>(
    () => checkItems[0] ?? null,
    [checkItems],
  );

  if (loading) {
    return (
      <div className="flex items-center justify-center gap-2 py-16 text-muted-foreground">
        <Loader2 className="size-4 animate-spin" />
        <span className="text-label-13">正在加载品牌授权…</span>
      </div>
    );
  }

  if (error && !caps) {
    return (
      <SectionCard title="品牌授权">
        <EmptyState
          icon={<TriangleAlert className="size-8 text-destructive" />}
          title="品牌授权加载失败"
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
        title="品牌授权"
        description="授权方、授权范围（店铺 / 国家 / 类目 / 期限）、授权文件、被授权主体，并校验刊登时的品牌使用是否在授权范围内。"
        actions={
          <>
            <Button variant="outline" onClick={() => void refresh()}>
              <RefreshCw className="mr-2 size-4" />
              刷新
            </Button>
            <Button onClick={openCreate}>
              <ClipboardCheck className="mr-2 size-4" />
              录入授权
            </Button>
          </>
        }
      />

      {error && (
        <SectionCard>
          <div className="text-destructive flex items-center gap-2 text-copy-13">
            <TriangleAlert className="size-4" />
            {error}
          </div>
        </SectionCard>
      )}

      <div className="grid gap-3 sm:grid-cols-2 xl:grid-cols-4">
        <StatCard label="授权档案" value={authorizations?.total ?? 0} icon={<FileCheck2 className="size-4" />} />
        <StatCard
          label="即将到期"
          value={reminders?.expiringCount ?? 0}
          polarity="negative"
          icon={<CircleAlert className="size-4" />}
        />
        <StatCard
          label="已过期"
          value={reminders?.expiredCount ?? 0}
          polarity="negative"
          icon={<ShieldAlert className="size-4" />}
        />
        <StatCard
          label="最近一次校验"
          value={latestCheck ? conclusionLabel(latestCheck.conclusion) : '尚未校验'}
          icon={<ClipboardCheck className="size-4" />}
        />
      </div>

      {caps && caps.gaps.length > 0 && (
        <SectionCard
          title="能力声明"
          description="下面这些是**本模块之外**、当前尚未交付的能力。它们缺了不影响本模块的授权范围校验。"
        >
          <ul className="space-y-2">
            {caps.gaps.map((gap) => (
              <li key={gap.moduleId} className="text-copy-13 flex items-start gap-2">
                <Badge tone="warning">{gap.moduleId}</Badge>
                <span className="text-foreground">{gap.moduleName}</span>
                <span className="text-muted-foreground">{gap.impact}</span>
              </li>
            ))}
          </ul>
          <p className="text-muted-foreground mt-3 text-copy-12">{caps.boundaryNote}</p>
        </SectionCard>
      )}

      {/* ---------- 到期提醒 ---------- */}
      <SectionCard
        title="到期提醒"
        description="授权过期是「平时没事、上新时出事」—— 临期与已过期在这里单列。"
        extra={
          <select
            className="border-control-border bg-card text-copy-13 h-9 rounded-md border px-2"
            value={reminderState}
            onChange={(e) => {
              setReminderState(e.target.value);
              setReminderPage(0);
              void loadReminders(0);
            }}
          >
            <option value="">全部</option>
            <option value="EXPIRING">即将到期</option>
            <option value="EXPIRED">已过期</option>
            <option value="ACTIVE">生效中</option>
          </select>
        }
      >
        {reminderItems.length === 0 ? (
          <EmptyState
            icon={<FileCheck2 className="size-8" />}
            title={emptyState('NO_EXPIRING').title}
            description={emptyState('NO_EXPIRING').description}
          />
        ) : (
          <div className="space-y-2">
            {reminderItems.map((row) => (
              <div
                key={row.authorizationId}
                className="border-border flex flex-wrap items-center justify-between gap-2 rounded-md border px-3 py-2"
              >
                <div className="min-w-0">
                  <div className="text-copy-13 text-foreground">
                    {row.brandName || row.brandId} · {row.grantor} → {row.licenseeName}
                  </div>
                  <div className="text-copy-12 text-muted-foreground">
                    到期 {row.effectiveTo.slice(0, 10)} · {row.scopeSummary}
                  </div>
                </div>
                <div className="flex items-center gap-2">
                  <Badge tone={expiryStateTone(row.expiryState)}>{row.expiryStateLabel}</Badge>
                  <span className="text-copy-12 text-muted-foreground">
                    {daysToExpiryText(row.daysToExpiry)}
                  </span>
                  <Button variant="ghost" size="sm" onClick={() => void openDetail(row.authorizationId)}>
                    查看
                  </Button>
                </div>
              </div>
            ))}
            <Pager
              page={reminders?.page ?? 0}
              size={PAGE_SIZE}
              total={reminders?.total ?? 0}
              onChange={(target) => {
                setReminderPage(target);
                void loadReminders(target);
              }}
            />
          </div>
        )}
      </SectionCard>

      {/* ---------- 授权档案 ---------- */}
      <SectionCard
        title="授权档案"
        description="授权方 / 授权范围 / 授权文件 / 被授权主体 / 期限。"
        extra={
          <>
            <Input
              className="h-9 w-40"
              placeholder="搜索授权方 / 被授权主体"
              value={keyword}
              onChange={(e) => setKeyword(e.target.value)}
            />
            <Button
              variant="secondary"
              onClick={() => {
                setPage(0);
                void loadAuthorizations(0);
              }}
            >
              搜索
            </Button>
          </>
        }
      >
        {authorizationItems.length === 0 ? (
          <EmptyState
            icon={<FileCheck2 className="size-8" />}
            title={emptyState('NO_AUTHORIZATION').title}
            description={emptyState('NO_AUTHORIZATION').description}
            action={
              <Button onClick={openCreate}>
                <ClipboardCheck className="mr-2 size-4" />
                录入授权
              </Button>
            }
          />
        ) : (
          <div className="space-y-2">
            {authorizationItems.map((record) => (
              <div
                key={record.authorizationId}
                className="border-border flex flex-wrap items-center justify-between gap-2 rounded-md border px-3 py-2"
              >
                <div className="min-w-0">
                  <div className="text-copy-13 text-foreground">
                    {record.brandName || record.brandId} · {record.grantor} → {record.licenseeName}
                    <span className="text-muted-foreground">
                      （{licenseeTypeLabel(record.licenseeType)}）
                    </span>
                  </div>
                  <div className="text-copy-12 text-muted-foreground">
                    {record.effectiveFrom.slice(0, 10)} ~ {record.effectiveTo.slice(0, 10)} ·{' '}
                    {record.scopeCount} 条范围 · {record.documentCount} 份文件
                  </div>
                </div>
                <div className="flex items-center gap-2">
                  <Badge tone={expiryStateTone(record.expiryState)}>
                    {record.expiryStateLabel}
                  </Badge>
                  <span className="text-copy-12 text-muted-foreground">
                    {daysToExpiryText(record.daysToExpiry)}
                  </span>
                  <Button variant="ghost" size="sm" onClick={() => void openDetail(record.authorizationId)}>
                    详情
                  </Button>
                  <Button variant="ghost" size="sm" onClick={() => void openEdit(record)}>
                    修改
                  </Button>
                  <Button variant="ghost" size="sm" onClick={() => void toggleEnabled(record)}>
                    {record.enabled ? '停用' : '启用'}
                  </Button>
                </div>
              </div>
            ))}
            <Pager
              page={authorizations?.page ?? 0}
              size={PAGE_SIZE}
              total={authorizations?.total ?? 0}
              onChange={(target) => {
                setPage(target);
                void loadAuthorizations(target);
              }}
            />
          </div>
        )}
      </SectionCard>

      {/* ---------- 校验与刊登闸门 ---------- */}
      <SectionCard
        title="刊登时的品牌使用校验"
        description="填一次刊登上下文，跑一次范围校验。结论由服务端算，页面只显示。"
      >
        <div className="grid gap-3 sm:grid-cols-2 xl:grid-cols-4">
          <label className="space-y-1">
            <span className="text-label-13 text-foreground">品牌 id</span>
            <Input
              value={brandId}
              placeholder="03 品牌 id"
              onChange={(e) => setBrandId(e.target.value)}
            />
          </label>
          <label className="space-y-1">
            <span className="text-label-13 text-foreground">店铺</span>
            <select
              className="border-control-border bg-card text-copy-13 h-9 w-full rounded-md border px-2"
              value={shopId}
              onChange={(e) => setShopId(e.target.value)}
            >
              <option value="">（不指定）</option>
              {shops.map((shop) => (
                <option key={shop.shopId} value={shop.shopId}>
                  {shop.shopName}（{shop.countryCodes.join('/') || '无国家'}）
                </option>
              ))}
            </select>
          </label>
          <label className="space-y-1">
            <span className="text-label-13 text-foreground">国家</span>
            <Input
              value={countryCode}
              placeholder="如 DE"
              onChange={(e) => setCountryCode(e.target.value)}
            />
          </label>
          <label className="space-y-1">
            <span className="text-label-13 text-foreground">类目 id</span>
            <Input
              value={categoryId}
              placeholder="03 类目 id"
              onChange={(e) => setCategoryId(e.target.value)}
            />
          </label>
        </div>

        <div className="mt-3 flex flex-wrap items-center gap-2">
          <Button onClick={() => void doCheck()} disabled={busy || !brandId.trim()}>
            <ClipboardCheck className="mr-2 size-4" />
            跑一次范围校验
          </Button>
          <Button variant="outline" onClick={() => void doGate()} disabled={busy || !brandId.trim()}>
            <ShieldAlert className="mr-2 size-4" />
            跑刊登闸门
          </Button>
          <Button variant="ghost" onClick={() => void lookupBrands()} disabled={busy}>
            从 03 品牌里查
          </Button>
        </div>

        {brandCandidates && (
          <div className="mt-3 space-y-1">
            <p className="text-copy-12 text-muted-foreground">{brandCandidates.note}</p>
            <div className="flex flex-wrap gap-2">
              {brandCandidates.items.map((candidate) => (
                <Button
                  key={candidate.id}
                  variant="secondary"
                  size="sm"
                  onClick={() => setBrandId(candidate.id)}
                >
                  {candidate.name}
                </Button>
              ))}
            </div>
          </div>
        )}

        {gate && (
          <div className="border-border mt-4 rounded-md border px-3 py-3">
            <div className="flex flex-wrap items-center gap-2">
              <Badge tone={conclusionTone(gate.conclusion)}>{conclusionLabel(gate.conclusion)}</Badge>
              <Badge tone={severityTone(gate.severity)}>{severityLabel(gate.severity)}</Badge>
              <span className="text-copy-13 text-foreground">{gateSummary(gate)}</span>
            </div>
            <ReasonList reasons={gate.reasons} />
            <p className="text-copy-12 text-muted-foreground mt-2">
              判定时刻 {gate.evaluatedAt} · 规则版本 {gate.ruleVersion} · 凭据 {gate.checkId}
            </p>
          </div>
        )}

        {!gate && latestCheck && (
          <div className="border-border mt-4 rounded-md border px-3 py-3">
            <div className="flex flex-wrap items-center gap-2">
              <Badge tone={conclusionTone(latestCheck.conclusion)}>
                {latestCheck.conclusionLabel}
              </Badge>
              <Badge tone={severityTone(latestCheck.severity)}>{latestCheck.severityLabel}</Badge>
            </div>
            <ReasonList reasons={latestCheck.reasons} />
          </div>
        )}
      </SectionCard>

      {/* ---------- 校验历史 ---------- */}
      <SectionCard title="校验历史" description="每一次校验都是一条不可改写的判定凭据。">
        {checkItems.length === 0 ? (
          <EmptyState
            icon={<ClipboardCheck className="size-8" />}
            title={emptyState('NO_CHECK').title}
            description={emptyState('NO_CHECK').description}
          />
        ) : (
          <div className="space-y-2">
            {checkItems.map((item) => (
              <div
                key={item.checkId}
                className="border-border flex flex-wrap items-center justify-between gap-2 rounded-md border px-3 py-2"
              >
                <div className="min-w-0">
                  <div className="text-copy-13 text-foreground">
                    {item.brandName || item.brandId}
                    {item.countryCode ? ` · ${item.countryCode}` : ''}
                    {item.categoryId ? ` · ${item.categoryId.slice(0, 8)}` : ''}
                  </div>
                  <div className="text-copy-12 text-muted-foreground">
                    {item.evaluatedAt.slice(0, 19).replace('T', ' ')} · 规则版本 {item.ruleVersion} ·{' '}
                    {item.reasonCount} 条原因
                  </div>
                </div>
                <div className="flex items-center gap-2">
                  <Badge tone={conclusionTone(item.conclusion)}>{item.conclusionLabel}</Badge>
                  <Badge tone={severityTone(item.severity)}>{item.severityLabel}</Badge>
                </div>
              </div>
            ))}
            <Pager
              page={checks?.page ?? 0}
              size={PAGE_SIZE}
              total={checks?.total ?? 0}
              onChange={(target) => {
                setCheckPage(target);
                void loadChecks(target);
              }}
            />
          </div>
        )}
      </SectionCard>

      {/* ---------- 授权详情 ---------- */}
      <Modal open={detail !== null} onOpenChange={(open) => !open && setDetail(null)}>
        <ModalContent>
          <ModalHeader>
            <ModalTitle>授权详情</ModalTitle>
            <ModalDescription>
              授权方、授权范围、授权文件与被授权主体。授权文件只登记**引用与元数据**，文件本体不入库。
            </ModalDescription>
          </ModalHeader>
          {detail && (
            <div className="space-y-3 text-copy-13">
              <Row label="品牌" value={detail.record.brandName || detail.record.brandId} />
              <Row label="授权方" value={detail.record.grantor} />
              <Row label="被授权主体" value={detail.record.licenseeName} />
              <Row label="主体类型" value={licenseeTypeLabel(detail.record.licenseeType)} />
              <Row
                label="授权期限"
                value={`${detail.record.effectiveFrom.slice(0, 10)} ~ ${detail.record.effectiveTo.slice(0, 10)}`}
              />
              <Row label="到期状态" value={detail.record.expiryStateLabel} />
              <Row label="授权范围" value={scopeSummary(detail.scopes)} />
              <Row
                label="授权文件"
                value={
                  detail.documents.length === 0
                    ? '（未登记）'
                    : detail.documents
                        .map(
                          (document) =>
                            `${document.fileName}（${document.contentType} · ${document.sizeBytes} 字节 · ${document.storageKey}）`,
                        )
                        .join('；')
                }
              />
              <Row label="备注" value={detail.record.note || '（无）'} />
            </div>
          )}
          <ModalFooter>
            <Button variant="outline" onClick={() => setDetail(null)}>
              关闭
            </Button>
          </ModalFooter>
        </ModalContent>
      </Modal>

      {/* ---------- 录入 / 修改 ---------- */}
      <Modal open={formOpen} onOpenChange={(open) => !open && setFormOpen(false)}>
        <ModalContent>
          <ModalHeader>
            <ModalTitle>{editing ? '修改授权' : '录入授权'}</ModalTitle>
            <ModalDescription>
              授权方 / 授权范围（店铺 · 国家 · 类目）/ 被授权主体 / 期限。
              同一维度上没登记任何范围 = 该维度不限制。
            </ModalDescription>
          </ModalHeader>
          <div className="grid gap-3 sm:grid-cols-2">
            <label className="space-y-1">
              <span className="text-label-13 text-foreground">品牌 id *</span>
              <Input
                value={form.brandId}
                onChange={(e) => setForm({ ...form, brandId: e.target.value })}
              />
            </label>
            <label className="space-y-1">
              <span className="text-label-13 text-foreground">品牌名</span>
              <Input
                value={form.brandName}
                onChange={(e) => setForm({ ...form, brandName: e.target.value })}
              />
            </label>
            <label className="space-y-1">
              <span className="text-label-13 text-foreground">授权方 *</span>
              <Input
                value={form.grantor}
                onChange={(e) => setForm({ ...form, grantor: e.target.value })}
              />
            </label>
            <label className="space-y-1">
              <span className="text-label-13 text-foreground">被授权主体 *</span>
              <Input
                value={form.licenseeName}
                onChange={(e) => setForm({ ...form, licenseeName: e.target.value })}
              />
            </label>
            <label className="space-y-1">
              <span className="text-label-13 text-foreground">主体类型</span>
              <select
                className="border-control-border bg-card text-copy-13 h-9 w-full rounded-md border px-2"
                value={form.licenseeType}
                onChange={(e) => setForm({ ...form, licenseeType: e.target.value })}
              >
                <option value="SUPPLIER">供应商</option>
                <option value="DISTRIBUTOR">经销商</option>
                <option value="AGENT">代理商</option>
                <option value="SELF">品牌自有主体</option>
              </select>
            </label>
            <label className="space-y-1">
              <span className="text-label-13 text-foreground">资质 id（6.1.2.3）</span>
              <Input
                value={form.qualificationId}
                onChange={(e) => setForm({ ...form, qualificationId: e.target.value })}
              />
            </label>
            <label className="space-y-1">
              <span className="text-label-13 text-foreground">期限起 *</span>
              <Input
                type="date"
                value={form.effectiveFrom}
                onChange={(e) => setForm({ ...form, effectiveFrom: e.target.value })}
              />
            </label>
            <label className="space-y-1">
              <span className="text-label-13 text-foreground">期限止 *</span>
              <Input
                type="date"
                value={form.effectiveTo}
                onChange={(e) => setForm({ ...form, effectiveTo: e.target.value })}
              />
            </label>
            <label className="space-y-1">
              <span className="text-label-13 text-foreground">国家范围（逗号分隔）</span>
              <Input
                value={form.countries}
                placeholder="DE, US"
                onChange={(e) => setForm({ ...form, countries: e.target.value })}
              />
            </label>
            <label className="space-y-1">
              <span className="text-label-13 text-foreground">类目范围 id（逗号分隔）</span>
              <Input
                value={form.categoryIds}
                onChange={(e) => setForm({ ...form, categoryIds: e.target.value })}
              />
            </label>
          </div>

          <div className="mt-3 space-y-1">
            <span className="text-label-13 text-foreground">店铺范围</span>
            <div className="flex flex-wrap gap-2">
              {shops.map((shop) => {
                const picked = form.shopIds.includes(shop.shopId);
                return (
                  <Button
                    key={shop.shopId}
                    variant={picked ? 'brand' : 'secondary'}
                    size="sm"
                    onClick={() =>
                      setForm({
                        ...form,
                        shopIds: picked
                          ? form.shopIds.filter((id) => id !== shop.shopId)
                          : [...form.shopIds, shop.shopId],
                      })
                    }
                  >
                    {shop.shopName}
                  </Button>
                );
              })}
              {shops.length === 0 && (
                <span className="text-copy-12 text-muted-foreground">本租户下没有可选店铺。</span>
              )}
            </div>
          </div>

          <div className="mt-3 grid gap-3 sm:grid-cols-2">
            <label className="space-y-1">
              <span className="text-label-13 text-foreground">授权文件名</span>
              <Input
                value={form.fileName}
                onChange={(e) => setForm({ ...form, fileName: e.target.value })}
              />
            </label>
            <label className="space-y-1">
              <span className="text-label-13 text-foreground">内容类型</span>
              <Input
                value={form.contentType}
                onChange={(e) => setForm({ ...form, contentType: e.target.value })}
              />
            </label>
            <label className="space-y-1">
              <span className="text-label-13 text-foreground">字节数</span>
              <Input
                value={form.sizeBytes}
                onChange={(e) => setForm({ ...form, sizeBytes: e.target.value })}
              />
            </label>
            <label className="space-y-1">
              <span className="text-label-13 text-foreground">存储句柄</span>
              <Input
                value={form.storageKey}
                placeholder="obj://…"
                onChange={(e) => setForm({ ...form, storageKey: e.target.value })}
              />
            </label>
          </div>

          <label className="mt-3 block space-y-1">
            <span className="text-label-13 text-foreground">备注</span>
            <Input value={form.note} onChange={(e) => setForm({ ...form, note: e.target.value })} />
          </label>

          {supplierCandidates && supplierCandidates.items.length > 0 && (
            <div className="mt-3 space-y-1">
              <span className="text-label-13 text-foreground">6.1.2.3 供应商候选</span>
              <div className="flex flex-wrap gap-2">
                {supplierCandidates.items.map((candidate) => (
                  <Button
                    key={candidate.id}
                    variant="secondary"
                    size="sm"
                    onClick={() => setForm({ ...form, licenseeName: candidate.name })}
                  >
                    {candidate.name}
                  </Button>
                ))}
              </div>
            </div>
          )}
          <Button
            variant="ghost"
            size="sm"
            className="mt-2"
            onClick={() => void lookupSuppliers()}
            disabled={busy}
          >
            查 6.1.2.3 供应商候选
          </Button>
          {supplierCandidates && candidatesUnavailable(supplierCandidates) && (
            <p className="text-copy-12 text-muted-foreground mt-1">
              🔴 6.1.2.3 供应商候选**读不到**（{supplierCandidates.note}）——
              这不是「没有供应商」。
            </p>
          )}

          <ModalFooter>
            <Button variant="outline" onClick={() => setFormOpen(false)}>
              取消
            </Button>
            <Button onClick={() => void submitForm()} disabled={busy}>
              {editing ? '保存' : '录入'}
            </Button>
          </ModalFooter>
        </ModalContent>
      </Modal>
    </div>
  );
}

// ==================================================================
// 局部组件
// ==================================================================

function Pager({
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
  const pages = Math.max(1, Math.ceil(total / size));
  if (total === 0) return null;
  return (
    <div className="text-copy-12 text-muted-foreground flex items-center justify-end gap-2">
      <span>
        第 {page + 1} / {pages} 页 · 共 {total} 条
      </span>
      <Button variant="secondary" size="sm" disabled={page <= 0} onClick={() => onChange(page - 1)}>
        上一页
      </Button>
      <Button
        variant="secondary"
        size="sm"
        disabled={page + 1 >= pages}
        onClick={() => onChange(page + 1)}
      >
        下一页
      </Button>
    </div>
  );
}

function Row({ label, value }: { label: string; value: string }) {
  return (
    <div className="flex gap-2">
      <span className="text-muted-foreground w-24 shrink-0">{label}</span>
      <span className="text-foreground min-w-0 break-words">{value}</span>
    </div>
  );
}

function ReasonList({
  reasons,
}: {
  reasons: { code: string; label: string; message: string; severity: string }[];
}) {
  if (!reasons || reasons.length === 0) return null;
  return (
    <ul className="mt-2 space-y-1">
      {reasons.map((reason) => (
        <li key={`${reason.code}-${reason.message}`} className="text-copy-13 flex items-start gap-2">
          <Badge tone={severityTone(reason.severity)}>{reason.label}</Badge>
          <span className="text-foreground">{reason.message}</span>
        </li>
      ))}
    </ul>
  );
}

function licenseeNameOf(form: FormState): string | undefined {
  return form.licenseeName.trim() || undefined;
}

/** 把表单拼成后端的入参。 */
function buildInput(form: FormState): BrandAuthorizationRegistrationInput {
  const split = (raw: string) =>
    raw
      .split(/[,，\s]+/)
      .map((value) => value.trim())
      .filter((value) => value.length > 0);
  const scopes = [
    ...split(form.countries).map((value) => ({
      scopeKind: 'COUNTRY',
      scopeValue: value.toUpperCase(),
      scopeLabel: value.toUpperCase(),
    })),
    ...split(form.categoryIds).map((value) => ({
      scopeKind: 'CATEGORY',
      scopeValue: value,
      scopeLabel: value,
    })),
    ...form.shopIds.map((value) => ({
      scopeKind: 'SHOP',
      scopeValue: value,
      scopeLabel: value,
    })),
  ];
  const documents =
    form.fileName.trim().length > 0 && form.storageKey.trim().length > 0
      ? [
          {
            fileName: form.fileName.trim(),
            contentType: form.contentType.trim() || 'application/octet-stream',
            sizeBytes: Number.parseInt(form.sizeBytes || '0', 10) || 0,
            storageKey: form.storageKey.trim(),
            uploadedAt: null,
            note: '',
          },
        ]
      : [];
  return {
    brandId: form.brandId.trim(),
    brandName: form.brandName.trim(),
    grantor: form.grantor.trim(),
    licenseeName: form.licenseeName.trim(),
    licenseeType: form.licenseeType,
    qualificationId: form.qualificationId.trim() || null,
    effectiveFrom: form.effectiveFrom,
    effectiveTo: form.effectiveTo,
    enabled: true,
    note: form.note.trim(),
    scopes,
    documents,
  };
}
