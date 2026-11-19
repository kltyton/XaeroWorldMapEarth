package io.github.kltyton.xaeroearth.client.render.shaderpack;

import com.mojang.blaze3d.opengl.GlProgram;
import com.mojang.blaze3d.opengl.Uniform;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.logging.LogUtils;
import io.github.kltyton.xaeroearth.client.mixin.shaderpack.NativePackTerrainFormat;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.nio.IntBuffer;
import java.util.BitSet;
import java.util.Optional;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL31;

/** Links Iris only when its optional native-pipeline API and offscreen lifecycle are present. */
final class NativeIrisAccess {
    record Selection(Object pack, Object dimension) {}
    record CameraState(Matrix4fc view, Matrix4fc projection, float alpha) {}

    private static final String ROOT = "net.irisshaders.iris.";
    static final String MASK_UNIFORM = "EarthMaskParams";
    private static final class Link { static final NativeIrisAccess ACCESS = link(); }
    private final MethodHandle active, pack, dimension, programs, create, begin, prepare, mainBound, hand,
            translucent, finish, destroy, phase, currentPhase, view, projection, setView, setProjection, alpha, setAlpha,
            shadowOrtho, shadowPerspective, shadowDistance, shaderMap, shader, blockIds;
    private final Object captured, solid, cutout, water;
    private final Object solidShader, cutoutShader, waterShader, shadowShader, shadowWaterShader;
    private final MethodHandle bufferObjects;

    static NativeIrisAccess get() { return Link.ACCESS; }

    private static NativeIrisAccess link() {
        try { return new NativeIrisAccess(); }
        catch (ClassNotFoundException absent) { return null; }
        catch (ReflectiveOperationException | LinkageError failure) {
            LogUtils.getLogger().warn("Earth cannot link to this Iris native pipeline", failure);
            return null;
        }
    }

