'use client';

import { useCallback, useEffect, useState } from 'react';
import { PackageSearch, Truck } from 'lucide-react';
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
  Tabs,
  TabsContent,
  TabsList,
  TabsTrigger,
} from '@starcon/ui';
import type {
  ChannelEligibilityInput,
  ChannelEligibilityResult,
  ChannelFilters,
  LogisticsChannelCapabilities,
  LogisticsChannelOverview,
  LogisticsChannelPage,
  LogisticsChannelRow,
  SaveChannelInput,
} from './logistics-channel-model';
import {
  EMPTY_CHANNEL_FILTERS,
  EMPTY_CHANNEL_FORM,
  billingModeLabel,
  channelStatusLabel,
  fetchChannelCapabilities,
  fetchChannelOverview,
  fetchChannels,
  insuranceModeLabel,
  isEffectiveAt,
  judgeChannels,
  optionalNumber,
  parseList,
  readinessMeta,
  removeChannel,
  restrictionKindLabel,
  restrictionModeLabel,
  saveChannel,
  scopeKindLabel,
  surchargeKindLabel,
  surchargeText,
  toChannelForm,
  validateChannelForm,
  verdictMeta,
  VOLUME_DIVISOR,
} from './logistics-channel-model';

/**
 * 10.1.1.2 渠道配置。
 *
 * <p>蓝图：「配置具体物流渠道：所属承运商、渠道代码、适用店铺/国家/品类、计费方式
 * （按重/按件/按体积）、附加费（燃油、偏远、旺季）、申报限制、保价规则」，
 * 输出「渠道配置；供运费试算与自动匹配使用」。
 *
 * <p>🔴 这一页最重要的设计决定在「可承运性试算」那一段：**判定是服务端做的**。
 * 页面把包裹属性（目的国 / 店铺 / 品类 / 带电 / 带液 / 带磁 / 申报价值 / 保价额）
 * 发给 <code>POST /channel/logistics-channels/eligibility</code>，拿回来的���是
 * PASS / REVIEW / BLOCK 与逐条理由。
 *
 * <p>本页**刻意没有**任何一处拿 restrictions 数组自己比对品类和带电带液 ——
 * 那样规则会散落到每个调用方，而总有一个调用方会漏掉磁力这一条。蓝图的加粗句是
 * 「渠道限制（禁限品类、电池液体、磁力）要硬拦截，否则会被承运商拒收产生额外费用」，
 * 一个能被绕过的判断不算硬拦截。
 *
 * <p>另一条边界：承运商身份（名称 / 编码 / 接口类型 / 账号）是 10.1.1.1 的只读快照，
 * 这一页只在**新建时**选一个承运商，建完不可改 —— 改归属会让费率对不上账。
 */
