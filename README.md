# pi-mobile APK

π mobile 的 Android 客户端。Capacitor 外壳 + 内置全部界面，不依赖服务器托管网页。

配套服务端：[pi-mobile](https://github.com/freedom2599/pi-mobile)

## 下载

本仓库只放源码，不发成品 APK。安装包由服务端分发：

```
http://<服务器IP>:8848/download/pi-mobile-<版本>.apk
```

当前版本见服务端 `/api/version`。

## 结构

```
www/index.html        引导页：填服务器地址 → 跳转
www/app.html          完整应用（单文件，含全部 UI 与逻辑）
android/              原生工程
  app/src/main/java/com/polaris/pimobile/
    MainActivity.java   返回键处理、WebView 配置
    ApkUpdater.java     应用内下载 + 拉起安装器
capacitor.config.json
build.sh              同步 + 编译脚本
```

`www/app.html` 是纯前端，不依赖构建工具，改完直接生效。

## 编译

需要 JDK 17+ 和 Android SDK。

```bash
npm install
./build.sh              # release
./build.sh debug        # debug
./build.sh sync         # 只同步资源
```

首次编译前设 `ANDROID_HOME`：

```bash
export ANDROID_HOME=/path/to/android-sdk
export JAVA_HOME=$(dirname $(dirname $(readlink -f $(command -v java))))
```

## 签名

签名密钥**不入库**。仓库里 `app/build.gradle` 从环境变量读取：

```bash
export PI_KEYSTORE=/path/to/release.jks
export PI_KEYSTORE_PASSWORD=...
export PI_KEY_ALIAS=pimobile
export PI_KEY_PASSWORD=...
```

或写进本地 `.env`（已 gitignore），`build.sh` 会自动加载：

```
PI_KEYSTORE=/path/to/release.jks
PI_KEYSTORE_PASSWORD=...
PI_KEY_ALIAS=pimobile
PI_KEY_PASSWORD=...
```

不配置时 release 包会退回 debug 签名，仅能本地验证，不能发布。

## 主要实现

**返回键**（`MainActivity.onBackPressed`）

调用网页的 `window.__onAndroidBack()`，按顺序处理：

```
设置页打开   → 关闭
对话框打开   → 关闭（强制更新时拦截）
抽屉打开     → 关抽屉 / 回上级目录
有附件       → 清空附件
正在流式回答 → 中断
都不是       → 退到后台
```

**应用内更新**（`ApkUpdater`）

用系统 `DownloadManager` 下载，JS 侧轮询进度：

```java
window.PiApk.download(url)      // 开始下载
window.PiApk.getState()         // {state, got, total, percent, canInstall}
window.PiApk.install()          // 拉起系统安装器
window.PiApk.canInstall()       // 是否有「安装未知应用」权限
window.PiApk.openInstallPermission()
```

下载完成自动调起安装器；缺权限时引导用户去系统设置开启。

**混合内容**

页面跑在 `http://localhost`（`capacitor.config.json` 里 `androidScheme: "http"`），
和 API 同协议，避免 WebView 拦截 http 图片。

## 权限

- `INTERNET` / `ACCESS_NETWORK_STATE`
- `WAKE_LOCK`
- `REQUEST_INSTALL_PACKAGES` —— 应用内更新必需，缺了 `canRequestPackageInstalls()` 恒为 false

## 已知限制

- 自签名，非商店分发
- 建议在 WireGuard 或内网使用
- 应用内更新首次需要手动允许「安装未知应用」
