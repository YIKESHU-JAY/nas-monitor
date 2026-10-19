# NAS Monitor 源码交付（v236）

[English](README.en.md) · 中文

本包包含实际后端、Web 前端、Android App 源码与构建配置，供接收方 AI 依据本地环境部署和适配。解压后从 [AI接手指南](docs/AI接手指南.md) 开始，先勘察已有服务并确认需要接入哪些，再配置。

GitHub 工作流仅手动触发；上传源码不会自动构建 APK 或发布镜像。正式发行时由维护者配置自己的签名 Secrets 并主动运行。

> **写在前面**：这个项目最初只是作者自用的小工具，从写代码到部署上线全程由 AI 完成，功能取舍都以作者自己的 NAS 环境为准，未必适合所有人的场景，也没打算做成通用产品。如果你恰好也有类似需求，欢迎参考和按需改造；如果和你的用法不同，也完全理解，希望能帮上一点忙就好 🙂
 
## 界面预览

| 服务器总览 | 网关概览 | 网关日志 |
| --- | --- | --- |
| ![服务器总览](docs/screenshots/01-服务器总览.jpg) | ![网关概览](docs/screenshots/02-网关概览.jpg) | ![网关日志](docs/screenshots/03-网关日志.jpg) |

| 日志详情 | 使用日报 | 工具 |
| --- | --- | --- |
| ![日志详情](docs/screenshots/04-日志详情.jpg) | ![使用日报](docs/screenshots/05-使用日报.jpg) | ![工具](docs/screenshots/06-工具.jpg) |

| NAS 详情 | | |
| --- | --- | --- |
| ![NAS 详情](docs/screenshots/07-NAS详情.jpg) | | |

> 截图为作者自建环境的演示数据，已移除拍摄设备信息；主机地址、渠道名等仅代表该演示环境。

## 包内结构
 
- `backend/api_server.py`：Python 3.11 后端入口。
- `backend/web/`：实际 Web 单页，后端同源托管，无需 Node 构建。
- `backend/collectors/`：上游适配与可选采集任务。
- `HADashboard/`：Android v236/236.0，包名 `io.github.nasmonitor.app`，JDK 17 / SDK 34 / Gradle 8.7。
- `docs/需求规格.md` 与 `docs/assets/`：功能需求与结构图。需求文档中的伪代码不是启动入口。
- [配置与凭据获取说明](docs/配置与凭据获取说明.md)：配置作用、获取方式与保管边界。
- [环境勘察表](docs/环境勘察表.example.md)：让接收方填写自己的服务拓扑。
 
## 最小部署：后端 + Web + 已有 Glances v4
 
先核实 Docker、端口、数据卷空间，以及 Glances 容器/进程所在主机与实际访问地址；在用户允许的目标项目目录部署。基础模板不挂载 Docker socket，默认关闭可选调度器。
 
```bash
cp backend/.env.example .env
chmod 600 .env
```
 
由用户本地生成 24 字节以上随机 Token（例如 `openssl rand -hex 24`），填入 `.env`；不要把生成值发到聊天或提交。至少填写：
 
```dotenv
APP_API_TOKEN=CHANGE_ME_RANDOM_TOKEN
WEB_API_TOKEN=CHANGE_ME_ANOTHER_RANDOM_TOKEN
NAS_HOST=CHANGE_ME_HOST
NAS_GLANCES=http://<用户确认的指标源地址>:<实际端口>
NAS_MONITOR_SCHEDULER=0
```
 
上面是占位符，必须替换。默认监听宿主机 loopback。如从另一台电脑访问，用户明确选择 LAN/VPN 后可设置 `MONITOR_BIND_ADDRESS=<宿主机实际LAN或VPN地址>`；反代同主机时保持 `127.0.0.1`。公网采用 TLS 或 VPN，Glances 和管理 API 不直接公开。
 
```bash
docker compose -f docker-compose.example.yml config --quiet
docker compose -f docker-compose.example.yml up -d --build
docker compose -f docker-compose.example.yml exec -T nas-monitor-api python3 -c "import urllib.request; print(urllib.request.urlopen('http://127.0.0.1:8766/health').status)"
```
 
浏览器访问用户选择的入口，输入 WEB_API_TOKEN（APP_API_TOKEN 也可登录），确认总览显示来自真实 Glances 的 CPU、内存、磁盘和网络指标。静态页匿名加载；数据接口有 Bearer Token 鉴权；业务端口 8765、免鉴权健康探针仅在容器 loopback 8766。
 
若 Glances 在另一个容器，`127.0.0.1` 在 monitor 容器内只代表 monitor 自身；使用核实过的宿主 LAN 地址或共享 Docker 网络服务名。没有采集器时由用户选择是否新建，不得伪造指标。
 
## 可选 Docker 能力
 
仅在需要容器列表/日志/控制且用户授权时启用：
 
```bash
stat -c '%g' /var/run/docker.sock
docker compose -f docker-compose.example.yml -f docker-compose.docker.yml config --quiet
docker compose -f docker-compose.example.yml -f docker-compose.docker.yml up -d --build
```
 
用户将第一条命令的数值填入 `.env` 的 DOCKER_GID。持有 socket 权限可控制宿主机，`:ro` 不限制 Docker API 的写操作。非标准 socket 路径需同步修改 override 的宿主挂载源。
 
HA、iKuai/OpenWrt、New API、WorkBuddy、知识库与上游用量都是可选；按配置说明选择。不要因看到模块名就安装或登录对应服务。动态远程主机可从管理页面添加，无须沿用厂商命名。
 
## Android 源码验证
 
接收方在本地设置 Android SDK 路径（不包含作者的 local.properties、签名库或缓存）：
 
```bash
cd HADashboard
bash ./gradlew --no-daemon testDebugUnitTest
```
 
首次安装由用户配置自己的后端 URL 和 Token，SSH 页面中的主机/用户名/口令/密钥也由用户本地配置。需要正式 APK 时自行构建并生成自己的签名库；本交付包不含 APK。当前 release 无 keystore 时回退 debug 签名，仅适合开发。
 
## 隐私边界与测试
 
本包已排除生产 .env、凭据文件、运行数据、Git 历史、备份、日志、APK、签名库、构建缓存。账号昵称、用户 ID、个人 URL 与日志样例已去除或改为用户配置；保留框架/SDK依赖域名和 RFC 保留示例域名。
 
后端验证使用隔离环境与假上游：`python3 -m unittest discover -s backend`。不要加载作者或其他项目的 .env，也不要把测试写入生产数据卷。具体审查范围与验证结果见 `docs/源码交付审查报告.md`。
