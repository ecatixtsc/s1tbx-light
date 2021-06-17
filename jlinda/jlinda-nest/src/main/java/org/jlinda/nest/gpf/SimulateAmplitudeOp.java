package org.jlinda.nest.gpf;

import com.bc.ceres.core.ProgressMonitor;
import org.esa.snap.core.datamodel.*;
import org.esa.snap.core.dataop.dem.ElevationModel;
import org.esa.snap.core.dataop.dem.ElevationModelDescriptor;
import org.esa.snap.core.dataop.dem.ElevationModelRegistry;
import org.esa.snap.core.dataop.resamp.Resampling;
import org.esa.snap.core.gpf.Operator;
import org.esa.snap.core.gpf.OperatorException;
import org.esa.snap.core.gpf.OperatorSpi;
import org.esa.snap.core.gpf.Tile;
import org.esa.snap.core.gpf.annotations.OperatorMetadata;
import org.esa.snap.core.gpf.annotations.Parameter;
import org.esa.snap.core.gpf.annotations.SourceProduct;
import org.esa.snap.core.gpf.annotations.TargetProduct;
import org.esa.snap.core.util.ProductUtils;
import org.esa.snap.dem.dataio.FileElevationModel;
import org.esa.snap.engine_utilities.datamodel.AbstractMetadata;
import org.esa.snap.engine_utilities.datamodel.Unit;
import org.esa.snap.engine_utilities.gpf.OperatorUtils;
import org.esa.snap.engine_utilities.gpf.ReaderUtils;
import org.jblas.ComplexDoubleMatrix;
import org.jlinda.core.*;
import org.jlinda.core.Window;
import org.jlinda.core.geom.DemTile;
import org.jlinda.core.geom.SimAmpTile;
import org.jlinda.core.geom.ThetaTile;
import org.jlinda.core.utils.*;

import java.awt.*;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

@OperatorMetadata(alias = "SimulateAmplitude",
        category = "Radar/Interferometric/Products",
        authors = "Petar Marinkovic",
        version = "1.0",
        copyright = "Copyright (C) 2013 by PPO.labs",
        description = "Simulate amplitude based on DEM", internal = true)
public final class SimulateAmplitudeOp extends Operator {

    @SourceProduct
    private Product sourceProduct;

    @TargetProduct
    private Product targetProduct;

    @Parameter(interval = "(1, 10]",
            description = "Degree of orbit interpolation polynomial",
            defaultValue = "3",
            label = "Orbit Interpolation Degree")
    private int orbitDegree = 3;

    @Parameter(description = "The digital elevation model.",
            defaultValue = "SRTM 3Sec",
            label = "Digital Elevation Model")
    private String demName = "SRTM 3Sec";

    @Parameter(label = "External DEM")
    private File externalDEMFile = null;

    @Parameter(label = "DEM No Data Value", defaultValue = "0")
    private double externalDEMNoDataValue = 0;

    @Parameter(description = "Simulated amplitude band name.",
            defaultValue = "sim_amp",
            label = "Simulated Amplitude Phase Band Name")
    private String simAmpBandName = "sim_amp";

    private ElevationModel dem = null;
    private double demNoDataValue = 0;
    private double demSampling;

    // source maps
    private HashMap<Integer, CplxContainer> masterMap = new HashMap<>();
    private HashMap<Integer, CplxContainer> slaveMap = new HashMap<>();

    // target maps
    private HashMap<String, ProductContainer> targetMap = new HashMap<>();

    // operator tags
    private static final boolean CREATE_VIRTUAL_BAND = true;
    private static final String PRODUCT_NAME = "srd_ifgs";
    public static final String PRODUCT_TAG = "_ifg_srd";


    /**
     * Initializes this operator and sets the one and only target product.
     * <p>The target product can be either defined by a field of type {@link Product} annotated with the
     * {@link TargetProduct TargetProduct} annotation or
     * by calling {@link #setTargetProduct} method.</p>
     * <p>The framework calls this method after it has created this operator.
     * Any client code that must be performed before computation of tile data
     * should be placed here.</p>
     *
     * @throws OperatorException
     *          If an error occurs during operator initialisation.
     * @see #getTargetProduct()
     */
    @Override
    public void initialize() throws OperatorException {
        try {

            checkUserInput();
            constructSourceMetadata();
            constructTargetMetadata();
            createTargetProduct();

            // define DEM
            defineDEM();

        } catch (Exception e) {
            throw new OperatorException(e);
        }
    }

