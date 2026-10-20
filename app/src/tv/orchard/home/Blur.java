package tv.orchard.home;

/**
 * Stack blur on an int[] ARGB array, in place. Pure Java (no android.*), so host tests can run it.
 *
 * Each pixel becomes a triangular-weighted average of its neighbours: weight (r + 1 - |d|) at distance d,
 * divided by (r + 1)^2, edges clamped. That is what the classic stack blur computes; it is done here with
 * two running sums per channel (the falling half and the rising half of the triangle) in a horizontal then
 * a vertical pass. Memory: one int[max(w, h)] line copy.
 */
public final class Blur {
    private Blur() {}

    public static final int MAX_RADIUS = 254;

    public static void stack(int[] pixels, int w, int h, int radius) {
        if (pixels == null || radius < 1 || w < 1 || h < 1 || pixels.length < w * h) return;
        int r = Math.min(radius, MAX_RADIUS);
        int[] line = new int[Math.max(w, h)];
        for (int y = 0; y < h; y++) pass(pixels, y * w, 1, w, r, line);
        for (int x = 0; x < w; x++) pass(pixels, x, w, h, r, line);
    }

    private static int clamp(int i, int n) {
        return i < 0 ? 0 : (i >= n ? n - 1 : i);
    }

    /** One line of n pixels starting at off with the given stride. */
    private static void pass(int[] px, int off, int stride, int n, int r, int[] line) {
        for (int i = 0; i < n; i++) line[i] = px[off + i * stride];
        final int div = (r + 1) * (r + 1);
        final int half = div >> 1;
        // channel loop: shift 24 = alpha, 16 = red, 8 = green, 0 = blue
        for (int shift = 24; shift >= 0; shift -= 8) {
            // window sums at x = 0: a = sum p[-r..0], b = sum p[1..r+1], s = triangular weighted sum
            int a = 0, b = 0, s = 0;
            for (int d = -r; d <= 0; d++) a += (line[clamp(d, n)] >>> shift) & 0xFF;
            for (int d = 1; d <= r + 1; d++) b += (line[clamp(d, n)] >>> shift) & 0xFF;
            for (int d = -r; d <= r; d++) s += (r + 1 - Math.abs(d)) * ((line[clamp(d, n)] >>> shift) & 0xFF);
            for (int x = 0; x < n; x++) {
                int idx = off + x * stride;
                int v = (s + half) / div;
                px[idx] = (px[idx] & ~(0xFF << shift)) | (v << shift);
                // advance x -> x + 1
                s += b - a;
                a += ((line[clamp(x + 1, n)] >>> shift) & 0xFF) - ((line[clamp(x - r, n)] >>> shift) & 0xFF);
                b += ((line[clamp(x + r + 2, n)] >>> shift) & 0xFF) - ((line[clamp(x + 1, n)] >>> shift) & 0xFF);
            }
        }
    }
}
