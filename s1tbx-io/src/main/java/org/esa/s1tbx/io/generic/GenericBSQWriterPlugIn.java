/*
 * Copyright (C) 2014 by Array Systems Computing Inc. http://www.array.ca
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
package org.esa.s1tbx.io.generic;

import org.esa.snap.core.dataio.AbstractProductWriter;
import org.esa.snap.core.dataio.EncodeQualification;
import org.esa.snap.core.dataio.ProductWriter;
import org.esa.snap.core.dataio.ProductWriterPlugIn;
import org.esa.snap.core.datamodel.Product;
import org.esa.snap.core.util.io.SnapFileFilter;

import java.io.File;
import java.util.Locale;

public class GenericBSQWriterPlugIn implements ProductWriterPlugIn {

    private static final String FORMAT_NAME = "Generic Binary BSQ";
    private final static String FILE_EXTENSION = ".bin";

    /**
     * Constructs a new product writer plug-in instance.
     */
    public GenericBSQWriterPlugIn() {
    }

    @Override
    public EncodeQualification getEncodeQualification(Product product) {
        return new EncodeQualification(EncodeQualification.Preservation.PARTIAL);
    }

    public String[] getFormatNames() {
        return new String[]{FORMAT_NAME};
    }

    public String[] getDefaultFileExtensions() {
        return new String[]{FILE_EXTENSION};
    }

    /**
     * Returns an array containing the classes that represent valid output types for this GDAL product writer.
     * <p/>
     * <p> Intances of the classes returned in this array are valid objects for the <code>writeProductNodes</code>
     * method of the <code>AbstractProductWriter</code> interface (the method will not throw an
     * <code>InvalidArgumentException</code> in this case).
     *
     * @return an array containing valid output types, never <code>null</code>
     * @see AbstractProductWriter#writeProductNodes
     */
    public Class[] getOutputTypes() {
        return new Class[]{
                String.class,
                File.class,
//            ImageOutputStream.class
        };
    }

    /**
     * Gets a short description of this plug-in. If the given locale is set to <code>null</code> the default locale is
     * used.
     * <p/>
     * <p> In a GUI, the description returned could be used as tool-tip text.
     *
     * @param name the local for the given decription string, if <code>null</code> the default locale is used
     * @return a textual description of this product reader/writer
     */
    public String getDescription(Locale name) {
        return "Generic flat binary writer";
    }

    /**
     * Creates an instance of the actual product writer class.
     *
     * @return a new instance of the <code>GDALWriter</code> class
     */
    public ProductWriter createWriterInstance() {
        return new GenericBSQWriter(this);
    }

    public SnapFileFilter getProductFileFilter() {
        return new SnapFileFilter(getFormatNames()[0], getDefaultFileExtensions(), getDescription(null));
    }
}
