/*
 * Copyright (c) MuleSoft, Inc.  All rights reserved.  http://www.mulesoft.com
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */
package org.mule.services.oauth.internal.builder;

import static java.lang.String.format;
import static java.nio.charset.StandardCharsets.UTF_8;

import org.mule.runtime.api.el.MuleExpressionLanguage;
import org.mule.runtime.api.lock.LockFactory;
import org.mule.runtime.api.tls.TlsContextFactory;
import org.mule.runtime.oauth.api.builder.OAuthDancerBuilder;
import org.mule.runtime.oauth.api.state.DefaultResourceOwnerOAuthContext;
import org.mule.service.http.api.HttpService;
import org.mule.service.http.api.client.HttpClient;
import org.mule.service.http.api.client.HttpClientConfiguration;
import org.mule.service.http.api.client.HttpClientConfiguration.Builder;
import org.mule.service.http.api.client.HttpRequestAuthentication;
import org.mule.service.http.api.client.async.ResponseHandler;
import org.mule.service.http.api.domain.message.request.HttpRequest;
import org.mule.service.http.api.domain.message.response.HttpResponse;

import java.io.IOException;
import java.nio.charset.Charset;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

public abstract class AbstractOAuthDancerBuilder<D> implements OAuthDancerBuilder<D> {

  protected final LockFactory lockProvider;
  protected final Map<String, DefaultResourceOwnerOAuthContext> tokensStore;
  protected final HttpService httpService;
  protected final MuleExpressionLanguage expressionEvaluator;

  protected String clientId;
  protected String clientSecret;
  protected String tokenUrl;
  protected Supplier<HttpClient> httpClientFactory;

  protected Charset encoding = UTF_8;
  protected String responseAccessTokenExpr = "#[payload.access_token]";
  protected String responseRefreshTokenExpr = "#[payload.refresh_token]";
  protected String responseExpiresInExpr = "#[payload.expires_in]";
  protected String scopes = null;
  protected Map<String, String> customParametersExtractorsExprs;

  public AbstractOAuthDancerBuilder(LockFactory lockProvider, Map<String, DefaultResourceOwnerOAuthContext> tokensStore,
                                    HttpService httpService, MuleExpressionLanguage expressionEvaluator) {
    this.lockProvider = lockProvider;
    this.tokensStore = tokensStore;
    this.httpService = httpService;
    this.expressionEvaluator = expressionEvaluator;
  }

  @Override
  public OAuthDancerBuilder clientCredentials(String clientId, String clientSecret) {
    this.clientId = clientId;
    this.clientSecret = clientSecret;
    return this;
  }

  @Override
  public OAuthDancerBuilder tokenUrl(String tokenUrl) {
    this.tokenUrl = tokenUrl;
    this.httpClientFactory = () -> {
      final Builder clientConfigBuilder =
          new HttpClientConfiguration.Builder().setName(format("oauthToken.requester[%s]", tokenUrl));
      return httpService.getClientFactory().create(clientConfigBuilder.build());
    };
    return this;
  }

  @Override
  public OAuthDancerBuilder tokenUrl(HttpClient httpClient, String tokenUrl) {
    this.httpClientFactory = () -> new HttpClient() {

      @Override
      public void stop() {
        // Nothing to do. The lifecycle of this object is handled by whoever passed me the client.
      }

      @Override
      public void start() {
        // Nothing to do. The lifecycle of this object is handled by whoever passed me the client.
      }

      @Override
      public void send(HttpRequest request, int responseTimeout, boolean followRedirects,
                       HttpRequestAuthentication authentication,
                       ResponseHandler handler) {
        httpClient.send(request, responseTimeout, followRedirects, authentication, handler);
      }

      @Override
      public HttpResponse send(HttpRequest request, int responseTimeout, boolean followRedirects,
                               HttpRequestAuthentication authentication)
          throws IOException, TimeoutException {
        return httpClient.send(request, responseTimeout, followRedirects, authentication);
      }
    };

    this.tokenUrl = tokenUrl;
    return this;
  }

  @Override
  public OAuthDancerBuilder tokenUrl(String tokenUrl, TlsContextFactory tlsContextFactory) {
    this.tokenUrl = tokenUrl;
    this.httpClientFactory = () -> {
      final Builder clientConfigBuilder =
          new HttpClientConfiguration.Builder().setName(format("oauthToken.requester[%s]", tokenUrl));
      clientConfigBuilder.setTlsContextFactory(tlsContextFactory);
      return httpService.getClientFactory().create(clientConfigBuilder.build());
    };
    return this;
  }

  @Override
  public OAuthDancerBuilder scopes(String scopes) {
    this.scopes = scopes;
    return this;
  }

  @Override
  public OAuthDancerBuilder encoding(Charset encoding) {
    this.encoding = encoding;
    return this;
  }

  @Override
  public OAuthDancerBuilder responseAccessTokenExpr(String responseAccessTokenExpr) {
    this.responseAccessTokenExpr = responseAccessTokenExpr;
    return this;
  }

  @Override
  public OAuthDancerBuilder responseRefreshTokenExpr(String responseRefreshTokenExpr) {
    this.responseRefreshTokenExpr = responseRefreshTokenExpr;
    return this;
  }

  @Override
  public OAuthDancerBuilder responseExpiresInExpr(String responseExpiresInExpr) {
    this.responseExpiresInExpr = responseExpiresInExpr;
    return this;
  }

  @Override
  public OAuthDancerBuilder customParametersExtractorsExprs(Map<String, String> customParamsExtractorsExprs) {
    this.customParametersExtractorsExprs = customParamsExtractorsExprs;
    return this;
  }

}
