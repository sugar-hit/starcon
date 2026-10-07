'use client';

import { TitleWorkspace } from '@/components/catalog/title-workspace';

/**
 * 标题库：3.4.2.1。
 *
 * <p>路由是 `/products/content`，是 L3「3.4.2 文案资产」的锚点路由，
 * 与 `/products/media`（3.4.1 媒体库与图片处理那条线）并列。
 *
 * <p>蓝图职责：「沉淀可复用的标题模板与标题记录，支持变量占位（品牌 + 品类 + 核心词 + 卖点），
 * 批量生成差异化标题（同一商品在多平台不同标题以避重复铺货判定）。」
 * 「输出：生成的标题候选集；供 04 选用或导出。」
 *
 * <p>蓝图关系：上游 = 3.1.1.1（SPU 与 SKU 的品牌、分类、规格）；下游 = 04 刊登。
 *
 * <p>同一个 L3 下还有一个兄弟模块 3.4.2.2 描述与卖点库（未开发）——
 * 它管详情描述模板、卖点短语库与多语言版本，那是**长文本**；本模块管标题这个
 * **有硬字符上限**的位置，两者排版约束不同，所以不合并成一个页面。
 */
export default function TitleLibraryPage() {
  return <TitleWorkspace />;
}