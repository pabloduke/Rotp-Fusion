package rotp;

/** Selects the process mode before either desktop entry point initializes. */
public class Launcher {
    private static boolean headless;
    public static boolean headless() { return headless; }

    public static void main(String[] args) throws Exception {
        if (!dispatch(args))
            RotpGovernor.main(args);
    }

    static boolean dispatch(String[] args) {
        if (args.length == 0 || !args[0].equals("--headless"))
            return false;
        enableHeadless();
        System.exit(HeadlessMatch.run(args));
        return true;
    }

    static void enableHeadless() {
        headless = true;
        System.setProperty("java.awt.headless", "true");
    }

    public static class Mini {
        public static void main(String[] args) {
            if (!dispatch(args))
                Rotp.main(args);
        }
    }
}
