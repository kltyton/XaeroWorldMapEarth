package io.github.kltyton.xaeroearth.client.render.shaderpack;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Inserts material capture and replay only in terrain programs with a known material contract. */
final class MapPackMaterialTileShaders {
    private static final String MODE = "EarthMaterialTileMode";
    static final String GLSL_MODE = "\nuniform int " + MODE + ";\n";
    private static final Pattern ALBEDO = Pattern.compile(
            "\\bvec3\\s+albedo\\s*=\\s*albedoTex\\s*\\.\\s*rgb\\s*\\*\\s*v_color\\s*;");
    private static final Pattern ORTHOGRAPHIC_POSITION = Pattern.compile(
            "\\bgl_Position\\s*=\\s*[^;{}]*\\bgbufferModelView\\b[^;{}]*\\bworldPos\\b[^;{}]*;");
    private static final Pattern RESOLUTION_DISCARD = Pattern.compile(
            "\\bif\\s*\\(\\s*FsrDiscardFS\\s*\\(\\s*gl_FragCoord\\s*\\.\\s*xy\\s*\\)\\s*\\)\\s*discard\\s*;");
    private static final Pattern WAVING_POSITION = Pattern.compile("\\bWavingPlants\\s*\\(");
    private static final Pattern RESIZE_POSITION = Pattern.compile("\\bRedoProject\\s*\\(");
    private static final Pattern ADDITIONAL_OUTPUT = Pattern.compile(
            "\\blayout\\s*\\(\\s*location\\s*=\\s*[456]\\s*\\)\\s*out\\b");

    static final String INPUTS = """

            uniform sampler2DArray EarthMaterialPlanes;
            uniform sampler2D EarthMaterialDepth;
            uniform vec4 EarthMaterialTileRect;
            uniform vec4 EarthMaterialViewport;
            uniform vec4 EarthMaterialAdjust;
            uniform mat4 EarthMaterialUnproject;

            layout(location = 4) out vec4 earthTileTangent;
            layout(location = 5) out vec4 earthTileBitangent;
            layout(location = 6) out vec4 earthTileNormal;
            """;
    private static final String CAPTURE = """

                framebuffer_albedo = vec4(albedo, v_emissiveness);
                framebuffer_gsoildData = specularTex;
                framebuffer_gsoildNormal = vec4(normalTex, v_materialIDs);
                earthTileTangent = vec4(v_tbn[0], v_blockLight.x);
                earthTileBitangent = vec4(v_tbn[1], v_blockLight.y);
                earthTileNormal = vec4(v_tbn[2], 1.0);
            }
            """;
    static final String REPLAY_ADDRESS = """
                vec2 pixel = (gl_FragCoord.xy - EarthMaterialAdjust.xy) / EarthMaterialViewport.zw;
                vec2 tilePixel = pixel - EarthMaterialTileRect.xy;
                // Replay and live fill use the same scissor. The gutter absorbs rounding at its edge.
                ivec2 sampleAt = clamp(ivec2(floor(tilePixel)) + ivec2(1),
                        ivec2(0), ivec2(EarthMaterialTileRect.zw) + ivec2(1));
            """;
    static final String REPLAY_DEPTH = """
                float earthTerrainDepth = texelFetch(EarthMaterialDepth, sampleAt, 0).r + EarthMaterialAdjust.z;
                // Move from the capture texel center to the fragment along the same world-space face.
                // An orthographic ray has a constant clip-to-world basis across the tile.
                vec2 sampleCenter = EarthMaterialTileRect.xy + vec2(sampleAt) - vec2(0.5);
                vec2 clipShift = (pixel - sampleCenter) / EarthMaterialViewport.xy * 2.0;
                vec3 lateral = EarthMaterialUnproject[0].xyz * clipShift.x +
                        EarthMaterialUnproject[1].xyz * clipShift.y;
                vec3 alongRay = EarthMaterialUnproject[2].xyz;
                float normalAlongRay = dot(earthWorldNormal, alongRay);
                if (abs(normalAlongRay) > 0.125 * length(alongRay))
                    earthTerrainDepth -= dot(earthWorldNormal, lateral) / (normalAlongRay * EarthMaterialAdjust.w);
                if (earthTerrainDepth <= 0.0 || earthTerrainDepth >= 1.0) discard;
                gl_FragDepth = earthTerrainDepth;
                vec4 position = EarthMaterialUnproject * vec4(pixel / EarthMaterialViewport.xy * 2.0 - 1.0,
                        earthTerrainDepth * EarthMaterialAdjust.w + (1.0 - EarthMaterialAdjust.w), 1.0);
            """;
    private static final String REPLAY = """

            void earthReplayTerrainMaterial() {
            """ + REPLAY_ADDRESS + """
                vec4 derivedNormal = texelFetch(EarthMaterialPlanes, ivec3(sampleAt, 5), 0);
                if (derivedNormal.a == 0.0) discard;
                vec4 rawAlbedo = texelFetch(EarthMaterialPlanes, ivec3(sampleAt, 0), 0);
                vec3 albedo = rawAlbedo.rgb;
                float v_emissiveness = rawAlbedo.a;
                vec4 specularTex = texelFetch(EarthMaterialPlanes, ivec3(sampleAt, 1), 0);
                vec4 rawNormal = texelFetch(EarthMaterialPlanes, ivec3(sampleAt, 2), 0);
                vec3 normalTex = rawNormal.rgb;
                float v_materialIDs = rawNormal.a;
                vec4 tangent = texelFetch(EarthMaterialPlanes, ivec3(sampleAt, 3), 0);
                vec4 bitangent = texelFetch(EarthMaterialPlanes, ivec3(sampleAt, 4), 0);
                mat3 v_tbn = mat3(tangent.xyz, bitangent.xyz, derivedNormal.xyz);
                vec2 v_blockLight = vec2(tangent.a, bitangent.a);
                const float parallaxShadow = 1.0;
                vec3 earthWorldNormal = derivedNormal.xyz;
            """ + REPLAY_DEPTH + """
                vec3 v_worldPos = position.xyz / position.w;
            """;
    static final String SCREEN_TRIANGLE = """

                if (EarthMaterialTileMode == 2) {
                    vec2 point = vec2(float((gl_VertexID << 1) & 2), float(gl_VertexID & 2));
                    gl_Position = vec4(point * 2.0 - 1.0, 0.0, 1.0);
                    return;
                }
                """;

