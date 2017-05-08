/*
 * Copyright (c) MuleSoft, Inc.  All rights reserved.  http://www.mulesoft.com
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */
package org.mule.test.integration.exceptions;

import static org.junit.Assert.assertNotNull;
import static org.mule.runtime.api.util.DataUnit.BYTE;

import org.mule.runtime.api.message.Message;
import org.mule.runtime.api.util.DataSize;
import org.mule.runtime.core.api.Event;
import org.mule.runtime.core.internal.streaming.bytes.InMemoryCursorStreamProvider;
import org.mule.runtime.core.internal.streaming.bytes.PoolingByteBufferManager;
import org.mule.runtime.core.streaming.bytes.InMemoryCursorStreamConfig;
import org.mule.runtime.core.util.IOUtils;
import org.mule.test.AbstractIntegrationTestCase;

import org.junit.Test;

public class ExceptionHandlingStreamTestCase extends AbstractIntegrationTestCase {

  public static final String MESSAGE = "some message";

  @Override
  protected String getConfigFile() {
    return "org/mule/test/integration/exceptions/exception-handling-test.xml";
  }

  @Test
  public void test() throws Exception {
    InMemoryCursorStreamConfig config =
        new InMemoryCursorStreamConfig(new DataSize(1024, BYTE),
                                       new DataSize(1024 / 2, BYTE),
                                       new DataSize(2048, BYTE));
    InMemoryCursorStreamProvider payload =
        new InMemoryCursorStreamProvider(IOUtils.toInputStream(MESSAGE), config, new PoolingByteBufferManager());
    final Event muleEvent = flowRunner("flow").withPayload(payload).run();

    Message response = muleEvent.getMessage();

    assertNotNull(response);
  }

}
