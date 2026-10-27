# 册页 Folior

> 社交媒体习惯把照片切成一张张孤立的图片，但摄影师想说的话往往不在单独的一张图里，它们的顺序、间隔与并置之间有许多被忽视的表达空间。我们鼓励一种更完整的摄影表达方式。
>
> 「册页」为呈现方式而存在，快速便捷的编排工具与导出交付能够跟得上社交媒体的速度。
>
> 我们提供一个把照片编辑成册的工作台，照片排序、页面设计、文字介绍、装裱设计、手绘标注，都可以在这里一次性完成。你可以将完成的电子册页导出分享，或输出一本 PDF 电子册传播到社交平台上。

Android 摄影书 / 展览编排应用。

## 功能

| 功能 | 说明 |
|---|---|
| 两种载体 | 摄影书页面（毫米制）／展览墙面（米制，含展厅预设） |
| 真实尺寸 | 照片按 EXIF PPI 以实际打印尺寸落位，墙面左下角有米制标尺 |
| 排序 | 拖放排序；看片台全屏平铺，跨行跨屏一次调完 |
| 编排 | 横排／竖排文字、装裱（纸张·木板·金属·亚克力，横纵外沿与比例）、色块、S Pen 压感手绘 |
| 放映 | 全屏翻页，拖动条快速跳页，进入即定位当前页 |
| 输出 | PDF 三种版式（单页／双联／单页+空白首页），精度 72/192/300 或自定义，体积上限可选 |
| 项目预览 | 全部页面按顺序拼为一张图片导出 |
| 迁移 | 工程打包为 `.folio` |

照片比例锁定，缩放与替换都不裁切。

## 安装

到 [Releases](../../releases) 下载 APK，Android 12 及以上。安装时允许「未知来源应用」即可。

## 构建

需要 JDK 17、Android SDK 34、Gradle 8.7。

```bash
export ANDROID_HOME=<Android SDK 路径>
./gradlew assembleRelease
```

产物：`app/build/outputs/apk/release/app-release.apk`

签名信息写在 `local.properties`，该文件不入库；没有密钥时产出未签名 APK。

```properties
folior.storeFile=folior.keystore
folior.storePassword=<口令>
folior.keyAlias=folior
folior.keyPassword=<口令>
```

## 许可证

MIT，见 [LICENSE](LICENSE)。
