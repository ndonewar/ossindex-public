/*
 * Copyright (c) 2018-present Sonatype, Inc. All rights reserved.
 * "Sonatype" is a trademark of Sonatype, Inc.
 */
package com.sonatype.ossindex.service.client.internal;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import com.sonatype.ossindex.service.api.componentreport.ComponentReport;
import com.sonatype.ossindex.service.api.componentreport.ComponentReportRequest;
import com.sonatype.ossindex.service.client.OssindexClient;
import com.sonatype.ossindex.service.client.OssindexClientConfiguration;
import com.sonatype.ossindex.service.client.transport.Marshaller;
import com.sonatype.ossindex.service.client.transport.Transport;

import org.sonatype.goodies.packageurl.PackageUrl;

import com.google.common.base.Stopwatch;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.reflect.TypeToken;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static com.sonatype.ossindex.service.api.componentreport.ComponentReportMediaTypes.REPORT_V1_JSON;
import static com.sonatype.ossindex.service.api.componentreport.ComponentReportMediaTypes.REQUEST_V1_JSON;

/**
 * Default {@link OssindexClient}.
 *
 * @since ???
 */
public class OssindexClientImpl
    implements OssindexClient
{
  private static final Logger log = LoggerFactory.getLogger(OssindexClientImpl.class);

  private final Transport transport;

  private final Marshaller marshaller;

  private final URI baseUrl;

  private final Cache<PackageUrl, ComponentReport> reportCache;

  private final int batchSize;

  public OssindexClientImpl(final OssindexClientConfiguration config,
                            final Transport transport,
                            final Marshaller marshaller)
  {
    checkNotNull(config);

    this.transport = checkNotNull(transport);
    log.debug("Transport: {}", transport);

    this.marshaller = checkNotNull(marshaller);
    log.debug("Marshaller: {}", marshaller);

    checkState(config.getBaseUrl() != null, "Base-URL required");
    baseUrl = normalize(config.getBaseUrl());
    log.debug("Base URL: {}", baseUrl);

    checkState(config.getReportCache() != null, "Report cache required");
    log.debug("Report cache: {}", config.getReportCache());
    reportCache = CacheBuilder.from(config.getReportCache()).build();

    checkState(config.getBatchSize() > 0 && config.getBatchSize() <= 1024, "Batch-size out of range");
    batchSize = config.getBatchSize();
    log.debug("Batch size: {}", batchSize);
  }

  /**
   * Normalize base-URL, must end with trailing "/".
   */
  private static URI normalize(final URI baseUrl) {
    if (!baseUrl.toString().endsWith("/")) {
      return URI.create(baseUrl + "/");
    }
    return baseUrl;
  }

  @Override
  public Map<PackageUrl, ComponentReport> requestComponentReports(final List<PackageUrl> coordinates) throws Exception {
    checkNotNull(coordinates);
    checkArgument(!coordinates.isEmpty(), "One or more coordinates required");

    log.debug("Requesting {} component-reports", coordinates.size());
    Stopwatch watch = Stopwatch.createStarted();

    Map<PackageUrl, ComponentReport> result = new LinkedHashMap<>();

    // resolve cached reports and generate list of un-cached requests
    List<PackageUrl> uncached = new LinkedList<>();
    for (PackageUrl purl : coordinates) {
      ComponentReport report = reportCache.getIfPresent(purl);
      if (report != null) {
        log.debug("Found cached report for: {}", purl);
        result.put(purl, report);
      }
      else {
        uncached.add(purl);
      }
    }

    // request any un-cached reports in batches and append to cache
    if (!uncached.isEmpty()) {
      List<PackageUrl> batch = new ArrayList<>(batchSize);

      Iterator<PackageUrl> iter = uncached.iterator();
      while (iter.hasNext()) {
        batch.add(iter.next());

        // perform request if batch size reached or if there are no more requests after current
        if (batch.size() == batchSize || !iter.hasNext()) {
          Map<PackageUrl, ComponentReport> reports = doRequestComponentReports(batch);
          reportCache.putAll(reports);
          result.putAll(reports);
          batch.clear();
        }
      }
    }

    log.debug("{} component-reports; {}", result.size(), watch);

    return result;
  }

  private static final TypeToken<List<ComponentReport>> LIST_COMPONENT_REPORT = new TypeToken<List<ComponentReport>>() { };

  private Map<PackageUrl, ComponentReport> doRequestComponentReports(final List<PackageUrl> coordinates)
      throws Exception
  {
    log.debug("Requesting {} un-cached component-reports", coordinates.size());

    ComponentReportRequest request = new ComponentReportRequest();
    request.setCoordinates(coordinates);

    URI url = baseUrl.resolve("api/v3/component-report");
    String response = transport.post(url, REQUEST_V1_JSON, marshaller.marshal(request), REPORT_V1_JSON);
    List<ComponentReport> reports = marshaller.unmarshal(response, LIST_COMPONENT_REPORT);

    // puke if the response does not contain the same number of entries as input request
    checkState(reports.size() == coordinates.size(), "Result size mismatch; expected: %s, have: %s", coordinates.size(),
        reports.size());

    Map<PackageUrl, ComponentReport> result = new LinkedHashMap<>();
    int i = 0;
    for (PackageUrl purl : coordinates) {
      result.put(purl, reports.get(i++));
    }
    return result;
  }

  @Override
  public ComponentReport requestComponentReport(final PackageUrl coordinates) throws Exception {
    checkNotNull(coordinates);
    Map<PackageUrl, ComponentReport> reports = requestComponentReports(Collections.singletonList(coordinates));
    ComponentReport report = reports.get(coordinates);
    checkState(report != null);
    return report;
  }
}
