package io.github.kltyton.xaeroearth.client.render.shaderpack;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Replays the default Complementary terrain inputs through its current material and lighting program. */
final class MapPackComplementaryMaterials {
    private static final List<String> VARYINGS = List.of(
            "int mat", "vec2 texCoord", "vec2 lmCoord", "vec2 signMidCoordPos", "vec2 absMidCoordPos",
            "vec3 normal", "vec3 vertexPos", "vec4 glColorRaw");
    private static final List<String> LIGHTING_INPUTS = List.of(
            "float NdotU", "float geoNdotU", "float NdotUmax0", "vec4 glColor", "float miplevel");
    private static final Pattern BASE_COLOR = Pattern.compile(
            "\\bvec4\\s+color\\s*=\\s*texture\\s*\\(\\s*tex\\s*,\\s*texCoord\\s*\\)\\s*;");
    private static final Pattern TERRAIN_POSITION = Pattern.compile(
            "\\bgl_Position\\s*=\\s*[^;{}]*\\bgbufferModelView\\b[^;{}]*\\bposition\\b[^;{}]*;");
    private static final Pattern JITTER = Pattern.compile("\\bgl_Position\\.xy\\s*=\\s*TAAJitter\\s*\\([^;{}]*;");
    private static final Pattern LIVE_ONLY_INPUT = Pattern.compile(
            "\\b(?:spriteBounds|vTexCoordAM|viewVector|tbnMatrix|clrwl_computeFragment)\\b");
    private static final Pattern CHUNK_FADE = Pattern.compile("\\bchunkFade\\b");
    private static final Pattern FIXED_FADE = Pattern.compile(
            "\\bconst\\s+float\\s+mc_chunkFade\\s*=\\s*(-?\\d+\\.\\d+[fF]?)\\s*;");
    private static final Pattern FADE_WRITE = Pattern.compile("\\bchunkFade\\s*=\\s*([^;]+);");
    private static final Pattern FADE_INPUT = Pattern.compile("(?m)^\\h*flat\\h+in\\h+float\\h+chunkFade\\h*;");
    private static final Pattern ENCODED_CHUNK_FADE = Pattern.compile(
            "\\bskyLightFactor\\s*=\\s*1\\.0[fF]?\\s*-\\s*chunkFade\\s*\\*\\s*0\\.5[fF]?\\s*;");
    // Complementary's DoWave terrain materials, including optional foliage and Nether variants.
    private static final Set<Integer> WAVING = Set.of(
            10005, 10009, 10021, 10068, 10489, 10769, 10884, 10885, 10972, 10976, 11009, 32000);
    private static final String CAPTURE = """

            void earthCaptureComplementary() {
                vec4 albedo = texture(tex, texCoord);
                if (albedo.a <= 0.5) discard;
                iris_FragData0 = albedo;
                iris_FragData1 = glColorRaw;
                iris_FragData2 = vec4(normal, float(mat & 255));
                earthTileTangent = vec4(lmCoord, signMidCoordPos);
                vec2 atlasCoordinate = texCoord * 1024.0;
                earthTileBitangent = vec4(floor(atlasCoordinate), fract(atlasCoordinate));
                earthTileNormal = vec4(absMidCoordPos, miplevel, float(mat >> 8));
            }
            """;
    private static final String REPLAY = """

            void earthReplayComplementary() {
            """ + MapPackMaterialTileShaders.REPLAY_ADDRESS + """
                earthMaterialAlbedo = texelFetch(EarthMaterialPlanes, ivec3(sampleAt, 0), 0);
                if (earthMaterialAlbedo.a <= 0.5) discard;
                glColorRaw = texelFetch(EarthMaterialPlanes, ivec3(sampleAt, 1), 0);
                vec4 normals = texelFetch(EarthMaterialPlanes, ivec3(sampleAt, 2), 0);
                normal = normalize(normals.xyz);
                vec4 lighting = texelFetch(EarthMaterialPlanes, ivec3(sampleAt, 3), 0);
                lmCoord = lighting.xy;
                signMidCoordPos = lighting.zw;
                vec4 atlasCoordinate = texelFetch(EarthMaterialPlanes, ivec3(sampleAt, 4), 0);
                texCoord = (atlasCoordinate.xy + atlasCoordinate.zw) / 1024.0;
                vec4 material = texelFetch(EarthMaterialPlanes, ivec3(sampleAt, 5), 0);
                absMidCoordPos = material.xy;
                miplevel = material.z;
                mat = int(material.w) * 256 + int(normals.w);
                vec3 earthWorldNormal = normalize(mat3(gbufferModelViewInverse) * normal);
            """ + MapPackMaterialTileShaders.REPLAY_DEPTH + """
                vertexPos = position.xyz / position.w;
                earthMaterialFragmentDepth = earthTerrainDepth;
                glColor = glColorRaw;
                NdotU = dot(normal, upVec);
                geoNdotU = NdotU;
                NdotUmax0 = max(NdotU, 0.0);
                earth_map_original_main();
            }
            """;

