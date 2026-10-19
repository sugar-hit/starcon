'use client';

import { PublishWorkspace } from '@/components/listing-publish/publish-workspace';

/**
 * 批量发布：L3「4.3.1 发布与同步」的锚点路由 `/listings/publish`。
 *
 * <p>这个 L3 下有三个模块：
 *
 * <ul>
 *   <li><b>4.3.1.1 一键刊登</b> —— 单个 SKU 一次性发布到多个目标店铺。<strong>尚未开发</strong>。
 *   <li><b>4.3.1.2 批量发布</b> —— 按规则筛选待发布商品，生成任务批次，支持暂停 / 调整顺序 /
 *       批量重试，按平台配额限流。<strong>已交付</strong>，就是本页。
 *   <li><b>4.3.1.3 发布任务</b> —— 单次发布任务的步骤时间线与错误明细。<strong>尚未开发</strong>，
 *       本页的失败明细先展示后端记录的原因，跳转目标缺失是预期的。
 * </ul>
 *
 * <p>与 `/listings`（4.1.1.1 列表）、`/listings/mappings`（4.1.2.1 映射）、`/listings/templates`
 * 各挂一个工作区是同一手法：4.3.1.2 消费 4.1.1.1 的 listing 与 4.2.1.2 的内容草稿，
 * 但它是另一套状态机（队列 / 限流 / 顺序 / 重试），所以独立成页而不是做成列表的一个筛选维度。
 */
export default function ListingPublishPage() {
  return (
    <div className="space-y-4">
      <PublishWorkspace />
    </div>
  );
}
