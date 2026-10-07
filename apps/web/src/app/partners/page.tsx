'use client';

import { PartyWorkspace } from '@/components/catalog/party-workspace';

/**
 * 往来单位：3.6.1.1。
 *
 * <p>路由是 `/partners`，与 `/products/*`（商品主数据）**并列而不挂在它下面**：
 * 往来单位不是商品的属性，而是与商品平级的主数据 —— 06 采购、11 售后、12 财务
 * 也引用它，而那些都不是商品页面。台账里记的锚点路由就是 `/partners`。
 *
 * <p>3.6.1.2 角色档案（同 L3，尚未开发）会落在同一页面：它按蓝图要求
 * 「每个角色一张 1:1 档案表」，本页面现在承载的是角色**指派**，
 * 角色特有字段（账期、MOQ、客户分级、信用额度、接口账号）由那一层补上。
 */
export default function PartnersPage() {
  return <PartyWorkspace />;
}
