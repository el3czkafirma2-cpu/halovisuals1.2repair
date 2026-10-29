package pl.halovisuals;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.PlayerSkinDrawer;
import net.minecraft.client.gui.screen.ChatScreen;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.client.util.InputUtil;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.sound.SoundEvent;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.ActionResult;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.lwjgl.glfw.GLFW;
import pl.halovisuals.gui.ClickGui;
import pl.halovisuals.gui.Draw;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** Event wiring + all renderers/effects of Halo Visuals. */
public final class Features {
    private static final MinecraftClient mc = MinecraftClient.getInstance();
    private static final Random rnd = new Random();

    // combat / target state
    private static LivingEntity target, shownRef, pendEnt, flashEnt;
    private static long lastHit, hitMarkerAt, comboAt, comboPopAt, activeUntil, pendAt, flashAt, screenFlashAt, cinemaAt;
    private static float pendHp, shownHp, popT, blurAmt, prevYaw, prevPitch;
    private static int combo;
    private static boolean killHandled, deadHandled, cinemaWasOn;
    private static long lastNanos = System.nanoTime();
    // HUD drag
    private static boolean dragging, lmbWas;
    private static double dragDX, dragDY;
    // notifications
    private static String notifText = "";
    private static long notifAt = -10000;
    // misc
    private static boolean wasOnGround = true, keyWasDown, fbApplied;
    private static ClientWorld lastWorld;
    private record Circle(double x, double y, double z, long t) {}
    private record Dmg(double x, double y, double z, long t, String text) {}
    private static final List<Circle> circles = new ArrayList<>();
    private static final List<Dmg> dmg = new ArrayList<>();
    // camera basis (world space) for billboards
    private static double crx, cry, crz, cux, cuy, cuz;

