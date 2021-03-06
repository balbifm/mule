/*
 * Copyright (c) MuleSoft, Inc.  All rights reserved.  http://www.mulesoft.com
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */
package org.mule.extension.ws.internal.metadata;

import static org.mule.runtime.api.metadata.resolving.FailureCode.CONNECTION_FAILURE;
import org.mule.extension.ws.internal.ConsumeOperation;
import org.mule.runtime.api.connection.ConnectionException;
import org.mule.runtime.api.metadata.MetadataContext;
import org.mule.runtime.api.metadata.MetadataResolvingException;
import org.mule.runtime.api.metadata.resolving.NamedTypeResolver;
import org.mule.services.soap.api.client.SoapClient;
import org.mule.services.soap.api.client.metadata.SoapMetadataResolver;

/**
 * Base class for all metadata resolvers of the {@link ConsumeOperation}.
 *
 * @since 4.0
 */
public abstract class BaseWscResolver implements NamedTypeResolver {

  private static final String WSC_CATEGORY = "WebServiceConsumerCategory";

  @Override
  public String getCategoryName() {
    return WSC_CATEGORY;
  }

  SoapMetadataResolver getMetadataResolver(MetadataContext context)
      throws MetadataResolvingException, ConnectionException {
    return context.<SoapClient>getConnection().map(SoapClient::getMetadataResolver)
        .orElseThrow(() -> new MetadataResolvingException("Could not obtain connection to retrieve metadata",
                                                          CONNECTION_FAILURE));
  }
}
