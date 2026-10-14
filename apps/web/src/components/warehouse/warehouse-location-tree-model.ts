import type {
  LocationSaved,
  LocationTree,
  LocationTreeCapabilities,
} from '../../../../../packages/api-contracts/src/index';
import { api } from './api';
import { errorText, qty } from './warehouse-facility-model';

/**
 * 8.1.2.1 库位树前端模型。
 *
 * <p>蓝图：「维护库位层级与属性（区 → 巷 → 排 → 架 → 层 → 格），支持容量、承重、
 * 类型（存储/暂存/待检/残次/冻结）、是否可拣。输入：库位增删改与层级调整；防环校验。
 * 输出：库位树 + 占用情况。」
 *
 * <p>本模型处理五件在界面上最容易说错的事：
 *
 * <ul>
 *   <li><b>「没量过容量」与「量过且空置」是两种形状。</b>容量未填时利用率是 {@code null}，
 *       渲染成 0.0% 会让人以为「这个库位量过，是空的」—— 而事实是「从没量过」。
 *   <li><b>手数与预占是两个数，不相加。</b>预占是已被订单锁住但还没走的货。
 *   <li><b>节点占用与子树占用不是同一个数。</b>父节点的「自己占了多少」和「整棵子树占了
 *       多少」要分开显示，否则看区的人会以为货压在区这一层。
 *   <li><b>天然不可拣要说出来</b>（待检 / 残次 / 冻结），这是 8.1.2.2 自动分配的硬规则前提。
 *   <li><b>未交付的自动分配与拣货路径显示「未接入」</b>，不假装这一页能推荐库位。
 * </ul>
 */

/**
 * 树形渲染的一行 = 契约里的一个 {@code LocationNode}（本身已带 depth）。
 *
 * 后端刻意输出**扁平**的 levels 而不是嵌套 children：springdoc 会丢掉自引用的 record 属性，
 * 嵌套 children 在契约里整个消失，前端按类型写了也收不到。depth 已足够表达缩进。
 */
export type LocationRow = LocationTree['levels'][number];

const base = 'warehouse/location-trees';

export function fetchLocationTree(warehouseId: string): Promise<LocationTree> {
  return api<LocationTree>(`${base}/${warehouseId}`);
}

export function fetchLocationTreeCapabilities(): Promise<LocationTreeCapabilities> {
  return api<LocationTreeCapabilities>(`${base}/capabilities`);
}

export interface LocationDraft {
  code: string;
  name: string;
  parentId: string;
  type: string;
  pickable: boolean;
  capacity: string;
  weightLimit: string;
}

/** 新建：parentId 空串表示顶层根节点（「区」），不是漏填。 */
export function createLocation(warehouseId: string, draft: LocationDraft): Promise<LocationSaved> {
  return api<LocationSaved>(`${base}/${warehouseId}/locations`, 'POST', {
    code: draft.code,
    name: draft.name,
    // 空串 → null：根节点
    parentId: draft.parentId === '' ? null : draft.parentId,
    type: draft.type,
    pickable: draft.pickable,
    capacity: draft.capacity === '' ? null : draft.capacity,
    weightLimit: draft.weightLimit === '' ? null : draft.weightLimit,
  });
}

/** 改属性。`move` 为真时附带 parentId（空串 = 升为根节点），否则完全不动层级。 */
export function updateLocation(
  warehouseId: string,
  locationId: string,
  draft: LocationDraft,
  move: boolean,
): Promise<LocationSaved> {
  return api<LocationSaved>(`${base}/${warehouseId}/locations/${locationId}`, 'PUT', {
    name: draft.name,
    type: draft.type,
    pickable: draft.pickable,
    capacity: draft.capacity === '' ? null : draft.capacity,
    weightLimit: draft.weightLimit === '' ? null : draft.weightLimit,
    // parentProvided 是三态开关：不带 = 层级不动；带 true + null = 升为根
    parentProvided: move,
    parentId: move ? (draft.parentId === '' ? null : draft.parentId) : null,
  });
}

export function deleteLocation(warehouseId: string, locationId: string): Promise<void> {
  return api<void>(`${base}/${warehouseId}/locations/${locationId}`, 'DELETE');
}

// ------------------------------------------------------------ 展示

/** 库位类型中文名。与后端 {@code LocationType.label()} 逐条一致。 */
const typeLabels: Record<string, string> = {
  STORAGE: '存储',
  RECEIVING: '收货暂存',
  SHIPPING: '发货暂存',
  QUARANTINE: '待检',
  DEFECTIVE: '残次',
  FROZEN: '冻结',
};

export function locationTypeLabel(type: string): string {
  return typeLabels[type] ?? type;
}

/**
 * 容量利用率文本。
 *
 * <p><b>null（没量过）显示「未量」而不是 0.0%</b> —— 这是本页面最容易说错的一处：
 * 0% 是一句关于事实的断言（「量过了，是空的」），而「未量」是一句关于知识的断言
 * （「没人量过」）。两者混成同一个 0，运营会拿它去排库位。
 */
export function utilizationText(value: number | null | undefined): string {
  if (value === null || value === undefined) return '未量';
  return `${(value * 100).toFixed(1)}%`;
}

/** 容量/承重文本。没量过显示「—」（复用 qty 的缺值形状）。 */
export function measureText(value: number | null | undefined): string {
  return qty(value);
}

export interface OccupancyText {
  onHand: string;
  reserved: string;
  /** 手数与预占**分开渲染**，不合并成一个「占用」数字。 */
  pickableHint: string | null;
}

export function occupancyText(node: LocationRow): OccupancyText {
  const o = node.occupied;
  return {
    onHand: qty(o.onHand),
    reserved: qty(o.reserved),
    pickableHint:
      node.pickableByNature === false && node.pickable
        ? '该类型天然不可拣，此处被手工设为可拣'
        : null,
  };
}

/** 树是否为空 —— 用于区分「空状态」与「加载失败」，两者渲染完全不同。 */
export function isEmptyTree(tree: LocationTree | null): boolean {
  return !tree || tree.levels.length === 0;
}

export { errorText };
