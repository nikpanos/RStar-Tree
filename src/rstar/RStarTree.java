package rstar;

import rstar.dto.PointDTO;
import rstar.dto.TreeDTO;
import rstar.interfaces.IDtoConvertible;
import rstar.interfaces.ISpatialQuery;
import rstar.spatial.HyperRectangle;
import rstar.spatial.SpatialComparator;
import rstar.spatial.SpatialPoint;
import util.Constants;

import java.io.File;
import java.io.FileNotFoundException;
import java.util.*;

import static java.util.Arrays.sort;

/**
 * User: Lokesh
 * Date: 3/4/12
 * Time: 1:29 AM
 */
public class RStarTree implements ISpatialQuery, IDtoConvertible {

    private int dimension;
    private int pagesize;
    private File saveFile;
    private StorageManager storage;
    private RStarNode root;
    private long rootPointer = -1;
    private ArrayList<Boolean> levelReinserts;

    private float _pointSearchResult = -1;
    private ArrayList<SpatialPoint> _rangeSearchResult;
    private int bestSortOrder = -1;

    public RStarTree() {
        init(Constants.TREE_FILE, Constants.DIMENSION, Constants.PAGESIZE);
    }

    public RStarTree(int dimension) {
        init(Constants.TREE_FILE, dimension, Constants.PAGESIZE);
    }

    public RStarTree(String saveFile, int dimension, int pagesize) {
        init(saveFile, dimension, pagesize);
    }

    private void init(String saveFile, int dimension, int pagesize){
        this.dimension = dimension;
        this.pagesize = pagesize;
        this.saveFile = new File(saveFile);
        this.storage = new StorageManager();
        initStorage();
        setCapacities();
    }

    public RStarTree(String saveFile) {
        dimension = Constants.DIMENSION;
        pagesize = Constants.PAGESIZE;
        this.saveFile = new File(saveFile);
        this.storage = new StorageManager();
        initStorage();
        setCapacities();
    }

    private void initStorage() {
        loadTree();
        storage.createDataDir(saveFile);
    }

    private void setCapacities(){
        Constants.DIMENSION = dimension;
//        Constants.MAX_CHILDREN = Constants.PAGESIZE/8;          // M = (pagesize - mbr_size)/ (size of Long = 8)
//        Constants.MIN_CHILDREN = Constants.MAX_CHILDREN/3;      // m = M/3
        Constants.MAX_CHILDREN = 10;
        Constants.MIN_CHILDREN = 4;
    }

    /* QUERY FUNCTIONS */

    /**
     * inserts a point in the tree and saves it on disk
     * @param point the point to be inserted
     * @return 1 if successful, else -1
     */
    @Override
    public int insert(SpatialPoint point) {
        System.out.println("inserting point with oid=" + point.getOid());
        RStarLeaf target = chooseSubtree(point);

        if (target.isNotFull()) {
            target.insert(point);
            storage.saveNode(target);
            //adjust root reference
            if (target.nodeId == rootPointer) {
                root = target;
            }
            adjustParentOf(target);
            return 1;
        } else {
            int status = treatLeafOverflow(target, point);
            return status;
        }
    }

    private void adjustParentOf(RStarNode target) {
        if (target.getNodeId() != rootPointer) {
            RStarNode parent = loadNode(target.getParentId());
            HyperRectangle mbr = parent.getMBR();
            mbr.update(target.getMBR());
            parent.mbr = mbr;
            storage.saveNode(parent);
            if (parent.getNodeId() == rootPointer) {
                root = parent;
            }
            adjustParentOf(parent);
        }
    }

    /**
     * inserts a RStar node in the node pointed by nodePointer
     * @param nodePointer pointer to node in which the given node
     *                    is to be inserted
     * @param nodeToInsert the node to be inserted
     * @return 1 of successful, else -1
     */
    private int insert(Long nodePointer, RStarNode nodeToInsert) {
        storage.saveNode(nodeToInsert);
        RStarInternal target = (RStarInternal) loadNode(nodePointer);

        if (target.isNotFull()) {
            target.insert(nodeToInsert);

            if (target.nodeId == rootPointer) {
                root = target;
            }

            storage.saveNode(target);
            adjustParentOf(target);
            return 1;
        } else {
            return treatInternalOverflow(target, nodeToInsert);
        }
    }

