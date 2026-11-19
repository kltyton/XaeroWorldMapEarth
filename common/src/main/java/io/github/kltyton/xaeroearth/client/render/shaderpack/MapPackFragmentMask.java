package io.github.kltyton.xaeroearth.client.render.shaderpack;

import com.mojang.logging.LogUtils;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

/** Adds map-local clipping to transformed Iris terrain fragments without changing their material stages. */
public final class MapPackFragmentMask {
    private static final Pattern MAIN = Pattern.compile("\\bvoid\\s+main\\s*\\(");
    private static final Pattern VERSION = Pattern.compile("(?m)^\\s*#\\s*version\\s+(\\d+)");
    private static final Pattern SHADOW_WORLD = Pattern.compile("\\bin\\s+vec3\\s+v_worldPos\\s*;");
    private static final Pattern SHADOW_TEX = Pattern.compile("\\bin\\s+vec3\\s+v_texcoord_isWater\\s*;");
    private static final Pattern WORLD_TERRAIN = Pattern.compile(
            "\\b(?:v_worldPos\\s*\\+\\s*cameraPosition|cameraPosition\\s*\\+\\s*v_worldPos)\\b");
    private static final Pattern VOXEL_CONDITION = Pattern.compile("\\bif\\s*\\(\\s*\\(*\\s*g_voxel\\s*\\[\\s*0\\s*]\\s*\\+\\s*"
            + "g_voxel\\s*\\[\\s*1\\s*]\\s*\\+\\s*g_voxel\\s*\\[\\s*2\\s*]\\s*\\)*\\s*<\\s*1000000000[uU]\\s*\\)");
    private static final Pattern CAMERA_FRACTION = Pattern.compile("\\buniform\\s+vec3\\s+cameraPositionFract\\s*;");
    private static final Pattern POSITION_LAYOUT = Pattern.compile(
            "\\blayout\\s*\\([^)]*\\b(?:origin_upper_left|pixel_center_integer)\\b[^)]*\\)\\s*in\\s+vec4\\s+gl_FragCoord\\s*;");
    private static final Pattern DIRECTIVES = Pattern.compile("(?m)^[ \\t]*#[ \\t]*(if|ifdef|ifndef|endif|version|extension)\\b[^\\r\\n]*(?:\\r?\\n|$)");
    private static final String WRAPPER = """

            uniform mat4 EarthMaskInverse;
            uniform vec4 EarthMaskViewport;
            uniform vec2 EarthMaskDepthRange;
            uniform int EarthMaskBits;
            void main() {
                if (EarthMaskBits != 15) {
                    vec2 screen = (gl_FragCoord.xy - EarthMaskViewport.xy) / EarthMaskViewport.zw * 2.0 - 1.0;
                    float depth = (gl_FragCoord.z - EarthMaskDepthRange.x) / (EarthMaskDepthRange.y - EarthMaskDepthRange.x) * 2.0 - 1.0;
                    vec4 local = EarthMaskInverse * vec4(screen, depth, 1.0);
                    vec2 cell = clamp(floor((local.xz / local.w) / 16.0), vec2(0.0), vec2(1.0));
                    float bit = cell.x + 2.0 * cell.y;
                    if (mod(floor(float(EarthMaskBits) / exp2(bit)), 2.0) < 0.5) discard;
                }
                earth_map_original_main();
            }
            """;
    private static final String NATIVE_WRAPPER = """

            layout(std140) uniform EarthMaskParams {
                mat4 EarthMaskInverse;
                vec4 EarthMaskViewport;
                vec4 EarthMaskDepthAndFlags;
                vec4 EarthMaskOrigin;
            };
            void main() {
                if (EarthMaskDepthAndFlags.z != 15.0) {
                    vec2 screen = (gl_FragCoord.xy - EarthMaskViewport.xy) / EarthMaskViewport.zw * 2.0 - 1.0;
                    float depth = (gl_FragCoord.z - EarthMaskDepthAndFlags.x) / (EarthMaskDepthAndFlags.y - EarthMaskDepthAndFlags.x);
                    depth = EarthMaskDepthAndFlags.w > 0.5 ? depth : depth * 2.0 - 1.0;
                    vec4 local = EarthMaskInverse * vec4(screen, depth, 1.0);
                    vec2 cell = clamp(floor((local.xz / local.w) / 16.0), vec2(0.0), vec2(1.0));
                    float bit = cell.x + 2.0 * cell.y;
                    if (mod(floor(EarthMaskDepthAndFlags.z / exp2(bit)), 2.0) < 0.5) discard;
                }
                earth_map_original_main();
            }
            """;
    private static final String SHADOW_WRAPPER = """

            uniform vec2 EarthMaskOrigin;
            uniform int EarthMaskBits;
            void main() {
                if (EarthMaskBits != 15) {
                    vec2 cell = clamp(floor((v_worldPos.xz - EarthMaskOrigin) / 16.0), vec2(0.0), vec2(1.0));
                    float bit = cell.x + 2.0 * cell.y;
                    if (mod(floor(float(EarthMaskBits) / exp2(bit)), 2.0) < 0.5) discard;
                }
                earth_map_original_main();
            }
            """;
    private static final String NATIVE_SHADOW_WRAPPER = """

            layout(std140) uniform EarthMaskParams {
                mat4 EarthMaskInverse;
                vec4 EarthMaskViewport;
                vec4 EarthMaskDepthAndFlags;
                vec4 EarthMaskOrigin;
            };
            void main() {
                if (EarthMaskDepthAndFlags.z != 15.0) {
                    vec2 cell = clamp(floor((v_worldPos.xz - EarthMaskOrigin.xy) / 16.0), vec2(0.0), vec2(1.0));
                    float bit = cell.x + 2.0 * cell.y;
                    if (mod(floor(EarthMaskDepthAndFlags.z / exp2(bit)), 2.0) < 0.5) discard;
                }
                earth_map_original_main();
            }
            """;

