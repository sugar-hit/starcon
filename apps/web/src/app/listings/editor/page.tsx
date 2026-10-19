'use client';

import { ListingEditorWorkspace } from '@/components/listing-editor/listing-editor-workspace';

/**
 * 刊登编辑器：L3「4.2.1 商品内容」下的 4.2.1.1。
 *
 * <p>这个 L3 下有两个模块：
 *
 * <ul>
 *   <li><b>4.2.1.1 刊登编辑器</b> —— 单商品的平台内容编辑表单：按目标平台动态渲染字段、左侧编辑右侧实时预览、
 *       多语言 Tab、保存草稿、提交审核。<strong>已交付</strong>，就是本页。
 *   <li><b>4.2.1.2 批量编辑</b> —— 对一批 listing 执行统一的字段修改。<strong>后端已交付</strong>
 *       （端点 {@code /channel/listing-bulk-edit}），<strong>前端尚未交付</strong>；它与本模块共用
 *       {@code /listings/editor} 这条路由，等它的前端落地时加成页签 ——
 *       与 {@code /listings/templates} 里「4.2.2.1 未交付时先只挂 4.2.2.2」是同一手法。
 * </ul>
 *
 * <p>🔴 本页**没有**「直接上线」这个动作：编辑器只写草稿，提交审核只是入队，
 * 真正发给平台的是 4.3.1.1 一键刊登（尚未交付）。
 */
export default function ListingEditorPage() {
  return (
    <div className="space-y-4">
      <ListingEditorWorkspace />
    </div>
  );
}
