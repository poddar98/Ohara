package org.example.ohara.content.application;

import org.example.ohara.content.domain.ContentAsset.ContentProfile;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LadderTest {

    private static java.util.List<Integer> heights(int w, int h, ContentProfile profile) {
        return Ladder.forSource(w, h, profile).stream().map(Ladder.Rung::height).toList();
    }

    @Test
    void lecturesTopOutAt720pAndScreenDetailGets1080p() {
        assertThat(heights(1920, 1080, ContentProfile.SLIDES)).containsExactly(720, 480);
        assertThat(heights(1920, 1080, ContentProfile.TALKING_HEAD)).containsExactly(720, 480);
        assertThat(heights(1920, 1080, ContentProfile.SCREEN_DETAIL)).containsExactly(1080, 720, 480);
    }

    @Test
    void neverUpscales() {
        assertThat(heights(1280, 720, ContentProfile.SCREEN_DETAIL)).containsExactly(720, 480);
        assertThat(heights(854, 480, ContentProfile.SLIDES)).containsExactly(480);
        assertThat(heights(640, 360, ContentProfile.SLIDES)).containsExactly(360);
    }
}
