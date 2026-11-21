# 参与开发

## 开始前

阅读[需求文档](CareContinuo-需求文档.md)和[开发交接](AGENTS.md)，先说明目标、涉及角色和验收编号。环境需要 Java 21、Python 3.13、Node.js 24 和 Docker Compose。所有复现资料使用合成数据。

## 宿主源码开发

使用独立的本机 PostgreSQL 数据库，例如 `carecontinuo_dev`。为 Java 进程设置
`CARE_DB_URL=jdbc:postgresql://localhost:5432/carecontinuo_dev`、`CARE_DB_USER`、`CARE_DB_PASSWORD`
和 `SPRING_PROFILES_ACTIVE=demo`。在根目录执行 `./mvnw spring-boot:run`，Windows 用
`./mvnw.cmd spring-boot:run`。Flyway 初始化该库，API 监听 8080。

在 `agent` 执行 `uv run --locked uvicorn app.main:app --port 8000`。在 `web` 执行
`npm ci` 及 `npm run dev`，使用 Vite 输出的本机地址。Vite 将 `/api` 转给 8080。没有模型密钥时可走人工随访。
使用 macOS/Linux 的 `export` 或 Windows PowerShell 的 `$env:` 为各进程配置环境。开发库、演示库和预生产库应分别使用。

## 测试

- 项目根目录执行 `./mvnw -B verify`，Windows 使用 `./mvnw.cmd -B verify`。
- 在 `agent` 执行 `uv run --locked python -m pytest`。
- 在 `web` 执行 `npm ci`、`npm run check`、`npm run build`。首次运行浏览器测试可执行 `npx playwright install chromium`。
- 浏览器测试使用下列隔离项目。根目录运行 Compose，`web` 目录运行 Playwright。

```powershell
$env:CARE_WEB_PORT = '18081'
docker compose -f compose.yaml -f compose.verify.yaml -p carecontinuo-accept up --build --wait
$env:CARE_WEB_URL = 'http://127.0.0.1:18081'
$env:CARE_E2E_DB_CONTAINER = 'carecontinuo-accept-postgres-1'
Push-Location web
npm run test:e2e
Pop-Location
```

Playwright 启动时要求两个目标均显式配置，并根据容器标签和端口核验 Web 与 PostgreSQL 属于同一测试项目。CI 执行相同检查。测试使用模型替身和合成数据库。真实 GPT 验收单独记录模型、引用定位和错误状态，仓库中的配置示例只保留路径和变量名。

预生产验收使用独立 TLS、OIDC 测试身份和 PostgreSQL 卷，运行 [AC-10～14 用例与压测](docs/预生产部署与验收.md)。新增 Flyway 迁移需通过空库与 V7 样本升级，工作人员权限修改需验证已有会话失效。

## 提交约定

1. 以可独立验证的业务改动组织提交，提交前检查差异并运行相关测试。
2. PR 写清业务规则、影响范围、测试命令、结果和剩余风险。涉及迁移时写明升级顺序。已应用迁移保留原文。
3. 分阶段开发时，等上一阶段的提交通过 CI 再继续。失败在所属阶段定位并修复。
4. 安全问题按[安全政策](SECURITY.md)报告。
