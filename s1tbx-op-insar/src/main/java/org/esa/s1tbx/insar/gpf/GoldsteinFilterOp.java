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
package org.esa.s1tbx.insar.gpf;

import com.bc.ceres.core.ProgressMonitor;
import edu.emory.mathcs.jtransforms.fft.DoubleFFT_1D;
import org.apache.commons.math3.util.FastMath;
import org.esa.snap.core.datamodel.Band;
import org.esa.snap.core.datamodel.Product;
import org.esa.snap.core.datamodel.ProductData;
import org.esa.snap.core.gpf.Operator;
import org.esa.snap.core.gpf.OperatorException;
import org.esa.snap.core.gpf.OperatorSpi;
import org.esa.snap.core.gpf.Tile;
import org.esa.snap.core.gpf.annotations.OperatorMetadata;
import org.esa.snap.core.gpf.annotations.Parameter;
import org.esa.snap.core.gpf.annotations.SourceProduct;
import org.esa.snap.core.gpf.annotations.TargetProduct;
import org.esa.snap.core.util.ProductUtils;
import org.esa.snap.engine_utilities.datamodel.Unit;
import org.esa.snap.engine_utilities.gpf.InputProductValidator;
import org.esa.snap.engine_utilities.gpf.OperatorUtils;
import org.esa.snap.engine_utilities.gpf.ReaderUtils;
import org.esa.snap.engine_utilities.gpf.TileIndex;

import java.awt.*;
import java.util.HashMap;
import java.util.Map;

@OperatorMetadata(alias = "GoldsteinPhaseFiltering",
        category = "Radar/Interferometric/Filtering",
        authors = "Jun Lu, Luis Veci",
        version = "1.0",
        copyright = "Copyright (C) 2014 by Array Systems Computing Inc.",
        description = "Phase Filtering")
public class GoldsteinFilterOp extends Operator {

    @SourceProduct
    private Product sourceProduct;

    @TargetProduct
    private Product targetProduct;

    @Parameter(description = "adaptive filter exponent", interval = "(0, 1]", defaultValue = "1.0",
            label = "Adaptive Filter Exponent in (0,1]")
    private double alpha = 1.0;

    @Parameter(valueSet = {"32", "64", "128", "256"}, defaultValue = "64", label = "FFT Size")
    private String FFTSizeString = "64";

    @Parameter(valueSet = {"3", "5", "7"}, defaultValue = "3", label = "Window Size")
    private String windowSizeString = "3";

    @Parameter(description = "Use coherence mask", defaultValue = "false", label = "Use coherence mask")
    private Boolean useCoherenceMask = false;

    @Parameter(description = "The coherence threshold", interval = "[0, 1]", defaultValue = "0.2",
            label = "Coherence Threshold in [0,1]")
    private double coherenceThreshold = 0.2;

    private int sourceImageWidth = 0;
    private int sourceImageHeight = 0;
    private int FFTSize;
    private int halfFFTSize;
    private int windowSize;
    private int halfWindowSize;
    private double noDataValue = 0;
    private Band cohBand = null;
    private final Map<Band, Band> targetIQPair = new HashMap<>();

    private static final String PRODUCT_SUFFIX = "_Flt";

