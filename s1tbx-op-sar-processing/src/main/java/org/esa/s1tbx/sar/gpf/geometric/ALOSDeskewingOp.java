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
package org.esa.s1tbx.sar.gpf.geometric;

import com.bc.ceres.core.ProgressMonitor;
import org.apache.commons.math3.util.FastMath;
import org.esa.s1tbx.commons.OrbitStateVectors;
import org.esa.s1tbx.commons.SARGeocoding;
import org.esa.s1tbx.commons.SARUtils;
import org.esa.snap.core.datamodel.*;
import org.esa.snap.core.dataop.dem.ElevationModel;
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
import org.esa.snap.dem.dataio.DEMFactory;
import org.esa.snap.engine_utilities.datamodel.AbstractMetadata;
import org.esa.snap.engine_utilities.datamodel.OrbitStateVector;
import org.esa.snap.engine_utilities.datamodel.PosVector;
import org.esa.snap.engine_utilities.eo.Constants;
import org.esa.snap.engine_utilities.eo.GeoUtils;
import org.esa.snap.engine_utilities.gpf.OperatorUtils;
import org.esa.snap.engine_utilities.gpf.TileIndex;
import org.esa.snap.engine_utilities.util.Maths;

import java.awt.*;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Skew correction for ALOS product
 * Reference: ALOS-PALSAR-FAQ-001, ESRIN Contract No.20700/07/I-OL, IDEAS QC PALSAR Team
 */

@OperatorMetadata(alias = "ALOS-Deskewing",
        category = "Radar/Geometric",
        authors = "Jun Lu, Luis Veci",
        version = "1.0",
        copyright = "Copyright (C) 2014 by Array Systems Computing Inc.",
        description = "Deskewing ALOS product")
public class ALOSDeskewingOp extends Operator {

    public static final String PRODUCT_SUFFIX = "_DSk";

    @SourceProduct(alias = "source")
    Product sourceProduct;
    @TargetProduct
    Product targetProduct;

    @Parameter(description = "The list of source bands.", alias = "sourceBands",
            rasterDataNodeType = Band.class, label = "Source Bands")
    private String[] sourceBandNames = null;

    @Parameter(description = "The digital elevation model.",
            defaultValue = "SRTM 3Sec",
            label = "Digital Elevation Model")
    private String demName = "SRTM 3Sec";

    //@Parameter(defaultValue="false", label="Use Mapready Shift Only")
    boolean useMapreadyShiftOnly = false;

    //@Parameter(defaultValue="false", label="Use FAQ Shift Only")
    boolean useFAQShiftOnly = false;

    //@Parameter(defaultValue="false", label="Use Mapready + FAQ Shift")
    boolean useBoth = false;

    //@Parameter(defaultValue="false", label="Use Mapready + Hybrid Shift")
    boolean useHybrid = true;

    private int sourceImageWidth = 0;
    private int sourceImageHeight = 0;
    private double absShift = 0;
    private double fracShift = 0.0;

    private OrbitStateVector[] orbitStateVectors = null;
    private double firstLineTime = 0.0;
    private double lineTimeInterval = 0.0;
    private double lastLineTime = 0.0;
    private AbstractMetadata.DopplerCentroidCoefficientList[] dopplerCentroidCoefficientLists = null;
    private double rangeSpacing = 0.0;
    private double azimuthSpacing = 0.0;
    private double slantRangeToFirstPixel = 0.0;
    private double radarWaveLength = 0.0;
    private OrbitStateVectors orbit = null;

    private final HashMap<String, String[]> targetBandNameToSourceBandName = new HashMap<>();

    private final static double AngularVelocity = getAngularVelocity();

    /*
     Note:
        if useMapreadyShiftOnly is true, the shift is computed using MapReady method;
        if useFAQShiftOnly is true, the shift is computed using method from FAQ;
        if useBoth is true, the shift for each pixel has two parts, one is the shift computed by using MapReady method,
           the second part is the shift computed using FAQ method;
        if useHybrid is true, the shift for each pixel has two parts, one is the shift computed by using MapReady
           method, the second part is a constant shift computed for pixel (0,0) using zero Doppler time.
     */

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
            if (!useMapreadyShiftOnly && !useFAQShiftOnly && !useBoth && !useHybrid) {
                throw new OperatorException("No method was selected for shift calculation");
            }

