package org.example.ohara.ai.infrastructure.aws;

import org.example.ohara.ai.application.SpeechToText.Segment;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/** How Transcribe's word list is cut into caption-sized segments. */
class TranscribeOutputTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** One word item; {@code speaker} may be null. */
    private static String word(String text, double start, double end, double confidence, String speaker) {
        return String.format(Locale.ROOT,
            "{\"type\":\"pronunciation\",\"start_time\":\"%.3f\",\"end_time\":\"%.3f\"%s,\"alternatives\":[{\"confidence\":\"%.2f\",\"content\":\"%s\"}]}",
            start, end, speaker == null ? "" : ",\"speaker_label\":\"" + speaker + "\"", confidence, text);
    }

    private static String punct(String mark) {
        return "{\"type\":\"punctuation\",\"alternatives\":[{\"confidence\":\"0.0\",\"content\":\"" + mark + "\"}]}";
    }

    private static List<Segment> parse(List<String> items) {
        return TranscribeOutput.segments(JSON.readTree("{\"results\":{\"items\":[" + String.join(",", items) + "]}}"));
    }

    @Test
    void punctuationJoinsTheWordBeforeItAndALongEnoughSentenceEndsTheSegment() {
        List<Segment> segments = parse(List.of(
            word("Merge", 0.0, 0.4, 0.9, "spk_0"), word("sort", 0.4, 0.8, 0.8, "spk_0"), punct(","),
            word("recursively", 0.8, 1.9, 1.0, "spk_0"), punct("."),
            word("Next", 2.0, 2.3, 0.9, "spk_0")));

        assertThat(segments).hasSize(2);
        assertThat(segments.get(0).text()).isEqualTo("Merge sort, recursively.");
        assertThat(segments.get(0).startMs()).isZero();
        assertThat(segments.get(0).endMs()).isEqualTo(1900);
        assertThat(segments.get(0).confidence()).isCloseTo(0.9, org.assertj.core.data.Offset.offset(0.001));
        assertThat(segments.get(1).text()).isEqualTo("Next");
    }

    @Test
    void aShortSentenceRunsOnIntoTheNextInsteadOfMakingATinyCue() {
        List<Segment> segments = parse(List.of(
            word("Yes", 0.0, 0.3, 0.9, null), punct("."), word("Exactly", 0.4, 0.9, 0.9, null), punct(".")));

        assertThat(segments).singleElement().extracting(Segment::text).isEqualTo("Yes. Exactly.");
    }

    @Test
    void aSpeakerChangeOrALongPauseStartsANewSegment() {
        List<Segment> segments = parse(List.of(
            word("Any", 0.0, 0.3, 0.9, "spk_0"), word("questions", 0.3, 0.8, 0.9, "spk_0"),
            word("Why", 1.0, 1.2, 0.9, "spk_1"),
            word("Because", 4.0, 4.5, 0.9, "spk_1")));

        assertThat(segments).extracting(Segment::text).containsExactly("Any questions", "Why", "Because");
        assertThat(segments).extracting(Segment::speaker).containsExactly("spk_0", "spk_1", "spk_1");
    }

    @Test
    void aSegmentNeverRunsPastEightSecondsOrACaptionLine() {
        List<String> items = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            items.add(word("partition", i * 0.5, i * 0.5 + 0.45, 0.9, "spk_0"));
        }

        List<Segment> segments = parse(items);

        assertThat(segments).hasSizeGreaterThan(1);
        assertThat(segments).allSatisfy(s -> {
            assertThat(s.endMs() - s.startMs()).isLessThanOrEqualTo(TranscribeOutput.MAX_MS);
            assertThat(s.text().length()).isLessThanOrEqualTo(TranscribeOutput.MAX_CHARS);
        });
        assertThat(segments.stream().mapToInt(s -> s.text().split(" ").length).sum()).isEqualTo(40);
    }

    @Test
    void speakersFromTheOlderSpeakerLabelsBlockAreMatchedByStartTime() {
        String json = """
            {"results": {
              "speaker_labels": {"segments": [{"items": [
                {"start_time": "0.000", "speaker_label": "spk_0"}, {"start_time": "1.000", "speaker_label": "spk_1"}]}]},
              "items": [%s, %s]}}""".formatted(word("Hello", 0, 0.5, 0.9, null), word("Hi", 1, 1.4, 0.9, null));

        List<Segment> segments = TranscribeOutput.segments(JSON.readTree(json));

        assertThat(segments).extracting(Segment::speaker).containsExactly("spk_0", "spk_1");
    }

    @Test
    void noSpeechGivesNoSegments() {
        assertThat(parse(List.of())).isEmpty();
        assertThat(TranscribeOutput.segments(JSON.readTree("{\"results\":{}}"))).isEmpty();
    }
}
