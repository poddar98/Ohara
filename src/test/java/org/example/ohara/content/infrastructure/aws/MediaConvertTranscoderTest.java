package org.example.ohara.content.infrastructure.aws;

import org.example.ohara.content.application.Ladder;
import org.example.ohara.content.application.Transcoder;
import org.example.ohara.content.domain.ContentAsset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.services.mediaconvert.MediaConvertClient;
import software.amazon.awssdk.services.mediaconvert.model.BadRequestException;
import software.amazon.awssdk.services.mediaconvert.model.Container;
import software.amazon.awssdk.services.mediaconvert.model.CreateJobRequest;
import software.amazon.awssdk.services.mediaconvert.model.CreateJobResponse;
import software.amazon.awssdk.services.mediaconvert.model.GetJobRequest;
import software.amazon.awssdk.services.mediaconvert.model.GetJobResponse;
import software.amazon.awssdk.services.mediaconvert.model.H264Settings;
import software.amazon.awssdk.services.mediaconvert.model.Job;
import software.amazon.awssdk.services.mediaconvert.model.JobStatus;
import software.amazon.awssdk.services.mediaconvert.model.Output;
import software.amazon.awssdk.services.mediaconvert.model.ProbeRequest;
import software.amazon.awssdk.services.mediaconvert.model.ProbeResponse;
import software.amazon.awssdk.services.mediaconvert.model.ProbeResult;
import software.amazon.awssdk.services.mediaconvert.model.TooManyRequestsException;
import software.amazon.awssdk.services.mediaconvert.model.Track;
import software.amazon.awssdk.services.mediaconvert.model.TrackType;
import software.amazon.awssdk.services.mediaconvert.model.VideoProperties;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.withSettings;

/** What the adapter sends to MediaConvert and how it reads the answers. A dev-account smoke test covers the real service. */
class MediaConvertTranscoderTest {

    MediaConvertClient client;
    MediaConvertTranscoder transcoder;

    @BeforeEach
    void setUp() {
        // SDK clients are interfaces of default methods; the consumer-style overloads delegate to the stubbed ones.
        client = mock(MediaConvertClient.class, withSettings().defaultAnswer(CALLS_REAL_METHODS));
        transcoder = new MediaConvertTranscoder(client, "ohara-source", "ohara-delivery",
            "arn:aws:iam::123456789012:role/ohara-mediaconvert", "arn:aws:mediaconvert:ap-south-1:123456789012:queues/ohara", 7);
    }

    private void probeReturns(Double seconds, Track... tracks) {
        doReturn(ProbeResponse.builder().probeResults(ProbeResult.builder()
                .container(Container.builder().duration(seconds).tracks(tracks).build()).build()).build())
            .when(client).probe(any(ProbeRequest.class));
    }

    private static Track video(int width, int height, Integer rotation) {
        return Track.builder().trackType(TrackType.VIDEO)
            .videoProperties(VideoProperties.builder().width(width).height(height).rotation(rotation).build()).build();
    }

    @Test
    void probeReadsDurationAndUprightSize() {
        probeReturns(3600.5, Track.builder().trackType(TrackType.AUDIO).build(), video(1920, 1080, 0));
        Transcoder.MediaInfo info = transcoder.probe("org/o1/asset/a1/source/lecture.mp4");
        assertThat(info).isEqualTo(new Transcoder.MediaInfo(true, null, 3_600_500, 1920, 1080));

        ArgumentCaptor<ProbeRequest> request = ArgumentCaptor.forClass(ProbeRequest.class);
        verify(client).probe(request.capture());
        assertThat(request.getValue().inputFiles().getFirst().fileUrl()).isEqualTo("s3://ohara-source/org/o1/asset/a1/source/lecture.mp4");

        probeReturns(60.0, video(1920, 1080, 90)); // a phone recording held upright
        assertThat(transcoder.probe("k")).extracting(Transcoder.MediaInfo::width, Transcoder.MediaInfo::height).containsExactly(1080, 1920);
    }