export function LogisticsChannelWorkspace() {
  const [capabilities, setCapabilities] = useState<LogisticsChannelCapabilities | null>(null);
  const [overview, setOverview] = useState<LogisticsChannelOverview | null>(null);
  const [page, setPage] = useState<LogisticsChannelPage | null>(null);
  const [filters, setFilters] = useState<ChannelFilters>(EMPTY_CHANNEL_FILTERS);
  const [editing, setEditing] = useState<LogisticsChannelRow | null>(null);
  const [creating, setCreating] = useState(false);
  const [form, setForm] = useState<SaveChannelInput>(EMPTY_CHANNEL_FORM);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [busy, setBusy] = useState(false);

  // 试算表单
  const [probe, setProbe] = useState<ChannelEligibilityInput>({
    channelIds: null,
    countryCode: 'DE',
    shopCode: '',
    categoryCodes: [],
    containsBattery: false,
    containsLiquid: false,
    containsMagnet: false,
    declaredValue: null,
    insuredValue: null,
  });
  const [probeInput, setProbeInput] = useState('');
  const [probeResult, setProbeResult] = useState<ChannelEligibilityResult | null>(null);
  const [probeError, setProbeError] = useState('');

  const load = useCallback(async () => {
    setBusy(true);
    setError('');
    try {
      const [next, caps, ov] = await Promise.all([
        fetchChannels(filters),
        fetchChannelCapabilities(),
        fetchChannelOverview(),
      ]);
      setPage(next);
      setCapabilities(caps);
      setOverview(ov);
    } catch (e) {
      setError(e instanceof Error ? e.message : '渠道配置读不到');
    } finally {
      setBusy(false);
    }
  }, [filters]);

  useEffect(() => {
    void load();
  }, [load]);

  async function submit(next: SaveChannelInput) {
    const problems = validateChannelForm(next);
    if (problems.length > 0) {
      setError(problems.join('；'));
      return;
    }
    setBusy(true);
    setError('');
    try {
      const saved = await saveChannel(next);
      setEditing(null);
      setCreating(false);
      setForm(EMPTY_CHANNEL_FORM);
      setNotice(
        `${saved.channelName}（${saved.channelCode}）已保存，可用性：${readinessMeta(saved.readiness).label}。`,
      );
      await load();
    } catch (e) {
      setError(e instanceof Error ? e.message : '保存失败');
    } finally {
      setBusy(false);
    }
  }

  async function runProbe() {
    setProbeError('');
    try {
      const result = await judgeChannels({ ...probe, categoryCodes: parseList(probeInput) });
      setProbeResult(result);
      if (result.verdicts.length === 0) {
        // 空结果**不等于**「随便选一条」——它代表这些条件下没有候选渠道。
        setProbeError(result.note);
      }
    } catch (e) {
      setProbeError(e instanceof Error ? e.message : '可承运性判定失败');
    }
  }

  async function remove(row: LogisticsChannelRow) {
    setBusy(true);
    setError('');
    try {
      await removeChannel(row.id);
      setNotice(`已撤下渠道配置「${row.channelName}」（所属承运商档案不受影响）。`);
      await load();
    } catch (e) {
      setError(e instanceof Error ? e.message : '撤下失败');
    } finally {
      setBusy(false);
    }
  }

  const gaps = (capabilities?.dimensions ?? []).filter((d) => !d.available);

  return (
    <>
      <PageHeader
        title="渠道配置"
        description="配置具体物流渠道：所属承运商、适用店铺 / 国家 / 品类、计费方式、附加费（燃油 / 偏远 / 旺季）、申报限制与保价规则。渠道限制由服务端判定 —— 被拦的包裹下单会被承运商拒收并产生额外费用。"
      />

      {error ? <p className="text-destructive text-sm">{error}</p> : null}
      {notice ? <p className="text-muted-foreground text-sm">{notice}</p> : null}

      <div className="mt-4 grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
        <StatCard
          label="配置齐备"
          value={String(page?.summary.ready ?? 0)}
          comparisonLabel="可参与运费试算"
        />
        <StatCard
          label="待补配置"
          value={String((page?.summary.noBaseRate ?? 0) + (page?.summary.noScope ?? 0))}
          comparisonLabel="缺单价 / 缺适用范围"
        />
        <StatCard
          label="带限制"
          value={String(page?.summary.withRestrictions ?? 0)}
          comparisonLabel="禁限品类 / 电池 / 液体 / 磁力"
        />
        <StatCard
          label="旺季中"
          value={String(page?.summary.peakSeasonActive ?? 0)}
          comparisonLabel="旺季附加费当前生效"
        />
      </div>

      <Tabs defaultValue="channels" className="mt-6">
        <TabsList>
          <TabsTrigger value="channels">渠道</TabsTrigger>
          <TabsTrigger value="probe">可承运性试算</TabsTrigger>
          <TabsTrigger value="capabilities">能力与边界</TabsTrigger>
        </TabsList>

        <TabsContent value="channels">
          <SectionCard
            title="筛选"
            extra={
              <Button
                size="sm"
                onClick={() => {
                  setCreating(true);
                  setEditing(null);
                  setForm(EMPTY_CHANNEL_FORM);
                  setError('');
                }}
                disabled={busy}
              >
                新建渠道
              </Button>
            }
          >
            <div className="grid gap-3 md:grid-cols-4">
              <FormField id="kw" label="关键词">
                <Input
                  id="kw"
                  value={filters.keyword}
                  placeholder="渠道代码 / 名称"
                  onChange={(e) => setFilters({ ...filters, keyword: e.target.value, page: 0 })}
                />
              </FormField>
              <FormField id="carrier" label="所属承运商 ID">
                <Input
                  id="carrier"
                  value={filters.carrierProfileId}
                  placeholder="全部"
                  onChange={(e) =>
                    setFilters({ ...filters, carrierProfileId: e.target.value, page: 0 })
                  }
                />
              </FormField>
              <FormField id="mode" label="计费方式">
                <Input
                  id="mode"
                  value={filters.billingMode}
                  placeholder="全部"
                  onChange={(e) => setFilters({ ...filters, billingMode: e.target.value, page: 0 })}
                />
              </FormField>
              <FormField id="status" label="状态">
                <Input
                  id="status"
                  value={filters.status}
                  placeholder="全部"
                  onChange={(e) => setFilters({ ...filters, status: e.target.value, page: 0 })}
                />
              </FormField>
              <FormField id="ready" label="可用性">
                <Input
                  id="ready"
                  value={filters.readiness}
                  placeholder="全部"
                  onChange={(e) => setFilters({ ...filters, readiness: e.target.value, page: 0 })}
                />
              </FormField>
              <FormField id="scopeKind" label="适用维度">
                <Input
                  id="scopeKind"
                  value={filters.scopeKind}
                  placeholder="COUNTRY / SHOP / CATEGORY"
                  onChange={(e) => setFilters({ ...filters, scopeKind: e.target.value, page: 0 })}
                />
              </FormField>
              <FormField id="scopeValue" label="维度取值">
                <Input
                  id="scopeValue"
                  value={filters.scopeValue}
                  placeholder="如 DE"
                  onChange={(e) => setFilters({ ...filters, scopeValue: e.target.value, page: 0 })}
                />
              </FormField>
              <FormField id="rKind" label="有限制">
                <Input
                  id="rKind"
                  value={filters.restrictionKind}
                  placeholder="BATTERY / LIQUID ..."
                  onChange={(e) =>
                    setFilters({ ...filters, restrictionKind: e.target.value, page: 0 })
                  }
                />
              </FormField>
            </div>
            <p className="text-muted-foreground mt-2 text-xs">
              上方四张卡覆盖当前筛选结果且不受分页影响。所属承运商用档案 ID 过滤 ——
              每行的承运商名称已经显示在卡片上，需要按名称筛时用关键词即可。
            </p>
          </SectionCard>

          <SectionCard title={`渠道（${page?.total ?? 0}）`} className="mt-4">
            {busy && !page ? (
              <p className="text-muted-foreground text-sm">正在加载渠道配置…</p>
            ) : page && page.items.length > 0 ? (
              <div className="space-y-3">
                {page.items.map((row) => (
                  <ChannelCard
                    key={row.id}
                    row={row}
                    onEdit={() => {
                      setCreating(false);
                      setEditing(row);
                      setForm(toChannelForm(row));
                      setError('');
                    }}
                    onDelete={() => void remove(row)}
                    busy={busy}
                  />
                ))}
                <div className="flex items-center gap-2">
                  <Button
                    size="sm"
                    variant="ghost"
                    disabled={page.page <= 0 || busy}
                    onClick={() => setFilters({ ...filters, page: filters.page - 1 })}
                  >
                    上一页
                  </Button>
                  <span className="text-muted-foreground text-xs">
                    第 {page.page + 1} / {Math.max(page.totalPages, 1)} 页
                  </span>
                  <Button
                    size="sm"
                    variant="ghost"
                    disabled={page.page + 1 >= page.totalPages || busy}
                    onClick={() => setFilters({ ...filters, page: filters.page + 1 })}
                  >
                    下一页
                  </Button>
                </div>
              </div>
            ) : (
              <EmptyState
                icon={<Truck className="h-6 w-6" />}
                title="还没有渠道配置"
                description="渠道挂在承运商之下：先在 /logistics/carriers 登记好承运商的物流侧档案，再回来为它配置具体渠道（代码、适用范围、计费与附加费）。"
              />
            )}
          </SectionCard>

          {creating || editing ? (
            <SectionCard
              title={creating ? '新建渠道配置' : `编辑渠道 · ${editing?.channelName}`}
              className="mt-4"
            >
              <ChannelForm
                form={form}
                lockedCarrier={editing?.carrierName}
                busy={busy}
                onChange={setForm}
                onCancel={() => {
                  setCreating(false);
                  setEditing(null);
                  setForm(EMPTY_CHANNEL_FORM);
                }}
                onSubmit={() => void submit(form)}
              />
            </SectionCard>
          ) : null}
        </TabsContent>

        <TabsContent value="probe">
          <SectionCard title="可承运性试算（判定在服务端）">
            <Notice tone="warning" title="为什么要放在服务端判">
              蓝图加粗句：「渠道限制（禁限品类、电池液体、磁力）要硬拦截，否则会被承运商
              拒收产生额外费用」。本页把包裹属性发给服务端换回结论，**不在浏览器里比对限制** ——
              散落的判断规则总有一处会漏掉磁力。
            </Notice>

            <div className="mt-4 grid gap-3 md:grid-cols-3">
              <FormField id="p-country" label="目的国">
                <Input
                  id="p-country"
                  value={probe.countryCode}
                  placeholder="如 DE"
                  onChange={(e) => setProbe({ ...probe, countryCode: e.target.value })}
                />
              </FormField>
              <FormField id="p-shop" label="店铺编码">
                <Input
                  id="p-shop"
                  value={probe.shopCode}
                  placeholder="留空表示不按店铺判"
                  onChange={(e) => setProbe({ ...probe, shopCode: e.target.value })}
                />
              </FormField>
              <FormField id="p-cat" label="包裹品类编码">
                <Input
                  id="p-cat"
                  value={probeInput}
                  placeholder="逗号分隔，如 HOME_GOODS,BEAUTY_LIQUID"
                  onChange={(e) => setProbeInput(e.target.value)}
                />
              </FormField>
              <FormField id="p-declared" label="申报价值">
                <Input
                  id="p-declared"
                  value={probe.declaredValue ?? ''}
                  placeholder="留空不按申报限制判"
                  onChange={(e) =>
                    setProbe({ ...probe, declaredValue: optionalNumber(e.target.value) })
                  }
                />
              </FormField>
              <FormField id="p-insured" label="保价金额">
                <Input
                  id="p-insured"
                  value={probe.insuredValue ?? ''}
                  placeholder="超过保价上限会被硬拦截"
                  onChange={(e) =>
                    setProbe({ ...probe, insuredValue: optionalNumber(e.target.value) })
                  }
                />
              </FormField>
            </div>

            <div className="mt-3 flex flex-wrap gap-4">
              {(
                [
                  ['containsBattery', '带电池'],
                  ['containsLiquid', '带液体'],
                  ['containsMagnet', '带磁力'],
                ] as const
              ).map(([key, label]) => (
                <label key={key} className="flex items-center gap-2 text-sm">
                  <input
                    type="checkbox"
                    checked={probe[key]}
                    onChange={(e) => setProbe({ ...probe, [key]: e.target.checked })}
                  />
                  {label}
                </label>
              ))}
            </div>

            <Button className="mt-4" onClick={() => void runProbe()} disabled={busy}>
              <PackageSearch className="mr-1 h-4 w-4" />
              判定可承运性
            </Button>

            {probeError ? <p className="text-destructive mt-3 text-sm">{probeError}</p> : null}

            {probeResult && probeResult.verdicts.length > 0 ? (
              <div className="mt-4 space-y-2">
                <p className="text-muted-foreground text-xs">
                  可承运 {probeResult.pass} · 需人工确认 {probeResult.review} · 硬拦截{' '}
                  {probeResult.block}
                </p>
                {probeResult.verdicts.map((v) => (
                  <VerdictCard key={v.channelId} verdict={v} />
                ))}
                <p className="text-muted-foreground mt-2 text-xs">{probeResult.note}</p>
              </div>
            ) : null}
          </SectionCard>
        </TabsContent>

        <TabsContent value="capabilities">
          <SectionCard title="能力与边界">
            <Notice tone="info" title="上游边界">
              {capabilities?.boundaryNote ?? ''}
            </Notice>
            <Notice tone="warning" title="渠道限制">
              {capabilities?.restrictionNote ?? ''}
            </Notice>
            <ul className="mt-3 space-y-2 text-sm">
              {(capabilities?.dimensions ?? []).map((d) => (
                <li key={d.dimension}>
                  <Badge tone={d.available ? 'success' : 'warning'}>
                    {d.available ? '已具备' : '未交付'}
                  </Badge>{' '}
                  <strong>{d.description}</strong>
                  <div className="text-muted-foreground text-xs">
                    来源：{d.source}
                    {!d.available ? ` · 归属 ${d.ownerModule} · 影响：${d.impact}` : ''}
                  </div>
                </li>
              ))}
            </ul>
          </SectionCard>

          <SectionCard title="全量概览（不受筛选与分页影响）" className="mt-4">
            <div className="grid gap-3 md:grid-cols-2">
              <div>
                <h4 className="text-sm font-medium">计费方式</h4>
                {(overview?.byBillingMode ?? []).map((c) => (
                  <p key={c.code} className="text-muted-foreground text-xs">
                    {c.label}：{c.count}
                  </p>
                ))}
              </div>
              <div>
                <h4 className="text-sm font-medium">渠道限制</h4>
                {(overview?.byRestrictionKind ?? []).map((c) => (
                  <p key={c.code} className="text-muted-foreground text-xs">
                    {c.label}：{c.count}
                  </p>
                ))}
              </div>
            </div>
            <p className="text-muted-foreground mt-2 text-xs">{overview?.note ?? ''}</p>
            {gaps.length > 0 ? (
              <p className="text-muted-foreground mt-2 text-xs">
                未交付维度：{gaps.map((g) => g.ownerModule).join('、')}（详见「能力与边界」）
              </p>
            ) : null}
          </SectionCard>
        </TabsContent>
      </Tabs>
    </>
  );
}

