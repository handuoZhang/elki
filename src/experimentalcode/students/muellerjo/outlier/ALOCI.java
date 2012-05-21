package experimentalcode.students.muellerjo.outlier;

/*
 This file is part of ELKI:
 Environment for Developing KDD-Applications Supported by Index-Structures

 Copyright (C) 2012
 Ludwig-Maximilians-Universität München
 Lehr- und Forschungseinheit für Datenbanksysteme
 ELKI Development Team

 This program is free software: you can redistribute it and/or modify
 it under the terms of the GNU Affero General Public License as published by
 the Free Software Foundation, either version 3 of the License, or
 (at your option) any later version.

 This program is distributed in the hope that it will be useful,
 but WITHOUT ANY WARRANTY; without even the implied warranty of
 MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 GNU Affero General Public License for more details.

 You should have received a copy of the GNU Affero General Public License
 along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import de.lmu.ifi.dbs.elki.algorithm.AbstractAlgorithm;
import de.lmu.ifi.dbs.elki.algorithm.outlier.OutlierAlgorithm;
import de.lmu.ifi.dbs.elki.data.NumberVector;
import de.lmu.ifi.dbs.elki.data.type.TypeInformation;
import de.lmu.ifi.dbs.elki.data.type.TypeUtil;
import de.lmu.ifi.dbs.elki.database.Database;
import de.lmu.ifi.dbs.elki.database.datastore.DataStoreFactory;
import de.lmu.ifi.dbs.elki.database.datastore.DataStoreUtil;
import de.lmu.ifi.dbs.elki.database.datastore.WritableDoubleDataStore;
import de.lmu.ifi.dbs.elki.database.ids.DBID;
import de.lmu.ifi.dbs.elki.database.relation.MaterializedRelation;
import de.lmu.ifi.dbs.elki.database.relation.Relation;
import de.lmu.ifi.dbs.elki.distance.distancefunction.EuclideanDistanceFunction;
import de.lmu.ifi.dbs.elki.distance.distancefunction.NumberVectorDistanceFunction;
import de.lmu.ifi.dbs.elki.distance.distancevalue.NumberDistance;
import de.lmu.ifi.dbs.elki.logging.Logging;
import de.lmu.ifi.dbs.elki.logging.progress.FiniteProgress;
import de.lmu.ifi.dbs.elki.math.DoubleMinMax;
import de.lmu.ifi.dbs.elki.math.linearalgebra.Vector;
import de.lmu.ifi.dbs.elki.result.outlier.OutlierResult;
import de.lmu.ifi.dbs.elki.result.outlier.OutlierScoreMeta;
import de.lmu.ifi.dbs.elki.result.outlier.QuotientOutlierScoreMeta;
import de.lmu.ifi.dbs.elki.utilities.DatabaseUtil;
import de.lmu.ifi.dbs.elki.utilities.documentation.Description;
import de.lmu.ifi.dbs.elki.utilities.documentation.Reference;
import de.lmu.ifi.dbs.elki.utilities.documentation.Title;
import de.lmu.ifi.dbs.elki.utilities.optionhandling.AbstractParameterizer;
import de.lmu.ifi.dbs.elki.utilities.optionhandling.OptionID;
import de.lmu.ifi.dbs.elki.utilities.optionhandling.parameterization.Parameterization;
import de.lmu.ifi.dbs.elki.utilities.optionhandling.parameters.IntParameter;
import de.lmu.ifi.dbs.elki.utilities.optionhandling.parameters.ObjectParameter;
import de.lmu.ifi.dbs.elki.utilities.pairs.DoubleIntPair;
import de.lmu.ifi.dbs.elki.utilities.pairs.Pair;
import experimentalcode.students.muellerjo.index.ALOCIQuadTree;
import experimentalcode.students.muellerjo.index.AbstractALOCIQuadTreeNode;

/**
 * Fast Outlier Detection Using the "approximate Local Correlation Integral".
 * 
 * Outlier detection using multiple epsilon neighborhoods.
 * 
 * Based on: S. Papadimitriou, H. Kitagawa, P. B. Gibbons and C. Faloutsos:
 * LOCI: Fast Outlier Detection Using the Local Correlation Integral. In: Proc.
 * 19th IEEE Int. Conf. on Data Engineering (ICDE '03), Bangalore, India, 2003.
 * 
 * @author Jonathan von Bruenken
 * 
 * @param <O> Object type
 * @param <D> Distance type
 */
