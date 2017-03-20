/*
 * Copyright (c) MuleSoft, Inc.  All rights reserved.  http://www.mulesoft.com
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */
package org.mule.runtime.module.deployment.internal;

import static com.google.common.base.Preconditions.checkArgument;
import static java.lang.System.currentTimeMillis;
import static java.lang.System.getProperty;
import static java.lang.System.setProperty;
import static java.util.Collections.emptyMap;
import static java.util.Collections.singletonList;
import static org.apache.commons.io.FileUtils.copyFile;
import static org.apache.commons.io.FileUtils.copyFileToDirectory;
import static org.apache.commons.io.FileUtils.deleteDirectory;
import static org.apache.commons.lang.StringUtils.isEmpty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.core.Is.is;
import static org.hamcrest.core.IsNull.notNullValue;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mule.functional.services.TestServicesUtils.buildSchedulerServiceFile;
import static org.mule.runtime.container.api.MuleFoldersUtil.CONTAINER_APP_PLUGINS;
import static org.mule.runtime.container.api.MuleFoldersUtil.getDomainFolder;
import static org.mule.runtime.container.api.MuleFoldersUtil.getServicesFolder;
import static org.mule.runtime.core.api.config.MuleProperties.MULE_HOME_DIRECTORY_PROPERTY;
import static org.mule.runtime.deployment.model.api.domain.Domain.DEFAULT_DOMAIN_NAME;
import static org.mule.runtime.deployment.model.api.plugin.ArtifactPluginDescriptor.EXTENSION_BUNDLE_TYPE;
import static org.mule.runtime.deployment.model.api.plugin.MavenClassLoaderConstants.MAVEN;
import static org.mule.runtime.module.deployment.impl.internal.policy.PropertiesBundleDescriptorLoader.ARTIFACT_ID;
import static org.mule.runtime.module.deployment.impl.internal.policy.PropertiesBundleDescriptorLoader.CLASSIFIER;
import static org.mule.runtime.module.deployment.impl.internal.policy.PropertiesBundleDescriptorLoader.GROUP_ID;
import static org.mule.runtime.module.deployment.impl.internal.policy.PropertiesBundleDescriptorLoader.PROPERTIES_BUNDLE_DESCRIPTOR_LOADER_ID;
import static org.mule.runtime.module.deployment.impl.internal.policy.PropertiesBundleDescriptorLoader.TYPE;
import static org.mule.runtime.module.deployment.impl.internal.policy.PropertiesBundleDescriptorLoader.VERSION;
import static org.mule.runtime.module.deployment.internal.DeploymentDirectoryWatcher.CHANGE_CHECK_INTERVAL_PROPERTY;
import static org.mule.runtime.module.deployment.internal.DeploymentServiceTestCase.TestPolicyComponent.invocationCount;
import static org.mule.runtime.module.deployment.internal.DeploymentServiceTestCase.TestPolicyComponent.policyParametrization;
import static org.mule.tck.junit4.AbstractMuleContextTestCase.TEST_MESSAGE;

