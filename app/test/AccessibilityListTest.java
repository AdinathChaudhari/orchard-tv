package tv.orchard.home;

public final class AccessibilityListTest {
    static final String A = AccessibilityList.HOF_IDS[0];
    static final String B = AccessibilityList.HOF_IDS[1];
    static int fails;

    static void eq(String name, String want, String got) {
        if (!want.equals(got)) { fails++; System.err.println("FAIL " + name + ": want [" + want + "] got [" + got + "]"); }
    }
    static void is(String name, boolean want, boolean got) {
        if (want != got) { fails++; System.err.println("FAIL " + name + ": want " + want + " got " + got); }
    }

    public static void main(String[] args) {
        eq("strip empty", "", AccessibilityList.strip(""));
        eq("strip null", "", AccessibilityList.strip(null));
        eq("strip \"null\"", "", AccessibilityList.strip("null"));
        eq("strip null token kept others", "x/.S", AccessibilityList.strip("null:x/.S"));
        eq("strip short id", "", AccessibilityList.strip(A));
        eq("strip long id", "", AccessibilityList.strip(B));
        eq("strip both ids, others kept", "p/.One:q/.Two", AccessibilityList.strip("p/.One:" + A + ":q/.Two:" + B));
        eq("strip duplicates", "p/.One:q/.Two", AccessibilityList.strip("p/.One:q/.Two:p/.One"));
        eq("strip trailing colon", "p/.One", AccessibilityList.strip("p/.One:"));
        eq("strip leading and double colon", "p/.One:q/.Two", AccessibilityList.strip(":p/.One::q/.Two"));
        eq("strip order kept", "z/.Z:a/.A:m/.M", AccessibilityList.strip("z/.Z:a/.A:m/.M"));
        eq("strip untouched without HoF", "p/.One:q/.Two", AccessibilityList.strip("p/.One:q/.Two"));

        eq("append empty", A, AccessibilityList.append(""));
        eq("append null", A, AccessibilityList.append(null));
        eq("append \"null\"", A, AccessibilityList.append("null"));
        eq("append to others", "p/.One:" + A, AccessibilityList.append("p/.One"));
        eq("append never doubles short", "p/.One:" + A, AccessibilityList.append("p/.One:" + A));
        eq("append never doubles long (normalises)", "p/.One:" + A, AccessibilityList.append("p/.One:" + B));
        eq("append both ids once", A, AccessibilityList.append(A + ":" + B));
        eq("append moves HoF to end", "p/.One:" + A, AccessibilityList.append(A + ":p/.One"));
        eq("append idempotent", AccessibilityList.append("p/.One"), AccessibilityList.append(AccessibilityList.append("p/.One")));
        eq("append trailing colon", "p/.One:" + A, AccessibilityList.append("p/.One:"));

        is("contains empty", false, AccessibilityList.contains(""));
        is("contains null", false, AccessibilityList.contains(null));
        is("contains \"null\"", false, AccessibilityList.contains("null"));
        is("contains others", false, AccessibilityList.contains("p/.One:q/.Two"));
        is("contains short", true, AccessibilityList.contains("p/.One:" + A));
        is("contains long", true, AccessibilityList.contains(B + ":p/.One"));
        is("contains after strip", false, AccessibilityList.contains(AccessibilityList.strip(A + ":" + B)));

        if (fails > 0) { System.err.println(fails + " failure(s)"); System.exit(1); }
    }
}
