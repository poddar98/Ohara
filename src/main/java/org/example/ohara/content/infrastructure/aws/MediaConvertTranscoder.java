package org.example.ohara.content.infrastructure.aws;

import org.example.ohara.content.application.Ladder;
import org.example.ohara.content.application.Transcoder;
import software.amazon.awssdk.services.mediaconvert.MediaConvertClient;
import software.amazon.awssdk.services.mediaconvert.model.AacCodingMode;
import software.amazon.awssdk.services.mediaconvert.model.AudioCodec;
import software.amazon.awssdk.services.mediaconvert.model.AudioDefaultSelection;
import software.amazon.awssdk.services.mediaconvert.model.AudioDescription;
import software.amazon.awssdk.services.mediaconvert.model.AudioSelector;
import software.amazon.awssdk.services.mediaconvert.model.ContainerType;
import software.amazon.awssdk.services.mediaconvert.model.CreateJobRequest;
import software.amazon.awssdk.services.mediaconvert.model.H264CodecProfile;
import software.amazon.awssdk.services.mediaconvert.model.H264GopSizeUnits;
import software.amazon.awssdk.services.mediaconvert.model.H264RateControlMode;
import software.amazon.awssdk.services.mediaconvert.model.H264SceneChangeDetect;
import software.amazon.awssdk.services.mediaconvert.model.HlsDirectoryStructure;
import software.amazon.awssdk.services.mediaconvert.model.HlsManifestDurationFormat;
import software.amazon.awssdk.services.mediaconvert.model.HlsSegmentControl;
import software.amazon.awssdk.services.mediaconvert.model.Input;
import software.amazon.awssdk.services.mediaconvert.model.InputRotate;
import software.amazon.awssdk.services.mediaconvert.model.MediaConvertException;
import software.amazon.awssdk.services.mediaconvert.model.NotFoundException;
import software.amazon.awssdk.services.mediaconvert.model.Output;
import software.amazon.awssdk.services.mediaconvert.model.OutputGroup;
import software.amazon.awssdk.services.mediaconvert.model.OutputGroupType;
import software.amazon.awssdk.services.mediaconvert.model.ProbeResult;
import software.amazon.awssdk.services.mediaconvert.model.ScalingBehavior;
import software.amazon.awssdk.services.mediaconvert.model.Track;
import software.amazon.awssdk.services.mediaconvert.model.TrackType;
import software.amazon.awssdk.services.mediaconvert.model.VideoCodec;
import software.amazon.awssdk.services.mediaconvert.model.VideoProperties;

import java.util.Map;
import java.util.Optional;

/**
 * {@link Transcoder} on AWS Elemental MediaConvert (docs 06 §4). Probe uses MediaConvert's Probe API (container,
 * duration, video size and rotation). Each job writes one HLS group at {@code outputPrefix}: {@code master.m3u8} plus
 * one variant playlist per rung ({@code master_<height>p.m3u8}) and its 6-second segments, H.264 QVBR capped at the
 * rung's max bitrate, AAC stereo. Only the height is set, so the source aspect ratio is kept (4:3 lectures aren't
 * stretched); the ladder never upscales. The asset id travels as job metadata for the console and cost reports.
 */
public class MediaConvertTranscoder implements Transcoder {

    static final String AUDIO_SELECTOR = "Audio Selector 1";

    private final MediaConvertClient mediaConvert;
    private final String sourceBucket;
    private final String deliveryBucket;
    private final String roleArn;
    private final String queueArn;
    private final int qvbrQuality;

    public MediaConvertTranscoder(MediaConvertClient mediaConvert, String sourceBucket, String deliveryBucket, String roleArn,
                                  String queueArn, int qvbrQuality) {
        this.mediaConvert = mediaConvert;
        this.sourceBucket = sourceBucket;
        this.deliveryBucket = deliveryBucket;
        this.roleArn = roleArn;
        this.queueArn = queueArn == null || queueArn.isBlank() ? null : queueArn;
        this.qvbrQuality = qvbrQuality;
    }

    String s3Uri(String key) {
        return "s3://" + (key.startsWith(S3ObjectStorage.DELIVERY_PREFIX) ? deliveryBucket : sourceBucket) + "/" + key;
    }

