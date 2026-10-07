/**
 * 17.5.2.1 数据字典的展示口径与前端预校验。
 *
 * <p>后端 {@code DictionaryController} 是最终判定（编码一旦被历史数据引用就锁死、
 * 字典项只能停用不能删除），这里的校验与后端同口径同顺序，目的是让用户在提交前就看到
 * 「为什么不行」，而不是提交后吃一个 400。后端仍然是权威：即使前端校验放过，后端也会拒。
 *
 * <p>本模块最容易被前端做错的一件事，是把「删除」当成字典项的常规操作。这里刻意
 * **不提供删除入口**：被引用后只能停用，否则历史单据的下拉会渲染成空白。
 * {@link disableActions} 把这条纪律写成一个纯函数，页面上没有删除按钮就是它在起作用。
 */

export type ItemKind = 'STATUS_ENUM' | 'REASON_CODE' | 'CATEGORY_LABEL' | 'UNIT_TYPE';
export type ItemStatus = 'ACTIVE' | 'DISABLED';

export const ITEM_KIND_LABELS: Record<string, string> = {
  STATUS_ENUM: '状态枚举',
  REASON_CODE: '原因码',
  CATEGORY_LABEL: '分类标签',
  UNIT_TYPE: '单位类型',
};

export const ITEM_STATUS_LABELS: Record<string, string> = {
  ACTIVE: '启用',
  DISABLED: '停用',
};

export const ACTION_LABELS: Record<string, string> = {
  CREATED: '新建',
  UPDATED: '编辑',
  DEACTIVATED: '停用',
  REACTIVATED: '恢复',
};

/** 字典项的最小形状：前端只需要展示与校验用到的这些字段。 */
export type DictItemLike = {
  id: string;
  dictCode: string;
  itemCode: string;
  name: string;
  status: string;
  referenceCount: number;
  referencingSystems: number;
  referenced: boolean;
  codeLocked: boolean;
};

/** 变更历史一行的最小形状。 */
export type HistoryRow = {
  action: string;
  beforeName?: string | null;
  afterName?: string | null;
  beforeDescription?: string | null;
  afterDescription?: string | null;
  beforeStatus?: string | null;
  afterStatus?: string | null;
  reason: string;
  itemCode: string;
};

/**
 * 编码格式：大写字母开头，后接 1–63 位字母数字下划线。
 *
 * 与后端 {@code DictionaryController.checkCode} 的正则逐字对应。两处必须同时改——
 * 只改一处会出现「前端放行、后端 400」或「前端拦下、后端其实允许」两种错位。
 */
export const ITEM_CODE_PATTERN = /^[A-Z][A-Z0-9_]{1,63}$/;

/**
 * 校验字典项编码。返回空串表示通过。
 *
 * <p>编码会进单据、报表与导出，口径与编号前缀一致：小写与空格会让同一个字典项
 * 在不同系统里出现两种写法，跨服务对齐时永远对不上。
 */
export function validateItemCode(code: string): string {
  const value = code.trim();
  if (!value) return '字典项编码不能为空';
  if (!ITEM_CODE_PATTERN.test(value))
    return '编码需为大写字母开头的标识符（2–64 位字母、数字或下划线）';
  return '';
}

/** 校验字典项名称。空名称会让历史单据渲染成空白标签，所以必填。 */
export function validateItemName(name: string): string {
  return name.trim() ? '' : '字典项名称不能为空';
}

/**
 * 校验一次编辑是否可以提交。
 *
 * <p>被引用后编码锁死：返回「因为被引用所以不能改编码」这个**具体**原因，
 * 而不是笼统的「保存失败」——用户需要知道该改名称还是先去清理引用。
 *
 * @param original 编辑前的字典项；为 null 表示新建
 * @param nextCode 用户填的编码；留空表示「不改编码」
 */
export function validateItemEdit(
  original: DictItemLike | null,
  nextCode: string,
  nextName: string,
): string {
  const nameProblem = validateItemName(nextName);
  if (nameProblem) return nameProblem;
  if (!original) return validateItemCode(nextCode);
  // 编辑时留空表示不改编码；显式传了别的编码才走引用判定
  if (!nextCode.trim()) return '';
  const codeProblem = validateItemCode(nextCode);
  if (codeProblem) return codeProblem;
  if (nextCode.trim() !== original.itemCode && original.referenced)
    return `字典项「${original.itemCode}」已被历史数据引用，不能修改编码；名称、说明仍可修改`;
  return '';
}

/**
 * 这个字典项允许哪些操作。
 *
 * <p>刻意<b>没有</b> canDelete：这个字段永远不存在，让「能不能删」在类型层面就无法被
 * 表达成 true。删除防线在四层（控制器无 DELETE 端点、迁移不授予 DELETE 权限、
 * 外键 RESTRICT、删除保护触发器），前端这一层只是不让用户去找一个不存在的按钮。
 */
export function disableActions(item: Pick<DictItemLike, 'status'>): {
  canDeactivate: boolean;
  canReactivate: boolean;
} {
  return {
    canDeactivate: item.status === 'ACTIVE',
    canReactivate: item.status === 'DISABLED',
  };
}

/** 引用统计的一句话摘要。引用数为 0 与「从未被引用过」是两回事，要分开说。 */
export function referenceSummary(
  item: Pick<DictItemLike, 'referenceCount' | 'referencingSystems' | 'referenced'>,
): string {
  if (!item.referenced) return '尚未被任何业务系统引用';
  if (item.referenceCount === 0)
    return `${item.referencingSystems} 个来源系统曾引用过，当前引用 0 条`;
  return `被 ${item.referencingSystems} 个来源系统引用，共 ${item.referenceCount} 条`;
}

/** 变更历史一行的可读摘要：每种 action 回答一个不同的问题。 */
export function historySummary(row: HistoryRow): string {
  switch (row.action) {
    case 'CREATED':
      return `新建字典项「${row.afterName ?? row.itemCode}」（${row.itemCode}）`;
    case 'UPDATED':
      return row.beforeName === row.afterName
        ? `编辑字典项「${row.afterName ?? row.itemCode}」的说明或排序`
        : `字典项「${row.itemCode}」改名：${row.beforeName ?? '—'} → ${row.afterName ?? '—'}`;
    case 'DEACTIVATED':
      return `停用字典项「${row.afterName ?? row.itemCode}」（${row.itemCode}）${row.reason ? `：${row.reason}` : ''}`;
    case 'REACTIVATED':
      return `恢复字典项「${row.afterName ?? row.itemCode}」（${row.itemCode}）${row.reason ? `：${row.reason}` : ''}`;
    default:
      return `${row.itemCode} · ${row.action}`;
  }
}

/** 按字典分组，让配置页按「字典 → 字典项」组织，而不是一长条平铺列表。 */
export function groupByDictionary<T extends { dictCode: string; dictName?: string }>(
  items: T[],
): { dictCode: string; label: string; rows: T[] }[] {
  const groups = new Map<string, { dictCode: string; label: string; rows: T[] }>();
  for (const item of items) {
    let group = groups.get(item.dictCode);
    if (!group) {
      group = { dictCode: item.dictCode, label: item.dictName || item.dictCode, rows: [] };
      groups.set(item.dictCode, group);
    }
    group.rows.push(item);
  }
  return [...groups.values()];
}
