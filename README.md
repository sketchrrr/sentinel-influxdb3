# Sentinel 1.8.8 + InfluxDB 3 持久化扩展版

## 项目简介

本项目基于 [Alibaba Sentinel 1.8.8](https://github.com/alibaba/Sentinel) 进行二次开发，在原有功能基础上新增了 **InfluxDB 3** 作为监控指标（Metrics）的持久化存储后端，替代默认的内存存储方案，解决了控制台重启后历史数据丢失的问题。

---

## 主要改动

改动范围集中在 `sentinel-dashboard` 子模块：

| 文件 | 类型 | 说明 |
|---|---|---|
| `InfluxDBMetricsRepository.java` | 新增 | 实现 `MetricsRepository` 接口，通过 InfluxDB 3 存储和查询指标数据 |
| `InfluxDBUtils.java` | 新增 | InfluxDB 3 客户端工具类，管理连接生命周期及增删查操作 |
| `MetricPO.java` | 新增 | InfluxDB 指标数据映射实体 |
| `MetricFetcher.java` | 修改 | 通过 `@Qualifier` 注入 `InfluxDBMetricsRepository` |
| `MetricController.java` | 修改 | 通过 `@Qualifier` 注入 `InfluxDBMetricsRepository` |
| `pom.xml` | 修改 | 新增 `influxdb3-java` 依赖 |
| `application.properties` | 修改 | 新增 InfluxDB 连接配置项 |

---

## 数据模型

- **Measurement**：`sentinel_metric`
- **Tags**：`app`、`resource`
- **Fields**：`passQps`、`successQps`、`blockQps`、`exceptionQps`、`rt`、`count`、`resourceCode` 等
- **Timestamp**：来源于 `MetricEntity` 的时间戳（毫秒）

---

## 环境要求

- JDK 8+
- Maven 3.6+
- InfluxDB 3.x（本地或远端部署）

---

## 配置说明

在 `sentinel-dashboard/src/main/resources/application.properties` 中添加以下配置：

```properties
# InfluxDB 3 连接地址
influxdb.url=http://localhost:8181

# InfluxDB 3 API Token
influxdb.token=<your-influxdb-token>

# 目标数据库（Database）名称
influxdb.database=sentinel
```

> Token 需在 InfluxDB 控制台创建，并赋予目标数据库的读写权限。

---

## 构建与启动

### 1. 编译打包

```bash
# 在项目根目录执行，仅构建 sentinel-dashboard 模块
mvn clean package -pl sentinel-dashboard -am -DskipTests
```

### 2. 基础启动

```bash
java -jar sentinel-dashboard/target/sentinel-dashboard.jar
```

启动后访问 `http://localhost:8080`，默认用户名/密码均为 `sentinel`。

### 3. 命令行参数启动

所有 `application.properties` 中的配置项均可通过 `-D` JVM 系统属性覆盖，无需修改配置文件。

**基础启动（含 Sentinel 自身参数）：**

```bash
java -Dserver.port=8080 \
  -Dcsp.sentinel.dashboard.server=localhost:8080 \
  -Dproject.name=sentinel-dashboard \
  -jar sentinel-dashboard.jar
```

**覆盖 InfluxDB 连接信息：**

```bash
java -Dserver.port=8080 \
  -Dcsp.sentinel.dashboard.server=localhost:8080 \
  -Dproject.name=sentinel-dashboard \
  -Dinfluxdb.url=http://192.168.1.100:8181 \
  -Dinfluxdb.token=<your-influxdb-token> \
  -Dinfluxdb.database=sentinel \
  -jar sentinel-dashboard.jar
```

**完整生产环境启动示例：**

```bash
java -Xms512m -Xmx1g \
  -Dserver.port=8080 \
  -Dcsp.sentinel.dashboard.server=localhost:8080 \
  -Dproject.name=sentinel-dashboard \
  -Dauth.username=admin \
  -Dauth.password=admin123 \
  -Dinfluxdb.url=http://192.168.1.100:8181 \
  -Dinfluxdb.token=<your-influxdb-token> \
  -Dinfluxdb.database=sentinel \
  -jar sentinel-dashboard.jar
```

**参数说明：**

| 参数 | 说明 | 默认值 |
|---|---|---|
| `-Dserver.port` | Dashboard 服务端口 | `8080` |
| `-Dcsp.sentinel.dashboard.server` | Dashboard 自身注册地址 | — |
| `-Dproject.name` | 应用名称 | `sentinel-dashboard` |
| `-Dauth.username` | 控制台登录用户名 | `sentinel` |
| `-Dauth.password` | 控制台登录密码 | `sentinel` |
| `-Dinfluxdb.url` | InfluxDB 3 服务地址 | `http://localhost:8181` |
| `-Dinfluxdb.token` | InfluxDB 3 API Token | — |
| `-Dinfluxdb.database` | InfluxDB 目标数据库名 | `sentinel` |

> Windows PowerShell 环境下将换行符 `\` 替换为 `` ` ``，或将所有参数写在同一行。

---

## 架构说明

```
Sentinel Client (业务应用)
        │  上报指标
        ▼
MetricFetcher (采集 & 聚合)
        │
        ▼
InfluxDBMetricsRepository  ──►  InfluxDB 3
        │
        ▼
MetricController (Dashboard 查询接口)
```

`InfluxDBMetricsRepository` 通过 Spring `@Qualifier` 替换默认的 `InMemoryMetricsRepository`，无需修改接口契约，对上层逻辑透明。

---

## 依赖声明

```xml
<dependency>
    <groupId>com.influxdb</groupId>
    <artifactId>influxdb3-java</artifactId>
    <version>RELEASE</version>
</dependency>
```

---

## 原始项目

- 官方仓库：https://github.com/alibaba/Sentinel
- 当前基础版本：`1.8.8`
