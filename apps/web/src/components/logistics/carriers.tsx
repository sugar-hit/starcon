'use client';

import { useCallback, useEffect, useState } from 'react';
import { RefreshCw, Truck } from 'lucide-react';
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
  CarrierCapabilities,
  CarrierOverview,
  CarrierPage,
  CarrierRow,
  SaveCarrierInput,
} from './carriers-model';
import {
  EMPTY_CARRIER_FILTERS,
  availabilityMeta,
  businessTypeLabel,
  carrierCategoryLabel,
  fetchCarrierCapabilities,
  fetchCarrierOverview,
  fetchCarriers,
  interfaceTypeLabel,
  listText,
  parseList,
  saveCarrier,
  serviceCapabilityLabel,
  serviceTierLabel,
  syncCarriers,
  toForm,
  validateCarrierForm,
} from './carriers-model';

/**
 * 10.1.1.1 承运商列表。
 *
 * <p>🔴 这一页最重要的一条设计前提：**承运商是往来单位的一种角色，
 * 不在此处重复建档**。所以主体身份（名称 / 编码 / 接口类型 / 账号 / 服务能力）
 * 在表格里**只读**，页面上没有任何编辑它们的入口 —— 它们要回 /partners 改。
 * 本页能写的只有物流侧那几项。
 *
 * <p>可用性不是管理员点一个下拉框选出来的：它是「凭据引用 + 覆盖国家 +
 * 业务类型 + 上游主体可用性 + 接口类型」算出来的，表格里那条徽章后面跟着
 * 判定理由，缺什么就写什么。
 */