    private NativeIrisAccess() throws ReflectiveOperationException {
        var lookup = MethodHandles.publicLookup();
        Class<?> api = type("api.v0.IrisApi");
        Class<?> iris = type("Iris");
        Class<?> packType = type("shaderpack.ShaderPack");
        Class<?> pipeline = type("pipeline.IrisRenderingPipeline");
        Class<?> phaseType = type("pipeline.WorldRenderingPhase");
        Class<?> state = type("uniforms.CapturedRenderingState");
        Class<?> keys = type("api.v0.IrisProgram");
        Class<?> shadowKeys = type("api.v0.IrisShadowProgram");
        Class<?> shadowMatrices = type("shadows.ShadowMatrices");
        Class<?> shaderKey = type("pipeline.programs.ShaderKey");
        var shaderMapGetter = pipeline.getMethod("getShaderMap");
        shaderMap = lookup.unreflect(shaderMapGetter);
        shader = lookup.unreflect(shaderMapGetter.getReturnType().getMethod("getShader", shaderKey));
        shadowDistance = lookup.unreflect(type("compat.dh.DHCompat").getMethod("getRenderDistance"));
        shadowOrtho = lookup.unreflect(shadowMatrices.getMethod("createOrthoMatrix", float.class, float.class, float.class));
        shadowPerspective = lookup.unreflect(shadowMatrices.getMethod("createPerspectiveMatrix", float.class));
        Object instance = api.getMethod("getInstance").invoke(null);
        active = lookup.unreflect(api.getMethod("isShaderPackInUse")).bindTo(instance);
        pack = lookup.unreflect(iris.getMethod("getCurrentPack"));
        bufferObjects = lookup.unreflect(packType.getMethod("getBufferObjects"));
        var dimensionGetter = iris.getMethod("getCurrentDimension");
        dimension = lookup.unreflect(dimensionGetter);
        var programGetter = packType.getMethod("getProgramSet", dimensionGetter.getReturnType());
        programs = lookup.unreflect(programGetter);
        create = lookup.unreflectConstructor(pipeline.getConstructor(programGetter.getReturnType()));
        begin = lookup.unreflect(pipeline.getMethod("beginLevelRendering"));
        prepare = lookup.unreflect(pipeline.getMethod("renderShadows", type("mixin.LevelRendererAccessor"),
                Camera.class, CameraRenderState.class));
        mainBound = lookup.unreflect(pipeline.getMethod("setIsMainBound", boolean.class));
        hand = lookup.unreflect(pipeline.getMethod("beginHand"));
        translucent = lookup.unreflect(pipeline.getMethod("beginTranslucents"));
        finish = lookup.unreflect(pipeline.getMethod("finalizeLevelRendering"));
        destroy = lookup.unreflect(pipeline.getMethod("destroy"));
        phase = lookup.unreflect(pipeline.getMethod("setPhase", phaseType));
        currentPhase = lookup.unreflect(pipeline.getMethod("getPhase"));
        solid = phaseType.getField("TERRAIN_SOLID").get(null);
        cutout = phaseType.getField("TERRAIN_CUTOUT").get(null);
        water = phaseType.getField("TERRAIN_TRANSLUCENT").get(null);
        captured = state.getField("INSTANCE").get(null);
        view = lookup.unreflect(state.getMethod("getGbufferModelView")).bindTo(captured);
        projection = lookup.unreflect(state.getMethod("getGbufferProjection")).bindTo(captured);
        setView = lookup.unreflect(state.getMethod("setGbufferModelView", Matrix4fc.class)).bindTo(captured);
        setProjection = lookup.unreflect(state.getMethod("setGbufferProjection", Matrix4f.class)).bindTo(captured);
        alpha = lookup.unreflect(state.getMethod("getCurrentAlphaTest")).bindTo(captured);
        setAlpha = lookup.unreflect(state.getMethod("setCurrentAlphaTest", float.class)).bindTo(captured);
        Class<?> formats = type("vertices.IrisVertexFormats");
        var terrainFormat = (VertexFormat) formats.getField("TERRAIN").get(null);
        int kuiStride = NativePackTerrainFormat.earth$format().getVertexSize();
        if (kuiStride != 32 || !NativeMapPackDevice.compatibleTerrain(terrainFormat))
            throw new NoSuchFieldException("Unsupported map terrain layouts: KUI stride " + kuiStride
                    + ", Iris " + terrainFormat);
        Class<?> settings = type("shaderpack.materialmap.WorldRenderingSettings");
        blockIds = lookup.unreflect(settings.getMethod("getBlockStateIds"))
                .bindTo(settings.getField("INSTANCE").get(null));
        NativeMapPackDevice.initializeTerrain(terrainFormat);
        var assign = api.getMethod("assignPipeline", RenderPipeline.class, keys);
        Object terrainProgram = keys.getField("TERRAIN_CUTOUT").get(null);
        Object solidProgram = keys.getField("TERRAIN_SOLID").get(null);
        Object waterProgram = keys.getField("TRANSLUCENT").get(null);
        assign.invoke(instance, NativeMapPackDevice.OPAQUE, terrainProgram);
        assign.invoke(instance, NativeMapPackDevice.SOLID, solidProgram);
        assign.invoke(instance, NativeMapPackDevice.TRANSLUCENT, waterProgram);
        var assignShadow = api.getMethod("assignPipelineShadow", RenderPipeline.class, shadowKeys);
        Object shadow = shadowKeys.getField("SHADOW_TERRAIN_CUTOUT").get(null);
        assignShadow.invoke(instance, NativeMapPackDevice.OPAQUE, shadow);
        assignShadow.invoke(instance, NativeMapPackDevice.TRANSLUCENT, shadow);
        assignShadow.invoke(instance, NativeMapPackDevice.SHADOW_OPAQUE, shadow);
        assignShadow.invoke(instance, NativeMapPackDevice.SHADOW_TRANSLUCENT, shadow);
        Class<?> programId = type("shaderpack.loading.ProgramId");
        var match = shaderKey.getMethod("findBestMatch", RenderPipeline.class, programId);
        var fromApi = programId.getMethod("fromAPI", keys);
        var shadowFromApi = programId.getMethod("fromAPI", shadowKeys);
        solidShader = match.invoke(null, NativeMapPackDevice.SOLID, fromApi.invoke(null, solidProgram));
        cutoutShader = match.invoke(null, NativeMapPackDevice.OPAQUE, fromApi.invoke(null, terrainProgram));
        waterShader = match.invoke(null, NativeMapPackDevice.TRANSLUCENT, fromApi.invoke(null, waterProgram));
        shadowShader = match.invoke(null, NativeMapPackDevice.SHADOW_OPAQUE, shadowFromApi.invoke(null, shadow));
        shadowWaterShader = match.invoke(null, NativeMapPackDevice.SHADOW_TRANSLUCENT, shadowFromApi.invoke(null, shadow));
    }

    private static Class<?> type(String type) throws ClassNotFoundException {
        return Class.forName(ROOT + type, false, NativeIrisAccess.class.getClassLoader());
    }

    Selection selection() throws Throwable {
        if (!(boolean) active.invoke()) return null;
        var current = ((Optional<?>) pack.invoke()).orElse(null);
        return current == null ? null : new Selection(current, dimension.invoke());
    }

