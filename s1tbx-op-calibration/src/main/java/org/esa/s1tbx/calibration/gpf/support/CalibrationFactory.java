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
package org.esa.s1tbx.calibration.gpf.support;

import org.esa.s1tbx.commons.SARGeocoding;
import org.esa.snap.core.datamodel.*;
import org.esa.snap.core.gpf.OperatorException;
import org.esa.snap.engine_utilities.datamodel.AbstractMetadata;

/**
 * The abstract base class for all calibration operators intended to be extended by clients.
 * The following methods are intended to be implemented or overridden:
 */
public class CalibrationFactory {

    public static Calibrator createCalibrator(Product sourceProduct) throws Exception {

        final MetadataElement absRoot = AbstractMetadata.getAbstractedMetadata(sourceProduct);
        if (absRoot == null) {
            throw new OperatorException("AbstractMetadata is null");
        }

//        if (absRoot.getAttribute(AbstractMetadata.abs_calibration_flag).getData().getElemBoolean()) {
//            throw new OperatorException("Absolute radiometric calibration has already been applied to the product");
//        }

        final String mission = absRoot.getAttributeString(AbstractMetadata.MISSION);

        Calibrator calibrator = CalibratorRegistry.getInstance().getCalibrator(mission);
        if(calibrator == null) {
            throw new OperatorException("Mission " + mission + " is currently not supported for calibration.");
        }

        return calibrator;
    }

    //================================== Create Sigma0, Gamma0 and Beta0 virtual bands ====================================

    /**
     * Create Sigma0 image as a virtual band using incidence angle from ellipsoid.
     */
    public static void createSigmaNoughtVirtualBand(final Product targetProduct, final String incidenceAngleForSigma0) {

        if (incidenceAngleForSigma0.contains(SARGeocoding.USE_PROJECTED_INCIDENCE_ANGLE_FROM_DEM)) {
            return;
        }

        final Band[] bands = targetProduct.getBands();
        for (Band trgBand : bands) {

            final String trgBandName = trgBand.getName();
            if (trgBand instanceof VirtualBand || !trgBandName.contains("Sigma0")) {
                continue;
            }

            String expression = null;
            String sigmaNoughtVirtualBandName = null;
            String description = null;

            if (incidenceAngleForSigma0.contains(SARGeocoding.USE_INCIDENCE_ANGLE_FROM_ELLIPSOID)) {

                expression = trgBandName +
                        "==" + trgBand.getNoDataValue() + '?' + trgBand.getNoDataValue() +
                        ':' + trgBandName + " / sin(projectedLocalIncidenceAngle * PI/180.0)" +
                        " * sin(incidenceAngleFromEllipsoid * PI/180)";

                sigmaNoughtVirtualBandName = trgBandName + "_use_inci_angle_from_ellipsoid";

                description = "Sigma0 image created using incidence angle from ellipsoid";

            } else if (incidenceAngleForSigma0.contains(SARGeocoding.USE_LOCAL_INCIDENCE_ANGLE_FROM_DEM)) {

                expression = trgBandName +
                        "==" + trgBand.getNoDataValue() + '?' + trgBand.getNoDataValue() +
                        ':' + trgBandName + " / sin(projectedLocalIncidenceAngle * PI/180.0)" +
                        " * sin(localIncidenceAngle * PI/180)";

                sigmaNoughtVirtualBandName = trgBandName + "_use_local_inci_angle_from_dem";

                description = "Sigma0 image created using local incidence angle from DEM";
            }

            final VirtualBand band = new VirtualBand(sigmaNoughtVirtualBandName,
                    ProductData.TYPE_FLOAT32,
                    trgBand.getRasterWidth(),
                    trgBand.getRasterHeight(),
                    expression);
            band.setUnit(trgBand.getUnit());
            band.setDescription(description);
            band.setNoDataValueUsed(true);
            targetProduct.addBand(band);
        }
    }

