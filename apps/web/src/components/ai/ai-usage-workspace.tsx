'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import {
  Badge,
  Button,
  EmptyState,
  Input,
  PageHeader,
  SectionCard,
  Segmented,
  StatCard,
} from '@starcon/ui';
import { CircleAlert, Gauge, Loader2, RefreshCw, ShieldCheck, TriangleAlert } from 'lucide-react';
import {
  checkQuota,
  evaluationModeLabel,
  fetchCapabilities,
  fetchEffectivePolicy,
  fetchModels,
  fetchPolicies,
  fetchQuotas,
  fetchRecords,
  fetchSummary,
  formatCount,
  formatInstant,
  formatMoney,
  memberLabel,
  quotaSubjectLabel,
  quotaTone,
  quotaUsageFacts,
  recordUsage,
  saveMemberQuota,
  saveModel,
  savePolicy,
  saveTenantQuota,
  sensitiveActionLabel,
  sensitiveActionTone,
  trimDecimal,
  usageUnitLabel,
  violationFacts,
  violationTone,
  type Tone,
} from './ai-usage-model';
import type {
  AiModelRow,
  AiQuotaEvaluation,
  AiQuotaRow,
  AiSensitivePolicyRow,
  AiSensitivePolicyView,
  AiUsageCapabilities,
  AiUsageRecordPage,
  AiUsageSummary,
} from '../../../../../packages/api-contracts/src/index';

/**
 * 18.4.3.1「使用与配额」工作区（卷 18 前端入口 `/ai/usage`）。
 *
 * 蓝图：「**职责**：管理 AI 使用：按租户与用户的调用量与成本、配额限制、模型选择、
 * 敏感数据处理策略开关。**输入**：调用记录；配额配置。**输出**：用量报表 + 配额执行。
 * **关系**：上游 = 18.4 全部；下游 = 17.6.1 计费。」
 *
 * 🔴 页面上的六条纪律：
 *
 * 1. **边界写在最上面**：敏感数据策略的**执行点是各个 AI 能力自己**（本仓没有全局 AI
 *    拦截点）；配额计数的**权威判定**在记用量那一次的数据库原子递增；下游计费
 *    （17.6.1）**自行取数**，本模块**不写 finance 表**。三句话都摆在最上面，
 *    不藏在折叠里。
 * 2. **金额与用量全程十进制文本**：不转 `number`。不同币种**不求和**。
 * 3. **超限说清五要素**：超了哪一条、当前用量、阈值、周期、什么时候重置。
 * 4. **主体成员不是前端能填的**：用户级配额走路径（`/members/{id}/quotas`），
 *    请求体里**没有** membershipId。
 * 5. **上游读不到 ≠ 没有**：能力声明里 `available=false` 的项按原样显示并写明归谁。
 * 6. **加载 / 空 / 错误三态齐全**：空态区分「筛选无结果」与「还没有数据」。
 */

type Tab = 'summary' | 'records' | 'quotas' | 'models' | 'policies' | 'capabilities';

const PAGE_SIZE = 10;

/** 语气 → Badge 的 tone。 */
const BADGE_TONE: Record<Tone, 'neutral' | 'success' | 'warning' | 'danger'> = {
  ok: 'success',
  warn: 'warning',
  danger: 'danger',
  muted: 'neutral',
};

const PERIODS = [
  { value: 'DAY', label: '日' },
  { value: 'MONTH', label: '月' },
];

const SENSITIVE_ACTIONS = [
  { value: 'BLOCK', label: '禁传' },
  { value: 'MASK', label: '脱敏' },
  { value: 'ALLOW', label: '允许' },
];

const UNITS = [
  { value: 'REQUEST', label: '次' },
  { value: 'TOKEN', label: 'Token' },
  { value: 'THOUSAND_TOKEN', label: '千 Token' },
  { value: 'CHARACTER', label: '字符' },
];

interface ModelForm {
  modelCode: string;
  modelName: string;
  provider: string;
  unit: string;
  unitCost: string;
  currency: string;
  contextLimit: string;
  enabled: boolean;
  note: string;
}

const EMPTY_MODEL: ModelForm = {
  modelCode: '',
  modelName: '',
  provider: '',
  unit: 'REQUEST',
  unitCost: '',
  currency: 'USD',
  contextLimit: '0',
  enabled: true,
  note: '',
};

interface QuotaForm {
  scope: 'TENANT' | 'USER';
  memberId: string;
  capabilityCode: string;
  limitCount: string;
  limitCost: string;
  currency: string;
  period: string;
  enabled: boolean;
  note: string;
}

const EMPTY_QUOTA: QuotaForm = {
  scope: 'TENANT',
  memberId: '',
  capabilityCode: '',
  limitCount: '',
  limitCost: '',
  currency: 'USD',
  period: 'DAY',
  enabled: true,
  note: '',
};

interface PreflightForm {
  capabilityCode: string;
  modelCode: string;
  quantity: string;
}

