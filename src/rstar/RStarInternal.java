package rstar;

import rstar.dto.NodeDTO;
import rstar.interfaces.IRStarNode;
import rstar.spatial.HyperRectangle;
import rstar.spatial.SpatialPoint;
import util.Constants;

import java.util.ArrayList;

/**
 * User: Lokesh
 * Date: 3/4/12
 * Time: 2:55 AM
 */
public class RStarInternal extends RStarNode {
    private transient ArrayList<IRStarNode> children;

    public RStarInternal(int dimension) {
        _dimension = dimension;
        children = new ArrayList<IRStarNode>(Constants.MAX_CHILDREN);
        childPointers = new ArrayList<Long>(Constants.MAX_CHILDREN);
        mbr = new HyperRectangle(dimension);
    }

    public RStarInternal(NodeDTO dto, long nodeId) {
        this.nodeId = nodeId;
        this.childPointers = dto.children;
        //TODO MBR
    }

    @Override
    public boolean isLeaf() {
        return false;
    }

    @Override
    public boolean isNotFull() {
        return children.size() < Constants.MAX_CHILDREN;
    }

    @Override
    public <T> int insert(T newChild) {
        if (this.isNotFull() && newChild instanceof IRStarNode) {
            children.add((IRStarNode)newChild);
            mbr.update(((IRStarNode) newChild).getMBR());
            return 1;
        }
        else return -1;
    }

    @Override
    public HyperRectangle getMBR() {
        return mbr;
    }

    /*@Override
    public ArrayList<IRStarNode> getOverlappingChildren(HyperRectangle searchRegion) {
        //TODO getOverlappingChildren
        return children;
    }*/

    public long changeInVolume(SpatialPoint newPoint) {
        HyperRectangle pointmbr = new HyperRectangle(_dimension);
        pointmbr.update(newPoint);
        return mbr.deltaV_onInclusion(pointmbr);
    }

    @Override
    public NodeDTO toDTO() {
        return new NodeDTO(childPointers, mbr.toDTO(), false);
    }
}
