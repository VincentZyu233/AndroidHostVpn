<p align="center">
  <img src="docs/images/logo.png" width="128" height="128" alt="Hosts VPN Logo" />
</p>

<h1 align="center">AndroidHostVpn (Hosts VPN)</h1>

<p align="center">
  <a href="https://github.com/VincentZyu233/AndroidHostVpn/releases/latest"><img src="https://img.shields.io/github/v/release/VincentZyu233/AndroidHostVpn?style=flat-square&color=7F52FF" alt="Latest Release"></a>
  <img src="https://img.shields.io/badge/Platform-Android%208.0+-3DDC84?style=flat-square&logo=android&logoColor=white" alt="Platform">
  <img src="https://img.shields.io/badge/Language-Kotlin%202.0-7F52FF?style=flat-square&logo=kotlin&logoColor=white" alt="Kotlin">
  <img src="https://img.shields.io/badge/UI-Jetpack%20Compose%20%7C%20Material%203-4285F4?style=flat-square" alt="Material 3">
</p>

<p align="center">
  一个轻量、通用的 Android hosts 编辑与 DNS 劫持重定向工具。<br/>
  基于 <b>Jetpack Compose</b> 与 <b>Material Design 3 (Material You)</b> 现代设计语言构建。
</p>

---

通过 Android `VpnService` 建立本地隧道，拦截 DNS 查询：匹配 hosts 表中配置的域名时返回指定 IP，其余查询转发到上游 DNS。**不内置任何 hosts 内容，默认是干净的空工具。**

## 用途

- 本地开发 / 调试：把域名指向内网 IP
- 临时屏蔽某域名（指向 0.0.0.0）
- 需要按域名重定向而不改路由器、不 root 的场景

## 功能特性

- ⚡ **DNS 劫持总开关**：动态呼吸脉冲状态指示灯，一键启停本地 VPN 隧道
- 📝 **Hosts 规则编辑器**：支持 `ip domain` 一行一条与 `#` 注释，内置规则有效行数统计与快速语法辅助
- 📥 **远程 hosts 订阅拉取**：从 URL 动态下载 hosts 规则，便于集中更新维护
- 🌐 **自定义上游 DNS**：未命中 hosts 规则时自动转发至指定上游（默认 `223.5.5.5`，支持一键恢复）
- 🎨 **现代 Material 3 界面**：适配深色模式、全屏 Edge-to-Edge 沉浸与自适应圆角桌面图标

## 下载与构建

可以直接在 [Releases](https://github.com/VincentZyu233/AndroidHostVpn/releases) 页面下载最新预编译的 `app-release.apk`。

如需自行构建：

```bash
./gradlew assembleRelease
```

APK 输出在 `app/build/outputs/apk/release/`。Release 构建也由 GitHub Actions 自动完成，产物直接挂在 Release。

## 使用说明

1. 打开 App，在 hosts 编辑框填入需要重定向的域名（格式见下方）。
2. 打开总开关，系统会弹出 VPN 授权，允许即可。
3. 关闭开关即恢复正常网络。

hosts 格式（与系统 `/etc/hosts` 一致）：

```
# 这是注释
192.168.1.3 n100
10.0.0.1 example.com
```

## 说明

- 本仓库不内置任何真实 hosts 内容，也不包含任何私服地址或逆向成果。
- 需要分发的 hosts 内容请通过「远程 hosts」功能从你自己的私有地址拉取，或手动填入。
- 这是通用工具，与任何具体服务无关。