    static Map<?, String> normalizeNativeTerrainFade(Map<?, String> sources) {
        var vertexKey = MapPackMaterialTileShaders.key(sources, "VERTEX");
        var fragmentKey = MapPackMaterialTileShaders.key(sources, "FRAGMENT");
        if (vertexKey == null || fragmentKey == null) return sources;
        String vertex = sources.get(vertexKey), fragment = sources.get(fragmentKey);
        if (vertex == null || fragment == null) return sources;
        var code = MapPackFragmentMask.withoutComments(fragment);
        if (!ENCODED_CHUNK_FADE.matcher(code).find()) return sources;

        var vertexCode = MapPackFragmentMask.withoutComments(vertex);
        var fade = FIXED_FADE.matcher(vertexCode);
        var assignment = FADE_WRITE.matcher(vertexCode);
        if (!fade.find() || Float.parseFloat(fade.group(1)) != -1.0f || !assignment.find()
                || !"mc_chunkFade".equals(assignment.group(1).trim()) || assignment.find()) return sources;

        // The Iris non-chunk sentinel becomes a zero fade when Complementary encodes it in an
        // unsigned G-buffer channel. Earth supplies its own exploration fade after the pack pass.
        var changed = new HashMap<Object, String>(sources);
        changed.put(vertexKey, vertex.substring(0, fade.start(1)) + "1.0" + vertex.substring(fade.end(1)));
        return changed;
    }

    static Map<?, String> install(Map<?, String> sources, Object vertexKey, Object fragmentKey,
                                  String vertex, String fragment) {
        String code = MapPackFragmentMask.withoutComments(fragment);
        if (!code.contains("DoLighting(") || !code.contains("DoFoliageColorTweaks(")
                || !code.contains("iris_FragData0") || !code.contains("iris_FragData1") || !code.contains("iris_FragData2")
                || !code.contains("gbufferModelViewInverse") || LIVE_ONLY_INPUT.matcher(code).find()
                || code.contains("gl_FragDepth") || code.contains("iris_FragData3")) return sources;
        for (String declared : LIGHTING_INPUTS) if (!code.contains(declared + " = ")) return sources;
        String vertexCode = MapPackFragmentMask.withoutComments(vertex);
        if (!TERRAIN_POSITION.matcher(vertexCode).find()) return sources;
        if (CHUNK_FADE.matcher(code).find()) {
            fragment = fixedChunkFade(vertexCode, fragment);
            if (fragment == null) return sources;
        }
        var aliased = aliasInputs(vertex, fragment);
        if (aliased == null) return sources;
        String vertices = transformVertex(aliased.vertex());
        String fragments = transformFragment(aliased.fragment());
        if (vertices == null || fragments == null) return sources;
        var changed = new HashMap<Object, String>(sources);
        changed.put(vertexKey, vertices);
        changed.put(fragmentKey, fragments);
        MapPackTarget.materialProfile(MapPackMaterialTileShaders.fingerprint(vertices, fragments),
                vertexCode.contains("DoWave(") ? WAVING::contains : null);
        return changed;
    }

