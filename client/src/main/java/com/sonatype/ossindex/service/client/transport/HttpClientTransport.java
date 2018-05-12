/*
 * Copyright (c) 2018-present Sonatype, Inc. All rights reserved.
 * "Sonatype" is a trademark of Sonatype, Inc.
 */
package com.sonatype.ossindex.service.client.transport;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;

import com.google.common.base.Charsets;
import org.apache.http.HttpEntity;
import org.apache.http.HttpHeaders;
import org.apache.http.StatusLine;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * Apache {@link org.apache.http.client.HttpClient} transport.
 *
 * @since ???
 */
public class HttpClientTransport
    implements Transport
{
  private static final Logger log = LoggerFactory.getLogger(HttpClientTransport.class);

  private final UserAgentSupplier userAgent;

  public HttpClientTransport(final UserAgentSupplier userAgent) {
    this.userAgent = checkNotNull(userAgent);
  }

  @Override
  public String post(final URI url, final String payloadType, final String payload, final String acceptType)
      throws TransportException, IOException
  {
    log.debug("POST {}; payload: {} ({}); accept: {}", url, payload, payloadType, acceptType);

    try (CloseableHttpClient httpClient = createClient()) {
      HttpPost request = new HttpPost(url.toURL().toExternalForm());
      customize(request);
      request.setHeader(HttpHeaders.ACCEPT, acceptType);
      request.setEntity(new StringEntity(payload, ContentType.create(payloadType, Charsets.UTF_8)));

      try (CloseableHttpResponse response = httpClient.execute(request)) {
        StatusLine status = response.getStatusLine();
        log.trace("Status: {}", status);

        if (status.getStatusCode() == HttpURLConnection.HTTP_OK) {
          HttpEntity entity = response.getEntity();
          try {
            return EntityUtils.toString(entity);
          }
          finally {
            EntityUtils.consumeQuietly(entity);
          }
        }

        throw new TransportException("Unexpected response; status: " + status);
      }
    }
  }

  /**
   * Create customized client.
   */
  protected CloseableHttpClient createClient() {
    return HttpClients.createDefault();
  }

  /**
   * Customize request.
   */
  protected void customize(final HttpPost request) {
    request.setHeader(HttpHeaders.USER_AGENT, userAgent.get());
  }
}