    /**
     * finds the most appropriate leaf node to
     * insert the newPoint into
     * @param newPoint
     * @return RStarLeaf
     */
    private RStarLeaf chooseSubtree(SpatialPoint newPoint) {
        loadRoot();
        SpatialPoint[] temp = new SpatialPoint[1];
        temp[0] = newPoint;
        return _chooseSubtree(root, new HyperRectangle(dimension, temp));
    }

    private RStarLeaf _chooseSubtree(RStarNode startNode, HyperRectangle newMbr) {
        if(startNode.isLeaf()) {
            return (RStarLeaf)startNode;
        }

        else {
            ArrayList<Long> childPointers = startNode.childPointers;
            assert childPointers.size() > 0;
            ArrayList<RStarNode> children = new ArrayList<RStarNode>(childPointers.size());
            //load all children
            for (long childId : childPointers) {
                children.add(loadNode(childId));
            }

            //check whether children are leaves
            if (children.get(0).isLeaf()) {
                //check for least overlap increment
                ArrayList<Double> minOverlap = new ArrayList<Double>();
                // the candidate nodes for next recursive step
                ArrayList<RStarNode> cands = new ArrayList<RStarNode>();

                for (RStarNode child : children) {
                    HyperRectangle union = child.getMBR().union(newMbr);
                    //find union's overlap with all other children
                    double deltaOverlap = 0;

                    for (RStarNode otherChild : children) {
                        if (otherChild == child) {
                            continue;
                        }

                        deltaOverlap += union.overlap(otherChild.getMBR()) -
                                child.getMBR().overlap(otherChild.getMBR());

                    }

                    if (minOverlap.size() == 0) {
                        cands.add(child);
                        minOverlap.add(deltaOverlap);
                    } else {
                        if (minOverlap.get(0) > deltaOverlap) {
                            minOverlap.removeAll(minOverlap);
                            cands.removeAll(cands);
                            minOverlap.add(deltaOverlap);
                            cands.add(child);
                        }
                        else if (minOverlap.get(0) == deltaOverlap) {
                            minOverlap.add(deltaOverlap);
                            cands.add(child);
                        }
                    }
                }

                if(cands.size() == 1)
                    return _chooseSubtree(cands.get(0), newMbr);
                //break ties
                else{
                    ArrayList<Double> minAreas = new ArrayList<Double>();
                    ArrayList<RStarNode> cands2 = new ArrayList<RStarNode>();

                    double deltaV = 0;
                    for (RStarNode candNode : cands) {
                        deltaV = candNode.getMBR().deltaV_onInclusion(newMbr);
                        if(minAreas.size() == 0 || minAreas.get(0) > deltaV) {
                            minAreas.removeAll(minAreas);
                            cands2.removeAll(cands2);
                            minAreas.add(deltaV);
                            cands2.add(candNode);
                        }
                        else if (minAreas.get(0) == deltaV) {
                            minAreas.add(deltaV);
                            cands2.add(candNode);
                        }
                    }

                    if(cands2.size() == 1)
                        return _chooseSubtree(cands2.get(0), newMbr);
                    else {
                        //again break ties
                        double minArea = Double.MAX_VALUE;
                        RStarNode candidate = null;
                        for (RStarNode candNode : cands2) {
                            double vol = candNode.getMBR().volume();
                            if( vol < minArea ){
                                minArea = vol;
                                candidate = candNode;
                            }
                        }
                        return _chooseSubtree(candidate, newMbr);
                    }
                }
            } else {
                //check for least volume increment
                ArrayList<Double> minAreas = new ArrayList<Double>();
                ArrayList<RStarNode> cands = new ArrayList<RStarNode>();

                double deltaV = 0;
                for (RStarNode candNode : children) {
                    deltaV = candNode.getMBR().deltaV_onInclusion(newMbr);
                    if(minAreas.size() == 0 || minAreas.get(0) > deltaV) {
                        minAreas.removeAll(minAreas);
                        cands.removeAll(cands);
                        minAreas.add(deltaV);
                        cands.add(candNode);
                    }
                    else if (minAreas.get(0) == deltaV) {
                        minAreas.add(deltaV);
                        cands.add(candNode);
                    }
                }

                if(cands.size() == 1)
                    return _chooseSubtree(cands.get(0), newMbr);
                else {
                    //again break ties
                    double minArea = Double.MAX_VALUE;
                    RStarNode candidate = null;
                    for (RStarNode candNode : cands) {
                        double vol = candNode.getMBR().volume();
                        if( vol < minArea ){
                            minArea = vol;
                            candidate = candNode;
                        }
                    }
                    return _chooseSubtree(candidate, newMbr);
                }
            }
        }
    }

