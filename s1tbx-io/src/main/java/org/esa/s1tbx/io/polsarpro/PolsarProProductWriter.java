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
package org.esa.s1tbx.io.polsarpro;

import org.esa.s1tbx.commons.io.FileImageOutputStreamExtImpl;
import org.esa.s1tbx.commons.polsar.PolBandUtils;
import org.esa.snap.core.dataio.ProductWriterPlugIn;
import org.esa.snap.core.dataio.dimap.EnviHeader;
import org.esa.snap.core.datamodel.Band;
import org.esa.snap.core.datamodel.MetadataElement;
import org.esa.snap.core.datamodel.Product;
import org.esa.snap.core.util.SystemUtils;
import org.esa.snap.dataio.envi.EnviProductWriter;
import org.esa.snap.engine_utilities.datamodel.AbstractMetadata;
import org.esa.snap.engine_utilities.datamodel.metadata.AbstractMetadataIO;

import javax.imageio.stream.ImageOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.ByteOrder;

/**
 * The product writer for PolSARPro products.
 */
public class PolsarProProductWriter extends EnviProductWriter {

    private final static String BIN_EXTENSION = ".bin";

    /**
     * Construct a new instance of a product writer for the given ENVI product writer plug-in.
     *
     * @param writerPlugIn the given ENVI product writer plug-in, must not be <code>null</code>
     */
    public PolsarProProductWriter(final ProductWriterPlugIn writerPlugIn) {
        super(writerPlugIn);
    }

    /**
     * Writes the in-memory representation of a data product. This method was called by <code>writeProductNodes(product,
     * output)</code> of the AbstractProductWriter.
     *
     * @throws IllegalArgumentException if <code>output</code> type is not one of the supported output sources.
     * @throws java.io.IOException      if an I/O error occurs
     */
    @Override
    protected void writeProductNodesImpl() throws IOException {
        super.writeProductNodesImpl();

        writeConfigFile(getSourceProduct(), getOutputDir());

        final MetadataElement absRoot = AbstractMetadata.getAbstractedMetadata(getSourceProduct());
        AbstractMetadataIO.saveExternalMetadata(getSourceProduct(), absRoot, new File(getOutputDir(), "metadata.xml"));
    }

    protected void writeEnviHeader(Band band) throws IOException {
        EnviHeader.createPhysicalFile(getEnviHeaderFile(band),
                                      band,
                                      band.getRasterWidth(),
                                      band.getRasterHeight(), 0);
    }

    protected ImageOutputStream createImageOutputStream(Band band) throws IOException {
        final ImageOutputStream out = new FileImageOutputStreamExtImpl(getValidImageFile(band));
        out.setByteOrder(ByteOrder.LITTLE_ENDIAN);
        return out;
    }

    /**
     * Initializes all the internal file and directory elements from the given output file. This method only must be
     * called if the product writer should write the given data to raw data files without calling of writeProductNodes.
     * This may be at the time when a dimap product was opened and the data shold be continously changed in the same
     * product file without an previous call to the saveProductNodes to this product writer.
     *
     * @param outputFileLocation the dimap header file location.
     */
    protected void initDirs(final File outputFileLocation) {
        super.initDirs(outputFileLocation);

        final PolBandUtils.MATRIX matrixType = PolBandUtils.getSourceProductType(getSourceProduct());
        String folder = "";
        if (matrixType.equals(PolBandUtils.MATRIX.C3)) {
            folder = "C3";
        } else if (matrixType.equals(PolBandUtils.MATRIX.T3)) {
            folder = "T3";
        } else if (matrixType.equals(PolBandUtils.MATRIX.C4)) {
            folder = "C4";
        } else if (matrixType.equals(PolBandUtils.MATRIX.T4)) {
            folder = "T4";
        }
        if (!folder.isEmpty()) {
            outputDir = new File(outputDir, folder);
            if (!outputDir.exists() && !outputDir.mkdirs()) {
                SystemUtils.LOG.severe("Unable to create folders in " + outputDir);
            }
            outputFile = new File(outputDir, outputFile.getName());
        }
    }

    protected void ensureNamingConvention() {
    }

    protected String createImageFilename(Band band) {
        return band.getName() + BIN_EXTENSION;
    }

    protected String createEnviHeaderFilename(Band band) {
        return band.getName() + BIN_EXTENSION + EnviHeader.FILE_EXTENSION;
    }

    private static void writeConfigFile(final Product srcProduct, final File folder) {
        PrintStream p = null;
        try {
            final File file = new File(folder, "config.txt");
            final FileOutputStream out = new FileOutputStream(file);
            p = new PrintStream(out);

            p.println("Nrow");
            p.println(srcProduct.getSceneRasterHeight());
            p.println("---------");

            p.println("Ncol");
            p.println(srcProduct.getSceneRasterWidth());
            p.println("---------");

            p.println("PolarCase");
            p.println(getPolarCase(srcProduct));
            p.println("---------");

            p.println("PolarType");
            p.println(PolBandUtils.getPolarType(srcProduct));
            p.println("---------");

        } catch (Exception e) {
            System.out.println("PolsarProWriter unable to write config.txt " + e.getMessage());
        } finally {
            if (p != null)
                p.close();
        }
    }

    private static String getPolarCase(final Product srcProduct) {
        final PolBandUtils.MATRIX matrixType = PolBandUtils.getSourceProductType(srcProduct);
        if (matrixType.equals(PolBandUtils.MATRIX.C4) || matrixType.equals(PolBandUtils.MATRIX.T4)) {
            return "bistatic";
        }
        return "monostatic";
    }

}
