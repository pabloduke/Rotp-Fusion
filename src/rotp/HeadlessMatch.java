package rotp;

import java.nio.file.Path;
import rotp.replay.ReplayRecorder;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import rotp.model.ai.AIEntry;
import rotp.model.game.*;
import rotp.multiplayer.session.*;
import rotp.multiplayer.turn.StrictInProcessNotificationSink;

final class HeadlessMatch {
    private static final List<String> SIZES = List.of("tiny", "small", "medium", "large", "huge");
    private static final List<String> AIS = List.of("fusion", "base", "modnar", "rookie", "xilmi", "hybrid", "fun", "personality");

    static int run(String[] args) {
        String size = "small";
        String[] ais = {"fusion"};
        int opponents = 1;
        Path recording = null;
        try {
            for (int i = 1; i < args.length; i++) {
                String key = args[i];
                if (key.equals("--help")) {
                    System.out.println("--headless [--galaxy-size small] [--opponents 1] [--ais fusion] [--record <path>]\n"
                            + "Sizes: " + SIZES + "\nAIs (one or one per empire): " + AIS);
                    return 0;
                }
                if (++i == args.length) throw new IllegalArgumentException("Missing value for " + key);
                String value = args[i].toLowerCase(Locale.ROOT);
                switch (key) {
                    case "--galaxy-size" -> size = value;
                    case "--opponents" -> opponents = Integer.parseInt(value);
                    case "--record" -> recording = Path.of(args[i]);
                    case "--ais" -> ais = value.split(",", -1);
                    default -> throw new IllegalArgumentException("Unknown option " + key);
                }
            }
            if (!SIZES.contains(size))
                throw new IllegalArgumentException("Unknown galaxy size " + size);
            if (opponents < 1 || ais.length != 1 && ais.length - 1 != opponents)
                throw new IllegalArgumentException("Expected one AI or one per empire, with at least one opponent");
            for (String ai : ais)
                if (!AIS.contains(ai))
                    throw new IllegalArgumentException("Unknown AI " + ai);
        } catch (IllegalArgumentException invalid) {
            System.err.println(invalid.getMessage());
            return 2;
        }
        try {
            rotp.model.planet.PlanetFactory.current();
            rotp.model.tech.TechLibrary.current();
            rotp.util.LanguageManager.current().selectedLanguageName();
            var options = RulesetManager.current().newOptions();
            options.selectedGalaxySize(size.equals("medium") ? IGameOptions.SIZE_MEDIUM : "SETUP_GALAXY_SIZE_" + size.toUpperCase(Locale.ROOT));
            if (opponents > options.maximumOpponentsOptions()) {
                System.err.println("Too many opponents for " + size);
                return 2;
            }
            if (recording == null) {
                var directory = java.nio.file.Files.createDirectories(Path.of("replays"));
                recording = directory.resolve("match-" + java.util.UUID.randomUUID() + ".rotpreplay");
            }
            var session = start(options, opponents, ais);
            try (var recorder = new ReplayRecorder(recording, session)) {
                recorder.capture();
                while (!session.matchOutcome().finished()) {
                    int turn = session.galaxy().currentTurn();
                    try {
                        session.advanceInProcessTurn();
                    } catch (RuntimeException failure) {
                        throw new IllegalStateException("Turn " + turn + ", phase "
                                + session.turnCoordinator().activePhase(), failure);
                    }
                    if (turn == session.galaxy().currentTurn() && !session.matchOutcome().finished())
                        throw new IllegalStateException("Match stalled at turn " + turn + ": " + session.turnCheckpoint());
                    recorder.capture();
                }
                recorder.finish();
            } catch (java.io.IOException failure) {
                throw new java.io.IOException("Cannot record " + recording + " at turn " + session.galaxy().currentTurn(), failure);
            }
            System.out.println("Recording: " + recording.toAbsolutePath());
            System.out.println("Video: " + rotp.replay.ReplayVideo.export(recording));
            var outcome = session.matchOutcome();
            System.out.println("Turn " + session.galaxy().currentTurn() + " — " + outcome.cause());
            for (var seat : session.controllerRegistry().seats())
                if (outcome.resultsByPlayer().get(seat.playerId()) == MatchOutcome.SeatResult.WON)
                    System.out.println("Winner " + seat.empireId() + ": " + session.galaxy().empire(seat.empireId()).name());
            return 0;
        } catch (Throwable failure) {
            failure.printStackTrace(System.err);
            return 1;
        }
    }

    static GameSession start(IGameOptions options, int opponents, String[] ais) {
        options.selectedNumberOpponents(opponents);
        options.selectedPlayer().update(options);
        options.selectedOpponentAIOption(IGameOptions.OPPONENT_AI_SELECTABLE);
        var registry = new ControllerRegistry();
        for (int i = 0; i <= opponents; i++) {
            AIEntry ai = switch (ais[ais.length == 1 ? 0 : i]) {
                case "base" -> IGameOptions.baseAI;
                case "modnar" -> IGameOptions.modnarAI;
                case "rookie" -> IGameOptions.rookieAI;
                case "xilmi" -> IGameOptions.xilmiAI;
                case "hybrid" -> IGameOptions.hybridAI;
                case "fun" -> IGameOptions.funAI;
                case "personality" -> IGameOptions.personalityAI;
                default -> IGameOptions.fusionAI;
            };
            options.specificOpponentAIOption(ai.aliensKey, i);
            if (i == 0) options.selectedAutoplayOption(ai.playerKey);
            registry.add(new PlayerSeat("ai-" + i, i, PlayerSeat.ControllerType.AI,
                    PlayerSeat.ConnectionStatus.NOT_APPLICABLE));
        }
        var session = GameSession.instance();
        session.startGame(options, registry);
        session.turnNotificationSink(StrictInProcessNotificationSink.deferredDecisions(
                Map.of(), Map.of(), Map.of(), Map.of()));
        return session;
    }
}
