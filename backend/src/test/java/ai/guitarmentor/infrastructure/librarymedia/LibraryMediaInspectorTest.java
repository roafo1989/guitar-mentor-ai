package ai.guitarmentor.infrastructure.librarymedia;

import static org.junit.jupiter.api.Assertions.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

class LibraryMediaInspectorTest {
    @Test void jpegAndPngAreDecodedAndReencodedAsSanitizedPreviews() throws Exception {
        byte[] jpeg=image("jpeg",31,19);
        var jpg=LibraryMediaInspector.inspectPhoto(jpeg,"JPEG","image/jpeg");
        assertEquals(31,jpg.width()); assertEquals(19,jpg.height());
        assertEquals("image/jpeg",jpg.mediaType());
        assertTrue(jpg.preview().length>0);
        byte[] png=image("png",17,23);
        var pngResult=LibraryMediaInspector.inspectPhoto(png,"PNG","image/png");
        assertEquals(17,pngResult.width()); assertEquals(23,pngResult.height());
        assertEquals("image/png",pngResult.mediaType());
        assertTrue(pngResult.preview().length>0);
    }

    @Test void exifIsNotCopiedIntoSanitizedJpegPreview() throws Exception {
        byte[] base=image("jpeg",12,9);
        byte[] payload=new byte[]{'E','x','i','f',0,0,1,2,3,4};
        ByteArrayOutputStream decorated=new ByteArrayOutputStream();
        decorated.write(base,0,2); decorated.write(0xff); decorated.write(0xe1);
        int length=payload.length+2; decorated.write((length>>>8)&255); decorated.write(length&255);
        decorated.write(payload); decorated.write(base,2,base.length-2);
        byte[] original=decorated.toByteArray();
        var result=LibraryMediaInspector.inspectPhoto(original,"JPEG","image/jpeg");
        assertTrue(new String(result.preview(),java.nio.charset.StandardCharsets.ISO_8859_1).indexOf("Exif")<0);
        assertFalse(java.util.Arrays.equals(original,result.preview()));
    }

    @Test void photoDimensionsAndInputSizeAreRejectedBeforeFullDecode() throws Exception {
        assertThrows(LibraryMediaInspector.InvalidMedia.class,
            ()->LibraryMediaInspector.inspectPhoto(pngHeader(5000,5000),"PNG","image/png"));
        assertThrows(LibraryMediaInspector.InvalidMedia.class,
            ()->LibraryMediaInspector.inspectPhoto(pngHeader(12001,1),"PNG","image/png"));
        byte[] tooLarge=new byte[LibraryMediaInspector.MAX_PHOTO_BYTES+1];
        tooLarge[0]=(byte)0xff; tooLarge[1]=(byte)0xd8; tooLarge[2]=(byte)0xff;
        assertThrows(LibraryMediaInspector.InvalidMedia.class,
            ()->LibraryMediaInspector.inspectPhoto(tooLarge,"JPEG","image/jpeg"));
    }

    @Test void photoSignatureExtensionMimeAndDecodeMustAgree() throws Exception {
        byte[] jpeg=image("jpeg",10,10);
        assertThrows(LibraryMediaInspector.InvalidMedia.class,
            ()->LibraryMediaInspector.inspectPhoto(jpeg,"PNG","image/png"));
        assertThrows(LibraryMediaInspector.InvalidMedia.class,
            ()->LibraryMediaInspector.inspectPhoto(jpeg,"JPEG","application/octet-stream"));
        assertThrows(LibraryMediaInspector.InvalidMedia.class,
            ()->LibraryMediaInspector.inspectPhoto(new byte[]{1,2,3},"JPEG","image/jpeg"));
    }

    @Test void wavRequiresPcm16MonoOrStereoAndBoundedDuration() throws Exception {
        var wav=LibraryMediaInspector.inspectAudio(wav(1,44100,88200,1,16),"WAV","audio/wav");
        assertEquals(1000,wav.durationMillis()); assertEquals(1,wav.channels()); assertEquals(44100,wav.sampleRate());
        var stereo=LibraryMediaInspector.inspectAudio(wav(2,48000,192000,1,16),"WAV","audio/x-wav");
        assertEquals(1000,stereo.durationMillis()); assertEquals(2,stereo.channels());
        assertThrows(LibraryMediaInspector.InvalidMedia.class,
            ()->LibraryMediaInspector.inspectAudio(wav(2,44100,176400,1,24),"WAV","audio/wav"));
        assertThrows(LibraryMediaInspector.InvalidMedia.class,
            ()->LibraryMediaInspector.inspectAudio(wav(3,44100,264600,1,16),"WAV","audio/wav"));
        assertThrows(LibraryMediaInspector.InvalidMedia.class,
            ()->LibraryMediaInspector.inspectAudio(wav(1,8000,9_600_160,1,16),"WAV","audio/wav"));
    }

