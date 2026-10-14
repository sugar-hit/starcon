'use client';

import { PartyContactsWorkspace } from '@/components/catalog/party-contacts-workspace';

/**
 * 地址与联系人：3.6.3.1。
 *
 * <p>路由是 `/partners/contacts`，挂在 `/partners`（3.6.1.1 往来单位）**之下**
 * 而不是另开一个顶级域：地址与联系人的归属就是往来单位 —— 06 采购取发货 / 收货地址、
 * 10 物流取提货地址、11 售后找对接人，全都是「某个往来单位的地址与联系人」，
 * 离开主体它们无法独立存在（后端用 (tenant_id, party_id) 复合外键把这一点钉在 DB 层）。
 *
 * <p>与 3.6.1.1 的分工：`party` 上的 email / phone / website 回答「怎么找到这个主体」
 * （公司总机 / info@ 邮箱），本页面回答「跟这个主体对接的那个人是谁」，
 * 以及「这个主体的货从哪里发、到哪里收」。
 *
 * <p>蓝图那句「单据上的地址是冻结快照」在本页面上的落点是「历史版本」区块：
 * 每一版都是当时的内容，已发生单据引用的就是其中一版，改地址不会改写它们。
 */
export default function PartnerContactsPage() {
  return <PartyContactsWorkspace />;
}