    CameraState camera() throws Throwable {
        return new CameraState((Matrix4fc) view.invoke(), (Matrix4fc) projection.invoke(), (float) alpha.invoke());
    }

    void camera(Matrix4fc orientation, Matrix4f perspective) throws Throwable {
        setView.invoke(orientation);
        setProjection.invoke(perspective);
    }

    void restore(CameraState previous) throws Throwable {
        if (previous.view != null && previous.projection != null) camera(previous.view, new Matrix4f(previous.projection));
        setAlpha.invoke(previous.alpha);
    }

    Object create(Selection selected) throws Throwable { return create.invoke(programs.invoke(selected.pack, selected.dimension)); }
    MapPackStorageBindings storage(Selection selected) throws Throwable {
        var declared = (Int2ObjectMap<?>) bufferObjects.invoke(selected.pack());
        return declared.isEmpty() ? null : new MapPackStorageBindings(declared.keySet().toIntArray());
    }
    void start(Object pipeline) throws Throwable { begin.invoke(pipeline); mainBound.invoke(pipeline, true); }
    void prepare(Object pipeline, Camera camera) throws Throwable { prepare.invoke(pipeline, null, camera, null); }
    void phase(Object pipeline, boolean transparent, boolean solidTerrain) throws Throwable {
        phase.invoke(pipeline, transparent ? water : solidTerrain ? solid : cutout);
    }
    Object currentPhase(Object pipeline) throws Throwable { return currentPhase.invoke(pipeline); }
    void restorePhase(Object pipeline, Object previous) throws Throwable { phase.invoke(pipeline, previous); }
    void water(Object pipeline) throws Throwable { hand.invoke(pipeline); translucent.invoke(pipeline); }
    void finish(Object pipeline) throws Throwable { finish.invoke(pipeline); }
    int shadowDistance() throws Throwable { return (int) shadowDistance.invoke(); }
    void destroy(Object pipeline) throws Throwable { destroy.invoke(pipeline); }

    @SuppressWarnings("unchecked")
    void materialIds(IntBuffer destination) throws Throwable {
        var ids = (Object2IntMap<BlockState>) blockIds.invoke();
        for (var block : Block.BLOCK_STATE_REGISTRY) {
            int material = ids == null ? -1 : ids.getOrDefault(block, -1);
            destination.put(Block.getId(block), (material & 0xffff) | ((block.getLightEmission() & 0xff) << 16));
        }
    }

    boolean registerMasks(Object pipeline) throws Throwable {
        Object map = shaderMap.invoke(pipeline);
        boolean complete = true;
        for (Object key : new Object[]{solidShader, cutoutShader, waterShader, shadowShader, shadowWaterShader}) {
            var program = (GlProgram) shader.invoke(map, key);
            complete &= program != null && registerMask(program);
        }
        return complete;
    }

    private static boolean registerMask(GlProgram program) {
        int id = program.getProgramId();
        int block = GL31.glGetUniformBlockIndex(id, MASK_UNIFORM);
        if (block == GL31.GL_INVALID_INDEX) return false;
        var uniforms = program.getUniforms();
        if (uniforms.get(MASK_UNIFORM) instanceof Uniform.Ubo) return true;

        // Iris declares the stock uniform layouts before Earth can supply its map-only block.
        // Register the linked block with Blaze3D so RenderPass.setUniform actually binds its slice.
        var used = new BitSet();
        int blocks = GL20.glGetProgrami(id, GL31.GL_ACTIVE_UNIFORM_BLOCKS);
        for (int index = 0; index < blocks; index++) {
            if (index != block) used.set(GL31.glGetActiveUniformBlocki(id, index, GL31.GL_UNIFORM_BLOCK_BINDING));
        }
        int binding = used.nextClearBit(0);
        if (binding >= GL11.glGetInteger(GL31.GL_MAX_UNIFORM_BUFFER_BINDINGS)) {
            throw new IllegalStateException("Iris map program has no free uniform buffer binding for " + MASK_UNIFORM);
        }
        GL31.glUniformBlockBinding(id, block, binding);
        uniforms.put(MASK_UNIFORM, new Uniform.Ubo(binding));
        return true;
    }

    Matrix4f shadowMatrix(float radius, float near, float far, Float fov) {
        try {
            return fov == null ? (Matrix4f) shadowOrtho.invoke(radius, near, far)
                    : (Matrix4f) shadowPerspective.invoke(fov.floatValue());
        } catch (RuntimeException | Error failure) {
            throw failure;
        } catch (Throwable failure) {
            throw new IllegalStateException("Iris shadow projection is unavailable", failure);
        }
    }
}
