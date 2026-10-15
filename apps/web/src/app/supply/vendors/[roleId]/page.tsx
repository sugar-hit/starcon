import { VendorDetailWorkspace } from '@/components/supply/vendor-detail';

/**
 * 6.1.1.2 供应商详情（`supplier_id` = SUPPLIER 角色的 id，与 6.1.1.1 列表下钻同一个锚点）。
 *
 * <p>蓝图：「单供应商全景：基础信息、账期与开票信息、供货 SKU 与报价、采购历史与金额、
 * 交期与质量表现、联系人、附件资质」，输出「详情视图 + 操作入口」。
 *
 * <p>路由从列表的供应商行进入（`/supply/vendors/{roleId}`），入口仍是台账里记的 `/supply/vendors`。
 * 它与 6.1.1.1 的列表下钻是**两个不同的区块**：那个回答「采购侧现在是什么状态、
 * 表现如何、事实是什么」，本页回答全景（含账期与开票、报价、附件资质与操作入口）。
 *
 * <p>🔴 页面只提供两处可写：账期与开票信息、附件资质。编码 / 名称 / 联系方式 / 合作等级 /
 * 采购侧合作状态全部只读并给出跳转链接 —— 供应商主数据只有一份，写入所有权也只有一份。
 */
export default async function VendorDetailPage({
  params,
}: {
  params: Promise<{ roleId: string }>;
}) {
  const { roleId } = await params;
  return <VendorDetailWorkspace roleId={roleId} />;
}