    @Override
    public MediaInfo probe(String sourceKey) {
        ProbeResult result;
        try {
            result = mediaConvert.probe(r -> r.inputFiles(f -> f.fileUrl(s3Uri(sourceKey)))).probeResults().stream()
                .findFirst().orElse(null);
        } catch (MediaConvertException e) {
            if (e.statusCode() >= 400 && e.statusCode() < 500 && e.statusCode() != 429) {
                return MediaInfo.invalid("The file couldn't be read as media: " + e.awsErrorDetails().errorMessage());
            }
            throw e; // throttling or a service error: the job retries
        }
        if (result == null || result.container() == null) {
            return MediaInfo.invalid("Not a recognised media container");
        }
        Optional<VideoProperties> video = result.container().tracks().stream()
            .filter(t -> t.trackType() == TrackType.VIDEO && t.videoProperties() != null)
            .map(Track::videoProperties)
            .findFirst();
        if (video.isEmpty() || video.get().width() == null || video.get().height() == null) {
            return MediaInfo.invalid("The file has no video track");
        }
        Double seconds = result.container().duration();
        if (seconds == null || seconds <= 0) {
            return MediaInfo.invalid("The video has no duration");
        }
        int rotation = video.get().rotation() == null ? 0 : Math.floorMod(video.get().rotation(), 360);
        boolean sideways = rotation == 90 || rotation == 270;
        int width = sideways ? video.get().height() : video.get().width();
        int height = sideways ? video.get().width() : video.get().height();
        return new MediaInfo(true, null, Math.round(seconds * 1000), width, height);
    }

    @Override
    public String submit(Job job) {
        CreateJobRequest.Builder request = CreateJobRequest.builder()
            .role(roleArn)
            .userMetadata(Map.of("outputPrefix", job.outputPrefix()))
            .settings(s -> s
                .inputs(Input.builder()
                    .fileInput(s3Uri(job.sourceKey()))
                    .audioSelectors(Map.of(AUDIO_SELECTOR, AudioSelector.builder().defaultSelection(AudioDefaultSelection.DEFAULT).build()))
                    .videoSelector(v -> v.rotate(InputRotate.AUTO))
                    .build())
                .outputGroups(OutputGroup.builder()
                    .name("HLS")
                    .outputGroupSettings(g -> g
                        .type(OutputGroupType.HLS_GROUP_SETTINGS)
                        .hlsGroupSettings(h -> h
                            .destination(s3Uri(job.outputPrefix() + "master"))
                            .segmentLength(6)
                            .minSegmentLength(0)
                            .segmentControl(HlsSegmentControl.SEGMENTED_FILES)
                            .directoryStructure(HlsDirectoryStructure.SINGLE_DIRECTORY)
                            .manifestDurationFormat(HlsManifestDurationFormat.FLOATING_POINT)))
                    .outputs(job.ladder().stream().map(this::output).toList())
                    .build()));
        if (queueArn != null) {
            request.queue(queueArn);
        }
        return mediaConvert.createJob(request.build()).job().id();
    }

    private Output output(Ladder.Rung rung) {
        return Output.builder()
            .nameModifier("_" + rung.name())
            .containerSettings(c -> c.container(ContainerType.M3_U8))
            .videoDescription(v -> v
                .height(rung.height())
                .scalingBehavior(ScalingBehavior.DEFAULT)
                .codecSettings(c -> c
                    .codec(VideoCodec.H_264)
                    .h264Settings(h -> h
                        .rateControlMode(H264RateControlMode.QVBR)
                        .qvbrSettings(q -> q.qvbrQualityLevel(qvbrQuality))
                        .maxBitrate(rung.maxKbps() * 1000)
                        .codecProfile(H264CodecProfile.MAIN)
                        .gopSize(2.0)
                        .gopSizeUnits(H264GopSizeUnits.SECONDS)
                        .sceneChangeDetect(H264SceneChangeDetect.TRANSITION_DETECTION))))
            .audioDescriptions(AudioDescription.builder()
                .audioSourceName(AUDIO_SELECTOR)
                .codecSettings(c -> c
                    .codec(AudioCodec.AAC)
                    .aacSettings(a -> a.bitrate(rung.audioKbps() * 1000).codingMode(AacCodingMode.CODING_MODE_2_0).sampleRate(48000)))
                .build())
            .build();
    }

    @Override
    public JobStatus status(String jobRef) {
        software.amazon.awssdk.services.mediaconvert.model.Job job;
        try {
            job = mediaConvert.getJob(r -> r.id(jobRef)).job();
        } catch (NotFoundException gone) {
            return new JobStatus(State.FAILED, "MediaConvert job " + jobRef + " not found");
        }
        return switch (job.status()) {
            case COMPLETE -> new JobStatus(State.COMPLETE, null);
            case ERROR -> new JobStatus(State.FAILED, "MediaConvert error " + job.errorCode() + ": " + job.errorMessage());
            case CANCELED -> new JobStatus(State.FAILED, "MediaConvert job was cancelled");
            default -> new JobStatus(State.PENDING, null);
        };
    }
}
