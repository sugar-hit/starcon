'use client';

import { useCallback, useEffect, useMemo, useState, type ReactNode } from 'react';
import { Badge, Button, EmptyState, PageHeader, SectionCard } from '@starcon/ui';
import { Building2, RotateCcw, Save, Store, Warehouse } from 'lucide-react';
import { control } from '@/components/common/fields';
import {
  DIMENSION_LEGAL_ENTITY,
  DIMENSION_SHOP,
  DIMENSION_WAREHOUSE,
  describeContext,
  dimensionLabel,
  fetchDefaultContext,
  fetchDefaultContextCapabilities,
  fetchDefaultContextOptions,
  resetDefaultContext,
  saveDefaultContext,
  selectableLegalEntities,
  selectableShops,
  selectableWarehouses,
  sourceOf,
  staleOf,
  type DefaultContextCapabilities,
  type DefaultContextOptions,
  type DefaultContextSourceStatus,
  type DefaultContextView,
} from './default-context-model';

/**
 * 1.4.2.2 默认店铺与仓库（卷 01 · L3 1.4.2 账号偏好）。
 *
 * <p>蓝图原文：「<b>职责</b>：设定默认进入的店铺与默认操作的法人/仓库，减少每次进入的重复选择。
 * <b>输入</b>：用户选择；用户有权限的店铺/仓库集合。
 * <b>输出</b>：默认上下文，注入后续页面初始筛选。
 * <b>关系</b>：上游 = 02 店铺、08 仓库、17 权限；下游 = 09、07、13 各页面的默认筛选。
 * <b>默认值必须在权限范围内</b>，权限变更后自动失效并回落。」
 *
 * <p>本组件是**真实工作区**，不是占位页：
 *
 * <ul>
 *   <li>三个下拉分别列店铺 / 法人 / 仓库，候选集来自后端（上游授权只读端点）；</li>
 *   <li>已失效的默认项**如实列出来并说明为什么失效**（权限变更 / 停用 / 上游读不到），
 *       而不是悄悄消失 —— 悄悄消失会让人以为「系统把我设的弄丢了」；</li>
 *   <li>某个上游读不到时，那一个维度显示「读不到 + 原因」，另外两个照常可用；</li>
 *   <li>加载 / 错误 / 空三态齐全。</li>
 * </ul>
 *
 * <p><b>不硬编码任何清单</b>：下拉里没有一行是写死的常量。
 */