    private int treatLeafOverflow(RStarLeaf target, SpatialPoint point) {
        //TODO forced reinserts
        try {
            splitLeaf(target, point);
            return 1;
        } catch (AssertionError e) {
            return -1;
        }
    }

    private int treatInternalOverflow(RStarInternal fullNode, RStarNode newChild) {
        //TODO forced reinserts
        try {
            splitInternalNode(fullNode, newChild);
            return 1;
        } catch (AssertionError e) {
            return -1;
        }
    }

    /**
     * inserts point into and splits the target node
     * @param splittingLeaf
     * @param newPoint
     * @throws AssertionError when the target node does
     * not have any children
     */
    private void splitLeaf(RStarLeaf splittingLeaf, SpatialPoint newPoint) throws AssertionError{
        ArrayList<Long> childPointers = splittingLeaf.childPointers;
        if (childPointers.size() <= 0) {
            throw new AssertionError();
        }

        ArrayList<SpatialPoint> children = new ArrayList<SpatialPoint>(childPointers.size());
        //load all children
        for (long childId : childPointers) {
            PointDTO dto = storage.loadPoint(childId);
            children.add(new SpatialPoint(dto));
        }

        children.add(newPoint);
        int splitAxis = chooseLeafSplitAxis(children);
        int splitPoint = chooseLeafSplitpoint(children, splitAxis);

        Object[] sorting = children.toArray();
        final SpatialComparator comp = new SpatialComparator(splitAxis, bestSortOrder);
        sort(sorting, comp);

        splittingLeaf.loadedChildren = new ArrayList<SpatialPoint>();
        splittingLeaf.childPointers = new ArrayList<Long>();
        RStarLeaf newChild = new RStarLeaf(dimension);

        HyperRectangle newMbr1 = new HyperRectangle(dimension);     //adjusted mbr for splittingLeaf
        HyperRectangle newMbr2 = new HyperRectangle(dimension);     //adjusted mbr for newChild

        for (int i = 0; i < sorting.length; i++) {
            SpatialPoint spatialPoint = (SpatialPoint) sorting[i];
            if (i < splitPoint) {
                if (spatialPoint == newPoint) {
                    splittingLeaf.loadedChildren.add(spatialPoint);
                } else {
                    splittingLeaf.childPointers.add(childPointers.get(children.indexOf(spatialPoint)));
                }
                newMbr1.update(spatialPoint);
            } else {
                if (spatialPoint == newPoint) {
                    newChild.loadedChildren.add(spatialPoint);
                } else {
                    newChild.childPointers.add(childPointers.get(children.indexOf(spatialPoint)));
                }
                newMbr2.update(spatialPoint);
            }
        }
        splittingLeaf.mbr = newMbr1;
        newChild.mbr = newMbr2;

        storage.saveNode(splittingLeaf);
        if (splittingLeaf.getNodeId() == rootPointer) {
            //we just split root
            root = splittingLeaf;
            splitRoot(newChild);
        }else {
            newChild.setParentId(splittingLeaf.getParentId());
            insert(splittingLeaf.getParentId(), newChild);
        }
    }

    private void splitInternalNode(RStarInternal splittingNode, RStarNode node) {
        //load all children of target
        ArrayList<Long> childPointers = splittingNode.childPointers;
        if (childPointers.size() <= 0) {
            throw new AssertionError();
        }

         ArrayList<RStarNode> children = new ArrayList<RStarNode>(childPointers.size());
        //load all children
        for (long childNodeId : childPointers) {
            children.add(loadNode(childNodeId));
        }

        children.add(node);
        int splitAxis = chooseInternalSplitAxis(children);
        int splitPoint = chooseInternalSplitpoint(children, splitAxis);

        Object[] sorting = children.toArray();
        final SpatialComparator comp = new SpatialComparator(splitAxis, bestSortOrder);
        sort(sorting, comp);

        splittingNode.childPointers = new ArrayList<Long>();
        RStarInternal createdNode = new RStarInternal(dimension);

        HyperRectangle newMbr1 = new HyperRectangle(dimension);
        HyperRectangle newMbr2 = new HyperRectangle(dimension);

        for (int i = 0; i < sorting.length; i++) {
            RStarNode childNode = (RStarNode) sorting[i];
            if (i < splitPoint) {
                splittingNode.childPointers.add(childNode.getNodeId());
                childNode.setParentId(splittingNode.getNodeId());
                newMbr1.update(childNode.getMBR());
            } else {
                createdNode.childPointers.add(childNode.getNodeId());
                childNode.setParentId(createdNode.getNodeId());
                newMbr2.update(childNode.getMBR());
            }
            storage.saveNode(childNode);            //record the updates to disk
        }

        splittingNode.mbr = newMbr1;
        createdNode.mbr = newMbr2;

        storage.saveNode(splittingNode);
        if (splittingNode.getNodeId() == rootPointer) {
            //we just split root
            root = splittingNode;
            splitRoot(createdNode);
        } else {
            createdNode.setParentId(splittingNode.getParentId());
            insert(splittingNode.getParentId(), createdNode);
        }
    }

