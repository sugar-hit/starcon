# 2026-09-27: 恢复城市轮换、白色线框地球与完整图片显示

## 摘要

用户要求去掉突兀的白底球体、恢复原城市轮换、城市国家大洲单行展示，并避免图片从上到下逐步出现。延续上一轮工作台优化，修正了把“每城照片固定”误解为“六城固定”的实现。

## 决策 / 结论

- 北京固定，五个区域每 15 秒推进一城；保留完整 34 城池。城市、时间、地球和照片同步对应当前城市。
- 地球改为透明底、白色大陆轮廓和淡经纬网；去除实色海洋、大陆填色与光晕。reduced-motion 直接切换视角。
- 标题为“北京 中国（亚洲）”形式；按城市归属显示大洲，不将“东南亚”“其他”混作大洲。
- 为保证长名称单行完整，卡片按视口宽度使用 1 / 2 / 3 / 6 列；地球放在时间行右侧。
- img 隐藏下载并等待 decode，避免渐进加载。续做时仓库已有 ebb89e5 的双层交叉淡入实现，保留这项后续改动，并同步修正文档中的“无淡入”旧描述；过期请求忽略，失败保留深色底。
- 只预取下一轮五城；后台标签暂停推进与预取。
- 补齐 34 城照片元数据，保留许可证/作者/来源。查询尊重限流，等待并降速后完成。视觉 QA 发现自动搜索的两张教堂图不属于目标城市，已按具体城市和来源描述替换；同时改善吉隆坡、布宜诺斯艾利斯、内罗毕的图片。
- CDN 尚未接入；生产建议使用版本化 WebP/AVIF 公共静态图及长期缓存，仍保留前端 decode 显示门槛。

## 关键 trade-off / 讨论

完整单行名称需要足够卡片宽度，因此普通桌面采用三列两行，不压小长地名或截断信息。图片加载速度不阻塞城市时钟，失败时优先保持准确文字而非残留上一城市背景。
CDN 负责传输与缓存，前端负责何时显示；两者不能互相替代。
新增图片运行时仍用公开 Wikimedia 直链，生产自有 CDN 上传属于后续工作。

## 涉及文件

- packages/ui/src/lib/world-clock.ts、world-clock.test.ts
- packages/ui/src/lib/city-photos.ts、city-photos.test.ts
- packages/ui/src/components/common/world-clock-strip.tsx
- packages/ui/src/components/common/mini-globe.tsx
- packages/ui/src/components/common/city-backdrop.tsx
- tests/e2e/world-clock.spec.ts、scroll-shell.spec.ts
- docs/design/visual-spec.md
- docs/design/world-clock-images.md、world-clock-photo-credits.md
- 本会话日志

## 验证

- 单元测试覆盖完整 34 城轮换、各组归属、大洲分类、全部城市图片清单。
- 浏览器覆盖轮换、透明球体、解码门槛、完整图片交叉淡入、失败回退、过期请求、最长城市名单行及各屏幕布局。
- 浏览器实际检查全部 34 张图片均可完整解码；总览截图保存在 work/clock-photo-catalog.png。
- 最终 lint、typecheck、24 项单元测试、18 项浏览器回归及生产构建全部通过。

## 后续待办

- 按 docs/design/world-clock-images.md 接入生产 CDN，确认压缩尺寸、裁切、版本化缓存和公开归因页。
- 用户已有 tools/scripts/fetch-city-photos.mjs 与 .gitkeep 删除保持原状。
- 本轮未提交、未推送，未上传生产图片。

## 关联

- [图片与 CDN 说明](../../design/world-clock-images.md)
- [图片来源清单](../../design/world-clock-photo-credits.md)
- [上一轮视觉调整](2026-09-27-clock-visuals-backend-research.md)
