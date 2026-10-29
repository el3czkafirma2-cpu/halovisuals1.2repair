package pl.halovisuals;

import net.minecraft.client.render.Camera;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.util.math.MatrixStack;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import pl.halovisuals.gui.Draw;

import java.util.ArrayList;
import java.util.Random;

/**
 * Custom particle engine (replaces vanilla particles): billboarded, glowing, smoothly fading dust / stars / petals / bubbles.
 * Simulated per tick, rendered per frame with interpolation.
 */
public final class Particles {
    public static final int ANGEL_DUST = 0, SPARKS = 1, PETALS = 2, CRYSTAL = 3, SNOW = 4, BLOOD = 5, MIXED = 6;
    public static final int T_DUST = 0, T_STAR = 1, T_PETAL = 2, T_BUBBLE = 3, T_CRYSTAL = 4, T_SNOW = 5;
    public static final int GOLD = 0xFFFFD84D, ORANGE = 0xFFFF8A1F, WHITE = 0xFFFFFFFF;

    private static final class P {
        double x, y, z, px, py, pz, vx, vy, vz;
        float life, age, size, spin, spinV, seed, grav, drag;
        int type, c1, c2;
    }

    private static final ArrayList<P> list = new ArrayList<>();
    private static final Random R = new Random();
    public static int max = 1500;
    public static boolean glow = true;
    private static double rx, ry, rz, ux, uy, uz;

    private Particles() {}

    public static int count() { return list.size(); }
    public static void clear() { list.clear(); }
    private static double rr() { return R.nextDouble() - 0.5; }

    public static void add(int type, double x, double y, double z, double vx, double vy, double vz,
                           int life, float size, int c1, int c2, float grav, float drag) {
        if (list.size() >= max) return;
        P p = new P();
        p.type = type; p.x = p.px = x; p.y = p.py = y; p.z = p.pz = z; p.vx = vx; p.vy = vy; p.vz = vz;
        p.life = life; p.size = size; p.c1 = c1; p.c2 = c2; p.grav = grav; p.drag = drag;
        p.seed = R.nextFloat() * 100f; p.spin = R.nextFloat() * 6.28f; p.spinV = (float) rr() * 0.3f;
        list.add(p);
    }

    public static void spawn(int style, double x, double y, double z, double sxz, double sy, double speed, int count) {
        for (int i = 0; i < count; i++) {
            if (list.size() >= max) return;
            double px = x + rr() * sxz, py = y + rr() * sy, pz = z + rr() * sxz;
            double dx = rr(), dy = rr(), dz = rr();
            double dl = Math.max(0.001, Math.sqrt(dx * dx + dy * dy + dz * dz));
            dx /= dl; dy /= dl; dz /= dl;
            int st = style == MIXED ? R.nextInt(5) : style;
            switch (st) {
                case CRYSTAL -> {
                    double sp = speed * (0.3 + R.nextDouble() * 0.5);
                    add(T_CRYSTAL, px, py, pz, dx * sp, dy * sp * 0.6 + 0.01, dz * sp, 24 + R.nextInt(26), 0.09f + R.nextFloat() * 0.09f, 0xFFFFF1B8, 0xFFFFB347, 0.0012f, 0.93f);
                }
                case SNOW -> add(T_SNOW, px, py, pz, rr() * speed * 0.4, -0.004, rr() * speed * 0.4,
                        50 + R.nextInt(40), 0.10f + R.nextFloat() * 0.08f, 0xFFDFF3FF, 0xFF8FD3FF, 0.0015f, 0.97f);
                case SPARKS -> {
                    double sp = speed * (0.5 + R.nextDouble());
                    add(R.nextFloat() < 0.8f ? T_STAR : T_DUST, px, py, pz, dx * sp, dy * sp + 0.02, dz * sp,
                            12 + R.nextInt(14), 0.10f + R.nextFloat() * 0.12f, WHITE, R.nextBoolean() ? GOLD : ORANGE, 0.004f, 0.88f);
                }
                case PETALS -> add(T_PETAL, px, py, pz, rr() * speed * 0.5, -0.005, rr() * speed * 0.5,
                        40 + R.nextInt(35), 0.10f + R.nextFloat() * 0.07f, GOLD, ORANGE, 0.0035f, 0.97f);
                case BLOOD -> {
                    double sp = speed * (0.4 + R.nextDouble());
                    add(R.nextFloat() < 0.6f ? T_STAR : T_DUST, px, py, pz, dx * sp, dy * sp + 0.03, dz * sp,
                            14 + R.nextInt(14), 0.09f + R.nextFloat() * 0.10f, 0xFFFF4A5A, 0xFFB0101C, 0.006f, 0.9f);
                }
                default -> add(R.nextFloat() < 0.7f ? T_DUST : T_STAR, px, py, pz, dx * speed * 0.4, 0.01 + R.nextDouble() * 0.03, dz * speed * 0.4,
                        28 + R.nextInt(30), 0.07f + R.nextFloat() * 0.08f, WHITE, GOLD, -0.0006f, 0.96f);
            }
        }
    }