    private void splitRoot(RStarNode newChild) {
        RStarInternal newRoot = new RStarInternal(dimension);
        newRoot.setParentId(newRoot.getNodeId());
        newRoot.insert(root);
        newRoot.insert(newChild);
        storage.saveNode(root);
        storage.saveNode(newChild);
        storage.saveNode(newRoot);
        root = newRoot;
        rootPointer = newRoot.getNodeId();
    }

    /**
     * computes the split axis for the given list of entries
     * @param entries the points to be split
     * @return the index of the dimension perpendicular to which splitting
     * should be done
     */
    private int chooseLeafSplitAxis(final ArrayList<SpatialPoint> entries) {
        int splitAxis = 0;
        ArrayList<SpatialPoint> maxSorting = (ArrayList<SpatialPoint>) entries.clone();
        ArrayList<SpatialPoint> minSorting = (ArrayList<SpatialPoint>) entries.clone();

        // best value for total margin
        double minMargin = Double.MAX_VALUE;

        for (int i = 0; i < dimension; i++) {
            double margin = 0.0;
            // sort the entries according to their minimal and according to their maximal value
            final SpatialComparator compMin = new SpatialComparator(i, HyperRectangle.MIN_CORD);
            Collections.sort(minSorting, compMin);
            final SpatialComparator compMax = new SpatialComparator(i, HyperRectangle.MAX_CORD);
            Collections.sort(maxSorting, compMax);

            for (int k = 0; k <= (entries.size() - 2 * Constants.MIN_CHILDREN); k++) {
                HyperRectangle mbr1 = new HyperRectangle(dimension, minSorting.subList(0, Constants.MIN_CHILDREN + k));
                HyperRectangle mbr2 = new HyperRectangle(dimension, minSorting.subList(Constants.MIN_CHILDREN + k, entries.size()));

                margin += mbr1.margin() + mbr2.margin();

                mbr1 = new HyperRectangle(dimension, maxSorting.subList(0, Constants.MIN_CHILDREN + k));
                mbr2 = new HyperRectangle(dimension, maxSorting.subList(Constants.MIN_CHILDREN + k, entries.size()));
                margin += mbr1.margin() + mbr2.margin();
            }

            if (margin < minMargin) {
                splitAxis = i;
                minMargin = margin;
            }
        }
        return splitAxis;
    }

    private int chooseInternalSplitAxis(ArrayList<RStarNode> children) {
        int splitAxis = 0;
        ArrayList<RStarNode> maxSorting = (ArrayList<RStarNode>) children.clone();
        ArrayList<RStarNode> minSorting = (ArrayList<RStarNode>) children.clone();

        // best value for total margin
        double minMargin = Double.MAX_VALUE;

        for (int i = 0; i < dimension; i++) {
            double margin = 0.0;
            // sort the entries according to their minimal and according to their maximal value
            final SpatialComparator compMin = new SpatialComparator(i, HyperRectangle.MIN_CORD);
            Collections.sort(minSorting, compMin);
            final SpatialComparator compMax = new SpatialComparator(i, HyperRectangle.MAX_CORD);
            Collections.sort(maxSorting, compMax);

            for (int k = 0; k <= (children.size() - 2 * Constants.MIN_CHILDREN); k++) {
                HyperRectangle mbr1 = new HyperRectangle(dimension, minSorting.subList(0, Constants.MIN_CHILDREN + k));
                HyperRectangle mbr2 = new HyperRectangle(dimension, minSorting.subList(Constants.MIN_CHILDREN + k, children.size()));

                margin += mbr1.margin() + mbr2.margin();

                mbr1 = new HyperRectangle(dimension, maxSorting.subList(0, Constants.MIN_CHILDREN + k));
                mbr2 = new HyperRectangle(dimension, maxSorting.subList(Constants.MIN_CHILDREN + k, children.size()));
                margin += mbr1.margin() + mbr2.margin();
            }

            if (margin < minMargin) {
                splitAxis = i;
                minMargin = margin;
            }
        }
        return splitAxis;
    }

