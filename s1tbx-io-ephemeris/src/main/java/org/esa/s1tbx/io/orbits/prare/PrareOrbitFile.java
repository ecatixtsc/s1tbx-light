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
package org.esa.s1tbx.io.orbits.prare;

import com.bc.ceres.core.NullProgressMonitor;
import org.esa.s1tbx.io.orbits.BaseOrbitFile;
import org.esa.snap.core.datamodel.MetadataElement;
import org.esa.snap.core.datamodel.Product;
import org.esa.snap.core.dataop.downloadable.FtpDownloader;
import org.esa.snap.engine_utilities.datamodel.AbstractMetadata;
import org.esa.snap.engine_utilities.datamodel.Orbits;
import org.esa.snap.engine_utilities.download.DownloadableArchive;
import org.esa.snap.engine_utilities.util.Settings;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.util.Calendar;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * DORIS Orbit File
 */
public class PrareOrbitFile extends BaseOrbitFile {

    public static final String PRARE_PRECISE = "PRARE Precise";

    private PrareOrbitReader prareReader = null;
    private final Product sourceProduct;

    public PrareOrbitFile(final MetadataElement absRoot,
                          final Product sourceProduct) throws Exception {
        super(absRoot);
        this.sourceProduct = sourceProduct;
    }

    public String[] getAvailableOrbitTypes() {
        return new String[] { PRARE_PRECISE };
    }

    public File retrieveOrbitFile(final String orbitType) throws Exception {
        prareReader = PrareOrbitReader.getInstance();
        final String mission = absRoot.getAttributeString(AbstractMetadata.MISSION);

        // construct path to the orbit file folder
        final String orbitPath;
        final String remoteBaseFolder;
        final String remoteHTTPFolder;
        if (mission.equals("ERS1")) {
            orbitPath = Settings.getPath("OrbitFiles.prareERS1OrbitPath");
            remoteBaseFolder = Settings.getPath("OrbitFiles.prareFTP_ERS1_remotePath");
            remoteHTTPFolder = Settings.getPath("OrbitFiles.prareHTTP_ERS1_remotePath");
        } else {
            orbitPath = Settings.getPath("OrbitFiles.prareERS2OrbitPath");
            remoteBaseFolder = Settings.getPath("OrbitFiles.prareFTP_ERS2_remotePath");
            remoteHTTPFolder = Settings.getPath("OrbitFiles.prareHTTP_ERS2_remotePath");
        }

        // get product start time
        // todo the startDate below is different from the start time in the metadata, why?
        final double startMJD = sourceProduct.getStartTime().getMJD();
        final Calendar startDate = sourceProduct.getStartTime().getAsCalendar();
        final int year = startDate.get(Calendar.YEAR);
        final int month = startDate.get(Calendar.MONTH) + 1;
        final String folder = String.valueOf(year);
        final File localPath = new File(orbitPath + File.separator + folder);

        // find orbit file in the folder
        orbitFile = FindPrareOrbitFile(prareReader, localPath, startMJD);
        if (orbitFile == null) {
            getRemoteFiles(new File(orbitPath), remoteHTTPFolder, year);
            orbitFile = FindPrareOrbitFile(prareReader, localPath, startMJD);

            if (orbitFile == null) {
                final String remotePath = remoteBaseFolder + '/' + folder;
                getRemotePrareFiles(remotePath, localPath, getPrefix(year, month));
                // find again in newly downloaded folder
                orbitFile = FindPrareOrbitFile(prareReader, localPath, startMJD);
                if (orbitFile == null) {
                    // check next month
                    getRemotePrareFiles(remotePath, localPath, getPrefix(year, month + 1));
                    orbitFile = FindPrareOrbitFile(prareReader, localPath, startMJD);
                }
            }
        }

        if (orbitFile == null) {
            throw new IOException("Unable to find suitable orbit file \n" + orbitPath + "\nPlease check your firewall settings");
        }

        // read orbit data records in each orbit file
        prareReader.readOrbitData(orbitFile);

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

        return prareReader.getOrbitVector(utc);
    }

    private void getRemoteFiles(final File localFolder, final String remoteHTTPFolder, final int year) throws Exception {

        final URL remotePath = new URL(remoteHTTPFolder);
        final File localFile = new File(localFolder, year+".zip");

        final DownloadableArchive archive = new DownloadableArchive(localFile, remotePath);
        archive.getContentFiles();
    }

    private static String getPrefix(int year, int month) {
        if (year >= 2000)
            year -= 2000;
        else
            year -= 1900;
        String monthStr = String.valueOf(month);
        if (month < 10)
            monthStr = '0' + monthStr;

        return "PRC_" + year + monthStr;
    }

    private void getRemotePrareFiles(final String remotePath, final File localPath, final String prefix) {
        final String prareFTP = Settings.instance().get("OrbitFiles.prareFTP");
        try {
            if (ftp == null) {
                final String user = Settings.instance().get("OrbitFiles.prareFTP_user");
                final String pass = Settings.instance().get("OrbitFiles.prareFTP_pass");
                ftp = new FtpDownloader(prareFTP, user, pass);
                final Map<String, Long> allfileSizeMap = FtpDownloader.readRemoteFileList(ftp, prareFTP, remotePath);
                fileSizeMap = new HashMap<>(10);

                // keep only those starting with prefix
                final Set<String> remoteFileNames = allfileSizeMap.keySet();
                for (String fileName : remoteFileNames) {
                    if (fileName.startsWith(prefix)) {
                        fileSizeMap.put(fileName, allfileSizeMap.get(fileName));
                    }
                }
            }

            if (!localPath.exists()) {
                if(!localPath.mkdirs()) {
                    throw new IOException("Failed to create directory '" + localPath + "'.");
                }
            }

            getRemoteFiles(ftp, fileSizeMap, remotePath, localPath, new NullProgressMonitor());

        } catch (Exception e) {
            System.out.println(e.getMessage());
        }
    }

    /**
     * Find PRARE orbit file.
     *
     * @param prareReader The PRARE oribit reader.
     * @param path        The path to the orbit file.
     * @param startMJD    The start date of the product.
     * @return The orbit file.
     * @throws IOException if can't read file
     */
    private static File FindPrareOrbitFile(final PrareOrbitReader prareReader, final File path,
                                           final double startMJD) throws IOException {

        final File[] list = path.listFiles();
        if (list == null) return null;

        // loop through all orbit files in the given folder
        for (File f : list) {

            if (f.isDirectory()) {
                continue;
            }

            // read header record of each orbit file
            prareReader.readOrbitHeader(f);

            // get the start and end dates and compare them against product start date
            final float startDateInMJD = prareReader.getSensingStart(); // in days
            final float stopDateInMJD = prareReader.getSensingStop(); // in days
            if (startDateInMJD <= startMJD && startMJD < stopDateInMJD) {
                try {
                    return f;
                } catch (Exception e) {
                    throw new IOException("Unable to parse file: " + e.toString());
                }
            }
        }

        return null;
    }
}
