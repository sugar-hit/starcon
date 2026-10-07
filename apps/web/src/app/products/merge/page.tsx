'use client';

import { SkuMergeWorkspace } from '@/components/catalog/sku-merge-workspace';

/**
 * 3.9.1.2 SKU 合并（路由 `/products/merge`）。
 *
 * <p><b>为什么独立成页而不挂在 3.9.1.1 的候选清单下面</b>：3.9.1.1 回答的是
 * 「<b>这两条是不是重复</b>」（读为主），3.9.1.2 回答的是
 * 「<b>把它们并起来要动多少东西</b>」（写为主，且不可逆）。两者的时间尺度也不同：
 * 识别可以随时重扫，合并是一次性动作。把执行入口放进候选详情页，
 * 会让「看一眼证据」和「按下那个会改写十几张表的按钮」挨得太近。
 *
 * <p>页面上必须写清的四条纪律：
 *
 * <ul>
 *   <li><b>先预演再执行。</b>执行前逐维度展示「会改指多少行、哪几行搬不动、
 *       哪些是刻意不动的历史」，确认按钮在预演不可执行时是禁用的。</li>
 *   <li><b>原记录标记合并且停用，不物理删除。</b>停用而非删除是主数据纪律
 *       （实体目录 §5）—— 历史单据仍要显示「当时是哪一个 SKU」。</li>
 *   <li><b>历史订单行冻结当时 SKU 快照，不随合并回溯改写</b>（实体目录 §3.6）——
 *       报告里那一大块「历史冻结」是刻意不搬的证据，不是漏搬。</li>
 *   <li><b>只迁移当前引用（映射 / 库存 / 价格）。</b>04 映射、07 库存、09 订单的引用
 *       在别的服务的库里，本服务不跨库读写；页面上 catalog 这一侧报告说清
 *       「哪几类引用在动」，剩下的由各自服务走授权 API。</li>
 * </ul>
 */
export default function SkuMergePage() {
  return (
    <div className="space-y-4">
      <header className="space-y-1">
        <h1 className="text-copy-20 font-semibold">SKU 合并</h1>
        <p className="text-muted-foreground text-copy-13">
          执行重复 SKU / 商品的归并：把重复记录<strong>并到主记录</strong>，
          迁移<strong>当前引用</strong>（条码、素材、报价、套装、BOM、包装、安全属性、单位换算），
          原记录<strong>标记合并并停用</strong>而不是物理删除。
          历史订单行<strong>冻结当时 SKU 快照</strong>，不随合并回溯改写。
        </p>
      </header>

      <SkuMergeWorkspace />
    </div>
  );
}