    /**
     * Create Gamma0 image as a virtual band.
     */
    public static void createGammaNoughtVirtualBand(Product targetProduct, String incidenceAngleForGamma0) {

        final Band[] bands = targetProduct.getBands();
        for (Band trgBand : bands) {

            final String trgBandName = trgBand.getName();
            if (trgBand instanceof VirtualBand || !trgBandName.contains("Sigma0")) {
                continue;
            }

            final String incidenceAngle;
            if (incidenceAngleForGamma0.contains(SARGeocoding.USE_INCIDENCE_ANGLE_FROM_ELLIPSOID)) {
                incidenceAngle = "incidenceAngleFromEllipsoid";
            } else if (incidenceAngleForGamma0.contains(SARGeocoding.USE_LOCAL_INCIDENCE_ANGLE_FROM_DEM)) {
                incidenceAngle = "localIncidenceAngle";
            } else { // USE_PROJECTED_INCIDENCE_ANGLE_FROM_DEM
                incidenceAngle = "projectedLocalIncidenceAngle";
            }

            final String expression = trgBandName +
                    "==" + trgBand.getNoDataValue() + '?' + trgBand.getNoDataValue() +
                    ':' + trgBandName + " / sin(projectedLocalIncidenceAngle * PI/180.0)" +
                    " * sin(" + incidenceAngle + " * PI/180)" + " / cos(" + incidenceAngle + " * PI/180)";

            String gammaNoughtVirtualBandName;
            String description;
            if (incidenceAngleForGamma0.contains(SARGeocoding.USE_INCIDENCE_ANGLE_FROM_ELLIPSOID)) {
                gammaNoughtVirtualBandName = "_use_inci_angle_from_ellipsoid";
                description = "Gamma0 image created using incidence angle from ellipsoid";
            } else if (incidenceAngleForGamma0.contains(SARGeocoding.USE_LOCAL_INCIDENCE_ANGLE_FROM_DEM)) {
                gammaNoughtVirtualBandName = "_use_local_inci_angle_from_dem";
                description = "Gamma0 image created using local incidence angle from DEM";
            } else { // USE_PROJECTED_INCIDENCE_ANGLE_FROM_DEM
                gammaNoughtVirtualBandName = "_use_projected_local_inci_angle_from_dem";
                description = "Gamma0 image created using projected local incidence angle from dem";
            }

            if (trgBandName.contains("_HH")) {
                gammaNoughtVirtualBandName = "Gamma0_HH" + gammaNoughtVirtualBandName;
            } else if (trgBandName.contains("_VV")) {
                gammaNoughtVirtualBandName = "Gamma0_VV" + gammaNoughtVirtualBandName;
            } else if (trgBandName.contains("_HV")) {
                gammaNoughtVirtualBandName = "Gamma0_HV" + gammaNoughtVirtualBandName;
            } else if (trgBandName.contains("_VH")) {
                gammaNoughtVirtualBandName = "Gamma0_VH" + gammaNoughtVirtualBandName;
            } else {
                gammaNoughtVirtualBandName = "Gamma0" + gammaNoughtVirtualBandName;
            }

            final VirtualBand band = new VirtualBand(gammaNoughtVirtualBandName,
                    ProductData.TYPE_FLOAT32,
                    trgBand.getRasterWidth(),
                                                     trgBand.getRasterHeight(),
                                                     expression);
            band.setUnit(trgBand.getUnit());
            band.setDescription(description);
            band.setNoDataValueUsed(true);
            targetProduct.addBand(band);
        }
    }

    /**
     * Create Beta0 image as a virtual band.
     */
    public static void createBetaNoughtVirtualBand(final Product targetProduct) {

        final Band[] bands = targetProduct.getBands();
        for (Band trgBand : bands) {

            final String trgBandName = trgBand.getName();
            if (trgBand instanceof VirtualBand || !trgBandName.contains("Sigma0")) {
                continue;
            }

            final String expression = trgBandName +
                    "==" + trgBand.getNoDataValue() + '?' + trgBand.getNoDataValue() +
                    ':' + trgBandName + " / sin(projectedLocalIncidenceAngle * PI/180.0)";

            String betaNoughtVirtualBandName = trgBandName.replace("Sigma0", "Beta0");
            final VirtualBand band = new VirtualBand(betaNoughtVirtualBandName,
                    ProductData.TYPE_FLOAT32,
                    trgBand.getRasterWidth(),
                    trgBand.getRasterHeight(),
                    expression);
            band.setUnit(trgBand.getUnit());
            band.setDescription("Beta0 image");
            band.setNoDataValueUsed(true);
            targetProduct.addBand(band);
        }
    }
}