    public static Map<?, String> install(Map<?, String> sources) {
        return install(sources, WRAPPER);
    }

    public static Map<?, String> installNative(Map<?, String> sources) {
        if (MapPackTarget.current() != null && sources != null)
            sources = MapPackComplementaryMaterials.normalizeNativeTerrainFade(sources);
        return install(sources, NATIVE_WRAPPER);
    }

    private static Map<?, String> install(Map<?, String> sources, String wrapper) {
        if (MapPackTarget.current() == null || sources == null) return sources;
        Object fragmentKey = sources.keySet().stream().filter(key -> key instanceof Enum<?> stage
                && stage.name().equals("FRAGMENT")).findFirst().orElse(null);
        if (fragmentKey == null || sources.get(fragmentKey) == null) return sources;
        String source = sources.get(fragmentKey);
        boolean nativeDevice = wrapper == NATIVE_WRAPPER;
        if (nativeDevice) {
            var version = VERSION.matcher(source);
            if (!version.find() || Integer.parseInt(version.group(1)) < 140) {
                LogUtils.getLogger().warn("Cannot add map clipping to a shader without uniform-block support");
                return sources;
            }
        }
        String code = withoutComments(source);
        boolean worldShadow = SHADOW_WORLD.matcher(code).find() && SHADOW_TEX.matcher(code).find();
        boolean worldTerrain = SHADOW_WORLD.matcher(code).find() && code.contains("FsrDiscardFS")
                && WORLD_TERRAIN.matcher(code).find();
        // Clipping in the pack's own world coordinates remains stable under jitter and render scaling.
        if (worldShadow || worldTerrain)
            wrapper = nativeDevice ? NATIVE_SHADOW_WRAPPER : SHADOW_WRAPPER;
        else if (POSITION_LAYOUT.matcher(code).find()) {
            LogUtils.getLogger().warn("Cannot add screen-coordinate map clipping to a shader with a modified fragment origin");
            return sources;
        }
        var matcher = MAIN.matcher(code);
        if (!matcher.find() || matcher.find()) {
            LogUtils.getLogger().warn("Cannot add chunk clipping to a transformed map shader");
            return sources;
        }
        matcher.reset(); matcher.find();
        int name = matcher.end() - 1;
        while (name > matcher.start() && Character.isWhitespace(source.charAt(name - 1))) name--;
        int start = name - "main".length();
        if (!source.regionMatches(start, "main", 0, 4)) return sources;
        String masked = source.substring(0, start) + "earth_map_original_main" + source.substring(name) + wrapper;
        var copy = new HashMap<Object, String>();
        sources.forEach(copy::put);
        copy.put(fragmentKey, masked);
        if (worldShadow) {
            Object geometryKey = sources.keySet().stream().filter(key -> key instanceof Enum<?> stage
                    && stage.name().equals("GEOMETRY")).findFirst().orElse(null);
            if (geometryKey != null && sources.get(geometryKey) != null) {
                String geometry = installShadowVoxels(sources.get(geometryKey), nativeDevice);
                // Fragment clipping cannot undo writes already made by the geometry stage.
                if (geometry == null) return sources;
                copy.put(geometryKey, geometry);
            }
        }
        return MapPackTarget.buildsMaterialTiles() ? MapPackMaterialTileShaders.install(copy) : copy;
    }

