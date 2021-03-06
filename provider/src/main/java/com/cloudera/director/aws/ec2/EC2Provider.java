// (c) Copyright 2015 Cloudera, Inc.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.cloudera.director.aws.ec2;

import static com.cloudera.director.aws.ec2.EC2InstanceTemplate.EC2InstanceTemplateConfigurationPropertyToken.KEY_NAME;
import static com.cloudera.director.aws.ec2.EC2Provider.EC2ProviderConfigurationPropertyToken.ASSOCIATE_PUBLIC_IP_ADDRESSES;
import static com.cloudera.director.aws.ec2.EC2Provider.EC2ProviderConfigurationPropertyToken.IMPORT_KEY_PAIR_IF_MISSING;
import static com.cloudera.director.aws.ec2.EC2Provider.EC2ProviderConfigurationPropertyToken.KEY_NAME_PREFIX;
import static com.cloudera.director.spi.v1.compute.ComputeInstanceTemplate.ComputeInstanceTemplateConfigurationPropertyToken.SSH_JCE_PRIVATE_KEY;
import static com.cloudera.director.spi.v1.compute.ComputeInstanceTemplate.ComputeInstanceTemplateConfigurationPropertyToken.SSH_JCE_PUBLIC_KEY;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.Iterables.getOnlyElement;
import static java.util.Objects.requireNonNull;

import com.amazonaws.AmazonClientException;
import com.amazonaws.AmazonServiceException;
import com.amazonaws.services.ec2.AmazonEC2AsyncClient;
import com.amazonaws.services.ec2.AmazonEC2Client;
import com.amazonaws.services.ec2.model.AmazonEC2Exception;
import com.amazonaws.services.ec2.model.BlockDeviceMapping;
import com.amazonaws.services.ec2.model.CancelSpotInstanceRequestsRequest;
import com.amazonaws.services.ec2.model.CancelSpotInstanceRequestsResult;
import com.amazonaws.services.ec2.model.CreateTagsRequest;
import com.amazonaws.services.ec2.model.DescribeImagesRequest;
import com.amazonaws.services.ec2.model.DescribeImagesResult;
import com.amazonaws.services.ec2.model.DescribeInstanceAttributeRequest;
import com.amazonaws.services.ec2.model.DescribeInstanceStatusRequest;
import com.amazonaws.services.ec2.model.DescribeInstanceStatusResult;
import com.amazonaws.services.ec2.model.DescribeInstancesRequest;
import com.amazonaws.services.ec2.model.DescribeInstancesResult;
import com.amazonaws.services.ec2.model.DescribeKeyPairsResult;
import com.amazonaws.services.ec2.model.DescribeSpotInstanceRequestsRequest;
import com.amazonaws.services.ec2.model.DescribeSpotInstanceRequestsResult;
import com.amazonaws.services.ec2.model.DescribeVolumesRequest;
import com.amazonaws.services.ec2.model.DescribeVolumesResult;
import com.amazonaws.services.ec2.model.EbsBlockDevice;
import com.amazonaws.services.ec2.model.Filter;
import com.amazonaws.services.ec2.model.IamInstanceProfileSpecification;
import com.amazonaws.services.ec2.model.Image;
import com.amazonaws.services.ec2.model.ImportKeyPairRequest;
import com.amazonaws.services.ec2.model.Instance;
import com.amazonaws.services.ec2.model.InstanceAttribute;
import com.amazonaws.services.ec2.model.InstanceAttributeName;
import com.amazonaws.services.ec2.model.InstanceBlockDeviceMapping;
import com.amazonaws.services.ec2.model.InstanceNetworkInterfaceSpecification;
import com.amazonaws.services.ec2.model.InstanceStateName;
import com.amazonaws.services.ec2.model.InstanceStatus;
import com.amazonaws.services.ec2.model.KeyPairInfo;
import com.amazonaws.services.ec2.model.LaunchSpecification;
import com.amazonaws.services.ec2.model.Placement;
import com.amazonaws.services.ec2.model.RequestSpotInstancesRequest;
import com.amazonaws.services.ec2.model.RequestSpotInstancesResult;
import com.amazonaws.services.ec2.model.Reservation;
import com.amazonaws.services.ec2.model.ResourceType;
import com.amazonaws.services.ec2.model.RunInstancesRequest;
import com.amazonaws.services.ec2.model.RunInstancesResult;
import com.amazonaws.services.ec2.model.SpotInstanceRequest;
import com.amazonaws.services.ec2.model.SpotInstanceState;
import com.amazonaws.services.ec2.model.SpotPlacement;
import com.amazonaws.services.ec2.model.Tag;
import com.amazonaws.services.ec2.model.TagSpecification;
import com.amazonaws.services.ec2.model.TerminateInstancesRequest;
import com.amazonaws.services.ec2.model.TerminateInstancesResult;
import com.amazonaws.services.ec2.model.Volume;
import com.amazonaws.services.identitymanagement.AmazonIdentityManagementClient;
import com.amazonaws.services.kms.AWSKMSClient;
import com.cloudera.director.aws.AWSExceptions;
import com.cloudera.director.aws.AWSFilters;
import com.cloudera.director.aws.AWSTimeouts;
import com.cloudera.director.aws.CustomTagMappings;
import com.cloudera.director.aws.Tags.InstanceTags;
import com.cloudera.director.aws.Tags.ResourceTags;
import com.cloudera.director.aws.common.AWSKMSClientProvider;
import com.cloudera.director.aws.common.AmazonEC2ClientProvider;
import com.cloudera.director.aws.common.AmazonIdentityManagementClientProvider;
import com.cloudera.director.aws.ec2.ebs.EBSAllocator;
import com.cloudera.director.aws.ec2.ebs.EBSAllocator.InstanceEbsVolumes;
import com.cloudera.director.aws.ec2.ebs.EBSMetadata;
import com.cloudera.director.aws.network.NetworkRules;
import com.cloudera.director.spi.v1.compute.util.AbstractComputeProvider;
import com.cloudera.director.spi.v1.model.ConfigurationProperty;
import com.cloudera.director.spi.v1.model.ConfigurationValidator;
import com.cloudera.director.spi.v1.model.Configured;
import com.cloudera.director.spi.v1.model.InstanceState;
import com.cloudera.director.spi.v1.model.LocalizationContext;
import com.cloudera.director.spi.v1.model.Property;
import com.cloudera.director.spi.v1.model.Resource;
import com.cloudera.director.spi.v1.model.exception.PluginExceptionConditionAccumulator;
import com.cloudera.director.spi.v1.model.exception.PluginExceptionDetails;
import com.cloudera.director.spi.v1.model.exception.UnrecoverableProviderException;
import com.cloudera.director.spi.v1.model.util.CompositeConfigurationValidator;
import com.cloudera.director.spi.v1.model.util.SimpleConfiguration;
import com.cloudera.director.spi.v1.model.util.SimpleConfigurationPropertyBuilder;
import com.cloudera.director.spi.v1.model.util.SimpleResourceTemplate;
import com.cloudera.director.spi.v1.provider.ResourceProviderMetadata;
import com.cloudera.director.spi.v1.provider.util.SimpleResourceProviderMetadata;
import com.cloudera.director.spi.v1.util.ConfigurationPropertiesUtil;
import com.cloudera.director.spi.v1.util.KeySerialization;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Charsets;
import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.base.Predicate;
import com.google.common.base.Predicates;
import com.google.common.base.Stopwatch;
import com.google.common.collect.BiMap;
import com.google.common.collect.Collections2;
import com.google.common.collect.HashBiMap;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.google.common.hash.Hasher;
import com.google.common.hash.Hashing;
import com.google.common.io.BaseEncoding;

import java.io.IOException;
import java.net.InetAddress;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.EnumSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import javax.annotation.Nullable;
import javax.xml.bind.DatatypeConverter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Compute provider of Amazon EC2 instances.
 */
@SuppressWarnings("PMD.TooManyStaticImports")
public class EC2Provider extends AbstractComputeProvider<EC2Instance, EC2InstanceTemplate> {

  private static final Logger LOG = LoggerFactory.getLogger(EC2Provider.class);
  @VisibleForTesting
  protected static final int MAX_TAGS_ALLOWED = 50 - InstanceTags.values().length - ResourceTags.values().length;

  private static final Set<InstanceStateName> UNALLOCATED_STATES = Collections
      .unmodifiableSet(EnumSet.of(InstanceStateName.Terminated, InstanceStateName.ShuttingDown));

  private static final Predicate<Instance> INSTANCE_IS_TERMINAL =
      new Predicate<Instance>() {
        @Override
        public boolean apply(@Nullable Instance instance) {
          return instance != null &&
              UNALLOCATED_STATES.contains(InstanceStateName.fromValue(instance.getState().getName()));
        }
      };

  private static final Predicate<EC2Instance> EC2INSTANCE_IS_TERMINAL =
      new Predicate<EC2Instance>() {
        @Override
        public boolean apply(@Nullable EC2Instance instance) {
          return instance != null && INSTANCE_IS_TERMINAL.apply(instance.unwrap());
        }
      };

  private static final Function<EC2Instance, String> EXTRACT_VIRTUAL_INSTANCE_ID_FROM_EC2INSTANCE =
      new Function<EC2Instance, String>() {
    @Nullable
    @Override
    public String apply(@Nullable EC2Instance instance) {
      return instance == null ? null : instance.getId();
    }
  };

  /**
   * The provider configuration properties.
   */
  protected static final List<ConfigurationProperty> CONFIGURATION_PROPERTIES =
      ConfigurationPropertiesUtil.asConfigurationPropertyList(
          EC2ProviderConfigurationPropertyToken.values());

  /**
   * EC2 only allows 95 instance status requests per batch.
   */
  private static final int MAX_INSTANCE_STATUS_REQUESTS = 95;

  /**
   * EC2 only allows 200 tag values to be filtered at a time.
   */
  private static final int MAX_TAG_FILTERING_REQUESTS = 200;

  /**
   * Error message if we can't allocate the minimum number of instances.
   */
  private static final String COUNT_BELOW_THRESHOLD = "Allocated %d instances when the minimum count is %d.";

  /**
   * Insufficient instance capacity failure string.
   */
  private static final String INSUFFICIENT_INSTANCE_CAPACITY = "InsufficientInstanceCapacity";

  /**
   * Instance limit exceeded failure string.
   */
  private static final String INSTANCE_LIMIT_EXCEEDED = "InstanceLimitExceeded";

  /**
   * Request limit exceeded failure string.
   */
  private static final String REQUEST_LIMIT_EXCEEDED = "RequestLimitExceeded";

  /**
   * Max spot instance count exceeded failure string.
   */
  private static final String MAX_SPOT_INSTANCE_COUNT_EXCEEDED = "MaxSpotInstanceCountExceeded";

  /**
   * Instance ID not found failure string. This may indicate an eventual consistency issue.
   */
  @VisibleForTesting
  static final String INVALID_INSTANCE_ID_NOT_FOUND = "InvalidInstanceID.NotFound";

  /**
   * Spot Instance Request ID not found failure string. This may indicate an eventual consistency issue.
   */
  @VisibleForTesting
  static final String INVALID_SPOT_INSTANCE_REQUEST_ID_NOT_FOUND = "InvalidSpotInstanceRequestID.NotFound";

  /**
   * The resource provider ID.
   */
  public static final String ID = EC2Provider.class.getCanonicalName();

  /**
   * The resource provider metadata.
   */
  public static final ResourceProviderMetadata METADATA = SimpleResourceProviderMetadata.builder()
      .id(ID)
      .name("EC2 (Elastic Compute Cloud)")
      .description("AWS EC2 compute provider")
      .providerClass(EC2Provider.class)
      .providerConfigurationProperties(CONFIGURATION_PROPERTIES)
      .resourceTemplateConfigurationProperties(EC2InstanceTemplate.getConfigurationProperties())
      .resourceDisplayProperties(EC2Instance.getDisplayProperties())
      .build();

  /**
   * The default wait until tagged instances are findable, in milliseconds.
   */
  public static final long DEFAULT_INSTANCE_WAIT_UNTIL_FINDABLE_MS = 10 * 60 * 1000; //10 min

  /**
   * The key for the wait until instances are findable.
   */
  public static final String INSTANCE_WAIT_UNTIL_FINDABLE_MS =
      "ec2.instance.waitUntilFindableMilliseconds";

  /**
   * The default spot instance request duration, in milliseconds.
   */
  public static final long DEFAULT_SPOT_INSTANCE_REQUEST_DURATION_MS = 10 * 60 * 1000; //10 min

  /**
   * The key for spot instance timeouts.
   */
  public static final String SPOT_INSTANCE_REQUEST_DURATION_MS = "ec2.spot.requestDurationMilliseconds";

  /**
   * The default amount of time to wait, in milliseconds, for a Spot price change when the Spot
   * bid is known to be below the current Spot price.
   */
  public static final int DEFAULT_SPOT_INSTANCE_PRICE_CHANGE_DURATION_MS = 0;

