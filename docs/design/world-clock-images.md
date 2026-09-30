# 工作台世界时钟：图片加载与 CDN

更新：2026-09-27。

## 展示约定

北京固定，其余五组每 5 秒切换，完整池共 34 城。每座城市绑定固定图片，不等于固定展示六城。切换时城市、国家、大洲、IANA 时区、地球视角与图片同步更新。

标题示例：北京 中国（亚洲）。大洲与轮换组分开：东南亚属于亚洲；“其他”组里的城市分别属于南美洲、大洋洲、非洲。标题保持单行，窄屏减少列数以完整显示长城市名。地球使用透明底白色轮廓和低亮度经纬线，没有海洋/大陆填色或光晕。

## 为什么不能只加 CDN

用户看到“从上往下出现”的图片，可能是浏览器在文件尚未完整到达时展示已有像素。CDN 缩短传输距离，但不能保证慢网下不会逐步绘制。仅设置 decoding=async 也只是提示，不保证图片何时显示。

当前实现：

1. 图片提前请求，visibility:hidden，深色卡片立即显示文字与地球。
2. 对实际 img 调用 decode()，等待完整下载、解码。
3. 完整解码后才切换 visibility；现有照片层使用 1.6 秒交叉淡入切换完整图片，不展示模糊预览、逐行加载或半张图片。
4. 换 URL 时取消过期请求结果的 UI 更新；新图准备期间保留上一层，解码成功后交叉淡入并移除旧层，过期请求不能覆盖最新图片。
5. 图片失败保留深色底，不展示破图；时钟和城市轮换不依赖图像成功。
6. 当前轮次稳定 1.5 秒后，只预取下一轮五城，不在首屏加载全部城市。后台标签不继续推进轮换，也不发起下一轮预取。

参考：[MDN image.decode](https://developer.mozilla.org/en-US/docs/Web/API/HTMLImageElement/decode)、[decoding 属性](https://developer.mozilla.org/en-US/docs/Web/API/HTMLImageElement/decoding)。

## 生产环境建议

- 将审核过的原图转成适合卡片的 WebP / AVIF，按最终卡片宽度及 2x 像素比准备约 640–960px 的变体，裁剪时核对地标主体。不要直接传多 MB 原图。
- 对公共装饰图片使用独立对象存储 + CDN，路径包含内容哈希或版本，例如 /world-clock/beijing.<hash>.webp。
- 对这类版本化公共图片设置 Cache-Control: public, max-age=31536000, immutable；替换图片使用新 URL。该缓存策略不用于私人文件或租户 API。
- 在 packages/ui/src/lib/city-photos.ts 统一替换 url，组件不关心 CDN 厂商。不要在浏览器运行时查询 Wikimedia 搜索 API。
- 正确配置 Content-Type 与 HTTPS。仅展示和 decode 不要求跨域像素读取权限；后续若用 canvas 读取图片像素，再单独配置 CORS。
- 作者、许可证、来源仍须保留；CC BY / BY-SA 等授权图片上线前应在用户可访问的图片致谢页提供归因，并说明裁剪/转换。CDN 不改变原图授权要求。
- 冷缓存、限速、失败请求、快速切换城市和请求乱序都要测试；CDN 上线不移除前端解码门槛。

参考：[MDN HTTP caching](https://developer.mozilla.org/en-US/docs/Web/HTTP/Guides/Caching)。

## 边界

目前仍使用清单内的 Wikimedia 图片直链，并保留作者与许可证元数据。CDN 账号、域名及存储尚未接入。本轮未上传任何生产资源。