export function DefaultContextWorkspace() {
  const [view, setView] = useState<DefaultContextView | null>(null);
  const [options, setOptions] = useState<DefaultContextOptions | null>(null);
  const [capabilities, setCapabilities] = useState<DefaultContextCapabilities | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [shopId, setShopId] = useState('');
  const [legalEntityId, setLegalEntityId] = useState('');
  const [warehouseId, setWarehouseId] = useState('');

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const [current, available, declared] = await Promise.all([
        fetchDefaultContext(),
        fetchDefaultContextOptions(),
        fetchDefaultContextCapabilities(),
      ]);
      setView(current);
      setOptions(available);
      setCapabilities(declared);
      setShopId(current.shop?.id ?? '');
      setLegalEntityId(current.legalEntity?.id ?? '');
      setWarehouseId(current.warehouse?.id ?? '');
    } catch (e) {
      setError(e instanceof Error ? e.message : '默认上下文读取失败，请稍后重试');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  const shops = useMemo(() => selectableShops(options), [options]);
  const legalEntities = useMemo(() => selectableLegalEntities(options), [options]);
  const warehouses = useMemo(() => selectableWarehouses(options), [options]);

  const save = useCallback(async () => {
    setSaving(true);
    setError(null);
    setNotice(null);
    try {
      const saved = await saveDefaultContext({
        shopId: shopId === '' ? null : shopId,
        legalEntityId: legalEntityId === '' ? null : legalEntityId,
        warehouseId: warehouseId === '' ? null : warehouseId,
      });
      setView(saved);
      setNotice('已保存。之后的页面会默认落在这几个上下文里。');
    } catch (e) {
      setError(e instanceof Error ? e.message : '保存失败，请检查后重试');
    } finally {
      setSaving(false);
    }
  }, [shopId, legalEntityId, warehouseId]);

  const reset = useCallback(async () => {
    setSaving(true);
    setError(null);
    setNotice(null);
    try {
      await resetDefaultContext();
      setView(null);
      setShopId('');
      setLegalEntityId('');
      setWarehouseId('');
      setNotice('已重置：默认上下文回到「什么都没设」。');
    } catch (e) {
      setError(e instanceof Error ? e.message : '重置失败，请稍后重试');
    } finally {
      setSaving(false);
    }
  }, []);

  if (loading) {
    return <p className="text-copy-13 text-muted-foreground">正在读取你的默认上下文…</p>;
  }

  const empty =
    options !== null && shops.length === 0 && legalEntities.length === 0 && warehouses.length === 0;

  return (
    <div className="space-y-6">
      <PageHeader
        title="默认店铺与仓库"
        description="设定默认进入的店铺，以及默认操作的法人与仓库。之后的页面会用它们作为初始筛选，少选一次。只对你自己生效。"
        actions={
          <div className="flex items-center gap-2">
            {view?.stored ? (
              <Badge tone="success">已保存</Badge>
            ) : (
              <Badge tone="neutral">当前未设置任何默认项</Badge>
            )}
            <Button
              variant="outline"
              onClick={reset}
              disabled={saving}
              aria-label="重置默认上下文"
            >
              <RotateCcw className="h-4 w-4" />
              <span className="ml-1">重置</span>
            </Button>
            <Button onClick={save} disabled={saving}>
              <Save className="h-4 w-4" />
              <span className="ml-1">保存</span>
            </Button>
          </div>
        }
      />

      {error && (
        <div
          role="alert"
          className="border-destructive/30 bg-destructive/5 text-destructive rounded-md border p-3 text-sm"
        >
          {error}
        </div>
      )}
      {notice && (
        <p role="status" className="text-copy-12 text-muted-foreground">
          {notice}
        </p>
      )}

      {view?.note && (
        <p role="status" className="text-copy-12 text-muted-foreground">
          {view.note}
        </p>
      )}

      {empty ? (
        <EmptyState
          icon={<Store className="h-6 w-6" />}
          title="还没有可选的店铺 / 法人 / 仓库"
          description="候选集来自上游系统的授权只读接口。先去 02 渠道与店铺、08 仓储作业、17.1 法人档案把对象建档，再回到这里设默认。"
        />
      ) : (
        <SectionCard
          title="我的默认上下文"
          description="下拉里列出来的就是你有权限操作的清单（上游按数据范围过滤后的结果）。选中的默认项一旦落在权限范围之外，会自动失效并回落。"
        >
          <div className="space-y-5">
            <DimensionPicker
              dimension={DIMENSION_SHOP}
              icon={<Store className="h-4 w-4" />}
              sources={options?.sources}
              stale={staleOf(view, DIMENSION_SHOP)}
              value={shopId}
              onChange={setShopId}
              emptyText="没有可用的店铺（渠道服务返回的店铺清单为空）"
              options={shops.map((shop) => ({
                id: shop.id,
                label: `${shop.code} ${shop.name}${shop.platformName ? `（${shop.platformName}）` : ''}`,
              }))}
            />
            <DimensionPicker
              dimension={DIMENSION_LEGAL_ENTITY}
              icon={<Building2 className="h-4 w-4" />}
              sources={options?.sources}
              stale={staleOf(view, DIMENSION_LEGAL_ENTITY)}
              value={legalEntityId}
              onChange={setLegalEntityId}
              emptyText="没有可用的法人（法人档案为空，或当前角色没有法人读取权限）"
              options={legalEntities.map((entity) => ({
                id: entity.id,
                label: `${entity.code} ${entity.name}`,
              }))}
            />
            <DimensionPicker
              dimension={DIMENSION_WAREHOUSE}
              icon={<Warehouse className="h-4 w-4" />}
              sources={options?.sources}
              stale={staleOf(view, DIMENSION_WAREHOUSE)}
              value={warehouseId}
              onChange={setWarehouseId}
              emptyText="没有可用的仓库（仓储服务返回的仓库清单为空）"
              options={warehouses.map((warehouse) => ({
                id: warehouse.id,
                label: `${warehouse.code} ${warehouse.name}${warehouse.defaultWarehouse ? '（租户默认仓）' : ''}`,
              }))}
            />
          </div>
        </SectionCard>
      )}

      {view && (
        <SectionCard title="当前生效的默认上下文" description="下游页面（09 订单履约 / 07 库存核算 / 13 利润分析）读到的就是这一份。">
          <ul className="text-copy-13 space-y-1">
            {describeContext(view).map((line) => (
              <li key={line} className="text-muted-foreground">
                {line}
              </li>
            ))}
          </ul>
        </SectionCard>
      )}

      {capabilities && (
        <SectionCard
          title="能力声明"
          description="这个模块哪些维度真接通、哪些没接通、缺什么、归谁 —— 逐条写明，不用「正常」「已就绪」这类没有依据的说法。"
        >
          <div className="space-y-4">
            <ul className="space-y-2">
              {(capabilities.sources ?? []).map((source) => (
                <li key={source.dimension} className="text-copy-13 flex flex-wrap items-center gap-2">
                  <Badge tone={source.available ? 'success' : 'warning'}>
                    {dimensionLabel(source.dimension)}
                    {source.available ? ' 已接通' : ' 未接通'}
                  </Badge>
                  <span className="text-muted-foreground">{source.source}</span>
                  {!source.available && source.reason && (
                    <span className="text-muted-foreground">· {source.reason}</span>
                  )}
                  {!source.available && source.ownerModule && (
                    <span className="text-muted-foreground">· 归属：{source.ownerModule}</span>
                  )}
                </li>
              ))}
            </ul>
            <div>
              <p className="text-copy-13 font-medium">判定口径</p>
              <ul className="text-copy-12 text-muted-foreground mt-1 list-disc space-y-1 pl-5">
                {(capabilities.rules ?? []).map((rule) => (
                  <li key={rule}>{rule}</li>
                ))}
              </ul>
            </div>
            <div>
              <p className="text-copy-13 font-medium">边界</p>
              <ul className="text-copy-12 text-muted-foreground mt-1 list-disc space-y-1 pl-5">
                {(capabilities.boundaries ?? []).map((boundary) => (
                  <li key={boundary}>{boundary}</li>
                ))}
              </ul>
            </div>
          </div>
        </SectionCard>
      )}
    </div>
  );
}