  /**
   * EC2 configuration properties.
   */
  // Fully qualifying class name due to compiler bug
  public enum EC2ProviderConfigurationPropertyToken
      implements com.cloudera.director.spi.v1.model.ConfigurationPropertyToken {

    /**
     * Whether to associate a public IP address with instances. Default is <code>true</code>.
     *
     * @see <a href="http://docs.aws.amazon.com/AmazonVPC/latest/UserGuide/vpc-ip-addressing.html">IP Addressing in
     * your VPC</a>
     */
    ASSOCIATE_PUBLIC_IP_ADDRESSES(new SimpleConfigurationPropertyBuilder()
        .configKey("associatePublicIpAddresses")
        .name("Associate public IP addresses")
        .widget(ConfigurationProperty.Widget.CHECKBOX)
        .defaultValue("true")
        .type(Property.Type.BOOLEAN)
        .defaultDescription("Whether to associate a public IP address with instances or not. " +
            "If this is false, instances are expected to be able to access the internet using a NAT instance. " +
            "Currently the only way to get optimal S3 data transfer performance is to assign " +
            "public IP addresses to instances and not use NAT instances (public subnet setup).")
        .build()),

    /**
     * A custom IAM endpoint URL. When not specified, the global IAM endpoint is used.
     *
     * @see <a href="http://docs.aws.amazon.com/general/latest/gr/rande.html#iam_region">IAM endpoints</a>
     */
    IAM_ENDPOINT(new SimpleConfigurationPropertyBuilder()
        .configKey("iamEndpoint")
        .name("IAM endpoint")
        .defaultDescription("<p>IAM endpoint is an optional URL that Cloudera Director can use to communicate with" +
            " the AWS Identity and Access Management service.  AWS provides a single endpoint for IAM.</p>For more" +
            " information see the <a target=\"_blank\" href=" +
            "\"http://docs.aws.amazon.com/general/latest/gr/rande.html#iam_region\">AWS documentation.</a>")
        .build()),

    /**
     * Whether to import key pair to AWS if it's missing. Default is <code>false</code>.
     *
     * @see <a href="http://docs.aws.amazon.com/cli/latest/reference/ec2/import-key-pair.html">Importing key pair
     * to your AWS account</a>
     */
    IMPORT_KEY_PAIR_IF_MISSING(new SimpleConfigurationPropertyBuilder()
        .configKey("importKeyPairIfMissing")
        .name("Import key pair if missing")
        .widget(ConfigurationProperty.Widget.CHECKBOX)
        .defaultValue("false")
        .type(Property.Type.BOOLEAN)
        .defaultDescription("<p>Whether to import missing key pair to your EC2 account. The public key is " +
            "extracted from PEM encoding of the private key supplied in the request.</p>For more information see the " +
            "<a target=\"_blank\" href=\"http://docs.aws.amazon.com/cli/latest/reference/ec2/import-key-pair.html\">" +
            "AWS documentation.</a>")
        .build()),

    /**
     * Prefix for key names used when importing missing key pairs. Default is "CLOUDERA-".
     *
     * @see <a href="http://docs.aws.amazon.com/cli/latest/reference/ec2/import-key-pair.html">Importing key pair
     * to your AWS account</a>
     */
    KEY_NAME_PREFIX(new SimpleConfigurationPropertyBuilder()
        .configKey("keyNamePrefix")
        .name("Prefix for key names used in import")
        .defaultValue("CLOUDERA-")
        .defaultDescription("<p>Prefix used for generated key names used when importing missing key pairs.</p>")
        .build()),

    /**
     * EC2 region. Each region is a separate geographic area. Each region has multiple, isolated
     * locations known as Availability Zones. Default is us-east-1.
     *
     * @see <a href="http://docs.aws.amazon.com/AWSEC2/latest/UserGuide/using-regions-availability-zones.html">Regions
     * and Availability Zones</a>
     */
    REGION(new SimpleConfigurationPropertyBuilder()
        .configKey("region")
        .name("EC2 region")
        .defaultValue("us-east-1")
        .defaultDescription("ID of the Amazon Web Services region to use.")
        .widget(ConfigurationProperty.Widget.OPENLIST)
        .addValidValues(
            "ap-northeast-1",
            "ap-northeast-2",
            "ap-south-1",
            "ap-southeast-1",
            "ap-southeast-2",
            "ca-central-1",
            "eu-central-1",
            "eu-west-1",
            "eu-west-2",
            "sa-east-1",
            "us-east-1",
            "us-east-2",
            "us-west-1",
            "us-west-2")
        .build()),

    /**
     * <p>Custom endpoint identifying a region.</p>
     * <p>This is critical for Gov. cloud because there is no other way to discover those
     * regions.</p>
     */
    REGION_ENDPOINT(new SimpleConfigurationPropertyBuilder()
        .configKey("regionEndpoint")
        .name("EC2 region endpoint")
        .defaultDescription("<p>EC2 region endpoint is an optional URL that Cloudera Director can use to " +
                            "communicate with the AWS EC2 service.  AWS provides multiple regional endpoints for " +
                            "EC2 as well as GovCloud endpoints.</p>For more information see the <a target=\"_blank\" " +
                            "href=\"http://docs.aws.amazon.com/general/latest/gr/rande.html#ec2_region\">AWS " +
                            "documentation.</a>")
        .build()),

    /**
     * <p>Custom endpoint identifying a region for KMS.</p>
     * <p>This is critical for Gov. cloud because there is no other way to discover those
     * regions.</p>
     */
    KMS_REGION_ENDPOINT(new SimpleConfigurationPropertyBuilder()
        .configKey("kmsRegionEndpoint")
        .name("KMS region endpoint")
        .defaultDescription("<p>KMS region endpoint is an optional URL that Cloudera Director can use to " +
                            "communicate with the AWS KMS service. AWS provides multiple regional endpoints for KMS " +
                            "as well as GovCloud endpoints.</p>For more information see the <a target=\"_blank\" " +
                            "href=\"http://docs.aws.amazon.com/general/latest/gr/rande.html#kms_region\">AWS " +
                            "documentation.</a>")
        .build());

    /**
     * The configuration property.
     */
    private final ConfigurationProperty configurationProperty;

    /**
     * Creates a configuration property token with the specified parameters.
     *
     * @param configurationProperty the configuration property
     */
    EC2ProviderConfigurationPropertyToken(ConfigurationProperty configurationProperty) {
      this.configurationProperty = configurationProperty;
    }

    @Override
    public ConfigurationProperty unwrap() {
      return configurationProperty;
    }
  }

  private enum EBSAllocationStrategy {
    NO_EBS_VOLUMES,
    AS_INSTANCE_REQUEST,
    AS_SEPARATE_REQUESTS;

    private static EBSAllocationStrategy get(EC2InstanceTemplate template) {
      if (template.getEbsVolumeCount() == 0) {
        return NO_EBS_VOLUMES;
      }

      // Ideally we want to request EBS volumes as part of the instance launch request.
      // However due to AWS API limitations, requesting encrypted EBS volumes with a
      // user specified KMS key can not be done as part of instance launch. In this
      // scenario we have to individually create and attach each EBS volume separately
      // after instance launch.

      if (template.isEnableEbsEncryption() && template.getEbsKmsKeyId().isPresent()) {
        return AS_SEPARATE_REQUESTS;
      } else {
        return AS_INSTANCE_REQUEST;
      }
    }
  }

  private final AmazonEC2AsyncClient client;
  private final AmazonIdentityManagementClient identityManagementClient;
  private final AWSKMSClient kmsClient;

  private final EphemeralDeviceMappings ephemeralDeviceMappings;
  private final VirtualizationMappings virtualizationMappings;
  private final AWSFilters ec2Filters;
  @VisibleForTesting
  final EC2TagHelper ec2TagHelper;
  private final NetworkRules networkRules;

  private final boolean associatePublicIpAddresses;
  private final boolean importKeyPairIfMissing;
  private final String keyNamePrefix;
  private final long spotRequestDuration;
  private final long waitUntilFindableMillis;

  private final ConfigurationValidator resourceTemplateConfigurationValidator;

  private final EBSAllocator ebsAllocator;

  /**
   * Construct a new provider instance and validate all configurations.
   *
   * @param configuration            the configuration
   * @param ephemeralDeviceMappings  the ephemeral device mappings
   * @param ebsMetadata              the EBS metadata
   * @param virtualizationMappings   the virtualization mappings
   * @param awsFilters               the AWS filters
   * @param awsTimeouts              the AWS timeouts
   * @param customTagMappings        the custom tag mappings
   * @param networkRules             the network rules
   * @param clientProvider                   the EC2 client provider
   * @param identityManagementClientProvider the IAM client provider
   * @param kmsClientProvider                the KMS client provider
   * @param cloudLocalizationContext         the parent cloud localization context
   * @throws UnrecoverableProviderException  if an unrecoverable exception occurs communicating with
   *                                         the provider
   */
  public EC2Provider(
      Configured configuration,
      EphemeralDeviceMappings ephemeralDeviceMappings,
      EBSMetadata ebsMetadata,
      VirtualizationMappings virtualizationMappings,
      AWSFilters awsFilters,
      AWSTimeouts awsTimeouts,
      CustomTagMappings customTagMappings,
      NetworkRules networkRules,
      AmazonEC2ClientProvider clientProvider,
      AmazonIdentityManagementClientProvider identityManagementClientProvider,
      AWSKMSClientProvider kmsClientProvider,
      LocalizationContext cloudLocalizationContext) {

    super(configuration, METADATA, cloudLocalizationContext);
    LocalizationContext localizationContext = getLocalizationContext();
    this.ephemeralDeviceMappings =
        requireNonNull(ephemeralDeviceMappings, "ephemeralDeviceMappings is null");
    this.virtualizationMappings =
        requireNonNull(virtualizationMappings, "virtualizationMappings is null");
    this.ec2Filters = requireNonNull(awsFilters, "awsFilters").getSubfilters(ID);
    this.ec2TagHelper = new EC2TagHelper(customTagMappings);
    this.networkRules = requireNonNull(networkRules, "networkRules is null");

    PluginExceptionConditionAccumulator accumulator = new PluginExceptionConditionAccumulator();

    this.client = requireNonNull(clientProvider, "clientProvider is null")
        .getClient(configuration, accumulator, localizationContext, false);
    this.identityManagementClient = requireNonNull(
        identityManagementClientProvider, "identityManagementClientProvider is null")
        .getClient(configuration, accumulator, localizationContext, false);
    this.kmsClient = requireNonNull(kmsClientProvider, "kmsClientProvider is null")
        .getClient(configuration, accumulator, localizationContext, false);

    if (accumulator.hasError()) {
      PluginExceptionDetails pluginExceptionDetails =
          new PluginExceptionDetails(accumulator.getConditionsByKey());
      throw new UnrecoverableProviderException("Provider initialization failed",
          pluginExceptionDetails);
    }

    this.associatePublicIpAddresses = Boolean.parseBoolean(
        getConfigurationValue(ASSOCIATE_PUBLIC_IP_ADDRESSES, localizationContext));

    this.importKeyPairIfMissing = Boolean.parseBoolean(
        getConfigurationValue(IMPORT_KEY_PAIR_IF_MISSING, localizationContext));
    this.keyNamePrefix = getConfigurationValue(KEY_NAME_PREFIX, localizationContext);

    this.spotRequestDuration =
        awsTimeouts.getTimeout(SPOT_INSTANCE_REQUEST_DURATION_MS).or(DEFAULT_SPOT_INSTANCE_REQUEST_DURATION_MS);

    this.waitUntilFindableMillis =
        awsTimeouts.getTimeout(INSTANCE_WAIT_UNTIL_FINDABLE_MS).or(DEFAULT_INSTANCE_WAIT_UNTIL_FINDABLE_MS);

    this.resourceTemplateConfigurationValidator =
        new CompositeConfigurationValidator(
            METADATA.getResourceTemplateConfigurationValidator(),
            new EC2InstanceTemplateConfigurationValidator(this, ebsMetadata),
            new EC2NetworkValidator(this)
        );

    this.ebsAllocator = new EBSAllocator(this.client, awsTimeouts, ec2TagHelper);
  }

  public AmazonEC2Client getClient() {
    return client;
  }

  public AmazonIdentityManagementClient getIdentityManagementClient() {
    return identityManagementClient;
  }

  public AWSKMSClient getKmsClient() {
    return kmsClient;
  }

  /**
   * Returns the ephemeral device mappings.
   *
   * @return the ephemeral device mappings
   */
  public EphemeralDeviceMappings getEphemeralDeviceMappings() {
    return ephemeralDeviceMappings;
  }

  /**
   * Returns the virtualization mappings.
   *
   * @return the virtualization mappings
   */
  public VirtualizationMappings getVirtualizationMappings() {
    return virtualizationMappings;
  }

  /**
   * Returns the EC2 filters.
   *
   * @return the EC2 filters
   */
  public AWSFilters getEC2Filters() {
    return ec2Filters;
  }

  /**
   * Returns the EC2 tag helper.
   *
   * @return the EC2 tag helper
   */
  public EC2TagHelper getEC2TagHelper() {
    return ec2TagHelper;
  }

  /**
   * Returns the network rules.
   *
   * @return the network rules
   */
  public NetworkRules getNetworkRules() {
    return networkRules;
  }

  @Override
  public ConfigurationValidator getResourceTemplateConfigurationValidator() {
    return resourceTemplateConfigurationValidator;
  }

  @Override
  public Resource.Type getResourceType() {
    return EC2Instance.TYPE;
  }

  @Override
  public EC2InstanceTemplate createResourceTemplate(String name, Configured configuration,
      Map<String, String> tags) {

    LocalizationContext providerLocalizationContext = getLocalizationContext();
    LocalizationContext templateLocalizationContext =
        SimpleResourceTemplate.getTemplateLocalizationContext(providerLocalizationContext);
    configuration = enhanceTemplateConfiguration(name, configuration, templateLocalizationContext);

    return new EC2InstanceTemplate(name, configuration, tags, providerLocalizationContext);
  }

  @Override
  public void allocate(EC2InstanceTemplate template, Collection<String> virtualInstanceIds, int minCount)
      throws InterruptedException {

    // TODO: This should really be taken care of in the SPI validate command.
    // We are punting this for a future change that compels us to release a new version of SPI
    validateTags(template.getTags());

    Collection<String> allocatedVirtualInstanceIds;
    if (template.isUseSpotInstances()) {
      allocatedVirtualInstanceIds = allocateSpotInstances(template, virtualInstanceIds, minCount);
    } else {
      allocatedVirtualInstanceIds = allocateOnDemandInstances(template, virtualInstanceIds, minCount);
    }

    if (EBSAllocationStrategy.get(template) == EBSAllocationStrategy.AS_SEPARATE_REQUESTS) {
      if (allocatedVirtualInstanceIds.size() > 0) {
        LOG.info(">> Allocating EBS volumes");
        allocateEbsVolumes(template, allocatedVirtualInstanceIds, minCount);
      } else {
        LOG.info(">> Skipping EBS volume allocation since no instances were allocated");
      }
    }
  }

