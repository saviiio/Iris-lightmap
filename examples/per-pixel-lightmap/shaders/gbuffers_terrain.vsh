#version 330 compatibility

uniform mat4 gbufferModelViewInverse;
out vec2 texcoord;
out vec2 lightcoord;
out vec4 vertexColor;
out vec3 relativePosition;
out vec3 worldNormal;

void main() {
    vec4 viewPosition = gl_ModelViewMatrix * gl_Vertex;
    relativePosition = (gbufferModelViewInverse * viewPosition).xyz;
    worldNormal = normalize(mat3(gbufferModelViewInverse) * gl_NormalMatrix * gl_Normal);
    texcoord = (gl_TextureMatrix[0] * gl_MultiTexCoord0).xy;
    lightcoord = (gl_TextureMatrix[1] * gl_MultiTexCoord1).xy;
    vertexColor = gl_Color;
    gl_Position = gl_ProjectionMatrix * viewPosition;
}