    private void defineDEM() throws IOException {

        final ElevationModelRegistry elevationModelRegistry = ElevationModelRegistry.getInstance();
        final ElevationModelDescriptor demDescriptor = elevationModelRegistry.getDescriptor(demName);

        if (demDescriptor == null) {
            throw new OperatorException("The DEM '" + demName + "' is not supported.");
        }

        Resampling resampling = Resampling.BILINEAR_INTERPOLATION;

        if (externalDEMFile != null) { // if external DEM file is specified by user
            dem = new FileElevationModel(externalDEMFile, resampling.getName(), externalDEMNoDataValue);
            demNoDataValue = externalDEMNoDataValue;
            demName = externalDEMFile.getPath();

        } else {
            dem = demDescriptor.createDem(resampling);
            if (dem == null)
                throw new OperatorException("The DEM '" + demName + "' has not been installed.");

            demNoDataValue = demDescriptor.getNoDataValue();

            demSampling = demDescriptor.getTileWidthInDegrees() * (1.0f / demDescriptor.getTileWidth()) * Constants.DTOR;
        }


    }

    private void checkUserInput() {
        // TODO: use jdoris input.coherence class to check user input
    }

    private void constructSourceMetadata() throws Exception {

        // define sourceMaster/sourceSlave name tags
        final String masterTag = "ifg";
        final String slaveTag = "dummy";

        // get sourceMaster & sourceSlave MetadataElement
        final MetadataElement masterMeta = AbstractMetadata.getAbstractedMetadata(sourceProduct);
        final String slaveMetadataRoot = AbstractMetadata.SLAVE_METADATA_ROOT;

        /* organize metadata */

        // put sourceMaster metadata into the masterMap
        metaMapPut(masterTag, masterMeta, sourceProduct, masterMap);

        // pug sourceSlave metadata into slaveMap
        MetadataElement[] slaveRoot = sourceProduct.getMetadataRoot().getElement(slaveMetadataRoot).getElements();
        for (MetadataElement meta : slaveRoot) {
            if (!meta.getName().equals(AbstractMetadata.ORIGINAL_PRODUCT_METADATA))
                metaMapPut(slaveTag, meta, sourceProduct, slaveMap);
        }

    }

