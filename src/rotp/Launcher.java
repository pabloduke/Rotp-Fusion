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
        if (args.length > 0 && (args[0].equals("--export-replay") || args[0].equals("--export-replay-logs"))) {
            enableHeadless();
            if (args.length != 2) {
                System.err.println("Usage: " + args[0] + " <recording>");
                System.exit(2);
            }
            try {
                var recording = java.nio.file.Path.of(args[1]);
                if (args[0].equals("--export-replay-logs"))
                    System.out.println(rotp.replay.ReplayLogs.export(recording));
                else
                    System.out.println("Video: " + rotp.replay.ReplayVideo.export(recording));
            } catch (Exception failure) {
                System.err.println("Cannot export " + args[1] + ": " + failure.getMessage());
                System.exit(1);
            }
            return true;
        }
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
