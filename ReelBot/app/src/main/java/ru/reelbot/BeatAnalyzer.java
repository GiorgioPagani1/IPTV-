package ru.reelbot;

import android.content.Context;
import android.media.AudioFormat;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * On-device beat estimator: decodes the selected audio and searches for repeating
 * transient-energy accents. No music upload or external service is used.
 * It is approximate; changing-tempo/live music may not align perfectly.
 */
public final class BeatAnalyzer {
    private static final int HOP_MS = 20;

    public static final class Result {
        public final List<Long> beatsMs;
        public final int estimatedBpm;
        public final String warning;
        private Result(List<Long> beats, int bpm, String warning) {
            this.beatsMs = beats;
            this.estimatedBpm = bpm;
            this.warning = warning;
        }
        static Result unavailable(String warning) {
            return new Result(Collections.emptyList(), 0, warning);
        }
    }

    private BeatAnalyzer() {}

    public static Result analyze(Context context, Uri audioUri, long targetDurationMs) {
        MediaExtractor extractor = new MediaExtractor();
        MediaCodec decoder = null;
        try {
            extractor.setDataSource(context, audioUri, null);
            int track = -1;
            MediaFormat inputFormat = null;
            String mime = null;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat test = extractor.getTrackFormat(i);
                String type = test.getString(MediaFormat.KEY_MIME);
                if (type != null && type.startsWith("audio/")) {
                    track = i;
                    inputFormat = test;
                    mime = type;
                    break;
                }
            }
            if (track < 0 || mime == null) return Result.unavailable("В выбранном файле нет аудиодорожки");
            extractor.selectTrack(track);
            decoder = MediaCodec.createDecoderByType(mime);
            decoder.configure(inputFormat, null, null, 0);
            decoder.start();

            long capped = Math.min(65_000, targetDurationMs + 1000);
            int bins = (int)(capped / HOP_MS) + 2;
            double[] values = new double[bins];
            int[] counts = new int[bins];
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean inputEos = false;
            boolean outputEos = false;
            int idle = 0;
            int rate = inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE);
            int channels = inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
            int encoding = AudioFormat.ENCODING_PCM_16BIT;

            while (!outputEos && idle < 35) {
                if (!inputEos) {
                    int index = decoder.dequeueInputBuffer(10_000);
                    if (index >= 0) {
                        ByteBuffer input = decoder.getInputBuffer(index);
                        if (input == null) throw new IllegalStateException("Audio input buffer missing");
                        input.clear();
                        int length = extractor.readSampleData(input, 0);
                        long timestamp = extractor.getSampleTime();
                        if (length < 0 || timestamp < 0 || timestamp > capped * 1000) {
                            decoder.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputEos = true;
                        } else {
                            decoder.queueInputBuffer(index, 0, length, timestamp, 0);
                            extractor.advance();
                        }
                    }
                }
                int output = decoder.dequeueOutputBuffer(info, 10_000);
                if (output == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat resultFormat = decoder.getOutputFormat();
                    if (resultFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE))
                        rate = resultFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                    if (resultFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT))
                        channels = resultFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                    if (resultFormat.containsKey(MediaFormat.KEY_PCM_ENCODING))
                        encoding = resultFormat.getInteger(MediaFormat.KEY_PCM_ENCODING);
                } else if (output >= 0) {
                    idle = 0;
                    ByteBuffer buffer = decoder.getOutputBuffer(output);
                    if (buffer != null && info.size > 0 && info.presentationTimeUs >= 0) {
                        accumulate(buffer, info, Math.max(rate,1), Math.max(channels,1),
                            encoding, values, counts);
                    }
                    decoder.releaseOutputBuffer(output, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0)
                        outputEos = true;
                } else if (inputEos) {
                    idle++;
                }
            }
            return detect(values, counts, targetDurationMs);
        } catch (Exception error) {
            return Result.unavailable("Не получилось определить ритм: " + error.getClass().getSimpleName());
        } finally {
            if (decoder != null) {
                try { decoder.stop(); } catch (Exception ignored) {}
                try { decoder.release(); } catch (Exception ignored) {}
            }
            extractor.release();
        }
    }

    private static void accumulate(ByteBuffer raw, MediaCodec.BufferInfo info,
                                   int rate, int channels, int encoding,
                                   double[] values, int[] counts) {
        boolean floating = encoding == AudioFormat.ENCODING_PCM_FLOAT;
        if (!floating && encoding != AudioFormat.ENCODING_PCM_16BIT) return;
        int bytesPerSample = floating ? 4 : 2;
        int frameWidth = bytesPerSample * channels;
        if (frameWidth <= 0) return;
        ByteBuffer pcm = raw.duplicate().order(ByteOrder.LITTLE_ENDIAN);
        pcm.position(info.offset);
        pcm.limit(info.offset + info.size);
        int frames = info.size / frameWidth;
        for (int f = 0; f < frames; f++) {
            int bin = (int)((info.presentationTimeUs + f * 1_000_000L / rate) / (HOP_MS * 1000L));
            if (bin < 0 || bin >= values.length) break;
            double amplitude = 0;
            for (int ch = 0; ch < channels; ch++) {
                if (floating) amplitude += Math.abs(pcm.getFloat());
                else amplitude += Math.abs((int)pcm.getShort()) / 32768.0;
            }
            values[bin] += amplitude / channels;
            counts[bin]++;
        }
    }

    static Result detect(double[] values, int[] counts, long targetMs) {
        int n = Math.min(values.length, (int)(targetMs / HOP_MS) + 1);
        if (n < 90) return Result.unavailable("Аудиодорожка слишком короткая для анализа ритма");
        double[] energy = new double[n];
        for (int i = 0; i < n; i++) energy[i] = counts[i] > 0 ? values[i] / counts[i] : 0;

        double[] onset = new double[n];
        double total = 0;
        for (int i = 5; i < n; i++) {
            double previous = 0;
            for (int k = 1; k <= 5; k++) previous += energy[i-k];
            previous /= 5.0;
            onset[i] = Math.max(0, energy[i] - previous);
            total += onset[i];
        }
        if (total < 0.15) return Result.unavailable("В музыке недостаточно выраженных ударов");

        int bestLag = -1;
        double bestScore = 0;
        // Approximately 80-188 BPM; estimates half-/double-time for other tempos.
        for (int lag = 16; lag <= 38; lag++) {
            double score = 0;
            for (int i = 6; i + lag < n; i++)
                score += onset[i] * onset[i + lag];
            if (score > bestScore) { bestScore = score; bestLag = lag; }
        }
        if (bestLag < 0 || bestScore < 0.0005)
            return Result.unavailable("Не удалось найти устойчивый музыкальный пульс");

        int phase = 0;
        double strongest = -1;
        for (int offset = 0; offset < bestLag; offset++) {
            double score = 0;
            for (int i = offset; i < n; i += bestLag) score += onset[i];
            if (score > strongest) { strongest = score; phase = offset; }
        }
        ArrayList<Long> beats = new ArrayList<>();
        long last = -500;
        for (int i = phase; i < n; i += bestLag) {
            int chosen = i;
            double strongestLocal = 0;
            for (int j = Math.max(0,i-2); j <= Math.min(n-1,i+2); j++) {
                if (onset[j] > strongestLocal) { strongestLocal=onset[j];chosen=j; }
            }
            long position = chosen * HOP_MS;
            if (position - last >= 250 && position < targetMs) {
                beats.add(position);last = position;
            }
        }
        return new Result(beats, Math.round(60_000f / (bestLag * HOP_MS)), null);
    }
}
