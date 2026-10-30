package org.example.ohara.content.application;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PartSizeTest {

    private static final long MB = 1024L * 1024;

    @Test
    void smallFilesAreOnePart() {
        assertThat(AssetService.partSize(5 * MB)).isEqualTo(5 * MB);
        assertThat(AssetService.partSize(100 * MB)).isEqualTo(100 * MB);
    }

    @Test
    void largeFilesUseSixteenMegabytePartsUntilTheTenThousandPartLimitForcesBiggerOnes() {
        assertThat(AssetService.partSize(2L * 1024 * MB)).isEqualTo(16 * MB);
        long twentyGb = 20L * 1024 * MB;
        long size = AssetService.partSize(twentyGb);
        assertThat(Math.ceilDiv(twentyGb, size)).isLessThanOrEqualTo(10_000);
        assertThat(size % MB).isZero();
    }
}
