package gamemashup.minepeak;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.sound.sampled.AudioFormat;
import net.minecraft.client.sounds.AudioStream;
import org.lwjgl.BufferUtils;

/**
 * One of PEAK's sounds as Minecraft plays it: a 16-bit PCM WAV file (as the MinePeak Exporter saves them), read a piece at
 * a time; over again from its start when it loops; made mono when it's to be heard from a place (Minecraft places only
 * mono sounds).
 */
final class WavStream implements AudioStream {
	private final Path file;
	private final boolean loop, mono;
	private DataInputStream in;
	private int channels, rate, left;
	private AudioFormat format;

	WavStream(Path file, boolean loop, boolean mono) throws IOException {
		this.file = file;
		this.loop = loop;
		this.mono = mono;
		open();
	}

	/** Opens the file and reads its header, up to its samples. */
	private void open() throws IOException {
		if (in != null) in.close();
		in = new DataInputStream(new BufferedInputStream(Files.newInputStream(file), 1 << 16));
		byte[] four = new byte[4];
		in.readFully(four);   // RIFF
		int32(in);
		in.readFully(four);   // WAVE
		while (true) {
			in.readFully(four);
			String id = new String(four, java.nio.charset.StandardCharsets.US_ASCII);
			int size = int32(in);
			if (id.equals("fmt ")) {
				int16(in);
				channels = int16(in);
				rate = int32(in);
				int32(in);
				int16(in);
				int bits = int16(in);
				if (bits != 16) throw new IOException("MinePeak plays 16-bit WAVs, not " + bits + "-bit: " + file);
				if (size > 16) in.skipNBytes(size - 16);
			} else if (id.equals("data")) {
				left = size;
				break;
			} else in.skipNBytes(size + (size & 1));
		}
		format = new AudioFormat(rate, 16, mono ? 1 : channels, true, false);
	}

	private static int int32(DataInputStream in) throws IOException { return Integer.reverseBytes(in.readInt()); }
	private static int int16(DataInputStream in) throws IOException { return Short.reverseBytes(in.readShort()) & 0xFFFF; }

	@Override public AudioFormat getFormat() { return format; }

	@Override
	public ByteBuffer read(int expectedSize) throws IOException {
		int frame = channels * 2, frames = Math.max(1, expectedSize / (mono ? 2 : frame));
		byte[] raw = new byte[frames * frame];
		int got = 0;
		while (got < raw.length) {
			if (left <= 0) {
				if (!loop || got > 0) break;
				open();   // (looping: from the start again)
				if (left <= 0) break;
			}
			int n = in.read(raw, got, Math.min(raw.length - got, left));
			if (n < 0) { left = 0; continue; }
			got += n;
			left -= n;
		}
		got -= got % frame;
		if (got == 0) return null;
		ByteBuffer out;
		if (mono && channels > 1) {
			out = BufferUtils.createByteBuffer(got / channels);
			for (int i = 0; i < got; i += frame) {
				int sum = 0;
				for (int c = 0; c < channels; c++) sum += (short) ((raw[i + c * 2] & 0xFF) | (raw[i + c * 2 + 1] << 8));
				int v = sum / channels;
				out.put((byte) v).put((byte) (v >> 8));
			}
		} else {
			out = BufferUtils.createByteBuffer(got);
			out.put(raw, 0, got);
		}
		out.flip();
		return out;
	}

	/** All of it at once (for a short sound that doesn't loop). */
	ByteBuffer readAll() throws IOException {
		ByteBuffer all = BufferUtils.createByteBuffer(Math.max(2, mono ? left / Math.max(1, channels) : left));
		ByteBuffer part;
		while ((part = read(1 << 16)) != null) all.put(part);
		all.flip();
		return all;
	}

	@Override
	public void close() throws IOException {
		if (in != null) in.close();
	}
}