    /**
     * Initializes this operator and sets the one and only target product.
     * <p>The target product can be either defined by a field of type {@link Product} annotated with the
     * {@link TargetProduct TargetProduct} annotation or
     * by calling {@link #setTargetProduct} method.</p>
     * <p>The framework calls this method after it has created this operator.
     * Any client code that must be performed before computation of tile data
     * should be placed here.</p>
     *
     * @throws OperatorException If an error occurs during operator initialisation.
     * @see #getTargetProduct()
     */
    @Override
    public void initialize() throws OperatorException {
        try {
            final InputProductValidator validator = new InputProductValidator(sourceProduct);
            validator.checkIfSARProduct();
            validator.checkIfCoregisteredStack();
            validator.checkIfSLC();

            FFTSize = Integer.parseInt(FFTSizeString);
            halfFFTSize = FFTSize / 2;

            windowSize = Integer.parseInt(windowSizeString);
            halfWindowSize = windowSize / 2;

            sourceImageWidth = sourceProduct.getSceneRasterWidth();
            sourceImageHeight = sourceProduct.getSceneRasterHeight();

            createTargetProduct();

            if (useCoherenceMask) {
                for (Band band : sourceProduct.getBands()) {
                    if(band.getUnit() != null && band.getUnit().equals(Unit.COHERENCE)) {
                        cohBand = band;
                    }
                }
                if (cohBand == null) {
                    throw new OperatorException("Cannot find coherence band");
                }
            }

        } catch (Exception e) {
            throw new OperatorException(e);
        }
    }

    private void createTargetProduct() {

        targetProduct = new Product(sourceProduct.getName() + PRODUCT_SUFFIX,
                sourceProduct.getProductType(),
                sourceImageWidth,
                sourceImageHeight);

        addSelectedBands();

        ProductUtils.copyProductNodes(sourceProduct, targetProduct);

        if(sourceProduct.getQuicklookBandName() != null) {
            if(targetProduct.getBand(sourceProduct.getQuicklookBandName()) != null) {
                targetProduct.setQuicklookBandName(sourceProduct.getQuicklookBandName());
            }
        }
    }

    /**
     * Add the user selected bands to the target product.
     */
    private void addSelectedBands() {

        String[] sourceBandNames = null;
        final Band[] sourceBands = OperatorUtils.getSourceBands(sourceProduct, sourceBandNames, false);

        int i = 0;
        while (i < sourceBands.length) {

            final Band srcBandI = sourceBands[i];
            final String unit = srcBandI.getUnit();
            String nextUnit = null;
            if (unit == null) {
                throw new OperatorException("band " + srcBandI.getName() + " requires a unit");
            } else if (unit.contains(Unit.DB)) {
                throw new OperatorException("bands in dB are not supported");
            } else if (unit.contains(Unit.IMAGINARY)) {
                throw new OperatorException("I and Q bands should be selected in pairs");
            } else if (unit.contains(Unit.REAL)) {
                if (i + 1 >= sourceBands.length) {
                    throw new OperatorException("I and Q bands should be selected in pairs");
                }
                nextUnit = sourceBands[i + 1].getUnit();
                if (nextUnit == null || !nextUnit.contains(Unit.IMAGINARY)) {
                    throw new OperatorException("I and Q bands should be selected in pairs");
                }
            } else {
                // let other bands such as coherence pass through
                ProductUtils.copyBand(srcBandI.getName(), sourceProduct, targetProduct, true);
                ++i;
                continue;
            }

            final Band targetBandI = targetProduct.addBand(srcBandI.getName(), ProductData.TYPE_FLOAT32);
            targetBandI.setUnit(unit);

            final Band srcBandQ = sourceBands[i + 1];
            final Band targetBandQ = targetProduct.addBand(srcBandQ.getName(), ProductData.TYPE_FLOAT32);
            targetBandQ.setUnit(nextUnit);

            targetIQPair.put(targetBandI, targetBandQ);

            final String suffix = targetBandI.getName().substring(targetBandI.getName().indexOf('_'));
            ReaderUtils.createVirtualIntensityDBBand(targetProduct, targetBandI, targetBandQ, suffix);
            ReaderUtils.createVirtualPhaseBand(targetProduct, targetBandI, targetBandQ, suffix);

            i += 2;
        }
    }

