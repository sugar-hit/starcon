'use client';

import { ImageProcessWorkspace } from '@/components/catalog/image-process-workspace';

/**
 * 图片处理：3.4.1.2。
 *
 * <p>路由是 `/products/media/processing`，挂在 `/products/media`（3.4.1.1 媒体库）之下：
 * 处理的输入是素材、产物也就是素材的新版本，所以它天然属于媒体库这条线。
 * 但单独开一页而不是塞进媒体库工作区，是因为两边的节奏完全不同——
 * 媒体库是「每天上传与整理素材」，图片处理是「上架前按平台规格批量加工」，
 * 后者要盯长任务进度与逐素材的失败原因，塞进同一个页面会让两边互相挤掉上下文。
 *
 * <p>蓝图关系：上游 = 3.4.1.1 媒体库；下游 = 04 刊登上传（尚未交付）。
 * 产物写的是 media_asset 的新版本，所以刊登侧读素材当前版本即可，不需要知道处理细节。
 */
export default function ImageProcessingPage() {
  return <ImageProcessWorkspace />;
}