import org.mule.runtime.api.deployment.meta.MuleArtifactLoaderDescriptor;
import org.mule.runtime.api.deployment.meta.MulePolicyModel.MulePolicyModelBuilder;
import org.mule.runtime.api.exception.MuleException;
import org.mule.runtime.api.exception.MuleRuntimeException;
import org.mule.runtime.container.api.ModuleRepository;
import org.mule.runtime.container.internal.DefaultModuleRepository;
import org.mule.runtime.core.DefaultEventContext;
import org.mule.runtime.core.api.Event;
import org.mule.runtime.core.api.construct.Flow;
import org.mule.runtime.core.api.message.InternalMessage;
import org.mule.runtime.core.policy.PolicyParametrization;
import org.mule.runtime.core.registry.SpiServiceRegistry;
import org.mule.runtime.core.util.FileUtils;
import org.mule.runtime.core.util.StringUtils;
import org.mule.runtime.deployment.model.api.application.Application;
import org.mule.runtime.deployment.model.api.application.ApplicationStatus;
import org.mule.runtime.module.artifact.builder.AbstractArtifactFileBuilder;
import org.mule.runtime.module.artifact.builder.AbstractDependencyFileBuilder;
import org.mule.runtime.module.artifact.builder.TestArtifactDescriptor;
import org.mule.runtime.module.artifact.classloader.ArtifactClassLoader;
import org.mule.runtime.module.deployment.api.DeploymentListener;
import org.mule.runtime.module.deployment.impl.internal.MuleArtifactResourcesRegistry;
import org.mule.runtime.module.deployment.impl.internal.artifact.ServiceRegistryDescriptorLoaderRepository;
import org.mule.runtime.module.deployment.impl.internal.builder.ApplicationFileBuilder;
import org.mule.runtime.module.deployment.impl.internal.builder.PolicyFileBuilder;
import org.mule.runtime.module.deployment.impl.internal.plugin.MuleExtensionModelLoaderManager;
import org.mule.runtime.module.deployment.impl.internal.policy.PolicyTemplateDescriptorFactory;
import org.mule.runtime.module.extension.internal.loader.ExtensionModelLoaderManager;
import org.mule.runtime.module.service.ServiceManager;
import org.mule.tck.ZipUtils;
import org.mule.tck.junit4.AbstractMuleTestCase;
import org.mule.tck.junit4.rule.DynamicPort;
import org.mule.tck.junit4.rule.SystemProperty;
import org.mule.tck.probe.JUnitProbe;
import org.mule.tck.probe.PollingProber;
import org.mule.tck.probe.Prober;

import com.google.common.collect.Lists;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URL;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