    /**
     * computes the split point for the given list of entries
     * it sets bestSort to 0 or 1 depending upon whether splitting should be done
     * according to maximal or minimal value for the given splitAxis
     * @param entries the points to be split
     * @return the split point
     */
    private int chooseLeafSplitpoint(final ArrayList<SpatialPoint> entries, final int splitAxis)
    {
        int splitPoint = 0;
        // numEntries
        int numEntries = entries.size();

        ArrayList<SpatialPoint> maxSorting = (ArrayList<SpatialPoint>) entries.clone();
        ArrayList<SpatialPoint> minSorting = (ArrayList<SpatialPoint>) entries.clone();

        // sort upper and lower in the right dimension
        final SpatialComparator compMin = new SpatialComparator(splitAxis, HyperRectangle.MIN_CORD);
        Collections.sort(minSorting, compMin);
        final SpatialComparator compMax = new SpatialComparator(splitAxis, HyperRectangle.MAX_CORD);
        Collections.sort(maxSorting, compMax);

        // the split point (first set to minimum entries in the node)
        splitPoint = Constants.MIN_CHILDREN;
        // best value for the overlap
        double minOverlap = Double.MAX_VALUE;
        // the volume of mbr1 and mbr2
        double volume = 0.0;
        int minEntries = Constants.MIN_CHILDREN;

        bestSortOrder = -1;

        for (int i = 0; i <= numEntries - 2 * minEntries; i++) {
            // test the sorting with respect to the minimal values
            HyperRectangle mbr1 = new HyperRectangle(dimension, minSorting.subList(0, minEntries + i));
            HyperRectangle mbr2 = new HyperRectangle(dimension, minSorting.subList(minEntries + i, entries.size()));

            double currentOverlap = mbr1.overlap(mbr2);
            if (currentOverlap < minOverlap || (currentOverlap == minOverlap && (mbr1.volume() + mbr2.volume()) < volume)) {
                minOverlap = currentOverlap;
                splitPoint = minEntries + i;
                bestSortOrder = HyperRectangle.MIN_CORD;
                volume = mbr1.volume() + mbr2.volume();
            }
            // test the sorting with respect to the maximal values
            mbr1 = new HyperRectangle(dimension, maxSorting.subList(0, minEntries + i));
            mbr2 = new HyperRectangle(dimension, maxSorting.subList(minEntries + i, entries.size()));

            currentOverlap = mbr1.overlap(mbr2);
            if (currentOverlap < minOverlap || (currentOverlap == minOverlap && (mbr1.volume() + mbr2.volume()) < volume)) {
                minOverlap = currentOverlap;
                splitPoint = minEntries + i;
                bestSortOrder = HyperRectangle.MAX_CORD;
                volume = mbr1.volume() + mbr2.volume();
            }
        }
        return splitPoint;
    }