    /**
     * Called by the framework in order to compute the stack of tiles for the given target bands.
     * <p>The default implementation throws a runtime exception with the message "not implemented".</p>
     *
     * @param targetTileMap   The current tiles to be computed for each target band.
     * @param targetRectangle The area in pixel coordinates to be computed (same for all rasters in <code>targetRasters</code>).
     * @param pm              A progress monitor which should be used to determine computation cancelation requests.
     * @throws OperatorException if an error occurs during computation of the target rasters.
     */
    @Override
    public void computeTileStack(Map<Band, Tile> targetTileMap, Rectangle targetRectangle, ProgressMonitor pm)
            throws OperatorException {
        try {
            final int x0 = targetRectangle.x;
            final int y0 = targetRectangle.y;
            final int w = targetRectangle.width;
            final int h = targetRectangle.height;
            if (w < FFTSize || h < FFTSize) {
                return;
            }

            final Rectangle sourceTileRectangle = getSourceRectangle(x0, y0, w, h);
            final int sx0 = sourceTileRectangle.x;
            final int sy0 = sourceTileRectangle.y;
            final int sw = sourceTileRectangle.width;
            final int sh = sourceTileRectangle.height;

            for (Band iBand : targetIQPair.keySet()) {
                final Band qBand = targetIQPair.get(iBand);

                final Tile iTargetTile = targetTileMap.get(iBand);
                final Tile qTargetTile = targetTileMap.get(qBand);

                final Tile iBandRaster = getSourceTile(sourceProduct.getBand(iBand.getName()), sourceTileRectangle);
                final Tile qBandRaster = getSourceTile(sourceProduct.getBand(qBand.getName()), sourceTileRectangle);

                final ProductData iBandData = iBandRaster.getDataBuffer();
                final ProductData qBandData = qBandRaster.getDataBuffer();
                final TileIndex srcIndex = new TileIndex(iBandRaster);
                noDataValue = iBand.getNoDataValue();

                // perform filtering with a sliding window
                final boolean[][] mask = new boolean[FFTSize][FFTSize];
                final double[][] I = new double[FFTSize][FFTSize];
                final double[][] Q = new double[FFTSize][FFTSize];
                final double[][] specI = new double[FFTSize][FFTSize];
                final double[][] specQ = new double[FFTSize][FFTSize];
                final double[][] pwrSpec = new double[FFTSize][FFTSize];
                final double[][] fltSpec = new double[FFTSize][FFTSize];
                final int colMax = I[0].length;

                // arrays saving filtered I/Q data for the tile, note tile size could be different from 512x512 on boundary
                final float[] iBandFiltered = new float[w * h];
                final float[] qBandFiltered = new float[w * h];

                final int stepSize = FFTSize / 4;
                final int syMax = FastMath.min(sy0 + sh - FFTSize, sourceImageHeight - FFTSize);
                final int sxMax = FastMath.min(sx0 + sw - FFTSize, sourceImageWidth - FFTSize);
                for (int y = sy0; y <= syMax; y += stepSize) {
                    for (int x = sx0; x <= sxMax; x += stepSize) {

                        getComplexImagettes(x, y, iBandData, qBandData, srcIndex, I, Q, mask);

                        // check for no data value
                        boolean allNoData = true;
                        for (double[] aI : I) {
                            for (int c = 0; c < colMax; ++c) {
                                if (aI[c] != noDataValue) {
                                    allNoData = false;
                                    break;
                                }
                            }
                            if (!allNoData)
                                break;
                        }
                        if (allNoData) {
                            continue;
                        }

                        perform2DFFT(I, Q, specI, specQ);

                        getPowerSpectrum(specI, specQ, pwrSpec);

                        getFilteredPowerSpectrum(pwrSpec, fltSpec, alpha, halfWindowSize);

                        performInverse2DFFT(specI, specQ, fltSpec, I, Q);

                        updateFilteredBands(x0, y0, w, h, x, y, I, Q, mask, iBandFiltered, qBandFiltered);
                    }
                }

                // mask out pixels with low coherence
                if (cohBand != null) {
                    try {
                        Tile cohBandRaster = getSourceTile(cohBand, targetRectangle);
                        final ProductData cohBandData = cohBandRaster.getDataBuffer();
                        final TileIndex cohIndex = new TileIndex(cohBandRaster);
                        final int yMax = y0 + h;
                        final int xMax = x0 + w;
                        for (int y = y0; y < yMax; y++) {
                            cohIndex.calculateStride(y);
                            for (int x = x0; x < xMax; x++) {
                                final int k = (y - y0) * w + x - x0;
                                if (cohBandData.getElemFloatAt(cohIndex.getIndex(x)) < coherenceThreshold) {
                                    final int idx = iBandRaster.getDataBufferIndex(x, y);
                                    iBandFiltered[k] = iBandData.getElemFloatAt(idx);
                                    qBandFiltered[k] = qBandData.getElemFloatAt(idx);
                                }
                            }
                        }
                    } catch (Exception e) {
                        throw new OperatorException(e);
                    }
                }

                iTargetTile.setRawSamples(new ProductData.Float(iBandFiltered));
                qTargetTile.setRawSamples(new ProductData.Float(qBandFiltered));
            }
        } catch (Exception e) {
            throw new OperatorException(e);
        }
    }

