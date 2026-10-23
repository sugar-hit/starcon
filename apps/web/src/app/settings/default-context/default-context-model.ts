// 相对路径而不是 '@/lib/api'：本仓的 vitest 配置（根 vitest.config.ts）**没有**配 '@/' 别名，
// 用别名会让单元测试在解析模块时就报错（Next 那边能解析，vitest 那边不能）。
import { api } from '../../../lib/api';
import type {
  DefaultContextCapabilities,
  DefaultContextInput,
  DefaultContextLegalEntityOption,
  DefaultContextOptions,
  DefaultContextSelection,
  DefaultContextShopOption,
  DefaultContextSourceStatus,
  DefaultContextStaleSelection,
  DefaultContextView,
  DefaultContextWarehouseOption,
} from '../../../../../../packages/api-contracts/src/index';

/**
 * 1.4.2.2 默认店铺与仓库 —— 前端模型（纯函数 + 接口调用）。
 *
 * <p>蓝图原文：「<b>职责</b>：设定默认进入的店铺与默认操作的法人/仓库，减少每次进入的重复选择。
 * <b>输入</b>：用户选择；用户有权限的店铺/仓库集合。
 * <b>输出</b>：默认上下文，注入后续页面初始筛选。
 * <b>关系</b>：上游 = 02 店铺、08 仓库、17 权限；下游 = 09、07、13 各页面的默认筛选。
 * <b>默认值必须在权限范围内</b>，权限变更后自动失效并回落。」
 *
 * <p><b>类型来自 OpenAPI 契约</b>（{@code packages/api-contracts}，由后端 spec codegen 生成），
 * 前端不另写一套形状 —— 两边不一致的时候，错的永远是手写的那一份。
 *
 * <p><b>本文件不自己算「哪些店铺我能选」</b>：候选集是后端从 channel / warehouse 的
 * 授权只读端点读回来的（那边带数据范围闸），前端只负责渲染与提交。
 * 前端再算一遍权限范围就是造第二套真相 —— 而且一定是错的。
 *
 * <p><b>三个维度各自带来源状态</b>：某一个上游读不到时，另外两个照常可用。
 * 所以界面要按维度显示「读不到」，而不是把整个页面变成空的。
 */

export type {
  DefaultContextCapabilities,
  DefaultContextInput,
  DefaultContextLegalEntityOption,
  DefaultContextOptions,
  DefaultContextSelection,
  DefaultContextShopOption,
  DefaultContextSourceStatus,
  DefaultContextStaleSelection,
  DefaultContextView,
  DefaultContextWarehouseOption,
};

/** 维度标识。 */
export const DIMENSION_SHOP = 'SHOP';
export const DIMENSION_LEGAL_ENTITY = 'LEGAL_ENTITY';
export const DIMENSION_WAREHOUSE = 'WAREHOUSE';

/** 维度的人读名。 */
export function dimensionLabel(dimension: string): string {
  switch (dimension) {
    case DIMENSION_SHOP:
      return '店铺';
    case DIMENSION_LEGAL_ENTITY:
      return '法人';
    case DIMENSION_WAREHOUSE:
      return '仓库';
    default:
      return dimension;
  }
}

/** 维度名 → 视图里的选择字段。 */
export function selectionOf(
  view: DefaultContextView | null,
  dimension: string,
): DefaultContextSelection {
  switch (dimension) {
    case DIMENSION_SHOP:
      return view?.shop ?? null;
    case DIMENSION_LEGAL_ENTITY:
      return view?.legalEntity ?? null;
    case DIMENSION_WAREHOUSE:
      return view?.warehouse ?? null;
    default:
      return null;
  }
}

/** 某个维度的来源状态。 */
export function sourceOf(
  sources: DefaultContextSourceStatus[] | undefined,
  dimension: string,
): DefaultContextSourceStatus | undefined {
  return (sources ?? []).find((source) => source.dimension === dimension);
}

/** 某个维度上已失效的默认项。 */
export function staleOf(
  view: DefaultContextView | null,
  dimension: string,
): DefaultContextStaleSelection | undefined {
  return (view?.stale ?? []).find((item) => item.dimension === dimension);
}

/** 只有 ACTIVE 的候选才能被选中（停用 / 归档的留在清单里但不可选）。 */
export function isSelectable(status: string | null | undefined): boolean {
  return status === 'ACTIVE';
}

/**
 * 可选候选（按维度取，过滤掉停用 / 归档的）。
 *
 * <p>停用的候选**留在清单里但不可选** —— 直接把它们从清单里抹掉的话，
 * 用户会以为这家店凭空消失了。
 */
export function selectableShops(options: DefaultContextOptions | null): DefaultContextShopOption[] {
  return (options?.shops ?? []).filter((shop) => isSelectable(shop.status));
}

export function selectableLegalEntities(
  options: DefaultContextOptions | null,
): DefaultContextLegalEntityOption[] {
  return (options?.legalEntities ?? []).filter((entity) => isSelectable(entity.status));
}

export function selectableWarehouses(
  options: DefaultContextOptions | null,
): DefaultContextWarehouseOption[] {
  return (options?.warehouses ?? []).filter((warehouse) => isSelectable(warehouse.status));
}

/**
 * 当前选中的 id（**按生效值**，失效项不作为选中值）。
 *
 * <p>失效项之所以不预选：它已经不在权限范围内了，把它预选在表单上，
 * 用户会以为它还能用 —— 而保存时后端会拒绝它。
 */
export function selectedId(view: DefaultContextView | null, dimension: string): string {
  return selectionOf(view, dimension)?.id ?? '';
}

/** 把视图渲染成人能读的摘要（设置页用）。 */
export function describeContext(view: DefaultContextView | null): string[] {
  if (!view) return [];
  const lines: string[] = [];
  for (const dimension of [DIMENSION_SHOP, DIMENSION_LEGAL_ENTITY, DIMENSION_WAREHOUSE]) {
    const selection = selectionOf(view, dimension);
    lines.push(
      selection
        ? `默认${dimensionLabel(dimension)}：${selection.code} ${selection.name}`
        : `默认${dimensionLabel(dimension)}：未设置`,
    );
  }
  if (view.stale.length > 0) {
    lines.push(
      `已失效（不再生效）：${view.stale
        .map((item) => `${dimensionLabel(item.dimension)} ${item.name ?? item.id}`)
        .join('、')}`,
    );
  }
  return lines;
}

// ==========================================================================
// 接口调用
// ==========================================================================

function path(suffix = ''): string {
  return `identity/default-context${suffix}`;
}

/** 读当前生效的默认上下文（从没设过时是 200 + stored=false，不是 404）。 */
export function fetchDefaultContext(): Promise<DefaultContextView> {
  return api<DefaultContextView>(path());
}

/** 读三个维度的候选集。 */
export function fetchDefaultContextOptions(): Promise<DefaultContextOptions> {
  return api<DefaultContextOptions>(path('/options'));
}

/** 读能力声明。 */
export function fetchDefaultContextCapabilities(): Promise<DefaultContextCapabilities> {
  return api<DefaultContextCapabilities>(path('/capabilities'));
}

/** 保存（整体覆盖：给 null 的维度等于清空它）。 */
export function saveDefaultContext(input: DefaultContextInput): Promise<DefaultContextView> {
  return api<DefaultContextView>(path(), 'PUT', input);
}

/** 重置（删掉服务端那一行 = 回到「什么都没设」）。 */
export function resetDefaultContext(): Promise<unknown> {
  return api<unknown>(path(), 'DELETE');
}
