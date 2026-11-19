package io.github.kltyton.xaeroearth.client.render.shaderpack;

import com.mojang.logging.LogUtils;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.MatchResult;
import java.util.regex.Pattern;
import java.util.function.Function;

/** Corrects compressed perspective-only projection expressions in an isolated orthographic map pass. */
public final class MapPackProjectionCompat {
    private static final String WS = "\\s*";
    private static final String NAME = "([A-Za-z_][A-Za-z_0-9]*)";
    // Iris applies its GLSL preprocessor and printer before Earth sees the stages.
    private static final String ZERO = "0\\.0+[fF]?";
    private static final String HALF = "0\\.50*[fF]?";
    private static final String ONE = "1\\.0+[fF]?";
    private static final String INV_XY = "vec2" + WS + "\\(" + WS + "gbufferProjectionInverse0" + WS
            + "\\." + WS + "x" + WS + "," + WS + "gbufferProjectionInverse0" + WS + "\\." + WS + "y" + WS + "\\)";
    private static final Pattern VIEW = Pattern.compile("vec3" + WS + "\\(" + WS + INV_XY + WS + "\\*" + WS
            + NAME + WS + "\\." + WS + "xy" + WS + "," + WS + ZERO + WS + "\\)" + WS
            + "\\+" + WS + "gbufferProjectionInverse1\\b");
    private static final Pattern PREVIOUS_VIEW = Pattern.compile("vec3" + WS + "\\(" + WS + "vec2" + WS + "\\(" + WS
            + "ssb_gbufferPreviousProjectionInverse0" + WS + "\\." + WS + "x" + WS + "," + WS
            + "ssb_gbufferPreviousProjectionInverse0" + WS + "\\." + WS + "y" + WS + "\\)" + WS + "\\*" + WS
            + NAME + WS + "\\." + WS + "xy" + WS + "," + WS + ZERO + WS + "\\)" + WS
            + "\\+" + WS + "ssb_gbufferPreviousProjectionInverse1\\b");
    private static final Pattern CURRENT_SCREEN = Pattern.compile("\\bscreenPos" + WS + "\\*" + WS + "\\(" + WS
            + HALF + WS + "/" + WS + "-" + WS + "viewPos" + WS + "\\." + WS + "z" + WS + "\\)");
    private static final Pattern CURRENT_POSITION = Pattern.compile("\\bvec3" + WS + "screenPos" + WS + "=" + WS
            + "vec3" + WS + "\\(" + WS + "gbufferProjection0" + WS + "\\." + WS + "x" + WS + "," + WS
            + "gbufferProjection0" + WS + "\\." + WS + "y" + WS + "," + WS + "gbufferProjection0" + WS
            + "\\." + WS + "z" + WS + "\\)" + WS + "\\*" + WS + "viewPos" + WS + "\\+" + WS
            + "gbufferProjection1\\b");
    private static final Pattern VIEW_DIRECTION = Pattern.compile("\\bvec3" + WS + "viewDir" + WS + "=" + WS
            + "normalize" + WS + "\\(" + WS + "viewPos" + WS + "(?:\\." + WS + "xyz" + WS + ")?\\)");
    private static final Pattern LIGHT_AND_NORMAL_BASIS = Pattern.compile(
            "\\b((?:viewNormal|viewVertexNormal|shadowVector)\\s*=\\s*)"
                    + "([^;{}]*\\bmat3\\s*\\(\\s*gbufferModelView(?:Inverse)?\\s*\\)[^;{}]*)(?=;)");
    private static final Pattern WORLD_DIRECTION = Pattern.compile("(\\bvec3" + WS + "worldPos" + WS + "=" + WS
            + "mat3" + WS + "\\(" + WS + "gbufferModelViewInverse" + WS + "\\)" + WS + "\\*" + WS
            + "(?:viewPos\\b|ViewPos_From_ScreenPos\\b)[^;{}]*;" + WS
            + "(?:vec3" + WS + "viewDir" + WS + "=[^;{}]*;" + WS + ")?)"
            + "vec3" + WS + "worldDir" + WS + "=" + WS
            + "normalize" + WS + "\\(" + WS + "worldPos" + WS + "(?:\\." + WS + "xyz" + WS + ")?\\)");
    private static final Pattern DIRECT_WORLD_DIRECTION = Pattern.compile("\\bvec3" + WS + "worldDir" + WS + "=" + WS
            + "normalize" + WS + "\\(" + WS + "mat3" + WS + "\\(" + WS + "gbufferModelViewInverse" + WS
            + "\\)" + WS + "\\*" + WS + "viewPos" + WS + "\\)");
    private static final Pattern PARALLAX_VIEW_DIRECTION = Pattern.compile("\\bviewPosRaw" + WS + "\\+=" + WS
            + "parallaxDist" + WS + "\\*" + WS + "normalize" + WS + "\\(" + WS + "viewPosRaw" + WS + "\\)");
    private static final Pattern SAMPLE_PARALLAX_DIRECTION = Pattern.compile("(\\bsampleViewPos" + WS + "\\+=" + WS
            + "saturate" + WS + "\\(" + WS + "parallaxDist" + WS + "-" + WS + "sampleViewPos" + WS + "\\." + WS
            + "z" + WS + "\\*" + WS + "1\\.1451e-4" + WS + "\\)" + WS + "\\*" + WS + ")"
            + "normalize" + WS + "\\(" + WS + "sampleViewPos" + WS + "\\)");
    private static final Pattern SAMPLE_REFLECTION_FACING = Pattern.compile("\\bdot" + WS + "\\(" + WS
            + "sampleVertexNormal" + WS + "," + WS + "-" + WS + "normalize" + WS + "\\(" + WS
            + "sampleWorldPos" + WS + "\\)" + WS + "\\)");
    private static final Pattern WATER_VIEW_DIRECTION = Pattern.compile("\\bvec3" + WS + "viewVector" + WS + "=" + WS
            + "v_worldPos" + WS + "-" + WS + "gbufferModelViewInverse" + WS + "\\[" + WS + "3" + WS + "]"
            + WS + "\\." + WS + "xyz");
    private static final Pattern WAVE_PARALLAX_CALL = Pattern.compile("\\bmcPos" + WS + "=" + WS
            + "WaveParallax" + WS + "\\(" + WS + "mcPos" + WS + "," + WS + "viewVector" + WS + ","
            + WS + "tbn" + WS + "\\[" + WS + "2" + WS + "\\]" + WS + "\\." + WS + "y" + WS + ","
            + WS + "noise" + WS + "," + WS + "parallaxWaterDist" + WS + "\\);");
    private static final Pattern VIEW_DISTANCE = Pattern.compile("\\b(opaqueDist|waterDist)" + WS + "=([^;]*?)"
            + "\\blength" + WS + "\\(" + WS + "(viewPos(?:Opaque)?)" + WS + "\\)");
    private static final Pattern WATER_OPAQUE_DISTANCE = Pattern.compile("\\bfloat" + WS + "opaqueDist" + WS + "=" + WS
            + "length" + WS + "\\(" + WS + "ViewPos_From_ScreenPos" + WS + "\\(" + WS
            + "gl_FragCoord" + WS + "\\." + WS + "xy" + WS + "\\*" + WS
            + "(?:UNIFORM_PIXEL_SIZE|pixelSize|fsrPixelSize)" + WS + "," + WS
            + "texelFetch" + WS + "\\(" + WS + "depthtex1" + WS + "," + WS + "ivec2" + WS + "\\(" + WS
            + "gl_FragCoord" + WS + "\\." + WS + "xy" + WS + "\\)" + WS + "," + WS + "0" + WS + "\\)" + WS
            + "\\." + WS + "x" + WS + "\\)" + WS + "\\)" + WS + ";");
    private static final Pattern PACKED_WATER_THICKNESS = Pattern.compile("\\bdist" + WS + "=" + WS
            + "opaqueDist" + WS + "-" + WS + "dist" + WS + ";");
    private static final Pattern WATER_THICKNESS = Pattern.compile("\\bwaterDeep" + WS + "=" + WS
            + "opaqueDist" + WS + "-" + WS + "waterDist" + WS + ";");
    private static final Pattern WATER_REFRACTION_STEP = Pattern.compile("\\brefractPos" + WS + "\\+=" + WS
            + "([^;{}]*\\bwaterDeep\\b[^;{}]*);");
    private static final Pattern REFRACTION_NORMAL = Pattern.compile("\\bvertexNormal" + WS + "=" + WS
            + "vertexNormal" + WS + "\\*" + WS + "mat3" + WS + "\\(" + WS + "gbufferModelViewInverse"
            + WS + "\\);");
    private static final Pattern REFRACTED_WATER_DATA = Pattern.compile("\\bwaterData" + WS + "=" + WS
            + "texelFetch" + WS + "\\(" + WS + "FBTEX_GWATER" + WS + "," + WS + "ivec2" + WS + "\\("
            + WS + "refractCoord" + WS + "\\*" + WS + "(?:UNIFORM_SCREEN_SIZE|screenSize|fsrScreenSize)" + WS + "\\)" + WS + ","
            + WS + "0" + WS + "\\);");
    private static final String ORTHOGRAPHIC = "abs(gbufferProjection[2][3]) < 1e-6";
    private static final String WORLD_UNITS_PER_VIEW_UNIT = "(abs(gbufferProjection[2][3]) < 1e-6"
            + " ? length(mat3(gbufferModelViewInverse) * vec3(0.0, 0.0, -1.0)) : 1.0)";
    private static final String BLOCKS_PER_WINDOW_DEPTH = "(-2.0 * gbufferProjectionInverse[2][2] * "
            + WORLD_UNITS_PER_VIEW_UNIT + ")";
    private static final Pattern CURRENT_SCREEN_XY = Pattern.compile("\\(" + WS + "vec2" + WS + "\\(" + WS
            + "gbufferProjection0" + WS + "\\." + WS + "x" + WS + "," + WS
            + "gbufferProjection0" + WS + "\\." + WS + "y" + WS + "\\)" + WS + "\\*" + WS + NAME
            + WS + "\\." + WS + "xy" + WS + "\\+" + WS + "gbufferProjection1" + WS + "\\." + WS
            + "xy" + WS + "\\)" + WS + "/" + WS + "-" + WS + "\\1" + WS + "\\." + WS + "z\\b");
    private static final Pattern SCREEN_RAY_START = Pattern.compile("\\bstart" + WS + "=" + WS + "vec3" + WS
            + "\\(" + WS + "vec2" + WS + "\\(" + WS + "gbufferProjection0" + WS + "\\." + WS + "x" + WS
            + "," + WS + "gbufferProjection0" + WS + "\\." + WS + "y" + WS + "\\)" + WS + "\\*" + WS
            + "start" + WS + "\\." + WS + "xy" + WS + "," + WS + "-" + WS + "start" + WS + "\\." + WS + "z" + WS + "\\)");
    private static final Pattern SCREEN_RAY_PROJECT = Pattern.compile("\\b(start|end)" + WS + "=" + WS + "vec3" + WS
            + "\\(" + WS + "vec2" + WS + "\\(" + WS + "gbufferProjection0" + WS + "\\." + WS + "x" + WS
            + "," + WS + "gbufferProjection0" + WS + "\\." + WS + "y" + WS + "\\)" + WS + "\\*" + WS
            + "\\1" + WS + "\\." + WS + "xy" + WS + "," + WS + "-" + WS + "\\1" + WS + "\\." + WS + "z" + WS + "\\)");
    private static final Pattern SCREEN_RAY_COORD = Pattern.compile("\\bsamplePos" + WS + "\\." + WS + "xy" + WS
            + "=" + WS + "samplePos" + WS + "\\." + WS + "xy" + WS + "/" + WS + "samplePos" + WS
            + "\\." + WS + "z" + WS + "\\+" + WS + "offsetCoord\\b");
    private static final Pattern SCREEN_RAY_WIDTH = Pattern.compile("\\bscreenRayDir" + WS + "\\." + WS + "xy" + WS
            + "/" + WS + "start" + WS + "\\." + WS + "z" + WS + "\\*" + WS + "screenSize\\b");
    private static final Pattern PREVIOUS_SCREEN = Pattern.compile("\\(" + WS + "vec3" + WS + "\\(" + WS
            + "gbufferPreviousProjection0" + WS + "\\." + WS + "x" + WS + "," + WS
            + "gbufferPreviousProjection0" + WS + "\\." + WS + "y" + WS + "," + WS
            + "gbufferPreviousProjection0" + WS + "\\." + WS + "z" + WS + "\\)" + WS + "\\*" + WS + NAME
            + WS + "\\+" + WS + "gbufferPreviousProjection1" + WS + "\\)" + WS + "/" + WS + "-" + WS
            + "\\1" + WS + "\\." + WS + "z\\b");
    private static final Pattern PREVIOUS_SCREEN_XY = Pattern.compile("\\(" + WS + "vec2" + WS + "\\(" + WS
            + "gbufferPreviousProjection0" + WS + "\\." + WS + "x" + WS + "," + WS
            + "gbufferPreviousProjection0" + WS + "\\." + WS + "y" + WS + "\\)" + WS + "\\*" + WS + NAME
            + WS + "\\." + WS + "xy" + WS + "\\+" + WS + "gbufferPreviousProjection1" + WS + "\\." + WS
            + "xy" + WS + "\\)" + WS + "/" + WS + "-" + WS + "\\1" + WS + "\\." + WS + "z\\b");
    private static final Pattern LINEAR_DEPTH = Pattern.compile("\\breturn" + WS + ONE + WS + "/" + WS
            + "\\(" + WS + "depth" + WS + "\\*" + WS + "gbufferProjectionInverse0" + WS + "\\." + WS + "z"
            + WS + "\\+" + WS + "gbufferProjectionInverse0" + WS + "\\." + WS + "w" + WS + "\\)");
    private static final Pattern SCREEN_DEPTH = Pattern.compile("\\bdepth" + WS + "=" + WS + "\\(" + WS + ONE
            + WS + "/" + WS + "depth" + WS + "-" + WS + "gbufferProjectionInverse0" + WS + "\\." + WS + "w"
            + WS + "\\)" + WS + "/" + WS + "gbufferProjectionInverse0" + WS + "\\." + WS + "z");
    private static final Pattern PREVIOUS_SAMPLE = Pattern.compile("\\bsampleViewPos" + WS + "\\." + WS + "z" + WS
            + "=" + WS + "gbufferPreviousProjection1" + WS + "\\." + WS + "z" + WS + "/" + WS + "\\(" + WS
            + "sampleViewPos" + WS + "\\." + WS + "z" + WS + "\\+" + WS + "gbufferPreviousProjection0" + WS
            + "\\." + WS + "z" + WS + "\\)" + WS + ";" + WS + "sampleViewPos" + WS + "=" + WS
            + "vec3" + WS + "\\(" + WS + "sampleViewPos" + WS + "\\." + WS + "xy" + WS + "/" + WS
            + "vec2" + WS + "\\(" + WS + "gbufferPreviousProjection0" + WS + "\\." + WS + "x" + WS + "," + WS
            + "gbufferPreviousProjection0" + WS + "\\." + WS + "y" + WS + "\\)" + WS + "\\*" + WS
            + "sampleViewPos" + WS + "\\." + WS + "z" + WS + "," + WS + "-" + WS + "sampleViewPos" + WS
            + "\\." + WS + "z" + WS + "\\)");
    private static final Pattern PREVIOUS_MATRIX = Pattern.compile("\\bmat4" + WS + "gbufferPreviousProjection" + WS
            + "=" + WS + "mat4" + WS + "\\(([^;{}]*)\\)" + WS + ";", Pattern.DOTALL);
    private static final Pattern WARPED_SHADOW_RESULT = Pattern.compile("\\bresult" + WS + "=" + WS
            + "mix" + WS + "\\(" + WS + "result" + WS + "," + WS + "caustics\\.rgb" + WS + ","
            + WS + "caustics\\.a" + WS + "\\*" + WS + "rSteps" + WS + "\\)" + WS + ";");

