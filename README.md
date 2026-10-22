# 页册 Folior

安卓摄影书 / 展览编排应用，目标设备 Galaxy S23 Ultra。

## 构建

```bash
export ANDROID_HOME=/opt/android-sdk
/opt/gradle-8.7/bin/gradle assembleRelease --no-daemon
# 产物：app/build/outputs/apk/release/app-release.apk
```

签名密钥 `folior.keystore` 不入库，需自行放置于仓库根目录（密码见 app/build.gradle.kts）。

## 版本发布

```bash
tools/release.sh 1.8.0 "本次主要改动"
```

自动完成：versionCode+1 → 改 versionName → 编译 → 复制 APK 到 `/workspace/页册Folior-v<版本>.apk`
→ git commit → 打 tag `v<版本>` →（已配置 origin 时）推送。

## 回滚

```bash
tools/rollback.sh v1.6.1
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
  MainActivity.kt        项目列表
  EditorActivity.kt      编排编辑器
  ViewerActivity.kt      全屏放映
  model/Model.kt         数据模型（项目/页/元素，JSON 序列化）
  store/Store.kt         项目与图片存储、.folio 打包
  ui/PageCanvas.kt       编辑画布（手势、跨页、裁切）
  ui/PageRenderer.kt     统一渲染（画布/缩略图/PDF/放映）
  ui/PageStrip.kt        页面预览栏
  ui/PdfExporter.kt      PDF 导出（三种版式）
  ui/ProjectDialog.kt    项目设置
  ui/TextDialog.kt       文字编辑
  ui/ColorPickerDialog.kt 调色板
```

## 版本记录

见 [CHANGELOG.md](CHANGELOG.md) 与 [待办.md](待办.md)。
