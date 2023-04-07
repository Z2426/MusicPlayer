/*
 * Copyright (C) 2015 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.ldt.musicr.ui.widget.avsb;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;
import java.util.Arrays;

import android.content.res.AssetFileDescriptor;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;

import com.ldt.musicr.App;
import com.ldt.musicr.model.Song;

public class SoundFile {
    private ProgressListener mProgressListener = null;
    private AssetFileDescriptor mInputFile = null;

    // Member variables representing frame data
    private String mFileType;
    private int mFileSize;
    private int mAvgBitRate;  // Average bit rate in kbps.
    private int mSampleRate;
    private int mChannels;
    private int mNumSamples;  // total number of samples per channel in audio file
    private ByteBuffer mDecodedBytes;  // Raw audio data
    private ShortBuffer mDecodedSamples;  // shared buffer with mDecodedBytes.
    // mDecodedSamples has the following format:
    // {s1c1, s1c2, ..., s1cM, s2c1, ..., s2cM, ..., sNc1, ..., sNcM}
    // where sicj is the ith sample of the jth channel (a sample is a signed short)
    // M is the number of channels (e.g. 2 for stereo) and N is the number of samples per channel.

    // Member variables for hack (making it work with old version, until app just uses the samples).
    private int mNumFrames;
    private int[] mFrameGains;
    private int[] mFrameLens;
    private int[] mFrameOffsets;

    // TODO(nfaralli): what is the real list of supported extensions? Is it device dependent?
    public static String[] getSupportedExtensions() {
        return new String[] {"mp3", "wav", "3gpp", "3gp", "amr", "aac", "m4a", "ogg"};
    }

    public static boolean isFilenameSupported(String filename) {
        String[] extensions = getSupportedExtensions();
        for (int i=0; i<extensions.length; i++) {
            if (filename.endsWith("." + extensions[i])) {
                return true;
            }
        }
        return false;
    }

    public static SoundFile create(Song song, ProgressListener progressListener)
            throws
            java.io.IOException, InvalidInputException {
        return create(song.id, song.data, progressListener);
    }

    // Create and return a SoundFile object using the file fileName.
    public static SoundFile create(long audioId, String dataColumn,
                                   ProgressListener progressListener)
        throws
            java.io.IOException, InvalidInputException {

        Uri uri = Uri.withAppendedPath(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, String.valueOf(audioId));
        AssetFileDescriptor file;

        // will throw FileNotFoundException if file does not exist
        file = App.getInstance().getContentResolver().openAssetFileDescriptor(uri, "r");

        String name = dataColumn.toLowerCase();
        String[] components = name.split("\\.");
        if (components.length < 2) {
            return null;
        }
        if (!Arrays.asList(getSupportedExtensions()).contains(components[components.length - 1])) {
            return null;
        }
        SoundFile soundFile = new SoundFile();
        soundFile.setProgressListener(progressListener);
        soundFile.parseFile(uri, file, dataColumn);
        return soundFile;
    }

    // Create and return a SoundFile object by recording a mono audio stream.
    public static SoundFile record(ProgressListener progressListener) {
        if (progressListener ==  null) {
            // must have a progessListener to stop the recording.
            return null;
        }
        SoundFile soundFile = new SoundFile();
        soundFile.setProgressListener(progressListener);
        soundFile.RecordAudio();
        return soundFile;
    }

    public String getFiletype() {
        return mFileType;
    }

    public int getFileSizeBytes() {
        return mFileSize;
    }

    public int getAvgBitrateKbps() {
        return mAvgBitRate;
    }

    public int getSampleRate() {
        return mSampleRate;
    }

    public int getChannels() {
        return mChannels;
    }

    public int getNumSamples() {
        return mNumSamples;  // Number of samples per channel.
    }

    // Should be removed when the app will use directly the samples instead of the frames.
    public int getNumFrames() {
        return mNumFrames;
    }

    // Should be removed when the app will use directly the samples instead of the frames.
    public int getSamplesPerFrame() {
        return 1024;  // just a fixed value here...
    }

    // Should be removed when the app will use directly the samples instead of the frames.
    public int[] getFrameGains() {
        return mFrameGains;
    }

    public ShortBuffer getSamples() {
        if (mDecodedSamples != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N &&
                Build.VERSION.SDK_INT <= Build.VERSION_CODES.N_MR1) {
                // Hack for Nougat where asReadOnlyBuffer fails to respect byte ordering.
                // See https://code.google.com/p/android/issues/detail?id=223824
                return mDecodedSamples;
            } else {
                return mDecodedSamples.asReadOnlyBuffer();
            }
        } else {
            return null;
        }
    }

    // A SoundFile object should only be created using the static methods create() and record().
    private SoundFile() {
    }

    private void setProgressListener(ProgressListener progressListener) {
        mProgressListener = progressListener;
    }

    private void parseFile(Uri uri, AssetFileDescriptor inputFile, String dataColumn)
        throws
            java.io.IOException, InvalidInputException {
        MediaExtractor extractor = new MediaExtractor();
        MediaFormat format = null;
        int i;

        mInputFile = inputFile;
        String[] components = dataColumn.split("\\.");
        mFileType = components[components.length - 1];
        mFileSize = (int)mInputFile.getLength();
        extractor.setDataSource(App.getInstance(), uri, null);
        int numTracks = extractor.getTrackCount();
        // find and select the first audio track present in the file.
        for (i=0; i<numTracks; i++) {
            format = extractor.getTrackFormat(i);
            if (format.getString(MediaFormat.KEY_MIME).startsWith("audio/")) {
                extractor.selectTrack(i);
                break;
            }
        }
        if (i == numTracks) {
            throw new InvalidInputException("No audio track found in " + mInputFile);
        }
        mChannels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
        mSampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE);
        // Expected total number of samples per channel.
        int expectedNumSamples =
            (int)((format.getLong(MediaFormat.KEY_DURATION) / 1000000.f) * mSampleRate + 0.5f);

        MediaCodec codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME));
        codec.configure(format, null, null, 0);
        codec.start();

        int decodedSamplesSize = 0;  // size of the output buffer containing decoded samples.
        byte[] decodedSamples = null;
        ByteBuffer[] inputBuffers = codec.getInputBuffers();
        ByteBuffer[] outputBuffers = codec.getOutputBuffers();
        int sample_size;
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        long presentation_time;
        int tot_size_read = 0;
        boolean done_reading = false;

        // Set the size of the decoded samples buffer to 1MB (~6sec of a stereo stream at 44.1kHz).
        // For longer streams, the buffer size will be increased later on, calculating a rough
        // estimate of the total size needed to store all the samples in order to resize the buffer
        // only once.
        mDecodedBytes = ByteBuffer.allocate(1<<20);
        Boolean firstSampleData = true;
        while (true) {
            // read data from file and feed it to the decoder input buffers.
            int inputBufferIndex = codec.dequeueInputBuffer(100);
            if (!done_reading && inputBufferIndex >= 0) {
                sample_size = extractor.readSampleData(inputBuffers[inputBufferIndex], 0);
                if (firstSampleData
                        && format.getString(MediaFormat.KEY_MIME).equals("audio/mp4a-latm")
                        && sample_size == 2) {
                    // For some reasons on some devices (e.g. the Samsung S3) you should not
                    // provide the first two bytes of an AAC stream, otherwise the MediaCodec will
                    // crash. These two bytes do not contain music data but basic info on the
                    // stream (e.g. channel configuration and sampling frequency), and skipping them
                    // seems OK with other devices (MediaCodec has already been configured and
                    // already knows these parameters).
                    extractor.advance();
                    tot_size_read += sample_size;
                } else if (sample_size < 0) {
                    // All samples have been read.
                    codec.queueInputBuffer(
                            inputBufferIndex, 0, 0, -1, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                    done_reading = true;
                } else {
                    presentation_time = extractor.getSampleTime();
                    codec.queueInputBuffer(inputBufferIndex, 0, sample_size, presentation_time, 0);
                    extractor.advance();
                    tot_size_read += sample_size;
                    if (mProgressListener != null) {
                        if (!mProgressListener.reportProgress((float)(tot_size_read) / mFileSize)) {
                            // We are asked to stop reading the file. Returning immediately. The
                            // SoundFile object is invalid and should NOT be used afterward!
                            extractor.release();
                            extractor = null;
                            codec.stop();
                            codec.release();
                            codec = null;
                            return;
                        }
                    }
                }
                firstSampleData = false;
            }

            // Get decoded stream from the decoder output buffers.
            int outputBufferIndex = codec.dequeueOutputBuffer(info, 100);
            if (outputBufferIndex >= 0 && info.size > 0) {
                if (decodedSamplesSize < info.size) {
                    decodedSamplesSize = info.size;
                    decodedSamples = new byte[decodedSamplesSize];
                }
                outputBuffers[outputBufferIndex].get(decodedSamples, 0, info.size);
                outputBuffers[outputBufferIndex].clear();
                // Check if buffer is big enough. Resize it if it's too small.
                if (mDecodedBytes.remaining() < info.size) {
                    // Getting a rough estimate of the total size, allocate 20% more, and
                    // make sure to allocate at least 5MB more than the initial size.
                    int position = mDecodedBytes.position();
                    int newSize = (int)((position * (1.0 * mFileSize / tot_size_read)) * 1.2);
                    if (newSize - position < info.size + 5 * (1<<20)) {
                        newSize = position + info.size + 5 * (1<<20);
                    }
                    ByteBuffer newDecodedBytes = null;
                    // Try to allocate memory. If we are OOM, try to run the garbage collector.
                    int retry = 10;
                    while(retry > 0) {
                        try {
                            newDecodedBytes = ByteBuffer.allocate(newSize);
                            break;
                        } catch (OutOfMemoryError oome) {
                            // setting android:largeHeap="true" in <application> seem to help not
                            // reaching this section.
                            retry--;
                        }
                    }
                    if (retry == 0) {
                        // Failed to allocate memory... Stop reading more data and finalize the
                        // instance with the data decoded so far.
                        break;
                    }
                    //ByteBuffer newDecodedBytes = ByteBuffer.allocate(newSize);
                    mDecodedBytes.rewind();
                    newDecodedBytes.put(mDecodedBytes);
                    mDecodedBytes = newDecodedBytes;
                    mDecodedBytes.position(position);
                }
                mDecodedBytes.put(decodedSamples, 0, info.size);
                codec.releaseOutputBuffer(outputBufferIndex, false);
            } else if (outputBufferIndex == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                outputBuffers = codec.getOutputBuffers();
            } else if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                // Subsequent data will conform to new format.
                // We could check that codec.getOutputFormat(), which is the new output format,
                // is what we expect.
            }
            if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                    || (mDecodedBytes.position() / (2 * mChannels)) >= expectedNumSamples) {
                // We got all the decoded data from the decoder. Stop here.
                // Theoretically dequeueOutputBuffer(info, ...) should have set info.flags to
                // MediaCodec.BUFFER_FLAG_END_OF_STREAM. However some phones (e.g. Samsung S3)
                // won't do that for some files (e.g. with mono AAC files), in which case subsequent
                // calls to dequeueOutputBuffer may result in the application crashing, without
                // even an exception being thrown... Hence the second check.
                // (for mono AAC files, the S3 will actually double each sample, as if the stream
                // was stereo. The resulting stream is half what it's supposed to be and with a much
                // lower pitch.)
                break;
            }
        }
        mNumSamples = mDecodedBytes.position() / (mChannels * 2);  // One sample = 2 bytes.
        mDecodedBytes.rewind();
        mDecodedBytes.order(ByteOrder.LITTLE_ENDIAN);
        mDecodedSamples = mDecodedBytes.asShortBuffer();
        mAvgBitRate = (int)((mFileSize * 8) * ((float)mSampleRate / mNumSamples) / 1000);

        extractor.release();
        extractor = null;
        codec.stop();
        codec.release();
        codec = null;

        // Temporary hack to make it work with the old version.
        mNumFrames = mNumSamples / getSamplesPerFrame();
        if (mNumSamples % getSamplesPerFrame() != 0){
            mNumFrames++;
        }
        mFrameGains = new int[mNumFrames];
        mFrameLens = new int[mNumFrames];
        mFrameOffsets = new int[mNumFrames];
        int j;
        int gain, value;
        int frameLens = (int)((1000 * mAvgBitRate / 8) *
                ((float)getSamplesPerFrame() / mSampleRate));
        for (i=0; i<mNumFrames; i++){
            gain = -1;
            for(j=0; j<getSamplesPerFrame(); j++) {
                value = 0;
                for (int k=0; k<mChannels; k++) {
                    if (mDecodedSamples.remaining() > 0) {
                        value += java.lang.Math.abs(mDecodedSamples.get());
                    }
                }
                value /= mChannels;
                if (gain < value) {
                    gain = value;
                }
            }
            mFrameGains[i] = (int)Math.sqrt(gain);  // here gain = sqrt(max value of 1st channel)...
            mFrameLens[i] = frameLens;  // totally not accurate...
            mFrameOffsets[i] = (int)(i * (1000 * mAvgBitRate / 8) *  //  = i * frameLens
                    ((float)getSamplesPerFrame() / mSampleRate));
        }
        mDecodedSamples.rewind();
        // DumpSamples();  // Uncomment this line to dump the samples in a TSV file.
    }

    private void RecordAudio() {
        if (mProgressListener ==  null) {
            // A progress listener is mandatory here, as it will let us know when to stop recording.
            return;
        }
        mInputFile = null;
        mFileType = "raw";
        mFileSize = 0;
        mSampleRate = 44100;
        mChannels = 1;  // record mono audio.
        short[] buffer = new short[1024];  // buffer contains 1 mono frame of 1024 16 bits samples
        int minBufferSize = AudioRecord.getMinBufferSize(
                mSampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        // make sure minBufferSize can contain at least 1 second of audio (16 bits sample).
        if (minBufferSize < mSampleRate * 2) {
            minBufferSize = mSampleRate * 2;
        }
        AudioRecord audioRecord = new AudioRecord(
                MediaRecorder.AudioSource.DEFAULT,
                mSampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minBufferSize
                );

        // Allocate memory for 20 seconds first. Reallocate later if more is needed.
        mDecodedBytes = ByteBuffer.allocate(20 * mSampleRate * 2);
        mDecodedBytes.order(ByteOrder.LITTLE_ENDIAN);
        mDecodedSamples = mDecodedBytes.asShortBuffer();
        audioRecord.startRecording();
        while (true) {
            // check if mDecodedSamples can contain 1024 additional samples.
            if (mDecodedSamples.remaining() < 1024) {
                // Try to allocate memory for 10 additional seconds.
                int newCapacity = mDecodedBytes.capacity() + 10 * mSampleRate * 2;
                ByteBuffer newDecodedBytes = null;
                try {
                    newDecodedBytes = ByteBuffer.allocate(newCapacity);
                } catch (OutOfMemoryError oome) {
                    break;
                }
                int position = mDecodedSamples.position();
                mDecodedBytes.rewind();
                newDecodedBytes.put(mDecodedBytes);
                mDecodedBytes = newDecodedBytes;
                mDecodedBytes.order(ByteOrder.LITTLE_ENDIAN);
                mDecodedBytes.rewind();
                mDecodedSamples = mDecodedBytes.asShortBuffer();
                mDecodedSamples.position(position);
            }
            // TODO(nfaralli): maybe use the read method that takes a direct ByteBuffer argument.
            audioRecord.read(buffer, 0, buffer.length);
            mDecodedSamples.put(buffer);
            // Let the progress listener know how many seconds have been recorded.
            // The returned value tells us if we should keep recording or stop.
            if (!mProgressListener.reportProgress(
                    (float)(mDecodedSamples.position()) / mSampleRate)) {
                break;
            }
        }
        audioRecord.stop();
        audioRecord.release();
        mNumSamples = mDecodedSamples.position();
        mDecodedSamples.rewind();
        mDecodedBytes.rewind();
        mAvgBitRate = mSampleRate * 16 / 1000;

        // Temporary hack to make it work with the old version.
        mNumFrames = mNumSamples / getSamplesPerFrame();
        if (mNumSamples % getSamplesPerFrame() != 0){
            mNumFrames++;
        }
        mFrameGains = new int[mNumFrames];
        mFrameLens = null;  // not needed for recorded audio
        mFrameOffsets = null;  // not needed for recorded audio
        int i, j;
        int gain, value;
        for (i=0; i<mNumFrames; i++){
            gain = -1;
            for(j=0; j<getSamplesPerFrame(); j++) {
                if (mDecodedSamples.remaining() > 0) {
                    value = java.lang.Math.abs(mDecodedSamples.get());
                } else {
                    value = 0;
                }
                if (gain < value) {
                    gain = value;
                }
            }
            mFrameGains[i] = (int)Math.sqrt(gain);  // here gain = sqrt(max value of 1st channel)...
        }
        mDecodedSamples.rewind();
        // DumpSamples();  // Uncomment this line to dump the samples in a TSV file.
    }

    // should be removed in the near future...
    public void WriteFile(File outputFile, int startFrame, int numFrames)
            throws java.io.IOException {
        float startTime = (float)startFrame * getSamplesPerFrame() / mSampleRate;
        float endTime = (float)(startFrame + numFrames) * getSamplesPerFrame() / mSampleRate;
        WriteFile(outputFile, startTime, endTime);
    }

    public void WriteFile(File outputFile, float startTime, float endTime)
            throws java.io.IOException {
        int startOffset = (int)(startTime * mSampleRate) * 2 * mChannels;
        int numSamples = (int)((endTime - startTime) * mSampleRate);
        // Some devices have problems reading mono AAC files (e.g. Samsung S3). Making it stereo.
        int numChannels = (mChannels == 1) ? 2 : mChannels;

        String mimeType = "audio/mp4a-latm";
        int bitrate = 64000 * numChannels;  // rule of thumb for a good quality: 64kbps per channel.
        MediaCodec codec = MediaCodec.createEncoderByType(mimeType);
        MediaFormat format = MediaFormat.createAudioFormat(mimeType, mSampleRate, numChannels);
        format.setInteger(MediaFormat.KEY_BIT_RATE, bitrate);
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        codec.start();

        // Get an estimation of the encoded data based on the bitrate. Add 10% to it.
        int estimatedEncodedSize = (int)((endTime - startTime) * (bitrate / 8) * 1.1);
        ByteBuffer encodedBytes = ByteBuffer.allocate(estimatedEncodedSize);
        ByteBuffer[] inputBuffers = codec.getInputBuffers();
        ByteBuffer[] outputBuffers = codec.getOutputBuffers();
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        boolean done_reading = false;
        long presentation_time = 0;

        int frame_size = 1024;  // number of samples per frame per channel for an mp4 (AAC) stream.
        byte[] buffer = new byte[frame_size * numChannels * 2];  // a sample is coded with a short.
        mDecodedBytes.position(startOffset);
        numSamples += (2 * frame_size);  // Adding 2 frames, Cf. priming frames for AAC.
        int tot_num_frames = 1 + (numSamples / frame_size);  // first AAC frame = 2 bytes
        if (numSamples % frame_size != 0) {
            tot_num_frames++;
        }
        int[] frame_sizes = new int[tot_num_frames];
        int num_out_frames = 0;
        int num_frames=0;
        int num_samples_left = numSamples;
        int encodedSamplesSize = 0;  // size of the output buffer containing the encoded samples.
        byte[] encodedSamples = null;
        while (true) {
            // Feed the samples to the encoder.
            int inputBufferIndex = codec.dequeueInputBuffer(100);
            if (!done_reading && inputBufferIndex >= 0) {
                if (num_samples_left <= 0) {
                    // All samples have been read.
                    codec.queueInputBuffer(
                            inputBufferIndex, 0, 0, -1, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                    done_reading = true;
                } else {
                    inputBuffers[inputBufferIndex].clear();
                    if (buffer.length > inputBuffers[inputBufferIndex].remaining()) {
                        // Input buffer is smaller than one frame. This should never happen.
                        continue;
                    }
                    // bufferSize is a hack to create a stereo file from a mono stream.
                    int bufferSize = (mChannels == 1) ? (buffer.length / 2) : buffer.length;
                    if (mDecodedBytes.remaining() < bufferSize) {
                        for (int i=mDecodedBytes.remaining(); i < bufferSize; i++) {
                            buffer[i] = 0;  // pad with extra 0s to make a full frame.
                        }
                        mDecodedBytes.get(buffer, 0, mDecodedBytes.remaining());
                    } else {
                        mDecodedBytes.get(buffer, 0, bufferSize);
                    }
                    if (mChannels == 1) {
                        for (int i=bufferSize - 1; i >= 1; i -= 2) {
                            buffer[2*i + 1] = buffer[i];
                            buffer[2*i] = buffer[i-1];
                            buffer[2*i - 1] = buffer[2*i + 1];
                            buffer[2*i - 2] = buffer[2*i];
                        }
                    }
                    num_samples_left -= frame_size;
                    inputBuffers[inputBufferIndex].put(buffer);
                    presentation_time = (long) (((num_frames++) * frame_size * 1e6) / mSampleRate);
                    codec.queueInputBuffer(
                            inputBufferIndex, 0, buffer.length, presentation_time, 0);
                }
            }

            // Get the encoded samples from the encoder.
            int outputBufferIndex = codec.dequeueOutputBuffer(info, 100);
            if (outputBufferIndex >= 0 && info.size > 0 && info.presentationTimeUs >=0) {
                if (num_out_frames < frame_sizes.length) {
                    frame_sizes[num_out_frames++] = info.size;
                }
                if (encodedSamplesSize < info.size) {
                    encodedSamplesSize = info.size;
                    encodedSamples = new byte[encodedSamplesSize];
                }
                outputBuffers[outputBufferIndex].get(encodedSamples, 0, info.size);
                outputBuffers[outputBufferIndex].clear();
                codec.releaseOutputBuffer(outputBufferIndex, false);
                if (encodedBytes.remaining() < info.size) {  // Hopefully this should not happen.
                    estimatedEncodedSize = (int)(estimatedEncodedSize * 1.2);  // Add 20%.
                    ByteBuffer newEncodedBytes = ByteBuffer.allocate(estimatedEncodedSize);
                    int position = encodedBytes.position();
                    encodedBytes.rewind();
                    newEncodedBytes.put(encodedBytes);
                    encodedBytes = newEncodedBytes;
                    encodedBytes.position(position);
                }
                encodedBytes.put(encodedSamples, 0, info.size);
            } else if (outputBufferIndex == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                outputBuffers = codec.getOutputBuffers();
            } else if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                // Subsequent data will conform to new format.
                // We could check that codec.getOutputFormat(), which is the new output format,
                // is what we expect.
            }
            if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                // We got all the encoded data from the encoder.
                break;
            }
        }
        int encoded_size = encodedBytes.position();
        encodedBytes.rewind();
        codec.stop();
        codec.release();
        codec = null;

        // Write the encoded stream to the file, 4kB at a time.
        buffer = new byte[4096];
        try {
            FileOutputStream outputStream = new FileOutputStream(outputFile);
            outputStream.write(
                    MP4Header.getMP4Header(mSampleRate, numChannels, frame_sizes, bitrate));
            while (encoded_size - encodedBytes.position() > buffer.length) {
                encodedBytes.get(buffer);
                outputStream.write(buffer);
            }
            int remaining = encoded_size - encodedBytes.position();
            if (remaining > 0) {
                encodedBytes.get(buffer, 0, remaining);
                outputStream.write(buffer, 0, remaining);
            }
            outputStream.close();
        } catch (IOException e) {
            Log.e("Ringdroid", "Failed to create the .m4a file.");
            Log.e("Ringdroid", getStackTrace(e));
        }
    }

    // Method used to swap the left and right channels (needed for stereo WAV files).
    // buffer contains the PCM data: {sample 1 right, sample 1 left, sample 2 right, etc.}
    // The size of a sample is assumed to be 16 bits (for a single channel).
    // When done, buffer will contain {sample 1 left, sample 1 right, sample 2 left, etc.}
    private void swapLeftRightChannels(byte[] buffer) {
        byte[] left = new byte[2];
        byte[] right = new byte[2];
        if (buffer.length % 4 != 0) {  // 2 channels, 2 bytes per sample (for one channel).
            // Invalid buffer size.
            return;
        }
        for (int offset = 0; offset < buffer.length; offset += 4) {
            left[0] = buffer[offset];
            left[1] = buffer[offset + 1];
            right[0] = buffer[offset + 2];
            right[1] = buffer[offset + 3];
            buffer[offset] = right[0];
            buffer[offset + 1] = right[1];
            buffer[offset + 2] = left[0];
            buffer[offset + 3] = left[1];
        }
    }

    // should be removed in the near future...
    public void WriteWAVFile(File outputFile, int startFrame, int numFrames)
            throws java.io.IOException {
        float startTime = (float)startFrame * getSamplesPerFrame() / mSampleRate;
        float endTime = (float)(startFrame + numFrames) * getSamplesPerFrame() / mSampleRate;
        WriteWAVFile(outputFile, startTime, endTime);
    }

    public void WriteWAVFile(File outputFile, float startTime, float endTime)
            throws java.io.IOException {
        int startOffset = (int)(startTime * mSampleRate) * 2 * mChannels;
        int numSamples = (int)((endTime - startTime) * mSampleRate);

        // Start by writing the RIFF header.
        FileOutputStream outputStream = new FileOutputStream(outputFile);
        outputStream.write(WAVHeader.getWAVHeader(mSampleRate, mChannels, numSamples));

        // Write the samples to the file, 1024 at a time.
        byte[] buffer = new byte[1024 * mChannels * 2];  // Each sample is coded with a short.
        mDecodedBytes.position(startOffset);
        int numBytesLeft = numSamples * mChannels * 2;
        while (numBytesLeft >= buffer.length) {
            if (mDecodedBytes.remaining() < buffer.length) {
                // This should not happen.
                for (int i = mDecodedBytes.remaining(); i < buffer.length; i++) {
                    buffer[i] = 0;  // pad with extra 0s to make a full frame.
                }
                mDecodedBytes.get(buffer, 0, mDecodedBytes.remaining());
            } else {
                mDecodedBytes.get(buffer);
            }
            if (mChannels == 2) {
                swapLeftRightChannels(buffer);
            }
            outputStream.write(buffer);
            numBytesLeft -= buffer.length;
        }
        if (numBytesLeft > 0) {
            if (mDecodedBytes.remaining() < numBytesLeft) {
                // This should not happen.
                for (int i = mDecodedBytes.remaining(); i < numBytesLeft; i++) {
                    buffer[i] = 0;  // pad with extra 0s to make a full frame.
                }
                mDecodedBytes.get(buffer, 0, mDecodedBytes.remaining());
            } else {
                mDecodedBytes.get(buffer, 0, numBytesLeft);
            }
            if (mChannels == 2) {
                swapLeftRightChannels(buffer);
            }
            outputStream.write(buffer, 0, numBytesLeft);
        }
        outputStream.close();
    }

    // Debugging method dumping all the samples in mDecodedSamples in a TSV file.
    // Each row describes one sample and has the following format:
    // "<presentation time in seconds>\t<channel 1>\t...\t<channel N>\n"
    // File will be written on the SDCard under media/audio/debug/
    // If fileName is null or empty, then the default file name (samples.tsv) is used.
    private void DumpSamples(String fileName) {
        String externalRootDir = Environment.getExternalStorageDirectory().getPath();
        if (!externalRootDir.endsWith("/")) {
            externalRootDir += "/";
        }
        String parentDir = externalRootDir + "media/audio/debug/";
        // Create the parent directory
        File parentDirFile = new File(parentDir);
        parentDirFile.mkdirs();
        // If we can't write to that special path, try just writing directly to the SDCard.
        if (!parentDirFile.isDirectory()) {
            parentDir = externalRootDir;
        }
        if (fileName == null || fileName.isEmpty()) {
            fileName = "samples.tsv";
        }
        File outFile = new File(parentDir + fileName);

        // Start dumping the samples.
        BufferedWriter writer = null;
        float presentationTime = 0;
        mDecodedSamples.rewind();
        StringBuilder row;
        try {
            writer = new BufferedWriter(new FileWriter(outFile));
            for (int sampleIndex = 0; sampleIndex < mNumSamples; sampleIndex++) {
                presentationTime = (float)(sampleIndex) / mSampleRate;
                row = new StringBuilder(Float.toString(presentationTime));
                for (int channelIndex = 0; channelIndex < mChannels; channelIndex++) {
                    row.append("\t").append(mDecodedSamples.get());
                }
                row.append("\n");
                writer.write(row.toString());
            }
        } catch (IOException e) {
            Log.w("Ringdroid", "Failed to create the sample TSV file.");
            Log.w("Ringdroid", getStackTrace(e));
        }
        // We are done here. Close the file and rewind the buffer.
        try {
            writer.close();
        } catch (Exception e) {
            Log.w("Ringdroid", "Failed to close sample TSV file.");
            Log.w("Ringdroid", getStackTrace(e));
        }
        mDecodedSamples.rewind();
    }

    // Helper method (samples will be dumped in media/audio/debug/samples.tsv).
    private void DumpSamples() {
        DumpSamples(null);
    }

    // Return the stack trace of a given exception.
    private String getStackTrace(Exception e) {
        StringWriter writer = new StringWriter();
        e.printStackTrace(new PrintWriter(writer));
        return writer.toString();
    }
}