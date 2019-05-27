package com.agorapulse.micronaut.aws.cloudwatch;

import com.amazonaws.auth.AWSCredentialsProvider;
import com.amazonaws.regions.AwsRegionProvider;
import com.amazonaws.services.cloudwatch.AmazonCloudWatch;
import com.amazonaws.services.cloudwatch.AmazonCloudWatchClientBuilder;
import io.micronaut.configuration.aws.AWSClientConfiguration;
import io.micronaut.context.annotation.*;

import javax.inject.Singleton;
import java.util.Optional;

/**
 * Factory for providing CloudWatch.
 *
 * This is very basic support for CloudWatch which main purpose is to support Kinesis listeners customisation.
 */
@Factory
@Requires(classes = AmazonCloudWatch.class)
public class CloudWatchFactory {

    @Bean
    @Singleton
    AmazonCloudWatch cloudWatch(
        AWSClientConfiguration clientConfiguration,
        AWSCredentialsProvider credentialsProvider,
        AwsRegionProvider awsRegionProvider,
        @Value("${aws.cloudwatch.region}") Optional<String> region
    ) {
        return AmazonCloudWatchClientBuilder.standard()
            .withCredentials(credentialsProvider)
            .withRegion(region.orElseGet(awsRegionProvider::getRegion))
            .withClientConfiguration(clientConfiguration.getClientConfiguration())
            .build();
    }

    @EachBean(DefaultCloudWatchConfiguration.class)
    CloudWatchService cloudWatchService(DefaultCloudWatchConfiguration configuration, AmazonCloudWatch client) {
        return new DefaultCloudWatchService(configuration, client);
    }

}