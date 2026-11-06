import net.irisshaders.iris.lighting.LightmapVolumeData;
import java.nio.ByteBuffer;

public class LightmapVolumeCheck {
    private static final int N = LightmapVolumeData.SIZE;

    private static ByteBuffer volume(int ox, int oy, int oz) {
        ByteBuffer data = ByteBuffer.allocate(LightmapVolumeData.BYTES);
        LightmapVolumeData.fill(data, ox, oy, oz, (x, y, z) ->
            256 | Math.floorMod(x, 16) | (Math.floorMod(y + z, 16) << 4));
        assert data.remaining() == LightmapVolumeData.BYTES;
        return data;
    }

    // OpenGL normalized linear filtering: samples sit at block centers.
    private static double linear(ByteBuffer data, int ox, int oy, int oz, double x, double y, double z, int channel) {
        double[] p = {x - ox - 0.5, y - oy - 0.5, z - oz - 0.5};
        int[] b = {(int) Math.floor(p[0]), (int) Math.floor(p[1]), (int) Math.floor(p[2])};
        double value = 0;
        for (int dz = 0; dz < 2; dz++) for (int dy = 0; dy < 2; dy++) for (int dx = 0; dx < 2; dx++) {
            int index = ((b[2] + dz) * N * N + (b[1] + dy) * N + b[0] + dx) * 3 + channel;
            double wx = dx == 0 ? 1 - (p[0] - b[0]) : p[0] - b[0];
            double wy = dy == 0 ? 1 - (p[1] - b[1]) : p[1] - b[1];
            double wz = dz == 0 ? 1 - (p[2] - b[2]) : p[2] - b[2];
            value += Byte.toUnsignedInt(data.get(index)) / 255.0 * wx * wy * wz;
        }
        return value;
    }

    public static void main(String[] args) {
        assert LightmapVolumeData.origin(-0.01) == -33;
        assert LightmapVolumeData.origin(0.99) == -32;
        assert LightmapVolumeData.origin(30_000_000.75) == 29_999_968;
        ByteBuffer a = volume(-32, -32, -32);
        for (int z = 0; z < N; z++) for (int y = 0; y < N; y++) for (int x = 0; x < N; x++) {
            int i = ((z * N + y) * N + x) * 3;
            assert Byte.toUnsignedInt(a.get(i)) == Math.floorMod(x - 32, 16) * 17;
            assert Byte.toUnsignedInt(a.get(i + 1)) == Math.floorMod(y + z - 64, 16) * 17;
            assert Byte.toUnsignedInt(a.get(i + 2)) == 255;
        }
        ByteBuffer shifted = volume(-31, -33, -30);
        for (int channel = 0; channel < 3; channel++) {
            double v = linear(a, -32, -32, -32, -0.2, 3.1, -2.7, channel);
            double moved = linear(shifted, -31, -33, -30, -0.2, 3.1, -2.7, channel);
            assert Math.abs(v - moved) < 1e-12 : "Light shifted when camera crossed a block";
            double left = linear(a, -32, -32, -32, -1e-7, 3.1, -2.7, channel);
            double right = linear(a, -32, -32, -32, 1e-7, 3.1, -2.7, channel);
            assert Math.abs(left - right) < 1e-6 : "Discontinuous block boundary";
        }
        LightmapVolumeData.fill(a, 0, 0, 0, (x, y, z) -> 255); // unloaded, even with stray light bits
        while (a.hasRemaining()) assert a.get() == 0 : "Unloaded samples must not leak light";
        LightmapVolumeData.fill(a, 0, 0, 0, (x, y, z) -> 256 | 15 | (15 << 4));
        while (a.hasRemaining()) assert Byte.toUnsignedInt(a.get()) == 255;
        System.out.println("PASS: volume layout, normalization, negative/world-border coordinates, recentering, continuity and unloaded mask");
    }
}
