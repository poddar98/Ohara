package org.example.ohara.content.infrastructure.aws;

import org.example.ohara.content.application.ObjectStorage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.net.URI;

/**
 * S3 storage when {@code ohara.storage.provider=s3}. Credentials come from the default AWS chain (instance/task role
 * in AWS, env or profile locally). {@code endpoint} and static keys exist only for S3-compatible emulators in tests
 * and development; never set them in production.
 */
@Configuration
@ConditionalOnProperty(name = "ohara.storage.provider", havingValue = "s3")
class AwsStorageConfig {

    private final Region region;
    private final String endpoint;
    private final boolean pathStyle;
    private final AwsCredentialsProvider credentials;

    AwsStorageConfig(@Value("${ohara.aws.region:ap-south-1}") String region,
                     @Value("${ohara.storage.s3.endpoint:}") String endpoint,
                     @Value("${ohara.storage.s3.path-style:false}") boolean pathStyle,
                     @Value("${ohara.storage.s3.access-key:}") String accessKey,
                     @Value("${ohara.storage.s3.secret-key:}") String secretKey) {
        this.region = Region.of(region);
        this.endpoint = endpoint;
        this.pathStyle = pathStyle;
        this.credentials = accessKey.isBlank()
            ? DefaultCredentialsProvider.builder().build()
            : StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey));
    }

    @Bean(destroyMethod = "close")
    S3Client s3Client() {
        var builder = S3Client.builder().region(region).credentialsProvider(credentials)
            .httpClientBuilder(UrlConnectionHttpClient.builder())
            .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(pathStyle).build());
        if (!endpoint.isBlank()) {
            builder.endpointOverride(URI.create(endpoint));
        }
        return builder.build();
    }

    @Bean(destroyMethod = "close")
    S3Presigner s3Presigner() {
        var builder = S3Presigner.builder().region(region).credentialsProvider(credentials)
            .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(pathStyle).build());
        if (!endpoint.isBlank()) {
            builder.endpointOverride(URI.create(endpoint));
        }
        return builder.build();
    }

    @Bean
    ObjectStorage objectStorage(S3Client s3, S3Presigner presigner,
                                @Value("${ohara.storage.s3.source-bucket}") String sourceBucket,
                                @Value("${ohara.storage.s3.delivery-bucket}") String deliveryBucket) {
        return new S3ObjectStorage(s3, presigner, sourceBucket, deliveryBucket);
    }
}
