package java.lang;

@SuppressWarnings("unused")
public class LineCountSink {
    private static final ThreadLocal<State> state = ThreadLocal.withInitial(State::new);

    private LineCountSink() {}

    public static void addLine() {
        var threadState = state.get();
        threadState.lines += threadState.increment;
    }

    public static long getLines() {
        return state.get().lines;
    }

    public static void reset() {
        var threadState = state.get();
        threadState.lines = 0;
    }

    public static boolean getCounting() {
        return state.get().increment > 0;
    }

    public static void setCounting(boolean counting) {
        state.get().increment = counting ? 1 : 0;
    }

    private static class State {
        long lines = 0;
        long increment = 0;
    }
}