    public static Map<?, String> install(Map<?, String> sources) {
        if (MapPackTarget.current() == null || sources == null) return sources;
        sources = MapPackDepthMapping.install(sources);
        Map<Object, String> changed = null;
        for (var entry : sources.entrySet()) {
            String result = install(entry.getValue());
            if (result == entry.getValue()) continue;
            if (changed == null) { changed = new HashMap<>(); changed.putAll(sources); }
            changed.put(entry.getKey(), result);
        }
        return changed == null ? sources : changed;
    }

    public static String install(String source) {
        if (MapPackTarget.current() == null || source == null) return source;
        // Map optics scale positions to keep fog in range. The BRDF and shadow bias still require unit directions.
        String result = replace(source, LIGHT_AND_NORMAL_BASIS, match ->
                match.group(1) + "normalize(" + match.group(2) + ")");
        if (!source.contains("gbufferProjection") && !source.contains("gbufferPreviousProjection")) return result;
        if (source.contains("SampleRTWWarpSmoothWithPixelSize") && source.contains("shadowCoord")
                && source.contains("caustics.rgb")) {
            result = replace(result, WARPED_SHADOW_RESULT, match -> match.group() + """

                    if (abs(gbufferProjection[2][3]) < 1e-6) {
                        vec2 earthShadowBorder = min(shadowCoord, vec2(1.0) - shadowCoord);
                        float earthShadowCoverage = min(min(earthShadowBorder.x, earthShadowBorder.y),
                                1.0 - shadowScreenPos.z);
                        result = mix(vec3(1.0), result, smoothstep(0.0, 0.12, earthShadowCoverage));
                    }
                    """);
        }
        result = replace(result, VIEW, match -> "vec3(vec2(gbufferProjectionInverse0.x, gbufferProjectionInverse0.y) * "
                + match.group(1) + ".xy + vec2(gbufferProjectionInverse[1][0] * " + match.group(1)
                + ".y, gbufferProjectionInverse[0][1] * " + match.group(1) + ".x), gbufferProjectionInverse[2][2] * "
                + match.group(1) + ".z) + gbufferProjectionInverse1");
        result = replace(result, PREVIOUS_VIEW, match -> "vec3(vec2(ssb_gbufferPreviousProjectionInverse0.x, "
                + "ssb_gbufferPreviousProjectionInverse0.y) * " + match.group(1)
                + ".xy - vec2(gbufferPreviousProjection[1][0] * " + match.group(1)
                + ".y, gbufferPreviousProjection[0][1] * " + match.group(1)
                + ".x) / (gbufferPreviousProjection0.x * gbufferPreviousProjection0.y"
                + " - gbufferPreviousProjection[1][0] * gbufferPreviousProjection[0][1]), "
                + "(gbufferPreviousProjection[3][3] / (gbufferPreviousProjection[2][2] * gbufferPreviousProjection[3][3]"
                + " - gbufferPreviousProjection[3][2] * gbufferPreviousProjection[2][3])) * " + match.group(1)
                + ".z) + ssb_gbufferPreviousProjectionInverse1");
        result = replace(result, VIEW_DIRECTION, ignored -> "vec3 viewDir = abs(gbufferProjection[2][3]) < 1e-6"
                + " ? vec3(0.0, 0.0, -1.0) : normalize(viewPos.xyz)");
        result = replace(result, WORLD_DIRECTION, match -> match.group(1)
                + "vec3 worldDir = abs(gbufferProjection[2][3]) < 1e-6"
                + " ? normalize(mat3(gbufferModelViewInverse) * vec3(0.0, 0.0, -1.0)) : normalize(worldPos.xyz)");
        result = replace(result, DIRECT_WORLD_DIRECTION, ignored -> "vec3 worldDir = normalize(mat3(gbufferModelViewInverse)"
                + " * (abs(gbufferProjection[2][3]) < 1e-6 ? vec3(0.0, 0.0, -1.0) : viewPos))");
        result = replace(result, PARALLAX_VIEW_DIRECTION, ignored -> "viewPosRaw += parallaxDist * "
                + "(abs(gbufferProjection[2][3]) < 1e-6 ? vec3(0.0, 0.0, -1.0) : normalize(viewPosRaw))");
        result = replace(result, SAMPLE_PARALLAX_DIRECTION, match -> match.group(1)
                + "(abs(gbufferProjection[2][3]) < 1e-6 ? vec3(0.0, 0.0, -1.0) : normalize(sampleViewPos))");
        if (result.contains("ViewPos_From_ScreenPos_Raw") && result.contains("sampleWorldPos"))
            result = replace(result, SAMPLE_REFLECTION_FACING, ignored -> "dot(sampleVertexNormal, "
                    + "-(abs(gbufferProjection[2][3]) < 1e-6"
                    + " ? normalize(mat3(gbufferModelViewInverse) * vec3(0.0, 0.0, -1.0)) : normalize(sampleWorldPos)))");
        if (result.contains("WaveParallax")) {
            result = replace(result, WATER_VIEW_DIRECTION, ignored -> "vec3 viewVector = "
                    + "abs(gbufferProjection[2][3]) < 1e-6 ? mat3(gbufferModelViewInverse) * vec3(0.0, 0.0, -1.0)"
                    + " : v_worldPos - gbufferModelViewInverse[3].xyz");
            // WaveParallax adds a displacement measured in blocks. Its result is stored in
            // an image whose readers expect the same optical units as the unshifted water depth.
            result = replace(result, WAVE_PARALLAX_CALL, ignored -> "float earthWaveUnits = "
                    + WORLD_UNITS_PER_VIEW_UNIT + "; float earthWaveDepth = parallaxWaterDist * earthWaveUnits;"
                    + " mcPos = WaveParallax(mcPos, viewVector, tbn[2].y, noise, earthWaveDepth);"
                    + " parallaxWaterDist = earthWaveDepth / earthWaveUnits;");
        }
        result = replace(result, VIEW_DISTANCE, match -> match.group(1) + " =" + match.group(2)
                + "(abs(gbufferProjection[2][3]) < 1e-6 ? -" + match.group(3) + ".z : length("
                + match.group(3) + "))");
        String waterCode = MapPackFragmentMask.withoutComments(result);
        if (WATER_OPAQUE_DISTANCE.matcher(waterCode).find() && PACKED_WATER_THICKNESS.matcher(waterCode).find()) {
            // The difference of window depths along an orthographic ray is affine. Its
            // translation cancels, including the map's distant virtual eye and viewport crop.
            result = replace(result, PACKED_WATER_THICKNESS, ignored -> "dist = " + ORTHOGRAPHIC
                    + " ? max(0.0, (texelFetch(depthtex1, ivec2(gl_FragCoord.xy), 0).x - gl_FragCoord.z) * "
                    + BLOCKS_PER_WINDOW_DEPTH + ") : opaqueDist - dist;");
        }
        if (result.contains("GlassRefraction") && result.contains("WaterFog")) {
            result = replace(result, WATER_THICKNESS, ignored -> "waterDeep = " + ORTHOGRAPHIC
                    + " ? max(0.0, (refractDepth - depth) * " + BLOCKS_PER_WINDOW_DEPTH + ") : opaqueDist - waterDist;");
            result = replace(result, WATER_REFRACTION_STEP, match -> "refractPos += (" + match.group(1)
                    + ") / " + WORLD_UNITS_PER_VIEW_UNIT + ";");
            // The inverse view includes the optical scale. Subtract the two unit normals in the same space.
            result = replace(result, REFRACTION_NORMAL, ignored -> "vertexNormal = " + ORTHOGRAPHIC
                    + " ? normalize(vertexNormal * mat3(gbufferModelViewInverse))"
                    + " : vertexNormal * mat3(gbufferModelViewInverse);");
            // Refraction offsets the sample behind the surface. It must not replace the visible water
            // fragment's mask with the material of a neighboring pixel, which can be dry riverbank.
            result = replace(result, REFRACTED_WATER_DATA, match -> "if ((" + ORTHOGRAPHIC
                    + ") && mask.water > 0.5) { waterData = texelFetch(FBTEX_GWATER, texelCoord, 0); }"
                    + " else { " + match.group() + " }");
        }
        if (result.contains("gbufferProjection0"))
            result = replace(result, CURRENT_SCREEN, ignored -> "screenPos * (0.5 / (gbufferProjection[2][3] * viewPos.z + gbufferProjection[3][3]))");
        result = replace(result, CURRENT_POSITION, ignored -> "vec3 screenPos = vec3(gbufferProjection0.x, "
                + "gbufferProjection0.y, gbufferProjection0.z) * viewPos + gbufferProjection1"
                + " + vec3(gbufferProjection[1][0] * viewPos.y, gbufferProjection[0][1] * viewPos.x, 0.0)");
        result = replace(result, CURRENT_SCREEN_XY, match -> "(vec2(gbufferProjection0.x, gbufferProjection0.y) * "
                + match.group(1) + ".xy + gbufferProjection1.xy + vec2(gbufferProjection[1][0] * "
                + match.group(1) + ".y, gbufferProjection[0][1] * " + match.group(1) + ".x)) / (gbufferProjection[2][3] * "
                + match.group(1) + ".z + gbufferProjection[3][3])");
        if (SCREEN_RAY_START.matcher(MapPackFragmentMask.withoutComments(result)).find()) {
            result = replace(result, SCREEN_RAY_PROJECT, match -> match.group(1)
                    + " = vec3(vec2(gbufferProjection0.x, gbufferProjection0.y) * " + match.group(1)
                    + ".xy + vec2(gbufferProjection[1][0] * " + match.group(1)
                    + ".y, gbufferProjection[0][1] * " + match.group(1) + ".x), -" + match.group(1) + ".z)");
            result = replace(result, SCREEN_RAY_COORD, ignored -> "samplePos.xy = samplePos.xy / "
                    + "(-gbufferProjection[2][3] * samplePos.z + gbufferProjection[3][3]) + offsetCoord");
            result = replace(result, SCREEN_RAY_WIDTH, ignored -> "screenRayDir.xy / "
                    + "(-gbufferProjection[2][3] * start.z + gbufferProjection[3][3]) * screenSize");
        }
        result = replace(result, PREVIOUS_SCREEN, match -> "(vec3(gbufferPreviousProjection0.x, gbufferPreviousProjection0.y, "
                + "gbufferPreviousProjection0.z) * " + match.group(1) + " + gbufferPreviousProjection1"
                + " + vec3(gbufferPreviousProjection[1][0] * " + match.group(1)
                + ".y, gbufferPreviousProjection[0][1] * " + match.group(1) + ".x, 0.0)) / "
                + "(gbufferPreviousProjection[2][3] * " + match.group(1) + ".z + gbufferPreviousProjection[3][3])");
        result = replace(result, PREVIOUS_SCREEN_XY, match -> "(vec2(gbufferPreviousProjection0.x, gbufferPreviousProjection0.y) * "
                + match.group(1) + ".xy + gbufferPreviousProjection1.xy + vec2(gbufferPreviousProjection[1][0] * "
                + match.group(1) + ".y, gbufferPreviousProjection[0][1] * " + match.group(1)
                + ".x)) / (gbufferPreviousProjection[2][3] * "
                + match.group(1) + ".z + gbufferPreviousProjection[3][3])");
        result = replace(result, LINEAR_DEPTH, ignored -> "return -(gbufferProjectionInverse[2][2] * depth + gbufferProjectionInverse1.z)"
                + " / (depth * gbufferProjectionInverse0.z + gbufferProjectionInverse0.w)");
        result = replace(result, SCREEN_DEPTH, ignored -> "depth = (-gbufferProjectionInverse1.z - depth * gbufferProjectionInverse0.w)"
                + " / (depth * gbufferProjectionInverse0.z + gbufferProjectionInverse[2][2])");
        result = replace(result, PREVIOUS_SAMPLE, ignored -> "{ float earthMapDet = gbufferPreviousProjection[2][2]"
                + " * gbufferPreviousProjection[3][3] - gbufferPreviousProjection[3][2] * gbufferPreviousProjection[2][3]; "
                + "float earthMapPreviousW = (gbufferPreviousProjection[2][2] - gbufferPreviousProjection[2][3]"
                + " * sampleViewPos.z) / earthMapDet; "
                + "float earthMapPreviousZ = (gbufferPreviousProjection[3][3] * sampleViewPos.z"
                + " - gbufferPreviousProjection[3][2]) / earthMapDet; "
                + "vec2 earthMapPreviousXY = sampleViewPos.xy - gbufferPreviousProjection1.xy * earthMapPreviousW; "
                + "float earthMapPreviousXYDet = gbufferPreviousProjection0.x * gbufferPreviousProjection0.y"
                + " - gbufferPreviousProjection[1][0] * gbufferPreviousProjection[0][1]; "
                + "sampleViewPos = vec3(vec2(gbufferPreviousProjection0.y * earthMapPreviousXY.x"
                + " - gbufferPreviousProjection[1][0] * earthMapPreviousXY.y,"
                + " gbufferPreviousProjection0.x * earthMapPreviousXY.y"
                + " - gbufferPreviousProjection[0][1] * earthMapPreviousXY.x)"
                + " / earthMapPreviousXYDet, earthMapPreviousZ) / earthMapPreviousW; }");
        result = replace(result, PREVIOUS_MATRIX, match -> {
            String[] values = match.group(1).split(",");
            if (values.length != 16 || !values[0].contains("gbufferPreviousProjection0")
                    || !values[10].contains("gbufferPreviousProjection0") || !values[14].contains("gbufferPreviousProjection1"))
                return match.group();
            return "mat4 earthMapPreviousProjection = gbufferPreviousProjection; "
                    + "earthMapPreviousProjection[0][0] = gbufferPreviousProjection0.x; "
                    + "earthMapPreviousProjection[1][1] = gbufferPreviousProjection0.y; "
                    + "earthMapPreviousProjection[2][2] = gbufferPreviousProjection0.z; "
                    + "earthMapPreviousProjection[3].xyz = gbufferPreviousProjection1; "
                    + "mat4 gbufferPreviousProjection = earthMapPreviousProjection;";
        });
        if (result.equals(source)) return source;
        if (result.contains("gbufferProjectionInverse[")) {
            result = ensureUniform(result, "gbufferProjectionInverse");
            if (result == null) return source;
        }
        if (result.contains(WORLD_UNITS_PER_VIEW_UNIT)) {
            result = ensureUniform(result, "gbufferModelViewInverse");
            if (result == null) return source;
        }
        if (result.contains("gbufferProjection[")) {
            result = ensureUniform(result, "gbufferProjection");
            if (result == null) return source;
        }
        if (result.contains("gbufferPreviousProjection[")
                || result.contains("earthMapPreviousProjection = gbufferPreviousProjection")) {
            result = ensureUniform(result, "gbufferPreviousProjection");
            if (result == null) return source;
        }
        return result;
    }