    private static String fixedChunkFade(String vertex, String fragment) {
        // Iris supplies a fixed fade for native non-chunk terrain. The fullscreen
        // triangle cannot interpolate it, so preserve that same value in replay.
        var constant = FIXED_FADE.matcher(vertex);
        var write = FADE_WRITE.matcher(vertex);
        var input = FADE_INPUT.matcher(fragment);
        if (!constant.find() || !write.find() || !"mc_chunkFade".equals(write.group(1).trim())
                || write.find() || !input.find()) return null;
        return input.replaceFirst("const float chunkFade = " + constant.group(1) + ";");
    }

    private record Stages(String vertex, String fragment) { }

    private static Stages aliasInputs(String vertex, String fragment) {
        for (String input : VARYINGS) {
            int space = input.indexOf(' ');
            String type = input.substring(0, space), name = input.substring(space + 1);
            String alias = "earthMaterialInput_" + name;
            Pattern declaration = Pattern.compile("(?m)^(\\h*(?:flat\\h+)?in\\h+)" + type + "\\h+" + name + "\\h*;");
            var match = declaration.matcher(fragment);
            if (!match.find() || !Pattern.compile("\\bout\\s+" + type + "\\s+" + name + "\\s*;")
                    .matcher(vertex).find()) return null;
            fragment = match.replaceFirst("$1" + type + " " + alias + ";\n" + input + " = " + alias + ";");
            vertex = vertex.replaceAll("\\b" + name + "\\b", alias);
        }
        return new Stages(vertex, fragment);
    }

    private static String transformVertex(String vertex) {
        var main = MapPackMaterialTileShaders.function(MapPackFragmentMask.withoutComments(vertex), "main");
        if (main == null) return null;
        String body = vertex.substring(main.open() + 1, main.close());
        body = JITTER.matcher(body).replaceAll("if (EarthMaterialTileMode != 1) $0");
        return MapPackMaterialTileShaders.withMode(vertex.substring(0, main.open() + 1)
                + MapPackMaterialTileShaders.SCREEN_TRIANGLE + body + vertex.substring(main.close()));
    }

    private static String transformFragment(String fragment) {
        String code = MapPackFragmentMask.withoutComments(fragment);
        var original = MapPackMaterialTileShaders.function(code, "earth_map_original_main");
        var main = MapPackMaterialTileShaders.function(code, "main");
        if (original == null || main == null) return null;
        var base = BASE_COLOR.matcher(code).region(original.open() + 1, original.close());
        if (!base.find()) return null;
        String lighting = fragment.substring(base.end(), original.close())
                .replaceAll("\\bgl_FragCoord\\s*\\.\\s*z\\b", "earthMaterialFragmentDepth");
        String dispatch = fragment.substring(main.open() + 1).replace("earth_map_original_main();",
                "if (EarthMaterialTileMode == 1) earthCaptureComplementary(); else earth_map_original_main();");
        int insertion = MapPackFragmentMask.directiveInsertion(fragment);
        if (insertion >= original.declaration()) return null;
        return fragment.substring(0, insertion) + MapPackMaterialTileShaders.GLSL_MODE
                + MapPackMaterialTileShaders.INPUTS + "\nvec4 earthMaterialAlbedo;\nfloat earthMaterialFragmentDepth;\n"
                + fragment.substring(insertion, base.start())
                + "vec4 color = EarthMaterialTileMode == 2 ? earthMaterialAlbedo : texture(tex, texCoord);"
                + lighting + fragment.substring(original.close(), main.declaration())
                + CAPTURE + REPLAY + fragment.substring(main.declaration(), main.open() + 1) + """

                gl_FragDepth = gl_FragCoord.z;
                earthMaterialFragmentDepth = gl_FragCoord.z;
                if (EarthMaterialTileMode == 2) {
                    earthReplayComplementary();
                    return;
                }
                """ + dispatch;
    }

    private MapPackComplementaryMaterials() { }
}
