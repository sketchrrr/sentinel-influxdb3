package com.alibaba.csp.sentinel.dashboard.util;

import com.influxdb.v3.client.InfluxDBClient;
import com.influxdb.v3.client.Point;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

@Component
public class InfluxDBUtils {

    private static final Logger logger = LoggerFactory.getLogger(InfluxDBUtils.class);

    // InfluxDB 3.x 客户端是线程安全的，建议作为单例长期持有
    private static InfluxDBClient client;

    private static String url;
    private static String token;     // 3.x 使用 Token
    private static String database;  // 3.x 需要指定数据库/Bucket
    private static String org;       // 可选，视服务器配置而定

    @Value("${influxdb.url}")
    public void setUrl(String url) {
        InfluxDBUtils.url = url;
    }

    @Value("${influxdb.token}")
    public void setToken(String token) {
        InfluxDBUtils.token = token;
    }

    @Value("${influxdb.database}")
    public void setDatabase(String database) {
        InfluxDBUtils.database = database;
    }

    @Value("${influxdb.org:}") // 默认为空，部分环境可能需要
    public void setOrg(String org) {
        InfluxDBUtils.org = org;
    }

    /**
     * 初始化客户端 (Spring 启动时自动调用)
     */
    @PostConstruct
    public void init() {
        try {
            // 初始化 InfluxDB 3 客户端
            // 注意：如果你的 InfluxDB 是 HTTPS 且有自签名证书，可能需要额外的 SSL 配置
            if (client == null) {
                client = InfluxDBClient.getInstance(url, token.toCharArray(), database);
                logger.info("InfluxDB 3.x client initialized successfully.");
            }
        } catch (Exception e) {
            logger.error("Failed to initialize InfluxDB client", e);
        }
    }

    /**
     * 销毁客户端 (Spring 关闭时自动调用)
     */
    @PreDestroy
    public void close() {
        if (client != null) {
            try {
                client.close();
            } catch (Exception e) {
                logger.error("Error closing InfluxDB client", e);
            }
        }
    }

    /**
     * 写入单条数据
     *
     * @param point InfluxDB 3.x 的 Point 对象
     */
    public static void insert(Point point) {
        try {
            client.writePoint(point);
        } catch (Exception e) {
            logger.error("[InfluxDB Insert Error]", e);
        }
    }

    /**
     * 写入多条数据
     *
     * @param points Point 列表
     */
    public static void insertBatch(List<Point> points) {
        try {
            for (Point point : points) {
                client.writePoint(point); // 3.x 客户端内部有批处理机制，也可以循环写入
            }
        } catch (Exception e) {
            logger.error("[InfluxDB Batch Insert Error]", e);
        }
    }

    /**
     * 执行 SQL 查询并手动映射结果
     *
     * @param sql    SQL 查询语句 (注意：InfluxDB 3 推荐使用 SQL)
     * @param mapper 将 Object[] 转换为 Java 对象的函数接口
     * @param <T>    目标类型
     * @return 结果列表
     */
    public static <T> List<T> queryList(String sql, RowMapper<T> mapper) {
        List<T> result = new ArrayList<>();
        try (Stream<Object[]> stream = client.query(sql)) {
            stream.forEach(row -> {
                try {
                    result.add(mapper.mapRow(row));
                } catch (Exception e) {
                    logger.error("Error mapping row", e);
                }
            });
        } catch (Exception e) {
            logger.error("[InfluxDB Query Error] SQL: {}", sql, e);
        }
        return result;
    }

    /**
     * 简单的行映射接口 (替代旧版的 ResultMapper)
     */
    @FunctionalInterface
    public interface RowMapper<T> {
        T mapRow(Object[] row);
    }
}