    private static String replace(String source, Pattern pattern, Function<MatchResult, String> replacement) {
        var matches = pattern.matcher(MapPackFragmentMask.withoutComments(source));
        StringBuilder result = null;
        int end = 0;
        while (matches.find()) {
            String value = replacement.apply(matches.toMatchResult());
            if (value.equals(source.substring(matches.start(), matches.end()))) continue;
            if (result == null) result = new StringBuilder(source.length() + 128);
            result.append(source, end, matches.start()).append(value);
            end = matches.end();
        }
        return result == null ? source : result.append(source, end, source.length()).toString();
    }

    private static String ensureUniform(String source, String name) {
        var declaration = Pattern.compile("\\buniform\\s+(?:(?:lowp|mediump|highp)\\s+)?mat4\\s+" + name + "\\s*;");
        String code = MapPackFragmentMask.withoutComments(source);
        if (declaration.matcher(code).find()) return source;
        int insertion = MapPackFragmentMask.directiveInsertion(source);
        var firstUse = Pattern.compile("\\b" + name + "\\b").matcher(code);
        if (firstUse.find() && firstUse.start() < insertion) {
            LogUtils.getLogger().warn("Cannot declare map projection uniform {} before its first use", name);
            return null;
        }
        return source.substring(0, insertion) + "\nuniform mat4 " + name + ";\n" + source.substring(insertion);
    }

    private MapPackProjectionCompat() { }
}