            getMetadata();

            computeSensorPositionsAndVelocities();

            createTargetProduct();

            computeShift();

            updateTargetProductMetadata();

        } catch (Throwable e) {
            OperatorUtils.catchOperatorException(getId(), e);
        }
    }

    /**
     * Retrieve required data from Abstracted Metadata
     *
     * @throws Exception if metadata not found
     */
    private void getMetadata() throws Exception {
        sourceImageWidth = sourceProduct.getSceneRasterWidth();
        sourceImageHeight = sourceProduct.getSceneRasterHeight();

        final MetadataElement absRoot = AbstractMetadata.getAbstractedMetadata(sourceProduct);

        final String mission = absRoot.getAttributeString(AbstractMetadata.MISSION);
        if (!mission.equals("ALOS")) {
            throw new OperatorException("The deskewing operator is for ALOS PALSAR 1 products only");
        }

        orbitStateVectors = AbstractMetadata.getOrbitStateVectors(absRoot);
        if (orbitStateVectors == null) {
            throw new OperatorException("Invalid Obit State Vectors");
        } else if (orbitStateVectors.length < 2) {
            throw new OperatorException("Not enough orbit state vectors");
        }

        firstLineTime = absRoot.getAttributeUTC(AbstractMetadata.first_line_time).getMJD();

        lastLineTime = absRoot.getAttributeUTC(AbstractMetadata.last_line_time).getMJD();

        lineTimeInterval = absRoot.getAttributeDouble(AbstractMetadata.line_time_interval) / Constants.secondsInDay; // s to day

        dopplerCentroidCoefficientLists = AbstractMetadata.getDopplerCentroidCoefficients(absRoot);

        rangeSpacing = AbstractMetadata.getAttributeDouble(absRoot, AbstractMetadata.range_spacing);

        azimuthSpacing = AbstractMetadata.getAttributeDouble(absRoot, AbstractMetadata.azimuth_spacing);

        slantRangeToFirstPixel = AbstractMetadata.getAttributeDouble(absRoot, AbstractMetadata.slant_range_to_first_pixel);

        radarWaveLength = SARUtils.getRadarWavelength(absRoot);
    }

    /**
     * Compute sensor position and velocity for each range line.
     */
    private void computeSensorPositionsAndVelocities() {

        orbit = new OrbitStateVectors(orbitStateVectors, firstLineTime, lineTimeInterval, sourceImageHeight);
    }

    /**
     * Create target product.
     */
    private void createTargetProduct() {

        targetProduct = new Product(sourceProduct.getName(),
                sourceProduct.getProductType(),
                sourceImageWidth,
                sourceImageHeight);

        OperatorUtils.addSelectedBands(
                sourceProduct, sourceBandNames, targetProduct, targetBandNameToSourceBandName, false, false);

        ProductUtils.copyProductNodes(sourceProduct, targetProduct);

        if(sourceBandNames == null || sourceBandNames.length == 0) {
            for (Band srcBand : sourceProduct.getBands()) {
                if (srcBand instanceof VirtualBand) {
                    ProductUtils.copyVirtualBand(targetProduct, (VirtualBand) srcBand, srcBand.getName());
                }
            }
        }
    }

    private void updateTargetProductMetadata() {

        final MetadataElement absTgt = AbstractMetadata.getAbstractedMetadata(targetProduct);

        final double fd = getDopplerFrequency(0);

        final stateVector v = getOrbitStateVector(firstLineTime);

        final double vel = Math.sqrt(v.xVel * v.xVel + v.yVel * v.yVel + v.zVel * v.zVel);

        final double newSlantRangeToFirstPixel = FastMath.cos(FastMath.asin(fd * radarWaveLength / (2.0 * vel))) * slantRangeToFirstPixel;

        AbstractMetadata.setAttribute(absTgt, AbstractMetadata.slant_range_to_first_pixel, newSlantRangeToFirstPixel);
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
    public void computeTileStack(Map<Band, Tile> targetTiles, Rectangle targetRectangle, ProgressMonitor pm) throws OperatorException {

        try {
            final int tx0 = targetRectangle.x;
            final int ty0 = targetRectangle.y;
            final int tw = targetRectangle.width;
            final int th = targetRectangle.height;
            final int tyMax = ty0 + th;
            final int txMax = tx0 + tw;
            //System.out.println("x0 = " + tx0 + ", y0 = " + ty0 + ", w = " + tw + ", h = " + th);

            final int maxShift = (int) computeMaxShift(txMax, ty0);

            final Rectangle sourceRectangle = getSourceRectangle(tx0, ty0, tw, th, maxShift);
            final int sx0 = sourceRectangle.x;
            final int sy0 = sourceRectangle.y;
            final int sw = sourceRectangle.width;
            final int sh = sourceRectangle.height;
            final int syMax = sy0 + sh;
            final int sxMax = sx0 + sw;

            final Set<Band> keySet = targetTiles.keySet();
            double totalShift;
            for (Band targetBand : keySet) {

                final Tile targetTile = targetTiles.get(targetBand);
                final String srcBandName = targetBandNameToSourceBandName.get(targetBand.getName())[0];
                final Tile sourceTile = getSourceTile(sourceProduct.getBand(srcBandName), sourceRectangle);
                final ProductData trgDataBuffer = targetTile.getDataBuffer();
                final ProductData srcDataBuffer = sourceTile.getDataBuffer();
                final TileIndex srcIndex = new TileIndex(sourceTile);

                for (int y = sy0; y < syMax; y++) {
                    srcIndex.calculateStride(y);
                    final stateVector v = getOrbitStateVector(firstLineTime + y * lineTimeInterval);
                    for (int x = sx0; x < sxMax; x++) {

                        if (useMapreadyShiftOnly) {
                            totalShift = FastMath.round(fracShift * x);
                        } else if (useFAQShiftOnly) {
                            totalShift = computeFAQShift(v, x);
                        } else if (useBoth) {
                            totalShift = computeFAQShift(v, x) + FastMath.round(fracShift * x);
                        } else if (useHybrid) {
                            totalShift = absShift + FastMath.round(fracShift * x);
                        } else {
                            throw new OperatorException("No method was selected for shift calculation");
                        }

                        final int newy = y + (int) totalShift;
                        if (newy >= ty0 && newy < tyMax) {
                            final int trgIdx = targetTile.getDataBufferIndex(x, newy);
                            trgDataBuffer.setElemFloatAt(trgIdx, srcDataBuffer.getElemFloatAt(srcIndex.getIndex(x)));
                        }
                    }
                }
            }

        } catch (Throwable e) {
            OperatorUtils.catchOperatorException(getId(), e);
        }
    }

    private double computeMaxShift(final int txMax, final int ty0) throws Exception {

        if (useMapreadyShiftOnly) {
            return FastMath.round(txMax * fracShift);
        } else if (useFAQShiftOnly) {
            final stateVector v = getOrbitStateVector(firstLineTime + ty0 * lineTimeInterval);
            return computeFAQShift(v, txMax) + FastMath.round(txMax * fracShift);
        } else { // hybrid
            return absShift + FastMath.round(txMax * fracShift);
        }
    }

    private Rectangle getSourceRectangle(final int tx0, final int ty0, final int tw, final int th, final int maxShift) {

        final int sx0 = tx0;
        final int sw = tw;

        int sy0, syMax;
        if (maxShift > 0) {
            sy0 = Math.max(ty0 - maxShift, 0);
            syMax = ty0 + th - 1;
        } else if (maxShift < 0) {
            sy0 = ty0;
            syMax = Math.min(ty0 + th - 1 - maxShift, sourceImageHeight - 1);
        } else { // maxShift == 0
            sy0 = ty0;
            syMax = ty0 + th - 1;
        }

        final int sh = syMax - sy0 + 1;
        return new Rectangle(sx0, sy0, sw, sh);
    }

    private double computeFAQShift(final stateVector v, final int x) throws Exception {

        final double slr = slantRangeToFirstPixel + x * rangeSpacing;
        final double fd = getDopplerFrequency(x);
        final double vel = Math.sqrt(v.xVel * v.xVel + v.yVel * v.yVel + v.zVel * v.zVel);
        return slr * fd * radarWaveLength / (2.0 * vel * azimuthSpacing);
    }

    /**
     * Compute deskewing shift for pixel at (0,0).
     *
     * @throws Exception The exceptions.
     */
    private void computeShift() throws Exception {

        if (useFAQShiftOnly) {
            return;
        }

        final stateVector v = getOrbitStateVector(firstLineTime);
        final double slr = slantRangeToFirstPixel + 0 * rangeSpacing;
        final double fd = getDopplerFrequency(0);

        // fractional shift
        final double[] lookYaw = new double[2];
        computeLookYawAngles(v, slr, fd, lookYaw);
        fracShift = FastMath.sin(lookYaw[0]) * FastMath.sin(lookYaw[1]);

        if (useMapreadyShiftOnly) {
            return;
        }

        // compute absolute shift
        final String demResamplingMethod = ResamplingFactory.BILINEAR_INTERPOLATION_NAME;
        DEMFactory.validateDEM(demName, sourceProduct);
        ElevationModel dem = DEMFactory.createElevationModel(demName, demResamplingMethod);
        final float demNoDataValue = dem.getDescriptor().getNoDataValue();

        GeoPos geoPos = new GeoPos();
        for (int y = 0; y < sourceImageHeight; y++) {
            sourceProduct.getSceneGeoCoding().getGeoPos(new PixelPos(0.5f, y + 0.5f), geoPos);
            final double lat = geoPos.lat;
            double lon = geoPos.lon;
            if (lon >= 180.0) {
                lon -= 360.0;
            }

            final Double alt = dem.getElevation(new GeoPos(lat, lon));
            if (alt.equals(demNoDataValue)) {
                continue;
            }

            final PosVector earthPoint = new PosVector();
            final PosVector sensorPos = new PosVector();
            GeoUtils.geo2xyzWGS84(geoPos.getLat(), geoPos.getLon(), alt, earthPoint);

            final double zeroDopplerTime = SARGeocoding.getEarthPointZeroDopplerTime(
                    firstLineTime, lineTimeInterval, radarWaveLength, earthPoint, orbit.sensorPosition, orbit.sensorVelocity);

            if (zeroDopplerTime == SARGeocoding.NonValidZeroDopplerTime) {
                continue;
            }

            final double slantRange = SARGeocoding.computeSlantRange(zeroDopplerTime, orbit, earthPoint, sensorPos);

            final double zeroDopplerTimeWithoutBias =
                    zeroDopplerTime + slantRange / Constants.lightSpeedInMetersPerDay;

            absShift = (zeroDopplerTimeWithoutBias - firstLineTime) / lineTimeInterval - y;
            return;

        }
        absShift = computeFAQShift(v, 0);
    }

    /**
     * Get orbit state vector for given time.
     *
     * @param time The given time.
     * @return Orbit state vector.
     */
    private stateVector getOrbitStateVector(final double time) {
        OrbitStateVectors.PositionVelocity pv = orbit.getPositionVelocity(time);
        return new stateVector(time, pv.position.x, pv.position.y, pv.position.z, pv.velocity.x, pv.velocity.y, pv.velocity.z);
    }

    /**
     * Compute orbit state vector for given time using interpolation of two given vectors.
     *
     * @param v1   First given vector.
     * @param v2   Second given vector.
     * @param time The given time.
     * @return The interpolated vector.
     */
    private static stateVector vectorInterpolation(final OrbitStateVector v1,
                                                   final OrbitStateVector v2,
                                                   final double time) {

        final double[] pos1 = {v1.x_pos, v1.y_pos, v1.z_pos};
        final double[] vel1 = {v1.x_vel, v1.y_vel, v1.z_vel};
        final double[] pos2 = {v2.x_pos, v2.y_pos, v2.z_pos};
        final double[] vel2 = {v2.x_vel, v2.y_vel, v2.z_vel};

        final double t = time;
        final double t1 = v1.time.getMJD();
        final double t2 = v2.time.getMJD();
        final double dt = t2 - t1;
        final double alpha = (t - t1) / dt;
        final double alpha2 = alpha * alpha;
        final double alpha3 = alpha2 * alpha;

        final double[] pos = new double[3];
        final double[] vel = new double[3];
        double a0, a1, a2, a3;
        for (int i = 0; i < 3; i++) {
            a0 = pos1[i];
            a1 = vel1[i] * dt;
            a2 = -3 * pos1[i] + 3 * pos2[i] - 2 * vel1[i] * dt - vel2[i] * dt;
            a3 = 2 * pos1[i] - 2 * pos2[i] + vel1[i] * dt + vel2[i] * dt;
            pos[i] = a0 + a1 * alpha + a2 * alpha2 + a3 * alpha3;
            vel[i] = (a1 + 2 * a2 * alpha + 3 * a3 * alpha2) / dt;
        }

        return new stateVector(time, pos[0], pos[1], pos[2], vel[0], vel[1], vel[2]);
    }

    private double getDopplerFrequency(final int x) {

        return dopplerCentroidCoefficientLists[0].coefficients[0] +
                dopplerCentroidCoefficientLists[0].coefficients[1] * x +
                dopplerCentroidCoefficientLists[0].coefficients[2] * x * x;
    }

    private void computeLookYawAngles(final stateVector v, final double slant, final double dopp, double[] lookYaw) {

        int iterations = 0, max_iter = 10000;
        double yaw = 0, deltaAz;
        final double[] look = {0};
        double dopGuess, deltaDop, prevDeltaDop = -9999999;
        final double[] vRel = new double[3];

        final double lambda = radarWaveLength;

        while (true) {

            double relativeVelocity;

            boolean succeed = getLook(v, slant, yaw, look);
            if (!succeed) {
                break;
            }

            dopGuess = getDoppler(v, look[0], yaw, vRel, radarWaveLength);

            deltaDop = dopp - dopGuess;
            relativeVelocity = Math.sqrt(vRel[0] * vRel[0] + vRel[1] * vRel[1] + vRel[2] * vRel[2]);
            deltaAz = deltaDop * (lambda / (2.0 * relativeVelocity));
            if (Math.abs(deltaDop + prevDeltaDop) < 0.000001) {
                deltaAz /= 2.0;
            }

            if (Math.abs(deltaAz * slant) < 0.1) {
                break;
            }

            yaw += deltaAz;

            if (++iterations > max_iter) {
                break;
            }

            prevDeltaDop = deltaDop;
        }
        lookYaw[0] = look[0];
        lookYaw[1] = yaw;
    }

    private boolean getLook(final stateVector v, final double slant, final double yaw, final double[] plook) {

        final GeoCoding geoCoding = sourceProduct.getSceneGeoCoding();
        if (geoCoding == null) {
            throw new OperatorException("GeoCoding is null");
        }

        final GeoPos geoPos = geoCoding.getGeoPos(new PixelPos(0, 0), null);
        final double earthRadius = computeEarthRadius(geoPos);

        final double ht = Math.sqrt(v.xPos * v.xPos + v.yPos * v.yPos + v.zPos * v.zPos);

        double look = FastMath.acos((ht * ht + slant * slant - earthRadius * earthRadius) / (2.0 * slant * ht));

        for (int iter = 0; iter < 100; iter++) {

            double delta_range = slant - calcRange(v, look, yaw);
            if (Math.abs(delta_range) < 0.1) {
                plook[0] = look;
                return true;
            } else {
                double sininc = (ht / earthRadius) * FastMath.sin(look);
                double taninc = sininc / Math.sqrt(1 - sininc * sininc);
                look += delta_range / (slant * taninc);
            }
        }

        return false;
    }

    private static double calcRange(final stateVector v, final double look, final double yaw) {

        final double[][] rM = new double[3][3];
        getRotationMatrix(v, rM);

        final double cosyaw = FastMath.cos(yaw);
        final double x = FastMath.sin(yaw);
        final double y = -FastMath.sin(look) * cosyaw;
        final double z = -FastMath.cos(look) * cosyaw;

        final double rx = rM[0][0] * x + rM[1][0] * y + rM[2][0] * z;
        final double ry = rM[0][1] * x + rM[1][1] * y + rM[2][1] * z;
        final double rz = rM[0][2] * x + rM[1][2] * y + rM[2][2] * z;

        final double re = GeoUtils.WGS84.a;
        final double rp = re - re / GeoUtils.WGS84.b;
        final double re2 = re * re;
        final double rp2 = rp * rp;
        final double a = (rx * rx + ry * ry) / re2 + rz * rz / rp2;
        final double b = 2.0 * ((v.xPos * rx + v.yPos * ry) / re2 + v.zPos * rz / rp2);
        final double c = (v.xPos * v.xPos + v.yPos * v.yPos) / re2 + v.zPos * v.zPos / rp2 - 1.0;

        final double d = (b * b - 4.0 * a * c);
        if (d < 0) {
            return -1.0;
        }

        final double sqrtD = Math.sqrt(d);
        final double ans1 = (-b + sqrtD) / (2.0 * a);
        final double ans2 = (-b - sqrtD) / (2.0 * a);

        return Math.min(ans1, ans2);
    }

    private static void getRotationMatrix(final stateVector v, double[][] rM) {

        final PosVector az = new PosVector(v.xPos, v.yPos, v.zPos);
        final PosVector vl = new PosVector(v.xVel, v.yVel, v.zVel);

        Maths.normalizeVector(az);
        Maths.normalizeVector(vl);

        final PosVector ay = new PosVector();
        final PosVector ax = new PosVector();
        Maths.crossProduct(az, vl, ay);
        Maths.crossProduct(ay, az, ax);

        rM[0][0] = ax.x;
        rM[1][0] = ay.x;
        rM[2][0] = az.x;

        rM[0][1] = ax.y;
        rM[1][1] = ay.y;
        rM[2][1] = az.y;

        rM[0][2] = ax.z;
        rM[1][2] = ay.z;
        rM[2][2] = az.z;
    }

    private static double getDoppler(final stateVector v, final double look, final double yaw, final double[] relVel,
                                     final double lambda) {

        final double spx = v.xPos, spy = v.yPos, spz = v.zPos;
        final double svx = v.xVel, svy = v.yVel, svz = v.zVel;

        final double x = FastMath.sin(yaw);
        final double y = -FastMath.sin(look) * FastMath.cos(yaw);
        final double z = -FastMath.cos(look) * FastMath.cos(yaw);

        final double[][] rM = new double[3][3];
        getRotationMatrix(v, rM);

        double rpx = rM[0][0] * x + rM[1][0] * y + rM[2][0] * z;
        double rpy = rM[0][1] * x + rM[1][1] * y + rM[2][1] * z;
        double rpz = rM[0][2] * x + rM[1][2] * y + rM[2][2] * z;

        final double range = calcRange(v, look, yaw);

        rpx *= range;
        rpy *= range;
        rpz *= range;

        final double tpx = rpx + spx;
        final double tpy = rpy + spy;
        final double tpz = rpz + spz;

        final double tvx = -AngularVelocity * tpy;
        final double tvy = AngularVelocity * tpx;
        final double tvz = 0.0;

        final double rvx = tvx - svx;
        final double rvy = tvy - svy;
        final double rvz = tvz - svz;

        relVel[0] = rvx;
        relVel[1] = rvy;
        relVel[2] = rvz;

        return -2.0 / (lambda * range) * (rpx * rvx + rpy * rvy + rpz * rvz);
    }

    private static double getAngularVelocity() {
        final double dayLength = 24.0 * 60.0 * 60.0;
        return (366.225 / 365.225) * 2 * Constants.PI / dayLength;
    }

    /**
     * Compute Earth radius for pixel at (0,0).
     *
     * @param geoPos lat lon position
     * @return The Earth radius.
     */
    private static double computeEarthRadius(final GeoPos geoPos) {

        final double lat = geoPos.lat;
        final double re = Constants.semiMajorAxis;
        final double rp = Constants.semiMinorAxis;
        return (re * rp) / Math.sqrt(rp * rp * FastMath.cos(lat) * FastMath.cos(lat) + re * re * FastMath.sin(lat) * FastMath.sin(lat));
    }

    public static class stateVector {
        public double xPos;
        public double yPos;
        public double zPos;
        public double xVel;
        public double yVel;
        public double zVel;
        public double time;

        public stateVector(final double time, final double xPos, final double yPos, final double zPos,
                           final double xVel, final double yVel, final double zVel) {
            this.time = time;
            this.xPos = xPos;
            this.yPos = yPos;
            this.zPos = zPos;
            this.xVel = xVel;
            this.yVel = yVel;
            this.zVel = zVel;
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
            super(ALOSDeskewingOp.class);
        }
    }
}
