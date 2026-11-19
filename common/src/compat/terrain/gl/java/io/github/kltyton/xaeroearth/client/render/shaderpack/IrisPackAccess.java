package io.github.kltyton.xaeroearth.client.render.shaderpack;

import com.mojang.logging.LogUtils;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.util.Optional;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import java.nio.IntBuffer;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;

/** Adapter for Iris/Oculus releases with the ShaderInstance IrisRenderingPipeline contract. */
final class IrisPackAccess {
    record Selection(Object pack, Object dimension) {}

    private final MethodHandle active, pack, dimension, programs, create, shaderMap, shader, begin, phase, currentPhase,
            shadow, hand, translucent, finish, destroy, currentView, currentProjection, setView, setProjection,
            getAlpha, setAlpha, convertView, convertProjection, blockIds, shadowDistance, shadowOrtho, shadowPerspective;
    private final Object state, solidPhase, cutoutPhase, translucentPhase, solidShader, cutoutShader, waterShader, shadowShader;
    private final MethodHandle bufferDirectives, bufferObjects;
    private static IrisPackAccess found;
    private static boolean linked;

    static IrisPackAccess get() {
        if (linked) return found;
        linked = true;
        for (String root : new String[]{"net.irisshaders.iris.", "net.coderbot.iris."}) {
            try { return found = new IrisPackAccess(root); }
            catch (ClassNotFoundException ignored) {
                // Releases before the package migration are tried through their original names.
            } catch (ReflectiveOperationException | LinkageError mismatch) {
                LogUtils.getLogger().debug("Iris implementation has no compatible map shader entry", mismatch);
            }
        }
        return null;
    }