  /**
   * Creates and attaches EBS volumes to EC2 instances. This expects that the instances
   * have already been allocated. Instances that could not acquire the correct number of
   * EBS volumes will be terminated along with any leftover volumes. If the minimum
   * number of instances could not acquire EBS volumes, all the specified instances and
   * leftover volumes will be terminated.
   *
   * @param template           the EC2 instance template that contains EBS configurations
   * @param virtualInstanceIds list of virtual instance to attach volumes to.
   * @param minCount           the minimum number of instances that need EBS volumes attached
   * @throws InterruptedException
   */
  private void allocateEbsVolumes(EC2InstanceTemplate template, Collection<String> virtualInstanceIds, int minCount)
      throws InterruptedException {

    boolean success = false;

    BiMap<String, String> instanceIdPairs = getEC2InstanceIdsByVirtualInstanceId(virtualInstanceIds);
    List<InstanceEbsVolumes> instanceVolumes = ebsAllocator.createVolumes(template, instanceIdPairs);

    try {
      instanceVolumes = ebsAllocator.waitUntilVolumesAvailable(instanceVolumes);
      instanceVolumes = ebsAllocator.attachVolumes(template, instanceVolumes);
      ebsAllocator.addDeleteOnTerminationFlag(instanceVolumes);

      int successfulInstances = getSuccessfulInstanceCount(instanceVolumes);
      LOG.info("{} out of {} instances successfully acquired EBS volumes",
          successfulInstances, virtualInstanceIds.size());

      if (successfulInstances < minCount) {
        LOG.warn("A minimum number of {} instances could not acquire EBS volumes, cleaning up by " +
            "deleting all allocated instances and volumes", minCount);
      } else {
        success = true;
      }
    } catch (InterruptedException e) {
      throw e;
    } catch (Exception e) {
      throw new UnrecoverableProviderException("Unexpected problem allocating EBS volumes", e);
    } finally {
      if (!success) {
        deleteAllInstancesAndVolumes(instanceVolumes, template, ebsAllocator);
      } else {
        deleteFailedInstancesAndVolumes(instanceVolumes, template, ebsAllocator);
      }
    }
  }

  /**
   * Terminates failed instances and their associated volumes. In this case a failed
   * instance is any instance that doesn't have all their volumes as ATTACHED.
   */
  private void deleteFailedInstancesAndVolumes(List<InstanceEbsVolumes> instanceEbsVolumesList,
      EC2InstanceTemplate template, EBSAllocator ebsAllocator) throws InterruptedException {

    Set<String> instancesToTerminate = Sets.newHashSet();

    nextInstanceVolume:
    for (InstanceEbsVolumes instanceEbsVolumes : instanceEbsVolumesList) {
      for (InstanceEbsVolumes.Status status : instanceEbsVolumes.getVolumeStatuses().values()) {
        if (status != InstanceEbsVolumes.Status.ATTACHED) {
          deleteCreatedVolumes(instanceEbsVolumes, ebsAllocator);
          instancesToTerminate.add(instanceEbsVolumes.getVirtualInstanceId());
          continue nextInstanceVolume;
        }
      }
    }
    delete(template, instancesToTerminate);
  }


  /**
   * Terminates a list of instances and deletes their associated volumes.
   */
  private void deleteAllInstancesAndVolumes(List<InstanceEbsVolumes> instanceEbsVolumesList,
      EC2InstanceTemplate template, EBSAllocator ebsAllocator) throws InterruptedException {

    Set<String> instancesToTerminate = Sets.newHashSet();

    for (InstanceEbsVolumes instanceEbsVolumes : instanceEbsVolumesList) {
      deleteCreatedVolumes(instanceEbsVolumes, ebsAllocator);
      instancesToTerminate.add(instanceEbsVolumes.getVirtualInstanceId());
    }
    delete(template, instancesToTerminate);
  }

  private void deleteCreatedVolumes(InstanceEbsVolumes instanceEbsVolumes, EBSAllocator ebsAllocator) {
    Set<String> volumesToDelete = Sets.newHashSet();
    for (String volumeId : instanceEbsVolumes.getVolumeStatuses().keySet()) {
      if (!volumeId.startsWith(InstanceEbsVolumes.UNCREATED_VOLUME_ID)) {
        volumesToDelete.add(volumeId);
      }
    }
    ebsAllocator.deleteVolumes(volumesToDelete);
  }

  /**
   * Get the number of instances that have successfully acquired volumes. Success means
   * that all volumes associated with an instance are in the ATTACHED state.
   */
  private int getSuccessfulInstanceCount(List<InstanceEbsVolumes> instanceEbsVolumesList) {
    int count = 0;
    for (InstanceEbsVolumes instanceEbsVolumes : instanceEbsVolumesList) {
      boolean success = true;
      for (InstanceEbsVolumes.Status status : instanceEbsVolumes.getVolumeStatuses().values()) {
        if (status != InstanceEbsVolumes.Status.ATTACHED) {
          success = false;
          break;
        }
      }
      if (success) count++;
    }
    return count;
  }

  /**
   * Get EBS volumes attached to the specified virtual instance id.
   *
   * @return list of ebs volumes
   */
  @VisibleForTesting
  List<Volume> getVolumes(String virtualInstanceId) {
    String ec2InstanceId = getOnlyElement(
        getEC2InstanceIdsByVirtualInstanceId(
            Collections.singletonList(virtualInstanceId)
        ).values()
    );

    InstanceAttribute instanceAttribute =
        describeInstanceAttribute(ec2InstanceId, InstanceAttributeName.BlockDeviceMapping);
    List<InstanceBlockDeviceMapping> blockDeviceMappings =
        instanceAttribute.getBlockDeviceMappings();

    List<String> volumeIds = Lists.newArrayList();
    for (InstanceBlockDeviceMapping mapping : blockDeviceMappings) {
      volumeIds.add(mapping.getEbs().getVolumeId());
    }

    DescribeVolumesResult volumeResults = client.describeVolumes(
        new DescribeVolumesRequest().withVolumeIds(volumeIds)
    );

    return volumeResults.getVolumes();
  }

  @Override
  public Collection<EC2Instance> find(final EC2InstanceTemplate template,
      Collection<String> virtualInstanceIds) throws InterruptedException {

    LOG.debug("Finding virtual instances {}", virtualInstanceIds);
    final Collection<EC2Instance> ec2Instances =
        Lists.newArrayListWithExpectedSize(virtualInstanceIds.size());

    forEachInstance(virtualInstanceIds, new InstanceHandler() {
      @Override
      public void handle(Instance instance) {
        String virtualInstanceId = checkInstanceIsManagedByDirector(instance, template);
        fillMissingProperties(instance);
        ec2Instances.add(new EC2Instance(template, virtualInstanceId, instance));
      }
    });

    LOG.debug("Found {} instances for {} virtual instance IDs", ec2Instances.size(),
        virtualInstanceIds.size());
    return ec2Instances;
  }

  private void fillMissingProperties(Instance instance) {
    try {
      InstanceAttribute instanceAttribute = describeInstanceAttribute(
          instance.getInstanceId(), InstanceAttributeName.SriovNetSupport);
      String sriovNetSupport = instanceAttribute.getSriovNetSupport();
      instance.setSriovNetSupport(sriovNetSupport);
    } catch (AmazonServiceException e) {
      // In practice, users may not have appropriate IAM permission for
      // DescribeInstanceAttribute. We need to be more forgiving in those cases,
      // and simply leave a warning in the log here.
      LOG.warn("Could not fill missing properties. Failed to perform " +
          "DescribeInstanceAttribute action.", e);
    }
  }

  /**
   * Retrieves an instance attribute.
   *
   * @param  instanceId instance ID
   * @param  attribute  name of attribute to describe
   * @return            attribute
   */
  @VisibleForTesting
  InstanceAttribute describeInstanceAttribute(String instanceId, InstanceAttributeName attribute) {
    DescribeInstanceAttributeRequest request = new DescribeInstanceAttributeRequest()
        .withInstanceId(instanceId)
        .withAttribute(attribute);

    return client.describeInstanceAttribute(request).getInstanceAttribute();
  }

  @Override
  @SuppressWarnings("PMD.UnusedFormalParameter")
  public void delete(EC2InstanceTemplate template,
      Collection<String> virtualInstanceIds) throws InterruptedException {

    if (virtualInstanceIds.isEmpty()) {
      return;
    }

    Map<String, String> ec2InstanceIdsByVirtualInstanceId =
        getEC2InstanceIdsByVirtualInstanceId(virtualInstanceIds);
    Collection<String> ec2InstanceIds = ec2InstanceIdsByVirtualInstanceId.values();
    if (ec2InstanceIds.isEmpty()) {
      LOG.info("Unable to terminate instances, all unknown {}", virtualInstanceIds);
      return;
    }

    LOG.info(">> Terminating {}", ec2InstanceIds);
    TerminateInstancesResult terminateResult;
    try {
      terminateResult = client.terminateInstances(
          new TerminateInstancesRequest().withInstanceIds(ec2InstanceIds));
    } catch (AmazonClientException e) {
      throw AWSExceptions.propagate(e);
    }

    LOG.info("<< Result {}", terminateResult);

    if (ec2InstanceIdsByVirtualInstanceId.size() != virtualInstanceIds.size()) {
      Set<String> missingVirtualInstanceIds = Sets.newLinkedHashSet();
      for (String virtualInstanceId : virtualInstanceIds) {
        if (!ec2InstanceIdsByVirtualInstanceId.containsKey(virtualInstanceId)) {
          missingVirtualInstanceIds.add(virtualInstanceId);
        }
      }
      LOG.info("Unable to terminate unknown instances {}", missingVirtualInstanceIds);
    }
  }

  @Override
  @SuppressWarnings("PMD.UnusedFormalParameter")
  public Map<String, InstanceState> getInstanceState(EC2InstanceTemplate template,
      Collection<String> virtualInstanceIds) {
    Map<String, InstanceState> instanceStateByVirtualInstanceId =
        Maps.newHashMapWithExpectedSize(virtualInstanceIds.size());

    // Partition full requests into multiple batch requests, AWS limits
    // the total number of instance status requests you can make.
    List<List<String>> partitions =
        Lists.partition(Lists.newArrayList(virtualInstanceIds), MAX_INSTANCE_STATUS_REQUESTS);

    for (List<String> partition : partitions) {
      instanceStateByVirtualInstanceId.putAll(getBatchInstanceState(partition));
    }

    return instanceStateByVirtualInstanceId;
  }

  @Override
  protected Configured enhanceTemplateConfiguration(String name, Configured configuration,
      LocalizationContext templateLocalizationContext) {
    // Add the key name to the configuration if possible.
    String privateKeyString =
        configuration.getConfigurationValue(SSH_JCE_PRIVATE_KEY, templateLocalizationContext);
    if (privateKeyString != null) {
      String publicKeyString =
          configuration.getConfigurationValue(SSH_JCE_PUBLIC_KEY, templateLocalizationContext);
      configuration = addKeyName(configuration, templateLocalizationContext, privateKeyString,
          publicKeyString);
    } else {
      LOG.warn("No private key fingerprint specified for template {}", name);
    }
    return configuration;
  }

  /**
   * Adds the AWS key name corresponding to a private key to the given
   * configuration.
   *
   * @param configuration    the configuration to be enhanced
   * @param privateKeyString private key, in serialized form
   * @return the enhanced configuration
   * @throws IllegalArgumentException if the key could not be deserialized, or if no key known to
   *                                  AWS matches this key's fingerprint
   */
  private Configured addKeyName(Configured configuration,
      LocalizationContext templateLocalizationContext,
      String privateKeyString, String publicKeyString) {
    PrivateKey privateKey;
    PublicKey publicKey;
    try {
      KeySerialization keySerialization = new KeySerialization();
      privateKey = keySerialization.deserializePrivateKey(privateKeyString);
      if (publicKeyString != null) {
        publicKey = keySerialization.deserializePublicKey(publicKeyString);
      } else {
        publicKey = null;
      }
    } catch (IOException e) {
      throw new IllegalArgumentException(
          "Unable to deserialize private key from compute instance template", e);
    }
    String privateKeyFingerprint = getSha1Fingerprint(privateKey);
    String publicKeyFingerprint = getMd5Fingerprint(publicKey);
    String keyName = lookupKeyName(privateKeyFingerprint, publicKeyFingerprint);
    if (keyName == null) {
      if (importKeyPairIfMissing) {
        keyName = keyNamePrefix + publicKeyFingerprint;
        LOG.info("KeyPair not found. Adding public key to EC2 with key name : {}", keyName);
        try {
          client.importKeyPair(new ImportKeyPairRequest().withKeyName(keyName).withPublicKeyMaterial(
              BaseEncoding.base64().encode(publicKey.getEncoded())));
        } catch (AmazonEC2Exception e) {
          if (e.getErrorCode().equals("UnauthorizedOperation")) {
            String message = String.format("No private key in EC2 matches the " +
                "fingerprint %s. To auto register keys, " +
                "add ec2:ImportKeyPair permission", privateKeyFingerprint);
            throw new AmazonEC2Exception(message);
          }
          throw e;
        }
      } else {
        throw new IllegalArgumentException("No private key in EC2 matches the fingerprint " +
            privateKeyFingerprint);
      }
    } else {
      LOG.info("Found EC2 key name {} for fingerprint", keyName);
    }
    Map<String, String> configMap =
        Maps.newHashMap(configuration.getConfiguration(templateLocalizationContext));
    configMap.put(KEY_NAME.unwrap().getConfigKey(),
        keyName);
    return new SimpleConfiguration(configMap);
  }

