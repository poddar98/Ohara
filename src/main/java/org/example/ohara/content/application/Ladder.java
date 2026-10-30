package org.example.ohara.content.application;

import org.example.ohara.content.domain.ContentAsset;

import java.util.ArrayList;
import java.util.List;

/**
 * The adaptive-bitrate ladder for lectures (docs 06 §4). Slides and talking heads top out at 720p — the biggest
 * delivery-cost lever (docs 11 §7); 1080p only for screen-detail content. Never upscales: rungs above the source are
 * skipped, and a source below 480p gets a single rung at its own height.
 */
public final class Ladder {

    public record Rung(int height, int width, int videoKbps, int maxKbps, int audioKbps) {

        public String name() {
            return height + "p";
        }

        public int bandwidthKbps() {
            return maxKbps + audioKbps;
        }
    }

    static final Rung P1080 = new Rung(1080, 1920, 2500, 4000, 96);
    static final Rung P720 = new Rung(720, 1280, 1200, 2000, 96);
    static final Rung P480 = new Rung(480, 854, 600, 900, 64);

    private Ladder() {
    }

    public static List<Rung> forSource(int sourceWidth, int sourceHeight, ContentAsset.ContentProfile profile) {
        List<Rung> rungs = new ArrayList<>();
        if (profile == ContentAsset.ContentProfile.SCREEN_DETAIL && sourceHeight >= 1080) {
            rungs.add(P1080);
        }
        if (sourceHeight >= 720) {
            rungs.add(P720);
        }
        if (sourceHeight >= 480) {
            rungs.add(P480);
        } else {
            int width = even(sourceWidth > 0 ? sourceWidth : sourceHeight * 16 / 9);
            rungs.add(new Rung(even(sourceHeight), width, 400, 600, 64));
        }
        return rungs;
    }

    private static int even(int value) {
        return value % 2 == 0 ? value : value - 1;
    }
}
