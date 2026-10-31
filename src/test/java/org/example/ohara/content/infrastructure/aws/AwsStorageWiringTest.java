package org.example.ohara.content.infrastructure.aws;

import org.example.ohara.content.application.ObjectStorage;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

/** With provider=s3 the application runs on S3 and the local storage stand-in (and its /dev-storage endpoint) is gone. */
@SpringBootTest(properties = {
    "ohara.storage.provider=s3",
    "ohara.storage.s3.source-bucket=ohara-source",
    "ohara.storage.s3.delivery-bucket=ohara-delivery",
    "ohara.storage.s3.endpoint=http://localhost:1",
    "ohara.storage.s3.access-key=test",
    "ohara.storage.s3.secret-key=test"})
class AwsStorageWiringTest {

    @Autowired ObjectStorage storage;
    @Autowired ApplicationContext context;

    @Test
    void s3ReplacesTheLocalStandIn() {
        assertThat(storage).isInstanceOf(S3ObjectStorage.class);
        assertThat(((S3ObjectStorage) storage).bucketOf("delivery/org/x/master.m3u8")).isEqualTo("ohara-delivery");
        assertThat(((S3ObjectStorage) storage).bucketOf("org/x/asset/y/source/a.mp4")).isEqualTo("ohara-source");
        assertThat(context.getBeanNamesForType(org.example.ohara.content.infrastructure.storage.LocalObjectStorage.class)).isEmpty();
    }
}
