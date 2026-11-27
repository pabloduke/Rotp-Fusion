package rotp.replay;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.StringJoiner;
import rotp.model.ai.interfaces.Diplomat;
import rotp.model.empires.Empire;
import rotp.model.tech.Tech;
import rotp.ui.diplomacy.DiplomaticCounterReply;
import rotp.ui.diplomacy.DiplomaticReply;

/** Records the cross-empire exchange once, around the real diplomat's call. */
public final class ReplayDiplomacy {
    private ReplayDiplomacy() { }

    static Diplomat observe(Empire receiver, Diplomat delegate) {
        return (Diplomat) Proxy.newProxyInstance(Diplomat.class.getClassLoader(), new Class<?>[] {Diplomat.class},
                (proxy, method, args) -> {
            boolean exchange = DiplomaticReply.class.isAssignableFrom(method.getReturnType());
            String action = method.getName();
            if (exchange) {
                var terms = new StringJoiner(", ");
                for (Object arg : args) terms.add(describe(arg));
                ReplayRecorder.event("Diplomacy", receiver.id, -1, action + " (" + terms + ")");
            }
            Object result;
            try { result = method.invoke(delegate, args); }
            catch (InvocationTargetException failure) { throw failure.getCause(); }
            if (exchange && result instanceof DiplomaticReply reply)
                ReplayRecorder.event("Reply", receiver.id, -1, describe(reply));
            return result;
        });
    }

    private static String describe(Object arg) {
        if (arg instanceof Empire e) return e.name() + " (#" + e.id + ")";
        if (arg instanceof Tech t) return t.id();
        if (arg instanceof DiplomaticCounterReply r)
            return "Counteroffer: " + r.remark() + " " + r.techs() + ", " + r.bribeAmt() + " BC";
        if (arg instanceof DiplomaticReply r) return (r.accepted() ? "Accepted: " : "Refused: ") + r.remark();
        return String.valueOf(arg);
    }
}
