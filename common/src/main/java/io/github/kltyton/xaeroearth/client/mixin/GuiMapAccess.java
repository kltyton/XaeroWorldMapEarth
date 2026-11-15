package io.github.kltyton.xaeroearth.client.mixin;

import java.util.ArrayList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;
import net.minecraft.client.gui.components.Button;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import xaero.map.gui.GuiMap;
import xaero.map.gui.dropdown.rightclick.GuiRightClickMenu;
import xaero.map.animation.SlowingAnimation;
import xaero.map.region.MapRegion;
import xaero.map.region.BranchLeveledRegion;

/** Reads the native viewport and visible cache selection without replacing GuiMap. */
@Mixin(value = GuiMap.class, remap = false)
public interface GuiMapAccess {
    @Accessor("cameraX") double earth$cameraX();
    @Accessor("cameraZ") double earth$cameraZ();
    @Accessor("cameraX") void earth$cameraX(double value);
    @Accessor("cameraZ") void earth$cameraZ(double value);
    @Accessor("shouldResetCameraPos") void earth$resetCameraPosition(boolean value);
    @Accessor("cameraDestination") void earth$cameraDestination(int[] value);
    @Accessor("cameraDestinationAnimX") void earth$cameraAnimationX(SlowingAnimation value);
    @Accessor("cameraDestinationAnimZ") void earth$cameraAnimationZ(SlowingAnimation value);
    @Accessor("cameraDestinationAnimX") SlowingAnimation earth$cameraAnimationX();
    @Accessor("cameraDestinationAnimZ") SlowingAnimation earth$cameraAnimationZ();
    @Accessor("attachedCamera") static boolean earth$attachedCamera() { throw new AssertionError(); }
    @Accessor("attachedCamera") static void earth$attachedCamera(boolean value) { throw new AssertionError(); }
    @Invoker("onAttachedCameraButton") void earth$toggleAttachedCamera(Button button);
    @Accessor("rightClickMenu") GuiRightClickMenu earth$rightClickMenu();
    @Accessor("rightClickX") int earth$rightClickX();
    @Accessor("rightClickY") int earth$rightClickY();
    @Accessor("rightClickZ") int earth$rightClickZ();
    @Accessor("rightClickDim") ResourceKey<Level> earth$rightClickDimension();
    @Accessor("caveModeOptions") xaero.map.gui.GuiCaveModeOptions earth$caveModeOptions();
    @Accessor("scale") double earth$scale();
    @Accessor("regionBuffer") ArrayList<MapRegion> earth$regions();
    @Accessor("branchRegionBuffer") ArrayList<BranchLeveledRegion> earth$branches();
    @Accessor("mouseBlockPosX") void earth$mouseX(int value);
    @Accessor("mouseBlockPosY") void earth$mouseY(int value);
    @Accessor("mouseBlockPosZ") void earth$mouseZ(int value);
}