function VerdictCard({ verdict }: { verdict: ChannelEligibilityResult['verdicts'][number] }) {
  const meta = verdictMeta(verdict.verdict);
  return (
    <div className="rounded-md border p-3">
      <div className="flex flex-wrap items-center gap-2">
        <strong>{verdict.channelName}</strong>
        <span className="text-muted-foreground text-xs">{verdict.channelCode}</span>
        <Badge tone={meta.tone}>{meta.label}</Badge>
        <span className="text-muted-foreground text-xs">承运商：{verdict.carrierName}</span>
      </div>
      {verdict.blockingReasons.map((r) => (
        <p key={r} className="text-destructive mt-2 text-xs">
          ⛔ {r}
        </p>
      ))}
      {verdict.reviewReasons.map((r) => (
        <p key={r} className="text-muted-foreground mt-2 text-xs">
          ⚠ {r}
        </p>
      ))}
      {verdict.blockingReasons.length === 0 && verdict.reviewReasons.length === 0 ? (
        <p className="text-muted-foreground mt-2 text-xs">没有命中任何限制。</p>
      ) : null}
    </div>
  );
}

function ChannelCard({
  row,
  onEdit,
  onDelete,
  busy,
}: {
  row: LogisticsChannelRow;
  onEdit: () => void;
  onDelete: () => void;
  busy: boolean;
}) {
  const meta = readinessMeta(row.readiness);
  const now = new Date();
  return (
    <div className="rounded-md border p-3">
      <div className="flex flex-wrap items-center gap-2">
        <strong>{row.channelName}</strong>
        <span className="text-muted-foreground text-xs">{row.channelCode}</span>
        <Badge tone={meta.tone}>{meta.label}</Badge>
        <Badge tone="info">{billingModeLabel(row.billingMode)}</Badge>
        <Badge tone="neutral">{channelStatusLabel(row.status)}</Badge>
        {row.peakSeasonActive ? <Badge tone="warning">旺季中</Badge> : null}
        {row.peakSeasonActive === false &&
        row.surcharges.some((s) => s.surchargeKind === 'PEAK_SEASON') ? (
          <Badge tone="neutral">旺季未生效</Badge>
        ) : null}
      </div>

      <div className="text-muted-foreground mt-1 text-xs">
        承运商：{row.carrierName}（{row.carrierCode}，只读快照） · 单价 {row.baseRate}{' '}
        {row.billingCurrency} · 最低收费 {row.minCharge}
        {row.billingMode === 'BY_WEIGHT' ? ' · 按实重' : ''}
        {row.billingMode === 'BY_PIECE' ? ' · 按件' : ''}
        {row.billingMode === 'BY_VOLUME' ? ` · 体积折算 ${row.chargeDivisor}` : ''}
      </div>

      <p className="text-muted-foreground mt-1 text-xs">{row.readinessMessage}</p>

      <p className="mt-2 text-xs">
        <span className="text-muted-foreground">适用：</span>
        {row.scopes.length === 0
          ? '未登记'
          : row.scopes.map((s) => `${scopeKindLabel(s.scopeKind)} ${s.scopeValue}`).join('、')}
      </p>

      <p className="mt-1 text-xs">
        <span className="text-muted-foreground">附加费：</span>
        {row.surcharges.length === 0
          ? '无'
          : row.surcharges.map((s) => surchargeText(s)).join('、')}
        {row.surcharges.length > 0 ? (
          <span className="text-muted-foreground">
            {' '}
            （当前生效：
            {row.activeSurcharges.length === 0
              ? '无'
              : row.activeSurcharges.map((s) => surchargeText(s)).join('、')}
            ）
          </span>
        ) : null}
      </p>

      {row.surcharges.some((s) => s.surchargeKind === 'PEAK_SEASON') ? (
        <p className="text-muted-foreground mt-1 text-xs">
          旺季区间：
          {row.surcharges
            .filter((s) => s.surchargeKind === 'PEAK_SEASON')
            .map(
              (s) =>
                `${s.effectiveFrom?.slice(0, 10) ?? '?'} ~ ${s.effectiveTo?.slice(0, 10) ?? '?'}` +
                (isEffectiveAt(s, now) ? '（生效中）' : '（未生效）'),
            )
            .join('、')}
        </p>
      ) : null}

      <p className="mt-1 text-xs">
        <span className="text-muted-foreground">渠道限制：</span>
        {row.restrictions.length === 0
          ? '未登记'
          : row.restrictions
              .map(
                (r) =>
                  `${restrictionKindLabel(r.restrictionKind)}${
                    r.subject ? `「${r.subject}」` : ''
                  } · ${restrictionModeLabel(r.restrictionMode)}`,
              )
              .join('、')}
      </p>

      <p className="mt-1 text-xs">
        <span className="text-muted-foreground">申报：</span>
        {row.declarationMinValue === null && row.declarationMaxValue === null
          ? '未登记上下限'
          : `${row.declarationMinValue ?? '不限'} ~ ${row.declarationMaxValue ?? '不限'} ${
              row.declarationCurrency ?? ''
            }`}
        <span className="text-muted-foreground"> · 保价：</span>
        {insuranceModeLabel(row.insuranceMode)}
        {row.insuranceMode !== 'NONE'
          ? ` · 费率 ${row.insuranceRatePct}% · 最低保费 ${row.insuranceMinFee}`
          : ''}
        {row.insuranceMaxValue !== null ? ` · 保价上限 ${row.insuranceMaxValue}` : ''}
      </p>

      <div className="mt-3 flex gap-2">
        <Button size="sm" variant="outline" onClick={onEdit} disabled={busy}>
          编辑
        </Button>
        <Button size="sm" variant="ghost" onClick={onDelete} disabled={busy}>
          撤下
        </Button>
      </div>
    </div>
  );
}

