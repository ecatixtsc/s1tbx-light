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
package org.esa.s1tbx.io.sentinel1;

import java.io.File;
import java.nio.file.Path;

/**
 * Several constants used for reading SENTINEL-1 products.
 */
public class Sentinel1Constants {

    private final static String[] FORMAT_NAMES = new String[]{"SENTINEL-1"};
    private final static String[] FORMAT_FILE_EXTENSIONS = new String[]{"safe", "zip"};
    private final static String PLUGIN_DESCRIPTION = "SENTINEL-1 Products";      /*I18N*/

    public final static String PRODUCT_HEADER_PREFIX = "MANIFEST";
    public final static String PRODUCT_HEADER_NAME = "manifest.safe";

    private final static String INDICATION_KEY = "SAFE";

    final static Class[] VALID_INPUT_TYPES = new Class[]{Path.class, File.class, String.class};

    public static String getIndicationKey() {
        return INDICATION_KEY;
    }

    public static String getPluginDescription() {
        return PLUGIN_DESCRIPTION;
    }

    public static String[] getFormatNames() {
        return FORMAT_NAMES;
    }

    public static String[] getFormatFileExtensions() {
        return FORMAT_FILE_EXTENSIONS;
    }

}