@Title("LOCI: Fast Outlier Detection Using the Local Correlation Integral")
@Description("Algorithm to compute outliers based on the Local Correlation Integral")
@Reference(authors = "S. Papadimitriou, H. Kitagawa, P. B. Gibbons, C. Faloutsos", title = "LOCI: Fast Outlier Detection Using the Local Correlation Integral", booktitle = "Proc. 19th IEEE Int. Conf. on Data Engineering (ICDE '03), Bangalore, India, 2003", url = "http://dx.doi.org/10.1109/ICDE.2003.1260802")
public class ALOCI<O extends NumberVector<O, ?>, D extends NumberDistance<D, ?>> extends AbstractAlgorithm<OutlierResult> implements OutlierAlgorithm {
  /**
   * The logger for this class.
   */
  private static final Logging logger = Logging.getLogger(ALOCI.class);

  /**
   * Parameter to specify the minimum neighborhood size
   */
  public static final OptionID NMIN_ID = OptionID.getOrCreateOptionID("loci.nmin", "Minimum neighborhood size to be considered.");

  /**
   * Parameter to specify the averaging neighborhood scaling.
   */
  public static final OptionID ALPHA_ID = OptionID.getOrCreateOptionID("loci.alpha", "Scaling factor for averaging neighborhood");

  /**
   * Parameter to specify the number of Grids to use.
   */
  public static final OptionID GRIDS_ID = OptionID.getOrCreateOptionID("loci.g", "The number of Grids to use.");

  /**
   * Holds the value of {@link #NMIN_ID}.
   */
  private int nmin;

  /**
   * Holds the value of {@link #ALPHA_ID}.
   */
  private int alpha;

  /**
   * Holds the value of {@link #GRIDS_ID}.
   */
  private int g;

  private Random random;

  private NumberVectorDistanceFunction<D> distFunc;

  /**
   * Constructor.
   * 
   * @param distanceFunction Distance function
   * @param rmax Maximum radius
   * @param nmin Minimum neighborhood size
   * @param alpha Alpha value
   * @param g Number of grids to use
   */
  public ALOCI(NumberVectorDistanceFunction<D> distanceFunction, int nmin, int alpha, int g) {
    super();
    this.distFunc = distanceFunction;
    this.nmin = nmin;
    this.alpha = alpha;
    this.g = g;
    // FIXME: make seed parameterizable and optional.
    this.random = new Random(0);
  }

