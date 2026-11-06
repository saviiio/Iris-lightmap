"""Optional GPU check: pip install PyOpenGL; requires Mesa EGL or equivalent."""
import os
os.environ.setdefault("PYOPENGL_PLATFORM", "egl")
os.environ.setdefault("EGL_PLATFORM", "surfaceless")

from ctypes import byref, c_int, c_float
from pathlib import Path
from OpenGL import EGL, GL
from OpenGL.GL.shaders import compileShader, compileProgram

root = Path(__file__).resolve().parents[1]
pack = root / "examples/per-pixel-lightmap/shaders"
display = EGL.eglGetDisplay(EGL.EGL_DEFAULT_DISPLAY)
major, minor = c_int(), c_int()
assert EGL.eglInitialize(display, byref(major), byref(minor))
assert EGL.eglBindAPI(EGL.EGL_OPENGL_API)
attrs = (c_int * 11)(EGL.EGL_SURFACE_TYPE, EGL.EGL_PBUFFER_BIT,
    EGL.EGL_RENDERABLE_TYPE, EGL.EGL_OPENGL_BIT, EGL.EGL_RED_SIZE, 8,
    EGL.EGL_GREEN_SIZE, 8, EGL.EGL_BLUE_SIZE, 8, EGL.EGL_NONE)
configs, count = (EGL.EGLConfig * 1)(), c_int()
assert EGL.eglChooseConfig(display, attrs, configs, 1, byref(count)) and count.value
context = EGL.eglCreateContext(display, configs[0], EGL.EGL_NO_CONTEXT, None)
surface = EGL.eglCreatePbufferSurface(display, configs[0],
    (c_int * 5)(EGL.EGL_WIDTH, 1, EGL.EGL_HEIGHT, 1, EGL.EGL_NONE))
assert EGL.eglMakeCurrent(display, surface, surface, context)

helper = (pack / "lib/world_lightmap.glsl").read_text()
def link(vertex, fragment):
    return compileProgram(compileShader(vertex, GL.GL_VERTEX_SHADER),
                          compileShader(fragment, GL.GL_FRAGMENT_SHADER), validate=False)

vertex = (pack / "gbuffers_terrain.vsh").read_text()
fragment = (pack / "gbuffers_terrain.fsh").read_text().replace(
    '#include "/lib/world_lightmap.glsl"', helper)
for pixel in (False, True):
    for material in (False, True):
        source = fragment
        if not pixel: source = source.replace("#define PIXEL_LIGHTING", "//#define PIXEL_LIGHTING")
        if not material: source = source.replace("#define WORLD_TEXTURE", "//#define WORLD_TEXTURE")
        GL.glDeleteProgram(link(vertex, source))
GL.glDeleteProgram(link((pack / "final.vsh").read_text(), (pack / "final.fsh").read_text()))

program = link("#version 330 compatibility\nvoid main(){gl_Position=gl_Vertex;}",
    "#version 330 compatibility\n" + helper + """
    uniform vec3 testPosition;
    void main() {
        vec2 uv = iris_samplePixelLightmap(testPosition, vec3(0,1,0), vec2(0.5));
        gl_FragColor = vec4(uv, iris_sampleWorldTexture(testPosition, vec3(0,1,0)).r, 1);
    }
    """)
GL.glUseProgram(program)
def u(name): return GL.glGetUniformLocation(program, name)
GL.glUniform1i(u("iris_lightVolume"), 0)
GL.glUniform1i(u("worldLightmapTexture"), 1)
GL.glUniform1i(u("iris_lightVolumeSize"), 64)
GL.glUniform1i(u("iris_lightVolumeValid"), 1)
GL.glUniform3f(u("iris_lightVolumeOrigin"), -32, -32, -32)
volume = GL.glGenTextures(1)
GL.glActiveTexture(GL.GL_TEXTURE0)
GL.glBindTexture(GL.GL_TEXTURE_3D, volume)
for pname in (GL.GL_TEXTURE_MIN_FILTER, GL.GL_TEXTURE_MAG_FILTER):
    GL.glTexParameteri(GL.GL_TEXTURE_3D, pname, GL.GL_LINEAR)
for pname in (GL.GL_TEXTURE_WRAP_S, GL.GL_TEXTURE_WRAP_T, GL.GL_TEXTURE_WRAP_R):
    GL.glTexParameteri(GL.GL_TEXTURE_3D, pname, GL.GL_CLAMP_TO_EDGE)

