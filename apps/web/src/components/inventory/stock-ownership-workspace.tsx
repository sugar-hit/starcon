'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import { AlertTriangle, Boxes, Package, RefreshCw, Scale, Warehouse } from 'lucide-react';
import { Button, EmptyState, PageHeader, SectionCard, StatCard } from '@starcon/ui';
import type {
  OwnerInventoryReport,
  OwnershipDriftReport,
  OwnershipReferenceOptions,
  OwnershipReassignRequest,
  OwnershipRuleInput,
  OwnershipRulePage,
  StockOwnershipCapabilities,
  StockOwnershipResolved,
  StockOwnerInput,
  StockOwnerPage,
} from '../../../../../packages/api-contracts/src/catalog';
import {
  allowedRefTypes,
  createOwner,
  createRule,
  errorText,
  fetchCapabilities,
  fetchDrift,
  fetchOwners,
  fetchReferenceOptions,
  fetchReport,
  fetchResolved,
  fetchRules,
  intervalText,
  notNull,
  ownedAssetText,
  ownerKindLabels,
  ownerKindTone,
  qty,
  reassign,
  refTypeLabels,
  retireRule,
  resolvedHeadline,
  scopeTargetText,
  scopeTypeLabels,
  setOwnerStatus,
  supersedeRule,
} from './stock-ownership-model';
import { useIdentity } from '../iam/identity-shell';

/**
 * 7.7.1.1 货主与所有权工作区。
 *
 * <p>蓝图：「为库存 bucket 显式标注货主（stock_owner）与所有权类型（自有 / 供应商寄销 /
 * 平台所有 / 代销），使『货在谁的仓库』与『货是谁的』不再混为一谈。默认自有，但租户可把整仓
 * 或整 SKU 设为寄售。」
 *
 * <p>界面纪律：
 *
 * <ul>
 *   <li><b>按货主拆分的库存占第一屏</b>。这一页存在的理由不是"配几行配置"，而是让财务与运营
 *       看见"这批货到底是谁的"，所以拆分表在最上面，配置在下面。
 *   <li><b>寄售量与自有量必须分开看</b>。把两者合成一个"库存总量"显示，等于把本模块最关键的
 *       区分又抹掉了 —— 所以自有 / 非自有各自成列，非自有还要单列出数量。
 *   <li><b>货值不可用要说出来</b>。本页只出数量口径。用采购报价冒充库存成本会把账面资产算错，
 *       比留一个空列危险得多。
 *   <li><b>存量不跟着新配置改这件事必须明写</b>。配完"整仓寄售"之后库里已有的货仍是原货主，
 *       要改判得显式发起货权转移。不说清楚，用户会以为配完就生效了。
 * </ul>
 */
