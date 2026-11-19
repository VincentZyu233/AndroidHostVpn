# AndroidHostVpn

一个通用的 Android hosts 编辑 / DNS 劫持工具。

通过 Android `VpnService` 建立本地隧道，拦截 DNS 查询：匹配 hosts 表中配置的域名时返回指定 IP，其余查询转发到上游 DNS。**不内置任何 hosts 内容，默认是干净的空工具。**

## 用途

- 本地开发 / 调试：把域名指向内网 IP
- 临时屏蔽某域名（指向 0.0.0.0）
- 需要按域名重定向而不改路由器、不 root 的场景

## 功能

- 总开关（开启 / 关闭 DNS 劫持）
- hosts 编辑（`ip domain` 一行一条，支持 `#` 注释）
- 远程 hosts 拉取（从 URL 下载 hosts，便于集中维护、无需重装 App）
- 自定义上游 DNS（默认 `8.8.8.8`）
- 支持 IPv4 与 IPv6 域名重定向

## 构建

```bash
./gradlew assembleRelease
```

APK 输出在 `app/build/outputs/apk/release/`。Release 构建也由 GitHub Actions 自动完成，产物挂在 Release。

## 使用说明

1. 安装 App，打开后在 hosts 编辑框填入需要重定向的域名（格式见下方）。
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
