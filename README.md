# 考勤统计汇总系统

基于 Spring Boot + React 的考勤统计管理系统，支持考勤机导出文件的自动解析、工时计算、多维度汇总展示及 Excel/CSV 导出。

## 技术栈

- **后端**: Java 21 + Spring Boot 3.5.0 + Spring Security + Spring Data JPA + PostgreSQL 16
- **前端**: React 19 + Mantine UI + Vite
- **导出**: Apache POI (Excel) / OpenCSV (CSV)

## 功能特性

### 数据导入
- 支持 `.xls` / `.xlsx` 格式考勤机导出文件
- 自动识别 `考勤记录` sheet
- 上传新文件时自动同步人员（新增/删除/改名），已有规则完整保留

### 核心计算规则
- **午休扣除**: 四种组合规则，按人员独立配置
  - 普通：固定扣除 `12:00-13:00`（60 分钟）
  - 厂区住宿：固定扣除 `12:00-14:00`（120 分钟）
  - 弹性午休：默认扣除 `12:00-14:00`，若期间有打卡按最早恢复
  - 厂区住宿 + 弹性：同弹性午休规则
- **晚餐扣除**: 当天打卡次数 ≥4 时，额外扣除 60 分钟
- **跨天合并**: 次日凌晨 `06:00` 前打卡，且当天有白天打卡时，自动并入前一天
- **工时计算**: `floor(实际分钟 / 20) × 0.5`，每满 20 分钟计 0.5 工时

### 页面模块

| Tab | 说明 |
|-----|------|
| **人员汇总** | 按总工时降序，展示每人整月汇总，点击姓名跳转个人明细 |
| **个人明细** | 单人选中日历 + 逐日打卡记录、工时、异常标记 |
| **考勤汇总** | 矩阵表格：每人 × 每日工时，0 值显示 `-`，异常高亮，点击姓名跳转 |
| **全部明细** | 所有人逐日明细，支持全局搜索 |
| **原表数据** | 原始 Excel 文件结构预览 |
| **人员管理** | 独立配置每人的厂区住宿 / 弹性午休 / 晚餐扣除开关，特殊人员蓝色高亮 |
| **计算说明** | 系统规则文档 |

### 数据导出
- **Excel**: 三 Sheet（人员汇总 / 考勤汇总 / 每日明细），表头冻结、带边框、斑马纹
- **CSV**: 每日明细纯文本导出

### 安全特性
- Spring Security 表单登录 + 单一会话控制
- 登录限流（5 分钟内最多 5 次失败）
- API 未认证返回 401（防止重定向到登录页）
- 安全响应头：CSP、HSTS、X-Frame-Options、X-Content-Type-Options
- Cookie：HttpOnly、Secure、SameSite=Strict
- 文件上传：magic bytes 校验、仅允许 `.xls/.xlsx`、过滤路径遍历
- 请求审计日志

## 特殊人员默认规则

人员规则存储于 PostgreSQL `employee_rule` 表，上传新文件时自动保留已有开关设置，仅清理离职人员、新增人员、同步改名。

| 姓名 | 厂区住宿 | 弹性午休 | 晚餐扣除 |
|------|---------|---------|---------|
| 任杰 | ✓ | | ✓ |
| 王清玲 | ✓ | ✓ | |
| 梁宗茂 | ✓ | | |

## 本地开发

### 1. 环境准备
- Java 21
- Node.js 20+
- PostgreSQL 16（端口 5433，数据库 `attendance`）

### 2. 启动后端
```bash
mvn spring-boot:run
```

### 3. 启动前端（开发模式）
```bash
cd frontend
npm install
npm run dev
```

### 4. 生产构建
```bash
# 前端构建输出到 src/main/resources/static/app
cd frontend && npm run build

# 打包可执行 jar
mvn package -DskipTests
java -jar target/attendance-audit-0.0.1-SNAPSHOT.jar \
  --spring.profiles.active=prod
```

### 5. 访问
- 本地: `http://127.0.0.1:8080/app/index.html`
- 默认账号: `admin` / `AttendanceAdmin#2026`

## 线上部署

服务器：Ubuntu 24.04 + systemd + Nginx 反向代理

```bash
# 复制 jar 到服务器
scp target/attendance-audit-0.0.1-SNAPSHOT.jar server:/opt/attendance/attendance-audit.jar

# 重启服务
sudo systemctl restart attendance.service
```

Nginx 配置示例：
```nginx
location / {
    proxy_pass http://127.0.0.1:8080;
    proxy_set_header Host $host;
    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    proxy_set_header X-Forwarded-Proto https;
}
```

## 异常标记说明

以下情况会标淡红色背景：
- **跨天并单**：次日凌晨打卡并入本日
- **打卡次数异常**：仅 1 次或 2 次打卡
- **晚餐扣除**：当天有晚餐扣除记录
