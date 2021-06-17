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
package org.esa.s1tbx.io.orbits.doris;

import org.esa.s1tbx.io.orbits.BaseOrbitFile;
import org.esa.snap.core.datamodel.MetadataElement;
import org.esa.snap.core.datamodel.Product;
import org.esa.snap.dataio.envisat.EnvisatOrbitReader;
import org.esa.snap.engine_utilities.datamodel.AbstractMetadata;
import org.esa.snap.engine_utilities.datamodel.Orbits;
import org.esa.snap.engine_utilities.download.DownloadableArchive;
import org.esa.snap.engine_utilities.util.Settings;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.util.Calendar;
import java.util.Date;

/**
 * DORIS Orbit File
 */
public class DorisOrbitFile extends BaseOrbitFile {

    private EnvisatOrbitReader dorisReader = null;
    private final Product sourceProduct;

    public static final String DORIS_POR = "DORIS Preliminary POR";
    public static final String DORIS_VOR = "DORIS Precise VOR";

    public DorisOrbitFile(final MetadataElement absRoot,
                          final Product sourceProduct) throws Exception {
        super(absRoot);
        this.sourceProduct = sourceProduct;
    }

    public String[] getAvailableOrbitTypes() {
        return new String[] { DORIS_VOR, DORIS_POR };
    }

    public File retrieveOrbitFile(final String orbitType) throws Exception {
        dorisReader = EnvisatOrbitReader.getInstance();
        final int absOrbit = absRoot.getAttributeInt(AbstractMetadata.ABS_ORBIT, 0);

        // construct path to the orbit file folder
        String orbitPath = "";
        if (orbitType.contains(DORIS_VOR)) {
            orbitPath = Settings.getPath("OrbitFiles.dorisVOROrbitPath");
        } else if (orbitType.contains(DORIS_POR)) {
            orbitPath = Settings.getPath("OrbitFiles.dorisPOROrbitPath");
        }

        final Calendar startCal = sourceProduct.getStartTime().getAsCalendar();
        final int year = startCal.get(Calendar.YEAR);
        final int month = startCal.get(Calendar.MONTH) + 1;
        String folder = String.valueOf(year);

        if (month < 10) {
            folder += '0';
        }
        folder += month;
        orbitPath += folder;
        final File localPath = new File(orbitPath);

        // find orbit file in the folder
        final Date startDate = sourceProduct.getStartTime().getAsDate();
        orbitFile = FindDorisOrbitFile(dorisReader, localPath, startDate, absOrbit);
        if (orbitFile == null) {
            getRemoteFiles(orbitType, year);
            orbitFile = FindDorisOrbitFile(dorisReader, localPath, startDate, absOrbit);
        }

        if (orbitFile == null) {
            throw new IOException("Unable to find suitable DORIS orbit file in\n" + orbitPath);
        }

        dorisReader.readOrbitData();

        return orbitFile;
    }

    /**
     * Get orbit information for given time.
     *
     * @param utc The UTC in days.
     * @return The orbit information.
     * @throws Exception The exceptions.
     */
    public Orbits.OrbitVector getOrbitData(final double utc) throws Exception {

        final EnvisatOrbitReader.OrbitVector orb = dorisReader.getOrbitVector(utc);

        return new Orbits.OrbitVector(utc,
                orb.xPos, orb.yPos, orb.zPos,
                orb.xVel, orb.yVel, orb.zVel);
    }

    private void getRemoteFiles(final String orbitType, final int year) throws Exception {

        if(!orbitType.contains(DORIS_VOR)) {
            return;
        }

        final File localFolder = new File(Settings.getPath("OrbitFiles.dorisVOROrbitPath"));
        final URL remotePath = new URL(Settings.getPath("OrbitFiles.dorisHTTP_vor_remotePath"));
        final File localFile = new File(localFolder, year+".zip");

        final DownloadableArchive archive = new DownloadableArchive(localFile, remotePath);
        archive.getContentFiles();
    }

    /**
     * Find DORIS orbit file.
     *
     * @param dorisReader The ENVISAT oribit reader.
     * @param path        The path to the orbit file.
     * @param productDate The start date of the product.
     * @param absOrbit    The absolute orbit number.
     * @return The orbit file.
     * @throws IOException
     */
    private static File FindDorisOrbitFile(EnvisatOrbitReader dorisReader, File path, Date productDate, int absOrbit)
            throws IOException {

        final File[] list = path.listFiles();
        if (list == null) return null;

        // loop through all orbit files in the given folder
        for (File f : list) {

            if (f.isDirectory()) {
                final File foundFile = FindDorisOrbitFile(dorisReader, f, productDate, absOrbit);
                if (foundFile != null) {
                    return foundFile;
                }
            }

            try {
                // open each orbit file
                dorisReader.readProduct(f);

                // get the start and end dates and compare them against product start date
                final Date startDate = dorisReader.getSensingStart();
                final Date stopDate = dorisReader.getSensingStop();
                if (productDate.after(startDate) && productDate.before(stopDate)) {

                    return f;
                }
            } catch (Exception e) {
                System.out.println(e.getMessage());
                // continue
            }
        }

        return null;
    }
}
