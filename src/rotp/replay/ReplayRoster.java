package rotp.replay;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import rotp.leader.MatchConfig;
import rotp.model.game.GameSession;
import rotp.multiplayer.session.MatchOutcome;

/** Public observer metadata. Configuration, prompts and endpoint credentials stay outside the archive. */
public final class ReplayRoster {
    private ReplayRoster() {}

    public record Seat(int empireId, String empireName, String race, String playerName, String persona,
                       String provider, String model, String reasoningEffort, String builtinAi, boolean winner) {
        public String controllerLabel() {
            if (builtinAi != null) return builtinAi + " AI";
            return persona + " · " + model;
        }
    }

    static List<Seat> capture(GameSession session, MatchConfig match, String[] ais) {
        var result = new ArrayList<Seat>();
        var outcome = session.matchOutcome();
        for (var player : session.controllerRegistry().seats()) {
            int id = player.empireId();
            var empire = session.galaxy().empire(id);
            var seat = match == null ? null : match.seats().get(id);
            boolean winner = outcome.finished() && outcome.resultsByPlayer().get(player.playerId()) == MatchOutcome.SeatResult.WON;
            if (seat != null && seat.isLeader()) {
                result.add(new Seat(id, empire.name(), empire.raceName(), seat.name(),
                        Objects.requireNonNullElse(seat.preset(), seat.name()), seat.isCodex() ? "codex" : seat.endpoint(),
                        seat.model(), seat.reasoningEffort(), null, winner));
            } else {
                String ai = ais == null ? empire.getAiName() : ais[ais.length == 1 ? 0 : id];
                String name = seat != null && seat.name() != null ? seat.name() : ai;
                result.add(new Seat(id, empire.name(), empire.raceName(), name, null, null, null, null, ai, winner));
            }
        }
        return List.copyOf(result);
    }
}
