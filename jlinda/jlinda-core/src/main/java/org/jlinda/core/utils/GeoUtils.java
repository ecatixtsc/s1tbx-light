package org.jlinda.core.utils;

import org.jlinda.core.*;

public class GeoUtils {

    /* compute tile geocorners at height defined in float[4] array */
    public static GeoPoint[] computeCorners(
            final SLCImage meta, final Orbit orbit, final Window tile, final float height[]) throws Exception {

        if (height.length != 4) {
            throw new IllegalArgumentException("input height array has to have 4 elements");
        }

        GeoPoint[] corners = new GeoPoint[2];
        double[] phiAndLambda;

        final double l0 = tile.linelo;
        final double lN = tile.linehi;
        final double p0 = tile.pixlo;
        final double pN = tile.pixhi;

        // compute Phi, Lambda for Tile corners
        phiAndLambda = orbit.lph2ell(new Point(l0, p0, height[0]), meta);
        final double phi_l0p0 = phiAndLambda[0];
        final double lambda_l0p0 = phiAndLambda[1];

        phiAndLambda = orbit.lph2ell(new Point(lN, p0, height[1]), meta);
        final double phi_lNp0 = phiAndLambda[0];
        final double lambda_lNp0 = phiAndLambda[1];

        phiAndLambda = orbit.lph2ell(new Point(lN, pN, height[2]), meta);
        final double phi_lNpN = phiAndLambda[0];
        final double lambda_lNpN = phiAndLambda[1];

        phiAndLambda = orbit.lph2ell(new Point(l0, pN, height[3]), meta);
        final double phi_l0pN = phiAndLambda[0];
        final double lambda_l0pN = phiAndLambda[1];

        //// Select DEM values based on rectangle outside l,p border ////
        // phi
        double phiMin = Math.min(Math.min(Math.min(phi_l0p0, phi_lNp0), phi_lNpN), phi_l0pN);
        double phiMax = Math.max(Math.max(Math.max(phi_l0p0, phi_lNp0), phi_lNpN), phi_l0pN);
        // lambda
        double lambdaMin = Math.min(Math.min(Math.min(lambda_l0p0, lambda_lNp0), lambda_lNpN), lambda_l0pN);
        double lambdaMax = Math.max(Math.max(Math.max(lambda_l0p0, lambda_lNp0), lambda_lNpN), lambda_l0pN);

        corners[0] = new GeoPoint((float) (phiMax * Constants.RTOD), (float) (lambdaMin * Constants.RTOD));
        corners[1] = new GeoPoint((float) (phiMin * Constants.RTOD), (float) (lambdaMax * Constants.RTOD));

        return corners;
    }

    /* compute input tile geocorners on ellipsoid */
    public static GeoPoint[] computeCorners(final SLCImage meta, final Orbit orbit, final Window tile) throws Exception {
        final float height[] = new float[4];
        return computeCorners(meta, orbit, tile, height);
    }

    public static GeoPoint[] extendCorners(final GeoPoint extraGeo, final GeoPoint[] inGeo) {

        if (inGeo.length != 2) {
            throw new IllegalArgumentException("Input GeoPoint[] array has to have exactly 2 elements");
        }

        GeoPoint[] outGeo = new GeoPoint[inGeo.length];

        outGeo[0] = new GeoPoint();
        outGeo[0].lat = inGeo[0].lat + extraGeo.lat;
        outGeo[0].lon = inGeo[0].lon - extraGeo.lon;

        outGeo[1] = new GeoPoint();
        outGeo[1].lat = inGeo[1].lat - extraGeo.lat;
        outGeo[1].lon = inGeo[1].lon + extraGeo.lon;

        return outGeo;
    }

    public static GeoPoint defineExtraPhiLam(
            final double heightMin, final double heightMax, final Window window, final SLCImage meta, final Orbit orbit)
            throws Exception {

        // compute Phi, Lambda for Tile corners
        final double midPix = (window.pixlo + window.pixhi) / 2.0;
        final double midLin = (window.linelo + window.linehi) / 2.0;
        final double[] latLonMin = orbit.lph2ell(midLin, midPix, heightMin, meta);
        final double[] latLonMax = orbit.lph2ell(midLin, midPix, heightMax, meta);
        float lonExtra = (float) (Math.abs(latLonMin[1] - latLonMax[1]) * Constants.RTOD);
        float latExtra = (float) (Math.abs(latLonMin[0] - latLonMax[0]) * Constants.RTOD);

        return new GeoPoint(latExtra, lonExtra);
    }
}