/** 一个维度的选择器：下拉 + 来源状态 + 失效说明。 */
function DimensionPicker({
  dimension,
  icon,
  sources,
  stale,
  value,
  onChange,
  options,
  emptyText,
}: {
  dimension: string;
  icon: ReactNode;
  sources: DefaultContextSourceStatus[] | undefined;
  stale: ReturnType<typeof staleOf>;
  value: string;
  onChange: (value: string) => void;
  options: { id: string; label: string }[];
  emptyText: string;
}) {
  const source = sourceOf(sources, dimension);
  const unavailable = source !== undefined && !source.available;
  const label = dimensionLabel(dimension);

  return (
    <div className="space-y-2">
      <label className="text-label-13 flex items-center gap-2" htmlFor={`default-context-${dimension}`}>
        {icon}
        默认{label}
        {source && (
          <Badge tone={source.available ? 'neutral' : 'warning'}>
            {source.available ? '来源已接通' : '来源读不到'}
          </Badge>
        )}
      </label>
      {unavailable && source.reason ? (
        <p className="text-copy-12 text-destructive">{source.reason}</p>
      ) : (
        <select
          id={`default-context-${dimension}`}
          className={control}
          value={value}
          disabled={unavailable || options.length === 0}
          onChange={(event) => onChange(event.target.value)}
        >
          <option value="">不设默认{label}</option>
          {options.map((option) => (
            <option key={option.id} value={option.id}>
              {option.label}
            </option>
          ))}
        </select>
      )}
      {options.length === 0 && !unavailable && (
        <p className="text-copy-12 text-muted-foreground">{emptyText}</p>
      )}
      {stale && (
        <p className="text-copy-12 text-destructive">
          已失效的默认{label}
          {stale.name ? `（${stale.name}）` : ''}：{stale.reason}
        </p>
      )}
    </div>
  );
}
