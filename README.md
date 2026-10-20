# NAS Monitor

自托管 NAS 与 AI 网关监控面板，提供 Android App 和同源 Web 界面。

[English](README.en.md) · 中文

## 当前版本

当前文档版本为 **v238.1**。Android 应用版本保持 **238 / 238.0**；本次补丁只整理 README 和截图布局，不修改业务源码、APK 或截图素材。

主要能力：

- 服务器总览、趋势、磁盘、温度、容器、SSH 与安全状态。
- New API 网关概览、日志、渠道、路由策略和模型用量。
- 1 天、7 天、30 天模型用量：请求数、Token、环形分布和双序列趋势。
- qBittorrent、Transmission 下载器，以及 Mihomo、机场看板和代理设置。
- WorkBuddy 多出口账号池、动态远程服务器和可选工具入口。

版本变化见 [更新记录](CHANGELOG.md)。

## 用量页

原“模型”Tab 已替换为“用量”。页面只展示有调用的模型、请求数和 Token，不展示费用、金额、quota 或美元指标。后端只读 SQLite 聚合，使用 300 秒快照、single-flight 刷新和持久化 stale 降级。

| Android 用量页 | Android 趋势 |
| --- | --- |
| ![Android 用量页](docs/screenshots/13-用量页-Android-30天.png) | ![Android 用量趋势](docs/screenshots/14-用量趋势-Android-30天.png) |

| Web 桌面 | Web 移动端 |
| --- | --- |
| ![Web 桌面用量页](docs/screenshots/15-用量页-Web桌面-30天.png) | ![Web 移动端用量页](docs/screenshots/16-用量页-Web移动端-30天.png) |

截图保持 v238 已授权素材的原始画面和尺寸，仅清除图片元数据；本轮没有新增视觉授权。

## 功能截图

### 服务器与日志

| 服务器总览 | 网关概览 | 网关日志 |
| --- | --- | --- |
| ![服务器总览](docs/screenshots/01-服务器总览.jpg) | ![网关概览](docs/screenshots/02-网关概览.jpg) | ![网关日志](docs/screenshots/03-网关日志.jpg) |

- 日志详情：[查看截图](docs/screenshots/04-日志详情.jpg)
- 使用日报：[查看截图](docs/screenshots/05-使用日报.jpg)
- NAS 详情：[查看截图](docs/screenshots/07-NAS详情.jpg)

### 下载器

| 下载器概览 | qBittorrent 任务 |
| --- | --- |
| ![下载器概览](docs/screenshots/08-下载器概览.jpg) | ![qBittorrent 任务](docs/screenshots/09-qBittorrent任务.jpg) |

### 代理与机场

| 机场看板 | 代理概览 | 代理设置 |
| --- | --- | --- |
| ![机场看板](docs/screenshots/10-机场看板.jpg) | ![代理概览](docs/screenshots/11-代理概览.jpg) | ![代理设置](docs/screenshots/12-代理设置.jpg) |

### 工具

[打开工具截图](docs/screenshots/06-工具.jpg)

## 快速开始

### 后端与 Web

先确认 Docker、端口、数据卷空间，以及 Glances 容器或进程的实际主机和访问地址。在用户允许的目录部署：

```bash
cp backend/.env.example .env
chmod 600 .env
```

在本地生成 24 字节以上随机 Token，例如 `openssl rand -hex 24`，填入 `.env`。不要把生成值发到聊天或提交。至少填写：

```dotenv
APP_API_TOKEN=CHANGE_ME_RANDOM_TOKEN
WEB_API_TOKEN=CHANGE_ME_ANOTHER_RANDOM_TOKEN
NAS_HOST=CHANGE_ME_HOST
NAS_GLANCES=http://<用户确认的指标源地址>:<实际端口>
NAS_MONITOR_SCHEDULER=0
```

```bash
docker compose -f docker-compose.example.yml config --quiet
docker compose -f docker-compose.example.yml up -d --build
docker compose -f docker-compose.example.yml exec -T nas-monitor-api python3 -c "import urllib.request; print(urllib.request.urlopen('http://127.0.0.1:8766/health').status)"
```

浏览器访问用户选择的入口，使用 `WEB_API_TOKEN` 或 `APP_API_TOKEN` 登录，确认总览显示真实 Glances 指标。静态页匿名加载；数据接口需要 Bearer Token。业务端口为 8765，免鉴权健康探针仅容器 loopback 8766。

若 Glances 在另一个容器，`127.0.0.1` 在 monitor 容器内只代表 monitor 自身；使用核实过的宿主 LAN 地址或共享 Docker 网络服务名。没有采集器时由用户决定是否新建，不得伪造指标。

### Android 验证

接收方在本地设置 Android SDK 路径，不包含作者的 `local.properties`、签名库或缓存：

```bash
cd HADashboard
bash ./gradlew --no-daemon testDebugUnitTest
```

首次启动由用户配置自己的后端 URL 和 Token；SSH 主机、用户名、口令和密钥也在本地配置。需要正式 APK 时自行构建并生成签名。当前 release 无 keystore 时回退 debug 签名，仅适合开发。

## 可选能力

HA、iKuai/OpenWrt、New API、WorkBuddy、知识库和上游用量均为可选，按配置说明选择。不要因模块名就安装或登录对应服务。动态远程主机可从管理页添加。

容器列表、日志和控制能力仅在用户授权时启用：

```bash
stat -c '%g' /var/run/docker.sock
docker compose -f docker-compose.example.yml -f docker-compose.docker.yml config --quiet
docker compose -f docker-compose.example.yml -f docker-compose.docker.yml up -d --build
```

把第一条命令的数值填入 `.env` 的 `DOCKER_GID`。持有 Docker socket 权限可控制宿主机，`:ro` 不限制 Docker API 的写操作。

## 安全边界

- `.env`、账号目录、运行数据、日志、备份、APK、签名库、构建缓存和生产 Git 历史不得进入公开源码。
- Token、密码、私钥和 Cookie 只保存在用户自己的环境或凭据存储中，不提交、不截图、不发送到聊天。
- 默认监听宿主机 loopback。需要 LAN/VPN 访问时，用户明确选择后再设置 `MONITOR_BIND_ADDRESS`；同主机反向代理保持 `127.0.0.1`。
- 公网访问使用 TLS 或 VPN，Glances、管理 API 和 Docker socket 不得直接暴露到公网。
- 截图仅展示用户确认的 demo 内容。后续新增截图必须重新审查，不沿用一次性展示授权。

后端验证使用隔离环境和假上游，不加载作者或其他项目的 `.env`，也不向生产数据卷写入测试数据。

## 文档与源码

- [AI 接手指南](docs/AI接手指南.md)
- [配置与凭据获取说明](docs/配置与凭据获取说明.md)
- [环境勘察表](docs/环境勘察表.example.md)
- [需求规格](docs/需求规格.md)
- [源码交付审查报告](docs/源码交付审查报告.md)
- [更新记录](CHANGELOG.md)

`backend/api_server.py` 是后端入口，`backend/web/` 是同源 Web 单页，`HADashboard/` 是 Android 工程。需求文档中的伪代码不是启动入口。

## License

MIT，见 [LICENSE](LICENSE)。