function ChannelForm({
  form,
  lockedCarrier,
  busy,
  onChange,
  onCancel,
  onSubmit,
}: {
  form: SaveChannelInput;
  // `string | undefined`：编辑态才有承运商，新建时是 undefined。AGENTS.md 开着
  // exactOptionalPropertyTypes，所以**可选但可能为 undefined** 的 prop 必须显式
  // 带上 `| undefined`，否则传 `editing?.carrierName` 报 TS2375。
  lockedCarrier?: string | undefined;
  busy: boolean;
  onChange: (next: SaveChannelInput) => void;
  onCancel: () => void;
  onSubmit: () => void;
}) {
  return (
    <div className="space-y-4">
      <div className="grid gap-3 md:grid-cols-3">
        <FormField id="f-carrier" label="所属承运商 ID（新建必填，建后不可改）">
          <Input
            id="f-carrier"
            value={form.carrierProfileId ?? ''}
            placeholder="承运商档案 UUID"
            disabled={Boolean(lockedCarrier)}
            onChange={(e) => onChange({ ...form, carrierProfileId: e.target.value || null })}
          />
        </FormField>
        <FormField id="f-code" label="渠道代码（建后不可改）">
          <Input
            id="f-code"
            value={form.channelCode}
            placeholder="如 SF-DE-STD"
            disabled={Boolean(form.channelId)}
            onChange={(e) => onChange({ ...form, channelCode: e.target.value })}
          />
        </FormField>
        <FormField id="f-name" label="渠道名称">
          <Input
            id="f-name"
            value={form.channelName}
            placeholder="如 顺丰德国标准"
            onChange={(e) => onChange({ ...form, channelName: e.target.value })}
          />
        </FormField>
        <FormField id="f-status" label="状态">
          <Input
            id="f-status"
            value={form.status}
            placeholder="DRAFT / ACTIVE / SUSPENDED"
            onChange={(e) => onChange({ ...form, status: e.target.value })}
          />
        </FormField>
        <FormField id="f-mode" label="计费方式">
          <Input
            id="f-mode"
            value={form.billingMode}
            placeholder="BY_WEIGHT / BY_PIECE / BY_VOLUME"
            onChange={(e) => {
              const mode = e.target.value;
              onChange({
                ...form,
                billingMode: mode,
                // 按件时基数被 DB 钉死为 1；按体积给行业通用缺省，省得每次手填
                chargeDivisor: mode === 'BY_VOLUME' ? VOLUME_DIVISOR : 1,
              });
            }}
          />
        </FormField>
        <FormField id="f-currency" label="计费币种">
          <Input
            id="f-currency"
            value={form.billingCurrency}
            placeholder="USD"
            onChange={(e) => onChange({ ...form, billingCurrency: e.target.value })}
          />
        </FormField>
        <FormField id="f-rate" label="基础单价">
          <Input
            id="f-rate"
            value={String(form.baseRate)}
            onChange={(e) => onChange({ ...form, baseRate: Number(e.target.value) || 0 })}
          />
        </FormField>
        <FormField id="f-divisor" label="折算基数（按件必须为 1）">
          <Input
            id="f-divisor"
            value={String(form.chargeDivisor)}
            onChange={(e) => onChange({ ...form, chargeDivisor: Number(e.target.value) || 1 })}
          />
        </FormField>
        <FormField id="f-min" label="最低收费">
          <Input
            id="f-min"
            value={String(form.minCharge)}
            onChange={(e) => onChange({ ...form, minCharge: Number(e.target.value) || 0 })}
          />
        </FormField>
      </div>

      <ScopeEditor form={form} onChange={onChange} />
      <SurchargeEditor form={form} onChange={onChange} />
      <RestrictionEditor form={form} onChange={onChange} />

      <div className="grid gap-3 md:grid-cols-3">
        <FormField id="f-dmin" label="申报价值下限">
          <Input
            id="f-dmin"
            value={form.declarationMinValue ?? ''}
            placeholder="留空不限"
            onChange={(e) =>
              onChange({ ...form, declarationMinValue: optionalNumber(e.target.value) })
            }
          />
        </FormField>
        <FormField id="f-dmax" label="申报价值上限">
          <Input
            id="f-dmax"
            value={form.declarationMaxValue ?? ''}
            placeholder="留空不限"
            onChange={(e) =>
              onChange({ ...form, declarationMaxValue: optionalNumber(e.target.value) })
            }
          />
        </FormField>
        <FormField id="f-dcur" label="申报币种">
          <Input
            id="f-dcur"
            value={form.declarationCurrency}
            placeholder="如 EUR"
            onChange={(e) => onChange({ ...form, declarationCurrency: e.target.value })}
          />
        </FormField>
        <FormField id="f-ins" label="保价方式">
          <Input
            id="f-ins"
            value={form.insuranceMode}
            placeholder="NONE / DECLARED_VALUE / FIXED"
            onChange={(e) => onChange({ ...form, insuranceMode: e.target.value })}
          />
        </FormField>
        <FormField id="f-insrate" label="保价费率 %">
          <Input
            id="f-insrate"
            value={String(form.insuranceRatePct)}
            onChange={(e) => onChange({ ...form, insuranceRatePct: Number(e.target.value) || 0 })}
          />
        </FormField>
        <FormField id="f-insmax" label="保价上限（超过判硬拦截）">
          <Input
            id="f-insmax"
            value={form.insuranceMaxValue ?? ''}
            placeholder="留空不限"
            onChange={(e) =>
              onChange({ ...form, insuranceMaxValue: optionalNumber(e.target.value) })
            }
          />
        </FormField>
      </div>

      <FormField id="f-note" label="备注">
        <Input
          id="f-note"
          value={form.note}
          onChange={(e) => onChange({ ...form, note: e.target.value })}
        />
      </FormField>

      <div className="flex gap-2">
        <Button size="sm" onClick={onSubmit} disabled={busy}>
          保存
        </Button>
        <Button size="sm" variant="ghost" onClick={onCancel} disabled={busy}>
          取消
        </Button>
      </div>
    </div>
  );
}

