package org.jlinda.nest.gpf;

import com.bc.ceres.core.ProgressMonitor;
import org.esa.snap.core.datamodel.Band;
import org.esa.snap.core.datamodel.MetadataElement;
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
import org.esa.snap.engine_utilities.datamodel.AbstractMetadata;
import org.esa.snap.engine_utilities.datamodel.Unit;
import org.esa.snap.engine_utilities.gpf.OperatorUtils;
import org.jblas.DoubleMatrix;
import org.jlinda.core.Orbit;
import org.jlinda.core.SLCImage;
import org.jlinda.core.Window;
import org.jlinda.core.geocode.Slant2Height;
import org.jlinda.core.utils.BandUtilsDoris;
import org.jlinda.core.utils.CplxContainer;
import org.jlinda.core.utils.ProductContainer;
import org.jlinda.core.utils.TileUtilsDoris;

import java.awt.*;
import java.util.HashMap;

@OperatorMetadata(alias = "PhaseToHeight",
        category = "Radar/Interferometric/Products",
        authors = "Petar Marinkovic",
        version = "1.0",
        copyright = "Copyright (C) 2013 by PPO.labs",
        description = "Phase to Height conversion")
public class Slant2HeightOp extends Operator {

    @SourceProduct(description = "Source product that contains unwrapped phase.")
    private Product sourceProduct;

    @TargetProduct
    private Product targetProduct;

    @Parameter(valueSet = {"100", "200", "300", "400", "500"},
            description = "Number of points for evaluation of flat earth phase at different altitudes",
            defaultValue = "200",
            label = "Number of estimation points")
    private int nPoints; // where ref.phase is evaluated

    @Parameter(valueSet = {"2", "3", "4", "5"},
            description = "Number of height samples in range [0,5000)",
            defaultValue = "3",
            label = "Number of height samples")
    private int nHeights;

    @Parameter(valueSet = {"1", "2", "3", "4", "5"},
            description = "Degree of the 1D polynomial to fit reference phase through.",
            defaultValue = "2",
            label = "Degree of 1D polynomial")
    private int degree1D; // only possible now.

    @Parameter(valueSet = {"1", "2", "3", "4", "5", "6", "7", "8"},
            description = "Degree of the 2D polynomial to fit reference phase through.",
            defaultValue = "5",
            label = "Degree of 2D polynomial")
    private int degree2D; // only possible now.

    @Parameter(valueSet = {"2", "3", "4", "5"},
            description = "Degree of orbit (polynomial) interpolator",
            defaultValue = "3",
            label = "Orbit interpolation degree")
    private int orbitDegree = 3;

    // source maps
    private HashMap<Integer, CplxContainer> masterMap = new HashMap<>();
    private HashMap<Integer, CplxContainer> slaveMap = new HashMap<>();

    // target maps
    private HashMap<String, ProductContainer> targetMap = new HashMap<>();

    // classes map: for multi-band support
    private HashMap<String, Slant2Height> slant2HeightMap = new HashMap<>();

    // operator tags
    public static final String PRODUCT_TAG = "slant2h";
    private static final String PRODUCT_SUFFIX = "_Hgt";

    @Override
    public void initialize() throws OperatorException {

        try {
            // work out which is which product: loop through source product and check which product has a 'unwrapped phase' band
            sortOutSourceProducts();

            constructSourceMetadata();
            constructTargetMetadata();
            createTargetProduct();

            // does the math for slant2height conversion
            slant2Height();

        } catch (Exception e) {
            throw new OperatorException(e);
        }
    }

    private void slant2Height() throws Exception {

        int sourceImageWidth = sourceProduct.getSceneRasterWidth();
        int sourceImageHeight = sourceProduct.getSceneRasterHeight();
        for (Integer keyMaster : masterMap.keySet()) {
            CplxContainer master = masterMap.get(keyMaster);
            for (Integer keySlave : slaveMap.keySet()) {
                CplxContainer slave = slaveMap.get(keySlave);

                Slant2Height slant2Height = new Slant2Height(nPoints, nHeights, degree1D, degree2D,
                        master.metaData, master.orbit, slave.metaData, slave.orbit);
                slant2Height.setDataWindow(new Window(0, sourceImageHeight, 0, sourceImageWidth));
                slant2Height.schwabisch();

                slant2HeightMap.put(slave.date, slant2Height);
            }
        }
    }

    private void constructTargetMetadata() {

        for (Integer keyMaster : masterMap.keySet()) {

            CplxContainer master = masterMap.get(keyMaster);

            for (Integer keySlave : slaveMap.keySet()) {

                // generate name for product bands
                String productName = keyMaster.toString() + "_" + keySlave.toString();

                final CplxContainer slave = slaveMap.get(keySlave);
                final ProductContainer product = new ProductContainer(productName, master, slave, false);

                product.targetBandName_I = PRODUCT_TAG + "_" + master.date + "_" + slave.date;

                // put ifg-product bands into map
                targetMap.put(productName, product);
            }
        }
    }

