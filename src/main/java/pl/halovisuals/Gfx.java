package pl.halovisuals;

import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.Vec3d;

/** Low-level helpers for smooth, glowing 3D geometry (vertex-colour gradients on translucent quads). */
public final class Gfx {
    public static Vec3d cam = Vec3d.ZERO;

    public interface Col { int at(float t); }

    private Gfx() {}

    public static int alpha(int c, float f) {
        int a = (int) (((c >>> 24) & 255) * Math.max(0f, Math.min(1f, f)));
        return (a << 24) | (c & 0xFFFFFF);
    }

    private static void v(MatrixStack.Entry e, VertexConsumer vc, double x, double y, double z, int c) {
        vc.vertex(e, (float) (x - cam.x), (float) (y - cam.y), (float) (z - cam.z)).color(c);
    }

    public static void tri(MatrixStack.Entry e, VertexConsumer vc,
                           double ax, double ay, double az, int ca,
                           double bx, double by, double bz, int cb,
                           double cx, double cy, double cz, int cc) {
        v(e, vc, ax, ay, az, ca); v(e, vc, bx, by, bz, cb); v(e, vc, cx, cy, cz, cc); v(e, vc, cx, cy, cz, cc);
    }

    public static void quad(MatrixStack.Entry e, VertexConsumer vc,
                            double ax, double ay, double az, int ca,
                            double bx, double by, double bz, int cb,
                            double cx, double cy, double cz, int cc,
                            double dx, double dy, double dz, int cd) {
        v(e, vc, ax, ay, az, ca); v(e, vc, bx, by, bz, cb); v(e, vc, cx, cy, cz, cc); v(e, vc, dx, dy, dz, cd);
    }

