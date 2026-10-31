package org.example.ohara.content.application;

import org.example.ohara.content.infrastructure.storage.LocalObjectStorage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Verification follows the master playlist, so MediaConvert's single-directory layout passes like the local one. */
class HlsVerifierLayoutTest {

    @Test
    void mediaConvertStyleOutputVerifies(@TempDir Path root) throws Exception {
        ObjectStorage storage = new LocalObjectStorage(root.toString(), "http://localhost");
        String prefix = "delivery/org/o1/asset/a1/g1/hls/";
        put(storage, prefix + "master.m3u8", """
            #EXTM3U
            #EXT-X-VERSION:3
            #EXT-X-INDEPENDENT-SEGMENTS
            #EXT-X-STREAM-INF:BANDWIDTH=2096000,AVERAGE-BANDWIDTH=1100000,CODECS="avc1.4d401f,mp4a.40.2",RESOLUTION=1280x720,FRAME-RATE=30.000
            master_720p.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=964000,AVERAGE-BANDWIDTH=560000,CODECS="avc1.4d401e,mp4a.40.2",RESOLUTION=640x480,FRAME-RATE=30.000
            master_480p.m3u8
            """);
        for (String rung : List.of("720p", "480p")) {
            put(storage, prefix + "master_" + rung + ".m3u8", """
                #EXTM3U
                #EXT-X-VERSION:3
                #EXT-X-TARGETDURATION:6
                #EXT-X-MEDIA-SEQUENCE:1
                #EXT-X-PLAYLIST-TYPE:VOD
                #EXTINF:6.00000,
                master_%1$s_00001.ts
                #EXTINF:6.00000,
                master_%1$s_00002.ts
                #EXTINF:3.00000,
                master_%1$s_00003.ts
                #EXT-X-ENDLIST
                """.formatted(rung));
            for (int i = 1; i <= 3; i++) {
                put(storage, prefix + "master_" + rung + "_0000" + i + ".ts", "ts");
            }
        }

        List<HlsVerifier.Variant> variants = new HlsVerifier(storage).verify(prefix, 15_000, 2);

        assertThat(variants).extracting(HlsVerifier.Variant::height).containsExactly(720, 480);
        assertThat(variants).extracting(HlsVerifier.Variant::width).containsExactly(1280, 640); // a 4:3 source kept its shape
        assertThat(variants.getFirst().playlistKey()).isEqualTo(prefix + "master_720p.m3u8");
    }

    private static void put(ObjectStorage storage, String key, String text) {
        storage.put(key, text.getBytes(StandardCharsets.UTF_8), "text/plain");
    }
}