export function CarrierWorkspace() {
  const [capabilities, setCapabilities] = useState<CarrierCapabilities | null>(null);
  const [overview, setOverview] = useState<CarrierOverview | null>(null);
  const [page, setPage] = useState<CarrierPage | null>(null);
  const [filters, setFilters] = useState(EMPTY_CARRIER_FILTERS);
  const [editing, setEditing] = useState<CarrierRow | null>(null);
  const [form, setForm] = useState<SaveCarrierInput | null>(null);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [busy, setBusy] = useState(false);

  const load = useCallback(async () => {
    setBusy(true);
    setError('');
    try {
      const [next, caps, ov] = await Promise.all([
        fetchCarriers(filters),
        fetchCarrierCapabilities(),
        fetchCarrierOverview(),
      ]);
      setPage(next);
      setCapabilities(caps);
      setOverview(ov);
    } catch (e) {
      setError(e instanceof Error ? e.message : '承运商列表读不到');
    } finally {
      setBusy(false);
    }
  }, [filters]);

  useEffect(() => {
    void load();
  }, [load]);

  async function runSync() {
    setBusy(true);
    setError('');
    setNotice('');
    try {
      const result = await syncCarriers();
      setNotice(
        `同步完成：看到 ${result.seen} 个承运商身份，新建 ${result.created} 条物流投影，刷新 ${result.refreshed} 条。` +
          `其中 ${result.skippedNoCarrierRole} 个主体还没建承运商角色档案，已如实跳过。${result.note}`,
      );
      await load();
    } catch (e) {
      setError(e instanceof Error ? e.message : '同步失败');
    } finally {
      setBusy(false);
    }
  }

  async function submit(next: SaveCarrierInput, row: CarrierRow) {
    const problems = validateCarrierForm(next);
    if (problems.length > 0) {
      setError(problems.join('；'));
      return;
    }
    setBusy(true);
    setError('');
    try {
      const saved = await saveCarrier(row.id, next);
      setEditing(null);
      setForm(null);
      setNotice(
        `${saved.partyName} 的物流侧档案已保存，可用性：${availabilityMeta(saved.availability).label}。`,
      );
      await load();
    } catch (e) {
      setError(e instanceof Error ? e.message : '保存失败');
    } finally {
      setBusy(false);
    }
  }

  const gaps = (capabilities?.dimensions ?? []).filter((d) => !d.available);

  return (
    <>
      <PageHeader
        title="承运商列表"
        description="承运商是往来单位的一种角色，身份主档在往来单位档案里，本页只维护物流侧的档案（覆盖国家、时效等级、支持业务类型、凭据引用）与可用性。"
      />

      {error ? <p className="text-destructive text-sm">{error}</p> : null}
      {notice ? <p className="text-muted-foreground text-sm">{notice}</p> : null}

      <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
        <StatCard
          label="可用"
          value={String(page?.summary.available ?? 0)}
          comparisonLabel="系统可自动对接"
        />
        <StatCard
          label="仅人工"
          value={String(page?.summary.manualOnly ?? 0)}
          comparisonLabel="平台下单 / 邮件往来"
        />
        <StatCard
          label="待补配置"
          value={String(
            (page?.summary.notRegistered ?? 0) +
              (page?.summary.noCredential ?? 0) +
              (page?.summary.noCoverage ?? 0) +
              (page?.summary.noBusinessScope ?? 0),
          )}
          comparisonLabel="缺凭据 / 覆盖 / 业务类型"
        />
        <StatCard
          label="主体不可用"
          value={String(page?.summary.partyBlocked ?? 0)}
          comparisonLabel="上游已停用 / 拉黑 / 删除"
        />
      </div>

      <Tabs defaultValue="carriers" className="mt-6">
        <TabsList>
          <TabsTrigger value="carriers">承运商</TabsTrigger>
          <TabsTrigger value="capabilities">能力与边界</TabsTrigger>
        </TabsList>

        <TabsContent value="carriers">
          <SectionCard
            title="筛选"
            extra={
              <Button size="sm" onClick={() => void runSync()} disabled={busy}>
                <RefreshCw className="mr-1 h-4 w-4" />
                同步承运商身份
              </Button>
            }
          >
            <div className="grid gap-3 md:grid-cols-4">
              <FormField id="kw" label="关键词">
                <Input
                  id="kw"
                  value={filters.keyword}
                  placeholder="主体编码 / 名称"
                  onChange={(e) =>
                    setFilters({ ...filters, keyword: e.target.value, page: 0 })
                  }
                />
              </FormField>
              <FormField id="cat" label="承运商类别">
                <Input
                  id="cat"
                  value={filters.carrierCategory}
                  placeholder="全部"
                  onChange={(e) =>
                    setFilters({ ...filters, carrierCategory: e.target.value, page: 0 })
                  }
                />
              </FormField>
              <FormField id="tier" label="时效等级">
                <Input
                  id="tier"
                  value={filters.serviceTier}
                  placeholder="全部"
                  onChange={(e) =>
                    setFilters({ ...filters, serviceTier: e.target.value, page: 0 })
                  }
                />
              </FormField>
              <FormField id="avail" label="可用性">
                <Input
                  id="avail"
                  value={filters.availability}
                  placeholder="全部"
                  onChange={(e) =>
                    setFilters({ ...filters, availability: e.target.value, page: 0 })
                  }
                />
              </FormField>
              <FormField id="country" label="覆盖国家">
                <Input
                  id="country"
                  value={filters.countryCode}
                  placeholder="如 DE"
                  onChange={(e) =>
                    setFilters({ ...filters, countryCode: e.target.value, page: 0 })
                  }
                />
              </FormField>
              <FormField id="biz" label="业务类型">
                <Input
                  id="biz"
                  value={filters.businessType}
                  placeholder="如 FBA_INBOUND"
                  onChange={(e) =>
                    setFilters({ ...filters, businessType: e.target.value, page: 0 })
                  }
                />
              </FormField>
            </div>
            <p className="text-muted-foreground mt-2 text-xs">
              汇总数字（上方四张卡）覆盖当前筛选结果且不受分页影响；概览页统计的是全量。
            </p>
          </SectionCard>

          <SectionCard title={`承运商（${page?.total ?? 0}）`} className="mt-4">
            {page && page.items.length > 0 ? (
              <div className="space-y-3">
                {page.items.map((row) => (
                  <CarrierCard
                    key={row.id}
                    row={row}
                    onEdit={() => {
                      setEditing(row);
                      setForm(toForm(row));
                    }}
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
                title="还没有承运商"
                description="先在往来单位档案里给对手方挂上承运商身份并建角色档案，然后点「同步承运商身份」把它们投影过来。"
              />
            )}
          </SectionCard>

          {editing && form ? (
            <SectionCard title={`登记物流侧档案 · ${editing.partyName}`} className="mt-4">
              <CarrierForm
                form={form}
                onChange={setForm}
                busy={busy}
                onCancel={() => {
                  setEditing(null);
                  setForm(null);
                }}
                onSubmit={() => void submit(form, editing)}
              />
            </SectionCard>
          ) : null}
        </TabsContent>

        <TabsContent value="capabilities">
          <SectionCard title="能力与边界">
            <Notice tone="info" title="模块边界">
              {capabilities?.boundaryNote ?? ''}
            </Notice>
            <Notice tone="info" title="上游来源">
              {capabilities?.upstreamNote ?? ''}
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
                <h4 className="text-sm font-medium">承运商类别</h4>
                {(overview?.byCategory ?? []).map((c) => (
                  <p key={c.category} className="text-muted-foreground text-xs">
                    {c.label}：{c.count}
                  </p>
                ))}
              </div>
              <div>
                <h4 className="text-sm font-medium">时效等级</h4>
                {(overview?.byTier ?? []).map((t) => (
                  <p key={t.category} className="text-muted-foreground text-xs">
                    {t.label}：{t.count}
                  </p>
                ))}
              </div>
            </div>
            <p className="text-muted-foreground mt-2 text-xs">{overview?.note ?? ''}</p>
            {gaps.length > 0 ? (
              <p className="text-muted-foreground mt-2 text-xs">
                未交付维度：{gaps.map((g) => g.dimension).join('、')}（详见「能力与边界」）
              </p>
            ) : null}
          </SectionCard>
        </TabsContent>
      </Tabs>
    </>
  );
}

function CarrierCard({ row, onEdit }: { row: CarrierRow; onEdit: () => void }) {
  const meta = availabilityMeta(row.availability);
  return (
    <div className="rounded-md border p-3">
      <div className="flex flex-wrap items-center gap-2">
        <strong>{row.partyName}</strong>
        <span className="text-muted-foreground text-xs">{row.partyCode}</span>
        <Badge tone={meta.tone}>{meta.label}</Badge>
        <Badge tone="info">{carrierCategoryLabel(row.carrierCategory)}</Badge>
        <Badge tone="info">{serviceTierLabel(row.serviceTier)}</Badge>
        <span className="flex-1" />
        <Button size="sm" variant="ghost" onClick={onEdit}>
          登记物流侧档案
        </Button>
      </div>

      <p className="text-muted-foreground mt-1 text-xs">{row.availabilityMessage}</p>

      <dl className="mt-2 grid gap-x-4 gap-y-1 text-xs md:grid-cols-3">
        <div>
          <dt className="text-muted-foreground">接口类型（上游只读）</dt>
          <dd>{interfaceTypeLabel(row.interfaceType)}</dd>
        </div>
        <div>
          <dt className="text-muted-foreground">账号（上游只读）</dt>
          <dd>{row.accountRef || '—'}</dd>
        </div>
        <div>
          <dt className="text-muted-foreground">服务能力（上游只读）</dt>
          <dd>{listText(row.serviceCapabilities, serviceCapabilityLabel)}</dd>
        </div>
        <div>
          <dt className="text-muted-foreground">覆盖国家</dt>
          <dd>{listText(row.coverageCountries, (c) => c)}</dd>
        </div>
        <div>
          <dt className="text-muted-foreground">支持业务类型</dt>
          <dd>{listText(row.businessTypes, businessTypeLabel)}</dd>
        </div>
        <div>
          <dt className="text-muted-foreground">凭据（只存引用）</dt>
          {/* 只显示掩码，不显示引用本身：引用是密钥管理器的路径，给运营看没有意义 */}
          <dd>{row.secretMasked || '未登记'}</dd>
        </div>
      </dl>
    </div>
  );
}

function CarrierForm({
  form,
  onChange,
  busy,
  onSubmit,
  onCancel,
}: {
  form: SaveCarrierInput;
  onChange: (next: SaveCarrierInput) => void;
  busy: boolean;
  onSubmit: () => void;
  onCancel: () => void;
}) {
  return (
    <div className="space-y-3">
      <div className="grid gap-3 md:grid-cols-2">
        <FormField id="f-cat" label="承运商类别" required>
          <Input
            id="f-cat"
            value={form.carrierCategory}
            placeholder="EXPRESS_COURIER / LINE_HAUL / POSTAL / FBA_CARRIER / LAST_MILE"
            onChange={(e) => onChange({ ...form, carrierCategory: e.target.value.trim() })}
          />
        </FormField>
        <FormField id="f-tier" label="时效等级" required>
          <Input
            id="f-tier"
            value={form.serviceTier}
            placeholder="STANDARD / EXPRESS / PRIORITY"
            onChange={(e) => onChange({ ...form, serviceTier: e.target.value.trim() })}
          />
        </FormField>
      </div>

      <FormField
        id="f-ref"
        label="凭据引用"
        hint="只填密钥管理器引用（secret:// / kv:// / vault:// / external-secrets://），真实密钥不入库"
      >
        <Input
          id="f-ref"
          value={form.secretRef}
          placeholder="kv://starcon/carriers/xxx"
          onChange={(e) => onChange({ ...form, secretRef: e.target.value.trim() })}
        />
      </FormField>

      <FormField id="f-mask" label="凭据掩码" hint="界面上显示的是它；必须真的打码（含 *）">
        <Input
          id="f-mask"
          value={form.secretMasked}
          placeholder="xxx_live_****3f2a"
          onChange={(e) => onChange({ ...form, secretMasked: e.target.value.trim() })}
        />
      </FormField>

      <FormField id="f-country" label="覆盖国家" hint="逗号分隔，如 DE, US, JP" required>
        <Input
          id="f-country"
          value={form.coverageCountries.join(', ')}
          onChange={(e) =>
            onChange({ ...form, coverageCountries: parseList(e.target.value) })
          }
        />
      </FormField>

      <FormField id="f-biz" label="支持业务类型" hint="逗号分隔，如 OWN_FULFILLMENT, FBA_INBOUND" required>
        <Input
          id="f-biz"
          value={form.businessTypes.join(', ')}
          onChange={(e) => onChange({ ...form, businessTypes: parseList(e.target.value) })}
        />
      </FormField>

      <FormField id="f-note" label="备注">
        <Input
          id="f-note"
          value={form.note}
          onChange={(e) => onChange({ ...form, note: e.target.value })}
        />
      </FormField>

      <div className="flex gap-2">
        <Button size="sm" onClick={onSubmit} disabled={busy}>
          保存物流侧档案
        </Button>
        <Button size="sm" variant="ghost" onClick={onCancel} disabled={busy}>
          取消
        </Button>
      </div>
      <p className="text-muted-foreground text-xs">
        主体名称、编码、接口类型、账号、服务能力在本页不可编辑 —— 那是往来单位档案的主权，要回 /partners 改。
      </p>
    </div>
  );
}
