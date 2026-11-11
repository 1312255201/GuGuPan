# GuGuPan 咕咕盘

轻量个人文件中转站。管理员上传并分享文件，也可以创建收件箱，让访客通过浏览器向自己发送文件。

Java 21、Spring Boot 3.5.16、Spring Security、H2 文件数据库、Vue 3、TypeScript、Vite。前端构建产物打入同一个 JAR，运行时只需要 Java 21 和可写的数据目录。

## 已实现的功能

- 管理员登录、退出、修改密码、会话失效和请求限流。
- 拖拽与多文件上传、逐文件进度、取消和失败重试。
- 文件搜索、来源筛选、分页、下载、删除、延长保留时间。
- 单个或多个文件创建分享，提供随机链接、10 位取件码和二维码。
- 分享支持访问密码、1 / 3 / 7 / 30 天有效期和立即关闭。
- 独立收件箱支持说明、访问密码、有效期、文件数量和容量限制。
- 访客上传后获取回执，管理员在「收到的文件」中查看和下载。
- 管理存储总额度、单文件上限和默认文件保留时间。
- 每 10 分钟清理到期文件和崩溃遗留的无引用文件。
- 页面适配手机、桌面浏览器，字体随应用提供。

默认总存储额度为 20 GiB，单文件上限为 1 GiB，文件保留 7 天。界面使用 GB / MB 表示 1024 进制容量。上传按文件逐个执行，中断后重试会重新传输整个文件。

分享有效期只控制链接访问。文件保留期控制实际文件清理。任一期限到达都会停止新的下载请求，已经开始的下载可以完成。删除文件后，所有关联分享会失去该文件。

收件箱额度与系统总额度共享，按当前仍保留的文件计算。并发上传预留容量和文件数，避免多个请求同时超过额度。全站最多同时处理 8 个上传任务。

## Windows 本机启动

本项目脚本优先使用本机 `D:\Software\jdk21.0.12_9`，构建完成后恢复原有的 `JAVA_HOME` 和 `PATH`。

```powershell
cd C:\Users\260700119\Documents\GitHub\GuGuPan
.\build.ps1
.\run.ps1
```

打开 <http://localhost:8080>，点击「管理文件」登录。默认用户名为 `admin`。首次启动时，如果没有提供管理员密码，会生成随机密码并写入 `data/initial-admin-password.txt`。在服务器本机查看该文件，登录后可在系统设置中修改密码。

也可以明确指定 Java 21 的位置和端口。

```powershell
.\build.ps1 -JdkHome 'D:\Software\jdk21.0.12_9'
.\run.ps1 -JdkHome 'D:\Software\jdk21.0.12_9' -Port 8081
```

构建需要 Maven、Node.js 22.12+ 和 npm。`build.ps1` 安装前端依赖、执行 TypeScript 检查、构建前端，并运行后端集成测试和打包。生成 `target/gugupan.jar`。

## JAR 部署

把构建得到的 `gugupan.jar` 复制到服务器的应用目录，使用 Java 21 启动。

```sh
java -jar gugupan.jar --server.address=127.0.0.1 --server.port=8080
```

数据默认写入当前工作目录下的 `data`，可以通过 `GUGUPAN_DATA_DIR` 指定绝对路径。Linux 源码构建使用 `sh build.sh`。应用由单个 JVM 进程打开 H2 数据库，扩容时需要相应调整数据库和文件存储方案。

IDEA 导入根目录 `pom.xml`，为当前项目设置 JDK 21 和 Maven Runner JRE 21。前端可单独启动开发服务。

```powershell
cd frontend
npm ci
npm run dev
```

开发服务将 `/api` 代理到 `http://127.0.0.1:8080`。启动后端后，在 Vite 输出的本地地址访问前端。

## Docker Compose 部署

服务器安装 Docker 和 Compose 插件后，在项目根目录执行以下命令。

