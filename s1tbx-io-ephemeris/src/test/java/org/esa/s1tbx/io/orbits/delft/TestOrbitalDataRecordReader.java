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
package org.esa.s1tbx.io.orbits.delft;

import org.esa.snap.core.util.ResourceInstaller;
import org.esa.snap.engine_utilities.util.TestUtils;
import org.junit.Assert;
import org.junit.Test;

import java.nio.file.Path;

/**
 * OrbitalDataRecordReader Tester.
 *
 * @author lveci
 */
public class TestOrbitalDataRecordReader {

    private final static String envisatOrbitFilePath = "org/esa/s1tbx/io/orbits/envisat_ODR.051";
    private final static String ers1OrbitFilePath = "org/esa/s1tbx/io/orbits/ers1_ODR.079";
    private final static String ers2OrbitFilePath = "org/esa/s1tbx/io/orbits/ers2_ODR.015";
    private final Path basePath = ResourceInstaller.findModuleCodeBasePath(this.getClass());

    @Test
    public void testOpenFile() {

        final OrbitalDataRecordReader reader = new OrbitalDataRecordReader();

        Assert.assertTrue(reader.OpenOrbitFile(basePath.resolve(envisatOrbitFilePath)));
    }

    @Test
    public void testReadHeader() {

        final OrbitalDataRecordReader reader = new OrbitalDataRecordReader();

        if (reader.OpenOrbitFile(basePath.resolve(envisatOrbitFilePath))) {

            reader.parseHeader1();
            reader.parseHeader2();
        } else {
            Assert.assertTrue(false);
        }
    }

    @Test
    public void testReadERS1OrbitFiles() throws Exception {
        readOrbitFile("ERS1 ORD", basePath.resolve(ers1OrbitFilePath));
    }

    @Test
    public void testReadERS2OrbitFile() throws Exception {
        readOrbitFile("ERS2 ORD", basePath.resolve(ers2OrbitFilePath));
    }

    @Test
    public void testReadEnvisatOrbitFile() throws Exception {
        readOrbitFile("Envisat ORD", basePath.resolve(envisatOrbitFilePath));
    }

    private static void readOrbitFile(final String name, final Path path) throws Exception {
        final OrbitalDataRecordReader reader = new OrbitalDataRecordReader();
        final boolean res = reader.readOrbitFile(path);
        assert(res);

        final OrbitalDataRecordReader.OrbitDataRecord[] orbits = reader.getDataRecords();
        final StringBuilder str = new StringBuilder(name+ " Num Orbits " + orbits.length);
        for (int i = 0; i < 2; ++i) {
            str.append(" Orbit time " + orbits[i].time);
            str.append(" lat " + orbits[i].latitude);
            str.append(" lng " + orbits[i].longitude);
            str.append(" hgt " + orbits[i].heightOfCenterOfMass);
        }
        TestUtils.log.info(str.toString());
    }

}