    private void sortOutSourceProducts() {

        // check whether there are absolute phases bands in the product
        Band tempBand = null;
        for (Band band : sourceProduct.getBands()) {
            if (band.getUnit() != null && band.getUnit().equals(Unit.ABS_PHASE)) {
                tempBand = band;
            }
        }
        if (tempBand == null) {
            throw new OperatorException("Slant2HeightOp requires minimum one 'unwrapped' phase band");
        }
    }

    private void constructSourceMetadata() throws Exception {

        // define sourceMaster/sourceSlave name tags
        final String masterTag = "ifg";
        final String slaveTag = "ifg";
        final MetadataElement masterMeta = AbstractMetadata.getAbstractedMetadata(sourceProduct);
        final String slaveMetadataRoot = AbstractMetadata.SLAVE_METADATA_ROOT;
        MetadataElement[] slaveRoot;

        /* organize metadata */
        // put sourceMaster metadata into the masterMap
        metaMapPut(masterTag, masterMeta, sourceProduct, masterMap);

        // put sourceSlave metadata into slaveDefoMap
        slaveRoot = sourceProduct.getMetadataRoot().getElement(slaveMetadataRoot).getElements();
        for (MetadataElement meta : slaveRoot) {
            if (!meta.getName().equals(AbstractMetadata.ORIGINAL_PRODUCT_METADATA))
                metaMapPut(slaveTag, meta, sourceProduct, slaveMap);
        }
    }

    private void metaMapPut(final String tag,
                            final MetadataElement root,
                            final Product product,
                            final HashMap<Integer, CplxContainer> map) throws Exception {

        // pull out band names for this product
        final String[] bandNames = product.getBandNames();
        final int numOfBands = bandNames.length;

        // map key: ORBIT NUMBER
        int mapKey = root.getAttributeInt(AbstractMetadata.ABS_ORBIT);

        // metadata: construct classes and define bands
        final String date = OperatorUtils.getAcquisitionDate(root);
        final SLCImage meta = new SLCImage(root, product);
        final Orbit orbit = new Orbit(root, orbitDegree);

        // TODO: mlook factores are hard-coded for now
        meta.setMlAz(1);
        meta.setMlRg(1);

        Band bandReal = null;
        Band bandImag = null;

        for (int i = 0; i < numOfBands; i++) {
            String bandName = bandNames[i];
            if (bandName.contains(tag) && bandName.contains(date)) {
                final Band band = product.getBandAt(i);

                if (product.getBandAt(i).getUnit().contains(Unit.ABS_PHASE)) {
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

    private void createTargetProduct() {

        // construct target product
        targetProduct = new Product(sourceProduct.getName() + PRODUCT_SUFFIX,
                sourceProduct.getProductType(),
                sourceProduct.getSceneRasterWidth(),
                sourceProduct.getSceneRasterHeight());

        ProductUtils.copyProductNodes(sourceProduct, targetProduct);

        for (String key : targetMap.keySet()) {
            String bandName = targetMap.get(key).targetBandName_I;
            targetProduct.addBand(bandName, ProductData.TYPE_FLOAT32);
            targetProduct.getBand(bandName).setUnit(Unit.METERS);
        }
    }

    @Override
    public void computeTile(Band targetBand, Tile targetTile, ProgressMonitor pm) throws OperatorException {
        try {
            final Rectangle rect = targetTile.getRectangle();
            final int x0 = rect.x;
            final int y0 = rect.y;
            final int w = rect.width;
            final int h = rect.height;

            Window tileWindow = new Window(y0, y0 + h - 1, x0, x0 + w - 1);

            for (String absPhaseKey : targetMap.keySet()) {

                final ProductContainer product = targetMap.get(absPhaseKey);

                if (targetBand.getName().equals(product.targetBandName_I)) {

                    // check out from source
                    Tile tileRealMaster = getSourceTile(product.sourceMaster.realBand, rect);
                    final DoubleMatrix dataMaster = TileUtilsDoris.pullDoubleMatrix(tileRealMaster);// check out from source

                    // get class for this slave from the map
                    Slant2Height slant2Height = slant2HeightMap.get(product.sourceSlave.date);
                    slant2Height.applySchwabisch(tileWindow, dataMaster);

                    TileUtilsDoris.pushDoubleMatrix(dataMaster, targetTile, targetTile.getRectangle());
                }
            }

        } catch (Exception e) {
            throw new OperatorException(e);
        }
    }

    public static class Spi extends OperatorSpi {
        public Spi() {
            super(Slant2HeightOp.class);
        }
    }
}