    public static void tick() {
        for (int i = list.size() - 1; i >= 0; i--) {
            P p = list.get(i);
            p.px = p.x; p.py = p.y; p.pz = p.z;
            p.vx *= p.drag; p.vy *= p.drag; p.vz *= p.drag;
            p.vy -= p.grav;
            double sway = Math.sin((p.age + p.seed) * 0.3) * (p.type == T_PETAL ? 0.004 : 0.0012);
            p.vx += sway; p.vz += Math.cos((p.age + p.seed) * 0.27) * (p.type == T_PETAL ? 0.004 : 0.0012);
            p.x += p.vx; p.y += p.vy; p.z += p.vz;
            p.spin += p.spinV;
            if (++p.age >= p.life) { list.set(i, list.get(list.size() - 1)); list.remove(list.size() - 1); }
        }
    }

    private static double ox(double a, double r) { return (rx * Math.cos(a) + ux * Math.sin(a)) * r; }
    private static double oy(double a, double r) { return (ry * Math.cos(a) + uy * Math.sin(a)) * r; }
    private static double oz(double a, double r) { return (rz * Math.cos(a) + uz * Math.sin(a)) * r; }

    private static int al(int c, float a) { return Draw.a(c, (int) (255 * Math.max(0f, Math.min(1f, a)))); }

    public static void render(MatrixStack.Entry e, VertexConsumer vc, Camera camera, float td) {
        Quaternionf q = camera.getRotation();
        Vector3f r = new Vector3f(1, 0, 0).rotate(q), u = new Vector3f(0, 1, 0).rotate(q);
        rx = r.x; ry = r.y; rz = r.z; ux = u.x; uy = u.y; uz = u.z;

        for (P p : list) {
            float t = p.age + td, f = t / p.life;
            float fi = Math.min(1f, f / 0.15f), fo = Math.min(1f, (1f - f) / 0.45f);
            float a = Math.max(0f, Math.min(1f, fi * fo));
            a = a * a * (3 - 2 * a);
            if (a < 0.01f) continue;
            double x = p.px + (p.x - p.px) * td, y = p.py + (p.y - p.py) * td, z = p.pz + (p.z - p.pz) * td;

            switch (p.type) {
                case T_STAR -> {
                    float s = p.size * (0.75f + 0.25f * (float) Math.sin(t * 0.8f + p.seed)) * (0.4f + 0.6f * Math.min(1f, f / 0.2f));
                    if (glow) disc(e, vc, x, y, z, s * 2.6f, al(p.c2, 0.30f * a), al(p.c2, 0f), 10, 0);
                    drawStar(e, vc, x, y, z, s, p.spin + t * 0.05f, al(WHITE, a), al(p.c2, 0.85f * a), al(p.c1, 0.6f * a));
                }
                case T_PETAL -> {
                    float s = p.size;
                    double ang = p.spin + t * 0.12;
                    double wdt = s * (0.18 + 0.38 * Math.abs(Math.cos(t * 0.22 + p.seed)));
                    double ax = x + ox(ang + Math.PI / 2, s), ay = y + oy(ang + Math.PI / 2, s), az = z + oz(ang + Math.PI / 2, s);
                    double bx = x + ox(ang - Math.PI / 2, s * 0.6), by = y + oy(ang - Math.PI / 2, s * 0.6), bz = z + oz(ang - Math.PI / 2, s * 0.6);
                    double lx = x + ox(ang, wdt), ly = y + oy(ang, wdt), lz = z + oz(ang, wdt);
                    double mx = x + ox(ang + Math.PI, wdt), my = y + oy(ang + Math.PI, wdt), mz = z + oz(ang + Math.PI, wdt);
                    Gfx.quad(e, vc, bx, by, bz, al(p.c2, a), lx, ly, lz, al(p.c1, a), ax, ay, az, al(0xFFFFF4C2, 0.9f * a), mx, my, mz, al(p.c1, a));
                    if (glow) disc(e, vc, x, y, z, s * 1.8f, al(p.c1, 0.16f * a), al(p.c1, 0f), 8, 0);
                }
                case T_BUBBLE -> {
                    float s = p.size * (0.5f + 0.5f * Math.min(1f, f / 0.3f));
                    for (int i = 0; i < 14; i++) {
                        double a0 = i / 14.0 * Math.PI * 2, a1 = (i + 1) / 14.0 * Math.PI * 2;
                        int mid = al(WHITE, 0.75f * a), edge = al(p.c1, 0f);
                        Gfx.quad(e, vc, x + ox(a0, s * 0.82), y + oy(a0, s * 0.82), z + oz(a0, s * 0.82), edge,
                                x + ox(a0, s), y + oy(a0, s), z + oz(a0, s), mid,
                                x + ox(a1, s), y + oy(a1, s), z + oz(a1, s), mid,
                                x + ox(a1, s * 0.82), y + oy(a1, s * 0.82), z + oz(a1, s * 0.82), edge);
                        Gfx.quad(e, vc, x + ox(a0, s), y + oy(a0, s), z + oz(a0, s), mid,
                                x + ox(a0, s * 1.25), y + oy(a0, s * 1.25), z + oz(a0, s * 1.25), edge,
                                x + ox(a1, s * 1.25), y + oy(a1, s * 1.25), z + oz(a1, s * 1.25), edge,
                                x + ox(a1, s), y + oy(a1, s), z + oz(a1, s), mid);
                    }
                }
                case T_CRYSTAL -> {
                    float s = p.size;
                    double wd = s * (0.25 + 0.55 * Math.abs(Math.cos(t * 0.12 + p.seed)));
                    double tx = x + ox(Math.PI / 2, s * 1.2), ty = y + oy(Math.PI / 2, s * 1.2), tz = z + oz(Math.PI / 2, s * 1.2);
                    double bx = x + ox(-Math.PI / 2, s), by = y + oy(-Math.PI / 2, s), bz = z + oz(-Math.PI / 2, s);
                    double lx = x + ox(Math.PI, wd), ly = y + oy(Math.PI, wd), lz = z + oz(Math.PI, wd);
                    double rx2 = x + ox(0, wd), ry2 = y + oy(0, wd), rz2 = z + oz(0, wd);
                    int light = al(WHITE, a), mid = al(p.c1, 0.9f * a), dark = al(p.c2, 0.85f * a);
                    Gfx.tri(e, vc, tx, ty, tz, light, lx, ly, lz, mid, x, y, z, light);
                    Gfx.tri(e, vc, tx, ty, tz, mid, rx2, ry2, rz2, dark, x, y, z, mid);
                    Gfx.tri(e, vc, bx, by, bz, dark, lx, ly, lz, mid, x, y, z, mid);
                    Gfx.tri(e, vc, bx, by, bz, dark, rx2, ry2, rz2, dark, x, y, z, mid);
                    if (glow) disc(e, vc, x, y, z, s * 2.3f, al(p.c2, 0.22f * a), al(p.c2, 0f), 8, 0);
                    if (Math.sin(t * 0.6 + p.seed) > 0.8) drawStar(e, vc, x, y, z, s * 0.9f, p.spin, al(WHITE, a), al(WHITE, 0.6f * a), al(WHITE, 0.3f * a));
                }
                case T_SNOW -> {
                    float s = p.size;
                    for (int k = 0; k < 3; k++) {
                        double ang = p.spin + t * 0.04 + k * Math.PI / 3, wd = s * 0.13;
                        Gfx.quad(e, vc, x + ox(ang, s), y + oy(ang, s), z + oz(ang, s), al(p.c2, 0.7f * a),
                                x + ox(ang + Math.PI / 2, wd), y + oy(ang + Math.PI / 2, wd), z + oz(ang + Math.PI / 2, wd), al(WHITE, a),
                                x + ox(ang + Math.PI, s), y + oy(ang + Math.PI, s), z + oz(ang + Math.PI, s), al(p.c2, 0.7f * a),
                                x + ox(ang - Math.PI / 2, wd), y + oy(ang - Math.PI / 2, wd), z + oz(ang - Math.PI / 2, wd), al(WHITE, a));
                    }
                    if (glow) disc(e, vc, x, y, z, s * 1.8f, al(p.c2, 0.18f * a), al(p.c2, 0f), 8, 0);
                }
                default -> { // soft glowing dust
                    float s = p.size * (0.6f + 0.4f * (float) Math.sin(Math.min(1f, f) * Math.PI));
                    if (glow) disc(e, vc, x, y, z, s * 2.4f, al(p.c2, 0.28f * a), al(p.c2, 0f), 8, 0);
                    disc(e, vc, x, y, z, s, al(WHITE, a), al(p.c1, 0.0f), 8, 0);
                }
            }
        }
    }

