package net.irisshaders.iris.lighting;

import java.nio.ByteBuffer;

/** World-aligned light samples: RGB = block light, sky light, loaded mask. */
public final class LightmapVolumeData {
	public static final int SIZE = 64;
	public static final int BYTES = SIZE * SIZE * SIZE * 3;

	private LightmapVolumeData() {
	}

	@FunctionalInterface
	public interface LightSampler {
		/** Low nibble: block light; next nibble: sky light; bit 8: loaded. */
		int sample(int x, int y, int z);
	}

	public static int origin(double cameraCoordinate) {
		return (int) Math.floor(cameraCoordinate) - SIZE / 2;
	}

	public static void fill(ByteBuffer target, int originX, int originY, int originZ, LightSampler sampler) {
		target.clear();
		// X is contiguous, matching OpenGL's 3D texture upload layout.
		for (int z = 0; z < SIZE; z++) {
			for (int y = 0; y < SIZE; y++) {
				for (int x = 0; x < SIZE; x++) {
					int light = sampler.sample(originX + x, originY + y, originZ + z);
					boolean loaded = (light & 256) != 0;
					target.put((byte) (loaded ? (light & 15) * 17 : 0));
					target.put((byte) (loaded ? ((light >> 4) & 15) * 17 : 0));
					target.put((byte) (loaded ? 255 : 0));
				}
			}
		}
		target.flip();
	}
}