    static Map<?, String> install(Map<?, String> sources) {
        if (sources == null || MapPackTarget.current() == null) return sources;
        Object vertexKey = key(sources, "VERTEX"), fragmentKey = key(sources, "FRAGMENT"), geometryKey = key(sources, "GEOMETRY");
        if (vertexKey == null || fragmentKey == null) return sources;
        String vertex = sources.get(vertexKey), fragment = sources.get(fragmentKey);
        if (vertex == null || fragment == null) return sources;
        String geometry = geometryKey == null ? null : sources.get(geometryKey);
        if (geometry == null) return MapPackComplementaryMaterials.install(sources, vertexKey, fragmentKey, vertex, fragment);
        String fragments = transformFragment(fragment);
        String vertices = transformVertex(vertex);
        String geometries = transformGeometry(geometry);
        if (fragments == null || vertices == null || geometries == null) return sources;
        var changed = new HashMap<Object, String>(sources);
        changed.put(vertexKey, vertices);
        changed.put(fragmentKey, fragments);
        changed.put(geometryKey, geometries);
        MapPackTarget.materialProfile(fingerprint(vertices, geometries, fragments), WAVING_POSITION.matcher(vertex).find()
                ? material -> material == 4 || material == 7040 || Math.abs(material - 7020) <= 10 : null);
        return changed;
    }

