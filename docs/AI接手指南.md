# 给接收方 AI：先勘察，再搭建

本包为 NAS Monitor v236 源码快照，包含 Python 后端、同源静态 Web 和 Android App。阅读根 README.md、配置与凭据获取说明.md、需求规格.md、AGENTS.md 后按以下顺序执行。

## 1. 核实当前环境

先确认当前 Workspace、源码位置、操作所在主机（本机/远程/容器）、操作系统、CPU 架构、可用数据卷和部署方式。临时目录、下载、Docker 构建缓存和 Gradle 缓存放用户允许的数据卷；不要默认塞进小容量系统分区。

在用户授权的本机范围内查看容器摘要：

```bash
docker ps -a --format '{{.Names}}\t{{.Image}}\t{{.Status}}\t{{.Ports}}'
```

只对候选相关容器核实状态、实际挂载和端口：

```bash
docker inspect <候选容器名> --format '{{.Name}} {{.State.Status}} {{json .Mounts}} {{json .NetworkSettings.Ports}}'
```

不要输出完整 inspect 或 Config.Env，它们可能含密码。先核实容器内路径与宿主机 Mounts 对应关系，再去看配置；没有映射证据时不能仅凭 README 推断宿主机位置。

若没有 Docker，按需核对该项目候选 systemd 服务、进程和监听端口。不要递归读取整台主机的配置、SSH 私钥、浏览器凭据、备份或其他项目日志。

勘察以下能力，并将结论写入环境勘察表：Glances v4/其他指标源、Docker/Podman、路由器管理 API、Home Assistant、New API、WorkBuddy、知识库、SSH、VPN、反代/TLS。软件名只是候选，存在不等于用户同意接入。

## 2. 向用户确认接入范围

发现已有服务后，列出“已发现/未发现/需确认”，询问：

- 哪些主机要监控，哪台运行聚合后端？
- 先只要 Web，还是同时配置 Android？本包不含 APK。
- 哪些可选服务需要接入，哪些应关闭？是否允许访问 Docker socket？
- 用户从内网、VPN 还是公网访问？有无可复用的反代、域名和证书？
- 凭据由用户在哪个本地文件配置？哪些服务允许只读访问？

先复用用户认可的已有服务。缺少组件时给出最小部署方案，由用户选择是否新增；不要自动部署全部扩展、覆盖已有同名容器、重启无关服务或复用其他项目的密钥。

## 3. 建立最小 Web 闭环

按根 README.md 启动真实 backend/api_server.py，静态资源来自 backend/web/。需求文档的骨架用于说明设计，不是本包启动脚本。

最小输入：APP_API_TOKEN 或 WEB_API_TOKEN、NAS_HOST、NAS_GLANCES。Token 由用户本地生成；Glances 地址根据勘察证据填写。可选字段留空，NAS_MONITOR_SCHEDULER=0。

基础 Compose 无 Docker socket，使用独立数据卷，默认宿主 loopback。用户从另一台设备浏览时选择已确认的 LAN/VPN 地址，或本机反代；不要把容器的 127.0.0.1 当宿主地址。

后端服务用户 UID/GID 为 10001；新增 bind mount 的文件需对该用户可读，数据目录可写。只挂所需 secret 文件，明确容器内目的路径；不要挂整个用户家目录或生产源码。

## 4. 按模块逐个接入

主机指标正常后再接容器、远程主机、路由器、HA、New API、WorkBuddy、知识库。每项记录上游接口版本、访问路径、认证方式、字段映射、失败状态。

容器模块通过 docker-compose.docker.yml 显式启用；Docker socket 权限可控制宿主机，:ro 不限制 API 操作。其他模块的 secret 挂载需接收方按自己的路径配置。

ALIYUN_*、TENCENT_* 只是兼容旧接口的可选变量名，不代表必须有对应厂商主机。普通远程主机可从主机管理页添加。

如果当地指标源不是 Glances v4，编写适配层返回原客户端所需结构，并做接口契约测试；不要将新的 JSON 直接喂给旧 Web/App。缺失能力应显示未配置/隐藏，不能返回伪造的健康数据。

## 5. 验收并交付自己的配置

- 后端免鉴权健康探针 8766 只访问自身，不依赖上游。
- Web 静态页可加载，错误 Token 被拒，正确 Token 登录后显示真实 CPU/内存/磁盘/网络。
- 上游离线时页面可用并显示未知/离线；没有配置的扩展不触发外部请求。
- Docker 不授权时没有 socket；启用后只连接已确认的主机。
- 页面换手机/浏览器也可通过用户选择的入口访问。
- Android 地址/Token/SSH 参数由用户首次启动配置；需要构建时再准备 SDK/JDK 和自己的签名库。
- 重启后用户数据按已确认的 volume 保留；备份和日志均不包含秘密值。

每次验证记录实际命令、预期与结果。未测试的外部集成标明未测试，先完成核心闭环，再分阶段增强。不得用“代码看起来正确”代替运行验证。
