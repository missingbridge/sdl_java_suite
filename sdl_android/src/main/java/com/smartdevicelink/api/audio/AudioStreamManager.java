package com.smartdevicelink.api.audio;

import android.net.rtp.AudioStream;
import android.os.Build;
import android.support.annotation.IntDef;
import android.support.annotation.NonNull;
import android.support.annotation.RequiresApi;
import android.support.annotation.StringDef;
import android.util.Log;

import com.smartdevicelink.SdlConnection.SdlSession;
import com.smartdevicelink.protocol.enums.SessionType;
import com.smartdevicelink.proxy.interfaces.IAudioStreamListener;
import com.smartdevicelink.proxy.interfaces.ISdl;
import com.smartdevicelink.proxy.interfaces.ISdlServiceListener;
import com.smartdevicelink.proxy.rpc.enums.BitsPerSample;
import com.smartdevicelink.proxy.rpc.enums.SamplingRate;

import java.io.File;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.LinkedList;
import java.util.Queue;

@RequiresApi(api = Build.VERSION_CODES.JELLY_BEAN)
public class AudioStreamManager implements ISdlServiceListener {
    private static final String TAG = AudioStreamManager.class.getSimpleName();

    private ISdl sdlInterface;
    private IAudioStreamListener sdlAudioStream;
    private int sdlSampleRate;
    private @SampleType int sdlSampleType;
    private final Queue<BaseAudioDecoder> queue;
    private boolean didRequestShutdown = false;

    public AudioStreamManager(@NonNull ISdl sdlInterface, @NonNull @SamplingRate String sampleRate, @NonNull @BitsPerSample int sampleType) {
        this.sdlInterface = sdlInterface;
        this.queue = new LinkedList<>();

        switch (sampleRate) {
            case SamplingRate.EIGHT_KHZ:
                sdlSampleRate = 8000;
                break;
            case SamplingRate.SIXTEEN_KHZ:
                sdlSampleRate = 16000;
                break;
            case SamplingRate.TWENTY_TWO_KHZ:
                // common sample rate is 22050, not 22000
                // see https://en.wikipedia.org/wiki/Sampling_(signal_processing)#Audio_sampling
                sdlSampleRate = 22050;
                break;
            case SamplingRate.FOURTY_FOUR_KHX:
                // 2x 22050 is 44100
                // see https://en.wikipedia.org/wiki/Sampling_(signal_processing)#Audio_sampling
                sdlSampleRate = 44100;
                break;
        }

        switch (sampleType) {
            case BitsPerSample.EIGHT_BIT:
                sdlSampleType = SampleType.UNSIGNED_8_BIT;
                break;
            case BitsPerSample.SIXTEEN_BIT:
                sdlSampleType = SampleType.SIGNED_16_BIT;
                break;
        }
    }

    public void startAudioService(boolean encrypted) {
        if (sdlInterface != null && sdlInterface.isConnected()) {
            sdlInterface.addServiceListener(SessionType.PCM, this);
            sdlInterface.startAudioService(encrypted);
        }
    }

    public void stopAudioService() {
        if (sdlInterface != null && sdlInterface.isConnected()) {
            didRequestShutdown = true;
            sdlInterface.stopAudioService();
        }
    }

    public void pushAudioFile(File audioFile) {
        BaseAudioDecoder decoder;
        AudioDecoderListener listener = new AudioDecoderListener() {
            @Override
            public void onAudioDataAvailable(SampleBuffer buffer) {
                sdlAudioStream.sendAudio(buffer.getByteBuffer(), buffer.getPresentationTimeUs());
            }

            @Override
            public void onDecoderFinish() {
                synchronized (queue) {
                    // remove throws an exception if the queue is empty. The decoder of this listener
                    // should still be in this queue so we should be fine by just removing it
                    // if the queue is empty than we have a bug somewhere in the code
                    // and we deserve the crash...
                    queue.remove();

                    // if the queue contains more items then start the first one (without removing it)
                    if (queue.size() > 0) {
                        queue.element().start();
                    }
                }
            }

            @Override
            public void onDecoderError(Exception e) {

            }
        };

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {

            decoder = new AudioDecoder(audioFile, sdlSampleRate, sdlSampleType, listener);
        } else {
            // this BaseAudioDecoder subclass uses methods deprecated with api 21
            decoder = new AudioDecoderCompat(audioFile, sdlSampleRate, sdlSampleType, listener);
        }

        synchronized (queue) {
            queue.add(decoder);

            if (queue.size() == 1) {
                decoder.start();
            }
        }
    }

