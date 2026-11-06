package net.irisshaders.iris.lighting;

import com.mojang.blaze3d.opengl.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import net.irisshaders.iris.gl.IrisRenderSystem;
import net.irisshaders.iris.gl.sampler.GlSampler;
import net.irisshaders.iris.gl.sampler.SamplerHolder;
import net.irisshaders.iris.gl.texture.TextureType;
import net.irisshaders.iris.gl.texture.TextureUploadHelper;
import net.irisshaders.iris.gl.uniform.UniformHolder;
import net.irisshaders.iris.uniforms.CameraUniforms;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LightLayer;
import org.joml.Vector3f;
import org.lwjgl.opengl.GL33C;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;

import static net.irisshaders.iris.gl.uniform.UniformUpdateFrequency.ONCE;
import static net.irisshaders.iris.gl.uniform.UniformUpdateFrequency.PER_FRAME;

/** One continuous light field per rendering pipeline, allocated only when used. */
public final class PixelLightmap {
	private int texture;
	private GlSampler sampler;
	private ByteBuffer pixels;
	private boolean requested;
	private boolean valid;
	private int originX, originY, originZ;
	private long lastTick = Long.MIN_VALUE;
	private ClientLevel lastLevel;

	public void addSampler(SamplerHolder samplers) {
		if (samplers.hasSampler("iris_lightVolume")) {
			requested = true;
			samplers.addDynamicSampler(TextureType.TEXTURE_3D, this::getTexture, () -> {
				getTexture();
				return sampler;
			}, "iris_lightVolume");
		}
	}

	public void addUniforms(UniformHolder uniforms) {
		uniforms.uniform1i(ONCE, "iris_lightVolumeSize", () -> LightmapVolumeData.SIZE);
		uniforms.uniform1i(PER_FRAME, "iris_lightVolumeValid", () -> valid ? 1 : 0);
		uniforms.uniform3f(PER_FRAME, "iris_lightVolumeOrigin", () -> {
			var camera = CameraUniforms.getUnshiftedCameraPosition();
			// Subtract in double precision before conversion, including near the world border.
			return new Vector3f((float) (originX - camera.x), (float) (originY - camera.y), (float) (originZ - camera.z));
		});
	}

	private int getTexture() {
		if (texture == 0) {
			RenderSystem.assertOnRenderThread();
			pixels = MemoryUtil.memCalloc(LightmapVolumeData.BYTES);
			texture = GlStateManager._genTexture();
			int previous = GL33C.glGetInteger(GL33C.GL_TEXTURE_BINDING_3D);
			TextureUploadHelper.resetTextureUploadState();
			GlStateManager._pixelStore(GL33C.GL_UNPACK_SKIP_IMAGES, 0);
			IrisRenderSystem.texImage3D(texture, GL33C.GL_TEXTURE_3D, 0, GL33C.GL_RGB8,
				LightmapVolumeData.SIZE, LightmapVolumeData.SIZE, LightmapVolumeData.SIZE, 0,
				GL33C.GL_RGB, GL33C.GL_UNSIGNED_BYTE, pixels);
			IrisRenderSystem.texParameteri(texture, GL33C.GL_TEXTURE_3D, GL33C.GL_TEXTURE_MAX_LEVEL, 0);
			sampler = new GlSampler(true, false, false, false, false, true);
			IrisRenderSystem.samplerParameteri(sampler.getId(), GL33C.GL_TEXTURE_WRAP_R, GL33C.GL_CLAMP_TO_EDGE);
			IrisRenderSystem.bindTextureForSetup(GL33C.GL_TEXTURE_3D, previous);
		}
		return texture;
	}

	public void update() {
		if (!requested) return;
		RenderSystem.assertOnRenderThread();
		ClientLevel level = Minecraft.getInstance().level;
		if (level == null) {
			valid = false;
			lastLevel = null;
			return;
		}
		var camera = CameraUniforms.getUnshiftedCameraPosition();
		int x = LightmapVolumeData.origin(camera.x);
		int y = LightmapVolumeData.origin(camera.y);
		int z = LightmapVolumeData.origin(camera.z);
		long tick = level.getGameTime();
		if (valid && level == lastLevel && tick == lastTick && x == originX && y == originY && z == originZ) return;

		int id = getTexture();
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		// ponytail: refresh 64^3 samples per tick/moved block; use dirty light sections if profiling requires it.
		LightmapVolumeData.fill(pixels, x, y, z, (wx, wy, wz) -> {
			pos.set(wx, wy, wz);
			if (!level.hasChunkAt(pos)) return 0;
			return 256 | level.getBrightness(LightLayer.BLOCK, pos) | (level.getBrightness(LightLayer.SKY, pos) << 4);
		});
		TextureUploadHelper.resetTextureUploadState();
		GlStateManager._pixelStore(GL33C.GL_UNPACK_SKIP_IMAGES, 0);
		int previous = GL33C.glGetInteger(GL33C.GL_TEXTURE_BINDING_3D);
		IrisRenderSystem.bindTextureForSetup(GL33C.GL_TEXTURE_3D, id);
		GL33C.glTexSubImage3D(GL33C.GL_TEXTURE_3D, 0, 0, 0, 0,
			LightmapVolumeData.SIZE, LightmapVolumeData.SIZE, LightmapVolumeData.SIZE,
			GL33C.GL_RGB, GL33C.GL_UNSIGNED_BYTE, pixels);
		IrisRenderSystem.bindTextureForSetup(GL33C.GL_TEXTURE_3D, previous);
		originX = x;
		originY = y;
		originZ = z;
		lastLevel = level;
		lastTick = tick;
		valid = true;
	}

	public void destroy() {
		RenderSystem.assertOnRenderThread();
		if (texture != 0) GlStateManager._deleteTexture(texture);
		if (sampler != null) sampler.destroy();
		if (pixels != null) MemoryUtil.memFree(pixels);
		texture = 0;
		sampler = null;
		pixels = null;
		lastLevel = null;
		valid = false;
	}
}
