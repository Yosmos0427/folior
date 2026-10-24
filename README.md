# 册页 Folior

> 社交媒体习惯把照片切成一张张孤立的图片，但摄影师想说的话往往不在单独的一张图里——它们的顺序、间隔与并置之间，有许多被忽视的表达空间。
>
> 「册页」为呈现方式而存在：快速便捷的编排工具与导出交付，能够跟得上社交媒体的速度。我们提供一个把照片编辑成册的工作台——照片排序、页面设计、文字介绍、装裱设计、手绘标注，都可以在这里一次性完成。你可以将完成的电子册页导出分享，或输出一本 PDF 电子册，传播到社交平台上。
>
> **我们鼓励一种更完整的摄影表达方式。**

安卓端摄影书 / 展览编排应用，目标设备 Galaxy S23 Ultra。

## 能力

| 能力 | 说明 |
|---|---|
| 两种载体 | 摄影书页面（毫米制）／展览墙面（米制，含展厅预设） |
| 真实尺寸 | 照片按 EXIF PPI 以实际打印尺寸落位，墙面左下角有米制标尺 |
| 顺序即叙事 | 拖放排序；看片台全屏平铺，跨行跨屏一次调完 |
| 编排 | 横排／竖排文字、装裱（纸张·木板·金属·亚克力，横纵外沿与比例）、色块、S Pen 压感手绘 |
| 放映 | 全屏翻页，拖动条快速跳页，进入即定位当前页 |
| 输出 | PDF 三种版式（单页／双联／单页+空白首页），精度与体积可选 |
| 可迁移 | 工程打包为 `.folio`，换设备继续编 |

## 硬规矩

**不为填满画框而裁切。** 照片比例锁定，缩放手柄与双指缩放都不改变比例；替换图片只换图源，框按新图比例重算——你按下快门时框住的东西，会原样出现在读者眼前。

## 构建

```bash
# 方式一：gradle wrapper（外部机器，自动下载 Gradle 8.7）
export ANDROID_HOME=<你的 Android SDK 路径>
./gradlew assembleRelease

# 方式二：本机已装 Gradle
export ANDROID_HOME=/opt/android-sdk
/opt/gradle-8.7/bin/gradle assembleRelease --no-daemon

# 产物：app/build/outputs/apk/release/app-release.apk
```

签名密钥 `folior.keystore` 不入库，需自行放置于仓库根目录（密码见 app/build.gradle.kts）。

## 版本号规则

**正式发布（1.0.0）之前，主版本号固定为 0**：`0.MINOR.PATCH`

| 变更类型 | 递增位 | 示例 |
|---|---|---|
| 小修改 / 修复 / 微调 | PATCH（最后一位） | 0.9.8 → 0.9.9 |
| 新增功能 | MINOR（第二位），PATCH 归零 | 0.9.9 → 0.10.0 |
| 正式发布 | MAJOR → 1.0.0 | — |

历史 `1.x.y` 版本对应重编为 `0.x.y`（后两位序号保持连续）。

## 版本发布

```bash
tools/release.sh 0.28.0 "本次主要改动"
```

自动完成：versionCode+1 → 改 versionName → 编译 → 复制 APK 到 `/workspace/册页Folior-v<版本>.apk`
→ git commit → 打 tag `v<版本>` →（已配置 origin 时）推送。

## 回滚

```bash
tools/rollback.sh v0.20.0
```

以独立 worktree 检出该 tag 并编译，不污染当前分支。

## 配置远端（需要时）

```bash
git remote add origin https://github.com/<账号>/<仓库>.git
git push -u origin master --tags
```

推送凭证建议使用 Personal Access Token（scope: repo），不要写入仓库文件。

## 目录

```
app/src/main/java/com/yussy/folior/
  MainActivity.kt        项目列表（长按：打开/重命名/导出/删除）
  EditorActivity.kt      编排编辑器（工具条、二级菜单、看片台抽屉）
  ViewerActivity.kt      全屏放映
  SettingsActivity.kt    全局设置（PDF 精度与体积上限、关于）
  model/Model.kt         数据模型（项目/页/元素，JSON 序列化）
  store/Store.kt         项目与图片存储、.folio 打包、位图缓存
  store/Prefs.kt         全局设置存储（SharedPreferences）
  ui/PageCanvas.kt       编辑画布（手势、绘制、工作区缩放、翻页）
  ui/PageRenderer.kt     统一渲染（画布/缩略图/PDF/放映）
  ui/PageStrip.kt        页面预览栏（覆盖式删除、拖放排序）
  ui/LightTable.kt       看片台（全屏平铺排序）
  ui/PdfExporter.kt      PDF 导出（三种版式、单次渲染、体积压缩）
  ui/FramingPanel.kt     装裱面板
  ui/PageSizeDialog.kt   页面/墙体尺寸与底色
  ui/ProjectDialog.kt    项目设置
  ui/TextDialog.kt       文字编辑
  ui/ColorPickerDialog.kt 调色板
  ui/DialogScroll.kt     对话框内容滚动包装
```

## 当前状态

**v0.27.0**（2026-10-24）——更名「册页 Folior」，应用内新增「关于」。

## 版本记录

见 [CHANGELOG.md](CHANGELOG.md) 与 [待办.md](待办.md)。
