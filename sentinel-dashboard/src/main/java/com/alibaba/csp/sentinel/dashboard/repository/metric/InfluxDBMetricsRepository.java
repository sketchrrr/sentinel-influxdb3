package com.alibaba.csp.sentinel.dashboard.repository.metric;

import com.alibaba.csp.sentinel.dashboard.datasource.entity.MetricEntity;
import com.alibaba.csp.sentinel.dashboard.util.InfluxDBUtils;
import com.alibaba.csp.sentinel.util.StringUtil;
import com.influxdb.v3.client.Point;
import org.springframework.stereotype.Repository;
import org.springframework.util.CollectionUtils;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Metrics数据 InfluxDB 3.x 存储实现
 */
@Repository("influxDBMetricsRepository")
public class InfluxDBMetricsRepository implements MetricsRepository<MetricEntity> {

    private static final String METRIC_MEASUREMENT = "sentinel_metric";

    /**
     * 固定查询列顺序，保证 Object[] 索引映射可靠。
     * 顺序：time(0), app(1), resource(2), id(3), gmtCreate(4), gmtModified(5),
     *       passQps(6), successQps(7), blockQps(8), exceptionQps(9), rt(10), count(11), resourceCode(12)
     */
    private static final String SELECT_COLUMNS =
            "time, app, resource, id, \"gmtCreate\", \"gmtModified\", \"passQps\", \"successQps\", \"blockQps\", \"exceptionQps\", rt, count, \"resourceCode\"";

    @Override
    public void save(MetricEntity metric) {
        if (metric == null || StringUtil.isBlank(metric.getApp())) {
            return;
        }
        InfluxDBUtils.insert(convertToPoint(metric));
    }

    @Override
    public void saveAll(Iterable<MetricEntity> metrics) {
        if (metrics == null) {
            return;
        }
        List<Point> points = new ArrayList<>();
        metrics.forEach(metric -> {
            if (metric != null && StringUtil.isNotBlank(metric.getApp())) {
                points.add(convertToPoint(metric));
            }
        });
        if (!points.isEmpty()) {
            InfluxDBUtils.insertBatch(points);
        }
    }

    @Override
    public List<MetricEntity> queryByAppAndResourceBetween(String app, String resource, long startTime, long endTime) {
        if (StringUtil.isBlank(app) || StringUtil.isBlank(resource)) {
            return new ArrayList<>();
        }
        String sql = String.format(
                "SELECT %s FROM \"%s\" WHERE app = '%s' AND resource = '%s'"
                + " AND time >= to_timestamp_millis(%d) AND time <= to_timestamp_millis(%d) ORDER BY time DESC",
                SELECT_COLUMNS, METRIC_MEASUREMENT, app, resource,
                startTime, endTime
        );
        return InfluxDBUtils.queryList(sql, this::mapRowToEntity);
    }