  @Override
  public OutlierResult run(Database database) {
    Relation<O> relation = database.getRelation(getInputTypeRestriction()[0]);
    final int dim = DatabaseUtil.dimensionality(relation);
    FiniteProgress progressPreproc = logger.isVerbose() ? new FiniteProgress("aLOCI preprocessing", relation.size(), logger) : null;

    // Compute extend of dataset.
    double[] min, max;
    {
      Pair<O, O> hbbs = DatabaseUtil.computeMinMax(relation);
      double maxd = 0;
      min = new double[dim];
      max = new double[dim];
      for(int i = 0; i < dim; i++) {
        min[i] = hbbs.first.doubleValue(i + 1);
        max[i] = hbbs.second.doubleValue(i + 1);
        maxd = Math.max(maxd, max[i] - min[i]);
      }
      // Enlarge bounding box to have equal lengths.
      for(int i = 0; i < dim; i++) {
        double diff = (maxd - (max[i] - min[i])) / 2;
        min[i] -= diff;
        max[i] += diff;
      }
    }

    List<ALOCIQuadTree> qts = new ArrayList<ALOCIQuadTree>(g);
    List<double[]> shifts = new ArrayList<double[]>(g);

    ALOCIQuadTree qt = new ALOCIQuadTree(nmin, min, max);
    double[] nshift = new double[dim];
    qts.add(qt);
    shifts.add(nshift);
    /*
     * create the remaining g-1 shifted QuadTrees. This not clearly described in
     * the paper and therefore implemented in a way that achieves good results
     * with the test data.
     */
    for(int shift = 0; shift < g - 1; shift++) {
      double[] svec = new double[dim];
      for(int i = 0; i < dim; i++) {
        svec[i] = random.nextDouble() * (max[i] - min[i]);
      }
      qt = new ALOCIQuadTree(nmin, min, max);
      qts.add(qt);
      shifts.add(svec);
    }

    // Insert the database into the trees
    for(DBID id : relation.iterDBIDs()) {
      for(int i = 0; i < g; i++) {
        qts.get(i).insert(shiftObject(relation.get(id), shifts.get(i), min, max));
      }
      if(progressPreproc != null) {
        progressPreproc.incrementProcessed(logger);
      }
    }
    /*
     * Add alpha levels to the QuadTree (Sampling Neighborhood holds atLeast
     * nmin items. Therefore additional alpha-1 levels are needed to reach the
     * lowest Counting Neighborhood) The addLevel method adds these alpha-1
     * levels.
     */
    for(int i = 0; i < g; i++) {
      qts.get(i).addLevel(alpha);
    }
    if(progressPreproc != null) {
      progressPreproc.ensureCompleted(logger);
    }

    /*
     * aLoci Main Part
     */
    FiniteProgress progressLOCI = logger.isVerbose() ? new FiniteProgress("LOCI scores", relation.size(), logger) : null;
    WritableDoubleDataStore mdef_norm = DataStoreUtil.makeDoubleStorage(relation.getDBIDs(), DataStoreFactory.HINT_STATIC);
    WritableDoubleDataStore mdef_level = DataStoreUtil.makeDoubleStorage(relation.getDBIDs(), DataStoreFactory.HINT_STATIC);
    DoubleMinMax minmax = new DoubleMinMax();

    for(DBID id : relation.iterDBIDs()) {
      /*
       * Get the lowest Counting Neighborhood whose center is closest to the
       * Object getBestCountingNode can not be used in this case, as the the
       * lowest level is not necessarily the same across different QuadTrees.
       */
      Vector v = null;
      int qti = -1;
      AbstractALOCIQuadTreeNode cg = null;
      {
        final O obj = relation.get(id);
        for(int i = 0; i < g; i++) {
          Vector v2 = shiftObject(obj, shifts.get(i), min, max);
          AbstractALOCIQuadTreeNode cg2 = qts.get(i).getCountingGrid(v2);
          if(cg == null || distFunc.distance(cg.getCenter(), v).compareTo(distFunc.distance(cg2.getCenter(), v2)) > 0) {
            cg = cg2;
            v = v2;
            qti = i;
          }
        }
      }
      /*
       * Calculate the MDEF value for the Counting Neighborhood cg and the best
       * matching Sampling Neighborhood alpha levels over cg.
       */
      int level = cg.getLevel() - alpha;
      DoubleIntPair res = calculate_MDEF_norm(qts, cg, level, qti);
      double maxmdefnorm = res.first;
      double radius = res.second;
      /*
       * While the Sampling Neighborhood has not reached the root level,
       * calculate MDEF for these levels
       */
      while(level > 0) {
        level--;
        cg = getBestCountingNode(qts, cg.getParent(), v, qti);
        res = calculate_MDEF_norm(qts, cg, level, qti);
        if(maxmdefnorm < res.first) {
          maxmdefnorm = res.first;
          radius = res.second;
        }
      }
      mdef_norm.putDouble(id, maxmdefnorm);
      mdef_level.putDouble(id, radius);
      minmax.put(maxmdefnorm);
      if(progressLOCI != null) {
        progressLOCI.incrementProcessed(logger);
      }
    }
    if(progressLOCI != null) {
      progressLOCI.ensureCompleted(logger);
    }
    Relation<Double> scoreResult = new MaterializedRelation<Double>("aLOCI normalized MDEF", "aloci-mdef-outlier", TypeUtil.DOUBLE, mdef_norm, relation.getDBIDs());
    Relation<Double> levelResult = new MaterializedRelation<Double>("aLOCI Sampling Level", "aloci-sampling-level", TypeUtil.DOUBLE, mdef_level, relation.getDBIDs());
    OutlierScoreMeta scoreMeta = new QuotientOutlierScoreMeta(minmax.getMin(), minmax.getMax(), 0.0, Double.POSITIVE_INFINITY);
    OutlierResult result = new OutlierResult(scoreMeta, scoreResult);
    result.addChildResult(levelResult);
    return result;
  }

