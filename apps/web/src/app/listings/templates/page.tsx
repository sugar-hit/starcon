'use client';

import { DescriptionTemplateWorkspace } from '@/components/catalog/description-template-workspace';

/**
 * 刊登模板工作区：L3「4.2.2 模板」的锚点路由 `/listings/templates`。
 *
 * <p>这个 L3 下有两个模块：
 *
 * <ul>
 *   <li><b>4.2.2.1 刊登模板</b> —— 类目级 / 店铺级的字段填充规则（取自商品属性、
 *       引用文案库、套用图片组），一键套用到目标商品。<strong>尚未开发</strong>。
 *   <li><b>4.2.2.2 描述模板</b> —— 富文本描述模板：五段段落结构、变量占位、
 *       多语言版本、图片插入位，产出渲染后的描述 HTML。<strong>已交付</strong>。
 * </ul>
 *
 * <p>两者分属 L3 的两个子节点、解决不同的问题：4.2.2.1 管「一整条 listing 的字段怎么填」，
 * 4.2.2.2 管「详情页正文怎么组织」。所以现在这一页先只挂描述模板，
 * 等 4.2.2.1 交付后再加成页签 —— 与 `/products/content` 里标题库 / 描述库用页签分开是同一手法。
 */
export default function ListingTemplatesPage() {
  return (
    <div className="space-y-4">
      <DescriptionTemplateWorkspace />
    </div>
  );
}
