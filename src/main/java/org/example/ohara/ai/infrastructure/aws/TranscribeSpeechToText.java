package org.example.ohara.ai.infrastructure.aws;

import org.example.ohara.ai.application.SpeechToText;
import org.example.ohara.content.application.ObjectStorage;
import software.amazon.awssdk.services.mediaconvert.MediaConvertClient;
import software.amazon.awssdk.services.mediaconvert.model.AacCodingMode;
import software.amazon.awssdk.services.mediaconvert.model.AudioCodec;
import software.amazon.awssdk.services.mediaconvert.model.AudioDefaultSelection;
import software.amazon.awssdk.services.mediaconvert.model.AudioDescription;
import software.amazon.awssdk.services.mediaconvert.model.AudioSelector;
import software.amazon.awssdk.services.mediaconvert.model.ContainerType;
import software.amazon.awssdk.services.mediaconvert.model.CreateJobRequest;
import software.amazon.awssdk.services.mediaconvert.model.Input;
import software.amazon.awssdk.services.mediaconvert.model.Output;
import software.amazon.awssdk.services.mediaconvert.model.OutputGroup;
import software.amazon.awssdk.services.mediaconvert.model.OutputGroupType;
import software.amazon.awssdk.services.transcribe.TranscribeClient;
import software.amazon.awssdk.services.transcribe.model.BadRequestException;
import software.amazon.awssdk.services.transcribe.model.ConflictException;
import software.amazon.awssdk.services.transcribe.model.LanguageCodeItem;
import software.amazon.awssdk.services.transcribe.model.MediaFormat;
import software.amazon.awssdk.services.transcribe.model.StartTranscriptionJobRequest;
import software.amazon.awssdk.services.transcribe.model.TranscriptionJob;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * {@link SpeechToText} on Amazon Transcribe batch jobs (docs 07 §3), in two stages behind one job reference:
 * <ol>
 *   <li>MediaConvert extracts a mono 64 kbps AAC file ({@code stt-audio/<job>/speech.m4a} in the source bucket) from
 *       whatever the source is: an uploaded video in any container MediaConvert reads, or a live recording's HLS
 *       master. Transcribe accepts neither HLS nor files over 2 GB, and the audio file is ~29 MB per hour.</li>
 *   <li>When that finishes, the next poll starts the Transcribe job, which writes its JSON to
 *       {@code stt-output/<job>.json} in the source bucket (not Transcribe's own bucket, so retention is ours).</li>
 * </ol>
 * Both calls are idempotent on {@link Request#clientRef()}: MediaConvert through its client request token, Transcribe
 * through the unique job name, so a repeated submit or poll never starts or bills a second job. Both scratch prefixes
 * are expired by an S3 lifecycle rule (docs 10), not deleted here: deleting early would make a re-delivered
 * poll start the job again.
 * <p>
 * The reference is {@code <transcribe job name>@<MediaConvert job id>}.
 */
public class TranscribeSpeechToText implements SpeechToText, AutoCloseable {

    static final String AUDIO_PREFIX = "stt-audio/";
    static final String OUTPUT_PREFIX = "stt-output/";
    static final String DELIVERY_PREFIX = "delivery/";
    private static final String AUDIO_SELECTOR = "Audio Selector 1";
    /** The language candidates travel with the extraction job, so the poll that starts stage 2 knows them. */
    static final String LANGUAGES = "languages";

    private final TranscribeClient transcribe;
    private final MediaConvertClient mediaConvert;
    private final ObjectStorage storage;
    private final JsonMapper json;
    private final String sourceBucket;
    private final String deliveryBucket;
    private final String mediaConvertRoleArn;
    private final String mediaConvertQueueArn;
    private final boolean multipleLanguages;
    private final int maxSpeakers;

    public TranscribeSpeechToText(TranscribeClient transcribe, MediaConvertClient mediaConvert, ObjectStorage storage,
                                  JsonMapper json, String sourceBucket, String deliveryBucket, String mediaConvertRoleArn,
                                  String mediaConvertQueueArn, boolean multipleLanguages, int maxSpeakers) {
        this.transcribe = transcribe;
        this.mediaConvert = mediaConvert;
        this.storage = storage;
        this.json = json;
        this.sourceBucket = sourceBucket;
        this.deliveryBucket = deliveryBucket;
        this.mediaConvertRoleArn = mediaConvertRoleArn;
        this.mediaConvertQueueArn = mediaConvertQueueArn == null || mediaConvertQueueArn.isBlank() ? null : mediaConvertQueueArn;
        this.multipleLanguages = multipleLanguages;
        this.maxSpeakers = maxSpeakers;
    }

    @Override
    public String provider() {
        return "AWS_TRANSCRIBE";
    }

    @Override
    public String model() {
        return "transcribe-batch";
    }

    static String jobName(String clientRef) {
        return "ohara-" + clientRef.replaceAll("[^0-9A-Za-z._-]", "-");
    }

    static String audioKey(String jobName) {
        return AUDIO_PREFIX + jobName + "/speech.m4a";
    }

    static String outputKey(String jobName) {
        return OUTPUT_PREFIX + jobName + ".json";
    }

    private String s3Uri(String key) {
        return "s3://" + (key.startsWith(DELIVERY_PREFIX) ? deliveryBucket : sourceBucket) + "/" + key;
    }

    @Override
    public String submit(Request request) {
        String name = jobName(request.clientRef());
        String audioKey = audioKey(name);
        CreateJobRequest.Builder job = CreateJobRequest.builder()
            .role(mediaConvertRoleArn)
            .clientRequestToken(name)
            .userMetadata(Map.of("purpose", "stt-audio", "sttJob", name, LANGUAGES, String.join(",", request.languages())))
            .settings(s -> s
                .inputs(Input.builder()
                    .fileInput(s3Uri(request.mediaKey()))
                    .audioSelectors(Map.of(AUDIO_SELECTOR, AudioSelector.builder().defaultSelection(AudioDefaultSelection.DEFAULT).build()))
                    .build())
                .outputGroups(OutputGroup.builder()
                    .name("STT audio")
                    .outputGroupSettings(g -> g
                        .type(OutputGroupType.FILE_GROUP_SETTINGS)
                        .fileGroupSettings(f -> f.destination(s3Uri(audioKey.substring(0, audioKey.length() - ".m4a".length())))))
                    .outputs(Output.builder()
                        .extension("m4a")
                        .containerSettings(c -> c.container(ContainerType.MP4))
                        .audioDescriptions(AudioDescription.builder()
                            .audioSourceName(AUDIO_SELECTOR)
                            .codecSettings(c -> c
                                .codec(AudioCodec.AAC)
                                .aacSettings(a -> a.bitrate(64_000).codingMode(AacCodingMode.CODING_MODE_1_0).sampleRate(48_000)))
                            .build())
                        .build())
                    .build()));
        if (mediaConvertQueueArn != null) {
            job.queue(mediaConvertQueueArn);
        }
        String mediaConvertJobId = mediaConvert.createJob(job.build()).job().id();
        return name + "@" + mediaConvertJobId;
    }

    @Override
    public Result status(String jobRef) {
        int at = jobRef.lastIndexOf('@');
        if (at <= 0) {
            return failed("Malformed job reference " + jobRef);
        }
        String name = jobRef.substring(0, at);
        String mediaConvertJobId = jobRef.substring(at + 1);

        TranscriptionJob job = transcriptionJob(name);
        if (job == null) {
            return extractionStatus(name, mediaConvertJobId);
        }
        return switch (job.transcriptionJobStatus()) {
            case COMPLETED -> new Result(State.COMPLETE, null, language(job), transcript(outputKey(name)));
            case FAILED -> failed("Transcribe: " + job.failureReason());
            default -> pending();
        };
    }

    /** Stage 1 is still running, failed, or has just finished (then stage 2 starts). */
    private Result extractionStatus(String name, String mediaConvertJobId) {
        software.amazon.awssdk.services.mediaconvert.model.Job extraction;
        try {
            extraction = mediaConvert.getJob(r -> r.id(mediaConvertJobId)).job();
        } catch (software.amazon.awssdk.services.mediaconvert.model.NotFoundException gone) {
            return failed("Audio extraction job " + mediaConvertJobId + " not found");
        }
        return switch (extraction.status()) {
            case COMPLETE -> startTranscription(name, List.of(extraction.userMetadata().getOrDefault(LANGUAGES, "en-IN").split(",")));
            case ERROR -> failed("Audio extraction failed: MediaConvert error " + extraction.errorCode() + ": "
                + extraction.errorMessage());
            case CANCELED -> failed("Audio extraction was cancelled");
            default -> pending();
        };
    }

    private Result startTranscription(String name, List<String> languages) {
        StartTranscriptionJobRequest.Builder request = StartTranscriptionJobRequest.builder()
            .transcriptionJobName(name)
            .media(m -> m.mediaFileUri(s3Uri(audioKey(name))))
            .mediaFormat(MediaFormat.M4_A)
            .outputBucketName(sourceBucket)
            .outputKey(outputKey(name));
        if (languages.size() == 1) {
            request.languageCode(languages.getFirst());
        } else if (multipleLanguages) {
            request.identifyMultipleLanguages(true).languageOptionsWithStrings(languages);
        } else {
            request.identifyLanguage(true).languageOptionsWithStrings(languages);
        }
        if (maxSpeakers > 1) {
            request.settings(s -> s.showSpeakerLabels(true).maxSpeakerLabels(maxSpeakers));
        }
        try {
            transcribe.startTranscriptionJob(request.build());
        } catch (ConflictException alreadyStarted) {
            // an earlier poll started it; nothing more to do
        } catch (BadRequestException rejected) {
            return failed("Transcribe rejected the job: " + rejected.awsErrorDetails().errorMessage());
        }
        return pending();
    }

    private TranscriptionJob transcriptionJob(String name) {
        try {
            return transcribe.getTranscriptionJob(r -> r.transcriptionJobName(name)).transcriptionJob();
        } catch (BadRequestException notFound) {
            // Transcribe reports an unknown job name as a BadRequest ("The requested job couldn't be found")
            return null;
        }
    }

    private String language(TranscriptionJob job) {
        if (job.languageCode() != null) {
            return job.languageCodeAsString();
        }
        if (job.hasLanguageCodes() && !job.languageCodes().isEmpty()) {
            // several languages: the one spoken longest (Hindi-English lectures are usually mostly one of the two)
            return job.languageCodes().stream()
                .max(Comparator.comparing(i -> i.durationInSeconds() == null ? 0f : i.durationInSeconds()))
                .map(LanguageCodeItem::languageCodeAsString).orElse(null);
        }
        return null;
    }

    private List<Segment> transcript(String key) {
        try (InputStream in = storage.open(key)) {
            return TranscribeOutput.segments(json.readTree(in));
        } catch (IOException e) {
            // the job just completed, so a read failure is transient; the check job retries
            throw new UncheckedIOException("Couldn't read Transcribe output " + key, e);
        }
    }

    private static Result pending() {
        return new Result(State.PENDING, null, null, List.of());
    }

    private static Result failed(String why) {
        return new Result(State.FAILED, why, null, List.of());
    }

    @Override
    public void close() {
        transcribe.close();
        mediaConvert.close();
    }
}
