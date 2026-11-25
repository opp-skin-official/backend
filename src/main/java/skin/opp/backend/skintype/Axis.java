package skin.opp.backend.skintype;

/** 설문 5축. 점수가 높을수록 앞 글자(front) 쪽이다. */
public enum Axis {
    OIL('O', 'D'),
    SENSITIVITY('S', 'R'),
    PIGMENTATION('P', 'N'),
    WT('W', 'T'),
    CARE_STYLE('F', 'L');

    private final char front;
    private final char back;

    Axis(char front, char back) {
        this.front = front;
        this.back = back;
    }

    public char front() { return front; }
    public char back() { return back; }
}
