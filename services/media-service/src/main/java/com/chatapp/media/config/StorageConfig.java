package com.chatapp.media.config;

import java.net.URI;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/** The two S3 clients: one for the service itself, one that signs links for the browser. */
@Configuration
public class StorageConfig {

    @Bean
    public StaticCredentialsProvider s3Credentials(MediaProperties properties) {
        return StaticCredentialsProvider.create(
                AwsBasicCredentials.create(properties.s3().accessKey(), properties.s3().secretKey()));
    }

    /** Used by the service to upload, delete and check the bucket (internal address). */
    @Bean
    public S3Client s3Client(MediaProperties properties, StaticCredentialsProvider credentials) {
        return S3Client.builder()
                .endpointOverride(URI.create(properties.s3().endpoint()))
                .region(Region.of(properties.s3().region()))
                .credentialsProvider(credentials)
                .forcePathStyle(true)                     // http://host:port/bucket/key (works with RustFS and real S3)
                // Newer SDKs add extra checksums by default; not every S3-compatible storage understands them.
                .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
                .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
                .build();
    }

    /**
     * Signs download links. Signing is a local calculation (nothing is sent), so we can sign for the PUBLIC
     * address that browsers use even if the service cannot reach that address itself.
     */
    @Bean
    public S3Presigner s3Presigner(MediaProperties properties, StaticCredentialsProvider credentials) {
        return S3Presigner.builder()
                .endpointOverride(URI.create(properties.s3().publicEndpoint()))
                .region(Region.of(properties.s3().region()))
                .credentialsProvider(credentials)
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .build();
    }
}
