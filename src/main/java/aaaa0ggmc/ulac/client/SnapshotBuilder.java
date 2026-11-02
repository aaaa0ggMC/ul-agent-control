package aaaa0ggmc.ulac.client;

import aaaa0ggmc.ulac.ControlState;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityList;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.potion.PotionEffect;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.RayTraceResult;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * 状态快照：在客户端 tick 构建（游戏状态只能在游戏线程读），HTTP 线程拿现成 JSON。
 */
public final class SnapshotBuilder {

    private SnapshotBuilder() {
    }

    public static JsonObject build(Minecraft mc) {
        JsonObject root = new JsonObject();
        root.addProperty("ok", true);

        if (mc.player == null || mc.world == null) {
            root.addProperty("inGame", false);
            return root;
        }
        root.addProperty("inGame", true);

        EntityPlayer player = mc.player;

        JsonObject p = new JsonObject();
        p.addProperty("name", player.getName());
        p.addProperty("x", round(player.posX));
        p.addProperty("y", round(player.posY));
        p.addProperty("z", round(player.posZ));
        p.addProperty("yaw", round(player.rotationYaw));
        p.addProperty("pitch", round(player.rotationPitch));
        p.addProperty("health", round(player.getHealth()));
        p.addProperty("maxHealth", round(player.getMaxHealth()));
        p.addProperty("food", player.getFoodStats().getFoodLevel());
        p.addProperty("air", player.getAir());
        p.addProperty("xpLevel", player.experienceLevel);
        p.addProperty("xpProgress", round(player.experience));
        p.addProperty("gamemode", mc.playerController.getCurrentGameType().getName());
        p.addProperty("dimension", player.dimension);
        p.addProperty("biome", mc.world.getBiome(new BlockPos(player)).getBiomeName());
        p.addProperty("onGround", player.onGround);
        p.addProperty("inWater", player.isInWater());
        p.addProperty("sprinting", player.isSprinting());
        p.add("held", describe(player.getHeldItemMainhand()));

        JsonArray hotbar = new JsonArray();
        for (int i = 0; i < 9; i++) {
            hotbar.add(describe(player.inventory.mainInventory.get(i)));
        }
        p.add("hotbar", hotbar);
        JsonArray armor = new JsonArray();
        for (int i = 0; i < 4; i++) {
            armor.add(describe(player.inventory.armorInventory.get(i)));
        }
        p.add("armor", armor);
        p.add("offhand", describe(player.getHeldItemOffhand()));

        JsonArray effects = new JsonArray();
        for (PotionEffect effect : player.getActivePotionEffects()) {
            JsonObject e = new JsonObject();
            e.addProperty("potion", String.valueOf(effect.getPotion().getRegistryName()));
            e.addProperty("duration", effect.getDuration());
            e.addProperty("amplifier", effect.getAmplifier());
            effects.add(e);
        }
        p.add("effects", effects);
        root.add("player", p);

        JsonObject w = new JsonObject();
        w.addProperty("time", mc.world.getWorldTime());
        w.addProperty("day", mc.world.isDaytime());
        w.addProperty("raining", mc.world.isRaining());
        w.addProperty("players", mc.world.playerEntities.size());
        root.add("world", w);

        // 附近实体（32 格内，按距离取前 12）
        List<Object[]> near = new ArrayList<>();
        for (Object obj : mc.world.loadedEntityList) {
            if (!(obj instanceof Entity) || obj == player) {
                continue;
            }
            Entity e = (Entity) obj;
            double d = e.getDistance(player);
            if (d <= 32.0D) {
                near.add(new Object[]{e, d});
            }
        }
        Collections.sort(near, Comparator.comparingDouble(o -> (Double) o[1]));
        JsonArray arr = new JsonArray();
        for (int i = 0; i < Math.min(12, near.size()); i++) {
            Entity e = (Entity) near.get(i)[0];
            JsonObject o = new JsonObject();
            o.addProperty("name", e.getName());
            o.addProperty("id", String.valueOf(EntityList.getKey(e)));
            o.addProperty("x", round(e.posX));
            o.addProperty("y", round(e.posY));
            o.addProperty("z", round(e.posZ));
            o.addProperty("distance", round((Double) near.get(i)[1]));
            if (e instanceof EntityLivingBase) {
                o.addProperty("health", round(((EntityLivingBase) e).getHealth()));
            }
            arr.add(o);
        }
        root.add("nearby", arr);

        // 注视目标
        JsonObject look = new JsonObject();
        RayTraceResult hit = mc.objectMouseOver;
        if (hit == null) {
            look.addProperty("type", "miss");
        } else if (hit.typeOfHit == RayTraceResult.Type.ENTITY && hit.entityHit != null) {
            look.addProperty("type", "entity");
            look.addProperty("name", hit.entityHit.getName());
            look.addProperty("id", String.valueOf(EntityList.getKey(hit.entityHit)));
        } else if (hit.typeOfHit == RayTraceResult.Type.BLOCK && hit.getBlockPos() != null) {
            BlockPos pos = hit.getBlockPos();
            look.addProperty("type", "block");
            look.addProperty("x", pos.getX());
            look.addProperty("y", pos.getY());
            look.addProperty("z", pos.getZ());
            look.addProperty("block", String.valueOf(
                    net.minecraft.block.Block.REGISTRY.getNameForObject(mc.world.getBlockState(pos).getBlock())));
            look.addProperty("meta", mc.world.getBlockState(pos).getBlock().getMetaFromState(mc.world.getBlockState(pos)));
        } else {
            look.addProperty("type", "miss");
        }
        root.add("lookingAt", look);

        root.addProperty("screen", mc.currentScreen == null ? "ingame" : mc.currentScreen.getClass().getSimpleName());
        root.addProperty("fps", Minecraft.getDebugFPS());
        root.addProperty("lastScreenshot", ControlState.lastScreenshot);

        JsonObject tick = new JsonObject();
        tick.addProperty("targetTps", ControlState.tickTps);
        tick.addProperty("lastTickMs", round(ControlState.lastTickMs));
        tick.addProperty("achievedTps", round(ControlState.achievedTps));
        root.add("tick", tick);

        JsonArray chat = new JsonArray();
        for (String line : ControlState.recentChat(20)) {
            chat.add(line);
        }
        root.add("chat", chat);

        return root;
    }

    private static JsonObject describe(ItemStack stack) {
        JsonObject o = new JsonObject();
        if (stack == null || stack.isEmpty()) {
            o.addProperty("empty", true);
            return o;
        }
        o.addProperty("id", String.valueOf(stack.getItem().getRegistryName()));
        o.addProperty("count", stack.getCount());
        o.addProperty("name", stack.getDisplayName());
        return o;
    }

    private static double round(double v) {
        return Math.round(v * 1000.0D) / 1000.0D;
    }
}