    private static final EquipmentSlot[] SLOTS = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS,
            EquipmentSlot.FEET, EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND};

    private Features() {}

    public static void notify(String s) { notifText = s; notifAt = System.currentTimeMillis(); }

    private static boolean on(String n) { Module m = ModuleManager.get(n); return m != null && m.enabled; }
    private static Module mod(String n) { return ModuleManager.get(n); }

    /** Crystal-like UI sound (ClickGUI open/close/hover/click). */
    public static void uiSound(float pitch, float vol) {
        Module g = mod("Click GUI");
        if (g == null || !g.on("UI Sounds")) return;
        mc.getSoundManager().play(PositionedSoundInstance.master(SoundEvents.BLOCK_AMETHYST_BLOCK_CHIME, pitch, (float) (vol * g.num("UI Volume"))));
    }

    public static void init() {
        AttackEntityCallback.EVENT.register((player, world, hand, entity, hit) -> {
            if (world.isClient()) onAttack(entity);
            return ActionResult.PASS;
        });
        ClientTickEvents.END_CLIENT_TICK.register(Features::tick);
        HudRenderCallback.EVENT.register(Features::hud);
        WorldRenderEvents.AFTER_ENTITIES.register(Features::world);
        DiscordRpc.init();
    }

    // ------------------------------------------------------------------ helpers
    private static void setTarget(LivingEntity le) {
        if (le != target) { target = le; killHandled = false; shownHp = le.getHealth(); }
    }

    /** Layered playback: every 1.0 of loudness adds another full-volume layer (louder than the vanilla cap). */
    private static void play(SoundEvent s, float pitch, float loudness) {
        float v = loudness;
        for (int i = 0; i < 4 && v > 0.01f; i++, v -= 1f)
            mc.getSoundManager().play(PositionedSoundInstance.master(s, pitch, Math.min(1f, v)));
    }

    private static void entityBurst(Entity e, int style, int count, double speed) {
        Particles.spawn(style, e.getX(), e.getY() + e.getHeight() / 2, e.getZ(), e.getWidth() + 0.3, e.getHeight(), speed, count);
    }

    private static float ease(float x) { x = Math.max(0f, Math.min(1f, x)); return x * x * (3 - 2 * x); }

    private static float easeOutBack(float x) {
        x = Math.max(0f, Math.min(1f, x));
        float c1 = 1.70158f, c3 = c1 + 1f;
        return 1f + c3 * (float) Math.pow(x - 1, 3) + c1 * (float) Math.pow(x - 1, 2);
    }

    private static int[] pal() {
        return switch ((int) mod("Target ESP").num("Palette")) {
            case 1 -> new int[]{0xFFFFFFFF, 0xFF3AB8FF, 0xFF1565FF};
            case 2 -> new int[]{0xFFFFFFFF, 0xFFFF6AA8, 0xFFB0307A};
            default -> new int[]{Draw.WHITE, Draw.YELLOW, Draw.ORANGE};
        };
    }

    // ------------------------------------------------------------------ events
    private static void onAttack(Entity e) {
        if (!(e instanceof LivingEntity le) || mc.world == null) return;
        long now = System.currentTimeMillis();
        setTarget(le);
        lastHit = now; hitMarkerAt = now; activeUntil = Math.max(activeUntil, now + 4000);
        pendEnt = le; pendHp = le.getHealth(); pendAt = now;
        flashEnt = le; flashAt = now;
        if (now - comboAt > 3000) combo = 0;
        combo++; comboAt = now; comboPopAt = now;

        Module hs = mod("Hit Sounds");
        if (hs.enabled) {
            float vol = (float) hs.num("Loudness");
            switch (hs.mode("Style")) {
                case 1 -> play(SoundEvents.BLOCK_AMETHYST_BLOCK_CHIME, 1.5f, vol);
                case 2 -> play(SoundEvents.BLOCK_NOTE_BLOCK_BELL.value(), 1.3f, vol);
                case 3 -> play(SoundEvents.BLOCK_NOTE_BLOCK_BASEDRUM.value(), 0.8f, vol);
                default -> {}
            }
        }
        if (on("Hit Particles")) {
            Module hp = mod("Hit Particles");
            entityBurst(le, hp.mode("Style"), (int) hp.num("Amount"), 0.22);
        }
        if (on("Hit Bubbles")) {
            int n = (int) mod("Hit Bubbles").num("Amount");
            for (int i = 0; i < n; i++)
                Particles.add(Particles.T_BUBBLE, le.getX() + (rnd.nextDouble() - .5) * le.getWidth() * 1.6, le.getBodyY(rnd.nextDouble()),
                        le.getZ() + (rnd.nextDouble() - .5) * le.getWidth() * 1.6, (rnd.nextDouble() - .5) * 0.03, 0.02 + rnd.nextDouble() * 0.03,
                        (rnd.nextDouble() - .5) * 0.03, 18 + rnd.nextInt(14), 0.08f + rnd.nextFloat() * 0.08f, Draw.WHITE, 0xFFBFE6FF, -0.0008f, 0.95f);
        }
    }

    /** Called from the network mixin when a totem pops (status 35). */
    public static void totemPop(Entity e) {
        if (!on("Totem Pop") || e == null) return;
        entityBurst(e, Particles.SPARKS, 60, 0.35);
        entityBurst(e, Particles.PETALS, 40, 0.3);
        entityBurst(e, Particles.CRYSTAL, 25, 0.25);
        if (e == mc.player) screenFlashAt = System.currentTimeMillis();
    }

    // ------------------------------------------------------------------ tick
    private static void tick(MinecraftClient c) {
        if (c.player == null || c.world == null) return;
        long now = System.currentTimeMillis();

        Module pf = mod("Performance");
        Particles.max = pf.enabled ? (int) pf.num("Particle Limit") : 1500;
        Particles.glow = !pf.enabled || pf.on("Glow Layers");

        if (c.world != lastWorld) {
            lastWorld = c.world; Particles.clear(); target = null; shownRef = null; dmg.clear(); circles.clear();
            if (on("Cinematic Text")) cinemaAt = now;
        }
        boolean cin = on("Cinematic Text");
        if (cin && !cinemaWasOn) cinemaAt = now;
        cinemaWasOn = cin;

        // ClickGUI key (configurable, default I)
        int key = (int) mod("Click GUI").num("Key");
        boolean down = InputUtil.isKeyPressed(c.getWindow().getHandle(), key);
        if (down && !keyWasDown && c.currentScreen == null) c.setScreen(new ClickGui());
        keyWasDown = down;

        // crosshair target -> Target HUD / ESP
        if (c.targetedEntity instanceof LivingEntity le && le.isAlive()) {
            setTarget(le);
            activeUntil = Math.max(activeUntil, now + 1200);
        }

        // damage numbers: compare health shortly after the hit
        if (pendEnt != null && now - pendAt >= 260) {
            float d = pendHp - pendEnt.getHealth();
            if (d > 0.05f && on("Damage Numbers"))
                dmg.add(new Dmg(pendEnt.getX(), pendEnt.getY() + pendEnt.getHeight() + 0.25, pendEnt.getZ(), now, String.format("-%.1f", d)));
            pendEnt = null;
        }
        dmg.removeIf(d -> now - d.t() > 1200);

        // kill detection
        if (target != null && !killHandled && !target.isAlive()) {
            killHandled = true;
            if (now - lastHit < 5000) {
                if (on("Kill Effect")) {
                    entityBurst(target, Particles.ANGEL_DUST, 40, 0.3);
                    entityBurst(target, Particles.SPARKS, 30, 0.35);
                    entityBurst(target, Particles.CRYSTAL, 14, 0.25);
                }
                if (on("Kill Sound")) play(SoundEvents.ENTITY_PLAYER_LEVELUP, 1.3f, (float) mod("Kill Sound").num("Loudness"));
            }
        }

        // own death
        if (c.player.getHealth() <= 0 && !deadHandled) {
            deadHandled = true;
            if (on("Death Effect")) {
                entityBurst(c.player, Particles.ANGEL_DUST, 70, 0.35);
                entityBurst(c.player, Particles.SPARKS, 40, 0.4);
                entityBurst(c.player, Particles.SNOW, 25, 0.2);
            }
        } else if (c.player.getHealth() > 0) deadHandled = false;

        // fullbright
        if (on("Fullbright")) {
            StatusEffectInstance cur = c.player.getStatusEffect(StatusEffects.NIGHT_VISION);
            if (cur == null || cur.getDuration() < 300) {
                c.player.addStatusEffect(new StatusEffectInstance(StatusEffects.NIGHT_VISION, 1000, 0, false, false, false));
                fbApplied = true;
            }
        } else if (fbApplied) {
            c.player.removeStatusEffect(StatusEffects.NIGHT_VISION);
            fbApplied = false;
        }

        // atmosphere
        if (on("Atmosphere")) {
            Module am = mod("Atmosphere");
            // if (am.on("Lock Time")) c.world.setTimeOfDay((long) am.num("Time"));
            if (am.on("Clear Weather")) { c.world.setRainGradient(0f); c.world.setThunderGradient(0f); }
        }

        // jumping
        boolean og = c.player.isOnGround();
        if (wasOnGround && !og && c.player.getVelocity().y > 0.2) {
            if (on("Jump Circles")) circles.add(new Circle(c.player.getX(), c.player.getY(), c.player.getZ(), now));
            if (on("Jump Particles")) {
                Module jp = mod("Jump Particles");
                Particles.spawn(jp.mode("Style"), c.player.getX(), c.player.getY() + 0.1, c.player.getZ(), 0.9, 0.25, 0.16, (int) jp.num("Amount"));
            }
        }
        wasOnGround = og;
        circles.removeIf(ci -> now - ci.t() > 900);

        // ambient particles
        if (on("Ambient Particles")) {
            Module ap = mod("Ambient Particles");
            int n = (int) ap.num("Amount"), st = ap.mode("Style");
            for (int i = 0; i < n; i++)
                Particles.spawn(st, c.player.getX() + (rnd.nextDouble() * 2 - 1) * 12, c.player.getY() + rnd.nextDouble() * 7 - 1,
                        c.player.getZ() + (rnd.nextDouble() * 2 - 1) * 12, 0.2, 0.2, 0.05, 1);
        }

        // trails
        if (on("Trails") && c.player.getVelocity().horizontalLengthSquared() > 0.001)
            Particles.spawn(mod("Trails").mode("Style"), c.player.getX(), c.player.getY() + 0.15, c.player.getZ(), 0.25, 0.1, 0.02, 2);

        boolean fp = c.options.getPerspective().isFirstPerson();
        // halo sparkles
        if (on("Halo") && !fp && c.player.age % 2 == 0) {
            double a = rnd.nextDouble() * Math.PI * 2, r = mod("Halo").num("Radius");
            Particles.add(Particles.T_STAR, c.player.getX() + Math.cos(a) * r, c.player.getY() + c.player.getHeight() + 0.3, c.player.getZ() + Math.sin(a) * r,
                    0, 0.01, 0, 20, 0.07f, Draw.WHITE, Draw.YELLOW, 0f, 0.95f);
        }
        // orbit stars (cosmetics)
        Module cos = mod("Cosmetics");
        if (cos.enabled && cos.on("Orbit Stars") && !fp) {
            double a = c.player.age * 0.35;
            Particles.add(Particles.T_STAR, c.player.getX() + Math.cos(a) * 0.8, c.player.getY() + 1.0 + Math.sin(a * 0.5) * 0.5, c.player.getZ() + Math.sin(a) * 0.8,
                    0, 0.004, 0, 16, 0.1f, Draw.WHITE, Draw.ORANGE, 0f, 0.95f);
        }
        Particles.tick();
    }

    // ------------------------------------------------------------------ HUD
    private static void hud(DrawContext g, RenderTickCounter tc) {
        if (mc.player == null || mc.options.hudHidden) return;
        long now = System.currentTimeMillis();
        long nn = System.nanoTime();
        float dt = Math.min(0.1f, (nn - lastNanos) / 1e9f);
        lastNanos = nn;
        int w = g.getScaledWindowWidth(), h = g.getScaledWindowHeight();
        int cx = w / 2, cy = h / 2;
        Draw.pulse = 0.5f + 0.5f * (float) Math.sin(now / 600.0);

        // ---- screen effects under the HUD
        if (on("Color Saturation")) {
            Module cs = mod("Color Saturation");
            float s = (float) cs.num("Strength");
            int col = switch (cs.mode("Style")) { case 1 -> 0xFF7AA8; case 2 -> 0x9AD8FF; default -> 0xFFD84D; };
            g.fill(0, 0, w, h, Draw.a(col, (int) (34 * s)));
            g.fillGradient(0, 0, w, h / 3, Draw.a(col, (int) (50 * s)), Draw.a(col, 0));
            g.fillGradient(0, h * 2 / 3, w, h, Draw.a(col, 0), Draw.a(col, (int) (60 * s)));
        }
        if (on("Motion Blur")) {
            float yaw = mc.player.getYaw(), pitch = mc.player.getPitch();
            float sp = Math.abs(MathHelper.wrapDegrees(yaw - prevYaw)) + Math.abs(pitch - prevPitch);
            prevYaw = yaw; prevPitch = pitch;
            blurAmt += (Math.min(1f, sp / 22f) - blurAmt) * 0.35f;
            float k = blurAmt * (float) mod("Motion Blur").num("Strength");
            int bw = (int) (w * 0.16f * k);
            for (int i = 0; i < bw; i++) {
                int a = (int) (70 * k * Math.pow(1 - i / (float) bw, 2));
                g.fill(i, 0, i + 1, h, Draw.a(Draw.WHITE, a));
                g.fill(w - 1 - i, 0, w - i, h, Draw.a(Draw.WHITE, a));
            }
        }
        if (now - screenFlashAt < 500) {
            float f = 1f - (now - screenFlashAt) / 500f;
            g.fill(0, 0, w, h, Draw.a(Draw.YELLOW, (int) (110 * f * f)));
        }

        // ---- Watermark
        if (on("Watermark")) {
            PlayerListEntry pe = mc.getNetworkHandler() == null ? null : mc.getNetworkHandler().getPlayerListEntry(mc.player.getUuid());
            String name = "Halo Visuals", rest = "  /  " + mc.getCurrentFps() + " FPS  /  " + (pe == null ? 0 : pe.getLatency()) + " ms";
            int tw = Draw.width(name) + Draw.width(rest);
            Draw.panel(g, 8, 8, tw + 20, 20, 10);
            Draw.text(g, name, 18, 14, Draw.YELLOW);
            Draw.text(g, rest, 18 + Draw.width(name), 14, Draw.WHITE);
        }

        // ---- Dynamic Island
        if (on("Dynamic Island")) {
            long el = now - notifAt;
            if (el < 2400) {
                float f = el < 260 ? easeOutBack(el / 260f) : (el > 2100 ? ease((2400 - el) / 300f) : 1f);
                int tw = Draw.width(notifText) + 28, pw = (int) (tw * Math.max(0f, f)), ph = (int) (8 + 14 * Math.max(0f, Math.min(1f, f)));
                Draw.panel(g, cx - pw / 2, 8, pw, ph, ph / 2);
                if (f > 0.9f) {
                    Draw.rrect(g, cx - tw / 2 + 8, 8 + ph / 2 - 2, 4, 4, 2, Draw.ORANGE);
                    Draw.text(g, notifText, cx - tw / 2 + 16, 8 + ph / 2 - 4, Draw.WHITE);
                }
            }
        }

        targetHud(g, w, h, now, dt);

        // ---- Potions
        if (on("Potions")) {
            int y = 40;
            for (StatusEffectInstance e : mc.player.getStatusEffects()) {
                if (fbApplied && e.getEffectType().equals(StatusEffects.NIGHT_VISION)) continue;
                String nm = e.getEffectType().value().getName().getString() + (e.getAmplifier() > 0 ? " " + (e.getAmplifier() + 1) : "");
                int sec = e.getDuration() / 20;
                String tm = e.isInfinite() ? "inf" : String.format("%d:%02d", sec / 60, sec % 60);
                int pw = Draw.width(nm) + Draw.width(tm) + 30, x = w - 8 - pw;
                Draw.panel(g, x, y, pw, 18, 9);
                Draw.rrect(g, x + 6, y + 7, 4, 4, 2, e.getEffectType().value().isBeneficial() ? Draw.YELLOW : Draw.ORANGE);
                Draw.text(g, nm, x + 14, y + 5, Draw.WHITE);
                Draw.text(g, tm, x + pw - 8 - Draw.width(tm), y + 5, Draw.GRAY);
                y += 24;
            }
        }

        // ---- Cooldowns / ring
        float cd = mc.player.getAttackCooldownProgress(0f);
        if (on("Cooldowns") && cd < 1f) {
            Draw.panel(g, cx - 32, h - 66, 64, 10, 5);
            Draw.bar(g, cx - 28, h - 63, 56, 4, cd);
        }
        if (on("Cooldown Ring") && cd < 1f) {
            Draw.ring(g, cx, cy, 12, 1f, Draw.a(Draw.WHITE, 40));
            Draw.ring(g, cx, cy, 12, cd, Draw.ORANGE);
        }

        // ---- Combo (pop animation on every hit)
        if (on("Combo Counter") && combo > 0) {
            if (now - comboAt > 3000) combo = 0;
            else {
                float pop = 1f + 0.45f * (1f - ease((now - comboPopAt) / 220f));
                float fade = Math.min(1f, (3000 - (now - comboAt)) / 500f);
                MatrixStack ms = g.getMatrices();
                ms.push();
                ms.translate((float) cx, (float) (cy + 26), 0f);
                ms.scale(pop, pop, 1f);
                int col = Draw.a(Draw.lerp(Draw.YELLOW, Draw.ORANGE, Math.min(1f, combo / 15f)), (int) (255 * fade));
                Draw.ctext(g, "x" + combo + " COMBO", 0, 0, col);
                ms.pop();
            }
        }

        // ---- Crosshair
        Module ch = mod("Crosshair");
        if (ch.enabled) {
            int s = (int) ch.num("Size"), gp = (int) ch.num("Gap");
            switch (ch.mode("Style")) {
                case 1 -> Draw.rrect(g, cx - 2, cy - 2, 4, 4, 2, Draw.a(Draw.YELLOW, 230));
                case 2 -> Draw.ring(g, cx, cy, s + gp, 1f, Draw.a(Draw.YELLOW, 230));
                default -> {
                    int col = Draw.a(Draw.WHITE, 230), sh = 0x66000000;
                    g.fill(cx - gp - s - 1, cy - 1, cx - gp + 1, cy + 2, sh); g.fill(cx - gp - s, cy, cx - gp, cy + 1, col);
                    g.fill(cx + gp - 1, cy - 1, cx + gp + s + 1, cy + 2, sh); g.fill(cx + gp, cy, cx + gp + s, cy + 1, col);
                    g.fill(cx - 1, cy - gp - s - 1, cx + 2, cy - gp + 1, sh); g.fill(cx, cy - gp - s, cx + 1, cy - gp, col);
                    g.fill(cx - 1, cy + gp - 1, cx + 2, cy + gp + s + 1, sh); g.fill(cx, cy + gp, cx + 1, cy + gp + s, col);
                    g.fill(cx, cy, cx + 1, cy + 1, Draw.ORANGE);
                }
            }
        }

        // ---- Hit marker
        Module hm = mod("Hit Marker");
        if (hm.enabled) {
            float dur = (float) hm.num("Duration"), el = now - hitMarkerAt;
            if (el < dur) {
                int a = (int) (255 * (1 - el / dur)), size = (int) hm.num("Size"), gap = 3;
                int col = Draw.a(Draw.lerp(Draw.WHITE, Draw.ORANGE, el / dur), a);
                for (int i = gap; i < gap + size; i++)
                    for (int sx = -1; sx <= 1; sx += 2) for (int sy = -1; sy <= 1; sy += 2)
                        g.fill(cx + sx * i, cy + sy * i, cx + sx * i + 1, cy + sy * i + 1, col);
            }
        }

        // ---- Cinematic bars + text (on top of everything)
        if (on("Cinematic Text") && cinemaAt > 0) {
            float el = (now - cinemaAt) / 1000f;
            if (el < 4.6f) {
                float bar = el < 0.6f ? ease(el / 0.6f) : el > 3.9f ? ease(1f - (el - 3.9f) / 0.7f) : 1f;
                int bh = (int) (h * 0.11f * bar);
                g.fill(0, 0, w, bh, 0xFF000000);
                g.fill(0, h - bh, w, h, 0xFF000000);
                String[] parts = mc.world.getRegistryKey().getValue().getPath().split("_");
                StringBuilder sb = new StringBuilder();
                for (String p : parts) { if (p.isEmpty()) continue; if (sb.length() > 0) sb.append("  "); sb.append(Character.toUpperCase(p.charAt(0))).append(p.substring(1)); }
                String txt = sb.toString().toUpperCase();
                int shown = (int) (txt.length() * ease((el - 0.7f) / 1.4f));
                float fade = el > 3.6f ? ease(1f - (el - 3.6f) / 0.8f) : 1f;
                StringBuilder sp = new StringBuilder();
                for (int i = 0; i < shown; i++) sp.append(txt.charAt(i)).append(' ');
                Draw.ctext(g, sp.toString(), cx, h - bh - 26, Draw.a(Draw.YELLOW, (int) (255 * fade)));
                Draw.ctext(g, "H A L O   V I S U A L S", cx, h - bh - 14, Draw.a(Draw.WHITE, (int) (150 * fade)));
            }
        }
    }

    private static void targetHud(DrawContext g, int w, int h, long now, float dt) {
        Module th = mod("Target HUD");
        if (!th.enabled) { popT = 0f; return; }
        boolean chat = mc.currentScreen instanceof ChatScreen;
        boolean live = target != null && now < activeUntil && (target.isAlive() || now - lastHit < 1500);
        LivingEntity src = target;
        boolean preview = false;
        if (!live && chat) { src = mc.player; live = true; preview = true; }
        if (live) shownRef = src;
        popT = Math.max(0f, Math.min(1f, popT + (live ? 1f : -1f) * dt / 0.28f));
        if (popT <= 0.001f || shownRef == null) return;

        LivingEntity t = shownRef;
        boolean eq = th.on("Equipment");
        float sc = (float) th.num("Scale");
        int pw = 150, ph = eq ? 70 : 44;
        int rw = (int) (pw * sc), rh = (int) (ph * sc);
        int x = Math.max(0, Math.min(w - rw, (int) (th.num("X") * w)));
        int y = Math.max(0, Math.min(h - rh, (int) (th.num("Y") * h)));

        // drag with the mouse while chat is open
        if (chat) {
            double mxs = mc.mouse.getX() * w / (double) mc.getWindow().getWidth();
            double mys = mc.mouse.getY() * h / (double) mc.getWindow().getHeight();
            boolean lmb = GLFW.glfwGetMouseButton(mc.getWindow().getHandle(), GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS;
            if (lmb && !lmbWas && mxs >= x && mxs <= x + rw && mys >= y && mys <= y + rh) { dragging = true; dragDX = mxs - x; dragDY = mys - y; }
            if (!lmb) { if (dragging) ModuleManager.save(); dragging = false; }
            if (dragging) {
                th.s("X").value = Math.max(0, Math.min(1, (mxs - dragDX) / w));
                th.s("Y").value = Math.max(0, Math.min(1, (mys - dragDY) / h));
                x = Math.max(0, Math.min(w - rw, (int) (th.num("X") * w)));
                y = Math.max(0, Math.min(h - rh, (int) (th.num("Y") * h)));
            }
            lmbWas = lmb;
            Draw.text(g, "Drag to move  -  Target HUD", x, Math.max(2, y - 12), Draw.a(Draw.WHITE, 170));
        } else { dragging = false; lmbWas = false; }

        float hp = Math.max(0, t.getHealth()), max = Math.max(1, t.getMaxHealth());
        if (preview) shownHp = hp; else shownHp += (hp - shownHp) * 0.15f;

        // pop-up zoom + damage shake
        float pop = easeOutBack(popT);
        float amp = t.hurtTime > 0 ? t.hurtTime / 10f * 3.4f : 0f;
        float shx = (float) Math.sin(now * 0.085) * amp, shy = (float) Math.cos(now * 0.11) * amp * 0.6f;
        MatrixStack ms = g.getMatrices();
        ms.push();
        ms.translate(x + rw / 2f + shx, y + rh / 2f + shy, 0f);
        ms.scale(sc * pop, sc * pop, 1f);
        ms.translate(-pw / 2f, -ph / 2f, 0f);

        Draw.panel(g, 0, 0, pw, ph, 10);
        Draw.rrect(g, 6, 6, 32, 32, 8, Draw.a(Draw.WHITE, 40));
        if (t instanceof AbstractClientPlayerEntity p) PlayerSkinDrawer.draw(g, p.getSkinTextures(), 8, 8, 28);
        else {
            String n0 = t.getName().getString();
            Draw.ctext(g, n0.isEmpty() ? "?" : n0.substring(0, 1).toUpperCase(), 22, 18, Draw.YELLOW);
        }
        String nm = t.getName().getString();
        Draw.text(g, nm.length() > 14 ? nm.substring(0, 14) : nm, 44, 9, Draw.WHITE);
        String hs = String.format("%.1f", hp);
        Draw.text(g, hs, pw - 8 - Draw.width(hs), 9, t.hurtTime > 0 ? Draw.WHITE : Draw.ORANGE);
        Draw.bar(g, 44, 26, pw - 54, 7, shownHp / max);
        if (eq) {
            for (int i = 0; i < SLOTS.length; i++) {
                ItemStack st = t.getEquippedStack(SLOTS[i]);
                int ix = 9 + i * 22, iy = 47;
                Draw.rrect(g, ix - 1, iy - 1, 18, 18, 4, Draw.a(Draw.WHITE, 30));
                if (!st.isEmpty()) {
                    g.drawItem(st, ix, iy);
                    g.drawStackOverlay(mc.textRenderer, st, ix, iy); // durability bar + stack count
                }
            }
        }
        ms.pop();
    }

    // ------------------------------------------------------------------ world rendering
    private static void world(WorldRenderContext c) {
        if (mc.world == null || mc.player == null) return;
        MatrixStack m = c.matrixStack();
        if (m == null) return;
        VertexConsumerProvider.Immediate imm = mc.getBufferBuilders().getEntityVertexConsumers();
        long now = System.currentTimeMillis();
        double t = now / 1000.0;
        float td = c.tickCounter().getTickDelta(false);
        Camera camera = c.camera();
        Gfx.cam = camera.getPos();
        Quaternionf rot = camera.getRotation();
        Vector3f cr = new Vector3f(1, 0, 0).rotate(rot), cu = new Vector3f(0, 1, 0).rotate(rot);
        crx = cr.x; cry = cr.y; crz = cr.z; cux = cu.x; cuy = cu.y; cuz = cu.z;

        boolean fp = mc.options.getPerspective().isFirstPerson();
        Module cos = mod("Cosmetics");
        boolean halo = on("Halo") && !fp, hat = on("China Hat") && !fp, wings = cos.enabled && cos.on("Wings") && !fp;
        boolean esp = on("Target ESP") && target != null && target.isAlive() && now < activeUntil;
        boolean flash = on("Hit Color") && flashEnt != null && now - flashAt < 400;
        boolean hb = on("Hitbox Customizer");
        MatrixStack.Entry e = m.peek();

        List<LivingEntity> boxes = new ArrayList<>();
        Module hbm = mod("Hitbox Customizer");
        if (hb) {
            double range = hbm.num("Range");
            for (Entity en : mc.world.getEntities())
                if (en instanceof LivingEntity le && le != mc.player && le.isAlive() && le.squaredDistanceTo(mc.player) < range * range) boxes.add(le);
        }

        if (halo || hat || wings || esp || flash || !circles.isEmpty() || Particles.count() > 0 || (hb && hbm.on("Fill"))) {
            VertexConsumer q = imm.getBuffer(RenderLayer.getDebugQuads());

            // Halo + China Hat: ONLY above the local player
            if (halo || hat) {
                Vec3d pos = mc.player.getLerpedPos(td);
                double top = pos.y + mc.player.getHeight();
                if (halo) halo(e, q, pos, top, t);
                if (hat) chinaHat(e, q, pos, top, t);
            }
            if (wings) wings(e, q, td, t);

            if (esp) {
                Module em = mod("Target ESP");
                Vec3d p = target.getLerpedPos(td);
                int[] pl = pal();
                double sp = em.num("Speed");
                boolean gl = em.on("Glow");
                switch (em.mode("Style")) {
                    case 1 -> espSoul(e, q, p, target.getHeight(), target.getWidth(), t, sp, pl, gl);
                    case 2 -> espSquare(e, q, p, target.getHeight(), t, sp, pl, gl);
                    case 3 -> espCrystals(e, q, p, target.getHeight(), target.getWidth(), t, sp, pl, gl, (int) em.num("Rings"));
                    default -> espRings(e, q, p, target.getHeight(), target.getWidth(), t, sp, pl, gl, (int) em.num("Rings"));
                }
            }

            if (flash) {
                float pr = (now - flashAt) / 400f;
                int col = switch (mod("Hit Color").mode("Color")) { case 1 -> Draw.WHITE; case 2 -> 0xFFFF7AA8; default -> Draw.YELLOW; };
                Vec3d p = flashEnt.getLerpedPos(td);
                double hw = flashEnt.getWidth() / 2 + 0.05 + 0.08 * pr;
                Gfx.boxFaces(e, q, p.x - hw, p.y - 0.03, p.z - hw, p.x + hw, p.y + flashEnt.getHeight() + 0.05, p.z + hw,
                        Draw.a(col, (int) (110 * (1 - pr) * (1 - pr))));
            }

            if (hb && hbm.on("Fill")) {
                double ex = hbm.num("Expand");
                for (LivingEntity le : boxes) {
                    Vec3d p = le.getLerpedPos(td);
                    double hw = le.getWidth() / 2 + ex;
                    Gfx.boxFaces(e, q, p.x - hw, p.y - ex, p.z - hw, p.x + hw, p.y + le.getHeight() + ex, p.z + hw, Draw.a(Draw.YELLOW, 22));
                }
            }

            for (Circle ci : circles) {
                float age = (now - ci.t()) / 900f;
                float f = 1f - age;
                double rad = 0.3 + age * 1.7;
                Gfx.Col col = tt -> Draw.a(Draw.lerp(Draw.WHITE, Draw.ORANGE, age), (int) (230 * f * f));
                Gfx.annulus(e, q, ci.x(), ci.y() + 0.02, ci.z(), rad, 0.05, 56, col);
                Gfx.annulus(e, q, ci.x(), ci.y() + 0.02, ci.z(), rad, 0.20, 56, tt -> Gfx.alpha(col.at(tt), 0.3f));
            }

            Particles.render(e, q, camera, td);
            imm.draw(RenderLayer.getDebugQuads());
        }

        if (hb) {
            VertexConsumer l = imm.getBuffer(RenderLayer.getLines());
            double ex = hbm.num("Expand");
            for (LivingEntity le : boxes) {
                Vec3d p = le.getLerpedPos(td);
                double hw = le.getWidth() / 2 + ex;
                Gfx.boxLines(e, l, p.x - hw, p.y - ex, p.z - hw, p.x + hw, p.y + le.getHeight() + ex, p.z + hw, Draw.a(Draw.YELLOW, 235));
            }
            imm.draw(RenderLayer.getLines());
        }

        // floating damage numbers
        if (!dmg.isEmpty() && on("Damage Numbers")) {
            float sc0 = (float) mod("Damage Numbers").num("Scale");
            for (Dmg d : dmg) {
                float age = (now - d.t()) / 1000f;
                float pop = age < 0.15f ? easeOutBack(age / 0.15f) : 1f;
                int a = (int) (255 * Math.min(1f, (1.2f - age) / 0.4f));
                if (a <= 4) continue;
                m.push();
                m.translate(d.x() - Gfx.cam.x, d.y() + age * 0.7 - Gfx.cam.y, d.z() - Gfx.cam.z);
                m.multiply(rot);
                float s = 0.03f * sc0 * pop;
                m.scale(-s, -s, s);
                Matrix4f mat = m.peek().getPositionMatrix();
                float wdt = mc.textRenderer.getWidth(d.text());
                mc.textRenderer.draw(d.text(), -wdt / 2f, 0f, Draw.a(Draw.lerp(Draw.YELLOW, Draw.ORANGE, age), a), true, mat, imm,
                        TextRenderer.TextLayerType.SEE_THROUGH, 0, 15728880);
                m.pop();
            }
            imm.draw();
        }
    }

    // ---- Target ESP styles ------------------------------------------------
    private static void orbitRing(MatrixStack.Entry e, VertexConsumer q, double cx, double cy, double cz, double[] u, double[] w, double[] n,
                                  double R, double a0, double sweep, int seg, double hw, Gfx.Col core, boolean glow) {
        Gfx.arcBand(e, q, cx, cy, cz, u, w, R, hw, a0, sweep, seg, core);
        Gfx.ribbon(e, q, cx, cy, cz, u, w, n, R, hw * 0.8, a0, sweep, seg, core);
        if (glow) {
            Gfx.arcBand(e, q, cx, cy, cz, u, w, R, hw * 3.4, a0, sweep, seg, tt -> Gfx.alpha(core.at(tt), 0.38f));
            Gfx.ribbon(e, q, cx, cy, cz, u, w, n, R, hw * 3.0, a0, sweep, seg, tt -> Gfx.alpha(core.at(tt), 0.30f));
            Gfx.arcBand(e, q, cx, cy, cz, u, w, R, hw * 7.0, a0, sweep, seg, tt -> Gfx.alpha(core.at(tt), 0.12f));
        }
    }

    private static void feetRing(MatrixStack.Entry e, VertexConsumer q, Vec3d p, double R, double t, double sp, int[] pl, boolean glow) {
        double spot = (t * 0.35 * sp) % 1.0;
        Gfx.Col col = tt -> {
            float i = (float) Math.pow(0.5 + 0.5 * Math.cos((tt - spot) * Math.PI * 2), 3);
            return Draw.a(Draw.lerp(pl[1], pl[0], i), (int) (80 + 170 * i));
        };
        double pulse = 1 + 0.04 * Math.sin(t * 3);
        Gfx.annulus(e, q, p.x, p.y + 0.03, p.z, R * pulse, 0.035, 72, col);
        if (glow) {
            Gfx.annulus(e, q, p.x, p.y + 0.03, p.z, R * pulse, 0.12, 72, tt -> Gfx.alpha(col.at(tt), 0.35f));
            Gfx.annulus(e, q, p.x, p.y + 0.03, p.z, R * pulse, 0.26, 72, tt -> Gfx.alpha(col.at(tt), 0.12f));
        }
    }

    /** 3D angel rings: tilted comet rings precessing around the torso + floor ring. */
    private static void espRings(MatrixStack.Entry e, VertexConsumer q, Vec3d p, double hh, double wd, double t, double sp, int[] pl, boolean gl, int rings) {
        double R = Math.max(0.55, wd / 2 + 0.42);
        for (int k = 0; k < rings; k++) {
            double dir = k % 2 == 0 ? 1 : -1;
            double tilt = Math.toRadians(24 + k * 8) * dir;
            double prec = t * 0.7 * sp * dir + k * 1.7;
            double[][] b = Gfx.basis(new double[]{Math.sin(tilt) * Math.cos(prec), Math.cos(tilt), Math.sin(tilt) * Math.sin(prec)});
            double a0 = t * sp * 2.6 * dir + k * 2.1;
            double cy = p.y + hh * 0.52 + Math.sin(t * sp * 1.3 + k) * hh * 0.12;
            Gfx.Col core = tt -> {
                float f = (float) Math.pow(tt, 1.6);
                return Draw.a(Draw.lerp(pl[1], pl[0], tt * tt), (int) (255 * f));
            };
            orbitRing(e, q, p.x, cy, p.z, b[0], b[1], b[2], R + k * 0.07, a0, dir * Math.toRadians(300), 96, 0.03, core, gl);
        }
        feetRing(e, q, p, R + 0.2, t, sp, pl, gl);
    }

    /** "Soul": glowing comet trails spiralling around the target (camera-facing smoky ribbons). */
    private static void espSoul(MatrixStack.Entry e, VertexConsumer q, Vec3d p, double hh, double wd, double t, double sp, int[] pl, boolean gl) {
        int souls = 3, N = 34;
        for (int k = 0; k < souls; k++) {
            double head = t * sp * 2.2 + k * 2.094;
            double R = wd / 2 + 0.5 + 0.1 * Math.sin(t * 1.7 + k);
            double[][] pts = new double[N + 1][3];
            double[] wdt = new double[N + 1];
            int[] cols = new int[N + 1];
            for (int i = 0; i <= N; i++) {
                double s = i / (double) N;
                double ang = head - (1 - s) * 2.1;
                pts[i][0] = p.x + Math.cos(ang) * R;
                pts[i][1] = p.y + hh * (0.55 + 0.32 * Math.sin(ang * 0.9 + k * 2.0));
                pts[i][2] = p.z + Math.sin(ang) * R;
                wdt[i] = 0.015 + 0.15 * Math.pow(s, 0.9);
                int base = s < 0.7 ? Draw.lerp(pl[2], pl[1], (float) (s / 0.7)) : Draw.lerp(pl[1], pl[0], (float) ((s - 0.7) / 0.3));
                cols[i] = Draw.a(base, (int) (255 * Math.pow(s, 1.25)));
            }
            Gfx.strip(e, q, pts, wdt, cols, crx, cry, crz, 0.55);
            if (gl) {
                int[] c2 = new int[N + 1], c3 = new int[N + 1];
                for (int i = 0; i <= N; i++) { c2[i] = Gfx.alpha(cols[i], 0.42f); c3[i] = Gfx.alpha(cols[i], 0.16f); }
                Gfx.strip(e, q, pts, wdt, c2, crx, cry, crz, 1.6);
                Gfx.strip(e, q, pts, wdt, c3, crx, cry, crz, 3.2);
            }
            double[] hp = pts[N];
            orb(e, q, hp[0], hp[1], hp[2], 0.13, Draw.a(pl[0], 235));
        }
    }

    private static void orb(MatrixStack.Entry e, VertexConsumer q, double x, double y, double z, double r, int col) {
        int edge = col & 0xFFFFFF;
        for (int i = 0; i < 10; i++) {
            double a0 = i * Math.PI / 5, a1 = (i + 1) * Math.PI / 5;
            Gfx.tri(e, q, x, y, z, col,
                    x + (crx * Math.cos(a0) + cux * Math.sin(a0)) * r, y + (cry * Math.cos(a0) + cuy * Math.sin(a0)) * r, z + (crz * Math.cos(a0) + cuz * Math.sin(a0)) * r, edge,
                    x + (crx * Math.cos(a1) + cux * Math.sin(a1)) * r, y + (cry * Math.cos(a1) + cuy * Math.sin(a1)) * r, z + (crz * Math.cos(a1) + cuz * Math.sin(a1)) * r, edge);
        }
    }

    /** Rotating square brackets around the target's body (Soup-style marker). */
    private static void espSquare(MatrixStack.Entry e, VertexConsumer q, Vec3d p, double hh, double t, double sp, int[] pl, boolean gl) {
        double cx = p.x, cy = p.y + hh * 0.55, cz = p.z;
        double s = Math.max(0.42, hh * 0.36) * (1 + 0.05 * Math.sin(t * 3));
        double rot = t * sp * 1.4;
        float pulse = (float) (0.5 + 0.5 * Math.sin(t * 4));
        int col = Draw.lerp(pl[1], pl[0], pulse);
        double[][] cs = new double[4][2];
        for (int i = 0; i < 4; i++) {
            double a = rot + Math.PI / 4 + i * Math.PI / 2;
            cs[i][0] = s * 1.4142 * Math.cos(a); cs[i][1] = s * 1.4142 * Math.sin(a);
        }
        int fill = Draw.a(pl[1], 26), fe = Draw.a(pl[1], 6);
        Gfx.quad(e, q, bx(cx, cs[0]), by(cy, cs[0]), bz(cz, cs[0]), fill, bx(cx, cs[1]), by(cy, cs[1]), bz(cz, cs[1]), fe,
                bx(cx, cs[2]), by(cy, cs[2]), bz(cz, cs[2]), fill, bx(cx, cs[3]), by(cy, cs[3]), bz(cz, cs[3]), fe);
        double[] widths = {0.022, 0.06, 0.13};
        float[] als = {1f, 0.34f, 0.12f};
        for (int layer = 0; layer < (gl ? 3 : 1); layer++) {
            int c = Gfx.alpha(col, als[layer]);
            for (int i = 0; i < 4; i++) {
                double[] a = cs[i], n = cs[(i + 1) % 4], pv = cs[(i + 3) % 4];
                arm(e, q, cx, cy, cz, a[0], a[1], a[0] + (n[0] - a[0]) * 0.32, a[1] + (n[1] - a[1]) * 0.32, widths[layer], c);
                arm(e, q, cx, cy, cz, a[0], a[1], a[0] + (pv[0] - a[0]) * 0.32, a[1] + (pv[1] - a[1]) * 0.32, widths[layer], c);
            }
        }
    }

    private static double bx(double cx, double[] c) { return cx + crx * c[0] + cux * c[1]; }
    private static double by(double cy, double[] c) { return cy + cry * c[0] + cuy * c[1]; }
    private static double bz(double cz, double[] c) { return cz + crz * c[0] + cuz * c[1]; }

    private static void arm(MatrixStack.Entry e, VertexConsumer q, double cx, double cy, double cz, double x0, double y0, double x1, double y1, double wd, int col) {
        double dx = x1 - x0, dy = y1 - y0, l = Math.hypot(dx, dy);
        if (l < 1e-6) return;
        double nx = -dy / l, ny = dx / l;
        double[][] pts = {{cx + crx * x0 + cux * y0, cy + cry * x0 + cuy * y0, cz + crz * x0 + cuz * y0},
                {cx + crx * x1 + cux * y1, cy + cry * x1 + cuy * y1, cz + crz * x1 + cuz * y1}};
        double px = (crx * nx + cux * ny) * wd, py = (cry * nx + cuy * ny) * wd, pz = (crz * nx + cuz * ny) * wd;
        Gfx.strip(e, q, pts, new double[]{1, 1}, new int[]{col, col}, px, py, pz, 1.0);
    }

    /** Crystals orbiting the target + floor ring. */
    private static void espCrystals(MatrixStack.Entry e, VertexConsumer q, Vec3d p, double hh, double wd, double t, double sp, int[] pl, boolean gl, int rings) {
        int n = 4 + rings;
        double R = Math.max(0.6, wd / 2 + 0.55);
        for (int i = 0; i < n; i++) {
            double a = t * sp * 1.3 + i * Math.PI * 2 / n;
            double x = p.x + Math.cos(a) * R, z = p.z + Math.sin(a) * R, y = p.y + hh * 0.5 + Math.sin(t * 1.6 + i * 1.3) * hh * 0.3;
            double wf = 0.25 + 0.75 * Math.abs(Math.cos(t * 2.2 + i));
            gem(e, q, x, y, z, 0.15, wf, Draw.a(pl[0], 245), Draw.a(pl[1], 230), Draw.a(pl[2], 220));
            if (gl) orb(e, q, x, y, z, 0.32, Draw.a(pl[1], 60));
        }
        feetRing(e, q, p, R + 0.1, t, sp, pl, gl);
    }

    private static void gem(MatrixStack.Entry e, VertexConsumer q, double x, double y, double z, double s, double wf, int light, int mid, int dark) {
        double w = s * 0.6 * wf;
        double tx = x + cux * s * 1.3, ty = y + cuy * s * 1.3, tz = z + cuz * s * 1.3;
        double bx = x - cux * s, by = y - cuy * s, bz = z - cuz * s;
        double lx = x - crx * w, ly = y - cry * w, lz = z - crz * w, rx = x + crx * w, ry = y + cry * w, rz = z + crz * w;
        Gfx.tri(e, q, tx, ty, tz, light, lx, ly, lz, mid, x, y, z, light);
        Gfx.tri(e, q, tx, ty, tz, mid, rx, ry, rz, dark, x, y, z, mid);
        Gfx.tri(e, q, bx, by, bz, dark, lx, ly, lz, mid, x, y, z, mid);
        Gfx.tri(e, q, bx, by, bz, dark, rx, ry, rz, dark, x, y, z, mid);
    }

    // ---- Halo / China Hat / Wings (local player only) --------------------
    private static void halo(MatrixStack.Entry e, VertexConsumer q, Vec3d pos, double top, double t) {
        Module hm = mod("Halo");
        double r = hm.num("Radius");
        double y = top + 0.30 + Math.sin(t * 2) * 0.025;
        double spot = (t * 0.45) % 1.0;
        Gfx.Col col = tt -> {
            float i = (float) Math.pow(0.5 + 0.5 * Math.cos((tt - spot) * Math.PI * 2), 4);
            return Draw.a(Draw.lerp(Draw.YELLOW, Draw.WHITE, i), (int) (170 + 85 * i));
        };
        Gfx.annulus(e, q, pos.x, y, pos.z, r, 0.035, 72, col);
        double[][] b = Gfx.basis(new double[]{0, 1, 0});
        Gfx.ribbon(e, q, pos.x, y, pos.z, b[0], b[1], b[2], r, 0.016, 0, Math.PI * 2, 72, col);
        if (hm.on("Glow")) {
            Gfx.annulus(e, q, pos.x, y, pos.z, r, 0.11, 72, tt -> Gfx.alpha(col.at(tt), 0.35f));
            Gfx.annulus(e, q, pos.x, y, pos.z, r, 0.24, 72, tt -> Gfx.alpha((col.at(tt) & 0xFF000000) | 0xFF9A2E, 0.13f));
        }
        if (hm.on("Crystals")) {
            for (int i = 0; i < 8; i++) {
                double a = t * 0.8 + i * Math.PI / 4;
                gem(e, q, pos.x + Math.cos(a) * r, y + 0.02, pos.z + Math.sin(a) * r, 0.05, 0.3 + 0.7 * Math.abs(Math.cos(t * 2 + i)),
                        Draw.a(Draw.WHITE, 245), Draw.a(Draw.YELLOW, 230), Draw.a(Draw.ORANGE, 220));
            }
        }
    }

    private static void chinaHat(MatrixStack.Entry e, VertexConsumer q, Vec3d pos, double top, double t) {
        double r = mod("China Hat").num("Radius");
        double base = top - 0.04, apex = base + 0.34;
        int n = 40;
        int cApex = Draw.a(Draw.WHITE, 235);
        for (int i = 0; i < n; i++) {
            double a0 = i * Math.PI * 2 / n + t * 0.6, a1 = (i + 1) * Math.PI * 2 / n + t * 0.6;
            float sh = (float) (0.5 + 0.5 * Math.sin(t * 2.4 + i * 0.55));
            int cb = Draw.a(Draw.lerp(Draw.ORANGE, Draw.YELLOW, sh), (int) (150 + 60 * sh));
            Gfx.tri(e, q, pos.x, apex, pos.z, cApex, pos.x + Math.cos(a0) * r, base, pos.z + Math.sin(a0) * r, cb,
                    pos.x + Math.cos(a1) * r, base, pos.z + Math.sin(a1) * r, cb);
        }
        double spot = (t * 0.5) % 1.0;
        Gfx.Col rim = tt -> {
            float i = (float) Math.pow(0.5 + 0.5 * Math.cos((tt - spot) * Math.PI * 2), 3);
            return Draw.a(Draw.lerp(Draw.YELLOW, Draw.WHITE, i), (int) (160 + 95 * i));
        };
        Gfx.annulus(e, q, pos.x, base, pos.z, r, 0.04, 72, rim);
        Gfx.annulus(e, q, pos.x, base, pos.z, r, 0.14, 72, tt -> Gfx.alpha(rim.at(tt), 0.3f));
    }

    /** Golden angel wings (cosmetic) - flapping feathers with gradient + glow. */
    private static void wings(MatrixStack.Entry e, VertexConsumer q, float td, double t) {
        PlayerEntity p = mc.player;
        Vec3d pos = p.getLerpedPos(td);
        double th = Math.toRadians(MathHelper.lerpAngleDegrees(td, p.prevBodyYaw, p.bodyYaw));
        double fx = -Math.sin(th), fz = Math.cos(th), lx = Math.cos(th), lz = Math.sin(th);
        double by = pos.y + (p.isSneaking() ? 1.1 : 1.32), bx0 = pos.x - fx * 0.2, bz0 = pos.z - fz * 0.2;
        double sz = mod("Cosmetics").num("Wing Size");
        double flap = Math.sin(t * 2.4) * Math.toRadians(11);
        int n = 8;
        for (int side = -1; side <= 1; side += 2) {
            for (int k = 0; k < n; k++) {
                double f = k / (double) (n - 1);
                double ang = Math.toRadians(78 - f * 118) + flap * (1 - 0.4 * f);
                double len = (0.75 + 0.55 * Math.sin(Math.min(1.0, f * 1.05) * Math.PI * 0.95)) * sz;
                double dl = Math.cos(ang) * len, du = Math.sin(ang) * len, db = (0.10 + 0.22 * f) * sz;
                double rx = bx0 + lx * side * 0.10, ry = by - 0.02 * f, rz = bz0 + lz * side * 0.10;
                double tx = rx + lx * side * dl - fx * db, ty = ry + du, tz = rz + lz * side * dl - fz * db;
                double wx = lx * side * -Math.sin(ang), wy = Math.cos(ang), wz = lz * side * -Math.sin(ang);
                double w0 = 0.02 * sz, w1 = 0.075 * sz * (0.8 + 0.4 * f);
                int cr = Draw.a(Draw.WHITE, 225), ct = Draw.a(Draw.lerp(Draw.YELLOW, Draw.ORANGE, (float) f), 70);
                Gfx.quad(e, q, rx - wx * w0, ry - wy * w0, rz - wz * w0, cr, rx + wx * w0, ry + wy * w0, rz + wz * w0, cr,
                        tx + wx * w1, ty + wy * w1, tz + wz * w1, ct, tx - wx * w1, ty - wy * w1, tz - wz * w1, ct);
                Gfx.quad(e, q, rx - wx * w0 * 2, ry - wy * w0 * 2, rz - wz * w0 * 2, Draw.a(Draw.YELLOW, 40), rx + wx * w0 * 2, ry + wy * w0 * 2, rz + wz * w0 * 2, Draw.a(Draw.YELLOW, 40),
                        tx + wx * w1 * 2.2, ty + wy * w1 * 2.2, tz + wz * w1 * 2.2, Draw.a(Draw.ORANGE, 8), tx - wx * w1 * 2.2, ty - wy * w1 * 2.2, tz - wz * w1 * 2.2, Draw.a(Draw.ORANGE, 8));
            }
        }
    }
}
