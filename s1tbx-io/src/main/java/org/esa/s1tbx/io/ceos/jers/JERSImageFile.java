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
package org.esa.s1tbx.io.ceos.jers;

import org.esa.s1tbx.io.binary.BinaryDBReader;
import org.esa.s1tbx.io.binary.BinaryFileReader;
import org.esa.s1tbx.io.binary.BinaryRecord;
import org.esa.s1tbx.io.binary.IllegalBinaryFormatException;
import org.esa.s1tbx.io.ceos.CEOSImageFile;
import org.jdom2.Document;

import javax.imageio.stream.ImageInputStream;
import java.io.IOException;


class JERSImageFile extends CEOSImageFile {

    private final static String mission = "jers";
    private final static String image_DefinitionFile = "image_file.xml";
    private final static String image_recordDefinition = "image_record.xml";

    private final static Document imgDefXML = BinaryDBReader.loadDefinitionFile(mission, image_DefinitionFile);
    private final static Document imgRecordXML = BinaryDBReader.loadDefinitionFile(mission, image_recordDefinition);

    public JERSImageFile(final ImageInputStream imageStream) throws IOException, IllegalBinaryFormatException {
        binaryReader = new BinaryFileReader(imageStream);
        imageFDR = new BinaryRecord(binaryReader, -1, imgDefXML, image_DefinitionFile);
        binaryReader.seek(imageFDR.getAbsolutPosition(imageFDR.getRecordLength()));
        final int numLines = imageFDR.getAttributeInt("Number of lines per data set");
        if (numLines == 0) {
            throw new IllegalBinaryFormatException("not an image file", 0);
        }
        imageRecords = new BinaryRecord[numLines];
        imageRecords[0] = createNewImageRecord(0);

        _imageRecordLength = imageRecords[0].getRecordLength();
        startPosImageRecords = imageRecords[0].getStartPos();
        imageHeaderLength = imageFDR.getAttributeInt("Number of bytes of prefix data per record");
    }

    protected BinaryRecord createNewImageRecord(final int line) throws IOException {
        final long pos = imageFDR.getAbsolutPosition(imageFDR.getRecordLength()) + (line * _imageRecordLength);
        return new BinaryRecord(binaryReader, pos, imgRecordXML, image_recordDefinition);
    }
}
