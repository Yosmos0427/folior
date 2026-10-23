# 页册 Folior

安卓摄影书 / 展览编排应用，目标设备 Galaxy S23 Ultra。

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
  ui/PageCanvas.kt       编辑画布（手势、跨页、绘制、工作区缩放）
  ui/PageRenderer.kt     统一渲染（画布/缩略图/PDF/放映）
  ui/PageStrip.kt        页面预览栏
  ui/PdfExporter.kt      PDF 导出（三种版式）
  ui/ProjectDialog.kt    项目设置
  ui/TextDialog.kt       文字编辑
  ui/ColorPickerDialog.kt 调色板
```

## 当前状态

**v0.16.0 · 早期测试版**（2026-10-23）——功能自 v0.15.4 冻结，投放收集用户意见。
转正 1.0.0 正式版的条件：全量回归清单通过 + 反馈无 P0/P1 + 无未定位崩溃（详见《1.0.0 转正审阅清单》与 CHANGELOG）。

## 版本记录

见 [CHANGELOG.md](CHANGELOG.md) 与 [待办.md](待办.md)。
