'use client';

import { MediaWorkspace } from '@/components/catalog/media-workspace';

/**
 * 媒体库：3.4.1.1。
 *
 * <p>路由是 `/products/media`，与 `/products/brands`（3.3.2.1）、`/products/categories`（3.3.1.1）
 * 并列：三者都是租户级的字典/资产库，被 SPU 引用，但维护节奏与责任人不同
 * （品牌跟授权合约走，类目跟商品结构走，素材跟拍摄与供应商批次走），
 * 放在同一个页面会让三边的编辑互相干扰。
 *
 * <p>与 `/products/assets` 不是一回事：那个页面读的是 3.1.x 的通用记录面
 * （`/catalog/records/assets`，落 `product_asset` 表），形状是
 * title / notes / reference / status / locale，与媒体元数据没有交集。
 * 媒体库的权威是 `/catalog/media` 与 media_* 那组表，见 V18 迁移头部。
 */
export default function MediaPage() {
  return <MediaWorkspace />;
}