package org.esa.s1tbx.sar.gpf.geometric;

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
 * Created by lveci on 24/10/2014.
 */
public class TestMosaic {

    private final static OperatorSpi spi = new MosaicOp.Spi();

    private final static File inputFile1 = TestData.inputASAR_IMM;
    private final static File inputFile2 = TestData.inputASAR_IMMSub;

    @Before
    public void setUp() {
        // If any of the file does not exist: the test will be ignored
        //assumeTrue(inputFile1 + " not found", inputFile1.exists());
        //assumeTrue(inputFile2 + " not found", inputFile2.exists());
    }

    /**
     * Processes a product and compares it to processed product known to be correct
     *
     * @throws Exception general exception
     */
    @Test
    public void testProcessing() throws Exception {

        final Product sourceProduct1 = TestUtils.readSourceProduct(inputFile1);
        final Product sourceProduct2 = TestUtils.readSourceProduct(inputFile2);

        final MosaicOp op = (MosaicOp) spi.createOperator();
        assertNotNull(op);
        op.setSourceProducts(sourceProduct1,sourceProduct2);

        // get targetProduct: execute initialize()
        final Product targetProduct = op.getTargetProduct();
        TestUtils.verifyProduct(targetProduct, false, true, true);
    }
}