export function StockOwnershipWorkspace() {
  const { session } = useIdentity();
  const readable = session.permissions.includes('warehouse.inventory.read');
  // 建货主 / 配货权 / 转移货权都直接改变资产归属，写入走 update 权限。
  const writable = session.permissions.includes('warehouse.inventory.update');

  const [owners, setOwners] = useState<StockOwnerPage | null>(null);
  const [rules, setRules] = useState<OwnershipRulePage | null>(null);
  const [report, setReport] = useState<OwnerInventoryReport | null>(null);
  const [drift, setDrift] = useState<OwnershipDriftReport | null>(null);
  const [resolved, setResolved] = useState<StockOwnershipResolved | null>(null);
  const [capabilities, setCapabilities] = useState<StockOwnershipCapabilities | null>(null);
  const [options, setOptions] = useState<OwnershipReferenceOptions | null>(null);

  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');

  const [scopeFilter, setScopeFilter] = useState('WAREHOUSE');
  // 换版目标：点某一行的「换版」后，建档表单切到换版模式并预填该档的作用域。
  const [superseding, setSuperseding] = useState<OwnershipRulePage['rules'][number] | null>(null);
  const [warehouseFilter, setWarehouseFilter] = useState('');
  const [legalEntityFilter, setLegalEntityFilter] = useState('');
  const [skuFilter, setSkuFilter] = useState('');
  const [page, setPage] = useState(0);

  // 空筛选项不落到键上：`exactOptionalPropertyTypes` 下「键存在但值为 undefined」
  // 和「键不存在」不是一回事，下游签名一律是 `?:`。
  const filterParams = useMemo(
    () => ({
      ...(warehouseFilter ? { warehouseId: warehouseFilter } : {}),
      ...(legalEntityFilter ? { legalEntityId: legalEntityFilter } : {}),
      ...(skuFilter ? { skuId: skuFilter } : {}),
    }),
    [warehouseFilter, legalEntityFilter, skuFilter],
  );

  const load = useCallback(async () => {
    setLoading(true);
    setError('');
    try {
      const [ownerPage, rulePage, inventoryReport, driftReport, resolution, caps, refs] =
        await Promise.all([
          fetchOwners({ page: 0, size: 50 }),
          fetchRules({ effectiveOnly: false, page, size: 20 }),
          fetchReport(filterParams),
          fetchDrift(filterParams),
          fetchResolved(filterParams),
          fetchCapabilities(),
          fetchReferenceOptions(),
        ]);
      setOwners(ownerPage);
      setRules(rulePage);
      setReport(inventoryReport);
      setDrift(driftReport);
      setResolved(resolution);
      setCapabilities(caps);
      setOptions(refs);
    } catch (e) {
      setError(errorText(e));
    } finally {
      setLoading(false);
    }
  }, [page, filterParams]);

  useEffect(() => {
    if (!readable) return;
    void load();
  }, [readable, load]);

  const ownerList = (owners?.owners ?? []).filter(notNull);
  const ruleList = (rules?.rules ?? []).filter(notNull);
  const reportRows = (report?.rows ?? []).filter(notNull);
  const driftRows = (drift?.rows ?? []).filter(notNull);
  const amountDim = capabilities?.dimensions?.filter(notNull).find((d) => d.code === 'AMOUNT_VALUATION');

  return (
    <div className="space-y-6">
      <PageHeader
        title="货主与所有权"
        description="标注每个库存 bucket 的货主与所有权类型（自有 / 供应商寄售 / 平台所有 / 代销），使「货在谁的仓库」与「货是谁的」不再混为一谈。默认自有。"
      />

      {error && (
        <div className="border-destructive/40 bg-destructive-soft text-destructive flex items-start gap-3 rounded-md border p-4 text-sm">
          <AlertTriangle className="mt-0.5 h-4 w-4 shrink-0" />
          <div className="space-y-2">
            <p>{error}</p>
            <Button variant="outline" size="sm" onClick={() => void load()}>
              重新加载
            </Button>
          </div>
        </div>
      )}
      {notice && (
        <div className="border-success/40 bg-success-soft text-success rounded-md border p-4 text-sm">
          {notice}
        </div>
      )}

      <div className="grid gap-4 md:grid-cols-4">
        <StatCard
          label="货主"
          value={owners === null ? '—' : owners.total}
          polarity="neutral"
          comparisonLabel="货在谁的仓库 ≠ 货是谁的"
        />
        <StatCard
          label="自有货量"
          value={report === null ? '—' : qty(report.ownedAssetOnHand)}
          polarity="positive"
          comparisonLabel="12 财务只把这一列算进自有资产"
        />
        <StatCard
          label="非自有货量"
          value={report === null ? '—' : qty(report.nonOwnedAssetOnHand)}
          polarity="neutral"
          comparisonLabel="寄售 / 代销 / 平台所有：不进自有资产"
        />
        <StatCard
          label="货权漂移"
          value={drift === null ? '—' : drift.totalBucketCount}
          polarity={driftRows.length > 0 ? 'negative' : 'positive'}
          comparisonLabel="库位明细的货主 ≠ 当前生效档应给的货主"
        />
      </div>

      {/* 解析结论占第一屏：这一页存在的理由是回答「这批货是谁的」。 */}
      <SectionCard
        title="这批货归谁"
        description="按 SKU / 法人 / 仓库 / 兜底四档解析，越具体越优先。回落默认货主是兜底而不是判定结果。"
        extra={
          <Button variant="outline" size="sm" onClick={() => void load()} disabled={loading}>
            <RefreshCw className={loading ? 'mr-2 h-3.5 w-3.5 animate-spin' : 'mr-2 h-3.5 w-3.5'} />
            刷新
          </Button>
        }
      >
        {resolved === null ? (
          <EmptyState icon={<Scale className="h-5 w-5" />} title="加载中" description="正在解析货权。" />
        ) : (
          <div className="space-y-2 text-sm">
            <p className="font-medium">{resolvedHeadline(resolved)}</p>
            {resolved.owner && (
              <p className="text-muted-foreground">
                {ownerKindLabels[resolved.owner.ownerKind] ?? resolved.owner.ownerKind} ·{' '}
                {refTypeLabels[resolved.owner.ownerRefType] ?? resolved.owner.ownerRefType}
                {resolved.owner.ownerRefLabel ? ` · ${resolved.owner.ownerRefLabel}` : ''} ·{' '}
                {ownedAssetText(resolved.owner.countsAsOwnedAsset)}
                {!resolved.owner.legalEntityLinked && resolved.owner.ownerKind === 'SELF' && (
                  <span className="text-warning">
                    　（未挂法人：本租户尚未登记法人，12 财务暂时无法归到法人资产表）
                  </span>
                )}
              </p>
            )}
            <p className="text-muted-foreground">{resolved.note}</p>
          </div>
        )}
      </SectionCard>

      <SectionCard
        title="按货主拆分的库存"
        description="真实从库存明细聚合。自有与非自有分列 —— 合成一个「库存总量」就等于把最关键的区分抹掉了。"
        extra={<ScopeFilter options={options} filter={filterParams} onChange={(next) => {
          setWarehouseFilter(next.warehouseId ?? '');
          setLegalEntityFilter(next.legalEntityId ?? '');
          setSkuFilter(next.skuId ?? '');
        }} />}
      >
        {reportRows.length === 0 ? (
          <EmptyState
            icon={<Boxes className="h-5 w-5" />}
            title={loading ? '加载中' : '当前范围内没有库存'}
            description={
              loading
                ? '正在按货主聚合库位明细。'
                : '库存明细按货主维度还没有数据 —— 写入库位明细时会自动落默认自有货主。'
            }
          />
        ) : (
          <>
            <table className="w-full text-sm">
              <thead className="text-muted-foreground text-left text-xs">
                <tr>
                  <th className="py-2">货主</th>
                  <th className="py-2">所有权类型</th>
                  <th className="py-2">资产口径</th>
                  <th className="py-2 text-right">仓</th>
                  <th className="py-2 text-right">SKU</th>
                  <th className="py-2 text-right">库位</th>
                  <th className="py-2 text-right">在手</th>
                  <th className="py-2 text-right">预占</th>
                  <th className="py-2 text-right">可用</th>
                </tr>
              </thead>
              <tbody>
                {reportRows.map((row) => (
                  <tr key={row.ownerId} className="border-control-border border-t">
                    <td className="py-2">{row.ownerName}</td>
                    <td className="py-2">
                      <OwnerKindBadge kind={row.ownerKind} />
                    </td>
                    <td
                      className={`py-2 text-xs ${
                        ownerKindTone(row.ownerKind) === 'owned' ? 'text-success' : 'text-muted-foreground'
                      }`}
                    >
                      {ownedAssetText(row.countsAsOwnedAsset)}
                    </td>
                    <td className="py-2 text-right">{row.warehouseCount}</td>
                    <td className="py-2 text-right">{row.skuCount}</td>
                    <td className="py-2 text-right">{row.locationCount}</td>
                    <td className="py-2 text-right">{qty(row.onHand)}</td>
                    <td className="py-2 text-right">{qty(row.reserved)}</td>
                    <td className="py-2 text-right">{qty(row.available)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
            {report && <p className="text-muted-foreground mt-3 text-xs">{report.note}</p>}
            {/* 货值不可用必须显式说明，不能画成一个 0。 */}
            <p className="text-muted-foreground mt-2 text-xs">
              <strong className="text-warning">货值口径不可用</strong>
              {report?.amountBasisNote ?? amountDim?.unavailableReason ?? ''}
            </p>
          </>
        )}
      </SectionCard>

      <SectionCard
        title="货权漂移"
        description="库位明细上的货主与「按当前生效档应该属于的货主」不一致。货权档只对生效之后写入的库位明细赋权，存量不会自动跟着改 —— 这是有意的：追溯改存量货权等于改账。"
      >
        {driftRows.length === 0 ? (
          <EmptyState
            icon={<Warehouse className="h-5 w-5" />}
            title={loading ? '加载中' : '没有货权漂移'}
            description={
              loading
                ? '正在比对库位明细与生效货权档。'
                : '所有库位明细的货主都与生效货权档一致，或该范围未配货权档、已落默认自有货主。'
            }
          />
        ) : (
          <>
            <table className="w-full text-sm">
              <thead className="text-muted-foreground text-left text-xs">
                <tr>
                  <th className="py-2">当前货主</th>
                  <th className="py-2">应归属</th>
                  <th className="py-2 text-right">明细行</th>
                  <th className="py-2 text-right">在手</th>
                  <th className="py-2 text-right">操作</th>
                </tr>
              </thead>
              <tbody>
                {driftRows.map((row) => (
                  <tr key={`${row.currentOwnerId}-${row.expectedOwnerId ?? 'none'}`} className="border-control-border border-t">
                    <td className="py-2">{row.currentOwnerName}</td>
                    <td className="py-2">{row.expectedOwnerName ?? '（未配置）'}</td>
                    <td className="py-2 text-right">{row.bucketCount}</td>
                    <td className="py-2 text-right">{qty(row.onHand)}</td>
                    <td className="py-2 text-right">
                      <Button
                        variant="outline"
                        size="sm"
                        disabled={!writable || saving || !row.expectedOwnerId}
                        onClick={() =>
                          void doReassign(row.currentOwnerId, row.expectedOwnerId ?? '', filterParams)
                        }
                      >
                        改判
                      </Button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
            {drift && <p className="text-muted-foreground mt-3 text-xs">{drift.note}</p>}
          </>
        )}
      </SectionCard>

      <SectionCard
        title="货权档"
        description="改货权 = 收口旧档 + 开新档，历史各档原样保留。货权档只对生效之后写入的库位明细生效；要让存量跟着改，用上面的货权转移。"
        extra={
          <div className="flex items-center gap-2">
            <select
              className="border-control-border bg-card rounded-md border px-3 py-2 text-sm"
              value={scopeFilter}
              onChange={(e) => setScopeFilter(e.target.value)}
            >
              {Object.entries(scopeTypeLabels).map(([code, label]) => (
                <option key={code} value={code}>
                  {label}
                </option>
              ))}
            </select>
            <RuleEditor
              writable={writable}
              saving={saving}
              scopeType={scopeFilter}
              owners={ownerList}
              options={options}
              superseding={superseding}
              onCancelSupersede={() => setSuperseding(null)}
              onStart={() => {
                setError('');
                setNotice('');
                setSaving(true);
              }}
              onSaved={(message: string) => {
                setNotice(message);
                setSaving(false);
                setSuperseding(null);
                void load();
              }}
              onError={(message: string) => {
                setError(message);
                setSaving(false);
              }}
              onRetire={doRetire}
            />
          </div>
        }
      >
        {ruleList.length === 0 ? (
          <EmptyState
            icon={<Scale className="h-5 w-5" />}
            title={loading ? '加载中' : '还没有配置货权'}
            description={
              loading
                ? '正在读取货权分档。'
                : '没有货权档时，所有库位明细都会落默认自有货主 —— 这是蓝图说的「默认自有」，但它是一个兜底而不是判定结果。'
            }
          />
        ) : (
          <table className="w-full text-sm">
            <thead className="text-muted-foreground text-left text-xs">
              <tr>
                <th className="py-2">范围</th>
                <th className="py-2">对象</th>
                <th className="py-2">货主</th>
                <th className="py-2">资产口径</th>
                <th className="py-2">生效区间</th>
                <th className="py-2 text-right">操作</th>
              </tr>
            </thead>
            <tbody>
              {ruleList.map((rule) => (
                <tr key={rule.id} className="border-control-border border-t">
                  <td className="py-2 text-xs">{scopeTypeLabels[rule.scopeType] ?? rule.scopeType}</td>
                  <td className="py-2">{scopeTargetText(rule)}</td>
                  <td className="py-2">{rule.ownerName}</td>
                  <td
                    className={`py-2 text-xs ${
                      rule.countsAsOwnedAsset ? 'text-success' : 'text-muted-foreground'
                    }`}
                  >
                    {ownedAssetText(rule.countsAsOwnedAsset)}
                  </td>
                  <td className="py-2 text-xs">
                    {intervalText(rule.effectiveFrom, rule.effectiveTo)}
                  </td>
                  <td className="py-2 text-right">
                    <div className="flex justify-end gap-2">
                      <Button
                        variant="outline"
                        size="sm"
                        disabled={!writable || saving || rule.effectiveTo !== null}
                        title={rule.effectiveTo ? '已收口的档不能作为换版对象' : '收口旧档 + 开新档'}
                        onClick={() => {
                          setSuperseding(rule);
                          setScopeFilter(rule.scopeType);
                        }}
                      >
                        换版
                      </Button>
                      <Button
                        variant="outline"
                        size="sm"
                        disabled={!writable || saving}
                        onClick={() => void doRetire(rule.id)}
                      >
                        作废
                      </Button>
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
        <Pager page={rules?.page ?? 0} size={20} total={rules?.total ?? 0} onTurn={setPage} />
      </SectionCard>

      <SectionCard
        title="货主档案"
        description="货主不提供删除：停用走状态，历史货权仍需可查 —— 删掉之后上个月末这批货记在谁名下就答不出来了。"
      >
        {ownerList.length === 0 ? (
          <EmptyState
            icon={<Package className="h-5 w-5" />}
            title={loading ? '加载中' : '还没有货主'}
            description={
              loading
                ? '正在读取货主档案。'
                : '写入第一条库位明细时会自动建立一个默认自有货主，也可以在这里手工建。'
            }
          />
        ) : (
          <table className="w-full text-sm">
            <thead className="text-muted-foreground text-left text-xs">
              <tr>
                <th className="py-2">编码</th>
                <th className="py-2">名称</th>
                <th className="py-2">所有权类型</th>
                <th className="py-2">指向</th>
                <th className="py-2">资产口径</th>
                <th className="py-2">状态</th>
                <th className="py-2 text-right">操作</th>
              </tr>
            </thead>
            <tbody>
              {ownerList.map((owner) => (
                <tr key={owner.id} className="border-control-border border-t">
                  <td className="py-2 font-mono text-xs">{owner.code}</td>
                  <td className="py-2">
                    {owner.name}
                    {owner.isDefault && (
                      <span className="text-muted-foreground ml-2 text-xs">默认</span>
                    )}
                  </td>
                  <td className="py-2">
                    <OwnerKindBadge kind={owner.ownerKind} />
                  </td>
                  <td className="py-2 text-xs">
                    {refTypeLabels[owner.ownerRefType] ?? owner.ownerRefType}
                    {owner.ownerRefLabel ? ` · ${owner.ownerRefLabel}` : ''}
                  </td>
                  <td
                    className={`py-2 text-xs ${
                      owner.countsAsOwnedAsset ? 'text-success' : 'text-muted-foreground'
                    }`}
                  >
                    {ownedAssetText(owner.countsAsOwnedAsset)}
                  </td>
                  <td className="py-2 text-xs">{owner.status === 'ACTIVE' ? '启用' : '停用'}</td>
                  <td className="py-2 text-right">
                    <Button
                      variant="outline"
                      size="sm"
                      disabled={!writable || saving || owner.isDefault}
                      onClick={() =>
                        void doToggleStatus(owner.id, owner.status === 'ACTIVE' ? 'INACTIVE' : 'ACTIVE')
                      }
                    >
                      {owner.status === 'ACTIVE' ? '停用' : '启用'}
                    </Button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
        <OwnerEditor
          writable={writable}
          saving={saving}
          onStart={() => {
            setError('');
            setNotice('');
            setSaving(true);
          }}
          onSaved={(message: string) => {
            setNotice(message);
            setSaving(false);
            void load();
          }}
          onError={(message: string) => {
            setError(message);
            setSaving(false);
          }}
        />
      </SectionCard>
    </div>
  );

  /** 货权转移：只改「这批货是谁的」，不动数量、余额与流水。 */
  async function doReassign(
    fromOwnerId: string,
    toOwnerId: string,
    scope: { warehouseId?: string; legalEntityId?: string; skuId?: string },
  ) {
    setSaving(true);
    setError('');
    setNotice('');
    try {
      const input: OwnershipReassignRequest = {
        fromOwnerId,
        toOwnerId,
        warehouseId: scope.warehouseId ?? null,
        legalEntityId: scope.legalEntityId ?? null,
        skuId: scope.skuId ?? null,
      };
      const saved = await reassign(input);
      setNotice(saved.message);
      await load();
    } catch (e) {
      setError(errorText(e));
    } finally {
      setSaving(false);
    }
  }

  /**
   * 作废某一档。列表里的「作废」按钮和建档表单的回调是同一份实现 ——
   * 之前它只以内联 prop 的形式存在，列表那一侧拿不到这个绑定。
   */
  async function doRetire(id: string) {
    try {
      const saved = await retireRule(id);
      setNotice(saved.message);
      void load();
    } catch (e) {
      setError(errorText(e));
    }
  }

  async function doToggleStatus(ownerId: string, status: string) {
    setSaving(true);
    setError('');
    setNotice('');
    try {
      const saved = await setOwnerStatus(ownerId, status);
      setNotice(saved.message);
      await load();
    } catch (e) {
      setError(errorText(e));
    } finally {
      setSaving(false);
    }
  }
}

function OwnerKindBadge({ kind }: { kind: string }) {
  const owned = ownerKindTone(kind) === 'owned';
  return (
    <span
      className={`rounded px-2 py-0.5 text-xs ${
        owned ? 'bg-success-soft text-success' : 'bg-muted text-muted-foreground'
      }`}
    >
      {ownerKindLabels[kind] ?? kind}
    </span>
  );
}

function ScopeFilter({
  options,
  filter,
  onChange,
}: {
  options: OwnershipReferenceOptions | null;
  filter: { warehouseId?: string; legalEntityId?: string; skuId?: string };
  onChange: (next: { warehouseId?: string; legalEntityId?: string; skuId?: string }) => void;
}) {
  const warehouses = (options?.warehouses ?? []).filter(notNull);
  const entities = (options?.legalEntities ?? []).filter(notNull);
  const skus = (options?.skus ?? []).filter(notNull);
  return (
    <div className="flex flex-wrap items-center gap-2">
      <select
        className="border-control-border bg-card rounded-md border px-3 py-2 text-sm"
        value={filter.warehouseId ?? ''}
        onChange={(e) => {
          const { warehouseId: _cleared, ...rest } = filter;
          onChange({ ...rest, ...(e.target.value ? { warehouseId: e.target.value } : {}) });
        }}
      >
        <option value="">全部仓库</option>
        {warehouses.map((w) => (
          <option key={w.id} value={w.id}>
            {w.code} {w.name}
          </option>
        ))}
      </select>
      <select
        className="border-control-border bg-card rounded-md border px-3 py-2 text-sm"
        value={filter.legalEntityId ?? ''}
        onChange={(e) => {
          const { legalEntityId: _cleared, ...rest } = filter;
          onChange({ ...rest, ...(e.target.value ? { legalEntityId: e.target.value } : {}) });
        }}
      >
        <option value="">全部法人</option>
        {entities.map((l) => (
          <option key={l.id} value={l.id}>
            {l.code} {l.name}
          </option>
        ))}
      </select>
      <select
        className="border-control-border bg-card rounded-md border px-3 py-2 text-sm"
        value={filter.skuId ?? ''}
        onChange={(e) => {
          const { skuId: _cleared, ...rest } = filter;
          onChange({ ...rest, ...(e.target.value ? { skuId: e.target.value } : {}) });
        }}
      >
        <option value="">全部 SKU</option>
        {skus.map((s) => (
          <option key={s.id} value={s.id}>
            {s.code} {s.name}
          </option>
        ))}
      </select>
    </div>
  );
}

function RuleEditor({
  writable,
  saving,
  scopeType,
  owners,
  options,
  onStart,
  onSaved,
  onError,
  onCancelSupersede,
  onRetire,
  superseding,
}: {
  writable: boolean;
  saving: boolean;
  scopeType: string;
  owners: StockOwnerPage['owners'];
  options: OwnershipReferenceOptions | null;
  superseding: OwnershipRulePage['rules'][number] | null;
  onCancelSupersede: () => void;
  onStart: () => void;
  onSaved: (message: string) => void;
  onError: (message: string) => void;
  onRetire: (id: string) => Promise<void>;
}) {
  const [open, setOpen] = useState(false);
  const [ownerId, setOwnerId] = useState('');
  const [warehouseId, setWarehouseId] = useState('');
  const [legalEntityId, setLegalEntityId] = useState('');
  const [skuId, setSkuId] = useState('');

  // 换版时预填原档的作用域 —— 换版必须落在同一个范围槽位内，否则原档收口后无处继承。
  useEffect(() => {
    if (!superseding) return;
    setOpen(true);
    setOwnerId(superseding.ownerId);
    setWarehouseId(superseding.warehouseId ?? '');
    setLegalEntityId(superseding.legalEntityId ?? '');
    setSkuId(superseding.skuId ?? '');
  }, [superseding]);

  const activeOwners = owners.filter(notNull).filter((o) => o.status === 'ACTIVE');
  const warehouses = (options?.warehouses ?? []).filter(notNull);
  const entities = (options?.legalEntities ?? []).filter(notNull);
  const skus = (options?.skus ?? []).filter(notNull);

  async function submit() {
    onStart();
    try {
      const input: OwnershipRuleInput = {
        scopeType,
        warehouseId: scopeType === 'WAREHOUSE' ? warehouseId || null : null,
        legalEntityId: scopeType === 'LEGAL_ENTITY' ? legalEntityId || null : null,
        skuId: scopeType === 'SKU' ? skuId || null : null,
        ownerId,
        effectiveFrom: null,
        note: '',
      };
      // 换版 = 收口旧档 + 开新档；没有「就地改」的入口。
      const saved = superseding ? await supersedeRule(superseding.id, input) : await createRule(input);
      onSaved(saved.message);
      setOpen(false);
      setOwnerId('');
    } catch (e) {
      onError(errorText(e));
    }
  }

  return (
    <>
      <Button
        variant="outline"
        size="sm"
        disabled={!writable || saving}
        onClick={() => {
          onCancelSupersede();
          setOpen((v) => !v);
        }}
      >
        {superseding
          ? `换版：新建${scopeTypeLabels[scopeType] ?? scopeType}档`
          : `新建${scopeTypeLabels[scopeType] ?? scopeType}货权档`}
      </Button>
      {open && (
        <div className="border-control-border mt-3 w-full space-y-3 rounded-md border p-4 text-sm">
          <div className="flex flex-wrap items-center gap-2">
            {scopeType === 'WAREHOUSE' && (
              <select
                className="border-control-border bg-card rounded-md border px-3 py-2 text-sm"
                value={warehouseId}
                onChange={(e) => setWarehouseId(e.target.value)}
              >
                <option value="">选仓库</option>
                {warehouses.map((w) => (
                  <option key={w.id} value={w.id}>
                    {w.code} {w.name}
                  </option>
                ))}
              </select>
            )}
            {scopeType === 'LEGAL_ENTITY' && (
              <select
                className="border-control-border bg-card rounded-md border px-3 py-2 text-sm"
                value={legalEntityId}
                onChange={(e) => setLegalEntityId(e.target.value)}
              >
                <option value="">选法人</option>
                {entities.map((l) => (
                  <option key={l.id} value={l.id}>
                    {l.code} {l.name}
                  </option>
                ))}
              </select>
            )}
            {scopeType === 'SKU' && (
              <select
                className="border-control-border bg-card rounded-md border px-3 py-2 text-sm"
                value={skuId}
                onChange={(e) => setSkuId(e.target.value)}
              >
                <option value="">选 SKU</option>
                {skus.map((s) => (
                  <option key={s.id} value={s.id}>
                    {s.code} {s.name}
                  </option>
                ))}
              </select>
            )}
            <select
              className="border-control-border bg-card rounded-md border px-3 py-2 text-sm"
              value={ownerId}
              onChange={(e) => setOwnerId(e.target.value)}
            >
              <option value="">选货主</option>
              {activeOwners.map((o) => (
                <option key={o.id} value={o.id}>
                  {o.name}（{ownerKindLabels[o.ownerKind] ?? o.ownerKind}）
                </option>
              ))}
            </select>
            <Button variant="outline" size="sm" disabled={saving || !ownerId} onClick={() => void submit()}>
              保存
            </Button>
          </div>
          <p className="text-muted-foreground text-xs">
            该档自保存时刻起生效，<strong>只对之后写入的库位明细赋权</strong>；库里已有的货不会自动改判，
            要改判请用上面的「货权漂移 → 改判」。
          </p>
          {superseding && (
            <p className="text-muted-foreground text-xs">
              换版模式：将收口原档的生效区间并开一档新的（作用域保持不变）。历史那一档原样保留，
              「当时这批货归谁」永远查得到。
              <button
                type="button"
                className="ml-2 underline"
                onClick={() => {
                  onCancelSupersede();
                  setOpen(false);
                }}
              >
                取消换版
              </button>
            </p>
          )}
        </div>
      )}
    </>
  );
}

function OwnerEditor({
  writable,
  saving,
  onStart,
  onSaved,
  onError,
}: {
  writable: boolean;
  saving: boolean;
  onStart: () => void;
  onSaved: (message: string) => void;
  onError: (message: string) => void;
}) {
  const [open, setOpen] = useState(false);
  const [code, setCode] = useState('');
  const [name, setName] = useState('');
  const [kind, setKind] = useState('SELF');
  const [refType, setRefType] = useState('LEGAL_ENTITY');
  const [refId, setRefId] = useState('');
  const [options, setOptions] = useState<OwnershipReferenceOptions | null>(null);

  useEffect(() => {
    if (!open) return;
    fetchReferenceOptions()
      .then(setOptions)
      .catch(() => setOptions(null));
  }, [open]);

  const entities = (options?.legalEntities ?? []).filter(notNull);
  const allowed = allowedRefTypes(kind);

  async function submit() {
    onStart();
    try {
      const input: StockOwnerInput = {
        code,
        name,
        ownerKind: kind,
        ownerRefType: refType,
        ownerRefId: refType === 'LEGAL_ENTITY' || refType === 'SUPPLIER' ? refId : null,
        ownerRefLabel: '',
        isDefault: false,
        status: 'ACTIVE',
        note: '',
      };
      const saved = await createOwner(input);
      onSaved(saved.message);
      setOpen(false);
      setCode('');
      setName('');
      setRefId('');
    } catch (e) {
      onError(errorText(e));
    }
  }

  return (
    <div className="mt-4">
      <Button variant="outline" size="sm" disabled={!writable || saving} onClick={() => setOpen((v) => !v)}>
        新建货主
      </Button>
      {open && (
        <div className="border-control-border mt-3 space-y-3 rounded-md border p-4 text-sm">
          <div className="flex flex-wrap items-center gap-2">
            <input
              className="border-control-border bg-card rounded-md border px-3 py-2 text-sm"
              placeholder="编码"
              value={code}
              onChange={(e) => setCode(e.target.value)}
            />
            <input
              className="border-control-border bg-card rounded-md border px-3 py-2 text-sm"
              placeholder="名称"
              value={name}
              onChange={(e) => setName(e.target.value)}
            />
            <select
              className="border-control-border bg-card rounded-md border px-3 py-2 text-sm"
              value={kind}
              onChange={(e) => {
                const next = e.target.value;
                setKind(next);
                // 切换所有权类型时把指向收窄到合法集合 —— 与后端同一套规则就地拦下自相矛盾的组合。
                const permitted = allowedRefTypes(next);
                setRefType(permitted[0] ?? 'INTERNAL');
                setRefId('');
              }}
            >
              {Object.entries(ownerKindLabels).map(([code2, label]) => (
                <option key={code2} value={code2}>
                  {label}
                </option>
              ))}
            </select>
            <select
              className="border-control-border bg-card rounded-md border px-3 py-2 text-sm"
              value={refType}
              onChange={(e) => {
                setRefType(e.target.value);
                setRefId('');
              }}
            >
              {allowed.map((t) => (
                <option key={t} value={t}>
                  {refTypeLabels[t] ?? t}
                </option>
              ))}
            </select>
            {(refType === 'LEGAL_ENTITY' || refType === 'SUPPLIER') && (
              <input
                className="border-control-border bg-card rounded-md border px-3 py-2 text-sm"
                placeholder={
                  refType === 'LEGAL_ENTITY' ? '法人 id（必填）' : '供应商 id（catalog 逻辑 id，必填）'
                }
                value={refId}
                onChange={(e) => setRefId(e.target.value)}
              />
            )}
            <Button
              variant="outline"
              size="sm"
              disabled={
                saving ||
                !code.trim() ||
                !name.trim() ||
                ((refType === 'LEGAL_ENTITY' || refType === 'SUPPLIER') && !refId.trim())
              }
              onClick={() => void submit()}
            >
              保存
            </Button>
          </div>
          <p className="text-muted-foreground text-xs">
            {refType === 'LEGAL_ENTITY' &&
              '法人必须是本租户已登记的；供应商是 catalog 的跨服务逻辑 id（只存 id 与名称快照，不联查对方服务）。'}
          </p>
        </div>
      )}
      {entities.length === 0 && open && (
        <p className="text-muted-foreground mt-2 text-xs">
          本租户尚未登记法人 —— 自有货主可以先用「本租户自身」，但 12 财务将无法把它归到法人资产表。
        </p>
      )}
    </div>
  );
}

function Pager({
  page,
  size,
  total,
  onTurn,
}: {
  page: number;
  size: number;
  total: number;
  onTurn: (page: number) => void;
}) {
  const pages = Math.max(1, Math.ceil(total / Math.max(1, size)));
  if (pages <= 1) return null;
  return (
    <div className="mt-3 flex items-center justify-end gap-2 text-sm">
      <Button variant="outline" size="sm" disabled={page <= 0} onClick={() => onTurn(page - 1)}>
        上一页
      </Button>
      <span className="text-muted-foreground text-xs">
        第 {page + 1} / {pages} 页，共 {total} 条
      </span>
      <Button variant="outline" size="sm" disabled={page + 1 >= pages} onClick={() => onTurn(page + 1)}>
        下一页
      </Button>
    </div>
  );
}