    /**
     * Get the source tile rectangle.
     *
     * @param x0 The x coordinate of the pixel on the upper left corner of current tile.
     * @param y0 The y coordinate of the pixel on the upper left corner of current tile.
     * @param w  The width of current tile.
     * @param h  The height of current tile.
     * @return The rectangle.
     */
    private Rectangle getSourceRectangle(final int x0, final int y0, final int w, final int h) {

        final int FFTSize3_4 = FFTSize * 3 / 4;
        final int sx0 = FastMath.max(x0 - FFTSize3_4, 0);
        final int sy0 = FastMath.max(y0 - FFTSize3_4, 0);
        final int sxMax = FastMath.min(x0 + w - 1 + FFTSize3_4, sourceImageWidth - 1);
        final int syMax = FastMath.min(y0 + h - 1 + FFTSize3_4, sourceImageHeight - 1);
        final int sw = sxMax - sx0 + 1;
        final int sh = syMax - sy0 + 1;

        return new Rectangle(sx0, sy0, sw, sh);
    }

    /**
     * Get source image data for given sliding window
     *
     * @param x           The x coordinate of the upper left pixel in the sliding window
     * @param y           The y coordinate of the upper left pixel in the sliding window
     * @param iBandData The source tile for I band
     * @param qBandData The source tile for Q band
     * @param I           Real parts of the retrieved data
     * @param Q           Imaginary parts of the retrieved data
     */
    private void getComplexImagettes(final int x, final int y,
                                     final ProductData iBandData, final ProductData qBandData,
                                     final TileIndex srcIndex,
                                     final double[][] I, final double[][] Q,
                                     final boolean[][] mask) {
        int index;
        final int maxY = y + FFTSize;
        final int maxX = x + FFTSize;
        for (int yy = y; yy < maxY; yy++) {
            srcIndex.calculateStride(yy);
            final int yidx = yy - y;
            for (int xx = x; xx < maxX; xx++) {
                index = srcIndex.getIndex(xx);
                I[yidx][xx - x] = iBandData.getElemDoubleAt(index);
                Q[yidx][xx - x] = qBandData.getElemDoubleAt(index);
                mask[yidx][xx - x] = I[yidx][xx - x] != noDataValue;
            }
        }
    }

