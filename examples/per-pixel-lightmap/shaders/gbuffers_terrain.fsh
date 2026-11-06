#version 330 compatibility

#define PIXEL_LIGHTING // Iluminacao por pixel
#define WORLD_TEXTURE // Textura continua no mundo

uniform sampler2D gtexture;
uniform sampler2D lightmap;
uniform float alphaTestRef;
in vec2 texcoord;
in vec2 lightcoord;
in vec4 vertexColor;
in vec3 relativePosition;
in vec3 worldNormal;
#include "/lib/world_lightmap.glsl"

/* DRAWBUFFERS:0 */
void main() {
    vec4 albedo = texture(gtexture, texcoord) * vertexColor;
    if (albedo.a < alphaTestRef) discard;
    vec2 uv = lightcoord;
#ifdef PIXEL_LIGHTING
    uv = iris_samplePixelLightmap(relativePosition, worldNormal, lightcoord);
#endif
    albedo.rgb *= texture(lightmap, uv).rgb;
#ifdef WORLD_TEXTURE
    albedo.rgb *= iris_sampleWorldTexture(relativePosition, worldNormal);
#endif
    gl_FragData[0] = albedo;
}