def upload(origin=-32, loaded=True):
    data = bytes(v for z in range(64) for y in range(64) for x in range(64)
                 for v in ((max(0, min(255, (origin + x + 32) * 4)), 136, 255) if loaded else (0,0,0)))
    GL.glTexImage3D(GL.GL_TEXTURE_3D, 0, GL.GL_RGB8, 64, 64, 64, 0,
                    GL.GL_RGB, GL.GL_UNSIGNED_BYTE, data)

upload()
material = GL.glGenTextures(1)
GL.glActiveTexture(GL.GL_TEXTURE1)
GL.glBindTexture(GL.GL_TEXTURE_2D, material)
for pname in (GL.GL_TEXTURE_MIN_FILTER, GL.GL_TEXTURE_MAG_FILTER):
    GL.glTexParameteri(GL.GL_TEXTURE_2D, pname, GL.GL_LINEAR)
for pname in (GL.GL_TEXTURE_WRAP_S, GL.GL_TEXTURE_WRAP_T):
    GL.glTexParameteri(GL.GL_TEXTURE_2D, pname, GL.GL_REPEAT)
GL.glTexImage2D(GL.GL_TEXTURE_2D, 0, GL.GL_RGB8, 16, 16, 0, GL.GL_RGB,
               GL.GL_UNSIGNED_BYTE, bytes(v for y in range(16) for x in range(16) for v in (x*16, y*16, 128)))
GL.glActiveTexture(GL.GL_TEXTURE0)

def draw(x=0, y=0, z=0, camera=(0,0,0), fract=(0,0,0)):
    GL.glUniform3f(u("testPosition"), x, y, z)
    GL.glUniform3i(u("cameraPositionInt"), *camera)
    GL.glUniform3f(u("cameraPositionFract"), *fract)
    GL.glBegin(GL.GL_TRIANGLES)
    GL.glVertex2f(-1,-1); GL.glVertex2f(3,-1); GL.glVertex2f(-1,3)
    GL.glEnd()
    pixel = (c_float * 4)()
    GL.glReadPixels(0, 0, 1, 1, GL.GL_RGBA, GL.GL_FLOAT, pixel)
    return tuple(pixel)

base = draw(0.25)
expected = ((31.75 * 4 / 255) * 240 + 8) / 256
assert abs(base[0] - expected) < 0.005, (base, expected)
assert abs(draw(-0.0001)[0] - draw(0.0001)[0]) < 0.005
upload(-31)
GL.glUniform3f(u("iris_lightVolumeOrigin"), -31, -32, -32)
assert abs(draw(0.25)[0] - base[0]) < 0.005, "Volume recenter changed lighting"
upload(loaded=False)
assert abs(draw()[0] - 0.5) < 0.005, "Missing chunks need fallback"
GL.glUniform1i(u("iris_lightVolumeValid"), 0)
assert abs(draw()[0] - 0.5) < 0.005
assert abs(draw(1000)[0] - 0.5) < 0.005
GL.glUniform1i(u("iris_lightVolumeValid"), 1)
assert abs(draw(-0.0001)[2] - draw(0.0001)[2]) < 0.005, "Material tile seam"
assert abs(draw(15.9999)[2] - draw(16.0001)[2]) < 0.005
assert abs(draw(0.25)[2] - draw(-0.35, fract=(0.6,0,0))[2]) < 0.005, "Material follows camera"
assert abs(draw(0.25)[2] - draw(0.25, camera=(30_000_000,0,0))[2]) < 0.005
assert abs(draw(0.25)[2] - draw(0.25, camera=(-30_000_000,0,0))[2]) < 0.005
assert GL.glGetError() == GL.GL_NO_ERROR
print("PASS: five shader combinations linked; GPU interpolation, recenter, fallbacks, seamless material and world-border stability")
GL.glUseProgram(0)
GL.glDeleteProgram(program)
GL.glDeleteTextures([volume, material])
EGL.eglMakeCurrent(display, EGL.EGL_NO_SURFACE, EGL.EGL_NO_SURFACE, EGL.EGL_NO_CONTEXT)
EGL.eglDestroySurface(display, surface)
EGL.eglDestroyContext(display, context)
EGL.eglTerminate(display)