    private int chooseInternalSplitpoint(ArrayList<RStarNode> children, int splitAxis) {
        int splitPoint = 0;
        // numEntries
        int numEntries = children.size();

        ArrayList<RStarNode> maxSorting = (ArrayList<RStarNode>) children.clone();
        ArrayList<RStarNode> minSorting = (ArrayList<RStarNode>) children.clone();

        // sort upper and lower in the right dimension
        final SpatialComparator compMin = new SpatialComparator(splitAxis, HyperRectangle.MIN_CORD);
        Collections.sort(minSorting, compMin);
        final SpatialComparator compMax = new SpatialComparator(splitAxis, HyperRectangle.MAX_CORD);
        Collections.sort(maxSorting, compMax);

        // the split point (first set to minimum entries in the node)
        splitPoint = Constants.MIN_CHILDREN;
        // best value for the overlap
        double minOverlap = Double.MAX_VALUE;
        // the volume of mbr1 and mbr2
        double volume = 0.0;
        int minEntries = Constants.MIN_CHILDREN;

        bestSortOrder = -1;

        for (int i = 0; i <= numEntries - 2 * minEntries; i++) {
            // test the sorting with respect to the minimal values
            HyperRectangle mbr1 = new HyperRectangle(dimension, minSorting.subList(0, minEntries + i));
            HyperRectangle mbr2 = new HyperRectangle(dimension, minSorting.subList(minEntries + i, children.size()));

            double currentOverlap = mbr1.overlap(mbr2);
            if (currentOverlap < minOverlap || (currentOverlap == minOverlap && (mbr1.volume() + mbr2.volume()) < volume)) {
                minOverlap = currentOverlap;
                splitPoint = minEntries + i;
                bestSortOrder = HyperRectangle.MIN_CORD;
                volume = mbr1.volume() + mbr2.volume();
            }
            // test the sorting with respect to the maximal values
            mbr1 = new HyperRectangle(dimension, maxSorting.subList(0, minEntries + i));
            mbr2 = new HyperRectangle(dimension, maxSorting.subList(minEntries + i, children.size()));

            currentOverlap = mbr1.overlap(mbr2);
            if (currentOverlap < minOverlap || (currentOverlap == minOverlap && (mbr1.volume() + mbr2.volume()) < volume)) {
                minOverlap = currentOverlap;
                splitPoint = minEntries + i;
                bestSortOrder = HyperRectangle.MAX_CORD;
                volume = mbr1.volume() + mbr2.volume();
            }
        }
        return splitPoint;
    }

    /**
     * searches for a spatial point in the tree and
     * returns its oid if its found.
     * @param point the point to be searched
     * @return oid of the point if found, else -1.
     */
    @Override
    public float pointSearch(SpatialPoint point) {
        _pointSearchResult = -1;
        loadRoot();
        _pointSearch(root, point);
        return _pointSearchResult;
    }

    private void _pointSearch(RStarNode start, SpatialPoint point) {
        HyperRectangle searchRegion = new HyperRectangle(point.getCords());
        HyperRectangle intersection = start.getMBR().getIntersection(searchRegion);

        if(intersection != null) {
            if (start.isLeaf()) {
                float[] searchPoints = point.getCords();

                //lazy loading of child points
                for (Long pointer : start.childPointers) {
                    PointDTO dto = storage.loadPoint(pointer);

                    float[] candidates = dto.coords;
                    boolean found = true;
                    for (int i = 0; i < candidates.length; i++) {
                        if (candidates[i] != searchPoints[i]){
                            found = false;
                            break;
                        }
                    }
                    if (found) {
                        _pointSearchResult = dto.oid;
                        break;
                    }
                }
            } else {
                for (Long pointer : start.childPointers) {
                    if(_pointSearchResult != -1)         // point found
                        break;

                    try {
                        RStarNode childNode = storage.loadNode(pointer);    //recurse down
                        _pointSearch(childNode, point);

                    } catch (FileNotFoundException e) {
                        System.err.println("Exception while loading node from disk. message = "+e.getMessage());
                    }
                }
            }
        }
    }

    /**
     * searches for points in the given range of the center point
     * @param center center point of the search region.
     * @param range radius of the search region.
     * @return ArrayList of all the points found in the range
     */
    @Override
    public List<SpatialPoint> rangeSearch(SpatialPoint center, double range) {
        System.out.println("searching in range " + range + " of point: " + center);

        float[] points = center.getCords();
        float[][] mbrPoints = new float[dimension][2];
        for (int i = 0; i < dimension; i++) {
            mbrPoints[i][0] = points[i] + (float) range;
            mbrPoints[i][1] = points[i] - (float) range;
        }
        HyperRectangle searchRegion = new HyperRectangle(dimension);
        searchRegion.setPoints(mbrPoints);

        _rangeSearchResult = new ArrayList<SpatialPoint>();
        loadRoot();
        _rangeSearch(root, searchRegion);
        return _rangeSearchResult;
    }

    private void _rangeSearch(RStarNode start, HyperRectangle searchRegion) {
        HyperRectangle intersection = start.getMBR().getIntersection(searchRegion);
        if (intersection != null) {
            if (start.isLeaf()) {
                for (Long pointer : start.childPointers) {
                    PointDTO dto = storage.loadPoint(pointer);
                    SpatialPoint spoint = new SpatialPoint(dto);
                    HyperRectangle pointMbr = new HyperRectangle(dto.coords);

                    if(pointMbr.getIntersection(searchRegion) != null)
                        _rangeSearchResult.add(spoint);
                }
            }
            else {
                for (Long pointer : start.childPointers) {
                    try {
                        RStarNode childNode = storage.loadNode(pointer);    //recurse down
                        _rangeSearch(childNode, searchRegion);

                    } catch (FileNotFoundException e) {
                        System.err.println("Exception while loading node from disk");
                    }
                }
            }
        }
    }