import org.apache.maven.model.Dependency;
import org.apache.maven.model.Model;
import org.apache.maven.model.io.xpp3.MavenXpp3Writer;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class DeploymentServiceHttpPluginTestCase extends AbstractMuleTestCase {

  public static final String PRIVILEGED_EXTENSION_PLUGIN_NAME = "privilegedExtensionPlugin";
  protected static final int DEPLOYMENT_TIMEOUT = 10000;
  private static final String MULE_POLICY_CLASSIFIER = "mule-policy";
  // Resources
  private static final String APP_WITH_EXTENSION_PLUGIN_CONFIG = "app-with-http-plugin-config.xml";
  private static final String BAZ_POLICY_NAME = "bazPolicy";
  private static final String FOO_POLICY_ID = "fooPolicy";
  private static final String MIN_MULE_VERSION = "4.0.0";
  private final PolicyFileBuilder policyIncludingPluginFileBuilder =
      createPolicyIncludingPluginFileBuilder();
  @Rule
  public SystemProperty changeChangeInterval = new SystemProperty(CHANGE_CHECK_INTERVAL_PROPERTY, "10");
  @Rule
  public DynamicPort httpPort = new DynamicPort("httpPort");
  @Rule
  public TemporaryFolder compilerWorkFolder = new TemporaryFolder();
  protected File muleHome;
  protected File appsDir;
  protected File domainsDir;
  protected File containerAppPluginsDir;
  protected File tmpAppsDir;
  protected ServiceManager serviceManager;
  protected ExtensionModelLoaderManager extensionModelLoaderManager;
  protected MuleDeploymentService deploymentService;
  protected DeploymentListener applicationDeploymentListener;
  protected DeploymentListener domainDeploymentListener;
  private TestPolicyManager policyManager;
  private File services;

  private static File getResourceFile(String resource) {
    return new File(DeploymentServiceTestCase.class.getResource(resource).getFile());
  }

  private static MuleArtifactLoaderDescriptor createBundleDescriptorLoader(String artifactId, String classifier,
                                                                           String bundleDescriptorLoaderId) {
    return createBundleDescriptorLoader(artifactId, classifier, bundleDescriptorLoaderId, "1.0");
  }

  private static MuleArtifactLoaderDescriptor createBundleDescriptorLoader(String artifactId, String classifier,
                                                                           String bundleDescriptorLoaderId, String version) {
    Map<String, Object> attributes = new HashMap<>();
    attributes.put(VERSION, version);
    attributes.put(GROUP_ID, "org.mule.modules");
    attributes.put(ARTIFACT_ID, artifactId);
    attributes.put(CLASSIFIER, classifier);
    attributes.put(TYPE, EXTENSION_BUNDLE_TYPE);

    return new MuleArtifactLoaderDescriptor(bundleDescriptorLoaderId, attributes);
  }

  @Before
  public void setUp() throws Exception {

    final String tmpDir = getProperty("java.io.tmpdir");
    muleHome = new File(new File(tmpDir, "mule_home"), getClass().getSimpleName() + currentTimeMillis());
    appsDir = new File(muleHome, "apps");
    appsDir.mkdirs();
    containerAppPluginsDir = new File(muleHome, CONTAINER_APP_PLUGINS);
    containerAppPluginsDir.mkdirs();
    tmpAppsDir = new File(muleHome, "tmp");
    tmpAppsDir.mkdirs();
    domainsDir = new File(muleHome, "domains");
    domainsDir.mkdirs();
    setProperty(MULE_HOME_DIRECTORY_PROPERTY, muleHome.getCanonicalPath());

    final File domainFolder = getDomainFolder(DEFAULT_DOMAIN_NAME);
    assertThat(domainFolder.mkdirs(), is(true));

    services = getServicesFolder();
    services.mkdirs();
    copyFileToDirectory(buildSchedulerServiceFile(compilerWorkFolder.newFolder("schedulerService")), services);

    applicationDeploymentListener = mock(DeploymentListener.class);
    domainDeploymentListener = mock(DeploymentListener.class);
    TestContainerModuleDiscoverer moduleDiscoverer =
        new TestContainerModuleDiscoverer(singletonList(PRIVILEGED_EXTENSION_PLUGIN_NAME));
    ModuleRepository moduleRepository = new DefaultModuleRepository(moduleDiscoverer);
    MuleArtifactResourcesRegistry muleArtifactResourcesRegistry =
        new MuleArtifactResourcesRegistry.Builder().moduleRepository(moduleRepository).build();
    serviceManager = muleArtifactResourcesRegistry.getServiceManager();
    ArtifactClassLoader containerClassLoader = muleArtifactResourcesRegistry.getContainerClassLoader();
    extensionModelLoaderManager = new MuleExtensionModelLoaderManager(containerClassLoader);

    deploymentService = new MuleDeploymentService(muleArtifactResourcesRegistry.getDomainFactory(),
                                                  muleArtifactResourcesRegistry.getApplicationFactory());
    deploymentService.addDeploymentListener(applicationDeploymentListener);
    deploymentService.addDomainDeploymentListener(domainDeploymentListener);

    policyManager = new TestPolicyManager(deploymentService,
                                          new PolicyTemplateDescriptorFactory(
                                                                              muleArtifactResourcesRegistry
                                                                                  .getArtifactPluginDescriptorLoader(),
                                                                              new ServiceRegistryDescriptorLoaderRepository(new SpiServiceRegistry())));
    // Reset test component state
    invocationCount = 0;
    policyParametrization = "";
  }

  @After
  public void tearDown() throws Exception {
    if (deploymentService != null) {
      deploymentService.stop();
    }

    if (serviceManager != null) {
      serviceManager.stop();
    }

    if (extensionModelLoaderManager != null) {
      extensionModelLoaderManager.stop();
    }

    FileUtils.deleteTree(muleHome);

    // this is a complex classloader setup and we can't reproduce standalone Mule 100%,
    // so trick the next test method into thinking it's the first run, otherwise
    // app resets CCL ref to null and breaks the next test
    Thread.currentThread().setContextClassLoader(ClassLoader.getSystemClassLoader());
  }

  @Test
  public void appliesApplicationPolicyWithHttpPlugin() throws Exception {
    policyManager.registerPolicyTemplate(policyIncludingPluginFileBuilder.getArtifactFile());

    ApplicationFileBuilder applicationFileBuilder = createAppNoPlugins(APP_WITH_EXTENSION_PLUGIN_CONFIG);
    addPackedAppFromBuilder(applicationFileBuilder);

    startDeployment();
    assertApplicationDeploymentSuccess(applicationDeploymentListener, applicationFileBuilder.getId());

    policyManager.addPolicy(applicationFileBuilder.getId(), policyIncludingPluginFileBuilder.getId(),
                            new PolicyParametrization(FOO_POLICY_ID, s -> true, 1, emptyMap(),
                                                      getResourceFile("/httpPluginPolicy.xml")));

    executeApplicationFlow("main");
    assertThat(invocationCount, equalTo(1));
  }

  @Test
  public void appliesApplicationPolicyDuplicatingHttpPlugin() throws Exception {
    policyManager.registerPolicyTemplate(policyIncludingPluginFileBuilder.getArtifactFile());

    ApplicationFileBuilder applicationFileBuilder = createAppWithHttp(APP_WITH_EXTENSION_PLUGIN_CONFIG);
    addPackedAppFromBuilder(applicationFileBuilder);

    startDeployment();
    assertApplicationDeploymentSuccess(applicationDeploymentListener, applicationFileBuilder.getId());

    policyManager.addPolicy(applicationFileBuilder.getId(), policyIncludingPluginFileBuilder.getId(),
                            new PolicyParametrization(FOO_POLICY_ID, s -> true, 1, emptyMap(),
                                                      getResourceFile("/httpPluginPolicy.xml")));

    executeApplicationFlow("main");
    assertThat(invocationCount, equalTo(1));
  }

  private ApplicationFileBuilder createAppNoPlugins(String appConfigFile)
      throws Exception {

    File installedService = new File(services, "mule-service-http-4.0.0-SNAPSHOT.zip");
    copyFile(new File(getClass().getResource("/mule-service-http-4.0.0-SNAPSHOT.zip").getFile()), installedService);

    return new ApplicationFileBuilder("appWithExtensionPlugin")
        .definedBy(appConfigFile);
  }

  private ApplicationFileBuilder createAppWithHttp(String appConfigFile)
      throws Exception {

    File installedService = new File(services, "mule-service-http-4.0.0-SNAPSHOT.zip");
    copyFile(new File(getClass().getResource("/mule-service-http-4.0.0-SNAPSHOT.zip").getFile()), installedService);

    ApplicationFileBuilder applicationFileBuilder = appBuilderWithHttp()
        .definedBy(appConfigFile);

    applicationFileBuilder.dependingOn(httpExtPlugin());
    applicationFileBuilder.dependingOn(socketsPlugin());

    return applicationFileBuilder;
  }

  private ApplicationFileBuilder appBuilderWithHttp() {
    return new ApplicationFileBuilder("appWithExtensionPlugin") {

      public File getArtifactPomFile() {
        if (artifactPomFile == null) {
          checkArgument(!isEmpty(artifactId), "Filename cannot be empty");

          final File tempFile = new File(getTempFolder(), artifactId + ".pom");
          tempFile.deleteOnExit();

          Model model = new Model();
          model.setGroupId(getGroupId());
          model.setArtifactId(getArtifactId());
          model.setVersion(getVersion());
          model.setModelVersion("4.0.0");

          Dependency dependency = new Dependency();
          dependency.setVersion("4.0.0-SNAPSHOT");
          dependency.setClassifier("mule-plugin");
          dependency.setGroupId("org.mule.modules");
          dependency.setArtifactId("mule-module-http-ext");

          Dependency dependency2 = new Dependency();
          dependency2.setVersion("4.0.0-SNAPSHOT");
          dependency2.setClassifier("mule-plugin");
          dependency2.setGroupId("org.mule.modules");
          dependency2.setArtifactId("mule-module-sockets");

          model.addDependency(dependency);
          model.addDependency(dependency2);

          artifactPomFile = new File(tempFile.getAbsolutePath());
          try (FileOutputStream fileOutputStream = new FileOutputStream(artifactPomFile)) {
            new MavenXpp3Writer().write(fileOutputStream, model);
          } catch (IOException e) {
            throw new MuleRuntimeException(e);
          }
        }
        return artifactPomFile;
      }
    };
  }

  private void startDeployment() throws MuleException {
    serviceManager.start();
    extensionModelLoaderManager.start();
    deploymentService.start();
  }

  private void assertApplicationDeploymentSuccess(DeploymentListener listener, String artifactName) {
    assertDeploymentSuccess(listener, artifactName);
    assertStatus(artifactName, ApplicationStatus.STARTED);
  }

  private void assertDeploymentSuccess(final DeploymentListener listener, final String artifactName) {
    Prober prober = new PollingProber(DEPLOYMENT_TIMEOUT, 100);
    prober.check(new JUnitProbe() {

      @Override
      protected boolean test() throws Exception {
        verify(listener, times(1)).onDeploymentSuccess(artifactName);
        return true;
      }

      @Override
      public String describeFailure() {
        return "Failed to deploy application: " + artifactName + System.lineSeparator() + super.describeFailure();
      }
    });
  }

  private void assertStatus(String appName, ApplicationStatus status) {
    assertStatus(appName, status, -1);
  }

  private void assertStatus(String appName, ApplicationStatus status, int expectedApps) {
    Application app = findApp(appName, expectedApps);
    assertThat(app, notNullValue());
    assertStatus(app, status);
  }

  private void assertStatus(final Application application, final ApplicationStatus status) {
    Prober prober = new PollingProber(DEPLOYMENT_TIMEOUT, 100);
    prober.check(new JUnitProbe() {

      @Override
      protected boolean test() throws Exception {
        assertThat(application.getStatus(), is(status));
        return true;
      }

      @Override
      public String describeFailure() {
        return String.format("Application %s was expected to be in status %s but was %s instead", application.getArtifactName(),
                             status.name(), application.getStatus().name());
      }
    });

  }

  /**
   * Find a deployed app, performing some basic assertions.
   */
  private Application findApp(final String appName, int totalAppsExpected) {
    // list all apps to validate total count
    final List<Application> apps = deploymentService.getApplications();
    assertNotNull(apps);

    if (totalAppsExpected >= 0) {
      assertEquals(totalAppsExpected, apps.size());
    }

    final Application app = deploymentService.findApplication(appName);
    assertNotNull(app);
    return app;
  }

  private void addPackedAppFromBuilder(TestArtifactDescriptor artifactFileBuilder) throws Exception {
    addPackedAppFromBuilder(artifactFileBuilder, null);
  }

  private void addPackedAppFromBuilder(TestArtifactDescriptor artifactFileBuilder, String targetName) throws Exception {
    addPackedAppArchive(artifactFileBuilder, targetName);
  }

  /**
   * Copies a given app archive with a given target name to the apps folder for deployment
   */
  private void addPackedAppArchive(TestArtifactDescriptor artifactFileBuilder, String targetFile) throws Exception {
    addArchive(appsDir, artifactFileBuilder.getArtifactFile().toURI().toURL(), targetFile);
  }

  private void addArchive(File outputDir, URL url, String targetFile) throws Exception {
    ReentrantLock lock = deploymentService.getLock();

    lock.lock();
    try {
      // copy is not atomic, copy to a temp file and rename instead (rename is atomic)
      final String tempFileName = new File((targetFile == null ? url.getFile() : targetFile) + ".part").getName();
      final File tempFile = new File(outputDir, tempFileName);
      FileUtils.copyURLToFile(url, tempFile);
      final File destFile = new File(StringUtils.removeEnd(tempFile.getAbsolutePath(), ".part"));
      File deployFolder = new File(destFile.getAbsolutePath().replace(".zip", ""));
      if (deployFolder.exists()) {
        // Delete META-INF folder so maven file do not get duplicated during redeployment testing.
        deleteDirectory(new File(deployFolder, "META-INF"));
      }
      tempFile.renameTo(destFile);
      assertThat("File does not exists: " + destFile.getAbsolutePath(), destFile.exists(), is(true));
    } finally {
      lock.unlock();
    }
  }

  private void executeApplicationFlow(String flowName) throws MuleException {
    Flow mainFlow =
        (Flow) deploymentService.getApplications().get(0).getMuleContext().getRegistry().lookupFlowConstruct(flowName);
    InternalMessage muleMessage = InternalMessage.builder().payload(TEST_MESSAGE).build();

    mainFlow.process(Event.builder(DefaultEventContext.create(mainFlow, TEST_CONNECTOR))
        .message(muleMessage)
        .flow(mainFlow).build());
  }

  private PolicyFileBuilder createPolicyIncludingPluginFileBuilder() {
    MulePolicyModelBuilder mulePolicyModelBuilder = new MulePolicyModelBuilder()
        .setMinMuleVersion(MIN_MULE_VERSION).setName(BAZ_POLICY_NAME)
        .withBundleDescriptorLoader(createBundleDescriptorLoader(BAZ_POLICY_NAME, MULE_POLICY_CLASSIFIER,
                                                                 PROPERTIES_BUNDLE_DESCRIPTOR_LOADER_ID));
    mulePolicyModelBuilder.withClassLoaderModelDescriber().setId(MAVEN);
    return policyWithHttp()
        .describedBy(mulePolicyModelBuilder.build());
  }

  private PolicyFileBuilder policyWithHttp() {
    return new PolicyFileBuilder(BAZ_POLICY_NAME) {

      public File getArtifactPomFile() {
        if (artifactPomFile == null) {
          checkArgument(!isEmpty(artifactId), "Filename cannot be empty");

          final File tempFile = new File(getTempFolder(), artifactId + ".pom");
          tempFile.deleteOnExit();

          Model model = new Model();
          model.setGroupId(getGroupId());
          model.setArtifactId(getArtifactId());
          model.setVersion(getVersion());
          model.setModelVersion("4.0.0");

          Dependency dependency = new Dependency();
          dependency.setVersion("4.0.0-SNAPSHOT");
          dependency.setClassifier("mule-plugin");
          dependency.setGroupId("org.mule.modules");
          dependency.setArtifactId("mule-module-http-ext");

          Dependency dependency2 = new Dependency();
          dependency2.setVersion("4.0.0-SNAPSHOT");
          dependency2.setClassifier("mule-plugin");
          dependency2.setGroupId("org.mule.modules");
          dependency2.setArtifactId("mule-module-sockets");

          model.addDependency(dependency);
          model.addDependency(dependency2);

          artifactPomFile = new File(tempFile.getAbsolutePath());
          try (FileOutputStream fileOutputStream = new FileOutputStream(artifactPomFile)) {
            new MavenXpp3Writer().write(fileOutputStream, model);
          } catch (IOException e) {
            throw new MuleRuntimeException(e);
          }
        }
        return artifactPomFile;
      }
    }
        .dependingOn(socketsPlugin())
        .dependingOn(httpExtPlugin());

  }

  private AbstractArtifactFileBuilder socketsPlugin() {
    return new AbstractArtifactFileBuilder("mule-module-sockets") {

      @Override
      protected List<ZipUtils.ZipResource> getCustomResources() {
        return Lists.newArrayList();
      }

      @Override
      protected AbstractDependencyFileBuilder getThis() {
        return this;
      }

      @Override
      public String getConfigFile() {
        return null;
      }

      public File getArtifactFile() {
        return new File(getClass().getResource("/mule-module-sockets-4.0.0-SNAPSHOT-mule-plugin.jar").getFile());
      }
    };
  }

  private AbstractArtifactFileBuilder httpExtPlugin() {
    return new AbstractArtifactFileBuilder("mule-module-http-ext") {

      @Override
      protected List<ZipUtils.ZipResource> getCustomResources() {
        return Lists.newArrayList();
      }

      @Override
      protected AbstractDependencyFileBuilder getThis() {
        return this;
      }

      @Override
      public String getConfigFile() {
        return null;
      }

      public File getArtifactFile() {
        return new File(getClass().getResource("/mule-module-http-ext-4.0.0-SNAPSHOT-mule-plugin.jar").getFile());
      }
    };
  }

}