function ScopeEditor({
  form,
  onChange,
}: {
  form: SaveChannelInput;
  onChange: (next: SaveChannelInput) => void;
}) {
  const [kind, setKind] = useState('COUNTRY');
  const [value, setValue] = useState('');
  return (
    <div className="rounded-md border p-3">
      <h4 className="text-sm font-medium">适用店铺 / 国家 / 品类</h4>
      <p className="text-muted-foreground text-xs">
        每值一行，不是逗号串 —— 下游要按它真筛选。一条都没有的渠道对谁都不可用。
      </p>
      <div className="mt-2 flex gap-2">
        <Input value={kind} onChange={(e) => setKind(e.target.value)} placeholder="COUNTRY" />
        <Input value={value} onChange={(e) => setValue(e.target.value)} placeholder="如 DE" />
        <Button
          size="sm"
          variant="outline"
          onClick={() => {
            const v = value.trim();
            if (!v) return;
            onChange({
              ...form,
              scopes: [...form.scopes, { scopeKind: kind, scopeValue: v }],
            });
            setValue('');
          }}
        >
          添加
        </Button>
      </div>
      <div className="mt-2 flex flex-wrap gap-2">
        {form.scopes.map((s) => (
          <Badge key={`${s.scopeKind}:${s.scopeValue}`} tone="info">
            {scopeKindLabel(s.scopeKind)} {s.scopeValue}
            <button
              type="button"
              className="ml-1"
              onClick={() =>
                onChange({
                  ...form,
                  scopes: form.scopes.filter(
                    (x) => !(x.scopeKind === s.scopeKind && x.scopeValue === s.scopeValue),
                  ),
                })
              }
            >
              ×
            </button>
          </Badge>
        ))}
      </div>
    </div>
  );
}

