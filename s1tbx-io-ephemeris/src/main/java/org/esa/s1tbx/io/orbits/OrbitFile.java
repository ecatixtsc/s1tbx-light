/*
 * Copyright (C) 2015 by Array Systems Computing Inc. http://www.array.ca
 *
 * This program is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by the Free
 * Software Foundation; either version 3 of the License, or (at your option)
 * any later version.
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for
 * more details.
 *
 * You should have received a copy of the GNU General Public License along
 * with this program; if not, see http://www.gnu.org/licenses/
 */
package org.esa.s1tbx.io.orbits;

import org.esa.snap.engine_utilities.datamodel.Orbits;

import java.io.File;

/**
 * retrieves an orbit file
 */
public interface OrbitFile {

    String[] getAvailableOrbitTypes();

    /**
     * download, find and read orbit file
     *
     * @throws Exception The exceptions.
     */
    File retrieveOrbitFile(final String orbitType) throws Exception;

    /**
     * Get orbit information for given time.
     *
     * @param utc The UTC in days.
     * @return The orbit information.
     * @throws Exception The exceptions.
     */
    Orbits.OrbitVector getOrbitData(final double utc) throws Exception;

    /**
     * Get the orbit file used
     *
     * @return the new orbit file
     */
    File getOrbitFile();

    default String getVersion() { return null; }
}
