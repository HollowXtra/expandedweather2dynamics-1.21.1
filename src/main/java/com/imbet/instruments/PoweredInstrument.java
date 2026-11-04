package com.imbet.instruments;

/**
 * An instrument that drives redstone from its reading.
 *
 * <p>Every instrument answers the same way a comparator-friendly block should: it holds a power level
 * between 0 and 15 and pushes it to its neighbours when it changes. Keeping that behind one interface
 * means the blocks do not each have to reimplement the four signal methods, and a new instrument gets
 * redstone for free by implementing this.
 */
public interface PoweredInstrument {
    /** The current redstone signal, 0 to 15. */
    int instrumentPower();
}