    private static void perform2DFFT(final double[][] I, final double[][] Q,
                                     final double[][] specI, final double[][] specQ) {

        final int rowMax = I.length;
        final int colMax = I[0].length;

        // perform 1-D FFT to each row
        final int rowFFTSize = colMax;
        final int colFFTSize = rowMax;
        final DoubleFFT_1D row_fft = new DoubleFFT_1D(rowFFTSize);
        final double[][] complexDataI = new double[colFFTSize][rowFFTSize];
        final double[][] complexDataQ = new double[colFFTSize][rowFFTSize];
        final double[] rowArray = new double[2 * rowFFTSize];
        for (int r = 0; r < rowMax; r++) {
            int k = 0;
            for (int c = 0; c < colMax; c++) {
                rowArray[k++] = Q[r][c];
                rowArray[k++] = I[r][c];
            }
            row_fft.complexForward(rowArray);
            for (int c = 0; c < rowFFTSize; c++) {
                complexDataQ[r][c] = rowArray[c + c];
                complexDataI[r][c] = rowArray[c + c + 1];
            }
        }

        // perform 1-D FFT to each column
        final DoubleFFT_1D col_fft = new DoubleFFT_1D(colFFTSize);
        final double[] colArray = new double[2 * colFFTSize];
        for (int c = 0; c < colMax; c++) {
            int k = 0;
            for (int r = 0; r < rowMax; r++) {
                colArray[k++] = complexDataQ[r][c];
                colArray[k++] = complexDataI[r][c];
            }
            col_fft.complexForward(colArray);
            for (int r = 0; r < colFFTSize; r++) {
                specQ[r][c] = colArray[r + r];
                specI[r][c] = colArray[r + r + 1];
            }
        }
    }

    private static void getPowerSpectrum(final double[][] specI, final double[][] specQ, final double[][] pwrSpec) {

        final int rowMax = specI.length;
        final int colMax = specI[0].length;

        for (int r = 0; r < rowMax; r++) {
            for (int c = 0; c < colMax; c++) {
                pwrSpec[r][c] = Math.sqrt(specI[r][c] * specI[r][c] + specQ[r][c] * specQ[r][c]);
            }
        }
    }

    private void getFilteredPowerSpectrum(
            final double[][] pwrSpec, final double[][] fltSpec, final double alpha, final int halfWindowSize) {

        final int rowMax = pwrSpec.length;
        final int colMax = pwrSpec[0].length;

        for (int r = 0; r < rowMax; r++) {
            final int jMin = Math.max(0, r - halfWindowSize);
            final int jMax = Math.min(rowMax - 1, r + halfWindowSize);
            for (int c = 0; c < colMax; c++) {
                double sum = 0;
                int k = 0;
                final int iMin = Math.max(0, c - halfWindowSize);
                final int iMax = Math.min(colMax - 1, c + halfWindowSize);
                for (int j = jMin; j <= jMax; j++) {
                    for (int i = iMin; i <= iMax; i++) {
                        if(pwrSpec[j][i] != noDataValue) {
                            sum += pwrSpec[j][i];
                            k++;
                        }
                    }
                }
                if(k != 0) {
                    fltSpec[r][c] = FastMath.pow(sum / k, alpha);
                } else {
                    fltSpec[r][c] = 0;
                }
            }
        }
    }

    private static void performInverse2DFFT(final double[][] specI, final double[][] specQ, final double[][] fltSpec,
                                            final double[][] I, final double[][] Q) {

        final int rowMax = I.length;
        final int colMax = I[0].length;

        final int rowFFTSize = colMax;
        final int colFFTSize = rowMax;
        final double[][] complexDataI = new double[colFFTSize][rowFFTSize];
        final double[][] complexDataQ = new double[colFFTSize][rowFFTSize];

        // perform 1-D FFT to each column
        final DoubleFFT_1D col_fft = new DoubleFFT_1D(colFFTSize);
        final double[] colArray = new double[2 * colFFTSize];
        for (int c = 0; c < colMax; c++) {
            int k = 0;
            for (int r = 0; r < rowMax; r++) {
                colArray[k++] = specQ[r][c] * fltSpec[r][c];
                colArray[k++] = specI[r][c] * fltSpec[r][c];
            }
            col_fft.complexInverse(colArray, false);
            for (int r = 0; r < colFFTSize; r++) {
                complexDataQ[r][c] = colArray[r + r];
                complexDataI[r][c] = colArray[r + r + 1];
            }
        }

        // perform 1-D FFT to each row
        final DoubleFFT_1D row_fft = new DoubleFFT_1D(rowFFTSize);
        final double[] rowArray = new double[2 * rowFFTSize];
        for (int r = 0; r < rowMax; r++) {
            int k = 0;
            for (int c = 0; c < colMax; c++) {
                rowArray[k++] = complexDataQ[r][c];
                rowArray[k++] = complexDataI[r][c];
            }
            row_fft.complexInverse(rowArray, false);
            for (int c = 0; c < rowFFTSize; c++) {
                Q[r][c] = rowArray[c + c];
                I[r][c] = rowArray[c + c + 1];
            }
        }
    }

