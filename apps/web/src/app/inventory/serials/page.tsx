import { InventorySerialWorkspace } from '@/components/inventory/inventory-serial';

/**
 * 7.3.2.1 序列号。
 *
 * <p>路由 `/inventory/serials` 是功能蓝图给 7.3.2.1 登记的契约地址
 * （台账 `module-progress.md` 里这一行的「路由」列写的就是它），
 * 与 `/inventory` 总览工作区分开成独立页：序列号是**逐件**管理的，
 * 一屏几十行档案与一屏汇总数字是两回事，混在一个页面里两边都会被挤掉。
 *
 * <p>页面本身只负责挂载。所有筛选、分页与写入都在
 * {@link InventorySerialWorkspace} 里，与权限、错误态、空态、加载态一起处理。
 */
export default function InventorySerialsPage() {
  return <InventorySerialWorkspace />;
}