    public static void line(MatrixStack.Entry e, VertexConsumer vc, double x0, double y0, double z0, double x1, double y1, double z1, int argb) {
        float dx = (float) (x1 - x0), dy = (float) (y1 - y0), dz = (float) (z1 - z0);
        float len = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len == 0) return;
        dx /= len; dy /= len; dz /= len;
        vc.vertex(e, (float) (x0 - cam.x), (float) (y0 - cam.y), (float) (z0 - cam.z)).color(argb).normal(e, dx, dy, dz);
        vc.vertex(e, (float) (x1 - cam.x), (float) (y1 - cam.y), (float) (z1 - cam.z)).color(argb).normal(e, dx, dy, dz);
    }

    public static void boxLines(MatrixStack.Entry e, VertexConsumer vc, double x0, double y0, double z0, double x1, double y1, double z1, int c) {
        line(e, vc, x0, y0, z0, x1, y0, z0, c); line(e, vc, x1, y0, z0, x1, y0, z1, c);
        line(e, vc, x1, y0, z1, x0, y0, z1, c); line(e, vc, x0, y0, z1, x0, y0, z0, c);
        line(e, vc, x0, y1, z0, x1, y1, z0, c); line(e, vc, x1, y1, z0, x1, y1, z1, c);
        line(e, vc, x1, y1, z1, x0, y1, z1, c); line(e, vc, x0, y1, z1, x0, y1, z0, c);
        line(e, vc, x0, y0, z0, x0, y1, z0, c); line(e, vc, x1, y0, z0, x1, y1, z0, c);
        line(e, vc, x1, y0, z1, x1, y1, z1, c); line(e, vc, x0, y0, z1, x0, y1, z1, c);
    }

    public static void boxFaces(MatrixStack.Entry e, VertexConsumer vc, double x0, double y0, double z0, double x1, double y1, double z1, int c) {
        quad(e, vc, x0, y0, z0, c, x1, y0, z0, c, x1, y0, z1, c, x0, y0, z1, c);
        quad(e, vc, x0, y1, z0, c, x1, y1, z0, c, x1, y1, z1, c, x0, y1, z1, c);
        quad(e, vc, x0, y0, z0, c, x1, y0, z0, c, x1, y1, z0, c, x0, y1, z0, c);
        quad(e, vc, x0, y0, z1, c, x1, y0, z1, c, x1, y1, z1, c, x0, y1, z1, c);
        quad(e, vc, x0, y0, z0, c, x0, y0, z1, c, x0, y1, z1, c, x0, y1, z0, c);
        quad(e, vc, x1, y0, z0, c, x1, y0, z1, c, x1, y1, z1, c, x1, y1, z0, c);
    }

    /** Orthonormal basis (u, w) of the plane whose normal is n. */
    public static double[][] basis(double[] n) {
        double l = Math.sqrt(n[0] * n[0] + n[1] * n[1] + n[2] * n[2]);
        double[] nn = {n[0] / l, n[1] / l, n[2] / l};
        double[] h = Math.abs(nn[1]) < 0.9 ? new double[]{0, 1, 0} : new double[]{1, 0, 0};
        double[] u = {h[1] * nn[2] - h[2] * nn[1], h[2] * nn[0] - h[0] * nn[2], h[0] * nn[1] - h[1] * nn[0]};
        double ul = Math.sqrt(u[0] * u[0] + u[1] * u[1] + u[2] * u[2]);
        u[0] /= ul; u[1] /= ul; u[2] /= ul;
        double[] w = {nn[1] * u[2] - nn[2] * u[1], nn[2] * u[0] - nn[0] * u[2], nn[0] * u[1] - nn[1] * u[0]};
        return new double[][]{u, w, nn};
    }

    /** Soft band (core alpha in the middle, 0 at both edges) following an arc/circle in an arbitrary plane. */
    public static void ribbon(MatrixStack.Entry e, VertexConsumer vc, double cx, double cy, double cz,
                              double[] u, double[] w, double[] n, double R, double hw,
                              double a0, double sweep, int seg, Col col) {
        for (int i = 0; i < seg; i++) {
            double t0 = i / (double) seg, t1 = (i + 1) / (double) seg;
            double g0 = a0 + sweep * t0, g1 = a0 + sweep * t1;
            double c0 = Math.cos(g0), s0 = Math.sin(g0), c1 = Math.cos(g1), s1 = Math.sin(g1);
            double x0 = cx + R * (c0 * u[0] + s0 * w[0]), y0 = cy + R * (c0 * u[1] + s0 * w[1]), z0 = cz + R * (c0 * u[2] + s0 * w[2]);
            double x1 = cx + R * (c1 * u[0] + s1 * w[0]), y1 = cy + R * (c1 * u[1] + s1 * w[1]), z1 = cz + R * (c1 * u[2] + s1 * w[2]);
            int k0 = col.at((float) t0), k1 = col.at((float) t1);
            int e0 = k0 & 0xFFFFFF, e1 = k1 & 0xFFFFFF;
            double nx = n[0] * hw, ny = n[1] * hw, nz = n[2] * hw;
            quad(e, vc, x0 + nx, y0 + ny, z0 + nz, e0, x0, y0, z0, k0, x1, y1, z1, k1, x1 + nx, y1 + ny, z1 + nz, e1);
            quad(e, vc, x0, y0, z0, k0, x0 - nx, y0 - ny, z0 - nz, e0, x1 - nx, y1 - ny, z1 - nz, e1, x1, y1, z1, k1);
        }
    }

    /** Ribbon with 2 extra translucent layers = soft smoky glow. */
    public static void glowRibbon(MatrixStack.Entry e, VertexConsumer vc, double cx, double cy, double cz,
                                  double[] u, double[] w, double[] n, double R, double a0, double sweep, int seg,
                                  double hw, Col core, boolean glow) {
        ribbon(e, vc, cx, cy, cz, u, w, n, R, hw, a0, sweep, seg, core);
        if (glow) {
            ribbon(e, vc, cx, cy, cz, u, w, n, R, hw * 3.2, a0, sweep, seg, t -> alpha(core.at(t), 0.40f));
            ribbon(e, vc, cx, cy, cz, u, w, n, R, hw * 7.0, a0, sweep, seg, t -> alpha((core.at(t) & 0xFF000000) | 0xFF9A2E, 0.16f));
        }
    }

    /** Soft camera-facing strip along a polyline (r = width direction); centre alpha full, edges 0. */
    public static void strip(MatrixStack.Entry e, VertexConsumer vc, double[][] p, double[] w, int[] c, double rx, double ry, double rz, double mul) {
        for (int i = 0; i < p.length - 1; i++) {
            double w0 = w[i] * mul, w1 = w[i + 1] * mul;
            int e0 = c[i] & 0xFFFFFF, e1 = c[i + 1] & 0xFFFFFF;
            double ax = p[i][0], ay = p[i][1], az = p[i][2], bx = p[i + 1][0], by = p[i + 1][1], bz = p[i + 1][2];
            quad(e, vc, ax + rx * w0, ay + ry * w0, az + rz * w0, e0, ax, ay, az, c[i], bx, by, bz, c[i + 1], bx + rx * w1, by + ry * w1, bz + rz * w1, e1);
            quad(e, vc, ax, ay, az, c[i], ax - rx * w0, ay - ry * w0, az - rz * w0, e0, bx - rx * w1, by - ry * w1, bz - rz * w1, e1, bx, by, bz, c[i + 1]);
        }
    }

    /** Arc with radial width (+-hw in-plane) and soft edges; plane given by basis u,w. */
    public static void arcBand(MatrixStack.Entry e, VertexConsumer vc, double cx, double cy, double cz, double[] u, double[] w,
                               double R, double hw, double a0, double sweep, int seg, Col col) {
        for (int i = 0; i < seg; i++) {
            double t0 = i / (double) seg, t1 = (i + 1) / (double) seg;
            double g0 = a0 + sweep * t0, g1 = a0 + sweep * t1;
            double c0 = Math.cos(g0), s0 = Math.sin(g0), c1 = Math.cos(g1), s1 = Math.sin(g1);
            int k0 = col.at((float) t0), k1 = col.at((float) t1), e0 = k0 & 0xFFFFFF, e1 = k1 & 0xFFFFFF;
            double ri = R - hw, ro = R + hw;
            quad(e, vc, cx + ri * (c0 * u[0] + s0 * w[0]), cy + ri * (c0 * u[1] + s0 * w[1]), cz + ri * (c0 * u[2] + s0 * w[2]), e0,
                    cx + R * (c0 * u[0] + s0 * w[0]), cy + R * (c0 * u[1] + s0 * w[1]), cz + R * (c0 * u[2] + s0 * w[2]), k0,
                    cx + R * (c1 * u[0] + s1 * w[0]), cy + R * (c1 * u[1] + s1 * w[1]), cz + R * (c1 * u[2] + s1 * w[2]), k1,
                    cx + ri * (c1 * u[0] + s1 * w[0]), cy + ri * (c1 * u[1] + s1 * w[1]), cz + ri * (c1 * u[2] + s1 * w[2]), e1);
            quad(e, vc, cx + R * (c0 * u[0] + s0 * w[0]), cy + R * (c0 * u[1] + s0 * w[1]), cz + R * (c0 * u[2] + s0 * w[2]), k0,
                    cx + ro * (c0 * u[0] + s0 * w[0]), cy + ro * (c0 * u[1] + s0 * w[1]), cz + ro * (c0 * u[2] + s0 * w[2]), e0,
                    cx + ro * (c1 * u[0] + s1 * w[0]), cy + ro * (c1 * u[1] + s1 * w[1]), cz + ro * (c1 * u[2] + s1 * w[2]), e1,
                    cx + R * (c1 * u[0] + s1 * w[0]), cy + R * (c1 * u[1] + s1 * w[1]), cz + R * (c1 * u[2] + s1 * w[2]), k1);
        }
    }

    /** Flat horizontal ring, radial width +-hw, soft edges. */
    public static void annulus(MatrixStack.Entry e, VertexConsumer vc, double cx, double cy, double cz, double R, double hw, int seg, Col col) {
        for (int i = 0; i < seg; i++) {
            double t0 = i / (double) seg, t1 = (i + 1) / (double) seg;
            double a0 = t0 * Math.PI * 2, a1 = t1 * Math.PI * 2;
            double c0 = Math.cos(a0), s0 = Math.sin(a0), c1 = Math.cos(a1), s1 = Math.sin(a1);
            int k0 = col.at((float) t0), k1 = col.at((float) t1), e0 = k0 & 0xFFFFFF, e1 = k1 & 0xFFFFFF;
            double ri = R - hw, ro = R + hw;
            quad(e, vc, cx + c0 * ri, cy, cz + s0 * ri, e0, cx + c0 * R, cy, cz + s0 * R, k0, cx + c1 * R, cy, cz + s1 * R, k1, cx + c1 * ri, cy, cz + s1 * ri, e1);
            quad(e, vc, cx + c0 * R, cy, cz + s0 * R, k0, cx + c0 * ro, cy, cz + s0 * ro, e0, cx + c1 * ro, cy, cz + s1 * ro, e1, cx + c1 * R, cy, cz + s1 * R, k1);
        }
    }
}
