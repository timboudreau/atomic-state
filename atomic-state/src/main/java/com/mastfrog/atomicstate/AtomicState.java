/*
 * The MIT License
 *
 * Copyright 2022 Mastfrog Technologies.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package com.mastfrog.atomicstate;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Annotation which can be applied to an interface with methods that return
 * simple types or enums, which will generate an implementaton of that interface
 * over a single Java <code>int</code> or <code>long</code> depending on the
 * number of bits required, and a "holder" class which uses AtomicInteger or
 * AtomicLong to store the state, and makes atomic updates trivial to implement.
 * <p>
 * Where this is useful: When you have a small amount of state that *must* be
 * updated atomically, which can be packed into a number of bits less than 64.
 * Examples where this pattern has been used:
 * </p>
 * <ul>
 * <li>Keeping track of the state of an external process, where updates may
 * arrive on different threads</li>
 * <li>
 * A desktop "tray app" that does network access, which may do network access,
 * which needs to keep track of states like network access in progress, network
 * access disabled, shutting down, menu open or closed, where things like
 * initiating network access happen on background threads, and things like menu
 * state changes may happen on the Mac OS app-kit thread.
 * </li>
 * </ul>
 *
 * @author Tim Boudreau
 */
@Retention(RetentionPolicy.CLASS)
public @interface AtomicState {

    /**
     * If true, the generated holder class will include a listener interface
     * which allows for listening for state changes.
     *
     * @return a boolean
     */
    boolean generateChangeSupport() default false;
}