    /**
     * searches for the k nearest neighbours of a center point
     * @param center SpatialPoint
     * @param k number of nearest neighbours required
     * @return ArrayList of the k nearest neighbours of center.
     */
    @Override
    public List<SpatialPoint> knnSearch(SpatialPoint center, int k) {
        System.out.println("knn search with k = "+k+" and point: "+center);
        loadRoot();
        _knnSearch(root, center, k, 1);
        return _rangeSearchResult;
    }

    private void _knnSearch(RStarNode start, SpatialPoint center, int k, float range) {
        _rangeSearchResult = new ArrayList<SpatialPoint>();

        float[] points = center.getCords();
        float[][] mbrPoints = new float[dimension][2];
        for (int i = 0; i < dimension; i++) {
            mbrPoints[i][0] = points[i] + (float) range;
            mbrPoints[i][1] = points[i] - (float) range;
        }
        HyperRectangle searchRegion = new HyperRectangle(dimension);
        searchRegion.setPoints(mbrPoints);

        _rangeSearch(start, searchRegion);

        if (_rangeSearchResult.size() < k) {
            _knnSearch(start, center, k, 2 * range);
        } else {
            final SpatialPoint fcenter = center;
            Comparator<? super SpatialPoint> paramComparator = new Comparator<SpatialPoint>() {
                @Override
                public int compare(SpatialPoint point1, SpatialPoint point2) {
                    return (int) (fcenter.distance(point1) - fcenter.distance(point2));
                }
            };
            Collections.sort(_rangeSearchResult, paramComparator);
            _rangeSearchResult = (ArrayList<SpatialPoint>) _rangeSearchResult.subList(0, k);
        }
    }

    /*
     ***** DISK RELATED FUNCTIONS ****
     */

    /**
     * loads root from disk if exists
     * otherwise creates a new LeafNode and
     * assigns it root.
     */
    private void loadRoot() {
        if (root == null) {
            //empty tree
            root = loadNode(rootPointer);
            if (root == null)            // still null -> empty tree
            {
                root = new RStarLeaf(dimension);
                root.setParentId(root.getNodeId());
            }
            rootPointer = root.getNodeId();
        }
    }

    /**
     * loads Nodes from disk using their nodeId
     * @param nodeId the nodeId attribute of the Node
     *               to be loaded
     * @return the Node required, null uf it doesn't exist
     */
    private RStarNode loadNode(long nodeId) {
        //check for valid nodeId
        if (nodeId != -1) {
            try {
                if (nodeId == rootPointer) {
                    loadRoot();
                    return root;
                } else {
                    return storage.loadNode(nodeId);
                }
            } catch (FileNotFoundException e) {
                System.err.println("Error while loading R* Tree node from file " + storage.constructFilename(nodeId));
            }
        }
        return null;
    }

    /**
     * saves the tree details to disk
     * @return 1 if successful, -1 otherwise
     */
    public int save() {
        return storage.saveTree(this.toDTO(), saveFile);
    }

    /**
     * converts this tree to its DTO representation
     * which in turn can be saved to disk.
     * @return TreeDTO object which is the DTO form of
     * this tree
     */
    @Override
    public TreeDTO toDTO() {
        return new TreeDTO(dimension, Constants.PAGESIZE, rootPointer);
    }

    private void loadTree() {
        if (saveFile.exists() && saveFile.length() != 0) {
            try {
                TreeDTO treeData = storage.loadTree(saveFile);
                if (treeData != null) {             //update tree fields from saveFile
                    this.dimension = treeData.dimension;
                    this.pagesize = treeData.pagesize;
                    this.rootPointer = treeData.rootPointer;
                    System.out.printf("Tree loaded successfully from %s. dimension = %d and pagesize = %d bytes%n",
                            saveFile.getName(), dimension, pagesize);
                }
            } catch (FileNotFoundException e) {
                System.err.println("Failed to load R* Tree from "+saveFile.getName());
            }

        }
    }
}
