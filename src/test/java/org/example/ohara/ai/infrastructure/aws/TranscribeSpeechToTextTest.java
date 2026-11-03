package org.example.ohara.ai.infrastructure.aws;

import org.example.ohara.ai.application.SpeechToText;
import org.example.ohara.ai.application.SpeechToText.State;
import org.example.ohara.content.application.ObjectStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.services.mediaconvert.MediaConvertClient;
import software.amazon.awssdk.services.mediaconvert.model.AacCodingMode;
import software.amazon.awssdk.services.mediaconvert.model.ContainerType;
import software.amazon.awssdk.services.mediaconvert.model.CreateJobRequest;
import software.amazon.awssdk.services.mediaconvert.model.CreateJobResponse;
import software.amazon.awssdk.services.mediaconvert.model.GetJobRequest;
import software.amazon.awssdk.services.mediaconvert.model.GetJobResponse;
import software.amazon.awssdk.services.mediaconvert.model.Job;
import software.amazon.awssdk.services.mediaconvert.model.JobStatus;
import software.amazon.awssdk.services.mediaconvert.model.Output;
import software.amazon.awssdk.services.transcribe.TranscribeClient;
import software.amazon.awssdk.services.transcribe.model.BadRequestException;
import software.amazon.awssdk.services.transcribe.model.ConflictException;
import software.amazon.awssdk.services.transcribe.model.GetTranscriptionJobRequest;
import software.amazon.awssdk.services.transcribe.model.GetTranscriptionJobResponse;
import software.amazon.awssdk.services.transcribe.model.LanguageCodeItem;
import software.amazon.awssdk.services.transcribe.model.MediaFormat;
import software.amazon.awssdk.services.transcribe.model.StartTranscriptionJobRequest;
import software.amazon.awssdk.services.transcribe.model.StartTranscriptionJobResponse;
import software.amazon.awssdk.services.transcribe.model.TranscriptionJob;
import software.amazon.awssdk.services.transcribe.model.TranscriptionJobStatus;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

/** The two-stage Transcribe adapter against mocked AWS clients. A dev-account smoke test covers the real services. */
class TranscribeSpeechToTextTest {

    static final String NAME = "ohara-7d5c-1";
    static final String REF = NAME + "@mc-job-1";

    TranscribeClient transcribe;
    MediaConvertClient mediaConvert;
    ObjectStorage storage;
    TranscribeSpeechToText stt;

    @BeforeEach
    void setUp() {
        transcribe = mock(TranscribeClient.class, withSettings().defaultAnswer(CALLS_REAL_METHODS));
        mediaConvert = mock(MediaConvertClient.class, withSettings().defaultAnswer(CALLS_REAL_METHODS));
        storage = mock(ObjectStorage.class);
        stt = new TranscribeSpeechToText(transcribe, mediaConvert, storage, JsonMapper.builder().build(), "ohara-source",
            "ohara-delivery", "arn:aws:iam::123456789012:role/ohara-mediaconvert", "", false, 4);
    }

    private static BadRequestException notFound() {
        return (BadRequestException) BadRequestException.builder()
            .awsErrorDetails(AwsErrorDetails.builder().errorMessage("The requested job couldn't be found.").build()).build();
    }

    private void noTranscriptionJobYet() {
        doThrow(notFound()).when(transcribe).getTranscriptionJob(any(GetTranscriptionJobRequest.class));
    }

    private void extraction(JobStatus status) {
        doReturn(GetJobResponse.builder().job(Job.builder().id("mc-job-1").status(status).errorCode(1040)
                .errorMessage("No audio track").userMetadata(Map.of("languages", "en-IN,hi-IN")).build()).build())
            .when(mediaConvert).getJob(any(GetJobRequest.class));
    }

    private void transcription(TranscriptionJob job) {
        doReturn(GetTranscriptionJobResponse.builder().transcriptionJob(job).build())
            .when(transcribe).getTranscriptionJob(any(GetTranscriptionJobRequest.class));
    }

    @Test
    void submitExtractsMonoAudioIdempotentlyAndKeepsTheLanguagesWithTheJob() {
        doReturn(CreateJobResponse.builder().job(Job.builder().id("mc-job-1").build()).build())
            .when(mediaConvert).createJob(any(CreateJobRequest.class));

        String ref = stt.submit(new SpeechToText.Request("7d5c-1", "live/org/s/recording/master.m3u8", 3_600_000,
            List.of("en-IN", "hi-IN")));

        assertThat(ref).isEqualTo(REF);
        ArgumentCaptor<CreateJobRequest> sent = ArgumentCaptor.forClass(CreateJobRequest.class);
        verify(mediaConvert).createJob(sent.capture());
        CreateJobRequest job = sent.getValue();
        assertThat(job.clientRequestToken()).isEqualTo(NAME);
        assertThat(job.userMetadata()).containsEntry("languages", "en-IN,hi-IN");
        assertThat(job.settings().inputs().getFirst().fileInput()).isEqualTo("s3://ohara-source/live/org/s/recording/master.m3u8");
        var group = job.settings().outputGroups().getFirst();
        assertThat(group.outputGroupSettings().fileGroupSettings().destination())
            .isEqualTo("s3://ohara-source/stt-audio/" + NAME + "/speech");
        Output output = group.outputs().getFirst();
        assertThat(output.extension()).isEqualTo("m4a");
        assertThat(output.containerSettings().container()).isEqualTo(ContainerType.MP4);
        assertThat(output.videoDescription()).isNull();
        assertThat(output.audioDescriptions().getFirst().codecSettings().aacSettings().codingMode())
            .isEqualTo(AacCodingMode.CODING_MODE_1_0);
    }