```sh
cp .env.example .env
docker compose up -d --build
docker compose logs -f app
```

应用端口默认绑定 `127.0.0.1:8080`，交给反向代理提供 HTTPS。应用以 UID / GID 10001 运行，数据持久化到命名卷 `gugupan-data`。在首次启动后查看随机初始密码。

```sh
docker compose exec app cat /app/data/initial-admin-password.txt
```

如果已有 Nginx 或服务器面板，将站点反向代理到 `http://127.0.0.1:8080`。`deploy/nginx.conf.example` 包含上传超时、流式转发、大小限制和真实客户端地址配置示例。在 `.env` 中设置 `COOKIE_SECURE=true` 后重新创建应用容器。

如果希望使用附带的 Caddy 自动申请证书，将域名解析到服务器，开放 80 / 443 端口。在 `.env` 中设置 `GUGUPAN_DOMAIN` 为实际域名，并设置 `COOKIE_SECURE=true`，然后执行以下命令。

```sh
docker compose --profile https up -d --build
```

通过 `https://你的域名` 访问。反向代理需要保留流式请求体，以便应用边接收边检查容量。使用外部 CDN 时，其上传大小和超时限制也会影响传输。

## 配置

| 环境变量 | 默认值 | 作用 |
| --- | --- | --- |
| `GUGUPAN_DATA_DIR` | `./data` | JAR 运行时的数据目录 |
| `GUGUPAN_ADMIN_USERNAME` | `admin` | 第一次初始化管理员时使用 |
| `GUGUPAN_ADMIN_PASSWORD` | 随机生成 | 第一次初始化管理员时使用 |
| `PORT` | `8080` | JAR 的监听端口 |
| `COOKIE_SECURE` | `false` | HTTPS 部署时设置为 `true` |
| `GUGUPAN_PORT` | `8080` | Compose 映射到宿主机的端口 |
| `GUGUPAN_DOMAIN` | `.env` 中配置 | 可选 Caddy 的站点域名 |
| `GUGUPAN_TRUSTED_PROXIES` | 回环与 Docker 私网地址正则 | Compose 中可信代理地址范围 |

管理员用户名和密码环境变量只在空数据库首次初始化时使用。此后通过后台修改密码。密码修改会使已登录管理会话退出，并删除初始密码文件。访问密码至少 4 个字符，管理员新密码至少 10 个字符，密码 UTF-8 编码长度均不超过 72 字节。

JAR 直接接入本机 Nginx 时，可以设置 `SERVER_FORWARD_HEADERS_STRATEGY=native`，并将 `SERVER_TOMCAT_REMOTEIP_INTERNAL_PROXIES` 设置为实际可信代理地址正则。限流使用客户端 IP。通过可信代理还原真实地址后，每个客户端分别计数。

## 数据与备份

```text
data/
  db/                         H2 数据库
  files/                      以服务端 UUID 命名的文件
  tmp/                        正在上传的临时文件
  initial-admin-password.txt  随机生成的首次登录密码
```

升级 JAR 时保留整个 `data` 目录。备份时先停止应用，再复制完整数据目录，保持数据库和文件一致。Compose 可采用下面的方式。

```sh
docker compose stop app
mkdir -p backups
docker compose cp app:/app/data ./backups/gugupan-data
docker compose start app
```

每次使用新的备份子目录。恢复时停止应用，将备份的完整数据目录恢复到应用数据卷，并确保 UID / GID 10001 可读写，再启动应用。迁移到另一台服务器时一并迁移数据库和上传文件。

## 验证

在 Java 21 环境中运行 `mvn test`。集成测试使用隔离的 H2 内存数据库和系统临时目录，检查实际权限链、分享密码和成员校验、链接关闭、收件箱上传回执与额度、过期清理，以及并发上传的容量预留和失败释放。

接口健康检查为 `GET /api/health`。浏览器中以管理员身份进入「我的文件」，上传文件后选择「分享」，即可验证收发流程。
