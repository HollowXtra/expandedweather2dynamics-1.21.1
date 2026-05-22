package com.imbet.weather;

public enum SpcRisk {
    NONE(0, "None", 0xFF263238, 0),
    TSTM(1, "TSTM", 0xFF80C342, 2),
    MARGINAL(2, "MRGL", 0xFF2E7D32, 5),
    SLIGHT(3, "SLGT", 0xFFFFD54F, 8),
    ENHANCED(4, "ENH", 0xFFFF8F00, 11),
    MODERATE(5, "MDT", 0xFFD32F2F, 13),
    HIGH(6, "HIGH", 0xFFC2185B, 15);

    private final int severity;
    private final String label;
    private final int color;
    private final int redstonePower;

    SpcRisk(int severity, String label, int color, int redstonePower) {
        this.severity = severity;
        this.label = label;
        this.color = color;
        this.redstonePower = redstonePower;
    }

    public int severity() {
        return severity;
    }

    public String label() {
        return label;
    }

    public int color() {
        return color;
    }

    public int redstonePower() {
        return redstonePower;
    }

    public static SpcRisk max(SpcRisk first, SpcRisk second) {
        return first.severity >= second.severity ? first : second;
    }

    public static SpcRisk cap(SpcRisk risk, SpcRisk cap) {
        return risk.severity > cap.severity ? cap : risk;
    }

    public static SpcRisk fromSeverity(int severity) {
        if (severity >= HIGH.severity) {
            return HIGH;
        }
        if (severity >= MODERATE.severity) {
            return MODERATE;
        }
        if (severity >= ENHANCED.severity) {
            return ENHANCED;
        }
        if (severity >= SLIGHT.severity) {
            return SLIGHT;
        }
        if (severity >= MARGINAL.severity) {
            return MARGINAL;
        }
        if (severity >= TSTM.severity) {
            return TSTM;
        }
        return NONE;
    }
}
