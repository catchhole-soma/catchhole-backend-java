package org.monitoring.catchholebackend.global.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "storage.s3")
public class S3StorageProperties {

    private String bucket;

    private String region;

    private String endpointOverride;

    private String accessKeyId;

    private String secretAccessKey;
}
