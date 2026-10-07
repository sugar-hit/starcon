'use client';

import { useCallback, useEffect, useState } from 'react';
import { AlertTriangle, Gauge, Layers, RefreshCw, ShieldAlert, ShieldCheck } from 'lucide-react';
import { Button, EmptyState, PageHeader, SectionCard, StatCard } from '@starcon/ui';
import type {
  SellablePolicyDetail,
  SellablePolicyInput,
  SellablePolicyPage,
  SellablePolicySaved,
  SellablePoolDetail,
  SellablePoolSummary,
  SellableQuantityResult,
  SkuRef,
  WarehouseRef,
} from '../../../../../packages/api-contracts/src/catalog';
import { fetchSkus } from './inventory-model';
import {
  allocationText,
  calculate,
  createPolicy,
  errorText,
  fetchPool,
  fetchPools,
  fetchSellablePolicies,
  fetchSellablePolicy,
  fetchWarehouses,
  fromRatio,
  headroomText,
  intervalText,
  notNull,
  percent,
  poolModeLabels,
  qty,
  retirePolicy,
  scopeTypeLabels,
  stockScopeLabels,
  supersedePolicy,
  toRatio,
  verdictTone,
  warehouseKindLabels,
} from './sellable-policy-model';
import { useIdentity } from '../iam/identity-shell';

/**
 * 7.2.2.1 可售量口径工作区。
 *
 * <p>蓝图：「配置不同渠道/店铺的库存口径：是否共享同一池、是否允许超卖多少、平台可售量是按
 * 自有仓还是含 FBA、平台可售数保留缓冲比例」，且「**平台库存回写缓冲是防超卖的最后一道防线**」。
 *
 * <p>界面纪律：
 *
 * <ul>
 *   <li>**共享池体检占第一屏**。这一页存在的理由不是"配几个数字"，而是让运营在回写平台之前
 *       先看到"这几个店铺合起来会不会超卖"。所以判定在最上面，配置表单在下面。
 *   <li>**判定先于数字**。余量为负时，"有意超卖"（TOLERATED）和"配错了"（OVERSOLD）
 *       处置动作完全不同，不能只显示一个负数让人自己猜。
 *   <li>**未配置口径是一个要占版面的空态**。回写 0 = 立刻断货，回写满 = 超卖，
 *       两条路都比拒绝更糟，所以这里显示"拒绝"而不是显示 0。
 *   <li>**历史分档只读**。改口径只能"换版"（收口旧档 + 开新档），页面不给"就地改"的入口 ——
 *       能悄悄改掉一条正在生效的口径，等于把防线本身拆了。
 * </ul>
 */
