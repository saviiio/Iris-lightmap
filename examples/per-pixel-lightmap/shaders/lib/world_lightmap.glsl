#ifndef IRIS_WORLD_LIGHTMAP_GLSL
#define IRIS_WORLD_LIGHTMAP_GLSL

uniform sampler3D iris_lightVolume;
uniform int iris_lightVolumeSize;
uniform int iris_lightVolumeValid;
// Position of the minimum block corner, relative to the unshifted camera.
uniform vec3 iris_lightVolumeOrigin;

// Return conventional lightmap UVs; all eight neighboring light samples are
// interpolated by the GPU, independently of terrain tessellation and block UVs.
vec2 iris_samplePixelLightmap(vec3 relativePosition, vec3 worldNormal, vec2 fallbackUV) {
    if (iris_lightVolumeValid == 0) return fallbackUV;
    vec3 p = relativePosition + normalize(worldNormal) * 0.501 - iris_lightVolumeOrigin;
    float size = float(iris_lightVolumeSize);
    vec3 edge = min(p - 0.5, vec3(size - 0.5) - p);
    float weight = smoothstep(0.0, 2.0, min(edge.x, min(edge.y, edge.z)));
    vec3 field = texture(iris_lightVolume, p / size).rgb;
    vec2 fallbackLevels = clamp((fallbackUV * 256.0 - 8.0) / 240.0, 0.0, 1.0);
    // Missing chunks have zero RGB: retain fallback for their interpolation weight.
    vec2 levels = field.rg + fallbackLevels * (1.0 - field.b);
    return mix(fallbackUV, (levels * 240.0 + 8.0) / 256.0, weight);
}

uniform sampler2D worldLightmapTexture;
uniform ivec3 cameraPositionInt;
uniform vec3 cameraPositionFract;

// A 16-block repeating world-space material. Integer modulo keeps it stable
// even at the world border and when Iris rebases cameraPosition.
vec3 iris_sampleWorldTexture(vec3 relativePosition, vec3 worldNormal) {
    ivec3 tileOrigin = ((cameraPositionInt % 16) + 16) % 16;
    vec3 p = (relativePosition + cameraPositionFract + vec3(tileOrigin)) / 16.0;
    vec3 weights = pow(abs(normalize(worldNormal)), vec3(4.0));
    weights /= max(dot(weights, vec3(1.0)), 0.0001);
    // Do not fract these UVs: let GL_REPEAT preserve derivatives and filtering.
    return texture(worldLightmapTexture, p.yz).rgb * weights.x
         + texture(worldLightmapTexture, p.xz).rgb * weights.y
         + texture(worldLightmapTexture, p.xy).rgb * weights.z;
}

vec3 iris_applyWorldLightmap(sampler2D lightmapTexture, vec3 relativePosition,
                            vec3 worldNormal, vec2 fallbackUV) {
    vec2 uv = iris_samplePixelLightmap(relativePosition, worldNormal, fallbackUV);
    vec3 illumination = texture(lightmapTexture, uv).rgb;
    return illumination * iris_sampleWorldTexture(relativePosition, worldNormal);
}
#endif
