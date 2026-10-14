'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import {
  FileCheck2,
  FilePlus2,
  RefreshCw,
  ScrollText,
  ShieldCheck,
  TriangleAlert,
} from 'lucide-react';
import {
  Button,
  EmptyState,
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
import type {
  TradeDocumentCapabilities,
  TradeDocumentRow,
  TradeReminderRow,
  TradeSetRow,
} from '../../../../../packages/api-contracts/src/index';
import { errorText, fetchLegalEntities } from '../inventory/inventory-model';
import {
  acknowledgeTradeSet,
  cancelTradeSet,
  certificationStatusLabels,
  checkResultLabels,
  claimTradePreference,
  confirmTradePreference,
  createTradeCertification,
  createTradePreferenceRule,
  createTradeRule,
  createTradeSet,
  documentsReady,
  fetchTradeCapabilities,
  fetchTradeCertifications,
  fetchTradePreferenceConfirmations,
  fetchTradePreferenceRules,
  fetchTradeReminders,
  fetchTradeRules,
  fetchTradeSetDetail,
  fetchTradeSets,
  finalizeTradeDocument,
  generateTradeDocument,
  isOriginCertificate,
  money,
  moment,
  rejectTradeSetReview,
  reminderTypeLabels,
  requirementLevelLabels,
  resolveTradeReminder,
  reviewTradeSet,
  runTradeCheck,
  sendBackTradeSet,
  submitBlockers,
  submitTradeSet,
  submitTradeSetForReview,
  substantiveChangeBlocking,
  substantiveChangeLabels,
  today,
  tradeActionLabel,
  tradeDocumentKindLabels,
  tradeEventLabels,
  tradeSetActions,
  tradeSetSortOptions,
  tradeSetStatusLabels,
  withdrawTradeSetReview,
  type TradeRuleParams,
  type TradeSetParams,
} from './trade-document-model';

type Detail = Awaited<ReturnType<typeof fetchTradeSetDetail>>;
type PrefRules = Awaited<ReturnType<typeof fetchTradePreferenceRules>>;
type Confirmations = Awaited<ReturnType<typeof fetchTradePreferenceConfirmations>>;
type ReminderPage = Awaited<ReturnType<typeof fetchTradeReminders>>;
type CertPage = Awaited<ReturnType<typeof fetchTradeCertifications>>;

interface LegalEntityOption {
  id: string;
  code: string;
  name: string;
}

type Tab = 'sets' | 'rules' | 'certifications' | 'preferences' | 'reminders';

/**
 * 改一个筛选条件。
 *
 * <p>值为空就**删掉这个键**，而不是写 `undefined`：`exactOptionalPropertyTypes` 下
 * `undefined` 不等于「没填」，写进去会一路传到查询串里变成 `status=undefined`。
 * 顺带把页码复位 —— 任何筛选变化都必须回到第一页，否则会停在一个已经空掉的页码上。
 */
function patchParam(
  prev: TradeSetParams,
  patch: Partial<Record<keyof TradeSetParams, string | boolean | string[] | number>>,
): TradeSetParams {
  const next: Record<string, unknown> = { ...prev };
  for (const [key, value] of Object.entries(patch)) {
    if (value === undefined || value === '' || value === false) delete next[key];
    else next[key] = value;
  }
  return { ...next, page: 0 } as TradeSetParams;
}

function patchRule(
  prev: TradeRuleParams,
  patch: Partial<Record<keyof TradeRuleParams, string | number>>,
): TradeRuleParams {
  const next: Record<string, unknown> = { ...prev };
  for (const [key, value] of Object.entries(patch)) {
    if (value === undefined || value === '') delete next[key];
    else next[key] = value;
  }
  return { ...next, page: 0 } as TradeRuleParams;
}

function promptOrNull(message: string, fallback = ''): string | null {
  const value = window.prompt(message, fallback);
  return value === null || value.trim() === '' ? null : value.trim();
}

/**
 * 10.5.2.1 贸易单证工作区。
 *
 * <p>蓝图：「管理与生成贸易相关单证：商业发票、装箱单、原产地证（RCEP/CO/FTA 优惠）、提单/空运单、
 * 进口许可与认证文件，按目的地国家规则校验完整性」。
 *
 * <p>界面纪律（三条都写进了常驻口径卡，因为它们是本模块最容易被误读的地方）：
 *
 * <ul>
 *   <li><b>「实质性改变」系统不下结论</b>。主张了 FTA 优惠的原产地证会停在「待业务确认」，
 *       并把这一项显式报成阻断级缺失提醒 —— 界面上不能出现「优惠已通过」这种说法。
 *       本模块能回答「有没有人确认过、依据是什么、什么时候」，不能回答「法律上成不成立」。
 *   <li><b>「待业务确认」不是「校验通过」</b>。所以提交按钮旁的阻断清单会把最近一次校验的
 *       真实结论原样写出来，缺什么写什么，而不是笼统地说「请先完善」。
 *   <li><b>单证要求是数据不是常量</b>。目的国 × 单证类型 × 必备程度落在「目的国单证要求」
 *       页签里、租户自己维护；没配规则时校验会<strong>报不通过</strong>并说清原因，
 *       而不是「没有要求就算通过」。
 * </ul>
 */
export function TradeDocumentWorkspace() {
  const [tab, setTab] = useState<Tab>('sets');
  const [params, setParams] = useState<TradeSetParams>({ page: 0, size: 20 });
  const [data, setData] = useState<Awaited<ReturnType<typeof fetchTradeSets>> | null>(null);
  const [caps, setCaps] = useState<TradeDocumentCapabilities | null>(null);
  const [legalEntities, setLegalEntities] = useState<LegalEntityOption[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [openId, setOpenId] = useState<string | null>(null);
  const [creating, setCreating] = useState(false);

  const [ruleParams, setRuleParams] = useState<TradeRuleParams>({ page: 0, size: 20 });
  const [rules, setRules] = useState<Awaited<ReturnType<typeof fetchTradeRules>> | null>(null);
  const [ruleForm, setRuleForm] = useState({
    countryCode: '',
    documentKind: 'COMMERCIAL_INVOICE',
    requirementLevel: 'MANDATORY',
    conditionExpression: '',
    requiresCertification: false,
  });

  const [prefRules, setPrefRules] = useState<PrefRules | null>(null);
  const [confirmations, setConfirmations] = useState<Confirmations | null>(null);
  const [prefForm, setPrefForm] = useState({
    scheme: 'RCEP',
    destinationCountryCode: '',
    preferenceCriteria: '',
    substantiveChangeCriterion: '',
  });
  const [reminders, setReminders] = useState<ReminderPage | null>(null);

  // 16 合规尚未交付：这里维护的是本租户自己登记的认证状态，每行都带来源标注
  // （TENANT_MAINTAINED / MODULE_16），校验时只按登记值判断，不假装是从 16 合规读来的。
  const [certs, setCerts] = useState<CertPage | null>(null);
  const [certParams, setCertParams] = useState({ page: 0, size: 20 });
  const [certForm, setCertForm] = useState({
    skuId: '',
    certificationCode: '',
    certificationName: '',
    status: 'PENDING',
    certificateNo: '',
    issuingBody: '',
    issuedOn: '',
    expiresOn: '',
  });

  const load = useCallback(async () => {
    setError(null);
    try {
      setData(await fetchTradeSets(params));
    } catch (e) {
      setError(errorText(e));
    } finally {
      setLoading(false);
    }
  }, [params]);

  const loadRules = useCallback(async () => {
    setError(null);
    try {
      setRules(await fetchTradeRules(ruleParams));
    } catch (e) {
      setError(errorText(e));
    } finally {
      setLoading(false);
    }
  }, [ruleParams]);

  const loadPreferences = useCallback(async () => {
    setError(null);
    try {
      setPrefRules(await fetchTradePreferenceRules({ page: 0, size: 100 }));
      setConfirmations(
        await fetchTradePreferenceConfirmations({ claimedOnly: true, page: 0, size: 100 }),
      );
    } catch (e) {
      setError(errorText(e));
    } finally {
      setLoading(false);
    }
  }, []);

  const loadReminders = useCallback(async () => {
    setError(null);
    try {
      setReminders(await fetchTradeReminders({ status: 'OPEN', page: 0, size: 100 }));
    } catch (e) {
      setError(errorText(e));
    } finally {
      setLoading(false);
    }
  }, []);

  const loadCerts = useCallback(async () => {
    setError(null);
    try {
      setCerts(await fetchTradeCertifications(certParams));
    } catch (e) {
      setError(errorText(e));
    } finally {
      setLoading(false);
    }
  }, [certParams]);

  useEffect(() => {
    void load();
  }, [load]);

  useEffect(() => {
    void loadRules();
  }, [loadRules]);

  useEffect(() => {
    void loadPreferences();
    void loadReminders();
  }, [loadPreferences, loadReminders]);

  useEffect(() => {
    // 能力清单与法人下拉各只读一次：它们决定「哪些控件可点」，不是筛选项。
    void (async () => {
      try {
        setCaps(await fetchTradeCapabilities());
      } catch {
        // 能力清单读不到不该让整页不可用：下面的动作入口本来还有状态兜底。
      }
      try {
        setLegalEntities(await fetchLegalEntities());
      } catch {
        setLegalEntities([]);
      }
    })();
  }, []);

  const refreshAll = useCallback(() => {
    void load();
    void loadRules();
    void loadCerts();
    void loadPreferences();
    void loadReminders();
  }, [load, loadRules, loadCerts, loadPreferences, loadReminders]);

  const onChanged = useCallback(async () => {
    await load();
    await loadReminders();
    await loadPreferences();
  }, [load, loadReminders, loadPreferences]);

  const unavailable = useMemo(() => (caps?.dependencies ?? []).filter((d) => !d.available), [caps]);
  const summary = data?.summary;

  const tabs: { value: Tab; label: string }[] = [
    { value: 'sets', label: '单证包' },
    { value: 'rules', label: '目的国单证要求' },
    { value: 'certifications', label: '认证状态' },
    { value: 'preferences', label: 'FTA 优惠与主张台账' },
    { value: 'reminders', label: '缺失提醒' },
  ];

  return (
    <div className="space-y-6">
      <PageHeader
        title="贸易单证"
        description="按目的地国家规则生成与校验商业发票、装箱单、原产地证、提单/空运单与认证文件，提交报关行/目的仓"
        actions={
          <>
            <Button variant="ghost" onClick={refreshAll}>
              <RefreshCw className="size-4" />
              刷新
            </Button>
            <Button onClick={() => setCreating(true)}>
              <FilePlus2 className="size-4" />
              新建单证包
            </Button>
          </>
        }
      />

      {/* 口径常驻：这不是页脚备注，而是本模块最容易读错的三件事 */}
      <SectionCard title="备证口径">
        <ul className="text-muted-foreground space-y-1 text-sm">
          <li>
            <strong className="text-foreground">「实质性改变」系统不下结论</strong>
            ：FTA 优惠原产地证能显著降低关税，但适用条件（尤其是「实质性改变」）需要业务确认。
            本系统只负责按规则校验与留档 —— 主张优惠的单证会停在「待业务确认」，
            由业务留痕确认或撤回后才能提交。
          </li>
          <li>
            <strong className="text-foreground">「待业务确认」不是「校验通过」</strong>
            ：完整性校验的结论分三档，单证齐备但优惠未确认时结论是「待业务确认」， 不计入通过 ——
            混进去界面就会许诺一个还没人拍板的结论。
          </li>
          <li>
            <strong className="text-foreground">目的国要求是数据不是常量</strong>
            ：单证要求、单证模板与优惠规则都真实落库、租户可维护。 该目的国一条规则都没配时校验会
            <strong>报不通过</strong>并说清原因 —— 没有定义要求就无法断言齐不齐。
          </li>
          {caps && (
            <>
              <li>{caps.substantiveChangeNote}</li>
              <li>{caps.submitGateNote}</li>
              <li>{caps.ruleDataNote}</li>
              <li>{caps.certificationSourceNote}</li>
            </>
          )}
          {unavailable.map((d) => (
            <li key={d.value}>
              <strong className="text-foreground">
                {d.label}（归属 {d.ownerModule}）
              </strong>
              ：{d.note}
            </li>
          ))}
        </ul>
      </SectionCard>

      {summary && (
        <div className="grid gap-4 md:grid-cols-4">
          <StatCard
            label="单证包"
            value={String(summary.total)}
            comparisonLabel={`草稿 ${summary.draft} · 待复核 ${summary.underReview} · 已复核 ${summary.approved}`}
          />
          <StatCard
            label="已提交"
            value={String(summary.submitted)}
            comparisonLabel="提交痕迹不会消失：被退回后仍留着"
          />
          <StatCard
            label="有阻断"
            value={String(summary.blocked)}
            comparisonLabel={`缺件或待业务确认 · 待业务确认 ${summary.pendingBusiness}`}
          />
          <StatCard
            label="申报货值合计"
            value={money(summary.totalDeclaredValue, summary.documentCurrency)}
            comparisonLabel="同一币种合计，不随分页变化"
          />
        </div>
      )}

      <div className="flex flex-wrap gap-2">
        {tabs.map((t) => (
          <Button
            key={t.value}
            variant={tab === t.value ? 'default' : 'ghost'}
            onClick={() => setTab(t.value)}
          >
            {t.label}
            {t.value === 'reminders' && (reminders?.summary.openBlockers ?? 0) > 0
              ? `（阻断 ${reminders?.summary.openBlockers}）`
              : ''}
          </Button>
        ))}
      </div>

      {error && (
        <SectionCard title="操作失败">
          <div className="flex items-start gap-2">
            <TriangleAlert className="text-destructive mt-0.5 size-5" />
            <p className="text-destructive text-sm">{error}</p>
          </div>
        </SectionCard>
      )}

      {tab === 'sets' && (
        <>
          <SectionCard title="筛选">
            <div className="grid gap-3 md:grid-cols-4">
              <label className="flex flex-col gap-1 text-sm">
                <span className="text-muted-foreground">关键字</span>
                <input
                  className="rounded-md border px-3 py-2"
                  placeholder="单证包号 / 报关单号 / 出口商 / 收货人"
                  defaultValue={params.keyword ?? ''}
                  onBlur={(e) => setParams((p) => patchParam(p, { keyword: e.target.value }))}
                />
              </label>
              <label className="flex flex-col gap-1 text-sm">
                <span className="text-muted-foreground">状态</span>
                <select
                  className="rounded-md border px-3 py-2"
                  value={params.status?.[0] ?? ''}
                  onChange={(e) =>
                    setParams((p) => {
                      const next: TradeSetParams = { ...p, page: 0 };
                      if (e.target.value) next.status = [e.target.value];
                      else delete next.status;
                      return next;
                    })
                  }
                >
                  <option value="">全部状态</option>
                  {(data?.statuses ?? caps?.statusOptions ?? []).map((o) => (
                    <option key={o.value} value={o.value}>
                      {o.label}
                    </option>
                  ))}
                </select>
              </label>
              <label className="flex flex-col gap-1 text-sm">
                <span className="text-muted-foreground">目的国</span>
                <input
                  className="rounded-md border px-3 py-2"
                  placeholder="两位国家代码，如 US"
                  defaultValue={params.destinationCountryCode ?? ''}
                  onBlur={(e) =>
                    setParams((p) => patchParam(p, { destinationCountryCode: e.target.value }))
                  }
                />
              </label>
              <label className="flex flex-col gap-1 text-sm">
                <span className="text-muted-foreground">校验结论</span>
                <select
                  className="rounded-md border px-3 py-2"
                  value={params.latestCheckResult ?? ''}
                  onChange={(e) =>
                    setParams((p) => patchParam(p, { latestCheckResult: e.target.value }))
                  }
                >
                  <option value="">全部</option>
                  {(data?.checkResults ?? caps?.checkResults ?? []).map((o) => (
                    <option key={o.value} value={o.value}>
                      {o.label}
                    </option>
                  ))}
                </select>
              </label>
              <label className="flex flex-col gap-1 text-sm">
                <span className="text-muted-foreground">只看有阻断的</span>
                <select
                  className="rounded-md border px-3 py-2"
                  value={params.blockedOnly ? 'true' : ''}
                  onChange={(e) =>
                    setParams((p) => patchParam(p, { blockedOnly: e.target.value === 'true' }))
                  }
                >
                  <option value="">全部</option>
                  <option value="true">有阻断（缺件或待业务确认）</option>
                </select>
              </label>
              <label className="flex flex-col gap-1 text-sm">
                <span className="text-muted-foreground">排序</span>
                <select
                  className="rounded-md border px-3 py-2"
                  value={params.sort ?? 'UPDATED_AT'}
                  onChange={(e) => setParams((p) => patchParam(p, { sort: e.target.value }))}
                >
                  {tradeSetSortOptions.map((o) => (
                    <option key={o.value} value={o.value}>
                      {o.label}
                    </option>
                  ))}
                </select>
              </label>
            </div>
          </SectionCard>

          {loading && !data ? (
            <SectionCard title="单证包列表">
              <p className="text-muted-foreground text-sm">正在加载单证包…</p>
            </SectionCard>
          ) : (data?.rows.length ?? 0) === 0 ? (
            <SectionCard title="单证包列表">
              <EmptyState
                icon={<FilePlus2 className="size-8" />}
                title="还没有单证包"
                description="贸易单证按 10.5.1.1 报关单备证：先有一张已复核的报关单，才能据它建单证包。"
                action={
                  <Button onClick={() => setCreating(true)}>
                    <FilePlus2 className="size-4" />
                    新建单证包
                  </Button>
                }
              />
            </SectionCard>
          ) : (
            <SectionCard
              title="单证包列表"
              description={`共 ${data?.total ?? 0} 个，合计不受分页影响`}
              extra={
                <Pagination
                  page={data?.page ?? 0}
                  size={data?.size ?? 20}
                  total={data?.total ?? 0}
                  onChange={(p) => setParams((prev) => ({ ...prev, page: p }))}
                />
              }
            >
              <table className="w-full text-sm">
                <thead className="text-muted-foreground border-b text-left">
                  <tr>
                    <th className="px-4 py-3 font-medium">单证包号</th>
                    <th className="px-4 py-3 font-medium">来源报关单</th>
                    <th className="px-4 py-3 font-medium">出口商 → 收货人</th>
                    <th className="px-4 py-3 font-medium">目的国</th>
                    <th className="px-4 py-3 font-medium">齐备度</th>
                    <th className="px-4 py-3 font-medium">校验结论</th>
                    <th className="px-4 py-3 font-medium">提交于</th>
                    <th className="px-4 py-3 font-medium">状态</th>
                    <th className="px-4 py-3 font-medium"></th>
                  </tr>
                </thead>
                <tbody>
                  {(data?.rows ?? []).map((row) => (
                    <SetRow key={row.id} row={row} onOpen={() => setOpenId(row.id)} />
                  ))}
                </tbody>
              </table>
            </SectionCard>
          )}
        </>
      )}

      {tab === 'rules' && (
        <RulesTab
          rules={rules}
          params={ruleParams}
          form={ruleForm}
          kinds={caps?.documentKinds ?? []}
          onPatch={(patch) => setRuleParams((p) => patchRule(p, patch))}
          onForm={(patch) => setRuleForm((f) => ({ ...f, ...patch }))}
          onCreate={async () => {
            setError(null);
            try {
              await createTradeRule({
                countryCode: ruleForm.countryCode,
                documentKind: ruleForm.documentKind,
                requirementLevel: ruleForm.requirementLevel,
                conditionExpression: ruleForm.conditionExpression,
                requiresCertification: ruleForm.requiresCertification,
                ruleSource: 'TENANT_MAINTAINED',
                sourceReference: '界面维护',
                validFrom: today(),
                validTo: null,
                notes: '',
              });
              setRuleForm((f) => ({ ...f, countryCode: '', conditionExpression: '' }));
              await loadRules();
            } catch (e) {
              setError(errorText(e));
            }
          }}
        />
      )}

      {tab === 'certifications' && (
        <CertificationsTab
          certs={certs}
          params={certParams}
          form={certForm}
          onPatch={(patch) => setCertParams((prev) => ({ ...prev, ...patch, page: 0 }))}
          onForm={(patch) => setCertForm((f) => ({ ...f, ...patch }))}
          onCreate={async () => {
            setError(null);
            try {
              await createTradeCertification({
                skuId: certForm.skuId.trim(),
                certificationCode: certForm.certificationCode.trim(),
                certificationName: certForm.certificationName.trim(),
                status: certForm.status,
                certificateNo: certForm.certificateNo.trim() || null,
                issuingBody: certForm.issuingBody.trim() || null,
                issuedOn: certForm.issuedOn.trim() || null,
                expiresOn: certForm.expiresOn.trim() || null,
                documentRef: '',
                // 16 合规还没交付，所以这里**只能**声明为租户自维护，不冒充从 16 合规读来的。
                certificationSource: 'TENANT_MAINTAINED',
                sourceReference: '界面维护',
                notes: '',
              });
              setCertForm((f) => ({
                ...f,
                skuId: '',
                certificationCode: '',
                certificationName: '',
                certificateNo: '',
                issuingBody: '',
                issuedOn: '',
                expiresOn: '',
              }));
              await loadCerts();
            } catch (e) {
              setError(errorText(e));
            }
          }}
        />
      )}

      {tab === 'preferences' && (
        <PreferencesTab
          rules={prefRules}
          confirmations={confirmations}
          form={prefForm}
          onForm={(patch) => setPrefForm((f) => ({ ...f, ...patch }))}
          onCreate={async () => {
            setError(null);
            try {
              await createTradePreferenceRule({
                scheme: prefForm.scheme,
                destinationCountryCode: prefForm.destinationCountryCode,
                productScope: 'ALL',
                hsPrefix: '',
                preferenceCriteria: prefForm.preferenceCriteria,
                substantiveChangeCriterion: prefForm.substantiveChangeCriterion,
                requiresBusinessConfirmation: true,
                validFrom: today(),
                validTo: null,
                status: 'ACTIVE',
                ruleSource: 'TENANT_MAINTAINED',
                sourceReference: '界面维护',
                notes: '',
              });
              setPrefForm((f) => ({
                ...f,
                destinationCountryCode: '',
                substantiveChangeCriterion: '',
                preferenceCriteria: '',
              }));
              await loadPreferences();
            } catch (e) {
              setError(errorText(e));
            }
          }}
        />
      )}

      {tab === 'reminders' && (
        <RemindersTab
          reminders={reminders}
          onRefresh={loadReminders}
          onResolve={async (id, status, note) => {
            setError(null);
            try {
              await resolveTradeReminder(id, { status, resolutionNote: note });
              await onChanged();
            } catch (e) {
              setError(errorText(e));
            }
          }}
        />
      )}

      {openId && (
        <SetDetailModal
          setId={openId}
          prefRules={prefRules}
          onClose={() => setOpenId(null)}
          onChanged={onChanged}
          onError={setError}
        />
      )}

      {creating && (
        <CreateSetModal
          legalEntities={legalEntities}
          onClose={() => setCreating(false)}
          onCreated={async (id) => {
            setCreating(false);
            await load();
            setOpenId(id);
          }}
          onError={setError}
        />
      )}
    </div>
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
  const pages = Math.max(1, Math.ceil(total / size));
  return (
    <div className="flex items-center gap-2 text-sm">
      <Button variant="ghost" disabled={page <= 0} onClick={() => onChange(page - 1)}>
        上一页
      </Button>
      <span className="text-muted-foreground">
        {page + 1} / {pages}
      </span>
      <Button variant="ghost" disabled={page + 1 >= pages} onClick={() => onChange(page + 1)}>
        下一页
      </Button>
    </div>
  );
}

/** 列表一行。缺件与「待业务确认」分两行显示 —— 它们要处理的事完全不同。 */
function SetRow({ row, onOpen }: { row: TradeSetRow; onOpen: () => void }) {
  const pendingBusiness = (row.pendingBusinessCount ?? 0) > 0;
  const missing = (row.missingDocumentCount ?? 0) > 0;
  return (
    <tr className="hover:bg-muted/50 border-b last:border-0">
      <td className="px-4 py-3 font-medium">{row.setCode}</td>
      <td className="text-muted-foreground px-4 py-3">{row.sourceDeclarationCode || '—'}</td>
      <td className="px-4 py-3">
        <div>{row.exporterName}</div>
        <div className="text-muted-foreground text-xs">
          {row.consigneeName}（{row.consigneeCountryCode}）
        </div>
      </td>
      <td className="px-4 py-3">{row.destinationCountryCode}</td>
      <td className="px-4 py-3 text-xs">
        <div>
          单证 {row.totalDocumentCount ?? 0} / 必备 {row.requiredDocumentCount ?? 0}
        </div>
        {missing && <div className="text-destructive">缺 {row.missingDocumentCount} 项</div>}
        {pendingBusiness && (
          <div className="text-destructive">待业务确认 {row.pendingBusinessCount} 项（优惠）</div>
        )}
      </td>
      <td className="px-4 py-3">
        {row.latestCheckResultLabel ||
          checkResultLabels[row.latestCheckResult ?? 'NOT_CHECKED'] ||
          '—'}
      </td>
      {/* 提交痕迹是历史：被退回后仍然留着，因为「发出去过没有」是举证材料 */}
      <td className="text-muted-foreground px-4 py-3 text-xs">{moment(row.submittedAt)}</td>
      <td className="px-4 py-3">
        {row.statusLabel || tradeSetStatusLabels[row.status] || row.status}
      </td>
      <td className="px-4 py-3 text-right">
        <Button variant="ghost" onClick={onOpen}>
          详情
        </Button>
      </td>
    </tr>
  );
}

/** 目的国单证要求：租户自己维护的真实数据，所以给的是维护界面而不是只读视图。 */
function RulesTab({
  rules,
  params,
  form,
  kinds,
  onPatch,
  onForm,
  onCreate,
}: {
  rules: Awaited<ReturnType<typeof fetchTradeRules>> | null;
  params: TradeRuleParams;
  form: {
    countryCode: string;
    documentKind: string;
    requirementLevel: string;
    conditionExpression: string;
    requiresCertification: boolean;
  };
  kinds: { value: string; label: string; originCertificate: boolean }[];
  onPatch: (patch: Partial<TradeRuleParams>) => void;
  onForm: (patch: Partial<typeof form>) => void;
  onCreate: () => void;
}) {
  return (
    <>
      <SectionCard title="新增单证要求">
        <div className="grid gap-3 md:grid-cols-3">
          <label className="flex flex-col gap-1 text-sm">
            <span className="text-muted-foreground">目的国</span>
            <input
              className="rounded-md border px-3 py-2"
              placeholder="US"
              value={form.countryCode}
              onChange={(e) => onForm({ countryCode: e.target.value })}
            />
          </label>
          <label className="flex flex-col gap-1 text-sm">
            <span className="text-muted-foreground">单证类型</span>
            <select
              className="rounded-md border px-3 py-2"
              value={form.documentKind}
              onChange={(e) => onForm({ documentKind: e.target.value })}
            >
              {kinds.map((k) => (
                <option key={k.value} value={k.value}>
                  {k.label}
                </option>
              ))}
            </select>
          </label>
          <label className="flex flex-col gap-1 text-sm">
            <span className="text-muted-foreground">必备程度</span>
            <select
              className="rounded-md border px-3 py-2"
              value={form.requirementLevel}
              onChange={(e) => onForm({ requirementLevel: e.target.value })}
            >
              {Object.entries(requirementLevelLabels).map(([value, label]) => (
                <option key={value} value={value}>
                  {label}
                </option>
              ))}
            </select>
          </label>
          <label className="flex flex-col gap-1 text-sm">
            <span className="text-muted-foreground">适用条件（仅「有条件必备」需填）</span>
            <input
              className="rounded-md border px-3 py-2"
              placeholder="人读口径，系统不执行"
              value={form.conditionExpression}
              onChange={(e) => onForm({ conditionExpression: e.target.value })}
            />
          </label>
          <label className="flex flex-col gap-1 text-sm">
            <span className="text-muted-foreground">要求附认证状态</span>
            <select
              className="rounded-md border px-3 py-2"
              value={form.requiresCertification ? 'true' : ''}
              onChange={(e) => onForm({ requiresCertification: e.target.value === 'true' })}
            >
              <option value="">否</option>
              <option value="true">是</option>
            </select>
          </label>
          <div className="flex items-end">
            <Button onClick={onCreate}>
              <FilePlus2 className="size-4" />
              新增要求
            </Button>
          </div>
        </div>
        <p className="text-muted-foreground mt-3 text-xs">
          「有条件必备」的条件是<strong>给人读的口径</strong>，系统不执行它：
          缺这一类时只报提示级提醒请人判断是否适用，不会自动判成必备或可省。
        </p>
      </SectionCard>

      <SectionCard title="筛选">
        <div className="grid gap-3 md:grid-cols-3">
          <label className="flex flex-col gap-1 text-sm">
            <span className="text-muted-foreground">目的国</span>
            <input
              className="rounded-md border px-3 py-2"
              defaultValue={params.countryCode ?? ''}
              onBlur={(e) => onPatch({ countryCode: e.target.value })}
            />
          </label>
          <label className="flex flex-col gap-1 text-sm">
            <span className="text-muted-foreground">单证类型</span>
            <select
              className="rounded-md border px-3 py-2"
              value={params.documentKind ?? ''}
              onChange={(e) => onPatch({ documentKind: e.target.value })}
            >
              <option value="">全部</option>
              {kinds.map((k) => (
                <option key={k.value} value={k.value}>
                  {k.label}
                </option>
              ))}
            </select>
          </label>
          <label className="flex flex-col gap-1 text-sm">
            <span className="text-muted-foreground">状态</span>
            <select
              className="rounded-md border px-3 py-2"
              value={params.status ?? ''}
              onChange={(e) => onPatch({ status: e.target.value })}
            >
              <option value="">全部</option>
              <option value="ACTIVE">生效中</option>
              <option value="RETIRED">已停用</option>
            </select>
          </label>
        </div>
      </SectionCard>

      {(rules?.rows.length ?? 0) === 0 ? (
        <SectionCard title="单证要求">
          <EmptyState
            icon={<ScrollText className="size-8" />}
            title="这个目的国还没有单证要求"
            description="没有定义要求就无法断言单证齐不齐：该目的国一条规则都没配时，完整性校验会报不通过并说清原因，而不是「没有要求就算通过」。"
          />
        </SectionCard>
      ) : (
        <SectionCard title="单证要求" description={`共 ${rules?.total ?? 0} 条`}>
          <table className="w-full text-sm">
            <thead className="text-muted-foreground border-b text-left">
              <tr>
                <th className="px-4 py-3 font-medium">目的国</th>
                <th className="px-4 py-3 font-medium">单证类型</th>
                <th className="px-4 py-3 font-medium">必备程度</th>
                <th className="px-4 py-3 font-medium">适用条件（人读口径）</th>
                <th className="px-4 py-3 font-medium">要求认证</th>
                <th className="px-4 py-3 font-medium">来源</th>
                <th className="px-4 py-3 font-medium">有效期</th>
              </tr>
            </thead>
            <tbody>
              {(rules?.rows ?? []).map((r) => (
                <tr key={r.id} className="hover:bg-muted/50 border-b last:border-0">
                  <td className="px-4 py-3 font-medium">{r.countryCode}</td>
                  <td className="px-4 py-3">{r.documentKindLabel}</td>
                  <td className="px-4 py-3">{r.requirementLevelLabel}</td>
                  <td className="text-muted-foreground px-4 py-3 text-xs">
                    {r.conditionExpression || '—'}
                  </td>
                  <td className="px-4 py-3">{r.requiresCertification ? '是' : '否'}</td>
                  <td className="text-muted-foreground px-4 py-3 text-xs">
                    {r.ruleSource}
                    {r.sourceReference ? ` · ${r.sourceReference}` : ''}
                  </td>
                  <td className="text-muted-foreground px-4 py-3 text-xs">
                    {r.validFrom} ~ {r.validTo ?? '长期'}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </SectionCard>
      )}
    </>
  );
}

/**
 * 认证状态登记台账。
 *
 * <p>蓝图把「16 合规的认证状态」列为输入，但 `backend/services/` 里没有 product_compliance 服务，
 * 16 合规尚未交付。所以这里**不假装**读到了 16 合规的数据：登记表是本租户自己维护的真实行，
 * 每行都带 `certificationSource`（TENANT_MAINTAINED / MODULE_16）与 `sourceReference` 如实标注来源。
 *
 * <p>这样 16 合规真的交付后，把来源换成 MODULE_16 就能让数据接过去，
 * 而不必把同一份认证状态在本模块里再抄一遍、写成两份会漂移的真相。
 */
function CertificationsTab({
  certs,
  params,
  form,
  onPatch,
  onForm,
  onCreate,
}: {
  certs: CertPage | null;
  params: { skuId?: string; status?: string; page?: number; size?: number };
  form: {
    skuId: string;
    certificationCode: string;
    certificationName: string;
    status: string;
    certificateNo: string;
    issuingBody: string;
    issuedOn: string;
    expiresOn: string;
  };
  onPatch: (patch: Partial<{ skuId: string; status: string; page: number }>) => void;
  onForm: (patch: Partial<typeof form>) => void;
  onCreate: () => void;
}) {
  return (
    <>
      <SectionCard title="登记认证状态">
        <div className="grid gap-3 md:grid-cols-4">
          <label className="flex flex-col gap-1 text-sm">
            <span className="text-muted-foreground">SKU</span>
            <input
              className="rounded-md border px-3 py-2"
              placeholder="货号对应的 SKU id"
              value={form.skuId}
              onChange={(e) => onForm({ skuId: e.target.value })}
            />
          </label>
          <label className="flex flex-col gap-1 text-sm">
            <span className="text-muted-foreground">认证代码</span>
            <input
              className="rounded-md border px-3 py-2"
              placeholder="CE / FCC / FDA…"
              value={form.certificationCode}
              onChange={(e) => onForm({ certificationCode: e.target.value })}
            />
          </label>
          <label className="flex flex-col gap-1 text-sm">
            <span className="text-muted-foreground">认证名称</span>
            <input
              className="rounded-md border px-3 py-2"
              value={form.certificationName}
              onChange={(e) => onForm({ certificationName: e.target.value })}
            />
          </label>
          <label className="flex flex-col gap-1 text-sm">
            <span className="text-muted-foreground">状态</span>
            <select
              className="rounded-md border px-3 py-2"
              value={form.status}
              onChange={(e) => onForm({ status: e.target.value })}
            >
              {Object.entries(certificationStatusLabels).map(([value, label]) => (
                <option key={value} value={value}>
                  {label}
                </option>
              ))}
            </select>
          </label>
          <label className="flex flex-col gap-1 text-sm">
            <span className="text-muted-foreground">证书编号</span>
            <input
              className="rounded-md border px-3 py-2"
              placeholder="「有效」必填"
              value={form.certificateNo}
              onChange={(e) => onForm({ certificateNo: e.target.value })}
            />
          </label>
          <label className="flex flex-col gap-1 text-sm">
            <span className="text-muted-foreground">发证机构</span>
            <input
              className="rounded-md border px-3 py-2"
              value={form.issuingBody}
              onChange={(e) => onForm({ issuingBody: e.target.value })}
            />
          </label>
          <label className="flex flex-col gap-1 text-sm">
            <span className="text-muted-foreground">取证日期</span>
            <input
              type="date"
              className="rounded-md border px-3 py-2"
              value={form.issuedOn}
              onChange={(e) => onForm({ issuedOn: e.target.value })}
            />
          </label>
          <label className="flex flex-col gap-1 text-sm">
            <span className="text-muted-foreground">有效期至</span>
            <input
              type="date"
              className="rounded-md border px-3 py-2"
              value={form.expiresOn}
              onChange={(e) => onForm({ expiresOn: e.target.value })}
            />
          </label>
          <div className="flex items-end">
            <Button onClick={onCreate}>
              <FilePlus2 className="size-4" />
              登记
            </Button>
          </div>
        </div>
        <p className="text-muted-foreground mt-3 text-xs">
          来源会记成<strong>「租户自维护」</strong> —— 16 合规模块交付前，这里不冒充从 16
          合规读来的数据。
          单证要求里勾了「要求附认证状态」的，校验会看这张表：缺失或已过期会报阻断/提示级提醒。
        </p>
      </SectionCard>

      <SectionCard title="筛选">
        <div className="grid gap-3 md:grid-cols-3">
          <label className="flex flex-col gap-1 text-sm">
            <span className="text-muted-foreground">SKU</span>
            <input
              className="rounded-md border px-3 py-2"
              defaultValue={params.skuId ?? ''}
              onBlur={(e) => onPatch({ skuId: e.target.value })}
            />
          </label>
          <label className="flex flex-col gap-1 text-sm">
            <span className="text-muted-foreground">状态</span>
            <select
              className="rounded-md border px-3 py-2"
              value={params.status ?? ''}
              onChange={(e) => onPatch({ status: e.target.value })}
            >
              <option value="">全部</option>
              {Object.entries(certificationStatusLabels).map(([value, label]) => (
                <option key={value} value={value}>
                  {label}
                </option>
              ))}
            </select>
          </label>
        </div>
      </SectionCard>

      <SectionCard
        title="认证状态"
        description={`共 ${certs?.total ?? 0} 条`}
        extra={
          <Pagination
            page={certs?.page ?? 0}
            size={certs?.size ?? 20}
            total={certs?.total ?? 0}
            onChange={(p) => onPatch({ page: p })}
          />
        }
      >
        {(certs?.rows.length ?? 0) === 0 ? (
          <EmptyState
            icon={<ScrollText className="size-8" />}
            title="还没有登记认证状态"
            description="单证要求里勾了「要求附认证状态」的目的国，没有登记记录时校验会报缺失提醒 —— 这是有意的：没有登记不等于不用交。"
          />
        ) : (
          <table className="w-full text-sm">
            <thead className="text-muted-foreground border-b text-left">
              <tr>
                <th className="px-4 py-3 font-medium">SKU</th>
                <th className="px-4 py-3 font-medium">认证</th>
                <th className="px-4 py-3 font-medium">状态</th>
                <th className="px-4 py-3 font-medium">证书编号</th>
                <th className="px-4 py-3 font-medium">有效期</th>
                <th className="px-4 py-3 font-medium">来源</th>
              </tr>
            </thead>
            <tbody>
              {(certs?.rows ?? []).map((r) => (
                <tr key={r.id} className="hover:bg-muted/50 border-b last:border-0">
                  <td className="px-4 py-3 font-medium">
                    {r.skuCode || r.skuId}
                    {r.skuName ? (
                      <span className="text-muted-foreground block text-xs">{r.skuName}</span>
                    ) : null}
                  </td>
                  <td className="px-4 py-3">
                    {r.certificationName || r.certificationCode}
                    {r.certificationName ? (
                      <span className="text-muted-foreground block text-xs">
                        {r.certificationCode}
                      </span>
                    ) : null}
                  </td>
                  <td className="px-4 py-3">
                    {r.statusLabel || certificationStatusLabels[r.status] || r.status}
                  </td>
                  <td className="px-4 py-3">{r.certificateNo || '—'}</td>
                  <td className="text-muted-foreground px-4 py-3 text-xs">
                    {r.expiresOn ? `至 ${r.expiresOn}` : '长期'}
                  </td>
                  <td className="text-muted-foreground px-4 py-3 text-xs">
                    {r.certificationSource === 'MODULE_16' ? '16 合规' : '租户自维护'}
                    {r.sourceReference ? ` · ${r.sourceReference}` : ''}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </SectionCard>
    </>
  );
}

/**
 * FTA 优惠规则与优惠主张台账。
 *
 * <p>刻意把「规则配置」与「谁主张了优惠」放在同一个页签：前者是配置、后者是留痕，
 * 而业务要判断的永远是「这份优惠按哪条规则、谁确认的、什么时候确认的」。
 */
function PreferencesTab({
  rules,
  confirmations,
  form,
  onForm,
  onCreate,
}: {
  rules: PrefRules | null;
  confirmations: Confirmations | null;
  form: {
    scheme: string;
    destinationCountryCode: string;
    preferenceCriteria: string;
    substantiveChangeCriterion: string;
  };
  onForm: (patch: Partial<typeof form>) => void;
  onCreate: () => void;
}) {
  const pending = (confirmations?.rows ?? []).filter((c) =>
    substantiveChangeBlocking(c.substantiveChangeStatus),
  );
  return (
    <>
      <SectionCard title="新增优惠规则">
        <div className="grid gap-3 md:grid-cols-4">
          <label className="flex flex-col gap-1 text-sm">
            <span className="text-muted-foreground">优惠方案</span>
            <input
              className="rounded-md border px-3 py-2"
              value={form.scheme}
              onChange={(e) => onForm({ scheme: e.target.value })}
            />
          </label>
          <label className="flex flex-col gap-1 text-sm">
            <span className="text-muted-foreground">适用目的国</span>
            <input
              className="rounded-md border px-3 py-2"
              placeholder="US"
              value={form.destinationCountryCode}
              onChange={(e) => onForm({ destinationCountryCode: e.target.value })}
            />
          </label>
          <label className="flex flex-col gap-1 text-sm">
            <span className="text-muted-foreground">优惠适用条件口径</span>
            <input
              className="rounded-md border px-3 py-2"
              value={form.preferenceCriteria}
              onChange={(e) => onForm({ preferenceCriteria: e.target.value })}
            />
          </label>
          <label className="flex flex-col gap-1 text-sm">
            <span className="text-muted-foreground">「实质性改变」适用条件口径</span>
            <input
              className="rounded-md border px-3 py-2"
              placeholder="业务确认时要依据的东西，不能为空"
              value={form.substantiveChangeCriterion}
              onChange={(e) => onForm({ substantiveChangeCriterion: e.target.value })}
            />
          </label>
          <div className="flex items-end">
            <Button onClick={onCreate}>
              <FilePlus2 className="size-4" />
              新增优惠规则
            </Button>
          </div>
        </div>
        <p className="text-muted-foreground mt-3 text-xs">
          这两条是<strong>条件描述</strong>，不是判定式。系统不执行也不推断「实质性改变」是否成立 ——
          优惠主张默认停在「待业务确认」，由业务留痕确认或撤回。
        </p>
      </SectionCard>

      {pending.length > 0 && (
        <SectionCard title={`待业务确认的优惠主张（${pending.length}）`}>
          <div className="flex items-start gap-2">
            <TriangleAlert className="text-destructive mt-0.5 size-5" />
            <p className="text-sm">
              以下单证主张了 FTA 优惠但还没有业务确认痕迹，它们在确认之前提交不出去 ——
              这是刻意设计的闸门，不是缺陷。
            </p>
          </div>
        </SectionCard>
      )}

      <SectionCard title="优惠规则" description={`共 ${rules?.total ?? 0} 条`}>
        <table className="w-full text-sm">
          <thead className="text-muted-foreground border-b text-left">
            <tr>
              <th className="px-4 py-3 font-medium">方案</th>
              <th className="px-4 py-3 font-medium">目的国</th>
              <th className="px-4 py-3 font-medium">适用范围</th>
              <th className="px-4 py-3 font-medium">「实质性改变」条件口径</th>
              <th className="px-4 py-3 font-medium">要求业务确认</th>
              <th className="px-4 py-3 font-medium">有效期</th>
            </tr>
          </thead>
          <tbody>
            {(rules?.rows ?? []).map((r) => (
              <tr key={r.id} className="hover:bg-muted/50 border-b last:border-0">
                <td className="px-4 py-3 font-medium">{r.scheme}</td>
                <td className="px-4 py-3">{r.destinationCountryCode}</td>
                <td className="px-4 py-3 text-xs">
                  {r.productScope === 'ALL' ? '全部产品' : `HS 前缀 ${r.hsPrefix}`}
                </td>
                <td className="text-muted-foreground px-4 py-3 text-xs">
                  {r.substantiveChangeCriterion || '—'}
                </td>
                <td className="px-4 py-3">
                  {r.requiresBusinessConfirmation ? '是' : '否（已显式关闭）'}
                </td>
                <td className="text-muted-foreground px-4 py-3 text-xs">
                  {r.validFrom} ~ {r.validTo ?? '长期'}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </SectionCard>

      <SectionCard
        title="优惠主张台账"
        description="谁主张了优惠、确认到哪一步、按哪一版规则确认的"
      >
        <table className="w-full text-sm">
          <thead className="text-muted-foreground border-b text-left">
            <tr>
              <th className="px-4 py-3 font-medium">单证</th>
              <th className="px-4 py-3 font-medium">方案</th>
              <th className="px-4 py-3 font-medium">原产国</th>
              <th className="px-4 py-3 font-medium">确认状态</th>
              <th className="px-4 py-3 font-medium">确认依据（人写的，不是系统算的）</th>
              <th className="px-4 py-3 font-medium">规则版本</th>
            </tr>
          </thead>
          <tbody>
            {(confirmations?.rows ?? []).map((c) => (
              <tr key={c.documentId} className="hover:bg-muted/50 border-b last:border-0">
                <td className="px-4 py-3">
                  {c.documentKindLabel}
                  <div className="text-muted-foreground text-xs">{c.documentNo}</div>
                </td>
                <td className="px-4 py-3">{c.ftaScheme || '—'}</td>
                <td className="px-4 py-3">{c.originCountryCode || '—'}</td>
                <td
                  className={
                    substantiveChangeBlocking(c.substantiveChangeStatus)
                      ? 'text-destructive px-4 py-3'
                      : 'px-4 py-3'
                  }
                >
                  {c.substantiveChangeStatusLabel ||
                    substantiveChangeLabels[c.substantiveChangeStatus] ||
                    c.substantiveChangeStatus}
                </td>
                <td className="text-muted-foreground px-4 py-3 text-xs">
                  {c.substantiveChangeNote || '—'}
                </td>
                <td className="text-muted-foreground px-4 py-3 text-xs">
                  {c.preferenceRuleVersion ?? '—'}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </SectionCard>
    </>
  );
}

/** 缺失提醒工作台：阻断级与提示级分开显示，因为处理方式完全不同。 */
function RemindersTab({
  reminders,
  onRefresh,
  onResolve,
}: {
  reminders: ReminderPage | null;
  onRefresh: () => void;
  onResolve: (id: string, status: string, note: string) => void;
}) {
  const rows = reminders?.rows ?? [];
  const blockers = rows.filter((r) => r.severity === 'BLOCKER');
  const warnings = rows.filter((r) => r.severity === 'WARNING');
  return (
    <SectionCard
      title="缺失提醒"
      description={`未处理：阻断 ${reminders?.summary.openBlockers ?? 0} · 提示 ${reminders?.summary.openWarnings ?? 0}`}
      extra={
        <Button variant="ghost" onClick={onRefresh}>
          <RefreshCw className="size-4" />
          刷新
        </Button>
      }
    >
      {rows.length === 0 ? (
        <EmptyState
          icon={<ShieldCheck className="size-8" />}
          title="没有未处理的缺失提醒"
          description="每次跑完整性校验都会重算：上一轮报过、这一轮不再报的提醒会自动结清并留痕，所以这里不会攒着一堆历史阻断。"
        />
      ) : (
        <div className="space-y-4">
          <ReminderGroup
            title={`阻断级（${blockers.length}）—— 不处理就提交不出去`}
            rows={blockers}
            onResolve={onResolve}
          />
          <ReminderGroup
            title={`提示级（${warnings.length}）`}
            rows={warnings}
            onResolve={onResolve}
          />
        </div>
      )}
    </SectionCard>
  );
}

function ReminderGroup({
  title,
  rows,
  onResolve,
}: {
  title: string;
  rows: TradeReminderRow[];
  onResolve: (id: string, status: string, note: string) => void;
}) {
  if (rows.length === 0) return null;
  return (
    <div>
      <h3 className="text-sm font-medium">{title}</h3>
      <table className="mt-2 w-full text-sm">
        <thead className="text-muted-foreground border-b text-left">
          <tr>
            <th className="px-4 py-3 font-medium">单证类型</th>
            <th className="px-4 py-3 font-medium">提醒</th>
            <th className="px-4 py-3 font-medium">说明</th>
            <th className="px-4 py-3 font-medium">发现于</th>
            <th className="px-4 py-3 font-medium">处理</th>
          </tr>
        </thead>
        <tbody>
          {rows.map((r) => (
            <tr key={r.id} className="hover:bg-muted/50 border-b last:border-0">
              <td className="px-4 py-3">
                {r.documentKindLabel || tradeDocumentKindLabels[r.documentKind] || r.documentKind}
              </td>
              <td className="px-4 py-3">
                {r.reminderTypeLabel || reminderTypeLabels[r.reminderType] || r.reminderType}
              </td>
              <td className="text-muted-foreground px-4 py-3 text-xs">{r.message}</td>
              <td className="text-muted-foreground px-4 py-3 text-xs">{moment(r.createdAt)}</td>
              <td className="px-4 py-3">
                <ResolveButton reminder={r} onResolve={onResolve} />
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

function ResolveButton({
  reminder,
  onResolve,
}: {
  reminder: TradeReminderRow;
  onResolve: (id: string, status: string, note: string) => void;
}) {
  const [open, setOpen] = useState(false);
  const [note, setNote] = useState('');
  return (
    <>
      <Button variant="ghost" onClick={() => setOpen(true)}>
        处理
      </Button>
      {open && (
        <Modal open={open} onOpenChange={setOpen}>
          <ModalContent>
            <ModalHeader>
              <ModalTitle>处理缺失提醒</ModalTitle>
              <ModalDescription>
                {reminder.reminderTypeLabel || reminderTypeLabels[reminder.reminderType]}：
                {reminder.message}
              </ModalDescription>
            </ModalHeader>
            <div className="space-y-2">
              <p className="text-muted-foreground text-xs">
                处理必须写说明：静默消失的提醒等于没提醒过。真正把问题解决掉之后，
                重跑一次完整性校验会把不再报的提醒自动结清。
              </p>
              <textarea
                className="w-full rounded-md border px-3 py-2 text-sm"
                rows={3}
                value={note}
                onChange={(e) => setNote(e.target.value)}
                placeholder="怎么处理的"
              />
            </div>
            <ModalFooter>
              <Button
                disabled={note.trim() === ''}
                onClick={() => {
                  onResolve(reminder.id, 'RESOLVED', note);
                  setOpen(false);
                  setNote('');
                }}
              >
                标记已处理
              </Button>
              <Button
                variant="ghost"
                disabled={note.trim() === ''}
                onClick={() => {
                  onResolve(reminder.id, 'DISMISSED', note);
                  setOpen(false);
                  setNote('');
                }}
              >
                忽略
              </Button>
            </ModalFooter>
          </ModalContent>
        </Modal>
      )}
    </>
  );
}

function CreateSetModal({
  legalEntities,
  onClose,
  onCreated,
  onError,
}: {
  legalEntities: LegalEntityOption[];
  onClose: () => void;
  onCreated: (id: string) => void;
  onError: (message: string) => void;
}) {
  const [declarationId, setDeclarationId] = useState('');
  const [legalEntityId, setLegalEntityId] = useState('');
  const [notes, setNotes] = useState('');
  const [busy, setBusy] = useState(false);
  return (
    <Modal open onOpenChange={onClose}>
      <ModalContent>
        <ModalHeader>
          <ModalTitle>新建单证包</ModalTitle>
          <ModalDescription>
            来源固定是 10.5.1.1 报关单：备证资料要基于**已复核**的申报资料。
            收发货人、币种与金额会从报关单固化到单证包上。
          </ModalDescription>
        </ModalHeader>
        <div className="space-y-3">
          <label className="flex flex-col gap-1 text-sm">
            <span className="text-muted-foreground">报关单 id</span>
            <input
              className="rounded-md border px-3 py-2"
              value={declarationId}
              onChange={(e) => setDeclarationId(e.target.value)}
            />
          </label>
          <label className="flex flex-col gap-1 text-sm">
            <span className="text-muted-foreground">法人（留空则沿用报关单上的）</span>
            <select
              className="rounded-md border px-3 py-2"
              value={legalEntityId}
              onChange={(e) => setLegalEntityId(e.target.value)}
            >
              <option value="">沿用报关单</option>
              {legalEntities.map((l) => (
                <option key={l.id} value={l.id}>
                  {l.code} {l.name}
                </option>
              ))}
            </select>
          </label>
          <label className="flex flex-col gap-1 text-sm">
            <span className="text-muted-foreground">备注</span>
            <input
              className="rounded-md border px-3 py-2"
              value={notes}
              onChange={(e) => setNotes(e.target.value)}
            />
          </label>
        </div>
        <ModalFooter>
          <Button
            disabled={busy || declarationId.trim() === ''}
            onClick={async () => {
              setBusy(true);
              try {
                const saved = await createTradeSet({
                  sourceDeclarationId: declarationId.trim(),
                  legalEntityId: legalEntityId || null,
                  notes,
                });
                onCreated(saved.id);
              } catch (e) {
                onError(errorText(e));
                setBusy(false);
              }
            }}
          >
            创建
          </Button>
        </ModalFooter>
      </ModalContent>
    </Modal>
  );
}

interface ClaimDraft {
  ruleId: string;
  note: string;
}

function SetDetailModal({
  setId,
  prefRules,
  onClose,
  onChanged,
  onError,
}: {
  setId: string;
  prefRules: PrefRules | null;
  onClose: () => void;
  onChanged: () => void;
  onError: (message: string) => void;
}) {
  const [detail, setDetail] = useState<Detail | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [newKind, setNewKind] = useState('COMMERCIAL_INVOICE');
  const [defaultRef, setDefaultRef] = useState('');
  const [claimDrafts, setClaimDrafts] = useState<Record<string, ClaimDraft>>({});

  const reload = useCallback(async () => {
    setError(null);
    try {
      setDetail(await fetchTradeSetDetail(setId));
    } catch (e) {
      setError(errorText(e));
    } finally {
      setLoading(false);
    }
  }, [setId]);

  useEffect(() => {
    void reload();
  }, [reload]);

  const run = useCallback(
    async (work: () => Promise<unknown>) => {
      setBusy(true);
      setError(null);
      try {
        await work();
        await reload();
        onChanged();
      } catch (e) {
        setError(errorText(e));
      } finally {
        setBusy(false);
      }
    },
    [reload, onChanged],
  );

  const blockers = detail ? submitBlockers(detail) : [];
  const drafts = detail ? documentsReady(detail.documents).drafts : [];

  return (
    <Modal open onOpenChange={onClose}>
      <ModalContent className="max-w-4xl">
        <ModalHeader>
          <ModalTitle>单证包详情</ModalTitle>
          <ModalDescription>
            {detail?.set.setCode} · 来源报关单 {detail?.set.sourceDeclarationCode || '—'} ·{' '}
            {detail?.set.statusLabel}
          </ModalDescription>
        </ModalHeader>

        {error && (
          <p className="text-destructive text-sm">
            <TriangleAlert className="mr-1 inline size-4" />
            {error}
          </p>
        )}

        {loading ? (
          <p className="text-muted-foreground text-sm">正在加载…</p>
        ) : !detail ? (
          <p className="text-muted-foreground text-sm">读不到这个单证包。</p>
        ) : (
          <div className="max-h-[70vh] space-y-4 overflow-y-auto">
            <ActionBar set={detail.set} busy={busy} onRun={run} />

            {/* 提交前的阻断清单：缺什么写什么，而不是笼统说「请先完善」 */}
            {blockers.length > 0 && (
              <div className="border-destructive/40 bg-destructive/5 rounded-md border p-3">
                <p className="text-destructive text-sm font-medium">
                  现在提交不出去，阻断项 {blockers.length} 条：
                </p>
                <ul className="text-muted-foreground mt-1 list-disc pl-5 text-xs">
                  {blockers.map((b) => (
                    <li key={b}>{b}</li>
                  ))}
                </ul>
              </div>
            )}

            <div className="grid gap-3 md:grid-cols-4">
              <StatCard
                label="单证"
                value={String(detail.set.totalDocumentCount ?? 0)}
                comparisonLabel={`必备 ${detail.set.requiredDocumentCount ?? 0}`}
              />
              <StatCard
                label="最近校验"
                value={detail.set.latestCheckResultLabel ?? '未校验'}
                comparisonLabel={moment(detail.set.latestCheckAt)}
              />
              <StatCard
                label="待业务确认"
                value={String(detail.set.pendingBusinessCount ?? 0)}
                comparisonLabel="优惠主张等业务留痕"
              />
              <StatCard
                label="复核轮次"
                value={String(detail.set.reviewRound ?? 0)}
                comparisonLabel={moment(detail.set.reviewedAt)}
              />
            </div>

            <div>
              <h3 className="text-sm font-medium">单证（{detail.documents.length}）</h3>
              {drafts.length > 0 && (
                <p className="text-muted-foreground text-xs">
                  还没定稿：{drafts.map((k) => tradeDocumentKindLabels[k] ?? k).join('、')}
                  —— 定稿前的单证不是备证资料。
                </p>
              )}
              <table className="mt-2 w-full text-sm">
                <thead className="text-muted-foreground border-b text-left">
                  <tr>
                    <th className="px-3 py-2 font-medium">类型</th>
                    <th className="px-3 py-2 font-medium">单证号</th>
                    <th className="px-3 py-2 font-medium">版本</th>
                    <th className="px-3 py-2 font-medium">金额</th>
                    <th className="px-3 py-2 font-medium">状态</th>
                    <th className="px-3 py-2 font-medium">优惠主张</th>
                    <th className="px-3 py-2 font-medium">操作</th>
                  </tr>
                </thead>
                <tbody>
                  {detail.documents.map((d) => (
                    <tr key={d.id} className="hover:bg-muted/50 border-b last:border-0">
                      <td className="px-3 py-2">{d.documentKindLabel}</td>
                      <td className="px-3 py-2">{d.documentNo || '—'}</td>
                      <td className="px-3 py-2">v{d.documentVersion}</td>
                      <td className="px-3 py-2">{money(d.totalAmount, d.currency)}</td>
                      <td className="px-3 py-2">
                        {d.status === 'FINAL'
                          ? '已定稿'
                          : d.status === 'SUPERSEDED'
                            ? '已被取代'
                            : '草稿'}
                      </td>
                      <td className="px-3 py-2 text-xs">
                        <PreferenceCell document={d} />
                      </td>
                      <td className="px-3 py-2">
                        <DocumentActions
                          destinationCountryCode={detail.set.destinationCountryCode}
                          document={d}
                          busy={busy}
                          prefRules={prefRules}
                          defaultRef={defaultRef}
                          draft={claimDrafts[d.id]}
                          onDraft={(patch) =>
                            setClaimDrafts((prev) => ({
                              ...prev,
                              [d.id]: { ruleId: '', note: '', ...prev[d.id], ...patch },
                            }))
                          }
                          onFinalize={(ref) =>
                            void run(() =>
                              finalizeTradeDocument(setId, d.id, {
                                documentNo: d.documentNo,
                                sourceReference: ref,
                              }),
                            )
                          }
                          onClaim={(ruleId) =>
                            void run(() =>
                              claimTradePreference(setId, d.id, {
                                ftaScheme: schemeOf(prefRules, ruleId),
                                originCountryCode: 'CN',
                                preferenceRuleId: ruleId,
                                substantiveChangeNote: '',
                                confirmed: null,
                              }),
                            )
                          }
                          onConfirm={(confirmed, note) =>
                            void run(() =>
                              confirmTradePreference(setId, d.id, {
                                ftaScheme: d.ftaScheme,
                                originCountryCode: d.originCountryCode,
                                preferenceRuleId: d.preferenceRuleId,
                                substantiveChangeNote: note,
                                confirmed,
                              }),
                            )
                          }
                        />
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>

              <div className="mt-3 flex flex-wrap items-end gap-2">
                <label className="flex flex-col gap-1 text-sm">
                  <span className="text-muted-foreground">生成 / 改版单证</span>
                  <select
                    className="rounded-md border px-3 py-2"
                    value={newKind}
                    onChange={(e) => setNewKind(e.target.value)}
                  >
                    {(detail.documentKinds ?? []).map((k) => (
                      <option key={k.value} value={k.value}>
                        {k.label}
                        {k.originCertificate ? '（可主张优惠）' : ''}
                      </option>
                    ))}
                  </select>
                </label>
                <Button
                  variant="ghost"
                  disabled={busy}
                  onClick={() =>
                    void run(() =>
                      generateTradeDocument(setId, {
                        documentKind: newKind,
                        templateId: null,
                        documentNo: '',
                        currency: detail.set.documentCurrency,
                        quantityTotal: null,
                        packageCount: null,
                        content: {},
                        notes: '',
                      }),
                    )
                  }
                >
                  生成（改版会取代旧版，历史版本保留）
                </Button>
                <label className="flex flex-col gap-1 text-sm">
                  <span className="text-muted-foreground">默认来源定位</span>
                  <input
                    className="rounded-md border px-3 py-2"
                    value={defaultRef}
                    onChange={(e) => setDefaultRef(e.target.value)}
                    placeholder="如 ERP-EXPORT-2026-10"
                  />
                </label>
              </div>
            </div>

            <div>
              <h3 className="text-sm font-medium">缺失提醒（{detail.reminders.length}）</h3>
              <ul className="text-muted-foreground mt-1 list-disc pl-5 text-xs">
                {detail.reminders.map((r) => (
                  <li
                    key={r.id}
                    className={
                      r.status === 'OPEN' && r.severity === 'BLOCKER' ? 'text-destructive' : ''
                    }
                  >
                    [{r.severity === 'BLOCKER' ? '阻断' : '提示'}]{' '}
                    {r.reminderTypeLabel || reminderTypeLabels[r.reminderType]}：{r.message}
                    {r.status !== 'OPEN'
                      ? `（${r.status === 'RESOLVED' ? '已处理' : '已忽略'}：${r.resolutionNote}）`
                      : ''}
                  </li>
                ))}
              </ul>
            </div>

            <div>
              <h3 className="text-sm font-medium">完整性校验历史（append-only 留档）</h3>
              <ul className="text-muted-foreground mt-1 list-disc pl-5 text-xs">
                {detail.checks.map((c) => (
                  <li key={c.id}>
                    第 {c.checkRound} 轮 · {c.resultLabel || c.result} · 命中 {c.ruleCount} 条规则 ·{' '}
                    {moment(c.checkedAt)} —— {c.missingSummary || '齐备'}
                  </li>
                ))}
              </ul>
            </div>

            <div>
              <h3 className="text-sm font-medium">事件时间线</h3>
              <ul className="text-muted-foreground mt-1 list-disc pl-5 text-xs">
                {detail.events.map((e) => (
                  <li key={e.id}>
                    {moment(e.occurredAt)} · {tradeEventLabels[e.kind] ?? e.kind}
                    {e.reason ? ` —— ${e.reason}` : ''}
                    {e.notes ? `（${e.notes}）` : ''}
                  </li>
                ))}
              </ul>
            </div>
          </div>
        )}
      </ModalContent>
    </Modal>
  );
}

function schemeOf(prefRules: PrefRules | null, ruleId: string): string {
  return prefRules?.rows.find((r) => r.id === ruleId)?.scheme ?? '';
}

/** 优惠状态单元格。文案刻意不说「优惠已通过」—— 业务确认 ≠ 系统判定成立。 */
function PreferenceCell({ document }: { document: TradeDocumentRow }) {
  if (!isOriginCertificate(document.documentKind)) {
    return <span className="text-muted-foreground">不适用</span>;
  }
  if (!document.preferenceClaimed) {
    return <span className="text-muted-foreground">未主张</span>;
  }
  return (
    <span
      className={
        substantiveChangeBlocking(document.substantiveChangeStatus) ? 'text-destructive' : ''
      }
    >
      {document.ftaScheme} ·{' '}
      {document.substantiveChangeStatusLabel ??
        substantiveChangeLabels[document.substantiveChangeStatus] ??
        document.substantiveChangeStatus}
    </span>
  );
}

/**
 * 单证级动作。
 *
 * <p>优惠主张与业务确认分成两个入口，而且**只有原产地证**才出现 ——
 * 非原产地证承载优惠主张在业务上就是无效优惠。
 */
function DocumentActions({
  destinationCountryCode,
  document,
  busy,
  prefRules,
  defaultRef,
  draft,
  onDraft,
  onFinalize,
  onClaim,
  onConfirm,
}: {
  destinationCountryCode: string;
  document: TradeDocumentRow;
  busy: boolean;
  prefRules: PrefRules | null;
  defaultRef: string;
  draft: ClaimDraft | undefined;
  onDraft: (patch: Partial<ClaimDraft>) => void;
  onFinalize: (sourceReference: string) => void;
  onClaim: (ruleId: string) => void;
  onConfirm: (confirmed: boolean, note: string) => void;
}) {
  const canClaim = isOriginCertificate(document.documentKind);
  const claimed = document.preferenceClaimed === true;
  const pending = substantiveChangeBlocking(document.substantiveChangeStatus);
  // 优惠主张必须引用一条**本租户真实存在、且适用本单证包目的国**的规则：
  // 下拉直接读后端返回的规则行，不在前端编一份；目的国对不上的不列出来，省得提交时才被 DB 挡。
  const options = (prefRules?.rows ?? []).filter(
    (r) => r.status === 'ACTIVE' && r.destinationCountryCode === destinationCountryCode,
  );
  return (
    <div className="space-y-1">
      {document.status === 'DRAFT' && (
        <Button
          variant="ghost"
          disabled={busy}
          onClick={() => {
            const ref = promptOrNull(
              '定稿需要写清来源定位（单证文件放在哪 / 出自哪个系统）',
              document.sourceReference || defaultRef,
            );
            if (ref) onFinalize(ref);
          }}
        >
          定稿
        </Button>
      )}
      {canClaim && document.status !== 'SUPERSEDED' && !claimed && (
        <div className="space-y-1">
          <select
            className="rounded-md border px-2 py-1 text-xs"
            value={draft?.ruleId ?? ''}
            onChange={(e) => onDraft({ ruleId: e.target.value })}
          >
            <option value="">选一条优惠规则…</option>
            {options.map((r) => (
              <option key={r.id} value={r.id}>
                {r.scheme} · {r.destinationCountryCode}
                {r.productScope === 'ALL' ? '' : ` · HS ${r.hsPrefix}`}
              </option>
            ))}
          </select>
          <Button
            variant="ghost"
            disabled={busy || !draft?.ruleId || options.length === 0}
            onClick={() => draft?.ruleId && onClaim(draft.ruleId)}
          >
            登记优惠主张
          </Button>
          {options.length === 0 && (
            <p className="text-muted-foreground text-xs">
              还没有生效的优惠规则：先在「FTA 优惠与主张台账」里配一条。
            </p>
          )}
        </div>
      )}
      {canClaim && claimed && pending && (
        <div className="space-y-1">
          <input
            className="w-44 rounded-md border px-2 py-1 text-xs"
            placeholder="确认依据（必填，人写的）"
            value={draft?.note ?? ''}
            onChange={(e) => onDraft({ note: e.target.value })}
          />
          <div className="flex gap-1">
            <Button
              variant="ghost"
              disabled={busy || !(draft?.note ?? '').trim()}
              onClick={() => onConfirm(true, (draft?.note ?? '').trim())}
            >
              业务确认
            </Button>
            <Button
              variant="ghost"
              disabled={busy || !(draft?.note ?? '').trim()}
              onClick={() => onConfirm(false, (draft?.note ?? '').trim())}
            >
              撤回优惠
            </Button>
          </div>
        </div>
      )}
      {canClaim && claimed && !pending && (
        <span className="text-muted-foreground text-xs">
          {document.substantiveChangeStatus === 'CONFIRMED'
            ? `已由业务留痕确认（规则版本 ${document.preferenceRuleVersion ?? '—'}）`
            : '已撤回优惠主张'}
        </span>
      )}
    </div>
  );
}

function ActionBar({
  set,
  busy,
  onRun,
}: {
  set: TradeSetRow;
  busy: boolean;
  onRun: (work: () => Promise<unknown>) => void;
}) {
  const actions = tradeSetActions(set.status);
  return (
    <div className="flex flex-wrap gap-2">
      {/* 跑校验是读侧动作，任何非终态都该能做 —— 它不改变状态机，只追加一条留档 */}
      {set.status !== 'ACKNOWLEDGED' && set.status !== 'CANCELLED' && (
        <Button
          variant="ghost"
          disabled={busy}
          onClick={() => void onRun(() => runTradeCheck(set.id))}
        >
          <FileCheck2 className="size-4" />
          跑完整性校验
        </Button>
      )}
      {actions.includes('submitForReview') && (
        <Button
          disabled={busy}
          onClick={() => {
            const note = window.prompt('送复核说明', '') ?? '';
            void onRun(() => submitTradeSetForReview(set.id, { reviewNotes: note }));
          }}
        >
          {tradeActionLabel('submitForReview')}
        </Button>
      )}
      {actions.includes('review') && (
        <Button
          disabled={busy}
          onClick={() => {
            const note = window.prompt('复核意见', '') ?? '';
            void onRun(() => reviewTradeSet(set.id, { reviewNotes: note }));
          }}
        >
          {tradeActionLabel('review')}
        </Button>
      )}
      {actions.includes('rejectReview') && (
        <Button
          variant="ghost"
          disabled={busy}
          onClick={() => {
            const reason = promptOrNull('复核退回原因（必填）');
            if (reason) void onRun(() => rejectTradeSetReview(set.id, { reason }));
          }}
        >
          {tradeActionLabel('rejectReview')}
        </Button>
      )}
      {actions.includes('withdrawReview') && (
        <Button
          variant="ghost"
          disabled={busy}
          onClick={() => void onRun(() => withdrawTradeSetReview(set.id, { notes: '' }))}
        >
          {tradeActionLabel('withdrawReview')}
        </Button>
      )}
      {actions.includes('submit') && (
        <Button
          disabled={busy}
          onClick={() => {
            const to = promptOrNull('提交给谁（报关行 / 目的仓）');
            if (!to) return;
            const ref = window.prompt('提交回执号（可空）', '') ?? '';
            void onRun(() => submitTradeSet(set.id, { submittedTo: to, submissionReference: ref }));
          }}
        >
          {tradeActionLabel('submit')}
        </Button>
      )}
      {actions.includes('acknowledge') && (
        <Button
          variant="ghost"
          disabled={busy}
          onClick={() => {
            const ref = window.prompt('接收回执号', '') ?? '';
            void onRun(() => acknowledgeTradeSet(set.id, { acknowledgementReference: ref }));
          }}
        >
          {tradeActionLabel('acknowledge')}
        </Button>
      )}
      {actions.includes('sendBack') && (
        <Button
          variant="ghost"
          disabled={busy}
          onClick={() => {
            const reason = promptOrNull('退回原因（必填）');
            if (reason) void onRun(() => sendBackTradeSet(set.id, { reason }));
          }}
        >
          {tradeActionLabel('sendBack')}
        </Button>
      )}
      {actions.includes('cancel') && (
        <Button
          variant="ghost"
          disabled={busy}
          onClick={() => {
            const reason = promptOrNull('取消原因（必填）');
            if (reason) void onRun(() => cancelTradeSet(set.id, { reason }));
          }}
        >
          {tradeActionLabel('cancel')}
        </Button>
      )}
    </div>
  );
}