    @Override
    public void onServiceStarted(SdlSession session, SessionType type, boolean isEncrypted) {
        this.sdlAudioStream = session.startAudioStream();
    }

    @Override
    public void onServiceEnded(SdlSession session, SessionType type) {
        if (didRequestShutdown && sdlInterface != null) {
            session.stopAudioStream();
            sdlAudioStream = null;
            sdlInterface.removeServiceListener(SessionType.PCM, this);
        }
    }

    @Override
    public void onServiceError(SdlSession session, SessionType type, String reason) {
        Log.e(TAG, "OnServiceError: " + reason);
    }

    @IntDef({SampleType.UNSIGNED_8_BIT, SampleType.SIGNED_16_BIT, SampleType.FLOAT})
    @Retention(RetentionPolicy.SOURCE)
    public @interface SampleType {
        // ref https://developer.android.com/reference/android/media/AudioFormat "Encoding" section
        // The audio sample is a 8 bit unsigned integer in the range [0, 255], with a 128 offset for zero.
        // This is typically stored as a Java byte in a byte array or ByteBuffer. Since the Java byte is
        // signed, be careful with math operations and conversions as the most significant bit is inverted.
        //
        // The unsigned byte range is [0, 255] and should be converted to double [-1.0, 1.0]
        // The 8 bits of the byte are easily converted to int by using bitwise operator
        int UNSIGNED_8_BIT = Byte.SIZE >> 3;

        // ref https://developer.android.com/reference/android/media/AudioFormat "Encoding" section
        // The audio sample is a 16 bit signed integer typically stored as a Java short in a short array,
        // but when the short is stored in a ByteBuffer, it is native endian (as compared to the default Java big endian).
        // The short has full range from [-32768, 32767], and is sometimes interpreted as fixed point Q.15 data.
        //
        // the conversion is slightly easier from [-32768, 32767] to [-1.0, 1.0]
        int SIGNED_16_BIT = Short.SIZE >> 3;

        // ref https://developer.android.com/reference/android/media/AudioFormat "Encoding" section
        // Introduced in API Build.VERSION_CODES.LOLLIPOP, this encoding specifies that the audio sample
        // is a 32 bit IEEE single precision float. The sample can be manipulated as a Java float in a
        // float array, though within a ByteBuffer it is stored in native endian byte order. The nominal
        // range of ENCODING_PCM_FLOAT audio data is [-1.0, 1.0].
        int FLOAT = Float.SIZE >> 3;
    }

    @IntDef({BitsPerSample.EIGHT_BIT, BitsPerSample.SIXTEEN_BIT})
    @Retention(RetentionPolicy.SOURCE)
    public @interface BitsPerSample {
        int EIGHT_BIT = 8;
        int SIXTEEN_BIT = 16;
    }

    @StringDef({SamplingRate.EIGHT_KHZ, SamplingRate.SIXTEEN_KHZ, SamplingRate.TWENTY_TWO_KHZ, SamplingRate.FOURTY_FOUR_KHX})
    @Retention(RetentionPolicy.SOURCE)
    public @interface SamplingRate {
        String EIGHT_KHZ = "8KHZ";
        String SIXTEEN_KHZ = "16KHZ";
        String TWENTY_TWO_KHZ = "22KHZ";
        String FOURTY_FOUR_KHX = "44KHZ";
    }
}