  private Vector shiftObject(O o, double[] nshift, double[] min, double[] max) {
    double[] v = new double[nshift.length];
    for(int i = 0; i < v.length; i++) {
      v[i] = nshift[i] + o.doubleValue(i + 1);
      if(v[i] > max[i]) {
        v[i] -= (max[i] - min[i]);
      }
    }
    return new Vector(v);
  }

  /**
   * Method for the MDEF calculation
   * 
   * @param qts List of (shifted) QuadTrees
   * @param cg Node containing the counting Neighborhood found to fit best for
   *        the Object
   * @param level Target Level of the Sampling Neighborhood
   * 
   * @return DoubleIntPair containing the MDEF Norm and the level of the
   *         sampling Neighborhood
   */

  private DoubleIntPair calculate_MDEF_norm(List<ALOCIQuadTree> qts, AbstractALOCIQuadTreeNode cg, int level, int qti) {
    // find the best Sampling Neighborhood for cg on the right level in qts
    AbstractALOCIQuadTreeNode sn = getBestSamplingNode(qts, cg, level, qti);
    // get the square sum of the counting neighborhoods box counts
    long sq = sn.getBoxCountSquareSum(alpha, qts.get(qti));
    double mdef_norm;
    /*
     * if the square sum is equal to box count of the sampling Neighborhood then
     * n_hat is equal one, and as cg needs to have at least one Element mdef
     * would get zero or lower than zero. This is the case when all of the
     * counting Neighborhoods contain one or zero Objects. Additionally, the
     * cubic sum, square sum and sampling Neighborhood box count are all equal,
     * which leads to sig_n_hat being zero and thus mdef_norm is either negative
     * infinite or undefined. As the distribution of the Objects seem quite
     * uniform, a mdef_norm value of zero ( = no outlier) is appropriate and
     * circumvents the problem of undefined values.
     */
    if(sq == sn.getBucketCount()) {
      return new DoubleIntPair(0.0, sn.getLevel());
    }
    // calculation of mdef according to the paper and standardization as done in
    // LOCI
    long cb = sn.getBoxCountCubicSum(alpha, qts.get(qti));
    double n_hat = (double) sq / (double) sn.getBucketCount();
    double sig_n_hat = java.lang.Math.sqrt(cb * sn.getBucketCount() - (sq * sq)) / sn.getBucketCount();
    // Avoid NaN - correct result 0.0?
    if(sig_n_hat < Double.MIN_NORMAL) {
      return new DoubleIntPair(0.0, sn.getLevel());
    }
    double mdef = n_hat - cg.getBucketCount();
    mdef_norm = mdef / sig_n_hat;
    return new DoubleIntPair(mdef_norm, sn.getLevel());
  }