    /** Masks iteration-style geometry-stage voxel writes at the same voxel they address. */
    private static String installShadowVoxels(String source, boolean nativeDevice) {
        String code = withoutComments(source);
        if (!code.contains("g_voxelCoord") || !code.contains("img_voxelID3D") || !code.contains("voxelResolution"))
            return source;
        var condition = VOXEL_CONDITION.matcher(code);
        if (!condition.find()) {
            LogUtils.getLogger().warn("Cannot add map clipping to the identified shader voxel write condition");
            return null;
        }
        String changed = source.substring(0, condition.start())
                + "if (g_voxel[0] + g_voxel[1] + g_voxel[2] < 1000000000u && "
                + "earthMapVoxelVisible(g_voxelCoord[0] * 0.33333333 + g_voxelCoord[1] * 0.33333333"
                + " + g_voxelCoord[2] * 0.33333333))"
                + source.substring(condition.end());
        String uniforms = nativeDevice ? "layout(std140) uniform EarthMaskParams { mat4 EarthMaskInverse; "
                + "vec4 EarthMaskViewport; vec4 EarthMaskDepthAndFlags; vec4 EarthMaskOrigin; };\n"
                : "uniform vec2 EarthMaskOrigin; uniform int EarthMaskBits;\n";
        if (!CAMERA_FRACTION.matcher(code).find()) uniforms += "uniform vec3 cameraPositionFract;\n";
        int insertion = directiveInsertion(changed);
        if (changed.indexOf("earthMapVoxelVisible(") < insertion) {
            LogUtils.getLogger().warn("Cannot place map voxel clipping declarations before their use");
            return null;
        }
        changed = changed.substring(0, insertion) + "\n" + uniforms + "bool earthMapVoxelVisible(vec3 coord);\n"
                + changed.substring(insertion);
        String bits = nativeDevice ? "EarthMaskDepthAndFlags.z" : "float(EarthMaskBits)";
        String origin = nativeDevice ? "EarthMaskOrigin.xy" : "EarthMaskOrigin";
        return changed + "\nbool earthMapVoxelVisible(vec3 coord) { float mask = " + bits + "; "
                + "if (mask == 15.0) return true; vec2 local = floor(coord.xz) + vec2(0.5)"
                + " - cameraPositionFract.xz - vec2(float(voxelResolution) * 0.5) - " + origin + "; "
                + "vec2 cell = clamp(floor(local / 16.0), vec2(0.0), vec2(1.0)); "
                + "return mod(floor(mask / exp2(cell.x + 2.0 * cell.y)), 2.0) >= 0.5; }\n";
    }

    /** Inserts declarations after extension directives and their enclosing conditional block. */
    static int directiveInsertion(String source) {
        var directives = DIRECTIVES.matcher(withoutComments(source));
        int insertion = 0, depth = 0;
        boolean nestedExtension = false;
        while (directives.find()) {
            String directive = directives.group(1);
            if (directive.equals("if") || directive.equals("ifdef") || directive.equals("ifndef")) depth++;
            else if (directive.equals("endif")) {
                if (depth > 0) depth--;
                if (depth == 0 && nestedExtension) {
                    insertion = directives.end();
                    nestedExtension = false;
                }
            } else if (directive.equals("version") || directive.equals("extension")) {
                if (depth == 0) insertion = directives.end();
                else nestedExtension = true;
            }
        }
        return insertion;
    }

    static String withoutComments(String source) {
        char[] text = source.toCharArray();
        boolean line = false, block = false;
        for (int i = 0; i < text.length; i++) {
            if (line) {
                if (text[i] == '\n' || text[i] == '\r') line = false;
                else text[i] = ' ';
            } else if (block) {
                if (text[i] == '*' && i + 1 < text.length && text[i + 1] == '/') {
                    text[i] = text[++i] = ' '; block = false;
                } else if (text[i] != '\n' && text[i] != '\r') text[i] = ' ';
            } else if (text[i] == '/' && i + 1 < text.length && text[i + 1] == '/') {
                text[i] = text[++i] = ' '; line = true;
            } else if (text[i] == '/' && i + 1 < text.length && text[i + 1] == '*') {
                text[i] = text[++i] = ' '; block = true;
            }
        }
        return new String(text);
    }

    private MapPackFragmentMask() { }
}
