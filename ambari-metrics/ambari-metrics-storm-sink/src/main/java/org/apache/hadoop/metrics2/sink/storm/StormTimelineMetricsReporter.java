/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.metrics2.sink.storm;

import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang3.ClassUtils;
import org.apache.hadoop.metrics2.sink.timeline.AbstractTimelineMetricsSink;
import org.apache.hadoop.metrics2.sink.timeline.TimelineMetric;
import org.apache.hadoop.metrics2.sink.timeline.TimelineMetrics;
import org.apache.hadoop.metrics2.sink.timeline.UnableToConnectException;
import org.apache.hadoop.metrics2.sink.timeline.configuration.Configuration;
import org.apache.storm.metric.api.DataPoint;
import org.apache.storm.metric.api.IClusterMetricsConsumer;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

public class StormTimelineMetricsReporter extends AbstractTimelineMetricsSink
  implements IClusterMetricsConsumer {

  public static final String CLUSTER_REPORTER_APP_ID = "clusterReporterAppId";
  public static final String DEFAULT_CLUSTER_REPORTER_APP_ID = "nimbus";

  private String hostname;
  private String collectorUri;
  private String applicationId;
  private int timeoutSeconds;
  private String port;
  private Collection<String> collectorHosts;
  private String zkQuorum;
  private String protocol;

  public StormTimelineMetricsReporter() {

  }

  @Override
  protected String getCollectorUri(String host) {
    return constructTimelineMetricUri(protocol, host, port);
  }

  @Override
  protected String getCollectorProtocol() {
    return protocol;
  }

  @Override
  protected int getTimeoutSeconds() {
    return timeoutSeconds;
  }

  @Override
  protected String getZookeeperQuorum() {
    return zkQuorum;
  }

  protected Collection<String> getConfiguredCollectorHosts() {
    return collectorHosts;
  }

  @Override
  protected String getCollectorPort() {
    return port;
  }

  @Override
  protected String getHostname() {
    return hostname;
  }


  @Override
  public void prepare(Object registrationArgument) {
    LOG.info("Preparing Storm Metrics Reporter");
    try {
      try {
        hostname = InetAddress.getLocalHost().getHostName();
        //If not FQDN , call  DNS
        if ((hostname == null) || (!hostname.contains("."))) {
          hostname = InetAddress.getLocalHost().getCanonicalHostName();
        }
      } catch (UnknownHostException e) {
        LOG.error("Could not identify hostname.");
        throw new RuntimeException("Could not identify hostname.", e);
      }
      Configuration conf = new Configuration("/storm-metrics2.properties");

      collectorHosts = parseHostsStringIntoCollection(conf.getProperty(COLLECTOR_HOSTS_PROPERTY).toString());
      port = conf.getProperty(COLLECTOR_PORT) != null ? conf.getProperty(COLLECTOR_PORT) : "6188";
      protocol = conf.getProperty(COLLECTOR_PROTOCOL, "http");

      zkQuorum = StringUtils.isEmpty(conf.getProperty(COLLECTOR_ZOOKEEPER_QUORUM)) ?
        conf.getProperty(ZOOKEEPER_QUORUM) : conf.getProperty(COLLECTOR_ZOOKEEPER_QUORUM);

      timeoutSeconds = conf.getProperty(METRICS_POST_TIMEOUT_SECONDS) != null ?
        Integer.parseInt(conf.getProperty(METRICS_POST_TIMEOUT_SECONDS).toString()) :
        DEFAULT_POST_TIMEOUT_SECONDS;
      applicationId = conf.getProperty(CLUSTER_REPORTER_APP_ID, DEFAULT_CLUSTER_REPORTER_APP_ID);

      collectorUri = constructTimelineMetricUri(protocol, findPreferredCollectHost(), port);

      if (collectorUri.toLowerCase().startsWith("https://")) {
        String trustStorePath = conf.getProperty(SSL_KEYSTORE_PATH_PROPERTY).toString().trim();
        String trustStoreType = conf.getProperty(SSL_KEYSTORE_TYPE_PROPERTY).toString().trim();
        String trustStorePwd = conf.getProperty(SSL_KEYSTORE_PASSWORD_PROPERTY).toString().trim();
        loadTruststore(trustStorePath, trustStoreType, trustStorePwd);
      }


    } catch (Exception e) {
      LOG.warn("Could not initialize metrics collector, please specify " +
        "protocol, host, port under $STORM_HOME/conf/config.yaml ", e);
    }
    // Initialize the collector write strategy
    super.init();
  }

  @Override
  public void handleDataPoints(ClusterInfo clusterInfo, Collection<DataPoint> dataPoints) {
    long timestamp = clusterInfo.getTimestamp();
    List<TimelineMetric> totalMetrics = new ArrayList<>();

    for (DataPoint dataPoint : dataPoints) {
      LOG.debug(dataPoint.getName() + " = " + dataPoint.getValue());
      List<DataPoint> populatedDataPoints = populateDataPoints(dataPoint);

      for (DataPoint populatedDataPoint : populatedDataPoints) {
        LOG.debug("Populated datapoint: " + dataPoint.getName() + " = " + dataPoint.getValue());

        try {
          StormAmbariMappedMetric mappedMetric = StormAmbariMappedMetric
              .valueOf(populatedDataPoint.getName());
          TimelineMetric timelineMetric = createTimelineMetric(timestamp * 1000, applicationId,
              mappedMetric.getAmbariMetricName(),
              Double.valueOf(populatedDataPoint.getValue().toString()));

          totalMetrics.add(timelineMetric);
        } catch (IllegalArgumentException e) {
          // not interested metrics on Ambari, skip
          LOG.debug("Not interested metrics, skip: " + populatedDataPoint.getName());
        }
      }
    }

    if (totalMetrics.size() <= 0) {
      return;
    }

    TimelineMetrics timelineMetrics = new TimelineMetrics();
    timelineMetrics.setMetrics(totalMetrics);

    try {
      emitMetrics(timelineMetrics);
    } catch (UnableToConnectException e) {
      LOG.warn("Unable to connect to Metrics Collector " + e.getConnectUrl() + ". " + e.getMessage());
    }

  }

  @Override
  public void handleDataPoints(SupervisorInfo supervisorInfo, Collection<DataPoint> dataPoints) {
    // Ambari is not interested on metrics on each supervisor
  }

  @Override
  public void cleanup() {
    LOG.info("Stopping Storm Metrics Reporter");
  }

  private List<DataPoint> populateDataPoints(DataPoint dataPoint) {
    List<DataPoint> dataPoints = new ArrayList<>();

    if (dataPoint.getValue() == null) {
      LOG.warn("Data point with name " + dataPoint.getName() + " is null. Discarding." + dataPoint
          .getName());
    } else if (dataPoint.getValue() instanceof Map) {
      Map<String, Object> dataMap = (Map<String, Object>) dataPoint.getValue();

      for (Map.Entry<String, Object> entry : dataMap.entrySet()) {
        Double value = convertValueToDouble(entry.getKey(), entry.getValue());
        if (value != null) {
          dataPoints.add(new DataPoint(dataPoint.getName() + "." + entry.getKey(), value));
        }
      }
    } else {
      Double value = convertValueToDouble(dataPoint.getName(), dataPoint.getValue());
      if (value != null) {
        dataPoints.add(new DataPoint(dataPoint.getName(), value));
      }
    }

    return dataPoints;
  }

  private Double convertValueToDouble(String metricName, Object value) {
    try {
      Double converted = NumberUtil.convertValueToDouble(value);
      if (converted == null) {
        LOG.warn("Data point with name " + metricName + " has value " + value +
            " which is not supported. Discarding.");
      }
      return converted;
    } catch (NumberFormatException e) {
      LOG.warn("Data point with name " + metricName + " doesn't have number format value " +
          value + ". Discarding.");
      return null;
    }
  }

  private TimelineMetric createTimelineMetric(long currentTimeMillis, String component,
                                              String attributeName, Double attributeValue) {
    TimelineMetric timelineMetric = new TimelineMetric();
    timelineMetric.setMetricName(attributeName);
    timelineMetric.setHostName(hostname);
    timelineMetric.setAppId(component);
    timelineMetric.setStartTime(currentTimeMillis);
    timelineMetric.setType(ClassUtils.getShortCanonicalName(attributeValue, "Number"));
    timelineMetric.getMetricValues().put(currentTimeMillis, attributeValue);    return timelineMetric;
  }

  enum StormAmbariMappedMetric {
    supervisors("Supervisors"),
    topologies("Topologies"),
    slotsTotal("Total Slots"),
    slotsUsed("Used Slots"),
    slotsFree("Free Slots"),
    executorsTotal("Total Executors"),
    tasksTotal("Total Tasks");

    private String ambariMetricName;

    StormAmbariMappedMetric(String ambariMetricName) {
      this.ambariMetricName = ambariMetricName;
    }

    public String getAmbariMetricName() {
      return ambariMetricName;
    }
  }

}
