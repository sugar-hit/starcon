'use client';

import { useState } from 'react';
import { Columns3, RotateCcw } from 'lucide-react';
import { Button } from '@starcon/ui';
import { control } from './fields';
import type { ListColumn } from '../../lib/list-preferences-model';
import type { UseListPreferences } from '../../lib/use-list-preferences';

/**
 * 1.4.1.2 列表与表格偏好 —— 各列表页共用的**偏好工具条**。
 *
 * <p>蓝图：「记住各列表页的列显示、排序、分页大小、筛选条件，降低重复操作成本。」
 * 这个工具条就是那四个动作的入口，五个已交付的列表页共用同一份实现。
 *
 * <p>刻意**不**做成一个「设置」弹窗挂在页面右上角：偏好是列表页的**上下文**，
 * 放在列表旁边（同一屏内）用户才知道它改的是哪张表。
 *
 * <p>三态分开：
 * <ul>
 *   <li><b>保存中</b>：轻微提示，不遮挡列表；</li>
 *   <li><b>保存失败</b>：留一条红色提示，但**列表照常可用** ——
 *       偏好存不上只意味着「下次进来要重调一次」，不该让列表页变成错误页；</li>
 *   <li><b>已恢复上次视图</b>：告诉用户「你上次调过」，
 *       否则用户会以为这一屏是随机显示的。</li>
 * </ul>
 */
export function ListPreferenceBar({
  columns,
  sortOptions,
  pageSizeOptions,
  prefs,
}: {
  /** 这一页的列定义。 */
  columns: ListColumn[];
  /** 这一页支持的排序；为空则不显示排序选择。 */
  sortOptions?: ListColumn[];
  /** 可选的每页条数。 */
  pageSizeOptions: readonly number[];
  /** hook 的返回值。 */
  prefs: UseListPreferences;
}) {
  const [open, setOpen] = useState(false);
  const { state } = prefs;

  return (
    <div className="flex flex-wrap items-center gap-3">
      {pageSizeOptions.length > 0 ? (
        <label className="flex items-center gap-1.5 text-sm">
          <span className="text-muted-foreground">每页</span>
          <select
            aria-label="每页条数"
            className={`${control} w-auto`}
            value={state.pageSize}
            onChange={(e) => prefs.setPageSize(Number(e.target.value))}
          >
            {pageSizeOptions.map((size) => (
              <option key={size} value={size}>
                {size} 条
              </option>
            ))}
          </select>
        </label>
      ) : null}

      {sortOptions && sortOptions.length > 0 ? (
        <label className="flex items-center gap-1.5 text-sm">
          <span className="text-muted-foreground">排序</span>
          <select
            aria-label="排序"
            className={`${control} w-auto`}
            value={state.sortKey}
            onChange={(e) => prefs.setSortKey(e.target.value)}
          >
            {sortOptions.map((option) => (
              <option key={option.key} value={option.key}>
                {option.label}
              </option>
            ))}
          </select>
        </label>
      ) : null}

      <Button variant="outline" size="sm" onClick={() => setOpen((v) => !v)}>
        <Columns3 className="h-4 w-4" />
        列显示
      </Button>

      <Button variant="ghost" size="sm" onClick={prefs.reset}>
        <RotateCcw className="h-4 w-4" />
        恢复默认
      </Button>

      {prefs.saving ? (
        <span role="status" className="text-muted-foreground text-xs">
          正在保存偏好…
        </span>
      ) : null}
      {prefs.error ? (
        <span role="alert" className="text-destructive text-xs">
          偏好未保存（{prefs.error}）
        </span>
      ) : null}
      {!prefs.saving && !prefs.error && prefs.stored ? (
        <span role="status" className="text-muted-foreground text-xs">
          已恢复上次的视图
        </span>
      ) : null}

      {open ? (
        <div className="bg-card flex w-full flex-wrap gap-x-5 gap-y-2 rounded-md border p-3">
          {columns.map((column) => (
            <label key={column.key} className="flex items-center gap-2 text-sm">
              <input
                type="checkbox"
                checked={state.columns.includes(column.key)}
                onChange={(e) => prefs.toggleColumn(column.key, e.target.checked)}
              />
              {column.label}
            </label>
          ))}
        </div>
      ) : null}
    </div>
  );
}