function SurchargeEditor({
  form,
  onChange,
}: {
  form: SaveChannelInput;
  onChange: (next: SaveChannelInput) => void;
}) {
  const [kind, setKind] = useState('FUEL');
  const [amount, setAmount] = useState('0');
  const [from, setFrom] = useState('');
  const [to, setTo] = useState('');
  return (
    <div className="rounded-md border p-3">
      <h4 className="text-sm font-medium">附加费（燃油 / 偏远 / 旺季）</h4>
      <p className="text-muted-foreground text-xs">
        旺季附加费**必须**填生效起止时刻：没有区间的旺季费无法回答「现在要不要加」。
      </p>
      <div className="mt-2 grid gap-2 md:grid-cols-5">
        <Input value={kind} onChange={(e) => setKind(e.target.value)} placeholder="FUEL" />
        <Input
          value={amount}
          onChange={(e) => setAmount(e.target.value)}
          placeholder="金额 / 百分比"
        />
        <Input
          value={from}
          onChange={(e) => setFrom(e.target.value)}
          placeholder="旺季起 2026-11-01"
        />
        <Input value={to} onChange={(e) => setTo(e.target.value)} placeholder="旺季止 2026-12-01" />
        <Button
          size="sm"
          variant="outline"
          onClick={() => {
            const replaced = form.surcharges.filter((s) => s.surchargeKind !== kind);
            onChange({
              ...form,
              surcharges: [
                ...replaced,
                {
                  surchargeKind: kind,
                  chargeMode: kind === 'FUEL' ? 'PERCENT' : 'FIXED',
                  amount: Number(amount) || 0,
                  effectiveFrom: from ? new Date(from).toISOString() : null,
                  effectiveTo: to ? new Date(to).toISOString() : null,
                  note: '',
                },
              ],
            });
            setAmount('0');
            setFrom('');
            setTo('');
          }}
        >
          设置
        </Button>
      </div>
      <div className="mt-2 flex flex-wrap gap-2">
        {form.surcharges.map((s) => (
          <Badge key={s.surchargeKind} tone="info">
            {surchargeKindLabel(s.surchargeKind)} {s.amount}
            {s.chargeMode === 'PERCENT' ? '%' : ''}
            <button
              type="button"
              className="ml-1"
              onClick={() =>
                onChange({
                  ...form,
                  surcharges: form.surcharges.filter((x) => x.surchargeKind !== s.surchargeKind),
                })
              }
            >
              ×
            </button>
          </Badge>
        ))}
      </div>
      <p className="text-muted-foreground mt-1 text-xs">
        附加费一律走金额（BigDecimal / NUMERIC），页面不做任何浮点运算，只原样提交。
        {form.surcharges.length > 0
          ? ` 当前生效：${
              form.surcharges
                .filter((s) => isEffectiveAt(s, new Date()))
                .map((s) => surchargeText(s))
                .join('、') || '无'
            }`
          : ''}
      </p>
    </div>
  );
}

