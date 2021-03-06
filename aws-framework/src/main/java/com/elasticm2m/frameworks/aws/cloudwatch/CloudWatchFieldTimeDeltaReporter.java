package com.elasticm2m.frameworks.aws.cloudwatch;

import org.apache.storm.Config;
import org.apache.storm.task.OutputCollector;
import org.apache.storm.task.TopologyContext;
import org.apache.storm.tuple.Tuple;
import org.apache.storm.tuple.Values;
import com.amazonaws.auth.AWSCredentialsProvider;
import com.amazonaws.auth.DefaultAWSCredentialsProviderChain;
import com.amazonaws.regions.Region;
import com.amazonaws.regions.Regions;
import com.amazonaws.services.cloudwatch.AmazonCloudWatchAsyncClient;
import com.blacklocus.metrics.CloudWatchReporter;
import com.blacklocus.metrics.ContinualCloudWatchBuilder;
import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.ScheduledReporter;
import com.elasticm2m.frameworks.common.base.ElasticBaseRichBolt;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.inject.Inject;
import com.google.inject.name.Named;
import com.scaleset.geo.geojson.GeoJsonModule;
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.joda.time.format.DateTimeFormatter;
import org.joda.time.format.ISODateTimeFormat;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.apache.commons.lang3.StringUtils.isNotBlank;

public class CloudWatchFieldTimeDeltaReporter extends ElasticBaseRichBolt {

    private final MetricRegistry registry = getMetricRegistry();
    private String name;
    private String regionName;
    private String namespace;
    private String metricName;
    private Integer reportFrequency = 1;
    private ScheduledReporter reporter;
    private ObjectMapper objectMapper;
    private String startProperty;
    private DateTimeFormatter dateTimeFormatter;
    private String endProperty;
    private TimeUnit metricTimeUnit = TimeUnit.SECONDS;


    @Inject(optional = true)
    public void setName(@Named("cloudwatch-name") String name) {
        this.name = name;
    }

    @Inject(optional = true)
    public void setMetricTimeUnit(@Named("cloudwatch-metric-time-unit") String metricTimeUnit) {
        this.metricTimeUnit = TimeUnit.valueOf(metricTimeUnit);
    }

    @Inject(optional = true)
    public void setNamespace(@Named("cloudwatch-namespace") String namespace) {
        this.namespace = namespace;
    }

    @Inject(optional = true)
    public void setRegionName(@Named("cloudwatch-region-name") String regionName) {
        this.regionName = regionName;
    }

    @Inject(optional = true)
    public void setStartProperty(@Named("start-property") String startProperty) {
        this.startProperty = startProperty;
    }

    @Inject(optional = true)
    public void setEndProperty(@Named("end-property") String endProperty) {
        this.endProperty = endProperty;
    }

    @Inject(optional = true)
    public void setStableSeconds(@Named("report-frequency") Integer reportFrequency) {
        this.reportFrequency = reportFrequency;
    }

    private String buildMetricName(Map conf) {
        String topologyName = (String) conf.get(Config.TOPOLOGY_NAME);
        return String.format("%s Topology=%s Region=%s", this.name, topologyName, regionName);
    }

    @Override
    public void cleanup() {
        super.cleanup();
        reporter.close();
    }

    @Override
    public void prepare(Map conf, TopologyContext topologyContext, OutputCollector collector) {

        super.prepare(conf, topologyContext, collector);

        metricName = buildMetricName(conf);

        logger.info("prepare() - namespace: {}, metric: {}", namespace, metricName);

        objectMapper = new ObjectMapper().registerModule(new GeoJsonModule());

        dateTimeFormatter = ISODateTimeFormat.dateTimeParser();

        reporter = getCloudwatchReporter();

        reporter.start(reportFrequency, TimeUnit.MINUTES);
    }

    @Override
    public void execute(Tuple tuple) {
        try {
            try {
                Object payload = tuple.getValue(1);
                if (payload != null) {
                    String json = payload.toString();
                    JsonNode root = objectMapper.readTree(json);
                    DateTime start = getDateTime(root, startProperty);
                    DateTime end = isNotBlank(endProperty) ? getDateTime(root, endProperty) : new DateTime(DateTimeZone.UTC);
                    long millis = end.getMillis() - start.getMillis();
                    long convertedTime = metricTimeUnit.convert(millis, TimeUnit.MILLISECONDS);

                    registry.histogram(metricName).update(convertedTime);
                }
            } catch (Throwable t) {
                logger.error("error computing timing delta, {}", t.getMessage(), t);
            }

            List<Object> values = new Values();
            values.add(tuple.getValue(0));
            values.add(tuple.getValue(1));
            values.add(tuple.getValue(2));
            collector.emit(tuple, values);
        } finally {
            collector.ack(tuple);
        }

    }

    private DateTime getDateTime(JsonNode root, String jsonNodeRef) {
        JsonNode node = root.at(jsonNodeRef);
        return dateTimeFormatter.parseDateTime(node.textValue());
    }


    MetricRegistry getMetricRegistry() {
        return new MetricRegistry();
    }

    CloudWatchReporter getCloudwatchReporter() {

        AWSCredentialsProvider credentialsProvider = new DefaultAWSCredentialsProviderChain();
        AmazonCloudWatchAsyncClient client = new AmazonCloudWatchAsyncClient(credentialsProvider);
        client.setRegion(Region.getRegion(Regions.fromName(regionName)));

        return new ContinualCloudWatchBuilder()
                .withNamespace(this.namespace)
                .withClient(client)
                .withRegistry(registry)
                .build();
    }
}