  /**
   * Method for retrieving the best matching Sampling Node
   * 
   * @param qts List of (shifted) QuadTrees
   * @param cg Node containing the counting Neighborhood found to fit best for
   *        the Object
   * @param level Target Level of the Sampling Neighborhood
   */
  private AbstractALOCIQuadTreeNode getBestSamplingNode(List<ALOCIQuadTree> qts, AbstractALOCIQuadTreeNode cg, int level, int qti) {
    Vector center = cg.getCenter();
    /*
     * get the Sampling Node of cg in the same QuadTree as a base case This
     * choice is usually quite bad, but is always present.
     */
    AbstractALOCIQuadTreeNode sn = cg.getParent();
    while(sn.getLevel() != level) {
      sn = sn.getParent();
    }
    // look through the other QuadTrees and choose the one with the lowest
    // distance to the counting Neighborhoods center.
    for(int i = 0; i < g; i++) {
      if(i == qti) {
        continue;
      }
      // getSamplingNode(O int) returns null if there is no node containing the
      // coordinates given, or if the node does not have at least nmin Elements.
      AbstractALOCIQuadTreeNode sn2 = qts.get(i).getSamplingNode(center, level);
      if(sn2 == null) {
        continue;
      }
      if(distFunc.distance(center, sn.getCenter()).doubleValue() > distFunc.distance(center, sn2.getCenter()).doubleValue()) {
        sn = sn2;
      }
    }
    return sn;
  }

  /**
   * Method for retrieving the best matching counting Node
   * 
   * @param qts List of (shifted) QuadTrees
   * @param cg Node containing a possible counting Neighborhood
   * @param center location of the Object and therefore best possible center of
   *        the counting node
   */
  private AbstractALOCIQuadTreeNode getBestCountingNode(List<ALOCIQuadTree> qts, AbstractALOCIQuadTreeNode cg, Vector center, int qti) {
    AbstractALOCIQuadTreeNode cn = cg;
    // compare the other counting nodes at the right level in the QuadTrees and
    // choose the one closest to center.
    for(int i = 0; i < g; i++) {
      if(i == qti) {
        continue;
      }
      // getCountingNode(O, int) returns null if the Object has not reached the
      // right level in the Tree
      AbstractALOCIQuadTreeNode cn2 = qts.get(i).getCountingNode(center, cn.getLevel());
      if(cn2 == null) {
        continue;
      }
      if(distFunc.distance(center, cn.getCenter()).doubleValue() > distFunc.distance(center, cn2.getCenter()).doubleValue()) {
        cn = cn2;
      }
    }
    return cn;
  }

  @Override
  protected Logging getLogger() {
    return logger;
  }

  @Override
  public TypeInformation[] getInputTypeRestriction() {
    return TypeUtil.array(distFunc.getInputTypeRestriction());
  }

  /**
   * Parameterization class.
   * 
   * @author Erich Schubert
   * 
   * @apiviz.exclude
   */
  public static class Parameterizer<O extends NumberVector<O, ?>, D extends NumberDistance<D, ?>> extends AbstractParameterizer {
    protected int nmin = 0;

    protected int alpha = 4;

    protected int g = 1;

    private NumberVectorDistanceFunction<D> distanceFunction;

    @Override
    protected void makeOptions(Parameterization config) {
      super.makeOptions(config);

      ObjectParameter<NumberVectorDistanceFunction<D>> distanceFunctionP = makeParameterDistanceFunction(EuclideanDistanceFunction.class, NumberVectorDistanceFunction.class);
      if(config.grab(distanceFunctionP)) {
        distanceFunction = distanceFunctionP.instantiateClass(config);
      }

      final IntParameter nminP = new IntParameter(NMIN_ID, 20);
      if(config.grab(nminP)) {
        nmin = nminP.getValue();
      }

      final IntParameter g = new IntParameter(GRIDS_ID, 1);
      if(config.grab(g)) {
        this.g = g.getValue();
      }

      final IntParameter alphaP = new IntParameter(ALPHA_ID, 4);
      if(config.grab(alphaP)) {
        alpha = alphaP.getValue();
        if(alpha < 1) {
          alpha = 1;
        }
      }
    }

    @Override
    protected ALOCI<O, D> makeInstance() {
      return new ALOCI<O, D>(distanceFunction, nmin, alpha, g);
    }
  }
}