    @Test
    void whileAudioIsExtractingTheJobIsPendingAndTranscribeIsNotCalled() {
        noTranscriptionJobYet();
        extraction(JobStatus.PROGRESSING);

        assertThat(stt.status(REF).state()).isEqualTo(State.PENDING);
        verify(transcribe, never()).startTranscriptionJob(any(StartTranscriptionJobRequest.class));
    }

    @Test
    void whenExtractionFinishesTheNextPollStartsTranscribeOnTheAudioFile() {
        noTranscriptionJobYet();
        extraction(JobStatus.COMPLETE);
        doReturn(StartTranscriptionJobResponse.builder().build())
            .when(transcribe).startTranscriptionJob(any(StartTranscriptionJobRequest.class));

        assertThat(stt.status(REF).state()).isEqualTo(State.PENDING);

        ArgumentCaptor<StartTranscriptionJobRequest> sent = ArgumentCaptor.forClass(StartTranscriptionJobRequest.class);
        verify(transcribe).startTranscriptionJob(sent.capture());
        StartTranscriptionJobRequest request = sent.getValue();
        assertThat(request.transcriptionJobName()).isEqualTo(NAME);
        assertThat(request.media().mediaFileUri()).isEqualTo("s3://ohara-source/stt-audio/" + NAME + "/speech.m4a");
        assertThat(request.mediaFormat()).isEqualTo(MediaFormat.M4_A);
        assertThat(request.identifyLanguage()).isTrue();
        assertThat(request.languageOptionsAsStrings()).containsExactly("en-IN", "hi-IN");
        assertThat(request.outputBucketName()).isEqualTo("ohara-source");
        assertThat(request.outputKey()).isEqualTo("stt-output/" + NAME + ".json");
        assertThat(request.settings().showSpeakerLabels()).isTrue();
        assertThat(request.settings().maxSpeakerLabels()).isEqualTo(4);
    }

    @Test
    void aJobAnEarlierPollAlreadyStartedIsNotStartedAgain() {
        noTranscriptionJobYet();
        extraction(JobStatus.COMPLETE);
        doThrow(ConflictException.builder().message("exists").build())
            .when(transcribe).startTranscriptionJob(any(StartTranscriptionJobRequest.class));

        assertThat(stt.status(REF).state()).isEqualTo(State.PENDING);
    }

    @Test
    void extractionErrorsFailTheJobWithMediaConvertsReason() {
        noTranscriptionJobYet();
        extraction(JobStatus.ERROR);

        SpeechToText.Result result = stt.status(REF);

        assertThat(result.state()).isEqualTo(State.FAILED);
        assertThat(result.failure()).contains("1040").contains("No audio track");
    }

    @Test
    void aRunningTranscriptionIsPendingWithoutAskingMediaConvert() {
        transcription(TranscriptionJob.builder().transcriptionJobName(NAME)
            .transcriptionJobStatus(TranscriptionJobStatus.IN_PROGRESS).build());

        assertThat(stt.status(REF).state()).isEqualTo(State.PENDING);
        verify(mediaConvert, never()).getJob(any(GetJobRequest.class));
    }

    @Test
    void aFailedTranscriptionCarriesTranscribesReason() {
        transcription(TranscriptionJob.builder().transcriptionJobName(NAME)
            .transcriptionJobStatus(TranscriptionJobStatus.FAILED).failureReason("Invalid sample rate").build());

        SpeechToText.Result result = stt.status(REF);

        assertThat(result.state()).isEqualTo(State.FAILED);
        assertThat(result.failure()).contains("Invalid sample rate");
    }

    @Test
    void aCompletedTranscriptionIsReadFromTheSourceBucketWithItsIdentifiedLanguage() throws Exception {
        transcription(TranscriptionJob.builder().transcriptionJobName(NAME)
            .transcriptionJobStatus(TranscriptionJobStatus.COMPLETED).languageCode("hi-IN").build());
        String output = """
            {"results": {"items": [
              {"type": "pronunciation", "start_time": "0.10", "end_time": "0.60", "speaker_label": "spk_0",
               "alternatives": [{"confidence": "0.98", "content": "Namaste"}]},
              {"type": "punctuation", "alternatives": [{"confidence": "0.0", "content": "."}]}
            ]}}""";
        when(storage.open("stt-output/" + NAME + ".json"))
            .thenReturn(new ByteArrayInputStream(output.getBytes(StandardCharsets.UTF_8)));

        SpeechToText.Result result = stt.status(REF);

        assertThat(result.state()).isEqualTo(State.COMPLETE);
        assertThat(result.language()).isEqualTo("hi-IN");
        assertThat(result.segments()).singleElement().satisfies(s -> {
            assertThat(s.text()).isEqualTo("Namaste.");
            assertThat(s.startMs()).isEqualTo(100);
            assertThat(s.endMs()).isEqualTo(600);
            assertThat(s.speaker()).isEqualTo("spk_0");
        });
    }

    @Test
    void withSeveralLanguagesTheOneSpokenLongestIsReported() throws Exception {
        transcription(TranscriptionJob.builder().transcriptionJobName(NAME)
            .transcriptionJobStatus(TranscriptionJobStatus.COMPLETED)
            .languageCodes(LanguageCodeItem.builder().languageCode("hi-IN").durationInSeconds(600f).build(),
                LanguageCodeItem.builder().languageCode("en-IN").durationInSeconds(2400f).build()).build());
        when(storage.open(any())).thenReturn(new ByteArrayInputStream("{\"results\":{\"items\":[]}}".getBytes(StandardCharsets.UTF_8)));

        assertThat(stt.status(REF).language()).isEqualTo("en-IN");
    }

    @Test
    void aMalformedReferenceFailsInsteadOfPollingForever() {
        assertThat(stt.status("no-separator").state()).isEqualTo(State.FAILED);
    }
}
