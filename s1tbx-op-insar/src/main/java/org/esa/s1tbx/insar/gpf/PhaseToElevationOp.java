/*
 * Copyright (C) 2016 by Array Systems Computing Inc. http://www.array.ca
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
import org.apache.commons.math3.util.FastMath;
import org.esa.s1tbx.commons.SARUtils;
import org.esa.snap.core.datamodel.*;
import org.esa.snap.core.dataop.dem.ElevationModel;
import org.esa.snap.core.dataop.dem.ElevationModelDescriptor;
import org.esa.snap.core.dataop.dem.ElevationModelRegistry;
import org.esa.snap.core.dataop.resamp.ResamplingFactory;
import org.esa.snap.core.gpf.Operator;
import org.esa.snap.core.gpf.OperatorException;
import org.esa.snap.core.gpf.OperatorSpi;
import org.esa.snap.core.gpf.Tile;
import org.esa.snap.core.gpf.annotations.OperatorMetadata;
import org.esa.snap.core.gpf.annotations.Parameter;
import org.esa.snap.core.gpf.annotations.SourceProduct;
import org.esa.snap.core.gpf.annotations.TargetProduct;
import org.esa.snap.core.util.ProductUtils;
import org.esa.snap.core.util.math.MathUtils;
import org.esa.snap.dem.dataio.DEMFactory;
import org.esa.snap.dem.dataio.FileElevationModel;
import org.esa.snap.engine_utilities.datamodel.AbstractMetadata;
import org.esa.snap.engine_utilities.datamodel.OrbitStateVector;
import org.esa.snap.engine_utilities.datamodel.Unit;
import org.esa.snap.engine_utilities.eo.Constants;
import org.esa.snap.engine_utilities.gpf.InputProductValidator;
import org.esa.snap.engine_utilities.gpf.OperatorUtils;
import org.esa.snap.engine_utilities.gpf.TileIndex;
import org.esa.snap.engine_utilities.util.Maths;
import org.jlinda.core.Baseline;
import org.jlinda.core.Orbit;
import org.jlinda.core.SLCImage;

import java.awt.*;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;


@OperatorMetadata(alias = "PhaseToElevation",
        category = "Radar/Interferometric/Products",
        authors = "Jun Lu, Luis Veci",
        version = "1.0",
        copyright = "Copyright (C) 2016 by Array Systems Computing Inc.",
        description = "DEM Generation")
public final class PhaseToElevationOp extends Operator {

    @SourceProduct(alias = "source")
    private Product sourceProduct;
    @TargetProduct
    private Product targetProduct;

    @Parameter(description = "The digital elevation model.",
            defaultValue = "SRTM 3Sec", label = "Digital Elevation Model")
    private String demName = "SRTM 3Sec";

    @Parameter(defaultValue = ResamplingFactory.BILINEAR_INTERPOLATION_NAME,
            label = "DEM Resampling Method")
    private String demResamplingMethod = ResamplingFactory.BILINEAR_INTERPOLATION_NAME;

    @Parameter(label = "External DEM")
    private File externalDEMFile = null;

    @Parameter(label = "DEM No Data Value", defaultValue = "0")
    private double externalDEMNoDataValue = 0;

    private ElevationModel dem = null;
    private FileElevationModel fileElevationModel = null;
    private TiePointGrid latitudeTPG = null;
    private TiePointGrid longitudeTPG = null;
    private TiePointGrid incidenceAngleTPG = null;
    private TiePointGrid slantRangeTimeTPG = null;

    private int sourceImageWidth = 0;
    private int sourceImageHeight = 0;
    private boolean isElevationModelAvailable = false;
    private boolean refHeightPhaseComputed = false;

    private double waveNumber = 0.0;
    private double refHeight = 0.0;
    private double refPhase = 0.0;

    private double demNoDataValue = 0; // no data value for DEM
    private double[] lookAngles = null;
    private double firstLineUTC = 0.0; // in days
    private OrbitStateVector[] orbitStateVectors = null;

    private final Baseline baseline = new Baseline();

    private Band unwrappedPhaseBand;
    private static final String PRODUCT_SUFFIX = "_Hgt";

    /**
     * Initializes this operator and sets the one and only target product.
     * <p>The target product can be either defined by a field of type {@link Product}
     * annotated with the {@link TargetProduct TargetProduct} annotation or
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
            validator.checkIfMapProjected(false);

            getMetadata();

            getTiePointGrid();

            createTargetProduct();

            if (externalDEMFile == null) {
                DEMFactory.checkIfDEMInstalled(demName);
            }

            DEMFactory.validateDEM(demName, sourceProduct);

            getBaseline();

        } catch (Throwable e) {
            OperatorUtils.catchOperatorException(getId(), e);
        }
    }

    @Override
    public synchronized void dispose() {
        if (dem != null) {
            dem.dispose();
            dem = null;
        }
        if (fileElevationModel != null) {
            fileElevationModel.dispose();
        }
    }

    /**
     * Retrieve required data from Abstracted Metadata
     *
     * @throws Exception if metadata not found
     */
    private void getMetadata() throws Exception {

        final MetadataElement absRoot = AbstractMetadata.getAbstractedMetadata(sourceProduct);
        final double wavelength = SARUtils.getRadarWavelength(absRoot);
        waveNumber = Constants.TWO_PI / wavelength;
        orbitStateVectors = AbstractMetadata.getOrbitStateVectors(absRoot);
        firstLineUTC = absRoot.getAttributeUTC(AbstractMetadata.first_line_time).getMJD(); // in days

        sourceImageWidth = sourceProduct.getSceneRasterWidth();
        sourceImageHeight = sourceProduct.getSceneRasterHeight();
    }

    /**
     * Get incidence angle and slant range time tie point grids.
     */
    private void getTiePointGrid() {

        latitudeTPG = OperatorUtils.getLatitude(sourceProduct);
        if (latitudeTPG == null) {
            throw new OperatorException("Cannot find latitude tie point grid with the source product");
        }

        longitudeTPG = OperatorUtils.getLongitude(sourceProduct);
        if (longitudeTPG == null) {
            throw new OperatorException("Cannot find longitude tie point grid with the source product");
        }

        incidenceAngleTPG = OperatorUtils.getIncidenceAngle(sourceProduct);
        if (incidenceAngleTPG == null) {
            throw new OperatorException("Cannot find incidence angle tie point grid with the source product");
        }

        slantRangeTimeTPG = OperatorUtils.getSlantRangeTime(sourceProduct);
        if (slantRangeTimeTPG == null) {
            throw new OperatorException("Cannot find slant range time tie point grid with the source product");
        }
    }

    /**
     * Create target product.
     */
    private void createTargetProduct() {

        targetProduct = new Product(sourceProduct.getName() + PRODUCT_SUFFIX,
                sourceProduct.getProductType(),
                sourceImageWidth,
                sourceImageHeight);

        addSelectedBands();

        ProductUtils.copyProductNodes(sourceProduct, targetProduct);

        final MetadataElement absTgt = AbstractMetadata.getAbstractedMetadata(targetProduct);

        if (externalDEMFile != null && fileElevationModel == null) { // if external DEM file is specified by user
            AbstractMetadata.setAttribute(absTgt, AbstractMetadata.DEM, externalDEMFile.getPath());
        } else {
            AbstractMetadata.setAttribute(absTgt, AbstractMetadata.DEM, demName);
        }

        absTgt.setAttributeString("DEM resampling method", demResamplingMethod);

        if (externalDEMFile != null) {
            absTgt.setAttributeDouble("external DEM no data value", externalDEMNoDataValue);
        }
    }

    /**
     * Add user selected bands to target product.
     */
    private void addSelectedBands() {

        final Band[] sourceBands = OperatorUtils.getSourceBands(sourceProduct, null, false);
        boolean validProduct = false;
        for (Band band : sourceBands) {
            if (band.getName().toLowerCase().startsWith("unw")) {
                validProduct = true;
                unwrappedPhaseBand = band;
                break;
            }
        }

        if (!validProduct) {
            throw new OperatorException("Cannot find UnwrappedPhase band in the source product.");
        }

        final Band targetBand = new Band("elevation", ProductData.TYPE_FLOAT32,
                sourceImageWidth, sourceImageHeight);

        targetBand.setUnit(Unit.METERS);
        targetProduct.addBand(targetBand);
    }

    private void getBaseline() throws Exception {
        final MetadataElement masterMeta = AbstractMetadata.getAbstractedMetadata(sourceProduct);
        final SLCImage masterMetaData = new SLCImage(masterMeta, sourceProduct);
        final Orbit masterOrbit = new Orbit(masterMeta, 3);

        final MetadataElement[] slaveRoot = sourceProduct.getMetadataRoot().
                getElement(AbstractMetadata.SLAVE_METADATA_ROOT).getElements();
        final SLCImage slaveMetaData = new SLCImage(slaveRoot[0], sourceProduct);
        final Orbit slaveOrbit = new Orbit(slaveRoot[0], 3);

        baseline.model(masterMetaData, slaveMetaData, masterOrbit, slaveOrbit);
    }

    /**
     * Called by the framework in order to compute the stack of tiles for the given target bands.
     * <p>The default implementation throws a runtime exception with the message "not implemented".</p>
     *
     * @param targetTiles     The current tiles to be computed for each target band.
     * @param targetRectangle The area in pixel coordinates to be computed (same for all rasters in <code>targetRasters</code>).
     * @param pm              A progress monitor which should be used to determine computation cancelation requests.
     * @throws OperatorException if an error occurs during computation of the target rasters.
     */
    @Override
    public void computeTileStack(Map<Band, Tile> targetTiles, Rectangle targetRectangle, ProgressMonitor pm)
            throws OperatorException {

        try {
            final Band sourceBand = unwrappedPhaseBand;
            final Tile sourceTile = getSourceTile(sourceBand, targetRectangle);
            final ProductData sourceData = sourceTile.getDataBuffer();

            final Band targetBand = targetProduct.getBand("elevation");
            final Tile targetTile = targetTiles.get(targetBand);
            final ProductData targetData = targetTile.getDataBuffer();
            final TileIndex srcIndex = new TileIndex(sourceTile);
            final TileIndex trgIndex = new TileIndex(targetTile);

            if (!isElevationModelAvailable) {
                getElevationModel();
            }

            if (!refHeightPhaseComputed) {
                computeReferenceHeightAndPhase(sourceBand, baseline);
            }

            final int x0 = targetRectangle.x;
            final int y0 = targetRectangle.y;
            final int w = targetRectangle.width;
            final int h = targetRectangle.height;
            // System.out.println("x0 = " + x0 + ", y0 = " + y0 + ", w = " + w + ", h = " + h);

            final int xc = sourceImageWidth / 2;
            double phase, slantRange, incidenceAngle, bn, bp, alpha, height, flatAngle;
            for (int y = y0; y < y0 + h; y++) {
                srcIndex.calculateStride(y);
                trgIndex.calculateStride(y);
                for (int x = x0; x < x0 + w; x++) {

                    phase = sourceData.getElemDoubleAt(srcIndex.getIndex(x));
                    slantRange = slantRangeTimeTPG.getPixelDouble(x, y) / Constants.oneBillion * Constants.halfLightSpeed;
                    incidenceAngle = incidenceAngleTPG.getPixelDouble(x, y) * MathUtils.DTOR;
                    bn = baseline.getBperp(y, x);
                    bp = baseline.getBpar(y, x);
                    flatAngle = lookAngles[x] - lookAngles[xc];
                    alpha = -slantRange * FastMath.sin(incidenceAngle) /
                            (2 * waveNumber * (bp * FastMath.sin(flatAngle) + bn * FastMath.cos(flatAngle)));
//                  alpha = -slantRange*FastMath.sin(incidenceAngle)/(2*waveNumber*bn);
                    height = refHeight + alpha * (phase - refPhase);
                    targetData.setElemDoubleAt(trgIndex.getIndex(x), height);
                }
            }

        } catch (Throwable e) {
            OperatorUtils.catchOperatorException(getId(), e);
        }
    }

    /**
     * Get elevation model.
     *
     * @throws Exception The exceptions.
     */
    private synchronized void getElevationModel() throws Exception {

        if (isElevationModelAvailable) {
            return;
        }

        if (externalDEMFile != null && fileElevationModel == null) { // if external DEM file is specified by user

            fileElevationModel = new FileElevationModel(externalDEMFile, demResamplingMethod, externalDEMNoDataValue);
            demNoDataValue = externalDEMNoDataValue;
            demName = externalDEMFile.getPath();

        } else {

            final ElevationModelRegistry elevationModelRegistry = ElevationModelRegistry.getInstance();
            final ElevationModelDescriptor demDescriptor = elevationModelRegistry.getDescriptor(demName);
            if (demDescriptor == null) {
                throw new OperatorException("The DEM '" + demName + "' is not supported.");
            }

            dem = demDescriptor.createDem(ResamplingFactory.createResampling(demResamplingMethod));
            if (dem == null) {
                throw new OperatorException("The DEM '" + demName + "' has not been installed.");
            }

            demNoDataValue = dem.getDescriptor().getNoDataValue();
        }
        isElevationModelAvailable = true;
    }

    private synchronized void computeReferenceHeightAndPhase(final Band unwrappedPhaseBand, final Baseline baseline)
            throws Exception {

        if (refHeightPhaseComputed) {
            return;
        }

        computeLookAngles();

        // get initial 100x100 seeds and compute their slopes
        final int seedGridSize = 100;
        final int slopeCalRadius = 4;
        final int seedGridResY = (sourceImageHeight - 1 - 2 * slopeCalRadius) / (seedGridSize - 1);
        final int seedGridResX = (sourceImageWidth - 1 - 2 * slopeCalRadius) / (seedGridSize - 1);
        List<SeedRecord> seedList = new ArrayList<>(seedGridSize * seedGridSize);

        for (int r = 0; r < seedGridSize; r++) {
            final int y = r * seedGridResY + slopeCalRadius;
            for (int c = 0; c < seedGridSize; c++) {
                final int x = c * seedGridResX + slopeCalRadius;
                final Double h = getElevation(x, y);
                if (!h.equals(demNoDataValue) && h > 0.0) {
                    SeedRecord seed = new SeedRecord(x, y, h, computeSlope(x, y, slopeCalRadius));
                    seedList.add(seed);
                }
            }
        }

        // sort the seed list in ascending order according to the seed's slope
        Collections.sort(seedList);

        // get the final seed list
        final int maskSize = 15;
        final int totalFinalSeeds = 150;
        boolean[][] mask = new boolean[maskSize][maskSize];
        SeedRecord[] finalSeedList = new SeedRecord[totalFinalSeeds];
        int numSeeds = 0;
        for (SeedRecord seed : seedList) {
            int maskX = (int) ((double) seed.x / sourceImageWidth * maskSize);
            int maskY = (int) ((double) seed.y / sourceImageHeight * maskSize);
            if (!mask[maskY][maskX]) {
                finalSeedList[numSeeds++] = new SeedRecord(seed.x, seed.y, seed.height, seed.slope);
                if (numSeeds >= totalFinalSeeds) {
                    break;
                }
                mask[maskY][maskX] = true;
            }
        }

        // get unwrapped phases for seeds in the final seed list
        final double[] phaseList = new double[numSeeds];
        for (int i = 0; i < numSeeds; i++) {
            SeedRecord seed = finalSeedList[i];
            final Rectangle srcRect = new Rectangle(seed.x, seed.y, 1, 1);
            final Tile sourceTile = getSourceTile(unwrappedPhaseBand, srcRect);
            phaseList[i] = sourceTile.getDataBuffer().getElemDoubleAt(sourceTile.getDataBufferIndex(seed.x, seed.y));
        }

        // Compute reference (elevation, phase) using least square method
        final int xc = sourceImageWidth / 2;
        double phase, slantRange, incidenceAngle, bn, bp, alpha, flatAngle;
        double a = 0.0, b = 0.0, c = 0.0, d = 0.0, e = 0.0, f = 0.0;
        for (int i = 0; i < numSeeds; i++) {
            SeedRecord seed = finalSeedList[i];
            phase = phaseList[i];
            slantRange = slantRangeTimeTPG.getPixelDouble(seed.x, seed.y) / Constants.oneBillion * Constants.halfLightSpeed;
            incidenceAngle = incidenceAngleTPG.getPixelDouble(seed.x, seed.y) * MathUtils.DTOR;
            bn = baseline.getBperp(seed.y, seed.x);
            bp = baseline.getBpar(seed.y, seed.x);
            flatAngle = lookAngles[seed.x] - lookAngles[xc];
            alpha = -slantRange * FastMath.sin(incidenceAngle) /
                    (2 * waveNumber * (bp * FastMath.sin(flatAngle) + bn * FastMath.cos(flatAngle)));
//            alpha = -slantRange*Math.sin(incidenceAngle)/(2*waveNumber*bn);
            a += -alpha * alpha;
            b += alpha;
            e += alpha * (seed.height - alpha * phase);
            f += seed.height - alpha * phase;
        }
        c = -b;
        d = numSeeds;

        refHeight = (a * f - c * e) / (a * d - c * b);
        refPhase = (e * d - b * f) / (a * d - c * b);

        refHeightPhaseComputed = true;
    }

    private synchronized void computeLookAngles() {

        double[] senPos = new double[3];
        getSensorPosition(firstLineUTC, senPos);

        final double ht = Math.sqrt(senPos[0] * senPos[0] + senPos[1] * senPos[1] + senPos[2] * senPos[2]); // satelliteHeight
        final double er = computeEarthRadius(senPos[2], ht);  // earthRadius

        lookAngles = new double[sourceImageWidth];
        for (int x = 0; x < sourceImageWidth; x++) {
            final double sr = slantRangeTimeTPG.getPixelDouble(x, 0) / Constants.oneBillion * Constants.halfLightSpeed;
            lookAngles[x] = FastMath.acos((sr * sr + ht * ht - er * er) / (2.0 * sr * ht));
        }
    }

    private void getSensorPosition(final double time, double[] senPos) {

        final int numVectors = orbitStateVectors.length;
        final int numVectorsUsed = Math.min(orbitStateVectors.length, 5);
        final int d = numVectors / numVectorsUsed;
        final double[] timeArray = new double[numVectorsUsed];
        final double[] xPosArray = new double[numVectorsUsed];
        final double[] yPosArray = new double[numVectorsUsed];
        final double[] zPosArray = new double[numVectorsUsed];
        for (int i = 0; i < numVectorsUsed; i++) {
            timeArray[i] = orbitStateVectors[i * d].time_mjd;
            xPosArray[i] = orbitStateVectors[i * d].x_pos; // m
            yPosArray[i] = orbitStateVectors[i * d].y_pos; // m
            zPosArray[i] = orbitStateVectors[i * d].z_pos; // m
        }
        senPos[0] = Maths.lagrangeInterpolatingPolynomial(timeArray, xPosArray, time);
        senPos[1] = Maths.lagrangeInterpolatingPolynomial(timeArray, yPosArray, time);
        senPos[2] = Maths.lagrangeInterpolatingPolynomial(timeArray, zPosArray, time);
    }

    private static double computeEarthRadius(final double senPosZ, final double satelliteHeight) {
        final double re = Constants.semiMajorAxis;
        final double rp = Constants.semiMinorAxis;
        final double lat = FastMath.asin(senPosZ / satelliteHeight);
        return (re * rp) / Math.sqrt(rp * rp * FastMath.cos(lat) * FastMath.cos(lat) + re * re * FastMath.sin(lat) * FastMath.sin(lat));
    }

    private double getElevation(final int x, final int y) throws Exception {

        final GeoPos geoPos = new GeoPos();
        double alt;
        geoPos.setLocation(latitudeTPG.getPixelDouble(x, y), longitudeTPG.getPixelDouble(x, y));
        if (externalDEMFile == null) {
            alt = dem.getElevation(geoPos);
        } else {
            alt = fileElevationModel.getElevation(geoPos);
        }

        return alt;
    }

    private double computeSlope(final int xc, final int yc, final int slopeCalRadius) throws Exception {

        double slope = 0.0;
        Double h = 0.0;
        int numPoints = 0;
        final double hc = getElevation(xc, yc);
        final int halfSlopeCalRadius = slopeCalRadius / 2;
        for (int y = yc - slopeCalRadius; y <= yc + slopeCalRadius; y += slopeCalRadius) {
            for (int x = xc - slopeCalRadius; x <= xc + slopeCalRadius; x += halfSlopeCalRadius) {
                h = getElevation(x, y);
                if (!h.equals(demNoDataValue)) {
                    slope += Math.abs(h - hc);
                    numPoints++;
                }
            }
        }
        return slope / numPoints;
    }

    static class SeedRecord implements Comparable<SeedRecord> {
        public int x;
        public int y;
        public double height;
        public double slope;

        SeedRecord(final int x, final int y, final double h, final double slope) {
            this.x = x;
            this.y = y;
            this.height = h;
            this.slope = slope;
        }

        public int compareTo(SeedRecord record) {
            double slopeCmp = slope - record.slope;
            return (slopeCmp < 0 ? -1 : +1);
        }
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
            super(PhaseToElevationOp.class);
        }
    }
}
