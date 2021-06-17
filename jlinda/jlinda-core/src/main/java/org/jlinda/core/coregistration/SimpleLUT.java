package org.jlinda.core.coregistration;

import org.esa.snap.core.util.SystemUtils;
import org.jblas.DoubleMatrix;

import java.util.logging.Logger;

public class SimpleLUT extends LUT {

    private static final Logger logger = SystemUtils.LOG;

    public SimpleLUT(String method) {
        super(method);
    }

    public SimpleLUT(String method, int kernelLength) {
        super(method, kernelLength);
    }

    @Override
    public void constructLUT() {

        double[] kernelAxis = defineAxis(kernelLength);

        // temp matrices
        DoubleMatrix kernelTmp;

        // initialization
        axis = new DoubleMatrix(kernelAxis); // static for simple LUT
        kernel = new DoubleMatrix(nInterval, kernelLength);

        for (int i = 0; i < nInterval; i++) {
            switch (method) {
                case RECT:
                    kernelTmp = new DoubleMatrix(rect(kernelAxis));
                    kernel.putRow(i, kernelTmp);
                    break;
                case TRI:
                    kernelTmp = new DoubleMatrix(tri(kernelAxis));
                    kernel.putRow(i, kernelTmp);
                    break;
                case TS6P:
                    kernelTmp = new DoubleMatrix(ts6(kernelAxis));
                    kernel.putRow(i, kernelTmp);
                    break;
                case TS8P:
                    kernelTmp = new DoubleMatrix(ts8(kernelAxis));
                    kernel.putRow(i, kernelTmp);
                    break;
                case TS16P:
                    kernelTmp = new DoubleMatrix(ts16(kernelAxis));
                    kernel.putRow(i, kernelTmp);
                    break;
                case CC4P:
                    kernelTmp = new DoubleMatrix(cc4(kernelAxis));
                    kernel.putRow(i, kernelTmp);
                    break;
                case CC6P:
                    kernelTmp = new DoubleMatrix(cc6(kernelAxis));
                    kernel.putRow(i, kernelTmp);
                    break;
            }

            kernelAxis = new DoubleMatrix(kernelAxis).sub(dx).toArray();

        }

        // normalization and back to array
        kernel.divColumnVector(kernel.rowSums());

    }

    @Override
    public void overviewLUT() {

        logger.info("Overview of Simplified LUT for interpolation");
        logger.info("--------------------------------------------");

        for (int i = 0; i < nInterval; ++i) {

            // logger
            logger.info("Kernel row [{}]: {} :"+ i+ kernel.getRow(i).toString());

        }

    }

}
