package tv.orchard.home;

public final class GreetingTest {
    static int fails;

    static void eq(String name, String want, String got) {
        if (want == null ? got != null : !want.equals(got)) { fails++; System.err.println("FAIL " + name + ": want [" + want + "] got [" + got + "]"); }
    }

    public static void main(String[] args) {
        eq("04:59", "Good night", Greeting.at(299));
        eq("05:00", "Good morning", Greeting.at(300));
        eq("11:59", "Good morning", Greeting.at(719));
        eq("12:00", "Good afternoon", Greeting.at(720));
        eq("16:59", "Good afternoon", Greeting.at(1019));
        eq("17:00", "Good evening", Greeting.at(1020));
        eq("20:59", "Good evening", Greeting.at(1259));
        eq("21:00", "Good night", Greeting.at(1260));
        eq("00:00", "Good night", Greeting.at(0));
        eq("23:59", "Good night", Greeting.at(1439));
        if (fails > 0) { System.err.println(fails + " failure(s)"); System.exit(1); }
    }
}
