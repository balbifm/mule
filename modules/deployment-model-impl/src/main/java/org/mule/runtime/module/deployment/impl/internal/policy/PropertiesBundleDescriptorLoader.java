/*
 * Copyright (c) MuleSoft, Inc.  All rights reserved.  http://www.mulesoft.com
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */

package org.mule.runtime.module.deployment.impl.internal.policy;

import org.mule.runtime.module.artifact.descriptor.ArtifactDescriptorCreateException;
import org.mule.runtime.module.artifact.descriptor.BundleDescriptor;

import java.util.Map;

/**
 * Loads a {@link BundleDescriptor} using properties defined in the stored descriptor loader.
 */
public class PropertiesBundleDescriptorLoader {

  public static final String VERSION = "version";
  public static final String GROUP_ID = "groupId";
  public static final String ARTIFACT_ID = "artifactId";
  public static final String CLASSIFIER = "classifier";
  public static final String TYPE = "type";

  /**
   * Loads a bundle descriptor from the provided properties
   *
   * @param attributes attributes defined in the loader.
   * @return a locator of the coordinates of the current artifact
   * @throws ArtifactDescriptorCreateException if any bundle descriptor required property is missing on the given attributes.
   */
  public BundleDescriptor loadBundleDescriptor(Map<String, Object> attributes) {
    String version = (String) attributes.get(VERSION);
    String groupId = (String) attributes.get(GROUP_ID);
    String artifactId = (String) attributes.get(ARTIFACT_ID);
    String classifier = (String) attributes.get(CLASSIFIER);
    String type = (String) attributes.get(TYPE);
    try {
      return new BundleDescriptor.Builder().setVersion(version).setGroupId(groupId).setArtifactId(artifactId)
          .setClassifier(classifier).setType(type).build();
    } catch (IllegalArgumentException e) {
      throw new ArtifactDescriptorCreateException("Bundle descriptor attributes are not complete", e);
    }
  }
}