    private static String transformFragment(String fragment) {
        String code = MapPackFragmentMask.withoutComments(fragment);
        if (!code.contains("framebuffer_gsoildData") || !code.contains("framebuffer_gsoildNormal")
                || !code.contains("earth_map_original_main") || !code.contains("v_blockLight")
                || code.contains("gl_FragDepth") || code.contains("parallaxDist")
                || code.contains("ParallaxOcclusionMapping")
                || code.contains("framebuffer_gwater") || ADDITIONAL_OUTPUT.matcher(code).find()) return null;
        var original = function(code, "earth_map_original_main");
        var entry = function(code, "main");
        if (original == null || entry == null) return null;
        var albedo = ALBEDO.matcher(code).region(original.open() + 1, original.close());
        if (!albedo.find()) return null;
        String sampling = fragment.substring(original.open() + 1, albedo.end());
        String lighting = fragment.substring(albedo.end(), original.close()).replaceAll(
                "\\bgl_FragCoord\\s*\\.\\s*z\\b", "earthTerrainDepth");
        // The pack's render rectangle describes the live viewport. Tile rasters have their own clip.
        sampling = RESOLUTION_DISCARD.matcher(sampling).replaceAll("");
        String helper = "\nvoid earthCaptureTerrainMaterial() {" + sampling + CAPTURE + REPLAY + lighting + "\n}\n";
        String replacement = helper + fragment.substring(entry.declaration(), entry.open() + 1) + """

                gl_FragDepth = gl_FragCoord.z;
                if (EarthMaterialTileMode == 2) {
                    earthReplayTerrainMaterial();
                    return;
                }
                """ + fragment.substring(entry.open() + 1);
        replacement = replacement.replace("earth_map_original_main();",
                "if (EarthMaterialTileMode == 1) earthCaptureTerrainMaterial(); else earth_map_original_main();");
        int insertion = MapPackFragmentMask.directiveInsertion(fragment);
        if (insertion >= entry.declaration()) return null;
        return fragment.substring(0, insertion) + GLSL_MODE + INPUTS + fragment.substring(insertion, entry.declaration())
                + replacement;
    }

    private static String transformVertex(String vertex) {
        String code = MapPackFragmentMask.withoutComments(vertex);
        if (!code.contains("g_materialIDs") || !code.contains("g_texCoord")) return null;
        var entry = function(code, "main");
        if (entry == null) return null;
        String original = code.substring(entry.open() + 1, entry.close());
        if (RESIZE_POSITION.matcher(original).find()) return null;
        if (WAVING_POSITION.matcher(original).find()
                && (!code.contains("7040.0") || !code.contains("7020.0") || !code.contains("10.5"))) return null;
        var position = ORTHOGRAPHIC_POSITION.matcher(code).region(entry.open() + 1, entry.close());
        if (!position.find()) return null;
        String changed = vertex.substring(0, entry.open() + 1) + SCREEN_TRIANGLE
                + vertex.substring(entry.open() + 1, position.end())
                + "\nvec4 earthTilePosition = gl_Position;\n" + vertex.substring(position.end(), entry.close())
                + "\nif (EarthMaterialTileMode == 1) gl_Position = earthTilePosition;\n" + vertex.substring(entry.close());
        return withMode(changed);
    }

    private static String transformGeometry(String geometry) {
        String code = MapPackFragmentMask.withoutComments(geometry);
        if (!code.contains("g_materialIDs") || !code.contains("EmitVertex")) return null;
        var entry = function(code, "main");
        if (entry == null) return null;
        String changed = geometry.substring(0, entry.open() + 1) + """

                if (EarthMaterialTileMode == 2) {
                    for (int vertex = 0; vertex < 3; vertex++) {
                        gl_Position = gl_in[vertex].gl_Position;
                        EmitVertex();
                    }
                    EndPrimitive();
                    return;
                }
                """ + geometry.substring(entry.open() + 1);
        return withMode(changed);
    }

    static String withMode(String source) {
        int insertion = MapPackFragmentMask.directiveInsertion(source);
        return source.substring(0, insertion) + GLSL_MODE + source.substring(insertion);
    }

    record Function(int declaration, int open, int close) { }

    static Function function(String source, String name) {
        var start = Pattern.compile("\\bvoid\\s+" + name + "\\s*\\(\\s*(?:void\\s*)?\\)\\s*\\{").matcher(source);
        if (!start.find()) return null;
        int open = start.end() - 1;
        int braces = 1;
        for (int end = open + 1; end < source.length(); end++) {
            char ch = source.charAt(end);
            if (ch == '{') braces++;
            else if (ch == '}' && --braces == 0) return new Function(start.start(), open, end);
        }
        return null;
    }

    static Object key(Map<?, String> sources, String name) {
        for (Object key : sources.keySet()) if (key instanceof Enum<?> stage && stage.name().equals(name)) return key;
        return null;
    }

    static String fingerprint(String... sources) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            for (String source : sources) {
                digest.update(source.getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("Shader pack material cache requires SHA-256", failure);
        }
    }

    private MapPackMaterialTileShaders() { }
}
