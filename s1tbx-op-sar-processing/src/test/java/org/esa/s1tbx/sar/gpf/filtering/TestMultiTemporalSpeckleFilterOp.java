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
package org.esa.s1tbx.sar.gpf.filtering;

import org.esa.s1tbx.commons.test.TestData;
import org.esa.snap.core.datamodel.Product;
import org.esa.snap.core.gpf.OperatorSpi;
import org.esa.snap.engine_utilities.util.TestUtils;
import org.junit.Before;
import org.junit.Test;

import java.io.File;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assume.assumeTrue;

/**
 * Unit test for Calibration Operator.
 */
public class TestMultiTemporalSpeckleFilterOp {

    private final static File inputFile = TestData.inputStackIMS;

    @Before
    public void setUp() {
        // If the file does not exist: the test will be ignored
        //assumeTrue(inputFile + " not found", inputFile.exists());
    }

    static {
        TestUtils.initTestEnvironment();
    }

    private final static OperatorSpi spi = new MultiTemporalSpeckleFilterOp.Spi();

    @Test
    public void testProcessingIMS() throws Exception {
        processFile(inputFile);
    }

    /**
     * Processes a product and compares it to processed product known to be correct
     *
     * @param inputFile    the path to the input product
     * @throws Exception general exception
     */
    public void processFile(final File inputFile) throws Exception {

        final Product sourceProduct = TestUtils.readSourceProduct(inputFile);

        final MultiTemporalSpeckleFilterOp op = (MultiTemporalSpeckleFilterOp) spi.createOperator();
        assertNotNull(op);
        op.setSourceProduct(sourceProduct);

        // get targetProduct: execute initialize()
        final Product targetProduct = op.getTargetProduct();
        TestUtils.verifyProduct(targetProduct, true, true, true);

        final float[] expected = new float[] { 340.0f,8906.0f,9673.0f };
        TestUtils.comparePixels(targetProduct, targetProduct.getBandAt(0).getName(), expected);
    }

}