const EMPTY_PREFLIGHT: PreflightForm = {
  capabilityCode: '',
  modelCode: '',
  quantity: '1',
};

interface PolicyForm {
  capabilityCode: string;
  modelCode: string;
  action: string;
  enabled: boolean;
  note: string;
}

const EMPTY_POLICY: PolicyForm = {
  capabilityCode: '',
  modelCode: '',
  action: 'MASK',
  enabled: true,
  note: '',
};

export function AiUsageWorkspace() {
  const [tab, setTab] = useState<Tab>('summary');

  const [capabilities, setCapabilities] = useState<AiUsageCapabilities | null>(null);
  const [summary, setSummary] = useState<AiUsageSummary | null>(null);
  const [records, setRecords] = useState<AiUsageRecordPage | null>(null);
  const [quotas, setQuotas] = useState<AiQuotaRow[]>([]);
  const [models, setModels] = useState<AiModelRow[]>([]);
  const [policies, setPolicies] = useState<AiSensitivePolicyRow[]>([]);

  const [capabilityFilter, setCapabilityFilter] = useState('');
  const [modelFilter, setModelFilter] = useState('');
  const [pageIndex, setPageIndex] = useState(0);

  const [modelForm, setModelForm] = useState<ModelForm>(EMPTY_MODEL);
  const [quotaForm, setQuotaForm] = useState<QuotaForm>(EMPTY_QUOTA);
  const [policyForm, setPolicyForm] = useState<PolicyForm>(EMPTY_POLICY);
  const [preflightForm, setPreflightForm] = useState<PreflightForm>(EMPTY_PREFLIGHT);
  const [effective, setEffective] = useState<AiSensitivePolicyView | null>(null);
  const [preflight, setPreflight] = useState<AiQuotaEvaluation | null>(null);

  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');

  const fail = (e: unknown) => {
    setError(e instanceof Error ? e.message : '请求失败，请稍后重试');
  };

  const loadAll = useCallback(async () => {
    setLoading(true);
    setError('');
    try {
      const [caps, sum, rec, quo, mod, pol] = await Promise.all([
        fetchCapabilities(),
        fetchSummary({}),
        fetchRecords(0, PAGE_SIZE, {}),
        fetchQuotas(),
        fetchModels({}),
        fetchPolicies({}),
      ]);
      setCapabilities(caps);
      setSummary(sum);
      setRecords(rec);
      setQuotas(quo);
      setModels(mod);
      setPolicies(pol);
    } catch (e) {
      fail(e);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void loadAll();
  }, [loadAll]);

  const reloadRecords = useCallback(async () => {
    try {
      const page = await fetchRecords(pageIndex, PAGE_SIZE, {
        capabilityCode: capabilityFilter || undefined,
        modelCode: modelFilter || undefined,
      });
      setRecords(page);
    } catch (e) {
      fail(e);
    }
  }, [capabilityFilter, modelFilter, pageIndex]);

  useEffect(() => {
    if (tab !== 'records') return;
    void reloadRecords();
  }, [tab, reloadRecords]);

  const totalCostByCurrency = useMemo(
    () => (summary?.byCurrency ?? []).map((row) => `${row.currency} ${trimDecimal(row.cost)}`),
    [summary],
  );

  const submitModel = async () => {
    setBusy(true);
    setError('');
    setNotice('');
    try {
      await saveModel({
        modelCode: modelForm.modelCode,
        modelName: modelForm.modelName,
        provider: modelForm.provider,
        unit: modelForm.unit,
        unitCost: modelForm.unitCost,
        currency: modelForm.currency,
        contextLimit: Number(modelForm.contextLimit || '0'),
        enabled: modelForm.enabled,
        note: modelForm.note,
      });
      setModelForm(EMPTY_MODEL);
      setModels(await fetchModels({}));
      setNotice('模型已登记。改价**不影响历史台账** —— 台账里存的是当时的单价快照。');
    } catch (e) {
      fail(e);
    } finally {
      setBusy(false);
    }
  };

  const submitQuota = async () => {
    setBusy(true);
    setError('');
    setNotice('');
    try {
      const input = {
        capabilityCode: quotaForm.capabilityCode.trim() || '*',
        limitCount: quotaForm.limitCount.trim() === '' ? null : Number(quotaForm.limitCount),
        limitCost: quotaForm.limitCost.trim() === '' ? '' : quotaForm.limitCost.trim(),
        currency: quotaForm.currency.trim() || 'USD',
        period: quotaForm.period,
        enabled: quotaForm.enabled,
        note: quotaForm.note,
      };
      if (quotaForm.scope === 'USER') {
        if (!quotaForm.memberId.trim()) {
          setError('用户级配额必须指定主体成员（它走路径，不是请求体）。');
          return;
        }
        await saveMemberQuota(quotaForm.memberId.trim(), input);
      } else {
        await saveTenantQuota(input);
      }
      setQuotaForm(EMPTY_QUOTA);
      setQuotas(await fetchQuotas());
      setNotice('配额已保存。计数从现在开始走**数据库侧原子递增**。');
    } catch (e) {
      fail(e);
    } finally {
      setBusy(false);
    }
  };

  const submitPolicy = async () => {
    setBusy(true);
    setError('');
    setNotice('');
    try {
      await savePolicy({
        capabilityCode: policyForm.capabilityCode,
        modelCode: policyForm.modelCode,
        action: policyForm.action,
        enabled: policyForm.enabled,
        note: policyForm.note,
      });
      setPolicyForm(EMPTY_POLICY);
      setPolicies(await fetchPolicies({}));
      setNotice('策略已保存。⚠️ 执行点是**各个 AI 能力自己**（本仓没有全局 AI 拦截点）。');
    } catch (e) {
      fail(e);
    } finally {
      setBusy(false);
    }
  };

  const runPreflight = async () => {
    setBusy(true);
    setError('');
    try {
      const result = await checkQuota({
        capabilityCode: preflightForm.capabilityCode.trim() || '*',
        modelCode: preflightForm.modelCode.trim(),
        quantity: preflightForm.quantity.trim(),
        callCount: 1,
      });
      setPreflight(result);
    } catch (e) {
      fail(e);
    } finally {
      setBusy(false);
    }
  };

  const lookupEffective = async () => {
    setBusy(true);
    setError('');
    try {
      setEffective(
        await fetchEffectivePolicy(policyForm.capabilityCode, policyForm.modelCode || undefined),
      );
    } catch (e) {
      fail(e);
    } finally {
      setBusy(false);
    }
  };

  if (loading) {
    return (
      <div className="text-muted-foreground flex items-center gap-2 py-10">
        <Loader2 className="h-4 w-4 animate-spin" />
        正在加载 AI 使用与配额…
      </div>
    );
  }

  return (
    <div className="space-y-4">
      <PageHeader
        title="AI 使用与配额"
        description="卷 18 · 18.4.3.1 使用与配额：按租户与用户的调用量与成本、配额限制、模型选择、敏感数据处理策略开关。"
        actions={
          <Button variant="outline" size="sm" onClick={() => void loadAll()} disabled={busy}>
            <RefreshCw className="h-3.5 w-3.5" />
            刷新
          </Button>
        }
      />

      {/* 🔴 三条边界摆在最上面，不藏在折叠里 */}
      <div className="border-border bg-muted/40 text-copy-13 space-y-1 rounded-lg border p-4">
        <p className="text-foreground flex items-start gap-2">
          <ShieldCheck className="mt-0.5 h-4 w-4 shrink-0" />
          <span>
            <b>敏感数据策略的执行点是各个 AI 能力自己</b>（调用前查
            <code className="text-foreground"> GET /platform/ai/usage/policies/effective</code>）。
            本仓**没有**统一的 AI 出站拦截点 —— 页面不写「已全局拦截」。
          </span>
        </p>
        <p className="text-muted-foreground flex items-start gap-2">
          <Gauge className="mt-0.5 h-4 w-4 shrink-0" />
          <span>
            <b>配额计数的权威判定发生在记用量那一次</b>（数据库侧原子递增）；
            「配额预检」只是**读快照**，不占额度，也不预留。
          </span>
        </p>
        <p className="text-muted-foreground flex items-start gap-2">
          <CircleAlert className="mt-0.5 h-4 w-4 shrink-0" />
          <span>
            <b>下游计费（17.6.1）自行取数</b>：本模块只提供可计费口径的汇总，
            <b>不直连 finance 库、不建跨库外键、不写 finance 表</b>。
          </span>
        </p>
      </div>

      {error && (
        <div className="border-destructive/40 bg-destructive-soft text-destructive text-copy-13 flex items-start gap-2 rounded-lg border p-3">
          <TriangleAlert className="mt-0.5 h-4 w-4 shrink-0" />
          {error}
        </div>
      )}
      {notice && (
        <div className="border-border bg-muted/40 text-foreground text-copy-13 rounded-lg border p-3">
          {notice}
        </div>
      )}

      <Segmented
        value={tab}
        onChange={(value) => setTab(value as Tab)}
        aria-label="AI 使用与配额视图"
        options={[
          { value: 'summary', label: '用量报表' },
          { value: 'records', label: '用量台账' },
          { value: 'quotas', label: '配额' },
          { value: 'models', label: '模型目录' },
          { value: 'policies', label: '敏感策略' },
          { value: 'capabilities', label: '能力声明' },
        ]}
      />

      {/* ================================================================ 用量报表 */}
      {tab === 'summary' && (
        <div className="space-y-4">
          <div className="grid gap-4 md:grid-cols-3">
            <StatCard label="调用次数（窗口内）" value={formatCount(summary?.totalCalls ?? 0)} />
            <StatCard label="涉及币种" value={summary?.byCurrency.length ?? 0} unit="种" />
            <StatCard label="可计费粒度" value={summary?.byCapability.length ?? 0} unit="行" />
          </div>

          <SectionCard
            title="可计费口径：能力 × 模型 × 币种"
            description="不同模型的量纲与不同币种的钱**不合并** —— Token 数与字符数相加、人民币与美元相加，都没有业务含义。"
          >
            {summary?.byCapability.length ? (
              <div className="space-y-2">
                {summary.byCapability.map((row) => (
                  <div
                    key={`${row.capabilityCode}-${row.modelCode}-${row.currency}`}
                    className="border-border text-copy-13 flex flex-wrap items-center justify-between gap-2 rounded-md border p-3"
                  >
                    <div>
                      <div className="text-foreground font-medium">
                        {row.capabilityCode} · {row.modelName || row.modelCode}
                      </div>
                      <div className="text-muted-foreground">
                        量纲 {row.unitLabel || usageUnitLabel(row.unit)} · {row.modelCode}
                      </div>
                    </div>
                    <div className="text-right">
                      <div className="text-foreground font-medium">
                        {formatMoney(row.cost, row.currency)}
                      </div>
                      <div className="text-muted-foreground">
                        {formatCount(row.calls)} 次 · 用量 {trimDecimal(row.quantity)}{' '}
                        {row.unitLabel || usageUnitLabel(row.unit)}
                      </div>
                    </div>
                  </div>
                ))}
              </div>
            ) : (
              <EmptyState
                icon={<Gauge className="h-5 w-5" />}
                title="窗口内还没有用量记录"
                description="上游的 AI 能力接上并回报调用之后，这里才会有数字。"
              />
            )}
          </SectionCard>

          <SectionCard title="按币种合计" description="🔴 不同币种**分行列出**，页面不替它们求和。">
            {summary?.byCurrency.length ? (
              <div className="space-y-1">
                {summary.byCurrency.map((row) => (
                  <div
                    key={row.currency}
                    className="text-copy-13 flex items-center justify-between"
                  >
                    <span>{row.currency}</span>
                    <span>
                      {formatCount(row.calls)} 次 · {trimDecimal(row.cost)} {row.currency}
                    </span>
                  </div>
                ))}
              </div>
            ) : (
              <EmptyState title="还没有成本记录" description="记一笔用量之后这里会按币种分开列。" />
            )}
          </SectionCard>

          <SectionCard title="按成员" description="谁在用 AI、花了多少（仍按币种分开）。">
            {summary?.byMember.length ? (
              <div className="space-y-1">
                {summary.byMember.map((row) => (
                  <div
                    key={`${row.membershipId}-${row.currency}`}
                    className="text-copy-13 flex items-center justify-between"
                  >
                    <span>{memberLabel(row.membershipId)}</span>
                    <span>
                      {formatCount(row.calls)} 次 · {trimDecimal(row.cost)} {row.currency}
                    </span>
                  </div>
                ))}
              </div>
            ) : (
              <EmptyState
                title="还没有成员维度的用量"
                description="记一笔用量之后这里会按成员列出。"
              />
            )}
          </SectionCard>

          <SectionCard title="计费口径说明" description="下游取数时按这段口径对齐。">
            <p className="text-copy-13 text-muted-foreground">{summary?.billableBasis}</p>
            {totalCostByCurrency.length ? (
              <p className="text-copy-13 text-muted-foreground mt-2">
                当前窗口：{totalCostByCurrency.join(' · ')}
              </p>
            ) : null}
          </SectionCard>
        </div>
      )}

      {/* ================================================================ 用量台账 */}
      {tab === 'records' && (
        <div className="space-y-4">
          <SectionCard title="筛选">
            <div className="flex flex-wrap items-end gap-3">
              <label className="text-copy-13 flex flex-col gap-1">
                <span className="text-muted-foreground">能力码</span>
                <Input
                  value={capabilityFilter}
                  placeholder="TEXT_TRANSLATION"
                  onChange={(e) => {
                    setCapabilityFilter(e.target.value);
                    setPageIndex(0);
                  }}
                />
              </label>
              <label className="text-copy-13 flex flex-col gap-1">
                <span className="text-muted-foreground">模型</span>
                <Input
                  value={modelFilter}
                  placeholder="model-code"
                  onChange={(e) => {
                    setModelFilter(e.target.value);
                    setPageIndex(0);
                  }}
                />
              </label>
              <Button
                variant="outline"
                size="sm"
                onClick={() => {
                  setPageIndex(0);
                  void reloadRecords();
                }}
              >
                查询
              </Button>
            </div>
          </SectionCard>

          <SectionCard
            title={`用量台账（只追加，共 ${formatCount(records?.total ?? 0)} 行）`}
            description="一次 AI 调用一行：主体成员、能力、模型、量纲、调用量、成本快照、发生时刻、请求关联 id。"
          >
            {records?.items.length ? (
              <div className="space-y-2">
                {records.items.map((row) => (
                  <div
                    key={row.id}
                    className="border-border text-copy-13 flex flex-wrap items-center justify-between gap-2 rounded-md border p-3"
                  >
                    <div>
                      <div className="text-foreground font-medium">
                        {row.capabilityCode} · {row.modelCode}
                      </div>
                      <div className="text-muted-foreground">
                        成员 {memberLabel(row.membershipId)} · {formatInstant(row.occurredAt)}
                        {row.requestRef ? ` · ${row.requestRef}` : ''}
                      </div>
                    </div>
                    <div className="text-right">
                      <div className="text-foreground font-medium">
                        {formatMoney(row.cost, row.currency)}
                      </div>
                      <div className="text-muted-foreground">
                        用量 {trimDecimal(row.quantity)} {row.unitLabel || usageUnitLabel(row.unit)}
                        （单价快照 {trimDecimal(row.unitCost)}）
                      </div>
                    </div>
                  </div>
                ))}
              </div>
            ) : (
              <EmptyState
                icon={<CircleAlert className="h-5 w-5" />}
                title={capabilityFilter || modelFilter ? '没有匹配的记录' : '台账还是空的'}
                description={
                  capabilityFilter || modelFilter
                    ? '换个筛选条件再试 —— 筛选无结果**不等于**没有数据。'
                    : '上游 AI 能力回报调用之后，这里会逐行追加（**只追加，不许改也不许删**）。'
                }
              />
            )}
            {records && records.total > PAGE_SIZE ? (
              <div className="text-copy-13 mt-3 flex items-center justify-between">
                <Button
                  variant="outline"
                  size="sm"
                  disabled={pageIndex === 0}
                  onClick={() => setPageIndex((p) => Math.max(p - 1, 0))}
                >
                  上一页
                </Button>
                <span className="text-muted-foreground">
                  第 {pageIndex + 1} 页 / 共 {Math.ceil(records.total / PAGE_SIZE)} 页
                </span>
                <Button
                  variant="outline"
                  size="sm"
                  disabled={(pageIndex + 1) * PAGE_SIZE >= records.total}
                  onClick={() => setPageIndex((p) => p + 1)}
                >
                  下一页
                </Button>
              </div>
            ) : null}
          </SectionCard>
        </div>
      )}

      {/* ================================================================ 配额 */}
      {tab === 'quotas' && (
        <div className="space-y-4">
          <SectionCard
            title="配额配置"
            description="次数上限与成本上限至少配一个；两者都配时**任一越线即超限**。🔴 成本上限**声明自己的币种** —— 币种不符的调用照计次数、不计成本。"
          >
            <div className="grid gap-3 md:grid-cols-3">
              <label className="text-copy-13 flex flex-col gap-1">
                <span className="text-muted-foreground">主体范围</span>
                <select
                  className="border-control-border bg-card text-copy-14 text-foreground h-9 rounded-md border px-3"
                  value={quotaForm.scope}
                  onChange={(e) =>
                    setQuotaForm((f) => ({
                      ...f,
                      scope: e.target.value === 'USER' ? 'USER' : 'TENANT',
                    }))
                  }
                >
                  <option value="TENANT">租户级</option>
                  <option value="USER">用户级</option>
                </select>
              </label>
              {quotaForm.scope === 'USER' ? (
                <label className="text-copy-13 flex flex-col gap-1">
                  <span className="text-muted-foreground">主体成员（走路径，不是请求体）</span>
                  <Input
                    value={quotaForm.memberId}
                    placeholder="成员 ID"
                    onChange={(e) => setQuotaForm((f) => ({ ...f, memberId: e.target.value }))}
                  />
                </label>
              ) : null}
              <label className="text-copy-13 flex flex-col gap-1">
                <span className="text-muted-foreground">能力码（留空 = 全部能力）</span>
                <Input
                  value={quotaForm.capabilityCode}
                  placeholder="*"
                  onChange={(e) => setQuotaForm((f) => ({ ...f, capabilityCode: e.target.value }))}
                />
              </label>
              <label className="text-copy-13 flex flex-col gap-1">
                <span className="text-muted-foreground">次数上限（留空 = 不限）</span>
                <Input
                  value={quotaForm.limitCount}
                  placeholder="100"
                  onChange={(e) => setQuotaForm((f) => ({ ...f, limitCount: e.target.value }))}
                />
              </label>
              <label className="text-copy-13 flex flex-col gap-1">
                <span className="text-muted-foreground">成本上限（留空 = 不限）</span>
                <Input
                  value={quotaForm.limitCost}
                  placeholder="100.000000"
                  onChange={(e) => setQuotaForm((f) => ({ ...f, limitCost: e.target.value }))}
                />
              </label>
              <label className="text-copy-13 flex flex-col gap-1">
                <span className="text-muted-foreground">币种</span>
                <Input
                  value={quotaForm.currency}
                  placeholder="USD"
                  onChange={(e) => setQuotaForm((f) => ({ ...f, currency: e.target.value }))}
                />
              </label>
              <label className="text-copy-13 flex flex-col gap-1">
                <span className="text-muted-foreground">周期</span>
                <select
                  className="border-control-border bg-card text-copy-14 text-foreground h-9 rounded-md border px-3"
                  value={quotaForm.period}
                  onChange={(e) => setQuotaForm((f) => ({ ...f, period: e.target.value }))}
                >
                  {PERIODS.map((p) => (
                    <option key={p.value} value={p.value}>
                      {p.label}
                    </option>
                  ))}
                </select>
              </label>
            </div>
            <div className="mt-3 flex flex-wrap gap-2">
              <Button size="sm" onClick={() => void submitQuota()} disabled={busy}>
                保存配额
              </Button>
            </div>
          </SectionCard>

          <SectionCard
            title="配额预检"
            description="回答「按当前用量算，还放得下吗」。⚠️ 这是**读快照**、**不占额度** —— 并发下的权威判定发生在记用量那一次的数据库原子递增。"
          >
            <div className="grid gap-3 md:grid-cols-3">
              <label className="text-copy-13 flex flex-col gap-1">
                <span className="text-muted-foreground">能力码</span>
                <Input
                  value={preflightForm.capabilityCode}
                  placeholder="TEXT_TRANSLATION"
                  onChange={(e) =>
                    setPreflightForm((f) => ({ ...f, capabilityCode: e.target.value }))
                  }
                />
              </label>
              <label className="text-copy-13 flex flex-col gap-1">
                <span className="text-muted-foreground">模型（留空 = 不估成本）</span>
                <Input
                  value={preflightForm.modelCode}
                  placeholder="model-code"
                  onChange={(e) => setPreflightForm((f) => ({ ...f, modelCode: e.target.value }))}
                />
              </label>
              <label className="text-copy-13 flex flex-col gap-1">
                <span className="text-muted-foreground">本次调用量</span>
                <Input
                  value={preflightForm.quantity}
                  placeholder="1"
                  onChange={(e) => setPreflightForm((f) => ({ ...f, quantity: e.target.value }))}
                />
              </label>
            </div>
            <div className="mt-3">
              <Button
                variant="outline"
                size="sm"
                onClick={() => void runPreflight()}
                disabled={busy}
              >
                预检
              </Button>
            </div>
          </SectionCard>

          {preflight ? (
            <SectionCard
              title="预检结果"
              description={`判定口径：${evaluationModeLabel(preflight.mode)} —— 预检**不占额度**，权威判定在记用量那一次。`}
            >
              {preflight.violations.length ? (
                <div className="space-y-3">
                  {preflight.violations.map((violation) => (
                    <div
                      key={`${violation.quotaId}-${violation.limitKind}`}
                      className="border-border rounded-md border p-3"
                    >
                      <Badge tone={BADGE_TONE[violationTone(violation)]}>
                        {violation.limitKind === 'COST' ? '成本上限' : '次数上限'}
                      </Badge>
                      <dl className="text-copy-13 mt-2 grid gap-1 sm:grid-cols-2">
                        {violationFacts(violation).map((fact) => (
                          <div key={fact.label} className="flex gap-2">
                            <dt className="text-muted-foreground w-24 shrink-0">{fact.label}</dt>
                            <dd className="text-foreground">{fact.value}</dd>
                          </div>
                        ))}
                      </dl>
                      <p className="text-muted-foreground text-copy-13 mt-2">{violation.message}</p>
                    </div>
                  ))}
                </div>
              ) : (
                <EmptyState
                  title="按当前用量，放得下"
                  description="⚠️ 这是**预检快照** —— 并发下别的调用可能同时占用额度，权威判定在记用量那一次。"
                />
              )}
            </SectionCard>
          ) : null}

          <SectionCard title="配额列表" description="含当前周期的已用量、阈值与重置时刻。">
            {quotas.length ? (
              <div className="space-y-2">
                {quotas.map((quota) => (
                  <div key={quota.id} className="border-border rounded-md border p-3">
                    <div className="flex flex-wrap items-center justify-between gap-2">
                      <div className="text-foreground font-medium">{quotaSubjectLabel(quota)}</div>
                      <Badge tone={BADGE_TONE[quotaTone(quota.exceeded, quota.enabled)]}>
                        {quota.exceeded ? '已越线' : quota.enabled ? '正常' : '已停用'}
                      </Badge>
                    </div>
                    <dl className="text-copy-13 mt-2 grid gap-1 sm:grid-cols-2">
                      {quotaUsageFacts(quota).map((fact) => (
                        <div key={fact.label} className="flex gap-2">
                          <dt className="text-muted-foreground w-40 shrink-0">{fact.label}</dt>
                          <dd className="text-foreground">{fact.value}</dd>
                        </div>
                      ))}
                    </dl>
                  </div>
                ))}
              </div>
            ) : (
              <EmptyState
                icon={<Gauge className="h-5 w-5" />}
                title="还没有配额"
                description="配一条租户级兜底额度，或者给某个成员单独设一条。"
              />
            )}
          </SectionCard>
        </div>
      )}

      {/* ================================================================ 模型目录 */}
      {tab === 'models' && (
        <div className="space-y-4">
          <SectionCard
            title="登记 / 修改模型"
            description="🔴 **单价不硬编码在代码里** —— 单价是商业条款，归租户定。改价**不影响历史台账**（台账里存的是当时的单价快照）。"
          >
            <div className="grid gap-3 md:grid-cols-3">
              <label className="text-copy-13 flex flex-col gap-1">
                <span className="text-muted-foreground">模型标识</span>
                <Input
                  value={modelForm.modelCode}
                  placeholder="gpt-4o-mini"
                  onChange={(e) => setModelForm((f) => ({ ...f, modelCode: e.target.value }))}
                />
              </label>
              <label className="text-copy-13 flex flex-col gap-1">
                <span className="text-muted-foreground">模型名称</span>
                <Input
                  value={modelForm.modelName}
                  placeholder="轻量模型"
                  onChange={(e) => setModelForm((f) => ({ ...f, modelName: e.target.value }))}
                />
              </label>
              <label className="text-copy-13 flex flex-col gap-1">
                <span className="text-muted-foreground">供应商</span>
                <Input
                  value={modelForm.provider}
                  placeholder="OpenAI"
                  onChange={(e) => setModelForm((f) => ({ ...f, provider: e.target.value }))}
                />
              </label>
              <label className="text-copy-13 flex flex-col gap-1">
                <span className="text-muted-foreground">量纲</span>
                <select
                  className="border-control-border bg-card text-copy-14 text-foreground h-9 rounded-md border px-3"
                  value={modelForm.unit}
                  onChange={(e) => setModelForm((f) => ({ ...f, unit: e.target.value }))}
                >
                  {UNITS.map((u) => (
                    <option key={u.value} value={u.value}>
                      {u.label}
                    </option>
                  ))}
                </select>
              </label>
              <label className="text-copy-13 flex flex-col gap-1">
                <span className="text-muted-foreground">单位成本（每 1 个量纲单位）</span>
                <Input
                  value={modelForm.unitCost}
                  placeholder="0.002000"
                  onChange={(e) => setModelForm((f) => ({ ...f, unitCost: e.target.value }))}
                />
              </label>
              <label className="text-copy-13 flex flex-col gap-1">
                <span className="text-muted-foreground">币种</span>
                <Input
                  value={modelForm.currency}
                  placeholder="USD"
                  onChange={(e) => setModelForm((f) => ({ ...f, currency: e.target.value }))}
                />
              </label>
              <label className="text-copy-13 flex flex-col gap-1">
                <span className="text-muted-foreground">上下文上限（Token，0 = 不登记）</span>
                <Input
                  value={modelForm.contextLimit}
                  placeholder="8192"
                  onChange={(e) => setModelForm((f) => ({ ...f, contextLimit: e.target.value }))}
                />
              </label>
            </div>
            <div className="mt-3">
              <Button size="sm" onClick={() => void submitModel()} disabled={busy}>
                保存模型
              </Button>
            </div>
          </SectionCard>

          <SectionCard
            title="模型目录"
            description="停用的模型不能**新**记用量（历史台账不受影响）。"
          >
            {models.length ? (
              <div className="space-y-2">
                {models.map((model) => (
                  <div
                    key={model.id}
                    className="border-border text-copy-13 flex flex-wrap items-center justify-between gap-2 rounded-md border p-3"
                  >
                    <div>
                      <div className="text-foreground font-medium">
                        {model.modelName}（{model.modelCode}）
                      </div>
                      <div className="text-muted-foreground">
                        {model.provider || '未登记供应商'} · 量纲 {model.unitLabel} · 上下文{' '}
                        {formatCount(model.contextLimit)}
                      </div>
                    </div>
                    <div className="text-right">
                      <div className="text-foreground font-medium">
                        {trimDecimal(model.unitCost)} {model.currency} / {model.unitLabel}
                      </div>
                      <Badge tone={model.enabled ? 'success' : 'neutral'}>
                        {model.enabled ? '可用' : '已停用'}
                      </Badge>
                    </div>
                  </div>
                ))}
              </div>
            ) : (
              <EmptyState
                icon={<CircleAlert className="h-5 w-5" />}
                title="模型目录是空的"
                description="平台**零预置** —— 单价是商业条款，归租户自己定。"
              />
            )}
          </SectionCard>
        </div>
      )}

      {/* ================================================================ 敏感策略 */}
      {tab === 'policies' && (
        <div className="space-y-4">
          <SectionCard
            title="登记 / 修改敏感数据策略"
            description="🔴 动作只有三档：禁传 / 脱敏 / 允许。模型标识留空 = 该能力下**全部模型**；对个别模型加严时，**更具体的那条生效**。⚠️ 执行点是**各个 AI 能力自己**（本仓没有全局 AI 拦截点）。"
          >
            <div className="grid gap-3 md:grid-cols-3">
              <label className="text-copy-13 flex flex-col gap-1">
                <span className="text-muted-foreground">能力码</span>
                <Input
                  value={policyForm.capabilityCode}
                  placeholder="TEXT_TRANSLATION"
                  onChange={(e) => setPolicyForm((f) => ({ ...f, capabilityCode: e.target.value }))}
                />
              </label>
              <label className="text-copy-13 flex flex-col gap-1">
                <span className="text-muted-foreground">模型标识（留空 = 全部模型）</span>
                <Input
                  value={policyForm.modelCode}
                  placeholder="（全部模型）"
                  onChange={(e) => setPolicyForm((f) => ({ ...f, modelCode: e.target.value }))}
                />
              </label>
              <label className="text-copy-13 flex flex-col gap-1">
                <span className="text-muted-foreground">处理动作</span>
                <select
                  className="border-control-border bg-card text-copy-14 text-foreground h-9 rounded-md border px-3"
                  value={policyForm.action}
                  onChange={(e) => setPolicyForm((f) => ({ ...f, action: e.target.value }))}
                >
                  {SENSITIVE_ACTIONS.map((a) => (
                    <option key={a.value} value={a.value}>
                      {a.label}
                    </option>
                  ))}
                </select>
              </label>
            </div>
            <div className="mt-3 flex flex-wrap gap-2">
              <Button size="sm" onClick={() => void submitPolicy()} disabled={busy}>
                保存策略
              </Button>
              <Button
                variant="outline"
                size="sm"
                onClick={() => void lookupEffective()}
                disabled={busy}
              >
                查生效的那条
              </Button>
            </div>
          </SectionCard>

          {effective ? (
            <SectionCard title="生效的策略" description={effective.basis}>
              <div className="flex items-center gap-2">
                <Badge tone={BADGE_TONE[sensitiveActionTone(effective.action)]}>
                  {sensitiveActionLabel(effective.action)}
                </Badge>
                <span className="text-copy-13 text-muted-foreground">
                  {effective.capabilityCode} · {effective.modelCode || '全部模型'}
                  {!effective.configured ? '（库里没登记，落到默认档）' : ''}
                </span>
              </div>
            </SectionCard>
          ) : null}

          <SectionCard title="已登记的策略">
            {policies.length ? (
              <div className="space-y-2">
                {policies.map((policy) => (
                  <div
                    key={policy.id}
                    className="border-border text-copy-13 flex flex-wrap items-center justify-between gap-2 rounded-md border p-3"
                  >
                    <div>
                      <div className="text-foreground font-medium">
                        {policy.capabilityCode} · {policy.modelCode || '全部模型'}
                      </div>
                      <div className="text-muted-foreground">
                        {policy.note || '无备注'} · {formatInstant(policy.createdAt)}
                      </div>
                    </div>
                    <div className="flex items-center gap-2">
                      {!policy.enabled ? <Badge tone="neutral">已停用</Badge> : null}
                      <Badge tone={BADGE_TONE[sensitiveActionTone(policy.action)]}>
                        {policy.actionLabel || sensitiveActionLabel(policy.action)}
                      </Badge>
                    </div>
                  </div>
                ))}
              </div>
            ) : (
              <EmptyState
                icon={<ShieldCheck className="h-5 w-5" />}
                title="还没有敏感数据策略"
                description="库里没登记时，调用方落到默认档「允许」，并如实知道「没登记」。"
              />
            )}
          </SectionCard>
        </div>
      )}

      {/* ================================================================ 能力声明 */}
      {tab === 'capabilities' && (
        <div className="space-y-4">
          <SectionCard
            title="能力声明"
            description="逐维说清「有没有、依据是什么、缺了归谁」。`available=false` 的项**按原样显示**并写明归谁 —— 不显示成「暂无」。"
          >
            <div className="space-y-2">
              {capabilities?.capabilities.map((cap) => (
                <div key={cap.name} className="border-border rounded-md border p-3">
                  <div className="flex flex-wrap items-center justify-between gap-2">
                    <span className="text-foreground font-medium">{cap.name}</span>
                    <Badge tone={cap.available ? 'success' : 'danger'}>
                      {cap.available ? '已交付' : '未交付'}
                    </Badge>
                  </div>
                  <p className="text-muted-foreground text-copy-13 mt-1">{cap.basis}</p>
                  {cap.ownerModule ? (
                    <p className="text-muted-foreground text-copy-13 mt-1">
                      归 {cap.ownerModule} 模块；影响：{cap.impact}
                    </p>
                  ) : null}
                </div>
              ))}
            </div>
          </SectionCard>

          <SectionCard title="缺口" description="谁没交付、归谁、缺了会怎样。">
            <div className="space-y-2">
              {capabilities?.downstreamGaps.map((gap) => (
                <div key={gap.name} className="border-border rounded-md border p-3">
                  <div className="flex flex-wrap items-center justify-between gap-2">
                    <span className="text-foreground font-medium">
                      {gap.module} · {gap.name}
                    </span>
                    <Badge tone="warning">{gap.group}</Badge>
                  </div>
                  <p className="text-muted-foreground text-copy-13 mt-1">{gap.detail}</p>
                </div>
              ))}
            </div>
          </SectionCard>
        </div>
      )}
    </div>
  );
}