  /**
   * Gets the SHA-1 digest of the private key bits. This is used as a
   * fingerprint by AWS for a key pair generated by AWS.
   *
   * @param privateKey private key
   * @return private key fingerprint, as a lowercase hex string without colons
   * @throws IllegalStateException if the SHA-1 digest algorithm is not
   *                               available
   */
  @VisibleForTesting
  static String getSha1Fingerprint(PrivateKey privateKey) {
    MessageDigest digest;
    try {
      digest = MessageDigest.getInstance("SHA-1");
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
    byte[] fingerprintBytes = digest.digest(privateKey.getEncoded());
    return DatatypeConverter.printHexBinary(fingerprintBytes).toLowerCase(Locale.US);
  }

  /**
   * Gets the MD5 digest of the public key bits. This is used as a fingerprint
   * by AWS for an imported key pair.
   *
   * @param publicKey public key
   * @return public key fingerprint, as a lowercase hex string without colons,
   * or null if publicKey is null
   */
  @VisibleForTesting
  static String getMd5Fingerprint(PublicKey publicKey) {
    if (publicKey == null) {
      return null;
    }
    MessageDigest digest;
    try {
      digest = MessageDigest.getInstance("MD5");
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
    byte[] fingerprintBytes = digest.digest(publicKey.getEncoded());
    return DatatypeConverter.printHexBinary(fingerprintBytes).toLowerCase(Locale.US);
  }

  /**
   * Returns the key name corresponding to the specified fingerprints, or {@code null} if it
   * cannot be determined.
   *
   * @param privateKeyFingerprint the private key fingerprint
   * @param publicKeyFingerprint  the public key fingerprint
   * @return the key name corresponding to the specified fingerprints, or {@code null} if it
   * cannot be determined
   */
  private String lookupKeyName(String privateKeyFingerprint, String publicKeyFingerprint) {
    DescribeKeyPairsResult keyPairsResult = client.describeKeyPairs();
    for (KeyPairInfo keyPairInfo : keyPairsResult.getKeyPairs()) {
      String knownFingerprint = keyPairInfo.getKeyFingerprint().replace(":", "");
      LOG.debug("Found fingerprint {} for keyName {}", knownFingerprint, keyPairInfo.getKeyName());
      if (privateKeyFingerprint.equals(knownFingerprint)) {
        return keyPairInfo.getKeyName();
      }
      if (publicKeyFingerprint.equals(knownFingerprint)) {
        return keyPairInfo.getKeyName();
      }
    }
    return null;
  }

  /**
   * Atomically allocates multiple regular EC2 instances with the specified identifiers based on a
   * single instance template. If not all the instances can be allocated, the number of instances
   * allocated must be at least the specified minimum or the method must fail cleanly with no
   * billing implications.
   *
   * @param template           the instance template
   * @param virtualInstanceIds the unique identifiers for the instances
   * @param minCount           the minimum number of instances to allocate if not all resources can
   *                           be allocated
   * @return the virtual instance ids of the instances that were allocated
   * @throws InterruptedException if the operation is interrupted
   */
  public Collection<String> allocateOnDemandInstances(EC2InstanceTemplate template,
      Collection<String> virtualInstanceIds, int minCount)
      throws InterruptedException {

    int instanceCount = virtualInstanceIds.size();

    LOG.info(">> Requesting {} instances for {}", instanceCount, template);

    boolean success = false;

    try {
      // Try to find all instances that are not in a terminal state
      Collection<EC2Instance> existingInstance = Collections2.filter(find(template, virtualInstanceIds),
          Predicates.not(EC2INSTANCE_IS_TERMINAL));
      Collection<String> existingInstanceVirtualInstanceIds = Collections2.transform(existingInstance,
          EXTRACT_VIRTUAL_INSTANCE_ID_FROM_EC2INSTANCE);

      if (!existingInstanceVirtualInstanceIds.isEmpty()) {
        LOG.info("Instances with the following virtual instance IDs were already found allocated: {}",
            existingInstanceVirtualInstanceIds);
      }

      List<Tag> userDefinedTags = ec2TagHelper.getUserDefinedTags(template);
      List<RunInstancesResult> runInstancesResults = Lists.newArrayList();

      // Remove any already allocated instances
      Set<String> unallocatedInstanceIds = Sets.newHashSet(virtualInstanceIds);
      unallocatedInstanceIds.removeAll(existingInstanceVirtualInstanceIds);

      Map<String, Future<RunInstancesResult>> runInstanceRequests = Maps.newHashMap();
      LOG.info(">> Building {} instance requests", unallocatedInstanceIds.size());

      boolean rateLimitIssues = false;
      boolean capacityIssues = false;
      for (String virtualInstanceId : unallocatedInstanceIds) {
        runInstanceRequests.put(virtualInstanceId, client.runInstancesAsync(
            newRunInstancesRequest(template, virtualInstanceId, userDefinedTags)));
      }

      LOG.info(">> Submitted {} run instance requests.", runInstanceRequests.size());

      try {
        for (Map.Entry<String, Future<RunInstancesResult>> runInstanceRequest : runInstanceRequests.entrySet()) {
          try {
            runInstancesResults.add(runInstanceRequest.getValue().get());
          } catch (ExecutionException e) {
            if (e.getCause() instanceof AmazonServiceException) {
              AmazonServiceException awsException = (AmazonServiceException) e.getCause();

              AWSExceptions.propagateIfUnrecoverable(awsException);

              if (INSUFFICIENT_INSTANCE_CAPACITY.equals(awsException.getErrorCode()) ||
                  INSTANCE_LIMIT_EXCEEDED.equals(awsException.getErrorCode())) {
                capacityIssues = true;
              } else if (REQUEST_LIMIT_EXCEEDED.equals(awsException.getErrorCode())) {
                rateLimitIssues = true;
              } else {
                LOG.error("Exception while trying to allocate instance.", e);
              }
            } else {
              LOG.error("Error while requesting instance {}. Attempting to proceed.", runInstanceRequest.getKey());
              LOG.debug("Exception caught:", e);
            }
          }
        }
      } catch (AmazonServiceException e) {
        for (Future<RunInstancesResult> runInstanceRequest : runInstanceRequests.values()) {
          if (!runInstanceRequest.isDone()) {
            runInstanceRequest.cancel(true);
          }
        }

        throw e;
      }

      if (capacityIssues) {
        LOG.warn("Some instances were not allocated due to instance limits or capacity issues. " +
            "Checking if we have enough instances to continue.");
      }

      if (rateLimitIssues) {
        LOG.warn("Encountered rate limit errors while allocating instances. " +
            "Checking if we have enough instances to continue.");
      }

      if (LOG.isInfoEnabled()) {
        for (RunInstancesResult runInstancesResult : runInstancesResults) {
          LOG.info("<< Reservation {} with {}", runInstancesResult.getReservation().getReservationId(),
              summarizeReservationForLogging(runInstancesResult.getReservation()));
        }
      }

      // Determine which do not yet have a private IP address.

      Set<String> instancesWithNoPrivateIp = Sets.newHashSet();

      List<Instance> instances = Lists.newArrayList();

      for (RunInstancesResult runInstancesResult : runInstancesResults) {
        instances.addAll(runInstancesResult.getReservation().getInstances());
      }

      // Add in the already existing instances we found before
      for (EC2Instance ec2Instance : existingInstance) {
        instances.add(ec2Instance.unwrap());
      }

      if (instances.size() < minCount) {
        throw new UnrecoverableProviderException(String.format(COUNT_BELOW_THRESHOLD, instances.size(), minCount));
      }

      List<String> successfulVirtualInstanceIds = Lists.newArrayList();

      for (Instance instance : instances) {

        String virtualInstanceId = getVirtualInstanceId(instance.getTags(), "instance");
        String ec2InstanceId = instance.getInstanceId();

        if (waitUntilInstanceHasStarted(ec2InstanceId)) {
          successfulVirtualInstanceIds.add(virtualInstanceId);

          if (instance.getPrivateIpAddress() == null) {
            instancesWithNoPrivateIp.add(ec2InstanceId);
          } else {
            LOG.info("<< Instance {} got IP {}", ec2InstanceId, instance.getPrivateIpAddress());
          }
        } else {
          LOG.info("<< Instance {} did not start.", ec2InstanceId);
        }
      }

      // Wait until all of them have a private IP (it should be pretty fast)
      Collection<String> terminatedInstanceIds = waitForPrivateIpAddresses(instancesWithNoPrivateIp);

      // Remove any failed instances from the list of successful instance IDs
      successfulVirtualInstanceIds.removeAll(terminatedInstanceIds);

      int numInstancesAlive = successfulVirtualInstanceIds.size();
      if (numInstancesAlive < minCount) {
        LOG.error("Number of instances allocated ({}) is less than the minimum count ({}). " +
            "One or more instances may have unexpectedly terminated.", numInstancesAlive, minCount);
        throw new UnrecoverableProviderException(String.format(COUNT_BELOW_THRESHOLD, numInstancesAlive, minCount));
      }

      success = true;
      return successfulVirtualInstanceIds;
    } catch (InterruptedException e) {
      throw e;
    } catch (Exception e) {
      throw new UnrecoverableProviderException("Unexpected problem during instance allocation", e);
    } finally {
      if (!success) {
        LOG.error("Unsuccessful allocation of on demand instances. Terminating instances.");

        try {
          delete(template, virtualInstanceIds);
        } catch (Exception e) {
          LOG.error("Error while trying to delete instances after failed instance allocation.", e);
        }
      }
    }
  }

  /**
   * <p>Atomically allocates multiple EC2 Spot Instances with the specified identifiers based on a
   * single instance template. If not all the instances can be allocated, the number of instances
   * allocated must be at least the specified minimum or the method must fail cleanly with no
   * billing implications.</p>
   * <p><em>Note:</em> contrary to the contract of the SPI method, there are some cases where
   * despite failing to satisfy the min count there are billing implications, due to non-atomicity
   * of AWS operations. In particular, if we lose connectivity to AWS for an extended period of time
   * after creating Spot instance requests, we will be unable to cancel the requests, and unable to
   * detect that instances have been provisioned. The resulting requests and/or instances may or
   * may not be tagged appropriately, depending on when connectivity was interrupted.</p>
   *
   * @param template           the instance template
   * @param virtualInstanceIds the unique identifiers for the instances
   * @param minCount           the minimum number of instances to allocate if not all resources can
   *                           be allocated
   * @return virtual instance ids that were allocated
   * @throws InterruptedException if the operation is interrupted
   */
  public Collection<String> allocateSpotInstances(EC2InstanceTemplate template,
      Collection<String> virtualInstanceIds, int minCount) throws InterruptedException {

    // TODO add configurable duration
    long startTime = System.currentTimeMillis();
    Date requestExpirationTime =
        new Date(startTime + spotRequestDuration);
    Date priceChangeDeadline =
        new Date(startTime + DEFAULT_SPOT_INSTANCE_PRICE_CHANGE_DURATION_MS);

    SpotGroupAllocator spotGroupAllocator =
        new SpotGroupAllocator(
            template, virtualInstanceIds, minCount, requestExpirationTime, priceChangeDeadline);
    return spotGroupAllocator.allocate();
  }

  /**
   * Returns a map from virtual instance IDs to instance state for the specified batch of virtual
   * instance IDs.
   *
   * @param virtualInstanceIds batch of virtual instance IDs
   * @return the map from instance IDs to instance state for the specified batch of virtual
   * instance IDs
   */
  private Map<String, InstanceState> getBatchInstanceState(Collection<String> virtualInstanceIds) {
    Map<String, InstanceState> instanceStateByVirtualInstanceId =
        Maps.newHashMapWithExpectedSize(virtualInstanceIds.size());

    BiMap<String, String> virtualInstanceIdsByEC2InstanceId =
        getEC2InstanceIdsByVirtualInstanceId(virtualInstanceIds).inverse();

    int page = 0;
    LOG.info(">> Fetching page {}", page);

    if (virtualInstanceIdsByEC2InstanceId.isEmpty()) {
      // No EC2 instances are found, which means these id's are already terminated and gone.
      // In practice, this is possible when no instances were provisioned to begin with.
      // For example, when a deployment fails due to tagging error.
      return instanceStateByVirtualInstanceId;
    }

    DescribeInstanceStatusResult result = client.describeInstanceStatus(
        new DescribeInstanceStatusRequest()
            // Note that sending in an empty set will result in fetching _all_ instance Ids.
            // It requires you to send one or more EC2 Ids
            .withInstanceIds(virtualInstanceIdsByEC2InstanceId.keySet())
            .withIncludeAllInstances(true)
    );
    LOG.info("<< Result: {}", result);

    while (!result.getInstanceStatuses().isEmpty()) {
      for (InstanceStatus status : result.getInstanceStatuses()) {

        InstanceStateName currentState =
            InstanceStateName.fromValue(status.getInstanceState().getName());
        String ec2InstanceId = status.getInstanceId();
        String virtualInstanceId = virtualInstanceIdsByEC2InstanceId.get(ec2InstanceId);
        InstanceState instanceState = EC2InstanceState.fromInstanceStateName(currentState);
        instanceStateByVirtualInstanceId.put(virtualInstanceId, instanceState);
      }

      String nextToken = result.getNextToken();
      if (nextToken != null) {
        page++;
        LOG.info(">> Fetching page {} using token {}", page, nextToken);
        result = client.describeInstanceStatus(new DescribeInstanceStatusRequest()
            .withNextToken(nextToken));
        LOG.info("<< Result: {}", result);
      } else {
        break;
      }
    }

    return instanceStateByVirtualInstanceId;
  }

  /**
   * Waits until the instance has entered a running state.
   *
   * @param ec2InstanceId the EC2 instance id
   * @return true if the instance has entered a running state, false if the instance is shutting down/terminated or
   * the function has timed out waiting for the instance to enter one of these two states.
   */
  private boolean waitUntilInstanceHasStarted(String ec2InstanceId) throws InterruptedException {
    // TODO: Add a timeout to this loop.
    while (true) {
      try {
        DescribeInstanceStatusResult result = client.describeInstanceStatus(
            new DescribeInstanceStatusRequest()
                .withIncludeAllInstances(true)
                .withInstanceIds(ec2InstanceId)
        );
        for (InstanceStatus status : result.getInstanceStatuses()) {
          InstanceStateName currentState =
              InstanceStateName.fromValue(status.getInstanceState().getName());

          if (ec2InstanceId.equals(status.getInstanceId())) {
            if (currentState.equals(InstanceStateName.Terminated) ||
                currentState.equals(InstanceStateName.ShuttingDown)) {
              LOG.error("Instance {} has unexpectedly terminated", ec2InstanceId);
              return false;
            } else if (!currentState.equals(InstanceStateName.Pending)) {
              return true;
            }
          }
        }
      } catch (AmazonServiceException e) {
        if (!INVALID_INSTANCE_ID_NOT_FOUND.equals(e.getErrorCode())) {
          AWSExceptions.propagate(e);
        }
      }
      TimeUnit.SECONDS.sleep(5);
    }
  }

  /**
   * Builds a {@code RunInstancesRequest} starting from a template and a virtual instance ID.
   * Instances will be tagged as they're created.
   *
   * @param template          the instance template
   * @param virtualInstanceId the virtual instance IDs
   * @param userDefinedTags   user defined tags to attach to the instance
   */
  @SuppressWarnings("ConstantConditions")
  private RunInstancesRequest newRunInstancesRequest(EC2InstanceTemplate template,
      String virtualInstanceId, List<Tag> userDefinedTags) {

    String image = template.getImage();
    String type = template.getType();

    InstanceNetworkInterfaceSpecification network =
        getInstanceNetworkInterfaceSpecification(template);

    List<BlockDeviceMapping> deviceMappings = getBlockDeviceMappings(template);

    LOG.info(">> Instance request type: {}, image: {}", type, image);

    List<Tag> tags = ec2TagHelper.getInstanceTags(template, virtualInstanceId, userDefinedTags);
    List<TagSpecification> tagSpecifications = Lists.newArrayList(
        new TagSpecification().withTags(tags).withResourceType(ResourceType.Instance),
        new TagSpecification().withTags(tags).withResourceType(ResourceType.Volume));

    RunInstancesRequest request = new RunInstancesRequest()
        .withImageId(image)
        .withInstanceType(type)
        .withMaxCount(1)
        .withMinCount(1)
        .withClientToken(UUID.randomUUID().toString())
        .withNetworkInterfaces(network)
        .withTagSpecifications(tagSpecifications)
        .withBlockDeviceMappings(deviceMappings)
        .withEbsOptimized(template.isEbsOptimized());

    if (template.getIamProfileName().isPresent()) {
      request.withIamInstanceProfile(new IamInstanceProfileSpecification()
          .withName(template.getIamProfileName().get()));
    }

    if (template.getKeyName().isPresent()) {
      request.withKeyName(template.getKeyName().get());
    }

    Placement placement = null;
    if (template.getAvailabilityZone().isPresent()) {
      placement = new Placement().withAvailabilityZone(template.getAvailabilityZone().get());
    }
    if (template.getPlacementGroup().isPresent()) {
      placement = (placement == null) ?
          new Placement().withGroupName(template.getPlacementGroup().get())
          : placement.withGroupName(template.getPlacementGroup().get());
    }
    placement = (placement == null) ?
        new Placement().withTenancy(template.getTenancy())
        : placement.withTenancy(template.getTenancy());

    request.withPlacement(placement);

    Optional<String> userData = template.getUserData();
    if (userData.isPresent()) {
      request.withUserData(userData.get());
    }

    return request;
  }

  /**
   * Creates an instance network interface specification based on the specified instance template.
   *
   * @param template the instance template
   * @return instance network interface specification
   */
  private InstanceNetworkInterfaceSpecification getInstanceNetworkInterfaceSpecification(
      EC2InstanceTemplate template) {
    InstanceNetworkInterfaceSpecification network = new InstanceNetworkInterfaceSpecification()
        .withDeviceIndex(0)
        .withSubnetId(template.getSubnetId())
        .withGroups(template.getSecurityGroupIds())
        .withDeleteOnTermination(true)
        .withAssociatePublicIpAddress(associatePublicIpAddresses);

    LOG.info(">> Network interface specification: {}", network);
    return network;
  }

  private static final String DEVICE_TYPE_EBS = "ebs";

  /**
   * Creates block device mappings based on the specified instance template.
   *
   * @param template the instance template
   * @return the block device mappings
   */
  private List<BlockDeviceMapping> getBlockDeviceMappings(EC2InstanceTemplate template) {

    // Query the AMI about the root device name & mapping information
    DescribeImagesResult result = client.describeImages(
        new DescribeImagesRequest().withImageIds(template.getImage()));
    if (result.getImages().isEmpty()) {
      throw new IllegalArgumentException("The description for image " + template.getImage() +
          " is empty");
    }
    Image templateImage = result.getImages().get(0);
    String rootDeviceType = templateImage.getRootDeviceType();
    if (!DEVICE_TYPE_EBS.equals(rootDeviceType)) {
      throw new IllegalArgumentException("The root device for image " + template.getImage() +
          " must be \"" + DEVICE_TYPE_EBS + "\", found: " +
          rootDeviceType);
    }
    List<BlockDeviceMapping> originalMappings = templateImage.getBlockDeviceMappings();
    LOG.info(">> Original image block device mappings: {}", originalMappings);
    if (originalMappings.isEmpty()) {
      throw new IllegalArgumentException("The image " + template.getImage() +
          " has no block device mappings");
    }
    BlockDeviceMapping rootDevice = selectRootDevice(originalMappings,
        templateImage.getRootDeviceName());
    if (rootDevice == null) {
      throw new IllegalArgumentException("Could not determine root device for image " +
          template.getImage() + " based on root device name " +
          templateImage.getRootDeviceName());
    }

    // The encrypted property was added to the block device mapping in version 1.8 of the SDK.
    // It is a Boolean, but defaults to false instead of being unset, so we set it to null here.
    rootDevice.getEbs().setEncrypted(null);
    rootDevice.getEbs().setVolumeSize(template.getRootVolumeSizeGB());
    rootDevice.getEbs().setVolumeType(template.getRootVolumeType());
    rootDevice.getEbs().setDeleteOnTermination(true);

    List<BlockDeviceMapping> deviceMappings = Lists.newArrayList(rootDevice);

    int ebsVolumeCount = template.getEbsVolumeCount();

    EBSAllocationStrategy ebsAllocationStrategy = EBSAllocationStrategy.get(template);

    switch (ebsAllocationStrategy) {
      case NO_EBS_VOLUMES:
        // The volumes within an instance should be homogeneous. So we only add
        // instance store volumes when additional EBS volumes aren't mounted.
        deviceMappings.addAll(ephemeralDeviceMappings.apply(template.getType()));
        break;
      case AS_INSTANCE_REQUEST:
        LOG.info("EBS volumes will be allocated as part of instance launch request");
        List<BlockDeviceMapping> ebsDeviceMappings = getEbsBlockDeviceMapping(ebsVolumeCount,
            template.getEbsVolumeType(), template.getEbsVolumeSizeGiB(), template.isEnableEbsEncryption());
        deviceMappings.addAll(ebsDeviceMappings);
        break;
      case AS_SEPARATE_REQUESTS:
        LOG.info("EBS volumes will be separately allocated after instance launch request");
        break;
      default:
        throw new IllegalStateException("Invalid EBS allocation strategy " + ebsAllocationStrategy);
    }

    LOG.info(">> Block device mappings: {}", deviceMappings);
    return deviceMappings;
  }

  private List<BlockDeviceMapping> getEbsBlockDeviceMapping(int count, String volumeType,
      int volumeSizeGib, boolean enableEncryption) {
    List<String> deviceNames = ebsAllocator.getEbsDeviceNames(count);
    List<BlockDeviceMapping> mappings = Lists.newArrayList();

    for (String deviceName : deviceNames) {
      EbsBlockDevice ebs = new EbsBlockDevice()
          .withVolumeType(volumeType)
          .withVolumeSize(volumeSizeGib)
          .withEncrypted(enableEncryption)
          .withDeleteOnTermination(true);

      BlockDeviceMapping mapping = new BlockDeviceMapping()
          .withDeviceName(deviceName)
          .withEbs(ebs);

      mappings.add(mapping);
    }
    return mappings;
  }

  /**
   * Selects the root device from a list of block device mappings based on the
   * root device name for the mappings' image.
   *
   * @param mappings       list of block device mappings
   * @param rootDeviceName image root device name
   * @return root device mapping, or null if it could not be determined
   */
  @VisibleForTesting
  static BlockDeviceMapping selectRootDevice(List<BlockDeviceMapping> mappings,
      String rootDeviceName) {
    /*
     * Heuristic to find the root device:
     * - The best match is the EBS device that matches the root device name for the image, but
     *   this may not happen (/dev/sda1 vs. /dev/sda). See:
     *   http://docs.aws.amazon.com/AWSEC2/latest/UserGuide/device_naming.html
     * - If there isn't a best match, then a device whose name is a prefix for the image's root
     *   device name is selected.
     * - If all else fails, it's the first EBS volume in the list.
     */
    BlockDeviceMapping bestMatch = null;
    BlockDeviceMapping firstEbs = null;
    for (BlockDeviceMapping mapping : mappings) {
      if (mapping.getEbs() == null) {
        continue;
      }
      if (firstEbs == null) {
        firstEbs = mapping;
      }
      if (mapping.getDeviceName() == null) {
        continue;
      }
      if (rootDeviceName.equals(mapping.getDeviceName())) {
        return mapping;
      }
      if (rootDeviceName.startsWith(mapping.getDeviceName())) {
        bestMatch = mapping;
      }
    }

    if (bestMatch != null) {
      return bestMatch;
    } else if (firstEbs != null) {
      return firstEbs;
    } else {
      return null;
    }

  }

  /**
   * Returns a summary of the specified reservation suitable for logging.
   *
   * @param reservation the reservation
   * @return a summary of the specified reservation suitable for logging
   */
  private String summarizeReservationForLogging(Reservation reservation) {
    StringBuilder builder = new StringBuilder();
    for (Instance instance : reservation.getInstances()) {
      builder.append(String.format("Instance{id=%s privateIp=%s} ",
          instance.getInstanceId(), instance.getPrivateIpAddress()));
    }
    return builder.toString();
  }

  /**
   * <p>Zip two collections as a lazy iterable of pairs.</p>
   * <p><em>Note:</em> the returned iterable is not suitable for repeated use, since it
   * exhausts the iterator over the first collection.</p>
   *
   * @throws IllegalArgumentException if input collections don't have the same size
   */
  private <K, V> Iterable<Map.Entry<K, V>> zipWith(Collection<K> a, Collection<V> b) {
    checkArgument(a.size() == b.size(), "collections don't have the same size");

    final Iterator<K> iterator = a.iterator();
    return Iterables.transform(b, new Function<V, Map.Entry<K, V>>() {
      @Override
      public Map.Entry<K, V> apply(V input) {
        return Maps.immutableEntry(iterator.next(), input);
      }
    });
  }

  /**
   * Waits until all of the specified instances have assigned private IP addresses.
   *
   * @param instanceIds the instance IDs
   * @return instance IDs of instances that were terminated while waiting for IP addresses
   * @throws InterruptedException if the operation is interrupted
   */
  private Collection<String> waitForPrivateIpAddresses(final Collection<String> instanceIds)
      throws InterruptedException {

    final Set<String> instancesWithNoPrivateIpAddress = Sets.newHashSet(instanceIds);
    final Set<String> terminatedInstances = Sets.newHashSet();

    while (!instancesWithNoPrivateIpAddress.isEmpty()) {
      LOG.info(">> Waiting for {} instance(s) to get a private IP allocated",
          instancesWithNoPrivateIpAddress.size());

      DescribeInstancesResult result = client.describeInstances(
          new DescribeInstancesRequest().withInstanceIds(instancesWithNoPrivateIpAddress));
      forEachInstance(result, new InstanceHandler() {
        @Override
        public void handle(Instance instance) {
          InstanceStateName currentState =
              InstanceStateName.fromValue(instance.getState().getName());

          String ec2InstanceId = instance.getInstanceId();
          if (currentState.equals(InstanceStateName.Terminated) ||
              currentState.equals(InstanceStateName.ShuttingDown)) {
            LOG.info("<< Instance {} has terminated unexpectedly, skipping IP address wait.", ec2InstanceId);
            instancesWithNoPrivateIpAddress.remove(ec2InstanceId);
            terminatedInstances.add(ec2InstanceId);
          } else if (instance.getPrivateIpAddress() != null) {

            LOG.info("<< Instance {} got IP {}", ec2InstanceId, instance.getPrivateIpAddress());

            instancesWithNoPrivateIpAddress.remove(ec2InstanceId);
          }
        }
      });

      if (!instancesWithNoPrivateIpAddress.isEmpty()) {
        LOG.info("Waiting 5 seconds until next check, {} instance(s) still don't have an IP",
            instancesWithNoPrivateIpAddress.size());

        TimeUnit.SECONDS.sleep(5);
      }
    }

    return terminatedInstances;
  }

  /**
   * Performs a sequence of strict instance ownership checks to avoid any potential harmful
   * accidents.
   *
   * @param instance the instance
   * @param template the template from which the instance was created, or <code>null</code>
   *                 if it is unknown (such as during a delete call)
   * @return the virtual instance ID
   * @throws IllegalStateException if the instance fails an ownership check
   */
  private String checkInstanceIsManagedByDirector(Instance instance, EC2InstanceTemplate template) {
    String virtualInstanceId = getVirtualInstanceId(instance.getTags(), "instance");
    String instanceIds = instance.getInstanceId() + " / " + virtualInstanceId;
    String instanceKeyName = instance.getKeyName();

    if (template != null) {
      if (!template.getKeyName().equals(Optional.fromNullable(instanceKeyName))) {
        LOG.warn("Found unexpected key name: {} for instance: {}", instanceKeyName, instanceIds);
      }
      String instanceType = instance.getInstanceType();
      if (!template.getType().equals(instanceType)) {
        LOG.warn("Found unexpected type: {} for instance: {}", instanceType, instanceIds);
      }
      String instanceImageId = instance.getImageId();
      if (!template.getImage().equals(instanceImageId)) {
        LOG.warn("Found unexpected image: {} for instance: {}", instanceImageId, instanceIds);
      }
    }
    return virtualInstanceId;
  }

  /**
   * Determines the virtual instance ID from the specified list of tags.
   *
   * @param tags the tags
   * @param type the type of tagged object
   * @return the virtual instance ID
   * @throws IllegalStateException if the tags do not contain the virtual instance ID
   */
  private String getVirtualInstanceId(List<Tag> tags, String type) {
    String idTagName = ec2TagHelper.getClouderaDirectorIdTagName();
    for (Tag tag : tags) {
      if (tag.getKey().equals(idTagName)) {
        return tag.getValue();
      }
    }

    throw new IllegalStateException(String.format("Any %s managed by " +
        "Cloudera Director should have a %s tag.", type, idTagName));
  }

  /**
   * Returns a map from virtual instance ID to corresponding instance ID for the specified
   * virtual instance IDs.
   *
   * @param virtualInstanceIds the virtual instance IDs
   * @return the map from virtual instance ID to corresponding EC2 instance ID
   */
  private BiMap<String, String> getEC2InstanceIdsByVirtualInstanceId(
      Collection<String> virtualInstanceIds) {
    final BiMap<String, String> ec2InstanceIdsByVirtualInstanceId = HashBiMap.create();
    forEachInstance(virtualInstanceIds, new InstanceHandler() {
      @Override
      public void handle(Instance instance) {
        String virtualInstanceId = checkInstanceIsManagedByDirector(instance, null);
        ec2InstanceIdsByVirtualInstanceId.put(virtualInstanceId, instance.getInstanceId());
      }
    });
    return ec2InstanceIdsByVirtualInstanceId;
  }

  /**
   * Represents a callback that can be applied to each instance of
   * a {@code DescribeInstancesResult}.
   */
  @VisibleForTesting
  interface InstanceHandler {

    /**
     * Handles the specified instance.
     *
     * @param instance the instance
     */
    void handle(Instance instance);
  }

  /**
   * Iterates through the instances identified by the specified virtual instance IDs
   * and calls the specified handler on each instance. Instances with the same virtual
   * instance IDs are deduplicated, preferring running instances over terminating or
   * terminated instances.
   *
   * @param virtualInstanceIds the virtual instance IDs
   * @param instanceHandler    the instance handler
   */
  @VisibleForTesting
  void forEachInstance(Collection<String> virtualInstanceIds,
      EC2Provider.InstanceHandler instanceHandler) {
    List<DescribeInstancesResult> results = Lists.newArrayList();
    String idTagName = ec2TagHelper.getClouderaDirectorIdTagName();
    for (List<String> virtualInstanceIdChunk : Iterables.partition(virtualInstanceIds, MAX_TAG_FILTERING_REQUESTS)) {
      DescribeInstancesResult result = client.describeInstances(new DescribeInstancesRequest()
          .withFilters(new Filter().withName("tag:" + idTagName)
              .withValues(virtualInstanceIdChunk)));
      results.add(result);
    }

    // collect Instances, preferring running instances over terminated instances and
    // also ensuring we only process one instance per virtual instance id
    final Map<String, Instance> virtualInstanceIdToInstance =
        Maps.newHashMapWithExpectedSize(virtualInstanceIds.size());
    for (DescribeInstancesResult result : results) {
      forEachInstance(result, new InstanceHandler() {
        @Override
        public void handle(Instance instance) {
          String virtualInstanceId;
          try {
            virtualInstanceId = checkInstanceIsManagedByDirector(instance, null);
          } catch (IllegalStateException e) {
            LOG.error("Instance {} is not managed by Director. Skipping", instance.getInstanceId());
            return;
          }
          Instance oldInstance = virtualInstanceIdToInstance.get(virtualInstanceId);
          if (oldInstance == null || INSTANCE_IS_TERMINAL.apply(oldInstance)) {
            virtualInstanceIdToInstance.put(virtualInstanceId, instance);
            if (oldInstance != null) {
              LOG.warn("Retaining new instance {} in preference to terminal instance {}",
                  instance, oldInstance);
            }
          } else {
            if (!INSTANCE_IS_TERMINAL.apply(instance)) {
              LOG.error("Two non-terminal instances with virtual instance id {} exist: {} and {}",
                  virtualInstanceId, oldInstance, instance);
            } else {
              LOG.warn("Retaining current non-terminal instance {} in preference to terminal instance {}",
                  oldInstance, instance);
            }
          }
        }
      });
    }

    for (Instance instance : virtualInstanceIdToInstance.values()) {
      instanceHandler.handle(instance);
    }
  }

  /**
   * Iterates through the instances in the specified {@code DescribeInstancesResult}
   * and calls the specified handler on each instance. This method will retrieve the
   * follow-on {@code DescribeInstanceResult}s if the result holds a {@code nextToken}.
   *
   * @param result          the {@code DescribeInstancesResult}
   * @param instanceHandler the instance handler
   */
  @VisibleForTesting
  void forEachInstance(DescribeInstancesResult result, InstanceHandler instanceHandler) {
    List<Reservation> reservations;
    while (!(reservations = result.getReservations()).isEmpty()) {
      for (Reservation reservation : reservations) {
        for (Instance instance : reservation.getInstances()) {
          LOG.debug("Calling instance handler with instance {}", instance);
          instanceHandler.handle(instance);
        }
      }

      if (result.getNextToken() != null) {
        result = client.describeInstances(
            new DescribeInstancesRequest().withNextToken(result.getNextToken()));
      } else {
        break;
      }
    }
  }

  /**
   * Validate tags input.
   * A null tags map is allowed.
   * Number of entries should not exceed {@link #MAX_TAGS_ALLOWED}.
   *
   * @param tags given map of tags
   */
  private void validateTags(Map<String, String> tags) {
    if (tags != null && tags.size() > MAX_TAGS_ALLOWED) {
      throw new UnrecoverableProviderException("Number of tags exceeds the maximum of " +
          MAX_TAGS_ALLOWED);
    }
  }

  /**
   * Holds details about the allocation state of a single virtual instance.
   */
  @VisibleForTesting
  protected static class SpotAllocationRecord {

    /**
     * The virtual instance ID.
     */
    @VisibleForTesting
    protected final String virtualInstanceId;

    /**
     * The Spot instance request ID, or {@code null} if a Spot instance has not been requested.
     */
    @VisibleForTesting
    protected String spotInstanceRequestId;

    /**
     * The EC2 instance ID, or {@code null} if an instance has not been provisioned.
     */
    @VisibleForTesting
    protected String ec2InstanceId;

    /**
     * Whether the EC2 instance has been tagged.
     */
    @VisibleForTesting
    protected boolean instanceTagged;

    /**
     * The private IP address of the EC2 instance, or {@code null} if the instance does not yet
     * have a private IP address.
     */
    @VisibleForTesting
    protected InetAddress privateIpAddress;

    /**
     * Creates a Spot allocation record with the specified parameters.
     *
     * @param virtualInstanceId the virtual instance ID
     */
    @VisibleForTesting
    protected SpotAllocationRecord(String virtualInstanceId) {
      this.virtualInstanceId = virtualInstanceId;
    }
  }

  /**
   * Holds state and logic for allocating a group of Spot instances. A new instance is
   * required for each allocation request.
   */
  @VisibleForTesting
  protected class SpotGroupAllocator {

    /**
     * The instance template.
     */
    @VisibleForTesting
    protected final EC2InstanceTemplate template;

    /**
     * The virtual instance IDs.
     */
    @VisibleForTesting
    protected final Collection<String> virtualInstanceIds;

    /**
     * The minimum number of instances to allocate if not all resources can be allocated.
     */
    @VisibleForTesting
    protected final int minCount;

    /**
     * The latest time to wait for Spot instance request fulfillment.
     */
    @VisibleForTesting
    protected final Date requestExpirationTime;

    /**
     * The latest time to wait for a Spot price change when it is known that the current Spot price
     * exceeds the Spot bid.
     */
    @VisibleForTesting
    protected final Date priceChangeDeadlineTime;

    /**
     * The map from virtual instance IDs to the corresponding Spot allocation records.
     */
    @VisibleForTesting
    protected final Map<String, SpotAllocationRecord> spotAllocationRecordsByVirtualInstanceId;

    /**
     * The map from untagged Spot instance request IDs to the corresponding Spot instances.
     */
    @VisibleForTesting
    protected final Map<String, String> spotInstancesByUntaggedSpotInstanceRequestId;

    /**
     * The number of allocated instances (including reused orphans).
     */
    @VisibleForTesting
    protected int allocatedInstanceCount = 0;

    /**
     * The plugin exception condition accumulator.
     */
    private PluginExceptionConditionAccumulator accumulator;

    /**
     * Creates a Spot group allocator with the specified parameters.
     *
     * @param template              the instance template
     * @param virtualInstanceIds    the virtual instance IDs
     * @param minCount              the minimum number of instances to allocate if not all resources can
     *                              be allocated
     * @param requestExpirationTime the latest time to wait for Spot instance request fulfillment
     * @param priceChangeDeadline   the latest time to wait for a Spot price change when it is known
     *                              that the current Spot price exceeds the Spot bid
     */
    @VisibleForTesting
    protected SpotGroupAllocator(EC2InstanceTemplate template,
        Collection<String> virtualInstanceIds, int minCount, Date requestExpirationTime,
        Date priceChangeDeadline) {
      this.template = template;
      this.virtualInstanceIds = virtualInstanceIds;
      this.minCount = minCount;
      this.requestExpirationTime = requestExpirationTime;
      this.priceChangeDeadlineTime = priceChangeDeadline;
      this.spotAllocationRecordsByVirtualInstanceId =
          initializeSpotAllocationRecordMap(virtualInstanceIds);
      this.spotInstancesByUntaggedSpotInstanceRequestId = Maps.newHashMap();
    }

    /**
     * Initializes the map from virtual instance IDs to the corresponding Spot allocation records.
     *
     * @param virtualInstanceIds the virtual instance IDs
     * @return the map from virtual instance IDs to the corresponding Spot allocation records
     */
    @VisibleForTesting
    protected Map<String, SpotAllocationRecord> initializeSpotAllocationRecordMap(
        Collection<String> virtualInstanceIds) {
      Map<String, SpotAllocationRecord> spotAllocationRecordsByVirtualInstanceId =
          Maps.newLinkedHashMap();
      for (String virtualInstanceId : virtualInstanceIds) {
        SpotAllocationRecord spotAllocationRecord = new SpotAllocationRecord(virtualInstanceId);
        spotAllocationRecordsByVirtualInstanceId.put(virtualInstanceId, spotAllocationRecord);
      }
      return spotAllocationRecordsByVirtualInstanceId;
    }

    /**
     * Returns the Spot allocation record corresponding to the specified virtual instance ID.
     *
     * @param virtualInstanceId the virtual instance ID
     * @return the Spot allocation record corresponding to the specified virtual instance ID
     */
    @VisibleForTesting
    protected SpotAllocationRecord getSpotAllocationRecord(String virtualInstanceId) {
      return spotAllocationRecordsByVirtualInstanceId.get(virtualInstanceId);
    }

    /**
     * Allocates Spot instances.
     *
     * @throws InterruptedException if the operation is interrupted
     */
    @VisibleForTesting
    protected Collection<String> allocate() throws InterruptedException {

      int expectedInstanceCount = virtualInstanceIds.size();

      LOG.info(">> Requesting {} Spot instances for {}", expectedInstanceCount, template);

      boolean success = false;

      PluginExceptionConditionAccumulator accumulator = new PluginExceptionConditionAccumulator();

      try {
        try {
          // Check for existing instances orphaned by a previous call.
          checkForOrphanedInstances();

          // Check for existing Spot instance requests orphaned by a previous call.
          Set<String> orphanedSpotInstanceRequests = checkForOrphanedSpotInstanceRequests();
          Set<String> pendingRequestIds = Sets.newHashSet(orphanedSpotInstanceRequests);

          // Need to do a Spot instance request for any virtual instance ids not already associated
          // with an orphaned instance or Spot instance request. In the normal use case, this will
          // include all the requested virtual instance ids.
          Set<String> virtualInstanceIdsNeedingSpotInstanceRequest =
              determineVirtualInstanceIdsNeedingSpotInstanceRequest();

          if (!virtualInstanceIdsNeedingSpotInstanceRequest.isEmpty()) {

            // Request Spot instances
            Set<String> newRequestIds = requestSpotInstances(virtualInstanceIds, accumulator);

            // If we didn't get a request ID for every virtual instance ID, truncate the virtual instance ID set
            if (newRequestIds.size() < virtualInstanceIdsNeedingSpotInstanceRequest.size()) {
              virtualInstanceIdsNeedingSpotInstanceRequest = Sets.newHashSet(
                  Iterables.limit(virtualInstanceIdsNeedingSpotInstanceRequest, newRequestIds.size()));
            }

            // Tag Spot instance requests with virtual instance IDs
            tagSpotInstanceRequests(newRequestIds, virtualInstanceIdsNeedingSpotInstanceRequest);

            // Combine the request ids of the reused orphaned requests and the new requests.
            pendingRequestIds.addAll(newRequestIds);

          }

          // Wait for Spot requests to be processed
          waitForSpotInstances(pendingRequestIds, false);

          // Tag all the new instances so that we can easily find them later on.
          tagSpotInstances();

          if (allocatedInstanceCount < minCount) {
            LOG.info(">> Failed to acquire required number of Spot Instances "
                    + "(desired {}, required {}, acquired {})", expectedInstanceCount, minCount,
                allocatedInstanceCount);
          } else {

            // Wait until all of them have a private IP (it should be pretty fast)
            Collection<String> terminatedInstanceIds = waitForPrivateIpAddresses();

            // Remove any instances that have been terminated from our internal record
            for (String terminatedInstanceId : terminatedInstanceIds) {
              spotAllocationRecordsByVirtualInstanceId.remove(terminatedInstanceId);
            }

            // Wait until we can "find" all the allocated virtual instance ids
            // This will mitigate, but not remove, the possibility that eventual consistency
            // will cause us to not find the instances we just allocated.
            Collection<String> allocatedVirtualInstances = getVirtualInstanceIdsAllocated();
            int numAllocatedInstances = allocatedVirtualInstances.size();
            Collection<EC2Instance> foundInstances = find(template, allocatedVirtualInstances);
            int numFoundInstances = foundInstances.size();
            Stopwatch stopwatch = Stopwatch.createStarted();
            while (numFoundInstances != numAllocatedInstances &&
                stopwatch.elapsed(TimeUnit.MILLISECONDS) < waitUntilFindableMillis) {
              LOG.info("Found {} Spot instances while expecting {}. Waiting for all Spot " +
                      "instances to be findable",
                  numFoundInstances, numAllocatedInstances);
              TimeUnit.SECONDS.sleep(5);
              foundInstances = find(template, allocatedVirtualInstances);
              numFoundInstances = foundInstances.size();
            }
            if (numFoundInstances == numAllocatedInstances) {
              LOG.info("Found all {} allocated Spot instances.", numAllocatedInstances);
            } else {
              LOG.warn("Found only {} of {} Spot instances before wait timeout of {} ms. " +
                      "Continuing anyway.",
                  numFoundInstances, numAllocatedInstances, waitUntilFindableMillis);
              LOG.debug("Expecting {}. Found {}.", allocatedVirtualInstances, foundInstances);
            }

            success = true;
            return allocatedVirtualInstances;
          }
        } finally {
          try {
            cancelSpotRequests(accumulator);
          } finally {
            if (!success) {
              terminateSpotInstances(accumulator);
            }
          }
        }
      } catch (AmazonClientException e) {
        // Log here so we get a full stack trace.
        LOG.error("Problem allocating Spot instances", e);
        throw AWSExceptions.propagate(e);
      } catch (InterruptedException e) {
        throw e;
      } catch (Exception e) {
        // Log here so we get a full stack trace.
        LOG.error("Problem allocating Spot instances", e);
        accumulator.addError(null, getErrorMessage(e));
      }

      if (accumulator.hasError()) {
        PluginExceptionDetails pluginExceptionDetails =
            new PluginExceptionDetails(accumulator.getConditionsByKey());
        throw new UnrecoverableProviderException("Problem allocating Spot instances.",
            pluginExceptionDetails);
      }

      return Collections.emptyList();
    }

    /**
     * <p>Identifies reusable Spot instances orphaned by a previous call.</p>
     * <p><em>Note:</em> because of AWS's eventual consistency policies, we are not guaranteed
     * to be able to detect all orphans here, but we make a best-faith effort.</p>
     *
     * @throws InterruptedException if operation is interrupted
     */
    @VisibleForTesting
    protected void checkForOrphanedInstances()
        throws InterruptedException {

      LOG.info(">> Checking for orphaned Spot instances");
      for (EC2Instance ec2Instance : find(template, virtualInstanceIds)) {
        String ec2InstanceId = ec2Instance.unwrap().getInstanceId();
        String virtualInstanceId = ec2Instance.getId();
        LOG.info(">> Found orphaned instance {} / {}; will reuse", ec2InstanceId, virtualInstanceId);
        SpotAllocationRecord spotAllocationRecord = getSpotAllocationRecord(virtualInstanceId);
        spotAllocationRecord.ec2InstanceId = ec2InstanceId;
        spotAllocationRecord.instanceTagged = true;
        spotAllocationRecord.privateIpAddress = ec2Instance.getPrivateIpAddress();
        allocatedInstanceCount++;
      }
    }

    /**
     * Identifies reusable Spot instance requests orphaned by a previous call.
     *
     * @return the reusable Spot instance requests orphaned by a previous call
     */
    @VisibleForTesting
    protected Set<String> checkForOrphanedSpotInstanceRequests() {

      Set<String> orphanedSpotInstanceRequests = Sets.newHashSet();

      LOG.info(">> Checking for orphaned Spot instance requests");
      String idTagName = ec2TagHelper.getClouderaDirectorIdTagName();
      DescribeSpotInstanceRequestsRequest describeSpotInstanceRequestsRequest =
          new DescribeSpotInstanceRequestsRequest().withFilters(
              new Filter()
                  .withName("tag:" + idTagName)
                  .withValues(virtualInstanceIds));
      DescribeSpotInstanceRequestsResult describeSpotInstanceRequestsResult =
          client.describeSpotInstanceRequests(describeSpotInstanceRequestsRequest);
      for (SpotInstanceRequest existingSpotInstanceRequest :
          describeSpotInstanceRequestsResult.getSpotInstanceRequests()) {
        String spotInstanceRequestId = existingSpotInstanceRequest.getSpotInstanceRequestId();
        String virtualInstanceId = null;
        for (Tag tag : existingSpotInstanceRequest.getTags()) {
          if (idTagName.equals(tag.getKey())) {
            virtualInstanceId = tag.getValue();
          }
        }
        if (virtualInstanceId == null) {
          LOG.warn(">> Orphaned Spot instance request {} has no virtual instance id",
              spotInstanceRequestId);
        } else {
          SpotAllocationRecord spotAllocationRecord = getSpotAllocationRecord(virtualInstanceId);
          SpotInstanceState spotInstanceState =
              SpotInstanceState.fromValue(existingSpotInstanceRequest.getState());
          switch (spotInstanceState) {
            case Active:
              spotAllocationRecord.spotInstanceRequestId = spotInstanceRequestId;
              String ec2InstanceId = existingSpotInstanceRequest.getInstanceId();
              LOG.info(">> Reusing fulfilled orphaned Spot instance request {} / {} / {}",
                  spotInstanceRequestId, virtualInstanceId, ec2InstanceId);
              if (spotAllocationRecord.ec2InstanceId == null) {
                spotAllocationRecord.ec2InstanceId = ec2InstanceId;
              }
              break;
            case Cancelled:
            case Closed:
            case Failed:
              break;
            default:
              if (existingSpotInstanceRequest.getValidUntil().getTime() > System.currentTimeMillis()) {
                LOG.info(">> Reusing pending orphaned Spot instance request {} / {}",
                    spotInstanceRequestId, virtualInstanceId);
                spotAllocationRecord.spotInstanceRequestId = spotInstanceRequestId;
              }
              break;
          }
        }
      }

      return orphanedSpotInstanceRequests;
    }

    /**
     * Determines which virtual instance IDs require a Spot instance request.
     *
     * @return the virtual instance IDs which require a Spot instance request
     */
    @VisibleForTesting
    @SuppressWarnings("PMD.UselessParentheses")
    protected Set<String> determineVirtualInstanceIdsNeedingSpotInstanceRequest() {

      Set<String> result = Sets.newHashSet();

      LOG.info(">> Determining which virtual instances require Spot instance requests");
      for (Map.Entry<String, SpotAllocationRecord> entry
          : spotAllocationRecordsByVirtualInstanceId.entrySet()) {
        SpotAllocationRecord spotAllocationRecord = entry.getValue();
        if ((spotAllocationRecord.ec2InstanceId == null)
            && (spotAllocationRecord.spotInstanceRequestId == null)) {
          result.add(entry.getKey());
        }
      }

      return result;
    }

    /**
     * Builds a {@code RequestSpotInstancesRequest}.
     *
     * @return the {@code RequestSpotInstancesRequest}
     */
    @VisibleForTesting
    protected RequestSpotInstancesRequest newRequestSpotInstanceRequest(String virtualInstanceId) {

      String image = template.getImage();
      String type = template.getType();

      InstanceNetworkInterfaceSpecification network =
          getInstanceNetworkInterfaceSpecification(template);

      List<BlockDeviceMapping> deviceMappings = getBlockDeviceMappings(template);

      LaunchSpecification launchSpecification = new LaunchSpecification()
          .withImageId(image)
          .withInstanceType(type)
          .withNetworkInterfaces(network)
          .withBlockDeviceMappings(deviceMappings)
          .withEbsOptimized(template.isEbsOptimized());

      if (template.getIamProfileName().isPresent()) {
        launchSpecification.withIamInstanceProfile(new IamInstanceProfileSpecification()
            .withName(template.getIamProfileName().get()));
      }

      if (template.getKeyName().isPresent()) {
        launchSpecification.withKeyName(template.getKeyName().get());
      }

      SpotPlacement placement = null;
      if (template.getAvailabilityZone().isPresent()) {
        placement = new SpotPlacement().withAvailabilityZone(template.getAvailabilityZone().get());
      }
      if (template.getPlacementGroup().isPresent()) {
        placement = (placement == null) ?
            new SpotPlacement().withGroupName(template.getPlacementGroup().get())
            : placement.withGroupName(template.getPlacementGroup().get());
      }
      launchSpecification.withPlacement(placement);

      Optional<String> userData = template.getUserData();
      if (userData.isPresent()) {
        launchSpecification.withUserData(userData.get());
      }

      LOG.info(">> Spot instance request type: {}, image: {}", type, image);

      RequestSpotInstancesRequest request = new RequestSpotInstancesRequest()
          .withSpotPrice(template.getSpotBidUSDPerHour().get().toString())
          .withLaunchSpecification(launchSpecification)
          .withInstanceCount(1)
          .withClientToken(determineClientToken(virtualInstanceId, requestExpirationTime.getTime()))
          .withValidUntil(requestExpirationTime);

      Optional<Integer> blockDurationMinutes = template.getBlockDurationMinutes();
      if (blockDurationMinutes.isPresent()) {
        request.withBlockDurationMinutes(blockDurationMinutes.get());
      }

      return request;
    }

    /**
     * Requests Spot instances, and returns the resulting Spot instance request IDs.
     *
     * @param virtualInstanceIds the virtual instance IDs to request spot instances for
     * @param accumulator plugin exception condition accumulator
     * @return the resulting Spot instance request IDs
     */
    @VisibleForTesting
    protected Set<String> requestSpotInstances(Collection<String> virtualInstanceIds,
        PluginExceptionConditionAccumulator accumulator) throws InterruptedException {
      LOG.info(">> Requesting Spot instances");

      Set<String> newRequestIds = Sets.newHashSet();

      boolean maxSpotIssues = false;
      boolean capacityIssues = false;
      boolean rateLimitIssues = false;

      List<Future<RequestSpotInstancesResult>> spotResults = Lists.newArrayList();
      for (String virtualInstanceId : virtualInstanceIds) {
        spotResults.add(client.requestSpotInstancesAsync(newRequestSpotInstanceRequest(virtualInstanceId)));
      }

      int lostInstances = 0;
      for (Future<RequestSpotInstancesResult> spotResult : spotResults) {
        try {
          RequestSpotInstancesResult requestSpotInstancesResult = spotResult.get();
          SpotInstanceRequest requestResponse = getOnlyElement(requestSpotInstancesResult.getSpotInstanceRequests());
          String requestId = requestResponse.getSpotInstanceRequestId();
          LOG.info(">> Created Spot Request {}", requestId);
          newRequestIds.add(requestId);
        } catch (ExecutionException e) {
          lostInstances++;
          if (e.getCause() instanceof AmazonServiceException) {
            AmazonServiceException awsException = (AmazonServiceException) e.getCause();
            AWSExceptions.propagateIfUnrecoverable(awsException);

            if (MAX_SPOT_INSTANCE_COUNT_EXCEEDED.equals(awsException.getErrorCode())) {
              maxSpotIssues = true;
            }

            if (INSUFFICIENT_INSTANCE_CAPACITY.equals(awsException.getErrorCode()) ||
                INSTANCE_LIMIT_EXCEEDED.equals(awsException.getErrorCode())) {
              capacityIssues = true;
            }

            if (REQUEST_LIMIT_EXCEEDED.equals(awsException.getErrorCode())) {
              rateLimitIssues = true;
            }
          } else {
            LOG.error("Error while requesting spot instance. Attempting to proceed.");
            LOG.debug("Exception caught:", e);
          }
        }
      }

      if (maxSpotIssues) {
        String message = "Some spot instances were not allocated due to reaching the max spot instance request limit.";
        accumulator.addWarning(null, message);
        LOG.warn(message);
      }

      if (capacityIssues) {
        String message = "Some instances were not allocated due to instance limits or capacity issues.";
        accumulator.addWarning(null, message);
        LOG.warn(message);
      }

      if (rateLimitIssues) {
        String message = "Encountered rate limit errors while allocating instances.";
        accumulator.addWarning(null, message);
        LOG.warn(message);
      }

      if (lostInstances > 0) {
        LOG.warn("Lost {} spot requests.", lostInstances);
      }

      return newRequestIds;
    }

    /**
     * Tags the Spot instance requests with the specified IDs with the corresponding virtual
     * instance IDs.
     *
     * @param spotInstanceRequestIds the Spot instance request IDs
     * @param virtualInstanceIds     the corresponding virtual instance IDs
     * @throws InterruptedException if the operation is interrupted
     */
    @VisibleForTesting
    protected void tagSpotInstanceRequests(Collection<String> spotInstanceRequestIds,
        Collection<String> virtualInstanceIds) throws InterruptedException {
      // Pre-compute user-defined tags for efficiency
      List<Tag> userDefinedTags = ec2TagHelper.getUserDefinedTags(template);
      for (Map.Entry<String, String> entry : zipWith(spotInstanceRequestIds, virtualInstanceIds)) {
        String virtualInstanceId = entry.getValue();
        String spotInstanceRequestId = entry.getKey();
        tagSpotInstanceRequest(userDefinedTags, spotInstanceRequestId, virtualInstanceId);
        SpotAllocationRecord spotAllocationRecord = getSpotAllocationRecord(virtualInstanceId);
        spotAllocationRecord.spotInstanceRequestId = spotInstanceRequestId;
      }
    }

    /**
     * Tags an EC2 Spot instance request.
     *
     * @param userDefinedTags       the user-defined tags
     * @param spotInstanceRequestId the Spot instance request ID
     * @param virtualInstanceId     the virtual instance ID
     * @throws InterruptedException if the operation is interrupted
     */
    @VisibleForTesting
    @SuppressWarnings("PMD.UselessParentheses")
    protected void tagSpotInstanceRequest(List<Tag> userDefinedTags, String spotInstanceRequestId,
                                          String virtualInstanceId)
        throws InterruptedException {
      LOG.info(">> Tagging Spot instance request {} / {}", spotInstanceRequestId, virtualInstanceId);
      List<Tag> tags = Lists.newArrayList(
          ec2TagHelper.createClouderaDirectorIdTag(virtualInstanceId),
          ec2TagHelper.createClouderaDirectorTemplateNameTag(template.getName()));
      tags.addAll(userDefinedTags);

      // Wait for the request to become visible
      while (!spotInstanceRequestExists(spotInstanceRequestId)) {
        TimeUnit.SECONDS.sleep(5);
      }

      // Test failures and google indicate that we can fail to find a request to tag even when we
      // have determined that it exists by describing it. I am adding a retry loop to attempt to
      // avoid this case.
      boolean tagged = false;
      while (!tagged && (System.currentTimeMillis() < requestExpirationTime.getTime())) {
        try {
          client.createTags(
              new CreateTagsRequest().withTags(tags).withResources(spotInstanceRequestId));
          tagged = true;
        } catch (AmazonServiceException e) {
          String errorCode = e.getErrorCode();
          if ("InvalidSpotInstanceRequestID.NotFound".equals(errorCode)) {
            LOG.info(">> Waiting, requestId {}, transient error {}...", spotInstanceRequestId, errorCode);
            TimeUnit.SECONDS.sleep(5);
          } else {
            throw AWSExceptions.propagate(e);
          }
        }
      }
    }

    /**
     * Checks whether the specified Spot instance request is visible, <em>i.e.</em> can
     * be located by a describe Spot instance request call.
     *
     * @param spotInstanceRequestId the Spot instance request ID
     * @return whether the Spot instance request is visible, <em>i.e.</em> can
     * be located by a describe Spot instance request call
     */
    @VisibleForTesting
    protected boolean spotInstanceRequestExists(String spotInstanceRequestId) {
      DescribeSpotInstanceRequestsResult result;
      try {
        result = client.describeSpotInstanceRequests(
            new DescribeSpotInstanceRequestsRequest()
                .withSpotInstanceRequestIds(spotInstanceRequestId)
        );
      } catch (AmazonServiceException e) {
        if ("InvalidSpotInstanceRequestID.NotFound".equals(e.getErrorCode())) {
          return false;
        }
        throw AWSExceptions.propagate(e);
      }

      for (SpotInstanceRequest spotInstanceRequest : result.getSpotInstanceRequests()) {
        if (spotInstanceRequestId.equals(spotInstanceRequest.getSpotInstanceRequestId())) {
          return true;
        }
      }
      return false;
    }

    /**
     * Waits for pending Spot instance requests to be fulfilled.
     *
     * @param pendingRequestIds the pending Spot instance request IDs
     * @param cancelling        whether we are in the process of cancelling
     * @throws InterruptedException if the operation is interrupted
     */
    @VisibleForTesting
    @SuppressWarnings("PMD.EmptyCatchBlock")
    protected void waitForSpotInstances(Set<String> pendingRequestIds, boolean cancelling)
        throws InterruptedException {

      while (!pendingRequestIds.isEmpty()) {
        // Create the describeRequest object with all of the request ids
        // to monitor (e.g. that we started).
        DescribeSpotInstanceRequestsRequest describeRequest =
            new DescribeSpotInstanceRequestsRequest();
        describeRequest.setSpotInstanceRequestIds(pendingRequestIds);

        // Retrieve all of the requests we want to monitor.
        DescribeSpotInstanceRequestsResult describeResult =
            client.describeSpotInstanceRequests(describeRequest);
        List<SpotInstanceRequest> describeResponses = describeResult.getSpotInstanceRequests();

        for (SpotInstanceRequest describeResponse : describeResponses) {
          String requestId = describeResponse.getSpotInstanceRequestId();
          SpotInstanceState spotInstanceState =
              SpotInstanceState.fromValue(describeResponse.getState());
          String statusCodeString = describeResponse.getStatus().getCode();
          SpotInstanceRequestStatusCode statusCode =
              SpotInstanceRequestStatusCode.getSpotInstanceStatusCodeByStatusCodeString(
                  statusCodeString);
          String virtualInstanceId = null;
          try {
            virtualInstanceId =
                getVirtualInstanceId(describeResponse.getTags(), "Spot instance request");
          } catch (IllegalStateException ignore) {
            // Tagging is asynchronous. We may get here before the tagging completes.
          }
          switch (spotInstanceState) {
            case Active:
              if (cancelling) {
                LOG.info(">> Waiting, requestId {}, state {}...", requestId, spotInstanceState);
              } else {
                if (virtualInstanceId == null) {
                  LOG.info(">> Waiting, requestId {} not yet tagged...", requestId);
                } else {
                  pendingRequestIds.remove(requestId);
                  SpotAllocationRecord spotAllocationRecord =
                      getSpotAllocationRecord(virtualInstanceId);
                  if (spotAllocationRecord.ec2InstanceId == null) {
                    spotAllocationRecord.ec2InstanceId = describeResponse.getInstanceId();
                    allocatedInstanceCount++;
                  }
                }
              }
              break;
            case Cancelled:
              pendingRequestIds.remove(requestId);
              switch (statusCode) {
                case REQUEST_CANCELED_AND_INSTANCE_RUNNING:
                  if (virtualInstanceId == null) {
                    String ec2InstanceId = describeResponse.getInstanceId();
                    LOG.info(">> Untagged requestId {} has associated instance {}...", requestId,
                        ec2InstanceId);
                    spotInstancesByUntaggedSpotInstanceRequestId.put(requestId, ec2InstanceId);
                  } else {
                    SpotAllocationRecord spotAllocationRecord =
                        getSpotAllocationRecord(virtualInstanceId);
                    if (spotAllocationRecord.ec2InstanceId == null) {
                      spotAllocationRecord.ec2InstanceId = describeResponse.getInstanceId();
                      allocatedInstanceCount++;
                    }
                  }
                  break;
                default:
                  break;
              }
              break;
            case Closed:
            case Failed:
              pendingRequestIds.remove(requestId);
              break;
            default:
              switch (statusCode) {
                case PRICE_TOO_LOW:
                  if (System.currentTimeMillis() >= priceChangeDeadlineTime.getTime()) {
                    LOG.info("<< Spot bid too low for requestId {}", requestId);
                    pendingRequestIds.remove(requestId);
                  }
                  break;
                default:
                  // Keep looping on Open responses
                  LOG.info(">> Waiting, requestId {}, state {}...", requestId, spotInstanceState);
                  break;
              }
              break;
          }
        }

        if (System.currentTimeMillis() > requestExpirationTime.getTime()) {
          break;
        }

        // TODO add configurable delay
        Thread.sleep(1000);
      }
    }

    /**
     * Tags provisioned Spot instances. Expects that the instances already exists or are in the
     * process of being created.
     *
     * @throws InterruptedException if the operation is interrupted
     */
    @VisibleForTesting
    @SuppressWarnings("PMD.UselessParentheses")
    protected void tagSpotInstances() throws InterruptedException {

      // Pre-compute user-defined tags for efficiency
      List<Tag> userDefinedTags = ec2TagHelper.getUserDefinedTags(template);

      for (SpotAllocationRecord spotAllocationRecord :
          spotAllocationRecordsByVirtualInstanceId.values()) {
        if ((spotAllocationRecord.ec2InstanceId != null) && !spotAllocationRecord.instanceTagged &&
            tagSpotInstance(template, userDefinedTags, spotAllocationRecord.virtualInstanceId,
                spotAllocationRecord.ec2InstanceId)) {
          spotAllocationRecord.instanceTagged = true;
        }
      }
    }

    /**
     * Tags an EC2 instance. Expects that the instance already exists or is in the process of
     * being created. This may also tag EBS volumes depending on template configurations.
     *
     * @param template          the instance template
     * @param userDefinedTags   the user-defined tags
     * @param virtualInstanceId the virtual instance id
     * @param ec2InstanceId     the EC2 instance id
     * @return true if the instance was successfully tagged, false otherwise
     * @throws InterruptedException if the operation is interrupted
     */
    private boolean tagSpotInstance(EC2InstanceTemplate template, List<Tag> userDefinedTags,
                                String virtualInstanceId, String ec2InstanceId) throws InterruptedException {
      LOG.info(">> Tagging instance {} / {}", ec2InstanceId, virtualInstanceId);

      // We have to individually tag the spot instance and it's associated volumes
      // since AWS doesn't allow specifying tags as part of the launch request for
      // spot instances.

      // Wait for the instance to be started. If it is terminating, skip tagging.
      if (!waitUntilInstanceHasStarted(ec2InstanceId)) {
        return false;
      }

      List<Tag> tags = ec2TagHelper.getInstanceTags(template, virtualInstanceId, userDefinedTags);
      client.createTags(new CreateTagsRequest().withTags(tags).withResources(ec2InstanceId));

      // Tag EBS volumes if they were part of instance launch request
      if (EBSAllocationStrategy.get(template) == EBSAllocationStrategy.AS_INSTANCE_REQUEST) {
        tagSpotEbsVolumes(ec2InstanceId, virtualInstanceId, tags);
      }

      return true;
    }

    private void tagSpotEbsVolumes(String ec2InstanceId, String virtualInstanceId, List<Tag> tags)
        throws InterruptedException {
      DescribeInstancesResult result = client.describeInstances(
          new DescribeInstancesRequest().withInstanceIds(Collections.singletonList(ec2InstanceId))
      );
      List<InstanceBlockDeviceMapping> instanceBlockDeviceMappings =
          getOnlyElement(getOnlyElement(result.getReservations()).getInstances()).getBlockDeviceMappings();
      for (InstanceBlockDeviceMapping instanceBlockDeviceMapping : instanceBlockDeviceMappings) {
        String volumeId = instanceBlockDeviceMapping.getEbs().getVolumeId();
        tagSpotEbsVolume(template, tags, virtualInstanceId, volumeId);
      }
    }

    private void tagSpotEbsVolume(EC2InstanceTemplate template, List<Tag> userDefinedTags,
                          String virtualInstanceId, String volumeId) throws InterruptedException {
      LOG.info(">> Tagging volume {} / {}", volumeId, virtualInstanceId);
      List<Tag> tags = ec2TagHelper.getInstanceTags(template, virtualInstanceId, userDefinedTags);
      client.createTags(new CreateTagsRequest().withTags(tags).withResources(volumeId));
    }

    /**
     * Waits for provisioned Spot instances to have a private IP address.
     *
     * @return terminated instance IDs
     * @throws InterruptedException if the operation is interrupted
     */
    @VisibleForTesting
    @SuppressWarnings("PMD.UselessParentheses")
    protected Collection<String> waitForPrivateIpAddresses() throws InterruptedException {
      Set<String> ec2InstanceIds = Sets.newHashSet();
      for (SpotAllocationRecord spotAllocationRecord :
          spotAllocationRecordsByVirtualInstanceId.values()) {
        String ec2InstanceId = spotAllocationRecord.ec2InstanceId;
        if ((spotAllocationRecord.privateIpAddress == null) && (ec2InstanceId != null) &&
            spotAllocationRecord.instanceTagged) {
          ec2InstanceIds.add(ec2InstanceId);
        }
      }

      return EC2Provider.this.waitForPrivateIpAddresses(ec2InstanceIds);
    }

    private Collection<String> getVirtualInstanceIdsAllocated() {
      Set<String> virtualInstanceIds = Sets.newHashSet();

      for (Map.Entry<String, SpotAllocationRecord> entry : spotAllocationRecordsByVirtualInstanceId.entrySet()) {
        String virtualInstanceId = entry.getKey();
        SpotAllocationRecord spotAllocationRecord = entry.getValue();

        String ec2InstanceId = spotAllocationRecord.ec2InstanceId;
        if (ec2InstanceId != null && spotAllocationRecord.instanceTagged) {
          virtualInstanceIds.add(virtualInstanceId);
        }
      }
      return virtualInstanceIds;
    }

    /**
     * Terminates any running Spot instances (includes discovered orphans and allocated instances).
     *
     * @param accumulator the exception condition accumulator
     * @throws InterruptedException if operation is interrupted
     */
    @VisibleForTesting
    protected void terminateSpotInstances(PluginExceptionConditionAccumulator accumulator)
        throws InterruptedException {

      Set<String> ec2InstanceIds = Sets.newHashSet();
      for (SpotAllocationRecord spotAllocationRecord :
          spotAllocationRecordsByVirtualInstanceId.values()) {
        String ec2InstanceId = spotAllocationRecord.ec2InstanceId;
        if (ec2InstanceId != null) {
          ec2InstanceIds.add(ec2InstanceId);
        }
      }
      for (String ec2InstanceId : spotInstancesByUntaggedSpotInstanceRequestId.values()) {
        ec2InstanceIds.add(ec2InstanceId);
      }

      if (!ec2InstanceIds.isEmpty()) {
        LOG.info(">> Terminating Spot instances {}", ec2InstanceIds);
        TerminateInstancesResult terminateResult;
        try {
          terminateResult = client.terminateInstances(
              new TerminateInstancesRequest().withInstanceIds(ec2InstanceIds));
          LOG.info("<< Result {}", terminateResult);
        } catch (AmazonClientException e) {
          throw AWSExceptions.propagate(e);
        } catch (Exception e) {
          accumulator.addError(null, "Problem terminating Spot instances: "
              + getErrorMessage(e));
        }
      }
    }

    /**
     * Cancels any outstanding Spot requests (includes discovered orphans and created requests).
     *
     * @param accumulator the exception condition accumulator
     * @throws InterruptedException if the operation is interrupted
     */
    @VisibleForTesting
    protected void cancelSpotRequests(PluginExceptionConditionAccumulator accumulator)
        throws InterruptedException {

      Set<String> spotInstanceRequestIds = Sets.newHashSet();
      for (SpotAllocationRecord spotAllocationRecord :
          spotAllocationRecordsByVirtualInstanceId.values()) {
        String spotInstanceRequestId = spotAllocationRecord.spotInstanceRequestId;
        if (spotInstanceRequestId != null) {
          spotInstanceRequestIds.add(spotInstanceRequestId);
        }
      }

      if (!spotInstanceRequestIds.isEmpty()) {
        CancelSpotInstanceRequestsResult cancelResult = null;
        try {
          long expirationTime = requestExpirationTime.getTime();
          while (cancelResult == null && System.currentTimeMillis() < expirationTime) {
            LOG.info(">> Canceling Spot instance requests {}", spotInstanceRequestIds);
            try {
              cancelResult = client.cancelSpotInstanceRequests(
                  new CancelSpotInstanceRequestsRequest().withSpotInstanceRequestIds(spotInstanceRequestIds));
              LOG.info("<< Result {}", cancelResult);
            } catch (AmazonServiceException e) {
              if (!INVALID_SPOT_INSTANCE_REQUEST_ID_NOT_FOUND.equals(e.getErrorCode())) {
                LOG.warn("Possible eventual consistency issue when canceling spot instance requests:", e);
                TimeUnit.SECONDS.sleep(5);
              } else {
                // let outer try handle this
                throw e;
              }
            }
          }
          waitForSpotInstances(spotInstanceRequestIds, true);
        } catch (AmazonClientException e) {
          throw AWSExceptions.propagate(e);
        } catch (InterruptedException e) {
          throw e;
        } catch (Exception e) {
          accumulator.addError(null, "Problem canceling Spot instance requests: "
              + getErrorMessage(e));
        }
      }
    }

    /**
     * Returns the error message for the specified exception.
     *
     * @param e the exception
     * @return the error message for the specified exception
     */
    private String getErrorMessage(Exception e) {
      String message = e.getMessage();
      return (message == null) ? e.getClass().getSimpleName() : message;
    }

    /**
     * Determines the idempotency client token for the specified virtual instance ID.
     *
     * @param virtualInstanceId the virtual instance ID
     * @param discriminator a discriminator to further identify this request
     * @return the idempotency token
     */
    private String determineClientToken(String virtualInstanceId, Long discriminator) {
      // Using MD5 because clientToken should be less than 64 characters long
      Hasher hasher = Hashing.md5().newHasher(virtualInstanceIds.size());

      hasher.putString(virtualInstanceId, Charsets.UTF_8);
      hasher.putLong(discriminator);
      return hasher.hash().toString();
    }
  }
}
