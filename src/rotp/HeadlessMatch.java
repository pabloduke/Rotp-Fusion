package rotp;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import rotp.leader.LeaderMatch;
import rotp.leader.MatchConfig;
import rotp.replay.ReplayRecorder;
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
        Path matchFile = null;
        MatchConfig match = null;
        boolean rosterFlags = false;
        try {
            for (int i = 1; i < args.length; i++) {
                String key = args[i];
                if (key.equals("--help")) {
                    System.out.println("--headless [--galaxy-size small] [--opponents 1] [--ais fusion] [--record <path>] or --headless --match <file.json> [--record <path>]\n"
                            + "Sizes: " + SIZES + "\nAIs (one or one per empire): " + AIS);
                    return 0;
                }
                if (++i == args.length) throw new IllegalArgumentException("Missing value for " + key);
                String value = args[i].toLowerCase(Locale.ROOT);
                if (List.of("--galaxy-size", "--opponents", "--ais").contains(key)) rosterFlags = true;
                switch (key) {
                    case "--galaxy-size" -> size = value;
                    case "--opponents" -> opponents = Integer.parseInt(value);
                    case "--match" -> matchFile = Path.of(args[i]);
                    case "--record" -> recording = Path.of(args[i]);
                    case "--ais" -> ais = value.split(",", -1);
                    default -> throw new IllegalArgumentException("Unknown option " + key);
                }
            }
            if (matchFile != null) {
                if (rosterFlags) throw new IllegalArgumentException("--match defines the roster and galaxy size");
                match = MatchConfig.load(matchFile);
                size = match.galaxySize();
                opponents = match.seats().size() - 1;
            }
            if (!SIZES.contains(size))
                throw new IllegalArgumentException("Unknown galaxy size " + size);
            if (opponents < 1 || ais.length != 1 && ais.length - 1 != opponents)
                throw new IllegalArgumentException("Expected one AI or one per empire, with at least one opponent");
            for (String ai : ais)
                if (!AIS.contains(ai))
                    throw new IllegalArgumentException("Unknown AI " + ai);
        } catch (java.io.IOException | IllegalArgumentException invalid) {
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
            if (recording == null)
                recording = Path.of("match-" + java.util.UUID.randomUUID() + ".rotpreplay");
            if (recording.getParent() == null && !recording.isAbsolute())
                recording = Path.of("replays").resolve(recording);
            if (recording.getParent() != null)
                java.nio.file.Files.createDirectories(recording.getParent());
            LeaderMatch leaders;
            try {
                leaders = match == null ? null : new LeaderMatch(match, matchFile);
            } catch (java.io.IOException | IllegalArgumentException invalid) {
                System.err.println("Invalid match configuration: " + invalid.getMessage());
                return 2;
            }
            try (leaders) {
                if (leaders != null) leaders.draft(options);
                var session = start(options, opponents, ais);
                play(session, recording, leaders, match, ais);
            }
            return 0;
        } catch (Throwable failure) {
            failure.printStackTrace(System.err);
            return 1;
        }
    }

    private static void play(GameSession session, Path recording, LeaderMatch leaders, MatchConfig match, String[] ais) throws Exception {
        try (var recorder = new ReplayRecorder(recording, session, match, ais)) {
            if (leaders != null) leaders.decide(session.galaxy());
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
                if (leaders != null && !session.matchOutcome().finished()) leaders.decide(session.galaxy());
                recorder.capture();
            }
            recorder.finish();
        } catch (java.io.IOException failure) {
            throw new java.io.IOException("Cannot record " + recording + " at turn " + session.galaxy().currentTurn(), failure);
        }
        System.out.println("Recording: " + recording.toAbsolutePath());
        long videoStart = System.nanoTime();
        try {
            System.out.println("Video: " + rotp.replay.ReplayVideo.export(recording));
        } finally {
            if (leaders != null) {
                System.out.println("Metrics: " + leaders.writeMetrics(recording, videoStart, session.galaxy().currentTurn()));
                System.out.println(rotp.replay.ReplayLogs.export(recording));
            }
        }
        var outcome = session.matchOutcome();
        System.out.println("Turn " + session.galaxy().currentTurn() + " — " + outcome.cause());
        for (var seat : session.controllerRegistry().seats())
            if (outcome.resultsByPlayer().get(seat.playerId()) == MatchOutcome.SeatResult.WON)
                System.out.println("Winner " + seat.empireId() + ": " + session.galaxy().empire(seat.empireId()).name());
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
