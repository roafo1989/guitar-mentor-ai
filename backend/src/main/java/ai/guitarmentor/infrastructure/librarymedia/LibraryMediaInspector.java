package ai.guitarmentor.infrastructure.librarymedia;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Iterator;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;

/** Bounded, in-process validation for community-library media. No native tools or network access. */
public final class LibraryMediaInspector {
    public static final int MAX_PHOTO_BYTES = 5 * 1024 * 1024;
    public static final int MAX_AUDIO_BYTES = 20 * 1024 * 1024;
    public static final int MAX_PIXELS = 20_000_000;
    public static final int MAX_SIDE = 12_000;
    public static final long MAX_DURATION_MILLIS = 10 * 60 * 1000L;
    private static final byte[] PNG = {(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a};

    private LibraryMediaInspector() {}

    public record Photo(String format, String mediaType, int width, int height, byte[] preview) {}
    public record Audio(String format, String mediaType, long durationMillis, int channels, int sampleRate) {}

    public static Photo inspectPhoto(byte[] bytes, String extension, String declaredMime) throws IOException {
        if (bytes == null || bytes.length == 0 || bytes.length > MAX_PHOTO_BYTES)
            throw new InvalidMedia("PHOTO_SIZE_INVALID");
        String detected;
        if (bytes.length >= 8 && startsWith(bytes, PNG)) detected = "PNG";
        else if (bytes.length >= 3 && (bytes[0] & 255) == 0xff && (bytes[1] & 255) == 0xd8
                && (bytes[2] & 255) == 0xff) detected = "JPEG";
        else throw new InvalidMedia("PHOTO_SIGNATURE_INVALID");
        String expectedMime = "JPEG".equals(detected) ? "image/jpeg" : "image/png";
        if (!detected.equals(extension) || !expectedMime.equalsIgnoreCase(declaredMime))
            throw new InvalidMedia("PHOTO_TYPE_MISMATCH");

        ImageIO.setUseCache(false);
        try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            if (input == null) throw new InvalidMedia("PHOTO_DECODE_FAILED");
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw new InvalidMedia("PHOTO_DECODE_FAILED");
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (width < 1 || height < 1 || width > MAX_SIDE || height > MAX_SIDE
                        || (long) width * height > MAX_PIXELS)
                    throw new InvalidMedia("PHOTO_DIMENSIONS_EXCEEDED");
                BufferedImage decoded = reader.read(0);
                if (decoded == null || decoded.getWidth() != width || decoded.getHeight() != height)
                    throw new InvalidMedia("PHOTO_DECODE_FAILED");
                BoundedOutputStream output = new BoundedOutputStream(MAX_PHOTO_BYTES);
                boolean written = ImageIO.write(decoded, "JPEG".equals(detected) ? "jpeg" : "png", output);
                decoded.flush();
                if (!written || output.size() == 0) throw new InvalidMedia("PHOTO_PREVIEW_FAILED");
                return new Photo(detected, expectedMime, width, height, output.toByteArray());
            } finally {
                reader.dispose();
            }
        } catch (InvalidMedia e) {
            throw e;
        } catch (IOException e) {
            if ("Preview output limit exceeded".equals(e.getMessage()))
                throw new InvalidMedia("PHOTO_PREVIEW_TOO_LARGE",e);
            throw new InvalidMedia("PHOTO_DECODE_FAILED",e);
        } catch (RuntimeException e) {
            throw new InvalidMedia("PHOTO_DECODE_FAILED", e);
        }
    }

    public static Audio inspectAudio(byte[] bytes, String extension, String declaredMime) throws IOException {
        if (bytes == null || bytes.length == 0 || bytes.length > MAX_AUDIO_BYTES)
            throw new InvalidMedia("AUDIO_SIZE_INVALID");
        if ("WAV".equals(extension)) {
            if (!"audio/wav".equalsIgnoreCase(declaredMime) && !"audio/x-wav".equalsIgnoreCase(declaredMime))
                throw new InvalidMedia("AUDIO_TYPE_MISMATCH");
            return inspectWav(bytes);
        }
        if ("MP3".equals(extension)) {
            if (!"audio/mpeg".equalsIgnoreCase(declaredMime) && !"audio/mp3".equalsIgnoreCase(declaredMime))
                throw new InvalidMedia("AUDIO_TYPE_MISMATCH");
            return inspectMp3(bytes);
        }
        throw new InvalidMedia("AUDIO_FORMAT_UNSUPPORTED");
    }

    private static final class BoundedOutputStream extends OutputStream {
        private final int maximum;
        private final ByteArrayOutputStream delegate;
        private BoundedOutputStream(int maximum) { this.maximum=maximum; this.delegate=new ByteArrayOutputStream(Math.min(maximum,1024*1024)); }
        @Override public void write(int value) throws IOException {
            ensureCapacity(1); delegate.write(value);
        }
        @Override public void write(byte[] bytes,int offset,int length) throws IOException {
            if(bytes==null) throw new NullPointerException();
            if(offset<0 || length<0 || length>bytes.length-offset) throw new IndexOutOfBoundsException();
            ensureCapacity(length); delegate.write(bytes,offset,length);
        }
        private void ensureCapacity(int amount) throws IOException {
            if(amount<0 || (long)delegate.size()+amount>maximum) throw new IOException("Preview output limit exceeded");
        }
        int size() { return delegate.size(); }
        byte[] toByteArray() { return delegate.toByteArray(); }
    }

    private static Audio inspectWav(byte[] b) throws IOException {
        if (b.length < 44 || !ascii(b, 0, "RIFF") || !ascii(b, 8, "WAVE"))
            throw new InvalidMedia("WAV_HEADER_INVALID");
        long riffEnd = 8L + u32le(b, 4);
        if (riffEnd > b.length || riffEnd < 44) throw new InvalidMedia("WAV_LENGTH_INVALID");
        int channels = 0, rate = 0, bits = 0, format = 0, blockAlign = 0, byteRate=0;
        long dataBytes = -1;
        boolean formatSeen=false;
        int pos = 12, chunks=0;
        while (pos + 8 <= riffEnd) {
            if(++chunks>1024) throw new InvalidMedia("WAV_CHUNK_LIMIT_EXCEEDED");
            String chunk = new String(b, pos, 4, java.nio.charset.StandardCharsets.US_ASCII);
            long size = u32le(b, pos + 4);
            long start = pos + 8L, end = start + size;
            if (end > riffEnd || end < start) throw new InvalidMedia("WAV_CHUNK_INVALID");
            if ("fmt ".equals(chunk)) {
                if (formatSeen || size < 16) throw new InvalidMedia("WAV_FORMAT_INVALID");
                formatSeen=true;
                format = u16le(b, (int) start);
                channels = u16le(b, (int) start + 2);
                rate = (int) u32le(b, (int) start + 4);
                byteRate = (int) u32le(b, (int) start + 8);
                blockAlign = u16le(b, (int) start + 12);
                bits = u16le(b, (int) start + 14);
            } else if ("data".equals(chunk)) {
                if (dataBytes >= 0) throw new InvalidMedia("WAV_MULTIPLE_DATA_CHUNKS");
                dataBytes = size;
            }
            long next = end + (size & 1L);
            if (next > riffEnd) throw new InvalidMedia("WAV_CHUNK_INVALID");
            pos = (int) next;
        }
        if (pos != riffEnd || riffEnd != b.length || !formatSeen || format != 1 || bits != 16
                || (channels != 1 && channels != 2) || rate < 8000 || rate > 48000
                || blockAlign != channels * 2 || byteRate != rate * blockAlign
                || dataBytes < blockAlign || dataBytes % blockAlign != 0)
            throw new InvalidMedia("WAV_FORMAT_UNSUPPORTED");
        long duration = dataBytes * 1000L / (rate * (long) blockAlign);
        if (duration < 1 || duration > MAX_DURATION_MILLIS) throw new InvalidMedia("AUDIO_DURATION_EXCEEDED");
        return new Audio("WAV", "audio/wav", duration, channels, rate);
    }

    private static Audio inspectMp3(byte[] b) throws IOException {
        int pos = 0;
        if (b.length >= 10 && ascii(b, 0, "ID3")) {
            int size = synchsafe(b, 6);
            pos = 10 + size + ((b[5] & 0x10) != 0 ? 10 : 0);
            if (pos > b.length) throw new InvalidMedia("MP3_ID3_INVALID");
        }
        int firstFrame = -1;
        for (int i = pos; i + 4 <= Math.min(b.length, pos + 4096); i++) {
            if (validMp3Header(b, i)) { firstFrame = i; break; }
        }
        if (firstFrame < 0) throw new InvalidMedia("MP3_FRAME_INVALID");
        pos = firstFrame;
        long samples = 0;
        int commonRate = -1, commonChannels = -1, frames = 0;
        while (pos + 4 <= b.length) {
            if (b.length - pos == 128 && ascii(b, pos, "TAG")) break; // ID3v1 trailer
            if (!validMp3Header(b, pos)) break;
            int header = be32(b, pos);
            int version = (header >>> 19) & 3;
            int bitrateIndex = (header >>> 12) & 15;
            int sampleIndex = (header >>> 10) & 3;
            int channelMode = (header >>> 6) & 3;
            int[] rates = {44100, 48000, 32000};
            int rate = rates[sampleIndex] / (version == 3 ? 1 : version == 2 ? 2 : 4);
            int[] mpeg1 = {0,32,40,48,56,64,80,96,112,128,160,192,224,256,320};
            int[] mpeg2 = {0,8,16,24,32,40,48,56,64,80,96,112,128,144,160};
            int bitrate = (version == 3 ? mpeg1 : mpeg2)[bitrateIndex] * 1000;
            int padding = (header >>> 9) & 1;
            int frameLength = (version == 3 ? 144 : 72) * bitrate / rate + padding;
            int channels = channelMode == 3 ? 1 : 2;
            if (frameLength < 24 || pos + frameLength > b.length)
                throw new InvalidMedia("MP3_FRAME_TRUNCATED");
            if (commonRate < 0) { commonRate = rate; commonChannels = channels; }
            if (rate != commonRate || channels != commonChannels)
                throw new InvalidMedia("MP3_STREAM_PARAMETERS_CHANGED");
            samples += version == 3 ? 1152 : 576;
            frames++;
            pos += frameLength;
            if (samples * 1000L / rate > MAX_DURATION_MILLIS)
                throw new InvalidMedia("AUDIO_DURATION_EXCEEDED");
        }
        if (frames == 0 || (pos < b.length && !(b.length - pos == 128 && ascii(b, pos, "TAG"))))
            throw new InvalidMedia("MP3_FRAME_INVALID");
        long duration = samples * 1000L / commonRate;
        if (commonRate < 8000 || commonRate > 48000 || duration < 1 || duration > MAX_DURATION_MILLIS)
            throw new InvalidMedia("AUDIO_PARAMETERS_INVALID");
        return new Audio("MP3", "audio/mpeg", duration, commonChannels, commonRate);
    }

    private static boolean validMp3Header(byte[] b, int p) {
        if (p + 4 > b.length) return false;
        int h = be32(b, p);
        int version = (h >>> 19) & 3, layer = (h >>> 17) & 3;
        int bitrate = (h >>> 12) & 15, sample = (h >>> 10) & 3;
        return (h & 0xffe00000) == 0xffe00000 && version != 1 && layer == 1
                && bitrate != 0 && bitrate != 15 && sample != 3;
    }
    private static int synchsafe(byte[] b, int p) throws InvalidMedia {
        for (int i = p; i < p + 4; i++) if ((b[i] & 0x80) != 0) throw new InvalidMedia("MP3_ID3_INVALID");
        return ((b[p] & 127) << 21) | ((b[p+1] & 127) << 14) | ((b[p+2] & 127) << 7) | (b[p+3] & 127);
    }
    private static int be32(byte[] b, int p) { return ByteBuffer.wrap(b, p, 4).order(ByteOrder.BIG_ENDIAN).getInt(); }
    private static int u16le(byte[] b, int p) { return (b[p]&255) | ((b[p+1]&255)<<8); }
    private static long u32le(byte[] b, int p) { return Integer.toUnsignedLong(ByteBuffer.wrap(b,p,4).order(ByteOrder.LITTLE_ENDIAN).getInt()); }
    private static boolean ascii(byte[] b, int p, String s) {
        if (p + s.length() > b.length) return false;
        for (int i=0;i<s.length();i++) if ((b[p+i]&255)!=s.charAt(i)) return false;
        return true;
    }
    private static boolean startsWith(byte[] value, byte[] prefix) {
        if (value.length < prefix.length) return false;
        for (int i=0;i<prefix.length;i++) if (value[i]!=prefix[i]) return false;
        return true;
    }
    public static final class InvalidMedia extends IOException {
        private final String code;
        public InvalidMedia(String code) { super(code); this.code=code; }
        public InvalidMedia(String code, Throwable cause) { super(code,cause); this.code=code; }
        public String code() { return code; }
    }
}
