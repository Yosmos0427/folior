# 册页 Folior

> 社交媒体习惯把照片切成一张张孤立的图片，但摄影师想说的话往往不在单独的一张图里，它们的顺序、间隔与并置之间有许多被忽视的表达空间。我们鼓励一种更完整的摄影表达方式。
>
> 「册页」为呈现方式而存在，快速便捷的编排工具与导出交付能够跟得上社交媒体的速度。
>
> 我们提供一个把照片编辑成册的工作台，照片排序、页面设计、文字介绍、装裱设计、手绘标注，都可以在这里一次性完成。你可以将完成的电子册页导出分享，或输出一本 PDF 电子册传播到社交平台上。

Android 摄影书 / 展览编排应用。

## 功能

- 摄影书页面与展览墙面两种载体
- 照片按实际打印尺寸落位
- 导入保留原始像素，另存工作图供编辑使用
- 拖放排序与看片台平铺排序
- 文字、装裱、色块、S Pen 手绘
- 元素对齐吸附、多选分布、缩放吸附
- 构图指示线，可多条，可镜像成组；线与元素、页面边线互相吸附
- 书脊样式：贴合、留白、线编、活页
- 全屏放映，单击翻页、双击放大到 100%
- 放映可选原图或工作图
- PDF 导出，精度与体积上限可调
- 项目预览图导出
- `.folio` 工程打包

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