    @Test void mp3FramesAreParsedWithoutNativeToolsAndFormatSpoofingIsRejected() throws Exception {
        byte[] mp3=mp3Sample();
        var result=LibraryMediaInspector.inspectAudio(mp3,"MP3","audio/mpeg");
        assertEquals(2,result.channels()); assertEquals(44100,result.sampleRate());
        assertTrue(result.durationMillis()>=36000 && result.durationMillis()<=37000);
        assertThrows(LibraryMediaInspector.InvalidMedia.class,
            ()->LibraryMediaInspector.inspectAudio(mp3,"WAV","audio/wav"));
        var silentFrames=LibraryMediaInspector.inspectAudio(mp3Frames(40),"MP3","audio/mpeg");
        assertEquals(2,silentFrames.channels(),"valid zero-coded MP3 silence frames are a real MP3 stream");
        assertThrows(LibraryMediaInspector.InvalidMedia.class,
            ()->LibraryMediaInspector.inspectAudio(mp3,"MP3","application/octet-stream"));
        assertThrows(LibraryMediaInspector.InvalidMedia.class,
            ()->LibraryMediaInspector.inspectAudio(new byte[256],"MP3","audio/mpeg"));
        assertThrows(LibraryMediaInspector.InvalidMedia.class,
            ()->LibraryMediaInspector.inspectAudio(mp3Frames(23001),"MP3","audio/mpeg"));
        assertThrows(LibraryMediaInspector.InvalidMedia.class,
            ()->LibraryMediaInspector.inspectAudio(new byte[LibraryMediaInspector.MAX_AUDIO_BYTES+1],"MP3","audio/mpeg"));
    }

    private static byte[] pngHeader(int width,int height) throws Exception {
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        out.write(new byte[]{(byte)0x89,0x50,0x4e,0x47,0x0d,0x0a,0x1a,0x0a});
        ByteBuffer ihdr=ByteBuffer.allocate(13).order(ByteOrder.BIG_ENDIAN);
        ihdr.putInt(width).putInt(height).put((byte)8).put((byte)2).put((byte)0).put((byte)0).put((byte)0);
        writeChunk(out,"IHDR",ihdr.array()); writeChunk(out,"IEND",new byte[0]); return out.toByteArray();
    }
    private static void writeChunk(ByteArrayOutputStream out,String type,byte[] data) throws Exception {
        ByteBuffer size=ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(data.length);
        out.write(size.array()); byte[] typeBytes=type.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        out.write(typeBytes); out.write(data); java.util.zip.CRC32 crc=new java.util.zip.CRC32();
        crc.update(typeBytes); crc.update(data); ByteBuffer value=ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt((int)crc.getValue()); out.write(value.array());
    }

    private static byte[] image(String format,int width,int height) throws Exception {
        BufferedImage image=new BufferedImage(width,height,BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream output=new ByteArrayOutputStream();
        assertTrue(ImageIO.write(image,format,output)); image.flush(); return output.toByteArray();
    }

    private static byte[] mp3Sample() throws Exception {
        try(var input=LibraryMediaInspectorTest.class.getResourceAsStream("/exercises/GM-EB01/preview_v08B.mp3")) {
            assertNotNull(input,"tracked real MP3 fixture");
            return input.readAllBytes();
        }
    }

    private static byte[] mp3Frames(int count) {
        // MPEG-1 Layer III, 128 kbit/s, 44.1 kHz, joint stereo: 417 bytes/frame.
        byte[] result=new byte[count*417];
        for(int i=0;i<count;i++) {
            int p=i*417; result[p]=(byte)0xff; result[p+1]=(byte)0xfb;
            result[p+2]=(byte)0x90; result[p+3]=(byte)0x64;
        }
        return result;
    }

    private static byte[] wav(int channels,int sampleRate,int dataBytes,int format,int bits) {
        int block=channels*(bits/8); int byteRate=sampleRate*block;
        ByteBuffer b=ByteBuffer.allocate(44+dataBytes).order(ByteOrder.LITTLE_ENDIAN);
        b.put(new byte[]{'R','I','F','F'}).putInt(36+dataBytes).put(new byte[]{'W','A','V','E'});
        b.put(new byte[]{'f','m','t',' '}).putInt(16).putShort((short)format).putShort((short)channels)
            .putInt(sampleRate).putInt(byteRate).putShort((short)block).putShort((short)bits);
        b.put(new byte[]{'d','a','t','a'}).putInt(dataBytes);
        return b.array();
    }
}