    @Override
    public List<String> listResourcesOfApp(String app) {
        if (StringUtil.isBlank(app)) {
            return new ArrayList<>();
        }
        long startTime = System.currentTimeMillis() - 60_000L;
        String sql = String.format(
                "SELECT %s FROM \"%s\" WHERE app = '%s' AND time >= to_timestamp_millis(%d)",
                SELECT_COLUMNS, METRIC_MEASUREMENT, app, startTime
        );

        List<MetricEntity> metricEntities = InfluxDBUtils.queryList(sql, this::mapRowToEntity);
        if (CollectionUtils.isEmpty(metricEntities)) {
            return new ArrayList<>();
        }

        Map<String, MetricEntity> resourceCount = new ConcurrentHashMap<>(32);
        for (MetricEntity metricEntity : metricEntities) {
            String resource = metricEntity.getResource();
            if (resourceCount.containsKey(resource)) {
                MetricEntity oldEntity = resourceCount.get(resource);
                oldEntity.addPassQps(metricEntity.getPassQps());
                oldEntity.addRtAndSuccessQps(metricEntity.getRt(), metricEntity.getSuccessQps());
                oldEntity.addBlockQps(metricEntity.getBlockQps());
                oldEntity.addExceptionQps(metricEntity.getExceptionQps());
                oldEntity.addCount(1);
            } else {
                resourceCount.put(resource, MetricEntity.copyOf(metricEntity));
            }
        }

        return resourceCount.entrySet()
                .stream()
                .sorted((o1, o2) -> {
                    MetricEntity e1 = o1.getValue();
                    MetricEntity e2 = o2.getValue();
                    int t = e2.getBlockQps().compareTo(e1.getBlockQps());
                    if (t != 0) {
                        return t;
                    }
                    return e2.getPassQps().compareTo(e1.getPassQps());
                })
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());
    }

    /**
     * 将 MetricEntity 转换为 InfluxDB 3.x Point 对象。
     * app、resource 作为 Tag 存储以支持高效过滤；其余字段作为 Field 存储。
     */
    private Point convertToPoint(MetricEntity metric) {
        if (metric.getId() == null) {
            metric.setId(System.currentTimeMillis());
        }
        return Point.measurement(METRIC_MEASUREMENT)
                .setTag("app", metric.getApp())
                .setTag("resource", metric.getResource())
                .setField("id", metric.getId())
                .setField("gmtCreate", metric.getGmtCreate().getTime())
                .setField("gmtModified", metric.getGmtModified().getTime())
                .setField("passQps", metric.getPassQps())
                .setField("successQps", metric.getSuccessQps())
                .setField("blockQps", metric.getBlockQps())
                .setField("exceptionQps", metric.getExceptionQps())
                .setField("rt", metric.getRt())
                .setField("count", metric.getCount())
                .setField("resourceCode", metric.getResourceCode())
                .setTimestamp(Instant.ofEpochMilli(metric.getTimestamp().getTime()));
    }

    /**
     * 将查询结果行映射为 MetricEntity。
     * 列索引与 SELECT_COLUMNS 中的顺序严格对应：
     * 0:time  1:app  2:resource  3:id  4:gmtCreate  5:gmtModified
     * 6:passQps  7:successQps  8:blockQps  9:exceptionQps  10:rt  11:count  12:resourceCode
     */
    private MetricEntity mapRowToEntity(Object[] row) {
        MetricEntity entity = new MetricEntity();
        try {
            if (row[0] instanceof Instant) {
                entity.setTimestamp(Date.from((Instant) row[0]));
            } else {
                entity.setTimestamp(new Date());
            }
            entity.setApp((String) row[1]);
            // setResource 内部会同步设置 resourceCode，故优先调用
            entity.setResource((String) row[2]);
            entity.setId(toLong(row[3]));
            entity.setGmtCreate(row[4] != null ? new Date(toLong(row[4])) : new Date());
            entity.setGmtModified(row[5] != null ? new Date(toLong(row[5])) : new Date());
            entity.setPassQps(toLong(row[6]));
            entity.setSuccessQps(toLong(row[7]));
            entity.setBlockQps(toLong(row[8]));
            entity.setExceptionQps(toLong(row[9]));
            entity.setRt(toDouble(row[10]));
            entity.setCount(toInt(row[11]));
            // row[12]: resourceCode — MetricEntity 未暴露 setResourceCode，
            // setResource 已在内部通过 hashCode() 自动同步，此处仅做读取校验，不需额外赋值
        } catch (Exception e) {
            // 单行映射异常不影响其他行，由 InfluxDBUtils 记录异常日志
        }
        return entity;
    }

    private Long toLong(Object val) {
        if (val == null) {
            return 0L;
        }
        if (val instanceof Long) {
            return (Long) val;
        }
        if (val instanceof Number) {
            return ((Number) val).longValue();
        }
        return 0L;
    }

    private double toDouble(Object val) {
        if (val == null) {
            return 0.0;
        }
        if (val instanceof Number) {
            return ((Number) val).doubleValue();
        }
        return 0.0;
    }

    private int toInt(Object val) {
        if (val == null) {
            return 0;
        }
        if (val instanceof Number) {
            return ((Number) val).intValue();
        }
        return 0;
    }
}