    private IrisPackAccess(String root) throws ReflectiveOperationException {
        Class<?> iris = type(root + "Iris");
        boolean legacy = root.startsWith("net.coderbot");
        Class<?> pipeline = type(root + (legacy ? "pipeline.newshader.NewWorldRenderingPipeline"
                : "pipeline.IrisRenderingPipeline"));
        Class<?> api = type(root + "api.v0.IrisApi");
        Class<?> packType = type(root + "shaderpack.ShaderPack");
        Class<?> captured = type(root + "uniforms.CapturedRenderingState");
        var lookup = MethodHandles.publicLookup();
        if (legacy) {
            shadowDistance = null; shadowOrtho = null; shadowPerspective = null;
        } else {
            shadowDistance = lookup.unreflect(type(root + "compat.dh.DHCompat").getMethod("getRenderDistance"));
            var matrices = type(root + "shadows.ShadowMatrices");
            shadowOrtho = lookup.unreflect(matrices.getMethod("createOrthoMatrix", float.class, float.class, float.class));
            shadowPerspective = lookup.unreflect(matrices.getMethod("createPerspectiveMatrix", float.class));
        }
        active = lookup.unreflect(api.getMethod("isShaderPackInUse"))
                .bindTo(api.getMethod("getInstance").invoke(null));
        pack = lookup.unreflect(iris.getMethod("getCurrentPack"));
        var dim = iris.getMethod("getCurrentDimension");
        dimension = lookup.unreflect(dim);
        var programSet = packType.getMethod("getProgramSet", dim.getReturnType());
        programs = lookup.unreflect(programSet);
        // These optional dependencies have public buffer declarations on different owners.
        var packBuffers = optionalGetter(packType, "getBufferObjects");
        var directives = programSet.getReturnType().getMethod("getPackDirectives");
        var directiveBuffers = packBuffers == null ? optionalGetter(directives.getReturnType(), "getBufferObjects") : null;
        bufferDirectives = directiveBuffers == null ? null : lookup.unreflect(directives);
        bufferObjects = packBuffers != null ? lookup.unreflect(packBuffers)
                : directiveBuffers == null ? null : lookup.unreflect(directiveBuffers);
        create = lookup.unreflectConstructor(pipeline.getConstructor(programSet.getReturnType()));
        var shaderGetter = pipeline.getMethod("getShaderMap");
        shaderMap = lookup.unreflect(shaderGetter);
        Class<?> shaderKey = type(root + (legacy ? "pipeline.newshader.ShaderKey" : "pipeline.programs.ShaderKey"));
        shader = lookup.unreflect(shaderGetter.getReturnType().getMethod("getShader", shaderKey));
        solidShader = shaderKey.getField("TERRAIN_SOLID").get(null);
        cutoutShader = shaderKey.getField("TERRAIN_CUTOUT").get(null);
        waterShader = shaderKey.getField("TERRAIN_TRANSLUCENT").get(null);
        shadowShader = shaderKey.getField("SHADOW_TERRAIN_CUTOUT").get(null);
        Class<?> phaseType = type(root + "pipeline.WorldRenderingPhase");
        solidPhase = phaseType.getField("TERRAIN_SOLID").get(null);
        cutoutPhase = phaseType.getField("TERRAIN_CUTOUT").get(null);
        translucentPhase = phaseType.getField("TERRAIN_TRANSLUCENT").get(null);
        phase = lookup.unreflect(pipeline.getMethod("setPhase", phaseType));
        currentPhase = lookup.unreflect(pipeline.getMethod("getPhase"));
        begin = lookup.unreflect(pipeline.getMethod("beginLevelRendering"));
        shadow = lookup.unreflect(pipeline.getMethod("renderShadows", type(root + "mixin.LevelRendererAccessor"), Camera.class));
        hand = lookup.unreflect(pipeline.getMethod("beginHand"));
        translucent = lookup.unreflect(pipeline.getMethod("beginTranslucents"));
        finish = lookup.unreflect(pipeline.getMethod("finalizeLevelRendering"));
        destroy = lookup.unreflect(pipeline.getMethod("destroy"));
        state = captured.getField("INSTANCE").get(null);
        currentView = lookup.unreflect(captured.getMethod("getGbufferModelView"));
        currentProjection = lookup.unreflect(captured.getMethod("getGbufferProjection"));
        var view = matrixSetter(captured, "setGbufferModelView");
        var projection = matrixSetter(captured, "setGbufferProjection");
        setView = lookup.unreflect(view);
        setProjection = lookup.unreflect(projection);
        convertView = matrixConverter(view.getParameterTypes()[0], lookup);
        convertProjection = matrixConverter(projection.getParameterTypes()[0], lookup);
        getAlpha = lookup.unreflect(captured.getMethod("getCurrentAlphaTest"));
        setAlpha = lookup.unreflect(captured.getMethod("setCurrentAlphaTest", float.class));
        Class<?> settings = type(root + (legacy ? "block_rendering.BlockRenderingSettings"
                : "shaderpack.materialmap.WorldRenderingSettings"));
        blockIds = lookup.unreflect(settings.getMethod("getBlockStateIds"))
                .bindTo(settings.getField("INSTANCE").get(null));
    }

    private static Class<?> type(String type) throws ClassNotFoundException {
        return Class.forName(type, false, IrisPackAccess.class.getClassLoader());
    }

    private static java.lang.reflect.Method optionalGetter(Class<?> type, String name) {
        try { return type.getMethod(name); }
        catch (NoSuchMethodException absent) { return null; }
    }

    private static java.lang.reflect.Method matrixSetter(Class<?> type, String name) throws NoSuchMethodException {
        for (var method : type.getMethods())
            if (method.getName().equals(name) && method.getParameterCount() == 1) return method;
        throw new NoSuchMethodException(name);
    }

    private static MethodHandle matrixConverter(Class<?> type, MethodHandles.Lookup lookup) throws NoSuchMethodException, IllegalAccessException {
        if (type.isAssignableFrom(Matrix4f.class)) return null;
        return lookup.unreflectConstructor(type.getConstructor(float[].class));
    }

    Selection selection() throws Throwable {
        if (!(boolean) active.invoke()) return null;
        var selected = ((Optional<?>) pack.invoke()).orElse(null);
        return selected == null ? null : new Selection(selected, dimension.invoke());
    }

