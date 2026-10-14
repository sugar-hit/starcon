'use client';

import { MultilingualWorkspace } from '@/components/catalog/multilingual-workspace';

/**
 * 多语言内容工作区：3.4.3.1。
 *
 * <p>路由是 `/products/translations`，与 `/products/content`（3.4.2.1 标题库 + 3.4.2.2 描述库）
 * **并列而不是合并**，因为后者带 locale 的是「描述模板与其渲染段落」的多语言，
 * 这一页是「商品档案（SPU/SKU）本身的多语言名称与描述」。两者是**不同的两套口径**，
 * 合并在一个页面里只会让人把 `content_template_translation.locale` 与
 * `spu_translation.locale` 当成同一个东西 —— 那样两边数字迟早对不上。
 *
 * <p>本页与 `/products/content` 的上下游关系是明确的：商品多语言内容供 04 刊登按站点语言填充；
 * 「需要哪些语言」由店铺启用的站点推导（02 渠道，未接入前用人工维护登记）。
 */
export default function MultilingualContentPage() {
  return (
    <div className="space-y-4">
      <header className="space-y-1">
        <h1 className="text-copy-20 font-semibold">多语言内容</h1>
        <p className="text-muted-foreground text-copy-13">
          维护 SPU / SKU 的多语言名称与描述。翻译状态分待翻 / 已翻 / 已校对三态，
          <strong>只有已校对才可刊登</strong> ——「已翻」是机器译文，发到平台上等于没做校对这一步。
          需要哪些语言由店铺启用的站点推导（02 渠道），在它接入前先用「人工维护」登记。
        </p>
      </header>
      <MultilingualWorkspace />
    </div>
  );
}