    private static void disc(MatrixStack.Entry e, VertexConsumer vc, double x, double y, double z, float rad, int cc, int ce, int seg, float rot) {
        for (int i = 0; i < seg; i++) {
            double a0 = rot + i * Math.PI * 2 / seg, a1 = rot + (i + 1) * Math.PI * 2 / seg;
            Gfx.tri(e, vc, x, y, z, cc, x + ox(a0, rad), y + oy(a0, rad), z + oz(a0, rad), ce, x + ox(a1, rad), y + oy(a1, rad), z + oz(a1, rad), ce);
        }
    }

    private static void drawStar(MatrixStack.Entry e, VertexConsumer vc, double x, double y, double z, float s, float rot, int cCenter, int cTip, int cMid) {
        float in = s * 0.24f;
        for (int i = 0; i < 4; i++) {
            double a = rot + i * Math.PI / 2;
            Gfx.tri(e, vc, x, y, z, cCenter,
                    x + ox(a, s), y + oy(a, s), z + oz(a, s), cTip,
                    x + ox(a + Math.PI / 4, in), y + oy(a + Math.PI / 4, in), z + oz(a + Math.PI / 4, in), cMid);
            Gfx.tri(e, vc, x, y, z, cCenter,
                    x + ox(a + Math.PI / 4, in), y + oy(a + Math.PI / 4, in), z + oz(a + Math.PI / 4, in), cMid,
                    x + ox(a + Math.PI / 2, s), y + oy(a + Math.PI / 2, s), z + oz(a + Math.PI / 2, s), cTip);
        }
    }
}