    /**
     * @param x0            The x coordinate of the pixel on the upper left corner of current tile.
     * @param y0            The y coordinate of the pixel on the upper left corner of current tile.
     * @param w             The width of current tile.
     * @param h             The height of current tile.
     * @param x             The x coordinate of the pixel on the upper left corner of the sliding window.
     * @param y             The y coordinate of the pixel on the upper left corner of the sliding window.
     * @param I             Imaginary part of the filtered imagette.
     * @param Q             Real part of the filtered imagette.
     * @param iBandFiltered Buffer holding imaginary part of the filtered image.
     * @param qBandFiltered Buffer holding real part of the filtered image.
     */
    private void updateFilteredBands(final int x0, final int y0, final int w, final int h,
                                     final int x, final int y, final double[][] I, final double[][] Q,
                                     final boolean[][] mask,
                                     final float[] iBandFiltered, final float[] qBandFiltered) {

        final int xSt = FastMath.max(x, x0);
        final int ySt = FastMath.max(y, y0);
        final int xEd = FastMath.min(x + FFTSize, x0 + w);
        final int yEd = FastMath.min(y + FFTSize, y0 + h);
        for (int yy = ySt; yy < yEd; yy++) {
            final int yi = yy - y;
            final int yw = (yy - y0) * w;
            final double weightY = (1 - Math.abs(yy - y - halfFFTSize + 0.5) / halfFFTSize);
            for (int xx = xSt; xx < xEd; xx++) {

                if(!mask[yi][xx - x]) {
                    continue;
                }

                //final double weight = getTriangularWeight(x, y, xx, yy);
                final double weight = (1 - Math.abs(xx - x - halfFFTSize + 0.5) / halfFFTSize) * weightY;

                final int k = yw + (xx - x0);
                iBandFiltered[k] += I[yi][xx - x] * weight;
                qBandFiltered[k] += Q[yi][xx - x] * weight;
            }
        }
    }

    /**
     * Compute triangular weight
     *
     * @param xUL The x coordinate of the pixel on the upper left corner of the sliding window.
     * @param yUL The y coordinate of the pixel on the upper left corner of the sliding window.
     * @param x   The x coordinate of current pixel in the sliding window
     * @param y   The y coordinate of current pixel in the sliding window
     * @return The weight
     */
    private double getTriangularWeight(final int xUL, final int yUL, final int x, final int y) {
        return (1 - Math.abs(x - xUL - halfFFTSize + 0.5) / halfFFTSize) *
                (1 - Math.abs(y - yUL - halfFFTSize + 0.5) / halfFFTSize);
    }


    /**
     * The SPI is used to register this operator in the graph processing framework
     * via the SPI configuration file
     * {@code META-INF/services/org.esa.snap.core.gpf.OperatorSpi}.
     * This class may also serve as a factory for new operator instances.
     *
     * @see OperatorSpi#createOperator()
     * @see OperatorSpi#createOperator(java.util.Map, java.util.Map)
     */
    public static class Spi extends OperatorSpi {
        public Spi() {
            super(GoldsteinFilterOp.class);
        }
    }
}
