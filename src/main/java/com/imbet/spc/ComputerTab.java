package com.imbet.spc;

/**
 * The three things a weather station answers, as tabs.
 *
 * <p>The weather computer already had all of this - the outlook on its face, the chase log, the spotter log, the
 * album and now the instruments wired to it - but it delivered them as a column of chat messages, which is a
 * readout rather than an interface. A machine that shows one product and then steps to the next on a right-click
 * is a machine you cannot read: the moment you step past the radar to see the warnings, the radar is gone. So the
 * three questions are tabs instead, and the order they are in is the order they are asked. What is the sky doing
 * now; what has it already done to me; what is in force here.
 *
 * <p>It is a plain model with no Minecraft types, so the strip, its wrapping and its labels are checkable - which
 * matters more than it sounds, because a tab strip built out of arithmetic in a render method is exactly the sort
 * of thing that ends up off by one at one end and unreachable at the other.
 */
public enum ComputerTab {
    /** The scope: what the radar is seeing from this station right now. */
    RADAR("Live Radar", "what the sky is doing over this station"),
    /** The record: what the storms have already done to the player reading it. */
    LOGS("Storm Logs", "what the sky has already done here"),
    /** What is in force: every product the ladder has in range, strongest first. */
    WARNINGS("Active Warnings", "what is in force at this station");

    private final String title;
    private final String note;

    ComputerTab(String title, String note) {
        this.title = title;
        this.note = note;
    }

    /** The word on the tab. */
    public String title() {
        return title;
    }

    /** The one line under the strip saying what this tab is looking at. */
    public String note() {
        return note;
    }

    /** How many tabs there are, for a caller laying out a strip. */
    public static final int COUNT = 3;

    /** This tab's place in the strip, from nothing. */
    public int index() {
        return ordinal();
    }

    /** The tab to the right, wrapping. */
    public ComputerTab next() {
        return values()[(ordinal() + 1) % COUNT];
    }

    /** The tab to the left, wrapping. */
    public ComputerTab previous() {
        return values()[(ordinal() + COUNT - 1) % COUNT];
    }

    /**
     * The tab at an index, wrapping in both directions.
     *
     * <p>Wrapping rather than clamping, because a strip is a ring in the hand: a click one tab to the left of the
     * first tab is the last tab, and a caller holding an index that has been saved from an older version reads a
     * valid tab rather than nothing.
     */
    public static ComputerTab of(int index) {
        int wrapped = ((index % COUNT) + COUNT) % COUNT;
        return values()[wrapped];
    }
}