export function SellablePolicyWorkspace() {
  const { session } = useIdentity();
  const readable = session.permissions.includes('warehouse.inventory.read');
  // 缓冲比例与超卖容忍是经营决策，配错直接超卖：写入走 update 权限。
  const writable = session.permissions.includes('warehouse.inventory.update');

  const [pools, setPools] = useState<SellablePoolSummary[] | null>(null);
  const [policies, setPolicies] = useState<SellablePolicyPage | null>(null);
  const [warehouses, setWarehouses] = useState<WarehouseRef[]>([]);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');

  const [effectiveOnly, setEffectiveOnly] = useState(true);
  const [keyword, setKeyword] = useState('');
  const [page, setPage] = useState(0);
  const [openPool, setOpenPool] = useState<string | null>(null);
  const [poolDetail, setPoolDetail] = useState<SellablePoolDetail | null>(null);
  const [selected, setSelected] = useState<SellablePolicyDetail | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    setError('');
    try {
      const [poolList, policyPage] = await Promise.all([
        fetchPools(),
        fetchSellablePolicies({ effectiveOnly, keyword, page, size: 20 }),
      ]);
      setPools(poolList);
      setPolicies(policyPage);
    } catch (e) {
      setError(errorText(e));
    } finally {
      setLoading(false);
    }
  }, [effectiveOnly, keyword, page]);

  useEffect(() => {
    if (!readable) return;
    void load();
  }, [readable, load]);

  useEffect(() => {
    if (!readable) return;
    fetchWarehouses()
      .then(setWarehouses)
      .catch(() => setWarehouses([]));
  }, [readable]);

  const openPolicy = useCallback(async (id: string) => {
    setError('');
    try {
      setSelected(await fetchSellablePolicy(id));
    } catch (e) {
      setError(errorText(e));
    }
  }, []);

  const showPool = useCallback(async (poolKey: string) => {
    setError('');
    try {
      setPoolDetail(await fetchPool(poolKey));
      setOpenPool(poolKey);
    } catch (e) {
      setError(errorText(e));
    }
  }, []);

  if (!readable) {
    return (
      <EmptyState
        icon={<ShieldAlert className="h-6 w-6" />}
        title="没有可售量口径的查看权限"
        description="当前角色未被授予 warehouse.inventory.read，请联系管理员在角色中开启该页面。"
      />
    );
  }

  const poolList = (pools ?? []).filter(notNull);
  const dangerous = poolList.filter((p) => p.verdict === 'OVERSOLD' || p.verdict === 'NO_HEADROOM');

  return (
    <div className="space-y-6">
      <PageHeader
        title="可售量口径"
        description="按渠道/店铺配置库存口径：共享池、超卖容忍、平台可售量是否含 FBA、缓冲比例。缓冲是防超卖的最后一道防线。"
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

      <div className="grid gap-4 md:grid-cols-3">
        <StatCard
          label={effectiveOnly ? '在效口径' : '口径分档'}
          value={policies === null ? '—' : policies.total}
          polarity="neutral"
          comparisonLabel={effectiveOnly ? '只统计此刻仍在生效的档' : '含已收口的历史档'}
        />
        <StatCard
          label="池总数"
          value={pools === null ? '—' : poolList.length}
          polarity="neutral"
          comparisonLabel="同一池名的多档口径扣的是同一批货"
        />
        <StatCard
          label="需要处理"
          value={pools === null ? '—' : dangerous.length}
          polarity={dangerous.length > 0 ? 'negative' : 'positive'}
          comparisonLabel="零余量或必然超卖：回写前必须先改缓冲"
        />
      </div>

      <SectionCard
        title="共享池体检"
        description="各成员的回写额合起来必须小于池容量。分配比 ≥ 100% 就是没有余量，同步延迟期间会超卖。"
        extra={
          <Button variant="outline" size="sm" onClick={() => void load()} disabled={loading}>
            <RefreshCw className={loading ? 'mr-2 h-3.5 w-3.5 animate-spin' : 'mr-2 h-3.5 w-3.5'} />
            刷新
          </Button>
        }
      >
        {pools === null || pools.length === 0 ? (
          <EmptyState
            icon={<Layers className="h-5 w-5" />}
            title={loading ? '加载中' : '还没有配置任何口径'}
            description={
              loading
                ? '正在读取共享池与安全判定。'
                : '先在下面新建一档口径。池里没有成员时不做判定 —— 没有配置不是"安全"。'
            }
          />
        ) : (
          <table className="w-full text-sm">
            <thead className="text-muted-foreground text-left text-xs">
              <tr>
                <th className="py-2">判定</th>
                <th className="py-2">池</th>
                <th className="py-2">模式</th>
                <th className="py-2 text-right">成员</th>
                <th className="py-2 text-right">仓</th>
                <th className="py-2 text-right">缓冲合计</th>
                <th className="py-2 text-right">分配比</th>
                <th className="py-2 text-right">余量</th>
                <th className="py-2" />
              </tr>
            </thead>
            <tbody>
              {pools.filter(notNull).map((pool) => (
                <tr key={pool.poolKey} className="border-control-border border-t">
                  <td className="py-2">
                    <VerdictBadge verdict={pool.verdict} />
                  </td>
                  <td className="py-2 font-mono text-xs">{pool.poolKey}</td>
                  <td className="py-2 text-xs">{poolModeLabels[pool.poolMode] ?? pool.poolMode}</td>
                  <td className="py-2 text-right">{pool.memberCount}</td>
                  <td className="py-2 text-right">{pool.warehouseCount}</td>
                  <td className="py-2 text-right">{percent(pool.bufferRatioSum)}</td>
                  <td className="py-2 text-right">{allocationText(pool.allocationRatio)}</td>
                  <td className="py-2 text-right">{headroomText(pool.headroom)}</td>
                  <td className="py-2 text-right">
                    <Button variant="outline" size="sm" onClick={() => void showPool(pool.poolKey)}>
                      查看成员
                    </Button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
        {openPool && poolDetail ? <PoolDetail detail={poolDetail} /> : null}
      </SectionCard>

      <PolicyEditor
        writable={writable}
        warehouses={warehouses}
        selected={selected}
        onSaved={(saved: SellablePolicySaved) => {
          setNotice(saved.message);
          setSaving(false);
          void load();
        }}
        onError={(message: string) => {
          setError(message);
          setSaving(false);
        }}
        onStartSaving={() => {
          setError('');
          setNotice('');
          setSaving(true);
        }}
        onSupersede={async (id, input) => {
          try {
            const saved = await supersedePolicy(id, input);
            setNotice(saved.message);
            setSaving(false);
            void load();
          } catch (e) {
            setError(errorText(e));
            setSaving(false);
          }
        }}
        onRetire={async (id) => {
          try {
            const saved = await retirePolicy(id);
            setNotice(saved.message);
            void load();
          } catch (e) {
            setError(errorText(e));
          }
        }}
        saving={saving}
      />

      <SectionCard
        title="口径分档"
        description="改口径 = 收口旧档 + 开新档，历史各档原样保留。已收口的档永远读得到当时的口径。"
        extra={
          <div className="flex items-center gap-2">
            <input
              className="border-control-border bg-card rounded-md border px-3 py-2 text-sm"
              placeholder="搜索店铺名 / 池名 / 备注"
              value={keyword}
              onChange={(e) => {
                setKeyword(e.target.value);
                setPage(0);
              }}
            />
            <label className="text-muted-foreground flex items-center gap-1 text-xs">
              <input
                type="checkbox"
                checked={effectiveOnly}
                onChange={(e) => {
                  setEffectiveOnly(e.target.checked);
                  setPage(0);
                }}
              />
              只看在效
            </label>
          </div>
        }
      >
        {policies === null || policies.policies.length === 0 ? (
          <EmptyState
            icon={<Gauge className="h-5 w-5" />}
            title={loading ? '加载中' : keyword ? '没有匹配的口径' : '还没有配置口径'}
            description={
              loading
                ? '正在读取口径分档。'
                : keyword
                  ? '换个关键字试试。'
                  : '新建一档口径后，7.1.1.3 可用量计算与 04 平台回写才有依据。'
            }
          />
        ) : (
          <>
            <table className="w-full text-sm">
              <thead className="text-muted-foreground text-left text-xs">
                <tr>
                  <th className="py-2">范围</th>
                  <th className="py-2">对象</th>
                  <th className="py-2">池</th>
                  <th className="py-2">平台口径</th>
                  <th className="py-2 text-right">缓冲</th>
                  <th className="py-2 text-right">超卖容忍</th>
                  <th className="py-2">含在途</th>
                  <th className="py-2">生效区间</th>
                  <th className="py-2" />
                </tr>
              </thead>
              <tbody>
                {policies.policies.filter(notNull).map((row) => (
                  <tr key={row.id} className="border-control-border border-t">
                    <td className="py-2">{scopeTypeLabels[row.scopeType] ?? row.scopeType}</td>
                    <td className="py-2">{row.referenceLabel || '—'}</td>
                    <td className="py-2 font-mono text-xs">{row.poolKey}</td>
                    <td className="py-2 text-xs">
                      {stockScopeLabels[row.platformStockScope] ?? row.platformStockScope}
                    </td>
                    <td className="py-2 text-right">{percent(row.bufferRatio)}</td>
                    <td className="py-2 text-right">
                      {row.oversellAllowed ? qty(row.oversellTolerance) : '不允许'}
                    </td>
                    <td className="py-2 text-xs">{row.includeInTransit ? '计入' : '不计入'}</td>
                    <td className="text-muted-foreground py-2 text-xs">
                      {intervalText(row.effectiveFrom, row.effectiveTo)}
                    </td>
                    <td className="py-2 text-right">
                      <Button variant="outline" size="sm" onClick={() => void openPolicy(row.id)}>
                        查看 / 换版
                      </Button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
            <Pager
              page={policies.page}
              size={policies.size}
              total={policies.total}
              onTurn={setPage}
            />
          </>
        )}
      </SectionCard>

      {selected ? (
        <SectionCard
          title="可售量计算"
          description="按当前生效口径算出每个 SKU 的平台可售数与池余量。没有口径时返回空并拒绝回写，而不是回写 0。"
        >
          <CalculationPanel detail={selected} />
        </SectionCard>
      ) : null}
    </div>
  );
}

/** 判定徽标。三个危险档同族暖色 —— 它们都要人看一眼；只有 GUARDED 用中性色。 */
function VerdictBadge({ verdict }: { verdict: string }) {
  const tone = verdictTone[verdict] ?? 'ok';
  const label: Record<string, string> = {
    GUARDED: '有余量',
    NO_HEADROOM: '零余量',
    TOLERATED: '有意超卖',
    OVERSOLD: '必然超卖',
  };
  const cls =
    tone === 'ok'
      ? 'border-success/40 bg-success-soft text-success'
      : tone === 'warn'
        ? 'border-warning/40 bg-warning-soft text-warning'
        : 'border-destructive/40 bg-destructive-soft text-destructive';
  const Icon = tone === 'ok' ? ShieldCheck : ShieldAlert;
  return (
    <span className={`inline-flex items-center gap-1 rounded-md border px-2 py-0.5 text-xs ${cls}`}>
      <Icon className="h-3 w-3" />
      {label[verdict] ?? verdict}
    </span>
  );
}

function PoolDetail({ detail }: { detail: SellablePoolDetail }) {
  return (
    <div className="border-control-border mt-4 space-y-3 rounded-md border p-4">
      <p className="text-sm">{detail.note}</p>
      {detail.excludedPlatformQuantity > 0 ? (
        <p className="text-muted-foreground text-xs">
          被平台口径排除的 FBA 量：{qty(detail.excludedPlatformQuantity)} 件。排除而不是静默丢弃 ——
          不报出来会让人以为 FBA 已经被计入池容量。
        </p>
      ) : null}
      <table className="w-full text-sm">
        <thead className="text-muted-foreground text-left text-xs">
          <tr>
            <th className="py-1">对象</th>
            <th className="py-1 text-right">缓冲</th>
            <th className="py-1 text-right">超卖容忍</th>
            <th className="py-1">平台口径</th>
            <th className="py-1">含在途</th>
            <th className="py-1 text-right">挂仓</th>
          </tr>
        </thead>
        <tbody>
          {detail.members.filter(notNull).map((m) => (
            <tr key={m.policyId} className="border-control-border border-t">
              <td className="py-1">
                {m.referenceLabel || scopeTypeLabels[m.scopeType] || m.scopeType}
              </td>
              <td className="py-1 text-right">{percent(m.bufferRatio)}</td>
              <td className="py-1 text-right">
                {m.oversellAllowed ? qty(m.oversellTolerance) : '不允许'}
              </td>
              <td className="py-1 text-xs">
                {stockScopeLabels[m.platformStockScope] ?? m.platformStockScope}
              </td>
              <td className="py-1 text-xs">{m.includeInTransit ? '计入' : '不计入'}</td>
              <td className="py-1 text-right">{m.warehouseCount}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

/** 新建 / 换版表单。换版时带上被换掉那一档的当前值作为初值。 */
function PolicyEditor({
  writable,
  warehouses,
  selected,
  saving,
  onSaved,
  onError,
  onStartSaving,
  onSupersede,
  onRetire,
}: {
  writable: boolean;
  warehouses: WarehouseRef[];
  selected: SellablePolicyDetail | null;
  saving: boolean;
  onSaved: (saved: SellablePolicySaved) => void;
  onError: (message: string) => void;
  onStartSaving: () => void;
  onSupersede: (id: string, input: SellablePolicyInput) => Promise<void>;
  onRetire: (id: string) => Promise<void>;
}) {
  const current = selected?.policy ?? null;
  const [scopeType, setScopeType] = useState('SHOP');
  const [sourceSystem, setSourceSystem] = useState('CHANNEL');
  const [referenceId, setReferenceId] = useState('');
  const [referenceLabel, setReferenceLabel] = useState('');
  const [poolMode, setPoolMode] = useState('EXCLUSIVE');
  const [poolKey, setPoolKey] = useState('');
  const [buffer, setBuffer] = useState('20');
  const [stockScope, setStockScope] = useState('OWN_WAREHOUSE');
  const [inTransit, setInTransit] = useState(false);
  const [oversellAllowed, setOversellAllowed] = useState(false);
  const [tolerance, setTolerance] = useState('0');
  const [boundWarehouses, setBoundWarehouses] = useState<string[]>([]);
  const [note, setNote] = useState('');

  // 选中一档时把它的值灌进表单：换版 = 以当前档为初值改一改，而不是从空白重填。
  useEffect(() => {
    if (!current) return;
    setScopeType(current.scopeType);
    setReferenceId(current.referenceId ?? '');
    setReferenceLabel(current.referenceLabel);
    setPoolMode(current.poolMode);
    setPoolKey(current.poolMode === 'SHARED' ? current.poolKey : '');
    setBuffer(fromRatio(current.bufferRatio));
    setStockScope(current.platformStockScope);
    setInTransit(current.includeInTransit);
    setOversellAllowed(current.oversellAllowed);
    setTolerance(String(current.oversellTolerance ?? 0));
    setBoundWarehouses(selected?.warehouses.map((w) => w.warehouseId) ?? []);
  }, [current, selected]);

  const submit = async () => {
    const ratio = toRatio(buffer);
    if (ratio === null) {
      onError('缓冲比例必须是 0 到 100 之间的数字（按百分比填写）');
      return;
    }
    const input = {
      scopeType,
      sourceSystem: scopeType === 'DEFAULT' ? '' : sourceSystem,
      referenceId: scopeType === 'DEFAULT' ? null : referenceId.trim() || null,
      referenceLabel: referenceLabel.trim(),
      poolMode,
      poolKey: poolMode === 'SHARED' ? poolKey.trim() : null,
      oversellAllowed,
      oversellTolerance: Number(tolerance || 0),
      platformStockScope: stockScope,
      bufferRatio: ratio,
      includeInTransit: inTransit,
      effectiveFrom: null,
      note: note.trim(),
      warehouses: boundWarehouses.map((id) => ({
        warehouseId: id,
        warehouseKind:
          selected?.warehouses.find((w) => w.warehouseId === id)?.warehouseKind ??
          (warehouses.find((w) => w.id === id)?.code === 'FBA' ? 'PLATFORM_FBA' : 'OWN'),
      })),
    } as SellablePolicyInput;
    onStartSaving();
    try {
      if (current) await onSupersede(current.id, input);
      else onSaved(await createPolicy(input));
    } catch (e) {
      onError(errorText(e));
    }
  };

  return (
    <SectionCard
      title={
        current
          ? `换版：${current.referenceLabel || scopeTypeLabels[current.scopeType]}`
          : '新建口径'
      }
      description={
        current
          ? '换版会收口当前这一档并开新的一档 —— 旧值保留在历史里，随时能查到当时那道防线长什么样。'
          : '店铺/渠道在 02 渠道与店铺服务里，这里只存它的逻辑 id（跨服务不建外键）。'
      }
    >
      <div className="grid gap-3 md:grid-cols-2 xl:grid-cols-4">
        <label className="space-y-1 text-sm">
          <span className="block font-medium">生效范围</span>
          <select
            className="border-control-border bg-card w-full rounded-md border px-3 py-2 text-sm"
            value={scopeType}
            onChange={(e) => setScopeType(e.target.value)}
          >
            <option value="SHOP">按店铺</option>
            <option value="CHANNEL">按渠道</option>
            <option value="DEFAULT">全租户兜底</option>
          </select>
        </label>
        <label className="space-y-1 text-sm">
          <span className="block font-medium">来源系统</span>
          <input
            className="border-control-border bg-card w-full rounded-md border px-3 py-2 text-sm"
            value={sourceSystem}
            disabled={scopeType === 'DEFAULT'}
            onChange={(e) => setSourceSystem(e.target.value)}
          />
        </label>
        <label className="space-y-1 text-sm">
          <span className="block font-medium">对象 ID（店铺/渠道）</span>
          <input
            className="border-control-border bg-card w-full rounded-md border px-3 py-2 text-sm"
            value={referenceId}
            disabled={scopeType === 'DEFAULT'}
            onChange={(e) => setReferenceId(e.target.value)}
          />
        </label>
        <label className="space-y-1 text-sm">
          <span className="block font-medium">对象名称</span>
          <input
            className="border-control-border bg-card w-full rounded-md border px-3 py-2 text-sm"
            value={referenceLabel}
            onChange={(e) => setReferenceLabel(e.target.value)}
          />
        </label>

        <label className="space-y-1 text-sm">
          <span className="block font-medium">共享池策略</span>
          <select
            className="border-control-border bg-card w-full rounded-md border px-3 py-2 text-sm"
            value={poolMode}
            onChange={(e) => setPoolMode(e.target.value)}
          >
            <option value="EXCLUSIVE">独占池（自己一个池）</option>
            <option value="SHARED">共享池（与同名池共用）</option>
          </select>
        </label>
        <label className="space-y-1 text-sm">
          <span className="block font-medium">池名（仅共享池）</span>
          <input
            className="border-control-border bg-card w-full rounded-md border px-3 py-2 text-sm"
            value={poolKey}
            disabled={poolMode === 'EXCLUSIVE'}
            placeholder="例如 US-POOL"
            onChange={(e) => setPoolKey(e.target.value)}
          />
        </label>
        <label className="space-y-1 text-sm">
          <span className="block font-medium">缓冲比例（%）</span>
          <input
            className="border-control-border bg-card w-full rounded-md border px-3 py-2 text-sm"
            value={buffer}
            onChange={(e) => setBuffer(e.target.value)}
          />
        </label>
        <label className="space-y-1 text-sm">
          <span className="block font-medium">平台可售量口径</span>
          <select
            className="border-control-border bg-card w-full rounded-md border px-3 py-2 text-sm"
            value={stockScope}
            onChange={(e) => setStockScope(e.target.value)}
          >
            <option value="OWN_WAREHOUSE">仅自有仓</option>
            <option value="OWN_PLUS_FBA">自有仓 + FBA</option>
          </select>
        </label>
      </div>

      <div className="mt-3 grid gap-3 md:grid-cols-2 xl:grid-cols-4">
        <label className="flex items-center gap-2 text-sm">
          <input
            type="checkbox"
            checked={inTransit}
            onChange={(e) => setInTransit(e.target.checked)}
          />
          池容量含在途
        </label>
        <label className="flex items-center gap-2 text-sm">
          <input
            type="checkbox"
            checked={oversellAllowed}
            onChange={(e) => setOversellAllowed(e.target.checked)}
          />
          允许超卖
        </label>
        <label className="space-y-1 text-sm">
          <span className="block font-medium">超卖容忍（件）</span>
          <input
            className="border-control-border bg-card w-full rounded-md border px-3 py-2 text-sm"
            value={tolerance}
            disabled={!oversellAllowed}
            onChange={(e) => setTolerance(e.target.value)}
          />
        </label>
        <label className="space-y-1 text-sm">
          <span className="block font-medium">备注</span>
          <input
            className="border-control-border bg-card w-full rounded-md border px-3 py-2 text-sm"
            value={note}
            onChange={(e) => setNote(e.target.value)}
          />
        </label>
      </div>

      <div className="mt-3 space-y-1">
        <span className="block text-sm font-medium">挂仓（池容量的构成单元）</span>
        <p className="text-muted-foreground text-xs">
          逐个仓标注它是自有仓还是平台仓：口径设为「仅自有仓」时，FBA 会被排除在池容量外。
        </p>
        <div className="flex flex-wrap gap-3 pt-1">
          {warehouses.length === 0 ? (
            <span className="text-muted-foreground text-xs">还没有可选的仓库。</span>
          ) : (
            warehouses.map((w) => (
              <label key={w.id} className="flex items-center gap-1 text-sm">
                <input
                  type="checkbox"
                  checked={boundWarehouses.includes(w.id)}
                  onChange={(e) =>
                    setBoundWarehouses(
                      e.target.checked
                        ? [...boundWarehouses, w.id]
                        : boundWarehouses.filter((id) => id !== w.id),
                    )
                  }
                />
                {w.code} {w.name}
              </label>
            ))
          )}
        </div>
        {selected?.warehouses.length ? (
          <p className="text-muted-foreground text-xs">
            当前档的仓归属：
            {selected.warehouses
              .map(
                (w) =>
                  `${w.warehouseCode}（${warehouseKindLabels[w.warehouseKind] ?? w.warehouseKind}）`,
              )
              .join('、')}
          </p>
        ) : null}
      </div>

      <div className="mt-4 flex flex-wrap items-center gap-2">
        <Button onClick={() => void submit()} disabled={!writable || saving}>
          {saving ? '保存中…' : current ? '保存为新的一档' : '新建口径'}
        </Button>
        {current && !current.effectiveTo ? (
          <Button variant="outline" disabled={!writable} onClick={() => void onRetire(current.id)}>
            作废当前档
          </Button>
        ) : null}
        {!writable ? (
          <span className="text-muted-foreground text-xs">
            改缓冲与超卖容忍需要 warehouse.inventory.update 权限
          </span>
        ) : null}
      </div>
    </SectionCard>
  );
}

/**
 * 可售量计算。
 *
 * <p>SKU 从下拉里选：口径是店铺维度的，但可售量必须落到具体 SKU 上才有数 —— 所以这一段
 * 不做"用某个固定 SKU 演示"的事，空集合由后端 400 拒掉，界面则干脆不给提交按钮。
 *
 * <p>没配口径时后端返回 {@code effective.policy == null}，这里显示"拒绝回写"而不是一个 0：
 * 回写 0 是立刻断货，回写满是超卖，两条路都比拒绝更糟。
 */
function CalculationPanel({ detail }: { detail: SellablePolicyDetail }) {
  const policy = detail.policy;
  const [skus, setSkus] = useState<SkuRef[]>([]);
  const [picked, setPicked] = useState<string[]>([]);
  const [result, setResult] = useState<SellableQuantityResult | null>(null);
  const [running, setRunning] = useState(false);
  const [error, setError] = useState('');

  useEffect(() => {
    fetchSkus()
      .then(setSkus)
      .catch(() => setSkus([]));
  }, []);

  // 收窄放在**所有 Hook 之后**：Hooks 必须在每次渲染里以同样的顺序调用，
  // 提前 return 会让后面的 useState/useEffect 变成条件调用。
  // codegen 约定会让整个类型别名带上 `| null`（见 sellable-policy-model 的 notNull 注释），
  // 所以这里必须显式收窄，而不是靠非空断言糊过去。
  if (!policy) {
    return (
      <EmptyState
        icon={<Gauge className="h-5 w-5" />}
        title="这一档读不出来"
        description="口径详情缺少分档本身，通常是它在读的过程中被收口了。刷新后重试。"
      />
    );
  }
  const referenceId = policy.referenceId ?? '';

  const run = async () => {
    if (picked.length === 0) return;
    setRunning(true);
    setError('');
    try {
      setResult(await calculate({ skuIds: picked, referenceId, scopeType: policy.scopeType }));
    } catch (e) {
      setError(errorText(e));
      setResult(null);
    } finally {
      setRunning(false);
    }
  };

  return (
    <div className="space-y-3">
      <p className="text-muted-foreground text-sm">
        当前档：{policy.referenceLabel || scopeTypeLabels[policy.scopeType]} · 缓冲{' '}
        {percent(policy.bufferRatio)} ·{' '}
        {stockScopeLabels[policy.platformStockScope] ?? policy.platformStockScope} · 池{' '}
        {policy.poolKey}
      </p>

      {skus.length === 0 ? (
        <EmptyState
          icon={<Gauge className="h-5 w-5" />}
          title="没有可选的 SKU"
          description="先在商品中心维护 SKU，才能按 SKU 试算可售量。"
        />
      ) : (
        <>
          <label className="space-y-1 text-sm">
            <span className="block font-medium">选择 SKU（可多选）</span>
            <select
              multiple
              className="border-control-border bg-card h-32 w-full rounded-md border px-3 py-2 text-sm"
              value={picked}
              onChange={(e) => setPicked(Array.from(e.target.selectedOptions).map((o) => o.value))}
            >
              {skus.map((s) => (
                <option key={s.id} value={s.id}>
                  {s.code} {s.name}
                </option>
              ))}
            </select>
          </label>
          <Button
            onClick={() => void run()}
            disabled={running || picked.length === 0 || !referenceId}
          >
            {running ? '试算中…' : `试算 ${picked.length} 个 SKU`}
          </Button>
          {!referenceId ? (
            <p className="text-muted-foreground text-xs">
              该档是全租户兜底档，没有绑定店铺，无法按店铺试算。
            </p>
          ) : null}
        </>
      )}

      {error && (
        <div className="border-destructive/40 bg-destructive-soft text-destructive rounded-md border p-4 text-sm">
          {error}
        </div>
      )}

      {result === null ? (
        !error && (
          <EmptyState
            icon={<Gauge className="h-5 w-5" />}
            title="还没有试算结果"
            description="试算按当前生效口径算一遍池容量、缓冲与可回写件数，用来在回写平台之前先看一眼。"
          />
        )
      ) : result.effective?.policy == null ? (
        <div className="border-destructive/40 bg-destructive-soft text-destructive rounded-md border p-4 text-sm">
          <p className="font-medium">没有生效的口径，平台可售数没有依据。</p>
          <p className="mt-1">{result.effective?.note}</p>
        </div>
      ) : (
        <div className="space-y-3">
          <div className="flex flex-wrap items-center gap-3 text-sm">
            <VerdictBadge verdict={result.verdict ?? 'GUARDED'} />
            <span className="text-muted-foreground">
              池容量 {qty(result.totalPoolCapacity)} · 可回写 {qty(result.totalPlatformListing)} ·{' '}
              {headroomText(result.pool?.headroom)}
            </span>
            {result.effective?.usedFallback ? (
              <span className="border-warning/40 bg-warning-soft text-warning rounded-md border px-2 py-0.5 text-xs">
                用的是全租户兜底档
              </span>
            ) : null}
          </div>
          {result.effective?.note ? (
            <p className="text-muted-foreground text-xs">{result.effective.note}</p>
          ) : null}
          <table className="w-full text-sm">
            <thead className="text-muted-foreground text-left text-xs">
              <tr>
                <th className="py-1">SKU</th>
                <th className="py-1 text-right">合格在手</th>
                <th className="py-1 text-right">在途计入</th>
                <th className="py-1 text-right">池容量</th>
                <th className="py-1 text-right">缓冲留存</th>
                <th className="py-1 text-right">可回写</th>
              </tr>
            </thead>
            <tbody>
              {result.rows.filter(notNull).map((row) => (
                <tr key={row.skuId} className="border-control-border border-t">
                  <td className="py-1">{row.skuCode}</td>
                  <td className="py-1 text-right">{qty(row.qualifiedOnHand)}</td>
                  <td className="py-1 text-right">{qty(row.inTransitAdded)}</td>
                  <td className="py-1 text-right">{qty(row.poolCapacity)}</td>
                  <td className="py-1 text-right">{qty(row.bufferReserved)}</td>
                  <td className="py-1 text-right font-medium">{qty(row.platformListing)}</td>
                </tr>
              ))}
            </tbody>
          </table>
          <p className="text-muted-foreground text-xs">{result.note}</p>
        </div>
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
      <Button
        variant="outline"
        size="sm"
        disabled={page + 1 >= pages}
        onClick={() => onTurn(page + 1)}
      >
        下一页
      </Button>
    </div>
  );
}