function RestrictionEditor({
  form,
  onChange,
}: {
  form: SaveChannelInput;
  onChange: (next: SaveChannelInput) => void;
}) {
  const [kind, setKind] = useState('PROHIBITED_CATEGORY');
  const [subject, setSubject] = useState('');
  const [mode, setMode] = useState('BLOCK');
  const [reason, setReason] = useState('');
  return (
    <div className="rounded-md border p-3">
      <h4 className="text-sm font-medium">渠道限制（禁限品类 / 电池 / 液体 / 磁力）</h4>
      <p className="text-muted-foreground text-xs">
        禁限品类要**点名到具体品类编码**；电池 / 液体 / 磁力是整条渠道层面的限制（不带品类）。
        适用品类与禁限品类不得同时命中同一个品类 —— 写了会被服务端拒，因为那样的包裹
        下单会被承运商拒收并产生额外费用。
      </p>
      <div className="mt-2 grid gap-2 md:grid-cols-5">
        <Input
          value={kind}
          onChange={(e) => setKind(e.target.value)}
          placeholder="PROHIBITED_CATEGORY"
        />
        <Input
          value={subject}
          onChange={(e) => setSubject(e.target.value)}
          placeholder="品类编码（仅禁限品类填）"
        />
        <Input value={mode} onChange={(e) => setMode(e.target.value)} placeholder="BLOCK" />
        <Input
          value={reason}
          onChange={(e) => setReason(e.target.value)}
          placeholder="承运商侧理由（选填）"
        />
        <Button
          size="sm"
          variant="outline"
          onClick={() => {
            const replaced = form.restrictions.filter(
              (r) => !(r.restrictionKind === kind && r.subject === subject.trim()),
            );
            onChange({
              ...form,
              restrictions: [
                ...replaced,
                {
                  restrictionKind: kind,
                  subject: kind === 'PROHIBITED_CATEGORY' ? subject.trim() : '',
                  restrictionMode: mode,
                  carrierReason: reason,
                },
              ],
            });
            setSubject('');
            setReason('');
          }}
        >
          添加
        </Button>
      </div>
      <div className="mt-2 flex flex-wrap gap-2">
        {form.restrictions.map((r) => (
          <Badge key={`${r.restrictionKind}:${r.subject}`} tone="danger">
            {restrictionKindLabel(r.restrictionKind)}
            {r.subject ? `「${r.subject}」` : ''} · {restrictionModeLabel(r.restrictionMode)}
            <button
              type="button"
              className="ml-1"
              onClick={() =>
                onChange({
                  ...form,
                  restrictions: form.restrictions.filter(
                    (x) => !(x.restrictionKind === r.restrictionKind && x.subject === r.subject),
                  ),
                })
              }
            >
              ×
            </button>
          </Badge>
        ))}
      </div>
      <p className="text-muted-foreground mt-1 text-xs">
        限制的**判定**在服务端（可承运性试算那一页）；这里只是录入规则本身。
      </p>
    </div>
  );
}
