package tv.orchard.home;

/**
 * NOAA solar calculator (Meeus), zenith 90.833 deg: sunrise, sunset, solar noon,
 * and refraction-corrected elevation / azimuth. Line-for-line port of the mockup's
 * Sun object (mockup/mock.js).
 *
 * PURE JAVA (no android.* imports; host-tested)
 */
public final class Solar {
    private Solar() {}

    public static final int POLAR_NONE = 0, POLAR_DAY = 1, POLAR_NIGHT = 2;

    private static final double R = Math.PI / 180, D = 180 / Math.PI;

    /** Minutes after local midnight; rise and set are NaN when polar; noon is always set. */
    public static final class Times {
        public final double rise, set, noon;
        public final int polar;

        Times(double rise, double set, double noon, int polar) {
            this.rise = rise;
            this.set = set;
            this.noon = noon;
            this.polar = polar;
        }
    }

    /** Refraction-corrected elevation (deg) and azimuth (deg clockwise from north). */
    public static final class Pos {
        public final double elevation, azimuth;

        Pos(double elevation, double azimuth) {
            this.elevation = elevation;
            this.azimuth = azimuth;
        }
    }

    private static double julian(int yi, int mi, int di) {
        double y = yi, m = mi;
        if (m <= 2) { y -= 1; m += 12; }
        double a = Math.floor(y / 100), b = 2 - a + Math.floor(a / 4);
        return Math.floor(365.25 * (y + 4716)) + Math.floor(30.6001 * (m + 1)) + di + b - 1524.5;
    }

    /** declination (deg) and equation of time (min) at a Julian day: {decl, eq}. */
    private static double[] at(double jd) {
        double t = (jd - 2451545) / 36525;
        double l0 = (280.46646 + t * (36000.76983 + t * 0.0003032)) % 360;
        double m = 357.52911 + t * (35999.05029 - 0.0001537 * t);
        double e = 0.016708634 - t * (0.000042037 + 0.0000001267 * t);
        double c = Math.sin(m * R) * (1.914602 - t * (0.004817 + 0.000014 * t))
                + Math.sin(2 * m * R) * (0.019993 - 0.000101 * t)
                + Math.sin(3 * m * R) * 0.000289;
        double om = 125.04 - 1934.136 * t, lam = l0 + c - 0.00569 - 0.00478 * Math.sin(om * R);
        double sec = 21.448 - t * (46.815 + t * (0.00059 - t * 0.001813));
        double eps0 = 23 + (26 + sec / 60) / 60, eps = eps0 + 0.00256 * Math.cos(om * R);
        double decl = Math.asin(Math.sin(eps * R) * Math.sin(lam * R)) * D;
        double y = Math.pow(Math.tan(eps * R / 2), 2);
        double eq = 4 * D * (y * Math.sin(2 * l0 * R) - 2 * e * Math.sin(m * R)
                + 4 * e * y * Math.sin(m * R) * Math.cos(2 * l0 * R)
                - 0.5 * y * y * Math.sin(4 * l0 * R) - 1.25 * e * e * Math.sin(2 * m * R));
        return new double[] {decl, eq};
    }

    /** Hour angle at the horizon; -1 = sun never rises, 99 = never sets. */
    private static double ha(double lat, double decl) {
        double c = Math.cos(90.833 * R) / (Math.cos(lat * R) * Math.cos(decl * R))
                - Math.tan(lat * R) * Math.tan(decl * R);
        return c > 1 ? -1 : c < -1 ? 99 : Math.acos(c) * D;
    }

    public static Times times(double lat, double lon, int y, int m, int d, double tzHours) {
        double jd0 = julian(y, m, d);
        double[] n = at(jd0 + 720 / 1440.0 - tzHours / 24);
        double noon0 = 720 - 4 * lon - n[1] + tzHours * 60;
        double h0 = ha(lat, n[0]);
        if (h0 == -1) return new Times(Double.NaN, Double.NaN, noon0, POLAR_NIGHT);
        if (h0 == 99) return new Times(Double.NaN, Double.NaN, noon0, POLAR_DAY);
        double rise = noon0 - 4 * h0, set = noon0 + 4 * h0;
        double[] a = at(jd0 + rise / 1440 - tzHours / 24), b = at(jd0 + set / 1440 - tzHours / 24);
        double hr = ha(lat, a[0]), hs = ha(lat, b[0]);
        if (hr > 0 && hr < 99) rise = 720 - 4 * lon - a[1] + tzHours * 60 - 4 * hr;
        if (hs > 0 && hs < 99) set = 720 - 4 * lon - b[1] + tzHours * 60 + 4 * hs;
        return new Times(rise, set, noon0, POLAR_NONE);
    }

    public static Pos position(double lat, double lon, int y, int m, int d, double tzHours, double minute) {
        double[] n = at(julian(y, m, d) + minute / 1440 - tzHours / 24);
        double decl = n[0], eq = n[1];
        double tst = (((minute + eq + 4 * lon - 60 * tzHours) % 1440) + 1440) % 1440;
        double ha = tst / 4 - 180;
        double sl = Math.sin(lat * R), cl = Math.cos(lat * R), sd = Math.sin(decl * R), cd = Math.cos(decl * R);
        double cz = Math.max(-1, Math.min(1, sl * sd + cl * cd * Math.cos(ha * R)));
        double zen = Math.acos(cz) * D, eg = 90 - zen;
        double den = cl * Math.sin(zen * R);
        if (den == 0) den = 1e-9;
        double azr = Math.acos(Math.max(-1, Math.min(1, (sl * cz - sd) / den))) * D;
        double az = ha > 0 ? (azr + 180) % 360 : (540 - azr) % 360;
        double t = Math.tan(eg * R), rf;
        if (eg > 85) rf = 0;
        else if (eg > 5) rf = 58.1 / t - 0.07 / Math.pow(t, 3) + 0.000086 / Math.pow(t, 5);
        else if (eg > -0.575) rf = 1735 + eg * (-518.2 + eg * (103.4 + eg * (-12.79 + eg * 0.711)));
        else rf = -20.774 / t;
        return new Pos(eg + rf / 3600, az);
    }

    public static double elevationMax(double lat, double lon, int y, int m, int d, double tzHours) {
        Times t = times(lat, lon, y, m, d, tzHours);
        return position(lat, lon, y, m, d, tzHours, Math.round(t.noon)).elevation;
    }
}