    private void metaMapPut(final String tag,
                            final MetadataElement root,
                            final Product product,
                            final HashMap<Integer, CplxContainer> map) throws Exception {

        // TODO: include polarization flags/checks!
        // pull out band names for this product
        final String[] bandNames = product.getBandNames();
        final int numOfBands = bandNames.length;

        // map key: ORBIT NUMBER
        int mapKey = root.getAttributeInt(AbstractMetadata.ABS_ORBIT);

        // metadata: construct classes and define bands
        final String date = OperatorUtils.getAcquisitionDate(root);
        final SLCImage meta = new SLCImage(root, product);
        final Orbit orbit = new Orbit(root, orbitDegree);

        // TODO: resolve multilook factors
        meta.setMlAz(1);
        meta.setMlRg(1);

        Band bandReal = null;
        Band bandImag = null;

        for (int i = 0; i < numOfBands; i++) {
            String bandName = bandNames[i];
            if (bandName.contains(tag) && bandName.contains(date)) {
                final Band band = product.getBandAt(i);
                if (BandUtilsDoris.isBandReal(band)) {
                    bandReal = band;
                } else if (BandUtilsDoris.isBandImag(band)) {
                    bandImag = band;
                }
            }
        }
        try {
            map.put(mapKey, new CplxContainer(date, meta, orbit, bandReal, bandImag));
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void constructTargetMetadata() {

        for (Integer keyMaster : masterMap.keySet()) {

            CplxContainer master = masterMap.get(keyMaster);

            for (Integer keySlave : slaveMap.keySet()) {

                // generate name for product bands
                String productName = keyMaster.toString() + "_" + keySlave.toString();

                final CplxContainer slave = slaveMap.get(keySlave);
                final ProductContainer product = new ProductContainer(productName, master, slave, true);

                product.targetBandName_I = "i" + PRODUCT_TAG + "_" + master.date + "_" + slave.date;
                product.targetBandName_Q = "q" + PRODUCT_TAG + "_" + master.date + "_" + slave.date;

                product.masterSubProduct.name = simAmpBandName;
                product.masterSubProduct.targetBandName_I = simAmpBandName + "_" + master.date + "_" + slave.date;

                // put ifg-product bands into map
                targetMap.put(productName, product);

            }

        }
    }

    private void createTargetProduct() {

        targetProduct = new Product(PRODUCT_NAME,
                sourceProduct.getProductType(),
                sourceProduct.getSceneRasterWidth(),
                sourceProduct.getSceneRasterHeight());

        ProductUtils.copyProductNodes(sourceProduct, targetProduct);

        for (final Band band : targetProduct.getBands()) {
            targetProduct.removeBand(band);
        }

        for (String key : targetMap.keySet()) {

            String targetBandName_I = targetMap.get(key).targetBandName_I;
            String targetBandName_Q = targetMap.get(key).targetBandName_Q;
            targetProduct.addBand(targetBandName_I, ProductData.TYPE_FLOAT64);
            targetProduct.getBand(targetBandName_I).setUnit(Unit.REAL);
            targetProduct.addBand(targetBandName_Q, ProductData.TYPE_FLOAT64);
            targetProduct.getBand(targetBandName_Q).setUnit(Unit.IMAGINARY);

            final String tag0 = targetMap.get(key).sourceMaster.date;
            final String tag1 = targetMap.get(key).sourceSlave.date;
            if (CREATE_VIRTUAL_BAND) {
//                String countStr = "_" + PRODUCT_TAG + "_" + tag0 + "_" + tag1;
                String countStr = PRODUCT_TAG + "_" + tag0 + "_" + tag1;
                ReaderUtils.createVirtualIntensityBand(targetProduct, targetProduct.getBand(targetBandName_I), targetProduct.getBand(targetBandName_Q), countStr);
                ReaderUtils.createVirtualPhaseBand(targetProduct, targetProduct.getBand(targetBandName_I), targetProduct.getBand(targetBandName_Q), countStr);
            }

            if (targetMap.get(key).subProductsFlag) {
                String simAmpBandName = targetMap.get(key).masterSubProduct.targetBandName_I;
                targetProduct.addBand(simAmpBandName, ProductData.TYPE_FLOAT32);
                targetProduct.getBand(simAmpBandName).setNoDataValue(demNoDataValue);
                targetProduct.getBand(simAmpBandName).setUnit(Unit.AMPLITUDE);
                targetProduct.getBand(simAmpBandName).setDescription("simulated_amplitude");
            }
        }

        // For testing: the optimal results with 1024x1024 pixels tiles, not clear whether it's platform dependent?
        // targetProduct.setPreferredTileSize(512, 512);

    }

    /**
     * Called by the framework in order to compute a tile for the given target band.
     * <p>The default implementation throws a runtime exception with the message "not implemented".</p>
     *
     * @param targetTileMap   The target tiles associated with all target bands to be computed.
     * @param targetRectangle The rectangle of target tile.
     * @param pm              A progress monitor which should be used to determine computation cancelation requests.
     * @throws OperatorException
     *          If an error occurs during computation of the target raster.
     */
    @Override
    public void computeTileStack(Map<Band, Tile> targetTileMap, Rectangle targetRectangle, ProgressMonitor pm) throws OperatorException {
        try {

            int y0 = targetRectangle.y;
            int yN = y0 + targetRectangle.height - 1;
            int x0 = targetRectangle.x;
            int xN = targetRectangle.x + targetRectangle.width - 1;
            final Window tileWindow = new Window(y0, yN, x0, xN);

            Band simAmplitudeBand;
            Band targetBand_I;
            Band targetBand_Q;

            // TODO: smarter extension of search space : foreshortening extension? can I calculate how bit tile I
            // need (extra space) for the coverage, taking into the consideration only height of the tile?
            for (String ifgKey : targetMap.keySet()) {

                ProductContainer product = targetMap.get(ifgKey);

                /// get dem of tile ///

                // compute tile geo-corners ~ work on ellipsoid
                GeoPoint[] geoCorners = GeoUtils.computeCorners(product.sourceMaster.metaData,
                        product.sourceMaster.orbit,
                        tileWindow);

                // get corners as DEM indices
                PixelPos[] pixelCorners = new PixelPos[2];
                pixelCorners[0] = dem.getIndex(new GeoPos(geoCorners[0].lat, geoCorners[0].lon));
                pixelCorners[1] = dem.getIndex(new GeoPos(geoCorners[1].lat, geoCorners[1].lon));

                // get max/min height of tile ~ uses 'fast' GCP based interpolation technique
                double[] tileHeights = computeMaxHeight(pixelCorners, targetRectangle);

                // compute extra lat/lon for dem tile
                GeoPoint geoExtent = GeoUtils.defineExtraPhiLam(tileHeights[0], tileHeights[1],
                        tileWindow, product.sourceMaster.metaData, product.sourceMaster.orbit);

                // extend corners
                geoCorners = GeoUtils.extendCorners(geoExtent, geoCorners);

                // update corners
                pixelCorners[0] = dem.getIndex(new GeoPos(geoCorners[0].lat, geoCorners[0].lon));
                pixelCorners[1] = dem.getIndex(new GeoPos(geoCorners[1].lat, geoCorners[1].lon));

                pixelCorners[0] = new PixelPos(Math.ceil(pixelCorners[0].x), Math.floor(pixelCorners[0].y));
                pixelCorners[1] = new PixelPos(Math.floor(pixelCorners[1].x), Math.ceil(pixelCorners[1].y));

                GeoPos upperLeftGeo = dem.getGeoPos(pixelCorners[0]);

                int nLatPixels = (int) Math.abs(pixelCorners[1].y - pixelCorners[0].y);
                int nLonPixels = (int) Math.abs(pixelCorners[1].x - pixelCorners[0].x);

                int startX = (int) pixelCorners[0].x;
                int endX = startX + nLonPixels;
                int startY = (int) pixelCorners[0].y;
                int endY = startY + nLatPixels;

                double[][] elevation = new double[nLatPixels][nLonPixels];
                for (int y = startY, i = 0; y < endY; y++, i++) {
                    for (int x = startX, j = 0; x < endX; x++, j++) {
                        try {
                            double elev = dem.getSample(x, y);
                            if (Double.isNaN(elev))
                                elev = demNoDataValue;
                            elevation[i][j] = elev;
                        } catch (Exception e) {
                            elevation[i][j] = demNoDataValue;
                        }
                    }
                }

                DemTile demTile = new DemTile(upperLeftGeo.lat * Constants.DTOR, upperLeftGeo.lon * Constants.DTOR,
                        nLatPixels, nLonPixels, demSampling, demSampling, (long) demNoDataValue);
                demTile.setData(elevation);

                final ThetaTile thetaTile = new ThetaTile(product.sourceMaster.metaData, product.sourceMaster.orbit, tileWindow, demTile);
                thetaTile.radarCode();
                thetaTile.gridData();

                final SimAmpTile simAmpTile = new SimAmpTile(product.sourceMaster.metaData, product.sourceMaster.orbit, tileWindow, thetaTile, demTile);
                simAmpTile.simulateAmplitude();

                /// check out results from source ///
                Tile tileReal = getSourceTile(product.sourceMaster.realBand, targetRectangle);
                Tile tileImag = getSourceTile(product.sourceMaster.imagBand, targetRectangle);
                ComplexDoubleMatrix complexIfg = TileUtilsDoris.pullComplexDoubleMatrix(tileReal, tileImag);

                /// commit to target ///
                targetBand_I = targetProduct.getBand(product.targetBandName_I);
                Tile tileOutReal = targetTileMap.get(targetBand_I);
                TileUtilsDoris.pushDoubleMatrix(complexIfg.real(), tileOutReal, targetRectangle);

                targetBand_Q = targetProduct.getBand(product.targetBandName_Q);
                Tile tileOutImag = targetTileMap.get(targetBand_Q);
                TileUtilsDoris.pushDoubleMatrix(complexIfg.imag(), tileOutImag, targetRectangle);

                // check in also simulated amplitude
                simAmplitudeBand = targetProduct.getBand(product.masterSubProduct.targetBandName_I);
                Tile tileOutSimAmplitude = targetTileMap.get(simAmplitudeBand);
                TileUtilsDoris.pushDoubleArray2D(simAmpTile.simAmpArray, tileOutSimAmplitude, targetRectangle);

            }

        } catch (Exception e) {
            throw new OperatorException(e);
        }
    }


    private double[] computeMaxHeight(PixelPos[] corners, Rectangle rectangle) throws Exception {

        double[] heightArray = new double[2];

        // double square root : scales with the size of tile
        final int numberOfPoints = (int) (10 * Math.sqrt(Math.sqrt(rectangle.width * rectangle.height)));

        //System.out.println("numberOfPoints = " + numberOfPoints);

        // work with 1.75x size of tile
        double extendTimes = 1.75;
        int offsetX = (int) (extendTimes * rectangle.width);
        int offsetY = (int) (extendTimes * rectangle.height);

        // define window
        final Window window = new Window((long) (corners[0].y - offsetY), (long) (corners[1].y + offsetY),
                (long) (corners[0].x - offsetX), (long) (corners[1].x + offsetX));

        // distribute points
        final int[][] points = MathUtils.distributePoints(numberOfPoints, window);
        final ArrayList<Double> heights = new ArrayList();

        // then for number of extra points
        for (int[] point : points) {
            Double height = dem.getSample(point[1], point[0]);
            if (!Double.isNaN(height) && !height.equals(demNoDataValue)) {
                heights.add(height);
            }
        }

        // get max/min and add extra 30% to max height ~ just to be sure
        if (heights.size() > 2) {
            heightArray[0] = Collections.min(heights) * 0.70;
//            heightArray[1] = Collections.max(heights) * 1.25;
            heightArray[1] = Collections.max(heights) * 1.30;
        } else { // if nodatavalues return 0s ~ tile in the sea
            heightArray[0] = 0;
            heightArray[1] = 0;
        }

        return heightArray;
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
            super(SimulateAmplitudeOp.class);
        }
    }
}