    Object create(Selection selection) throws Throwable { return create.invoke(programs.invoke(selection.pack(), selection.dimension())); }
    MapPackStorageBindings storage(Selection selected) throws Throwable {
        if (bufferObjects == null) return null; // Older Oculus versions have no storage-buffer declarations.
        Object owner = bufferDirectives == null ? selected.pack()
                : bufferDirectives.invoke(programs.invoke(selected.pack(), selected.dimension()));
        var declared = (Int2ObjectMap<?>) bufferObjects.invoke(owner);
        return declared.isEmpty() ? null : new MapPackStorageBindings(declared.keySet().toIntArray());
    }
    void destroy(Object pipeline) throws Throwable { destroy.invoke(pipeline); }
    void begin(Object pipeline) throws Throwable { begin.invoke(pipeline); }
    void prepare(Object pipeline, Camera camera) throws Throwable { shadow.invoke(pipeline, null, camera); }
    void translucent(Object pipeline) throws Throwable { hand.invoke(pipeline); translucent.invoke(pipeline); }
    void phase(Object pipeline, boolean transparent, boolean solid) throws Throwable {
        phase.invoke(pipeline, transparent ? translucentPhase : solid ? solidPhase : cutoutPhase);
    }
    Object currentPhase(Object pipeline) throws Throwable { return currentPhase.invoke(pipeline); }
    void restorePhase(Object pipeline, Object previous) throws Throwable { phase.invoke(pipeline, previous); }
    void finish(Object pipeline) throws Throwable { finish.invoke(pipeline); }
    int shadowDistance() throws Throwable { return shadowDistance == null ? 0 : (int) shadowDistance.invoke(); }
    Matrix4f shadowMatrix(float radius, float near, float far, Float fov) throws Throwable {
        return (Matrix4f) (fov == null ? shadowOrtho.invoke(radius, near, far) : shadowPerspective.invoke(fov.floatValue()));
    }

    @SuppressWarnings("unchecked")
    void materialIds(IntBuffer destination) throws Throwable {
        var ids = (Object2IntMap<BlockState>) blockIds.invoke();
        for (var state : Block.BLOCK_STATE_REGISTRY) {
            int offset = Block.getId(state) * 2;
            destination.put(offset, ids == null ? -1 : ids.getOrDefault(state, -1));
            destination.put(offset + 1, state.getLightEmission());
        }
    }

    ShaderInstance shader(Object pipeline, boolean transparent, boolean shadowPass, boolean solid) throws Throwable {
        return (ShaderInstance) shader.invoke(shaderMap.invoke(pipeline), shadowPass ? shadowShader
                : transparent ? waterShader : solid ? solidShader : cutoutShader);
    }

    CameraState cameraState() throws Throwable {
        return new CameraState(currentView.invoke(state), currentProjection.invoke(state),
                (float) getAlpha.invoke(state));
    }

    void setCamera(Matrix4f view, Matrix4f projection) throws Throwable {
        setView.invoke(state, convertView == null ? view : convertView.invoke(view.get(new float[16])));
        setProjection.invoke(state, convertProjection == null ? projection : convertProjection.invoke(projection.get(new float[16])));
    }

    Object historyMatrix(Matrix4f matrix) {
        if (convertView == null) return matrix;
        try { return convertView.invoke(matrix.get(new float[16])); }
        catch (RuntimeException | Error failure) { throw failure; }
        catch (Throwable failure) { throw new IllegalStateException("Cannot supply map temporal matrices to Oculus", failure); }
    }

    void restore(CameraState old) throws Throwable {
        if (old.view() != null && old.projection() != null) {
            setView.invoke(state, old.view());
            setProjection.invoke(state, convertProjection == null && old.projection() instanceof Matrix4fc matrix
                    ? new Matrix4f(matrix) : old.projection());
        }
        setAlpha.invoke(state, old.alphaTest());
    }

    record CameraState(Object view, Object projection, float alphaTest) {}
}
