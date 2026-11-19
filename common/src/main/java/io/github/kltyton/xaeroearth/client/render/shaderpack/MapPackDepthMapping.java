package io.github.kltyton.xaeroearth.client.render.shaderpack;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;
import org.lwjgl.opengl.GL20;
import org.lwjgl.system.MemoryStack;

/** Maps final output pixels back to the active rectangle of the pack's geometry depth texture. */
public record MapPackDepthMapping(float xScale, float yScale, float xOffset, float yOffset) {
    public static final MapPackDepthMapping FULL_SIZE = new MapPackDepthMapping(1, 1, 0, 0);
    private static final String MARKER = "EarthPackedDepthOrigin";
    private static final String SCALED_JITTER = "EarthPackedDepthScaledJitter";
    private static final String FULL_JITTER = "EarthPackedDepthFullJitter";
    private static final Pattern SCALE = Pattern.compile(
            "\\bposition\\.xy\\s*=\\s*position\\.xy\\s*\\*\\s*fsrRenderScale\\s*\\+\\s*fsrRenderScale\\s*-\\s*1\\.0\\s*;");
    private static final Pattern JITTER = Pattern.compile(
            "\\bposition\\.xy\\s*\\+=\\s*jitter\\s*\\*\\s*fsrRenderScale\\s*;");
    private static final Pattern FULL_JITTER_ASSIGNMENT = Pattern.compile(
            "\\bgl_Position\\s*\\.\\s*xy\\s*=\\s*taaJitter\\s*\\*\\s*gl_Position\\s*\\.\\s*w\\s*\\+\\s*gl_Position\\s*\\.\\s*xy\\s*;");

    /** Marks the verified iterationRP viewport transform without changing its default coordinates. */
    public static Map<?, String> install(Map<?, String> sources) {
        if (MapPackTarget.current() == null || sources == null) return sources;
        for (var entry : sources.entrySet()) {
            if (!(entry.getKey() instanceof Enum<?> stage) || !stage.name().equals("VERTEX")) continue;
            String source = entry.getValue();
            if (source == null || !source.contains("taaJitter")) return sources;
            String code = MapPackFragmentMask.withoutComments(source);
            var scale = SCALE.matcher(code);
            if (scale.find()) {
                var jitter = JITTER.matcher(code);
                if (jitter.find() && jitter.start() > scale.end()) {
                    int end = jitter.end() - 1;
                    source = source.substring(0, end) + " + " + SCALED_JITTER + source.substring(end);
                }
                int end = scale.end() - 1;
                source = source.substring(0, end) + " + " + MARKER + source.substring(end);
            }
            var full = FULL_JITTER_ASSIGNMENT.matcher(MapPackFragmentMask.withoutComments(source));
            if (full.find()) {
                int end = full.end() - 1;
                source = source.substring(0, end) + " + " + FULL_JITTER + " * gl_Position.w" + source.substring(end);
            }
            if (source.equals(entry.getValue())) return sources;
            int insertion = MapPackFragmentMask.directiveInsertion(source);
            var changed = new HashMap<Object, String>(sources);
            changed.put(entry.getKey(), source.substring(0, insertion) + "\nuniform vec2 " + MARKER
                    + ";\nuniform vec2 " + SCALED_JITTER + ";\nuniform vec2 " + FULL_JITTER + ";\n"
                    + source.substring(insertion));
            return changed;
        }
        return sources;
    }

    public static final class Reader {
        private record Locations(int scale, int jitter, boolean scaledJitter) { }
        private final Map<Integer, Locations> programs = new HashMap<>();

        private static Locations locations(int program) {
            boolean supported = GL20.glGetUniformLocation(program, MARKER) >= 0;
            boolean jitter = supported || GL20.glGetUniformLocation(program, FULL_JITTER) >= 0;
            return new Locations(supported ? GL20.glGetUniformLocation(program, "fsrRenderScale") : -1,
                    jitter ? GL20.glGetUniformLocation(program, "taaJitter") : -1,
                    supported && GL20.glGetUniformLocation(program, SCALED_JITTER) >= 0);
        }

        MapPackDepthMapping read(int program, int width, int height) {
            if (program == 0) return FULL_SIZE;
            var locations = programs.computeIfAbsent(program, Reader::locations);
            if (locations.scale() < 0 && locations.jitter() < 0) return FULL_SIZE;
            try (var stack = MemoryStack.stackPush()) {
                var vec = stack.callocFloat(2);
                float x = 1, y = 1;
                if (locations.scale() >= 0) {
                    GL20.glGetUniformfv(program, locations.scale(), vec);
                    x = vec.get(0); y = vec.get(1);
                    if (!(x > 0 && y > 0) || !Float.isFinite(x) || !Float.isFinite(y)) return FULL_SIZE;
                }
                float jitterX = 0, jitterY = 0;
                if (locations.jitter() >= 0) {
                    GL20.glGetUniformfv(program, locations.jitter(), vec);
                    jitterX = vec.get(0) * width * 0.5f * (locations.scaledJitter() ? x : 1);
                    jitterY = vec.get(1) * height * 0.5f * (locations.scaledJitter() ? y : 1);
                }
                return new MapPackDepthMapping(x, y, jitterX, jitterY);
            }
        }

        public void clear() { programs.clear(); }
    }
}