    @Test
    void unusableFilesAreRejectedButServiceTroubleIsRetried() {
        probeReturns(30.0, Track.builder().trackType(TrackType.AUDIO).build());
        assertThat(transcoder.probe("k").valid()).isFalse();

        doThrow(BadRequestException.builder().statusCode(400)
                .awsErrorDetails(AwsErrorDetails.builder().errorMessage("Unsupported file").build()).build())
            .when(client).probe(any(ProbeRequest.class));
        assertThat(transcoder.probe("k").reason()).contains("Unsupported file");

        doThrow(TooManyRequestsException.builder().statusCode(429).build()).when(client).probe(any(ProbeRequest.class));
        assertThatThrownBy(() -> transcoder.probe("k")).isInstanceOf(TooManyRequestsException.class);
    }

    @Test
    void aJobEncodesTheLadderIntoOneHlsGroupInTheDeliveryBucket() {
        doReturn(CreateJobResponse.builder().job(Job.builder().id("1730000000000-abc123").build()).build())
            .when(client).createJob(any(CreateJobRequest.class));
        List<Ladder.Rung> ladder = Ladder.forSource(1920, 1080, ContentAsset.ContentProfile.SLIDES);

        String ref = transcoder.submit(new Transcoder.Job("org/o1/asset/a1/source/lecture.mp4",
            "delivery/org/o1/asset/a1/g1/hls/", ladder, 3_600_000));

        assertThat(ref).isEqualTo("1730000000000-abc123");
        ArgumentCaptor<CreateJobRequest> captured = ArgumentCaptor.forClass(CreateJobRequest.class);
        verify(client).createJob(captured.capture());
        CreateJobRequest job = captured.getValue();
        assertThat(job.role()).endsWith("role/ohara-mediaconvert");
        assertThat(job.queue()).endsWith("queues/ohara");
        assertThat(job.settings().inputs().getFirst().fileInput()).isEqualTo("s3://ohara-source/org/o1/asset/a1/source/lecture.mp4");
        var group = job.settings().outputGroups().getFirst();
        assertThat(group.outputGroupSettings().hlsGroupSettings().destination())
            .isEqualTo("s3://ohara-delivery/delivery/org/o1/asset/a1/g1/hls/master");
        assertThat(group.outputGroupSettings().hlsGroupSettings().segmentLength()).isEqualTo(6);

        // Slides top out at 720p: two rungs, never upscaled, aspect ratio kept (no width).
        assertThat(group.outputs()).extracting(Output::nameModifier).containsExactly("_720p", "_480p");
        Output top = group.outputs().getFirst();
        assertThat(top.videoDescription().height()).isEqualTo(720);
        assertThat(top.videoDescription().width()).isNull();
        H264Settings h264 = top.videoDescription().codecSettings().h264Settings();
        assertThat(h264.rateControlModeAsString()).isEqualTo("QVBR");
        assertThat(h264.maxBitrate()).isEqualTo(2_000_000);
        assertThat(h264.qvbrSettings().qvbrQualityLevel()).isEqualTo(7);
        assertThat(top.audioDescriptions().getFirst().codecSettings().aacSettings().bitrate()).isEqualTo(96_000);
        assertThat(top.audioDescriptions().getFirst().audioSourceName()).isEqualTo(MediaConvertTranscoder.AUDIO_SELECTOR);
    }

    @Test
    void jobStatesMapToThePipelinesStates() {
        for (var c : List.of(
                new Object[] {JobStatus.SUBMITTED, Transcoder.State.PENDING},
                new Object[] {JobStatus.PROGRESSING, Transcoder.State.PENDING},
                new Object[] {JobStatus.COMPLETE, Transcoder.State.COMPLETE},
                new Object[] {JobStatus.ERROR, Transcoder.State.FAILED},
                new Object[] {JobStatus.CANCELED, Transcoder.State.FAILED})) {
            doReturn(GetJobResponse.builder().job(Job.builder().id("j").status((JobStatus) c[0]).errorCode(1030)
                    .errorMessage("Unable to open input file").build()).build())
                .when(client).getJob(any(GetJobRequest.class));
            assertThat(transcoder.status("j").state()).isEqualTo(c[1]);
        }
        assertThat(transcoder.status("j").failure()).isEqualTo("MediaConvert job was cancelled");
        doReturn(GetJobResponse.builder().job(Job.builder().id("j").status(JobStatus.ERROR).errorCode(1030)
                .errorMessage("Unable to open input file").build()).build())
            .when(client).getJob(any(GetJobRequest.class));
        assertThat(transcoder.status("j").failure()).contains("1030").contains("Unable to open input file");
    }
}
